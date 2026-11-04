plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.shelflife.tv"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.shelflife.tv"
        minSdk = 31          // Android 12, what the first-gen Onn 4K Pro runs
        targetSdk = 36
        // A new version on every build, so the TV launcher refreshes its cached icon and banner.
        versionCode = (System.currentTimeMillis() / 60_000).toInt()
        versionName = "0.2"
        // The Onn 4K Pro runs a 32-bit Android (armeabi-v7a); Apple-silicon emulators are arm64.
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
        // TMDB key for production-company logos on case backs, from secrets.env (TMDB_API_KEY=...).
        val tmdb = rootProject.file("secrets.env").takeIf { it.exists() }?.readLines()
            ?.firstOrNull { it.startsWith("TMDB_API_KEY=") }?.substringAfter("=")?.trim()?.trim('"', '\'') ?: ""
        buildConfigField("String", "TMDB_API_KEY", "\"$tmdb\"")
        // Where the studio intros and the QuickPlay background are downloaded from (Extras.kt, docs/extras.md):
        // speelyberg.com, unless secrets.env sets EXTRAS_URL=https://... (EXTRAS_URL= with nothing: no intros).
        val extras = rootProject.file("secrets.env").takeIf { it.exists() }?.readLines()
            ?.firstOrNull { it.startsWith("EXTRAS_URL=") }?.substringAfter("=")?.trim()?.trim('"', '\'') ?: "https://speelyberg.com/shelflife/assets"
        buildConfigField("String", "EXTRAS_URL", "\"$extras\"")
    }
    buildFeatures { buildConfig = true }
    // Filament reads these straight from the APK, so keep them uncompressed.
    // The optimised build the TV runs. Compose is several times slower in debug builds on a TV box's CPU.
    // Signed with the local debug key, which is fine for sideloading onto your own Onn.
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    androidResources { noCompress += listOf("glb", "filamat") }
    buildFeatures { compose = true }
}

dependencies {
    val filament = "1.77.3"
    implementation("com.google.android.filament:filament-android:$filament")
    implementation("com.google.android.filament:gltfio-android:$filament")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    // On-screen UI follows Material 3 for TV (Compose for TV).
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.tv:tv-material:1.1.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
}
