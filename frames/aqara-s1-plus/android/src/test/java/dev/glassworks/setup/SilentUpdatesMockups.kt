package dev.glassworks.setup

import android.app.Application
import android.content.Context
import dev.glassworks.frame.Frame
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
import sh.edm.s1kiosk.frame.TestFrames
import dev.glassworks.ui.Mockup

/**
 * Setup's Silent updates step (#422) on the real setup screen, a rail row of its own after Panes, with Sign in at the
 * same size to compare against. At the S1's 1440×720 (the S1 frame for an old boot image, a tablet for the rest), a
 * tablet's 1280×800 and a portrait 1080×1920. Illustrative values only. Skipped unless asked:
 *   ./gradlew :kiosk:testDebugUnitTest --tests '*SilentUpdatesMockups*' -Ps1MockupDir=<dir>
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SilentUpdatesMockups {
    @Before fun interFromSources() = Mockup.interFromSources()

    private fun shot(
        name: String, step: String, w: Int, h: Int, silent: SilentUpdates? = SilentUpdates.UnknownAppsOff,
        frame: (Context) -> Frame = { TestFrames.tablet(it) },
    ) {
        val a = Mockup.activity()
        val own = listOf(SilentUpdatesStep({ silent }, { false }, {}))
        val panes = listOf(
            SetupPane(PaneChoice("Voice", "Assist and timers", on = { true }, apply = { null }), emptyList()),
            SetupPane(PaneChoice("Music", "A Sendspin speaker", on = { true }, apply = { null }), emptyList()),
        )
        val s = SetupOverlay(a, frame(a), panes, own)
        a.setContentView(s, android.view.ViewGroup.LayoutParams(-1, -1))
        val flow = SetupFlow.ids(true, emptyList(), own.map { it.id })
        s.render(SetupOverlay.Model(
            step = step, done = flow.takeWhile { it != step }.toSet(),
            wifi = WifiSetupStatus.Snapshot(WifiSetupStatus.State.CONNECTED, "Example-WiFi"), haUrl = "http://homeassistant.local:8123",
            haSource = "mdns", panelIp = "192.0.2.10", signedIn = step != SetupFlow.SIGNIN, improv = WifiStep.improv(ImprovFacts()),
        ))
        Mockup.idle()
        Mockup.save(name, s, w, h)
    }

    private fun all(prefix: String, w: Int, h: Int) {
        shot("$prefix-0-signin", SetupFlow.SIGNIN, w, h)
        shot("$prefix-1-allow", SilentUpdatesStep.ID, w, h)
        shot("$prefix-2-on", SilentUpdatesStep.ID, w, h, SilentUpdates.SelfUpdate)
        shot("$prefix-3-android9", SilentUpdatesStep.ID, w, h, SilentUpdates.OldAndroid("9"))
        shot("$prefix-4-target-too-low", SilentUpdatesStep.ID, w, h, SilentUpdates.TargetTooLow("14", 34, 33))
        shot("$prefix-6-checking", SilentUpdatesStep.ID, w, h, null) // the answer isn't read yet
    }

    @Test
    @Config(qualifiers = "w960dp-h480dp-land-hdpi")
    fun panelSize() {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", Mockup.dir != null)
        val s1 = { _: Context -> TestFrames.s1() }
        shot("silent-updates-s1-0-signin", SetupFlow.SIGNIN, Mockup.W, Mockup.H, SilentUpdates.NoRootService, s1)
        shot("silent-updates-s1-5-old-boot-image", SilentUpdatesStep.ID, Mockup.W, Mockup.H, SilentUpdates.NoRootService, s1)
        all("silent-updates-panel", Mockup.W, Mockup.H)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land-mdpi")
    fun tablet() {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", Mockup.dir != null)
        all("silent-updates-tablet", 1280, 800)
    }

    @Test
    @Config(qualifiers = "w540dp-h960dp-port-xhdpi")
    fun portrait() {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", Mockup.dir != null)
        all("silent-updates-portrait", 1080, 1920)
    }
}
