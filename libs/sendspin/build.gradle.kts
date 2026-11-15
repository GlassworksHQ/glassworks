// The Sendspin player engine (protocol, Noise PSK handshake, FLAC, playback scheduling), out of the
// sendspin pane (#152). No Pane API: the app's sendspin pane wraps it.
plugins {
    id("com.android.library")
}

android {
    namespace = "dev.glassworks.sendspin"
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
    api(project(":esphome-api")) // NoiseCipher, and Bouncy Castle's X25519

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
