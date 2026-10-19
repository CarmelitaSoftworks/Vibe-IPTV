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
        when {
            vm.showSettings && vm.screen == Screen.MENU -> {
                BackHandler { vm.showSettings = false }
                SettingsScreen(vm)
            }
            vm.screen == Screen.MENU -> if (vm.showWelcome || vm.source == null) {
                BackHandler(enabled = vm.source != null) { vm.showWelcome = false }
                WelcomeScreen(vm)
            } else {
                BackHandler(enabled = !vm.home || vm.series != null || vm.query.isNotBlank()) { vm.back() }
                MenuScreen(vm)
            }
            else -> PlayerScreen(vm)
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
