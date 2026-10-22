package br.com.expoworld.core

import org.junit.Assert.*
import org.junit.Test

class ExpeditionVideoTest {
    @Test fun timelineHasMonotonicTimestampsAndFinalHold() {
        assertEquals(0f, ExpeditionVideo.fraction(0), 0f)
        assertTrue(ExpeditionVideo.fraction(100) in 0f..1f)
        assertEquals(1f, ExpeditionVideo.fraction(239), 0f)
        assertEquals(1f, ExpeditionVideo.fraction(287), 0f)
        assertTrue((0 until 287).all { ExpeditionVideo.timestampUs(it + 1) > ExpeditionVideo.timestampUs(it) })
        assertEquals(12_000_000L, ExpeditionVideo.timestampUs(288))
    }
    @Test fun blackWhiteAndRedUseLimitedRangeBt601() {
        fun encode(color: Int): List<Int> {
            val output = ByteArray(6)
            ExpeditionYuv.encode(IntArray(4) { color }, 2, 2, output)
            return output.map { it.toInt() and 255 }
        }
        assertEquals(listOf(16, 16, 16, 16, 128, 128), encode(0xFF000000.toInt()))
        assertEquals(listOf(235, 235, 235, 235, 128, 128), encode(0xFFFFFFFF.toInt()))
        assertEquals(listOf(82, 82, 82, 82, 90, 240), encode(0xFFFF0000.toInt()))
    }
    @Test fun chromaAveragesTheWholeBlockAndPreservesRowOrder() {
        val output = ByteArray(6)
        ExpeditionYuv.encode(intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt()), 2, 2, output)
        assertEquals(listOf(16, 235, 82, 144), output.take(4).map { it.toInt() and 255 })
        assertEquals(100, output[4].toInt() and 255)
        assertEquals(133, output[5].toInt() and 255)
    }
    @Test fun invalidDimensionsAndBuffersAreRejected() {
        assertTrue(runCatching { ExpeditionYuv.encode(IntArray(9), 3, 3, ByteArray(13)) }.isFailure)
        assertTrue(runCatching { ExpeditionYuv.encode(IntArray(4), 2, 2, ByteArray(5)) }.isFailure)
        assertTrue(runCatching { ExpeditionYuv.encode(IntArray(3), 2, 2, ByteArray(6)) }.isFailure)
    }
}
