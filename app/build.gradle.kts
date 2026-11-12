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
        versionCode = 2
        versionName = "0.1.0-alpha.2"
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
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("org.mozilla.geckoview:geckoview:$geckoviewVersion")
    // Cast probe only (route discovery logging, no session launch).
    // Same versions as Mariner.
    implementation("com.google.android.gms:play-services-cast-framework:21.5.0")
    implementation("androidx.mediarouter:mediarouter:1.7.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
