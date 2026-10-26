package cl.iptv.player.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.Calendar
import java.util.TimeZone
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class Epg(
    internal val byId: Map<String, List<Programme>>,
    internal val nameToId: Map<String, String>,
    /** Guía pedida canal por canal al proveedor (Xtream) para los que no salen en el XMLTV: id del elemento → programas. */
    internal val extra: Map<String, List<Programme>> = emptyMap(),
) {

    val channelCount: Int get() = byId.size + extra.size

    /** El id del XMLTV sin distinguir mayúsculas (muchas listas escriben "Canal13.cl" y la guía "canal13.cl"). */
    private val idLower: Map<String, String> by lazy { byId.keys.associateBy { it.lowercase() } }
    /** Nombres "sueltos": sin país al principio ni HD / FHD / 4K al final ("CL: Canal 13 HD" = "Canal 13"). */
    private val looseToId: Map<String, String> by lazy {
        val m = HashMap<String, String>()
        nameToId.forEach { (k, v) -> m.putIfAbsent(loose(k), v) }
        byId.keys.forEach { id -> m.putIfAbsent(loose(id.substringBeforeLast('.')), id) }
        m
    }

    fun listFor(item: Item): List<Programme> {
        extra[item.id]?.let { if (it.isNotEmpty()) return it }
        if (item.tvgId.isNotEmpty()) {
            byId[item.tvgId]?.let { return it }
            idLower[item.tvgId.lowercase()]?.let { id -> byId[id]?.let { return it } }
        }
        for (n in listOf(item.tvgName, item.name)) {
            if (n.isBlank()) continue
            val id = nameToId[norm(n)] ?: continue
            byId[id]?.let { return it }
        }
        for (n in listOf(item.tvgName, item.name, item.tvgId.substringBeforeLast('.'))) {
            if (n.isBlank()) continue
            val id = looseToId[loose(n)] ?: continue
            byId[id]?.let { return it }
        }
        return emptyList()
    }

    /**
     * Buscar un programa en toda la guía: sin tildes ni mayúsculas, con todas las palabras ("colo colo" encuentra
     * "Colo-Colo vs. Universidad"), primero por título y después por descripción. Lo que se está dando ahora va
     * primero; después, por hora.
     */
    fun search(channels: List<Item>, query: String, from: Long, now: Long, limit: Int = 80): List<Pair<Item, Programme>> {
        val words = fold(query).split(' ').filter { it.length >= 2 }
        if (words.isEmpty()) return emptyList()
        val seen = HashSet<String>()
        val hits = ArrayList<Triple<Item, Programme, Int>>()
        for (ch in channels) {
            if (ch.kind != Kind.LIVE) continue
            for (p in listFor(ch)) {
                if (p.stop <= from) continue
                val score = when {
                    fold(p.title).let { t -> words.all { it in t } } -> 2
                    p.desc.isNotEmpty() && fold(p.desc).let { d -> words.all { it in d } } -> 1
                    else -> continue
                }
                if (!seen.add(ch.url + "|" + p.start)) continue                 // mismo canal repetido en la lista
                hits += Triple(ch, p, score)
                if (hits.size >= 2_000) break
            }
        }
        return hits.sortedWith(compareByDescending<Triple<Item, Programme, Int>> { it.second.start <= now && it.second.stop > now }
            .thenByDescending { it.third }.thenBy { it.second.start })
            .take(limit).map { it.first to it.second }
    }

    /** true si este canal ya tiene programación (para no pedirla de nuevo al proveedor). */
    fun has(item: Item) = listFor(item).isNotEmpty()

    fun withExtra(itemId: String, list: List<Programme>) = Epg(byId, nameToId, extra + (itemId to list))

    fun nowNext(item: Item, now: Long = System.currentTimeMillis()): Pair<Programme?, Programme?> {
        val list = listFor(item)
        val i = list.indexOfFirst { it.start <= now && it.stop > now }
        return if (i < 0) null to list.firstOrNull { it.start > now } else list[i] to list.getOrNull(i + 1)
    }

    /**
     * Guarda la guía ya leída en un archivo compacto: al abrir la app se carga en un par de segundos en vez de volver a
     * leer el XML completo (que en un TV box modesto puede tardar casi un minuto).
     */
    fun save(f: java.io.File) {
        val tmp = java.io.File(f.parentFile, f.name + ".part")
        java.io.DataOutputStream(java.io.BufferedOutputStream(GZIPOutputStream(tmp.outputStream()), 64 * 1024)).use { o ->
            o.writeInt(CACHE_VERSION)
            o.writeInt(byId.size)
            byId.forEach { (id, list) ->
                o.writeUTF(id); o.writeInt(list.size)
                list.forEach { p -> o.writeLong(p.start); o.writeLong(p.stop); o.writeUTF(p.title.take(300)); o.writeUTF(p.desc.take(MAX_DESC)) }
            }
            o.writeInt(nameToId.size)
            nameToId.forEach { (k, v) -> o.writeUTF(k); o.writeUTF(v) }
        }
        if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
    }

    companion object {
        val EMPTY = Epg(emptyMap(), emptyMap())
        private const val CACHE_VERSION = 1
        /** Descripciones más largas se cortan: es lo que más memoria ocupa y en la ficha no se lee más. */
        const val MAX_DESC = 600

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
        /** Para buscar: sin tildes, en minúsculas y con espacios en vez de signos. */
        fun fold(s: String): String = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "").lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

        private val QUALITY = Regex("(uhd|fhd|hd|sd|4k|8k|hevc|h265|h264|1080p?|720p?|2160p?|backup|bkp)+$")
        /** Nombre normalizado sin calidad al final. Recibe un nombre ya pasado por [norm] o uno original. */
        fun loose(s: String): String {
            // País al principio: "CL: Canal 13", "|AR| TN", "MX - Azteca" (se mira el original, antes de quitar signos)
            val raw = s.trim()
            val noCountry = Regex("^\\W*[A-Za-z]{2,3}\\s*[:|\\]\\-]+\\s*").replace(raw, "").ifBlank { raw }
            var n = norm(noCountry)
            repeat(2) { n = QUALITY.replace(n, "") }
            return n.ifBlank { norm(raw) }
        }

        /** Lee la guía guardada con [save]. null si el archivo no sirve (versión vieja o cortado). */
        fun load(f: java.io.File): Epg? = runCatching {
            java.io.DataInputStream(java.io.BufferedInputStream(GZIPInputStream(f.inputStream()), 64 * 1024)).use { i ->
                if (i.readInt() != CACHE_VERSION) return null
                val n = i.readInt()
                val byId = HashMap<String, List<Programme>>(n * 2)
                repeat(n) {
                    val id = i.readUTF(); val c = i.readInt()
                    byId[id] = List(c) { Programme(i.readLong(), i.readLong(), i.readUTF(), i.readUTF()) }
                }
                val m = i.readInt()
                val names = HashMap<String, String>(m * 2)
                repeat(m) { names[i.readUTF()] = i.readUTF() }
                Epg(byId, names)
            }
        }.getOrNull()
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

        // Ventana útil: 24 h hacia atrás (para ver en diferido lo de hoy) y 48 h hacia adelante; en modo liviano, menos
        // (la memoria de los TV box modestos es poca y una guía grande tiene cientos de miles de programas)
        val from = now - (if (Perf.lite) 8 else 24) * 3600_000L
        val to = now + (if (Perf.lite) 30 else 48) * 3600_000L
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
                    "desc" -> if (inProg && desc.isEmpty()) desc = safeText(xp).take(Epg.MAX_DESC)
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
