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
import br.com.expoworld.core.ExplorationProgress
import br.com.expoworld.core.TracePresentation
import br.com.expoworld.core.GpsSignal
import br.com.expoworld.core.GpsQuality
import br.com.expoworld.location.*
import br.com.expoworld.ui.WorldMap
import br.com.expoworld.ui.ExplorationScreen
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
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
    private val manager get() = getSystemService(LocationManager::class.java)
    private val preview = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (!app.tracking.value.active) app.tracking.update { it.copy(gpsFix = Fix(location.latitude, location.longitude,
                if (location.hasAccuracy()) location.accuracy.toDouble() else Double.POSITIVE_INFINITY, location.elapsedRealtimeNanos / 1e9)) }
            if (!app.tracking.value.active && location.hasAccuracy() && location.accuracy <= 50 && SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos < 10_000_000_000L) {
                app.tracking.update { it.copy(simulated = false, position = Fix(location.latitude, location.longitude, location.accuracy.toDouble(), location.elapsedRealtimeNanos / 1e9)) }
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
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF70E1BA))) {
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
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) { pendingStart = true; return }
        pendingStart = false
        if (app.tracking.value.active) return
        if (!fineGranted()) { requestLocation(true); return }
        runCatching { ContextCompat.startForegroundService(this, Intent(this, ExplorationService::class.java)) }
            .onFailure { app.tracking.update { it.copy(message = "Não foi possível iniciar. Mantenha o app aberto e confira as permissões.") } }
    }
    private fun stopTrip() {
        if (app.tracking.value.simulated) simulation.stop()
        else startService(Intent(this, ExplorationService::class.java).setAction(ExplorationService.STOP))
    }
    private fun startPreview() {
        if (!fineGranted() || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
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
        val now by produceState(SystemClock.elapsedRealtimeNanos() / 1e9) {
            while (true) { value = SystemClock.elapsedRealtimeNanos() / 1e9; delay(2000) }
        }
        var simulatedView by rememberSaveable { mutableStateOf(false) }
        val viewMode = if (state.active) { if (state.simulated) "simulation" else "real" } else if (simulatedView) "simulation" else "real"
        val progressFlow = remember(viewMode) { app.repository.progress(viewMode) }
        val progressValue by progressFlow.collectAsStateWithLifecycle(ExplorationProgress(mode = viewMode))
        val progress = progressValue.takeIf { it.mode == viewMode } ?: ExplorationProgress(mode = viewMode)
        val online by connectivity.online.collectAsStateWithLifecycle()
        val isFollowing by following
        val traceFlow = remember(viewMode) { app.repository.route(viewMode) }
        val completedTrace by traceFlow.collectAsStateWithLifecycle(null)
        val route = completedTrace?.takeIf { it.mode == viewMode }
        val displayedRoute = route?.takeIf { !state.active || !it.completed }
        var traceVisible by rememberSaveable { mutableStateOf(true) }
        val mapStatus by mapMessage
        val currentDialog by dialog
        val permissionsRevision by permissionVersion
        val precise = remember(permissionsRevision) { fineGranted() }
        LaunchedEffect(viewMode, progress.cells) { worldMap.setMode(viewMode) }
        LaunchedEffect(state.position, viewMode) { worldMap.updatePosition(state.position.takeIf { state.simulated == (viewMode == "simulation") }) }
        LaunchedEffect(state.active, route?.startedAtMillis, route?.completed, viewMode) {
            if (!state.active && route != null) { traceVisible = true; worldMap.focusTrace(route) }
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
            state = state,
            simulatedView = viewMode == "simulation",
            progress = progress,
            online = online,
            following = isFollowing,
            onFollowingChange = { worldMap.setFollowing(it) },
            status = status,
            mapStatus = mapStatus,
            precise = precise,
            gpsSignal = gpsSignal,
            trace = displayedRoute,
            traceVisible = traceVisible,
            onTraceVisibleChange = { traceVisible = it },
            onTraceFocus = { route?.let { worldMap.focusTrace(it) } },
            notificationsHidden = state.active && !state.simulated && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED,
            onSimulationChange = { simulatedView = it },
            onTripToggle = {
                if (state.active) stopTrip()
                else if (viewMode == "simulation") simulation.start(lifecycleScope)
                else prepareStart()
            },
            onCenter = { if (!precise && viewMode == "real") requestLocation(false) else {
                worldMap.center(); if (state.active) worldMap.setFollowing(true)
            } },
            onLocationPermission = { requestLocation(false) },
            onAppSettings = { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri())) },
            onGpsSettings = { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
        )
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
