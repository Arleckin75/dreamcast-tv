pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google(); mavenCentral()
        maven {
            url = uri("../.tools/libvlc-tv/maven")
            content { includeGroup("tv.dreamcast.libvlc") }
        }
    }
}
rootProject.name = "DreamCastNative"
include(":app")
