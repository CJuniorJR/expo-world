package br.com.expoworld.core

import org.junit.Assert.*
import org.junit.Test

class PocketExplorationTest {
    private val origin = PocketPoint(-25.43,-49.27)
    private fun request(purpose: PocketPurpose = PocketPurpose.EXPLORE, loop: Boolean = true) =
        PocketRequest(origin,30,ActivityKind.WALK,purpose,loop)
    private fun path(seconds: Double = 1620.0, end: PocketPoint = origin, steps: Int = 2) =
        PocketPath(listOf(origin,PocketPoint(-25.431,-49.27),end),List(steps) { PocketStep(it % 3,"Seguir",20.0) },2000.0,seconds)
    @Test fun everyActivityHasDistinctLoopAndOpenCandidatesWithFixedOrigin() {
        ActivityKind.entries.forEach { activity ->
            val r = request().copy(activity = activity)
            val loop = PocketPlanning.waypoints(r,0)
            assertEquals(4,loop.size); assertEquals(origin,loop.first()); assertEquals(origin,loop.last())
            assertNotEquals(loop,PocketPlanning.waypoints(r,1))
            assertNotEquals(loop,PocketPlanning.waypoints(r.copy(variation = 1),0))
            val open = PocketPlanning.waypoints(r.copy(returnToStart = false),0)
            assertEquals(2,open.size); assertEquals(origin,open.first()); assertTrue(origin.distance(open.last()) > 100)
        }
    }
    @Test fun budgetIncludesReturnAndRejectsRoutesOverBudgetOrFarFromOrigin() {
        assertTrue(PocketPlanning.fits(request(),path()))
        assertFalse(PocketPlanning.fits(request(),path(seconds = 2200.0)))
        val far = PocketPoint(-25.45,-49.27)
        assertFalse(PocketPlanning.fits(request(),path(end = far)))
        assertTrue(PocketPlanning.fits(request(loop = false),path(end = far)))
    }
    @Test fun explorationRewardsNewCoverageWhileStrollPenalizesTooManyTurns() {
        val p = path()
        assertTrue(PocketPlanning.score(request(),p,1500.0) > PocketPlanning.score(request(),p,10.0))
        val shortAllNew = path(seconds = 900.0).copy(distanceMeters = 1000.0)
        assertTrue(PocketPlanning.score(request(),p,1500.0) > PocketPlanning.score(request(),shortAllNew,950.0))
        val stroll = request(PocketPurpose.STROLL)
        assertTrue(PocketPlanning.score(stroll,p,null) > PocketPlanning.score(stroll,path(steps = 20),null))
        assertEquals(PocketPlanning.score(stroll,p,null),PocketPlanning.score(stroll,p,1900.0),0.0)
    }
    @Test fun decoderHandlesNegativeCoordinatesAndRejectsCorruptOrUnboundedInput() {
        val points = listOf(origin,PocketPoint(-25.431234,-49.269876))
        fun encode(value: Long): String {
            var n = if (value < 0) (value shl 1).inv() else value shl 1
            return buildString { while (n >= 32) { append(((32 or (n.toInt() and 31))+63).toChar()); n = n ushr 5 }; append((n+63).toInt().toChar()) }
        }
        var lat = 0L; var lon = 0L
        val encoded = buildString { points.forEach { p ->
            val a = kotlin.math.round(p.latitude*1e6).toLong(); val b = kotlin.math.round(p.longitude*1e6).toLong()
            append(encode(a-lat)); append(encode(b-lon)); lat=a;lon=b
        } }
        assertEquals(points,PocketPlanning.decodePolyline6(encoded))
        listOf("", "_", "_".repeat(100), encoded.dropLast(1)).forEach { value -> assertTrue(runCatching { PocketPlanning.decodePolyline6(value) }.isFailure) }
    }
    @Test fun loopProgressDoesNotJumpToArrivalAtDeparture() {
        val a = origin; val b = PocketPoint(-25.431,-49.27); val c = PocketPoint(-25.432,-49.27)
        val plan = PocketPlan("loop",request(),PocketPath(listOf(a,b,c,PocketPoint(-25.432,-49.268),a),
            listOf(PocketStep(1,"Primeira curva",100.0),PocketStep(4,"Chegada",0.0)),600.0,900.0),100.0)
        val progress = PocketProgress()
        assertEquals("Primeira curva",progress.next(plan,a)?.instruction)
        assertEquals("Chegada",progress.next(plan,c)?.instruction)
        assertTrue(PocketPlanning.geoJson(plan).contains("LineString"))
        assertFalse(PocketPlanning.geoJson(null).contains("coordinates"))
    }
    @Test fun invalidTimeAndInvalidCoordinatesAreRejected() {
        assertTrue(runCatching { request().copy(minutes = 0) }.isFailure)
        assertTrue(runCatching { PocketPoint(Double.NaN,0.0) }.isFailure)
        assertTrue(runCatching { PocketPoint(0.0,190.0) }.isFailure)
    }
    @Test fun destinationKeepsExactEndAndHonorsExtraTimeWithoutSlack() {
        val end=PocketPoint(-25.44,-49.28)
        val r=request().copy(destination=end,detourMinutes=10)
        val points=PocketPlanning.waypoints(r,0)
        assertEquals(origin,points.first());assertEquals(end,points.last());assertEquals(3,points.size)
        val p=path(seconds=1200.0,end=end)
        assertTrue(PocketPlanning.fits(r,p,600.0))
        assertFalse(PocketPlanning.fits(r,p.copy(seconds=1201.0),600.0))
        assertFalse(PocketPlanning.fits(r,p,null))
        assertFalse(PocketPlanning.fits(r,p.copy(points=listOf(origin,end,origin)),600.0))
        assertEquals(listOf(origin,end),PocketPlanning.waypoints(r.copy(detourMinutes=0),0))
        assertTrue(runCatching{r.copy(detourMinutes=61)}.isFailure)
    }
    @Test fun offlineRegionsAreBoundedAndIncludeRouteWithPadding() {
        val points=listOf(origin,PocketPoint(-25.44,-49.28))
        val area=OfflineJourneyArea.forPath(points)
        assertTrue(area.south<points.minOf{it.latitude});assertTrue(area.north>points.maxOf{it.latitude})
        assertTrue(area.west<points.minOf{it.longitude});assertTrue(area.east>points.maxOf{it.longitude})
        assertTrue(area.estimatedTiles in 1..4000)
        assertTrue(runCatching{OfflineJourneyArea.forPath(listOf(origin,PocketPoint(-24.0,-48.0)))}.isFailure)
        assertTrue(runCatching{OfflineJourneyArea.forPath(listOf(PocketPoint(0.0,179.9),PocketPoint(0.0,-179.9)))}.isFailure)
    }

}
