// The module build conventions (#282), an included build: settings.gradle.kts includes it in pluginManagement.
// Its own plugin (kotlin-dsl) resolves from here: without this block only the plugin portal, which sends what it
// doesn't hold itself (the Kotlin plugin's jars) to Maven Central.
pluginManagement {
    repositories {
        google()
        // Google's mirror of Maven Central first, then Central: why in the root settings.gradle.kts.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        // Google's mirror of Maven Central first, then Central: why in the root settings.gradle.kts.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "build-logic"
