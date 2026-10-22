package br.com.expoworld.storage

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import br.com.expoworld.core.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.StringWriter

@RunWith(AndroidJUnit4::class)
class HistoryBackupTest {
    private val context: Context by lazy {
        val base=ApplicationProvider.getApplicationContext<Context>()
        object : android.content.ContextWrapper(base) {
            private val prefix="history-test-${java.util.UUID.randomUUID()}-"
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences = super.getSharedPreferences(prefix+name, mode)
        }
    }
    private fun database() = Room.inMemoryDatabaseBuilder(context, ExplorationDatabase::class.java).build()
    private fun point(sequence: Int = 0) = TraceSample(sequence, -25.43, -49.27 + sequence * .0001, sequence.toDouble(), 12.0, sequence == 0, -1, 0.0,
        accuracyMeters = 5.0, accepted = true, rawSpeedMetersSecond = 3.33, monotonicSeconds = 100.0 + sequence, streetIds = "v1:street")

    @Test fun automaticHistoryNamesOncePreservesInterruptedAndDoesNotResurrectDeletedTrips() = runBlocking {
        val db = database()
        try {
            val repo = ExplorationRepository(db.cells())
            repo.saveAccepted("real", setOf(Cell(0,0)), point(), 1000L)
            repo.finishRoute("real", null)
            repo.archiveLastRealRoute()
            repo.saveRealRoute("Minha volta",1000)
            val saved = repo.savedRoutes().first().single()
            assertEquals("Minha volta",saved.title)
            assertNotNull(saved.distanceMeters)
            repo.setFavorite(saved.id,true)
            assertTrue(repo.savedRoutes().first().single().favorite)
            repo.deleteSavedRoute(saved.id)
            repo.archiveLastRealRoute()
            assertTrue(repo.savedRoutes().first().isEmpty())
            assertEquals(1L,repo.count("real").first())
            assertNotNull(repo.route("real").first())
            repo.saveAccepted("real", emptySet(), point(), 2000)
            repo.saveAccepted("real", emptySet(), point(1))
            // Beginning the next trip archives an interrupted predecessor before replacing its slot.
            repo.saveAccepted("real", emptySet(), point(), 3000)
            val interrupted = repo.savedRoutes().first().single()
            assertFalse(repo.route(interrupted.id).first()!!.completed)
            assertEquals(2,repo.route(interrupted.id).first()!!.samples.size)
            assertEquals(2000L,interrupted.startedAtMillis)
        } finally { db.close() }
    }
    @Test fun streetNoveltyIsSharedAcrossActivitiesAndExpeditionDeletionNeverRemovesExploration() = runBlocking {
        val db = database()
        try {
            val repo=ExplorationRepository(db.cells())
            val visit=StreetVisit("real","walk","v1","street",0,"bairro",10.0)
            repo.saveAccepted("real",emptySet(),point(),1000,"walk",listOf(visit,visit))
            repo.finishRoute("real",null)
            val saved=repo.savedRoutes().first().single()
            assertEquals(10.0,repo.routeInsights(saved.id).first().newStreetMeters!!,0.0)
            assertEquals(listOf(NeighborhoodMeters("bairro",10.0)),repo.routeInsights(saved.id).first().neighborhoods)
            val expedition=repo.createExpedition("Fim de semana")
            repo.assignExpedition(saved.id,expedition)
            assertEquals(1,repo.expeditionTrace(expedition).first().size)
            repo.saveAccepted("real",emptySet(),point(),2000,"bike",listOf(visit.copy(activity="bike")))
            assertEquals(0.0,repo.routeInsights("real").first().newStreetMeters!!,0.0)
            repo.deleteSavedRoute(saved.id)
            assertTrue(repo.expeditionTrace(expedition).first().isEmpty())
            assertEquals(10.0,db.cells().streetTotals("real","v1").first().single().meters,0.0)
        } finally { db.close() }
    }
    @Test fun completeBackupPreviewsThenMergesMetadataPlansCreditsAndLegacyCellsIdempotently() = runBlocking {
        val source=database();val target=database()
        val file=File.createTempFile("backup-test-",".expoworld",context.cacheDir)
        try {
            val repo=ExplorationRepository(source.cells())
            val visit=StreetVisit("real","walk","v1","street",0,"bairro",10.0)
            repo.saveAccepted("real",setOf(Cell(1,2)),point(),1000,"walk",listOf(visit))
            repo.saveAccepted("real",emptySet(),point(1));repo.finishRoute("real",null)
            val saved=repo.savedRoutes().first().single();repo.renameSavedRoute(saved.id,"Parque");repo.setFavorite(saved.id,true)
            val expedition=repo.createExpedition("Meu bairro");repo.assignExpedition(saved.id,expedition)
            repo.save("simulation",setOf(Cell(50,50)))
            val response=InstrumentationRegistry.getInstrumentation().context.assets.open("pocket/pedestrian.json").bufferedReader().use { JSONObject(it.readText()) }
            val path=PocketRouting.parse(response)
            PocketPlanStore(source.cells()).save("Domingo",PocketPlan("fixture",PocketRequest(path.points.first(),45,ActivityKind.WALK,PocketPurpose.EXPLORE),path,10.0))
            val exporter=BackupManager(context,source,{ExplorerTheme.fromKey(null)},{})
            assertEquals(1L,exporter.export(Uri.fromFile(file)))
            val importer=BackupManager(context,target,{ExplorerTheme.fromKey(null)},{})
            ExplorationRepository(target.cells()).save("real",setOf(Cell(5,5)))
            val preview=importer.prepareRestore(Uri.fromFile(file))
            assertEquals(3,preview.formatVersion);assertEquals(1L,preview.routes);assertEquals(1L,preview.cells)
            assertEquals(1L,preview.plannedRoutes);assertEquals(1L,preview.expeditions);assertEquals(1L,preview.streetVisits)
            assertTrue(target.cells().savedRoutes().first().isEmpty())
            // The user-selected file may change; prepared restore uses the validated immutable private copy.
            val original=file.readText();file.writeText("corrupt")
            assertEquals(1L,importer.restorePrepared())
            val targetRepo=ExplorationRepository(target.cells())
            val restored=targetRepo.savedRoutes().first().single()
            assertEquals("Parque",restored.title);assertTrue(restored.favorite);assertEquals(expedition,restored.expeditionId)
            assertEquals(repo.route(saved.id).first(),targetRepo.route(restored.id).first())
            assertEquals(10.0,targetRepo.routeInsights(restored.id).first().newStreetMeters!!,0.0)
            assertEquals(1,PocketPlanStore(target.cells()).saved.first().size)
            assertEquals(0L,targetRepo.count("simulation").first())
            targetRepo.renameSavedRoute(restored.id,"Título local preservado")
            file.writeText(original)
            assertEquals(0L,importer.restore(Uri.fromFile(file)))
            assertEquals("Título local preservado",targetRepo.savedRoutes().first().single().title)
            assertEquals(2L,targetRepo.count("real").first())
            val writer=StringWriter();ExplorationBackup.Encoder(writer).apply { cell(Cell(7,7),3000);finish() }
            file.writeText(writer.toString())
            assertEquals(2,importer.prepareRestore(Uri.fromFile(file)).formatVersion)
            assertEquals(1L,importer.restorePrepared())
            assertEquals(0L,importer.restore(Uri.fromFile(file)))
            assertEquals(1,targetRepo.savedRoutes().first().size)
        } finally { source.close();target.close();file.delete() }
    }
    @Test fun rejectedReadingsKeepChronologyWhileNonfiniteOptionalMetadataBecomesUnknown() = runBlocking {
        val source=database();val target=database();val file=File.createTempFile("raw-backup-",".expoworld",context.cacheDir)
        try {
            val repo=ExplorationRepository(source.cells())
            repo.saveAccepted("real",emptySet(),point(),1000)
            repo.saveAccepted("real",emptySet(),point(1).copy(latitude=Double.POSITIVE_INFINITY,accepted=false,breakBefore=true,
                accuracyMeters=Double.POSITIVE_INFINITY,rawSpeedMetersSecond=Double.NaN,monotonicSeconds=Double.POSITIVE_INFINITY))
            repo.finishRoute("real",null)
            BackupManager(context,source,{ExplorerTheme.fromKey(null)},{}).export(Uri.fromFile(file))
            assertTrue(file.readText().contains("\"Infinity\""))
            BackupManager(context,target,{ExplorerTheme.fromKey(null)},{}).restore(Uri.fromFile(file))
            val saved=target.cells().savedRoutes().first().single()
            val raw=ExplorationRepository(target.cells()).route(saved.id).first()!!.samples.last()
            assertFalse(raw.accepted);assertTrue(raw.latitude.isInfinite());assertNull(raw.accuracyMeters)
            assertNull(raw.rawSpeedMetersSecond);assertNull(raw.monotonicSeconds)
            assertEquals(1,raw.sequence);assertEquals(1.0,raw.elapsedSeconds,0.0)
            assertEquals(0L,target.cells().count("real").first())
        } finally { source.close();target.close();file.delete() }
    }
    @Test fun corruptAndBrokenLinkedBackupsAreRejectedBeforeAnyMerge() = runBlocking {
        val db=database();val file=File.createTempFile("invalid-backup-",".expoworld",context.cacheDir)
        try {
            val manager=BackupManager(context,db,{ExplorerTheme.fromKey(null)},{})
            file.writeText("EXPO_WORLD\t3\t10\ncells\t[\"real\",1,1,null]\nEND\t1\tbad\n")
            assertTrue(runCatching { manager.prepareRestore(Uri.fromFile(file)) }.isFailure)
            val writer=StringWriter();CompleteBackupCodec.Encoder(writer).apply {
                row("saved_routes",JSONArray(listOf("saved:missing","Broken",1000,"car",0,null,null,null)));finish()
            }
            file.writeText(writer.toString())
            assertTrue(runCatching { manager.prepareRestore(Uri.fromFile(file)) }.isFailure)
            assertEquals(0L,db.cells().count("real").first())
            assertTrue(db.cells().savedRoutes().first().isEmpty())
            assertTrue(runCatching { manager.restorePrepared() }.isFailure)
        } finally { db.close();file.delete() }
    }
}
