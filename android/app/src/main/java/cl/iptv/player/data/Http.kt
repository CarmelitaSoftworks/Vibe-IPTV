package cl.iptv.player.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

object Http {
    /** Muchos proveedores sólo aceptan reproductores conocidos. */
    const val DEFAULT_UA = "VLC/3.0.21 LibVLC/3.0.21"

    /**
     * DNS con memoria (10 min): al cambiar de canal no se vuelve a preguntar la dirección del servidor,
     * que en algunas redes tarda 0,5–2 s. Si falla, se usa la última respuesta conocida.
     */
    val dns: okhttp3.Dns = object : okhttp3.Dns {
        private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<java.net.InetAddress>>>()
        override fun lookup(hostname: String): List<java.net.InetAddress> {
            val now = System.currentTimeMillis()
            cache[hostname]?.let { (at, ips) -> if (now - at < 10 * 60_000L) return ips }
            return try { okhttp3.Dns.SYSTEM.lookup(hostname).also { cache[hostname] = now to it } }
            catch (e: java.net.UnknownHostException) { cache[hostname]?.second ?: throw e }
        }
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .dns(dns)
        // Reutilizar conexiones abiertas con el servidor (listas HLS, segmentos, guía) en vez de abrir una nueva cada vez
        .connectionPool(okhttp3.ConnectionPool(12, 5, TimeUnit.MINUTES))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * Para reproducir: si el servidor deja de enviar datos 10 s se corta y el reproductor reconecta enseguida
     * (antes de que se acabe la reserva guardada), en vez de quedarse esperando con la pantalla negra.
     */
    val streamClient: OkHttpClient = client.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private fun request(url: String, ua: String) = Request.Builder().url(url).header("User-Agent", ua.ifBlank { DEFAULT_UA }).build()

    suspend fun text(url: String, ua: String = DEFAULT_UA): String = withContext(Dispatchers.IO) {
        client.newCall(request(url, ua)).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} al descargar $url")
            r.body?.string() ?: ""
        }
    }

    /** Texto con cabeceras extra (por ejemplo, Authorization). */
    suspend fun textWithHeaders(url: String, headers: Map<String, String>, ua: String = "VibeIPTV-Android"): String = withContext(Dispatchers.IO) {
        val rq = okhttp3.Request.Builder().url(url).header("User-Agent", ua).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        client.newCall(rq).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            r.body?.string() ?: ""
        }
    }

    /** Descarga a un archivo (primero a un temporal, para no dejar la caché a medias). */
    suspend fun download(url: String, dest: java.io.File, ua: String = DEFAULT_UA) = withContext(Dispatchers.IO) {
        val tmp = java.io.File(dest.parentFile, dest.name + ".part")
        client.newCall(request(url, ua)).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val body = r.body ?: throw IOException("Respuesta vacía")
            tmp.outputStream().use { out -> body.byteStream().use { it.copyTo(out) } }
        }
        if (!tmp.renameTo(dest)) { tmp.copyTo(dest, overwrite = true); tmp.delete() }
        dest
    }

    /** Abre la respuesta como stream y la procesa dentro de [block] (en hilo IO). */
    suspend fun <T> stream(url: String, ua: String = DEFAULT_UA, block: (InputStream) -> T): T = withContext(Dispatchers.IO) {
        client.newCall(request(url, ua)).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} al descargar $url")
            val body = r.body ?: throw IOException("Respuesta vacía")
            body.byteStream().use(block)
        }
    }
}
