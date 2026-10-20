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
 */
@androidx.annotation.OptIn(UnstableApi::class)
class LiveCaptioner {
    @Volatile var enabled = false; private set
    var lines by mutableStateOf<List<String>>(emptyList()); private set
    var partial by mutableStateOf(""); private set
    var lastAt by mutableStateOf(0L); private set

    private val queue = LinkedBlockingQueue<ShortArray>(600)
    private var worker: Thread? = null
    @Volatile private var rate = 0
    @Volatile private var channels = 0
    @Volatile private var encoding = C.ENCODING_INVALID
    private var frac = 0.0

    /** Se engancha al audio de ExoPlayer (TeeAudioProcessor): recibe el PCM justo antes de que suene. */
    val sink = object : TeeAudioProcessor.AudioBufferSink {
        override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
            rate = sampleRateHz; channels = channelCount; this@LiveCaptioner.encoding = encoding; frac = 0.0
        }
        override fun handleBuffer(buffer: ByteBuffer) {
            if (!enabled || encoding != C.ENCODING_PCM_16BIT || rate <= 0 || channels <= 0) return
            val b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            val base = b.position()
            val frames = b.remaining() / (2 * channels)
            if (frames <= 0) return
            val step = rate / 16_000.0
            val out = ShortArray((frames / step).toInt() + 2)
            var n = 0
            var pos = frac
            while (pos < frames && n < out.size) {
                // Promedio de las muestras que caen en este instante (filtro simple contra el aliasing) y mezcla a mono
                val from = pos.toInt(); val to = minOf(frames, maxOf(from + 1, (pos + step).toInt()))
                var sum = 0L; var cnt = 0
                for (i in from until to) for (ch in 0 until channels) { sum += b.getShort(base + (i * channels + ch) * 2); cnt++ }
                out[n++] = (sum / maxOf(1, cnt)).toInt().toShort()
                pos += step
            }
            frac = pos - frames
            queue.offer(out.copyOf(n))
        }
    }

    fun start(modelDir: File, onError: (String) -> Unit) {
        stop()
        enabled = true
        worker = Thread({
            try {
                org.vosk.LibVosk.setLogLevel(org.vosk.LogLevel.WARNINGS)
                val model = cachedModel(modelDir)
                org.vosk.Recognizer(model, 16_000f).use { rec ->
                    while (enabled) {
                        val a = queue.poll(400, TimeUnit.MILLISECONDS) ?: continue
                        if (rec.acceptWaveForm(a, a.size)) {
                            val t = JSONObject(rec.result).optString("text").trim()
                            if (t.isNotEmpty()) { lines = (lines + t.replaceFirstChar { it.uppercase() }).takeLast(2); lastAt = System.currentTimeMillis() }
                            partial = ""
                        } else {
                            val p = JSONObject(rec.partialResult).optString("partial").trim()
                            if (p != partial) { partial = p; if (p.isNotEmpty()) lastAt = System.currentTimeMillis() }
                        }
                    }
                }
            } catch (e: Throwable) {
                enabled = false
                onError(e.message ?: e.toString())
            }
        }, "vibe-subtitulos-ia").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1; start() }
    }

    fun stop() {
        enabled = false
        worker = null
        queue.clear()
        lines = emptyList(); partial = ""
    }

    companion object {
        private var model: org.vosk.Model? = null
        private var modelPath = ""
        @Synchronized private fun cachedModel(dir: File): org.vosk.Model {
            if (model == null || modelPath != dir.absolutePath) { model?.close(); model = org.vosk.Model(dir.absolutePath); modelPath = dir.absolutePath }
            return model!!
        }
    }
}
