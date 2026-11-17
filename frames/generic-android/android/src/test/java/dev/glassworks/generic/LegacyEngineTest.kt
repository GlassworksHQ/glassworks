/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.generic

import android.app.Application
import android.content.Intent
import dev.glassworks.engine.Engine
import dev.glassworks.esphome.ApiEntity
import dev.glassworks.esphome.DeviceIdentity
import dev.glassworks.esphome.EntityCategory
import dev.glassworks.platform.AppBuild
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import sh.edm.s1kiosk.GlassworksApp
import sh.edm.s1kiosk.GlassworksRuntime
import sh.edm.s1kiosk.KioskPrefs
import sh.edm.s1kiosk.esphome.PanelDevice
import sh.edm.s1kiosk.gecko.GeckoPinHarness
import sh.edm.s1kiosk.gecko.GeckoShadows
import sh.edm.s1kiosk.update.ReleaseLine

/**
 * #29: the legacy build's engine is frozen and loads only Home Assistant. Home Assistant gets a diagnostic entity
 * that says so, and remote debugging never comes on. The legacy build has no unit tests of its own
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

    private fun entities(runtime: GlassworksRuntime): List<ApiEntity> {
        val device = PanelDevice(app, runtime, runtime::devicePanes)
        try {
            device.initEntities(
                DeviceIdentity(
                    name = "test-000001", friendlyName = "Test", macAddress = "02:00:00:00:00:01",
                    esphomeVersion = PanelDevice.ESPHOME_VERSION, compilationTime = "test", manufacturer = "Test",
                    model = "Test", voiceFeatures = PanelDevice.VOICE_FEATURES,
                ),
            )
            return device.entityList()
        } finally {
            device.stop()
        }
    }

    @Test fun homeAssistantHearsTheLegacyEngineIsFrozenAndLockedToIt() {
        val frame = GenericApp.frame(app)
        val legacy = GlassworksRuntime(frame, BUILD, LockedDown(GenericApp.engine(BUILD)), GenericParts(), releases = ReleaseLine.GENERIC_ANDROID_LEGACY)
        val engine = entities(legacy).single { it.objectId == "web_engine" }
        assertEquals("Web engine", engine.name)
        assertEquals(EntityCategory.DIAGNOSTIC, engine.category)
        assertFalse("on when Home Assistant adds the device", engine.disabledByDefault)
        assertEquals("Frozen, loads only Home Assistant", engine.shownState())

        assertNull("not on the generic app's engine", entities(GenericApp.runtime(frame, BUILD)).find { it.objectId == "web_engine" })
    }

    @Test @Config(application = LegacyApp::class)
    fun theLegacyBuildIgnoresTheRemoteDebugExtra() {
        GeckoPinHarness(prefs = { it.urlSetting = DASHBOARD }).use { h ->
            h.newIntent(Intent().putExtra("remote_debug", true))
            assertFalse("the setting stays off", KioskPrefs(app).remoteDebugging(false))
            assertTrue(h.logs("S1Kiosk").contains(
                "remote_debug ignored: the legacy engine is frozen and loads only Home Assistant, so it stays off"))
            assertFalse("no restart for it", h.logs("S1Kiosk").any { "restart" in it })
        }
    }

    @Test @Config(application = GenericApp::class)
    fun theGenericAppTakesTheRemoteDebugExtra() {
        GeckoPinHarness(prefs = { it.urlSetting = DASHBOARD }).use { h ->
            h.newIntent(Intent().putExtra("remote_debug", true))
            assertTrue(KioskPrefs(app).remoteDebugging(false))
            assertTrue("it restarts to apply it", h.logs("S1Kiosk").any { "restart" in it })
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
