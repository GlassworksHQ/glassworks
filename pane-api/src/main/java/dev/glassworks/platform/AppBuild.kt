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
     * model and update card, Improv and Sendspin ("S1 Kiosk 0.4.3"). Each app passes its own (#23); the
     * default is the S1's, which every S1 build and its tests have always sent.
     */
    val product: String = S1_KIOSK,
) {
    /** The version people see (#263): "0.4.3", or "0.4.3 · test build 28c157d". */
    val shown: String get() = if (testBuild.isEmpty()) versionName else "$versionName · test build $testBuild"

    companion object {
        /** The S1 app's [product]. */
        const val S1_KIOSK = "S1 Kiosk"
    }
}
