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

    /**
     * Through the address Home Assistant was found at, in place of [name]. Path, query and port stay as set.
     * [address] is the host and port alone, for the log: the URL may hold a user and password.
     */
    data class ByAddress(override val url: String, val name: String, val address: String) : LocalRoute

    /** Android can't resolve [name], and discovery has no address for it yet: the URL as set, until it has one. */
    data class NoAddressYet(override val url: String, val name: String) : LocalRoute

    /** An https URL on [name]: its certificate is for the name, so an address can't stand in. The URL as set. */
    data class KeptForCertificate(override val url: String, val name: String) : LocalRoute
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
        if (u.scheme.equals("https", ignoreCase = true)) return LocalRoute.KeptForCertificate(url, name)
        val host = found?.let { runCatching { URI(it.trim()).host }.getOrNull() }
        if (host.isNullOrEmpty() || isLocal(host.lowercase().trimEnd('.'))) return LocalRoute.NoAddressYet(url, name)
        val address = host + (if (u.port != -1) ":${u.port}" else "")
        val rest = (u.rawPath ?: "") + (u.rawQuery?.let { "?$it" } ?: "") + (u.rawFragment?.let { "#$it" } ?: "")
        return LocalRoute.ByAddress("${u.scheme}://${u.rawUserInfo?.let { "$it@" } ?: ""}$address$rest", name, address)
    }

    private fun isLocal(host: String) = host.endsWith(".local")
}
