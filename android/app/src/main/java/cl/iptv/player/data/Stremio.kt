package cl.iptv.player.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Extensiones de Stremio (formato abierto de complementos): cada una es una dirección que termina en manifest.json.
 * Una lista de tipo "Extensiones Stremio" junta varias: sus catálogos se ven como categorías, sus series traen los
 * episodios y, al elegir algo, se piden los videos a las extensiones que los entregan.
 *
 * Vibe IPTV sigue siendo un reproductor neutral: no trae extensiones ni recomienda ninguna. Sólo reproduce enlaces de
 * video directos (http/https); los torrents no (ni se descargan ni se comparten).
 */
object Stremio {
    /** Prefijo de los ids de los elementos que vienen de extensiones: "stm:<tipo>:<id de la extensión>". */
    const val PREFIX = "stm:"

    data class Catalog(val type: String, val id: String, val name: String)
    data class Addon(
        val base: String, val name: String, val catalogs: List<Catalog>,
        /** Recurso → tipos que atiende (null = los del manifiesto) y prefijos de id (null = cualquiera). */
        val resources: Map<String, Pair<List<String>?, List<String>?>>,
        val types: List<String>, val idPrefixes: List<String>?,
    ) {
        /** ¿Esta extensión entrega [resource] (stream, meta, subtitles) para este tipo e id? */
        fun serves(resource: String, type: String, id: String): Boolean {
            val (rt, rp) = resources[resource] ?: return false
            val ts = rt ?: types
            val ps = rp ?: idPrefixes
            return (ts.isEmpty() || type in ts) && (ps.isNullOrEmpty() || ps.any { id.startsWith(it) })
        }
    }
    data class Stream(val url: String, val title: String, val addon: String, val userAgent: String = "", val referrer: String = "")

    /** Lo que se puede pegar (stremio://…, con o sin manifest.json) → dirección base de la extensión. */
    fun normalize(input: String): String {
        var s = input.trim()
        if (s.startsWith("stremio://", true)) s = "https://" + s.substring("stremio://".length)
        if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "https://$s"
        return s.substringBefore('?').removeSuffix("/").removeSuffix("/manifest.json").removeSuffix("/")
    }

    /** Las direcciones de una lista de extensiones (una por línea, o separadas por espacios o comas). */
    fun urls(sourceUrl: String): List<String> = sourceUrl.split(Regex("[\\s,]+")).filter { it.isNotBlank() }.map(::normalize).distinct()

    fun itemId(type: String, id: String) = "$PREFIX$type:$id"
    /** (tipo, id de la extensión) de un elemento que vino de extensiones, o null. */
    fun parse(itemId: String): Pair<String, String>? {
        if (!itemId.startsWith(PREFIX)) return null
        val rest = itemId.removePrefix(PREFIX)
        val i = rest.indexOf(':').takeIf { it > 0 } ?: return null
        return rest.substring(0, i) to rest.substring(i + 1)
    }
    /** Elemento de extensión que todavía no tiene el enlace del video (se pide al elegirlo). */
    fun needsStream(item: Item) = item.url.isBlank() && parse(item.id) != null && item.kind != Kind.SERIES

    private val client by lazy { Http.client.newBuilder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build() }
    private fun get(url: String): JSONObject = client.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { r ->
        if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
        JSONObject(r.body!!.string())
    }
    private fun JSONArray?.strings(): List<String>? = this?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }

    // ---------- Manifiesto ----------
    fun parseManifest(base: String, o: JSONObject): Addon {
        val types = o.optJSONArray("types").strings().orEmpty()
        val prefixes = o.optJSONArray("idPrefixes").strings()
        val res = LinkedHashMap<String, Pair<List<String>?, List<String>?>>()
        o.optJSONArray("resources")?.let { a ->
            for (i in 0 until a.length()) {
                when (val r = a.get(i)) {
                    is String -> res[r] = null to null
                    is JSONObject -> res[r.optString("name")] = r.optJSONArray("types").strings() to r.optJSONArray("idPrefixes").strings()
                }
            }
        }
        val catalogs = (o.optJSONArray("catalogs") ?: JSONArray()).let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { c ->
                // Catálogos que exigen un dato extra (sólo búsqueda, sólo por género…) no se pueden listar enteros
                val required = c.optJSONArray("extraRequired").strings().orEmpty() +
                    (c.optJSONArray("extra")?.let { e -> (0 until e.length()).mapNotNull { e.optJSONObject(it) }.filter { it.optBoolean("isRequired") }.map { it.optString("name") } } ?: emptyList())
                required.isEmpty()
            }.map { Catalog(it.optString("type"), it.optString("id"), it.optString("name").ifBlank { it.optString("id") }) }
        }
        return Addon(base, o.optString("name").ifBlank { base.substringAfter("://").substringBefore('/') }, catalogs, res, types, prefixes)
    }

    suspend fun manifest(url: String): Addon = withContext(Dispatchers.IO) {
        val base = normalize(url)
        parseManifest(base, get("$base/manifest.json"))
    }

    // ---------- Catálogos → elementos de la lista ----------
    private fun kindFor(type: String) = when (type) { "movie" -> Kind.MOVIE; "series" -> Kind.SERIES; "tv", "channel" -> Kind.LIVE; else -> null }

    /** Un elemento de catálogo o de ficha (meta) de Stremio como elemento de la lista. */
    fun itemFrom(m: JSONObject, catalogType: String, group: String): Item? {
        val type = m.optString("type").ifBlank { catalogType }
        val kind = kindFor(type) ?: return null
        val id = m.optString("id").ifBlank { return null }
        val year = m.optString("releaseInfo").take(4).takeIf { it.length == 4 && it.all(Char::isDigit) }
        val name = m.optString("name").ifBlank { return null }
        return Item(
            id = itemId(type, id), kind = kind, name = if (year != null && kind == Kind.MOVIE && year !in name) "$name ($year)" else name,
            logo = m.optString("poster").ifBlank { m.optString("logo") }, group = group, url = "",
            seriesName = if (kind == Kind.SERIES) name else "",
        )
    }

    /**
     * Todos los catálogos de las extensiones (hasta 3 páginas cada uno). [onError] recibe "Extensión: motivo" de las
     * que fallan, sin cortar la carga de las demás.
     */
    suspend fun loadAll(addons: List<Addon>, onError: (String) -> Unit = {}): List<Item> = coroutineScope {
        addons.flatMap { a -> a.catalogs.map { c -> a to c } }.map { (a, c) ->
            async(Dispatchers.IO) {
                runCatching {
                    val group = "${c.name} · ${a.name}"
                    val out = ArrayList<Item>()
                    var skip = 0
                    for (page in 0 until 3) {
                        val url = if (skip == 0) "${a.base}/catalog/${c.type}/${c.id}.json" else "${a.base}/catalog/${c.type}/${c.id}/skip=$skip.json"
                        val metas = get(url).optJSONArray("metas") ?: break
                        if (metas.length() == 0) break
                        for (i in 0 until metas.length()) metas.optJSONObject(i)?.let { itemFrom(it, c.type, group) }?.let(out::add)
                        skip += metas.length()
                        if (metas.length() < 50) break
                    }
                    out
                }.onFailure { onError("${a.name}: ${it.message}") }.getOrDefault(emptyList())
            }
        }.awaitAll().flatten().distinctBy { it.id + "|" + it.group }
    }

    // ---------- Episodios de una serie ----------
    suspend fun episodes(addons: List<Addon>, series: Item): List<Item> = withContext(Dispatchers.IO) {
        val (type, id) = parse(series.id) ?: return@withContext emptyList()
        val addon = addons.firstOrNull { it.serves("meta", type, id) } ?: throw IOException("Ninguna de tus extensiones tiene la ficha de esta serie")
        val meta = get("${addon.base}/meta/$type/$id.json").optJSONObject("meta") ?: return@withContext emptyList()
        val videos = meta.optJSONArray("videos") ?: return@withContext emptyList()
        (0 until videos.length()).mapNotNull { videos.optJSONObject(it) }.mapNotNull { v ->
            val vid = v.optString("id").ifBlank { return@mapNotNull null }
            val s = v.optInt("season", 0); val e = v.optInt("episode", v.optInt("number", 0))
            val title = v.optString("name").ifBlank { v.optString("title") }
            Item(
                id = itemId(type, vid), kind = Kind.MOVIE,
                name = if (s > 0 || e > 0) "T$s E$e · $title".trimEnd(' ', '·') else title.ifBlank { vid },
                logo = v.optString("thumbnail").ifBlank { series.logo }, group = if (s > 0) "Temporada $s" else "Especiales",
                season = s, episode = e, seriesName = series.seriesName.ifBlank { series.name }, url = "",
            )
        }.sortedWith(compareBy({ if (it.season == 0) Int.MAX_VALUE else it.season }, { it.episode }))
    }

    // ---------- Videos ----------
    /** Los enlaces de video que entregan las extensiones para este elemento (sólo http/https: los torrents no se usan). */
    suspend fun streams(addons: List<Addon>, item: Item): Pair<List<Stream>, Int> = coroutineScope {
        val (type, id) = parse(item.id) ?: return@coroutineScope emptyList<Stream>() to 0
        val torrents = java.util.concurrent.atomic.AtomicInteger()
        val lists = addons.filter { it.serves("stream", type, id) }.map { a ->
            async(Dispatchers.IO) {
                runCatching {
                    val arr = get("${a.base}/stream/$type/$id.json").optJSONArray("streams") ?: JSONArray()
                    (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { s ->
                        val url = s.optString("url")
                        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
                            if (s.has("infoHash")) torrents.incrementAndGet()
                            return@mapNotNull null
                        }
                        val headers = s.optJSONObject("behaviorHints")?.optJSONObject("proxyHeaders")?.optJSONObject("request")
                        fun h(name: String) = headers?.keys()?.asSequence()?.firstOrNull { it.equals(name, true) }?.let { headers.optString(it) }.orEmpty()
                        val title = listOf(s.optString("name"), s.optString("title").ifBlank { s.optString("description") })
                            .map { it.replace('\n', ' ').trim() }.filter { it.isNotBlank() }.joinToString(" · ")
                        Stream(url, title.ifBlank { "Video" }, a.name, h("User-Agent"), h("Referer"))
                    }
                }.getOrDefault(emptyList())
            }
        }.awaitAll()
        lists.flatten().distinctBy { it.url } to torrents.get()
    }

    // ---------- Subtítulos ----------
    data class Sub(val url: String, val lang: String, val addon: String)
    /** Subtítulos de las extensiones que los entregan (por id IMDb: "tt…" o "tt…:temporada:episodio"). */
    fun subtitles(addons: List<Addon>, type: String, id: String): List<Sub> =
        addons.filter { it.serves("subtitles", type, id) }.flatMap { a ->
            runCatching {
                val arr = get("${a.base}/subtitles/$type/$id.json").optJSONArray("subtitles") ?: JSONArray()
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { s ->
                    s.optString("url").takeIf { it.startsWith("http") }?.let { Sub(it, s.optString("lang").lowercase(), a.name) }
                }
            }.getOrDefault(emptyList())
        }
}
