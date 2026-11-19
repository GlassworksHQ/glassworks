/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.generic

import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.sun.net.httpserver.HttpServer
import dev.glassworks.KioskPrefs
import dev.glassworks.auth.HaAuth
import dev.glassworks.auth.OAuth
import dev.glassworks.auth.StoredAuth
import dev.glassworks.engine.gecko.GeckoPinHarness
import dev.glassworks.engine.gecko.GeckoShadows
import dev.glassworks.setup.SetupOverlay
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.AllowOrDeny
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo
import java.net.InetSocketAddress
import java.net.URI
import java.util.Collections

/**
 * #633, the Pixel Tablet on 2026-11-19: on "Home Assistant moved to a new address. Sign in again.", *Sign in on this
 * panel instead* showed the dashboard through a session the page still had with Home Assistant, and nothing was
 * stored. `Signed in` stayed `no`, and the next start asked again. The real MainActivity under GenericApp, against a
 * Home Assistant on loopback that issues the code and trades it. The old sign-in's address is RFC 5737.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    application = GenericApp::class,
    instrumentedPackages = ["org.mozilla.geckoview"],
    shadows = [
        GeckoShadows.ShadowRuntime::class, GeckoShadows.ShadowExtensionController::class,
        GeckoShadows.ShadowSessionController::class, GeckoShadows.ShadowSession::class, GeckoShadows.ShadowView::class,
    ],
)
class PanelSignInRestartTest {
    private lateinit var ha: HttpServer
    private lateinit var base: String
    private val dashboard get() = "$base/lovelace/0"
    private val tokenRequests = Collections.synchronizedList(ArrayList<Map<String, String>>())

    /** The code Home Assistant issued, with the client_id and PKCE challenge of the login page it came from. */
    @Volatile private var issued: Triple<String, String, String>? = null

    @Before fun homeAssistant() {
        ha = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        ha.createContext("/auth/token") { ex ->
            val form = OAuth.parseQuery(ex.requestBody.readBytes().toString(Charsets.UTF_8))
            tokenRequests += form
            val code = issued
            val (status, body) = when (form["grant_type"]) {
                "authorization_code" -> if (code != null && form["code"] == code.first && form["client_id"] == code.second &&
                    OAuth.codeChallenge(form["code_verifier"].orEmpty()) == code.third
                ) {
                    issued = null
                    200 to """{"access_token":"AT1","token_type":"Bearer","refresh_token":"RT","expires_in":1800}"""
                } else 400 to """{"error":"invalid_request","error_description":"Invalid code"}"""
                "refresh_token" -> if (form["refresh_token"] == "RT" && form["client_id"] == "$base/glassworks/") {
                    200 to """{"access_token":"AT2","token_type":"Bearer","expires_in":1800}"""
                } else 400 to """{"error":"invalid_grant"}"""
                else -> 400 to """{"error":"unsupported_grant_type"}"""
            }
            val b = body.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(status, b.size.toLong())
            ex.responseBody.use { it.write(b) }
        }
        ha.start()
        base = "http://127.0.0.1:${ha.address.port}"
        online()
    }

    @After fun stop() = ha.stop(0)

    private fun online() {
        val cm = shadowOf(RuntimeEnvironment.getApplication().getSystemService(ConnectivityManager::class.java))
        cm.clearAllNetworks()
        val n = ShadowNetwork.newInstance(1)
        val ni = ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, NetworkInfo.State.CONNECTED)
        cm.addNetwork(n, ni)
        cm.setActiveNetworkInfo(ni)
        cm.setNetworkCapabilities(n, ShadowNetworkCapabilities.newInstance().also { c ->
            shadowOf(c).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            shadowOf(c).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        })
    }

    private fun GeckoPinHarness.loads() = events.filter { ".load " in it }.map { it.substringAfter(".load ") }
    private fun GeckoPinHarness.setup() = rootChildren().filterIsInstance<SetupOverlay>().single()

    private fun views(v: View): Sequence<View> = sequence {
        yield(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) yieldAll(views(v.getChildAt(i)))
    }

    private fun GeckoPinHarness.tap(text: String) =
        views(setup()).filterIsInstance<TextView>().single { it.isShown && it.text.toString() == text }.performClick().also { idle() }

    /** The page shows [url], as GeckoView reports a load. */
    private fun GeckoPinHarness.at(url: String) {
        navigation.onLocationChange(session, url, mutableListOf(), false)
        advance(2_500)
    }

    /** The person signs in on the login page at [authorize]; Home Assistant sends the page back. */
    private fun signInOn(authorize: String): String {
        val q = OAuth.parseQuery(URI(authorize).rawQuery)
        issued = Triple("CODE", q.getValue("client_id"), q.getValue("code_challenge"))
        return q.getValue("redirect_uri") + "?code=CODE&state=" + OAuth.enc(q.getValue("state"))
    }

    private fun GeckoPinHarness.until(what: String, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!ok()) {
            check(System.currentTimeMillis() < end) { "timed out: $what" }
            idle()
            Thread.sleep(10)
        }
        idle()
    }

    /** HaAuth on this test's settings, as a new process reads them (it keeps the first test's otherwise). */
    private fun GeckoPinHarness.newProcessAuth() {
        HaAuth::class.java.getDeclaredField("prefs").apply { isAccessible = true }.set(HaAuth, KioskPrefs(app))
        HaAuth::class.java.getDeclaredField("cache").apply { isAccessible = true }.get(HaAuth).let { it.javaClass.getMethod("clear").invoke(it) }
    }

    private fun GeckoPinHarness.externalAuth(): JSONObject =
        send(connect(dashboard), JSONObject().put("type", "getExternalAuth").put("id", 1)).single()

    @Test fun theSignInOnThisPanelIsStoredAndOutlivesARestart() = GeckoPinHarness(prefs = { it.urlSetting = dashboard; it.haSeen = true }).use { h ->
        h.signIn("http://192.0.2.10:8123")
        h.newIntent(Intent().putExtra("reload", true))
        assertTrue("Home Assistant moved", HaAuth.movedAway)
        // The page still has a session of its own with the new address: Home Assistant's dashboard, not its login page.
        h.at(dashboard)
        assertTrue("the Sign-in step covers it", h.setup().isShowing)

        val before = h.loads().size
        h.tap("Sign in on this panel instead")
        val authorize = h.loads().drop(before).single()
        assertTrue("Home Assistant's login page, not the dashboard: $authorize", authorize.startsWith("$base/auth/authorize?"))
        h.at(authorize)
        assertFalse("nothing covers the login page", h.setup().isShowing)

        val back = signInOn(authorize)
        val loaded = h.loads().size
        assertEquals("the page never loads the way back", AllowOrDeny.DENY, h.value(h.navigation.onLoadRequest(h.session, h.loadRequest(back))))
        h.until("the dashboard, signed in") { h.loads().drop(loaded).size == 2 }
        assertTrue(HaAuth.signedIn)
        assertEquals(StoredAuth(base, "$base/glassworks/", "RT"), KioskPrefs(h.app).auth)
        assertFalse(HaAuth.movedAway)
        val next = h.loads().drop(loaded)
        assertEquals(next.toString(), listOf("about:blank", "$dashboard?external_auth=1"), next)
        h.at("$dashboard?external_auth=1")
        assertFalse(h.setup().isShowing)

        // A new process: nothing cached, the stored sign-in read again (the tablet's adb install -r).
        h.newProcessAuth()
        val restarted = h.loads().size
        h.controller.recreate()
        h.idle()
        assertTrue(HaAuth.signedIn)
        assertEquals(listOf("$dashboard?external_auth=1"), h.loads().drop(restarted).distinct())
        h.at("$dashboard?external_auth=1")
        assertFalse("no Sign-in step after the restart", h.setup().isShowing)
        val token = h.externalAuth()
        assertTrue(token.toString(), token.getBoolean("success"))
        assertEquals("a token minted from the stored refresh token", "AT2", token.getString("access_token"))
        assertFalse(h.logs("Glassworks").any { "RT" in it.split(' ') || "AT2" in it })
    }

    @Test fun aSignInHomeAssistantRefusesBringsTheSignInStepBack() = GeckoPinHarness(prefs = { it.urlSetting = dashboard; it.haSeen = true }).use { h ->
        h.newProcessAuth()
        h.at("$base/auth/authorize?client_id=$base/")
        assertTrue(h.setup().isShowing)
        val before = h.loads().size
        h.tap("Sign in on this panel instead")
        val authorize = h.loads().drop(before).single()
        h.at(authorize)

        val back = signInOn(authorize).replace("code=CODE", "code=WRONG")
        h.navigation.onLoadRequest(h.session, h.loadRequest(back))
        h.until("a new login page") { h.loads().count { it.startsWith("$base/auth/authorize?") } == 2 }
        assertFalse(HaAuth.signedIn)
        assertEquals(null, KioskPrefs(h.app).auth)
        h.at(h.loads().last())
        assertTrue("the Sign-in step again", h.setup().isShowing)
        assertTrue(h.logs("Glassworks").toString(), h.logs("Glassworks").any { it.startsWith("Sign-in on this panel failed: code exchange failed") })
    }
}
