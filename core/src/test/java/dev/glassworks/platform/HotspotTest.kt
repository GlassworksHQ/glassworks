/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.platform

import android.Manifest
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import dev.glassworks.platform.HotspotAddress.Interface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Which hotspot each Android gets, what it checks before it starts, its QR code and its address. */
@RunWith(RobolectricTestRunner::class)
class HotspotTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val handler = Handler(Looper.getMainLooper())

    @Test
    fun `Android 6 and 7 get the hidden-API hotspot, 8 and newer the local-only one`() {
        for (level in 23..25) assertTrue("API $level", Hotspot.of(app, handler, PlatformApi(level)) is HiddenApHotspot<*>)
        for (level in 26..36) assertTrue("API $level", Hotspot.of(app, handler, PlatformApi(level)) is LocalOnlyHotspot)
    }

    private fun failureOf(platform: PlatformApi): HotspotFailure? {
        var got: HotspotFailure? = null
        LocalOnlyHotspot(app, handler, platform).start { if (it is Hotspot.Event.Failed) got = it.why }
        shadowOf(Looper.getMainLooper()).idle()
        return got
    }

    @Test
    fun `Android 13 and newer refuse without the nearby permission, before asking Android`() {
        shadowOf(app).denyPermissions(Manifest.permission.NEARBY_WIFI_DEVICES)
        assertEquals(HotspotFailure.NO_PERMISSION, failureOf(PlatformApi(33)))
    }

    @Test
    fun `Android 8 to 12 refuse without fine location, and with location off`() {
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        assertEquals(HotspotFailure.NO_PERMISSION, failureOf(PlatformApi(29)))
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        @Suppress("DEPRECATION")
        Settings.Secure.putInt(app.contentResolver, Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF)
        assertEquals(HotspotFailure.LOCATION_OFF, failureOf(PlatformApi(29)))
    }

    @Test
    @Config(sdk = [33]) // the call reaches startLocalOnlyHotspot, which this module's default Robolectric level lacks
    fun `from Android 13 location off doesn't matter`() {
        shadowOf(app).grantPermissions(Manifest.permission.NEARBY_WIFI_DEVICES)
        @Suppress("DEPRECATION")
        Settings.Secure.putInt(app.contentResolver, Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF)
        assertTrue(failureOf(PlatformApi(33)) != HotspotFailure.LOCATION_OFF)
    }

    @Test
    fun `the join QR code is the standard Wi-Fi text, with the special characters escaped`() {
        assertEquals("WIFI:T:WPA;S:AndroidShare_1234;P:abcdefgh;;", HotspotNetwork("AndroidShare_1234", "abcdefgh").qrText())
        assertEquals("""WIFI:T:WPA;S:a\;b\,c\:d\\e\"f;P:p\;w;;""", HotspotNetwork("""a;b,c:d\e"f""", "p;w").qrText())
    }

    @Test
    fun `neither the name nor the key reaches a log`() {
        val s = HotspotNetwork("Glassworks-AB12", "secretkey1").toString()
        assertTrue(s, "secretkey1" !in s && "AB12" !in s)
    }

    @Test
    fun `the address is the Wi-Fi interface's private IPv4, never mobile data's`() {
        val rmnet = Interface("rmnet_data0", up = true, ipv4 = listOf("10.20.30.40"))
        val wlan = Interface("wlan0", up = true, ipv4 = listOf("192.168.43.1"))
        assertEquals("192.168.43.1", HotspotAddress.pick(listOf(rmnet, wlan)))
        assertNull(HotspotAddress.pick(listOf(rmnet)))
    }

    @Test
    fun `an access point interface wins over the client's, and a down or public one doesn't count`() {
        assertEquals("192.168.49.1", HotspotAddress.pick(listOf(
            Interface("wlan0", up = true, ipv4 = listOf("192.168.1.20")),
            Interface("ap0", up = true, ipv4 = listOf("192.168.49.1")),
        )))
        assertNull(HotspotAddress.pick(listOf(Interface("ap0", up = false, ipv4 = listOf("192.168.49.1")))))
        assertNull(HotspotAddress.pick(listOf(Interface("wlan0", up = true, ipv4 = listOf("198.51.100.7")))))
        assertEquals("172.20.0.1", HotspotAddress.pick(listOf(Interface("swlan0", up = true, ipv4 = listOf("172.20.0.1")))))
        assertEquals("the AP on wlan1, the client on wlan0", "192.168.49.1", HotspotAddress.pick(listOf(
            Interface("wlan0", up = true, ipv4 = listOf("192.168.1.20")),
            Interface("wlan1", up = true, ipv4 = listOf("192.168.49.1")),
        )))
        // Name order can't tell: the interface Android's own Wi-Fi network uses is the client, whatever its number.
        assertEquals("the client on wlan1, the AP on wlan0", "192.168.43.1", HotspotAddress.pick(listOf(
            Interface("wlan0", up = true, ipv4 = listOf("192.168.43.1")),
            Interface("wlan1", up = true, ipv4 = listOf("192.168.1.20")),
        ), client = setOf("wlan1")))
        assertNull("only the client is up: no hotspot address", HotspotAddress.pick(listOf(
            Interface("wlan0", up = true, ipv4 = listOf("192.168.1.20")),
        ), client = setOf("wlan0")))
    }
}
