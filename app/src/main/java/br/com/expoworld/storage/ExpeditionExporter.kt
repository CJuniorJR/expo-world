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
        val date = SimpleDateFormat("dd.MM.yyyy", Locale.forLanguageTag("pt-BR")).format(Date(route.startedAtMillis))
        val temp = File.createTempFile("expedition-", if (animated) ".gif" else ".png", app.cacheDir)
        try {
            withContext(Dispatchers.Default) {
                if (animated) {
                    val width = 360; val height = 480
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val pixels = IntArray(width * height); val indexed = ByteArray(pixels.size)
                    val paletteCache = HashMap<Int, Byte>()
                    try {
                        temp.outputStream().buffered().use { output ->
                            val gif = ExpeditionGif(output, width, height)
                            for (frame in 0 until 40) {
                                coroutineContext.ensureActive()
                                ExpeditionRenderer.draw(Canvas(bitmap), ExpeditionArt.scene(route, title, date, frame / 39f), width.toFloat(), height.toFloat(), false)
                                bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                                pixels.forEachIndexed { i, pixel -> indexed[i] = paletteCache.getOrPut(pixel) {
                                    ExpeditionGif.PALETTE.indices.minBy { index ->
                                        val color = ExpeditionGif.PALETTE[index]
                                        val r = (pixel shr 16 and 255) - (color shr 16 and 255)
                                        val g = (pixel shr 8 and 255) - (color shr 8 and 255)
                                        val b = (pixel and 255) - (color and 255)
                                        r * r + g * g + b * b
                                    }.toByte()
                                } }
                                gif.frame(indexed, if (frame == 39) 150 else 25)
                            }
                            gif.finish()
                        }
                    } finally { bitmap.recycle() }
                } else {
                    val bitmap = Bitmap.createBitmap(ExpeditionArt.WIDTH, ExpeditionArt.HEIGHT, Bitmap.Config.ARGB_8888)
                    try {
                        ExpeditionRenderer.draw(Canvas(bitmap), ExpeditionArt.scene(route, title, date), bitmap.width.toFloat(), bitmap.height.toFloat())
                        temp.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    } finally { bitmap.recycle() }
                }
            }
            coroutineContext.ensureActive()
            val out = app.contentResolver.openOutputStream(uri, "wt") ?: error("Destino indisponível.")
            out.use { target -> temp.inputStream().use { it.copyTo(target) } }
        } finally { temp.delete() }
    }
}
