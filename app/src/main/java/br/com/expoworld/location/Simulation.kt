package br.com.expoworld.location

import android.os.SystemClock
import br.com.expoworld.WorldApp
import br.com.expoworld.core.SimulatedRoute
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.update

/** Debug-only caller. Uses the same filter, rasterizer and repository as GPS.
 * Deliberately runs only while the screen is visible; not a test of background GPS.
 */
class Simulation(private val app: WorldApp) {
    private var job: Job? = null
    fun start(scope: CoroutineScope) {
        if (app.tracking.value.active) return
        app.tracking.update { it.copy(active = true, simulated = true, position = null, liveHead = null) }
        job = scope.launch {
            val processor = ExplorationProcessor(app, "simulation")
            try {
                app.repository.clearSimulation()
                processor.begin()
                val origin = SystemClock.elapsedRealtimeNanos() / 1e9
                for (i in 0..SimulatedRoute.LAST_INDEX) {
                    val now = origin + i // Isolated virtual clock: 1 GPS second per frame.
                    processor.ingest(SimulatedRoute.fix(i, now), now)
                    delay(SimulatedRoute.PLAYBACK_DELAY_MILLIS)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { app.tracking.update { it.copy(message = "Falha ao salvar simulação. Verifique o espaço livre.") } }
            finally {
                withContext(NonCancellable) {
                    runCatching { processor.finish() }.onFailure {
                        app.tracking.update { it.copy(message = "Não foi possível concluir o rastro simulado. Confira o espaço livre.") }
                    }
                    app.tracking.update { it.copy(active = false) }
                }
            }
        }
    }
    fun stop() { job?.cancel(); job = null }
}
