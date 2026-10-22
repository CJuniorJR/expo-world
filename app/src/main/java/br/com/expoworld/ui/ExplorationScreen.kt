package br.com.expoworld.ui

import androidx.activity.compose.BackHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.expoworld.WorldApp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import br.com.expoworld.BuildConfig
import br.com.expoworld.R
import br.com.expoworld.TrackingState
import br.com.expoworld.core.RecordedTrace
import br.com.expoworld.core.TracePresentation
import br.com.expoworld.core.ExplorationProgress
import br.com.expoworld.core.TripSummary
import br.com.expoworld.core.ActivityKind
import br.com.expoworld.core.CityProgress
import br.com.expoworld.core.ExplorerTheme
import br.com.expoworld.storage.SavedRoute
import br.com.expoworld.core.GpsSignal
import br.com.expoworld.core.GpsQuality
import br.com.expoworld.core.SimulationScenario
import br.com.expoworld.core.MapPresentation
import br.com.expoworld.core.ArtCommand
import br.com.expoworld.core.ExpeditionArt
import br.com.expoworld.core.DiscoveryEpoch
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.horizontalScroll
import kotlinx.coroutines.delay
import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val Ink: Color @Composable get() = themeColor(LocalExplorerTheme.current.background)
private val Mint: Color @Composable get() = themeColor(LocalExplorerTheme.current.accent)
private val Muted: Color @Composable get() = themeColor(LocalExplorerTheme.current.muted)
private val Paper: Color @Composable get() = themeColor(LocalExplorerTheme.current.text)
private val Card: Color @Composable get() = themeColor(LocalExplorerTheme.current.surface)
private val Tint: Color @Composable get() = themeColor(LocalExplorerTheme.current.tinted)
private val Track: Color @Composable get() = themeColor(LocalExplorerTheme.current.track)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExplorationScreen(
    worldMap: WorldMap,
    onThemeChange: (ExplorerTheme) -> Unit,
    onPocketOpen: () -> Unit,
    onRoutesOpen: () -> Unit,
    onPocketStart: () -> Unit,
    canPocketStart: Boolean,
    onNeighborhoodPlan: (String) -> Unit,
    pocket: br.com.expoworld.storage.PocketState,
    pocketInstruction: String?,
    onPocketReturn: () -> Unit,
    canPocketReturn: Boolean,
    state: TrackingState,
    simulatedView: Boolean,
    progress: ExplorationProgress,
    cityProgress: CityProgress,
    onActivityChange: (ActivityKind) -> Unit,
    onNeighborhoodFocus: (String) -> Unit,
    online: Boolean,
    following: Boolean,
    onFollowingChange: (Boolean) -> Unit,
    status: String,
    mapStatus: String,
    precise: Boolean,
    gpsSignal: GpsSignal,
    fogOpacity: Float,
    onFogOpacityChange: (Float) -> Unit,
    highlight: Boolean,
    onHighlightChange: (Boolean) -> Unit,
    onOverview: () -> Unit,
    onPauseToggle: () -> Unit,
    scenario: SimulationScenario,
    onScenarioChange: (SimulationScenario) -> Unit,
    storageBusy: Boolean,
    storageMessage: String,
    exportProgress: Float,
    onCancelStorage: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    cardTitle: String,
    onCardTitleChange: (String) -> Unit,
    onCardExport: () -> Unit,
    onAnimationExport: () -> Unit,
    onVideoExport: () -> Unit,
    trace: RecordedTrace?,
    traceVisible: Boolean,
    onTraceVisibleChange: (Boolean) -> Unit,
    onTraceFocus: () -> Unit,
    savedRoutes: List<SavedRoute>,
    selectedSavedId: String?,
    onSavedRouteSelect: (String?) -> Unit,
    canSave: Boolean,
    saveBusy: Boolean,
    saveMessage: String,
    onSaveRoute: (String) -> Unit,
    notificationsHidden: Boolean,
    onSimulationChange: (Boolean) -> Unit,
    onTripToggle: () -> Unit,
    onCenter: () -> Unit,
    onLocationPermission: () -> Unit,
    onAppSettings: () -> Unit,
    onGpsSettings: () -> Unit
) {
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var menuSection by rememberSaveable { mutableIntStateOf(0) }
    var expeditionTraces by remember { mutableStateOf<List<RecordedTrace>>(emptyList()) }
    var expeditionSummaryOpen by remember { mutableStateOf(false) }
    val navigate: (Int) -> Unit = { index ->
        when(index) {
            0 -> menuOpen=false
            1 -> { menuSection=1;menuOpen=true }
            2 -> { menuOpen=false;onRoutesOpen() }
            3 -> { menuSection=2;menuOpen=true }
        }
    }
    BackHandler(menuOpen) { menuOpen=false }
    var confirmEnd by rememberSaveable { mutableStateOf(false) }
    var gpsDialog by rememberSaveable { mutableStateOf(false) }
    val requestTripAction: () -> Unit = {
        if (state.active) confirmEnd = true
        else { menuOpen = false; onTripToggle() }
    }
    LaunchedEffect(state.active) { if (!state.active) confirmEnd = false }
    var traceDetails by rememberSaveable { mutableStateOf(false) }
    var titleDialog by rememberSaveable { mutableStateOf(false) }
    var routeTitle by rememberSaveable { mutableStateOf("") }
    if (titleDialog && canSave) AlertDialog(
        onDismissRequest = { titleDialog = false },
        title = { Text("Salvar trajeto") },
        text = { OutlinedTextField(value = routeTitle, onValueChange = { routeTitle = it.take(80) },
            label = { Text("Título") }, singleLine = true, supportingText = { Text("${routeTitle.length}/80") }) },
        confirmButton = { TextButton(enabled = routeTitle.isNotBlank() && !saveBusy, onClick = {
            onSaveRoute(routeTitle.trim()); titleDialog = false
        }) { Text("Salvar") } },
        dismissButton = { TextButton(onClick = { titleDialog = false }) { Text("Cancelar") } }
    )
    var presentationIndex by rememberSaveable { mutableIntStateOf(0) }
    val presentation = MapPresentation.entries[presentationIndex]
    var comparison by rememberSaveable { mutableFloatStateOf(.5f) }
    var replayFraction by rememberSaveable { mutableFloatStateOf(0f) }
    var playing by remember { mutableStateOf(false) }
    var cardOpen by rememberSaveable { mutableStateOf(false) }
    var previewPlaying by remember { mutableStateOf(false) }
    var previewFraction by remember { mutableFloatStateOf(1f) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) playing = false }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(state.active, simulatedView) {
        presentationIndex = 0; playing = false; cardOpen = false
    }
    LaunchedEffect(presentation, comparison, replayFraction, trace) {
        worldMap.setPresentation(presentation, if (presentation == MapPresentation.COMPARE) comparison else replayFraction)
    }
    LaunchedEffect(playing, presentation) {
        if (playing && presentation == MapPresentation.REPLAY) {
            val first = replayFraction
            val started = android.os.SystemClock.elapsedRealtime()
            while (replayFraction < 1f) {
                delay(200)
                replayFraction = (first + (android.os.SystemClock.elapsedRealtime() - started) / 12_000f).coerceAtMost(1f)
            }
            playing = false
        }
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = context.applicationContext as WorldApp
    val palette = LocalExplorerTheme.current
    var cardMap by remember { mutableStateOf<br.com.expoworld.storage.ExpeditionMapImage?>(null) }
    var cardMapError by remember { mutableStateOf("") }
    LaunchedEffect(cardOpen, trace) {
        cardMap = null; cardMapError = ""
        if (cardOpen && trace != null) {
            try { cardMap = br.com.expoworld.storage.ExpeditionMapLoader.load(context, trace) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { cardMapError = "Não foi possível carregar o mapa. Confira a internet e tente abrir o cartão novamente." }
        }
    }
    DisposableEffect(cardMap) {
        val image = cardMap
        onDispose { image?.bitmap?.recycle() }
    }
    var art by remember { mutableStateOf<List<ArtCommand>>(emptyList()) }
    LaunchedEffect(cardOpen,trace,cardTitle,cardMap,palette,previewPlaying) {
        if(cardOpen && trace!=null) {
            val timeline=br.com.expoworld.core.TraceRenderTimeline(trace.activity.stopKmh)
            val date=java.text.SimpleDateFormat("dd.MM.yyyy",Locale.forLanguageTag("pt-BR")).format(java.util.Date(trace.startedAtMillis))
            val started=android.os.SystemClock.elapsedRealtime()
            if(previewPlaying)previewFraction=0f
            do {
                art=withContext(Dispatchers.Default) { ExpeditionArt.scene(trace,cardTitle,date,fraction=previewFraction,theme=palette,mapPoint=cardMap?.point,timeline=timeline) }
                if(!previewPlaying||previewFraction>=1f)break
                delay(100)
                previewFraction=((android.os.SystemClock.elapsedRealtime()-started)/10000f).coerceAtMost(1f)
            } while(true)
            if(previewPlaying)previewPlaying=false
        }
    }
    var summaryOpen by rememberSaveable { mutableStateOf(false) }
    var pendingSummaryStart by rememberSaveable { mutableStateOf<Long?>(null) }
    LaunchedEffect(state.active, trace?.startedAtMillis, trace?.completed) {
        if (state.active && trace != null && !trace.completed) pendingSummaryStart = trace.startedAtMillis
        if (!state.active && trace?.completed == true && pendingSummaryStart == trace.startedAtMillis) {
            pendingSummaryStart = null; summaryOpen = true; menuOpen = false; onHighlightChange(true)
        }
    }
    LaunchedEffect(highlight) { if (highlight) { delay(120_000); onHighlightChange(false) } }
    var summary by remember { mutableStateOf<TripSummary?>(null) }
    LaunchedEffect(trace) { summary = null; summary = trace?.let { data -> withContext(Dispatchers.Default) { TripSummary.from(data) } } }
    val density = LocalDensity.current
    val topInset = WindowInsets.safeDrawing.getTop(density)
    val bottomInset = WindowInsets.safeDrawing.getBottom(density)
    LaunchedEffect(topInset, bottomInset, presentation) { worldMap.setInsets(topInset, bottomInset + (density.density * (if (presentation != MapPresentation.NORMAL) 200 else 80)).toInt()) }
    val activeGps = state.active && !state.simulated
    val waitingGps = activeGps && !state.message.startsWith("Explorando")
    val statusLabel = when {
        storageBusy -> "Preparando arquivo…"
        state.active && state.paused -> if (simulatedView) "MODO SIMULADO • pausado" else "Percurso pausado"
        simulatedView -> "MODO SIMULADO"
        waitingGps -> when {
            state.message.startsWith("Movimento incompatível") -> "Confira a atividade escolhida"
            state.message.contains("GPS desligado") -> "GPS desligado"
            else -> "Aguardando GPS preciso"
        }
        activeGps && !online -> "GPS ativo • sem internet"
        activeGps -> "Explorando"
        mapStatus.startsWith("Visão geral") -> "Visão geral • aproxime para detalhes"
        mapStatus.startsWith("Aproxime") -> "Aproxime para ver a exploração"
        mapStatus.isNotEmpty() -> "Confira o mapa"
        trace?.isInterrupted(state.active) == true -> "Percurso interrompido • recuperado"
        trace != null && trace.samples.isNotEmpty() -> "Percurso concluído"
        !online -> "Sem internet"
        else -> "Pronto para explorar"
    }
    val statusColor = if (waitingGps || !online || mapStatus.isNotEmpty()) if (LocalExplorerTheme.current.light) Color(0xFF805500) else Color(0xFFFFD589) else Mint
    val actionLabel = if (state.active) "Encerrar exploração" else if (simulatedView) "Iniciar trajeto simulado" else when (state.activity) {
        ActivityKind.WALK -> "Iniciar caminhada"; ActivityKind.BIKE -> "Iniciar pedalada"; ActivityKind.CAR -> "Iniciar exploração"
    }
    val actionColors = ButtonDefaults.buttonColors(
        containerColor = if (state.active) Color(0xFFFFB4AB) else Mint,
        contentColor = if (state.active) Color(0xFF35100D) else Ink
    )

    Box(Modifier.fillMaxSize()) {
        // The map owns the entire window. All controls float over it.
        AndroidView(factory = { worldMap.view }, modifier = Modifier.fillMaxSize())
        if (presentation == MapPresentation.COMPARE) Canvas(Modifier.fillMaxSize()) {
            drawLine(Color(0xFFFFD166), Offset(size.width * comparison, 0f), Offset(size.width * comparison, size.height), 3.dp.toPx())
        }
        Box(Modifier.fillMaxWidth().height(160.dp).align(Alignment.TopCenter)
            .background(Brush.verticalGradient(listOf(Ink.copy(alpha = .65f), Color.Transparent))))
        Box(Modifier.fillMaxWidth().height(180.dp).align(Alignment.BottomCenter)
            .background(Brush.verticalGradient(listOf(Color.Transparent, Ink.copy(alpha = .72f)))))

        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Surface(modifier = Modifier.weight(1f, fill = false).padding(end = 12.dp), color = Ink.copy(alpha = .92f), contentColor = Paper,
                    shape = RoundedCornerShape(22.dp), shadowElevation = 6.dp) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(painterResource(R.drawable.ic_explore), null, tint = Mint, modifier = Modifier.size(24.dp))
                        Column {
                            Text("EXPO WORLD", fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("Cada caminho, uma descoberta", fontSize = 12.sp, color = Muted,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                FilledTonalIconButton(onClick = { menuSection=3;menuOpen = true }, modifier = Modifier.size(56.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Ink, contentColor = Paper)) {
                    Icon(painterResource(R.drawable.ic_settings), "Abrir ajustes", modifier = Modifier.size(24.dp))
                }
            }

            if (presentation == MapPresentation.NORMAL) Column(
                Modifier.align(Alignment.TopStart).padding(top = 90.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!state.active) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    ActivityKind.entries.forEach { activity ->
                        FilterChip(selected=state.activity==activity,enabled=!storageBusy,onClick={onActivityChange(activity)},
                            label={Text(if(activity==ActivityKind.WALK) "A pé" else activity.label)},modifier=Modifier.weight(1f))
                    }
                }
                val neighborhood=cityProgress.neighborhoods.firstOrNull{it.id==cityProgress.currentNeighborhoodId}
                Surface(onClick={navigate(1)},color=Ink.copy(alpha=.96f),contentColor=Paper,shape=RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text(neighborhood?.let{String.format(Locale.forLanguageTag("pt-BR"),"%s • %.1f%% explorado",it.name,it.fraction*100)}
                            ?: "Curitiba • ver progresso por bairro",fontWeight=FontWeight.SemiBold)
                        Text("${state.activity.label} • ${if(simulatedView) "SIMULADO • descobertas separadas" else "Exploração real"}",style=MaterialTheme.typography.labelMedium,color=Mint)
                    }
                }
                if (!state.active) FilledTonalButton(onClick=onPocketOpen,enabled=!simulatedView&&!storageBusy,
                    modifier=Modifier.fillMaxWidth()){Text("Planejar uma volta no meu tempo")}
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (presentation != MapPresentation.NORMAL) Surface(color = Ink.copy(alpha = .97f), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(when (presentation) {
                                MapPresentation.COMPARE -> "ANTES  ←  |  →  DEPOIS"
                                MapPresentation.REPLAY -> "REPLAY • ${(replayFraction * 100).toInt()}%"
                                else -> "SUAS PRIMEIRAS VEZES"
                            }, color = Mint, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            TextButton(onClick = { playing = false; presentationIndex = 0 }) { Text("Sair") }
                        }
                        when (presentation) {
                            MapPresentation.COMPARE -> Slider(value = comparison, onValueChange = { comparison = it })
                            MapPresentation.REPLAY -> {
                                Slider(value = replayFraction, onValueChange = { playing = false; replayFraction = it })
                                TextButton(onClick = { if (replayFraction >= 1f) replayFraction = 0f; playing = !playing }) {
                                    Text(if (playing) "Pausar replay" else "Reproduzir")
                                }
                            }
                            MapPresentation.EPOCHS -> DiscoveryEpoch.entries.forEach { epoch ->
                                Text("● ${epoch.label}", color = Color(android.graphics.Color.parseColor(epoch.color)), fontSize = 12.sp)
                            }
                            else -> Unit
                        }
                        if (presentation != MapPresentation.EPOCHS) Text("Aproxime para comparar ou animar a névoa no detalhe.", color = Muted, fontSize = 12.sp)
                    }
                }
                Surface(onClick = {
                    when {
                        simulatedView -> {menuSection=3;menuOpen=true}
                        !precise -> onLocationPermission()
                        state.message.contains("GPS desligado") -> onGpsSettings()
                        else -> gpsDialog=true
                    }
                }, color = Ink.copy(alpha = .94f), contentColor = statusColor,
                    shape = CircleShape) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(7.dp).background(statusColor, CircleShape))
                        Column {
                            Text(statusLabel, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            if (!simulatedView && precise && !state.paused) Text(gpsSignal.label +
                                (gpsSignal.accuracyMeters?.let { " • ~${it.toInt()} m" } ?: ""),
                                color = if (gpsSignal.quality == GpsQuality.GOOD) Mint else Color(0xFFFFD589), fontSize = 12.sp)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { if (state.active) onPauseToggle() else if(pocket.plan!=null&&!simulatedView)onPocketStart() else requestTripAction() },
                        enabled = !storageBusy && (state.active || pocket.plan==null || simulatedView || canPocketStart),
                        colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Ink), shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.weight(1f).heightIn(min = 56.dp), contentPadding = PaddingValues(14.dp)) {
                        Icon(painterResource(if (state.active) { if (state.paused) R.drawable.ic_play else R.drawable.ic_pause } else R.drawable.ic_explore), null,
                            modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(if (state.active) { if (state.paused) "Continuar percurso" else "Pausar percurso" } else if(pocket.plan!=null&&!simulatedView)"Começar roteiro" else actionLabel, fontWeight = FontWeight.SemiBold)
                    }
                    FilledTonalIconButton(onClick = onCenter, modifier = Modifier.size(56.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = if (following) Mint else Ink, contentColor = if (following) Ink else Mint)) {
                        Icon(painterResource(R.drawable.ic_center), if (following) "Acompanhando minha posição" else "Centralizar na minha posição", modifier = Modifier.size(24.dp))
                    }
                }
                if(!state.active && pocket.plan!=null && !simulatedView) {
                    val plan=pocket.plan
                    Text("${plan.request.activity.label} • ${String.format(Locale.forLanguageTag("pt-BR"),"%.1f km",plan.path.distanceMeters/1000)} • cerca de ${(plan.path.seconds/60).toInt()} min",color=Mint,style=MaterialTheme.typography.labelLarge)
                    if(!canPocketStart) Text("Obtenha GPS e aproxime-se da partida para começar este roteiro.",color=Paper,style=MaterialTheme.typography.bodySmall)
                    TextButton(onClick=requestTripAction,enabled=!storageBusy){Text("Explorar livremente sem seguir roteiro")}
                }
                if (state.active) TextButton(onClick = requestTripAction, enabled = !storageBusy,
                    colors = ButtonDefaults.textButtonColors(contentColor = if (palette.light) Color(0xFF9B3028) else Color(0xFFFFB4AB))) {
                    Icon(painterResource(R.drawable.ic_stop), null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp)); Text("Encerrar percurso")
                }
                if(pocket.plan!=null && pocket.active && state.active && !simulatedView) Surface(color=Ink,contentColor=Paper,shape=RoundedCornerShape(14.dp)){
                    Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                        Text(if(pocket.plan.returning)"Retorno ao início" else "${pocket.plan.request.purpose.label} • roteiro planejado",color=Mint,style=MaterialTheme.typography.labelLarge)
                        pocketInstruction?.let{Text("A seguir: $it",maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall)}
                        if(pocket.message.isNotBlank())Text(pocket.message,maxLines=2,style=MaterialTheme.typography.bodySmall)
                        OutlinedButton(enabled=canPocketReturn && !pocket.busy,onClick=onPocketReturn,modifier=Modifier.fillMaxWidth()){Text(if(pocket.busy)"Calculando volta…" else "Voltar ao início agora")}
                    }
                }
                if(mapStatus.isNotBlank()) OutlinedButton(onClick={worldMap.reload()},modifier=Modifier.fillMaxWidth()) {Text("Recarregar mapa")}
                Text("© MapTiler  ·  © OpenStreetMap contributors", color = Color(0xFFE0E7E9), fontSize = 12.sp)
                if(expeditionTraces.isNotEmpty()) TextButton(onClick={worldMap.showExpedition(emptyList());expeditionTraces=emptyList()}){Text("Sair do mapa da expedição")}
                PrimaryNavigation(0,navigate)
            }
        }
    }

    if (menuOpen) Surface(Modifier.fillMaxSize(),color=Ink,contentColor=Paper) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(when(menuSection){1->"Bairros";2->"Histórico";3->"Ajustes";else->"Explorar"}, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(if (simulatedView) "Modo simulado • dados separados" else "Novos caminhos, no seu ritmo", color = Muted,
                        style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { menuOpen = false }) {
                    Icon(painterResource(R.drawable.ic_close), "Fechar menu")
                }
            }
            Text(when (menuSection) {
                    1 -> "Percentuais por bairro e suas descobertas"
                    2 -> "Salvar, rever e compartilhar seus percursos"
                    3 -> "Aparência, mapa, localização e backup"
                    else -> "Planeje uma volta ou comece a explorar"
                }, color = Muted, style = MaterialTheme.typography.bodySmall)
            HorizontalDivider(color = Track)
            key(menuSection) {
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    if (menuSection == 0) {
                        OutlinedButton(onClick={menuOpen=false;onPocketOpen()},enabled=!simulatedView && !storageBusy,modifier=Modifier.fillMaxWidth()){Text("Exploração de bolso • planejar uma volta")}
                        if(simulatedView)Text("Volte ao modo real para planejar roteiros.",style=MaterialTheme.typography.bodySmall)
                        Text("Atividade", style = MaterialTheme.typography.titleMedium)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ActivityKind.entries.forEach { activity ->
                                FilterChip(selected = state.activity == activity, enabled = !state.active,
                                    onClick = { onActivityChange(activity) }, label = { Text(activity.label, fontSize = 12.sp) },
                                    modifier = Modifier.weight(1f))
                            }
                        }
                        Text(if (state.active) "${state.activity.label} em andamento. Encerre para trocar de atividade."
                            else "Escolha a atividade antes de iniciar. O mapa e o progresso são compartilhados entre carro, caminhada e bicicleta.", color = Muted,
                            style = MaterialTheme.typography.bodySmall)
                        Surface(shape = RoundedCornerShape(20.dp), color = Card, contentColor = Paper) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(statusLabel, color = statusColor, style = MaterialTheme.typography.labelLarge)
                                Text(status, style = MaterialTheme.typography.bodyMedium)
                                if (!online) Text("Sem internet: o mapa pode ficar sem ruas novas, mas o GPS e a gravação no aparelho continuam.",
                                    color = if (LocalExplorerTheme.current.light) Color(0xFF805500) else Color(0xFFFFD589), style = MaterialTheme.typography.bodySmall)
                                if (notificationsHidden) Text("Notificação oculta: encerre pelo app ou pelo gerenciador de apps ativos do Android.",
                                    color = if (LocalExplorerTheme.current.light) Color(0xFF805500) else Color(0xFFFFD589), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (state.active) OutlinedButton(onClick = onPauseToggle, modifier = Modifier.fillMaxWidth()) {
                            Text(if (state.paused) "Continuar este percurso" else "Pausar este percurso")
                        }
                        Button(onClick = requestTripAction, enabled = !storageBusy, colors = actionColors, shape = RoundedCornerShape(18.dp),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(actionLabel, fontWeight = FontWeight.SemiBold) }
                    }
                    if (menuSection == 1) {
                        NeighborhoodPanel(cityProgress, simulatedView, state.position != null && state.simulated == simulatedView,
                            onFocus={id->onNeighborhoodFocus(id);menuOpen=false},onPlan={id->menuOpen=false;onNeighborhoodPlan(id)})
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            InfoCard(areaLabel(progress.areaSquareMeters), "área descoberta • estimativa", Modifier.weight(1f))
                            InfoCard("50 m", "de cada lado do trajeto", Modifier.weight(1f))
                        }
                        Text("Suas descobertas ficam salvas neste aparelho e se acumulam entre viagens.", color = Muted,
                            style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { onOverview(); menuOpen = false }, modifier = Modifier.fillMaxWidth()) {
                            Text("Ver todas as descobertas")
                        }
                        Surface(shape = RoundedCornerShape(20.dp), color = Tint, contentColor = Paper) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("✦  ${progress.title}", color = Mint, style = MaterialTheme.typography.titleMedium)
                                LinearProgressIndicator(progress = { progress.progressToNext }, modifier = Modifier.fillMaxWidth(),
                                    color = Mint, trackColor = Track)
                                Text(progress.nextTarget?.let { "Próximo marco: ${areaLabel(it)} descobertos" }
                                    ?: "Seu mapa continua crescendo a cada novo caminho.", color = Muted, style = MaterialTheme.typography.bodySmall)
                            }
                        }

                    }
                    if (menuSection == 2) {
                        if(simulatedView) Text("A simulação não entra no seu histórico real. Volte ao modo real em Ajustes para abrir suas viagens.")
                        else HistoryLibrary(app,savedRoutes,selectedSavedId,trace,cityProgress,state.active,
                            onSelect=onSavedRouteSelect,
                            onMap={onTraceVisibleChange(true);onTraceFocus();menuOpen=false},
                            onReplay={onTraceVisibleChange(true);replayFraction=0f;presentationIndex=MapPresentation.REPLAY.ordinal;playing=true;menuOpen=false},
                            onCompare={onTraceVisibleChange(true);comparison=.5f;presentationIndex=MapPresentation.COMPARE.ordinal;playing=false;menuOpen=false},
                            onShare={playing=false;cardOpen=true;menuOpen=false},
                            onEpochs={presentationIndex=MapPresentation.EPOCHS.ordinal;playing=false;menuOpen=false},
                            onExpeditionMap={traces->expeditionTraces=traces;worldMap.showExpedition(traces);expeditionSummaryOpen=true;menuOpen=false})
                        if(trace!=null && selectedSavedId==null) OutlinedButton(onClick={summaryOpen=true;menuOpen=false},modifier=Modifier.fillMaxWidth()){Text("Resumo do último percurso")}
                    }
                    if (menuSection == 3) {
                        ThemePicker(onThemeChange)
                        Text("Intensidade da névoa • ${(fogOpacity * 100).toInt()}%", style = MaterialTheme.typography.titleMedium)
                        Slider(value = fogOpacity, onValueChange = onFogOpacityChange, valueRange = .2f.. .8f)
                        if (!state.active && trace != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text("Destacar áreas novas", style = MaterialTheme.typography.bodyMedium)
                                Text("Dourado • último percurso • por 2 minutos", color = Muted, style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked = highlight, onCheckedChange = onHighlightChange)
                        }
                        Text("Backup completo", style = MaterialTheme.typography.titleMedium)
                        Text("Guarda exploração real, percentuais, viagens, expedições, roteiros e preferências. Restaurar mostra uma prévia e mescla sem apagar os dados atuais. Mapas offline e simulação ficam fora do arquivo.",
                            color = Muted, style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onExport, enabled = !state.active && !storageBusy, modifier = Modifier.weight(1f)) { Text("Exportar") }
                            OutlinedButton(onClick = onImport, enabled = !state.active && !storageBusy, modifier = Modifier.weight(1f)) { Text("Restaurar") }
                        }
                        if (state.active) Text("Encerre o percurso para usar o backup.", color = Muted, style = MaterialTheme.typography.bodySmall)
                        if (storageMessage.isNotEmpty()) Text(storageMessage, color = Mint, style = MaterialTheme.typography.bodySmall)

                        if (mapStatus.isNotEmpty()) {
                            Text("Mapa", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(mapStatus, color = Muted, style = MaterialTheme.typography.bodySmall)
                            if (worldMap.configured) OutlinedButton(onClick = { worldMap.reload() }, modifier = Modifier.fillMaxWidth()) {
                                Text("Tentar carregar mapa")
                            }
                        }
                        HorizontalDivider(color = Track)
                        Text("Localização", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        if (!simulatedView && state.paused) Text("Gravação pausada; as atualizações GPS do percurso estão suspensas.", color = Mint)
                        if (!simulatedView && !state.paused) {
                            Text(gpsSignal.label + (gpsSignal.accuracyMeters?.let { " • precisão estimada de ${it.toInt()} m" } ?: ""),
                                color = if (gpsSignal.quality == GpsQuality.GOOD) Mint else Color(0xFFFFD589))
                            Text("O círculo azul representa a incerteza informada pelo GPS, não a área revelada. Leituras acima de 20 m não revelam o mapa.",
                                color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(if (precise) "Localização precisa autorizada" else "Autorize a localização precisa para revelar o caminho", color = Muted,
                            style = MaterialTheme.typography.bodySmall)
                        if (state.active) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                                Text("Acompanhar minha posição", style = MaterialTheme.typography.bodyMedium)
                                Text("Mover o mapa pausa o acompanhamento", color = Muted, style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked = following, onCheckedChange = onFollowingChange)
                        }
                        if (!precise && !state.active && !simulatedView) Button(onClick = onLocationPermission, modifier = Modifier.fillMaxWidth()) {
                            Text("Permitir localização")
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onGpsSettings, modifier = Modifier.weight(1f)) { Text("Ajustar GPS") }
                            OutlinedButton(onClick = onAppSettings, modifier = Modifier.weight(1f)) { Text("Permissões") }
                        }
                        if (BuildConfig.DEBUG) {
                            HorizontalDivider(color = Track)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                                    Text("Trajeto simulado", style = MaterialTheme.typography.titleMedium)
                                    Text("Ruas de Curitiba • reprodução rápida • velocidades e paradas variadas", color = Muted, style = MaterialTheme.typography.bodySmall)
                                }
                                Switch(checked = simulatedView, onCheckedChange = onSimulationChange, enabled = !state.active)
                            }
                            if (state.active) Text("Encerre a exploração para trocar de modo.", color = Muted,
                                style = MaterialTheme.typography.bodySmall)
                            if (simulatedView) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SimulationScenario.entries.forEach { option ->
                                    FilterChip(selected = scenario == option, enabled = !state.active && !storageBusy,
                                        onClick = { onScenarioChange(option) }, label = { Text(option.label) })
                                }
                            }
                        }
                    }
                    Text("Prepare seu percurso antes de sair. Durante a atividade, o app funciona de forma passiva.", color = Muted,
                        style = MaterialTheme.typography.bodySmall)
                    Text("Expo World  ${BuildConfig.VERSION_NAME}", color = Muted.copy(alpha = .7f), style = MaterialTheme.typography.labelSmall)
                }
            }
            PrimaryNavigation(if(menuSection==1)1 else if(menuSection==2)3 else -1,navigate)
        }
    }
    if (summaryOpen && trace != null) AlertDialog(
        onDismissRequest = { summaryOpen = false },
        title = { Text(if (trace.completed) "Seu percurso" else "Percurso recuperado") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (simulatedView) "MODO SIMULADO" else "Viagem guardada automaticamente no histórico", color = Mint)
                TripInsightsPanel(app,trace,cityProgress)
                Text("Área nova: ${trace.newAreaSquareMeters?.let { areaLabel(it) } ?: "indisponível neste percurso antigo"}", fontWeight = FontWeight.Bold)
                summary?.let { data ->
                    Text("Distância: ${distanceLabel(data.distanceMeters)}")
                    Text("Tempo entre registros: ${durationLabel(data.recordedSeconds)}")
                    Text("Parado: ${durationLabel(data.stoppedSeconds)} • ${data.stops} paradas")
                    Text(if (data.missingGpsSeconds >= 1) "Registro com trechos separados: ${durationLabel(data.missingGpsSeconds)} sem registro por pausa ou sinal."
                        else "Registro sem lacunas entre as amostras salvas.")
                    if (data.partial) Text("Rastro parcial: somente as amostras mais recentes. A área nova considera toda a viagem.")
                } ?: Text("Calculando resumo…")
                if (!trace.completed) Text("Trecho recuperado após interrupção; últimas leituras em memória podem estar ausentes.")
                if(canSave) Button(onClick={routeTitle=java.text.SimpleDateFormat("dd/MM • HH:mm",Locale.forLanguageTag("pt-BR")).format(java.util.Date(trace.startedAtMillis));titleDialog=true;summaryOpen=false},modifier=Modifier.fillMaxWidth()){Text("Dar um nome ao trajeto")}
                if(!simulatedView) OutlinedButton(onClick={summaryOpen=false;cardOpen=true},enabled=!storageBusy,modifier=Modifier.fillMaxWidth()){Text("Compartilhar descobertas")}
                Text("Estimativas de GPS. As áreas novas podem ser destacadas em dourado no mapa.", color = Muted)
            }
        },
        confirmButton = { TextButton(onClick = { summaryOpen = false; onTraceFocus(); onHighlightChange(true) }) { Text("Ver no mapa") } },
        dismissButton = { TextButton(onClick = { summaryOpen = false }) { Text("Fechar") } }
    )
    if (cardOpen && trace != null) Dialog(onDismissRequest = { cardOpen = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(.94f).padding(12.dp), shape = RoundedCornerShape(24.dp), color = Ink, contentColor = Paper) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Seu cartão de expedição", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(value = cardTitle, onValueChange = onCardTitleChange, singleLine = true, label = { Text("Título (até 40 caracteres)") }, modifier = Modifier.fillMaxWidth())
                if (cardMap == null) Text(cardMapError.ifBlank { "Carregando mapa do percurso…" }, color = Muted)
                else Canvas(Modifier.fillMaxWidth().aspectRatio(ExpeditionArt.WIDTH.toFloat() / ExpeditionArt.HEIGHT)) {
                    ExpeditionRenderer.draw(drawContext.canvas.nativeCanvas, art, size.width, size.height, map = cardMap?.bitmap)
                }
                Text("Escolha imagem, animação ou vídeo. A prévia mostra a evolução do trajeto; o vídeo inclui seu marcador e, quando disponível, as novas descobertas. O mapa precisa estar carregado.", color = Muted, style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onCardExport, enabled = !storageBusy && cardMap != null, modifier = Modifier.weight(1f)) { Text("Imagem") }
                    OutlinedButton(onClick = onAnimationExport, enabled = !storageBusy && cardMap != null, modifier = Modifier.weight(1f)) { Text("GIF") }
                }
                OutlinedButton(onClick={previewPlaying=!previewPlaying},modifier=Modifier.fillMaxWidth()){Text(if(previewPlaying)"Pausar prévia" else "Ver prévia animada")}
                Button(onClick=onVideoExport,enabled=!storageBusy&&cardMap!=null,modifier=Modifier.fillMaxWidth()){Text("Salvar vídeo MP4")}
                if(storageBusy){
                    if(exportProgress>=0)LinearProgressIndicator(progress={exportProgress},modifier=Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(if(exportProgress>=0)"Preparando arquivo • ${(exportProgress*100).toInt()}%" else "Preparando mapa e arquivo…")
                    OutlinedButton(onClick=onCancelStorage){Text("Cancelar exportação")}
                }
                if (storageMessage.isNotEmpty()) Text(storageMessage, color = Mint)
                TextButton(onClick = { cardOpen = false }, modifier = Modifier.fillMaxWidth()) { Text("Fechar") }
            }
        }
    }
    if(expeditionSummaryOpen) AlertDialog(onDismissRequest={expeditionSummaryOpen=false},title={Text("Sua expedição")},
        text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text("${expeditionTraces.size} percursos no mesmo mapa")
            val total by produceState<Pair<Double,Double>?>(null,expeditionTraces){value=withContext(Dispatchers.Default){val summaries=expeditionTraces.map{TripSummary.from(it)};summaries.sumOf{it.distanceMeters} to summaries.sumOf{it.recordedSeconds}}}
            total?.let{Text("${distanceLabel(it.first)} • ${durationLabel(it.second)} de registros")}
            Text("Soma dos percursos: ruas repetidas podem aparecer em várias viagens. O progresso de exploração continua contando cada trecho uma vez.")
        }},confirmButton={TextButton(onClick={expeditionSummaryOpen=false}){Text("Ver mapa")}})
    if(gpsDialog) AlertDialog(onDismissRequest={gpsDialog=false},title={Text("Localização do percurso")},
        text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
            Text(gpsSignal.label+(gpsSignal.accuracyMeters?.let{" • precisão estimada de ${it.toInt()} m"} ?: ""))
            Text(status)
            Text("A névoa e o reconhecimento de ruas usam limites de precisão diferentes. Em locais fechados ou entre prédios, aguarde um sinal melhor.",style=MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick={gpsDialog=false;onAppSettings()},modifier=Modifier.fillMaxWidth()){Text("Revisar permissões")}
        }},confirmButton={TextButton(onClick={gpsDialog=false;onGpsSettings()}){Text("Ajustar GPS")}},dismissButton={TextButton(onClick={gpsDialog=false}){Text("Voltar ao mapa")}})
    if (confirmEnd && state.active) AlertDialog(
        onDismissRequest = { confirmEnd = false },
        title = { Text("Encerrar este percurso?") },
        text = { Text("O registro será finalizado e suas descobertas serão mantidas. Se pretende continuar depois, use Pausar.") },
        confirmButton = { TextButton(enabled = !storageBusy, onClick = {
            confirmEnd = false; menuOpen = false; onTripToggle()
        }) { Text("Sim, encerrar") } },
        dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text(if(state.paused)"Manter pausado" else "Continuar percurso") } }
    )
}

@Composable
private fun NeighborhoodPanel(progress: CityProgress, simulated: Boolean, hasPosition: Boolean, onFocus: (String) -> Unit, onPlan:(String)->Unit) {
    val uriHandler = LocalUriHandler.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    Surface(shape = RoundedCornerShape(20.dp), color = Tint, contentColor = Paper) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Curitiba • ruas exploradas", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Carro + caminhada + bicicleta • ${if (simulated) "SIMULADO" else "real"}", color = Mint)
            when {
                progress.error != null -> Text(progress.error.orEmpty(), color = Muted)
                progress.neighborhoods.isEmpty() -> Text("Carregando base offline de Curitiba…", color = Muted)
                else -> {
                    val fraction = if (progress.totalMeters > 0) progress.visitedMeters / progress.totalMeters else 0.0
                    Text(String.format(Locale.forLanguageTag("pt-BR"), "%.1f%% de Curitiba", fraction * 100),
                        style = MaterialTheme.typography.headlineSmall, color = Mint)
                    Text("${distanceLabel(progress.visitedMeters)} de ${distanceLabel(progress.totalMeters)} • estimativa", color = Mint)
                    val current = progress.neighborhoods.firstOrNull { it.id == progress.currentNeighborhoodId }
                    Text(current?.let { "Você está em ${it.name}" } ?: if (hasPosition) "Sua posição está fora dos bairros de Curitiba desta base."
                        else "Aguardando posição para identificar o bairro.", color = Muted, style = MaterialTheme.typography.bodySmall)
                    Text("Trechos únicos de aproximadamente 10 m. Repetições não somam; GPS impreciso pode deixar trechos sem crédito.",
                        color = Muted, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (expanded) "Mostrar resumo" else "Ver os ${progress.neighborhoods.size} bairros")
                    }
                    if (expanded) OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Buscar bairro") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    val summary = listOfNotNull(current) + progress.neighborhoods.filter { it.visitedMeters > 0 && it.id != current?.id }
                        .sortedByDescending { it.fraction }.take(4)
                    val rows = if (expanded) progress.neighborhoods.filter { it.name.contains(query, ignoreCase = true) }
                        else summary.ifEmpty { progress.neighborhoods.take(3) }
                    rows.forEach { row ->
                        Surface(onClick = { onFocus(row.id) }, color = Card, contentColor = Paper,
                            shape = RoundedCornerShape(12.dp)) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(row.name, modifier = Modifier.weight(1f).padding(end = 8.dp))
                                    Text(String.format(Locale.forLanguageTag("pt-BR"), "%.1f%%", row.fraction * 100), color = Mint)
                                }
                                LinearProgressIndicator(progress = { row.fraction.toFloat() }, color = Mint,
                                    trackColor = Track, modifier = Modifier.fillMaxWidth())
                                Text(if (row.totalMeters > 0) "${distanceLabel(row.visitedMeters)} / ${distanceLabel(row.totalMeters)} estimados • tocar para ver no mapa"
                                    else "Sem vias elegíveis nesta base", color = Muted, style = MaterialTheme.typography.bodySmall)
                                if(!simulated) TextButton(onClick={onPlan(row.id)}){Text("Explorar este bairro")}
                            }
                        }
                    }
                    if (expanded && rows.isEmpty()) Text("Nenhum bairro encontrado.", color = Muted)
                    Text("Limites: IBGE/Censo 2022. Ruas: © OpenStreetMap contributors, ODbL 1.0 • ${progress.sourceDate.substringBefore('T')}. Não indica rota segura ou acessibilidade.",
                        color = Muted, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { uriHandler.openUri("https://www.openstreetmap.org/copyright") }) { Text("Licença dos dados OpenStreetMap") }
                }
            }
        }
    }
}

private fun paceLabel(kmh: Double?): String {
    if (kmh == null || kmh <= .2) return "—"
    val seconds = (3600 / kmh).toInt()
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

private fun areaLabel(squareMeters: Double): String = if (squareMeters >= 10_000)
    String.format(Locale.forLanguageTag("pt-BR"), "%.2f km²", squareMeters / 1_000_000)
else "${NumberFormat.getIntegerInstance(Locale.forLanguageTag("pt-BR")).format(squareMeters.toLong())} m²"
private fun distanceLabel(meters: Double): String = if (meters >= 1000)
    String.format(Locale.forLanguageTag("pt-BR"), "%.2f km", meters / 1000) else "${meters.toInt()} m"
private fun durationLabel(seconds: Double): String {
    val total = seconds.toInt().coerceAtLeast(0)
    return if (total >= 3600) "${total / 3600} h ${(total % 3600) / 60} min"
    else if (total >= 60) "${total / 60} min ${total % 60} s" else "$total s"
}

@Composable
private fun InfoCard(value: String, label: String, modifier: Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(20.dp), color = Card, contentColor = Paper) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(value, fontSize = 25.sp, fontWeight = FontWeight.Bold, color = Mint)
            Text(label, color = Muted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ThemePicker(onSelect: (ExplorerTheme) -> Unit) {
    val selected = LocalExplorerTheme.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Temas", style = MaterialTheme.typography.titleMedium)
        Text("Atual: ${selected.label}${if (selected == ExplorerTheme.GREEN) " • padrão original" else ""}", color = Muted,
            style = MaterialTheme.typography.bodySmall)
        ExplorerTheme.entries.chunked(3).forEach { group ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                group.forEach { option ->
                    FilterChip(selected = selected == option, onClick = { onSelect(option) },
                        label = { Text(option.label, fontSize = 12.sp) },
                        leadingIcon = { Box(Modifier.size(10.dp).background(themeColor(option.carHighlight), CircleShape)) })
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(10.dp).background(Brush.horizontalGradient(
            listOf(themeColor(selected.traceDark), themeColor(selected.traceLight))), RoundedCornerShape(6.dp)))
        Text("Interface, ícones e rastro combinam com o tema. Paradas permanecem pretas.", color = Muted,
            style = MaterialTheme.typography.bodySmall)
    }
}
