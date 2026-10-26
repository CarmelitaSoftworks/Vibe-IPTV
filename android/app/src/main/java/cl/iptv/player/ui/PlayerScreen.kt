package cl.iptv.player.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import cl.iptv.player.MainActivity
import cl.iptv.player.data.Http
import cl.iptv.player.data.Item
import cl.iptv.player.data.Kind
import cl.iptv.player.data.OpenSubtitles
import cl.iptv.player.data.SubtitleSources
import cl.iptv.player.data.cleanTitle
import cl.iptv.player.ui.theme.LocalIptv
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

@OptIn(UnstableApi::class)
/**
 * En directo, una descarga que se cae se reintenta enseguida y muchas veces (antes eran pocas y luego se recargaba el
 * canal entero, perdiendo lo ya guardado). Si el canal de verdad murió, los vigilantes de arranque y de imagen
 * detenida actúan igual.
 */
private object LiveRetryPolicy : androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy() {
    override fun getMinimumLoadableRetryCount(dataType: Int) = 30
    override fun getRetryDelayMsFor(info: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        if (super.getRetryDelayMsFor(info) == C.TIME_UNSET) return C.TIME_UNSET   // errores que no sirve reintentar
        return cl.iptv.player.data.LiveCushion.retryDelayMs(info.errorCount)
    }
}

private fun mediaItemFor(item: Item, subtitle: File?): MediaItem {
    val path = item.url.substringBefore('?').lowercase()
    val b = MediaItem.Builder()
        .setUri(item.url)
        .setMediaId(item.id)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(item.name).build())
    if (path.endsWith(".m3u8") || path.endsWith(".m3u")) b.setMimeType(MimeTypes.APPLICATION_M3U8)
    if (item.kind == Kind.LIVE) b.setLiveConfiguration(
        MediaItem.LiveConfiguration.Builder().setMinPlaybackSpeed(1f).setMaxPlaybackSpeed(1f).setTargetOffsetMs(10_000).build()
    )
    if (subtitle != null) {
        val mime = when {
            subtitle.name.endsWith(".vtt", true) -> MimeTypes.TEXT_VTT
            subtitle.name.endsWith(".ass", true) || subtitle.name.endsWith(".ssa", true) -> MimeTypes.TEXT_SSA
            else -> MimeTypes.APPLICATION_SUBRIP
        }
        b.setSubtitleConfigurations(
            listOf(
                MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(subtitle))
                    .setMimeType(mime).setLanguage("es").setLabel(subtitle.nameWithoutExtension)
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT or C.SELECTION_FLAG_FORCED)
                    .build()
            )
        )
    }
    return b.build()
}

private fun errorText(e: PlaybackException): String = when (e.errorCode) {
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "El servidor rechazó la conexión (¿cuenta vencida o límite de conexiones?)"
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Sin conexión con el servidor"
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "Formato no soportado por este dispositivo. Prueba \"Abrir en VLC\"."
    else -> e.errorCodeName.removePrefix("ERROR_CODE_").replace('_', ' ').lowercase()
}

/** En Xtream el mismo canal existe como .m3u8 y como .ts. */
private fun altFormat(url: String): String? {
    val m = Regex("^(.*/live/[^/]+/[^/]+/\\d+)\\.(m3u8|ts)(\\?.*)?$", RegexOption.IGNORE_CASE).find(url) ?: return null
    val (base, ext, q) = m.destructured
    return "$base.${if (ext.equals("m3u8", true)) "ts" else "m3u8"}$q"
}

private fun subtitleScale(size: String) = when (size) { "small" -> 0.8f; "large" -> 1.3f; "xlarge" -> 1.6f; else -> 1f }

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val activity = ctx as MainActivity
    val c = LocalIptv.current
    val item = vm.playing ?: return

    var bannerUntil by remember { mutableStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    var subtitle by remember(item.id) { mutableStateOf<File?>(null) }
    // Sincronizar subtítulos (ms, + = aparecen después). En ExoPlayer con los subtítulos del canal se muestran con
    // retraso desde aquí (cueQueue → delayedCues); con un archivo se corre el archivo; VLC y MPV lo hacen ellos.
    var subDelay by remember(item.id) { mutableStateOf(0L) }
    val cueQueue = remember { java.util.ArrayDeque<Pair<Long, List<androidx.media3.common.text.Cue>>>() }
    var delayedCues by remember { mutableStateOf<List<androidx.media3.common.text.Cue>>(emptyList()) }
    // Formato alternativo (.m3u8 ↔ .ts) si el primero falla: id del elemento que lo está usando
    var altFor by remember { mutableStateOf<String?>(null) }
    val playUrl = if (altFor == item.id) altFormat(item.url) ?: item.url else item.url
    val currentItem by rememberUpdatedState(item)
    // Motor: "exo" (ExoPlayer), "vlc", "mpv" o "system" (el del sistema). engineFor = (id del elemento, motor) cuando se
    // cambió para este canal. En automático cada canal parte con ExoPlayer; VLC, MPV y el del sistema sólo si ese canal falla.
    var engineFor by remember { mutableStateOf<Pair<String, String>?>(null) }
    val defaultEngine = when (vm.settings.engine) {
        "vlc", "mpv", "exo", "system" -> vm.settings.engine
        else -> "exo"
    }
    val engine = engineFor?.takeIf { it.first == item.id }?.second ?: defaultEngine
    val useAlt = engine != "exo"          // motor alternativo (VLC, MPV o el del sistema) en vez de ExoPlayer
    var alt by remember { mutableStateOf<AltEngine?>(null) }
    var reloadKey by remember { mutableStateOf(0) }
    var liveRetries by remember { mutableStateOf(0) }
    // Diagnóstico (Más → Información técnica): cortes y reconexiones del canal actual y velocidad del colchón
    var stalls by remember(item.id) { mutableStateOf(0) }
    var reconnects by remember(item.id) { mutableStateOf(0) }
    var cushionSpeed by remember { mutableStateOf(1f) }
    var videoDecoder by remember { mutableStateOf("") }
    // true cuando el motor actual ya mostró imagen o sonido (lo usa el vigilante de arranque)
    var started by remember { mutableStateOf(false) }
    var userPaused by remember(item.id) { mutableStateOf(false) }
    fun switchEngine(to: String, reason: String) {
        val it0 = currentItem
        if (engineFor?.first == it0.id && engineFor?.second == to) return
        engineFor = it0.id to to
        vm.logEvent("Cambio de motor → $to (${it0.name}): $reason")
        vm.message = reason
    }
    val engineName = mapOf("exo" to "ExoPlayer", "vlc" to "VLC", "mpv" to "MPV", "system" to tr("Motor del sistema"))
    var showSubs by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf<PAction?>(null) }
    val live = item.kind == Kind.LIVE

    // Controles en pantalla: se muestran al tocar o con cualquier tecla y se ocultan solos
    var controls by remember { mutableStateOf(true) }
    var lastTouch by remember { mutableStateOf(System.currentTimeMillis()) }
    val playFocus = remember { FocusRequester() }
    fun poke() { lastTouch = System.currentTimeMillis(); if (!controls) controls = true }
    LaunchedEffect(lastTouch, panel, controls, error) {
        if (controls && panel == null && error == null) { delay(5000); controls = false }
    }
    LaunchedEffect(controls, panel) {
        if (controls && panel == null) { delay(80); runCatching { playFocus.requestFocus() } }
    }

    // Búfer según Ajustes → Reproducción, más lo aprendido para este canal (sube solo si se corta seguido)
    // El nivel se fija al abrir el canal: si se sube por cortes, no se reinicia el reproductor (eso cortaba la imagen
    // justo cuando el canal ya andaba mal); el colchón en vivo sí usa el nivel nuevo de inmediato
    val bufLevel = remember(item.id) { vm.bufferLevel(item) }
    val memMb = remember { (ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager).memoryClass }
    val buf = cl.iptv.player.data.BufferProfile.of(bufLevel, live, memMb)
    val rebuffers = remember { mutableStateMapOf<String, Int>() }      // veces que cada canal se quedó cargando
    fun countRebuffer(it0: Item) {
        stalls++
        val n = (rebuffers[it0.id] ?: 0) + 1
        rebuffers[it0.id] = n
        if (n >= 3 && it0.kind == Kind.LIVE) {
            rebuffers[it0.id] = 0
            vm.bumpBuffer(it0)
        }
    }
    val aiOn = vm.aiSubs != null
    // El reproductor se arma una sola vez (no al cambiar de canal aunque cambie el nivel de búfer: rearmarlo sumaba una
    // pausa al cambiar de canal); el colchón en vivo usa el nivel de cada canal
    // Modo túnel sólo con ExoPlayer y sin subtítulos IA (necesitan el audio decodificado en la app)
    val tunnel = vm.settings.tunneling && !aiOn
    // Ecualizador y volumen parejo de ExoPlayer: uno por reproductor (dos reproductores no pueden compartirlo)
    val soundProc = remember(live, aiOn, tunnel) { cl.iptv.player.data.SoundProcessor() }
    // Sincronizar audio en ExoPlayer: el video se dibuja con la hora corrida (el audio manda el reloj), así el sonido
    // queda antes o después de la imagen. Lo lee el hilo del reproductor.
    val videoShift = remember { java.util.concurrent.atomic.AtomicLong(0L) }
    val player = remember(live, aiOn, tunnel) {
        // Búfer medido en segundos (no en megas: con canales 1080p pesados el límite en megas lo dejaba en pocos
        // segundos) y velocidad fija en directo: evita cortes y saltos hacia adelante
        val load = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(buf.minMs, buf.maxMs, buf.startMs, buf.rebufferMs)
            .setTargetBufferBytes(buf.targetBytes)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val liveSpeed = androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl.Builder()
            .setFallbackMinPlaybackSpeed(1f).setFallbackMaxPlaybackSpeed(1f)
            .build()
        // Decodificador FFmpeg incluido: audio AC-3 / E-AC-3 / DTS aunque el equipo no lo traiga
        // El audio pasa por el generador de subtítulos IA (sólo lo usa si están activados).
        // Con los subtítulos IA se evita el paso directo de AC-3 al equipo de sonido, para tener el audio decodificado.
        val renderers = object : androidx.media3.exoplayer.DefaultRenderersFactory(ctx) {
            override fun buildAudioSink(context: android.content.Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): androidx.media3.exoplayer.audio.AudioSink =
                androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .apply { @Suppress("DEPRECATION") setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams) }
                    .setAudioProcessors(arrayOf(androidx.media3.exoplayer.audio.TeeAudioProcessor(vm.captioner.sink), soundProc))
                    .apply { if (aiOn) @Suppress("DEPRECATION") setAudioCapabilities(androidx.media3.exoplayer.audio.AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES) }
                    .build()

            // Video con FFmpeg (NextLib) después de los decodificadores del equipo: sólo se usa si el equipo no puede
            // con el video (MPEG-2 de la TV de cable y otros), y así no hace falta pasar a VLC
            override fun buildVideoRenderers(context: android.content.Context, mode: Int, selector: androidx.media3.exoplayer.mediacodec.MediaCodecSelector,
                                             fallback: Boolean, handler: android.os.Handler, listener: androidx.media3.exoplayer.video.VideoRendererEventListener,
                                             joiningMs: Long, out: java.util.ArrayList<androidx.media3.exoplayer.Renderer>) {
                super.buildVideoRenderers(context, mode, selector, fallback, handler, listener, joiningMs, out)
                // El de video del equipo, con la hora corrida según "Sincronizar audio"
                val i = out.indexOfFirst { it.javaClass == androidx.media3.exoplayer.video.MediaCodecVideoRenderer::class.java }
                if (i >= 0) out[i] = object : androidx.media3.exoplayer.video.MediaCodecVideoRenderer(
                    context, codecAdapterFactory, selector, joiningMs, fallback, handler, listener, 50,
                ) {
                    override fun render(positionUs: Long, elapsedRealtimeUs: Long) = super.render(positionUs + videoShift.get(), elapsedRealtimeUs)
                }
                runCatching {
                    if (io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegLibrary.isAvailable())
                        out.add(io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegVideoRenderer(joiningMs, handler, listener, 50))
                }.onFailure { vm.logEvent("FFmpeg de video no disponible: ${it.message}") }
            }
        }
            .setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            // Siempre primero el decodificador del chip; el de software (y luego FFmpeg) sólo si el del chip no puede
            .setMediaCodecSelector { mime, secure, tunneling ->
                val all = androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getDecoderInfos(mime, secure, tunneling)
                cl.iptv.player.data.Decoders.hardwareFirst(all) { it.hardwareAccelerated && !it.softwareOnly && !cl.iptv.player.data.Decoders.isSoftwareName(it.name) }
            }
            .setEnableDecoderFallback(true)
        val selector = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(ctx).apply {
            if (tunnel) setParameters(buildUponParameters().setTunnelingEnabled(true))
        }
        ExoPlayer.Builder(ctx, renderers).setTrackSelector(selector).setLoadControl(load).setLivePlaybackSpeedControl(liveSpeed)
            .setBandwidthMeter(vm.bandwidth).build().apply {
            playWhenReady = true
            setHandleAudioBecomingNoisy(true)
        }
    }
    val exo = remember(player) { ExoControl(player) }

    // Pausar y retroceder en vivo (Ajustes → Reproducción): con ExoPlayer y canales .ts, una conexión guarda el canal
    // en el equipo y el reproductor lo lee desde ahí (ver data/Timeshift.kt)
    val tsOn = live && engine == "exo" && vm.settings.timeshiftMin > 0 && !item.id.startsWith("cu_") &&
        playUrl.substringBefore('?').endsWith(".ts", true)
    val tsRoot = remember { File(ctx.cacheDir, "pausa-en-vivo").apply { deleteRecursively() } }      // restos de otra vez
    val tsSession = remember(item.id, playUrl, tsOn) {
        if (!tsOn) return@remember null
        val minutes = vm.settings.timeshiftMin
        // Tope de espacio: unos 1,6 MB por segundo (canales 1080p pesados) y siempre dejando 400 MB libres
        val free = tsRoot.apply { mkdirs() }.usableSpace - 400L * 1024 * 1024
        val maxBytes = minOf(minutes * 60L * 1_600_000L, free)
        if (maxBytes < 150L * 1024 * 1024) { vm.logEvent("Pausa en vivo: poco espacio libre (${free / 1_048_576} MB)"); return@remember null }
        cl.iptv.player.data.TimeshiftSession(
            cl.iptv.player.data.TimeshiftBuffer(File(tsRoot, System.nanoTime().toString()), minutes * 60_000L, maxBytes),
            item.copy(url = playUrl),
        ).also { it.start() }
    }
    DisposableEffect(tsSession) { onDispose { tsSession?.close() } }
    // Dónde empezó a leer el reproductor (hora de llegada de esa parte), salto pedido y retraso normal del directo
    var tsStartAt by remember(tsSession) { mutableStateOf(0L) }
    var tsJump by remember(tsSession) { mutableStateOf<Long?>(null) }
    var tsJumpKey by remember(tsSession) { mutableStateOf(0) }
    var tsLag by remember(tsSession) { mutableStateOf<Long?>(null) }
    /** Hora (de llegada) de lo que se ve ahora. */
    fun tsShowing() = tsStartAt + player.currentPosition
    /** Cuánto va atrás del directo (0 = en directo). */
    fun tsBehind(): Long {
        val ts = tsSession ?: return 0
        if (tsStartAt == 0L) return 0
        val gap = ts.buffer.latestAt - tsShowing()
        return (gap - (tsLag ?: gap)).coerceAtLeast(0)
    }
    // Varias pulsaciones seguidas de ◄ se suman: se salta medio segundo después de la última
    LaunchedEffect(tsJump) { if (tsJump != null) { delay(500); tsJumpKey++ } }
    // Retraso normal del directo: se mide unos segundos después de empezar (o de volver al directo)
    LaunchedEffect(tsSession, tsStartAt, tsLag) {
        if (tsSession == null || tsLag != null || tsStartAt == 0L) return@LaunchedEffect
        while (!player.isPlaying) delay(500)
        delay(4000)
        tsLag = tsSession.buffer.latestAt - tsShowing()
    }
    val bandwidth = vm.bandwidth
    // Calidad en canales con varias calidades (Ajustes → Reproducción): automática según el internet, hasta 720p
    // para ahorrar datos, o siempre la mejor
    LaunchedEffect(vm.settings.hlsQuality, player) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
            when (vm.settings.hlsQuality) {
                "saver" -> { setMaxVideoSize(1280, 720); setForceHighestSupportedBitrate(false) }
                "max" -> { clearVideoSizeConstraints(); setForceHighestSupportedBitrate(true) }
                else -> { clearVideoSizeConstraints(); setForceHighestSupportedBitrate(false) }
            }
        }.build()
    }
    var infoTick by remember { mutableStateOf(0) }
    LaunchedEffect(panel) { while (panel == PAction.INFO) { delay(1000); infoTick++ } }
    val ctl: PlaybackControl? = if (useAlt) alt else exo
    /** Retroceder o adelantar en vivo (con la pausa en vivo). */
    fun tsSeek(delta: Long) {
        val ts = tsSession ?: return
        val lag = tsLag ?: 2_000
        var target = (tsJump ?: tsShowing()) + delta
        val earliest = ts.buffer.earliestAt + 1_000
        if (target < earliest) { target = earliest; vm.message = "Es lo más antiguo guardado" }
        if (target >= ts.buffer.latestAt - lag - 1_000) { tsJump = Long.MAX_VALUE; return }     // llegó al directo
        tsJump = target
    }

    // Cargar el elemento actual (o recargar al añadir subtítulos conservando la posición)
    LaunchedEffect(item.id, subtitle, playUrl, engine, reloadKey, player, tsSession, tsJumpKey) {
        error = null
        if (useAlt) { player.stop(); player.clearMediaItems(); bannerUntil = System.currentTimeMillis() + 4500; return@LaunchedEffect }
        val ts = tsSession
        if (ts != null) {
            val b = ts.buffer
            val jump = tsJump
            val from = when {
                // Volver al directo: un par de segundos antes de lo último que llegó
                jump == Long.MAX_VALUE -> { tsLag = null; b.offsetAt(b.latestAt - 2_500) }
                jump != null -> b.offsetAt(jump)
                // Recarga (la imagen se detuvo): seguir desde lo que se estaba viendo, con la descarga reconectada
                tsStartAt > 0 && player.currentMediaItem?.mediaId == item.id -> { ts.reconnect(); b.offsetAt(tsShowing()) }
                b.written > 0 -> b.offsetAt(b.latestAt - 2_500)
                else -> 0L
            }
            tsJump = null
            tsStartAt = if (b.written > 0) b.timeAt(from) else System.currentTimeMillis()
            val factory = DefaultMediaSourceFactory(androidx.media3.datasource.DataSource.Factory { cl.iptv.player.data.TimeshiftDataSource(b) })
            val mi = mediaItemFor(item.copy(url = "timeshift://canal?from=$from"), null).buildUpon().setMimeType(MimeTypes.VIDEO_MP2T).build()
            player.setMediaSource(factory.createMediaSource(mi))
            player.prepare(); player.play(); userPaused = false
            if (jump == null) bannerUntil = System.currentTimeMillis() + 4500
            return@LaunchedEffect
        }
        val saved = if (item.kind != Kind.LIVE) vm.resumeFor(item) else null
        val pos = if (subtitle != null && player.currentMediaItem?.mediaId == item.id) player.currentPosition
            else if (saved != null && player.currentMediaItem?.mediaId != item.id) { vm.message = "Continuando desde ${fmtDur(saved.pos)}"; saved.pos }
            else C.TIME_UNSET
        val http = OkHttpDataSource.Factory(Http.streamClient)
            .setUserAgent(item.userAgent.ifBlank { Http.DEFAULT_UA })
            .apply { if (item.referrer.isNotBlank()) setDefaultRequestProperties(mapOf("Referer" to item.referrer)) }
        val factory = DefaultMediaSourceFactory(DefaultDataSource.Factory(ctx, http))
            .apply { if (item.kind == Kind.LIVE) setLoadErrorHandlingPolicy(LiveRetryPolicy) }
        val source = factory.createMediaSource(mediaItemFor(item.copy(url = playUrl), subtitle))
        if (pos != C.TIME_UNSET) player.setMediaSource(source, pos) else player.setMediaSource(source)
        player.prepare()
        player.play()
        bannerUntil = System.currentTimeMillis() + 4500
    }
    // Vigilante de arranque: algunos canales aceptan la conexión pero nunca envían video y el motor
    // se queda esperando con la pantalla negra. Si no arranca a tiempo, se prueba el otro formato,
    // luego otro motor y, si nada funciona, se muestra el error con opciones.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(item.id, playUrl, engine, reloadKey) {
        started = false
        delay(if (item.kind == Kind.LIVE) 20_000L else 30_000L)
        if (started || error != null) return@LaunchedEffect
        if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@LaunchedEffect
        val it0 = currentItem
        val auto = vm.settings.engine == "auto"
        when {
            engine == "exo" && altFormat(it0.url) != null && altFor != it0.id -> {
                vm.message = "El canal no responde: probando otro formato…"
                altFor = it0.id
            }
            auto && nextEngine(engine) != null -> nextEngine(engine)!!.let { n -> switchEngine(n, "El canal no responde: probando con ${engineName[n]}…") }
            else -> {
                if (engine == "exo") player.stop()     // soltar la conexión colgada
                else alt?.pause()
                error = "El canal no responde (tiempo de espera agotado). Puede estar caído o saturado."
            }
        }
    }

    // Seguir viendo: VLC / MPV continúan donde quedó (una vez por elemento)
    var resumedFor by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(alt, item.id) {
        val a = alt ?: return@LaunchedEffect
        val saved = if (item.kind != Kind.LIVE) vm.resumeFor(item) else null
        if (saved != null && resumedFor != item.id) {
            resumedFor = item.id
            for (i in 0 until 20) { if (a.duration > 0) break; delay(500) }
            a.seekTo(saved.pos); vm.message = "Continuando desde ${fmtDur(saved.pos)}"
        }
    }
    // Guardar dónde va cada 10 s
    LaunchedEffect(item.id, engine, player) {
        if (live) return@LaunchedEffect
        while (true) {
            delay(10_000)
            val k = if (useAlt) alt else exo
            if (k != null && k.isPlaying) vm.savePosition(item, k.position, k.duration)
        }
    }

    // Vigilante de congelamiento: el canal ya andaba pero la imagen se detuvo (sin error).
    // 1.º recarga, 2.º otro motor (en automático) y si no queda otro, avisa con "Reintentar".
    // Imagen en imagen del sistema al salir de la app: sólo mientras algo se reproduce (no si se dejó en pausa)
    LaunchedEffect(userPaused) { activity.pipEnabled = !userPaused }
    // Gesto de brillo / volumen en curso (para el indicador)
    var gesture by remember { mutableStateOf<Pair<String, Float>?>(null) }
    var seekFlash by remember { mutableStateOf(0 to 0L) }        // doble toque: -1 / +1 y cuándo
    var digits by remember { mutableStateOf("") }                // teclas numéricas del control
    LaunchedEffect(digits) {
        if (digits.isNotEmpty()) { delay(1600); digits.toIntOrNull()?.let { vm.jumpToNumber(it) }; digits = "" }
    }
    // Temporizador para dormir: avisa un minuto antes y cierra el reproductor
    LaunchedEffect(vm.sleepAt) {
        val at = vm.sleepAt
        if (at <= 0L) return@LaunchedEffect
        val warn = at - 60_000 - System.currentTimeMillis()
        if (warn > 0) { delay(warn); vm.message = "El reproductor se apagará en 1 minuto" }
        delay((at - System.currentTimeMillis()).coerceAtLeast(0))
        vm.setSleep(0); vm.closePlayer()
    }
    // Cambio de canal más rápido: resolver ya la dirección del servidor de los canales vecinos (sólo DNS, sin conectar)
    LaunchedEffect(item.id) {
        val hosts = listOf(-1, 1).mapNotNull { vm.playList.getOrNull((vm.playIndex + it).mod(vm.playList.size.coerceAtLeast(1))) }
            .mapNotNull { runCatching { Uri.parse(it.url).host }.getOrNull() }.plus(Uri.parse(item.url).host).filterNotNull().distinct()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { hosts.forEach { h -> runCatching { cl.iptv.player.data.Http.dns.lookup(h) } } }
    }
    LaunchedEffect(item.id) { vm.logEvent("Reproducir: ${item.name} [${item.kind}] ${item.url}") }
    // Guía del canal: si no sale en el XMLTV, se pide al proveedor (Xtream)
    LaunchedEffect(item.id) { if (live) vm.ensureGuide(item) }
    // Colchón en vivo (Ajustes → Reproducción): si queda poco video guardado, se reproduce un 2–4 % más lento hasta
    // rehacerlo (ExoPlayer y MPV, que informan cuánto tienen guardado)
    LaunchedEffect(item.id, engine, player, alt, vm.settings.liveCushion) {
        val c = cl.iptv.player.data.LiveCushion()
        cushionSpeed = 1f
        if (!useAlt) player.playbackParameters = androidx.media3.common.PlaybackParameters.DEFAULT
        if (!live || !vm.settings.liveCushion) return@LaunchedEffect
        while (true) {
            delay(1000)
            val k = ctlOf(useAlt, alt, exo) ?: continue
            val ahead = k.bufferedAheadMs ?: continue                 // VLC no informa su reserva
            if (!k.isPlaying || userPaused) continue
            if (k.speed !in listOf(1f, cl.iptv.player.data.LiveCushion.SLOW, cl.iptv.player.data.LiveCushion.SLOWER)) continue   // velocidad elegida a mano
            val before = c.speed
            val s = c.update(ahead, cl.iptv.player.data.LiveCushion.targetMs(vm.bufferLevel(item)))
            if (s != before) {
                k.speed = s
                cushionSpeed = s
                vm.logEvent("Colchón en vivo: ${ahead / 1000} s guardados → velocidad ${"%.2f".format(s)}")
            }
        }
    }
    var gestureAt by remember { mutableStateOf(0L) }
    LaunchedEffect(item.id, engine, player) {
        val d = cl.iptv.player.data.StallDetector(firstStartMs = Long.MAX_VALUE / 4)   // el arranque lo vigila el bloque anterior
        d.reset(System.currentTimeMillis())
        while (true) {
            delay(2000)
            if (error != null) continue
            if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) { d.reset(System.currentTimeMillis()); continue }
            val k = if (useAlt) alt else exo
            if (k == null) continue
            val next = nextEngine(engine)
            val canSwitch = vm.settings.engine == "auto" && next != null
            when (d.check(System.currentTimeMillis(), k.position, !userPaused, canSwitch)) {
                cl.iptv.player.data.StallDetector.Action.RELOAD -> { vm.message = "La imagen se detuvo: reconectando…"; countRebuffer(item); reloadKey++ }
                cl.iptv.player.data.StallDetector.Action.SWITCH_ENGINE -> switchEngine(next!!, "Se volvió a detener: probando con ${engineName[next]}…")
                cl.iptv.player.data.StallDetector.Action.GIVE_UP -> {
                    if (engine == "exo") player.stop() else alt?.pause()
                    error = "La transmisión se detuvo y no se recuperó. Puede ser un corte del proveedor."
                }
                else -> Unit
            }
        }
    }

    // Sonido (Ajustes → Sonido o Más → Sonido): ecualizador y volumen parejo en el motor que esté sonando
    LaunchedEffect(vm.settings.soundEq, vm.settings.soundLevel, alt, engine, soundProc) {
        soundProc.preset = vm.settings.soundEq; soundProc.level = vm.settings.soundLevel
        if (useAlt) { delay(300); runCatching { alt?.setSound(vm.settings.soundEq, vm.settings.soundLevel) } }
    }

    // Sincronizar audio (Más → Sincronizar audio): igual para todos los canales (depende del parlante, no del canal)
    LaunchedEffect(vm.settings.audioDelayMs, alt, engine) {
        videoShift.set(vm.settings.audioDelayMs * 1000L)
        if (useAlt) { delay(300); runCatching { alt?.setAudioDelay(vm.settings.audioDelayMs.toLong()) } }
    }

    // Subtítulos IA: necesitan ExoPlayer (es el motor que entrega el audio decodificado); se apagan al salir
    LaunchedEffect(vm.aiSubs, engine) {
        if (vm.aiSubs != null && engine != "exo") { engineFor = item.id to "exo"; vm.message = "Subtítulos IA: usando el motor ExoPlayer" }
    }
    DisposableEffect(Unit) { onDispose { vm.stopAiSubs() } }
    // Si quedaron activados la última vez, se vuelven a encender solos (el modelo se carga mientras abre el canal)
    LaunchedEffect(Unit) { vm.settings.aiSubsLang.takeIf { it.isNotEmpty() && vm.aiSubs == null }?.let { vm.startAiSubs(it) } }

    // Subtítulos automáticos: si el audio no está en el idioma preferido, usar la pista interna en ese idioma
    // o buscar en las fuentes sin clave (OpenSubtitles vía Stremio y Podnapisi; SubDL si hay clave) y cargar el mejor
    var autoSubsDone by remember(item.id) { mutableStateOf(false) }
    LaunchedEffect(item.id, started, engine) {
        if (live || !started || autoSubsDone || !vm.settings.autoSubs || subtitle != null || item.id.startsWith("cu_")) return@LaunchedEffect
        delay(2500)                                            // dar tiempo a que el motor publique sus pistas
        autoSubsDone = true
        val want = vm.settings.subLangs.lowercase().split(Regex("[\\s,]+")).firstOrNull { it.isNotBlank() } ?: "es"
        fun isLang(label: String): Boolean {
            val l = label.lowercase()
            val names = listOf(localeOf(want).getDisplayLanguage(localeOf("es")), localeOf(want).getDisplayLanguage(java.util.Locale.ENGLISH))
                .map { it.lowercase() }.filter { it.isNotBlank() } + (if (want == "es") listOf("latino", "castellano", "spa") else emptyList())
            return names.any { it in l } || Regex("""(^|[^a-z])$want([^a-z]|$)""").containsMatchIn(l)
        }
        val k = ctlOf(useAlt, alt, exo) ?: return@LaunchedEffect
        val audio = k.audioTracks()
        if (audio.isNotEmpty() && audio.firstOrNull { it.selected }?.let { isLang(it.label) } == true) return@LaunchedEffect   // ya se oye en tu idioma
        k.subtitleTracks().firstOrNull { isLang(it.label) }?.let { t ->
            if (!t.selected) { k.selectSubtitle(t.id); vm.message = "Subtítulos: ${t.label}" }
            return@LaunchedEffect
        }
        val year = Regex("\\b(19[3-9]\\d|20[0-4]\\d)\\b").find(item.name)?.value.orEmpty()
        val q = SubtitleSources.Query(cleanTitle(item.seriesName.ifBlank { item.name }), item.seriesName.isNotBlank() || item.season > 0, item.season, item.episode, year)
        val found = runCatching { vm.subtitleSources.search(q) }.getOrNull() ?: return@LaunchedEffect
        val best = found.results.firstOrNull { it.lang == want } ?: run { vm.logEvent("Subtítulos automáticos: sin resultados en '$want' para ${q.title}"); return@LaunchedEffect }
        if (vm.playing?.id != item.id) return@LaunchedEffect
        runCatching { vm.subtitleSources.download(best, vm.subtitleDir()) }
            .onSuccess { f -> if (vm.playing?.id == item.id && subtitle == null) { subtitle = f; vm.message = "Subtítulo cargado solo (${best.source}) · cámbialo en Subtítulos" } }
            .onFailure { vm.logEvent("Subtítulos automáticos: ${best.source}: ${it.message}") }
    }

    // Subtítulo externo en VLC / MPV
    LaunchedEffect(subtitle, alt) { val f = subtitle; if (useAlt && f != null) runCatching { alt?.addExternalSubtitle(f) } }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition(); player.prepare(); return
                }
                val it0 = currentItem
                // Corte de red en directo: reintentar unas veces antes de cambiar de formato o de motor
                val network = e.errorCode in PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED..PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE
                if (it0.kind == Kind.LIVE && network && liveRetries < 3 && player.currentPosition > 0) {
                    liveRetries++
                    vm.viewModelScope.launch { delay(1000L * liveRetries); player.prepare(); player.play() }
                    return
                }
                if (altFormat(it0.url) != null && altFor != it0.id) { altFor = it0.id; return }   // probar el otro formato del canal
                if (vm.settings.engine == "auto") { switchEngine("vlc", "Probando con el motor VLC…"); return }
                error = errorText(e)
            }
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                // Sin decodificador para el video (MPEG-2, etc.) ni con FFmpeg: pasar a VLC
                if (vm.settings.engine != "auto") return
                val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                val video = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
                val badAudio = audio.isNotEmpty() && audio.none { it.isSupported }
                val badVideo = video.isNotEmpty() && video.none { it.isSupported }
                if (badAudio || badVideo) switchEngine("vlc", if (badVideo) "Video no compatible con este equipo: usando VLC" else "Audio no compatible con este equipo: usando VLC")
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { if (isPlaying) { started = true; error = null; liveRetries = 0 } }
            override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
                synchronized(cueQueue) { cueQueue.addLast(android.os.SystemClock.uptimeMillis() to cueGroup.cues); while (cueQueue.size > 200) cueQueue.removeFirst() }
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) started = true
                // Se quedó cargando en pleno directo (no al arrancar): cuenta para el búfer aprendido
                if (state == Player.STATE_BUFFERING && started && player.playWhenReady) countRebuffer(currentItem)
                // Siguiente episodio automático (Ajustes → Rápido)
                val cur = vm.playing
                if (state == Player.STATE_ENDED && vm.settings.autoNext && cur != null && cur.episode > 0 &&
                    vm.playIndex < vm.playList.size - 1) {
                    vm.message = "Siguiente: ${vm.playList[vm.playIndex + 1].name}"
                    vm.zap(1)
                }
            }
        }
        player.addListener(listener)
        val analytics = object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onLoadError(t: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, l: androidx.media3.exoplayer.source.LoadEventInfo,
                                     m: androidx.media3.exoplayer.source.MediaLoadData, e: java.io.IOException, cancelled: Boolean) { reconnects++ }
            // MPEG-2 de la TV de cable decodificado por FFmpeg (el chip no puede): viene entrelazado y FFmpeg no lo
            // desentrelaza (se ven rayas en los bordes al moverse). VLC sí, así que en automático se pasa a VLC,
            // salvo con subtítulos IA (necesitan ExoPlayer), si en Ajustes se eligió quedarse en ExoPlayer o si la
            // persona eligió ExoPlayer a mano para este canal. Se revisa al conocer el formato y al abrir el
            // decodificador, porque según el equipo uno llega antes que el otro.
            var inputMime: String? = null
            fun checkMpeg2() {
                val mpeg2 = (inputMime ?: player.videoFormat?.sampleMimeType) == androidx.media3.common.MimeTypes.VIDEO_MPEG2
                val manualExo = engineFor?.first == currentItem.id && engineFor?.second == "exo"
                if (videoDecoder.contains("ffmpeg", true) && mpeg2 && !manualExo && vm.settings.engine == "auto" &&
                    vm.settings.mpeg2Software == "vlc" && vm.aiSubs == null)
                    switchEngine("vlc", "Video de TV de cable sin decodificador en el equipo: usando VLC para una imagen sin rayas")
            }
            // Calidad automática: avisar cuando baja o sube por la velocidad del internet (como mucho cada 20 s)
            var lastHeight = 0
            var lastNoticeAt = 0L
            var lastItemId = ""
            override fun onVideoInputFormatChanged(t: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, format: androidx.media3.common.Format,
                                                   reuse: androidx.media3.exoplayer.DecoderReuseEvaluation?) {
                inputMime = format.sampleMimeType
                checkMpeg2()
                val h = format.height
                if (lastItemId != currentItem.id) { lastItemId = currentItem.id; lastHeight = 0 }
                if (h > 0 && lastHeight > 0 && h != lastHeight && !exo.qualityLocked && exo.qualities().size > 1) {
                    val nowMs = System.currentTimeMillis()
                    vm.logEvent("Calidad automática: ${lastHeight}p → ${h}p (${"%.1f".format(bandwidth.bitrateEstimate / 1e6)} Mb/s)")
                    if (nowMs - lastNoticeAt > 20_000) {
                        lastNoticeAt = nowMs
                        vm.message = if (h < lastHeight) "Calidad: ${h}p (tu internet va más lento)" else "Calidad: ${h}p"
                    }
                }
                if (h > 0) lastHeight = h
            }
            override fun onVideoDecoderInitialized(t: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, name: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                videoDecoder = name
                vm.logEvent("Decodificador de video: $name")
                checkMpeg2()
            }
        }
        player.addAnalyticsListener(analytics)
        onDispose { vm.saveBitrate(); player.removeListener(listener); player.removeAnalyticsListener(analytics); player.release() }
    }

    // Al salir del elemento (otro canal o volver al menú): guardar dónde quedó
    DisposableEffect(item.id, engine, player) {
        onDispose {
            // Sólo ExoPlayer: VLC y MPV ya se cerraron a esta altura (su posición se guarda cada 10 s)
            if (!live && !useAlt) runCatching { vm.savePosition(item, exo.position, exo.duration) }
        }
    }

    // Pantalla completa inmersiva + horizontal en teléfonos; PiP al salir de la app.
    // En el mini reproductor el teléfono vuelve a girar libre y con las barras del sistema, para usar el menú.
    val prevOrientation = remember { activity.requestedOrientation }
    DisposableEffect(vm.mini) {
        val win = activity.window
        val wc = WindowCompat.getInsetsController(win, win.decorView)
        if (vm.mini) {
            wc.show(WindowInsetsCompat.Type.systemBars())
            if (!activity.isTv) activity.requestedOrientation = prevOrientation
        } else {
            wc.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            wc.hide(WindowInsetsCompat.Type.systemBars())
            if (!activity.isTv) activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        onDispose {}
    }
    DisposableEffect(Unit) {
        val win = activity.window
        val wc = WindowCompat.getInsetsController(win, win.decorView)
        activity.pipEnabled = true
        onDispose {
            // El brillo ajustado con el gesto sólo vale dentro del reproductor
            runCatching { val lp = win.attributes; lp.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE; win.attributes = lp }
            wc.show(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = prevOrientation
            activity.pipEnabled = false
        }
    }

    // Pausar en segundo plano (salvo en PiP)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, player) {
        var wasPlaying = false
        val obs = LifecycleEventObserver { _, ev ->
            when (ev) {
                Lifecycle.Event.ON_STOP -> { wasPlaying = player.playWhenReady || alt?.isPlaying == true; player.pause(); alt?.pause() }
                Lifecycle.Event.ON_START -> if (wasPlaying) { if (alt != null) alt?.resume() else player.play() }
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    // Saltar intro (series): el botón aparece al principio del episodio. Si ya se marcó dónde termina la intro de
    // esta serie (Más → Aquí termina la intro), salta justo ahí; si no, avanza 85 s (lo que dura una intro típica).
    val isEpisode = !live && item.episode > 0
    // Estado (no un valor fijo): las teclas del control lo leen al momento, aunque se haya marcado recién
    var introEnd by remember(item.id) { mutableStateOf(if (isEpisode) vm.introEnd(item) else 0L) }
    var introDone by remember(item.id) { mutableStateOf(false) }
    /** ¿Se muestra "Saltar intro"? (al principio del episodio, si todavía no se saltó). */
    fun showSkipNow(): Boolean {
        if (!isEpisode || introDone || !started || panel != null) return false
        val p = ctlOf(useAlt, alt, exo)?.position ?: return false
        return if (introEnd > 0) p in 1_000 until introEnd - 3_000 else p in 15_000..240_000
    }
    fun skipIntro() {
        val k = ctlOf(useAlt, alt, exo) ?: return
        introDone = true
        if (introEnd > 0) { k.seekTo(introEnd); vm.message = "Intro saltada" }
        else { k.seekBy(85_000); vm.message = "Intro saltada (85 s). Si no calzó, márcala en Más → Aquí termina la intro" }
    }
    // Automático (Ajustes → Reproducción): sólo si ya se sabe dónde termina y el episodio está empezando
    LaunchedEffect(item.id, started, introEnd) {
        if (!isEpisode || !started || introEnd <= 0 || !vm.settings.autoSkipIntro || introDone) return@LaunchedEffect
        delay(1500)
        val p = ctlOf(useAlt, alt, exo)?.position ?: return@LaunchedEffect
        if (p < introEnd - 5_000 && p < 10 * 60_000) skipIntro()
    }

    // Mando a distancia. Con los controles a la vista, ▲▼◄► y OK se mueven entre los botones;
    // ocultos: ▲▼ cambia de canal (en vivo), ◄► avanza o retrocede, OK muestra los controles.
    DisposableEffect(item.id, engine, player, vm.mini, tsSession) {
        activity.keyInterceptor = handler@{ ev ->
            val code = ev.keyCode
            val down = ev.action == KeyEvent.ACTION_DOWN
            if (vm.mini) {
                // Mini reproductor: el control maneja el menú; sólo las teclas de medios y canal van al video
                return@handler when (code) {
                    KeyEvent.KEYCODE_CHANNEL_UP -> { if (down) vm.zap(1); true }
                    KeyEvent.KEYCODE_CHANNEL_DOWN -> { if (down) vm.zap(-1); true }
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                        if (down) ctlOf(useAlt, alt, exo)?.togglePause(); true
                    }
                    else -> false
                }
            }
            when (code) {
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> { if (down) vm.zap(1); return@handler true }
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> { if (down) vm.zap(-1); return@handler true }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    if (down) { val k = ctlOf(useAlt, alt, exo); userPaused = k?.isPlaying == true; k?.togglePause(); poke() }; return@handler true
                }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    if (down) { if (!live) { ctlOf(useAlt, alt, exo)?.seekBy(30_000); poke() } else if (tsSession != null) tsSeek(30_000) }; return@handler true
                }
                KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    if (down) { if (!live) { ctlOf(useAlt, alt, exo)?.seekBy(-10_000); poke() } else if (tsSession != null) tsSeek(-10_000) }; return@handler true
                }
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BOOKMARK -> { if (ev.action == KeyEvent.ACTION_UP) { panel = null; poke() }; return@handler true }
                KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_GUIDE -> { if (down) { bannerUntil = System.currentTimeMillis() + 5000; poke() }; return@handler true }
                KeyEvent.KEYCODE_CAPTIONS -> { if (down) { poke(); panel = PAction.SUBS }; return@handler true }
                KeyEvent.KEYCODE_LAST_CHANNEL -> { if (down) vm.previousChannel(); return@handler true }
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9, in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> {
                    if (vm.playList.size < 2) return@handler false
                    if (down && ev.repeatCount == 0) {
                        val d = if (code <= KeyEvent.KEYCODE_9) code - KeyEvent.KEYCODE_0 else code - KeyEvent.KEYCODE_NUMPAD_0
                        if (digits.length < 5) digits += d
                    }
                    return@handler true
                }
            }
            if (controls || panel != null) { if (down) lastTouch = System.currentTimeMillis(); return@handler false }
            when (code) {
                // Con "Saltar intro" a la vista, OK la salta (como en las apps de streaming)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE -> {
                    if (down) { if (showSkipNow()) skipIntro() else poke() }; true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (down) { if (live) vm.zap(if (code == KeyEvent.KEYCODE_DPAD_UP) -1 else 1) else poke() }; true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (down) {
                        // En vivo con la pausa en vivo: ◄ retrocede 10 s y ► adelanta (hasta el directo), sin abrir los controles
                        if (live && tsSession != null && (code == KeyEvent.KEYCODE_DPAD_LEFT || tsBehind() >= 3_000)) {
                            val left = code == KeyEvent.KEYCODE_DPAD_LEFT
                            tsSeek(if (left) -10_000 else 10_000); seekFlash = (if (left) -1 else 1) to System.currentTimeMillis()
                        }
                        else if (live) poke()
                        else { ctlOf(useAlt, alt, exo)?.seekBy(if (code == KeyEvent.KEYCODE_DPAD_LEFT) -10_000 else 10_000); poke() }
                    }
                    true
                }
                else -> false
            }
        }
        onDispose { activity.keyInterceptor = null }
    }

    LaunchedEffect(vm.mini) { if (vm.mini) { controls = false; panel = null } }
    BackHandler(enabled = !vm.mini) {
        when {
            panel != null -> panel = null
            controls && error == null -> controls = false
            else -> vm.closePlayer()
        }
    }

    val subsPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.viewModelScope.launch {
            runCatching {
                val name = uri.lastPathSegment?.substringAfterLast('/') ?: "externo.srt"
                val ext = when { name.endsWith(".vtt", true) -> "vtt"; name.endsWith(".ass", true) || name.endsWith(".ssa", true) -> "ass"; else -> "srt" }
                val dest = File(vm.subtitleDir().apply { mkdirs() }, "local_${System.currentTimeMillis()}.$ext")
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                    dest.writeText(cl.iptv.player.data.SubtitleSources.decode(bytes))   // corrige .srt en Windows-1252
                }
                dest
            }.onSuccess { subtitle = it; vm.message = "Subtítulo cargado" }
                .onFailure { vm.message = "No se pudo leer el subtítulo: ${it.message}" }
        }
    }

    /** Abre el canal en la app VLC instalada (opcional: Vibe IPTV ya trae VLC por dentro). */
    fun openInVlc() {
        val i = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(item.url), "video/*")
            setPackage("org.videolan.vlc")
            putExtra("title", item.name)
            subtitle?.let { putExtra("subtitles_location", it.absolutePath) }
        }
        try { player.pause(); alt?.pause(); ctx.startActivity(i) }
        catch (e: ActivityNotFoundException) {
            try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=org.videolan.vlc"))) }
            catch (e2: ActivityNotFoundException) { vm.message = "VLC no está instalado" }
        }
    }

    // Subtítulos del canal con retraso (ExoPlayer): se muestran los que llegaron hace [subDelay] ms
    LaunchedEffect(subDelay, player) {
        synchronized(cueQueue) { cueQueue.clear() }
        delayedCues = emptyList()
        if (subDelay <= 0) return@LaunchedEffect
        while (true) {
            delay(50)
            val limit = android.os.SystemClock.uptimeMillis() - subDelay
            var show: List<androidx.media3.common.text.Cue>? = null
            synchronized(cueQueue) { while (cueQueue.isNotEmpty() && cueQueue.first().first <= limit) show = cueQueue.removeFirst().second }
            show?.let { delayedCues = it }
        }
    }
    fun setSubDelay(v: Long) {
        val label = if (v == 0L) "sin retraso" else "%+.1f s".format(v / 1000f)
        if (useAlt) {
            if (alt?.setSubtitleDelay(v) == true) { subDelay = v; vm.message = "Subtítulos: $label" }
            else vm.message = "Este motor no permite sincronizar los subtítulos. Prueba con VLC (Más → Motor de reproducción)"
            return
        }
        val f = subtitle
        if (f != null) {
            runCatching { cl.iptv.player.data.SubtitleSources.shift(f, v - subDelay, vm.subtitleDir()) }
                .onSuccess { subtitle = it; subDelay = v; vm.message = "Subtítulos: $label" }
                .onFailure { vm.message = "No se pudo mover el subtítulo: ${it.message}" }
            return
        }
        if (v < 0) { vm.message = "Con ExoPlayer los subtítulos del canal sólo pueden ir más tarde. Para adelantarlos usa VLC (Más → Motor de reproducción)"; return }
        subDelay = v; vm.message = "Subtítulos: $label"
    }
    // Al cambiar de motor, el nuevo parte sin retraso: se le vuelve a aplicar
    LaunchedEffect(alt) { if (useAlt && subDelay != 0L) { delay(1500); alt?.setSubtitleDelay(subDelay) } }

    // Enviar a la TV: busca en la red mientras el panel está abierto
    var castTargets by remember { mutableStateOf<List<cl.iptv.player.data.Cast.Target>>(emptyList()) }
    DisposableEffect(panel == PAction.SEND) {
        val f = if (panel == PAction.SEND) cl.iptv.player.data.CastFinder(ctx, vm.deviceName) { castTargets = it }.also { it.start() } else null
        onDispose { f?.stop() }
    }
    fun sendTo(t: cl.iptv.player.data.Cast.Target) {
        val pos = if (live) 0L else ctl?.position ?: 0L
        val it0 = item
        vm.message = "Enviando a ${t.name.removePrefix("Vibe IPTV · ")}…"
        vm.viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val r = runCatching { cl.iptv.player.data.Cast.send(t, it0, pos) }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                r.onSuccess { ctlOf(useAlt, alt, exo)?.pause(); userPaused = true; vm.message = "Enviado a ${t.name.removePrefix("Vibe IPTV · ")}" }
                    .onFailure { vm.message = "No se pudo enviar: ${it.message}" }
            }
        }
    }

    fun onAction(a: PAction) {
        poke()
        val k = ctl
        when (a) {
            PAction.BACK -> vm.closePlayer()
            PAction.PREV -> vm.zap(-1)
            PAction.NEXT -> vm.zap(1)
            PAction.REW -> if (live && tsSession != null) tsSeek(-10_000) else k?.seekBy(-10_000)
            PAction.FWD -> if (live && tsSession != null) tsSeek(10_000) else k?.seekBy(10_000)
            PAction.LIVE -> { tsJump = Long.MAX_VALUE; vm.message = "En directo" }
            PAction.FIND -> vm.openGuideSearch()
            PAction.INTRO -> {
                val p = k?.position ?: 0L
                if (p < 10_000) vm.message = "Avanza hasta donde termina la intro y vuelve a elegir esta opción"
                else if (vm.markIntroEnd(item, p)) { introEnd = p; introDone = true; vm.message = "Listo: en los demás episodios, \"Saltar intro\" llegará hasta ${fmtDur(p)}" }
                else vm.message = "No se pudo saber a qué serie pertenece este episodio"
            }
            PAction.PLAY -> { userPaused = k?.isPlaying == true; k?.togglePause() }
            PAction.FAV -> {
                val wasFav = item.id in vm.favorites
                vm.toggleFavorite(item)
                vm.message = if (wasFav) "${item.name} quitado de favoritos" else "★ ${item.name} añadido a favoritos"
            }
            // En TV: mini reproductor dentro de la app para recorrer el menú y la guía; en celulares, el PiP del sistema
            // Igual que en la TV: el video sigue en una ventanita y se puede usar el menú de la app.
            // La imagen en imagen del sistema queda para cuando se sale de la app.
            PAction.PIP -> vm.enterMini()
            PAction.EXTERNAL -> openInVlc()
            PAction.RECORD -> if (vm.recording?.item?.id == item.id) vm.stopRecording() else vm.startRecording(item)
            PAction.LASTCH -> vm.previousChannel()
            PAction.MULTI -> vm.openMultiview(item)
            else -> panel = a
        }
    }

    val recentCount = remember(vm.recent, vm.items, item.id) { if (live) vm.recentChannels(item.id).size else 0 }
    // Reloj para el banner
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    LaunchedEffect(error) { error?.let { vm.logEvent("Error [$engine] ${item.name}: $it · ${playUrl}") } }
    val showBanner = !activity.inPip && !vm.mini && (controls || now < bannerUntil || error != null || vm.recording != null)
    val resize = when (vm.settings.aspect) {
        "fill", "zoom" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        "stretch" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    }

    // Tamaño de los controles (Ajustes → Reproducción): escala todo lo que se dibuja sobre el video
    val baseDensity = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(baseDensity.density * vm.settings.hudScale, baseDensity.fontScale),
    ) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val onAltEnded = {
            val cur = vm.playing
            if (vm.settings.autoNext && cur != null && cur.episode > 0 && vm.playIndex < vm.playList.size - 1) vm.zap(1)
        }
        if (engine == "vlc") {
            key(item.id, "vlc", playUrl, reloadKey, bufLevel) {
                VlcVideo(
                    url = playUrl, userAgent = item.userAgent, referrer = item.referrer, aspect = vm.settings.aspect, cachingMs = buf.vlcCachingMs,
                    audioFilter = when { vm.settings.soundEq == cl.iptv.player.data.Sound.NIGHT -> "night"; vm.settings.soundLevel -> "level"; else -> "" },
                    modifier = Modifier.fillMaxSize(),
                    onReady = { alt = it },
                    onPlaying = { started = true; error = null },
                    // VLC tampoco pudo: en automático, el siguiente motor (MPV o el del sistema)
                    onError = { msg -> val n = nextEngine("vlc"); if (vm.settings.engine == "auto" && n != null) switchEngine(n, "Probando con ${engineName[n]}…") else { error = msg } },
                    onEnded = onAltEnded,
                )
            }
            DisposableEffect(Unit) { onDispose { alt = null } }
        } else if (engine == "mpv") {
            key(item.id, "mpv", playUrl, reloadKey, bufLevel) {
                MpvVideo(
                    url = playUrl, userAgent = item.userAgent, referrer = item.referrer, aspect = vm.settings.aspect,
                    cacheSecs = buf.mpvSecs, maxMiB = buf.mpvMaxMiB,
                    modifier = Modifier.fillMaxSize(),
                    onReady = { alt = it },
                    onPlaying = { started = true; error = null },
                    // MPV tampoco pudo: en automático, último intento con el reproductor del sistema
                    onError = { msg -> if (vm.settings.engine == "auto") switchEngine("system", "Probando con ${engineName["system"]}…") else { error = msg } },
                    onEnded = onAltEnded,
                )
            }
            DisposableEffect(Unit) { onDispose { alt = null } }
        } else if (engine == "system") {
            key(item.id, "system", playUrl, reloadKey) {
                SystemVideo(
                    url = playUrl, userAgent = item.userAgent, referrer = item.referrer, aspect = vm.settings.aspect, live = live,
                    modifier = Modifier.fillMaxSize(),
                    onReady = { alt = it },
                    onStatus = { vm.message = it },
                    onPlaying = { started = true; error = null },
                    onError = { error = it },
                    onEnded = onAltEnded,
                )
            }
            DisposableEffect(Unit) { onDispose { alt = null } }
        } else AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                PlayerView(context).apply {
                    this.player = player
                    useController = false                 // los controles son los de Vibe IPTV (iguales en los tres motores)
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    subtitleView?.setFractionalTextSize(androidx.media3.ui.SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * subtitleScale(vm.settings.subSize))
                    keepScreenOn = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                }
            },
            update = { v ->
                v.resizeMode = resize; if (v.player !== player) v.player = player
                // Con retraso, los subtítulos del canal los dibuja la capa de abajo (los de aquí llegarían a tiempo)
                v.subtitleView?.visibility = if (subDelay > 0 && subtitle == null) android.view.View.GONE else android.view.View.VISIBLE
            },
        )
        if (!useAlt && subDelay > 0 && subtitle == null) AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                androidx.media3.ui.SubtitleView(context).apply {
                    setUserDefaultStyle(); setUserDefaultTextSize()
                    setFractionalTextSize(androidx.media3.ui.SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * subtitleScale(vm.settings.subSize))
                }
            },
            update = { it.setCues(delayedCues) },
        )

        // Tocar la imagen muestra u oculta los controles.
        // Deslizadores invisibles: arrastrar arriba/abajo en el borde izquierdo = brillo, en el derecho = volumen.
        val audio = remember { ctx.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager }
        val maxVol = remember { audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
        Box(
            Modifier.fillMaxSize()
                .pointerInput(live, engine, player) {
                    val tap: (androidx.compose.ui.geometry.Offset) -> Unit = { if (panel != null) panel = null else if (controls) controls = false else poke() }
                    if (live) detectTapGestures(onTap = tap)
                    // Películas y series: doble toque a la izquierda / derecha = −10 s / +10 s
                    else detectTapGestures(onTap = tap, onDoubleTap = { o ->
                        val dir = when { o.x < size.width * 0.4f -> -1; o.x > size.width * 0.6f -> 1; else -> 0 }
                        if (dir == 0) { val k = ctlOf(useAlt, alt, exo); userPaused = k?.isPlaying == true; k?.togglePause() }
                        else { ctlOf(useAlt, alt, exo)?.seekBy(dir * 10_000L); seekFlash = dir to System.currentTimeMillis() }
                    })
                }
                .pointerInput(Unit) {
                    var zone = 0          // -1 brillo, 1 volumen, 0 nada (centro)
                    var level = 0f
                    detectVerticalDragGestures(
                        onDragStart = { o ->
                            zone = when { o.x < size.width * 0.3f -> -1; o.x > size.width * 0.7f -> 1; else -> 0 }
                            level = if (zone == -1) {
                                activity.window.attributes.screenBrightness.takeIf { it >= 0f }
                                    ?: (android.provider.Settings.System.getInt(ctx.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, 128) / 255f)
                            } else audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC).toFloat() / maxVol
                        },
                        onDragEnd = { zone = 0 },
                        onDragCancel = { zone = 0 },
                    ) { change, dy ->
                        if (zone == 0) return@detectVerticalDragGestures
                        change.consume()
                        level = (level - dy / (size.height * 0.8f)).coerceIn(0f, 1f)   // arrastrar todo el alto ≈ 0 → 100 %
                        if (zone == -1) {
                            val lp = activity.window.attributes
                            lp.screenBrightness = level.coerceAtLeast(0.01f)
                            activity.window.attributes = lp
                            gesture = "brillo" to level
                        } else {
                            audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, Math.round(level * maxVol), 0)
                            gesture = "volumen" to level
                        }
                        gestureAt = System.currentTimeMillis()
                    }
                },
        )
        // Subtítulos IA en vivo (últimas dos frases + lo que se está oyendo)
        if (vm.aiSubs != null && !vm.mini) {
            val cap = vm.captioner
            val textNow = (cap.lines + cap.partial.split('\n').filter { it.isNotBlank() }).takeLast(2).joinToString("\n")
            val t0 = System.currentTimeMillis()
            // Sin texto: un aviso discreto de que está escuchando, o de que no le llega audio que reconocer
            if ((textNow.isBlank() || now - cap.lastAt >= 6000) && t0 - cap.startedAt > 4000) Text(
                if (t0 - cap.audioAt < 3000) "✨ Escuchando…" else "✨ Sin audio para reconocer (usa el motor ExoPlayer)",
                color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = if (controls) 150.dp else 40.dp)
                    .clip(RoundedCornerShape(8.dp)).background(Color(0x80000000)).padding(horizontal = 10.dp, vertical = 4.dp),
            )
            if (textNow.isNotBlank() && now - cap.lastAt < 6000) Text(
                textNow, color = Color.White, fontSize = (22 * subtitleScale(vm.settings.subSize)).sp, fontWeight = FontWeight.Bold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = (28 * subtitleScale(vm.settings.subSize)).sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = if (controls) 150.dp else 40.dp, start = 40.dp, end = 40.dp)
                    .clip(RoundedCornerShape(10.dp)).background(Color(0xA6000000)).padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
        // Doble toque: indicador ±10 s
        if (seekFlash.first != 0 && System.currentTimeMillis() - seekFlash.second < 700) {
            LaunchedEffect(seekFlash) { delay(720); seekFlash = 0 to 0L }
            Text(if (seekFlash.first < 0) "«  10 s" else "10 s  »", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp,
                modifier = Modifier.align(if (seekFlash.first < 0) Alignment.CenterStart else Alignment.CenterEnd).padding(horizontal = 60.dp)
                    .clip(RoundedCornerShape(30.dp)).background(Color(0x99000000)).padding(horizontal = 18.dp, vertical = 10.dp))
        }
        // Saltar intro (series): con el control, OK; con el dedo, tocarlo
        if (!vm.mini && run { now; showSkipNow() }) {
            Text("Saltar intro ⏭", color = Color(0xFF14181B), fontWeight = FontWeight.ExtraBold, fontSize = 16.sp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 36.dp, bottom = if (controls) 150.dp else 48.dp)
                    .clip(RoundedCornerShape(12.dp)).background(Color.White).clickable { skipIntro() }
                    .padding(horizontal = 18.dp, vertical = 10.dp))
        }
        // Pausa en vivo: cuánto va atrás del directo (con los controles ocultos)
        val behindNow = run { now; tsBehind() }
        if (tsSession != null && !controls && !vm.mini && behindNow >= 3_000) {
            Text("−${fmtDur(behindNow)} · ► vuelve al directo", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 20.dp, end = 24.dp)
                    .clip(RoundedCornerShape(10.dp)).background(Color(0xCC5A6066)).padding(horizontal = 12.dp, vertical = 6.dp))
        }
        // Número de canal que se está escribiendo con el control
        if (digits.isNotEmpty()) {
            Text(digits, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 44.sp,
                modifier = Modifier.align(Alignment.TopEnd).padding(28.dp).clip(RoundedCornerShape(14.dp))
                    .background(Color(0xCC101417)).padding(horizontal = 22.dp, vertical = 8.dp))
        }
        // Indicador de brillo / volumen mientras se ajusta
        gesture?.takeIf { now - gestureAt < 1200 || System.currentTimeMillis() - gestureAt < 1200 }?.let { (kind, v) ->
            Column(
                Modifier.align(if (kind == "brillo") Alignment.CenterStart else Alignment.CenterEnd).padding(horizontal = 36.dp)
                    .clip(RoundedCornerShape(16.dp)).background(Color(0xCC101417)).padding(horizontal = 14.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(if (kind == "brillo") "☀" else if (v == 0f) "🔇" else "🔊", color = Color.White, fontSize = 22.sp)
                Spacer(Modifier.height(10.dp))
                Box(Modifier.width(8.dp).height(140.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.25f)), contentAlignment = Alignment.BottomCenter) {
                    Box(Modifier.fillMaxWidth().fillMaxHeight(v).background(c.accent))
                }
                Spacer(Modifier.height(8.dp))
                Text("${Math.round(v * 100)} %", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }

        AnimatedVisibility(showBanner, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Row(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                    .padding(start = 24.dp, end = 20.dp, top = 16.dp, bottom = 36.dp),
                verticalAlignment = Alignment.Top,
            ) {
                if (item.logo.isNotBlank()) {
                    AsyncImage(item.logo, null, Modifier.size(width = 64.dp, height = 44.dp))
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        (item.num?.let { "$it · " } ?: "") + item.name, color = Color.White, fontWeight = FontWeight.ExtraBold,
                        fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(item.group, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, maxLines = 1)
                    if (live) {
                        val (cur, next) = vm.epg.nowNext(item, now - vm.epgShift)
                        val sh = vm.epgShift
                        if (cur != null) {
                            Spacer(Modifier.height(6.dp))
                            Text("${cur.title}  ${fmtTime(cur.start + sh)}–${fmtTime(cur.stop + sh)}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1)
                            LinearProgressIndicator(
                                progress = { ((now - sh - cur.start).toFloat() / (cur.stop - cur.start)).coerceIn(0f, 1f) },
                                color = c.accent, trackColor = Color.White.copy(alpha = 0.25f),
                                modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(vertical = 5.dp).height(4.dp).clip(RoundedCornerShape(4.dp)),
                            )
                        }
                        next?.let { Text("Luego: ${it.title} · ${fmtTime(it.start + sh)}", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, maxLines = 1) }
                    }
                }
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(horizontal = 8.dp)) {
                    vm.recording?.let { r ->
                        Text("● REC ${fmtDur(now - r.started)} · ${"%.0f".format(r.bytes / 1_048_576.0)} MB",
                            color = Color(0xFFFF6B6B), fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                    }
                    Text(fmtTime(now), color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                    if (vm.sleepAt > 0) Text("⏾ ${((vm.sleepAt - now) / 60_000 + 1).coerceAtLeast(1)} min", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text("Motor: ${engineName[engine]}" + if (tsSession != null) " · ⏸ en vivo" else "", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
                }
            }
        }

        // Error: qué pasó y qué se puede hacer, sin salir del reproductor
        if (vm.mini) {
            // Mini reproductor: tocarlo vuelve a pantalla completa
            Box(Modifier.fillMaxSize().clickable { vm.expandMini() }) {
                Text("⤢", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x99000000)).padding(horizontal = 6.dp))
                // En teléfonos: cerrar el video sin volver a pantalla completa
                if (!activity.isTv) Text("✕", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x99000000))
                        .clickable { vm.closePlayer() }.padding(horizontal = 7.dp))
                // En teléfonos y tabletas: cambiar el tamaño (pequeño → mediano → grande); se mueve arrastrándolo
                if (!activity.isTv) Text("⤡", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x99000000))
                        .clickable {
                            val next = when (vm.settings.miniSize) { "small" -> "medium"; "medium" -> "large"; else -> "small" }
                            vm.updateSettings(vm.settings.copy(miniSize = next))
                        }.padding(horizontal = 7.dp))
            }
        }
        if (!vm.mini) error?.let { msg ->
            Column(
                Modifier.align(Alignment.Center).widthIn(max = 520.dp).padding(24.dp).clip(RoundedCornerShape(18.dp))
                    .background(Color(0xF0161A1D)).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("No se pudo reproducir", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                Text(msg, color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton("Reintentar") { error = null; altFor = null; reloadKey++ }
                    val other = nextEngine(engine) ?: "exo"
                    ActionButton("Probar con ${engineName[other]}", ghost = true) { error = null; engineFor = item.id to other }
                }
            }
        }

        AnimatedVisibility(controls && panel == null && !activity.inPip && !vm.mini, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            PlayerControlsBar(
                ctl = ctl, live = live, hasList = vm.playList.size > 1, fav = item.id in vm.favorites,
                showQuality = !useAlt && exo.qualities().isNotEmpty(),
                recording = vm.recording?.item?.id == item.id,
                recents = recentCount > 0,
                timeshift = tsSession != null,
                behindMs = run { now; tsBehind() },
                playFocus = playFocus, onAction = ::onAction, onInteract = { lastTouch = System.currentTimeMillis() },
            )
        }

        // Paneles laterales de opciones
        if (!vm.mini) panel?.let { p ->
            val k = ctl
            val rows: List<PanelRow> = when (p) {
                PAction.LIST -> vm.playList.mapIndexed { i, it ->
                    val cur = if (it.kind == Kind.LIVE) vm.epg.nowNext(it, now - vm.epgShift).first else null
                    val sh = vm.epgShift
                    PanelRow(
                        it.name, i == vm.playIndex,                       // sólo el nombre: más fácil de leer
                        cur?.let { p -> "${p.title}  ${fmtTime(p.start + sh)}–${fmtTime(p.stop + sh)}" }.orEmpty(),
                        logo = it.logo,
                        progress = cur?.let { p -> ((now - sh - p.start).toFloat() / (p.stop - p.start)).coerceIn(0f, 1f) } ?: -1f,
                    ) { vm.playAt(i); panel = null }
                }
                PAction.AUDIO -> k?.audioTracks().orEmpty().map { t -> PanelRow(t.label, t.selected) { k?.selectAudio(t.id); panel = null } }
                PAction.SUBS -> buildList {
                    val tracks = k?.subtitleTracks().orEmpty()
                    add(PanelRow("Desactivados", tracks.none { it.selected } && subtitle == null) { k?.selectSubtitle(null); subtitle = null; panel = null })
                    tracks.forEach { t -> add(PanelRow(t.label, t.selected) { k?.selectSubtitle(t.id); panel = null }) }
                    add(PanelRow("Buscar subtítulos…", detail = "OpenSubtitles · SubDL · Podnapisi") { panel = null; showSubs = true })
                    // Generados con IA en el equipo (Vosk): transcribe el audio en su idioma, sin internet
                    if (vm.aiSubs != null) add(PanelRow("✨ Desactivar subtítulos IA", true, "Generando en ${cl.iptv.player.data.VoskModels.names[vm.aiSubs] ?: ""}") { vm.stopAiSubs(byUser = true); panel = null })
                    else listOf("es", "en", "pt").forEach { l ->
                        add(PanelRow("✨ Generar con IA · audio en ${cl.iptv.player.data.VoskModels.names[l]}", false,
                            if (vm.aiBundled(l)) "Sin internet · modelo incluido" else "Sin internet · descarga el modelo la 1.ª vez (~40 MB)") {
                            panel = null; vm.startAiSubs(l)
                        })
                    }
                    add(PanelRow("Cargar archivo .srt / .vtt / .ass") {
                        panel = null
                        try { subsPicker.launch(arrayOf("*/*")) } catch (e: ActivityNotFoundException) { vm.message = "Este dispositivo no tiene selector de archivos" }
                    })
                }
                PAction.QUALITY -> buildList {
                    add(PanelRow("Automática", !exo.qualityLocked) { exo.selectQuality(null); panel = null })
                    exo.qualities().forEach { q -> add(PanelRow(q.label, exo.qualityLocked && q.selected) { exo.selectQuality(q.id); panel = null }) }
                }
                PAction.ASPECT -> listOf("fit" to "Ajustar (sin recortar)", "fill" to "Rellenar pantalla", "stretch" to "Estirar", "zoom" to "Zoom").map { (id, name) ->
                    PanelRow(name, vm.settings.aspect == id) { vm.updateSettings(vm.settings.copy(aspect = id)); panel = null }
                }
                PAction.SPEED -> listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).map { v ->
                    PanelRow(if (v == 1f) "Normal" else "${v}×".replace(".0×", "×"), kotlin.math.abs((k?.speed ?: 1f) - v) < 0.01f) { k?.speed = v; panel = null }
                }
                PAction.ENGINE -> buildList {
                    add(PanelRow("ExoPlayer", engine == "exo", "Rápido y liviano; con FFmpeg para audio AC-3") { engineFor = item.id to "exo"; panel = null })
                    add(PanelRow("VLC", engine == "vlc", "TV de cable: MPEG-2, AC-3, MP2 (incluido en la app)") { engineFor = item.id to "vlc"; panel = null })
                    if (android.os.Build.VERSION.SDK_INT >= 26)
                        add(PanelRow("MPV", engine == "mpv", "Casi cualquier formato, buena tolerancia a cortes") { engineFor = item.id to "mpv"; panel = null })
                    add(PanelRow(engineName["system"].orEmpty(), engine == "system", "El reproductor que trae Android: usa el chip del equipo") { engineFor = item.id to "system"; panel = null })
                }
                PAction.SLEEP -> listOf(0, 15, 30, 60, 90, 120).map { m ->
                    val left = if (vm.sleepAt > 0) ((vm.sleepAt - now) / 60_000 + 1) else 0
                    PanelRow(if (m == 0) "Desactivado" else "$m min", if (m == 0) vm.sleepAt == 0L else false,
                        if (m == 0 && vm.sleepAt > 0) "Quedan $left min" else "") { vm.setSleep(m); panel = null }
                }
                PAction.SOUND -> buildList {
                    cl.iptv.player.data.Sound.presets.forEach { (id, name) ->
                        add(PanelRow(name, vm.settings.soundEq == id, when (id) {
                            cl.iptv.player.data.Sound.VOICES -> "Diálogos más nítidos"
                            cl.iptv.player.data.Sound.BASS -> "Para películas y música"
                            cl.iptv.player.data.Sound.NIGHT -> "Graves suaves y volumen parejo, para no despertar a nadie"
                            else -> "Sin cambios"
                        }) { vm.updateSettings(vm.settings.copy(soundEq = id)) })
                    }
                    add(PanelRow("Volumen parejo", vm.settings.soundLevel,
                        when {
                            engine == "system" -> "No disponible con el motor del sistema"
                            engine == "vlc" -> "Iguala canales y comerciales · en VLC se aplica al volver a abrir el canal"
                            else -> "Iguala el volumen entre canales y comerciales"
                        }) { vm.updateSettings(vm.settings.copy(soundLevel = !vm.settings.soundLevel)) })
                }
                PAction.AUDIODELAY -> (-10..10).map { i ->
                    val v = i * 100
                    PanelRow(if (v == 0) "Sin retraso" else "%+d ms".format(v) + if (v > 0) " (sonido después)" else " (sonido antes)", vm.settings.audioDelayMs == v,
                        when {
                            v != 0 -> ""
                            engine == "system" -> "No disponible con el motor del sistema"
                            tunnel && !useAlt -> "Con el modo túnel lo maneja el equipo: apágalo en Ajustes para usar esto"
                            else -> "Si el sonido va adelantado a la imagen (parlantes Bluetooth), elige \"sonido después\""
                        }) { vm.updateSettings(vm.settings.copy(audioDelayMs = v)) }
                }
                PAction.SUBDELAY -> (-10..10).map { i ->
                    val v = i * 500L
                    PanelRow(if (v == 0L) "Sin retraso" else "%+.1f s".format(v / 1000f) + if (v < 0) " (antes)" else " (después)", subDelay == v,
                        if (v == 0L) "Los subtítulos aparecen como vienen" else "") { setSubDelay(v) }
                }
                PAction.RECENT -> vm.recentChannels(item.id).map { ch ->
                    val cur = vm.epg.nowNext(ch, now - vm.epgShift).first
                    val sh = vm.epgShift
                    PanelRow(ch.name, false, cur?.let { "${it.title}  ${fmtTime(it.start + sh)}–${fmtTime(it.stop + sh)}" }.orEmpty(), logo = ch.logo,
                        progress = cur?.let { p -> ((now - sh - p.start).toFloat() / (p.stop - p.start)).coerceIn(0f, 1f) } ?: -1f) { vm.playRecent(ch); panel = null }
                }
                PAction.SEND -> if (castTargets.isEmpty()) listOf(
                    PanelRow("Buscando televisores con Vibe IPTV…", detail = "Abre Vibe IPTV en la TV y conéctala a la misma red Wi-Fi que este equipo") {},
                ) else castTargets.map { t ->
                    PanelRow(t.name.removePrefix("Vibe IPTV · "), detail = if (live) "Ver este canal en ese equipo" else "Seguir viendo en ese equipo desde ${fmtDur(ctl?.position ?: 0)}") { panel = null; sendTo(t) }
                }
                PAction.MORE -> moreActions(live, vm.playList.size > 1, !useAlt && exo.qualities().isNotEmpty(), isEpisode).map { a ->
                    val rec = a == PAction.RECORD && vm.recording?.item?.id == item.id
                    PanelRow(if (rec) "Detener grabación" else a.label, selected = rec, icon = a) { panel = null; onAction(a) }
                }
                PAction.INFO -> {
                    infoTick                                                    // se vuelve a leer cada segundo
                    val lvl = vm.bufferLevel(item)
                    val learned = (vm.bufferBoosts[item.id] ?: 0) > 0
                    val levelName = listOf("Rápido", "Equilibrado", "Estable", "Estable +")[lvl.coerceIn(0, 3)] + if (learned) " · aprendido" else ""
                    val diag = buildList {
                        k?.bufferedAheadMs?.let { add("Reserva guardada" to "%.1f s".format(it / 1000f)) }
                        add("Nivel de búfer" to levelName)
                        if (live) add("Colchón en vivo" to when {
                            !vm.settings.liveCushion -> "Desactivado"
                            k?.bufferedAheadMs == null -> "No disponible con este motor"
                            cushionSpeed < 1f -> "Rehaciendo la reserva (${Math.round(cushionSpeed * 100)} %)"
                            else -> "Reserva completa"
                        })
                        add("Cortes en este canal" to stalls.toString())
                        if (live) add("Pausa en vivo" to when {
                            tsSession != null -> {
                                val b = tsSession.buffer
                                val mins = ((b.latestAt - b.earliestAt) / 60_000).coerceAtLeast(0)
                                "Guardado: $mins min · ${(b.written - b.oldest) / 1_048_576} MB · reconexiones ${tsSession.reconnects}"
                            }
                            vm.settings.timeshiftMin <= 0 -> "Desactivada"
                            engine != "exo" -> "Sólo con ExoPlayer"
                            else -> "Sólo en canales .ts"
                        })
                        if (!useAlt) add("Reconexiones" to reconnects.toString())
                        if (!useAlt && videoDecoder.isNotEmpty()) add("Decodificador de video" to when {
                            videoDecoder.contains("ffmpeg", true) || videoDecoder.startsWith("lib") -> "FFmpeg (software) · $videoDecoder"
                            cl.iptv.player.data.Decoders.isSoftwareName(videoDecoder) -> "Software · $videoDecoder"
                            else -> "Chip del equipo · $videoDecoder"
                        })
                        if (!useAlt) bandwidth.bitrateEstimate.takeIf { it > 0 }?.let { add("Velocidad de descarga" to "%.1f Mb/s".format(it / 1e6)) }
                    }
                    (listOf("Motor" to engineName[engine].orEmpty()) + diag + k?.info().orEmpty().filter { it.first != "Búfer" } +
                        listOfNotNull(Uri.parse(playUrl).host?.let { "Servidor del canal" to it })).map { (l, v) -> PanelRow(l, detail = v) {} }
                }
                else -> emptyList()
            }
            PlayerSidePanel(p.label, rows, onClose = { panel = null }, modifier = Modifier.align(Alignment.CenterEnd), wide = p == PAction.LIST)
        }
    }

    }   // fin de la escala del HUD

    if (showSubs) SubtitlesDialog(
        vm = vm, item = item, current = subtitle,
        onPick = { f -> val first = subtitle == null; subtitle = f; if (first) { showSubs = false; vm.message = "Subtítulo cargado" } },
        onFile = { showSubs = false; try { subsPicker.launch(arrayOf("*/*")) } catch (e: ActivityNotFoundException) { vm.message = "Este dispositivo no tiene selector de archivos" } },
        onDisable = { subtitle = null; showSubs = false },
        onDismiss = { showSubs = false },
    )
}

private fun ctlOf(useAlt: Boolean, alt: AltEngine?, exo: ExoControl): PlaybackControl? = if (useAlt) alt else exo

/** Siguiente motor a probar en automático: ExoPlayer → VLC → MPV (Android 8+) → el del sistema. */
internal fun nextEngine(engine: String): String? = when (engine) {
    "exo" -> "vlc"
    "vlc" -> if (android.os.Build.VERSION.SDK_INT >= 26) "mpv" else "system"
    "mpv" -> "system"
    else -> null
}

@Composable
private fun SubtitlesDialog(
    vm: AppViewModel, item: Item, current: File?,
    onPick: (File) -> Unit, onFile: () -> Unit, onDisable: () -> Unit, onDismiss: () -> Unit,
) {
    val c = LocalIptv.current
    val src = vm.subtitleSources
    var query by remember { mutableStateOf(cleanTitle(item.seriesName.ifBlank { item.name })) }
    var found by remember { mutableStateOf<SubtitleSources.Found?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val year = remember(item.name) { Regex("\\b(19[3-9]\\d|20[0-4]\\d)\\b").find(item.name)?.value.orEmpty() }

    fun doSearch() {
        status = "Buscando en OpenSubtitles, SubDL y Podnapisi…"
        scope.launch {
            runCatching { src.search(SubtitleSources.Query(query, item.seriesName.isNotBlank() || item.season > 0, item.season, item.episode, year)) }
                .onSuccess { found = it; status = if (it.results.isEmpty()) "Sin resultados. Prueba con el título original (en inglés)." else null }
                .onFailure { status = it.message }
        }
    }

    Sheet("Subtítulos", onDismiss, wide = true) {
        if (current != null) {
            Text("Si no calzan con la voz: Más opciones → Sincronizar subtítulos", color = c.inkSoft, fontSize = 13.sp)
            Spacer(Modifier.height(10.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Field(query, { query = it }, "Título a buscar", modifier = Modifier.weight(1f), onDone = { doSearch() })
            ActionButton("Buscar") { doSearch() }
        }
        status?.let { Text(it, color = c.inkSoft, fontSize = 13.sp) }
        Spacer(Modifier.height(6.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            found?.results?.take(60)?.forEach { r ->
                ActionButton("[${r.lang.uppercase()}] ${r.label} · ${r.source}${if (r.downloads > 0) " · ⬇ ${r.downloads}" else ""}", Modifier.fillMaxWidth(), ghost = true) {
                    status = "Descargando de ${r.source}…"
                    scope.launch {
                        runCatching { src.download(r, vm.subtitleDir()) }
                            .onSuccess { onPick(it) }
                            .onFailure { status = "${r.source}: ${it.message}" }
                    }
                }
            }
            found?.errors?.takeIf { it.isNotEmpty() }?.let { errs ->
                Text(errs.entries.joinToString(" · ") { "${it.key}: ${it.value}" }, color = c.inkSoft, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionButton("Cargar archivo .srt / .vtt / .ass", ghost = true, onClick = onFile)
            if (current != null) ActionButton("Quitar subtítulo externo", ghost = true, onClick = onDisable)
        }
    }
}
