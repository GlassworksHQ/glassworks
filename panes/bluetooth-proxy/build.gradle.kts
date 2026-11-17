// The Bluetooth proxy pane, on the bluetooth-proxy library. See src/main/java/dev/glassworks/bluetoothproxy/README.md.
plugins {
    id("com.android.library")
    id("glassworks.android")
}

android {
    namespace = "dev.glassworks.panes.bluetoothproxy"
}

dependencies {
    implementation(project(":glassworks-pane-api"))
    implementation(project(":glassworks-core"))
    implementation(project(":bluetooth-proxy"))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("org.robolectric:robolectric:4.17")
}
