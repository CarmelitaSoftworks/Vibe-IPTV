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

    @Test fun idSinDistinguirMayusculas() {
        val epg = EpgParser.parse(xml.byteInputStream(), now)
        assertEquals("Noticiero", epg.nowNext(Item("x", Kind.LIVE, "Otro nombre", tvgId = "C1"), now).first?.title)
    }

    @Test fun nombreConPaisYCalidad() {
        val epg = EpgParser.parse(xml.byteInputStream(), now)
        for (n in listOf("CL: Canal Uno HD", "|CL| Canal Uno FHD", "Canal Uno 4K", "AR - Canal Uno"))
            assertEquals(n, "Noticiero", epg.nowNext(Item("x", Kind.LIVE, n), now).first?.title)
        assertNull(epg.nowNext(Item("x", Kind.LIVE, "Canal Dos HD"), now).first)
    }

    @Test fun guardadaCompactaSeLeeIgual() {
        val epg = EpgParser.parse(xml.byteInputStream(), now)
        val f = java.io.File.createTempFile("guia", ".bin")
        epg.save(f)
        val back = Epg.load(f)!!
        assertEquals(epg.byId, back.byId)
        assertEquals("Película", back.nowNext(Item("x", Kind.LIVE, "Canal Uno"), now).second?.title)
        f.writeBytes(byteArrayOf(1, 2, 3))
        assertNull(Epg.load(f))                                           // archivo dañado: se vuelve a leer el XML
    }

    @Test fun guiaPedidaCanalPorCanal() {
        val item = Item("l9", Kind.LIVE, "Canal sin guía")
        val epg = Epg.EMPTY.withExtra("l9", listOf(Programme(now - 60_000, now + 60_000, "En vivo", "")))
        assertEquals("En vivo", epg.nowNext(item, now).first?.title)
        assertEquals(true, epg.has(item))
    }

    @Test fun buscarSinTildesYPorPalabras() {
        val epg = EpgParser.parse(xml.byteInputStream(), now)
        val ch = listOf(Item("x", Kind.LIVE, "Canal Uno", url = "u"))
        assertEquals(listOf("Película"), epg.search(ch, "pelicula", from = now - 3_600_000, now = now).map { it.second.title })
        assertEquals("Noticiero", epg.search(ch, "NOTICIERO", from = 0, now = now).first().second.title)
        assertEquals(0, epg.search(ch, "x", from = 0, now = now).size)
    }

    @Test fun marcasNoSeConfundenConPais() {
        assertEquals("hbofamily", Epg.loose("HBO | Family"))
        assertEquals("tntseries", Epg.loose("TNT - Series"))
        assertEquals("canal13", Epg.loose("CL: Canal 13 HD"))
        assertEquals("tn", Epg.loose("|AR| TN"))
    }
}
