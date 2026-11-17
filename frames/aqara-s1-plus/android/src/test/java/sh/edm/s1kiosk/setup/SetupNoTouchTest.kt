package sh.edm.s1kiosk.setup

import android.app.Application
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
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
 * computer (Sign in keeps its phone QR code), and nothing on the setup screen takes a tap. Once the setup page signed
 * it in, the Home Assistant step waits for Home Assistant to add it (#484).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
class SetupNoTouchTest {
    private val ip = "192.0.2.10"
    private val online = WifiSetupStatus.Snapshot(WifiSetupStatus.State.CONNECTED, "Example-WiFi")
    private val offline = WifiSetupStatus.Snapshot(WifiSetupStatus.State.NOT_CONNECTED)

    private fun model(step: String, wifi: WifiSetupStatus.Snapshot = online, panelIp: String? = ip, done: Set<String> = emptySet(), signedIn: Boolean = false) =
        SetupOverlay.Model(step = step, done = done, wifi = wifi, haUrl = "http://homeassistant.local:8123",
            haSource = "mdns", panelIp = panelIp, signedIn = signedIn)

    private fun screen(
        step: String, input: Input, wifi: WifiSetupStatus.Snapshot = online, panelIp: String? = ip, done: Set<String> = emptySet(),
        signedIn: Boolean = false, setUp: (SetupOverlay) -> Unit = {},
    ): SetupOverlay {
        val a = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val s = s1SetupOverlay(a).also { it.input = input }.also(setUp)
        a.setContentView(s, ViewGroup.LayoutParams(-1, -1))
        s.render(model(step, wifi, panelIp, done, signedIn))
        return s
    }

    /** The visible text views, with whether each takes a tap. */
    private fun shown(v: View): List<Pair<String, Boolean>> = when {
        !v.isShown -> emptyList()
        v is TextView -> listOf(v.text.toString() to v.hasOnClickListeners())
        v is ViewGroup -> (0 until v.childCount).flatMap { shown(v.getChildAt(it)) }
        else -> emptyList()
    }

    private fun views(v: View): Sequence<View> = sequence {
        yield(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) yieldAll(views(v.getChildAt(i)))
    }

    /**
     * Every visible view that takes a tap, by its text: a button, and also a row whose texts sit in children (the
     * rail's rows are such rows).
     */
    private fun taps(v: View): List<String> = when {
        !v.isShown -> emptyList()
        v.hasOnClickListeners() -> listOf(shown(v).joinToString(" ") { it.first }.ifBlank { v.javaClass.simpleName })
        v is ViewGroup -> (0 until v.childCount).flatMap { taps(v.getChildAt(it)) }
        else -> emptyList()
    }

    @Test fun everyStepPointsAtTheSetupPageAndNothingTakesATap() {
        for (step in listOf(SetupFlow.HA, SetupFlow.PANES, "radio")) {
            val s = screen(step, Input.NONE)
            val texts = shown(s).map { it.first }
            assertTrue("$step: $texts", "Scan to set it up." in texts)
            assertTrue("$step: $texts", texts.any { "http://$ip:8099/setup" in it })
            assertEquals("$step: nothing to tap", emptyList<String>(), taps(s))
        }
    }

    @Test fun signedInTheHomeAssistantStepWaitsForHomeAssistantInsteadOfTheCode() {
        val s = screen(SetupFlow.HA, Input.NONE, done = setOf(SetupFlow.WIFI, SetupFlow.SIGNIN), signedIn = true)
        val texts = shown(s).map { it.first }
        assertTrue(texts.toString(), "Waiting for Home Assistant to add it…" in texts)
        assertTrue(texts.toString(), texts.any { "Devices & services" in it && "discovered" in it && "re-authenticate" in it })
        assertTrue(texts.toString(), "Scan to set it up." !in texts)
        assertTrue("the address stays, for a second computer: $texts", texts.any { "http://$ip:8099/setup" in it })
        assertTrue("no QR code", views(s).none { it is ImageView && it.isShown && it.drawable is BitmapDrawable })
        assertEquals(emptyList<String>(), taps(s))
    }

    @Test fun onceAddedTheNextStepPointsAtTheSetupPageAgain() {
        val s = screen(SetupFlow.PANES, Input.NONE, done = setOf(SetupFlow.WIFI, SetupFlow.HA, SetupFlow.SIGNIN), signedIn = true)
        assertTrue("Scan to set it up." in shown(s).map { it.first })
    }

    @Test fun theSetupScreenWakesAScreenNothingElseCanWakeOnceEachTimeItAppears() {
        val wakes = ArrayList<String>()
        val s = screen(SetupFlow.HA, Input.NONE) { it.presenceSensor = false; it.wakeScreen = { r -> wakes += r } }
        assertEquals(listOf("setup"), wakes)
        s.render(model(SetupFlow.HA, signedIn = true))
        assertEquals("not again while it stays up", listOf("setup"), wakes)
        s.hide()
        s.render(model(SetupFlow.HA))
        assertEquals("again when it comes back", listOf("setup", "setup"), wakes)
    }

    @Test fun aScreenThatCanBeWokenOtherwiseIsLeftAlone() {
        val wakes = ArrayList<String>()
        screen(SetupFlow.HA, Input.NONE) { it.presenceSensor = true; it.wakeScreen = { r -> wakes += r } }
        screen(SetupFlow.HA, Input.TOUCH) { it.presenceSensor = false; it.wakeScreen = { r -> wakes += r } }
        assertEquals(emptyList<String>(), wakes)
    }

    @Test fun withoutWifiItSaysToUseKiln() {
        val s = screen(SetupFlow.WIFI, Input.NONE, wifi = offline, panelIp = null)
        val texts = shown(s).map { it.first }
        assertTrue(texts.toString(), "Waiting for Wi-Fi." in texts)
        assertEquals(emptyList<String>(), taps(s))
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
        assertEquals(emptyList<String>(), taps(s))
    }

    @Test fun aTouchFrameKeepsItsButtons() {
        val taps = taps(screen(SetupFlow.HA, Input.TOUCH))
        assertTrue(taps.toString(), "Skip" in taps)
    }

}
