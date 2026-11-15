pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://maven.mozilla.org/maven2/") {
            content { includeGroup("org.mozilla.geckoview") }
        }
    }
}

rootProject.name = "s1-kiosk"
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

// Glassworks modules (#21): the pane API, the core, engines and panes. The S1 app above depends on them.
include(":glassworks-pane-api")
project(":glassworks-pane-api").projectDir = file("pane-api")
include(":glassworks-core")
project(":glassworks-core").projectDir = file("core")
include(":engine-gecko")
project(":engine-gecko").projectDir = file("engines/engine-gecko")
// The frozen GeckoView for Android 6-7, locked to the panel's Home Assistant (#198), and the S1 app built on it as
// a debug-only test APK (sh.edm.s1kiosk.legacy) until the generic-android frame (#23) replaces it.
include(":engine-gecko-legacy")
project(":engine-gecko-legacy").projectDir = file("engines/engine-gecko-legacy")
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
