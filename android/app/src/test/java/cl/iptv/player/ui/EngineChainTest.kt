package cl.iptv.player.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Orden en que se prueban los motores en automático cuando un canal falla. */
@RunWith(RobolectricTestRunner::class)
class EngineChainTest {
    @Config(sdk = [34])
    @Test fun ordenCompleto() {
        assertEquals("vlc", nextEngine("exo"))
        assertEquals("mpv", nextEngine("vlc"))
        assertEquals("system", nextEngine("mpv"))
        assertNull(nextEngine("system"))
    }

    // MPV necesita Android 8: en equipos más antiguos, de VLC se pasa directo al del sistema
    @Config(sdk = [24])
    @Test fun sinMpvEnAndroidAntiguo() { assertEquals("system", nextEngine("vlc")) }
}
