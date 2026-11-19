package dev.glassworks.setup

import android.app.Application
import android.view.ViewGroup
import dev.glassworks.frame.Input
import dev.glassworks.improv.ImprovState
import dev.glassworks.platform.HotspotNetwork
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import dev.glassworks.s1.frame.TestFrames
import dev.glassworks.ui.Mockup

/**
 * The Mirror's Wi-Fi step without touch (#611), at its portrait size: Bluetooth setup open by itself, a failed join,
 * joining, and after the window (Bluetooth waits, the setup hotspot off, then on). Made-up values only.
 *   ./gradlew :kiosk:testDebugUnitTest --tests '*NoTouchImprovMockups*' -Ps1MockupDir=<dir>
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w540dp-h960dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NoTouchImprovMockups {
    @Before fun interFromSources() = Mockup.interFromSources()

    private val offline = WifiSetupStatus.Snapshot(WifiSetupStatus.State.NOT_CONNECTED)
    private val bt = ImprovFacts(running = true, advertising = true, state = ImprovState.AUTHORIZED, authLeftMs = 299_001)
    private val ready = HotspotPermission.Step.Ready
    private val spotOff = HotspotStep.card(SetupHotspot.View.Off(null, null), ready, null)
    private val spotOn = HotspotStep.card(SetupHotspot.View.On(HotspotNetwork("Example-Setup", "abcdefghjkmn"), 15 * 60_000L, asked = false), ready, null)

    private val cases = listOf(
        "1-open" to (bt to spotOff),
        "2-failed" to (bt.copy(ssid = "Example-Home", failure = "wrong password (authentication failed)", authLeftMs = 241_001) to spotOff),
        "3-connecting" to (bt.copy(state = ImprovState.PROVISIONING, ssid = "Example-Home") to spotOff),
        "4-window-closed" to (bt.copy(state = ImprovState.AUTHORIZATION_REQUIRED, authLeftMs = 0) to spotOff),
        "5-window-closed-hotspot-on" to (bt.copy(state = ImprovState.AUTHORIZATION_REQUIRED, authLeftMs = 0) to spotOn),
    )

    @Test fun mirror() {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", Mockup.dir != null)
        for ((name, c) in cases) {
            val a = Mockup.activity()
            val s = SetupOverlay(a, TestFrames.mirror(a))
            a.setContentView(s, ViewGroup.LayoutParams(-1, -1))
            val (improv, spot) = c
            s.render(SetupOverlay.Model(
                step = SetupFlow.WIFI, done = emptySet(), wifi = offline, haUrl = null, haSource = "", panelIp = null,
                signedIn = false, improv = WifiStep.improv(improv, Input.NONE), hotspot = spot,
            ))
            Mockup.idle()
            Mockup.save("improv-no-touch-mirror-$name", s, 1080, 1920)
        }
    }
}
