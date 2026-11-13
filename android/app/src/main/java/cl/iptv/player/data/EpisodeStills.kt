package cl.iptv.player.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Imágenes reales de cada episodio (una escena del capítulo) en vez de la portada de la serie repetida o una imagen
 * genérica del proveedor. Vienen del catálogo abierto de Stremio (Cinemeta), sin clave. Sólo se usan si la serie se
 * identifica con seguridad (mismo año o mismo nombre): mejor la imagen del proveedor que escenas de otra serie.
 */
object EpisodeStills {
    private val cache = ConcurrentHashMap<String, Map<String, String>>()

    /** Clave de un episodio en el mapa que devuelve [find]. */
    fun key(season: Int, episode: Int) = "$season:$episode"

    /** Imagen de cada episodio ("temporada:episodio" → dirección), probando los nombres en orden. Vacío si no se encuentra. */
    suspend fun find(titles: List<String>, year: String): Map<String, String> = withContext(Dispatchers.IO) {
        val names = titles.map { it.trim() }.filter { it.length >= 2 }.distinct()
        val cacheKey = names.joinToString("|") + "#" + year
        cache[cacheKey]?.let { return@withContext it }
        val out = runCatching {
            val id = names.firstNotNullOfOrNull { imdbFor(it, year) } ?: return@runCatching emptyMap()
            val videos = json("https://v3-cinemeta.strem.io/meta/series/$id.json").optJSONObject("meta")?.optJSONArray("videos")
                ?: return@runCatching emptyMap()
            buildMap {
                for (i in 0 until videos.length()) {
                    val v = videos.optJSONObject(i) ?: continue
                    val s = v.optInt("season", -1); val e = v.optInt("episode", v.optInt("number", -1))
                    val img = v.optString("thumbnail")
                    if (s <= 0 || e <= 0 || !img.startsWith("http")) continue
                    // El catálogo a veces apunta a la temporada equivocada ("0" = especiales): con su servidor de
                    // imágenes se arma la dirección con la temporada y el episodio correctos
                    put(key(s, e), if ("episodes.metahub.space/" in img) "https://episodes.metahub.space/$id/$s/$e/w780.jpg" else img)
                }
            }
        }.getOrDefault(emptyMap())
        if (out.isNotEmpty()) cache[cacheKey] = out
        out
    }

    private fun imdbFor(title: String, year: String): String? {
        val q = java.net.URLEncoder.encode(title, "UTF-8").replace("+", "%20")
        val metas = json("https://v3-cinemeta.strem.io/catalog/series/top/search=$q.json").optJSONArray("metas") ?: return null
        val list = (0 until metas.length()).mapNotNull { metas.optJSONObject(it) }
            .map { SubtitleSources.CatalogEntry(it.optString("name"), it.optString("releaseInfo"), it.optString("imdb_id").ifBlank { it.optString("id") }) }
        return SubtitleSources.pickMeta(list, title, year, strict = true)?.id?.takeIf { it.startsWith("tt") }
    }

    private fun json(url: String): JSONObject =
        Http.client.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
            JSONObject(r.body!!.string())
        }
}
