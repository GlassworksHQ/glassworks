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
    private val setup = SetupHotspot(
        hotspot, connected = { connected }, clock = { now },
        schedule = { d, r -> val w = (now + d) to r; wakes += w; { wakes.remove(w) } },
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
        up()
        at(4 * min)
        assertEquals(View.On(net, 14 * min, asked = false), setup.view)
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
    fun `Android ending it counts as off, and nothing restarts it by itself`() {
        setup.start(); at(3 * min); up()
        hotspot.listener!!(Hotspot.Event.Stopped)
        at(120 * min)
        assertEquals(1, hotspot.starts)
        assertEquals(View.Off(Stop.FAILED, null), setup.view)
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
}
