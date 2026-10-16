package br.com.expoworld.storage

import android.content.res.AssetManager
import android.util.JsonReader
import br.com.expoworld.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import org.locationtech.jts.geom.*

class CuritibaRepository(private val dao: ExplorationDao, assets: AssetManager) {
    private val loaderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val catalog = loaderScope.async { load(assets) }
    suspend fun neighborhoodAt(fix: Fix?): String? = withContext(Dispatchers.Default) {
        try { catalog.await().neighborhoodAt(fix) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
    }
    suspend fun bounds(id: String): DoubleArray? = withContext(Dispatchers.Default) {
        val neighborhood = catalog.await().neighborhoods.firstOrNull { it.id == id } ?: return@withContext null
        val e = neighborhood.boundary.envelopeInternal
        doubleArrayOf(CuritibaProjection.latitude(e.minY), CuritibaProjection.longitude(e.minX),
            CuritibaProjection.latitude(e.maxY), CuritibaProjection.longitude(e.maxX))
    }
    suspend fun match(previous: Fix?, fix: Fix, activity: ActivityKind): Pair<String, Set<StreetCredit>> = withContext(Dispatchers.Default) {
        try { val network = catalog.await(); network.version to network.match(previous, fix, activity) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { "" to emptySet() } // A damaged catalog must not stop GPS/fog recording.
    }
    fun progress(mode: String): Flow<CityProgress> = flow {
        val network = catalog.await()
        emitAll(dao.streetTotals(mode, network.version).map { rows ->
            network.progress(rows.associate { it.neighborhoodId to it.meters })
        })
    }.catch { e ->
        if (e is CancellationException) throw e
        android.util.Log.e("CuritibaRepository", "Falha ao carregar catálogo offline", e)
        emit(CityProgress(error = "Base de Curitiba indisponível. A exploração e os trajetos continuam sendo registrados."))
    }.flowOn(Dispatchers.Default)

    private fun load(assets: AssetManager): CityNetwork {
        val factory = GeometryFactory()
        fun ring(coords: JSONArray): LinearRing = factory.createLinearRing(Array(coords.length()) { i ->
            val p = coords.getJSONArray(i)
            CuritibaProjection.point(p.getDouble(1), p.getDouble(0))
        })
        fun polygon(coords: JSONArray): Polygon = factory.createPolygon(ring(coords.getJSONArray(0)),
            Array((coords.length() - 1).coerceAtLeast(0)) { i -> ring(coords.getJSONArray(i + 1)) })
        val boundaryJson = assets.open("curitiba-neighborhoods.geojson").bufferedReader().use { JSONObject(it.readText()).getJSONArray("features") }
        val neighborhoods = (0 until boundaryJson.length()).map { i ->
            val feature = boundaryJson.getJSONObject(i); val geometry = feature.getJSONObject("geometry")
            val coords = geometry.getJSONArray("coordinates")
            val boundary = if (geometry.getString("type") == "Polygon") polygon(coords)
                else factory.createMultiPolygon(Array(coords.length()) { p -> polygon(coords.getJSONArray(p)) })
            CityNeighborhood(feature.getJSONObject("properties").getString("id"), feature.getJSONObject("properties").getString("name"), boundary)
        }
        val roads = ArrayList<CityStreet>()
        var version = ""
        var timestamp = ""
        // Stream the large road array: don't keep a second full JSON object graph in memory.
        // AAPT expands .gz assets and strips their suffix. Ship plain JSON and let the APK compress it.
        JsonReader(assets.open("curitiba-network.json").bufferedReader(Charsets.UTF_8)).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) when (reader.nextName()) {
                "metadata" -> {
                    reader.beginObject()
                    while (reader.hasNext()) when (reader.nextName()) {
                        "version" -> version = reader.nextString()
                        "roadsTimestamp" -> timestamp = reader.nextString()
                        else -> reader.skipValue()
                    }
                    reader.endObject()
                }
                "roads" -> {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        reader.beginArray()
                        val id = reader.nextString(); val neighborhoodId = reader.nextString(); val modes = reader.nextInt()
                        val coordinates = ArrayList<Coordinate>()
                        reader.beginArray()
                        while (reader.hasNext()) {
                            reader.beginArray(); coordinates.add(Coordinate(reader.nextDouble(), reader.nextDouble())); reader.endArray()
                        }
                        reader.endArray(); reader.endArray()
                        roads.add(CityStreet(id, neighborhoodId, modes, factory.createLineString(coordinates.toTypedArray())))
                    }
                    reader.endArray()
                }
                else -> reader.skipValue()
            }
            reader.endObject()
        }
        check(version.isNotBlank() && neighborhoods.size == 75 && roads.size > 1000)
        return CityNetwork(version, timestamp, neighborhoods, roads)
    }
}
