package dev.glassworks

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.glassworks.engine.Engine
import dev.glassworks.frame.Frame
import dev.glassworks.frame.GenericAndroid
import dev.glassworks.frame.Input
import dev.glassworks.frame.NamedBy
import dev.glassworks.manage.ManagePage
import dev.glassworks.manage.PanelCode
import dev.glassworks.pane.CantRun
import dev.glassworks.pane.Pane
import dev.glassworks.pane.PaneDefinition
import dev.glassworks.pane.SetupStep
import dev.glassworks.platform.AppBuild
import dev.glassworks.platform.HttpsTrust
import dev.glassworks.platform.Platform
import dev.glassworks.screen.PresenceRelay
import dev.glassworks.screen.ScreenPolicy
import dev.glassworks.auth.HaTokenClient
import dev.glassworks.auth.SignInServer
import dev.glassworks.discovery.HaDiscovery
import dev.glassworks.device.DeviceHost
import dev.glassworks.device.PanelDevice
import dev.glassworks.setup.SetupFlow
import dev.glassworks.setup.PanesStep
import dev.glassworks.setup.SetupPane
import dev.glassworks.setup.SetupPrefs
import dev.glassworks.setup.SetupState
import dev.glassworks.setup.ThisDeviceStep
import dev.glassworks.update.ReleaseLine
import dev.glassworks.update.SilentUpdates
import dev.glassworks.update.SilentUpdatesStep
import dev.glassworks.update.UpdateManager
import dev.glassworks.assist.OrbBakes

/**
 * The [Glassworks] an app runs: what its components hand each other while the process runs. The app owns
 * the one instance and gives it its engine and its parts ([app]): its panes, where their settings live, its
 * activity and its Manage page.
 */
class GlassworksRuntime(
    /** The device this app runs on, made once with the runtime: the S1's, or the generic one (KioskApp). */
    override val frame: Frame,
    /** The APK's build ([KioskApp.build]): its version, for everything that reports or compares it. */
    override val build: AppBuild,
    /** The web engine that shows the dashboard, made by the app (GeckoView for the S1, KioskApp). */
    override val engine: Engine,
    /** What the app adds: its panes, where their settings live, its activity and its Manage page. */
    val app: AppParts,
    /** The frame whose panes join the app's ([paneDefinitions]): [frame] in the app. Tests pass a fake one. */
    private val paneFrame: Frame = frame,
    /** The app's own releases, for its updates (#23). Required: an app that left it out would update to another app's APK. */
    override val releases: ReleaseLine,
) : Glassworks {
    init {
        // Before any component opens a connection: Android 6-7.0 lack the root GitHub's downloads chain to (#456).
        // install() never throws: a failure leaves HTTPS as the system has it, and the app starts.
        HttpsTrust.install()
            .onSuccess { if (it) Log.i(TAG, "HTTPS: added ${HttpsTrust.roots.size} root(s) the system store lacks") }
            .onFailure { Log.w(TAG, "HTTPS: couldn't add the bundled roots; downloads from hosts on them will fail", it) }
    }

    override val presence = PresenceRelay(frame.presence)

    override val screen = ScreenPolicy(
        presence, frame.light, app.components.admin, settings = ::KioskPrefs,
        logTag = "Glassworks.Screen", wakeLockTag = "glassworks:wake", sleepMode = frame.sleepMode,
    )

    private var definitions: List<PaneDefinition>? = null

    override val orbs = OrbBakes()

    private var masterVolume: dev.glassworks.audio.MasterVolume? = null

    override fun volume(context: Context): dev.glassworks.audio.MasterVolume = synchronized(this) {
        masterVolume ?: dev.glassworks.audio.MasterVolume.of(context).also { masterVolume = it }
    }

    /**
     * The app's panes in the order the settings list them, with the frame's among them, made once. A pane the
     * frame lacks the hardware for is [CantRun].
     */
    override fun paneDefinitions(context: Context): List<PaneDefinition> = synchronized(this) {
        definitions ?: run {
            val ctx = context.applicationContext
            migrateDeviceSteps(ctx)
            app.paneDefinitions(ctx, this, paneFrame.paneDefinitions(ctx)).map { CantRun.unless(it, frame.provides) }
        }.also { definitions = it }
    }

    /**
     * The panes' setup steps, in order, then the device's own ([deviceSetupSteps]), for the setup screen and the setup
     * channel. The only place that reads PaneDefinition.setupSteps (FramePanesTest checks). A step setup can't take
     * (SetupFlow.usable: a clashing id or request name) is left out with an error in the log, so a bad
     * pane never stops the app from starting.
     */
    override fun setupSteps(context: Context): List<SetupStep> = setupPanes(context).flatMap { it.steps } + deviceSetupSteps(context)

    private var deviceSteps: List<SetupStep>? = null

    /** The frame's screen takes a tap: read once, when setup first asks (a binder call). */
    private val touch by lazy { frame.input == Input.TOUCH }

    /**
     * This device (#403), only on a touch screen with no frame of its own (a tablet: GenericAndroid), then Silent updates
     * (#422): shown during setup whenever updates ask for a tap and there's a way to change that. The Silent updates
     * answer is read on every ask, at most once a second, once [UpdateManager] has read the frame's root update service;
     * a new answer goes to Home Assistant at once (#666).
     */
    override fun deviceSetupSteps(context: Context): List<SetupStep> = synchronized(this) {
        deviceSteps ?: run {
            val ctx = context.applicationContext
            val prefs = KioskPrefs(ctx)
            val silent = SilentUpdates.Fresh { updates?.let { u -> u.rootService?.let { SilentUpdates.read(ctx, it) }?.also(u::noticeSilent) } }
            listOfNotNull(
                // Not on a device a frame recognises (the Mirror): its recipe sets both, whatever input it reports.
                thisDevice(ctx).takeIf { frame is GenericAndroid && frame.namedBy != NamedBy.FRAME && touch },
                SilentUpdatesStep(silent, { prefs.silentUpdatesAnswered }, { prefs.silentUpdatesAnswered = true }, { touch }),
            )
        }.also { deviceSteps = it }
    }

    /**
     * The home screen and the device admin a Kiln install grants, asked for on the tablet instead (#403): Android's home
     * role or home settings, and its "Activate device admin app?" dialog with what Glassworks wants it for.
     */
    private fun thisDevice(ctx: Context): ThisDeviceStep {
        val setup = SetupPrefs(ctx)
        val admin = ComponentName(ctx, app.components.admin)
        return ThisDeviceStep(
            grants = { ThisDeviceStep.Grants(Platform.isHomeApp(ctx), GenericAndroid.adminActive(ctx)) },
            answered = { setup.thisDeviceAnswered },
            markAnswered = { setup.thisDeviceAnswered = true },
            askHome = { c -> (c as? Activity)?.let { Platform.askHome(it, HOME_REQUEST) } == true },
            askAdmin = { c ->
                runCatching {
                    val ask = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                        .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                        .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, ThisDeviceStep.ADMIN_EXPLANATION)
                    c.startActivity(if (c is Activity) ask else ask.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.isSuccess
            },
        )
    }

    /**
     * Once per install of a build with the device's own steps: a panel that was set up before never sees them, as with
     * the radio's step. Runs with the pane definitions, which the app makes before discovery can save a Home Assistant
     * URL in this process.
     */
    private fun migrateDeviceSteps(ctx: Context) {
        val prefs = KioskPrefs(ctx)
        val before = { SetupState.setUpBefore(prefs.haBase != null, prefs.haSeen, prefs.apiEncryption, prefs.haSetupSkipped) }
        if (!prefs.deviceStepsMigrated) {
            if (before()) prefs.silentUpdatesAnswered = true
            prefs.deviceStepsMigrated = true
        }
        val setup = SetupPrefs(ctx)
        if (!setup.thisDeviceMigrated) {
            if (before()) setup.thisDeviceAnswered = true
            setup.thisDeviceMigrated = true
        }
    }

    /**
     * The panes on setup's Panes step, in definition order: each card with its pane's usable steps
     * (SetupFlow.usable). A pane whose steps have no card (PaneDefinition.setupChoice) is left out with an
     * error in the log: its steps sit under Panes only while its card is on, so without a card they could
     * never be answered on the panel.
     */
    override fun setupPanes(context: Context): List<SetupPane> {
        val definitions = paneDefinitions(context)
        // The device's own steps go first, so a pane's step can't take their id or request names (CodeRabbit on #513).
        val kept = SetupFlow.usable(deviceSetupSteps(context) + definitions.flatMap { it.setupSteps }) { Log.e(TAG, it) }.toSet()
        return definitions.mapNotNull { d ->
            val steps = d.setupSteps.filter { it in kept }
            val card = d.setupChoice
            if (card == null) {
                if (steps.isNotEmpty()) Log.e(TAG, "Setup steps of pane '${d.id}' left out: it has no card on the Panes step (setupChoice)")
                null
            } else SetupPane(card, steps, d.id)
        }
    }

    private var panesStep: PanesStep? = null

    override fun panesStep(context: Context): PanesStep = synchronized(this) {
        panesStep ?: PanesStep(setupPanes(context)).also { panesStep = it }
    }

    @Volatile override var cameraLeaving = false
    @Volatile override var onCameraLeaving: (() -> Unit)? = null

    @Volatile override var panes: List<Pane> = emptyList()

    @Volatile override var discovery: HaDiscovery? = null

    /** Made on first use (the activity's or the service's), which starts its timeout; on the main thread's handler. */
    override val afterFirstFrame: AfterFirstFrame by lazy {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        AfterFirstFrame(
            schedule = { delayMs, task -> main.postDelayed(task, delayMs) },
            log = { Log.i(TAG, it) },
            warn = { msg, e -> Log.w(TAG, msg, e) },
            now = android.os.SystemClock::uptimeMillis,
        )
    }
    @Volatile override var setupHotspot: dev.glassworks.setup.SetupHotspot? = null
    @Volatile override var lanSetup: dev.glassworks.setup.LanSetup? = null
    @Volatile override var hotspotScan: List<dev.glassworks.setup.WifiNetwork> = emptyList()

    @Volatile override var commandSink: ((Intent) -> Unit)? = null

    @Volatile override var updates: UpdateManager? = null

    /** Set while KioskService runs: the ESPHome device, for the Manage page's status. */
    @Volatile var device: PanelDevice? = null

    override fun homeAssistantConnected(): Boolean = device?.homeAssistantConnected() == true

    override fun docked(): Boolean? = device?.docked()

    override val panelCode = PanelCode()

    override val dashboardAccount = dev.glassworks.auth.DashboardAccount({ dev.glassworks.auth.HaAuth.stored }, dev.glassworks.auth.HaAuth::askAccount)

    @Volatile override var voiceState: String = "off"

    override var onVoiceUi: (() -> Unit)? = null

    @Volatile override var voiceCancel: (() -> Unit)? = null

    override fun managePage(context: Context, signIn: SignInServer, tokens: HaTokenClient, prefs: KioskPrefs): ManagePage =
        app.managePage(context, this, signIn, tokens, prefs)

    override val components get() = app.components

    override fun paneFile(context: Context, namespace: String) = app.paneFile(context, namespace)

    /** The app's panes for the panel's device ([PanelDevice]), in order. */
    fun devicePanes(host: DeviceHost): List<Pane> = app.devicePanes(this, host)

    private companion object {
        const val TAG = "Glassworks"

        /** The home role dialog's request code (it needs a result, which nothing reads: the step reads the role). */
        const val HOME_REQUEST = 4031
    }
}

/** The runtime the app runs, with its parts ([GlassworksRuntime.app]), for Android entry points that start them. */
val Context.glassworksRuntime: GlassworksRuntime get() = glassworks as GlassworksRuntime
