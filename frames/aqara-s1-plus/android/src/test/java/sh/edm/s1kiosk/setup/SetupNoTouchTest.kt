package sh.edm.s1kiosk.setup

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.glassworks.frame.Input
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A frame without touch (Input.NONE, #34) only displays: every step shows where to set the panel up from a
 * computer (Sign in keeps its phone QR code), and nothing on the setup screen takes a tap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
class SetupNoTouchTest {
    private val ip = "192.0.2.10"
    private val online = WifiSetupStatus.Snapshot(WifiSetupStatus.State.CONNECTED, "Example-WiFi")
    private val offline = WifiSetupStatus.Snapshot(WifiSetupStatus.State.NOT_CONNECTED)

    private fun screen(step: String, input: Input, wifi: WifiSetupStatus.Snapshot = online, panelIp: String? = ip, done: Set<String> = emptySet()): SetupOverlay {
        val a = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val s = s1SetupOverlay(a).also { it.input = input }
        a.setContentView(s, ViewGroup.LayoutParams(-1, -1))
        s.render(SetupOverlay.Model(step = step, done = done, wifi = wifi, haUrl = "http://homeassistant.local:8123",
            haSource = "mdns", panelIp = panelIp, signedIn = false))
        return s
    }

    /** The visible text views, with whether each takes a tap. */
    private fun shown(v: View): List<Pair<String, Boolean>> = when {
        !v.isShown -> emptyList()
        v is TextView -> listOf(v.text.toString() to v.hasOnClickListeners())
        v is ViewGroup -> (0 until v.childCount).flatMap { shown(v.getChildAt(it)) }
        else -> emptyList()
    }

    private fun taps(s: SetupOverlay) = shown(s).filter { it.second }.map { it.first }.filter { it.isNotBlank() }

    @Test fun everyStepPointsAtTheSetupPageAndNothingTakesATap() {
        for (step in listOf(SetupFlow.HA, SetupFlow.PANES, "radio")) {
            val s = screen(step, Input.NONE)
            val texts = shown(s).map { it.first }
            assertTrue("$step: $texts", "Scan to set it up." in texts)
            assertTrue("$step: $texts", texts.any { "http://$ip:8099/setup" in it })
            assertEquals("$step: nothing to tap", emptyList<String>(), taps(s).filter { it !in RAIL })
        }
    }

    @Test fun withoutWifiItSaysToUseKiln() {
        val s = screen(SetupFlow.WIFI, Input.NONE, wifi = offline, panelIp = null)
        val texts = shown(s).map { it.first }
        assertTrue(texts.toString(), "Waiting for Wi-Fi." in texts)
        assertEquals(emptyList<String>(), taps(s).filter { it !in RAIL })
    }

    @Test fun signInKeepsItsPhoneCodeWithoutTheOnPanelWay() {
        val s = screen(SetupFlow.SIGNIN, Input.NONE)
        val texts = shown(s).map { it.first }
        assertTrue(texts.toString(), "Scan to sign in." in texts)
        assertTrue(texts.toString(), "Sign in on this panel instead" !in texts)
    }

    @Test fun signInDoneHasNoContinueToTap() {
        val s = screen(SetupFlow.SIGNIN, Input.NONE, done = setOf(SetupFlow.WIFI, SetupFlow.HA, SetupFlow.SIGNIN))
        assertTrue(shown(s).map { it.first }.toString(), "Signed in." in shown(s).map { it.first })
        assertEquals(emptyList<String>(), taps(s).filter { it !in RAIL })
    }

    @Test fun aTouchFrameKeepsItsButtons() {
        val taps = taps(screen(SetupFlow.HA, Input.TOUCH))
        assertTrue(taps.toString(), "Skip" in taps)
    }

    private companion object {
        /** The rail's rows: they only answer in a preview, which a frame without touch never opens. */
        val RAIL = setOf("Wi-Fi", "Add it", "Sign in", "Choose", "Radio", "Home Assistant", "Panes", "Done")
    }
}
