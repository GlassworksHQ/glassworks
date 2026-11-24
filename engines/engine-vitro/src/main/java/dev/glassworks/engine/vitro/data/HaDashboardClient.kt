/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.data

import dev.glassworks.auth.HaSocket
import dev.glassworks.auth.HaThreadClient
import dev.glassworks.net.HappyEyeballs
import dev.glassworks.sendspin.WsClient
import dev.glassworks.engine.vitro.model.DashboardConfig
import dev.glassworks.engine.vitro.model.DashboardSummary
import dev.glassworks.engine.vitro.model.LovelaceParser
import dev.glassworks.engine.vitro.model.LovelaceResource
import dev.glassworks.engine.vitro.theme.ThemeCatalog
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** History point `(timestampEpochSec, numericValue)` for native sparkline/history-graph cards. */
data class HistoryPoint(val epochSec: Double, val value: Double)

/**
 * Native Home Assistant WebSocket data plane for Vitro.
 *
 * Keeps one long-lived connection over [HaSocket] (`WsHaSocket`), authenticates with the panel's
 * Home Assistant token, fetches Lovelace config + resources + themes, and subscribes only to the
 * entities needed by the active view (`subscribe_entities`). Pure JVM so it is unit-testable with a
 * scripted fake socket.
 */
class HaDashboardClient(
    private val haBase: String,
    private val tokenProvider: (force: Boolean, done: (String?) -> Unit) -> Unit,
    private val store: EntityStore,
    private val connect: (url: String) -> HaSocket = ::connectLongLived,
    private val listener: Listener,
) {
    interface Listener {
        fun onConnected(haVersion: String) {}
        fun onAuthRequired() {}
        fun onDashboards(dashboards: List<DashboardSummary>) {}
        fun onResources(resources: List<LovelaceResource>) {}
        fun onThemes(catalog: ThemeCatalog) {}
        fun onDashboardConfig(urlPath: String?, config: DashboardConfig) {}
        fun onDisconnected(reason: String) {}
    }

    private val closed = AtomicBoolean(false)
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, (JSONObject) -> Unit>()
    private val subscriptions = ConcurrentHashMap<Int, (JSONObject) -> Unit>()
    private val sendLock = Any()

    @Volatile private var socket: HaSocket? = null
    @Volatile private var entitySubId: Int? = null
    @Volatile private var activeEntityFilter: Set<String> = emptySet()
    @Volatile private var readerThread: Thread? = null
    @Volatile private var requestedDashboardPath: String? = null
    @Volatile var haConfig: JSONObject = JSONObject()
        private set

    /** Sends an arbitrary HA WebSocket command (used by Tier 3 custom card `hass.callWS` bridge). */
    fun callWs(msg: JSONObject, done: (success: Boolean, result: Any?, error: JSONObject?) -> Unit) {
        val copy = JSONObject(msg.toString())
        val sent = request(copy) { res ->
            if (res.optBoolean("success", false)) {
                done(true, res.opt("result"), null)
            } else {
                done(false, null, res.optJSONObject("error"))
            }
        }
        if (sent <= 0) done(false, null, JSONObject().put("code", "disconnected").put("message", "Not connected"))
    }

    fun start(dashboardUrlPath: String?) {
        requestedDashboardPath = dashboardUrlPath
        if (closed.get()) return
        val thread = Thread({ loop() }, "vitro-ha-ws").apply { isDaemon = true }
        readerThread = thread
        thread.start()
    }

    fun close() {
        closed.set(true)
        runCatching { socket?.close() }
        socket = null
    }

    /** Switches the entity subscription to [entityIds] for the active view. */
    fun subscribeViewEntities(entityIds: Set<String>) {
        if (entityIds == activeEntityFilter && entitySubId != null) return
        activeEntityFilter = entityIds
        if (socket == null) return
        resubscribeEntities()
    }

    /** Loads a different Lovelace dashboard config over the live socket. */
    fun fetchDashboard(urlPath: String?) {
        requestedDashboardPath = urlPath
        val msg = JSONObject().put("type", "lovelace/config")
        if (!urlPath.isNullOrEmpty()) msg.put("url_path", urlPath)
        request(msg) { res ->
            if (res.optBoolean("success", false)) {
                val cfgObj = res.optJSONObject("result") ?: JSONObject()
                listener.onDashboardConfig(urlPath, LovelaceParser.parseDashboard(cfgObj))
            }
        }
    }

    /** Calls a Home Assistant service (e.g., `light.toggle`, `climate.set_temperature`). */
    fun callService(
        domain: String,
        service: String,
        serviceData: JSONObject? = null,
        target: JSONObject? = null,
        done: ((Boolean) -> Unit)? = null,
    ) {
        val msg = JSONObject()
            .put("type", "call_service")
            .put("domain", domain)
            .put("service", service)
        if (serviceData != null) msg.put("service_data", serviceData)
        if (target != null) msg.put("target", target)
        request(msg) { res -> done?.invoke(res.optBoolean("success", false)) }
    }

    /** Fetches numeric history points for [entityIds] over the last [hoursToShow] hours via `history/stream`. */
    fun fetchHistory(entityIds: List<String>, hoursToShow: Int = 24, onPoints: (Map<String, List<HistoryPoint>>) -> Unit) {
        if (entityIds.isEmpty()) return
        val startMs = System.currentTimeMillis() - hoursToShow.coerceAtLeast(1) * 3_600_000L
        val startIso = formatIsoUtc(startMs)
        val msg = JSONObject()
            .put("type", "history/stream")
            .put("entity_ids", JSONArray(entityIds))
            .put("start_time", startIso)
            .put("minimal_response", true)
            .put("significant_changes_only", false)
        var subId = -1
        subId = subscribe(msg) { ev ->
            val statesObj = ev.optJSONObject("states") ?: return@subscribe
            val out = LinkedHashMap<String, List<HistoryPoint>>()
            val keys = statesObj.keys()
            while (keys.hasNext()) {
                val eid = keys.next()
                val arr = statesObj.optJSONArray(eid) ?: continue
                val pts = ArrayList<HistoryPoint>(arr.length())
                for (i in 0 until arr.length()) {
                    val p = arr.optJSONObject(i) ?: continue
                    val value = p.optString("s", "").toDoubleOrNull() ?: continue
                    val ts = p.optDouble("lu", p.optDouble("lc", 0.0))
                    pts += HistoryPoint(ts, value)
                }
                if (pts.isNotEmpty()) out[eid] = pts
            }
            if (out.isNotEmpty()) onPoints(out)
            // One-shot history fetch: unsubscribe after receiving the initial series so we don't leak subs.
            if (subId > 0) unsubscribe(subId)
        }
    }

    /** Subscribes to HA's server-side Jinja2 `render_template` (for markdown & mushroom-template-card). */
    fun subscribeTemplate(template: String, onRendered: (String) -> Unit): () -> Unit {
        val msg = JSONObject()
            .put("type", "render_template")
            .put("template", template)
        val subId = subscribe(msg) { ev ->
            val rendered = ev.opt("result")?.takeIf { it != JSONObject.NULL }?.toString().orEmpty()
            onRendered(rendered)
        }
        return { if (subId > 0) unsubscribe(subId) }
    }

    private fun loop() {
        var backoffMs = 1_000L
        while (!closed.get()) {
            val wsUrl = HaThreadClient.websocketUrl(haBase)
            if (wsUrl == null) {
                listener.onDisconnected("Invalid Home Assistant URL: $haBase")
                return
            }
            val token = awaitToken(force = false)
            if (closed.get()) return
            var ws: HaSocket? = null
            try {
                ws = connect(wsUrl)
                socket = ws
                val hello = ws.receive()?.let(::JSONObject) ?: throw IOException("Closed before greeting")
                if (hello.optString("type") != "auth_required") throw IOException("Unexpected greeting: ${hello.optString("type")}")

                ws.send(JSONObject().put("type", "auth").put("access_token", token.orEmpty()).toString())
                val auth = ws.receive()?.let(::JSONObject) ?: throw IOException("Closed during auth")
                when (auth.optString("type")) {
                    "auth_ok" -> {
                        backoffMs = 1_000L
                        listener.onConnected(auth.optString("ha_version", ""))
                        onAuthenticated()
                    }
                    "auth_invalid" -> {
                        // Try once with a forced refresh token before reporting auth required.
                        val fresh = awaitToken(force = true)
                        if (fresh != null && fresh != token) {
                            runCatching { ws.close() }
                            continue
                        }
                        listener.onAuthRequired()
                        return
                    }
                    else -> throw IOException("Unexpected auth reply: ${auth.optString("type")}")
                }

                while (!closed.get()) {
                    val raw = ws.receive() ?: break
                    handleIncoming(JSONObject(raw))
                }
            } catch (e: Exception) {
                if (!closed.get()) listener.onDisconnected(e.message ?: e.javaClass.simpleName)
            } finally {
                runCatching { ws?.close() }
                socket = null
                entitySubId = null
                pending.clear()
                subscriptions.clear()
            }
            if (closed.get()) break
            try {
                Thread.sleep(backoffMs)
            } catch (_: InterruptedException) {
                break
            }
            backoffMs = (backoffMs * 2).coerceAtMost(10_000L)
        }
    }

    private fun onAuthenticated() {
        // 0. HA config (unit_system, location_name, time_zone — needed by custom cards reading hass.config)
        request(JSONObject().put("type", "get_config")) { res ->
            if (res.optBoolean("success", false)) {
                res.optJSONObject("result")?.let { haConfig = it }
            }
        }
        // 1. Themes
        request(JSONObject().put("type", "frontend/get_themes")) { res ->
            if (res.optBoolean("success", false)) {
                listener.onThemes(ThemeCatalog.parse(res.optJSONObject("result")))
            }
        }
        // Subscribe to live theme updates
        subscribe(JSONObject().put("type", "subscribe_events").put("event_type", "themes_updated")) {
            request(JSONObject().put("type", "frontend/get_themes")) { res ->
                if (res.optBoolean("success", false)) {
                    listener.onThemes(ThemeCatalog.parse(res.optJSONObject("result")))
                }
            }
        }
        // 2. Dashboard list (for ESPHome Dashboard select)
        request(JSONObject().put("type", "lovelace/dashboards/list")) { res ->
            if (res.optBoolean("success", false)) {
                val arr = res.optJSONArray("result") ?: JSONArray()
                val out = ArrayList<DashboardSummary>(arr.length())
                for (i in 0 until arr.length()) {
                    val d = arr.optJSONObject(i) ?: continue
                    val path = d.optString("url_path", "").ifEmpty { continue }
                    out += DashboardSummary(
                        urlPath = path,
                        title = d.optString("title", path),
                        icon = d.optString("icon", "").ifEmpty { null },
                        requireAdmin = d.optBoolean("require_admin", false),
                    )
                }
                listener.onDashboards(out)
            }
        }
        // 3. Lovelace custom card resources (for Tier 3 Micro-Web Islands)
        request(JSONObject().put("type", "lovelace/resources")) { res ->
            if (res.optBoolean("success", false)) {
                val arr = res.optJSONArray("result") ?: JSONArray()
                val out = ArrayList<LovelaceResource>(arr.length())
                for (i in 0 until arr.length()) {
                    val r = arr.optJSONObject(i) ?: continue
                    val u = r.optString("url", "").ifEmpty { continue }
                    out += LovelaceResource(u, r.optString("type", "module"))
                }
                listener.onResources(out)
            }
        }
        // 4. Active dashboard config (and live lovelace_updated notifications)
        fetchDashboard(requestedDashboardPath)
        subscribe(JSONObject().put("type", "subscribe_events").put("event_type", "lovelace_updated")) {
            fetchDashboard(requestedDashboardPath)
        }
        // 5. Initial entity subscription (refined to view's entities once config arrives)
        resubscribeEntities()
    }

    private fun resubscribeEntities() {
        entitySubId?.let { unsubscribe(it) }
        val msg = JSONObject().put("type", "subscribe_entities")
        val filter = activeEntityFilter
        if (filter.isNotEmpty()) {
            msg.put("entity_ids", JSONArray(filter.toList()))
        }
        entitySubId = subscribe(msg) { ev ->
            store.applyCompressedEvent(ev)
        }
    }

    private fun handleIncoming(msg: JSONObject) {
        val id = msg.optInt("id", -1)
        when (msg.optString("type")) {
            "result" -> pending.remove(id)?.invoke(msg)
            "event" -> msg.optJSONObject("event")?.let { ev -> subscriptions[id]?.invoke(ev) }
        }
    }

    private fun request(msg: JSONObject, onResult: (JSONObject) -> Unit): Int {
        val id = nextId.getAndIncrement()
        msg.put("id", id)
        pending[id] = onResult
        if (!send(msg)) {
            pending.remove(id)
            return -1
        }
        return id
    }

    private fun subscribe(msg: JSONObject, onEvent: (JSONObject) -> Unit): Int {
        val id = nextId.getAndIncrement()
        msg.put("id", id)
        subscriptions[id] = onEvent
        pending[id] = { res ->
            if (!res.optBoolean("success", false)) subscriptions.remove(id)
        }
        if (!send(msg)) {
            subscriptions.remove(id)
            pending.remove(id)
            return -1
        }
        return id
    }

    private fun unsubscribe(subscriptionId: Int) {
        subscriptions.remove(subscriptionId)
        val msg = JSONObject().put("type", "unsubscribe_events").put("subscription", subscriptionId)
        request(msg) {}
    }

    private fun send(msg: JSONObject): Boolean {
        val ws = socket ?: return false
        return try {
            synchronized(sendLock) { ws.send(msg.toString()) }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun awaitToken(force: Boolean): String? {
        val latch = java.util.concurrent.CountDownLatch(1)
        var token: String? = null
        tokenProvider(force) { t ->
            token = t
            latch.countDown()
        }
        latch.await(15, java.util.concurrent.TimeUnit.SECONDS)
        return token
    }

    companion object {
        /**
         * Long-lived RFC 6455 socket for the dashboard WebSocket (`readTimeoutMs = 0` so an idle
         * dashboard doesn't disconnect every 15 s like one-shot `WsHaSocket.connect`).
         */
        fun connectLongLived(url: String): HaSocket {
            val connector = HappyEyeballs()
            val ws = WsClient.connect(url, connectTimeoutMs = 8_000, readTimeoutMs = 0, openSocket = connector::connect)
            return object : HaSocket {
                override fun send(text: String) = ws.sendText(text)
                override fun receive(): String? {
                    while (true) {
                        val m = ws.receive() ?: return null
                        if (m.text) return m.data.toString(Charsets.UTF_8)
                    }
                }
                override fun close() = ws.close()
            }
        }

        private fun formatIsoUtc(epochMs: Long): String {
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
            return fmt.format(java.util.Date(epochMs))
        }
    }
}
