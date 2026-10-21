package cl.iptv.player

import android.app.PictureInPictureParams
import android.app.UiModeManager
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cl.iptv.player.ui.App
import cl.iptv.player.ui.AppViewModel
import cl.iptv.player.ui.theme.IptvTheme

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    /** Interceptor de teclas del mando (lo instala el reproductor). */
    var keyInterceptor: ((KeyEvent) -> Boolean)? = null

    /** true en Android TV / Google TV / Fire TV. */
    val isTv: Boolean by lazy {
        val ui = getSystemService(UI_MODE_SERVICE) as UiModeManager
        ui.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            !packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }

    /** Hay algo reproduciéndose: al salir de la app pasa a imagen en imagen. */
    var pipEnabled = false
        set(v) { field = v; updatePipParams() }
    var inPip by mutableStateOf(false)
        private set
    /** Se estaba en el mini reproductor al pasar a imagen en imagen: al volver se regresa a él. */
    private var miniBeforePip = false

    private fun pipSupported() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
        packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun pipParams(): PictureInPictureParams = PictureInPictureParams.Builder()
        .setAspectRatio(Rational(16, 9))
        .apply {
            // Android 12+: el sistema entra solo en imagen en imagen al salir de la app (también con gestos)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { setAutoEnterEnabled(pipEnabled && !isTv); setSeamlessResizeEnabled(true) }
        }.build()

    private fun updatePipParams() {
        if (pipSupported()) runCatching { setPictureInPictureParams(pipParams()) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Modo seguro y registro de cierres: antes de crear el ViewModel (que abre la última lista)
        cl.iptv.player.data.Arranque.iniciar(this, contar = savedInstanceState == null)
        // Sin lista que cargar (pantalla de bienvenida): el inicio se da por correcto a los 10 s
        if (savedInstanceState == null) window.decorView.postDelayed({ if (vm.source == null) cl.iptv.player.data.Arranque.listo(this) }, 10_000)
        enableEdgeToEdge()
        cl.iptv.player.ui.I18n.load(this)
        vm.applyLite()
        // Carátulas: en modo liviano menos memoria (colores de 16 bits, caché chica) y sin fundidos
        val lite = cl.iptv.player.data.Perf.lite
        coil.Coil.setImageLoader(
            coil.ImageLoader.Builder(this)
                .memoryCache { coil.memory.MemoryCache.Builder(this).maxSizePercent(if (lite) 0.12 else 0.25).build() }
                .allowRgb565(lite)
                .crossfade(!lite)
                .build()
        )
        if (savedInstanceState == null) {
            vm.checkUpdate(isTv)   // aviso de versión nueva (sólo lee el número de versión)
            intent?.data?.let { vm.openLink(it) }
        }
        setContent {
            cl.iptv.player.ui.I18n.apply(vm.settings.lang)
            IptvTheme(vm.settings.theme, vm.settings.accent, vm.settings.customTheme, vm.themeImage) { App(vm) }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        intent.data?.let { vm.openLink(it) }
    }

    override fun onStart() {
        super.onStart()
        vm.onForeground()   // al abrir o volver a la app: guía al día
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (keyInterceptor?.invoke(event) == true) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 12+ ya entra solo (setAutoEnterEnabled); antes se pide aquí
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !isTv) enterPip()
    }

    /** Imagen en imagen (también desde el botón del reproductor). Devuelve false si el equipo no lo permite. */
    fun enterPip(): Boolean {
        if (pipEnabled && pipSupported()) return runCatching { enterPictureInPictureMode(pipParams()) }.getOrDefault(false)
        return false
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        // En la ventanita del sistema sólo va el video: el mini reproductor pasa a pantalla completa y vuelve después
        if (isInPictureInPictureMode) { miniBeforePip = vm.mini; if (vm.mini) vm.expandMini() }
        else if (miniBeforePip) { miniBeforePip = false; vm.enterMini(quiet = true) }
    }
}
