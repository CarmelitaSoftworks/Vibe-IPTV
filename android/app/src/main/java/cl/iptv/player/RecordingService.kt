package cl.iptv.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import cl.iptv.player.data.Recorder
import cl.iptv.player.data.ScheduledRec
import cl.iptv.player.data.Schedules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Graba las grabaciones programadas en segundo plano (servicio con aviso en la barra de notificaciones, como pide
 * Android para trabajar con la app cerrada). Cada grabación termina sola a su hora y el servicio se cierra al acabar.
 */
class RecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<String, Job>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(Schedules.EXTRA_ID)
        val rec = id?.let { Schedules.find(this, it) }
        foreground(rec?.let { "Grabando: ${it.title} (${it.channel})" } ?: "Grabaciones de Vibe IPTV")
        if (rec == null || rec.recEnd <= System.currentTimeMillis()) { id?.let { Schedules.remove(this, it) }; stopIfIdle(); return START_NOT_STICKY }
        synchronized(jobs) {
            if (jobs.containsKey(rec.id)) return START_NOT_STICKY
            jobs[rec.id] = scope.launch { record(rec) }
        }
        return START_NOT_STICKY
    }

    private suspend fun record(r: ScheduledRec) {
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm", java.util.Locale.US).format(java.util.Date(r.start))
        val name = "${r.title.replace(Regex("[^\\p{L}\\p{N} ._-]"), "_").take(40).trim()}_$stamp.ts"
        var bytes = 0L
        var finish: ((Long) -> Unit)? = null
        try {
            val (out, fin) = Recorder.open(this, name); finish = fin
            // Graba hasta la hora de término (más el margen); Recorder ya reconecta si la transmisión se corta
            out.use { o -> withTimeoutOrNull(r.recEnd - System.currentTimeMillis()) { Recorder.record(r.item(), o) { n -> bytes = n } } }
        } catch (_: Exception) {
        } finally {
            runCatching { finish?.invoke(bytes) }
            Schedules.remove(this, r.id)
            notifyDone(r, bytes, name)
            synchronized(jobs) { jobs.remove(r.id) }
            stopIfIdle()
        }
    }

    private fun stopIfIdle() { synchronized(jobs) { if (jobs.isEmpty()) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() } } }

    private fun channel(): String {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null)
                nm.createNotificationChannel(NotificationChannel(CHANNEL, cl.iptv.player.ui.tr("Grabaciones"), NotificationManager.IMPORTANCE_LOW))
        }
        return CHANNEL
    }

    private fun build(text: String): Notification =
        (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channel()) else @Suppress("DEPRECATION") Notification.Builder(this))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Vibe IPTV")
            .setContentText(cl.iptv.player.ui.tr(text))
            .setOngoing(true)
            .build()

    private fun foreground(text: String) {
        val n = build(text)
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(NOTIF_ID, n)
    }

    private fun notifyDone(r: ScheduledRec, bytes: Long, name: String) {
        val text = if (bytes > 0) "Grabación guardada en Descargas/VibeIPTV: $name" else "No se pudo grabar ${r.title} (${r.channel})"
        runCatching {
            val n = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channel()) else @Suppress("DEPRECATION") Notification.Builder(this))
                .setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("Vibe IPTV").setContentText(cl.iptv.player.ui.tr(text)).build()
            getSystemService(NotificationManager::class.java).notify(r.id.hashCode(), n)
        }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private companion object {
        const val CHANNEL = "grabaciones"
        const val NOTIF_ID = 4021
    }
}
