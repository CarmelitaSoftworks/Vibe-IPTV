package cl.iptv.player.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cl.iptv.player.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.Normalizer

enum class Screen { MENU, PLAYER }

const val CAT_ALL = "__all__"
const val CAT_FAV = "__fav__"
const val CAT_RESUME = "__resume__"

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val store = Storage(app)
    val subtitles = OpenSubtitles { settings }
    val subtitleSources = SubtitleSources { settings }

    var sources by mutableStateOf(store.sources()); private set
    var settings by mutableStateOf(store.settings()); private set
    var source by mutableStateOf<Source?>(null); private set
    var items by mutableStateOf<List<Item>>(emptyList()); private set
    var epg by mutableStateOf(Epg.EMPTY); private set
    /** Momento de la última guía cargada (0 = ninguna) y texto de estado para Ajustes. */
    var epgAt by mutableStateOf(0L); private set
    var epgStatus by mutableStateOf(""); private set
    private var epgUrls = ""
    private var epgJob: Job? = null
    var loading by mutableStateOf<String?>(null); private set
    var message by mutableStateOf<String?>(null)

    /** Pantalla de inicio con los mosaicos TV en vivo / Películas / Series. */
    var home by mutableStateOf(true); private set
    var section by mutableStateOf(Kind.LIVE); private set
    var category by mutableStateOf(CAT_ALL); private set
    var query by mutableStateOf(""); private set
    var series by mutableStateOf<Item?>(null); private set
    private var episodes: List<Item> = emptyList()
    var favorites by mutableStateOf<Set<String>>(emptySet()); private set
    /** "Seguir viendo": dónde quedó cada película o episodio de esta lista. */
    var resume by mutableStateOf<Map<String, Resume>>(emptyMap()); private set

    // ---------- Control parental ----------
    /** Toda la lista; [items] es lo visible (sin contenido para adultos si hay PIN y está bloqueado). */
    private var allItems: List<Item> = emptyList()
    var adultUnlocked by mutableStateOf(false); private set
    var hiddenAdult by mutableStateOf(0); private set
    val parentalOn: Boolean get() = settings.parentalPin.isNotBlank()
    private fun applyParental() {
        items = if (parentalOn && !adultUnlocked) allItems.filterNot { it.isAdult() } else allItems
        hiddenAdult = allItems.size - items.size
        if (category != CAT_ALL && category != CAT_FAV && category != CAT_RESUME && items.none { it.cat == category }) { category = CAT_ALL; subcategory = "" }
        recompute(false)
    }
    /** Devuelve false si el PIN no coincide. */
    fun unlockAdult(pin: String): Boolean {
        if (pin != settings.parentalPin) return false
        adultUnlocked = true; applyParental(); message = "Contenido para adultos visible hasta cerrar la app"; return true
    }
    fun lockAdult() { adultUnlocked = false; applyParental(); message = "Contenido para adultos oculto" }

    /** Lista visible (filtrada) que dibuja la cuadrícula. */
    var view by mutableStateOf<List<Item>>(emptyList()); private set
    var page by mutableStateOf(0)
    var pageInfo by mutableStateOf("")

    var screen by mutableStateOf(Screen.MENU); private set
    /** Pantalla de inicio para añadir / elegir listas. */
    var showWelcome by mutableStateOf(false)
    /** Pantalla de Ajustes (dos columnas). */
    var showSettings by mutableStateOf(false)
    /** Guía de programación en grilla. */
    var showGuide by mutableStateOf(false)

    /** Canales de la guía: los de la categoría elegida en TV en vivo, o todos. */
    fun guideChannels(): List<Item> =
        if (!home && section == Kind.LIVE && series == null && query.isBlank()) view.filter { it.kind == Kind.LIVE }
        else items.filter { it.kind == Kind.LIVE }

    /** TV en diferido: reproduce un programa ya emitido desde su inicio. */
    fun playCatchup(channel: Item, prog: Programme): Boolean {
        val url = channel.catchupUrlFor(prog) ?: return false
        val it = channel.copy(
            id = "cu_${channel.id}_${prog.start}", kind = Kind.MOVIE, url = url,
            name = "${prog.title} · ${channel.name}", num = null,
        )
        activate(it, listOf(it))
        return true
    }
    /** Motor con el que funcionó el último canal en vivo (VLC o MPV): los siguientes parten con él. */
    var preferLiveEngine by mutableStateOf<String?>(null)

    /** Desfase de la guía elegido en Ajustes, en milisegundos. */
    val epgShift: Long get() = settings.epgOffset * 3600_000L
    fun nowNext(item: Item) = epg.nowNext(item, System.currentTimeMillis() - epgShift)
    var playList by mutableStateOf<List<Item>>(emptyList()); private set
    var playIndex by mutableStateOf(0); private set
    val playing: Item? get() = playList.getOrNull(playIndex)

    /** Último elemento reproducido: al volver al menú se enfoca su mosaico. */
    var returnFocusId by mutableStateOf<String?>(null)

    private var xtream: XtreamClient? = null
    private var resumeLastChannel = store.settings().startup == "lastChannel"
    private var loadJob: Job? = null

    init {
        val last = sources.firstOrNull { it.id == store.lastSourceId } ?: sources.firstOrNull()
        if (last != null && settings.startup != "welcome") open(last) else showWelcome = true
        // Renovación periódica de la guía mientras la app está abierta
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(10 * 60_000L)
                val every = settings.epgEvery * 3600_000L   // 0 = sólo al abrir la app
                if (every > 0 && source != null && epgUrls.isNotBlank() && System.currentTimeMillis() - epgAt > every) refreshEpg()
            }
        }
    }

    companion object {
        const val EPG_ON_RETURN_MS = 30 * 60_000L
    }

    // ---------- Actualizaciones ----------
    var update by mutableStateOf<Updates.Available?>(null); private set
    var updateStatus by mutableStateOf(""); private set
    val versionName: String get() = Updates.currentName(getApplication())
    /** Revisa GitHub (al abrir la app, en silencio; o desde Ajustes → Acerca de). */
    fun checkUpdate(isTv: Boolean, silent: Boolean = true) {
        viewModelScope.launch {
            if (!silent) updateStatus = "Buscando…"
            runCatching { Updates.check(getApplication(), isTv) }
                .onSuccess {
                    update = it
                    updateStatus = if (it != null) "Versión ${it.name} disponible" else "Tienes la última versión"
                    if (it != null && silent) message = "Hay una versión nueva (${it.name}): Ajustes → Acerca de"
                    if (it == null && !silent) message = "Tienes la última versión"
                }
                .onFailure { updateStatus = "No se pudo revisar (sin conexión)"; if (!silent) message = updateStatus }
        }
    }

    /** La app vuelve a primer plano: actualizar la guía si tiene más de 30 minutos. */
    fun onForeground() {
        if (source != null && epgUrls.isNotBlank() && epgJob?.isActive != true &&
            System.currentTimeMillis() - epgAt > EPG_ON_RETURN_MS) refreshEpg()
    }

    val sectionsAvailable: List<Kind> get() = Kind.entries.filter { k -> items.any { it.kind == k } }
    fun countOf(k: Kind) = items.count { it.kind == k }
    fun groupsOf(k: Kind) = items.asSequence().filter { it.kind == k }.map { it.group }.distinct().count()

    var subcategory by mutableStateOf(""); private set

    /** Categorías de la sección actual con su cantidad, en el orden de la lista. */
    fun categories(): List<Pair<String, Int>> =
        items.filter { it.kind == section }.groupingBy { it.cat }.eachCount().toList()
    /** Subcategorías de la categoría elegida (vacío si la lista no las trae). */
    fun subcategories(): List<Pair<String, Int>> =
        if (category == CAT_ALL || category == CAT_FAV) emptyList()
        else items.filter { it.kind == section && it.cat == category && it.sub.isNotEmpty() }.groupingBy { it.sub }.eachCount().toList()
    fun favoritesInSection() = items.count { it.kind == section && it.id in favorites }
    fun resumeInSection(): Int {
        val parents = resume.values.map { it.parent }.toSet()
        return items.count { it.kind == section && (it.id in resume || it.id in parents) }
    }

    /** Guarda dónde quedó una película o episodio; lo borra si se terminó de ver o casi no se vio. */
    fun savePosition(item: Item, pos: Long, dur: Long) {
        val src = source ?: return
        if (item.kind == Kind.LIVE || dur <= 0) return
        val done = pos > dur - 90_000 || pos > dur * 0.95
        val next = if (done || pos < 30_000) resume - item.id
            else resume + (item.id to Resume(pos, dur, System.currentTimeMillis(), series?.id.orEmpty()))
        if (next != resume) { resume = next; store.saveResume(src.id, next); if (category == CAT_RESUME) recompute(false) }
    }
    fun resumeFor(item: Item): Resume? = resume[item.id]
    fun clearResume() {
        val src = source ?: run { message = "Abre una lista primero"; return }
        store.clearResume(src.id); resume = emptyMap(); recompute(false); message = "\"Seguir viendo\" borrado"
    }

    // ---------- fuentes ----------
    fun open(src: Source) {
        loadJob?.cancel()
        source = src; allItems = emptyList(); items = emptyList(); epg = Epg.EMPTY; series = null; episodes = emptyList(); home = true
        epgJob?.cancel(); epgAt = 0; epgUrls = ""; epgStatus = ""
        loadCachedEpg(src)   // la última guía guardada se muestra al instante
        showWelcome = false
        favorites = store.favorites(src.id)
        resume = store.resume(src.id)
        store.lastSourceId = src.id
        recompute(true)
        loadJob = viewModelScope.launch {
            loading = "Cargando ${src.name}…"
            try {
                var epgUrl = src.epgUrl
                if (src.type == SourceType.XTREAM) {
                    val x = XtreamClient(src.server, src.username, src.password, settings.liveFormat)
                    xtream = x
                    x.login()
                    x.workingBase?.takeIf { it != XtreamClient.normalize(src.server) }?.let { fixed ->
                        val updated = src.copy(server = fixed)
                        store.upsert(updated); sources = store.sources(); source = updated
                        message = "Dirección corregida a $fixed"
                    }
                    allItems = x.loadAll().map { it.withCategory() }; applyParental()
                    if (epgUrl.isBlank()) epgUrl = x.epgUrl()
                } else {
                    xtream = null
                    val result = if (src.localFile) withContext(Dispatchers.IO) {
                        store.localM3u(src.id).bufferedReader().use(M3uParser::parse)
                    } else Http.stream(src.url) { it.bufferedReader().use(M3uParser::parse) }
                    allItems = result.items.map { it.withCategory() }; applyParental()
                    if (epgUrl.isBlank()) epgUrl = result.epgUrl
                }
                val kinds = sectionsAvailable
                section = if (section in kinds) section else kinds.firstOrNull() ?: Kind.LIVE
                category = CAT_ALL; query = ""
                recompute(true)
                loading = null
                // Inicio de la app → "Último canal visto"
                if (resumeLastChannel) {
                    resumeLastChannel = false
                    items.firstOrNull { it.id == store.lastItemId }?.let { activate(it, items.filter { i -> i.kind == it.kind }) }
                }
                refreshEpg(epgUrl)   // siempre al añadir la lista y al abrir la app
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                message = e.message ?: e.toString()
                loading = null
            }
        }
    }

    // ---------- guía EPG: caché en disco + actualización ----------
    private fun epgDir() = File(getApplication<Application>().filesDir, "epg").apply { mkdirs() }
    private fun epgFiles(srcId: String) = epgDir().listFiles { f -> f.name.startsWith("${srcId}_") && !f.name.endsWith(".part") }
        ?.sortedBy { it.name }.orEmpty()

    private fun parseFiles(files: List<File>): Epg? {
        val parsed = files.mapNotNull { f -> runCatching { f.inputStream().use { EpgParser.parse(it) } }.getOrNull() }
        return if (parsed.isEmpty()) null else Epg.merge(parsed)
    }

    private fun loadCachedEpg(src: Source) = viewModelScope.launch {
        val files = epgFiles(src.id)
        if (files.isEmpty()) return@launch
        val e = withContext(Dispatchers.IO) { parseFiles(files) } ?: return@launch
        if (source?.id != src.id || epgAt > 0) return@launch     // ya llegó una más nueva
        epg = e
        epgAt = files.maxOf { it.lastModified() }
        epgStatus = "Guía guardada del ${java.text.SimpleDateFormat("dd/MM HH:mm", I18n.locale).format(java.util.Date(epgAt))} · actualizando…"
    }

    /** Descarga una o varias guías (separadas por coma o espacio), las guarda y las combina. */
    fun refreshEpg(urls: String = epgUrls) {
        val src = source ?: return
        epgUrls = urls
        if (urls.isBlank()) { epgStatus = "Esta lista no trae guía. Agrega una URL XMLTV al editar la lista."; return }
        epgJob?.cancel()
        epgJob = viewModelScope.launch {
            epgStatus = "Actualizando guía…"
            val list = urls.split(Regex("[\\s,]+")).filter { it.isNotBlank() }
            val files = list.mapIndexed { i, url ->
                async {
                    runCatching { Http.download(url, File(epgDir(), "${src.id}_$i.xml")) }
                        .onFailure { android.util.Log.w("IPTV", "EPG: ${it.message}") }.getOrNull()
                }
            }.awaitAll()
            if (source?.id != src.id) return@launch
            val ok = files.filterNotNull()
            if (ok.isEmpty()) {
                epgStatus = "No se pudo actualizar la guía (sin respuesta del servidor)." + if (epgAt > 0) " Se mantiene la última guardada." else ""
                return@launch
            }
            // Descartar guías viejas de URLs que ya no están en la lista
            epgFiles(src.id).filter { f -> f !in ok }.forEach { it.delete() }
            val e = withContext(Dispatchers.IO) { parseFiles(ok) }
            if (e == null) { epgStatus = "La guía descargada no es un XMLTV válido."; return@launch }
            epg = e
            epgAt = System.currentTimeMillis()
            epgStatus = "Guía actualizada a las ${fmtTime(epgAt)} · ${e.channelCount} canales con programación"
        }
    }

    fun saveSource(src: Source, localUri: Uri?) {
        viewModelScope.launch {
            try {
                var s = src
                if (localUri != null) {
                    withContext(Dispatchers.IO) {
                        getApplication<Application>().contentResolver.openInputStream(localUri)!!.use { input ->
                            store.localM3u(src.id).outputStream().use { input.copyTo(it) }
                        }
                    }
                    s = s.copy(localFile = true, url = "")
                }
                store.upsert(s)
                sources = store.sources()
                open(s)
            } catch (e: Exception) { message = "No se pudo guardar: ${e.message}" }
        }
    }

    fun deleteSource(src: Source) {
        store.remove(src.id)
        epgFiles(src.id).forEach { it.delete() }
        sources = store.sources()
        if (source?.id == src.id) {
            source = null; allItems = emptyList(); items = emptyList(); recompute(true)
            showWelcome = true
        }
    }

    fun updateSettings(s: Settings) {
        val formatChanged = s.liveFormat != settings.liveFormat
        val pinChanged = s.parentalPin != settings.parentalPin
        settings = s
        store.saveSettings(s)
        if (pinChanged) { adultUnlocked = false; applyParental() }
        if (formatChanged) source?.takeIf { it.type == SourceType.XTREAM }?.let { open(it) }
    }

    // ---------- navegación / filtros ----------
    private fun baseList(): List<Item> = when {
        series != null -> episodes
        home -> items                       // búsqueda desde el inicio: todo el contenido
        else -> items.filter { it.kind == section }
    }

    private fun norm(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    private fun recompute(resetPage: Boolean) {
        if (home && query.isBlank()) { view = emptyList(); if (resetPage) page = 0; return }
        var list = baseList()
        if (query.isNotBlank()) {
            val q = norm(query)
            list = list.filter { norm(it.name).contains(q) }
        } else if (series == null) {
            list = when (category) {
                CAT_ALL -> list
                CAT_FAV -> list.filter { it.id in favorites }
                CAT_RESUME -> {
                    val parents = resume.values.map { it.parent }.toSet()
                    list.filter { it.id in resume || it.id in parents }
                        .sortedByDescending { i -> resume[i.id]?.at ?: resume.values.filter { it.parent == i.id }.maxOfOrNull { it.at } ?: 0L }
                }
                else -> list.filter { it.cat == category && (subcategory.isEmpty() || it.sub == subcategory) }
            }
        }
        view = list
        if (resetPage) page = 0
    }

    fun selectSection(k: Kind) { series = null; home = false; section = k; category = CAT_ALL; subcategory = ""; query = ""; recompute(true) }
    fun selectCategory(c: String, sub: String = "") { series = null; home = false; category = c; subcategory = sub; query = ""; recompute(true) }
    /** Sección desde la que se volvió al inicio (para enfocar su mosaico). */
    var returnHomeKind by mutableStateOf<Kind?>(null)
    fun goHome() { returnHomeKind = section; series = null; home = true; category = CAT_ALL; query = ""; recompute(true) }
    fun search(q: String) { query = q; recompute(true) }

    fun title(): String = when {
        source == null -> "Bienvenido"
        home && query.isBlank() -> "Inicio"
        query.isNotBlank() -> "Búsqueda: “$query”"
        series != null -> series!!.name
        category == CAT_ALL -> "${section.label} · Todos"
        category == CAT_FAV -> "Favoritos"
        category == CAT_RESUME -> "Seguir viendo"
        else -> if (subcategory.isNotEmpty()) "$category · $subcategory" else category
    }

    /** Back en el menú: sale de la serie / limpia filtros. Devuelve true si hizo algo. */
    fun back(): Boolean {
        val s = series
        return when {
            s != null -> { series = null; recompute(false); returnFocusId = s.id; true }
            subcategory.isNotEmpty() -> { subcategory = ""; recompute(true); true }
            query.isNotBlank() || category != CAT_ALL -> { query = ""; category = CAT_ALL; recompute(true); true }
            !home -> { goHome(); true }
            else -> false
        }
    }

    fun toggleFavorite(item: Item) {
        val src = source ?: return
        favorites = if (item.id in favorites) favorites - item.id else favorites + item.id
        store.saveFavorites(src.id, favorites)
        message = if (item.id in favorites) "★ ${item.name} en favoritos" else "${item.name} quitado de favoritos"
        if (category == CAT_FAV) recompute(false)
    }

    fun activate(item: Item, fromList: List<Item>? = null) {
        if (item.kind == Kind.SERIES) {
            if (item.episodes.isNotEmpty()) { episodes = item.episodes; series = item; query = ""; recompute(true); return }
            val x = xtream ?: return
            val id = item.seriesId ?: return
            viewModelScope.launch {
                loading = "Cargando episodios…"
                try {
                    episodes = x.episodes(id)
                    series = item; query = ""
                    recompute(true)
                } catch (e: Exception) { message = e.message }
                loading = null
            }
            return
        }
        val list = fromList ?: view.filter { it.kind == item.kind }
        store.lastItemId = item.id
        playList = list
        playIndex = list.indexOf(item).coerceAtLeast(0)
        screen = Screen.PLAYER
    }

    /** Ir directo a un elemento de la lista que se está reproduciendo (lista de canales en el reproductor). */
    fun playAt(index: Int) {
        if (index !in playList.indices) return
        playIndex = index
        playing?.let { store.lastItemId = it.id }
    }

    fun zap(delta: Int) {
        if (playList.size < 2) return
        playIndex = (playIndex + delta).mod(playList.size)
        playing?.let { store.lastItemId = it.id }
    }

    // ---------- Ajustes: acciones de "Datos" ----------
    fun clearEpgCache() {
        epgDir().listFiles()?.forEach { it.delete() }
        message = "Guías guardadas borradas"
    }
    fun clearFavorites() {
        val src = source ?: run { message = "Abre una lista primero"; return }
        store.clearFavorites(src.id); favorites = emptySet(); recompute(false)
        message = "Favoritos borrados"
    }
    fun resetSettings() { updateSettings(Settings()); message = "Ajustes restablecidos" }

    fun closePlayer() {
        returnFocusId = playing?.id
        screen = Screen.MENU
    }

    fun subtitleDir() = File(getApplication<Application>().cacheDir, "subs")
}
