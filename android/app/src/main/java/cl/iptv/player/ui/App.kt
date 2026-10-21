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
            else -> {
                BackHandler(enabled = !vm.home || vm.series != null || vm.query.isNotBlank()) { vm.back() }
                MenuScreen(vm)
            }
        }
        // El reproductor queda en el mismo lugar del árbol al pasar a mini: el video no se corta
        if (vm.multiview != null) MultiviewScreen(vm)
        else if (vm.screen == Screen.PLAYER) {
            val cfg = androidx.compose.ui.platform.LocalConfiguration.current
            Box(
                // Teléfono: ventanita más grande (55 % del ancho en vertical) y sobre la barra de navegación
                if (vm.mini) Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 16.dp, bottom = 16.dp)
                    .width((cfg.screenWidthDp * (if (cfg.screenWidthDp < 600) 0.55f else if (cfg.smallestScreenWidthDp < 600) 0.40f else 0.30f)).dp).aspectRatio(16f / 9f)
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
