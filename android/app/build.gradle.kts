plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "cl.iptv.player"
    compileSdk = 36

    defaultConfig {
        applicationId = "cl.iptv.player"
        minSdk = 24          // Android 7.0 / Android TV 7+
        targetSdk = 35
        // Número de versión automático en GitHub Actions (número de compilación); 1 en local
        val build = (System.getenv("VIBE_BUILD") ?: "1").toIntOrNull() ?: 1
        versionCode = build
        versionName = "0.1.$build"
    }

    // Firma fija: la misma clave en cada versión permite actualizar encima sin perder listas ni ajustes.
    // La clave NO está en el repositorio: GitHub Actions la toma de los secretos VIBE_KEYSTORE_B64 y VIBE_KEY_PASS.
    val keystore = System.getenv("VIBE_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (keystore != null) create("vibe") {
            storeFile = keystore
            storePassword = System.getenv("VIBE_KEY_PASS")
            keyAlias = System.getenv("VIBE_KEY_ALIAS") ?: "vibe"
            keyPassword = System.getenv("VIBE_KEY_PASS")
        }
    }

    buildTypes {
        release {
            // Sin R8: VLC, MPV, FFmpeg y ML Kit cargan clases por reflexión; el peso está en las librerías nativas
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = if (keystore != null) signingConfigs.getByName("vibe") else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }

    // VLC y MPV traen la misma librería de C++: se usa una sola copia
    packaging {
        jniLibs { pickFirsts += setOf("**/libc++_shared.so") }
    }

    // Un APK por arquitectura (más livianos) además del universal
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    // FFmpeg para ExoPlayer (compilado por Jellyfin): audio AC-3 / E-AC-3 / DTS aunque el equipo no lo traiga
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.5.0+1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Motor VLC: MPEG-2, AC-3, E-AC-3, MP2… (formatos habituales de la TV de cable)
    implementation("org.videolan.android:libvlc-all:3.7.6")
    // Motor MPV (libmpv): tercera opción si ExoPlayer y VLC no pueden con un canal
    implementation("dev.jdtech.mpv:libmpv:1.0.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.tvprovider:tvprovider:1.0.0")
    // Lectura de texto de capturas ("Desde una captura"): modelo incluido, sin Internet ni Google Play
    implementation("com.google.mlkit:text-recognition:16.0.1")
    testImplementation("junit:junit:4.13.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
