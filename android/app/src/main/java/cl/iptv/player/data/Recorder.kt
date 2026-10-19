package cl.iptv.player.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import okhttp3.Request
import java.io.File
import java.io.OutputStream
import java.net.URI

/**
 * Grabación de canales en vivo a un archivo .ts (Descargas/VibeIPTV).
 * Usa una conexión aparte: si la cuenta admite una sola conexión, la reproducción puede cortarse.
 */
object Recorder {

    /** Abre el archivo de salida. Devuelve el flujo y una función para cerrarlo (borra el archivo si quedó vacío). */
    fun open(ctx: Context, name: String): Pair<OutputStream, (Long) -> Unit> {
        if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "video/mp2t")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/VibeIPTV")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: error("No se pudo crear el archivo")
            val out = ctx.contentResolver.openOutputStream(uri) ?: error("No se pudo abrir el archivo")
            return out to { bytes ->
                if (bytes <= 0) ctx.contentResolver.delete(uri, null, null)
                else ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            }
        }
        val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "VibeIPTV").apply { mkdirs() }
        val f = File(dir, name)
        return f.outputStream() to { bytes -> if (bytes <= 0) f.delete() }
    }

    /** En Xtream el canal .m3u8 también existe como .ts (más simple de grabar). */
    fun bestUrl(url: String): String {
        val m = Regex("^(.*/live/[^/]+/[^/]+/\\d+)\\.m3u8(\\?.*)?$", RegexOption.IGNORE_CASE).find(url) ?: return url
        return "${m.groupValues[1]}.ts${m.groupValues[2]}"
    }

    private fun request(url: String, item: Item) = Request.Builder().url(url)
        .header("User-Agent", item.userAgent.ifBlank { Http.DEFAULT_UA })
        .apply { if (item.referrer.isNotBlank()) header("Referer", item.referrer) }.build()

    /** Graba hasta que se cancele la corrutina. onBytes recibe el total escrito. */
    suspend fun record(item: Item, out: OutputStream, onBytes: (Long) -> Unit) {
        val url = bestUrl(item.url)
        if (url.substringBefore('?').endsWith(".m3u8", true)) recordHls(url, item, out, onBytes)
        else recordStream(url, item, out, onBytes)
    }

    private suspend fun recordStream(url: String, item: Item, out: OutputStream, onBytes: (Long) -> Unit) {
        var total = 0L
        var retries = 0
        while (retries < 5) {
            currentCoroutineContext().ensureActive()
            try {
                Http.client.newCall(request(url, item)).execute().use { r ->
                    if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
                    val input = r.body!!.byteStream()
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); total += n; onBytes(total); retries = 0
                    }
                }
            } catch (e: java.io.IOException) {
                if (total == 0L && retries >= 2) throw e
            }
            retries++                      // corte de la transmisión: reconectar y seguir grabando
            delay(1500)
        }
    }

    private suspend fun recordHls(start: String, item: Item, out: OutputStream, onBytes: (Long) -> Unit) {
        var playlist = start
        val seen = LinkedHashSet<String>()
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val text = Http.client.newCall(request(playlist, item)).execute().use { it.body?.string().orEmpty() }
            val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.any { it.startsWith("#EXT-X-STREAM-INF") }) {          // lista maestra: elegir la primera calidad
                val v = lines.firstOrNull { !it.startsWith("#") } ?: error("Lista vacía")
                playlist = URI(playlist).resolve(v).toString(); continue
            }
            val target = lines.firstOrNull { it.startsWith("#EXT-X-TARGETDURATION") }?.substringAfter(':')?.toDoubleOrNull() ?: 6.0
            for (seg in lines.filter { !it.startsWith("#") }) {
                val segUrl = URI(playlist).resolve(seg).toString()
                if (!seen.add(segUrl)) continue
                Http.client.newCall(request(segUrl, item)).execute().use { r ->
                    r.body?.byteStream()?.copyTo(out)?.let { total += it; onBytes(total) }
                }
                while (seen.size > 300) seen.remove(seen.first())
            }
            if (lines.any { it.startsWith("#EXT-X-ENDLIST") }) break
            delay((target * 500).toLong().coerceIn(1000, 6000))
        }
    }
}
