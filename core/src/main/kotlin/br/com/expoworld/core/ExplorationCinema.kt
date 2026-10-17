package br.com.expoworld.core

import java.util.Calendar
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory

enum class MapPresentation { NORMAL, COMPARE, EPOCHS, REPLAY }
data class CellDiscovery(val cell: Cell, val firstDiscoveredAtMillis: Long?)
data class TripDiscovery(val cell: Cell, val elapsedSeconds: Double)
enum class DiscoveryEpoch(val label: String, val color: String) {
    TRIP("Nesta viagem", "#FFD166"), MONTH("Neste mês", "#70E1BA"), OLDER("Antes deste mês", "#729ED9"), UNKNOWN("Data desconhecida", "#8A9298")
}
object DiscoveryHistory {
    fun monthStart(now: Long): Long = Calendar.getInstance().apply {
        timeInMillis = now; set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    fun epoch(date: Long?, inTrip: Boolean, now: Long, monthStart: Long): DiscoveryEpoch = when {
        inTrip -> DiscoveryEpoch.TRIP
        date == null || date <= 0 || date > now -> DiscoveryEpoch.UNKNOWN
        date >= monthStart -> DiscoveryEpoch.MONTH
        else -> DiscoveryEpoch.OLDER
    }
    fun geoJson(cells: List<CellDiscovery>, trip: Set<Cell>, now: Long): String {
        val start = monthStart(now)
        val features = cells.groupBy { epoch(it.firstDiscoveredAtMillis, it.cell in trip, now, start) }.flatMap { (epoch, group) ->
            CoverageGeometry.rows(group.map { it.cell }).map { b ->
                CoverageGeometry.geoJson(listOf(b)).substringAfter("\"features\":[").removeSuffix("]}")
                    .replace("\"properties\":{}", "\"properties\":{\"color\":\"${epoch.color}\"}")
            }
        }
        return features.joinToString(",", "{\"type\":\"FeatureCollection\",\"features\":[", "]}")
    }
}

/** Immutable viewport snapshot. Geometries are built off the UI thread and never stored. */
class ExplorationScene(cells: List<CellDiscovery>, private val trip: List<TripDiscovery>) {
    private val afterCells = cells.map { it.cell }.distinct()
    private val tripIds = trip.map { it.cell }.toSet()
    private val beforeCells = afterCells.filter { it !in tripIds }
    private val after by lazy { FogGeometry.geometry(afterCells) }
    private val delta by lazy { FogGeometry.geometry(beforeCells).difference(after) }
    val afterJson by lazy { FogGeometry.geoJson(after) }
    val canAnimateFog = afterCells.size <= 20_000 && trip.any { it.elapsedSeconds >= 0 }
    fun comparisonMask(clip: List<XY>): String {
        if (clip.size < 4 || clip.any { !it.x.isFinite() || !it.y.isFinite() } ||
            clip.maxOf { it.x } - clip.minOf { it.x } > 180) return FogGeometry.geoJson(emptyGeometry())
        val polygon = GeometryFactory().createPolygon((clip + clip.first()).map { Coordinate(it.x, it.y) }.toTypedArray())
        return FogGeometry.geoJson(delta.intersection(polygon))
    }
    private fun emptyGeometry() = GeometryFactory().createPolygon()
    fun replayCells(elapsed: Double): List<Cell> {
        val notYet = trip.filter { it.elapsedSeconds >= 0 && it.elapsedSeconds > elapsed }.map { it.cell }.toSet()
        return afterCells.filter { it !in notYet }
    }
    fun replayFog(elapsed: Double): String = if (!canAnimateFog) afterJson else FogGeometry.geoJson(replayCells(elapsed))
}

object ReplayTimeline {
    fun elapsed(samples: List<TraceSample>, fraction: Float): Double {
        val first = samples.firstOrNull()?.elapsedSeconds ?: return 0.0
        return first + ((samples.last().elapsedSeconds - first) * fraction.coerceIn(0f, 1f))
    }
    fun frame(samples: List<TraceSample>, fraction: Float): List<TraceSample> {
        if (samples.isEmpty()) return emptyList()
        val time = elapsed(samples, fraction)
        var low = 0; var high = samples.size
        while (low < high) { val mid = (low + high) / 2; if (samples[mid].elapsedSeconds <= time) low = mid + 1 else high = mid }
        val prefix = samples.subList(0, low.coerceAtLeast(1))
        val a = prefix.last(); val b = samples.getOrNull(low) ?: return prefix
        if (!a.accepted || !b.accepted || b.breakBefore || b.elapsedSeconds <= a.elapsedSeconds || time <= a.elapsedSeconds) return prefix
        val t = (time - a.elapsedSeconds) / (b.elapsedSeconds - a.elapsedSeconds)
        val lon = a.longitude + (b.longitude - a.longitude) * t
        if (kotlin.math.abs(b.longitude - a.longitude) > 180) return prefix
        val speed = if (a.speedKmh != null && b.speedKmh != null) a.speedKmh + (b.speedKmh - a.speedKmh) * t else null
        return TracePrefix(prefix, prefix.size, a.copy(latitude = a.latitude + (b.latitude - a.latitude) * t, longitude = lon,
            elapsedSeconds = time, speedKmh = speed, breakBefore = false))
    }
    fun visualSamples(samples: List<TraceSample>): List<TraceSample> {
        return samples // Replay must retain every passage, including short loops between GPS readings.
    }
}
