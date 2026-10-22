package br.com.expoworld.storage

import androidx.room.*
import br.com.expoworld.core.Cell
import br.com.expoworld.core.CellBounds
import br.com.expoworld.core.ExplorationConfig
import br.com.expoworld.core.TraceSample
import br.com.expoworld.core.RecordedTrace
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi

@Entity(tableName = "cells", primaryKeys = ["mode", "y", "x"])
data class ExploredCell(val mode: String, val y: Int, val x: Int,
    @ColumnInfo(defaultValue = "NULL") val firstDiscoveredAtMillis: Long? = null)

@Entity(tableName = "trip_cells", primaryKeys = ["mode", "y", "x"])
data class TripCell(val mode: String, val y: Int, val x: Int,
    @ColumnInfo(defaultValue = "-1") val elapsedSeconds: Double = -1.0)

data class CoverageBounds(val minX: Int?, val maxX: Int?, val minY: Int?, val maxY: Int?) {
    fun domain(): CellBounds? = minX?.let { CellBounds(it, maxX!!, minY!!, maxY!!) }
}

@Entity(tableName = "route_sessions")
data class RouteSession(@PrimaryKey val mode: String, val startedAtMillis: Long, val endedAtMillis: Long?, val completed: Boolean,
    @ColumnInfo(defaultValue = "'car'") val activity: String = "car",
    @ColumnInfo(defaultValue = "NULL") val newAreaSquareMeters: Double? = null,
    @ColumnInfo(defaultValue = "NULL") val archivedId: String? = null,
    @ColumnInfo(defaultValue = "NULL") val newStreetMeters: Double? = null)

@Entity(tableName = "route_samples", primaryKeys = ["mode", "sequence"])
data class RouteSampleRow(
    val mode: String, val sequence: Int, val latitude: Double, val longitude: Double,
    val elapsedSeconds: Double, val speedKmh: Double?, val breakBefore: Boolean,
    val stopId: Int, val stopSeconds: Double,
    @ColumnInfo(defaultValue = "NULL") val accuracyMeters: Double? = null,
    @ColumnInfo(defaultValue = "1") val accepted: Boolean = true,
    @ColumnInfo(defaultValue = "NULL") val rawSpeedMetersSecond: Double? = null,
    @ColumnInfo(defaultValue = "NULL") val monotonicSeconds: Double? = null,
    @ColumnInfo(defaultValue = "NULL") val streetIds: String? = null
)

data class RouteSnapshot(
    @Embedded val session: RouteSession,
    @Relation(parentColumn = "mode", entityColumn = "mode") val samples: List<RouteSampleRow>
)
data class CellRowCount(val y: Int, val cells: Long)

@Entity(tableName = "saved_routes")
data class SavedRoute(@PrimaryKey val id: String, val title: String, val startedAtMillis: Long,
    @ColumnInfo(defaultValue = "'car'") val activity: String = "car",
    @ColumnInfo(defaultValue = "0") val favorite: Boolean = false,
    @ColumnInfo(defaultValue = "NULL") val expeditionId: String? = null,
    @ColumnInfo(defaultValue = "NULL") val distanceMeters: Double? = null,
    @ColumnInfo(defaultValue = "NULL") val recordedSeconds: Double? = null)

@Entity(tableName = "expeditions")
data class ExpeditionRow(@PrimaryKey val id: String, val title: String, val createdAtMillis: Long)

@Entity(tableName = "street_visits", primaryKeys = ["mode", "activity", "dataset", "streetId", "bin"],
    indices = [Index(value = ["mode", "activity", "dataset", "neighborhoodId"])])
data class StreetVisit(val mode: String, val activity: String, val dataset: String, val streetId: String,
    val bin: Int, val neighborhoodId: String, val meters: Double)
@Entity(tableName = "planned_routes")
data class PlannedRouteRow(@PrimaryKey val id: String, val title: String, val createdAtMillis: Long, val payload: String)

@Entity(tableName = "trip_street_visits", primaryKeys = ["mode", "dataset", "streetId", "bin"])
data class TripStreetVisit(val mode: String, val dataset: String, val streetId: String, val bin: Int, val neighborhoodId: String, val meters: Double)
data class NeighborhoodMeters(val neighborhoodId: String, val meters: Double)
data class RouteInsights(val newStreetMeters: Double? = null, val matchedMeters: Double? = null, val neighborhoods: List<NeighborhoodMeters> = emptyList())

private fun RouteSampleRow.toDomain() = TraceSample(sequence, latitude, longitude, elapsedSeconds, speedKmh, breakBefore, stopId, stopSeconds,
    accuracyMeters, accepted, rawSpeedMetersSecond, monotonicSeconds, streetIds)
private fun TraceSample.toRow(mode: String) = RouteSampleRow(mode, sequence, latitude, longitude,
    elapsedSeconds, speedKmh, breakBefore, stopId, stopSeconds, accuracyMeters, accepted, rawSpeedMetersSecond, monotonicSeconds, streetIds)

@Dao
interface ExplorationDao {
    @Query("SELECT * FROM trip_street_visits WHERE mode = :mode")
    suspend fun allTripStreets(mode: String): List<TripStreetVisit>
    @Query("SELECT neighborhoodId, SUM(meters) AS meters FROM trip_street_visits WHERE mode = :mode GROUP BY neighborhoodId ORDER BY meters DESC")
    fun tripStreetTotals(mode: String): Flow<List<NeighborhoodMeters>>
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTripStreets(rows: List<TripStreetVisit>)
    @Query("DELETE FROM trip_street_visits WHERE mode = :mode")
    suspend fun clearTripStreets(mode: String)
    @Query("SELECT EXISTS(SELECT 1 FROM street_visits WHERE mode = :mode AND activity IN ('car','walk','bike') AND dataset = :dataset AND streetId = :streetId AND bin = :bin)")
    suspend fun hasStreetVisit(mode: String, dataset: String, streetId: String, bin: Int): Boolean
    @Query("UPDATE route_sessions SET newStreetMeters = newStreetMeters + :meters WHERE mode = :mode")
    suspend fun addNewStreetMeters(mode: String, meters: Double)
    @Query("SELECT * FROM planned_routes ORDER BY createdAtMillis DESC")
    fun plannedRoutes(): Flow<List<PlannedRouteRow>>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putPlannedRoute(route: PlannedRouteRow)
    @Query("DELETE FROM planned_routes WHERE id = :id")
    suspend fun deletePlannedRoute(id: String)
    @Query("SELECT * FROM street_visits WHERE mode = 'real' AND dataset = :dataset")
    suspend fun realStreetVisits(dataset: String): List<StreetVisit>

    @Query("SELECT * FROM cells WHERE mode = 'real' AND (y > :afterY OR (y = :afterY AND x > :afterX)) ORDER BY y, x LIMIT 1024")
    suspend fun exportPage(afterY: Int, afterX: Int): List<ExploredCell>
    @Query("SELECT MIN(x) AS minX, MAX(x) AS maxX, MIN(y) AS minY, MAX(y) AS maxY FROM cells WHERE mode = :mode")
    suspend fun extent(mode: String): CoverageBounds
    @Query("SELECT MIN(x) AS minX, MAX(x) AS maxX, MIN(y) AS minY, MAX(y) AS maxY FROM cells WHERE mode = :mode AND y BETWEEN :minY AND :maxY AND x BETWEEN :minX AND :maxX GROUP BY (x - CASE WHEN x < 0 THEN :stride - 1 ELSE 0 END) / :stride, (y - CASE WHEN y < 0 THEN :stride - 1 ELSE 0 END) / :stride LIMIT 4001")
    suspend fun coverage(mode: String, minX: Int, maxX: Int, minY: Int, maxY: Int, stride: Int): List<CoverageBounds>
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertStreetVisits(visits: List<StreetVisit>)
    @Query("SELECT neighborhoodId, SUM(meters) AS meters FROM (SELECT neighborhoodId, streetId, bin, MAX(meters) AS meters FROM street_visits WHERE mode = :mode AND dataset = :dataset GROUP BY neighborhoodId, streetId, bin) GROUP BY neighborhoodId")
    fun streetTotals(mode: String, dataset: String): Flow<List<NeighborhoodMeters>>
    @Query("DELETE FROM street_visits WHERE mode = 'simulation'")
    suspend fun clearSimulatedVisits()
    @Query("SELECT * FROM saved_routes ORDER BY favorite DESC, startedAtMillis DESC, id")
    fun savedRoutes(): Flow<List<SavedRoute>>
    @Query("SELECT * FROM saved_routes WHERE expeditionId = :id ORDER BY startedAtMillis")
    fun expeditionRoutes(id: String): Flow<List<SavedRoute>>
    @Query("SELECT * FROM expeditions ORDER BY createdAtMillis DESC")
    fun expeditions(): Flow<List<ExpeditionRow>>
    @Query("SELECT * FROM expeditions WHERE id = :id")
    suspend fun expedition(id: String): ExpeditionRow?
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertExpedition(row: ExpeditionRow): Long
    @Query("SELECT * FROM saved_routes WHERE id = :id")
    suspend fun savedRoute(id: String): SavedRoute?
    @Query("SELECT * FROM saved_routes WHERE startedAtMillis = :start LIMIT 1")
    suspend fun savedByStart(start: Long): SavedRoute?
    @Query("UPDATE saved_routes SET title = :title WHERE id = :id")
    suspend fun renameSavedRoute(id: String, title: String)
    @Query("UPDATE saved_routes SET favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: String, favorite: Boolean)
    @Query("UPDATE saved_routes SET expeditionId = :expeditionId WHERE id = :id")
    suspend fun assignExpeditionRow(id: String, expeditionId: String?)
    @Query("DELETE FROM saved_routes WHERE id = :id")
    suspend fun deleteSavedRouteRow(id: String)
    @Query("DELETE FROM route_sessions WHERE mode = :mode")
    suspend fun deleteSession(mode: String)
    @Query("UPDATE route_sessions SET archivedId = :id WHERE mode = 'real'")
    suspend fun markArchived(id: String)
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSavedRoute(route: SavedRoute): Long
    @Query("SELECT * FROM route_sessions WHERE mode = 'real'")
    suspend fun realSession(): RouteSession?
    @Query("INSERT INTO route_samples(mode,sequence,latitude,longitude,elapsedSeconds,speedKmh,breakBefore,stopId,stopSeconds,accuracyMeters,accepted,rawSpeedMetersSecond,monotonicSeconds,streetIds) SELECT :id,sequence,latitude,longitude,elapsedSeconds,speedKmh,breakBefore,stopId,stopSeconds,accuracyMeters,accepted,rawSpeedMetersSecond,monotonicSeconds,streetIds FROM route_samples WHERE mode = 'real'")
    suspend fun copyRealSamples(id: String)
    @Query("INSERT INTO trip_cells(mode,y,x,elapsedSeconds) SELECT :id,y,x,elapsedSeconds FROM trip_cells WHERE mode = 'real'")
    suspend fun copyRealTrip(id: String)
    @Query("INSERT INTO trip_street_visits(mode,dataset,streetId,bin,neighborhoodId,meters) SELECT :id,dataset,streetId,bin,neighborhoodId,meters FROM trip_street_visits WHERE mode = 'real'")
    suspend fun copyRealTripStreets(id: String)
    /** Archive once, before the only mutable route slot is replaced. Interrupted trips remain marked incomplete. */
    @Transaction
    suspend fun archiveLastRealRoute(): String? {
        val session = realSession() ?: return null
        if (session.archivedId != null) return session.archivedId
        val points = samples("real")
        if (points.isEmpty()) return null
        val existing = savedByStart(session.startedAtMillis)
        val id = existing?.id ?: "saved:auto:${session.startedAtMillis}"
        if (existing == null) {
            val end = session.endedAtMillis ?: (session.startedAtMillis + (points.last().elapsedSeconds * 1000).toLong())
            putSession(session.copy(mode = id, endedAtMillis = end, archivedId = id))
            copyRealSamples(id)
            copyRealTrip(id)
            copyRealTripStreets(id)
            val date = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale("pt", "BR")).format(java.util.Date(session.startedAtMillis))
            val label = if (session.completed) "Percurso" else "Percurso interrompido"
            val summary = br.com.expoworld.core.TripSummary.from(RecordedTrace(session.startedAtMillis, end,
                points.map { it.toDomain() }, id, session.completed, session.newAreaSquareMeters, br.com.expoworld.core.ActivityKind.fromKey(session.activity)))
            insertSavedRoute(SavedRoute(id, "$label • $date", session.startedAtMillis, session.activity,
                distanceMeters = summary.distanceMeters, recordedSeconds = summary.recordedSeconds))
        }
        markArchived(id)
        return id
    }
    @Transaction
    suspend fun saveRealRoute(title: String, expectedStart: Long) {
        require(title.trim().length in 1..80)
        val session = checkNotNull(realSession())
        check(session.completed && session.startedAtMillis == expectedStart)
        check(samples("real").isNotEmpty())
        // Manual saving names the automatic archive, avoiding duplicate copies.
        val id = archiveLastRealRoute() ?: error("Percurso sem registros.")
        check(savedRoute(id) != null) { "Este percurso foi excluído do histórico." }
        renameSavedRoute(id, title.trim())
    }
    @Transaction
    suspend fun assignExpedition(id: String, expeditionId: String?) {
        require(expeditionId == null || expedition(expeditionId) != null) { "Expedição não encontrada." }
        assignExpeditionRow(id, expeditionId)
    }
    @Transaction
    suspend fun deleteSavedRoute(id: String) {
        require(id.startsWith("saved:"))
        deleteSavedRouteRow(id); deleteSamples(id); clearTrip(id); clearTripStreets(id); deleteSession(id)
    }
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTrip(cells: List<TripCell>)
    @Query("SELECT * FROM trip_cells WHERE mode = :mode")
    suspend fun allTrip(mode: String): List<TripCell>
    @Query("DELETE FROM trip_cells WHERE mode = :mode")
    suspend fun clearTrip(mode: String)
    @Query("SELECT * FROM trip_cells WHERE mode = :mode AND y BETWEEN :minY AND :maxY AND x BETWEEN :minX AND :maxX LIMIT 60001")
    suspend fun discoveries(mode: String, minX: Int, maxX: Int, minY: Int, maxY: Int): List<TripCell>
    @Query("SELECT * FROM trip_cells WHERE mode = :mode AND y BETWEEN :minY AND :maxY AND x BETWEEN :minX AND :maxX LIMIT :limit")
    suspend fun discoveriesBounded(mode: String, minX: Int, maxX: Int, minY: Int, maxY: Int, limit: Int): List<TripCell>
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(cells: List<ExploredCell>): List<Long>
    @Query("UPDATE route_sessions SET newAreaSquareMeters = newAreaSquareMeters + :area WHERE mode = :mode")
    suspend fun addNewArea(mode: String, area: Double)
    @Query("SELECT * FROM cells WHERE mode = :mode AND y BETWEEN :minY AND :maxY AND x BETWEEN :minX AND :maxX LIMIT :limit")
    suspend fun visible(mode: String, minX: Int, maxX: Int, minY: Int, maxY: Int, limit: Int): List<ExploredCell>
    @Query("SELECT COUNT(*) FROM cells WHERE mode = :mode")
    fun count(mode: String): Flow<Long>
    @Query("SELECT y, COUNT(*) AS cells FROM cells WHERE mode = :mode GROUP BY y")
    fun progressRows(mode: String): Flow<List<CellRowCount>>
    @Query("DELETE FROM cells WHERE mode = 'simulation'")
    suspend fun clearSimulation()
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSession(session: RouteSession)
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun putSample(sample: RouteSampleRow)
    @Query("DELETE FROM route_samples WHERE mode = :mode")
    suspend fun deleteSamples(mode: String)
    @Query("DELETE FROM route_sessions WHERE mode = 'simulation'")
    suspend fun deleteSimulationSession()
    @Query("UPDATE route_sessions SET completed = 1, endedAtMillis = :endedAt WHERE mode = :mode")
    suspend fun completeSession(mode: String, endedAt: Long)
    @Query("SELECT * FROM route_sessions WHERE mode = :mode AND completed = 1")
    fun completedSession(mode: String): Flow<RouteSession?>
    @Query("SELECT * FROM route_sessions WHERE mode = :mode")
    fun sessionFlow(mode: String): Flow<RouteSession?>
    @Transaction
    @Query("SELECT * FROM route_sessions WHERE mode = :mode")
    fun routeSnapshot(mode: String): Flow<RouteSnapshot?>
    @Query("SELECT * FROM route_samples WHERE mode = :mode ORDER BY sequence ASC")
    suspend fun samples(mode: String): List<RouteSampleRow>
    @Transaction
    suspend fun beginRoute(mode: String, startedAt: Long, activity: String = "car") {
        if (mode == "real") archiveLastRealRoute()
        deleteSamples(mode)
        clearTrip(mode)
        clearTripStreets(mode)
        putSession(RouteSession(mode, startedAt, null, false, activity, 0.0, newStreetMeters = 0.0))
    }
    @Transaction
    suspend fun saveAccepted(cells: List<ExploredCell>, sample: RouteSampleRow?, startedAt: Long?, activity: String = "car", visits: List<StreetVisit> = emptyList(), elapsed: Double = -1.0) {
        if (sample != null && startedAt != null) beginRoute(sample.mode, startedAt, activity)
        if (visits.isNotEmpty()) {
            val fresh = visits.distinctBy { listOf(it.mode, it.dataset, it.streetId, it.bin.toString()) }.filter { !hasStreetVisit(it.mode, it.dataset, it.streetId, it.bin) }
            insertTripStreets(fresh.map { TripStreetVisit(it.mode, it.dataset, it.streetId, it.bin, it.neighborhoodId, it.meters) })
            fresh.groupBy { it.mode }.forEach { (mode, rows) -> addNewStreetMeters(mode, rows.sumOf { it.meters }) }
            insertStreetVisits(visits)
        }
        val inserted = insert(cells)
        val fresh = cells.filterIndexed { index, _ -> inserted[index] != -1L }
        if (fresh.isNotEmpty()) {
            insertTrip(fresh.map { TripCell(it.mode, it.y, it.x, elapsed) })
            val area = br.com.expoworld.core.ExplorationProgress.fromRows(
                fresh.groupingBy { it.y }.eachCount().map { it.key to it.value.toLong() }).areaSquareMeters
            addNewArea(fresh.first().mode, area)
        }
        if (sample != null) {
            putSample(sample)
        }
    }
    @Transaction
    suspend fun finishRoute(mode: String, endedAt: Long, last: RouteSampleRow?) {
        if (last != null) putSample(last)
        completeSession(mode, endedAt)
        if (mode == "real") archiveLastRealRoute()
    }
    @Transaction
    suspend fun resetSimulation() {
        clearSimulation(); clearTripStreets("simulation"); clearTrip("simulation"); deleteSamples("simulation"); deleteSimulationSession(); clearSimulatedVisits()
    }
}

@Database(entities = [ExploredCell::class, TripCell::class, RouteSession::class, RouteSampleRow::class, SavedRoute::class, StreetVisit::class, PlannedRouteRow::class, ExpeditionRow::class, TripStreetVisit::class], version = 10, exportSchema = true)
abstract class ExplorationDatabase : RoomDatabase() { abstract fun cells(): ExplorationDao }



val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS route_sessions (mode TEXT NOT NULL PRIMARY KEY, startedAtMillis INTEGER NOT NULL, endedAtMillis INTEGER, completed INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS route_samples (mode TEXT NOT NULL, sequence INTEGER NOT NULL, latitude REAL NOT NULL, longitude REAL NOT NULL, elapsedSeconds REAL NOT NULL, speedKmh REAL, breakBefore INTEGER NOT NULL, stopId INTEGER NOT NULL, stopSeconds REAL NOT NULL, PRIMARY KEY(mode, sequence))")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE cells RENAME TO cells_v2")
        db.execSQL("CREATE TABLE cells (mode TEXT NOT NULL, y INTEGER NOT NULL, x INTEGER NOT NULL, PRIMARY KEY(mode, y, x))")
        db.execSQL("INSERT INTO cells(mode, y, x) SELECT mode, y*2+dy, x*2+dx FROM cells_v2 CROSS JOIN (SELECT 0 AS dy UNION ALL SELECT 1) CROSS JOIN (SELECT 0 AS dx UNION ALL SELECT 1)")
        db.execSQL("DROP TABLE cells_v2")
    }
}




class ExplorationRepository(private val dao: ExplorationDao) {
    fun routeInsights(mode: String): Flow<RouteInsights> = combine(dao.sessionFlow(mode), dao.tripStreetTotals(mode)) { route, totals ->
        RouteInsights(route?.newStreetMeters, null, totals)
    }
    fun savedRoutes() = dao.savedRoutes()
    fun expeditions() = dao.expeditions()
    fun expeditionRoutes(id: String) = dao.expeditionRoutes(id)
    @OptIn(ExperimentalCoroutinesApi::class)
    fun expeditionTrace(id: String): Flow<List<RecordedTrace>> = dao.expeditionRoutes(id).flatMapLatest { rows ->
        if (rows.isEmpty()) kotlinx.coroutines.flow.flowOf(emptyList()) else kotlinx.coroutines.flow.combine(rows.map { route(it.id) }) { values -> values.filterNotNull() }
    }
    suspend fun archiveLastRealRoute() = dao.archiveLastRealRoute()
    suspend fun renameSavedRoute(id: String, title: String) { require(title.trim().length in 1..80); dao.renameSavedRoute(id, title.trim()) }
    suspend fun setFavorite(id: String, favorite: Boolean) = dao.setFavorite(id, favorite)
    suspend fun deleteSavedRoute(id: String) = dao.deleteSavedRoute(id)
    suspend fun createExpedition(title: String): String {
        require(title.trim().length in 1..80)
        val id = java.util.UUID.randomUUID().toString()
        dao.insertExpedition(ExpeditionRow(id, title.trim(), System.currentTimeMillis()))
        return id
    }
    suspend fun assignExpedition(routeId: String, expeditionId: String?) = dao.assignExpedition(routeId, expeditionId)
    suspend fun saveRealRoute(title: String, expectedStart: Long) = dao.saveRealRoute(title, expectedStart)
    suspend fun visualCells(mode: String, bounds: CellBounds): List<br.com.expoworld.core.CellDiscovery> {
        val rows = dao.visible(mode, bounds.minX, bounds.maxX, bounds.minY, bounds.maxY, ExplorationConfig.MAX_VIEW_CELLS + 1)
        check(rows.size <= ExplorationConfig.MAX_VIEW_CELLS) { "Aproxime o mapa para mostrar a exploração desta região." }
        return rows.map { br.com.expoworld.core.CellDiscovery(Cell(it.x, it.y), it.firstDiscoveredAtMillis) }
    }
    suspend fun timeline(mode: String, bounds: CellBounds): List<br.com.expoworld.core.TripDiscovery> =
        dao.discoveries(mode, bounds.minX, bounds.maxX, bounds.minY, bounds.maxY).map {
            br.com.expoworld.core.TripDiscovery(Cell(it.x, it.y), it.elapsedSeconds)
        }
    suspend fun timeline(mode: String, bounds: CellBounds, limit: Int): List<br.com.expoworld.core.TripDiscovery> =
        dao.discoveriesBounded(mode, bounds.minX, bounds.maxX, bounds.minY, bounds.maxY, limit.coerceIn(1, 60_001)).map {
            br.com.expoworld.core.TripDiscovery(Cell(it.x, it.y), it.elapsedSeconds)
        }
    suspend fun extent(mode: String) = dao.extent(mode).domain()
    suspend fun coverage(mode: String, bounds: CellBounds): List<CellBounds> {
        var stride = 8
        while (true) {
            val rows = dao.coverage(mode, bounds.minX, bounds.maxX, bounds.minY, bounds.maxY, stride)
            if (rows.size <= 4000) return rows.mapNotNull { it.domain() }
            stride *= 2
        }
    }
    suspend fun discoveries(mode: String, bounds: CellBounds): List<Cell> {
        val cells = dao.discoveries(mode, bounds.minX, bounds.maxX, bounds.minY, bounds.maxY)
        return if (cells.size > ExplorationConfig.MAX_VIEW_CELLS) emptyList() else cells.map { Cell(it.x, it.y) }
    }
    suspend fun save(mode: String, cells: Set<Cell>) {
        // One Room transaction per accepted fix; committed before UI revision changes.
        dao.insert(cells.map { ExploredCell(mode, it.y, it.x) })
    }
    suspend fun saveAccepted(mode: String, cells: Set<Cell>, sample: TraceSample?, startedAt: Long? = null,
        activity: String = "car", visits: List<StreetVisit> = emptyList(),
        elapsed: Double = sample?.elapsedSeconds ?: -1.0, discoveredAt: Long = System.currentTimeMillis()) = dao.saveAccepted(
        cells.map { ExploredCell(mode, it.y, it.x, discoveredAt) }, sample?.toRow(mode), startedAt, activity, visits, elapsed
    )
    suspend fun beginRoute(mode: String) = dao.beginRoute(mode, System.currentTimeMillis())
    suspend fun finishRoute(mode: String, last: TraceSample?) = dao.finishRoute(mode, System.currentTimeMillis(), last?.toRow(mode))
    fun route(mode: String): Flow<RecordedTrace?> = dao.routeSnapshot(mode).map { snapshot ->
        snapshot?.let { RecordedTrace(it.session.startedAtMillis, it.session.endedAtMillis ?: it.session.startedAtMillis,
            it.samples.sortedBy { row -> row.sequence }.map { row -> row.toDomain() }, mode, it.session.completed, it.session.newAreaSquareMeters, br.com.expoworld.core.ActivityKind.fromKey(it.session.activity)) }
    }.flowOn(Dispatchers.Default)
    fun completedRoute(mode: String): Flow<RecordedTrace?> = route(mode).map { it?.takeIf { data -> data.completed } }
    fun progress(mode: String): Flow<br.com.expoworld.core.ExplorationProgress> = dao.progressRows(mode).map { rows ->
        br.com.expoworld.core.ExplorationProgress.fromRows(rows.map { it.y to it.cells }).copy(mode = mode)
    }.flowOn(Dispatchers.Default)
    suspend fun visible(mode: String, bounds: CellBounds): List<Cell> {
        val rows = dao.visible(mode, bounds.minX, bounds.maxX, bounds.minY, bounds.maxY, ExplorationConfig.MAX_VIEW_CELLS + 1)
        check(rows.size <= ExplorationConfig.MAX_VIEW_CELLS) { "Aproxime o mapa para mostrar a exploração desta região." }
        return rows.map { Cell(it.x, it.y) }
    }
    fun count(mode: String) = dao.count(mode)
    suspend fun clearSimulation() = dao.resetSimulation()
}
