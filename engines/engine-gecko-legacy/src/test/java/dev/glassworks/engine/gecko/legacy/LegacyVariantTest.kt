/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.gecko.legacy

import dev.glassworks.engine.gecko.GeckoEngine
import dev.glassworks.engine.gecko.GeckoVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The legacy engine compiles engine-gecko's GeckoEngine.kt with this module's GeckoVariant: the lockdown (#198). */
class LegacyVariantTest {
    @Test fun `the legacy engine is locked down`() {
        assertTrue(GeckoVariant.guard("test") is Lockdown)
        assertTrue(GeckoVariant.LABEL.contains("Home Assistant only"))
    }

    /** #29: the real legacy engine says it is frozen and held to Home Assistant, so the core lists Web engine. */
    @Test fun `the legacy engine says it is locked down`() {
        val engine = GeckoEngine(settings = { error("not opened") }, extension = GeckoEngine.Extension("", "", ""), logTag = "test", generatedBy = "test")
        assertTrue(engine.lockedDown)
    }

    /** The Mirror has about 930 MB of RAM (the root's probes, 2026-11-15): one content process, none spare. */
    @Test fun `one content process, none started ahead`() {
        val prefs = Lockdown("test").prefs
        assertEquals(1, prefs["dom.ipc.processCount"])
        assertEquals(false, prefs["dom.ipc.processPrelaunch.enabled"])
        assertEquals(false, prefs["fission.autostart"])
    }
}
