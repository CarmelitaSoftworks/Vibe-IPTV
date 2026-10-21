package cl.iptv.player.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cl.iptv.player.R
import cl.iptv.player.data.SourceType
import cl.iptv.player.ui.theme.LocalIptv
import kotlinx.coroutines.delay

/*
 * Asistente del primer inicio. El recorrido y el diseño están adaptados del asistente de OwnTV
 * (https://github.com/ahXN00/OwnTV, GPLv3): logo arriba a la izquierda, pasos arriba a la derecha,
 * un panel central con título, texto y tarjetas, y Atrás / Siguiente abajo.
 * Bienvenida → Antes de empezar → Tu lista (escribirla aquí, desde una captura o restaurar un respaldo).
 */

private enum class SetupStep(val label: String) { WELCOME("Bienvenida"), NOTICE("Antes de empezar"), LIST("Tu lista") }

private class SetupAction(val label: String, val onClick: () -> Unit)

@Composable
fun SetupWizard(vm: AppViewModel) {
    var step by rememberSaveable { mutableStateOf(SetupStep.WELCOME) }
    var typing by rememberSaveable { mutableStateOf(false) }      // dentro de "Tu lista": escribirla aquí
    var add by remember { mutableStateOf<AddOption?>(null) }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { vm.finishSetup(); vm.importBackup(uri) }
    }
    // Al guardar la primera lista el asistente termina y se abre la lista
    LaunchedEffect(vm.sources.size) { if (vm.sources.isNotEmpty()) vm.finishSetup() }

    when (step) {
        SetupStep.WELCOME -> SetupFrame(
            step, "Te damos la bienvenida a Vibe IPTV",
            "Tu reproductor de TV en vivo, películas y series. Configurarlo toma un par de minutos.",
            back = null, next = SetupAction("Comenzar") { step = SetupStep.NOTICE }, onBack = null,
        ) { _, _ ->
            Image(painterResource(R.drawable.logo_mark), "Vibe IPTV",
                Modifier.size(120.dp).clip(RoundedCornerShape(26.dp)).background(Color.White))
        }

        SetupStep.NOTICE -> SetupFrame(
            step, "Antes de empezar",
            "Vibe IPTV es sólo un reproductor: no incluye canales, películas ni series. Para ver contenido " +
                "necesitas una lista de tu proveedor (un enlace M3U o una cuenta Xtream Codes). " +
                "Usa sólo contenido que tengas derecho a ver.",
            back = SetupAction("Atrás") { step = SetupStep.WELCOME },
            next = SetupAction("Entiendo") { step = SetupStep.LIST },
        )

        SetupStep.LIST -> if (!typing) SetupFrame(
            step, "Agrega tu lista",
            "Elige cómo quieres agregarla. Después podrás agregar más listas desde la pantalla de inicio.",
            back = SetupAction("Saltar por ahora") { vm.finishSetup() }, next = null,
            onBack = { step = SetupStep.NOTICE },
        ) { narrow, first ->
            SetupCards(narrow, first, listOf(
                Triple("Escribirla aquí", "Enlace M3U, archivo de lista o cuenta Xtream Codes", SetupIcon.PENCIL) to { typing = true },
                Triple(AddOption.SHOT.title, AddOption.SHOT.desc, SetupIcon.SHOT) to { add = AddOption.SHOT },
                Triple("Restaurar un respaldo", "Trae tus listas y ajustes desde un respaldo de Vibe IPTV", SetupIcon.BACKUP) to
                    { restore.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*")) },
            ))
        } else SetupFrame(
            step, "Escribirla aquí", "¿Qué te dio tu proveedor?",
            back = SetupAction("Atrás") { typing = false }, next = null,
        ) { narrow, first ->
            SetupCards(narrow, first, listOf(AddOption.URL, AddOption.FILE, AddOption.XTREAM).map { o ->
                Triple(o.title, o.desc, SetupIcon.Option(o)) to { add = o }
            })
        }
    }

    add?.let { o ->
        SourcesDialog(
            vm, onDismiss = { add = null },
            addType = if (o == AddOption.XTREAM) SourceType.XTREAM else SourceType.M3U,
            autoPickFile = o == AddOption.FILE,
            autoPickShot = o == AddOption.SHOT,
        )
    }
}

/** Marco común de cada paso. [body] recibe si la pantalla es angosta y el foco inicial (si el paso tiene tarjetas). */
@Composable
private fun SetupFrame(
    step: SetupStep,
    title: String,
    sub: String?,
    back: SetupAction?,
    next: SetupAction?,
    onBack: (() -> Unit)? = back?.onClick,
    body: (@Composable ColumnScope.(narrow: Boolean, first: FocusRequester) -> Unit)? = null,
) {
    val c = LocalIptv.current
    if (onBack != null) BackHandler { onBack() }
    val first = remember(step, title) { FocusRequester() }
    val nextFocus = remember(step, title) { FocusRequester() }
    LaunchedEffect(step, title) {
        delay(80)
        runCatching { if (body != null && next == null) first.requestFocus() else nextFocus.requestFocus() }
    }
    BoxWithConstraints(Modifier.fillMaxSize().themeBackground().systemBarsPadding()) {
        val narrow = maxWidth < 640.dp
        // Logo y nombre
        Row(
            Modifier.align(Alignment.TopStart).padding(start = if (narrow) 16.dp else 40.dp, top = if (narrow) 12.dp else 28.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(painterResource(R.drawable.logo_mark), null,
                Modifier.size(if (narrow) 34.dp else 42.dp).clip(RoundedCornerShape(10.dp)).background(Color.White))
            Text("Vibe IPTV", color = c.ink, fontWeight = FontWeight.ExtraBold, fontSize = if (narrow) 18.sp else 22.sp)
        }
        StepDots(step, narrow, Modifier.align(Alignment.TopEnd).padding(end = if (narrow) 16.dp else 40.dp, top = if (narrow) 22.dp else 40.dp))

        // Panel central
        val shape = RoundedCornerShape(28.dp)
        Column(
            Modifier.align(Alignment.TopCenter)
                .padding(top = if (narrow) 64.dp else 96.dp, bottom = 20.dp, start = 16.dp, end = 16.dp)
                .widthIn(max = 900.dp).fillMaxWidth()
                .clip(shape).background(c.tileA.copy(alpha = 0.94f)).border(1.dp, c.tileEdge, shape)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = if (narrow) 20.dp else 44.dp, vertical = if (narrow) 22.dp else 36.dp),
        ) {
            Text(title, color = c.ink, fontWeight = FontWeight.ExtraBold, fontSize = if (narrow) 26.sp else 36.sp, lineHeight = if (narrow) 30.sp else 40.sp)
            if (!sub.isNullOrBlank()) Text(sub, color = c.inkSoft, fontWeight = FontWeight.SemiBold, fontSize = if (narrow) 15.sp else 18.sp,
                lineHeight = if (narrow) 21.sp else 26.sp, modifier = Modifier.padding(top = 10.dp))
            if (body != null) Column(Modifier.fillMaxWidth().padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally) { body(narrow, first) }
            if (back != null || next != null) {
                Row(Modifier.fillMaxWidth().padding(top = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (back != null) Pill("‹ " + back.label, modifier = if (next == null && body == null) Modifier.focusRequester(nextFocus) else Modifier, onClick = back.onClick)
                    Spacer(Modifier.weight(1f))
                    if (next != null) Pill(next.label + " ›", active = true, modifier = Modifier.focusRequester(nextFocus), onClick = next.onClick)
                }
            }
        }
    }
}

/** Pasos arriba a la derecha: los recorridos y el actual en color; el actual, alargado y con su nombre. */
@Composable
private fun StepDots(current: SetupStep, narrow: Boolean, modifier: Modifier) {
    val c = LocalIptv.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        SetupStep.entries.forEach { s ->
            val on = s == current
            Box(Modifier.size(width = if (on) 26.dp else 10.dp, height = 10.dp)
                .background(if (s.ordinal <= current.ordinal) c.accent else c.ink.copy(alpha = 0.18f), RoundedCornerShape(5.dp)))
            if (on && !narrow) Text(s.label, color = c.ink, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
    }
}

private sealed interface SetupIcon {
    data object PENCIL : SetupIcon
    data object BACKUP : SetupIcon
    data object SHOT : SetupIcon
    data class Option(val o: AddOption) : SetupIcon
}

/** Tarjetas lado a lado (TV y tablet) o una bajo otra (teléfono). La primera recibe el foco. */
@Composable
private fun SetupCards(narrow: Boolean, first: FocusRequester, cards: List<Pair<Triple<String, String, SetupIcon>, () -> Unit>>) {
    @Composable
    fun tile(i: Int, horizontal: Boolean, mod: Modifier) {
        val (info, onClick) = cards[i]
        val (title, desc, icon) = info
        OptionTile(title, desc, horizontal, mod.then(if (i == 0) Modifier.focusRequester(first) else Modifier),
            { col, m -> SetupIconView(icon, col, m) }, onClick)
    }
    if (narrow) cards.indices.forEach { i -> tile(i, true, Modifier.fillMaxWidth().height(96.dp)) }
    else Row(Modifier.fillMaxWidth().height(250.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        cards.indices.forEach { i -> tile(i, false, Modifier.weight(1f).height(250.dp)) }
    }
}

@Composable
private fun SetupIconView(icon: SetupIcon, color: Color, modifier: Modifier) {
    when (icon) {
        is SetupIcon.Option -> OptionIcon(icon.o, color, modifier)
        SetupIcon.SHOT -> OptionIcon(AddOption.SHOT, color, modifier)
        else -> Canvas(modifier) {
            val s = size.minDimension / 48f
            val w = 2.6f * s
            fun line(vararg p: Float) { for (i in 0 until p.size - 2 step 2) drawLine(color, Offset(p[i] * s, p[i + 1] * s), Offset(p[i + 2] * s, p[i + 3] * s), w, cap = StrokeCap.Round) }
            if (icon == SetupIcon.PENCIL) {
                // lápiz sobre una línea
                line(30f, 8f, 40f, 18f, 18f, 40f, 8f, 40f, 8f, 30f, 30f, 8f)
                line(26f, 12f, 36f, 22f)
                line(24f, 42f, 42f, 42f)
            } else {
                // caja de respaldo con flecha hacia arriba
                drawRoundRect(color, Offset(6 * s, 24 * s), Size(36 * s, 18 * s), CornerRadius(4 * s), style = Stroke(w, cap = StrokeCap.Round))
                line(24f, 32f, 24f, 6f); line(16f, 14f, 24f, 6f, 32f, 14f)
            }
        }
    }
}
