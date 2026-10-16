package br.com.expoworld.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.expoworld.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PocketPlannerTest {
    private fun fixture(name: String): PocketPath = InstrumentationRegistry.getInstrumentation().context.assets
        .open("pocket/$name.json").bufferedReader().use { PocketRouting.parse(JSONObject(it.readText())) }
    @Test fun threeProfilesParseConnectedLoopsAndCorrectStepIndices() {
        val paths=listOf("pedestrian","bicycle","auto").map { fixture(it) }
        paths.forEach { path ->
            assertTrue(path.points.first().distance(path.points.last())<100)
            assertTrue(path.steps.isNotEmpty())
            assertTrue(path.steps.all { it.pointIndex in path.points.indices })
            assertTrue(path.points.all { it.latitude in -25.46..-25.41 && it.longitude in -49.30..-49.25 })
        }
        assertEquals(3,paths.map { it.distanceMeters }.distinct().size)
    }
    @Test fun plannedRouteRoundTripAndDeleteNeverGrantExplorationCredit() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val db=Room.inMemoryDatabaseBuilder(context,ExplorationDatabase::class.java).build()
        try {
            val path=fixture("pedestrian")
            val plan=PocketPlan("fixture",PocketRequest(path.points.first(),45,ActivityKind.WALK,PocketPurpose.EXPLORE),path,100.0)
            val store=PocketPlanStore(db.cells())
            store.save("Minha volta",plan)
            val saved=store.saved.first().single()
            assertEquals("Minha volta",saved.title)
            assertEquals(plan,saved.plan)
            assertEquals(0L,db.cells().count("real").first())
            assertTrue(db.cells().realStreetVisits("v1").isEmpty())
            assertNull(db.cells().routeSnapshot("real").first())
            store.delete(saved.id)
            assertTrue(store.saved.first().isEmpty())
            assertTrue(runCatching { PocketPlanCodec.decode("{}") }.isFailure)
        } finally { db.close() }
    }
}
