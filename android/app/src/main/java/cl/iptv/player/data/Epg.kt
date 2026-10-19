package cl.iptv.player.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.Calendar
import java.util.TimeZone
import java.util.zip.GZIPInputStream

class Epg(internal val byId: Map<String, List<Programme>>, internal val nameToId: Map<String, String>) {

    val channelCount: Int get() = byId.size

    fun listFor(item: Item): List<Programme> {
        if (item.tvgId.isNotEmpty()) byId[item.tvgId]?.let { return it }
        for (n in listOf(item.tvgName, item.name)) {
            val id = nameToId[norm(n)] ?: continue
            byId[id]?.let { return it }
        }
        return emptyList()
    }

    fun nowNext(item: Item, now: Long = System.currentTimeMillis()): Pair<Programme?, Programme?> {
        val list = listFor(item)
        val i = list.indexOfFirst { it.start <= now && it.stop > now }
        return if (i < 0) null to list.firstOrNull { it.start > now } else list[i] to list.getOrNull(i + 1)
    }

    companion object {
        val EMPTY = Epg(emptyMap(), emptyMap())

        /** Combina varias guías; ante canales repetidos manda la primera. */
        fun merge(list: List<Epg>): Epg {
            val byId = LinkedHashMap<String, List<Programme>>()
            val names = HashMap<String, String>()
            list.forEach { e ->
                e.byId.forEach { (k, v) -> byId.putIfAbsent(k, v) }
                e.nameToId.forEach { (k, v) -> names.putIfAbsent(k, v) }
            }
            return Epg(byId, names)
        }
        fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
    }
}

/** Parser XMLTV en streaming (admite .gz). Sólo conserva una ventana de tiempo útil. */
object EpgParser {

    fun parse(input: InputStream, now: Long = System.currentTimeMillis()): Epg {
        val bis = BufferedInputStream(input, 64 * 1024)
        bis.mark(4)
        val b1 = bis.read(); val b2 = bis.read()
        bis.reset()
        val stream: InputStream = if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(bis, 64 * 1024) else bis

        val from = now - 3 * 3600_000L
        val to = now + 36 * 3600_000L
        val byId = HashMap<String, MutableList<Programme>>()
        val nameToId = HashMap<String, String>()

        val xp = Xml.newPullParser()
        xp.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        xp.setInput(stream, null)

        var channelId: String? = null
        var pStart = 0L; var pStop = 0L; var pChannel = ""; var title = ""; var desc = ""; var inProg = false

        var ev = xp.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (xp.name) {
                    "channel" -> channelId = xp.getAttributeValue(null, "id")
                    "display-name" -> channelId?.let { id -> nameToId[Epg.norm(safeText(xp))] = id }
                    "programme" -> {
                        inProg = true
                        pStart = parseTime(xp.getAttributeValue(null, "start"))
                        pStop = parseTime(xp.getAttributeValue(null, "stop"))
                        pChannel = xp.getAttributeValue(null, "channel").orEmpty()
                        title = ""; desc = ""
                    }
                    "title" -> if (inProg && title.isEmpty()) title = safeText(xp)
                    "desc" -> if (inProg && desc.isEmpty()) desc = safeText(xp)
                }
            } else if (ev == XmlPullParser.END_TAG) {
                when (xp.name) {
                    "channel" -> channelId = null
                    "programme" -> {
                        inProg = false
                        if (pStop > from && pStart < to && pStart > 0) {
                            byId.getOrPut(pChannel) { ArrayList() } += Programme(pStart, pStop, title, desc)
                        }
                    }
                }
            }
            ev = try { xp.next() } catch (e: Exception) { XmlPullParser.END_DOCUMENT } // XML truncado: nos quedamos con lo leído
        }
        byId.values.forEach { it.sortBy { p -> p.start } }
        return Epg(byId, nameToId)
    }

    private fun safeText(xp: XmlPullParser): String = try { xp.nextText().trim() } catch (e: Exception) { "" }

    /** "20261019120000 +0000" -> epoch ms */
    fun parseTime(s: String?): Long {
        if (s == null) return 0
        val t = s.trim()
        if (t.length < 12) return 0
        return try {
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            cal.clear()
            cal.set(
                t.substring(0, 4).toInt(), t.substring(4, 6).toInt() - 1, t.substring(6, 8).toInt(),
                t.substring(8, 10).toInt(), t.substring(10, 12).toInt(),
                if (t.length >= 14 && t[12].isDigit()) t.substring(12, 14).toInt() else 0,
            )
            var ms = cal.timeInMillis
            val tz = Regex("""([+-])(\d{2})(\d{2})""").find(t.substring(12))
            if (tz != null) {
                val sign = if (tz.groupValues[1] == "-") -1 else 1
                ms -= sign * (tz.groupValues[2].toInt() * 60 + tz.groupValues[3].toInt()) * 60_000L
            }
            ms
        } catch (e: Exception) { 0 }
    }
}
