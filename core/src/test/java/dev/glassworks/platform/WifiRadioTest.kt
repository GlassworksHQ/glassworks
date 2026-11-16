/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.platform

import android.app.Application
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.net.wifi.WifiManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * The Wi-Fi radio as the setup hotspot needs it (#197), on Robolectric's WifiManager: off and locked from Android
 * 10 for an ordinary app, turned on where the app may (Android 9 and older, the device owner).
 */
@RunWith(RobolectricTestRunner::class)
class WifiRadioTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val wifi = app.getSystemService(WifiManager::class.java)

    @Suppress("DEPRECATION")
    private fun radio(on: Boolean) { wifi.isWifiEnabled = on }

    private fun owner() = shadowOf(app.getSystemService(DevicePolicyManager::class.java))
        .setDeviceOwner(ComponentName(app.packageName, "Admin"))

    @Test
    fun `on is never locked`() {
        radio(true)
        assertFalse(WifiRadio.lockedOff(app, PlatformApi(29)))
        assertTrue(WifiRadio.ensureOn(app, PlatformApi(29)))
    }

    @Test
    fun `off from Android 10 is locked for an ordinary app, and stays off`() {
        radio(false)
        assertTrue(WifiRadio.lockedOff(app, PlatformApi(29)))
        assertFalse(WifiRadio.ensureOn(app, PlatformApi(34)))
        assertFalse(wifi.isWifiEnabled)
    }

    @Test
    fun `off on Android 9 and older isn't locked, and comes on`() {
        radio(false)
        assertFalse(WifiRadio.lockedOff(app, PlatformApi(28)))
        assertTrue(WifiRadio.ensureOn(app, PlatformApi(28)))
        assertTrue(wifi.isWifiEnabled)
    }

    @Test
    fun `the device owner may turn it on from Android 10 too, and does`() {
        radio(false)
        owner()
        assertFalse(WifiRadio.lockedOff(app, PlatformApi(29)))
        assertTrue(WifiRadio.ensureOn(app, PlatformApi(29)))
        assertTrue(wifi.isWifiEnabled)
    }
}
