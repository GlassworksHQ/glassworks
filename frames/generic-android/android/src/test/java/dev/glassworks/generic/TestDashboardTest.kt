/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.generic

import android.app.Application
import android.content.Intent
import dev.glassworks.GlassworksApp
import dev.glassworks.GlassworksRuntime
import dev.glassworks.KioskPrefs
import dev.glassworks.auth.HaAuth
import dev.glassworks.engine.Engine
import dev.glassworks.engine.gecko.GeckoPinHarness
import dev.glassworks.engine.gecko.GeckoShadows
import dev.glassworks.platform.AppBuild
import dev.glassworks.screen.TestHold
import dev.glassworks.setup.SetupOverlay
import dev.glassworks.update.ReleaseLine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Clarity's test dashboard (`--ez test_hold true --es test_dashboard <loopback URL>`). The Pixel Tablet's run of
 * 2026-11-18: the runner pointed the saved dashboard at its fake Home Assistant, which moved the panel's Home Assistant
 * address, so the sign-in moved (#496) and the panel asked to sign in again, during the run and after it. The test's
 * dashboard shows instead, and the saved dashboard and the sign-in stay as they were. RFC 5737 addresses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    application = GenericApp::class,
    instrumentedPackages = ["org.mozilla.geckoview"],
    shadows = [
        GeckoShadows.ShadowRuntime::class, GeckoShadows.ShadowExtensionController::class,
        GeckoShadows.ShadowSessionController::class, GeckoShadows.ShadowSession::class, GeckoShadows.ShadowView::class,
    ],
)
class TestDashboardTest {
    private val app = RuntimeEnvironment.getApplication()

    @After fun noHoldLeft() {
        File(app.noBackupFilesDir, "test_hold").delete()
    }

    private fun GeckoPinHarness.loads() = events.filter { ".load " in it }.map { it.substringAfter(".load ") }

    private fun GeckoPinHarness.setupShowing() = rootChildren().filterIsInstance<SetupOverlay>().any { it.isShowing }

    private fun signedInPanel() = GeckoPinHarness(prefs = { it.urlSetting = DASHBOARD }).also { h ->
        h.signIn(HA)
        h.newIntent(Intent().putExtra("reload", true))
    }

    @Test fun theTestsDashboardShowsAndTheSavedOneAndTheSignInStay() = signedInPanel().use { h ->
        val before = h.loads().size
        h.newIntent(Intent().putExtra("test_hold", true).putExtra("test_hold_s", 1800).putExtra("test_dashboard", FAKE))
        assertEquals(listOf(FAKE), h.loads().drop(before))
        assertEquals("the saved dashboard", DASHBOARD, KioskPrefs(h.app).urlSetting)
        assertTrue("still signed in", HaAuth.signedIn)
        assertFalse("Home Assistant didn't move", HaAuth.movedAway)
        assertFalse("nothing covers the test's dashboard", h.setupShowing())

        // A restart of the graded part: the new activity comes back to the test's dashboard.
        val restarted = h.loads().size
        h.newIntent(Intent().putExtra("reload", true))
        h.controller.recreate()
        h.idle()
        assertTrue(h.loads().drop(restarted).toString(), h.loads().drop(restarted).all { it == FAKE })

        val after = h.loads().size
        h.newIntent(Intent().putExtra("test_hold", false))
        val back = h.loads().drop(after)
        assertEquals(back.toString(), 1, back.size)
        assertTrue("the panel's own dashboard, signed in: $back", back[0].startsWith(DASHBOARD) && "external_auth=1" in back[0])
        assertTrue(HaAuth.signedIn)
        assertFalse(HaAuth.movedAway)
    }

    /** What the runner did before: the saved dashboard on the fake's address moves the sign-in (#496). */
    @Test fun pointingTheSavedDashboardAtTheFakeMovesTheSignIn() = signedInPanel().use { h ->
        h.newIntent(Intent().putExtra("url", FAKE))
        assertTrue(HaAuth.movedAway)
        assertFalse(HaAuth.signedIn)
    }

    @Test fun anAddressOffTheDeviceIsRefused() = signedInPanel().use { h ->
        val before = h.loads().size
        h.newIntent(Intent().putExtra("test_hold", true).putExtra("test_dashboard", "http://192.0.2.99:8123/"))
        assertEquals(emptyList<String>(), h.loads().drop(before))
        assertEquals(null, TestHold.dashboard(h.app))
    }

    /** The legacy engine holds pages to the origin it started with (#198): the test's dashboard restarts the app. */
    @Test @Config(application = LegacyApp::class)
    fun theLegacyEngineRestartsForItAndStartsOnItsOrigin() = signedInPanel().use { h ->
        h.newIntent(Intent().putExtra("test_hold", true).putExtra("test_dashboard", FAKE))
        assertTrue(h.logs("Glassworks").toString(), h.logs("Glassworks").any { it.startsWith("restart deferred") })
        assertEquals(FAKE, TestHold.dashboard(h.app))
        assertEquals("the new process's engine", FAKE, GenericApp.engineSettings(h.app, BUILD, GenericApp.LEGACY_APPLICATION_ID).dashboard)
        assertEquals("the current engine doesn't read it", DASHBOARD, GenericApp.engineSettings(h.app, BUILD).dashboard)
        assertEquals(DASHBOARD, KioskPrefs(h.app).urlSetting)
    }

    private class LockedDown(engine: Engine) : Engine by engine {
        override val lockedDown = true
    }

    class LegacyApp : Application(), GlassworksApp {
        override val glassworks: GlassworksRuntime by lazy {
            GlassworksRuntime(GenericApp.frame(this), BUILD, LockedDown(GenericApp.engine(BUILD)), GenericParts(),
                releases = ReleaseLine.GENERIC_ANDROID_LEGACY)
        }
    }

    private companion object {
        val BUILD = AppBuild("1.0.0", 10000, debug = false, product = GenericApp.PRODUCT)
        const val HA = "http://192.0.2.10:8123"
        const val DASHBOARD = "http://192.0.2.10:8123/lovelace/0"
        const val FAKE = "http://127.0.0.1:8089/clarity-ref/start"
    }
}
