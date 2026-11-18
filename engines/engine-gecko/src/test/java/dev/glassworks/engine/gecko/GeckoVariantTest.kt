/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.gecko

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** The current engine has no page guard (#198): the lockdown is the legacy engine's only, and the S1's pages load as before. */
class GeckoVariantTest {
    @Test fun `the current engine guards nothing`() {
        assertNull(GeckoVariant.guard("test"))
        assertEquals("", GeckoVariant.LABEL)
    }

    /** #29: the engine the S1 and the generic app run doesn't say it is frozen, so they get no Web engine sensor. */
    @Test fun `the current engine is not locked down`() {
        val engine = GeckoEngine(settings = { error("not opened") }, extension = GeckoEngine.Extension("", "", ""), logTag = "test", generatedBy = "test")
        assertFalse(engine.lockedDown)
    }
}
