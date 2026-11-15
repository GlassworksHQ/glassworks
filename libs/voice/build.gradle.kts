// The voice engine (microphone, wake word with microWakeWord and openWakeWord on TFLite, the Assist client),
// out of the assist pane (#152). No Pane API: the app's assist pane wraps it.
plugins {
    id("com.android.library")
}

android {
    namespace = "dev.glassworks.voice"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Wake word inference, microWakeWord and the legacy openWakeWord (ships every ABI; the APK keeps the one built).
    implementation("org.tensorflow:tensorflow-lite:2.16.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
