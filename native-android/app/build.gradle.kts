import java.util.Properties
import java.io.File
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Identify the actual sources even when several debug APKs share a version number.
val sourceRevision = providers.environmentVariable("GITHUB_SHA").orElse(provider {
    runCatching {
        val revision = providers.exec {
            workingDir(rootProject.projectDir.parentFile)
            commandLine("git", "rev-parse", "--short=8", "HEAD")
        }.standardOutput.asText.get().trim()
        val changes = providers.exec {
            workingDir(rootProject.projectDir.parentFile)
            commandLine("git", "status", "--porcelain", "--untracked-files=no")
        }.standardOutput.asText.get()
        revision + if (changes.isNotBlank()) "-local" else ""
    }.getOrDefault("local")
}).get().replace(Regex("[^A-Za-z0-9._-]"), "").take(40)

// Release numbers come from git tags, see scripts/app_version.py: only the release workflow
// takes the next number and passes it as -PappVersion. Every other build reuses the latest
// release tag and is marked "-dev" (release) or "-debug" (debug).
val releaseVersionPattern = Regex("""^(\d+)\.(\d)\.(\d)$""")
val requestedAppVersion = providers.gradleProperty("appVersion").orNull?.trim()?.takeIf { it.isNotEmpty() }
val latestReleaseTag = runCatching {
    providers.exec {
        workingDir(rootProject.projectDir.parentFile)
        commandLine("git", "tag", "--list", "v*")
    }.standardOutput.asText.get()
        .lines()
        .mapNotNull { Regex("""^v(\d+)\.(\d+)\.(\d+)$""").find(it.trim())?.destructured }
        .map { (a, b, c) -> listOf(a.toInt(), b.toInt(), c.toInt()) }
        .maxWithOrNull(compareBy<List<Int>>({ it[0] }, { it[1] }, { it[2] }))
        ?.joinToString(".")
}.getOrNull() ?: "0.0.0"
val appVersionName = requestedAppVersion ?: latestReleaseTag
val appVersionCode = releaseVersionPattern.find(appVersionName)?.destructured
    ?.let { (major, minor, patch) -> major.toInt() * 1_000_000 + minor.toInt() * 100_000 + patch.toInt() * 1000 + 1 }
    ?: error("appVersion must be X.Y.Z with Y and Z in 0-9, got '$appVersionName'")

android {
    namespace = "com.dreamcast.nativeapp"
    ndkVersion = "28.2.13676358"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.dreamcast.app"
        minSdk = 23
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "SOURCE_REVISION", "\"$sourceRevision\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += setOf("armeabi-v7a", "arm64-v8a") }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    sourceSets {
        getByName("main").assets.srcDir("../../assets")
    }
    buildTypes {
        debug {
            applicationIdSuffix = providers.gradleProperty("nativeDebugSuffix").getOrElse(".kotlin")
            versionNameSuffix = "-debug"
        }
        release {
            // Only the release workflow passes appVersion; other release builds are not releases.
            if (requestedAppVersion == null) versionNameSuffix = "-dev"
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val signingFile = rootProject.file("key.properties")
            if (!signingFile.exists() &&
                providers.gradleProperty("releaseDebugSigning").orNull == "true"
            ) {
                // CI on develop: an installable minified build to catch R8 problems.
                signingConfig = signingConfigs.getByName("debug")
            }
            if (signingFile.exists()) {
                val props = Properties().apply { signingFile.inputStream().use { load(it) } }
                signingConfig = signingConfigs.create("production") {
                    keyAlias = props.getProperty("keyAlias")
                    keyPassword = props.getProperty("keyPassword")
                    val configured=File(props.getProperty("storeFile"))
                    storeFile = if(configured.isAbsolute)configured else rootProject.file(props.getProperty("storeFile"))
                    storePassword = props.getProperty("storePassword")
                }
            }
        }
        // Baseline Profile generation only (:baselineprofile): release code without R8, so the
        // profile names real classes; R8 maps them in release builds. Its own application ID and
        // the debug key let it install next to a published release on the TV.
        create("profiling") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
            applicationIdSuffix = ".profiling"
            versionNameSuffix = "-profiling"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

// Resolve the API 28 test runtime and Robolectric's API 35 resource runtime
// through Gradle's cache, rather than Robolectric's separate HTTP fetcher.
val robolectricAndroid by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}
// Separate configurations keep Gradle from selecting only the newer version
// of the same module: Robolectric needs both Android releases at runtime.
val robolectricResourceAndroid by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}
dependencies {
    add(robolectricAndroid.name, "org.robolectric:android-all-instrumented:9-robolectric-4913185-2-i7")
    add(robolectricResourceAndroid.name, "org.robolectric:android-all-instrumented:15-robolectric-12650502-i7")
}
val prepareRobolectricAndroid by tasks.registering(Sync::class) {
    from(robolectricAndroid, robolectricResourceAndroid)
    into(layout.buildDirectory.dir("robolectric-android"))
}
tasks.withType<Test>().configureEach {
    dependsOn(prepareRobolectricAndroid)
    systemProperty("robolectric.dependency.dir", layout.buildDirectory.dir("robolectric-android").get().asFile.absolutePath)
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.tv:tv-material:1.1.0")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("androidx.core:core-ktx:1.18.0")
    // Installs src/main/baseline-prof.txt on sideloaded installs, where no app store does it.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    implementation("org.jsoup:jsoup:1.23.2")
    constraints {
        implementation("com.google.guava:guava:33.7.2-android") {
            because("Fix GHSA-xxph-c9ww-hj94 in the transitive Android Guava dependency")
        }
    }
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation( "androidx.media3:media3-exoplayer:1.11.1")
    implementation( "androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation( "androidx.media3:media3-exoplayer-dash:1.11.1")
    implementation( "androidx.media3:media3-ui:1.11.1")
    implementation("androidx.media3:media3-inspector:1.11.1")
    implementation(project(":dav1d"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.4.0")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.work:work-testing:2.11.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.06.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:5.4.0")
}
