package cl.iptv.player.ui

import android.content.Context
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import org.json.JSONObject
import java.util.Locale

/**
 * Idiomas de la interfaz. La app está escrita en español; el inglés sale del diccionario
 * compartido con la versión web (assets/i18n/en.json, generado desde i18n/en.json).
 * UI languages: the app is written in Spanish; English comes from the dictionary shared with the web app.
 */
object I18n {
    /** "es" o "en". Es un estado de Compose: al cambiarlo se redibujan los textos. */
    var lang by mutableStateOf("es")
        private set

    private var exact: Map<String, String> = emptyMap()
    private var patterns: List<Pair<Regex, String>> = emptyList()
    private val cache = HashMap<String, String>()

    fun load(ctx: Context) {
        if (exact.isNotEmpty()) return
        runCatching {
            val o = JSONObject(ctx.assets.open("i18n/en.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
            val m = HashMap<String, String>()
            o.keys().forEach { k -> m[k] = o.getString(k) }
            exact = m
            patterns = m.entries.filter { "{}" in it.key }.sortedByDescending { it.key.length }.map { (k, v) ->
                val re = k.split("{}").joinToString("((?:(?! · ).)+?)") { Regex.escape(it) }
                Regex("^$re$", RegexOption.DOT_MATCHES_ALL) to v
            }
        }
    }

    /** "auto" sigue el idioma del sistema: español si es español, si no inglés. */
    fun apply(pref: String) {
        val l = when (pref) { "es", "en" -> pref; else -> if (Locale.getDefault().language == "es") "es" else "en" }
        if (l != lang) { cache.clear(); lang = l }
    }

    val locale: Locale get() = if (lang == "en") Locale.US else localeOf("es", "CL")

    private fun one(s: String): String? {
        exact[s]?.let { return it }
        for ((re, v) in patterns) {
            val m = re.matchEntire(s) ?: continue
            var i = 1
            return Regex("\\{\\}").replace(v) { val a = m.groupValues.getOrElse(i++) { "" }; exact[a] ?: a }
        }
        return null
    }

    fun t(s: String): String {
        if (lang == "es" || s.isBlank() || s.none { it.isLetter() }) return s
        cache[s]?.let { return it }
        val lead = s.takeWhile { it.isWhitespace() }
        val trail = s.takeLastWhile { it.isWhitespace() }
        val core = s.substring(lead.length, s.length - trail.length)
        val out = one(core)
            ?: (if ('\n' in core) core.split('\n').joinToString("\n") { one(it) ?: it } else null)
            ?: (if (" · " in core) core.split(" · ").joinToString(" · ") { one(it) ?: it } else null)
            ?: core
        val r = lead + out + trail
        if (cache.size > 4000) cache.clear()
        cache[s] = r
        return r
    }
}

/** Atajo para textos que no pasan por [Text] (descripciones, mensajes, etc.). */
fun tr(s: String): String = I18n.t(s)

/**
 * Reemplaza a material3.Text en esta carpeta: traduce el texto antes de dibujarlo.
 * Same parameters as material3.Text, translating the string first.
 */
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = LocalTextStyle.current,
) {
    @Suppress("UNUSED_VARIABLE") val l = I18n.lang   // se vuelve a dibujar al cambiar de idioma
    androidx.compose.material3.Text(
        text = I18n.t(text), modifier = modifier, color = color, fontSize = fontSize, fontStyle = fontStyle,
        fontWeight = fontWeight, fontFamily = fontFamily, letterSpacing = letterSpacing, textDecoration = textDecoration,
        textAlign = textAlign, lineHeight = lineHeight, overflow = overflow, softWrap = softWrap, maxLines = maxLines,
        minLines = minLines, onTextLayout = onTextLayout, style = style,
    )
}

/** Locale a partir de un código de idioma (y región). Reemplaza el constructor Locale(...) obsoleto. */
fun localeOf(lang: String, region: String = ""): java.util.Locale =
    runCatching { java.util.Locale.Builder().setLanguage(lang).setRegion(region).build() }
        .getOrElse { java.util.Locale.forLanguageTag(if (region.isEmpty()) lang else "$lang-$region") }
