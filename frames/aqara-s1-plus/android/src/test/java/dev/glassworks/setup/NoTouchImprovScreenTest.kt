package dev.glassworks.setup

import android.app.Activity
import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.glassworks.frame.Frame
import dev.glassworks.frame.Input
import dev.glassworks.improv.ImprovState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import dev.glassworks.s1.frame.TestFrames
import dev.glassworks.ui.Mockup

/**
 * #611: on the Mirror (no touch) Bluetooth setup opens by itself for its first 5 minutes with no network. The Wi-Fi step
 * says where to send the Wi-Fi from, with the countdown, and shows no button. Once the window closes, the step goes
 * back to what it showed before: the setup hotspot when it is on, else where to set the panel up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w540dp-h960dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NoTouchImprovScreenTest {
    @Before fun interFromSources() = Mockup.interFromSources()

    private val offline = WifiSetupStatus.Snapshot(WifiSetupStatus.State.NOT_CONNECTED)
    private val open = ImprovFacts(running = true, advertising = true, state = ImprovState.AUTHORIZED, authLeftMs = 299_001)
    private val closed = ImprovFacts(running = true, advertising = true, state = ImprovState.AUTHORIZATION_REQUIRED)

    private fun screen(improv: ImprovFacts, input: Input, frame: (Activity) -> Frame): SetupOverlay {
        val a = Mockup.activity()
        val s = SetupOverlay(a, frame(a))
        a.setContentView(s, ViewGroup.LayoutParams(-1, -1))
        s.render(SetupOverlay.Model(
            step = SetupFlow.WIFI, done = emptySet(), wifi = offline, haUrl = null, haSource = "", panelIp = null,
            signedIn = false, improv = WifiStep.improv(improv, input), hotspot = null,
        ))
        Mockup.idle()
        Mockup.layout(s, 1080, 1920)
        return s
    }

    private fun views(v: View): Sequence<View> = sequence {
        yield(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) yieldAll(views(v.getChildAt(i)))
    }

    private fun texts(s: View) = views(s).filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }.toList()

    @Test fun theOpenWindowSaysWhatToDoWithNoButton() {
        val s = screen(open, Input.NONE) { TestFrames.mirror(it) }
        val t = texts(s)
        assertTrue(t.toString(), "Send the Wi-Fi from a phone." in t)
        assertTrue(t.toString(), "Open Home Assistant or improv-wifi.com on your phone to send the Wi-Fi." in t)
        assertTrue(t.toString(), "Bluetooth setup · open for 5:00" in t)
        assertFalse(t.toString(), t.any { it == "Allow" || it == "Stop" || it == "Not now" })
    }

    @Test fun afterTheWindowTheStepIsAsBefore() {
        val t = texts(screen(closed, Input.NONE) { TestFrames.mirror(it) })
        assertTrue(t.toString(), "Waiting for Wi-Fi." in t)
        assertFalse(t.toString(), "Send the Wi-Fi from a phone." in t)
    }

    @Test fun aTouchscreenKeepsAllow() {
        val t = texts(screen(open.copy(authLeftMs = 119_001), Input.TOUCH) { TestFrames.tablet(it) })
        assertTrue(t.toString(), "Waiting for Wi-Fi details" in t)
        assertFalse(t.toString(), "Send the Wi-Fi from a phone." in t)
    }
}
