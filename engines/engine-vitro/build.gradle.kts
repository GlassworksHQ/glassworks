/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

// Vitro: experimental native Android Lovelace rendering engine for Glassworks.
// Runs directly on Android 6+ (minSdk 23, including the lululemon Studio Mirror) with zero GeckoView
// dependency of its own; an optional fallback Engine handles non-dashboard pages and unknown custom cards.
plugins {
    id("com.android.library")
    id("glassworks.android")
}

android {
    namespace = "dev.glassworks.engine.vitro"
    defaultConfig {
        minSdk = 23
    }
}

dependencies {
    api(project(":glassworks-core"))
    implementation(project(":sendspin")) // WsClient for HA WebSocket data plane

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("org.robolectric:robolectric:4.17")
}
