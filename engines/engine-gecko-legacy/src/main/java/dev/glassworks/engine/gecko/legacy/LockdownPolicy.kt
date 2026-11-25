/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.gecko.legacy

import dev.glassworks.engine.gecko.PageGuard.Load

/**
 * What the legacy engine lets a page load (#198). The engine is frozen (GeckoView 144, no security fixes), so
 * it shows the panel's Home Assistant and nothing else:
 *
 * - The allowed origin is the configured dashboard's ([start]), set once when the engine starts. Nothing in the
 *   session moves or widens it: not the page, and not a later load by the app. A new dashboard address restarts
 *   the process (the S1 app's settings do), and the new engine starts with it.
 * - The app's own loads are pages too: one elsewhere is refused.
 * - A top-level page loads only from that origin. `about:blank` is allowed (an empty page).
 * - A frame loads only from that origin, or is `about:blank`/`about:srcdoc` (they inherit the page's origin)
 *   or the lockdown's own note (`moz-extension:`). Cross-origin iframes and web cards are refused.
 * - A new window loads in place, as in the current engine, if it is Home Assistant's; any other is refused.
 * - Downloads are always refused.
 *
 * - The app's own loopback server ([local]: Vitro's island server, `http://127.0.0.1:<port>`) is the one other origin a
 *   page or frame may come from, set with the dashboard's and as fixed. Nothing else on loopback: another port is
 *   another app's. A new window still goes only to Home Assistant.
 *
 * Without a dashboard (Home Assistant not found yet), nothing from the network loads. Subresources (images, map tiles,
 * fetches, WebSockets) don't pass through here: the lockdown extension blocks them (assets/lockdown), and holds each
 * page to its own origin, so Home Assistant's page never reaches the loopback server, nor its page Home Assistant.
 */
internal class LockdownPolicy {
    /** The origin pages may use: the configured dashboard's, from [start]. */
    @Volatile var origin: String? = null
        private set

    /** The app's own loopback server's origin, from [start]: `http://127.0.0.1:<port>`, or null. */
    @Volatile var local: String? = null
        private set

    private var started = false

    /**
     * The engine starts with the configured [dashboard] URL (null: none yet) and the app's loopback server, [localUrl]
     * (null: none). Only the first call counts: returns false, and changes nothing, after that.
     */
    fun start(dashboard: String?, localUrl: String? = null): Boolean {
        if (started) return false
        started = true
        origin = Origin.of(dashboard)
        local = loopback(localUrl)?.takeIf { it != origin }
        return true
    }

    fun allows(kind: Load, url: String): Boolean {
        val u = url.trim()
        return when (kind) {
            Load.DOWNLOAD -> false
            Load.WINDOW -> sameOrigin(u)
            Load.PAGE -> u == "about:blank" || sameOrigin(u) || isLocal(u)
            Load.FRAME -> u == "about:blank" || u == "about:srcdoc" || u.startsWith("moz-extension://") || sameOrigin(u) || isLocal(u)
        }
    }

    private fun sameOrigin(url: String) = origin.let { it != null && Origin.of(url) == it }

    private fun isLocal(url: String) = local.let { it != null && Origin.of(url) == it }

    companion object {
        private val LOOPBACK = Regex("^http://127\\.0\\.0\\.1:\\d+$")

        /** [url]'s origin when it is exactly `http://127.0.0.1:<port>` with a port of its own; else null (fails closed). */
        internal fun loopback(url: String?): String? = Origin.of(url)?.takeIf { LOOPBACK.matches(it) }
    }
}
