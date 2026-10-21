package cl.iptv.player.data

import android.net.Uri
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Cliente de la API Xtream Codes (player_api.php). */
class XtreamClient(server: String, private val user: String, private val pass: String, private val liveFormat: String) {

    var base: String = normalize(server)
        private set
    /** Dirección que funcionó al iniciar sesión (puede diferir de la escrita). */
    var workingBase: String? = null
        private set
    private val u = Uri.encode(user)
    private val p = Uri.encode(pass)

    companion object {
        fun normalize(server: String): String {
            var s = server.trim().trimEnd('/')
            if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
            return s.replace(Regex("/(player_api|get)\\.php.*$", RegexOption.IGNORE_CASE), "")
        }
    }

    private suspend fun api(action: String?, extra: Map<String, String> = emptyMap()): String {
        val b = Uri.parse("$base/player_api.php").buildUpon()
            .appendQueryParameter("username", user)
            .appendQueryParameter("password", pass)
        action?.let { b.appendQueryParameter("action", it) }
        extra.forEach { (k, v) -> b.appendQueryParameter(k, v) }
        return Http.text(b.build().toString())
    }

    private suspend fun apiArray(action: String): JSONArray =
        runCatching { JSONArray(api(action)) }.getOrDefault(JSONArray())

    /** Variantes si la dirección escrita no responde: http en vez de https y puertos habituales de Xtream. */
    private fun candidateBases(): List<String> {
        val u = Uri.parse(base)
        val out = linkedSetOf(base)
        val host = u.host ?: return out.toList()
        val path = u.path.orEmpty().trimEnd('/')
        if (u.scheme == "https") out += "http://$host${if (u.port > 0) ":${u.port}" else ""}$path"
        if (u.port <= 0) listOf(8080, 80, 25461, 8000, 2095).forEach { p -> out += "http://$host${if (p == 80) "" else ":$p"}$path" }
        return out.toList()
    }

    suspend fun login() {
        var firstErr: Exception? = null
        val bases = candidateBases()
        for (b in bases) {
            base = b
            try {
                val o = JSONObject(api(null))
                val ui = o.optJSONObject("user_info") ?: throw IOException("La dirección no corresponde a un servidor Xtream")
                if (ui.optString("auth") != "1") throw SecurityException("Usuario o contraseña incorrectos")
                val status = ui.optString("status")
                if (status.isNotBlank() && !status.equals("Active", true)) throw SecurityException("La cuenta está ${status.lowercase()}")
                serverTz = o.optJSONObject("server_info")?.optString("timezone").orEmpty()
                account = Account(
                    expiresAt = ui.optString("exp_date").toLongOrNull()?.takeIf { it > 0 }?.times(1000),
                    maxConnections = ui.optString("max_connections").toIntOrNull() ?: 0,
                    activeConnections = ui.optString("active_cons").toIntOrNull() ?: 0,
                    trial = ui.optString("is_trial") == "1",
                )
                workingBase = b
                return
            } catch (e: SecurityException) {
                throw IOException(e.message)          // el servidor respondió: no seguir probando
            } catch (e: Exception) {
                if (firstErr == null) firstErr = e
            }
        }
        base = bases.first()
        throw IOException(friendly(firstErr))
    }

    private fun friendly(e: Exception?): String = when (e) {
        is java.net.UnknownHostException -> "No se encontró el dominio del servidor (revisa la dirección)"
        is java.net.ConnectException -> "El servidor rechazó la conexión (¿falta el puerto, por ejemplo :8080?)"
        is java.net.SocketTimeoutException -> "El servidor no responde (revisa la dirección y el puerto)"
        is javax.net.ssl.SSLException -> "Error de conexión segura: prueba con http:// en vez de https://"
        null -> "No se pudo conectar con el servidor"
        else -> e.message ?: e.toString()
    }

    /** Datos de la cuenta que entrega el servidor al iniciar sesión (vencimiento y conexiones). */
    data class Account(val expiresAt: Long?, val maxConnections: Int, val activeConnections: Int, val trial: Boolean)
    var account: Account? = null; private set

    /** Para guardar la lista en disco sin el usuario ni la contraseña; [restore] los vuelve a poner al leerla. */
    fun scrub(s: String) = s.replace("/$u/$p/", "/{xu}/{xp}/").replace("username=$u&password=$p", "username={xu}&password={xp}")
    fun restore(s: String) = s.replace("/{xu}/{xp}/", "/$u/$p/").replace("username={xu}&password={xp}", "username=$u&password=$p")

    /** Zona horaria del servidor (para TV en diferido). */
    var serverTz: String = ""; private set

    fun liveUrl(id: Any) = "$base/live/$u/$p/$id.$liveFormat"
    /** TV en diferido de Xtream: /timeshift/usuario/clave/minutos/AAAA-MM-DD:HH-MM/id.ts */
    fun catchupUrl(id: Any) = "$base/timeshift/$u/$p/{dur}/{start}/$id.ts"
    fun movieUrl(id: Any, ext: String) = "$base/movie/$u/$p/$id.${ext.ifBlank { "mp4" }}"
    fun episodeUrl(id: Any, ext: String) = "$base/series/$u/$p/$id.${ext.ifBlank { "mp4" }}"
    fun epgUrl() = "$base/xmltv.php?username=$u&password=$p"

    private fun cats(a: JSONArray): Map<String, String> =
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }
            .associate { it.optString("category_id") to it.optString("category_name") }

    private inline fun JSONArray.objects(block: (JSONObject) -> Unit) {
        for (i in 0 until length()) optJSONObject(i)?.let(block)
    }

    suspend fun loadAll(): List<Item> = coroutineScope {
        val lc = async { apiArray("get_live_categories") }
        val ls = async { apiArray("get_live_streams") }
        val vc = async { apiArray("get_vod_categories") }
        val vs = async { apiArray("get_vod_streams") }
        val sc = async { apiArray("get_series_categories") }
        val ss = async { apiArray("get_series") }
        val lcm = cats(lc.await()); val vcm = cats(vc.await()); val scm = cats(sc.await())
        val out = ArrayList<Item>()
        ls.await().objects { s ->
            val id = s.optString("stream_id")
            out += Item(
                id = "l$id", kind = Kind.LIVE, name = s.optString("name", "Canal"),
                logo = s.optString("stream_icon"), group = lcm[s.optString("category_id")] ?: "Sin categoría",
                url = liveUrl(id), tvgId = s.optString("epg_channel_id").takeUnless { it == "null" }.orEmpty(),
                tvgName = s.optString("name"), num = s.optInt("num").takeIf { it > 0 },
                archiveDays = if (s.optString("tv_archive") == "1") s.optString("tv_archive_duration").toIntOrNull() ?: 0 else 0,
                catchup = if (s.optString("tv_archive") == "1") catchupUrl(id) else "",
                catchupTz = serverTz,
            )
        }
        vs.await().objects { s ->
            val id = s.optString("stream_id")
            out += Item(
                id = "v$id", kind = Kind.MOVIE, name = s.optString("name", "Película"),
                logo = s.optString("stream_icon"), group = vcm[s.optString("category_id")] ?: "Sin categoría",
                url = movieUrl(id, s.optString("container_extension")),
            )
        }
        ss.await().objects { s ->
            val id = s.optInt("series_id")
            out += Item(
                id = "s$id", kind = Kind.SERIES, name = s.optString("name", "Serie"),
                logo = s.optString("cover"), group = scm[s.optString("category_id")] ?: "Sin categoría",
                seriesId = id,
            )
        }
        out
    }

    /** Ficha de una película o serie que entrega el propio servidor (sinopsis, reparto, género…). */
    suspend fun info(item: Item): JSONObject? = runCatching {
        when {
            item.kind == Kind.SERIES && item.seriesId != null ->
                JSONObject(api("get_series_info", mapOf("series_id" to item.seriesId.toString()))).optJSONObject("info")
            item.kind == Kind.MOVIE && item.id.startsWith("v") ->
                JSONObject(api("get_vod_info", mapOf("vod_id" to item.id.drop(1)))).optJSONObject("info")
            else -> null
        }
    }.getOrNull()

    suspend fun episodes(seriesId: Int): List<Item> {
        val o = JSONObject(api("get_series_info", mapOf("series_id" to seriesId.toString())))
        val cover = o.optJSONObject("info")?.optString("cover").orEmpty()
        val seriesName = o.optJSONObject("info")?.optString("name").orEmpty()
        val lists = mutableListOf<JSONArray>()
        when (val eps = o.opt("episodes")) {
            is JSONObject -> eps.keys().forEach { k -> eps.optJSONArray(k)?.let(lists::add) }
            is JSONArray -> {
                // Algunos servidores devuelven [[...], [...]] o una lista plana
                if (eps.optJSONArray(0) != null) for (i in 0 until eps.length()) eps.optJSONArray(i)?.let(lists::add)
                else lists += eps
            }
        }
        val out = ArrayList<Item>()
        lists.forEach { arr ->
            arr.objects { e ->
                val season = e.optInt("season")
                val ep = e.optInt("episode_num")
                out += Item(
                    id = "e${e.optString("id")}", kind = Kind.MOVIE,
                    name = "T$season E$ep · ${e.optString("title")}",
                    logo = e.optJSONObject("info")?.optString("movie_image")?.ifBlank { cover } ?: cover,
                    group = "Temporada $season", season = season, episode = ep, seriesName = seriesName,
                    url = episodeUrl(e.optString("id"), e.optString("container_extension")),
                )
            }
        }
        return out.sortedWith(compareBy({ it.season }, { it.episode }))
    }
}
