package cl.iptv.player.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cl.iptv.player.data.Settings
import cl.iptv.player.ui.theme.LocalIptv
import kotlinx.coroutines.delay

/** Una fila de ajustes (mismo modelo que la versión web). */
private sealed class Row(val label: String, val desc: String = "") {
    class Toggle(label: String, desc: String = "", val get: (Settings) -> Boolean, val set: (Settings, Boolean) -> Settings) : Row(label, desc)
    class Choice(label: String, desc: String = "", val options: List<Pair<String, String>>, val get: (Settings) -> String, val set: (Settings, String) -> Settings) : Row(label, desc)
    class TextField(label: String, desc: String = "", val secret: Boolean = false, val get: (Settings) -> String, val set: (Settings, String) -> Settings) : Row(label, desc)
    class Action(label: String, desc: String = "", val value: () -> String = { "" }, val arrow: Boolean = false, val run: () -> Unit) : Row(label, desc)
    class Danger(label: String, desc: String = "", val run: () -> Unit) : Row(label, desc)
    class Info(label: String, val value: String) : Row(label)
}

private enum class SecIcon { QUICK, SOURCES, LOOK, PLAY, SUBS, STARTUP, LOCK, DATA, ABOUT }
private class Section(val id: String, val name: String, val icon: SecIcon, val rows: List<Row>)

private fun sections(vm: AppViewModel): List<Section> {
    val offsets = (-3..3).map { h -> h.toString() to if (h == 0) "Sin desfase" else "${if (h > 0) "+" else ""}$h h" }
    return listOf(
        Section("quick", "Rápido", SecIcon.QUICK, listOf(
            Row.Toggle("Números de canal", "Muestra el número en cada mosaico de TV en vivo", { it.showNumbers }, { s, v -> s.copy(showNumbers = v) }),
            Row.Toggle("Guía bajo los mosaicos", "Programa actual y siguiente del canal enfocado", { it.showEpgInfo }, { s, v -> s.copy(showEpgInfo = v) }),
            Row.Toggle("Siguiente episodio automático", "Al terminar un episodio empieza el siguiente", { it.autoNext }, { s, v -> s.copy(autoNext = v) }),
            Row.Toggle("Animaciones", "Zoom de mosaicos y transiciones", { it.animations }, { s, v -> s.copy(animations = v) }),
        )),
        Section("sources", "Listas y guía", SecIcon.SOURCES, listOf(
            Row.Action("Mis listas", value = { "${vm.sources.size} ${if (vm.sources.size == 1) "guardada" else "guardadas"}" }, arrow = true) { vm.showSettings = false; vm.showWelcome = true },
            Row.Action("Actualizar guía ahora", value = { vm.epgStatus.ifBlank { if (vm.source == null) "Abre una lista primero" else "Sin guía cargada" } }) { vm.refreshEpg() },
            Row.Choice("Actualización automática de la guía", "Además de al añadir una lista y al abrir la app",
                listOf("4" to "Cada 4 horas", "12" to "Cada 12 horas", "24" to "Una vez al día", "0" to "Sólo al abrir la app"),
                { it.epgEvery.toString() }, { s, v -> s.copy(epgEvery = v.toInt()) }),
            Row.Choice("Desfase de la guía", "Corrige guías que muestran la hora corrida", offsets, { it.epgOffset.toString() }, { s, v -> s.copy(epgOffset = v.toInt()) }),
        )),
        Section("look", "Apariencia", SecIcon.LOOK, listOf(
            Row.Choice("Idioma", "Idioma de la app", listOf("auto" to "Automático (idioma del sistema)", "es" to "Español", "en" to "English"), get = { it.lang }, set = { s, v -> s.copy(lang = v) }),
            Row.Choice("Tema", options = listOf("light" to "Claro", "dark" to "Oscuro", "system" to "Según el sistema"), get = { it.theme }, set = { s, v -> s.copy(theme = v) }),
            Row.Choice("Color de acento", options = listOf("celeste" to "Celeste", "verde" to "Verde", "naranja" to "Naranja", "rosa" to "Rosa", "violeta" to "Violeta"), get = { it.accent }, set = { s, v -> s.copy(accent = v) }),
            Row.Choice("Tamaño de los mosaicos", options = listOf("normal" to "Normal", "large" to "Grande", "small" to "Compacto"), get = { it.tileSize }, set = { s, v -> s.copy(tileSize = v) }),
            Row.Toggle("Animaciones", get = { it.animations }, set = { s, v -> s.copy(animations = v) }),
        )),
        Section("play", "Reproducción", SecIcon.PLAY, listOf(
            Row.Choice("Motor de reproducción", "VLC reproduce el audio AC-3 / MP2 y el video MPEG-2 de la TV de cable",
                listOf("auto" to "Automático: ExoPlayer, y VLC o MPV si hace falta", "exo" to "ExoPlayer", "vlc" to "VLC siempre", "mpv" to "MPV siempre"),
                { it.engine }, { s, v -> s.copy(engine = v) }),
            Row.Choice("Formato de pantalla", options = listOf("fit" to "Ajustar", "fill" to "Rellenar", "stretch" to "Estirar", "zoom" to "Zoom"),
                get = { it.aspect }, set = { s, v -> s.copy(aspect = v) }),
            Row.Choice("Formato de canales en vivo (Xtream)", options = listOf("ts" to "MPEG-TS (.ts) — recomendado", "m3u8" to "HLS (.m3u8)"), get = { it.liveFormat }, set = { s, v -> s.copy(liveFormat = v) }),
            Row.Toggle("Siguiente episodio automático", get = { it.autoNext }, set = { s, v -> s.copy(autoNext = v) }),
        )),
        Section("subs", "Subtítulos", SecIcon.SUBS, listOf(
            Row.Choice("Tamaño del texto", options = listOf("small" to "Pequeño", "normal" to "Normal", "large" to "Grande", "xlarge" to "Muy grande"), get = { it.subSize }, set = { s, v -> s.copy(subSize = v) }),
            Row.TextField("Idiomas preferidos", "Códigos separados por coma, ej: es,en", get = { it.subLangs }, set = { s, v -> s.copy(subLangs = v.ifBlank { "es,en" }) }),
            Row.Info("Fuentes", "OpenSubtitles · SubDL · Podnapisi"),
            Row.TextField("API key de SubDL", "Gratis en subdl.com: mucho catálogo en español latino", secret = true, get = { it.subdlKey }, set = { s, v -> s.copy(subdlKey = v) }),
            Row.TextField("API key de OpenSubtitles (opcional)", "Sin clave igual se busca en OpenSubtitles; con clave hay más resultados", secret = true, get = { it.osApiKey }, set = { s, v -> s.copy(osApiKey = v) }),
            Row.TextField("Usuario de OpenSubtitles", "Opcional: más descargas diarias", get = { it.osUser }, set = { s, v -> s.copy(osUser = v) }),
            Row.TextField("Contraseña de OpenSubtitles", secret = true, get = { it.osPass }, set = { s, v -> s.copy(osPass = v) }),
        )),
        Section("startup", "Inicio de la app", SecIcon.STARTUP, listOf(
            Row.Choice("Al abrir la app", options = listOf("home" to "Inicio de la última lista", "welcome" to "Pantalla de mis listas", "lastChannel" to "Último canal visto"), get = { it.startup }, set = { s, v -> s.copy(startup = v) }),
        )),
        Section("parental", "Control parental", SecIcon.LOCK, listOf(
            Row.TextField("PIN de control parental", "4 dígitos. Con PIN, las categorías para adultos (XXX, Adultos, +18…) quedan ocultas. Vacío = desactivado.",
                secret = true, get = { it.parentalPin }, set = { s, v -> s.copy(parentalPin = v.filter(Char::isDigit).take(8)) }),
            Row.Action("Contenido para adultos", "Se desbloquea con el PIN desde la barra de categorías, hasta cerrar la app",
                value = { if (!vm.parentalOn) "Visible (sin PIN)" else if (vm.adultUnlocked) "Desbloqueado" else "Oculto (${vm.hiddenAdult})" }) {
                if (vm.adultUnlocked) vm.lockAdult()
            },
        )),
        Section("data", "Datos", SecIcon.DATA, listOf(
            Row.Danger("Borrar \"Seguir viendo\"", "Olvida dónde quedaste en películas y series de esta lista") { vm.clearResume() },
            Row.Danger("Borrar guías guardadas", "Se vuelven a descargar al abrir cada lista") { vm.clearEpgCache() },
            Row.Danger("Borrar favoritos de esta lista") { vm.clearFavorites() },
            Row.Danger("Restablecer ajustes", "Vuelve a los valores de fábrica (no borra tus listas)") { vm.resetSettings() },
        )),
        Section("about", "Acerca de", SecIcon.ABOUT, listOf(
            Row.Info("Vibe IPTV", "Versión 1.0"),
            Row.Info("Desarrollado por", "Carmelita Softworks"),
            Row.Info("Licencia", "Código abierto · GPLv3"),
            Row.Info("Reproducción", "ExoPlayer · VLC · MPV"),
            Row.Info("Guía", "XMLTV (compatible con iptv-org/epg)"),
            Row.Info("Subtítulos", "OpenSubtitles · SubDL · Podnapisi"),
        )),
    )
}

private var lastSection = "quick"

/** Ajustes en pantalla completa: secciones a la izquierda, filas con su valor a la derecha. */
@Composable
fun SettingsScreen(vm: AppViewModel) {
    val c = LocalIptv.current
    val all = sections(vm)
    var current by remember { mutableStateOf(lastSection) }
    var query by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf<Row?>(null) }
    val navFocus = remember { all.associate { it.id to FocusRequester() } }

    LaunchedEffect(Unit) { delay(80); runCatching { navFocus.getValue(current).requestFocus() } }

    fun save(s: Settings) = vm.updateSettings(s)

    BoxWithConstraints(Modifier.fillMaxSize().background(c.bg).brushedLines(c.bgLines).systemBarsPadding()) {
        val narrow = maxWidth < 760.dp
        Column(Modifier.fillMaxSize().padding(horizontal = if (narrow) 16.dp else 36.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // Encabezado: volver, título con engranaje y buscador
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Pill("‹ Volver") { vm.showSettings = false }
                Icon(Icons.Filled.Settings, null, tint = c.accentDeep, modifier = Modifier.size(30.dp))
                Text("Ajustes", color = c.ink, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp, modifier = Modifier.weight(1f))
                if (!narrow) Field(query, { query = it }, "Buscar ajuste", modifier = Modifier.width(300.dp))
            }
            if (narrow) Field(query, { query = it }, "Buscar ajuste")

            val panelBox = Modifier.clip(RoundedCornerShape(22.dp)).background(c.sheet).border(1.dp, c.tileEdge, RoundedCornerShape(22.dp))
            val nav: @Composable () -> Unit = {
                all.forEach { sec ->
                    SectionButton(sec, active = sec.id == current && query.isBlank(), modifier = Modifier.focusRequester(navFocus.getValue(sec.id)), compact = narrow) {
                        current = sec.id; lastSection = sec.id; query = ""
                    }
                }
            }
            val visible: List<Pair<Section, List<Row>>> =
                if (query.isNotBlank()) all.mapNotNull { s ->
                    val r = s.rows.filter { listOf(it.label, it.desc, tr(it.label), tr(it.desc)).joinToString(" ").contains(query.trim(), ignoreCase = true) }
                    if (r.isEmpty()) null else s to r
                } else listOf(all.first { it.id == current }.let { it to it.rows })

            if (narrow) {
                LazyRow(panelBox.fillMaxWidth().padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) { item { Row { nav() } } }
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                if (!narrow) LazyColumn(panelBox.width(290.dp).fillMaxHeight().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { item { Column { nav() } } }
                LazyColumn(panelBox.weight(1f).fillMaxHeight().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    if (visible.isEmpty()) item { Text("No hay ajustes que coincidan con “$query”.", color = c.inkSoft, modifier = Modifier.padding(16.dp)) }
                    visible.forEach { (sec, rows) ->
                        item(key = "h_" + sec.id) {
                            Row(Modifier.padding(start = 4.dp, top = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                SectionIcon(sec.icon, c.accentDeep, Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(sec.name, color = c.accentDeep, fontWeight = FontWeight.ExtraBold, fontSize = 19.sp)
                            }
                        }
                        itemsIndexed(rows, key = { i, r -> sec.id + i + r.label }) { _, r ->
                            SettingRow(r, vm.settings, onToggle = { t -> save(t.set(vm.settings, !t.get(vm.settings))) }, onOpen = { picking = it })
                        }
                    }
                }
            }
        }
    }

    // Ventanas para elegir una opción o escribir un valor
    when (val p = picking) {
        is Row.Choice -> Sheet(p.label, onDismiss = { picking = null }) {
            if (p.desc.isNotEmpty()) Text(p.desc, color = c.inkSoft, fontSize = 13.sp, modifier = Modifier.padding(bottom = 10.dp))
            val first = remember { FocusRequester() }
            LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
            val cur = p.get(vm.settings)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                p.options.forEach { (v, l) ->
                    PickOption(l, selected = v == cur, modifier = if (v == cur || (p.options.none { it.first == cur } && v == p.options.first().first)) Modifier.focusRequester(first) else Modifier) {
                        save(p.set(vm.settings, v)); picking = null
                    }
                }
            }
        }
        is Row.TextField -> Sheet(p.label, onDismiss = { picking = null }) {
            if (p.desc.isNotEmpty()) Text(p.desc, color = c.inkSoft, fontSize = 13.sp, modifier = Modifier.padding(bottom = 10.dp))
            var text by remember { mutableStateOf(p.get(vm.settings)) }
            val fr = remember { FocusRequester() }
            LaunchedEffect(Unit) { delay(60); runCatching { fr.requestFocus() } }
            Field(text, { text = it }, p.label, modifier = Modifier.focusRequester(fr), password = p.secret, keyboard = KeyboardType.Text, onDone = { save(p.set(vm.settings, text.trim())); picking = null })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                ActionButton("Cancelar", ghost = true) { picking = null }
                ActionButton("Guardar") { save(p.set(vm.settings, text.trim())); picking = null }
            }
        }
        else -> Unit
    }
}

@Composable
private fun SectionButton(sec: Section, active: Boolean, modifier: Modifier, compact: Boolean, onClick: () -> Unit) {
    val c = LocalIptv.current
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    // En TV, moverse por la lista ya muestra la sección (como en OwnTV)
    LaunchedEffect(focused) { if (focused && !active) onClick() }
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier
            .then(if (compact) Modifier else Modifier.fillMaxWidth())
            .clip(shape)
            .background(if (active) c.accent.copy(alpha = 0.08f) else Color.Transparent)
            .border(2.dp, if (focused) c.focus else Color.Transparent, shape)
            .clickable(interactionSource = src, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(if (active) c.accent else c.bg), contentAlignment = Alignment.Center) {
            SectionIcon(sec.icon, if (active) Color.White else c.accentDeep, Modifier.size(20.dp))
        }
        Text(sec.name, color = c.ink, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, maxLines = 1,
            modifier = if (compact) Modifier else Modifier.weight(1f))
        if (!compact) Text("${sec.rows.size}", color = c.inkSoft, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp,
            modifier = Modifier.clip(CircleShape).background(c.bg).padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

@Composable
private fun SettingRow(r: Row, s: Settings, onToggle: (Row.Toggle) -> Unit, onOpen: (Row) -> Unit) {
    val c = LocalIptv.current
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    var confirm by remember(r) { mutableStateOf(false) }
    LaunchedEffect(confirm) { if (confirm) { delay(3500); confirm = false } }
    val shape = RoundedCornerShape(12.dp)
    val value = when (r) {
        is Row.Choice -> r.options.firstOrNull { it.first == r.get(s) }?.second ?: r.get(s)
        is Row.TextField -> if (r.secret) (if (r.get(s).isBlank()) "Sin configurar" else "••••••") else r.get(s).ifBlank { "Sin configurar" }
        is Row.Action -> r.value()
        is Row.Info -> r.value
        else -> ""
    }
    val clickable = r !is Row.Info
    Row(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(if (focused) c.accent.copy(alpha = 0.07f) else Color.Transparent)
            .border(2.dp, if (focused) c.focus else Color.Transparent, shape)
            .then(if (clickable) Modifier.clickable(interactionSource = src, indication = null) {
                when (r) {
                    is Row.Toggle -> onToggle(r)
                    is Row.Action -> r.run()
                    is Row.Danger -> if (confirm) { confirm = false; r.run() } else confirm = true
                    is Row.Choice, is Row.TextField -> onOpen(r)
                    is Row.Info -> Unit
                }
            } else Modifier)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (r is Row.Danger && confirm) "${r.label}: pulsa otra vez para confirmar" else r.label,
                color = if (r is Row.Danger) Color(0xFFD9534F) else c.ink, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp,
            )
            if (r.desc.isNotEmpty()) Text(r.desc, color = c.inkSoft, fontSize = 13.sp)
        }
        if (r is Row.Toggle) Switch(r.get(s))
        else if (value.isNotEmpty()) Text(value, color = c.inkSoft, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 280.dp))
        if (r is Row.Choice || r is Row.TextField || (r is Row.Action && r.arrow)) Text("›", color = c.inkSoft, fontSize = 22.sp)
    }
}

@Composable
private fun Switch(on: Boolean) {
    val c = LocalIptv.current
    Box(Modifier.size(width = 50.dp, height = 28.dp).clip(CircleShape).background(if (on) c.accent else c.tileEdge).padding(3.dp)) {
        Box(Modifier.size(22.dp).align(if (on) Alignment.CenterEnd else Alignment.CenterStart).clip(CircleShape).background(Color.White))
    }
}

@Composable
private fun PickOption(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalIptv.current
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier.fillMaxWidth().clip(shape)
            .background(if (selected) c.accent.copy(alpha = 0.08f) else c.bg)
            .border(2.dp, if (focused) c.focus else Color.Transparent, shape)
            .clickable(interactionSource = src, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = c.ink, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (selected) Text("✓", color = c.accentDeep, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun SectionIcon(icon: SecIcon, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val s = size.minDimension / 24f
        val w = 2f * s
        val st = Stroke(width = w, cap = StrokeCap.Round)
        fun line(vararg p: Float) { for (i in 0 until p.size - 2 step 2) drawLine(color, Offset(p[i] * s, p[i + 1] * s), Offset(p[i + 2] * s, p[i + 3] * s), w, cap = StrokeCap.Round) }
        fun rect(x: Float, y: Float, ww: Float, h: Float, r: Float = 3f) = drawRoundRect(color, Offset(x * s, y * s), Size(ww * s, h * s), CornerRadius(r * s), style = st)
        when (icon) {
            SecIcon.QUICK -> drawPath(Path().apply { moveTo(13 * s, 2 * s); lineTo(4 * s, 14 * s); lineTo(11 * s, 14 * s); lineTo(10 * s, 22 * s); lineTo(19 * s, 10 * s); lineTo(12 * s, 10 * s); close() }, color, style = st)
            SecIcon.SOURCES -> { line(4f, 6f, 20f, 6f); line(4f, 12f, 20f, 12f); line(4f, 18f, 14f, 18f); drawCircle(color, 2f * s, Offset(19 * s, 18 * s), style = st) }
            SecIcon.LOOK -> { drawCircle(color, 9 * s, Offset(12 * s, 12 * s), style = st); drawArc(color, 90f, 180f, true, Offset(3 * s, 3 * s), Size(18 * s, 18 * s)) }
            SecIcon.PLAY -> { rect(3f, 5f, 18f, 14f); drawPath(Path().apply { moveTo(10 * s, 9 * s); lineTo(15 * s, 12 * s); lineTo(10 * s, 15 * s); close() }, color) }
            SecIcon.SUBS -> { rect(3f, 5f, 18f, 14f); line(7f, 15f, 11f, 15f); line(13f, 15f, 17f, 15f); line(7f, 11f, 17f, 11f) }
            SecIcon.STARTUP -> { line(12f, 3f, 12f, 12f); drawArc(color, -50f, 280f, false, Offset(4 * s, 5 * s), Size(16 * s, 16 * s), style = st) }
            SecIcon.LOCK -> { drawRoundRect(color, Offset(5 * s, 11 * s), Size(14 * s, 10 * s), androidx.compose.ui.geometry.CornerRadius(2 * s), style = st); drawArc(color, 180f, 180f, false, Offset(8 * s, 4 * s), Size(8 * s, 10 * s), style = st); line(8f, 9f, 8f, 11f); line(16f, 9f, 16f, 11f) }
            SecIcon.DATA -> { drawOval(color, Offset(4 * s, 3 * s), Size(16 * s, 6 * s), style = st); line(4f, 6f, 4f, 18f); line(20f, 6f, 20f, 18f); drawArc(color, 0f, 180f, false, Offset(4 * s, 15 * s), Size(16 * s, 6 * s), style = st) }
            SecIcon.ABOUT -> { drawCircle(color, 9 * s, Offset(12 * s, 12 * s), style = st); line(12f, 11f, 12f, 17f); drawCircle(color, 1.2f * s, Offset(12 * s, 7.5f * s)) }
        }
    }
}
