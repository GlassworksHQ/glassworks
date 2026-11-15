// The voice engine for Android: the microphone, wake words (microWakeWord and openWakeWord on TFLite) and the
// Assist client over esphome-api's VoiceAssistantHandler. No Pane API: the app's assist pane wraps it (#152).
// See README.md in this directory.
plugins {
    id("com.android.library")
    id("org.jetbrains.dokka")
    id("com.vanniktech.maven.publish") // dev.glassworks:voice, set up in the root build.gradle.kts
}

android {
    namespace = "dev.glassworks.voice"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        minSdk = 23
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// WakeWordCatalogTest reads the host script, which no compile task takes in: a change to it re-runs the tests.
// The root build declares repo inputs for the Glassworks modules only, not for libs/.
tasks.withType<Test>().configureEach {
    inputs.file(rootProject.file("tools/voice/fetch-models.sh")).withPropertyName("fetchModels")
        .withPathSensitivity(PathSensitivity.NONE)
}

dependencies {
    // VoiceAssistantController implements its VoiceAssistantHandler, and WakeWordCatalog returns its WakeWordInfo.
    api(project(":esphome-api"))
    // Wake word inference, microWakeWord and the legacy openWakeWord (ships every ABI; the APK keeps the one built).
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    // @ChecksSdkIntAtLeast for lint; class-retention, nothing at runtime.
    compileOnly("androidx.annotation:annotation:1.11.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    // VoiceEngineSwitchTest, WakeWordCatalogTest and the README example: the engine on Android under Robolectric.
    testImplementation("org.robolectric:robolectric:4.17")
}
