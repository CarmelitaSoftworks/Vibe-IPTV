package cl.iptv.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mismos casos que web/test/ocr.test.mjs */
class ScreenshotParserTest {
    private fun p(s: String) = ScreenshotParser.parse(s)

    @Test fun mensajeDelProveedor() {
        val r = p("✅ Datos de tu cuenta\nUsuario: juan123\nContraseña: Ab9xK2\nURL del servidor: http://ejemplo-tv.com:8080\nVence: 12/12/2026\nConexiones: 1")
        assertEquals(SourceType.XTREAM, r.type)
        assertEquals("http://ejemplo-tv.com:8080", r.server)
        assertEquals("juan123", r.username)
        assertEquals("Ab9xK2", r.password)
    }

    @Test fun etiquetasEnInglesYPuertoAparte() {
        val r = p("Your IPTV subscription\nUsername\nmaria_88\nPassword\nZz77qq\nHost\nline.provider.net\nPort: 2095")
        assertEquals(SourceType.XTREAM, r.type)
        assertEquals("http://line.provider.net:2095", r.server)
        assertEquals("maria_88", r.username)
    }

    @Test fun enlaceGetPhpConErroresDeLectura() {
        val r = p("Lista M3U: h t t p ; / / tv.ejemplo.cl:80/get.php?username=ana&password=xyz&type=m3u_plus&output=ts\nEPG: http://tv.ejemplo.cl:80/xmltv.php?username=ana&password=xyz")
        assertEquals(SourceType.XTREAM, r.type)
        assertEquals("http://tv.ejemplo.cl:80", r.server)
        assertEquals("ana", r.username)
        assertEquals("xyz", r.password)
        assertTrue(r.url.contains("get.php"))
        assertTrue(r.epgUrl.contains("xmltv.php"))
    }

    @Test fun soloEnlaceM3u8() {
        val r = p("Mi lista\nhttps://cdn.ejemplo.org/listas/chile.m3u8")
        assertEquals(SourceType.M3U, r.type)
        assertEquals("https://cdn.ejemplo.org/listas/chile.m3u8", r.url)
    }

    @Test fun textoSinDatos() {
        val r = p("Hola, ¿cómo estás?\nNos vemos mañana")
        assertNull(r.type)
        assertEquals(0, r.found)
    }

    @Test fun serverStatusNoEsElServidor() {
        val r = p("Server status: online\nUser: pepe\nPass: 1234\nDNS: http://iptv.ejemplo.com:25461")
        assertEquals("http://iptv.ejemplo.com:25461", r.server)
        assertEquals(SourceType.XTREAM, r.type)
    }

    @Test fun vinetas() {
        val r = p("jHola! Aqui estan tus datos\n» Usuario: carlos2026\n» Contrasena: Tq7Lm2xP\nURL: http://demo-iptv.example.com:8080\n1) Vence: 30/11/2026")
        assertEquals(SourceType.XTREAM, r.type)
        assertEquals("carlos2026", r.username)
        assertEquals("Tq7Lm2xP", r.password)
    }
}
