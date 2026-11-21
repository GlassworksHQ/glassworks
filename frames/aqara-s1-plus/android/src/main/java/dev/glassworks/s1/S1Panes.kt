package dev.glassworks.s1

import dev.glassworks.AppComponents
import sh.edm.s1kiosk.update.UpdateInstallReceiver

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.FrameLayout
import dev.glassworks.bluetoothproxy.AndroidProxyRadio
import dev.glassworks.bluetoothproxy.BluetoothProxyDefinition
import dev.glassworks.diagnostics.DiagnosticsDefinition
import dev.glassworks.diagnostics.DiagnosticsPane
import dev.glassworks.manage.ManagePage
import dev.glassworks.notifications.HaImages
import dev.glassworks.notifications.NotificationScreen
import dev.glassworks.notifications.NotificationSounds
import dev.glassworks.notifications.NotificationsDefinition
import dev.glassworks.notifications.NotificationsHub
import dev.glassworks.notifications.NotificationsPane
import dev.glassworks.pane.Pane
import dev.glassworks.pane.PaneDefinition
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import dev.glassworks.auth.FrontendUrls
import dev.glassworks.auth.HaAuth
import dev.glassworks.auth.HaTokenClient
import dev.glassworks.auth.SignInServer
import dev.glassworks.camera.CameraWiring
import dev.glassworks.device.DeviceHost
import dev.glassworks.s1.manage.S1Manage
import dev.glassworks.notifications.NotificationOverlay
import dev.glassworks.radio.RadioDefinition
import dev.glassworks.speaker.SendspinDefinition
import dev.glassworks.speaker.SendspinPane
import dev.glassworks.bluetoothproxy.BluetoothProxyPage
import dev.glassworks.setup.SetupState
import dev.glassworks.timers.TimersPane
import dev.glassworks.assist.AssistDefinition
import dev.glassworks.assist.AssistPane
import dev.glassworks.assist.SpeakerMedia
import dev.glassworks.voice.VoiceEngine
import dev.glassworks.voice.VoiceEvent
import dev.glassworks.assist.VoiceLevels
import dev.glassworks.assist.VoiceShowing
import dev.glassworks.assist.VoiceUi
import dev.glassworks.assist.chime
import dev.glassworks.assist.wakeChime
import dev.glassworks.AppParts
import dev.glassworks.GlassworksRuntime
import dev.glassworks.KioskPrefs
import dev.glassworks.OverlayHost
import dev.glassworks.PaneOverlay
import dev.glassworks.TimerOverlay
import dev.glassworks.EdgeGlowOverlay
import dev.glassworks.VoiceOverlay
import dev.glassworks.glassworks
import sh.edm.s1kiosk.AdminReceiver
import sh.edm.s1kiosk.MainActivity

/**
 * The S1 app's parts ([AppParts]): its panes' definitions and what each pane is built with for the panel's
 * device, in order, plus the panes' state its shell reads ([notifications], [voiceUi]).
 */
class S1Panes : AppParts {

    /** The notifications pane's state, for the overlay and the settings page (#118). */
    val notifications = NotificationsHub()

    /** What the voice overlay shows (state, heard text, reply). Main thread. */
    var voiceUi = VoiceUi()

    /**
     * One thread for the settings pages' work that must not run on the main thread and must keep its order
     * (Music's volume, sent to Music Assistant). Made on first use; idle, it is one parked daemon thread.
     */
    private val settingsWorker by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "s1-settings").apply { isDaemon = true } } }

    /**
     * The core's panes in the order the settings list them (voice, music, notifications, radio, Bluetooth
     * proxy), then the frame's, then the camera, then diagnostics.
     */
    override fun paneDefinitions(context: Context, runtime: GlassworksRuntime, frameDefinitions: List<PaneDefinition>): List<PaneDefinition> {
        val prefs = KioskPrefs(context)
        val panePrefs = PanePrefs(context)
        val radio = RadioDefinition.app(
            panePrefs.radio,
            setUpBefore = { SetupState.setUpBefore(prefs.haBase != null, prefs.haSeen, prefs.apiEncryption, prefs.haSetupSkipped) },
            agent = runtime.frame.agent,
        ) { prefs.haBase }
        val notify = NotificationsDefinition(
            panePrefs.paneFile("notifications"), notifications,
            nodeName = { runtime.device?.nodeName },
            now = SystemClock::elapsedRealtime,
        )
        // It requires a camera: without one in the frame's hardware it is CantRun, as every pane that lacks its own.
        val camera = CameraWiring.definition(context, runtime, panePrefs.paneFile("camera"))
        // The Bluetooth adapter's MAC comes from the frame agent (#246); without one there is none, and Home
        // Assistant keys the proxy on the panel's ESPHome MAC, as before.
        val bluetoothProxy = BluetoothProxyDefinition(
            panePrefs.paneFile("bluetooth-proxy"), AndroidProxyRadio(context),
            readAdapterMac = runtime.frame.agent::bluetoothAddress, page = ::BluetoothProxyPage,
        )
        return listOf(AssistDefinition(panePrefs.paneFile("assist")), SendspinDefinition(panePrefs.paneFile("sendspin"), settingsWorker::execute), notify, radio, bluetoothProxy) +
            frameDefinitions + camera + DiagnosticsDefinition(panePrefs.paneFile("diagnostics"))
    }

    /**
     * The S1 app's layers over the dashboard, bottom first: the timers (12 dp), the notifications (14 dp, over
     * the timers), the voice orb (16 dp), and the camera's privacy dot above the menus (30 dp).
     */
    override fun overlays(runtime: GlassworksRuntime, host: OverlayHost): List<PaneOverlay> {
        val activity = host.activity
        val panePrefs = PanePrefs(activity)
        val timers = TimerOverlay(activity, runtime.orbs)
        // Both Assist looks report whether they show; the timers hear one signal (either shows).
        val showing = VoiceShowing(timers::voiceShowing)
        val voice = VoiceOverlay(activity).apply {
            onCancel = { runtime.voiceCancel?.invoke() }
            onShowingChanged = showing::orb // a timer set by voice flies out of the orb
        }
        val notes = NotificationOverlay(activity, notifications, notificationImages(activity), orbs = runtime.orbs).apply {
            screenOn = { runtime.screen.screenOn }
            input = runtime.frame.input
            onPage = host::openInPage
            onPageArea = host::placePage
            capturePage = host::capturePage
            onDoNotDisturb = { on -> panePrefs.notifications.doNotDisturb = on }
        }
        val listener: (NotificationsHub.State) -> Unit = { s -> host.post { notes.update(s) } }
        notifications.addListener(listener)
        notifications.onOpenList = { host.post { host.hideMenu(); notes.openList() } }
        notifications.onReveal = { host.post { host.hideMenu() } }
        // The Assist look picked on the Appearance page (#691): the orb, or Spark's edge glow. The other one hides.
        val glow = EdgeGlowOverlay(activity).apply {
            onCancel = { runtime.voiceCancel?.invoke() }
            onShowingChanged = showing::glow
        }
        val kiosk = KioskPrefs(activity)
        runtime.onVoiceUi = {
            val enabled = panePrefs.voice.overlay
            val edge = kiosk.assistLook == KioskPrefs.ASSIST_GLOW
            voice.update(voiceUi, enabled && !edge)
            glow.update(voiceUi, enabled && edge)
        }
        return listOf(
            object : PaneOverlay {
                override val view = timers
                override val layoutParams = timers.layoutParams()
                override fun screenChanged() = timers.refresh()
            },
            object : PaneOverlay {
                override val view = notes
                override val layoutParams = FrameLayout.LayoutParams(-1, -1)
                override fun screenChanged() = notes.update(notifications.state)
                override fun back() = notes.back()
                override fun destroy() {
                    notifications.removeListener(listener)
                    notifications.onOpenList = null
                    notifications.onReveal = null
                }
            },
            object : PaneOverlay {
                override val view = voice
                override val layoutParams = FrameLayout.LayoutParams(-1, -1)
                override fun destroy() { runtime.onVoiceUi = null }
            },
            object : PaneOverlay {
                override val view = glow
                override val layoutParams = FrameLayout.LayoutParams(-1, -1)
            },
            // Images leaving the panel (#117): the dot follows, on the main thread (the device posts each change).
            CameraWiring.dot(runtime, activity),
        )
    }

    /**
     * Pictures from the panel's own HA, with the dashboard's sign-in. Only when that sign-in belongs to
     * the same HA, so the token never goes to another host.
     */
    private fun notificationImages(context: Context): HaImages {
        val prefs = KioskPrefs(context)
        return HaImages(
            haBase = { prefs.haBase },
            token = token@{
                if (!HaAuth.signedIn || !FrontendUrls.sameOrigin(HaAuth.signedInBase, prefs.haBase)) return@token null
                val done = CountDownLatch(1)
                var token: String? = null
                HaAuth.accessToken(force = false) { a -> token = (a as? HaAuth.Access.Ok)?.accessToken; done.countDown() }
                done.await(10, TimeUnit.SECONDS)
                token
            },
        )
    }

    // The subclasses at the names panels, Kiln and the root helper know (#22); KioskService is the core's own (#527).
    override val components = AppComponents(MainActivity::class.java, dev.glassworks.KioskService::class.java, AdminReceiver::class.java, UpdateInstallReceiver::class.java)

    /** The direct-boot splash (our look before unlock at the next boot): on only while it's safe. */
    override fun activityStarted(context: Context) = BootSplashActivity.sync(context)

    /** The voice state starts as the assistant's switch says; the overlay starts from it. */
    override fun serviceStarted(context: Context, runtime: GlassworksRuntime) {
        runtime.voiceState = if (PanePrefs(context).voice.enabled) "idle" else "off"
        voiceUi = VoiceUi(runtime.voiceState)
    }

    override fun paneFile(context: Context, namespace: String) = PanePrefs.file(context, namespace)

    override fun managePage(context: Context, runtime: GlassworksRuntime, signIn: SignInServer, tokens: HaTokenClient, prefs: KioskPrefs): ManagePage =
        S1Manage(context, runtime, signIn, tokens, prefs)

    /** A voice event from the assistant (main thread): the voice overlay, the voice state and the screen follow it. */
    fun onVoiceEvent(runtime: GlassworksRuntime, e: VoiceEvent) {
        voiceUi = voiceUi.on(e)
        try { runtime.onVoiceUi?.invoke() } catch (t: Throwable) { Log.w("Glassworks.Voice", "voice overlay failed", t) }
        if (e is VoiceEvent.Heard || e is VoiceEvent.Reply) return // texts only, no state change
        runtime.voiceState = voiceUi.state
        Log.i("Glassworks.Voice", "voice: ${runtime.voiceState}")
        // Talking to the panel counts as activity: wake the screen and keep it on.
        if (e !is VoiceEvent.Idle && e !is VoiceEvent.Error) runtime.screen.wake("voice")
        runtime.screen.onStateChanged?.invoke()
    }

    /** What each pane is built with for the panel's device, in the order the device starts them and lists their entities. */
    override fun devicePanes(runtime: GlassworksRuntime, host: DeviceHost): List<Pane> {
        val paneContext = host.paneContext
        val ctx = paneContext.context
        val prefs = KioskPrefs(ctx)
        val panePrefs = PanePrefs(ctx)
        val exec = host.exec
        val screen = runtime.screen
        val main = Handler(Looper.getMainLooper())
        val ui = { block: () -> Unit -> main.post(block); Unit }
        // The assistant's speaking pulse on the voice overlay follows the voice player.
        host.onVoicePlaying = { VoiceLevels.ttsPlaying = it }
        val assistPane = AssistPane(
            paneContext,
            player = host.voicePlayer,
            speaker = object : SpeakerMedia {
                override val playing get() = host.mediaPlayer.isPlaying
                override val announcing get() = host.announcing
                override fun pause() = host.mediaPlayer.pause()
                override fun resume() = host.mediaPlayer.resume()
            },
            resolveUrl = host::resolveUrl,
            wakeChime = { host.chimes().wakeChime() },
            onVoiceEvent = { e -> main.post { onVoiceEvent(runtime, e) } },
            createInput = { cfg, model, onModelChanged -> VoiceEngine(ctx, cfg, model, onModelChanged) },
        )
        var panes = listOf(
            SendspinPane(paneContext, haBase = { prefs.haBase }),
            assistPane,
            // Timers only come from the voice assistant: without it the pane has nothing to do.
            TimersPane(
                paneContext,
                screen,
                schedule = { ms, action -> exec.schedule({ action() }, ms, TimeUnit.MILLISECONDS) },
                play = { host.chimes().chime() },
                silence = host::silenceChimes,
            ),
            NotificationsPane(
                paneContext, panePrefs.notifications, notifications,
                screen = object : NotificationScreen {
                    override fun wake() = ui { screen.wake("notification") }
                    override fun hold() = ui { screen.hold("notification") }
                    override fun release() = ui { screen.release("notification") }
                },
                play = { sound, done ->
                    val pcm = NotificationSounds.pcm(sound)
                    if (pcm == null) done() else host.chimes().run { begin(pcm.rate, 2, 1); write(pcm.data); end(done) }
                },
                silence = host::silenceChimes,
                schedule = { ms, action -> exec.schedule({ action() }, ms, TimeUnit.MILLISECONDS) },
                haBase = { prefs.haBase },
                now = SystemClock::elapsedRealtime,
                touch = { runtime.frame.input == dev.glassworks.frame.Input.TOUCH },
            ),
        ) + runtime.frame.panes(paneContext, exec) +
            runtime.paneDefinitions(ctx).filterIsInstance<RadioDefinition>().map { it.pane(paneContext, panelIp = host::ipAddress) } +
            runtime.paneDefinitions(ctx).filterIsInstance<BluetoothProxyDefinition>().map { it.pane(paneContext, exec) } +
            listOfNotNull(CameraWiring.pane(runtime, host)) +
            DiagnosticsPane(paneContext, runtime.frame.cpuThermalZone, runtime.engine::isContentProcess, exec)
        // Timers ride with Assist (their pane.json requires the same): without it they go too.
        if (assistPane.id in host.cantRun) panes = panes.filter { it !is TimersPane }
        else host.cancelVoice = assistPane::cancel
        return panes
    }
}
