/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.generic

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import sh.edm.s1kiosk.KioskPrefs
import sh.edm.s1kiosk.MainActivity
import sh.edm.s1kiosk.gecko.GeckoPinHarness
import sh.edm.s1kiosk.gecko.GeckoShadows

/**
 * CodeRabbit on #364: another app on the device must not change a setting through MainActivity's extras. The
 * real MainActivity runs under GenericApp (GeckoShadows stand in for GeckoView). A start through the launcher
 * alias, which any app may make, changes nothing, at first start and as a new intent; a start of MainActivity
 * itself, which takes WRITE_SECURE_SETTINGS (adb) unless it is the app's own, does.
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
class ExtrasGuardTest {
    private val app = RuntimeEnvironment.getApplication()
    private val before = "http://192.0.2.10:8123/"
    private val sent = "http://192.0.2.66/"

    private fun start(component: String) =
        Intent(Intent.ACTION_MAIN).setClassName(app.packageName, component).putExtra("url", sent)

    private fun url() = KioskPrefs(app).urlSetting

    @Test fun aStartThroughTheLauncherAliasChangesNoSetting() {
        GeckoPinHarness(start(GenericParts.LAUNCHER), prefs = { it.urlSetting = before }).use { h ->
            assertEquals("the alias reaches MainActivity", GenericParts.LAUNCHER, h.activity.intent.component?.className)
            assertEquals(before, url())
            assertTrue(h.logs("S1Kiosk").any { it.startsWith("ignored the extras of a start through") })
        }
    }

    @Test fun aNewIntentThroughTheLauncherAliasChangesNoSetting() {
        GeckoPinHarness(prefs = { it.urlSetting = before }).use { h ->
            h.newIntent(start(GenericParts.LAUNCHER))
            assertEquals(before, url())
        }
    }

    @Test fun adbsDirectStartChangesTheSetting() {
        GeckoPinHarness(start(MainActivity::class.java.name), prefs = { it.urlSetting = before }).use {
            assertEquals(sent, url())
        }
        GeckoPinHarness(prefs = { it.urlSetting = before }).use { h ->
            h.newIntent(start(MainActivity::class.java.name))
            assertEquals(sent, url())
        }
    }

    @Test fun onlyAdbMayStartMainActivityDirectly() {
        val main = app.packageManager.getActivityInfo(android.content.ComponentName(app, MainActivity::class.java), 0)
        assertEquals(android.Manifest.permission.WRITE_SECURE_SETTINGS, main.permission)
        val alias = app.packageManager.getActivityInfo(android.content.ComponentName(app.packageName, GenericParts.LAUNCHER), 0)
        assertEquals("anyone may use the launcher's way in", null, alias.permission)
    }
}
