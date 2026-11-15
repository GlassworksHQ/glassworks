// Android helpers for esphome-api: mDNS advertising with NsdManager. See README.md in this directory.
plugins {
    id("com.android.library")
    id("org.jetbrains.dokka")
    id("com.vanniktech.maven.publish") // dev.glassworks:esphome-api-android, set up in the root build.gradle.kts
}

android {
    namespace = "dev.glassworks.esphome.android"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        minSdk = 23
        // The androidTest checks run on an API 23 emulator in CI (pr.yml, libs-api23).
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":esphome-api"))

    testImplementation("junit:junit:4.13.2")

    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
