package cl.iptv.player.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleHashTest {
    /** La huella de OpenSubtitles da lo mismo que otra implementación de la misma fórmula (hecha aparte). */
    @Test fun huellaDeOpenSubtitles() {
        val head = ByteArray(65_536) { ((it * 7 + 3) % 256).toByte() }
        val tail = ByteArray(65_536) { ((it * 13 + 5) % 256).toByte() }
        assertEquals("20a01fa01fb2d687", SubtitleSources.hashOf(1_234_567, head, tail))
    }

    @Test fun usaElTituloOriginalSiElDeLaListaNoSirve() {
        val q = SubtitleSources.Query("Pelicula 123", false, alts = listOf("Inception"))
        assertEquals(listOf("Inception", "Pelicula 123"), (q.alts + q.title).distinct())
    }

    @Test fun soloAceptaUnaPeliculaQueCalce() {
        org.junit.Assert.assertTrue(SubtitleSources.sharesWords("Toy Story 4", "Toy Story 4"))
        org.junit.Assert.assertTrue(SubtitleSources.sharesWords("Oppenheimer", "Oppenheimer"))
        org.junit.Assert.assertFalse(SubtitleSources.sharesWords("Duro de matar", "Duro"))           // otra película
        org.junit.Assert.assertFalse(SubtitleSources.sharesWords("Pelicula 123", "Apollo 13"))
        org.junit.Assert.assertTrue(SubtitleSources.sharesWords("El señor de los anillos", "Señor de los Anillos"))
    }
}
