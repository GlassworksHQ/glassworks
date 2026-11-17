package sh.edm.s1kiosk.setup

import sh.edm.s1kiosk.frame.TestFrames
import android.app.Application
import dev.glassworks.pane.PaneChoice
import dev.glassworks.update.SilentUpdates
import dev.glassworks.update.SilentUpdatesStep
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.edm.s1kiosk.ui.Mockup

/**
 * Setup's Silent updates step (#422's approved design) on the real setup screen, on a tablet and at the S1's size.
 * Until the step has its own row after Panes (it waits on #418's setup screen), it is drawn as a step under Panes.
 * Illustrative values only. Skipped unless asked:
 *   ./gradlew :kiosk:testDebugUnitTest --tests '*SilentUpdatesMockups*' -Ps1MockupDir=<dir>
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SilentUpdatesMockups {
    @Before fun interFromSources() = Mockup.interFromSources()

    private fun shot(name: String, answer: SilentUpdates, w: Int, h: Int) {
        val a = Mockup.activity()
        val step = SilentUpdatesStep({ answer }, { false }, {})
        val panes = listOf(
            SetupPane(PaneChoice("Voice", "Assist and timers", on = { true }, apply = { null }), emptyList()),
            SetupPane(PaneChoice("Music", "A Sendspin speaker", on = { true }, apply = { null }), emptyList()),
            SetupPane(PaneChoice("Updates", "Silent updates", on = { true }, apply = { null }), listOf(step)),
        )
        val s = SetupOverlay(a, TestFrames.tablet(a), panes)
        a.setContentView(s, android.view.ViewGroup.LayoutParams(-1, -1))
        s.render(SetupOverlay.Model(
            step = SilentUpdatesStep.ID, done = setOf(SetupFlow.WIFI, SetupFlow.HA, SetupFlow.SIGNIN, SetupFlow.PANES),
            wifi = WifiSetupStatus.Snapshot(WifiSetupStatus.State.CONNECTED, "Example-WiFi"), haUrl = "http://homeassistant.local:8123",
            haSource = "mdns", panelIp = "192.0.2.10", signedIn = true, improv = WifiStep.improv(ImprovFacts()), installer = null, preview = false,
        ))
        Mockup.idle()
        Mockup.save(name, s, w, h)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land-mdpi")
    fun tablet() {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", Mockup.dir != null)
        shot("silent-updates-tablet-1-allow", SilentUpdates.UnknownAppsOff, 1280, 800)
        shot("silent-updates-tablet-2-on", SilentUpdates.SelfUpdate, 1280, 800)
        shot("silent-updates-tablet-3-android9", SilentUpdates.OldAndroid("9"), 1280, 800)
    }

    @Test
    @Config(qualifiers = "w960dp-h480dp-land-hdpi")
    fun panelSize() {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", Mockup.dir != null)
        shot("silent-updates-panel-1-allow", SilentUpdates.UnknownAppsOff, Mockup.W, Mockup.H)
        shot("silent-updates-panel-3-android9", SilentUpdates.OldAndroid("9"), Mockup.W, Mockup.H)
    }
}
