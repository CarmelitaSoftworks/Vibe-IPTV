package cl.iptv.player.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * Subtítulos de varias fuentes a la vez:
 *  - OpenSubtitles sin clave (complemento abierto de Stremio + Cinemeta para el código IMDb)
 *  - OpenSubtitles con clave (API oficial, la base de VLSub de VLC)
 *  - SubDL (clave gratis; mucho catálogo en español latino)
 *  - Podnapisi (sin clave)
 */
class SubtitleSources(private val settings: () -> Settings) {

    data class Query(val title: String, val series: Boolean, val season: Int = 0, val episode: Int = 0, val year: String = "")
    data class Result(val provider: String, val source: String, val id: String, val lang: String, val label: String, val downloads: Int,
                      /** "S01E05": elige ese episodio si la descarga es un paquete de temporada. */
                      val episode: String = "")
    data class Found(val results: List<Result>, val errors: Map<String, String>)

    private val ua = "VibeIPTVAndroid v1.0"
    private val names = mapOf("stremio" to "OpenSubtitles", "opensubtitles" to "OpenSubtitles (cuenta)", "subdl" to "SubDL", "podnapisi" to "Podnapisi")
    private val lang3 = mapOf("es" to listOf("spa", "spl", "ea"), "en" to listOf("eng"), "pt" to listOf("por", "pob"), "fr" to listOf("fre", "fra"), "it" to listOf("ita"), "de" to listOf("ger", "deu"))

    private fun langs() = settings().subLangs.lowercase().split(Regex("[\\s,]+")).filter { it.isNotBlank() }.ifEmpty { listOf("es", "en") }

    private fun call(url: String, build: Request.Builder.() -> Unit = {}): ByteArray {
        val req = Request.Builder().url(url).header("User-Agent", ua).header("Accept", "application/json").apply(build).build()
        return try {
            Http.client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                r.body?.bytes() ?: ByteArray(0)
            }
        } catch (e: java.net.SocketTimeoutException) { throw IOException("no responde") }
        catch (e: java.net.UnknownHostException) { throw IOException("sin conexión con la fuente") }
    }
    private fun json(url: String, build: Request.Builder.() -> Unit = {}) = JSONObject(String(call(url, build)))

    // ---------- Proveedores ----------
    private fun imdbFor(q: Query): String? {
        val type = if (q.series) "series" else "movie"
        val metas = json("https://v3-cinemeta.strem.io/catalog/$type/top/search=${java.net.URLEncoder.encode(q.title, "UTF-8").replace("+", "%20")}.json").optJSONArray("metas") ?: return null
        var pick: JSONObject? = null
        for (i in 0 until metas.length()) {
            val m = metas.optJSONObject(i) ?: continue
            if (pick == null) pick = m
            if (q.year.isNotBlank() && m.optString("releaseInfo").startsWith(q.year)) { pick = m; break }
        }
        return pick?.let { it.optString("imdb_id").ifBlank { it.optString("id") } }?.ifBlank { null }
    }

    private fun stremio(q: Query): List<Result> {
        val imdb = imdbFor(q) ?: return emptyList()
        val type = if (q.series) "series" else "movie"
        val id = if (q.series && q.season > 0) "$imdb:${q.season}:${q.episode.coerceAtLeast(1)}" else imdb
        val subs = json("https://opensubtitles-v3.strem.io/subtitles/$type/$id.json").optJSONArray("subtitles") ?: return emptyList()
        val want = langs().flatMap { l -> (lang3[l] ?: listOf(l)).map { it to l } }.toMap()
        return (0 until subs.length()).mapNotNull { subs.optJSONObject(it) }.mapIndexedNotNull { i, s ->
            val l = want[s.optString("lang").lowercase()] ?: return@mapIndexedNotNull null
            Result("stremio", names.getValue("stremio"), s.optString("url"), l, "Subtítulo ${i + 1}", 0)
        }
    }

    private fun opensubtitles(q: Query): List<Result> {
        val key = settings().osApiKey
        val url = "https://api.opensubtitles.com/api/v1/subtitles".toHttpUrl().newBuilder()
            .addQueryParameter("query", q.title).addQueryParameter("languages", langs().joinToString(","))
            .apply {
                if (q.season > 0) addQueryParameter("season_number", q.season.toString())
                if (q.episode > 0) addQueryParameter("episode_number", q.episode.toString())
                if (q.year.isNotBlank()) addQueryParameter("year", q.year)
            }.build().toString()
        val data = json(url) { header("Api-Key", key) }.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<Result>()
        for (i in 0 until data.length()) {
            val a = data.optJSONObject(i)?.optJSONObject("attributes") ?: continue
            val files = a.optJSONArray("files") ?: continue
            for (j in 0 until files.length()) {
                val f = files.optJSONObject(j) ?: continue
                out += Result("opensubtitles", names.getValue("opensubtitles"), f.optLong("file_id").toString(), a.optString("language"),
                    a.optString("release").ifBlank { f.optString("file_name") }, a.optInt("download_count"))
            }
        }
        return out
    }

    /**
     * SubDL, API oficial (https://subdl.com/api-doc): busca por código IMDb cuando se encuentra (más preciso)
     * y si no por título; pide los archivos sueltos de los paquetes de temporada para bajar sólo el episodio.
     */
    private fun subdl(q: Query): List<Result> {
        val imdb = runCatching { imdbFor(q) }.getOrNull()
        fun query(byImdb: Boolean): JSONObject {
            val url = "https://api.subdl.com/api/v1/subtitles".toHttpUrl().newBuilder()
                .addQueryParameter("api_key", settings().subdlKey)
                .apply { if (byImdb && imdb != null) addQueryParameter("imdb_id", imdb) else addQueryParameter("film_name", q.title) }
                .addQueryParameter("languages", langs().joinToString(",") { subdlLang(it) })
                .addQueryParameter("subs_per_page", "30")
                .addQueryParameter("type", if (q.series) "tv" else "movie")
                .addQueryParameter("hi", "1").addQueryParameter("releases", "1").addQueryParameter("unpack", "1")
                .addQueryParameter("client", "custom_integration")
                .apply {
                    if (q.season > 0) addQueryParameter("season_number", q.season.toString())
                    if (q.episode > 0) addQueryParameter("episode_number", q.episode.toString())
                    if (q.year.isNotBlank() && !(byImdb && imdb != null)) addQueryParameter("year", q.year)
                }.build().toString()
            return try { json(url) } catch (e: IOException) {
                // La API responde 4xx con un JSON de error: mostrar el motivo real
                throw IOException(if (e.message == "HTTP 401" || e.message == "HTTP 403") "API key inválida (revísala en Ajustes → Subtítulos)"
                    else if (e.message == "HTTP 429") "límite diario de la API alcanzado" else e.message)
            }
        }
        var o = query(byImdb = imdb != null)
        if (imdb != null && (o.optJSONArray("subtitles")?.length() ?: 0) == 0) o = query(byImdb = false)
        if (o.has("status") && !o.optBoolean("status")) {
            val err = o.optString("error")
            if (err.contains("can't find", true) || err.contains("not found", true)) return emptyList()
            throw IOException(err.ifBlank { "sin resultados" })
        }
        val subs = o.optJSONArray("subtitles") ?: return emptyList()
        val ep = if (q.season > 0 && q.episode > 0) "S%02dE%02d".format(q.season, q.episode) else ""
        val out = ArrayList<Result>()
        for (i in 0 until subs.length()) {
            val s = subs.optJSONObject(i) ?: continue
            val lang = subdlLangCode(s.optString("language"), s.optString("lang"))
            val hi = if (s.optBoolean("hi")) " · SDH" else ""
            val name = s.optString("release_name").ifBlank { s.optString("name") }
            // Paquete de temporada con archivos sueltos: sólo el del episodio pedido
            val files = s.optJSONArray("unpack_files")
            val match = if (files != null && q.episode > 0) (0 until files.length()).mapNotNull { files.optJSONObject(it) }
                .firstOrNull { f -> f.optInt("episode") == q.episode && (q.season == 0 || f.optInt("season", q.season) == q.season) } else null
            if (match != null) {
                out += Result("subdl", names.getValue("subdl"), match.optString("url"), lang, match.optString("name").ifBlank { name } + hi, 0, ep)
            } else {
                val pack = if (s.optBoolean("full_season")) " · temporada completa" else ""
                out += Result("subdl", names.getValue("subdl"), s.optString("url"), lang, name + hi + pack, 0, ep)
            }
        }
        return out.distinctBy { it.id }
    }

    /** Código de idioma que espera SubDL (ES, EN, BR_PT…). */
    private fun subdlLang(l: String) = when (l) { "pt" -> "BR_PT,PT"; "zh" -> "ZH"; else -> l.uppercase() }
    private fun subdlLangCode(code: String, name: String): String {
        val c = code.lowercase()
        if (c.length == 2) return c
        if (c.startsWith("br") || c.startsWith("pt")) return "pt"
        return when (name.lowercase().substringBefore(' ')) {
            "spanish" -> "es"; "english" -> "en"; "portuguese", "brazillian", "brazilian" -> "pt"; "french" -> "fr"
            "italian" -> "it"; "german" -> "de"; else -> c.take(2).ifBlank { name.take(2).lowercase() }
        }
    }

    /** Estado de la clave de SubDL (plan y cuota de hoy) para Ajustes → Subtítulos. */
    suspend fun subdlAccount(key: String): String = withContext(Dispatchers.IO) {
        if (key.isBlank()) throw IOException("Escribe primero la API key")
        val o = try { json("https://api.subdl.com/api/v1/me".toHttpUrl().newBuilder().addQueryParameter("api_key", key).build().toString()) { header("x-api-key", key) } }
            catch (e: IOException) { throw IOException(if (e.message == "HTTP 401" || e.message == "HTTP 403") "API key inválida" else "SubDL: ${e.message}") }
        if (o.has("status") && !o.optBoolean("status")) throw IOException(o.optString("error").ifBlank { "API key inválida" })
        val d = o.optJSONObject("data") ?: o.optJSONObject("user") ?: o
        val plan = listOf("plan", "plan_name", "tier").firstNotNullOfOrNull { d.optString(it).takeIf { v -> v.isNotBlank() && v != "null" } } ?: "gratis"
        val used = listOf("requests_today", "used_today", "today_requests").firstNotNullOfOrNull { k -> d.opt(k)?.toString()?.takeIf { it != "null" } }
        val limit = listOf("daily_limit", "requests_limit", "limit").firstNotNullOfOrNull { k -> d.opt(k)?.toString()?.takeIf { it != "null" } }
        "Clave válida · plan $plan" + (if (used != null) " · hoy $used${limit?.let { "/$it" } ?: ""} búsquedas" else "")
    }

    private fun podnapisi(q: Query): List<Result> {
        val b = "https://www.podnapisi.net/subtitles/search/advanced".toHttpUrl().newBuilder()
            .addQueryParameter("keywords", q.title).addQueryParameter("movie_type", if (q.series) "tv-series" else "movie")
        langs().forEach { b.addQueryParameter("language", it) }
        if (q.season > 0) b.addQueryParameter("seasons", q.season.toString())
        if (q.episode > 0) b.addQueryParameter("episodes", q.episode.toString())
        if (q.year.isNotBlank()) b.addQueryParameter("year", q.year)
        val data = json(b.build().toString()).optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { data.optJSONObject(it) }.map { d ->
            val rel = d.optJSONArray("custom_releases")?.optString(0).orEmpty()
            Result("podnapisi", names.getValue("podnapisi"), d.optString("id").ifBlank { d.optString("pid") }, d.optString("language").take(2),
                rel.ifBlank { d.optString("release").ifBlank { d.optJSONObject("movie")?.optString("title").orEmpty() } },
                d.optJSONObject("stats")?.optInt("downloads") ?: 0)
        }
    }

    suspend fun search(q: Query): Found = withContext(Dispatchers.IO) {
        val s = settings()
        val errors = java.util.concurrent.ConcurrentHashMap<String, String>()
        if (s.subdlKey.isBlank()) errors["SubDL"] = "agrega una API key gratis en Ajustes → Subtítulos"
        val jobs = buildList<Pair<String, () -> List<Result>>> {
            add("stremio" to { stremio(q) })
            if (s.osApiKey.isNotBlank()) add("opensubtitles" to { opensubtitles(q) })
            if (s.subdlKey.isNotBlank()) add("subdl" to { subdl(q) })
            add("podnapisi" to { podnapisi(q) })
        }
        val results = coroutineScope {
            jobs.map { (name, fn) -> async { runCatching(fn).onFailure { errors[names.getValue(name)] = it.message ?: "error" }.getOrDefault(emptyList()) } }.awaitAll().flatten()
        }
        val order = langs()
        val sorted = results.sortedWith(compareBy<Result>({ order.indexOf(it.lang).let { i -> if (i < 0) 99 else i } }, { -it.downloads }))
        Found(sorted, errors.toMap())
    }

    // ---------- Descarga: zip, codificación y formato ----------
    suspend fun download(r: Result, dir: File): File = withContext(Dispatchers.IO) {
        var bytes = when (r.provider) {
            "stremio" -> call(r.id) { header("Accept", "*/*") }
            "subdl" -> call("https://dl.subdl.com${if (r.id.startsWith("/")) r.id else "/" + r.id}") {
                header("Accept", "*/*")
                settings().subdlKey.takeIf { it.isNotBlank() }?.let { header("x-api-key", it) }
            }
            "podnapisi" -> call("https://www.podnapisi.net/subtitles/${r.id}/download") { header("Accept", "*/*") }
            "opensubtitles" -> {
                val body = JSONObject().put("file_id", r.id.toLong()).toString().toRequestBody("application/json".toMediaType())
                val link = json("https://api.opensubtitles.com/api/v1/download") { header("Api-Key", settings().osApiKey); post(body) }.optString("link")
                if (link.isBlank()) throw IOException("límite diario alcanzado")
                call(link) { header("Accept", "*/*") }
            }
            else -> throw IOException("fuente desconocida")
        }
        var ext = "srt"
        if (bytes.size > 2 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte()) {
            val (name, data) = unzipBest(bytes, r.episode)
            bytes = data
            ext = name.substringAfterLast('.', "srt").lowercase()
        } else if (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
            bytes = GZIPInputStream(ByteArrayInputStream(bytes)).readBytes()
        }
        val text = decode(bytes)
        if (text.trimStart().startsWith("[Script Info]")) ext = "ass"
        if (text.trimStart().startsWith("WEBVTT")) ext = "vtt"
        dir.mkdirs()
        File(dir, "${r.provider}_${r.id.hashCode().toUInt()}.${if (ext == "ssa") "ass" else ext}").apply { writeText(text) }
    }

    private fun unzipBest(zip: ByteArray, episode: String = ""): Pair<String, ByteArray> {
        // En paquetes de temporada se prefiere el archivo del episodio (S01E05, 1x05)
        val epRe = if (episode.isBlank()) null else Regex("""S0*(\d+)E0*(\d+)""").find(episode)?.let { m ->
            val (s, e) = m.destructured; Regex("""(?i)(s0*$s[ ._-]*e0*$e(?!\d)|\b$s[x×]0*$e(?!\d))""") }
        val rank = { n: String ->
            val base = when { n.endsWith(".srt", true) -> 0; n.endsWith(".vtt", true) -> 1; n.endsWith(".ass", true) || n.endsWith(".ssa", true) -> 2; else -> 9 }
            if (base < 9 && epRe != null && !epRe.containsMatchIn(n)) base + 3 else base
        }
        var best: Pair<String, ByteArray>? = null
        ZipInputStream(ByteArrayInputStream(zip)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (!e.isDirectory && rank(e.name) < 9 && (best == null || rank(e.name) < rank(best!!.first))) best = e.name to z.readBytes()
            }
        }
        return best ?: throw IOException("el zip no trae subtítulos .srt/.vtt/.ass")
    }

    /** Los .srt en español suelen venir en Windows-1252: sin esto salen "Ã±". */
    companion object {
        fun decode(b: ByteArray): String {
            if (b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) return String(b, 2, b.size - 2, Charsets.UTF_16LE)
            if (b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) return String(b, 2, b.size - 2, Charsets.UTF_16BE)
            return try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(b)).toString().removePrefix("﻿")
            } catch (e: CharacterCodingException) {
                String(b, Charset.forName("windows-1252"))
            }
        }

        /** Corre todos los tiempos de un .srt/.vtt `deltaMs` milisegundos y devuelve el archivo nuevo. */
        fun shift(src: File, deltaMs: Long, dir: File): File {
            val re = Regex("(\\d{1,2}):(\\d{2}):(\\d{2})([,.])(\\d{3})")
            val shifted = re.replace(src.readText()) { m ->
                val (h, mi, s, sep, ms) = m.destructured
                val t = ((h.toLong() * 60 + mi.toLong()) * 60 + s.toLong()) * 1000 + ms.toLong() + deltaMs
                val v = t.coerceAtLeast(0)
                "%02d:%02d:%02d%s%03d".format(v / 3_600_000, v / 60_000 % 60, v / 1000 % 60, sep, v % 1000)
            }
            dir.mkdirs()
            val base = src.nameWithoutExtension.substringBefore("__")
            return File(dir, "${base}__${System.nanoTime()}.${src.extension}").apply { writeText(shifted) }
        }
    }
}
