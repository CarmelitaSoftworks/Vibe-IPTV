package cl.iptv.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CastTest {
    @Test fun idaYVueltaDelCanal() {
        val ch = Item("l1", Kind.LIVE, "Canal Ñuñoa", "http://tv.ejemplo.pro/logo.png", "CHILE", "http://tv.ejemplo.pro/live/11112222/11112222/1.ts",
            num = 7, userAgent = "UA", referrer = "http://tv.ejemplo.pro/")
        val back = Cast.itemFrom(Cast.toJson(ch, 0, "Celular"))!!
        assertEquals(ch.copy(group = "CHILE"), back)
    }

    @Test fun rechazaDireccionesRaras() {
        assertNull(Cast.itemFrom(org.json.JSONObject().put("url", "file:///sdcard/x").put("name", "x")))
        assertNull(Cast.itemFrom(org.json.JSONObject().put("url", "javascript:alert(1)")))
    }

    @Test fun soloRedLocal() {
        assertTrue(Cast.isLocal(InetAddress.getByName("192.168.1.20")))
        assertTrue(Cast.isLocal(InetAddress.getByName("10.0.0.5")))
        assertFalse(Cast.isLocal(InetAddress.getByName("8.8.8.8")))
    }
}
