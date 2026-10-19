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

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    private fun request(url: String, ua: String) = Request.Builder().url(url).header("User-Agent", ua.ifBlank { DEFAULT_UA }).build()

    suspend fun text(url: String, ua: String = DEFAULT_UA): String = withContext(Dispatchers.IO) {
        client.newCall(request(url, ua)).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} al descargar $url")
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
