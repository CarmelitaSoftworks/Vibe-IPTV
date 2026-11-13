package cl.iptv.player.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Ver torrents (opcional, desactivado por defecto en Ajustes → Reproducción). Se baja en orden el archivo de video
 * elegido, con prioridad a lo que se está viendo, y se entrega al reproductor por un servidor local (sólo en este
 * equipo, 127.0.0.1), así funciona igual con ExoPlayer, VLC o MPV y se puede adelantar.
 *
 * BitTorrent comparte pedazos con otras personas mientras se ve (y ellas ven tu IP): la subida va limitada y al cerrar
 * el video se deja de compartir y se borra todo lo bajado.
 */
object TorrentStream {
    private const val VIDEO = "mp4|mkv|avi|m4v|mov|webm|ts|wmv|flv|mpg|mpeg"

    private class Active(
        val handle: TorrentHandle, val ti: TorrentInfo, val dir: File, val file: File,
        val offset: Long, val size: Long, val pieceLen: Int, val name: String,
    )

    private var session: SessionManager? = null
    @Volatile private var active: Active? = null
    /** Cambia en cada cierre: un torrent que se estaba abriendo sabe que ya no corresponde (se cerró o se cortó la VPN). */
    @Volatile private var gen = 0
    @Volatile private var vpnLost = false
    @Volatile private var opening = false
    private var server: ServerSocket? = null
    private val lock = Any()

    /** Enlace magnet a partir del hash y los rastreadores ("tracker:udp://…" de Stremio o direcciones sueltas). */
    fun magnet(infoHash: String, sources: List<String> = emptyList(), name: String = ""): String {
        val tr = sources.mapNotNull { s ->
            val t = s.removePrefix("tracker:")
            t.takeIf { it.startsWith("udp://") || it.startsWith("http://") || it.startsWith("https://") || it.startsWith("wss://") }
        }.distinct()
        return buildString {
            append("magnet:?xt=urn:btih:").append(infoHash.lowercase())
            if (name.isNotBlank()) append("&dn=").append(java.net.URLEncoder.encode(name, "UTF-8"))
            tr.forEach { append("&tr=").append(java.net.URLEncoder.encode(it, "UTF-8")) }
        }
    }

    const val NO_VPN = "No hay una VPN conectada: conéctala o desactiva «Torrents: sólo con VPN» en Ajustes"

    fun isTorrent(url: String) = url.startsWith("magnet:", true)

    /** Índice del archivo pedido en el enlace (vibe_file=N), si lo trae. */
    private fun fileIdxOf(magnet: String) = Regex("[?&]vibe_file=(\\d+)").find(magnet)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * Abre el torrent y devuelve la dirección local para el reproductor. [onStatus] cuenta en qué va (buscando pares,
     * preparando…). Cierra el torrent anterior.
     */
    suspend fun open(
        ctx: Context, magnet: String, uploadKBps: Int, requireVpn: Boolean = false,
        onVpnLost: () -> Unit = {}, onStatus: (String) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        stop()
        if (requireVpn && !vpnOn(ctx)) throw IOException(NO_VPN)
        val myGen = gen
        vpnLost = false
        opening = true
        // Corte de seguridad desde el primer momento (también mientras se buscan personas y bajan las primeras piezas)
        if (requireVpn) watchVpn(ctx, myGen, onVpnLost)
        fun check() { if (gen != myGen) throw IOException(if (vpnLost) NO_VPN else "Se cerró antes de abrir") }
        try {
            val dir = File(ctx.cacheDir, "torrent").apply { deleteRecursively(); mkdirs() }
            val sm = synchronized(lock) {
                check()                                               // (si la VPN ya se cortó, no se crea otro motor)
                session ?: SessionManager(false).also { s ->
                    val pack = SettingsPack().uploadRateLimit(uploadKBps.coerceAtLeast(1) * 1024)
                    s.start(SessionParams(pack))
                    session = s
                }
            }
            sm.uploadRateLimit(uploadKBps.coerceAtLeast(1) * 1024)
            onStatus("Buscando a quién pedirle el video…")
            val bytes = sm.fetchMagnet(magnet.substringBefore("&vibe_file="), 60, dir)
            check()
            if (bytes == null) throw IOException("No se encontraron personas compartiendo este torrent (prueba otra opción)")
            val ti = TorrentInfo.bdecode(bytes)
            val fs = ti.files()
            val idx = fileIdxOf(magnet)?.takeIf { it in 0 until fs.numFiles() }
                ?: (0 until fs.numFiles()).filter { Regex(".*\\.($VIDEO)$", RegexOption.IGNORE_CASE).matches(fs.filePath(it)) }.maxByOrNull { fs.fileSize(it) }
                ?: (0 until fs.numFiles()).maxByOrNull { fs.fileSize(it) } ?: throw IOException("El torrent no trae archivos")
            val prios = Array(fs.numFiles()) { if (it == idx) Priority.DEFAULT else Priority.IGNORE }
            // Con el candado: el corte por VPN no puede pasar justo entre la revisión y el inicio de la bajada
            val a = synchronized(lock) {
                check()
                sm.download(ti, dir, null, prios, null, TorrentFlags.SEQUENTIAL_DOWNLOAD)
                val handle = sm.find(ti.infoHash()) ?: throw IOException("No se pudo empezar el torrent")
                addSources(handle, magnet)
                Active(handle, ti, dir, File(dir, fs.filePath(idx)), fs.fileOffset(idx), fs.fileSize(idx), ti.pieceLength(),
                    fs.filePath(idx).substringAfterLast('/')).also { active = it }
            }
            // Primero el principio y el final del archivo (muchos videos guardan ahí el índice para poder adelantar)
            prioritize(a, 0, ahead = 12)
            val lastPiece = pieceOf(a, a.size - 1)
            for (p in maxOf(pieceOf(a, 0), lastPiece - 1)..lastPiece) runCatching { a.handle.setPieceDeadline(p, 500) }
            onStatus("Preparando el video…")
            val port = startServer()
            check()
            "http://127.0.0.1:$port/${java.net.URLEncoder.encode(a.name, "UTF-8").replace("+", "%20")}"
        } catch (e: Throwable) {
            // Se cortó la VPN mientras se abría: el motor se apaga aquí (no se pudo mientras buscaba personas)
            if (gen != myGen && vpnLost) shutdown() else if (gen == myGen) stop()
            throw e
        } finally { opening = false }
    }

    /**
     * Los datos del torrent no traen los rastreadores ni las personas que venían en el enlace: sin esto sólo se
     * encontrarían personas por la red DHT (más lento, o nada en torrents con pocas personas).
     */
    private fun addSources(handle: TorrentHandle, magnet: String) {
        val params = magnet.substringAfter('?').split('&').mapNotNull { kv ->
            val k = kv.substringBefore('='); val v = runCatching { java.net.URLDecoder.decode(kv.substringAfter('=', ""), "UTF-8") }.getOrNull()
            if (v.isNullOrBlank()) null else k to v
        }
        params.filter { it.first == "tr" }.forEach { runCatching { handle.addTracker(org.libtorrent4j.AnnounceEntry(it.second)) } }
        params.filter { it.first == "x.pe" }.forEach { (_, pe) ->
            runCatching {
                val host = pe.substringBeforeLast(':').removePrefix("[").removeSuffix("]")
                val port = pe.substringAfterLast(':').toInt()
                val ec = org.libtorrent4j.swig.error_code()
                handle.swig().connect_peer(org.libtorrent4j.swig.tcp_endpoint(org.libtorrent4j.swig.address.from_string(host, ec), port))
            }
        }
    }

    /**
     * ¿El internet de esta app va por una VPN? Se pregunta por la red que usa esta app (no la del equipo): si la VPN
     * deja fuera a Vibe IPTV (túnel dividido), cuenta como sin VPN.
     */
    fun vpnOn(ctx: Context): Boolean = runCatching {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
    }.getOrDefault(false)

    /**
     * Corte de seguridad: si la VPN se cae, se apaga el motor completo al tiro (no sólo este torrent: también la red de
     * pares y la conexión que recibe pedidos), antes de compartir sin ella. Vigila hasta que se cierra este torrent.
     */
    private fun watchVpn(ctx: Context, myGen: Int, onLost: () -> Unit) {
        Thread({
            while (gen == myGen) {
                if (!vpnOn(ctx)) {
                    if (gen == myGen) {
                        vpnLost = true
                        // Primero se frena todo (rápido y seguro aunque esté buscando personas)…
                        runCatching { session?.pause() }; runCatching { session?.stopDht() }
                        stop()
                        // …y el motor se apaga del todo; si se estaba abriendo, lo apaga open() al volver de la búsqueda
                        if (!opening) shutdown()
                        onLost()
                    }
                    break
                }
                Thread.sleep(1000)
            }
        }, "vibe-torrent-vpn").apply { isDaemon = true }.start()
    }

    /** Borra lo que haya quedado de una vez anterior (si la app se cerró de golpe mientras se veía un torrent). */
    fun cleanLeftovers(ctx: Context) {
        if (active == null) runCatching { File(ctx.cacheDir, "torrent").deleteRecursively() }
    }

    /** Estado para el diagnóstico: personas conectadas y velocidades. */
    fun status(): String? {
        val a = active ?: return null
        val st = runCatching { a.handle.status() }.getOrNull() ?: return null
        return "Torrent: ${st.numPeers()} personas · ⬇ %.1f Mb/s · ⬆ %.0f KB/s · %.0f %%".format(
            st.downloadRate() * 8 / 1e6, st.uploadRate() / 1024.0, st.progress() * 100)
    }

    /** Deja de compartir, borra lo bajado y cierra el servidor local. */
    fun stop() {
        gen++
        val a = active
        active = null
        runCatching { server?.close() }
        server = null
        if (a != null) {
            runCatching { session?.remove(a.handle, org.libtorrent4j.swig.session_handle.delete_files) }
            runCatching { a.dir.deleteRecursively() }
        }
    }

    /** Al cerrar la app: también se apaga el motor. */
    fun shutdown() {
        stop()
        synchronized(lock) { runCatching { session?.stop() }; session = null }
    }

    // ---------- Piezas ----------
    private fun pieceOf(a: Active, pos: Long) = ((a.offset + pos) / a.pieceLen).toInt()

    /** Lo que viene desde [pos] se pide primero (con plazos cortos y crecientes). */
    private fun prioritize(a: Active, pos: Long, ahead: Int = 24) {
        val first = pieceOf(a, pos)
        val last = pieceOf(a, a.size - 1)
        runCatching { a.handle.clearPieceDeadlines() }
        for ((i, p) in (first..minOf(last, first + ahead)).withIndex()) runCatching { a.handle.setPieceDeadline(p, 300 + i * 150) }
    }

    /** Espera a que la pieza esté bajada (hasta [timeoutMs]); false si no llegó o el torrent se cerró. */
    private fun waitPiece(a: Active, p: Int, timeoutMs: Long = 90_000): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (active !== a) return false
            if (runCatching { a.handle.havePiece(p) }.getOrDefault(false)) return true
            Thread.sleep(80)
        }
        return false
    }

    // ---------- Servidor local (sólo 127.0.0.1) ----------
    private fun startServer(): Int {
        val s = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        server = s
        Thread({
            while (!s.isClosed) {
                val c = runCatching { s.accept() }.getOrNull() ?: break
                Thread({ serve(c) }, "vibe-torrent-pedido").apply { isDaemon = true }.start()
            }
        }, "vibe-torrent-servidor").apply { isDaemon = true }.start()
        return s.localPort
    }

    private fun serve(c: Socket) = c.use { sock ->
        val a = active ?: return@use
        val input = sock.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val first = input.readLine() ?: return@use
        var range: String? = null
        while (true) {
            val h = input.readLine() ?: break
            if (h.isEmpty()) break
            if (h.startsWith("Range:", true)) range = h.substringAfter(':').trim()
        }
        val out = sock.getOutputStream()
        val type = when (a.name.substringAfterLast('.').lowercase()) {
            "mp4", "m4v" -> "video/mp4"; "mkv" -> "video/x-matroska"; "webm" -> "video/webm"; "avi" -> "video/x-msvideo"
            "ts" -> "video/mp2t"; "mov" -> "video/quicktime"; else -> "application/octet-stream"
        }
        val m = range?.let { Regex("bytes=(\\d*)-(\\d*)").find(it) }
        val start = m?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val end = (m?.groupValues?.get(2)?.toLongOrNull() ?: (a.size - 1)).coerceAtMost(a.size - 1)
        if (start >= a.size || start > end) {
            out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */${a.size}\r\nConnection: close\r\n\r\n".toByteArray()); return@use
        }
        val head = if (m != null) "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $start-$end/${a.size}\r\n" else "HTTP/1.1 200 OK\r\n"
        out.write((head + "Content-Type: $type\r\nAccept-Ranges: bytes\r\nContent-Length: ${end - start + 1}\r\nConnection: close\r\n\r\n").toByteArray())
        if (first.startsWith("HEAD")) { out.flush(); return@use }
        // Se adelantó (o empieza): lo que viene desde aquí va primero
        prioritize(a, start)
        var pos = start
        var raf: RandomAccessFile? = null
        val buf = ByteArray(64 * 1024)
        try {
            while (pos <= end) {
                val p = pieceOf(a, pos)
                if (!waitPiece(a, p)) break
                // Pedir las que siguen mientras se entrega ésta
                if ((pos - start) % (a.pieceLen.toLong() * 4) < buf.size) prioritize(a, pos)
                val pieceEnd = (p.toLong() + 1) * a.pieceLen - a.offset - 1       // último byte del archivo en esta pieza
                val chunkEnd = minOf(end, pieceEnd)
                val f = raf ?: RandomAccessFile(a.file, "r").also { raf = it }
                f.seek(pos)
                var left = chunkEnd - pos + 1
                while (left > 0) {
                    val n = f.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n <= 0) break
                    out.write(buf, 0, n); pos += n; left -= n
                }
                if (left > 0) break
            }
            out.flush()
        } catch (_: IOException) {
            // El reproductor cortó (adelantó o cerró): normal
        } finally { runCatching { raf?.close() } }
    }
}
