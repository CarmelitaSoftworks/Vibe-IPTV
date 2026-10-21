package cl.iptv.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import cl.iptv.player.ui.theme.LocalIptv
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

private val TABS = listOf("Inicio", "TV en vivo", "Películas", "Series", "Guía", "Ajustes")
/** Texto traducido y en minúscula (estilo de las pestañas y mosaicos). */
private fun lc(s: String) = tr(s).lowercase()
private fun kindOf(tab: Int) = when (tab) { 1 -> Kind.LIVE; 2 -> Kind.MOVIE; 3 -> Kind.SERIES; else -> null }

/**
 * Menú del tema Mosaicos: pestañas en minúscula arriba y mosaicos planos de distintos tamaños
 * (uno grande destacado, medianos con imagen y verdes con ícono), que se recorren de lado.
 */
@Composable
fun MetroScreen(vm: AppViewModel) {
    val c = LocalIptv.current
    val nav = vm.shell
    val first = remember { FocusRequester() }
    var dialog by remember { mutableStateOf<String?>(null) }
    val isTv = (androidx.compose.ui.platform.LocalContext.current as? cl.iptv.player.MainActivity)?.isTv == true

    fun goTab(t: Int) {
        nav.tab = t
        kindOf(t)?.let { k -> if (vm.section != k || vm.home) vm.selectSection(k) }
    }
    LaunchedEffect(Unit) { kindOf(nav.tab)?.let { k -> if (vm.section != k || vm.home) vm.selectSection(k) } }
    LaunchedEffect(dialog, vm.details) { if (dialog == null && vm.details == null) { delay(80); runCatching { first.requestFocus() } } }

    BackHandler(enabled = dialog == null && vm.details == null && (vm.series != null || vm.category != CAT_ALL || vm.query.isNotBlank() || nav.tab != 0)) {
        when {
            vm.series != null || vm.query.isNotBlank() -> vm.back()
            vm.category != CAT_ALL -> vm.selectCategory(CAT_ALL)
            else -> goTab(0)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().themeBackground().systemBarsPadding()) {
        val compact = maxHeight < 480.dp
        val side = if (compact) 48.dp else 110.dp
        Column(Modifier.fillMaxSize()) {
            // Pestañas
            Row(Modifier.fillMaxWidth().padding(start = side - 14.dp, top = if (compact) 10.dp else 34.dp, end = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                TABS.forEachIndexed { i, t ->
                    val src = remember { MutableInteractionSource() }
                    val focused by src.collectIsFocusedAsState()
                    LaunchedEffect(focused) { if (focused && nav.tab != i) goTab(i) }
                    Text(
                        tr(t).lowercase(), fontSize = if (compact) 22.sp else 34.sp,
                        fontWeight = if (i == nav.tab) FontWeight.Normal else FontWeight.Light,
                        color = if (i == nav.tab) c.ink else c.inkSoft.copy(alpha = 0.75f),
                        modifier = Modifier.clip(RoundedCornerShape(2.dp))
                            .then(if (focused) Modifier.background(c.accent.copy(alpha = 0.18f)) else Modifier)
                            .clickable(src, null) { goTab(i) }.padding(horizontal = 14.dp, vertical = 4.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(3.dp)).background(c.accent), contentAlignment = Alignment.Center) {
                    TIconView(TIcon.PROFILE, Color.White, Modifier.size(24.dp))
                }
            }
            // Subtítulo: categoría o serie elegida
            val subtitle = when { kindOf(nav.tab) == null -> ""; vm.series != null -> vm.series!!.name; else -> vm.title() }
            Text(subtitle.lowercase(), color = c.inkSoft, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = side, top = 2.dp, bottom = 6.dp).height(20.dp))

            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val gap = if (compact) 6.dp else 9.dp
                val tileH = ((maxHeight - (if (compact) 26.dp else 44.dp)) - gap * 2) / 3
                val tileW = tileH * 1.32f
                when (val k = kindOf(nav.tab)) {
                    null -> when (nav.tab) {
                        0 -> MetroHome(vm, tileW, tileH, gap, side, first, onTab = ::goTab, onSearch = { goTab(1); dialog = "search" })
                        4 -> MetroRow(side, gap) {
                            item { MetroTile(lc("Guía de programación"), Modifier.size(tileW * 2 + gap, tileH * 2 + gap).focusRequester(first), icon = TIcon.GUIDE, green = true) { vm.showGuide = true } }
                            item {
                                Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                                    MetroTile(lc("Buscar programa"), Modifier.size(tileW, tileH), icon = TIcon.SEARCH, green = true) { vm.showGuide = true }
                                    MetroTile(lc("TV en vivo"), Modifier.size(tileW, tileH), icon = TIcon.TV) { goTab(1) }
                                }
                            }
                        }
                        else -> MetroRow(side, gap) {
                            item { MetroTile(lc("Ajustes"), Modifier.size(tileW * 2 + gap, tileH * 2 + gap).focusRequester(first), icon = TIcon.SETTINGS, green = true) { vm.showSettings = true } }
                            item {
                                Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                                    MetroTile(lc("Mis listas"), Modifier.size(tileW, tileH), icon = TIcon.LISTS, green = true) { vm.showWelcome = true }
                                    MetroTile("${lc("Perfil")} · ${vm.profile.name}", Modifier.size(tileW, tileH), icon = TIcon.PROFILE) { dialog = "profiles" }
                                    MetroTile(lc("Buscar actualizaciones"), Modifier.size(tileW, tileH), icon = TIcon.UPDATE) { vm.checkUpdate(isTv, silent = false) }
                                }
                            }
                        }
                    }
                    else -> {
                        val view = vm.view
                        val columns = remember(view) { view.chunked(3) }
                        val state = rememberLazyListState()
                        LaunchedEffect(vm.category, vm.series?.id, vm.query) { state.scrollToItem(0) }
                        MetroRow(side, gap, state) {
                            item {
                                Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                                    MetroTile(lc("Categorías"), Modifier.size(tileW, tileH).focusRequester(first), icon = TIcon.CATEGORIES, green = true) { dialog = "cats" }
                                    MetroTile(if (k == Kind.LIVE) lc("Favoritos") else lc("Seguir viendo"), Modifier.size(tileW, tileH), icon = if (k == Kind.LIVE) TIcon.STAR else TIcon.PLAY, green = true) {
                                        vm.selectCategory(if (k == Kind.LIVE) CAT_FAV else CAT_RESUME)
                                    }
                                    MetroTile(lc("Buscar"), Modifier.size(tileW, tileH), icon = TIcon.SEARCH, green = true) { dialog = "search" }
                                }
                            }
                            if (view.isEmpty()) item {
                                Box(Modifier.size(tileW * 2, tileH * 3 + gap * 2), contentAlignment = Alignment.Center) {
                                    Text(if (vm.loading != null) lc("Cargando…") else lc("No hay contenido para mostrar."), color = c.inkSoft, fontSize = 18.sp)
                                }
                            }
                            items(columns, key = { col -> col.joinToString("|") { it.id } }) { col ->
                                Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                                    col.forEach { it0 ->
                                        MetroTile(
                                            it0.name, Modifier.size(tileW, tileH), image = it0.logo, poster = it0.kind != Kind.LIVE,
                                            progress = vm.resume[it0.id]?.fraction ?: 0f,
                                            sub = if (it0.kind == Kind.LIVE) vm.nowNext(it0).first?.title.orEmpty() else "",
                                            onLong = { vm.toggleFavorite(it0) },
                                        ) { if (it0.kind == Kind.LIVE) vm.activate(it0, view) else vm.choose(it0) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // Ayuda abajo y reloj
            var now by remember { mutableStateOf(System.currentTimeMillis()) }
            LaunchedEffect(Unit) { while (true) { delay(15_000); now = System.currentTimeMillis() } }
            Row(Modifier.fillMaxWidth().padding(start = side, end = 28.dp, bottom = if (compact) 6.dp else 18.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(22.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
                    Text("OK", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                }
                Text("  " + lc("Seleccionar"), color = c.ink, fontSize = 14.sp)
                Spacer(Modifier.weight(1f))
                Text(java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(now)), color = c.ink, fontSize = 16.sp)
            }
        }
    }

    when (dialog) {
        "search" -> SearchDialog(vm) { dialog = null }
        "cats" -> CategoriesDialog(vm) { dialog = null }
        "profiles" -> ProfilesDialog(vm) { dialog = null }
    }
    vm.details?.let { DetailsScreen(vm, it) }
}

@Composable
private fun MetroRow(side: Dp, gap: Dp, state: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState(),
                     content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyRow(state = state, horizontalArrangement = Arrangement.spacedBy(gap), contentPadding = PaddingValues(start = side, end = 80.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Top, content = content)
}

/** Inicio: destacado grande con lo último que viste, accesos verdes y las secciones. */
@Composable
private fun MetroHome(vm: AppViewModel, tileW: Dp, tileH: Dp, gap: Dp, side: Dp, first: FocusRequester, onTab: (Int) -> Unit, onSearch: () -> Unit) {
    val cont = remember(vm.items, vm.resume) { vm.continueWatching() }
    val last = remember(vm.items) { vm.store.lastItemId?.let { id -> vm.items.firstOrNull { it.id == id } } }
    fun open(item: Item, ep: String? = null) {
        if (item.kind == Kind.LIVE) vm.activate(item, vm.items.filter { it.kind == Kind.LIVE }) else vm.openContinue(item, ep)
    }
    MetroRow(side, gap) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                val c0 = cont.getOrNull(1)
                if (c0 != null) MetroTile(c0.first.name, Modifier.size(tileW, tileH), image = c0.first.logo, poster = true, progress = c0.second.fraction) { open(c0.first, c0.third) }
                else MetroTile(lc("Películas"), Modifier.size(tileW, tileH), icon = TIcon.MOVIE) { onTab(2) }
                MetroTile(lc("Favoritos"), Modifier.size(tileW, tileH), icon = TIcon.STAR, green = true) { onTab(1); vm.selectCategory(CAT_FAV) }
                MetroTile(lc("Recientes"), Modifier.size(tileW, tileH), icon = TIcon.CLOCK, green = true) { onTab(1); vm.selectCategory(CAT_RECENT) }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                val heroItem = cont.firstOrNull()?.first ?: last
                val heroMod = Modifier.size(tileW * 2 + gap, tileH * 2 + gap).focusRequester(first)
                if (heroItem != null) MetroTile(
                    "${lc("Continuar")}: ${heroItem.name}", heroMod, image = heroItem.logo, poster = heroItem.kind != Kind.LIVE,
                    progress = vm.resume[heroItem.id]?.fraction ?: cont.firstOrNull()?.second?.fraction ?: 0f, big = true,
                ) { open(heroItem, cont.firstOrNull()?.third) }
                else MetroTile(lc("TV en vivo"), heroMod, icon = TIcon.TV, green = true, big = true) { onTab(1) }
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    val c2 = cont.getOrNull(2)
                    if (c2 != null) MetroTile(c2.first.name, Modifier.size(tileW, tileH), image = c2.first.logo, poster = true, progress = c2.second.fraction) { open(c2.first, c2.third) }
                    else MetroTile(lc("Series"), Modifier.size(tileW, tileH), icon = TIcon.SERIES) { onTab(3) }
                    MetroTile(lc("Guía"), Modifier.size(tileW, tileH), icon = TIcon.GUIDE, green = true) { vm.showGuide = true }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                MetroTile("${lc("TV en vivo")} · ${vm.countOf(Kind.LIVE)}", Modifier.size(tileW, tileH), icon = TIcon.TV) { onTab(1) }
                MetroTile("${lc("Películas")} · ${vm.countOf(Kind.MOVIE)}", Modifier.size(tileW, tileH), icon = TIcon.MOVIE) { onTab(2) }
                MetroTile("${lc("Series")} · ${vm.countOf(Kind.SERIES)}", Modifier.size(tileW, tileH), icon = TIcon.SERIES) { onTab(3) }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                MetroTile(lc("Buscar"), Modifier.size(tileW, tileH), icon = TIcon.SEARCH, green = true) { onSearch() }
                MetroTile(lc("Mis listas"), Modifier.size(tileW, tileH), icon = TIcon.LISTS) { vm.showWelcome = true }
                MetroTile(lc("Ajustes"), Modifier.size(tileW, tileH), icon = TIcon.SETTINGS) { vm.showSettings = true }
            }
        }
    }
}

/** Mosaico plano: verde con ícono, o imagen con su nombre en una franja abajo. Al enfocarlo crece un poco. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MetroTile(
    label: String, modifier: Modifier, image: String = "", icon: TIcon? = null, green: Boolean = false, poster: Boolean = false,
    big: Boolean = false, progress: Float = 0f, sub: String = "", onLong: (() -> Unit)? = null, onClick: () -> Unit,
) {
    val c = LocalIptv.current
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused && !cl.iptv.player.data.Perf.lite) 1.07f else 1f, label = "metro")
    var imgFailed by remember(image) { mutableStateOf(image.isBlank()) }
    Box(
        modifier.zIndex(if (focused) 1f else 0f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .then(if (focused && !cl.iptv.player.data.Perf.lite) Modifier.shadow(14.dp, RoundedCornerShape(2.dp)) else Modifier)
            .clip(RoundedCornerShape(2.dp))
            .background(if (green) c.accent else if (imgFailed) c.tileA else Color(0xFF1E1E1E))
            .then(if (focused) Modifier.border(3.dp, if (green) Color.White else c.focus, RoundedCornerShape(2.dp)) else Modifier)
            .combinedClickable(interactionSource = src, indication = null, onClick = onClick, onLongClick = onLong),
    ) {
        if (!imgFailed) AsyncImage(
            image, null, contentScale = if (poster) ContentScale.Crop else ContentScale.Fit, onError = { imgFailed = true },
            modifier = Modifier.fillMaxSize().then(if (poster) Modifier else Modifier.background(Color(0xFFF7F7F7)).padding(horizontal = 18.dp, vertical = 14.dp).padding(bottom = 18.dp)),
        )
        else if (icon != null) TIconView(icon, if (green) Color.White else c.accent, Modifier.align(Alignment.Center).offset(y = (-8).dp).fillMaxSize(if (big) 0.28f else 0.36f), stroke = 1.6f)
        else Text(initials(label), color = c.inkSoft, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
        if (progress > 0f) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(bottom = 26.dp).height(4.dp).background(Color(0x66000000))) {
            Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(c.accent))
        }
        // Nombre abajo a la izquierda (franja oscura sobre las imágenes)
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .then(if (!green && !imgFailed) Modifier.background(Brush.verticalGradient(listOf(Color(0xB3262626), Color(0xE6262626)))) else Modifier)
                .padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            Text(label, color = if (green || !imgFailed) Color.White else c.ink, fontSize = if (big) 17.sp else 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub.isNotBlank() && focused) Text(sub, color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
