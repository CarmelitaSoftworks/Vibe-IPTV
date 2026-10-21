package cl.iptv.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lector de listas M3U: casos reales que han dado problemas. */
class M3uParserTest {
    private fun parse(text: String) = M3uParser.parse(text.trimIndent().reader().buffered())

    @Test fun leeCanalesGrupoLogoYGuia() {
        val r = parse("""
            #EXTM3U url-tvg="http://epg.example/guide.xml"
            #EXTINF:-1 tvg-id="c1" tvg-logo="http://x/logo.png" group-title="Noticias",Canal Uno
            http://server/live/1.ts
        """)
        assertEquals("http://epg.example/guide.xml", r.epgUrl)
        assertEquals(1, r.items.size)
        val it = r.items[0]
        assertEquals("Canal Uno", it.name); assertEquals("Noticias", it.group); assertEquals("c1", it.tvgId)
    }

    @Test fun urlsRepetidasTienenIdsDistintos() {
        val r = parse("""
            #EXTM3U
            #EXTINF:-1 group-title="A",Canal
            http://server/live/1.ts
            #EXTINF:-1 group-title="B",Canal
            http://server/live/1.ts
            #EXTINF:-1 group-title="C",Canal
            http://server/live/1.ts
        """)
        assertEquals(3, r.items.size)
        assertEquals(3, r.items.map { it.id }.toSet().size)
    }

    @Test fun sinGrupoNiCabeceraNoFalla() {
        val r = parse("""
            #EXTINF:-1,Sin grupo
            http://server/a.m3u8
            línea suelta que no es url
        """)
        assertTrue(r.items.isNotEmpty())
        assertEquals("Sin categoría", r.items[0].group)
    }

    @Test fun listaVaciaOBasura() {
        assertEquals(0, parse("").items.size)
        parse("#EXTINF:-1 tvg-name=\"\" group-title=\",\n\n#EXTINF")   // no debe lanzar
    }
}
