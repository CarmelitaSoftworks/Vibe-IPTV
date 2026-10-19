package cl.iptv.player.ui

import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import cl.iptv.player.data.Http
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * Motor VLC (LibVLC, el mismo de la app VLC). Reproduce los formatos de la TV de cable que
 * ExoPlayer o el decodificador del equipo no soportan: video MPEG-2 entrelazado, audio AC-3 / E-AC-3 / MP2.
 */
/** Controles comunes de los motores alternativos (VLC y MPV). */
interface AltEngine {
    val isPlaying: Boolean
    fun togglePause()
    fun seekBy(ms: Long)
    fun pause()
    fun resume()
    fun audioTracks(): List<Pair<Int, String>>
    var audioTrack: Int
}

class VlcHandle(val player: MediaPlayer) : AltEngine {
    override val isPlaying get() = player.isPlaying
    override fun togglePause() { if (player.isPlaying) player.pause() else player.play() }
    override fun seekBy(ms: Long) { if (player.isSeekable) player.time = (player.time + ms).coerceAtLeast(0) }
    override fun audioTracks(): List<Pair<Int, String>> =
        player.audioTracks?.filter { it.id >= 0 }?.map { it.id to it.name } ?: emptyList()
    override var audioTrack: Int
        get() = player.audioTrack
        set(v) { player.audioTrack = v }
    override fun pause() = player.pause()
    override fun resume() = player.play()
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
        LibVLC(ctx, arrayListOf(
            "--network-caching=2000", "--live-caching=2000",
            "--deinterlace=1", "--deinterlace-mode=yadif",   // la TV de cable suele venir entrelazada
            "--http-reconnect", "--audio-time-stretch", "--no-stats",
        ))
    }
    val mp = remember { MediaPlayer(libVlc) }
    val handle = remember { VlcHandle(mp) }
    val cbPlaying by rememberUpdatedState(onPlaying)
    val cbError by rememberUpdatedState(onError)
    val cbEnded by rememberUpdatedState(onEnded)

    AndroidView(
        modifier = modifier,
        factory = { context -> VLCVideoLayout(context).also { layout -> mp.attachViews(layout, null, true, false) } },
    )

    LaunchedEffect(aspect) { mp.videoScale = vlcScale(aspect) }

    LaunchedEffect(url) {
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
            mp.stop()
            mp.detachViews()
            mp.release()
            libVlc.release()
        }
    }
}
