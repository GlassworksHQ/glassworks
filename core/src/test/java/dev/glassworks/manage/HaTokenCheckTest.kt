/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.manage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** #466: the per-panel check of a token from another panel's page, apart from the server. */
class HaTokenCheckTest {
    @Test fun anOlderLookupThatEndsLastDoesntReplaceANewerRefusal() {
        val firstAsked = CountDownLatch(1)
        val firstMayAnswer = CountDownLatch(1)
        val calls = AtomicInteger()
        val gateway = object : HaGateway {
            override fun exchangeCode(haBase: String, clientId: String, code: String, codeVerifier: String) = HaGateway.Tokens.Failed("unused")
            override fun revoke(haBase: String, clientId: String, refreshToken: String) = true
            override fun currentUser(haBase: String, accessToken: String): HaUser? {
                if (calls.incrementAndGet() == 1) {
                    // Asked first, while the token was still good; answers last.
                    firstAsked.countDown()
                    firstMayAnswer.await(5, TimeUnit.SECONDS)
                    return HaUser("Alex", isAdmin = true)
                }
                return null // revoked in Home Assistant since
            }
        }
        val check = HaTokenCheck(gateway, { "http://192.0.2.10:8123" }, { true }, monoMs = { 0L })
        var first: HaTokenCheck.Result? = null
        val t = Thread { first = check.check("TOKEN-0123456789") }.apply { start() }
        assertTrue(firstAsked.await(5, TimeUnit.SECONDS))
        assertEquals(HaTokenCheck.Result.Refused, check.check("TOKEN-0123456789"))
        firstMayAnswer.countDown()
        t.join(5_000)
        assertTrue("the older question still answers its own request", first is HaTokenCheck.Result.Ok)
        assertEquals("the kept answer is the newer refusal", HaTokenCheck.Result.Refused, check.check("TOKEN-0123456789"))
        assertEquals("no third question: the refusal is kept", 2, calls.get())
    }
}
