package sh.edm.s1kiosk.setup

import android.app.Application
import dev.glassworks.frame.Input
import dev.glassworks.improv.ImprovState
import dev.glassworks.platform.HotspotFailure
import dev.glassworks.setup.HotspotPermission
import dev.glassworks.setup.HotspotStep
import dev.glassworks.setup.SetupHotspot
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.edm.s1kiosk.ui.Mockup

/**
 * The Wi-Fi step leading with one way (#468): Bluetooth setup, the setup hotspot, or typing it here, with the other ways
 * in a quiet row (layout A) or behind "Other ways to set up" (layout B). Made-up values only.
 *   ./gradlew :kiosk:testDebugUnitTest --tests '*WifiWaysMockups*' -Ps1MockupDir=<dir>
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WifiWaysMockups {
    @Before fun interFromSources() = Mockup.interFromSources()

    private val offline = WifiSetupStatus.Snapshot(WifiSetupStatus.State.NOT_CONNECTED)
    private val ready = HotspotPermission.Step.Ready
    private val offer = HotspotStep.card(SetupHotspot.View.Off(null, null), ready, null)
    private val wifiOff = HotspotStep.card(SetupHotspot.View.Off(null, HotspotFailure.WIFI_OFF), ready, null)
    private val btOn = ImprovFacts(running = true, advertising = true, state = ImprovState.AUTHORIZATION_REQUIRED)
    private val noBt = ImprovFacts(startError = "this panel has no Bluetooth adapter")

    /** The four cases of #468. */
    private val cases = listOf(
        "improv-leads" to (btOn to offer),
        "hotspot-leads" to (noBt to offer),
        "manual-leads" to (noBt to wifiOff),
    )

    private fun model(improv: ImprovFacts, spot: HotspotStep.Card?) = SetupOverlay.Model(
        step = SetupFlow.WIFI, done = emptySet(), wifi = offline, haUrl = null, haSource = "", panelIp = null,
        signedIn = false, improv = WifiStep.improv(improv), hotspot = spot,
    )

    private fun shot(name: String, m: SetupOverlay.Model, folded: Boolean, input: Input = Input.TOUCH, w: Int = 1440, h: Int = 720, open: Boolean = false) {
        val a = Mockup.activity()
        val s = s1SetupOverlay(a).also { it.input = input; it.otherWaysFolded = folded }
        a.setContentView(s, android.view.ViewGroup.LayoutParams(-1, -1))
        s.render(m)
        Mockup.idle()
        if (open) { Mockup.layout(s, w, h); Mockup.label(s, "Other ways to set up ›").performClick(); Mockup.idle() }
        Mockup.save(name, s, w, h)
    }

    @Test
    fun landscape() {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", Mockup.dir != null)
        for ((case, f) in cases) {
            shot("ways-A-$case", model(f.first, f.second), folded = false)
            shot("ways-B-$case", model(f.first, f.second), folded = true)
        }
        shot("ways-B-improv-leads-opened", model(btOn, offer), folded = true, open = true)
        shot("ways-no-touch", model(btOn, offer), folded = false, input = Input.NONE)
    }

    @Test
    @Config(qualifiers = "w540dp-h960dp-port-xhdpi")
    fun portrait() {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", Mockup.dir != null)
        for ((case, f) in cases) {
            shot("ways-A-$case-portrait", model(f.first, f.second), folded = false, w = 1080, h = 1920)
            shot("ways-B-$case-portrait", model(f.first, f.second), folded = true, w = 1080, h = 1920)
        }
        shot("ways-no-touch-portrait", model(btOn, offer), folded = false, input = Input.NONE, w = 1080, h = 1920)
    }
}
