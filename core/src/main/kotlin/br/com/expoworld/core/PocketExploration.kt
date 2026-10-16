package br.com.expoworld.core

import kotlin.math.*

enum class PocketPurpose(val label: String) { EXPLORE("Explorar"), STROLL("Passear") }
data class PocketPoint(val latitude: Double, val longitude: Double) {
    init { require(latitude.isFinite() && longitude.isFinite() && abs(latitude) <= 85 && abs(longitude) <= 180) }
    fun fix(time: Double = 0.0) = Fix(latitude, longitude, 3.0, time)
    fun distance(other: PocketPoint) = Geo.distance(fix(), other.fix())
}
data class PocketRequest(val start: PocketPoint, val minutes: Int, val activity: ActivityKind,
    val purpose: PocketPurpose, val returnToStart: Boolean = true, val variation: Int = 0) {
    init { require(minutes in 10..180) }
    val budgetSeconds get() = minutes * 60.0
    val planningSpeed get() = when (activity) { ActivityKind.WALK -> 4.5; ActivityKind.BIKE -> 14.0; ActivityKind.CAR -> 24.0 }
}
data class PocketStep(val pointIndex: Int, val instruction: String, val meters: Double)
data class PocketPath(val points: List<PocketPoint>, val steps: List<PocketStep>, val distanceMeters: Double, val seconds: Double) {
    init {
        require(points.size in 2..20_000 && distanceMeters.isFinite() && distanceMeters in 1.0..500_000.0)
        require(seconds.isFinite() && seconds in 1.0..86_400.0)
        require(steps.all { it.pointIndex in points.indices && it.instruction.length <= 2000 && it.meters.isFinite() && it.meters >= 0 })
    }
}
data class PocketPlan(val id: String, val request: PocketRequest, val path: PocketPath,
    val estimatedNewMeters: Double?, val returning: Boolean = false)
data class PocketSavedPlan(val id: String, val title: String, val createdAtMillis: Long, val plan: PocketPlan)

object PocketPlanning {
    /** Loops include the home leg inside the budget. The provider supplies the final ETA. */
    fun waypoints(request: PocketRequest, candidate: Int, scale: Double = 1.0): List<PocketPoint> {
        val angle = Math.toRadians((request.variation * 137.508 + candidate * 120.0) % 360)
        val distance = request.budgetSeconds * request.planningSpeed / 3.6 * .88
        val radius = distance / (if (request.returnToStart) 4.2 else 1.45) * scale.coerceIn(.15, 4.0)
        fun offset(direction: Double): PocketPoint {
            val angular = radius / Geo.R; val lat = Math.toRadians(request.start.latitude); val lon = Math.toRadians(request.start.longitude)
            val targetLat = asin(sin(lat) * cos(angular) + cos(lat) * sin(angular) * cos(direction))
            val targetLon = lon + atan2(sin(direction) * sin(angular) * cos(lat), cos(angular) - sin(lat) * sin(targetLat))
            return PocketPoint(Math.toDegrees(targetLat).coerceIn(-85.0,85.0), ((Math.toDegrees(targetLon) + 540) % 360) - 180)
        }
        return if (request.returnToStart) listOf(request.start, offset(angle - .6), offset(angle + .6), request.start)
            else listOf(request.start, offset(angle))
    }
    fun fits(request: PocketRequest, path: PocketPath) = path.seconds <= request.budgetSeconds * 1.1 &&
        path.seconds >= request.budgetSeconds * .45 && path.points.first().distance(request.start) <= 100 &&
        (!request.returnToStart || path.points.last().distance(request.start) <= 100)
    fun score(request: PocketRequest, path: PocketPath, freshMeters: Double?): Double {
        val timeFit = 1 - abs(path.seconds - request.budgetSeconds * .9) / request.budgetSeconds
        val unique = path.points.zipWithNext().map { (a, b) ->
            val x = minOf(a.longitude,b.longitude); val y = minOf(a.latitude,b.latitude)
            (x * 10_000).roundToInt() to (y * 10_000).roundToInt()
        }.toSet().size.toDouble() / (path.points.size - 1)
        // Favor more new kilometers within the same time budget, not merely a high new/total percentage on a short route.
        val novelty = ((freshMeters ?: 0.0) / (request.budgetSeconds * request.planningSpeed / 3.6)).coerceIn(0.0,1.0)
        return if (request.purpose == PocketPurpose.EXPLORE) novelty * 3 + timeFit + unique * .3
            else timeFit + unique * .5 - path.steps.size / (path.distanceMeters / 1000).coerceAtLeast(1.0) * .01
    }
    /** Bounded decoder: corrupt provider/saved data must not create infinite loops or huge allocations. */
    fun decodePolyline6(encoded: String): List<PocketPoint> {
        require(encoded.length <= 400_000)
        var at = 0; var lat = 0L; var lon = 0L
        fun number(): Long {
            var value = 0L; var shift = 0
            while (true) {
                require(at < encoded.length && shift <= 35)
                val b = encoded[at++].code - 63; require(b in 0..63)
                value = value or ((b and 31).toLong() shl shift); shift += 5
                if (b < 32) return if (value and 1L != 0L) (value ushr 1).inv() else value ushr 1
            }
        }
        return buildList {
            while (at < encoded.length) {
                require(size < 20_000)
                lat += number(); lon += number()
                add(PocketPoint(lat / 1_000_000.0, lon / 1_000_000.0))
            }
            require(size >= 2)
        }
    }
    fun geoJson(plan: PocketPlan?): String = if (plan == null) "{\"type\":\"FeatureCollection\",\"features\":[]}"
        else plan.path.points.joinToString(",", "{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"properties\":{},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[", "]}}]}") { "[${it.longitude},${it.latitude}]" }
}

/** Progress stays on a nearby forward segment, so a loop's final origin cannot mark it complete at departure. */
class PocketProgress {
    private var id: String? = null
    private var cursor = 0
    fun next(plan: PocketPlan, point: PocketPoint): PocketStep? {
        if (id != plan.id) { id = plan.id; cursor = 0 }
        var best = cursor; var bestDistance = Double.POSITIVE_INFINITY; var ahead = 0.0
        for (i in cursor until plan.path.points.size) {
            if (i > cursor) ahead += plan.path.points[i-1].distance(plan.path.points[i])
            if (ahead > 300) break
            val distance = point.distance(plan.path.points[i])
            if (distance < bestDistance) { bestDistance = distance; best = i }
        }
        if (bestDistance <= 60) cursor = max(cursor,best)
        return plan.path.steps.firstOrNull { it.pointIndex > cursor } ?: plan.path.steps.lastOrNull()
    }
}
