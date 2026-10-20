package cl.iptv.player.data

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import java.io.File

/**
 * Modo seguro: si la app se cierra dos veces seguidas al abrir (por ejemplo por un dato guardado dañado
 * o por falta de memoria), el siguiente inicio no carga la última lista ni la guía guardada.
 * El último error (crash.txt) y los motivos de cierre que informa Android 11+ (salidas.txt: cierres nativos
 * de VLC/MPV, falta de memoria, ANR) se incluyen en Ajustes → Acerca de → Enviar registro.
 */
object Arranque {
    @Volatile var seguro: Boolean = false
        private set
    private const val PREFS = "arranque"

    private fun crashFile(ctx: Context) = File(ctx.applicationContext.filesDir, "crash.txt")
    private fun salidasFile(ctx: Context) = File(ctx.applicationContext.filesDir, "salidas.txt")
    private fun fmt(ms: Long) = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(ms))

    /** Llamar al principio de MainActivity.onCreate, antes de usar el ViewModel. */
    fun iniciar(ctx: Context, contar: Boolean) {
        val app = ctx.applicationContext
        val previo = Thread.getDefaultUncaughtExceptionHandler()
        if (previo !is Captura) Thread.setDefaultUncaughtExceptionHandler(Captura(app, previo))
        if (!contar) return
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        registrarSalidaAnterior(app, p)
        val fallos = runCatching { p.getInt("fallos", 0) }.getOrDefault(0)
        seguro = fallos >= 2
        p.edit().putInt("fallos", fallos + 1).commit()
    }

    /** El inicio fue correcto (la lista cargó y la app siguió abierta unos segundos). */
    fun listo(ctx: Context) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("fallos", 0).apply()
    }

    /** Último error de Kotlin/Java y últimos motivos de cierre informados por Android. */
    fun ultimoError(ctx: Context): String? {
        val crash = runCatching { crashFile(ctx).takeIf { it.exists() }?.readText() }.getOrNull()
        val salidas = runCatching { salidasFile(ctx).takeIf { it.exists() }?.readText() }.getOrNull()
        if (crash.isNullOrBlank() && salidas.isNullOrBlank()) return null
        return buildString {
            if (!salidas.isNullOrBlank()) { appendLine("Cierres informados por Android:"); appendLine(salidas.trim()) }
            if (!crash.isNullOrBlank()) { appendLine("Último error:"); append(crash.trim()) }
        }
    }

    /** Una línea para Ajustes → Acerca de. */
    fun resumen(ctx: Context): String? {
        val salida = runCatching { salidasFile(ctx).takeIf { it.exists() }?.readLines()?.firstOrNull { it.isNotBlank() } }.getOrNull()
        val crash = runCatching { crashFile(ctx).takeIf { it.exists() }?.readLines()?.take(2)?.joinToString(" · ") }.getOrNull()
        return (salida ?: crash)?.take(160)
    }

    private val MOTIVOS = setOf(
        ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_LOW_MEMORY, ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
    )

    private fun nombre(reason: Int) = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "error de la app"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "cierre nativo (VLC / MPV / FFmpeg)"
        ApplicationExitInfo.REASON_ANR -> "la app dejó de responder"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "falta de memoria"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "fallo al iniciar"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "uso excesivo de recursos"
        else -> "motivo $reason"
    }

    /** Android 11+: guarda por qué se cerró la app la última vez (aunque no haya quedado ningún error en el registro). */
    private fun registrarSalidaAnterior(app: Context, p: SharedPreferences) {
        if (Build.VERSION.SDK_INT < 30) return
        runCatching {
            val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val visto = p.getLong("salida_vista", 0L)
            val nuevas = am.getHistoricalProcessExitReasons(app.packageName, 0, 5)
                .filter { it.timestamp > visto && it.reason in MOTIVOS }
            if (nuevas.isNotEmpty()) {
                p.edit().putLong("salida_vista", nuevas.maxOf { it.timestamp }).apply()
                val texto = nuevas.joinToString("\n") { e ->
                    "${fmt(e.timestamp)} · ${nombre(e.reason)}" +
                        (e.description?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "") +
                        " · memoria ${e.pss / 1024} MB"
                }
                val f = salidasFile(app)
                val anterior = if (f.exists()) f.readText() else ""
                f.writeText((texto + "\n" + anterior).lines().filter { it.isNotBlank() }.take(20).joinToString("\n"))
            }
        }
    }

    private class Captura(val app: Context, val previo: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            runCatching {
                crashFile(app).writeText("${fmt(System.currentTimeMillis())} · hilo ${t.name}\n${e.stackTraceToString().take(20_000)}")
            }
            previo?.uncaughtException(t, e)
        }
    }
}
