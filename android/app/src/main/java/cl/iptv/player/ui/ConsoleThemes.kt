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
 *  · Pulso (como PS5): pestañas de texto arriba, una fila de íconos que se desplaza a la izquierda, el elegido grande
 *    con su nombre al lado, su imagen de fondo a pantalla completa, su información y botones debajo y, en Inicio,
 *    tarjetas anchas de lo que se estaba viendo.
 *  · Horizonte (como Xbox Series): perfil y pestañas arriba, la imagen grande de lo enfocado con su nombre, filas de
 *    mosaicos de esquinas casi rectas (el primero ancho) con borde blanco grueso al enfocar, y una cuadrícula.
 * Funcionan con el control remoto (foco), el tacto y el mouse; todas las listas se desplazan solas hasta lo enfocado.
 */

private val CTABS = listOf("Inicio", "TV en vivo", "Películas", "Series", "Guía", "Ajustes")
internal fun ckind(tab: Int) = when (tab) { 1 -> Kind.LIVE; 2 -> Kind.MOVIE; 3 -> Kind.SERIES; else -> null }

/** Lo común a los dos temas: pestaña actual, abrir elementos y diálogos. */
internal class ConsoleCtl(val vm: AppViewModel) {
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
internal fun rememberConsoleCtl(vm: AppViewModel): ConsoleCtl {
    val ctl = remember(vm) { ConsoleCtl(vm) }
    LaunchedEffect(Unit) { ckind(vm.shell.tab)?.let { k -> if (vm.section != k || vm.home) vm.selectSection(k) } }
    BackHandler(enabled = ctl.dialog == null &&
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
internal fun ConsoleDialogs(vm: AppViewModel, ctl: ConsoleCtl) {
    when (ctl.dialog) {
        "search" -> SearchDialog(vm) { ctl.dialog = null }
        "cats" -> CategoriesDialog(vm) { ctl.dialog = null }
        "profiles" -> ProfilesDialog(vm) { ctl.dialog = null }
    }
}

@Composable
internal fun Clock(color: Color, size: Int) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(15_000); now = System.currentTimeMillis() } }
    androidx.compose.material3.Text(java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(now)),
        color = color, fontSize = size.sp, fontWeight = FontWeight.SemiBold)
}

/** En pantallas grandes (laptop, tableta, monitor) todo se agranda en proporción para que se vea como en la TV y no diminuto. */
@Composable
internal fun ScaledForWide(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val k = minOf(maxWidth / 960.dp, maxHeight / 540.dp)
        if (k > 1.1f) {
            val d = androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density * k, d.fontScale)) { content() }
        } else content()
    }
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
// Pulso (al estilo del menú de PS5)
// ============================================================================================

/** Elemento de la fila de Pulso: contenido o acceso. */
private class PEntry(val key: String, val title: String, val sub: String, val image: String, val icon: TIcon?, val item: Item?,
                     val progress: Float, val episode: String?, val action: (() -> Unit)?)

@Composable
fun PulseScreen(vm: AppViewModel) {
    val c = LocalIptv.current
    val ctl = rememberConsoleCtl(vm)
    val tab = vm.shell.tab
    val k = ckind(tab)
    val isTv = (androidx.compose.ui.platform.LocalContext.current as? cl.iptv.player.MainActivity)?.isTv == true
    val first = remember { FocusRequester() }

    val cont = remember(vm.items, vm.resume) { vm.continueWatching() }
    val entries: List<PEntry> = remember(tab, vm.view, vm.items, vm.resume, vm.favorites, vm.recent, vm.profile) { when {
        k != null -> vm.view.map { it0 -> PEntry(it0.id, it0.name, it0.group, it0.logo, null, it0, vm.resume[it0.id]?.fraction ?: 0f, null, null) }
        tab == 0 -> {
            val favs = vm.favorites.mapNotNull { id -> vm.items.firstOrNull { it.id == id } }.map { PEntry("f:${it.id}", it.name, tr("Favoritos"), it.logo, null, it, 0f, null, null) }
            val recents = vm.recent.mapNotNull { id -> vm.items.firstOrNull { it.id == id } }.take(12)
                .map { PEntry("r:${it.id}", it.name, tr("Recientes"), it.logo, null, it, vm.resume[it.id]?.fraction ?: 0f, null, null) }
            val going = cont.map { (it0, r, ep) -> PEntry("c:${it0.id}", it0.name, tr("Seguir viendo"), it0.logo, null, it0, r.fraction, ep, null) }
            val content = (going + favs + recents).distinctBy { it.item?.id }
            content + shortcuts(vm, ctl, isTv).map { s -> PEntry("s:${s.label}", tr(s.label.substringBefore(" · ")), s.label.substringAfter(" · ", ""), "", s.icon, null, 0f, null, s.onClick) }
        }
        tab == 4 -> listOf(PEntry("g", tr("Guía de programación"), tr("Lo que dan ahora y después"), "", TIcon.GUIDE, null, 0f, null) { vm.showGuide = true })
        else -> settingsShortcuts(vm, ctl, isTv).map { s -> PEntry("s:${s.label}", tr(s.label.substringBefore(" · ")), s.label.substringAfter(" · ", ""), "", s.icon, null, 0f, null, s.onClick) }
    } }
    var sel by remember(tab, vm.category, vm.series?.id, vm.query) { mutableStateOf(0) }
    val cur = entries.getOrNull(sel.coerceIn(0, maxOf(0, entries.lastIndex)))
    fun activate(e: PEntry) { e.action?.invoke() ?: e.item?.let { ctl.open(it, if (k != null) vm.view else vm.items, e.episode) } }

    val state = rememberLazyListState()
    // Lo elegido queda siempre al comienzo de la fila (la fila se desplaza a la izquierda)
    LaunchedEffect(sel, entries.size) { if (entries.isNotEmpty()) runCatching { state.animateScrollToItem(sel.coerceIn(0, entries.lastIndex)) } }
    LaunchedEffect(tab, vm.category, vm.series?.id, vm.query) { runCatching { state.scrollToItem(0) } }
    LaunchedEffect(ctl.dialog, tab) { if (ctl.dialog == null) { delay(80); runCatching { first.requestFocus() } } }

    ScaledForWide { BoxWithConstraints(Modifier.fillMaxSize().themeBackground()) {
        val compact = maxHeight < 480.dp || maxWidth < 600.dp
        val side = if (compact) 16.dp else 48.dp
        // Fondo: la imagen de lo elegido a pantalla completa, fundida hacia la izquierda y hacia abajo
        val img = cur?.image.orEmpty()
        if (img.startsWith("http") && !Perf.lite) {
            AsyncImage(img, null, contentScale = if (cur?.item?.kind == Kind.LIVE) ContentScale.Fit else ContentScale.Crop,
                modifier = Modifier.fillMaxSize().then(if (cur?.item?.kind == Kind.LIVE) Modifier.padding(start = maxWidth * 0.45f, top = 60.dp, bottom = maxHeight * 0.35f, end = 40.dp) else Modifier)
                    .graphicsLayer { alpha = 0.6f })
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(c.bg, c.bg.copy(alpha = 0.55f), Color.Transparent))))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, c.bg.copy(alpha = 0.75f), c.bg2))))
        }
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            // Arriba: pestañas de texto a la izquierda; buscar, ajustes, perfil y hora a la derecha
            Row(Modifier.fillMaxWidth().padding(start = side, end = side, top = if (compact) 8.dp else 22.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 8.dp)) {
                    CTABS.forEachIndexed { i, t -> PulseTab(tr(t), i == tab, compact) { ctl.goTab(i) } }
                }
                PulseRound(TIcon.SEARCH) { if (k == null) ctl.goTab(1); ctl.dialog = "search" }
                Spacer(Modifier.width(6.dp))
                PulseRound(TIcon.SETTINGS) { vm.showSettings = true }
                Spacer(Modifier.width(10.dp))
                Box(Modifier.size(30.dp).clip(CircleShape).background(Brush.linearGradient(listOf(c.accentDeep, c.accent))).combinedClickable { ctl.dialog = "profiles" },
                    contentAlignment = Alignment.Center) {
                    androidx.compose.material3.Text(initials(vm.profile.name).take(1), color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                }
                Spacer(Modifier.width(14.dp))
                Clock(c.ink, if (compact) 14 else 16)
            }
            if (k != null) androidx.compose.material3.Text(vm.series?.name ?: vm.title(), color = c.inkSoft, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = side, top = 8.dp))
            // Fila de íconos: el elegido, grande, con su nombre al lado
            val small = if (compact) 52.dp else 68.dp
            val big = if (compact) 80.dp else 112.dp
            LazyRow(state = state, horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp),
                contentPadding = PaddingValues(start = side, end = 600.dp, top = if (compact) 10.dp else 18.dp, bottom = 6.dp), verticalAlignment = Alignment.Top,
                modifier = Modifier.height(big + (if (compact) 10.dp else 18.dp) + 14.dp)) {
                if (entries.isEmpty()) item {
                    androidx.compose.material3.Text(tr(if (vm.loading != null) "Cargando…" else "No hay contenido para mostrar."), color = c.inkSoft, fontSize = 18.sp)
                }
                itemsIndexed(entries, key = { i, e -> "$i:${e.key}" }) { i, e ->
                    PulseTile(e, selected = i == sel, small = small, big = big,
                        modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                        onFocused = { sel = i },
                        onLong = e.item?.let { it0 -> { vm.toggleFavorite(it0) } },
                    ) { if (i == sel) activate(e) else sel = i }   // tocar: el primer toque elige, el segundo abre
                }
            }
            // Debajo: categoría, título grande, información y botones
            if (cur != null) Column(Modifier.padding(start = side, end = side, top = if (compact) 4.dp else 14.dp).widthIn(max = 640.dp)) {
                if (cur.sub.isNotBlank()) androidx.compose.material3.Text(cur.sub.uppercase(), color = c.inkSoft, fontSize = if (compact) 10.sp else 11.sp,
                    fontWeight = FontWeight.Medium, letterSpacing = 1.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                androidx.compose.material3.Text(cur.title, color = c.ink, fontWeight = FontWeight.Bold, fontSize = if (compact) 22.sp else 34.sp,
                    lineHeight = if (compact) 26.sp else 40.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                cur.item?.takeIf { it.kind == Kind.LIVE }?.let { ch ->
                    vm.nowNext(ch).first?.let { nw -> androidx.compose.material3.Text("${tr("Ahora")}: ${nw.title}", color = c.ink, fontSize = 13.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp)) }
                    vm.nowNext(ch).second?.let { nx -> androidx.compose.material3.Text("${tr("Después")}: ${nx.title}", color = c.inkSoft, fontSize = 12.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp)) }
                }
                if (cur.progress > 0f) Box(Modifier.padding(top = 10.dp).width(180.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.2f))) {
                    Box(Modifier.fillMaxWidth(cur.progress).fillMaxHeight().background(c.accent))
                }
                Row(Modifier.padding(top = if (compact) 10.dp else 16.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PulseButton(if (cur.item == null) tr("Abrir") else if (cur.progress > 0f) "▶  " + tr("Continuar") else "▶  " + tr("Ver")) { activate(cur) }
                    cur.item?.let { it0 ->
                        val fav = it0.id in vm.favorites
                        PulseButton(if (fav) "★  " + tr("Quitar de favoritos") else "☆  " + tr("Favorito")) { vm.toggleFavorite(it0) }
                    }
                    if (k != null) PulseButton("☰  " + tr("Categorías")) { ctl.dialog = "cats" }
                }
            }
            // Inicio: tarjetas anchas abajo, de lo que se estaba viendo (o, si no hay, de los favoritos)
            val favItems = remember(vm.items, vm.favorites) { vm.favorites.mapNotNull { id -> vm.items.firstOrNull { it.id == id } } }
            val cards = if (cont.isNotEmpty()) cont else favItems.map { Triple(it, null, null) }
            if (tab == 0 && cards.isNotEmpty() && !compact) {
                Spacer(Modifier.weight(1f))
                androidx.compose.material3.Text(tr(if (cont.isNotEmpty()) "Seguir viendo" else "Favoritos"), color = c.ink, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                    modifier = Modifier.padding(start = side, bottom = 8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(start = side, end = side, bottom = 20.dp)) {
                    itemsIndexed(cards, key = { i, t -> "$i:${t.first.id}" }) { _, (it0, r, ep) ->
                        PulseCard(it0, r?.fraction ?: 0f) { ctl.open(it0, if (r == null) favItems else vm.items, ep) }
                    }
                }
            }
        }
    } }
    ConsoleDialogs(vm, ctl)
}

@Composable
private fun PulseTab(label: String, selected: Boolean, compact: Boolean, onClick: () -> Unit) {
    val c = LocalIptv.current
    val src = remember { MutableInteractionSource() }
    val f by src.collectIsFocusedAsState()
    androidx.compose.material3.Text(label, color = if (selected || f) c.ink else c.ink.copy(alpha = 0.55f), fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        fontSize = if (compact) 13.sp else 15.sp, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (f) Color.White.copy(alpha = 0.16f) else Color.Transparent)
            .combinedClickable(interactionSource = src, indication = null, onClick = onClick).padding(horizontal = 10.dp, vertical = 5.dp))
}

@Composable
private fun PulseRound(icon: TIcon, onClick: () -> Unit) {
    val c = LocalIptv.current
    val src = remember { MutableInteractionSource() }
    val f by src.collectIsFocusedAsState()
    Box(Modifier.size(30.dp).clip(CircleShape).background(if (f) Color.White else Color.Transparent)
        .combinedClickable(interactionSource = src, indication = null, onClick = onClick), contentAlignment = Alignment.Center) {
        TIconView(icon, if (f) Color(0xFF0B1426) else c.ink, Modifier.size(18.dp))
    }
}

/** Ícono de la fila: el elegido crece, brilla con borde blanco y muestra su nombre a la derecha. */
@Composable
private fun PulseTile(e: PEntry, selected: Boolean, small: Dp, big: Dp, modifier: Modifier, onFocused: () -> Unit,
                      onLong: (() -> Unit)?, onClick: () -> Unit) {
    val c = LocalIptv.current
    val src = remember { MutableInteractionSource() }
    val f by src.collectIsFocusedAsState()
    LaunchedEffect(f) { if (f) onFocused() }
    val size by animateDpAsState(if (selected) big else small, label = "pulse")
    var failed by remember(e.image) { mutableStateOf(e.image.isBlank()) }
    val shape = RoundedCornerShape(c.radius.dp)
    Row(verticalAlignment = Alignment.Bottom) {
        Box(
            modifier.size(size)
                .then(if (f && !Perf.lite) Modifier.shadow(22.dp, shape, ambientColor = c.accent, spotColor = c.accent) else Modifier)
                .clip(shape).background(if (e.icon != null) Brush.linearGradient(listOf(c.accent.copy(alpha = if (selected) 0.95f else 0.6f), c.bg))
                    else Brush.linearGradient(listOf(if (failed) c.tileA else Color(0xFF0E1726), if (failed) c.tileB else Color(0xFF0E1726))))
                .then(if (f) Modifier.border(3.dp, c.focus, shape) else Modifier)
                .combinedClickable(interactionSource = src, indication = null, onClick = onClick, onLongClick = onLong),
            contentAlignment = Alignment.Center,
        ) {
            when {
                e.icon != null -> TIconView(e.icon, Color.White, Modifier.fillMaxSize(0.42f))
                !failed -> AsyncImage(e.image, null, contentScale = if (e.item?.kind == Kind.LIVE) ContentScale.Fit else ContentScale.Crop, onError = { failed = true },
                    modifier = Modifier.fillMaxSize().then(if (e.item?.kind == Kind.LIVE) Modifier.background(Color(0xFFF2F4F7)).padding(10.dp) else Modifier))
                else -> androidx.compose.material3.Text(initials(e.title), color = c.inkSoft, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
            if (e.progress > 0f) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x66000000))) {
                Box(Modifier.fillMaxWidth(e.progress).fillMaxHeight().background(c.accent))
            }
        }
        // Nombre al lado, sólo del elegido (como en los menús de consola)
        if (selected) androidx.compose.material3.Text(e.title, color = c.ink, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp, bottom = 4.dp).widthIn(max = 160.dp))
    }
}

@Composable
private fun PulseButton(label: String, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val f by src.collectIsFocusedAsState()
    androidx.compose.material3.Text(label, color = if (f) Color(0xFF0B1426) else Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(8.dp))
            .background(if (f) Color.White else Color.White.copy(alpha = 0.14f))
            .combinedClickable(interactionSource = src, indication = null, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 9.dp))
}

/** Tarjeta ancha de "Seguir viendo" (abajo en Inicio). */
@Composable
private fun PulseCard(item: Item, progress: Float, onClick: () -> Unit) {
    val c = LocalIptv.current
    val src = remember { MutableInteractionSource() }
    val f by src.collectIsFocusedAsState()
    var failed by remember(item.logo) { mutableStateOf(item.logo.isBlank()) }
    val shape = RoundedCornerShape(c.radius.dp)
    Box(Modifier.width(200.dp).height(100.dp).clip(shape).background(c.tileA)
        .then(if (f) Modifier.border(3.dp, c.focus, shape) else Modifier)
        .combinedClickable(interactionSource = src, indication = null, onClick = onClick)) {
        if (!failed) AsyncImage(item.logo, null, contentScale = if (item.kind == Kind.LIVE) ContentScale.Fit else ContentScale.Crop, onError = { failed = true },
            modifier = Modifier.fillMaxSize())
        else androidx.compose.material3.Text(initials(item.name), color = c.inkSoft, fontWeight = FontWeight.Bold, fontSize = 20.sp,
            modifier = Modifier.align(Alignment.TopStart).padding(10.dp))
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
            .padding(horizontal = 10.dp, vertical = 6.dp)) {
            androidx.compose.material3.Text(item.name, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (progress > 0f) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x66000000))) {
            Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(c.accent))
        }
    }
}

// ============================================================================================
// Horizonte (al estilo del menú de Xbox Series)
// ============================================================================================

@Composable
fun HorizonScreen(vm: AppViewModel) {
    val c = LocalIptv.current
    val ctl = rememberConsoleCtl(vm)
    val tab = vm.shell.tab
    val first = remember { FocusRequester() }
    val isTv = (androidx.compose.ui.platform.LocalContext.current as? cl.iptv.player.MainActivity)?.isTv == true
    var focused by remember { mutableStateOf<Item?>(null) }
    // Con el tacto nada queda enfocado: el destacado parte con lo que se estaba viendo o el primer favorito
    val firstPick = remember(vm.items, vm.resume, vm.favorites) {
        vm.continueWatching().firstOrNull()?.first ?: vm.favorites.firstNotNullOfOrNull { id -> vm.items.firstOrNull { it.id == id } }
    }
    val hero = focused ?: firstPick
    LaunchedEffect(ctl.dialog, tab) { if (ctl.dialog == null) { delay(80); runCatching { first.requestFocus() } } }

    ScaledForWide { BoxWithConstraints(Modifier.fillMaxSize().themeBackground()) {
        val compact = maxHeight < 480.dp || maxWidth < 600.dp
        val side = if (compact) 16.dp else 40.dp
        val k = ckind(tab)
        // Arriba, la imagen grande de lo enfocado, fundida hacia abajo y hacia la izquierda
        val heroImg = hero?.logo.orEmpty()
        if (heroImg.startsWith("http") && hero?.kind != Kind.LIVE && !Perf.lite && (tab == 0 || k != null)) {
            Box(Modifier.fillMaxWidth().fillMaxHeight(if (k == null) 0.68f else 0.45f)) {
                AsyncImage(heroImg, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (k == null) 0.6f else 0.3f })
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(c.bg2.copy(alpha = 0.9f), Color.Transparent))))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, c.bg2.copy(alpha = 0.6f), c.bg2))))
            }
        }
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            // Barra superior: perfil a la izquierda, pestañas, y a la derecha buscar, ajustes y hora
            Row(Modifier.fillMaxWidth().padding(start = side, end = side, top = if (compact) 8.dp else 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.clip(RoundedCornerShape(50)).combinedClickable { ctl.dialog = "profiles" }.padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(if (compact) 28.dp else 30.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
                        androidx.compose.material3.Text(initials(vm.profile.name).take(1), color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                    }
                    if (!compact) androidx.compose.material3.Text(vm.profile.name, color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp).widthIn(max = 120.dp))
                }
                Row(Modifier.weight(1f).padding(start = 12.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    CTABS.forEachIndexed { i, t -> HorizonTab(tr(t), selected = i == tab, compact = compact) { ctl.goTab(i) } }
                }
                HorizonRound(TIcon.SEARCH) { if (k == null) ctl.goTab(1); ctl.dialog = "search" }
                Spacer(Modifier.width(4.dp))
                HorizonRound(TIcon.SETTINGS) { vm.showSettings = true }
                Spacer(Modifier.width(12.dp))
                Clock(c.ink, if (compact) 14 else 15)
            }
            if (k == null) {
                // Destacado: el nombre grande de lo enfocado, sobre su imagen
                if (hero != null && tab == 0) Column(Modifier.padding(start = side, end = side, top = if (compact) 8.dp else 40.dp).height(if (compact) 40.dp else 76.dp).widthIn(max = 640.dp),
                    verticalArrangement = Arrangement.Bottom) {
                    hero?.let { h ->
                        androidx.compose.material3.Text(h.name, color = c.ink, fontWeight = FontWeight.Bold, fontSize = if (compact) 20.sp else 30.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val sub = vm.resume[h.id]?.let { tr("Continúa donde lo dejaste") } ?: h.group
                        if (sub.isNotBlank() && !compact) androidx.compose.material3.Text(sub, color = c.inkSoft, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                HorizonRows(vm, ctl, tab, isTv, side, compact, first) { focused = it }
            } else {
                Spacer(Modifier.height(if (compact) 8.dp else 16.dp))
                HorizonGrid(vm, ctl, k, side, compact, first) { focused = it }
            }
        }
    } }
    ConsoleDialogs(vm, ctl)
}

@Composable
private fun HorizonTab(label: String, selected: Boolean, compact: Boolean, onClick: () -> Unit) {
    val c = LocalIptv.current
    val src = remember { MutableInteractionSource() }
    val f by src.collectIsFocusedAsState()
    androidx.compose.material3.Text(
        label, color = if (f) Color(0xFF111416) else if (selected) c.ink else c.ink.copy(alpha = 0.7f),
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, fontSize = if (compact) 12.sp else 13.sp, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(c.radius.dp)).background(if (f) Color.White else if (selected) Color.White.copy(alpha = 0.16f) else Color.Transparent)
            .combinedClickable(interactionSource = src, indication = null, onClick = onClick)
            .padding(horizontal = if (compact) 10.dp else 12.dp, vertical = if (compact) 5.dp else 6.dp),
    )
}

@Composable
private fun HorizonRound(icon: TIcon, onClick: () -> Unit) {
    val c = LocalIptv.current
    val src = remember { MutableInteractionSource() }
    val f by src.collectIsFocusedAsState()
    Box(Modifier.size(30.dp).clip(RoundedCornerShape(c.radius.dp)).background(if (f) Color.White else Color.Transparent)
        .combinedClickable(interactionSource = src, indication = null, onClick = onClick), contentAlignment = Alignment.Center) {
        TIconView(icon, if (f) Color(0xFF111416) else c.ink, Modifier.size(18.dp))
    }
}

/** Inicio, Guía y Ajustes: filas de mosaicos una debajo de otra (la columna y cada fila se desplazan). */
@Composable
private fun HorizonRows(vm: AppViewModel, ctl: ConsoleCtl, tab: Int, isTv: Boolean, side: Dp, compact: Boolean, first: FocusRequester, onHero: (Item?) -> Unit) {
    val tileH = if (compact) 92.dp else 146.dp
    val wide = tileH * 1.78f
    val cont = remember(vm.items, vm.resume) { vm.continueWatching() }
    val favs = remember(vm.items, vm.favorites) { vm.favorites.mapNotNull { id -> vm.items.firstOrNull { it.id == id } } }
    val recents = remember(vm.items, vm.recent) { vm.recent.mapNotNull { id -> vm.items.firstOrNull { it.id == id } } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = if (compact) 6.dp else 14.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 20.dp)) {
        when (tab) {
            0 -> {
                // Fila principal sin título: lo que se estaba viendo (el primero ancho) y los accesos
                item {
                    HorizonRow(null, side) {
                        itemsIndexed(cont) { i, (it0, r, ep) ->
                            HorizonTile(it0, Modifier.height(tileH).width(if (i == 0) wide else tileH).then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                                progress = r.fraction, onFocus = onHero) { ctl.open(it0, vm.items, ep) }
                        }
                        itemsIndexed(shortcuts(vm, ctl, isTv)) { i, s ->
                            HorizonIconTile(s.label, s.icon, Modifier.size(tileH).then(if (i == 0 && cont.isEmpty()) Modifier.focusRequester(first) else Modifier), s.onClick)
                        }
                    }
                }
                if (favs.isNotEmpty()) item { HorizonRow("Favoritos", side) { itemsIndexed(favs) { _, it0 -> HorizonTile(it0, Modifier.height(if (compact) tileH else tileH * 0.8f).width(tileH * 1.1f), onFocus = onHero, onLong = { vm.toggleFavorite(it0) }) { ctl.open(it0, favs) } } } }
                if (recents.isNotEmpty()) item { HorizonRow("Recientes", side) { itemsIndexed(recents) { _, it0 -> HorizonTile(it0, Modifier.height(if (compact) tileH else tileH * 0.8f).width(tileH * 1.1f), onFocus = onHero) { ctl.open(it0, recents) } } } }
            }
            4 -> item {
                HorizonRow("Guía", side) {
                    itemsIndexed(listOf(Shortcut("Guía de programación", TIcon.GUIDE) { vm.showGuide = true }, Shortcut("TV en vivo", TIcon.TV) { ctl.goTab(1) })) { i, s ->
                        HorizonIconTile(s.label, s.icon, Modifier.height(tileH).width(if (i == 0) wide else tileH).then(if (i == 0) Modifier.focusRequester(first) else Modifier), s.onClick)
                    }
                }
            }
            else -> item {
                HorizonRow("Ajustes", side) {
                    itemsIndexed(settingsShortcuts(vm, ctl, isTv)) { i, s ->
                        HorizonIconTile(s.label, s.icon, Modifier.size(tileH).then(if (i == 0) Modifier.focusRequester(first) else Modifier), s.onClick)
                    }
                }
            }
        }
    }
}

@Composable
private fun HorizonRow(title: String?, side: Dp, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    val c = LocalIptv.current
    Column {
        if (title != null) androidx.compose.material3.Text(tr(title), color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.padding(start = side, bottom = 6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = side, vertical = 6.dp), content = content)
    }
}

/** TV en vivo, Películas y Series: accesos arriba y la cuadrícula de contenido, que se recorre hacia abajo. */
@Composable
private fun HorizonGrid(vm: AppViewModel, ctl: ConsoleCtl, k: Kind, side: Dp, compact: Boolean, first: FocusRequester, onHero: (Item?) -> Unit) {
    val c = LocalIptv.current
    val view = vm.view
    val state = rememberLazyGridState()
    LaunchedEffect(vm.category, vm.series?.id, vm.query) { state.scrollToItem(0) }
    val minW = if (k == Kind.LIVE) (if (compact) 130.dp else 170.dp) else (if (compact) 96.dp else 130.dp)
    LazyVerticalGrid(GridCells.Adaptive(minW), state = state, modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = side, end = side, bottom = 40.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                HorizonChip("☰ " + tr("Categorías"), Modifier.focusRequester(first)) { ctl.dialog = "cats" }
                HorizonChip(if (k == Kind.LIVE) "★ " + tr("Favoritos") else "▶ " + tr("Seguir viendo"), active = vm.category == CAT_FAV || vm.category == CAT_RESUME) {
                    vm.selectCategory(if (k == Kind.LIVE) CAT_FAV else CAT_RESUME)
                }
                HorizonChip("⌕ " + tr("Buscar")) { ctl.dialog = "search" }
                androidx.compose.material3.Text(vm.series?.name ?: vm.title(), color = c.inkSoft, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (view.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            androidx.compose.material3.Text(tr(if (vm.loading != null) "Cargando…" else "No hay contenido para mostrar."), color = c.inkSoft, fontSize = 18.sp, modifier = Modifier.padding(24.dp))
        }
        gridItemsIndexed(view, key = { i, it0 -> "$i:${it0.id}" }) { _, it0 ->
            HorizonTile(it0, Modifier.fillMaxWidth().aspectRatio(if (it0.kind == Kind.LIVE) 16f / 10f else 2f / 3f),
                progress = vm.resume[it0.id]?.fraction ?: 0f,
                sub = if (it0.kind == Kind.LIVE) vm.nowNext(it0).first?.title.orEmpty() else "",
                onFocus = onHero, onLong = { vm.toggleFavorite(it0) }) { ctl.open(it0, view) }
        }
    }
}

@Composable
private fun HorizonChip(label: String, modifier: Modifier = Modifier, active: Boolean = false, onClick: () -> Unit) {
    val c = LocalIptv.current
    val src = remember { MutableInteractionSource() }
    val f by src.collectIsFocusedAsState()
    androidx.compose.material3.Text(label, color = if (f) Color(0xFF111416) else c.ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1,
        modifier = modifier.clip(RoundedCornerShape(c.radius.dp)).background(if (f) Color.White else if (active) c.accent.copy(alpha = 0.4f) else c.tileA)
            .combinedClickable(interactionSource = src, indication = null, onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp))
}

/** Mosaico de esquinas apenas redondeadas; al enfocarlo crece un poco, lo rodea un borde blanco grueso y aparece su nombre. */
@Composable
private fun HorizonTile(item: Item, modifier: Modifier, progress: Float = 0f, sub: String = "", onFocus: (Item?) -> Unit = {},
                        onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    val c = LocalIptv.current
    val src = remember { MutableInteractionSource() }
    val f by src.collectIsFocusedAsState()
    LaunchedEffect(f) { if (f) onFocus(item) }
    val scale by animateFloatAsState(if (f && !Perf.lite) 1.04f else 1f, label = "horizon")
    var failed by remember(item.logo) { mutableStateOf(item.logo.isBlank()) }
    val shape = RoundedCornerShape(c.radius.dp)
    Box(
        modifier.zIndex(if (f) 1f else 0f).graphicsLayer { scaleX = scale; scaleY = scale }
            .then(if (f && !Perf.lite) Modifier.shadow(14.dp, shape) else Modifier)
            .then(if (f) Modifier.border(4.dp, c.focus, shape) else Modifier)
            .padding(if (f) 4.dp else 0.dp)
            .clip(shape).background(if (failed) c.tileA else Color(0xFF1B1E21))
            .combinedClickable(interactionSource = src, indication = null, onClick = onClick, onLongClick = onLong),
    ) {
        if (!failed) AsyncImage(item.logo, null, contentScale = if (item.kind == Kind.LIVE) ContentScale.Fit else ContentScale.Crop, onError = { failed = true },
            modifier = Modifier.fillMaxSize().then(if (item.kind == Kind.LIVE) Modifier.padding(14.dp).padding(bottom = 16.dp) else Modifier))
        else androidx.compose.material3.Text(initials(item.name), color = c.inkSoft, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.TopStart).padding(10.dp))
        if (progress > 0f) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x66000000))) {
            Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(c.accent))
        }
        if (f || item.kind == Kind.LIVE || failed) Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xD9000000)))).padding(horizontal = 10.dp, vertical = 6.dp)) {
            androidx.compose.material3.Text(item.name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (sub.isNotBlank()) androidx.compose.material3.Text(sub, color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Acceso: mosaico gris con el ícono de color arriba y el nombre abajo; al enfocarlo, borde blanco grueso. */
@Composable
private fun HorizonIconTile(label: String, icon: TIcon, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalIptv.current
    val src = remember { MutableInteractionSource() }
    val f by src.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (f && !Perf.lite) 1.04f else 1f, label = "horizonIcon")
    val shape = RoundedCornerShape(c.radius.dp)
    BoxWithConstraints(
        modifier.zIndex(if (f) 1f else 0f).graphicsLayer { scaleX = scale; scaleY = scale }
            .then(if (f) Modifier.border(4.dp, c.focus, shape) else Modifier)
            .padding(if (f) 4.dp else 0.dp)
            .clip(shape).background(c.tileA).then(if (f) Modifier.background(Color.White.copy(alpha = 0.08f)) else Modifier)
            .combinedClickable(interactionSource = src, indication = null, onClick = onClick),
    ) {
        // Mosaico chico (teléfono): ícono y texto más pequeños para que no se tapen
        val small = maxHeight < 120.dp
        val pad = if (small) 9.dp else 14.dp
        TIconView(icon, c.accent, Modifier.align(Alignment.TopStart).padding(pad).size(if (small) 22.dp else 34.dp))
        androidx.compose.material3.Text(tr(label.substringBefore(" · ")) + (label.substringAfter(" · ", "").let { if (it.isBlank()) "" else " · $it" }),
            color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = if (small) 11.sp else 14.sp, lineHeight = if (small) 13.sp else 18.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.BottomStart).padding(pad))
    }
}
