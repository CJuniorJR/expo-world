package br.com.expoworld.storage

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import br.com.expoworld.BuildConfig
import br.com.expoworld.core.*
import kotlinx.coroutines.*
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.snapshotter.MapSnapshotter
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.*

class MapExportUnavailable : java.io.IOException("Não foi possível carregar o mapa para exportar. Confira internet e configuração do mapa e tente novamente.")
data class ExpeditionMapImage(val bitmap: Bitmap, val point: (TraceSample) -> XY)

object ExpeditionMapLoader {
    suspend fun load(context: Context, route: RecordedTrace): ExpeditionMapImage {
        val url = BuildConfig.MAP_STYLE_URL.ifBlank {
            if (BuildConfig.MAPTILER_KEY.isBlank()) throw MapExportUnavailable()
            "https://api.maptiler.com/maps/streets-v4/style.json?key=${BuildConfig.MAPTILER_KEY}"
        }
        val samples = route.samples.filter { it.accepted }
        val positions = samples.map { Geo.project(it.latitude, it.longitude) }
        if (positions.isEmpty() || samples.maxOf { it.longitude } - samples.minOf { it.longitude } > 180) throw MapExportUnavailable()
        var minX = positions.minOf { it.x }; var maxX = positions.maxOf { it.x }
        var minY = positions.minOf { it.y }; var maxY = positions.maxOf { it.y }
        val centerX = (minX + maxX) / 2; val centerY = (minY + maxY) / 2
        val scale = min(800 / (maxX - minX).coerceAtLeast(100.0), 540 / (maxY - minY).coerceAtLeast(100.0))
        minX = centerX - 460 / scale; maxX = centerX + 460 / scale
        minY = centerY - 350 / scale; maxY = centerY + 350 / scale
        val sw = LatLng(Geo.latitude(minY), Geo.longitude(minX))
        val ne = LatLng(Geo.latitude(maxY), Geo.longitude(maxX))
        try {
            return withTimeout(45_000) { withContext(Dispatchers.Main.immediate) {
                suspendCancellableCoroutine { continuation ->
                    val options = MapSnapshotter.Options(920, 700).withPixelRatio(1f).withStyle(url)
                        .withRegion(LatLngBounds.Builder().includes(listOf(sw, ne)).build())
                        .withPadding(0, 0, 0, 0).withLogo(true).withAttribution(true)
                    val snapshotter = MapSnapshotter(context.applicationContext, options)
                    continuation.invokeOnCancellation { Handler(Looper.getMainLooper()).post { snapshotter.cancel() } }
                    snapshotter.start({ snapshot ->
                        if (continuation.isActive) {
                            val a = snapshot.pixelForLatLng(sw); val b = snapshot.pixelForLatLng(ne)
                            val project: (TraceSample) -> XY = { sample ->
                                val p = Geo.project(sample.latitude, sample.longitude)
                                XY(80 + a.x + (p.x - minX) / (maxX - minX) * (b.x - a.x),
                                    330 + a.y + (p.y - minY) / (maxY - minY) * (b.y - a.y))
                            }
                            continuation.resume(ExpeditionMapImage(snapshot.bitmap, project))
                        } else snapshot.bitmap.recycle()
                    }, { if (continuation.isActive) continuation.resumeWithException(MapExportUnavailable()) })
                }
            } }
        } catch (_: TimeoutCancellationException) { throw MapExportUnavailable() }
    }
}
