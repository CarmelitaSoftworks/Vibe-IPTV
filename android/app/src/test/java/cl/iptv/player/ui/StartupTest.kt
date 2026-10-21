package cl.iptv.player.ui

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import cl.iptv.player.data.Source
import cl.iptv.player.data.SourceType
import cl.iptv.player.data.Storage
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * Abrir la app con una lista ya guardada (el caso "después del primer uso"). Hasta la 0.1.60 la app se cerraba
 * siempre aquí: el ViewModel abría la lista dentro de su init y usaba propiedades declaradas más abajo, aún nulas.
 */
@RunWith(RobolectricTestRunner::class)
class StartupTest {
    @Test fun abreConUnaListaGuardada() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("iptv", Context.MODE_PRIVATE).edit().clear().commit()
        val st = Storage(app)
        val src = Source("lista1", "Mi lista", SourceType.M3U, localFile = true)
        st.localM3u(src.id).writeText("#EXTM3U\n#EXTINF:-1 group-title=\"A\",Canal\nhttp://x/1.ts\n")
        st.saveSources(listOf(src))
        st.lastSourceId = src.id

        val vm = AppViewModel(app)                       // antes: NullPointerException aquí
        assertEquals("lista1", vm.source?.id)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))   // la lista se abre tras el inicio
        vm.open(src)                                      // abrir de nuevo tampoco debe fallar
        shadowOf(Looper.getMainLooper()).idle()
    }
}
