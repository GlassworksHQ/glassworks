package dev.glassworks.dashboard

import dev.glassworks.KioskPrefs
import dev.glassworks.auth.OAuth
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.URI

/** A Lovelace dashboard from `lovelace/dashboards/list` (HA serves it at `/<urlPath>`). */
data class Dashboard(val urlPath: String, val title: String)

/**
 * The ESPHome select "Dashboard" (pure JVM, see DashboardOptionsTest). docs/onboarding.md, step 5.
 *
 * Options: [DEFAULT] (HA's root, i.e. the user's default dashboard), then one per Lovelace dashboard
 * (its title; duplicates get the url_path appended), then [CUSTOM] only while the saved URL matches
 * none of them, so the state is always truthful.
 *
 * HA 2026.11.0 `lovelace/dashboard.py` `DashboardsCollectionWebSocket.ws_list_item` returns
 * `dashboard.config` for every dashboard that has one (storage and YAML; the built-in default has
 * none, so it is never listed), each with `url_path`, `title`, `mode`, `require_admin`, ...
 */
object DashboardOptions {
    const val DEFAULT = "Default"
    const val CUSTOM = "Custom"
    /** The default dashboard's own path (`/lovelace`, `/lovelace/<view>`). */
    private const val DEFAULT_PATH = "lovelace"
    private const val MAX_LEN = 64

    /** `[{url_path, title}]` → dashboards; entries without a usable url_path are dropped. Null if not a JSON array. */
    fun parse(json: String): List<Dashboard>? = try {
        fromJson(JSONArray(json))
    } catch (_: JSONException) {
        null
    }

    fun fromJson(a: JSONArray, includeAdmin: Boolean = true): List<Dashboard> {
        val out = ArrayList<Dashboard>()
        val seen = HashSet<String>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (!includeAdmin && o.optBoolean("require_admin", false)) continue
            val path = o.optString("url_path").trim().trim('/')
            if (path.isEmpty() || '/' in path || path.length > 100 || !seen.add(path)) continue
            out += Dashboard(path, o.optString("title").trim())
        }
        return out
    }

    fun toJson(list: List<Dashboard>): String =
        JSONArray().apply { list.forEach { put(JSONObject().put("url_path", it.urlPath).put("title", it.title)) } }.toString()

    /** Option label → dashboard, in list order, unique and never [DEFAULT] / [CUSTOM]. */
    fun labels(list: List<Dashboard>): LinkedHashMap<String, Dashboard> {
        val out = LinkedHashMap<String, Dashboard>()
        val taken = hashSetOf(DEFAULT.lowercase(), CUSTOM.lowercase())
        for (d in list) {
            val base = d.title.ifEmpty { d.urlPath }.take(MAX_LEN)
            var label = base
            if (label.lowercase() in taken) label = "$base (${d.urlPath})".take(MAX_LEN + 40)
            var n = 2
            while (label.lowercase() in taken) label = "$base (${d.urlPath}) ${n++}"
            taken += label.lowercase()
            out[label] = d
        }
        return out
    }

    /**
     * The option matching the dashboard setting ([KioskPrefs.urlSetting]): `auto` and HA's root (or
     * `/lovelace…`) → [DEFAULT]; `<ha>/<url_path>[/view][?…]` → that dashboard; anything else
     * (another host, another path) → [CUSTOM].
     */
    fun currentOption(urlSetting: String, haBase: String?, list: List<Dashboard>): String {
        if (urlSetting.trim().equals(KioskPrefs.AUTO, ignoreCase = true)) return DEFAULT
        val origin = OAuth.origin(urlSetting) ?: return CUSTOM
        if (haBase != null && origin != OAuth.origin(haBase)) return CUSTOM
        val first = firstSegment(urlSetting) ?: return CUSTOM
        labels(list).entries.firstOrNull { it.value.urlPath == first }?.let { return it.key }
        return if (first.isEmpty() || first == DEFAULT_PATH) DEFAULT else CUSTOM
    }

    /**
     * The options for the select: [DEFAULT], the dashboards, then [CUSTOM] (always: if it came and went
     * with the URL, every switch to or from a custom URL would change the option list, and ESPHome can
     * only apply that by making Home Assistant reconnect, so the panel's entities blinked unavailable).
     */
    @Suppress("UNUSED_PARAMETER")
    fun options(list: List<Dashboard>, current: String): List<String> {
        val out = ArrayList<String>()
        out += DEFAULT
        out += labels(list).keys
        out += CUSTOM
        return out
    }

    /**
     * Options for interactive dashboard pickers (settings, Manage, setup): [DEFAULT], each dashboard,
     * and [CUSTOM] only while [current] is [CUSTOM] so users aren't offered a no-op Custom choice.
     */
    fun pickerOptions(list: List<Dashboard>, current: String): List<String> = buildList {
        add(DEFAULT)
        addAll(labels(list).keys)
        if (current == CUSTOM) add(CUSTOM)
    }

    /**
     * The new dashboard setting for a chosen [option], or null when nothing changes ([CUSTOM], the
     * option that is already current, unknown options, HA unknown). Always on [haBase]'s host, so the
     * secure-context hosts don't change (no restart). [DEFAULT] goes back to `auto` when the
     * discovered HA ([haUrl]) is that same host, else `<haBase>/`. A user and password in [urlSetting] stay in the
     * new setting, which never goes back to `auto` then: [haBase], an origin, holds neither (#449).
     */
    fun urlFor(option: String, urlSetting: String, haBase: String?, haUrl: String, list: List<Dashboard>): String? {
        if (option == CUSTOM) return null
        if (option == currentOption(urlSetting, haBase, list)) return null
        val origin = haBase?.let(OAuth::trimBase) ?: return null
        val userInfo = try { URI(urlSetting.trim()).rawUserInfo } catch (_: Exception) { null }
        val base = userInfo?.let { origin.replaceFirst("://", "://$it@") } ?: origin
        if (option == DEFAULT) {
            val sameAsFound = haUrl.isNotEmpty() && OAuth.origin(haUrl) == OAuth.origin(origin)
            return if (sameAsFound && userInfo == null) KioskPrefs.AUTO else "$base/"
        }
        val d = labels(list)[option] ?: return null
        return "$base/${d.urlPath}"
    }

    private fun firstSegment(url: String): String? = try {
        val p = URI(url.trim()).rawPath.orEmpty()
        p.trim('/').substringBefore('/')
    } catch (_: Exception) {
        null
    }
}
