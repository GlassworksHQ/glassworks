// The Glassworks pane API: Pane, Frame, Platform and the types every pane and frame share. See src/main/java/dev/glassworks/README.md.
plugins {
    id("com.android.library")
    id("glassworks.android")
}

android {
    namespace = "dev.glassworks.paneapi"
}

dependencies {
    api(project(":esphome-api"))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("org.robolectric:robolectric:4.17")
    // HttpsTrustTest: made-up CAs and server certificates, generated per run (bcprov comes with esphome-api).
    testImplementation("org.bouncycastle:bcpkix-jdk18on:1.80")
}
