@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package cl.iptv.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cl.iptv.player.data.Item
import cl.iptv.player.data.Kind
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

/*
 * Tema "Escritorio": inspirado en los escritorios de principios de los 2000 (ventana azul con botones redondeados,
 * panel de tareas a la izquierda, vista de íconos y barra de tareas con botón de inicio verde).
 * Diseño propio: sin logos, fondos, sonidos ni tipografías de ninguna marca.
 * Funciona con el control remoto (foco), el tacto y el mouse; la vista de íconos y el panel se desplazan solos.
 */

private val XP_TITLE = Brush.verticalGradient(listOf(Color(0xFF0A58E8), Color(0xFF3D95FF), Color(0xFF0A5BE6), Color(0xFF0047D3)))
private val XP_TASKBAR = Brush.verticalGradient(listOf(Color(0xFF3168D5), Color(0xFF4993E6), Color(0xFF2157D7), Color(0xFF1941A5)))
private val XP_TRAY = Brush.verticalGradient(listOf(Color(0xFF0C59B9), Color(0xFF139EE9), Color(0xFF1290E8), Color(0xFF0D61C7)))
private val XP_START = Brush.verticalGradient(listOf(Color(0xFF3C9A3C), Color(0xFF5DC15D), Color(0xFF3C9A3C), Color(0xFF2F7F2F)))
private val XP_PANE = Brush.verticalGradient(listOf(Color(0xFF7BA2E7), Color(0xFF6375D6)))
private val XP_GROUP_HEAD = Brush.horizontalGradient(listOf(Color(0xFFFFFFFF), Color(0xFFC6D3F7)))
private val XP_DESKTOP = Brush.verticalGradient(listOf(Color(0xFF245EDC), Color(0xFF3A80F3), Color(0xFF2B6BE0)))
private val XP_CREAM = Color(0xFFECE9D8)
private val XP_SELECT = Color(0xFF316AC5)
private val XP_LINK = Color(0xFF215DC6)
private val TITLE_TEXT = TextStyle(color = Color.White, fontWeight = FontWeight.Bold,
    shadow = Shadow(Color(0xFF0A1E5A), androidx.compose.ui.geometry.Offset(1f, 1f), 2f))

@Composable
fun DeskScreen(vm: AppViewModel) {
    val ctl = rememberConsoleCtl(vm)
    val tab = vm.shell.tab
    val first = remember { FocusRequester() }
    val startFirst = remember { FocusRequester() }
    val isTv = (androidx.compose.ui.platform.LocalContext.current as? cl.iptv.player.MainActivity)?.isTv == true
    var start by remember { mutableStateOf(false) }
    var paneOpen by remember { mutableStateOf<Boolean?>(null) }
    BackHandler(enabled = start) { start = false }
    LaunchedEffect(ctl.dialog, tab, start) {
        if (ctl.dialog == null) { delay(80); runCatching { (if (start) startFirst else first).requestFocus() } }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(XP_DESKTOP)) {
        val compact = maxHeight < 480.dp
        val narrow = maxWidth < 720.dp
        val showPane = paneOpen ?: !narrow
        val title = when {
            vm.series != null -> vm.series!!.name
            ckind(tab) != null -> vm.title()
            tab == 0 -> tr("Mi Vibe")
            else -> tr(CTABS_DESK[tab])
        }
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            // ---- Ventana ----
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = if (compact) 4.dp else 10.dp, bottom = 6.dp)
                    .clip(RoundedCornerShape(topStart = 9.dp, topEnd = 9.dp)).background(XP_CREAM)
                    .border(1.dp, Color(0xFF0831D9), RoundedCornerShape(topStart = 9.dp, topEnd = 9.dp)),
            ) {
                // Barra de título
                Row(Modifier.fillMaxWidth().height(if (compact) 28.dp else 32.dp).background(XP_TITLE).padding(start = 8.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    TIconView(deskIcon(tab), Color.White, Modifier.size(18.dp))
                    Text("Vibe IPTV — $title", style = TITLE_TEXT, fontSize = if (compact) 13.sp else 15.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 8.dp))
                    Caption("—", Color(0xFF2663E8)) { ctl.goTab(0) }
                    Spacer(Modifier.width(3.dp))
                    Caption("☰", Color(0xFF2663E8)) { paneOpen = !showPane }
                    Spacer(Modifier.width(3.dp))
                    Caption("✕", Color(0xFFD9542F)) {
                        when {
                            vm.series != null || vm.query.isNotBlank() -> vm.back()
                            vm.category != CAT_ALL -> vm.selectCategory(CAT_ALL)
                            else -> ctl.goTab(0)
                        }
                    }
                }
                // Barra de herramientas y dirección
                Row(Modifier.fillMaxWidth().background(XP_CREAM).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ToolButton("◀ " + tr("Atrás")) {
                        when {
                            vm.series != null || vm.query.isNotBlank() -> vm.back()
                            vm.category != CAT_ALL -> vm.selectCategory(CAT_ALL)
                            else -> ctl.goTab(0)
                        }
                    }
                    ToolButton("⌕ " + tr("Buscar")) { if (ckind(tab) == null) ctl.goTab(1); ctl.dialog = "search" }
                    ToolButton("☰ " + tr("Categorías")) { if (ckind(tab) == null) ctl.goTab(1); ctl.dialog = "cats" }
                    ToolButton("★ " + tr("Favoritos")) { if (ckind(tab) == null) ctl.goTab(1); vm.selectCategory(CAT_FAV) }
                    Text(tr("Dirección") + ":", color = Color(0xFF444444), fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
                    Box(Modifier.widthIn(min = 220.dp).background(Color.White).border(1.dp, Color(0xFF7F9DB9)).padding(horizontal = 8.dp, vertical = 3.dp)) {
                        Text(listOfNotNull(tr("Inicio"), ckind(tab)?.let { tr(CTABS_DESK[tab]) }, vm.series?.name,
                            vm.title().takeIf { ckind(tab) != null && vm.category != CAT_ALL }).joinToString(" › "),
                            color = Color.Black, fontSize = 13.sp, maxLines = 1)
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFACA899)))
                // Cuerpo: panel de tareas + contenido
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    if (showPane) TaskPane(vm, ctl, isTv, Modifier.width(if (narrow) 190.dp else 230.dp).fillMaxHeight()) { if (narrow) paneOpen = false }
                    Box(Modifier.weight(1f).fillMaxHeight().background(Color.White)) {
                        val k = ckind(tab)
                        if (k == null) DeskHome(vm, ctl, isTv, compact, first) else DeskIcons(vm, ctl, k, compact, first)
                    }
                }
                // Barra de estado
                Row(Modifier.fillMaxWidth().background(XP_CREAM).border(1.dp, Color(0xFFD6D2C2)).padding(horizontal = 8.dp, vertical = 3.dp)) {
                    Text(vm.loading ?: (if (ckind(tab) != null) "${vm.view.size} " + tr("objetos") else vm.source?.name.orEmpty()),
                        color = Color(0xFF333333), fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f))
                    Text(vm.profile.name, color = Color(0xFF333333), fontSize = 12.sp)
                }
            }
            // ---- Barra de tareas ----
            Row(Modifier.fillMaxWidth().height(if (compact) 34.dp else 40.dp).background(XP_TASKBAR), verticalAlignment = Alignment.CenterVertically) {
                StartButton(start, compact) { start = !start }
                Row(Modifier.weight(1f).padding(start = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(1, 2, 3).forEach { t -> TaskButton(tr(CTABS_DESK[t]), deskIcon(t), selected = tab == t, compact) { start = false; ctl.goTab(t) } }
                    TaskButton(tr("Guía"), TIcon.GUIDE, selected = false, compact) { start = false; vm.showGuide = true }
                }
                Row(Modifier.fillMaxHeight().background(XP_TRAY).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Clock(Color.White, if (compact) 13 else 15)
                }
            }
        }
        if (start) StartMenu(vm, ctl, isTv, compact, startFirst, Modifier.align(Alignment.BottomStart).systemBarsPadding()
            .padding(bottom = if (compact) 34.dp else 40.dp)) { start = false }
    }
    ConsoleDialogs(vm, ctl)
}

private val CTABS_DESK = listOf("Inicio", "TV en vivo", "Películas", "Series", "Guía", "Ajustes")
private fun deskIcon(tab: Int) = when (tab) { 1 -> TIcon.TV; 2 -> TIcon.MOVIE; 3 -> TIcon.SERIES; 4 -> TIcon.GUIDE; 5 -> TIcon.SETTINGS; else -> TIcon.FOLDER }

/** Botón redondeado de la barra de título (minimizar, panel, cerrar). */
@Composable
private fun Caption(label: String, color: Color, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val f by src.collectIsFocusedAsState()
    Box(Modifier.size(24.dp).clip(RoundedCornerShape(4.dp))
        .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.55f), color, color)))
        .border(if (f) 2.dp else 1.dp, if (f) Color(0xFFFFD25A) else Color.White, RoundedCornerShape(4.dp))
        .combinedClickable(interactionSource = src, indication = null, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable
private fun ToolButton(label: String, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val f by src.collectIsFocusedAsState()
    Box(Modifier.clip(RoundedCornerShape(3.dp)).background(if (f) Color(0xFFFFF3C8) else Color.Transparent)
        .border(1.dp, if (f) Color(0xFFE6A800) else Color.Transparent, RoundedCornerShape(3.dp))
        .combinedClickable(interactionSource = src, indication = null, onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp)) {
        Text(label, color = Color.Black, fontSize = 13.sp)
    }
}

/** Panel de tareas azul de la izquierda: grupos con enlaces. */
@Composable
private fun TaskPane(vm: AppViewModel, ctl: ConsoleCtl, isTv: Boolean, modifier: Modifier, onPicked: () -> Unit) {
    Column(modifier.background(XP_PANE).verticalScroll(rememberScrollState()).padding(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PaneGroup(tr("Secciones")) {
            PaneLink(tr("Mi Vibe"), TIcon.FOLDER) { onPicked(); ctl.goTab(0) }
            PaneLink("${tr("TV en vivo")} (${vm.countOf(Kind.LIVE)})", TIcon.TV) { onPicked(); ctl.goTab(1) }
            PaneLink("${tr("Películas")} (${vm.countOf(Kind.MOVIE)})", TIcon.MOVIE) { onPicked(); ctl.goTab(2) }
            PaneLink("${tr("Series")} (${vm.countOf(Kind.SERIES)})", TIcon.SERIES) { onPicked(); ctl.goTab(3) }
            PaneLink(tr("Guía"), TIcon.GUIDE) { onPicked(); vm.showGuide = true }
        }
        PaneGroup(tr("Otros sitios")) {
            PaneLink(tr("Mis listas"), TIcon.LISTS) { vm.showWelcome = true }
            PaneLink(tr("Perfil") + " · " + vm.profile.name, TIcon.PROFILE) { ctl.dialog = "profiles" }
            PaneLink(tr("Ajustes"), TIcon.SETTINGS) { vm.showSettings = true }
            PaneLink(tr("Buscar actualizaciones"), TIcon.UPDATE) { vm.checkUpdate(isTv, silent = false) }
        }
        PaneGroup(tr("Detalles")) {
            Text(vm.source?.name ?: tr("Sin lista"), color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text("${vm.countOf(Kind.LIVE) + vm.countOf(Kind.MOVIE) + vm.countOf(Kind.SERIES)} " + tr("objetos"), color = Color(0xFF333333), fontSize = 12.sp)
        }
    }
}

@Composable
private fun PaneGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))) {
        Box(Modifier.fillMaxWidth().background(XP_GROUP_HEAD).padding(horizontal = 10.dp, vertical = 5.dp)) {
            Text(title, color = Color(0xFF215DC6), fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        Column(Modifier.fillMaxWidth().background(Color(0xFFD6DFF7)).border(1.dp, Color.White).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

@Composable
private fun PaneLink(label: String, icon: TIcon, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val f by src.collectIsFocusedAsState()
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(3.dp)).background(if (f) Color.White else Color.Transparent)
        .combinedClickable(interactionSource = src, indication = null, onClick = onClick).padding(horizontal = 4.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically) {
        TIconView(icon, XP_LINK, Modifier.size(16.dp))
        Text(label, color = XP_LINK, fontSize = 13.sp, maxLines = 2, modifier = Modifier.padding(start = 6.dp),
            style = TextStyle(textDecoration = if (f) androidx.compose.ui.text.style.TextDecoration.Underline else null))
    }
}

/** Inicio ("Mi Vibe"): accesos grandes, seguir viendo y favoritos, como íconos de escritorio. */
@Composable
private fun DeskHome(vm: AppViewModel, ctl: ConsoleCtl, isTv: Boolean, compact: Boolean, first: FocusRequester) {
    val cont = remember(vm.items, vm.resume) { vm.continueWatching() }
    val favs = remember(vm.items, vm.favorites) { val byId = vm.items.associateBy { it.id }; vm.favorites.mapNotNull { byId[it] }.take(24) }
    LazyVerticalGrid(GridCells.Adaptive(if (compact) 150.dp else 190.dp), modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) { GroupHeader(tr("Secciones")) }
        val shortcuts = listOf(
            Triple(tr("TV en vivo"), "${vm.countOf(Kind.LIVE)} " + tr("canales"), TIcon.TV) to { ctl.goTab(1) },
            Triple(tr("Películas"), "${vm.countOf(Kind.MOVIE)}", TIcon.MOVIE) to { ctl.goTab(2) },
            Triple(tr("Series"), "${vm.countOf(Kind.SERIES)}", TIcon.SERIES) to { ctl.goTab(3) },
            Triple(tr("Guía"), tr("Programación"), TIcon.GUIDE) to { vm.showGuide = true },
            Triple(tr("Mis listas"), vm.source?.name.orEmpty(), TIcon.LISTS) to { vm.showWelcome = true },
            Triple(tr("Ajustes"), "", TIcon.SETTINGS) to { vm.showSettings = true },
        )
        itemsIndexed(shortcuts, key = { i, _ -> "s$i" }) { i, (t, onClick) ->
            DeskIcon(t.first, t.second, null, t.third, Modifier.then(if (i == 0) Modifier.focusRequester(first) else Modifier), onClick = onClick)
        }
        if (cont.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { GroupHeader(tr("Seguir viendo")) }
            itemsIndexed(cont, key = { i, it0 -> "c$i:${it0.first.id}" }) { _, (item, r, ep) ->
                DeskIcon(item.name, "${(r.fraction * 100).toInt()} %", item, null, Modifier, progress = r.fraction) { ctl.open(item, vm.items, ep) }
            }
        }
        if (favs.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { GroupHeader(tr("Favoritos")) }
            itemsIndexed(favs, key = { i, it0 -> "f$i:${it0.id}" }) { _, it0 ->
                DeskIcon(it0.name, vm.nowNext(it0).first?.title.orEmpty(), it0, null, Modifier, onLong = { vm.toggleFavorite(it0) }) { ctl.open(it0, favs) }
            }
        }
    }
}

@Composable
private fun GroupHeader(title: String) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text(title, color = Color(0xFF215DC6), fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Box(Modifier.padding(top = 3.dp).fillMaxWidth(0.6f).height(1.dp).background(Brush.horizontalGradient(listOf(Color(0xFF7BA2E7), Color.Transparent))))
    }
}

/** Vista de íconos de una sección (TV, películas o series), con la categoría elegida. */
@Composable
private fun DeskIcons(vm: AppViewModel, ctl: ConsoleCtl, k: Kind, compact: Boolean, first: FocusRequester) {
    val view = vm.view
    val state = rememberLazyGridState()
    LaunchedEffect(vm.category, vm.series?.id, vm.query) { state.scrollToItem(0) }
    LazyVerticalGrid(GridCells.Adaptive(if (compact) 160.dp else 210.dp), state = state, modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (view.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Text(tr(if (vm.loading != null) "Cargando…" else "No hay contenido para mostrar."), color = Color(0xFF444444), fontSize = 16.sp,
                modifier = Modifier.padding(20.dp).focusRequester(first))
        }
        itemsIndexed(view, key = { i, it0 -> "$i:${it0.id}" }) { i, it0 ->
            val sub = if (it0.kind == Kind.LIVE) vm.nowNext(it0).first?.title.orEmpty() else it0.group
            DeskIcon(it0.name, sub, it0, null, if (i == 0) Modifier.focusRequester(first) else Modifier,
                progress = vm.resume[it0.id]?.fraction ?: 0f, onLong = { vm.toggleFavorite(it0) }) { ctl.open(it0, view) }
        }
    }
}

/** Ícono con texto al lado (vista "Mosaicos"): al elegirlo se pinta de azul como una selección de escritorio. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DeskIcon(title: String, sub: String, item: Item?, icon: TIcon?, modifier: Modifier, progress: Float = 0f,
                     onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val f by src.collectIsFocusedAsState()
    Row(modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(2.dp))
        .background(if (f) XP_SELECT.copy(alpha = 0.18f) else Color.Transparent)
        .border(1.dp, if (f) XP_SELECT else Color.Transparent, RoundedCornerShape(2.dp))
        .combinedClickable(interactionSource = src, indication = null, onClick = onClick, onLongClick = onLong).padding(4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(6.dp)).background(if (icon != null) Brush.verticalGradient(listOf(Color(0xFFFFF2B3), Color(0xFFF2C14E)))
            else Brush.verticalGradient(listOf(Color(0xFFF4F3EE), Color(0xFFDCD9C9)))), contentAlignment = Alignment.Center) {
            if (icon != null) TIconView(icon, Color(0xFF7A5A0A), Modifier.size(30.dp))
            else if (item != null) {
                var failed by remember(item.logo) { mutableStateOf(item.logo.isBlank()) }
                if (!failed) AsyncImage(item.logo, null, contentScale = if (item.kind == Kind.LIVE) ContentScale.Fit else ContentScale.Crop,
                    onError = { failed = true }, modifier = Modifier.fillMaxSize().padding(if (item.kind == Kind.LIVE) 4.dp else 0.dp))
                else Text(initials(item.name), color = Color(0xFF555555), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
            if (progress > 0f) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x55000000))) {
                Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(Color(0xFF3FA33F)))
            }
        }
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Box(Modifier.background(if (f) XP_SELECT else Color.Transparent).padding(horizontal = 2.dp)) {
                Text(title, color = if (f) Color.White else Color.Black, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (sub.isNotBlank()) Text(sub, color = Color(0xFF6D6D6D), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun StartButton(open: Boolean, compact: Boolean, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val f by src.collectIsFocusedAsState()
    Row(Modifier.fillMaxHeight().clip(RoundedCornerShape(topEnd = 14.dp, bottomEnd = 14.dp)).background(XP_START)
        .border(if (f) 2.dp else 0.dp, if (f) Color(0xFFFFD25A) else Color.Transparent, RoundedCornerShape(topEnd = 14.dp, bottomEnd = 14.dp))
        .combinedClickable(interactionSource = src, indication = null, onClick = onClick).padding(start = 10.dp, end = 18.dp),
        verticalAlignment = Alignment.CenterVertically) {
        // Glifo propio (no el de ninguna marca): un círculo con el triángulo de reproducir
        Box(Modifier.size(if (compact) 18.dp else 22.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.9f)), contentAlignment = Alignment.Center) {
            Text("▶", color = Color(0xFF2F7F2F), fontSize = if (compact) 9.sp else 11.sp, fontWeight = FontWeight.Bold)
        }
        Text(tr("inicio"), style = TITLE_TEXT.copy(fontStyle = FontStyle.Italic), fontSize = if (compact) 15.sp else 18.sp, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun TaskButton(label: String, icon: TIcon, selected: Boolean, compact: Boolean, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val f by src.collectIsFocusedAsState()
    Row(Modifier.height(if (compact) 26.dp else 30.dp).widthIn(min = 120.dp).clip(RoundedCornerShape(3.dp))
        .background(if (selected) Color(0xFF1E52B7) else Color(0xFF3C81F3))
        .border(1.dp, if (f) Color(0xFFFFD25A) else Color(0x66FFFFFF), RoundedCornerShape(3.dp))
        .combinedClickable(interactionSource = src, indication = null, onClick = onClick).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        TIconView(icon, Color.White, Modifier.size(16.dp))
        Text(label, color = Color.White, fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(start = 6.dp))
    }
}

/** Menú de inicio: a la izquierda lo último visto, a la derecha las secciones y ajustes. */
@Composable
private fun StartMenu(vm: AppViewModel, ctl: ConsoleCtl, isTv: Boolean, compact: Boolean, first: FocusRequester, modifier: Modifier, close: () -> Unit) {
    val recent = remember(vm.items, vm.recent) { val byId = vm.items.associateBy { it.id }; vm.recent.mapNotNull { byId[it] }.take(if (compact) 5 else 8) }
    Column(modifier.padding(start = 2.dp).width(if (compact) 400.dp else 460.dp).heightIn(max = if (compact) 300.dp else 460.dp)
        .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)).border(1.dp, Color(0xFF0831D9), RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))) {
        Row(Modifier.fillMaxWidth().background(XP_TITLE).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFFF2C14E)).border(2.dp, Color.White, RoundedCornerShape(5.dp)),
                contentAlignment = Alignment.Center) { Text(initials(vm.profile.name).take(1), color = Color(0xFF7A5A0A), fontWeight = FontWeight.ExtraBold) }
            Text(vm.profile.name, style = TITLE_TEXT, fontSize = 16.sp, modifier = Modifier.padding(start = 10.dp))
        }
        Row(Modifier.weight(1f, fill = false)) {
            Column(Modifier.weight(1f).background(Color.White).verticalScroll(rememberScrollState()).padding(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(tr("Vistos recientemente"), color = Color(0xFF6D6D6D), fontSize = 12.sp, modifier = Modifier.padding(4.dp))
                if (recent.isEmpty()) Text(tr("Todavía nada"), color = Color(0xFF8D8D8D), fontSize = 13.sp, modifier = Modifier.padding(4.dp).focusRequester(first))
                recent.forEachIndexed { i, it0 ->
                    StartItem(it0.name, deskIcon(when (it0.kind) { Kind.LIVE -> 1; Kind.MOVIE -> 2; else -> 3 }), bold = false,
                        modifier = if (i == 0) Modifier.focusRequester(first) else Modifier) { close(); ctl.open(it0, recent) }
                }
            }
            Column(Modifier.weight(1f).background(Color(0xFFD3E5FA)).verticalScroll(rememberScrollState()).padding(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                StartItem(tr("TV en vivo"), TIcon.TV) { close(); ctl.goTab(1) }
                StartItem(tr("Películas"), TIcon.MOVIE) { close(); ctl.goTab(2) }
                StartItem(tr("Series"), TIcon.SERIES) { close(); ctl.goTab(3) }
                StartItem(tr("Guía"), TIcon.GUIDE) { close(); vm.showGuide = true }
                StartItem(tr("Buscar"), TIcon.SEARCH) { close(); ctl.goTab(1); ctl.dialog = "search" }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF95BDEE)))
                StartItem(tr("Mis listas"), TIcon.LISTS) { close(); vm.showWelcome = true }
                StartItem(tr("Ajustes"), TIcon.SETTINGS) { close(); vm.showSettings = true }
                StartItem(tr("Buscar actualizaciones"), TIcon.UPDATE) { close(); vm.checkUpdate(isTv, silent = false) }
            }
        }
        Row(Modifier.fillMaxWidth().background(XP_TITLE).padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.End) {
            ToolChip(tr("Cerrar menú"), close)
        }
    }
}

@Composable
private fun StartItem(label: String, icon: TIcon, bold: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val f by src.collectIsFocusedAsState()
    Row(modifier.fillMaxWidth().background(if (f) XP_SELECT else Color.Transparent)
        .combinedClickable(interactionSource = src, indication = null, onClick = onClick).padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically) {
        TIconView(icon, if (f) Color.White else XP_LINK, Modifier.size(20.dp))
        Text(label, color = if (f) Color.White else Color.Black, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, fontSize = 13.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun ToolChip(label: String, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val f by src.collectIsFocusedAsState()
    Box(Modifier.clip(RoundedCornerShape(3.dp)).background(if (f) Color(0xFFD9542F) else Color(0xFFE2683C))
        .border(1.dp, Color.White, RoundedCornerShape(3.dp))
        .combinedClickable(interactionSource = src, indication = null, onClick = onClick).padding(horizontal = 10.dp, vertical = 3.dp)) {
        Text("⏻ $label", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

