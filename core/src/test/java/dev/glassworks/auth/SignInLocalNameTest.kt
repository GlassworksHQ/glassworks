/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.auth

import dev.glassworks.platform.PlatformApi
import dev.glassworks.web.LocalName
import java.net.URI
import java.util.Collections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The first sign-in on an Android that can't resolve `.local` names (before 12, #449) failed with "Can't reach Home
 * Assistant" (a lululemon Mirror on Android 6): Kiln set Home Assistant's address to `http://homeassistant.local:8123`
 * and opened the sign-in before discovery had found it, so the sign-in kept the name, and the code exchange went to a
 * name the panel can't resolve. Discovery had the address a second later; the person's retry signed in through it.
 * The exchange now reaches Home Assistant the way the dashboard does at that moment ([LocalName.route]).
 */
class SignInLocalNameTest {
    private val name = "http://homeassistant.local:8123"
    private val address = "http://192.0.2.10:8123"
    private val panelHost = "192.0.2.20:8099"

    /** What discovery has found (KioskPrefs.haUrl): null until it finds Home Assistant. */
    @Volatile private var found: String? = null

    /** A network on which [address] answers and `.local` names don't resolve, as on Android before 12. */
    private inner class Lan(private val reachable: Set<String> = setOf("192.0.2.10")) : HttpPoster {
        val posts: MutableList<String> = Collections.synchronizedList(ArrayList())
        override fun postForm(url: String, body: String): Pair<Int, String> {
            posts += url
            if (URI(url).host !in reachable) return -1 to "Unable to resolve host"
            val form = OAuth.parseQuery(body)
            return when {
                url.endsWith("/auth/revoke") -> 200 to ""
                form["grant_type"] == "authorization_code" ->
                    200 to """{"access_token":"AT","token_type":"Bearer","refresh_token":"RT","expires_in":1800}"""
                form["grant_type"] == "refresh_token" -> 200 to """{"access_token":"AT2","token_type":"Bearer","expires_in":1800}"""
                else -> 400 to """{"error":"unsupported_grant_type"}"""
            }
        }
        override fun get(url: String, maxBytes: Int): Pair<Int, String> = -1 to ""
    }

    private var signedIn: StoredAuth? = null

    /** The panel's server as HaAuth builds it: [setting] is the dashboard's Home Assistant, routed as KioskPrefs.route. */
    private fun server(lan: Lan, api: Int, setting: String = name, waitMs: Long = 0, thread: ThreadJoin? = null) = SignInServer(
        0, { setting }, HaTokenClient(lan), { auth, _ -> signedIn = auth }, thread = thread,
        reach = { LocalName.route(it, found, PlatformApi(api).resolvesLocalNames) }, addressWaitMs = waitMs,
    )

    private fun SignInServer.get(path: String) =
        handle(SignInServer.Request("GET", URI(path).rawPath, OAuth.parseQuery(URI(path).rawQuery), mapOf("host" to panelHost)))

    /** Starts a sign-in (`then=kiln`, as Kiln's) and returns its state. */
    private fun SignInServer.begin(path: String = "${SignInServer.CONTINUE}?then=${SignInServer.THEN_KILN}"): String {
        val r = get(path)
        assertEquals(302, r.status)
        return OAuth.parseQuery(URI(r.headers.getValue("Location")).rawQuery).getValue("state")
    }

    private fun SignInServer.Response.text() = body.toString(Charsets.UTF_8)

    @Test fun aSignInStartedBeforeDiscoveryTradesItsCodeAtTheAddressFoundSince() {
        val lan = Lan()
        val s = server(lan, api = 23)
        val state = s.begin() // no address yet: the sign-in is for the name
        found = address // discovery finds Home Assistant while the person signs in

        val r = s.get("/callback?code=c0de&state=$state")
        assertEquals(r.text(), 200, r.status)
        assertEquals(listOf("$address/auth/token"), lan.posts.toList())
        val auth = signedIn!!
        assertEquals("http://$panelHost/", auth.clientId) // still the panel's own, as at the authorize request

        // Stored for the base the dashboard loads from now (KioskPrefs.haBase: the origin of its routed URL), so the
        // sign-in counts as the dashboard's, and its refreshes go to the address too.
        val dashboardBase = OAuth.origin(LocalName.route("$name/", found, false).url)
        assertEquals(address, dashboardBase)
        assertTrue(auth.isFor(dashboardBase))
        assertTrue(HaTokenClient(lan).refresh(auth) is TokenResult.Ok)
        assertEquals("$address/auth/token", lan.posts.last())
    }

    @Test fun theExchangeWaitsAMomentForDiscovery() {
        val lan = Lan()
        val s = server(lan, api = 23, waitMs = 5_000)
        val state = s.begin()
        Thread { Thread.sleep(300); found = address }.start()

        val r = s.get("/callback?code=c0de&state=$state")
        assertEquals(r.text(), 200, r.status)
        assertEquals(listOf("$address/auth/token"), lan.posts.toList())
        assertEquals(address, signedIn!!.haBase)
    }

    @Test fun withNoAddressAfterTheWaitThePageSaysHomeAssistantIsNotFoundYet() {
        val lan = Lan()
        val s = server(lan, api = 23, waitMs = 300)
        val state = s.begin()

        val r = s.get("/callback?code=c0de&state=$state")
        assertEquals(502, r.status)
        // It still tried the name, in case this Android resolves it after all.
        assertEquals(listOf("$name/auth/token"), lan.posts.toList())
        assertTrue(r.text(), r.text().contains("hasn't found homeassistant.local on the network yet"))
        assertTrue(r.text(), r.text().contains("sign in again from Kiln"))
        assertNull(signedIn)
    }

    @Test fun anAndroidThatResolvesLocalNamesTradesAtTheName() {
        val lan = Lan(reachable = setOf("homeassistant.local"))
        val s = server(lan, api = 31)
        val state = s.begin()
        found = address

        val r = s.get("/callback?code=c0de&state=$state")
        assertEquals(r.text(), 200, r.status)
        assertEquals(listOf("$name/auth/token"), lan.posts.toList())
        assertEquals(name, signedIn!!.haBase)
    }

    @Test fun httpsOnALocalNameStaysOnTheNameForItsCertificate() {
        val https = "https://homeassistant.local:8123"
        val lan = Lan(reachable = setOf("homeassistant.local"))
        val s = server(lan, api = 23, setting = https, waitMs = 5_000)
        val state = s.begin()
        found = address

        val r = s.get("/callback?code=c0de&state=$state")
        assertEquals(r.text(), 200, r.status)
        assertEquals("no address stands in, and no wait", listOf("$https/auth/token"), lan.posts.toList())
        assertEquals(https, signedIn!!.haBase)
    }

    @Test fun aSignInStartedAtTheAddressStaysThere() {
        val lan = Lan()
        found = address
        val s = server(lan, api = 23, setting = address)
        val state = s.begin()
        found = "http://192.0.2.11:8123" // discovery moves on: the sign-in is for the address it started at

        assertEquals(200, s.get("/callback?code=c0de&state=$state").status)
        assertEquals(listOf("$address/auth/token"), lan.posts.toList())
        assertEquals(address, signedIn!!.haBase)
    }

    @Test fun theThreadJoinTradesAtTheAddressFoundSinceToo() {
        val lan = Lan()
        val sockets = Collections.synchronizedList(ArrayList<String>())
        val join = ThreadJoin(HaThreadClient { url -> sockets += url; HaThreadClientTest.FakeHa(token = "AT") }, HaTokenClient(lan),
            { name }, { done -> done(null) }, { it.run() })
        join.deliver = { null }
        val s = server(lan, api = 23, thread = join)
        join.arm()
        val state = s.begin(SignInServer.THREAD_CONTINUE)
        found = address

        val r = s.get("${ThreadJoin.CALLBACK}?code=adm1n&state=$state")
        assertEquals(r.text(), 200, r.status)
        assertEquals(listOf("$address/auth/token", "$address/auth/revoke"), lan.posts.toList())
        assertTrue(sockets.toString(), sockets.isNotEmpty() && sockets.all { URI(it).host == "192.0.2.10" })
        assertFalse(lan.posts.any { it.contains(".local") })
    }
}
