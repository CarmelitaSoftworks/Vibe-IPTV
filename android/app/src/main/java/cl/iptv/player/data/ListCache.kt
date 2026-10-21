package cl.iptv.player.data

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Lista guardada en disco ya procesada (formato binario propio). Al abrir la app se muestra al instante,
 * sin esperar al servidor, y sigue disponible sin internet. Se actualiza en segundo plano.
 * Las URL de Xtream se guardan sin usuario ni contraseña (ver [XtreamClient.scrub]).
 */
object ListCache {
    private const val MAGIC = 0x56494245            // "VIBE"
    private const val VERSION = 1
    private const val MAX_ITEMS = 5_000_000
    private const val MAX_STR = 8 * 1024 * 1024

    class Cached(val items: List<Item>, val epgUrl: String, val savedAt: Long)

    private fun file(ctx: Context, sourceId: String) =
        File(File(ctx.filesDir, "listas").apply { mkdirs() }, sourceId.replace(Regex("[^\\w-]"), "_") + ".bin")

    fun delete(ctx: Context, sourceId: String) { runCatching { file(ctx, sourceId).delete() } }

    fun save(ctx: Context, sourceId: String, items: List<Item>, epgUrl: String, scrub: (String) -> String = { it }) {
        val f = file(ctx, sourceId)
        val tmp = File(f.parentFile, f.name + ".part")
        DataOutputStream(BufferedOutputStream(tmp.outputStream(), 256 * 1024)).use { o ->
            o.writeInt(MAGIC); o.writeInt(VERSION); o.writeLong(System.currentTimeMillis())
            o.str(scrub(epgUrl))
            o.writeInt(items.size)
            items.forEach { o.item(it, scrub) }
        }
        if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
    }

    /** null si no hay lista guardada o si el archivo está dañado (en ese caso se borra). */
    fun load(ctx: Context, sourceId: String, restore: (String) -> String = { it }): Cached? {
        val f = file(ctx, sourceId)
        if (!f.exists()) return null
        return runCatching {
            DataInputStream(BufferedInputStream(f.inputStream(), 256 * 1024)).use { i ->
                require(i.readInt() == MAGIC && i.readInt() == VERSION) { "formato distinto" }
                val at = i.readLong()
                val epg = restore(i.str())
                val n = i.readInt(); require(n in 0..MAX_ITEMS)
                Cached(List(n) { i.item(restore) }, epg, at)
            }
        }.getOrElse { f.delete(); null }
    }

    private fun DataOutputStream.str(s: String) { val b = s.toByteArray(Charsets.UTF_8); writeInt(b.size); write(b) }
    private fun DataInputStream.str(): String {
        val n = readInt(); require(n in 0..MAX_STR)
        return if (n == 0) "" else ByteArray(n).also { readFully(it) }.toString(Charsets.UTF_8)
    }
    private fun DataOutputStream.optInt(v: Int?) { writeBoolean(v != null); if (v != null) writeInt(v) }
    private fun DataInputStream.optInt(): Int? = if (readBoolean()) readInt() else null

    private fun DataOutputStream.item(it: Item, scrub: (String) -> String) {
        str(it.id); writeByte(it.kind.ordinal); str(it.name); str(it.logo); str(it.group); str(scrub(it.url))
        str(it.tvgId); str(it.tvgName); optInt(it.num); optInt(it.seriesId); writeInt(it.season); writeInt(it.episode)
        str(it.userAgent); str(it.referrer); str(it.seriesName)
        writeInt(it.episodes.size); it.episodes.forEach { e -> item(e, scrub) }
        str(it.cat); str(it.sub); writeInt(it.archiveDays); str(scrub(it.catchup)); str(it.catchupTz)
    }

    private fun DataInputStream.item(restore: (String) -> String): Item {
        val id = str(); val kind = Kind.entries[readByte().toInt()]; val name = str(); val logo = str(); val group = str(); val url = restore(str())
        val tvgId = str(); val tvgName = str(); val num = optInt(); val seriesId = optInt(); val season = readInt(); val episode = readInt()
        val ua = str(); val ref = str(); val seriesName = str()
        val ne = readInt(); require(ne in 0..MAX_ITEMS)
        val eps = List(ne) { item(restore) }
        return Item(
            id = id, kind = kind, name = name, logo = logo, group = group, url = url, tvgId = tvgId, tvgName = tvgName,
            num = num, seriesId = seriesId, season = season, episode = episode, userAgent = ua, referrer = ref,
            seriesName = seriesName, episodes = eps, cat = str(), sub = str(), archiveDays = readInt(),
            catchup = restore(str()), catchupTz = str(),
        )
    }
}
