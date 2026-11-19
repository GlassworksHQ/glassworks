/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.generic

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.FrameLayout
import dev.glassworks.manage.ManagePage
import dev.glassworks.pane.Pane
import dev.glassworks.pane.PaneDefinition
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import dev.glassworks.AppParts
import dev.glassworks.GlassworksRuntime
import dev.glassworks.KioskPrefs
import dev.glassworks.AppComponents
import dev.glassworks.AdminReceiver
import dev.glassworks.KioskService
import dev.glassworks.MainActivity
import dev.glassworks.update.UpdateInstallReceiver
import dev.glassworks.OverlayHost
import dev.glassworks.PaneOverlay
import dev.glassworks.PaneFiles
import dev.glassworks.TimerOverlay
import dev.glassworks.VoiceOverlay
import dev.glassworks.auth.FrontendUrls
import dev.glassworks.auth.HaAuth
import dev.glassworks.auth.HaTokenClient
import dev.glassworks.bluetoothproxy.AndroidProxyRadio
import dev.glassworks.bluetoothproxy.BluetoothProxyDefinition
import dev.glassworks.bluetoothproxy.BluetoothProxyPage
import dev.glassworks.camera.CameraWiring
import dev.glassworks.diagnostics.DiagnosticsDefinition
import dev.glassworks.diagnostics.DiagnosticsPane
import dev.glassworks.auth.SignInServer
import dev.glassworks.device.DeviceHost
import dev.glassworks.frame.Input
import dev.glassworks.manage.AppManage
import dev.glassworks.notifications.HaImages
import dev.glassworks.notifications.NotificationOverlay
import dev.glassworks.notifications.NotificationScreen
import dev.glassworks.notifications.NotificationSounds
import dev.glassworks.notifications.NotificationsDefinition
import dev.glassworks.notifications.NotificationsHub
import dev.glassworks.notifications.NotificationsPane
import dev.glassworks.notifications.NotificationsPrefs
import dev.glassworks.speaker.SendspinDefinition
import dev.glassworks.speaker.SendspinPane
import dev.glassworks.timers.TimersPane
import dev.glassworks.assist.AssistDefinition
import dev.glassworks.assist.AssistPane
import dev.glassworks.assist.SpeakerMedia
import dev.glassworks.voice.VoiceEngine
import dev.glassworks.voice.VoiceEvent
import dev.glassworks.assist.VoiceLevels
import dev.glassworks.assist.VoicePrefs
import dev.glassworks.assist.VoiceUi
import dev.glassworks.assist.chime
import dev.glassworks.assist.wakeChime

/**
 * The generic app's parts ([AppParts]): its fixed set of panes (#23), Assist with its timers and Sendspin, which
 * need a microphone and a speaker, notifications, which need nothing, the Bluetooth proxy, which needs Bluetooth LE,
 * the camera (#117), which needs a camera, and diagnostics, which needs nothing. A device without a pane's hardware
 * hides the pane (CantRun). The wiring of each pane is the S1 app's (S1Panes), with the S1's own panes left out
 * (the radio, the wall base, the time zone) and no root helper for the Bluetooth adapter's MAC.
 */
class GenericParts : AppParts {

    /** The notifications pane's state, for the overlay and the settings page (#118). */
    val notifications = NotificationsHub()

    /** What the voice overlay shows. Main thread. */
    var voiceUi = VoiceUi()

    /** One thread for settings work that must keep its order off the main thread (Music's volume). */
    private val settingsWorker by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "gw-settings").apply { isDaemon = true } } }

    /**
     * In the S1 app's order (S1Panes): voice, music, notifications, the Bluetooth proxy, then the frame's, the camera
     * and diagnostics.
     */
    override fun paneDefinitions(context: Context, runtime: GlassworksRuntime, frameDefinitions: List<PaneDefinition>): List<PaneDefinition> {
        val notify = NotificationsDefinition(
            paneFile(context, "notifications"), notifications,
            nodeName = { runtime.device?.nodeName },
            now = SystemClock::elapsedRealtime,
        )
        // No root helper reads the adapter's MAC here: Home Assistant keys the proxy on the ESPHome MAC (AdapterMac).
        val bluetoothProxy = BluetoothProxyDefinition(paneFile(context, "bluetooth-proxy"), AndroidProxyRadio(context), page = ::BluetoothProxyPage)
        return listOf(AssistDefinition(paneFile(context, "assist")), SendspinDefinition(paneFile(context, "sendspin"), settingsWorker::execute), notify, bluetoothProxy) +
            frameDefinitions + CameraWiring.definition(context, runtime, paneFile(context, "camera")) +
            DiagnosticsDefinition(paneFile(context, "diagnostics"))
    }

    /**
     * The timers (12 dp), the notifications over them (14 dp), the voice orb (16 dp), and the camera's privacy dot
     * above the menus (30 dp), as in the S1 app.
     */
    override fun overlays(runtime: GlassworksRuntime, host: OverlayHost): List<PaneOverlay> {
        val activity = host.activity
        val timers = TimerOverlay(activity, runtime.orbs)
        val voice = VoiceOverlay(activity).apply {
            onCancel = { runtime.voiceCancel?.invoke() }
            onShowingChanged = { timers.voiceShowing(it) }
        }
        val notes = NotificationOverlay(activity, notifications, notificationImages(activity), orbs = runtime.orbs).apply {
            screenOn = { runtime.screen.screenOn }
            input = runtime.frame.input
            onPage = host::openInPage
            onPageArea = host::placePage
            capturePage = host::capturePage
            onDoNotDisturb = { on -> NotificationsPrefs(paneFile(activity, "notifications")).doNotDisturb = on }
        }
        val listener: (NotificationsHub.State) -> Unit = { s -> host.post { notes.update(s) } }
        notifications.addListener(listener)
        notifications.onOpenList = { host.post { host.hideMenu(); notes.openList() } }
        notifications.onReveal = { host.post { host.hideMenu() } }
        runtime.onVoiceUi = { voice.update(voiceUi, VoicePrefs(paneFile(activity, "assist")).overlay) }
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
            CameraWiring.dot(runtime, activity),
        )
    }

    /**
     * Pictures from the panel's own HA, with the dashboard's sign-in, as in the S1 app: only when that sign-in belongs
     * to the same HA, so the token never goes to another host.
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

    // Settings from intent extras: only a start of MainActivity itself, which takes WRITE_SECURE_SETTINGS (adb) unless
    // it is the app's own; never through the launcher alias ([LAUNCHER]), which any app can start. AppParts'
    // trustsExtras does exactly that (#409).
    // The core's own classes: this app has no older names to keep (#527).
    override val components = AppComponents(MainActivity::class.java, KioskService::class.java, AdminReceiver::class.java, UpdateInstallReceiver::class.java)

    override fun serviceStarted(context: Context, runtime: GlassworksRuntime) {
        runtime.voiceState = if (VoicePrefs(paneFile(context, "assist")).enabled) "idle" else "off"
        voiceUi = VoiceUi(runtime.voiceState)
    }

    /** Each pane's settings where the S1 app keeps them ([PaneFiles]): `kiosk.xml`, opened through SecretFiles. */
    override fun paneFile(context: Context, namespace: String): SharedPreferences {
        require(namespace in PANES) { "No settings file for pane '$namespace'" }
        return PaneFiles.open(context, namespace)
    }

    override fun managePage(context: Context, runtime: GlassworksRuntime, signIn: SignInServer, tokens: HaTokenClient, prefs: KioskPrefs): ManagePage =
        AppManage(context, runtime, signIn, tokens, prefs)

    /** A voice event (main thread): the overlay, the voice state and the screen follow it. */
    private fun onVoiceEvent(runtime: GlassworksRuntime, e: VoiceEvent) {
        voiceUi = voiceUi.on(e)
        try { runtime.onVoiceUi?.invoke() } catch (t: Throwable) { Log.w("Glassworks.Voice", "voice overlay failed", t) }
        if (e is VoiceEvent.Heard || e is VoiceEvent.Reply) return
        runtime.voiceState = voiceUi.state
        if (e !is VoiceEvent.Idle && e !is VoiceEvent.Error) runtime.screen.wake("voice")
        runtime.screen.onStateChanged?.invoke()
    }

    override fun devicePanes(runtime: GlassworksRuntime, host: DeviceHost): List<Pane> {
        val paneContext = host.paneContext
        val ctx = paneContext.context
        val prefs = KioskPrefs(ctx)
        val exec = host.exec
        val screen = runtime.screen
        val main = Handler(Looper.getMainLooper())
        val ui = { block: () -> Unit -> main.post(block); Unit }
        host.onVoicePlaying = { VoiceLevels.ttsPlaying = it }
        val assist = AssistPane(
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
        val panes = listOf(
            SendspinPane(paneContext, haBase = { prefs.haBase }),
            assist,
            TimersPane(
                paneContext,
                screen,
                schedule = { ms, action -> exec.schedule({ action() }, ms, TimeUnit.MILLISECONDS) },
                play = { host.chimes().chime() },
                silence = host::silenceChimes,
            ),
            NotificationsPane(
                paneContext, NotificationsPrefs(paneFile(ctx, "notifications")), notifications,
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
                // A screen without touch (the Mirror) can't close a card: what would stay up closes by itself (#118).
                touch = { runtime.frame.input == Input.TOUCH },
            ),
        ) + runtime.frame.panes(paneContext, exec) +
            // Without Bluetooth LE the definition is CantRun, so there is no BluetoothProxyDefinition and no pane.
            runtime.paneDefinitions(ctx).filterIsInstance<BluetoothProxyDefinition>().map { it.pane(paneContext, exec) } +
            listOfNotNull(CameraWiring.pane(runtime, host)) +
            // A frame with no CPU thermal zone (any device here) has no temperature entity.
            DiagnosticsPane(paneContext, runtime.frame.cpuThermalZone, runtime.engine::isContentProcess, exec)
        // Timers ride with Assist (their pane.json requires the same).
        if (assist.id in host.cantRun) return panes.filter { it !is TimersPane }
        host.cancelVoice = assist::cancel
        return panes
    }

    companion object {
        /** The launcher's and home screen's way in (AndroidManifest.xml): an alias of MainActivity with no permission. */
        const val LAUNCHER = "dev.glassworks.generic.Launcher"

        /** The panes this app has settings for. */
        val PANES = setOf("assist", "sendspin", "timers", "notifications", "bluetooth-proxy", "camera", "diagnostics")
    }
}
