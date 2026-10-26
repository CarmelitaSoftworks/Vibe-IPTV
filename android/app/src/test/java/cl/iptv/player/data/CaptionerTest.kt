package cl.iptv.player.data

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Audio que llega a los subtítulos con IA: conversión a 16 kHz mono, 5.1, PCM en coma flotante y atraso máximo. */
@RunWith(RobolectricTestRunner::class)
class CaptionerTest {
    private fun pcm16(frames: Int, ch: Int, value: (frame: Int, c: Int) -> Int): ByteBuffer =
        ByteBuffer.allocate(frames * ch * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            for (i in 0 until frames) for (c in 0 until ch) putShort(value(i, c).toShort())
            flip()
        }

    @Test fun estereo48kHzPasaA16kHzEnTrozosDe02s() {
        val cap = LiveCaptioner().apply { armForTest() }
        cap.sink.flush(48_000, 2, C.ENCODING_PCM_16BIT)
        repeat(10) { cap.sink.handleBuffer(pcm16(4_800, 2) { _, _ -> 1000 }) }   // 1 s de audio
        val chunks = cap.drainForTest()
        assertEquals(5, chunks.size)
        assertTrue(chunks.all { it.size == 3_200 && it.all { s -> s.toInt() == 1000 } })
    }

    @Test fun cincoPuntoUnoUsaElCanalCentral() {
        val cap = LiveCaptioner().apply { armForTest() }
        cap.sink.flush(48_000, 6, C.ENCODING_PCM_16BIT)
        repeat(2) { cap.sink.handleBuffer(pcm16(4_800, 6) { _, c -> if (c == 2) 2000 else -9000 }) }
        val s = cap.drainForTest().first()
        assertTrue(s.all { it.toInt() == 2000 })
    }

    @Test fun comaFlotante() {
        val cap = LiveCaptioner().apply { armForTest() }
        cap.sink.flush(16_000, 1, C.ENCODING_PCM_FLOAT)
        val b = ByteBuffer.allocate(3_200 * 4).order(ByteOrder.LITTLE_ENDIAN).apply { repeat(3_200) { putFloat(0.5f) }; flip() }
        cap.sink.handleBuffer(b)
        val s = cap.drainForTest().single()
        assertTrue(s.all { it.toInt() in 16_380..16_386 })
    }

    @Test fun nuncaAcumulaMasDe3Segundos() {
        val cap = LiveCaptioner().apply { armForTest() }
        cap.sink.flush(16_000, 1, C.ENCODING_PCM_16BIT)
        repeat(100) { cap.sink.handleBuffer(pcm16(3_200, 1) { _, _ -> 1 }) }    // 20 s sin que nadie los lea
        assertTrue(cap.pendingSamples <= 16_000 * 3)
    }

    @Test fun lineasCortasSinPartirPalabras() {
        val t = "esta es una frase bastante larga que antes llenaba toda la pantalla con subtítulos interminables"
        val l = LiveCaptioner.wrap(t)
        assertTrue(l.size > 1 && l.all { it.length <= LiveCaptioner.LINE })
        assertEquals(t, l.joinToString(" "))
    }

    @Test fun volumenAutomaticoSubeLaVozBajaYNoElSilencio() {
        val voz = ShortArray(3_200) { if (it % 2 == 0) 500 else -500 }
        var g = 1f
        repeat(30) { g = LiveCaptioner.normalize(voz.copyOf(), g) }
        assertTrue(g > 3f)
        val silencio = ShortArray(3_200) { 20 }
        assertEquals(g, LiveCaptioner.normalize(silencio, g))
    }

    @Test fun conAtrasoSeSaltanLosSilencios() {
        val cap = LiveCaptioner().apply { armForTest() }
        cap.sink.flush(16_000, 1, C.ENCODING_PCM_16BIT)
        repeat(10) { cap.sink.handleBuffer(pcm16(3_200, 1) { _, _ -> 3000 }) }   // 2 s de voz sin leer
        repeat(10) { cap.sink.handleBuffer(pcm16(3_200, 1) { _, _ -> 0 }) }      // 2 s de silencio
        assertEquals(16_000 * 2, cap.pendingSamples)
    }

    @Test fun descartaRuidoSuelto() {
        assertEquals("", LiveCaptioner.clean("[unk]"))
        assertEquals("", LiveCaptioner.clean(" y "))
        assertEquals("hola a todos", LiveCaptioner.clean("hola  [unk] a todos"))
        assertEquals("sol", LiveCaptioner.clean("sol"))
    }

    @Test fun filtroDeVozQuitaGravesYDejaLaVoz() {
        fun tono(hz: Double) = ShortArray(16_000) { (8_000 * kotlin.math.sin(2 * Math.PI * hz * it / 16_000)).toInt().toShort() }
        fun nivel(hz: Double): Double { val a = tono(hz); VoiceFilter().process(a); return LiveCaptioner.rms(a.copyOfRange(4_000, 16_000)) }
        val voz = nivel(1_000.0)
        assertTrue("la voz pasa casi entera", voz > 5_000)
        assertTrue("los graves se atenúan", nivel(40.0) < voz / 4)
        assertTrue("lo muy agudo se atenúa", nivel(7_000.0) < voz / 3)
    }
}
