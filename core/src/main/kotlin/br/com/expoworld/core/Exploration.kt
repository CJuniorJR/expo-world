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
    val speedMetersPerSecond: Double? = null, val bearingDegrees: Double? = null)
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
    /** Heading clockwise from north, matching Android Location and MapLibre. */
    fun bearing(from: Fix, to: Fix): Double {
        val p1 = Math.toRadians(from.latitude); val p2 = Math.toRadians(to.latitude)
        val dl = Math.toRadians(to.longitude - from.longitude)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
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
