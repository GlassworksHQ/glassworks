/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import dev.glassworks.engine.vitro.island.VitroIslandServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The island server keeps one origin across app starts (#881) and an island's bridge open while it is quiet (#882).
 * Real loopback sockets, no Home Assistant: nothing here is proxied.
 */
class IslandOriginAndBridgeTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val servers = CopyOnWriteArrayList<VitroIslandServer>()
    private val logs = CopyOnWriteArrayList<String>()

    /** The app's preferences, as far as the port goes: they outlive a server (an app start). */
    private class Memory(var port: Int = 0) : VitroIslandServer.PortMemory {
        override fun load(): Int = port
        override fun save(port: Int) { this.port = port }
    }

    private fun server(
        memory: VitroIslandServer.PortMemory? = null,
        idleMs: Int = VitroIslandServer.BRIDGE_IDLE_MS,
        keepAliveMs: Long = VitroIslandServer.KEEPALIVE_MS,
    ) = VitroIslandServer(
        session = { null to null },
        log = { logs += it },
        portMemory = memory,
        bridgeIdleMs = idleMs,
        keepAliveMs = keepAliveMs,
    ).also { servers += it }

    @After
    fun tearDown() {
        servers.forEach { it.stop() }
    }

    // ---- #881: one origin across app starts ----

    /**
     * A socket listening on a free port in [VitroIslandServer.NEW_PORTS], or null when none of a few tries is free. The
     * caller closes it.
     */
    private fun listenInNewPorts(): ServerSocket? {
        val random = java.util.Random()
        repeat(20) {
            val port = VitroIslandServer.NEW_PORTS.first + random.nextInt(VitroIslandServer.NEW_PORTS.last - VitroIslandServer.NEW_PORTS.first + 1)
            val s = ServerSocket()
            try {
                s.bind(java.net.InetSocketAddress(loopback, port))
                return s
            } catch (_: java.io.IOException) {
                s.close()
            }
        }
        return null
    }

    @Test
    fun anAppRestartKeepsTheIslandsOrigin() {
        val memory = Memory()
        val first = server(memory)
        val origin = first.ensureStarted()
        assertTrue("a new port is picked below the ephemeral range: $origin", first.port in VitroIslandServer.NEW_PORTS)
        assertEquals("and remembered", first.port, memory.port)
        // stop() returns with the port free (HttpServer waits for its listener), as a new process would find it.
        first.stop()

        // The next app start: a new server, the same preferences.
        val second = server(memory)
        assertEquals("the same origin, so the cards' storage is still theirs", origin, second.ensureStarted())
        second.stop()
        assertEquals(origin, server(memory).ensureStarted())
        assertTrue("nothing moved: ${logs.joinToString()}", logs.none { "taken" in it })
    }

    @Test
    fun aPortAnotherAppHoldsMovesTheIslandsAndTheNewOneIsRemembered() {
        // Another app holds the port the islands had last time: bound here first, so nothing races for it.
        val squatter = listenInNewPorts() ?: throw AssertionError("no free port in ${VitroIslandServer.NEW_PORTS} after 20 tries")
        val old = squatter.localPort
        val memory = Memory(port = old)
        val s = server(memory)
        squatter.use {
            val moved = s.ensureStarted()
            assertNotEquals("a free port", "http://127.0.0.1:$old", moved)
            assertTrue("a new one below the ephemeral range: $moved", s.port in VitroIslandServer.NEW_PORTS)
            assertEquals("remembered for the next start", s.port, memory.port)
            assertTrue("logged: ${logs.joinToString()}", logs.any { "$old is taken" in it })
        }
        // The next start keeps the new one, though the old one is free again.
        val now = s.port
        s.stop()
        assertEquals(now, server(memory).ensureStarted().substringAfterLast(':').toInt())
    }

    @Test
    fun aPortThatIsNoPortIsReplaced() {
        val memory = Memory(port = 80)
        val s = server(memory)
        s.ensureStarted()
        assertTrue(s.port in VitroIslandServer.NEW_PORTS)
        assertEquals(s.port, memory.port)
    }

    @Test
    fun withoutMemoryEveryStartTakesAnEphemeralPort() {
        val s = server(null)
        s.ensureStarted()
        assertTrue("an ephemeral port, as before: ${s.port}", s.port > 0 && s.port !in VitroIslandServer.NEW_PORTS)
    }

    // ---- #882: a quiet bridge stays open ----

    /** The ping schedule on a fake clock: pings come every interval, however the card's messages fall. */
    @Test
    fun overADayOfIdleAndBusyStretchesNoPingIsLate() {
        var now = 1_000_000L
        val interval = VitroIslandServer.KEEPALIVE_MS
        val keepAlive = VitroIslandServer.KeepAlive(interval) { now }
        val random = java.util.Random(881)
        // The page's last read the server saw: a pong comes back with each ping (loopback, ~1 ms).
        var lastRead = now
        var pings = 0
        val day = 24 * 3600 * 1000L
        val end = now + day
        while (now < end) {
            // The writer waits for a message at most until the next ping is due. Messages come at random: none for
            // hours (a quiet house), or every few ms (a busy one), which must not hold the pings back.
            val nextMessage = if (random.nextInt(4) == 0) random.nextInt(50).toLong() else random.nextInt(3 * 3600_000).toLong()
            now += minOf(keepAlive.waitMs(), nextMessage)
            assertTrue("the read deadline never passes", now - lastRead < VitroIslandServer.BRIDGE_IDLE_MS)
            if (keepAlive.due()) {
                keepAlive.pinged()
                pings++
                lastRead = now + 1
            }
        }
        assertTrue("one ping per interval, no more: $pings", kotlin.math.abs(day / interval - pings) <= 1)
        assertTrue(interval * 3 < VitroIslandServer.BRIDGE_IDLE_MS)
    }

    @Test
    fun aQuietIslandKeepsItsOneBridgePastTheReadDeadline() {
        // Scaled down: a 400 ms deadline and a ping every 100 ms stand for 120 s and 30 s.
        val s = server(idleMs = 400, keepAliveMs = 100)
        val slot = CountingSlot()
        val (id, _) = s.registerSlot(slot)
        val ws = Client(s, id, answerPings = true)
        try {
            assertEquals("snapshot", ws.next().getString("type"))
            Thread.sleep(2_000) // five deadlines with nothing from the page but its pongs
            assertEquals("connected once: no reconnect, no second snapshot", 1, slot.connects.get())
            assertEquals("never dropped", 1L, slot.disconnected.count)
            assertTrue("pinged: ${ws.pings.get()}", ws.pings.get() >= 10)
            // The same socket still carries the card's messages.
            ws.say(JSONObject().put("type", "hello"))
            assertEquals("ack", ws.next().getString("type"))
        } finally {
            ws.close()
        }
    }

    @Test
    fun aPageThatStopsAnsweringIsStillDroppedAtTheDeadline() {
        val s = server(idleMs = 300, keepAliveMs = 100)
        val slot = CountingSlot()
        val (id, _) = s.registerSlot(slot)
        val ws = Client(s, id, answerPings = false)
        try {
            assertEquals("snapshot", ws.next().getString("type"))
            assertTrue("dropped once nothing came back", slot.disconnected.await(5, TimeUnit.SECONDS))
        } finally {
            ws.close()
        }
    }

    /** Sends a snapshot on connect and acks each message; counts connections. */
    private class CountingSlot : VitroIslandServer.SlotHandler {
        val connects = AtomicInteger()
        val disconnected = CountDownLatch(1)
        override val html = "<html></html>"
        override fun onConnected(send: (JSONObject) -> Unit) {
            connects.incrementAndGet()
            send(JSONObject().put("type", "snapshot"))
        }
        override fun onMessage(msg: JSONObject, send: (JSONObject) -> Unit) = send(JSONObject().put("type", "ack"))
        override fun onDisconnected(send: (JSONObject) -> Unit) = disconnected.countDown()
    }

    /**
     * An island page's bridge, as a browser runs it: a reader thread answers each ping with a pong ([answerPings]),
     * as Gecko's network code does without the page's script, and queues text messages for [next].
     */
    private inner class Client(server: VitroIslandServer, id: String, answerPings: Boolean) {
        private val sock = Socket(loopback, server.port)
        private val input = BufferedInputStream(sock.getInputStream())
        private val out = sock.getOutputStream()
        private val texts = java.util.concurrent.LinkedBlockingQueue<String>()
        val pings = AtomicInteger()

        init {
            out.write(
                ("GET ${server.capPath}/__vitro/ws/$id HTTP/1.1\r\nHost: 127.0.0.1:${server.port}\r\nOrigin: http://127.0.0.1:${server.port}\r\n" +
                    "Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
                    "Sec-WebSocket-Version: 13\r\n\r\n").toByteArray(),
            )
            out.flush()
            val status = line()
            check(status.startsWith("HTTP/1.1 101")) { status }
            while (line().isNotEmpty()) Unit
            Thread({
                try {
                    while (true) {
                        val b0 = input.read(); if (b0 < 0) break
                        val b1 = input.read(); if (b1 < 0) break
                        var len = b1 and 0x7f
                        if (len == 126) len = (input.read() shl 8) or input.read()
                        val payload = ByteArray(len)
                        var got = 0
                        while (got < len) { val n = input.read(payload, got, len - got); if (n < 0) return@Thread; got += n }
                        when (b0 and 0x0f) {
                            0x1 -> texts += String(payload, Charsets.UTF_8)
                            0x9 -> { pings.incrementAndGet(); if (answerPings) frame(0xA, payload) }
                            0x8 -> break
                        }
                    }
                } catch (_: Exception) {
                }
            }, "test-bridge-reader").apply { isDaemon = true; start() }
        }

        private fun line(): String {
            val sb = StringBuilder()
            while (true) {
                val c = input.read()
                if (c < 0 || c == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(c.toChar())
            }
        }

        /** A masked frame, as a client sends it. */
        @Synchronized
        private fun frame(op: Int, payload: ByteArray) {
            val mask = byteArrayOf(9, 8, 7, 6)
            val head = ByteArrayOutputStream().apply {
                write(0x80 or op)
                if (payload.size < 126) write(0x80 or payload.size) else { write(0x80 or 126); write(payload.size shr 8); write(payload.size and 0xff) }
                write(mask)
            }
            out.write(head.toByteArray())
            out.write(ByteArray(payload.size) { (payload[it].toInt() xor mask[it and 3].toInt()).toByte() })
            out.flush()
        }

        fun say(obj: JSONObject) = frame(0x1, (obj.toString() + "\n").toByteArray())

        fun next(): JSONObject = JSONObject(texts.poll(5, TimeUnit.SECONDS)?.trim() ?: throw AssertionError("no message"))

        fun close() = runCatching { sock.close() }
    }
}
