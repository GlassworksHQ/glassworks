// Improv Wi-Fi for Android: the protocol and state machines (plain Kotlin) plus a BLE GATT server.
// See README.md in this directory.
plugins {
    id("com.android.library")
    id("glassworks.android")
    id("org.jetbrains.dokka")
    id("com.vanniktech.maven.publish") // dev.glassworks:improv, set up in the root build.gradle.kts
}

android {
    namespace = "dev.glassworks.improv"
    defaultConfig {
        minSdk = 23
        // The androidTest checks run on an API 23 emulator in CI (core-emulators.yml, libs-api23).
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    // @ChecksSdkIntAtLeast and @RequiresApi for lint; class-retention annotations, nothing at runtime.
    compileOnly("androidx.annotation:annotation:1.11.0")

    testImplementation("junit:junit:4.13.2")
    // WifiProvisionerSaveTest: a join through connect on Android 6 and 10, as the other libraries' Robolectric tests.
    testImplementation("org.robolectric:robolectric:4.17")

    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
