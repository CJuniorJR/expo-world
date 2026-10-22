package br.com.expoworld.storage

import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.provider.DocumentsContract
import br.com.expoworld.WorldApp
import br.com.expoworld.core.*
import br.com.expoworld.ui.ExpeditionRenderer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.coroutineContext

class ExpeditionExporter(private val app: WorldApp) {
    suspend fun export(uri: Uri, mode: String, title: String, animated: Boolean,
        onProgress: (Float) -> Unit = {}) = withContext(Dispatchers.IO) {
        var map: ExpeditionMapImage? = null
        var temp: File? = null
        try {
            onProgress(0f)
            val route = recordedRoute(mode)
            val image = ExpeditionMapLoader.load(app, route).also { map = it }
            onProgress(.12f)
            val theme = app.theme.value
            val date = date(route)
            val target = File.createTempFile("expedition-", if (animated) ".gif" else ".png", app.cacheDir).also { temp = it }
            withContext(Dispatchers.Default) {
                if (animated) {
                    val width = 720; val height = 960
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val pixels = IntArray(width * height); val indexed = ByteArray(pixels.size)
                    try {
                        target.outputStream().buffered().use { output ->
                            // One map and one learned palette prevent color changes between GIF frames.
                            ExpeditionRenderer.draw(Canvas(bitmap), ExpeditionArt.scene(route, title, date, theme = theme, mapPoint = image.point), width.toFloat(), height.toFloat(), map = image.bitmap)
                            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                            val palette = ExpeditionPalette.from(pixels, intArrayOf(0x000000, 0xFFFFFF,
                                0x101C22, 0xFFD166, theme.accent, theme.traceDark, theme.traceLight))
                            val gif = ExpeditionGif(output, width, height, palette.colors)
                            val timeline = TraceRenderTimeline(route.activity.stopKmh)
                            for (frame in 0 until 100) {
                                coroutineContext.ensureActive()
                                ExpeditionRenderer.draw(Canvas(bitmap), ExpeditionArt.scene(route, title, date, frame / 99f, theme, image.point, timeline), width.toFloat(), height.toFloat(), true, image.bitmap)
                                bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                                palette.index(pixels, indexed)
                                gif.frame(indexed, if (frame == 99) 150 else 10)
                                onProgress(.12f + .80f * (frame + 1) / 100)
                                yield()
                            }
                            gif.finish()
                        }
                    } finally { bitmap.recycle() }
                } else {
                    val bitmap = Bitmap.createBitmap(ExpeditionArt.WIDTH, ExpeditionArt.HEIGHT, Bitmap.Config.ARGB_8888)
                    try {
                        ExpeditionRenderer.draw(Canvas(bitmap), ExpeditionArt.scene(route, title, date, theme = theme, mapPoint = image.point), bitmap.width.toFloat(), bitmap.height.toFloat(), map = image.bitmap)
                        target.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                        onProgress(.92f)
                    } finally { bitmap.recycle() }
                }
            }
            copyToDocument(target, uri, onProgress)
        } catch (error: Throwable) {
            // CreateDocument has already created this destination, even if rendering was cancelled.
            deleteDestination(uri)
            throw error
        } finally { temp?.delete(); map?.bitmap?.recycle() }
    }

    suspend fun exportVideo(uri: Uri, mode: String, title: String,
        onProgress: (Float) -> Unit = {}) = withContext(Dispatchers.IO) {
        var map: ExpeditionMapImage? = null
        var temp: File? = null
        try {
            onProgress(0f)
            val route = recordedRoute(mode)
            val image = ExpeditionMapLoader.load(app, route).also { map = it }
            onProgress(.12f)
            val theme = app.theme.value
            val date = date(route)
            val fog = videoFog(mode, route, image)
            onProgress(.18f)
            val output = File.createTempFile("expedition-", ".mp4", app.cacheDir).also { temp = it }
            withContext(Dispatchers.Default) {
                val timeline = TraceRenderTimeline(route.activity.stopKmh)
                ExpeditionVideoMarker(route.activity, theme).use { marker ->
                    var heading = 0.0
                    var lastFraction = -1f
                    ExpeditionVideoEncoder.encode(output, ExpeditionVideo.WIDTH, ExpeditionVideo.HEIGHT,
                        ExpeditionVideo.FRAME_RATE, ExpeditionVideo.FRAME_COUNT, { canvas, frameIndex ->
                            val fraction = ExpeditionVideo.fraction(frameIndex)
                            // Reuse the complete card for the final reading pause.
                            if (fraction != lastFraction) {
                                val frame = ReplayTimeline.frame(route.samples, fraction)
                                val headIndex = frame.indexOfLast { it.accepted }
                                val head = frame.getOrNull(headIndex)
                                if (head != null) {
                                    for (at in headIndex - 1 downTo 0) {
                                        val previous = frame[at]
                                        if (!previous.accepted || frame.getOrNull(at + 1)?.breakBefore == true) break
                                        val a = Fix(previous.latitude, previous.longitude, 1.0, 0.0)
                                        val b = Fix(head.latitude, head.longitude, 1.0, 0.0)
                                        if (Geo.distance(a, b) > 1.0) { heading = Geo.bearing(a, b); break }
                                    }
                                }
                                val scene = ExpeditionArt.scene(route, title, date, fraction, theme, image.point, timeline)
                                val commands = if (fog == null) scene else scene + ArtCommand.Text(80.0, 1106.0,
                                    "REVELAÇÃO: ÁREA NOVA DESTE PERCURSO", "#AFBFC6", 18.0)
                                ExpeditionRenderer.draw(canvas, commands, ExpeditionVideo.WIDTH.toFloat(), ExpeditionVideo.HEIGHT.toFloat(), map = image.bitmap,
                                    mapOverlay = fog?.let { overlay -> { c -> overlay.draw(c, ReplayTimeline.elapsed(route.samples, fraction)) } })
                                head?.let { marker.draw(canvas, image.point(it), heading, ExpeditionVideo.WIDTH.toFloat(), ExpeditionVideo.HEIGHT.toFloat()) }
                                lastFraction = fraction
                            }
                        }, { progress -> onProgress(.18f + .74f * progress) })
                }
            }
            copyToDocument(output, uri, onProgress)
        } catch (error: Throwable) {
            deleteDestination(uri)
            throw error
        } finally { temp?.delete(); map?.bitmap?.recycle() }
    }

    private suspend fun recordedRoute(mode: String): RecordedTrace {
        require(mode == "real" || mode == "simulation" || mode.startsWith("saved:")) { "Percurso inválido." }
        val route = app.repository.route(mode).first()?.takeIf { data -> data.samples.any { it.accepted } }
            ?: error("Nenhum percurso registrado para exportar.")
        // Saved real trips retain their explicit repository key, but never get a simulation label.
        return if (mode.startsWith("saved:")) route.copy(mode = "real") else route
    }

    private suspend fun videoFog(mode: String, route: RecordedTrace, map: ExpeditionMapImage): ExpeditionVideoFog? {
        val samples = route.samples.filter { it.accepted }
        val base = Geo.bounds(samples.minOf { it.latitude }, samples.minOf { it.longitude },
            samples.maxOf { it.latitude }, samples.maxOf { it.longitude })
        val bounds = CellBounds(base.minX - 10, base.maxX + 10, base.minY - 10, base.maxY + 10)
        val discoveries = app.repository.timeline(mode, bounds, 20_001)
        // Legacy timelines lack timing; large trips keep the map and animated trace without a mask.
        if (discoveries.size > 20_000 || discoveries.none { it.elapsedSeconds >= 0 }) return null
        return ExpeditionVideoFog(discoveries, map)
    }

    private fun deleteDestination(uri: Uri) {
        runCatching {
            if (DocumentsContract.isDocumentUri(app, uri)) DocumentsContract.deleteDocument(app.contentResolver, uri)
            else app.contentResolver.delete(uri, null, null)
        }
    }

    private fun date(route: RecordedTrace) = SimpleDateFormat("dd.MM.yyyy", Locale.forLanguageTag("pt-BR")).format(Date(route.startedAtMillis))

    private suspend fun copyToDocument(file: File, uri: Uri, onProgress: (Float) -> Unit) {
        coroutineContext.ensureActive()
        val out = app.contentResolver.openOutputStream(uri, "wt") ?: error("Destino indisponível.")
        out.use { target -> file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            var copied = 0L
            while (true) {
                coroutineContext.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                target.write(buffer, 0, count); copied += count
                onProgress(.92f + .08f * copied / file.length().coerceAtLeast(1))
            }
        } }
        onProgress(1f)
    }
}
