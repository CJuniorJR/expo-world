package br.com.expoworld.core

import org.locationtech.jts.geom.Envelope
import org.locationtech.jts.index.quadtree.Quadtree
import kotlin.math.*

/** Display-only thresholds, in ground meters. Never used to filter or edit GPS history. */
object TraceVisualConfig {
    const val MAX_UNMAPPED_OFFSET_METERS = 1.5
    const val MAX_MATCHED_OFFSET_METERS = 5.0
    const val MAX_ANGLE_DEGREES = 10.0
    const val MIN_PIECE_METERS = .02
}

data class TraceVertex(val latitude: Double, val longitude: Double, val elapsedSeconds: Double = 0.0)
data class TracePiece(val id: Long, val from: TraceVertex, val to: TraceVertex, val sequence: Int,
    val speedKmh: Double?, val accuracy: Double?, val streetIds: Set<String>) {
    val bearing get() = Geo.bearing(Fix(from.latitude, from.longitude, 1.0, 0.0), Fix(to.latitude, to.longitude, 1.0, 0.0))
    val meters get() = Geo.distance(Fix(from.latitude, from.longitude, 1.0, 0.0), Fix(to.latitude, to.longitude, 1.0, 0.0))
}

/** Incremental geographic overlay, independent of storage and replay time.
 * The quadtree finds nearby pieces; only coincident subintervals of OLD pieces are cut.
 * Latest GPS coordinates, direction and speed win. Unvisited portions keep their metadata.
 * Own exclusively from one worker. A temporary interpolated head can be undone locally.
 */
class ConsolidatedTrace(private val stopKmh: Double = TraceConfig.STOP_SPEED_KMH) {
    private val index = Quadtree()
    private val pieces = linkedMapOf<Long, TracePiece>()
    private var previous: TraceSample? = null
    private var lastMovingId: Long? = null
    private var nextId = 0L
    private var undo: MutableMap<Long, TracePiece?>? = null
    private var savedPrevious: TraceSample? = null
    private var savedMoving: Long? = null
    private var snapshotCache: List<TracePiece>? = null
    var candidateChecks = 0L; private set
    var processedSamples = 0; private set
    fun snapshot(): List<TracePiece> = snapshotCache ?: pieces.values.toList().also { snapshotCache = it }
    private fun envelope(piece: TracePiece): Envelope {
        val a = Geo.project(piece.from.latitude, piece.from.longitude)
        val b = Geo.project(piece.to.latitude, piece.to.longitude)
        return Envelope(a.x, b.x, a.y, b.y)
    }
    private fun remember(id: Long) { undo?.let { if (!it.containsKey(id)) it[id] = pieces[id] } }
    private fun remove(id: Long) {
        remember(id)
        pieces.remove(id)?.let { index.remove(envelope(it), it); snapshotCache = null }
    }
    private fun add(piece: TracePiece) {
        remember(piece.id)
        pieces[piece.id] = piece
        index.insert(envelope(piece), piece)
        snapshotCache = null
    }
    fun beginPreview() {
        check(undo == null)
        savedPrevious = previous; savedMoving = lastMovingId; undo = linkedMapOf()
    }
    fun endPreview() {
        val changes = checkNotNull(undo); undo = null
        changes.keys.forEach { remove(it) }
        changes.values.filterNotNull().forEach { add(it) }
        previous = savedPrevious; lastMovingId = savedMoving
    }
    fun append(sample: TraceSample) {
        if (undo == null) processedSamples++
        val old = previous; previous = sample
        if (!sample.accepted || old == null || !old.accepted || sample.breakBefore ||
            abs(sample.longitude - old.longitude) > 180) { lastMovingId = null; return }
        val piece = TracePiece(nextId++, TraceVertex(old.latitude, old.longitude, old.elapsedSeconds), TraceVertex(sample.latitude, sample.longitude, sample.elapsedSeconds),
            sample.sequence, sample.speedKmh,
            listOfNotNull(old.accuracyMeters, sample.accuracyMeters).maxOrNull(),
            sample.streetIds?.split('|')?.filter { it.isNotBlank() }?.toSet().orEmpty())
        if (piece.meters < TraceVisualConfig.MIN_PIECE_METERS) {
            // A stationary fix updates only the latest approach, never an older passage.
            if (sample.speedKmh?.let { it <= stopKmh } == true) lastMovingId?.let { id ->
                pieces[id]?.takeIf { it.speedKmh != 0.0 }?.let { remove(id); add(it.copy(speedKmh = 0.0)) }
            }
            return
        }
        val scale = cos(Math.toRadians((old.latitude + sample.latitude) / 2)).coerceAtLeast(.08)
        val search = envelope(piece).apply { expandBy(TraceVisualConfig.MAX_MATCHED_OFFSET_METERS / scale) }
        val nearby = index.query(search).filterIsInstance<TracePiece>()
        for (candidate in nearby) {
            candidateChecks++
            val interval = overlap(candidate, piece, scale) ?: continue
            remove(candidate.id)
            if (interval.first > 0) retain(candidate, 0.0, interval.first)
            if (interval.second < 1) retain(candidate, interval.second, 1.0)
        }
        add(piece); lastMovingId = piece.id
    }
    private fun retain(piece: TracePiece, start: Double, end: Double) {
        val part = piece.copy(id = nextId++, from = interpolate(piece, start), to = interpolate(piece, end))
        if (part.meters >= TraceVisualConfig.MIN_PIECE_METERS) add(part)
    }
    private fun interpolate(piece: TracePiece, t: Double): TraceVertex {
        val a = Geo.project(piece.from.latitude, piece.from.longitude); val b = Geo.project(piece.to.latitude, piece.to.longitude)
        return TraceVertex(Geo.latitude(a.y + (b.y - a.y) * t), Geo.longitude(a.x + (b.x - a.x) * t),
            piece.from.elapsedSeconds + (piece.to.elapsedSeconds - piece.from.elapsedSeconds) * t)
    }
    /** Range on the old segment covered by this new segment (either direction). */
    private fun overlap(old: TracePiece, new: TracePiece, scale: Double): Pair<Double, Double>? {
        val mapped = old.streetIds.isNotEmpty() && new.streetIds.isNotEmpty()
        if (mapped && old.streetIds.intersect(new.streetIds).isEmpty()) return null
        val accuracy = listOfNotNull(old.accuracy, new.accuracy).minOrNull()
        val ceiling = if (mapped) TraceVisualConfig.MAX_MATCHED_OFFSET_METERS else TraceVisualConfig.MAX_UNMAPPED_OFFSET_METERS
        val tolerance = if (accuracy == null) .25 else if (max(old.accuracy ?: 0.0, new.accuracy ?: 0.0) > 12) .1
            else (accuracy * .5).coerceIn(.25, ceiling)
        val a = Geo.project(old.from.latitude, old.from.longitude); val b = Geo.project(old.to.latitude, old.to.longitude)
        val c = Geo.project(new.from.latitude, new.from.longitude); val d = Geo.project(new.to.latitude, new.to.longitude)
        val dx = (d.x - c.x) * scale; val dy = (d.y - c.y) * scale
        val length = hypot(dx, dy); val ox = (b.x - a.x) * scale; val oy = (b.y - a.y) * scale
        val oldLength = hypot(ox, oy)
        if (length < .02 || oldLength < .02 || abs(dx * ox + dy * oy) / (length * oldLength) < cos(Math.toRadians(TraceVisualConfig.MAX_ANGLE_DEGREES))) return null
        val x = (a.x - c.x) * scale; val y = (a.y - c.y) * scale
        val along = (x * dx + y * dy) / length
        val advance = (ox * dx + oy * dy) / length
        val t0 = (-along / advance); val t1 = ((length - along) / advance)
        val start = max(0.0, min(t0, t1)); val end = min(1.0, max(t0, t1))
        if (end <= start || (end - start) * oldLength < .02) return null
        fun lateral(t: Double) = ((x + ox * t) * dy - (y + oy * t) * dx) / length
        val p = lateral(start); val q = lateral(end)
        if (max(abs(p), abs(q)) > tolerance) return null
        // A shallow crossing is not a repeat. Permit only sub-decimeter numeric noise.
        if (p * q < 0 && min(abs(p), abs(q)) > .1) return null
        return start to end
    }
}

/** Keeps a committed chronological prefix; scrubbing backwards resets only this display cache.
 * The interpolated edge is overlaid and undone without copying/rebuilding the prefix.
 */
class TraceRenderTimeline(private val stopKmh: Double = TraceConfig.STOP_SPEED_KMH) {
    private var overlay = ConsolidatedTrace(stopKmh)
    private var count = 0
    private var last: TraceSample? = null
    var rebuilds = 0; private set
    val processedSamples get() = overlay.processedSamples
    fun frame(samples: List<TraceSample>, checkActive: () -> Unit = {}): List<TracePiece> {
        // Prefixes from ReplayTimeline retain original sequence IDs. A final interpolated
        // point shares its predecessor ID and must never enter the committed cache.
        val ephemeral = samples.size >= 2 && samples.last().sequence == samples[samples.lastIndex - 1].sequence
        val committed = samples.size - if (ephemeral) 1 else 0
        if (committed < count || (count > 0 && samples.getOrNull(count - 1) != last)) {
            overlay = ConsolidatedTrace(stopKmh); count = 0; last = null; rebuilds++
        }
        while (count < committed) {
            if (count % 128 == 0) checkActive()
            val next = samples[count]; overlay.append(next); count++; last = next
        }
        if (!ephemeral) return overlay.snapshot()
        overlay.beginPreview()
        return try { overlay.append(samples.last()); overlay.snapshot() } finally { overlay.endPreview() }
    }
}

object ConsolidatedTracePresentation {
    fun lines(pieces: List<TracePiece>, activity: ActivityKind, theme: ExplorerTheme): String = pieces.joinToString(",", "{\"type\":\"FeatureCollection\",\"features\":[", "]}") {
        """{"type":"Feature","id":${it.id},"properties":{"sequence":${it.sequence},"color":"${TracePresentation.color(it.speedKmh, activity.brightKmh, activity.stopKmh, theme)}"},"geometry":{"type":"LineString","coordinates":[[${it.from.longitude},${it.from.latitude}],[${it.to.longitude},${it.to.latitude}]]}}"""
    }
    fun directions(pieces: List<TracePiece>): String {
        // At most one arrow per ~35m cell: dense 1Hz history doesn't clutter the map.
        val selected = linkedMapOf<Pair<Long, Long>, TracePiece>()
        for (piece in pieces) if (piece.meters >= .5) {
            val p = Geo.project((piece.from.latitude + piece.to.latitude) / 2, (piece.from.longitude + piece.to.longitude) / 2)
            val key = floor(p.x / 40).toLong() to floor(p.y / 40).toLong()
            if ((selected[key]?.sequence ?: -1) <= piece.sequence) selected[key] = piece
        }
        return selected.values.joinToString(",", "{\"type\":\"FeatureCollection\",\"features\":[", "]}") {
            """{"type":"Feature","properties":{"bearing":${it.bearing}},"geometry":{"type":"Point","coordinates":[${(it.from.longitude + it.to.longitude) / 2},${(it.from.latitude + it.to.latitude) / 2}]}}"""
        }
    }
}
