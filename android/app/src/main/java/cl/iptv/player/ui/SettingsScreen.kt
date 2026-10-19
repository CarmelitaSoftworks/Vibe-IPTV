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
    class Slider(label: String, desc: String = "", val min: Float, val max: Float, val step: Float,
                 val format: (Float) -> String, val get: (Settings) -> Float, val set: (Settings, Float) -> Settings) : Row(label, desc)
}

private enum class SecIcon { QUICK, SOURCES, LOOK, PLAY, SUBS, STARTUP, LOCK, DATA, ABOUT }
private class Section(val id: String, val name: String, val icon: SecIcon, val rows: List<Row>)

private fun sections(vm: AppViewModel, ctx: android.content.Context, isTv: Boolean, onExport: () -> Unit = {}, onImport: () -> Unit = {}, onProfiles: () -> Unit = {}, onSaveLog: () -> Unit = {}): List<Section> {
    val offsets = (-3..3).map { h -> h.toString() to if (h == 0) "Sin desfase" else "${if (h > 0) "+" else ""}$h h" }
    return listOf(
        Section("quick", "Rápido", SecIcon.QUICK, listOf(
            Row.Toggle("Números de canal", "Muestra el número en cada mosaico de TV en vivo", { it.showNumbers }, { s, v -> s.copy(showNumbers = v) }),
            Row.Toggle("Guía bajo los mosaicos", "Programa actual y siguiente del canal enfocado", { it.showEpgInfo }, { s, v -> s.copy(showEpgInfo = v) }),
            Row.Toggle("Siguiente episodio automático", "Al terminar un episodio empieza el siguiente", { it.autoNext }, { s, v -> s.copy(autoNext = v) }),
            Row.Choice("Modo liviano (TV boxes)", "Menos efectos y memoria: más fluido en Xiaomi Mi Box / Mi TV Stick y equipos de 1–2 GB · ${cl.iptv.player.data.Device.summary(ctx)}",
                listOf("auto" to "Automático (${if (vm.lowEnd) "activado" else "desactivado"} en este equipo)", "on" to "Activado", "off" to "Desactivado"),
                get = { it.lite }, set = { s, v -> s.copy(lite = v) }),
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
            Row.Slider("Tamaño de los controles del reproductor", "Botones, textos y paneles del reproductor (HUD)",
                min = 0.7f, max = 1.5f, step = 0.05f, format = { "${Math.round(it * 100)} %" }, get = { it.hudScale }, set = { s, v -> s.copy(hudScale = v) }),
            Row.Choice("Búfer", "Cuánto video se carga por adelantado. Si un canal se corta seguido, la app le sube el búfer sola",
                listOf("fast" to "Rápido: cambia de canal antes", "balanced" to "Equilibrado (recomendado)", "stable" to "Estable: para proveedores que se cortan"),
                get = { it.buffer }, set = { s, v -> s.copy(buffer = v) }),
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
            Row.Action("Probar la clave de SubDL", "Comprueba la clave y muestra tu plan · consíguela gratis en subdl.com/panel/api",
                value = { vm.subdlStatus.ifBlank { if (vm.settings.subdlKey.isBlank()) "Sin clave" else "Probar" } }) { vm.checkSubdl() },
            Row.TextField("API key de OpenSubtitles (opcional)", "Sin clave igual se busca en OpenSubtitles; con clave hay más resultados", secret = true, get = { it.osApiKey }, set = { s, v -> s.copy(osApiKey = v) }),
            Row.TextField("Usuario de OpenSubtitles", "Opcional: más descargas diarias", get = { it.osUser }, set = { s, v -> s.copy(osUser = v) }),
            Row.TextField("Contraseña de OpenSubtitles", secret = true, get = { it.osPass }, set = { s, v -> s.copy(osPass = v) }),
        )),
        Section("startup", "Inicio de la app", SecIcon.STARTUP, listOf(
            Row.Choice("Al abrir la app", options = listOf("home" to "Inicio de la última lista", "welcome" to "Pantalla de mis listas", "lastChannel" to "Último canal visto"), get = { it.startup }, set = { s, v -> s.copy(startup = v) }),
        )),
        Section("meta", "Fichas", SecIcon.ABOUT, listOf(
            Row.Toggle("Mostrar la ficha de las películas", "Al elegir una película se ve su sinopsis antes de reproducir", get = { it.showDetails }, set = { s, v -> s.copy(showDetails = v) }),
            Row.TextField("Clave de TMDB (opcional)", "Gratis en themoviedb.org → Ajustes → API. Las cuentas Xtream ya traen fichas; la clave sirve para listas M3U.",
                secret = true, get = { it.tmdbKey }, set = { s, v -> s.copy(tmdbKey = v) }),
        )),
        Section("parental", "Control parental", SecIcon.LOCK, listOf(
            Row.Action("Perfil", "Favoritos y Seguir viendo por persona; perfil infantil sin contenido para adultos",
                value = { (if (vm.profile.kid) "🧒 " else "👤 ") + vm.profile.name }, arrow = true) { onProfiles() },
            Row.TextField("PIN de control parental", "4 dígitos. Con PIN, las categorías para adultos (XXX, Adultos, +18…) quedan ocultas. Vacío = desactivado.",
                secret = true, get = { it.parentalPin }, set = { s, v -> s.copy(parentalPin = v.filter(Char::isDigit).take(8)) }),
            Row.Action("Contenido para adultos", "Se desbloquea con el PIN desde la barra de categorías, hasta cerrar la app",
                value = { if (!vm.parentalOn) "Visible (sin PIN)" else if (vm.adultUnlocked) "Desbloqueado" else "Oculto (${vm.hiddenAdult})" }) {
                if (vm.adultUnlocked) vm.lockAdult()
            },
        )),
        Section("data", "Datos", SecIcon.DATA, listOf(
            Row.Action("Exportar respaldo", "Listas (con contraseñas), favoritos, Seguir viendo y ajustes en un archivo", arrow = true) { onExport() },
            Row.Action("Importar respaldo", "Reemplaza lo guardado en este equipo por el del archivo", arrow = true) { onImport() },
            Row.Danger("Borrar \"Seguir viendo\"", "Olvida dónde quedaste en películas y series de esta lista") { vm.clearResume() },
            Row.Danger("Borrar guías guardadas", "Se vuelven a descargar al abrir cada lista") { vm.clearEpgCache() },
            Row.Danger("Borrar favoritos de esta lista") { vm.clearFavorites() },
            Row.Danger("Olvidar búfer aprendido", "Vuelve al búfer elegido en todos los canales") { vm.forgetBufferBoosts() },
            Row.Danger("Restablecer ajustes", "Vuelve a los valores de fábrica (no borra tus listas)") { vm.resetSettings() },
        )),
        Section("about", "Acerca de", SecIcon.ABOUT, listOf(
            Row.Info("Vibe IPTV", "Versión ${vm.versionName}"),
            Row.Action("Actualizaciones", vm.update?.let { "Descarga el APK y ábrelo para instalarlo encima: se conservan tus listas" }
                ?: "Busca una versión más nueva en GitHub", value = { vm.updateStatus.ifBlank { "Buscar ahora" } }, arrow = true) {
                val u = vm.update
                if (u == null) vm.checkUpdate(isTv, silent = false)
                else try {
                    ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(u.url)))
                } catch (e: android.content.ActivityNotFoundException) {
                    vm.message = "Sin navegador: usa Downloader con el código 9370754"
                }
            },
            Row.Action("Enviar registro", "Para reportar un problema: errores y cambios de motor recientes, sin usuarios ni contraseñas", arrow = true) {
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_SUBJECT, "Vibe IPTV · registro")
                    putExtra(android.content.Intent.EXTRA_TEXT, vm.logReport(ctx))
                }
                try { ctx.startActivity(android.content.Intent.createChooser(send, "Enviar registro")) }
                catch (e: android.content.ActivityNotFoundException) { onSaveLog() }
            },
            Row.Action("Guardar registro en un archivo", "Útil en TV, donde no siempre se puede compartir", arrow = true) { onSaveLog() },
            Row.Info("Equipo detectado", vm.hw.text()),
            Row.Action("Volver a detectar y configurar", "Ajusta modo liviano, búfer y animaciones según este equipo", arrow = true) { vm.autoConfigure(force = true) },
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
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val exporter = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")) { uri -> if (uri != null) vm.exportBackup(uri) }
    val importer = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importBackup(uri) }
    val logSaver = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain")) { uri -> if (uri != null) vm.exportLog(uri) }
    var showProfiles by remember { mutableStateOf(false) }
    if (showProfiles) ProfilesDialog(vm) { showProfiles = false }
    val all = sections(vm, ctx, (ctx as? cl.iptv.player.MainActivity)?.isTv == true,
        onProfiles = { showProfiles = true },
        onSaveLog = {
            try { logSaver.launch("vibe-iptv-registro.txt") }
            catch (e: android.content.ActivityNotFoundException) { vm.message = "Este dispositivo no tiene selector de archivos" }
        },
        onExport = {
            try { exporter.launch("vibe-iptv-respaldo-${java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())}.json") }
            catch (e: android.content.ActivityNotFoundException) { vm.message = "Este dispositivo no tiene selector de archivos" }
        },
        onImport = {
            try { importer.launch(arrayOf("application/json", "text/plain", "*/*")) }
            catch (e: android.content.ActivityNotFoundException) { vm.message = "Este dispositivo no tiene selector de archivos" }
        })
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
        is Row.Slider -> Sheet(p.label, onDismiss = { picking = null }) {
            if (p.desc.isNotEmpty()) Text(p.desc, color = c.inkSoft, fontSize = 13.sp, modifier = Modifier.padding(bottom = 10.dp))
            val v = p.get(vm.settings)
            fun setV(x: Float) { val r = (Math.round(x / p.step) * p.step).coerceIn(p.min, p.max); save(p.set(vm.settings, r)) }
            val minus = remember { FocusRequester() }
            LaunchedEffect(Unit) { delay(60); runCatching { minus.requestFocus() } }
            Text(p.format(v), color = c.accentDeep, fontWeight = FontWeight.ExtraBold, fontSize = 30.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionButton("−", Modifier.focusRequester(minus), ghost = true) { setV(v - p.step) }
                androidx.compose.material3.Slider(
                    value = v, onValueChange = { setV(it) }, valueRange = p.min..p.max,
                    steps = ((p.max - p.min) / p.step).toInt() - 1, modifier = Modifier.weight(1f),
                    colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent),
                )
                ActionButton("+", ghost = true) { setV(v + p.step) }
            }
            // Vista previa con el tamaño elegido
            val d = androidx.compose.ui.platform.LocalDensity.current
            Box(Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF101417)).padding(12.dp), contentAlignment = Alignment.Center) {
                androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density * v, d.fontScale)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf(PAction.PREV, PAction.PLAY, PAction.NEXT, PAction.AUDIO, PAction.SUBS).forEach { a ->
                            Box(Modifier.size(if (a == PAction.PLAY) 60.dp else 48.dp).clip(androidx.compose.foundation.shape.CircleShape)
                                .background(Color.White.copy(alpha = if (a == PAction.PLAY) 0.18f else 0f)), contentAlignment = Alignment.Center) {
                                PlayerIcon(a, Color.White, Modifier.size(if (a == PAction.PLAY) 30.dp else 24.dp))
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                ActionButton("Normal", ghost = true) { setV(1f) }
                ActionButton("Listo") { picking = null }
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
        is Row.Slider -> r.format(r.get(s))
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
                    is Row.Choice, is Row.TextField, is Row.Slider -> onOpen(r)
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
        if (r is Row.Choice || r is Row.TextField || r is Row.Slider || (r is Row.Action && r.arrow)) Text("›", color = c.inkSoft, fontSize = 22.sp)
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
