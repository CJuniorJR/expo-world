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

fun PocketPlannerDialog(app:WorldApp,worldMap:WorldMap,tracking:TrackingState,position:Fix?,

    chosenStart:PocketPoint?,onUsePosition:()->Unit,onPickStart:()->Unit,

    onStart:(PocketPlan)->Unit,onLocation:()->Unit,onDismiss:()->Unit,

    chosenDestination:PocketPoint?=null,onPickDestination:()->Unit={},onClearDestination:()->Unit={},

    openLibrary:Boolean=false,initialNeighborhoodId:String?=null) {

    val controller=remember { app.pocket }

    val state by controller.state.collectAsStateWithLifecycle()

    val saved by controller.store.saved.collectAsStateWithLifecycle(emptyList())

    val offline by controller.offline.state.collectAsStateWithLifecycle()

    val scope=rememberCoroutineScope()

    val neighborhoods by produceState<List<Pair<String,String>>>(emptyList()) { value=app.city.planningNetwork()?.neighborhoods.orEmpty().map{it.id to it.name}.sortedBy{it.second} }

    var page by rememberSaveable { mutableStateOf(if(controller.resumeConfigure) "configure" else if(openLibrary) "library" else if(state.plan!=null && initialNeighborhoodId==null && !controller.resumeConfigure) "preview" else "configure") }

    var minutes by rememberSaveable { mutableStateOf(controller.draft.minutes) }

    var extraMinutes by rememberSaveable { mutableStateOf(controller.draft.extraMinutes) }

    var toDestination by rememberSaveable { mutableStateOf(chosenDestination!=null || controller.draft.toDestination) }

    var activityKey by rememberSaveable { mutableStateOf(controller.draft.activityKey ?: tracking.activity.key) }

    var purposeKey by rememberSaveable { mutableStateOf(controller.draft.purposeKey) }

    var neighborhoodId by rememberSaveable { mutableStateOf(initialNeighborhoodId ?: controller.draft.neighborhoodId) }

    var neighborhoodSearch by rememberSaveable { mutableStateOf("") }

    var neighborhoodExpanded by remember { mutableStateOf(false) }

    var loop by rememberSaveable { mutableStateOf(controller.draft.loop) }

    var variation by rememberSaveable { mutableIntStateOf(0) }

    var saveTitle by rememberSaveable { mutableStateOf("Minha volta") }

    var saveDialog by remember { mutableStateOf(false) }

    var localMessage by remember { mutableStateOf("") }

    var saving by remember { mutableStateOf(false) }

    var instructions by rememberSaveable { mutableStateOf(false) }

    var loadedDestination by remember { mutableStateOf<PocketPoint?>(null) }

    val destination=chosenDestination ?: loadedDestination

    DisposableEffect(Unit) {controller.resumeConfigure=false;onDispose {

        controller.draft=br.com.expoworld.storage.PocketDraft(minutes,extraMinutes,activityKey,purposeKey,loop,toDestination,neighborhoodId)

    }}

    val origin=chosenStart ?: position?.let{PocketPoint(it.latitude,it.longitude)}

    val disabled=state.busy || tracking.active || saving || app.storageBusy

    var observedPlanId by remember {mutableStateOf(state.plan?.id)}

    LaunchedEffect(state.plan?.id) {if(state.plan?.id!=observedPlanId){state.plan?.let{page="preview";worldMap.focusPocket(it)};observedPlanId=state.plan?.id}}

    fun generate() {

        val start=origin ?: return

        val duration=if(toDestination)30 else minutes.toIntOrNull() ?: return

        variation++

        val request=PocketRequest(start,duration,ActivityKind.fromKey(activityKey),PocketPurpose.valueOf(purposeKey),

            loop && !toDestination,variation,if(toDestination)destination else null,if(toDestination)extraMinutes.toIntOrNull() ?: 0 else 0,neighborhoodId)

        controller.generate(scope,request);localMessage=""

    }

    if(saveDialog) AlertDialog(onDismissRequest={if(!saving)saveDialog=false},title={Text("Salvar roteiro")},

        text={OutlinedTextField(value=saveTitle,onValueChange={saveTitle=it.take(80)},label={Text("Nome do roteiro")},singleLine=true)},

        confirmButton={TextButton(enabled=saveTitle.isNotBlank() && !saving,onClick={val plan=state.plan ?: return@TextButton;saving=true;scope.launch{

            try{controller.store.save(saveTitle,plan);localMessage="Roteiro salvo. Encontre-o em Meus roteiros.";saveDialog=false}

            catch(e:kotlinx.coroutines.CancellationException){throw e}catch(_:Exception){localMessage="Não consegui salvar. Confira o espaço livre."}finally{saving=false}

        }}){Text(if(saving)"Salvando…" else "Salvar")}},dismissButton={TextButton(enabled=!saving,onClick={saveDialog=false}){Text("Cancelar")}})

    Dialog(onDismissRequest={onDismiss()},properties=DialogProperties(usePlatformDefaultWidth=false)) {

        Surface(Modifier.fillMaxWidth().fillMaxHeight(.96f).padding(10.dp),shape=androidx.compose.foundation.shape.RoundedCornerShape(22.dp)) {

            Column(Modifier.fillMaxWidth().padding(16.dp)) {

                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {

                    Text("Roteiros",style=MaterialTheme.typography.headlineSmall)

                    TextButton(onClick=onDismiss){Text("Fechar")}

                }

                ScrollableTabRow(selectedTabIndex=listOf("configure","preview","library").indexOf(page).coerceAtLeast(0),edgePadding=0.dp) {

                    listOf("configure" to "Planejar","preview" to "Prévia","library" to "Meus roteiros").forEach{(key,label)->

                        Tab(selected=page==key,onClick={page=key},text={Text(label)})

                    }

                }

                Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(top=12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {

                    if(state.busy){LinearProgressIndicator(Modifier.fillMaxWidth());Text(state.message);OutlinedButton(onClick=controller::cancel){Text("Cancelar busca")}}

                    else if(state.message.isNotBlank()) Text(state.message,style=MaterialTheme.typography.bodySmall)

                    if(localMessage.isNotBlank())Text(localMessage)

                    when(page) {

                        "configure" -> {

                            Text("1. Como será sua saída?",style=MaterialTheme.typography.titleMedium)

                            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {

                                ActivityKind.entries.forEach{option->FilterChip(selected=activityKey==option.key,enabled=!disabled,onClick={activityKey=option.key},label={Text(option.label)})}

                            }

                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {

                                FilterChip(selected=!toDestination,enabled=!disabled,onClick={toDestination=false;loadedDestination=null;onClearDestination()},label={Text("Dar uma volta")})

                                FilterChip(selected=toDestination,enabled=!disabled,onClick={toDestination=true},label={Text("Ir a um lugar")})

                            }

                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {PocketPurpose.entries.forEach{option->FilterChip(selected=purposeKey==option.name,enabled=!disabled,onClick={purposeKey=option.name},label={Text(option.label)})}}

                            Text(if(purposeKey==PocketPurpose.EXPLORE.name)"Prioriza ruas novas. Só o percurso real concede descobertas." else "Prefere continuidade e menos curvas. Passear também revela ruas novas.",style=MaterialTheme.typography.bodySmall)

                            if(toDestination) {

                                Text("2. Destino e tempo extra",style=MaterialTheme.typography.titleMedium)

                                Text(destination?.let{String.format(Locale.ROOT,"Destino no mapa: %.5f, %.5f",it.latitude,it.longitude)} ?: "Escolha onde quer terminar.",style=MaterialTheme.typography.bodySmall)

                                OutlinedButton(enabled=!disabled,onClick={controller.resumeConfigure=true;onPickDestination()},modifier=Modifier.fillMaxWidth()){Text("Escolher destino no mapa")}

                                OutlinedTextField(value=extraMinutes,onValueChange={extraMinutes=it.filter(Char::isDigit).take(2)},enabled=!disabled,label={Text("Até quantos minutos extras? (0 a 60)")},singleLine=true,modifier=Modifier.fillMaxWidth(),keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(keyboardType=androidx.compose.ui.text.input.KeyboardType.Number))

                                Text("Comparamos com o caminho direto. O desvio nunca ultrapassa esse tempo extra estimado; a prévia mostra o total.",style=MaterialTheme.typography.bodySmall)

                            } else {

                                Text("2. Quanto tempo você tem?",style=MaterialTheme.typography.titleMedium)

                                Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {listOf(15,30,45,60).forEach{value->FilterChip(selected=minutes==value.toString(),enabled=!disabled,onClick={minutes=value.toString()},label={Text("$value min")})}}

                                OutlinedTextField(value=minutes,onValueChange={minutes=it.filter(Char::isDigit).take(3)},enabled=!disabled,label={Text("Personalizado: 10 a 180 minutos")},singleLine=true,modifier=Modifier.fillMaxWidth(),keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(keyboardType=androidx.compose.ui.text.input.KeyboardType.Number))

                                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Text("Voltar ao início",modifier=Modifier.weight(1f));Switch(checked=loop,enabled=!disabled,onCheckedChange={loop=it})}

                                Text(if(loop)"O tempo já inclui a ida e a volta." else "A volta não entra nesse tempo; você termina em outro lugar.",style=MaterialTheme.typography.bodySmall)

                            }

                            Text("3. Partida",style=MaterialTheme.typography.titleMedium)

                            Text(origin?.let{String.format(Locale.ROOT,"%s: %.5f, %.5f",if(chosenStart!=null)"Escolhida no mapa" else "Sua posição",it.latitude,it.longitude)} ?: "Use o GPS ou escolha um ponto no mapa.",style=MaterialTheme.typography.bodySmall)

                            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {

                                OutlinedButton(enabled=!disabled,onClick={onUsePosition();if(position==null)onLocation()}){Text("Minha posição")}

                                OutlinedButton(enabled=!disabled,onClick={controller.resumeConfigure=true;onPickStart()}){Text("No mapa")}

                            }

                            Text("Focar um bairro de Curitiba (opcional)",style=MaterialTheme.typography.titleSmall)

                            OutlinedButton(enabled=!disabled,onClick={neighborhoodExpanded=!neighborhoodExpanded},modifier=Modifier.fillMaxWidth()){Text(neighborhoods.firstOrNull{it.first==neighborhoodId}?.second ?: "Sem bairro específico")}

                            if(neighborhoodExpanded) {

                                OutlinedTextField(value=neighborhoodSearch,onValueChange={neighborhoodSearch=it},label={Text("Buscar entre os 75 bairros")},modifier=Modifier.fillMaxWidth(),singleLine=true)

                                TextButton(onClick={neighborhoodId=null;neighborhoodExpanded=false}){Text("Sem bairro específico")}

                                Column(Modifier.heightIn(max=220.dp).verticalScroll(rememberScrollState())) {

                                    neighborhoods.filter{it.second.contains(neighborhoodSearch,true)}.forEach{(id,name)->TextButton(onClick={neighborhoodId=id;neighborhoodExpanded=false},modifier=Modifier.fillMaxWidth()){Text(name)}}

                                }

                            }

                            if(neighborhoodId!=null)Text("Buscaremos pelo menos metade da distância dentro desse bairro. Partir longe pode impedir uma opção dentro do tempo. A estimativa não garante avanço real.",style=MaterialTheme.typography.bodySmall)

                            Text("Partida, destino e pontos sugeridos são enviados ao Valhalla/FOSSGIS. Seu histórico e seus créditos de exploração ficam no aparelho.",style=MaterialTheme.typography.bodySmall)

                            Button(enabled=!disabled && origin!=null && (toDestination || minutes.toIntOrNull() in 10..180) && (!toDestination || (destination!=null && extraMinutes.toIntOrNull() in 0..60)),onClick=::generate,modifier=Modifier.fillMaxWidth()){Text("Gerar e ver prévia")}

                        }

                        "preview" -> {

                            val plan=state.plan

                            if(plan==null){Text("Seu próximo passeio começa em Planejar.");Button(onClick={page="configure"}){Text("Planejar uma saída")}}

                            else {

                                Text(if(plan.returning)"Sua volta ao início" else "Seu roteiro",style=MaterialTheme.typography.titleLarge)

                                Text("${plan.request.activity.label} • ${plan.request.purpose.label}")

                                Text(String.format(Locale.forLanguageTag("pt-BR"),"%.1f km • cerca de %d min",plan.path.distanceMeters/1000,kotlin.math.ceil(plan.path.seconds/60).toInt()),style=MaterialTheme.typography.headlineSmall)

                                plan.baselineSeconds?.let{base->Text("Caminho direto: ${kotlin.math.ceil(base/60).toInt()} min. Tempo extra desta opção: ${kotlin.math.ceil((plan.path.seconds-base).coerceAtLeast(0.0)/60).toInt()} min (limite ${plan.request.detourMinutes} min).",style=MaterialTheme.typography.bodySmall)}

                                Text(if(plan.request.destination!=null && !plan.returning)"Termina no destino escolhido." else if(plan.returning || plan.request.returnToStart)"Retorno ao ponto de partida incluído." else "Termina em outro lugar; retorno não incluído.",style=MaterialTheme.typography.bodySmall)

                                Text(plan.estimatedNewMeters?.let{String.format(Locale.forLanguageTag("pt-BR"),"Até %.2f km de ruas novas estimadas.",it/1000)} ?: "Estimativa de ruas novas indisponível para esta região.")

                                plan.request.neighborhoodId?.let{id->

                                    Text("Foco: ${neighborhoods.firstOrNull{it.first==id}?.second ?: "bairro escolhido"}")

                                    val fresh=plan.neighborhoodFreshMeters;val total=plan.neighborhoodTotalMeters
                                    if(fresh!=null && total!=null && total>0)Text(String.format(Locale.forLanguageTag("pt-BR"),"Até %.2f km novos no bairro, cerca de +%.2f pontos percentuais. Estimativa; GPS e vias disponíveis podem reduzir o avanço.",fresh/1000,fresh/total*100),style=MaterialTheme.typography.bodySmall)

                                }

                                Text("Tempo estimado, sujeito ao seu ritmo, paradas e condições atuais das vias.",style=MaterialTheme.typography.bodySmall)

                                OutlinedButton(onClick={worldMap.focusPocket(plan);onDismiss()},modifier=Modifier.fillMaxWidth()){Text("Ver percurso no mapa")}

                                val near=position?.let{PocketPoint(it.latitude,it.longitude).distance(plan.path.points.first())<=150}==true

                                Button(enabled=!tracking.active && !state.busy && !app.storageBusy && near,onClick={onStart(plan);onDismiss()},modifier=Modifier.fillMaxWidth()){Text("Começar este percurso")}

                                if(!near && !tracking.active)Text("Para começar, use GPS recente e fique a até 150 m da partida.",style=MaterialTheme.typography.bodySmall)

                                OutlinedButton(enabled=!disabled,onClick={

                                    // Reuse exactly the preview's settings, independent of stale form fields.

                                    controller.generate(scope,plan.request.copy(variation=plan.request.variation+1));localMessage=""

                                },modifier=Modifier.fillMaxWidth()){Text("Outra opção")}

                                OutlinedButton(enabled=!state.busy && !saving,onClick={saveDialog=true},modifier=Modifier.fillMaxWidth()){Text("Salvar roteiro")}

                                OutlinedButton(enabled=!offline.busy,onClick={controller.offline.prepare(plan,saveTitle)},modifier=Modifier.fillMaxWidth()){Text(if(controller.offline.mapsAllowed)"Preparar mapa e roteiro offline" else "Guardar roteiro e orientações offline")}

                                Text(controller.offline.policyMessage,style=MaterialTheme.typography.bodySmall)

                                if(offline.message.isNotBlank())Text(offline.message,style=MaterialTheme.typography.bodySmall)

                                if(offline.busy)OutlinedButton(onClick=controller.offline::cancel){Text("Cancelar download")}

                                TextButton(onClick={page="configure";minutes=plan.request.minutes.toString();activityKey=plan.request.activity.key;purposeKey=plan.request.purpose.name;loop=plan.request.returnToStart;neighborhoodId=plan.request.neighborhoodId;toDestination=plan.request.destination!=null;loadedDestination=plan.request.destination;extraMinutes=plan.request.detourMinutes.toString()}){Text("Alterar planejamento")}

                                OutlinedButton(enabled=position!=null && !state.busy,onClick={position?.let{controller.returnNow(scope,PocketPoint(it.latitude,it.longitude))}},modifier=Modifier.fillMaxWidth()){Text("Voltar ao início agora")}

                                TextButton(onClick={instructions=!instructions}){Text(if(instructions)"Ocultar orientações" else "Ver orientações")}

                                if(instructions)plan.path.steps.take(200).forEach{Text(it.instruction,style=MaterialTheme.typography.bodySmall)}

                                if(!tracking.active)TextButton(onClick={controller.clear();page="configure"}){Text("Tirar roteiro do mapa")}

                            }

                        }

                        "library" -> {

                            Text("Seus roteiros planejados",style=MaterialTheme.typography.titleMedium)

                            Button(enabled=!disabled,onClick={page="configure"},modifier=Modifier.fillMaxWidth()){Text("Planejar uma nova saída")}

                            if(saved.isEmpty())Text("Salve uma prévia para repetir ou adaptar depois. As viagens realizadas ficam no Histórico.")

                            saved.forEach{item->

                                ElevatedCard(Modifier.fillMaxWidth()) {Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){

                                    Text(item.title,style=MaterialTheme.typography.titleMedium)

                                    Text("${item.plan.request.activity.label} • ${kotlin.math.ceil(item.plan.path.seconds/60).toInt()} min • ${String.format(Locale.forLanguageTag("pt-BR"),"%.1f km",item.plan.path.distanceMeters/1000)}")

                                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){

                                        OutlinedButton(enabled=!disabled,onClick={saving=true;scope.launch{try{controller.reuse(item.plan);worldMap.focusPocket(controller.state.value.plan ?: item.plan);saveTitle=item.title;page="preview"}catch(e:kotlinx.coroutines.CancellationException){throw e}catch(_:Exception){localMessage="Não consegui abrir esse roteiro."}finally{saving=false}}}){Text("Abrir prévia")}

                                        TextButton(enabled=!disabled,onClick={scope.launch{try{controller.store.delete(item.id)}catch(e:kotlinx.coroutines.CancellationException){throw e}catch(_:Exception){localMessage="Não consegui excluir."}}}){Text("Excluir")}

                                    }

                                }}

                            }

                            HorizontalDivider();Text("Passeios preparados offline",style=MaterialTheme.typography.titleMedium)

                            Text(controller.offline.policyMessage,style=MaterialTheme.typography.bodySmall)

                            OutlinedButton(enabled=!offline.busy,onClick=controller.offline::refresh){Text("Verificar downloads")}

                            if(offline.message.isNotBlank())Text(offline.message,style=MaterialTheme.typography.bodySmall)

                            offline.journeys.forEach{item->

                                ElevatedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){

                                    Text(item.title,style=MaterialTheme.typography.titleMedium)

                                    Text("Roteiro e orientações no aparelho. ${if(item.mapComplete)"Mapa preparado (zoom 10–16)." else "Mapa não preparado."}")

                                    Text(item.message,style=MaterialTheme.typography.bodySmall)

                                    if(item.required>0 && !item.mapComplete)Text("${item.completed} de ${item.required} recursos • ${item.bytes/1024} KB",style=MaterialTheme.typography.bodySmall)

                                    else if(item.completed>0)Text("${item.completed} recursos • ${item.bytes/1024} KB",style=MaterialTheme.typography.bodySmall)

                                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){

                                        OutlinedButton(enabled=!disabled,onClick={saving=true;scope.launch{try{controller.reuse(item.plan);worldMap.focusPocket(controller.state.value.plan ?: item.plan);page="preview"}catch(e:kotlinx.coroutines.CancellationException){throw e}catch(_:Exception){localMessage="Não consegui abrir o roteiro local."}finally{saving=false}}}){Text("Abrir roteiro")}

                                        TextButton(enabled=!offline.busy,onClick={controller.offline.delete(item.id)}){Text("Remover pacote")}

                                    }

                                }}

                            }

                            if(offline.busy)OutlinedButton(onClick=controller.offline::cancel){Text("Cancelar download")}

                        }

                    }

                    Text("Valhalla/FOSSGIS • © OpenStreetMap contributors. Orientações em texto; sem voz ou recálculo automático.",style=MaterialTheme.typography.bodySmall)

                }

            }

        }

    }

}
