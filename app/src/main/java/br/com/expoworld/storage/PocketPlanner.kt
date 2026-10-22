package br.com.expoworld.storage

import br.com.expoworld.WorldApp
import br.com.expoworld.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.locationtech.jts.geom.Coordinate
import java.util.UUID
import kotlin.math.*

data class PocketDraft(val minutes:String="30",val extraMinutes:String="10",val activityKey:String?=null,
    val purposeKey:String=PocketPurpose.EXPLORE.name,val loop:Boolean=true,val toDestination:Boolean=false,val neighborhoodId:String?=null)
data class PocketState(val plan: PocketPlan? = null, val active: Boolean = false, val busy: Boolean = false, val message: String = "")
class PocketPlanner(private val app: WorldApp) {
    private val prefs = app.getSharedPreferences("pocket", android.content.Context.MODE_PRIVATE)
    private val router = PocketRouting()
    var draft=PocketDraft()
    var resumeConfigure=false
    val store by lazy { PocketPlanStore(app.database.cells()) }
    val offline by lazy { OfflineJourneyManager(app) }
    val state = MutableStateFlow(PocketState(plan = prefs.getString("current",null)?.let { runCatching { PocketPlanCodec.decode(it) }.getOrNull() }))
    private var job: Job? = null
    private var revision = 0
    fun cancel() { revision++;job?.cancel(); state.update { it.copy(busy = false,message = if(it.busy) "Busca cancelada. O roteiro anterior foi mantido." else it.message) } }
    fun select(plan: PocketPlan) {
        state.value = PocketState(plan)
        prefs.edit().putString("current",PocketPlanCodec.encode(plan)).apply()
    }
    suspend fun reuse(plan: PocketPlan) {
        val network=app.city.planningNetwork()
        val visited=network?.let{app.database.cells().realStreetVisits(it.version)}.orEmpty().map{it.streetId to it.bin}.toSet()
        val fresh=estimate(network,visited,plan.path,plan.request.activity)
        val targetFresh=plan.request.neighborhoodId?.let { estimate(network,visited,plan.path,plan.request.activity,it) }
        select(plan.copy(estimatedNewMeters=fresh,neighborhoodFreshMeters=targetFresh))
    }
    fun clear() { cancel(); state.value = PocketState(); prefs.edit().remove("current").apply() }
    fun begin() { state.update { it.copy(active = it.plan != null) } }
    fun end() { state.update { it.copy(active = false) } }
    fun generate(scope: CoroutineScope, request: PocketRequest) {
        cancel()
        val run=revision
        job = scope.launch {
            state.update { it.copy(busy = true, message = "Buscando percursos pelas ruas…") }
            try {
                val network = app.city.planningNetwork()
                val visits = network?.let { app.database.cells().realStreetVisits(it.version) }.orEmpty().map { it.streetId to it.bin }.toSet()
                val candidates = ArrayList<PocketPlan>()
                val destination=request.destination
                var lastError: Exception? = null
                val target=request.neighborhoodId?.let { id -> network?.neighborhoods?.firstOrNull { it.id==id } }
                check(request.neighborhoodId==null || target!=null) { "O bairro escolhido não está disponível na base de Curitiba." }
                var baseline: PocketPath? = null
                if(destination!=null) {
                    state.update { it.copy(message="Calculando o caminho direto até seu destino…") }
                    baseline=router.route(listOf(request.start,destination),request.activity,request.purpose)
                    check(PocketPlanning.fits(request,baseline,baseline.seconds)) { "Não encontrei vias próximas da partida e do destino. Escolha pontos mais perto das ruas." }
                }
                suspend fun add(path: PocketPath) {
                    val share=target?.let { neighborhoodShare(network!!,path,it.id) } ?: 1.0
                    if(share<.5) return
                    val fresh=estimate(network,visits,path,request.activity)
                    val local=request.neighborhoodId?.let { estimate(network,visits,path,request.activity,it) }
                    val total=request.neighborhoodId?.let { id -> network?.streets?.filter { it.neighborhoodId==id }?.sumOf { it.length } }
                    candidates.add(PocketPlan(UUID.randomUUID().toString(),request,path,fresh,baselineSeconds=baseline?.seconds,
                        neighborhoodFreshMeters=local,neighborhoodTotalMeters=total))
                }
                baseline?.let { add(it) }
                val attempts=if(request.destination!=null && request.detourMinutes==0) 0 else 3
                for (candidate in 0 until attempts) {
                    ensureActive()
                    state.update { it.copy(message = "Comparando opção ${candidate+1} de $attempts…") }
                    try {
                        val waypoints = withContext(Dispatchers.Default) { anchors(request,candidate,network,visits) }
                        val path = router.route(waypoints,request.activity,request.purpose)
                        add(path)
                    } catch (e: CancellationException) { throw e } catch (e: Exception) { lastError = e }
                }
                val closest = candidates.minByOrNull { abs(it.path.seconds-request.budgetSeconds*.9) }
                if (request.destination==null && closest != null && closest.path.seconds !in request.budgetSeconds*.8..request.budgetSeconds*1.05) {
                    try {
                        state.update { it.copy(message = "Ajustando a duração, incluindo o retorno…") }
                        val scale = request.budgetSeconds*.9/closest.path.seconds
                        val path = router.route(anchors(request,0,network,visits,scale),request.activity,request.purpose)
                        add(path)
                    } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Keep already valid alternatives. */ }
                }
                val chosen = candidates.filter { PocketPlanning.fits(request,it.path,baseline?.seconds) }.maxByOrNull {
                    val fresh=if(target!=null) it.neighborhoodFreshMeters else it.estimatedNewMeters
                    if(request.destination!=null) {
                        // The detour is a ceiling, never a demand to spend all the extra time.
                        val extra=(it.path.seconds-(baseline?.seconds ?: 0.0)).coerceAtLeast(0.0)
                        if(request.purpose==PocketPurpose.EXPLORE) (fresh ?: 0.0)/1000-extra/3600
                        else -extra/600-it.path.steps.size/(it.path.distanceMeters/1000).coerceAtLeast(1.0)*.01
                    } else PocketPlanning.score(request,it.path,fresh)
                } ?: throw lastError.takeIf { candidates.isEmpty() && target==null }.let { it ?: IllegalStateException(
                    if(target!=null) "Não encontrei uma opção com pelo menos metade do percurso nesse bairro e dentro do tempo. Tente partir mais perto, mudar o tempo ou retirar o destino."
                    else "Não encontrei um percurso adequado a esse tempo. Tente outra duração ou outro ponto de partida.") }
                select(chosen)
            } catch (e: CancellationException) { throw e }
            catch (_: java.io.IOException) { state.update { it.copy(message = "Não foi possível acessar o serviço de percursos. Confira a internet e tente novamente.") } }
            catch (e: Exception) { state.update { it.copy(message = e.message?.takeIf { text -> text.length < 200 && !text.contains("http") } ?: "Não foi possível gerar esse percurso. Tente outra duração ou partida.") } }
            finally { if(revision==run) state.update { it.copy(busy = false) } }
        }
    }
    fun returnNow(scope: CoroutineScope, current: PocketPoint) {
        val original = state.value.plan ?: return
        if (state.value.busy) return
        if(current.distance(original.request.start)<=60){state.update{it.copy(message="Você já está próximo do ponto de partida.")};return}
        val run=++revision
        job = scope.launch {
            state.update { it.copy(busy = true,message = "Calculando a volta ao ponto de partida…") }
            try {
                val path = router.route(listOf(current,original.request.start),original.request.activity,PocketPurpose.STROLL)
                require(path.points.first().distance(current) <= 100 && path.points.last().distance(original.request.start) <= 100)
                val plan = original.copy(id = UUID.randomUUID().toString(),path = path,estimatedNewMeters = null,returning = true,baselineSeconds=null,neighborhoodFreshMeters=null,neighborhoodTotalMeters=null)
                val active = state.value.active
                select(plan);state.update { it.copy(active = active,message = "Retorno recalculado pela rede de vias.") }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { state.update { it.copy(message = "Não consegui recalcular a volta. O roteiro anterior foi mantido; confira a internet e tente novamente.") } }
            finally { if(revision==run) state.update { it.copy(busy = false) } }
        }
    }
    private fun anchors(request: PocketRequest, candidate: Int, network: CityNetwork?, visited: Set<Pair<String,Int>>, scale: Double=1.0): List<PocketPoint> {
        val destination=request.destination
        val points = PocketPlanning.waypoints(request,candidate,scale)
        request.neighborhoodId?.let { id ->
            val streets=network?.streets.orEmpty().filter { it.neighborhoodId==id && it.modes and request.activity.mask!=0 }
            check(streets.isNotEmpty()) { "Não há vias para esta atividade no bairro escolhido." }
            // Restrict every proposed intermediate point to eligible streets of the selected neighborhood.
            // Candidate directions vary within that boundary rather than sending the route to other neighborhoods.
            val center=network!!.neighborhoods.first { it.id==id }.boundary.interiorPoint.coordinate
            val radius=(request.budgetSeconds*request.planningSpeed/3.6/4).coerceIn(150.0,3000.0)*scale.coerceIn(.15,4.0)
            val angle=Math.toRadians((candidate*120+request.variation*137.508)%360)
            val start=CuritibaProjection.point(request.start.latitude,request.start.longitude)
            val targetCenter=if(network.neighborhoodAt(request.start.fix())==id) start else center
            fun anchor(direction: Double): PocketPoint {
                val ideal=Coordinate(targetCenter.x+cos(direction)*radius,targetCenter.y+sin(direction)*radius)
                val street=streets.maxBy { road ->
                    val middle=road.indexed.extractPoint(road.length/2)
                    val unknown=(0 until road.binCount).count { (road.id to it) !in visited }.toDouble()/road.binCount
                    (if(request.purpose==PocketPurpose.EXPLORE) unknown*min(radius,700.0)*.6 else 0.0)-middle.distance(ideal)
                }
                val p=street.indexed.extractPoint(street.length/2)
                return PocketPoint(CuritibaProjection.latitude(p.y),CuritibaProjection.longitude(p.x))
            }
            return when {
                destination!=null -> listOf(request.start,anchor(angle),destination)
                request.returnToStart -> listOf(request.start,anchor(angle-.7),anchor(angle+.7),request.start)
                else -> listOf(request.start,anchor(angle-.7),anchor(angle+.7))
            }
        }
        if (network == null || request.purpose != PocketPurpose.EXPLORE || network.neighborhoodAt(request.start.fix()) == null) return points
        return points.mapIndexed { i,p ->
            if (i == 0 || ((request.returnToStart || request.destination!=null) && i == points.lastIndex)) return@mapIndexed p
            val target = CuritibaProjection.point(p.latitude,p.longitude)
            val radius = min(700.0,request.start.distance(p)*.4).coerceAtLeast(100.0)
            val best = network.streets.asSequence().filter { it.modes and request.activity.mask != 0 }
                .map { street -> val mid=street.indexed.extractPoint(street.length/2); Triple(street,mid,mid.distance(target)) }
                .filter { it.third <= radius }
                .maxByOrNull { (street,_,distance) ->
                    val unknown = (0 until street.binCount).count { (street.id to it) !in visited }.toDouble()/street.binCount
                    unknown*radius*.7-distance
                }?.second
            best?.let { PocketPoint(CuritibaProjection.latitude(it.y),CuritibaProjection.longitude(it.x)) } ?: p
        }
    }
    private suspend fun neighborhoodShare(network: CityNetwork,path:PocketPath,id:String):Double = withContext(Dispatchers.Default) {
        val boundary=network.neighborhoods.first { it.id==id }.boundary
        val factory=org.locationtech.jts.geom.GeometryFactory()
        var inside=0.0;var total=0.0
        for((a,b) in path.points.zipWithNext()) {
            ensureActive();val length=a.distance(b);val count=ceil(length/40).toInt().coerceIn(1,1000)
            for(i in 0 until count) {
                val t=(i+.5)/count
                val p=CuritibaProjection.point(a.latitude+(b.latitude-a.latitude)*t,a.longitude+(b.longitude-a.longitude)*t)
                if(boundary.covers(factory.createPoint(p))) inside+=length/count
            }
            total+=length
        }
        if(total>0) inside/total else 0.0
    }
    private suspend fun estimate(network: CityNetwork?, visited: Set<Pair<String,Int>>, path: PocketPath, activity: ActivityKind, neighborhoodId:String?=null): Double? = withContext(Dispatchers.Default) {
        if (network == null || network.neighborhoodAt(path.points.first().fix()) == null) return@withContext null
        val unique = HashMap<Pair<String,Int>,Double>()
        for ((a,b) in path.points.zipWithNext()) {
            ensureActive()
            if (abs(a.longitude-b.longitude)>180) continue
            val distance=a.distance(b); if (distance < 3) continue
            val steps=ceil(distance/20).toInt().coerceAtMost(500)
            for (i in 0 until steps) {
                fun point(t: Double,time: Double) = Fix(a.latitude+(b.latitude-a.latitude)*t,a.longitude+(b.longitude-a.longitude)*t,2.0,time)
                val first=point(i.toDouble()/steps,0.0);val second=point((i+1.0)/steps,distance/steps/2)
                network.match(first,second,activity).forEach { credit -> if ((neighborhoodId==null || credit.neighborhoodId==neighborhoodId) && (credit.streetId to credit.bin) !in visited) unique[credit.streetId to credit.bin]=credit.meters }
            }
        }
        unique.values.sum().coerceAtMost(path.distanceMeters)
    }
}
