package cl.iptv.player.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Persistencia simple con SharedPreferences + JSON. */
class Storage(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("iptv", Context.MODE_PRIVATE)

    fun sources(): List<Source> {
        val arr = runCatching { JSONArray(prefs.getString("sources", "[]")) }.getOrDefault(JSONArray())
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map {
            Source(
                id = it.getString("id"), name = it.optString("name"),
                type = runCatching { SourceType.valueOf(it.optString("type")) }.getOrDefault(SourceType.M3U),
                url = it.optString("url"), server = it.optString("server"),
                username = it.optString("username"), password = it.optString("password"),
                epgUrl = it.optString("epgUrl"), localFile = it.optBoolean("localFile"),
            )
        }
    }

    fun saveSources(list: List<Source>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().apply {
                put("id", it.id); put("name", it.name); put("type", it.type.name); put("url", it.url)
                put("server", it.server); put("username", it.username); put("password", it.password)
                put("epgUrl", it.epgUrl); put("localFile", it.localFile)
            })
        }
        prefs.edit().putString("sources", arr.toString()).apply()
    }

    fun upsert(src: Source) {
        val list = sources().toMutableList()
        val i = list.indexOfFirst { it.id == src.id }
        if (i >= 0) list[i] = src else list += src
        saveSources(list)
    }

    fun remove(id: String) {
        saveSources(sources().filterNot { it.id == id })
        prefs.edit().remove("favs_$id").apply()
        localM3u(id).delete()
    }

    fun localM3u(id: String) = File(ctx.filesDir, "m3u_$id.m3u")

    fun favorites(sourceId: String): Set<String> = prefs.getStringSet("favs_$sourceId", emptySet())!!.toSet()
    fun saveFavorites(sourceId: String, set: Set<String>) = prefs.edit().putStringSet("favs_$sourceId", set).apply()

    var lastSourceId: String?
        get() = prefs.getString("last_source", null)
        set(v) = prefs.edit().putString("last_source", v).apply()

    fun settings(): Settings {
        val d = Settings()
        fun str(k: String, def: String) = prefs.getString(k, def) ?: def
        return Settings(
            liveFormat = str("liveFormat", d.liveFormat), osApiKey = str("osApiKey", ""), osUser = str("osUser", ""),
            osPass = str("osPass", ""), subLangs = str("subLangs", d.subLangs),
            showNumbers = prefs.getBoolean("showNumbers", d.showNumbers), showEpgInfo = prefs.getBoolean("showEpgInfo", d.showEpgInfo),
            autoNext = prefs.getBoolean("autoNext", d.autoNext), animations = prefs.getBoolean("animations", d.animations),
            lang = str("lang", d.lang), theme = str("theme", d.theme), accent = str("accent", d.accent), tileSize = str("tileSize", d.tileSize),
            epgEvery = prefs.getInt("epgEvery", d.epgEvery), epgOffset = prefs.getInt("epgOffset", d.epgOffset),
            subSize = str("subSize", d.subSize), startup = str("startup", d.startup),
            engine = str("engine", d.engine), aspect = str("aspect", d.aspect), subdlKey = str("subdlKey", ""),
        )
    }

    fun saveSettings(s: Settings) = prefs.edit()
        .putString("liveFormat", s.liveFormat).putString("osApiKey", s.osApiKey)
        .putString("osUser", s.osUser).putString("osPass", s.osPass).putString("subLangs", s.subLangs)
        .putBoolean("showNumbers", s.showNumbers).putBoolean("showEpgInfo", s.showEpgInfo)
        .putBoolean("autoNext", s.autoNext).putBoolean("animations", s.animations)
        .putString("lang", s.lang).putString("theme", s.theme).putString("accent", s.accent).putString("tileSize", s.tileSize)
        .putInt("epgEvery", s.epgEvery).putInt("epgOffset", s.epgOffset)
        .putString("subSize", s.subSize).putString("startup", s.startup)
        .putString("engine", s.engine).putString("aspect", s.aspect).putString("subdlKey", s.subdlKey)
        .apply()

    var lastItemId: String?
        get() = prefs.getString("last_item", null)
        set(v) = prefs.edit().putString("last_item", v).apply()

    fun clearFavorites(sourceId: String) = prefs.edit().remove("favs_$sourceId").apply()
}
