/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.auth

import java.net.URI
import java.util.Collections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Once the panel is signed in, its QR sign-in pages no longer replace that sign-in for anyone on the network: only a
 * browser signed in to Manage, or while the panel's own screen shows the sign-in code ([SignInArm]). Before, a phone on
 * the LAN (or a page that sent a browser to `/signin/continue`, login CSRF) could swap the panel's Home Assistant
 * account for its own. Not signed in (first setup, after a sign-out or a revoked token, Home Assistant moved), anyone may.
 */
class SignInReplaceTest {
    private val ha = "http://192.0.2.10:8123"
    private val panelHost = "192.0.2.20:8099"

    /** Home Assistant that takes any code. */
    private val haApi = object : HttpPoster {
        val exchanges: MutableList<String> = Collections.synchronizedList(ArrayList())
        override fun postForm(url: String, body: String): Pair<Int, String> {
            exchanges += url
            return 200 to """{"access_token":"AT","token_type":"Bearer","refresh_token":"RT2","expires_in":1800}"""
        }
        override fun get(url: String, maxBytes: Int): Pair<Int, String> = -1 to ""
    }

    /** The panel's stored sign-in: what the callback replaces. */
    private var stored: StoredAuth? = null
    private var monoMs = 1_000L
    private val arm = SignInArm(ttlMs = 60_000, monoMs = { monoMs })
    private val states = StateStore()

    private val server = SignInServer(
        0, { "$ha/" }, HaTokenClient(haApi), { auth, _ -> stored = auth; arm.disarm() }, states,
        signedIn = { stored != null }, armed = arm::armed,
    )

    private fun get(path: String, manage: Boolean = false): SignInServer.Response {
        val u = URI(path)
        return server.handle(SignInServer.Request("GET", u.rawPath, OAuth.parseQuery(u.rawQuery), mapOf("host" to panelHost), manage))
    }

    /** `/signin/continue[then]`: the state it sent Home Assistant, or null when it didn't go there. */
    private fun start(manage: Boolean = false, then: String = ""): String? {
        val r = get("${SignInServer.CONTINUE}$then", manage)
        if (r.status != 302) return null
        return OAuth.parseQuery(URI(r.headers["Location"]!!).rawQuery)["state"]
    }

    private fun callback(state: String, manage: Boolean = false) = get("/callback?code=c0de&state=${OAuth.enc(state)}", manage)

    private fun signedInAlready() { stored = StoredAuth(ha, "http://$panelHost/", "RT1") }

    private fun text(r: SignInServer.Response) = r.body.toString(Charsets.UTF_8)

    @Test fun signedInTheQrPagesRefuseAnyoneOnTheNetwork() {
        signedInAlready()
        for (p in listOf("/signin", SignInServer.CONTINUE, "${SignInServer.CONTINUE}?then=kiln", "${SignInServer.CONTINUE}?then=setup", "/")) {
            val r = get(p)
            assertEquals(p, 409, r.status)
            assertTrue(text(r), text(r).contains("Already signed in"))
            assertNull("no redirect to Home Assistant", r.headers["Location"])
        }
        assertEquals("no sign-in was started", 0, states.size())
        assertEquals("RT1", stored!!.refreshToken)
    }

    @Test fun aSignInStartedBeforeThePanelSignedInAnotherWayDoesntReplaceIt() {
        // Login CSRF: someone starts a sign-in while the panel isn't signed in, and finishes it after it is.
        val state = start()!!
        signedInAlready()
        val r = callback(state)
        assertEquals(409, r.status)
        assertTrue(haApi.exchanges.isEmpty())
        assertEquals("RT1", stored!!.refreshToken)
        // A Manage session at the callback doesn't count: HA's redirect back is cross-site, so the Strict cookie never comes.
        val again = start()
        assertNull(again)
    }

    @Test fun beforeSetupAnyoneMaySignIn() {
        assertEquals(200, get("/signin").status)
        val state = start(then = "?then=kiln")!!
        assertEquals(200, callback(state).status)
        assertEquals("RT2", stored!!.refreshToken)
    }

    @Test fun afterHomeAssistantMovedOrASignOutAnyoneMaySignInAgain() {
        // HaAuth.signedIn is false once Home Assistant moved (the stored sign-in is for the old address) or after a sign-out.
        signedInAlready()
        stored = null
        val state = start()!!
        assertEquals(200, callback(state).status)
        assertEquals("RT2", stored!!.refreshToken)
    }

    @Test fun aBrowserSignedInToManageMayReplaceIt() {
        signedInAlready()
        assertEquals(200, get("/signin", manage = true).status)
        val state = start(manage = true)!!
        // Home Assistant sends the browser back cross-site: no Manage cookie at the callback, the start counts.
        val r = callback(state, manage = false)
        assertEquals(200, r.status)
        assertEquals("RT2", stored!!.refreshToken)
    }

    @Test fun whileThePanelsScreenShowsTheCodeItsSignInMayReplaceIt() {
        signedInAlready()
        arm.arm()
        assertEquals(200, get("/signin").status)
        val state = start()!!
        assertEquals(200, callback(state).status)
        assertEquals("RT2", stored!!.refreshToken)
        assertFalse("one sign-in per code on the screen", arm.armed())
        assertNull("a second phone that scanned the same code doesn't replace it", start())
    }

    @Test fun theArmRunsOut() {
        signedInAlready()
        arm.arm()
        monoMs += 59_999
        assertTrue(arm.armed())
        monoMs += 1
        assertFalse(arm.armed())
        assertEquals(409, get("/signin").status)
    }

    @Test fun aReplacingSignInIsRememberedAcrossARestart() {
        val saved = object : SavedSignIns {
            var text: String? = null
            override fun read() = text
            override fun write(text: String?) { this.text = text }
        }
        val s = StateStore(saved = saved)
        val a = s.create("http://$panelHost/", "http://$panelHost/callback", ha, replaces = true)
        val b = s.create("http://$panelHost/", "http://$panelHost/callback", ha)
        val next = StateStore(saved = saved)
        assertTrue(next.consume(a.state)!!.replaces)
        assertFalse(next.consume(b.state)!!.replaces)
        // A copy saved before the field existed: no replacing.
        saved.text = """[{"state":"old","client_id":"c","redirect_uri":"r","ha_base":"$ha","created_at":${System.currentTimeMillis()},"purpose":"signin","verifier":"v","then":"QR","slot":-1}]"""
        assertFalse(StateStore(saved = saved).consume("old")!!.replaces)
    }
}
