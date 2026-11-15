package sh.edm.s1kiosk.settings

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.SettingsSection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import sh.edm.s1kiosk.KioskApp
import sh.edm.s1kiosk.gecko.GeckoPinHarness
import sh.edm.s1kiosk.gecko.GeckoShadows
import sh.edm.s1kiosk.ui.Mockup

/**
 * The real MainActivity: when the screen goes off, the settings menu hides a revealed secret (the camera
 * stream's password), so a hallway panel doesn't keep it for the next person (#252, verifier 3965c4ee).
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    application = KioskApp::class,
    instrumentedPackages = ["org.mozilla.geckoview"],
    shadows = [
        GeckoShadows.ShadowRuntime::class, GeckoShadows.ShadowExtensionController::class,
        GeckoShadows.ShadowSessionController::class, GeckoShadows.ShadowSession::class, GeckoShadows.ShadowView::class,
    ],
)
class ScreenOffConcealsTest {
    private fun all(v: View): List<View> = listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())

    @Test fun theScreenGoingOffHidesARevealedPassword() = GeckoPinHarness(prefs = { it.urlSetting = "http://192.0.2.10:8123/lovelace/0" }).use { h ->
        val menu = h.rootChildren().filterIsInstance<KioskSettingsOverlay>().single()
        // The camera stream's sign-in, as #253's sheet shows it.
        val secret = SettingsSection("secret", "Secret", listOf(
            SettingsItem.SignIn("Sign-in", isSet = { true }, user = { "glassworks" }, reveal = { "made-up-pass-1234" }) { _, d -> d(null) },
        ))
        menu.show(KioskSettingsOverlay.Model(listOf(secret), "0.0"))
        menu.previewSection("secret")
        menu.previewEdit("Sign-in") // the sign-in sheet
        h.idle()
        fun password() = all(menu).filterIsInstance<EditText>().firstOrNull { it.isShown && it.text.toString() == "made-up-pass-1234" }
        fun showVisible() = Mockup.label(menu, "Show").isShown
        Mockup.label(menu, "Show").performClick()
        h.idle()
        assertNotNull("Show puts the password in its field", password())
        assertFalse("Show steps aside while it shows", showVisible())

        h.app.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF)) // ScreenPolicy hears it and tells MainActivity
        h.idle()
        assertNull("the field is emptied once the screen is off", password())
        assertTrue("and Show is back", showVisible())
        assertTrue(all(menu).filterIsInstance<EditText>().none { it.text.toString() == "made-up-pass-1234" })
    }
}
