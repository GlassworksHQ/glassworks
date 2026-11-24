package dev.glassworks.s1

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import dev.glassworks.engine.gecko.GeckoPinHarness
import dev.glassworks.engine.gecko.GeckoShadows
import dev.glassworks.KioskPrefs

/**
 * `--ez vitro true|false` (Settings → Labs' switch from adb): the engine is picked when the runtime is made, before
 * MainActivity reads its intent, so a cold start that changes the setting restarts the process at once. Otherwise
 * the launch would run on the engine the setting had before. A cold start that changes nothing doesn't restart.
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
class VitroExtraTest {
    private fun coldStartRestarts(saved: Boolean, asked: Boolean): Boolean =
        GeckoPinHarness(startIntent = Intent().putExtra("vitro", asked), prefs = { it.vitroEnabled = saved }).use { h ->
            assertEquals(asked, KioskPrefs(h.app).vitroEnabled)
            h.logs("Glassworks").any { it.startsWith("restart deferred") }
        }

    @Test fun turningVitroOffOnAColdStartRestarts() = assertTrue(coldStartRestarts(saved = true, asked = false))

    @Test fun turningVitroOnOnAColdStartRestarts() = assertTrue(coldStartRestarts(saved = false, asked = true))

    @Test fun theSameValueOnAColdStartDoesnt() = assertFalse(coldStartRestarts(saved = false, asked = false))

    @Test fun whileRunningItRestartsAsTheOtherStartSettingsDo() = GeckoPinHarness().use { h ->
        h.newIntent(Intent().putExtra("vitro", true))
        assertTrue(KioskPrefs(h.app).vitroEnabled)
        assertTrue(h.logs("Glassworks").any { it.startsWith("restart deferred") })
    }
}
