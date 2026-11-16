package sh.edm.s1kiosk

import android.content.Context
import android.content.Intent
import android.util.Log
import dev.glassworks.engine.Engine
import dev.glassworks.frame.Frame
import dev.glassworks.manage.ManagePage
import dev.glassworks.manage.PanelCode
import dev.glassworks.pane.CantRun
import dev.glassworks.pane.Pane
import dev.glassworks.pane.PaneDefinition
import dev.glassworks.pane.SetupStep
import dev.glassworks.platform.AppBuild
import dev.glassworks.screen.PresenceRelay
import dev.glassworks.screen.ScreenPolicy
import sh.edm.s1kiosk.auth.HaTokenClient
import sh.edm.s1kiosk.auth.SignInServer
import sh.edm.s1kiosk.discovery.HaDiscovery
import sh.edm.s1kiosk.esphome.DeviceHost
import sh.edm.s1kiosk.esphome.PanelDevice
import sh.edm.s1kiosk.setup.SetupFlow
import sh.edm.s1kiosk.setup.SetupPane
import sh.edm.s1kiosk.update.ReleaseLine
import sh.edm.s1kiosk.update.UpdateManager
import sh.edm.s1kiosk.voice.OrbBakes

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
    override val presence = PresenceRelay(frame.presence)

    override val screen = ScreenPolicy(
        presence, frame.light, AdminReceiver::class.java, settings = ::KioskPrefs,
        logTag = "S1Kiosk.Screen", wakeLockTag = "s1kiosk:wake",
    )

    private var definitions: List<PaneDefinition>? = null

    override val orbs = OrbBakes()

    /**
     * The app's panes in the order the settings list them, with the frame's among them, made once. A pane the
     * frame lacks the hardware for is [CantRun].
     */
    override fun paneDefinitions(context: Context): List<PaneDefinition> = synchronized(this) {
        definitions ?: run {
            val ctx = context.applicationContext
            app.paneDefinitions(ctx, this, paneFrame.paneDefinitions(ctx)).map { CantRun.unless(it, frame.provides) }
        }.also { definitions = it }
    }

    /**
     * The panes' setup steps, in order, for the setup screen and the setup channel. The only place that
     * reads PaneDefinition.setupSteps (FramePanesTest checks). A step setup can't take
     * (SetupFlow.usable: a clashing id or request name) is left out with an error in the log, so a bad
     * pane never stops the app from starting.
     */
    override fun setupSteps(context: Context): List<SetupStep> = setupPanes(context).flatMap { it.steps }

    /**
     * The panes on setup's Panes step, in definition order: each card with its pane's usable steps
     * (SetupFlow.usable). A pane whose steps have no card (PaneDefinition.setupChoice) is left out with an
     * error in the log: its steps sit under Panes only while its card is on, so without a card they could
     * never be answered on the panel.
     */
    override fun setupPanes(context: Context): List<SetupPane> {
        val definitions = paneDefinitions(context)
        val kept = SetupFlow.usable(definitions.flatMap { it.setupSteps }) { Log.e(TAG, it) }.toSet()
        return definitions.mapNotNull { d ->
            val steps = d.setupSteps.filter { it in kept }
            val card = d.setupChoice
            if (card == null) {
                if (steps.isNotEmpty()) Log.e(TAG, "Setup steps of pane '${d.id}' left out: it has no card on the Panes step (setupChoice)")
                null
            } else SetupPane(card, steps)
        }
    }

    @Volatile override var cameraLeaving = false
    @Volatile override var onCameraLeaving: (() -> Unit)? = null

    @Volatile override var panes: List<Pane> = emptyList()

    @Volatile override var discovery: HaDiscovery? = null

    @Volatile override var commandSink: ((Intent) -> Unit)? = null

    @Volatile override var updates: UpdateManager? = null

    /** Set while KioskService runs: the ESPHome device, for the Manage page's status. */
    @Volatile var device: PanelDevice? = null

    override fun homeAssistantConnected(): Boolean = device?.homeAssistantConnected() == true

    override val panelCode = PanelCode()

    override val dashboardAccount = sh.edm.s1kiosk.auth.DashboardAccount({ sh.edm.s1kiosk.auth.HaAuth.stored }, sh.edm.s1kiosk.auth.HaAuth::askAccount)

    @Volatile override var voiceState: String = "off"

    override var onVoiceUi: (() -> Unit)? = null

    @Volatile override var voiceCancel: (() -> Unit)? = null

    override fun managePage(context: Context, signIn: SignInServer, tokens: HaTokenClient, prefs: KioskPrefs): ManagePage =
        app.managePage(context, this, signIn, tokens, prefs)

    override val mainActivity get() = app.mainActivity

    override fun paneFile(context: Context, namespace: String) = app.paneFile(context, namespace)

    /** The app's panes for the panel's device ([PanelDevice]), in order. */
    fun devicePanes(host: DeviceHost): List<Pane> = app.devicePanes(this, host)

    private companion object {
        const val TAG = "S1Kiosk"
    }
}

/** The runtime the app runs, with its parts ([GlassworksRuntime.app]), for Android entry points that start them. */
val Context.glassworksRuntime: GlassworksRuntime get() = glassworks as GlassworksRuntime
