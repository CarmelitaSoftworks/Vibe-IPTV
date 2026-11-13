package cl.iptv.player.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cl.iptv.player.data.Stremio
import cl.iptv.player.ui.theme.LocalIptv

/** Varias opciones de video para lo elegido (extensiones de Stremio): cuál ver. Funciona con el control (▲▼ y OK). */
@Composable
fun StreamPickerSheet(pick: AppViewModel.StreamPick, onPick: (Stremio.Stream) -> Unit, onDismiss: () -> Unit) {
    val c = LocalIptv.current
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(100); runCatching { first.requestFocus() } }
    Sheet(pick.item.name, onDismiss, wide = true) {
        Text("Elige un video (${pick.streams.size} opciones)", color = c.inkSoft, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        pick.streams.forEachIndexed { i, st ->
            ActionButton("${st.title} — ${st.addon}", Modifier.fillMaxWidth().padding(vertical = 2.dp).then(if (i == 0) Modifier.focusRequester(first) else Modifier), ghost = i > 0) { onPick(st) }
        }
    }
}
