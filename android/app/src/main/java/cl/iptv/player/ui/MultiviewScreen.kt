package cl.iptv.player.ui

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import cl.iptv.player.data.Http
import cl.iptv.player.data.Item
import cl.iptv.player.ui.theme.LocalIptv
import kotlinx.coroutines.delay

/**
 * Multivista: hasta 4 canales en vivo a la vez (ExoPlayer, búfer corto). Sólo suena el canal elegido.
 * OK / toque: ese canal suena · OK otra vez: pantalla completa · Menú / mantener: cambiar canal.
 */
@OptIn(UnstableApi::class)
@Composable
fun MultiviewScreen(vm: AppViewModel) {
    val slots = vm.multiview ?: return
    var audio by remember { mutableStateOf(0) }
    var picking by remember { mutableStateOf<Int?>(null) }      // índice del recuadro a cambiar (slots.size = añadir)
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(150); runCatching { first.requestFocus() } }
    BackHandler { vm.closeMultiview(null) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val rows = if (slots.size <= 2) listOf(slots.indices.toList()) else slots.indices.chunked(2)
        Column(Modifier.fillMaxSize()) {
            rows.forEach { r ->
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    r.forEach { i ->
                        key(i, slots[i].id) {
                            MultiTile(
                                item = slots[i], sound = audio == i,
                                modifier = Modifier.weight(1f).fillMaxHeight().then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                                onFocus = { audio = i },
                                onClick = { if (audio == i) vm.closeMultiview(slots[i]) else audio = i },
                                onMenu = { picking = i },
                            )
                        }
                    }
                    if (r.size == 1 && slots.size > 2) Spacer(Modifier.weight(1f))
                }
            }
        }
        Row(
            Modifier.align(Alignment.BottomCenter).padding(10.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xB3000000))
                .padding(horizontal = 14.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("OK: sonido · OK otra vez: pantalla completa · Menú o mantener: cambiar canal", color = Color.White, fontSize = 12.sp)
            if (slots.size < 4) Pill("＋ Añadir") { picking = slots.size }
            if (slots.size > 2) Pill("− Quitar") { vm.setMultiviewSlot(audio, null); audio = 0 }
        }
    }

    picking?.let { slot ->
        var q by remember { mutableStateOf("") }
        val all = vm.playList
        val found = remember(q, all) { val n = q.trim().lowercase(); all.filter { n.isEmpty() || it.name.lowercase().contains(n) }.take(80) }
        Sheet(if (slot >= slots.size) "Añadir canal" else "Cambiar canal", onDismiss = { picking = null }, wide = true) {
            Field(q, { q = it }, "Buscar canal")
            Spacer(Modifier.height(8.dp))
            found.forEach { it2 ->
                ActionButton(it2.name, Modifier.fillMaxWidth().padding(vertical = 2.dp), ghost = true) {
                    vm.setMultiviewSlot(slot, it2); picking = null
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MultiTile(item: Item, sound: Boolean, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit, onMenu: () -> Unit) {
    val ctx = LocalContext.current
    val c = LocalIptv.current
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    var failed by remember { mutableStateOf(false) }
    val player = remember(item.id) {
        val load = androidx.media3.exoplayer.DefaultLoadControl.Builder().setBufferDurationsMs(4_000, 15_000, 1_500, 3_000).build()
        val renderers = androidx.media3.exoplayer.DefaultRenderersFactory(ctx)
            .setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
        ExoPlayer.Builder(ctx, renderers).setLoadControl(load).build().apply {
            val http = OkHttpDataSource.Factory(Http.streamClient).setUserAgent(item.userAgent.ifBlank { Http.DEFAULT_UA })
                .apply { if (item.referrer.isNotBlank()) setDefaultRequestProperties(mapOf("Referer" to item.referrer)) }
            setMediaSource(DefaultMediaSourceFactory(DefaultDataSource.Factory(ctx, http)).createMediaSource(androidx.media3.common.MediaItem.fromUri(item.url)))
            addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) { failed = true }
            })
            playWhenReady = true
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(sound) { player.volume = if (sound) 1f else 0f }

    Box(
        modifier.padding(2.dp)
            .border(if (focused || sound) 3.dp else 1.dp, if (focused) c.focus else if (sound) c.accent else Color(0x33FFFFFF))
            .onFocusChanged { if (it.isFocused) onFocus() }
            .onPreviewKeyEvent { e ->
                val k = e.nativeKeyEvent
                if (k.keyCode == KeyEvent.KEYCODE_MENU && k.action == KeyEvent.ACTION_UP) { onMenu(); true } else false
            }
            .combinedClickable(interactionSource = src, indication = null, onClick = onClick, onLongClick = onMenu),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                PlayerView(context).apply {
                    this.player = player; useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT; keepScreenOn = true
                    isFocusable = false; isFocusableInTouchMode = false
                }
            },
            update = { it.player = player },
        )
        Text(
            (if (sound) "🔊 " else "") + item.name + (if (failed) " · no disponible" else ""),
            color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x99000000))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}
