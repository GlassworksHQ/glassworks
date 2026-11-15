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
        gate.run { ran += "first" }
        gate.run { ran += "second" }
        assertEquals(emptyList<String>(), ran)
        assertTrue(gate.open())
        assertEquals(listOf("first", "second"), ran)
        gate.run { ran += "later" }
        assertEquals(listOf("first", "second", "later"), ran)
        assertFalse("opens once", gate.open())
        assertEquals(3, ran.size)
    }

    @Test fun `it never opens by itself`() {
        val gate = ReadyGate()
        var ran = false
        gate.run { ran = true }
        assertFalse(ran)
    }
}
