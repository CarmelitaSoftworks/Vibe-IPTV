package cl.iptv.player.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cl.iptv.player.ui.theme.LocalIptv
import kotlinx.coroutines.delay

/** Botones del reproductor. */
enum class PAction(val label: String) {
    BACK("Volver"), PREV("Anterior"), REW("Retroceder 10 s"), PLAY("Reproducir / pausa"), FWD("Adelantar 10 s"), NEXT("Siguiente"),
    LIST("Lista de canales"), AUDIO("Audio"), SUBS("Subtítulos"), QUALITY("Calidad"), ASPECT("Formato de pantalla"),
    SPEED("Velocidad"), ENGINE("Motor de reproducción"), INFO("Información técnica"), FAV("Favorito"), PIP("Imagen en imagen"),
    EXTERNAL("Abrir en la app VLC"), RECORD("Grabar"), LASTCH("Canal anterior"), SLEEP("Temporizador para dormir"),
    MULTI("Multivista"),
}

/** Íconos dibujados (líneas simples, como los del menú). */
@Composable
fun PlayerIcon(a: PAction, tint: Color, modifier: Modifier = Modifier, on: Boolean = false) {
    Canvas(modifier) {
        val s = size.minDimension / 24f
        val w = 2f * s
        fun p(x: Float, y: Float) = Offset(x * s, y * s)
        fun line(vararg v: Float) { for (i in 0 until v.size - 2 step 2) drawLine(tint, p(v[i], v[i + 1]), p(v[i + 2], v[i + 3]), w, StrokeCap.Round) }
        fun poly(fill: Boolean, vararg v: Float) {
            val path = Path().apply { moveTo(v[0] * s, v[1] * s); for (i in 2 until v.size step 2) lineTo(v[i] * s, v[i + 1] * s); close() }
            if (fill) drawPath(path, tint) else drawPath(path, tint, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        fun rect(x: Float, y: Float, rw: Float, rh: Float, r: Float = 2.5f, fill: Boolean = false) =
            drawRoundRect(tint, p(x, y), Size(rw * s, rh * s), CornerRadius(r * s), style = if (fill) androidx.compose.ui.graphics.drawscope.Fill else Stroke(w))
        when (a) {
            PAction.BACK -> line(15f, 5f, 8f, 12f, 15f, 19f)
            PAction.PREV -> { line(6f, 6f, 6f, 18f); poly(true, 18f, 6f, 9f, 12f, 18f, 18f) }
            PAction.NEXT -> { line(18f, 6f, 18f, 18f); poly(true, 6f, 6f, 15f, 12f, 6f, 18f) }
            PAction.REW -> { poly(true, 12f, 7f, 4f, 12f, 12f, 17f); poly(true, 20f, 7f, 12f, 12f, 20f, 17f) }
            PAction.FWD -> { poly(true, 4f, 7f, 12f, 12f, 4f, 17f); poly(true, 12f, 7f, 20f, 12f, 12f, 17f) }
            PAction.PLAY -> if (on) { rect(6.5f, 5f, 4f, 14f, 1f, true); rect(13.5f, 5f, 4f, 14f, 1f, true) } else poly(true, 8f, 5f, 19f, 12f, 8f, 19f)
            PAction.LIST -> { line(9f, 7f, 20f, 7f); line(9f, 12f, 20f, 12f); line(9f, 17f, 20f, 17f); listOf(7f, 12f, 17f).forEach { drawCircle(tint, 1.3f * s, p(4.5f, it)) } }
            PAction.AUDIO -> { poly(false, 4f, 9.5f, 8f, 9.5f, 12.5f, 5.5f, 12.5f, 18.5f, 8f, 14.5f, 4f, 14.5f); drawArc(tint, -50f, 100f, false, p(11f, 8f), Size(7f * s, 8f * s), style = Stroke(w, cap = StrokeCap.Round)); drawArc(tint, -55f, 110f, false, p(11f, 4.5f), Size(10f * s, 15f * s), style = Stroke(w, cap = StrokeCap.Round)) }
            PAction.SUBS -> { rect(3f, 5f, 18f, 14f); line(7f, 13f, 10f, 13f); line(12.5f, 13f, 17f, 13f); line(7f, 16f, 14f, 16f) }
            PAction.QUALITY -> { line(4f, 7f, 20f, 7f); line(4f, 12f, 20f, 12f); line(4f, 17f, 20f, 17f); drawCircle(Color.Black, 2.4f * s, p(9f, 7f)); drawCircle(tint, 2.4f * s, p(9f, 7f), style = Stroke(w)); drawCircle(Color.Black, 2.4f * s, p(15f, 12f)); drawCircle(tint, 2.4f * s, p(15f, 12f), style = Stroke(w)); drawCircle(Color.Black, 2.4f * s, p(7f, 17f)); drawCircle(tint, 2.4f * s, p(7f, 17f), style = Stroke(w)) }
            PAction.ASPECT -> { rect(3f, 6f, 18f, 12f); line(7f, 12f, 7f, 9f, 10f, 9f); line(17f, 12f, 17f, 15f, 14f, 15f) }
            PAction.SPEED -> { drawArc(tint, 150f, 240f, false, p(3f, 4f), Size(18f * s, 18f * s), style = Stroke(w, cap = StrokeCap.Round)); line(12f, 13f, 16f, 8.5f); drawCircle(tint, 1.5f * s, p(12f, 13f)) }
            PAction.ENGINE -> { rect(7f, 7f, 10f, 10f, 1.5f); rect(10f, 10f, 4f, 4f, 0.8f, true); listOf(9.5f, 12f, 14.5f).forEach { x -> line(x, 4f, x, 7f); line(x, 17f, x, 20f); line(4f, x, 7f, x); line(17f, x, 20f, x) } }
            PAction.INFO -> { drawCircle(tint, 9f * s, p(12f, 12f), style = Stroke(w)); line(12f, 11f, 12f, 16.5f); drawCircle(tint, 1.3f * s, p(12f, 7.8f)) }
            PAction.FAV -> poly(on, 12f, 3.5f, 14.6f, 9f, 20.5f, 9.6f, 16f, 13.6f, 17.3f, 19.5f, 12f, 16.4f, 6.7f, 19.5f, 8f, 13.6f, 3.5f, 9.6f, 9.4f, 9f)
            PAction.PIP -> { rect(3f, 5f, 18f, 14f); rect(12f, 11.5f, 7f, 5.5f, 1f, true) }
            PAction.RECORD -> { drawCircle(tint, 9f * s, p(12f, 12f), style = Stroke(w)); drawCircle(if (on) Color(0xFFE53935) else tint, 5f * s, p(12f, 12f)) }
            PAction.EXTERNAL -> { line(13f, 4f, 20f, 4f, 20f, 11f); line(20f, 4f, 11f, 13f); line(17f, 14f, 17f, 19f, 5f, 19f, 5f, 7f, 10f, 7f) }
            PAction.LASTCH -> { drawArc(tint, 200f, 250f, false, p(4f, 4f), Size(16f * s, 16f * s), style = Stroke(w, cap = StrokeCap.Round)); poly(true, 2.5f, 8.5f, 7.5f, 9.5f, 4f, 13.5f) }
            PAction.SLEEP -> { drawArc(tint, 60f, 250f, false, p(4f, 4f), Size(16f * s, 16f * s), style = Stroke(w, cap = StrokeCap.Round)); line(12f, 8f, 12f, 12f, 15f, 14f) }
            PAction.MULTI -> { rect(3f, 5f, 8.2f, 6.2f, 1.5f); rect(12.8f, 5f, 8.2f, 6.2f, 1.5f); rect(3f, 12.8f, 8.2f, 6.2f, 1.5f); rect(12.8f, 12.8f, 8.2f, 6.2f, 1.5f) }
        }
    }
}

@Composable
private fun CtlButton(
    a: PAction, size: Int = 48, on: Boolean = false, modifier: Modifier = Modifier,
    onFocused: (PAction) -> Unit, onClick: () -> Unit,
) {
    val c = LocalIptv.current
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) onFocused(a) }
    Box(
        modifier.size(size.dp).clip(CircleShape)
            .background(if (focused) Color.White else if (a == PAction.PLAY) Color.White.copy(alpha = 0.18f) else Color.Transparent)
            .border(if (focused) 3.dp else 0.dp, if (focused) c.focus else Color.Transparent, CircleShape)
            .clickable(interactionSource = src, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        PlayerIcon(a, if (focused) Color(0xFF1A1F23) else Color.White, Modifier.size((size * 0.5f).dp), on)
    }
}

fun fmtDur(ms: Long): String {
    val t = (ms / 1000).coerceAtLeast(0); val h = t / 3600; val m = (t % 3600) / 60; val s = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * Barra inferior del reproductor: progreso (películas y series) y todos los botones.
 * Funciona con el dedo y con el control remoto (▲▼◄► y OK).
 */
@Composable
fun PlayerControlsBar(
    ctl: PlaybackControl?,
    live: Boolean,
    hasList: Boolean,
    fav: Boolean,
    showQuality: Boolean,
    recording: Boolean = false,
    playFocus: FocusRequester,
    onAction: (PAction) -> Unit,
    onInteract: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(500); tick++ } }
    var caption by remember { mutableStateOf<PAction?>(null) }
    val focused: (PAction) -> Unit = { caption = it; onInteract() }
    @Suppress("UNUSED_EXPRESSION") tick
    val playing = ctl?.isPlaying ?: false
    val pos = ctl?.position ?: 0L
    val dur = ctl?.duration ?: -1L

    Column(
        modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xD9000000))))
            .padding(start = 20.dp, end = 20.dp, top = 40.dp, bottom = 14.dp),
    ) {
        if (!live && dur > 0) {
            var dragging by remember { mutableStateOf<Float?>(null) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(fmtDur(dragging?.let { (it * dur).toLong() } ?: pos), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Slider(
                    value = dragging ?: (pos.toFloat() / dur).coerceIn(0f, 1f),
                    onValueChange = { dragging = it; onInteract() },
                    onValueChangeFinished = { dragging?.let { ctl?.seekTo((it * dur).toLong()) }; dragging = null },
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = LocalIptv.current.accent, inactiveTrackColor = Color.White.copy(alpha = 0.3f)),
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp).focusProperties { canFocus = false },
                )
                Text(fmtDur(dur), color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        } else if (live) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                Box(Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFFD9363E)).padding(horizontal = 8.dp, vertical = 2.dp)) {
                    Text("EN VIVO", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
        }
        Text(caption?.label ?: " ", color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CtlButton(PAction.BACK, onFocused = focused) { onAction(PAction.BACK) }
            Spacer(Modifier.width(8.dp))
            if (hasList) CtlButton(PAction.PREV, onFocused = focused) { onAction(PAction.PREV) }
            if (!live) CtlButton(PAction.REW, onFocused = focused) { onAction(PAction.REW) }
            CtlButton(PAction.PLAY, size = 60, on = playing, modifier = Modifier.focusRequester(playFocus), onFocused = focused) { onAction(PAction.PLAY) }
            if (!live) CtlButton(PAction.FWD, onFocused = focused) { onAction(PAction.FWD) }
            if (hasList) CtlButton(PAction.NEXT, onFocused = focused) { onAction(PAction.NEXT) }
            Spacer(Modifier.width(12.dp))
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.End) {
                val extra = buildList {
                    if (hasList) add(PAction.LIST)
                    add(PAction.AUDIO); add(PAction.SUBS)
                    if (showQuality) add(PAction.QUALITY)
                    add(PAction.ASPECT)
                    if (!live) add(PAction.SPEED)
                    if (live) add(PAction.RECORD)
                    if (live && hasList) { add(PAction.LASTCH); add(PAction.MULTI) }
                    add(PAction.SLEEP)
                    add(PAction.ENGINE); add(PAction.INFO); add(PAction.FAV); add(PAction.PIP); add(PAction.EXTERNAL)
                }
                extra.forEach { a -> CtlButton(a, on = (a == PAction.FAV && fav) || (a == PAction.RECORD && recording), onFocused = focused) { onAction(a) } }
            }
        }
    }
}

/** Una fila de un panel lateral de opciones. */
data class PanelRow(
    val label: String, val selected: Boolean = false, val detail: String = "",
    /** Lista de canales: logo y avance del programa actual (0..1, o -1 si no hay guía). */
    val logo: String = "", val progress: Float = -1f,
    val onClick: () -> Unit,
)

/** Panel lateral (audio, subtítulos, calidad, formato, velocidad, motor, lista de canales, información). */
@Composable
fun PlayerSidePanel(title: String, rows: List<PanelRow>, onClose: () -> Unit, modifier: Modifier = Modifier, wide: Boolean = false) {
    val c = LocalIptv.current
    val first = remember { FocusRequester() }
    val start = rows.indexOfFirst { it.selected }.coerceAtLeast(0)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (start - 2).coerceAtLeast(0))
    LaunchedEffect(title) { delay(60); runCatching { first.requestFocus() } }
    Column(
        modifier.fillMaxHeight().then(if (wide) Modifier.widthIn(min = 300.dp, max = 620.dp).fillMaxWidth(0.62f) else Modifier.widthIn(max = 380.dp).fillMaxWidth(0.42f))
            .background(Color(0xF2161A1D)).padding(vertical = 18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 20.dp)) {
            Text(title, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, modifier = Modifier.weight(1f))
            Text("✕", color = Color.White.copy(alpha = 0.7f), fontSize = 20.sp, modifier = Modifier.clip(CircleShape).clickable { onClose() }.padding(8.dp))
        }
        Spacer(Modifier.height(10.dp))
        if (rows.isEmpty()) Text("No hay opciones para este canal.", color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(horizontal = 20.dp))
        LazyColumn(state = state) {
            itemsIndexed(rows) { i, r ->
                val src = remember { MutableInteractionSource() }
                val f by src.collectIsFocusedAsState()
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (f) Color.White else Color.Transparent)
                        .then(if (i == start) Modifier.focusRequester(first) else Modifier)
                        .clickable(interactionSource = src, indication = null, onClick = r.onClick)
                        .padding(horizontal = 12.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                        if (r.selected) Box(Modifier.size(10.dp).clip(CircleShape).background(if (f) c.accentDeep else c.accent))
                    }
                    Spacer(Modifier.width(10.dp))
                    if (wide) {
                        Box(Modifier.size(width = 52.dp, height = 36.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = if (f) 0f else 0.08f)), contentAlignment = Alignment.Center) {
                            if (r.logo.isNotBlank()) coil.compose.AsyncImage(r.logo, null, contentScale = androidx.compose.ui.layout.ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(3.dp))
                            else Text(initials(r.label), color = if (f) Color(0xFF5D666C) else Color.White.copy(alpha = 0.6f), fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                        }
                        Spacer(Modifier.width(12.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(r.label, color = if (f) Color(0xFF1A1F23) else Color.White, fontWeight = if (r.selected) FontWeight.ExtraBold else FontWeight.SemiBold,
                            fontSize = 15.sp, maxLines = if (wide) 2 else 1, overflow = TextOverflow.Ellipsis, lineHeight = 18.sp)
                        if (r.detail.isNotBlank()) Text(r.detail, color = if (f) Color(0xFF5D666C) else Color.White.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (r.progress >= 0f) Box(Modifier.padding(top = 4.dp).fillMaxWidth(0.6f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.2f))) {
                            Box(Modifier.fillMaxWidth(r.progress).fillMaxHeight().background(c.accent))
                        }
                    }
                }
            }
        }
    }
}
