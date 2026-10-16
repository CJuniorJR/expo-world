package br.com.expoworld.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.expoworld.WorldApp
import br.com.expoworld.TrackingState
import br.com.expoworld.core.*
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun PocketPlannerDialog(app: WorldApp, worldMap: WorldMap, tracking: TrackingState, position: Fix?,
    chosenStart: PocketPoint?, onUsePosition: () -> Unit, onPickStart: () -> Unit,
    onStart: (PocketPlan) -> Unit, onLocation: () -> Unit, onDismiss: () -> Unit) {
    val controller = remember { app.pocket }
    val state by controller.state.collectAsStateWithLifecycle()
    val saved by controller.store.saved.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var minutes by rememberSaveable { mutableStateOf("30") }
    var activityKey by rememberSaveable { mutableStateOf(tracking.activity.key) }
    var purposeKey by rememberSaveable { mutableStateOf(PocketPurpose.EXPLORE.name) }
    var loop by rememberSaveable { mutableStateOf(true) }
    var variation by rememberSaveable { mutableIntStateOf(0) }
    var title by rememberSaveable { mutableStateOf("Minha volta") }
    var savedMessage by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var instructions by rememberSaveable { mutableStateOf(false) }
    val origin = chosenStart ?: position?.let { PocketPoint(it.latitude,it.longitude) }
    val disabled = state.busy || tracking.active || saving || app.storageBusy
    Dialog(onDismissRequest = { if (!state.busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(.96f).padding(10.dp),shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Exploração de bolso",style=MaterialTheme.typography.headlineSmall)
                Text("Planeje uma volta no seu tempo. Explorar e Passear revelam ruas novas quando você realmente as percorre.",style=MaterialTheme.typography.bodySmall)
                Text("Tempo disponível",style=MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    listOf(15,30,45,60).forEach { value -> FilterChip(selected=minutes==value.toString(),enabled=!disabled,onClick={minutes=value.toString()},label={Text("$value min")}) }
                }
                OutlinedTextField(value=minutes,onValueChange={minutes=it.filter(Char::isDigit).take(3)},enabled=!disabled,
                    label={Text("Personalizado: 10 a 180 minutos")},singleLine=true,modifier=Modifier.fillMaxWidth(),
                    keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(keyboardType=androidx.compose.ui.text.input.KeyboardType.Number))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    ActivityKind.entries.forEach { option -> FilterChip(selected=activityKey==option.key,enabled=!disabled,onClick={activityKey=option.key},label={Text(option.label)}) }
                }
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    PocketPurpose.entries.forEach { option -> FilterChip(selected=purposeKey==option.name,enabled=!disabled,onClick={purposeKey=option.name},label={Text(option.label)}) }
                }
                Text(if(purposeKey==PocketPurpose.EXPLORE.name) "Prioriza trechos novos entre as opções encontradas. Em Curitiba usa suas ruas descobertas."
                    else "Busca uma volta contínua com menos mudanças de direção. O roteador favorece menos subidas a pé/bike e reduz preferência por rodovias no carro.",style=MaterialTheme.typography.bodySmall)
                Text("Ponto de partida",style=MaterialTheme.typography.titleMedium)
                Text(origin?.let { String.format(Locale.ROOT,"%s: %.5f, %.5f",if(chosenStart!=null) "Escolhido no mapa" else "Sua posição",it.latitude,it.longitude) } ?: "Aguardando localização ou escolha no mapa.",style=MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled=!disabled,onClick={onUsePosition();if(position==null)onLocation()}) {Text("Minha posição")}
                    OutlinedButton(enabled=!disabled,onClick=onPickStart) {Text("Escolher no mapa")}
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Voltar ao início",modifier=Modifier.weight(1f));Switch(checked=loop,enabled=!disabled,onCheckedChange={loop=it})
                }
                Text(if(loop) "O tempo escolhido inclui a ida e a volta." else "O percurso termina em outro lugar. A volta fica fora desse tempo.",style=MaterialTheme.typography.bodySmall)
                Text("Planejamento online: partida e pontos sugeridos são enviados ao Valhalla/FOSSGIS para calcular vias. O histórico de descobertas fica no aparelho.",style=MaterialTheme.typography.bodySmall)
                Button(enabled=!disabled && origin!=null && minutes.toIntOrNull() in 10..180,onClick={
                    val start=origin ?: return@Button
                    variation++
                    controller.generate(scope,PocketRequest(start,minutes.toInt(),ActivityKind.fromKey(activityKey),PocketPurpose.valueOf(purposeKey),loop,variation))
                    savedMessage=""
                },modifier=Modifier.fillMaxWidth()) {Text(if(state.plan==null) "Gerar percurso" else "Gerar outra opção")}
                if(state.busy) { LinearProgressIndicator(Modifier.fillMaxWidth());OutlinedButton(onClick={controller.cancel()}){Text("Cancelar busca")} }
                if(state.message.isNotEmpty()) Text(state.message,style=MaterialTheme.typography.bodySmall)
                state.plan?.let { plan ->
                    HorizontalDivider()
                    Text(if(plan.returning) "Volta ao início" else "Seu roteiro planejado",style=MaterialTheme.typography.titleMedium)
                    Text("${plan.request.activity.label} • ${plan.request.purpose.label} • ${if(plan.returning || plan.request.returnToStart) "com retorno" else "só ida"}")
                    Text(String.format(Locale.forLanguageTag("pt-BR"),"%.1f km • cerca de %d min",plan.path.distanceMeters/1000,kotlin.math.ceil(plan.path.seconds/60).toInt()))
                    Text(if(plan.returning) "Estimativa do retorno da sua posição atual à partida original." else "Tempo estimado: pode variar com seu ritmo, paradas e condições das vias. Este roteiro foi gerado para ${plan.request.minutes} min.",style=MaterialTheme.typography.bodySmall)
                    Text(String.format(Locale.ROOT,"Partida original: %.5f, %.5f",plan.request.start.latitude,plan.request.start.longitude),style=MaterialTheme.typography.bodySmall)
                    Text(plan.estimatedNewMeters?.let { String.format(Locale.forLanguageTag("pt-BR"),"Até %.2f km de trechos novos estimados na base de Curitiba.",it/1000) }
                        ?: "Estimativa de descoberta indisponível para este trecho.",style=MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick={worldMap.focusPocket(plan);onDismiss()},modifier=Modifier.fillMaxWidth()){Text("Ver no mapa")}
                    val nearStart=position?.let{PocketPoint(it.latitude,it.longitude).distance(plan.path.points.first())<=150}==true
                    Button(enabled=!tracking.active && !state.busy && !app.storageBusy && nearStart,onClick={onStart(plan);onDismiss()},modifier=Modifier.fillMaxWidth()){Text("Iniciar este percurso")}
                    if(!nearStart && !tracking.active) Text("Para iniciar, obtenha GPS e fique a até 150 m da partida, ou gere um roteiro da sua posição.",style=MaterialTheme.typography.bodySmall)
                    OutlinedButton(enabled=position!=null && !state.busy,onClick={position?.let{controller.returnNow(scope,PocketPoint(it.latitude,it.longitude))}},modifier=Modifier.fillMaxWidth()){Text("Voltar ao início agora")}
                    OutlinedTextField(value=title,onValueChange={title=it.take(80)},label={Text("Título do roteiro")},singleLine=true,enabled=!saving,modifier=Modifier.fillMaxWidth())
                    OutlinedButton(enabled=title.isNotBlank() && !saving && !state.busy,onClick={
                        saving=true;scope.launch {try{controller.store.save(title,plan);savedMessage="Roteiro salvo para reutilizar."}catch(e: kotlinx.coroutines.CancellationException){throw e}catch(_:Exception){savedMessage="Não consegui salvar. Confira o espaço livre."}finally{saving=false}}
                    },modifier=Modifier.fillMaxWidth()){Text(if(saving)"Salvando…" else "Salvar roteiro planejado")}
                    if(savedMessage.isNotEmpty())Text(savedMessage)
                    TextButton(onClick={instructions=!instructions}){Text(if(instructions)"Ocultar orientações" else "Orientações do percurso")}
                    if(instructions)plan.path.steps.forEach{Text(it.instruction,style=MaterialTheme.typography.bodySmall)}
                    if(!tracking.active)TextButton(onClick={controller.clear()}){Text("Tirar roteiro do mapa")}
                }
                HorizontalDivider()
                Text("Roteiros planejados salvos",style=MaterialTheme.typography.titleMedium)
                if(saved.isEmpty())Text("Os roteiros que você salvar aparecerão aqui. Os trajetos realizados continuam na seção própria.",style=MaterialTheme.typography.bodySmall)
                saved.forEach { item ->
                    Text(item.title,style=MaterialTheme.typography.titleSmall)
                    Text(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(java.util.Date(item.createdAtMillis)),style=MaterialTheme.typography.bodySmall)
                    Text("${item.plan.request.activity.label} • ${item.plan.request.purpose.label} • ${kotlin.math.ceil(item.plan.path.seconds/60).toInt()} min",style=MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled=!disabled,onClick={saving=true;scope.launch{try{controller.reuse(item.plan);worldMap.focusPocket(controller.state.value.plan ?: item.plan);title=item.title;minutes=item.plan.request.minutes.toString();activityKey=item.plan.request.activity.key;purposeKey=item.plan.request.purpose.name;loop=item.plan.request.returnToStart}catch(e:kotlinx.coroutines.CancellationException){throw e}catch(_:Exception){savedMessage="Não consegui abrir esse roteiro."}finally{saving=false}}}){Text("Abrir roteiro")}
                        TextButton(enabled=!disabled,onClick={scope.launch{try{controller.store.delete(item.id)}catch(e:kotlinx.coroutines.CancellationException){throw e}catch(_:Exception){savedMessage="Não consegui excluir esse roteiro."}}}){Text("Excluir")}
                    }
                }
                Text("Roteamento Valhalla/FOSSGIS • © OpenStreetMap contributors. Orientações em texto; sem voz ou recálculo automático.",style=MaterialTheme.typography.bodySmall)
                TextButton(enabled=!state.busy,onClick=onDismiss,modifier=Modifier.fillMaxWidth()){Text("Fechar")}
            }
        }
    }
}
