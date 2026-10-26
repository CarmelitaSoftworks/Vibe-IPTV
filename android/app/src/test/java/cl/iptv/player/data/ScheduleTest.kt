package cl.iptv.player.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScheduleTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()
    private val hora = 3_600_000L
    private fun rec(id: String, inicio: Long, fin: Long) =
        ScheduledRec(id, "Programa $id", "Canal", "http://tv.ejemplo.pro/live/11112222/11112222/1.ts", "", "", inicio, fin)

    @Test fun guardaOrdenaYCancela() {
        val ahora = System.currentTimeMillis()
        Schedules.add(ctx, rec("b", ahora + 3 * hora, ahora + 4 * hora))
        Schedules.add(ctx, rec("a", ahora + hora, ahora + 2 * hora))
        assertEquals(listOf("a", "b"), Schedules.pending(ctx).map { it.id })
        Schedules.remove(ctx, "a")
        assertEquals(listOf("b"), Schedules.pending(ctx).map { it.id })
    }

    @Test fun conMargenAntesYDespues() {
        val r = rec("m", 10 * hora, 11 * hora)
        assertEquals(10 * hora - 2 * 60_000, r.recStart)
        assertEquals(11 * hora + 3 * 60_000, r.recEnd)
    }

    @Test fun avisaSiChocan() {
        val ahora = System.currentTimeMillis()
        Schedules.add(ctx, rec("x", ahora + hora, ahora + 2 * hora))
        assertTrue(Schedules.overlaps(ctx, rec("y", ahora + 2 * hora, ahora + 3 * hora)))     // los márgenes se cruzan
        assertFalse(Schedules.overlaps(ctx, rec("z", ahora + 5 * hora, ahora + 6 * hora)))
    }

    @Test fun lasTerminadasSeDescartan() {
        val ahora = System.currentTimeMillis()
        Schedules.add(ctx, rec("vieja", ahora - 3 * hora, ahora - 2 * hora))
        assertTrue(Schedules.pending(ctx).none { it.id == "vieja" })
    }
}
