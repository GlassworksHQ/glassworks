// SPDX-License-Identifier: MIT
// Copyright (c) 2026 aguilaair
plugins {
    // the version the repo's root build uses
    id("com.android.application") version "9.3.3"
}

// -PprobeVersion=2 builds the update: same code and key, higher versionCode, so it installs over 1.
val probeVersion = (findProperty("probeVersion") as String?)?.toInt() ?: 1

android {
    // The Mirror pipeline accepts only sh.edm.s1kiosk (image.ts), so the probe borrows S1 Kiosk's id.
    namespace = "sh.edm.s1kiosk"
    compileSdk = 36
    defaultConfig {
        applicationId = "sh.edm.s1kiosk"
        minSdk = 23
        // Android 6 (the Mirror): runtime permissions as API 23 has them, nothing newer to opt out of.
        targetSdk = 23
        versionCode = probeVersion
        versionName = probeVersion.toString()
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            vcsInfo.include = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    lint {
        // targetSdk 23 is the point; Play's target rules don't apply to a test probe
        checkReleaseBuilds = false
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
