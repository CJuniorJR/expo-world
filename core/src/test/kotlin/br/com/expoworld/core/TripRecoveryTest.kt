package br.com.expoworld.core

import org.junit.Assert.*
import org.junit.Test

class TripRecoveryTest {
    private fun sample(i: Int) = TraceSample(i, 0.0, i * .00001, i.toDouble(), 20.0, i == 0, -1, 0.0)
    @Test fun unfinishedSavedRouteIsRecoveredOnlyWhenInactive() {
        val saved = RecordedTrace(1000, 1000, listOf(sample(0)), completed = false, newAreaSquareMeters = 100.0)
        assertTrue(saved.isInterrupted(false))
        assertFalse(saved.isInterrupted(true))
        assertFalse(saved.copy(completed = true).isInterrupted(false))
        assertFalse(saved.copy(samples = emptyList()).isInterrupted(false))
    }
    @Test fun chunksCoverEveryEdgeExactlyOnceIncludingBreaks() {
        for (size in listOf(0, 1, 255, 256, 257, 258, 513, 20_000)) {
            val samples = (0 until size).map { sample(it).copy(breakBefore = it % 101 == 0) }
            val chunks = TraceChunks.split(samples)
            assertTrue(chunks.recent.size <= TraceChunks.SIZE)
            val allEdges = chunks.history.zipWithNext() + chunks.recent.zipWithNext()
            assertEquals(samples.zipWithNext(), allEdges)
            assertEquals(samples.zipWithNext().count { !it.second.breakBefore }, allEdges.count { !it.second.breakBefore })
        }
    }
    @Test fun frozenHistoryDoesNotChangeWithinChunkAndLiveTailIsBounded() {
        val points = (0 until 1000).map { sample(it) }
        assertEquals(TraceChunks.split(points.take(800)).history, TraceChunks.split(points.take(900)).history)
        assertEquals(2, TraceChunks.live(points, sample(1000)).size)
        val head = sample(1000).copy(breakBefore = true)
        assertFalse(TracePresentation.lineGeoJson(TraceChunks.live(points, head)).contains("LineString"))
        assertEquals(listOf(points.last()), TraceChunks.live(points, sample(0)))
    }
}
