package cl.iptv.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cl.iptv.player.data.Item
import cl.iptv.player.data.Kind
import cl.iptv.player.ui.theme.LocalIptv
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

/** Ficha de una película o serie: carátula, año, puntuación, género, sinopsis, reparto y botones. */
@Composable
fun DetailsScreen(vm: AppViewModel, item: Item) {
    val c = LocalIptv.current
    val compact = LocalConfiguration.current.screenHeightDp < 500
    val meta = vm.detailsMeta
    val saved = vm.resumeFor(item)
    val playFocus = remember { FocusRequester() }
    val close = { vm.details = null }
    BackHandler { close() }
    LaunchedEffect(item.id) { delay(80); runCatching { playFocus.requestFocus() } }
    val listForPlay = vm.view.filter { it.kind == item.kind }.ifEmpty { listOf(item) }

    Box(Modifier.fillMaxSize().background(Color(0xFF101417))) {
        val backdrop = meta?.backdrop?.ifBlank { null } ?: meta?.poster?.ifBlank { null } ?: item.logo
        if (backdrop.isNotBlank()) AsyncImage(backdrop, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().alpha(0.35f))
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xF2101417), Color(0xB3101417), Color(0x40101417)))))

        Row(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = if (compact) 24.dp else 48.dp, vertical = if (compact) 16.dp else 32.dp)) {
            val poster = meta?.poster?.ifBlank { null } ?: item.logo
            if (poster.isNotBlank()) AsyncImage(
                poster, null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxHeight().aspectRatio(2f / 3f).shadow(12.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)),
            )
            Spacer(Modifier.width(if (compact) 20.dp else 36.dp))
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                Text(meta?.title?.ifBlank { null } ?: item.name, color = Color.White, fontWeight = FontWeight.ExtraBold,
                    fontSize = if (compact) 24.sp else 34.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = if (compact) 28.sp else 38.sp)
                Spacer(Modifier.height(6.dp))
                val line = listOfNotNull(meta?.year?.ifBlank { null }, meta?.runtime?.ifBlank { null }, meta?.rating?.ifBlank { null }?.let { "★ $it" }, meta?.genres?.ifBlank { null })
                if (line.isNotEmpty()) Text(line.joinToString("  ·  "), color = c.focus, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(Modifier.height(16.dp))
                // Botones
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (item.kind == Kind.SERIES) {
                        ActionButton("▶ Ver episodios", Modifier.focusRequester(playFocus)) { close(); if (vm.series?.id != item.id) vm.activate(item) }
                    } else if (saved != null) {
                        ActionButton("▶ Continuar desde ${fmtDur(saved.pos)}", Modifier.focusRequester(playFocus)) { close(); vm.activate(item, listForPlay) }
                        ActionButton("Desde el inicio", ghost = true) { vm.forgetResume(item); close(); vm.activate(item, listForPlay) }
                    } else {
                        ActionButton("▶ Ver", Modifier.focusRequester(playFocus)) { close(); vm.activate(item, listForPlay) }
                    }
                    val fav = item.id in vm.favorites
                    ActionButton(if (fav) "★ En favoritos" else "☆ Favorito", ghost = true) { vm.toggleFavorite(item) }
                    ActionButton("Volver", ghost = true) { close() }
                }
                Spacer(Modifier.height(18.dp))
                when {
                    vm.detailsLoading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = c.accent, modifier = Modifier.size(22.dp), strokeWidth = 3.dp)
                        Spacer(Modifier.width(10.dp)); Text("Buscando la ficha…", color = Color.White.copy(alpha = 0.7f))
                    }
                    meta == null || meta.isEmpty -> Text(
                        if (vm.settings.tmdbKey.isBlank() && vm.source?.type != cl.iptv.player.data.SourceType.XTREAM)
                            "Sin ficha. Agrega una clave gratuita de TMDB en Ajustes → Fichas para ver sinopsis y puntuación."
                        else "No se encontró la ficha de este título.",
                        color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp,
                    )
                    else -> {
                        if (meta.plot.isNotBlank()) Text(meta.plot, color = Color.White.copy(alpha = 0.92f), fontSize = 15.sp, lineHeight = 22.sp)
                        if (meta.director.isNotBlank()) { Spacer(Modifier.height(12.dp)); Label("Dirección", meta.director) }
                        if (meta.cast.isNotBlank()) { Spacer(Modifier.height(6.dp)); Label("Reparto", meta.cast) }
                        Spacer(Modifier.height(12.dp))
                        Text("Ficha: ${meta.source}", color = Color.White.copy(alpha = 0.4f), fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun Label(name: String, value: String) {
    Row {
        Text("$name: ", color = Color.White.copy(alpha = 0.6f), fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text(value, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}
