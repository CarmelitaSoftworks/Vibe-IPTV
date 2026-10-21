package cl.iptv.player.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import cl.iptv.player.ui.theme.LocalIptv
import coil.compose.rememberAsyncImagePainter

/** Fondo de las pantallas según el tema: líneas (clásico), degradado radial (metro), ondas (wave) o imagen propia. */
fun Modifier.themeBackground(): Modifier = composed {
    val c = LocalIptv.current
    // Imagen propia limitada a 1920×1080 al decodificarla: una imagen grande (p. ej. de un tema importado)
    // agotaba la memoria o superaba el máximo que puede dibujar Android y la app se cerraba al abrir
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val painter = if (c.bgImage.isNotBlank()) rememberAsyncImagePainter(
        remember(c.bgImage) {
            coil.request.ImageRequest.Builder(ctx).data(java.io.File(c.bgImage)).size(1920, 1080)
                .precision(coil.size.Precision.INEXACT).allowHardware(true).build()
        }
    ) else null
    val stars = remember { List(70) { Triple(Math.random().toFloat(), Math.random().toFloat(), (Math.random() * 1.6 + 0.4).toFloat()) } }
    drawBehind {
        when (c.bgStyle) {
            "metro" -> drawRect(Brush.radialGradient(listOf(c.bg, c.bg2), center = Offset(size.width * 0.5f, size.height * 0.35f), radius = size.maxDimension * 0.75f))
            "wave" -> {
                drawRect(Brush.linearGradient(listOf(c.bg, c.bg2), start = Offset(size.width, 0f), end = Offset(0f, size.height)))
                drawWaves(this.size, this, stars)
            }
            "gradient" -> drawRect(Brush.verticalGradient(listOf(c.bg, c.bg2)))
            "flat" -> drawRect(c.bg)
            else -> {
                drawRect(c.bg)
                if (!cl.iptv.player.data.Perf.lite && c.bgLines.alpha > 0f) { var y = 0f; while (y < size.height) { drawLine(c.bgLines, Offset(0f, y), Offset(size.width, y), 1f); y += 9f } }
            }
        }
        // Imagen propia: recortada para cubrir la pantalla y oscurecida un poco para que se lean los textos
        painter?.let { p ->
            val img = p.intrinsicSize
            if (img.width > 0 && img.height > 0) {
                val k = maxOf(size.width / img.width, size.height / img.height)
                val w = img.width * k; val h = img.height * k
                clipRect {
                    translate((size.width - w) / 2f, (size.height - h) / 2f) { with(p) { draw(Size(w, h)) } }
                }
                drawRect(if (c.dark) Color(0x66000000) else Color(0x55FFFFFF))
            }
        }
    }
}

private fun drawWaves(size: Size, scope: androidx.compose.ui.graphics.drawscope.DrawScope, stars: List<Triple<Float, Float, Float>>) = with(scope) {
    // Varias ondas suaves y translúcidas que cruzan la pantalla
    val lite = cl.iptv.player.data.Perf.lite
    val n = if (lite) 3 else 9
    for (i in 0 until n) {
        val t = i / n.toFloat()
        val path = Path().apply {
            val y0 = size.height * (0.62f + t * 0.06f)
            moveTo(0f, y0)
            cubicTo(size.width * 0.3f, y0 - size.height * (0.10f + t * 0.05f), size.width * 0.6f, y0 + size.height * (0.12f - t * 0.04f), size.width, y0 - size.height * (0.05f + t * 0.03f))
        }
        drawPath(path, Color.White.copy(alpha = 0.05f + 0.035f * (1 - t)), style = Stroke(width = if (i % 3 == 0) 26f else 3f))
    }
    if (!lite) stars.forEach { (x, y, r) -> drawCircle(Color.White.copy(alpha = 0.35f), r, Offset(x * size.width, (0.45f + y * 0.45f) * size.height)) }
}

/** Forma de mosaicos y botones según el tema (esquinas rectas en "metro"). */
@Composable
fun themeShape(default: Float): androidx.compose.ui.graphics.Shape {
    val r = LocalIptv.current.radius
    return androidx.compose.foundation.shape.RoundedCornerShape((if (r < 18f) r else default).let { androidx.compose.ui.unit.Dp(it) })
}

/** Texto de pestañas y píldoras según el tema. */
@Composable
fun themeLabel(text: String): String = if (LocalIptv.current.lowercase) text.lowercase() else text
