package sh.edm.s1kiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import sh.edm.s1kiosk.camera.CameraDot
import sh.edm.s1kiosk.gecko.GeckoPinHarness
import sh.edm.s1kiosk.gecko.GeckoShadows
import sh.edm.s1kiosk.notifications.NotificationOverlay
import sh.edm.s1kiosk.settings.KioskSettingsOverlay
import sh.edm.s1kiosk.setup.ThreadJoinOverlay

/** The activity adds the layers the app builds for it ([AppParts.overlays]) in their order, over the page. */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [30],
    application = KioskApp::class,
    instrumentedPackages = ["org.mozilla.geckoview"],
    shadows = [
        GeckoShadows.ShadowRuntime::class, GeckoShadows.ShadowExtensionController::class,
        GeckoShadows.ShadowSessionController::class, GeckoShadows.ShadowSession::class, GeckoShadows.ShadowView::class,
    ],
)
class PaneOverlaysTest {
    @Test fun theS1sLayersSitUnderTheMenusAndTheCameraDotAboveThem() = GeckoPinHarness().use { h ->
        val kids = h.rootChildren()
        fun at(c: Class<*>) = kids.indexOfFirst { c.isInstance(it) }.also { assertTrue("${c.simpleName} is in the activity", it >= 0) }
        val order = listOf(TimerOverlay::class.java, NotificationOverlay::class.java, VoiceOverlay::class.java, KioskSettingsOverlay::class.java).map(::at)
        assertEquals("timers, notifications, voice, then the settings menu", order.sorted(), order)
        assertTrue("the camera's privacy dot comes after the menus, so they never cover it", at(CameraDot::class.java) > at(ThreadJoinOverlay::class.java))
    }
}
