// Notscape Pioneer — Phase 1 spike.
// Toolchain (2026-11-12): AGP 9.4.1 / Kotlin 2.3.21 / Gradle 9.8.1 /
// compileSdk 36, targetSdk 36. GeckoView 158+ AAR metadata requires
// compileSdk 37+ and AGP 9.1.0+, so the Mariner-era AGP 8.13.2 /
// Kotlin 2.2.20 toolchain cannot build any current stable GeckoView
// (159 tried and rejected by CI on 2026-11-12, then 158 the same
// way). The android-37 platform itself is not yet published to
// Google's public SDK repository, so the spike compiles against SDK
// 36 with the AAR metadata check disabled (see app/build.gradle.kts)
// and re-enables it when the platform ships. No release signing
// here: the spike is debug-signed only. No release key is created
// by this project.
// NOTE: AGP 9.0+ has Kotlin support BUILT IN — do NOT apply
// org.jetbrains.kotlin.android (AGP fails configuration if it is
// present). Only the Compose compiler plugin is still applied
// explicitly. See https://kotl.in/gradle/agp-built-in-kotlin
plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
}
