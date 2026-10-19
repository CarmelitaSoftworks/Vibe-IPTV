package cl.iptv.player.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Cliente de la API REST de OpenSubtitles: la misma base de datos que usa VLSub,
 * el plugin de subtítulos de código abierto incluido en VLC.
 * Requiere una API key gratuita: https://www.opensubtitles.com/consumers
 */
class OpenSubtitles(private val settings: () -> Settings) {

    data class Result(val fileId: Long, val label: String, val lang: String, val downloads: Int)

    private val api = "https://api.opensubtitles.com/api/v1"
    private val ua = "VibeIPTVAndroid v1.0"
    private var token: String? = null
    private val json = "application/json".toMediaType()

    private fun req(url: String): Request.Builder {
        val s = settings()
        if (s.osApiKey.isBlank()) throw IOException("Configura tu API key de OpenSubtitles en Ajustes")
        return Request.Builder().url(url)
            .header("Api-Key", s.osApiKey)
            .header("User-Agent", ua)
            .header("Accept", "application/json")
            .apply { token?.let { header("Authorization", "Bearer $it") } }
    }

    private fun exec(r: Request): JSONObject = Http.client.newCall(r).execute().use { res ->
        val body = res.body?.string().orEmpty()
        val o = runCatching { JSONObject(body) }.getOrDefault(JSONObject())
        if (!res.isSuccessful) throw IOException(o.optString("message").ifBlank { "OpenSubtitles HTTP ${res.code}" })
        o
    }

    private fun ensureLogin() {
        val s = settings()
        if (token != null || s.osUser.isBlank() || s.osPass.isBlank()) return
        runCatching {
            val body = JSONObject().put("username", s.osUser).put("password", s.osPass).toString().toRequestBody(json)
            token = exec(req("$api/login").post(body).build()).optString("token").ifBlank { null }
        }
    }

    suspend fun search(query: String, season: Int = 0, episode: Int = 0): List<Result> = withContext(Dispatchers.IO) {
        ensureLogin()
        val url = "$api/subtitles".toHttpUrl().newBuilder()
            .addQueryParameter("query", query)
            .addQueryParameter("languages", settings().subLangs.replace(" ", "").lowercase())
            .apply {
                if (season > 0) addQueryParameter("season_number", season.toString())
                if (episode > 0) addQueryParameter("episode_number", episode.toString())
            }.build()
        val data = exec(req(url.toString()).get().build()).optJSONArray("data") ?: return@withContext emptyList()
        val out = ArrayList<Result>()
        for (i in 0 until data.length()) {
            val a = data.optJSONObject(i)?.optJSONObject("attributes") ?: continue
            val files = a.optJSONArray("files") ?: continue
            for (j in 0 until files.length()) {
                val f = files.optJSONObject(j) ?: continue
                out += Result(
                    fileId = f.optLong("file_id"),
                    label = "[${a.optString("language")}] ${a.optString("release").ifBlank { f.optString("file_name") }}",
                    lang = a.optString("language"),
                    downloads = a.optInt("download_count"),
                )
            }
        }
        out.sortedByDescending { it.downloads }
    }

    /** Descarga el subtítulo y lo guarda en [dir]. Devuelve el archivo .srt. */
    suspend fun download(fileId: Long, dir: File): File = withContext(Dispatchers.IO) {
        ensureLogin()
        val body = JSONObject().put("file_id", fileId).toString().toRequestBody(json)
        val link = exec(req("$api/download").post(body).build()).optString("link")
        if (link.isBlank()) throw IOException("No se obtuvo el enlace de descarga (¿límite diario?)")
        val text = Http.client.newCall(Request.Builder().url(link).header("User-Agent", ua).build()).execute().use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
            it.body?.string().orEmpty()
        }
        dir.mkdirs()
        File(dir, "os_$fileId.srt").apply { writeText(text) }
    }
}

/** "ES| Matrix (1999) [4K]" -> "Matrix" */
fun cleanTitle(name: String): String = name
    .replace(Regex("^[A-Z]{2,3}\\s*[|:\\-]\\s*"), "")
    .replace(Regex("\\[[^\\]]*\\]|\\([^)]*\\)"), " ")
    .replace(Regex("\\b(4k|fhd|uhd|hd|sd|1080p|720p|multi|lat|cast|sub|vose)\\b", RegexOption.IGNORE_CASE), " ")
    .replace(Regex("T\\d+\\s*E\\d+.*$", RegexOption.IGNORE_CASE), "")
    .replace(Regex("\\s+"), " ")
    .trim()
