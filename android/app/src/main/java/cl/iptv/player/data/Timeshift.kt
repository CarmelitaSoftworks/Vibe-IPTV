package cl.iptv.player.data

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile

/**
 * Pausar y retroceder la TV en vivo. Mientras se ve un canal (.ts), una sola conexión lo descarga a trozos en el
 * equipo y el reproductor lee desde ahí: pausar no corta nada (la descarga sigue), retroceder es leer un poco más atrás
 * y "volver al directo" es leer lo último que llegó. Se guardan los últimos minutos (Ajustes → Reproducción) y los
 * trozos más viejos se borran solos.
 *
 * Esta clase guarda los bytes y recuerda a qué hora llegó cada parte. Lógica sin red para poder probarla.
 */
class TimeshiftBuffer(
    private val dir: File,
    /** Cuánto tiempo guardar hacia atrás (ms). */
    private val keepMs: Long,
    /** Tope de espacio en el equipo (bytes): se respeta aunque se esté en pausa. */
    private val maxBytes: Long,
    private val chunk: Long = CHUNK,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Total escrito desde el inicio (posición "global": no vuelve a cero al borrar trozos). */
    @Volatile var written = 0L; private set
    /** Lo más antiguo que todavía está guardado. */
    @Volatile var oldest = 0L; private set
    /** El reproductor va leyendo aquí: no se borra lo que todavía no ve (salvo que se pase del tope de espacio). */
    @Volatile var readerAt = 0L
    @Volatile var failure: IOException? = null
    @Volatile var closed = false; private set

    /** (hora de llegada, posición): uno cada medio segundo, para pasar de "10 s atrás" a una posición del archivo. */
    private val index = ArrayList<LongArray>()
    private var file: RandomAccessFile? = null
    private var fileNo = -1L

    init { dir.mkdirs() }

    private fun name(no: Long) = File(dir, "$no.ts")

    @Synchronized fun append(b: ByteArray, off: Int, len: Int) {
        if (closed) return
        var o = off; var left = len
        while (left > 0) {
            val no = written / chunk
            if (no != fileNo) { file?.close(); file = RandomAccessFile(name(no), "rw"); fileNo = no }
            val n = minOf(left.toLong(), chunk - written % chunk).toInt()
            file!!.write(b, o, n)
            o += n; left -= n; written += n
        }
        val now = clock()
        if (index.isEmpty() || now - index.last()[0] >= 500) index.add(longArrayOf(now, written))
        trim(now)
    }

    /** Borra los trozos viejos: los que pasaron la ventana de tiempo (si ya se vieron) o los que pasan el tope. */
    private fun trim(now: Long) {
        while (true) {
            val firstNo = oldest / chunk
            if (firstNo >= written / chunk) return                       // el trozo que se está escribiendo, nunca
            val firstEnd = (firstNo + 1) * chunk
            val tooOld = timeAtLocked(firstEnd) < now - keepMs && readerAt >= firstEnd
            val tooBig = written - oldest > maxBytes
            if (!tooOld && !tooBig) return
            name(firstNo).delete()
            oldest = firstEnd
            while (index.size > 1 && index[1][1] <= oldest) index.removeAt(0)
        }
    }

    /**
     * Lee lo que haya desde [pos] (sin esperar). Devuelve 0 si todavía no llegó nada nuevo, y -1 si [pos] ya se
     * borró (lo más viejo guardado empieza en [oldest]).
     */
    fun read(pos: Long, b: ByteArray, off: Int, len: Int): Int {
        if (pos < oldest) return -1
        val avail = written - pos
        if (avail <= 0) return 0
        val no = pos / chunk
        val n = minOf(len.toLong(), avail, chunk - pos % chunk).toInt()
        return runCatching {
            RandomAccessFile(name(no), "r").use { f -> f.seek(pos % chunk); f.read(b, off, n) }
        }.getOrElse { if (pos < oldest) -1 else throw IOException(it) }.coerceAtLeast(0)
    }

    /** Hora (aproximada) en que llegó la posición [pos]. */
    @Synchronized fun timeAt(pos: Long): Long = timeAtLocked(pos)

    private fun timeAtLocked(pos: Long): Long {
        if (index.isEmpty()) return clock()
        var lo = 0; var hi = index.size - 1
        if (pos >= index[hi][1]) return index[hi][0]
        while (lo < hi) { val m = (lo + hi) / 2; if (index[m][1] < pos) lo = m + 1 else hi = m }
        return index[lo][0]
    }

    /** Posición del archivo que llegó a la hora [at] (o lo más cercano guardado), alineada a un paquete de TV (188 bytes). */
    @Synchronized fun offsetAt(at: Long): Long {
        if (index.isEmpty()) return oldest
        var pos = index.last { it[0] <= at || it === index.first() }[1]
        if (index.first()[0] > at) pos = oldest
        return aligned(maxOf(pos, oldest))
    }

    /** Al inicio de un paquete de TV (contando desde el principio de la transmisión), sin pasar a lo ya borrado. */
    fun aligned(pos: Long): Long {
        val p = pos - pos % TS_PACKET
        return if (p >= oldest) p else p + TS_PACKET * ((oldest - p + TS_PACKET - 1) / TS_PACKET)
    }

    /** Hora de lo último que llegó. */
    val latestAt: Long @Synchronized get() = if (index.isEmpty()) clock() else index.last()[0]
    /** Hora de lo más viejo que se puede ver. */
    val earliestAt: Long get() = timeAt(oldest)

    @Synchronized fun close() {
        closed = true
        runCatching { file?.close() }
        file = null
        dir.deleteRecursively()
    }

    companion object {
        const val CHUNK = 2L * 1024 * 1024
        const val TS_PACKET = 188L
    }
}

/** Descarga el canal al [TimeshiftBuffer] y reconecta sola si se corta (sin perder lo ya guardado). */
class TimeshiftSession(val buffer: TimeshiftBuffer, private val item: Item) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    /** Veces que se reconectó la descarga (para el diagnóstico). */
    @Volatile var reconnects = 0; private set

    fun start() { job = scope.launch { download() } }

    /** Cortar y volver a conectar (cuando la imagen se detuvo: puede que el servidor dejó de mandar). */
    fun reconnect() { job?.cancel(); job = scope.launch { download() } }

    private suspend fun download() {
        var retries = 0
        val buf = ByteArray(64 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                val req = Request.Builder().url(item.url)
                    .header("User-Agent", item.userAgent.ifBlank { Http.DEFAULT_UA })
                    .apply { if (item.referrer.isNotBlank()) header("Referer", item.referrer) }.build()
                Http.streamClient.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                    val input = r.body!!.byteStream()
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf); if (n < 0) break
                        if (n > 0) { buffer.append(buf, 0, n); retries = 0 }
                    }
                }
            } catch (e: IOException) {
                // Si nunca llegó nada, el canal no sirve así: el reproductor lo sabrá y probará otra cosa
                if (buffer.written == 0L && retries >= 2) { buffer.failure = e; return }
                if (retries >= 30) { buffer.failure = e; return }
            }
            retries++; reconnects++
            delay(LiveCushion.retryDelayMs(retries))
        }
    }

    fun close() { scope.cancel(); buffer.close() }
}

/**
 * Lo que ExoPlayer lee: el archivo que va creciendo, desde la posición pedida (timeshift://canal?from=N).
 * Espera a que llegue más (nunca "termina" mientras la descarga siga).
 */
@OptIn(UnstableApi::class)
class TimeshiftDataSource(private val buffer: TimeshiftBuffer) : BaseDataSource(false) {
    private var uri: Uri? = null
    private var pos = 0L

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val from = dataSpec.uri.getQueryParameter("from")?.toLongOrNull() ?: 0L
        pos = maxOf(from + dataSpec.position, buffer.oldest)
        buffer.readerAt = pos
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        while (true) {
            val n = buffer.read(pos, b, off, len)
            if (n > 0) { pos += n; buffer.readerAt = pos; bytesTransferred(n); return n }
            // Se pasó del tope de espacio en una pausa muy larga: seguir desde lo más antiguo que queda
            if (n < 0) { pos = buffer.aligned(buffer.oldest); continue }
            buffer.failure?.let { throw it }
            if (buffer.closed) return C.RESULT_END_OF_INPUT
            try { Thread.sleep(25) } catch (e: InterruptedException) { throw InterruptedIOException() }
        }
    }

    override fun getUri(): Uri? = uri
    override fun close() { if (uri != null) { uri = null; transferEnded() } }
}
