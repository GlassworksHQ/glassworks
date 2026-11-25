/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

// Vitro's WebRTC cameras: the WEB_RTC player behind Vitro's camera seam (camera/CameraStream.kt), on libs/ha-webrtc.
// Its own module because libwebrtc adds about 4.6 MB (armeabi-v7a) to 6.3 MB (arm64-v8a) to an APK: an app opts in by
// depending on it and calling VitroWebRtc.register() (the S1 app and the generic app do, with Vitro on). Without it,
// Vitro's cameras play HLS and MJPEG.
plugins {
    id("com.android.library")
    id("glassworks.android")
}

android {
    namespace = "dev.glassworks.engine.vitro.webrtc"
    defaultConfig {
        minSdk = 23
    }
}

dependencies {
    implementation(project(":engine-vitro"))
    implementation(project(":ha-webrtc"))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("org.robolectric:robolectric:4.17")
}
