import java.util.Properties

plugins {
    id("com.android.application")
}

// One version for the whole project: ../version.json (also used by the Windows app).
// versionCode = major*10000 + minor*100 + patch  (1.1.0 -> 10100)
val versionText: String = rootProject.file("../version.json").readText()
val appVersion: String = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(versionText)?.groupValues?.get(1)
    ?: error("version.json has no \"version\"")
val versionNumbers = appVersion.substringBefore('-').split('.').map { it.toInt() }
val appVersionCode = versionNumbers[0] * 10000 + versionNumbers[1] * 100 + versionNumbers[2]

// Release signing key, first match wins:
//  1. environment KEYSTORE_PATH / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD  (GitHub Actions, from secrets)
//  2. android/keystore.properties  (local builds; never committed)  storeFile=..., storePassword=..., keyAlias=..., keyPassword=...
//  3. none -> the APK is signed with the debug key (installable, but cannot update a release-signed install)
val envKeystore: String? = System.getenv("KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
val localKeyProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseKey = envKeystore != null || localKeyProps.getProperty("storeFile") != null

// Fallback audio decoder for formats the phone cannot decode itself (E-AC3/AC3, DTS, TrueHD, ALAC):
// Media3's FFmpeg extension — Java in src/main/java/androidx/media3/decoder/ffmpeg, JNI wrapper in
// src/main/cpp. Its FFmpeg static libraries come from scripts/build-ffmpeg-android.sh (CI builds and
// caches them in ../ffmpeg-android); without them the app is simply built without this decoder.
val ffmpegDir = rootProject.file("ffmpeg-android")
val ffmpegAbis = listOf("arm64-v8a", "armeabi-v7a").filter { File(ffmpegDir, "$it/lib/libavcodec.a").exists() }

android {
    namespace = "com.moa.player"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.moa.player"
        minSdk = 24
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersion
        if (ffmpegAbis.isNotEmpty()) {
            ndk { abiFilters += ffmpegAbis }
            externalNativeBuild { cmake { arguments += "-DFFMPEG_DIR=${ffmpegDir.absolutePath}" } }
        }
    }

    if (ffmpegAbis.isNotEmpty()) {
        ndkVersion = "27.2.12479018" // r27c, the NDK that built the FFmpeg libraries (build-android.yml)
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }

    packaging {
        // compressed native library: a smaller APK to download (Android unpacks it once at install)
        jniLibs { useLegacyPackaging = true }
    }

    signingConfigs {
        create("release") {
            if (envKeystore != null) {
                storeFile = file(envKeystore)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "moa"
                keyPassword = System.getenv("KEY_PASSWORD")?.takeIf { it.isNotBlank() } ?: System.getenv("KEYSTORE_PASSWORD")
            } else if (localKeyProps.getProperty("storeFile") != null) {
                storeFile = rootProject.file(localKeyProps.getProperty("storeFile"))
                storePassword = localKeyProps.getProperty("storePassword")
                keyAlias = localKeyProps.getProperty("keyAlias", "moa")
                keyPassword = localKeyProps.getProperty("keyPassword", localKeyProps.getProperty("storePassword"))
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    val media3 = "1.11.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-exoplayer-rtsp:$media3")
}
