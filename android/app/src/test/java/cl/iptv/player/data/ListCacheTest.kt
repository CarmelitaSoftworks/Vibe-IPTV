package cl.iptv.player.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Lista guardada en el equipo: ida y vuelta, sin credenciales en disco y archivos dañados. */
@RunWith(RobolectricTestRunner::class)
class ListCacheTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun muestra(x: XtreamClient) = listOf(
        Item("l1", Kind.LIVE, "Canal Ñandú", logo = "http://x/l.png", group = "CHILE | NOTICIAS", url = x.liveUrl(1), tvgId = "c1",
            num = 7, archiveDays = 3, catchup = x.catchupUrl(1), catchupTz = "America/Santiago", cat = "CHILE", sub = "NOTICIAS"),
        Item("s1", Kind.SERIES, "Serie", seriesId = 9, episodes = listOf(
            Item("e1", Kind.SERIES, "Ep 1", url = x.episodeUrl(11, "mkv"), season = 1, episode = 1, seriesName = "Serie"))),
        Item("v1", Kind.MOVIE, "Película", url = x.movieUrl(5, ""), userAgent = "UA", referrer = "http://ref"),
    )

    @Test fun idaYVueltaSinCredencialesEnDisco() {
        val x = XtreamClient("http://srv:8080", "mi usuario", "cl@ve/secreta", "ts")
        val items = muestra(x)
        ListCache.save(ctx, "src1", items, x.epgUrl(), x::scrub)
        val bytes = File(ctx.filesDir, "listas/src1.bin").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(bytes.contains("secreta"))
        val c = ListCache.load(ctx, "src1", x::restore)!!
        assertEquals(items, c.items)
        assertEquals(x.epgUrl(), c.epgUrl)
    }

    @Test fun archivoDanadoSeDescarta() {
        val f = File(ctx.filesDir, "listas").apply { mkdirs() }.resolve("roto.bin")
        f.writeBytes(byteArrayOf(0x56, 0x49, 0x42, 0x45, 0, 0, 0, 1, 1, 2, 3))
        assertNull(ListCache.load(ctx, "roto"))
        assertFalse(f.exists())
    }

    @Test fun sinListaGuardada() {
        assertNull(ListCache.load(ctx, "no-existe"))
    }
}
