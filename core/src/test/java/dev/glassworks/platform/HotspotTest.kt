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
import org.junit.Assert.assertFalse
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
        for (level in 23..25) assertTrue("API $level", Hotspot.of(app, handler, PlatformApi(level)) is HiddenApHotspot)
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
        // Name order can't tell: the client's own address never counts, whatever its interface's number.
        assertEquals("the client on wlan1, the AP on wlan0", "192.168.43.1", HotspotAddress.pick(listOf(
            Interface("wlan0", up = true, ipv4 = listOf("192.168.43.1")),
            Interface("wlan1", up = true, ipv4 = listOf("192.168.1.20")),
        ), client = setOf("192.168.1.20")))
        assertNull("only the client is up: no hotspot address", HotspotAddress.pick(listOf(
            Interface("wlan0", up = true, ipv4 = listOf("192.168.1.20")),
        ), client = setOf("192.168.1.20")))
    }

    @Test
    fun `the AP and a lingering client network on the same wlan0 (the S1) still give the AP's address`() {
        // Android still lists the client network on wlan0, with its old address; the interface holds the AP's now.
        assertEquals("192.168.43.1", HotspotAddress.pick(listOf(
            Interface("wlan0", up = true, ipv4 = listOf("192.168.43.1")),
        ), client = setOf("192.168.1.20")))
        // Both addresses on wlan0 at once: the one that isn't the client's.
        assertEquals("192.168.43.1", HotspotAddress.pick(listOf(
            Interface("wlan0", up = true, ipv4 = listOf("192.168.1.20", "192.168.43.1")),
        ), client = setOf("192.168.1.20")))
    }

    @Test
    fun `within one interface the address in a hotspot subnet wins over the interface's own order`() {
        assertEquals("no client list to go by", "192.168.43.1", HotspotAddress.pick(listOf(
            Interface("wlan0", up = true, ipv4 = listOf("192.168.1.20", "192.168.43.1")),
        )))
        assertEquals("Wi-Fi Direct's subnet too", "192.168.49.1", HotspotAddress.pick(listOf(
            Interface("wlan0", up = true, ipv4 = listOf("192.168.1.20", "192.168.49.1")),
        )))
        assertEquals("no hotspot subnet: the interface's order", "192.168.1.20", HotspotAddress.pick(listOf(
            Interface("wlan0", up = true, ipv4 = listOf("192.168.1.20", "192.168.7.1")),
        )))
    }

    @Test
    fun `the AP on wlan1 with the client on wlan0 gives the AP's address`() {
        assertEquals("192.168.49.1", HotspotAddress.pick(listOf(
            Interface("wlan0", up = true, ipv4 = listOf("192.168.1.20")),
            Interface("wlan1", up = true, ipv4 = listOf("192.168.49.1")),
        ), client = setOf("192.168.1.20")))
        assertEquals("the client's address is skipped even where name order would pick it", "192.168.49.1", HotspotAddress.pick(listOf(
            Interface("wlan1", up = true, ipv4 = listOf("192.168.1.20")),
            Interface("wlan0", up = true, ipv4 = listOf("192.168.49.1")),
        ), client = setOf("192.168.1.20")))
    }

    @Test
    fun `a device on the hotspot's subnet is a client, the panel itself and other networks aren't`() {
        assertTrue(HotspotAddress.onSubnet("192.168.43.206", "192.168.43.42", 24))
        assertTrue("an IPv4-mapped address", HotspotAddress.onSubnet("::ffff:192.168.43.206", "192.168.43.42", 24))
        assertFalse("the panel's own address", HotspotAddress.onSubnet("192.168.43.42", "192.168.43.42", 24))
        assertFalse("loopback", HotspotAddress.onSubnet("127.0.0.1", "192.168.43.42", 24))
        assertFalse("another network", HotspotAddress.onSubnet("192.168.44.5", "192.168.43.42", 24))
        assertTrue("a wider prefix", HotspotAddress.onSubnet("192.168.44.5", "192.168.43.42", 21))
        assertFalse(HotspotAddress.onSubnet(null, "192.168.43.42", 24))
        assertFalse("IPv6", HotspotAddress.onSubnet("fe80::1", "192.168.43.42", 24))
        assertFalse("no prefix", HotspotAddress.onSubnet("192.168.43.206", "192.168.43.42", 0))
    }

    @Test
    fun `a client is on the subnet of the hotspot's own address, with that address's prefix`() {
        val addresses = listOf(HotspotAddress.Address("192.0.2.10", 24), HotspotAddress.Address("192.168.43.1", 24))
        assertTrue(HotspotAddress.isClient("192.168.43.206", "192.168.43.1", addresses))
        assertFalse("the device's other network", HotspotAddress.isClient("192.0.2.20", "192.168.43.1", addresses))
        assertFalse("no hotspot address yet", HotspotAddress.isClient("192.168.43.206", null, addresses))
        assertFalse("its address isn't the device's", HotspotAddress.isClient("192.168.43.206", "192.168.43.1", addresses.take(1)))
    }
}
