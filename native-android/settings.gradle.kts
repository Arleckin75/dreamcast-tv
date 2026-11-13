pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google(); mavenCentral()
    }
}
rootProject.name = "DreamCastNative"
include(":app")
include(":dav1d")
// Generates app/src/main/baseline-prof.txt on a connected TV; not part of the APK.
include(":baselineprofile")
