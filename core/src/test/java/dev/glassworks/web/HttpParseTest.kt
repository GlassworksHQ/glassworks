/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.web

import org.junit.Assert.assertEquals
import org.junit.Test

/** A repeated query key or cookie keeps its first value, as `putIfAbsent` did before #380 made it plain Kotlin. */
class HttpParseTest {
    @Test fun `a repeated query key keeps the first value`() {
        assertEquals(mapOf("a" to "1", "b" to "x y", "c" to ""), HttpServer.parseQuery("a=1&b=x%20y&a=2&c&b=z"))
        assertEquals(emptyMap<String, String>(), HttpServer.parseQuery(null))
    }

    @Test fun `a repeated cookie keeps the first value`() {
        assertEquals(mapOf("s" to "one", "t" to "2"), HttpServer.parseCookies("s=one; t=2; s=two; junk"))
        assertEquals(emptyMap<String, String>(), HttpServer.parseCookies(null))
    }
}
