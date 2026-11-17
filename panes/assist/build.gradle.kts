// The Assist pane: wake words (microWakeWord, openWakeWord), the voice pipeline, TTS and the voice overlay.
plugins {
    id("com.android.library")
    id("glassworks.android")
}

android {
    namespace = "dev.glassworks.panes.assist"
}

dependencies {
    implementation(project(":glassworks-pane-api"))
    implementation(project(":glassworks-core"))
    api(project(":voice")) // the engine: microphone, wake word, TFLite

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("org.robolectric:robolectric:4.17")
}
