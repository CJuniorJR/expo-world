package br.com.expoworld.core

import org.junit.Assert.*
import org.junit.Test
import java.io.StringReader
import java.io.StringWriter
import java.security.MessageDigest

class ExplorationCinemaTest {
    private fun point(sequence: Int, time: Double, longitude: Double, gap: Boolean = false) =
        TraceSample(sequence, -25.43, longitude, time, 20.0, gap, -1, 0.0)
    @Test fun replayInterpolatesOnlyInsideContinuousSegments() {
        val points = listOf(point(0, 0.0, -49.27, true), point(1, 10.0, -49.269), point(2, 30.0, -49.265, true), point(3, 40.0, -49.264))
        assertEquals(-49.2695, ReplayTimeline.frame(points, .125f).last().longitude, .000001)
        assertEquals(points.take(2), ReplayTimeline.frame(points, .5f))
        assertEquals(-49.2645, ReplayTimeline.frame(points, .875f).last().longitude, .000001)
        assertEquals(points, ReplayTimeline.frame(points, 1f))
        assertEquals(listOf(points.first()), ReplayTimeline.frame(points, 0f))
        assertEquals(0.0, points.first().elapsedSeconds, 0.0)
    }
    @Test fun beforeAfterAndReplayNeverModifyPersistedCellsOrInventOldTiming() {
        val old = Cell(0, 0); val fresh = Cell(10, 0); val legacy = Cell(20, 0)
        val records = listOf(old, fresh, legacy).map { CellDiscovery(it, null) }
        val scene = ExplorationScene(records, listOf(TripDiscovery(fresh, 5.0), TripDiscovery(legacy, -1.0)))
        assertEquals(setOf(old, legacy), scene.replayCells(0.0).toSet())
        assertEquals(setOf(old, fresh, legacy), scene.replayCells(5.0).toSet())
        val size = ExplorationConfig.CELL_PROJECTED_METERS
        val clip = listOf(XY(Geo.longitude(90.0), -1.0), XY(Geo.longitude(120.0), -1.0), XY(Geo.longitude(120.0), 1.0), XY(Geo.longitude(90.0), 1.0))
        val mask = scene.comparisonMask(clip)
        assertTrue(mask.contains("MultiPolygon"))
        assertNotEquals(scene.comparisonMask(clip.map { it.copy(x = it.x - 1) }), mask)
        assertEquals(3, records.size)
        assertEquals(10.0, size, 0.0)
    }
    @Test fun epochsRespectMonthUnknownAndFutureDates() {
        val now = 1_760_000_000_000L; val month = DiscoveryHistory.monthStart(now)
        assertEquals(DiscoveryEpoch.MONTH, DiscoveryHistory.epoch(month, false, now, month))
        assertEquals(DiscoveryEpoch.OLDER, DiscoveryHistory.epoch(month - 1, false, now, month))
        assertEquals(DiscoveryEpoch.UNKNOWN, DiscoveryHistory.epoch(null, false, now, month))
        assertEquals(DiscoveryEpoch.UNKNOWN, DiscoveryHistory.epoch(now + 1, false, now, month))
        assertEquals(DiscoveryEpoch.TRIP, DiscoveryHistory.epoch(null, true, now, month))
        val json = DiscoveryHistory.geoJson(listOf(CellDiscovery(Cell(0, 0), now)), emptySet(), now)
        assertTrue(json.contains(DiscoveryEpoch.MONTH.color))
    }
    @Test fun backupPreservesDatesAndStillReadsVersionOne() {
        val writer = StringWriter(); val encoder = ExplorationBackup.Encoder(writer)
        encoder.cell(Cell(1, 2), 1_760_000_000_000L); encoder.cell(Cell(3, 4)); encoder.finish()
        assertEquals(listOf(1_760_000_000_000L, null), ExplorationBackup.decodeRecords(StringReader(writer.toString())).map { it.firstDiscoveredAtMillis }.toList())
        val line = "-1\t2\n"
        val checksum = MessageDigest.getInstance("SHA-256").digest(line.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        val old = "EXPO_WORLD\t1\t10\n${line}END\t1\t$checksum\n"
        assertEquals(listOf(ExplorationBackup.Record(Cell(-1, 2), null)), ExplorationBackup.decodeRecords(StringReader(old)).toList())
    }
    @Test fun curitibaRouteHasCurvesAndAllNormalFixesPassFilter() {
        val points = (0..SimulatedRoute.LAST_INDEX).map { SimulatedRoute.fix(it, it.toDouble()) }
        assertTrue(points.all { it.latitude in -25.44..-25.42 && it.longitude in -49.28..-49.26 })
        val filter = FixFilter()
        points.forEach { assertTrue(filter.accept(it, it.monotonicSeconds) is FilterResult.Accepted) }
        val vectors = points.zipWithNext().mapNotNull { (a, b) ->
            if (Geo.distance(a, b) > 3) kotlin.math.atan2(b.latitude - a.latitude, b.longitude - a.longitude) else null
        }
        assertTrue(vectors.max() - vectors.min() > Math.PI / 2)
    }
}
