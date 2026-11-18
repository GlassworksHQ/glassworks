/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A revoke counts only when Home Assistant took it (CodeRabbit on #534). */
class HaTokenClientTest {
    private class Answers(vararg val statuses: Int) : HttpPoster {
        val urls = ArrayList<String>()
        override fun postForm(url: String, body: String): Pair<Int, String> {
            urls += url
            return statuses[urls.size - 1] to ""
        }
        override fun get(url: String, maxBytes: Int): Pair<Int, String> = error("not used")
    }

    private val auth = StoredAuth("http://homeassistant.local:8123/", "http://192.0.2.10:8099/", "REFRESH")

    @Test fun onlyA2xxIsARevoke() {
        val http = Answers(503, 502, 400, -1, 200)
        val c = HaTokenClient(http)
        assertFalse("a proxy's 503 in front of HA", c.revoke(auth))
        assertFalse("a proxy's 502", c.revoke(auth))
        assertFalse("a refusal", c.revoke(auth))
        assertFalse("no answer", c.revoke(auth))
        assertTrue("HA's 200", c.revoke(auth))
        assertEquals("http://homeassistant.local:8123/auth/revoke", http.urls.last())
    }
}
