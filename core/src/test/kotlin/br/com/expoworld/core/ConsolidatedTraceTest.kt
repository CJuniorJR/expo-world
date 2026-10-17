package br.com.expoworld.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class ConsolidatedTraceTest {
    // Ground-meter fixtures at Curitiba, not degrees treated as meters.
    private val origin = Geo.project(-25.43, -49.27)
    private val scale = cos(Math.toRadians(-25.43))
    private fun point(i: Int, x: Double, y: Double = 0.0, gap: Boolean = i == 0, road: String? = null, accuracy: Double = 5.0) =
        TraceSample(i, Geo.latitude(origin.y + y / scale), Geo.longitude(origin.x + x / scale), i * 10.0,
            10.0 + i, gap, -1, 0.0, accuracy, streetIds = road)
    private fun route(xs: List<Int>) = xs.mapIndexed { i, x -> point(i, x.toDouble()) }
    private fun overlay(samples: List<TraceSample>) = ConsolidatedTrace().also { engine -> samples.forEach(engine::append) }
    private fun length(pieces: List<TracePiece>) = pieces.sumOf { it.meters }
    private fun signature(pieces: List<TracePiece>) = pieces.map { listOf(it.from.latitude, it.from.longitude, it.to.latitude, it.to.longitude, it.sequence, it.speedKmh) }.toSet()
    private val forward = (0..100 step 10).toList()
    private fun cases(): List<List<TraceSample>> {
        val twice = forward.mapIndexed { i, x -> point(i, x.toDouble()) } + forward.mapIndexed { i, x -> point(i + 11, x.toDouble(), gap = i == 0) }
        val circle = listOf(0.0 to 0.0, 80.0 to 0.0, 80.0 to 80.0, 0.0 to 80.0, 0.0 to 0.0)
            .mapIndexed { i, (x, y) -> point(i, x, y) }
        return listOf(route(forward), route(forward + (90 downTo 0 step 10)), twice,
            route(forward + (90 downTo 0 step 10) + (10..100 step 10)),
            listOf(point(0, 0.0), point(1, 100.0), point(2, 25.0, gap = true), point(3, 75.0)), circle)
    }
    @Test fun simpleRouteKeepsEverySegmentAndDoesNotEditHistory() {
        val samples = cases()[0]; val original = samples.toList()
        val engine = overlay(samples)
        assertEquals(100.0, length(engine.snapshot()), .01)
        assertEquals(10, engine.snapshot().size)
        assertEquals(original, samples)
        assertEquals(100.0, TripSummary.from(RecordedTrace(0, 1, samples)).distanceMeters, .01)
    }
    @Test fun outAndBackKeepsLatestReverseDirectionButBothPassagesInStatistics() {
        val samples = cases()[1]; val visible = overlay(samples).snapshot()
        assertEquals(100.0, length(visible), .01)
        assertTrue(visible.all { it.to.longitude < it.from.longitude && it.sequence >= 11 })
        assertEquals(200.0, TripSummary.from(RecordedTrace(0, 1, samples)).distanceMeters, .01)
        assertEquals(21, samples.size)
    }
    @Test fun sameDirectionSecondPassAndThreePassesUseLatestColorAndDirection() {
        for (samples in listOf(cases()[2], cases()[3])) {
            val visible = overlay(samples).snapshot()
            assertEquals(100.0, length(visible), .01)
            assertTrue(visible.all { it.sequence > samples.size - 11 && it.to.longitude > it.from.longitude })
            assertTrue(visible.all { it.speedKmh == samples[it.sequence].speedKmh })
        }
    }
    @Test fun partialRepetitionSplitsOnlyCoveredInterval() {
        val samples = cases()[4]; val visible = overlay(samples).snapshot()
        assertEquals(3, visible.size)
        assertEquals(100.0, length(visible), .01)
        assertEquals(50.0, length(visible.filter { it.sequence == 1 }), .01)
        assertEquals(50.0, length(visible.filter { it.sequence == 3 }), .01)
        assertEquals(150.0, TripSummary.from(RecordedTrace(0, 1, samples)).distanceMeters, .01)
    }
    @Test fun circularRouteRemainsClosedAndItsSecondLapReplacesTheFirst() {
        val circle = cases()[5]
        val laps = circle + circle.drop(1).mapIndexed { i, sample -> sample.copy(sequence = i + 5, elapsedSeconds = (i + 5) * 10.0) }
        val visible = overlay(laps).snapshot()
        assertEquals(320.0, length(visible), .02)
        assertEquals(4, visible.size)
        assertTrue(visible.all { it.sequence >= 5 })
        assertEquals(640.0, TripSummary.from(RecordedTrace(0, 1, laps)).distanceMeters, .02)
    }
    @Test fun replayOfAllSixScenariosVisitsEveryOriginalPointAndMatchesLiveConsolidation() {
        for (samples in cases()) {
            val replay = TraceRenderTimeline()
            val live = ConsolidatedTrace()
            samples.forEachIndexed { i, sample ->
                live.append(sample)
                // The replay source contains the entire chronological prefix, even repeats.
                val fraction = i.toFloat() / samples.lastIndex
                val frame = ReplayTimeline.frame(samples, fraction)
                assertEquals(sample.longitude, frame.last().longitude, .000001)
                assertEquals(sample.latitude, frame.last().latitude, .000001)
                val visible = replay.frame(samples.take(i + 1))
                assertEquals(signature(live.snapshot()), signature(visible))
            }
            assertEquals(samples, ReplayTimeline.frame(samples, 1f))
            assertEquals(samples, ReplayTimeline.visualSamples(samples))
            assertEquals(samples.size, replay.processedSamples)
            assertEquals(0, replay.rebuilds)
        }
    }
    @Test fun interpolatedReturnProgressivelyReplacesAndRewindingDoesNotLeakFutureGeometry() {
        val samples = listOf(point(0, 0.0), point(1, 100.0), point(2, 0.0))
        val timeline = TraceRenderTimeline()
        timeline.frame(ReplayTimeline.frame(samples, .5f))
        val returning = timeline.frame(ReplayTimeline.frame(samples, .75f))
        assertEquals(100.0, length(returning), .01)
        assertEquals(50.0, length(returning.filter { it.to.longitude < it.from.longitude }), .01)
        val nearEnd = timeline.frame(ReplayTimeline.frame(samples, .9f))
        assertEquals(80.0, length(nearEnd.filter { it.to.longitude < it.from.longitude }), .01)
        assertEquals(2, timeline.processedSamples) // Interpolation never enters committed prefix.
        val rewind = timeline.frame(ReplayTimeline.frame(samples, .25f))
        assertEquals(50.0, length(rewind), .01)
        assertTrue(rewind.all { it.to.longitude > it.from.longitude })
        assertEquals(1, timeline.rebuilds)
        assertEquals(100.0, length(timeline.frame(samples)), .01)
        assertEquals(3, samples.size)
    }
    @Test fun crossingParallelRoadsAndUncertainFixesAreNotConsolidated() {
        val crossing = listOf(point(0, 0.0), point(1, 100.0), point(2, 50.0, -50.0, true), point(3, 50.0, 50.0))
        assertEquals(200.0, length(overlay(crossing).snapshot()), .01)
        val parallel = listOf(point(0, 0.0), point(1, 100.0), point(2, 0.0, 7.0, true), point(3, 100.0, 7.0))
        assertEquals(200.0, length(overlay(parallel).snapshot()), .01)
        val distinct = parallel.mapIndexed { i, s -> s.copy(latitude = s.latitude - if (i > 1) Math.toDegrees(6.0 / Geo.R) else 0.0, streetIds = if (i < 2) "road-A" else "road-B") }
        assertEquals(200.0, length(overlay(distinct).snapshot()), .01) // Even ~1m apart with known different IDs.
        val shallow = listOf(point(0, 0.0), point(1, 100.0), point(2, 0.0, -.6, true), point(3, 100.0, .6))
        assertEquals(200.0, length(overlay(shallow).snapshot()), .02)
        val uncertain = parallel.map { it.copy(accuracyMeters = 20.0) }
        assertEquals(200.0, length(overlay(uncertain).snapshot()), .01)
    }
    @Test fun smallGpsOffsetWithSameStreetReplacesTheOldPassageInGroundMeters() {
        val samples = listOf(point(0, 0.0, road = "A"), point(1, 100.0, road = "A"),
            point(2, 0.0, 2.0, true, "A"), point(3, 100.0, 2.0, road = "A"))
        val visible = overlay(samples).snapshot()
        assertEquals(100.0, length(visible), .01)
        assertTrue(visible.all { it.sequence == 3 })
    }
    @Test fun partialReplacementWorksWithDifferentSamplingAndKeepsOldSpeedAtBothEnds() {
        val first = (0..100 step 10).mapIndexed { i, x -> point(i, x.toDouble()).copy(speedKmh = 60.0) }
        val second = listOf(75.0, 58.0, 36.0, 25.0).mapIndexed { i, x -> point(11 + i, x, .7, gap = i == 0).copy(speedKmh = 5.0) }
        val visible = overlay(first + second).snapshot()
        assertEquals(100.0, length(visible), .02)
        assertEquals(50.0, length(visible.filter { it.sequence <= 10 }), .02)
        assertTrue(visible.filter { it.sequence <= 10 }.all { it.speedKmh == 60.0 })
        assertTrue(visible.filter { it.sequence > 10 }.all { it.speedKmh == 5.0 && it.to.longitude < it.from.longitude })
    }
    @Test fun pauseResumeStopAndRejectedReadingsRetainHistoryWithoutBridgingGaps() {
        val sampler = TraceSampler()
        fun fix(t: Int, x: Double, accuracy: Double = 5.0) = Fix(-25.43, Geo.longitude(origin.x + x / scale), accuracy, t.toDouble(), 5.0)
        val samples = mutableListOf(sampler.accept(fix(0, 0.0), false), sampler.accept(fix(10, 50.0), true))
        sampler.finish()?.let(samples::add)
        sampler.breakContinuity() // Pause/resume; preserve elapsed time, no connecting line.
        samples += sampler.accept(fix(30, 200.0), true)
        samples += sampler.reject(fix(31, 5000.0, 100.0))
        samples += sampler.accept(fix(40, 220.0), true)
        samples += sampler.accept(fix(50, 240.0), true)
        sampler.finish()?.let(samples::add)
        assertEquals(6, samples.size)
        assertEquals((0..5).toList(), samples.map { it.sequence })
        assertFalse(samples[3].accepted)
        assertTrue(samples[2].breakBefore && samples[4].breakBefore)
        assertEquals(70.0, length(overlay(samples).snapshot()), .01)
        assertEquals(70.0, TripSummary.from(RecordedTrace(0, 1, samples)).distanceMeters, .01)
        assertFalse(TracePresentation.lineGeoJson(samples).contains(samples[3].longitude.toString()))
        assertEquals(signature(overlay(samples).snapshot()), signature(TraceRenderTimeline().frame(ReplayTimeline.frame(samples, 1f))))
    }
    @Test fun historyKeepsEveryFixBeyondTheOldLimitAndRenderingIsIncremental() {
        val sampler = TraceSampler()
        val samples = (0..20_100).map { i -> sampler.accept(Fix(-25.43, Geo.longitude(origin.x + i * 3 / scale), 5.0, i.toDouble(), 3.0), i > 0) }
        assertEquals(20_101, samples.size)
        assertEquals(20_100, samples.last().sequence)
        assertEquals(samples, ReplayTimeline.visualSamples(samples))
        val engine = ConsolidatedTrace()
        samples.forEach(engine::append)
        assertEquals(samples.size, engine.processedSamples)
        assertTrue("Nearby candidates must not grow quadratically: ${engine.candidateChecks}", engine.candidateChecks < samples.size * 100L)
        val timeline = TraceRenderTimeline()
        for (i in 1..200) timeline.frame(samples.subList(0, i))
        assertEquals(200, timeline.processedSamples)
        assertEquals(0, timeline.rebuilds)
    }
    @Test fun canceledWorkerCanContinueFromItsCommittedPrefix() {
        val samples = (0..400).map { point(it, it * 3.0) }
        val timeline = TraceRenderTimeline()
        var checks = 0
        try {
            timeline.frame(samples) { if (++checks == 2) throw java.util.concurrent.CancellationException() }
            fail("Cancellation must interrupt a long first render")
        } catch (_: java.util.concurrent.CancellationException) { }
        assertEquals(128, timeline.processedSamples)
        val result = timeline.frame(samples)
        assertEquals(samples.size, timeline.processedSamples)
        assertEquals(signature(overlay(samples).snapshot()), signature(result))
        assertEquals(0, timeline.rebuilds)
    }
    @Test fun manyLapsHaveBoundedVisualGeometryAndDirectionsFollowLatestPassage() {
        val engine = ConsolidatedTrace()
        var i = 0
        repeat(100) { lap ->
            val xs = if (lap % 2 == 0) forward else forward.reversed()
            xs.forEach { x -> engine.append(point(i++, x.toDouble())) }
        }
        val visible = engine.snapshot()
        assertEquals(100.0, length(visible), .01)
        assertEquals(10, visible.size)
        assertTrue(visible.all { it.bearing in 269.0..271.0 })
        val json = ConsolidatedTracePresentation.directions(visible)
        assertTrue(json.contains("bearing"))
        assertFalse(json.contains("NaN"))
    }
    @Test fun curitibaRetraceSimulationRetainsItsReturnAndPassesGpsFilter() {
        val filter = FixFilter(); val sampler = TraceSampler()
        val history = (0..SimulatedRoute.LAST_INDEX).map { i ->
            val fix = SimulationScenario.RETRACE.fix(i, i.toDouble())!!
            val result = filter.accept(fix, fix.monotonicSeconds)
            assertTrue("GPS filter rejected simulation at $i", result is FilterResult.Accepted)
            sampler.accept(fix, (result as FilterResult.Accepted).previous != null)
        }
        assertEquals(history.first().latitude, history.last().latitude, .00000001)
        assertEquals(history.first().longitude, history.last().longitude, .00000001)
        val summary = TripSummary.from(RecordedTrace(0, 1, history))
        val visible = overlay(history).snapshot()
        assertTrue(length(visible) < summary.distanceMeters * .6)
        assertEquals(history, ReplayTimeline.frame(history, 1f))
    }
}
