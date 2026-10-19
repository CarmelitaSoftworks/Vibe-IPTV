package cl.iptv.player.data

import java.net.URI
import java.net.URLDecoder

/** Datos de una lista encontrados en el texto de una captura de pantalla. */
data class ShotDetails(
    val type: SourceType? = null,
    val server: String = "",
    val username: String = "",
    val password: String = "",
    val url: String = "",
    val epgUrl: String = "",
    val name: String = "",
) {
    val found: Int get() = listOf(server, username, password, url, epgUrl, name).count { it.isNotBlank() }
}

/**
 * Reconoce servidor, usuario, contraseña, enlace M3U y guía en el texto leído de una captura
 * (mensaje del proveedor, correo, panel…). Misma lógica que la versión web: web/js/ocr-parse.js
 */
object ScreenshotParser {
    private val I = RegexOption.IGNORE_CASE
    private val LABELS: List<Pair<String, Regex>> = listOf(
        "server" to Regex("""^(?:url\s+(?:del\s+)?servidor|server\s*url|xtream\s*(?:url|server)|portal(?:\s*url)?|servidor|server|host(?:name)?|dns|direcci[oó0]n|address|dominio|domain)\b""", I),
        "username" to Regex("""^(?:nombre\s+de\s+usuario|user\s*name|username|usuario|user|login|usr|id\s+de\s+usuario)\b""", I),
        "password" to Regex("""^(?:contrase\S{0,3}a|password|passw(?:or)?d|pass|clave|pwd|senha|pin)\b""", I),
        "port" to Regex("""^(?:puerto|port|porta)\b""", I),
        "m3u" to Regex("""^(?:m3u(?:8)?(?:\s*(?:url|link|enlace))?|(?:url|link|enlace)\s*(?:de\s+la\s+)?(?:m3u8?|lista|playlist)|lista(?:\s+m3u8?)?|playlist(?:\s*url)?)\b""", I),
        "epg" to Regex("""^(?:epg(?:\s*url)?|xmltv(?:\s*url)?|gu[ií]a(?:\s+epg)?|tv\s*guide|guide)\b""", I),
        "name" to Regex("""^(?:proveedor|provider|servicio|service|nombre\s+de\s+la\s+lista|playlist\s+name)\b""", I),
    )
    private val SEP = Regex("""^\s*(?:[:：=]|-{1,2}>?|–|—|→|»)?\s*""")
    private val BULLETS = Regex("""^(?:[\s"'•·*\-–—>»›◦‣⁃●○■□▪▫►▶→⇒✓✔☑✅❇✳⭐\x{1F300}-\x{1FAFF}\x{FE0F}]|\d{1,2}[.)]\s)+""")
    private val HOST = Regex("""^(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+[a-z]{2,}$|^\d{1,3}(?:\.\d{1,3}){3}$|^localhost$""", I)
    private val URL_RE = Regex("""https?://[^\s"'<>]+""", I)

    /** Arregla errores típicos de la lectura (espacios dentro de http://, ";" en vez de ":"). */
    fun clean(text: String): String = text
        .replace(Regex("[   ]"), " ")
        .replace(Regex("[“”«»]"), "\"").replace(Regex("[‘’]"), "'")
        .replace(Regex("""\bh\s*t\s*t\s*p\s*(s?)\s*[:;.,]?\s*[/\\|l]\s*[/\\|l]\s*""", I)) { "http${it.groupValues[1].lowercase()}://" }
        .replace(Regex("""(https?://\S*?)\s+([.:/])\s*(?=\w)""", I), "$1$2")
        .replace("\r", "")

    private fun strip(v: String) = v.replace(Regex("""^[\s"'`(\[{<•·*]+"""), "").replace(Regex("""[\s"'`)\]}>.,;•·*]+$"""), "")
    private fun firstToken(v: String) = strip(v).split(Regex("""\s+""")).firstOrNull().orEmpty()

    private fun uri(raw: String): URI? = runCatching { URI(raw.replace(" ", "%20")) }.getOrNull()?.takeIf { it.host != null }

    private fun query(u: URI): Map<String, String> = (u.rawQuery ?: "").split('&').mapNotNull {
        val i = it.indexOf('='); if (i <= 0) null
        else runCatching { URLDecoder.decode(it.substring(0, i), "UTF-8").lowercase() to URLDecoder.decode(it.substring(i + 1), "UTF-8") }.getOrNull()
    }.toMap()

    private fun asServer(v: String, port: String = ""): String {
        var s = firstToken(v)
        if (s.isBlank()) return ""
        if (!Regex("^https?://", I).containsMatchIn(s)) s = "http://$s"
        val u = uri(s) ?: return ""
        if (!HOST.matches(u.host)) return ""
        val p = if (u.port > 0) u.port else port.toIntOrNull()?.takeIf { port.length in 2..5 } ?: -1
        val path = (u.rawPath ?: "").replace(Regex("""/(?:player_api|get|panel_api|xmltv)\.php$""", I), "")
            .replace(Regex("""/c/?$""", I), "").trimEnd('/')
        return "${u.scheme.lowercase()}://${u.host}${if (p > 0) ":$p" else ""}$path"
    }

    fun parse(raw: String): ShotDetails {
        val text = clean(raw)
        val lines = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        var server = ""; var username = ""; var password = ""; var url = ""; var epg = ""; var name = ""; var port = ""

        // 1. Enlaces completos: get.php?username=..&password=.. trae todo
        for (m in URL_RE.findAll(text)) {
            val link = m.value.trimEnd(')', ',', '.', ';')
            val u = uri(link) ?: continue
            if (!HOST.matches(u.host)) continue
            val q = query(u)
            val user = q["username"] ?: q["user"] ?: ""
            val pass = q["password"] ?: q["pass"] ?: ""
            val path = u.rawPath ?: ""
            if (Regex("""xmltv\.php|\.xml(?:\.gz)?$|epg""", I).containsMatchIn(path)) { if (epg.isBlank()) epg = link; continue }
            if (user.isNotBlank() && pass.isNotBlank() && username.isBlank()) { username = user; password = pass; server = asServer(link) }
            if (Regex("""get\.php|\.m3u8?$|type=m3u|output=(?:ts|m3u8|hls)""", I).containsMatchIn(path + (u.rawQuery?.let { "?$it" } ?: ""))) { if (url.isBlank()) url = link }
            else if (server.isBlank()) server = asServer(link)
        }

        // 2. Campos con etiqueta: "Usuario: juan" o la etiqueta en una línea y el valor en la siguiente
        fun labelOnly(l: String) = LABELS.any { (_, re) -> re.find(l)?.let { strip(l.substring(it.value.length).replace(SEP, "")).isEmpty() } ?: false }
        for (i in lines.indices) {
            val line = lines[i].replace(BULLETS, "")
            for ((key, re) in LABELS) {
                val m = re.find(line) ?: continue
                var value = line.substring(m.value.length).replace(SEP, "").trim()
                if (strip(value).isEmpty() && i + 1 < lines.size && !labelOnly(lines[i + 1])) value = lines[i + 1]
                if (strip(value).isEmpty()) continue
                val isUrl = Regex("^https?://", I).containsMatchIn(strip(value))
                when {
                    key == "server" -> if (server.isBlank()) server = asServer(value)
                    key == "username" -> if (username.isBlank()) username = firstToken(value)
                    key == "password" -> if (password.isBlank()) password = firstToken(value)
                    key == "port" -> port = Regex("""\d{2,5}""").find(value)?.value.orEmpty()
                    key == "m3u" -> if (url.isBlank() && isUrl) url = firstToken(value)
                    key == "epg" -> if (epg.isBlank() && isUrl) epg = firstToken(value)
                    key == "name" -> if (name.isBlank()) name = strip(value).take(40)
                }
                break
            }
        }
        if (port.isNotBlank() && server.isNotBlank()) server = asServer(server, port)

        // 3. Un dominio suelto (sin etiqueta ni http) cuando ya hay usuario y contraseña
        if (server.isBlank() && username.isNotBlank() && password.isNotBlank()) {
            Regex("""\b(?:[a-z0-9-]+\.)+[a-z]{2,}(?::\d{2,5})?\b""", I).find(text)?.let { server = asServer(it.value, port) }
        }

        val type = when {
            server.isNotBlank() && username.isNotBlank() && password.isNotBlank() -> SourceType.XTREAM
            url.isNotBlank() -> SourceType.M3U
            else -> null
        }
        return ShotDetails(type, server, username, password, url, epg, name)
    }
}
