package cl.iptv.player.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke

/** Íconos de línea propios (dibujados, sin imágenes de terceros) para los temas Mosaicos y Ondas. */
enum class TIcon { TV, MOVIE, SERIES, GUIDE, SETTINGS, LISTS, STAR, CLOCK, PLAY, SEARCH, FOLDER, PROFILE, UPDATE, CATEGORIES }

@Composable
fun TIconView(icon: TIcon, color: Color, modifier: Modifier, stroke: Float = 1.8f) {
    Canvas(modifier) {
        val s = size.minDimension / 24f
        val st = Stroke(width = stroke * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(x: Float, y: Float) = Offset(x * s, y * s)
        fun line(vararg v: Float) { for (i in 0 until v.size - 2 step 2) drawLine(color, p(v[i], v[i + 1]), p(v[i + 2], v[i + 3]), st.width, StrokeCap.Round) }
        fun rect(x: Float, y: Float, w: Float, h: Float, r: Float = 2f, fill: Boolean = false) =
            drawRoundRect(color, p(x, y), Size(w * s, h * s), CornerRadius(r * s), style = if (fill) androidx.compose.ui.graphics.drawscope.Fill else st)
        fun tri(fill: Boolean, vararg v: Float) {
            val path = Path().apply { moveTo(v[0] * s, v[1] * s); for (i in 2 until v.size step 2) lineTo(v[i] * s, v[i + 1] * s); close() }
            if (fill) drawPath(path, color) else drawPath(path, color, style = st)
        }
        when (icon) {
            TIcon.TV -> { rect(2.5f, 5.5f, 19f, 13f); line(8f, 21f, 16f, 21f); line(9f, 2.5f, 12f, 5.5f, 15f, 2.5f) }
            TIcon.MOVIE -> { rect(3f, 4f, 18f, 16f); line(7f, 4f, 7f, 20f); line(17f, 4f, 17f, 20f); line(3f, 9f, 7f, 9f); line(3f, 15f, 7f, 15f); line(17f, 9f, 21f, 9f); line(17f, 15f, 21f, 15f) }
            TIcon.SERIES -> { rect(2.5f, 8f, 15f, 12f); line(6f, 5f, 19f, 5f, 21f, 7f, 21f, 16f); tri(true, 8.5f, 11f, 13.5f, 14f, 8.5f, 17f) }
            TIcon.GUIDE -> { rect(3f, 4.5f, 18f, 16f); line(3f, 9f, 21f, 9f); line(8f, 2.5f, 8f, 6f); line(16f, 2.5f, 16f, 6f); line(7f, 13f, 10f, 13f); line(13f, 13f, 17f, 13f); line(7f, 16.5f, 12f, 16.5f) }
            TIcon.SETTINGS -> {
                drawCircle(color, 3.2f * s, p(12f, 12f), style = st)
                for (a in 0 until 8) {
                    val r = Math.toRadians(a * 45.0)
                    line(12f + 6.2f * Math.cos(r).toFloat(), 12f + 6.2f * Math.sin(r).toFloat(), 12f + 9f * Math.cos(r).toFloat(), 12f + 9f * Math.sin(r).toFloat())
                }
                drawCircle(color, 6.2f * s, p(12f, 12f), style = st)
            }
            TIcon.LISTS -> { line(8f, 6f, 20f, 6f); line(8f, 12f, 20f, 12f); line(8f, 18f, 20f, 18f); listOf(6f, 12f, 18f).forEach { drawCircle(color, 1.3f * s, p(4f, it)) } }
            TIcon.STAR -> tri(false, 12f, 3f, 14.6f, 8.8f, 20.8f, 9.4f, 16.1f, 13.6f, 17.5f, 19.8f, 12f, 16.6f, 6.5f, 19.8f, 7.9f, 13.6f, 3.2f, 9.4f, 9.4f, 8.8f)
            TIcon.CLOCK -> { drawCircle(color, 9f * s, p(12f, 12f), style = st); line(12f, 7f, 12f, 12f, 15.5f, 14f) }
            TIcon.PLAY -> { drawCircle(color, 9f * s, p(12f, 12f), style = st); tri(true, 10f, 8f, 16f, 12f, 10f, 16f) }
            TIcon.SEARCH -> { drawCircle(color, 6.5f * s, p(10.5f, 10.5f), style = st); line(15.5f, 15.5f, 21f, 21f) }
            TIcon.FOLDER -> tri(false, 2.5f, 6f, 9f, 6f, 11f, 8.5f, 21.5f, 8.5f, 21.5f, 19f, 2.5f, 19f)
            TIcon.PROFILE -> { drawCircle(color, 4f * s, p(12f, 8f), style = st); line(4.5f, 20.5f, 5.5f, 17f, 9f, 14.5f, 15f, 14.5f, 18.5f, 17f, 19.5f, 20.5f) }
            TIcon.UPDATE -> { drawArc(color, 300f, 290f, false, p(4f, 4f), Size(16f * s, 16f * s), style = st); tri(true, 17.5f, 2.5f, 21f, 7f, 16f, 7.5f) }
            TIcon.CATEGORIES -> { rect(3f, 3f, 7.5f, 7.5f); rect(13.5f, 3f, 7.5f, 7.5f); rect(3f, 13.5f, 7.5f, 7.5f); rect(13.5f, 13.5f, 7.5f, 7.5f) }
        }
    }
}
