plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.evc.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.evc.app"
        minSdk = 24
        targetSdk = 35
        // This release targets only 32-bit ARM devices to reduce APK size.
        ndk {
            abiFilters += listOf("armeabi-v7a")
        }
        versionCode = 383
        versionName = "0.2.183"
    }

    buildTypes {
        getByName("debug") {
            // Stability hotfix: keep FFmpegKit JNI/native bindings and Media3 classes intact.
            // Disable shrinking until the reported button-triggered app exits are diagnosed.
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.media3:media3-transformer:1.6.1")
    implementation("androidx.media3:media3-effect:1.6.1")
    implementation("androidx.media3:media3-common:1.6.1")
    // FFmpeg muxing enables Matroska (MKV) output while copying existing streams.
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-full:6.0.4")
}
