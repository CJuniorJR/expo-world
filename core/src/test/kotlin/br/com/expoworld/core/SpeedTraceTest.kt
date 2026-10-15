package br.com.expoworld.core

import org.junit.Assert.*
import org.junit.Test

class SpeedTraceTest {
    private fun fix(time: Int, x: Double = 0.0, speed: Double? = 0.0, accuracy: Double = 5.0) =
        Fix(0.0, Geo.longitude(x), accuracy, 100.0 + time, speed)
    @Test fun gpsVelocityIsPreferredAndConvertedToKmh() {
        val sample = TraceSampler().accept(fix(0, speed = 15.0), false)!!
        assertEquals(54.0, sample.speedKmh!!, .001)
    }
    @Test fun firstUnknownSpeedDoesNotMeanStopped() {
        val sample = TraceSampler().accept(fix(0, speed = null), false)!!
        assertNull(sample.speedKmh)
        assertEquals(-1, sample.stopId)
        assertEquals(TracePresentation.UNKNOWN_COLOR, TracePresentation.color(sample.speedKmh))
    }
    @Test fun fallbackUsesMeterDistanceAndElapsedTime() {
        val sampler = TraceSampler()
        val samples = (0..5).mapNotNull { sampler.accept(fix(it, x = it * 10.0, speed = null), it > 0) }
        assertEquals(36.0, samples.last().speedKmh!!, .01)
    }
    @Test fun uncertainFallbackDoesNotInventStops() {
        val sampler = TraceSampler()
        val samples = (0..10).mapNotNull { sampler.accept(fix(it, speed = null, accuracy = 25.0), it > 0) }
        assertTrue(samples.all { it.speedKmh == null })
        assertTrue(TracePresentation.stops(samples).isEmpty())
    }
    @Test fun speedChangesAreSmoothedAndBecomeDarker() {
        val sampler = TraceSampler()
        val fast = sampler.accept(fix(0, speed = 15.0), false)!!
        val firstSlow = sampler.accept(fix(1, speed = 0.0), true)!!
        assertTrue(firstSlow.speedKmh!! in 0.1..53.9)
        val slow = (2..20).mapNotNull { sampler.accept(fix(it, speed = 0.0), true) }.last()
        assertTrue(slow.speedKmh!! < firstSlow.speedKmh!!)
        assertNotEquals(TracePresentation.color(fast.speedKmh), TracePresentation.color(slow.speedKmh))
    }
    @Test fun stationarySamplesProduceOneStopWithDuration() {
        val sampler = TraceSampler()
        val samples = (0..20).mapNotNull { sampler.accept(fix(it), it > 0) }
        val stops = TracePresentation.stops(samples)
        assertEquals(1, stops.size)
        assertEquals(20.0, stops.single().durationSeconds, .001)
        assertTrue(TracePresentation.stopGeoJson(samples).contains("\"type\":\"Point\""))
        assertFalse(TracePresentation.lineGeoJson(samples).contains("LineString"))
    }
    @Test fun finalFixFlushesTheLastSecondsOfAStop() {
        val sampler = TraceSampler()
        val samples = (0..7).mapNotNull { sampler.accept(fix(it), it > 0) } + listOfNotNull(sampler.finish())
        assertEquals(7.0, TracePresentation.stops(samples).single().durationSeconds, .001)
        assertNull(sampler.finish())
    }
    @Test fun gapSeparatesRouteAndStopDurations() {
        val sampler = TraceSampler()
        val a = sampler.accept(fix(0, speed = 5.0), false)!!
        val b = sampler.accept(fix(5, x = 25.0, speed = 5.0), true)!!
        val c = sampler.accept(fix(100, x = 3000.0, speed = 0.0), false)!!
        assertTrue(c.breakBefore)
        assertEquals(0.0, c.stopSeconds, .001)
        assertEquals(1, Regex("LineString").findAll(TracePresentation.lineGeoJson(listOf(a, b, c))).count())
    }
    @Test fun rejectedFixBreaksNextTraceSegment() {
        val sampler = TraceSampler()
        sampler.accept(fix(0), false)
        sampler.breakContinuity()
        assertTrue(sampler.accept(fix(5), true)!!.breakBefore)
    }
    @Test fun liveHeadExtendsOnlyForwardAndRespectsGaps() {
        val sampler = TraceSampler()
        val stored = sampler.accept(fix(0, speed = 5.0), false)!!
        sampler.accept(fix(1, x = 5.0, speed = 5.0), true)
        val live = TracePresentation.withLiveHead(listOf(stored), sampler.liveHead())
        assertEquals(2, live.size)
        assertTrue(TracePresentation.lineGeoJson(live).contains("LineString"))
        assertEquals(live, TracePresentation.withLiveHead(live, stored))
        sampler.breakContinuity()
        sampler.accept(fix(2, x = 10.0, speed = 5.0), true)
        val broken = TracePresentation.withLiveHead(listOf(stored), sampler.liveHead())
        assertFalse(TracePresentation.lineGeoJson(broken).contains("LineString"))
    }
    @Test fun simulationIncludesFastSlowAndStoppedSamples() {
        val filter = FixFilter(); val sampler = TraceSampler()
        val samples = (0..SimulatedRoute.LAST_INDEX).mapNotNull { i ->
            val point = SimulatedRoute.fix(i, 100.0 + i)
            val accepted = filter.accept(point, 100.0 + i) as FilterResult.Accepted
            sampler.accept(point, accepted.previous != null)
        } + listOfNotNull(sampler.finish())
        assertTrue(samples.any { it.speedKmh!! > 40 })
        assertTrue(samples.any { it.speedKmh!! in 3.0..15.0 })
        assertTrue(TracePresentation.stops(samples).any { it.durationSeconds >= 5 })
    }
}
