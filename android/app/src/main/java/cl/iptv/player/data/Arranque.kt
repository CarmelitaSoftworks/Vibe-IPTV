package cl.iptv.player.data

import android.content.Context
import java.io.File

/**
 * Modo seguro: si la app se cierra dos veces seguidas al abrir (por ejemplo por un dato guardado dañado),
 * el siguiente inicio no carga la última lista ni la guía guardada. El último error se guarda en crash.txt
 * y se incluye en Ajustes → Acerca de → Enviar registro.
 */
object Arranque {
    @Volatile var seguro: Boolean = false
        private set
    private const val PREFS = "arranque"
    private const val ESPERA_MS = 8_000L

    private fun crashFile(ctx: Context) = File(ctx.applicationContext.filesDir, "crash.txt")

    /** Llamar al principio de MainActivity.onCreate, antes de usar el ViewModel. */
    fun iniciar(ctx: Context, contar: Boolean) {
        val app = ctx.applicationContext
        val previo = Thread.getDefaultUncaughtExceptionHandler()
        if (previo !is Captura) Thread.setDefaultUncaughtExceptionHandler(Captura(app, previo))
        if (!contar) return
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val fallos = runCatching { p.getInt("fallos", 0) }.getOrDefault(0)
        seguro = fallos >= 2
        p.edit().putInt("fallos", fallos + 1).commit()
    }

    /** La app lleva unos segundos abierta sin cerrarse: el inicio fue correcto. */
    fun programarListo(ctx: Context, post: (Runnable, Long) -> Unit) {
        val app = ctx.applicationContext
        post(Runnable { app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("fallos", 0).apply() }, ESPERA_MS)
    }

    fun ultimoError(ctx: Context): String? =
        runCatching { crashFile(ctx).takeIf { it.exists() }?.readText() }.getOrNull()

    private class Captura(val app: Context, val previo: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            runCatching {
                val fecha = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
                crashFile(app).writeText("$fecha · hilo ${t.name}\n${e.stackTraceToString().take(20_000)}")
            }
            previo?.uncaughtException(t, e)
        }
    }
}
