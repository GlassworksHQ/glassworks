package dev.glassworks.settings

import android.app.Application
import dev.glassworks.diagnostics.CpuTimes
import dev.glassworks.diagnostics.DiagnosticsPane
import dev.glassworks.diagnostics.Probe
import dev.glassworks.diagnostics.Sample
import dev.glassworks.pane.HaLink
import dev.glassworks.pane.Pane
import dev.glassworks.pane.PaneContext
import dev.glassworks.platform.AppBuild
import dev.glassworks.esphome.DeviceIdentity
import dev.glassworks.esphome.TextSensorEntity
import sh.edm.s1kiosk.KioskApp
import dev.glassworks.KioskPrefs
import sh.edm.s1kiosk.PanePrefs
import sh.edm.s1kiosk.base.BaseBackend
import sh.edm.s1kiosk.base.BaseRes
import sh.edm.s1kiosk.base.WallBase
import sh.edm.s1kiosk.base.WallBasePane
import sh.edm.s1kiosk.frame.AqaraCloud
import sh.edm.s1kiosk.frame.AqaraS1Plus
import sh.edm.s1kiosk.frame.TimeZoneKeeper
import sh.edm.s1kiosk.frame.TimeZonePane
import sh.edm.s1kiosk.frame.TimeZonePrefs
import dev.glassworks.radio.SysProps
import dev.glassworks.pane.PanelEvent
import dev.glassworks.pane.PanelTimer
import dev.glassworks.speaker.SendspinPane
import dev.glassworks.sendspin.SendspinPlayer
import dev.glassworks.speaker.MusicView
import dev.glassworks.device.UrlPlayer
import dev.glassworks.assist.AssistPane
import dev.glassworks.assist.SpeakerMedia
import java.util.TimeZone
import dev.glassworks.radio.FixedReadout
import dev.glassworks.radio.HelperFacts
import dev.glassworks.radio.ui.RadioFollowUp
import dev.glassworks.radio.RadioMode
import dev.glassworks.radio.ZigbeeStack
import dev.glassworks.radio.radioDefinition
import java.util.concurrent.Executors
import android.os.SystemClock
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import sh.edm.s1kiosk.settings.BluetoothProxyPage

/**
 * The S1's panes running with illustrative values, and the settings model the panel's menu and the Manage
 * page draw from them (#212, #242): made-up names and RFC 5737 addresses. Call [shutdown] after.
 */
class PaneFixtures(private val app: Application) {
    private val held = ArrayList<java.util.concurrent.ExecutorService>()
    private val stops = ArrayList<() -> Unit>()

    fun shutdown() {
        stops.forEach { runCatching(it) }
        held.forEach { it.shutdownNow() }
    }

    /**
     * The Bluetooth proxy with a usable radio: under Robolectric the S1's own reports no Bluetooth LE, so
     * its page would be one line. "Listen and connect" is on, so the page draws all three tiles and the mode.
     */
    val bluetoothProxyDefinition: dev.glassworks.bluetoothproxy.BluetoothProxyDefinition by lazy {
        val file = PanePrefs(app).paneFile("bluetooth-proxy")
        file.edit().putBoolean(dev.glassworks.bluetoothproxy.BluetoothProxyPrefs.KEY_CONNECT, true).commit()
        val radio = object : dev.glassworks.bluetoothproxy.ProxyRadio {
            override fun newScanner() = object : dev.glassworks.esphome.bluetoothproxy.BleScanner {
                override fun start(power: dev.glassworks.esphome.bluetoothproxy.ScanPower, listener: dev.glassworks.esphome.bluetoothproxy.BleScanner.Listener) {}
                override fun stop() {}
            }
            override fun newGattRadio() = dev.glassworks.esphome.bluetoothproxy.GattRadio { _, _ -> null }
            override fun unavailable(): dev.glassworks.esphome.bluetoothproxy.ScanFailure? = null
            override fun turnBluetoothOn() = true
            override fun turnLocationOn() = true
        }
        dev.glassworks.bluetoothproxy.BluetoothProxyDefinition(file, radio, page = ::BluetoothProxyPage)
    }

    /** The proxy running, with Home Assistant subscribed: its page reads Listening. */
    fun bluetoothProxy(): Pane {
        val device = Executors.newSingleThreadScheduledExecutor().also { held += it }
        val pane = bluetoothProxyDefinition.pane(ctx, device)
        val server = dev.glassworks.esphome.ApiServer(0, { ctx.device }, emptyList(), keys = null)
        pane.bind(server)
        pane.start()
        stops += { pane.stop() }
        server.bluetooth!!.onSubscribed(true)
        val until = System.nanoTime() + 5_000_000_000L
        while (pane.status()?.subscribed != true) {
            check(System.nanoTime() < until) { "the proxy's engine never took the subscription" }
            Thread.sleep(5)
        }
        return pane
    }

    private val ctx = PaneContext(
        app, PanePrefs(app)::paneFile,
        DeviceIdentity("aqara-s1-plus-000001", "Aqara S1 Plus", "02:00:00:00:00:01", "2026.10.2", "x", "Aqara", "Panel Hub S1 Plus", 0),
        publishStates = {}, emit = {}, entitiesChanged = {}, sendEvent = { _, _ -> },
        build = AppBuild("1.0.0", 10000, debug = false),
    )

    val coordinator = RadioFollowUp.Facts(
        RadioMode.ZIGBEE_TCP, "ok Zigbee coordinator (TCP): listening on :6638 for Home Assistant (192.0.2.20)",
        HelperFacts(true, 4), ZigbeeStack.AQARA, null, null, "http://homeassistant.local:8123", "192.0.2.10",
    )

    /** An hour of made-up readings, a minute apart, ending at [now]. */
    fun diagnostics(): DiagnosticsPane {
        val now = (3L * 24 + 4) * 3_600_000L
        val samples = ArrayDeque((0 until 60).map { n ->
            val t = now - (59 - n) * 60_000L
            val wave = kotlin.math.sin(n / 5.0).toFloat()
            Sample(
                elapsedMs = t, wallMs = 1_762_765_920_000L + t, cpu = CpuTimes(n * 6000L, n * 6000L - n * 1300L - (wave * 300).toLong()),
                appTicks = mapOf(1 to n * 330L), ticksPerSecond = 100, cpus = 4, contentProcesses = 3,
                cpuTemperature = 50f + wave * 1.4f + n / 60f, memoryAvailableBytes = (700L - n * 1.5f.toLong() - (wave * 20).toLong()) shl 20,
                appPssBytes = 214L shl 20, storageFreeBytes = 4_424_000_000L, wifiRssi = -58 + (wave * 2).toInt(), wifiLinkSpeed = 72,
                appStartElapsedMs = now - (2 * 60 + 10) * 60_000L, storageTotalBytes = 6_013_000_000L,
            )
        })
        val probe = object : Probe {
            override fun sample() = samples.removeFirst()
            override fun lastExitReason() = android.app.ApplicationExitInfo.REASON_PACKAGE_UPDATED
        }
        ShadowSystemClock.advanceBy(Duration.ofMillis(now - SystemClock.elapsedRealtime()))
        // Started on a device thread that never gets to run: start() reads the restart reason, the ticks are ours.
        val device = Executors.newSingleThreadScheduledExecutor().also { held += it; it.execute { Thread.sleep(Long.MAX_VALUE) } }
        return DiagnosticsPane(ctx, true, device, probe).also { p -> p.start(); repeat(60) { p.tick() } }
    }

    /** A three-channel switch base, as the tracker hears it, with relay 1 on and button 1 just pressed; or none. */
    fun wallBase(
        attached: Boolean = true,
        /** The base's link: none by default; a test passes one that records relay writes. */
        newLink: ((WallBase.Config, java.util.concurrent.ScheduledExecutorService, sh.edm.s1kiosk.base.BaseLink.Listener) -> sh.edm.s1kiosk.base.BaseLink)? = null,
    ): WallBasePane {
        PanePrefs(app).wallBase.layout = ""
        val device = Executors.newSingleThreadScheduledExecutor().also { held += it }
        // No getprop or setprop on the host: Aqara's daemon reads as stopped, as on a panel with the UART base.
        val p = WallBasePane(ctx, WallBase.Config(BaseBackend.UART), device, AqaraCloud { cmd -> if (cmd.firstOrNull() == "getprop") "stopped" else "" }, newLink)
        p.tracker.apply(if (!attached) mapOf(BaseRes.DOCK_CLASS to 0) else mapOf(
            BaseRes.DOCK_CLASS to 0x0100F110, BaseRes.CHANNEL_COUNT to 3, BaseRes.RELAY_MASK to 7,
            BaseRes.relay(1) to 1, BaseRes.relay(2) to 0, BaseRes.relay(3) to 0,
            BaseRes.FIRMWARE to "0.0.0_0034", BaseRes.POWER to 12.4f, BaseRes.PROTECTION to 0,
        ), 0)
        p.baseListener.onLayout(p.tracker.current()!!)
        if (attached) p.baseListener.onButton(1, "double")
        return p
    }

    /** Europe/Madrid, kept by the root helper; Home Assistant's clock as the POSIX rule it sends ([haRule]). */
    fun timeZone(haRule: String = "CET-1CEST,M3.5.0,M10.5.0/3"): TimeZonePane {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Madrid"))
        val props = object : SysProps {
            val values = mutableMapOf(TimeZoneKeeper.PROP to "Europe/Madrid", TimeZoneKeeper.PROP_STATUS to "ok Europe/Madrid")
            override fun get(name: String) = values[name].orEmpty()
            override fun set(name: String, value: String) { values[name] = value }
        }
        val p = TimeZonePane(ctx, TimeZonePrefs(PanePrefs(app).paneFile("time-zone")), TimeZoneKeeper(props, inline = true))
        p.start()
        p.onEvent(PanelEvent.HomeAssistantTimeZone(haRule))
        return p
    }

    /** Listening for Okay Nabu, with one timer running. */
    fun voice(): AssistPane {
        PanePrefs(app).voice.apply { wakeWord = "okay_nabu"; micMuted = false; micGain = 1.5f; wakeSound = true; overlay = true }
        val p = AssistPane(
            ctx,
            player = object : UrlPlayer { override fun play(urls: List<String>, onDone: (Boolean) -> Unit) {}; override fun stop() {} },
            speaker = object : SpeakerMedia { override val playing = false; override val announcing = false; override fun pause() {}; override fun resume() {} },
            resolveUrl = { it }, wakeChime = {}, onVoiceEvent = {}, createInput = { _, _, _ -> error("not started in the mock-ups") },
        )
        p.onEvent(PanelEvent.TimersChanged(listOf(PanelTimer("1", "Pasta", 900, 360, true))))
        return p
    }

    /** Playing a public-domain piece from Music Assistant at 192.0.2.30. */
    fun music(): SendspinPane {
        val p = SendspinPane(ctx, haBase = { "http://homeassistant.local:8123" })
        p.player.setVolume(40)
        p.view = object : MusicView {
            override val status = SendspinPlayer.Status(SendspinPlayer.Phase.PLAYING, serverName = "Music Assistant", paired = true, codec = "flac")
            override val serverUrl = "ws://192.0.2.30:8927/sendspin"
            override val nowPlayingText = "Claude Debussy – Clair de lune"
            override val volume get() = p.player.volume
            override fun setVolume(volume: Int) = p.player.setVolume(volume)
        }
        return p
    }

    /** The radio's entities the page shows, as Home Assistant has them; [connected]: who uses the coordinator now (#217). */
    fun radio(connected: String = "Home Assistant · 192.0.2.20 · since 14:32") = object : Pane {
        override val id = "radio"
        override fun entities() = listOf(
            TextSensorEntity("radio_connected", "Radio connected client", get = { connected }),
            TextSensorEntity("radio_helper", "Radio helper", get = { "v4 · root side ready" }),
            TextSensorEntity("radio_firmware", "Radio firmware", get = { "EmberZNet 7.4.4 · EZSP v13" }),
        )
    }

    /**
     * The menu's model with [running] panes, the S1's own definitions with the radio on [coordinator]; the
     * panes in [off] are turned off. Read again on each call, as the menu and the Manage page do.
     */
    fun model(running: List<Pane>, off: Set<String> = emptySet(), link: () -> HaLink = { HaLink.CONNECTED }): () -> KioskSettingsOverlay.Model {
        KioskPrefs(app).apply { urlSetting = "http://homeassistant.local:8123/lovelace/panel" }
        val radio = radioDefinition(app, FixedReadout(coordinator, running = true, allowedNow = "192.0.2.20"))
        val definitions = KioskApp.runtime(AqaraS1Plus(), build = AppBuild("1.0.0", 10000, debug = false)).paneDefinitions(app)
            .map { when (it.id) { "radio" -> radio; "bluetooth-proxy" -> bluetoothProxyDefinition; else -> it } }
        definitions.forEach { d -> d.switch?.on = d.id !in off }
        val byId = running.associateBy { it.id }
        return { MenuFixture.model(app, radio, status = "screen on · presence near · voice=idle", panes = definitions, running = { byId[it] }, link = link) }
    }

    /** Every pane the S1 has, running. */
    fun all(): List<Pane> = listOf(diagnostics(), wallBase(), timeZone(), voice(), music(), radio(), bluetoothProxy())
}
