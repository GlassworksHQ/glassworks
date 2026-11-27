/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.setup

import dev.glassworks.setup.SetupHotspotPolicy.Decision
import dev.glassworks.setup.SetupHotspotPolicy.Stop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #38's rule, on a fake clock: the times are the decided ones (3 min, 15 min). */
class SetupHotspotPolicyTest {
    private val min = 60_000L
    private val p = SetupHotspotPolicy(start = 0)

    @Test
    fun `rule 1 - offline 3 minutes after the start, it comes on, and not a moment sooner`() {
        assertEquals(Decision(false, 3 * min), p.update(connected = false, now = 0))
        assertEquals(Decision(false, 1), p.update(false, 3 * min - 1))
        assertEquals(Decision(true, 15 * min), p.update(false, 3 * min))
    }

    @Test
    fun `rule 2 - never while connected, also when asked`() {
        for (t in listOf(0L, 3 * min, 10 * min)) assertFalse(p.update(connected = true, now = t).on)
        assertFalse("ask refused while connected", p.ask(connected = true, now = 10 * min))
        assertFalse(p.update(true, 10 * min).on)
    }

    @Test
    fun `rule 2 - it goes off the moment the panel connects`() {
        p.update(false, 3 * min)
        assertTrue(p.update(false, 5 * min).on)
        assertEquals(Decision(false, null), p.update(connected = true, now = 5 * min + 1))
        assertEquals(Stop.CONNECTED, p.lastStop)
    }

    @Test
    fun `a panel that has been online since starting doesn't take itself off a router that reboots`() {
        p.update(connected = true, now = min)
        assertEquals(Decision(false, null), p.update(connected = false, now = 2 * min))
        assertFalse(p.update(false, 60 * min).on)
    }

    @Test
    fun `rule 3 - off after 15 minutes, then the saved network gets 3 minutes, then rule 1 again`() {
        p.update(false, 3 * min)
        assertTrue(p.update(false, 18 * min - 1).on)
        assertEquals("off at 15 minutes, back in 3 if still offline", Decision(false, 3 * min), p.update(false, 18 * min))
        assertEquals(Stop.TIMED_OUT, p.lastStop)
        assertFalse(p.update(false, 21 * min - 1).on)
        assertTrue(p.update(false, 21 * min).on)
    }

    @Test
    fun `rule 3 - Wi-Fi configured turns it off, and the new network gets 3 minutes`() {
        p.update(false, 3 * min)
        p.configured(now = 4 * min)
        assertEquals(Stop.CONFIGURED, p.lastStop)
        assertEquals(Decision(false, 3 * min), p.update(false, 4 * min))
        assertFalse("joined", p.update(true, 5 * min).on)
        assertEquals("still connected 3 minutes on: the new network works", Decision(false, null), p.update(true, 8 * min))
        assertFalse(p.update(false, 30 * min).on)
    }

    @Test
    fun `Wi-Fi details sent while still on the old network - if the new one fails, the hotspot comes back`() {
        p.update(connected = true, now = min)
        p.configured(now = 10 * min)
        assertEquals("rule 2 still holds", Decision(false, 3 * min), p.update(connected = true, now = 10 * min))
        p.update(connected = true, now = 10 * min + 5_000)
        assertFalse("left the old network, the new one failed", p.update(false, 10 * min + 6_000).on)
        assertFalse(p.update(false, 13 * min + 5_999).on)
        assertTrue("3 minutes after the drop", p.update(false, 13 * min + 6_000).on)
    }

    @Test
    fun `the 3 minutes count from when the old network drops, not from the last update that saw it`() {
        p.update(connected = true, now = 0)
        p.configured(now = 10 * min)
        p.update(connected = true, now = 10 * min + 5_000)
        assertEquals(Decision(false, 3 * min), p.update(connected = false, now = 12 * min + 59_000))
        assertFalse(p.update(false, 15 * min + 58_999).on)
        assertTrue(p.update(false, 15 * min + 59_000).on)
    }

    @Test
    fun `a connection that outlasts the hold disarms at the hold's end, so a later drop doesn't re-arm`() {
        p.configured(now = 0)
        p.update(connected = true, now = 0)
        p.update(connected = false, now = min)
        assertEquals("wakes when the hold ends", Decision(false, 90_000), p.update(connected = true, now = 90_000))
        assertEquals(Decision(false, null), p.update(connected = true, now = 3 * min))
        assertFalse(p.update(connected = false, now = 4 * min).on)
        assertFalse(p.update(connected = false, now = 30 * min).on)
    }

    @Test
    fun `a new network that fails brings the hotspot back, the only way back without touch`() {
        p.update(false, 3 * min)
        p.configured(now = 4 * min)
        assertTrue(p.update(false, 7 * min).on)
    }

    @Test
    fun `Stop turns it off and it stays off until asked`() {
        p.update(false, 3 * min)
        p.cancel()
        assertEquals(Stop.CANCELLED, p.lastStop)
        assertEquals(Decision(false, null), p.update(false, 4 * min))
        assertFalse(p.update(false, 60 * min).on)
        assertTrue(p.ask(connected = false, now = 61 * min))
        assertEquals(Decision(true, 15 * min), p.update(false, 61 * min))
    }

    @Test
    fun `asked for on the setup screen it starts at once, and lasts 15 minutes`() {
        assertTrue(p.ask(connected = false, now = 10_000))
        assertEquals(Decision(true, 15 * min), p.update(false, 10_000))
        assertEquals(5 * min, p.leftMs(10_000 + 10 * min))
        assertTrue((p.state as SetupHotspotPolicy.State.On).asked)
    }

    @Test
    fun `asked for while the start was armed, rule 1 counts again after it times out`() {
        p.ask(false, min)
        assertEquals(Decision(false, 3 * min), p.update(false, 16 * min))
    }

    @Test
    fun `asked for after the panel had been online, it doesn't come back by itself after 15 minutes`() {
        p.update(true, 0)
        p.update(false, min)
        p.ask(false, 2 * min)
        assertEquals(Decision(false, null), p.update(false, 17 * min))
    }

    @Test
    fun `Android closing it re-arms, as the 15 minutes do`() {
        p.update(false, 3 * min)
        p.androidStopped(now = 13 * min)
        assertEquals(Stop.ANDROID, p.lastStop)
        assertEquals(Decision(false, 3 * min), p.update(false, 13 * min))
        assertTrue(p.update(false, 16 * min).on)
    }

    @Test
    fun `Android closing one asked for after the panel had been online leaves it off`() {
        p.update(true, 0)
        p.update(false, min)
        p.ask(false, 2 * min)
        p.androidStopped(now = 5 * min)
        assertEquals(Decision(false, null), p.update(false, 5 * min))
    }

    @Test
    fun `a start that failed waits for someone to ask, so it doesn't loop`() {
        p.update(false, 3 * min)
        p.failed()
        assertEquals(Stop.FAILED, p.lastStop)
        assertEquals(Decision(false, null), p.update(false, 30 * min))
    }

    @Test
    fun `no time left while off`() {
        assertNull(p.leftMs(0))
    }

    @Test
    fun `a radio switched on as rule 1 starts sends it back to waiting, not an asked hotspot`() {
        val p = SetupHotspotPolicy(0)
        assertTrue(p.update(connected = false, now = 3 * 60_000L).on)
        p.radioSwitchedOn(3 * 60_000L)
        assertEquals(SetupHotspotPolicy.State.Waiting(3 * 60_000L), p.state)
        assertNull("not a stop: it never started", p.lastStop)

        val asked = SetupHotspotPolicy(0)
        assertTrue(asked.ask(connected = false, now = 0))
        asked.radioSwitchedOn(0)
        assertTrue(asked.state is SetupHotspotPolicy.State.On)
    }
}
