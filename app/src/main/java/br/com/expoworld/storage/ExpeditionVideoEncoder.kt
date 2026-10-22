package br.com.expoworld.storage

import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import android.os.Build
import br.com.expoworld.core.ExpeditionYuv
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

class VideoExportUnavailable : IOException("Este aparelho não oferece codificador MP4 compatível com 720 × 960. A imagem e o GIF continuam disponíveis.")

/** One reusable bitmap and I420 buffer; codec planes may have independent row/pixel strides. */
internal object ExpeditionVideoEncoder {
    suspend fun encode(file: File, width: Int, height: Int, fps: Int, frameCount: Int,
        drawFrame: (Canvas, Int) -> Unit, onProgress: (Float) -> Unit = {}) {
        require(width % 2 == 0 && height % 2 == 0 && frameCount > 0 && fps > 0)
        val codec = encoder(width, height, fps)
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var codecStarted = false
        var bitmap: Bitmap? = null
        try {
            muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            codec.start(); codecStarted = true
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val pixels = IntArray(width * height)
            val yuv = ByteArray(width * height * 3 / 2)
            val info = MediaCodec.BufferInfo()
            var track = -1; var nextFrame = 0; var inputEnded = false; var outputEnded = false
            var lastProgress = SystemClock.elapsedRealtime()
            while (!outputEnded) {
                coroutineContext.ensureActive()
                if (!inputEnded) {
                    val input = codec.dequeueInputBuffer(10_000)
                    if (input >= 0) {
                        if (nextFrame < frameCount) {
                            drawFrame(canvas, nextFrame)
                            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                            ExpeditionYuv.encode(pixels, width, height, yuv)
                            val image = codec.getInputImage(input) ?: throw IOException("O codificador de vídeo deste aparelho não oferece entrada YUV compatível.")
                            image.use { fill(it, yuv, width, height) }
                            codec.queueInputBuffer(input, 0, yuv.size, nextFrame * 1_000_000L / fps, 0)
                            nextFrame++
                            onProgress(nextFrame.toFloat() / frameCount)
                        } else {
                            codec.queueInputBuffer(input, 0, 0, frameCount * 1_000_000L / fps, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        }
                        lastProgress = SystemClock.elapsedRealtime()
                    }
                }
                var drain = true
                while (drain && !outputEnded) {
                    coroutineContext.ensureActive()
                    when (val output = codec.dequeueOutputBuffer(info, if (inputEnded) 10_000 else 0)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> drain = false
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            check(!muxerStarted) { "Formato de vídeo alterado durante a gravação." }
                            track = muxer.addTrack(codec.outputFormat)
                            muxer.start(); muxerStarted = true
                            lastProgress = SystemClock.elapsedRealtime()
                        }
                        else -> if (output >= 0) {
                            try {
                                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                                if (info.size > 0) {
                                    check(muxerStarted && track >= 0)
                                    val buffer = codec.getOutputBuffer(output) ?: error("Quadro de vídeo indisponível.")
                                    buffer.position(info.offset); buffer.limit(info.offset + info.size)
                                    muxer.writeSampleData(track, buffer, info)
                                }
                                outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                lastProgress = SystemClock.elapsedRealtime()
                            } finally { codec.releaseOutputBuffer(output, false) }
                        } else drain = false
                    }
                }
                if (SystemClock.elapsedRealtime() - lastProgress > 30_000) throw IOException("O codificador de vídeo parou de responder. Tente exportar novamente.")
                yield()
            }
            check(muxerStarted) { "O aparelho não conseguiu produzir o vídeo." }
            muxer.stop(); muxerStarted = false
        } finally {
            if (codecStarted) runCatching { codec.stop() }
            runCatching { codec.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            bitmap?.recycle()
        }
    }

    private fun encoder(width: Int, height: Int, fps: Int): MediaCodec {
        val mime = MediaFormat.MIMETYPE_VIDEO_AVC
        val candidates = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder && it.supportedTypes.any { type -> type.equals(mime, true) } }
        for (candidate in candidates) {
            var codec: MediaCodec? = null
            try {
                val capabilities = candidate.getCapabilitiesForType(mime)
                if (MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible !in capabilities.colorFormats) continue
                val video = capabilities.videoCapabilities ?: continue
                if (!video.areSizeAndRateSupported(width, height, fps.toDouble())) continue
                val format = MediaFormat.createVideoFormat(mime, width, height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                    setInteger(MediaFormat.KEY_BIT_RATE, video.bitrateRange.clamp(4_500_000))
                    setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    if (Build.VERSION.SDK_INT >= 24) {
                        setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT601_NTSC)
                        setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                        setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                    }
                }
                codec = MediaCodec.createByCodecName(candidate.name)
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                return codec
            } catch (_: Exception) { runCatching { codec?.release() } }
        }
        throw VideoExportUnavailable()
    }

    private fun fill(image: Image, i420: ByteArray, width: Int, height: Int) {
        require(image.planes.size == 3 && image.width >= width && image.height >= height)
        val sizes = intArrayOf(width * height, width * height / 4, width * height / 4)
        var offset = 0
        image.planes.forEachIndexed { index, plane ->
            val w = if (index == 0) width else width / 2
            val h = if (index == 0) height else height / 2
            val buffer = plane.buffer
            val base = buffer.position()
            for (row in 0 until h) {
                val at = base + row * plane.rowStride
                if (plane.pixelStride == 1) {
                    buffer.position(at)
                    buffer.put(i420, offset + row * w, w)
                } else for (column in 0 until w) buffer.put(at + column * plane.pixelStride, i420[offset + row * w + column])
            }
            offset += sizes[index]
        }
    }
}
