// The diagnostics pane: CPU, temperature, RAM, storage, Wi-Fi and uptime in Home Assistant. See src/main/java/dev/glassworks/diagnostics/README.md.
plugins {
    id("com.android.library")
}

android {
    namespace = "dev.glassworks.panes.diagnostics"
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

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("org.robolectric:robolectric:4.17")
}
