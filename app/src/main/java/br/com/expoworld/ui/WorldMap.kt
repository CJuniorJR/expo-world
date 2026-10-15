package br.com.expoworld.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as PixelColor
import androidx.core.view.doOnLayout
import br.com.expoworld.BuildConfig
import br.com.expoworld.WorldApp
import br.com.expoworld.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.*
import org.maplibre.android.style.layers.*
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point

class WorldMap(context: Context, private val app: WorldApp, private val scope: CoroutineScope,
    private val message: (String) -> Unit, private val followingChanged: (Boolean) -> Unit = {}) {
    val view = MapView(context)
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var fogJob: Job? = null
    private var mode = "real"
    private var position: Fix? = null
    private var centered = false
    private var generation = 0
    private val density = context.resources.displayMetrics.density
    private var topInset = 0
    private var bottomInset = 0
    private var trace: RecordedTrace? = null
    private var traceJob: Job? = null
    private var focusPending: RecordedTrace? = null
    private val emptyFeatures = """{"type":"FeatureCollection","features":[]}"""
    private val fogMutex = Mutex()
    private var following = false
    private var loadFailure = false
    private var fogWarning = ""
    val configured = BuildConfig.MAP_STYLE_URL.isNotBlank() || BuildConfig.MAPTILER_KEY.isNotBlank()
    private val fallback = """{"version":8,"sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"#bbc7c3"}}]}"""

    fun initialize() {
        view.addOnDidFailLoadingMapListener { loadFailure = true; publishMessage() }
        view.getMapAsync { loaded ->
            map = loaded
            applyInsets()
            loaded.setMinZoomPreference(8.0)
            loaded.setMaxZoomPreference(20.0)
            loaded.cameraPosition = CameraPosition.Builder().target(LatLng(-23.5615, -46.6559)).zoom(15.0).build()
            loaded.addOnCameraIdleListener { refreshFog() }
            loaded.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) setFollowing(false)
            }
            reload()
        }
    }
    private fun publishMessage() {
        message(when {
            !configured -> "Configure MAPTILER_KEY em local.properties para carregar ruas. O modo simulado pode testar a revelação sem tiles."
            loadFailure -> "Mapa indisponível. Confira internet, chave e cota do provedor. O registro local continua."
            else -> fogWarning
        })
    }
    fun retryIfNeeded() { if (loadFailure) reload() }
    fun setFollowing(value: Boolean) {
        if (following == value) return
        following = value
        followingChanged(value)
        if (value) { centered = position != null; center() } else centered = true
    }
    fun setInsets(top: Int, bottom: Int) {
        topInset = top
        bottomInset = bottom
        applyInsets()
    }
    private fun applyInsets() {
        fun px(dp: Int) = (dp * density).toInt()
        map?.uiSettings?.apply {
            setLogoMargins(px(16), 0, 0, bottomInset + px(158))
            setAttributionMargins(px(112), 0, 0, bottomInset + px(158))
            setCompassMargins(0, topInset + px(88), px(20), 0)
        }
    }
    fun reload() {
        val m = map ?: return
        fogJob?.cancel()
        traceJob?.cancel()
        generation++
        style = null
        fogWarning = ""
        val builder = if (!configured) Style.Builder().fromJson(fallback) else {
            val uri = BuildConfig.MAP_STYLE_URL.ifBlank { "https://api.maptiler.com/maps/streets-v4/style.json?key=${BuildConfig.MAPTILER_KEY}" }
            Style.Builder().fromUri(uri)
        }
        m.setStyle(builder) { s ->
            style = s
            s.addSource(GeoJsonSource("fog", FogGeometry.geoJson(emptyList())))
            s.addLayer(FillLayer("fog-fill", "fog").withProperties(fillColor("#101820"), fillOpacity(.55f), fillAntialias(true)))
            s.addSource(GeoJsonSource("speed-route", emptyFeatures))
            s.addLayer(LineLayer("speed-route-outline", "speed-route").withProperties(lineColor("#102027"), lineWidth(10f),
                lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)))
            s.addLayer(LineLayer("speed-route-color", "speed-route").withProperties(lineColor(Expression.get("color")), lineWidth(6f),
                lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)))
            s.addImage("palio-green", palioPixelArt())
            s.addSource(GeoJsonSource("position"))
            s.addLayer(SymbolLayer("position-palio", "position").withProperties(
                iconImage("palio-green"), iconSize(1.7f), iconAnchor(Property.ICON_ANCHOR_CENTER),
                iconRotate(Expression.get("bearing")), iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP), iconAllowOverlap(true), iconIgnorePlacement(true)
            ))
            updatePosition(position)
            loadFailure = false
            publishMessage()
            refreshFog()
            renderTrace()
            focusPending?.let { focusTrace(it) }
        }
    }
    fun setMode(value: String) {
        if (value != mode) {
            mode = value; position = null; centered = false
            style?.getSourceAs<GeoJsonSource>("position")?.setGeoJson("""{"type":"FeatureCollection","features":[]}""")
            style?.getSourceAs<GeoJsonSource>("fog")?.setGeoJson(FogGeometry.geoJson(emptyList()))
        }
        refreshFog()
    }
    fun updatePosition(fix: Fix?) {
        if (fix == null) { position = null; return }
        val previous = position
        val bearing = fix.bearingDegrees?.takeIf { it.isFinite() } ?: previous
            ?.takeIf { Geo.distance(it, fix) > 1.5 && fix.monotonicSeconds > it.monotonicSeconds }
            ?.let { Geo.bearing(it, fix) } ?: previous?.bearingDegrees ?: 0.0
        val orientedFix = fix.copy(bearingDegrees = bearing)
        position = orientedFix
        val feature = Feature.fromGeometry(Point.fromLngLat(orientedFix.longitude, orientedFix.latitude)).apply {
            addNumberProperty("bearing", bearing)
        }
        style?.getSourceAs<GeoJsonSource>("position")?.setGeoJson(feature)
        if (!centered && map != null) { center(); centered = true }
        else if (following) map?.easeCamera(CameraUpdateFactory.newLatLng(LatLng(orientedFix.latitude, orientedFix.longitude)), if (mode == "simulation") 100 else 700)
    }
    fun center() { position?.let { map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(it.latitude, it.longitude), 16.0)) } }
    fun setTrace(value: RecordedTrace?) {
        trace = value
        if (value == null) focusPending = null
        renderTrace()
    }
    private fun renderTrace() {
        traceJob?.cancel()
        val s = style ?: return
        val data = trace
        if (data == null) {
            s.getSourceAs<GeoJsonSource>("speed-route")?.setGeoJson(emptyFeatures)
            return
        }
        traceJob = scope.launch {
            val json = withContext(Dispatchers.Default) {
                TracePresentation.lineGeoJson(data.samples)
            }
            ensureActive()
            if (style === s && trace === data) {
                s.getSourceAs<GeoJsonSource>("speed-route")?.setGeoJson(json)
            }
        }
    }
    fun focusTrace(data: RecordedTrace) {
        focusPending = data
        val m = map ?: return
        if (style == null || data.samples.isEmpty()) return
        if (view.width <= 0 || view.height <= 0) {
            view.doOnLayout { if (trace === data) focusTrace(data) }
            return
        }
        val points = data.samples
        val west = points.minOf { it.longitude }; val east = points.maxOf { it.longitude }
        if (points.size == 1 || east - west > 180 || (west == east && points.minOf { it.latitude } == points.maxOf { it.latitude })) {
            val last = points.last()
            m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(last.latitude, last.longitude), 16.0))
        } else {
            val bounds = LatLngBounds.Builder().includes(points.map { LatLng(it.latitude, it.longitude) }).build()
            val top = topInset + density * 112
            val bottom = bottomInset + density * 200
            // Keep a positive camera viewport on short/landscape screens.
            val factor = (view.height * .6f / (top + bottom)).coerceAtMost(1f)
            val side = (density * 32).toInt().coerceAtMost(view.width / 5)
            m.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, side, (top * factor).toInt(),
                side, (bottom * factor).toInt()))
        }
        centered = true
        focusPending = null
    }
    fun refreshFog() {
        val m = map ?: return
        val s = style ?: return
        val bounds = m.projection.visibleRegion.latLngBounds
        val selectedMode = mode
        val request = ++generation
        fogJob?.cancel()
        fogJob = scope.launch {
            delay(if (selectedMode == "simulation") 50 else 250) // Keep up with accelerated playback.
            try {
                val cells = withContext(Dispatchers.IO) {
                    // The SDK can return unwrapped longitudes such as 179..181.
                    Geo.viewportBounds(bounds.latitudeSouth, bounds.longitudeWest, bounds.latitudeNorth, bounds.longitudeEast)
                        .flatMap { app.repository.visible(selectedMode, it) }
                }
                val json = withContext(Dispatchers.Default) {
                    fogMutex.withLock { ensureActive(); FogGeometry.geoJson(cells.distinct()) }
                }
                ensureActive()
                if (request == generation && style === s) {
                    s.getSourceAs<GeoJsonSource>("fog")?.setGeoJson(json)
                    if (fogWarning.isNotEmpty()) { fogWarning = ""; publishMessage() }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                // Conservative fallback: never reveal an unexplored place on failure.
                s.getSourceAs<GeoJsonSource>("fog")?.setGeoJson(FogGeometry.geoJson(emptyList()))
                fogWarning = "Aproxime o mapa para visualizar a exploração. Se persistir, confira o armazenamento."
                publishMessage()
            }
        }
    }
    fun dispose() { fogJob?.cancel(); traceJob?.cancel() }

    /** Tiny overhead pixel drawing of a green Brazilian compact sedan, facing north. */
    private fun palioPixelArt(): Bitmap {
        val rows = listOf(
            "........DDDD........",
            "......DDDDDDDD......",
            ".....DDGGGGGGDD.....",
            "....DGGGYYYYGGGD....",
            "...DGGGGGGGGGGGGD...",
            "..DDGGGGGGGGGGGGDD..",
            ".DDGGGGGGGGGGGGGGDD.",
            "DDGGGGGGGGGGGGGGGGDD",
            "DDTTGGGGGGGGGGGGTTDD",
            "..TTDGGGGGGGGGGDTT..",
            "....DGGGGGGGGGGD....",
            "....DWWWWWWWWWWD....",
            "....DWWWWKKWWWWD....",
            "....DWWWWKKWWWWD....",
            "....DWWWWWWWWWWD....",
            "....DGGGGGGGGGGD....",
            ".....GGGGgGGGG.....",
            ".DD.DGGGGGGGGGGD.DD.",
            "DDGGDGGGGGGGGGGDGGDD",
            "DDGGDGGGGGGGGGGDGGDD",
            ".DD.DGGGGGGGGGGD.DD.",
            "....DGGGGGGGGGGD....",
            "....DGGGGGGGGGGD....",
            "....DTTTTTTTTTTD....",
            "....DTTTTTTTTTTD....",
            "...DDTTTTTTTTTTDD...",
            "..DDDDDDSSSSDDDDDD..",
            "..DDD......DDD..."
        )
        val width = rows.maxOf { it.length }
        val bitmap = Bitmap.createBitmap(width, rows.size, Bitmap.Config.ARGB_8888)
        val colors = mapOf(
            'D' to PixelColor.rgb(29, 39, 38), 'G' to PixelColor.rgb(0, 116, 105),
            'g' to PixelColor.rgb(18, 143, 126), 'Y' to PixelColor.rgb(246, 177, 48),
            'W' to PixelColor.rgb(122, 192, 199), 'K' to PixelColor.rgb(40, 81, 84),
            'T' to PixelColor.rgb(19, 28, 28), 'S' to PixelColor.rgb(161, 180, 172)
        )
        rows.forEachIndexed { y, row ->
            val left = (width - row.length) / 2
            row.forEachIndexed { x, pixel -> bitmap.setPixel(left + x, y, colors[pixel] ?: PixelColor.TRANSPARENT) }
        }
        return bitmap
    }
}
