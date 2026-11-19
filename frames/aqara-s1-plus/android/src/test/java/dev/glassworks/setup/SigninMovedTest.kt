package dev.glassworks.setup

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.glassworks.frame.Input
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import dev.glassworks.s1.frame.TestFrames
import dev.glassworks.ui.Mockup

/**
 * Home Assistant moved to an address the panel can't confirm (#467): the Sign in step says why it asks again, with
 * its phone QR code, also on a frame without touch. Its mock-ups (touch, no touch, each with the usual line) are written with
 * `./gradlew :kiosk:testDebugUnitTest --tests '*SigninMovedTest*' -Ps1MockupDir=<dir>`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SigninMovedTest {
    private val moved = "Home Assistant moved to a new address. Sign in again."
    private val online = WifiSetupStatus.Snapshot(WifiSetupStatus.State.CONNECTED, "Example-WiFi")

    private fun screen(input: Input, signinMoved: Boolean, setUp: (SetupOverlay) -> Unit = {}): SetupOverlay {
        val a = Mockup.activity()
        // Each input comes from a real frame: touch is the S1, no touch is the Mirror (#497).
        val frame = if (input == Input.NONE) TestFrames.mirror(a) else TestFrames.s1()
        val s = SetupOverlay(a, frame).also(setUp)
        a.setContentView(s, ViewGroup.LayoutParams(-1, -1))
        s.render(SetupOverlay.Model(step = SetupFlow.SIGNIN, done = setOf(SetupFlow.WIFI, SetupFlow.HA), wifi = online,
            haUrl = "http://192.0.2.20:8123", haSource = "mdns", panelIp = "192.0.2.10", signedIn = true, signinMoved = signinMoved))
        return s
    }

    private fun texts(v: View): List<String> = when {
        !v.isShown -> emptyList()
        v is TextView -> listOf(v.text.toString())
        v is ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        else -> emptyList()
    }

    @Test fun signInSaysHomeAssistantMovedWithItsPhoneCode() {
        for (input in listOf(Input.TOUCH, Input.NONE)) {
            val t = texts(screen(input, signinMoved = true))
            assertTrue("$input: $t", "Scan to sign in." in t)
            assertTrue("$input: $t", moved in t)
        }
    }

    /**
     * #484's rule on a no-touch frame without a presence sensor: only setup can light the screen. Home Assistant added
     * this panel before (its step is done), but after a move only a person scanning the code can sign it in, so the
     * Sign in step wakes the screen and keeps it on (MainActivity's stayOn reads [SetupOverlay.needsHomeAssistant]).
     */
    @Test fun aNoTouchScreenWakesAndStaysOnForTheMovedSignIn() {
        val wakes = ArrayList<String>()
        val s = screen(Input.NONE, signinMoved = true) { it.presenceSensor = false; it.wakeScreen = { r -> wakes += r } }
        assertEquals(listOf("setup"), wakes)
        assertTrue("kept on", s.needsHomeAssistant)

        val calm = screen(Input.NONE, signinMoved = false) { it.presenceSensor = false; it.wakeScreen = { r -> wakes += r } }
        assertEquals("a set-up panel's Sign in step otherwise stays dark", listOf("setup"), wakes)
        assertFalse(calm.needsHomeAssistant)
    }

    @Test fun aFirstSignInKeepsItsUsualLine() {
        val t = texts(screen(Input.TOUCH, signinMoved = false))
        assertTrue(t.toString(), "Sign in to Home Assistant on your phone. Nothing to type here." in t)
        assertFalse(t.toString(), moved in t)
    }

    @Test fun mockups() {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", Mockup.dir != null)
        Mockup.interFromSources()
        for ((name, input, moved) in listOf(
            Triple("setup-signin-usual", Input.TOUCH, false), Triple("setup-signin-moved", Input.TOUCH, true),
            Triple("setup-signin-usual-no-touch", Input.NONE, false), Triple("setup-signin-moved-no-touch", Input.NONE, true),
        )) {
            val s = screen(input, signinMoved = moved)
            Mockup.idle()
            Mockup.save(name, s)
        }
    }
}
