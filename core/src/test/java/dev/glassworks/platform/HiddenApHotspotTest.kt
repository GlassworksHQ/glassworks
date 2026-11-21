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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import dev.glassworks.secrets.SecretStore
import java.io.File
import java.time.Duration
import javax.crypto.KeyGenerator

/**
 * The hidden-API hotspot of Android 6 and 7 against a fake device: it starts and reports, a refusal is a clear
 * failure, and every way it ends puts back the device's hotspot settings and the Wi-Fi client (#197's undo).
 */
@RunWith(RobolectricTestRunner::class)
class HiddenApHotspotTest {
    /** A device: its saved hotspot settings, its Wi-Fi switch, and an AP that answers as told. */
    private class FakeDevice(var wifiOn: Boolean, var config: String? = "Home hotspot") : ApControls {
        var apOn = false

        /** The network the AP is up as. */
        var apSsid: String? = null
        var refuse: Throwable? = null
        var answer = true

        /** Android refuses to take the hotspot settings back. */
        var refuseConfig = false

        /** The Wi-Fi switch takes a while: on asks Android, and [wifiOn] follows only when the test says so. */
        var wifiLags = false
        var wifiAsked: Boolean? = null
        var listener: ((ApControls.State) -> Unit)? = null

        override fun wifiEnabled() = wifiOn
        override fun apEnabled() = apOn

        // Android 6 ignores the switch while the AP is up (ApEnabledState).
        override fun setWifiEnabled(on: Boolean) { if (!apOn) { wifiAsked = on; if (!wifiLags) wifiOn = on } }
        override fun savedConfig() = config
        override fun restoreConfig(config: String?) { if (refuseConfig) throw SecurityException("no"); this.config = config }

        override fun setAp(net: HotspotNetwork?, on: Boolean): Boolean {
            refuse?.let { throw it }
            if (!answer) return false
            if (on && apOn) return true // up already: Android ignores another start, and keeps the network it has
            if (on) { config = net!!.ssid; wifiOn = false } // what Android 6 does: saves it, and drops the client
            apOn = on
            apSsid = net?.ssid
            return true
        }

        override fun watch(onChange: (ApControls.State) -> Unit): () -> Unit { listener = onChange; return { listener = null } }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val net = HotspotNetwork("Glassworks-TEST", "abcdefghjkmn")
    private val events = mutableListOf<Hotspot.Event>()
    private val logged = mutableListOf<String>()

    /** The hotspot interface's address, as the hotspot reads it; null until tethering gives it one. */
    private var address: String? = "192.168.43.1"

    @get:Rule val dir = TemporaryFolder()

    /** The undo record's file, which outlives the app's process; sealed with a software key, as the Keystore's would. */
    private val undoFile by lazy { File(dir.root, Hotspot.UNDO_FILE) }
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun undo() = ApUndoFile(undoFile, SecretStore { key }, log = { logged += it })

    private fun hotspot(d: FakeDevice, network: HotspotNetwork = net) =
        HiddenApHotspot(d, handler, address = { address }, undo = undo(), newNetwork = { network }, startTimeoutMs = 5_000, log = { logged += it })

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
        assertFalse("the undo record goes once things are back", undoFile.exists())
    }

    @Test
    fun `a run that died with the AP up is put back when the app starts again`() {
        val d = FakeDevice(wifiOn = true)
        hotspot(d).start { events += it }
        d.listener!!(ApControls.State.ENABLED)
        // The process dies: no stop. Android keeps the AP up, with our network as the device's hotspot settings.
        d.listener = null
        assertTrue(d.apOn)
        assertEquals(net.ssid, d.config)

        hotspot(d) // the app's next start
        assertEquals(listOf("setup hotspot: putting back what a run that died left"), logged)
        assertFalse(d.apOn)
        assertEquals("Home hotspot", d.config)
        assertTrue(d.wifiOn)
        assertFalse(undoFile.exists())
    }

    @Test
    fun `after a run that died, the next start's network is the one the AP has, and its stop puts back the device's own`() {
        val d = FakeDevice(wifiOn = true)
        hotspot(d).start { events += it }
        d.listener!!(ApControls.State.ENABLED)
        events.clear()

        val next = HotspotNetwork("Glassworks-NEXT", "pqrstuvwxyz2")
        val h = hotspot(d, next)
        h.start { events += it }
        d.listener!!(ApControls.State.ENABLED)
        assertEquals(listOf<Hotspot.Event>(Hotspot.Event.Started(next)), events)
        assertEquals("the network the screen shows is the AP's", next.ssid, d.apSsid)
        h.stop()
        assertEquals("Home hotspot", d.config)
        assertTrue(d.wifiOn)
    }

    @Test
    fun `an AP that is already on and not ours is a clear failure, and stays as it was`() {
        val d = FakeDevice(wifiOn = false).apply { apOn = true; apSsid = "Home hotspot" }
        val h = hotspot(d)
        h.start { events += it }
        idle()
        assertEquals(listOf<Hotspot.Event>(Hotspot.Event.Failed(HotspotFailure.TETHERING_ON)), events)
        h.stop()
        assertTrue(d.apOn)
        assertEquals("Home hotspot", d.apSsid)
        assertEquals("Home hotspot", d.config)
        assertFalse(undoFile.exists())
    }

    @Test
    fun `the undo record keeps the device's hotspot settings sealed`() {
        val d = FakeDevice(wifiOn = true, config = "Home hotspot key-1234")
        hotspot(d).start { events += it }
        assertTrue(undoFile.exists())
        assertFalse("key-1234" in undoFile.readText())
        assertEquals(ApUndo.Kept.Found(ApBefore("Home hotspot key-1234", wifiOn = true)), undo().read())
    }

    /** The app dies with the AP up, and a new hotspot (the app's next start) is made. */
    private fun diedWithTheApUp(d: FakeDevice) {
        hotspot(d).start { events += it }
        d.listener!!(ApControls.State.ENABLED)
        d.listener = null
        events.clear()
        logged.clear()
    }

    @Test
    fun `a record the key store can't open now stays, and no start replaces it`() {
        val d = FakeDevice(wifiOn = true)
        diedWithTheApUp(d)
        val out = HiddenApHotspot(d, handler, address = { address }, undo = ApUndoFile(undoFile, SecretStore({ key }, { null })),
            newNetwork = { net }, log = { logged += it })
        out.start { events += it }
        idle()
        assertEquals(listOf<Hotspot.Event>(Hotspot.Event.Failed(HotspotFailure.FAILED)), events)
        assertTrue("the record waits", undoFile.exists())
        assertEquals("its record still holds the device's own settings", ApUndo.Kept.Found(ApBefore("Home hotspot", wifiOn = true)), undo().read())

        hotspot(d) // the key store is back
        assertEquals("Home hotspot", d.config)
        assertFalse(d.apOn)
        assertTrue(d.wifiOn)
        assertFalse(undoFile.exists())
    }

    @Test
    fun `settings Android won't take back keep the record, and the next start tries again before anything else`() {
        val d = FakeDevice(wifiOn = true)
        diedWithTheApUp(d)
        d.refuseConfig = true
        val h = hotspot(d)
        assertFalse(d.apOn)
        assertTrue("kept for another try", undoFile.exists())
        h.start { events += it }
        idle()
        assertEquals(listOf<Hotspot.Event>(Hotspot.Event.Failed(HotspotFailure.FAILED)), events)
        assertEquals("not started over a record that isn't back", null, d.apSsid)

        d.refuseConfig = false
        events.clear()
        h.start { events += it }
        assertTrue(d.apOn)
        assertEquals(ApUndo.Kept.Found(ApBefore("Home hotspot", wifiOn = true)), undo().read())
        h.stop()
        assertEquals("Home hotspot", d.config)
        assertFalse(undoFile.exists())
    }

    @Test
    fun `a start while Android is still turning the Wi-Fi client back on keeps it to go back on`() {
        val d = FakeDevice(wifiOn = true).apply { wifiLags = true }
        diedWithTheApUp(d)
        val h = hotspot(d) // puts things back: asks for the client, which isn't on yet
        assertEquals(true, d.wifiAsked)
        assertFalse(d.wifiOn)
        h.start { events += it }
        assertEquals(ApUndo.Kept.Found(ApBefore("Home hotspot", wifiOn = true)), undo().read())
        d.wifiAsked = null
        h.stop()
        assertEquals("the client asked back on at the stop", true, d.wifiAsked)
    }

    @Test
    fun `an undo record that no longer opens is dropped, and changes nothing`() {
        undoFile.writeText("not sealed")
        val d = FakeDevice(wifiOn = false, config = "Home hotspot")
        hotspot(d)
        assertFalse(undoFile.exists())
        assertEquals("Home hotspot", d.config)
        assertFalse(d.wifiOn)
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
    fun `a refusal logs Android's reason once, without the key`() {
        // What the Mirror's Android 6.0.1 throws without WRITE_SETTINGS (Settings.isCallingPackageAllowedToPerformAppOpsProtectedOperation).
        val reason = "dev.glassworks.generic_android.legacy was not granted  this permission: android.permission.WRITE_SETTINGS."
        val d = FakeDevice(wifiOn = true).apply { refuse = SecurityException("$reason (${net.passphrase})") }
        hotspot(d).start { events += it }
        idle()
        assertEquals(1, logged.size)
        assertTrue(logged.single(), logged.single().startsWith("setup hotspot refused: java.lang.SecurityException: $reason"))
        assertFalse(logged.single(), net.passphrase in logged.single())
    }

    @Test
    fun `Android answering false is a refusal too, and logged`() {
        val d = FakeDevice(wifiOn = true).apply { answer = false }
        hotspot(d).start { events += it }
        idle()
        assertEquals(listOf<Hotspot.Event>(Hotspot.Event.Failed(HotspotFailure.NOT_ALLOWED)), events)
        assertEquals(listOf("setup hotspot refused: setWifiApEnabled returned false"), logged)
        assertTrue(d.wifiOn)
    }

    @Test
    fun `a device without the hidden method fails clearly, and nothing stays changed`() {
        val d = FakeDevice(wifiOn = true).apply { refuse = NoSuchMethodException("setWifiApEnabled") }
        hotspot(d).start { events += it }
        idle()
        assertEquals(listOf<Hotspot.Event>(Hotspot.Event.Failed(HotspotFailure.NOT_ALLOWED)), events)
        assertTrue(d.wifiOn)
        assertEquals("Home hotspot", d.config)
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
    fun `up with no address in 5 s (Android 7-1 not tethering it) is a clear failure, and nothing stays changed`() {
        address = null
        val d = FakeDevice(wifiOn = true)
        hotspot(d).start { events += it }
        d.listener!!(ApControls.State.ENABLED)
        assertEquals("not started without an address", emptyList<Hotspot.Event>(), events)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        assertEquals(listOf<Hotspot.Event>(Hotspot.Event.Failed(HotspotFailure.NOT_TETHERED)), events)
        assertFalse(d.apOn)
        assertEquals("Home hotspot", d.config)
        assertTrue(d.wifiOn)
    }

    @Test
    fun `Android ending the AP during the address wait fails it at once, and nothing stays changed`() {
        address = null
        val d = FakeDevice(wifiOn = true)
        hotspot(d).start { events += it }
        d.listener!!(ApControls.State.ENABLED)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        d.listener!!(ApControls.State.DISABLED)
        assertEquals(listOf<Hotspot.Event>(Hotspot.Event.Failed(HotspotFailure.FAILED)), events)
        assertFalse(d.apOn)
        assertEquals("Home hotspot", d.config)
        assertTrue("the client is back at once, not after the 5 s wait", d.wifiOn)
        assertNull(d.listener)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
        assertEquals("no NOT_TETHERED later", 1, events.size)
    }

    @Test
    fun `the old state Android repeats before the AP is up doesn't end the start`() {
        val d = FakeDevice(wifiOn = true)
        hotspot(d).start { events += it }
        d.listener!!(ApControls.State.DISABLED)
        d.listener!!(ApControls.State.ENABLED)
        assertEquals(listOf<Hotspot.Event>(Hotspot.Event.Started(net)), events)
    }

    @Test
    fun `an address that comes a moment after the AP is up starts it`() {
        address = null
        val d = FakeDevice(wifiOn = true)
        hotspot(d).start { events += it }
        d.listener!!(ApControls.State.ENABLED)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_200))
        address = "192.168.43.1"
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        assertEquals(listOf<Hotspot.Event>(Hotspot.Event.Started(net)), events)
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
