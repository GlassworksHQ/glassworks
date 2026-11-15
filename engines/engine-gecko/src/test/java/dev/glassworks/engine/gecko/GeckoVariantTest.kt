/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.gecko

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The current engine has no page guard (#198): the lockdown is the legacy engine's only, and the S1's pages load as before. */
class GeckoVariantTest {
    @Test fun `the current engine guards nothing`() {
        assertNull(GeckoVariant.guard("test"))
        assertEquals("", GeckoVariant.LABEL)
    }
}
