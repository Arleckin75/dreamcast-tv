import java.util.Properties
import groovy.json.JsonSlurper

plugins {
    id("com.android.application")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

val keystorePropertiesFile = rootProject.file("key.properties")
val keystoreProperties = Properties()
val hasReleaseKeystore = keystorePropertiesFile.exists()
if (hasReleaseKeystore) {
    keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
}

android {
    namespace = "com.dreamcast.app"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = rootProject.file("../.android-ndk-version").readText().trim()

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    defaultConfig {
        applicationId = "com.dreamcast.app"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        minSdk = flutter.minSdkVersion
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    // Flutter may re-populate build-type ABI filters (including x86_64) for
    // Debug builds. Explicitly exclude x86 native libraries at packaging time.
    // Both TV flavors target physical ARM devices, not x86 emulators.
    packaging {
        jniLibs {
            excludes += setOf("**/x86/**", "**/x86_64/**")
        }
    }

    flavorDimensions += "av1"
    productFlavors {
        create("softwareAv1") {
            dimension = "av1"
            // Real Android TV devices: 32-bit ARM (Cosmos) and 64-bit ARM.
            // Exclude x86/x86_64 native binaries from the software player APK.
            ndk {
                abiFilters += setOf("armeabi-v7a", "arm64-v8a")
            }
        }
        create("hardwareAv1") { dimension = "av1" }
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
            }
        }
    }

    buildTypes {
        getByName("debug") {
            // Install alongside the signed v0.6.0 Release without overwriting its data.
            applicationIdSuffix = ".diagnostic"
            versionNameSuffix = "-cosmos-diagnostic"
        }
        release {
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                null
            }
        }
    }
}

gradle.taskGraph.whenReady {
    // Inspect requested targets: Flutter's dependency graph can also contain
    // helper tasks mentioning a different flavor.
    val softwareRequested = gradle.startParameter.taskNames.any { it.contains("SoftwareAv1", ignoreCase = true) }
    val hardwareRequested = gradle.startParameter.taskNames.any { it.contains("HardwareAv1", ignoreCase = true) }
    if (softwareRequested && hardwareRequested) {
        throw GradleException("Build one AV1 flavor at a time using scripts/build_tv.dart.")
    }
    if (!softwareRequested && !hardwareRequested && allTasks.any {
            it.project.path == ":app" && (it.name.startsWith("assemble") || it.name.startsWith("bundle"))
        }) {
        throw GradleException("Specify softwareAv1 or hardwareAv1 using scripts/build_tv.dart.")
    }
    if (softwareRequested || hardwareRequested) {
        val packageConfig = rootProject.file("../.dart_tool/package_config.json")
        val config = JsonSlurper().parse(packageConfig) as Map<*, *>
        val packages = config["packages"] as List<*>
        val videoPackage = packages.filterIsInstance<Map<*, *>>()
            .firstOrNull { it["name"] == "video_player_android" }
        val usesNoopPlayer = videoPackage?.get("rootUri").toString()
            .contains("video_player_android_noop")
        if (softwareRequested != usesNoopPlayer) {
            throw GradleException(
                "Flutter video plugin does not match the Android flavor. " +
                    "Use dart run scripts/build_tv.dart software|hardware debug|release."
            )
        }
    }
    val releaseTaskRequested = allTasks.any {
        it.name.contains("Release", ignoreCase = true) && (it.name.startsWith("assemble") || it.name.startsWith("bundle"))
    }
    if (releaseTaskRequested && !hasReleaseKeystore) {
        throw GradleException(
            "Release signing is not configured. Create android/key.properties " +
                "and keep the keystore file outside git."
        )
    }
}

flutter {
    source = "../.."
}

repositories {
    maven {
        name = "dreamcastLibVlcTv"
        url = uri(rootProject.file("../.tools/libvlc-tv/maven"))
        content { includeGroup("tv.dreamcast.libvlc") }
    }
}

val libvlcTvVersions = Properties().apply {
    rootProject.file("../third_party/libvlc_tv/versions.env").inputStream().use { load(it) }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    // Device video-track compatibility selection (same Media3 version as video_player_android).
    add("hardwareAv1Implementation", "androidx.media3:media3-exoplayer:1.9.2")
    add("hardwareAv1Implementation", "androidx.media3:media3-exoplayer-hls:1.9.2")
    add("hardwareAv1Implementation", "androidx.media3:media3-exoplayer-dash:1.9.2")
    // LibVLC software AV1 fallback for devices without a supported hardware decoder.
    // Produced by build-libvlc-tv.yml. No fallback to the unchanged full AAR.
    add("softwareAv1Implementation", "tv.dreamcast.libvlc:libvlc-tv:${libvlcTvVersions.getProperty("TV_VERSION")}")
}
