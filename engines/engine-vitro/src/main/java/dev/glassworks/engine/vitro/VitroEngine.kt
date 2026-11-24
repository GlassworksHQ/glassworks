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
import android.view.View
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
     * port once). It reads the active page's Home Assistant origin and token on every request. The legacy engine
     * (a locked-down [fallback]) is held to one origin, so [fallbackDashboardOrigin] gives it this one up front.
     */
    val islandServer: VitroIslandServer = VitroIslandServer(
        session = { activePage?.islandSession() ?: (null to null) },
        log = { msg -> Log.d(logTag, "island: $msg") },
    )

    /**
     * The origin a locked-down [fallback] is told its dashboard is (`EngineSettings.dashboard`): the island server's
     * loopback origin, started now. Pages the fallback shows come through it and are proxied to Home Assistant.
     */
    fun fallbackDashboardOrigin(): String = islandServer.ensureStarted()
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

    private inner class VitroPage(
        private val activity: Activity,
        private val host: PageHost,
        private val backgroundColor: Int,
    ) : Page {
        private val main = Handler(Looper.getMainLooper())
        private val container = FrameLayout(activity).apply { setBackgroundColor(backgroundColor) }
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
            override fun onContentGone(killed: Boolean) {}
            override fun onAndroidPermissions(permissions: List<String>, answer: (Boolean) -> Unit) =
                host.onAndroidPermissions(permissions, answer)
            override fun onConnect(port: PagePort) {}
            override fun onPortMessage(port: PagePort, message: JSONObject) {}
        }
        private var currentUrl: String? = null
        @Volatile var currentOrigin: String? = null
        private var currentDashboardPath: String? = null

        /** The origin and dashboard path of the config [vitroView] shows, set when it arrives. */
        private var displayedDashboard: Pair<String?, String?>? = null

        /** Bumped for every new client: callbacks of a client that was replaced or closed are dropped. */
        private var clientGeneration = 0
        private var disposed = false

        /** The Home Assistant origin the app is set to (the settings' dashboard), the only one its token is for. */
        private fun configuredOrigin(): String? = settings(activity.applicationContext).dashboard?.let(OAuth::origin)

        /** Where the island server proxies to: the loaded origin, or before any load the configured one (sign-in first). */
        val proxyOrigin: String? get() = currentOrigin ?: configuredOrigin()

        private val lifecycle = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityDestroyed(a: Activity) { if (a === activity) dispose() }
            override fun onActivityCreated(a: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, outState: Bundle) {}
        }

        /** The activity is gone: close the Home Assistant connection, so its reader thread no longer holds this page. */
        fun dispose() {
            if (disposed) return
            disposed = true
            activity.application.unregisterActivityLifecycleCallbacks(lifecycle)
            clientGeneration++
            client?.close()
            client = null
            if (activePage === this) activePage = null
        }
        private var pendingViewPath: String? = null
        @Volatile var cachedToken: String? = null

        /** The token with the origin it was issued for, published as one value. */
        @Volatile private var cachedSession: Pair<String, String>? = null

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
            vitroView.onMetrics = { m -> lastMetrics = m }
            vitroView.onViewSelected = { v ->
                // An empty filter subscribes to every entity, which auto-entities' runtime filters need.
                val refs = if (CardRouter.followsAllEntities(v)) emptySet() else CardRouter.referencedEntities(v)
                client?.subscribeViewEntities(refs)
                // Only the dashboard that owns the route reports a location: one still on screen while another loads
                // (a theme rebuild) would pair its view with the other's path.
                // Origin and path: before the new dashboard is on screen (displayedDashboard null) nothing is reported,
                // not even for the default dashboard, whose path is null too.
                val owns = displayedDashboard != null && displayedDashboard == (currentOrigin to currentDashboardPath)
                val path = if (owns) buildViewUrl(currentOrigin, currentDashboardPath, v.path ?: v.index.toString()) else null
                if (path != null && path != currentUrl) {
                    currentUrl = path
                    host.onLocationChange(path)
                }
            }
            vitroView.onThemeResolved = { theme -> reportThemeToHost(theme) }
            vitroView.contextFactory = { theme ->
                val haBase = currentOrigin.orEmpty()
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
                    callWs = { msg, done ->
                        val c = client
                        if (c != null) c.callWs(msg, done) else done(false, null, null)
                    },
                    navigate = { path -> navigateInPlace(path) },
                    openMoreInfo = { entityId -> vitroView.openMoreInfo(entityId) },
                    fetchHistory = { ids, hours, done -> client?.fetchHistory(ids, hours, done) },
                    fetchForecast = { id, kind, done -> client?.fetchForecast(id, kind, done) },
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
                )
            }
            main.post { host.onConnect(port) }
        }

        override fun load(url: String) {
            val redirected = host.redirect(url)
            if (redirected != null) {
                if (redirected == "about:blank") return
                load(redirected)
                return
            }
            currentUrl?.let { if (it != url) history.addLast(it) }
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
                signalReady("view")
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
                        Log.w(logTag, "Not sending the Home Assistant token to $origin: the panel is set to $allowed")
                        done(null)
                    } else {
                        tokenProvider(force) { tok ->
                            // Checked again on delivery: the panel's Home Assistant may have changed while the token
                            // was on its way, and then this token is the next one's, not this client's.
                            val stillAllowed = configuredOrigin().let { it == null || VitroCardContext.sameOrigin(it, origin) }
                            if (!stillAllowed) {
                                Log.w(logTag, "Dropping a token that arrived for $origin after the panel moved to another Home Assistant")
                                done(null)
                                return@tokenProvider
                            }
                            if (tok != null && generation == clientGeneration) {
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
            wsClient.start(dashPath)
        }

        override fun reload() {
            val activeFallback = fallbackPage
            if (activeFallback != null && activeFallback.view.visibility == View.VISIBLE) {
                activeFallback.reload()
                return
            }
            client?.fetchDashboard(currentDashboardPath) ?: currentUrl?.let(::load)
        }

        override fun goBack() {
            if (vitroView.closeMoreInfo()) return
            val activeFallback = fallbackPage
            if (activeFallback != null && activeFallback.view.visibility == View.VISIBLE) {
                activeFallback.goBack()
                return
            }
            if (history.isNotEmpty()) {
                val prev = history.removeLast()
                currentUrl = null // don't push onto history again
                load(prev)
                return
            }
            vitroView.goBack()
        }

        override fun restart() {
            fallbackPage?.restart()
            // The island server stays up: its port is the locked-down fallback's one allowed origin for the process.
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
            currentDashboardPath = NO_DASHBOARD
            client?.releaseDashboard()
        }

        private fun showVitroLayer() {
            vitroView.visibility = View.VISIBLE
            fallbackPage?.view?.visibility = View.GONE
        }

        private fun openInFallback(url: String) {
            val target = loopbackUrl(url)
            Log.i(logTag, "Routing non-dashboard URL to fallback engine: $target")
            val fp = fallbackPage ?: fallback.open(activity, host, backgroundColor).also { created ->
                fallbackPage = created
                container.addView(created.view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            }
            vitroView.visibility = View.GONE
            fp.view.visibility = View.VISIBLE
            fp.load(target)
        }

        /**
         * A locked-down fallback loads only one origin, the island server's. A Home Assistant URL (`/auth/authorize`)
         * moves to it, so the server proxies it to [currentOrigin], and to nothing else.
         */
        private fun loopbackUrl(url: String): String {
            val ha = proxyOrigin ?: return url
            if (!fallback.lockedDown || OAuth.origin(url) != ha) return url
            val afterScheme = url.trim().substringAfter("://")
            val rest = afterScheme.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) "" else afterScheme.substring(it) }
            return islandServer.ensureStarted() + (if (rest.startsWith("/")) rest else "/$rest")
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
                post {
                    // An older request's answer for a dashboard the page has left must not replace the current one.
                    if (urlPath != currentDashboardPath) return@post
                    displayedDashboard = currentOrigin to urlPath
                    vitroView.setConnectionBanner(null)
                    vitroView.setDashboard(config, pendingViewPath)
                    client?.dashboardShown()
                    signalReady("vitro")
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
                Log.w(logTag, "HA WebSocket disconnected: $reason")
                post {
                    if (firstPaintReported) {
                        vitroView.setConnectionBanner("Connection lost. Reconnecting…")
                    } else {
                        host.onPageStop(false)
                    }
                }
            }
        }
    }

    companion object {
        const val VERSION_NAME = "Vitro 0.0.1 (experimental)"

        /** The dashboard path while the fallback shows a non-dashboard page: no real path equals it. */
        private const val NO_DASHBOARD = "\u0000fallback"

        internal fun buildViewUrl(origin: String?, dashPath: String?, viewPath: String): String? {
            if (origin.isNullOrBlank()) return null
            val dash = dashPath?.takeIf { it.isNotBlank() } ?: "lovelace"
            return "${origin.trimEnd('/')}/$dash/$viewPath"
        }
    }
}
