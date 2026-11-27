/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.setup

import dev.glassworks.platform.Hotspot
import dev.glassworks.platform.HotspotFailure
import dev.glassworks.platform.HotspotNetwork
import dev.glassworks.setup.SetupHotspot.View
import dev.glassworks.setup.SetupHotspotPolicy.Stop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hotspot is on exactly when the rule says, on a fake clock with a fake Wi-Fi state and a fake hotspot,
 * and every way out stops it.
 */
class SetupHotspotTest {
    private val min = 60_000L
    private val net = HotspotNetwork("AndroidShare_1234", "k3yk3yk3yk3y")

    /** Records starts and stops; [up] answers the last start. */
    private class FakeHotspot : Hotspot {
        var starts = 0
        var stops = 0
        var listener: ((Hotspot.Event) -> Unit)? = null
        val on get() = listener != null

        override fun start(onEvent: (Hotspot.Event) -> Unit) { starts++; listener = onEvent }
        override fun stop() { stops++; listener = null }
    }

    private var now = 0L
    private var connected = false
    private val wakes = mutableListOf<Pair<Long, () -> Unit>>()
    private val hotspot = FakeHotspot()
    /** The radio is off and can't be turned on (Android 10+), when set. */
    private var radioLocked = false
    /** When set, the scan before a start is held here until the test lets it finish. */
    private var holdScan = false
    private var scanDone: (() -> Unit)? = null
    private val logged = mutableListOf<String>()
    private var changes = 0
    /** The Wi-Fi switch is off and the app may switch it on, when set; switching it on clears it. */
    private var radioOff = false
    private var radioSwitches = 0
    private val setup = SetupHotspot(
        hotspot, connected = { connected }, clock = { now },
        onChange = { changes++ }, log = { logged += it },
        schedule = { d, r -> val w = (now + d) to r; wakes += w; { wakes.remove(w) } },
        blocked = { if (radioLocked) HotspotFailure.WIFI_OFF else null },
        beforeStart = { start -> if (holdScan) scanDone = start else start() },
        switchRadioOn = { if (radioOff) { radioOff = false; radioSwitches++; true } else false },
    )

    /** Moves the clock to [t], running each wake that falls due on the way. */
    private fun at(t: Long) {
        repeat(1_000) {
            val next = wakes.minByOrNull { it.first }?.takeIf { it.first <= t } ?: run { now = t; return }
            wakes.remove(next)
            now = next.first
            next.second()
        }
        throw AssertionError("wakes never settle: one is due again at once")
    }

    private fun up() = hotspot.listener!!(Hotspot.Event.Started(net))

    @Test
    fun `offline 3 minutes after starting, the hotspot comes on and the screen gets its network and countdown`() {
        setup.start()
        at(3 * min - 1)
        assertFalse(hotspot.on)
        at(3 * min)
        assertTrue(hotspot.on)
        assertEquals(View.Starting, setup.view)
        assertFalse("not up before Android says so", setup.isOn)
        up()
        assertTrue(setup.isOn)
        at(4 * min)
        assertEquals(View.On(net, 14 * min, asked = false), setup.view)
    }

    @Test
    fun `with Wi-Fi off, rule 1 switches it on and gives the saved network its minutes before the hotspot`() {
        // The Mirror, 2026-11-25: the driver failed at boot, the hotspot switched Wi-Fi on and took the radio 4 s later
        radioOff = true
        setup.start()
        at(3 * min)
        assertEquals("switched on", 1, radioSwitches)
        assertFalse("no hotspot while the saved network joins", hotspot.on)
        at(6 * min - 1)
        assertFalse(hotspot.on)
        at(6 * min)
        assertTrue("still offline 3 minutes later: the hotspot", hotspot.on)
        assertEquals(1, radioSwitches)
    }

    @Test
    fun `with Wi-Fi off, a saved network that joins after the switch-on keeps the hotspot off`() {
        radioOff = true
        setup.start()
        at(3 * min)
        connected = true
        setup.refresh()
        at(30 * min)
        assertEquals(0, hotspot.starts)
    }

    @Test
    fun `an asked hotspot doesn't wait for the radio`() {
        radioOff = true
        setup.start()
        assertTrue(setup.ask())
        assertTrue(hotspot.on)
        assertEquals("the scan before it switches the radio on, not this", 0, radioSwitches)
    }

    @Test
    fun `never while connected, not even when asked`() {
        connected = true
        setup.start()
        at(30 * min)
        assertFalse(setup.ask())
        assertEquals(0, hotspot.starts)
    }

    @Test
    fun `a connection nobody reported still stops it within 30 seconds`() {
        setup.start(); at(3 * min); up()
        connected = true // no refresh(): a network callback that never came
        at(3 * min + 30_000)
        assertFalse(hotspot.on)
        assertEquals(View.Off(Stop.CONNECTED, null), setup.view)
    }

    @Test
    fun `connecting stops it`() {
        setup.start(); at(3 * min); up()
        connected = true
        setup.refresh()
        assertFalse(hotspot.on)
        assertEquals(View.Off(Stop.CONNECTED, null), setup.view)
    }

    @Test
    fun `Wi-Fi configured stops it before the call returns, so the radio is free to join`() {
        setup.start(); at(3 * min); up()
        setup.configured()
        assertFalse(hotspot.on)
        assertFalse(setup.isOn)
        assertEquals(1, hotspot.stops)
        assertEquals(View.Off(Stop.CONFIGURED, null), setup.view)
    }

    @Test
    fun `Stop and setup's end stop it, and it stays off`() {
        setup.start(); at(3 * min); up()
        setup.cancel()
        assertFalse(hotspot.on)
        at(120 * min)
        assertEquals(1, hotspot.starts)
    }

    @Test
    fun `after 15 minutes it stops, then the saved network gets 3 minutes before it's back`() {
        setup.start(); at(3 * min); up()
        at(18 * min)
        assertFalse(hotspot.on)
        assertEquals(View.Off(Stop.TIMED_OUT, null), setup.view)
        at(21 * min)
        assertTrue(hotspot.on)
        assertEquals(2, hotspot.starts)
    }

    @Test
    fun `asked for on the setup screen, it starts at once`() {
        setup.start()
        assertTrue(setup.ask())
        assertTrue(hotspot.on)
        up()
        assertEquals(View.On(net, 15 * min, asked = true), setup.view)
    }

    @Test
    fun `a failed start shows why and doesn't try again by itself`() {
        setup.start(); at(3 * min)
        hotspot.listener!!(Hotspot.Event.Failed(HotspotFailure.NO_PERMISSION))
        assertEquals(View.Off(Stop.FAILED, HotspotFailure.NO_PERMISSION), setup.view)
        at(120 * min)
        assertEquals(1, hotspot.starts)
    }

    @Test
    fun `Android closing it by itself (nobody joined) is a stop, and the hotspot comes back 3 minutes later`() {
        setup.start(); at(3 * min); up()
        at(13 * min)
        hotspot.listener!!(Hotspot.Event.Stopped) // the S1's SoftApManager: 10 minutes with no station
        assertEquals(View.Off(Stop.ANDROID, null), setup.view)
        at(16 * min - 1)
        assertEquals(1, hotspot.starts)
        at(16 * min)
        assertEquals(2, hotspot.starts)
    }

    @Test
    fun `Close it, then Wi-Fi off and on again, keeps it closed`() {
        setup.start(); at(3 * min); up()
        setup.cancel()
        radioLocked = true
        setup.refresh() // a network callback while the radio is off
        radioLocked = false
        setup.refresh()
        at(60 * min)
        assertEquals("closed until the next Wi-Fi is set", 1, hotspot.starts)
        assertEquals(View.Off(Stop.CANCELLED, null), setup.view)
    }

    @Test
    fun `it scans before it starts, while the radio still can, and starts only once the scan is done`() {
        holdScan = true
        setup.start(); at(3 * min)
        assertEquals("scanning first", 0, hotspot.starts)
        assertEquals(View.Starting, setup.view)
        scanDone!!()
        assertEquals(1, hotspot.starts)
    }

    @Test
    fun `a connection during the scan means no start at all`() {
        holdScan = true
        setup.start(); at(3 * min)
        connected = true
        setup.refresh()
        scanDone!!()
        assertEquals(0, hotspot.starts)
        assertEquals(View.Off(Stop.CONNECTED, null), setup.view)
    }

    @Test
    fun `with the radio off and locked, it never starts, and the screen says why`() {
        radioLocked = true
        setup.start()
        at(30 * min)
        assertEquals(0, hotspot.starts)
        assertEquals(View.Off(null, HotspotFailure.WIFI_OFF), setup.view)
        assertFalse("asked for, it is refused too", setup.ask())
        assertEquals(0, hotspot.starts)
    }

    @Test
    fun `a block that shows up while ours runs doesn't stop it (Android parks the client for our own AP)`() {
        setup.start(); at(3 * min); up()
        radioLocked = true // what the S1 retest on b8f827fe misread: the client parked while our AP is up
        at(3 * min + 30_000)
        setup.refresh()
        assertTrue("our own hotspot keeps running", hotspot.on)
        assertTrue(setup.view is View.On)
        assertEquals(0, hotspot.stops)
        connected = true // and the rule still runs: a connection stops it as ever
        setup.refresh()
        assertFalse(hotspot.on)
        assertEquals(View.Off(Stop.CONNECTED, null), setup.view)
    }

    @Test
    fun `a block while it is starting (the scan before the start) doesn't stop it either`() {
        holdScan = true
        setup.start(); at(3 * min)
        radioLocked = true
        setup.refresh()
        scanDone!!()
        assertEquals(1, hotspot.starts)
        up()
        at(3 * min + 30_000)
        assertTrue("still blocked, but ours stays up past the next re-check (#418 delta verifier, M7)", hotspot.on)
    }

    @Test
    fun `asking while ours runs doesn't leave a stale Wi-Fi-off line`() {
        setup.start(); at(3 * min); up()
        radioLocked = true
        setup.ask()
        radioLocked = false
        at(3 * min + 15 * min + 1_000)
        val v = setup.view
        assertTrue(v is View.Off)
        assertEquals("the client parked by our own AP isn't Wi-Fi off", null, (v as View.Off).error)
    }

    @Test
    fun `Wi-Fi switched off before the start waits, and back on the saved network gets 3 minutes first`() {
        radioLocked = true
        setup.start()
        at(10 * min)
        assertEquals(0, hotspot.starts)
        radioLocked = false
        at(10 * min + 30_000)
        assertFalse("Wi-Fi back on: the saved network gets 3 minutes first", hotspot.on)
        assertTrue("and the screen no longer says Wi-Fi is off", (setup.view as View.Off).error == null)
        at(13 * min + 30_000)
        assertTrue(hotspot.on)
    }

    @Test
    fun `an event from a start that was stopped since changes nothing`() {
        setup.start(); at(3 * min)
        val stale = hotspot.listener!!
        setup.cancel()
        assertTrue(setup.ask())
        up()
        stale(Hotspot.Event.Failed(HotspotFailure.FAILED))
        stale(Hotspot.Event.Stopped)
        assertTrue(hotspot.on)
        assertEquals(View.On(net, 15 * min, asked = true), setup.view)
    }

    @Test
    fun `the service ending stops it, and nothing starts it after`() {
        setup.start(); at(3 * min); up()
        setup.close()
        assertFalse(hotspot.on)
        assertFalse(setup.ask())
        at(120 * min)
        assertEquals(1, hotspot.starts)
    }

    @Test
    fun `a phone reaching the panel over it turns the screen to the setup page, once per run`() {
        assertTrue(setup.ask())
        setup.phoneJoined(setup.runId)
        assertFalse("not before it is up", setup.phoneSeen)
        up()
        assertEquals(View.On(net, 15 * min, asked = true), setup.view)
        val before = changes
        setup.phoneJoined(setup.runId)
        assertEquals(View.On(net, 15 * min, asked = true, phone = true), setup.view)
        assertEquals("the screen hears of it", before + 1, changes)
        setup.phoneJoined(setup.runId)
        assertEquals("once per run", before + 1, changes)
        assertEquals(1, logged.count { "phone" in it })
    }

    @Test
    fun `the next run starts without the phone`() {
        assertTrue(setup.ask())
        up()
        setup.phoneJoined(setup.runId)
        setup.cancel()
        setup.phoneJoined(setup.runId)
        assertFalse("off: nobody to reach", (setup.view as? View.On)?.phone == true)
        assertTrue(setup.ask())
        up()
        assertEquals(View.On(net, 15 * min, asked = true), setup.view)
    }

    @Test
    fun `its log names neither the hotspot nor its key`() {
        assertTrue(setup.ask())
        up()
        setup.phoneJoined(setup.runId)
        setup.cancel()
        assertTrue("it logged its run", logged.size >= 3)
        assertTrue(logged.toString(), logged.none { net.ssid in it || net.passphrase in it })
    }

    @Test
    fun `a phone seen in one run doesn't count for the next, even when the post comes late`() {
        assertTrue(setup.ask())
        up()
        val posted = mutableListOf<() -> Unit>()
        // A request from the hotspot's network is checked in run A; the main thread gets to it only later.
        PhoneJoin({ setup }, { true }, { posted += it }).request("192.168.43.206")
        setup.cancel()
        assertTrue(setup.ask())
        up()
        posted.forEach { it() }
        assertFalse("run B has seen no phone", setup.phoneSeen)
        assertEquals(View.On(net, 15 * min, asked = true), setup.view)
        PhoneJoin({ setup }, { true }, { it() }).request("192.168.43.206")
        assertTrue("run B's own phone counts", setup.phoneSeen)
    }
}
