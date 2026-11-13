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


    @Test fun soloAceptaUnaPeliculaQueCalce() {
        org.junit.Assert.assertTrue(SubtitleSources.sharesWords("Toy Story 4", "Toy Story 4"))
        org.junit.Assert.assertTrue(SubtitleSources.sharesWords("Oppenheimer", "Oppenheimer"))
        org.junit.Assert.assertFalse(SubtitleSources.sharesWords("Duro de matar", "Duro"))           // otra película
        org.junit.Assert.assertFalse(SubtitleSources.sharesWords("Pelicula 123", "Apollo 13"))
        org.junit.Assert.assertTrue(SubtitleSources.sharesWords("El señor de los anillos", "Señor de los Anillos"))
    }

    private fun e(name: String, year: String, id: String) = SubtitleSources.CatalogEntry(name, year, id)

    @Test fun eligeLaPeliculaCorrectaDelCatalogo() {
        val cat = listOf(e("Inception", "2010", "tt1"), e("Otra 2010", "2010", "tt2"))
        // Nombre traducido con año: el primero del catálogo y del mismo año (el catálogo conoce "El origen")
        assertEquals("tt1", SubtitleSources.pickMeta(cat, "El origen", "2010", strict = true)?.id)
        // Automática: nunca una cualquiera de ese año más abajo en la lista
        assertEquals(null, SubtitleSources.pickMeta(listOf(e("Algo", "2009", "tt3"), e("Otra", "2010", "tt2")), "Pelicula 123", "2010", strict = true))
        // Automática sin año ni palabras en común: nada (antes traía otra película)
        assertEquals(null, SubtitleSources.pickMeta(listOf(e("Die Hard", "1988", "tt4")), "Duro de matar", "", strict = true))
        // A mano sin año: lo más parecido (tú eliges de la lista); antes de este arreglo no devolvía nada
        assertEquals("tt4", SubtitleSources.pickMeta(listOf(e("Die Hard", "1988", "tt4")), "Duro de matar", "", strict = false)?.id)
        // Con palabras en común, aunque no sea el primero
        assertEquals("tt5", SubtitleSources.pickMeta(listOf(e("Toy Story", "1995", "tt6"), e("Toy Story 4", "2019", "tt5")), "Toy Story 4", "2019", strict = true)?.id)
    }
}
