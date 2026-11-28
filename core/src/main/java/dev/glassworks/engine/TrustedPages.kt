/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine

import dev.glassworks.auth.FrontendUrls
import dev.glassworks.auth.OAuth
import java.net.URI

/**
 * The pages the kiosk gives the microphone, the camera and the permissions they ask for, with no prompt (#847):
 * the signed-in Home Assistant's origin, and the secure-context hosts ([dev.glassworks.KioskPrefs.secureHosts]:
 * the dashboard's host and the extra hosts set on the panel). A link tapped on a dashboard, or a card that
 * navigates away, lands on a page that gets none of them. While a test holds the screen, its test dashboard's own
 * origin too (Clarity's fake Home Assistant, on loopback or the LAN): that origin only, not its host's other ports.
 */
object TrustedPages {
    /**
     * Whether [url] is on [signedInBase]'s origin (null: not signed in) or on one of [secureHosts], over http or
     * https. A secure host matches on any port, as Gecko's `dom.securecontext.allowlist` does. [testDashboard] (the
     * test hold's, or null) matches on its exact origin only.
     */
    fun trusts(url: String?, signedInBase: String?, secureHosts: List<String>, testDashboard: String? = null): Boolean {
        if (url.isNullOrBlank()) return false
        if (FrontendUrls.sameOrigin(url, signedInBase)) return true
        if (FrontendUrls.sameOrigin(url, testDashboard)) return true
        val origin = OAuth.origin(url) ?: return false
        val host = try { URI(origin).host } catch (_: Exception) { null } ?: return false
        return secureHosts.any { it.trim().equals(host, ignoreCase = true) }
    }
}
