/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import dev.glassworks.engine.vitro.ui.KeyedText
import dev.glassworks.engine.vitro.ui.NumberText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** The texts onDraw reuses: built once per value, rebuilt when it changes. */
class DrawMemoTest {
    @Test
    fun numberTextBuildsOncePerValue() {
        val memo = NumberText()
        var builds = 0
        val first = memo.of(20.5) { builds++; "${it}°" }
        assertSame(first, memo.of(20.5) { builds++; "${it}°" })
        assertEquals(1, builds)
        assertEquals("21.0°", memo.of(21.0) { builds++; "${it}°" })
        assertEquals(2, builds)
        memo.reset()
        memo.of(21.0) { builds++; "${it}°" }
        assertEquals(3, builds)
    }

    @Test
    fun keyedTextFollowsBothKeys() {
        val memo = KeyedText()
        var builds = 0
        memo.of("heating", "heat") { builds++; "Heating" }
        memo.of("heating", "heat") { builds++; "Heating" }
        assertEquals(1, builds)
        assertEquals("Idle", memo.of(null, "heat") { builds++; "Idle" })
        assertEquals(2, builds)
        // The second key alone changes: built again.
        assertEquals("Off", memo.of(null, "off") { builds++; "Off" })
        assertEquals(3, builds)
    }
}
