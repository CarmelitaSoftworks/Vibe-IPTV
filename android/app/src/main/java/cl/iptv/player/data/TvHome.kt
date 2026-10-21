package cl.iptv.player.data

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.WatchNextProgram
import androidx.tvprovider.media.tv.TvContractCompat

/**
 * Fila "Vibe IPTV" en la pantalla de inicio de Android TV / Google TV (Android 8+):
 * tus canales favoritos y los últimos vistos; al elegir uno se abre directo en el reproductor.
 * En equipos sin esa función (Fire TV, celulares) no hace nada.
 */
object TvHome {
    private const val PREFS = "tvhome"

    fun supported(ctx: Context) = Build.VERSION.SDK_INT >= 26 &&
        ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    fun link(sourceId: String, itemId: String, episodeId: String? = null): Uri =
        Uri.Builder().scheme("vibeiptv").authority("play").appendQueryParameter("src", sourceId).appendQueryParameter("id", itemId)
            .apply { if (episodeId != null) appendQueryParameter("ep", episodeId) }.build()

    /** Una película o serie a medias (o el último canal) para "Seguir viendo" del inicio de la TV. */
    class Watched(val item: Item, val episodeId: String?, val episodeTitle: String?, val season: Int, val episode: Int,
                  val posMs: Long, val durMs: Long, val at: Long)

    /**
     * "Seguir viendo" del inicio de Google TV / Android TV (Watch Next): películas y episodios a medias con su
     * avance, para reanudarlos con un clic. Reemplaza lo publicado antes; respeta lo que el usuario quitó de esa fila.
     * Llamar fuera del hilo principal.
     */
    fun publishWatchNext(ctx: Context, sourceId: String, watched: List<Watched>) {
        if (!supported(ctx)) return
        runCatching {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val cr = ctx.contentResolver
            // Lo que el usuario quitó de la fila (el sistema lo deja "no visible"): no se vuelve a agregar
            val removed = prefs.getStringSet("wn_removed", emptySet())!!.toMutableSet()
            prefs.getStringSet("wn", emptySet())!!.forEach { idStr ->
                val uri = TvContractCompat.buildWatchNextProgramUri(idStr.toLong())
                runCatching {
                    cr.query(uri, null, null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            val p = WatchNextProgram.fromCursor(c)
                            if (!p.isBrowsable) removed += p.internalProviderId.orEmpty()
                        }
                    }
                }
                runCatching { cr.delete(uri, null, null) }
            }
            val ids = watched.filter { (it.episodeId ?: it.item.id) !in removed }.take(10).mapNotNull { w ->
                runCatching {
                    val key = w.episodeId ?: w.item.id
                    val type = when {
                        w.item.kind == Kind.LIVE -> TvContractCompat.WatchNextPrograms.TYPE_CHANNEL
                        w.episodeId != null -> TvContractCompat.WatchNextPrograms.TYPE_TV_EPISODE
                        else -> TvContractCompat.WatchNextPrograms.TYPE_MOVIE
                    }
                    val b = WatchNextProgram.Builder()
                        .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
                        .setLastEngagementTimeUtcMillis(w.at)
                        .setType(type)
                        .setTitle(w.item.name)
                        .setIntentUri(link(sourceId, w.item.id, w.episodeId))
                        .setInternalProviderId(key)
                        .setPosterArtAspectRatio(if (w.item.kind == Kind.LIVE) TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9 else TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3)
                    if (w.item.kind == Kind.LIVE) b.setLive(true)
                    if (w.durMs > 0) b.setDurationMillis(w.durMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                        .setLastPlaybackPositionMillis(w.posMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                    if (w.episodeId != null) {
                        w.episodeTitle?.let { b.setEpisodeTitle(it) }
                        if (w.season > 0) b.setSeasonNumber(w.season)
                        if (w.episode > 0) b.setEpisodeNumber(w.episode)
                    }
                    if (w.item.logo.startsWith("http")) b.setPosterArtUri(Uri.parse(w.item.logo))
                    else b.setPosterArtUri(Uri.parse("android.resource://${ctx.packageName}/${cl.iptv.player.R.drawable.tv_banner}"))
                    cr.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, b.build().toContentValues())?.let { u -> android.content.ContentUris.parseId(u).toString() }
                }.getOrNull()
            }
            prefs.edit().putStringSet("wn", ids.toSet()).putStringSet("wn_removed", removed.toSet().take(200).toSet()).apply()
        }
    }

    /** Publica (o reemplaza) los canales de la fila. Llamar fuera del hilo principal. */
    fun publish(ctx: Context, sourceId: String, channels: List<Item>) {
        if (!supported(ctx)) return
        runCatching {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val helper = PreviewChannelHelper(ctx)
            var channelId = prefs.getLong("channel", -1L)
            if (channelId > 0 && helper.getPreviewChannel(channelId) == null) channelId = -1L
            if (channelId <= 0) {
                val logo = ContextCompat.getDrawable(ctx, cl.iptv.player.R.drawable.ic_launcher)!!.toBitmap(160, 160)
                val ch = PreviewChannel.Builder()
                    .setDisplayName("Vibe IPTV")
                    .setDescription("Favoritos y vistos recientemente")
                    .setAppLinkIntentUri(Uri.parse("vibeiptv://open"))
                    .setLogo(logo)
                    .build()
                channelId = helper.publishDefaultChannel(ch)      // la primera fila queda visible sin pedir permiso
                prefs.edit().putLong("channel", channelId).apply()
            }
            // Reemplazar los programas anteriores
            prefs.getStringSet("programs", emptySet())!!.forEach { runCatching { helper.deletePreviewProgram(it.toLong()) } }
            val ids = channels.take(20).mapNotNull { item ->
                runCatching {
                    val b = PreviewProgram.Builder()
                        .setChannelId(channelId)
                        .setType(TvContractCompat.PreviewPrograms.TYPE_CHANNEL)
                        .setTitle(item.name)
                        .setDescription(item.group)
                        .setLive(item.kind == Kind.LIVE)
                        .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
                        .setIntentUri(link(sourceId, item.id))
                        .setInternalProviderId(item.id)
                    if (item.logo.startsWith("http")) b.setPosterArtUri(Uri.parse(item.logo))
                    else b.setPosterArtUri(Uri.parse("android.resource://${ctx.packageName}/${cl.iptv.player.R.drawable.tv_banner}"))
                    helper.publishPreviewProgram(b.build()).toString()
                }.getOrNull()
            }
            prefs.edit().putStringSet("programs", ids.toSet()).apply()
        }
    }
}
