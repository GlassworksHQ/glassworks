// The module build conventions (#282), an included build: settings.gradle.kts includes it in pluginManagement.
// Its own plugin (kotlin-dsl) resolves from here. Without this block only the plugin portal is asked, and it sends
// what it doesn't hold itself (the Kotlin plugin's jars) to Maven Central.
pluginManagement {
    repositories {
        // kotlin-dsl and its marker are on the plugin portal only, so nothing else is asked for them: when Central
        // answered a CI runner with anything but a 404 (a 429), Gradle gave up on the plugin (#868, 2026-11-25).
        // tools/ci/gradle-without-central.sh checks that the build still configures while Central fails.
        exclusiveContent {
            forRepository { gradlePluginPortal() }
            filter {
                includeGroup("org.gradle.kotlin")
                includeGroup("org.gradle.kotlin.kotlin-dsl")
            }
        }
        google()
        // Google's mirror of Maven Central first, then Central: why in the root settings.gradle.kts.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
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
