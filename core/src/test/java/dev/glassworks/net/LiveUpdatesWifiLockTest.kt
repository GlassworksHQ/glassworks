/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.net

import android.app.Application
import android.net.wifi.WifiManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The lock for live updates (#644) on Android 6, the Mirror's, and Android 10, the S1's. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 29], application = Application::class)
class LiveUpdatesWifiLockTest {
    private val app = RuntimeEnvironment.getApplication()
    private val locks get() = shadowOf(app.getSystemService(WifiManager::class.java)).activeLockCount

    @Test fun heldOnlyWhileTheAppIsInFrontWithTheScreenOn() {
        val lock = LiveUpdatesWifiLock(app)
        assertFalse("nothing held before the first update", lock.held)
        for ((inFront, screenOn) in listOf(true to true, true to false, true to true, false to true, true to true, false to false)) {
            lock.update(inFront, screenOn)
            val want = inFront && screenOn
            assertEquals("in front $inFront, screen on $screenOn", want, lock.held)
            assertEquals("in front $inFront, screen on $screenOn", if (want) 1 else 0, locks)
        }
    }

    @Test fun oneReleaseUndoesRepeatedUpdates() {
        val lock = LiveUpdatesWifiLock(app)
        repeat(3) { lock.update(inFront = true, screenOn = true) }
        assertEquals(1, locks)
        lock.update(inFront = false, screenOn = true)
        assertFalse(lock.held)
        assertEquals(0, locks)
        lock.update(inFront = false, screenOn = false)
        assertEquals("a release with nothing held changes nothing", 0, locks)
    }

    @Test fun eachActivityHasItsOwnLock() {
        val old = LiveUpdatesWifiLock(app).apply { update(inFront = true, screenOn = true) }
        val new = LiveUpdatesWifiLock(app).apply { update(inFront = true, screenOn = true) }
        old.update(inFront = false, screenOn = true)
        assertTrue("the old activity stopping after the new one started leaves the new one's lock", new.held)
        assertEquals(1, locks)
    }
}
