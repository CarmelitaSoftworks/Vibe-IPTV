package cl.iptv.player.data

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

/*
 * Subtítulos con Whisper (versión de prueba, teléfonos de gama media-alta).
 * whisper.cpp (MIT) con el modelo "base" cuantizado (~57 MB, se descarga la primera vez). Todo en el equipo.
 *
 * Para que los subtítulos no lleguen atrasados, el audio se reconoce ANTES de que suene: al activar Whisper
 * el reproductor junta unos segundos de audio decodificado antes de empezar (como un búfer inicial más largo).
 * Mientras tanto Whisper ya va transcribiendo, y cada frase se muestra cuando su audio realmente suena.
 */

/** Funciones de la librería nativa (libvibewhisper.so). */
object WhisperNative {
    @JvmStatic external fun supported(): Boolean
    @JvmStatic external fun init(path: String): Long
    @JvmStatic external fun free(ctx: Long)
    @JvmStatic external fun transcribe(ctx: Long, samples: FloatArray, lang: String, threads: Int, audioCtx: Int): Array<String>?
}

object WhisperModel {
    const val FILE = "ggml-base-q5_1.bin"
    private const val URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$FILE"
    const val SIZE_MB = 57

    /** Procesador de 64 bits ARMv8.2 con fp16 y dotprod (para eso está compilada la librería) y 4 GB de RAM o más. */
    fun deviceOk(ctx: Context): Boolean {
        if ("arm64-v8a" !in Build.SUPPORTED_64_BIT_ABIS) return false
        val cpu = runCatching { File("/proc/cpuinfo").readText() }.getOrDefault("")
        if (!cpu.contains("asimddp") || !(cpu.contains("asimdhp") || cpu.contains("fphp"))) return false
        val mi = ActivityManager.MemoryInfo()
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        return mi.totalMem >= 3_500L * 1024 * 1024
    }

    /** Por qué no está disponible (o null si lo está). */
    fun unavailableReason(ctx: Context): String? = when {
        "arm64-v8a" !in Build.SUPPORTED_64_BIT_ABIS -> "Requiere un teléfono de 64 bits"
        !deviceOk(ctx) -> "Este equipo no tiene el procesador o la memoria que necesita"
        !libraryLoaded() -> "Esta versión de la app no trae Whisper"
        else -> null
    }

    @Volatile private var loaded: Boolean? = null
    @Synchronized fun libraryLoaded(): Boolean {
        loaded?.let { return it }
        return runCatching { System.loadLibrary("vibewhisper"); WhisperNative.supported() }.getOrDefault(false).also { loaded = it }
    }

    fun file(ctx: Context) = File(ctx.filesDir, "whisper/$FILE")
    fun ready(ctx: Context) = file(ctx).let { it.exists() && it.length() > 30L * 1024 * 1024 }

    suspend fun prepare(ctx: Context, progress: (String) -> Unit): File = withContext(Dispatchers.IO) {
        val f = file(ctx)
        if (ready(ctx)) return@withContext f
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".part")
        val req = Request.Builder().url(URL).header("User-Agent", "VibeIPTV").build()
        Http.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("No se pudo descargar el modelo Whisper (HTTP ${r.code})")
            val body = r.body ?: throw IOException("Descarga vacía")
            val total = body.contentLength()
            var read = 0L; var last = -1
            body.byteStream().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); read += n
                        val pct = if (total > 0) (read * 100 / total).toInt() else -1
                        if (pct != last && pct % 5 == 0) { last = pct; progress("Descargando Whisper… $pct %") }
                    }
                }
            }
        }
        if (tmp.length() < 30L * 1024 * 1024) { tmp.delete(); throw IOException("El modelo descargado está incompleto") }
        if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
        f
    }
}

/** Una frase reconocida, con el momento en que suena (en el reloj del audio del reproductor). */
data class Cue(val startUs: Long, val endUs: Long, val text: String)

@androidx.annotation.OptIn(UnstableApi::class)
class WhisperCaptioner {
    /** Segundos de audio que se juntan antes de empezar a reproducir (lo que Whisper va adelantado). */
    val leadUs = 8_000_000L

    @Volatile var enabled = false; private set
    /** Posición actual del audio que suena (la informa [DelayAudioSink]). */
    @Volatile var positionUs = C.TIME_UNSET
    @Volatile var lastMs = 0L; private set              // cuánto tardó el último trozo
    private val cues = ArrayList<Cue>()

    // ---- conversión a 16 kHz mono ----
    @Volatile private var rate = 0
    @Volatile private var channels = 0
    @Volatile private var encoding = C.ENCODING_INVALID
    private var frac = 0.0
    private val window = ShortArray(MAX_WINDOW)
    private var filled = 0
    private var windowStartUs = C.TIME_UNSET

    private class Job(val samples: ShortArray, val startUs: Long)
    private val jobs = LinkedBlockingDeque<Job>()
    @Volatile private var generation = 0

    fun configure(sampleRate: Int, channelCount: Int, pcmEncoding: Int) {
        rate = sampleRate; channels = channelCount; encoding = pcmEncoding; flush()
    }

    fun flush() {
        frac = 0.0; filled = 0; windowStartUs = C.TIME_UNSET; jobs.clear()
        synchronized(cues) { cues.clear() }
    }

    /** Audio decodificado (PCM) con su tiempo de presentación. No cambia la posición de [buffer]. */
    fun feed(buffer: ByteBuffer, ptsUs: Long) {
        val enc = encoding
        if (!enabled || rate <= 0 || channels <= 0 || (enc != C.ENCODING_PCM_16BIT && enc != C.ENCODING_PCM_FLOAT)) return
        val bytes = if (enc == C.ENCODING_PCM_FLOAT) 4 else 2
        val ch = channels
        val b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val base = b.position()
        val frames = b.remaining() / (bytes * ch)
        if (frames <= 0) return
        fun sample(i: Int, c: Int): Int = if (bytes == 2) b.getShort(base + (i * ch + c) * 2).toInt()
            else (b.getFloat(base + (i * ch + c) * 4).coerceIn(-1f, 1f) * 32767f).toInt()
        fun mono(i: Int): Int = if (ch >= 6) sample(i, 2) else { var s = 0; for (c in 0 until ch) s += sample(i, c); s / ch }
        val step = rate / 16_000.0
        var pos = frac
        while (pos < frames) {
            if (filled >= MAX_WINDOW) filled = 0                    // por si se borró a mitad (al apagar Whisper)
            if (filled == 0) windowStartUs = ptsUs + (pos * 1_000_000.0 / rate).toLong()
            val from = pos.toInt(); val to = minOf(frames, maxOf(from + 1, (pos + step).toInt()))
            var sum = 0L
            for (i in from until to) sum += mono(i)
            window[filled++] = (sum / (to - from)).toInt().coerceIn(-32768, 32767).toShort()
            // Se corta en una pausa (desde 3 s) o, a más tardar, a los 5 s
            if (filled % BLOCK == 0 && (filled >= MAX_WINDOW || (filled >= MIN_WINDOW && blockQuiet()))) submit()
            pos += step
        }
        frac = pos - frames
    }

    private fun blockQuiet(): Boolean = LiveCaptioner.rms(window.copyOfRange(filled - BLOCK, filled)) < 350.0

    private fun submit() {
        val job = Job(window.copyOf(filled), windowStartUs)
        filled = 0
        jobs.offerLast(job)
        while (jobs.size > 3) jobs.pollFirst()        // muy atrasado: se descarta lo más antiguo
    }

    fun start(model: File, lang: String, onError: (String) -> Unit) {
        stop()
        val gen = ++generation
        enabled = true
        Thread({
            var ctx = 0L
            try {
                ctx = WhisperNative.init(model.absolutePath)
                if (ctx == 0L) throw IOException("No se pudo cargar el modelo Whisper")
                val threads = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 4)
                var gain = 1f
                while (enabled && gen == generation) {
                    val job = jobs.pollFirst(400, TimeUnit.MILLISECONDS) ?: continue
                    if (LiveCaptioner.rms(job.samples) < 250.0) continue                     // silencio
                    gain = LiveCaptioner.normalize(job.samples, gain)
                    val pcm = FloatArray(job.samples.size) { job.samples[it] / 32768f }
                    val secs = pcm.size / 16_000f
                    val audioCtx = (((secs * 50).toInt() + 64 + 63) / 64 * 64).coerceIn(256, 1500)
                    val t0 = android.os.SystemClock.uptimeMillis()
                    val segs = WhisperNative.transcribe(ctx, pcm, lang, threads, audioCtx) ?: continue
                    lastMs = android.os.SystemClock.uptimeMillis() - t0
                    if (gen != generation) break
                    val add = segs.mapNotNull { s ->
                        val p = s.split('|', limit = 3); if (p.size < 3) return@mapNotNull null
                        val text = clean(p[2]); if (text.isEmpty()) return@mapNotNull null
                        val a = job.startUs + (p[0].toLongOrNull() ?: 0) * 10_000
                        val z = job.startUs + (p[1].toLongOrNull() ?: 0) * 10_000
                        Cue(a, maxOf(z, a + 800_000), text)
                    }
                    synchronized(cues) { cues += add; while (cues.size > 30) cues.removeAt(0) }
                }
            } catch (e: Throwable) {
                if (gen == generation) { enabled = false; onError(e.message ?: e.toString()) }
            } finally {
                if (ctx != 0L) runCatching { WhisperNative.free(ctx) }
            }
        }, "vibe-whisper").apply { isDaemon = true; start() }
    }

    fun stop() {
        enabled = false; generation++
        flush()
    }

    /** Texto que corresponde a lo que suena ahora (hasta dos líneas). */
    fun textAt(posUs: Long): String {
        if (posUs == C.TIME_UNSET) return ""
        val now = synchronized(cues) { cues.filter { posUs >= it.startUs && posUs <= it.endUs + 1_200_000 } }
        return now.joinToString(" ") { it.text }.let { LiveCaptioner.wrap(it).takeLast(2).joinToString("\n") }
    }

    companion object {
        private const val BLOCK = 3_200                 // 0,2 s
        private const val MIN_WINDOW = 16_000 * 3
        private const val MAX_WINDOW = 16_000 * 5

        /** Lo que Whisper "oye" en silencios o música (frases típicas de subtítulos de internet): se descarta. */
        private val NOISE = listOf("amara.org", "subtítulos realizados", "subtitulado por", "suscríbete", "gracias por ver",
            "thanks for watching", "subtitles by", "www.", "http")
        internal fun clean(raw: String): String {
            val t = raw.replace(Regex("\\[[^\\]]*]|\\([^)]*\\)|[♪♫*]"), " ").replace(Regex("\\s+"), " ").trim()
            if (t.length < 2 || NOISE.any { t.lowercase().contains(it) }) return ""
            return t
        }
    }
}

/**
 * Salida de audio que junta [WhisperCaptioner.leadUs] de audio antes de empezar a sonar. El decodificador
 * va así varios segundos adelantado y Whisper recibe cada trozo antes de que se escuche; el video se
 * sincroniza solo con el audio, así que todo queda a la par (empieza unos segundos más tarde).
 */
@androidx.annotation.OptIn(UnstableApi::class)
class DelayAudioSink(sink: AudioSink, private val tap: WhisperCaptioner) : ForwardingAudioSink(sink) {
    private class Entry(val data: ByteBuffer, val ptsUs: Long, val count: Int, val durUs: Long)
    private val queue = ArrayDeque<Entry>()
    private var queuedUs = 0L
    private var holding = true
    private var pcm = false
    private var frameBytes = 0
    private var rate = 0

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        pcm = inputFormat.sampleMimeType == MimeTypes.AUDIO_RAW && inputFormat.pcmEncoding != Format.NO_VALUE
        if (pcm) {
            frameBytes = Util.getPcmFrameSize(inputFormat.pcmEncoding, inputFormat.channelCount)
            rate = inputFormat.sampleRate
            tap.configure(inputFormat.sampleRate, inputFormat.channelCount, inputFormat.pcmEncoding)
        }
        clear()
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (!pcm || frameBytes <= 0 || rate <= 0) return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        pump()
        if (queuedUs >= tap.leadUs + 2_000_000L) return false          // suficiente adelanto: que espere
        val copy = ByteBuffer.allocateDirect(buffer.remaining()).order(ByteOrder.nativeOrder())
        copy.put(buffer.duplicate()); copy.flip()
        tap.feed(copy, presentationTimeUs)
        val dur = (copy.remaining() / frameBytes) * 1_000_000L / rate
        queue.addLast(Entry(copy, presentationTimeUs, encodedAccessUnitCount, dur)); queuedUs += dur
        buffer.position(buffer.limit())
        pump()
        return true
    }

    private fun pump() {
        if (holding) { if (queuedUs >= tap.leadUs) holding = false else return }
        while (queue.isNotEmpty()) {
            val e = queue.first()
            if (super.handleBuffer(e.data, e.ptsUs, e.count)) { queue.removeFirst(); queuedUs -= e.durUs } else break
        }
    }

    private fun clear() { queue.clear(); queuedUs = 0; holding = true }

    override fun hasPendingData(): Boolean { pump(); return queue.isNotEmpty() || super.hasPendingData() }
    override fun isEnded(): Boolean = queue.isEmpty() && super.isEnded()
    override fun playToEndOfStream() { holding = false; pump(); if (queue.isEmpty()) super.playToEndOfStream() }
    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        pump()
        return super.getCurrentPositionUs(sourceEnded).also { if (it != AudioSink.CURRENT_POSITION_NOT_SET) tap.positionUs = it }
    }
    override fun flush() { clear(); tap.flush(); super.flush() }
    override fun reset() { clear(); super.reset() }
}
