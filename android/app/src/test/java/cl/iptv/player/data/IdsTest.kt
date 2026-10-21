package cl.iptv.player.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** Ids repetidos (mismo canal en varias categorías de Xtream) cerraban la app en los temas. */
class IdsTest {
    @Test fun numeraRepetidosYConservaElPrimero() {
        val list = listOf(Item("l1", Kind.LIVE, "A"), Item("l1", Kind.LIVE, "B"), Item("l2", Kind.LIVE, "C"), Item("l1", Kind.LIVE, "D"))
        val (out, n) = list.withUniqueIds()
        assertEquals(2, n)
        assertEquals("l1", out[0].id)
        assertEquals(out.size, out.map { it.id }.toSet().size)
    }

    @Test fun noChocaConIdsQueYaTerminanEnNumero() {
        val list = listOf(Item("x", Kind.LIVE, "A"), Item("x_1", Kind.LIVE, "B"), Item("x", Kind.LIVE, "C"))
        val (out, _) = list.withUniqueIds()
        assertEquals(3, out.map { it.id }.toSet().size)
    }

    @Test fun sinRepetidosNoCambiaNada() {
        val list = (1..100).map { Item("v$it", Kind.MOVIE, "P$it") }
        val (out, n) = list.withUniqueIds()
        assertEquals(0, n); assertEquals(list, out)
    }
}
