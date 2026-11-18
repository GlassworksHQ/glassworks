package sh.edm.s1kiosk

import android.content.Intent
import dev.glassworks.notifications.NotificationCenter
import dev.glassworks.notifications.NotificationOutputs
import dev.glassworks.notifications.NotificationStore
import dev.glassworks.notifications.NotificationsHub
import dev.glassworks.notifications.Sound
import java.util.concurrent.CompletableFuture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import dev.glassworks.camera.CameraDot
import dev.glassworks.engine.gecko.GeckoPinHarness
import dev.glassworks.engine.gecko.GeckoShadows
import dev.glassworks.notifications.NotificationOverlay
import dev.glassworks.settings.KioskSettingsOverlay
import dev.glassworks.settings.SettingsMenu
import dev.glassworks.setup.ThreadJoinOverlay
import dev.glassworks.TimerOverlay
import dev.glassworks.VoiceOverlay
import dev.glassworks.glassworks

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

    private val dash = "http://homeassistant.local:8123/lovelace/0"

    /** A notifications center on the S1 app's hub, attached as the running pane attaches its own. */
    private fun center(h: GeckoPinHarness): Pair<NotificationsHub, NotificationCenter> {
        val hub = ((h.app as KioskApp).glassworks.app as S1Panes).notifications
        val c = NotificationCenter(
            NotificationStore({ 0L }),
            object : NotificationOutputs {
                override fun render(cards: List<NotificationStore.Entry>, page: NotificationStore.Entry?, list: List<NotificationStore.Entry>) =
                    hub.update { it.copy(cards = cards, page = page, list = list) }
                override fun wake(overNight: Boolean) {}
                override fun play(sound: Sound, critical: Boolean) {}
            },
            report = {}, doNotDisturb = { false }, haBase = { "http://homeassistant.local:8123" }, now = { 0L },
            schedule = { _, _ -> CompletableFuture<Unit>() },
        )
        hub.attach(c)
        return hub to c
    }

    @Suppress("DEPRECATION")
    @Test fun backClosesAnOpenNotificationViewBeforeThePageGoesBack() = GeckoPinHarness(prefs = { it.urlSetting = dash }).use { h ->
        val (hub, c) = center(h)
        try {
            c.handle("Someone is at the door", "Doorbell", """{"url":"/dashboard-door/0"}""")
            h.idle()
            assertNotNull("the view is open", hub.state.page)
            h.navigation.onCanGoBack(h.session, true)
            assertEquals("the page doesn't go back", emptyList<String>(), h.eventsDuring { h.activity.onBackPressed() })
            assertNull("the view closed", hub.state.page)
        } finally {
            hub.detach(c)
        }
    }

    @Suppress("DEPRECATION")
    @Test fun theSettingsMenuOverAnOpenViewGetsBackFirst() = GeckoPinHarness(prefs = { it.urlSetting = dash }).use { h ->
        val (hub, c) = center(h)
        try {
            c.handle("Someone is at the door", "Doorbell", """{"url":"/dashboard-door/0"}""")
            h.idle()
            h.newIntent(Intent().putExtra(SettingsMenu.EXTRA, true))
            val menu = h.rootChildren().filterIsInstance<KioskSettingsOverlay>().single()
            assertTrue("the menu is open", menu.isShowing)
            h.activity.onBackPressed()
            h.idle()
            assertFalse("Back closed the menu", menu.isShowing)
            assertNotNull("and left the view open", hub.state.page)
        } finally {
            hub.detach(c)
        }
    }
}

