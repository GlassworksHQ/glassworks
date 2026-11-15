package sh.edm.s1kiosk

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import dev.glassworks.bluetoothproxy.AndroidProxyRadio
import dev.glassworks.bluetoothproxy.BluetoothProxyDefinition
import dev.glassworks.camera.CameraDefinition
import dev.glassworks.diagnostics.DiagnosticsDefinition
import dev.glassworks.engine.Engine
import dev.glassworks.engine.EngineSettings
import dev.glassworks.engine.gecko.GeckoEngine
import dev.glassworks.frame.Frame
import dev.glassworks.manage.ManagePage
import dev.glassworks.manage.PanelCode
import dev.glassworks.notifications.NotificationsDefinition
import dev.glassworks.notifications.NotificationsHub
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
import sh.edm.s1kiosk.frame.AqaraS1Plus
import sh.edm.s1kiosk.manage.S1Manage
import sh.edm.s1kiosk.radio.RadioDefinition
import sh.edm.s1kiosk.sendspin.SendspinDefinition
import sh.edm.s1kiosk.settings.BluetoothProxyPage
import sh.edm.s1kiosk.setup.SetupFlow
import sh.edm.s1kiosk.setup.SetupPane
import sh.edm.s1kiosk.setup.SetupState
import sh.edm.s1kiosk.update.UpdateManager
import sh.edm.s1kiosk.voice.AssistDefinition
import sh.edm.s1kiosk.voice.OrbBakes
import sh.edm.s1kiosk.voice.VoiceEvent
import sh.edm.s1kiosk.voice.VoiceUi
import java.util.concurrent.Executors

/**
 * The S1 app's [Glassworks]: what its components hand each other while the process runs. [KioskApp] owns
 * the one instance. Besides the core's parts, it holds the panes' shared state that the S1 app's shell
 * reads ([notifications], [voiceUi], [device]).
 */
class GlassworksRuntime(
    /** The device this app runs on, made once with the runtime: the S1's, or the generic one (KioskApp). */
    override val frame: Frame,
    /** The APK's build ([KioskApp.build]): its version, for everything that reports or compares it. */
    override val build: AppBuild,
    /** The frame whose panes join the core's ([paneDefinitions]): [frame] in the app. Tests pass a fake one. */
    private val paneFrame: Frame = frame,
) : Glassworks {
    /** [frame] when it is the S1's, for its parts no other frame has (root updates, the screen-off Wi-Fi scan). */
    val s1: AqaraS1Plus? get() = frame as? AqaraS1Plus

    override val presence = PresenceRelay(frame.presence)

    override val screen = ScreenPolicy(
        presence, frame.light, AdminReceiver::class.java, settings = ::KioskPrefs,
        logTag = "S1Kiosk.Screen", wakeLockTag = "s1kiosk:wake",
    )

    /**
     * GeckoView, with the kiosk's helper extension (assets/extension). Its settings are read once, when
     * MainActivity first opens a page.
     */
    override val engine: Engine = GeckoEngine(
        settings = { context -> KioskPrefs(context).run { EngineSettings(secureHosts(), zoom, remoteDebugging(build.debug)) } },
        extension = GeckoEngine.Extension("resource://android/assets/extension/", "helper@s1kiosk.edm.sh", nativeApp = "s1kiosk"),
        logTag = "S1Kiosk",
        generatedBy = "S1 Kiosk",
    )

    private var definitions: List<PaneDefinition>? = null

    /** The notifications pane's state, for the overlay and the settings page (#118). */
    val notifications = NotificationsHub()

    override val orbs = OrbBakes()

    /**
     * One thread for the settings pages' work that must not run on the main thread and must keep its order
     * (Music's volume, sent to Music Assistant). Made on first use; idle, it is one parked daemon thread.
     */
    private val settingsWorker by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "s1-settings").apply { isDaemon = true } } }

    /**
     * The core's panes in the order the settings list them (voice, music, notifications, radio, Bluetooth
     * proxy), then the frame's, then the camera, then diagnostics.
     */
    override fun paneDefinitions(context: Context): List<PaneDefinition> = synchronized(this) {
        definitions ?: run {
            val app = context.applicationContext
            val prefs = KioskPrefs(app)
            val panePrefs = PanePrefs(app)
            val radio = RadioDefinition.app(
                panePrefs.radio,
                setUpBefore = { SetupState.setUpBefore(prefs.haBase != null, prefs.haSeen, prefs.apiEncryption, prefs.haSetupSkipped) },
            ) { prefs.haBase }
            val notify = NotificationsDefinition(
                panePrefs.paneFile("notifications"), notifications,
                nodeName = { device?.nodeName },
                now = android.os.SystemClock::elapsedRealtime,
            )
            // It requires a camera: without one in the frame's hardware it is CantRun (below), as every pane that lacks its own.
            val camera = CameraDefinition(
                panePrefs.paneFile("camera"),
                granted = { app.checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED },
                pickers = { c -> sh.edm.s1kiosk.camera.CameraUseCards(c) },
            )
            val bluetoothProxy = BluetoothProxyDefinition(panePrefs.paneFile("bluetooth-proxy"), AndroidProxyRadio(app), ::BluetoothProxyPage)
            (listOf(AssistDefinition(panePrefs.paneFile("assist")), SendspinDefinition(panePrefs.paneFile("sendspin"), settingsWorker::execute), notify, radio, bluetoothProxy) +
                paneFrame.paneDefinitions(app) + camera + DiagnosticsDefinition(panePrefs.paneFile("diagnostics")))
                .map { CantRun.unless(it, frame.provides) }
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

    override val panelCode = PanelCode()

    override val dashboardAccount = sh.edm.s1kiosk.auth.DashboardAccount({ sh.edm.s1kiosk.auth.HaAuth.stored }, sh.edm.s1kiosk.auth.HaAuth::askAccount)

    @Volatile override var voiceState: String = "off"

    /** What the voice overlay shows (state, heard text, reply). Main thread. */
    var voiceUi = VoiceUi()

    override var onVoiceUi: (() -> Unit)? = null

    @Volatile override var voiceCancel: (() -> Unit)? = null

    override fun managePage(context: Context, signIn: SignInServer, tokens: HaTokenClient, prefs: KioskPrefs): ManagePage =
        S1Manage(context, this, signIn, tokens, prefs)

    override val mainActivity = MainActivity::class.java

    override fun paneFile(context: Context, namespace: String) = PanePrefs.file(context, namespace)

    /** The S1 app's panes for the panel's device ([PanelDevice]), in order. */
    fun devicePanes(host: DeviceHost): List<Pane> = S1Panes.forDevice(this, host)

    /** A voice event from the assistant (main thread): the voice overlay, the voice state and the screen follow it. */
    fun onVoiceEvent(e: VoiceEvent) {
        voiceUi = voiceUi.on(e)
        try { onVoiceUi?.invoke() } catch (t: Throwable) { Log.w("S1Kiosk.Voice", "voice overlay failed", t) }
        if (e is VoiceEvent.Heard || e is VoiceEvent.Reply) return // texts only, no state change
        voiceState = voiceUi.state
        Log.i("S1Kiosk.Voice", "voice: $voiceState")
        // Talking to the panel counts as activity: wake the screen and keep it on.
        if (e !is VoiceEvent.Idle && e !is VoiceEvent.Error) screen.wake("voice")
        screen.onStateChanged?.invoke()
    }

    private companion object {
        const val TAG = "S1Kiosk"
    }
}
