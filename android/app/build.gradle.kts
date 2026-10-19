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
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Para probar el APK release sin keystore propio. Cámbialo por tu firma antes de publicar.
            signingConfig = signingConfigs.getByName("debug")
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
    // Lectura de texto de capturas ("Desde una captura"): modelo incluido, sin Internet ni Google Play
    implementation("com.google.mlkit:text-recognition:16.0.1")
    testImplementation("junit:junit:4.13.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
