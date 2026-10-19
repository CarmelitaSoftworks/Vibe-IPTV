package cl.iptv.player.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cl.iptv.player.ui.theme.LocalIptv
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Pantalla de inicio con el mismo estilo del menú: fondo con líneas, mosaicos que aparecen
 * uno a uno y el primero se enciende con el color de acento y el botón de reproducir.
 */
@Composable
fun SplashScreen(onDone: () -> Unit) {
    val c = LocalIptv.current
    val done by rememberUpdatedState(onDone)
    val tiles = remember { List(6) { Animatable(0f) } }
    val lit = remember { Animatable(0f) }
    val text = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        tiles.forEachIndexed { i, a ->
            launch { delay(i * 70L); a.animateTo(1f, spring(dampingRatio = .55f, stiffness = 380f)) }
        }
        delay(520)
        lit.animateTo(1f, tween(320))
        text.animateTo(1f, tween(360))
        delay(650)
        done()
    }

    val sweep = rememberInfiniteTransition(label = "sweep")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "x")

    BoxWithConstraints(Modifier.fillMaxSize().background(c.bg).brushedLines(c.bgLines)) {
        val tileW = (maxWidth * 0.11f).coerceIn(64.dp, 150.dp)
        val tileH = tileW * 0.62f
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(verticalArrangement = Arrangement.spacedBy(tileW * 0.12f)) {
                for (row in 0..1) Row(horizontalArrangement = Arrangement.spacedBy(tileW * 0.12f)) {
                    for (col in 0..2) {
                        val i = row * 3 + col
                        val p = tiles[i].value
                        Box(
                            Modifier.size(tileW, tileH).scale(0.6f + 0.4f * p).alpha(p.coerceIn(0f, 1f))
                                .shadow(4.dp, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp))
                                .background(Brush.verticalGradient(listOf(c.tileA, c.tileB)))
                                .border(1.dp, c.tileEdge, RoundedCornerShape(18.dp))
                                .brushedLines(c.tileLines, 6f),
                        ) {
                            if (i == 0) {
                                Box(Modifier.fillMaxSize().alpha(lit.value).background(Brush.verticalGradient(listOf(c.accent, c.accentDeep))))
                                Canvas(Modifier.fillMaxSize().alpha(lit.value)) {
                                    val h = size.height * .34f
                                    val cx = size.width / 2f - h * .3f
                                    val cy = size.height / 2f
                                    drawPath(Path().apply {
                                        moveTo(cx, cy - h / 2); lineTo(cx, cy + h / 2); lineTo(cx + h * .9f, cy); close()
                                    }, Color.White)
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
            Column(Modifier.alpha(text.value).offset(y = (12 * (1 - text.value)).dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Vibe IPTV", color = c.ink, fontWeight = FontWeight.ExtraBold, fontSize = 40.sp)
                Text("Libre · Código abierto", color = c.accentDeep, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
        // Barra de carga fina, como la línea del dock
        Canvas(Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp).width(180.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.tileB)) {
            val w = size.width * .35f
            val x = (size.width + w) * sweep.value - w
            drawRoundRect(c.accent, Offset(x, 0f), androidx.compose.ui.geometry.Size(w, size.height),
                androidx.compose.ui.geometry.CornerRadius(size.height / 2))
        }
    }
}
