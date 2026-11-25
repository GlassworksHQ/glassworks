// SPDX-License-Identifier: MIT
// Copyright (c) 2026 aguilaair
// Its own Gradle build, apart from the repo's (settings.gradle.kts at the root doesn't include it).
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
    }
}
rootProject.name = "mirror-probe"
