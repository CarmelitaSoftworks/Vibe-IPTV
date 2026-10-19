package cl.iptv.player.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import cl.iptv.player.data.Source
import cl.iptv.player.data.SourceType
import cl.iptv.player.ui.theme.LocalIptv
import kotlinx.coroutines.delay

private enum class AddOption(val title: String, val desc: String) {
    URL("Lista M3U por enlace", "Pega la URL .m3u o .m3u8 que te dio tu proveedor"),
    FILE("Archivo M3U", "Carga una lista guardada en este dispositivo"),
    XTREAM("Xtream Codes", "Servidor, usuario y contraseña de tu cuenta"),
    SHOT("Desde una captura", "Sube una foto o captura de pantalla con los datos y se rellenan solos"),
}

private sealed interface WelcomeDialog {
    data object None : WelcomeDialog
    data object Manage : WelcomeDialog
    data class Add(val option: AddOption) : WelcomeDialog
}

/** Pantalla de inicio: elegir una lista guardada o añadir una nueva. */
@Composable
fun WelcomeScreen(vm: AppViewModel) {
    val c = LocalIptv.current
    var dialog by remember { mutableStateOf<WelcomeDialog>(WelcomeDialog.None) }
    var showProfiles by remember { mutableStateOf(false) }
    if (showProfiles) ProfilesDialog(vm) { showProfiles = false }
    val firstOption = remember { FocusRequester() }
    val currentList = remember { FocusRequester() }
    val sources = vm.sources
    val hasCurrent = sources.any { it.id == vm.source?.id }

    LaunchedEffect(Unit) {
        delay(80)
        runCatching { if (hasCurrent) currentList.requestFocus() else firstOption.requestFocus() }
    }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(c.bg).brushedLines(c.bgLines).systemBarsPadding()
    ) {
        val narrow = maxWidth < 640.dp
        Column(
            Modifier.align(Alignment.TopCenter).widthIn(max = 1276.dp).fillMaxWidth().fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = if (narrow) 16.dp else 48.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(if (narrow) 18.dp else 30.dp),
        ) {
            // Encabezado
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                AppLogo(Modifier.size(if (narrow) 52.dp else 68.dp))
                Column(Modifier.weight(1f)) {
                    Text("Vibe IPTV", color = c.ink, fontWeight = FontWeight.ExtraBold, fontSize = if (narrow) 26.sp else 38.sp)
                    Text(
                        if (sources.isEmpty()) "¿Cómo quieres agregar tu lista?" else "Abre una de tus listas o agrega otra",
                        color = c.accentDeep, fontWeight = FontWeight.Bold, fontSize = if (narrow) 15.sp else 19.sp,
                    )
                }
                Pill((if (vm.profile.kid) "🧒 " else "👤 ") + vm.profile.name) { showProfiles = true }
                if (vm.source != null && !narrow) Pill("‹ Volver al menú") { vm.showWelcome = false }
                RoundButton(Icons.Filled.Settings, "Ajustes", size = if (narrow) 48 else 58) { vm.showSettings = true }
            }

            // Opciones para añadir
            val options = AddOption.entries
            if (narrow) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    options.forEachIndexed { i, o ->
                        OptionTile(o, horizontal = true, modifier = Modifier.fillMaxWidth().height(96.dp).then(if (i == 0) Modifier.focusRequester(firstOption) else Modifier)) {
                            dialog = WelcomeDialog.Add(o)
                        }
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth().height(300.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    options.forEachIndexed { i, o ->
                        OptionTile(o, horizontal = false, modifier = Modifier.weight(1f).fillMaxHeight().then(if (i == 0) Modifier.focusRequester(firstOption) else Modifier)) {
                            dialog = WelcomeDialog.Add(o)
                        }
                    }
                }
            }

            // Listas guardadas
            if (sources.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Mis listas", color = c.ink, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                        Pill("Administrar listas") { dialog = WelcomeDialog.Manage }
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(6.dp)) {
                        items(sources, key = { it.id }) { s ->
                            val isCurrent = s.id == vm.source?.id
                            SavedCard(s, isCurrent, if (isCurrent) Modifier.focusRequester(currentList) else Modifier) {
                                if (isCurrent) vm.showWelcome = false else vm.open(s)
                            }
                        }
                    }
                }
            }
            if (vm.source != null && narrow) Pill("‹ Volver al menú") { vm.showWelcome = false }
        }
    }

    when (val d = dialog) {
        WelcomeDialog.None -> Unit
        WelcomeDialog.Manage -> SourcesDialog(vm, onDismiss = { dialog = WelcomeDialog.None })
        is WelcomeDialog.Add -> SourcesDialog(
            vm, onDismiss = { dialog = WelcomeDialog.None },
            addType = if (d.option == AddOption.XTREAM) SourceType.XTREAM else SourceType.M3U,
            autoPickFile = d.option == AddOption.FILE,
            autoPickShot = d.option == AddOption.SHOT,
        )
    }
}

@Composable
private fun OptionTile(option: AddOption, horizontal: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalIptv.current
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, label = "option")
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .softShadow(if (focused) 20.dp else 3.dp, shape, ambientColor = c.focus, spotColor = if (focused) c.focus else Color.Black)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(c.tileA, c.tileB)))
            .brushedLines(c.tileLines, 7f)
            .border(if (focused) 4.dp else 1.dp, if (focused) c.focus else c.tileEdge, shape)
            .clickable(interactionSource = src, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (horizontal) {
            Row(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                OptionIcon(option, c.accentDeep, Modifier.size(48.dp))
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(option.title, color = c.ink, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                    Text(option.desc, color = c.inkSoft, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            }
        } else {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OptionIcon(option, c.accentDeep, Modifier.size(88.dp))
                Spacer(Modifier.height(6.dp))
                Text(option.title, color = c.ink, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, textAlign = TextAlign.Center)
                Text(option.desc, color = c.inkSoft, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 260.dp))
            }
        }
    }
}

@Composable
private fun SavedCard(s: Source, current: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalIptv.current
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, label = "saved")
    val shape = RoundedCornerShape(16.dp)
    val kind = when { s.type == SourceType.XTREAM -> "XTREAM CODES"; s.localFile -> "ARCHIVO M3U"; else -> "LISTA M3U" }
    Column(
        modifier
            .width(250.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .softShadow(if (focused) 16.dp else 2.dp, shape, ambientColor = c.focus, spotColor = if (focused) c.focus else Color.Black)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(c.tileA, c.tileB)))
            .border(if (focused) 4.dp else if (current) 2.dp else 1.dp, if (focused) c.focus else if (current) c.accent else c.tileEdge, shape)
            .clickable(interactionSource = src, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(kind + if (current) " · ABIERTA" else "", color = c.accentDeep, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, letterSpacing = 0.8.sp)
        Text(s.name, color = c.ink, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            if (s.type == SourceType.XTREAM) s.server else if (s.localFile) "Guardada en este dispositivo" else s.url,
            color = c.inkSoft, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AppLogo(modifier: Modifier) {
    val c = LocalIptv.current
    Canvas(modifier.softShadow(3.dp, RoundedCornerShape(22)).clip(RoundedCornerShape(22)).background(c.tileA)) {
        val s = size.minDimension / 512f
        fun tile(x: Float, y: Float, a: Float) = drawRoundRect(c.accent.copy(alpha = a), Offset(x * s, y * s), Size(148 * s, 104 * s), CornerRadius(26 * s))
        tile(96f, 128f, 1f); tile(268f, 128f, .45f); tile(96f, 256f, .45f); tile(268f, 256f, .45f)
        drawPath(Path().apply { moveTo(152 * s, 156 * s); lineTo(152 * s, 204 * s); lineTo(192 * s, 180 * s); close() }, Color.White)
    }
}

@Composable
private fun OptionIcon(option: AddOption, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val s = size.minDimension / 48f
        val w = 2.6f * s
        val st = Stroke(width = w, cap = StrokeCap.Round)
        fun line(vararg p: Float) { for (i in 0 until p.size - 2 step 2) drawLine(color, Offset(p[i] * s, p[i + 1] * s), Offset(p[i + 2] * s, p[i + 3] * s), w, cap = StrokeCap.Round) }
        when (option) {
            AddOption.URL -> {
                // dos eslabones de cadena
                drawRoundRect(color, Offset(4 * s, 20 * s), Size(24 * s, 12 * s), CornerRadius(6 * s), style = st)
                drawRoundRect(color, Offset(20 * s, 16 * s), Size(24 * s, 12 * s), CornerRadius(6 * s), style = st)
            }
            AddOption.FILE -> {
                line(12f, 5f, 29f, 5f, 38f, 14f, 38f, 43f, 12f, 43f, 12f, 5f)
                line(29f, 5f, 29f, 14f, 38f, 14f)
                line(24f, 21f, 24f, 35f); line(18f, 29f, 24f, 35f, 30f, 29f)
            }
            AddOption.SHOT -> {
                // marco de captura con líneas de texto
                line(6f, 15f, 6f, 10f, 9f, 7f, 14f, 7f); line(34f, 7f, 39f, 7f, 42f, 10f, 42f, 15f)
                line(42f, 33f, 42f, 38f, 39f, 41f, 34f, 41f); line(14f, 41f, 9f, 41f, 6f, 38f, 6f, 33f)
                line(14f, 18f, 34f, 18f); line(14f, 24f, 28f, 24f); line(14f, 30f, 31f, 30f)
            }
            AddOption.XTREAM -> {
                drawRoundRect(color, Offset(7 * s, 7 * s), Size(34 * s, 12 * s), CornerRadius(3 * s), style = st)
                drawRoundRect(color, Offset(7 * s, 25 * s), Size(34 * s, 12 * s), CornerRadius(3 * s), style = st)
                drawCircle(color, 1.8f * s, Offset(14 * s, 13 * s)); drawCircle(color, 1.8f * s, Offset(14 * s, 31 * s))
                line(24f, 37f, 24f, 43f); line(16f, 43f, 32f, 43f)
            }
        }
    }
}
