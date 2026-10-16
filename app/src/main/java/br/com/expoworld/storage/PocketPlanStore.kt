package br.com.expoworld.storage

import br.com.expoworld.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object PocketPlanCodec {
    fun encode(plan: PocketPlan): String = JSONObject().apply {
        put("version",1);put("id",plan.id);put("activity",plan.request.activity.key);put("purpose",plan.request.purpose.name)
        put("minutes",plan.request.minutes);put("loop",plan.request.returnToStart);put("variation",plan.request.variation)
        put("start",JSONArray(listOf(plan.request.start.latitude,plan.request.start.longitude)))
        put("distance",plan.path.distanceMeters);put("seconds",plan.path.seconds);put("fresh",plan.estimatedNewMeters ?: JSONObject.NULL);put("returning",plan.returning)
        put("points",JSONArray().apply { plan.path.points.forEach { put(JSONArray(listOf(it.latitude,it.longitude))) } })
        put("steps",JSONArray().apply { plan.path.steps.forEach { put(JSONArray(listOf(it.pointIndex,it.instruction,it.meters))) } })
    }.toString()
    fun decode(text: String): PocketPlan {
        require(text.length <= 2_000_000)
        val json = JSONObject(text); require(json.getInt("version") == 1)
        val start = json.getJSONArray("start");val points = json.getJSONArray("points");val steps = json.getJSONArray("steps")
        require(points.length() in 2..20_000 && steps.length() <= 10_000)
        val request = PocketRequest(PocketPoint(start.getDouble(0),start.getDouble(1)),json.getInt("minutes"),
            ActivityKind.entries.single { it.key == json.getString("activity") },PocketPurpose.valueOf(json.getString("purpose")),json.getBoolean("loop"),json.optInt("variation"))
        val path = PocketPath((0 until points.length()).map { val p=points.getJSONArray(it);PocketPoint(p.getDouble(0),p.getDouble(1)) },
            (0 until steps.length()).map { val s=steps.getJSONArray(it);PocketStep(s.getInt(0),s.getString(1),s.getDouble(2)) },json.getDouble("distance"),json.getDouble("seconds"))
        val fresh = if(json.isNull("fresh")) null else json.getDouble("fresh").also { require(it.isFinite() && it >= 0 && it <= path.distanceMeters) }
        return PocketPlan(json.getString("id"),request,path,fresh,json.optBoolean("returning"))
    }
}
class PocketPlanStore(private val dao: ExplorationDao) {
    val saved = dao.plannedRoutes().map { rows -> rows.mapNotNull { row ->
        runCatching { PocketSavedPlan(row.id,row.title,row.createdAtMillis,PocketPlanCodec.decode(row.payload)) }.getOrNull()
    } }.flowOn(Dispatchers.Default)
    suspend fun save(title: String, plan: PocketPlan) = withContext(Dispatchers.IO) {
        require(title.trim().length in 1..80)
        val id=UUID.randomUUID().toString()
        dao.putPlannedRoute(PlannedRouteRow(id,title.trim(),System.currentTimeMillis(),PocketPlanCodec.encode(plan)))
    }
    suspend fun delete(id: String) = dao.deletePlannedRoute(id)
}
