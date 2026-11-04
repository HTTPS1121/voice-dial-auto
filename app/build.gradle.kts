plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.voicedialauto"
    compileSdk = 36

    defaultConfig {
        // v13: ONNX Runtime ships ~18 MB per ABI; keep real-phone ABIs only (no x86 emulators).
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        // NOTE: change applicationId to something unique before uploading to Google Play.
        applicationId = "com.example.voicedialauto"
        minSdk = 26
        targetSdk = 36
        versionCode = 17
        versionName = "0.15-v15"

        // Car app category, injected into the manifest.
        // Default POI: Android Auto shows it for Play-registered installs (see Settings →
        // "הצגה ב-Android Auto"); POI allows MessageTemplate + ListTemplate (all we use).
        // CALLING is the "honest" category but is labs/beta (Play internal/closed tracks only):
        //   ./gradlew assembleDebug -PcarCategory=androidx.car.app.category.CALLING
        manifestPlaceholders["carAppCategory"] =
            (project.findProperty("carCategory") as String?) ?: "androidx.car.app.category.POI"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        lintConfig = file("lint.xml")
    }
    buildFeatures {
        buildConfig = true
        aidl = true
    }
}

kotlin {
    jvmToolchain(17)
}

// v13: compress native libs in the APK (ONNX Runtime is large); extracted at install time.
android.packaging.jniLibs.useLegacyPackaging = true

dependencies {
    implementation(libs.car.app)
    implementation(libs.car.app.projected)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.hiddenapibypass)
    // v13: real VAD — Silero (ONNX Runtime) primary, WebRTC fallback.
    implementation(libs.onnxruntime.android)
    implementation(libs.vad.webrtc)
    testImplementation(libs.junit)
}
