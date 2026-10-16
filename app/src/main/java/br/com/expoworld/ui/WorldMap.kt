package br.com.expoworld.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as PixelColor
import android.graphics.PointF
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
    private var selectedNeighborhoodId = ""
    private var theme = app.theme.value
    private var activity = ActivityKind.CAR
    private fun activityIcon() = when (activity) { ActivityKind.CAR -> "palio-green"; ActivityKind.WALK -> "walker-green"; ActivityKind.BIKE -> "bike-green" }
    private var fogOpacity = context.getSharedPreferences("map", Context.MODE_PRIVATE).getFloat("fogOpacity", .55f).coerceIn(.2f, .8f)
    private var showDiscoveries = false
    private var presentation = MapPresentation.NORMAL
    private var comparison = .5f
    private var replayFraction = 0f
    private var scene: ExplorationScene? = null
    private var visualJob: Job? = null
    private var visualRequest = 0
    private var visualRunning = false
    private var visualRunToken = 0
    private var replaySamples: List<TraceSample> = emptyList()
    private var replayRouteKey: List<Any?>? = null
    val configured = BuildConfig.MAP_STYLE_URL.isNotBlank() || BuildConfig.MAPTILER_KEY.isNotBlank()
    private val fallback = """{"version":8,"sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"#bbc7c3"}}]}"""

    fun initialize() {
        view.addOnDidFailLoadingMapListener { loadFailure = true; publishMessage() }
        view.getMapAsync { loaded ->
            map = loaded
            applyInsets()
            loaded.setMinZoomPreference(2.0)
            loaded.setMaxZoomPreference(20.0)
            loaded.cameraPosition = CameraPosition.Builder().target(LatLng(-25.429, -49.273)).zoom(13.0).build()
            loaded.addOnCameraIdleListener { refreshFog() }
            loaded.addOnCameraMoveListener { if (presentation == MapPresentation.COMPARE) renderVisual() }
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
        visualRunToken++; visualJob?.cancel(); visualRunning = false; scene = null
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
            s.addSource(GeoJsonSource("neighborhoods", emptyFeatures))
            s.addLayer(LineLayer("neighborhood-outlines", "neighborhoods").withProperties(lineColor(ExplorerTheme.hex(theme.accent)), lineWidth(1f), lineOpacity(.65f)))
            s.addLayer(FillLayer("neighborhood-selected", "neighborhoods")
                .withFilter(Expression.eq(Expression.get("id"), Expression.literal(selectedNeighborhoodId)))
                .withProperties(fillColor(ExplorerTheme.hex(theme.accent)), fillOpacity(.15f)))
            scope.launch {
                try {
                    val boundaries = withContext(Dispatchers.IO) { app.assets.open("curitiba-neighborhoods.geojson").bufferedReader().use { it.readText() } }
                    if (style === s) s.getSourceAs<GeoJsonSource>("neighborhoods")?.setGeoJson(boundaries)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* The repository reports catalog availability in the menu. */ }
            }
            s.addSource(GeoJsonSource("coverage", emptyFeatures))
            s.addLayer(FillLayer("coverage-fill", "coverage").withProperties(fillColor("#70E1BA"), fillOpacity(.3f)))
            s.addSource(GeoJsonSource("discoveries", emptyFeatures))
            s.addLayer(FillLayer("discoveries-fill", "discoveries").withProperties(fillColor("#FFD166"), fillOpacity(.45f)))
            s.addSource(GeoJsonSource("comparison", emptyFeatures))
            s.addLayer(FillLayer("comparison-fill", "comparison").withProperties(fillColor("#101820"), fillOpacity(fogOpacity)))
            s.addSource(GeoJsonSource("epochs", emptyFeatures))
            s.addLayer(FillLayer("epochs-fill", "epochs").withProperties(fillColor(Expression.get("color")), fillOpacity(.45f)))
            s.addSource(GeoJsonSource("speed-route", emptyFeatures))
            s.addLayer(LineLayer("speed-route-outline", "speed-route").withProperties(lineColor(if (theme.light) "#354555" else "#102027"), lineWidth(10f),
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
            s.addSource(GeoJsonSource("accuracy", AccuracyGeometry.geoJson(accuracyFix)))
            s.addLayer(FillLayer("accuracy-fill", "accuracy").withProperties(fillColor("#69ABFF"), fillOpacity(.16f)))
            s.addLayer(LineLayer("accuracy-border", "accuracy").withProperties(lineColor("#69ABFF"), lineWidth(1.5f)))
            s.addSource(GeoJsonSource("replay-head", emptyFeatures))
            s.addLayer(CircleLayer("replay-dot", "replay-head").withProperties(circleColor("#FFD166"), circleRadius(8f), circleStrokeColor("#FFFFFF"), circleStrokeWidth(2f)))
            s.addImage("palio-green", palioPixelArt())
            s.addImage("walker-green", activityPixelArt(ActivityKind.WALK))
            s.addImage("bike-green", activityPixelArt(ActivityKind.BIKE))
            s.addSource(GeoJsonSource("position"))
            s.addLayer(SymbolLayer("position-palio", "position").withProperties(
                iconImage(activityIcon()), iconSize(1.7f), iconAnchor(Property.ICON_ANCHOR_CENTER),
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
            visualRunToken++; visualJob?.cancel(); visualRunning = false; scene = null
            mode = value; position = null; centered = false
            style?.getSourceAs<GeoJsonSource>("position")?.setGeoJson("""{"type":"FeatureCollection","features":[]}""")
            style?.getSourceAs<GeoJsonSource>("fog")?.setGeoJson(FogGeometry.geoJson(emptyList()))
            style?.getSourceAs<GeoJsonSource>("coverage")?.setGeoJson(emptyFeatures)
            style?.getSourceAs<GeoJsonSource>("discoveries")?.setGeoJson(emptyFeatures)
            style?.getSourceAs<GeoJsonSource>("comparison")?.setGeoJson(emptyFeatures)
            style?.getSourceAs<GeoJsonSource>("epochs")?.setGeoJson(emptyFeatures)
        }
        refreshFog()
    }
    fun updatePosition(fix: Fix?) {
        if (presentation == MapPresentation.REPLAY) { position = fix; return }
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
    fun updateAccuracy(fix: Fix?) {
        accuracyFix = fix
        if (presentation == MapPresentation.REPLAY) return
        style?.getSourceAs<GeoJsonSource>("accuracy")?.setGeoJson(AccuracyGeometry.geoJson(fix))
    }
    fun center() { position?.let { map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(it.latitude, it.longitude), 16.0)) } }
    fun setTheme(value: ExplorerTheme) {
        theme = value
        style?.let { s ->
            s.addImage("palio-green", palioPixelArt())
            s.addImage("walker-green", activityPixelArt(ActivityKind.WALK))
            s.addImage("bike-green", activityPixelArt(ActivityKind.BIKE))
            s.getLayerAs<LineLayer>("neighborhood-outlines")?.setProperties(lineColor(ExplorerTheme.hex(value.accent)))
            s.getLayerAs<FillLayer>("neighborhood-selected")?.setProperties(fillColor(ExplorerTheme.hex(value.accent)))
            s.getLayerAs<LineLayer>("speed-route-outline")?.setProperties(lineColor(if (value.light) "#354555" else "#102027"))
        }
        renderTrace()
    }
    fun setActivity(value: ActivityKind) {
        activity = value
        style?.getLayerAs<SymbolLayer>("position-palio")?.setProperties(iconImage(activityIcon()))
    }
    fun focusNeighborhood(id: String) {
        scope.launch {
            try {
                val area = app.city.bounds(id) ?: return@launch
                selectedNeighborhoodId = id
                style?.getLayerAs<FillLayer>("neighborhood-selected")?.setFilter(
                    Expression.eq(Expression.get("id"), Expression.literal(id)))
                setFollowing(false)
                val bounds = LatLngBounds.Builder().includes(listOf(LatLng(area[0], area[1]), LatLng(area[2], area[3]))).build()
                map?.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, (density * 40).toInt()))
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { message("Não foi possível enquadrar o bairro. Confira a base de Curitiba no menu.") }
        }
    }
    fun setFogOpacity(value: Float) {
        fogOpacity = value.coerceIn(.2f, .8f)
        style?.getLayerAs<FillLayer>("fog-fill")?.setProperties(fillOpacity(fogOpacity))
        style?.getLayerAs<FillLayer>("comparison-fill")?.setProperties(fillOpacity(fogOpacity))
    }
    fun setPresentation(value: MapPresentation, fraction: Float) {
        val changed = presentation != value
        presentation = value
        comparison = fraction.coerceIn(0f, 1f)
        replayFraction = fraction.coerceIn(0f, 1f)
        if (changed) {
            visualRunToken++
            visualJob?.cancel(); visualJob = null; visualRunning = false; scene = null
            listOf("comparison", "epochs", "replay-head").forEach { style?.getSourceAs<GeoJsonSource>(it)?.setGeoJson(emptyFeatures) }
            if (value != MapPresentation.NORMAL) setFollowing(false)
            if (value == MapPresentation.REPLAY) {
                style?.getSourceAs<GeoJsonSource>("position")?.setGeoJson(emptyFeatures)
                style?.getSourceAs<GeoJsonSource>("accuracy")?.setGeoJson(emptyFeatures)
                trace?.let { focusTrace(it) }
            } else { updatePosition(position); updateAccuracy(accuracyFix) }
            renderedTail = null; renderedHistory = null; pendingTail = null
            refreshFog()
        }
        if (value == MapPresentation.REPLAY) renderTrace()
        if (value == MapPresentation.COMPARE || value == MapPresentation.REPLAY) renderVisual()
        if (changed && value != MapPresentation.REPLAY) renderTrace()
    }
    private fun renderVisual() {
        visualRequest++
        if (visualRunning) return
        val snapshot = scene ?: return
        val s = style ?: return
        val selected = presentation
        if (selected != MapPresentation.COMPARE && selected != MapPresentation.REPLAY) return
        val request = visualRequest
        val fraction = replayFraction
        val clip = if (selected == MapPresentation.COMPARE && view.width > 0 && view.height > 0) {
            val x = view.width * comparison
            listOf(PointF(0f, 0f), PointF(x.coerceAtLeast(.01f), 0f), PointF(x.coerceAtLeast(.01f), view.height.toFloat()), PointF(0f, view.height.toFloat()))
                .map { point -> map!!.projection.fromScreenLocation(point).let { XY(it.longitude, it.latitude) } }
        } else emptyList()
        val elapsed = ReplayTimeline.elapsed(replaySamples, fraction)
        visualRunning = true
        val runToken = ++visualRunToken
        visualJob = scope.launch {
            try {
                val json = withContext(Dispatchers.Default) {
                    fogMutex.withLock { ensureActive()
                        if (selected == MapPresentation.COMPARE) snapshot.comparisonMask(clip) else snapshot.replayFog(elapsed)
                    }
                }
                if (scene === snapshot && style === s && presentation == selected) {
                    s.getSourceAs<GeoJsonSource>(if (selected == MapPresentation.COMPARE) "comparison" else "fog")?.setGeoJson(json)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { message("Aproxime o mapa para comparar ou animar a névoa.") }
            finally { if (runToken == visualRunToken) { visualRunning = false; if (request != visualRequest && scene === snapshot) renderVisual() } }
        }
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
        val original = trace
        val routeKey = listOf(original?.mode, original?.startedAtMillis, original?.samples?.size, original?.samples?.firstOrNull(), original?.samples?.lastOrNull())
        if (routeKey != replayRouteKey) { replayRouteKey = routeKey; replaySamples = ReplayTimeline.visualSamples(original?.samples.orEmpty()) }
        val data = if (presentation == MapPresentation.REPLAY && original != null)
            original.copy(samples = ReplayTimeline.frame(replaySamples, replayFraction)) else original
        s.getSourceAs<GeoJsonSource>("replay-head")?.setGeoJson(
            if (presentation == MapPresentation.REPLAY) data?.samples?.lastOrNull()?.let { Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)).toJson() } ?: emptyFeatures else emptyFeatures)
        if (data == null) {
            traceJob?.cancel()
            renderedTail = null; renderedHistory = null; pendingTail = null
            listOf("speed-route", "speed-recent", "speed-live").forEach {
                s.getSourceAs<GeoJsonSource>(it)?.setGeoJson(emptyFeatures)
            }
            return
        }
        s.getSourceAs<GeoJsonSource>("speed-live")?.setGeoJson(
            TracePresentation.lineGeoJson(TraceChunks.live(data.samples, liveHead), data.activity, theme))
        val palette = theme
        val key = listOf(theme.key, data.activity.key, data.mode, data.startedAtMillis, data.samples.firstOrNull()?.sequence, data.samples.size, data.samples.lastOrNull())
        if (key == renderedTail || key == pendingTail) return
        traceJob?.cancel()
        pendingTail = key
        val window = TraceChunks.split(data.samples)
        val historyKey = listOf(theme.key, data.activity.key, data.mode, data.startedAtMillis, window.history.firstOrNull()?.sequence, window.history.lastOrNull()?.sequence)
        val historyChanged = historyKey != renderedHistory
        traceJob = scope.launch {
            val json = withContext(Dispatchers.Default) {
                Triple(if (historyChanged) TracePresentation.lineGeoJson(window.history, data.activity, palette) else null,
                    TracePresentation.lineGeoJson(window.recent, data.activity, palette), "")
            }
            ensureActive()
            if (style === s) {
                json.first?.let { s.getSourceAs<GeoJsonSource>("speed-route")?.setGeoJson(it) }
                s.getSourceAs<GeoJsonSource>("speed-recent")?.setGeoJson(json.second)
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
            delay(if (selectedMode == "simulation") 50 else 250) // Coalesce location and camera changes; cancellation rejects stale results.
            val viewBounds = Geo.viewportBounds(bounds.latitudeSouth, bounds.longitudeWest, bounds.latitudeNorth, bounds.longitudeEast)
            suspend fun showCoverage() {
                val coverage = withContext(Dispatchers.IO) { viewBounds.flatMap { app.repository.coverage(selectedMode, it) } }
                val json = withContext(Dispatchers.Default) { CoverageGeometry.geoJson(coverage) }
                ensureActive()
                if (request == generation && style === s) {
                    scene = null
                    s.getSourceAs<GeoJsonSource>("fog")?.setGeoJson(FogGeometry.geoJson(emptyList()))
                    s.getSourceAs<GeoJsonSource>("coverage")?.setGeoJson(json)
                    s.getSourceAs<GeoJsonSource>("discoveries")?.setGeoJson(emptyFeatures)
                    s.getSourceAs<GeoJsonSource>("epochs")?.setGeoJson(emptyFeatures)
                    s.getSourceAs<GeoJsonSource>("comparison")?.setGeoJson(emptyFeatures)
                    fogWarning = "Visão geral • cobertura aproximada em verde. Aproxime para ver os limites reais."
                    publishMessage()
                }
            }
            try {
                if (m.cameraPosition.zoom < 12) {
                    showCoverage()
                    return@launch
                }
                val facts = withContext(Dispatchers.IO) {
                    // The SDK can return unwrapped longitudes such as 179..181.
                    viewBounds.flatMap { app.repository.visualCells(selectedMode, it) }
                }
                val timeline = if (showDiscoveries || presentation != MapPresentation.NORMAL) withContext(Dispatchers.IO) {
                    viewBounds.flatMap { app.repository.timeline(trace?.mode ?: selectedMode, it) }
                } else emptyList()
                val discoveries = if (showDiscoveries && presentation == MapPresentation.NORMAL) timeline.map { it.cell } else emptyList()
                val snapshot = ExplorationScene(facts, timeline)
                val json = withContext(Dispatchers.Default) {
                    fogMutex.withLock { ensureActive()
                        if (presentation == MapPresentation.REPLAY) snapshot.replayFog(ReplayTimeline.elapsed(replaySamples, replayFraction)) else snapshot.afterJson
                    }
                }
                val newJson = withContext(Dispatchers.Default) { CoverageGeometry.geoJson(CoverageGeometry.rows(discoveries)) }
                val epochJson = if (presentation == MapPresentation.EPOCHS) withContext(Dispatchers.Default) {
                    DiscoveryHistory.geoJson(facts, timeline.map { it.cell }.toSet(), System.currentTimeMillis())
                } else emptyFeatures
                ensureActive()
                if (request == generation && style === s) {
                    scene = snapshot
                    s.getSourceAs<GeoJsonSource>("fog")?.setGeoJson(json)
                    s.getSourceAs<GeoJsonSource>("coverage")?.setGeoJson(emptyFeatures)
                    s.getSourceAs<GeoJsonSource>("discoveries")?.setGeoJson(newJson)
                    s.getSourceAs<GeoJsonSource>("epochs")?.setGeoJson(epochJson)
                    renderVisual()
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
                s.getSourceAs<GeoJsonSource>("epochs")?.setGeoJson(emptyFeatures)
                s.getSourceAs<GeoJsonSource>("comparison")?.setGeoJson(emptyFeatures)
                fogWarning = "Aproxime o mapa para visualizar a exploração. Se persistir, confira o armazenamento."
                publishMessage()
            }
        }
    }
    fun dispose() { fogJob?.cancel(); traceJob?.cancel(); visualJob?.cancel() }

    private fun activityPixelArt(kind: ActivityKind): Bitmap {
        val rows = if (kind == ActivityKind.WALK) listOf(
            "......DDDD......", ".....DSSSSD.....", ".....DSSSSD.....", "......DDDD......",
            "....DDGGGGDD....", "...DGGGGGGGGD...", "..DGGGGGGGGGGD..", "..DGGGGGGGGGGD..",
            "..DGGGGGGGGGGD..", "...DGGGGGGGGD...", "....DGGGGGGD....", "....DDDDDDDD....",
            "....DD....DD....", "....DD....DD....", "...DDD....DDD..."
        ) else listOf(
            ".......DD.......", ".......DD.......", ".......DD.......", "...SSSSSSSSSS...",
            ".......DD.......", ".......GG.......", "......GGGG......", "......GDDG......",
            "......GDDG......", "......GGGG......", ".......GG.......", "......DDDD......",
            "......DDDD......", ".......DD.......", ".......DD.......", ".......DD.......", ".......DD......."
        )
        val bitmap = Bitmap.createBitmap(16, rows.size, Bitmap.Config.ARGB_8888)
        rows.forEachIndexed { y, row -> row.forEachIndexed { x, pixel -> bitmap.setPixel(x, y, when (pixel) {
            'D' -> PixelColor.rgb(23, 35, 34); 'G' -> (0xFF000000.toInt() or if (theme == ExplorerTheme.GREEN) 0x21B38B else theme.carHighlight)
            'S' -> PixelColor.rgb(214, 217, 185); else -> PixelColor.TRANSPARENT
        }) } }
        return bitmap
    }

    /** Tiny overhead pixel drawing of a Brazilian Palio, facing north. */
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
            'D' to PixelColor.rgb(29, 39, 38), 'G' to (0xFF000000.toInt() or theme.carBody),
            'g' to (0xFF000000.toInt() or theme.carHighlight), 'Y' to PixelColor.rgb(246, 177, 48),
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
