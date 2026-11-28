/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.manage

import dev.glassworks.pane.SettingsChange
import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.SettingsSection
import dev.glassworks.web.HttpRequest
import dev.glassworks.web.HttpResponse
import org.json.JSONArray
import org.json.JSONObject

/**
 * The panel's settings for a page on another panel (#466): `GET` reads them, `POST` changes one, as the
 * Manage page's own forms do ([SettingsChange]). Every request carries a Home Assistant access token
 * (`Authorization: Bearer …`), which this panel checks with its own Home Assistant ([HaTokenCheck]):
 * administrators only. No cookie counts here, so another site can't use a signed-in browser's session, and
 * any page may call it (CORS `*`): without the token it gets nothing.
 *
 * Values are text, as the forms send them: `on`/`off`, a number, an option's id, `HH:MM-HH:MM`. A sign-in's
 * password never leaves the panel: its item says only whether one is set. Actions aren't listed.
 *
 * A change that asks first on the panel answers `409` with the question and a one-shot `id`; it runs only when the
 * same token sends that change again with `answer=<id>`, as the page's own dialogs do (verifier on #487).
 * [HELLO] needs no token: it says only that this is a Glassworks panel, and its model as Home Assistant has it,
 * so a page sends its token only to a panel the registry lists ([HELLO]'s callers in panels.js).
 */
class ManageApi(
    private val host: ManageHost,
    private val check: HaTokenCheck,
    /** The HA address the panel uses now: a request runs only for the address that confirmed its user. */
    private val haBase: () -> String?,
    /** [SettingsChange.change] on the panel's main thread: null, why not, or [pending] while it still runs. */
    private val apply: (SettingsItem, String) -> String?,
    private val pending: String,
    private val event: (String) -> Unit,
    /** A one-shot id for the question about this change, for [owner] ([ManageServer]'s questions). */
    private val ask: (owner: String, section: String, key: String, value: String) -> String,
    /** Whether [id] answers that question for [owner]; used up either way. */
    private val answered: (id: String?, owner: String, section: String, key: String, value: String) -> Boolean,
) {
    fun handle(req: HttpRequest): HttpResponse {
        if (req.method == "OPTIONS") return HttpResponse(204, cors(req), ByteArray(0))
        if (req.path == HELLO && req.method == "GET") return json(req, 200, JSONObject().put("api", VERSION).put("glassworks", true).put("model", host.model))
        if (req.path != SETTINGS) return json(req, 404, JSONObject().put("problem", "Not found."))
        if (req.method != "GET" && req.method != "POST") return json(req, 405, JSONObject().put("problem", "Not allowed."))
        val token = HaTokenCheck.bearer(req.headers["authorization"])
        val user = when (val r = check.check(token)) {
            // Bound to the address that confirmed the user, as the handoff is (CodeRabbit on #487): if it changed since, start again.
            is HaTokenCheck.Result.Ok -> if (r.haBase == haBase()?.trim()?.trimEnd('/')) r.user
                else return json(req, 401, JSONObject().put("problem", ADDRESS_CHANGED), mapOf("WWW-Authenticate" to "Bearer"))
            is HaTokenCheck.Result.NotAdmin -> return json(req, 403, JSONObject().put("problem", "${r.name} isn't a Home Assistant administrator."))
            HaTokenCheck.Result.NoHa -> return json(req, 409, JSONObject().put("problem", NO_HA))
            HaTokenCheck.Result.Refused -> return json(req, 401, JSONObject().put("problem", REFUSED), mapOf("WWW-Authenticate" to "Bearer"))
            HaTokenCheck.Result.Busy -> return json(req, 503, JSONObject().put("problem", BUSY), mapOf("Retry-After" to "2"))
        }
        // The question's answer is bound to this token, as a form's is to its session.
        return if (req.method == "GET") json(req, 200, settings(host.status())) else change(req, user, "api:" + SessionStore.hash(token!!))
    }

    private fun change(req: HttpRequest, user: HaUser, owner: String): HttpResponse {
        val f = req.form
        val section = host.status().sections.firstOrNull { it.id == f["section"] }
        val key = f["key"].orEmpty()
        val item = section?.let { SettingsChange.find(it, key) }
            ?: return json(req, 404, JSONObject().put("problem", "This panel doesn't have that setting.").put("missing", true))
        if (item is SettingsItem.SignIn || item is SettingsItem.Action) return json(req, 422, JSONObject().put("problem", "Change this on the panel's own Manage page."))
        // The panel's own connection and dashboard choice: never overwritten from another panel's page (#466 panel run).
        if ((item is SettingsItem.Text && item.perPanel) || (item is SettingsItem.Choice && item.perPanel)) return json(req, 422, JSONObject().put("problem", PER_PANEL))
        val raw = f["value"].orEmpty().trim()
        val what = what(section, key)
        question(item, raw)?.takeIf { !answered(f["answer"], owner, section.id, key, raw) }?.let { q ->
            return json(req, 409, JSONObject().put("ask", JSONObject().put("title", q.title).put("detail", q.detail).put("action", q.action)
                .put("id", ask(owner, section.id, key, raw))))
        }
        val by = "${user.name} (from ${from(req)})"
        val problem = apply(item, raw)
        return when (problem) {
            pending -> { event("$by changed $what (still applying)"); json(req, 202, JSONObject().put("pending", true)) }
            null -> { event("$by changed $what"); json(req, 200, JSONObject().put("ok", true).put("restart", SettingsChange.restarts(item))) }
            else -> { event("$by couldn't change $what: $problem"); json(req, 422, JSONObject().put("problem", problem)) }
        }
    }

    private fun json(req: HttpRequest, status: Int, body: JSONObject, extra: Map<String, String> = emptyMap()) =
        HttpResponse(status, mapOf("Content-Type" to "application/json; charset=utf-8", "Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff") +
            cors(req) + extra, body.toString().toByteArray(Charsets.UTF_8))

    private fun cors(req: HttpRequest): Map<String, String> = mapOf(
        "Access-Control-Allow-Origin" to "*",
        "Access-Control-Allow-Methods" to "GET, POST, OPTIONS",
        "Access-Control-Allow-Headers" to "Authorization, Content-Type",
        "Access-Control-Max-Age" to "600",
        // A page on another panel reads the busy answer's wait; a cross-origin script sees no other header (CodeRabbit on #488).
        "Access-Control-Expose-Headers" to "Retry-After",
    ) + if (req.headers["access-control-request-private-network"] == "true" && localOrigin(req.headers["origin"])) mapOf("Access-Control-Allow-Private-Network" to "true") else emptyMap()

    companion object {
        const val PREFIX = "/manage/api/"
        const val SETTINGS = "/manage/api/settings"
        const val HELLO = "/manage/api/hello"

        /** Bumped when a page on an older panel couldn't read the answer any more. */
        const val VERSION = 1

        const val REFUSED = "Home Assistant didn't confirm this sign-in. Sign in again."
        const val NO_HA = "This panel has no Home Assistant address on your network, so it can't check who you are."
        const val PER_PANEL = "This is the panel's own, not copied from another panel. Change it on this panel's own Manage page."
        const val ADDRESS_CHANGED = "This panel's Home Assistant address changed. Sign in again."
        const val BUSY = "The panel is still asking Home Assistant about other sign-ins. Try again in a moment."

        /**
         * Whether a page at [origin] is on the local network: its host is a private, link-local or loopback IP
         * address, `localhost` or an mDNS `.local` name. Only such a page gets Chrome's private-network permission;
         * a public site's page doesn't, whatever it holds. Names aren't resolved here.
         */
        fun localOrigin(origin: String?): Boolean {
            val host = try { java.net.URI(origin ?: return false).host } catch (_: Exception) { null }?.trim('[', ']')?.lowercase()?.trimEnd('.') ?: return false
            if (host == "localhost" || host.endsWith(".local")) return true
            val literal = host.all { it.isDigit() || it == '.' } || (':' in host && host.all { it.isLetterOrDigit() || it == ':' || it == '.' })
            if (!literal) return false
            return runCatching { HaSignIn.isLocalAddress(java.net.InetAddress.getByName(host)) }.getOrDefault(false)
        }

        /** Every section with something to change, and its items, as [item] lists them. */
        fun settings(status: PanelStatus): JSONObject = JSONObject()
            .put("api", VERSION).put("name", status.name).put("frame", status.frame).put("version", status.version)
            .put("sections", JSONArray().also { out ->
                for (s in status.sections) {
                    val items = SettingsChange.changeable(s).mapNotNull { (k, i) -> item(k, i) }
                    if (items.isNotEmpty()) out.put(JSONObject().put("id", s.id).put("title", s.title).put("items", JSONArray(items)))
                }
            })

        /** One changeable item, or null for an action or one whose value can't be read now. */
        fun item(key: String, i: SettingsItem): JSONObject? = runCatching {
            val o = JSONObject().put("key", key).put("title", SettingsChange.titleOf(i))
            when (i) {
                is SettingsItem.Switch -> i.value().let { on -> o.put("kind", "switch").put("value", if (on) "on" else "off").put("shown", if (on) "On" else "Off").put("restarts", i.restartsApp) }
                is SettingsItem.Slider -> i.value().let { v ->
                    o.put("kind", "slider").put("value", SettingsChange.plain(v)).put("shown", i.shown(v))
                        .put("min", SettingsChange.plain(i.min)).put("max", SettingsChange.plain(i.max)).put("step", SettingsChange.plain(i.step))
                }
                is SettingsItem.Duration -> i.value().let { v -> o.put("kind", "duration").put("value", v.toString()).put("shown", i.shown(v)).put("choices", JSONArray(i.choices)) }
                is SettingsItem.TimeRange -> i.value().let { v -> o.put("kind", "time-range").put("value", v).put("shown", i.shown(v)) }
                is SettingsItem.Choice -> o.put("kind", "choice").put("value", i.value()).put("shown", SettingsChange.shown(i))
                    .put("options", JSONArray(i.options().map { (id, name) -> JSONArray(listOf(id, name)) }))
                    .also { if (i.perPanel) it.put("copy", false) }
                is SettingsItem.Text -> o.put("kind", "text").put("value", i.value()).put("shown", SettingsChange.shown(i)).put("restarts", i.restartsApp)
                    .also { if (i.perPanel) it.put("copy", false) }
                is SettingsItem.SignIn -> o.put("kind", "sign-in").put("set", i.isSet())
                else -> return@runCatching null
            }
        }.getOrNull()

        /** The question the panel asks before setting [item] to [raw], or null when it asks none. */
        fun question(item: SettingsItem, raw: String): SettingsItem.Confirm? = when {
            item is SettingsItem.Switch && (raw == "on" || raw == "off") -> runCatching { item.confirm(raw == "on") }.getOrNull()
            item is SettingsItem.Action && raw == SettingsItem.Action.RUN -> item.confirm
            // A choice that asks first (the radio's role).
            item is SettingsItem.Choice -> runCatching { item.confirm(raw) }.getOrNull()
            else -> null
        }

        /** "Screen › Turn off when idle", for the Log. */
        fun what(section: SettingsSection, key: String) =
            "${section.title} › " + if (key == SettingsChange.MASTER) "on/off" else key.replace("/", " › ")

        /**
         * Where a request came from, for the Log: its page's address from `Origin`, else [stated], the address the page
         * gave itself (Safari sends `Origin: null` on the handoff's post from a new tab, #466 panel run), else "no page".
         */
        fun from(req: HttpRequest, stated: String? = null): String =
            host(req.headers["origin"]?.takeIf { "://" in it }?.substringAfter("://")) ?: host(stated) ?: "no page"

        /** Only the characters an address has, at most 80. */
        private fun host(s: String?): String? = s?.filter { it.isLetterOrDigit() || it in ".:-[]" }?.take(80)?.ifEmpty { null }
    }
}
