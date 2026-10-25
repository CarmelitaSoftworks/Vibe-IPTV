package cl.iptv.player.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class IptvColors(
    val bg: Color, val bgLines: Color,
    val tileA: Color, val tileB: Color, val tileEdge: Color,
    val emptyA: Color, val emptyB: Color,
    val ink: Color, val inkSoft: Color,
    val accent: Color, val accentDeep: Color, val focus: Color,
    val dock: Color, val sheet: Color,
    val tileLines: Color = Color(0x59FFFFFF),
    /** Estilo del fondo: "lines" (clásico), "metro" (degradado radial gris), "wave" (degradado con ondas), "flat". */
    val bgStyle: String = "lines",
    /** Segundo color del fondo (degradados). */
    val bg2: Color = Color.Unspecified,
    /** Redondeo de mosaicos y botones (0 = esquinas rectas). */
    val radius: Float = 18f,
    /** Textos de las pestañas en minúsculas (estilo "metro"). */
    val lowercase: Boolean = false,
    /** Imagen de fondo del tema personalizado (archivo interno), o "". */
    val bgImage: String = "",
    val dark: Boolean = false,
)

val Light = IptvColors(
    bg = Color(0xFFE8ECEF), bgLines = Color(0x8CFFFFFF),
    tileA = Color(0xFFF7F8F9), tileB = Color(0xFFD9DEE2), tileEdge = Color(0xFFBFC6CB),
    emptyA = Color(0xFFEEF1F3), emptyB = Color(0xFFE1E5E8),
    ink = Color(0xFF5D666C), inkSoft = Color(0xFF8C959B),
    accent = Color(0xFF22B3DF), accentDeep = Color(0xFF138BB0), focus = Color(0xFF3FD2FF),
    dock = Color(0xFFF4F6F7), sheet = Color(0xFFFBFCFC),
)

val Dark = Light.copy(
    bg = Color(0xFF1D2226), bgLines = Color(0x08FFFFFF),
    tileA = Color(0xFF3A4248), tileB = Color(0xFF2A3035), tileEdge = Color(0xFF4B555C),
    emptyA = Color(0xFF2A3035), emptyB = Color(0xFF252A2E),
    ink = Color(0xFFD5DBDF), inkSoft = Color(0xFF8D979E),
    dock = Color(0xFF262C30), sheet = Color(0xFF23292D),
    tileLines = Color(0x06FFFFFF), accentDeep = Color(0xFF4CC3EA), dark = true,
)

/** Inspirado en los menús de consola de mosaicos planos: gris claro con degradado radial, verde intenso, esquinas rectas. */
val Metro = Light.copy(
    bg = Color(0xFFE6E6E6), bg2 = Color(0xFF8F8F8F), bgLines = Color.Transparent, bgStyle = "metro",
    tileA = Color(0xFFF5F5F5), tileB = Color(0xFFF5F5F5), tileEdge = Color(0x00000000), tileLines = Color.Transparent,
    emptyA = Color(0x33FFFFFF), emptyB = Color(0x33FFFFFF),
    ink = Color(0xFF333333), inkSoft = Color(0xFF6E6E6E),
    accent = Color(0xFF3FA535), accentDeep = Color(0xFF2B7F24), focus = Color(0xFF63D457),
    dock = Color(0xF2F0F0F0), sheet = Color(0xFFF7F7F7), radius = 2f, lowercase = true,
)

/** Inspirado en los menús de consola con fondo de ondas: violeta degradado, mosaicos translúcidos y texto blanco. */
val Wave = Dark.copy(
    bg = Color(0xFF3B3470), bg2 = Color(0xFF8E6BAE), bgLines = Color.Transparent, bgStyle = "wave",
    tileA = Color(0x38FFFFFF), tileB = Color(0x1AFFFFFF), tileEdge = Color(0x40FFFFFF), tileLines = Color.Transparent,
    emptyA = Color(0x14FFFFFF), emptyB = Color(0x0AFFFFFF),
    ink = Color(0xFFFFFFFF), inkSoft = Color(0xCCE9E4F7),
    accent = Color(0xFF9D8BFF), accentDeep = Color(0xFFD9D1FF), focus = Color(0xFFFFFFFF),
    dock = Color(0x33140F2E), sheet = Color(0xFF2C2752), radius = 16f, dark = true,
)

/** Pulso: azul profundo, fila de íconos arriba y la imagen de lo elegido de fondo. */
val Pulse = Dark.copy(
    bg = Color(0xFF0A1A3F), bg2 = Color(0xFF02050D), bgLines = Color.Transparent, bgStyle = "gradient",
    tileA = Color(0x26FFFFFF), tileB = Color(0x12FFFFFF), tileEdge = Color(0x33FFFFFF), tileLines = Color.Transparent,
    emptyA = Color(0x14FFFFFF), emptyB = Color(0x0AFFFFFF),
    ink = Color(0xFFFFFFFF), inkSoft = Color(0xCCC9D6F2),
    accent = Color(0xFF3F7FF0), accentDeep = Color(0xFF8FB2FF), focus = Color(0xFFFFFFFF),
    dock = Color(0x99040A16), sheet = Color(0xFF0F1B33), radius = 14f, dark = true,
)

/** Horizonte: gris carbón, acento verde y mosaicos de esquinas casi rectas. */
val Horizon = Dark.copy(
    bg = Color(0xFF1C1F22), bg2 = Color(0xFF0D0E10), bgLines = Color.Transparent, bgStyle = "metro",
    tileA = Color(0xFF2A2E33), tileB = Color(0xFF1E2124), tileEdge = Color(0x26FFFFFF), tileLines = Color.Transparent,
    emptyA = Color(0x14FFFFFF), emptyB = Color(0x0AFFFFFF),
    ink = Color(0xFFF2F4F5), inkSoft = Color(0xFFA9B0B6),
    accent = Color(0xFF3BAA4A), accentDeep = Color(0xFF8CE3A0), focus = Color(0xFFFFFFFF),
    dock = Color(0xCC14171A), sheet = Color(0xFF1B1F22), radius = 6f, dark = true,
)

/** Escritorio: ventana clásica de escritorio de principios de los 2000 (azul, crema y verde). Diseño propio. */
val Desk = Light.copy(
    bg = Color(0xFF2B66D6), bg2 = Color(0xFF5B95EE), bgLines = Color.Transparent, bgStyle = "flat",
    tileA = Color(0xFFFFFFFF), tileB = Color(0xFFF4F3EE), tileEdge = Color(0xFFACA899), tileLines = Color.Transparent,
    emptyA = Color(0xFFF4F3EE), emptyB = Color(0xFFECE9D8),
    ink = Color(0xFF1B1B1B), inkSoft = Color(0xFF55585C),
    accent = Color(0xFF316AC5), accentDeep = Color(0xFF1C4FA8), focus = Color(0xFF316AC5),
    dock = Color(0xFFECE9D8), sheet = Color(0xFFFFFFFF), radius = 4f,
)

val LocalIptv = staticCompositionLocalOf { Light }

/** Tema personalizado guardado en Ajustes (JSON): base, colores, esquinas, transparencia e imagen de fondo. */
fun customTheme(json: String, bgImage: String): IptvColors {
    val o = runCatching { org.json.JSONObject(json) }.getOrDefault(org.json.JSONObject())
    fun col(k: String): Color? = o.optString(k).takeIf { it.matches(Regex("#?[0-9a-fA-F]{6}")) }
        ?.let { Color(0xFF000000 or it.removePrefix("#").toLong(16)) }
    val dark = o.optString("base", "dark") == "dark"
    var c = if (dark) Dark else Light
    col("accent")?.let { a -> c = c.copy(accent = a, accentDeep = if (dark) lerp(a, Color.White, 0.35f) else lerp(a, Color.Black, 0.25f), focus = lerp(a, Color.White, 0.3f)) }
    col("bg")?.let { b -> c = c.copy(bg = b, bg2 = col("bg2") ?: lerp(b, if (dark) Color.Black else Color.White, 0.35f), bgStyle = o.optString("bgStyle", "gradient"), bgLines = Color.Transparent) }
    if (o.optBoolean("glass", false)) c = c.copy(
        tileA = (if (dark) Color.White else Color.Black).copy(alpha = 0.16f), tileB = (if (dark) Color.White else Color.Black).copy(alpha = 0.08f),
        tileEdge = Color.White.copy(alpha = 0.25f), tileLines = Color.Transparent,
        dock = c.dock.copy(alpha = 0.75f), emptyA = Color.White.copy(alpha = 0.06f), emptyB = Color.White.copy(alpha = 0.03f),
        ink = if (dark) Color.White else c.ink,
    )
    return c.copy(radius = if (o.optBoolean("square", false)) 2f else 18f, bgImage = bgImage, dark = dark)
}

private fun lerp(a: Color, b: Color, t: Float) = androidx.compose.ui.graphics.lerp(a, b, t)

/** Colores de acento elegibles en Ajustes → Apariencia: (acento, acento oscuro, foco). */
val ACCENTS = mapOf(
    "celeste" to Triple(Color(0xFF22B3DF), Color(0xFF138BB0), Color(0xFF3FD2FF)),
    "verde" to Triple(Color(0xFF2FB57A), Color(0xFF1E8A5A), Color(0xFF4FE0A0)),
    "naranja" to Triple(Color(0xFFF08A24), Color(0xFFC4660D), Color(0xFFFFB35C)),
    "rosa" to Triple(Color(0xFFE04F8A), Color(0xFFB8336A), Color(0xFFFF7FB2)),
    "violeta" to Triple(Color(0xFF7B61D6), Color(0xFF5A43B0), Color(0xFFA693FF)),
)

/** El menú es blanco por defecto; "dark" u "system" sólo si se eligen en Ajustes. */
@Composable
fun IptvTheme(theme: String = "light", accent: String = "celeste", custom: String = "", bgImage: String = "", content: @Composable () -> Unit) {
    val base = when (theme) {
        "dark" -> Dark
        "system" -> if (isSystemInDarkTheme()) Dark else Light
        "metro" -> Metro
        "wave" -> Wave
        "pulse" -> Pulse
        "horizon" -> Horizon
        "desk" -> Desk
        "custom" -> customTheme(custom, bgImage)
        else -> Light
    }
    val dark = base.dark
    // El acento elegido se aplica encima (salvo en el personalizado, que trae el suyo)
    val c = ACCENTS[accent]?.takeIf { accent != "celeste" && theme != "custom" }?.let { (a, d, f) -> base.copy(accent = a, accentDeep = d, focus = f) } ?: base
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = c.accent, onPrimary = Color.White, background = c.bg, surface = c.sheet,
        onBackground = c.ink, onSurface = c.ink, secondary = c.accentDeep,
    )
    androidx.compose.runtime.CompositionLocalProvider(LocalIptv provides c) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
