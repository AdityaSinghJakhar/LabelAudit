plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.labelguard.app"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.labelguard.app"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Candidate backend addresses, tried in order at runtime; the first
        // one that answers /api/health is used for the rest of the session.
        //   - a LAN address works untethered over Wi-Fi
        //   - 127.0.0.1 works when forwarded: adb reverse tcp:8000 tcp:8000
        //   - 10.0.2.2 is the host loopback as seen from the emulator
        // Override the whole list with:
        //   -PapiBaseUrl=http://192.168.1.20:8000/
        val candidates = (project.findProperty("apiBaseUrl") as String?)
            ?.split(",")
            ?: listOf(
                "http://10.194.102.96:8000/",
                "http://127.0.0.1:8000/",
                "http://10.0.2.2:8000/",
            )
        buildConfigField(
            "String",
            "API_BASE_URLS",
            "\"${candidates.joinToString(",") { it.trim() }}\""
        )

        // OpenCV and ML Kit both ship native libraries per ABI, and shipping
        // all four pushed the APK past 200 MB. x86/x86_64 exist only for
        // emulators, so real devices are covered by the two ARM ABIs.
        // Add x86_64 back if you need to run on an emulator.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.accompanist.permissions)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.text.recognition.devanagari)
    implementation(libs.snakeyaml)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}