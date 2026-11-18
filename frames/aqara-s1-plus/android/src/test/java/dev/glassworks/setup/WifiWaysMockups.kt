package dev.glassworks.setup

import android.app.Activity
import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.glassworks.frame.Frame
import dev.glassworks.improv.ImprovState
import dev.glassworks.platform.HotspotFailure
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
 * The Wi-Fi step leading with one way (#468): Bluetooth setup, the setup hotspot, or typing it here, with the other ways
 * behind "Other ways to set up ›" (shot closed, then opened). At every size the setup mockups cover: the S1, the Pixel
 * Tablet, a portrait screen and a small phone, plus the Mirror without touch. Made-up values only.
 *   ./gradlew :kiosk:testDebugUnitTest --tests '*WifiWaysMockups*' -Ps1MockupDir=<dir>
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WifiWaysMockups {
    @Before fun interFromSources() = Mockup.interFromSources()

    private val offline = WifiSetupStatus.Snapshot(WifiSetupStatus.State.NOT_CONNECTED)
    private val ready = HotspotPermission.Step.Ready
    private val offer = HotspotStep.card(SetupHotspot.View.Off(null, null), ready, null)
    private val wifiOff = HotspotStep.card(SetupHotspot.View.Off(null, HotspotFailure.WIFI_OFF), ready, null)
    private val btOn = ImprovFacts(running = true, advertising = true, state = ImprovState.AUTHORIZATION_REQUIRED)
    private val btOff = ImprovFacts()
    private val noBt = ImprovFacts(startError = "this panel has no Bluetooth adapter")

    /** Each way leading: Bluetooth on, Bluetooth switched off (the hotspot leads, Use Bluetooth is another way), no Bluetooth, neither; then Wi-Fi off. */
    private val cases = listOf(
        "1-improv-leads" to (btOn to offer),
        "2-hotspot-leads" to (btOff to offer),
        "3-hotspot-leads-no-bluetooth" to (noBt to offer),
        "4-manual-leads" to (noBt to wifiOff),
        "5-wifi-off-bluetooth-on" to (btOn to wifiOff),
        "6-wifi-off-bluetooth-switched-off" to (btOff to wifiOff),
    )

    private fun model(improv: ImprovFacts, spot: HotspotStep.Card?) = SetupOverlay.Model(
        step = SetupFlow.WIFI, done = emptySet(), wifi = offline, haUrl = null, haSource = "", panelIp = null,
        signedIn = false, improv = WifiStep.improv(improv), hotspot = spot,
    )

    private fun views(v: View): Sequence<View> = sequence {
        yield(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) yieldAll(views(v.getChildAt(i)))
    }

    private fun shot(name: String, m: SetupOverlay.Model, w: Int, h: Int, frame: (Activity) -> Frame) {
        val a = Mockup.activity()
        val s = SetupOverlay(a, frame(a))
        a.setContentView(s, ViewGroup.LayoutParams(-1, -1))
        s.render(m)
        Mockup.idle()
        Mockup.save(name, s, w, h)
        // Main has no link: its "before" has no opened shot.
        val link = views(s).filterIsInstance<TextView>().firstOrNull { it.isShown && it.text.toString() == "Other ways to set up ›" } ?: return
        link.performClick()
        Mockup.idle()
        Mockup.save("$name-opened", s, w, h)
    }

    private fun all(size: String, w: Int, h: Int, frame: (Activity) -> Frame) {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", Mockup.dir != null)
        for ((case, f) in cases) shot("ways-$size-$case", model(f.first, f.second), w, h, frame)
    }

    @Test @Config(qualifiers = "w960dp-h480dp-land-hdpi")
    fun s1() {
        all("s1", Mockup.W, Mockup.H) { TestFrames.s1() }
    }

    @Test @Config(qualifiers = "w1280dp-h800dp-land-mdpi")
    fun tablet() = all("tablet", 1280, 800) { TestFrames.tablet(it) }

    @Test @Config(qualifiers = "w540dp-h960dp-port-xhdpi")
    fun portrait() = all("portrait", 1080, 1920) { TestFrames.tablet(it) }

    @Test @Config(qualifiers = "w480dp-h800dp-port-hdpi")
    fun smallPortrait() = all("small-portrait", 720, 1200) { TestFrames.tablet(it) }

    @Test @Config(qualifiers = "w960dp-h480dp-land-hdpi")
    fun noTouch() {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", Mockup.dir != null)
        shot("ways-no-touch-mirror-landscape", model(btOn, offer), Mockup.W, Mockup.H) { TestFrames.mirror(it) }
    }

    @Test @Config(qualifiers = "w540dp-h960dp-port-xhdpi")
    fun noTouchPortrait() {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", Mockup.dir != null)
        shot("ways-no-touch-mirror", model(btOn, offer), 1080, 1920) { TestFrames.mirror(it) }
    }
}
