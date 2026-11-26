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
        /**
         * The dashboards HA's frontend lists for the signed-in user ([mayOpen], decided as the list arrives): once the
         * user and their panels are known too, and again when HA's panels change (`panels_updated`).
         */
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
         * HA's frontend wouldn't open the dashboard at [urlPath] for the signed-in user: its panel isn't among the
         * user's (`get_panels`, which leaves an admin-only one out for anyone but an administrator). Home Assistant
         * answers its `lovelace/config` all the same; its frontend is what refuses it, routing the URL to the user's
         * default panel instead. Only for the dashboard still requested: in place of [onDashboardConfig] or
         * [onDashboardConfigFailed], or after a config was handed on, when HA's panels change and it no longer is one.
         */
        fun onDashboardRefused(urlPath: String?) {}

        /**
         * The theme this user picked in HA's profile (`core` user data `selectedTheme`), or `null` to follow the
         * server default. [dark] is the explicit light/dark choice, `null` for auto.
         */
        fun onUserTheme(themeName: String?, dark: Boolean?) {}

        /**
         * The user's language or formats changed ([HaLocale], from `language` user data or HA's time zone), live as
         * HA's frontend applies them: what was drawn with the old ones is drawn again.
         */
        fun onLocaleChanged() {}
        /** `auth/current_user` answered: [user] is now [currentUser]. */
        fun onCurrentUser(user: JSONObject) {}
        fun onDisconnected(reason: String) {}
    }

    private val closed = AtomicBoolean(false)
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, (JSONObject) -> Unit>()
    private val subscriptions = ConcurrentHashMap<Int, (JSONObject) -> Unit>()
    private val sendLock = Any()

    /** Held while an id is taken and its message queued, so ids reach the wire in order (HA rejects a lower id later). */
    private val queueLock = Any()

    /** Held while an entity event is applied to [store] and by [close]: no write lands after close() returns. */
    private val storeWriteLock = Any()

    /** The entity subscription allowed to write to the store (replaced on every resubscribe). */
    @Volatile private var entityOwner: Any? = null

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

    /**
     * True once a view has said which entities it needs. Until then nothing is subscribed: an empty
     * [activeEntityFilter] means "every entity" (auto-entities), and on a large Home Assistant that first snapshot can
     * outgrow the socket's message limit before the view's scoped one is asked for. Kept across reconnects.
     */
    @Volatile private var scopeSelected = false
    @Volatile private var readerThread: Thread? = null
    @Volatile private var requestedDashboardPath: String? = null
    @Volatile var haConfig: JSONObject = JSONObject()
        private set

    /** The signed-in HA user from `auth/current_user` (`id`, `name`, `is_admin`, ...); empty until it arrives. */
    @Volatile var currentUser: JSONObject = JSONObject()
        private set

    /**
     * True once `auth/current_user` has answered on this connection: with the user, or with an error, which leaves no
     * user and so no administrator (as HA's frontend has `hass.user` undefined). Each connection asks again: the user
     * may have changed since (another sign-in, a role changed in HA).
     */
    @Volatile private var userKnown = false

    /**
     * `lovelace/dashboards/list` as Home Assistant last answered it on this connection, for the panel's Dashboard select
     * ([Listener.onDashboards]); null until it has, or when it refused. Who may open which is [mayOpen]'s.
     */
    @Volatile var dashboards: List<DashboardSummary>? = null
        private set

    /**
     * The url paths of the panels HA's frontend may route to for this user, from `get_panels` on this connection: HA
     * itself leaves out an admin-only one for anyone but an administrator, with `frontend/update_panel`'s
     * `require_admin` override applied, which `lovelace/dashboards/list` doesn't see. Null until it has answered, or
     * when it refused.
     */
    @Volatile private var panels: Set<String>? = null

    /**
     * True once the latest `get_panels` has answered, with the panels or with an error. False again while HA's panels
     * changed (`panels_updated`) and the new answer is on its way: nothing is handed on with the old one.
     */
    @Volatile private var panelsAnswered = false

    /** Bumped for every `get_panels` asked: only the latest answer counts. */
    private val panelsGeneration = AtomicInteger(0)

    /**
     * The dashboard whose config was last handed on ([hasDelivered]): checked again when HA's panels change. The path
     * state (this, [requestedDashboardPath], [dashboardActive], [missedRefetch]) is written under [pathLock], and every
     * check whose result decides a write sits in the same section as that write; listeners are called after it.
     */
    @Volatile private var deliveredDashboard: String? = null
    @Volatile private var hasDelivered = false

    /** Guards [heldForAccess]; never held while a held answer runs. */
    private val accessLock = Any()

    /** Answers that wait for HA to say who the user is and which panels they have ([afterAccessKnown]). */
    private val heldForAccess = ArrayList<() -> Unit>()

    private fun accessKnown(): Boolean = userKnown && panelsAnswered

    /**
     * Runs [block] once Home Assistant has said who the user is and which panels they have, now if it has: a dashboard
     * config isn't handed on before Vitro knows whether HA's frontend would show it to this user, and every card is
     * built knowing whether the user is an administrator (HA's error cards show them more).
     */
    private fun afterAccessKnown(block: () -> Unit) {
        synchronized(accessLock) {
            if (!accessKnown()) {
                heldForAccess += block
                return
            }
        }
        block()
    }

    /** Runs what [afterAccessKnown] held, once both answers are in. */
    private fun releaseHeldForAccess() {
        val ready = synchronized(accessLock) {
            if (!accessKnown()) return
            ArrayList(heldForAccess).also { heldForAccess.clear() }
        }
        ready.forEach { it() }
    }

    /**
     * Whether HA's frontend opens the dashboard at [urlPath] (the default one, `lovelace`, for none) for the signed-in
     * user: its router (`partial-panel-resolver`) routes only to the user's panels, `get_panels`, and sends any other
     * URL to the user's default panel. An administrator has every panel there is: a path that is none is HA's to answer
     * as not found. With no panels (HA refused them), Vitro can't tell for anyone else: the default dashboard opens,
     * and HA's frontend decides for any other.
     */
    fun mayOpen(urlPath: String?): Boolean {
        if (currentUser.optBoolean("is_admin", false)) return true
        val panel = urlPath?.takeIf { it.isNotEmpty() } ?: DEFAULT_DASHBOARD_PANEL
        val known = panels ?: return panel == DEFAULT_DASHBOARD_PANEL
        return panel in known
    }

    /**
     * Asks HA for this user's panels (`get_panels`, as HA's frontend does on connect and on every `panels_updated`).
     * Until the answer, configs wait ([afterAccessKnown]); then the dashboard already handed on is checked again.
     */
    private fun fetchPanels() {
        val generation = synchronized(accessLock) {
            panelsAnswered = false
            panelsGeneration.incrementAndGet()
        }
        request(JSONObject().put("type", "get_panels")) { res ->
            // A dropped connection asks again on the reconnect.
            if (isTransportError(res)) return@request
            val result = res.optJSONObject("result")
            val got = if (res.optBoolean("success", false) && result != null) result.keys().asSequence().toSet() else null
            synchronized(accessLock) {
                if (generation != panelsGeneration.get()) return@request
                panels = got
                panelsAnswered = true
            }
            releaseHeldForAccess()
            recheckDelivered()
        }
    }

    /**
     * The dashboard on screen, checked again against the user's panels as they are now: one HA's frontend no longer
     * routes to for this user (it became admin only, or was removed) is refused ([Listener.onDashboardRefused]).
     */
    private fun recheckDelivered() = afterAccessKnown {
        // Read, check and clear as one, under [pathLock] like every write of the claim: a [claimDashboard] on the main
        // thread can't land between them and have its claim cleared. The listener is called after, outside the lock.
        val refused = synchronized(pathLock) {
            if (closed.get() || !dashboardActive || !hasDelivered) return@afterAccessKnown
            val path = deliveredDashboard
            if (path != requestedDashboardPath || mayOpen(path)) return@afterAccessKnown
            hasDelivered = false
            path
        }
        listener.onDashboardRefused(refused)
    }

    /** `lovelace/dashboards/list`, for the panel's Dashboard select: the user's dashboards, once their panels are known. */
    private fun fetchDashboardList() {
        request(JSONObject().put("type", "lovelace/dashboards/list")) { res ->
            if (!res.optBoolean("success", false)) return@request
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
            dashboards = out
            // Filtered as it's handed on, under the panels known then: the user's list, as HA's frontend has it.
            afterAccessKnown { listener.onDashboards(out.filter { mayOpen(it.urlPath) }) }
        }
    }

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
        if (sent <= 0) done(false, null, JSONObject().put("code", DISCONNECTED).put("message", "Not connected"))
    }

    fun start(dashboardUrlPath: String?) {
        synchronized(pathLock) { requestedDashboardPath = dashboardUrlPath }
        if (closed.get()) return
        // A new client may be another Home Assistant, or another user: back to the browser's language and formats
        // until this one's profile answers. A reconnect of this client keeps them, as HA's page does.
        HaLocale.reset()
        // HA's frontend strings as this HA served them last time, until it answers (or when it can't be reached).
        // This client's requests are the ones that install strings from now on (a retired one's late download doesn't).
        origin?.let { HaFrontend.claim(this, it) }
        origin?.let { o -> HaFrontend.ensureCached(this, o, HaLocale.current.language) { if (!closed.get()) listener.onLocaleChanged() } }
        val thread = Thread({ loop() }, "vitro-ha-ws").apply { isDaemon = true }
        readerThread = thread
        thread.start()
    }

    fun close() {
        // Under the store lock: an entity event already past its `closed` check finishes before this returns.
        synchronized(storeWriteLock) { closed.set(true) }
        runCatching { socket?.close() }
        socket = null
        sendExecutor.shutdownNow()
        synchronized(registryLock) { registryTimer?.shutdownNow() }
    }

    /** Switches the entity subscription to [entityIds] for the active view. */
    fun subscribeViewEntities(entityIds: Set<String>) {
        if (scopeSelected && entityIds == activeEntityFilter && entitySubId != null) return
        activeEntityFilter = entityIds
        scopeSelected = true
        if (socket == null) return
        resubscribeEntities()
    }

    /**
     * False while the page shows a fallback (non-dashboard) page: no dashboard is fetched on a reconnect or a
     * `lovelace_updated`, and an answer still on its way is dropped.
     */
    @Volatile private var dashboardActive = true

    /**
     * Held while the requested path is read and a request for it is sent, or the path is changed: a refetch that read
     * path A can't land after a navigation to B and make A the requested one again. Taken before [queueLock], never
     * inside it (no result callback runs under queueLock).
     */
    private val pathLock = Any()

    /**
     * The page shows [urlPath] again without asking: an answer still on its way for another path is dropped. The
     * config on screen stays, but whether the user may still open it is checked again ([recheckDelivered]): HA's panels
     * may have changed while the fallback showed, and a check waits for panels being asked for again.
     */
    fun claimDashboard(urlPath: String?) {
        synchronized(pathLock) {
            requestedDashboardPath = urlPath
            dashboardActive = true
            deliveredDashboard = urlPath
            hasDelivered = true
            // A lovelace_updated or reconnect skipped while the fallback showed: the dashboard may have changed.
            if (missedRefetch) fetchDashboard(urlPath)
        }
        recheckDelivered()
    }

    /**
     * A refetch was skipped while the page showed the fallback; the next claim fetches instead of reusing. Cleared
     * only by [dashboardShown], where the page puts a config on screen: an answer dropped on the way leaves it set.
     */
    @Volatile private var missedRefetch = false

    /** The page put the config it got on screen: nothing skipped is owed any more. */
    fun dashboardShown() = synchronized(pathLock) {
        missedRefetch = false
    }

    /** The page left the dashboards for the fallback; the next [fetchDashboard] or [claimDashboard] takes them back. */
    fun releaseDashboard() = synchronized(pathLock) {
        dashboardActive = false
        hasDelivered = false
    }

    /** Fetches the requested dashboard again (reconnect, `lovelace_updated`), unless the page has left the dashboards. */
    private fun refetchDashboard() = synchronized(pathLock) {
        if (dashboardActive) fetchDashboard(requestedDashboardPath) else missedRefetch = true
    }

    /** Loads a different Lovelace dashboard config over the live socket. */
    fun fetchDashboard(urlPath: String?) = synchronized(pathLock) {
        requestedDashboardPath = urlPath
        dashboardActive = true
        val msg = JSONObject().put("type", "lovelace/config")
        if (!urlPath.isNullOrEmpty()) msg.put("url_path", urlPath)
        request(msg) { res ->
            // The connection, not Home Assistant, failed: the reconnect asks again (onAuthenticated). Reporting it
            // would send a dashboard that exists to the fallback as "not found".
            if (isTransportError(res)) return@request
            // Nothing is handed on before HA has said whether this user may see the dashboard.
            afterAccessKnown {
                val ok = res.optBoolean("success", false)
                // Whether the answer is still the page's, whether the user may open it, and the claim it makes are one
                // decision under [pathLock]: a [claimDashboard] or [fetchDashboard] for another path on the main thread
                // can't land between them and have its claim overwritten by this stale answer. Listeners run after.
                val allowed = synchronized(pathLock) {
                    // An answer for a dashboard the page has since moved away from must not replace the current one.
                    if (closed.get() || !dashboardActive || urlPath != requestedDashboardPath) return@afterAccessKnown
                    val allowed = mayOpen(urlPath)
                    deliveredDashboard = urlPath
                    hasDelivered = allowed && ok
                    allowed
                }
                if (!allowed) {
                    listener.onDashboardRefused(urlPath)
                } else if (ok) {
                    val cfgObj = res.optJSONObject("result") ?: JSONObject()
                    listener.onDashboardConfig(urlPath, LovelaceParser.parseDashboard(cfgObj))
                } else {
                    listener.onDashboardConfigFailed(urlPath, res.optJSONObject("error")?.optString("code", "").orEmpty())
                }
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
        // Not queued (no connection): the caller hears it now, so an optimistic write rolls back.
        if (request(msg) { res -> done?.invoke(res.optBoolean("success", false)) } <= 0) done?.invoke(false)
    }

    /**
     * Fetches numeric history points for [entityIds] over the last [hoursToShow] hours, once, with
     * `history/history_during_period`: the whole window in one answer. (`history/stream`'s first event can leave out
     * a change the recorder hasn't committed yet, which only a later catch-up event carries.) A failed request calls
     * nothing back.
     */
    fun fetchHistory(entityIds: List<String>, hoursToShow: Int = 24, onPoints: (Map<String, List<HistoryPoint>>) -> Unit) {
        if (entityIds.isEmpty()) return
        val nowMs = System.currentTimeMillis()
        val startMs = nowMs - hoursToShow.coerceAtLeast(1) * 3_600_000L
        val msg = JSONObject()
            .put("type", "history/history_during_period")
            .put("entity_ids", JSONArray(entityIds))
            .put("start_time", formatIsoUtc(startMs))
            .put("end_time", formatIsoUtc(nowMs + 60_000L))
            .put("minimal_response", true)
            .put("no_attributes", true)
            .put("significant_changes_only", false)
        request(msg) { res ->
            if (!res.optBoolean("success", false)) return@request
            val statesObj = res.optJSONObject("result") ?: return@request
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

    /**
     * Subscribes [msg] (any subscription command, e.g. `history/stream`) and hands each of its events to [onEvent], on
     * the socket's thread. [onError] runs once if Home Assistant rejects the command (with its `error`), or with null
     * when it can't be sent; cancelling with the returned handle never calls it. [onStarted] runs once Home Assistant
     * accepted it (its `result`), unless it was cancelled first.
     */
    fun subscribeWs(
        msg: JSONObject,
        onEvent: (JSONObject) -> Unit,
        onError: (JSONObject?) -> Unit,
        onStarted: () -> Unit = {},
    ): () -> Unit {
        val subId = subscribeWithId(JSONObject(msg.toString()), onError, onStarted) { _, ev -> onEvent(ev) }
        if (subId <= 0) onError(null)
        return { if (subId > 0) unsubscribe(subId) }
    }

    // ---- Registries for web islands ----

    /** Guards the registry feed's state below. Taken before [queueLock] (start and stop send), never inside it. */
    private val registryLock = Any()
    private val registryWatchers = ArrayList<(JSONObject) -> Unit>()

    /** The connection ([connectionGen]) the feed runs on, -1 when it doesn't run. */
    private var registryGen = -1
    private var registrySubs: List<Int> = emptyList()

    /** Each registry's last `result` on this connection; the set goes out once all four answered. */
    private val registryParts = HashMap<HassRegistries.Kind, Any?>()
    private var registriesNow: JSONObject? = null

    /** Registries whose refetch is waiting out [REGISTRY_DEBOUNCE_MS]. */
    private val registryDue = Collections.newSetFromMap(ConcurrentHashMap<HassRegistries.Kind, Boolean>())
    @Volatile private var registryTimer: java.util.concurrent.ScheduledExecutorService? = null

    /**
     * HA's registries as its frontend hands them to cards ([HassRegistries]: `{entities, devices, areas, floors}`), for
     * web islands. Nothing is fetched or subscribed until the first watcher: then the four lists are read and each is
     * read again on its `*_registry_updated` event (debounced, as HA's frontend does), on every connection, until the
     * last handle is called. [onChange] gets the whole set on the socket's thread, now when one is known and on every
     * change; the returned handle stops it.
     */
    fun watchRegistries(onChange: (JSONObject) -> Unit): () -> Unit {
        val now = synchronized(registryLock) {
            registryWatchers += onChange
            startRegistries()
            registriesNow
        }
        now?.let(onChange)
        return {
            synchronized(registryLock) {
                if (registryWatchers.remove(onChange) && registryWatchers.isEmpty()) stopRegistries()
            }
        }
    }

    /** Starts the feed on this connection when someone watches and it doesn't run yet. Under [registryLock]. */
    private fun startRegistries() {
        val gen = connectionGen.get()
        if (registryWatchers.isEmpty() || !authenticated || registryGen == gen) return
        registryGen = gen
        registryParts.clear()
        registrySubs = HassRegistries.Kind.values().map { kind ->
            subscribe(JSONObject().put("type", "subscribe_events").put("event_type", kind.event)) { refetchRegistry(kind, gen) }
        }.filter { it > 0 }
        for (kind in HassRegistries.Kind.values()) fetchRegistry(kind, gen)
    }

    /** Stops the feed and forgets the registries: nobody watches. Under [registryLock]. */
    private fun stopRegistries() {
        if (registryGen == connectionGen.get()) registrySubs.forEach(::unsubscribe)
        registrySubs = emptyList()
        registryGen = -1
        registryParts.clear()
        registriesNow = null
    }

    private fun fetchRegistry(kind: HassRegistries.Kind, gen: Int) {
        request(JSONObject().put("type", kind.command)) { res ->
            val (set, watchers) = synchronized(registryLock) {
                if (gen != connectionGen.get() || registryGen != gen) return@request
                // A refused list (an older Home Assistant without floors) counts as empty; a failed refetch keeps the
                // last answer.
                if (res.optBoolean("success", false)) registryParts[kind] = res.opt("result")
                else if (kind !in registryParts) registryParts[kind] = null
                if (registryParts.size < HassRegistries.Kind.values().size) return@request
                val built = HassRegistries.build(registryParts)
                registriesNow = built
                built to registryWatchers.toList()
            }
            watchers.forEach { it(set) }
        }
    }

    /** A registry changed: read it again once its burst of events is over (an integration reload sends dozens). */
    private fun refetchRegistry(kind: HassRegistries.Kind, gen: Int) {
        if (!registryDue.add(kind)) return
        val timer = synchronized(registryLock) {
            // Checked under the lock close() takes to stop the timer: a closed client starts no new one.
            if (closed.get()) return
            registryTimer ?: Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "vitro-ha-registries").apply { isDaemon = true }
            }.also { registryTimer = it }
        }
        try {
            timer.schedule({
                registryDue.remove(kind)
                // Only for the feed still running: the last watcher may have gone, or the connection, meanwhile. Checked
                // and sent under one lock, which stopRegistries() takes too: a stop can't slip in between.
                synchronized(registryLock) {
                    if (registryGen == gen && gen == connectionGen.get()) fetchRegistry(kind, gen)
                }
            }, REGISTRY_DEBOUNCE_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            registryDue.remove(kind)
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
                    // A clean close by HA (or its proxy) is still a lost connection: the panel shows it and retries.
                    val raw = ws.receive() ?: throw IOException("Closed by Home Assistant")
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
        val orphaned: List<Int>
        synchronized(queueLock) {
            // The display requests of this connection settle no more: their callbacks (below, or a late send_failed)
            // see an older generation than the next connection's.
            connectionGen.incrementAndGet()
            authenticated = false
            socket = null
            entitySubId = null
            orphaned = pending.keys.toList()
            subscriptions.clear()
        }
        for (id in orphaned) {
            // Claimed with remove: the writer's send_failed path may have taken it already, and only one may call it.
            val cb = pending.remove(id) ?: continue
            runCatching {
                cb(
                    JSONObject().put("id", id).put("type", "result").put("success", false)
                        .put("error", JSONObject().put("code", DISCONNECTED).put("message", "Connection to Home Assistant lost")),
                )
            }
        }
        // After the callbacks (display requests among them decrement the counter): the next connection asks for its
        // display data afresh.
        displayRequested.clear()
        displayPending.set(0)
        displayChanged.set(false)
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
    // A set over a ConcurrentHashMap through Collections: the map's own key-set view is API 24, the Mirror runs 23.
    private val displayRequested: MutableSet<String> = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val displayPending = java.util.concurrent.atomic.AtomicInteger(0)

    /** Bumped when a connection ends: display callbacks of an earlier one leave the counters alone. */
    private val connectionGen = AtomicInteger(0)

    /** Some display data arrived since the last re-render; without it (all refused) there is nothing to redraw. */
    private val displayChanged = AtomicBoolean(false)

    /**
     * Asks HA for the icon and state translations the [entityIds] need (their domains, and the integrations of the
     * ones with a translation key), the way HA's frontend loads them, and re-renders once they've all arrived.
     */
    /**
     * Writes display data ([HaDisplay] is shared by every client) only while this client is open, under the lock
     * close() takes: a client retired mid-callback can't put its server's names into the next one's.
     */
    private fun applyDisplay(write: () -> Unit) = synchronized(storeWriteLock) {
        if (closed.get()) return
        write()
        displayChanged.set(true)
    }

    /**
     * One display request answered. Re-render once all of them are in, and only when some display data arrived: a
     * notification for nothing would look like a state change to every listener.
     */
    private fun settleDisplay() {
        if (displayPending.decrementAndGet() == 0 && displayChanged.getAndSet(false) && !closed.get()) store.renotifyAll()
    }

    private fun loadDisplayFor(entityIds: Collection<String>) {
        val (domains, platforms) = HaDisplay.missingFor(entityIds)
        val newDomains = domains.filter { displayRequested.add("d:$it") }
        val newPlatforms = platforms.filter { displayRequested.add("p:$it") }
        fun ask(type: String, category: String, names: List<String>, apply: (JSONObject) -> Unit) {
            if (names.isEmpty()) return
            val msg = JSONObject().put("type", type).put("category", category).put("integration", JSONArray(names))
            val language = HaLocale.current.language
            if (type == "frontend/get_translations") msg.put("language", language)
            displayPending.incrementAndGet()
            val gen = connectionGen.get()
            request(msg) { res ->
                if (gen != connectionGen.get()) return@request
                // Words in a language the user has left since: the request in the new one is on its way.
                if (type == "frontend/get_translations" && language != HaLocale.current.language) { settleDisplay(); return@request }
                if (res.optBoolean("success", false)) res.optJSONObject("result")?.let { r -> applyDisplay { apply(r) } }
                settleDisplay()
            }
        }
        ask("frontend/get_icons", "entity_component", newDomains) { HaDisplay.applyIcons(it, true, newDomains) }
        ask("frontend/get_translations", "entity_component", newDomains) { HaDisplay.applyTranslations(it, true, newDomains) }
        ask("frontend/get_icons", "entity", newPlatforms) { HaDisplay.applyIcons(it, false, newPlatforms) }
        ask("frontend/get_translations", "entity", newPlatforms) { HaDisplay.applyTranslations(it, false, newPlatforms) }
    }

    /**
     * Applies a locale update and, when the language changed, asks again for every translation in the new one (the
     * frontend's `_refetchCachedHassTranslations`); the listener draws the view again either way.
     */
    private fun applyLocale(update: () -> Any?) {
        // Under the lock close() takes: an update that passed the check finishes before close() returns, so a
        // retired client can't write its user's locale over the next client's.
        val languageChanged = synchronized(storeWriteLock) {
            if (closed.get()) return
            val before = HaLocale.current
            val zone = HaLocale.timeZone().id
            update()
            // Only what changes a text: HA's own zone arriving while the user keeps the panel's changes nothing.
            if (HaLocale.current == before && HaLocale.timeZone().id == zone) return
            HaLocale.current.language != before.language
        }
        if (languageChanged) languageChanged()
        listener.onLocaleChanged()
    }

    /** The words of a language the user just picked: HA's backend translations again, and its frontend strings. */
    private fun languageChanged() {
        synchronized(storeWriteLock) {
            if (closed.get()) return
            HaDisplay.forgetTranslations()
            displayRequested.removeAll { it.startsWith("d:") || it.startsWith("p:") }
        }
        loadDisplayFor(displayIds())
        ensureStrings()
    }

    /** HA's own origin, where its frontend files are (`http(s)://host:port`). */
    private val origin: String? = runCatching { java.net.URL(haBase).let { "${it.protocol}://${it.authority}" } }.getOrNull()

    /**
     * HA's frontend strings for the user's language, fetched from HA ([HaFrontend]); the view draws again once they
     * are in. A browser language matched again against HA's own list may change the language: its words follow.
     */
    private fun ensureStrings(revalidate: Boolean = false) {
        val o = origin ?: return
        val language = HaLocale.current.language
        HaFrontend.ensure(this, o, language, revalidate) {
            if (closed.get()) return@ensure
            if (HaLocale.current.language != language) languageChanged()
            listener.onLocaleChanged()
        }
    }

    private fun displayIds(): Collection<String> = activeEntityFilter.ifEmpty { store.all().keys }

    private fun onAuthenticated() {
        // Who the user is and which panels they have are this connection's to say: nothing is handed on with the last
        // one's answers. What a dropped connection held goes too: this one asks for it again, and decides afresh.
        synchronized(accessLock) {
            heldForAccess.clear()
            userKnown = false
            panels = null
            panelsAnswered = false
        }
        synchronized(pathLock) { hasDelivered = false }
        displayRequested.clear()
        displayPending.set(0)
        // Icons and state labels are this Home Assistant's: a new connection loads them again.
        HaDisplay.reset()
        // 0. The user's language and formats (HA's translations-mixin subscribes to them on connect), first: the
        // translations asked for below are in that language. Its first event is the current value.
        subscribe(JSONObject().put("type", "frontend/subscribe_user_data").put("key", "language")) { ev ->
            applyLocale { HaLocale.applyUserData(ev.optJSONObject("value")) }
        }
        // HA's frontend strings in the language in use now; a profile language arriving asks again. Every connection
        // reads HA's index again (it may have been upgraded since); an unchanged frontend reuses its cached files.
        ensureStrings(revalidate = true)
        // HA config (unit_system, location_name, time_zone — needed by custom cards reading hass.config, and by
        // `time_zone: server`)
        request(JSONObject().put("type", "get_config")) { res ->
            if (res.optBoolean("success", false)) {
                res.optJSONObject("result")?.let { haConfig = it; applyLocale { HaLocale.applyConfig(it) } }
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
        fetchDashboardList()
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
        refetchDashboard()
        subscribe(JSONObject().put("type", "subscribe_events").put("event_type", "lovelace_updated")) {
            refetchDashboard()
        }
        // 5. The entity subscription, once a view has picked its entities (on a reconnect, the last view's).
        if (scopeSelected) resubscribeEntities()
        // 6. The signed-in user and the theme they picked in their HA profile (kept after the steps above)
        request(JSONObject().put("type", "auth/current_user")) { res ->
            // A dropped connection asks again on the reconnect.
            if (isTransportError(res)) return@request
            // A refusal leaves no user, not the last one: no administrator.
            if (!res.optBoolean("success", false) || res.optJSONObject("result") == null) currentUser = JSONObject()
            if (res.optBoolean("success", false)) {
                res.optJSONObject("result")?.let {
                    currentUser = it
                    listener.onCurrentUser(it)
                }
            }
            userKnown = true
            releaseHeldForAccess()
        }
        request(JSONObject().put("type", "frontend/get_user_data").put("key", "core")) { res ->
            if (res.optBoolean("success", false)) {
                dispatchUserTheme(res.optJSONObject("result")?.optJSONObject("value"))
            }
        }
        subscribe(JSONObject().put("type", "frontend/subscribe_user_data").put("key", "core")) { ev ->
            dispatchUserTheme(ev.optJSONObject("value"))
        }
        // The user's panels, which decide what HA's frontend opens for them ([mayOpen]), again whenever HA changes its
        // panels (a dashboard added, removed, or made admin only), as HA's frontend's panel collection does.
        fetchPanels()
        subscribe(JSONObject().put("type", "subscribe_events").put("event_type", "panels_updated")) {
            fetchPanels()
            fetchDashboardList()
        }
        // 7. Display data, after everything the first paint needs: the entity registry, then the icons and state
        // translations for the view's entities.
        // The registry counts as one display request: when it is the only one that succeeds, cards still re-render.
        val gen = connectionGen.get()
        displayPending.incrementAndGet()
        request(JSONObject().put("type", "config/entity_registry/list_for_display")) { res ->
            if (gen != connectionGen.get()) return@request
            if (res.optBoolean("success", false)) res.optJSONObject("result")?.let { r -> applyDisplay { HaDisplay.applyRegistry(r) } }
            loadDisplayFor(displayIds())
            settleDisplay()
        }
        // Device names, for HA's default tile names ("<device> <entity>").
        displayPending.incrementAndGet()
        request(JSONObject().put("type", "config/device_registry/list")) { res ->
            if (gen != connectionGen.get()) return@request
            if (res.optBoolean("success", false)) res.optJSONArray("result")?.let { r -> applyDisplay { HaDisplay.applyDevices(r) } }
            settleDisplay()
        }
        // Areas, for the area card (its name, icon, picture and sensors).
        displayPending.incrementAndGet()
        request(JSONObject().put("type", "config/area_registry/list")) { res ->
            if (gen != connectionGen.get()) return@request
            if (res.optBoolean("success", false)) res.optJSONArray("result")?.let { r -> applyDisplay { HaDisplay.applyAreas(r) } }
            settleDisplay()
        }
        // 8. The registries web islands read, when one does (they ask on first read): again on every connection.
        synchronized(registryLock) { startRegistries() }
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
        // This subscription owns the store's entity writes from now on; a retired one's events (one the reader
        // already picked up when it was replaced) are dropped, even before subscribe() returns. Switched without the
        // write lock: this runs on the UI thread when the view changes, and the reader can hold that lock for the
        // whole first snapshot (hundreds of ms on a panel). An old event mid-commit finishes its write, and its
        // snapshot mark, keyed to its owner, no longer lands.
        val owner = Any()
        entityOwner = owner
        store.resetSnapshot(owner)
        // The first event is the subscription's snapshot: an entity of [filter] missing from it is gone (deleted while
        // the panel was offline) and leaves the store. Later events are diffs.
        val first = AtomicBoolean(true)
        entitySubId = subscribe(msg) { ev -> synchronized(storeWriteLock) {
            // Checked under the lock close() takes: a closed client never writes to the (shared) store again.
            if (closed.get() || entityOwner !== owner) return@subscribe
            val isFirst = first.getAndSet(false)
            store.applyCompressedEvent(ev, snapshotScope = if (isFirst) filter else null)
            if (isFirst) {
                store.markSnapshot(filter.toSet(), owner)
                loadDisplayFor(displayIds())
            }
        } }.takeIf { it > 0 }
    }

    private fun handleIncoming(msg: JSONObject) {
        val id = msg.optInt("id", -1)
        when (msg.optString("type")) {
            "result" -> pending.remove(id)?.invoke(msg)
            // The answer to a `ping` (an island's callWS can send one): settle it as a result, or its promise hangs.
            "pong" -> pending.remove(id)?.invoke(JSONObject().put("id", id).put("type", "result").put("success", true).put("result", JSONObject.NULL))
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
    private fun subscribeWithId(
        msg: JSONObject,
        onError: ((JSONObject?) -> Unit)? = null,
        onStarted: (() -> Unit)? = null,
        onEvent: (id: Int, event: JSONObject) -> Unit,
    ): Int = synchronized(queueLock) {
        val id = nextId.getAndIncrement()
        msg.put("id", id)
        subscriptions[id] = { ev -> onEvent(id, ev) }
        val state = SubscriptionState()
        subscriptionStates[id] = state
        pending[id] = { res ->
            // The result and a cancel decide under the subscription's own lock, so neither can slip past the other.
            var started = false
            val report = synchronized(state) {
                state.settled = true
                subscriptionStates.remove(id)
                if (res.optBoolean("success", false)) {
                    started = !state.cancelled
                    false
                } else {
                    subscriptions.remove(id)
                    // Only an explicit cancel keeps it quiet: a rejection or a lost connection (which clears the
                    // subscriptions before it answers this) is reported once.
                    !state.cancelled
                }
            }
            if (report) onError?.invoke(res.optJSONObject("error"))
            if (started) onStarted?.invoke()
        }
        if (!send(msg)) {
            subscriptions.remove(id)
            subscriptionStates.remove(id)
            pending.remove(id)
            return -1
        }
        id
    }

    /** Subscriptions cancelled before their result came: their failure is the cancel's, not an error to report. */
    /** One subscription until its result: whether it was cancelled first, and whether the result has settled it. */
    private class SubscriptionState {
        var cancelled = false
        var settled = false
    }

    /** Subscriptions waiting for their result; each leaves the map when it settles, so nothing outlives a subscription. */
    private val subscriptionStates = java.util.concurrent.ConcurrentHashMap<Int, SubscriptionState>()

    private fun unsubscribe(subscriptionId: Int) {
        subscriptionStates[subscriptionId]?.let { st -> synchronized(st) { if (!st.settled) st.cancelled = true } }
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
                                .put("error", JSONObject().put("code", SEND_FAILED).put("message", e.message ?: "Send failed")),
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
        /** Error codes this client makes up when the connection fails (HA never sent them). */
        const val DISCONNECTED = "disconnected"

        /** The default dashboard's panel (`lovelace`), the one a request with no url path opens. */
        const val DEFAULT_DASHBOARD_PANEL = "lovelace"
        const val SEND_FAILED = "send_failed"

        /** A result the client made up for a failed connection, not an answer from Home Assistant. */
        fun isTransportError(res: JSONObject): Boolean =
            !res.optBoolean("success", false) && res.optJSONObject("error")?.optString("code") in setOf(DISCONNECTED, SEND_FAILED)

        /** How long a registry's refetch waits for its burst of update events to end, as HA's frontend debounces its own. */
        internal const val REGISTRY_DEBOUNCE_MS = 500L

        /** Default bound on the greeting and auth exchange; after `auth_ok` reads may idle for as long as they like. */
        const val AUTH_TIMEOUT_MS = 20_000L

        /** Bound on the HTTP upgrade (the `101 Switching Protocols` answer), before the auth watchdog runs. */
        const val UPGRADE_TIMEOUT_MS = 20_000

        /**
         * Long-lived RFC 6455 socket for the dashboard WebSocket. The upgrade answer must come within
         * [upgradeTimeoutMs] (a proxy that accepts TCP and then stalls can't hold the attempt forever): a timer closes
         * the TCP socket if it doesn't, which ends the blocked read. No read timeout is set at all, so an idle dashboard
         * stays connected on `ws` and `wss` alike (a TLS socket keeps the timeout set on it, not on the TCP below);
         * the greeting and auth are bounded by the client's watchdog. [HaSocket.close] aborts: it drops TCP without
         * waiting on a write that holds the socket's write lock (the page disposes on the main thread).
         */
        fun connectLongLived(url: String, upgradeTimeoutMs: Int = UPGRADE_TIMEOUT_MS): HaSocket {
            val connector = HappyEyeballs()
            val lock = Any()
            var tcp: java.net.Socket? = null
            var expired = false
            var finished = false
            val timer = Thread({
                try {
                    Thread.sleep(upgradeTimeoutMs.toLong())
                    synchronized(lock) {
                        if (!finished) {
                            expired = true
                            runCatching { tcp?.close() }
                        }
                    }
                } catch (_: InterruptedException) {
                }
            }, "vitro-ha-upgrade-timeout").apply { isDaemon = true; start() }
            val ws = try {
                WsClient.connect(url, connectTimeoutMs = 8_000, readTimeoutMs = 0) { host, port, timeoutMs ->
                    val s = connector.connect(host, port, timeoutMs)
                    synchronized(lock) {
                        tcp = s
                        if (expired) { runCatching { s.close() }; throw java.net.SocketTimeoutException("upgrade took too long") }
                    }
                    s
                }
            } catch (e: IOException) {
                // The timer closing the socket mid-upgrade surfaces as a closed-socket error: report it as the timeout.
                if (synchronized(lock) { expired }) throw java.net.SocketTimeoutException("upgrade took too long")
                throw e
            } finally {
                synchronized(lock) { finished = true }
                timer.interrupt()
            }
            if (synchronized(lock) { expired }) {
                ws.abort()
                throw java.net.SocketTimeoutException("upgrade took too long")
            }
            return object : HaSocket {
                override fun send(text: String) = ws.sendText(text)
                override fun receive(): String? {
                    while (true) {
                        val m = ws.receive() ?: return null
                        if (m.text) return m.data.toString(Charsets.UTF_8)
                    }
                }
                override fun close() = ws.abort()
            }
        }

        private fun formatIsoUtc(epochMs: Long): String {
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
            return fmt.format(java.util.Date(epochMs))
        }
    }
}
