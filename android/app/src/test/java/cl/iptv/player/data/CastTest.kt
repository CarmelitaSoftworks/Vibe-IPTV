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

    @Test fun pasarListasConCodigo() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        var shown: String? = null
        var received: List<Source> = emptyList()
        val rx = CastReceiver(ctx, "TV de prueba", onPlay = { _, _, _ -> }, onPairCode = { c, _ -> shown = c }, onSources = { l, _ -> received = l })
        rx.start()
        try {
            val tv = Cast.Target("TV de prueba", InetAddress.getByName("127.0.0.1"), rx.port)
            val xt = Source("a", "Mi cuenta", SourceType.XTREAM, server = "http://tv.ejemplo.pro", username = "11112222", password = "11112222")
            Cast.pair(tv)
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            val code = shown!!
            assertEquals(4, code.length)
            // Código equivocado: no entra nada
            assertTrue(runCatching { Cast.sendSources(tv, if (code == "1234") "4321" else "1234", listOf(xt)) }.isFailure)
            assertEquals(1, Cast.sendSources(tv, code, listOf(xt)))
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            assertEquals(listOf(xt), received)
            assertNull(shown)                                                // el código desaparece de la TV
            // El mismo código no sirve dos veces
            assertTrue(runCatching { Cast.sendSources(tv, code, listOf(xt)) }.isFailure)
        } finally { rx.stop() }
    }
}
