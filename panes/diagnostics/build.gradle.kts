// The diagnostics pane: CPU, temperature, RAM, storage, Wi-Fi and uptime in Home Assistant. See src/main/java/dev/glassworks/diagnostics/README.md.
plugins {
    id("com.android.library")
    id("glassworks.android")
}

android {
    namespace = "dev.glassworks.panes.diagnostics"
}

dependencies {
    implementation(project(":glassworks-pane-api"))
    implementation(project(":glassworks-core"))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("org.robolectric:robolectric:4.17")
}
