package br.com.expoworld.storage

import android.os.SystemClock
import br.com.expoworld.BuildConfig
import br.com.expoworld.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/** Explicitly authorized server. Serialized, user-triggered calls; no periodic GPS uploads. */
class PocketRouting {
    private val gate = Mutex()
    private var lastRequest = 0L
    suspend fun route(points: List<PocketPoint>, activity: ActivityKind, purpose: PocketPurpose): PocketPath = gate.withLock {
        delay((1200 - (SystemClock.elapsedRealtime() - lastRequest)).coerceAtLeast(0))
        lastRequest = SystemClock.elapsedRealtime()
        withContext(Dispatchers.IO) {
            val base = BuildConfig.ROUTING_URL.ifBlank {
                check(BuildConfig.DEBUG) { "Configure o serviço de percursos antes de publicar o app." }
                "https://valhalla1.openstreetmap.de"
            }
            require(base.startsWith("https://")) { "O serviço de percursos precisa usar HTTPS." }
            val costing = when (activity) { ActivityKind.WALK -> "pedestrian"; ActivityKind.BIKE -> "bicycle"; ActivityKind.CAR -> "auto" }
            val options = JSONObject().put("use_ferry", 0)
            when (activity) {
                ActivityKind.WALK -> { options.put("walking_speed",4.5); if (purpose == PocketPurpose.STROLL) options.put("use_hills",.1).put("step_penalty",60) }
                ActivityKind.BIKE -> { options.put("cycling_speed",14); if (purpose == PocketPurpose.STROLL) options.put("use_hills",.1).put("use_roads",.1) }
                ActivityKind.CAR -> { if (purpose == PocketPurpose.STROLL) options.put("use_highways",.1).put("use_tolls",0) }
            }
            val request = JSONObject().put("locations",JSONArray().apply { points.forEach { p -> put(JSONObject().put("lat",p.latitude).put("lon",p.longitude).put("type","break")) } })
                .put("costing",costing).put("costing_options",JSONObject().put(costing,options)).put("units","kilometers").put("language","pt-BR")
            val connection = URL(base.trimEnd('/') + "/route").openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects=false
                connection.requestMethod = "POST"; connection.connectTimeout = 15000; connection.readTimeout = 25000
                connection.setRequestProperty("Content-Type","application/json; charset=utf-8")
                connection.setRequestProperty("User-Agent","ExpoWorld/${BuildConfig.VERSION_NAME} (https://github.com/CJuniorJR/expo-world)")
                connection.setRequestProperty("X-Client-Id","expo-world-cjuniorjr-dev")
                connection.doOutput = true
                connection.outputStream.use { it.write(request.toString().toByteArray(Charsets.UTF_8)) }
                coroutineContext.ensureActive()
                if (connection.responseCode == 429) error("O serviço está ocupado. Aguarde um pouco e tente novamente.")
                if (connection.responseCode !in 200..299) error("Não foi possível encontrar vias para esse percurso. Tente outro ponto de partida.")
                val bytes = connection.inputStream.use { it.readBounded(2_000_000) }
                coroutineContext.ensureActive()
                parse(JSONObject(bytes.toString(Charsets.UTF_8)))
            } finally { connection.disconnect() }
        }
    }
    private fun java.io.InputStream.readBounded(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) { val n = read(buffer); if (n < 0) break; check(out.size() + n <= limit); out.write(buffer,0,n) }
        return out.toByteArray()
    }
    companion object {
        fun parse(json: JSONObject): PocketPath {
            val trip = json.getJSONObject("trip"); val summary = trip.getJSONObject("summary")
            val points = ArrayList<PocketPoint>(); val steps = ArrayList<PocketStep>()
            val legs = trip.getJSONArray("legs"); require(legs.length() in 1..8)
            for (i in 0 until legs.length()) {
                val leg = legs.getJSONObject(i); val decoded = PocketPlanning.decodePolyline6(leg.getString("shape"))
                val offset = if (points.isEmpty()) 0 else points.size - 1
                if (points.isNotEmpty()) require(points.last().distance(decoded.first()) < 50)
                points.addAll(if (points.isEmpty()) decoded else decoded.drop(1)); require(points.size <= 20_000)
                val maneuvers = leg.optJSONArray("maneuvers") ?: JSONArray()
                for (j in 0 until maneuvers.length()) {
                    val m = maneuvers.getJSONObject(j)
                    if(i < legs.length()-1 && m.optInt("type") in 4..6) continue
                    steps.add(PocketStep(offset + m.getInt("begin_shape_index"),m.getString("instruction"),m.optDouble("length",0.0)*1000))
                }
            }
            return PocketPath(points,steps,summary.getDouble("length")*1000,summary.getDouble("time"))
        }
    }
}
