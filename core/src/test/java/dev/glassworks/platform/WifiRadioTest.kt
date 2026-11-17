/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.platform

import android.app.Application
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.net.wifi.WifiManager
import android.provider.Settings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * The Wi-Fi radio as the setup hotspot needs it (#197), on Robolectric: what counts is the user's switch
 * (`Settings.Global.WIFI_ON`), not the client's state, which Android parks while our own access point runs.
 */
@RunWith(RobolectricTestRunner::class)
class WifiRadioTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val wifi = app.getSystemService(WifiManager::class.java)

    private fun switch(value: Int) = Settings.Global.putInt(app.contentResolver, Settings.Global.WIFI_ON, value)

    @Suppress("DEPRECATION")
    private fun client(on: Boolean) { wifi.isWifiEnabled = on }

    private fun owner() = shadowOf(app.getSystemService(DevicePolicyManager::class.java))
        .setDeviceOwner(ComponentName(app.packageName, "Admin"))

    @Test
    fun `the S1 retest - switch on, client parked for our own AP - is not off, and nothing touches it`() {
        switch(1)
        client(false)
        assertFalse(WifiRadio.switchedOff(app))
        assertFalse(WifiRadio.lockedOff(app, PlatformApi(29)))
        assertTrue(WifiRadio.ensureOn(app, PlatformApi(29)))
        assertFalse("left alone: no setWifiEnabled while our AP holds the radio", wifi.isWifiEnabled)
    }

    @Test
    fun `switched off from Android 10 is locked for an ordinary app, and stays off`() {
        switch(0)
        client(false)
        assertTrue(WifiRadio.lockedOff(app, PlatformApi(29)))
        assertFalse(WifiRadio.ensureOn(app, PlatformApi(34)))
        assertFalse(wifi.isWifiEnabled)
    }

    @Test
    fun `airplane mode - off because of it counts as off, on in it as on`() {
        switch(3)
        assertTrue(WifiRadio.lockedOff(app, PlatformApi(29)))
        switch(2)
        assertFalse(WifiRadio.lockedOff(app, PlatformApi(29)))
    }

    @Test
    fun `switched off on Android 9 and older isn't locked, and comes on`() {
        switch(0)
        client(false)
        assertFalse(WifiRadio.lockedOff(app, PlatformApi(28)))
        assertTrue(WifiRadio.ensureOn(app, PlatformApi(28)))
        assertTrue(wifi.isWifiEnabled)
    }

    @Test
    fun `the device owner may switch it on from Android 10 too, and does`() {
        switch(0)
        client(false)
        owner()
        assertFalse(WifiRadio.lockedOff(app, PlatformApi(29)))
        assertTrue(WifiRadio.ensureOn(app, PlatformApi(29)))
        assertTrue(wifi.isWifiEnabled)
    }

    @Test
    fun `an unreadable switch counts as on`() {
        client(false)
        assertFalse(WifiRadio.switchedOff(app))
        assertFalse(WifiRadio.lockedOff(app, PlatformApi(29)))
    }
}
