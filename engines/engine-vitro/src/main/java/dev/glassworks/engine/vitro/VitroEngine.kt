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
import dev.glassworks.GlassworksApp
import dev.glassworks.auth.FrontendUrls
import dev.glassworks.auth.HaSocket
import dev.glassworks.auth.OAuth
import dev.glassworks.auth.WsHaSocket
import dev.glassworks.engine.Engine
import dev.glassworks.engine.EngineSettings
import dev.glassworks.engine.Page
import dev.glassworks.engine.PageHost
import dev.glassworks.engine.PagePort
import dev.glassworks.engine.vitro.clarity.ClarityHttp
import dev.glassworks.engine.vitro.clarity.VitroClarity
import dev.glassworks.engine.vitro.compat.CompatWarmup
import dev.glassworks.engine.vitro.compat.IslandCardSizes
import dev.glassworks.engine.vitro.compat.WebFallback
import dev.glassworks.engine.vitro.data.HaFrontend
import dev.glassworks.engine.vitro.data.HaText
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.data.HaDashboardClient
import dev.glassworks.engine.vitro.data.HaLocale
import dev.glassworks.engine.vitro.island.VitroIslandServer
import dev.glassworks.engine.vitro.model.CardRouter
import dev.glassworks.engine.vitro.model.DashboardConfig
import dev.glassworks.engine.vitro.model.DashboardSummary
import dev.glassworks.engine.vitro.model.LovelaceParser
import dev.glassworks.engine.vitro.model.LovelaceResource
import dev.glassworks.engine.vitro.camera.CameraPlayers
import dev.glassworks.engine.vitro.camera.CameraStreamHost
import dev.glassworks.engine.vitro.theme.ResolvedTheme
import dev.glassworks.engine.vitro.theme.ThemeCatalog
import dev.glassworks.engine.vitro.ui.MdiIcons
import dev.glassworks.engine.vitro.ui.VitroCardContext
import dev.glassworks.engine.vitro.ui.VitroMetrics
import dev.glassworks.engine.vitro.ui.VitroView
import dev.glassworks.web.HaPageData
import dev.glassworks.web.HaPageStorage
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
    /**
     * The test hold's dashboard (ScreenPolicy.testDashboard: a loopback or local network page, only while a test holds the screen), or
     * null. Clarity's native harness runs only while it is Clarity's reference dashboard ([VitroClarity]).
     */
    private val testDashboard: (Context) -> String? = { c ->
        (c.applicationContext as? GlassworksApp)?.glassworks?.screen?.testDashboard
    },
) : Engine {

    @Volatile private var activeInProcess: Boolean? = null
    @Volatile private var activePage: VitroPage? = null

    /**
     * The loopback server for Tier 3 islands, one per process and started on first use ([ensureStarted] picks the
     * port once). It reads the active page's Home Assistant origin, token and Lovelace resources on every request.
     * The legacy engine (a locked-down [fallback]) is held to the dashboard's origin, so [islandOrigin] gives it this
     * one up front as well.
     */
    /** How Clarity's harness reaches its fake Home Assistant ([VitroClarity]); tests give one that touches no network. */
    internal var clarityHttp: ClarityHttp = ClarityHttp.Loopback

    /** How often a page's connection is checked and its token asked for again ([HaDashboardClient]); tests shorten it. */
    internal var keepAliveMs: Long = HaDashboardClient.KEEP_ALIVE_MS

    /** The waits between a page's retries of a dashboard config HA failed to give ([HaDashboardClient]); tests shorten them. */
    internal var configRetryMs: LongArray = HaDashboardClient.CONFIG_RETRY_MS

    /** The app, once Vitro has seen it: where the island server keeps its port ([islandPort]). */
    @Volatile private var appContext: Context? = null

    /**
     * The island server's port between app starts, in the app's preferences: the islands keep one origin, and the
     * storage their cards keep there (#881). Before Vitro has seen the app, nothing is kept (a new port).
     */
    private val islandPort = object : VitroIslandServer.PortMemory {
        private fun prefs() = appContext?.getSharedPreferences(ISLAND_PREFS, Context.MODE_PRIVATE)
        override fun load(): Int = prefs()?.getInt(ISLAND_PORT, 0) ?: 0
        override fun save(port: Int) { prefs()?.edit()?.putInt(ISLAND_PORT, port)?.apply() }
    }

    /**
     * The island proxy's session again after Home Assistant refused its token (a 401 on a proxied `/api/` request): a
     * forced refresh, handed to every consumer. For the island server to retry once with; [done] runs on any thread.
     */
    fun renewIslandSession(done: (Pair<String?, String?>) -> Unit) {
        val page = activePage ?: return done(null to null)
        page.renewIslandSession(done)
    }

    val islandServer: VitroIslandServer = VitroIslandServer(
        session = { activePage?.islandSession() ?: (null to null) },
        log = { msg -> Log.d(logTag, "island: ${forLog(msg)}") },
        resources = { activePage?.resourceUrls().orEmpty() },
        portMemory = islandPort,
        // A request Home Assistant refused (401) is tried once more with the token this renews.
        renewSession = { done -> renewIslandSession(done) },
    )

    /**
     * The island server's loopback origin, started now: the second origin a locked-down [fallback] lets pages load
     * (`EngineSettings.localOrigin`), for the islands alone. Home Assistant's own pages load from Home Assistant.
     * With [context], on the port it had last time.
     */
    fun islandOrigin(context: Context? = null): String {
        context?.let(::seeApp)
        return islandServer.ensureStarted()
    }

    private fun seeApp(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }
    @Volatile private var startedSettings: EngineSettings? = null
    @Volatile var lastMetrics: VitroMetrics = VitroMetrics()
        private set

    override val started: EngineSettings?
        get() = if (activeInProcess == true) startedSettings else fallback.started

    override val name: String
        get() = if (activeInProcess == true) VERSION_NAME else fallback.name

    override val lockedDown: Boolean
        get() = fallback.lockedDown

    /** The islands start from HA's `localStorage` (#881): only while Vitro draws in this process. */
    override val keepsPageStorage: Boolean
        get() = activeInProcess == true

    override fun isContentProcess(processName: String): Boolean = fallback.isContentProcess(processName)

    override fun open(activity: Activity, host: PageHost, background: Int): Page {
        seeApp(activity)
        val useVitro = activeInProcess ?: enabled(activity.applicationContext).also { activeInProcess = it }
        if (!useVitro) return fallback.open(activity, host, background)

        val cfg = settings(activity.applicationContext)
        startedSettings = cfg
        MdiIcons.init(activity.applicationContext)
        // HA's frontend strings, kept per frontend version and language for a start without HA (HaFrontend).
        HaFrontend.cacheDir = java.io.File(activity.applicationContext.cacheDir, HaPageData.FRONTEND_CACHE)
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
        /**
         * What one island's page reports: its own host, so a crashed or killed content process names the island it
         * took. Only permissions go on to the activity's host.
         */
        private inner class IslandHost : PageHost {
            /** The page this host is for, set once the engine opened it. */
            var page: Page? = null
            override fun onPageStart(url: String) {}
            override fun onPageStop(success: Boolean) {}
            override fun onFirstPaint() {}
            override fun onLocationChange(url: String?) {}
            override fun redirect(url: String): String? = null
            // The island's page stays the island's: a card's link or `window.open` (a calendar event's link, an `<a
            // href>`) would load that site at card size with no way back. It opens as a native card's `url` action
            // does instead ([navigateInPlace]: the panel's page, with back).
            override fun allowsLoad(url: String): Boolean {
                // An island document of the island server (`/<cap>/__vitro/island/<id>`), never another of its paths or hosts.
                if (url == "about:blank" || islandServer.isIslandPage(url)) return true
                val haPath = islandServer.haPath(url)
                when {
                    // Another document the island server proxies from HA (a `/local/help.html`): HA's own page for it,
                    // with back, never in the card's window, and without the island server's capability.
                    haPath != null -> main.post { if (!disposed) navigateInPlace(haPath) }
                    url.startsWith("http://") || url.startsWith("https://") -> main.post { if (!disposed) navigateInPlace(url) }
                }
                return false
            }
            // The activity's host decides, as for every page (#847). An island's own page is on loopback, not Home
            // Assistant, so its card gets no microphone or camera without a prompt.
            override fun trusts(url: String): Boolean = host.trusts(url)
            // The island's page draws nothing again: it never goes to another build of its card, and its card gets a
            // new one. Islands whose content lives on keep theirs.
            override fun onContentGone(killed: Boolean) {
                val dead = page ?: return
                deadIslandPages += dead
                if (deadIslandsPosted) return
                deadIslandsPosted = true
                main.post {
                    deadIslandsPosted = false
                    val pages = deadIslandPages.toSet()
                    deadIslandPages.clear()
                    if (disposed) return@post
                    Log.w(logTag, "${pages.size} island page(s) lost their content process (${if (killed) "killed" else "crashed"}); reopening them unless they keep dying")
                    vitroView.islandPagesGone(pages)
                }
            }
            override fun onAndroidPermissions(permissions: List<String>, answer: (Boolean) -> Unit) =
                host.onAndroidPermissions(permissions, answer)
            override fun onConnect(port: PagePort) {}
            override fun onPortMessage(port: PagePort, message: JSONObject) {}
        }

        /** The islands to tell when a new token or the signed-in user arrives ([VitroCardContext.watchToken]). Any thread. */
        private val tokenWatchers = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()

        /**
         * Whether the connection to Home Assistant is up, and the islands to tell when it goes down or comes back
         * ([VitroCardContext.watchConnection]). Any thread.
         */
        private val haConnected: Boolean get() = connectedGeneration == clientGeneration
        private val connectionWatchers = java.util.concurrent.CopyOnWriteArraySet<(Boolean) -> Unit>()

        /** The islands to tell when Home Assistant's config arrives ([VitroCardContext.watchConfig]). Any thread. */
        private val configWatchers = java.util.concurrent.CopyOnWriteArraySet<(JSONObject) -> Unit>()

        /** Counts the connections that came up ([VitroCardContext.haConnectionEpoch]). */
        @Volatile private var connectionEpoch = 0L

        /** The client ([clientGeneration]) whose connection is up, -1 when none is: a new client starts down. */
        @Volatile private var connectedGeneration = -1

        /**
         * Tells the islands the connection of the client of [generation] is [up] (or down), once per change. A new
         * client's first connection is a change too, so the islands' subscriptions start on it. One at a time: a client
         * being replaced can't write its state after the new one's, and watchers hear each change once, in order.
         */
        @Synchronized
        private fun connectionIs(up: Boolean, generation: Int) {
            if (generation != clientGeneration) return
            val now = if (up) generation else -1
            if (connectedGeneration == now) return
            connectedGeneration = now
            if (up) connectionEpoch++
            connectionWatchers.forEach { runCatching { it(up) } }
        }

        /**
         * Moves to a new client generation (the client is replaced, closed or signs in again) and gives it. Islands whose
         * connection was up hear it go down now: the old client's own `onDisconnected` no longer reaches them, and the
         * new client's first connection is then a change they start their subscriptions on.
         */
        @Synchronized
        private fun retireClient(): Int {
            val next = ++clientGeneration
            if (connectedGeneration != -1) {
                connectedGeneration = -1
                connectionWatchers.forEach { runCatching { it(false) } }
            }
            return next
        }

        /** Island pages whose content went away this turn ([IslandHost]), handled together. Main thread. */
        private val deadIslandPages = LinkedHashSet<Page>()
        private var deadIslandsPosted = false

        /**
         * What the fallback page reports, on its way to [host]. Only while it is in front: a page behind the native
         * dashboard shows no loading or offline screen and says nothing of where it is. A move of Home Assistant's own
         * frontend to a dashboard Vitro draws (a sidebar tap, a link) hands the page back to the native view. Loads,
         * permissions and ports always go through; a crashed content process only while the page is in front.
         */
        private val fallbackHost = object : PageHost {
            override fun onPageStart(url: String) { if (fallbackInFront()) host.onPageStart(url) }
            override fun onPageStop(success: Boolean) { if (fallbackInFront()) host.onPageStop(success) }
            override fun onFirstPaint() { if (fallbackInFront()) host.onFirstPaint() }
            override fun onLocationChange(url: String?) {
                if (!fallbackInFront()) return
                if (url != null) fallbackLocation = url
                if (url != null && handsBack(url)) {
                    Log.i(logTag, "Home Assistant's frontend went to a dashboard Vitro draws: ${forLog(url)}")
                    pendingHandBack = url
                    // HA's More Info dialog closed (its URL lost `more-info-entity-id`): back to the view it opened
                    // over, as if it had never left, so Back doesn't open the dialog again.
                    val closedDialog = moreInfoReturn?.let { samePage(it, url) } == true
                    main.post {
                        // Only if the page is still there: a newer location (edit mode, a sign-in) supersedes it.
                        if (pendingHandBack != url) return@post
                        pendingHandBack = null
                        if (disposed || !fallbackInFront()) return@post
                        if (closedDialog) {
                            currentUrl = null
                            if (history.lastOrNull() == moreInfoReturn) history.removeLast()
                        }
                        moreInfoReturn = null
                        load(url)
                    }
                    return
                }
                // HA's page moved on from the view the dialog opened over: closing the dialog is no way back now.
                if (url != null && moreInfoReturn?.let { samePage(it, url) } != true) moreInfoReturn = null
                pendingHandBack = null
                host.onLocationChange(url)
            }
            override fun redirect(url: String): String? = host.redirect(url)
            override fun onContentGone(killed: Boolean) {
                // In front, it's the page the user sees: the activity starts it over, as for any engine's page.
                if (fallbackInFront()) {
                    host.onContentGone(killed)
                    return
                }
                // Behind the native dashboard (which doesn't run in Gecko), only the hidden page went with the content
                // process (islands, in the same runtime, are reopened by their own hosts). It starts over in place,
                // empty, as showVitroLayer leaves it. The activity isn't told: its answer, restart(), would close
                // the native WebSocket and draw the whole dashboard again for a page the user wasn't looking at.
                // Marked now: a page sent to the fallback before the restart below runs restarts it first (openInFallback),
                // instead of loading into the session that went.
                fallbackFresh = false
                main.post {
                    if (disposed || fallbackInFront()) return@post
                    Log.w(logTag, "The hidden fallback page lost its content process (${if (killed) "killed" else "crashed"}); it starts over, the native dashboard stays")
                    fallbackPage?.restart()
                    fallbackFresh = true
                }
            }
            // Home Assistant's own frontend: the activity's host decides, as without Vitro (#847).
            override fun trusts(url: String): Boolean = host.trusts(url)
            override fun onAndroidPermissions(permissions: List<String>, answer: (Boolean) -> Unit) =
                host.onAndroidPermissions(permissions, answer)
            override fun onConnect(port: PagePort) = host.onConnect(port)
            override fun onPortMessage(port: PagePort, message: JSONObject) {
                if (fallbackInFront()) host.onPortMessage(port, message)
            }
        }

        /**
         * The last native dashboard view the page showed, set once its config is on screen (a dashboard HA has no
         * config for never is): where Back and Reload dashboard return from the fallback.
         */
        private var lastNativeUrl: String? = null

        /** A dashboard HA's frontend went to, queued to load natively; null once a newer location supersedes it. */
        private var pendingHandBack: String? = null

        /**
         * The native view HA's More Info dialog was opened over ([VitroView.onMoreInfoInFrontend]), while HA's page shows it:
         * the dialog closing goes back there in place, without a step in the history.
         */
        private var moreInfoReturn: String? = null

        /** Dashboards (origin and path) whose config Home Assistant didn't have: its frontend draws them, not Vitro. */
        private val webDashboards = HashSet<Pair<String?, String?>>()

        /**
         * Dashboards whose config Home Assistant failed to read with an error that may pass (anything but
         * `config_not_found`, after the client's retries): HA's frontend shows them, with its own error, for now. Unlike
         * [webDashboards] they are Vitro's again on the next connection or Reload dashboard.
         */
        private val failedDashboards = HashSet<Pair<String?, String?>>()

        /** The fallback page's latest location: where it was opened, then where Home Assistant's frontend went. */
        private var fallbackLocation: String? = null

        /** False until the fallback page shows a page: then the next one starts it over, without that page's history. */
        private var fallbackFresh = true

        private fun fallbackInFront(): Boolean = fallbackPage?.view?.visibility == View.VISIBLE

        /**
         * True when [url], where Home Assistant's frontend went, is a dashboard of the page's Home Assistant that Vitro
         * draws itself. Not one HA's frontend had to draw (no saved config), and not a sign-in's return or edit mode.
         */
        private fun handsBack(url: String): Boolean {
            val origin = OAuth.origin(url) ?: return false
            if (!VitroCardContext.sameOrigin(origin, proxyOrigin)) return false
            if (!LovelaceParser.isDashboardUrl(url)) return false
            val query = runCatching { java.net.URI(url).rawQuery }.getOrNull().orEmpty()
            // HA's own More Info dialog over the view (a script's fields): it stays HA's until the dialog closes.
            if (inFrontendWorkflow(query)) return false
            return !isWebDashboard(url)
        }

        /**
         * A sign-in callback, HA's dashboard editor or its More Info dialog (a script's fields) on the page: the user is
         * in the middle of something there, which neither a hand-back nor a reconnect's retry may reload.
         */
        private fun inFrontendWorkflow(query: String): Boolean =
            "auth_callback" in query || "edit=" in query || "more-info-entity-id=" in query

        /** True when [url] is a dashboard HA had no config for: its frontend draws it, so it's no way back to Vitro. */
        private fun isWebDashboard(url: String): Boolean =
            (OAuth.origin(url) to LovelaceParser.parseRoute(url).first).let { it in webDashboards || it in failedDashboards } ||
                isWebView(url)

        /**
         * True when [url] opens a view of the dashboard on screen that Vitro sends to HA's frontend (a strategy, a card
         * or row it doesn't draw: [WebFallback]). Like a dashboard with no config, it's no way back to Vitro: handing it
         * back would only send it to the fallback again.
         */
        private fun isWebView(url: String): Boolean {
            val (dash, view) = LovelaceParser.parseRoute(url)
            if (displayedDashboard != (OAuth.origin(url) to dash)) return false
            val d = vitroView.dashboard ?: return false
            return d.views.getOrNull(d.viewIndex(view, currentUserId()))?.let { vitroView.fallbackReason(it) } != null
        }

        /** The signed-in Home Assistant user's id, or null while it isn't known. */
        private fun currentUserId(): String? = client?.currentUser?.optString("id", "")?.ifEmpty { null }

        /** The dashboard the panel is set to, when it's one Vitro draws. */
        private fun configuredDashboard(): String? =
            settings(activity.applicationContext).dashboard?.takeIf { LovelaceParser.isDashboardUrl(it) && !isWebDashboard(it) }
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

        /** Clarity's native harness, while the test hold shows its reference dashboard here; null otherwise. */
        private var clarity: VitroClarity? = null

        /**
         * Starts Clarity's harness when the page shows the reference dashboard (`/clarity-ref/…`) on the origin of the
         * test hold's dashboard, Clarity's fake (loopback or LAN); stops it on anything else. Outside a Clarity run this
         * is one lookup per load.
         */
        private fun followClarity(origin: String, dashPath: String?, viewPath: String?) {
            val test = runCatching { testDashboard(activity.applicationContext) }.getOrNull()
            if (test == null || OAuth.origin(test) != origin || dashPath != VitroClarity.DASHBOARD) {
                stopClarity()
                return
            }
            // One harness per fake: a test hold moved to another loopback port gets a new one, polling that fake.
            if (clarity?.fake == origin) return
            stopClarity()
            Log.i(logTag, "Clarity: measuring Vitro on the test dashboard")
            clarity = VitroClarity(
                origin, vitroView, store, viewPath ?: "start", follow = ::watchRuntimeEntity,
                // A harness that ended by itself goes, unless another has replaced it meanwhile: the next load starts one.
                onExit = { done -> if (clarity === done) stopClarity() },
                // The hold ending (or moving) ends it too, whether or not a page load follows.
                active = {
                    runCatching { testDashboard(activity.applicationContext) }.getOrNull()
                        ?.let { OAuth.origin(it) == origin } == true
                },
                transport = clarityHttp,
            ).also {
                vitroView.attachClarity(it)
                it.start()
            }
        }

        private fun stopClarity() {
            val c = clarity ?: return
            clarity = null
            vitroView.attachClarity(null)
            c.stop()
        }

        /** The selected view's own entities, and whether it follows every entity (an empty filter). */
        private var selectedRefs: Set<String> = emptySet()
        private var selectedFollowsAll = false

        /**
         * Every entity the shown dashboard references, in all its views and subviews ([dashboardEntitiesOf]): what the
         * client follows, once per connection, so a view switch asks HA for nothing. Null above
         * [MAX_DASHBOARD_ENTITIES]: then each view follows its own, as it is selected. Computed for
         * [dashboardEntitiesFor], the config on screen.
         */
        private var dashboardEntities: Set<String>? = null
        private var dashboardEntitiesFor: DashboardConfig? = null

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

        /**
         * The entities the page follows now, on the current client.
         *
         * Every entity of the dashboard, as HA's frontend subscribes once at startup: a view switch sends nothing and
         * waits for no snapshot, and a config change or an island's runtime read adds only the new ids
         * ([HaDashboardClient.followEntities], additive). The selected view's own come first in a new subscription,
         * so its first build waits for its snapshot alone. A view that follows every entity (auto-entities, an island
         * that walks `hass.states`) follows them while it is selected, and the dashboard's again after it.
         *
         * Above [MAX_DASHBOARD_ENTITIES] each view follows only its own: a view switch replaces the subscription
         * ([additive] only for an island's runtime read, which adds to the view's).
         */
        private fun subscribeSelectedView(additive: Boolean = false) {
            val c = client ?: return
            val all = dashboardEntities
            when {
                selectedFollowsAll -> c.followEntities(emptySet())
                all != null -> c.followEntities(all + runtimeEntities, first = selectedRefs, additive = true)
                else -> c.followEntities(selectedRefs + runtimeEntities, additive = additive)
            }
        }

        /** [config]'s entities ([dashboardEntities]), or null when there are more than [MAX_DASHBOARD_ENTITIES]. */
        private fun dashboardEntitiesOf(config: DashboardConfig): Set<String>? {
            val out = LinkedHashSet<String>()
            for (v in config.views) {
                out += CardRouter.referencedEntities(v)
                if (out.size > MAX_DASHBOARD_ENTITIES) return null
            }
            return out
        }

        private fun watchRuntimeEntity(id: String) {
            if (disposed || selectedFollowsAll || id in selectedRefs || dashboardEntities?.contains(id) == true ||
                runtimeEntities.size >= MAX_RUNTIME_ENTITIES
            ) return
            if (!runtimeEntities.add(id) || runtimeResubscribe) return
            // A card reading several at once costs one more subscription.
            runtimeResubscribe = true
            main.postDelayed({
                runtimeResubscribe = false
                if (!disposed) subscribeSelectedView(additive = true)
            }, RUNTIME_RESUBSCRIBE_MS)
        }

        /** The Home Assistant origin the app is set to (the settings' dashboard), the only one its token is for. */
        private fun configuredOrigin(): String? = settings(activity.applicationContext).dashboard?.let(OAuth::origin)

        /**
         * True when [url] is on the panel's own Home Assistant: the one it is set to, or the test hold's fake while a
         * test holds the screen (Clarity's reference dashboards). A panel set to no Home Assistant yet has no outside
         * to tell apart: any origin may be its dashboard, as before it had one.
         */
        private fun onPanelsHa(url: String): Boolean {
            val own = listOfNotNull(
                configuredOrigin(),
                runCatching { testDashboard(activity.applicationContext) }.getOrNull()?.let(OAuth::origin),
            )
            return own.isEmpty() || own.any { VitroCardContext.sameOrigin(url, it) }
        }

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
            stopClarity()
            // The islands' pages and the fallback's go with the activity: nothing of this page keeps running.
            // closeIslands() cancels the pending builds too; closeMoreInfo() dismisses the sheet and makes a script-fields
            // lookup still out answer into nothing, so a disposed page opens no sheet.
            vitroView.closeMoreInfo()
            vitroView.closeIslands()
            fallbackPage?.close()
            fallbackPage = null
            activity.application.unregisterActivityLifecycleCallbacks(lifecycle)
            retireClient()
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
         * A fresh token for the island proxy, for a request Home Assistant refused with the one [islandSession] gave
         * (a 401): the provider is asked with force, every consumer gets the new token as with any other ([tokenWatchers]),
         * and [done] gets the session after it, on any thread. With no connection it gets the session as it is.
         */
        fun renewIslandSession(done: (Pair<String?, String?>) -> Unit) {
            val c = client ?: return done(islandSession())
            c.refreshToken(force = true) { done(islandSession()) }
        }

        /**
         * The token for [origin], read when a request goes out: only one issued for that origin. An image or stream
         * queued for the old Home Assistant never gets the next one's token.
         */
        fun tokenFor(origin: String?): String? =
            cachedSession?.takeIf { VitroCardContext.sameOrigin(it.first, origin) }?.second

        /**
         * The camera players for the dashboard on [haBase] (HLS and MJPEG, WebRTC once its player registers), on
         * the dashboard's own WebSocket: built afresh with each context, so a player registered meanwhile counts.
         */
        private fun cameraPlayersFor(haBase: String): CameraPlayers? {
            if (haBase.isEmpty()) return null
            val host = CameraStreamHost(
                context = activity,
                main = main,
                haBase = haBase.trimEnd('/'),
                accessToken = { tokenFor(haBase) },
                callWs = { msg, done ->
                    val c = client
                    if (c != null) c.callWs(msg, done) else done(false, null, null)
                },
                // A camera's exchange (a WebRTC offer and its candidates) belongs to one connection: it isn't sent again
                // on the next one, as a card's subscription is.
                subscribeWs = { msg, onEvent, onError ->
                    val c = client
                    if (c != null) c.subscribeWs(msg, onEvent, onError, resubscribe = false) else { onError(null); {} }
                },
            )
            return CameraPlayers.forHost(host) { id -> store[id]?.entityPicture }
        }
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
            // The device's zone is read once and kept (HaLocale.deviceTimeZone): this hears it change.
            HaLocale.watchDeviceZone(activity)
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
            // And for the dashboard's Lovelace resources, which decide its islands and whether it goes to HA's frontend:
            // one answer per server, so only a cold start (or another HA) waits, and only when they land after the config.
            vitroView.holdFirstBuildForResourcesMs = FIRST_RESOURCES_WAIT_MS
            vitroView.onViewSelected = { v ->
                // An empty filter subscribes to every entity, which auto-entities' runtime filters need.
                selectedFollowsAll = CardRouter.followsAllEntities(v) || v.index in walkedViews
                selectedRefs = if (selectedFollowsAll) emptySet() else CardRouter.referencedEntities(v)
                // A new config (another dashboard, a lovelace_updated): its entities, once.
                val shown = vitroView.dashboard
                if (shown !== dashboardEntitiesFor) {
                    dashboardEntitiesFor = shown
                    dashboardEntities = shown?.let(::dashboardEntitiesOf)
                }
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
                if (path != null) lastNativeUrl = path
                if (path != null && path != currentUrl) {
                    currentUrl = path
                    host.onLocationChange(path)
                }
            }
            vitroView.onWebView = { v, reason ->
                // Only for the dashboard that owns the route, as onViewSelected: one still on screen while another
                // loads isn't the page's to leave.
                // A disposed page opens nothing: an island's report posted before dispose can still land here.
                if (!disposed && nativeActive && displayedDashboard != null && displayedDashboard == (currentOrigin to currentDashboardPath)) {
                    val url = buildViewUrl(currentOrigin, currentDashboardPath, v.path ?: v.index.toString())
                    if (url != null) {
                        Log.i(logTag, "Vitro doesn't draw $reason; Home Assistant's frontend shows ${forLog(url)}")
                        leaveNativeDashboard()
                        currentUrl = url
                        openInFallback(url)
                    }
                }
            }
            vitroView.onMoreInfoInFrontend = { v, entityId, reason, moreInfoView ->
                // As onWebView, with HA's own More Info dialog open over the view (its `more-info-entity-id`). When the
                // dialog closes, HA drops the parameter, and the plain view hands the page back (handsBack).
                if (!disposed && nativeActive && displayedDashboard != null && displayedDashboard == (currentOrigin to currentDashboardPath)) {
                    val url = buildViewUrl(currentOrigin, currentDashboardPath, v.path ?: v.index.toString())
                    if (url != null) {
                        val withDialog = "$url?more-info-entity-id=${java.net.URLEncoder.encode(entityId, "UTF-8")}" +
                            (moreInfoView?.let { "&more-info-view=${java.net.URLEncoder.encode(it, "UTF-8")}" } ?: "")
                        Log.i(logTag, "Vitro doesn't draw $reason; Home Assistant's frontend shows ${forLog(url)} with its dialog")
                        leaveNativeDashboard()
                        // Back from HA's dialog comes back to this view; closing it does too, in place ([moreInfoReturn]).
                        currentUrl?.let(::remember)
                        moreInfoReturn = url
                        currentUrl = withDialog
                        openInFallback(withDialog)
                    }
                }
            }
            // One chronological history for back: a tab tap goes in it like a load does.
            vitroView.onTabNavigation = { currentUrl?.let(::remember) }
            vitroView.pageBack = { backInHistory() }
            vitroView.onThemeResolved = { theme -> reportThemeToHost(theme) }
            vitroView.currentUserId = { currentUserId() }
            vitroView.haOrigin = { currentOrigin }
            vitroView.contextFactory = { theme ->
                val haBase = currentOrigin.orEmpty()
                // The view being built: the context is made for its cards.
                val viewIndex = vitroView.currentViewIndex
                val walkGen = walkGeneration
                val resources = vitroView.resourcesList
                // The Home Assistant this view's islands are of: a size one reports after another took its place is dropped.
                val serverGen = vitroView.serverGeneration
                VitroCardContext(
                    context = activity,
                    haBase = haBase,
                    store = store,
                    theme = theme,
                    resources = resources,
                    accessToken = { tokenFor(haBase) },
                    haConfig = { client?.haConfig ?: JSONObject() },
                    pageUrl = { lastNativeUrl ?: currentUrl },
                    haUser = { client?.currentUser ?: JSONObject() },
                    callService = { domain, service, data, target ->
                        client?.callService(domain, service, data, target)
                    },
                    watchEntity = { id -> main.post { watchRuntimeEntity(id) } },
                    watchAllEntities = { main.post { followAllForIsland(viewIndex, walkGen) } },
                    watchRegistries = { onChange -> watchIslandRegistries(onChange) },
                    watchToken = { onChange ->
                        tokenWatchers += onChange
                        ({ tokenWatchers -= onChange })
                    },
                    callServiceThen = { domain, service, data, target, done ->
                        val c = client
                        if (c != null) c.callService(domain, service, data, target, done) else done(false)
                    },
                    callWs = { msg, done ->
                        val c = client
                        if (c != null) c.callWs(msg, done) else done(false, null, null)
                    },
                    navigate = { path -> navigateInPlace(path) },
                    navigateReplace = { path -> navigateInPlace(path, replace = true) },
                    openMoreInfo = { entityId -> vitroView.openMoreInfo(entityId) },
                    fetchHistory = { ids, hours, done -> client?.fetchHistory(ids, hours, done) },
                    subscribeForecast = { id, kind, onForecast -> client?.subscribeForecast(id, kind, onForecast) ?: {} },
                    subscribeWs = { msg, onEvent, onError ->
                        val c = client
                        if (c != null) c.subscribeWs(msg, onEvent, onError) else { onError(null); {} }
                    },
                    subscribeWsFresh = { message, onEvent, onError ->
                        val c = client
                        if (c != null) c.subscribeWsFresh(message, onEvent, onError) else { onError(null); {} }
                    },
                    subscribeTemplate = { tpl, onRendered ->
                        client?.subscribeTemplate(tpl, onRendered) ?: {}
                    },
                    subscribeWsStarted = { msg, onStarted, onEvent, onError ->
                        val c = client
                        // On this connection only: the island starts its subscriptions again itself after a reconnect
                        // (each page's own `resubscribe` and `preCheck`), so the client doesn't hold them.
                        if (c != null) c.subscribeWs(msg, onEvent, onError, resubscribe = false, onStarted = onStarted) else { onError(null); {} }
                    },
                    cameraPlayers = cameraPlayersFor(haBase),
                    islandServer = islandServer,
                    openIslandPage = { _ ->
                        // Tier 3 islands always run through the wrapped fallback Engine (GeckoView)
                        // for consistent modern CSS/ES2020 support across Android 6, 10, and 15+.
                        val islandHost = IslandHost()
                        runCatching { fallback.open(activity, islandHost, android.graphics.Color.TRANSPARENT) }.getOrNull()
                            ?.also { islandHost.page = it }
                            // A view rebuilt behind the fallback (a theme change): its islands stay hidden until it shows.
                            ?.also { if (fallbackInFront()) hideIslandSurfaces(it.view) }
                    },
                    touch = touchScreen,
                    needsFrontend = { card, tags, owners -> vitroView.cardNeedsFrontend(card, tags, owners, resources) },
                    islandCardSize = { card, size -> vitroView.islandCardSize(card, size, serverGen) },
                    islandFailed = { card, reason -> vitroView.cardFailedInIsland(card, reason, resources) },
                    matchedNeedFrontend = { card, reason -> vitroView.matchedNeedFrontend(card, reason) },
                    haConnected = { haConnected },
                    haConnectionEpoch = { connectionEpoch },
                    watchConnection = { onChange ->
                        connectionWatchers += onChange
                        ({ connectionWatchers -= onChange })
                    },
                    watchConfig = { onChange ->
                        configWatchers += onChange
                        ({ configWatchers -= onChange })
                    },
                    pageStorage = { HaPageStorage.of(activity).items(haBase.ifEmpty { null }) },
                    showToast = { toast -> vitroView.showToast(toast) },
                    moreInfoInFrontend = { entityId, view, reason -> vitroView.moreInfoInFrontend(entityId, view, reason) },
                )
            }
            main.post { host.onConnect(port) }
        }

        override fun load(url: String) {
            // A navigation queued before the activity went away: a new client here would outlive the page.
            if (disposed) return
            // A path (`/history`) is one on Home Assistant: no engine loads a bare path, and the legacy one's lockdown
            // refuses it for its missing origin. The core sends URLs; a path that reaches here goes on HA's origin too.
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                val resolved = FrontendUrls.resolve(url, currentOrigin ?: configuredOrigin())
                if (resolved == null) {
                    Log.w(logTag, "Not a URL, nor a path on a known Home Assistant: nothing loads")
                    return
                }
                if (resolved != url) {
                    load(resolved)
                    return
                }
            }
            val redirected = host.redirect(url)
            if (redirected != null) {
                if (redirected == "about:blank") return
                load(redirected)
                return
            }
            currentUrl?.let { if (it != url) remember(it) }
            currentUrl = url

            // Only the panel's own Home Assistant has dashboards. Any other address (a card's link to a web page, an
            // island's) is a page, shown as it is, as HA's frontend opens an outside URL.
            if (!LovelaceParser.isDashboardUrl(url) || !onPanelsHa(url)) {
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
            followClarity(origin, dashPath, viewPath)
            pendingViewPath = viewPath
            host.onPageStart(url)
            host.onLocationChange(url)

            // Same HA origin + same dashboard already shown -> instant in-memory view switch! The shown config's own key
            // counts, not the last request's: that one may still be loading while the old dashboard is on screen.
            if (origin == currentOrigin && displayedDashboard == (origin to dashPath) && vitroView.dashboard != null && client != null) {
                // Back to the dashboard on screen: a request for another one still on its way no longer owns the page.
                currentDashboardPath = dashPath
                val previousNative = lastNativeUrl
                lastNativeUrl = url // its config is on screen already
                client?.claimDashboard(dashPath)
                vitroView.navigateToView(viewPath)
                // A view Vitro doesn't draw left for the fallback, which signals ready itself. It's no return target:
                // the way back from it is the native view before it.
                if (nativeActive) readyWhenBuilt("view") else lastNativeUrl = previousNative
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
                webDashboards.clear()
                failedDashboards.clear()
            }
            currentOrigin = origin
            // The sizes this Home Assistant's island cards gave before: the first masonry build places them once.
            IslandCardSizes.useStore(vitroView.context.applicationContext.cacheDir, origin)
            displayedDashboard = null
            firstPaintReported = false
            vitroView.showStatus("Connecting to Home Assistant…")
            client?.close()
            val generation = retireClient()
            // Said once per client: the client asks for its token again every 30 s.
            val withheldLogged = java.util.concurrent.atomic.AtomicBoolean(false)
            val wsClient = HaDashboardClient(
                haBase = origin,
                tokenProvider = { force, done ->
                    // The panel's token is for the Home Assistant it is set to: never hand it to another origin.
                    val allowed = configuredOrigin()
                    if (allowed != null && !VitroCardContext.sameOrigin(allowed, origin)) {
                        if (withheldLogged.compareAndSet(false, true)) {
                            Log.w(logTag, "Not sending the Home Assistant token to a dashboard on another origin than the panel's")
                        }
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
                                val changed = cachedSession != (origin to tok)
                                cachedToken = tok
                                cachedSession = origin to tok
                                // Islands whose page stays (a kept dashboard after a reconnect, or a token refreshed
                                // on a live connection: the client asks for it every 30 s) get it now. The same token
                                // again (the provider's cached one) tells nobody.
                                if (changed) tokenWatchers.forEach { runCatching { it() } }
                            }
                            done(tok)
                        }
                    }
                },
                store = store,
                connect = socketFactory,
                listener = WsListener(generation),
                keepAliveMs = keepAliveMs,
                configRetryMs = configRetryMs,
            )
            client = wsClient
            // Islands holding the registries keep them, now from this Home Assistant.
            if (registryWatchers.isNotEmpty()) attachRegistries()
            wsClient.start(dashPath)
        }

        /**
         * Reload dashboard (the panel menu, Manage, Home Assistant's button). On a fallback page it's the way back to
         * the native dashboard view the page last showed (else the one the panel is set to), in place of that page in
         * the history: Back from there doesn't return to it. Only with no native dashboard at all does the fallback
         * page reload itself.
         */
        override fun reload() {
            // Asked for: a dashboard whose config failed to read is tried again.
            failedDashboards.clear()
            val activeFallback = fallbackPage
            if (activeFallback != null && fallbackInFront()) {
                val target = returnTarget()
                if (target == null) {
                    activeFallback.reload()
                    return
                }
                currentUrl = null
                // Back from the dashboard goes where the page was before it, not to the dashboard again.
                if (history.lastOrNull() == target) history.removeLast()
                load(target)
                return
            }
            // Asked for: the dashboard is built again even if its config is the same.
            rebuildNextConfig = true
            client?.fetchDashboard(currentDashboardPath) ?: currentUrl?.let(::load)
        }

        /** The next config is built even when it's the one on screen (a reload the user asked for). */
        private var rebuildNextConfig = false

        override val canGoBack: Boolean
            get() = history.isNotEmpty() || (fallbackInFront() && (fallbackPage?.canGoBack == true || returnTarget() != null))

        override fun goBack() {
            val activeFallback = fallbackPage
            if (activeFallback != null && fallbackInFront()) {
                // Home Assistant's page first, within its own history (the fallback starts over for each page Vitro
                // sends it, so that history is this visit's alone). Then where the page came from: Vitro's history,
                // or the native dashboard when there is none (the panel started on that page).
                if (activeFallback.canGoBack) {
                    activeFallback.goBack()
                    return
                }
                if (!backInHistory()) returnTarget()?.let { target ->
                    currentUrl = null // the page the panel started on isn't a step to come back to
                    load(target)
                }
                return
            }
            if (vitroView.closeMoreInfo()) return
            // Every view change (load or tab) is in [history], so it alone says where back goes. The view's own back
            // stack mirrors it, and replaying that after the history would revisit views already gone back through.
            backInHistory()
        }

        /** The native dashboard a fallback page returns to with no history to go back through. */
        private fun returnTarget(): String? = lastNativeUrl?.takeUnless(::isWebDashboard) ?: configuredDashboard()

        /** Loads the previous entry of the page's history; false when there is none. */
        private fun backInHistory(): Boolean {
            if (history.isEmpty()) return false
            // Back leaves a fallback dialog's visit: a later return to that view (a sidebar tap) isn't the dialog closing.
            moreInfoReturn = null
            val prev = history.removeLast()
            currentUrl = null // don't push onto history again
            load(prev)
            return true
        }

        override fun restart() {
            fallbackPage?.let {
                it.restart()
                fallbackFresh = true
            }
            // The island server stays up: a locked-down fallback allows its port, and only it, for the process.
            retireClient()
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

        /**
         * Opens [path] (a card's `navigate` or `url` action) on this page. [replace]: HA's `navigate(path, {replace:
         * true})` (`navigation_replace`), so the page it leaves is no step in the history and back skips it.
         */
        private fun navigateInPlace(path: String, replace: Boolean = false) {
            // Anything else is a path on Home Assistant, its query and fragment kept. One with another scheme stays a
            // path there (`/javascript:…`), as before: it never runs in the page.
            val url = if (path.startsWith("http://") || path.startsWith("https://")) {
                path
            } else {
                FrontendUrls.pathOn(currentOrigin, path) ?: return
            }
            if (replace) currentUrl = null // the page left isn't a step to come back to
            load(url)
        }

        /**
         * The page left the native dashboards for the fallback: a dashboard answer still on its way no longer owns
         * the page (it would render, report its location and signal ready behind the fallback).
         */
        private fun leaveNativeDashboard() {
            stopClarity()
            nativeActive = false
            readyOnBuild = null
            currentDashboardPath = NO_DASHBOARD
            client?.releaseDashboard()
        }

        /**
         * The dashboard at [urlPath] has no native views any more (a live refresh made it a strategy, or HA has no
         * config for it): if it's the one on screen, its old views are stale, and a later load fetches it again
         * instead of switching to them. A view-level handoff ([VitroView.onWebView]) keeps them: the config is current.
         */
        private fun forgetDisplayed(urlPath: String?) {
            if (displayedDashboard == (currentOrigin to urlPath)) displayedDashboard = null
        }

        private fun showVitroLayer() {
            val left = fallbackInFront()
            vitroView.visibility = View.VISIBLE
            showIslandSurfaces()
            vitroView.dashboardShown = true
            fallbackPage?.let { fp ->
                fp.view.visibility = View.GONE
                // Home Assistant's frontend doesn't keep running behind the native view: the page starts over, empty.
                if (left) {
                    fp.restart()
                    fallbackFresh = true
                }
            }
        }

        /** The islands' surfaces [hideIslandSurfaces] hid, to show again with the dashboard. */
        private val hiddenSurfaces = ArrayList<SurfaceView>()

        /**
         * Hides the surfaces under [vitroView] (the islands' GeckoViews) while the fallback shows a page. Android 6 hides
         * a SurfaceView only when it, or its window, goes invisible, not an ancestor (API 24's onVisibilityAggregated
         * does): on the legacy engine an island's card stayed drawn over Home Assistant's pages.
         */
        private fun hideIslandSurfaces(root: View = vitroView) {
            fun walk(v: View) {
                if (v is SurfaceView) {
                    if (v.visibility == View.VISIBLE) { v.visibility = View.INVISIBLE; hiddenSurfaces += v }
                } else if (v is ViewGroup) {
                    for (i in 0 until v.childCount) walk(v.getChildAt(i))
                }
            }
            walk(root)
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
            fallbackLocation = url
            val fp = fallbackPage ?: fallback.open(activity, fallbackHost, backgroundColor).also { created ->
                fallbackPage = created
                container.addView(created.view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            }
            // A More-Info sheet doesn't wait behind Home Assistant's page to show again over the dashboard.
            vitroView.closeMoreInfo()
            vitroView.visibility = View.GONE
            // The islands stop painting under the fallback's page, and Android 6 stops showing their surfaces.
            vitroView.dashboardShown = false
            hideIslandSurfaces()
            fp.view.visibility = View.VISIBLE
            // Each page Vitro sends the fallback starts with no history: Back goes through this page's own, then Vitro's.
            if (!fallbackFresh) fp.restart()
            fallbackFresh = false
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
                // The islands' hass.connection is ready again: their subscriptions start over on this connection.
                connectionIs(true, generation)
                post {
                    // A new connection tries again the dashboards whose config failed to read on the last one. The one
                    // HA's frontend shows for that reason now goes back to the native view; other fallback pages stay.
                    // Where HA's page is now: the user may have moved on from the failed dashboard inside it.
                    // Not while HA's editor or a dialog is open in it: a reload would lose what the user was doing.
                    val retry = (fallbackLocation ?: currentUrl)?.takeIf { url ->
                        fallbackInFront() && (OAuth.origin(url) to LovelaceParser.parseRoute(url).first) in failedDashboards &&
                            !inFrontendWorkflow(runCatching { java.net.URI(url).rawQuery }.getOrNull().orEmpty())
                    }
                    failedDashboards.clear()
                    retry?.let(::load)
                    vitroView.setConnectionBanner(null)
                    vitroView.connectionRestored()
                }
            }

            override fun onAuthRequired() {
                Log.i(logTag, "Home Assistant requires authentication; handing off to sign-in flow")
                val base = currentOrigin ?: return
                post {
                    // That client's reader has stopped: the load after sign-in starts a new one instead of reusing it.
                    // Images fetched with the rejected sign-in go too.
                    VitroCardContext.clearImageCache()
                    retireClient()
                    // Nor does the page keep that session's registries for its islands: the next client's feed brings them.
                    detachRegistries()
                    client?.close()
                    client = null
                    host.onLocationChange("$base/auth/authorize")
                }
            }

            override fun onDashboards(dashboards: List<DashboardSummary>) {
                // The dashboards HA's frontend lists for this user, as the web engine reports them: the client left out
                // an admin-only one for anyone but an administrator.
                val arr = JSONArray()
                for (d in dashboards) arr.put(JSONObject().put("url_path", d.urlPath).put("title", d.title))
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

            // The islands' hass.user follows it as it arrives, which may be after the token (and after their connect).
            override fun onCurrentUser(user: JSONObject) {
                if (generation == clientGeneration) tokenWatchers.forEach { runCatching { it() } }
                // A view's `visible` follows the user: which tabs show.
                if (generation == clientGeneration) post { vitroView.userChanged() }
            }

            // The islands' hass.config follows it: a page made before it arrived has none.
            override fun onConfig(config: JSONObject) {
                if (generation == clientGeneration) configWatchers.forEach { runCatching { it(config) } }
            }

            override fun onUserTheme(themeName: String?, dark: Boolean?) {
                post { vitroView.setUserTheme(themeName, dark) }
            }

            override fun onLocaleChanged() {
                post { vitroView.setLocale(HaLocale.current, HaLocale.timeZone().id, HaText.version) }
            }

            override fun onDashboardConfig(urlPath: String?, config: DashboardConfig) {
                // Route the cards off the UI thread while the config waits for it to build the view.
                CompatWarmup.start(config, pendingViewPath)
                post {
                    // An older request's answer for a dashboard the page has left must not replace the current one.
                    if (urlPath != currentDashboardPath) return@post
                    // A strategy dashboard has no views to draw: HA's frontend generates them, as it does for a
                    // dashboard with no saved config.
                    WebFallback.dashboardReason(config)?.let { reason ->
                        Log.i(logTag, "Vitro doesn't draw $reason; Home Assistant's frontend shows it")
                        val url = currentUrl
                        // Like a dashboard with no config: HA's frontend draws it, so it's no way back to Vitro.
                        webDashboards += currentOrigin to urlPath
                        leaveNativeDashboard()
                        forgetDisplayed(urlPath)
                        url?.let(::openInFallback)
                        return@post
                    }
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
                        // Only while the native view owns the page (a view HA's frontend draws signalled ready itself).
                        if (nativeActive) readyWhenBuilt("vitro")
                        return@post
                    }
                    displayedDashboard = currentOrigin to urlPath
                    // It has a config now (made in HA since it failed): Vitro's to draw again.
                    webDashboards -= currentOrigin to urlPath
                    failedDashboards -= currentOrigin to urlPath
                    // Shown natively: a return target now (onViewSelected then narrows it to the view on screen).
                    val previousNative = lastNativeUrl
                    currentUrl?.let { lastNativeUrl = it }
                    // A new config builds new islands; they ask again for what they read.
                    runtimeEntities.clear()
                    walkedViews.clear()
                    walkGeneration++
                    vitroView.setConnectionBanner(null)
                    vitroView.setDashboard(config, pendingViewPath)
                    client?.dashboardShown()
                    // The view it opens may have gone to the fallback ([VitroView.onWebView]), which signals ready; then
                    // it's no return target, and the native view before it stays the way back.
                    if (nativeActive) readyWhenBuilt("vitro") else lastNativeUrl = previousNative
                }
            }

            override fun onDashboardRefused(urlPath: String?) {
                Log.i(logTag, "Dashboard ${urlPath ?: "(default)"} is admin only and this user isn't an administrator: Home Assistant's frontend decides what it shows")
                post {
                    if (urlPath != currentDashboardPath) return@post
                    // HA's frontend has no panel for it for this user, and routes the URL to the user's default panel
                    // (their profile's, the system's, or Home). The fallback shows exactly that, and hands the page back
                    // if it's a dashboard Vitro draws. It's no way back to Vitro, as a dashboard with no config isn't.
                    val url = currentUrl
                    webDashboards += currentOrigin to urlPath
                    leaveNativeDashboard()
                    forgetDisplayed(urlPath)
                    url?.let(::openInFallback)
                }
            }

            override fun onDashboardConfigFailed(urlPath: String?, code: String) {
                Log.w(logTag, "Home Assistant didn't give the dashboard config for ${urlPath ?: "the default dashboard"}: $code")
                post {
                    if (urlPath != currentDashboardPath) return@post
                    // Only an answer from Home Assistant: a dropped connection reconnects and asks again.
                    if (code == HaDashboardClient.DISCONNECTED || code == HaDashboardClient.SEND_FAILED) return@post
                    // HA's own frontend decides what such a dashboard shows: it generates one HA has no config for
                    // (`config_not_found`), and shows its error for any other failure (the client has retried those).
                    // The fallback owns the page now: a reconnect or lovelace_updated doesn't fetch this dashboard again.
                    // A dashboard with no config stays HA's for the session; one that failed is Vitro's again on the
                    // next connection or Reload dashboard.
                    val url = currentUrl
                    if (code == HaDashboardClient.CONFIG_NOT_FOUND) webDashboards += currentOrigin to urlPath
                    else failedDashboards += currentOrigin to urlPath
                    leaveNativeDashboard()
                    forgetDisplayed(urlPath)
                    url?.let(::openInFallback)
                }
            }

            override fun onDisconnected(reason: String) {
                Log.w(logTag, "HA WebSocket disconnected: ${forLog(reason)}")
                connectionIs(false, generation)
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

        /** The preferences that keep the island server's port, and its key ([islandPort]). */
        private const val ISLAND_PREFS = HaPageData.ISLAND_PREFS // the factory reset clears it
        private const val ISLAND_PORT = "port"

        /** How long a view's first build waits for its first states. */
        private const val FIRST_STATES_WAIT_MS = 1_000L

        /**
         * How long a first build waits for the Lovelace resources: as long as Bubble Card's modules check may take
         * ([dev.glassworks.engine.vitro.data.BubbleModules.probe]'s own timeouts), after two WebSocket round trips.
         */
        private const val FIRST_RESOURCES_WAIT_MS = 6_000L

        /** Entities islands may add to the entity subscription at runtime, and how long their lookups are batched. */
        private const val MAX_RUNTIME_ENTITIES = 256

        /**
         * Above this many entities, a dashboard's views each follow their own ([dashboardEntities]). Measured on the
         * JVM (DashboardStoreMeasure): 0.8 to 1 KB of heap per entity in the store, and 0.15 to 0.45 KB per entity in
         * HA's snapshot, so 2,000 entities hold about 2 MB and parse a snapshot of up to about 0.9 MB, once per
         * connection. Clarity's heavy dashboard references 363 (all seven views), its heavy view alone 359. Past a
         * few thousand, a dashboard lists a whole house (an auto-generated Overview, entity lists), and one view's own
         * snapshot at each switch costs a panel with little RAM less than holding every state.
         */
        internal const val MAX_DASHBOARD_ENTITIES = 2_000
        private const val RUNTIME_RESUBSCRIBE_MS = 150L

        /** The dashboard path while the fallback shows a non-dashboard page: no real path equals it. */
        private const val NO_DASHBOARD = "\u0000fallback"

        private val LOG_QUERY = Regex("""[?#][^\s"'<>]*""")

        /** The island server's capability, a path segment of 32 hex digits (`VitroIslandServer.capPath`). */
        private val LOG_CAPABILITY = Regex("""/[0-9a-fA-F]{32}(?![0-9a-zA-Z])""")
        private val LOG_PROXIED = Regex("""/(api|local|hacsfiles|static|auth|__vitro/island|__vitro/ws)/[^\s?#"'<>]*""")

        private val LOG_AUTHORITY = Regex("""\b([a-z][a-z0-9+.-]*://)[^/\s?#"'<>]+""", RegexOption.IGNORE_CASE)
        private val LOG_IPV4 = Regex("""(?<![\d.])\d{1,3}(?:\.\d{1,3}){3}(?::\d+)?(?![\d.])""")
        private val LOG_IPV6 = Regex("""(?<![0-9a-z:])(?:[0-9a-f]{0,4}:){2,7}[0-9a-f]{0,4}(?![0-9a-z:])""", RegexOption.IGNORE_CASE)
        private val LOG_HOST = Regex("""(?<![\w.-])(?:[a-z0-9-]+\.)+[a-z][a-z0-9-]*(?::\d+)?(?![\w.-])""", RegexOption.IGNORE_CASE)

        /**
         * [text] as it may go to logcat: no query or fragment (an OAuth `state`, a camera token), not the island
         * proxy's capability, no path below the proxied prefixes (entity ids, slot ids, resource names), and no Home
         * Assistant address (an origin, a host name or an IP a connection error names).
         */
        internal fun forLog(text: String): String =
            text.replace(LOG_QUERY, "?…").replace(LOG_CAPABILITY, "/…").replace(LOG_PROXIED) { "/${it.groupValues[1]}/…" }
                .replace(LOG_AUTHORITY) { "${it.groupValues[1]}…" }
                .replace(LOG_IPV4, "…").replace(LOG_IPV6, "…").replace(LOG_HOST, "…")

        /** Back steps a page keeps. */
        private const val MAX_HISTORY = 50

        /** [a] and [b] are the same page (scheme, host, port and path), whatever their queries. */
        internal fun samePage(a: String, b: String): Boolean {
            fun key(u: String) = u.substringBefore('#').substringBefore('?').trimEnd('/')
            return key(a) == key(b)
        }

        internal fun buildViewUrl(origin: String?, dashPath: String?, viewPath: String): String? {
            if (origin.isNullOrBlank()) return null
            val dash = dashPath?.takeIf { it.isNotBlank() } ?: "lovelace"
            return "${origin.trimEnd('/')}/$dash/$viewPath"
        }
    }
}
