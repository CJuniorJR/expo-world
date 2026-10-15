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
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.update

class ExplorationService : Service(), LocationListener {
    private val app get() = application as WorldApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val queue = Channel<Fix>(64)
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
        processor = ExplorationProcessor(app, "real")
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Exploração em andamento", NotificationManager.IMPORTANCE_LOW)
        )
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { finishTrip(); return START_NOT_STICKY }
        if (started || stopping) return START_NOT_STICKY
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            app.tracking.update { it.copy(active = false, message = "Ative a localização precisa nas permissões para revelar uma faixa de 50 m.") }
            stopSelf(); return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(this, 1, notification(), if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
            started = true
            lastValid = now()
            app.tracking.update { it.copy(active = true, simulated = false, position = null, liveHead = null, message = "Aguardando sinal GPS preciso…") }
            worker = scope.launch(Dispatchers.IO) {
                try {
                    processor.begin()
                    for (fix in queue) if (processor.ingest(fix, now())) lastValid = now()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    app.tracking.update { it.copy(message = "Não foi possível salvar a exploração. Verifique o espaço livre; a viagem foi encerrada.") }
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
        if (stopping) return
        val reliableSpeed = if (location.hasSpeed() && (Build.VERSION.SDK_INT < 26 || !location.hasSpeedAccuracy() || location.speedAccuracyMetersPerSecond <= 3f)) location.speed.toDouble() else null
        val reliableBearing = location.bearing.toDouble().takeIf { location.hasBearing() && (reliableSpeed ?: 0.0) > .5 }
        val fix = Fix(location.latitude, location.longitude, if (location.hasAccuracy()) location.accuracy.toDouble() else Double.POSITIVE_INFINITY,
            location.elapsedRealtimeNanos / 1e9, reliableSpeed, reliableBearing)
        if (queue.trySend(fix).isFailure) {
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
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_explore)
            .setContentTitle("Expo World • exploração ativa")
            .setContentText("Registrando GPS localmente. Toque em Encerrar para parar.")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(R.drawable.ic_explore, "Encerrar exploração", end).build()
    }
    private fun finishTrip() {
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
            app.tracking.update { it.copy(active = false, message = when {
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
        app.tracking.update { it.copy(active = false) }
        super.onDestroy()
    }
    override fun onBind(intent: Intent?) = null
    companion object { const val STOP = "br.com.expoworld.STOP"; private const val CHANNEL = "exploration" }
}
