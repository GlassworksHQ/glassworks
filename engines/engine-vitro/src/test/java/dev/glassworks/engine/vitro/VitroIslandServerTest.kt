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

        override fun onDisconnected() {
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
    ) {
        val text get() = String(body, Charsets.UTF_8)
    }

    /** The proxy's capability cookie (`vitro_cap=…`), as the entry route hands it to a browser. */
    private val capability: String by lazy {
        get(island.entryUrl("/"), capability = false).setCookie!!.substringBefore(';')
    }

    /** [capability]: send the proxy's cookie, as the island page or the legacy engine's browser has it. */
    private fun get(
        url: String,
        method: String = "GET",
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
        capability: Boolean = true,
    ): Reply {
        val cookie = if (capability) this.capability else null
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.instanceFollowRedirects = false
            cookie?.let { conn.setRequestProperty("Cookie", it) }
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
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun await(latch: CountDownLatch, what: String) =
        assertTrue("timed out waiting for $what", latch.await(5, TimeUnit.SECONDS))

    /**
     * An island page's bridge: the upgrade with [headers] (by default the capability cookie the page holds), then
     * RFC 6455 text frames. A refused upgrade throws an [IOException] naming the status.
     */
    private inner class Bridge(id: String, headers: Map<String, String> = mapOf("Cookie" to capability)) {
        private val sock = java.net.Socket(loopback, island.ensureStarted().substringAfterLast(':').toInt()).apply { soTimeout = 5_000 }
        private val input = java.io.BufferedInputStream(sock.getInputStream())
        private val out = sock.getOutputStream()

        init {
            val req = StringBuilder("GET /__vitro/ws/$id HTTP/1.1\r\nHost: 127.0.0.1:${island.port}\r\n")
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
        assertEquals(island.ensureStarted() + "/__vitro/island/" + id, url)

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
        // Any app on the device can reach loopback: without the cookie it never gets the panel's token spent for it.
        val base = island.ensureStarted()
        assertEquals(403, get("$base/api/services/light/turn_on", "POST", "{}", capability = false).code)
        assertEquals(403, get("$base/local/custom-card.js", capability = false).code)
        assertEquals(403, get("$base/local/custom-card.js", headers = mapOf("Cookie" to "vitro_cap=guess"), capability = false).code)
        assertTrue("Home Assistant heard nothing", haSeen.isEmpty())
        assertEquals(200, get("$base/local/custom-card.js").code)
    }

    @Test
    fun theIslandPageAndTheEntryRouteHandOutTheCapability() {
        val (_, url) = island.registerSlot(RecordingSlot())
        val page = get(url, capability = false)
        assertEquals(200, page.code)
        assertTrue(page.setCookie!!, page.setCookie.startsWith(capability + ";"))
        assertTrue(page.setCookie.contains("HttpOnly"))
        assertTrue(page.setCookie.contains("SameSite=Strict"))

        val entry = get(island.entryUrl("/config?tab=1"), capability = false)
        assertEquals(302, entry.code)
        assertEquals("/config?tab=1", entry.location)
        assertTrue(entry.setCookie!!.startsWith(capability + ";"))

        val base = island.ensureStarted()
        assertEquals(403, get("$base/__vitro/enter?c=guess&to=%2Fconfig", capability = false).code)
        fun code() = island.entryUrl("/").substringAfter("c=").substringBefore('&')
        // The code opens the path it was issued for: a `to` swapped into the URL goes nowhere else.
        assertEquals("/", get("$base/__vitro/enter?c=${code()}&to=%2F%2Fexample.com", capability = false).location)
        assertEquals("/", get("$base/__vitro/enter?c=${code()}&to=https%3A%2F%2Fexample.com", capability = false).location)
        // The URL carries a one-time code, never the capability itself.
        assertFalse(island.entryUrl("/config").contains(capability.substringAfter('=')))
    }

    // ---- Loopback hardening ----

    /** One raw request with [host] as its Host header: the status line's code. */
    private fun statusWithHost(path: String, host: String): Int {
        java.net.Socket(loopback, island.ensureStarted().substringAfterLast(':').toInt()).use { sock ->
            sock.soTimeout = 5_000
            sock.getOutputStream().write("GET $path HTTP/1.1\r\nHost: $host\r\nCookie: $capability\r\nConnection: close\r\n\r\n".toByteArray())
            val status = sock.getInputStream().bufferedReader().readLine().orEmpty()
            return status.split(' ').getOrNull(1)?.toIntOrNull() ?: -1
        }
    }

    @Test
    fun aRequestNamingAnotherHostIsRefused() {
        // DNS rebinding: a page on evil.example that resolves to 127.0.0.1 sends its own name, even with the cookie.
        val (_, url) = island.registerSlot(RecordingSlot())
        val path = url.substringAfter(island.ensureStarted())
        assertEquals(421, statusWithHost("/local/custom-card.js", "evil.example:${island.port}"))
        assertEquals(421, statusWithHost(path, "localhost:${island.port}"))
        assertEquals(421, statusWithHost(path, "127.0.0.1:1"))
        assertEquals(200, statusWithHost(path, "127.0.0.1:${island.port}"))
        assertTrue("Home Assistant heard nothing", haSeen.isEmpty())
    }

    @Test
    fun theProxyForwardsOnlyThePathsHaAndTheIslandsUse() {
        val base = island.ensureStarted()
        assertEquals(404, get("$base/some/other/thing").code)
        assertEquals(404, get("$base/.well-known/x").code)
        assertTrue("Home Assistant heard nothing", haSeen.isEmpty())
        for (p in listOf("/", "/api/states", "/auth/authorize", "/auth/token", "/local/x.js", "/hacsfiles/a/b.js", "/static/icons/x.png",
            "/frontend_latest/app.js", "/frontend_es5/app.js", "/media/local/x.jpg", "/manifest.json", "/sw-modern.js",
            "/config/dashboard", "/history", "/developer-tools/state", "/mirror-home/wall", "/profile")) {
            assertTrue(p, VitroIslandServer.proxied(p))
        }
        for (p in listOf("/x", "/some/thing", "/robots.txt.bak", "/a.b-c")) assertFalse(p, VitroIslandServer.proxied(p))
        // HACS opens in the fallback: through the entry route, then its page reaches Home Assistant.
        val entry = get(island.entryUrl("/hacs/dashboard"), capability = false)
        assertEquals(302, entry.code)
        get("$base/hacs/dashboard", headers = mapOf("Cookie" to entry.setCookie!!.substringBefore(';')), capability = false)
        assertEquals(listOf("/hacs/dashboard"), haSeen.map { it.target })
    }

    @Test
    fun anEntryPathStaysOnThisOriginAndOutOfOtherHeaders() {
        assertEquals("/example.com", VitroIslandServer.entryPath("//example.com"))
        assertEquals("/example.com", VitroIslandServer.entryPath("/\\example.com"))
        assertEquals("/config", VitroIslandServer.entryPath("config"))
        val entry = get(island.entryUrl("/config\r\nX-Evil: 1"), capability = false)
        assertEquals(302, entry.code)
        assertEquals("/config%0D%0AX-Evil: 1", entry.location)
    }

    @Test
    fun aTokenAnswerIsNeverCachedWhilePublicFilesKeepTheirCaching() {
        val base = island.ensureStarted()
        assertEquals("no-store", get("$base/api/private").cacheControl)
        assertEquals("no-store", get("$base/api/camera_proxy/cam").cacheControl)
        assertEquals("max-age=3600", get("$base/local/cached.js").cacheControl)
    }

    @Test
    fun aRedirectToAPathTheProxyRefusesIsNotFollowed() {
        val reply = get(island.ensureStarted() + "/local/away")
        assertEquals(502, reply.code)
        assertEquals(listOf("/local/away"), haSeen.map { it.target })
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
    }

    @Test
    fun anEntryCodeWorksOnceAndNotAfterItExpires() {
        val base = island.ensureStarted()
        val url = island.entryUrl("/config")
        assertEquals(302, get(url, capability = false).code)
        assertEquals("used once only", 403, get(url, capability = false).code)
        island.entryCodeTtlNs = 1_000_000L // 1 ms
        val late = island.entryUrl("/config")
        Thread.sleep(20)
        assertEquals("expired", 403, get(late, capability = false).code)
        assertEquals(403, get("$base/__vitro/enter?c=&to=%2Fconfig", capability = false).code)
    }

    @Test
    fun aBridgeNeedsTheCapabilityOrThisServersOrigin() {
        val slot = RecordingSlot()
        val (id, _) = island.registerSlot(slot)
        try {
            Bridge(id, headers = emptyMap()).abort()
            throw AssertionError("a bridge without the cookie or Origin should be refused")
        } catch (e: IOException) {
            assertTrue(e.message, e.message.orEmpty().contains("403"))
        }
        try {
            Bridge(id, headers = mapOf("Origin" to "http://evil.example")).abort()
            throw AssertionError("another Origin should be refused")
        } catch (e: IOException) {
            assertTrue(e.message, e.message.orEmpty().contains("403"))
        }
        val byOrigin = Bridge(id, headers = mapOf("Origin" to "http://127.0.0.1:${island.port}"))
        try {
            assertEquals("init", byOrigin.line().getString("type"))
        } finally {
            byOrigin.abort()
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
