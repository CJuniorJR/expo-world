package br.com.expoworld.storage

import androidx.room.*
import br.com.expoworld.core.Cell
import br.com.expoworld.core.CellBounds
import br.com.expoworld.core.ExplorationConfig
import br.com.expoworld.core.TraceConfig
import br.com.expoworld.core.TraceSample
import br.com.expoworld.core.RecordedTrace
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi

@Entity(tableName = "cells", primaryKeys = ["mode", "y", "x"])
data class ExploredCell(val mode: String, val y: Int, val x: Int)

@Entity(tableName = "route_sessions")
data class RouteSession(@PrimaryKey val mode: String, val startedAtMillis: Long, val endedAtMillis: Long?, val completed: Boolean)

@Entity(tableName = "route_samples", primaryKeys = ["mode", "sequence"])
data class RouteSampleRow(
    val mode: String, val sequence: Int, val latitude: Double, val longitude: Double,
    val elapsedSeconds: Double, val speedKmh: Double?, val breakBefore: Boolean,
    val stopId: Int, val stopSeconds: Double
)

data class RouteSnapshot(
    @Embedded val session: RouteSession,
    @Relation(parentColumn = "mode", entityColumn = "mode") val samples: List<RouteSampleRow>
)
data class CellRowCount(val y: Int, val cells: Long)

@Entity(tableName = "saved_routes")
data class SavedRoute(@PrimaryKey val id: String, val title: String, val startedAtMillis: Long)

private fun RouteSampleRow.toDomain() = TraceSample(sequence, latitude, longitude, elapsedSeconds, speedKmh, breakBefore, stopId, stopSeconds)
private fun TraceSample.toRow(mode: String) = RouteSampleRow(mode, sequence, latitude, longitude,
    elapsedSeconds, speedKmh, breakBefore, stopId, stopSeconds)

@Dao
interface ExplorationDao {
    @Query("SELECT * FROM saved_routes ORDER BY startedAtMillis DESC, id")
    fun savedRoutes(): Flow<List<SavedRoute>>
    @Insert
    suspend fun insertSavedRoute(route: SavedRoute)
    @Query("SELECT * FROM route_sessions WHERE mode = 'real'")
    suspend fun realSession(): RouteSession?
    @Transaction
    suspend fun saveRealRoute(title: String, expectedStart: Long) {
        require(title.trim().length in 1..80)
        val session = checkNotNull(realSession())
        check(session.completed && session.startedAtMillis == expectedStart)
        val points = samples("real")
        check(points.isNotEmpty())
        val id = "saved:" + java.util.UUID.randomUUID().toString()
        putSession(session.copy(mode = id))
        points.forEach { putSample(it.copy(mode = id)) }
        insertSavedRoute(SavedRoute(id, title.trim(), session.startedAtMillis))
    }
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(cells: List<ExploredCell>)
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
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSample(sample: RouteSampleRow)
    @Query("DELETE FROM route_samples WHERE mode = :mode")
    suspend fun deleteSamples(mode: String)
    @Query("DELETE FROM route_sessions WHERE mode = 'simulation'")
    suspend fun deleteSimulationSession()
    @Query("DELETE FROM route_samples WHERE mode = :mode AND sequence < :minSequence")
    suspend fun trimSamples(mode: String, minSequence: Int)
    @Query("UPDATE route_sessions SET completed = 1, endedAtMillis = :endedAt WHERE mode = :mode")
    suspend fun completeSession(mode: String, endedAt: Long)
    @Query("SELECT * FROM route_sessions WHERE mode = :mode AND completed = 1")
    fun completedSession(mode: String): Flow<RouteSession?>
    @Transaction
    @Query("SELECT * FROM route_sessions WHERE mode = :mode")
    fun routeSnapshot(mode: String): Flow<RouteSnapshot?>
    @Query("SELECT * FROM route_samples WHERE mode = :mode ORDER BY sequence ASC LIMIT 20000")
    suspend fun samples(mode: String): List<RouteSampleRow>
    @Transaction
    suspend fun beginRoute(mode: String, startedAt: Long) {
        deleteSamples(mode)
        putSession(RouteSession(mode, startedAt, null, false))
    }
    @Transaction
    suspend fun saveAccepted(cells: List<ExploredCell>, sample: RouteSampleRow?, startedAt: Long?) {
        if (sample != null && startedAt != null) beginRoute(sample.mode, startedAt)
        insert(cells)
        if (sample != null) {
            putSample(sample)
            trimSamples(sample.mode, sample.sequence - TraceConfig.MAX_SAMPLES + 1)
        }
    }
    @Transaction
    suspend fun finishRoute(mode: String, endedAt: Long, last: RouteSampleRow?) {
        if (last != null) { putSample(last); trimSamples(mode, last.sequence - TraceConfig.MAX_SAMPLES + 1) }
        completeSession(mode, endedAt)
    }
    @Transaction
    suspend fun resetSimulation() {
        clearSimulation(); deleteSamples("simulation"); deleteSimulationSession()
    }
}

@Database(entities = [ExploredCell::class, RouteSession::class, RouteSampleRow::class, SavedRoute::class], version = 4, exportSchema = true)
abstract class ExplorationDatabase : RoomDatabase() { abstract fun cells(): ExplorationDao }

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS saved_routes (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, startedAtMillis INTEGER NOT NULL)")
    }
}

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
    fun savedRoutes() = dao.savedRoutes()
    suspend fun saveRealRoute(title: String, expectedStart: Long) = dao.saveRealRoute(title, expectedStart)
    suspend fun save(mode: String, cells: Set<Cell>) {
        // One Room transaction per accepted fix; committed before UI revision changes.
        dao.insert(cells.map { ExploredCell(mode, it.y, it.x) })
    }
    suspend fun saveAccepted(mode: String, cells: Set<Cell>, sample: TraceSample?, startedAt: Long? = null) = dao.saveAccepted(
        cells.map { ExploredCell(mode, it.y, it.x) }, sample?.toRow(mode), startedAt
    )
    suspend fun beginRoute(mode: String) = dao.beginRoute(mode, System.currentTimeMillis())
    suspend fun finishRoute(mode: String, last: TraceSample?) = dao.finishRoute(mode, System.currentTimeMillis(), last?.toRow(mode))
    fun route(mode: String): Flow<RecordedTrace?> = dao.routeSnapshot(mode).map { snapshot ->
        snapshot?.let { RecordedTrace(it.session.startedAtMillis, it.session.endedAtMillis ?: it.session.startedAtMillis,
            it.samples.sortedBy { row -> row.sequence }.map { row -> row.toDomain() }, mode, it.session.completed) }
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
