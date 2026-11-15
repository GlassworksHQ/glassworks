package sh.edm.s1kiosk

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.glassworks.bluetoothproxy.BluetoothProxyDefinition
import dev.glassworks.camera.CameraDefinition
import dev.glassworks.camera.CameraHub
import dev.glassworks.camera.CameraIdentity
import dev.glassworks.camera.CameraPane
import dev.glassworks.camera.CameraStream
import dev.glassworks.camera.FrameEgress
import dev.glassworks.camera.stillGate
import dev.glassworks.diagnostics.DiagnosticsPane
import dev.glassworks.esphome.EspHomeLog
import dev.glassworks.notifications.NotificationScreen
import dev.glassworks.notifications.NotificationSounds
import dev.glassworks.notifications.NotificationsPane
import dev.glassworks.pane.Pane
import java.util.concurrent.TimeUnit
import sh.edm.s1kiosk.esphome.DeviceHost
import sh.edm.s1kiosk.radio.RadioDefinition
import sh.edm.s1kiosk.sendspin.SendspinPane
import sh.edm.s1kiosk.timers.TimersPane
import sh.edm.s1kiosk.voice.AssistPane
import sh.edm.s1kiosk.voice.SpeakerMedia
import sh.edm.s1kiosk.voice.VoiceEngine
import sh.edm.s1kiosk.voice.VoiceLevels
import sh.edm.s1kiosk.voice.chime
import sh.edm.s1kiosk.voice.wakeChime

/**
 * The S1 app's panes for the panel's device (GlassworksRuntime.devicePanes): what each pane is built
 * with, in the order the device starts them and lists their entities.
 */
internal object S1Panes {

    fun forDevice(runtime: GlassworksRuntime, host: DeviceHost): List<Pane> {
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
            onVoiceEvent = { e -> main.post { runtime.onVoiceEvent(e) } },
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
                paneContext, panePrefs.notifications, runtime.notifications,
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
            ),
        ) + runtime.frame.panes(paneContext, exec) +
            runtime.paneDefinitions(ctx).filterIsInstance<RadioDefinition>().map { it.pane(paneContext, panelIp = host::ipAddress) } +
            runtime.paneDefinitions(ctx).filterIsInstance<BluetoothProxyDefinition>().map { it.pane(paneContext, exec) } +
            listOfNotNull(cameraPane(runtime, host, main)) +
            DiagnosticsPane(paneContext, runtime.frame.cpuThermalZone, runtime.engine::isContentProcess, exec)
        // Timers ride with Assist (their pane.json requires the same): without it they go too.
        if (assistPane.id in host.cantRun) panes = panes.filter { it !is TimersPane }
        else host.cancelVoice = assistPane::cancel
        return panes
    }

    /** The camera pane (#117), on a device with a camera only: elsewhere its definition is CantRun (#23), and this finds none. */
    private fun cameraPane(runtime: GlassworksRuntime, host: DeviceHost, main: Handler): CameraPane? {
        val paneContext = host.paneContext
        val ctx = paneContext.context
        val identity = paneContext.device
        val def = runtime.paneDefinitions(ctx).filterIsInstance<CameraDefinition>().firstOrNull() ?: return null
        val hub = CameraHub(ctx)
        host.onStop { hub.release() }
        val egress = FrameEgress(SystemClock::elapsedRealtime, { ms, r -> main.postDelayed(r, ms) }) { leaving ->
            EspHomeLog.i("Camera frames ${if (leaving) "leaving the panel" else "no longer leaving"}")
            runtime.cameraLeaving = leaving
            main.post { runtime.onCameraLeaving?.invoke() }
        }
        var pane: CameraPane? = null
        // The pane's own judgement: no still while it is off or Android doesn't allow the camera.
        val gate = stillGate(hub, { pane?.stillPolicy() ?: def.prefs.policy.copy(privacy = true) }, egress)
        val stream = CameraStream(
            ctx, hub, egress,
            identity = { CameraIdentity(identity.friendlyName, "Glassworks ${runtime.build.versionName}", identity.macAddress) },
            host = host::ipAddress,
            snapshot = gate,
        )
        return CameraPane(
            paneContext, def.prefs, hub, hub, stream, egress,
            permitted = { ctx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED },
            ownSensor = { runtime.presence.ownSensor(ctx) },
            toScreen = runtime.presence::offer,
            exec = host.exec,
            release = hub::idle,
            facts = hub,
        ).also { pane = it }
    }
}
