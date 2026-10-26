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
 */
class SystemHandle(private val mp: MediaPlayer) : AltEngine {
    /** false en cuanto se libera: desde ahí nadie lo toca (MediaPlayer se cae si se usa después de liberarlo). */
    @Volatile var alive = true
    @Volatile var prepared = false

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
        set(v) = safe(Unit) { if (prepared) mp.playbackParams = mp.playbackParams.setSpeed(v) }

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
    modifier: Modifier = Modifier,
    onReady: (SystemHandle) -> Unit,
    onPlaying: () -> Unit,
    onError: (String) -> Unit,
    onEnded: () -> Unit,
) {
    val ctx = LocalContext.current
    val cbPlaying by rememberUpdatedState(onPlaying)
    val cbError by rememberUpdatedState(onError)
    val cbEnded by rememberUpdatedState(onEnded)
    val mp = remember { MediaPlayer() }
    val handle = remember { SystemHandle(mp) }
    var frame by remember { mutableStateOf<AspectRatioFrameLayout?>(null) }

    DisposableEffect(Unit) {
        var started = false
        mp.setOnPreparedListener { handle.prepared = true; if (handle.alive) it.start() }
        mp.setOnInfoListener { _, what, _ ->
            if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START || (what == MediaPlayer.MEDIA_INFO_BUFFERING_END && !started)) {
                if (!started) { started = true; cbPlaying() }
            }
            false
        }
        mp.setOnVideoSizeChangedListener { _, w, h -> if (w > 0 && h > 0) frame?.setAspectRatio(w.toFloat() / h) }
        mp.setOnErrorListener { _, what, extra -> cbError("El reproductor del sistema no pudo abrir el canal ($what/$extra)"); true }
        mp.setOnCompletionListener { if (started) cbEnded() }
        onDispose {
            handle.alive = false
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
                        override fun surfaceCreated(h: SurfaceHolder) { if (handle.alive) runCatching { mp.setDisplay(h) } }
                        override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, hgt: Int) {}
                        override fun surfaceDestroyed(h: SurfaceHolder) { if (handle.alive) runCatching { mp.setDisplay(null) } }
                    })
                }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, android.view.Gravity.CENTER))
            }
        },
        update = { it.resizeMode = systemResize(aspect) },
    )

    LaunchedEffect(url) {
        if (!handle.alive) return@LaunchedEffect
        runCatching {
            val headers = buildMap {
                put("User-Agent", userAgent.ifBlank { Http.DEFAULT_UA })
                if (referrer.isNotBlank()) put("Referer", referrer)
            }
            mp.setDataSource(ctx, Uri.parse(url), headers)
            mp.prepareAsync()
            onReady(handle)
        }.onFailure { cbError("El reproductor del sistema no pudo abrir el canal: ${it.message}") }
    }
}
