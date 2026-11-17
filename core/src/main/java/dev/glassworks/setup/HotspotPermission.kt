/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.setup

import android.Manifest
import dev.glassworks.platform.PlatformApi

/**
 * What the setup screen does about Android's permission before it asks for the hotspot (#197). The
 * screen always says why before Android's own dialog shows (the maintainer's call, 2026-11-13), and says
 * where to allow it once Android won't ask again.
 *
 * Android's `shouldShowRequestPermissionRationale` is false both before the first ask and after "Don't ask
 * again", so the screen keeps [askedBefore] itself to tell the two apart.
 */
object HotspotPermission {
    sealed interface Step {
        /** Nothing to ask: start the hotspot. */
        data object Ready : Step

        /** Show [why], with a button that asks Android for [permission]. */
        data class Explain(val permission: String, val why: String) : Step

        /** Android won't ask again: [why] says where to allow it. */
        data class Blocked(val permission: String, val why: String) : Step
    }

    fun step(platform: PlatformApi, granted: (String) -> Boolean, askedBefore: Boolean, androidWouldExplain: (String) -> Boolean): Step {
        val p = platform.hotspotPermission ?: return Step.Ready
        if (granted(p)) return Step.Ready
        return if (!askedBefore || androidWouldExplain(p)) Step.Explain(p, explain(p)) else Step.Blocked(p, blocked(p))
    }

    private fun explain(p: String) = when (p) {
        Manifest.permission.NEARBY_WIFI_DEVICES ->
            "To open its own Wi-Fi for setup, the panel needs Android's “Nearby devices” permission. It doesn't use it to find where you are."
        else -> "To open its own Wi-Fi for setup, Android needs the panel to have the location permission. The panel doesn't use your location."
    }

    private fun blocked(p: String) = when (p) {
        Manifest.permission.NEARBY_WIFI_DEVICES -> "Android won't ask again. Allow “Nearby devices” for this app in Android's settings, under Apps."
        else -> "Android won't ask again. Allow “Location” for this app in Android's settings, under Apps."
    }
}
