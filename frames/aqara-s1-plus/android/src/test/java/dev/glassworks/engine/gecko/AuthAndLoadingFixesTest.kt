package dev.glassworks.engine.gecko

import dev.glassworks.KioskPrefs
import dev.glassworks.MainActivity
import dev.glassworks.auth.HaAuth
import dev.glassworks.s1.KioskApp
import java.net.InetAddress
import java.net.ServerSocket
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.AllowOrDeny
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies the Mirror bring-up fixes on the real [MainActivity] and [GeckoEngine]:
 * - `/auth/authorize` → `external_auth=1` redirect loops break instead of trapping the panel;
 * - `LoadingOverlay` hides promptly when `getExternalAuth` fails or is refused on the top-level page;
 * - `LoadingOverlay` hides when the engine reports a blocked/stuck state (`GeckoEngine.showStuck` → `PageHost.onBlocked`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    application = KioskApp::class,
    instrumentedPackages = ["org.mozilla.geckoview"],
    shadows = [
        GeckoShadows.ShadowRuntime::class, GeckoShadows.ShadowExtensionController::class,
        GeckoShadows.ShadowSessionController::class, GeckoShadows.ShadowSession::class, GeckoShadows.ShadowView::class,
        GeckoShadows.ShadowStorageController::class,
    ],
)
class AuthAndLoadingFixesTest {
    private val base = "http://192.0.2.10:8123"
    private val dash = "$base/lovelace/0"
    private val authUrl = "$base/auth/authorize?client_id=x"

    private fun harness(prefs: (KioskPrefs) -> Unit = { it.urlSetting = dash }) = GeckoPinHarness(prefs = prefs)

    /** Loopback HTTP stub returning [status] and [body] to every request until closed. */
    private fun stubHttpServer(status: Int, body: String): ServerSocket {
        val server = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
        Thread({
            while (!server.isClosed) {
                val sock = runCatching { server.accept() }.getOrNull() ?: break
                runCatching {
                    sock.use { s ->
                        s.soTimeout = 2_000
                        val input = s.getInputStream().bufferedReader()
                        var line = input.readLine()
                        while (!line.isNullOrEmpty()) line = input.readLine()
                        val bytes = body.toByteArray()
                        val resp = "HTTP/1.1 $status Forbidden\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                        s.getOutputStream().apply { write(resp.toByteArray()); write(bytes); flush() }
                    }
                }
            }
        }, "test-auth-403-stub").apply { isDaemon = true }.start()
        return server
    }

    @Test
    fun `repeated authorize redirects break the loop and hide the loading screen`() = harness().use { h ->
        h.signIn(base)
        val flagged = "$dash?external_auth=1"

        // First two bounces redirect back to the dashboard with external_auth=1.
        repeat(2) { n ->
            var r: AllowOrDeny? = null
            assertEquals("bounce ${n + 1}", listOf("s1.load $flagged"), h.eventsDuring {
                r = h.value(h.navigation.onLoadRequest(h.session, h.loadRequest(authUrl)))
            })
            assertEquals(AllowOrDeny.DENY, r)
        }

        // Third bounce stops intercepting, hides LoadingOverlay, and lets /auth/authorize load.
        assertEquals(emptyList<String>(), h.eventsDuring {
            assertNull(h.navigation.onLoadRequest(h.session, h.loadRequest(authUrl)))
        })
        assertFalse("loading overlay hidden when loop breaks", h.loading.isShowing)
        assertTrue(h.logs("Glassworks").any { it.startsWith("Stopping /auth/authorize redirect loop") })
        assertTrue(h.logs("Glassworks").any { it.startsWith("Connecting screen hidden: auth redirect loop") })

        // Loading /auth/authorize itself does not bring the connecting overlay back up.
        h.progress.onPageStart(h.session, authUrl)
        assertFalse("onPageStart for /auth/authorize keeps overlay hidden", h.loading.isShowing)

        // A ready message from /auth/authorize must NOT reset the loop guard.
        h.send(h.connect(authUrl), JSONObject("""{"type":"ready","why":"page"}"""), replies = 0)
        assertEquals(emptyList<String>(), h.eventsDuring {
            assertNull(h.navigation.onLoadRequest(h.session, h.loadRequest(authUrl)))
        })

        // A ready message from the dashboard resets the guard so future single redirects work again.
        h.send(h.connect(dash), JSONObject("""{"type":"ready","why":"frontend/loaded"}"""), replies = 0)
        var afterReady: AllowOrDeny? = null
        assertEquals(listOf("s1.load $flagged"), h.eventsDuring {
            afterReady = h.value(h.navigation.onLoadRequest(h.session, h.loadRequest(authUrl)))
        })
        assertEquals(AllowOrDeny.DENY, afterReady)
    }

    @Test
    fun `external auth failure hides loading overlay promptly and prevents authorize redirect loop`() {
        // Simulate an HTTP 403 IP ban: refresh fails, refresh token is NOT revoked (signedIn stays true).
        stubHttpServer(403, "403: Forbidden").use { server ->
            val localBase = "http://127.0.0.1:${server.localPort}"
            val localDash = "$localBase/lovelace/0"
            val localAuth = "$localBase/auth/authorize?client_id=x"
            harness(prefs = { it.urlSetting = localDash }).use { h ->
                h.signIn(localBase)
                h.progress.onPageStart(h.session, "$localDash?external_auth=1")
                val port = h.connect("$localDash?external_auth=1")
                h.send(port, JSONObject("""{"type":"launch"}"""), replies = 0)
                h.progress.onPageStop(h.session, true)
                assertTrue(h.loading.isShowing)

                val reply = h.send(port, JSONObject().put("type", "getExternalAuth").put("id", 7).put("force", true)).single()
                assertFalse(reply.getBoolean("success"))
                h.idle()
                assertTrue("panel is still marked signed in after 403", HaAuth.signedIn)
                assertFalse("loading overlay hides immediately instead of waiting 45 s", h.loading.isShowing)
                assertTrue(h.logs("Glassworks").any { it.startsWith("Connecting screen hidden: external auth failed: http_403") })

                // Immediate /auth/authorize navigation after getExternalAuth failure does not bounce at all.
                assertEquals(emptyList<String>(), h.eventsDuring {
                    assertNull(h.navigation.onLoadRequest(h.session, h.loadRequest(localAuth)))
                })
            }
        }
    }

    @Test
    fun `top-level external auth refusal hides loading overlay while a frame refusal does not`() = harness().use { h ->
        h.progress.onPageStart(h.session, dash)
        val frame = h.connect(dash, topLevel = false)
        h.send(frame, JSONObject("""{"type":"launch"}"""), replies = 0)
        assertTrue(h.loading.isShowing)

        // Frame refused while signed out: loading overlay stays up.
        val frameReply = h.send(frame, JSONObject().put("type", "getExternalAuth").put("id", 1)).single()
        assertFalse(frameReply.getBoolean("success"))
        h.idle()
        assertTrue("frame refusal leaves loading overlay alone", h.loading.isShowing)

        // Top-level page refused while signed out: loading overlay hides promptly.
        val top = h.connect(dash, topLevel = true)
        h.send(top, JSONObject("""{"type":"launch"}"""), replies = 0)
        val topReply = h.send(top, JSONObject().put("type", "getExternalAuth").put("id", 2)).single()
        assertFalse(topReply.getBoolean("success"))
        h.idle()
        assertFalse("top-level refusal hides loading overlay", h.loading.isShowing)
        assertTrue(h.logs("Glassworks").any { it.startsWith("Connecting screen hidden: external auth refused") })
    }

    @Test
    fun `gecko engine stuck message notifies PageHost and hides loading overlay`() = harness().use { h ->
        // Page started and is still covered by LoadingOverlay (e.g. lockdown refused the load or timed out).
        assertTrue(h.loading.isShowing)
        val pageField = MainActivity::class.java.getDeclaredField("page").apply { isAccessible = true }
        val page = pageField.get(h.activity)!!
        val showStuck = page.javaClass.getDeclaredMethod("showStuck", String::class.java).apply { isAccessible = true }

        val message = "Home Assistant can't load: the panel started without a dashboard address."
        showStuck.invoke(page, message)
        h.idle()

        assertFalse("loading overlay hidden so lockdown message is visible", h.loading.isShowing)
        assertTrue(h.logs("Glassworks").any { it.startsWith("Connecting screen hidden: engine blocked") })

        // The TextView with the lockdown message was added to GeckoView.
        val geckoView = h.rootChildren().first() as android.view.ViewGroup
        val texts = (0 until geckoView.childCount)
            .map { geckoView.getChildAt(it) }
            .filterIsInstance<android.widget.TextView>()
            .map { it.text.toString() }
        assertEquals(listOf(message), texts)

        // Clearing stuck removes the TextView cleanly.
        showStuck.invoke(page, null)
        h.idle()
        assertEquals(0, (0 until geckoView.childCount).map { geckoView.getChildAt(it) }.filterIsInstance<android.widget.TextView>().size)
    }
}
