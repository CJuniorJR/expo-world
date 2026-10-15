package br.com.expoworld.ui

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
import br.com.expoworld.storage.SavedRoute
import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val Ink = Color(0xFF101C22)
private val Mint = Color(0xFF70E1BA)
private val Muted = Color(0xFFAFBFC6)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExplorationScreen(
    worldMap: WorldMap,
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
    var summary by remember { mutableStateOf<TripSummary?>(null) }
    LaunchedEffect(trace) { summary = trace?.let { data -> withContext(Dispatchers.Default) { TripSummary.from(data) } } }
    val density = LocalDensity.current
    val topInset = WindowInsets.safeDrawing.getTop(density)
    val bottomInset = WindowInsets.safeDrawing.getBottom(density)
    LaunchedEffect(topInset, bottomInset) { worldMap.setInsets(topInset, bottomInset) }
    val activeGps = state.active && !state.simulated
    val waitingGps = activeGps && !state.message.startsWith("Explorando")
    val statusLabel = when {
        simulatedView -> "MODO SIMULADO"
        waitingGps -> when {
            state.message.startsWith("Movimento incompatível") -> "Confira a atividade escolhida"
            state.message.contains("GPS desligado") -> "GPS desligado"
            else -> "Aguardando GPS preciso"
        }
        activeGps && !online -> "GPS ativo • sem internet"
        activeGps -> "Explorando"
        mapStatus.startsWith("Aproxime") -> "Aproxime para ver a exploração"
        mapStatus.isNotEmpty() -> "Confira o mapa"
        trace != null && trace.samples.isNotEmpty() -> "Percurso concluído"
        !online -> "Sem internet"
        else -> "Pronto para explorar"
    }
    val statusColor = if (waitingGps || !online || mapStatus.isNotEmpty()) Color(0xFFFFD589) else Mint
    val actionLabel = if (state.active) "Encerrar exploração" else if (simulatedView) "Iniciar trajeto simulado" else when (state.activity) {
        ActivityKind.WALK -> "Iniciar caminhada"; ActivityKind.BIKE -> "Iniciar pedalada"; ActivityKind.CAR -> "Iniciar exploração"
    }
    val actionColors = ButtonDefaults.buttonColors(
        containerColor = if (state.active) Color(0xFFFFB4AB) else Mint,
        contentColor = Ink
    )

    Box(Modifier.fillMaxSize()) {
        // The map owns the entire window. All controls float over it.
        AndroidView(factory = { worldMap.view }, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxWidth().height(160.dp).align(Alignment.TopCenter)
            .background(Brush.verticalGradient(listOf(Ink.copy(alpha = .65f), Color.Transparent))))
        Box(Modifier.fillMaxWidth().height(180.dp).align(Alignment.BottomCenter)
            .background(Brush.verticalGradient(listOf(Color.Transparent, Ink.copy(alpha = .72f)))))

        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Surface(modifier = Modifier.weight(1f, fill = false).padding(end = 12.dp), color = Ink.copy(alpha = .92f), contentColor = Color.White,
                    shape = RoundedCornerShape(22.dp), shadowElevation = 6.dp) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(painterResource(R.drawable.ic_explore), null, tint = Mint, modifier = Modifier.size(24.dp))
                        Column {
                            Text("EXPO WORLD", fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("Cada caminho, uma descoberta", fontSize = 10.sp, color = Muted,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                FilledTonalIconButton(onClick = { menuOpen = true }, modifier = Modifier.size(56.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Ink, contentColor = Color.White)) {
                    Icon(painterResource(R.drawable.ic_menu), "Abrir menu e informações", modifier = Modifier.size(24.dp))
                }
            }

            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(onClick = { menuOpen = true }, color = Ink.copy(alpha = .94f), contentColor = statusColor,
                    shape = CircleShape) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(7.dp).background(statusColor, CircleShape))
                        Text(statusLabel, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = onTripToggle, colors = actionColors, shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.weight(1f).heightIn(min = 56.dp), contentPadding = PaddingValues(14.dp)) {
                        Icon(painterResource(if (state.active) R.drawable.ic_stop else R.drawable.ic_explore), null,
                            modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(actionLabel, fontWeight = FontWeight.SemiBold)
                    }
                    FilledTonalIconButton(onClick = onCenter, modifier = Modifier.size(56.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = if (following) Mint else Ink, contentColor = if (following) Ink else Mint)) {
                        Icon(painterResource(R.drawable.ic_center), if (following) "Acompanhando minha posição" else "Centralizar na minha posição", modifier = Modifier.size(24.dp))
                    }
                }
                // Attribution stays visible even with the information menu closed.
                Text("© MapTiler  ·  © OpenStreetMap contributors", color = Color(0xFFE0E7E9), fontSize = 10.sp)
            }
        }
    }

    if (menuOpen) ModalBottomSheet(
        onDismissRequest = { menuOpen = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Ink,
        contentColor = Color.White,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)
            .padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Sua exploração", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(if (simulatedView) "Modo simulado • dados separados" else "Novos caminhos, no seu ritmo", color = Muted,
                        style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { menuOpen = false }) {
                    Icon(painterResource(R.drawable.ic_close), "Fechar menu")
                }
            }
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
            NeighborhoodPanel(cityProgress, simulatedView, state.position != null && state.simulated == simulatedView) { id ->
                onNeighborhoodFocus(id); menuOpen = false
            }
            Surface(shape = RoundedCornerShape(20.dp), color = Color(0xFF1C2C33), contentColor = Color.White) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(statusLabel, color = statusColor, style = MaterialTheme.typography.labelLarge)
                    Text(status, style = MaterialTheme.typography.bodyMedium)
                    if (!online) Text("Sem internet: o mapa pode ficar sem ruas novas, mas o GPS e a gravação no aparelho continuam.",
                        color = Color(0xFFFFD589), style = MaterialTheme.typography.bodySmall)
                    if (notificationsHidden) Text("Notificação oculta: encerre pelo app ou pelo gerenciador de apps ativos do Android.",
                        color = Color(0xFFFFD589), style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                InfoCard(areaLabel(progress.areaSquareMeters), "área descoberta • estimativa", Modifier.weight(1f))
                InfoCard("50 m", "de cada lado do trajeto", Modifier.weight(1f))
            }
            Text("Suas descobertas ficam salvas neste aparelho e se acumulam entre viagens.", color = Muted,
                style = MaterialTheme.typography.bodySmall)
            Surface(shape = RoundedCornerShape(20.dp), color = Color(0xFF183A36), contentColor = Color.White) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("✦  ${progress.title}", color = Mint, style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(progress = { progress.progressToNext }, modifier = Modifier.fillMaxWidth(),
                        color = Mint, trackColor = Color(0xFF2E514D))
                    Text(progress.nextTarget?.let { "Próximo marco: ${areaLabel(it)} descobertos" }
                        ?: "Seu mapa continua crescendo a cada novo caminho.", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }

            HorizontalDivider(color = Color(0xFF2D414A))
            Text("Rastro por velocidade", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            trace?.let { Text("Percurso: ${it.activity.label}", color = Muted, style = MaterialTheme.typography.bodySmall) }
            savedRoutes.firstOrNull { it.id == selectedSavedId }?.let { Text(it.title, color = Mint) }
            when {
                trace == null || trace.samples.isEmpty() -> Text(if (state.active) "Aguardando a primeira posição precisa para desenhar o rastro ao vivo."
                    else "Inicie um percurso para ver o rastro ao vivo e consulte o resumo ao encerrar.", color = Muted,
                    style = MaterialTheme.typography.bodySmall)
                else -> {
                    if (state.active) Text("Rastro ao vivo. As cores e paradas se atualizam durante o percurso.", color = Mint,
                        style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(if (state.active) "Rastro visível ao vivo" else "Exibir percurso", modifier = Modifier.weight(1f))
                        Switch(checked = traceVisible || state.active, onCheckedChange = onTraceVisibleChange, enabled = !state.active)
                    }
                    val currentSummary = summary
                    if (currentSummary != null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        InfoCard(distanceLabel(currentSummary.distanceMeters), "distância registrada", Modifier.weight(1f))
                        InfoCard(durationLabel(currentSummary.recordedSeconds), "tempo de registros", Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        InfoCard(durationLabel(currentSummary.stoppedSeconds), "parado • estimativa", Modifier.weight(1f))
                        InfoCard(if (trace.activity == ActivityKind.WALK) paceLabel(currentSummary.averageKmh)
                            else currentSummary.averageKmh?.let { String.format(Locale.forLanguageTag("pt-BR"), "%.0f km/h", it) } ?: "—",
                            if (trace.activity == ActivityKind.WALK) "ritmo médio • min/km" else "velocidade média • GPS", Modifier.weight(1f))
                    }
                    if (currentSummary.missingGpsSeconds >= 1) Text("${durationLabel(currentSummary.missingGpsSeconds)} sem continuidade de sinal; lacunas não entram na distância.",
                        color = Muted, style = MaterialTheme.typography.bodySmall)
                    if (currentSummary.partial) Text("Resumo parcial: o percurso ultrapassou o limite de amostras e mostra a parte mais recente.",
                        color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    Box(Modifier.fillMaxWidth().height(12.dp).background(
                        Brush.horizontalGradient(listOf(Color.Black, Color(0xFF759FA0), Color(0xFFC9FFF1))), RoundedCornerShape(6.dp)))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("0 km/h", color = Muted, fontSize = 11.sp)
                        Text("${(trace.activity.brightKmh / 2).toInt()} km/h", color = Muted, fontSize = 11.sp)
                        Text("${trace.activity.brightKmh.toInt()}+ km/h", color = Muted, fontSize = 11.sp)
                    }
                    Text("Preto: parado (até ${trace.activity.stopKmh} km/h). Escuro: lento. Claro: mais rápido. Cinza: velocidade não estimada.", color = Muted,
                        style = MaterialTheme.typography.bodySmall)
                    val stoppedSeconds = currentSummary?.stoppedSeconds?.toInt() ?: 0
                    Text("${currentSummary?.stops ?: 0} paradas detectadas • ${stoppedSeconds / 60} min ${stoppedSeconds % 60} s",
                        color = Mint, style = MaterialTheme.typography.labelLarge)
                    Text("Paradas de pelo menos 5 segundos entram no resumo. O rastro fica preto, sem círculos. A velocidade não confirma congestionamento.",
                        color = Muted, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { onTraceVisibleChange(true); onTraceFocus(); menuOpen = false }, modifier = Modifier.fillMaxWidth()) {
                        Text("Enquadrar percurso")
                    }
                }
            }
            Text("Salve o percurso real antes de começar outro para mantê-lo na sua lista.", color = Muted,
                style = MaterialTheme.typography.bodySmall)
            if (!simulatedView) {
                if (canSave) Button(enabled = !saveBusy, onClick = { routeTitle = ""; titleDialog = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (saveBusy) "Salvando…" else "Salvar trajeto com título")
                }
                if (saveMessage.isNotEmpty()) Text(saveMessage, color = Mint)
                Text("Trajetos salvos", style = MaterialTheme.typography.titleMedium)
                if (savedRoutes.isEmpty()) Text("Seus trajetos reais salvos aparecerão aqui.", color = Muted)
                if (selectedSavedId != null) OutlinedButton(onClick = { onSavedRouteSelect(null) }) { Text("Voltar ao último percurso") }
                savedRoutes.forEach { saved ->
                    OutlinedButton(enabled = !state.active, onClick = { onSavedRouteSelect(saved.id); menuOpen = false }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(saved.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(ActivityKind.fromKey(saved.activity).label + " • " + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                                .format(java.util.Date(saved.startedAtMillis)), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            if (mapStatus.isNotEmpty()) {
                Text("Mapa", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(mapStatus, color = Muted, style = MaterialTheme.typography.bodySmall)
                if (worldMap.configured) OutlinedButton(onClick = { worldMap.reload() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Tentar carregar mapa")
                }
            }
            HorizontalDivider(color = Color(0xFF2D414A))
            Text("Localização", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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
                HorizontalDivider(color = Color(0xFF2D414A))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("Trajeto simulado", style = MaterialTheme.typography.titleMedium)
                        Text("Ruas de São Paulo • reprodução 8× • velocidades e paradas variadas", color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = simulatedView, onCheckedChange = onSimulationChange, enabled = !state.active)
                }
                if (state.active) Text("Encerre a exploração para trocar de modo.", color = Muted,
                    style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = { onTripToggle(); menuOpen = false }, colors = actionColors, shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(actionLabel, fontWeight = FontWeight.SemiBold) }
            Text("Prepare seu percurso antes de sair. Durante a atividade, o app funciona de forma passiva.", color = Muted,
                style = MaterialTheme.typography.bodySmall)
            Text("Expo World  ${BuildConfig.VERSION_NAME}", color = Muted.copy(alpha = .7f), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun NeighborhoodPanel(progress: CityProgress, simulated: Boolean, hasPosition: Boolean, onFocus: (String) -> Unit) {
    val uriHandler = LocalUriHandler.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    Surface(shape = RoundedCornerShape(20.dp), color = Color(0xFF183A36), contentColor = Color.White) {
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
                        Surface(onClick = { onFocus(row.id) }, color = Color(0xFF1C2C33), contentColor = Color.White,
                            shape = RoundedCornerShape(12.dp)) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(row.name, modifier = Modifier.weight(1f).padding(end = 8.dp))
                                    Text(String.format(Locale.forLanguageTag("pt-BR"), "%.1f%%", row.fraction * 100), color = Mint)
                                }
                                LinearProgressIndicator(progress = { row.fraction.toFloat() }, color = Mint,
                                    trackColor = Color(0xFF2E514D), modifier = Modifier.fillMaxWidth())
                                Text(if (row.totalMeters > 0) "${distanceLabel(row.visitedMeters)} / ${distanceLabel(row.totalMeters)} estimados • tocar para ver no mapa"
                                    else "Sem vias elegíveis nesta base", color = Muted, style = MaterialTheme.typography.bodySmall)
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
    Surface(modifier = modifier, shape = RoundedCornerShape(20.dp), color = Color(0xFF1C2C33), contentColor = Color.White) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(value, fontSize = 25.sp, fontWeight = FontWeight.Bold, color = Mint)
            Text(label, color = Muted, style = MaterialTheme.typography.labelSmall)
        }
    }
}
