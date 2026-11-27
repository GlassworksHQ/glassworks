// The voice engine for Android: the microphone, wake words (microWakeWord and openWakeWord on TFLite) and the
// Assist client over esphome-api's VoiceAssistantHandler. No Pane API: the app's assist pane wraps it (#152).
// See README.md in this directory.
plugins {
    id("com.android.library")
    id("glassworks.android")
    id("org.jetbrains.dokka")
    id("com.vanniktech.maven.publish") // dev.glassworks:voice, set up in the root build.gradle.kts
}

android {
    namespace = "dev.glassworks.voice"
    defaultConfig {
        minSdk = 23
    }
}

// WakeWordCatalogTest reads the host script, which no compile task takes in: a change to it re-runs the tests.
// glassworks.android declares repo inputs for the Glassworks modules only, not for libs/.
tasks.withType<Test>().configureEach {
    inputs.file(rootProject.file("tools/voice/fetch-models.sh")).withPropertyName("fetchModels")
        .withPathSensitivity(PathSensitivity.NONE)
}

dependencies {
    // VoiceAssistantController implements its VoiceAssistantHandler, and WakeWordCatalog returns its WakeWordInfo.
    api(project(":esphome-api"))
    // Wake word inference, microWakeWord and the legacy openWakeWord (ships every ABI; the APK keeps the one built).
    // LiteRT, TensorFlow Lite's new name: the same org.tensorflow.lite API, and its libtensorflowlite_jni.so is 16 KB
    // page aligned, which TensorFlow Lite 2.16.1's wasn't (#962). Stay on 1.x: LiteRT 2.x needs minSdk 24.
    implementation("com.google.ai.edge.litert:litert:1.4.1")
    // @ChecksSdkIntAtLeast for lint; class-retention, nothing at runtime.
    compileOnly("androidx.annotation:annotation:1.11.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    // VoiceEngineSwitchTest, WakeWordCatalogTest and the README example: the engine on Android under Robolectric.
    testImplementation("org.robolectric:robolectric:4.17")
}
