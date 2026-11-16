// The Zigbee/Thread radio pane: the S1's Silicon Labs radio as a Zigbee coordinator or Thread border router.
plugins {
    id("com.android.library")
    id("glassworks.android")
}

android {
    namespace = "dev.glassworks.panes.radio"
}

dependencies {
    implementation(project(":glassworks-pane-api"))
    implementation(project(":glassworks-core"))
    // XZ decoder (pure Java, 0BSD) for Silicon Labs' zigbeed .deb (radio/SisdkZigbeed.kt).
    implementation("org.tukaani:xz:1.10")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("org.robolectric:robolectric:4.17")
}
