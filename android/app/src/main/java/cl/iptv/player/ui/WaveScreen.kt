package cl.iptv.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cl.iptv.player.data.Kind
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

/** Una fila de la lista vertical del tema Ondas. */
private data class WEntry(val key: String, val title: String, val sub: String = "", val icon: TIcon? = null, val logo: String = "", val open: () -> Unit)

private enum class WCol(val label: String, val icon: TIcon, val kind: Kind? = null) {
    SETTINGS("Ajustes", TIcon.SETTINGS), LIVE("TV en vivo", TIcon.TV, Kind.LIVE), MOVIE("Películas", TIcon.MOVIE, Kind.MOVIE),
    SERIES("Series", TIcon.SERIES, Kind.SERIES), GUIDE("Guía", TIcon.GUIDE),
}

/**
 * Menú del tema Ondas: fila horizontal de íconos y, bajo el elegido, una lista vertical
 * (categorías → canales / películas / series → episodios). Se maneja con ◄ ► ▲ ▼ y OK.
 */
@Composable
fun WaveScreen(vm: AppViewModel) {
    val nav = vm.shell
    val c = cl.iptv.player.ui.theme.LocalIptv.current
    val focus = remember { FocusRequester() }
    var dialog by remember { mutableStateOf<String?>(null) }
    val cols = WCol.entries
    val col = cols[nav.col.coerceIn(0, cols.lastIndex)]
    val lite = cl.iptv.player.data.Perf.lite

    LaunchedEffect(dialog, vm.details, vm.showGuide, vm.showSettings, vm.showWelcome) { if (dialog == null && vm.details == null) { delay(60); runCatching { focus.requestFocus() } } }
    // Al pasar por una columna de contenido, la sección del menú es esa (categorías, búsqueda, favoritos)
    LaunchedEffect(nav.col) { col.kind?.let { k -> if (vm.section != k || nav.level == 0) { if (vm.series == null) vm.selectSection(k) } } }

    // Nivel 0: lo que hay en cada columna
    fun rootEntries(col: WCol): List<WEntry> = when (col) {
        WCol.SETTINGS -> listOf(
            WEntry("set", "Ajustes", "Reproducción, subtítulos, apariencia y más", TIcon.SETTINGS) { vm.showSettings = true },
            WEntry("lists", "Mis listas", vm.source?.name.orEmpty(), TIcon.LISTS) { vm.showWelcome = true },
            WEntry("prof", "Perfil", vm.profile.name, TIcon.PROFILE) { dialog = "profiles" },
        )
        WCol.GUIDE -> listOf(WEntry("guide", "Guía de programación", "Todos los canales en una línea de tiempo", TIcon.GUIDE) { vm.showGuide = true })
        else -> {
            val k = col.kind!!
            val items = vm.items.filter { it.kind == k }
            buildList {
                add(WEntry("search", "Buscar", "En ${tr(k.label)}", TIcon.SEARCH) { dialog = "search" })
                val res = vm.resumeInSection()
                if (k != Kind.LIVE && res > 0) add(WEntry(CAT_RESUME, "Seguir viendo", "$res", TIcon.PLAY) { vm.selectCategory(CAT_RESUME); nav.enter("Seguir viendo") })
                val fav = items.count { it.id in vm.favorites }
                if (fav > 0) add(WEntry(CAT_FAV, "Favoritos", "$fav", TIcon.STAR) { vm.selectCategory(CAT_FAV); nav.enter("Favoritos") })
                val ids = items.mapTo(HashSet()) { it.id }
                val rec = vm.recent.count { it in ids }
                if (rec > 0) add(WEntry(CAT_RECENT, "Vistos recientemente", "$rec", TIcon.CLOCK) { vm.selectCategory(CAT_RECENT); nav.enter("Vistos recientemente") })
                add(WEntry(CAT_ALL, "Todos", "${items.size}", TIcon.FOLDER) { vm.selectCategory(CAT_ALL); nav.enter("Todos") })
                vm.categories().forEach { (cat, n) -> add(WEntry("c_$cat", cat, "$n", TIcon.FOLDER) { vm.selectCategory(cat); nav.enter(cat) }) }
            }
        }
    }

    val entries: List<WEntry> = if (nav.level == 0) remember(nav.col, vm.section, vm.items, vm.favorites, vm.recent, vm.resume, vm.hiddenCats, vm.catOrder, vm.profile, vm.source) { rootEntries(col) }
    else remember(vm.view, nav.col) {
        vm.view.map { it0 ->
            WEntry(it0.id, it0.name, "", null, it0.logo) {
                if (it0.kind == Kind.LIVE) vm.activate(it0, vm.view) else vm.choose(it0)
            }
        }
    }
    val sel = (if (nav.level == 0) nav.idx0[nav.col] ?: entries.indexOfFirst { it.key == CAT_ALL }.coerceAtLeast(0) else nav.idx1).coerceIn(0, maxOf(0, entries.lastIndex))
    // Al abrir una serie, al volver de ella o del reproductor: el foco en el elemento que corresponde
    LaunchedEffect(vm.series?.id, vm.view, vm.returnFocusId) {
        if (nav.level != 1) return@LaunchedEffect
        val id = vm.returnFocusId
        if (id != null) { val i = vm.view.indexOfFirst { it.id == id }; if (i >= 0) nav.idx1 = i; vm.returnFocusId = null }
        else if (vm.series != null) nav.idx1 = 0
    }
    fun setSel(i: Int) { val v = i.coerceIn(0, maxOf(0, entries.lastIndex)); if (nav.level == 0) nav.idx0[nav.col] = v else nav.idx1 = v }
    fun goBack(): Boolean = when {
        nav.level == 1 && vm.series != null -> { vm.back(); true }
        nav.level == 1 -> { nav.level = 0; true }
        else -> false
    }
    BackHandler(enabled = nav.level == 1 && dialog == null && vm.details == null) { goBack() }

    BoxWithConstraints(
        Modifier.fillMaxSize()
            .focusRequester(focus).focusable()
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (ev.key) {
                    Key.DirectionLeft -> { if (nav.level == 1) goBack() else if (nav.col > 0) nav.col--; true }
                    Key.DirectionRight -> { if (nav.level == 0 && nav.col < cols.lastIndex) nav.col++; true }
                    Key.DirectionUp -> { setSel(sel - 1); true }
                    Key.DirectionDown -> { setSel(sel + 1); true }
                    Key.PageUp, Key.ChannelUp -> { setSel(sel - 10); true }
                    Key.PageDown, Key.ChannelDown -> { setSel(sel + 10); true }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { entries.getOrNull(sel)?.open?.invoke(); true }
                    Key.Menu, Key.Bookmark -> { if (nav.level == 1) vm.view.getOrNull(sel)?.let { vm.toggleFavorite(it) }; true }
                    else -> false
                }
            },
    ) {
        val w = maxWidth; val h = maxHeight
        val compact = h < 480.dp
        WaveBackdrop(lite)

        // Reloj y fecha arriba a la derecha, en una barra translúcida
        var now by remember { mutableStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) { while (true) { delay(15_000); now = System.currentTimeMillis() } }
        Row(
            Modifier.align(Alignment.TopEnd).padding(top = 28.dp).width(w * 0.32f).clip(RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp))
                .background(Color.White.copy(alpha = 0.12f)).border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp))
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(vm.profile.name, color = Color.White, fontSize = 15.sp, maxLines = 1, modifier = Modifier.weight(1f))
            Text(java.text.SimpleDateFormat("d/M  HH:mm", java.util.Locale.getDefault()).format(java.util.Date(now)), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium)
        }

        // Fila de íconos: el elegido queda fijo a la izquierda y los demás se deslizan
        val anchorX = w * 0.22f
        val spacing = if (compact) 120.dp else 165.dp
        val rowY = h * (if (compact) 0.20f else 0.24f)
        val slide by animateFloatAsState(nav.col.toFloat(), tween(if (lite) 0 else 260), label = "cols")
        cols.forEachIndexed { i, cc ->
            val selected = i == nav.col
            if (nav.level == 1 && !selected) return@forEachIndexed
            val size = if (selected) (if (compact) 64.dp else 84.dp) else (if (compact) 46.dp else 60.dp)
            val x = anchorX + spacing * (i - slide) - size / 2 - (if (nav.level == 1) w * 0.12f else 0.dp)
            Column(
                Modifier.offset(x = x, y = rowY - size / 2).width(size + 40.dp).offset(x = (-20).dp)
                    .clickable(remember { MutableInteractionSource() }, null) { if (nav.level == 1) nav.level = 0; nav.col = i },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                TIconView(cc.icon, Color.White, Modifier.size(size).alpha(if (selected) 1f else 0.55f), stroke = if (selected) 1.6f else 1.4f)
                if (selected) Text(cc.label, color = Color.White, fontSize = if (compact) 15.sp else 19.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
        if (nav.level == 1) Text(
            nav.title + (vm.series?.let { "  ›  ${it.name}" } ?: ""), color = Color.White.copy(alpha = 0.75f), fontSize = 15.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.offset(x = anchorX - w * 0.12f + 60.dp, y = rowY - 12.dp).widthIn(max = w * 0.5f),
        )

        // Lista vertical: el elegido grande a media altura; los anteriores suben por encima de la fila de íconos
        val listX = if (nav.level == 1) anchorX - w * 0.04f else anchorX - (if (compact) 26.dp else 34.dp)
        val focusY = h * (if (compact) 0.46f else 0.50f)
        val step = if (compact) 52.dp else 66.dp
        if (entries.isEmpty()) Text(if (vm.loading != null) "Cargando…" else "Sin contenido", color = Color.White.copy(alpha = 0.7f), fontSize = 18.sp,
            modifier = Modifier.offset(x = listX, y = focusY))
        val from = (sel - 2).coerceAtLeast(0)
        val to = minOf(entries.lastIndex, sel + (if (compact) 4 else 6))
        for (i in from..to) {
            val e = entries[i]
            val d = i - sel
            val y = when {
                d == 0 -> focusY
                d > 0 -> focusY + step * 0.4f + step * d
                else -> rowY - (if (compact) 60.dp else 80.dp) - step * (-d - 1)
            }
            WaveRow(
                e, selected = d == 0, compact = compact, live = col.kind == Kind.LIVE && nav.level == 1, vm = vm,
                modifier = Modifier.offset(x = listX, y = y - (if (d == 0) step * 0.5f else step * 0.35f)).widthIn(max = w * 0.72f)
                    .clickable(remember(e.key) { MutableInteractionSource() }, null) { if (d == 0) e.open() else setSel(i) },
            )
        }

        // Deslizar arriba / abajo en pantallas táctiles
        Box(Modifier.matchParentSize().pointerInput(entries.size, nav.level) {
            var acc = 0f
            detectVerticalDragGestures(onDragEnd = { acc = 0f }) { ch, dy ->
                acc += dy
                val px = step.toPx()
                while (acc > px) { setSel(sel - 1); acc -= px }
                while (acc < -px) { setSel(sel + 1); acc += px }
                ch.consume()
            }
        })
    }

    when (dialog) {
        "search" -> SearchDialog(vm) { dialog = null; if (vm.query.isNotBlank()) nav.enter("Búsqueda: ${vm.query}") }
        "profiles" -> ProfilesDialog(vm) { dialog = null }
    }
    vm.details?.let { DetailsScreen(vm, it) }
}

@Composable
private fun WaveRow(e: WEntry, selected: Boolean, compact: Boolean, live: Boolean, vm: AppViewModel, modifier: Modifier) {
    val box: Dp = if (selected) (if (compact) 56.dp else 76.dp) else (if (compact) 40.dp else 50.dp)
    val sub = if (selected) {
        if (live) vm.view.firstOrNull { it.id == e.key }?.let { vm.nowNext(it).first?.title }.orEmpty() else e.sub
    } else ""
    Row(modifier.alpha(if (selected) 1f else 0.6f), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = box * 1.35f, height = box), contentAlignment = Alignment.Center) {
            if (e.logo.isNotBlank()) AsyncImage(e.logo, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            else TIconView(e.icon ?: TIcon.FOLDER, Color.White, Modifier.size(box * 0.8f), stroke = 1.5f)
        }
        Spacer(Modifier.width(if (selected) 18.dp else 12.dp))
        Column {
            Text(e.title, color = Color.White, fontSize = if (selected) (if (compact) 19.sp else 24.sp) else (if (compact) 14.sp else 17.sp),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub.isNotBlank()) Text(sub, color = Color.White.copy(alpha = 0.8f), fontSize = if (compact) 12.sp else 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Fondo violeta con ondas que se mueven despacio (quietas en modo liviano). Cambia de tono según la hora. */
@Composable
private fun WaveBackdrop(lite: Boolean) {
    val hour = remember { java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) }
    // Mañana más clara, tarde violeta, noche más oscura
    val (top, bottom) = when (hour) {
        in 6..11 -> Color(0xFF6E62A8) to Color(0xFFB79CCB)
        in 12..18 -> Color(0xFF4A3F86) to Color(0xFF9A76B8)
        else -> Color(0xFF241F4A) to Color(0xFF5B4383)
    }
    val phase = if (lite) 0f else {
        val t = rememberInfiniteTransition(label = "waves")
        t.animateFloat(0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(26_000, easing = LinearEasing)), label = "phase").value
    }
    val stars = remember { List(90) { Triple(Math.random().toFloat(), Math.random().toFloat(), (Math.random() * 1.8 + 0.4).toFloat()) } }
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Brush.linearGradient(listOf(bottom, top), start = Offset(0f, size.height), end = Offset(size.width, 0f)))
        val n = if (lite) 4 else 10
        for (i in 0 until n) {
            val k = i / n.toFloat()
            val a = 0.06f * kotlin.math.sin(phase + k * 2.4f)
            val path = Path().apply {
                val y0 = size.height * (0.60f + k * 0.07f)
                moveTo(-20f, y0)
                cubicTo(size.width * 0.3f, y0 - size.height * (0.12f + a), size.width * 0.62f, y0 + size.height * (0.10f - a), size.width + 20f, y0 - size.height * (0.06f + k * 0.02f))
            }
            drawPath(path, Color.White.copy(alpha = 0.04f + 0.05f * (1 - k)), style = Stroke(width = if (i % 3 == 0) 30f else 2.5f))
        }
        if (!lite) stars.forEach { (x, y, r) ->
            val tw = 0.25f + 0.25f * kotlin.math.sin(phase * 3 + x * 20)
            drawCircle(Color.White.copy(alpha = tw), r, Offset(x * size.width, (0.40f + y * 0.5f) * size.height))
        }
    }
}
