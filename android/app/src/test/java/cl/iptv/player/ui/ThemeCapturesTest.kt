package cl.iptv.player.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import cl.iptv.player.data.Item
import cl.iptv.player.data.Kind
import cl.iptv.player.data.Source
import cl.iptv.player.data.SourceType
import cl.iptv.player.ui.theme.IptvTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Capturas de las pantallas de los temas, dibujadas como en una TV (1920×1080).
 * Sólo corren en el flujo "Capturas" de GitHub Actions (-Pcapturas=true); en las pruebas normales se saltan.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
open class ThemeCapturesTest {
    /** Sufijo de los archivos: vacío en la TV; las subclases (otros equipos) ponen el suyo. */
    protected open val dispositivo = ""

    // La regla de Compose abre una Activity; en las pruebas normales (release) no existe, así que
    // primero se comprueba si es el flujo de capturas y, si no, la prueba se salta antes de abrirla
    private val soloCapturas = org.junit.rules.TestRule { base, _ ->
        object : org.junit.runners.model.Statement() {
            override fun evaluate() {
                Assume.assumeTrue(System.getProperty("vibe.capturas") == "true")
                base.evaluate()
            }
        }
    }
    val compose = createComposeRule()
    @get:Rule val reglas: org.junit.rules.RuleChain = org.junit.rules.RuleChain.outerRule(soloCapturas).around(compose)

    @Before fun soloEnCapturas() { Assume.assumeTrue(System.getProperty("vibe.capturas") == "true") }

    private val out = "build/capturas"

    /**
     * Carátulas y logos de ejemplo dibujados aquí mismo (degradado de color con el nombre, sin marcas), para que las
     * capturas muestren los temas con imágenes como en una lista real.
     */
    private fun demoImage(name: String, w: Int, h: Int, hue: Float): String {
        val dir = java.io.File(ApplicationProvider.getApplicationContext<Application>().cacheDir, "demo").apply { mkdirs() }
        val f = java.io.File(dir, name.replace(Regex("[^A-Za-z0-9]"), "_") + ".png")
        if (!f.exists()) {
            val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            val cv = android.graphics.Canvas(bmp)
            val c1 = android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.65f, 0.75f))
            val c2 = android.graphics.Color.HSVToColor(floatArrayOf((hue + 40) % 360, 0.8f, 0.25f))
            val paint = android.graphics.Paint().apply { shader = android.graphics.LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), c1, c2, android.graphics.Shader.TileMode.CLAMP) }
            cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
            // Formas suaves de fondo y el nombre en grande
            val soft = android.graphics.Paint().apply { color = android.graphics.Color.argb(50, 255, 255, 255); isAntiAlias = true }
            cv.drawCircle(w * 0.78f, h * 0.28f, w * 0.32f, soft); cv.drawCircle(w * 0.15f, h * 0.85f, w * 0.25f, soft)
            val text = android.graphics.Paint().apply { color = android.graphics.Color.WHITE; textSize = w / 9f; isAntiAlias = true; isFakeBoldText = true }
            name.split(" ").chunked(2).map { it.joinToString(" ") }.forEachIndexed { i, line -> cv.drawText(line, w * 0.08f, h * 0.5f + i * text.textSize * 1.15f, text) }
            f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        return "file://" + f.absolutePath
    }

    private fun demo(): List<Item> {
        val cats = listOf("CHILE | NOTICIAS", "CHILE | DEPORTES", "CHILE | ENTRETENCIÓN", "INFANTIL", "CINE", "DOCUMENTALES", "MÚSICA")
        val live = (1..42).map { i -> Item("l$i", Kind.LIVE, "Canal ${listOf("Noticias", "Deportes", "Show", "Kids", "Cine", "Docu", "Música")[i % 7]} ${i}", group = cats[i % cats.size], num = i,
            logo = demoImage("Canal ${listOf("Noticias", "Deportes", "Show", "Kids", "Cine", "Docu", "Música")[i % 7]} $i", 480, 300, (i * 47f) % 360)) }
        val movies = (1..30).map { i -> Item("m$i", Kind.MOVIE, "Película de ejemplo $i (20${10 + i % 15})", group = listOf("ESTRENOS", "ACCIÓN", "COMEDIA", "DRAMA")[i % 4],
            logo = demoImage("Película $i", 400, 600, (i * 61f) % 360)) }
        val series = (1..12).map { i -> Item("s$i", Kind.SERIES, "Serie de ejemplo $i", group = listOf("SERIES LATINO", "ANIME", "DOCUSERIES")[i % 3], logo = demoImage("Serie $i", 400, 600, (i * 83f) % 360),
            episodes = (1..8).map { e -> Item("s${i}e$e", Kind.SERIES, "Episodio $e", season = 1, episode = e, seriesName = "Serie de ejemplo $i") }) }
        return live + movies + series
    }

    private fun vm(theme: String): AppViewModel {
        val vm = AppViewModel(ApplicationProvider.getApplicationContext<Application>())
        vm.loadPreview(Source("demo", "Demostración", SourceType.M3U, "demo://"), demo(), theme)
        return vm
    }

    private fun shot(name: String) {
        // Con el reloj detenido, un cambio hecho desde la prueba (p. ej. de pestaña) no se dibujaba hasta la foto
        // siguiente: se avisa del cambio y se avanzan unos cuadros antes de esperar las animaciones
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        compose.mainClock.advanceTimeBy(1500)
        // Las imágenes se leen en otro hilo: darles tiempo real y dibujar de nuevo
        repeat(6) { Thread.sleep(150); shadowOf(android.os.Looper.getMainLooper()).idle(); compose.mainClock.advanceTimeBy(100) }
        compose.onRoot().captureRoboImage("$out/$name${if (dispositivo.isEmpty()) "" else "_$dispositivo"}.png")
    }

    @Test fun mosaicos() {
        compose.mainClock.autoAdvance = false
        val vm = vm("metro")
        compose.setContent { IptvTheme("metro") { MetroScreen(vm) } }
        shot("mosaicos_1_inicio")
        compose.runOnUiThread { vm.shell.tab = 1; vm.selectSection(Kind.LIVE) }
        shot("mosaicos_2_tv")
        compose.runOnUiThread { vm.shell.tab = 2; vm.selectSection(Kind.MOVIE) }
        shot("mosaicos_3_peliculas")
        compose.runOnUiThread { vm.shell.tab = 5 }
        shot("mosaicos_4_ajustes")
    }

    @Test fun ondas() {
        compose.mainClock.autoAdvance = false
        val vm = vm("wave")
        compose.setContent { IptvTheme("wave") { WaveScreen(vm) } }
        shot("ondas_1_tv_categorias")
        compose.runOnUiThread { vm.selectCategory(CAT_ALL); vm.shell.enter("Todos") }
        shot("ondas_2_tv_canales")
        compose.runOnUiThread { vm.shell.level = 0; vm.shell.col = 2 }
        shot("ondas_3_peliculas")
        compose.runOnUiThread { vm.shell.col = 0 }
        shot("ondas_4_ajustes")
    }

    /** Los dos temas de consola: Inicio (con algunos favoritos), TV en vivo, Películas y Ajustes. */
    private fun consola(theme: String, nombre: String, screen: @androidx.compose.runtime.Composable (AppViewModel) -> Unit) {
        compose.mainClock.autoAdvance = false
        val vm = vm(theme)
        listOf("m3", "s2", "l5", "m7").forEach { id -> vm.items.firstOrNull { it.id == id }?.let { vm.toggleFavorite(it) } }
        compose.setContent { IptvTheme(theme) { screen(vm) } }
        shot("${nombre}_1_inicio")
        compose.runOnUiThread { vm.shell.tab = 1; vm.selectSection(Kind.LIVE) }
        shot("${nombre}_2_tv")
        compose.runOnUiThread { vm.shell.tab = 2; vm.selectSection(Kind.MOVIE) }
        shot("${nombre}_3_peliculas")
        compose.runOnUiThread { vm.shell.tab = 5 }
        shot("${nombre}_4_ajustes")
    }

    @Test fun pulso() = consola("pulse", "pulso") { PulseScreen(it) }

    @Test fun horizonte() = consola("horizon", "horizonte") { HorizonScreen(it) }

    @Test fun escritorio() = consola("desk", "escritorio") { DeskScreen(it) }

    @Test fun oscuro() {
        compose.mainClock.autoAdvance = false
        val vm = vm("dark")
        compose.setContent { IptvTheme("dark") { MenuScreen(vm) } }
        shot("oscuro_1_inicio")
    }

    @Test fun clasico() {
        compose.mainClock.autoAdvance = false
        val vm = vm("light")
        compose.setContent { IptvTheme("light") { MenuScreen(vm) } }
        shot("clasico_1_inicio")
    }
}

/** Las mismas capturas en otros equipos: teléfono en vertical y en horizontal, tableta y laptop. */
@Config(sdk = [34], qualifiers = "w411dp-h891dp-port-xxhdpi")
class ThemeCapturesTestTelefono : ThemeCapturesTest() { override val dispositivo = "telefono" }

@Config(sdk = [34], qualifiers = "w891dp-h411dp-land-xxhdpi")
class ThemeCapturesTestTelefonoHorizontal : ThemeCapturesTest() { override val dispositivo = "telefono_horizontal" }

@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-xhdpi")
class ThemeCapturesTestTableta : ThemeCapturesTest() { override val dispositivo = "tableta" }

@Config(sdk = [34], qualifiers = "w1366dp-h768dp-land-mdpi")
class ThemeCapturesTestLaptop : ThemeCapturesTest() { override val dispositivo = "laptop" }
