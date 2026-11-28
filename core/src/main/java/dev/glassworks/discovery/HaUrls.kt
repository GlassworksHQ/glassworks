package dev.glassworks.discovery

import dev.glassworks.auth.OAuth
import org.json.JSONException
import org.json.JSONObject
import java.net.IDN
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.util.Locale

/**
 * Pure logic for finding Home Assistant (unit-tested in HaUrlsTest).
 *
 * mDNS: HA 2026.11.0 `components/zeroconf/__init__.py` (`_async_get_instance_info`) registers
 * `_home-assistant._tcp.local.` with TXT `location_name`, `uuid`, `version`, `external_url`,
 * `internal_url` and `base_url` (= external_url or internal_url, "for backward compatibility").
 * Empty values are published as empty strings. We prefer `internal_url` (the panel is on the LAN),
 * then `base_url`, then the advertised address + port.
 */
object HaUrls {
    const val SERVICE_TYPE = "_home-assistant._tcp"
    const val DEFAULT_PORT = 8123

    /** Picks the URL from a resolved `_home-assistant._tcp` service (first of [candidates]). */
    fun fromTxt(txt: Map<String, ByteArray?>, host: InetAddress?, port: Int): String? =
        candidates(txt, host, port).firstOrNull()

    /**
     * Candidate URLs in order: `internal_url`, `base_url`, then `http://<resolved address>:<port>`.
     * The address comes last but matters: Android 10's resolver can't resolve `.local` names, so an
     * `internal_url` like `http://homeassistant.local:8123` fails its probe on the panel.
     */
    fun candidates(txt: Map<String, ByteArray?>, host: InetAddress?, port: Int): List<String> {
        fun attr(k: String) = txt[k]?.toString(Charsets.UTF_8)?.trim().orEmpty()
        val out = ArrayList<String>(3)
        for (k in listOf("internal_url", "base_url")) normalize(attr(k))?.let { if (it !in out) out += it }
        host?.let { fromAddress(it, if (port > 0) port else DEFAULT_PORT) }?.let { if (it !in out) out += it }
        return out
    }

    /** `http://<ip>[:<port>]` (omits `:80`); IPv6 is bracketed. Link-local / loopback addresses are not useful. */
    fun fromAddress(addr: InetAddress, port: Int = DEFAULT_PORT): String? {
        if (addr.isLoopbackAddress || addr.isAnyLocalAddress || addr.isLinkLocalAddress || addr.isMulticastAddress) return null
        val h = if (addr is Inet6Address) "[" + addr.hostAddress.substringBefore('%') + "]" else addr.hostAddress
        return if (port == 80) "http://$h" else "http://$h:$port"
    }

    private val IDNA_DEVIATION = Regex("[\\u00df\\u03c2\\u200c\\u200d]")

    /**
     * Normalizes and validates a user-entered or configured Home Assistant URL, keeping any path,
     * query, and fragment:
     * - Missing scheme: prepends `http://`, and defaults the port to [DEFAULT_PORT] (8123) when no
     *   port was given (so bare IPs and names like `192.0.2.10` or `homeassistant.local/lovelace/0`
     *   reach Home Assistant and satisfy the legacy engine's origin parser).
     * - Explicit `http://` or `https://`: keeps the scheme, host, and port as given (does not force
     *   `:8123` on reverse-proxied port 80/443 URLs).
     * - Rejects non-http(s) schemes, whitespace/bad authorities, invalid ports, and hosts that
     *   `OAuth.origin` or the legacy engine's `Origin.of` cannot parse.
     */
    fun normalizeDashboardUrl(url: String?): String? {
        val u = url?.trim().orEmpty()
        if (u.isEmpty()) return null
        if (u.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7f }) return null

        val beforePath = u.substringBefore('/').substringBefore('?').substringBefore('#')
        val hasScheme = beforePath.endsWith(":") && u.startsWith("$beforePath//")
        val candidate = if (hasScheme) u else "http://$u"

        val uri = try { URI(candidate) } catch (_: Exception) { return null }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return null
        val rawAuth = uri.rawAuthority?.takeIf { it.isNotEmpty() } ?: return null
        if (rawAuth.any { it == '\\' || it == '\'' || it == '"' || it.isWhitespace() || it.code < 0x20 || it.code == 0x7f }) return null

        val hasUserInfo = '@' in rawAuth
        if (hasUserInfo && !hasScheme) return null
        val userInfo = if (hasUserInfo) rawAuth.substringBeforeLast('@') else null
        val hostPort = rawAuth.substringAfterLast('@')

        val host: String
        val port: Int?
        if (hostPort.startsWith("[")) {
            val close = hostPort.indexOf(']')
            if (close < 0) return null
            host = hostPort.substring(0, close + 1)
            val rest = hostPort.substring(close + 1)
            port = when {
                rest.isEmpty() -> null
                rest.startsWith(":") -> parsePort(rest.substring(1)) ?: return null
                else -> return null
            }
        } else {
            val colon = hostPort.indexOf(':')
            if (colon < 0) {
                host = hostPort
                port = null
            } else {
                host = hostPort.substring(0, colon)
                port = parsePort(hostPort.substring(colon + 1)) ?: return null
            }
        }

        if (uri.host.isNullOrEmpty() || host.isEmpty() || host.trimEnd('.').isEmpty()) return null
        if ('%' in host || IDNA_DEVIATION.containsMatchIn(host)) return null
        if (host.any { it.code >= 0x80 } && runCatching { IDN.toASCII(host, IDN.ALLOW_UNASSIGNED) }.getOrNull().isNullOrEmpty()) return null

        val schemeEnd = candidate.indexOf("://")
        val afterScheme = candidate.substring(schemeEnd + 3)
        if (!afterScheme.startsWith(rawAuth)) return null
        val suffix = afterScheme.substring(rawAuth.length)

        val effectivePort = port ?: if (!hasScheme) DEFAULT_PORT else null
        val normalized = buildString {
            append(scheme).append("://")
            if (userInfo != null) append(userInfo).append('@')
            append(host.lowercase(Locale.ROOT))
            if (effectivePort != null) append(':').append(effectivePort)
            append(suffix)
        }
        return normalized.takeIf { OAuth.origin(it) != null }
    }

    private fun parsePort(s: String): Int? =
        if (s.isEmpty() || !s.all { it in '0'..'9' }) null else s.toIntOrNull()?.takeIf { it in 1..65535 }

    /** `http(s)://host[:port]` without path / trailing slash, or null if it isn't one. */
    fun normalize(url: String?): String? = normalizeDashboardUrl(url)?.let(OAuth::origin)

    /**
     * `GET /manifest.json` of an HA instance is the PWA manifest served by the `frontend`
     * component; its `name` is "Home Assistant".
     */
    fun looksLikeHaManifest(status: Int, body: String): Boolean {
        if (status != 200) return false
        return try {
            JSONObject(body).optString("name").contains("Home Assistant")
        } catch (_: JSONException) {
            false
        }
    }
}
