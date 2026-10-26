package cl.iptv.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import cl.iptv.player.data.Cast
import cl.iptv.player.data.CastFinder
import cl.iptv.player.ui.theme.LocalIptv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pasar las listas a la TV desde el celular (Ajustes → Listas y guía): se elige la TV, la TV muestra un código de
 * 4 cifras y se escribe aquí. Así no hay que escribir usuario y contraseña con el control remoto.
 */
@Composable
fun SendSourcesSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val c = LocalIptv.current
    var targets by remember { mutableStateOf<List<Cast.Target>>(emptyList()) }
    var chosen by remember { mutableStateOf<Cast.Target?>(null) }
    var code by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val f = CastFinder(ctx, vm.deviceName) { targets = it }.also { it.start() }
        onDispose { f.stop() }
    }
    // Las listas cargadas desde un archivo del equipo no se pueden pasar (el archivo está sólo aquí)
    val sendable = vm.sources.filter { !it.localFile }
    fun name(t: Cast.Target) = t.name.removePrefix("Vibe IPTV · ")

    Sheet("Pasar mis listas a la TV", onDismiss, wide = true) {
        val t = chosen
        if (sendable.isEmpty()) {
            Text("No hay listas para pasar (las cargadas desde un archivo del equipo no se pueden enviar).", color = c.inkSoft)
            return@Sheet
        }
        if (t == null) {
            Text("Abre Vibe IPTV en la TV, conéctala a la misma red Wi-Fi y elígela:", color = c.inkSoft, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            if (targets.isEmpty()) Text("Buscando televisores con Vibe IPTV…", color = c.ink)
            targets.forEach { tv ->
                ActionButton(name(tv), Modifier.fillMaxWidth().padding(vertical = 3.dp), ghost = true) {
                    busy = true; status = "Pidiendo el código a la TV…"
                    vm.viewModelScope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { Cast.pair(tv) } }
                        busy = false
                        r.onSuccess { chosen = tv; status = null }.onFailure { status = "No se pudo conectar: ${it.message}" }
                    }
                }
            }
        } else {
            Text("La TV «${name(t)}» muestra un código de 4 cifras. Escríbelo aquí:", color = c.ink, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            Field(code, { v -> code = v.filter { it.isDigit() }.take(4) }, "Código", keyboard = KeyboardType.NumberPassword)
            Spacer(Modifier.height(6.dp))
            Text("Se enviarán ${sendable.size} ${if (sendable.size == 1) "lista" else "listas"} con sus usuarios y contraseñas, sólo por la red de tu casa.",
                color = c.inkSoft, fontSize = 13.sp)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton(if (busy) "Enviando…" else "Enviar") {
                    if (busy || code.length != 4) return@ActionButton
                    busy = true
                    vm.viewModelScope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { Cast.sendSources(t, code, sendable) } }
                        busy = false
                        r.onSuccess { n -> vm.message = "Listo: ${name(t)} recibió $n ${if (n == 1) "lista" else "listas"}"; onDismiss() }
                            .onFailure { status = it.message; code = "" }
                    }
                }
                ActionButton("Otra TV", ghost = true) { chosen = null; code = ""; status = null }
            }
        }
        status?.let { Spacer(Modifier.height(8.dp)); Text(it, color = c.inkSoft, fontSize = 13.sp) }
    }
}

/** En la TV: el código que hay que escribir en el celular. */
@Composable
fun PairCodeOverlay(code: String, from: String, onCancel: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xB3000000)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.clip(RoundedCornerShape(22.dp)).background(Color(0xFF1B2126)).padding(horizontal = 40.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Recibir listas desde «$from»", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(6.dp))
            Text("Escribe este código en el celular:", color = Color.White.copy(alpha = 0.75f), fontSize = 14.sp)
            Spacer(Modifier.height(14.dp))
            Text(code.toCharArray().joinToString(" "), color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 56.sp)
            Spacer(Modifier.height(16.dp))
            ActionButton("Cancelar", ghost = true, onClick = onCancel)
        }
    }
}
