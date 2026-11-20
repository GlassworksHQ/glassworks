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
    private class Answers(vararg val statuses: Int, val body: String = "") : HttpPoster {
        val urls = ArrayList<String>()
        override fun postForm(url: String, body: String): Pair<Int, String> {
            urls += url
            return statuses[urls.size - 1] to this.body
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

    /** Only a sign-in HA no longer has is refused: an account turned off keeps it, revocable (CodeRabbit on #534). */
    @Test fun aRenewalHaRefusesForTheAccountKeepsTheSignIn() {
        fun renew(status: Int, body: String) = HaGatewayClient(HaTokenClient(Answers(status, body = body))) { error("no socket") }
            .refresh(auth.haBase, auth.clientId, auth.refreshToken) as dev.glassworks.manage.HaGateway.Tokens.Failed
        assertTrue("invalid_grant: gone", renew(400, "{\"error\":\"invalid_grant\"}").refused)
        assertFalse("a proxy's 503 with that body isn't HA saying so", renew(503, "{\"error\":\"invalid_grant\"}").refused)
        val denied = renew(403, "{\"error\":\"access_denied\",\"error_description\":\"User is not active\"}")
        assertFalse("access_denied: kept", denied.refused)
        assertFalse(denied.unreachable)
        assertTrue("no answer", renew(-1, "").unreachable)
    }
}
