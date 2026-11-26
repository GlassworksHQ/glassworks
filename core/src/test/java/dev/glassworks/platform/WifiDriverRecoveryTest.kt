/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.platform

import android.net.wifi.WifiManager.WIFI_STATE_DISABLED
import android.net.wifi.WifiManager.WIFI_STATE_DISABLING
import android.net.wifi.WifiManager.WIFI_STATE_ENABLED
import android.net.wifi.WifiManager.WIFI_STATE_ENABLING
import android.net.wifi.WifiManager.WIFI_STATE_UNKNOWN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lululemon Mirror's stock Android 6 fails to load the Wi-Fi driver on a fast boot, then switches Wi-Fi off for
 * good: the app switches it back on, and only after that failure.
 */
class WifiDriverRecoveryTest {
    private var now = 0L
    private val wakes = mutableListOf<Pair<Long, () -> Unit>>()
    private val logs = mutableListOf<String>()
    private var may = true
    private var state = WIFI_STATE_UNKNOWN
    private var enables = 0
    /** What Wi-Fi does on the next switch-on: the driver loads (true) or not. */
    private var driverLoads = false

    private val recovery = WifiDriverRecovery(
        mayEnable = { may },
        state = { state },
        enable = { enables++; if (driverLoads) state = WIFI_STATE_ENABLING; true },
        schedule = { d, r -> val w = (now + d) to r; wakes += w; { wakes.remove(w) } },
        log = { logs += it },
        now = { now },
    )

    /** Runs what is due up to [ms] from now. */
    private fun advance(ms: Long) {
        val until = now + ms
        while (true) {
            val due = wakes.filter { it.first <= until }.minByOrNull { it.first } ?: break
            wakes.remove(due)
            now = due.first
            due.second()
        }
        now = until
    }

    /** What Android does on the Mirror: unknown (the driver failed), then its own switch-off. */
    private fun driverFails() {
        state = WIFI_STATE_UNKNOWN
        recovery.onState(WIFI_STATE_UNKNOWN, WIFI_STATE_DISABLED)
        state = WIFI_STATE_DISABLED
        recovery.onState(WIFI_STATE_DISABLED, WIFI_STATE_UNKNOWN)
    }

    @Test fun `the failure is unknown, or a switch-off right after it`() {
        assertTrue(WifiDriverRecovery.isDriverFailure(WIFI_STATE_UNKNOWN, WIFI_STATE_DISABLED))
        assertTrue(WifiDriverRecovery.isDriverFailure(WIFI_STATE_UNKNOWN, WIFI_STATE_ENABLING))
        assertTrue("the sticky broadcast after Android's switch-off", WifiDriverRecovery.isDriverFailure(WIFI_STATE_DISABLED, WIFI_STATE_UNKNOWN))
        assertFalse("a plain switch-off", WifiDriverRecovery.isDriverFailure(WIFI_STATE_DISABLED, WIFI_STATE_DISABLING))
        assertFalse(WifiDriverRecovery.isDriverFailure(WIFI_STATE_DISABLING, WIFI_STATE_ENABLED))
        assertFalse(WifiDriverRecovery.isDriverFailure(WIFI_STATE_ENABLED, WIFI_STATE_ENABLING))
        assertFalse("no extras", WifiDriverRecovery.isDriverFailure(-1, -1))
    }

    @Test fun `the Mirror's stock Android says the failure as state 5, its vendor failed state`() {
        val failed = WifiDriverRecovery.VENDOR_STATE_FAILED
        assertTrue("the broadcast the Mirror sends (2026-11-25)", WifiDriverRecovery.isDriverFailure(failed, WIFI_STATE_DISABLED))
        assertTrue(WifiDriverRecovery.isDriverFailure(failed, WIFI_STATE_ENABLING))
        assertTrue("a switch-off right after it", WifiDriverRecovery.isDriverFailure(WIFI_STATE_DISABLED, failed))
        assertFalse("not a plain switch-off", WifiDriverRecovery.isSwitchOff(WIFI_STATE_DISABLED, failed))
    }

    @Test fun `on the Mirror's broadcast alone it switches Wi-Fi on`() {
        // What the Mirror does (dumpsys activity broadcasts, 2026-11-25): one broadcast, 5 after 1, and Android's
        // switch-off after it sends none, so this is also the sticky state an app that starts later gets.
        driverLoads = true
        state = WifiDriverRecovery.VENDOR_STATE_FAILED
        recovery.onState(WifiDriverRecovery.VENDOR_STATE_FAILED, WIFI_STATE_DISABLED)
        assertTrue(recovery.running)
        advance(5_000)
        assertEquals(1, enables)
        state = WIFI_STATE_ENABLED
        recovery.onState(WIFI_STATE_ENABLED, WIFI_STATE_ENABLING)
        assertFalse(recovery.running)
    }

    @Test fun `after the failure it switches Wi-Fi on once Android's own switch-off is through, and stops when it's on`() {
        driverLoads = true
        driverFails()
        assertTrue(recovery.running)
        advance(4_999)
        assertEquals("not before the first delay", 0, enables)
        advance(1)
        assertEquals(1, enables)
        state = WIFI_STATE_ENABLED
        recovery.onState(WIFI_STATE_ENABLED, WIFI_STATE_ENABLING)
        assertFalse(recovery.running)
        advance(600_000)
        assertEquals("nothing more", 1, enables)
        assertEquals("Wi-Fi is on again after 1 try", logs.last())
    }

    @Test fun `it tries again with backoff while the driver still fails, then gives up`() {
        driverFails()
        advance(10 * 60_000L)
        assertEquals(WifiDriverRecovery.DELAYS_MS.size, enables)
        assertFalse(recovery.running)
        assertTrue(logs.last(), logs.last().startsWith("Wi-Fi didn't come back"))
        assertTrue("about two and a half minutes in all", WifiDriverRecovery.DELAYS_MS.sum() in 120_000L..180_000L)
    }

    @Test fun `a later try that finds the driver loaded ends it`() {
        driverFails()
        advance(5_000)
        assertEquals(1, enables)
        driverLoads = true
        advance(10_000)
        assertEquals(2, enables)
        assertTrue("waits for the enabling Wi-Fi", recovery.running)
        state = WIFI_STATE_ENABLED
        advance(15_000)
        assertFalse(recovery.running)
        assertEquals(2, enables)
    }

    @Test fun `a Wi-Fi that's coming on isn't asked again`() {
        driverFails()
        state = WIFI_STATE_ENABLING
        advance(5_000)
        assertEquals(0, enables)
        assertTrue(recovery.running)
    }

    @Test fun `a plain switch-off is left alone`() {
        state = WIFI_STATE_DISABLED
        recovery.onState(WIFI_STATE_DISABLING, WIFI_STATE_ENABLED)
        recovery.onState(WIFI_STATE_DISABLED, WIFI_STATE_DISABLING)
        assertFalse(recovery.running)
        advance(10 * 60_000L)
        assertEquals(0, enables)
    }

    @Test fun `a switch-off after the recovery worked is left alone`() {
        driverLoads = true
        driverFails()
        advance(5_000)
        state = WIFI_STATE_ENABLED
        recovery.onState(WIFI_STATE_ENABLED, WIFI_STATE_ENABLING)
        state = WIFI_STATE_DISABLED
        recovery.onState(WIFI_STATE_DISABLING, WIFI_STATE_ENABLED)
        recovery.onState(WIFI_STATE_DISABLED, WIFI_STATE_DISABLING)
        advance(10 * 60_000L)
        assertEquals(1, enables)
    }

    @Test fun `where the app may not switch Wi-Fi on, it only says so`() {
        may = false
        driverFails()
        assertFalse(recovery.running)
        advance(10 * 60_000L)
        assertEquals(0, enables)
        assertTrue(logs.toString(), logs.isNotEmpty() && logs.all { "can't switch Wi-Fi on" in it })
    }

    @Test fun `one failure is one recovery, and close stops it`() {
        driverFails()
        driverFails()
        assertEquals("one try pending", 1, wakes.size)
        recovery.close()
        assertFalse(recovery.running)
        advance(10 * 60_000L)
        assertEquals(0, enables)
    }

    @Test fun `switching Wi-Fi off during a recovery stops it`() {
        driverLoads = true
        driverFails()
        advance(WifiDriverRecovery.DELAYS_MS[0])
        assertEquals(1, enables)
        // the switch-on is under way, and someone switches Wi-Fi off
        recovery.onState(WIFI_STATE_DISABLING, WIFI_STATE_ENABLING)
        state = WIFI_STATE_DISABLED
        recovery.onState(WIFI_STATE_DISABLED, WIFI_STATE_DISABLING)
        assertFalse(recovery.running)
        advance(WifiDriverRecovery.DELAYS_MS.sum())
        assertEquals(1, enables)
    }

    @Test fun `a switch-off is told from the failure`() {
        assertTrue(WifiDriverRecovery.isSwitchOff(WIFI_STATE_DISABLING, WIFI_STATE_ENABLED))
        assertTrue(WifiDriverRecovery.isSwitchOff(WIFI_STATE_DISABLED, WIFI_STATE_ENABLING))
        assertFalse(WifiDriverRecovery.isSwitchOff(WIFI_STATE_DISABLED, WIFI_STATE_UNKNOWN))
    }

    @Test fun `the answer to an exhausted recovery's last switch-on doesn't start another, a later failure does`() {
        driverFails()
        advance(WifiDriverRecovery.DELAYS_MS.sum())
        assertEquals(WifiDriverRecovery.DELAYS_MS.size, enables)
        assertFalse(recovery.running)
        // the driver fails again in answer to the last switch-on
        driverFails()
        assertFalse(recovery.running)
        advance(WifiDriverRecovery.DELAYS_MS.sum())
        assertEquals(WifiDriverRecovery.DELAYS_MS.size, enables)
        // much later, a failure of its own: a new recovery
        advance(WifiDriverRecovery.OWN_ANSWER_MS)
        driverFails()
        assertTrue(recovery.running)
    }
}
