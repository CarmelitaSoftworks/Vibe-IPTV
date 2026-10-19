package cl.iptv.player.ui

import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import cl.iptv.player.data.Http
import dev.jdtech.mpv.MPVLib

/**
 * Motor MPV (libmpv, el mismo que usan mpv-android y OwnTV). Tercera opción cuando ExoPlayer y VLC
 * no logran reproducir un canal: decodifica casi cualquier formato y tolera bien las transmisiones inestables.
 */
class MpvHandle(private val mpv: MPVLib) : AltEngine {
    override val isPlaying get() = mpv.getPropertyBoolean("pause") == false && mpv.getPropertyBoolean("idle-active") != true
    override fun togglePause() { mpv.setPropertyBoolean("pause", !(mpv.getPropertyBoolean("pause") ?: false)) }
    override fun seekBy(ms: Long) { mpv.command(arrayOf("seek", (ms / 1000.0).toString(), "relative")) }
    override fun pause() { mpv.setPropertyBoolean("pause", true) }
    override fun resume() { mpv.setPropertyBoolean("pause", false) }
    override fun audioTracks(): List<Pair<Int, String>> {
        val n = mpv.getPropertyInt("track-list/count") ?: return emptyList()
        return (0 until n).mapNotNull { i ->
            if (mpv.getPropertyString("track-list/$i/type") != "audio") return@mapNotNull null
            val id = mpv.getPropertyInt("track-list/$i/id") ?: return@mapNotNull null
            val name = listOfNotNull(mpv.getPropertyString("track-list/$i/lang"), mpv.getPropertyString("track-list/$i/title"),
                mpv.getPropertyString("track-list/$i/codec")).joinToString(" · ").ifBlank { "Audio $id" }
            id to name
        }
    }
    override var audioTrack: Int
        get() = mpv.getPropertyInt("aid") ?: 0
        set(v) { mpv.setPropertyInt("aid", v) }
}

private fun applyAspect(mpv: MPVLib, aspect: String) {
    when (aspect) {
        "fill", "zoom" -> { mpv.setPropertyString("keepaspect", "yes"); mpv.setPropertyDouble("panscan", 1.0) }
        "stretch" -> { mpv.setPropertyString("keepaspect", "no"); mpv.setPropertyDouble("panscan", 0.0) }
        else -> { mpv.setPropertyString("keepaspect", "yes"); mpv.setPropertyDouble("panscan", 0.0) }
    }
}

@Composable
fun MpvVideo(
    url: String,
    userAgent: String,
    referrer: String,
    aspect: String,
    modifier: Modifier = Modifier,
    onReady: (MpvHandle) -> Unit,
    onPlaying: () -> Unit,
    onError: (String) -> Unit,
    onEnded: () -> Unit,
) {
    val ctx = LocalContext.current
    val cbPlaying by rememberUpdatedState(onPlaying)
    val cbError by rememberUpdatedState(onError)
    val cbEnded by rememberUpdatedState(onEnded)

    // libmpv necesita Android 8.0 o más nuevo
    if (android.os.Build.VERSION.SDK_INT < 26) {
        LaunchedEffect(Unit) { cbError("MPV no está disponible en este equipo") }
        return
    }
    // Crear e iniciar libmpv una vez por pantalla
    val mpv = remember {
        runCatching { MPVLib.create(ctx.applicationContext) }.getOrNull()?.apply {
            setOptionString("config", "no")
            setOptionString("vo", "gpu")
            setOptionString("gpu-context", "android")
            setOptionString("opengl-es", "yes")
            setOptionString("hwdec", "auto-safe")                     // decodificación por hardware si se puede; si no, software
            setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
            setOptionString("ao", "audiotrack,opensles")
            setOptionString("deinterlace", "auto")                    // TV de cable entrelazada
            setOptionString("cache", "yes")
            setOptionString("cache-secs", "20")
            setOptionString("demuxer-max-bytes", "64MiB")
            setOptionString("network-timeout", "15")
            setOptionString("tls-verify", "no")
            setOptionString("user-agent", userAgent.ifBlank { Http.DEFAULT_UA })
            if (referrer.isNotBlank()) setOptionString("referrer", referrer)
            setOptionString("stream-lavf-o", "reconnect=1,reconnect_streamed=1,reconnect_delay_max=5")
            setOptionString("input-default-bindings", "no")
            setOptionString("keep-open", "no")
            setOptionString("force-window", "no")
            setOptionString("idle", "yes")
            init()
            observeProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
            observeProperty("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        }
    }
    if (mpv == null) {
        LaunchedEffect(Unit) { cbError("MPV no está disponible en este equipo") }
        return
    }
    val handle = remember { MpvHandle(mpv) }

    // Eventos de libmpv (llegan en otro hilo: se pasan al hilo principal)
    DisposableEffect(mpv) {
        val main = Handler(Looper.getMainLooper())
        var started = false
        val obs = object : MPVLib.EventObserver {
            override fun eventProperty(property: String) {}
            override fun eventProperty(property: String, value: Long) {}
            override fun eventProperty(property: String, value: Boolean) {}
            override fun eventProperty(property: String, value: String) {}
            override fun eventProperty(property: String, value: Double) {
                if (property == "time-pos" && value > 0 && !started) { started = true; main.post { cbPlaying() } }
            }
            override fun event(eventId: Int) {
                when (eventId) {
                    MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> { if (!started) { started = true; main.post { cbPlaying() } } }
                    MPVLib.MpvEvent.MPV_EVENT_END_FILE -> main.post {
                        if (started) cbEnded() else cbError("MPV no pudo abrir el canal")
                    }
                    MPVLib.MpvEvent.MPV_EVENT_START_FILE -> started = false
                }
            }
        }
        mpv.addObserver(obs)
        onDispose {
            mpv.removeObserver(obs)
            runCatching { mpv.command(arrayOf("stop")) }
            runCatching { mpv.destroy() }
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(h: SurfaceHolder) {
                        mpv.attachSurface(h.surface)
                        mpv.setOptionString("force-window", "yes")
                        mpv.setPropertyString("vo", "gpu")
                    }
                    override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, hgt: Int) {
                        mpv.setPropertyString("android-surface-size", "${w}x$hgt")
                    }
                    override fun surfaceDestroyed(h: SurfaceHolder) {
                        mpv.setPropertyString("vo", "null")
                        mpv.setOptionString("force-window", "no")
                        mpv.detachSurface()
                    }
                })
            }
        },
    )

    LaunchedEffect(aspect) { runCatching { applyAspect(mpv, aspect) } }
    LaunchedEffect(url) {
        mpv.command(arrayOf("loadfile", url))
        onReady(handle)
    }
}
