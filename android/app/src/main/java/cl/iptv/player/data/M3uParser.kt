package cl.iptv.player.data

import java.io.BufferedReader

object M3uParser {
    data class Result(val epgUrl: String, val items: List<Item>)

    private val attrRe = Regex("""([\w-]+)\s*=\s*"([^"]*)"""")

    private fun attrs(s: String): Map<String, String> =
        attrRe.findAll(s).associate { it.groupValues[1].lowercase() to it.groupValues[2] }

    /** Primera coma fuera de comillas: separa atributos del título. */
    private fun titleComma(line: String): Int {
        var inQ = false
        line.forEachIndexed { i, c ->
            if (c == '"') inQ = !inQ else if (c == ',' && !inQ) return i
        }
        return -1
    }

    /** Episodios: S01E02, S1 E2, T1E2, 1x02 */
    val EP_RE = Regex("""^(.*?)[\s._\-|:]*(?:S(\d{1,2})\s*E(\d{1,3})|T(\d{1,2})\s*E(\d{1,3})|\b(\d{1,2})x(\d{2,3}))\b(.*)$""", RegexOption.IGNORE_CASE)
    private val VOD_EXT = Regex("""\.(mp4|mkv|avi|mov|m4v|wmv|flv|webm|mpg|mpeg)$""", RegexOption.IGNORE_CASE)
    private val SERIES_GROUP = Regex("""\b(series?|temporadas?|seasons?|novelas?|tv ?shows?|animes?)\b""", RegexOption.IGNORE_CASE)
    private val MOVIE_GROUP = Regex("""\b(pel[ií]culas?|movies?|vod|cine|films?|estrenos|peliculas)\b""", RegexOption.IGNORE_CASE)

    private enum class Raw { LIVE, MOVIE, EPISODE }

    /** Clasifica en TV en vivo, película o episodio usando URL, extensión, grupo y nombre. */
    private fun classify(url: String, group: String, name: String): Raw {
        val path = url.substringBefore('?')
        return when {
            path.contains("/series/", true) -> Raw.EPISODE
            path.contains("/movie/", true) -> Raw.MOVIE
            EP_RE.containsMatchIn(name) && (SERIES_GROUP.containsMatchIn(group) || VOD_EXT.containsMatchIn(path) ||
                !(path.endsWith(".m3u8", true) || path.endsWith(".ts", true))) -> Raw.EPISODE
            SERIES_GROUP.containsMatchIn(group) -> Raw.EPISODE
            MOVIE_GROUP.containsMatchIn(group) -> Raw.MOVIE
            VOD_EXT.containsMatchIn(path) -> Raw.MOVIE
            else -> Raw.LIVE
        }
    }

    private fun normKey(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "").replace(Regex("[^a-z0-9]+"), " ").trim()

    /** Agrupa los episodios sueltos en series que contienen sus episodios. */
    private fun groupSeries(items: List<Pair<Item, Raw>>): List<Item> {
        val out = ArrayList<Any>()            // Item o clave de serie (para conservar el orden)
        val series = LinkedHashMap<String, Pair<Item, MutableList<Item>>>()
        for ((item, raw) in items) {
            if (raw != Raw.EPISODE) { out += item; continue }
            val m = EP_RE.find(item.name)
            val title = ((m?.groupValues?.get(1)?.takeIf { t -> t.isNotBlank() }) ?: item.name).trimEnd(' ', '-', '|', ':', '.').trim().ifEmpty { item.name }
            val g = m?.groupValues
            val season = g?.let { v -> (v[2].ifEmpty { v[4] }.ifEmpty { v[6] }).toIntOrNull() } ?: 1
            val episode = g?.let { v -> (v[3].ifEmpty { v[5] }.ifEmpty { v[7] }).toIntOrNull() } ?: 0
            val key = normKey(title) + "|" + item.group
            val entry = series.getOrPut(key) {
                out += key
                Item(id = "s" + key.hashCode().toUInt().toString(36), kind = Kind.SERIES, name = title, logo = item.logo, group = item.group) to ArrayList()
            }
            val rest = g?.get(8)?.trimStart(' ', '-', '|', ':', '.')?.trim().orEmpty()
            entry.second += item.copy(
                kind = Kind.MOVIE, season = season, episode = episode, seriesName = title, group = "Temporada $season",
                name = if (m != null) "T$season E$episode" + (if (rest.isNotEmpty()) " · $rest" else "") else item.name,
            )
        }
        return out.map { o ->
            if (o is Item) o else series.getValue(o as String).let { (s, eps) ->
                s.copy(episodes = eps.sortedWith(compareBy({ e -> e.season }, { e -> e.episode })))
            }
        }
    }

    private class Pending(val name: String, val logo: String, var group: String, val tvgId: String, val tvgName: String)

    fun parse(reader: BufferedReader): Result {
        var header = emptyMap<String, String>()
        val items = ArrayList<Pair<Item, Raw>>(4096)
        val seen = HashMap<String, Int>()
        var cur: Pending? = null
        var ua = ""
        var ref = ""

        reader.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTM3U") -> header = attrs(line)
                line.startsWith("#EXTINF") -> {
                    val idx = titleComma(line)
                    val meta = if (idx >= 0) line.substring(0, idx) else line
                    val title = if (idx >= 0) line.substring(idx + 1).trim() else ""
                    val a = attrs(meta)
                    cur = Pending(
                        name = title.ifEmpty { a["tvg-name"] ?: "Sin nombre" },
                        logo = a["tvg-logo"].orEmpty(),
                        group = a["group-title"].orEmpty(),
                        tvgId = a["tvg-id"].orEmpty(),
                        tvgName = a["tvg-name"].orEmpty(),
                    )
                }
                line.startsWith("#EXTGRP:") -> cur?.let { if (it.group.isEmpty()) it.group = line.substring(8).trim() }
                line.startsWith("#EXTVLCOPT:") -> {
                    val kv = line.substring(11)
                    val k = kv.substringBefore('=')
                    val v = kv.substringAfter('=', "")
                    if (k.contains("user-agent", true)) ua = v
                    if (k.contains("referrer", true) || k.contains("referer", true)) ref = v
                }
                line.startsWith("#") -> Unit
                else -> {
                    val p = cur
                    var id = "m" + line.hashCode().toUInt().toString(36)
                    val n = seen.getOrDefault(id, 0)
                    seen[id] = n + 1
                    if (n > 0) id += "_$n"
                    val name = p?.name ?: line.substringAfterLast('/')
                    val group = p?.group?.ifEmpty { null } ?: "Sin categoría"
                    val raw = classify(line, group, name)
                    items += Item(
                        id = id, kind = if (raw == Raw.LIVE) Kind.LIVE else Kind.MOVIE, url = line,
                        name = name,
                        logo = p?.logo.orEmpty(),
                        group = group,
                        tvgId = p?.tvgId.orEmpty(), tvgName = p?.tvgName.orEmpty(),
                        userAgent = ua, referrer = ref,
                    ) to raw
                    cur = null; ua = ""; ref = ""
                }
            }
        }
        val epg = (header["x-tvg-url"] ?: header["url-tvg"]).orEmpty().trim()
        return Result(epg, groupSeries(items))
    }
}
