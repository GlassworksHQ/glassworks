package dev.glassworks.setup

import android.net.wifi.WifiManager
import dev.glassworks.frame.Input
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.glassworks.provisioning.ImprovController

class OfflineTextTest {
    private val grace = ImprovController.BOOT_GRACE_MS
    private val boot = OfflineText.Facts(wifiEnabled = true, savedNetwork = null, joining = false, wasOnlineBefore = true, offlineForMs = 3_000)

    private fun text(f: OfflineText.Facts, input: Input = Input.TOUCH) = OfflineText.decide(f, grace, input)

    @Test
    fun graceIsImprovsBootGrace() = assertEquals(30_000L, grace)

    @Test
    fun rebootWithASavedNetworkSaysConnecting() {
        val t = text(boot)
        assertTrue(t.connecting)
        assertEquals("Connecting to Wi-Fi…", t.title)
        assertEquals("Home Assistant loads as soon as it's connected", t.detail)
    }

    @Test
    fun savedNetworkListedCountsEvenIfNeverOnline() {
        assertTrue(text(boot.copy(savedNetwork = true, wasOnlineBefore = false)).connecting)
    }

    @Test
    fun joiningNowCountsEvenWithoutAnyHistory() {
        assertTrue(text(boot.copy(savedNetwork = false, wasOnlineBefore = false, joining = true)).connecting)
    }

    @Test
    fun noSavedNetworkShowsTheSetupHint() {
        val t = text(boot.copy(savedNetwork = false))
        assertFalse(t.connecting)
        assertEquals("No network yet", t.title)
        assertEquals(OfflineText.SETUP_HINT, t.detail)
    }

    @Test
    fun unknownSavedStateAndNeverOnlineShowsTheSetupHint() {
        assertFalse(text(boot.copy(savedNetwork = null, wasOnlineBefore = false)).connecting)
    }

    @Test
    fun wifiOffShowsTheHintWithItsOwnTitle() {
        val t = text(boot.copy(wifiEnabled = false, savedNetwork = true, joining = true))
        assertFalse(t.connecting)
        assertEquals("Wi-Fi is off", t.title)
        assertEquals(OfflineText.SETUP_HINT, t.detail)
    }

    @Test
    fun graceOverShowsTheHint() {
        assertTrue(text(boot.copy(offlineForMs = grace - 1)).connecting)
        val t = text(boot.copy(offlineForMs = grace, joining = true, savedNetwork = true))
        assertFalse(t.connecting)
        assertEquals("No network yet", t.title)
    }

    @Test
    fun neverShowsAnSsid() {
        listOf(boot, boot.copy(savedNetwork = false), boot.copy(wifiEnabled = false), boot.copy(offlineForMs = 99_000)).forEach {
            val t = text(it)
            assertTrue(t.title in setOf("Connecting to Wi-Fi…", "No network yet", "Wi-Fi is off"))
        }
    }

    @Test
    fun joiningFromAndroidStates() {
        assertTrue(OfflineText.joining("ASSOCIATING", null))
        assertTrue(OfflineText.joining("COMPLETED", "IDLE"))  // associated, getting an address
        assertTrue(OfflineText.joining(null, "OBTAINING_IPADDR"))
        assertFalse(OfflineText.joining("SCANNING", "DISCONNECTED"))
        assertFalse(OfflineText.joining("DISCONNECTED", null))
        assertFalse(OfflineText.joining("INACTIVE", "IDLE"))
        assertFalse(OfflineText.joining(null, null))
    }

    @Test
    fun wifiStateReadsOnlyAndroidsAnswer() {
        assertEquals(true, OfflineText.wifiEnabled(WifiManager.WIFI_STATE_ENABLED))
        assertEquals(true, OfflineText.wifiEnabled(WifiManager.WIFI_STATE_ENABLING))
        assertEquals(false, OfflineText.wifiEnabled(WifiManager.WIFI_STATE_DISABLED))
        assertEquals(false, OfflineText.wifiEnabled(WifiManager.WIFI_STATE_DISABLING))
        assertNull(OfflineText.wifiEnabled(WifiManager.WIFI_STATE_UNKNOWN))
        assertNull("no WifiManager, or the read threw", OfflineText.wifiEnabled(null))
    }

    @Test
    fun aWifiStateNobodyReadIsNeverOff() {
        // #448: the Mirror said "Wi-Fi is off" while Android had Wi-Fi on.
        val unknown = boot.copy(wifiEnabled = null, savedNetwork = false, wasOnlineBefore = false)
        assertEquals("No network yet", text(unknown).title)
        assertTrue("a join still counts", text(unknown.copy(joining = true)).connecting)
    }

    @Test
    fun theSetupButtonNeedsTouch() {
        val stuck = boot.copy(savedNetwork = false)
        assertEquals(OfflineText.SETUP_ACTION, text(stuck).action)
        assertEquals(OfflineText.SETUP_ACTION, text(stuck.copy(wifiEnabled = false)).action)
        assertNull(text(stuck, Input.NONE).action)
        assertNull(text(stuck.copy(wifiEnabled = false), Input.NONE).action)
        assertNull("connecting has no button", text(boot).action)
    }
}
