package cl.iptv.player.data

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Sonido del reproductor: ecualizador simple y "volumen parejo".
 * - Volumen parejo: sube lo que está bajo y baja lo que está fuerte (de a poco, para que no se note), así no hay que
 *   tocar el volumen al cambiar de canal o cuando empiezan los comerciales.
 * - Ecualizador: Voces claras (diálogos), Más graves y Noche (graves suaves y todo a un volumen parejo, para no
 *   despertar a nadie).
 */
object Sound {
    const val NORMAL = "normal"
    const val VOICES = "voces"
    const val BASS = "graves"
    const val NIGHT = "noche"
    val presets = listOf(NORMAL to "Normal", VOICES to "Voces claras", BASS to "Más graves", NIGHT to "Noche")

    /** Una banda del ecualizador: tipo, frecuencia (Hz), ganancia (dB) y ancho (Q). */
    data class Band(val type: Char, val freq: Double, val db: Double, val q: Double = 0.8)

    /** 'L' = graves (shelf), 'P' = un punto (peaking), 'H' = agudos (shelf). */
    fun bands(preset: String): List<Band> = when (preset) {
        VOICES -> listOf(Band('L', 200.0, -4.0), Band('P', 2_500.0, 5.0, 1.0), Band('P', 5_000.0, 2.0, 1.2))
        BASS -> listOf(Band('L', 110.0, 6.0), Band('H', 9_000.0, 1.0))
        NIGHT -> listOf(Band('L', 150.0, -6.0), Band('P', 2_500.0, 3.0, 1.0))
        else -> emptyList()
    }

    /** Ganancia aproximada (dB) del preset en una frecuencia: para los ecualizadores de VLC y del sistema. */
    fun gainAt(preset: String, freq: Double): Double = bands(preset).sumOf { b ->
        val r = freq / b.freq
        when (b.type) {
            'L' -> b.db / (1 + r.pow(2))                          // pleno bajo la frecuencia, se apaga arriba
            'H' -> b.db / (1 + (1 / r).pow(2))
            else -> b.db / (1 + (kotlin.math.ln(r) / (0.5 / b.q)).pow(2))
        }
    }

    /** Cadena de filtros de MPV (FFmpeg) para el mismo resultado. "" = sin filtros. */
    fun mpvFilters(preset: String, level: Boolean): String {
        val f = mutableListOf<String>()
        bands(preset).forEach { b ->
            f += when (b.type) {
                'L' -> "lavfi=[bass=g=${b.db}:f=${b.freq.toInt()}]"
                'H' -> "lavfi=[treble=g=${b.db}:f=${b.freq.toInt()}]"
                else -> "lavfi=[equalizer=f=${b.freq.toInt()}:t=q:w=${b.q}:g=${b.db}]"
            }
        }
        if (preset == NIGHT) f += "lavfi=[acompressor=threshold=-24dB:ratio=4:makeup=6]"
        else if (level) f += "lavfi=[dynaudnorm=f=250:g=15:m=6]"
        return f.joinToString(",")
    }
}

/** Filtro de segundo orden (fórmulas clásicas de ecualizador de audio). */
internal class Biquad(private val b0: Double, private val b1: Double, private val b2: Double, private val a1: Double, private val a2: Double) {
    private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0
    fun step(x: Double): Double {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x; y2 = y1; y1 = y
        return y
    }

    companion object {
        fun of(b: Sound.Band, rate: Int): Biquad {
            val a = 10.0.pow(b.db / 40)
            val w = 2 * PI * b.freq / rate
            val cw = cos(w); val sw = sin(w)
            val alpha = sw / (2 * b.q)
            val (n, d) = when (b.type) {
                'L' -> {
                    val s = 2 * sqrt(a) * alpha
                    doubleArrayOf(a * ((a + 1) - (a - 1) * cw + s), 2 * a * ((a - 1) - (a + 1) * cw), a * ((a + 1) - (a - 1) * cw - s)) to
                        doubleArrayOf((a + 1) + (a - 1) * cw + s, -2 * ((a - 1) + (a + 1) * cw), (a + 1) + (a - 1) * cw - s)
                }
                'H' -> {
                    val s = 2 * sqrt(a) * alpha
                    doubleArrayOf(a * ((a + 1) + (a - 1) * cw + s), -2 * a * ((a - 1) + (a + 1) * cw), a * ((a + 1) + (a - 1) * cw - s)) to
                        doubleArrayOf((a + 1) - (a - 1) * cw + s, 2 * ((a - 1) - (a + 1) * cw), (a + 1) - (a - 1) * cw - s)
                }
                else -> doubleArrayOf(1 + alpha * a, -2 * cw, 1 - alpha * a) to doubleArrayOf(1 + alpha / a, -2 * cw, 1 - alpha / a)
            }
            return Biquad(n[0] / d[0], n[1] / d[0], n[2] / d[0], d[1] / d[0], d[2] / d[0])
        }
    }
}

/**
 * El procesamiento en sí, sobre muestras entre -1 y 1 intercaladas por canal. Lógica pura (sin Android) para probarla.
 */
class SoundDsp(private val rate: Int, private val channels: Int) {
    @Volatile var preset: String = Sound.NORMAL
        set(v) { if (v != field) { field = v; rebuild = true } }
    @Volatile var level: Boolean = false

    private var rebuild = true
    private var filters: List<Array<Biquad>> = emptyList()       // por banda, un filtro por canal
    private var builtFor = ""
    /** Volumen medido (potencia promedio) y ganancia aplicada ahora. */
    private var power = 0.11 * 0.11
    /** Medición rápida (50 ms): para saber al tiro si se quedó en silencio. */
    private var fastPower = 0.11 * 0.11
    var gain = 1.0; private set

    val active get() = preset != Sound.NORMAL || level || gain != 1.0

    fun process(s: FloatArray, n: Int = s.size) {
        if (rebuild) {
            rebuild = false
            if (builtFor != preset) {
                builtFor = preset
                filters = Sound.bands(preset).map { b -> Array(channels) { Biquad.of(b, rate) } }
            }
        }
        val night = preset == Sound.NIGHT
        val leveling = level || night
        // Noche reacciona más rápido (como un compresor); el volumen parejo, lento para que no "respire"
        val win = if (night) 0.08 else 0.4
        val coef = 1 - exp(-1.0 / (win * rate))
        val target = if (night) 0.09 else 0.11                    // ≈ -20 dB: volumen de un diálogo normal
        val maxGain = if (night) 4.0 else 3.2
        val attack = 1 - exp(-1.0 / (0.05 * rate))                // bajar: rápido (un golpe fuerte)
        val fastCoef = attack
        val release = 1 - exp(-1.0 / ((if (night) 0.8 else 2.5) * rate))   // subir: de a poco
        var i = 0
        while (i + channels <= n) {
            var peak = 0.0
            for (c in 0 until channels) {
                var x = s[i + c].toDouble()
                for (f in filters) x = f[c].step(x)
                s[i + c] = x.toFloat()
                peak = maxOf(peak, abs(x))
            }
            if (leveling) {
                power += (peak * peak - power) * coef
                fastPower += (peak * peak - fastPower) * fastCoef
                val rms = sqrt(power)
                // Casi silencio (pausa, fin de una escena): no se sube, para no levantar ruido ni "respirar" después
                // de una escena fuerte; bajar sí se puede
                val want = (target / rms).coerceIn(0.25, maxGain).let { if (sqrt(fastPower) < 0.012) minOf(it, gain) else it }
                gain += (want - gain) * if (want < gain) attack else release
            } else if (gain != 1.0) {
                gain += (1.0 - gain) * release
                if (abs(gain - 1.0) < 0.001) gain = 1.0
            }
            for (c in 0 until channels) s[i + c] = limit(s[i + c] * gain).toFloat()
            i += channels
        }
    }

    /** Evita que lo subido se rompa (saturación): suave sobre 0,8. */
    private fun limit(x: Double): Double {
        val a = abs(x)
        if (a <= 0.8) return x
        return (0.8 + 0.2 * tanh((a - 0.8) / 0.2)) * if (x < 0) -1 else 1
    }
}

/**
 * Procesador de audio de ExoPlayer con [SoundDsp]. Siempre activo en PCM (16 bits o decimal) para poder cambiar el
 * ecualizador al vuelo; con todo apagado sólo copia las muestras. El audio que va directo al equipo de sonido
 * (AC-3 sin decodificar) no pasa por aquí.
 */
@OptIn(UnstableApi::class)
class SoundProcessor : BaseAudioProcessor() {
    @Volatile var preset: String = Sound.NORMAL
    @Volatile var level: Boolean = false
    private var dsp: SoundDsp? = null
    private var floats = FloatArray(0)

    override fun onConfigure(input: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (input.encoding != C.ENCODING_PCM_16BIT && input.encoding != C.ENCODING_PCM_FLOAT) throw AudioProcessor.UnhandledAudioFormatException(input)
        dsp = SoundDsp(input.sampleRate, input.channelCount)
        return input
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val d = dsp
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val out = replaceOutputBuffer(remaining)
        d?.preset = preset; d?.level = level
        if (d == null || !d.active) { out.put(inputBuffer); out.flip(); return }
        inputBuffer.order(ByteOrder.nativeOrder())
        if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) {
            val n = remaining / 4
            if (floats.size < n) floats = FloatArray(n)
            for (k in 0 until n) floats[k] = inputBuffer.getFloat()
            d.process(floats, n)
            for (k in 0 until n) out.putFloat(floats[k])
        } else {
            val n = remaining / 2
            if (floats.size < n) floats = FloatArray(n)
            for (k in 0 until n) floats[k] = inputBuffer.getShort() / 32768f
            d.process(floats, n)
            for (k in 0 until n) out.putShort((floats[k] * 32767f).toInt().coerceIn(-32768, 32767).toShort())
        }
        out.flip()
    }

    override fun onReset() { dsp = null }
}
