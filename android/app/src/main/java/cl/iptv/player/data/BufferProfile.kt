package cl.iptv.player.data

/**
 * Cuánto video se guarda por adelantado, igual para los tres motores.
 * Nivel 0 = Rápido · 1 = Equilibrado · 2 = Estable · 3 = Estable + (canales que se cortan seguido).
 */
data class BufferProfile(
    val minMs: Int, val maxMs: Int, val startMs: Int, val rebufferMs: Int,
    val targetBytes: Int, val vlcCachingMs: Int, val mpvSecs: Int, val mpvMaxMiB: Int,
) {
    companion object {
        fun levelOf(setting: String) = when (setting) { "fast" -> 0; "stable" -> 2; else -> 1 }

        /** [memoryMb]: memoria que Android le da a la app; el búfer de ExoPlayer nunca usa más de un tercio. */
        fun of(level: Int, live: Boolean, memoryMb: Int = 256): BufferProfile {
            val p = when (level.coerceIn(0, 3)) {
                // Arranque corto en vivo (1–1,5 s) y más margen tras un corte para no detenerse de nuevo enseguida
                0 -> BufferProfile(6_000, 30_000, if (live) 1_000 else 1_500, 3_000, 32, 1_200, 10, 32)
                1 -> BufferProfile(15_000, 50_000, if (live) 1_500 else 2_500, 8_000, 64, 2_500, 20, 64)
                2 -> BufferProfile(30_000, 90_000, 2_500, 10_000, 96, 5_000, 40, 128)
                else -> BufferProfile(45_000, 120_000, 3_500, 12_000, 128, 8_000, 60, 192)
            }
            val capMb = (memoryMb / 3).coerceAtLeast(24)
            return p.copy(targetBytes = minOf(p.targetBytes, capMb) * 1024 * 1024)
        }
    }
}
