/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import dev.glassworks.engine.vitro.island.VitroIslandServer
import dev.glassworks.sendspin.WsClient
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
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * [VitroIslandServer] over real loopback sockets: a tiny [HttpServer] plays Home Assistant (the island server proxies
 * to it), the island's WebSocket is a real RFC 6455 client ([WsClient]). Tokens are made up; every address is loopback.
 */
class VitroIslandServerTest {
    private val loopback = InetAddress.getByName("127.0.0.1")

    /** What the fake Home Assistant saw: path with query, and the `Authorization` header (null when absent). */
    private class Seen(val target: String, val method: String, val authorization: String?, val body: String)

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
        haSeen += Seen(req.target, req.method, req.headers["authorization"], String(req.body, Charsets.UTF_8))
        return when {
            req.path == "/local/custom-card.js" ->
                HttpResponse(200, mapOf("Content-Type" to "application/javascript; charset=utf-8"), "customElements.define('x-card', class {});".toByteArray())
            req.path == "/api/camera_proxy/cam" ->
                HttpResponse(200, mapOf("Content-Type" to "image/jpeg"), byteArrayOf(1, 2, 3, 4))
            req.path == "/api/services/light/turn_on" && req.method == "POST" ->
                HttpResponse(200, mapOf("Content-Type" to "application/json"), "[]".toByteArray())
            else -> HttpResponse.text(404, "nope")
        }
    }

    private class Reply(val code: Int, val contentType: String?, val body: ByteArray) {
        val text get() = String(body, Charsets.UTF_8)
    }

    private fun get(url: String, method: String = "GET", body: String? = null): Reply {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 5_000
            conn.readTimeout = 10_000
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..399) conn.inputStream else conn.errorStream
            return Reply(code, conn.contentType, stream?.use { it.readBytes() } ?: ByteArray(0))
        } finally {
            conn.disconnect()
        }
    }

    private fun await(latch: CountDownLatch, what: String) =
        assertTrue("timed out waiting for $what", latch.await(5, TimeUnit.SECONDS))

    private fun wsUrl(id: String) = island.ensureStarted().replaceFirst("http://", "ws://") + "/__vitro/ws/$id"

    private fun WsClient.line(): JSONObject {
        val m = receive() ?: throw AssertionError("socket closed")
        assertTrue(m.text)
        return JSONObject(String(m.data, Charsets.UTF_8).trim())
    }

    private fun WsClient.say(obj: JSONObject) = sendText(obj.toString() + "\n")

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
        val ws = WsClient.connect(wsUrl(id), readTimeoutMs = 5_000)
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
            WsClient.connect(wsUrl("s999"), readTimeoutMs = 5_000).also { it.abort() }
            throw AssertionError("the upgrade should have been refused")
        } catch (_: IOException) {
            // 404 instead of 101.
        }
    }

    @Test
    fun unregisteringASlotClosesItsSocketAndForgetsThePage() {
        val slot = RecordingSlot()
        val (id, url) = island.registerSlot(slot)
        val ws = WsClient.connect(wsUrl(id), readTimeoutMs = 5_000)
        try {
            ws.line()
            await(slot.connected, "onConnected")

            island.unregisterSlot(id)

            await(slot.disconnected, "onDisconnected after unregisterSlot")
            val after = try { ws.receive() } catch (_: IOException) { null }
            assertNull("the socket is closed", after)
            assertEquals(404, get(url).code)
        } finally {
            ws.abort()
        }
    }

    @Test
    fun stopTearsDownSocketsAndTheListener() {
        val slot = RecordingSlot()
        val (id, _) = island.registerSlot(slot)
        val port = island.port
        val ws = WsClient.connect(wsUrl(id), readTimeoutMs = 5_000)
        try {
            ws.line()
            await(slot.connected, "onConnected")

            island.stop()

            await(slot.disconnected, "onDisconnected after stop")
            val after = try { ws.receive() } catch (_: IOException) { null }
            assertNull("the socket is closed", after)
            assertEquals(-1, island.port)
            assertNull(island.origin)
            try {
                Socket(loopback, port).close()
                throw AssertionError("nothing should listen on $port any more")
            } catch (_: ConnectException) {
                // Refused: the listener is gone.
            }
        } finally {
            ws.abort()
        }
        // It can start again (on a new port), with no slots left.
        val again = island.ensureStarted()
        assertNotNull(again)
        assertFalse(get("$again/__vitro/island/$id").code == 200)
    }
}
