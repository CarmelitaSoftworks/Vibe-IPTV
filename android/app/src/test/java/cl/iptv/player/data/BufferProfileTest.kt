package cl.iptv.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BufferProfileTest {
    @Test fun nivelesValidosParaExoPlayer() {
        for (level in 0..3) for (live in listOf(true, false)) {
            val p = BufferProfile.of(level, live)
            // Reglas de DefaultLoadControl: arranque y rearranque ≤ mínimo ≤ máximo
            assertTrue(p.startMs <= p.minMs); assertTrue(p.rebufferMs <= p.minMs); assertTrue(p.minMs <= p.maxMs)
        }
    }
    @Test fun masNivelMasBufer() {
        for (level in 0..2) assertTrue(BufferProfile.of(level + 1, true).minMs > BufferProfile.of(level, true).minMs)
    }
    @Test fun vivoArrancaRapidoEnEquilibrado() {
        assertEquals(1_500, BufferProfile.of(1, live = true).startMs)
        assertEquals(8_000, BufferProfile.of(1, live = true).rebufferMs)
    }
    @Test fun respetaLaMemoria() {
        assertEquals(32 * 1024 * 1024, BufferProfile.of(3, true, memoryMb = 96).targetBytes)
        assertEquals(128 * 1024 * 1024, BufferProfile.of(3, true, memoryMb = 512).targetBytes)
    }
    @Test fun nivelFueraDeRango() { assertEquals(BufferProfile.of(3, true), BufferProfile.of(9, true)) }
}
