/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.generic

import android.app.Application
import android.content.Intent
import dev.glassworks.engine.Engine
import dev.glassworks.manage.PanelStatus
import dev.glassworks.platform.AppBuild
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import dev.glassworks.GlassworksApp
import dev.glassworks.GlassworksRuntime
import dev.glassworks.KioskPrefs
import dev.glassworks.auth.HaTokenClient
import dev.glassworks.auth.HttpPoster
import dev.glassworks.auth.SignInServer
import dev.glassworks.engine.gecko.GeckoPinHarness
import dev.glassworks.engine.gecko.GeckoShadows
import dev.glassworks.manage.AppManage
import dev.glassworks.update.ReleaseLine

/**
 * #29: the legacy build's engine is frozen and loads only Home Assistant. The Manage page shows a Legacy chip that
 * says so, and remote debugging never comes on. The legacy build has no unit tests of its own
 * (build.gradle.kts), so these run the release build's code with its engine marked [Engine.lockedDown], as the
 * legacy engine is.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    application = Application::class,
    instrumentedPackages = ["org.mozilla.geckoview"],
    shadows = [
        GeckoShadows.ShadowRuntime::class, GeckoShadows.ShadowExtensionController::class,
        GeckoShadows.ShadowSessionController::class, GeckoShadows.ShadowSession::class, GeckoShadows.ShadowView::class,
    ],
)
class LegacyEngineTest {
    private val app = RuntimeEnvironment.getApplication()

    private class LockedDown(engine: Engine) : Engine by engine {
        override val lockedDown = true
    }

    /** The generic app as its legacy build runs it: its panes and release line, on a locked-down engine. */
    class LegacyApp : Application(), GlassworksApp {
        override val glassworks: GlassworksRuntime by lazy {
            GlassworksRuntime(GenericApp.frame(this), BUILD, LockedDown(GenericApp.engine(BUILD)), GenericParts(),
                releases = ReleaseLine.GENERIC_ANDROID_LEGACY)
        }
    }

    private fun status(runtime: GlassworksRuntime): PanelStatus {
        val tokens = HaTokenClient(object : HttpPoster {
            override fun postForm(url: String, body: String) = 0 to ""
            override fun get(url: String, maxBytes: Int) = 0 to ""
        })
        return AppManage(app, runtime, SignInServer(0, { null }, tokens, { _, _ -> }), tokens, KioskPrefs(app)).status()
    }

    @Test fun theManagePageShowsTheLegacyChipOnlyOnTheLegacyEngine() {
        val frame = GenericApp.frame(app)
        val legacy = GlassworksRuntime(frame, BUILD, LockedDown(GenericApp.engine(BUILD)), GenericParts(), releases = ReleaseLine.GENERIC_ANDROID_LEGACY)
        assertTrue(status(legacy).legacy)
        assertFalse("not on the generic app's engine", status(GenericApp.runtime(frame, BUILD)).legacy)
    }

    @Test @Config(application = LegacyApp::class)
    fun theLegacyBuildIgnoresTheRemoteDebugExtra() {
        GeckoPinHarness(prefs = { it.urlSetting = DASHBOARD }).use { h ->
            h.newIntent(Intent().putExtra("remote_debug", true))
            assertFalse("the setting stays off", KioskPrefs(app).remoteDebugging(false))
            assertTrue(h.logs("Glassworks").contains(
                "remote_debug ignored: the legacy engine is frozen and loads only Home Assistant, so it stays off"))
            assertFalse("no restart for it", h.logs("Glassworks").any { "restart" in it })
        }
    }

    @Test @Config(application = GenericApp::class)
    fun theGenericAppTakesTheRemoteDebugExtra() {
        GeckoPinHarness(prefs = { it.urlSetting = DASHBOARD }).use { h ->
            h.newIntent(Intent().putExtra("remote_debug", true))
            assertTrue(KioskPrefs(app).remoteDebugging(false))
            assertTrue("it restarts to apply it", h.logs("Glassworks").any { "restart" in it })
        }
    }

    @Test fun theLegacyBuildStartsTheEngineWithoutRemoteDebuggingEvenIfTheSettingIsOn() {
        KioskPrefs(app).setRemoteDebugging(true)
        assertFalse(GenericApp.engineSettings(app, BUILD, GenericApp.LEGACY_APPLICATION_ID).remoteDebugging)
        assertTrue("the generic app still takes it", GenericApp.engineSettings(app, BUILD, "dev.glassworks.generic_android").remoteDebugging)
    }

    private companion object {
        val BUILD = AppBuild("1.0.0", 10000, debug = false, product = GenericApp.PRODUCT)
        const val DASHBOARD = "http://192.0.2.10:8123/lovelace/0"
    }
}
