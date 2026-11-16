// The Sendspin pane: a synchronized audio player for Music Assistant's Sendspin protocol.
plugins {
    id("com.android.library")
    id("glassworks.android")
}

android {
    namespace = "dev.glassworks.panes.sendspin"
}

dependencies {
    implementation(project(":glassworks-pane-api"))
    implementation(project(":glassworks-core"))
    api(project(":sendspin")) // the engine; SendspinPane.player exposes it

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("org.robolectric:robolectric:4.17")
}
