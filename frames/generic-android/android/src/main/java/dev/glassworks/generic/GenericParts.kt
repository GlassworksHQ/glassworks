/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.generic

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.FrameLayout
import dev.glassworks.manage.ManagePage
import dev.glassworks.pane.Pane
import dev.glassworks.pane.PaneDefinition
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import dev.glassworks.AppParts
import dev.glassworks.GlassworksRuntime
import dev.glassworks.KioskPrefs
import sh.edm.s1kiosk.MainActivity
import dev.glassworks.OverlayHost
import dev.glassworks.PaneOverlay
import dev.glassworks.PaneFiles
import dev.glassworks.TimerOverlay
import dev.glassworks.VoiceOverlay
import dev.glassworks.auth.HaTokenClient
import dev.glassworks.camera.CameraWiring
import dev.glassworks.auth.SignInServer
import dev.glassworks.device.DeviceHost
import dev.glassworks.manage.AppManage
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
 * need a microphone and a speaker, and the camera (#117), which needs a camera. A device without a pane's hardware
 * hides the pane (CantRun). The wiring of each pane is S1 Kiosk's (S1Panes), with the S1's own panes left out.
 */
class GenericParts : AppParts {

    /** What the voice overlay shows. Main thread. */
    var voiceUi = VoiceUi()

    /** One thread for settings work that must keep its order off the main thread (Music's volume). */
    private val settingsWorker by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "gw-settings").apply { isDaemon = true } } }

    override fun paneDefinitions(context: Context, runtime: GlassworksRuntime, frameDefinitions: List<PaneDefinition>): List<PaneDefinition> =
        listOf(AssistDefinition(paneFile(context, "assist")), SendspinDefinition(paneFile(context, "sendspin"), settingsWorker::execute)) +
            frameDefinitions + CameraWiring.definition(context, runtime, paneFile(context, "camera"))

    /** The timers (12 dp), then the voice orb (16 dp) over them, and the camera's privacy dot above the menus (30 dp). */
    override fun overlays(runtime: GlassworksRuntime, host: OverlayHost): List<PaneOverlay> {
        val activity = host.activity
        val timers = TimerOverlay(activity, runtime.orbs)
        val voice = VoiceOverlay(activity).apply {
            onCancel = { runtime.voiceCancel?.invoke() }
            onShowingChanged = { timers.voiceShowing(it) }
        }
        runtime.onVoiceUi = { voice.update(voiceUi, VoicePrefs(paneFile(activity, "assist")).overlay) }
        return listOf(
            object : PaneOverlay {
                override val view = timers
                override val layoutParams = timers.layoutParams()
                override fun screenChanged() = timers.refresh()
            },
            object : PaneOverlay {
                override val view = voice
                override val layoutParams = FrameLayout.LayoutParams(-1, -1)
                override fun destroy() { runtime.onVoiceUi = null }
            },
            CameraWiring.dot(runtime, activity),
        )
    }

    // Settings from intent extras: only a start of MainActivity itself, which takes WRITE_SECURE_SETTINGS (adb) unless
    // it is the app's own; never through the launcher alias ([LAUNCHER]), which any app can start. AppParts'
    // trustsExtras does exactly that (#409).
    override val mainActivity = MainActivity::class.java

    override fun serviceStarted(context: Context, runtime: GlassworksRuntime) {
        runtime.voiceState = if (VoicePrefs(paneFile(context, "assist")).enabled) "idle" else "off"
        voiceUi = VoiceUi(runtime.voiceState)
    }

    /** Each pane's settings where S1 Kiosk keeps them ([PaneFiles]): `kiosk.xml`, opened through SecretFiles. */
    override fun paneFile(context: Context, namespace: String): SharedPreferences {
        require(namespace in PANES) { "No settings file for pane '$namespace'" }
        return PaneFiles.open(context, namespace)
    }

    override fun managePage(context: Context, runtime: GlassworksRuntime, signIn: SignInServer, tokens: HaTokenClient, prefs: KioskPrefs): ManagePage =
        AppManage(context, runtime, signIn, tokens, prefs)

    /** A voice event (main thread): the overlay, the voice state and the screen follow it. */
    private fun onVoiceEvent(runtime: GlassworksRuntime, e: VoiceEvent) {
        voiceUi = voiceUi.on(e)
        try { runtime.onVoiceUi?.invoke() } catch (t: Throwable) { Log.w("S1Kiosk.Voice", "voice overlay failed", t) }
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
        val main = Handler(Looper.getMainLooper())
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
                runtime.screen,
                schedule = { ms, action -> exec.schedule({ action() }, ms, TimeUnit.MILLISECONDS) },
                play = { host.chimes().chime() },
                silence = host::silenceChimes,
            ),
        ) + runtime.frame.panes(paneContext, exec) + listOfNotNull(CameraWiring.pane(runtime, host))
        // Timers ride with Assist (their pane.json requires the same).
        if (assist.id in host.cantRun) return panes.filter { it !is TimersPane }
        host.cancelVoice = assist::cancel
        return panes
    }

    companion object {
        /** The launcher's and home screen's way in (AndroidManifest.xml): an alias of MainActivity with no permission. */
        const val LAUNCHER = "dev.glassworks.generic.Launcher"

        /** The panes this app has settings for. */
        val PANES = setOf("assist", "sendspin", "timers", "camera")
    }
}
