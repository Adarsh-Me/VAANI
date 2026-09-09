import java.util.Properties
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}
kapt {
    arguments {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

android {
    namespace = "com.itantra"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.itantra"
        minSdk = 26 // Android 8.0 per PRD (NNAPI paths need 27+; guarded at runtime)
        targetSdk = 34
        versionCode = 3
        versionName = "1.2-demo" // SraVaani STT bundled in APK: fully offline first launch

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        ndk {
            // arm64-v8a: real devices. x86_64: emulator verification of the
            // sherpa-onnx TTS path. Strip x86_64 for a store release.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    // Release signing reads the machine-local keystore (local.properties,
    // gitignored). Unsigned release fails at packageRelease by design.
    val localProps = Properties().apply {
        rootProject.file("local.properties").takeIf { it.exists() }
            ?.inputStream()?.use(::load)
    }
    val releaseStore = localProps.getProperty("itantra.storeFile")?.let(::file)
        ?.takeIf { it.exists() }
    if (releaseStore != null) {
        signingConfigs {
            create("release") {
                storeFile = releaseStore
                storePassword = localProps.getProperty("itantra.storePassword")
                keyAlias = localProps.getProperty("itantra.keyAlias")
                keyPassword = localProps.getProperty("itantra.keyPassword")
            }
        }
    }
    buildTypes {
        release {
            releaseStore?.let { signingConfig = signingConfigs.getByName("release") }
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14" // matches Kotlin 1.9.24
    }
    // SraVaani weights ship bundled in assets/savaani/. Store .onnx uncompressed:
    // quantized weights are high-entropy (deflate gains ~0), and stored assets
    // are stream-copyable to filesDir at first launch with zero inflate cost.
    androidResources {
        noCompress += "onnx"
        noCompress += "flitevox"
    }
    // Flite TTS engine (Hear2Read Indic flite fork + happyalu JNI) built
    // from tools/flite-src + tools/flite-android via NDK r28 clang.
    // Outputs libttsflite.so â€” loaded by ml/FliteEngine.kt.
    externalNativeBuild {
        cmake {
            path = file("../tools/flite-android/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // --- UI (versions per Implementation.md Â§1.3) ---
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-text-google-fonts")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.core:core-ktx:1.13.1")

    // --- Persistence ---
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    // --- Concurrency (per Implementation.md Â§1.3) ---
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // --- ML runtime: 1.29.0 (was 1.17.0). The IndicTrans2 encoder stub needs
    // ai.onnx.ml opset 5, which the 1.17 line rejects (ceiling was opset 4).
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.29.0")

    // --- Model downloads ---
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // --- Voice tarball extraction (tts models ship as .tar.bz2 with espeak-ng-data/) ---
    implementation("org.apache.commons:commons-compress:1.25.0")

    // --- JSON (packet codec uses org.json; no extra dep needed, ships with Android) ---

    // NOTE (deliberate deviations from Implementation.md Â§1.3):
    // - androidx.bluetooth alpha02 dropped: artifact is experimental; framework
    //   android.bluetooth LE APIs used directly (BLEServer/BLEClient).
    // - oboe dropped: AudioRecord/AudioTrack cover the 16kHz mono path with no
    //   NDK/CMake toolchain requirement. Revisit if sub-50ms latency is missed.

    // --- Tests ---
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240205")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("org.mockito:mockito-core:5.11.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
