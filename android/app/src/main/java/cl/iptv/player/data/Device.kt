package cl.iptv.player.data

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * Perfil del equipo. Los TV boxes de Xiaomi (Mi Box S, Mi TV Stick) y otros con Amlogic,
 * 1–2 GB de RAM y GPU Mali chica se notan lentos con sombras, animaciones y carátulas grandes:
 * para ellos se activa el modo liviano.
 */
object Device {
    val isXiaomi: Boolean get() = Build.MANUFACTURER.equals("Xiaomi", true) || Build.BRAND.equals("Xiaomi", true) ||
        Build.MODEL.uppercase().let { it.startsWith("MIBOX") || it.startsWith("MITV") || it.contains("MI BOX") }

    /** Equipo modesto: poca RAM, sistema de 32 bits o TV box de Xiaomi. */
    fun lowEnd(ctx: Context): Boolean {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return am.isLowRamDevice || mem.totalMem < 2_600L * 1024 * 1024 || Build.SUPPORTED_64_BIT_ABIS.isEmpty() || isXiaomi
    }

    /** Lo que se detecta al iniciar: memoria, procesador y decodificadores por hardware. */
    data class Hw(
        val name: String, val ramMb: Long, val heapMb: Int, val bits64: Boolean, val cores: Int,
        val hwMpeg2: Boolean, val hwHevc: Boolean, val hwAc3: Boolean, val lowEnd: Boolean,
    ) {
        /** Ajustes recomendados para este equipo. */
        val lite get() = lowEnd
        val buffer get() = if (ramMb >= 3_500 && bits64) "stable" else "balanced"
        /** Sin decodificador MPEG-2 (TV de cable) es mejor empezar los canales en vivo con VLC. */
        val liveEngine: String? get() = if (!hwMpeg2) "vlc" else null
        val tileSize get() = if (lowEnd) "normal" else "normal"
        fun text() = "$name · ${"%.1f".format(ramMb / 1024.0)} GB RAM · ${if (bits64) 64 else 32} bits · $cores núcleos" +
            " · MPEG-2 ${if (hwMpeg2) "sí" else "no"} · HEVC ${if (hwHevc) "sí" else "no"} · AC-3 ${if (hwAc3) "sí" else "no (FFmpeg)"}"
    }

    fun detect(ctx: Context): Hw {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val codecs = runCatching { android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder } }
            .getOrDefault(emptyList())
        fun has(mime: String, hwOnly: Boolean) = codecs.any { c ->
            c.supportedTypes.any { it.equals(mime, true) } &&
                (!hwOnly || !(c.name.startsWith("OMX.google.") || c.name.startsWith("c2.android.") || c.name.contains("ffmpeg", true)))
        }
        return Hw(
            name = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            ramMb = mem.totalMem / 1_048_576, heapMb = am.memoryClass,
            bits64 = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty(), cores = Runtime.getRuntime().availableProcessors(),
            hwMpeg2 = has("video/mpeg2", true), hwHevc = has("video/hevc", true), hwAc3 = has("audio/ac3", false),
            lowEnd = lowEnd(ctx),
        )
    }

    fun summary(ctx: Context): String {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return "${Build.MANUFACTURER} ${Build.MODEL} · ${"%.1f".format(mem.totalMem / 1_073_741_824.0)} GB · ${if (Build.SUPPORTED_64_BIT_ABIS.isEmpty()) "32" else "64"} bits"
    }
}

/** Interruptor global del modo liviano (lo fija la app al iniciar y al cambiar el ajuste). */
object Perf { @Volatile var lite: Boolean = false }
