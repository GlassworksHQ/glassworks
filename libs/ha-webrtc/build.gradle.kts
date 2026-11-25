// Home Assistant camera WebRTC for Android: HA's camera/webrtc/* signaling and the stream around it (plain Kotlin,
// any HA WebSocket client plugs in), and a player on libwebrtc. See README.md in this directory.
plugins {
    id("com.android.library")
    id("glassworks.android")
    id("org.jetbrains.dokka")
    id("com.vanniktech.maven.publish") // dev.glassworks:ha-webrtc, set up in the root build.gradle.kts
}

android {
    namespace = "dev.glassworks.hawebrtc"
    defaultConfig {
        minSdk = 23
        // libwebrtc's Java classes are called from its native code by name: an app that shrinks keeps them.
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    // libwebrtc, prebuilt: the webrtc-sdk project's build of Chromium's WebRTC (M150), BSD-3-Clause, with its native
    // library for armeabi-v7a, arm64-v8a, x86 and x86_64, minSdk 21. The "prefixed" build: its Java package is
    // livekit.org.webrtc and its library liblkjingle_peerconnection_so.so, because GeckoView carries WebRTC's own
    // org.webrtc classes and an app with both would have each class twice. Pinned; README.md "libwebrtc" says why
    // this one, and THIRD-PARTY-NOTICES.md lists the code it bundles.
    implementation("io.github.webrtc-sdk:android-prefixed:150.7871.04")
    // @MainThread, @AnyThread for lint; class-retention annotations, nothing at runtime.
    compileOnly("androidx.annotation:annotation:1.11.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("org.robolectric:robolectric:4.17")
}
