pluginManagement {
    // The module build conventions, glassworks.android and glassworks.jvm (#282).
    includeBuild("build-logic")
    repositories {
        google()
        // Google's mirror of Maven Central first, Central itself after it: Central answers bursts with 429 (too many
        // requests), which failed CI's downloads. Gradle picks a repository per module: it moves on to Central only
        // when the mirror doesn't have a module's metadata (a 404), then takes all of that module's files from where
        // it found it. Any other error from the mirror fails the build (docs/ci.md, Caches).
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Google's mirror of Maven Central first, then Central (why: the comment in pluginManagement above).
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        // GeckoView comes from Mozilla's repository only. Without exclusiveContent Gradle asks Google and Maven
        // Central for it first, and any error there other than a 404 drops GeckoView from the build (2026-11-17).
        exclusiveContent {
            forRepository { maven("https://maven.mozilla.org/maven2/") }
            filter { includeGroup("org.mozilla.geckoview") }
        }
    }
}

rootProject.name = "glassworks"
include(":kiosk")
project(":kiosk").projectDir = file("frames/aqara-s1-plus/android")
// Glassworks for generic Android (#23): its own app, dev.glassworks.generic_android.
include(":generic-android")
project(":generic-android").projectDir = file("frames/generic-android/android")

// Reusable libraries extracted from the app (each has its own README).
include(":esphome-api")
project(":esphome-api").projectDir = file("libs/esphome-api")
include(":esphome-api-android")
project(":esphome-api-android").projectDir = file("libs/esphome-api-android")
include(":improv")
project(":improv").projectDir = file("libs/improv")
include(":bluetooth-proxy")
project(":bluetooth-proxy").projectDir = file("libs/bluetooth-proxy")
include(":sendspin")
project(":sendspin").projectDir = file("libs/sendspin")
include(":voice")
project(":voice").projectDir = file("libs/voice")
include(":ha-webrtc")
project(":ha-webrtc").projectDir = file("libs/ha-webrtc")

// Glassworks modules (#21): the pane API, the core, engines and panes. The S1 app above depends on them.
include(":glassworks-pane-api")
project(":glassworks-pane-api").projectDir = file("pane-api")
include(":glassworks-core")
project(":glassworks-core").projectDir = file("core")
include(":engine-gecko")
project(":engine-gecko").projectDir = file("engines/engine-gecko")
// The frozen GeckoView for Android 6-7, locked to the panel's Home Assistant (#198), and the S1 app built on it as
// a debug-only test APK (dev.glassworks.s1.legacy) until the generic-android frame (#23) replaces it.
include(":engine-gecko-legacy")
project(":engine-gecko-legacy").projectDir = file("engines/engine-gecko-legacy")
include(":engine-vitro")
project(":engine-vitro").projectDir = file("engines/engine-vitro")
// Vitro's WebRTC cameras on libs/ha-webrtc, for the apps that register them (libwebrtc is big).
include(":engine-vitro-webrtc")
project(":engine-vitro-webrtc").projectDir = file("engines/engine-vitro-webrtc")
// The engine contract on emulators (docs/engine-contract.md): test code only, nothing ships.
include(":engine-conformance")
project(":engine-conformance").projectDir = file("engines/conformance")
include(":vitro-test")
project(":vitro-test").projectDir = file("engines/engine-vitro/test-app")
include(":kiosk-legacy")
project(":kiosk-legacy").projectDir = file("engines/engine-gecko-legacy/test-app")
include(":pane-diagnostics")
project(":pane-diagnostics").projectDir = file("panes/diagnostics")
include(":pane-bluetooth-proxy")
project(":pane-bluetooth-proxy").projectDir = file("panes/bluetooth-proxy")
include(":pane-camera")
project(":pane-camera").projectDir = file("panes/camera")
include(":pane-notifications")
project(":pane-notifications").projectDir = file("panes/notifications")
include(":pane-assist")
project(":pane-assist").projectDir = file("panes/assist")
include(":pane-timers")
project(":pane-timers").projectDir = file("panes/timers")
include(":pane-sendspin")
project(":pane-sendspin").projectDir = file("panes/sendspin")
include(":aqara-s1-plus-wall-base")
project(":aqara-s1-plus-wall-base").projectDir = file("frames/aqara-s1-plus/panes/wall-base")
include(":pane-zigbee-thread")
project(":pane-zigbee-thread").projectDir = file("panes/zigbee-thread")
