/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

/**
 * "Sign in on this panel instead" (#633) against a Home Assistant that checks what its TokenView checks: the code was
 * issued to this client_id, it works once, and the PKCE verifier matches the challenge the authorize request carried.
 * RFC 5737 addresses.
 */
class PanelSignInTest {
    private val base = "http://192.0.2.10:8123"

    /** Home Assistant: issues a code for an authorize URL, and trades it at /auth/token. */
    private inner class FakeHa : HttpPoster {
        private val codes = HashMap<String, Pair<String, String>>() // code -> client_id, challenge
        val posts = ArrayList<Pair<String, Map<String, String>>>()
        var refreshToken: String? = "RT"

        /** The person signs in on the login page at [authorize]: where Home Assistant sends the page next. */
        fun signIn(authorize: String, code: String = "CODE"): String {
            val q = OAuth.parseQuery(URI(authorize).rawQuery)
            codes[code] = q.getValue("client_id") to q.getValue("code_challenge")
            return q.getValue("redirect_uri") + "?code=$code&state=" + OAuth.enc(q.getValue("state"))
        }

        override fun postForm(url: String, body: String): Pair<Int, String> {
            val form = OAuth.parseQuery(body)
            posts += url to form
            val (client, challenge) = codes.remove(form["code"]) ?: return 400 to """{"error":"invalid_request","error_description":"Invalid code"}"""
            if (client != form["client_id"]) return 400 to """{"error":"invalid_request","error_description":"Invalid code"}"""
            if (OAuth.codeChallenge(form.getValue("code_verifier")) != challenge) return 400 to """{"error":"invalid_grant"}"""
            val rt = refreshToken?.let { ""","refresh_token":"$it"""" } ?: ""
            return 200 to """{"access_token":"AT","token_type":"Bearer","expires_in":1800$rt}"""
        }

        override fun get(url: String, maxBytes: Int): Pair<Int, String> = error("no GET")
    }

    private val ha = FakeHa()
    private val signIn = PanelSignIn(HaTokenClient(ha))

    @Test fun theLoginPageAndTheWayBackAreOnHomeAssistantsOwnOrigin() {
        val authorize = signIn.start("$base/")
        assertTrue(authorize, authorize.startsWith("$base/auth/authorize?"))
        val q = OAuth.parseQuery(URI(authorize).rawQuery)
        assertEquals("$base/glassworks/", q["client_id"])
        assertEquals("$base/glassworks/callback", q["redirect_uri"])
        assertEquals("S256", q["code_challenge_method"])
        assertTrue("the dashboard's page loads it", FrontendUrls.isAuthorizePage(authorize, base))
    }

    @Test fun theWayBackIsTheCallbackOnTheDashboardsHomeAssistantOnly() {
        assertTrue(signIn.isCallback("$base/glassworks/callback?code=C&state=S", base))
        assertTrue(signIn.isCallback("$base/glassworks/callback?code=C&state=S", "$base/lovelace/0"))
        assertFalse("another Home Assistant", signIn.isCallback("http://192.0.2.11:8123/glassworks/callback?code=C", base))
        assertFalse("the dashboard", signIn.isCallback("$base/lovelace/0", base))
        assertFalse("the login page", signIn.isCallback(signIn.start(base), base))
        assertFalse("no Home Assistant known", signIn.isCallback("$base/glassworks/callback?code=C", null))
    }

    @Test fun theCodeBecomesThePanelsRefreshTokenForThatHomeAssistant() {
        val o = signIn.finish(ha.signIn(signIn.start(base)))
        assertTrue(o.toString(), o is PanelSignIn.Outcome.SignedIn)
        val auth = (o as PanelSignIn.Outcome.SignedIn).auth
        assertEquals(StoredAuth(base, "$base/glassworks/", "RT"), auth)
        assertTrue("the dashboard's Home Assistant", auth.isFor("$base/lovelace/0"))
        assertEquals("$base/auth/token", ha.posts.single().first)
        assertEquals("authorization_code", ha.posts.single().second["grant_type"])
    }

    @Test fun aWayBackWorksOnce() {
        val back = ha.signIn(signIn.start(base))
        assertTrue(signIn.finish(back) is PanelSignIn.Outcome.SignedIn)
        assertTrue(signIn.finish(back) is PanelSignIn.Outcome.Failed)
        assertEquals("the second never reached Home Assistant", 1, ha.posts.size)
    }

    @Test fun aStateThisPanelNeverHandedOutIsRefusedBeforeTheCodeIsUsed() {
        val other = PanelSignIn(HaTokenClient(ha))
        val o = signIn.finish(ha.signIn(other.start(base)))
        assertTrue(o.toString(), o is PanelSignIn.Outcome.Failed)
        assertEquals(emptyList<Any>(), ha.posts)
    }

    @Test fun nothingIsStoredWhenHomeAssistantSaysNoOrSendsNoRefreshToken() {
        val cancelled = signIn.start(base).let { a ->
            val q = OAuth.parseQuery(URI(a).rawQuery)
            "$base/glassworks/callback?error=access_denied&state=" + OAuth.enc(q.getValue("state"))
        }
        assertEquals(PanelSignIn.Outcome.Failed("Home Assistant answered access_denied"), signIn.finish(cancelled))
        ha.refreshToken = null
        assertEquals(PanelSignIn.Outcome.Failed("Home Assistant returned no refresh token"), signIn.finish(ha.signIn(signIn.start(base))))
    }
}
