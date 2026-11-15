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
project(":kiosk").projectDir = file("app/kiosk")

// Reusable libraries extracted from the app (each has its own README).
include(":esphome-api")
project(":esphome-api").projectDir = file("app/libs/esphome-api")
include(":esphome-api-android")
project(":esphome-api-android").projectDir = file("app/libs/esphome-api-android")
include(":improv")
project(":improv").projectDir = file("app/libs/improv")
include(":bluetooth-proxy")
project(":bluetooth-proxy").projectDir = file("app/libs/bluetooth-proxy")
