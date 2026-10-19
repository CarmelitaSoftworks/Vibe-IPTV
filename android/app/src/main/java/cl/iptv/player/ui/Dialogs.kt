package cl.iptv.player.ui

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cl.iptv.player.data.Settings
import cl.iptv.player.data.Source
import cl.iptv.player.data.SourceType
import cl.iptv.player.ui.theme.LocalIptv
import kotlinx.coroutines.delay

@Composable
fun Sheet(title: String, onDismiss: () -> Unit, wide: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalIptv.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val shape = RoundedCornerShape(22.dp)
        Column(
            Modifier
                .padding(12.dp)
                .widthIn(max = if (wide) 720.dp else 560.dp)
                .fillMaxWidth()
                .clip(shape)
                .background(c.sheet)
                .border(2.dp, c.accent, shape)
                .verticalScroll(rememberScrollState())
                .padding(22.dp),
        ) {
            Text(title, color = c.accentDeep, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
fun Field(
    value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier,
    password: Boolean = false, keyboard: KeyboardType = KeyboardType.Text, onDone: (() -> Unit)? = null,
) {
    val c = LocalIptv.current
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboard, imeAction = if (onDone != null) ImeAction.Done else ImeAction.Next),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.focus, unfocusedBorderColor = c.tileEdge, focusedLabelColor = c.accentDeep),
        modifier = modifier.fillMaxWidth().padding(bottom = 8.dp),
    )
}

// ---------------- Mis listas ----------------
@Composable
fun SourcesDialog(
    vm: AppViewModel,
    onDismiss: () -> Unit,
    /** Modo "añadir" desde la pantalla de inicio: sólo el formulario, con el tipo ya elegido. */
    addType: SourceType? = null,
    autoPickFile: Boolean = false,
) {
    val c = LocalIptv.current
    val adding = addType != null
    var editing by remember { mutableStateOf<Source?>(null) }
    var type by remember { mutableStateOf(addType ?: SourceType.M3U) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var epg by remember { mutableStateOf("") }
    var fileUri by remember { mutableStateOf<Uri?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun fill(s: Source?) {
        editing = s; type = s?.type ?: SourceType.M3U; name = s?.name.orEmpty(); url = s?.url.orEmpty()
        server = s?.server.orEmpty(); user = s?.username.orEmpty(); pass = s?.password.orEmpty(); epg = s?.epgUrl.orEmpty()
        fileUri = null; error = null
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            fileUri = uri; url = ""
            if (name.isBlank()) name = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.').orEmpty()
        }
    }
    LaunchedEffect(autoPickFile) {
        if (autoPickFile) try { picker.launch(arrayOf("*/*")) } catch (e: ActivityNotFoundException) { error = "Este dispositivo no tiene selector de archivos. Usa un enlace." }
    }

    val title = when (addType) { null -> "Mis listas"; SourceType.XTREAM -> "Cuenta Xtream Codes"; SourceType.M3U -> "Lista M3U" }
    Sheet(title, onDismiss, wide = true) {
        if (!adding && vm.sources.isEmpty()) Text("Aún no tienes listas guardadas.", color = c.inkSoft)
        if (!adding) vm.sources.forEach { s ->
            val shape = RoundedCornerShape(14.dp)
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(shape).background(c.tileA)
                    .border(if (vm.source?.id == s.id) 2.dp else 1.dp, if (vm.source?.id == s.id) c.accent else c.tileEdge, shape)
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(s.name, color = c.ink, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (s.type == SourceType.XTREAM) "Xtream · ${s.server}" else if (s.localFile) "Archivo local" else s.url,
                        color = c.inkSoft, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                ActionButton("Abrir") { vm.open(s); onDismiss() }
                ActionButton("Editar", ghost = true) { fill(s) }
                ActionButton("Borrar", danger = true) { vm.deleteSource(s); if (editing?.id == s.id) fill(null) }
            }
        }

        if (!adding) {
            Spacer(Modifier.height(14.dp))
            Text(if (editing != null) "Editar lista" else "Agregar lista", color = c.ink, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("M3U / M3U8", active = type == SourceType.M3U) { type = SourceType.M3U }
                Pill("Xtream Codes", active = type == SourceType.XTREAM) { type = SourceType.XTREAM }
            }
            Spacer(Modifier.height(10.dp))
        }
        Field(name, { name = it }, "Nombre (ej: Mi proveedor)")
        if (type == SourceType.M3U) {
            Field(url, { url = it; if (it.isNotBlank()) fileUri = null }, "URL de la lista .m3u / .m3u8", keyboard = KeyboardType.Uri)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton("Cargar archivo…", ghost = true) {
                    try { picker.launch(arrayOf("*/*")) } catch (e: ActivityNotFoundException) { error = "Este dispositivo no tiene selector de archivos. Usa una URL." }
                }
                fileUri?.let { Text(it.lastPathSegment ?: "archivo", color = c.inkSoft, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            Spacer(Modifier.height(8.dp))
        } else {
            Field(server, { server = it }, "Servidor (ej: http://dominio.com:8080)", keyboard = KeyboardType.Uri)
            Field(user, { user = it }, "Usuario")
            Field(pass, { pass = it }, "Contraseña", password = true)
        }
        Field(epg, { epg = it }, "Guía EPG XMLTV (opcional, varias separadas por coma)", keyboard = KeyboardType.Uri)
        error?.let { Text(it, color = androidx.compose.ui.graphics.Color(0xFFD9534F), fontWeight = FontWeight.Bold) }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
            ActionButton("Limpiar", ghost = true) { fill(null) }
            ActionButton("Guardar y abrir") {
                val id = editing?.id ?: ("s" + System.currentTimeMillis().toString(36))
                error = when {
                    type == SourceType.M3U && url.isBlank() && fileUri == null && editing?.localFile != true -> "Indica la URL o carga un archivo"
                    type == SourceType.XTREAM && (server.isBlank() || user.isBlank()) -> "Completa servidor, usuario y contraseña"
                    else -> null
                }
                if (error == null) {
                    val src = Source(
                        id = id, type = type, epgUrl = epg.trim(),
                        name = name.trim().ifBlank { if (type == SourceType.XTREAM) server.removePrefix("http://").removePrefix("https://") else "Mi lista" },
                        url = url.trim(), server = server.trim(), username = user.trim(), password = pass,
                        localFile = type == SourceType.M3U && url.isBlank() && (fileUri != null || editing?.localFile == true),
                    )
                    vm.saveSource(src, if (type == SourceType.M3U) fileUri else null)
                    onDismiss()
                }
            }
        }
    }
}

// ---------------- Categorías ----------------
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun CategoriesDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    val cats = remember(vm.view, vm.section, vm.series) { vm.categories() }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
    Sheet(if (vm.home) "Secciones" else "Categorías", onDismiss, wide = true) {
        if (vm.home) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                cl.iptv.player.data.Kind.entries.forEachIndexed { i, k ->
                    Pill("${k.label}  ${vm.countOf(k)}", modifier = if (i == 0) Modifier.focusRequester(first) else Modifier) { vm.selectSection(k); onDismiss() }
                }
            }
            return@Sheet
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Todos", active = vm.category == CAT_ALL && vm.query.isBlank(), modifier = Modifier.focusRequester(first)) { vm.selectCategory(CAT_ALL); onDismiss() }
            Pill("★ Favoritos", active = vm.category == CAT_FAV) { vm.selectCategory(CAT_FAV); onDismiss() }
            cats.forEach { (g, n) -> Pill("$g  $n", active = vm.category == g) { vm.selectCategory(g); onDismiss() } }
        }
    }
}

// ---------------- Buscar ----------------
@Composable
fun SearchDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    var q by remember { mutableStateOf(vm.query) }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { fr.requestFocus() } }
    LaunchedEffect(q) { delay(250); vm.search(q.trim()) }
    Sheet("Buscar", onDismiss) {
        Field(q, { q = it }, "Nombre del canal, película o serie", modifier = Modifier.focusRequester(fr), onDone = onDismiss)
        Text("Busca en todas las categorías de la sección actual.", color = LocalIptv.current.inkSoft, fontSize = 13.sp)
    }
}
