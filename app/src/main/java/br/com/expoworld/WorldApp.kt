package br.com.expoworld

import android.app.Application
import androidx.room.Room
import br.com.expoworld.core.Fix
import br.com.expoworld.core.TraceSample
import br.com.expoworld.core.ActivityKind
import br.com.expoworld.storage.*
import kotlinx.coroutines.flow.MutableStateFlow

data class TrackingState(
    val active: Boolean = false,
    val simulated: Boolean = false,
    val position: Fix? = null,
    val liveHead: TraceSample? = null,
    val activity: ActivityKind = ActivityKind.CAR,
    val message: String = "Solicite localização para ver sua posição. A exploração começa ao iniciar uma viagem.",
    val revision: Long = 0
)

class WorldApp : Application() {
    val tracking = MutableStateFlow(TrackingState())
    val database by lazy { Room.databaseBuilder(this, ExplorationDatabase::class.java, "exploration.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build() }
    val repository by lazy { ExplorationRepository(database.cells()) }
    val city by lazy { CuritibaRepository(database.cells(), assets) }
}
