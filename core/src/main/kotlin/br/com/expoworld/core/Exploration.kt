package br.com.expoworld.core

import kotlin.math.*

object ExplorationConfig {
    const val REVEAL_RADIUS_METERS = 50.0
    // Grid 10 m: database v3 expands old 20 m cells without losing coverage.
    const val CELL_PROJECTED_METERS = 10.0
    const val MAX_ACCURACY_METERS = 20.0
    const val MAX_SPEED_METERS_SECOND = 55.0
    const val MAX_GAP_SECONDS = 15.0
    const val MAX_SEGMENT_METERS = 500.0
    const val MAX_FIX_AGE_SECONDS = 10.0
    const val MAX_VIEW_CELLS = 60_000
}

data class Fix(val latitude: Double, val longitude: Double, val accuracy: Double, val monotonicSeconds: Double,
    val speedMetersPerSecond: Double? = null)
data class XY(val x: Double, val y: Double)
data class Cell(val x: Int, val y: Int)
data class CellBounds(val minX: Int, val maxX: Int, val minY: Int, val maxY: Int)

object Geo {
    const val R = 6378137.0
    const val MAX_LAT = 85.0
    fun project(lat: Double, lon: Double): XY = XY(
        R * Math.toRadians(lon),
        R * ln(tan(PI / 4 + Math.toRadians(lat.coerceIn(-MAX_LAT, MAX_LAT)) / 2))
    )
    fun latitude(y: Double) = Math.toDegrees(2 * atan(exp(y / R)) - PI / 2)
    fun longitude(x: Double) = Math.toDegrees(x / R)
    fun distance(a: Fix, b: Fix): Double {
        val p = Math.toRadians(a.latitude); val q = Math.toRadians(b.latitude)
        val h = sin((q - p) / 2).pow(2) + cos(p) * cos(q) * sin(Math.toRadians(b.longitude - a.longitude) / 2).pow(2)
        return 2 * R * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
    fun cell(point: XY) = Cell(floor(point.x / ExplorationConfig.CELL_PROJECTED_METERS).toInt(), floor(point.y / ExplorationConfig.CELL_PROJECTED_METERS).toInt())
    fun bounds(south: Double, west: Double, north: Double, east: Double): CellBounds {
        val a = cell(project(south, west)); val b = cell(project(north, east))
        return CellBounds(a.x - 1, b.x + 1, a.y - 1, b.y + 1)
    }
    fun viewportBounds(south: Double, west: Double, north: Double, east: Double): List<CellBounds> {
        val span = if (east < west) east - west + 360 else east - west
        if (span >= 360) return listOf(bounds(south, -180.0, north, 180.0))
        val normalizedWest = ((west + 180) % 360 + 360) % 360 - 180
        val unwrappedEast = normalizedWest + span
        return if (unwrappedEast <= 180) listOf(bounds(south, normalizedWest, north, unwrappedEast))
        else listOf(bounds(south, normalizedWest, north, 180.0), bounds(south, -180.0, north, unwrappedEast - 360))
    }
}

sealed interface FilterResult {
    data class Accepted(val fix: Fix, val previous: Fix?) : FilterResult
    data class Rejected(val message: String) : FilterResult
}

class FixFilter {
    private var anchor: Fix? = null
    fun breakContinuity() { anchor = null }
    fun accept(fix: Fix, nowSeconds: Double): FilterResult {
        if (!fix.latitude.isFinite() || !fix.longitude.isFinite() || abs(fix.latitude) > Geo.MAX_LAT || abs(fix.longitude) > 180 ||
            !fix.accuracy.isFinite() || fix.accuracy <= 0 || fix.accuracy > ExplorationConfig.MAX_ACCURACY_METERS ||
            !fix.monotonicSeconds.isFinite() || nowSeconds - fix.monotonicSeconds !in 0.0..ExplorationConfig.MAX_FIX_AGE_SECONDS) {
            breakContinuity()
            return FilterResult.Rejected("Aguardando GPS preciso (até 20 m). Trechos sem sinal não são conectados.")
        }
        val old = anchor
        if (old != null) {
            val dt = fix.monotonicSeconds - old.monotonicSeconds
            if (dt <= 0) return FilterResult.Rejected("Aguardando uma nova leitura do GPS.")
            val distance = Geo.distance(old, fix)
            val speeds = listOfNotNull(old.speedMetersPerSecond, fix.speedMetersPerSecond)
                .filter { it.isFinite() && it in 0.0..ExplorationConfig.MAX_SPEED_METERS_SECOND }
            // Doppler speed helps reject implausible coordinate jitter between
            // frequent fixes, allowing both accuracy envelopes and acceleration.
            val inconsistent = dt <= 2.0 && speeds.isNotEmpty() && distance >
                speeds.max() * dt + old.accuracy + fix.accuracy + 10.0 + 5.0 * dt * dt
            if (distance / dt > ExplorationConfig.MAX_SPEED_METERS_SECOND || inconsistent) {
                // Discard the jump itself, and require a new independent anchor.
                breakContinuity()
                return FilterResult.Rejected("Salto de GPS descartado; aguardando nova posição.")
            }
            anchor = fix
            val dateline = abs(fix.longitude - old.longitude) > 180
            return FilterResult.Accepted(fix, old.takeIf { dt <= ExplorationConfig.MAX_GAP_SECONDS && distance <= ExplorationConfig.MAX_SEGMENT_METERS && !dateline })
        }
        anchor = fix
        return FilterResult.Accepted(fix, null)
    }
}

object Revealer {
    /** Rasterizes a capsule in Mercator, correcting its radius by local latitude scale.
     * Cell-center sampling has <= 7.08 m boundary error (less away from the equator).
     * Inputs must have passed FixFilter; this guard also bounds per-fix work.
     */
    fun cells(fix: Fix, previous: Fix?): Set<Cell> {
        val a = Geo.project(fix.latitude, fix.longitude)
        val b = previous?.let { Geo.project(it.latitude, it.longitude) } ?: a
        require(previous == null || (Geo.distance(fix, previous) <= ExplorationConfig.MAX_SEGMENT_METERS && abs(fix.longitude - previous.longitude) <= 180))
        val scale = 1 / cos(Math.toRadians(max(abs(fix.latitude), abs(previous?.latitude ?: fix.latitude))))
        val radius = ExplorationConfig.REVEAL_RADIUS_METERS * scale
        val size = ExplorationConfig.CELL_PROJECTED_METERS
        val start = Geo.cell(XY(min(a.x, b.x) - radius, min(a.y, b.y) - radius))
        val end = Geo.cell(XY(max(a.x, b.x) + radius, max(a.y, b.y) + radius))
        val dx = b.x - a.x; val dy = b.y - a.y; val length2 = dx * dx + dy * dy
        val result = HashSet<Cell>()
        for (y in start.y..end.y) for (x in start.x..end.x) {
            val px = (x + .5) * size; val py = (y + .5) * size
            val t = if (length2 == 0.0) 0.0 else (((px - a.x) * dx + (py - a.y) * dy) / length2).coerceIn(0.0, 1.0)
            if (hypot(px - a.x - t * dx, py - a.y - t * dy) <= radius && abs(Geo.longitude(px)) <= 180 && abs(Geo.latitude(py)) <= Geo.MAX_LAT) result.add(Cell(x, y))
        }
        return result
    }
}

object SimulatedRoute {
    // Curitiba center: connected OSM geometry, not navigation/one-way guidance.
    // Marechal Floriano -> André de Barros -> Barão do Rio Branco -> Pedro Ivo.
    const val DEFAULT_LATITUDE = -25.4284
    const val DEFAULT_LONGITUDE = -49.2733
    private val path = listOf(
        -25.4338968 to -49.2705333, -25.4352631 to -49.2698546,
        -25.4348518 to -49.2688272, -25.4347609 to -49.2685999,
        -25.4345037 to -49.2679574, -25.4342507 to -49.2673266,
        -25.4341569 to -49.2673704, -25.4335399 to -49.2676583,
        -25.4329195 to -49.2679477, -25.4334503 to -49.2693522,
        -25.4335092 to -49.2695081, -25.4336156 to -49.2697911,
        -25.4337914 to -49.2702547, -25.4338968 to -49.2705333
    ).map { (lat, lon) -> Fix(lat, lon, 5.0, 0.0) }
    private val lengths = path.zipWithNext().map { (a, b) -> Geo.distance(a, b) }
    private val total = lengths.sum()
    private fun speed(second: Int): Double = when {
        second <= 25 -> 15.0
        second <= 40 -> 15.0 - (second - 25) * .8
        second <= 55 -> 3.0
        second <= 65 -> (3.0 - (second - 55) * .3).coerceAtLeast(0.0)
        second <= 85 -> 0.0
        second <= 105 -> (second - 85) * .6
        else -> 12.0
    }
    fun fix(index: Int, time: Double): Fix {
        val leg = index.coerceIn(0, LAST_INDEX)
        var distance = (1..leg).sumOf { speed(it) } % total
        var segment = 0
        while (segment < lengths.lastIndex && distance > lengths[segment]) { distance -= lengths[segment]; segment++ }
        val a = Geo.project(path[segment].latitude, path[segment].longitude)
        val b = Geo.project(path[segment + 1].latitude, path[segment + 1].longitude)
        val t = distance / lengths[segment]
        return Fix(Geo.latitude(a.y + (b.y - a.y) * t), Geo.longitude(a.x + (b.x - a.x) * t), 5.0, time, speed(leg))
    }
    const val LAST_INDEX = 120
}
