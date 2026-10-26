package cl.iptv.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import kotlinx.coroutines.delay

@Composable
fun App(vm: AppViewModel) {
    var splash by rememberSaveable { mutableStateOf(true) }
    Box(Modifier.fillMaxSize()) {
        // Mini reproductor: Atrás en la raíz del menú vuelve a pantalla completa
        // (va antes que los BackHandler del menú: ésos tienen prioridad cuando están activos)
        BackHandler(enabled = vm.mini && vm.multiview == null) { vm.expandMini() }
        if (vm.menuVisible && vm.multiview == null) when {
            vm.showSettings -> {
                BackHandler { vm.showSettings = false }
                SettingsScreen(vm)
            }
            vm.showSetup -> SetupWizard(vm)
            vm.showGuide && vm.source != null -> GuideScreen(vm)
            vm.showWelcome || vm.source == null -> {
                BackHandler(enabled = vm.source != null) { vm.showWelcome = false }
                WelcomeScreen(vm)
            }
            // Los temas Mosaicos y Ondas traen su propio menú (distribución y navegación propias)
            vm.settings.theme == "metro" -> MetroScreen(vm)
            vm.settings.theme == "wave" -> WaveScreen(vm)
            vm.settings.theme == "pulse" -> PulseScreen(vm)
            vm.settings.theme == "horizon" -> HorizonScreen(vm)
            vm.settings.theme == "desk" -> DeskScreen(vm)
            else -> {
                BackHandler(enabled = !vm.home || vm.series != null || vm.query.isNotBlank()) { vm.back() }
                MenuScreen(vm)
            }
        }
        // El reproductor queda en el mismo lugar del árbol al pasar a mini: el video no se corta
        if (vm.multiview != null) MultiviewScreen(vm)
        else if (vm.screen == Screen.PLAYER) {
            val cfg = androidx.compose.ui.platform.LocalConfiguration.current
            // Mini reproductor ajustable (Ajustes → Reproducción): tamaño y esquina; con el dedo se arrastra y queda
            // en la esquina más cercana. Base: teléfono 55 % del ancho en vertical, 40 % en horizontal, TV 30 %.
            val base = if (cfg.screenWidthDp < 600) 0.55f else if (cfg.smallestScreenWidthDp < 600) 0.40f else 0.30f
            val scale = when (vm.settings.miniSize) { "small" -> 0.72f; "large" -> 1.4f; else -> 1f }
            val miniW = (cfg.screenWidthDp * (base * scale).coerceAtMost(0.9f)).dp
            val corner = when (vm.settings.miniCorner) {
                "bl" -> Alignment.BottomStart; "tr" -> Alignment.TopEnd; "tl" -> Alignment.TopStart; else -> Alignment.BottomEnd
            }
            var drag by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
            val density = androidx.compose.ui.platform.LocalDensity.current
            Box(
                if (vm.mini) Modifier.align(corner).systemBarsPadding().padding(16.dp)
                    .offset { androidx.compose.ui.unit.IntOffset(drag.x.toInt(), drag.y.toInt()) }
                    .width(miniW).aspectRatio(16f / 9f)
                    .pointerInput(vm.settings.miniCorner, vm.settings.miniSize) {
                        detectDragGestures(
                            onDragEnd = {
                                // Soltar: a la esquina más cercana según dónde quedó el centro de la ventanita
                                val w = cfg.screenWidthDp * density.density; val h = cfg.screenHeightDp * density.density
                                val left = corner == Alignment.BottomStart || corner == Alignment.TopStart
                                val top = corner == Alignment.TopEnd || corner == Alignment.TopStart
                                val cx = (if (left) size.width / 2f else w - size.width / 2f) + drag.x
                                val cy = (if (top) size.height / 2f else h - size.height / 2f) + drag.y
                                val c = (if (cy < h / 2) "t" else "b") + (if (cx < w / 2) "l" else "r")
                                drag = androidx.compose.ui.geometry.Offset.Zero
                                if (c != vm.settings.miniCorner) vm.updateSettings(vm.settings.copy(miniCorner = c))
                            },
                            onDragCancel = { drag = androidx.compose.ui.geometry.Offset.Zero },
                        ) { change, d -> change.consume(); drag += d }
                    }
                    .clip(RoundedCornerShape(12.dp)).border(2.dp, androidx.compose.ui.graphics.Color.White.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                else Modifier.fillMaxSize()
            ) { PlayerScreen(vm) }
        }

        // Mensajes breves (equivalente a un toast)
        val msg = vm.message
        LaunchedEffect(msg) { if (msg != null) { delay(3500); vm.message = null } }
        AnimatedVisibility(
            msg != null, enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 130.dp, start = 16.dp, end = 16.dp),
        ) {
            Text(
                msg.orEmpty(), color = Color.White, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xF02A3238)).padding(horizontal = 18.dp, vertical = 12.dp),
            )
        }

        // Pantalla de inicio (sólo al abrir la app, no al girar la pantalla)
        AnimatedVisibility(splash, enter = fadeIn(), exit = fadeOut(tween(380))) {
            SplashScreen(onDone = { splash = false })
        }
    }
}
