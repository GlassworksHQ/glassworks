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
 * #198 Phase B: the `url` extra restarts the process when what the engine starts with changes
 * ([KioskPrefs.engineStart]): the secure-context hosts, and now the dashboard's origin too, which the legacy engine
 * holds every page to. A same-host change of port used to reload only, and the legacy engine then refused the new
 * dashboard until the app restarted. RFC 5737 hosts.
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
class UrlExtraEngineStartTest {
    private val dash = "http://192.0.2.10:8123/lovelace/0"

    private fun restarts(next: String): Boolean = GeckoPinHarness(prefs = { it.urlSetting = dash }).use { h ->
        h.newIntent(Intent().putExtra("url", next))
        assertEquals(next, KioskPrefs(h.app).urlSetting)
        h.logs("Glassworks").any { it.startsWith("restart deferred") }
    }

    @Test fun theSameHostOnAnotherPortRestarts() = assertTrue(restarts("http://192.0.2.10:8124/lovelace/0"))

    @Test fun theSameHostOverHttpsRestarts() = assertTrue(restarts("https://192.0.2.10:8123/lovelace/0"))

    @Test fun anotherPathOnTheSameOriginDoesnt() = assertFalse(restarts("http://192.0.2.10:8123/lovelace/1"))
}
