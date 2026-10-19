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
    // Rápido
    val showNumbers: Boolean = true,
    val showEpgInfo: Boolean = true,
    val autoNext: Boolean = true,
    val animations: Boolean = true,
    // Apariencia: el menú es blanco por defecto
    val lang: String = "auto",            // auto (idioma del sistema) | es | en
    val theme: String = "light",          // light | dark | system
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
