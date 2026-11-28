/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.generic

import android.os.Looper
import dev.glassworks.KioskPrefs
import dev.glassworks.KioskService
import dev.glassworks.glassworksRuntime
import dev.glassworks.manage.PanelCode
import dev.glassworks.pane.SettingsItem
import dev.glassworks.settings.CoreSettings
import dev.glassworks.settings.S1Settings
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowSystemProperties
import java.time.Duration

/**
 * Showing a panel code on a `SleepMode.BACKLIGHT_OFF` frame (lululemon Mirror) must wake the screen and hold
 * `ScreenPolicy.stayOn` so the idle timeout cannot black out the display while the 5-minute code is active,
 * and release `stayOn` when dismissed, used, or when `KioskService` stops.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = GenericApp::class)
class PanelCodeStaysOnTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private var service: ServiceController<KioskService>? = null

    @Before fun onMirror() {
        ShadowSystemProperties.override("ro.board.platform", "msm8916")
        ShadowSystemProperties.override("ro.bootimage.build.fingerprint", "mirror/mirror/msm8916_64:6.0.1/IFC-6309-2.0-MIR.S22Q401/2:user/test-keys")
        ShadowSettings.setCanDrawOverlays(true)
        KioskPrefs(app).apply {
            idleOffSec = 20
            screenMode = "presence"
        }
    }

    @After fun tearDown() {
        service?.destroy()
        service = null
        app.glassworksRuntime.screen.stop()
        ShadowSettings.setCanDrawOverlays(false)
        ShadowSystemProperties.reset()
    }

    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test fun panelCodeWakesMirrorHoldsStayOnPastIdleTimeoutAndReleasesOnDismiss() {
        service = Robolectric.buildService(KioskService::class.java).create()
        val runtime = app.glassworksRuntime
        val screen = runtime.screen

        // Let the Mirror enter blackout sleep after the 20 s idle timeout.
        advance(25_000)
        assertTrue("Mirror entered blackout sleep", screen.blackout)
        assertFalse("screen is off before code is shown", screen.screenOn)

        // Showing a code wakes the screen and holds stayOn far past the 20 s idle timeout.
        val shown = runtime.panelCode.show("signin", "Sign in to manage this panel", "browser-1")
        assertTrue("code was shown: $shown", shown is PanelCode.Show.Ok)
        advance(100)
        assertTrue("showing a panel code wakes the screen", screen.screenOn)
        assertFalse("blackout lifted while code is shown", screen.blackout)

        advance(120_000)
        assertTrue("screen stays awake past the 20 s idle timeout while code is displayed", screen.screenOn)
        assertFalse("no blackout while code is displayed", screen.blackout)

        // Dismissing the code releases stayOn so the idle timeout runs again.
        runtime.panelCode.dismiss()
        advance(19_000)
        assertTrue("still awake before idle timeout elapses", screen.screenOn)
        advance(2_000)
        assertFalse("screen sleeps again once idle timeout elapses after dismiss", screen.screenOn)
        assertTrue("Mirror returns to blackout sleep", screen.blackout)
    }

    @Test fun completingOrExpiringCodeAndDestroyingServiceReleaseStayOn() {
        service = Robolectric.buildService(KioskService::class.java).create()
        val runtime = app.glassworksRuntime
        val screen = runtime.screen

        // Using the code hides it and releases stayOn.
        val ok = runtime.panelCode.show("signin", "Sign in to manage this panel", "browser-a") as PanelCode.Show.Ok
        advance(60_000)
        assertTrue("awake while code is shown", screen.screenOn)

        assertTrue(runtime.panelCode.check("signin", "browser-a", ok.shown.code) is PanelCode.Check.Ok)
        advance(25_000)
        assertFalse("sleeps after code is used", screen.screenOn)
        assertTrue(screen.blackout)

        // Destroying KioskService while a code is showing also releases stayOn.
        val second = runtime.panelCode.show("signin", "Sign in to manage this panel", "browser-b")
        assertTrue(second is PanelCode.Show.Ok)
        advance(60_000)
        assertTrue("awake while second code is shown", screen.screenOn)

        service?.destroy()
        service = null
        advance(25_000)
        assertFalse("sleeps after KioskService.onDestroy releases stayOn", screen.screenOn)
        assertTrue(screen.blackout)
    }

    @Test fun settingIdleTimeoutToNeverWakesABlackedOutMirror() {
        service = Robolectric.buildService(KioskService::class.java).create()
        val runtime = app.glassworksRuntime
        val screen = runtime.screen

        // Let the Mirror enter blackout sleep after the 20 s idle timeout.
        advance(25_000)
        assertTrue("Mirror entered blackout sleep", screen.blackout)
        assertFalse("screen is off before changing settings", screen.screenOn)

        // Setting "Turn off when idle" to Never (0) via S1Settings wakes the Mirror immediately.
        val model = S1Settings.model(runtime, KioskPrefs(app), runtime.paneDefinitions(app), deviceSettings = false, fullScreen = {}, context = app)
        val idle = model.sections.first { it.id == CoreSettings.SCREEN }.items
            .filterIsInstance<SettingsItem.Duration>()
            .single { it.title == "Turn off when idle" }
        idle.save(0) {}
        advance(100)
        assertTrue("screen wakes when idle timeout is set to Never", screen.screenOn)
        assertFalse("blackout lifted when idle timeout is set to Never", screen.blackout)

        // And it stays awake past the old 20 s timeout.
        advance(60_000)
        assertTrue("screen stays awake with Never", screen.screenOn)
        assertFalse("no blackout with Never", screen.blackout)
    }
}
