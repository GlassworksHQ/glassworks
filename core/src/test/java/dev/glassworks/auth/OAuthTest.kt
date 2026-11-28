package dev.glassworks.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

class OAuthTest {

    @Test
    fun authorizeUrlMatchesHaExpectations() {
        val cid = OAuth.clientId("192.168.3.39:8099")
        val redirect = OAuth.redirectUri("192.168.3.39:8099")
        assertEquals("http://192.168.3.39:8099/", cid)
        assertEquals("http://192.168.3.39:8099/callback", redirect)
        val u = OAuth.authorizeUrl("http://192.168.1.100:8123/", cid, redirect, "st/+=", "chal-_")
        assertTrue(u.startsWith("http://192.168.1.100:8123/auth/authorize?response_type=code&"))
        val q = OAuth.parseQuery(URI(u).rawQuery)
        assertEquals("code", q["response_type"])
        assertEquals(cid, q["client_id"])
        assertEquals(redirect, q["redirect_uri"])
        assertEquals("st/+=", q["state"])
        assertEquals("chal-_", q["code_challenge"])
        assertEquals("S256", q["code_challenge_method"])
        // IndieAuth (indieauth.py verify_redirect_uri): same scheme + netloc, no fetch needed.
        val c = URI(cid); val r = URI(redirect)
        assertEquals(c.scheme, r.scheme)
        assertEquals(c.rawAuthority, r.rawAuthority)
    }

    @Test
    fun codeChallengeMatchesRfc7636AppendixB() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            OAuth.codeChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test
    fun codeVerifierFitsHaAndIsRandom() {
        val ha = Regex("^[A-Za-z0-9._~-]{43,128}$") // HA's _verify_code_verifier
        val a = OAuth.newCodeVerifier()
        assertTrue(a, ha.matches(a))
        assertEquals(43, a.length)
        assertNotEquals(a, OAuth.newCodeVerifier())
        assertEquals(43, OAuth.codeChallenge(a).length) // HA wants exactly 43 unpadded chars
    }

    @Test
    fun pendingSignInToStringHidesTheVerifier() {
        val p = PendingSignIn("cid", "redir", "http://ha", 0L, codeVerifier = "SECRETVERIFIER")
        assertFalse(p.toString().contains("SECRETVERIFIER"))
        assertTrue(p.toString().contains("<redacted>"))
    }

    @Test
    fun hostHeaderIsSanitized() {
        assertEquals("192.168.3.39:8099", OAuth.sanitizeHost("192.168.3.39:8099"))
        assertEquals("panel.lan:8099", OAuth.sanitizeHost("Panel.LAN:8099"))
        assertNull(OAuth.sanitizeHost(null))
        assertNull(OAuth.sanitizeHost("evil@host:1"))
        assertNull(OAuth.sanitizeHost("host/path"))
        assertNull(OAuth.sanitizeHost("host:8099#x"))
        assertNull(OAuth.sanitizeHost(""))
    }

    @Test
    fun origin() {
        assertEquals("http://192.168.1.100:8123", OAuth.origin("http://192.168.1.100:8123/s1-panel/home"))
        assertEquals("https://ha.example.com", OAuth.origin("https://HA.example.com/"))
        assertEquals("default http port is stripped", "http://192.0.2.10", OAuth.origin("http://192.0.2.10:80/"))
        assertEquals("default https port is stripped", "https://ha.example.com", OAuth.origin("https://ha.example.com:443/lovelace"))
        assertEquals("IPv6 default http port is stripped", "http://[fd00::5]", OAuth.origin("http://[fd00::5]:80/"))
        assertEquals("IPv6 default https port is stripped", "https://[fd00::5]", OAuth.origin("https://[FD00::5]:443/"))
        assertEquals("non-default port for http stays", "http://192.0.2.10:443", OAuth.origin("http://192.0.2.10:443/"))
        assertEquals("non-default port for https stays", "https://192.0.2.10:80", OAuth.origin("https://192.0.2.10:80/"))
        assertEquals("a user and password aren't part of it", "http://192.0.2.10:8123", OAuth.origin("http://user:secret@192.0.2.10:8123/"))
        assertEquals("userinfo and default port are both stripped", "http://192.0.2.10", OAuth.origin("http://user:pass@192.0.2.10:80/"))
        // The dashboard-loaded check: a page loaded with them is on the base, which has none (#449).
        assertTrue(FrontendUrls.sameOrigin("http://user:secret@192.0.2.10:8123/lovelace/0", "http://192.0.2.10:8123"))
        assertTrue(FrontendUrls.sameOrigin("http://192.0.2.10:8123/lovelace/0", "http://user:secret@192.0.2.10:8123"))
        assertTrue(FrontendUrls.sameOrigin("http://192.0.2.10/?external_auth=1", "http://192.0.2.10:80"))
        assertTrue(FrontendUrls.sameOrigin("https://ha.example.com/?external_auth=1", "https://ha.example.com:443"))
        assertFalse(FrontendUrls.sameOrigin("http://user:secret@192.0.2.11:8123/", "http://192.0.2.10:8123"))
        assertNull(OAuth.origin("ftp://x/"))
        assertNull(OAuth.origin("auto"))
    }

    @Test
    fun stateIsSingleUseAndExpires() {
        var now = 1_000L
        val s = StateStore(ttlMs = 600_000, max = 3, clock = { now })
        val na = s.create("cid", "redir", "http://ha")
        val nb = s.create("cid", "redir", "http://ha")
        val a = na.state
        val b = nb.state
        assertNotEquals(a, b)
        assertNotEquals("a fresh verifier per sign-in", na.codeChallenge, nb.codeChallenge)
        assertTrue(a.length >= 32)
        val p = s.consume(a)!!
        assertEquals("cid", p.clientId)
        assertEquals("the challenge handed out is the S256 of the stored verifier", na.codeChallenge, OAuth.codeChallenge(p.codeVerifier))
        assertNull("single use", s.consume(a))
        assertNull(s.consume("forged"))
        assertNull(s.consume(null))
        now += 600_000
        assertNull("expired after 10 min", s.consume(b))
        // Bounded: the oldest outstanding state is dropped.
        val x = s.create("c", "r", "h").state; s.create("c", "r", "h"); s.create("c", "r", "h"); s.create("c", "r", "h")
        assertNull(s.consume(x))
        assertEquals(3, s.size())
    }

    @Test
    fun savedSignInsCarryOverAndRunOutAsBefore() {
        var now = 1_000L
        val disk = object : SavedSignIns {
            var text: String? = null
            override fun read() = text
            override fun write(text: String?) { this.text = text }
        }
        fun store() = StateStore(ttlMs = 600_000, clock = { now }, saved = disk)
        val first = store()
        val a = first.create("cid", "redir", "http://ha", then = Then.SETUP)
        now += 1_000
        val b = first.create("cid", "redir", "http://ha", PendingSignIn.PURPOSE_THREAD)
        val second = store()
        assertEquals(2, second.size())
        assertNull("a thread state is still no good for a sign-in", second.consume(b.state))
        val p = second.consume(a.state)!!
        assertEquals(Then.SETUP, p.then)
        assertEquals(a.slot, p.slot)
        assertEquals(1_000L, p.createdAt)
        assertEquals("the 10 minutes count from the start, not the restart", 1, store().size())
        now += 600_000
        assertEquals(0, store().size())
        assertNull("nothing left once they ran out", disk.text)
    }

    @Test
    fun savedTextThatDoesntReadIsNoSignIn() {
        for (bad in listOf("", "not json", "{}", "[1, {}, {\"state\":\"x\"}]", "[{\"state\":\"x\",\"client_id\":\"c\",\"redirect_uri\":\"r\"," +
            "\"ha_base\":\"h\",\"created_at\":1,\"purpose\":\"signin\",\"verifier\":\"v\",\"then\":\"NOPE\",\"slot\":-1}]")) {
            val s = StateStore(clock = { 2L }, saved = object : SavedSignIns {
                override fun read() = bad
                override fun write(text: String?) {}
            })
            assertEquals(bad, 0, s.size())
            assertNull(s.consume("x"))
        }
    }

    @Test
    fun tokenResponses() {
        // components/auth/__init__.py TokenView, authorization_code grant
        val ok = TokenResult.parse(200, """{"access_token":"AT","token_type":"Bearer","refresh_token":"RT","expires_in":1800,"ha_auth_provider":"homeassistant"}""")
        ok as TokenResult.Ok
        assertEquals("AT", ok.accessToken)
        assertEquals("RT", ok.refreshToken)
        assertEquals(1800L, ok.expiresIn)
        assertFalse("toString hides tokens", ok.toString().contains("AT") || ok.toString().contains("RT"))
        // refresh_token grant: no refresh_token in the reply
        val r = TokenResult.parse(200, """{"access_token":"AT2","token_type":"Bearer","expires_in":1800}""") as TokenResult.Ok
        assertNull(r.refreshToken)

        val code = TokenResult.parse(400, """{"error":"invalid_request","error_description":"Invalid code"}""") as TokenResult.Error
        assertTrue(code.isInvalidCode)
        assertFalse(code.isRevoked)
        val revoked = TokenResult.parse(400, """{"error":"invalid_grant"}""") as TokenResult.Error
        assertTrue(revoked.isRevoked)
        val denied = TokenResult.parse(403, """{"error":"access_denied","error_description":"User is not active"}""") as TokenResult.Error
        assertTrue(denied.isRevoked)
        val net = TokenResult.parse(-1, "connect timed out") as TokenResult.Error
        assertEquals("network_error", net.code)
        assertEquals("http_502", (TokenResult.parse(502, "<html>") as TokenResult.Error).code)
        assertEquals("invalid_response", (TokenResult.parse(200, "{}") as TokenResult.Error).code)
    }

    @Test
    fun accessTokenCache() {
        var now = 0L
        val c = AccessTokenCache(marginMs = 60_000, clock = { now })
        assertNull(c.get())
        c.put("AT", 1800)
        assertEquals("AT", c.get())
        assertEquals(1800L, c.secondsLeft())
        now = 1_739_000L
        assertEquals("AT", c.get())
        now = 1_741_000L // less than 60 s left
        assertNull(c.get())
        c.put("AT2", 1800); c.clear()
        assertNull(c.get())
    }

    @Test
    fun storedAuthToStringHidesTheToken() {
        assertFalse(StoredAuth("http://ha", "cid", "SECRET").toString().contains("SECRET"))
    }

    @Test
    fun formAndQuery() {
        assertEquals("grant_type=authorization_code&client_id=http%3A%2F%2F1.2.3.4%3A8099%2F",
            OAuth.form("grant_type" to "authorization_code", "client_id" to "http://1.2.3.4:8099/"))
        assertEquals(mapOf("code" to "a b", "state" to "x"), OAuth.parseQuery("code=a+b&state=x&state=y"))
        assertEquals(emptyMap<String, String>(), OAuth.parseQuery(null))
    }

    @Test
    fun externalAuthFlag() {
        assertEquals("http://h:8123/x?external_auth=1", FrontendUrls.withFlag("http://h:8123/x"))
        assertEquals("http://h:8123/x?a=1&external_auth=1#f", FrontendUrls.withFlag("http://h:8123/x?a=1#f"))
        assertEquals("http://h:8123/x?external_auth=1", FrontendUrls.withFlag("http://h:8123/x?external_auth=1"))
        assertTrue(FrontendUrls.hasFlag("http://h/?a=2&external_auth=1"))
        assertFalse(FrontendUrls.hasFlag("http://h/?external_auth=10"))
        val base = "http://192.168.1.100:8123"
        assertTrue(FrontendUrls.needsFlag("http://192.168.1.100:8123/s1-panel/home", base))
        assertFalse(FrontendUrls.needsFlag("http://192.168.1.100:8123/s1-panel/home?external_auth=1", base))
        assertFalse(FrontendUrls.needsFlag("http://192.168.1.100:8123/auth/authorize?x", base))
        assertFalse(FrontendUrls.needsFlag("http://192.168.1.100:8123/api/camera_proxy/x", base))
        assertFalse(FrontendUrls.needsFlag("http://other:8123/", base))
        assertFalse(FrontendUrls.needsFlag("http://192.168.1.100:8123/", null))
        assertTrue(FrontendUrls.isAuthorizePage("http://192.168.1.100:8123/auth/authorize?response_type=code", base))
        assertFalse(FrontendUrls.isAuthorizePage("http://192.168.1.100:8123/lovelace", base))
    }

    @Test
    fun aSignInIsOnlyForItsOwnOrigin() {
        // #467: any other origin is Home Assistant having moved (or another device): the token goes to neither.
        val auth = StoredAuth("http://192.0.2.20:8123", "http://192.0.2.10:8099/", "made-up")
        assertTrue(auth.isFor(null))
        assertTrue(auth.isFor("http://192.0.2.20:8123/lovelace/0?kiosk"))
        assertFalse(auth.isFor("http://192.0.2.21:8123")) // DHCP moved it
        assertFalse(auth.isFor("https://192.0.2.20:8123")) // https turned on at the same host
        assertFalse(auth.isFor("http://192.0.2.20:8124"))
        assertFalse(auth.isFor("http://homeassistant.local:8123"))
        assertTrue(StoredAuth("http://192.0.2.10:80", "cid", "rt").isFor("http://192.0.2.10"))
        assertTrue(StoredAuth("https://ha.example.com:443", "cid", "rt").isFor("https://ha.example.com/lovelace/0"))
    }

    @Test
    fun setupSignInsGetAFreeSlotAndAnEvictedOnesSlotIsReused() {
        val s = StateStore(max = 3)
        val slots = List(4) { s.create("c", "r", "h", then = Then.SETUP).slot }
        assertEquals("the fourth pushes out the first and takes its slot", listOf(0, 1, 2, 0), slots)
        assertEquals("a QR sign-in has none", -1, s.create("c", "r", "h").slot)
    }
}
