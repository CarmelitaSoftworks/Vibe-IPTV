package cl.iptv.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class TimeshiftTest {
    private var t = 1_000_000L
    private fun buffer(keepMs: Long = 60_000, maxBytes: Long = Long.MAX_VALUE, chunk: Long = 1_000): TimeshiftBuffer =
        TimeshiftBuffer(Files.createTempDirectory("ts").toFile(), keepMs, maxBytes, chunk) { t }

    private fun TimeshiftBuffer.feed(bytes: Int, value: Int, everyMs: Long = 1_000) {
        append(ByteArray(bytes) { value.toByte() }, 0, bytes); t += everyMs
    }

    @Test fun leeLoQueSeEscribioAunqueCambieDeTrozo() {
        val b = buffer()
        b.feed(1_500, 1); b.feed(1_500, 2)
        val out = ByteArray(5_000)
        var pos = 0L; var total = 0
        while (true) { val n = b.read(pos, out, total, out.size - total); if (n <= 0) break; pos += n; total += n }
        assertEquals(3_000, total)
        assertEquals(1, out[0].toInt()); assertEquals(2, out[2_999].toInt())
        assertEquals(0, b.read(3_000, out, 0, 10))                        // todavía no llega más
        b.close()
    }

    @Test fun pasaDeHoraAPosicionAlineadaAPaquetes() {
        val b = buffer(chunk = 10_000)
        repeat(10) { b.feed(1_880, it) }                                  // 1.880 bytes por "segundo"
        val at = b.offsetAt(1_000_000L + 4_000)
        assertEquals(0L, at % TimeshiftBuffer.TS_PACKET)
        assertTrue(at in 7_000L..10_000L)
        assertEquals(b.oldest, b.offsetAt(0))                             // antes de todo: lo más viejo
        b.close()
    }

    @Test fun borraLoViejoYaVistoPeroNoLoQueFaltaVer() {
        val b = buffer(keepMs = 5_000, chunk = 1_000)
        b.readerAt = 0                                                    // en pausa al principio
        repeat(20) { b.feed(1_000, it) }
        assertEquals(0L, b.oldest)                                        // no se borra lo que todavía no se ve
        b.readerAt = b.written
        b.feed(1_000, 99)
        assertTrue("se borraron los viejos: ${b.oldest}", b.oldest >= 14_000)
        assertEquals(-1, b.read(0, ByteArray(10), 0, 10))
        b.close()
    }

    @Test fun respetaElTopeDeEspacioAunqueEsteEnPausa() {
        val b = buffer(keepMs = 600_000, maxBytes = 5_000, chunk = 1_000)
        repeat(20) { b.feed(1_000, it) }
        assertTrue(b.written - b.oldest <= 6_000)
        val dir = File(b.javaClass.getDeclaredField("dir").apply { isAccessible = true }.get(b) as File, "")
        assertTrue((dir.listFiles()?.size ?: 0) <= 6)
        b.close()
        assertTrue(!dir.exists())
    }
}
