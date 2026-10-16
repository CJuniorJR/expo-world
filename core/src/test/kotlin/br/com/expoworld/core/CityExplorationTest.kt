package br.com.expoworld.core

import org.junit.Assert.*
import org.junit.Test
import org.locationtech.jts.geom.*

class CityExplorationTest {
    @Test fun walkingWindowAccumulatesSmallStepsAndResetsAcrossGaps() {
        val window = CoverageWindow()
        assertNull(window.previousFor(fix(0.0), false))
        assertNull(window.previousFor(fix(1.3, time = 1.0), true))
        assertNull(window.previousFor(fix(2.6, time = 2.0), true))
        val previous = window.previousFor(fix(3.9, time = 3.0), true)
        assertNotNull(previous)
        assertFalse(network(street()).match(previous, fix(3.9, time = 3.0), ActivityKind.WALK).isEmpty())
        assertNull(window.previousFor(fix(10.0, time = 30.0), true))
        window.reset()
        assertNull(window.previousFor(fix(20.0, time = 32.0), true))
    }
    private val gf = GeometryFactory()
    private fun street(id: String = "a", y: Double = 0.0, mask: Int = 7, bairro: String = "1", start: Double = 0.0, end: Double = 100.0) =
        CityStreet(id, bairro, mask, gf.createLineString(arrayOf(Coordinate(start, y), Coordinate(end, y))))
    private fun fix(x: Double, y: Double = 0.0, time: Double = 0.0, accuracy: Double = 5.0) =
        Fix(CuritibaProjection.latitude(y), CuritibaProjection.longitude(x), accuracy, time)
    private fun network(vararg streets: CityStreet): CityNetwork {
        val boundary = gf.createPolygon(arrayOf(Coordinate(-10.0, -10.0), Coordinate(110.0, -10.0),
            Coordinate(110.0, 110.0), Coordinate(-10.0, 110.0), Coordinate(-10.0, -10.0)))
        return CityNetwork("test", "2026-10-15", listOf(CityNeighborhood("1", "Bairro", boundary)), streets.toList())
    }
    @Test fun partialStreetCountsOnlyReachedBinsAndRepeatsDoNotAddMileage() {
        val n = network(street())
        val first = n.match(fix(0.0), fix(40.0, time = 5.0), ActivityKind.CAR)
        assertEquals(setOf(0, 1, 2, 3), first.map { it.bin }.toSet())
        val reverse = n.match(fix(40.0, time = 5.0), fix(0.0, time = 10.0), ActivityKind.CAR)
        assertEquals(first, reverse)
        val unique = (first + reverse).sumOf { it.meters }
        assertEquals(40.0, unique, .001)
        assertEquals(.4, n.progress(mapOf("1" to unique)).neighborhoods.single().fraction, .001)
    }
    @Test fun rejectsBadAccuracyGapsStationaryDriftAndLongBridges() {
        val n = network(street())
        assertTrue(n.match(null, fix(40.0), ActivityKind.CAR).isEmpty())
        assertTrue(n.match(fix(0.0), fix(40.0, time = 5.0, accuracy = 13.0), ActivityKind.CAR).isEmpty())
        assertTrue(n.match(fix(0.0), fix(40.0, time = 16.0), ActivityKind.CAR).isEmpty())
        assertTrue(n.match(fix(0.0), fix(1.0, time = 1.0), ActivityKind.WALK).isEmpty())
        assertTrue(n.match(fix(0.0), fix(120.0, time = 10.0), ActivityKind.CAR).isEmpty())
        assertTrue(n.match(fix(0.0, y = 30.0), fix(40.0, y = 30.0, time = 5.0), ActivityKind.CAR).isEmpty())
    }
    @Test fun doesNotGuessBetweenParallelRoads() {
        val n = network(street(), street("b", y = 8.0))
        assertTrue(n.match(fix(0.0, 4.0), fix(40.0, 4.0, 5.0), ActivityKind.CAR).isEmpty())
        assertEquals(setOf("a"), n.match(fix(0.0), fix(40.0, time = 5.0), ActivityKind.CAR).map { it.streetId }.toSet())
    }
    @Test fun modesHaveDifferentEligibleNetworksAndMovementLimits() {
        val n = network(street(mask = ActivityKind.WALK.mask))
        assertTrue(n.match(fix(0.0), fix(20.0, time = 5.0), ActivityKind.CAR).isEmpty())
        assertFalse(n.match(fix(0.0), fix(20.0, time = 5.0), ActivityKind.WALK).isEmpty())
        assertTrue(n.match(fix(0.0), fix(40.0, time = 1.0), ActivityKind.WALK).isEmpty())
        assertEquals(100.0, n.progress(emptyMap()).totalMeters, .0)
    }
    @Test fun sharedProgressUsesOneDenominatorAcrossAllActivityNetworks() {
        val n = network(street("car", mask = 1), street("walk", y = 20.0, mask = 2),
            street("bike", y = 40.0, mask = 4), street("shared", y = 60.0, mask = 7))
        val progress = n.progress(mapOf("1" to 100.0))
        assertEquals(400.0, progress.totalMeters, .001)
        assertEquals(.25, progress.neighborhoods.single().fraction, .001)
        assertFalse(n.match(fix(0.0, 20.0), fix(10.0, 20.0, 5.0), ActivityKind.WALK).isEmpty())
        assertTrue(n.match(fix(0.0, 20.0), fix(10.0, 20.0, 5.0), ActivityKind.CAR).isEmpty())
    }
    @Test fun boundaryPiecesCreditTheirOwnNeighborhoodWithoutExceedingTotals() {
        val n = network(street("left", end = 50.0), street("right", bairro = "2", start = 50.0))
        val credits = n.match(fix(30.0), fix(70.0, time = 5.0), ActivityKind.CAR)
        assertEquals(setOf("1", "2"), credits.map { it.neighborhoodId }.toSet())
        assertTrue(credits.sumOf { it.meters } <= 40.01)
        assertEquals(1.0, n.progress(mapOf("1" to 999.0)).neighborhoods.single().fraction, .0)
    }
    @Test fun walkingStopThresholdAndPaletteAreAppropriateToWalking() {
        assertNotEquals("#000000", TracePresentation.color(2.0, ActivityKind.WALK.brightKmh, ActivityKind.WALK.stopKmh))
        assertEquals("#000000", TracePresentation.color(.5, ActivityKind.WALK.brightKmh, ActivityKind.WALK.stopKmh))
        assertEquals("#C9FFF1", TracePresentation.color(8.0, ActivityKind.WALK.brightKmh, ActivityKind.WALK.stopKmh))
        assertEquals("1", network(street()).neighborhoodAt(fix(10.0)))
        assertNull(network(street()).neighborhoodAt(fix(1000.0)))
    }
}
