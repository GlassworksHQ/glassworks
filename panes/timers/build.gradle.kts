// The timers pane: Assist's timers on screen, the dock and the chimes. It rides with the Assist pane.
plugins {
    id("com.android.library")
    id("glassworks.android")
}

android {
    namespace = "dev.glassworks.panes.timers"
}

dependencies {
    implementation(project(":glassworks-pane-api"))
    implementation(project(":glassworks-core"))
    implementation(project(":pane-assist"))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("org.robolectric:robolectric:4.17")
}
