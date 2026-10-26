package cl.iptv.player.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelCheckTest {
    private fun bytes(s: String) = s.toByteArray(Charsets.ISO_8859_1).let { it to it.size }

    @Test fun listaHlsAunqueTraigaBomOLineasEnBlanco() {
        val (b, n) = bytes("ï»¿\n#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nlow.m3u8\n")
        assertTrue(ChannelCheck.looksAlive(b, n))
    }

    @Test fun paginaDeErrorNoCuentaComoCanal() {
        val html = "<html><body>" + "Stream offline ".repeat(300) + "</body></html>"
        val (b, n) = bytes(html)
        assertFalse(ChannelCheck.looksAlive(b, n))
        val (j, m) = bytes("{\"error\":\"offline\"}" + " ".repeat(3000))
        assertFalse(ChannelCheck.looksAlive(j, m))
    }

    @Test fun videoMpegTs() {
        val ts = ByteArray(188 * 12) { i -> if (i % 188 == 5) 0x47 else 1 }
        assertTrue(ChannelCheck.looksAlive(ts, ts.size))
        val basura = ByteArray(4000) { 1 }
        assertFalse(ChannelCheck.looksAlive(basura, basura.size))
    }
}
