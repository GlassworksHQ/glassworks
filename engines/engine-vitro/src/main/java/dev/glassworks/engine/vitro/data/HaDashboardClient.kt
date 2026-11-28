/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.data

import dev.glassworks.auth.HaSocket
import dev.glassworks.auth.HaThreadClient
import dev.glassworks.net.HappyEyeballs
import dev.glassworks.sendspin.WsClient
import dev.glassworks.engine.vitro.VitroFailures
import dev.glassworks.engine.vitro.model.DashboardConfig
import dev.glassworks.engine.vitro.model.DashboardSummary
import dev.glassworks.engine.vitro.model.LovelaceParser
import dev.glassworks.engine.vitro.model.LovelaceResource
import dev.glassworks.engine.vitro.model.LovelaceResourceList
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
 * Home Assistant token, fetches Lovelace config + resources + themes, and follows the entities the page
 * asks for ([followEntities], `subscribe_entities`): every entity the shown dashboard references, once per
 * connection, as HA's frontend subscribes once at startup. Pure JVM so it is unit-testable with a scripted
 * fake socket.
 */
class HaDashboardClient(
    private val haBase: String,
    private val tokenProvider: (force: Boolean, done: (String?) -> Unit) -> Unit,
    private val store: EntityStore,
    private val connect: (url: String) -> HaSocket = ::connectLongLived,
    private val listener: Listener,
    /** How long the greeting and the auth exchange may take before the connection is dropped and retried. */
    private val authTimeoutMs: Long = AUTH_TIMEOUT_MS,
    /**
     * How often a live connection is checked ([keepAlive]): a `ping` that must be answered within [pongTimeoutMs], and
     * the token asked for again so its consumers get a fresh one before it expires.
     */
    private val keepAliveMs: Long = KEEP_ALIVE_MS,
    private val pongTimeoutMs: Long = PONG_TIMEOUT_MS,
    /**
     * How long each retry of a `lovelace/config` that failed with an error that may pass waits ([fetchDashboard]);
     * once they are spent, the error goes to the listener.
     */
    private val configRetryMs: LongArray = CONFIG_RETRY_MS,
    /** A GET's answer (null when unreadable), off the socket's threads: [BubbleModules]' legacy file. */
    private val fileProbe: (url: String) -> BubbleModules.FileProbe? = BubbleModules::probe,
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
         * Home Assistant answered `lovelace/config` for [urlPath] with an error ([code]): [CONFIG_NOT_FOUND] for a
         * dashboard with no saved config, which HA's frontend auto-generates, at once; any other (HA still starting, a
         * YAML dashboard that doesn't load) once the client's retries are spent. Only for the dashboard still requested.
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
        /** `get_config` answered on this connection: [config] is now [haConfig]. */
        fun onConfig(config: JSONObject) {}
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

    /**
     * The entity subscriptions allowed to write to the store: every part of one [EntitySubs] shares it, and a
     * replacement ([replaceEntitySubs]) makes a new one.
     */
    @Volatile private var entityOwner: Any? = null

    /** Guards every change to the entity subscriptions (startup, view changes and config changes race otherwise). */
    private val entitySubLock = Any()

    /**
     * The `subscribe_entities` live on connection [gen]: their [ids] on the wire, and the entity ids they [cover]
     * together (empty: every entity). Replaced as a whole under [entitySubLock]; a lost connection's no longer counts.
     */
    private class EntitySubs(val gen: Int, val ids: List<Int>, val cover: Set<String>)

    /**
     * Every post-auth write goes through this one daemon thread: cards call [callService], [fetchHistory] and the
     * rest from the UI thread, where a socket write throws `NetworkOnMainThreadException`. One thread also keeps
     * strict FIFO order between the reader thread's startup requests and the UI thread's.
     */
    private val sendExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "vitro-ha-send").apply { isDaemon = true } }

    @Volatile private var socket: HaSocket? = null

    /** True between `auth_ok` and the end of the connection; nothing but `auth` may be written before it. */
    @Volatile private var authenticated = false
    @Volatile private var entitySubs: EntitySubs? = null

    /** The entities the page follows (empty: every entity), asked for again on every connection. */
    @Volatile private var activeEntityFilter: Set<String> = emptySet()

    /** Of [activeEntityFilter], the ones the view on screen needs: a new subscription asks for them first. */
    @Volatile private var priorityEntities: Set<String> = emptySet()

    /**
     * Ids the page follows that aren't entity ids HA accepts ([isValidEntityId]): never sent, as one fails HA's whole
     * `subscribe_entities`, and marked missing at once (HA's frontend shows them as not found).
     */
    @Volatile private var invalidEntities: Set<String> = emptySet()

    /**
     * True once the page has said which entities it follows. Until then nothing is subscribed: an empty
     * [activeEntityFilter] means "every entity" (auto-entities), and on a large Home Assistant that first snapshot can
     * outgrow the socket's message limit before the dashboard's scoped one is asked for. Kept across reconnects.
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
        bubbleProbeExecutor.shutdownNow()
        synchronized(registryLock) { timer?.shutdownNow() }
        held.clear()
    }

    /** Switches the entity subscription to [entityIds] alone, for one view ([followEntities], not additive). */
    fun subscribeViewEntities(entityIds: Set<String>) = followEntities(entityIds)

    /**
     * Follows [entityIds] (empty: every entity) from now on, on this connection and the next ones.
     *
     * [first] are the ones the view on screen needs: a new subscription asks for them on their own first, so their
     * snapshot (and the view's first build, which waits for it) doesn't wait for the rest. The rest follow in a
     * second `subscribe_entities` right after; each entity is asked for once.
     *
     * [additive]: what this connection already follows stays subscribed, and only ids it doesn't follow yet are asked
     * for, in one more `subscribe_entities` (a dashboard config change, an entity an island reads at runtime). Ids no
     * longer wanted stay subscribed until the next connection, unless they outnumber the wanted ones it follows (another
     * dashboard): then the subscription is replaced. Not additive, anything but exactly [entityIds] is replaced (one
     * view's scope). A wish to follow every entity, or to stop, always replaces.
     */
    fun followEntities(entityIds: Set<String>, first: Set<String> = emptySet(), additive: Boolean = false) {
        // HA validates every id of a subscribe_entities (`cv.entity_ids`): one malformed id anywhere on the dashboard
        // would fail the whole command, the view on screen's entities with it. Only valid ones go on the wire; a list
        // of nothing but invalid ids follows nothing, never every entity.
        val (valid, invalid) = entityIds.partition(::isValidEntityId)
        synchronized(entitySubLock) {
            activeEntityFilter = valid.toCollection(LinkedHashSet())
            priorityEntities = first.filterTo(LinkedHashSet(), ::isValidEntityId)
            invalidEntities = invalid.toSet()
            scopeSelected = true
            if (socket == null) return
            val live = entitySubs?.takeIf { it.gen == connectionGen.get() }
            if (live == null) return replaceEntitySubs()
            val cover = live.cover
            markInvalidMissing(entityOwner)
            if (followsNothing()) return replaceEntitySubs()
            val entityIds = activeEntityFilter
            when {
                // Every entity is followed already.
                cover.isEmpty() && entityIds.isEmpty() -> Unit
                // From every entity to some, or the other way: one new subscription.
                cover.isEmpty() || entityIds.isEmpty() -> replaceEntitySubs()
                !additive -> if (cover != entityIds) replaceEntitySubs()
                else -> {
                    val added = entityIds.filterTo(LinkedHashSet()) { it !in cover }
                    val stale = cover.count { it !in entityIds }
                    when {
                        stale > cover.size - stale -> replaceEntitySubs()
                        added.isNotEmpty() -> {
                            val id = subscribeEntityPart(added, entityOwner, live.gen)
                            entitySubs = if (id > 0) EntitySubs(live.gen, live.ids + id, cover + added) else null
                        }
                    }
                }
            }
        }
    }

    /**
     * Follows [entityIds] as [followEntities] does (additive, [first] first), only while nothing has picked what this
     * client follows yet: the first dashboard config of a cold start, on the socket's thread, as soon as it lands. The
     * view's states then arrive while the main thread is still busy with the start, and its first build doesn't wait
     * for them (VitroView.holdFirstBuildForStatesMs); the page's own call for that view, later, finds them followed and
     * sends nothing. Returns whether it followed them.
     */
    fun followEntitiesIfUnscoped(entityIds: Set<String>, first: Set<String>): Boolean = synchronized(entitySubLock) {
        if (scopeSelected) return false
        followEntities(entityIds, first, additive = true)
        true
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

    /** Bumped by every [fetchDashboard]: the retries of an older one stop. Under [pathLock]. */
    private var dashboardFetch = 0

    /** Loads a different Lovelace dashboard config over the live socket. */
    fun fetchDashboard(urlPath: String?) = synchronized(pathLock) {
        requestedDashboardPath = urlPath
        dashboardActive = true
        requestDashboard(urlPath, ++dashboardFetch, attempt = 0)
    }

    /** Asks for [urlPath]'s config, for fetch [fetch] (its [attempt]th retry). Under [pathLock]. */
    private fun requestDashboard(urlPath: String?, fetch: Int, attempt: Int) {
        val msg = JSONObject().put("type", "lovelace/config")
        if (!urlPath.isNullOrEmpty()) msg.put("url_path", urlPath)
        request(msg) { res ->
            // The connection, not Home Assistant, failed: the reconnect asks again (onAuthenticated). Reporting it
            // would send a dashboard that exists to the fallback as "not found".
            if (isTransportError(res)) return@request
            // An error that may pass (HA still starting answers `unknown_command` until Lovelace is loaded; a passing
            // `home_assistant_error`) is asked again, a few times with more time between: HA's frontend reads the
            // config again too, when its connection is back. Only `config_not_found` means HA has none.
            val code = res.optJSONObject("error")?.optString("code", "").orEmpty()
            if (!res.optBoolean("success", false) && code != CONFIG_NOT_FOUND && attempt < configRetryMs.size) {
                val due = schedule(configRetryMs[attempt]) {
                    synchronized(pathLock) {
                        // Only while it is still the fetch the page waits for: a newer one (a reconnect, another
                        // dashboard, lovelace_updated) or a page gone to the fallback ends the retries.
                        if (closed.get() || !dashboardActive || dashboardFetch != fetch || urlPath != requestedDashboardPath) return@synchronized
                        requestDashboard(urlPath, fetch, attempt + 1)
                    }
                }
                if (due) return@request
            }
            // Nothing is handed on before HA has said whether this user may see the dashboard.
            afterAccessKnown {
                val ok = res.optBoolean("success", false)
                // Whether the answer is still the page's, whether the user may open it, and the claim it makes are one
                // decision under [pathLock]: a [claimDashboard] or [fetchDashboard] for another path on the main thread
                // can't land between them and have its claim overwritten by this stale answer. Listeners run after.
                val allowed = synchronized(pathLock) {
                    // An answer for a dashboard the page has since moved away from must not replace the current one.
                    if (closed.get() || !dashboardActive || urlPath != requestedDashboardPath) return@afterAccessKnown
                    // A retry's answer is its fetch's alone: a newer fetch of the same dashboard, started while this
                    // one waited (here, for the user's access), has its own answer coming.
                    if (attempt > 0 && dashboardFetch != fetch) return@afterAccessKnown
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
     * The forecast of weather [entityId] (`weather/subscribe_forecast`), held open as HA's weather card holds it: every
     * list the integration pushes reaches [onForecast], on the socket's thread, until the returned handle is called. It
     * is sent again on every new connection ([hold]).
     */
    fun subscribeForecast(entityId: String, forecastType: String = "daily", onForecast: (List<ForecastDay>) -> Unit): () -> Unit {
        val msg = JSONObject()
            .put("type", "weather/subscribe_forecast")
            .put("entity_id", entityId)
            .put("forecast_type", forecastType)
        return hold({ JSONObject(msg.toString()) }, { ev, _ ->
            ev.optJSONArray("forecast")?.let { onForecast(ForecastDay.parseList(it)) }
        }, {})
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
     * Subscribes [msg] (any subscription command, e.g. `todo/item/subscribe`) and hands each of its events to
     * [onEvent], on the socket's thread, until the returned handle is called. With [resubscribe] it is held as HA's
     * frontend holds its subscriptions ([hold]): asked for while the connection is down, it goes out once it is back,
     * and every new connection sends it again. [onError] then runs once, only if Home Assistant rejects it.
     *
     * Without [resubscribe] (a one-off exchange, such as a camera's WebRTC offer) it lives on this connection only:
     * [onError] runs once if Home Assistant rejects it, the connection drops before it starts (its `error`), or with
     * null when it can't be sent. Cancelling with the returned handle never calls it. [onStarted] runs once Home
     * Assistant accepted it (its `result`), unless it was cancelled first: what a web island's `subscribeMessage`
     * resolves on (an island holds its own subscriptions across reconnects, so it asks without [resubscribe]).
     */
    fun subscribeWs(
        msg: JSONObject,
        onEvent: (JSONObject) -> Unit,
        onError: (JSONObject?) -> Unit,
        resubscribe: Boolean = true,
        onStarted: () -> Unit = {},
    ): () -> Unit {
        if (resubscribe) return hold({ JSONObject(msg.toString()) }, { ev, _ -> onEvent(ev) }, onError)
        val subId = subscribeWithId(JSONObject(msg.toString()), onError, onStarted) { _, ev -> onEvent(ev) }
        if (subId <= 0) onError(null)
        return { if (subId > 0) unsubscribe(subId) }
    }

    /**
     * [subscribeWs] held across connections whose [message] is built again for each one (a history window that follows
     * the clock). [onEvent] hears with `first` the first event of each send: a stream that starts again from a fresh
     * window (`history/stream`) drops what it had before taking it in.
     */
    fun subscribeWsFresh(
        message: () -> JSONObject,
        onEvent: (event: JSONObject, first: Boolean) -> Unit,
        onError: (JSONObject?) -> Unit,
    ): () -> Unit = hold(message, onEvent, onError)

    // ---- Held subscriptions (cards') ----

    /**
     * A subscription a card holds for as long as it shows, whatever happens to the connection: HA's frontend
     * (home-assistant-js-websocket, `resubscribe`) sends every live subscription again on a new connection, and so does
     * this client ([onAuthenticated]). [wireId] and [gen] are its id on the connection ([connectionGen]) it was last
     * sent on; -1 while it waits for one. Guarded by the object's own lock.
     */
    private class Held(
        val message: () -> JSONObject,
        val onEvent: (JSONObject, Boolean) -> Unit,
        val onError: (JSONObject?) -> Unit,
    ) {
        var wireId = -1
        var gen = -1
        var ended = false
    }

    /** Every held subscription not ended yet, sent or waiting for a connection. */
    private val held: MutableSet<Held> = Collections.newSetFromMap(ConcurrentHashMap<Held, Boolean>())

    private fun hold(
        message: () -> JSONObject,
        onEvent: (JSONObject, Boolean) -> Unit,
        onError: (JSONObject?) -> Unit,
    ): () -> Unit {
        val h = Held(message, onEvent, onError)
        if (closed.get()) return {}
        held += h
        sendHeld(h)
        return { endHeld(h) }
    }

    /** Sends [h] on the connection there is now, unless it is already on it; without one it waits for the next. */
    private fun sendHeld(h: Held) {
        synchronized(h) {
            if (h.ended || closed.get() || !authenticated) return
            // The connection and the send as one, under the lock a dropped connection takes to end: [h] is on the
            // connection it names, or on none.
            synchronized(queueLock) {
                val gen = connectionGen.get()
                if (h.wireId > 0 && h.gen == gen) return
                val msg = runCatching { h.message() }.getOrElse { return }
                val first = AtomicBoolean(true)
                val id = subscribeWithId(msg, { err -> heldFailed(h, err) }) { _, ev -> h.onEvent(ev, first.getAndSet(false)) }
                if (id > 0) {
                    h.wireId = id
                    h.gen = gen
                }
            }
        }
    }

    /** Home Assistant turned [h] down: it ends, and its owner hears it once. A lost connection only sends it again. */
    private fun heldFailed(h: Held, error: JSONObject?) {
        if (error?.optString("code") in setOf(DISCONNECTED, SEND_FAILED)) return
        synchronized(h) {
            if (h.ended) return
            h.ended = true
        }
        held -= h
        h.onError(error)
    }

    private fun endHeld(h: Held) {
        val wire = synchronized(h) {
            h.ended = true
            h.wireId.takeIf { it > 0 && h.gen == connectionGen.get() }
        }
        held -= h
        wire?.let(::unsubscribe)
    }

    /** A new connection: every held subscription goes out on it. */
    private fun resendHeld() {
        for (h in held.toList()) sendHeld(h)
    }

    /** How many held subscriptions are live (tests). */
    internal fun heldCount(): Int = held.size

    /** How many requests wait for their result (tests). */
    internal fun pendingCount(): Int = pending.size

    // ---- Registries for web islands ----

    /** Guards the registry feed's state below. Taken before [queueLock] (start and stop send), never inside it. */
    private val registryLock = Any()
    private val registryWatchers = ArrayList<(JSONObject) -> Unit>()

    /** The connection ([connectionGen]) the feed runs on, -1 when it doesn't run. */
    private var registryGen = -1

    /**
     * Bumped by every [startRegistries]: a read the feed sent before it stopped and started again on the same
     * connection (where [registryGen] is the same again) puts nothing into the new feed's [registryParts].
     */
    private var registryEpoch = 0
    private var registrySubs: List<Int> = emptyList()

    /** Each registry's last `result` on this connection; the set goes out once all four answered. */
    private val registryParts = HashMap<HassRegistries.Kind, Any?>()
    private var registriesNow: JSONObject? = null

    /**
     * Registries whose refetch is waiting out [REGISTRY_DEBOUNCE_MS], with the connection it is for: one still waiting
     * for a connection that dropped doesn't swallow the next connection's.
     */
    private val registryDue = Collections.newSetFromMap(ConcurrentHashMap<Pair<HassRegistries.Kind, Int>, Boolean>())

    /** The client's timer ([schedule]): registry refetches and [keepAlive]. Made on first use, stopped by close(). */
    @Volatile private var timer: java.util.concurrent.ScheduledExecutorService? = null

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
        val epoch = ++registryEpoch
        registryParts.clear()
        // The registries native cards draw with have their events on every connection ([watchDisplayRegistries]), and
        // a refetch of one feeds the islands too. Only the floors' are the islands' alone.
        registrySubs = HassRegistries.Kind.values().filter { it !in DISPLAY_REGISTRIES }.map { kind ->
            subscribe(JSONObject().put("type", "subscribe_events").put("event_type", kind.event)) { refetchRegistry(kind, gen) }
        }.filter { it > 0 }
        for (kind in HassRegistries.Kind.values()) fetchRegistry(kind, gen, epoch, display = false)
    }

    /**
     * The entity, device and area registries' update events, on every connection: native cards draw names, display
     * precision, translated states and areas from them ([HaDisplay]), and HA's frontend follows them all the time
     * (`subscribeEntityRegistryDisplay`, the device and area collections). Each change reads that registry again.
     */
    private fun watchDisplayRegistries() {
        val gen = connectionGen.get()
        for (kind in DISPLAY_REGISTRIES) {
            subscribe(JSONObject().put("type", "subscribe_events").put("event_type", kind.event)) { refetchRegistry(kind, gen) }
        }
    }

    /** The connection whose floors native names follow ([followFloorsIfNeeded]); -1 for none. */
    @Volatile private var floorsFollowedGen = -1

    /**
     * Floor names, for a card `name` that names its entity's floor (HA's name picker): read and followed on connection
     * [gen] once an area is on a floor (at startup, or when an area update puts one there), never otherwise. A floor's
     * change reads them again ([refetchRegistry]).
     */
    private fun followFloorsIfNeeded(gen: Int) {
        if (HaDisplay.areas.values.none { it.floorId != null }) return
        synchronized(registryLock) {
            if (gen != connectionGen.get() || floorsFollowedGen == gen) return
            floorsFollowedGen = gen
            subscribe(JSONObject().put("type", "subscribe_events").put("event_type", HassRegistries.Kind.FLOORS.event)) {
                refetchRegistry(HassRegistries.Kind.FLOORS, gen)
            }
            fetchRegistry(HassRegistries.Kind.FLOORS, gen, -1, display = true)
        }
    }

    /** Stops the feed and forgets the registries: nobody watches. Under [registryLock]. */
    private fun stopRegistries() {
        if (registryGen == connectionGen.get()) registrySubs.forEach(::unsubscribe)
        registrySubs = emptyList()
        registryGen = -1
        registryParts.clear()
        registriesNow = null
    }

    /**
     * Reads [kind] on connection [gen]: into [HaDisplay] with [display] (the cards draw again once it is in), and into
     * the islands' feed of [epoch] (none when -1).
     */
    private fun fetchRegistry(kind: HassRegistries.Kind, gen: Int, epoch: Int, display: Boolean) {
        if (display) displayPending.incrementAndGet()
        val sent = request(JSONObject().put("type", kind.command)) { res ->
            // A read cut off by a dropped connection: the next one reads every registry afresh.
            if (gen != connectionGen.get()) return@request
            if (display) {
                if (res.optBoolean("success", false)) applyDisplay {
                    when (kind) {
                        HassRegistries.Kind.ENTITIES -> res.optJSONObject("result")?.let(HaDisplay::applyRegistry)
                        HassRegistries.Kind.DEVICES -> res.optJSONArray("result")?.let(HaDisplay::applyDevices)
                        HassRegistries.Kind.AREAS -> res.optJSONArray("result")?.let(HaDisplay::applyAreas)
                        HassRegistries.Kind.FLOORS -> res.optJSONArray("result")?.let(HaDisplay::applyFloors)
                    }
                }
                // An area put on a floor: its name needs the floors, read now if they aren't followed yet.
                if (kind == HassRegistries.Kind.AREAS) followFloorsIfNeeded(gen)
                // A new entity may be of an integration whose icons and state words aren't in yet.
                if (kind == HassRegistries.Kind.ENTITIES) loadDisplayFor(displayIds())
                settleDisplay()
            }
            if (epoch < 0) return@request
            val (set, watchers) = synchronized(registryLock) {
                if (gen != connectionGen.get() || registryGen != gen || registryEpoch != epoch) return@request
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
        // Not sent (the connection is going): nothing will settle it.
        if (sent <= 0 && display) displayPending.decrementAndGet()
    }

    /** A registry changed: read it again once its burst of events is over (an integration reload sends dozens). */
    private fun refetchRegistry(kind: HassRegistries.Kind, gen: Int) {
        val due = kind to gen
        if (!registryDue.add(due)) return
        val scheduled = schedule(REGISTRY_DEBOUNCE_MS) {
            registryDue.remove(due)
            // For the connection still up: native cards' registries always, the islands' feed only while it still
            // runs (the last watcher may have gone meanwhile). Checked and sent under one lock, which stopRegistries()
            // takes too: a stop can't slip in between.
            synchronized(registryLock) {
                if (gen != connectionGen.get()) return@synchronized
                val epoch = if (registryGen == gen) registryEpoch else -1
                val display = kind in DISPLAY_REGISTRIES || (kind == HassRegistries.Kind.FLOORS && floorsFollowedGen == gen)
                if (epoch >= 0 || display) fetchRegistry(kind, gen, epoch, display)
            }
        }
        if (!scheduled) registryDue.remove(due)
    }

    /** Subscribes to HA's server-side Jinja2 `render_template` (for markdown & mushroom-template-card). */
    fun subscribeTemplate(template: String, onRendered: (String) -> Unit): () -> Unit {
        val msg = JSONObject()
            .put("type", "render_template")
            .put("template", template)
        // Held across connections: a template a card shows keeps following HA after a reconnect.
        return hold({ JSONObject(msg.toString()) }, { ev, _ ->
            val rendered = ev.opt("result")?.takeIf { it != JSONObject.NULL }?.toString().orEmpty()
            onRendered(rendered)
        }, {})
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
                        lastHeard = System.nanoTime()
                        listener.onConnected(auth.optString("ha_version", ""))
                        onAuthenticated()
                        keepAlive(ws, connectionGen.get())
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
                    val raw = ws.receive() ?: throw IOException(if (silent === ws) NO_ANSWER else "Closed by Home Assistant")
                    lastHeard = System.nanoTime()
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
        // Under registryLock too (registry, then queue, as a registry refetch takes them): a refetch that checked the
        // generation has counted and sent its request before this ends the generation, and one after sees the new one.
        synchronized(registryLock) {
            synchronized(queueLock) {
                // The display requests of this connection settle no more: their callbacks (below, or a late
                // send_failed) see an older generation than the next connection's.
                connectionGen.incrementAndGet()
                authenticated = false
                socket = null
                entitySubs = null
                orphaned = pending.keys.toList()
                subscriptions.clear()
            }
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
        // display data afresh. Under registryLock, so no refetch counts a request in between.
        synchronized(registryLock) {
            displayRequested.clear()
            displayPending.set(0)
            displayChanged.set(false)
        }
    }

    /** When the reader last got a message from Home Assistant ([System.nanoTime]). */
    @Volatile private var lastHeard = 0L

    /** The socket [keepAlive] gave up on: its reader reports the drop as [NO_ANSWER]. */
    @Volatile private var silent: HaSocket? = null

    /**
     * Every [keepAliveMs] while connection [gen] lasts on [ws]: the token is asked for again, so its consumers (images,
     * the island proxy, an island's `hass.auth`) get a fresh one before HA's 30-minute token expires (the provider
     * hands out its cached one until then); and a `ping` goes out. When neither its `pong` nor anything else comes back
     * within [pongTimeoutMs], the connection is dead without having said so (HA's host lost power, a router dropped
     * it): the socket is closed and the reader reconnects. HA's frontend pings in the same way.
     */
    private fun keepAlive(ws: HaSocket, gen: Int) {
        fun live() = !closed.get() && gen == connectionGen.get() && socket === ws
        schedule(keepAliveMs) {
            if (!live()) return@schedule
            // The next check is due whatever this one finds; a dead connection ends the chain (live() fails).
            keepAlive(ws, gen)
            refreshToken(force = false) {}
            val sentAt = System.nanoTime()
            val answered = AtomicBoolean(false)
            val pingId = request(JSONObject().put("type", "ping")) { res -> if (!isTransportError(res)) answered.set(true) }
            if (pingId <= 0) return@schedule
            schedule(pongTimeoutMs) {
                // The check is the ping's end: a pong that never came (the connection lived on other traffic) leaves no
                // callback behind in [pending], one per check for as long as the connection lasts.
                pending.remove(pingId)
                // A pong held up behind a big message still counts: anything heard since the ping means it lives.
                if (!live() || answered.get() || lastHeard - sentAt > 0) return@schedule
                silent = ws
                runCatching { ws.close() }
            }
        }
    }

    /**
     * Asks the token provider for the token now ([force]: a refreshed one), for the consumers its provider tells
     * (the engine's images, island proxy and islands). [done] gets it, or null. Any thread; doesn't block.
     */
    fun refreshToken(force: Boolean, done: (String?) -> Unit) {
        if (closed.get()) { done(null); return }
        tokenProvider(force, done)
    }

    /** Runs [block] after [delayMs] on the client's timer; false (and nothing runs) once the client is closed. */
    private fun schedule(delayMs: Long, block: () -> Unit): Boolean {
        val t = synchronized(registryLock) {
            // Checked under the lock close() takes to stop the timer: a closed client starts no new one.
            if (closed.get()) return false
            timer ?: Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "vitro-ha-timer").apply { isDaemon = true }
            }.also { timer = it }
        }
        return try {
            t.schedule({ runCatching(block) }, delayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            true
        } catch (_: RejectedExecutionException) {
            false
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

    /**
     * Sends a batch of display requests ([send]) holding one count of its own: an answer that lands (on the socket's
     * thread) before the batch's last request is counted can't bring [displayPending] to zero, so the cards re-render
     * once when the whole batch is in, not once early and again after. A connection that ended meanwhile reset the
     * count: its batch leaves it alone.
     */
    private inline fun displayBatch(send: () -> Unit) {
        val gen = connectionGen.get()
        displayPending.incrementAndGet()
        try {
            send()
        } finally {
            if (gen == connectionGen.get()) settleDisplay()
        }
    }

    private fun loadDisplayFor(entityIds: Collection<String>) = displayBatch {
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
        // Icons and state labels are this Home Assistant's: a new connection loads them again. Those of the same Home
        // Assistant stay until this connection's answers replace them, so the snapshot below doesn't draw every card
        // with plain names and English states first (another Home Assistant's go at once).
        HaDisplay.connectionTo(origin)
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
                res.optJSONObject("result")?.let {
                    haConfig = it
                    applyLocale { HaLocale.applyConfig(it) }
                    listener.onConfig(it)
                }
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
                deliverResources(out)
            } else if (!isTransportError(res)) {
                // Home Assistant refused them: its frontend loads none, and the page that waits for them builds now
                // (VitroView.holdFirstBuildForResourcesMs). A dropped connection asks again when it's back.
                deliverResources(emptyList())
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
        // Sent as one batch: the first answer can't re-render before the other requests are counted.
        val gen = connectionGen.get()
        displayBatch {
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
                followFloorsIfNeeded(gen)
                settleDisplay()
            }
        }
        // ... and each of the three read again when it changes.
        watchDisplayRegistries()
        // 8. The registries web islands read, when one does (they ask on first read): again on every connection.
        synchronized(registryLock) { startRegistries() }
        // 9. The cards' own subscriptions (templates, forecasts, to-do lists, history streams): the ones the last
        // connection carried, and the ones asked for while it was down.
        resendHeld()
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

    /** True when the page follows only ids HA can't accept: nothing to subscribe. */
    private fun followsNothing() = activeEntityFilter.isEmpty() && invalidEntities.isNotEmpty()

    /** The invalid ids the page follows: HA will never have them, so they're missing now (under [entitySubLock]). */
    private fun markInvalidMissing(owner: Any?) {
        val invalid = invalidEntities
        if (invalid.isNotEmpty()) store.markSnapshot(invalid, owner)
    }

    /** A new connection: everything the page follows, asked for again ([replaceEntitySubs]). */
    private fun resubscribeEntities() = synchronized(entitySubLock) { replaceEntitySubs() }

    /**
     * Replaces the entity subscriptions with new ones for [activeEntityFilter]: the view's own entities
     * ([priorityEntities]) first when they are fewer than the whole, then the rest. Under [entitySubLock].
     */
    private fun replaceEntitySubs() {
        val gen = connectionGen.get()
        entitySubs?.takeIf { it.gen == gen }?.ids?.forEach { unsubscribe(it) }
        entitySubs = null
        val filter = activeEntityFilter
        // These subscriptions own the store's entity writes from now on; a retired one's events (one the reader
        // already picked up when it was replaced) are dropped, even before subscribe() returns. Switched without the
        // write lock: this runs on the UI thread when the view changes, and the reader can hold that lock for the
        // whole first snapshot (hundreds of ms on a panel). An old event mid-commit finishes its write, and its
        // snapshot mark, keyed to its owner, no longer lands.
        val owner = Any()
        entityOwner = owner
        store.resetSnapshot(owner)
        markInvalidMissing(owner)
        // Only invalid ids: nothing to ask HA for (an empty filter would ask for every entity).
        if (followsNothing()) return
        val head = if (filter.isEmpty()) emptySet() else priorityEntities.filterTo(LinkedHashSet()) { it in filter }
        val parts = if (head.isEmpty() || head.size == filter.size) listOf(filter) else listOf(head, filter.filterTo(LinkedHashSet()) { it !in head })
        val ids = ArrayList<Int>(parts.size)
        for (part in parts) {
            val id = subscribeEntityPart(part, owner, gen)
            // Not sent: the connection is going, and the next one asks for everything again.
            if (id <= 0) return
            ids += id
        }
        entitySubs = EntitySubs(gen, ids, filter)
    }

    /**
     * One `subscribe_entities` for [filter] (empty: every entity), writing to the store while [owner] owns it. Its
     * first event is its snapshot: an entity of [filter] missing from it is gone (deleted while the panel was offline)
     * and leaves the store. Later events are diffs. The wire id, or -1 when it wasn't sent.
     */
    private fun subscribeEntityPart(filter: Set<String>, owner: Any?, gen: Int): Int {
        val msg = JSONObject().put("type", "subscribe_entities")
        if (filter.isNotEmpty()) msg.put("entity_ids", JSONArray(filter.toList()))
        val first = AtomicBoolean(true)
        // Refused by HA: its ids are no longer counted as followed, so the next followEntities asks for them again.
        val onError: (JSONObject?) -> Unit = { _ ->
            synchronized(entitySubLock) {
                val live = entitySubs
                if (live != null && live.gen == gen && live.cover.isNotEmpty() && filter.isNotEmpty()) {
                    val left = live.cover - filter
                    // Nothing left followed: the next followEntities subscribes anew (an empty cover reads as every entity).
                    entitySubs = if (left.isEmpty()) null else EntitySubs(live.gen, live.ids, left)
                }
            }
        }
        return subscribeWithId(msg, onError = onError) { _, ev -> synchronized(storeWriteLock) {
            // Checked under the lock close() takes: a closed client never writes to the (shared) store again.
            if (closed.get() || entityOwner !== owner) return@subscribeWithId
            val isFirst = first.getAndSet(false)
            store.applyCompressedEvent(ev, snapshotScope = if (isFirst) filter else null)
            if (isFirst) {
                store.markSnapshot(filter, owner)
                loadDisplayFor(filter.ifEmpty { store.all().keys })
            }
        } }
    }

    private fun handleIncoming(msg: JSONObject) {
        val id = msg.optInt("id", -1)
        when (msg.optString("type")) {
            "result" -> pending.remove(id)?.let { cb -> contained("result") { cb(msg) } }
            // The answer to a `ping` (an island's callWS can send one): settle it as a result, or its promise hangs.
            "pong" -> pending.remove(id)?.let { cb ->
                contained("result") { cb(JSONObject().put("id", id).put("type", "result").put("success", true).put("result", JSONObject.NULL)) }
            }
            "event" -> msg.optJSONObject("event")?.let { ev -> subscriptions[id]?.let { cb -> contained("event") { cb(ev) } } }
        }
    }

    /**
     * Runs one result or event callback on the reader thread. A throw in it (a parser edge case, a card's callback) is
     * that callback's alone: it is reported ([VitroFailures]) and the connection carries on. Left to the reader's loop,
     * it would drop the connection, and a config that throws every time would reconnect forever.
     */
    private inline fun contained(where: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            VitroFailures.report("socket $where", null, e)
        } catch (e: StackOverflowError) {
            VitroFailures.report("socket $where", null, e)
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

    /**
     * Hands [resources] to the listener, with whether Bubble Card modules may restyle Bubble cards here
     * ([BubbleModules]), which only matters, and is only asked, when a resource loads Bubble Card. The three sources are
     * asked in turn, and the first that may hold modules answers.
     */
    private fun deliverResources(resources: List<LovelaceResource>) {
        if (!BubbleModules.loaded(resources)) {
            listener.onResources(LovelaceResourceList(resources, bubbleModules = false))
            return
        }
        val answered = AtomicBoolean(false)
        // An answer that lands after its connection was replaced (a reconnect asked again) is stale: the newer
        // connection's own delivery stands.
        val gen = connectionGen.get()
        fun answer(modules: Boolean) {
            if (!closed.get() && gen == connectionGen.get() && answered.compareAndSet(false, true)) {
                listener.onResources(LovelaceResourceList(resources, modules))
            }
        }
        fun legacyFile() {
            val url = (origin ?: haBase.trimEnd('/')) + BubbleModules.LEGACY_FILE
            try {
                bubbleProbeExecutor.execute { answer(BubbleModules.fileAnswer(fileProbe(url))) }
            } catch (_: RejectedExecutionException) {
                answer(true)
            }
        }
        fun sensor() {
            var subId = -1
            val gotFirst = AtomicBoolean(false)
            subId = subscribeWithId(
                JSONObject().put("type", "subscribe_entities").put("entity_ids", JSONArray().put(BubbleModules.SENSOR)),
                onError = { answer(true) },
            ) { id, ev ->
                if (!gotFirst.compareAndSet(false, true)) return@subscribeWithId
                unsubscribe(id)
                if (BubbleModules.sensorAnswer(ev)) answer(true) else legacyFile()
            }
            if (subId < 0) answer(true)
        }
        val sent = request(JSONObject().put("type", BubbleModules.TOOLS_COMMAND)) { res ->
            if (BubbleModules.toolsAnswer(res)) answer(true) else sensor()
        }
        if (sent < 0) answer(true)
    }

    private val bubbleProbeExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "vitro-bubble-probe").apply { isDaemon = true } }

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

        /** `lovelace/config`'s answer for a dashboard with no saved config: HA's frontend generates one. */
        const val CONFIG_NOT_FOUND = "config_not_found"

        /**
         * The waits between the retries of a `lovelace/config` that failed with another error (about 30 s in all),
         * before it goes to the listener.
         */
        internal val CONFIG_RETRY_MS = longArrayOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L)

        /** The default dashboard's panel (`lovelace`), the one a request with no url path opens. */
        const val DEFAULT_DASHBOARD_PANEL = "lovelace"
        const val SEND_FAILED = "send_failed"

        /**
         * Home Assistant's `valid_entity_id` (core `VALID_ENTITY_ID`, homeassistant/core.py): lowercase letters, digits
         * and single underscores, a domain and an object id, neither starting or ending with `_`. `subscribe_entities`
         * refuses the whole command for one id that isn't.
         */
        private val VALID_ENTITY_ID = Regex("^(?!.+__)(?!_)[\\da-z_]+(?<!_)\\.(?!_)[\\da-z_]+(?<!_)$")

        fun isValidEntityId(id: String): Boolean = VALID_ENTITY_ID.matches(id)

        /** A result the client made up for a failed connection, not an answer from Home Assistant. */
        fun isTransportError(res: JSONObject): Boolean =
            !res.optBoolean("success", false) && res.optJSONObject("error")?.optString("code") in setOf(DISCONNECTED, SEND_FAILED)

        /** How long a registry's refetch waits for its burst of update events to end, as HA's frontend debounces its own. */
        internal const val REGISTRY_DEBOUNCE_MS = 500L

        /** The registries native cards draw with ([HaDisplay]), followed on every connection. */
        private val DISPLAY_REGISTRIES = setOf(HassRegistries.Kind.ENTITIES, HassRegistries.Kind.DEVICES, HassRegistries.Kind.AREAS)

        /** Default bound on the greeting and auth exchange; after `auth_ok`, [keepAlive] checks the connection. */
        const val AUTH_TIMEOUT_MS = 20_000L

        /** How often a live connection is pinged and the token asked for again (well inside the provider's last minute). */
        const val KEEP_ALIVE_MS = 30_000L

        /** How long a ping may go unanswered, with nothing else heard either, before the connection counts as dead. */
        const val PONG_TIMEOUT_MS = 10_000L

        /** Why a connection [keepAlive] gave up on was dropped. */
        const val NO_ANSWER = "No answer from Home Assistant"

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
