plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.chaquopy)
}

// Buildscript classpath import: Gradle Kotlin DSL scripts do not resolve
// fully-qualified java.util references without it.
import java.util.Properties

android {
    namespace = "com.chaya.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.chaya.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.3.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The Python runtime ships a native build per ABI. Phones are arm64; x86_64 is for emulators
        // (and the CI walkthrough). 32-bit ABIs are left out to keep the APK from growing further.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    // Release signing (Phase 5.1): credentials never live in the repo.
    // Local: copy keystore.properties.example to keystore.properties
    // (gitignored) and fill in your key. CI: KEYSTORE_* secrets, see build.yml.
    // True when either source provides a keystore path (checked at
    // configuration time; assembleRelease falls back to debug signing).
    fun hasReleaseKey(): Boolean {
        val propsFile = rootProject.file("keystore.properties")
        if (propsFile.exists()) {
            val props = Properties()
            propsFile.inputStream().use { props.load(it) }
            if (!props.getProperty("storeFile").isNullOrBlank()) return true
        }
        return !System.getenv("KEYSTORE_PATH").isNullOrBlank()
    }
    signingConfigs {
        create("release") {
            val props = Properties()
            val propsFile = rootProject.file("keystore.properties")
            if (propsFile.exists()) propsFile.inputStream().use { props.load(it) }
            storeFile = (props.getProperty("storeFile") ?: System.getenv("KEYSTORE_PATH"))
                ?.let { file(it) }
            storePassword = props.getProperty("storePassword") ?: System.getenv("KEYSTORE_PASSWORD")
            keyAlias = props.getProperty("keyAlias") ?: System.getenv("KEY_ALIAS")
            keyPassword = props.getProperty("keyPassword") ?: System.getenv("KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Unsigned until credentials exist (local keystore.properties or
            // CI secrets); assembleRelease still works for verification.
            signingConfig = signingConfigs.findByName(
                if (hasReleaseKey()) "release" else "debug"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // Generates com.chaya.app.BuildConfig (DEBUG/ VERSION_NAME) used by
        // diagnostics gating (StrictMode) and crash-report app-version field.
        buildConfig = true
    }
}

dependencies {
    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // OkHttp
    implementation(libs.okhttp)

    // Coil: poster/page thumbnails in the media sheet. Its own OkHttp client
    // carries no WebView cookies, so thumbnails load anonymously.
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // QuickJS: runs yt-dlp's YouTube challenge solver inside the app (no separate JS runtime to ship).
    implementation(libs.quickjs.kt)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Media3 (HLS / DASH streaming downloads + in-app playback)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.ui)

    // LeakCanary runs only in debug builds; release APKs are unaffected.
    debugImplementation(libs.leakcanary)

    // Unit tests (JVM, no device/emulator needed — Robolectric hosts Compose)
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.androidx.room.testing)
    // Compose test APIs resolve via the BOM; ui-test-manifest supplies the
    // required test activity for createComposeRule under Robolectric.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // On-device tests (androidTest): what a plain JVM cannot run — Python and yt-dlp inside the app, the
    // QuickJS engine and the real MediaMuxer join. They run in a cloud emulator; see device-tests.yml.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.mockwebserver)

    // Compose UI tests (androidTest, emulator/CI only)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// Python on Android (Chaquopy) runs yt-dlp, which finds what a YouTube, Instagram, TikTok or X
// link points to. Both packages are pure Python, so no Android-specific builds are involved, and they
// are pinned together: yt-dlp checks its challenge-solver scripts against the yt-dlp-ejs release it shipped with.
chaquopy {
    defaultConfig {
        version = "3.13"
        pip {
            install("yt-dlp==2026.8.19")
            install("yt-dlp-ejs==0.8.0")
        }
    }
}

// Robolectric needs the resource-merged classpath for ApplicationProvider-style
// context access even when tests don't inflate layouts.
android {
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}
