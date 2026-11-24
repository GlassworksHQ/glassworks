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
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** History point `(timestampEpochSec, numericValue)` for native sparkline/history-graph cards. */
data class HistoryPoint(val epochSec: Double, val value: Double)

/** One entry of a `weather/subscribe_forecast` list: `datetime`, `condition`, high/low temperature and precipitation. */
data class ForecastDay(
    val datetime: String,
    val condition: String?,
    val temperature: Double?,
    val templow: Double?,
    val precipitation: Double?,
    val precipitationProbability: Double?,
) {
    companion object {
        fun parse(o: JSONObject): ForecastDay = ForecastDay(
            datetime = o.optString("datetime", ""),
            condition = o.optString("condition", "").ifEmpty { null },
            temperature = o.optNumber("temperature"),
            templow = o.optNumber("templow"),
            precipitation = o.optNumber("precipitation"),
            precipitationProbability = o.optNumber("precipitation_probability"),
        )

        fun parseList(arr: JSONArray?): List<ForecastDay> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::parse) }
        }

        private fun JSONObject.optNumber(key: String): Double? =
            if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() } else null
    }
}

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
    /** How long the greeting and the auth exchange may take before the connection is dropped and retried. */
    private val authTimeoutMs: Long = AUTH_TIMEOUT_MS,
) {
    interface Listener {
        fun onConnected(haVersion: String) {}
        fun onAuthRequired() {}
        fun onDashboards(dashboards: List<DashboardSummary>) {}
        fun onResources(resources: List<LovelaceResource>) {}
        fun onThemes(catalog: ThemeCatalog) {}
        fun onDashboardConfig(urlPath: String?, config: DashboardConfig) {}

        /**
         * Home Assistant answered `lovelace/config` for [urlPath] with an error ([code], such as `config_not_found` for
         * a dashboard with no saved config, which HA's frontend auto-generates). Only for the dashboard still requested.
         */
        fun onDashboardConfigFailed(urlPath: String?, code: String) {}

        /**
         * The theme this user picked in HA's profile (`core` user data `selectedTheme`), or `null` to follow the
         * server default. [dark] is the explicit light/dark choice, `null` for auto.
         */
        fun onUserTheme(themeName: String?, dark: Boolean?) {}
        fun onDisconnected(reason: String) {}
    }

    private val closed = AtomicBoolean(false)
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, (JSONObject) -> Unit>()
    private val subscriptions = ConcurrentHashMap<Int, (JSONObject) -> Unit>()
    private val sendLock = Any()

    /** Held while an id is taken and its message queued, so ids reach the wire in order (HA rejects a lower id later). */
    private val queueLock = Any()

    /** Guards the whole replacement of the entity subscription (startup and view changes race otherwise). */
    private val entitySubLock = Any()

    /**
     * Every post-auth write goes through this one daemon thread: cards call [callService], [fetchHistory] and the
     * rest from the UI thread, where a socket write throws `NetworkOnMainThreadException`. One thread also keeps
     * strict FIFO order between the reader thread's startup requests and the UI thread's.
     */
    private val sendExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "vitro-ha-send").apply { isDaemon = true } }

    @Volatile private var socket: HaSocket? = null

    /** True between `auth_ok` and the end of the connection; nothing but `auth` may be written before it. */
    @Volatile private var authenticated = false
    @Volatile private var entitySubId: Int? = null
    @Volatile private var activeEntityFilter: Set<String> = emptySet()
    @Volatile private var readerThread: Thread? = null
    @Volatile private var requestedDashboardPath: String? = null
    @Volatile var haConfig: JSONObject = JSONObject()
        private set

    /** The signed-in HA user from `auth/current_user` (`id`, `name`, `is_admin`, ...); empty until it arrives. */
    @Volatile var currentUser: JSONObject = JSONObject()
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
        sendExecutor.shutdownNow()
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
            // An answer for a dashboard the page has since moved away from must not replace the current one.
            if (closed.get() || urlPath != requestedDashboardPath) return@request
            if (res.optBoolean("success", false)) {
                val cfgObj = res.optJSONObject("result") ?: JSONObject()
                listener.onDashboardConfig(urlPath, LovelaceParser.parseDashboard(cfgObj))
            } else {
                listener.onDashboardConfigFailed(urlPath, res.optJSONObject("error")?.optString("code", "").orEmpty())
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
        // The id reaches the callback as an argument: a server that answers before subscribe() returns
        // would otherwise find it still unset, and the one-shot subscription would never be dropped.
        subscribeWithId(msg) { subId, ev ->
            val statesObj = ev.optJSONObject("states") ?: return@subscribeWithId
            val out = LinkedHashMap<String, List<HistoryPoint>>()
            val keys = statesObj.keys()
            while (keys.hasNext()) {
                val eid = keys.next()
                val arr = statesObj.optJSONArray(eid) ?: continue
                val pts = ArrayList<HistoryPoint>(arr.length())
                val numericOnly = eid.startsWith("sensor.") || eid.startsWith("number.") || eid.startsWith("input_number.")
                for (i in 0 until arr.length()) {
                    val p = arr.optJSONObject(i) ?: continue
                    val raw = if (p.has("s")) p.opt("s") else p.opt("state")
                    val text = raw?.toString()?.trim().orEmpty()
                    val value = text.toDoubleOrNull() ?: if (!numericOnly) parseBinaryState(text) else null
                    if (value != null) pts += HistoryPoint(pointTime(p), value)
                }
                if (pts.isNotEmpty()) out[eid] = pts
            }
            onPoints(out)
            // One-shot history fetch: unsubscribe after receiving the initial series so we don't leak subs.
            unsubscribe(subId)
        }
    }

    /**
     * Reads the forecast of weather [entityId] once via `weather/subscribe_forecast` and drops the subscription
     * after the first list, like [fetchHistory].
     */
    fun fetchForecast(entityId: String, forecastType: String = "daily", onForecast: (List<ForecastDay>) -> Unit) {
        val msg = JSONObject()
            .put("type", "weather/subscribe_forecast")
            .put("entity_id", entityId)
            .put("forecast_type", forecastType)
        subscribeWithId(msg) { subId, ev ->
            val arr = ev.optJSONArray("forecast") ?: return@subscribeWithId
            onForecast(ForecastDay.parseList(arr))
            unsubscribe(subId)
        }
    }

    private fun parseBinaryState(state: String): Double? = when (state.lowercase(java.util.Locale.ROOT)) {
        "on", "open", "opening", "home", "unlocked", "playing", "heating", "cooling", "active", "detected", "true" -> 1.0
        "off", "closed", "closing", "not_home", "away", "locked", "idle", "paused", "standby", "clear", "false" -> 0.0
        else -> null
    }

    /** Time of a history point: `lu`/`lc` (compressed, epoch seconds) or `last_updated`/`last_changed` (ISO or epoch). */
    private fun pointTime(p: JSONObject): Double {
        for (key in arrayOf("lu", "lc", "last_updated", "last_changed")) {
            val t = EntityStore.parseTimestamp(p.opt(key))
            if (t > 0.0) return t
        }
        return 0.0
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
        // A token a forced refresh returned after `auth_invalid`: the next handshake uses it, once.
        var refreshed: String? = null
        while (!closed.get()) {
            val wsUrl = HaThreadClient.websocketUrl(haBase)
            if (wsUrl == null) {
                listener.onDisconnected("Invalid Home Assistant URL: $haBase")
                return
            }
            val usingRefreshed = refreshed != null
            val token = refreshed ?: awaitToken(force = false)
            refreshed = null
            if (closed.get()) return
            var ws: HaSocket? = null
            var watchdog: Thread? = null
            try {
                ws = connect(wsUrl)
                socket = ws
                // close() may have run while connect() was in flight and found no socket to close: close this one.
                if (closed.get()) return
                watchdog = authWatchdog(ws)
                val hello = ws.receive()?.let(::JSONObject) ?: throw IOException("Closed before greeting")
                if (hello.optString("type") != "auth_required") throw IOException("Unexpected greeting: ${hello.optString("type")}")

                // Auth is written inline, before the first receive: nothing else may be on the wire until auth_ok.
                ws.send(JSONObject().put("type", "auth").put("access_token", token.orEmpty()).toString())
                val auth = ws.receive()?.let(::JSONObject) ?: throw IOException("Closed during auth")
                when (auth.optString("type")) {
                    "auth_ok" -> {
                        watchdog.interrupt()
                        backoffMs = 1_000L
                        authenticated = true
                        listener.onConnected(auth.optString("ha_version", ""))
                        onAuthenticated()
                    }
                    "auth_invalid" -> {
                        // Try once with a forced refresh token before reporting auth required. The refreshed token
                        // itself goes into the next handshake: the plain provider may still hand out the old one.
                        if (!usingRefreshed) {
                            val fresh = awaitToken(force = true)
                            if (fresh != null && fresh != token) {
                                refreshed = fresh
                                runCatching { ws.close() }
                                continue
                            }
                        }
                        if (!closed.get()) listener.onAuthRequired()
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
                watchdog?.interrupt()
                runCatching { ws?.close() }
                failOutstanding()
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

    /**
     * Ends the connection's state. Every request still waiting for its result fails once, with `disconnected`, so a
     * caller (an island's `callWS` promise) settles. Nothing is replayed: HA may have run it already.
     */
    private fun failOutstanding() {
        val orphaned: List<Pair<Int, (JSONObject) -> Unit>>
        synchronized(queueLock) {
            authenticated = false
            socket = null
            entitySubId = null
            orphaned = pending.entries.map { it.key to it.value }
            pending.clear()
            subscriptions.clear()
            // The next connection asks for its display data afresh; a request lost here never decrements.
            displayRequested.clear()
            displayPending.set(0)
            displayChanged.set(false)
        }
        for ((id, cb) in orphaned) {
            runCatching {
                cb(
                    JSONObject().put("id", id).put("type", "result").put("success", false)
                        .put("error", JSONObject().put("code", "disconnected").put("message", "Connection to Home Assistant lost")),
                )
            }
        }
    }

    /** Closes [ws] if Home Assistant hasn't greeted and accepted the token within [authTimeoutMs]. */
    private fun authWatchdog(ws: HaSocket): Thread = Thread({
        try {
            Thread.sleep(authTimeoutMs)
            if (!authenticated || socket !== ws) runCatching { ws.close() }
        } catch (_: InterruptedException) {
        }
    }, "vitro-ha-auth-timeout").apply {
        isDaemon = true
        start()
    }

    /** Domains and platforms whose display data (icons, state labels) has been asked for on this connection. */
    // newSetFromMap, not ConcurrentHashMap.newKeySet: KeySetView is API 24, and the Mirror runs Android 6.
    private val displayRequested: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val displayPending = java.util.concurrent.atomic.AtomicInteger(0)

    /** Some display data arrived since the last re-render; without it (all refused) there is nothing to redraw. */
    private val displayChanged = AtomicBoolean(false)

    /**
     * Asks HA for the icon and state translations the [entityIds] need (their domains, and the integrations of the
     * ones with a translation key), the way HA's frontend loads them, and re-renders once they've all arrived.
     */
    private fun loadDisplayFor(entityIds: Collection<String>) {
        val (domains, platforms) = HaDisplay.missingFor(entityIds)
        val newDomains = domains.filter { displayRequested.add("d:$it") }
        val newPlatforms = platforms.filter { displayRequested.add("p:$it") }
        fun ask(type: String, category: String, names: List<String>, apply: (JSONObject) -> Unit) {
            if (names.isEmpty()) return
            val msg = JSONObject().put("type", type).put("category", category).put("integration", JSONArray(names))
            if (type == "frontend/get_translations") msg.put("language", "en")
            displayPending.incrementAndGet()
            request(msg) { res ->
                if (res.optBoolean("success", false)) {
                    res.optJSONObject("result")?.let {
                        apply(it)
                        displayChanged.set(true)
                    }
                }
                // Re-render once all of them are in, and only when something display-related changed: a second
                // notification for nothing would look like a state change to every listener.
                if (displayPending.decrementAndGet() == 0 && displayChanged.getAndSet(false)) store.renotifyAll()
            }
        }
        ask("frontend/get_icons", "entity_component", newDomains) { HaDisplay.applyIcons(it, true, newDomains) }
        ask("frontend/get_translations", "entity_component", newDomains) { HaDisplay.applyTranslations(it, true, newDomains) }
        ask("frontend/get_icons", "entity", newPlatforms) { HaDisplay.applyIcons(it, false, newPlatforms) }
        ask("frontend/get_translations", "entity", newPlatforms) { HaDisplay.applyTranslations(it, false, newPlatforms) }
    }

    private fun displayIds(): Collection<String> = activeEntityFilter.ifEmpty { store.all().keys }

    private fun onAuthenticated() {
        displayRequested.clear()
        displayPending.set(0)
        // Icons and state labels are this Home Assistant's: a new connection loads them again.
        HaDisplay.reset()
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
        // 6. The signed-in user and the theme they picked in their HA profile (kept after the steps above)
        request(JSONObject().put("type", "auth/current_user")) { res ->
            if (res.optBoolean("success", false)) {
                res.optJSONObject("result")?.let { currentUser = it }
            }
        }
        request(JSONObject().put("type", "frontend/get_user_data").put("key", "core")) { res ->
            if (res.optBoolean("success", false)) {
                dispatchUserTheme(res.optJSONObject("result")?.optJSONObject("value"))
            }
        }
        subscribe(JSONObject().put("type", "frontend/subscribe_user_data").put("key", "core")) { ev ->
            dispatchUserTheme(ev.optJSONObject("value"))
        }
        // 7. Display data, after everything the first paint needs: the entity registry, then the icons and state
        // translations for the view's entities.
        request(JSONObject().put("type", "config/entity_registry/list_for_display")) { res ->
            if (res.optBoolean("success", false)) res.optJSONObject("result")?.let(HaDisplay::applyRegistry)
            loadDisplayFor(displayIds())
        }
    }

    /** Reads `selectedTheme` (a theme name, or `{theme, dark}`) out of the `core` user data and tells the listener. */
    private fun dispatchUserTheme(value: JSONObject?) {
        var name: String? = null
        var dark: Boolean? = null
        when (val sel = value?.opt("selectedTheme")) {
            is String -> name = sel
            is JSONObject -> {
                name = sel.optString("theme", "")
                if (sel.has("dark") && !sel.isNull("dark")) dark = sel.optBoolean("dark")
            }
        }
        listener.onUserTheme(name?.trim()?.takeIf { it.isNotEmpty() }, dark)
    }

    private fun resubscribeEntities() = synchronized(entitySubLock) {
        entitySubId?.takeIf { it > 0 }?.let { unsubscribe(it) }
        val msg = JSONObject().put("type", "subscribe_entities")
        val filter = activeEntityFilter
        if (filter.isNotEmpty()) {
            msg.put("entity_ids", JSONArray(filter.toList()))
        }
        store.resetSnapshot()
        // The first event is the subscription's snapshot: an entity of [filter] missing from it is gone (deleted while
        // the panel was offline) and leaves the store. Later events are diffs.
        val first = AtomicBoolean(true)
        entitySubId = subscribe(msg) { ev ->
            if (closed.get()) return@subscribe
            val isFirst = first.getAndSet(false)
            store.applyCompressedEvent(ev, snapshotScope = if (isFirst) filter else null)
            if (isFirst) {
                store.markSnapshot(filter.toSet())
                loadDisplayFor(displayIds())
            }
        }.takeIf { it > 0 }
    }

    private fun handleIncoming(msg: JSONObject) {
        val id = msg.optInt("id", -1)
        when (msg.optString("type")) {
            "result" -> pending.remove(id)?.invoke(msg)
            "event" -> msg.optJSONObject("event")?.let { ev -> subscriptions[id]?.invoke(ev) }
        }
    }

    private fun request(msg: JSONObject, onResult: (JSONObject) -> Unit): Int = synchronized(queueLock) {
        val id = nextId.getAndIncrement()
        msg.put("id", id)
        pending[id] = onResult
        if (!send(msg)) {
            pending.remove(id)
            return -1
        }
        id
    }

    private fun subscribe(msg: JSONObject, onEvent: (JSONObject) -> Unit): Int =
        subscribeWithId(msg) { _, ev -> onEvent(ev) }

    /** [subscribe] whose callback also gets its own subscription id, valid even for an event that beats the return. */
    private fun subscribeWithId(msg: JSONObject, onEvent: (id: Int, event: JSONObject) -> Unit): Int = synchronized(queueLock) {
        val id = nextId.getAndIncrement()
        msg.put("id", id)
        subscriptions[id] = { ev -> onEvent(id, ev) }
        pending[id] = { res ->
            if (!res.optBoolean("success", false)) subscriptions.remove(id)
        }
        if (!send(msg)) {
            subscriptions.remove(id)
            pending.remove(id)
            return -1
        }
        id
    }

    private fun unsubscribe(subscriptionId: Int) {
        subscriptions.remove(subscriptionId)
        val msg = JSONObject().put("type", "unsubscribe_events").put("subscription", subscriptionId)
        request(msg) {}
    }

    /**
     * Queues [msg] for the writer thread. True means it was accepted, not that it is on the wire: a write that
     * fails there answers the request with a `send_failed` error and drops the connection, so the caller's
     * callback still fires.
     */
    private fun send(msg: JSONObject): Boolean {
        if (closed.get() || !authenticated) return false
        val ws = socket ?: return false
        val payload = msg.toString()
        val id = msg.optInt("id", -1)
        return try {
            sendExecutor.execute {
                try {
                    synchronized(sendLock) { ws.send(payload) }
                } catch (e: Exception) {
                    if (!closed.get()) {
                        pending.remove(id)?.invoke(
                            JSONObject().put("id", id).put("type", "result").put("success", false)
                                .put("error", JSONObject().put("code", "send_failed").put("message", e.message ?: "Send failed")),
                        )
                        subscriptions.remove(id)
                        runCatching { ws.close() } // a dead write ends the connection; the reader loop reconnects
                    }
                }
            }
            true
        } catch (_: RejectedExecutionException) {
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
        /** Default bound on the greeting and auth exchange; after `auth_ok` reads may idle for as long as they like. */
        const val AUTH_TIMEOUT_MS = 20_000L

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
