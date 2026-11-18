package dev.glassworks.provisioning

import android.app.Application
import android.view.View
import android.view.ViewGroup
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import dev.glassworks.ui.Mockup

/**
 * The panel's "Wi-Fi setup · done" card for each way the Wi-Fi came (#460), at the panel's size, over a plain
 * activity (the card falls back to it without the overlay permission). Made-up network names only.
 *   ./gradlew :kiosk:testDebugUnitTest --tests '*ImprovDoneMockups*' -Ps1MockupDir=<dir>
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImprovDoneMockups {
    @Before fun interFromSources() = Mockup.interFromSources()

    private fun shown(via: ImprovDone.Via, ssid: String?, url: String? = null): View {
        val a = Mockup.activity()
        // The dashboard behind it: the app's dark page.
        a.setContentView(View(a).apply { setBackgroundColor(ImprovOverlay.BG) }, ViewGroup.LayoutParams(-1, -1))
        val o = ImprovOverlay(a).apply { fallbackActivity = { a } }
        val c = ImprovDone.card(via, ssid, url)
        o.show(ImprovOverlay.Model(c.label, c.title, c.body, primary = "Done" to {}, accent = ImprovOverlay.GREEN, mark = c.mark))
        Mockup.idle()
        val decor = a.window.decorView as ViewGroup
        return decor.getChildAt(decor.childCount - 1) // the card's scrim, over the page
    }

    @Test
    fun theCardFallsBackToTheActivityAndShowsItsText() {
        val root = shown(ImprovDone.Via.SETUP_PAGE, "Example-Home")
        Mockup.layout(root)
        assertEquals("Connected to Example-Home", Mockup.label(root, "Connected to Example-Home").text.toString())
    }

    @Test
    fun mockups() {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", Mockup.dir != null)
        Mockup.save("improv-done-01-bluetooth", shown(ImprovDone.Via.BLUETOOTH, "Example-Home", "http://192.0.2.10:8099/"))
        Mockup.save("improv-done-02-setup-page", shown(ImprovDone.Via.SETUP_PAGE, "Example-Home"))
        Mockup.save("improv-done-03-kiln-usb", shown(ImprovDone.Via.COMPUTER, "Example-Home"))
        Mockup.save("improv-done-04-other", shown(ImprovDone.Via.OTHER, null))
        // What #418 showed for every way the Wi-Fi came, the setup page included: Improv's card with no name.
        Mockup.save("improv-done-05-bluetooth-no-name", shown(ImprovDone.Via.BLUETOOTH, null))
    }
}
