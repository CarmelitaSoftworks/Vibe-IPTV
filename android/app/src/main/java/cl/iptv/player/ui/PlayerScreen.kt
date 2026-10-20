package cl.iptv.player.ui

import android.app.Activity
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
    val useAlt = engine != "exo"          // motor alternativo (VLC o MPV) en vez de ExoPlayer
    var alt by remember { mutableStateOf<AltEngine?>(null) }
    var reloadKey by remember { mutableStateOf(0) }
    var liveRetries by remember { mutableStateOf(0) }
    // true cuando el motor actual ya mostró imagen o sonido (lo usa el vigilante de arranque)
    var started by remember { mutableStateOf(false) }
    fun switchEngine(to: String, reason: String) {
        val it0 = currentItem
        if (engineFor?.first == it0.id && engineFor?.second == to) return
        engineFor = it0.id to to
        if (it0.kind == Kind.LIVE) vm.preferLiveEngine = to
        vm.logEvent("Cambio de motor → $to (${it0.name}): $reason")
        vm.message = reason
    }
    val engineName = mapOf("exo" to "ExoPlayer", "vlc" to "VLC", "mpv" to "MPV")
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
    val bumpTick = remember { mutableStateOf(0) }                         // cambia cuando se sube el búfer de un canal
    val bufLevel = remember(item.id, bumpTick.value) { vm.bufferLevel(item) }
    val memMb = remember { (ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager).memoryClass }
    val buf = cl.iptv.player.data.BufferProfile.of(bufLevel, live, memMb)
    val rebuffers = remember { mutableStateMapOf<String, Int>() }      // veces que cada canal se quedó cargando
    fun countRebuffer(it0: Item) {
        val n = (rebuffers[it0.id] ?: 0) + 1
        rebuffers[it0.id] = n
        if (n >= 3 && it0.kind == Kind.LIVE) {
            rebuffers[it0.id] = 0
            if (vm.bumpBuffer(it0)) bumpTick.value++
        }
    }
    val player = remember(bufLevel, live) {
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
        val renderers = androidx.media3.exoplayer.DefaultRenderersFactory(ctx)
            .setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
        ExoPlayer.Builder(ctx, renderers).setLoadControl(load).setLivePlaybackSpeedControl(liveSpeed).build().apply {
            playWhenReady = true
            setHandleAudioBecomingNoisy(true)
        }
    }
    val exo = remember(player) { ExoControl(player) }
    val ctl: PlaybackControl? = if (useAlt) alt else exo

    // Cargar el elemento actual (o recargar al añadir subtítulos conservando la posición)
    LaunchedEffect(item.id, subtitle, playUrl, engine, reloadKey, player) {
        error = null
        if (useAlt) { player.stop(); player.clearMediaItems(); bannerUntil = System.currentTimeMillis() + 4500; return@LaunchedEffect }
        val saved = if (item.kind != Kind.LIVE) vm.resumeFor(item) else null
        val pos = if (subtitle != null && player.currentMediaItem?.mediaId == item.id) player.currentPosition
            else if (saved != null && player.currentMediaItem?.mediaId != item.id) { vm.message = "Continuando desde ${fmtDur(saved.pos)}"; saved.pos }
            else C.TIME_UNSET
        val http = OkHttpDataSource.Factory(Http.streamClient)
            .setUserAgent(item.userAgent.ifBlank { Http.DEFAULT_UA })
            .apply { if (item.referrer.isNotBlank()) setDefaultRequestProperties(mapOf("Referer" to item.referrer)) }
        val factory = DefaultMediaSourceFactory(DefaultDataSource.Factory(ctx, http))
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
            engine == "exo" && auto -> switchEngine("vlc", "El canal no responde: probando con el motor VLC…")
            engine == "vlc" && auto && android.os.Build.VERSION.SDK_INT >= 26 ->
                switchEngine("mpv", "El canal no responde: probando con el motor MPV…")
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
    var userPaused by remember(item.id) { mutableStateOf(false) }
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
            val next = when (engine) { "exo" -> "vlc"; "vlc" -> if (android.os.Build.VERSION.SDK_INT >= 26) "mpv" else null; else -> null }
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
            val names = listOf(java.util.Locale(want).getDisplayLanguage(java.util.Locale("es")), java.util.Locale(want).getDisplayLanguage(java.util.Locale.ENGLISH))
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
        onDispose { player.removeListener(listener); player.release() }
    }

    // Al salir del elemento (otro canal o volver al menú): guardar dónde quedó
    DisposableEffect(item.id, engine, player) {
        onDispose {
            // Sólo ExoPlayer: VLC y MPV ya se cerraron a esta altura (su posición se guarda cada 10 s)
            if (!live && !useAlt) runCatching { vm.savePosition(item, exo.position, exo.duration) }
        }
    }

    // Pantalla completa inmersiva + horizontal en teléfonos; PiP al salir con el botón Inicio
    DisposableEffect(Unit) {
        val win = activity.window
        val wc = WindowCompat.getInsetsController(win, win.decorView)
        wc.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        wc.hide(WindowInsetsCompat.Type.systemBars())
        val prevOrientation = activity.requestedOrientation
        if (!activity.isTv) activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
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

    // Mando a distancia. Con los controles a la vista, ▲▼◄► y OK se mueven entre los botones;
    // ocultos: ▲▼ cambia de canal (en vivo), ◄► avanza o retrocede, OK muestra los controles.
    DisposableEffect(item.id, engine, player, vm.mini) {
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
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if (down && !live) { ctlOf(useAlt, alt, exo)?.seekBy(30_000); poke() }; return@handler true }
                KeyEvent.KEYCODE_MEDIA_REWIND -> { if (down && !live) { ctlOf(useAlt, alt, exo)?.seekBy(-10_000); poke() }; return@handler true }
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
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE -> { if (down) poke(); true }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (down) { if (live) vm.zap(if (code == KeyEvent.KEYCODE_DPAD_UP) -1 else 1) else poke() }; true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (down) {
                        if (live) poke()
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

    fun onAction(a: PAction) {
        poke()
        val k = ctl
        when (a) {
            PAction.BACK -> vm.closePlayer()
            PAction.PREV -> vm.zap(-1)
            PAction.NEXT -> vm.zap(1)
            PAction.REW -> k?.seekBy(-10_000)
            PAction.FWD -> k?.seekBy(10_000)
            PAction.PLAY -> { userPaused = k?.isPlaying == true; k?.togglePause() }
            PAction.FAV -> {
                val wasFav = item.id in vm.favorites
                vm.toggleFavorite(item)
                vm.message = if (wasFav) "${item.name} quitado de favoritos" else "★ ${item.name} añadido a favoritos"
            }
            // En TV: mini reproductor dentro de la app para recorrer el menú y la guía; en celulares, el PiP del sistema
            PAction.PIP -> if (activity.isTv || !activity.enterPip()) vm.enterMini()
            PAction.EXTERNAL -> openInVlc()
            PAction.RECORD -> if (vm.recording?.item?.id == item.id) vm.stopRecording() else vm.startRecording(item)
            PAction.LASTCH -> vm.previousChannel()
            PAction.MULTI -> vm.openMultiview(item)
            else -> panel = a
        }
    }

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
                    modifier = Modifier.fillMaxSize(),
                    onReady = { alt = it },
                    onPlaying = { started = true; error = null },
                    // VLC tampoco pudo: en automático, último intento con MPV
                    onError = { msg -> if (vm.settings.engine == "auto" && android.os.Build.VERSION.SDK_INT >= 26) switchEngine("mpv", "Probando con el motor MPV…") else { error = msg } },
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
            update = { v -> v.resizeMode = resize; if (v.player !== player) v.player = player },
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
        // Doble toque: indicador ±10 s
        if (seekFlash.first != 0 && System.currentTimeMillis() - seekFlash.second < 700) {
            LaunchedEffect(seekFlash) { delay(720); seekFlash = 0 to 0L }
            Text(if (seekFlash.first < 0) "«  10 s" else "10 s  »", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp,
                modifier = Modifier.align(if (seekFlash.first < 0) Alignment.CenterStart else Alignment.CenterEnd).padding(horizontal = 60.dp)
                    .clip(RoundedCornerShape(30.dp)).background(Color(0x99000000)).padding(horizontal = 18.dp, vertical = 10.dp))
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
                    Text("Motor: ${engineName[engine]}", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
                }
            }
        }

        // Error: qué pasó y qué se puede hacer, sin salir del reproductor
        if (vm.mini) {
            // Mini reproductor: tocarlo vuelve a pantalla completa
            Box(Modifier.fillMaxSize().clickable { vm.expandMini() }) {
                Text("⤢", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x99000000)).padding(horizontal = 6.dp))
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
                    val other = if (engine == "vlc") "mpv" else if (engine == "mpv") "exo" else "vlc"
                    if (other != "mpv" || android.os.Build.VERSION.SDK_INT >= 26)
                        ActionButton("Probar con ${engineName[other]}", ghost = true) { error = null; engineFor = item.id to other }
                }
            }
        }

        AnimatedVisibility(controls && panel == null && !activity.inPip && !vm.mini, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            PlayerControlsBar(
                ctl = ctl, live = live, hasList = vm.playList.size > 1, fav = item.id in vm.favorites,
                showQuality = !useAlt && exo.qualities().isNotEmpty(),
                recording = vm.recording?.item?.id == item.id,
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
                }
                PAction.SLEEP -> listOf(0, 15, 30, 60, 90, 120).map { m ->
                    val left = if (vm.sleepAt > 0) ((vm.sleepAt - now) / 60_000 + 1) else 0
                    PanelRow(if (m == 0) "Desactivado" else "$m min", if (m == 0) vm.sleepAt == 0L else false,
                        if (m == 0 && vm.sleepAt > 0) "Quedan $left min" else "") { vm.setSleep(m); panel = null }
                }
                PAction.INFO -> (listOf("Motor" to engineName[engine].orEmpty()) + k?.info().orEmpty() +
                    listOfNotNull(Uri.parse(playUrl).host?.let { "Servidor del canal" to it })).map { (l, v) -> PanelRow(l, detail = v) {} }
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
