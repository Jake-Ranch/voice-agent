plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.jakeranch.voiceagent"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.jakeranch.voiceagent"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.3.1"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        jniLibs { pickFirsts += listOf("**/libc++_shared.so") }
    }
}

dependencies {
    // 本地唤醒词 + 识别 + 端点检测（CI 下载到 libs/）
    implementation(files("libs/sherpa-onnx.aar"))

    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
