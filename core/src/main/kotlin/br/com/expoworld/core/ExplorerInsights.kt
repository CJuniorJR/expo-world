package br.com.expoworld.core

import kotlin.math.*

data class ExplorationProgress(val areaSquareMeters: Double = 0.0, val cells: Long = 0, val mode: String = "real") {
    val milestones = listOf(0.0 to "Primeiro horizonte", 10_000.0 to "Primeiras descobertas",
        100_000.0 to "Explorador do bairro", 1_000_000.0 to "Novos horizontes", 5_000_000.0 to "Explorador da cidade")
    val levelIndex get() = milestones.indexOfLast { areaSquareMeters.roundToLong() >= it.first }.coerceAtLeast(0)
    val title get() = milestones[levelIndex].second
    val nextTarget get() = milestones.getOrNull(levelIndex + 1)?.first
    val progressToNext get() = nextTarget?.let {
        ((areaSquareMeters - milestones[levelIndex].first) / (it - milestones[levelIndex].first)).coerceIn(0.0, 1.0).toFloat()
    } ?: 1f
    companion object {
        fun fromRows(rows: List<Pair<Int, Long>>): ExplorationProgress {
            val size = ExplorationConfig.CELL_PROJECTED_METERS
            val area = rows.sumOf { (y, count) ->
                val latitude = Geo.latitude((y + .5) * size)
                count.coerceAtLeast(0) * size * size * cos(Math.toRadians(latitude)).pow(2)
            }
            return ExplorationProgress(area, rows.sumOf { it.second.coerceAtLeast(0) })
        }
    }
}

data class TripSummary(
    val distanceMeters: Double, val recordedSeconds: Double, val stoppedSeconds: Double,
    val missingGpsSeconds: Double, val averageKmh: Double?, val stops: Int, val partial: Boolean
) {
    companion object {
        fun from(trace: RecordedTrace): TripSummary {
            val points = trace.samples
            var distance = 0.0
            var tracked = 0.0
            var weightedSpeed = 0.0
            var knownSpeedSeconds = 0.0
            points.zipWithNext().forEach { (a, b) ->
                val dt = b.elapsedSeconds - a.elapsedSeconds
                if (a.accepted && b.accepted && !b.breakBefore && dt > 0) {
                    tracked += dt
                    // Don't turn coordinate drift while standing still into mileage.
                    if (a.stopId < 0 || a.stopId != b.stopId) {
                        distance += Geo.distance(Fix(a.latitude, a.longitude, 1.0, 0.0), Fix(b.latitude, b.longitude, 1.0, 0.0))
                    }
                    b.speedKmh?.takeIf { it.isFinite() && it >= 0 }?.let { weightedSpeed += it * dt; knownSpeedSeconds += dt }
                }
            }
            val elapsed = if (points.isEmpty()) 0.0 else (points.last().elapsedSeconds - points.first().elapsedSeconds).coerceAtLeast(0.0)
            val stops = TracePresentation.stops(points)
            return TripSummary(distance, elapsed, stops.sumOf { it.durationSeconds }.coerceAtMost(tracked),
                (elapsed - tracked).coerceAtLeast(0.0), if (knownSpeedSeconds > 0) weightedSpeed / knownSpeedSeconds else null,
                stops.size, points.firstOrNull()?.sequence?.let { it > 0 } ?: false)
        }
    }
}
