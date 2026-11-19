package dev.glassworks.s1

import android.content.Intent
import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import dev.glassworks.engine.gecko.GeckoPinHarness
import dev.glassworks.engine.gecko.GeckoShadows

/**
 * The real MainActivity takes the Wi-Fi lock for live updates (#644) while it is started and the screen is on, and
 * drops it when the screen goes off, when it stops and when it is destroyed. On the S1's Android 10.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [29],
    application = KioskApp::class,
    instrumentedPackages = ["org.mozilla.geckoview"],
    shadows = [
        GeckoShadows.ShadowRuntime::class, GeckoShadows.ShadowExtensionController::class,
        GeckoShadows.ShadowSessionController::class, GeckoShadows.ShadowSession::class, GeckoShadows.ShadowView::class,
    ],
)
class LiveUpdatesWifiLockWiringTest {
    private fun GeckoPinHarness.lockLog() = logs("Glassworks.Wifi").filter { it.startsWith("Wi-Fi lock for live updates") }

    private fun GeckoPinHarness.screen(on: Boolean) {
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(on)
        app.sendBroadcast(Intent(if (on) Intent.ACTION_SCREEN_ON else Intent.ACTION_SCREEN_OFF))
        idle()
    }

    @Test fun followsTheScreenAndTheActivity() {
        val h = GeckoPinHarness()
        h.screen(on = false)
        h.screen(on = true)
        h.controller.pause().stop()
        h.idle()
        h.controller.start().resume()
        h.idle()
        h.controller.pause().stop().destroy()
        h.idle()
        val held = "Wi-Fi lock for live updates held"
        val released = "Wi-Fi lock for live updates released"
        assertEquals(
            "started, screen off, screen on, stopped, started, destroyed",
            listOf(held, released, held, released, held, released),
            h.lockLog(),
        )
    }

    @Test fun notTakenWhileTheScreenIsOff() {
        val h = GeckoPinHarness()
        h.screen(on = false)
        h.controller.pause().stop()
        h.controller.start().resume()
        h.idle()
        assertEquals("a start with the screen off takes nothing", listOf("Wi-Fi lock for live updates held", "Wi-Fi lock for live updates released"), h.lockLog())
        h.controller.pause().stop().destroy()
    }
}
