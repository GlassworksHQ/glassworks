// The module build conventions (#282), an included build: settings.gradle.kts includes it in pluginManagement.

// Where `kotlin-dsl` and its own dependencies (kotlin-stdlib, the Kotlin Gradle plugin) come from. Without this block
// that is the plugin portal alone, and CI failed when the portal answered "not found" for kotlin-stdlib (5 of the 13
// red app runs on 2026-11-18 and 19, #536). Maven Central has the same files; the portal stays for the plugin itself.
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "build-logic"
