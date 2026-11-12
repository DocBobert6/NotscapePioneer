plugins {
    id("com.android.application")
    // No org.jetbrains.kotlin.android: AGP 9 has Kotlin built in.
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "net.nspioneer.browser"
    // compileSdk 36 (targetSdk also 36). GeckoView 158+ AAR metadata
    // demands compileSdk 37+, but Google has NOT published an
    // android-37 platform to the public SDK repository as of
    // 2026-11-12 (verified: absent from repository2-1.xml on every
    // channel, and the runner's sdkmanager cannot find it on stable
    // or canary; only build-tools 37.0.0 exists). So the AAR metadata
    // check is disabled below and this spike compiles against 36.
    // Re-enable the check and raise compileSdk to 37 the moment the
    // android-37 platform ships. The AGP 9.4.1 / Kotlin 2.3.21 /
    // Gradle 9.8.1 toolchain stays: GeckoView 159 pulls in
    // androidx.core 1.19.x / lifecycle 2.11.x, whose Kotlin metadata
    // and AGP floor (9.1.0+) the Mariner-era toolchain cannot meet.
    compileSdk = 36

    defaultConfig {
        applicationId = "net.nspioneer.browser"
        minSdk = 26 // GeckoView floor: Android 8.0 (API 26) or later.
        targetSdk = 36
        versionCode = 11
        versionName = "0.1.0-alpha.11"
        // arm64-v8a only, matching Mariner's ABI decision.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        compose = true
        // SettingsScreen reads BuildConfig.VERSION_NAME/VERSION_CODE
        // for the About row.
        buildConfig = true
    }
    testOptions {
        unitTests {
            // Row F: DownloadsStoreTest runs under Robolectric,
            // which needs the merged manifest/resources.
            isIncludeAndroidResources = true
        }
    }
}

// AAR metadata check disabled: see the compileSdk comment above.
// GeckoView 159 (and core-ktx 1.19.x / lifecycle 2.11.x) declare a
// minCompileSdk of 37, but no android-37 platform is installable
// from Google's public SDK repository yet (2026-11-12), so the check
// can never pass today. The spike compiles against SDK 36 and uses
// only stable, long-present Android APIs, so nothing it calls
// depends on API 37. Re-enable (delete this block) when the
// android-37 platform ships and compileSdk is raised to 37.
// configureEach (not tasks.named): AGP creates these tasks after
// the build script is evaluated, so a direct lookup fails.
tasks.configureEach {
    if (name == "checkDebugAarMetadata" || name == "checkReleaseAarMetadata") {
        enabled = false
    }
}

// GeckoView pin: 159.0.20261102144711 — latest STABLE release at spike
// time (Firefox 159, published to Mozilla Maven 2026-11-03). Never a
// nightly or beta. Upgrade deliberately: one version bump, one full
// regression pass. NOTE (2026-11-12): 159 (and 158) require
// compileSdk 37+ and AGP 9.1.0+ per AAR metadata, so the toolchain is
// AGP 9.4.1 / Gradle 9.8.1 / Kotlin 2.3.21 — but the android-37
// platform itself is not published to Google's public SDK repository
// yet, so the spike compiles against SDK 36 with the AAR metadata
// check disabled (see the android {} block). The plain `geckoview`
// artifact carries the engine for all ABIs; `abiFilters` above
// strips everything but arm64-v8a at packaging time.
val geckoviewVersion = "159.0.20261102144711"

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.10.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    // The carried shell files use androidx.compose.material.icons.Icons
    // (core filled set only — no icons-extended in this phase).
    implementation("androidx.compose.material:material-icons-core")
    // Phase 3 (C): the player's Replay5/10/30, Forward5/10/30 and
    // automirrored QueueMusic glyphs live in icons-extended.
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("org.mozilla.geckoview:geckoview:$geckoviewVersion")
    // Phase 3 downloads (§8.4): Media3 1.9.4, exactly as Mariner
    // (exoplayer + HLS/DASH + UI + common + session for the
    // player, transformer for DASH/merge remux).
    implementation("androidx.media3:media3-exoplayer:1.9.4")
    implementation("androidx.media3:media3-exoplayer-hls:1.9.4")
    implementation("androidx.media3:media3-exoplayer-dash:1.9.4")
    implementation("androidx.media3:media3-ui:1.9.4")
    implementation("androidx.media3:media3-common:1.9.4")
    implementation("androidx.media3:media3-session:1.9.4")
    implementation("androidx.media3:media3-transformer:1.9.4")
    // alpha.10 (Scan QR): CameraX 1.4.2 + ZXing, exactly as
    // Mariner (camera-camera2 / camera-lifecycle / camera-view
    // + zxing core 3.5.3). The CAMERA permission is already
    // in the manifest.
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    // Phase 3 downloads: WorkManager for the HLS / DASH /
    // segmented / merge workers (foreground dataSync).
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    // Batch 4: banner swatch from a custom-photo wallpaper
    // (androidx.palette, dominant/vibrant/muted — Mariner
    // StartPage.kt:234-247). palette-ktx pulls in palette.
    implementation("androidx.palette:palette-ktx:1.0.0")
    // Phase 3 (Worker A): FilterEngineTest / UserBlockRulesTest.
    testImplementation("junit:junit:4.13.2")
    // Row F follow-up: DownloadsStoreTest runs the store-backed
    // download helpers (engineFor / locationFor / job bundles)
    // under Robolectric — a plain JVM test cannot build a
    // PioneerStore (context.filesDir + main-looper Handler throw
    // on the android.jar stubs). 4.14.1 is the latest stable;
    // runtime SDK is pinned to 35 in the test class.
    testImplementation("org.robolectric:robolectric:4.14.1")
    // Cast probe only (route discovery logging, no session launch).
    // Same versions as Mariner.
    implementation("com.google.android.gms:play-services-cast-framework:21.5.0")
    implementation("androidx.mediarouter:mediarouter:1.7.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
