@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package cl.iptv.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import cl.iptv.player.data.Item
import cl.iptv.player.data.Kind
import cl.iptv.player.data.Perf
import cl.iptv.player.ui.theme.LocalIptv
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

/*
 * Dos temas al estilo de los menús de las consolas actuales (diseño propio: sin logos, nombres ni recursos de marcas).
 *  · Pulso: barra de pestañas arriba, mosaicos redondeados en filas y una cuadrícula que se recorre hacia abajo.
 *  · Horizonte: una fila de íconos arriba que se desplaza a la izquierda, el elegido grande, con su imagen de fondo
 *    a pantalla completa y su información y botones debajo.
 * Funcionan con el control remoto (foco), el tacto y el mouse; todas las listas se desplazan solas hasta lo enfocado.
 */

private val CTABS = listOf("Inicio", "TV en vivo", "Películas", "Series", "Guía", "Ajustes")
private fun ckind(tab: Int) = when (tab) { 1 -> Kind.LIVE; 2 -> Kind.MOVIE; 3 -> Kind.SERIES; else -> null }

/** Lo común a los dos temas: pestaña actual, abrir elementos y diálogos. */
private class ConsoleCtl(val vm: AppViewModel) {
    var dialog by mutableStateOf<String?>(null)
    fun goTab(t: Int) {
        vm.shell.tab = t
        ckind(t)?.let { k -> if (vm.section != k || vm.home) vm.selectSection(k) }
    }
    fun open(item: Item, list: List<Item>, episode: String? = null) {
        when {
            episode != null -> vm.openContinue(item, episode)
            item.kind == Kind.LIVE -> vm.activate(item, list.filter { it.kind == Kind.LIVE }.ifEmpty { listOf(item) })
            else -> vm.choose(item)
        }
    }
}

@Composable
private fun rememberConsoleCtl(vm: AppViewModel): ConsoleCtl {
    val ctl = remember(vm) { ConsoleCtl(vm) }
    LaunchedEffect(Unit) { ckind(vm.shell.tab)?.let { k -> if (vm.section != k || vm.home) vm.selectSection(k) } }
    BackHandler(enabled = ctl.dialog == null && vm.details == null &&
        (vm.series != null || vm.category != CAT_ALL || vm.query.isNotBlank() || vm.shell.tab != 0)) {
        when {
            vm.series != null || vm.query.isNotBlank() -> vm.back()
            vm.category != CAT_ALL -> vm.selectCategory(CAT_ALL)
            else -> ctl.goTab(0)
        }
    }
    return ctl
}

@Composable
private fun ConsoleDialogs(vm: AppViewModel, ctl: ConsoleCtl) {
    when (ctl.dialog) {
        "search" -> SearchDialog(vm) { ctl.dialog = null }
        "cats" -> CategoriesDialog(vm) { ctl.dialog = null }
        "profiles" -> ProfilesDialog(vm) { ctl.dialog = null }
    }
    vm.details?.let { DetailsScreen(vm, it) }
}

@Composable
private fun Clock(color: Color, size: Int) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(15_000); now = System.currentTimeMillis() } }
    androidx.compose.material3.Text(java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(now)),
        color = color, fontSize = size.sp, fontWeight = FontWeight.SemiBold)
}

/** Un acceso de la pantalla de inicio o de Ajustes. */
private class Shortcut(val label: String, val icon: TIcon, val onClick: () -> Unit)

private fun shortcuts(vm: AppViewModel, ctl: ConsoleCtl, isTv: Boolean) = listOf(
    Shortcut("TV en vivo · ${vm.countOf(Kind.LIVE)}", TIcon.TV) { ctl.goTab(1) },
    Shortcut("Películas · ${vm.countOf(Kind.MOVIE)}", TIcon.MOVIE) { ctl.goTab(2) },
    Shortcut("Series · ${vm.countOf(Kind.SERIES)}", TIcon.SERIES) { ctl.goTab(3) },
    Shortcut("Guía", TIcon.GUIDE) { vm.showGuide = true },
    Shortcut("Buscar", TIcon.SEARCH) { ctl.goTab(1); ctl.dialog = "search" },
    Shortcut("Mis listas", TIcon.LISTS) { vm.showWelcome = true },
    Shortcut("Ajustes", TIcon.SETTINGS) { vm.showSettings = true },
)

private fun settingsShortcuts(vm: AppViewModel, ctl: ConsoleCtl, isTv: Boolean) = listOf(
    Shortcut("Ajustes", TIcon.SETTINGS) { vm.showSettings = true },
    Shortcut("Mis listas", TIcon.LISTS) { vm.showWelcome = true },
    Shortcut("Perfil · ${vm.profile.name}", TIcon.PROFILE) { ctl.dialog = "profiles" },
    Shortcut("Buscar actualizaciones", TIcon.UPDATE) { vm.checkUpdate(isTv, silent = false) },
)

// ============================================================================================
// Pulso
// ============================================================================================

@Composable
fun PulseScreen(vm: AppViewModel) {
    val c = LocalIptv.current
    val ctl = rememberConsoleCtl(vm)
    val tab = vm.shell.tab
    val first = remember { FocusRequester() }
    val isTv = (androidx.compose.ui.platform.LocalContext.current as? cl.iptv.player.MainActivity)?.isTv == true
    var hero by remember { mutableStateOf<Item?>(null) }
    LaunchedEffect(ctl.dialog, vm.details, tab) { if (ctl.dialog == null && vm.details == null) { delay(80); runCatching { first.requestFocus() } } }

    BoxWithConstraints(Modifier.fillMaxSize().themeBackground()) {
        val compact = maxHeight < 480.dp
        val side = if (compact) 16.dp else 56.dp
        // Imagen grande de lo enfocado, suave detrás de todo
        val heroImg = hero?.logo.orEmpty()
        if (heroImg.startsWith("http") && hero?.kind != Kind.LIVE && !Perf.lite) {
            AsyncImage(heroImg, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.32f })
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(c.bg.copy(alpha = 0.55f), c.bg.copy(alpha = 0.92f), c.bg))))
        }
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            // Barra superior: perfil, pestañas (se deslizan si no caben) y hora
            Row(Modifier.fillMaxWidth().padding(start = side, end = side, top = if (compact) 8.dp else 26.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(if (compact) 34.dp else 44.dp).clip(CircleShape).background(c.accent).combinedClickable { ctl.dialog = "profiles" },
                    contentAlignment = Alignment.Center) {
                    androidx.compose.material3.Text(initials(vm.profile.name).take(1), color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                }
                Row(Modifier.weight(1f).padding(start = 14.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CTABS.forEachIndexed { i, t -> PulseTab(tr(t), selected = i == tab, compact = compact) { ctl.goTab(i) } }
                }
                Clock(c.ink, if (compact) 15 else 20)
            }
            Spacer(Modifier.height(if (compact) 8.dp else 18.dp))
            val k = ckind(tab)
            if (k == null) PulseRows(vm, ctl, tab, isTv, side, compact, first) { hero = it }
            else PulseGrid(vm, ctl, k, side, compact, first) { hero = it }
        }
    }
    ConsoleDialogs(vm, ctl)
}

@Composable
private fun PulseTab(label: String, selected: Boolean, compact: Boolean, onClick: () -> Unit) {
    val c = LocalIptv.current
    val src = remember { MutableInteractionSource() }
    val f by src.collectIsFocusedAsState()
    androidx.compose.material3.Text(
        label, color = if (f) Color(0xFF111416) else if (selected) c.ink else c.inkSoft,
        fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold, fontSize = if (compact) 14.sp else 17.sp, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (f) Color.White else if (selected) c.accent.copy(alpha = 0.22f) else Color.Transparent)
            .combinedClickable(interactionSource = src, indication = null, onClick = onClick)
            .padding(horizontal = if (compact) 12.dp else 18.dp, vertical = if (compact) 6.dp else 9.dp),
    )
}

/** Inicio, Guía y Ajustes: filas de mosaicos una debajo de otra (la columna y cada fila se desplazan). */
@Composable
private fun PulseRows(vm: AppViewModel, ctl: ConsoleCtl, tab: Int, isTv: Boolean, side: Dp, compact: Boolean, first: FocusRequester, onHero: (Item?) -> Unit) {
    val tileH = if (compact) 92.dp else 150.dp
    val cont = remember(vm.items, vm.resume) { vm.continueWatching() }
    val favs = remember(vm.items, vm.favorites) { vm.favorites.mapNotNull { id -> vm.items.firstOrNull { it.id == id } } }
    val recents = remember(vm.items, vm.recent) { vm.recent.mapNotNull { id -> vm.items.firstOrNull { it.id == id } } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 22.dp)) {
        when (tab) {
            0 -> {
                item {
                    PulseRow(if (cont.isEmpty()) "Accesos" else "Seguir viendo", side) {
                        if (cont.isEmpty()) itemsIndexed(shortcuts(vm, ctl, isTv)) { i, s ->
                            PulseIconTile(s.label, s.icon, Modifier.height(tileH).width(if (i == 0) tileH * 2.2f else tileH * 1.3f).then(if (i == 0) Modifier.focusRequester(first) else Modifier), s.onClick)
                        } else itemsIndexed(cont) { i, (it0, r, ep) ->
                            PulseTile(it0, Modifier.height(tileH).width(if (i == 0) tileH * 2.2f else tileH * 1.3f).then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                                progress = r.fraction, onFocus = onHero) { ctl.open(it0, vm.items, ep) }
                        }
                    }
                }
                if (cont.isNotEmpty()) item { PulseRow("Accesos", side) { itemsIndexed(shortcuts(vm, ctl, isTv)) { _, s -> PulseIconTile(s.label, s.icon, Modifier.height(tileH * 0.8f).width(tileH * 1.3f), s.onClick) } } }
                if (favs.isNotEmpty()) item { PulseRow("Favoritos", side) { itemsIndexed(favs) { _, it0 -> PulseTile(it0, Modifier.height(tileH).width(tileH * 1.3f), onFocus = onHero, onLong = { vm.toggleFavorite(it0) }) { ctl.open(it0, favs) } } } }
                if (recents.isNotEmpty()) item { PulseRow("Recientes", side) { itemsIndexed(recents) { _, it0 -> PulseTile(it0, Modifier.height(tileH).width(tileH * 1.3f), onFocus = onHero) { ctl.open(it0, recents) } } } }
            }
            4 -> item {
                PulseRow("Guía", side) {
                    itemsIndexed(listOf(Shortcut("Guía de programación", TIcon.GUIDE) { vm.showGuide = true }, Shortcut("TV en vivo", TIcon.TV) { ctl.goTab(1) })) { i, s ->
                        PulseIconTile(s.label, s.icon, Modifier.height(tileH).width(tileH * 2f).then(if (i == 0) Modifier.focusRequester(first) else Modifier), s.onClick)
                    }
                }
            }
            else -> item {
                PulseRow("Ajustes", side) {
                    itemsIndexed(settingsShortcuts(vm, ctl, isTv)) { i, s ->
                        PulseIconTile(s.label, s.icon, Modifier.height(tileH).width(tileH * 1.6f).then(if (i == 0) Modifier.focusRequester(first) else Modifier), s.onClick)
                    }
                }
            }
        }
    }
}

@Composable
private fun PulseRow(title: String, side: Dp, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    val c = LocalIptv.current
    Column {
        androidx.compose.material3.Text(tr(title), color = c.ink, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(start = side, bottom = 8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(horizontal = side, vertical = 6.dp), content = content)
    }
}

/** TV en vivo, Películas y Series: accesos arriba y la cuadrícula de contenido, que se recorre hacia abajo. */
@Composable
private fun PulseGrid(vm: AppViewModel, ctl: ConsoleCtl, k: Kind, side: Dp, compact: Boolean, first: FocusRequester, onHero: (Item?) -> Unit) {
    val c = LocalIptv.current
    val view = vm.view
    val state = rememberLazyGridState()
    LaunchedEffect(vm.category, vm.series?.id, vm.query) { state.scrollToItem(0) }
    val minW = if (k == Kind.LIVE) (if (compact) 130.dp else 190.dp) else (if (compact) 96.dp else 150.dp)
    LazyVerticalGrid(GridCells.Adaptive(minW), state = state, modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = side, end = side, bottom = 40.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                PulseChip("☰ " + tr("Categorías"), Modifier.focusRequester(first)) { ctl.dialog = "cats" }
                PulseChip(if (k == Kind.LIVE) "★ " + tr("Favoritos") else "▶ " + tr("Seguir viendo"), active = vm.category == CAT_FAV || vm.category == CAT_RESUME) {
                    vm.selectCategory(if (k == Kind.LIVE) CAT_FAV else CAT_RESUME)
                }
                PulseChip("⌕ " + tr("Buscar")) { ctl.dialog = "search" }
                androidx.compose.material3.Text(vm.series?.name ?: vm.title(), color = c.inkSoft, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (view.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            androidx.compose.material3.Text(tr(if (vm.loading != null) "Cargando…" else "No hay contenido para mostrar."), color = c.inkSoft, fontSize = 18.sp, modifier = Modifier.padding(24.dp))
        }
        gridItemsIndexed(view, key = { i, it0 -> "$i:${it0.id}" }) { _, it0 ->
            PulseTile(it0, Modifier.fillMaxWidth().aspectRatio(if (it0.kind == Kind.LIVE) 16f / 10f else 2f / 3f),
                progress = vm.resume[it0.id]?.fraction ?: 0f,
                sub = if (it0.kind == Kind.LIVE) vm.nowNext(it0).first?.title.orEmpty() else "",
                onFocus = onHero, onLong = { vm.toggleFavorite(it0) }) { ctl.open(it0, view) }
        }
    }
}

@Composable
private fun PulseChip(label: String, modifier: Modifier = Modifier, active: Boolean = false, onClick: () -> Unit) {
    val c = LocalIptv.current
    val src = remember { MutableInteractionSource() }
    val f by src.collectIsFocusedAsState()
    androidx.compose.material3.Text(label, color = if (f) Color(0xFF111416) else c.ink, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1,
        modifier = modifier.clip(RoundedCornerShape(10.dp)).background(if (f) Color.White else if (active) c.accent.copy(alpha = 0.35f) else c.tileA)
            .combinedClickable(interactionSource = src, indication = null, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp))
}

/** Mosaico redondeado con imagen; al enfocarlo crece, se ilumina el borde y aparece su nombre. */
@Composable
private fun PulseTile(item: Item, modifier: Modifier, progress: Float = 0f, sub: String = "", onFocus: (Item?) -> Unit = {},
                      onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    val c = LocalIptv.current
    val src = remember { MutableInteractionSource() }
    val f by src.collectIsFocusedAsState()
    LaunchedEffect(f) { if (f) onFocus(item) }
    val scale by animateFloatAsState(if (f && !Perf.lite) 1.06f else 1f, label = "pulse")
    var failed by remember(item.logo) { mutableStateOf(item.logo.isBlank()) }
    val shape = RoundedCornerShape(c.radius.dp)
    Box(
        modifier.zIndex(if (f) 1f else 0f).graphicsLayer { scaleX = scale; scaleY = scale }
            .then(if (f && !Perf.lite) Modifier.shadow(16.dp, shape) else Modifier)
            .clip(shape).background(if (failed) c.tileA else Color(0xFF1B1E21))
            .then(if (f) Modifier.border(3.dp, c.focus, shape) else Modifier)
            .combinedClickable(interactionSource = src, indication = null, onClick = onClick, onLongClick = onLong),
    ) {
        if (!failed) AsyncImage(item.logo, null, contentScale = if (item.kind == Kind.LIVE) ContentScale.Fit else ContentScale.Crop, onError = { failed = true },
            modifier = Modifier.fillMaxSize().then(if (item.kind == Kind.LIVE) Modifier.padding(14.dp).padding(bottom = 16.dp) else Modifier))
        else androidx.compose.material3.Text(initials(item.name), color = c.inkSoft, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
        if (progress > 0f) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x66000000))) {
            Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(c.accent))
        }
        if (f || item.kind == Kind.LIVE || failed) Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xD9000000)))).padding(horizontal = 10.dp, vertical = 6.dp)) {
            androidx.compose.material3.Text(item.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (sub.isNotBlank()) androidx.compose.material3.Text(sub, color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun PulseIconTile(label: String, icon: TIcon, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalIptv.current
    val src = remember { MutableInteractionSource() }
    val f by src.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (f && !Perf.lite) 1.06f else 1f, label = "pulseIcon")
    val shape = RoundedCornerShape(c.radius.dp)
    Box(
        modifier.zIndex(if (f) 1f else 0f).graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape).background(if (f) c.accent else c.tileA)
            .then(if (f) Modifier.border(3.dp, c.focus, shape) else Modifier)
            .combinedClickable(interactionSource = src, indication = null, onClick = onClick),
    ) {
        TIconView(icon, if (f) Color.White else c.accent, Modifier.align(Alignment.TopStart).padding(14.dp).size(30.dp))
        androidx.compose.material3.Text(tr(label.substringBefore(" · ")) + (label.substringAfter(" · ", "").let { if (it.isBlank()) "" else " · $it" }),
            color = if (f) Color.White else c.ink, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(14.dp))
    }
}

// ============================================================================================
// Horizonte
// ============================================================================================

/** Elemento de la fila de Horizonte: contenido o acceso. */
private class HEntry(val key: String, val title: String, val sub: String, val image: String, val icon: TIcon?, val item: Item?,
                     val progress: Float, val episode: String?, val action: (() -> Unit)?)

@Composable
fun HorizonScreen(vm: AppViewModel) {
    val c = LocalIptv.current
    val ctl = rememberConsoleCtl(vm)
    val tab = vm.shell.tab
    val k = ckind(tab)
    val isTv = (androidx.compose.ui.platform.LocalContext.current as? cl.iptv.player.MainActivity)?.isTv == true
    val first = remember { FocusRequester() }

    val entries: List<HEntry> = remember(tab, vm.view, vm.items, vm.resume, vm.favorites, vm.profile) { when {
        k != null -> vm.view.map { it0 -> HEntry(it0.id, it0.name, it0.group, it0.logo, null, it0, vm.resume[it0.id]?.fraction ?: 0f, null, null) }
        tab == 0 -> {
            val cont = vm.continueWatching().map { (it0, r, ep) -> HEntry("c:${it0.id}", it0.name, tr("Seguir viendo"), it0.logo, null, it0, r.fraction, ep, null) }
            val favs = vm.favorites.mapNotNull { id -> vm.items.firstOrNull { it.id == id } }.map { HEntry("f:${it.id}", it.name, tr("Favoritos"), it.logo, null, it, 0f, null, null) }
            cont + favs + shortcuts(vm, ctl, isTv).map { s -> HEntry("s:${s.label}", tr(s.label.substringBefore(" · ")), s.label.substringAfter(" · ", ""), "", s.icon, null, 0f, null, s.onClick) }
        }
        tab == 4 -> listOf(HEntry("g", tr("Guía de programación"), tr("Lo que dan ahora y después"), "", TIcon.GUIDE, null, 0f, null) { vm.showGuide = true })
        else -> settingsShortcuts(vm, ctl, isTv).map { s -> HEntry("s:${s.label}", tr(s.label.substringBefore(" · ")), s.label.substringAfter(" · ", ""), "", s.icon, null, 0f, null, s.onClick) }
    } }
    var sel by remember(tab, vm.category, vm.series?.id, vm.query) { mutableStateOf(0) }
    val cur = entries.getOrNull(sel.coerceIn(0, maxOf(0, entries.lastIndex)))
    fun activate(e: HEntry) { e.action?.invoke() ?: e.item?.let { ctl.open(it, if (k != null) vm.view else vm.items, e.episode) } }

    val state = rememberLazyListState()
    // Lo elegido queda siempre al comienzo de la fila (la fila se desplaza a la izquierda)
    LaunchedEffect(sel, entries.size) { if (entries.isNotEmpty()) runCatching { state.animateScrollToItem(sel.coerceIn(0, entries.lastIndex)) } }
    LaunchedEffect(tab, vm.category, vm.series?.id, vm.query) { runCatching { state.scrollToItem(0) } }
    LaunchedEffect(ctl.dialog, vm.details, tab) { if (ctl.dialog == null && vm.details == null) { delay(80); runCatching { first.requestFocus() } } }

    BoxWithConstraints(Modifier.fillMaxSize().themeBackground()) {
        val compact = maxHeight < 480.dp
        val side = if (compact) 16.dp else 64.dp
        // Fondo: la imagen de lo elegido a pantalla completa, fundida hacia abajo y a la izquierda
        val img = cur?.image.orEmpty()
        if (img.startsWith("http") && !Perf.lite) {
            AsyncImage(img, null, contentScale = if (cur?.item?.kind == Kind.LIVE) ContentScale.Fit else ContentScale.Crop,
                modifier = Modifier.fillMaxSize().then(if (cur?.item?.kind == Kind.LIVE) Modifier.padding(start = maxWidth * 0.45f, top = 60.dp, bottom = maxHeight * 0.35f, end = 40.dp) else Modifier)
                    .graphicsLayer { alpha = 0.55f })
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(c.bg, c.bg.copy(alpha = 0.6f), Color.Transparent))))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, c.bg.copy(alpha = 0.7f), c.bg))))
        }
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            // Arriba: pestañas pequeñas a la izquierda, accesos y hora a la derecha
            Row(Modifier.fillMaxWidth().padding(start = side, end = side, top = if (compact) 8.dp else 26.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    CTABS.forEachIndexed { i, t -> HorizonTab(tr(t), i == tab, compact) { ctl.goTab(i) } }
                }
                HorizonRound(TIcon.SEARCH) { if (k == null) ctl.goTab(1); ctl.dialog = "search" }
                Spacer(Modifier.width(8.dp))
                HorizonRound(TIcon.SETTINGS) { vm.showSettings = true }
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(34.dp).clip(CircleShape).background(c.accent).combinedClickable { ctl.dialog = "profiles" }, contentAlignment = Alignment.Center) {
                    androidx.compose.material3.Text(initials(vm.profile.name).take(1), color = Color.White, fontWeight = FontWeight.ExtraBold)
                }
                Spacer(Modifier.width(14.dp))
                Clock(c.ink, if (compact) 15 else 19)
            }
            if (k != null) androidx.compose.material3.Text(vm.series?.name ?: vm.title(), color = c.inkSoft, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = side, top = 10.dp))
            // Fila de íconos: el elegido, grande
            val small = if (compact) 64.dp else 96.dp
            val big = if (compact) 92.dp else 150.dp
            LazyRow(state = state, horizontalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 16.dp),
                contentPadding = PaddingValues(start = side, end = 600.dp, top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.Top,
                modifier = Modifier.height(big + 22.dp + 22.dp)) {
                if (entries.isEmpty()) item {
                    androidx.compose.material3.Text(tr(if (vm.loading != null) "Cargando…" else "No hay contenido para mostrar."), color = c.inkSoft, fontSize = 18.sp)
                }
                itemsIndexed(entries, key = { i, e -> "$i:${e.key}" }) { i, e ->
                    HorizonTile(e, selected = i == sel, small = small, big = big,
                        modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                        onFocused = { sel = i },
                        onLong = e.item?.let { it0 -> { vm.toggleFavorite(it0) } },
                    ) { if (i == sel) activate(e) else sel = i }   // tocar: el primer toque elige, el segundo abre
                }
            }
            // Debajo: título grande, información y botones
            if (cur != null) Column(Modifier.padding(start = side, end = side, top = if (compact) 4.dp else 18.dp).widthIn(max = 720.dp)) {
                androidx.compose.material3.Text(cur.title, color = c.ink, fontWeight = FontWeight.ExtraBold, fontSize = if (compact) 22.sp else 34.sp,
                    lineHeight = if (compact) 26.sp else 40.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (cur.sub.isNotBlank()) androidx.compose.material3.Text(cur.sub, color = c.inkSoft, fontSize = if (compact) 13.sp else 16.sp, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                cur.item?.takeIf { it.kind == Kind.LIVE }?.let { ch ->
                    vm.nowNext(ch).first?.let { nw -> androidx.compose.material3.Text("${tr("Ahora")}: ${nw.title}", color = c.ink, fontSize = 14.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp)) }
                    vm.nowNext(ch).second?.let { nx -> androidx.compose.material3.Text("${tr("Después")}: ${nx.title}", color = c.inkSoft, fontSize = 13.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp)) }
                }
                if (cur.progress > 0f) Box(Modifier.padding(top = 10.dp).width(260.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.2f))) {
                    Box(Modifier.fillMaxWidth(cur.progress).fillMaxHeight().background(c.accent))
                }
                Row(Modifier.padding(top = if (compact) 10.dp else 22.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HorizonButton(if (cur.item == null) tr("Abrir") else if (cur.progress > 0f) "▶  " + tr("Continuar") else "▶  " + tr("Ver"), primary = true) { activate(cur) }
                    cur.item?.let { it0 ->
                        val fav = it0.id in vm.favorites
                        HorizonButton(if (fav) "★  " + tr("Quitar de favoritos") else "☆  " + tr("Favorito")) { vm.toggleFavorite(it0) }
                        if (it0.kind != Kind.LIVE) HorizonButton("ⓘ  " + tr("Detalles")) { vm.choose(it0) }
                    }
                    if (k != null) HorizonButton("☰  " + tr("Categorías")) { ctl.dialog = "cats" }
                }
            }
        }
    }
    ConsoleDialogs(vm, ctl)
}

@Composable
private fun HorizonTab(label: String, selected: Boolean, compact: Boolean, onClick: () -> Unit) {
    val c = LocalIptv.current
    val src = remember { MutableInteractionSource() }
    val f by src.collectIsFocusedAsState()
    Column(horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (f) Color.White.copy(alpha = 0.16f) else Color.Transparent)
            .combinedClickable(interactionSource = src, indication = null, onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp)) {
        androidx.compose.material3.Text(label, color = if (selected || f) c.ink else c.inkSoft, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            fontSize = if (compact) 14.sp else 18.sp, maxLines = 1)
        Box(Modifier.padding(top = 4.dp).size(width = 22.dp, height = 3.dp).clip(RoundedCornerShape(2.dp)).background(if (selected) c.ink else Color.Transparent))
    }
}

@Composable
private fun HorizonRound(icon: TIcon, onClick: () -> Unit) {
    val c = LocalIptv.current
    val src = remember { MutableInteractionSource() }
    val f by src.collectIsFocusedAsState()
    Box(Modifier.size(38.dp).clip(CircleShape).background(if (f) Color.White else Color.White.copy(alpha = 0.10f))
        .combinedClickable(interactionSource = src, indication = null, onClick = onClick), contentAlignment = Alignment.Center) {
        TIconView(icon, if (f) Color(0xFF0B1426) else c.ink, Modifier.size(20.dp))
    }
}

@Composable
private fun HorizonTile(e: HEntry, selected: Boolean, small: Dp, big: Dp, modifier: Modifier, onFocused: () -> Unit,
                        onLong: (() -> Unit)?, onClick: () -> Unit) {
    val c = LocalIptv.current
    val src = remember { MutableInteractionSource() }
    val f by src.collectIsFocusedAsState()
    LaunchedEffect(f) { if (f) onFocused() }
    val size by animateDpAsState(if (selected) big else small, label = "horizon")
    var failed by remember(e.image) { mutableStateOf(e.image.isBlank()) }
    val shape = RoundedCornerShape(c.radius.dp)
    Column(Modifier.width(size)) {
        Box(
            modifier.size(size)
                .then(if (selected && !Perf.lite) Modifier.shadow(18.dp, shape) else Modifier)
                .clip(shape).background(if (e.icon != null) c.accent.copy(alpha = if (selected) 0.9f else 0.55f) else if (failed) c.tileA else Color(0xFF0E1726))
                .then(if (f) Modifier.border(3.dp, c.focus, shape) else if (selected) Modifier.border(1.dp, Color.White.copy(alpha = 0.4f), shape) else Modifier)
                .combinedClickable(interactionSource = src, indication = null, onClick = onClick, onLongClick = onLong),
            contentAlignment = Alignment.Center,
        ) {
            when {
                e.icon != null -> TIconView(e.icon, Color.White, Modifier.fillMaxSize(0.42f))
                !failed -> AsyncImage(e.image, null, contentScale = if (e.item?.kind == Kind.LIVE) ContentScale.Fit else ContentScale.Crop, onError = { failed = true },
                    modifier = Modifier.fillMaxSize().then(if (e.item?.kind == Kind.LIVE) Modifier.background(Color(0xFFF2F4F7)).padding(10.dp) else Modifier))
                else -> androidx.compose.material3.Text(initials(e.title), color = c.inkSoft, fontWeight = FontWeight.Bold, fontSize = 22.sp)
            }
            if (e.progress > 0f) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x66000000))) {
                Box(Modifier.fillMaxWidth(e.progress).fillMaxHeight().background(c.accent))
            }
        }
        // Nombre debajo, sólo del elegido (como en los menús de consola)
        if (selected) androidx.compose.material3.Text(e.title, color = c.ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun HorizonButton(label: String, primary: Boolean = false, onClick: () -> Unit) {
    val c = LocalIptv.current
    val src = remember { MutableInteractionSource() }
    val f by src.collectIsFocusedAsState()
    androidx.compose.material3.Text(label, color = if (f) Color(0xFF0B1426) else Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(50))
            .background(if (f) Color.White else if (primary) c.accent else Color.White.copy(alpha = 0.12f))
            .combinedClickable(interactionSource = src, indication = null, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 11.dp))
}
