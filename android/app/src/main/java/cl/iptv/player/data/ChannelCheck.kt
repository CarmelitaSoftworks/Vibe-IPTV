package cl.iptv.player.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * "Revisar canales caídos": abre cada canal en vivo un momento y mira si llega video. Se revisa de a uno (muchas
 * cuentas admiten una sola conexión) y sólo cuando no se está viendo nada. Los que fallan se revisan otra vez al
 * final antes de marcarlos: un canal puede fallar una vez por casualidad.
 */
object ChannelCheck {
    private val client by lazy {
        Http.streamClient.newBuilder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS).build()
    }

    /** true si el canal responde con contenido (lista HLS válida o los primeros bytes de video). */
    suspend fun alive(item: Item): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(item.url)
                .header("User-Agent", item.userAgent.ifBlank { Http.DEFAULT_UA })
                .apply { if (item.referrer.isNotBlank()) header("Referer", item.referrer) }.build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@use false
                val input = r.body?.byteStream() ?: return@use false
                val buf = ByteArray(8 * 1024)
                var got = 0
                while (got < buf.size) { val n = input.read(buf, got, buf.size - got); if (n < 0) break; got += n }
                val head = String(buf, 0, minOf(got, 16), Charsets.ISO_8859_1)
                when {
                    head.startsWith("#EXTM3U") -> true                                 // lista HLS
                    got >= 1_880 -> true                                               // al menos 10 paquetes de video
                    else -> false
                }
            }
        }.getOrDefault(false)
    }

    // ---------- Resultado guardado por lista ----------
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("canales_caidos", Context.MODE_PRIVATE)
    fun load(ctx: Context, srcId: String): Pair<Set<String>, Long> {
        val p = prefs(ctx)
        return (p.getStringSet("ids_$srcId", emptySet()) ?: emptySet()).toSet() to p.getLong("at_$srcId", 0L)
    }
    fun save(ctx: Context, srcId: String, ids: Set<String>, at: Long) {
        prefs(ctx).edit().putStringSet("ids_$srcId", ids).putLong("at_$srcId", at).apply()
    }
    fun clear(ctx: Context, srcId: String) { prefs(ctx).edit().remove("ids_$srcId").remove("at_$srcId").apply() }
}
