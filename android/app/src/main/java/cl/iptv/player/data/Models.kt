package cl.iptv.player.data

enum class Kind(val label: String, val one: String, val many: String) {
    LIVE("TV en vivo", "canal", "canales"),
    MOVIE("Películas", "película", "películas"),
    SERIES("Series", "serie", "series"),
}

/** Elemento reproducible (canal, película, episodio) o contenedor (serie). */
data class Item(
    val id: String,
    val kind: Kind,
    val name: String,
    val logo: String = "",
    val group: String = "Sin categoría",
    val url: String = "",
    val tvgId: String = "",
    val tvgName: String = "",
    val num: Int? = null,
    val seriesId: Int? = null,
    val season: Int = 0,
    val episode: Int = 0,
    val userAgent: String = "",
    val referrer: String = "",
    /** Nombre de la serie (en episodios), para buscar subtítulos. */
    val seriesName: String = "",
    /** Episodios de una serie que vino en una lista M3U (en Xtream se piden a la API). */
    val episodes: List<Item> = emptyList(),
    /** Categoría y subcategoría sacadas del grupo ("CHILE | DEPORTES" → CHILE / DEPORTES). */
    val cat: String = "",
    val sub: String = "",
    /** TV en diferido (catch-up): días de archivo y plantilla de URL con {start} y {dur}. */
    val archiveDays: Int = 0,
    val catchup: String = "",
    /** Zona horaria del servidor para el diferido (Xtream usa la hora local del servidor). */
    val catchupTz: String = "",
)

private val GROUP_SPLIT = Regex("\\s*(?:\\||›|»|/|::|\\s-\\s)\\s*")
private val SECTION_WORDS = Regex("^(pel[ií]culas?|movies?|vod|series?|tv( en vivo)?|en vivo|live|canales?|channels?)$", RegexOption.IGNORE_CASE)

/** Divide el nombre del grupo en categoría y subcategoría; descarta un prefijo que sólo repite la sección. */
fun splitGroup(group: String): Pair<String, String> {
    var parts = group.split(GROUP_SPLIT).map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.size > 1 && SECTION_WORDS.matches(parts[0])) parts = parts.drop(1)
    return (parts.firstOrNull() ?: "Sin categoría") to parts.drop(1).joinToString(" · ")
}

fun Item.withCategory(): Item = splitGroup(group).let { (c, s) -> copy(cat = c, sub = s) }

enum class SourceType { M3U, XTREAM }

data class Source(
    val id: String,
    val name: String,
    val type: SourceType,
    val url: String = "",
    val server: String = "",
    val username: String = "",
    val password: String = "",
    val epgUrl: String = "",
    /** true si la lista M3U se cargó desde un archivo del dispositivo (copiado a almacenamiento interno). */
    val localFile: Boolean = false,
)

data class Programme(val start: Long, val stop: Long, val title: String, val desc: String)

data class Settings(
    /** Formato de canales en vivo Xtream: "ts" (más compatible) o "m3u8". */
    val liveFormat: String = "ts",
    val osApiKey: String = "",
    val osUser: String = "",
    val osPass: String = "",
    val subLangs: String = "es,en",
    val subdlKey: String = "",
    /** Control parental: PIN de 4 dígitos ("" = desactivado). Con PIN, el contenido para adultos queda oculto. */
    val parentalPin: String = "",
    /** Fichas: mostrar la ficha antes de reproducir una película, y clave opcional de TMDB para listas M3U. */
    val showDetails: Boolean = true,
    /** Tamaño de los controles del reproductor (0.7 = 70 %, 1.5 = 150 %). */
    val hudScale: Float = 1f,
    /** Búfer: fast (cambia de canal antes) | balanced | stable (aguanta mejor los cortes). */
    val buffer: String = "balanced",
    /** Colchón en vivo: reproducir un poco más lento cuando queda poco video guardado, hasta rehacerlo. */
    val liveCushion: Boolean = true,
    /** Modo túnel (Android TV): el video y el audio van directo al chip de la TV. Apagado por defecto: depende del equipo. */
    val tunneling: Boolean = false,
    /** Subtítulos IA: idioma que quedó activado ("" = apagados). Se vuelven a encender solos al abrir el reproductor. */
    val aiSubsLang: String = "",
    /** MPEG-2 sin decodificador en el chip: "vlc" (desentrelaza, mejor imagen) | "exo" (FFmpeg en ExoPlayer, sin cambiar de motor). */
    val mpeg2Software: String = "vlc",
    /** Sonido: ecualizador (normal | voces | graves | noche) y volumen parejo entre canales y comerciales. */
    val soundEq: String = "normal",
    val soundLevel: Boolean = false,
    /** Series: saltar la intro sola cuando ya se sabe dónde termina (se aprende con "Aquí termina la intro"). */
    val autoSkipIntro: Boolean = false,
    /** Pausar y retroceder la TV en vivo: guarda en el equipo los últimos minutos del canal (0 = apagado). */
    val timeshiftMin: Int = 15,
    /** Calidad en canales con varias calidades (HLS): auto (según el internet) | saver (hasta 720p) | max. */
    val hlsQuality: String = "auto",
    /** Mini reproductor dentro de la app: tamaño (small | medium | large) y esquina (br | bl | tr | tl). */
    val miniSize: String = "medium",
    val miniCorner: String = "br",
    /** Recibir videos enviados desde el celular por Wi-Fi (Enviar a la TV). */
    val castReceive: Boolean = true,
    /** Buscar y cargar subtítulos solos en películas y series (fuentes sin clave: OpenSubtitles y Podnapisi). */
    val autoSubs: Boolean = true,
    /** Modo liviano para TV boxes modestos (Xiaomi Mi Box / Mi TV Stick): auto | on | off. */
    val lite: String = "auto",
    val tmdbKey: String = "",
    // Rápido
    val showNumbers: Boolean = true,
    val showEpgInfo: Boolean = true,
    val autoNext: Boolean = true,
    val animations: Boolean = true,
    // Apariencia: el menú es blanco por defecto
    val lang: String = "auto",            // auto (idioma del sistema) | es | en
    val theme: String = "light",          // light | dark | system | metro | wave | custom
    /** Tema personalizado (JSON: base, bg, bg2, accent, square, glass, bgStyle). */
    val customTheme: String = "",
    val accent: String = "celeste",       // celeste | verde | naranja | rosa | violeta
    val tileSize: String = "normal",      // small | normal | large
    // Guía
    val epgEvery: Int = 4,                // horas; 0 = sólo al abrir la app
    val epgOffset: Int = 0,               // horas de desfase
    // Subtítulos
    val subSize: String = "normal",       // small | normal | large | xlarge
    // Inicio de la app
    val startup: String = "home",         // home | welcome | lastChannel
    // Reproductor
    val engine: String = "auto",          // auto (ExoPlayer → VLC → MPV) | exo | vlc | mpv
    val aspect: String = "fit",           // fit | fill | stretch | zoom
)


/** Punto donde quedó una película o episodio ("Seguir viendo"). parent = id de la serie, si es un episodio. */
data class Resume(val pos: Long, val dur: Long, val at: Long, val parent: String = "") {
    val fraction: Float get() = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
}

private val ADULT = Regex("""(?i)(^|[^a-z])(xxx|adult[oa]?s?|adults?|er[oó]tic[oa]s?|porno?|hentai|playboy|\+ ?18|18 ?\+|for adults|para adultos)([^a-z]|$)""")

/** Contenido para adultos según el grupo / categoría de la lista. */
fun Item.isAdult(): Boolean = ADULT.containsMatchIn(group) || ADULT.containsMatchIn(cat) || ADULT.containsMatchIn(sub)


/** URL para ver en diferido un programa ya emitido (null si el canal no tiene archivo o ya expiró). */
fun Item.catchupUrlFor(prog: Programme, now: Long = System.currentTimeMillis()): String? {
    if (catchup.isBlank() || archiveDays <= 0) return null
    if (prog.start >= now || prog.start < now - archiveDays * 86_400_000L) return null
    val tz = runCatching { java.util.TimeZone.getTimeZone(catchupTz.ifBlank { java.util.TimeZone.getDefault().id }) }.getOrDefault(java.util.TimeZone.getDefault())
    val fmt = java.text.SimpleDateFormat("yyyy-MM-dd:HH-mm", java.util.Locale.US).apply { timeZone = tz }
    val minutes = ((minOf(prog.stop, now) - prog.start + 59_999) / 60_000).coerceAtLeast(1)
    return catchup.replace("{start}", fmt.format(java.util.Date(prog.start))).replace("{dur}", minutes.toString())
}


/** Perfil de quien ve: favoritos y "Seguir viendo" propios. Infantil = nunca muestra contenido para adultos. */
data class Profile(val id: String, val name: String, val kid: Boolean = false)
