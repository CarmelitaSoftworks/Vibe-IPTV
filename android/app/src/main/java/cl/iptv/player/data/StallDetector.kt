package cl.iptv.player.data

/**
 * Detecta cuando la reproducción queda "pegada": se quiere reproducir pero la posición no avanza.
 * Lógica pura (sin Android) para poder probarla. El reproductor la consulta cada 2 s.
 *
 * Acciones: primero recarga el canal; si vuelve a pegarse, cambia de motor (en automático) o avisa.
 */
class StallDetector(
    /** Tiempo sin avanzar antes de actuar (en vivo hay más margen para cargar). */
    private val firstStartMs: Long = 25_000,
    private val stallMs: Long = 12_000,
) {
    enum class Action { NONE, RELOAD, SWITCH_ENGINE, GIVE_UP }

    private var lastPos = -1L
    private var lastMove = 0L
    private var started = false
    private var openedAt = 0L
    private var reloads = 0

    /** Llamar al abrir un canal (o al cambiar de motor). */
    fun reset(now: Long) { lastPos = -1; lastMove = now; started = false; openedAt = now; reloads = 0 }

    /** Llamar tras recargar: se cuenta el intento pero se vuelve a dar margen. */
    private fun afterReload(now: Long) { lastPos = -1; lastMove = now; started = false; openedAt = now }

    /**
     * now: reloj; position: posición actual (ms); wantPlay: el usuario no pausó; canSwitch: hay otro motor que probar.
     */
    fun check(now: Long, position: Long, wantPlay: Boolean, canSwitch: Boolean): Action {
        if (!wantPlay) { lastMove = now; lastPos = position; return Action.NONE }   // en pausa no cuenta
        if (position != lastPos) {
            if (lastPos >= 0 && position > lastPos) started = true
            lastPos = position; lastMove = now
            if (started) reloads = 0                                                 // volvió a andar
            return Action.NONE
        }
        val limit = if (started) stallMs else firstStartMs
        if (now - lastMove < limit) return Action.NONE
        return when {
            reloads == 0 -> { reloads++; afterReload(now); Action.RELOAD }
            canSwitch -> { reset(now); Action.SWITCH_ENGINE }
            else -> { reset(now); Action.GIVE_UP }
        }
    }
}
