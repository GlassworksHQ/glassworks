// The Gecko engine: GeckoView behind the core's Engine API, and the web extension (assets/extension) that runs in the dashboard page (#21).
plugins {
    id("com.android.library")
    id("glassworks.android")
}

android {
    namespace = "dev.glassworks.engine.gecko"
}

// The same ABI as the APK (root build.gradle.kts): each per-ABI GeckoView AAR has its own classes.jar.
val s1Abi: String by rootProject.extra

dependencies {
    api(project(":glassworks-core"))
    api("org.mozilla.geckoview:geckoview-$s1Abi:159.0.20261102144711") {
        // Proprietary Google Play services (play-services-fido/-base/-basement/-tasks), used only for
        // WebAuthn/passkeys. The panel has no Play services, so they could never run; excluding them
        // keeps the APK free software (as Firefox's F-Droid builds do). HA's login doesn't use WebAuthn.
        exclude(group = "com.google.android.gms")
    }

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("org.robolectric:robolectric:4.17")
}
