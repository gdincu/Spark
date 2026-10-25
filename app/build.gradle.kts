plugins {
    alias(libs.plugins.android.app)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.spark.assistant"
    compileSdk = 37
    // Must match the NDK installed in CI (see .github/workflows/android.yml).
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.spark.assistant"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                // arm64-v8a only (per project scope). Other ABIs build
                // with a stub .so; NeedleBridge reports nativeAvailable=false
                // and VoiceService refuses to start there.
                abiFilters += listOf("arm64-v8a")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin {
        jvmToolchain(21)
    }
}

dependencies {
    implementation(libs.core)
    implementation(libs.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.lifecycle.runtime)
    implementation(libs.viewmodel.compose)
    implementation(libs.datastore)
    implementation(libs.coroutines)
}
