// The Sendspin player engine for Android: the protocol, the Noise PSK handshake, FLAC and playback in
// sync with Music Assistant. No Pane API: the app's sendspin pane wraps it (#152). See README.md here.
plugins {
    id("com.android.library")
    id("org.jetbrains.dokka")
    id("com.vanniktech.maven.publish") // dev.glassworks:sendspin, set up in the root build.gradle.kts
}

android {
    namespace = "dev.glassworks.sendspin"
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

dependencies {
    implementation(project(":esphome-api")) // NoiseCipher and Bouncy Castle's X25519, inside the library only
    // @ChecksSdkIntAtLeast for lint; class-retention, nothing at runtime.
    compileOnly("androidx.annotation:annotation:1.11.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    // SendspinRunsTest and the README example: the player on Android, as the kiosk's own Robolectric tests.
    testImplementation("org.robolectric:robolectric:4.17")
}
