package br.com.expoworld.storage

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import br.com.expoworld.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PersistenceTest {
    @Test fun firstDiscoveryDateAndReplayTimingSurviveRevisitAndNewTrip() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ExplorationDatabase::class.java).build()
        try {
            val repo = ExplorationRepository(db.cells())
            val bounds = CellBounds(-1, 3, -1, 3)
            repo.save("real", setOf(Cell(0, 0))) // Legacy/import without date.
            val point = TraceSample(0, 0.0, 0.0, 0.0, 20.0, true, -1, 0.0)
            repo.saveAccepted("real", setOf(Cell(0, 0), Cell(1, 1)), point, 1000, elapsed = 5.0, discoveredAt = 1500)
            repo.saveAccepted("real", setOf(Cell(1, 1)), null, elapsed = 10.0, discoveredAt = 2000)
            assertEquals(1500L, repo.visualCells("real", bounds).first { it.cell == Cell(1, 1) }.firstDiscoveredAtMillis)
            assertNull(repo.visualCells("real", bounds).first { it.cell == Cell(0, 0) }.firstDiscoveredAtMillis)
            assertEquals(listOf(TripDiscovery(Cell(1, 1), 5.0)), repo.timeline("real", bounds))
            repo.finishRoute("real", null)
            repo.saveAccepted("real", setOf(Cell(1, 1), Cell(2, 2)), point, 3000, elapsed = 0.0, discoveredAt = 3000)
            assertEquals(listOf(TripDiscovery(Cell(2, 2), 0.0)), repo.timeline("real", bounds))
            assertEquals(1500L, repo.visualCells("real", bounds).first { it.cell == Cell(1, 1) }.firstDiscoveredAtMillis)
        } finally { db.close() }
    }
    @Test fun highlightsTrackOnlyFreshCellsAndBackupMergeDoesNotReplaceRoute() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ExplorationDatabase::class.java).build()
        try {
            val dao = db.cells(); val repo = ExplorationRepository(dao)
            val bounds = CellBounds(-100, 100, -100, 100)
            repo.save("real", setOf(Cell(-1, -1)))
            val point = TraceSample(0, 0.0, 0.0, 0.0, 10.0, true, -1, 0.0)
            repo.saveAccepted("real", setOf(Cell(-1, -1), Cell(0, 0)), point, 1000)
            repo.saveAccepted("real", setOf(Cell(0, 0), Cell(1, 0)), null)
            assertEquals(setOf(Cell(0, 0), Cell(1, 0)), repo.discoveries("real", bounds).toSet())
            assertEquals(CellBounds(-1, 1, -1, 0), repo.extent("real"))
            val saved = repo.route("real").first()
            // Backup import calls insert directly, without beginning a trip.
            assertEquals(1, dao.insert(listOf(ExploredCell("real", 0, 0), ExploredCell("real", 5, 5))).count { it != -1L })
            assertEquals(saved, repo.route("real").first())
            assertEquals(setOf(Cell(0, 0), Cell(1, 0)), repo.discoveries("real", bounds).toSet())
            assertEquals(4, dao.exportPage(Int.MIN_VALUE, Int.MIN_VALUE).size)
            repo.saveAccepted("simulation", setOf(Cell(8, 8)), point, 2000)
            repo.clearSimulation()
            assertEquals(setOf(Cell(0, 0), Cell(1, 0)), repo.discoveries("real", bounds).toSet())
            repo.saveAccepted("real", setOf(Cell(5, 5)), point, 3000)
            assertTrue(repo.discoveries("real", bounds).isEmpty())
        } finally { db.close() }
    }
    @Test fun interruptedRouteAndUniqueNewAreaSurviveReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "interrupted-test.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, ExplorationDatabase::class.java, name).build()
        var db = open()
        try {
            var repo = ExplorationRepository(db.cells())
            repo.save("real", setOf(Cell(0, 0))) // Previously explored.
            val point = TraceSample(0, 0.0, 0.0, 0.0, 20.0, true, -1, 0.0)
            repo.saveAccepted("real", setOf(Cell(0, 0), Cell(1, 0)), point, 1000)
            repo.saveAccepted("real", setOf(Cell(1, 0), Cell(2, 0)), null)
            repo.saveAccepted("real", setOf(Cell(2, 0)), null)
            val expected = ExplorationProgress.fromRows(listOf(0 to 2L)).areaSquareMeters
            assertEquals(expected, repo.route("real").first()!!.newAreaSquareMeters!!, .00001)
            db.close(); db = open(); repo = ExplorationRepository(db.cells())
            val recovered = repo.route("real").first()!!
            assertTrue(recovered.isInterrupted(false))
            assertEquals(listOf(point), recovered.samples)
            assertEquals(expected, recovered.newAreaSquareMeters!!, .00001)
            repo.finishRoute("real", null)
            assertFalse(repo.route("real").first()!!.isInterrupted(false))
            repo.saveAccepted("real", setOf(Cell(2, 0)), point, 2000)
            assertEquals(0.0, repo.route("real").first()!!.newAreaSquareMeters!!, 0.0)
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun explorationSurvivesReopenAndSimulationIsIsolated() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "persistence-test.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, ExplorationDatabase::class.java, name).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6).build()
        var db = open()
        try {
            val first = Fix(-23.5615, -46.6559, 5.0, 100.0)
            val cells = Revealer.cells(first, null)
            ExplorationRepository(db.cells()).save("real", cells)
            ExplorationRepository(db.cells()).save("real", cells) // Primary-key deduplication.
            ExplorationRepository(db.cells()).save("simulation", cells)
            val trace = TraceSample(0, first.latitude, first.longitude, 0.0, 54.0, true, -1, 0.0)
            val repoBefore = ExplorationRepository(db.cells())
            repoBefore.beginRoute("real")
            repoBefore.saveAccepted("real", cells, trace)
            assertNull(repoBefore.completedRoute("real").first())
            repoBefore.finishRoute("real", null)
            db.close()
            db = open()
            val bounds = Geo.bounds(-23.57, -46.67, -23.55, -46.64)
            val repo = ExplorationRepository(db.cells())
            assertEquals(cells, repo.visible("real", bounds).toSet())
            assertEquals(listOf(trace), repo.completedRoute("real").first()!!.samples)
            assertEquals(cells, repo.visible("simulation", bounds).toSet())
            repo.clearSimulation()
            assertTrue(repo.visible("simulation", bounds).isEmpty())
            assertEquals(cells, repo.visible("real", bounds).toSet())
            assertNotNull(repo.completedRoute("real").first())
            repo.beginRoute("real")
            assertNull(repo.completedRoute("real").first())
            assertTrue(db.cells().samples("real").isEmpty())
            assertEquals(cells, repo.visible("real", bounds).toSet())
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun migrationPreservesExistingExploration() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-test.db"
        context.deleteDatabase(name)
        val cell = Geo.cell(Geo.project(-23.5615, -46.6559))
        val oldCell = Cell(Math.floorDiv(cell.x, 2), Math.floorDiv(cell.y, 2))
        val expanded = (0..1).flatMap { y -> (0..1).map { x -> Cell(oldCell.x * 2 + x, oldCell.y * 2 + y) } }.toSet()
        val old = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        old.execSQL("CREATE TABLE cells (mode TEXT NOT NULL, y INTEGER NOT NULL, x INTEGER NOT NULL, PRIMARY KEY(mode, y, x))")
        old.execSQL("INSERT INTO cells(mode, y, x) VALUES('real', ?, ?)", arrayOf(oldCell.y, oldCell.x))
        old.version = 1
        old.close()
        val upgraded = Room.databaseBuilder(context, ExplorationDatabase::class.java, name).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6).build()
        try {
            val repo = ExplorationRepository(upgraded.cells())
            assertEquals(expanded, repo.visible("real", Geo.bounds(-23.57, -46.67, -23.55, -46.64)).toSet())
            assertNull(repo.completedRoute("real").first())
            repo.beginRoute("real")
            repo.finishRoute("real", null)
            assertNotNull(repo.completedRoute("real").first())
        } finally { upgraded.close(); context.deleteDatabase(name) }
    }
    @Test fun boundedTraceRetainsExplorationAndKeepsModesSeparate() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ExplorationDatabase::class.java).build()
        try {
            val dao = db.cells()
            val repo = ExplorationRepository(dao)
            val sample = TraceSample(0, 0.0, 0.0, 0.0, 0.0, true, 0, 0.0)
            repo.beginRoute("real")
            repo.beginRoute("simulation")
            repo.saveAccepted("real", setOf(Cell(0, 0)), sample)
            repo.saveAccepted("simulation", emptySet(), sample)
            repo.saveAccepted("real", emptySet(), sample.copy(sequence = TraceConfig.MAX_SAMPLES + 1, elapsedSeconds = 100.0))
            assertEquals(1, dao.samples("real").size)
            assertEquals(1, dao.samples("simulation").size)
            assertEquals(setOf(Cell(0, 0)), repo.visible("real", CellBounds(-1, 1, -1, 1)).toSet())
        } finally { db.close() }
    }
    @Test fun liveSnapshotIsConsistentAndFirstPointAtomicallyReplacesRoute() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ExplorationDatabase::class.java).build()
        try {
            val repo = ExplorationRepository(db.cells())
            val old = TraceSample(0, 0.0, 0.0, 0.0, 36.0, true, -1, 0.0)
            repo.beginRoute("real")
            repo.saveAccepted("real", setOf(Cell(0, 0)), old)
            repo.finishRoute("real", null)
            assertTrue(repo.route("real").first()!!.completed)
            val fresh = old.copy(latitude = 1.0, speedKmh = 18.0)
            repo.saveAccepted("real", setOf(Cell(1, 1)), fresh, 3000)
            val snapshot = repo.route("real").first()!!
            assertFalse(snapshot.completed)
            assertEquals(3000L, snapshot.startedAtMillis)
            assertEquals(listOf(fresh), snapshot.samples)
            assertEquals(setOf(Cell(0, 0), Cell(1, 1)), repo.visible("real", CellBounds(-1, 2, -1, 2)).toSet())
            assertEquals(2L, repo.progress("real").first().cells)
            assertEquals("simulation", repo.progress("simulation").first().mode)
        } finally { db.close() }
    }
}
