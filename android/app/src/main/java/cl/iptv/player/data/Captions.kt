package cl.iptv.player.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Modelos de voz de Vosk (código abierto, Apache 2.0, https://alphacephei.com/vosk): reconocen el habla
 * sin internet, en el propio equipo. La versión "IA" de la app trae el de español incluido;
 * la normal lo descarga la primera vez (unos 40 MB).
 */
object VoskModels {
    private const val BASE = "https://alphacephei.com/vosk/models/"
    val files = mapOf(
        "es" to "vosk-model-small-es-0.42", "en" to "vosk-model-small-en-us-0.15", "pt" to "vosk-model-small-pt-0.3",
        "fr" to "vosk-model-small-fr-0.22", "it" to "vosk-model-small-it-0.22", "de" to "vosk-model-small-de-0.15",
    )
    val names = mapOf("es" to "Español", "en" to "Inglés", "pt" to "Portugués", "fr" to "Francés", "it" to "Italiano", "de" to "Alemán")

    private fun dir(ctx: Context, lang: String) = File(ctx.filesDir, "vosk/$lang")
    fun bundled(ctx: Context, lang: String) = runCatching { ctx.assets.list("vosk/$lang")?.isNotEmpty() == true }.getOrDefault(false)
    fun ready(ctx: Context, lang: String) = File(dir(ctx, lang), ".listo").exists()

    /** Deja el modelo listo en el almacenamiento interno (copiándolo de la app o descargándolo). */
    suspend fun prepare(ctx: Context, lang: String, progress: (String) -> Unit): File = withContext(Dispatchers.IO) {
        val d = dir(ctx, lang)
        if (ready(ctx, lang)) return@withContext d
        d.deleteRecursively(); d.mkdirs()
        if (bundled(ctx, lang)) {
            progress("Preparando el modelo de voz incluido…")
            copyAssets(ctx, "vosk/$lang", d)
        } else {
            val name = files[lang] ?: throw IOException("No hay modelo de voz para ese idioma")
            val req = Request.Builder().url("$BASE$name.zip").header("User-Agent", "VibeIPTV").build()
            Http.client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw IOException("No se pudo descargar el modelo (HTTP ${r.code})")
                val body = r.body ?: throw IOException("Descarga vacía")
                val total = body.contentLength()
                var read = 0L; var lastPct = -1
                val counting = object : java.io.FilterInputStream(body.byteStream()) {
                    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { n ->
                        if (n > 0) { read += n; val pct = if (total > 0) (read * 100 / total).toInt() else -1
                            if (pct != lastPct && pct % 5 == 0) { lastPct = pct; progress("Descargando modelo de voz… $pct %") } }
                    }
                }
                ZipInputStream(counting).use { z ->
                    while (true) {
                        val e = z.nextEntry ?: break
                        val rel = e.name.substringAfter('/', "")          // sin la carpeta raíz del zip
                        if (rel.isBlank() || rel.contains("..")) continue
                        val f = File(d, rel)
                        if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { z.copyTo(it) } }
                    }
                }
            }
        }
        File(d, ".listo").writeText("1")
        d
    }

    private fun copyAssets(ctx: Context, path: String, dest: File) {
        val children = ctx.assets.list(path).orEmpty()
        if (children.isEmpty()) { dest.parentFile?.mkdirs(); ctx.assets.open(path).use { i -> dest.outputStream().use { i.copyTo(it) } }; return }
        dest.mkdirs()
        children.forEach { copyAssets(ctx, "$path/$it", File(dest, it)) }
    }
}

/**
 * Subtítulos generados en vivo: toma el audio que ya decodificó ExoPlayer (sin grabar nada ni usar el micrófono),
 * lo pasa a 16 kHz mono y lo reconoce con Vosk en un hilo aparte. Todo queda en el equipo.
 *
 * Para que vaya a la par del video aunque el equipo sea lento: el audio se entrega a Vosk en trozos de 0,2 s,
 * el texto parcial se pide como mucho 4 veces por segundo y, si el reconocimiento se atrasa más de 3 s,
 * se descarta lo más antiguo y se reinicia (antes se acumulaba y los subtítulos quedaban muy atrasados o sin sentido).
 */
@androidx.annotation.OptIn(UnstableApi::class)
class LiveCaptioner {
    @Volatile var enabled = false; private set
    var lines by mutableStateOf<List<String>>(emptyList()); private set
    var partial by mutableStateOf(""); private set
    var lastAt by mutableStateOf(0L); private set

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val queue = LinkedBlockingQueue<ShortArray>()
    private val queued = java.util.concurrent.atomic.AtomicInteger(0)      // muestras en espera (16 kHz)
    /** Cada inicio tiene su número: un hilo de un inicio anterior se detiene solo (antes podían quedar dos a la vez). */
    @Volatile private var generation = 0
    @Volatile private var resetPending = false
    @Volatile private var rate = 0
    @Volatile private var channels = 0
    @Volatile private var encoding = C.ENCODING_INVALID
    private var frac = 0.0
    private val chunk = ShortArray(CHUNK)
    private var filled = 0

    /** Muestras de 16 kHz en espera (para pruebas). */
    internal val pendingSamples get() = queued.get()
    internal fun armForTest() { enabled = true }
    internal fun drainForTest(): List<ShortArray> = mutableListOf<ShortArray>().also { queue.drainTo(it); queued.set(0) }

    /** Se engancha al audio de ExoPlayer (TeeAudioProcessor): recibe el PCM justo antes de que suene. */
    val sink = object : TeeAudioProcessor.AudioBufferSink {
        override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
            rate = sampleRateHz; channels = channelCount; this@LiveCaptioner.encoding = encoding; frac = 0.0
            // Cambio de canal, salto o nuevo formato: lo pendiente ya no corresponde
            filled = 0; queue.clear(); queued.set(0); resetPending = true
        }
        override fun handleBuffer(buffer: ByteBuffer) {
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
            // 5.1 / 7.1: los diálogos van en el canal central; mezclarlo con los demás (música, efectos) confunde a Vosk
            fun mono(i: Int): Int = if (ch >= 6) sample(i, 2) else { var s = 0; for (c in 0 until ch) s += sample(i, c); s / ch }
            val step = rate / 16_000.0
            var pos = frac
            while (pos < frames) {
                // Promedio de las muestras que caen en este instante (filtro simple contra el aliasing)
                val from = pos.toInt(); val to = minOf(frames, maxOf(from + 1, (pos + step).toInt()))
                var sum = 0L
                for (i in from until to) sum += mono(i)
                chunk[filled++] = (sum / (to - from)).toInt().coerceIn(-32768, 32767).toShort()
                if (filled == CHUNK) { push(chunk.copyOf()); filled = 0 }
                pos += step
            }
            frac = pos - frames
        }
    }

    private fun push(a: ShortArray) {
        queue.offer(a)
        // Atraso de más de 3 s: se descarta lo más antiguo y se reinicia el reconocimiento para volver a la par
        if (queued.addAndGet(a.size) > MAX_BACKLOG) {
            while (queued.get() > KEEP_BACKLOG) { val old = queue.poll() ?: break; queued.addAndGet(-old.size) }
            resetPending = true
        }
    }

    fun start(modelDir: File, onError: (String) -> Unit) {
        stop()
        val gen = ++generation
        enabled = true
        Thread({
            try {
                org.vosk.LibVosk.setLogLevel(org.vosk.LogLevel.WARNINGS)
                val model = cachedModel(modelDir)
                org.vosk.Recognizer(model, 16_000f).use { rec ->
                    var partialAt = 0L
                    var shown = ""
                    while (enabled && gen == generation) {
                        val a = queue.poll(400, TimeUnit.MILLISECONDS) ?: continue
                        queued.addAndGet(-a.size)
                        if (resetPending) { resetPending = false; rec.reset(); shown = ""; post(gen) { partial = "" } }
                        if (rec.acceptWaveForm(a, a.size)) {
                            val t = clean(JSONObject(rec.result).optString("text"))
                            shown = ""
                            post(gen) {
                                if (t.isNotEmpty()) { lines = (lines + t.replaceFirstChar { it.uppercase() }).takeLast(2); lastAt = System.currentTimeMillis() }
                                partial = ""
                            }
                        } else {
                            val now = android.os.SystemClock.uptimeMillis()
                            if (now - partialAt >= 250) {
                                partialAt = now
                                val p = clean(JSONObject(rec.partialResult).optString("partial"))
                                if (p != shown) { shown = p; post(gen) { partial = p; if (p.isNotEmpty()) lastAt = System.currentTimeMillis() } }
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                post(gen) { enabled = false; onError(e.message ?: e.toString()) }
            }
        }, "vibe-subtitulos-ia").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1; start() }
    }

    /** Los estados de Compose se cambian en el hilo principal, y sólo si ese inicio sigue vigente. */
    private fun post(gen: Int, block: () -> Unit) { main.post { if (gen == generation) block() } }

    fun stop() {
        enabled = false
        generation++
        queue.clear(); queued.set(0); filled = 0
        lines = emptyList(); partial = ""
    }

    companion object {
        private const val CHUNK = 3_200                 // 0,2 s a 16 kHz
        private const val MAX_BACKLOG = 16_000 * 3      // 3 s de atraso como máximo…
        private const val KEEP_BACKLOG = 16_000 / 2     // …y se vuelve a 0,5 s

        /** Ruido que el modelo pequeño "oye" como palabras sueltas: se descarta. */
        internal fun clean(raw: String): String {
            val t = raw.replace("[unk]", "").replace(Regex("\\s+"), " ").trim()
            return if (!t.contains(' ') && t.length <= 2) "" else t
        }

        private var model: org.vosk.Model? = null
        private var modelPath = ""
        @Synchronized private fun cachedModel(dir: File): org.vosk.Model {
            if (model == null || modelPath != dir.absolutePath) { model?.close(); model = org.vosk.Model(dir.absolutePath); modelPath = dir.absolutePath }
            return model!!
        }
    }
}
