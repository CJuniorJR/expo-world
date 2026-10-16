package br.com.expoworld.location

import android.os.SystemClock
import br.com.expoworld.WorldApp
import br.com.expoworld.core.SimulatedRoute
import br.com.expoworld.core.SimulationScenario
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.update

/** Debug-only caller. Uses the same filter, rasterizer and repository as GPS.
 * Deliberately runs only while the screen is visible; not a test of background GPS.
 */
class Simulation(private val app: WorldApp) {
    private var job: Job? = null
    private var pauseRevision = 0
    fun togglePause() {
        if (!app.tracking.value.active) return
        if (!app.tracking.value.paused) pauseRevision++
        app.tracking.update { it.copy(paused = !it.paused, message = "MODO SIMULADO • pausa alterada") }
    }
    fun start(scope: CoroutineScope, scenario: SimulationScenario = SimulationScenario.NORMAL) {
        if (app.tracking.value.active) return
        val activity = app.tracking.value.activity
        app.tracking.update { it.copy(active = true, paused = false, simulated = true, position = null, liveHead = null) }
        job = scope.launch {
            val processor = ExplorationProcessor(app, "simulation", activity)
            try {
                app.repository.clearSimulation()
                processor.begin()
                val origin = SystemClock.elapsedRealtimeNanos() / 1e9
                var observedPause = pauseRevision
                for (i in 0..SimulatedRoute.LAST_INDEX) {
                    if (observedPause != pauseRevision) { processor.pause(); observedPause = pauseRevision }
                    while (app.tracking.value.paused) delay(100)
                    if (observedPause != pauseRevision) { processor.pause(); observedPause = pauseRevision }
                    val now = origin + i * activity.simulationTimeScale
                    val fix = scenario.fix(i, now)
                    if (fix != null) processor.ingest(fix.copy(speedMetersPerSecond = fix.speedMetersPerSecond?.div(activity.simulationTimeScale)), now)
                    else app.tracking.update { it.copy(message = "MODO SIMULADO • ${scenario.label}: aguardando retorno do sinal") }
                    delay(SimulatedRoute.PLAYBACK_DELAY_MILLIS)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { app.tracking.update { it.copy(message = "Falha ao salvar simulação. Verifique o espaço livre.") } }
            finally {
                withContext(NonCancellable) {
                    runCatching { processor.finish() }.onFailure {
                        app.tracking.update { it.copy(message = "Não foi possível concluir o rastro simulado. Confira o espaço livre.") }
                    }
                    app.tracking.update { it.copy(active = false, paused = false) }
                }
            }
        }
    }
    fun stop() { job?.cancel(); job = null }
}
