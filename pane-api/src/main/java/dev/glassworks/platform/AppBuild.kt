/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.platform

/**
 * The app's own build, as its APK module's BuildConfig has it. Code outside the APK module has no
 * BuildConfig to read, so the APK makes this once and hands it to the runtime, which passes it on
 * (to the panes in [dev.glassworks.pane.PaneContext.build]).
 */
data class AppBuild(
    /**
     * The release version, "0.4.3". Updates compare it, and protocol fields send it: Home Assistant's
     * device info and version entity, Improv, Sendspin, user agents. Pages show [shown] instead.
     */
    val versionName: String,
    /** Android's versionCode, derived from [versionName] (docs/releasing.md). */
    val versionCode: Int,
    /** A debug build (the build type's): Gecko remote debugging defaults to on in one. No default, so a new APK can't leave it off by accident. */
    val debug: Boolean,
    /** A test build's commit, "" for a release. A test build reuses its release's [versionName], so it installs over it. */
    val testBuild: String = "",
    /**
     * The app's name as it announces itself: the service's notification, About, Home Assistant's device
     * model and update card, Improv and Sendspin ("Glassworks 0.4.3"). Each app passes its own (#23); the
     * default is the S1's.
     */
    val product: String = PRODUCT,
    /**
     * The small icon of the service's notification, a drawable of the app's that every Android the app runs on
     * loads. The status bar draws only its alpha, so a silhouette: the generic app's is the mark in white (#198). 0 is
     * the app's own icon, as the S1's has always shown.
     */
    val notificationIcon: Int = 0,
) {
    /** The version people see (#263): "0.4.3", or "0.4.3 · test build 28c157d". */
    val shown: String get() = if (testBuild.isEmpty()) versionName else "$versionName · test build $testBuild"

    companion object {
        /** The S1 app's [product]. It was "S1 Kiosk" up to 0.4.3 (#527). */
        const val PRODUCT = "Glassworks"
    }
}
