package br.com.expoworld.core

import kotlin.math.*

object TraceConfig {
    const val SAMPLE_INTERVAL_SECONDS = 5.0
    const val SAMPLE_DISTANCE_METERS = 35.0
    const val MAX_SAMPLES = 20_000
    const val STOP_SPEED_KMH = 3.0
    const val MIN_STOP_SECONDS = 5.0
    const val BRIGHT_SPEED_KMH = 60.0
}

data class TraceSample(
    val sequence: Int, val latitude: Double, val longitude: Double, val elapsedSeconds: Double,
    val speedKmh: Double?, val breakBefore: Boolean, val stopId: Int, val stopSeconds: Double
)
data class RecordedTrace(val startedAtMillis: Long, val endedAtMillis: Long, val samples: List<TraceSample>,
    val mode: String = "real", val completed: Boolean = true, val newAreaSquareMeters: Double? = null, val activity: ActivityKind = ActivityKind.CAR) {
    fun isInterrupted(active: Boolean) = !active && !completed && samples.isNotEmpty()
}
data class TraceStop(val latitude: Double, val longitude: Double, val durationSeconds: Double)

/** Frozen history changes only when another chunk fills; the recent tail is bounded. */
data class TraceWindow(val history: List<TraceSample>, val recent: List<TraceSample>)
object TraceChunks {
    const val SIZE = 256
    fun split(samples: List<TraceSample>): TraceWindow {
        if (samples.isEmpty()) return TraceWindow(emptyList(), emptyList())
        val boundary = ((samples.size - 1) / SIZE) * SIZE
        return TraceWindow(if (boundary == 0) emptyList() else samples.subList(0, boundary + 1),
            samples.subList(boundary, samples.size))
    }
    fun live(samples: List<TraceSample>, head: TraceSample?): List<TraceSample> =
        TracePresentation.withLiveHead(samples.takeLast(1), head)
}

/** Called only for fixes accepted by FixFilter. GPS speed is preferred; fallback
 * displacement uses a >=4-second window and requires accuracy <=10 m.
 * Unknown speed stays unknown. Gaps reset smoothing and stop durations.
 */
class TraceSampler(private val stopSpeedKmh: Double = TraceConfig.STOP_SPEED_KMH) {
    private val window = ArrayDeque<Fix>()
    private var origin: Double? = null
    private var lastTime: Double? = null
    private var smoothSpeed: Double? = null
    private var lastSample: TraceSample? = null
    private var latest: TraceSample? = null
    private var nextSequence = 0
    private var nextStopId = 0
    private var stopId = -1
    private var stopStart: Double? = null
    private var pendingBreak = true
    fun breakContinuity() { pendingBreak = true }
    fun accept(fix: Fix, continuous: Boolean): TraceSample? {
        val isBreak = pendingBreak || !continuous
        if (isBreak) {
            window.clear(); smoothSpeed = null; lastTime = null; stopStart = null; stopId = -1
        }
        pendingBreak = false
        val time = fix.monotonicSeconds
        if (origin == null) origin = time
        val elapsed = time - origin!!
        window.addLast(fix)
        while (window.size > 1 && time - window.first().monotonicSeconds > 6.0) window.removeFirst()
        val gpsSpeed = fix.speedMetersPerSecond?.takeIf { it.isFinite() && it in 0.0..ExplorationConfig.MAX_SPEED_METERS_SECOND }
        val first = window.first()
        val windowTime = time - first.monotonicSeconds
        val rawKmh = gpsSpeed?.times(3.6) ?: if (windowTime >= 4 && first.accuracy <= 10 && fix.accuracy <= 10) {
            val meters = Geo.distance(first, fix)
            if (meters <= 3) 0.0 else (meters / windowTime * 3.6).takeIf { it <= ExplorationConfig.MAX_SPEED_METERS_SECOND * 3.6 }
        } else null
        val dt = lastTime?.let { (time - it).coerceAtLeast(0.0) } ?: 0.0
        smoothSpeed = if (rawKmh == null) null else smoothSpeed?.let { old -> old + (1 - exp(-dt / 3.0)) * (rawKmh - old) } ?: rawKmh
        lastTime = time
        if (smoothSpeed != null && smoothSpeed!! <= stopSpeedKmh) {
            if (stopStart == null) { stopStart = time; stopId = nextStopId++ }
        } else { stopStart = null; stopId = -1 }
        val stopSeconds = stopStart?.let { time - it } ?: 0.0
        val old = lastSample
        val oldFix = old?.let { Fix(it.latitude, it.longitude, fix.accuracy, time) }
        val changedSpeed = old != null && ((old.speedKmh == null) != (smoothSpeed == null) ||
            (old.speedKmh != null && smoothSpeed != null && abs(old.speedKmh - smoothSpeed!!) >= 8))
        val newStop = stopSeconds >= TraceConfig.MIN_STOP_SECONDS && (old == null || old.stopId != stopId || old.stopSeconds < TraceConfig.MIN_STOP_SECONDS)
        val departedStop = old != null && old.stopId >= 0 && stopId < 0
        latest = TraceSample(nextSequence, fix.latitude, fix.longitude, elapsed, smoothSpeed, isBreak, stopId, stopSeconds)
        if (old != null && !isBreak && !changedSpeed && !newStop && !departedStop &&
            elapsed - old.elapsedSeconds < TraceConfig.SAMPLE_INTERVAL_SECONDS &&
            (oldFix == null || Geo.distance(oldFix, fix) < TraceConfig.SAMPLE_DISTANCE_METERS)) return null
        nextSequence++
        return latest!!.also { lastSample = it }
    }
    fun finish(): TraceSample? = latest?.takeIf { it.elapsedSeconds > (lastSample?.elapsedSeconds ?: -1.0) }
        ?.copy(sequence = nextSequence++)?.also { lastSample = it }
    fun liveHead(): TraceSample? = latest
}

object TracePresentation {
    const val UNKNOWN_COLOR = "#8A9298"
    fun withLiveHead(samples: List<TraceSample>, head: TraceSample?): List<TraceSample> {
        if (head == null) return samples
        val last = samples.lastOrNull() ?: return listOf(head)
        if (head.sequence < last.sequence || head.elapsedSeconds <= last.elapsedSeconds) return samples
        return if (head.sequence == last.sequence) samples.dropLast(1) + head else samples + head
    }
    fun color(speedKmh: Double?, brightSpeedKmh: Double = TraceConfig.BRIGHT_SPEED_KMH, stopSpeedKmh: Double = TraceConfig.STOP_SPEED_KMH, theme: ExplorerTheme = ExplorerTheme.GREEN): String {
        if (speedKmh == null || !speedKmh.isFinite()) return UNKNOWN_COLOR
        if (speedKmh <= stopSpeedKmh) return "#000000"
        val t = (speedKmh / brightSpeedKmh).coerceIn(0.0, 1.0)
        fun channel(dark: Int, light: Int) = (dark + (light - dark) * t).roundToInt()
        return "#%02X%02X%02X".format(java.util.Locale.ROOT, channel((theme.traceDark shr 16) and 255, (theme.traceLight shr 16) and 255),
            channel((theme.traceDark shr 8) and 255, (theme.traceLight shr 8) and 255), channel(theme.traceDark and 255, theme.traceLight and 255))
    }
    fun stops(samples: List<TraceSample>): List<TraceStop> = samples.filter { it.stopId >= 0 }
        .groupBy { it.stopId }.values.mapNotNull { group ->
            val duration = group.maxOf { it.stopSeconds }
            if (duration < TraceConfig.MIN_STOP_SECONDS) null else TraceStop(group.first().latitude, group.first().longitude, duration)
        }
    fun lineGeoJson(samples: List<TraceSample>, activity: ActivityKind = ActivityKind.CAR, theme: ExplorerTheme = ExplorerTheme.GREEN): String {
        // A stationary GPS has no length. Color its last approach segment black,
        // without inventing a dot or joining across a signal gap.
        val stoppedApproaches = mutableSetOf<Int>()
        var lastMovingSegment: Int? = null
        for (i in 1 until samples.size) {
            val a = samples[i - 1]; val b = samples[i]
            if (b.breakBefore) { lastMovingSegment = null; continue }
            if (a.latitude != b.latitude || a.longitude != b.longitude) lastMovingSegment = i
            if (b.speedKmh?.let { it <= activity.stopKmh } == true) {
                lastMovingSegment?.let { stoppedApproaches.add(it) }
            }
        }
        val features = samples.zipWithNext().mapIndexedNotNull { index, (a, b) ->
            if (b.breakBefore || (a.latitude == b.latitude && a.longitude == b.longitude)) null
            else """{"type":"Feature","properties":{"color":"${if (index + 1 in stoppedApproaches) "#000000" else color(b.speedKmh, activity.brightKmh, activity.stopKmh, theme)}"},"geometry":{"type":"LineString","coordinates":[[${a.longitude},${a.latitude}],[${b.longitude},${b.latitude}]]}}"""
        }
        return features.joinToString(",", "{\"type\":\"FeatureCollection\",\"features\":[", "]}")
    }
    fun stopGeoJson(samples: List<TraceSample>): String = stops(samples).joinToString(",", "{\"type\":\"FeatureCollection\",\"features\":[", "]}") {
        """{"type":"Feature","properties":{"seconds":${it.durationSeconds}},"geometry":{"type":"Point","coordinates":[${it.longitude},${it.latitude}]}}"""
    }
}
