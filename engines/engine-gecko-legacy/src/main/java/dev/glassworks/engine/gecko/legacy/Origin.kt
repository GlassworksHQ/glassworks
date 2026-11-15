/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.gecko.legacy

import java.net.IDN
import java.util.Locale

/**
 * A URL's origin as `scheme://host[:port]`: lower case, the default port dropped, `ws`/`wss` read as
 * `http`/`https` (a WebSocket to Home Assistant is Home Assistant), and a `blob:` URL's inner origin. Null for
 * anything without a network origin (`about:`, `data:`, `moz-extension:`, `javascript:`) or that doesn't parse.
 * An authority with a backslash, whitespace or a control character doesn't parse: a browser reads `\` as `/` and
 * drops tabs and newlines, so such a URL can go to another host than this reading says. A port is digits only.
 * A non-ASCII host is written in punycode (`ält.example.org` → `xn--lt-uia.example.org`), as Gecko writes it in the
 * URLs it reports: the app may load a Unicode URL, and Home Assistant's requests then arrive in punycode. A host
 * with an IDNA deviation character (ß, ς, ZWNJ, ZWJ) doesn't parse: Android 6-7's IDN (IDNA 2003) maps `faß` to
 * `fass`, Gecko (UTS 46, non-transitional) to `xn--fa-hia`, so the two would name different hosts.
 *
 * Plain string work, no java.net.URI: Home Assistant's URLs carry characters URI refuses, and a refused URL
 * must not open the lockdown either way. The same rules are in assets/lockdown/policy.js.
 */
internal object Origin {
    private val default = mapOf("http" to 80, "https" to 443)
    private val asHttp = mapOf("ws" to "http", "wss" to "https")
    private val form = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*)://([^/?#]*)")
    private val badAuthority = Regex("[\\\\\\s\\x00-\\x1f\\x7f]")
    private val deviation = Regex("[\\u00df\\u03c2\\u200c\\u200d]")

    fun of(url: String?): String? {
        val u = url?.trim() ?: return null
        if (u.regionMatches(0, "blob:", 0, 5, ignoreCase = true)) return of(u.substring(5))
        val m = form.find(u) ?: return null
        val raw = m.groupValues[1].lowercase(Locale.ROOT)
        val scheme = asHttp[raw] ?: raw
        if (scheme !in default) return null
        if (badAuthority.containsMatchIn(m.groupValues[2])) return null
        val authority = m.groupValues[2].substringAfterLast('@')
        val (host, port) = if (authority.startsWith("[")) {
            val end = authority.indexOf(']')
            if (end < 0) return null
            val rest = authority.substring(end + 1)
            if (rest.isNotEmpty() && !rest.startsWith(":")) return null
            authority.substring(0, end + 1) to rest.removePrefix(":")
        } else {
            authority.substringBefore(':') to (if (':' in authority) authority.substringAfter(':') else "")
        }
        if (host.isEmpty()) return null
        val p = when {
            port.isEmpty() -> default.getValue(scheme)
            port.all { it in '0'..'9' } -> port.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            else -> return null
        }
        if (deviation.containsMatchIn(host)) return null
        val ascii = if (host.all { it.code < 0x80 }) host else runCatching { IDN.toASCII(host, IDN.ALLOW_UNASSIGNED) }.getOrNull() ?: return null
        val h = ascii.lowercase(Locale.ROOT).trimEnd('.')
        return if (p == default.getValue(scheme)) "$scheme://$h" else "$scheme://$h:$p"
    }

    /** The host to name in a note: the origin without its scheme, or "" when it has none. */
    fun host(url: String?): String = of(url)?.substringAfter("://") ?: ""
}
