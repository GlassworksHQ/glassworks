// The S1 Plus wall base pane: the base's relays, buttons and sensors over its UART, and the Aqara cloud link (#21).
plugins {
    id("com.android.library")
    id("glassworks.android")
}

android {
    namespace = "dev.glassworks.frames.s1.wallbase"
}

dependencies {
    implementation(project(":glassworks-pane-api"))
    implementation(project(":glassworks-core"))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("org.robolectric:robolectric:4.17")
}
