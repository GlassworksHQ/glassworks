package sh.edm.s1kiosk.auth

import android.app.Application
import com.sun.net.httpserver.HttpServer
import dev.glassworks.testing.FakeAndroidKeyStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import sh.edm.s1kiosk.KioskPrefs
import sh.edm.s1kiosk.discovery.HaDiscovery
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * #467 on the production path: the real [HaAuth] and its HTTP client, against two Home Assistants on loopback that
 * record every request. The sign-in is for one, the dashboard and discovery's mDNS record (with the sign-in's uuid,
 * as a spoof on the LAN would claim) point at the other, both on http. [HaAuth.accessToken] and [HaAuth.signOut]
 * must send nothing to either. Each check has a control at the sign-in's own address, where the request does go out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HaAuthMovedTest {
    private val app = RuntimeEnvironment.getApplication()
    private val ours = "0123456789abcdef0123456789abcdef"
    private val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private lateinit var signedInHa: HttpServer
    private lateinit var dashboardHa: HttpServer
    private lateinit var prefs: KioskPrefs

    private fun ha(name: String) = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/") { x ->
            requests += "$name ${x.requestMethod} ${x.requestURI.path}"
            val body = """{"access_token":"made-up-access","expires_in":1800,"token_type":"Bearer"}""".toByteArray()
            x.sendResponseHeaders(200, body.size.toLong())
            x.responseBody.use { it.write(body) }
        }
        start()
    }

    private fun HttpServer.base() = "http://127.0.0.1:${address.port}"

    @Before fun twoHomeAssistants() {
        FakeAndroidKeyStore.install()
        signedInHa = ha("signed-in")
        dashboardHa = ha("dashboard")
        prefs = KioskPrefs(app)
        // HaAuth is process-wide and keeps the first prefs it got: bind it to this test's, as one panel's app has one set.
        HaAuth::class.java.getDeclaredField("prefs").apply { isAccessible = true }.set(HaAuth, prefs)
        prefs.setHaUrl(dashboardHa.base(), HaDiscovery.SOURCE_MDNS, ours)
    }

    @After fun stop() {
        signedInHa.stop(0)
        dashboardHa.stop(0)
        FakeAndroidKeyStore.uninstall()
    }

    private fun signIn(at: String) {
        prefs.auth = StoredAuth(at, "http://192.0.2.10:8099/", "made-up-refresh-token", ours)
    }

    private fun accessToken(): HaAuth.Access {
        var out: HaAuth.Access? = null
        val done = CountDownLatch(1)
        HaAuth.accessToken(force = true) { out = it; done.countDown() }
        assertTrue("accessToken answered", done.await(10, TimeUnit.SECONDS))
        return out!!
    }

    private fun signOut(): Boolean {
        var out = false
        val done = CountDownLatch(1)
        HaAuth.signOut("test") { out = it; done.countDown() }
        assertTrue("signOut answered", done.await(10, TimeUnit.SECONDS))
        return out
    }

    @Test fun aMovedHttpHomeAssistantGetsNoRefresh() {
        signIn(signedInHa.base())
        assertEquals(HaAuth.Access.Failed("Home Assistant moved", signedOut = false), accessToken())
        assertEquals(emptyList<String>(), requests.toList())
        assertEquals(signedInHa.base(), prefs.auth?.haBase)
    }

    @Test fun atItsOwnAddressTheRefreshGoesOut() {
        signIn(dashboardHa.base())
        assertTrue(accessToken() is HaAuth.Access.Ok)
        assertEquals(listOf("dashboard POST /auth/token"), requests.toList())
    }

    @Test fun signingOutWhileMovedSendsNoRevokeAndStillSignsOut() {
        signIn(signedInHa.base())
        assertTrue(signOut())
        assertEquals(emptyList<String>(), requests.toList())
        assertFalse(prefs.hasAuth)
    }

    @Test fun signingOutAtItsOwnAddressRevokes() {
        signIn(dashboardHa.base())
        assertTrue(signOut())
        assertEquals(listOf("dashboard POST /auth/revoke"), requests.toList())
        assertFalse(prefs.hasAuth)
    }
}
