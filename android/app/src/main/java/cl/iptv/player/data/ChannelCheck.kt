package cl.iptv.player.data

import android.content.Context
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

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

    /**
     * true si el canal responde con contenido. Se puede cancelar a mitad: la conexión se corta enseguida (para dejarle
     * la conexión libre al reproductor).
     */
    suspend fun alive(item: Item): Boolean {
        val req = runCatching {
            Request.Builder().url(item.url)
                .header("User-Agent", item.userAgent.ifBlank { Http.DEFAULT_UA })
                .apply { if (item.referrer.isNotBlank()) header("Referer", item.referrer) }.build()
        }.getOrNull() ?: return false
        val call = client.newCall(req)
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resume(false) }
                override fun onResponse(call: Call, response: Response) {
                    val ok = runCatching { response.use { r -> judge(r) } }.getOrDefault(false)
                    if (cont.isActive) cont.resume(ok)
                }
            })
        }
    }

    private fun judge(r: Response): Boolean {
        if (!r.isSuccessful) return false
        val type = r.header("Content-Type").orEmpty().lowercase()
        // Páginas de error con código 200 ("stream offline" en HTML o JSON)
        if (type.startsWith("text/html") || type.contains("json")) return false
        val input = r.body?.byteStream() ?: return false
        val buf = ByteArray(8 * 1024)
        var got = 0
        while (got < buf.size) { val n = input.read(buf, got, buf.size - got); if (n < 0) break; got += n }
        return looksAlive(buf, got)
    }

    /** ¿Lo que llegó parece un canal? Lista HLS (aunque traiga BOM o líneas en blanco) o video MPEG-TS / MP4 / FLV. */
    internal fun looksAlive(buf: ByteArray, got: Int): Boolean {
        val text = String(buf, 0, minOf(got, 64), Charsets.ISO_8859_1).trimStart('\uFEFF', '\u00EF', '\u00BB', '\u00BF', ' ', '\r', '\n', '\t')
        if (text.startsWith("#EXTM3U")) return true
        if (text.startsWith("<") || text.startsWith("{") || text.startsWith("[")) return false     // HTML, XML o JSON
        // MPEG-TS: el byte 0x47 se repite cada 188
        for (i in 0 until minOf(188, got)) if (i + 376 < got && buf[i] == 0x47.toByte() && buf[i + 188] == 0x47.toByte() && buf[i + 376] == 0x47.toByte()) return true
        if (got >= 8 && String(buf, 4, 4, Charsets.ISO_8859_1) == "ftyp") return true                // MP4
        if (got >= 3 && String(buf, 0, 3, Charsets.ISO_8859_1) == "FLV") return true
        return false
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
