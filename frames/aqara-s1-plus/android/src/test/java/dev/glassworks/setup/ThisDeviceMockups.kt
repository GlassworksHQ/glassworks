package dev.glassworks.setup

import android.app.Application
import dev.glassworks.pane.PaneChoice
import dev.glassworks.s1.frame.TestFrames
import dev.glassworks.ui.Mockup
import dev.glassworks.update.SilentUpdates
import dev.glassworks.update.SilentUpdatesStep
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Setup's This device step (#403) on the real setup screen, a rail row of its own after Panes, before Silent updates:
 * at a tablet's 1280×800 (the size it shows at), the S1's 960×480 dp (a small tablet) and a portrait 1080×1920.
 * Illustrative values only. Skipped unless asked:
 *   ./gradlew :kiosk:testDebugUnitTest --tests '*ThisDeviceMockups*' -Ps1MockupDir=<dir>
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThisDeviceMockups {
    @Before fun interFromSources() = Mockup.interFromSources()

    private fun shot(name: String, grants: ThisDeviceStep.Grants, w: Int, h: Int, opens: Boolean = true) {
        val a = Mockup.activity()
        val tablet = ThisDeviceStep({ grants }, { false }, {}, { opens }, { opens })
        val own = listOf(tablet, SilentUpdatesStep({ SilentUpdates.UnknownAppsOff }, { false }, {}))
        val panes = listOf(
            SetupPane(PaneChoice("Voice", "Assist and timers", on = { true }, apply = { null }), emptyList()),
            SetupPane(PaneChoice("Music", "A Sendspin speaker", on = { true }, apply = { null }), emptyList()),
        )
        val s = SetupOverlay(a, TestFrames.tablet(a), panes, own)
        a.setContentView(s, android.view.ViewGroup.LayoutParams(-1, -1))
        val flow = SetupFlow.ids(true, emptyList(), own.map { it.id })
        s.render(SetupOverlay.Model(
            step = ThisDeviceStep.ID, done = flow.takeWhile { it != ThisDeviceStep.ID }.toSet(),
            wifi = WifiSetupStatus.Snapshot(WifiSetupStatus.State.CONNECTED, "Example-WiFi"), haUrl = "http://homeassistant.local:8123",
            haSource = "mdns", panelIp = "192.0.2.10", signedIn = true, improv = WifiStep.improv(ImprovFacts()),
        ))
        if (!opens) {
            // The button pressed once, and Android had nothing to open.
            Mockup.idle()
            Mockup.label(s, ThisDeviceStep.MAKE_HOME).performClick()
        }
        Mockup.idle()
        Mockup.save(name, s, w, h)
    }

    private fun all(prefix: String, w: Int, h: Int) {
        shot("$prefix-1-both-off", ThisDeviceStep.Grants(home = false, admin = false), w, h)
        shot("$prefix-2-home-on", ThisDeviceStep.Grants(home = true, admin = false), w, h)
        shot("$prefix-3-both-on", ThisDeviceStep.Grants(home = true, admin = true), w, h)
        shot("$prefix-4-no-home-page", ThisDeviceStep.Grants(home = false, admin = false), w, h, opens = false)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land-mdpi")
    fun tablet() {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", Mockup.dir != null)
        all("this-device-tablet", 1280, 800)
    }

    @Test
    @Config(qualifiers = "w960dp-h480dp-land-hdpi")
    fun small() {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", Mockup.dir != null)
        all("this-device-small", Mockup.W, Mockup.H)
    }

    @Test
    @Config(qualifiers = "w540dp-h960dp-port-xhdpi")
    fun portrait() {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", Mockup.dir != null)
        all("this-device-portrait", 1080, 1920)
    }
}
