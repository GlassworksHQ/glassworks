/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.web

import java.net.URI

/** How the panel reaches a dashboard URL whose host may be an mDNS `.local` name ([LocalName.route]). */
sealed interface LocalRoute {
    /** The URL to load. */
    val url: String

    /** Not a `.local` host, or Android resolves the name itself: the URL as set. */
    data class AsSet(override val url: String) : LocalRoute

    /** Through the address Home Assistant was found at, in place of [name]. Path, query and port stay as set. */
    data class ByAddress(override val url: String, val name: String) : LocalRoute

    /** Android can't resolve the name, and no address can stand in: the URL as set, and [why] for the log. */
    data class Unresolved(override val url: String, val why: String) : LocalRoute
}

/**
 * A `.local` dashboard host on an Android that can't resolve mDNS names (before 12, #449). Discovery has found Home
 * Assistant's address already ([found], `http://192.0.2.10:8123`), so the dashboard loads through it. The setting
 * stays as the person entered it, and Android 12 and later load it as set.
 */
object LocalName {
    fun route(url: String, found: String?, resolvesLocal: Boolean): LocalRoute {
        val u = try { URI(url.trim()) } catch (_: Exception) { return LocalRoute.AsSet(url) }
        val name = u.host?.lowercase()?.trimEnd('.') ?: return LocalRoute.AsSet(url)
        if (!isLocal(name) || resolvesLocal) return LocalRoute.AsSet(url)
        // The certificate names the .local host: over an address the page would fail its check instead.
        if (u.scheme.equals("https", ignoreCase = true)) return LocalRoute.Unresolved(url, "https, and its certificate names $name")
        val address = found?.let { runCatching { URI(it.trim()).host }.getOrNull() }
        if (address.isNullOrEmpty() || isLocal(address.lowercase().trimEnd('.'))) {
            return LocalRoute.Unresolved(url, "Home Assistant's address isn't known yet")
        }
        val authority = (u.rawUserInfo?.let { "$it@" } ?: "") + address + (if (u.port != -1) ":${u.port}" else "")
        val rest = (u.rawPath ?: "") + (u.rawQuery?.let { "?$it" } ?: "") + (u.rawFragment?.let { "#$it" } ?: "")
        return LocalRoute.ByAddress("${u.scheme}://$authority$rest", name)
    }

    private fun isLocal(host: String) = host.endsWith(".local")
}
