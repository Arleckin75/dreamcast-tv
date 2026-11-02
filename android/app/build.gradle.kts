import java.util.Properties

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
    ndkVersion = flutter.ndkVersion

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
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
}

gradle.taskGraph.whenReady {
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

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    // Device video-track compatibility selection (same Media3 version as video_player_android).
    add("hardwareAv1Implementation", "androidx.media3:media3-exoplayer:1.9.2")
    add("hardwareAv1Implementation", "androidx.media3:media3-exoplayer-hls:1.9.2")
    add("hardwareAv1Implementation", "androidx.media3:media3-exoplayer-dash:1.9.2")
    // LibVLC software AV1 fallback for devices without a supported hardware decoder.
    add("softwareAv1Implementation", "org.videolan.android:libvlc-all:3.7.7")
}
