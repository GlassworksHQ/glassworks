package sh.edm.s1kiosk.setup

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.glassworks.frame.Input
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.edm.s1kiosk.ui.Mockup

/**
 * Home Assistant moved to an address the panel can't confirm (#467): the Sign in step says why it asks again, with
 * its phone QR code, also on a frame without touch. Its mock-ups (touch, no touch) are written with
 * `./gradlew :kiosk:testDebugUnitTest --tests '*SigninMovedTest*' -Ps1MockupDir=<dir>`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SigninMovedTest {
    private val moved = "Home Assistant moved to a new address. Sign in again."
    private val online = WifiSetupStatus.Snapshot(WifiSetupStatus.State.CONNECTED, "Example-WiFi")

    private fun screen(input: Input, signinMoved: Boolean): SetupOverlay {
        val a = Mockup.activity()
        val s = s1SetupOverlay(a).also { it.input = input }
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

    @Test fun aFirstSignInKeepsItsUsualLine() {
        val t = texts(screen(Input.TOUCH, signinMoved = false))
        assertTrue(t.toString(), "Sign in to Home Assistant on your phone. Nothing to type here." in t)
        assertFalse(t.toString(), moved in t)
    }

    @Test fun mockups() {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", Mockup.dir != null)
        Mockup.interFromSources()
        for ((name, input) in listOf("setup-signin-moved" to Input.TOUCH, "setup-signin-moved-no-touch" to Input.NONE)) {
            val s = screen(input, signinMoved = true)
            Mockup.idle()
            Mockup.save(name, s)
        }
    }
}
