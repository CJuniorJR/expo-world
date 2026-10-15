package br.com.expoworld.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos

class ExplorerInsightsTest {
    @Test fun areaUsesLatitudeScaleInsteadOfCountingProjectedMetersAsGround() {
        val equator = ExplorationProgress.fromRows(listOf(0 to 100L))
        assertEquals(10_000.0, equator.areaSquareMeters, .001)
        val y = Geo.cell(Geo.project(60.0, 0.0)).y
        val north = ExplorationProgress.fromRows(listOf(y to 100L))
        assertEquals(equator.areaSquareMeters * .25, north.areaSquareMeters, .02)
    }
    @Test fun subdividingLegacyCellsPreservesTheirArea() {
        val oldY = -135_000
        val expected = 400 * cos(Math.toRadians(Geo.latitude((oldY + .5) * 20))).let { it * it }
        val upgraded = ExplorationProgress.fromRows(listOf(oldY * 2 to 2L, oldY * 2 + 1 to 2L))
        assertEquals(expected, upgraded.areaSquareMeters, .00001)
    }
    @Test fun milestonesDependOnlyOnAreaAndRemainBounded() {
        val initial = ExplorationProgress()
        assertEquals(0f, initial.progressToNext, .001f)
        val progressed = ExplorationProgress(100_000.0, 1000)
        assertEquals("Explorador do bairro", progressed.title)
        assertEquals(1_000_000.0, progressed.nextTarget!!, .01)
        assertEquals(1f, ExplorationProgress(10_000_000.0).progressToNext, .001f)
    }
    @Test fun summaryExcludesGpsGapsAndStationaryDrift() {
        fun point(index: Int, x: Double, time: Double, gap: Boolean = false, stop: Int = -1, seconds: Double = 0.0) =
            TraceSample(index, 0.0, Geo.longitude(x), time, if (stop < 0) 36.0 else 0.0, gap, stop, seconds)
        val route = RecordedTrace(0, 30_000, listOf(point(0, 0.0, 0.0, true), point(1, 100.0, 10.0),
            point(2, 1000.0, 20.0, true, 0), point(3, 1005.0, 25.0, stop = 0, seconds = 5.0)))
        val summary = TripSummary.from(route)
        assertEquals(100.0, summary.distanceMeters, .001)
        assertEquals(10.0, summary.missingGpsSeconds, .001)
        assertEquals(5.0, summary.stoppedSeconds, .001)
        assertEquals(1, summary.stops)
        assertEquals(24.0, summary.averageKmh!!, .001)
    }
    @Test fun unknownVelocityIsNotReportedAsZeroAndPartialDataIsMarked() {
        val route = RecordedTrace(0, 1000, listOf(TraceSample(50, 0.0, 0.0, 500.0, null, true, -1, 0.0)))
        val summary = TripSummary.from(route)
        assertTrue(summary.partial)
        assertNull(summary.averageKmh)
        assertEquals(0.0, summary.recordedSeconds, .001)
    }
}
