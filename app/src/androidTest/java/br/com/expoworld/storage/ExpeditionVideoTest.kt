package br.com.expoworld.storage

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ExpeditionVideoTest {
    @Test fun codecProducesReadableAvcWithAccurateTimingAndVisibleColors() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val output = File.createTempFile("video-codec-test", ".mp4", context.cacheDir)
        val extractor = MediaExtractor()
        val retriever = MediaMetadataRetriever()
        try {
            val progress = mutableListOf<Float>()
            ExpeditionVideoEncoder.encode(output, 144, 192, 12, 12, { canvas, frame ->
                canvas.drawColor(Color.RED)
                canvas.drawRect(frame.toFloat(), 0f, frame + 24f, 24f, Paint().apply { color = Color.WHITE })
            }, { progress += it })
            extractor.setDataSource(output.absolutePath)
            assertEquals(1, extractor.trackCount)
            val format = extractor.getTrackFormat(0)
            assertEquals(MediaFormat.MIMETYPE_VIDEO_AVC, format.getString(MediaFormat.KEY_MIME))
            assertEquals(144, format.getInteger(MediaFormat.KEY_WIDTH))
            assertEquals(192, format.getInteger(MediaFormat.KEY_HEIGHT))
            assertTrue(format.getLong(MediaFormat.KEY_DURATION) in 900_000L..1_100_000L)
            assertEquals(1f, progress.last(), 0f)
            assertTrue(progress.zipWithNext().all { it.first <= it.second })
            retriever.setDataSource(output.absolutePath)
            val bitmap = retriever.getFrameAtTime(500_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)!!
            try {
                val color = bitmap.getPixel(72, 96)
                assertTrue("Encoded red must remain red", Color.red(color) > 200 && Color.green(color) < 50 && Color.blue(color) < 50)
            } finally { bitmap.recycle() }
        } finally { extractor.release(); retriever.release(); output.delete() }
    }

    @Test fun cancelledEncodingReleasesTheCodecSoAnotherExportCanStart() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val output = File.createTempFile("video-cancel-test", ".mp4", context.cacheDir)
        try {
            var cancelled = false
            try {
                ExpeditionVideoEncoder.encode(output, 144, 192, 12, 12, { canvas, frame ->
                    if (frame == 2) throw CancellationException("Cancelamento de teste")
                    canvas.drawColor(Color.BLUE)
                })
            } catch (_: CancellationException) { cancelled = true }
            assertTrue(cancelled)
            ExpeditionVideoEncoder.encode(output, 144, 192, 12, 3, { canvas, _ -> canvas.drawColor(Color.GREEN) })
            assertTrue(output.length() > 0)
        } finally { output.delete() }
    }
}
