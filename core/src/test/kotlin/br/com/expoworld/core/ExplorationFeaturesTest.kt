package br.com.expoworld.core

import org.junit.Assert.*
import org.junit.Test

class ExplorationFeaturesTest {
    @Test fun highlightedRowsPreserveCellsWithoutFillingGaps() {
        val cells = listOf(Cell(-3, -1), Cell(-2, -1), Cell(0, -1), Cell(0, 0), Cell(-3, -1))
        val rows = CoverageGeometry.rows(cells)
        val restored = rows.flatMap { b -> (b.minX..b.maxX).map { Cell(it, b.minY) } }.toSet()
        assertEquals(cells.toSet(), restored)
        assertEquals(3, rows.size)
        assertTrue(CoverageGeometry.geoJson(rows).contains("Polygon"))
    }
    @Test fun difficultScenariosRejectBadPointsAndRecoverWithoutBridging() {
        for (scenario in SimulationScenario.entries.filter { it != SimulationScenario.NORMAL }) {
            val filter = FixFilter()
            var rejected = 0; var islands = 0; var acceptedAfter = false
            for (i in 0..SimulatedRoute.LAST_INDEX) {
                val fix = scenario.fix(i, i.toDouble()) ?: continue
                when (val result = filter.accept(fix, i.toDouble())) {
                    is FilterResult.Rejected -> rejected++
                    is FilterResult.Accepted -> {
                        if (result.previous == null) islands++
                        if (i > 70) acceptedAfter = true
                        assertFalse(result.previous?.let { i - it.monotonicSeconds > ExplorationConfig.MAX_GAP_SECONDS } ?: false)
                    }
                }
            }
            assertTrue("$scenario needs separate islands", islands >= 2)
            assertTrue(acceptedAfter)
            if (scenario != SimulationScenario.TUNNEL) assertTrue(rejected > 0)
        }
    }
    @Test fun explicitPauseBreaksBothShortAndLongResumes() {
        for (delay in listOf(2.0, 60.0)) {
            val filter = FixFilter(); val trace = TraceSampler()
            val a = Fix(0.0, 0.0, 5.0, 100.0, 5.0)
            val accepted = filter.accept(a, 100.0) as FilterResult.Accepted
            trace.accept(accepted.fix, false)
            filter.breakContinuity(); trace.breakContinuity()
            val b = a.copy(longitude = .0001, monotonicSeconds = 100.0 + delay)
            val resumed = filter.accept(b, b.monotonicSeconds) as FilterResult.Accepted
            assertNull(resumed.previous)
            val point = trace.accept(resumed.fix, resumed.previous != null)!!
            assertTrue(point.breakBefore)
            assertFalse(TracePresentation.lineGeoJson(listOf(traceSample(a), point)).contains("LineString"))
        }
    }
    private fun traceSample(fix: Fix) = TraceSample(0, fix.latitude, fix.longitude, 0.0, 18.0, true, -1, 0.0)
}
