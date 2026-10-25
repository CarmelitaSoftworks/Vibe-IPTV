package cl.iptv.player.data

/**
 * Colchón en vivo: un canal en directo llega al ritmo real, así que el video guardado por adelantado nunca crece solo
 * y cada corte lo gasta para siempre. Para rehacerlo, se reproduce un poco más lento (2–4 %, no se nota: el audio
 * mantiene su tono) mientras el colchón esté bajo, y se vuelve a velocidad normal cuando alcanza la meta.
 * Ganancia: unos 1,2–2,4 s de colchón por minuto. Lógica pura (sin Android) para poder probarla.
 */
class LiveCushion {
    /** Velocidad actual (1 = normal). */
    var speed = 1f; private set

    /**
     * [aheadMs]: video ya cargado por delante de lo que se ve; [targetMs]: colchón deseado (según el nivel de búfer).
     * Con margen (histéresis) para no cambiar de velocidad a cada rato.
     */
    fun update(aheadMs: Long, targetMs: Long): Float {
        speed = when {
            aheadMs < targetMs / 3 -> SLOWER                       // casi sin colchón: rehacerlo más rápido
            aheadMs < targetMs * 3 / 4 -> SLOW
            aheadMs >= targetMs -> 1f                              // meta alcanzada
            else -> if (speed < 1f) SLOW else 1f                   // entre medio: seguir como iba
        }
        return speed
    }

    fun reset() { speed = 1f }

    companion object {
        const val SLOW = 0.98f
        const val SLOWER = 0.96f

        /** Colchón deseado según el nivel de búfer (0 = Rápido … 3 = canales que se cortan seguido). */
        fun targetMs(level: Int): Long = when (level.coerceIn(0, 3)) { 0 -> 4_000; 1 -> 8_000; 2 -> 15_000; else -> 25_000 }

        /**
         * Espera antes de reintentar una descarga caída en directo: rápido al principio (el corte suele ser breve) y
         * nunca más de 4 s, para reconectar mientras aún queda video guardado.
         */
        fun retryDelayMs(attempt: Int): Long = (300L * (1 shl (attempt - 1).coerceIn(0, 4))).coerceAtMost(4_000)
    }
}
