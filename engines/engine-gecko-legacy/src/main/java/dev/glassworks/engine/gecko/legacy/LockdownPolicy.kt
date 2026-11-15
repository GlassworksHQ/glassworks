/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.gecko.legacy

import dev.glassworks.engine.gecko.PageGuard.Load

/**
 * What the legacy engine lets a page load (#198). The engine is frozen (GeckoView 144, no security fixes), so
 * it shows the panel's Home Assistant and nothing else:
 *
 * - The app's own loads set the allowed origin ([appLoads]): the dashboard URL the person configured.
 * - A top-level page loads only from that origin. `about:blank` is allowed (an empty page).
 * - A frame loads only from that origin, or is `about:blank`/`about:srcdoc` (they inherit the page's origin)
 *   or the lockdown's own note (`moz-extension:`). Cross-origin iframes and web cards are refused.
 * - A new window loads in place, as in the current engine, if it is Home Assistant's; any other is refused.
 * - Downloads are always refused.
 *
 * Before the app has loaded anything, nothing from the network loads. Subresources (images, map tiles,
 * fetches, WebSockets) don't pass through here: the lockdown extension blocks them (assets/lockdown).
 */
internal class LockdownPolicy {
    /** The origin pages may use, from the app's last load. */
    @Volatile var origin: String? = null
        private set

    /** The app loads [url]. Returns true if the allowed origin changed. */
    fun appLoads(url: String): Boolean {
        val o = Origin.of(url) ?: return false
        if (o == origin) return false
        origin = o
        return true
    }

    fun allows(kind: Load, url: String): Boolean {
        val u = url.trim()
        return when (kind) {
            Load.DOWNLOAD -> false
            Load.WINDOW -> sameOrigin(u)
            Load.PAGE -> u == "about:blank" || sameOrigin(u)
            Load.FRAME -> u == "about:blank" || u == "about:srcdoc" || u.startsWith("moz-extension://") || sameOrigin(u)
        }
    }

    private fun sameOrigin(url: String) = origin.let { it != null && Origin.of(url) == it }
}
