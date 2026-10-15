package br.com.expoworld.core

import org.junit.Assert.*
import org.junit.Test
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import kotlin.math.*

class ExplorationTest {
    private fun fix(x: Double = 0.0, y: Double = 0.0, time: Double = 100.0, accuracy: Double = 5.0) = Fix(Geo.latitude(y), Geo.longitude(x), accuracy, time)
    @Test fun distanceIsInMeters() { assertEquals(100.0, Geo.distance(fix(), fix(x = 100.0)), .01) }
    @Test fun radiusIsFiftyMeters() {
        val cells = Revealer.cells(fix(), null)
        assertTrue(cells.contains(Geo.cell(XY(30.0, 0.0))))
        assertFalse(cells.contains(Geo.cell(XY(80.0, 0.0))))
        val size = ExplorationConfig.CELL_PROJECTED_METERS
        for (cell in cells) assertTrue(hypot((cell.x + .5) * size, (cell.y + .5) * size) <= 50)
    }
    @Test fun latitudeScalePreservesGroundRadius() {
        val latitude = 60.0
        val base = Geo.project(latitude, 0.0)
        val cells = Revealer.cells(Fix(latitude, 0.0, 5.0, 100.0), null)
        assertTrue(cells.contains(Geo.cell(XY(base.x + 80, base.y)))) // 40 m ground
        assertFalse(cells.contains(Geo.cell(XY(base.x + 160, base.y)))) // 80 m ground
    }
    @Test fun segmentIsContinuousAndHasNoExtraStrip() {
        val cells = Revealer.cells(fix(x = 300.0, time = 110.0), fix())
        for (x in 0..300 step 20) assertTrue(cells.contains(Geo.cell(XY(x.toDouble(), 0.0))))
        assertFalse(cells.contains(Geo.cell(XY(150.0, 100.0))))
    }
    @Test fun inaccurateFixBreaksContinuity() {
        val filter = FixFilter(); filter.accept(fix(), 100.0)
        assertTrue(filter.accept(fix(x = 50.0, time = 101.0, accuracy = 100.0), 101.0) is FilterResult.Rejected)
        assertNull((filter.accept(fix(x = 100.0, time = 105.0), 105.0) as FilterResult.Accepted).previous)
    }
    @Test fun gpsJumpIsDiscarded() {
        val filter = FixFilter(); filter.accept(fix(), 100.0)
        assertTrue(filter.accept(fix(x = 2000.0, time = 101.0), 101.0) is FilterResult.Rejected)
    }
    @Test fun dopplerSpeedRejectsAnImplausibleCoordinateJump() {
        val filter = FixFilter()
        filter.accept(fix().copy(speedMetersPerSecond = 0.0), 100.0)
        assertTrue(filter.accept(fix(x = 40.0, time = 101.0).copy(speedMetersPerSecond = 0.0), 101.0) is FilterResult.Rejected)
    }
    @Test fun dopplerConsistencyAllowsNormalAcceleration() {
        val filter = FixFilter()
        filter.accept(fix().copy(speedMetersPerSecond = 10.0), 100.0)
        assertTrue(filter.accept(fix(x = 20.0, time = 101.0).copy(speedMetersPerSecond = 20.0), 101.0) is FilterResult.Accepted)
    }
    @Test fun stricterPositionAccuracyIsEnforced() {
        assertTrue(FixFilter().accept(fix(accuracy = 21.0), 100.0) is FilterResult.Rejected)
        assertTrue(FixFilter().accept(fix(accuracy = 20.0), 100.0) is FilterResult.Accepted)
    }
    @Test fun roundedFogIsValidAndPreservesRouteCenter() {
        val cells = Revealer.cells(fix(x = 200.0, y = 200.0, time = 110.0), fix()).toList()
        val fog = FogGeometry.geometry(cells)
        val factory = GeometryFactory()
        assertTrue(fog.isValid)
        for (d in 0..200 step 20) assertFalse(fog.covers(factory.createPoint(Coordinate(Geo.longitude(d.toDouble()), Geo.latitude(d.toDouble())))))
        assertTrue(fog.covers(factory.createPoint(Coordinate(Geo.longitude(100.0), Geo.latitude(250.0)))))
    }
    @Test fun impossibleJumpAfterSignalLossIsStillDiscarded() {
        val filter = FixFilter(); filter.accept(fix(), 100.0)
        assertTrue(filter.accept(fix(x = 2000.0, time = 120.0), 120.0) is FilterResult.Rejected)
    }
    @Test fun longGapStartsNewIsland() {
        val filter = FixFilter(); filter.accept(fix(), 100.0)
        val result = filter.accept(fix(x = 1000.0, time = 160.0), 160.0) as FilterResult.Accepted
        assertNull(result.previous)
        assertFalse(Revealer.cells(result.fix, result.previous).contains(Geo.cell(XY(500.0, 0.0))))
    }
    @Test fun staleAndFutureAndInvalidPointsAreRejected() {
        assertTrue(FixFilter().accept(fix(), 120.0) is FilterResult.Rejected)
        assertTrue(FixFilter().accept(fix(), 90.0) is FilterResult.Rejected)
        assertTrue(FixFilter().accept(Fix(Double.NaN, 0.0, 5.0, 100.0), 100.0) is FilterResult.Rejected)
        assertTrue(FixFilter().accept(fix(accuracy = 0.0), 100.0) is FilterResult.Rejected)
    }
    @Test fun duplicateTimestampDoesNotReveal() {
        val filter = FixFilter(); filter.accept(fix(), 100.0)
        assertTrue(filter.accept(fix(x = 10.0), 100.0) is FilterResult.Rejected)
    }
    @Test fun datelineDoesNotCreateWorldSpanningSegment() {
        val filter = FixFilter(); filter.accept(Fix(0.0, 179.9999, 5.0, 100.0), 100.0)
        val result = filter.accept(Fix(0.0, -179.9999, 5.0, 101.0), 101.0) as FilterResult.Accepted
        assertNull(result.previous)
    }
    @Test fun viewportWrapQueriesBothSidesOfDateline() {
        val wrapped = Geo.viewportBounds(-1.0, 179.0, 1.0, -179.0)
        val unwrapped = Geo.viewportBounds(-1.0, 179.0, 1.0, 181.0)
        assertEquals(2, unwrapped.size)
        assertEquals(wrapped, unwrapped)
        assertTrue(unwrapped[0].minX > 0)
        assertTrue(unwrapped[1].maxX < 0)
    }
    @Test fun adjacentCellHolesAreUnionedAndValid() {
        val cells = Revealer.cells(fix(x = 300.0, time = 110.0), fix()).toList()
        val fog = FogGeometry.geometry(cells)
        val factory = GeometryFactory()
        assertTrue(fog.isValid)
        assertFalse(fog.covers(factory.createPoint(Coordinate(Geo.longitude(150.0), 0.0))))
        assertTrue(fog.covers(factory.createPoint(Coordinate(Geo.longitude(150.0), Geo.latitude(100.0)))))
        assertTrue(FogGeometry.geoJson(cells).contains("MultiPolygon"))
    }
    @Test fun revisitingDoesNotAccumulateGeometry() {
        val a = Revealer.cells(fix(), null)
        assertEquals(a.size, (a + Revealer.cells(fix(), null)).size)
    }
    @Test fun simulationIsAcceptedWithoutJumps() {
        val filter = FixFilter()
        for (i in 0..SimulatedRoute.LAST_INDEX) assertTrue(filter.accept(SimulatedRoute.fix(i, 100.0 + i), 100.0 + i) is FilterResult.Accepted)
    }
}
