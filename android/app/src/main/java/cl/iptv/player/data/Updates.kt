package cl.iptv.player.data

import android.content.Context
import android.os.Build
import org.json.JSONObject

/**
 * Busca si hay una versión más nueva publicada en GitHub (la versión "prueba" es siempre la última).
 * Sólo lee el número de versión: no envía ningún dato del equipo.
 */
object Updates {
    private const val API = "https://api.github.com/repos/CarmelitaSoftworks/Vibe-IPTV/releases/tags/prueba"
    private const val DOWNLOAD = "https://github.com/CarmelitaSoftworks/Vibe-IPTV/releases/download/prueba/"

    data class Available(val build: Int, val name: String, val url: String)

    fun currentBuild(ctx: Context): Int = runCatching {
        val p = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) p.longVersionCode.toInt() else @Suppress("DEPRECATION") p.versionCode
    }.getOrDefault(0)

    fun currentName(ctx: Context): String = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"

    /** APK adecuado para este equipo: 64 bits en celulares; 32 bits en TV (la mayoría usa apps de 32 bits). */
    fun apkFor(isTv: Boolean): String {
        val abis = Build.SUPPORTED_ABIS.toList()
        return when {
            abis.firstOrNull() == "x86_64" -> "vibe-iptv-x86_64.apk"
            !isTv && "arm64-v8a" in abis -> "vibe-iptv-arm64.apk"
            "armeabi-v7a" in abis -> "vibe-iptv-armv7.apk"
            else -> "vibe-iptv-universal.apk"
        }
    }

    suspend fun check(ctx: Context, isTv: Boolean): Available? {
        val json = JSONObject(Http.text(API, "VibeIPTV-Android"))
        val name = json.optString("name")
        val build = Regex("""0\.1\.(\d+)""").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        if (build <= currentBuild(ctx)) return null
        // La versión con el modelo de voz incluido se actualiza con su propio APK
        val file = apkFor(isTv).let { if (cl.iptv.player.BuildConfig.FLAVOR == "ia") it.replace("vibe-iptv-", "vibe-iptv-ia-") else it }
        return Available(build, "0.1.$build", DOWNLOAD + file)
    }
}
