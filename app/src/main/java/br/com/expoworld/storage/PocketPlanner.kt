package br.com.expoworld.storage

import br.com.expoworld.WorldApp
import br.com.expoworld.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.locationtech.jts.geom.Coordinate
import java.util.UUID
import kotlin.math.*

data class PocketState(val plan: PocketPlan? = null, val active: Boolean = false, val busy: Boolean = false, val message: String = "")
class PocketPlanner(private val app: WorldApp) {
    private val prefs = app.getSharedPreferences("pocket", android.content.Context.MODE_PRIVATE)
    private val router = PocketRouting()
    val store by lazy { PocketPlanStore(app.database.cells()) }
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
        select(plan.copy(estimatedNewMeters=estimate(network,visited,plan.path,plan.request.activity)))
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
                var lastError: Exception? = null
                for (candidate in 0..2) {
                    ensureActive()
                    state.update { it.copy(message = "Comparando opção ${candidate+1} de 3…") }
                    try {
                        val waypoints = withContext(Dispatchers.Default) { anchors(request,candidate,network,visits) }
                        val path = router.route(waypoints,request.activity,request.purpose)
                        val fresh = estimate(network,visits,path,request.activity)
                        candidates.add(PocketPlan(UUID.randomUUID().toString(),request,path,fresh))
                    } catch (e: CancellationException) { throw e } catch (e: Exception) { lastError = e }
                }
                val closest = candidates.minByOrNull { abs(it.path.seconds-request.budgetSeconds*.9) }
                if (closest != null && closest.path.seconds !in request.budgetSeconds*.8..request.budgetSeconds*1.05) {
                    try {
                        state.update { it.copy(message = "Ajustando a duração, incluindo o retorno…") }
                        val scale = request.budgetSeconds*.9/closest.path.seconds
                        val path = router.route(PocketPlanning.waypoints(request,0,scale),request.activity,request.purpose)
                        candidates.add(PocketPlan(UUID.randomUUID().toString(),request,path,estimate(network,visits,path,request.activity)))
                    } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Keep already valid alternatives. */ }
                }
                val chosen = candidates.filter { PocketPlanning.fits(request,it.path) }.maxByOrNull { PocketPlanning.score(request,it.path,it.estimatedNewMeters) }
                    ?: throw lastError.takeIf { candidates.isEmpty() }.let { it ?: IllegalStateException("Não encontrei um percurso adequado a esse tempo. Tente outra duração ou outro ponto de partida.") }
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
                val plan = original.copy(id = UUID.randomUUID().toString(),path = path,estimatedNewMeters = null,returning = true)
                val active = state.value.active
                select(plan);state.update { it.copy(active = active,message = "Retorno recalculado pela rede de vias.") }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { state.update { it.copy(message = "Não consegui recalcular a volta. O roteiro anterior foi mantido; confira a internet e tente novamente.") } }
            finally { if(revision==run) state.update { it.copy(busy = false) } }
        }
    }
    private fun anchors(request: PocketRequest, candidate: Int, network: CityNetwork?, visited: Set<Pair<String,Int>>): List<PocketPoint> {
        val points = PocketPlanning.waypoints(request,candidate)
        if (network == null || request.purpose != PocketPurpose.EXPLORE || network.neighborhoodAt(request.start.fix()) == null) return points
        return points.mapIndexed { i,p ->
            if (i == 0 || (request.returnToStart && i == points.lastIndex)) return@mapIndexed p
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
    private suspend fun estimate(network: CityNetwork?, visited: Set<Pair<String,Int>>, path: PocketPath, activity: ActivityKind): Double? = withContext(Dispatchers.Default) {
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
                network.match(first,second,activity).forEach { credit -> if ((credit.streetId to credit.bin) !in visited) unique[credit.streetId to credit.bin]=credit.meters }
            }
        }
        unique.values.sum().coerceAtMost(path.distanceMeters)
    }
}
