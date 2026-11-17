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
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowScanResult
import java.time.Duration

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

    /** Every outcome a join reports, in order. */
    private fun joinOutcomes(password: String = "password123"): MutableList<WifiProvisioner.Outcome> {
        val got = mutableListOf<WifiProvisioner.Outcome>()
        WifiProvisioner(ctx, Handler(Looper.getMainLooper())).connect("Example-WiFi", password) { got += it }
        return got
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun clientDownSwitch(value: Int) {
        @Suppress("DEPRECATION") wm.setWifiEnabled(false)
        Settings.Global.putInt(ctx.contentResolver, Settings.Global.WIFI_ON, value)
    }

    @Test
    fun aClientThatNeverComesBackFailsOnceAfterTheWaitAndSaysSoNotTurnedOff() {
        clientDownSwitch(1)
        val got = joinOutcomes()
        idle(14_000)
        assertEquals("still waiting at 14 s", emptyList<WifiProvisioner.Outcome>(), got)
        idle(3_000)
        assertEquals(1, got.size)
        val f = got.single() as WifiProvisioner.Outcome.Failed
        assertEquals(WifiProvisioner.RADIO_DIDNT_COME_BACK, f.reason)
        idle(30_000)
        assertEquals("exactly one", 1, got.size)
    }

    @Test
    fun aClientBackAfterTwoSecondsIsNoFailure() {
        clientDownSwitch(1)
        val got = joinOutcomes()
        idle(2_000)
        @Suppress("DEPRECATION") wm.setWifiEnabled(true)
        idle(10_000)
        assertTrue(got.toString(), got.none { it is WifiProvisioner.Outcome.Failed && (it.reason == WifiProvisioner.RADIO_DIDNT_COME_BACK || it.reason.startsWith("Wi-Fi is turned off")) })
    }

    @Test
    fun onInAirplaneModeCountsAsOn() {
        @Suppress("DEPRECATION") wm.setWifiEnabled(false)
        Settings.Global.putInt(ctx.contentResolver, Settings.Global.WIFI_ON, 2)
        val progress = join()
        assertFalse(wm.isWifiEnabled)
        assertEquals(listOf("Waiting for Wi-Fi…"), progress)
    }

    @Test
    fun aWpa3PasswordAndroidRefusesFailsTheJoinInsteadOfCrashing() {
        @Suppress("DEPRECATION") wm.setWifiEnabled(true)
        ShadowSettings.setCanDrawOverlays(false) // the suggestion path, where Android's builder checks the password
        // A WPA3 (SAE) network in the scan: WPA3 passwords pass our own check whatever their characters, and Android's
        // setWpa3Passphrase then throws for one that isn't ASCII (CodeRabbit on #418). The BSSID is a placeholder.
        shadowOf(ctx as Application).grantPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(ctx.getSystemService(android.location.LocationManager::class.java)).setLocationEnabled(true)
        shadowOf(wm).setScanResults(listOf(ShadowScanResult.newInstance("Example-WiFi", "02:00:00:00:00:00", "[RSN-SAE-CCMP][ESS]", -50, 2412)))
        val got = joinOutcomes(password = "café12345")
        idle(1_000)
        val f = got.single() as WifiProvisioner.Outcome.Failed
        assertTrue(f.reason, f.reason.startsWith("Android can't use that password"))
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
