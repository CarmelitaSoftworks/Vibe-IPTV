package cl.iptv.player.data

import org.json.JSONObject
import java.net.URLEncoder

/** Ficha de una película o serie. */
data class Meta(
    val title: String = "", val year: String = "", val rating: String = "", val genres: String = "",
    val plot: String = "", val cast: String = "", val director: String = "", val runtime: String = "",
    val poster: String = "", val backdrop: String = "", val source: String = "",
) {
    val isEmpty: Boolean get() = plot.isBlank() && cast.isBlank() && genres.isBlank() && year.isBlank()
}

/**
 * Busca la ficha: primero en el servidor Xtream (no necesita clave) y, si no hay o es una lista M3U,
 * en TMDB con la clave gratuita del usuario (Ajustes → Fichas).
 */
object Metadata {
    private val cache = HashMap<String, Meta?>()
    private const val IMG = "https://image.tmdb.org/t/p/"

    private fun JSONObject.s(vararg keys: String): String =
        keys.firstNotNullOfOrNull { k -> optString(k).takeIf { it.isNotBlank() && it != "null" } }.orEmpty()

    private fun fromXtream(o: JSONObject): Meta {
        val backdrop = o.optJSONArray("backdrop_path")?.optString(0).orEmpty()
        val rating = o.s("rating_5based").toDoubleOrNull()?.let { "%.1f/5".format(it) } ?: o.s("rating").takeIf { it != "0" }.orEmpty()
        return Meta(
            title = o.s("name", "o_name", "title"), year = o.s("releasedate", "releaseDate", "release_date", "year").take(4),
            rating = rating, genres = o.s("genre"), plot = o.s("plot", "description"), cast = o.s("cast", "actors"),
            director = o.s("director"), runtime = o.s("duration"),
            poster = o.s("movie_image", "cover", "cover_big"), backdrop = backdrop, source = "Servidor",
        )
    }

    private val YEAR = Regex("""\((19|20)\d\d\)|\b(19|20)\d\d\b""")

    suspend fun tmdb(item: Item, key: String, lang: String): Meta? {
        if (key.isBlank()) return null
        val title = cleanTitle(item.seriesName.ifBlank { item.name })
        val year = YEAR.find(item.name)?.value?.filter { it.isDigit() }.orEmpty()
        val tv = item.kind == Kind.SERIES || item.seriesName.isNotBlank()
        val path = if (tv) "search/tv" else "search/movie"
        val bearer = key.length > 60
        var url = "https://api.themoviedb.org/3/$path?query=${URLEncoder.encode(title, "UTF-8")}&language=$lang&include_adult=false" +
            (if (year.isNotBlank()) (if (tv) "&first_air_date_year=$year" else "&year=$year") else "")
        if (!bearer) url += "&api_key=$key"
        val res = JSONObject(Http.textWithHeaders(url, if (bearer) mapOf("Authorization" to "Bearer $key") else emptyMap()))
        val r = res.optJSONArray("results")?.optJSONObject(0) ?: return null
        return Meta(
            title = r.s("title", "name"), year = r.s("release_date", "first_air_date").take(4),
            rating = r.optDouble("vote_average", 0.0).takeIf { it > 0 }?.let { "%.1f/10".format(it) }.orEmpty(),
            plot = r.s("overview"), poster = r.s("poster_path").takeIf { it.isNotBlank() }?.let { IMG + "w500" + it }.orEmpty(),
            backdrop = r.s("backdrop_path").takeIf { it.isNotBlank() }?.let { IMG + "w780" + it }.orEmpty(), source = "TMDB",
        )
    }

    suspend fun forItem(item: Item, xtream: XtreamClient?, tmdbKey: String, lang: String): Meta? {
        val k = item.id + lang
        if (cache.containsKey(k)) return cache[k]
        var m = xtream?.info(item)?.let(::fromXtream)?.takeUnless { it.isEmpty }
        if (m == null || m.plot.isBlank()) runCatching { tmdb(item, tmdbKey, lang) }.getOrNull()?.let { t ->
            m = if (m == null) t else m!!.copy(plot = t.plot, rating = m!!.rating.ifBlank { t.rating }, backdrop = m!!.backdrop.ifBlank { t.backdrop }, source = "Servidor · TMDB")
        }
        cache[k] = m
        return m
    }
}
