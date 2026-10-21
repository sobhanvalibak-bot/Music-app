plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.musicapp"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.example.musicapp"
        minSdk = 24
        targetSdk = 35
        versionCode = 4
        versionName = "1.3"
    }
    // One fixed key for every build, so a new APK installs over the old one (an update)
    // and keeps the app's data instead of failing with a signature conflict.
    signingConfigs {
        create("fixed") {
            storeFile = file("musicapp.jks")
            storePassword = "musicapp123"
            keyAlias = "musicapp"
            keyPassword = "musicapp123"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("fixed") }
        getByName("release") { signingConfig = signingConfigs.getByName("fixed") }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // NewPipeExtractor uses java.time and other Java 8+ APIs that Android 7 does not have
        isCoreLibraryDesugaringEnabled = true
    }
    packaging {
        resources.excludes += setOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-datasource:1.4.1")
    // SoundCloud plays as HLS playlists
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    // Online search + streams (YouTube) without any API key. If this tag is not found on JitPack,
    // use the newest tag from https://github.com/TeamNewPipe/NewPipeExtractor/releases
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    // Reads and writes the real tags inside mp3 / m4a / flac / ogg files (Android build of jaudiotagger)
    implementation("com.github.AdrienPoupa:jaudiotagger:2.2.3")
}
