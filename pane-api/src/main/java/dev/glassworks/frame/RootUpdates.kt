/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.frame

/**
 * A frame's own way to install app updates without Android's confirmation ([FrameAgent.appUpdates]): the
 * S1's root update service in its patched boot image. The app falls back to PackageInstaller without one, and while
 * the agent doesn't offer [AgentCapability.APP_UPDATES] (a boot image from before the service).
 */
interface RootUpdates {
    /** At start: why the install the service did while this process was replaced failed, or null. */
    fun lastFailure(): String?

    /**
     * Installs the downloaded update, follows it until it ends or [over] says the attempt is over, and
     * gives [installing] each line for the update card. Returns why it failed, or null.
     */
    fun install(version: String, installing: (String) -> Unit, over: () -> Boolean): String?
}
