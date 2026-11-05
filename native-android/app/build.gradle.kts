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

android {
    namespace = "com.dreamcast.nativeapp"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.dreamcast.app"
        minSdk = 23
        targetSdk = 36
        versionCode = 901001
        versionName = "0.9.1"
        buildConfigField("String", "SOURCE_REVISION", "\"$sourceRevision\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    flavorDimensions += "av1"
    productFlavors {
        create("hardwareAv1") { dimension = "av1" }
        create("softwareAv1") {
            dimension = "av1"
            ndk { abiFilters += setOf("armeabi-v7a", "arm64-v8a") }
        }
    }
    sourceSets {
        getByName("main").java.srcDir(layout.buildDirectory.dir("generated/playerControls"))
        getByName("main").res.srcDir("../../android/app/src/main/res")
        getByName("main").assets.srcDir("../../assets")
        getByName("test").resources.srcDir("../../test/features/releases/fixtures")
        getByName("softwareAv1").java.srcDir(layout.buildDirectory.dir("generated/vlc"))
    }
    buildTypes {
        debug { applicationIdSuffix = providers.gradleProperty("nativeDebugSuffix").getOrElse(".kotlin") }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val signingFile = rootProject.file("../android/key.properties")
            if (signingFile.exists()) {
                val props = Properties().apply { signingFile.inputStream().use { load(it) } }
                signingConfig = signingConfigs.create("production") {
                    keyAlias = props.getProperty("keyAlias")
                    keyPassword = props.getProperty("keyPassword")
                    val configured=File(props.getProperty("storeFile"))
                    storeFile = if(configured.isAbsolute)configured else rootProject.file("../android/${props.getProperty("storeFile")}")
                    storePassword = props.getProperty("storePassword")
                }
            }
        }
    }
    testOptions { unitTests.isReturnDefaultValues = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

// Resolve the API 28 test runtime and Robolectric 4.14.1's API 35 resource runtime
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

val copyVlcSources by tasks.registering(Sync::class) {
    from("../../android/app/src/softwareAv1/kotlin") {
        include("**/CosmosAv1PlayerActivity.kt", "**/CosmosFmp4Resolver.kt")
    }
    into(layout.buildDirectory.dir("generated/vlc"))
}
val copyPlayerControls by tasks.registering(Sync::class) {
    from("../../android/app/src/softwareAv1/kotlin") { include("**/SoftwarePlayerControls.kt") }
    into(layout.buildDirectory.dir("generated/playerControls"))
}
tasks.named("preBuild") { dependsOn(copyVlcSources, copyPlayerControls) }

val vlc = Properties().apply {
    rootProject.file("../third_party/libvlc_tv/versions.env").inputStream().use { load(it) }
}
dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation(platform("androidx.compose:compose-bom:2025.08.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.tv:tv-material:1.0.1")
    implementation("androidx.work:work-runtime-ktx:2.10.3")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("io.coil-kt:coil-compose:2.7.0")
    add("hardwareAv1Implementation", "androidx.media3:media3-exoplayer:1.9.2")
    add("hardwareAv1Implementation", "androidx.media3:media3-exoplayer-hls:1.9.2")
    add("hardwareAv1Implementation", "androidx.media3:media3-exoplayer-dash:1.9.2")
    add("hardwareAv1Implementation", "androidx.media3:media3-ui:1.9.2")
    add("softwareAv1Implementation", "tv.dreamcast.libvlc:libvlc-tv:${vlc.getProperty("TV_VERSION")}")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.work:work-testing:2.10.3")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.08.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
