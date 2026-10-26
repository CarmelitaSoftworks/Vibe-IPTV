package cl.iptv.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class SoundTest {
    private val rate = 48_000

    /** Un tono estéreo de [seconds] segundos con volumen [amp]. */
    private fun tone(freq: Double, amp: Double, seconds: Double) = FloatArray((rate * seconds).toInt() * 2) { i ->
        (amp * sin(2 * PI * freq * (i / 2) / rate)).toFloat()
    }
    private fun rms(a: FloatArray, from: Int = a.size / 2) = sqrt((from until a.size).sumOf { a[it].toDouble() * a[it] } / (a.size - from))

    @Test fun normalNoCambiaNada() {
        val d = SoundDsp(rate, 2)
        val a = tone(440.0, 0.3, 0.5); val b = a.copyOf()
        d.process(a)
        assertTrue(a.indices.all { abs(a[it] - b[it]) < 1e-6 })
    }

    @Test fun volumenParejoSubeLoBajoYBajaLoFuerte() {
        val bajo = tone(440.0, 0.02, 6.0)
        SoundDsp(rate, 2).apply { level = true }.process(bajo)
        assertTrue("lo bajo sube: ${rms(bajo)}", rms(bajo) > 0.02 / sqrt(2.0) * 2)
        val fuerte = tone(440.0, 0.9, 6.0)
        SoundDsp(rate, 2).apply { level = true }.process(fuerte)
        assertTrue("lo fuerte baja: ${rms(fuerte)}", rms(fuerte) < 0.9 / sqrt(2.0) * 0.5)
    }

    @Test fun silencioNoSeSube() {
        val d = SoundDsp(rate, 2).apply { level = true }
        val s = tone(440.0, 0.001, 4.0)
        d.process(s)
        assertEquals(1.0, d.gain, 0.05)
    }

    @Test fun nuncaSatura() {
        val d = SoundDsp(rate, 2).apply { level = true; preset = Sound.BASS }
        val a = tone(80.0, 0.99, 2.0)
        d.process(a)
        assertTrue(a.all { abs(it) <= 1f })
    }

    @Test fun masGravesSubeLosGravesYNoLosAgudos() {
        fun gain(freq: Double): Double {
            val a = tone(freq, 0.1, 1.0)
            SoundDsp(rate, 2).apply { preset = Sound.BASS }.process(a)
            return rms(a) / (0.1 / sqrt(2.0))
        }
        assertTrue("graves: ${gain(60.0)}", gain(60.0) > 1.6)
        assertTrue("medios: ${gain(1_000.0)}", gain(1_000.0) in 0.9..1.2)
    }

    @Test fun vocesRealzaLaVoz() {
        assertTrue(Sound.gainAt(Sound.VOICES, 2_500.0) > 3)
        assertTrue(Sound.gainAt(Sound.VOICES, 60.0) < -2)
        assertEquals("", Sound.mpvFilters(Sound.NORMAL, false))
        assertTrue(Sound.mpvFilters(Sound.NORMAL, true).contains("dynaudnorm"))
    }
}
