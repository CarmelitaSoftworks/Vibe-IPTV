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
    /** Último pedido recibido: el servicio se cierra con stopSelf(startId) sólo si no llegó otro después. */
    @Volatile private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Con la app cerrada nadie cargó los textos traducidos: el aviso saldría siempre en español
        cl.iptv.player.ui.I18n.load(this)
        cl.iptv.player.ui.I18n.apply(runCatching { cl.iptv.player.data.Storage(this).settings().lang }.getOrDefault("auto"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val id = intent?.getStringExtra(Schedules.EXTRA_ID)
        if (intent?.action == Schedules.ACTION_CANCEL) {
            // Cancelada desde la guía mientras grababa: se detiene y se guarda lo que alcanzó a grabar
            val job = synchronized(jobs) { id?.let { jobs[it] } }
            job?.cancel()
            if (job == null) stopIfIdle()
            return START_NOT_STICKY
        }
        val rec = id?.let { Schedules.find(this, it) }
        val valid = rec != null && rec.recEnd > System.currentTimeMillis()
        // Android exige mostrar el aviso enseguida tras startForegroundService, aunque no haya nada que grabar;
        // si ya hay grabaciones en curso no se pisa su texto.
        val busy = synchronized(jobs) { jobs.isNotEmpty() }
        if (valid || !busy) foreground(if (valid) "Grabando: ${rec!!.title} (${rec.channel})" else "Grabaciones de Vibe IPTV")
        if (!valid) { id?.let { Schedules.remove(this, it) }; stopIfIdle(); return START_NOT_STICKY }
        synchronized(jobs) {
            if (!jobs.containsKey(rec!!.id)) jobs[rec.id] = scope.launch { record(rec) }
        }
        return START_NOT_STICKY
    }

    private suspend fun record(r: ScheduledRec) {
        val name = Recorder.fileName(r.title, r.start)
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

    private fun stopIfIdle() {
        synchronized(jobs) { if (jobs.isEmpty()) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(lastStartId) } }
    }

    /**
     * Android 15 limita el tiempo de los servicios de sincronización (unas 6 h al día): si se acaba, se cierran las
     * grabaciones guardando lo grabado, en vez de que el sistema cierre la app de golpe.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        synchronized(jobs) { jobs.values.toList() }.forEach { it.cancel() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun channel(): String {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null)
                nm.createNotificationChannel(NotificationChannel(CHANNEL, cl.iptv.player.ui.tr("Grabaciones"), NotificationManager.IMPORTANCE_LOW))
        }
        return CHANNEL
    }

    private fun builder(text: String) =
        (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channel()) else @Suppress("DEPRECATION") Notification.Builder(this))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Vibe IPTV")
            .setContentText(cl.iptv.player.ui.tr(text))

    private fun foreground(text: String) {
        val n = builder(text).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(NOTIF_ID, n)
    }

    private fun notifyDone(r: ScheduledRec, bytes: Long, name: String) {
        val text = if (bytes > 0) "Grabación guardada en ${Recorder.folderLabel}: $name" else "No se pudo grabar ${r.title} (${r.channel})"
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_ID + 1 + r.code, builder(text).build()) }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private companion object {
        const val CHANNEL = "grabaciones"
        const val NOTIF_ID = 4021
    }
}
