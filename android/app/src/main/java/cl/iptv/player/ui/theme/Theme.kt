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
    tileLines = Color(0x06FFFFFF), accentDeep = Color(0xFF4CC3EA),
)

val LocalIptv = staticCompositionLocalOf { Light }

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
fun IptvTheme(theme: String = "light", accent: String = "celeste", content: @Composable () -> Unit) {
    val dark = when (theme) { "dark" -> true; "system" -> isSystemInDarkTheme(); else -> false }
    val base = if (dark) Dark else Light
    val c = ACCENTS[accent]?.takeIf { accent != "celeste" }?.let { (a, d, f) -> base.copy(accent = a, accentDeep = d, focus = f) } ?: base
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = c.accent, onPrimary = Color.White, background = c.bg, surface = c.sheet,
        onBackground = c.ink, onSurface = c.ink, secondary = c.accentDeep,
    )
    androidx.compose.runtime.CompositionLocalProvider(LocalIptv provides c) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
