/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.manage

import org.junit.Assert.assertEquals
import org.junit.Test

/** The panel's names are listed at most once per TTL, not on every request (#34 verifier). */
class PanelHostsTest {
    @Test fun theNamesAreListedOncePerTtl() {
        var now = 0L
        var listed = 0
        val hosts = PanelHosts.cached(ttlMs = 5_000, monoMs = { now }) { listed++; setOf("192.0.2.10", "panel-$listed.local") }
        repeat(10) { assertEquals(setOf("192.0.2.10", "panel-1.local"), hosts()) }
        assertEquals(1, listed)
        now = 4_999
        hosts()
        assertEquals(1, listed)
        now = 5_000
        assertEquals("a new address shows after the TTL", setOf("192.0.2.10", "panel-2.local"), hosts())
        assertEquals(2, listed)
    }
}
