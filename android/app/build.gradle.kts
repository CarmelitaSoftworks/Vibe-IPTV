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
        targetSdk = 36
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
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { t ->
                t.systemProperty("roborazzi.test.record", "true")
                t.systemProperty("vibe.capturas", System.getProperty("vibe.capturas") ?: (project.findProperty("capturas")?.toString() ?: "false"))
                t.maxHeapSize = "3g"
            }
        }
    }
    buildFeatures { compose = true; buildConfig = true }

    // Dos versiones: "estandar" (descarga el modelo de voz si se usan los subtítulos con IA)
    // e "ia" (trae incluido el modelo de español: GitHub Actions lo descarga en src/ia/assets/vosk/es).
    flavorDimensions += "modelo"
    productFlavors {
        create("estandar") { dimension = "modelo"; isDefault = true }
        create("ia") { dimension = "modelo"; versionNameSuffix = "-ia" }
    }

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

// Kotlin 2.2+: jvmTarget va aquí (kotlinOptions ya no se acepta)
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")

    val media3 = "1.9.4"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    // FFmpeg para ExoPlayer (compilado por Jellyfin): audio AC-3 / E-AC-3 / DTS aunque el equipo no lo traiga
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.9.0+1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Motor VLC: MPEG-2, AC-3, E-AC-3, MP2… (formatos habituales de la TV de cable)
    implementation("org.videolan.android:libvlc-all:3.7.6")
    // Motor MPV (libmpv): tercera opción si ExoPlayer y VLC no pueden con un canal
    implementation("dev.jdtech.mpv:libmpv:1.0.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.tvprovider:tvprovider:1.0.0")
    // Subtítulos con IA en el equipo: Vosk (reconocimiento de voz sin internet, Apache 2.0)
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    // Lectura de texto de capturas ("Desde una captura"): modelo incluido, sin Internet ni Google Play
    implementation("com.google.mlkit:text-recognition:16.0.1")
    testImplementation("junit:junit:4.13.2")
    // Capturas automáticas de pantallas (Robolectric + Roborazzi): sólo en el flujo "Capturas" de GitHub Actions
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.40.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.40.1")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.test:core:1.6.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}
