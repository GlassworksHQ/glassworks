// The Assist pane: wake words (microWakeWord, openWakeWord), the voice pipeline, TTS and the voice overlay.
plugins {
    id("com.android.library")
}

android {
    namespace = "dev.glassworks.panes.assist"
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

// RepoFiles, the tests' way to find files across every module from the repo root (#21).
extensions.configure<com.android.build.api.dsl.LibraryExtension> {
    sourceSets.getByName("test").kotlin.srcDir(rootProject.file("testing/kotlin"))
}

dependencies {
    implementation(project(":glassworks-pane-api"))
    implementation(project(":glassworks-core"))
    api(project(":voice")) // the engine: microphone, wake word, TFLite

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("org.robolectric:robolectric:4.17")
}
