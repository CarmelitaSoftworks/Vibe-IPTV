package cl.iptv.player.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Datos guardados dañados o de otra versión: nunca deben cerrar la app al abrir. */
@RunWith(RobolectricTestRunner::class)
class StorageTest {
    private lateinit var ctx: Context
    private val prefs get() = ctx.getSharedPreferences("iptv", Context.MODE_PRIVATE)

    @Before fun limpiar() {
        ctx = ApplicationProvider.getApplicationContext()
        prefs.edit().clear().commit()
    }

    @Test fun listasDanadasSeOmiten() {
        prefs.edit().putString("sources", """[{"name":"sin id"},{"id":"a","name":"Buena","type":"XTREAM"},5,null,{"id":"b","type":"NO_EXISTE"}]""").commit()
        val s = Storage(ctx).sources()
        assertEquals(listOf("a", "b"), s.map { it.id })
        assertEquals(SourceType.M3U, s[1].type)
    }

    @Test fun jsonDeListasRoto() {
        prefs.edit().putString("sources", "[{\"id\":").commit()
        assertTrue(Storage(ctx).sources().isEmpty())
    }

    @Test fun ajusteConOtroTipoUsaLosDeFabrica() {
        prefs.edit().putInt("theme", 3).putString("epgEvery", "mucho").commit()
        assertEquals(Settings(), Storage(ctx).settings())
    }

    @Test fun clavesConOtroTipoNoFallan() {
        prefs.edit().putInt("last_source", 1).putBoolean("profile", true).putString("favs_a", "x").commit()
        val st = Storage(ctx)
        assertEquals(null, st.lastSourceId)
        assertEquals("", st.profileId)
        assertTrue(st.favorites("a").isEmpty())
    }

    @Test fun contrasenaIdaYVuelta() {
        val st = Storage(ctx)
        st.saveSources(listOf(Source("a", "Mi lista", SourceType.XTREAM, server = "http://s", username = "u", password = "clave secreta")))
        assertEquals("clave secreta", st.sources().single().password)
    }

    @Test fun respaldoLlevaLaContrasenaLegible() {
        val st = Storage(ctx)
        st.saveSources(listOf(Source("a", "Mi lista", SourceType.XTREAM, server = "http://s", username = "u", password = "clave")))
        val b = JSONObject(st.exportBackup())
        assertTrue(b.getJSONObject("prefs").getJSONObject("sources").getString("s").contains("\"clave\""))
        prefs.edit().clear().commit()
        st.importBackup(b.toString())
        assertEquals("clave", st.sources().single().password)
    }
}
