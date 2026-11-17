// SPDX-License-Identifier: MIT
// Copyright (c) 2026 aguilaair
// Its own Gradle build, apart from the repo's (settings.gradle.kts at the root doesn't include it).
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "mirror-probe"
