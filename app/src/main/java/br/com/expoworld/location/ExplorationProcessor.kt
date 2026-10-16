package br.com.expoworld.location

import br.com.expoworld.WorldApp
import br.com.expoworld.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

class ExplorationProcessor(private val app: WorldApp, private val mode: String) {
    private val filter = FixFilter()
    private val trace = TraceSampler()
    private var begun = false
    private var startedAt = 0L
    suspend fun begin() { startedAt = System.currentTimeMillis() }
    suspend fun finish() { if (begun) { app.repository.finishRoute(mode, trace.finish()); begun = false } }
    fun hasData() = begun
    fun breakContinuity() { filter.breakContinuity(); trace.breakContinuity() }
    suspend fun pause() {
        if (begun) trace.finish()?.let { app.repository.saveAccepted(mode, emptySet(), it) }
        breakContinuity()
    }
    suspend fun ingest(fix: Fix, now: Double): Boolean {
        return when (val result = filter.accept(fix, now)) {
            is FilterResult.Rejected -> { trace.breakContinuity(); app.tracking.update { it.copy(message = result.message) }; false }
            is FilterResult.Accepted -> {
                val cells = withContext(Dispatchers.Default) { Revealer.cells(result.fix, result.previous) }
                val sample = trace.accept(result.fix, result.previous != null)
                app.repository.saveAccepted(mode, cells, sample, startedAt.takeUnless { begun }, trace.liveHead()?.elapsedSeconds ?: -1.0,
                    System.currentTimeMillis() - ((now - fix.monotonicSeconds).coerceAtLeast(0.0) * 1000).toLong())
                begun = true
                app.tracking.update { it.copy(position = fix, liveHead = trace.liveHead(), revision = it.revision + 1,
                    message = if (mode == "simulation") "MODO SIMULADO • trajeto de desenvolvimento" else "Explorando • localização precisa. Você pode bloquear a tela.") }
                true
            }
        }
    }
}
