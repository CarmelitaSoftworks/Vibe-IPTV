package cl.iptv.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveCushionTest {
    @Test fun sinColchonVaMasLento() {
        val c = LiveCushion()
        assertEquals(LiveCushion.SLOWER, c.update(aheadMs = 1_000, targetMs = 8_000))
        assertEquals(LiveCushion.SLOW, c.update(aheadMs = 4_000, targetMs = 8_000))
    }

    @Test fun conColchonVaNormal() {
        val c = LiveCushion()
        assertEquals(1f, c.update(aheadMs = 9_000, targetMs = 8_000))
    }

    @Test fun histeresisNoCambiaACadaRato() {
        val c = LiveCushion()
        c.update(4_000, 8_000)                                   // bajo: lento
        assertEquals(LiveCushion.SLOW, c.update(7_000, 8_000))   // entre medio: sigue lento hasta la meta
        assertEquals(1f, c.update(8_000, 8_000))                 // meta: normal
        assertEquals(1f, c.update(7_000, 8_000))                 // entre medio: sigue normal
    }

    @Test fun nuncaMasDeCuatroPorCientoMasLento() {
        val c = LiveCushion()
        for (a in 0L..30_000L step 500) assertTrue(c.update(a, 25_000) >= 0.96f)
    }

    @Test fun masNivelMasColchon() {
        for (l in 0..2) assertTrue(LiveCushion.targetMs(l + 1) > LiveCushion.targetMs(l))
        assertEquals(LiveCushion.targetMs(3), LiveCushion.targetMs(9))
    }

    @Test fun reintentosRapidosYConTope() {
        assertEquals(300L, LiveCushion.retryDelayMs(1))
        assertEquals(600L, LiveCushion.retryDelayMs(2))
        assertEquals(4_000L, LiveCushion.retryDelayMs(10))
    }
}
