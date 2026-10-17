package br.com.expoworld.storage

import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
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
    suspend fun export(uri: Uri, mode: String, title: String, animated: Boolean) = withContext(Dispatchers.IO) {
        val route = app.repository.route(mode).first()?.takeIf { it.samples.isNotEmpty() } ?: error("Nenhum percurso salvo.")
        val map = ExpeditionMapLoader.load(app, route)
        val theme = app.theme.value
        val date = SimpleDateFormat("dd.MM.yyyy", Locale.forLanguageTag("pt-BR")).format(Date(route.startedAtMillis))
        val temp = File.createTempFile("expedition-", if (animated) ".gif" else ".png", app.cacheDir)
        try {
            withContext(Dispatchers.Default) {
                if (animated) {
                    val width = 720; val height = 960
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val pixels = IntArray(width * height); val indexed = ByteArray(pixels.size)
                    try {
                        temp.outputStream().buffered().use { output ->
                            // Learn colors from the complete card once; reuse its map and
                            // palette throughout the animation, with no per-frame color shifts.
                            ExpeditionRenderer.draw(Canvas(bitmap), ExpeditionArt.scene(route, title, date, theme = theme, mapPoint = map.point), width.toFloat(), height.toFloat(), map = map.bitmap)
                            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                            val palette = ExpeditionPalette.from(pixels, intArrayOf(0x000000, 0xFFFFFF,
                                0x101C22, 0xFFD166, theme.accent, theme.traceDark, theme.traceLight))
                            val gif = ExpeditionGif(output, width, height, palette.colors)
                            val timeline = TraceRenderTimeline(route.activity.stopKmh)
                            for (frame in 0 until 100) {
                                coroutineContext.ensureActive()
                                ExpeditionRenderer.draw(Canvas(bitmap), ExpeditionArt.scene(route, title, date, frame / 99f, theme, map.point, timeline), width.toFloat(), height.toFloat(), true, map.bitmap)
                                bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                                palette.index(pixels, indexed)
                                gif.frame(indexed, if (frame == 99) 150 else 10)
                            }
                            gif.finish()
                        }
                    } finally { bitmap.recycle() }
                } else {
                    val bitmap = Bitmap.createBitmap(ExpeditionArt.WIDTH, ExpeditionArt.HEIGHT, Bitmap.Config.ARGB_8888)
                    try {
                        ExpeditionRenderer.draw(Canvas(bitmap), ExpeditionArt.scene(route, title, date, theme = theme, mapPoint = map.point), bitmap.width.toFloat(), bitmap.height.toFloat(), map = map.bitmap)
                        temp.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    } finally { bitmap.recycle() }
                }
            }
            coroutineContext.ensureActive()
            val out = app.contentResolver.openOutputStream(uri, "wt") ?: error("Destino indisponível.")
            out.use { target -> temp.inputStream().use { it.copyTo(target) } }
        } finally { temp.delete(); map.bitmap.recycle() }
    }
}
