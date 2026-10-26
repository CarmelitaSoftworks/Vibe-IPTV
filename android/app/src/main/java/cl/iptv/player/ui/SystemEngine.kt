package cl.iptv.player.ui

import android.media.MediaPlayer
import android.net.Uri
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.AspectRatioFrameLayout
import cl.iptv.player.data.Http
import java.io.File

/**
 * Motor del sistema: el reproductor que trae Android (MediaPlayer). No agrega peso a la app y usa directamente el chip
 * del equipo; en algunos TV box maneja mejor la imagen entrelazada y el audio Dolby porque lo resuelve el fabricante.
 * Cómo funciona depende de cada marca, por eso queda como último recurso (o elegible a mano).
 *
 * El reproductor en sí no se puede cambiar (es parte del sistema del equipo); lo que se mejora es cómo se usa:
 * reconexión automática cuando la señal en vivo se corta (el del sistema se rinde al primer corte), y datos para
 * el diagnóstico (reserva cargada en películas, esperas de carga y reconexiones).
 */
class SystemHandle(private val mp: MediaPlayer) : AltEngine {
    /** false en cuanto se libera: desde ahí nadie lo toca (MediaPlayer se cae si se usa después de liberarlo). */
    @Volatile var alive = true
    @Volatile var prepared = false
    /** Diagnóstico: % cargado (películas y series), veces que se quedó cargando y reconexiones. */
    @Volatile var bufferPercent = 0
    @Volatile var stalls = 0
    @Volatile var reconnects = 0

    private inline fun <T> safe(default: T, f: () -> T): T = if (!alive) default else runCatching(f).getOrDefault(default)

    override val isPlaying get() = safe(false) { mp.isPlaying }
    override val position get() = safe(0L) { if (prepared) mp.currentPosition.toLong() else 0L }
    override val duration get() = safe(-1L) { if (prepared) mp.duration.toLong().takeIf { it > 0 } ?: -1L else -1L }
    override fun togglePause() = safe(Unit) { if (mp.isPlaying) mp.pause() else mp.start() }
    override fun pause() = safe(Unit) { mp.pause() }
    override fun resume() = safe(Unit) { mp.start() }
    override fun seekTo(ms: Long) = safe(Unit) { if (prepared && duration > 0) mp.seekTo(ms.coerceAtLeast(0).toInt()) }

    private fun tracks(type: Int): List<Pair<Int, MediaPlayer.TrackInfo>> = safe(emptyList()) {
        mp.trackInfo.withIndex().filter { it.value.trackType == type }.map { it.index to it.value }
    }
    private fun label(t: MediaPlayer.TrackInfo, n: Int, kind: String) =
        t.language?.takeIf { it.isNotBlank() && it != "und" }?.let { java.util.Locale(it).getDisplayLanguage(java.util.Locale("es")).replaceFirstChar { c -> c.uppercase() } }
            ?: "$kind $n"

    override fun audioTracks(): List<TrackOpt> {
        val sel = safe(-1) { mp.getSelectedTrack(MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) }
        return tracks(MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO).mapIndexed { n, (i, t) -> TrackOpt(i.toString(), label(t, n + 1, "Audio"), i == sel) }
    }
    override fun selectAudio(id: String) = safe(Unit) { id.toIntOrNull()?.let { mp.selectTrack(it) }; Unit }

    override fun subtitleTracks(): List<TrackOpt> {
        val sel = safe(-1) { mp.getSelectedTrack(MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT) }
        return tracks(MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT).mapIndexed { n, (i, t) -> TrackOpt(i.toString(), label(t, n + 1, "Subtítulo"), i == sel) }
    }
    override fun selectSubtitle(id: String?) = safe(Unit) {
        if (id == null) {
            val sel = mp.getSelectedTrack(MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT)
            if (sel >= 0) mp.deselectTrack(sel)
        } else id.toIntOrNull()?.let { mp.selectTrack(it) }
        Unit
    }

    override var speed: Float
        get() = safe(1f) { if (prepared) mp.playbackParams.speed else 1f }
        // En pausa no: en varios equipos cambiar la velocidad vuelve a reproducir
        set(v) = safe(Unit) { if (prepared && mp.isPlaying) mp.playbackParams = mp.playbackParams.setSpeed(v) }

    /** Sólo en películas y series (el del sistema informa cuánto lleva cargado del archivo; en vivo no). */
    override val bufferedAheadMs: Long? get() = safe(null) {
        val d = duration
        if (d > 0 && bufferPercent in 1..100) (d * bufferPercent / 100 - position).coerceAtLeast(0) else null
    }

    /** Sólo .srt (lo único que el reproductor del sistema acepta como subtítulo externo). */
    override fun addExternalSubtitle(file: File) = safe(Unit) {
        if (file.name.endsWith(".srt", true)) {
            mp.addTimedTextSource(file.absolutePath, MediaPlayer.MEDIA_MIMETYPE_TEXT_SUBRIP)
            tracks(MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT).lastOrNull()?.let { mp.selectTrack(it.first) }
        }
    }

    override fun info(): List<Pair<String, String>> = listOfNotNull(
        safe<Pair<String, String>?>(null) { if (mp.videoWidth > 0) "Video" to "${mp.videoWidth}×${mp.videoHeight}" else null },
        "Decodificación" to "Reproductor del sistema (chip del equipo)",
        "Esperas de carga" to stalls.toString(),
        "Reconexiones" to reconnects.toString(),
    )
}

private fun systemResize(aspect: String) = when (aspect) {
    "fill", "zoom" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    "stretch" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
}

@Composable
fun SystemVideo(
    url: String,
    userAgent: String,
    referrer: String,
    aspect: String,
    live: Boolean,
    modifier: Modifier = Modifier,
    onReady: (SystemHandle) -> Unit,
    /** Avisos breves para la persona (p. ej. "reconectando"). */
    onStatus: (String) -> Unit = {},
    onPlaying: () -> Unit,
    onError: (String) -> Unit,
    onEnded: () -> Unit,
) {
    val ctx = LocalContext.current
    val cbPlaying by rememberUpdatedState(onPlaying)
    val cbError by rememberUpdatedState(onError)
    val cbEnded by rememberUpdatedState(onEnded)
    val cbStatus by rememberUpdatedState(onStatus)
    val mp = remember { MediaPlayer() }
    val handle = remember { SystemHandle(mp) }
    var frame by remember { mutableStateOf<AspectRatioFrameLayout?>(null) }

    var surfaceHolder by remember { mutableStateOf<SurfaceHolder?>(null) }
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    val headers = remember(userAgent, referrer) {
        buildMap {
            put("User-Agent", userAgent.ifBlank { Http.DEFAULT_UA })
            if (referrer.isNotBlank()) put("Referer", referrer)
        }
    }
    fun open() {
        handle.prepared = false; handle.bufferPercent = 0
        mp.setDataSource(ctx, Uri.parse(url), headers)
        surfaceHolder?.let { runCatching { mp.setDisplay(it) } }
        mp.prepareAsync()
    }

    DisposableEffect(Unit) {
        var started = false
        // En vivo, un corte de la señal termina la transmisión (error o "fin"): se reconecta solo unas veces,
        // con esperas cada vez más largas, antes de darse por vencido. Si vuelve a andar, se reinicia la cuenta.
        var tries = 0
        fun reconnect(why: String): Boolean {
            if (!live || !handle.alive || tries >= 5) return false
            tries++; handle.reconnects++
            cbStatus("Señal cortada: reconectando (${tries}/5)…")
            main.postDelayed({
                if (handle.alive) runCatching { mp.reset(); open() }.onFailure { cbError("$why: ${it.message}") }
            }, 1_000L * tries)
            return true
        }
        mp.setOnPreparedListener { handle.prepared = true; if (handle.alive) it.start() }
        mp.setOnInfoListener { _, what, _ ->
            when (what) {
                MediaPlayer.MEDIA_INFO_BUFFERING_START -> if (started) handle.stalls++
                MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START, MediaPlayer.MEDIA_INFO_BUFFERING_END -> {
                    tries = 0
                    if (!started) { started = true; cbPlaying() }
                }
            }
            false
        }
        mp.setOnBufferingUpdateListener { _, pct -> handle.bufferPercent = pct }
        mp.setOnVideoSizeChangedListener { _, w, h -> if (w > 0 && h > 0) frame?.setAspectRatio(w.toFloat() / h) }
        mp.setOnErrorListener { _, what, extra ->
            val why = "El reproductor del sistema no pudo abrir el canal ($what/$extra)"
            if (!(started && reconnect(why))) cbError(why)
            true
        }
        mp.setOnCompletionListener { if (started && !reconnect("La transmisión terminó")) cbEnded() }
        onDispose {
            handle.alive = false
            main.removeCallbacksAndMessages(null)
            // release() puede tardar si el canal está colgado: fuera del hilo principal
            EngineReleaser.release { runCatching { mp.reset() }; runCatching { mp.release() } }
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            AspectRatioFrameLayout(context).apply {
                resizeMode = systemResize(aspect)
                keepScreenOn = true
                frame = this
                addView(SurfaceView(context).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder) { surfaceHolder = h; if (handle.alive) runCatching { mp.setDisplay(h) } }
                        override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, hgt: Int) {}
                        override fun surfaceDestroyed(h: SurfaceHolder) { surfaceHolder = null; if (handle.alive) runCatching { mp.setDisplay(null) } }
                    })
                }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, android.view.Gravity.CENTER))
            }
        },
        update = { it.resizeMode = systemResize(aspect) },
    )

    LaunchedEffect(url) {
        if (!handle.alive) return@LaunchedEffect
        runCatching { open(); onReady(handle) }
            .onFailure { cbError("El reproductor del sistema no pudo abrir el canal: ${it.message}") }
    }
}
