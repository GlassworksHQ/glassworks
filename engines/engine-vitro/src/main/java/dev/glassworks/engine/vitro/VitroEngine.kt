/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import dev.glassworks.auth.HaSocket
import dev.glassworks.auth.OAuth
import dev.glassworks.auth.WsHaSocket
import dev.glassworks.engine.Engine
import dev.glassworks.engine.EngineSettings
import dev.glassworks.engine.Page
import dev.glassworks.engine.PageHost
import dev.glassworks.engine.PagePort
import dev.glassworks.engine.vitro.compat.CompatWarmup
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.data.HaDashboardClient
import dev.glassworks.engine.vitro.island.VitroIslandServer
import dev.glassworks.engine.vitro.model.CardRouter
import dev.glassworks.engine.vitro.model.DashboardConfig
import dev.glassworks.engine.vitro.model.DashboardSummary
import dev.glassworks.engine.vitro.model.LovelaceParser
import dev.glassworks.engine.vitro.model.LovelaceResource
import dev.glassworks.engine.vitro.net.MjpegStreamPlayer
import dev.glassworks.engine.vitro.theme.ResolvedTheme
import dev.glassworks.engine.vitro.theme.ThemeCatalog
import dev.glassworks.engine.vitro.ui.MdiIcons
import dev.glassworks.engine.vitro.ui.VitroCardContext
import dev.glassworks.engine.vitro.ui.VitroMetrics
import dev.glassworks.engine.vitro.ui.VitroView
import org.json.JSONArray
import org.json.JSONObject

/**
 * Vitro: experimental native Android Lovelace rendering [Engine] for Glassworks.
 *
 * Wraps a [fallback] browser [Engine] (e.g., GeckoEngine). When [enabled] is false, every call
 * delegates transparently to [fallback] with zero overhead. When [enabled] is true, Vitro renders
 * Home Assistant Lovelace dashboards natively over HA's WebSocket API without booting [fallback]
 * at all—falling back lazily only for non-dashboard pages (such as `/auth/authorize` login or
 * Blueprint import dialogs).
 */
class VitroEngine(
    val fallback: Engine,
    private val settings: (Context) -> EngineSettings,
    private val enabled: (Context) -> Boolean = { true },
    private val tokenProvider: (force: Boolean, done: (String?) -> Unit) -> Unit = { _, done -> done(null) },
    private val socketFactory: (String) -> HaSocket = HaDashboardClient::connectLongLived,
    private val logTag: String = "Glassworks.Vitro",
) : Engine {

    @Volatile private var activeInProcess: Boolean? = null
    @Volatile private var activePage: VitroPage? = null

    /**
     * The loopback server for Tier 3 islands, one per process and started on first use ([ensureStarted] picks the
     * port once). It reads the active page's Home Assistant origin, token and Lovelace resources on every request.
     * The legacy engine (a locked-down [fallback]) is held to the dashboard's origin, so [islandOrigin] gives it this
     * one up front as well.
     */
    val islandServer: VitroIslandServer = VitroIslandServer(
        session = { activePage?.islandSession() ?: (null to null) },
        log = { msg -> Log.d(logTag, "island: ${forLog(msg)}") },
        resources = { activePage?.resourceUrls().orEmpty() },
    )

    /**
     * The island server's loopback origin, started now: the second origin a locked-down [fallback] lets pages load
     * (`EngineSettings.localOrigin`), for the islands alone. Home Assistant's own pages load from Home Assistant.
     */
    fun islandOrigin(): String = islandServer.ensureStarted()
    @Volatile private var startedSettings: EngineSettings? = null
    @Volatile var lastMetrics: VitroMetrics = VitroMetrics()
        private set

    override val started: EngineSettings?
        get() = if (activeInProcess == true) startedSettings else fallback.started

    override val name: String
        get() = if (activeInProcess == true) VERSION_NAME else fallback.name

    override val lockedDown: Boolean
        get() = fallback.lockedDown

    override fun isContentProcess(processName: String): Boolean = fallback.isContentProcess(processName)

    override fun open(activity: Activity, host: PageHost, background: Int): Page {
        val useVitro = activeInProcess ?: enabled(activity.applicationContext).also { activeInProcess = it }
        if (!useVitro) return fallback.open(activity, host, background)

        val cfg = settings(activity.applicationContext)
        startedSettings = cfg
        MdiIcons.init(activity.applicationContext)
        Log.i(logTag, "Starting $VERSION_NAME (native Lovelace renderer; fallback=${fallback.name.ifEmpty { "lazy" }})")
        // A recreated activity opens a new page: the old one's socket and reader thread go with it.
        activePage?.dispose()
        return VitroPage(activity, host, background).also { activePage = it }
    }

    /**
     * Whether the frame's screen takes a tap ([dev.glassworks.frame.Frame.input]): HA's browser on that device styles
     * for a coarse pointer (the chart legend's 40 px rows). Without a Glassworks app around it (a test app), a touch
     * screen, as on most Android devices.
     */
    private fun Activity.touchScreen(): Boolean =
        (applicationContext as? dev.glassworks.GlassworksApp)?.glassworks?.frame?.input != dev.glassworks.frame.Input.NONE

    private inner class VitroPage(
        private val activity: Activity,
        private val host: PageHost,
        private val backgroundColor: Int,
    ) : Page {
        private val main = Handler(Looper.getMainLooper())
        private val container = FrameLayout(activity).apply { setBackgroundColor(backgroundColor) }
        private val touchScreen = activity.touchScreen()
        private val store = EntityStore()
        private val vitroView = VitroView(activity, store, backgroundColor)
        private var fallbackPage: Page? = null
        private var client: HaDashboardClient? = null
        private val islandHost = object : PageHost {
            override fun onPageStart(url: String) {}
            override fun onPageStop(success: Boolean) {}
            override fun onFirstPaint() {}
            override fun onLocationChange(url: String?) {}
            override fun redirect(url: String): String? = null
            // An island's content process crashed or was killed: its page draws nothing again, and the host doesn't
            // say which island it was. Every island opens a new page (a rebuild would otherwise hand the dead ones
            // over); once, however many islands report it.
            override fun onContentGone(killed: Boolean) {
                if (islandsGonePosted) return
                islandsGonePosted = true
                main.post {
                    islandsGonePosted = false
                    if (disposed) return@post
                    Log.w(logTag, "An island's content process ${if (killed) "was killed" else "crashed"}; reopening the islands")
                    vitroView.reopenIslands()
                }
            }
            override fun onAndroidPermissions(permissions: List<String>, answer: (Boolean) -> Unit) =
                host.onAndroidPermissions(permissions, answer)
            override fun onConnect(port: PagePort) {}
            override fun onPortMessage(port: PagePort, message: JSONObject) {}
        }
        /** An island's content went away and the islands' reopening is posted. Main thread. */
        private var islandsGonePosted = false
        private var currentUrl: String? = null
        @Volatile var currentOrigin: String? = null
        private var currentDashboardPath: String? = null

        /** The origin and dashboard path of the config [vitroView] shows, set when it arrives. */
        private var displayedDashboard: Pair<String?, String?>? = null

        /** Bumped for every new client: callbacks of a client that was replaced or closed are dropped. */
        private var clientGeneration = 0
        private var disposed = false

        /** True while a native dashboard owns the page; false once the fallback took it over. */
        private var nativeActive = false

        /** The selected view's own entities, and whether it follows every entity (an empty filter). */
        private var selectedRefs: Set<String> = emptySet()
        private var selectedFollowsAll = false

        /**
         * Entities a web island of the shown dashboard looked up at runtime (`hass.states[id]`), beyond what its
         * config names: subscribed with the selected view, so their changes reach the island. Bounded; cleared with
         * the dashboard.
         */
        private val runtimeEntities = LinkedHashSet<String>()
        private var runtimeResubscribe = false

        /**
         * Views of the shown dashboard (by index) where a web island walked `hass.states`: they follow every entity,
         * as HA's frontend hands a card every state, also when shown again from the cache (the island asks only once).
         * Cleared with the dashboard.
         */
        private val walkedViews = HashSet<Int>()

        /** Bumped with every dashboard config: a walk an island of the previous one reported late is dropped. */
        private var walkGeneration = 0

        /**
         * A web island on view [viewIndex] of dashboard config [generation] walked `hass.states`: that view follows
         * every entity. On the main thread.
         */
        private fun followAllForIsland(viewIndex: Int, generation: Int = walkGeneration) {
            if (disposed || generation != walkGeneration || !walkedViews.add(viewIndex)) return
            if (viewIndex != vitroView.currentViewIndex || selectedFollowsAll) return
            selectedFollowsAll = true
            subscribeSelectedView()
        }

        /**
         * The islands holding the registry feed ([watchIslandRegistries]); main thread only. One feed for them all,
         * on the current client, while at least one holds it.
         */
        private val registryWatchers = LinkedHashSet<(JSONObject) -> Unit>()
        private var registriesNow: JSONObject? = null
        private var registryFeed: (() -> Unit)? = null

        /** [onChange] gets the islands' registries until the returned handle is called. Any thread. */
        private fun watchIslandRegistries(onChange: (JSONObject) -> Unit): () -> Unit {
            main.post {
                if (disposed || !registryWatchers.add(onChange)) return@post
                if (registryFeed == null) attachRegistries()
                registriesNow?.let(onChange)
            }
            return {
                main.post {
                    if (registryWatchers.remove(onChange) && registryWatchers.isEmpty()) detachRegistries()
                }
            }
        }

        /** Moves the registry feed onto the current client (a new one after a reconnect to another server). */
        private fun attachRegistries() {
            detachRegistries()
            val c = client ?: return
            if (registryWatchers.isEmpty()) return
            lateinit var feed: () -> Unit
            feed = c.watchRegistries { set ->
                main.post {
                    // Only the feed still attached: one replaced or released since drops its last answer.
                    if (registryFeed !== feed) return@post
                    registriesNow = set
                    registryWatchers.toList().forEach { it(set) }
                }
            }
            registryFeed = feed
        }

        private fun detachRegistries() {
            registryFeed?.invoke()
            registryFeed = null
            registriesNow = null
        }

        private fun subscribeSelectedView() {
            client?.subscribeViewEntities(if (selectedFollowsAll) emptySet() else selectedRefs + runtimeEntities)
        }

        private fun watchRuntimeEntity(id: String) {
            if (disposed || selectedFollowsAll || id in selectedRefs || runtimeEntities.size >= MAX_RUNTIME_ENTITIES) return
            if (!runtimeEntities.add(id) || runtimeResubscribe) return
            // A card reading several at once costs one resubscription.
            runtimeResubscribe = true
            main.postDelayed({
                runtimeResubscribe = false
                if (!disposed) subscribeSelectedView()
            }, RUNTIME_RESUBSCRIBE_MS)
        }

        /** The Home Assistant origin the app is set to (the settings' dashboard), the only one its token is for. */
        private fun configuredOrigin(): String? = settings(activity.applicationContext).dashboard?.let(OAuth::origin)

        /** Where the island server proxies to: the loaded origin, or before any load the configured one (sign-in first). */
        val proxyOrigin: String? get() = currentOrigin ?: configuredOrigin()

        private val lifecycle = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityDestroyed(a: Activity) { if (a === activity) dispose() }
            override fun onActivityCreated(a: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(a: Activity) { if (a === activity) vitroView.activityShown = true }
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) { if (a === activity) vitroView.activityShown = false }
            override fun onActivitySaveInstanceState(a: Activity, outState: Bundle) {}
        }

        /** The activity is gone: close the Home Assistant connection, so its reader thread no longer holds this page. */
        fun dispose() {
            if (disposed) return
            disposed = true
            readyOnBuild = null
            // The islands' pages and the fallback's go with the activity: nothing of this page keeps running.
            vitroView.closeIslands()
            fallbackPage?.close()
            fallbackPage = null
            activity.application.unregisterActivityLifecycleCallbacks(lifecycle)
            clientGeneration++
            detachRegistries()
            registryWatchers.clear()
            client?.close()
            client = null
            if (activePage === this) activePage = null
        }
        private var pendingViewPath: String? = null
        @Volatile var cachedToken: String? = null

        /** The token with the origin it was issued for, published as one value. */
        @Volatile private var cachedSession: Pair<String, String>? = null

        /** The URLs of the Lovelace resources the dashboard on screen loaded: the island proxy forwards their folders. */
        fun resourceUrls(): List<String> = vitroView.resourcesList.map { it.url }

        /** The island proxy's origin and its token: the token only when it was issued for that very origin. */
        fun islandSession(): Pair<String?, String?> {
            val origin = proxyOrigin
            return origin to tokenFor(origin)
        }

        /**
         * The token for [origin], read when a request goes out: only one issued for that origin. An image or stream
         * queued for the old Home Assistant never gets the next one's token.
         */
        fun tokenFor(origin: String?): String? =
            cachedSession?.takeIf { VitroCardContext.sameOrigin(it.first, origin) }?.second
        private val history = ArrayDeque<String>()

        /** Records [url] for back; a wall panel runs for months, so only the last [MAX_HISTORY] are kept. */
        private fun remember(url: String) {
            history.addLast(url)
            while (history.size > MAX_HISTORY) history.removeFirst()
        }
        private var firstPaintReported = false

        private val port = object : PagePort {
            override val url: String? get() = currentUrl
            override val topLevel: Boolean = true
            override fun post(message: JSONObject) = handleFromHost(message)
        }

        override val view: View get() = container

        init {
            activity.application.registerActivityLifecycleCallbacks(lifecycle)
            container.addView(vitroView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            vitroView.onMetrics = { m ->
                lastMetrics = m
                // The view waited for its states: it's on screen now. Only while the native dashboard still owns the
                // page: leaving it for the fallback or disposing drops the pending readiness.
                readyOnBuild?.let { why ->
                    readyOnBuild = null
                    if (nativeActive && !disposed) signalReady(why)
                }
            }
            // A view's first build waits (up to a second) for its states, as HA's frontend does: no wrong layout first,
            // and no second build once they land.
            vitroView.holdFirstBuildForStatesMs = FIRST_STATES_WAIT_MS
            vitroView.onViewSelected = { v ->
                // An empty filter subscribes to every entity, which auto-entities' runtime filters need.
                selectedFollowsAll = CardRouter.followsAllEntities(v) || v.index in walkedViews
                selectedRefs = if (selectedFollowsAll) emptySet() else CardRouter.referencedEntities(v)
                subscribeSelectedView()
                // Only the dashboard that owns the route reports a location: one still on screen while another loads
                // (a theme rebuild) would pair its view with the other's path.
                // Origin and path: before the new dashboard is on screen (displayedDashboard null) nothing is reported,
                // not even for the default dashboard, whose path is null too.
                val owns = displayedDashboard != null && displayedDashboard == (currentOrigin to currentDashboardPath)
                val path = if (owns) buildViewUrl(currentOrigin, currentDashboardPath, v.path ?: v.index.toString()) else null
                if (owns) {
                    // A reload or a config refresh comes back to this view, not the one the route was loaded with.
                    pendingViewPath = v.path ?: v.index.toString()
                }
                if (path != null && path != currentUrl) {
                    currentUrl = path
                    host.onLocationChange(path)
                }
            }
            // One chronological history for back: a tab tap goes in it like a load does.
            vitroView.onTabNavigation = { currentUrl?.let(::remember) }
            vitroView.pageBack = { backInHistory() }
            vitroView.onThemeResolved = { theme -> reportThemeToHost(theme) }
            vitroView.contextFactory = { theme ->
                val haBase = currentOrigin.orEmpty()
                // The view being built: the context is made for its cards.
                val viewIndex = vitroView.currentViewIndex
                val walkGen = walkGeneration
                VitroCardContext(
                    context = activity,
                    haBase = haBase,
                    store = store,
                    theme = theme,
                    resources = vitroView.resourcesList,
                    accessToken = { tokenFor(haBase) },
                    haConfig = { client?.haConfig ?: JSONObject() },
                    haUser = { client?.currentUser ?: JSONObject() },
                    callService = { domain, service, data, target ->
                        client?.callService(domain, service, data, target)
                    },
                    watchEntity = { id -> main.post { watchRuntimeEntity(id) } },
                    watchAllEntities = { main.post { followAllForIsland(viewIndex, walkGen) } },
                    watchRegistries = { onChange -> watchIslandRegistries(onChange) },
                    callServiceThen = { domain, service, data, target, done ->
                        val c = client
                        if (c != null) c.callService(domain, service, data, target, done) else done(false)
                    },
                    callWs = { msg, done ->
                        val c = client
                        if (c != null) c.callWs(msg, done) else done(false, null, null)
                    },
                    navigate = { path -> navigateInPlace(path) },
                    openMoreInfo = { entityId -> vitroView.openMoreInfo(entityId) },
                    fetchHistory = { ids, hours, done -> client?.fetchHistory(ids, hours, done) },
                    fetchForecast = { id, kind, done -> client?.fetchForecast(id, kind, done) },
                    subscribeWs = { msg, onEvent, onError ->
                        val c = client
                        if (c != null) c.subscribeWs(msg, onEvent, onError) else { onError(null); {} }
                    },
                    subscribeTemplate = { tpl, onRendered ->
                        client?.subscribeTemplate(tpl, onRendered) ?: {}
                    },
                    cameraStream = { entityId, picture, onFrame, onFallback ->
                        val streamUrl = MjpegStreamPlayer.streamUrl(haBase, entityId, picture)
                        if (streamUrl == null) {
                            onFallback()
                            ({})
                        } else {
                            val player = MjpegStreamPlayer(streamUrl, tokenFor(haBase), onFrame = onFrame, onError = onFallback)
                            player.start()
                            ({ player.stop() })
                        }
                    },
                    islandServer = islandServer,
                    openIslandPage = { _ ->
                        // Tier 3 islands always run through the wrapped fallback Engine (GeckoView)
                        // for consistent modern CSS/ES2020 support across Android 6, 10, and 15+.
                        runCatching { fallback.open(activity, islandHost, android.graphics.Color.TRANSPARENT) }.getOrNull()
                    },
                    touch = touchScreen,
                )
            }
            main.post { host.onConnect(port) }
        }

        override fun load(url: String) {
            // A navigation queued before the activity went away: a new client here would outlive the page.
            if (disposed) return
            val redirected = host.redirect(url)
            if (redirected != null) {
                if (redirected == "about:blank") return
                load(redirected)
                return
            }
            currentUrl?.let { if (it != url) remember(it) }
            currentUrl = url

            if (!LovelaceParser.isDashboardUrl(url)) {
                leaveNativeDashboard()
                openInFallback(url)
                return
            }

            showVitroLayer()
            val origin = OAuth.origin(url) ?: run {
                leaveNativeDashboard()
                openInFallback(url)
                return
            }
            nativeActive = true
            val (dashPath, viewPath) = LovelaceParser.parseRoute(url)
            pendingViewPath = viewPath
            host.onPageStart(url)
            host.onLocationChange(url)

            // Same HA origin + same dashboard already shown -> instant in-memory view switch! The shown config's own key
            // counts, not the last request's: that one may still be loading while the old dashboard is on screen.
            if (origin == currentOrigin && displayedDashboard == (origin to dashPath) && vitroView.dashboard != null && client != null) {
                // Back to the dashboard on screen: a request for another one still on its way no longer owns the page.
                currentDashboardPath = dashPath
                client?.claimDashboard(dashPath)
                vitroView.navigateToView(viewPath)
                readyWhenBuilt("view")
                return
            }

            currentDashboardPath = dashPath
            if (origin == currentOrigin && client != null) {
                vitroView.showStatus("Loading dashboard…")
                client?.fetchDashboard(dashPath)
                return
            }

            // New HA origin: start (or restart) the native WebSocket data plane, and drop the old server's views.
            if (origin != currentOrigin) {
                if (currentOrigin != null) vitroView.resetForServer()
                // Nothing of the previous Home Assistant carries over: not its token, entity states or images. Its
                // client closes first: once close() returns it writes nothing more, so the clear below sticks.
                client?.close()
                cachedToken = null
                cachedSession = null
                store.clear()
                VitroCardContext.clearImageCache()
            }
            currentOrigin = origin
            displayedDashboard = null
            firstPaintReported = false
            vitroView.showStatus("Connecting to Home Assistant…")
            client?.close()
            val generation = ++clientGeneration
            val wsClient = HaDashboardClient(
                haBase = origin,
                tokenProvider = { force, done ->
                    // The panel's token is for the Home Assistant it is set to: never hand it to another origin.
                    val allowed = configuredOrigin()
                    if (allowed != null && !VitroCardContext.sameOrigin(allowed, origin)) {
                        Log.w(logTag, "Not sending the Home Assistant token to a dashboard on another origin than the panel's")
                        done(null)
                    } else {
                        tokenProvider(force) { tok ->
                            // Checked again on delivery: the panel's Home Assistant may have changed while the token
                            // was on its way, and then this token is the next one's, not this client's.
                            val stillAllowed = configuredOrigin().let { it == null || VitroCardContext.sameOrigin(it, origin) }
                            if (!stillAllowed) {
                                Log.w(logTag, "Dropping a token that arrived after the panel moved to another Home Assistant")
                                done(null)
                                return@tokenProvider
                            }
                            if (tok != null && generation == clientGeneration) {
                                // Another sign-in than the cached images were fetched with: they go before it is used.
                                VitroCardContext.noteToken(tok)
                                cachedToken = tok
                                cachedSession = origin to tok
                            }
                            done(tok)
                        }
                    }
                },
                store = store,
                connect = socketFactory,
                listener = WsListener(generation),
            )
            client = wsClient
            // Islands holding the registries keep them, now from this Home Assistant.
            if (registryWatchers.isNotEmpty()) attachRegistries()
            wsClient.start(dashPath)
        }

        override fun reload() {
            val activeFallback = fallbackPage
            if (activeFallback != null && activeFallback.view.visibility == View.VISIBLE) {
                activeFallback.reload()
                return
            }
            // Asked for: the dashboard is built again even if its config is the same.
            rebuildNextConfig = true
            client?.fetchDashboard(currentDashboardPath) ?: currentUrl?.let(::load)
        }

        /** The next config is built even when it's the one on screen (a reload the user asked for). */
        private var rebuildNextConfig = false

        override fun goBack() {
            if (vitroView.closeMoreInfo()) return
            val activeFallback = fallbackPage
            if (activeFallback != null && activeFallback.view.visibility == View.VISIBLE) {
                activeFallback.goBack()
                return
            }
            // Every view change (load or tab) is in [history], so it alone says where back goes. The view's own back
            // stack mirrors it, and replaying that after the history would revisit views already gone back through.
            backInHistory()
        }

        /** Loads the previous entry of the page's history; false when there is none. */
        private fun backInHistory(): Boolean {
            if (history.isEmpty()) return false
            val prev = history.removeLast()
            currentUrl = null // don't push onto history again
            load(prev)
            return true
        }

        override fun restart() {
            fallbackPage?.restart()
            // The island server stays up: a locked-down fallback allows its port, and only it, for the process.
            clientGeneration++
            client?.close()
            client = null
            currentOrigin = null
            currentUrl?.let(::load)
        }

        override fun post(message: JSONObject) {
            handleFromHost(message)
            fallbackPage?.post(message)
        }

        override fun capture(done: (Bitmap?) -> Unit) {
            val activeFallback = fallbackPage
            if (activeFallback != null && activeFallback.view.visibility == View.VISIBLE) {
                activeFallback.capture(done)
                return
            }
            main.post {
                val w = container.width
                val h = container.height
                if (w <= 0 || h <= 0) {
                    done(null)
                    return@post
                }
                val bmp = runCatching {
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
                        container.draw(Canvas(b))
                    }
                }.getOrNull()
                done(bmp)
            }
        }

        private fun handleFromHost(m: JSONObject) {
            when (m.optString("type")) {
                "config" -> {
                    val cfg = m.optJSONObject("config") ?: return
                    main.post { vitroView.hideHeader = cfg.optBoolean("hideHeader", true) }
                }
                "navigate" -> {
                    val u = m.optString("url", "").ifEmpty { return }
                    main.post {
                        if (m.optBoolean("replace", false)) currentUrl = null
                        load(u)
                    }
                }
            }
        }

        private fun navigateInPlace(path: String) {
            if (path.startsWith("http://") || path.startsWith("https://")) {
                load(path)
                return
            }
            val origin = currentOrigin ?: return
            val normalized = if (path.startsWith("/")) path else "/$path"
            load(origin + normalized)
        }

        /**
         * The page left the native dashboards for the fallback: a dashboard answer still on its way no longer owns
         * the page (it would render, report its location and signal ready behind the fallback).
         */
        private fun leaveNativeDashboard() {
            nativeActive = false
            readyOnBuild = null
            currentDashboardPath = NO_DASHBOARD
            client?.releaseDashboard()
        }

        private fun showVitroLayer() {
            vitroView.visibility = View.VISIBLE
            showIslandSurfaces()
            vitroView.dashboardShown = true
            fallbackPage?.view?.visibility = View.GONE
        }

        /** The islands' surfaces [hideIslandSurfaces] hid, to show again with the dashboard. */
        private val hiddenSurfaces = ArrayList<SurfaceView>()

        /**
         * Hides the surfaces under [vitroView] (the islands' GeckoViews) while the fallback shows a page. Android 6 hides
         * a SurfaceView only when it, or its window, goes invisible, not an ancestor (API 24's onVisibilityAggregated
         * does): on the legacy engine an island's card stayed drawn over Home Assistant's pages.
         */
        private fun hideIslandSurfaces() {
            fun walk(v: View) {
                if (v is SurfaceView) {
                    if (v.visibility == View.VISIBLE) { v.visibility = View.INVISIBLE; hiddenSurfaces += v }
                } else if (v is ViewGroup) {
                    for (i in 0 until v.childCount) walk(v.getChildAt(i))
                }
            }
            walk(vitroView)
        }

        private fun showIslandSurfaces() {
            for (v in hiddenSurfaces) if (v.visibility == View.INVISIBLE) v.visibility = View.VISIBLE
            hiddenSurfaces.clear()
        }

        /**
         * Shows [url] in the fallback engine, as it is: a Home Assistant page loads from Home Assistant, the legacy
         * engine's too (its lockdown allows the dashboard's origin), so the page's WebSocket and its external auth are
         * Home Assistant's own, and its frontend extras (an integration's panel or script) load where HA serves them.
         */
        private fun openInFallback(url: String) {
            Log.i(logTag, "Routing non-dashboard URL to fallback engine: ${forLog(url)}")
            val fp = fallbackPage ?: fallback.open(activity, host, backgroundColor).also { created ->
                fallbackPage = created
                container.addView(created.view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            }
            vitroView.visibility = View.GONE
            // The islands stop painting under the fallback's page, and Android 6 stops showing their surfaces.
            vitroView.dashboardShown = false
            hideIslandSurfaces()
            fp.view.visibility = View.VISIBLE
            fp.load(url)
        }

        /** Why the page signals ready once the current view is built (it waits for its first states), or null. */
        private var readyOnBuild: String? = null

        /** Ready now if the view on screen is built, else when it is ([VitroView.holdFirstBuildForStatesMs]). */
        private fun readyWhenBuilt(why: String) {
            // A dashboard with no views has nothing to build: it's ready as it is, its "Connecting…" gone.
            val empty = vitroView.dashboard?.views?.isEmpty() == true
            if (empty) vitroView.hideStatus()
            if (vitroView.currentViewBuilt || empty) {
                readyOnBuild = null
                signalReady(why)
            } else {
                readyOnBuild = why
            }
        }

        private fun signalReady(why: String) {
            if (!firstPaintReported) {
                firstPaintReported = true
                host.onFirstPaint()
            }
            host.onPageStop(true)
            host.onPortMessage(port, JSONObject().put("type", "ready").put("why", why))
        }

        private fun reportThemeToHost(theme: ResolvedTheme) {
            val msg = JSONObject()
                .put("type", "haTheme")
                .put("dark", theme.dark)
                .put("primary", theme.hex(theme.primaryColor))
                .put("accent", theme.hex(theme.accentColor))
                .put("background", theme.hex(theme.backgroundColor))
            main.post { host.onPortMessage(port, msg) }
        }

        /** The listener of the client of [generation]; once that client is replaced or closed, its calls do nothing. */
        private inner class WsListener(private val generation: Int) : HaDashboardClient.Listener {
            /** Runs [block] on the main thread if this listener's client is still the page's. */
            private fun post(block: () -> Unit) {
                main.post { if (generation == clientGeneration) block() }
            }

            override fun onConnected(haVersion: String) {
                Log.i(logTag, "Connected to Home Assistant $haVersion over native WebSocket")
                post { vitroView.setConnectionBanner(null) }
            }

            override fun onAuthRequired() {
                Log.i(logTag, "Home Assistant requires authentication; handing off to sign-in flow")
                val base = currentOrigin ?: return
                post {
                    // That client's reader has stopped: the load after sign-in starts a new one instead of reusing it.
                    // Images fetched with the rejected sign-in go too.
                    VitroCardContext.clearImageCache()
                    clientGeneration++
                    // Nor does the page keep that session's registries for its islands: the next client's feed brings them.
                    detachRegistries()
                    client?.close()
                    client = null
                    host.onLocationChange("$base/auth/authorize")
                }
            }

            override fun onDashboards(dashboards: List<DashboardSummary>) {
                val arr = JSONArray()
                for (d in dashboards) {
                    arr.put(JSONObject().put("url_path", d.urlPath).put("title", d.title))
                }
                post {
                    host.onPortMessage(port, JSONObject().put("type", "dashboards").put("json", arr.toString()))
                }
            }

            override fun onResources(resources: List<LovelaceResource>) {
                post { vitroView.resourcesList = resources }
            }

            override fun onThemes(catalog: ThemeCatalog) {
                post { vitroView.setThemes(catalog) }
            }

            override fun onUserTheme(themeName: String?, dark: Boolean?) {
                post { vitroView.setUserTheme(themeName, dark) }
            }

            override fun onDashboardConfig(urlPath: String?, config: DashboardConfig) {
                // Route the cards off the UI thread while the config waits for it to build the view.
                CompatWarmup.start(config, pendingViewPath)
                post {
                    // An older request's answer for a dashboard the page has left must not replace the current one.
                    if (urlPath != currentDashboardPath) return@post
                    val forced = rebuildNextConfig
                    rebuildNextConfig = false
                    // The config on screen again (a reconnect, a lovelace_updated for another dashboard or an edit
                    // undone): the views, their islands and where the user was stay as they are.
                    val shown = vitroView.dashboard
                    if (!forced && displayedDashboard == (currentOrigin to urlPath) && shown != null &&
                        config.source != null && config.source == shown.source
                    ) {
                        vitroView.setConnectionBanner(null)
                        client?.dashboardShown()
                        readyWhenBuilt("vitro")
                        return@post
                    }
                    displayedDashboard = currentOrigin to urlPath
                    // A new config builds new islands; they ask again for what they read.
                    runtimeEntities.clear()
                    walkedViews.clear()
                    walkGeneration++
                    vitroView.setConnectionBanner(null)
                    vitroView.setDashboard(config, pendingViewPath)
                    client?.dashboardShown()
                    readyWhenBuilt("vitro")
                }
            }

            override fun onDashboardConfigFailed(urlPath: String?, code: String) {
                Log.w(logTag, "Home Assistant has no dashboard config for ${urlPath ?: "the default dashboard"}: $code")
                post {
                    if (urlPath != currentDashboardPath) return@post
                    // Only an answer from Home Assistant: a dropped connection reconnects and asks again.
                    if (code == HaDashboardClient.DISCONNECTED || code == HaDashboardClient.SEND_FAILED) return@post
                    // HA's own frontend decides what such a dashboard shows (an auto-generated one, or its error page).
                    // The fallback owns the page now: a reconnect or lovelace_updated doesn't fetch this dashboard again.
                    val url = currentUrl
                    leaveNativeDashboard()
                    url?.let(::openInFallback)
                }
            }

            override fun onDisconnected(reason: String) {
                Log.w(logTag, "HA WebSocket disconnected: ${forLog(reason)}")
                post {
                    if (firstPaintReported) {
                        vitroView.setConnectionBanner("Connection lost. Reconnecting…")
                    } else if (nativeActive) {
                        // Only the native dashboard's own load fails: the host is shared with the fallback page,
                        // which may have loaded (or be loading) normally.
                        host.onPageStop(false)
                    }
                }
            }
        }
    }

    companion object {
        const val VERSION_NAME = "Vitro 0.0.1 (experimental)"

        /** How long a view's first build waits for its first states. */
        private const val FIRST_STATES_WAIT_MS = 1_000L

        /** Entities islands may add to a view's subscription at runtime, and how long their lookups are batched. */
        private const val MAX_RUNTIME_ENTITIES = 256
        private const val RUNTIME_RESUBSCRIBE_MS = 150L

        /** The dashboard path while the fallback shows a non-dashboard page: no real path equals it. */
        private const val NO_DASHBOARD = "\u0000fallback"

        private val LOG_QUERY = Regex("""[?#][^\s"'<>]*""")
        private val LOG_PROXIED = Regex("""/(api|local|hacsfiles|static|auth|__vitro/island|__vitro/ws)/[^\s?#"'<>]*""")

        private val LOG_AUTHORITY = Regex("""\b([a-z][a-z0-9+.-]*://)[^/\s?#"'<>]+""", RegexOption.IGNORE_CASE)
        private val LOG_IPV4 = Regex("""(?<![\d.])\d{1,3}(?:\.\d{1,3}){3}(?::\d+)?(?![\d.])""")
        private val LOG_IPV6 = Regex("""(?<![0-9a-z:])(?:[0-9a-f]{0,4}:){2,7}[0-9a-f]{0,4}(?![0-9a-z:])""", RegexOption.IGNORE_CASE)
        private val LOG_HOST = Regex("""(?<![\w.-])(?:[a-z0-9-]+\.)+[a-z][a-z0-9-]*(?::\d+)?(?![\w.-])""", RegexOption.IGNORE_CASE)

        /**
         * [text] as it may go to logcat: no query or fragment (an OAuth `state`, a camera token, the proxy's
         * capability), no path below the proxied prefixes (entity ids, slot ids, resource names), and no Home
         * Assistant address (an origin, a host name or an IP a connection error names).
         */
        internal fun forLog(text: String): String =
            text.replace(LOG_QUERY, "?…").replace(LOG_PROXIED) { "/${it.groupValues[1]}/…" }
                .replace(LOG_AUTHORITY) { "${it.groupValues[1]}…" }
                .replace(LOG_IPV4, "…").replace(LOG_IPV6, "…").replace(LOG_HOST, "…")

        /** Back steps a page keeps. */
        private const val MAX_HISTORY = 50

        internal fun buildViewUrl(origin: String?, dashPath: String?, viewPath: String): String? {
            if (origin.isNullOrBlank()) return null
            val dash = dashPath?.takeIf { it.isNotBlank() } ?: "lovelace"
            return "${origin.trimEnd('/')}/$dash/$viewPath"
        }
    }
}
