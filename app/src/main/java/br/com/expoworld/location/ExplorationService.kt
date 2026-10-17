package br.com.expoworld.location

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.*
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import br.com.expoworld.*
import br.com.expoworld.core.Fix
import br.com.expoworld.core.ActivityKind
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.update

class ExplorationService : Service(), LocationListener {
    private val app get() = application as WorldApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private sealed interface Event {
        data class Position(val fix: Fix) : Event
        data object Pause : Event
        data object Resume : Event
    }
    private val queue = Channel<Event>(64)
    @Volatile private var paused = false
    private lateinit var manager: LocationManager
    private lateinit var processor: ExplorationProcessor
    private var worker: Job? = null
    private var watchdog: Job? = null
    @Volatile private var lastValid = 0.0
    private var started = false
    private var stopping = false
    private fun now() = SystemClock.elapsedRealtimeNanos() / 1e9
    override fun onCreate() {
        super.onCreate()
        manager = getSystemService(LocationManager::class.java)
        processor = ExplorationProcessor(app, "real") // Also handles a stale stop intent before a new trip.
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Exploração em andamento", NotificationManager.IMPORTANCE_LOW)
        )
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { finishTrip(); return START_NOT_STICKY }
        if (intent?.action == PAUSE || intent?.action == RESUME) {
            if (!started || stopping) { if (!started) stopSelf(); return START_NOT_STICKY }
            if (intent.action == PAUSE && !paused) {
                paused = true
                runCatching { manager.removeUpdates(this) }
                app.tracking.update { it.copy(paused = true, gpsFix = null, message = "Percurso pausado. Nenhum ponto novo será registrado até continuar.") }
                if (queue.trySend(Event.Pause).isFailure) finishTrip()
                updateNotification()
            } else if (intent.action == RESUME && paused) {
                if (queue.trySend(Event.Resume).isFailure) finishTrip()
            }
            return START_NOT_STICKY
        }
        if (started || stopping) return START_NOT_STICKY
        if (app.storageBusy) { stopSelf(); return START_NOT_STICKY }
        val activity = ActivityKind.fromKey(intent?.getStringExtra("activity"))
        processor = ExplorationProcessor(app, "real", activity)
        app.tracking.update { it.copy(activity = activity) }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            app.tracking.update { it.copy(active = false, message = "Ative a localização precisa nas permissões para revelar uma faixa de 50 m.") }
            stopSelf(); return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(this, 1, notification(), if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
            if(intent?.getBooleanExtra("pocket",false)==true) app.pocket.begin() else app.pocket.end()
            started = true
            lastValid = now()
            app.tracking.update { it.copy(active = true, paused = false, simulated = false, position = null, gpsFix = null, liveHead = null, message = "Aguardando sinal GPS preciso…") }
            worker = scope.launch(Dispatchers.IO) {
                try {
                    processor.begin()
                    for (event in queue) when (event) {
                        // Callbacks stop immediately on pause. Drain readings already queued
                        // before the ordered Pause event instead of silently dropping history.
                        is Event.Position -> if (processor.ingest(event.fix, now())) lastValid = now()
                        Event.Pause -> processor.pause()
                        Event.Resume -> {
                            processor.breakContinuity()
                            withContext(Dispatchers.Main) {
                                if (!stopping && paused) {
                                    if (ContextCompat.checkSelfPermission(this@ExplorationService, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                                        throw SecurityException("Localização precisa revogada")
                                    paused = false; lastValid = now()
                                    manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this@ExplorationService, Looper.getMainLooper())
                                    app.tracking.update { it.copy(paused = false, liveHead = null, message = "Aguardando GPS preciso para continuar o percurso…") }
                                    updateNotification()
                                }
                            }
                        }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    app.tracking.update { it.copy(message = if (e is SecurityException) "Permissão de localização revogada. Autorize novamente nas configurações."
                        else "Não foi possível salvar a exploração. Verifique o espaço livre; a viagem foi encerrada.") }
                    withContext(Dispatchers.Main) { finishTrip() }
                }
            }
            // GPS provider works without Google Play Services and without internet.
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
            watchdog = scope.launch {
                while (isActive) {
                    delay(5000)
                    if (ContextCompat.checkSelfPermission(this@ExplorationService, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                        app.tracking.update { it.copy(message = "Permissão de localização revogada. Autorize novamente nas configurações.") }
                        finishTrip(); break
                    }
                    if (paused) continue
                    if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) app.tracking.update { it.copy(message = "GPS desligado. Ative a localização do aparelho; a viagem aguarda sinal.") }
                    else if (now() - lastValid > 15) app.tracking.update { it.copy(message = "Sinal GPS perdido ou impreciso. Aguardando; nenhuma lacuna será conectada.") }
                }
            }
        } catch (e: Exception) {
            app.tracking.update { it.copy(message = "Não foi possível iniciar o GPS. Abra o app e confira localização precisa e GPS ativado.") }
            finishTrip()
        }
        // Explicitly started by visible activity. No automatic restart/boot tracking.
        return START_NOT_STICKY
    }
    override fun onLocationChanged(location: Location) {
        if (stopping || paused) return
        val reliableSpeed = if (location.hasSpeed() && (Build.VERSION.SDK_INT < 26 || !location.hasSpeedAccuracy() || location.speedAccuracyMetersPerSecond <= 3f)) location.speed.toDouble() else null
        val reliableBearing = location.bearing.toDouble().takeIf { location.hasBearing() && (reliableSpeed ?: 0.0) > .5 }
        val fix = Fix(location.latitude, location.longitude, if (location.hasAccuracy()) location.accuracy.toDouble() else Double.POSITIVE_INFINITY,
            location.elapsedRealtimeNanos / 1e9, reliableSpeed, reliableBearing)
        app.tracking.update { it.copy(gpsFix = fix) }
        if (queue.trySend(Event.Position(fix)).isFailure) {
            // End cleanly on storage backpressure; never bridge dropped queue entries.
            app.tracking.update { it.copy(message = "Gravação local lenta. Viagem encerrada para preservar os dados já salvos.") }
            finishTrip()
        }
    }
    override fun onProviderDisabled(provider: String) { app.tracking.update { it.copy(message = "GPS desligado. Ative a localização nas configurações do aparelho.") } }
    override fun onProviderEnabled(provider: String) { app.tracking.update { it.copy(message = "GPS ativado. Aguardando sinal preciso…") } }
    @Deprecated("Legacy callback")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val end = PendingIntent.getService(this, 1, Intent(this, ExplorationService::class.java).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pause = PendingIntent.getService(this, 2, Intent(this, ExplorationService::class.java).setAction(if (paused) RESUME else PAUSE), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_explore)
            .setContentTitle(if (paused) "Expo World • percurso pausado" else "Expo World • ${app.tracking.value.activity.label}")
            .setContentText(if (paused) "Sem registrar pontos. Continue quando estiver pronto." else "Registrando GPS localmente. Toque em Encerrar para parar.")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(R.drawable.ic_explore, if (paused) "Continuar" else "Pausar", pause)
            .addAction(R.drawable.ic_explore, "Encerrar exploração", end).build()
    }
    private fun updateNotification() { getSystemService(NotificationManager::class.java).notify(1, notification()) }
    private fun finishTrip() {
        app.pocket.end()
        if (stopping) return
        stopping = true
        runCatching { manager.removeUpdates(this) }
        watchdog?.cancel()
        queue.close()
        scope.launch {
            worker?.join() // Drain accepted fixes before removing foreground status.
            val hadPoints = processor.hasData()
            var saved = true
            runCatching { withContext(Dispatchers.IO) { processor.finish() } }.onFailure {
                saved = false
                app.tracking.update { it.copy(message = "Não foi possível concluir o rastro. Confira o espaço livre; a exploração já salva foi preservada.") }
            }
            app.tracking.update { it.copy(active = false, paused = false, message = when {
                !saved -> it.message
                !hadPoints -> "Nenhum ponto válido recebido. Seu rastro anterior foi preservado; confira GPS e permissões."
                else -> "Percurso encerrado. Rastro e descobertas foram salvos no aparelho."
            }) }
            ServiceCompat.stopForeground(this@ExplorationService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }
    override fun onDestroy() {
        runCatching { manager.removeUpdates(this) }
        queue.close(); scope.cancel()
        app.tracking.update { it.copy(active = false, paused = false) }
        super.onDestroy()
    }
    override fun onBind(intent: Intent?) = null
    companion object {
        const val STOP = "br.com.expoworld.STOP"
        const val PAUSE = "br.com.expoworld.PAUSE"
        const val RESUME = "br.com.expoworld.RESUME"
        private const val CHANNEL = "exploration"
    }
}
