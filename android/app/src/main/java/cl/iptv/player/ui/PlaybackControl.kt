package cl.iptv.player.ui

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import java.io.File

/** Una opción de pista (audio, subtítulo o calidad) para los menús del reproductor. */
data class TrackOpt(val id: String, val label: String, val selected: Boolean)

/**
 * Controles comunes a los tres motores (ExoPlayer, VLC y MPV): así los botones en pantalla
 * funcionan igual sin importar qué motor está reproduciendo.
 */
interface PlaybackControl {
    val isPlaying: Boolean
    /** Posición y duración en milisegundos (duración ≤ 0 = en vivo o desconocida). */
    val position: Long
    val duration: Long
    fun togglePause()
    fun pause()
    fun resume()
    fun seekTo(ms: Long)
    fun seekBy(ms: Long) = seekTo((position + ms).coerceAtLeast(0))
    fun audioTracks(): List<TrackOpt>
    fun selectAudio(id: String)
    fun subtitleTracks(): List<TrackOpt>
    /** null = sin subtítulos. */
    fun selectSubtitle(id: String?)
    fun qualities(): List<TrackOpt> = emptyList()
    /** null = automática. */
    fun selectQuality(id: String?) {}
    var speed: Float
    /** Subtítulo descargado o de archivo (en ExoPlayer se agrega al recargar el elemento). */
    fun addExternalSubtitle(file: File) {}
    /** Datos técnicos para el panel de información. */
    fun info(): List<Pair<String, String>> = emptyList()
    /** Video ya cargado por delante de lo que se ve (ms), o null si el motor no lo informa. */
    val bufferedAheadMs: Long? get() = null
    /** Ecualizador y volumen parejo (ver data/Sound.kt). */
    fun setSound(preset: String, level: Boolean) {}
    /** Retraso de los subtítulos en ms (+ = aparecen después). false si este motor no lo permite. */
    fun setSubtitleDelay(ms: Long): Boolean = false
}

private fun lang(code: String?): String? = code?.takeIf { it.isNotBlank() && it != "und" }?.let {
    val name = localeOf(it).getDisplayLanguage(localeOf(I18n.lang))
    if (name.isNotBlank() && name != it) name.replaceFirstChar { c -> c.uppercase() } else it.uppercase()
}

private fun codecName(f: Format): String? {
    val m = (f.sampleMimeType ?: f.codecs ?: return null).lowercase()
    return when {
        "ac3" in m || "ac-3" in m -> "AC-3"
        "eac3" in m || "ec-3" in m -> "E-AC-3"
        "mpeg-l2" in m || "mp2" in m -> "MP2"
        "mpeg" in m && "audio" in m -> "MP3"
        "mp4a" in m || "aac" in m -> "AAC"
        "opus" in m -> "Opus"
        "avc" in m || "h264" in m -> "H.264"
        "hevc" in m || "h265" in m -> "H.265"
        "mpeg2" in m -> "MPEG-2"
        "vtt" in m -> "WebVTT"
        "subrip" in m -> "SRT"
        "cea" in m -> "CC"
        else -> m.substringAfter('/').uppercase()
    }
}

/** ExoPlayer como [PlaybackControl]. */
@OptIn(UnstableApi::class)
class ExoControl(private val p: ExoPlayer) : PlaybackControl {
    override val bufferedAheadMs: Long get() = p.totalBufferedDuration
    override val isPlaying get() = p.isPlaying || (p.playWhenReady && p.playbackState == androidx.media3.common.Player.STATE_BUFFERING)
    override val position get() = p.currentPosition
    override val duration get() = if (p.isCurrentMediaItemLive || p.duration == C.TIME_UNSET) -1 else p.duration
    override fun togglePause() { if (p.playWhenReady) p.pause() else p.play() }
    override fun pause() = p.pause()
    override fun resume() = p.play()
    override fun seekTo(ms: Long) = p.seekTo(ms)

    private fun opts(type: Int, label: (Format, Int) -> String): List<TrackOpt> =
        p.currentTracks.groups.withIndex().filter { it.value.type == type }.flatMap { (gi, g) ->
            (0 until g.length).filter { g.isTrackSupported(it, true) }.map { ti -> TrackOpt("$gi:$ti", label(g.getTrackFormat(ti), ti), g.isTrackSelected(ti)) }
        }

    private fun override(type: Int, id: String) {
        val (gi, ti) = id.split(':').map { it.toInt() }
        val g = p.currentTracks.groups.getOrNull(gi) ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(type, false)
            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, ti))
            .build()
    }

    override fun audioTracks() = opts(C.TRACK_TYPE_AUDIO) { f, i ->
        listOfNotNull(f.label ?: lang(f.language), codecName(f), f.channelCount.takeIf { it > 0 }?.let { if (it >= 6) "5.1" else if (it == 2) "Estéreo" else "$it ch" })
            .joinToString(" · ").ifBlank { "Pista ${i + 1}" }
    }
    override fun selectAudio(id: String) = override(C.TRACK_TYPE_AUDIO, id)

    override fun subtitleTracks() = opts(C.TRACK_TYPE_TEXT) { f, i ->
        listOfNotNull(f.label ?: lang(f.language), codecName(f)).joinToString(" · ").ifBlank { "Subtítulo ${i + 1}" }
    }
    override fun selectSubtitle(id: String?) {
        if (id == null) p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        else override(C.TRACK_TYPE_TEXT, id)
    }

    override fun qualities() = opts(C.TRACK_TYPE_VIDEO) { f, _ ->
        listOfNotNull(f.height.takeIf { it > 0 }?.let { "${it}p" }, f.bitrate.takeIf { it > 0 }?.let { "%.1f Mb/s".format(it / 1e6) }).joinToString(" · ")
    }.let { list -> if (list.size > 1) list.sortedByDescending { it.label.substringBefore('p').toIntOrNull() ?: 0 } else emptyList() }
    override fun selectQuality(id: String?) {
        if (id == null) p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO).build()
        else override(C.TRACK_TYPE_VIDEO, id)
    }
    /** true si el usuario fijó una calidad. */
    val qualityLocked get() = p.trackSelectionParameters.overrides.keys.any { it.type == C.TRACK_TYPE_VIDEO }

    override var speed: Float
        get() = p.playbackParameters.speed
        set(v) { p.setPlaybackSpeed(v) }

    override fun info(): List<Pair<String, String>> {
        val v = p.videoFormat; val a = p.audioFormat
        return listOfNotNull(
            v?.let { "Video" to listOfNotNull(codecName(it), if (it.width > 0) "${it.width}×${it.height}" else null, it.frameRate.takeIf { f -> f > 0 }?.let { f -> "%.0f fps".format(f) }).joinToString(" · ") },
            a?.let { "Audio" to listOfNotNull(codecName(it), it.sampleRate.takeIf { r -> r > 0 }?.let { r -> "${r / 1000} kHz" }, it.channelCount.takeIf { c -> c > 0 }?.let { c -> "$c ch" }).joinToString(" · ") },
            (p.videoFormat?.bitrate ?: 0).takeIf { it > 0 }?.let { "Velocidad de bits" to "%.1f Mb/s".format(it / 1e6) },
            "Búfer" to "%.1f s".format((p.bufferedPosition - p.currentPosition).coerceAtLeast(0) / 1000f),
        )
    }
}
