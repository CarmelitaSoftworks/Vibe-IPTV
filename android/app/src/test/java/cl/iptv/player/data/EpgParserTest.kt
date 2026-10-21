package cl.iptv.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/** Guía XMLTV: lectura normal, comprimida y con datos rotos. */
@RunWith(RobolectricTestRunner::class)
class EpgParserTest {
    private val now = EpgParser.parseTime("20261020120000 +0000")
    private val xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <tv>
          <channel id="c1"><display-name>Canal Uno</display-name></channel>
          <programme start="20261020110000 +0000" stop="20261020123000 +0000" channel="c1"><title>Noticiero</title></programme>
          <programme start="20261020123000 +0000" stop="20261020140000 +0000" channel="c1"><title>Película</title></programme>
        </tv>
    """.trimIndent()

    @Test fun horaConZonaHoraria() {
        assertEquals(EpgParser.parseTime("20261020090000 -0300"), now)
    }

    @Test fun ahoraYDespues() {
        val epg = EpgParser.parse(xml.byteInputStream(), now)
        val (a, b) = epg.nowNext(Item("x", Kind.LIVE, "Canal Uno", tvgId = "c1"), now)
        assertEquals("Noticiero", a?.title); assertEquals("Película", b?.title)
    }

    @Test fun porNombreSinTvgId() {
        val epg = EpgParser.parse(xml.byteInputStream(), now)
        assertEquals("Noticiero", epg.nowNext(Item("x", Kind.LIVE, "Canal Uno"), now).first?.title)
    }

    @Test fun comprimidaConGzip() {
        val bytes = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(xml.toByteArray()) } }.toByteArray()
        val epg = EpgParser.parse(bytes.inputStream(), now)
        assertEquals(1, epg.channelCount)
    }

    @Test fun xmlCortadoNoDejaLaApp() {
        // un archivo de guía a medio descargar: puede fallar, pero sólo con una excepción que la app atrapa
        val r = runCatching { EpgParser.parse(xml.take(xml.length / 2).byteInputStream(), now) }
        assertNull(r.exceptionOrNull() as? Error)
    }
}
