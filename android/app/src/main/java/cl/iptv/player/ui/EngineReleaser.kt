package cl.iptv.player.ui

import java.util.concurrent.Executors

/**
 * Detener y liberar VLC / MPV puede tardar varios segundos cuando el canal no responde
 * (las librerías esperan a que se corte la conexión). Hacerlo en el hilo principal congelaba la app,
 * así que se hace aquí, en un hilo aparte y en orden (nunca se libera algo que aún se está deteniendo).
 *
 * Stopping and releasing VLC / MPV can take seconds when a channel hangs; doing it on the main
 * thread froze the app, so it runs here on a single background thread, in order.
 */
object EngineReleaser {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "vibe-engine-release").apply { isDaemon = true } }

    fun release(block: () -> Unit) {
        worker.execute { runCatching(block) }
    }
}
