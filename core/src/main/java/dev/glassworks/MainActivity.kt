package dev.glassworks

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.net.Uri
import android.widget.Toast
import dev.glassworks.engine.Page
import dev.glassworks.engine.PageHost
import dev.glassworks.engine.PagePort
import dev.glassworks.engine.TrustedPages
import dev.glassworks.pane.applyFrom
import dev.glassworks.pane.intentExtras
import dev.glassworks.pane.permissionsToAsk
import dev.glassworks.platform.Platform
import dev.glassworks.platform.HotspotAddress
import dev.glassworks.platform.HotspotFailure
import dev.glassworks.setup.HotspotPermission
import dev.glassworks.setup.HotspotStep
import dev.glassworks.setup.LanSetup
import dev.glassworks.setup.SetupHotspot
import dev.glassworks.auth.FrontendUrls
import dev.glassworks.auth.HaAuth
import dev.glassworks.auth.HaTokenClient
import dev.glassworks.auth.PanelSignIn
import dev.glassworks.auth.UrlConnectionPoster
import dev.glassworks.frame.Input
import dev.glassworks.frame.Orientation
import dev.glassworks.frame.openSystemSettings
import dev.glassworks.dashboard.DashboardOptions
import dev.glassworks.dashboard.Dashboards
import dev.glassworks.settings.KioskSettingsOverlay
import dev.glassworks.settings.PanelBlueprints
import dev.glassworks.settings.S1Settings
import dev.glassworks.settings.SettingsMenu
import dev.glassworks.setup.Backdrop
import dev.glassworks.setup.LoadingOverlay
import dev.glassworks.setup.OfflineText
import dev.glassworks.setup.holdingPanes
import dev.glassworks.setup.PaneStepFacts
import dev.glassworks.setup.SetupFlow
import dev.glassworks.setup.SetupInputs
import dev.glassworks.setup.SetupOverlay
import dev.glassworks.setup.SetupSignals
import dev.glassworks.setup.SetupState
import dev.glassworks.setup.WifiSetupStatus
import dev.glassworks.setup.WifiStep
import dev.glassworks.provisioning.ImprovController
import dev.glassworks.provisioning.ImprovWifiStatus
import dev.glassworks.web.LocalName
import dev.glassworks.web.LocalRoute
import org.json.JSONObject
import kotlin.system.exitProcess

open class MainActivity : Activity() {

    /** The app's runtime, for its parts: the panes' layers over the dashboard ([AppParts.overlays]). */
    private val runtime get() = glassworksRuntime
    private lateinit var prefs: KioskPrefs
    private lateinit var loadingOverlay: LoadingOverlay
    private lateinit var settingsOverlay: KioskSettingsOverlay
    /** The panes' layers over the dashboard, bottom first ([AppParts.overlays]). */
    private var overlays: List<PaneOverlay> = emptyList()
    private lateinit var threadJoinOverlay: dev.glassworks.setup.ThreadJoinOverlay
    private lateinit var setupOverlay: SetupOverlay
    private lateinit var wifiStatus: WifiSetupStatus
    private lateinit var root: android.widget.FrameLayout

    /**
     * Black over everything while the screen sleeps with its backlight off (ScreenPolicy.blackout, a frame's
     * SleepMode.BACKLIGHT_OFF). It takes the tap that wakes the screen, so that tap never reaches the page.
     */
    private val blackoutView by lazy {
        View(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            elevation = BLACKOUT_ELEVATION_DP * resources.displayMetrics.density
            visibility = View.GONE
            isClickable = true
            setOnTouchListener { _, ev ->
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) glassworks.screen.wake("touch")
                true
            }
        }
    }
    private var page: Page? = null
    private val handler = Handler(Looper.getMainLooper())
    private var pendingAndroidPermission: ((Boolean) -> Unit)? = null
    private var currentUrl: String? = null

    /** The panes' definitions: their setup steps, settings sections and adb extras. */
    private val panes by lazy { glassworks.paneDefinitions(this) }
    /** The panes on setup's Panes step, each with its steps (only usable ones). */
    private val setupPanes by lazy { glassworks.panesStep(this).panes }
    private val paneSteps by lazy { setupPanes.flatMap { it.steps } }
    /** The device's own steps, after Panes (#422's Silent updates). */
    private val deviceSteps by lazy { glassworks.deviceSetupSteps(this) }

    /** "Sign in on this panel instead" was chosen: don't cover HA's login page again (this process). */
    private var usePanelLogin = false

    /** The setup screen hid for that login page: it comes back once that sign-in ends (#665). */
    private var setupHidForLogin = false

    /** That sign-in: Home Assistant's login page on the panel, for a refresh token of the panel's own (#633). */
    private val panelSignIn by lazy { PanelSignIn(HaTokenClient(UrlConnectionPoster())) }

    /** Signed out on this panel (HA button / frontend logout) and not signed in again (this process). */
    private var signedOutHere = false

    /** Setup screen preview (`--ez show_setup true`, `--es setup_step …`): never changes state. */
    private var setupPreview = false
    private var setupPreviewStep: String? = null
    private var offlineSince = 0L
    private var lastSetupReasons: List<String> = emptyList()
    private var lastSetupStep: String? = null
    /**
     * The setup screen was up when the app restarted itself, or when Android recreated this activity: it counts as up
     * until this activity shows it (#621's Mirror run).
     */
    private var setupCarried = false
    /** This process is the app's own restart with the setup screen up: Sign in holds until the page answers (SetupState). */
    private var restartedInSetup = false

    private var lastImprovInline = false

    /** Improv shows its Allow prompt in the setup screen's Wi-Fi step while that is visible. */
    private val improvHost = object : ImprovController.InlineHost {
        override fun showsImprovInline() = improvInline()
        override fun onImprovChanged() = refreshSetup()
        override fun onImprovIdentify() = setupOverlay.flashImprov()
    }

    private val authListener: (Boolean) -> Unit = { signedIn ->
        // A test's dashboard stays on screen: the page follows the sign-in once the test ends (onTestDashboard).
        val testing = glassworks.screen.testDashboard != null
        if (signedIn) {
            Log.i(TAG, "Signed in; loading the dashboard with external auth")
            usePanelLogin = false
            signedOutHere = false
            if (setupPreview) closeSetupPreview()
            if (!testing) loadDashboard()
        } else {
            Log.i(TAG, "Signed out; showing the sign-in screen")
            usePanelLogin = false
            signedOutHere = true
            if (!testing) prefs.url.takeIf { it.isNotEmpty() }?.let { page?.load(it) }
        }
        refreshSetup()
    }

    private val setupSignalListener: () -> Unit = { refreshSetup() }

    /**
     * "Join Home Assistant's Thread network" (auth/ThreadJoin), from the settings menu, the setup
     * screen or Home Assistant's button: the QR code shows whenever an administrator's sign-in is
     * needed; how it ends shows only if the overlay is already up.
     */
    private val threadJoinListener: (dev.glassworks.auth.ThreadJoin.State) -> Unit = { s ->
        handler.post { if (!isDestroyed) showThreadJoin(s) }
    }

    private fun showThreadJoin(s: dev.glassworks.auth.ThreadJoin.State) {
        val url = dev.glassworks.auth.ThreadJoin.url(dev.glassworks.platform.PanelAddress.now(this))
        when {
            s is dev.glassworks.auth.ThreadJoin.State.NeedsAdmin -> {
                if (settingsOverlay.isShowing) settingsOverlay.hide()
                threadJoinOverlay.render(s, url)
            }
            s is dev.glassworks.auth.ThreadJoin.State.Idle -> threadJoinOverlay.hide()
            // How it ended, also when the panel's own sign-in did it (no QR code was up).
            s is dev.glassworks.auth.ThreadJoin.State.Joined || s is dev.glassworks.auth.ThreadJoin.State.Failed -> {
                if (settingsOverlay.isShowing) settingsOverlay.hide()
                threadJoinOverlay.render(s, url)
            }
            threadJoinOverlay.isShowing -> threadJoinOverlay.render(s, url)
        }
    }

    private val setupTick = object : Runnable {
        override fun run() {
            refreshSetup()
            setupOverlay.tick() // the panes' steps follow their state
            if (resumed) requestRuntimePermissions() // a pane turned on since (from Home Assistant) asks once
            refreshOffline()
            // #449: a frame with no presence sensor has nothing to wake it, so it stays on through setup and the
            // connecting screen. The S1's sensor wakes it as before. Setup, which covers the connecting screen, only until
            // Home Assistant has added the panel: a set-up panel that lost Wi-Fi sleeps on its idle timeout (#484).
            glassworks.screen.stayOn(NOT_READY, !presenceSensor && if (setupOverlay.isShowing) setupOverlay.needsHomeAssistant else loadingOverlay.isShowing)
            // Without touch nothing on the panel can wake its screen during setup, and the person reads it from a
            // computer's steps: it stays on for as long as the setup screen is up (a Mirror run, 2026-11-20).
            glassworks.screen.stayOn(SETUP_NO_TOUCH, setupOverlay.input == Input.NONE && setupOverlay.isShowing && !setupPreview)
            handler.postDelayed(this, SETUP_TICK_MS)
        }
    }

    private val presenceSensor by lazy { glassworks.presence.ownSensor(this) }

    // Hidden settings gesture: 5 taps in the top-left corner within 3 s.
    private var cornerTaps = 0
    private var firstCornerTapAt = 0L

    private val retryLoad = Runnable {
        Log.i(TAG, "Retrying dashboard load")
        loadDashboard()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The scroll path's knobs (debug.glassworks.scroll.*): read once, now, and logged.
        dev.glassworks.engine.ScrollTuning.init()
        prefs = KioskPrefs(this)
        val restarted = prefs.takeSetupUpAtRestart()
        if (restarted) Log.i(TAG, "Setup screen: up again after the app's restart")
        // Recreated (a new language, `set_locale`): the first activity read the restart's mark, so it carries on here.
        // Only in the same process: one Android killed and restored later isn't the app's own restart.
        val recreated = savedInstanceState?.takeIf { it.getInt(STATE_PID) == android.os.Process.myPid() }
        setupCarried = restarted || recreated?.getBoolean(STATE_SETUP_UP) == true
        restartedInSetup = restarted || recreated?.getBoolean(STATE_RESTARTED_IN_SETUP) == true
        glassworks.paneDefinitions(this) // first: a step's one-time check runs before discovery can save an HA URL
        HaAuth.init(this)
        intent?.let { screenExtras(it, runtime.app::trustsExtras) }
        applyIntent(intent, fromNewIntent = false)
        // VitroSentinel turned Vitro off after it crashed the app at start: the panel says so once (Labs keeps why).
        if (prefs.takeVitroOffNotice()) {
            Log.w(TAG, "Vitro: turned off after the app crashed at start; this start uses the browser engine")
            Toast.makeText(this, dev.glassworks.settings.CoreSettings.VITRO_OFF_NOTICE, Toast.LENGTH_LONG).show()
        }
        // The frame's orientation. The S1's manifest already asks for landscape: no change there.
        val orientation = glassworks.frame.orientation
        screenOrientation(orientation).let { if (requestedOrientation != it) requestedOrientation = it }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 8 bits per channel: the dithered backdrop/voice glows would band again on an RGB565 surface.
        window.setFormat(PixelFormat.RGBA_8888)
        if (Platform.hasDisplayCutoutMode) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        // Bake the dithered backdrop now, before the first frame (the overlays fill this full-screen window, in the
        // frame's orientation); until it is ready the loading screen draws 8-bit gradients.
        val real = android.graphics.Point().also { @Suppress("DEPRECATION") windowManager.defaultDisplay.getRealSize(it) }
        val (long, short) = maxOf(real.x, real.y) to minOf(real.x, real.y)
        when (orientation) {
            Orientation.LANDSCAPE -> Backdrop.prewarm(long, short)
            Orientation.PORTRAIT, Orientation.PORTRAIT_FIXED -> Backdrop.prewarm(short, long)
            Orientation.ANY -> Backdrop.prewarm(real.x, real.y)
        }
        loadingOverlay = LoadingOverlay(this, glassworks.frame.bootRest).apply { onAction = ::openWifiSettings }
        overlays = runtime.app.overlays(runtime, overlayHost)
        setupOverlay = SetupOverlay(this, glassworks.frame, setupPanes, deviceSteps, glassworks.panesStep(this)).apply {
            onUsePanel = ::onUsePanelLogin
            onManualUrl = ::onManualHaUrl
            onChooseNetwork = { if (!setupPreview) glassworks.setupHotspot?.configured(); openWifiSettings() }
            onSkipHa = ::onSkipHaSetup
            onClosePreview = ::closeSetupPreview
            onImprovAction = ::onImprovAction
            onHotspotAction = ::onHotspotAction
            onPanesDone = { if (!setupPreview) refreshSetup() }
            onStepDone = { refreshSetup() }
        }
        threadJoinOverlay = dev.glassworks.setup.ThreadJoinOverlay(this).apply {
            onClose = { HaAuth.threadJoin.disarm(); hide() }
        }
        settingsOverlay = KioskSettingsOverlay(this).apply { onRestart = ::restartProcess }
        // Improv-aware from the start: KioskService (which registers the provider) starts after this.
        wifiStatus = ImprovWifiStatus(this)
        root = android.widget.FrameLayout(this)
        setContentView(root.apply {
            // startSession adds the page at index 0, under everything.
            addView(loadingOverlay, android.widget.FrameLayout.LayoutParams(-1, -1)) // under the setup screen
            addView(setupOverlay, android.widget.FrameLayout.LayoutParams(-1, -1))
            // Z order (elevation): setup 20 dp > the panes' layers (the S1's: voice 16 dp, notifications 14 dp, timers 12 dp) > page.
            overlays.filterNot { it.aboveMenus }.forEach { addView(it.view, it.layoutParams) }
            addView(settingsOverlay, android.widget.FrameLayout.LayoutParams(-1, -1)) // 24 dp
            addView(threadJoinOverlay, android.widget.FrameLayout.LayoutParams(-1, -1)) // 26 dp
            overlays.filter { it.aboveMenus }.forEach { addView(it.view, it.layoutParams) } // never covered (the camera's dot, 30 dp)
        })
        // The launch backdrop (res/drawable/launch_backdrop.xml) covers the cold start; once our own
        // views have drawn, a plain colour behind them costs the GPU nothing on every later frame.
        window.decorView.post { window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0xFF0A0B0F.toInt())) }
        // Visible from the first frame: the page saying it is drawn ("ready", content.js) or the setup screen replaces it.
        loadingOverlay.show(
            if (hasNetwork()) LoadingOverlay.State.Connecting(LoadingOverlay.CONNECTING, dashboardHost())
            else LoadingOverlay.State.Connecting(OfflineText.CONNECTING_TITLE, OfflineText.CONNECTING_DETAIL, host = false),
        )
        HaAuth.addListener(authListener)
        HaAuth.threadJoin.addListener(threadJoinListener)
        // Recreated while the QR code was up: show it again (posted, so a newer update still wins).
        (HaAuth.threadJoin.state as? dev.glassworks.auth.ThreadJoin.State.NeedsAdmin)?.let(threadJoinListener)
        SetupSignals.addListener(setupSignalListener)
        wifiStatus.observe { refreshSetup() }
        ImprovController.inlineHost = improvHost
        KioskService.start(this)
        glassworks.screen.start(this)
        glassworks.screen.onKeepScreenOn = { on ->
            if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        glassworks.screen.onBrightness = { b ->
            window.attributes = window.attributes.apply {
                screenBrightness = if (b < 0f) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else b
            }
        }
        glassworks.screen.onStateChanged = {
            pushState()
            overlays.forEach { it.screenChanged() }
        }
        glassworks.screen.onTestDashboard = { onTestDashboard() }
        glassworks.screen.onBlackout = ::showBlackout
        glassworks.commandSink = { applyIntent(it, fromNewIntent = true) }
        startSession()
        intent?.let { i -> handler.post { applySetupPreview(i) } }
        handler.post(setupTick)
        // The app's upkeep on each start (the S1's direct-boot splash).
        Thread({ runtime.app.activityStarted(applicationContext) }, "glassworks-boot-splash").start()
        hideSystemUi()
    }

    private fun startSession() {
        val p = glassworks.engine.open(this, pageHost, BACKGROUND) // dark, not white, until the page paints
        root.addView(p.view, 0, android.widget.FrameLayout.LayoutParams(-1, -1))
        page = p
        loadDashboard()
    }

    // ---- dashboard, discovery, sign-in screen (docs/onboarding.md)

    /**
     * Loads the dashboard. Signed in with the QR code → with `external_auth=1` (the frontend then
     * gets tokens from the app); otherwise as before (the GeckoView's own login session). No URL
     * yet (setting "auto", HA not found) → the "Where is Home Assistant?" screen.
     */
    private fun loadDashboard() {
        glassworks.screen.testDashboard?.let { test ->
            Log.i(TAG, "Showing the test's dashboard (its hold); the saved dashboard and the sign-in stay as they are")
            dashboardLoadAt = SystemClock.elapsedRealtime()
            page?.load(test)
            return
        }
        val url = prefs.url
        if (url.isEmpty()) {
            Log.i(TAG, "No dashboard URL yet (auto, Home Assistant not found)")
            refreshSetup()
            return
        }
        val route = prefs.dashboardRoute
        if (!prefs.isAutoUrl && route != loggedRoute) {
            loggedRoute = route
            when (route) {
                is LocalRoute.ByAddress -> Log.i(TAG, "Dashboard through ${route.address}: Android can't resolve ${route.name} before 12")
                is LocalRoute.NoAddressYet -> Log.i(TAG, "Dashboard at ${route.name} as set: Android can't resolve it before 12, and Home Assistant's address isn't known yet")
                is LocalRoute.KeptForCertificate -> Log.i(TAG, "Dashboard at ${route.name} as set, though Android can't resolve it before 12: https, and its certificate is for the name")
                is LocalRoute.AsSet -> Unit
            }
        }
        dashboardLoadAt = SystemClock.elapsedRealtime()
        page?.load(externalize(url))
    }

    private var dashboardLoadAt = 0L

    /** What the web engine should start with now: the test's dashboard while it has one (KioskPrefs.engineStart). */
    private fun engineStartWanted(): List<String> =
        KioskPrefs.engineStart(prefs.secureHosts(), glassworks.screen.testDashboard ?: prefs.url)

    /**
     * A test's dashboard began or ended (ScreenPolicy.testDashboard). The legacy engine holds pages to the origin it
     * started with (#198), so a new origin restarts the app; otherwise the page loads it now.
     */
    private fun onTestDashboard() {
        if (page == null || isFinishing || isDestroyed) return
        val started = glassworks.engine.started
        if (runtime.engine.lockedDown && engineStartWanted() != KioskPrefs.engineStart(started?.secureHosts.orEmpty(), started?.dashboard)) {
            restartProcess()
            return
        }
        loadDashboard()
        refreshSetup()
    }

    /** The last [KioskPrefs.dashboardRoute] [loadDashboard] logged: a line when it changes, not every retry. */
    private var loggedRoute: LocalRoute? = null

    // ---- notifications (#118): the overlay, a view full screen and the way back

    /** What the panes' layers get from the activity. */
    private val overlayHost = object : OverlayHost {
        override val activity get() = this@MainActivity
        override fun openInPage(url: String?) = if (url != null) this@MainActivity.openInPage(url) else backToDashboard()
        override fun placePage(area: android.graphics.Rect?) { page?.view?.let { dev.glassworks.engine.PageCapture.place(it, area) } }
        override fun capturePage(done: (android.graphics.Bitmap?) -> Unit) = dev.glassworks.engine.PageCapture.capture(page, done)
        override fun hideMenu() { if (::settingsOverlay.isInitialized && settingsOverlay.isShowing) settingsOverlay.hide() }
        override fun post(block: () -> Unit) { handler.post(block) }
    }

    /** Where the page was before a notification opened a view full screen. */
    private var beforeNotificationPage: String? = null

    /** A notification's view, on the panel's own HA: in place when the frontend is already up, else loaded. */
    private fun openInPage(url: String) {
        if (beforeNotificationPage == null) beforeNotificationPage = currentUrl
        Log.i(TAG, "Notification: opening a view")
        if (FrontendUrls.sameOrigin(currentUrl, url)) page?.post(navigate(url))
        else page?.load(externalize(url))
    }

    /** The view closed (timeout, Back now, clear_notification): back where the panel was. */
    private fun backToDashboard() {
        val back = beforeNotificationPage ?: return
        beforeNotificationPage = null
        if (FrontendUrls.sameOrigin(currentUrl, back)) page?.post(navigate(back))
        else loadDashboard()
    }

    /** In place, replacing the history entry: dashboard → view → dashboard leaves one entry, not three. */
    private fun navigate(url: String) = JSONObject().put("type", "navigate").put("url", url).put("replace", true)

    private fun externalize(url: String): String =
        if (HaAuth.signedIn && FrontendUrls.sameOrigin(url, HaAuth.signedInBase)) FrontendUrls.withFlag(url) else url

    // ---- first-boot setup screen (docs/onboarding.md step 4; rules in setup/SetupState.kt)

    /** The last [HaAuth.movedAway] logged, so the 2 s setup tick logs each change once (#467). */
    private var movedLogged = false

    /** When this activity was made: the app's start where Android 6 can't say it (#198). */
    private val createdAt = SystemClock.elapsedRealtime()

    private fun setupInputs(): SetupInputs {
        val w = wifiStatus.snapshot()
        val now = SystemClock.elapsedRealtime()
        if (w.online) offlineSince = 0L else if (offlineSince == 0L) offlineSince = now
        if (w.online && !w.ethernet && !prefs.wifiSeen) prefs.wifiSeen = true
        val steps = paneSteps.map { PaneStepFacts(it.id, it.done(), it.reason, wanted = setupOverlay.wanted(it.id)) }
        val moved = HaAuth.movedAway
        if (moved != movedLogged) {
            movedLogged = moved
            Log.i(TAG, if (moved) "Home Assistant is at another address than the sign-in's: asking to sign in again, no token sent"
                else "The sign-in matches Home Assistant's address")
        }
        return SetupInputs(
            online = w.online,
            offlineForMs = if (w.online) 0L else now - offlineSince,
            haKnown = prefs.haBase != null,
            haSeen = prefs.haSeen || SetupSignals.haSubscribed,
            apiKeySet = prefs.apiEncryption,
            haSkipped = prefs.haSetupSkipped,
            haHelloThisProcess = SetupSignals.haHello,
            uptimeMs = now - (Platform.processStartElapsedMs() ?: createdAt),
            signedIn = HaAuth.signedIn,
            signinMoved = moved,
            onAuthPage = FrontendUrls.isAuthorizePage(currentUrl, prefs.haBase),
            signedOut = signedOutHere,
            usePanelLogin = usePanelLogin,
            showing = (setupOverlay.isShowing && !setupPreview) || setupCarried,
            afterOwnRestart = restartedInSetup,
            hidForPanelLogin = setupHidForLogin && !setupPreview,
            paneSteps = steps,
            ownSteps = deviceSteps.map { PaneStepFacts(it.id, it.done(), it.reason) },
            panesChosen = setupOverlay.panesChosen,
            dashboardLoaded = currentUrl.let { u -> u != null && FrontendUrls.sameOrigin(u, prefs.haBase) && !FrontendUrls.isAuthorizePage(u, prefs.haBase) },
            improv = ImprovController.setupFacts,
            installer = ImprovController.installerWifi,
            input = setupOverlay.input,
            // Read only where it counts (a frame without touch, offline): the S1 makes no extra Wi-Fi calls.
            joinExpected = !w.online && setupOverlay.input == Input.NONE && OfflineText.expectsJoin(offlineFacts()),
            neverSetUp = SetupState.neverSetUp(
                haKnown = prefs.haBase != null, haSeen = prefs.haSeen || SetupSignals.haSubscribed,
                apiKeySet = prefs.apiEncryption, haSkipped = prefs.haSetupSkipped, paneStepDone = steps.any { it.done },
                signedIn = HaAuth.signedIn,
            ),
        )
    }

    /** Re-evaluates the setup screen; cheap, called on every relevant change and every 2 s. */
    private fun refreshSetup() {
        if (!::setupOverlay.isInitialized) return
        if (glassworks.screen.testDashboard != null && !setupPreview) {
            // The test's dashboard isn't the panel's Home Assistant: no setup step applies to it, and nothing covers it.
            if (setupOverlay.isShowing) setupOverlay.hide()
            return
        }
        // Panes applying or refused: keep it up until Continue works (a pane's step can look done mid-way).
        // Panes holds while it applies or after a refusal, but never over a lost Wi-Fi: that step comes
        // first, and Panes again once the panel is back on.
        val d = SetupState.decide(setupInputs()).holdingPanes(setupOverlay.holdsPanes)
        if (setupPreview) {
            val forced = setupPreviewStep
            // A forced step: every step before it shows as done.
            val m = if (forced == null) setupModel(d, d.step, d.done, preview = true)
            // A forced step: every step before it shows as done, and so does any step that really is.
            else setupModel(d, forced, previewFlow().takeWhile { it != forced }.toSet() + d.done, preview = true)
            if (m.step == SetupFlow.DONE) {
                // A preview holds Done until it is closed (closeSetupPreview); nothing loads behind it.
                if (setupOverlay.model?.step != SetupFlow.DONE) setupOverlay.finish(m) {}
            } else {
                setupOverlay.render(m)
            }
            syncImprovInline()
            return
        }
        if (d.reasons != lastSetupReasons) {
            if (d.reasons.isNotEmpty()) Log.i(TAG, "Setup screen: ${d.reasons.joinToString()} (step ${d.step})")
            lastSetupReasons = d.reasons
        }
        // every step change, kept in the Manage log: what the screen showed when, across restarts
        val stepNow = "${d.step}${if (d.signinChecking) " (checking the sign-in)" else ""}"
        if (stepNow != lastSetupStep) {
            Log.i(TAG, "Setup step: $stepNow, ${if (d.show) "shown" else "hidden"}")
            lastSetupStep = stepNow
        }
        // Up again (the screen's own latch takes over) or nothing left: the login page's pause is over.
        if (d.show || d.step == SetupFlow.DONE) setupHidForLogin = false
        if (d.show) {
            VitroSentinel.setupShown() // no dashboard on this start: its end isn't a Vitro failure
            setupOverlay.render(setupModel(d, d.step, d.done, preview = false))
        } else if (setupOverlay.isShowing && setupOverlay.model?.step != SetupFlow.DONE) {
            Log.i(TAG, "Setup complete; loading the dashboard")
            glassworks.settleDeviceSteps(this)
            glassworks.setupHotspot?.cancel() // setup is over: its hotspot too (#197)
            hotspotAsked = false
            setupOverlay.finish { loadDashboardIfNeeded() }
        }
        if (setupOverlay.isShowing || d.step == SetupFlow.DONE) setupCarried = false
        syncImprovInline()
    }

    /**
     * While the setup screen is up, Improv lives in its Wi-Fi step and the overlay window stays
     * hidden, also while "Choose network" has Wi-Fi settings on top (the full-screen overlay would
     * cover the network list); the inline prompt is there again when the user comes back.
     */
    private fun improvInline(): Boolean =
        ::setupOverlay.isInitialized && setupOverlay.isShowing && !isFinishing && !isDestroyed

    /** The setup screen appeared or went away: Improv's overlay hides or comes back accordingly. */
    private fun syncImprovInline() {
        val now = improvInline()
        if (now == lastImprovInline) return
        lastImprovInline = now
        ImprovController.refreshUi()
    }

    /** Every step a preview can show: all the panes' steps, as if every pane were on. */
    private fun previewFlow() = SetupFlow.ids(setupPanes.isNotEmpty(), paneSteps.map { it.id }, deviceSteps.map { it.id })

    private fun setupModel(d: dev.glassworks.setup.SetupDecision, step: String, done: Set<String>, preview: Boolean) =
        SetupOverlay.Model(
            step = step,
            done = done,
            improv = d.improv,
            installer = ImprovController.installerWifi,
            wifi = wifiStatus.snapshot(),
            haUrl = prefs.haBase,
            haSource = if (prefs.isAutoUrl) prefs.haUrlSource else "",
            panelIp = dev.glassworks.platform.PanelAddress.now(this),
            signedIn = HaAuth.signedIn,
            signinMoved = !preview && HaAuth.movedAway,
            preview = preview,
            hotspot = if (preview) null else hotspotCard(),
            haChecking = d.haChecking,
            signinChecking = d.signinChecking,
        )

    // ---- setup: the panel's own Wi-Fi (#197)

    /** The person asked for the setup hotspot on this screen: Android's permission comes into the card now. */
    private var hotspotAsked = false

    /** Android was asked for the hotspot's permission in this run: tells "not asked yet" from "won't ask again". */
    private var hotspotPermissionAsked = false

    private fun hotspotPermission(): HotspotPermission.Step =
        HotspotPermission.step(Platform, { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }, hotspotPermissionAsked) {
            shouldShowRequestPermissionRationale(it)
        }

    private fun hotspotCard(): HotspotStep.Card? {
        val h = glassworks.setupHotspot ?: return null
        val view = h.view
        val needsPermission = hotspotAsked ||
            (view as? SetupHotspot.View.Off)?.error == HotspotFailure.NO_PERMISSION
        val permission = if (needsPermission) hotspotPermission() else HotspotPermission.Step.Ready
        val url = HotspotAddress.now(this)?.let { "http://$it:${dev.glassworks.auth.OAuth.SIGNIN_PORT}${LanSetup.PAGE}" }
        return HotspotStep.card(view, permission, url, asked = hotspotAsked)
    }

    private fun onHotspotAction(a: HotspotStep.Action) {
        if (setupPreview) return // the preview never changes state
        val h = glassworks.setupHotspot ?: return
        when (a) {
            HotspotStep.Action.OPEN, HotspotStep.Action.RETRY -> {
                hotspotAsked = true
                if (hotspotPermission() == HotspotPermission.Step.Ready) h.ask()
            }
            HotspotStep.Action.ALLOW -> (hotspotPermission() as? HotspotPermission.Step.Explain)?.let {
                hotspotPermissionAsked = true
                requestPermissions(HotspotPermission.request(it.permission), REQ_HOTSPOT)
            }
            HotspotStep.Action.STOP -> { hotspotAsked = false; h.cancel() }
            HotspotStep.Action.WIFI_SETTINGS -> openWifiSettings()
        }
        refreshSetup()
    }

    /** After setup: (re)load the dashboard unless it is already showing. */
    private fun loadDashboardIfNeeded() {
        val cur = currentUrl
        val onDashboard = cur != null && FrontendUrls.sameOrigin(cur, prefs.haBase) &&
            !FrontendUrls.isAuthorizePage(cur, prefs.haBase)
        // (just signed in: the auth listener already started the load)
        val justLoaded = SystemClock.elapsedRealtime() - dashboardLoadAt < 5_000
        if (!onDashboard && !justLoaded) loadDashboard()
    }

    /**
     * `--ez show_setup true [--es setup_step <id>]` (a core step, wifi|signin|ha|panes|done, a pane's step such as `radio`,
     * or a device step such as `silent_updates`), `--ez show_signin true` (= signin).
     */
    private fun applySetupPreview(intent: Intent) {
        val asked = intent.getStringExtra("setup_step")
        val step = SetupFlow.parse(asked, setupPanes.isNotEmpty(), paneSteps.map { it.id }, deviceSteps.map { it.id })
        if (asked != null && step == null) Log.e(TAG, "setup_step '$asked' is not a step: ${previewFlow()}")
        when {
            intent.getBooleanExtra("show_signin", false) -> startSetupPreview(SetupFlow.SIGNIN)
            intent.hasExtra("show_setup") && !intent.getBooleanExtra("show_setup", false) -> closeSetupPreview()
            intent.getBooleanExtra("show_setup", false) || step != null -> startSetupPreview(step)
        }
    }

    private fun startSetupPreview(step: String?) {
        Log.i(TAG, "Setup screen preview (step ${step ?: "live"})")
        setupPreview = true
        setupPreviewStep = step
        refreshSetup()
    }

    private fun closeSetupPreview() {
        if (!setupPreview) return
        setupPreview = false
        setupPreviewStep = null
        setupOverlay.hide()
        refreshSetup() // shows the real screen again if the panel isn't set up
    }

    /**
     * The fallback when the page never says it's drawn: hide the connecting screen 15 s after the page
     * loaded, or up to 45 s when Home Assistant's frontend said it is still loading (a cold load on the
     * S1), so its white launch screen doesn't show early.
     */
    private val hideLoading: Runnable = object : Runnable {
        override fun run() {
            val waited = SystemClock.uptimeMillis() - pageStoppedAt
            if (frontendLoading && waited < LAUNCH_FALLBACK_MS) handler.postDelayed(this, LAUNCH_FALLBACK_MS - waited)
            else hideConnecting(if (frontendLoading) "fallback 45 s, the frontend still loading" else "fallback 15 s, no word from the page")
        }
    }

    /** When the current page started loading, for the log. */
    private var pageStartedAt = 0L

    /** Hides the connecting screen, and says why in the log (one line per reason, for panel runs). */
    private fun hideConnecting(reason: String) {
        if (!loadingOverlay.isShowing) return
        val since = if (pageStartedAt > 0) " (${SystemClock.uptimeMillis() - pageStartedAt} ms after the page started)" else ""
        Log.i(TAG, "Connecting screen hidden: $reason$since")
        loadingOverlay.hide()
    }
    /** Home Assistant's launch screen is up on this page (content.js "launch"), and no "ready" yet. */
    private var frontendLoading = false
    /** When the fallback was last armed (first paint or page stop). */
    private var pageStoppedAt = 0L

    /** The host the dashboard loads from, for the connecting screen ("" before one is known). */
    private fun dashboardHost(): String = prefs.shownHost(currentUrl ?: prefs.url)

    private fun hasNetwork(): Boolean {
        val cm = getSystemService(android.net.ConnectivityManager::class.java) ?: return true
        return cm.activeNetwork != null
    }

    /** The loading screen shows the no-network text (OfflineText) for a failed load. */
    private var offlineShown = false

    /** A failed load without a network: "Connecting to Wi-Fi…" while a join is underway or expected. */
    private fun showOffline() {
        val t = OfflineText.decide(offlineFacts(), ImprovController.BOOT_GRACE_MS, setupOverlay.input)
        offlineShown = true
        loadingOverlay.show(
            if (t.connecting) LoadingOverlay.State.Connecting(t.title, t.detail, host = false)
            else LoadingOverlay.State.Offline(t.title, t.detail, t.action),
        )
    }

    /** Every setup tick while the no-network text is up: network back -> load now; else keep the text current. */
    private fun refreshOffline() {
        if (!offlineShown || !loadingOverlay.isShowing) return
        if (hasNetwork()) {
            offlineShown = false
            handler.removeCallbacks(retryLoad)
            loadDashboard()
        } else {
            showOffline()
        }
    }

    @Suppress("DEPRECATION")
    private fun offlineFacts(): OfflineText.Facts {
        val wm = applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
        val cm = getSystemService(android.net.ConnectivityManager::class.java)
        val now = SystemClock.elapsedRealtime()
        // Android 10 lists saved networks only to a device owner (and needs location): an empty list from
        // anyone else means "can't tell", not "none".
        val saved = runCatching { wm?.configuredNetworks }.getOrNull()?.let { list ->
            when {
                list.isNotEmpty() -> true
                isDeviceOwner() -> false
                else -> null
            }
        }
        val supplicant = runCatching { wm?.connectionInfo?.supplicantState?.name }.getOrNull()
        val detailed = runCatching { cm?.getNetworkInfo(android.net.ConnectivityManager.TYPE_WIFI)?.detailedState?.name }.getOrNull()
        // A failed read is "can't tell", never "off" (#448: a Mirror with Wi-Fi on said "Wi-Fi is off").
        val wifiState = runCatching { wm?.wifiState }
        val wifiRead = wifiState.exceptionOrNull()?.toString() ?: "state ${wifiState.getOrNull()}"
        if (wifiRead != lastWifiRead) {
            lastWifiRead = wifiRead
            Log.i(TAG, "Offline: Wi-Fi $wifiRead")
        }
        return OfflineText.Facts(
            wifiEnabled = OfflineText.wifiEnabled(wifiState.getOrNull()),
            savedNetwork = saved,
            joining = OfflineText.joining(supplicant, detailed),
            wasOnlineBefore = prefs.wifiSeen || prefs.haSeen,
            offlineForMs = if (offlineSince == 0L) 0L else now - offlineSince,
        )
    }

    /** The last Wi-Fi read [offlineFacts] logged: a line when it changes, not every tick. */
    private var lastWifiRead: String? = null

    private fun isDeviceOwner(): Boolean = runCatching {
        getSystemService(android.app.admin.DevicePolicyManager::class.java).isDeviceOwnerApp(packageName)
    }.getOrDefault(false)

    private fun openWifiSettings() {
        for (i in glassworks.frame.wifiSettingsIntents()) {
            try {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: Exception) {
                Log.w(TAG, "Cannot open ${i.action}: $e")
            }
        }
        Toast.makeText(this, "No Wi-Fi settings on this device", Toast.LENGTH_LONG).show()
    }

    // ---- setup: Improv inline (Wi-Fi step)

    private fun onImprovAction(a: WifiStep.Action) {
        if (setupPreview) return // the preview never changes state
        when (a) {
            WifiStep.Action.ALLOW -> ImprovController.authorize("button on the setup screen")
            WifiStep.Action.NOT_NOW -> ImprovController.stop("dismissed on panel")
            WifiStep.Action.STOP -> ImprovController.stop("stopped on panel")
            WifiStep.Action.START, WifiStep.Action.RETRY -> ImprovController.startOnDemand("setup screen")
        }
        refreshSetup()
    }

    private fun onSkipHaSetup() {
        if (setupPreview) { closeSetupPreview(); return }
        Log.i(TAG, "Setup: adding to Home Assistant skipped")
        prefs.haSetupSkipped = true
        refreshSetup()
    }

    private fun onUsePanelLogin() {
        setupHidForLogin = setupOverlay.isShowing && !setupPreview
        if (setupPreview) closeSetupPreview()
        usePanelLogin = true
        setupOverlay.hide()
        // HA's login page for the panel's own sign-in, also when the page still has a session of its own: that session
        // isn't the panel's sign-in, and the next start would ask again (#633).
        if (!HaAuth.signedIn) prefs.haBase?.let { page?.load(panelSignIn.start(it)) }
        refreshSetup()
    }

    /**
     * The code from Home Assistant's login page on the panel becomes the panel's sign-in, stored as the QR code's is.
     * HaAuth's listener then loads the dashboard. If it fails, the Sign-in step comes back over a new login page.
     * Called from [PageHost.redirect], whose `about:blank` the engine queues on the main looper once it returns. The
     * worker starts from a later message, so its outcome always lands after that load and the dashboard is last.
     */
    private fun finishPanelSignIn(url: String) = handler.post {
        if (isDestroyed) return@post
        loadingOverlay.show(LoadingOverlay.State.Connecting(LoadingOverlay.CONNECTING, prefs.shownHost(url)))
        Thread({
            val failed = when (val o = panelSignIn.finish(url)) {
                is PanelSignIn.Outcome.Failed -> o.reason
                is PanelSignIn.Outcome.SignedIn -> try {
                    prefs.auth = o.auth
                    null
                } catch (e: SignInNotStored) {
                    e.message
                }
            }
            if (failed == null) {
                Log.i(TAG, "Signed in on this panel (refresh token stored)")
                // Drops any access token cached for an earlier sign-in, and tells every screen.
                HaAuth.signInStored("Signed in on this panel")
                return@Thread
            }
            Log.w(TAG, "Sign-in on this panel failed: $failed")
            handler.post {
                if (isDestroyed) return@post
                usePanelLogin = false
                prefs.haBase?.let { page?.load(panelSignIn.start(it)) }
                refreshSetup()
            }
        }, "panel-signin").apply { isDaemon = true }.start()
    }

    private fun onManualHaUrl(raw: String) {
        if (setupPreview) {
            setupOverlay.manualResult(false, "Preview: nothing was saved.")
            return
        }
        val d = glassworks.discovery
        if (d == null) {
            setupOverlay.manualResult(false, "Glassworks is still starting up. Try again in a moment.")
            return
        }
        d.setManual(raw) { ok, url ->
            handler.post {
                if (ok && url != null) {
                    setupOverlay.manualResult(true, "Found Home Assistant at $url")
                    onHaFound(url)
                } else {
                    setupOverlay.manualResult(false, "No Home Assistant answered at ${url ?: raw}.")
                }
            }
        }
    }

    /** Discovery (or manual entry) found Home Assistant. */
    private fun onHaFound(url: String) {
        val setting = if (prefs.isAutoUrl) KioskPrefs.AUTO else LocalName.forLog(prefs.urlSetting)
        Log.i(TAG, "Home Assistant at ${LocalName.forLog(url)} (dashboard setting: $setting)")
        refreshSetup()
        if (!prefs.loadsFoundAddress || glassworks.screen.testDashboard != null) return
        val started = glassworks.engine.started
        if (engineStartWanted() != KioskPrefs.engineStart(started?.secureHosts.orEmpty(), started?.dashboard)) {
            // The HA host must be in dom.securecontext.allowlist (mic), which Gecko reads at startup, and the legacy
            // engine holds every page to the dashboard's origin, which it takes once (#198).
            restartProcess()
            return
        }
        loadDashboard()
    }

    // ---- intents (see app/README.md):
    //   --es url <url>          set + persist the dashboard URL
    //   --es open <url>         navigate once, without persisting
    //   --ez test_hold true [--ei test_hold_s 1800] [--es test_dashboard http://127.0.0.1:<port>/…]
    //                           a test's hold (ScreenPolicy.testHold), and its own dashboard while it lasts
    //   --ef zoom 1.25          CSS px ratio (0 = auto); restarts the process
    //   --es secure_hosts a,b   extra secure-context hosts; restarts the process
    //   --ez remote_debug true  Gecko remote debugging; restarts the process (ignored on the legacy engine, #29)
    //   --ez vitro true         Vitro, the native dashboard renderer (Settings → Labs); restarts the process, also on a
    //                           cold start when it changes the setting (the engine is picked when the runtime is made)
    //   --ez reload true        reload the page
    //   --ez restart true       restart the app process
    //   --es api_key <base64>   ESPHome API encryption key (32 bytes); --ez api_encryption true|false
    //   --ez show_setup true    preview the setup screen (--es setup_step wifi|ha|radio|signin|done); false closes it
    // (Wi-Fi, Home Assistant and radio from the web installer go over adb sockets instead:
    //  improv/ImprovSerialServer, setup/SetupChannelServer.)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        screenExtras(intent, runtime.app::trustsExtras)
        applyIntent(intent, fromNewIntent = true)
    }

    private fun applyIntent(intent: Intent?, fromNewIntent: Boolean) {
        intent ?: return
        var needsRestart = false
        intent.getStringExtra("url")?.takeIf { it.isNotBlank() }?.let {
            val oldStart = prefs.engineStart()
            prefs.urlSetting = it // a URL, or "auto"
            needsRestart = needsRestart || prefs.engineStart() != oldStart // hosts, or the dashboard's origin (#198)
            if (fromNewIntent) loadDashboard()
        }
        intent.getStringExtra("ha_discovered")?.let { if (fromNewIntent) onHaFound(it) }
        if (intent.getBooleanExtra("open_settings", false)) glassworks.frame.openSystemSettings(this)
        // From the web installer: the panel's language (it recreates this activity if it changes).
        intent.getStringExtra("set_locale")?.let { tag ->
            intent.removeExtra("set_locale")
            SystemLocale.set(tag)
        }
        if (fromNewIntent) applySetupPreview(intent)
        if (intent.hasExtra("zoom")) {
            prefs.zoom = intent.getFloatExtra("zoom", 0f)
            needsRestart = true
        }
        // The app picks its engine when its runtime is made, before this runs even on a cold start: a change to Vitro
        // restarts then too, so the extra takes effect at once, as it did when Vitro read it at the first page.
        var engineChoiceChanged = false
        if (intent.hasExtra("vitro")) {
            val on = intent.getBooleanExtra("vitro", false)
            engineChoiceChanged = on != prefs.vitroEnabled
            prefs.vitroEnabled = on
            needsRestart = true
        }
        intent.getStringExtra("secure_hosts")?.let {
            prefs.extraSecureHosts = it
            needsRestart = true
        }
        if (intent.hasExtra("remote_debug")) {
            if (runtime.engine.lockedDown) {
                Log.i(TAG, "remote_debug ignored: the legacy engine is frozen and loads only Home Assistant, so it stays off")
            } else {
                prefs.setRemoteDebugging(intent.getBooleanExtra("remote_debug", false))
                needsRestart = true
            }
        }
        if (intent.hasExtra("hide_header") || intent.hasExtra("hide_sidebar")) {
            if (intent.hasExtra("hide_header")) prefs.hideHeader = intent.getBooleanExtra("hide_header", true)
            if (intent.hasExtra("hide_sidebar")) prefs.hideSidebar = intent.getBooleanExtra("hide_sidebar", true)
            pushConfig()
        }
        intent.getStringExtra("screen_mode")?.let { prefs.screenMode = it }
        if (intent.hasExtra("idle_off_sec")) prefs.idleOffSec = intent.getIntExtra("idle_off_sec", 120)
        if (intent.hasExtra("presence_range_m")) {
            prefs.presenceRangeM = intent.getFloatExtra("presence_range_m", 0f)
            glassworks.screen.applyPresenceRange()
        }
        // The panes' extras (their on/off, then their own settings): their definitions.
        if (panes.intentExtras().applyFrom(intent)) needsRestart = true
        applyApiKeyExtras(
            prefs, intent.getStringExtra("api_key"),
            if (intent.hasExtra("api_encryption")) intent.getBooleanExtra("api_encryption", false) else null,
        )?.let { Log.w(TAG, it) }
        if (intent.hasExtra("auto_brightness")) {
            prefs.autoBrightness = intent.getBooleanExtra("auto_brightness", true)
            glassworks.screen.refreshBrightness()
        }
        when (intent.getStringExtra("screen")) {
            "off" -> glassworks.screen.sleep("intent")
            "on" -> glassworks.screen.wake("intent")
        }
        // Read before the hold takes it: applyTestHoldExtra removes the extra from the intent.
        val holdAsked = intent.getBooleanExtra(dev.glassworks.screen.ScreenPolicy.TEST_HOLD_EXTRA, false)
        glassworks.screen.applyTestHoldExtra(this, intent)
        if (holdAsked) {
            // Clarity reads which engine draws the page and the Labs setting it puts back after a Vitro run (perfrun.py).
            Log.i(TAG, "test hold engine: ${runtime.engine.name.ifEmpty { "unknown" }}; vitro setting ${if (prefs.vitroEnabled) "on" else "off"}")
        }
        if (intent.getBooleanExtra("remove_admin", false)) glassworks.screen.removeAdmin()
        // The kiosk settings menu: the notification, or `--ez kiosk_settings true` (also from a cold start).
        if (SettingsMenu.requested { k -> if (intent.hasExtra(k)) intent.getBooleanExtra(k, false) else null }) {
            intent.removeExtra(SettingsMenu.EXTRA)
            intent.removeExtra(SettingsMenu.LEGACY_EXTRA) // not again when the activity is recreated
            handler.post { if (::settingsOverlay.isInitialized) showSettings() }
        }
        if (engineChoiceChanged || (fromNewIntent && (needsRestart || intent.getBooleanExtra("restart", false)))) {
            restartProcess()
            return
        }
        if (fromNewIntent && intent.getBooleanExtra("reload", false)) page?.reload()
        // One-off navigation that does NOT change the saved dashboard URL.
        if (fromNewIntent) intent.getStringExtra("open")?.takeIf { it.isNotBlank() }?.let { page?.load(it) }
    }

    // ---- immersive full screen

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_PID, android.os.Process.myPid())
        outState.putBoolean(STATE_SETUP_UP, setupCarried || (::setupOverlay.isInitialized && setupOverlay.isShowing && !setupPreview))
        outState.putBoolean(STATE_RESTARTED_IN_SETUP, restartedInSetup)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        VitroSentinel.checkStable() // a start that slept past Vitro's crash window is a stable one
        hideSystemUi()
        requestRuntimePermissions() // at start, and what was turned on while another screen was in front
        glassworks.updates?.recheckSilent() // back from Android's settings: Home Assistant's Silent updates follows (#666)
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        if (Platform.hasWindowInsetsController) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN)
        }
    }

    // ---- hidden settings gesture (doesn't consume the touch)

    /**
     * Shows or hides [blackoutView]. It joins the window, on top, only at the first blackout, so a frame that never
     * has one (every SleepMode.LOCK frame) keeps its layers exactly as they were.
     */
    private fun showBlackout(on: Boolean) {
        if (on && blackoutView.parent == null) root.addView(blackoutView, android.widget.FrameLayout.LayoutParams(-1, -1))
        blackoutView.visibility = if (on) View.VISIBLE else View.GONE
    }

    private var wakeKeyCode: Int? = null

    /** A key wakes a screen that sleeps with its backlight off, and its repeats/release do nothing else (ScreenPolicy.blackout). */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (glassworks.screen.blackout) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                wakeKeyCode = event.keyCode
                glassworks.screen.wake("key")
            }
            return true
        }
        if (wakeKeyCode == event.keyCode) {
            if (event.action == android.view.KeyEvent.ACTION_UP || event.isCanceled) wakeKeyCode = null
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            glassworks.screen.userActivity()
            val corner = 80 * resources.displayMetrics.density
            if (ev.x < corner && ev.y < corner) {
                val now = SystemClock.uptimeMillis()
                if (cornerTaps == 0 || now - firstCornerTapAt > 3000) {
                    cornerTaps = 0
                    firstCornerTapAt = now
                }
                if (++cornerTaps >= 5) {
                    cornerTaps = 0
                    showSettings()
                }
            } else {
                cornerTaps = 0
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /** While the settings menu is open: its live views follow what they show (the radio's status, ...). */
    private val settingsTick = object : Runnable {
        override fun run() {
            if (!::settingsOverlay.isInitialized || !settingsOverlay.isShowing) return
            settingsOverlay.refresh()
            handler.postDelayed(this, SETTINGS_TICK_MS)
        }
    }

    /**
     * The kiosk settings menu (settings/KioskSettingsOverlay): 5 taps top-left, or the notification. The
     * core's sections and the panes', in the order every settings surface uses.
     */
    private fun showSettings() {
        // Resolved once per opening: the model is made again on each refresh tick (#235 N4).
        deviceSettings = S1Settings.hasDeviceSettings(glassworks, this)
        settingsOverlay.reload = ::settingsModel
        settingsOverlay.overview = HaAuth.manage?.let { m -> KioskSettingsOverlay.Overview(m::status, m.actions) }
        settingsOverlay.show(settingsModel())
        handler.removeCallbacks(settingsTick)
        handler.postDelayed(settingsTick, 500)
    }

    /** The frame has its own settings app, resolved when the menu opens ([showSettings]). */
    private var deviceSettings = false

    /** The menu's sections as they are now, the Manage page's too (#242): each pane's page reads its running pane (#212). */
    private fun settingsModel(): KioskSettingsOverlay.Model =
        S1Settings.model(glassworks, prefs, panes, deviceSettings, fullScreen = ::pushConfig, blueprints = { id -> blueprints.item(id, ::openBlueprintLink) }, context = this)

    /** The panes' Blueprints cards: the list, and what the panel's sign-in may do with them (checked off the main thread). */
    private val blueprints by lazy { PanelBlueprints.forPanel(this, glassworks, prefs) }

    /**
     * A Blueprints card's button: Home Assistant's own page (its import dialog, filled in, or its blueprint
     * list) in the dashboard, in place, so Back comes back to the dashboard. The panel writes nothing to
     * Home Assistant; the person does, in Home Assistant's dialog.
     */
    private fun openBlueprintLink(url: String) {
        Log.i(TAG, "Blueprints: opening Home Assistant's page in the dashboard")
        if (FrontendUrls.sameOrigin(currentUrl, url)) page?.post(JSONObject().put("type", "navigate").put("url", url).put("replace", false))
        else page?.load(externalize(url))
    }

    private var restartWaitingSince = 0L

    /**
     * Gecko prefs are read once per process, so restart the process to apply them. Not while a Wi-Fi
     * join is in flight (installer or Bluetooth): joining is when HA gets discovered, and restarting
     * then would cut the Improv connection before its result arrives. Waits up to a minute for that.
     */
    private fun restartProcess() {
        val now = SystemClock.elapsedRealtime()
        if (ImprovController.busy && (restartWaitingSince == 0L || now - restartWaitingSince < 60_000)) {
            if (restartWaitingSince == 0L) {
                restartWaitingSince = now
                Log.i(TAG, "restart deferred: Wi-Fi setup in progress")
            }
            window.decorView.postDelayed({ restartProcess() }, 1000)
            return
        }
        restartWaitingSince = 0L
        VitroSentinel.restarting() // the app's own restart isn't a failed start: a start that stayed up clears its strikes
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, glassworks.components.mainActivity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            Platform.backgroundStartOptions(),
        )
        (getSystemService(ALARM_SERVICE) as AlarmManager)
            .set(AlarmManager.RTC, System.currentTimeMillis() + 600, pi)
        // the setup screen comes back first in the new process, so the dashboard never shows in between
        prefs.markSetupUpAtRestart(::setupOverlay.isInitialized && setupOverlay.isShowing && !setupPreview)
        finishAffinity()
        exitProcess(0)
    }

    // ---- back: navigate back inside the page; never leave the kiosk

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // The topmost layer only: the menu's own (a picker, a dialog, then the menu), then a setup preview.
        if (::settingsOverlay.isInitialized && settingsOverlay.isShowing) { settingsOverlay.back(); return }
        if (overlays.asReversed().any { it.back() }) return
        if (setupPreview) { closeSetupPreview(); return }
        page?.goBack()
    }

    // ---- Android runtime permissions (mic/camera) for Gecko

    @Suppress("DEPRECATION") // getPackageInfo(String, PackageInfoFlags) is API 33; this one runs everywhere
    private val declaredPermissions by lazy { packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toSet() }

    /** The screen takes a tap: read once, as the setup tick asks every couple of seconds. */
    private val touchScreen by lazy { glassworks.frame.input == Input.TOUCH }

    /** In front of the person: only then may Android's dialog show. */
    private var resumed = false

    /** Answered in this activity's life: a refusal isn't asked again until the next start. Android kills the app on a revoke. */
    private val answeredPermissions = HashSet<String>()
    private val grantedPermissions = HashSet<String>()

    /** The startup request is open: no second one until Android answers it (an empty answer when it cancelled it). */
    private var askingPermissions = false

    private fun granted(permission: String): Boolean =
        permission in grantedPermissions || (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED).also { if (it) grantedPermissions += permission }

    /**
     * What the panes that are on need and the app declares (#461), at start and when a pane turns on later, each once,
     * while the activity is in front. Never on a screen without touch: nobody could answer there.
     */
    private fun requestRuntimePermissions() {
        if (askingPermissions) return
        val wanted = glassworks.paneDefinitions(this).permissionsToAsk(touch = touchScreen, declared = declaredPermissions, granted = ::granted) -
            answeredPermissions
        if (wanted.isEmpty()) return
        askingPermissions = true
        requestPermissions(wanted.toTypedArray(), REQ_STARTUP)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // A new grant can add a foreground type (microphone on Android 14+): KioskService takes it on start.
        if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) KioskService.start(this)
        if (requestCode == REQ_HOTSPOT) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) glassworks.setupHotspot?.ask()
            refreshSetup()
        }
        if (requestCode == REQ_STARTUP) {
            askingPermissions = false
            answeredPermissions += permissions.filterIndexed { i, _ -> i < grantResults.size }
        }
        if (requestCode == REQ_GECKO) {
            val cb = pendingAndroidPermission
            pendingAndroidPermission = null
            cb?.invoke(grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED })
        }
    }

    // ---- what the page reports (dev.glassworks.engine)

    private val pageHost = object : PageHost {
        // Home Assistant and the secure-context hosts, and a test's own dashboard while it has one (#847).
        override fun trusts(url: String): Boolean = TrustedPages.trusts(
            url, HaAuth.signedInBase,
            prefs.secureHosts() + listOfNotNull(glassworks.screen.testDashboard?.let { Uri.parse(it).host }),
        )

        override fun onAndroidPermissions(permissions: List<String>, answer: (Boolean) -> Unit) {
            val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isEmpty()) {
                answer(true)
            } else {
                pendingAndroidPermission?.invoke(false)
                pendingAndroidPermission = answer
                requestPermissions(missing.toTypedArray(), REQ_GECKO)
            }
        }

        // Not the end of the connecting screen: Home Assistant's first paint is its own white launch
        // screen. The page says when it's drawn ("ready", content.js); this only arms the fallback.
        override fun onFirstPaint() {
            handler.post {
                handler.removeCallbacks(hideLoading)
                pageStoppedAt = SystemClock.uptimeMillis()
                handler.postDelayed(hideLoading, READY_FALLBACK_MS)
            }
        }

        override fun onContentGone(killed: Boolean) {
            Log.e(TAG, "Content process ${if (killed) "killed" else "crashed"}; restarting session")
            handler.post {
                page?.restart()
                loadDashboard()
            }
        }

        override fun onLocationChange(url: String?) {
            currentUrl = url
            // An unauthenticated HA frontend redirects to /auth/authorize: the setup screen covers it
            // with the QR code (unless the user asked for the panel's own login page).
            handler.post { refreshSetup() }
        }

        // Once signed in, every top-level HA page gets external_auth=1, so the content script
        // installs window.externalApp before the frontend starts (also after reloads and redirects).
        override fun redirect(url: String): String? {
            // Home Assistant sending the panel's own sign-in back: the app takes the code, the page never loads it.
            if (panelSignIn.isCallback(url, prefs.haBase)) {
                finishPanelSignIn(url)
                return "about:blank"
            }
            if (!HaAuth.signedIn) return null
            val base = HaAuth.signedInBase
            return when {
                FrontendUrls.isAuthorizePage(url, base) -> prefs.url.takeIf { it.isNotEmpty() }?.let(::externalize)
                FrontendUrls.needsFlag(url, base) -> FrontendUrls.withFlag(url)
                else -> null
            }
        }

        override fun onPageStart(url: String) {
            handler.removeCallbacks(retryLoad)
            handler.removeCallbacks(hideLoading)
            frontendLoading = false
            pageStartedAt = SystemClock.uptimeMillis()
            offlineShown = false
            if (url.startsWith("http")) loadingOverlay.show(LoadingOverlay.State.Connecting(LoadingOverlay.CONNECTING, prefs.shownHost(url)))
        }

        override fun onPageStop(success: Boolean) {
            if (success) {
                // In case the page never says it's ready (no web extension, a frontend that fails).
                handler.removeCallbacks(hideLoading)
                pageStoppedAt = SystemClock.uptimeMillis()
                handler.postDelayed(hideLoading, READY_FALLBACK_MS)
            } else {
                if (hasNetwork()) {
                    offlineShown = false
                    loadingOverlay.show(LoadingOverlay.State.Unreachable(dashboardHost(), SystemClock.elapsedRealtime() + RETRY_MS))
                    if (prefs.loadsFoundAddress) glassworks.discovery?.findAgain() // DHCP may have moved Home Assistant (#449)
                } else {
                    showOffline() // "Connecting to Wi-Fi…" after a reboot, the setup hint when there's nothing to wait for
                }
                // HA restarting / Wi-Fi not up yet after boot: keep retrying.
                handler.removeCallbacks(retryLoad)
                handler.postDelayed(retryLoad, RETRY_MS)
            }
        }

        // ---- page <-> app messaging (assets/extension/content.js)

        override fun onConnect(port: PagePort) {
            port.post(JSONObject().put("type", "state").put("state", stateJson()))
        }

        override fun onPortMessage(port: PagePort, message: JSONObject) = onPortRequest(message, port)
    }

    private fun configJson() = JSONObject()
        .put("hideHeader", prefs.hideHeader)
        .put("hideSidebar", prefs.hideSidebar)
        // content.js reads HA's localStorage only when asked: Vitro's islands need the copy, nothing else does.
        .put("pageStorage", runtime.engine.keepsPageStorage)

    private fun stateJson() = JSONObject()
        .put("presence", glassworks.screen.near)
        .put("proximityRaw", glassworks.screen.proximityRaw.let { if (it.isNaN()) JSONObject.NULL else it.toDouble() })
        .put("lux", glassworks.screen.lux.let { if (it.isNaN()) JSONObject.NULL else it.toDouble() })
        .put("screenOn", glassworks.screen.screenOn)
        .put("voice", glassworks.voiceState)

    private fun pushConfig() {
        page?.post(JSONObject().put("type", "config").put("config", configJson()))
    }

    private fun pushState() {
        page?.post(JSONObject().put("type", "state").put("state", stateJson()))
    }

    /**
     * Request/response over the native port (content.js `request()`): `{type, id, ...}` in,
     * `{type: "reply", id, ...}` out. Used instead of sendNativeMessage replies, which don't reach
     * the page in this GeckoView build ("Invalid event data for callback").
     */
    private fun onPortRequest(m: JSONObject, port: PagePort) {
        if (m.optString("type") == "dashboards") { onDashboards(m, port); return }
        if (m.optString("type") == "haTheme") { onHaTheme(m, port); return }
        if (m.optString("type") == "pageStorage") { onPageStorage(m, port); return }
        if (m.optString("type") == "ready") { onPageReady(port, m.optString("why")); return }
        if (m.optString("type") == "launch") {
            if (port.topLevel) handler.post { frontendLoading = true; Log.i(TAG, "Home Assistant's launch screen is up: waiting for it to go") }
            return
        }
        val id = m.optInt("id", -1)
        if (id < 0) return
        fun reply(o: JSONObject?) {
            val out = o ?: JSONObject().put("success", false)
            out.put("type", "reply").put("id", id)
            handler.post { runCatching { port.post(out) } }
        }
        when (m.optString("type")) {
            "config" -> reply(JSONObject().put("config", configJson()))
            "state" -> reply(JSONObject().put("state", stateJson()))
            "getExternalAuth" -> externalAuth(m, port, ::reply)
            "revokeExternalAuth" -> revokeExternalAuth(port, ::reply)
            else -> reply(null)
        }
    }

    /**
     * `{type: "ready"}` from content.js: the top-level page is drawn (Home Assistant removed its own
     * launch screen), so the connecting screen cross-fades into it and the frontend's white splash never
     * shows (#63 round 2). A frame's word doesn't count.
     */
    private fun onPageReady(sender: PagePort, why: String) {
        if (!sender.topLevel) { Log.i(TAG, "A frame said ready ($why): ignored"); return }
        handler.post {
            frontendLoading = false
            handler.removeCallbacks(hideLoading)
            hideConnecting("ready, ${why.ifEmpty { "page" }}")
            // Vitro's dashboard is drawn, or a page it handed to the web engine: this start got going.
            VitroSentinel.pageDrawn()
        }
    }

    /**
     * `{type: "dashboards", json: "[{url_path,title}]"}` from the HA frontend (content.js, the page's
     * own `lovelace/dashboards/list`), for the ESPHome "Dashboard" select. Only the top-level page on
     * the Home Assistant the panel uses is believed.
     */
    private fun onDashboards(m: JSONObject, sender: PagePort) {
        if (!sender.topLevel || !FrontendUrls.sameOrigin(sender.url, prefs.haBase)) {
            Log.w(TAG, "dashboard list from ${sender.url} ignored (not the Home Assistant in use)")
            return
        }
        val list = DashboardOptions.parse(m.optString("json")) ?: return
        Log.d(TAG, "${list.size} dashboard(s) from the frontend")
        Dashboards.report(list)
    }

    /**
     * `{type: "pageStorage", json}` from the HA frontend (content.js: the page's `localStorage`, credentials left out),
     * kept for cards that run outside Home Assistant's origin, Vitro's web islands (#881, [HaPageStorage]). Only the
     * top-level page on the Home Assistant the panel uses is believed, as with the dashboards. Kept only while the
     * engine uses it (Vitro on): with Vitro off, the app keeps no copy of HA's storage.
     */
    private fun onPageStorage(m: JSONObject, sender: PagePort) {
        if (!runtime.engine.keepsPageStorage) return
        val url = sender.url
        if (!sender.topLevel || url == null || !FrontendUrls.sameOrigin(url, prefs.haBase)) return
        val json = m.optString("json").ifEmpty { return }
        dev.glassworks.web.HaPageStorage.putLater(applicationContext, url, json)
    }

    /**
     * `{type: "haTheme", dark?, primary?, accent?, background?}` from the HA frontend (content.js: the page's
     * `hass.themes.darkMode` and its theme colors as `#rrggbb`), for the overlays that follow Home Assistant's dark mode
     * or take its colors (#691). Only the top-level page on the Home Assistant the panel uses is believed, as with the
     * dashboards; saved, so the overlays have them from the next start. A part that isn't one is left as it was.
     */
    private fun onHaTheme(m: JSONObject, sender: PagePort) {
        if (!sender.topLevel || !FrontendUrls.sameOrigin(sender.url, prefs.haBase)) {
            Log.w(TAG, "theme from ${sender.url} ignored (not the Home Assistant in use)")
            return
        }
        val dark = m.opt("dark") as? Boolean
        fun colour(k: String) = (m.opt(k) as? String)?.let(dev.glassworks.theme.Themes::colour)
        val primary = colour("primary"); val background = colour("background")
        val colors = if (primary != null && background != null) dev.glassworks.theme.Themes.Palette(primary, colour("accent") ?: primary, background) else null
        handler.post {
            if (dark != null && prefs.haDark != dark) { prefs.haDark = dark; Log.i(TAG, "Home Assistant is in ${if (dark) "dark" else "light"} mode") }
            if (colors != null && prefs.haColors != colors) { prefs.haColors = colors; Log.i(TAG, "Home Assistant's theme colors changed") }
        }
    }

    /** Only the signed-in HA origin may ask for tokens. */
    private fun senderAllowed(sender: PagePort): Boolean =
        sender.topLevel && FrontendUrls.sameOrigin(sender.url, HaAuth.signedInBase)

    /** `externalApp.getExternalAuth` → `{success, access_token, expires_in}` (never logged). */
    private fun externalAuth(msg: JSONObject, sender: PagePort, reply: (JSONObject) -> Unit) {
        if (!senderAllowed(sender)) {
            Log.w(TAG, "getExternalAuth from ${sender.url} refused (not the signed-in Home Assistant)")
            reply(JSONObject().put("success", false))
            return
        }
        HaAuth.accessToken(force = msg.optBoolean("force")) { a ->
            val r = when (a) {
                is HaAuth.Access.Ok -> JSONObject().put("success", true)
                    .put("access_token", a.accessToken)
                    // GeckoView native-message replies go through GeckoBundle, which has no Long:
                    // a Long here fails with "Invalid event data for callback" and the page never hears back.
                    .put("expires_in", a.expiresIn.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
                is HaAuth.Access.Failed -> {
                    Log.w(TAG, "getExternalAuth failed: ${a.reason}")
                    JSONObject().put("success", false)
                }
            }
            Log.d(TAG, "getExternalAuth -> success=${r.optBoolean("success")}")
            reply(r)
        }
    }

    /** `externalApp.revokeExternalAuth`: the user logged out in the HA frontend. */
    private fun revokeExternalAuth(sender: PagePort, reply: (JSONObject) -> Unit) {
        if (!senderAllowed(sender)) { reply(JSONObject().put("success", false)); return }
        HaAuth.signOut("logged out in the frontend") { ok -> reply(JSONObject().put("success", ok)) }
    }

    override fun onDestroy() {
        HaAuth.removeListener(authListener)
        overlays.forEach { it.destroy() }
        HaAuth.threadJoin.removeListener(threadJoinListener)
        SetupSignals.removeListener(setupSignalListener)
        wifiStatus.observe(null)
        if (ImprovController.inlineHost === improvHost) {
            lastImprovInline = false
            ImprovController.inlineHost = null // the overlay takes over if Improv is still running
        }
        glassworks.screen.onKeepScreenOn = null
        glassworks.screen.onBrightness = null
        glassworks.screen.onStateChanged = null
        glassworks.screen.onTestDashboard = null
        glassworks.screen.onBlackout = null
        glassworks.screen.stayOn(NOT_READY, false)
        glassworks.screen.stayOn(SETUP_NO_TOUCH, false)
        glassworks.commandSink = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        /**
         * Drops [intent]'s extras unless [trusted] (AppParts.trustsExtras), so an entry point other apps can use
         * changes no setting (#23). Returns whether it dropped any. Public for tests.
         */
        fun screenExtras(intent: Intent, trusted: (Intent) -> Boolean): Boolean {
            if (intent.extras == null || trusted(intent)) return false
            Log.w("Glassworks", "ignored the extras of a start through ${intent.component?.shortClassName}: settings change only through adb")
            intent.replaceExtras(null as Bundle?)
            return true
        }

        /**
         * The activity's requested orientation for a frame's ([dev.glassworks.frame.Frame.orientation]). Public for tests.
         * [Orientation.PORTRAIT_FIXED] is plain `PORTRAIT`: AOSP 6.0.1's `PhoneWindowManager` gives it the display's
         * portrait rotation whatever auto-rotate says (only a rotation lock on the other portrait overrides it). On a
         * landscape panel (the Mirror's 1920x1080) that is rotation 3, the Mirror's upright, unless the image sets
         * `config_reverseDefaultRotation`; then it is 1 and this would be `REVERSE_PORTRAIT` (#686, needs the Mirror's proof).
         */
        fun screenOrientation(orientation: Orientation): Int = when (orientation) {
            Orientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            Orientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            Orientation.ANY -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
            Orientation.PORTRAIT_FIXED -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

        /** Onboarding/dashboard background (#0a0b0f), also used to cover the page until first paint. */
        private const val BACKGROUND = 0xFF0A0B0F.toInt()
        private const val TAG = "Glassworks"
        private const val REQ_STARTUP = 1
        private const val REQ_GECKO = 2
        private const val REQ_HOTSPOT = 3
        private const val RETRY_MS = 10_000L
        /** The connecting screen waits this long after the page loaded for the page to say it's drawn. Public for tests. */
        const val READY_FALLBACK_MS = 15_000L
        /** ... and this long while Home Assistant's frontend says it is still loading. Public for tests. */
        const val LAUNCH_FALLBACK_MS = 45_000L
        private const val SETUP_TICK_MS = 2_000L
        private const val SETTINGS_TICK_MS = 2_000L
        /** The [dev.glassworks.screen.ScreenPolicy.stayOn] reason while setup or the connecting screen is up (#449). */
        private const val NOT_READY = "not ready"
        /** Above every layer, the camera's dot (30 dp) included. */
        private const val BLACKOUT_ELEVATION_DP = 40f
        private const val SETUP_NO_TOUCH = "setup without touch"
        private const val STATE_SETUP_UP = "dev.glassworks.setup_up"
        private const val STATE_RESTARTED_IN_SETUP = "dev.glassworks.restarted_in_setup"
        private const val STATE_PID = "dev.glassworks.pid"
    }
}
