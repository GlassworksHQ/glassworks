package sh.edm.s1kiosk.settings

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.SettingsSection
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import sh.edm.s1kiosk.KioskApp
import sh.edm.s1kiosk.gecko.GeckoPinHarness
import sh.edm.s1kiosk.gecko.GeckoShadows

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
        val secret = SettingsSection("secret", "Secret", listOf(
            SettingsItem.Info({ "Sign-in" }, { "glassworks · password hidden, tap to show" }, reveal = { "glassworks · made-up-pass-1234 · tap to hide" }),
        ))
        menu.show(KioskSettingsOverlay.Model(listOf(secret), "0.0"))
        menu.previewSection("secret")
        h.idle()
        fun shown() = all(menu).filterIsInstance<TextView>().any { it.text.toString().contains("made-up-pass-1234") }
        val row = all(menu).filterIsInstance<TextView>().first { it.text.toString().startsWith("glassworks ·") }
        generateSequence(row as View) { it.parent as? View }.first { it.isClickable }.performClick()
        assertEquals("revealed on the tap", true, shown())

        h.app.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF)) // ScreenPolicy hears it and tells MainActivity
        h.idle()
        assertEquals("hidden once the screen is off", false, shown())
    }
}
