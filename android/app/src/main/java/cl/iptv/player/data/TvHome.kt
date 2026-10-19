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

    fun link(sourceId: String, itemId: String): Uri =
        Uri.Builder().scheme("vibeiptv").authority("play").appendQueryParameter("src", sourceId).appendQueryParameter("id", itemId).build()

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
