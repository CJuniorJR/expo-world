package br.com.expoworld.core

/** Geometry diagnostics, never used to grant street credit or change GPS history. */
data class TripRecordQuality(val hasMetadata: Boolean, val recognizedMeters: Double,
    val unrecognizedMeters: Double, val rejectedReadings: Int, val inaccurateReadings: Int) {
    companion object {
        fun from(trace: RecordedTrace): TripRecordQuality {
            var recognized = 0.0; var unrecognized = 0.0
            for ((a, b) in trace.samples.zipWithNext()) {
                if (!a.accepted || !b.accepted || b.breakBefore || kotlin.math.abs(a.longitude - b.longitude) > 180) continue
                val seconds = b.elapsedSeconds - a.elapsedSeconds
                if (seconds <= 0 || seconds > 12) continue
                val distance = Geo.distance(Fix(a.latitude,a.longitude,1.0,0.0), Fix(b.latitude,b.longitude,1.0,0.0))
                if (distance > trace.activity.maxMetersSecond * seconds + 25) continue
                if (!b.streetIds.isNullOrBlank()) recognized += distance else unrecognized += distance
            }
            return TripRecordQuality(trace.samples.any { it.accuracyMeters != null || it.streetIds != null || !it.accepted },
                recognized, unrecognized, trace.samples.count { !it.accepted }, trace.samples.count { (it.accuracyMeters ?: 0.0) > 12 })
        }
    }
}
