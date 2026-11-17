package dev.glassworks.improv

import android.app.Application
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * After our setup hotspot stops, a device without STA+AP (the S1, Android 10) has the user's switch on but the client
 * still down for a few seconds. A join then must wait for the client, not ask Android to turn Wi-Fi on: that is
 * refused from Android 10 and failed the join at once (#418 S1 retest 3, 2026-11-17).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class WifiProvisionerSwitchTest {
    private val ctx: Context = RuntimeEnvironment.getApplication()
    private val wm = ctx.getSystemService(WifiManager::class.java)

    private fun join(): List<String> {
        val progress = mutableListOf<String>()
        val p = WifiProvisioner(ctx, Handler(Looper.getMainLooper()))
        p.onProgress = { progress += it }
        p.connect("Example-WiFi", "password123") {}
        return progress
    }

    @Test
    fun theSwitchOnWithTheClientDownWaitsAndDoesNotAskToTurnItOn() {
        @Suppress("DEPRECATION") wm.setWifiEnabled(false)
        Settings.Global.putInt(ctx.contentResolver, Settings.Global.WIFI_ON, 1)
        val progress = join()
        assertFalse("never asks Android to turn Wi-Fi on", wm.isWifiEnabled)
        assertEquals(listOf("Waiting for Wi-Fi…"), progress)
    }

    @Test
    fun theSwitchOffStillAsksToTurnItOn() {
        @Suppress("DEPRECATION") wm.setWifiEnabled(false)
        Settings.Global.putInt(ctx.contentResolver, Settings.Global.WIFI_ON, 0)
        val progress = join()
        assertTrue("asked, and Robolectric's Wi-Fi says yes", wm.isWifiEnabled)
        assertEquals(listOf("Turning Wi-Fi on…"), progress)
    }
}
