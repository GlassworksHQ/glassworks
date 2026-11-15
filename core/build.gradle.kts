// The Glassworks core: the shell's free parts, the UI kit, setup and settings pieces, the web and Manage servers, the screen policy and the Engine API (#21). The parts that still reach the S1 app stay in it until their seams land.
plugins {
    id("com.android.library")
}

android {
    namespace = "dev.glassworks.core"
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
    api(project(":glassworks-pane-api"))
    api(project(":esphome-api-android")) // brings :esphome-api
    implementation(project(":improv"))
    // QR code on the sign-in screen (encoder only, pure Java, Apache-2.0).
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("org.robolectric:robolectric:4.17")
}
