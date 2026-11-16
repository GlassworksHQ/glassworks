/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.platform

import android.os.Handler
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * The hidden-API hotspot of Android 6 and 7 against a fake device: it starts and reports, a refusal is a clear
 * failure, and every way it ends puts back the device's hotspot settings and the Wi-Fi client (#197's undo).
 */
@RunWith(RobolectricTestRunner::class)
class HiddenApHotspotTest {
    /** A device: its saved hotspot settings, its Wi-Fi switch, and an AP that answers as told. */
    private class FakeDevice(var wifiOn: Boolean, var config: String? = "Home hotspot") : ApControls<String> {
        var apOn = false
        var refuse: Throwable? = null
        var listener: ((ApControls.State) -> Unit)? = null

        override fun wifiEnabled() = wifiOn
        override fun setWifiEnabled(on: Boolean) { wifiOn = on }
        override fun savedConfig() = config
        override fun restoreConfig(config: String?) { this.config = config }

        override fun setAp(net: HotspotNetwork?, on: Boolean): Boolean {
            refuse?.let { throw it }
            if (on) { config = net!!.ssid; wifiOn = false } // what Android 6 does: saves it, and drops the client
            apOn = on
            return true
        }

        override fun watch(onChange: (ApControls.State) -> Unit): () -> Unit { listener = onChange; return { listener = null } }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val net = HotspotNetwork("Glassworks-TEST", "abcdefghjkmn")
    private val events = mutableListOf<Hotspot.Event>()

    private fun hotspot(d: FakeDevice) = HiddenApHotspot(d, handler, newNetwork = { net }, startTimeoutMs = 5_000)

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `it starts as its own network and says so once the AP is up`() {
        val d = FakeDevice(wifiOn = true)
        val h = hotspot(d)
        h.start { events += it }
        assertTrue(d.apOn)
        d.listener!!(ApControls.State.ENABLED)
        assertEquals(listOf<Hotspot.Event>(Hotspot.Event.Started(net)), events)
    }

    @Test
    fun `stop puts back the device's hotspot settings and turns the Wi-Fi client on again`() {
        val d = FakeDevice(wifiOn = true)
        val h = hotspot(d)
        h.start { events += it }
        d.listener!!(ApControls.State.ENABLED)
        h.stop()
        assertFalse(d.apOn)
        assertEquals("Home hotspot", d.config)
        assertTrue(d.wifiOn)
    }

    @Test
    fun `Wi-Fi that was off before stays off`() {
        val d = FakeDevice(wifiOn = false)
        val h = hotspot(d)
        h.start { events += it }
        h.stop()
        assertFalse(d.wifiOn)
    }

    @Test
    fun `a refusal is a clear failure, and nothing stays changed`() {
        val d = FakeDevice(wifiOn = true).apply { refuse = SecurityException("TETHER_PRIVILEGED") }
        hotspot(d).start { events += it }
        idle()
        assertEquals(listOf<Hotspot.Event>(Hotspot.Event.Failed(HotspotFailure.NOT_ALLOWED)), events)
        assertEquals("Home hotspot", d.config)
        assertTrue(d.wifiOn)
        assertNull(d.listener)
    }

    @Test
    fun `Android ending the AP puts things back too`() {
        val d = FakeDevice(wifiOn = true)
        hotspot(d).start { events += it }
        d.listener!!(ApControls.State.ENABLED)
        d.listener!!(ApControls.State.DISABLED)
        assertEquals(Hotspot.Event.Stopped, events.last())
        assertEquals("Home hotspot", d.config)
        assertTrue(d.wifiOn)
    }

    @Test
    fun `an AP that never comes up fails after the timeout and puts things back`() {
        val d = FakeDevice(wifiOn = true)
        hotspot(d).start { events += it }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertEquals(listOf<Hotspot.Event>(Hotspot.Event.Failed(HotspotFailure.FAILED)), events)
        assertFalse(d.apOn)
        assertEquals("Home hotspot", d.config)
        assertTrue(d.wifiOn)
    }

    @Test
    fun `stop when off changes nothing`() {
        val d = FakeDevice(wifiOn = false, config = null)
        hotspot(d).stop()
        assertFalse(d.wifiOn)
        assertNull(d.config)
    }

    @Test
    fun `its own network has a fresh name and a key that can be typed from the screen`() {
        val a = HiddenApHotspot.randomNetwork()
        val b = HiddenApHotspot.randomNetwork()
        assertTrue(a.ssid, Regex("Glassworks-[A-Z2-9]{4}").matches(a.ssid))
        assertTrue(Regex("[a-z2-9]{12}").matches(a.passphrase))
        assertFalse(Regex("[01oil]").containsMatchIn(a.passphrase))
        assertTrue(a != b)
    }
}
