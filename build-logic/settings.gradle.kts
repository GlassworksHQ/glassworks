// The module build conventions (#282), an included build: settings.gradle.kts includes it in pluginManagement.
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "build-logic"
