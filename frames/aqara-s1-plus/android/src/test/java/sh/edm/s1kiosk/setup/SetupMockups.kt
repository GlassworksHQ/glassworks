package sh.edm.s1kiosk.setup

import android.app.Application
import android.content.Context
import dev.glassworks.frame.Input
import dev.glassworks.pane.PaneChoice
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import dev.glassworks.improv.ImprovState
import sh.edm.s1kiosk.radio.FixedReadout
import sh.edm.s1kiosk.radio.HelperFacts
import sh.edm.s1kiosk.radio.RadioDefinition
import sh.edm.s1kiosk.radio.RadioMode
import sh.edm.s1kiosk.radio.ThreadRouterState
import sh.edm.s1kiosk.radio.ZigbeeStack
import sh.edm.s1kiosk.radio.radioDefinition
import sh.edm.s1kiosk.radio.ui.RadioFollowUp
import sh.edm.s1kiosk.ui.Mockup

/**
 * Mock-ups of every step of the setup screen and of the connecting screen (#63), rendered at the
 * panel's 1440x720 px. Illustrative values only. Skipped unless asked:
 *   ./gradlew :kiosk:testDebugUnitTest --tests '*SetupMockups*' -Ps1MockupDir=<dir>
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SetupMockups {

    @Before fun interFromSources() = Mockup.interFromSources()

    private val ha = "http://homeassistant.local:8123"
    private val ip = "192.0.2.10"
    private val offline = WifiSetupStatus.Snapshot(WifiSetupStatus.State.NOT_CONNECTED)
    private val online = WifiSetupStatus.Snapshot(WifiSetupStatus.State.CONNECTED, "Example-WiFi")

    private val flow = SetupFlow.ids(true, listOf("radio"))

    private fun model(
        step: String, wifi: WifiSetupStatus.Snapshot = online, haUrl: String? = ha,
        improv: ImprovFacts = ImprovFacts(), installer: InstallerWifi? = null, preview: Boolean = false,
        done: Set<String> = flow.takeWhile { it != step }.toSet(), panelIp: String? = ip,
        hotspot: dev.glassworks.setup.HotspotStep.Card? = null,
    ) = SetupOverlay.Model(
        step = step, done = done, wifi = wifi, haUrl = haUrl, haSource = "mdns", panelIp = panelIp,
        signedIn = false, improv = WifiStep.improv(improv), installer = installer, preview = preview, hotspot = hotspot,
    )

    /** The setup hotspot's states (#197), with a made-up network. */
    private object Spot {
        private val net = dev.glassworks.platform.HotspotNetwork("Example-Setup", "abcdefghjkmn")
        private val ready = dev.glassworks.setup.HotspotPermission.Step.Ready
        private fun card(v: dev.glassworks.setup.SetupHotspot.View, p: dev.glassworks.setup.HotspotPermission.Step = ready, url: String? = "http://192.0.2.1:8099/setup") =
            dev.glassworks.setup.HotspotStep.card(v, p, url)
        val on = card(dev.glassworks.setup.SetupHotspot.View.On(net, 14 * 60_000L, asked = false))
        val offer = card(dev.glassworks.setup.SetupHotspot.View.Off(null, null))
        val permission = card(
            dev.glassworks.setup.SetupHotspot.View.Off(null, dev.glassworks.platform.HotspotFailure.NO_PERMISSION),
            dev.glassworks.setup.HotspotPermission.step(dev.glassworks.platform.PlatformApi(34), { false }, askedBefore = false) { false },
        )
        val failed = card(dev.glassworks.setup.SetupHotspot.View.Off(dev.glassworks.setup.SetupHotspotPolicy.Stop.FAILED, dev.glassworks.platform.HotspotFailure.TETHERING_ON))
        val wifiOff = card(dev.glassworks.setup.SetupHotspot.View.Off(null, dev.glassworks.platform.HotspotFailure.WIFI_OFF))
    }

    private fun facts(mode: RadioMode, status: String, thread: ThreadRouterState? = null) =
        RadioFollowUp.Facts(mode, status, HelperFacts(true, 4), ZigbeeStack.AQARA, if (thread != null) false else null, thread, ha, ip)

    /** The app's panes: voice, music and the wall base on, the radio as [radio] (its follow-up open when [followUp]). */
    private fun panes(a: Context, radio: RadioFollowUp.Facts?, followUp: Boolean = false): List<SetupPane> {
        val def: RadioDefinition = radioDefinition(a, FixedReadout(radio, mode = radio?.mode ?: RadioMode.OFF, haBase = ha, panelIp = ip))
        def.followUpOpen = followUp
        return listOf(
            SetupPane(PaneChoice("Voice", "Assist and timers", on = { true }, apply = { null }), emptyList()),
            SetupPane(PaneChoice("Music", "A Sendspin speaker", on = { true }, apply = { null }), emptyList()),
            SetupPane(def.setupChoice, def.setupSteps),
            SetupPane(PaneChoice("Bluetooth proxy", "For BLE sensors", on = { false }, apply = { null }), emptyList()),
            SetupPane(PaneChoice("Wall base", "The S1's wired base", on = { true }, apply = { null }), emptyList()),
        )
    }

    private fun shot(
        name: String, m: SetupOverlay.Model, radio: RadioFollowUp.Facts? = null, followUp: Boolean = false,
        input: Input = Input.TOUCH, then: (SetupOverlay) -> Unit = {},
    ) {
        val a = Mockup.activity()
        val s = s1SetupOverlay(a, panes(a, radio, followUp)).also { it.input = input }
        a.setContentView(s, android.view.ViewGroup.LayoutParams(-1, -1))
        s.render(m)
        then(s)
        Mockup.idle()
        Mockup.save(name, s)
    }

    private fun connecting(name: String, state: LoadingOverlay.State, phase: Float? = null) {
        val a = Mockup.activity()
        val o = LoadingOverlay(a)
        a.setContentView(o, android.view.ViewGroup.LayoutParams(-1, -1))
        o.show(state)
        phase?.let(o::previewPhase)
        Mockup.idle()
        Mockup.save(name, o)
    }

    @Test
    fun mockups() {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", Mockup.dir != null)

        connecting("connecting-01", LoadingOverlay.State.Connecting(LoadingOverlay.CONNECTING, "homeassistant.local"), phase = 0.62f)
        connecting("connecting-02-wifi-off", LoadingOverlay.State.Offline(OfflineText.WIFI_OFF_TITLE, OfflineText.SETUP_HINT, OfflineText.SETUP_ACTION))
        connecting("connecting-03-unreachable", LoadingOverlay.State.Unreachable("homeassistant.local", retryAt = android.os.SystemClock.elapsedRealtime() + 4_000))
        connecting("connecting-04-joining-wifi", LoadingOverlay.State.Connecting(OfflineText.CONNECTING_TITLE, OfflineText.CONNECTING_DETAIL, host = false), phase = 0.3f)

        val coordinator = facts(RadioMode.ZIGBEE_TCP, "ok Zigbee coordinator (TCP): listening on :6638 for Home Assistant (192.0.2.20). ZHA: radio type EZSP, serial path socket://$ip:6638")
        val threadRouter = facts(RadioMode.THREAD_ROUTER, "ok Thread router (repeater): no dataset yet", thread = ThreadRouterState(false, null, null))

        shot("setup-01-wifi-allow", model(
            SetupFlow.WIFI, wifi = offline,
            improv = ImprovFacts(running = true, advertising = true, clients = 1, state = ImprovState.AUTHORIZATION_REQUIRED),
        ))
        shot("setup-02-wifi-connected", model(SetupFlow.WIFI, done = setOf(SetupFlow.WIFI), preview = true))
        shot("setup-03-ha-add", model(SetupFlow.HA))
        shot("setup-04-ha-added", model(SetupFlow.HA, done = setOf(SetupFlow.WIFI, SetupFlow.HA), preview = true))
        shot("setup-05-ha-looking", model(SetupFlow.HA, haUrl = null))
        shot("setup-06-signin", model(SetupFlow.SIGNIN))
        shot("setup-07-signed-in", model(SetupFlow.SIGNIN, done = setOf(SetupFlow.WIFI, SetupFlow.HA, SetupFlow.SIGNIN), preview = true))
        shot("setup-08-panes", model(SetupFlow.PANES))
        shot("setup-08b-panes-scrolled", model(SetupFlow.PANES)) { s ->
            // The list scrolls above Continue once the cards don't fit (#121): its end, once the
            // pending layout passes have run at the panel's size (scrollTo clamps to the end).
            Mockup.idle()
            Mockup.layout(s)
            generateSequence(Mockup.label(s, "Voice") as android.view.View) { it.parent as? android.view.View }
                .firstOrNull { it is android.widget.ScrollView }?.scrollTo(0, 100_000)
        }
        shot("setup-09-radio-mode", model("radio"), radio = coordinator) // turned on in Panes, the mode offered
        shot("setup-10-radio-coordinator-next", model("radio"), radio = coordinator, followUp = true)
        shot("setup-11-radio-thread-router-next", model("radio"), radio = threadRouter, followUp = true)
        shot("setup-12-preview", model(SetupFlow.WIFI, preview = true))
        shot("setup-13-done", model(SetupFlow.PANES)) { it.finish(holdMs = 60_000) {} }
        shot("setup-14-done-preview", model(SetupFlow.PANES, preview = true)) { it.finish {} }

        // A frame without touch (Input.NONE, #34): set up from a computer; nothing on the screen takes a tap.
        shot("setup-15-no-touch-wifi", model(SetupFlow.WIFI, wifi = offline, panelIp = null), input = Input.NONE)
        shot("setup-16-no-touch-ha", model(SetupFlow.HA), input = Input.NONE)
        shot("setup-17-no-touch-signin", model(SetupFlow.SIGNIN), input = Input.NONE)
        shot("setup-18-no-touch-radio", model("radio"), radio = coordinator, input = Input.NONE)

        // The setup hotspot (#197): no Wi-Fi works, so the panel opens its own; a phone joins it and opens the setup page.
        val idle = ImprovFacts()
        shot("setup-19-hotspot-offer", model(SetupFlow.WIFI, wifi = offline, improv = idle, hotspot = Spot.offer))
        shot("setup-20-hotspot-permission", model(SetupFlow.WIFI, wifi = offline, improv = idle, hotspot = Spot.permission))
        shot("setup-21-hotspot-on", model(SetupFlow.WIFI, wifi = offline, hotspot = Spot.on))
        shot("setup-22-hotspot-failed", model(SetupFlow.WIFI, wifi = offline, improv = idle, hotspot = Spot.failed))
        shot("setup-23-no-touch-hotspot-on", model(SetupFlow.WIFI, wifi = offline, panelIp = null, hotspot = Spot.on), input = Input.NONE)
        shot("setup-24-hotspot-wifi-off", model(SetupFlow.WIFI, wifi = offline, improv = idle, hotspot = Spot.wifiOff))
        shot("setup-25-no-touch-wifi-off", model(SetupFlow.WIFI, wifi = offline, panelIp = null, hotspot = Spot.wifiOff), input = Input.NONE)
    }

    /**
     * A screen that stands portrait and takes no tap: the lululemon Mirror's 1080x1920 glass (#198), which the app now
     * fills portrait (GenericAndroid.orientation) and sets up from a computer (Input.NONE).
     *   ./gradlew :kiosk:testDebugUnitTest --tests '*SetupMockups.portraitWithoutTouch' -Ps1MockupDir=<dir>
     */
    @Test
    @Config(qualifiers = "w540dp-h960dp-port-xhdpi")
    fun portraitWithoutTouch() {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", Mockup.dir != null)
        for ((name, m) in listOf(
            "portrait-01-no-touch-wifi" to model(SetupFlow.WIFI, wifi = offline, panelIp = null),
            "portrait-02-no-touch-ha" to model(SetupFlow.HA),
            "portrait-03-no-touch-signin" to model(SetupFlow.SIGNIN),
            "portrait-05-no-touch-hotspot-on" to model(SetupFlow.WIFI, wifi = offline, panelIp = null, hotspot = Spot.on),
        )) {
            val a = Mockup.activity()
            val s = s1SetupOverlay(a, panes(a, null)).also { it.input = Input.NONE }
            a.setContentView(s, android.view.ViewGroup.LayoutParams(-1, -1))
            s.render(m)
            Mockup.idle()
            Mockup.save(name, s, 1080, 1920)
        }
        val a = Mockup.activity()
        val o = LoadingOverlay(a)
        a.setContentView(o, android.view.ViewGroup.LayoutParams(-1, -1))
        o.show(LoadingOverlay.State.Connecting(LoadingOverlay.CONNECTING, "homeassistant.local"))
        o.previewPhase(0.62f)
        Mockup.idle()
        Mockup.save("portrait-04-connecting", o, 1080, 1920)
        // #448: no network and nothing to join. The loading screen's text has no button here; the Wi-Fi step
        // (portrait-01) covers it at the next setup tick.
        val stuck = OfflineText.Facts(wifiEnabled = true, savedNetwork = null, joining = false, wasOnlineBefore = false, offlineForMs = 5_000)
        val t = OfflineText.decide(stuck, 30_000, Input.NONE)
        val b = Mockup.activity()
        val n = LoadingOverlay(b)
        b.setContentView(n, android.view.ViewGroup.LayoutParams(-1, -1))
        n.show(LoadingOverlay.State.Offline(t.title, t.detail, t.action))
        Mockup.idle()
        Mockup.save("portrait-05-no-touch-no-network", n, 1080, 1920)
    }
}
