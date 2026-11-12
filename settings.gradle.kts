pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // GeckoView is published to Mozilla's Maven repo, NOT Maven Central.
        maven { url = uri("https://maven.mozilla.org/maven2/") }
    }
}
rootProject.name = "NotscapePioneer"
include(":app")
