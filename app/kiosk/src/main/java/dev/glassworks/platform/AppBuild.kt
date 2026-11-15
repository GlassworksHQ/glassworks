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
    /** A test build's commit, "" for a release. A test build reuses its release's [versionName], so it installs over it. */
    val testBuild: String = "",
    /** A debug build (the build type's): Gecko remote debugging defaults to on in one. */
    val debug: Boolean = false,
) {
    /** The version people see (#263): "0.4.3", or "0.4.3 · test build 28c157d". */
    val shown: String get() = if (testBuild.isEmpty()) versionName else "$versionName · test build $testBuild"
}
