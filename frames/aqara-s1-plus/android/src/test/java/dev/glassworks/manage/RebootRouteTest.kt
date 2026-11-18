package dev.glassworks.manage

import android.Manifest
import android.app.Application
import android.os.Looper
import android.os.PowerManager
import dev.glassworks.frame.GenericAndroid
import dev.glassworks.platform.AppBuild
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import sh.edm.s1kiosk.KioskApp
import dev.glassworks.KioskPrefs
import dev.glassworks.auth.HaTokenClient
import dev.glassworks.auth.HttpPoster
import dev.glassworks.auth.SignInServer
import java.time.Duration
import sh.edm.s1kiosk.manage.S1Manage

/**
 * #198: on Android 6 a device owner can't reboot (DevicePolicyManager.reboot is Android 7), but a privileged system
 * app holding REBOOT can: the lululemon Mirror's, flashed into /system/priv-app. The Manage page's Reboot follows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23], application = Application::class)
class RebootRouteTest {
    private val app get() = RuntimeEnvironment.getApplication()

    private fun actions() = HaTokenClient(object : HttpPoster {
        override fun postForm(url: String, body: String) = 0 to ""
        override fun get(url: String, maxBytes: Int) = 0 to ""
    }).let { tokens ->
        S1Manage(app, KioskApp.runtime(GenericAndroid(app), AppBuild("1.0.0", 10000, debug = false)), SignInServer(0, { null }, tokens, { _, _ -> }), tokens, KioskPrefs(app)).actions
    }

    @Test fun withoutRebootOrDeviceOwnerThereIsNoReboot() {
        val a = actions()
        assertFalse(a.canReboot)
        assertEquals("The app can only reboot the panel as its device owner (Android 7 or newer), or as a system app.", a.reboot())
    }

    @Test fun aSystemAppWithRebootRebootsThroughPowerManager() {
        shadowOf(app).grantPermissions(Manifest.permission.REBOOT)
        val a = actions()
        assertTrue(a.canReboot)
        assertNull(a.reboot())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertEquals(listOf<String?>(null), shadowOf(app.getSystemService(PowerManager::class.java)).rebootReasons)
    }
}
