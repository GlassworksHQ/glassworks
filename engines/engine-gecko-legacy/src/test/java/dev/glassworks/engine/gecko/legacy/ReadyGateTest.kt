/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.gecko.legacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app's first load waits for the lockdown extension (#198): on an API 25 emulator (2026-11-15) a page that
 * loaded before the extension's webRequest listener fetched a cross-origin image.
 */
class ReadyGateTest {
    @Test fun `loads wait until the lockdown is in force, then run in order`() {
        val gate = ReadyGate()
        val ran = mutableListOf<String>()
        gate.whenOpen { ran += "first" }
        gate.whenOpen { ran += "second" }
        assertEquals(emptyList<String>(), ran)
        assertTrue(gate.open())
        assertEquals(listOf("first", "second"), ran)
        gate.whenOpen { ran += "later" }
        assertEquals(listOf("first", "second", "later"), ran)
        assertFalse("opens once", gate.open())
        assertEquals(3, ran.size)
    }

    @Test fun `a failure reaches every waiting load, and later ones, until it opens`() {
        val gate = ReadyGate()
        val heard = mutableListOf<String>()
        var ran = 0
        gate.whenOpen({ heard += "first: $it" }) { ran++ }
        assertTrue(gate.fail("lockdown off"))
        gate.whenOpen({ heard += "second: $it" }) { ran++ }
        assertEquals(listOf("first: lockdown off", "second: lockdown off"), heard)
        assertEquals(0, ran)
        assertTrue("it can still open", gate.open())
        assertEquals(2, ran)
        assertFalse("an open gate doesn't fail", gate.fail("late"))
    }

    @Test fun `it never opens by itself`() {
        val gate = ReadyGate()
        var ran = false
        gate.whenOpen { ran = true }
        assertFalse(ran)
    }
}
