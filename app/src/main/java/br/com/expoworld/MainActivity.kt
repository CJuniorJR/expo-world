package br.com.expoworld

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.*
import android.os.*
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.expoworld.core.Fix
import br.com.expoworld.core.ActivityKind
import br.com.expoworld.core.CityProgress
import br.com.expoworld.core.ExplorationProgress
import br.com.expoworld.core.TracePresentation
import br.com.expoworld.core.GpsSignal
import br.com.expoworld.core.GpsQuality
import br.com.expoworld.core.SimulationScenario
import br.com.expoworld.storage.BackupManager
import br.com.expoworld.storage.ExpeditionExporter
import br.com.expoworld.location.*
import br.com.expoworld.ui.WorldMap
import br.com.expoworld.ui.ExplorationScreen
import br.com.expoworld.ui.ExplorerAppearance
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import br.com.expoworld.storage.BackupPreview
import org.maplibre.android.MapLibre

class MainActivity : ComponentActivity() {
    private val app get() = application as WorldApp
    private lateinit var worldMap: WorldMap
    private lateinit var simulation: Simulation
    private lateinit var connectivity: ConnectivityObserver
    private val following = mutableStateOf(false)
    private val mapMessage = mutableStateOf("")
    private val dialog = mutableStateOf("")
    private val permissionVersion = mutableIntStateOf(0)
    private var pendingStart = false
    private var pendingPocketStart = false
    private val operations by lazy {
        androidx.lifecycle.ViewModelProvider(this,object:androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T:androidx.lifecycle.ViewModel> create(modelClass:Class<T>):T = br.com.expoworld.storage.StorageOperations(app) as T
        })[br.com.expoworld.storage.StorageOperations::class.java]
    }
    private val storageBusy get() = operations.busy
    private val storageMessage get() = operations.message
    private val exportProgress get() = operations.progress
    private val restorePreview get() = operations.preview
    private val backup get() = operations.backup
    private fun cancelStorage() = operations.cancel()
    private fun dismissRestore() = operations.dismissPreview()
    private fun exportMedia(uri: android.net.Uri, kind: String) {
        val prefs = getSharedPreferences("map", MODE_PRIVATE)
        val mode = prefs.getString("exportMode", "real") ?: "real"
        val title = prefs.getString("cardTitle", "Minha expedição") ?: "Minha expedição"
        runBackup {
            val exporter = ExpeditionExporter(app)
            if (kind == "video") exporter.exportVideo(uri, mode, title) { exportProgress.floatValue=it }
            else exporter.export(uri, mode, title, kind == "gif") { exportProgress.floatValue=it }
            when(kind) { "video" -> "Vídeo MP4 salvo."; "gif" -> "Animação GIF salva."; else -> "Imagem PNG salva." }
        }
    }
    private val exportCard = registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri -> uri?.let { exportMedia(it, "png") } }
    private val exportAnimation = registerForActivityResult(ActivityResultContracts.CreateDocument("image/gif")) { uri -> uri?.let { exportMedia(it, "gif") } }
    private val exportVideo = registerForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri -> uri?.let { exportMedia(it, "video") } }
    private val exportBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri?.let { runBackup { "Backup completo salvo: ${backup.export(it)} células, incluindo histórico, bairros, roteiros e preferências." } }
    }
    private val importBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { runBackup { restorePreview.value=backup.prepareRestore(it); "Arquivo conferido. Revise o conteúdo antes de restaurar." } }
    }
    private fun runBackup(allowPreview: Boolean = false, action: suspend () -> String) = operations.run(allowPreview,action)
    private val manager get() = getSystemService(LocationManager::class.java)
    private val preview = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (!app.tracking.value.active) app.tracking.update { it.copy(gpsFix = Fix(location.latitude, location.longitude,
                if (location.hasAccuracy()) location.accuracy.toDouble() else Double.POSITIVE_INFINITY, location.elapsedRealtimeNanos / 1e9)) }
            if (!app.tracking.value.active && location.hasAccuracy() && location.accuracy <= 50 && SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos < 10_000_000_000L) {
                app.tracking.update { it.copy(simulated = false, position = Fix(location.latitude, location.longitude, location.accuracy.toDouble(), location.elapsedRealtimeNanos / 1e9,
                    bearingDegrees = location.bearing.toDouble().takeIf { location.hasBearing() })) }
            }
        }
        override fun onProviderDisabled(provider: String) { if (!app.tracking.value.active) app.tracking.update { it.copy(message = "GPS desligado. Ative a localização do aparelho.") } }
        override fun onProviderEnabled(provider: String) = Unit
        @Deprecated("Legacy callback") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionVersion.intValue++
        if (fineGranted()) {
            startPreview()
            if (pendingStart) prepareStart()
        } else {
            pendingStart = false
            app.tracking.update { state -> state.copy(message = if (coarseGranted()) "Localização aproximada não permite uma faixa de 50 m. Ative localização precisa nas permissões." else "Localização negada. Você pode explorar o mapa; autorize nas configurações para registrar uma viagem.") }
        }
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (!it) app.tracking.update { state -> state.copy(message = "Notificações desativadas: encerre a viagem pelo app ou pelo gerenciador de apps ativos do Android.") }
        startReal()
    }
    private fun fineGranted() = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    private fun coarseGranted() = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        MapLibre.getInstance(this)
        simulation = Simulation(app)
        connectivity = ConnectivityObserver(this)
        worldMap = WorldMap(this, app, lifecycleScope, { mapMessage.value = it }, { following.value = it })
        worldMap.view.onCreate(savedInstanceState)
        worldMap.initialize()
        setContent {
            val theme by app.theme.collectAsStateWithLifecycle()
            LaunchedEffect(theme) {
                worldMap.setTheme(theme)
                enableEdgeToEdge(
                    statusBarStyle = if (theme.light) SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) else SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
                    navigationBarStyle = if (theme.light) SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) else SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
            }
            ExplorerAppearance(theme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground
                ) { Screen() }
            }
        }
    }
    private fun requestLocation(start: Boolean) { pendingStart = start; dialog.value = "location" }
    private fun prepareStart() {
        if (!fineGranted()) { requestLocation(true); return }
        if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            pendingStart = false
            app.tracking.update { it.copy(message = "Ative o GPS do aparelho e toque novamente em Iniciar exploração.") }
            dialog.value = "gps"; return
        }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) dialog.value = "notifications"
        else startReal()
    }
    private fun startReal() {
        if (app.storageBusy) return
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) { pendingStart = true; return }
        pendingStart = false
        if (app.tracking.value.active) return
        if (!fineGranted()) { requestLocation(true); return }
        runCatching { ContextCompat.startForegroundService(this, Intent(this, ExplorationService::class.java).putExtra("activity", app.tracking.value.activity.key).putExtra("pocket",pendingPocketStart)) }
            .onFailure { app.tracking.update { it.copy(message = "Não foi possível iniciar. Mantenha o app aberto e confira as permissões.") } }
    }
    private fun stopTrip() {
        if (app.tracking.value.simulated) simulation.stop()
        else startService(Intent(this, ExplorationService::class.java).setAction(ExplorationService.STOP))
    }
    private fun startPreview() {
        if (app.tracking.value.active || !fineGranted() || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        try {
            manager.removeUpdates(preview)
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 5f, preview, Looper.getMainLooper())
        } catch (e: SecurityException) {
            app.tracking.update { it.copy(message = "Permissão de localização indisponível. Autorize novamente nas configurações.") }
        } catch (e: IllegalArgumentException) {
            app.tracking.update { it.copy(message = "GPS indisponível. Confira a localização nas configurações do aparelho.") }
        }
    }
    @Composable private fun Screen() {
        val state by app.tracking.collectAsStateWithLifecycle()
        val pocket by app.pocket.state.collectAsStateWithLifecycle()
        var pocketOpen by rememberSaveable { mutableStateOf(false) }
        var pocketLibrary by rememberSaveable { mutableStateOf(false) }
        var pickingPocketStart by remember { mutableStateOf(false) }
        var chosenLat by rememberSaveable { mutableStateOf<Double?>(null) }
        var chosenLon by rememberSaveable { mutableStateOf<Double?>(null) }
        var destinationLat by rememberSaveable { mutableStateOf<Double?>(null) }
        var destinationLon by rememberSaveable { mutableStateOf<Double?>(null) }
        var choosingDestination by rememberSaveable { mutableStateOf(false) }
        var initialNeighborhood by rememberSaveable { mutableStateOf<String?>(null) }
        LaunchedEffect(state.active) {
            if(!state.active && !app.storageBusy) withContext(kotlinx.coroutines.Dispatchers.IO) { app.repository.archiveLastRealRoute() }
        }
        val pocketScope=rememberCoroutineScope()
        val realPosition=state.position?.takeIf { !state.simulated && it.accuracy<=50 && SystemClock.elapsedRealtimeNanos()/1e9-it.monotonicSeconds in 0.0..30.0 }
        val pocketProgress=remember(pocket.plan?.id,state.active) { br.com.expoworld.core.PocketProgress() }
        val nextPocketStep=remember(pocket.plan,state.position,state.active) { pocket.plan?.let{plan->realPosition?.let{p->pocketProgress.next(plan,br.com.expoworld.core.PocketPoint(p.latitude,p.longitude))}} }

        val now by produceState(SystemClock.elapsedRealtimeNanos() / 1e9) {
            while (true) { value = SystemClock.elapsedRealtimeNanos() / 1e9; delay(2000) }
        }
        var simulatedView by rememberSaveable { mutableStateOf(false) }
        var scenarioIndex by rememberSaveable { mutableIntStateOf(0) }
        var highlight by rememberSaveable { mutableStateOf(false) }
        val preferences = remember { getSharedPreferences("map", MODE_PRIVATE) }
        var cardTitle by rememberSaveable { mutableStateOf(preferences.getString("cardTitle", "Minha expedição") ?: "Minha expedição") }
        var fogOpacity by remember { mutableFloatStateOf(preferences.getFloat("fogOpacity", .55f).coerceIn(.2f, .8f)) }
        val storageRevision by operations.revision
        LaunchedEffect(storageRevision) {
            fogOpacity=preferences.getFloat("fogOpacity",.55f).coerceIn(.2f,.8f)
            cardTitle=preferences.getString("cardTitle","Minha expedição") ?: "Minha expedição"
            worldMap.refreshFog()
        }
        val viewMode = if (state.active) { if (state.simulated) "simulation" else "real" } else if (simulatedView) "simulation" else "real"
        LaunchedEffect(pocket.plan,viewMode) { worldMap.setPocket(pocket.plan.takeIf { viewMode == "real" }) }
        val progressFlow = remember(viewMode) { app.repository.progress(viewMode) }
        val progressValue by progressFlow.collectAsStateWithLifecycle(ExplorationProgress(mode = viewMode))
        val progress = progressValue.takeIf { it.mode == viewMode } ?: ExplorationProgress(mode = viewMode)
        val cityFlow = remember(viewMode) { app.city.progress(viewMode) }
        val cityValue by cityFlow.collectAsStateWithLifecycle(CityProgress())
        var currentNeighborhood by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(state.position, viewMode) {
            currentNeighborhood = app.city.neighborhoodAt(state.position.takeIf { state.simulated == (viewMode == "simulation") })
        }
        val cityProgress = cityValue.copy(currentNeighborhoodId = currentNeighborhood)
        val online by connectivity.online.collectAsStateWithLifecycle()
        val isFollowing by following
        val traceFlow = remember(viewMode) { app.repository.route(viewMode) }
        val completedTrace by traceFlow.collectAsStateWithLifecycle(null)
        val route = completedTrace?.takeIf { it.mode == viewMode }
        var selectedSavedId by rememberSaveable { mutableStateOf<String?>(null) }
        val savedRoutesFlow = remember { app.repository.savedRoutes() }
        val savedRoutes by savedRoutesFlow.collectAsStateWithLifecycle(emptyList())
        val savedTraceFlow = remember(selectedSavedId) { app.repository.route(selectedSavedId ?: "no-selection") }
        val savedTrace by savedTraceFlow.collectAsStateWithLifecycle(null)
        val selectedTrace = savedTrace?.takeIf { it.mode == selectedSavedId && viewMode == "real" && !state.active }
        val displayedRoute = selectedTrace ?: route?.takeIf { !state.active || !it.completed }
        var traceVisible by rememberSaveable { mutableStateOf(true) }
        var saveBusy by remember { mutableStateOf(false) }
        var saveMessage by remember { mutableStateOf("") }
        val saveScope = rememberCoroutineScope()
        var previousViewMode by rememberSaveable { mutableStateOf(viewMode) }
        var previousActive by rememberSaveable { mutableStateOf(state.active) }
        LaunchedEffect(viewMode,state.active) {
            if(previousViewMode!=viewMode || previousActive!=state.active){selectedSavedId=null;saveMessage=""}
            previousViewMode=viewMode;previousActive=state.active
        }
        LaunchedEffect(selectedTrace) { selectedTrace?.let { traceVisible = true; worldMap.focusTrace(it) } }
        LaunchedEffect(selectedSavedId) {
            savedRoutes.firstOrNull{it.id==selectedSavedId}?.let{saved->cardTitle=saved.title.take(40);preferences.edit().putString("cardTitle",cardTitle).apply()}
        }
        var focusAfterTrip by rememberSaveable { mutableStateOf<Long?>(null) }
        val mapStatus by mapMessage
        val currentDialog by dialog
        val permissionsRevision by permissionVersion
        val precise = remember(permissionsRevision) { fineGranted() }
        LaunchedEffect(state.active) {
            if (state.active) { manager.removeUpdates(preview); highlight = false } else startPreview()
        }
        LaunchedEffect(highlight, state.active, viewMode) { worldMap.setDiscoveries(highlight && !state.active) }
        LaunchedEffect(fogOpacity) { worldMap.setFogOpacity(fogOpacity) }
        LaunchedEffect(viewMode, progress.cells) { worldMap.setMode(viewMode) }
        LaunchedEffect(state.activity) { worldMap.setActivity(state.activity) }
        LaunchedEffect(state.position, viewMode) { worldMap.updatePosition(state.position.takeIf { state.simulated == (viewMode == "simulation") }) }
        LaunchedEffect(state.active, route?.startedAtMillis, route?.completed, viewMode) {
            if (state.active && route != null && !route.completed) focusAfterTrip = route.startedAtMillis
            if (!state.active && route?.completed == true && route.startedAtMillis == focusAfterTrip) {
                traceVisible = true; worldMap.focusTrace(route); focusAfterTrip = null
            }
            if (state.active) worldMap.setFollowing(true) else worldMap.setFollowing(false)
        }
        LaunchedEffect(displayedRoute, state.liveHead, traceVisible, state.active) {
            worldMap.setTrace(displayedRoute.takeIf { traceVisible || state.active }, state.liveHead.takeIf { state.active })
        }
        var wasOnline by remember { mutableStateOf(online) }
        LaunchedEffect(online) { if (!wasOnline && online) worldMap.retryIfNeeded(); wasOnline = online }
        val status = if (!state.active && state.simulated != (viewMode == "simulation")) {
            if (viewMode == "simulation") "Modo simulado. Inicie o trajeto de desenvolvimento para testar a revelação."
            else "Exploração real. Suas áreas estão salvas; inicie uma viagem para revelar novos lugares."
        } else state.message
        val gpsSignal = GpsSignal.from(state.gpsFix.takeIf { precise && viewMode == "real" }, now)
        LaunchedEffect(state.gpsFix, gpsSignal, viewMode) {
            worldMap.updateAccuracy(state.gpsFix.takeIf { gpsSignal.quality != GpsQuality.LOST && viewMode == "real" })
        }
        ExplorationScreen(
            worldMap = worldMap,
            onThemeChange = { app.selectTheme(it) },
            onPocketOpen = { worldMap.cancelPocketPick();pickingPocketStart=false;initialNeighborhood=null;pocketLibrary=false;pocketOpen=true },
            onRoutesOpen = { initialNeighborhood=null;pocketLibrary=true;pocketOpen=true },
            onPocketStart = { pocket.plan?.let{plan->
                if(realPosition!=null && br.com.expoworld.core.PocketPoint(realPosition.latitude,realPosition.longitude).distance(plan.path.points.first())<=150 && !state.active && !app.storageBusy) {
                    app.tracking.update{it.copy(activity=plan.request.activity)};pendingPocketStart=true;selectedSavedId=null;prepareStart()
                }
            } },
            canPocketStart = realPosition?.let{p->pocket.plan?.path?.points?.firstOrNull()?.let{br.com.expoworld.core.PocketPoint(p.latitude,p.longitude).distance(it)<=150}}==true,
            onNeighborhoodPlan = { id -> initialNeighborhood=id;pocketLibrary=false;pocketOpen=true },
            pocket = pocket,
            pocketInstruction = nextPocketStep?.instruction,
            onPocketReturn = { realPosition?.let{app.pocket.returnNow(pocketScope,br.com.expoworld.core.PocketPoint(it.latitude,it.longitude))} },
            canPocketReturn = realPosition!=null && !state.simulated,

            state = state,
            simulatedView = viewMode == "simulation",
            progress = progress,
            cityProgress = cityProgress,
            onActivityChange = { activity ->
                if (!state.active) { selectedSavedId = null; app.tracking.update { it.copy(activity = activity) } }
            },
            onNeighborhoodFocus = { worldMap.focusNeighborhood(it) },
            online = online,
            following = isFollowing,
            onFollowingChange = { worldMap.setFollowing(it) },
            status = status,
            mapStatus = mapStatus,
            precise = precise,
            gpsSignal = gpsSignal,
            fogOpacity = fogOpacity,
            onFogOpacityChange = { fogOpacity = it; preferences.edit().putFloat("fogOpacity", it).apply() },
            highlight = highlight,
            onHighlightChange = { highlight = it },
            onOverview = { worldMap.overview() },
            onPauseToggle = {
                if (state.simulated) simulation.togglePause()
                else startService(Intent(this, ExplorationService::class.java).setAction(if (state.paused) ExplorationService.RESUME else ExplorationService.PAUSE))
            },
            scenario = SimulationScenario.entries[scenarioIndex],
            onScenarioChange = { scenarioIndex = it.ordinal },
            storageBusy = storageBusy.value,
            storageMessage = storageMessage.value,
            exportProgress = exportProgress.floatValue,
            onCancelStorage = { cancelStorage() },
            onExport = { exportBackup.launch("expo-world-${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date())}.expoworld") },
            onImport = { importBackup.launch(arrayOf("application/octet-stream", "text/plain", "*/*")) },
            cardTitle = cardTitle,
            onCardTitleChange = { cardTitle = it.take(40); preferences.edit().putString("cardTitle", cardTitle).apply() },
            onCardExport = { preferences.edit().putString("exportMode", selectedSavedId ?: viewMode).apply(); exportCard.launch("expo-world-cartao.png") },
            onAnimationExport = { preferences.edit().putString("exportMode", selectedSavedId ?: viewMode).apply(); exportAnimation.launch("expo-world-replay.gif") },
            onVideoExport = { preferences.edit().putString("exportMode", selectedSavedId ?: viewMode).apply(); exportVideo.launch("expo-world-replay.mp4") },
            trace = displayedRoute,
            traceVisible = traceVisible,
            onTraceVisibleChange = { traceVisible = it },
            onTraceFocus = { displayedRoute?.let { worldMap.focusTrace(it) } },
            savedRoutes = savedRoutes,
            selectedSavedId = selectedSavedId,
            onSavedRouteSelect = { selectedSavedId = it; if (it == null) route?.let { data -> worldMap.focusTrace(data) } },
            canSave = !state.active && viewMode == "real" && selectedSavedId == null && route?.completed == true && route.samples.isNotEmpty(),
            saveBusy = saveBusy,
            saveMessage = saveMessage,
            onSaveRoute = { title ->
                val toSave = route
                if (!state.active && viewMode == "real" && toSave?.completed == true && !saveBusy) {
                    saveBusy = true
                    saveScope.launch {
                        try {
                            app.repository.saveRealRoute(title, toSave.startedAtMillis)
                            saveMessage = "Trajeto salvo: ${title.trim()}"
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (e: Exception) { saveMessage = "Não foi possível salvar. Confira o espaço livre e tente novamente antes de iniciar outro percurso." }
                        finally { saveBusy = false }
                    }
                }
            },
            notificationsHidden = state.active && !state.simulated && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED,
            onSimulationChange = { simulatedView = it },
            onTripToggle = {
                selectedSavedId = null
                if (app.storageBusy) Unit
                else if (state.active) stopTrip()
                else if (viewMode == "simulation") simulation.start(lifecycleScope, SimulationScenario.entries[scenarioIndex])
                else { pendingPocketStart=false;prepareStart() }
            },
            onCenter = { if (!precise && viewMode == "real") requestLocation(false) else {
                worldMap.center(); if (state.active) worldMap.setFollowing(true)
            } },
            onLocationPermission = { requestLocation(false) },
            onAppSettings = { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri())) },
            onGpsSettings = { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
        )
        if(pickingPocketStart) AlertDialog(onDismissRequest={pickingPocketStart=false;worldMap.cancelPocketPick()},
            title={Text("Escolher partida")},text={Text("Feche este aviso, mova o mapa e toque no ponto de partida desejado.")},
            confirmButton={TextButton(onClick={pickingPocketStart=false}){Text("Escolher no mapa")}})
        if(pocketOpen) br.com.expoworld.ui.PocketPlannerDialog(app,worldMap,state,realPosition,
            chosenLat?.let{lat->chosenLon?.let{lon->br.com.expoworld.core.PocketPoint(lat,lon)}},
            onUsePosition={chosenLat=null;chosenLon=null},
            onPickStart={pocketOpen=false;pickingPocketStart=true;worldMap.choosePocketStart{point->chosenLat=point.latitude;chosenLon=point.longitude;pocketOpen=true}},
            onStart={plan->app.pocket.select(plan);app.tracking.update{it.copy(activity=plan.request.activity)};pendingPocketStart=true;prepareStart()},
            onLocation={requestLocation(false)},onDismiss={pocketOpen=false},
            chosenDestination=destinationLat?.let{lat->destinationLon?.let{lon->br.com.expoworld.core.PocketPoint(lat,lon)}},
            onPickDestination={pocketOpen=false;choosingDestination=true;worldMap.choosePocketStart{point->destinationLat=point.latitude;destinationLon=point.longitude;choosingDestination=false;pocketOpen=true}},
            onClearDestination={destinationLat=null;destinationLon=null},
            initialNeighborhoodId=initialNeighborhood,openLibrary=pocketLibrary)
        if(choosingDestination) {
            androidx.activity.compose.BackHandler { choosingDestination=false;worldMap.cancelPocketPick();pocketOpen=true }
            Surface(Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp),tonalElevation=8.dp) {
                Column(Modifier.padding(16.dp)) { Text("Toque no destino desejado no mapa.")
                    TextButton(onClick={choosingDestination=false;worldMap.cancelPocketPick();pocketOpen=true}){Text("Cancelar escolha")}
                }
            }
        }
        restorePreview.value?.let { preview ->
            AlertDialog(onDismissRequest={if(!operations.running.value)dismissRestore()},title={Text("Restaurar este backup?")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
                Text("${preview.cells} células • ${preview.routes} viagens • ${preview.plannedRoutes} roteiros")
                Text("${preview.expeditions} expedições • ${preview.streetVisits} créditos de ruas")
                Text(if(preview.hasPreferences) "Inclui tema e preferências. Não inclui mapas baixados." else "Backup antigo de descobertas; outros dados não estão no arquivo.")
                Text("O arquivo foi validado. A restauração mescla os registros e mantém sua exploração atual.")
            if(operations.running.value)LinearProgressIndicator(Modifier.fillMaxWidth())
            }},confirmButton={TextButton(enabled=!operations.running.value,onClick={runBackup(allowPreview=true){try {val added=backup.restorePrepared();"Restauração concluída: $added células novas. Histórico e exploração preservados."}finally{restorePreview.value=null}}}){Text("Restaurar e mesclar")}},dismissButton={TextButton(onClick={if(operations.running.value)cancelStorage() else dismissRestore()}){Text(if(operations.running.value)"Cancelar restauração" else "Cancelar")}})
        }
        if (currentDialog.isNotEmpty()) AlertDialog(
            onDismissRequest = { dialog.value = ""; pendingStart = false },
            title = { Text(when (currentDialog) { "location" -> "Localização precisa"; "gps" -> "Ative o GPS"; else -> "Notificação da viagem" }) },
            text = { Text(when (currentDialog) {
                "location" -> "Usamos o GPS para mostrar sua posição e revelar o caminho durante uma viagem. Escolha localização precisa e permita durante o uso. O serviço iniciado aqui continua com a tela bloqueada; não pedimos acesso à localização o tempo todo."
                "gps" -> "A exploração precisa do GPS do aparelho. Ative-o nas configurações, volte ao app e inicie a viagem."
                else -> "A notificação mantém a viagem visível e oferece Encerrar exploração. Se negar, o Android permite o serviço, mas a notificação pode ficar oculta; você pode encerrar pelo app."
            }) },
            confirmButton = { TextButton(onClick = {
                dialog.value = ""
                when (currentDialog) {
                    "location" -> locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    "gps" -> startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    else -> if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else startReal()
                }
            }) { Text(if (currentDialog == "gps") "Abrir configurações" else "Continuar") } },
            dismissButton = { TextButton(onClick = { dialog.value = ""; pendingStart = false }) { Text("Agora não") } }
        )
    }
    override fun onStart() { super.onStart(); worldMap.view.onStart(); connectivity.start() }
    override fun onResume() { super.onResume(); worldMap.view.onResume(); permissionVersion.intValue++; startPreview(); if (pendingStart && fineGranted()) prepareStart() }
    override fun onPause() { runCatching { manager.removeUpdates(preview) }; worldMap.view.onPause(); super.onPause() }
    override fun onStop() { simulation.stop(); connectivity.stop(); worldMap.view.onStop(); super.onStop() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); worldMap.view.onSaveInstanceState(outState) }
    override fun onLowMemory() { super.onLowMemory(); worldMap.view.onLowMemory() }
    override fun onDestroy() { worldMap.dispose(); worldMap.view.onDestroy(); super.onDestroy() }
}
