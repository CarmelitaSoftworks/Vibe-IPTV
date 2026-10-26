package cl.iptv.player.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cl.iptv.player.data.*
import kotlinx.coroutines.sync.withPermit
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
const val CAT_RECENT = "__recent__"

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
    private val log = ArrayDeque<String>()
    private var messageState = mutableStateOf<String?>(null)
    /** Aviso breve en pantalla; también queda en el registro (Ajustes → Enviar registro). */
    var message: String?
        get() = messageState.value
        set(v) { messageState.value = v; if (v != null) logEvent("Aviso: $v") }

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

    // ---------- Perfiles ----------
    var profiles by mutableStateOf(store.profiles()); private set
    var profile by mutableStateOf(store.profiles().firstOrNull { it.id == store.profileId } ?: Profile("", "Principal")); private set
    /** Cambia de perfil. Salir de un perfil infantil pide el PIN si hay uno (devuelve false si no coincide). */
    fun switchProfile(p: Profile, pin: String? = null): Boolean {
        if (profile.kid && !p.kid && parentalOn && pin != settings.parentalPin) return false
        store.profileId = p.id; profile = p
        source?.let { favorites = store.favorites(it.id); resume = store.resume(it.id); recent = store.recent(it.id); loadCatPrefs() }
        adultUnlocked = false; applyParental()
        message = "Perfil: ${p.name}"
        return true
    }
    fun addProfile(name: String, kid: Boolean) {
        val p = Profile("p" + System.currentTimeMillis().toString(36), name.trim().ifBlank { "Perfil" }.take(24), kid)
        store.saveProfiles(profiles + p); profiles = store.profiles()
    }
    fun deleteProfile(p: Profile) {
        if (p.id.isBlank()) return
        store.saveProfiles(profiles.filter { it.id != p.id }); store.removeProfileData(p.id); profiles = store.profiles()
        if (profile.id == p.id) switchProfile(profiles.first(), settings.parentalPin)
    }

    // ---------- Control parental ----------
    /** Toda la lista; [items] es lo visible (sin contenido para adultos si hay PIN y está bloqueado). */
    private var allItems: List<Item> = emptyList()
    var adultUnlocked by mutableStateOf(false); private set
    var hiddenAdult by mutableStateOf(0); private set
    val parentalOn: Boolean get() = settings.parentalPin.isNotBlank()
    private fun applyParental() {
        // Perfil infantil: el contenido para adultos nunca se muestra
        items = (if ((parentalOn && !adultUnlocked) || profile.kid) allItems.filterNot { it.isAdult() } else allItems)
            .let { l -> if (settings.hideDead && deadIds.isNotEmpty()) l.filterNot { it.id in deadIds } else l }
        hiddenAdult = allItems.size - items.size
        if (category !in setOf(CAT_ALL, CAT_FAV, CAT_RESUME, CAT_RECENT) && items.none { it.cat == category }) { category = CAT_ALL; subcategory = "" }
        recompute(false)
    }
    /** Devuelve false si el PIN no coincide. */
    fun unlockAdult(pin: String): Boolean {
        if (pin != settings.parentalPin || profile.kid) return false
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
    /** Asistente del primer inicio: sólo la primera vez y si todavía no hay listas. */
    var showSetup by mutableStateOf(!store.setupDone && sources.isEmpty()); private set
    fun finishSetup() { store.setupDone = true; showSetup = false }
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

    // ---------- Equipo detectado al iniciar y modo liviano (TV boxes de Xiaomi y otros modestos) ----------
    val hw: Device.Hw = Device.detect(app)
    val lowEnd: Boolean = hw.lowEnd
    /**
     * Primera vez (o "Volver a detectar"): configura según el equipo. Después respeta lo que el usuario cambie;
     * si el equipo es otro (respaldo importado en otra TV) se vuelve a configurar.
     */
    fun autoConfigure(force: Boolean = false) {
        val key = "${hw.name}|${hw.ramMb / 256}"
        if (!force && store.hwConfigured == key) return
        store.hwConfigured = key
        updateSettings(settings.copy(lite = "auto", buffer = hw.buffer, animations = !hw.lite))
        logEvent("Equipo: ${hw.text()}")
        message = "Equipo detectado: ${hw.name}" + (if (hw.lite) " · modo liviano activado" else "")
    }
    var lite by mutableStateOf(false); private set
    fun applyLite() {
        lite = when (settings.lite) { "on" -> true; "off" -> false; else -> lowEnd }
        Perf.lite = lite
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
    fun categories(includeHidden: Boolean = false): List<Pair<String, Int>> {
        val all = items.filter { it.kind == section }.groupingBy { it.cat }.eachCount().toList()
        val order = catOrder[section.name].orEmpty()
        val sorted = all.sortedBy { (c, _) -> order.indexOf(c).let { if (it < 0) Int.MAX_VALUE else it } }
        return if (includeHidden) sorted else sorted.filter { it.first !in hiddenCats }
    }

    // ---------- Ocultar / ordenar categorías (por lista y perfil) ----------
    var hiddenCats by mutableStateOf<Set<String>>(emptySet()); private set
    var catOrder by mutableStateOf<Map<String, List<String>>>(emptyMap()); private set
    private fun loadCatPrefs() {
        val src = source ?: return
        hiddenCats = store.hiddenCats(src.id); catOrder = store.catOrder(src.id)
    }
    fun toggleHidden(cat: String) {
        val src = source ?: return
        hiddenCats = if (cat in hiddenCats) hiddenCats - cat else hiddenCats + cat
        store.saveHiddenCats(src.id, hiddenCats)
        if (category == cat) { category = CAT_ALL; subcategory = "" }
        recompute(false)
    }
    /** Mueve una categoría un lugar hacia arriba (delta -1) o abajo (+1). */
    fun moveCat(cat: String, delta: Int) {
        val src = source ?: return
        val cur = categories(includeHidden = true).map { it.first }.toMutableList()
        val i = cur.indexOf(cat); val j = i + delta
        if (i < 0 || j !in cur.indices) return
        cur.removeAt(i); cur.add(j, cat)
        catOrder = catOrder + (section.name to cur)
        store.saveCatOrder(src.id, catOrder)
    }
    fun resetCats() {
        val src = source ?: return
        hiddenCats = emptySet(); catOrder = emptyMap()
        store.saveHiddenCats(src.id, hiddenCats); store.saveCatOrder(src.id, catOrder); recompute(false)
    }
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
    fun resumeFor(item: Item): Resume? =
        remoteStart?.takeIf { it.first == item.id && it.second > 0 }?.let { Resume(it.second, 0, System.currentTimeMillis()) } ?: resume[item.id]

    /** Inicio → "Seguir viendo": películas y series a medias, la más reciente primero (episodios → su serie). */
    fun continueWatching(): List<Triple<Item, Resume, String?>> {
        val byId = items.associateBy { it.id }
        return resume.entries.sortedByDescending { it.value.at }.mapNotNull { (id, r) ->
            byId[id]?.let { Triple(it, r, null) } ?: byId[r.parent]?.let { Triple(it, r, id) }   // (serie, avance, episodio)
        }.distinctBy { it.first.id }.take(20)
    }
    /** Abre un elemento de "Seguir viendo": la película sigue donde quedó; la serie abre sus episodios con el foco en el que quedó. */
    fun openContinue(item: Item, episodeId: String?) {
        if (episodeId != null) { section = item.kind; home = false; returnFocusId = episodeId }
        choose(item)
    }

    // ---------- Respaldo ----------
    fun exportBackup(uri: android.net.Uri) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!.use { it.write(store.exportBackup().toByteArray()) }
                }
            }.onSuccess { message = "Respaldo guardado. Contiene tus contraseñas: guárdalo en un lugar privado." }
                .onFailure { message = "No se pudo guardar: ${it.message}" }
        }
    }
    fun importBackup(uri: android.net.Uri) {
        viewModelScope.launch {
            runCatching {
                val text = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
                }
                store.importBackup(text)
            }.onSuccess { n ->
                sources = store.sources(); settings = store.settings()
                message = "Respaldo restaurado: $n listas"
                (sources.firstOrNull { it.id == store.lastSourceId } ?: sources.firstOrNull())?.let { open(it) } ?: run { showWelcome = true }
            }.onFailure { message = "No se pudo restaurar: ${it.message}" }
        }
    }
    fun clearResume() {
        val src = source ?: run { message = "Abre una lista primero"; return }
        store.clearResume(src.id); resume = emptyMap(); recompute(false); message = "\"Seguir viendo\" borrado"
    }

    // ---------- fuentes ----------
    fun open(src: Source) {
        loadJob?.cancel()
        source = src; allItems = emptyList(); items = emptyList(); epg = Epg.EMPTY; series = null; episodes = emptyList(); home = true
        epgJob?.cancel(); epgAt = 0; epgUrls = ""; epgStatus = ""; guideTried.clear()
        showWelcome = false
        favorites = store.favorites(src.id)
        resume = store.resume(src.id)
        recent = store.recent(src.id)
        scanJob?.cancel(); scanStatus = ""
        ChannelCheck.load(getApplication(), src.id).let { (ids, at) -> deadIds = ids; deadAt = at }
        loadCatPrefs()
        store.lastSourceId = src.id
        recompute(true)
        loadJob = viewModelScope.launch {
            loading = "Cargando ${src.name}…"
            val app = getApplication<Application>()
            var epgUrl = src.epgUrl
            val x = if (src.type == SourceType.XTREAM) XtreamClient(src.server, src.username, src.password, settings.liveFormat) else null
            xtream = x
            xtreamAccount = null
            // 1) Lista guardada en el equipo: se muestra al instante, sin esperar al servidor
            val cached = withContext(Dispatchers.IO) { ListCache.load(app, src.id) { s -> x?.restore(s) ?: s } }
            if (cached != null) {
                allItems = cached.items; applyParental()
                if (epgUrl.isBlank()) epgUrl = cached.epgUrl
                listReady(src)
            }
            // 2) Lista del servidor: en segundo plano si ya se ve la guardada (en equipos modestos, sólo si tiene más de 12 h)
            val fresh = cached != null && lowEnd && System.currentTimeMillis() - cached.savedAt < 12 * 3600_000L
            try {
                if (!fresh) {
                    if (cached != null) loading = null
                    val loaded: List<Item>
                    if (x != null) {
                        x.login()
                        xtreamAccount = x.account
                        warnAccount(x.account)
                        x.workingBase?.takeIf { it != XtreamClient.normalize(src.server) }?.let { fixed ->
                            val updated = src.copy(server = fixed)
                            store.upsert(updated); sources = store.sources(); source = updated
                            message = "Dirección corregida a $fixed"
                        }
                        loaded = uniqueIds(x.loadAll().map { it.withCategory() })
                        if (epgUrl.isBlank()) epgUrl = x.epgUrl()
                    } else {
                        val result = if (src.localFile) withContext(Dispatchers.IO) {
                            store.localM3u(src.id).bufferedReader().use(M3uParser::parse)
                        } else Http.stream(src.url) { it.bufferedReader().use(M3uParser::parse) }
                        loaded = uniqueIds(result.items.map { it.withCategory() })
                        if (epgUrl.isBlank()) epgUrl = result.epgUrl
                    }
                    if (source?.id != src.id) return@launch
                    allItems = loaded; applyParental()
                    val epgForCache = epgUrl
                    withContext(Dispatchers.IO) { runCatching { ListCache.save(app, src.id, loaded, epgForCache) { s -> x?.scrub(s) ?: s } } }
                    if (cached == null) listReady(src) else { recompute(false); publishTvHome() }
                }
                // La guía se carga unos segundos después de la lista, no en paralelo: juntar ambas cargas
                // agotaba la memoria y la app se cerraba al volver a abrirla
                kotlinx.coroutines.delay(4_000)
                loadCachedEpg(src)
                refreshEpg(epgUrl)   // siempre al añadir la lista y al abrir la app
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                val why = if (e is OutOfMemoryError) "La lista es demasiado grande para la memoria de este equipo" else (e.message ?: e.toString())
                if (e is OutOfMemoryError) logEvent("Sin memoria al cargar la lista")
                if (cached != null) {
                    // Sin conexión o servidor caído: se sigue usando la lista guardada
                    message = "No se pudo actualizar la lista ($why). Se muestra la guardada."
                    loadCachedEpg(src)
                } else {
                    if (e is OutOfMemoryError) { allItems = emptyList(); items = emptyList(); epg = Epg.EMPTY }
                    message = if (e is OutOfMemoryError) "$why. Activa el modo liviano o usa una lista más pequeña." else why
                }
                loading = null
            }
            // La app siguió abierta tras cargar: el inicio fue correcto (se apaga el contador del modo seguro)
            kotlinx.coroutines.delay(8_000)
            Arranque.listo(app)
        }
    }

    /** La lista ya está en memoria: secciones, enlaces de Android TV y "último canal visto". */
    private fun listReady(src: Source) {
        val kinds = sectionsAvailable
        section = if (section in kinds) section else kinds.firstOrNull() ?: Kind.LIVE
        category = CAT_ALL; query = ""
        recompute(true)
        loading = null
        publishTvHome()
        // Abierto desde la pantalla de inicio de Android TV
        pendingLink?.let { (sid, id) ->
            if (sid == src.id) { pendingLink = null; resumeLastChannel = false; playLinked(id) }
        }
        // Inicio de la app → "Último canal visto"
        if (resumeLastChannel) {
            resumeLastChannel = false
            items.firstOrNull { it.id == store.lastItemId }?.let { activate(it, items.filter { i -> i.kind == it.kind }) }
        }
    }

    /** Descarga de nuevo la lista activa (Ajustes → Listas y guía). */
    fun refreshList() {
        val src = source ?: run { message = "Abre una lista primero"; return }
        ListCache.delete(getApplication(), src.id)
        open(src)
    }

    // ---------- Cuenta Xtream: vencimiento y conexiones ----------
    var xtreamAccount by mutableStateOf<XtreamClient.Account?>(null); private set
    fun accountSummary(): String {
        val a = xtreamAccount ?: return if (source?.type == SourceType.XTREAM) "Sin datos todavía" else "Sólo en listas Xtream"
        val vence = a.expiresAt?.let { "vence el " + java.text.SimpleDateFormat("dd/MM/yyyy", localeOf("es", "CL")).format(java.util.Date(it)) } ?: "sin vencimiento"
        val con = if (a.maxConnections > 0) " · ${a.activeConnections}/${a.maxConnections} conexiones" else ""
        return (if (a.trial) "Prueba · " else "") + vence + con
    }
    private fun warnAccount(a: XtreamClient.Account?) {
        val exp = a?.expiresAt ?: return
        val days = ((exp - System.currentTimeMillis()) / 86_400_000L).toInt()
        if (days in 0..7) message = if (days == 0) "Tu cuenta IPTV vence hoy" else "Tu cuenta IPTV vence en $days ${if (days == 1) "día" else "días"}"
        else if (a.maxConnections > 0 && a.activeConnections >= a.maxConnections) message = "Tu cuenta ya usa todas sus conexiones (${a.maxConnections}): otro equipo podría cortar la señal"
    }

    /**
     * Xtream puede repetir el mismo canal en varias categorías con el mismo stream_id. Dos elementos con el mismo id
     * cierran la app en las listas de Compose ("Key was already used"), sobre todo en los temas Mosaicos y en la Guía.
     * El primero conserva su id (favoritos y "Seguir viendo" no cambian); los repetidos se numeran como en M3U.
     */
    private fun uniqueIds(list: List<Item>): List<Item> {
        val (out, dup) = list.withUniqueIds()
        if (dup > 0) logEvent("Lista con $dup elementos de id repetido (numerados)")
        return out
    }

    // ---------- guía EPG: caché en disco + actualización ----------
    private fun epgDir() = File(getApplication<Application>().filesDir, "epg").apply { mkdirs() }
    private fun epgFiles(srcId: String) = epgDir().listFiles { f -> f.name.startsWith("${srcId}_") && f.name.endsWith(".xml") }
        ?.sortedBy { it.name }.orEmpty()
    /** La guía ya leída, en formato compacto (ver Epg.save): se carga mucho más rápido que el XML. */
    private fun epgBin(srcId: String) = File(epgDir(), "$srcId.bin")
    /** Pasado este tiempo se vuelve a leer el XML aunque no haya cambiado (para correr la ventana de horas). */
    private val EPG_BIN_MAX_AGE = 12 * 3600_000L

    /** Lee los XML y guarda la versión compacta. */
    private fun parseAndSave(srcId: String, files: List<File>): Epg? {
        val e = parseFiles(files) ?: return null
        runCatching { e.save(epgBin(srcId)) }.onFailure { epgBin(srcId).delete() }
        return e
    }
    /** Al reemplazar la guía se conservan los canales pedidos uno a uno al proveedor. */
    private fun replaceEpg(e: Epg) { epg = if (epg.extra.isEmpty()) e else Epg(e.byId, e.nameToId, epg.extra) }

    private fun parseFiles(files: List<File>): Epg? {
        val parsed = files.mapNotNull { f -> runCatching { f.inputStream().use { EpgParser.parse(it) } }.getOrNull() }
        return if (parsed.isEmpty()) null else runCatching { Epg.merge(parsed) }.getOrNull()
    }

    private suspend fun loadCachedEpg(src: Source) {
        val files = epgFiles(src.id)
        if (files.isEmpty()) return
        val bin = epgBin(src.id)
        val e = withContext(Dispatchers.IO) {
            (if (bin.exists() && System.currentTimeMillis() - bin.lastModified() < EPG_BIN_MAX_AGE) Epg.load(bin) else null)
                ?: parseAndSave(src.id, files)
        } ?: return
        if (source?.id != src.id || epgAt > 0) return     // ya llegó una más nueva
        replaceEpg(e)
        epgAt = files.maxOf { it.lastModified() }
        epgStatus = "Guía guardada del ${java.text.SimpleDateFormat("dd/MM HH:mm", I18n.locale).format(java.util.Date(epgAt))} · actualizando…"
    }

    // ---------- Revisar canales caídos ----------
    /** Canales en vivo que no respondieron en la última revisión (y cuándo fue). */
    var deadIds by mutableStateOf<Set<String>>(emptySet()); private set
    var deadAt by mutableStateOf(0L); private set
    var scanStatus by mutableStateOf(""); private set
    private var scanJob: Job? = null
    val scanning get() = scanJob?.isActive == true

    /**
     * Revisa todos los canales en vivo de la lista, de a uno y sólo mientras no se está viendo nada (para no quitarle
     * la conexión al reproductor). Se puede salir de Ajustes: sigue en segundo plano mientras la app esté abierta.
     */
    fun scanChannels() {
        if (scanning) { scanJob?.cancel(); scanStatus = "Revisión detenida"; return }
        val src = source ?: return
        val live = allItems.filter { it.kind == Kind.LIVE }.distinctBy { it.url }
        if (live.isEmpty()) { scanStatus = "Esta lista no tiene canales en vivo"; return }
        val app = getApplication<Application>()
        scanJob = viewModelScope.launch {
            val failed = LinkedHashSet<Item>()
            suspend fun waitIdle() { while (screen == Screen.PLAYER || multiview != null) { scanStatus = "En pausa mientras ves un canal…"; kotlinx.coroutines.delay(3_000) } }
            live.forEachIndexed { i, ch ->
                waitIdle()
                scanStatus = "Revisando ${i + 1} de ${live.size} · ${failed.size} sin señal"
                if (!ChannelCheck.alive(ch)) failed += ch
                kotlinx.coroutines.delay(250)
            }
            // Segunda oportunidad para los que fallaron (un corte momentáneo no lo marca como caído)
            val dead = HashSet<String>()
            failed.forEachIndexed { i, ch ->
                waitIdle()
                scanStatus = "Confirmando ${i + 1} de ${failed.size}…"
                if (!ChannelCheck.alive(ch)) dead += ch.id
                kotlinx.coroutines.delay(500)
            }
            // Mismo enlace en varias categorías: todos los elementos con esa dirección
            val deadUrls = live.filter { it.id in dead }.map { it.url }.toSet()
            val ids = allItems.filter { it.kind == Kind.LIVE && it.url in deadUrls }.map { it.id }.toSet()
            if (source?.id != src.id) return@launch
            deadIds = ids; deadAt = System.currentTimeMillis()
            ChannelCheck.save(app, src.id, ids, deadAt)
            scanStatus = "Listo: ${ids.size} de ${live.size} canales sin señal"
            logEvent("Revisión de canales: ${ids.size} de ${live.size} sin señal")
            message = scanStatus
            applyParental()
        }
    }
    fun clearDead() {
        val src = source ?: return
        ChannelCheck.clear(getApplication(), src.id); deadIds = emptySet(); deadAt = 0; scanStatus = ""
        applyParental(); message = "Marcas de canales caídos borradas"
    }

    // ---------- Guía canal por canal (Xtream) ----------
    // Canales que no salen en el XMLTV (o listas sin XMLTV): se pide su programación directo al proveedor, de a pocos
    private val guideTried = HashSet<String>()
    private val guideLimit = kotlinx.coroutines.sync.Semaphore(3)
    fun ensureGuide(item: Item) {
        val x = xtream ?: return
        if (item.kind != Kind.LIVE || item.id in guideTried || epg.has(item)) return
        val sid = Regex("/live/[^/]+/[^/]+/(\\d+)").find(item.url)?.groupValues?.get(1) ?: return
        guideTried += item.id
        val srcId = source?.id
        viewModelScope.launch {
            val list = guideLimit.withPermit { runCatching { x.shortEpg(sid) }.getOrDefault(emptyList()) }
            if (list.isNotEmpty() && source?.id == srcId) epg = epg.withExtra(item.id, list)
        }
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
            // Cada guía se baja sólo si cambió (y con reintentos); las que no cambiaron no se vuelven a leer
            val results = list.mapIndexed { i, url ->
                async {
                    runCatching { File(epgDir(), "${src.id}_$i.xml").let { f -> f to Http.downloadIfChanged(url, f) } }
                        .onFailure { logEvent("Guía: no se pudo bajar ${url.substringBefore('?')}: ${it.message}") }.getOrNull()
                }
            }.awaitAll()
            if (source?.id != src.id) return@launch
            val ok = results.filterNotNull()
            if (ok.isEmpty()) {
                epgStatus = "No se pudo actualizar la guía (sin respuesta del servidor)." + if (epgAt > 0) " Se mantiene la última guardada." else ""
                return@launch
            }
            val files = ok.map { it.first }
            // Descartar guías viejas de URLs que ya no están en la lista
            epgFiles(src.id).filter { f -> f !in files }.forEach { it.delete(); File(it.parentFile, it.name + ".meta").delete() }
            val bin = epgBin(src.id)
            val fresh = bin.exists() && System.currentTimeMillis() - bin.lastModified() < EPG_BIN_MAX_AGE
            if (ok.none { it.second } && epg.byId.isNotEmpty() && fresh && files.size == list.size) {
                epgAt = System.currentTimeMillis()
                epgStatus = "Guía al día (sin cambios) · revisada a las ${fmtTime(epgAt)} · ${epg.channelCount} canales con programación"
                return@launch
            }
            val e = withContext(Dispatchers.IO) { parseAndSave(src.id, files) }
            if (e == null) { epgStatus = "La guía descargada no es un XMLTV válido."; return@launch }
            replaceEpg(e)
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
                        // Se copia a un archivo temporal y se reemplaza al final: si la app se cierra a mitad
                        // de la copia, la lista anterior queda intacta en vez de cortada
                        val dest = store.localM3u(src.id)
                        val tmp = File(dest.parentFile, dest.name + ".part")
                        getApplication<Application>().contentResolver.openInputStream(localUri)!!.use { input ->
                            tmp.outputStream().use { input.copyTo(it) }
                        }
                        if (!tmp.renameTo(dest)) { tmp.copyTo(dest, overwrite = true); tmp.delete() }
                    }
                    s = s.copy(localFile = true, url = "")
                }
                ListCache.delete(getApplication(), s.id)
                store.upsert(s)
                sources = store.sources()
                open(s)
            } catch (e: Exception) { message = "No se pudo guardar: ${e.message}" }
        }
    }

    fun deleteSource(src: Source) {
        store.remove(src.id)
        epgFiles(src.id).forEach { it.delete(); File(it.parentFile, it.name + ".meta").delete() }
        epgBin(src.id).delete()
        sources = store.sources()
        if (source?.id == src.id) {
            source = null; allItems = emptyList(); items = emptyList(); recompute(true)
            showWelcome = true
        }
    }

    fun updateSettings(s: Settings) {
        val old = settings
        val formatChanged = s.liveFormat != settings.liveFormat
        val pinChanged = s.parentalPin != settings.parentalPin
        settings = s
        store.saveSettings(s)
        applyLite()
        if (pinChanged) { adultUnlocked = false; applyParental() }
        if (formatChanged) source?.takeIf { it.type == SourceType.XTREAM }?.let { open(it) }
        if (s.castReceive) startCastReceiver() else stopCastReceiver()
        if (s.hideDead != old.hideDead) applyParental()
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
                CAT_ALL -> if (hiddenCats.isEmpty()) list else list.filter { it.cat !in hiddenCats }
                CAT_FAV -> list.filter { it.id in favorites }
                CAT_RECENT -> { val byId = list.associateBy { it.id }; recent.mapNotNull { byId[it] } }
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
        category == CAT_RECENT -> "Vistos recientemente"
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
        publishTvHome()
    }

    // ---------- Pantalla de inicio de Android TV ----------
    private var pendingLink: Pair<String, String>? = null
    private fun publishTvHome() {
        val src = source ?: return
        val app = getApplication<Application>()
        if (!TvHome.supported(app)) return
        val byId = items.associateBy { it.id }
        val list = (favorites.mapNotNull { byId[it] }.filter { it.kind == Kind.LIVE } + recent.mapNotNull { byId[it] })
            .distinctBy { it.id }
        viewModelScope.launch(Dispatchers.IO) { TvHome.publish(app, src.id, list) }
        // "Seguir viendo" del inicio de Google TV: películas y episodios a medias (y el último canal en vivo).
        // Con una pausa breve: el reproductor guarda la posición final justo después de cerrarse.
        viewModelScope.launch {
            kotlinx.coroutines.delay(1_500)
            if (source?.id != src.id) return@launch
            val now = items.associateBy { it.id }
            val watched = continueWatching().map { (it0, r, ep) ->
                val e = ep?.let { id -> episodes.firstOrNull { x -> x.id == id } }
                TvHome.Watched(it0, ep, e?.name, e?.season ?: 0, e?.episode ?: 0, r.pos, r.dur, r.at)
            }.toMutableList()
            recent.firstNotNullOfOrNull { now[it]?.takeIf { i -> i.kind == Kind.LIVE } }?.let { ch ->
                watched += TvHome.Watched(ch, null, null, 0, 0, 0, 0, System.currentTimeMillis() - 60_000)
            }
            withContext(Dispatchers.IO) { TvHome.publishWatchNext(app, src.id, watched) }
        }
    }
    /** Enlace vibeiptv://play?src=…&id=… (desde la fila de Vibe IPTV en el inicio de la TV). */
    fun openLink(uri: Uri) {
        if (uri.scheme != "vibeiptv" || uri.host != "play") return
        val sid = uri.getQueryParameter("src") ?: return
        val id = uri.getQueryParameter("id") ?: return
        // Episodio desde "Seguir viendo": abre la serie con el foco en ese episodio
        val ep = uri.getQueryParameter("ep")
        if (ep != null) pendingEpisode = ep
        if (source?.id == sid && items.isNotEmpty()) { playLinked(id); return }
        pendingLink = sid to id
        if (source?.id != sid) sources.firstOrNull { it.id == sid }?.let { open(it) }
    }
    private var pendingEpisode: String? = null
    private fun playLinked(id: String) {
        val it0 = items.firstOrNull { it.id == id } ?: run { message = "Ese canal ya no está en la lista"; return }
        showSettings = false; showGuide = false; multiview = null; mini = false
        val ep = pendingEpisode; pendingEpisode = null
        if (ep != null && it0.kind == Kind.SERIES) { openContinue(it0, ep); return }
        // Películas: el reproductor sigue solo desde donde quedó
        activate(it0, items.filter { it.kind == it0.kind })
    }

    // ---------- Grabación ----------
    class RecState(val item: Item, val name: String, val started: Long) { var bytes by mutableStateOf(0L) }
    var recording by mutableStateOf<RecState?>(null); private set
    private var recJob: Job? = null
    fun startRecording(item: Item) {
        if (recording != null) { message = "Ya hay una grabación en curso"; return }
        val app = getApplication<Application>()
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm", java.util.Locale.US).format(java.util.Date())
        val name = "${item.name.replace(Regex("[^\\p{L}\\p{N} ._-]"), "_").take(40).trim()}_$stamp.ts"
        val st = RecState(item, name, System.currentTimeMillis())
        recording = st
        message = "Grabando ${item.name}… Si tu cuenta admite una sola conexión, la imagen puede cortarse."
        recJob = viewModelScope.launch(Dispatchers.IO) {
            var finish: ((Long) -> Unit)? = null
            var error: String? = null
            try {
                val (out, fin) = Recorder.open(app, name); finish = fin
                out.use { o -> Recorder.record(item, o) { n -> st.bytes = n } }
            } catch (e: kotlinx.coroutines.CancellationException) { /* detenida por el usuario */ }
            catch (e: Exception) { error = e.message }
            finally {
                runCatching { finish?.invoke(st.bytes) }
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                    if (recording === st) recording = null
                    message = when {
                        st.bytes > 0 -> "Grabación guardada en Descargas/VibeIPTV: $name"
                        error != null -> "No se pudo grabar: $error"
                        else -> "Grabación cancelada"
                    }
                }
            }
        }
    }
    fun stopRecording() { recJob?.cancel(); recJob = null }

    /** Elegir un mosaico: se reproduce directo (ya no hay pantalla de ficha). */
    fun choose(item: Item) = activate(item)
    fun forgetResume(item: Item) {
        val src = source ?: return
        if (item.id in resume) { resume = resume - item.id; store.saveResume(src.id, resume) }
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
        // Desde el inicio ("Seguir viendo") no hay vista: reproducir al menos ese elemento
        val list = (fromList ?: view.filter { it.kind == item.kind }).let { l -> if (item in l) l else listOf(item) }
        store.lastItemId = item.id
        playList = list
        playIndex = list.indexOf(item).coerceAtLeast(0)
        previousIndex = null
        addRecent(item)
        screen = Screen.PLAYER
        mini = false
    }

    /** Canal visto antes del actual (botón "Canal anterior" / tecla LAST del control). */
    var previousIndex by mutableStateOf<Int?>(null); private set

    fun playAt(index: Int) {
        if (index !in playList.indices || index == playIndex) return
        previousIndex = playIndex
        playIndex = index
        playing?.let { store.lastItemId = it.id; addRecent(it) }
    }

    fun zap(delta: Int) {
        if (playList.size < 2) return
        playAt((playIndex + delta).mod(playList.size))
    }

    fun previousChannel() {
        val p = previousIndex ?: run { message = "Todavía no hay un canal anterior"; return }
        playAt(p)
    }

    /** Teclas numéricas: salta al canal con ese número (o a esa posición si la lista no trae números). */
    fun jumpToNumber(n: Int) {
        val i = playList.indexOfFirst { it.num == n }.takeIf { it >= 0 }
            ?: (n - 1).takeIf { playList.none { it.num != null } && it in playList.indices }
        if (i == null) message = "No hay canal $n" else playAt(i)
    }

    // ---------- Vistos recientemente ----------
    var recent by mutableStateOf<List<String>>(emptyList()); private set
    fun addRecent(item: Item) {
        val src = source ?: return
        if (item.id.startsWith("cu_")) return
        recent = (listOf(item.id) + recent.filter { it != item.id }).take(40)
        store.saveRecent(src.id, recent)
    }
    /** Canales en vivo vistos hace poco (sin [exclude]): botón "Recientes" del reproductor. */
    fun recentChannels(exclude: String?, n: Int = 5): List<Item> {
        val byId = items.associateBy { it.id }
        return recent.asSequence().filter { it != exclude }.mapNotNull { byId[it] }.filter { it.kind == Kind.LIVE }.take(n).toList()
    }
    /** Ir a un canal reciente: si está en la lista que se está viendo, ahí mismo (▲▼ sigue igual); si no, con su categoría. */
    fun playRecent(item: Item) {
        val i = playList.indexOfFirst { it.id == item.id }
        if (i >= 0) playAt(i) else activate(item, items.filter { it.kind == Kind.LIVE && it.group == item.group })
    }

    // ---------- Saltar intro ----------
    /** Serie a la que pertenece un episodio (para recordar dónde termina su intro). */
    private fun introKey(item: Item): String? {
        val src = source ?: return null
        val s = item.seriesId?.toString() ?: resume[item.id]?.parent?.ifBlank { null } ?: series?.id ?: item.seriesName.ifBlank { null } ?: return null
        return "${src.id}:$s"
    }
    fun introEnd(item: Item): Long = introKey(item)?.let { store.introEnd(it) } ?: 0L
    fun markIntroEnd(item: Item, pos: Long): Boolean {
        val k = introKey(item) ?: return false
        store.saveIntroEnd(k, pos); return true
    }

    // ---------- Enviar del celular a la TV ----------
    /** Nombre con que este equipo aparece en "Enviar a la TV" de los otros. */
    val deviceName: String = "Vibe IPTV · " + (android.os.Build.MODEL ?: "Android").take(40)
    private var castReceiver: cl.iptv.player.data.CastReceiver? = null
    /** Película enviada desde el celular: empieza donde iba allá. */
    private var remoteStart: Pair<String, Long>? = null
    fun startCastReceiver() {
        if (!settings.castReceive || castReceiver != null) return
        castReceiver = cl.iptv.player.data.CastReceiver(getApplication(), deviceName,
            onPlay = { item, pos, from -> playRemote(item, pos, from) },
            onPairCode = { c, from -> pairCode = c?.let { it to from } },
            onSources = { list, from -> receiveSources(list, from) },
        ).also { it.start() }
    }
    fun stopCastReceiver() { castReceiver?.stop(); castReceiver = null; pairCode = null }

    // ---------- Pasar listas desde el celular ----------
    /** En la TV: código a mostrar y quién lo pidió (null = nada). */
    var pairCode by mutableStateOf<Pair<String, String>?>(null)
    /** En el celular: ventana "Pasar mis listas a la TV". */
    var showSendSources by mutableStateOf(false)
    fun cancelPair() { castReceiver?.cancelPair(); pairCode = null }
    private fun receiveSources(list: List<Source>, from: String) {
        if (list.isEmpty()) { message = "No llegó ninguna lista válida"; return }
        val existing = store.sources()
        var first: Source? = null
        list.forEach { s ->
            // La misma lista ya guardada (mismo servidor y usuario, o mismo enlace): se actualiza en vez de duplicarla
            val same = existing.firstOrNull { e ->
                e.type == s.type && if (s.type == SourceType.XTREAM) XtreamClient.normalize(e.server) == XtreamClient.normalize(s.server) && e.username == s.username else e.url == s.url
            }
            val idTaken = same == null && existing.any { it.id == s.id }
            val saved = when { same != null -> s.copy(id = same.id); idTaken -> s.copy(id = java.util.UUID.randomUUID().toString()); else -> s }
            store.upsert(saved)
            if (first == null) first = saved
        }
        sources = store.sources()
        logEvent("Listas recibidas desde $from: ${list.size}")
        message = "Listas recibidas desde $from: ${list.size}"
        if (source == null) first?.let { open(it) }
    }
    private fun playRemote(item: Item, pos: Long, from: String) {
        // Si este equipo tiene la misma lista, se usa el canal de aquí: así se puede seguir cambiando con ▲▼
        val local = items.firstOrNull { it.id == item.id && it.url == item.url } ?: items.firstOrNull { it.url == item.url }
        val it0 = local ?: item
        remoteStart = it0.id to pos
        logEvent("Recibido desde $from: ${it0.name}")
        message = "Recibido desde $from: ${it0.name}"
        activate(it0, if (local != null) items.filter { it.kind == local.kind && it.group == local.group } else listOf(it0))
    }

    // ---------- Calidad automática ----------
    /** Medidor de velocidad de internet compartido: parte de lo medido la última vez (no de un valor fijo bajo). */
    val bandwidth: androidx.media3.exoplayer.upstream.DefaultBandwidthMeter by lazy {
        androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.Builder(getApplication())
            .apply { store.lastBitrate.takeIf { it > 0 }?.let { setInitialBitrateEstimate(it) } }.build()
    }
    fun saveBitrate() { bandwidth.bitrateEstimate.takeIf { it > 0 }?.let { store.lastBitrate = it } }

    fun recentInSection() = recent.count { id -> items.any { it.id == id && it.kind == section } }

    // ---------- Temporizador para dormir ----------
    var sleepAt by mutableStateOf(0L); private set
    fun setSleep(minutes: Int) {
        sleepAt = if (minutes <= 0) 0L else System.currentTimeMillis() + minutes * 60_000L
        message = if (minutes <= 0) "Temporizador desactivado" else "El reproductor se apagará en $minutes min"
    }

    // ---------- Registro de errores (sin usuarios ni contraseñas) ----------
    fun logEvent(text: String) {
        val line = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + "  " + redact(text)
        synchronized(log) { log.addLast(line); while (log.size > 300) log.removeFirst() }
    }
    private fun redact(s: String): String = s
        .replace(Regex("/(live|movie|series|timeshift)/[^/\\s]+/[^/\\s]+/"), "/$1/***/***/")
        .replace(Regex("(?i)(username|password|user|pass)=[^&\\s]+"), "$1=***")
        .replace(Regex("(?i)//[^/@\\s]+:[^/@\\s]+@"), "//***:***@")
    fun logReport(ctx: android.content.Context): String = buildString {
        appendLine("Vibe IPTV ${Updates.currentName(ctx)} · Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        appendLine("Equipo: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · ABI ${android.os.Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("Motor: ${settings.engine} · Formato en vivo: ${settings.liveFormat} · Lista: ${source?.type ?: "-"} · Canales: ${items.size}")
        Arranque.ultimoError(ctx)?.let {
            appendLine("--- Último cierre inesperado ---")
            appendLine(redact(it))
        }
        appendLine("---")
        synchronized(log) { log.forEach { appendLine(it) } }
        if (log.isEmpty()) appendLine("(sin eventos todavía)")
    }
    fun exportLog(uri: android.net.Uri) {
        viewModelScope.launch {
            runCatching {
                val app = getApplication<Application>()
                withContext(Dispatchers.IO) { app.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(logReport(app).toByteArray()) } }
            }.onSuccess { message = "Registro guardado: no contiene usuarios ni contraseñas" }
                .onFailure { message = "No se pudo guardar: ${it.message}" }
        }
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

    // ---------- Búfer (Ajustes → Reproducción) y búfer aprendido por canal ----------
    var bufferBoosts by mutableStateOf(store.bufferBoosts()); private set
    fun bufferLevel(item: Item): Int =
        (BufferProfile.levelOf(settings.buffer) + (bufferBoosts[item.id] ?: 0)).coerceAtMost(3)
    /** El canal se quedó cargando varias veces: subir su búfer y recordarlo. */
    fun bumpBuffer(item: Item): Boolean {
        val cur = bufferBoosts[item.id] ?: 0
        if (cur >= 2 || bufferLevel(item) >= 3) return false
        bufferBoosts = (bufferBoosts - item.id) + (item.id to cur + 1)
        store.saveBufferBoosts(bufferBoosts)
        logEvent("Búfer aumentado para ${item.name} (+${cur + 1})")
        message = "Este canal se corta seguido: aumentando el búfer"
        return true
    }
    fun forgetBufferBoosts() { bufferBoosts = emptyMap(); store.saveBufferBoosts(bufferBoosts); message = "Búfer aprendido borrado" }

    // ---------- SubDL ----------
    var subdlStatus by mutableStateOf(""); private set
    fun checkSubdl() {
        subdlStatus = "Comprobando…"
        viewModelScope.launch {
            subdlStatus = runCatching { subtitleSources.subdlAccount(settings.subdlKey) }.getOrElse { it.message ?: "error" }
        }
    }

    // ---------- Multivista ----------
    var multiview by mutableStateOf<List<Item>?>(null); private set
    fun openMultiview(item: Item) {
        val favs = playList.filter { it.id in favorites && it.id != item.id }
        val near = (1..3).map { playList[(playIndex + it).mod(playList.size)] }
        multiview = (listOf(item) + favs + near).distinctBy { it.id }.take(4)
        message = "Multivista: elige con OK qué canal suena"
    }
    fun setMultiviewSlot(i: Int, item: Item?) {
        val cur = multiview?.toMutableList() ?: return
        when {
            item == null -> if (i in cur.indices && cur.size > 1) cur.removeAt(i)
            i in cur.indices -> cur[i] = item
            cur.size < 4 -> cur.add(item)
        }
        multiview = cur
    }
    /** Sale de la multivista; con [item] lo abre en pantalla completa. */
    fun closeMultiview(item: Item?) {
        multiview = null
        if (item != null) { val i = playList.indexOfFirst { it.id == item.id }; if (i >= 0) playAt(i) }
    }

    // ---------- Subtítulos con IA (Vosk, en el equipo y sin internet) ----------
    val captioner = LiveCaptioner()
    /** Idioma del audio que se está transcribiendo (null = apagado). */
    var aiSubs by mutableStateOf<String?>(null); private set
    var aiPreparing by mutableStateOf(false); private set
    fun aiBundled(lang: String) = VoskModels.bundled(getApplication(), lang)
    fun startAiSubs(lang: String) {
        if (aiPreparing) return
        aiPreparing = true
        viewModelScope.launch {
            val app = getApplication<Application>()
            runCatching { VoskModels.prepare(app, lang) { msg -> viewModelScope.launch { message = msg } } }
                .onSuccess { dir ->
                    aiSubs = lang
                    if (settings.aiSubsLang != lang) updateSettings(settings.copy(aiSubsLang = lang))   // recordarlos para la próxima vez
                    captioner.start(dir) { err -> viewModelScope.launch { aiSubs = null; message = "Subtítulos IA: $err" } }
                    message = "Subtítulos IA en ${VoskModels.names[lang] ?: lang}: se generan en el equipo, sin internet"
                    logEvent("Subtítulos IA activados ($lang)")
                }
                .onFailure { message = "No se pudo preparar el modelo de voz: ${it.message}" }
            aiPreparing = false
        }
    }
    /** [byUser]: los apagó la persona (no sólo se cerró el reproductor): ya no se encienden solos. */
    fun stopAiSubs(byUser: Boolean = false) {
        captioner.stop(); aiSubs = null
        if (byUser && settings.aiSubsLang.isNotEmpty()) updateSettings(settings.copy(aiSubsLang = ""))
    }

    // ---------- Temas: imagen de fondo y temas del usuario (exportar / importar) ----------
    // Un nombre nuevo por imagen: así la pantalla la vuelve a cargar al cambiarla
    private fun themeFiles() = getApplication<Application>().filesDir.listFiles { f -> f.name.startsWith("tema_fondo") }.orEmpty().sortedBy { it.name }
    private fun newThemeFile(): File { themeFiles().forEach { it.delete() }; return File(getApplication<Application>().filesDir, "tema_fondo_${System.currentTimeMillis()}.jpg") }
    var themeImage by mutableStateOf(themeFiles().lastOrNull()?.absolutePath.orEmpty()); private set
    private fun reloadThemeImage() { themeImage = themeFiles().lastOrNull()?.absolutePath.orEmpty() }
    fun setThemeImage(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val app = getApplication<Application>()
                    // Se reduce a ~1920 px para no gastar memoria en TV boxes
                    val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    app.contentResolver.openInputStream(uri)!!.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
                    var sample = 1; while (maxOf(opts.outWidth, opts.outHeight) / (sample * 2) >= 1920) sample *= 2
                    val bmp = app.contentResolver.openInputStream(uri)!!.use { android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) }
                        ?: throw java.io.IOException("No es una imagen")
                    newThemeFile().outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, it) }
                }
            }.onSuccess {
                reloadThemeImage()
                if (settings.theme != "custom") updateSettings(settings.copy(theme = "custom"))
                message = "Imagen de fondo aplicada (tema Personalizado)"
            }.onFailure { message = "No se pudo usar la imagen: ${it.message}" }
        }
    }
    fun clearThemeImage() { themeFiles().forEach { it.delete() }; reloadThemeImage(); message = "Imagen de fondo quitada" }
    fun exportTheme(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val o = org.json.JSONObject().put("vibeTheme", 1)
                        .put("theme", runCatching { org.json.JSONObject(settings.customTheme.ifBlank { "{}" }) }.getOrDefault(org.json.JSONObject()))
                    themeFiles().lastOrNull()?.let { o.put("image", android.util.Base64.encodeToString(it.readBytes(), android.util.Base64.NO_WRAP)) }
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!.use { it.write(o.toString().toByteArray()) }
                }
            }.onSuccess { message = "Tema guardado" }.onFailure { message = "No se pudo guardar el tema: ${it.message}" }
        }
    }
    fun importTheme(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val o = org.json.JSONObject(getApplication<Application>().contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() })
                    if (!o.has("vibeTheme")) throw java.io.IOException("No es un tema de Vibe IPTV")
                    val img = o.optString("image").takeIf { it.isNotBlank() }
                    if (img != null) newThemeFile().writeBytes(android.util.Base64.decode(img, android.util.Base64.DEFAULT)) else themeFiles().forEach { it.delete() }
                    o.optJSONObject("theme")?.toString() ?: "{}"
                }
            }.onSuccess { t -> reloadThemeImage(); updateSettings(settings.copy(theme = "custom", customTheme = t)); message = "Tema importado" }
                .onFailure { message = "No se pudo importar: ${it.message}" }
        }
    }

    /** Sólo para las capturas automáticas de pantallas (pruebas): carga una lista sin red. */
    internal fun loadPreview(src: Source, list: List<Item>, theme: String) {
        loadJob?.cancel()
        source = src; showWelcome = false
        allItems = uniqueIds(list.map { it.withCategory() }); applyParental()
        updateSettings(settings.copy(theme = theme, lite = "off"))
        selectSection(Kind.LIVE); goHome()
    }

    // ---------- Menús de los temas Mosaicos y Ondas: dónde quedó el usuario (sobrevive al abrir el reproductor) ----------
    class ShellNav {
        var col by mutableStateOf(1)              // Ondas: columna (1 = TV en vivo)
        var level by mutableStateOf(0)            // Ondas: 0 = categorías, 1 = contenido
        val idx0 = androidx.compose.runtime.mutableStateMapOf<Int, Int>()
        var idx1 by mutableStateOf(0)
        var title by mutableStateOf("")
        var tab by mutableStateOf(0)              // Mosaicos: pestaña
        fun enter(t: String) { title = t; level = 1; idx1 = 0 }
    }
    val shell = ShellNav()

    // ---------- Mini reproductor (imagen en imagen dentro de la app, para TV y TV boxes) ----------
    /** El video sigue en una ventana chica en la esquina mientras se recorre el menú o la guía. */
    var mini by mutableStateOf(false); private set
    /** Abrir la Guía directo en "Buscar programa" (desde el reproductor). */
    var guideSearch = false
    fun openGuideSearch() { guideSearch = true; enterMini(quiet = true); showGuide = true }
    fun enterMini(quiet: Boolean = false) {
        mini = true; showSettings = false; returnFocusId = playing?.id
        if (!quiet) message = "Mini reproductor: tócalo (o Atrás en el Inicio) para volver a pantalla completa. Tamaño y posición en Ajustes → Reproducción"
    }
    fun expandMini() { mini = false; showSettings = false; showGuide = false }
    val menuVisible: Boolean get() = screen == Screen.MENU || mini

    fun closePlayer() {
        mini = false
        returnFocusId = playing?.id
        screen = Screen.MENU
        publishTvHome()   // vistos recientemente → fila de la TV
    }

    fun subtitleDir() = File(getApplication<Application>().cacheDir, "subs")

    // Al final de la clase a propósito: así todas las propiedades de arriba ya existen cuando corre.
    // (Antes estaba en medio y open() usaba "recent", declarado más abajo y todavía nulo: la app se cerraba
    // en cada inicio con una lista guardada.)
    init {
        applyLite()
        autoConfigure()
        val last = sources.firstOrNull { it.id == store.lastSourceId } ?: sources.firstOrNull()
        if (Arranque.seguro) {
            // Se cerró dos veces seguidas al abrir: no cargar la última lista ni la guía guardada
            showWelcome = true
            logEvent("Inicio en modo seguro (cierres seguidos al abrir)")
            message = "La app se cerró al abrir: se inició sin cargar la última lista. Si se repite: Ajustes → Acerca de → Enviar registro"
        } else if (last != null && settings.startup != "welcome") {
            // La lista se abre cuando la app ya está en pantalla (no durante el arranque): menos carga al iniciar
            source = last; loading = "Iniciando…"
            viewModelScope.launch {
                kotlinx.coroutines.delay(1_500)
                if (source?.id == last.id && allItems.isEmpty()) open(last)
            }
        } else showWelcome = true
        // Renovación periódica de la guía mientras la app está abierta
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(10 * 60_000L)
                val every = settings.epgEvery * 3600_000L   // 0 = sólo al abrir la app
                if (every > 0 && source != null && epgUrls.isNotBlank() && System.currentTimeMillis() - epgAt > every) refreshEpg()
            }
        }
    }
}
