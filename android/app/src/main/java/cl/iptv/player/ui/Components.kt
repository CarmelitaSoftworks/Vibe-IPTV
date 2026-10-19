package cl.iptv.player.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cl.iptv.player.ui.theme.LocalIptv

/** Rayas horizontales finas (textura "metal cepillado"). */
fun Modifier.brushedLines(color: Color, step: Float = 9f) = drawBehind {
    var y = 0f
    while (y < size.height) {
        drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        y += step
    }
}

@Composable
fun Pill(
    text: String,
    active: Boolean = false,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val c = LocalIptv.current
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .clip(shape)
            .background(if (active) Brush.verticalGradient(listOf(c.accent, c.accent)) else Brush.verticalGradient(listOf(c.tileA, c.tileB)))
            .border(if (focused) 3.dp else 1.dp, if (focused) c.focus else if (active) c.accentDeep else c.tileEdge, shape)
            .clickable(interactionSource = src, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        leading?.invoke()
        Text(
            text, color = if (active) Color.White else c.ink, fontWeight = FontWeight.Bold, fontSize = 15.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun RoundButton(icon: ImageVector, description: String, modifier: Modifier = Modifier, size: Int = 76, onClick: () -> Unit) {
    val c = LocalIptv.current
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, label = "rb")
    Box(
        modifier
            .size(size.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(if (focused) 14.dp else 5.dp, CircleShape, ambientColor = c.focus, spotColor = if (focused) c.focus else Color.Black)
            .clip(CircleShape)
            .background(Brush.radialGradient(listOf(Color.White, Color(0xFFDFE5E9))))
            .border(3.dp, Color.White, CircleShape)
            .border(if (focused) 5.dp else 2.dp, if (focused) c.focus else c.accent, CircleShape)
            .clickable(interactionSource = src, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = c.accentDeep, modifier = Modifier.size((size * 0.42f).dp))
    }
}

@Composable
fun ActionButton(text: String, modifier: Modifier = Modifier, ghost: Boolean = false, danger: Boolean = false, onClick: () -> Unit) {
    val c = LocalIptv.current
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val shape = RoundedCornerShape(12.dp)
    val bg = when { danger -> Color(0xFFD9534F); ghost -> Color.Transparent; else -> c.accent }
    Box(
        modifier
            .clip(shape)
            .background(bg)
            .border(if (focused) 3.dp else 1.dp, if (focused) c.focus else if (ghost) c.tileEdge else bg, shape)
            .clickable(interactionSource = src, indication = null, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (ghost) c.ink else Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, maxLines = 1)
    }
}
