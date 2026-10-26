package cl.iptv.player.data

/**
 * Orden de los decodificadores: primero los del chip del equipo (rápidos y livianos para el procesador) y después los
 * de software, que quedan sólo de respaldo si el del chip no puede con el video o falla al iniciar.
 * Algunos TV box listan primero un decodificador por software y ExoPlayer lo tomaba aunque hubiera uno del chip.
 * Lógica pura para poder probarla; respeta el orden original dentro de cada grupo.
 */
object Decoders {
    fun <T> hardwareFirst(list: List<T>, isHardware: (T) -> Boolean): List<T> {
        val (hw, sw) = list.partition(isHardware)
        return hw + sw
    }

    /** Nombres de los decodificadores por software que trae Android (Google / AOSP). */
    fun isSoftwareName(name: String): Boolean {
        val n = name.lowercase()
        return n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.startsWith("omx.ffmpeg.") || n.contains(".sw.")
    }
}
