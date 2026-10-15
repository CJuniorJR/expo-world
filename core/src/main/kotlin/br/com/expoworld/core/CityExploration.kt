package br.com.expoworld.core

import org.locationtech.jts.geom.*
import org.locationtech.jts.index.strtree.STRtree
import org.locationtech.jts.linearref.LengthIndexedLine
import kotlin.math.*

enum class ActivityKind(val key: String, val label: String, val mask: Int, val maxMetersSecond: Double,
    val brightKmh: Double, val stopKmh: Double, val simulationTimeScale: Double) {
    CAR("car", "Carro", 1, 55.0, 60.0, 3.0, 1.0),
    WALK("walk", "Caminhada", 2, 4.5, 8.0, .8, 12.0),
    BIKE("bike", "Bicicleta", 4, 18.0, 30.0, 1.5, 3.0);
    companion object { fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: CAR }
}

/** Local equidistant approximation shared with the offline generator (not Mercator meters). */
object CuritibaProjection {
    private val scaleY = Geo.R * PI / 180
    private val scaleX = scaleY * cos(Math.toRadians(-25.48))
    fun point(latitude: Double, longitude: Double) = Coordinate((longitude + 49.28) * scaleX, (latitude + 25.48) * scaleY)
    fun latitude(y: Double) = y / scaleY - 25.48
    fun longitude(x: Double) = x / scaleX - 49.28
}

data class CityNeighborhood(val id: String, val name: String, val boundary: Geometry)
class CityStreet(val id: String, val neighborhoodId: String, val modes: Int, val line: LineString) {
    val length = line.length
    val binCount = ceil(length / 10.0).toInt().coerceAtLeast(1)
    val binMeters = length / binCount
    val indexed = LengthIndexedLine(line)
}
data class StreetCredit(val streetId: String, val neighborhoodId: String, val bin: Int, val meters: Double)
/** Accumulate slow walking fixes instead of discarding every sub-three-meter step. */
class CoverageWindow {
    private var anchor: Fix? = null
    fun reset() { anchor = null }
    fun previousFor(fix: Fix, continuous: Boolean): Fix? {
        if (!continuous || fix.accuracy > 12) { anchor = fix.takeIf { it.accuracy <= 12 }; return null }
        val previous = anchor
        if (previous == null || fix.monotonicSeconds - previous.monotonicSeconds !in .01..12.0) { anchor = fix; return null }
        if (Geo.distance(previous, fix) < 3.0) return null
        anchor = fix
        return previous
    }
}
data class NeighborhoodProgress(val id: String, val name: String, val totalMeters: Double, val visitedMeters: Double) {
    val fraction get() = if (totalMeters > 0) (visitedMeters / totalMeters).coerceIn(0.0, 1.0) else 0.0
}
data class CityProgress(val version: String = "", val sourceDate: String = "", val activity: ActivityKind = ActivityKind.CAR,
    val neighborhoods: List<NeighborhoodProgress> = emptyList(), val currentNeighborhoodId: String? = null, val error: String? = null) {
    val totalMeters get() = neighborhoods.sumOf { it.totalMeters }
    val visitedMeters get() = neighborhoods.sumOf { it.visitedMeters }
}

class CityNetwork(val version: String, val sourceDate: String, val neighborhoods: List<CityNeighborhood>, val streets: List<CityStreet>) {
    private val geometryFactory = GeometryFactory()
    private val index = STRtree().apply { streets.forEach { insert(it.line.envelopeInternal, it) }; build() }
    fun neighborhoodAt(fix: Fix?): String? {
        if (fix == null) return null
        val point = geometryFactory.createPoint(CuritibaProjection.point(fix.latitude, fix.longitude))
        return neighborhoods.firstOrNull { it.boundary.covers(point) }?.id
    }
    fun progress(activity: ActivityKind, visited: Map<String, Double>, fix: Fix? = null): CityProgress {
        val totals = streets.asSequence().filter { it.modes and activity.mask != 0 }.groupingBy { it.neighborhoodId }
            .fold(0.0) { value, street -> value + street.length }
        return CityProgress(version, sourceDate, activity, neighborhoods.map { neighborhood ->
            val total = totals[neighborhood.id] ?: 0.0
            NeighborhoodProgress(neighborhood.id, neighborhood.name, total, (visited[neighborhood.id] ?: 0.0).coerceIn(0.0, total))
        }.sortedBy { it.name }, neighborhoodAt(fix))
    }
    /** Conservative local matching. Fog's 50m radius never grants street coverage. */
    fun match(previous: Fix?, fix: Fix, activity: ActivityKind): Set<StreetCredit> {
        if (previous == null || previous.accuracy > 12 || fix.accuracy > 12) return emptySet()
        if (previous.speedMetersPerSecond?.let { it <= .25 } == true && fix.speedMetersPerSecond?.let { it <= .25 } == true) return emptySet()
        val dt = fix.monotonicSeconds - previous.monotonicSeconds
        val a = CuritibaProjection.point(previous.latitude, previous.longitude)
        val b = CuritibaProjection.point(fix.latitude, fix.longitude)
        val distance = a.distance(b)
        if (dt !in .01..12.0 || distance !in 3.0..100.0 || distance / dt > activity.maxMetersSecond) return emptySet()
        val envelope = Envelope(a, b).apply { expandBy(12.0) }
        val candidates = index.query(envelope).filterIsInstance<CityStreet>().filter { it.modes and activity.mask != 0 }
        if (candidates.isEmpty()) return emptySet()
        val result = mutableSetOf<StreetCredit>()
        val steps = ceil(distance / 3.0).toInt()
        for (step in 0..steps) {
            val t = step.toDouble() / steps
            val point = Coordinate(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
            val matches = candidates.mapNotNull { street ->
                val along = street.indexed.project(point)
                val nearest = street.indexed.extractPoint(along)
                val error = nearest.distance(point)
                if (error > 12.0) return@mapNotNull null
                val start = street.indexed.extractPoint((along - 3).coerceAtLeast(0.0))
                val end = street.indexed.extractPoint((along + 3).coerceAtMost(street.length))
                val headingLength = start.distance(end)
                if (distance >= 6 && headingLength > .1) {
                    val alignment = abs((b.x - a.x) * (end.x - start.x) + (b.y - a.y) * (end.y - start.y)) / (distance * headingLength)
                    if (alignment < .7) return@mapNotNull null
                }
                Triple(street, along, error)
            }.sortedBy { it.third }
            val nearest = matches.firstOrNull() ?: continue
            // Don't award both nearby parallel streets or guess at ambiguous junctions.
            if (matches.size > 1 && matches[1].third - nearest.third < 3.0) continue
            val street = nearest.first
            val bin = (nearest.second / street.binMeters).toInt().coerceIn(0, street.binCount - 1)
            if (abs(nearest.second - (bin + .5) * street.binMeters) > min(2.0, street.binMeters / 2)) continue
            result.add(StreetCredit(street.id, street.neighborhoodId, bin, street.binMeters))
        }
        return result
    }
}
