package cl.iptv.player.data

import org.junit.Assert.assertEquals
import org.junit.Test
import cl.iptv.player.data.StallDetector.Action

/** Situaciones en que el reproductor podía quedar pegado. */
class StallDetectorTest {
    private fun run(d: StallDetector, from: Long, to: Long, step: Long = 2000, pos: (Long) -> Long, want: Boolean = true, canSwitch: Boolean = true): List<Action> {
        val out = mutableListOf<Action>()
        var t = from
        while (t <= to) { out += d.check(t, pos(t), want, canSwitch); t += step }
        return out.filter { it != Action.NONE }
    }

    @Test fun reproduccionNormalNoHaceNada() {
        val d = StallDetector().apply { reset(0) }
        assertEquals(emptyList<Action>(), run(d, 0, 120_000, pos = { it }))
    }

    @Test fun nuncaEmpiezaRecargaYLuegoCambiaDeMotor() {
        val d = StallDetector().apply { reset(0) }
        // la posición nunca pasa de 0: canal que se queda cargando para siempre
        assertEquals(listOf(Action.RELOAD, Action.SWITCH_ENGINE), run(d, 0, 60_000, pos = { 0 }))
    }

    @Test fun sinOtroMotorAvisa() {
        val d = StallDetector().apply { reset(0) }
        assertEquals(listOf(Action.RELOAD, Action.GIVE_UP), run(d, 0, 60_000, pos = { 0 }, canSwitch = false))
    }

    @Test fun sePegaDespuesDeAndar() {
        val d = StallDetector().apply { reset(0) }
        // anda 30 s y luego la imagen se congela en 30 s
        val acts = run(d, 0, 50_000, pos = { minOf(it, 30_000) })
        assertEquals(Action.RELOAD, acts.first())
    }

    @Test fun pausaDelUsuarioNoEsBloqueo() {
        val d = StallDetector().apply { reset(0) }
        run(d, 0, 10_000, pos = { it })
        assertEquals(emptyList<Action>(), run(d, 12_000, 120_000, pos = { 10_000 }, want = false))
    }

    @Test fun siSeRecuperaTrasRecargarVuelveAEmpezarLaCuenta() {
        val d = StallDetector().apply { reset(0) }
        val first = run(d, 0, 30_000, pos = { 0 })                      // recarga
        assertEquals(listOf(Action.RELOAD), first)
        run(d, 32_000, 80_000, pos = { it - 32_000 })                   // vuelve a andar
        val later = run(d, 82_000, 100_000, pos = { 48_000 })           // se pega otra vez: recarga, no cambia de motor
        assertEquals(Action.RELOAD, later.first())
    }
}
