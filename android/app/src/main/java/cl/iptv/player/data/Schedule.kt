package cl.iptv.player.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/**
 * Grabaciones programadas desde la guía: a la hora del programa (con un margen antes y después, porque los horarios
 * de la guía casi nunca son exactos) se graba el canal en Descargas/VibeIPTV, aunque la app esté cerrada.
 * Se guardan en el equipo y se vuelven a programar si la TV se reinicia.
 */
data class ScheduledRec(
    val id: String, val title: String, val channel: String, val url: String, val userAgent: String, val referrer: String,
    /** Inicio y fin del programa (ms), ya con el ajuste de hora de la guía. */
    val start: Long, val end: Long,
    /** Número propio de la alarma (único y estable: con hashCode del id dos grabaciones podían pisarse). */
    val code: Int = 0,
) {
    /** Se empieza 2 min antes y se termina 3 min después. */
    val recStart get() = start - Schedules.BEFORE_MS
    val recEnd get() = end + Schedules.AFTER_MS

    fun toJson() = JSONObject().put("id", id).put("title", title).put("channel", channel).put("url", url)
        .put("ua", userAgent).put("ref", referrer).put("start", start).put("end", end).put("code", code)

    fun item() = Item(id = "rec_$id", kind = Kind.LIVE, name = channel, url = url, userAgent = userAgent, referrer = referrer)

    companion object {
        fun fromJson(o: JSONObject) = ScheduledRec(o.getString("id"), o.optString("title"), o.optString("channel"), o.getString("url"),
            o.optString("ua"), o.optString("ref"), o.getLong("start"), o.getLong("end"), o.optInt("code", o.getString("id").hashCode()))
        fun idFor(channelId: String, start: Long) = "${channelId.hashCode().toUInt()}_$start"
    }
}

object Schedules {
    const val BEFORE_MS = 2 * 60_000L
    const val AFTER_MS = 3 * 60_000L
    const val ACTION_RECORD = "cl.iptv.player.GRABAR"
    const val ACTION_CANCEL = "cl.iptv.player.CANCELAR_GRABACION"
    const val EXTRA_ID = "id"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("grabaciones", Context.MODE_PRIVATE)

    fun all(ctx: Context): List<ScheduledRec> = runCatching {
        val a = JSONArray(prefs(ctx).getString("lista", "[]"))
        (0 until a.length()).map { ScheduledRec.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList()).sortedBy { it.start }

    private fun save(ctx: Context, list: List<ScheduledRec>) =
        prefs(ctx).edit().putString("lista", JSONArray(list.map { it.toJson() }).toString()).commit()

    /** Quita las que ya terminaron (por si la TV estuvo apagada a esa hora). */
    fun pending(ctx: Context, now: Long = System.currentTimeMillis()) = all(ctx).filter { it.recEnd > now }

    // Leer-modificar-guardar desde la guía, la alarma y el servicio a la vez: sincronizado para no perder cambios
    @Synchronized fun add(ctx: Context, r: ScheduledRec) {
        val prev = all(ctx).firstOrNull { it.id == r.id }
        val code = prev?.code ?: nextCode(ctx)
        val rec = r.copy(code = code)
        save(ctx, pending(ctx).filter { it.id != r.id } + rec)
        arm(ctx, rec)
    }

    private fun nextCode(ctx: Context): Int {
        val n = prefs(ctx).getInt("codigo", 1000) + 1
        prefs(ctx).edit().putInt("codigo", n).commit()
        return n
    }

    /** Quita la grabación y su alarma. Si ya estaba grabando, la detiene (el archivo queda con lo grabado). */
    @Synchronized fun remove(ctx: Context, id: String, stopRunning: Boolean = false) {
        val r = all(ctx).firstOrNull { it.id == id }
        save(ctx, all(ctx).filter { it.id != id })
        r?.let { (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(alarmIntent(ctx, it)) }
        if (stopRunning) runCatching {
            ctx.startService(Intent(ctx, cl.iptv.player.RecordingService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_ID, id))
        }
    }

    fun find(ctx: Context, id: String) = all(ctx).firstOrNull { it.id == id }

    /** ¿Choca con otra? Muchas cuentas admiten una sola conexión: dos grabaciones a la vez no funcionarían. */
    fun overlaps(ctx: Context, r: ScheduledRec) = pending(ctx).any { it.id != r.id && it.recStart < r.recEnd && r.recStart < it.recEnd }

    private fun alarmIntent(ctx: Context, r: ScheduledRec): PendingIntent =
        PendingIntent.getBroadcast(ctx, r.code, Intent(ctx, RecAlarmReceiver::class.java).setAction(ACTION_RECORD).putExtra(EXTRA_ID, r.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /**
     * Alarma exacta a la hora de empezar: así Android deja iniciar la grabación aunque la app esté cerrada.
     * Si el equipo no permite alarmas exactas, una aproximada (puede atrasarse unos minutos): la guía avisa y ofrece
     * abrir el permiso ([exactAllowed]).
     */
    fun arm(ctx: Context, r: ScheduledRec) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val at = maxOf(r.recStart, System.currentTimeMillis() + 1_000)
        val pi = alarmIntent(ctx, r)
        val exact = exactAllowed(ctx)
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    /** Tras reiniciar el equipo (o actualizar la app) las alarmas se pierden: volver a programarlas. */
    fun exactAllowed(ctx: Context) =
        Build.VERSION.SDK_INT < 31 || (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()

    @Synchronized fun rearmAll(ctx: Context) {
        val list = pending(ctx)
        save(ctx, list)
        list.forEach { arm(ctx, it) }
    }
}

/** Recibe la alarma de una grabación (o el reinicio del equipo) y arranca el servicio que graba. */
class RecAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Schedules.ACTION_RECORD -> {
                val id = intent.getStringExtra(Schedules.EXTRA_ID) ?: return
                val svc = Intent(ctx, cl.iptv.player.RecordingService::class.java).putExtra(Schedules.EXTRA_ID, id)
                runCatching { if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(svc) else ctx.startService(svc) }
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Schedules.rearmAll(ctx)
        }
    }
}
