/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.platform

import android.app.Application
import android.net.wifi.WifiConfiguration
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The device's own hotspot settings as the undo record keeps them: the same settings back on this build, none on another. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23], application = Application::class)
class ReflectedApTest {
    @Suppress("DEPRECATION")
    private val home = WifiConfiguration().apply {
        SSID = "Home hotspot"
        preSharedKey = "home-key-1234"
        allowedKeyManagement.set(4) // WPA2_PSK, hidden on Android 6
    }

    @Test
    fun `the settings come back as they were`() {
        val back = ReflectedAp.decode(ReflectedAp.encode(home))!!
        assertEquals("Home hotspot", back.SSID)
        assertEquals("home-key-1234", back.preSharedKey)
        assertTrue(back.allowedKeyManagement.get(4))
    }

    @Test
    fun `another build's settings are none`() {
        val text = ReflectedAp.encode(home)
        assertTrue(text.startsWith(Build.FINGERPRINT + "\n"))
        assertNull(ReflectedAp.decode("another/build:6.0.1/test-keys\n" + text.substringAfter('\n')))
    }
}
