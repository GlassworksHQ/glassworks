/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import dev.glassworks.engine.vitro.island.VitroIslandServer
import dev.glassworks.web.HttpRequest
import dev.glassworks.web.HttpResponse
import dev.glassworks.web.HttpServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * [VitroIslandServer] over real loopback sockets: a tiny [HttpServer] plays Home Assistant (the island server proxies
 * to it), the island's WebSocket is a minimal RFC 6455 client ([Bridge]). Tokens are made up; every address is loopback.
 */
class VitroIslandServerTest {
    private val loopback = InetAddress.getByName("127.0.0.1")

    /** What the fake Home Assistant saw: path with query, and the `Authorization` header (null when absent). */
    private class Seen(
        val target: String,
        val method: String,
        val authorization: String?,
        val body: String,
        val requestedWith: String? = null,
    )

    private val haSeen = CopyOnWriteArrayList<Seen>()
    private lateinit var ha: HttpServer
    private lateinit var haOrigin: String
    @Volatile private var token: String? = "fake-token-0001"
    @Volatile private var haBase: String? = null
    private lateinit var island: VitroIslandServer

    /** A slot that records what it hears and answers a `watch` / `callWs` / `callService` line with one JSON line. */
    private class RecordingSlot(override val html: String = "<html><body>island</body></html>") : VitroIslandServer.SlotHandler {
        val connected = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val received = CopyOnWriteArrayList<JSONObject>()
        val gotMessage = CountDownLatch(3)

        override fun onConnected(send: (JSONObject) -> Unit) {
            send(JSONObject().put("type", "init").put("states", JSONObject().put("light.lounge_lamp", "on")))
            connected.countDown()
        }

        override fun onMessage(msg: JSONObject, send: (JSONObject) -> Unit) {
            received += msg
            send(JSONObject().put("type", "ack").put("for", msg.optString("type")))
            gotMessage.countDown()
        }

        override fun onDisconnected(send: (JSONObject) -> Unit) {
            disconnected.countDown()
        }
    }

    @Before
    fun setUp() {
        ha = HttpServer(0, ::fakeHa, bindAddress = loopback, workers = 4)
        ha.start()
        haOrigin = "http://127.0.0.1:${ha.localPort}"
        haBase = haOrigin
        island = VitroIslandServer(haBase = { haBase }, accessToken = { token })
    }

    @After
    fun tearDown() {
        island.stop()
        ha.stop()
    }

    private fun fakeHa(req: HttpRequest): HttpResponse {
        haSeen += Seen(req.target, req.method, req.headers["authorization"], String(req.body, Charsets.UTF_8), req.headers["x-requested-with"])
        return when {
            req.path == "/local/custom-card.js" ->
                HttpResponse(200, mapOf("Content-Type" to "application/javascript; charset=utf-8"), "customElements.define('x-card', class {});".toByteArray())
            // The page moves to another Home Assistant while this request is on its way, then HA redirects.
            req.path == "/local/hop" -> {
                token = "other-origin-token"
                HttpResponse(302, mapOf("Location" to "/local/custom-card.js"), ByteArray(0))
            }
            req.path == "/local/cached.js" ->
                HttpResponse(200, mapOf("Content-Type" to "application/javascript", "Cache-Control" to "max-age=3600"), "1".toByteArray())
            req.path == "/api/private" ->
                HttpResponse(200, mapOf("Content-Type" to "application/json", "Cache-Control" to "private, max-age=60"), "{}".toByteArray())
            // A redirect to a path the proxy would refuse on a direct request.
            req.path == "/local/away" -> HttpResponse(302, mapOf("Location" to "/some/other/thing"), ByteArray(0))
            req.path == "/local/to-resource" -> HttpResponse(302, mapOf("Location" to "/my_integration_card/chunk.js"), ByteArray(0))
            // An integration's own frontend (a StaticPathConfig at /<domain>/), served as a module (#840).
            req.path == "/dreame_vacuum/frontend.js" ->
                HttpResponse(200, mapOf("Content-Type" to "text/javascript"), "import './cards/map.js';".toByteArray())
            req.path == "/dreame_vacuum/cards/map.js" ->
                HttpResponse(200, mapOf("Content-Type" to "text/javascript"), "export {};".toByteArray())
            req.path == "/my_integration_card/chunk.js" ->
                HttpResponse(200, mapOf("Content-Type" to "application/javascript"), "export {};".toByteArray())
            // A public path that redirects to a token-bearing one.
            req.path == "/local/to-api" -> HttpResponse(302, mapOf("Location" to "/api/private"), ByteArray(0))
            // HA accepts only the renewed token here (a token that expired under the island).
            req.path == "/api/expiring" ->
                if (req.headers["authorization"] == "Bearer renewed-token") HttpResponse(200, mapOf("Content-Type" to "application/json"), "{\"ok\":true}".toByteArray())
                else HttpResponse.text(401, "401: Unauthorized")
            req.path == "/api/camera_proxy/cam" ->
                HttpResponse(200, mapOf("Content-Type" to "image/jpeg"), byteArrayOf(1, 2, 3, 4))
            req.path == "/api/services/light/turn_on" && req.method == "POST" ->
                HttpResponse(200, mapOf("Content-Type" to "application/json"), "[]".toByteArray())
            req.path == "/api/shopping_list/item/fake-item-1" && req.method == "DELETE" ->
                HttpResponse(204, emptyMap(), ByteArray(0))
            else -> HttpResponse.text(404, "nope")
        }
    }

    private class Reply(
        val code: Int,
        val contentType: String?,
        val body: ByteArray,
        val setCookie: String? = null,
        val location: String? = null,
        val cacheControl: String? = null,
        val referrerPolicy: String? = null,
    ) {
        val text get() = String(body, Charsets.UTF_8)
    }

    /**
     * [url] on [server] under that server's capability ([VitroIslandServer.capPath]), as the island page builds its
     * addresses; a URL already under it, or on another origin, is as it is.
     */
    private fun inside(url: String, server: VitroIslandServer = island): String {
        val o = server.origin ?: return url
        if (!url.startsWith("$o/")) return url
        val path = url.removePrefix(o)
        return if (path.startsWith(server.capPath + "/")) url else o + server.capPath + path
    }

    /** [capability]: ask under the island server's capability, as the island page does ([inside]). */
    private fun get(
        url: String,
        method: String = "GET",
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
        capability: Boolean = true,
    ): Reply {
        val conn = URL(if (capability) inside(url) else url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.instanceFollowRedirects = false
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.connectTimeout = 5_000
            conn.readTimeout = 10_000
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..399) conn.inputStream else conn.errorStream
            return Reply(
                code, conn.contentType, stream?.use { it.readBytes() } ?: ByteArray(0),
                conn.getHeaderField("Set-Cookie"), conn.getHeaderField("Location"), conn.getHeaderField("Cache-Control"),
                conn.getHeaderField("Referrer-Policy"),
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun await(latch: CountDownLatch, what: String) =
        assertTrue("timed out waiting for $what", latch.await(5, TimeUnit.SECONDS))

    /**
     * An island page's bridge: the upgrade (under the island server's capability, as the page opens it, unless
     * [inside] is false) with [headers], then RFC 6455 text frames. A refused upgrade throws an [IOException] naming
     * the status.
     */
    private inner class Bridge(id: String, headers: Map<String, String> = emptyMap(), inside: Boolean = true) {
        private val sock = java.net.Socket(loopback, island.ensureStarted().substringAfterLast(':').toInt()).apply { soTimeout = 5_000 }
        private val input = java.io.BufferedInputStream(sock.getInputStream())
        private val out = sock.getOutputStream()

        init {
            val req = StringBuilder("GET ${if (inside) island.capPath else ""}/__vitro/ws/$id HTTP/1.1\r\nHost: 127.0.0.1:${island.port}\r\n")
                .append("Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n")
                .append("Sec-WebSocket-Version: 13\r\n")
            headers.forEach { (k, v) -> req.append(k).append(": ").append(v).append("\r\n") }
            out.write(req.append("\r\n").toString().toByteArray()); out.flush()
            val status = readLine()
            if (!status.startsWith("HTTP/1.1 101")) { sock.close(); throw IOException("upgrade refused: $status") }
            while (readLine().isNotEmpty()) Unit
        }

        private fun readLine(): String {
            val sb = StringBuilder()
            while (true) {
                val c = input.read()
                if (c < 0 || c == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(c.toChar())
            }
        }

        /** The next text message, or null once the server closed. */
        fun receive(): String? {
            while (true) {
                val b0 = input.read(); if (b0 < 0) return null
                val b1 = input.read(); if (b1 < 0) return null
                var len = (b1 and 0x7f).toLong()
                if (len == 126L) len = ((input.read() shl 8) or input.read()).toLong()
                else if (len == 127L) { len = 0; repeat(8) { len = (len shl 8) or input.read().toLong() } }
                val payload = ByteArray(len.toInt())
                var got = 0
                while (got < payload.size) { val n = input.read(payload, got, payload.size - got); if (n < 0) return null; got += n }
                when (b0 and 0x0f) {
                    0x1 -> return String(payload, Charsets.UTF_8)
                    0x8 -> return null
                }
            }
        }

        fun line(): JSONObject = JSONObject((receive() ?: throw AssertionError("socket closed")).trim())

        fun say(obj: JSONObject) {
            val payload = (obj.toString() + "\n").toByteArray()
            val mask = byteArrayOf(1, 2, 3, 4)
            val head = java.io.ByteArrayOutputStream().apply {
                write(0x81)
                if (payload.size < 126) write(0x80 or payload.size) else { write(0x80 or 126); write(payload.size shr 8); write(payload.size and 0xff) }
                write(mask)
            }
            out.write(head.toByteArray())
            out.write(ByteArray(payload.size) { (payload[it].toInt() xor mask[it and 3].toInt()).toByte() })
            out.flush()
        }

        fun abort() = runCatching { sock.close() }
    }

    /** [ws] was closed by the server: EOF, a close frame or a reset. A read that times out means it is still open. */
    private fun assertClosed(ws: Bridge) {
        val after = try {
            ws.receive()
        } catch (e: java.net.SocketTimeoutException) {
            throw AssertionError("the socket is still open (the read timed out)", e)
        } catch (_: java.net.SocketException) {
            null // reset by the server
        }
        assertNull("the socket is closed", after)
    }

    // ---- Lifecycle ----

    // ---- A token that expired (401) ----

    @Test
    fun aRefusedTokenIsRenewedAndTheRequestTriedOnceMore() {
        island.stop()
        val renewals = java.util.concurrent.atomic.AtomicInteger()
        island = VitroIslandServer(
            session = { haBase to token },
            renewSession = { done -> renewals.incrementAndGet(); token = "renewed-token"; done(haBase to token) },
        )
        val reply = get(island.ensureStarted() + "/api/expiring", method = "POST", body = "{\"a\":1}")
        assertEquals(200, reply.code)
        assertEquals("{\"ok\":true}", reply.text)
        assertEquals(1, renewals.get())
        val asked = haSeen.filter { it.target == "/api/expiring" }
        assertEquals(listOf("Bearer fake-token-0001", "Bearer renewed-token"), asked.map { it.authorization })
        assertEquals("the body goes again with the retry", listOf("{\"a\":1}", "{\"a\":1}"), asked.map { it.body })

        // Already renewed by another request: no second forced refresh, the current token is used.
        token = "renewed-token"
        assertEquals(200, get(island.ensureStarted() + "/api/expiring").code)
        assertEquals(1, renewals.get())
    }

    /** A GET of [path] on [server] under that server's own capability (each server has its own). */
    private fun getOn(server: VitroIslandServer, path: String): Reply =
        get(server.ensureStarted() + server.capPath + path, capability = false)

    @Test
    fun aRefusalStaysARefusalWhenNothingBetterComes() {
        island.stop()
        val renewals = java.util.concurrent.atomic.AtomicInteger()
        // Renewed, but HA refuses that one too: one retry only, and the page gets HA's 401.
        island = VitroIslandServer(session = { haBase to token }, renewSession = { done ->
            renewals.incrementAndGet()
            token = "still-bad-${renewals.get()}"
            done(haBase to token)
        })
        assertEquals(401, getOn(island, "/api/expiring").code)
        assertEquals(1, renewals.get())
        assertEquals(2, haSeen.count { it.target == "/api/expiring" })
        island.stop()

        // A renewal for another Home Assistant, or none in time: no retry with it.
        haSeen.clear()
        island = VitroIslandServer(session = { haBase to token }, renewSession = { done -> done("http://192.0.2.20:8123" to "renewed-token") })
        assertEquals(401, getOn(island, "/api/expiring").code)
        assertEquals(1, haSeen.count { it.target == "/api/expiring" })
        island.stop()
        haSeen.clear()
        island = VitroIslandServer(session = { haBase to token }, renewSession = { _ -> }, renewTimeoutMs = 200)
        assertEquals(401, getOn(island, "/api/expiring").code)
        assertEquals(1, haSeen.count { it.target == "/api/expiring" })
    }

    @Test
    fun theServerListensOnLoopbackAndKeepsItsPort() {
        assertEquals(-1, island.port)
        assertNull(island.origin)
        val origin = island.ensureStarted()
        assertTrue(origin, Regex("http://127\\.0\\.0\\.1:\\d+").matches(origin))
        assertEquals("the same origin every time: a locked-down engine is told it once", origin, island.ensureStarted())
        assertEquals(origin, island.origin)
    }

    // ---- Island page and icons ----

    @Test
    fun islandPageIsTheSlotsHtmlAndAnUnknownSlotIs404() {
        val slot = RecordingSlot("<html><body>lounge card</body></html>")
        val (id, url) = island.registerSlot(slot)
        assertEquals(island.ensureStarted() + island.capPath + "/__vitro/island/" + id, url)
        assertTrue(island.capPath, Regex("/[0-9a-f]{32}").matches(island.capPath))

        val page = get(url)
        assertEquals(200, page.code)
        assertTrue(page.contentType, page.contentType!!.startsWith("text/html"))
        assertEquals("<html><body>lounge card</body></html>", page.text)

        assertEquals(404, get(island.ensureStarted() + "/__vitro/island/s999").code)
        assertTrue("the fake Home Assistant was never asked", haSeen.isEmpty())
    }

    @Test
    fun iconsAreServedAsSvgPaths() {
        val icon = get(island.ensureStarted() + "/__vitro/icon/mdi:lightbulb")
        assertEquals(200, icon.code)
        assertTrue(icon.contentType, icon.contentType!!.startsWith("image/svg+xml"))
        assertTrue(icon.text, icon.text.contains("<svg"))
        assertTrue(icon.text, icon.text.contains("<path"))
        assertTrue("never proxied", haSeen.isEmpty())
        // Nothing secret: the page asks for them outside the capability, so the browser keeps them across app starts.
        assertEquals(200, get(island.ensureStarted() + "/__vitro/icon/mdi:lightbulb", capability = false).code)
    }

    // ---- Reverse proxy ----

    @Test
    fun customCardScriptIsProxiedWithTheBearerToken() {
        val reply = get(island.ensureStarted() + "/local/custom-card.js")
        assertEquals(200, reply.code)
        assertTrue(reply.contentType, reply.contentType!!.startsWith("application/javascript"))
        assertEquals("customElements.define('x-card', class {});", reply.text)

        val seen = haSeen.single()
        assertEquals("/local/custom-card.js", seen.target)
        assertEquals("Bearer fake-token-0001", seen.authorization)
    }

    @Test
    fun aRedirectHopKeepsTheTokenReadWithItsOrigin() {
        val reply = get(island.ensureStarted() + "/local/hop")
        assertEquals(200, reply.code)
        assertEquals(listOf("/local/hop", "/local/custom-card.js"), haSeen.map { it.target })
        // Both hops carry the token read with the origin, never the one published for the next origin meanwhile.
        assertEquals(listOf("Bearer fake-token-0001", "Bearer fake-token-0001"), haSeen.map { it.authorization })
    }

    @Test
    fun cameraProxyKeepsItsQueryAndGetsTheToken() {
        val reply = get(island.ensureStarted() + "/api/camera_proxy/cam?token=abc")
        assertEquals(200, reply.code)
        assertEquals("image/jpeg", reply.contentType)
        assertEquals(listOf<Byte>(1, 2, 3, 4), reply.body.toList())

        val seen = haSeen.single()
        assertEquals("/api/camera_proxy/cam?token=abc", seen.target)
        assertEquals("Bearer fake-token-0001", seen.authorization)
    }

    @Test
    fun aPostGoesThroughWithItsBody() {
        val reply = get(island.ensureStarted() + "/api/services/light/turn_on", "POST", """{"entity_id":"light.lounge_lamp"}""")
        assertEquals(200, reply.code)
        val seen = haSeen.single()
        assertEquals("POST", seen.method)
        assertEquals("""{"entity_id":"light.lounge_lamp"}""", seen.body)
        assertEquals("Bearer fake-token-0001", seen.authorization)
    }

    @Test
    fun aDeleteGoesThroughWithTheTokenAndA204ComesBackEmpty() {
        val reply = get(island.ensureStarted() + "/api/shopping_list/item/fake-item-1", "DELETE")
        assertEquals(204, reply.code)
        assertEquals(0, reply.body.size)
        val seen = haSeen.single()
        assertEquals("DELETE", seen.method)
        assertEquals("/api/shopping_list/item/fake-item-1", seen.target)
        assertEquals("Bearer fake-token-0001", seen.authorization)
    }

    @Test
    fun aDeleteForwardsItsBodyAndXRequestedWith() {
        val reply = get(
            island.ensureStarted() + "/api/shopping_list/item/fake-item-1",
            "DELETE",
            """{"reason":"done"}""",
            mapOf("X-Requested-With" to "XMLHttpRequest"),
        )
        assertEquals(204, reply.code)
        assertEquals(0, reply.body.size)
        val seen = haSeen.single()
        assertEquals("DELETE", seen.method)
        assertEquals("""{"reason":"done"}""", seen.body)
        assertEquals("XMLHttpRequest", seen.requestedWith)
        assertEquals("Bearer fake-token-0001", seen.authorization)
    }

    @Test
    fun theProxyRefusesACallerWithoutTheCapability() {
        // Any app on the device can reach loopback: without the capability it never gets the panel's token spent for it.
        val base = island.ensureStarted()
        val other = "/" + "0".repeat(32)
        assertEquals(403, get("$base/api/services/light/turn_on", "POST", "{}", capability = false).code)
        assertEquals(403, get("$base/api/states", capability = false).code)
        assertEquals(403, get("$base/local/custom-card.js", capability = false).code)
        // Another capability (a guess, another server's): refused as no capability.
        assertEquals(403, get("$base$other/api/states", capability = false).code)
        assertEquals(403, get("$base$other/local/custom-card.js", capability = false).code)
        // The capability as a path segment further in, or a part of it, isn't the capability.
        assertEquals(403, get("$base/api${island.capPath}/states", capability = false).code)
        assertEquals(403, get("$base${island.capPath.dropLast(1)}/api/states", capability = false).code)
        assertEquals(403, get("$base${island.capPath}x/api/states", capability = false).code)
        assertTrue("Home Assistant heard nothing", haSeen.isEmpty())
        assertEquals(200, get("$base${island.capPath}/local/custom-card.js", capability = false).code)
    }

    /**
     * #799 (Greptile): the old capability, a cookie set on 127.0.0.1, went to every server on 127.0.0.1 whatever its
     * port, where another app could take it and replay it here. A cookie of that name, whatever its value, opens nothing.
     */
    @Test
    fun theOldHostWideCookieAloneOpensNothing() {
        val base = island.ensureStarted()
        val (id, url) = island.registerSlot(RecordingSlot())
        val page = get(url, capability = false)
        assertEquals(200, page.code)
        assertNull("the island page sets no cookie: one on 127.0.0.1 would go to every port there", page.setCookie)
        for (value in listOf("guess", island.capPath.removePrefix("/"), "0".repeat(64))) {
            val cookie = mapOf("Cookie" to "vitro_cap=$value")
            assertEquals(value, 403, get("$base/api/states", headers = cookie, capability = false).code)
            assertEquals(value, 403, get("$base/local/custom-card.js", headers = cookie, capability = false).code)
            assertEquals(value, 403, get("$base/__vitro/island/$id", headers = cookie, capability = false).code)
            try {
                Bridge(id, headers = cookie + ("Origin" to "http://127.0.0.1:${island.port}"), inside = false).abort()
                throw AssertionError("a bridge outside the capability should be refused, cookie and Origin or not")
            } catch (e: IOException) {
                assertTrue(e.message, e.message.orEmpty().contains("403"))
            }
        }
        assertTrue("Home Assistant heard nothing", haSeen.isEmpty())
    }

    @Test
    fun anIslandPageIsOnlyAtItsAddressUnderTheCapability() {
        val (id, url) = island.registerSlot(RecordingSlot("<html><body>lounge card</body></html>"))
        val base = island.ensureStarted()
        assertEquals(200, get(url, capability = false).code)
        assertEquals(403, get("$base/__vitro/island/$id", capability = false).code)
        assertEquals(403, get("$base/${"0".repeat(32)}/__vitro/island/$id", capability = false).code)
        // A page's own requests name its address as their Referer: the island page's alone doesn't open the page.
        assertEquals(403, get("$base/__vitro/island/$id", headers = mapOf("Referer" to url), capability = false).code)

        // The old entry route for Home Assistant's pages is gone: those load from Home Assistant itself.
        val entry = get("$base/__vitro/enter?c=guess&to=%2Fconfig", capability = false)
        assertEquals(403, entry.code)
        assertNull(entry.setCookie)
        assertEquals(404, get("$base${island.capPath}/__vitro/enter?c=guess&to=%2Fconfig", capability = false).code)
        assertTrue("Home Assistant heard nothing", haSeen.isEmpty())
    }

    /**
     * Every answer keeps the browser from naming this server's addresses, which hold the capability, to another origin
     * (another app's port on 127.0.0.1 above all): the island page, what the proxy brings and the refusals alike.
     */
    @Test
    fun everyAnswerKeepsTheCapabilityFromOtherOrigins() {
        val (_, url) = island.registerSlot(RecordingSlot())
        val base = island.ensureStarted()
        for (reply in listOf(
            get(url, capability = false),
            get("$base/local/custom-card.js"),
            get("$base/api/camera_proxy/cam"),
            get("$base/__vitro/icon/mdi:lightbulb"),
            get("$base/local/custom-card.js", capability = false),
            get("$base/local/custom-card.js", headers = mapOf("Referer" to url), capability = false),
        )) {
            assertEquals("${reply.code}", "same-origin", reply.referrerPolicy)
        }
    }

    /**
     * What a card builds from an absolute path itself (an entity picture's `/api/…`, `import('/hacsfiles/…')`) has no
     * capability in its address, but the island page's address as its Referer: a GET goes into the capability by a
     * redirect, so the browser holds a module at an address under it (its own imports carry it on); a POST goes
     * through as it is. A Referer from anywhere else opens nothing.
     */
    @Test
    fun aCardsAbsolutePathFromTheIslandPageGoesIntoTheCapability() {
        val (_, url) = island.registerSlot(RecordingSlot())
        val base = island.ensureStarted()
        val fromPage = mapOf("Referer" to url)

        val picture = get("$base/api/camera_proxy/cam?token=abc", headers = fromPage, capability = false)
        assertEquals(307, picture.code)
        assertEquals("${island.capPath}/api/camera_proxy/cam?token=abc", picture.location)
        assertEquals("no-store", picture.cacheControl)
        assertTrue("a redirect asks Home Assistant nothing", haSeen.isEmpty())
        assertEquals(200, get(base + picture.location, capability = false).code)
        assertEquals("/api/camera_proxy/cam?token=abc", haSeen.single().target)
        haSeen.clear()

        // A module the island holds under the capability names its own address: its absolute imports go in too.
        val module = mapOf("Referer" to "$base${island.capPath}/hacsfiles/a/card.js")
        val imported = get("$base/local/custom-card.js", headers = module, capability = false)
        assertEquals(307, imported.code)
        assertEquals("${island.capPath}/local/custom-card.js", imported.location)

        // `hass.callApi('POST', …)` with an absolute path of a card's own: through, with its body, no redirect.
        val post = get("$base/api/services/light/turn_on", "POST", "{\"a\":1}", headers = fromPage, capability = false)
        assertEquals(200, post.code)
        assertEquals("{\"a\":1}", haSeen.single().body)
        haSeen.clear()

        // A path the proxy doesn't forward stays refused, Referer or not.
        assertEquals(404, get("$base/config/dashboard", headers = fromPage, capability = false).code)
        assertEquals(404, get("$base/auth/token", "POST", "{}", headers = fromPage, capability = false).code)

        // A Referer that isn't an island address of this server: another port, another host, no capability, a guess,
        // the capability further in the path.
        for (referer in listOf(
            "http://127.0.0.1:1${island.capPath}/__vitro/island/s1",
            "http://localhost:${island.port}${island.capPath}/__vitro/island/s1",
            "http://evil.example${island.capPath}/",
            "$base/__vitro/island/s1",
            "$base/${"0".repeat(32)}/__vitro/island/s1",
            "$base/x${island.capPath}/",
            "$base${island.capPath}",
            "$base${island.capPath.dropLast(1)}/",
            "",
        )) {
            assertEquals(referer, 403, get("$base/api/camera_proxy/cam", headers = mapOf("Referer" to referer), capability = false).code)
            assertEquals(referer, 403, get("$base/api/services/light/turn_on", "POST", "{}", headers = mapOf("Referer" to referer), capability = false).code)
        }
        assertTrue("Home Assistant heard nothing", haSeen.isEmpty())
    }

    /** A capability is per server: another server's (an earlier app start's) opens nothing here. */
    @Test
    fun eachServerHasItsOwnCapability() {
        val second = VitroIslandServer(session = { haBase to token })
        try {
            second.ensureStarted()
            assertFalse(second.capPath == island.capPath)
            val base = island.ensureStarted()
            assertEquals(403, get("$base${second.capPath}/local/custom-card.js", capability = false).code)
            assertEquals(200, getOn(second, "/local/custom-card.js").code)
        } finally {
            second.stop()
        }
    }

    @Test
    fun theCapabilityStaysOffWhatGoesToHomeAssistant() {
        val base = island.ensureStarted()
        val (_, url) = island.registerSlot(RecordingSlot())
        assertTrue(island.isIslandPage(url))
        assertFalse(island.isIslandPage(url.replace(island.capPath, "")))
        assertFalse(island.isIslandPage("$base${island.capPath}/local/x.html"))
        assertEquals("/local/help.html?x=1", island.haPath("$base${island.capPath}/local/help.html?x=1"))
        assertEquals("/local/help.html", island.haPath("$base/local/help.html"))
        assertEquals("/", island.haPath("$base${island.capPath}"))
        assertNull(island.haPath("http://127.0.0.1:1/local/help.html"))
        assertEquals("/lovelace/1", island.withoutCap("${island.capPath}/lovelace/1"))
        assertEquals("/lovelace/1", island.withoutCap("/lovelace/1"))
    }

    // ---- Loopback hardening ----

    /** One raw request with [host] as its Host header: the status line's code. */
    private fun statusWithHost(path: String, host: String): Int {
        java.net.Socket(loopback, island.ensureStarted().substringAfterLast(':').toInt()).use { sock ->
            sock.soTimeout = 5_000
            sock.getOutputStream().write("GET $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray())
            val status = sock.getInputStream().bufferedReader().readLine().orEmpty()
            return status.split(' ').getOrNull(1)?.toIntOrNull() ?: -1
        }
    }

    @Test
    fun aRequestNamingAnotherHostIsRefused() {
        // DNS rebinding: a page on evil.example that resolves to 127.0.0.1 sends its own name, even with the capability.
        val (_, url) = island.registerSlot(RecordingSlot())
        val path = url.substringAfter(island.ensureStarted())
        assertEquals(421, statusWithHost("${island.capPath}/local/custom-card.js", "evil.example:${island.port}"))
        assertEquals(421, statusWithHost(path, "localhost:${island.port}"))
        assertEquals(421, statusWithHost(path, "127.0.0.1:1"))
        assertEquals(200, statusWithHost(path, "127.0.0.1:${island.port}"))
        assertTrue("Home Assistant heard nothing", haSeen.isEmpty())
    }

    @Test
    fun theProxyForwardsOnlyThePathsTheIslandsUse() {
        val base = island.ensureStarted()
        assertEquals(404, get("$base/some/other/thing").code)
        assertEquals(404, get("$base/.well-known/x").code)
        assertTrue("Home Assistant heard nothing", haSeen.isEmpty())
        for (p in listOf("/api/states", "/local/x.js", "/hacsfiles/a/b.js", "/static/icons/x.png", "/frontend_latest/app.js",
            "/frontend_es5/app.js", "/media/local/x.jpg")) {
            assertTrue(p, VitroIslandServer.proxied(p))
        }
        // Home Assistant's pages, sign-in and root files load from Home Assistant itself, never with the panel's token.
        for (p in listOf("/", "/auth/authorize", "/auth/token", "/manifest.json", "/sw-modern.js", "/config/dashboard",
            "/history", "/developer-tools/state", "/mirror-home/wall", "/profile", "/hacs/dashboard", "/x", "/some/thing",
            "/robots.txt.bak", "/a.b-c")) {
            assertFalse(p, VitroIslandServer.proxied(p))
        }
        for (p in listOf("/config/dashboard", "/auth/token", "/history", "/")) assertEquals(p, 404, get("$base$p").code)
        assertTrue("Home Assistant heard nothing", haSeen.isEmpty())
    }

    @Test
    fun aRedirectIntoTheDashboardsResourceFolderIsFollowed() {
        val withResources = VitroIslandServer(session = { haBase to token }, resources = { listOf("/my_integration_card/card.js") })
        try {
            val b = withResources.ensureStarted()
            // The same hop the plain proxy refuses (aRedirectToAPathTheProxyRefusesIsNotFollowed) is followed into a
            // resource folder, and only there.
            val reply = get("$b${withResources.capPath}/local/to-resource", capability = false)
            assertEquals(200, reply.code)
            assertEquals(listOf("/local/to-resource", "/my_integration_card/chunk.js"), haSeen.map { it.target })
        } finally {
            withResources.stop()
        }
    }

    @Test
    fun aTokenAnswerIsNeverCachedWhilePublicFilesKeepTheirCaching() {
        val base = island.ensureStarted()
        assertEquals("no-store", get("$base/api/private").cacheControl)
        assertEquals("no-store", get("$base/api/camera_proxy/cam").cacheControl)
        assertEquals("max-age=3600", get("$base/local/cached.js").cacheControl)
        // The path the answer came from decides: a public file that redirects to the API answers no-store.
        assertEquals("no-store", get("$base/local/to-api").cacheControl)
    }

    @Test
    fun aRedirectToAPathTheProxyRefusesIsNotFollowed() {
        val reply = get(island.ensureStarted() + "/local/away")
        assertEquals(502, reply.code)
        assertEquals(listOf("/local/away"), haSeen.map { it.target })
    }

    @Test
    fun aBodyOfDeclaredLengthIsReadIntoOneArrayOfThatSize() {
        val body = ByteArray(100_000) { (it % 251).toByte() }
        assertTrue(body.contentEquals(VitroIslandServer.readCapped(java.io.ByteArrayInputStream(body), 1 shl 20, expected = body.size.toLong())))
        // Shorter than declared: what came. Longer: all of it, still under the cap.
        assertEquals(40, VitroIslandServer.readCapped(java.io.ByteArrayInputStream(ByteArray(40)), 1 shl 20, expected = 100).size)
        val long = ByteArray(5_000) { 7 }
        assertTrue(long.contentEquals(VitroIslandServer.readCapped(java.io.ByteArrayInputStream(long), 1 shl 20, expected = 1_000)))
        // A declared length over the cap reads as an unknown one, and fails at the cap.
        try {
            VitroIslandServer.readCapped(java.io.ByteArrayInputStream(ByteArray(3_000)), 2_048, expected = 3_000)
            org.junit.Assert.fail("over the cap")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("over"))
        }
        try {
            VitroIslandServer.readCapped(java.io.ByteArrayInputStream(ByteArray(3_000)), 2_048, expected = 1_000)
            org.junit.Assert.fail("longer than declared and over the cap")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("over"))
        }
    }

    @Test
    fun proxiedBodiesShareOneMemoryBudget() {
        // 32 KiB of room: a 64 KiB body can't be read while it's taken, and what was taken is reported to release.
        val budget = java.util.concurrent.Semaphore(32)
        val held = IntArray(1)
        try {
            VitroIslandServer.readCapped(java.io.ByteArrayInputStream(ByteArray(64 * 1024)), 1 shl 20, budget, held, waitMs = 50)
            org.junit.Assert.fail("the budget should run out")
        } catch (e: IOException) {
            assertEquals("proxy busy", e.message)
        }
        assertEquals(32, held[0])
        assertEquals(0, budget.availablePermits())
        // Within the budget it reads whole.
        val roomy = java.util.concurrent.Semaphore(128)
        val held2 = IntArray(1)
        assertEquals(64 * 1024, VitroIslandServer.readCapped(java.io.ByteArrayInputStream(ByteArray(64 * 1024)), 1 shl 20, roomy, held2).size)
        assertEquals(64, held2[0])
        // Short reads are charged for the bytes they bring, not a whole buffer each: 6 KiB in 1 KiB reads fit in 8.
        val trickle = object : java.io.InputStream() {
            var left = 6 * 1024
            override fun read(): Int = if (left-- > 0) 0 else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (left <= 0) return -1
                val n = minOf(1024, len, left); left -= n; return n
            }
        }
        val small = java.util.concurrent.Semaphore(8)
        val held3 = IntArray(1)
        assertEquals(6 * 1024, VitroIslandServer.readCapped(trickle, 1 shl 20, small, held3, waitMs = 50).size)
        assertEquals(6, held3[0])
    }

    @Test
    fun aPathThatLeavesItsPrefixIsNeverForwarded() {
        val base = island.ensureStarted()
        for (p in listOf("/local/../auth/token", "/local/%2e%2e/auth/token", "/local/%2E./auth/token", "/api/./states",
            "/local/a%2fb.js", "/local/a%5Cb.js", "/hacsfiles/x/../../auth/token")) {
            assertFalse(p, VitroIslandServer.proxied(p))
        }
        assertEquals(404, get("$base/local/%2e%2e/auth/token").code)
        assertTrue("Home Assistant heard nothing", haSeen.isEmpty())
    }

    /** An integration serves its card at a folder of its own (`/<integration>/card.js`), listed as a Lovelace resource. */
    @Test
    fun theDashboardsOwnResourceFoldersAreForwarded() {
        assertEquals(
            setOf("/my_integration_card/", "/root-card.js", "/other_frontend/"),
            VitroIslandServer.resourcePaths(
                listOf(
                    "/my_integration_card/card.js?v=3", "/root-card.js", "$haOrigin/other_frontend/x/y.js",
                    "/local/z.js", // under /local/ already, and listed as its folder like any other
                    "https://cdn.example.com/card.js", "//cdn.example.com/card.js", "http://192.0.2.99:8123/elsewhere/a.js",
                    "/auth/x.js", "/__vitro/island/x.js", "/my_integration_card/../auth/token", "data:text/javascript,1",
                ),
                haOrigin,
            ) - "/local/",
        )

        // The same origin with its default port written, or left out, on either side (RFC 5737 / example names).
        assertEquals(setOf("/a_card/", "/b_card/"), VitroIslandServer.resourcePaths(
            listOf("https://ha.example:443/a_card/x.js", "HTTPS://HA.example/b_card/y.js", "https://ha.example:8443/c_card/z.js",
                "http://ha.example/d_card/w.js"),
            "https://ha.example",
        ))
        assertEquals(setOf("/a_card/", "/b_card/"), VitroIslandServer.resourcePaths(
            listOf("http://192.0.2.10/a_card/x.js", "http://192.0.2.10:80/b_card/y.js", "http://192.0.2.10:8123/c_card/z.js"),
            "http://192.0.2.10:80/",
        ))
        assertEquals(setOf("/c_card/"), VitroIslandServer.resourcePaths(
            listOf("http://192.0.2.10:8123/c_card/z.js", "http://192.0.2.10/a_card/x.js"), "http://192.0.2.10:8123",
        ))

        val resources = listOf("/my_integration_card/card.js?v=3")
        val withResources = VitroIslandServer(session = { haBase to token }, resources = { resources })
        try {
            val b = withResources.ensureStarted()
            fun status(path: String) = get("$b${withResources.capPath}$path", capability = false).code
            assertEquals("forwarded (the fake HA has no such file)", 404, status("/my_integration_card/chunk-1.js"))
            assertEquals(listOf("/my_integration_card/chunk-1.js"), haSeen.map { it.target })
            assertEquals("fake-token-0001".let { "Bearer $it" }, haSeen.single().authorization)
            haSeen.clear()
            for (p in listOf("/my_integration_card", "/other_frontend/a.js", "/my_integration_card/../auth/token",
                "/my_integration_card/%2e%2e/auth/token")) {
                assertEquals(p, 404, status(p))
            }
            assertTrue("Home Assistant heard none of those", haSeen.isEmpty())
        } finally {
            withResources.stop()
        }
    }

    /**
     * #840: a registered resource at an integration's own path, with HA's cache-busting query, loads as a module, and so
     * does what it imports beside it. Another integration's folder, one no resource names, stays refused.
     */
    @Test
    fun aRegisteredResourceAtAnIntegrationsOwnPathLoads() {
        val withResources = VitroIslandServer(session = { haBase to token }, resources = { listOf("/dreame_vacuum/frontend.js?v=1.0.0") })
        try {
            val b = withResources.ensureStarted()
            fun fetch(path: String) = get("$b${withResources.capPath}$path", capability = false)
            val module = fetch("/dreame_vacuum/frontend.js?v=1.0.0")
            assertEquals(200, module.code)
            assertEquals("text/javascript", module.contentType)
            assertEquals(200, fetch("/dreame_vacuum/cards/map.js").code)
            assertEquals(listOf("/dreame_vacuum/frontend.js?v=1.0.0", "/dreame_vacuum/cards/map.js"), haSeen.map { it.target })
            haSeen.clear()
            assertEquals(404, fetch("/other_vacuum/frontend.js").code)
            assertTrue("Home Assistant heard nothing", haSeen.isEmpty())
        } finally {
            withResources.stop()
        }
    }

    @Test
    fun aBridgeNeedsTheCapability() {
        val slot = RecordingSlot()
        val (id, url) = island.registerSlot(slot)
        // Outside the capability: refused, whatever Origin or Referer it writes (anyone can write those).
        for (headers in listOf(
            emptyMap(),
            mapOf("Origin" to "http://evil.example"),
            mapOf("Origin" to "http://127.0.0.1:${island.port}"),
            mapOf("Referer" to url),
        )) {
            try {
                Bridge(id, headers = headers, inside = false).abort()
                throw AssertionError("a bridge outside the capability should be refused ($headers)")
            } catch (e: IOException) {
                assertTrue(e.message, e.message.orEmpty().contains("403"))
            }
        }
        val inside = Bridge(id, headers = mapOf("Origin" to "http://127.0.0.1:${island.port}"))
        try {
            assertEquals("init", inside.line().getString("type"))
        } finally {
            inside.abort()
        }
    }

    @Test
    fun theTokenAndOriginAreReadOnEveryRequest() {
        val base = island.ensureStarted()
        get("$base/local/custom-card.js")
        token = "fake-token-0002"
        get("$base/local/custom-card.js")
        token = null
        get("$base/local/custom-card.js")
        assertEquals(listOf("Bearer fake-token-0001", "Bearer fake-token-0002", null), haSeen.map { it.authorization })

        haBase = null
        assertEquals("no Home Assistant yet", 503, get("$base/local/custom-card.js").code)
        assertEquals(3, haSeen.size)
    }

    @Test
    fun anUpstreamErrorStatusIsPassedOn() {
        assertEquals(404, get(island.ensureStarted() + "/local/missing.js").code)
    }

    @Test
    fun anUnreachableHomeAssistantIs502() {
        haBase = "http://127.0.0.1:1" // nothing listens there
        assertEquals(502, get(island.ensureStarted() + "/local/custom-card.js").code)
    }

    // ---- WebSocket bridge ----

    @Test
    fun webSocketSendsTheInitialPayloadAndRoundTripsLines() {
        val slot = RecordingSlot()
        val (id, _) = island.registerSlot(slot)
        val ws = Bridge(id)
        try {
            val init = ws.line()
            assertEquals("init", init.getString("type"))
            assertEquals("on", init.getJSONObject("states").getString("light.lounge_lamp"))
            await(slot.connected, "onConnected")

            ws.say(JSONObject().put("type", "watch").put("ids", org.json.JSONArray().put("light.lounge_lamp")))
            assertEquals("watch", ws.line().getString("for"))
            ws.say(JSONObject().put("type", "callWs").put("id", 7).put("msg", JSONObject().put("type", "ping")))
            assertEquals("callWs", ws.line().getString("for"))
            ws.say(JSONObject().put("type", "callService").put("domain", "light").put("service", "turn_on"))
            assertEquals("callService", ws.line().getString("for"))

            await(slot.gotMessage, "three messages")
            assertEquals(listOf("watch", "callWs", "callService"), slot.received.map { it.getString("type") })
            assertEquals("light.lounge_lamp", slot.received[0].getJSONArray("ids").getString(0))
            assertEquals(7, slot.received[1].getInt("id"))
            assertEquals("turn_on", slot.received[2].getString("service"))
        } finally {
            ws.abort()
        }
        await(slot.disconnected, "onDisconnected after the client dropped")
    }

    @Test
    fun webSocketToAnUnknownSlotIsRefused() {
        island.ensureStarted()
        try {
            Bridge("s999").abort()
            throw AssertionError("the upgrade should have been refused")
        } catch (e: IOException) {
            // 404 instead of 101; a refused or timed-out connection is a different failure.
            assertTrue("expected a 404, got: ${e.message}", e.message.orEmpty().contains("404"))
        }
    }

    @Test
    fun unregisteringASlotClosesItsSocketAndForgetsThePage() {
        val slot = RecordingSlot()
        val (id, url) = island.registerSlot(slot)
        val ws = Bridge(id)
        try {
            ws.line()
            await(slot.connected, "onConnected")

            island.unregisterSlot(id)

            await(slot.disconnected, "onDisconnected after unregisterSlot")
            assertClosed(ws)
            assertEquals(404, get(url).code)
        } finally {
            ws.abort()
        }
    }

    @Test
    fun stopTearsDownSocketsAndTheListener() {
        val slot = RecordingSlot()
        val (id, _) = island.registerSlot(slot)
        val ws = Bridge(id)
        try {
            ws.line()
            await(slot.connected, "onConnected")

            island.stop()

            await(slot.disconnected, "onDisconnected after stop")
            assertClosed(ws)
            assertEquals(-1, island.port)
            assertNull(island.origin)
            // No request to the old port: another island server in a parallel test can be given the same ephemeral
            // port and serve a slot with the same id and page. That the slot is cleared is checked after the restart below.
        } finally {
            ws.abort()
        }
        // It can start again (on a new port), with no slots left.
        val again = island.ensureStarted()
        assertNotNull(again)
        assertFalse(get("$again/__vitro/island/$id").code == 200)
    }
}
