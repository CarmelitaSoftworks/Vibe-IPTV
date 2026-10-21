package cl.iptv.player.ui

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.test.core.app.ApplicationProvider
import cl.iptv.player.data.Item
import cl.iptv.player.data.Kind
import cl.iptv.player.data.Source
import cl.iptv.player.data.SourceType
import cl.iptv.player.ui.theme.IptvTheme
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Abre cada tema con una lista grande y llena de ids repetidos (como algunas listas Xtream) y recorre sus secciones.
 * Si una pantalla se cae (por ejemplo "Key was already used" en una lista de Compose), la prueba falla.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
open class ThemeSmokeTest {

    private fun lista(): List<Item> {
        val live = (1..1500).map { i -> Item("l${i % 600}", Kind.LIVE, "Canal $i", group = "PAÍS | GRUPO ${i % 25}", num = i, tvgId = "c${i % 600}") }
        val movies = (1..800).map { i -> Item("v${i % 300}", Kind.MOVIE, "Película $i", group = "CINE ${i % 10}") }
        val series = (1..120).map { i -> Item("s${i % 50}", Kind.SERIES, "Serie $i", group = "SERIES ${i % 6}",
            episodes = (1..6).map { e -> Item("s${i}e$e", Kind.SERIES, "Episodio $e", season = 1, episode = e, seriesName = "Serie $i") }) }
        return live + movies + series
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun abrir(theme: String, ligero: Boolean = false, recorrer: (AppViewModel) -> Unit = {}, screen: @Composable (AppViewModel) -> Unit) {
        val vm = AppViewModel(ApplicationProvider.getApplicationContext<Application>())
        vm.loadPreview(Source("demo", "Demostración", SourceType.M3U, "demo://"), lista(), theme)
        cl.iptv.player.data.Perf.lite = ligero
        assertEquals("ids únicos", vm.items.size, vm.items.map { it.id }.toSet().size)
        val act = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        act.setContent { IptvTheme(theme) { screen(vm) } }
        idle()
        recorrer(vm); idle()
        for (k in Kind.entries) { vm.selectSection(k); idle(); vm.selectCategory(CAT_ALL); idle() }
        vm.selectCategory(CAT_FAV); idle()
        vm.selectCategory(CAT_RECENT); idle()
        vm.goHome(); idle()
    }

    @Test fun clasico() = abrir("light") { MenuScreen(it) }

    @Test fun oscuro() = abrir("dark") { MenuScreen(it) }

    @Test fun mosaicos() = abrir("metro", recorrer = { vm -> for (t in 0..5) { vm.shell.tab = t; idle() } }) { MetroScreen(it) }

    // Modo liviano: la animación infinita de las ondas no deja que el reloj simulado de Robolectric termine
    // (en un equipo real no pasa nada; sólo cuelga la prueba)
    @Test fun ondas() = abrir("wave", ligero = true, recorrer = { vm -> for (c in 0..5) { vm.shell.col = c; vm.shell.level = 0; idle() } }) { WaveScreen(it) }

    @Test fun guia() = abrir("light") { GuideScreen(it) }

    @Test fun pulso() = abrir("pulse", recorrer = { vm -> for (t in 0..5) { vm.shell.tab = t; idle() } }) { PulseScreen(it) }

    @Test fun horizonte() = abrir("horizon", recorrer = { vm -> for (t in 0..5) { vm.shell.tab = t; idle() } }) { HorizonScreen(it) }

    @Test fun escritorio() = abrir("desk", recorrer = { vm -> for (t in 0..5) { vm.shell.tab = t; idle() } }) { DeskScreen(it) }

    @Test fun asistenteDePrimerInicio() = abrir("light") { SetupWizard(it) }
}

/** Las mismas pruebas en un teléfono en horizontal (pantalla baja): ahí es donde más fácil algo no cabe o no se desplaza. */
@Config(sdk = [34], qualifiers = "w780dp-h360dp-land-xxhdpi")
class ThemeSmokePhoneTest : ThemeSmokeTest()
