plugins { id("com.android.library") }

val upstream = rootProject.file("../.tools/media3-dav1d/media/libraries/decoder_av1")
val verifyDav1d by tasks.registering {
    doLast {
        check(upstream.resolve("src/main/jni/nativelib/arm64-v8a/libdav1d.a").exists() &&
            upstream.resolve("src/main/jni/nativelib/armeabi-v7a/libdav1d.a").exists()) {
            "Run scripts/prepare_media3_dav1d.py --ndk <NDK path> before building the app"
        }
    }
}

android {
    namespace = "androidx.media3.decoder.av1"
    compileSdk = 36
    ndkVersion = "28.2.13676358"
    defaultConfig {
        minSdk = 23
        ndk { abiFilters += setOf("armeabi-v7a", "arm64-v8a") }
        externalNativeBuild { cmake {
            targets += "dav1dJNI"
            // Upstream no longer sets this explicitly; keep both ARM ABIs aligned to 16 KB.
            arguments += "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384"
        } }
        consumerProguardFiles("consumer-rules.pro")
    }
    sourceSets.getByName("main").java.srcDir(upstream.resolve("src/main/java"))
    if (upstream.resolve("src/main/jni/CMakeLists.txt").exists()) externalNativeBuild { cmake {
        path = upstream.resolve("src/main/jni/CMakeLists.txt")
        version = "3.22.1"
    } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
tasks.named("preBuild") { dependsOn(verifyDav1d) }

dependencies {
    implementation("androidx.annotation:annotation:1.9.1")
    api("androidx.media3:media3-decoder:1.11.1")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    compileOnly("org.jetbrains.kotlin:kotlin-annotations-jvm:2.2.20")
    compileOnly("com.google.errorprone:error_prone_annotations:2.36.0")
}
