// AGP 9.0+ has built-in Kotlin: do NOT apply org.jetbrains.kotlin.android
// (applying it fails the build in AgpWithBuiltInKotlinAppliedCheck —
// AGP applies the Kotlin Gradle plugin itself). The Compose compiler
// plugin below is still applied explicitly.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.crm.screentranslator"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.crm.screentranslator"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // Keep native libs small: ship only the two ABIs that matter.
        // Most modern phones are arm64-v8a.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    // Per-ABI APKs so each install stays small (no universal fat APK).
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            // ONNX Runtime + LiteRT ship duplicate META-INF files; pick first.
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Built-in Kotlin (AGP 9.0+): configure the Kotlin compiler via the
// kotlin.compilerOptions DSL — android.kotlinOptions was removed in AGP 9.
// The `kotlin` extension is registered by AGP's built-in KGP itself.
kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    // Compose (versions managed by the BOM)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.runtime)
    // Coroutines Play-services bridge (kotlinx.coroutines.tasks.await).
    implementation(libs.kotlinx.coroutines.play.services)

    // On-device inference
    // ONNX Runtime: YOLO11n bubble detector (download-on-demand model).
    implementation(libs.onnxruntime.android)
    // LiteRT: Hayai OCR v2.1 TFLite exports (download-on-demand model).
    // litert-gpu is included so the runtime can probe the GPU delegate;
    // the app always falls back to CPU (XNNPack) when the GPU path fails.
    implementation(libs.litert)
    implementation(libs.litert.gpu)

    // ML Kit — UNBUNDLED Play-services variants: zero APK weight.
    // OCR models and translation models download via Play services on demand.
    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.text.recognition.japanese)
    implementation(libs.mlkit.translate)
    implementation(libs.mlkit.language.id)

    // Networking: model downloads (OkHttp) + LibreTranslate (Retrofit).
    implementation(libs.okhttp)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
}
