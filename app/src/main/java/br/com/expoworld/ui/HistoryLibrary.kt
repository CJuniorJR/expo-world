package br.com.expoworld.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.expoworld.WorldApp
import br.com.expoworld.core.*
import br.com.expoworld.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.Locale

private fun kilometers(m: Double) = String.format(Locale.forLanguageTag("pt-BR"),"%.2f km",m/1000)
private fun dateLabel(time:Long) = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(java.util.Date(time))

@Composable
fun TripInsightsPanel(app: WorldApp, trace: RecordedTrace, city: CityProgress) {
    val flow = remember(trace.mode) { app.repository.routeInsights(trace.mode) }
    val insights by flow.collectAsStateWithLifecycle(RouteInsights())
    val quality by produceState<TripRecordQuality?>(null,trace) { value=withContext(Dispatchers.Default){TripRecordQuality.from(trace)} }
    Text(insights.newStreetMeters?.let { "${kilometers(it)} de ruas novas" } ?: "Ruas novas: não disponíveis neste registro antigo",
        style=MaterialTheme.typography.titleLarge,color=MaterialTheme.colorScheme.primary,fontWeight=FontWeight.Bold)
    if(insights.newStreetMeters!=null) Text("Trechos únicos reconhecidos pela primeira vez. Repetir uma rua não aumenta esse total.",style=MaterialTheme.typography.bodySmall)
    insights.neighborhoods.sortedByDescending{it.meters}.forEach { row ->
        val neighborhood=city.neighborhoods.firstOrNull{it.id==row.neighborhoodId}
        Text("${neighborhood?.name ?: row.neighborhoodId}: +${kilometers(row.meters)}" + (neighborhood?.takeIf{it.totalMeters>0}?.let{
            String.format(Locale.forLanguageTag("pt-BR")," • +%.2f pontos percentuais estimados",row.meters/it.totalMeters*100)} ?: ""),style=MaterialTheme.typography.bodyMedium)
    }
    var expanded by rememberSaveable(trace.mode){mutableStateOf(false)}
    TextButton(onClick={expanded=!expanded}){Text(if(expanded)"Ocultar qualidade do registro" else "Entender o reconhecimento das ruas")}
    if(expanded) quality?.let{q->
        if(!q.hasMetadata) Text("Este registro antigo não contém precisão GPS nem correspondências suficientes para avaliar seus trechos.")
        else {
            Text("Geometria com correspondência à base: ${kilometers(q.recognizedMeters)} • estimativa")
            Text("Sem correspondência: ${kilometers(q.unrecognizedMeters)} • estimativa")
            Text("${q.rejectedReadings} leituras rejeitadas; ${q.inaccurateReadings} com precisão pior que 12 m.")
            Text("Trechos sem correspondência podem resultar de GPS impreciso, vias ausentes ou regiões fora da base. A correspondência geométrica inclui repetições e não equivale a ruas novas. A área revelada usa critérios diferentes.",style=MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun RouteThumbnail(app:WorldApp,id:String) {
    val points by produceState<List<Pair<Double,Double>>>(emptyList(),id){
        value=withContext(Dispatchers.IO){
            val db=app.database.openHelper.readableDatabase
            val max=db.query("SELECT MAX(sequence) FROM route_samples WHERE mode = ?",arrayOf<Any>(id)).use{if(it.moveToFirst())it.getInt(0) else 0}
            val step=(max/60).coerceAtLeast(1)
            db.query("SELECT latitude,longitude FROM route_samples WHERE mode = ? AND accepted = 1 AND sequence % ? = 0 ORDER BY sequence LIMIT 64",arrayOf<Any>(id,step)).use{c->buildList{while(c.moveToNext())add(c.getDouble(0) to c.getDouble(1))}}
        }
    }
    val color=MaterialTheme.colorScheme.primary
    val background=MaterialTheme.colorScheme.surfaceVariant
    Canvas(Modifier.fillMaxWidth().height(90.dp)) {
        drawRect(background)
        if(points.size>1){
            val latitude=points.map{it.first};val longitude=points.map{it.second}
            val minX=longitude.min();val maxX=longitude.max();val minY=latitude.min();val maxY=latitude.max()
            val scale=minOf((size.width-32)/(maxX-minX).coerceAtLeast(.00001),(size.height-24)/(maxY-minY).coerceAtLeast(.00001))
            fun point(p:Pair<Double,Double>)=Offset((size.width/2+(p.second-(minX+maxX)/2)*scale).toFloat(),(size.height/2-(p.first-(minY+maxY)/2)*scale).toFloat())
            points.zipWithNext().forEach{(a,b)->drawLine(color,point(a),point(b),3.dp.toPx(),StrokeCap.Round)}
        }
    }
}

@Composable
fun HistoryLibrary(app:WorldApp,routes:List<SavedRoute>,selectedId:String?,trace:RecordedTrace?,city:CityProgress,active:Boolean,
    onSelect:(String?)->Unit,onMap:()->Unit,onReplay:()->Unit,onCompare:()->Unit,onShare:()->Unit,onEpochs:()->Unit,
    onExpeditionMap:(List<RecordedTrace>)->Unit) {
    val scope=rememberCoroutineScope()
    val groups by remember{app.repository.expeditions()}.collectAsStateWithLifecycle(emptyList())
    var search by rememberSaveable{mutableStateOf("")}
    var favorites by rememberSaveable{mutableStateOf(false)}
    var groupId by rememberSaveable{mutableStateOf<String?>(null)}
    var message by remember{mutableStateOf("")}
    var newGroup by rememberSaveable{mutableStateOf(false)}
    var groupTitle by rememberSaveable{mutableStateOf("")}
    var rename by rememberSaveable{mutableStateOf(false)}
    var title by rememberSaveable{mutableStateOf("")}
    var deleting by rememberSaveable{mutableStateOf(false)}
    var assigning by rememberSaveable{mutableStateOf(false)}
    var limit by rememberSaveable{mutableIntStateOf(12)}
    var busy by remember{mutableStateOf(false)}
    fun work(block:suspend()->Unit){busy=true;scope.launch{try{block();message=""}catch(e:CancellationException){throw e}catch(_:Exception){message="Não consegui concluir. Seus dados anteriores foram mantidos."}finally{busy=false}}}
    val selected=routes.firstOrNull{it.id==selectedId}
    if(message.isNotBlank())Text(message,color=MaterialTheme.colorScheme.error)
    if(selected!=null){
        TextButton(onClick={onSelect(null)}){Text("← Todos os trajetos")}
        Text(selected.title,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
        Text("${ActivityKind.fromKey(selected.activity).label} • ${dateLabel(selected.startedAtMillis)}")
        if(trace?.mode!=selected.id) LinearProgressIndicator(Modifier.fillMaxWidth()) else {
            RouteThumbnail(app,selected.id)
            Text("Prévia do traçado • abra o mapa para ver as ruas",style=MaterialTheme.typography.labelSmall)
            val summary by produceState<TripSummary?>(null,trace){value=withContext(Dispatchers.Default){TripSummary.from(trace)}}
            summary?.let{Text("${kilometers(it.distanceMeters)} • ${(it.recordedSeconds/60).toInt()} min de registros",style=MaterialTheme.typography.titleMedium)}
            TripInsightsPanel(app,trace,city)
            Button(onClick=onMap,modifier=Modifier.fillMaxWidth()){Text("Ver este trajeto no mapa")}
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                OutlinedButton(onClick=onReplay,modifier=Modifier.weight(1f)){Text("Replay")}
                OutlinedButton(onClick=onCompare,modifier=Modifier.weight(1f)){Text("Antes/depois")}
            }
            Button(onClick=onShare,enabled=!active,modifier=Modifier.fillMaxWidth()){Text("Compartilhar imagem, GIF ou vídeo")}
        }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            FilterChip(selected=selected.favorite,onClick={work{app.repository.setFavorite(selected.id,!selected.favorite)}},enabled=!busy,label={Text(if(selected.favorite)"★ Favorito" else "☆ Favoritar")})
            OutlinedButton(onClick={title=selected.title;rename=true},enabled=!busy){Text("Renomear")}
        }
        OutlinedButton(onClick={assigning=true},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text(groups.firstOrNull{it.id==selected.expeditionId}?.let{"Expedição: ${it.title}"} ?: "Adicionar a uma expedição")}
        TextButton(enabled=!active && !busy,onClick={deleting=true}){Text("Excluir este trajeto")}
    }else {
        Text("Suas viagens",style=MaterialTheme.typography.headlineSmall)
        Text("Os percursos reais ficam guardados automaticamente. Favoritos e expedições ajudam a organizar seu histórico.",style=MaterialTheme.typography.bodySmall)
        OutlinedTextField(value=search,onValueChange={search=it;limit=12},label={Text("Buscar pelo título")},singleLine=true,modifier=Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            FilterChip(selected=!favorites,onClick={favorites=false},label={Text("Todos")})
            FilterChip(selected=favorites,onClick={favorites=true},label={Text("★ Favoritos")})
            FilterChip(selected=groupId==null,onClick={groupId=null},label={Text("Todas as expedições")})
            groups.forEach{g->FilterChip(selected=groupId==g.id,onClick={groupId=g.id},label={Text(g.title)})}
        }
        OutlinedButton(onClick={groupTitle="";newGroup=true}){Text("Criar expedição")}
        groupId?.let{id->
            val members=routes.filter{it.expeditionId==id}
            Text("${groups.firstOrNull{it.id==id}?.title.orEmpty()} • ${members.size} percursos")
            Button(enabled=members.isNotEmpty()&&!busy,onClick={work{val traces=app.repository.expeditionTrace(id).first();onExpeditionMap(traces)}},modifier=Modifier.fillMaxWidth()){Text("Ver mapa e resumo da expedição")}
        }
        val visible=routes.filter{(!favorites||it.favorite)&&(groupId==null||it.expeditionId==groupId)&&it.title.contains(search,true)}
        if(visible.isEmpty())Text("Nenhum trajeto aqui ainda. Ao concluir uma viagem real, ela aparecerá no histórico.")
        visible.take(limit).forEach{row->
            Card(onClick={onSelect(row.id)},enabled=!active,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(18.dp)){
                RouteThumbnail(app,row.id)
                Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                    Text((if(row.favorite)"★ " else "")+row.title,style=MaterialTheme.typography.titleMedium)
                    Text("${ActivityKind.fromKey(row.activity).label} • ${dateLabel(row.startedAtMillis)}",style=MaterialTheme.typography.bodySmall)
                    Text(listOfNotNull(row.distanceMeters?.let{kilometers(it)},row.recordedSeconds?.let{"${(it/60).toInt()} min"}).joinToString(" • ").ifBlank{"Abrir resumo, mapa e compartilhamento"},style=MaterialTheme.typography.labelMedium)
                }
            }
        }
        if(visible.size>limit)TextButton(onClick={limit+=12}){Text("Mostrar mais viagens")}
        TextButton(onClick=onEpochs){Text("Mapa das primeiras descobertas")}
    }
    if(newGroup)AlertDialog(onDismissRequest={newGroup=false},title={Text("Nova expedição")},text={OutlinedTextField(value=groupTitle,onValueChange={groupTitle=it.take(80)},label={Text("Título")},singleLine=true)},confirmButton={TextButton(enabled=groupTitle.isNotBlank()&&!busy,onClick={work{groupId=app.repository.createExpedition(groupTitle);selected?.let{app.repository.assignExpedition(it.id,groupId)};newGroup=false}}){Text("Criar")}},dismissButton={TextButton(onClick={newGroup=false}){Text("Cancelar")}})
    if(rename&&selected!=null)AlertDialog(onDismissRequest={rename=false},title={Text("Nome do trajeto")},text={OutlinedTextField(value=title,onValueChange={title=it.take(80)},singleLine=true)},confirmButton={TextButton(enabled=title.isNotBlank()&&!busy,onClick={work{app.repository.renameSavedRoute(selected.id,title);rename=false}}){Text("Salvar")}},dismissButton={TextButton(onClick={rename=false}){Text("Cancelar")}})
    if(deleting&&selected!=null)AlertDialog(onDismissRequest={deleting=false},title={Text("Excluir trajeto?")},text={Text("Remove o registro deste percurso. Sua exploração e percentuais dos bairros são mantidos.")},confirmButton={TextButton(enabled=!busy,onClick={work{app.repository.deleteSavedRoute(selected.id);onSelect(null);deleting=false}}){Text("Excluir")}},dismissButton={TextButton(onClick={deleting=false}){Text("Manter")}})
    if(assigning&&selected!=null)AlertDialog(onDismissRequest={assigning=false},title={Text("Escolher expedição")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
        TextButton(onClick={work{app.repository.assignExpedition(selected.id,null);assigning=false}}){Text("Sem expedição")}
        groups.forEach{g->TextButton(onClick={work{app.repository.assignExpedition(selected.id,g.id);assigning=false}}){Text(g.title)}}
        TextButton(onClick={assigning=false;newGroup=true}){Text("Criar uma expedição")}
    }},confirmButton={TextButton(onClick={assigning=false}){Text("Fechar")}})
}
