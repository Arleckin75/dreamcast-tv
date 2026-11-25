// Generates the app's Baseline Profile on a connected TV (scripts/baseline_profile.ps1).
// Not part of the APK.
plugins {
    id("com.android.test")
}

android {
    namespace = "com.dreamcast.baselineprofile"
    // API 37.1: the Compose libraries ask for at least this minor release.
    compileSdk { version = release(37) { minorApiLevel = 1 } }

    defaultConfig {
        // BaselineProfileRule needs API 28+, and API 33+ without root.
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"
    // Runs in its own process and drives the app from outside, like a viewer with a remote.
    experimentalProperties["android.experimental.self-instrumenting"] = true

    buildTypes {
        // Pairs with the app's "profiling" build type.
        create("profiling") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents {
    beforeVariants(selector().all()) { it.enable = it.buildType == "profiling" }
}

dependencies {
    implementation("androidx.test.ext:junit:1.3.0")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation("androidx.benchmark:benchmark-macro-junit4:1.4.1")
}
