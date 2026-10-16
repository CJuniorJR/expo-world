package br.com.expoworld.ui

import android.content.Context
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
    private var accuracyFix: Fix? = null
    private var centered = false
    private var generation = 0
    private val density = context.resources.displayMetrics.density
    private var topInset = 0
    private var bottomInset = 0
    private var trace: RecordedTrace? = null
    private var traceJob: Job? = null
    private var liveHead: TraceSample? = null
    private var renderedTail: List<Any?>? = null
    private var pendingTail: List<Any?>? = null
    private var renderedHistory: List<Any?>? = null
    private var focusPending: RecordedTrace? = null
    private val emptyFeatures = """{"type":"FeatureCollection","features":[]}"""
    private val fogMutex = Mutex()
    private var following = false
    private var loadFailure = false
    private var fogWarning = ""
    private var fogOpacity = context.getSharedPreferences("map", Context.MODE_PRIVATE).getFloat("fogOpacity", .55f).coerceIn(.2f, .8f)
    private var showDiscoveries = false
    val configured = BuildConfig.MAP_STYLE_URL.isNotBlank() || BuildConfig.MAPTILER_KEY.isNotBlank()
    private val fallback = """{"version":8,"sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"#bbc7c3"}}]}"""

    fun initialize() {
        view.addOnDidFailLoadingMapListener { loadFailure = true; publishMessage() }
        view.getMapAsync { loaded ->
            map = loaded
            applyInsets()
            loaded.setMinZoomPreference(2.0)
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
            renderedTail = null; renderedHistory = null; pendingTail = null
            s.addSource(GeoJsonSource("fog", FogGeometry.geoJson(emptyList())))
            s.addLayer(FillLayer("fog-fill", "fog").withProperties(fillColor("#101820"), fillOpacity(fogOpacity), fillAntialias(true)))
            s.addSource(GeoJsonSource("coverage", emptyFeatures))
            s.addLayer(FillLayer("coverage-fill", "coverage").withProperties(fillColor("#70E1BA"), fillOpacity(.3f)))
            s.addSource(GeoJsonSource("discoveries", emptyFeatures))
            s.addLayer(FillLayer("discoveries-fill", "discoveries").withProperties(fillColor("#FFD166"), fillOpacity(.45f)))
            s.addSource(GeoJsonSource("speed-route", emptyFeatures))
            s.addSource(GeoJsonSource("speed-stops", emptyFeatures))
            s.addLayer(LineLayer("speed-route-outline", "speed-route").withProperties(lineColor("#102027"), lineWidth(10f),
                lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)))
            s.addLayer(LineLayer("speed-route-color", "speed-route").withProperties(lineColor(Expression.get("color")), lineWidth(6f),
                lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)))
            listOf("speed-recent", "speed-live").forEach { id ->
                s.addSource(GeoJsonSource(id, emptyFeatures))
                s.addLayer(LineLayer("$id-outline", id).withProperties(lineColor("#102027"), lineWidth(10f),
                    lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)))
                s.addLayer(LineLayer("$id-color", id).withProperties(lineColor(Expression.get("color")), lineWidth(6f),
                    lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)))
            }
            s.addLayer(CircleLayer("speed-stop-dots", "speed-stops").withProperties(circleColor("#21404A"), circleRadius(9f),
                circleStrokeColor("#C9FFF1"), circleStrokeWidth(2.5f)))
            s.addSource(GeoJsonSource("position"))
            s.addSource(GeoJsonSource("accuracy", AccuracyGeometry.geoJson(accuracyFix)))
            s.addLayer(FillLayer("accuracy-fill", "accuracy").withProperties(fillColor("#69ABFF"), fillOpacity(.16f)))
            s.addLayer(LineLayer("accuracy-border", "accuracy").withProperties(lineColor("#69ABFF"), lineWidth(1.5f)))
            s.addLayer(CircleLayer("position-dot", "position").withProperties(circleColor("#007DFF"), circleRadius(7f), circleStrokeColor("#FFFFFF"), circleStrokeWidth(3f)))
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
            style?.getSourceAs<GeoJsonSource>("coverage")?.setGeoJson(emptyFeatures)
            style?.getSourceAs<GeoJsonSource>("discoveries")?.setGeoJson(emptyFeatures)
        }
        refreshFog()
    }
    fun updatePosition(fix: Fix?) {
        position = fix
        if (fix == null) return
        style?.getSourceAs<GeoJsonSource>("position")?.setGeoJson(Feature.fromGeometry(Point.fromLngLat(fix.longitude, fix.latitude)))
        if (!centered && map != null) { center(); centered = true }
        else if (following) map?.easeCamera(CameraUpdateFactory.newLatLng(LatLng(fix.latitude, fix.longitude)), 700)
    }
    fun updateAccuracy(fix: Fix?) {
        accuracyFix = fix
        style?.getSourceAs<GeoJsonSource>("accuracy")?.setGeoJson(AccuracyGeometry.geoJson(fix))
    }
    fun center() { position?.let { map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(it.latitude, it.longitude), 16.0)) } }
    fun setFogOpacity(value: Float) {
        fogOpacity = value.coerceIn(.2f, .8f)
        style?.getLayerAs<FillLayer>("fog-fill")?.setProperties(fillOpacity(fogOpacity))
    }
    fun setDiscoveries(value: Boolean) { showDiscoveries = value; refreshFog() }
    fun overview() {
        setFollowing(false)
        val selectedMode = mode
        scope.launch {
            try {
                val extent = withContext(Dispatchers.IO) { app.repository.extent(selectedMode) }
                if (mode != selectedMode) return@launch
                if (extent == null) { message("Ainda não há descobertas neste modo."); return@launch }
                val size = ExplorationConfig.CELL_PROJECTED_METERS
                val bounds = LatLngBounds.Builder().includes(listOf(
                    LatLng(Geo.latitude(extent.minY * size), Geo.longitude(extent.minX * size)),
                    LatLng(Geo.latitude((extent.maxY + 1.0) * size), Geo.longitude((extent.maxX + 1.0) * size)))).build()
                if (view.width > 0 && view.height > 0) {
                    val top = topInset + density * 112
                    val bottom = bottomInset + density * 200
                    val factor = (view.height * .6f / (top + bottom)).coerceAtMost(1f)
                    val side = (density * 32).toInt().coerceAtMost(view.width / 5)
                    map?.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, side, (top * factor).toInt(), side, (bottom * factor).toInt()))
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message("Não foi possível abrir a visão geral. Tente novamente.") }
        }
    }
    fun setTrace(value: RecordedTrace?, head: TraceSample? = null) {
        trace = value
        liveHead = head
        if (value == null) focusPending = null
        renderTrace()
    }
    private fun renderTrace() {
        val s = style ?: return
        val data = trace
        if (data == null) {
            traceJob?.cancel()
            renderedTail = null; renderedHistory = null; pendingTail = null
            listOf("speed-route", "speed-recent", "speed-live", "speed-stops").forEach {
                s.getSourceAs<GeoJsonSource>(it)?.setGeoJson(emptyFeatures)
            }
            return
        }
        s.getSourceAs<GeoJsonSource>("speed-live")?.setGeoJson(
            TracePresentation.lineGeoJson(TraceChunks.live(data.samples, liveHead)))
        val key = listOf(data.mode, data.startedAtMillis, data.samples.firstOrNull()?.sequence, data.samples.size, data.samples.lastOrNull())
        if (key == renderedTail || key == pendingTail) return
        traceJob?.cancel()
        pendingTail = key
        val window = TraceChunks.split(data.samples)
        val historyKey = listOf(data.mode, data.startedAtMillis, window.history.firstOrNull()?.sequence, window.history.lastOrNull()?.sequence)
        val historyChanged = historyKey != renderedHistory
        traceJob = scope.launch {
            val json = withContext(Dispatchers.Default) {
                Triple(if (historyChanged) TracePresentation.lineGeoJson(window.history) else null,
                    TracePresentation.lineGeoJson(window.recent), TracePresentation.stopGeoJson(data.samples))
            }
            ensureActive()
            if (style === s) {
                json.first?.let { s.getSourceAs<GeoJsonSource>("speed-route")?.setGeoJson(it) }
                s.getSourceAs<GeoJsonSource>("speed-recent")?.setGeoJson(json.second)
                s.getSourceAs<GeoJsonSource>("speed-stops")?.setGeoJson(json.third)
                renderedTail = key; renderedHistory = historyKey; pendingTail = null
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
            delay(250) // Coalesce location and camera changes; cancellation rejects stale results.
            val viewBounds = Geo.viewportBounds(bounds.latitudeSouth, bounds.longitudeWest, bounds.latitudeNorth, bounds.longitudeEast)
            suspend fun showCoverage() {
                val coverage = withContext(Dispatchers.IO) { viewBounds.flatMap { app.repository.coverage(selectedMode, it) } }
                val json = withContext(Dispatchers.Default) { CoverageGeometry.geoJson(coverage) }
                ensureActive()
                if (request == generation && style === s) {
                    s.getSourceAs<GeoJsonSource>("fog")?.setGeoJson(FogGeometry.geoJson(emptyList()))
                    s.getSourceAs<GeoJsonSource>("coverage")?.setGeoJson(json)
                    s.getSourceAs<GeoJsonSource>("discoveries")?.setGeoJson(emptyFeatures)
                    fogWarning = "Visão geral • cobertura aproximada em verde. Aproxime para ver os limites reais."
                    publishMessage()
                }
            }
            try {
                if (m.cameraPosition.zoom < 12) {
                    showCoverage()
                    return@launch
                }
                val cells = withContext(Dispatchers.IO) {
                    // The SDK can return unwrapped longitudes such as 179..181.
                    viewBounds.flatMap { app.repository.visible(selectedMode, it) }
                }
                val discoveries = if (showDiscoveries) withContext(Dispatchers.IO) {
                    viewBounds.flatMap { app.repository.discoveries(selectedMode, it) }
                } else emptyList()
                val json = withContext(Dispatchers.Default) {
                    fogMutex.withLock { ensureActive(); FogGeometry.geoJson(cells.distinct()) }
                }
                val newJson = withContext(Dispatchers.Default) { CoverageGeometry.geoJson(CoverageGeometry.rows(discoveries)) }
                ensureActive()
                if (request == generation && style === s) {
                    s.getSourceAs<GeoJsonSource>("fog")?.setGeoJson(json)
                    s.getSourceAs<GeoJsonSource>("coverage")?.setGeoJson(emptyFeatures)
                    s.getSourceAs<GeoJsonSource>("discoveries")?.setGeoJson(newJson)
                    if (fogWarning.isNotEmpty()) { fogWarning = ""; publishMessage() }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (e is IllegalStateException && e.message?.startsWith("Aproxime") == true) {
                    try { showCoverage(); return@launch }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Continue with conservative fog on storage failure. */ }
                }
                // Conservative fallback: never reveal an unexplored place on failure.
                s.getSourceAs<GeoJsonSource>("fog")?.setGeoJson(FogGeometry.geoJson(emptyList()))
                s.getSourceAs<GeoJsonSource>("coverage")?.setGeoJson(emptyFeatures)
                s.getSourceAs<GeoJsonSource>("discoveries")?.setGeoJson(emptyFeatures)
                fogWarning = "Aproxime o mapa para visualizar a exploração. Se persistir, confira o armazenamento."
                publishMessage()
            }
        }
    }
    fun dispose() { fogJob?.cancel(); traceJob?.cancel() }
}
