package cl.iptv.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cl.iptv.player.data.Item
import cl.iptv.player.data.Programme
import cl.iptv.player.data.catchupUrlFor
import cl.iptv.player.ui.theme.LocalIptv
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

private const val HALF_HOUR = 30 * 60_000L

/**
 * Guía de programación en grilla: canales a la izquierda y programas en una línea de tiempo.
 * Con el control: ▲▼ cambia de canal, ◄► recorre los programas, OK ve el canal (o el programa en diferido).
 */
@Composable
fun GuideScreen(vm: AppViewModel) {
    val c = LocalIptv.current
    val compact = LocalConfiguration.current.screenHeightDp < 500
    val channels = remember(vm.view, vm.items) { vm.guideChannels() }
    val sh = vm.epgShift
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    // Ventana: desde 2 h atrás (para el diferido) hasta 12 h adelante, en medias horas
    val winStart = remember { (System.currentTimeMillis() - 2 * 3600_000L).let { it - it % HALF_HOUR } }
    val winEnd = winStart + 14 * 3600_000L
    val dpPerMin: Dp = if (compact) 3.2.dp else 4.dp
    val chanW: Dp = if (compact) 150.dp else 210.dp
    val rowH: Dp = if (compact) 52.dp else 64.dp
    val hScroll = rememberScrollState()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val firstFocus = remember { FocusRequester() }
    var focused by remember { mutableStateOf<Pair<Item, Programme?>?>(null) }
    var detail by remember { mutableStateOf<Pair<Item, Programme>?>(null) }
    var searching by remember { mutableStateOf(false) }
    // Grabaciones programadas (se vuelve a leer al programar o cancelar una)
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var recTick by remember { mutableStateOf(0) }
    val scheduled = remember(recTick) { cl.iptv.player.data.Schedules.pending(ctx) }
    var showScheduled by remember { mutableStateOf(false) }

    BackHandler { if (detail != null) detail = null else if (searching) searching = false else if (showScheduled) showScheduled = false else vm.showGuide = false }

    // Empezar con "ahora" a la vista y el foco en el programa actual del primer canal
    LaunchedEffect(Unit) {
        delay(80)
        // media hora antes de "ahora" queda en el borde izquierdo
        val px = with(density) { ((now - winStart - HALF_HOUR) / 60_000f * dpPerMin.value).coerceAtLeast(0f).dp.toPx() }
        hScroll.scrollTo(px.toInt().coerceAtMost(hScroll.maxValue))
        runCatching { firstFocus.requestFocus() }
    }

    fun listFor(ch: Item) = if (channels.any { it.id == ch.id }) channels else listOf(ch)
    fun open(ch: Item, p: Programme?) {
        val playingNow = p == null || (p.start + sh <= now && p.stop + sh > now)
        if (playingNow) vm.activate(ch, listFor(ch)) else detail = ch to p
    }

    Column(Modifier.fillMaxSize().themeBackground().systemBarsPadding()) {
        // Encabezado
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = if (compact) 6.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill("‹ Volver") { vm.showGuide = false }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Guía de programación", color = c.ink, fontWeight = FontWeight.ExtraBold, fontSize = if (compact) 18.sp else 22.sp)
                Text("${channels.size} canales" + if (vm.category != CAT_ALL && vm.section == cl.iptv.player.data.Kind.LIVE) " · ${vm.title()}" else "",
                    color = c.accentDeep, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            if (scheduled.isNotEmpty()) { Pill("● Programadas · ${scheduled.size}") { showScheduled = true }; Spacer(Modifier.width(10.dp)) }
            Pill("🔍 Buscar programa") { searching = true }
            Spacer(Modifier.width(14.dp))
            Text(fmtTime(now), color = c.ink, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
        }

        // Programa enfocado
        Box(Modifier.fillMaxWidth().height(if (compact) 46.dp else 62.dp).padding(horizontal = 24.dp)) {
            focused?.let { (ch, p) ->
                Column {
                    Text(
                        (p?.title ?: "Sin guía") + "  ·  " + ch.name + (p?.let { "  ·  ${fmtTime(it.start + sh)}–${fmtTime(it.stop + sh)}" } ?: ""),
                        color = c.ink, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    val extra = when {
                        p == null -> ""
                        p.start + sh <= now && p.stop + sh > now -> "▶ En emisión ahora · OK para ver"
                        p.stop + sh <= now && ch.catchupUrlFor(p.copy(start = p.start + sh, stop = p.stop + sh), now) != null -> "⏪ Disponible en diferido · OK para verlo desde el inicio"
                        else -> ""
                    }
                    Text(listOf(extra, p?.desc.orEmpty()).filter { it.isNotBlank() }.joinToString("  ·  "),
                        color = c.inkSoft, fontSize = 13.sp, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        // Regla de horas
        Row(Modifier.fillMaxWidth().height(26.dp).padding(end = 8.dp)) {
            Spacer(Modifier.width(chanW + 22.dp))
            Box(Modifier.weight(1f).horizontalScroll(hScroll, enabled = false)) {
                Box(Modifier.width(((winEnd - winStart) / 60_000f * dpPerMin.value).dp).fillMaxHeight()) {
                    var t = winStart
                    while (t < winEnd) {
                        Text(fmtTime(t + 0), color = c.inkSoft, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                            modifier = Modifier.offset(x = ((t - winStart) / 60_000f * dpPerMin.value).dp + 4.dp))
                        t += HALF_HOUR
                    }
                    NowLine(now, winStart, dpPerMin, c.accent)
                }
            }
        }

        if (channels.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No hay canales en esta categoría.", color = c.inkSoft) }
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize().padding(start = 16.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(channels, key = { i, ch -> "$i:${ch.id}" }) { i, ch ->
                GuideRow(
                    ch = ch, progs = remember(vm.epg, ch.id) { vm.epg.listFor(ch) }, sh = sh, now = now,
                    winStart = winStart, winEnd = winEnd, dpPerMin = dpPerMin, chanW = chanW, rowH = rowH,
                    hScroll = hScroll, firstFocus = if (i == 0) firstFocus else null,
                    onFocus = { p -> focused = ch to p }, onOpen = { p -> open(ch, p) },
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    // Buscar un programa por su nombre en toda la guía (todos los canales en vivo)
    if (searching) {
        var q by remember { mutableStateOf("") }
        var hits by remember { mutableStateOf<List<Pair<Item, Programme>>>(emptyList()) }
        val fr = remember { FocusRequester() }
        LaunchedEffect(Unit) { delay(80); runCatching { fr.requestFocus() } }
        LaunchedEffect(q) {
            delay(300)
            val n = cl.iptv.player.data.Epg.norm(q)
            hits = if (n.length < 2) emptyList() else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                val from = System.currentTimeMillis() - sh - 3 * 86_400_000L
                vm.items.asSequence().filter { it.kind == cl.iptv.player.data.Kind.LIVE }
                    .flatMap { ch -> vm.epg.listFor(ch).asSequence().filter { it.stop > from && cl.iptv.player.data.Epg.norm(it.title).contains(n) }.map { ch to it } }
                    .distinctBy { (ch, p) -> ch.id + p.start }
                    .take(400).toList().sortedBy { it.second.start }.take(80)
            }
        }
        val day = remember { java.text.SimpleDateFormat("EEE d", java.util.Locale.getDefault()) }
        Sheet("Buscar en la guía", onDismiss = { searching = false }, wide = true) {
            Field(q, { q = it }, "Nombre del programa", modifier = Modifier.focusRequester(fr))
            Spacer(Modifier.height(8.dp))
            if (q.length >= 2 && hits.isEmpty()) Text("Sin resultados en la guía cargada.", color = c.inkSoft, fontSize = 13.sp)
            hits.forEach { (ch, p) ->
                val s = p.start + sh; val e = p.stop + sh
                val tag = when { s <= now && e > now -> "▶ AHORA"; e <= now -> "⏪"; else -> day.format(java.util.Date(s)) }
                ActionButton("$tag ${fmtTime(s)} · ${p.title} — ${ch.name}", Modifier.fillMaxWidth().padding(vertical = 2.dp), ghost = true) {
                    searching = false; open(ch, p)
                }
            }
        }
    }

    // Programa futuro o ya emitido: ficha con lo que se puede hacer
    detail?.let { (ch, p) ->
        val shifted = p.copy(start = p.start + sh, stop = p.stop + sh)
        val catchup = shifted.stop <= now && ch.catchupUrlFor(shifted, now) != null
        Sheet(p.title, onDismiss = { detail = null }) {
            Text("${ch.name} · ${fmtTime(shifted.start)}–${fmtTime(shifted.stop)}", color = c.accentDeep, fontWeight = FontWeight.Bold)
            if (p.desc.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(p.desc, color = c.ink, fontSize = 14.sp) }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (catchup) ActionButton("⏪ Ver desde el inicio") { detail = null; vm.playCatchup(ch, shifted) }
                ActionButton("Ver el canal en vivo", ghost = catchup) { detail = null; vm.activate(ch, listFor(ch)) }
            }
            if (!catchup && shifted.stop <= now) {
                Spacer(Modifier.height(8.dp))
                Text("Este canal no tiene TV en diferido.", color = c.inkSoft, fontSize = 13.sp)
            }
            // Programa futuro: grabarlo a su hora (la app puede estar cerrada)
            if (shifted.start > now) {
                val recId = cl.iptv.player.data.ScheduledRec.idFor(ch.id, shifted.start)
                val already = scheduled.any { it.id == recId }
                Spacer(Modifier.height(10.dp))
                ActionButton(if (already) "Cancelar la grabación programada" else "● Programar grabación", ghost = already) {
                    if (already) { cl.iptv.player.data.Schedules.remove(ctx, recId); vm.message = "Grabación cancelada" }
                    else {
                        val r = cl.iptv.player.data.ScheduledRec(recId, p.title, ch.name, cl.iptv.player.data.Recorder.bestUrl(ch.url), ch.userAgent, ch.referrer, shifted.start, shifted.stop)
                        val clash = cl.iptv.player.data.Schedules.overlaps(ctx, r)
                        cl.iptv.player.data.Schedules.add(ctx, r)
                        vm.message = "Grabación programada: ${p.title} · ${fmtTime(shifted.start)}–${fmtTime(shifted.stop)}. Se guarda en Descargas/VibeIPTV" +
                            if (clash) ". Ojo: choca con otra grabación y muchas cuentas admiten una sola conexión" else ""
                        vm.logEvent("Grabación programada: ${p.title} (${ch.name})")
                    }
                    recTick++; detail = null
                }
                if (!already) Text("Empieza 2 min antes y termina 3 min después. La TV debe quedar encendida o en reposo, no desenchufada.",
                    color = c.inkSoft, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }

    // Lista de grabaciones programadas, para cancelarlas
    if (showScheduled) Sheet("Grabaciones programadas", onDismiss = { showScheduled = false }, wide = true) {
        if (scheduled.isEmpty()) Text("No hay grabaciones programadas.", color = c.inkSoft)
        scheduled.forEach { r ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.title, color = c.ink, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text("${r.channel} · ${java.text.SimpleDateFormat("EEE d", java.util.Locale.getDefault()).format(java.util.Date(r.start))} · ${fmtTime(r.start)}–${fmtTime(r.end)}",
                        color = c.inkSoft, fontSize = 13.sp)
                }
                ActionButton("Cancelar", ghost = true) { cl.iptv.player.data.Schedules.remove(ctx, r.id); recTick++ }
            }
        }
    }
}

@Composable
private fun NowLine(now: Long, winStart: Long, dpPerMin: Dp, color: Color) {
    Box(Modifier.offset(x = ((now - winStart) / 60_000f * dpPerMin.value).dp).width(2.dp).fillMaxHeight().background(color))
}

@Composable
private fun GuideRow(
    ch: Item, progs: List<Programme>, sh: Long, now: Long,
    winStart: Long, winEnd: Long, dpPerMin: Dp, chanW: Dp, rowH: Dp,
    hScroll: ScrollState, firstFocus: FocusRequester?,
    onFocus: (Programme?) -> Unit, onOpen: (Programme?) -> Unit,
) {
    val c = LocalIptv.current
    val visible = progs.filter { it.stop + sh > winStart && it.start + sh < winEnd }
    val totalW = ((winEnd - winStart) / 60_000f * dpPerMin.value).dp
    Row(Modifier.fillMaxWidth().height(rowH), verticalAlignment = Alignment.CenterVertically) {
        // Canal
        Row(
            Modifier.width(chanW).fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(c.tileA)
                .border(1.dp, c.tileEdge, RoundedCornerShape(12.dp)).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (ch.logo.isNotBlank()) AsyncImage(ch.logo, null, contentScale = ContentScale.Fit, modifier = Modifier.size(width = 40.dp, height = 30.dp))
            else Text(initials(ch.name), color = c.inkSoft, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, modifier = Modifier.width(40.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(ch.name, color = c.ink, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp)
                if (ch.archiveDays > 0) Text("⏪ ${ch.archiveDays} d", color = c.accentDeep, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.width(6.dp))
        // Programas
        Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(hScroll)) {
            Box(Modifier.width(totalW).fillMaxHeight()) {
                if (visible.isEmpty()) {
                    ProgBlock(null, "Sin guía · OK para ver el canal", x = 0.dp, w = totalW, state = 0, archive = false,
                        modifier = firstFocus?.let { Modifier.focusRequester(it) } ?: Modifier, onFocus = { onFocus(null) }, onOpen = { onOpen(null) })
                } else visible.forEach { p ->
                    val s = maxOf(p.start + sh, winStart); val e = minOf(p.stop + sh, winEnd)
                    val state = when { p.stop + sh <= now -> -1; p.start + sh <= now -> 1; else -> 0 }
                    val archive = state == -1 && ch.catchupUrlFor(p.copy(start = p.start + sh, stop = p.stop + sh), now) != null
                    ProgBlock(
                        p, p.title, x = ((s - winStart) / 60_000f * dpPerMin.value).dp, w = ((e - s) / 60_000f * dpPerMin.value).dp,
                        state = state, archive = archive,
                        modifier = if (state == 1 && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier,
                        onFocus = { onFocus(p) }, onOpen = { onOpen(p) },
                    )
                }
                NowLine(now, winStart, dpPerMin, c.accent.copy(alpha = 0.7f))
            }
        }
    }
}

/** state: -1 ya emitido, 0 futuro, 1 en emisión. */
@Composable
private fun ProgBlock(
    p: Programme?, title: String, x: Dp, w: Dp, state: Int, archive: Boolean,
    modifier: Modifier, onFocus: () -> Unit, onOpen: () -> Unit,
) {
    val c = LocalIptv.current
    val src = remember { MutableInteractionSource() }
    val f by src.collectIsFocusedAsState()
    LaunchedEffect(f) { if (f) onFocus() }
    val shape = RoundedCornerShape(10.dp)
    val bg = when {
        f -> Color.White
        state == 1 -> c.accent.copy(alpha = 0.22f)
        state == -1 -> c.tileB.copy(alpha = if (archive) 0.9f else 0.5f)
        else -> c.tileA
    }
    Box(
        modifier.offset(x = x).width((w - 3.dp).coerceAtLeast(2.dp)).fillMaxHeight().padding(vertical = 2.dp)
            .clip(shape).background(bg)
            .border(if (f) 3.dp else 1.dp, if (f) c.focus else if (state == 1) c.accent else c.tileEdge, shape)
            .clickable(interactionSource = src, indication = null, onClick = onOpen)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (w > 28.dp) Text(
            (if (archive) "⏪ " else "") + title, color = if (state == -1 && !archive && !f) c.inkSoft else c.ink,
            fontWeight = if (state == 1) FontWeight.ExtraBold else FontWeight.Bold, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp,
        )
    }
}
