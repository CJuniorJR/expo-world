package br.com.expoworld

import android.app.Application
import androidx.room.Room
import br.com.expoworld.core.Fix
import br.com.expoworld.core.TraceSample
import br.com.expoworld.core.ActivityKind
import br.com.expoworld.core.ExplorerTheme
import br.com.expoworld.storage.*
import kotlinx.coroutines.flow.MutableStateFlow

data class TrackingState(
    val active: Boolean = false,
    val paused: Boolean = false,
    val simulated: Boolean = false,
    val position: Fix? = null,
    val gpsFix: Fix? = null,
    val liveHead: TraceSample? = null,
    val activity: ActivityKind = ActivityKind.CAR,
    val message: String = "Solicite localização para ver sua posição. A exploração começa ao iniciar uma viagem.",
    val revision: Long = 0
)

class WorldApp : Application() {
    var storageBusy = false
    val tracking = MutableStateFlow(TrackingState())
    private val appearance by lazy { getSharedPreferences("appearance", MODE_PRIVATE) }
    val theme by lazy { MutableStateFlow(ExplorerTheme.fromKey(appearance.getString("theme", null))) }
    fun selectTheme(value: ExplorerTheme) {
        appearance.edit().putString("theme", value.key).apply()
        theme.value = value
    }
    val database by lazy { Room.databaseBuilder(this, ExplorationDatabase::class.java, "exploration.db").addMigrations(*ALL_MIGRATIONS).build() }
    val repository by lazy { ExplorationRepository(database.cells()) }
    val pocket by lazy { PocketPlanner(this) }
    val city by lazy { CuritibaRepository(database.cells(), assets) }
}
