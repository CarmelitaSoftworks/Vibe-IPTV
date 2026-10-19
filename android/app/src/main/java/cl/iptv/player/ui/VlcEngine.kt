package cl.iptv.player.ui

import android.net.Uri
import android.view.View
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import cl.iptv.player.data.Http
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File

/**
 * Motor VLC (LibVLC, el mismo de la app VLC, incluido dentro de Vibe IPTV: no hace falta tener VLC instalado).
 * Reproduce los formatos de la TV de cable que ExoPlayer o el equipo no soportan:
 * video MPEG-2 entrelazado, audio AC-3 / E-AC-3 / MP2.
 */
/** Controles de los motores alternativos (VLC y MPV). */
interface AltEngine : PlaybackControl

class VlcHandle(val player: MediaPlayer) : AltEngine {
    override val isPlaying get() = player.isPlaying
    override val position get() = player.time
    override val duration get() = player.length.takeIf { it > 0 && player.isSeekable } ?: -1L
    override fun togglePause() { if (player.isPlaying) player.pause() else player.play() }
    override fun pause() = player.pause()
    override fun resume() = player.play()
    override fun seekTo(ms: Long) { if (player.isSeekable) player.time = ms.coerceAtLeast(0) }

    override fun audioTracks(): List<TrackOpt> = player.audioTracks?.filter { it.id >= 0 }
        ?.map { TrackOpt(it.id.toString(), it.name ?: "Audio ${it.id}", it.id == player.audioTrack) } ?: emptyList()
    override fun selectAudio(id: String) { id.toIntOrNull()?.let { player.audioTrack = it } }

    override fun subtitleTracks(): List<TrackOpt> = player.spuTracks?.filter { it.id >= 0 }
        ?.map { TrackOpt(it.id.toString(), it.name ?: "Subtítulo ${it.id}", it.id == player.spuTrack) } ?: emptyList()
    override fun selectSubtitle(id: String?) { player.spuTrack = id?.toIntOrNull() ?: -1 }

    override var speed: Float
        get() = player.rate
        set(v) { player.rate = v }

    override fun addExternalSubtitle(file: File) {
        player.addSlave(org.videolan.libvlc.interfaces.IMedia.Slave.Type.Subtitle, Uri.fromFile(file), true)
    }

    override fun info(): List<Pair<String, String>> {
        val v = player.currentVideoTrack
        return listOfNotNull(
            v?.let { "Video" to "${it.width}×${it.height}" },
            "Audio" to (player.audioTracks?.firstOrNull { it.id == player.audioTrack }?.name ?: "—"),
        )
    }
}

fun vlcScale(aspect: String): MediaPlayer.ScaleType = when (aspect) {
    "fill", "zoom" -> MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
    "stretch" -> MediaPlayer.ScaleType.SURFACE_FILL
    else -> MediaPlayer.ScaleType.SURFACE_BEST_FIT
}

@Composable
fun VlcVideo(
    url: String,
    userAgent: String,
    referrer: String,
    aspect: String,
    modifier: Modifier = Modifier,
    onReady: (VlcHandle) -> Unit,
    onPlaying: () -> Unit,
    onError: (String) -> Unit,
    onEnded: () -> Unit,
) {
    val ctx = LocalContext.current
    val libVlc = remember {
        LibVLC(ctx.applicationContext, arrayListOf(
            "--network-caching=2500", "--live-caching=2500",
            "--deinterlace=1", "--deinterlace-mode=yadif",   // la TV de cable suele venir entrelazada
            "--http-reconnect", "--audio-time-stretch", "--no-stats", "--avcodec-skiploopfilter=1",
        ))
    }
    val mp = remember { MediaPlayer(libVlc) }
    val handle = remember { VlcHandle(mp) }
    val cbPlaying by rememberUpdatedState(onPlaying)
    val cbError by rememberUpdatedState(onError)
    val cbEnded by rememberUpdatedState(onEnded)
    var attached by remember { mutableStateOf(false) }

    // La superficie de video sólo existe cuando la vista está en pantalla: conectar VLC recién ahí
    AndroidView(
        modifier = modifier,
        factory = { context ->
            VLCVideoLayout(context).also { layout ->
                layout.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                        if (!mp.vlcVout.areViewsAttached()) mp.attachViews(layout, null, true, false)
                        mp.videoScale = vlcScale(aspect)
                        attached = true
                    }
                    override fun onViewDetachedFromWindow(v: View) {
                        // Sólo soltar la superficie: detener VLC aquí bloqueaba la app con canales que no responden
                        attached = false
                        runCatching { if (mp.vlcVout.areViewsAttached()) mp.detachViews() }
                    }
                })
            }
        },
    )

    LaunchedEffect(aspect, attached) { if (attached) mp.videoScale = vlcScale(aspect) }

    // Abrir el canal cuando la superficie está lista (y de nuevo si cambia la dirección)
    LaunchedEffect(url, attached) {
        if (!attached) return@LaunchedEffect
        val media = Media(libVlc, Uri.parse(url))
        media.setHWDecoderEnabled(true, false)
        media.addOption(":http-user-agent=${userAgent.ifBlank { Http.DEFAULT_UA }}")
        if (referrer.isNotBlank()) media.addOption(":http-referrer=$referrer")
        mp.media = media
        media.release()
        mp.play()
        onReady(handle)
    }

    DisposableEffect(Unit) {
        mp.setEventListener { ev ->
            when (ev.type) {
                MediaPlayer.Event.Playing -> cbPlaying()
                MediaPlayer.Event.EncounteredError -> cbError("VLC no pudo abrir el canal")
                MediaPlayer.Event.EndReached -> cbEnded()
            }
        }
        onDispose {
            mp.setEventListener(null)
            runCatching { if (mp.vlcVout.areViewsAttached()) mp.detachViews() }
            // stop() espera a que VLC suelte la conexión (segundos si el canal está colgado): fuera del hilo principal
            EngineReleaser.release {
                runCatching { mp.stop() }
                runCatching { mp.release() }
                runCatching { libVlc.release() }
            }
        }
    }
}
