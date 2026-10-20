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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Capturas de las pantallas de los temas, dibujadas como en una TV (1920×1080).
 * Sólo corren en el flujo "Capturas" de GitHub Actions (-Pcapturas=true); en las pruebas normales se saltan.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class ThemeCapturesTest {
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

    private fun demo(): List<Item> {
        val cats = listOf("CHILE | NOTICIAS", "CHILE | DEPORTES", "CHILE | ENTRETENCIÓN", "INFANTIL", "CINE", "DOCUMENTALES", "MÚSICA")
        val live = (1..42).map { i -> Item("l$i", Kind.LIVE, "Canal ${listOf("Noticias", "Deportes", "Show", "Kids", "Cine", "Docu", "Música")[i % 7]} ${i}", group = cats[i % cats.size], num = i) }
        val movies = (1..30).map { i -> Item("m$i", Kind.MOVIE, "Película de ejemplo $i (20${10 + i % 15})", group = listOf("ESTRENOS", "ACCIÓN", "COMEDIA", "DRAMA")[i % 4]) }
        val series = (1..12).map { i -> Item("s$i", Kind.SERIES, "Serie de ejemplo $i", group = listOf("SERIES LATINO", "ANIME", "DOCUSERIES")[i % 3],
            episodes = (1..8).map { e -> Item("s${i}e$e", Kind.SERIES, "Episodio $e", season = 1, episode = e, seriesName = "Serie de ejemplo $i") }) }
        return live + movies + series
    }

    private fun vm(theme: String): AppViewModel {
        val vm = AppViewModel(ApplicationProvider.getApplicationContext<Application>())
        vm.loadPreview(Source("demo", "Demostración", SourceType.M3U, "demo://"), demo(), theme)
        return vm
    }

    private fun shot(name: String) {
        compose.mainClock.advanceTimeBy(1500)
        compose.onRoot().captureRoboImage("$out/$name.png")
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

    @Test fun clasico() {
        compose.mainClock.autoAdvance = false
        val vm = vm("light")
        compose.setContent { IptvTheme("light") { MenuScreen(vm) } }
        shot("clasico_1_inicio")
    }
}
