// The Glassworks core: the shell's free parts, the UI kit, setup and settings pieces, the web and Manage servers, the screen policy and the Engine API (#21). The parts that still reach the S1 app stay in it until their seams land.
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test

plugins {
    id("com.android.library")
    id("glassworks.android")
}

android {
    namespace = "dev.glassworks.core"
    defaultConfig {
        // The conformance test (src/androidTest) boots the core with a fake frame; CI runs it on emulators at
        // API 26, 29 and 34 (core-emulators.yml, #23).
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

// DeviceNamesTest reads a frame app's own tests and mockups and the shots they save (#502), which no compile task takes
// in: a new Mirror shot, or one drawn from another frame, re-runs this module's tests instead of leaving them up to date.
val frameMockups = fileTree(rootProject.file("frames")) { include("*/android/src/test/**/*.kt") }
tasks.withType<Test>().configureEach {
    inputs.files(frameMockups).withPropertyName("frameMockups").withPathSensitivity(PathSensitivity.RELATIVE)
}

dependencies {
    api(project(":glassworks-pane-api"))
    api(project(":esphome-api-android")) // brings :esphome-api
    implementation(project(":improv"))
    implementation(project(":sendspin")) // WsClient, for Home Assistant's websocket API (HaThreadClient)
    // QR code on the sign-in screen (encoder only, pure Java, Apache-2.0).
    implementation("com.google.zxing:core:3.5.3")
    // Audio playback (TTS, announcements, media player): streams over HTTP in-process, avoiding the
    // vendor CedarX HTTP stack. Same version GeckoView already ships.
    implementation("androidx.media3:media3-exoplayer:1.11.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("org.robolectric:robolectric:4.17")

    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
