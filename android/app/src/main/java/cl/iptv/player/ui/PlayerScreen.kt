package cl.iptv.player.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.KeyEvent
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

    var controllerVisible by remember { mutableStateOf(true) }
    var bannerUntil by remember { mutableStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    var subtitle by remember(item.id) { mutableStateOf<File?>(null) }
    // Formato alternativo (.m3u8 ↔ .ts) si el primero falla: id del elemento que lo está usando
    var altFor by remember { mutableStateOf<String?>(null) }
    val playUrl = if (altFor == item.id) altFormat(item.url) ?: item.url else item.url
    val currentItem by rememberUpdatedState(item)
    // Motor: "exo" (ExoPlayer), "vlc" o "mpv". engineFor = (id del elemento, motor) cuando se cambió para este canal.
    // En automático: ExoPlayer → VLC → MPV, y los siguientes canales en vivo parten con el último motor que funcionó.
    var engineFor by remember { mutableStateOf<Pair<String, String>?>(null) }
    val defaultEngine = when (vm.settings.engine) {
        "vlc", "mpv", "exo" -> vm.settings.engine
        else -> if (item.kind == Kind.LIVE) vm.preferLiveEngine ?: "exo" else "exo"
    }
    val engine = engineFor?.takeIf { it.first == item.id }?.second ?: defaultEngine
    val useVlc = engine != "exo"          // motor alternativo (VLC o MPV) en vez de ExoPlayer
    var vlc by remember { mutableStateOf<AltEngine?>(null) }
    var liveRetries by remember { mutableStateOf(0) }
    fun switchEngine(to: String, reason: String) {
        val it0 = currentItem
        if (engineFor?.first == it0.id && engineFor?.second == to) return
        engineFor = it0.id to to
        if (it0.kind == Kind.LIVE) vm.preferLiveEngine = to
        vm.message = reason
    }
    fun switchToVlc(reason: String) = switchEngine("vlc", reason)
    val engineName = mapOf("exo" to "ExoPlayer", "vlc" to "VLC", "mpv" to "MPV")
    var showOptions by remember { mutableStateOf(false) }
    var showSubs by remember { mutableStateOf(false) }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    val live = item.kind == Kind.LIVE

    val player = remember {
        // Búfer más generoso y velocidad fija en directo: evita cortes y saltos hacia adelante
        val load = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 60_000, 2_500, 5_000)
            .build()
        val liveSpeed = androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl.Builder()
            .setFallbackMinPlaybackSpeed(1f).setFallbackMaxPlaybackSpeed(1f)
            .build()
        ExoPlayer.Builder(ctx).setLoadControl(load).setLivePlaybackSpeedControl(liveSpeed).build().apply {
            playWhenReady = true
            setHandleAudioBecomingNoisy(true)
        }
    }

    // Cargar el elemento actual (o recargar al añadir subtítulos conservando la posición)
    LaunchedEffect(item.id, subtitle, playUrl, engine) {
        error = null
        if (useVlc) { player.stop(); player.clearMediaItems(); bannerUntil = System.currentTimeMillis() + 4500; return@LaunchedEffect }
        val pos = if (subtitle != null && player.currentMediaItem?.mediaId == item.id) player.currentPosition else C.TIME_UNSET
        val http = OkHttpDataSource.Factory(Http.client)
            .setUserAgent(item.userAgent.ifBlank { Http.DEFAULT_UA })
            .apply { if (item.referrer.isNotBlank()) setDefaultRequestProperties(mapOf("Referer" to item.referrer)) }
        val factory = DefaultMediaSourceFactory(DefaultDataSource.Factory(ctx, http))
        val source = factory.createMediaSource(mediaItemFor(item.copy(url = playUrl), subtitle))
        if (pos != C.TIME_UNSET) player.setMediaSource(source, pos) else player.setMediaSource(source)
        player.prepare()
        player.play()
        bannerUntil = System.currentTimeMillis() + 4500
    }

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
                if (vm.settings.engine == "auto") { switchToVlc("Probando con el motor VLC…"); return }
                error = errorText(e)
            }
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                // Audio AC-3 / MP2 o video MPEG-2 sin decodificador en el equipo: pasar a VLC
                if (vm.settings.engine != "auto") return
                val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                val video = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
                val badAudio = audio.isNotEmpty() && audio.none { it.isSupported }
                val badVideo = video.isNotEmpty() && video.none { it.isSupported }
                if (badAudio || badVideo) switchToVlc(if (badVideo) "Video no compatible con este equipo: usando VLC" else "Audio no compatible con este equipo: usando VLC")
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { if (isPlaying) { error = null; liveRetries = 0 } }
            override fun onPlaybackStateChanged(state: Int) {
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
        onDispose { player.removeListener(listener); player.release() }
    }

    // Pantalla completa inmersiva + horizontal en teléfonos; PiP al salir con el botón Inicio
    DisposableEffect(Unit) {
        val win = activity.window
        val ctl = WindowCompat.getInsetsController(win, win.decorView)
        ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctl.hide(WindowInsetsCompat.Type.systemBars())
        val prevOrientation = activity.requestedOrientation
        if (!activity.isTv) activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        activity.pipEnabled = true
        onDispose {
            ctl.show(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = prevOrientation
            activity.pipEnabled = false
        }
    }

    // Pausar en segundo plano (salvo en PiP)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        var wasPlaying = false
        val obs = LifecycleEventObserver { _, ev ->
            when (ev) {
                Lifecycle.Event.ON_STOP -> { wasPlaying = player.playWhenReady || vlc?.isPlaying == true; player.pause(); vlc?.pause() }
                Lifecycle.Event.ON_START -> if (wasPlaying) { if (vlc != null) vlc?.resume() else player.play() }
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    // Mando a distancia: zapping, avance/retroceso, menú de opciones
    DisposableEffect(item.id, controllerVisible, engine) {
        activity.keyInterceptor = handler@{ ev ->
            val code = ev.keyCode
            if (useVlc) {
                // Motor VLC: controles propios con el mando
                val down = ev.action == KeyEvent.ACTION_DOWN
                return@handler when (code) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> {
                        if (down) { vlc?.togglePause(); bannerUntil = System.currentTimeMillis() + 4000 }; true
                    }
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (down) { if (live) vm.zap(if (code == KeyEvent.KEYCODE_DPAD_UP) -1 else 1) else { bannerUntil = System.currentTimeMillis() + 4000 } }; true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (down && !live) { vlc?.seekBy(if (code == KeyEvent.KEYCODE_DPAD_LEFT) -10_000 else 10_000); bannerUntil = System.currentTimeMillis() + 3000 }
                        !live
                    }
                    KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> { if (down) vm.zap(1); true }
                    KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> { if (down) vm.zap(-1); true }
                    KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BOOKMARK -> { if (ev.action == KeyEvent.ACTION_UP) showOptions = true; true }
                    KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_GUIDE -> { if (down) bannerUntil = System.currentTimeMillis() + 5000; true }
                    else -> false
                }
            }
            val handled = when (code) {
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> { if (ev.action == KeyEvent.ACTION_DOWN) vm.zap(1); true }
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> { if (ev.action == KeyEvent.ACTION_DOWN) vm.zap(-1); true }
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BOOKMARK -> { if (ev.action == KeyEvent.ACTION_UP) showOptions = true; true }
                KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_GUIDE -> {
                    if (ev.action == KeyEvent.ACTION_DOWN) { bannerUntil = System.currentTimeMillis() + 5000; playerView?.showController() }
                    true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (controllerVisible) false
                    else {
                        if (ev.action == KeyEvent.ACTION_DOWN) {
                            if (live) vm.zap(if (code == KeyEvent.KEYCODE_DPAD_UP) -1 else 1) else playerView?.showController()
                        }
                        true
                    }
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (controllerVisible || live) false
                    else {
                        if (ev.action == KeyEvent.ACTION_DOWN) {
                            val d = if (code == KeyEvent.KEYCODE_DPAD_LEFT) -10_000 else 10_000
                            player.seekTo((player.currentPosition + d).coerceAtLeast(0))
                            playerView?.showController()
                        }
                        true
                    }
                }
                else -> false
            }
            handled
        }
        onDispose { activity.keyInterceptor = null }
    }

    BackHandler {
        if (!useVlc && controllerVisible && playerView != null) playerView?.hideController() else vm.closePlayer()
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

    fun openInVlc() {
        val i = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(item.url), "video/*")
            setPackage("org.videolan.vlc")
            putExtra("title", item.name)
            subtitle?.let { putExtra("subtitles_location", it.absolutePath) }
        }
        try { player.pause(); vlc?.pause(); ctx.startActivity(i) }
        catch (e: ActivityNotFoundException) {
            try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=org.videolan.vlc"))) }
            catch (e2: ActivityNotFoundException) { vm.message = "VLC no está instalado" }
        }
    }

    // Reloj para el banner
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val showBanner = !activity.inPip && ((!useVlc && controllerVisible) || now < bannerUntil || error != null)
    val resize = when (vm.settings.aspect) {
        "fill", "zoom" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        "stretch" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val onAltEnded = {
            val cur = vm.playing
            if (vm.settings.autoNext && cur != null && cur.episode > 0 && vm.playIndex < vm.playList.size - 1) vm.zap(1)
        }
        if (engine == "vlc") {
            key(item.id, "vlc") {
                VlcVideo(
                    url = playUrl, userAgent = item.userAgent, referrer = item.referrer, aspect = vm.settings.aspect,
                    modifier = Modifier.fillMaxSize(),
                    onReady = { vlc = it },
                    onPlaying = { error = null },
                    // VLC tampoco pudo: en automático, último intento con MPV
                    onError = { msg -> if (vm.settings.engine == "auto" && android.os.Build.VERSION.SDK_INT >= 26) switchEngine("mpv", "Probando con el motor MPV…") else { error = msg } },
                    onEnded = onAltEnded,
                )
            }
            DisposableEffect(Unit) { onDispose { vlc = null } }
        } else if (engine == "mpv") {
            key(item.id, "mpv") {
                MpvVideo(
                    url = playUrl, userAgent = item.userAgent, referrer = item.referrer, aspect = vm.settings.aspect,
                    modifier = Modifier.fillMaxSize(),
                    onReady = { vlc = it },
                    onPlaying = { error = null },
                    onError = { error = it },
                    onEnded = onAltEnded,
                )
            }
            DisposableEffect(Unit) { onDispose { vlc = null } }
        } else AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                PlayerView(context).apply {
                    this.player = player
                    useController = true
                    controllerShowTimeoutMs = 4000
                    controllerHideOnTouch = true
                    setShowSubtitleButton(true)       // pistas de subtítulos internas (CC)
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    setShowFastForwardButton(!live)
                    setShowRewindButton(!live)
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    subtitleView?.setFractionalTextSize(androidx.media3.ui.SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * subtitleScale(vm.settings.subSize))
                    keepScreenOn = true
                    setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> controllerVisible = v == View.VISIBLE })
                    isFocusable = true
                    requestFocus()
                    playerView = this
                }
            },
            update = { v ->
                v.setShowFastForwardButton(!live)
                v.setShowRewindButton(!live)
                v.resizeMode = resize
            },
        )

        AnimatedVisibility(showBanner, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Row(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                    .padding(start = 24.dp, end = 12.dp, top = 16.dp, bottom = 36.dp),
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
                    error?.let {
                        Spacer(Modifier.height(6.dp))
                        Text("⚠ $it", color = Color(0xFFFFB4A9), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text(fmtTime(now), color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                    Text("Motor: ${engineName[engine]}", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
                }
                IconButton(onClick = { showOptions = true }) { Icon(Icons.Filled.MoreVert, "Opciones", tint = Color.White) }
            }
        }
    }

    if (showOptions) {
        Sheet("Opciones", onDismiss = { showOptions = false }) {
            val fav = item.id in vm.favorites
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton(if (fav) "★ Quitar de favoritos" else "☆ Añadir a favoritos", Modifier.fillMaxWidth()) { vm.toggleFavorite(item); showOptions = false }
                ActionButton("Subtítulos (OpenSubtitles / archivo)", Modifier.fillMaxWidth()) { showOptions = false; showSubs = true }
                // Cambiar de motor para este canal: ExoPlayer → VLC → MPV → ExoPlayer
                val nextEngine = when (engine) { "exo" -> "vlc"; "vlc" -> "mpv"; else -> "exo" }
                ActionButton("Motor: ${engineName[engine]} · cambiar a ${engineName[nextEngine]}", Modifier.fillMaxWidth(), ghost = true) {
                    engineFor = item.id to nextEngine; showOptions = false
                }
                val aspects = listOf("fit" to "Ajustar", "fill" to "Rellenar", "stretch" to "Estirar", "zoom" to "Zoom")
                val ai = aspects.indexOfFirst { it.first == vm.settings.aspect }.coerceAtLeast(0)
                ActionButton("Formato de pantalla: ${aspects[ai].second}", Modifier.fillMaxWidth(), ghost = true) {
                    vm.updateSettings(vm.settings.copy(aspect = aspects[(ai + 1) % aspects.size].first))
                }
                val tracks = vlc?.audioTracks().orEmpty()
                if (useVlc && tracks.size > 1) {
                    val ti = tracks.indexOfFirst { it.first == vlc?.audioTrack }.coerceAtLeast(0)
                    ActionButton("Audio: ${tracks[ti].second}", Modifier.fillMaxWidth(), ghost = true) {
                        vlc?.audioTrack = tracks[(ti + 1) % tracks.size].first
                        showOptions = false; vm.message = "Audio: ${tracks[(ti + 1) % tracks.size].second}"
                    }
                }
                ActionButton("Abrir en la app VLC", Modifier.fillMaxWidth(), ghost = true) { showOptions = false; openInVlc() }
                if (vm.playList.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton("‹ Anterior", Modifier.weight(1f), ghost = true) { vm.zap(-1); showOptions = false }
                    ActionButton("Siguiente ›", Modifier.weight(1f), ghost = true) { vm.zap(1); showOptions = false }
                }
                ActionButton("Volver al menú", Modifier.fillMaxWidth(), ghost = true) { showOptions = false; vm.closePlayer() }
            }
            Spacer(Modifier.height(8.dp))
            Text("Consejo: en el control, ▲/▼ cambia de canal, OK pausa y MENÚ abre estas opciones. Con ExoPlayer, las pistas de audio y subtítulos se eligen en los controles.", color = c.inkSoft, fontSize = 12.sp)
        }
    }

    if (showSubs) SubtitlesDialog(
        vm = vm, item = item, current = subtitle,
        onPick = { f -> val first = subtitle == null; subtitle = f; if (first) { showSubs = false; vm.message = "Subtítulo cargado" } },
        onFile = { showSubs = false; try { subsPicker.launch(arrayOf("*/*")) } catch (e: ActivityNotFoundException) { vm.message = "Este dispositivo no tiene selector de archivos" } },
        onDisable = { subtitle = null; showSubs = false },
        onDismiss = { showSubs = false },
    )
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
    var offset by remember { mutableStateOf(0L) }
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
            Text("Desfase del subtítulo: ${if (offset == 0L) "ninguno" else "%+.1f s".format(offset / 1000f)}", color = c.ink, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton("Aparecen antes (−0,5 s)", ghost = true) { offset -= 500; onPick(SubtitleSources.shift(current, -500, vm.subtitleDir())) }
                ActionButton("Aparecen después (+0,5 s)", ghost = true) { offset += 500; onPick(SubtitleSources.shift(current, 500, vm.subtitleDir())) }
            }
            Spacer(Modifier.height(14.dp))
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
                            .onSuccess { offset = 0; onPick(it) }
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
