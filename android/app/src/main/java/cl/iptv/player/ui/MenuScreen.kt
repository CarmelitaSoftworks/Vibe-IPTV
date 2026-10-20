package cl.iptv.player.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class MenuDialog { NONE, SOURCES, CATEGORIES, SEARCH }

private val ES = Locale("es", "CL")
private val hhmm = SimpleDateFormat("HH:mm", ES)
fun fmtTime(ms: Long): String = hhmm.format(Date(ms))

@Composable
fun MenuScreen(vm: AppViewModel) {
    val c = LocalIptv.current
    var dialog by remember { mutableStateOf(MenuDialog.NONE) }
    var focusedItem by remember { mutableStateOf<Item?>(null) }
    // Teléfono en horizontal (poca altura): pantalla completa y barras más bajas para que se vean las carátulas
    val compact = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 500
    val activity = androidx.compose.ui.platform.LocalContext.current as? cl.iptv.player.MainActivity
    DisposableEffect(compact) {
        val win = activity?.window
        val wc = win?.let { androidx.core.view.WindowCompat.getInsetsController(it, it.decorView) }
        if (compact && wc != null) {
            wc.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            wc.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
        onDispose { }
    }


    Column(
        Modifier
            .fillMaxSize()
            .themeBackground()
            .systemBarsPadding()
    ) {
        TopBar(vm, compact, onSources = { vm.showWelcome = true }, onSearch = { dialog = MenuDialog.SEARCH })
        if (vm.source != null && !vm.home && vm.series == null && vm.query.isBlank()) CategoryBar(vm)

        Box(Modifier.weight(1f).fillMaxWidth()) {
            TileGrid(vm, compact, onFocusItem = { focusedItem = it }, onAddList = { vm.showWelcome = true })
            vm.loading?.let { text ->
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = c.accent)
                    Spacer(Modifier.height(12.dp))
                    Text(text, color = c.ink, fontWeight = FontWeight.Bold)
                }
            }
        }

        FocusInfo(vm, focusedItem, compact)
        Dock(
            compact = compact,
            title = vm.title(),
            pageText = vm.pageInfo,
            onCategories = { dialog = if (vm.source != null) MenuDialog.CATEGORIES else MenuDialog.SOURCES },
            onSettings = { vm.showSettings = true },
        )
    }

    when (dialog) {
        MenuDialog.SOURCES -> SourcesDialog(vm, onDismiss = { dialog = MenuDialog.NONE })
        MenuDialog.CATEGORIES -> CategoriesDialog(vm) { dialog = MenuDialog.NONE }
        MenuDialog.SEARCH -> SearchDialog(vm) { dialog = MenuDialog.NONE }
        MenuDialog.NONE -> Unit
    }
    vm.details?.let { DetailsScreen(vm, it) }
}


/** Barra de categorías y, si la lista las trae, de subcategorías ("CHILE | DEPORTES"). */
@Composable
private fun CategoryBar(vm: AppViewModel) {
    val cats = remember(vm.items, vm.section, vm.hiddenCats, vm.catOrder) { vm.categories() }
    val nRecent = remember(vm.items, vm.section, vm.recent) { vm.recentInSection() }
    val subs = remember(vm.items, vm.section, vm.category) { vm.subcategories() }
    val nFav = vm.favoritesInSection()
    val nResume = remember(vm.items, vm.section, vm.resume) { vm.resumeInSection() }
    val total = remember(vm.items, vm.section) { vm.items.count { it.kind == vm.section } }
    var askPin by remember { mutableStateOf(false) }
    if (askPin) PinDialog(onDismiss = { askPin = false }) { pin -> if (vm.unlockAdult(pin)) { askPin = false; true } else false }
    Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(3.dp)) {
            if (vm.section == Kind.LIVE) item { Pill("📅 Guía") { vm.showGuide = true } }
            item { Pill("Todos  $total", active = vm.category == CAT_ALL) { vm.selectCategory(CAT_ALL) } }
            if (nResume > 0 || vm.section != Kind.LIVE) item { Pill("▶ Seguir viendo  $nResume", active = vm.category == CAT_RESUME) { vm.selectCategory(CAT_RESUME) } }
            if (nFav > 0) item { Pill("★ Favoritos  $nFav", active = vm.category == CAT_FAV) { vm.selectCategory(CAT_FAV) } }
            if (nRecent > 0) item { Pill("🕘 Recientes  $nRecent", active = vm.category == CAT_RECENT) { vm.selectCategory(CAT_RECENT) } }
            items(cats, key = { it.first }) { (c, n) -> Pill("$c  $n", active = vm.category == c) { vm.selectCategory(c) } }
            // Control parental: categorías para adultos ocultas con PIN
            if (vm.parentalOn && !vm.adultUnlocked && !vm.profile.kid && vm.hiddenAdult > 0) item { Pill("🔒 Adultos") { askPin = true } }
            if (vm.parentalOn && vm.adultUnlocked) item { Pill("🔓 Ocultar adultos") { vm.lockAdult() } }
        }
        if (subs.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(3.dp)) {
            item { Pill("Todas", active = vm.subcategory.isEmpty()) { vm.selectCategory(vm.category) } }
            items(subs, key = { "s_" + it.first }) { (sname, n) -> Pill("$sname  $n", active = vm.subcategory == sname) { vm.selectCategory(vm.category, sname) } }
        }
    }
}

@Composable
private fun TopBar(vm: AppViewModel, compact: Boolean, onSources: () -> Unit, onSearch: () -> Unit) {
    val c = LocalIptv.current
    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = if (compact) 6.dp else 12.dp, bottom = if (compact) 2.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Pill(
            vm.source?.name ?: "Sin lista",
            modifier = Modifier.widthIn(max = 220.dp),
            leading = { Box(Modifier.size(9.dp).clip(RoundedCornerShape(50)).background(c.accent)) },
            onClick = onSources,
        )
        val series = vm.series
        if (series != null) {
            Pill("‹ Volver") { vm.back() }
            Pill("ⓘ Ficha") { vm.openDetails(series) }
            Text(series.name, color = c.ink, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        } else {
            LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(3.dp)) {
                if (vm.source != null) {
                    item { Pill("Inicio", active = vm.home) { vm.goHome() } }
                    items(Kind.entries) { k -> Pill("${k.label}  ${vm.countOf(k)}", active = !vm.home && k == vm.section) { vm.selectSection(k) } }
                }
            }
        }
        Pill("", leading = { Icon(Icons.Filled.Search, "Buscar", tint = c.ink, modifier = Modifier.size(20.dp)) }, onClick = onSearch)
    }
}

@Composable
private fun TileGrid(vm: AppViewModel, compact: Boolean, onFocusItem: (Item?) -> Unit, onAddList: () -> Unit) {
    val c = LocalIptv.current
    if (vm.source == null) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Agrega tu primera lista M3U o cuenta Xtream Codes para empezar.", color = c.ink, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(14.dp))
            ActionButton("Agregar lista", onClick = onAddList)
        }
        return
    }

    if (vm.home && vm.query.isBlank()) { HomeGrid(vm, compact, onFocusItem); return }

    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = if (compact) 30.dp else 40.dp, vertical = if (compact) 6.dp else 12.dp)) {
        // Películas y series: carátula vertical 2:3 con el nombre debajo. TV en vivo: mosaico apaisado con el logo.
        val poster = vm.series == null && vm.section != Kind.LIVE
        val gap: Dp = if (maxWidth < 600.dp || compact) 10.dp else 16.dp
        val zoom = when (vm.settings.tileSize) { "large" -> 1.25f; "small" -> 0.8f; else -> 1f }
        val nameH = 34.dp                                   // franja del nombre bajo la carátula (2 líneas)
        // 1) filas según la altura disponible, 2) columnas según el ancho que deja la proporción del mosaico
        val minH = (if (poster) 200.dp else 96.dp) * zoom
        val rows = ((maxHeight + gap) / (minH + gap)).toInt().coerceIn(1, 5)
        val tileH = (maxHeight - gap * (rows - 1)) / rows
        val idealW = if (poster) (tileH - nameH) / 1.5f else tileH * 1.65f
        val cols = ((maxWidth + gap) / (idealW + gap)).let { Math.round(it) }.coerceIn(2, 10)
        val perPage = cols * rows
        val view = vm.view
        val pages = maxOf(1, (view.size + perPage - 1) / perPage)
        SideEffect {
            if (vm.page >= pages) vm.page = pages - 1
            vm.pageInfo = if (pages > 1) "${vm.page + 1} / $pages" else ""
        }

        val requesters = remember(perPage) { List(perPage) { FocusRequester() } }
        var pendingFocus by remember { mutableStateOf<Int?>(0) }

        // Volver del reproductor o de una serie: enfocar su mosaico. Si cambia la lista: el primero.
        var lastView by remember { mutableStateOf<List<Item>?>(null) }
        LaunchedEffect(vm.returnFocusId, view, perPage) {
            val id = vm.returnFocusId
            if (id != null) {
                val idx = view.indexOfFirst { it.id == id }
                if (idx >= 0) { vm.page = idx / perPage; pendingFocus = idx % perPage }
                vm.returnFocusId = null
            } else if (lastView != null && lastView !== view) pendingFocus = 0
            lastView = view
        }
        LaunchedEffect(pendingFocus, vm.page, view, perPage) {
            val p = pendingFocus ?: return@LaunchedEffect
            delay(60)
            runCatching { requesters[p.coerceAtMost(requesters.lastIndex)].requestFocus() }
            pendingFocus = null
        }

        fun go(delta: Int, focusPos: Int): Boolean {
            val np = vm.page + delta
            if (np !in 0 until pages) return false
            vm.page = np
            val onNext = (view.size - np * perPage).coerceAtMost(perPage)
            pendingFocus = focusPos.coerceIn(0, maxOf(0, onNext - 1))
            return true
        }

        if (view.isEmpty() && vm.loading == null) {
            Text(
                if (vm.query.isBlank() && vm.category == CAT_RESUME) "Aquí aparecerá lo que dejes a medias.\nSe guarda al ver más de 30 segundos de una película o episodio."
                else if (vm.query.isBlank() && vm.title() == "Favoritos") "No tienes favoritos aquí todavía.\nMantén pulsado un canal (o botón MENÚ del control) para marcarlo."
                else "No hay contenido para mostrar.",
                color = c.ink, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center),
            )
            return@BoxWithConstraints
        }

        var dragX by remember { mutableStateOf(0f) }
        // Animación de cambio de página: la nueva página entra deslizándose desde el lado
        val slide = remember { Animatable(0f) }
        var prevPage by remember { mutableStateOf(vm.page) }
        LaunchedEffect(vm.page) {
            val dir = (vm.page - prevPage).coerceIn(-1, 1)
            prevPage = vm.page
            if (dir != 0 && !cl.iptv.player.data.Perf.lite && vm.settings.animations) { slide.snapTo(dir * 60f); slide.animateTo(0f, tween(240)) }
        }
        val page = vm.page
        val start = page * perPage
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { translationX = slide.value.dp.toPx(); alpha = 1f - kotlin.math.abs(slide.value) / 120f }
                .pointerInput(pages) {
                    detectHorizontalDragGestures(
                        onDragEnd = { if (dragX < -80) go(1, 0) else if (dragX > 80) go(-1, 0); dragX = 0f },
                        onDragCancel = { dragX = 0f },
                    ) { _, d -> dragX += d }
                },
        ) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
                for (r in 0 until rows) {
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                        for (col in 0 until cols) {
                            val pos = r * cols + col
                            val item = view.getOrNull(start + pos)
                            val mod = Modifier.weight(1f).fillMaxHeight()
                            if (item == null) EmptyTile(mod)
                            else {
                                val onPage = (view.size - start).coerceAtMost(perPage)
                                Tile(
                                    item = item,
                                    fav = item.id in vm.favorites,
                                    showNumber = vm.settings.showNumbers,
                                    animate = vm.settings.animations,
                                    poster = item.kind != Kind.LIVE,
                                    progress = vm.resume[item.id]?.fraction ?: 0f,
                                    modifier = mod
                                        .focusRequester(requesters[pos])
                                        .onPreviewKeyEvent { ev ->
                                            if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                            when (ev.key) {
                                                Key.DirectionRight -> if (col == cols - 1 || pos == onPage - 1) go(1, r * cols) else false
                                                Key.DirectionLeft -> if (col == 0) go(-1, r * cols + cols - 1) else false
                                                Key.PageDown, Key.ChannelDown -> go(1, 0)
                                                Key.PageUp, Key.ChannelUp -> go(-1, 0)
                                                Key.Menu, Key.Bookmark -> { vm.toggleFavorite(item); true }
                                                else -> false
                                            }
                                        },
                                    onFocused = { onFocusItem(item) },
                                    onClick = { vm.choose(item) },
                                    onLongClick = { vm.toggleFavorite(item) },
                                )
                            }
                        }
                    }
                }
            }
        }

        val arrowX = if (compact) 27.dp else 34.dp
        if (vm.page > 0) PagerArrow(left = true, Modifier.align(Alignment.CenterStart).offset(x = -arrowX)) { go(-1, 0) }
        if (vm.page < pages - 1) PagerArrow(left = false, Modifier.align(Alignment.CenterEnd).offset(x = arrowX)) { go(1, 0) }
    }
}

/** Inicio: tres mosaicos grandes (TV en vivo, Películas, Series). */
@Composable
private fun HomeGrid(vm: AppViewModel, compact: Boolean, onFocusItem: (Item?) -> Unit) {
    val requesters = remember { Kind.entries.associateWith { FocusRequester() } }
    LaunchedEffect(Unit) {
        delay(60)
        val k = vm.returnHomeKind ?: Kind.LIVE
        vm.returnHomeKind = null
        runCatching { requesters.getValue(k).requestFocus() }
    }
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = if (compact) 8.dp else 12.dp), contentAlignment = Alignment.Center) {
        val narrow = maxWidth < 560.dp
        val gap = if (narrow) 12.dp else 18.dp
        val cont = remember(vm.items, vm.resume) { vm.continueWatching() }
        SideEffect { vm.pageInfo = "" }          // el inicio no tiene páginas (antes quedaba "1 / 384")
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        if (narrow) {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(gap)) {
                Kind.entries.forEach { k ->
                    HomeTile(k, vm.countOf(k), vm.groupsOf(k), Modifier.weight(1f).fillMaxWidth().focusRequester(requesters.getValue(k)), horizontal = true, onFocused = { onFocusItem(null) }) { vm.selectSection(k) }
                }
            }
        } else {
            Row(Modifier.widthIn(max = 1180.dp).fillMaxWidth().heightIn(max = 440.dp).fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                Kind.entries.forEach { k ->
                    HomeTile(k, vm.countOf(k), vm.groupsOf(k), Modifier.weight(1f).fillMaxHeight().focusRequester(requesters.getValue(k)), onFocused = { onFocusItem(null) }) { vm.selectSection(k) }
                }
            }
        }
        }
        if (cont.isNotEmpty()) ContinueRow(vm, cont, if (compact) 118.dp else 168.dp, onFocusItem)
        }
    }
}

/** Fila "Seguir viendo" del inicio: carátulas con su barra de avance. */
@Composable
private fun ContinueRow(vm: AppViewModel, cont: List<Triple<Item, cl.iptv.player.data.Resume, String?>>, height: Dp, onFocusItem: (Item?) -> Unit) {
    val c = LocalIptv.current
    Column(Modifier.widthIn(max = 1180.dp).fillMaxWidth()) {
        Text("▶ Seguir viendo", color = c.accentDeep, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)) {
            items(cont, key = { it.first.id }) { (item, r, ep) ->
                Tile(
                    item = item, fav = item.id in vm.favorites, poster = true, showNumber = false, animate = vm.settings.animations,
                    progress = r.fraction.coerceAtLeast(0.02f),
                    modifier = Modifier.height(height).width((height - 34.dp) / 1.5f),
                    onFocused = { onFocusItem(item) },
                    onClick = { vm.openContinue(item, ep) },
                    onLongClick = { vm.toggleFavorite(item) },
                )
            }
        }
    }
}

@Composable
private fun SectionIcon(kind: Kind, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val s = size.minDimension / 48f
        val st = Stroke(width = 2.4f * s, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
        fun rr(x: Float, y: Float, w: Float, h: Float) = drawRoundRect(color, Offset(x * s, y * s), androidx.compose.ui.geometry.Size(w * s, h * s), androidx.compose.ui.geometry.CornerRadius(4.5f * s), style = st)
        fun line(vararg p: Float) { for (i in 0 until p.size - 2 step 2) drawLine(color, Offset(p[i] * s, p[i + 1] * s), Offset(p[i + 2] * s, p[i + 3] * s), st.width, cap = androidx.compose.ui.graphics.StrokeCap.Round) }
        fun play(x: Float, y: Float) = drawPath(Path().apply { moveTo(x * s, y * s); lineTo((x + 8) * s, (y + 4.5f) * s); lineTo(x * s, (y + 9) * s); close() }, color)
        when (kind) {
            Kind.LIVE -> { rr(5f, 9f, 38f, 26f); line(17f, 41f, 31f, 41f); line(24f, 35f, 24f, 41f); line(19f, 4f, 24f, 9f, 29f, 4f); drawCircle(color, 1.8f * s, Offset(36 * s, 16 * s)) }
            Kind.MOVIE -> { rr(6f, 16f, 36f, 25f); line(6f, 16f, 10f, 7f, 44f, 7f, 41f, 16f); line(14f, 7f, 19f, 16f); line(24f, 7f, 29f, 16f); line(34f, 7f, 39f, 16f); play(21f, 24f) }
            Kind.SERIES -> { rr(4f, 15f, 30f, 22f); line(10f, 10f, 38f, 10f, 42f, 14f, 42f, 32f); line(16f, 5f, 42f, 5f, 46f, 9f, 46f, 25f); play(16f, 21.5f) }
        }
    }
}

@Composable
private fun HomeTile(
    kind: Kind, count: Int, groups: Int, modifier: Modifier, horizontal: Boolean = false,
    onFocused: () -> Unit, onClick: () -> Unit,
) {
    val c = LocalIptv.current
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) onFocused() }
    val scale by animateFloatAsState(if (focused && !cl.iptv.player.data.Perf.lite) 1.04f else 1f, label = "home")
    val shape = themeShape(22f)
    val countText = if (count == 0) "Sin contenido en esta lista" else "$count ${if (count == 1) kind.one else kind.many}" + if (groups > 1) " · $groups categorías" else ""
    Box(
        modifier
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .softShadow(if (focused) 20.dp else 3.dp, shape, ambientColor = c.focus, spotColor = if (focused) c.focus else Color.Black)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(c.tileA, c.tileB)))
            .brushedLines(c.tileLines, 7f)
            .border(if (focused) 4.dp else 1.dp, if (focused) c.focus else c.tileEdge, shape)
            .clickable(interactionSource = src, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (horizontal) {
            Row(Modifier.fillMaxSize().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                SectionIcon(kind, c.accentDeep, Modifier.size(60.dp))
                Spacer(Modifier.width(18.dp))
                Column {
                    Text(kind.label, color = c.ink, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
                    Text(countText, color = c.inkSoft, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        } else BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // El ícono y los textos se ajustan a la altura del mosaico (teléfono en horizontal, TV, tablet)
            val k = (maxHeight / 380.dp).coerceIn(0.5f, 1f)
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy((6 * k).dp), modifier = Modifier.padding(horizontal = 12.dp)) {
                SectionIcon(kind, c.accentDeep, Modifier.size((120 * k).dp))
                Spacer(Modifier.height((8 * k).dp))
                Text(if (c.lowercase) tr(kind.label).lowercase() else kind.label, color = c.ink, fontWeight = FontWeight.ExtraBold, fontSize = (32 * k).coerceAtLeast(20f).sp, maxLines = 1)
                Text(countText, color = c.inkSoft, fontWeight = FontWeight.Bold, fontSize = (15 * k).coerceAtLeast(12f).sp,
                    maxLines = 2, textAlign = TextAlign.Center)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Tile(
    item: Item, fav: Boolean, poster: Boolean, modifier: Modifier,
    showNumber: Boolean = true, animate: Boolean = true, progress: Float = 0f,
    onFocused: () -> Unit, onClick: () -> Unit, onLongClick: () -> Unit,
) {
    val c = LocalIptv.current
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) onFocused() }
    val scale by animateFloatAsState(if (focused && animate && !cl.iptv.player.data.Perf.lite) 1.05f else 1f, label = "tile")
    val shape = themeShape(18f)
    var imgFailed by remember(item.logo) { mutableStateOf(item.logo.isBlank()) }
    val usePoster = poster && !imgFailed

    Box(
        modifier
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .softShadow(if (focused) 18.dp else 2.dp, shape, ambientColor = c.focus, spotColor = if (focused) c.focus else Color.Black)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(c.tileA, c.tileB)))
            .brushedLines(c.tileLines, 7f)
            .border(if (focused) 4.dp else 1.dp, if (focused) c.focus else c.tileEdge, shape)
            .combinedClickable(interactionSource = src, indication = null, onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        if (poster) {
            // Carátula completa arriba y el nombre debajo (hasta 2 líneas), sin taparla
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (!imgFailed) AsyncImage(
                        model = item.logo, contentDescription = null, contentScale = ContentScale.Crop,
                        onError = { imgFailed = true }, modifier = Modifier.fillMaxSize(),
                    ) else Text(initials(item.name), color = c.inkSoft, fontWeight = FontWeight.ExtraBold, fontSize = 30.sp)
                    // Seguir viendo: cuánto se vio
                    if (progress > 0f) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(5.dp).background(Color(0x99000000))) {
                        Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(c.accent))
                    }
                }
                Box(Modifier.fillMaxWidth().height(34.dp).background(c.tileA).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
                    Text(
                        item.name, color = c.ink, fontWeight = FontWeight.Bold, fontSize = 11.sp, lineHeight = 13.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        } else {
            if (!imgFailed) {
                AsyncImage(
                    model = item.logo, contentDescription = null, contentScale = ContentScale.Fit,
                    onError = { imgFailed = true },
                    modifier = Modifier.fillMaxWidth(0.72f).fillMaxHeight(0.55f).offset(y = (-8).dp),
                )
            } else {
                Text(initials(item.name), color = c.inkSoft, fontWeight = FontWeight.ExtraBold, fontSize = 30.sp, modifier = Modifier.offset(y = (-8).dp))
            }
            Text(
                item.name, color = c.ink, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        item.num?.takeIf { showNumber }?.let { Text("$it", color = c.inkSoft, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) }
        if (fav) Icon(Icons.Filled.Star, "Favorito", tint = Color(0xFFF2B01E), modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp))
    }
}

@Composable
private fun EmptyTile(modifier: Modifier) {
    val c = LocalIptv.current
    val shape = themeShape(18f)
    Box(modifier.clip(shape).background(Brush.verticalGradient(listOf(c.emptyA, c.emptyB))).border(1.dp, c.tileEdge.copy(alpha = 0.6f), shape))
}

@Composable
private fun PagerArrow(left: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalIptv.current
    Canvas(
        modifier.size(width = 26.dp, height = 46.dp)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
    ) {
        val p = Path().apply {
            if (left) { moveTo(size.width * 0.85f, size.height * 0.08f); lineTo(size.width * 0.12f, size.height / 2); lineTo(size.width * 0.85f, size.height * 0.92f) }
            else { moveTo(size.width * 0.15f, size.height * 0.08f); lineTo(size.width * 0.88f, size.height / 2); lineTo(size.width * 0.15f, size.height * 0.92f) }
            close()
        }
        drawPath(p, Color(0xE6C2EAF8))
        drawPath(p, c.accent, style = Stroke(width = 4f))
    }
}

@Composable
private fun FocusInfo(vm: AppViewModel, item: Item?, compact: Boolean) {
    val c = LocalIptv.current
    val extra = when {
        item == null -> ""
        !vm.settings.showEpgInfo -> ""
        item.kind == Kind.LIVE -> {
            val (now, next) = vm.nowNext(item)
            val sh = vm.epgShift
            if (now != null) "Ahora: ${now.title} (${fmtTime(now.start + sh)}–${fmtTime(now.stop + sh)})" + (next?.let { " · Luego: ${it.title}" } ?: "") else ""
        }
        else -> item.group
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 40.dp).height(if (compact) 20.dp else 24.dp), verticalAlignment = Alignment.CenterVertically) {
        if (item != null) {
            Text(item.name, color = c.ink, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, maxLines = 1)
            Spacer(Modifier.width(10.dp))
            Text(extra, color = c.inkSoft, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Dock(compact: Boolean = false, title: String, pageText: String, onCategories: () -> Unit, onSettings: () -> Unit) {
    val c = LocalIptv.current
    val clock by produceState(initialValue = clockText()) {
        while (true) { value = clockText(); delay(15_000) }
    }
    Box(Modifier.fillMaxWidth().height(if (compact) 62.dp else 116.dp)) {
        Canvas(Modifier.matchParentSize()) {
            val w = size.width; val top = (if (compact) 16.dp else 30.dp).toPx(); val bump = (if (compact) 2.dp else 4.dp).toPx()
            val mid = w / 2; val half = minOf(w * 0.2f, 260.dp.toPx())
            val path = Path().apply {
                moveTo(0f, top); lineTo(mid - half - 60f, top)
                cubicTo(mid - half, top, mid - half + 10f, bump, mid - half + 60f, bump)
                lineTo(mid + half - 60f, bump)
                cubicTo(mid + half - 10f, bump, mid + half, top, mid + half + 60f, top)
                lineTo(w, top)
            }
            val fill = Path().apply { addPath(path); lineTo(w, size.height); lineTo(0f, size.height); close() }
            drawPath(fill, c.dock)
            drawPath(path, c.accent, style = Stroke(width = 2.5.dp.toPx()))
        }
        val btn = if (compact) 46 else 76
        val btnTop = if (compact) 10.dp else 22.dp
        RoundButton(Icons.Filled.List, "Categorías", Modifier.align(Alignment.CenterStart).padding(start = 28.dp, top = btnTop), size = btn, onClick = onCategories)
        if (compact) {
            Row(Modifier.align(Alignment.Center).padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = c.accentDeep, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 300.dp))
                Text("  ·  $clock", color = c.inkSoft, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, maxLines = 1)
            }
        } else Column(Modifier.align(Alignment.Center).padding(top = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, color = c.accentDeep, fontWeight = FontWeight.Bold, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 420.dp))
            Text(clock, color = c.inkSoft, fontWeight = FontWeight.ExtraBold, fontSize = 30.sp)
        }
        if (pageText.isNotEmpty()) Text(pageText, color = c.inkSoft, fontWeight = FontWeight.Bold, fontSize = 13.sp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = if (compact) 90.dp else 120.dp, bottom = if (compact) 8.dp else 14.dp))
        RoundButton(Icons.Filled.Settings, "Ajustes", Modifier.align(Alignment.CenterEnd).padding(end = 28.dp, top = btnTop), size = btn, onClick = onSettings)
    }
}

private fun clockText(): String {
    val d = Date()
    val day = SimpleDateFormat("EEE", I18n.locale).format(d).replace(".", "").replaceFirstChar { it.uppercase() }
    return "$day ${SimpleDateFormat("dd/MM", ES).format(d)} · ${hhmm.format(d)}"
}

fun initials(name: String): String =
    name.replace(Regex("^[A-Z]{2,3}\\s*[|:]\\s*"), "").split(Regex("\\s+")).filter { it.isNotBlank() }
        .take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "?" }
