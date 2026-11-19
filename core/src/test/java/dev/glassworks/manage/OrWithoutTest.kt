/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.manage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A part the panel runs without (#435): a class Android refuses must cost that part, not the app. */
class OrWithoutTest {
    private val warned = ArrayList<String>()

    @Test fun `a part that loads is returned as it is`() {
        assertEquals("page", orWithout("The Manage page", { warned += it }) { "page" })
        assertTrue(warned.isEmpty())
    }

    @Test fun `a class Android refuses to load costs only that part`() {
        assertNull(orWithout<String>("Allow a new computer", { warned += it }) { throw VerifyError("Rejecting class dev.glassworks.platform.UsbDebugging\$Requests") })
        assertTrue(warned.single(), warned.single().startsWith("Allow a new computer can't load, so the panel runs without it: java.lang.VerifyError"))
    }

    @Test fun `a part that throws while it starts too`() {
        assertNull(orWithout<String>("The Manage page", { warned += it }) { throw IllegalStateException("no port") })
        assertTrue(warned.single(), warned.single().contains("can't start"))
    }
}
