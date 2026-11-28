package dev.glassworks.auth

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
import dev.glassworks.KioskPrefs
import dev.glassworks.discovery.HaDiscovery
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * #467 on the production path: the real [HaAuth] and its HTTP client, against two Home Assistants on loopback that
 * record every request. The sign-in is for one, the dashboard (discovery's address) is the other: Home Assistant
 * moved, or a device on the LAN claims to be it. [HaAuth.accessToken] and [HaAuth.signOut] must send nothing to
 * either, and no cached token goes out. Each check has a control at the sign-in's own address.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HaAuthMovedTest {
    private val app = RuntimeEnvironment.getApplication()
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
        prefs.setHaUrl(dashboardHa.base(), HaDiscovery.SOURCE_MDNS)
    }

    @After fun stop() {
        runCatching { prefs.auth = null }
        dev.glassworks.dashboard.Dashboards.clear()
        signedInHa.stop(0)
        dashboardHa.stop(0)
        FakeAndroidKeyStore.uninstall()
    }

    private fun signIn(at: String) {
        prefs.auth = StoredAuth(at, "http://192.0.2.10:8099/", "made-up-refresh-token")
    }

    /** A valid access token in HaAuth's cache, as after a sign-in or a refresh. */
    private fun cached(token: String) {
        val cache = HaAuth::class.java.getDeclaredField("cache").apply { isAccessible = true }.get(HaAuth)
        cache.javaClass.getMethod("put", String::class.java, Long::class.javaPrimitiveType).invoke(cache, token, 1800L)
    }

    private fun accessToken(force: Boolean = true): HaAuth.Access {
        var out: HaAuth.Access? = null
        val done = CountDownLatch(1)
        HaAuth.accessToken(force) { out = it; done.countDown() }
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

    /**
     * After a move [HaAuth.signedIn] is false and [HaAuth.stored] null, and the settings menu's Dashboard sign-in row
     * (DashboardAccount, wired to [HaAuth.stored] as GlassworksRuntime does) shows nothing. The setup page, Manage's
     * tile and the *Signed in* sensor read [HaAuth.signedIn] directly; this test doesn't draw them.
     */
    @Test fun aMovedSignInReadsAsNotSignedInEverywhere() {
        val row = DashboardAccount({ HaAuth.stored }, { _, done -> done("Alex") })
        signIn(signedInHa.base())
        assertTrue(HaAuth.movedAway)
        assertFalse(HaAuth.signedIn)
        assertEquals(null, HaAuth.stored)
        assertEquals(null, row.shown())

        signIn(dashboardHa.base())
        assertFalse(HaAuth.movedAway)
        assertTrue(HaAuth.signedIn)
        assertEquals(dashboardHa.base(), HaAuth.stored?.haBase)
        assertEquals(DashboardAccount.SIGNED_IN, row.shown()) // then it asks Home Assistant who
        assertEquals("Signed in as Alex", row.shown())
    }

    @Test fun aMovedHttpHomeAssistantGetsNoRefresh() {
        signIn(signedInHa.base())
        assertEquals(HaAuth.Access.Failed("Home Assistant moved", signedOut = false), accessToken())
        assertEquals(emptyList<String>(), requests.toList())
        assertEquals(signedInHa.base(), prefs.auth?.haBase)
    }

    @Test fun aMovedHomeAssistantGetsNoCachedTokenEither() {
        signIn(signedInHa.base())
        cached("made-up-cached-access")
        assertEquals(HaAuth.Access.Failed("Home Assistant moved", signedOut = false), accessToken(force = false))
        assertEquals(emptyList<String>(), requests.toList())
    }

    @Test fun atItsOwnAddressTheCachedTokenIsUsed() {
        signIn(dashboardHa.base())
        cached("made-up-cached-access")
        assertEquals("made-up-cached-access", (accessToken(force = false) as HaAuth.Access.Ok).accessToken)
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

    @Test fun signingInFetchesDashboardsImmediatelyAndSignOutClearsThem() {
        val prevClient = HaAuth.dashboardClient
        val fetched = mutableListOf<String>()
        try {
            dev.glassworks.dashboard.Dashboards.clear()
            HaAuth.dashboardClient = { base, _ ->
                fetched += base
                listOf(dev.glassworks.dashboard.Dashboard("tablet", "Tablet"))
            }
            val auth = StoredAuth(dashboardHa.base(), "http://192.0.2.10:8099/", "made-up-refresh-token")
            val onSignedIn = HaAuth::class.java.getDeclaredMethod("onSignedIn", StoredAuth::class.java, TokenResult.Ok::class.java)
            onSignedIn.isAccessible = true
            onSignedIn.invoke(HaAuth, auth, TokenResult.Ok("made-up-access", 1800L, "made-up-refresh-token"))

            assertEquals(listOf(dashboardHa.base()), fetched)
            assertEquals(listOf(dev.glassworks.dashboard.Dashboard("tablet", "Tablet")), dev.glassworks.dashboard.Dashboards.latest)
            assertEquals(listOf(dev.glassworks.dashboard.Dashboard("tablet", "Tablet")), dev.glassworks.dashboard.DashboardOptions.parse(prefs.dashboardsJson))

            assertTrue(signOut())
            assertEquals(null, dev.glassworks.dashboard.Dashboards.latest)
        } finally {
            HaAuth.dashboardClient = prevClient
            dev.glassworks.dashboard.Dashboards.clear()
        }
    }

    @Test fun dashboardFetchFailureIsNonFatalDuringSignIn() {
        val prevClient = HaAuth.dashboardClient
        try {
            dev.glassworks.dashboard.Dashboards.clear()
            HaAuth.dashboardClient = { _, _ -> throw RuntimeException("network down") }
            val auth = StoredAuth(dashboardHa.base(), "http://192.0.2.10:8099/", "made-up-refresh-token")
            val onSignedIn = HaAuth::class.java.getDeclaredMethod("onSignedIn", StoredAuth::class.java, TokenResult.Ok::class.java)
            onSignedIn.isAccessible = true
            onSignedIn.invoke(HaAuth, auth, TokenResult.Ok("made-up-access", 1800L, "made-up-refresh-token"))

            assertTrue("sign-in still completes", HaAuth.signedIn)
            assertEquals(null, dev.glassworks.dashboard.Dashboards.latest)
        } finally {
            HaAuth.dashboardClient = prevClient
            dev.glassworks.dashboard.Dashboards.clear()
        }
    }
}
