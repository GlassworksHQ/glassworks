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

// Reusable libraries extracted from the app (each has its own README).
include(":esphome-api")
project(":esphome-api").projectDir = file("libs/esphome-api")
include(":esphome-api-android")
project(":esphome-api-android").projectDir = file("libs/esphome-api-android")
include(":improv")
project(":improv").projectDir = file("libs/improv")
include(":bluetooth-proxy")
project(":bluetooth-proxy").projectDir = file("libs/bluetooth-proxy")

// Glassworks modules (#21): the pane API, the core, engines and panes. The S1 app above depends on them.
include(":glassworks-pane-api")
project(":glassworks-pane-api").projectDir = file("pane-api")
include(":glassworks-core")
project(":glassworks-core").projectDir = file("core")
include(":engine-gecko")
project(":engine-gecko").projectDir = file("engines/engine-gecko")
include(":pane-diagnostics")
project(":pane-diagnostics").projectDir = file("panes/diagnostics")
include(":pane-bluetooth-proxy")
project(":pane-bluetooth-proxy").projectDir = file("panes/bluetooth-proxy")
