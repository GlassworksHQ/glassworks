// Vitro's test APK: the native Lovelace engine (:engine-vitro) alone, in a tiny activity with a metrics HUD, for
// Android 6+ (the lululemon Studio Mirror, emulators, tablets). No GeckoView anywhere: the engine's fallback is a
// no-op stub, so no browser runtime is linked or started.
//
// - Debug only, debug-signed, never published. applicationId dev.glassworks.vitro.test, so it never installs over,
//   or updates, another Glassworks app.
//
//   ./gradlew :vitro-test:assembleDebug   → engines/engine-vitro/test-app/build/outputs/apk/debug/test-app-debug.apk
//   adb shell am start -n dev.glassworks.vitro.test/.VitroTestActivity --es url http://127.0.0.1:8089/clarity-ref/typical-nocam
plugins {
    id("com.android.application")
    id("glassworks.android")
}

android {
    namespace = "dev.glassworks.vitro.test"
    defaultConfig {
        applicationId = "dev.glassworks.vitro.test"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-vitro"
    }

    compileOptions {
        // java.time and friends on API 23-25.
        isCoreLibraryDesugaringEnabled = true
    }

    lint {
        // A test bed: the core is written for API 26+, and the real checks come with the frame that ships Vitro.
        checkReleaseBuilds = false
        abortOnError = false
    }
}

// Debug only: Vitro has no release yet.
androidComponents {
    beforeVariants { v -> if (v.buildType == "release") v.enable = false }
}

dependencies {
    implementation(project(":glassworks-core"))
    implementation(project(":engine-vitro"))
    // WebRTC cameras, as the generic app has them with Vitro on (libwebrtc for all four ABIs: the emulators' x86_64 too).
    implementation(project(":engine-vitro-webrtc"))

    // GPL-2.0 with the Classpath exception, like the JDK it backports (AGENTS.md, rule 4): this debug APK is never
    // released, only a test build's artifact, like the legacy test APK's.
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    // CameraProbeTest: the camera end-to-end test's probe (tools/vitro-camera-e2e).
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
}
