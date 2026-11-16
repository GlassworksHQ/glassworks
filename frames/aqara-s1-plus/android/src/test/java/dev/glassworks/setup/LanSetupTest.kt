/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.setup

import dev.glassworks.manage.PanelCode
import dev.glassworks.web.HttpRequest
import dev.glassworks.web.HttpResponse
import dev.glassworks.web.HttpServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import sh.edm.s1kiosk.setup.ChannelState
import sh.edm.s1kiosk.setup.SetupChannel
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.Socket
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Setup over the LAN (#34) end to end on loopback: the panel's web server with [LanSetup] in front, the real
 * [SetupChannel] behind the WebSocket, a fake backend that records what changed.
 */
class LanSetupTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private var wall = 1_000_000_000L
    private var mono = 0L
    private var open = true
    private val changes = CopyOnWriteArrayList<String>()
    private var shown: PanelCode.Shown? = null
    private val code = PanelCode(clock = { wall }).apply { canDisplay = { true }; display = { shown = it } }

    private val backend = object : SetupChannel.Backend {
        override fun state() = ChannelState(ip = "192.0.2.10", ha = ChannelState.Ha(null, "", false, null))
        override fun setHa(url: String): SetupChannel.HaChange { changes += "ha $url"; return SetupChannel.HaChange() }
        override fun armPairing(): String? { changes += "arm"; return null }
    }

    private val lan = LanSetup(
        serve = { i, o, may -> SetupChannel(backend, mayChange = may).serve(i, o, pollMs = 50) },
        code = code, ownHosts = { setOf("127.0.0.1") }, pairingOpen = { open }, monoMs = { mono }, wallMs = { wall },
    )
    private lateinit var server: HttpServer
    private val host get() = "127.0.0.1:${server.localPort}"

    @Before fun start() {
        server = HttpServer(0, { HttpResponse.text(404, "manage") }, bindAddress = loopback).apply { front = lan::handle; start() }
    }

    @After fun stop() = server.stop()

    @Test fun withoutASessionOnlyTheStateAnswers() {
        ws().use { c ->
            assertTrue(c.ask("""{"id":1,"op":"state"}""").getBoolean("ok"))
            for (op in listOf("""{"id":2,"op":"ha.set_url","url":"http://192.0.2.20:8123"}""", """{"id":3,"op":"set_ha","url":"http://192.0.2.20:8123"}""",
                """{"id":4,"op":"pairing.arm"}""", """{"id":5,"op":"radio.set_mode","mode":"off"}""")) {
                val r = c.ask(op)
                assertFalse(op, r.getBoolean("ok"))
                assertEquals(SetupChannel.NOT_PAIRED, r.getString("error"))
            }
        }
        assertEquals(emptyList<String>(), changes)
    }

    @Test fun theRightCodeGivesASessionThatChangesThings() {
        val session = pair()
        ws(session).use { c ->
            val r = c.ask("""{"id":7,"op":"ha.set_url","url":"http://192.0.2.20:8123"}""")
            assertTrue(r.toString(), r.getBoolean("ok"))
            assertEquals(7, r.getInt("id"))
        }
        assertEquals(listOf("ha http://192.0.2.20:8123"), changes)
        assertTrue(JSONObject(get(LanSetup.PAIR, session).text()).getBoolean("paired"))
    }

    @Test fun aWrongCodeOrNoCodeGivesNoSession() {
        val id = cookie(post(LanSetup.PAIR))!!
        val wrong = (shown!!.code.toInt() + 1).mod(1_000_000).toString().padStart(6, '0')
        val r = post(LanSetup.PAIR, "code=$wrong", id)
        assertEquals(401, r.status)
        assertEquals(null, cookie(r))
        ws(id).use { c -> assertFalse(c.ask("""{"id":1,"op":"pairing.arm"}""").getBoolean("ok")) }
        assertEquals(409, post(LanSetup.PAIR, "code=123456").status)
        assertEquals(emptyList<String>(), changes)
    }

    @Test fun guessingIsCutOffByThePanelCodesLimits() {
        // Five wrong tries kill a code.
        val first = cookie(post(LanSetup.PAIR))!!
        val firstCode = shown!!.code
        repeat(5) { assertEquals(401, post(LanSetup.PAIR, "code=${wrongFor(firstCode, it)}", first).status) }
        assertEquals("a dead code is no way in", 409, post(LanSetup.PAIR, "code=$firstCode", first).status)
        // Ten wrong tries in 15 minutes lock code entry: the tenth already says so, and so does a new code.
        wall += 31_000
        val second = cookie(post(LanSetup.PAIR))!!
        val secondCode = shown!!.code
        repeat(4) { assertEquals(401, post(LanSetup.PAIR, "code=${wrongFor(secondCode, it)}", second).status) }
        assertEquals(429, post(LanSetup.PAIR, "code=${wrongFor(secondCode, 4)}", second).status)
        val r = post(LanSetup.PAIR)
        assertEquals(429, r.status)
        assertTrue(r.text(), r.text().contains("Too many wrong codes"))
        assertEquals(emptyList<String>(), changes)
    }

    @Test fun pairingClosesOnceHomeAssistantAdoptedThePanelButASessionCarriesOn() {
        val session = pair()
        open = false
        val r = post(LanSetup.PAIR)
        assertEquals(403, r.status)
        assertEquals(LanSetup.PAIRING_OFF, JSONObject(r.text()).getString("error"))
        assertEquals(null, shown)
        ws(session).use { c -> assertTrue(c.ask("""{"id":1,"op":"pairing.arm"}""").getBoolean("ok")) }
    }

    @Test fun aSessionEndsWhenSetupIsDoneOrIdle() {
        val done = pair()
        assertEquals(200, post(LanSetup.DONE, cookie = done).status)
        ws(done).use { c -> assertFalse(c.ask("""{"id":1,"op":"pairing.arm"}""").getBoolean("ok")) }

        val idle = pair()
        mono += 30 * 60_000L
        ws(idle).use { c -> assertEquals(SetupChannel.NOT_PAIRED, c.ask("""{"id":1,"op":"pairing.arm"}""").getString("error")) }
        assertEquals(emptyList<String>(), changes)
    }

    @Test fun anotherSiteCantOpenTheChannelOrAskForACode() {
        assertEquals(403, handshake(origin = "http://evil.example").first)
        assertEquals(403, handshake(origin = null).first)
        assertEquals(400, handshake(hostHeader = "evil.example").first)
        assertEquals(403, post(LanSetup.PAIR, origin = "http://evil.example").status)
        assertEquals(403, post(LanSetup.PAIR, origin = null).status)
        assertEquals(null, shown)
    }

    @Test fun anUnpairedChannelNeverKeepsAPairedOneOut() {
        ws().use { a ->
            a.ask("""{"id":1,"op":"state"}""")
            assertEquals("one unpaired channel at a time", 503, handshake().first)
            val session = pair()
            ws(session).use { b ->
                assertTrue(b.ask("""{"id":2,"op":"pairing.arm"}""").getBoolean("ok"))
                ws(session).use { c ->
                    assertTrue("the newer paired channel takes over", b.ended())
                    assertTrue(c.ask("""{"id":3,"op":"pairing.arm"}""").getBoolean("ok"))
                }
            }
        }
    }

    @Test fun aChannelLeftOpenAfterDoneKeepsNoBrowserOut() {
        val first = pair()
        ws(first).use { a ->
            a.ask("""{"id":1,"op":"state"}""")
            assertEquals(200, post(LanSetup.DONE, cookie = first).status)
            assertTrue("setup done closes its channel", a.ended())
            ws(pair()).use { b -> assertTrue(b.ask("""{"id":2,"op":"pairing.arm"}""").getBoolean("ok")) }
        }
    }

    @Test fun anUnpairedChannelIsClosedSoonWhenIdle() {
        val quick = LanSetup(serve = { i, o, may -> SetupChannel(backend, mayChange = may).serve(i, o, pollMs = 50) },
            code = code, ownHosts = { setOf("127.0.0.1") }, pairingOpen = { open }, unpairedIdleMs = 300)
        server.front = quick::handle
        ws().use { c ->
            c.ask("""{"id":1,"op":"state"}""")
            assertTrue("closed by the panel", c.ended())
        }
    }

    @Test fun otherPathsGoToTheManagePage() {
        assertEquals(null, lan.handle(HttpRequest("GET", "/setup", emptyMap(), mapOf("host" to host))))
        assertEquals(null, lan.handle(HttpRequest("GET", "/", emptyMap(), mapOf("host" to host))))
    }

    // ------------------------------------------------------------------ helpers

    private fun wrongFor(right: String, n: Int) = ((right.toInt() + 1 + n).mod(1_000_000)).toString().padStart(6, '0')

    /** Pairs a browser: shows a code, types it. The session's cookie. */
    private fun pair(): String {
        val id = cookie(post(LanSetup.PAIR))!!
        val r = post(LanSetup.PAIR, "code=${shown!!.code}", id)
        assertEquals(r.text(), 200, r.status)
        return cookie(r)!!.also { assertTrue("the cookie is new after pairing", it != id) }
    }

    private fun HttpResponse.text() = body.toString(Charsets.UTF_8)

    private fun cookie(r: HttpResponse): String? =
        r.headers["Set-Cookie"]?.substringAfter("${LanSetup.COOKIE}=")?.substringBefore(';')?.takeIf { it.isNotEmpty() }

    private fun headers(cookie: String?, origin: String?) = buildMap {
        put("host", host)
        origin?.let { put("origin", it) }
        cookie?.let { put("cookie", "${LanSetup.COOKIE}=$it") }
    }

    private fun post(path: String, form: String = "", cookie: String? = null, origin: String? = "http://$host"): HttpResponse =
        lan.handle(HttpRequest("POST", path, emptyMap(), headers(cookie, origin) + ("content-type" to "application/x-www-form-urlencoded"),
            form.toByteArray()))!!

    private fun get(path: String, cookie: String?): HttpResponse = lan.handle(HttpRequest("GET", path, emptyMap(), headers(cookie, null)))!!

    /** A WebSocket handshake on a real connection: the status, and the socket. */
    private fun handshake(cookie: String? = null, origin: String? = "http://$host", hostHeader: String = host): Pair<Int, Socket> {
        val s = Socket(loopback, server.localPort).apply { soTimeout = 3_000 }
        val key = Base64.getEncoder().encodeToString(ByteArray(16) { it.toByte() })
        val head = buildString {
            append("GET ${LanSetup.CHANNEL} HTTP/1.1\r\nHost: $hostHeader\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n")
            append("Sec-WebSocket-Version: 13\r\nSec-WebSocket-Key: $key\r\n")
            origin?.let { append("Origin: $it\r\n") }
            cookie?.let { append("Cookie: ${LanSetup.COOKIE}=$it\r\n") }
            append("\r\n")
        }
        s.getOutputStream().write(head.toByteArray())
        val line = readHead(s.getInputStream())
        return line.split(' ')[1].toInt() to s
    }

    private fun readHead(inp: InputStream): String {
        val b = ByteArrayOutputStream()
        while (!b.toString().endsWith("\r\n\r\n")) b.write(inp.read().also { check(it >= 0) })
        return b.toString().lineSequence().first()
    }

    private fun ws(cookie: String? = null): Client {
        val (status, s) = handshake(cookie)
        assertEquals(101, status)
        return Client(s)
    }

    /** A browser's frame: masked, as the RFC asks of clients. */
    private fun clientFrame(op: Int, payload: ByteArray): ByteArray {
        val b = ByteArrayOutputStream()
        b.write(0x80 or op)
        if (payload.size < 126) b.write(0x80 or payload.size) else { b.write(0x80 or 126); b.write(payload.size shr 8); b.write(payload.size and 0xff) }
        val mask = byteArrayOf(0x0a, 0x1b, 0x2c, 0x3d)
        b.write(mask)
        b.write(ByteArray(payload.size) { (payload[it].toInt() xor mask[it and 3].toInt()).toByte() })
        return b.toByteArray()
    }

    private inner class Client(val s: Socket) : AutoCloseable {
        private val inp = DataInputStream(s.getInputStream())

        /** Sends one request line and reads frames until its reply (events come between). */
        fun ask(line: String): JSONObject {
            s.getOutputStream().write(clientFrame(0x1, (line + "\n").toByteArray()))
            val id = JSONObject(line).opt("id")
            while (true) {
                val b0 = inp.readUnsignedByte()
                var len = inp.readUnsignedByte()
                if (len == 126) len = inp.readUnsignedShort()
                val text = ByteArray(len).also(inp::readFully).toString(Charsets.UTF_8)
                assertEquals("a text frame", 0x81, b0)
                for (l in text.lines().filter { it.isNotBlank() }) {
                    val o = JSONObject(l)
                    if (o.opt("id") == id) return o
                }
            }
        }

        /** The panel ended the channel: a close frame, or the connection dropped, before any other frame but text. */
        fun ended(): Boolean {
            try {
                while (true) {
                    val b0 = inp.readUnsignedByte()
                    var len = inp.readUnsignedByte()
                    if (len == 126) len = inp.readUnsignedShort()
                    inp.skipBytes(len)
                    if (b0 != 0x81) return b0 == 0x88
                }
            } catch (_: java.io.IOException) {
                return true
            }
        }

        override fun close() {
            runCatching { s.getOutputStream().write(clientFrame(0x8, ByteArray(0))) }
            s.close()
        }
    }
}
