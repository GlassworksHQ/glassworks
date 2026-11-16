/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.web

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream

/** The setup channel's WebSocket as a byte stream (#34): what a browser sends in, and what goes back out. */
class WebSocketTest {

    @Test fun theAcceptKeyIsTheRfcs() {
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", WebSocketStreams.accept("dGhlIHNhbXBsZSBub25jZQ=="))
    }

    @Test fun onlyAProperHandshakeIsAnUpgrade() {
        val ok = mapOf("upgrade" to "websocket", "connection" to "keep-alive, Upgrade", "sec-websocket-version" to "13",
            "sec-websocket-key" to "dGhlIHNhbXBsZSBub25jZQ==")
        assertEquals("dGhlIHNhbXBsZSBub25jZQ==", WebSocketStreams.key(HttpRequest("GET", "/", emptyMap(), ok)))
        assertNull(WebSocketStreams.key(HttpRequest("POST", "/", emptyMap(), ok)))
        assertNull(WebSocketStreams.key(HttpRequest("GET", "/", emptyMap(), ok - "upgrade")))
        assertNull(WebSocketStreams.key(HttpRequest("GET", "/", emptyMap(), ok + ("sec-websocket-version" to "8"))))
        assertNull(WebSocketStreams.key(HttpRequest("GET", "/", emptyMap(), ok + ("sec-websocket-key" to "c2hvcnQ="))))
    }

    @Test fun textFramesAndTheirFragmentsReadAsOneStream() {
        val ws = streams(frame(0x1, "{\"id\":1,", fin = false) + frame(0x0, "\"op\":\"state\"}\n") + frame(0x1, "{\"id\":2}\n"))
        assertEquals("{\"id\":1,\"op\":\"state\"}\n{\"id\":2}\n", ws.first.input.readBytes().toString(Charsets.UTF_8))
    }

    @Test fun eachFlushIsOneUnmaskedTextFrame() {
        val (ws, out) = streams(ByteArray(0))
        ws.output.write("{\"id\":1,\"ok\":true}\n".toByteArray())
        ws.output.flush()
        ws.output.flush()
        val frames = frames(out.toByteArray())
        assertEquals(listOf(0x1 to "{\"id\":1,\"ok\":true}\n"), frames)
    }

    @Test fun aPingIsAnsweredAndACloseEndsTheStream() {
        val (ws, out) = streams(frame(0x9, "hi") + frame(0x1, "a\n") + frame(0x8, byteArrayOf(0x03, 0xe8.toByte())) + frame(0x1, "never\n"))
        assertEquals("a\n", ws.input.readBytes().toString(Charsets.UTF_8))
        val frames = frames(out.toByteArray())
        assertEquals(0xA to "hi", frames[0])
        assertEquals(0x8, frames[1].first)
    }

    @Test fun anUnmaskedClientFrameClosesWithAProtocolError() {
        val (ws, out) = streams(frame(0x1, "a\n", masked = false))
        assertEquals(-1, ws.input.read())
        assertArrayEquals(byteArrayOf(0x88.toByte(), 2, 0x03, 0xea.toByte()), out.toByteArray())
    }

    @Test fun anOversizedFrameClosesAsTooBig() {
        val out = ByteArrayOutputStream()
        val ws = WebSocketStreams(ByteArrayInputStream(frame(0x1, "x".repeat(200))), out, maxFrame = 100)
        assertEquals(-1, ws.input.read())
        assertArrayEquals(byteArrayOf(0x88.toByte(), 2, 0x03, 0xf1.toByte()), out.toByteArray())
    }

    @Test fun aLengthWithItsTopBitSetClosesAsTooBig() {
        val head = byteArrayOf(0x81.toByte(), (0x80 or 127).toByte(), 0x80.toByte(), 0, 0, 0, 0x7f, 0xff.toByte(), 0xff.toByte(), 0xff.toByte())
        val (ws, out) = streams(head + byteArrayOf(1, 2, 3, 4))
        assertEquals(-1, ws.input.read())
        assertArrayEquals(byteArrayOf(0x88.toByte(), 2, 0x03, 0xf1.toByte()), out.toByteArray())
    }

    @Test fun abortDoesntWaitForAWriteThatIsStuck() {
        val writing = java.util.concurrent.CountDownLatch(1)
        val released = java.util.concurrent.CountDownLatch(1)
        // A browser that stopped reading: a write blocks until the transport is closed.
        val stuck = object : java.io.OutputStream() {
            override fun write(b: Int) { writing.countDown(); released.await() }
            override fun write(b: ByteArray, off: Int, len: Int) { writing.countDown(); released.await() }
            override fun close() = released.countDown()
        }
        val ws = WebSocketStreams(ByteArrayInputStream(ByteArray(0)), stuck)
        val writer = Thread { ws.output.write("x\n".toByteArray()); runCatching { ws.output.flush() } }.apply { isDaemon = true; start() }
        assertTrue(writing.await(2, java.util.concurrent.TimeUnit.SECONDS))
        val aborter = Thread { ws.abort() }.apply { isDaemon = true; start() }
        aborter.join(2_000)
        assertFalse("abort returned", aborter.isAlive)
        writer.join(2_000)
        assertFalse("the stuck write ended", writer.isAlive)
    }

    private fun streams(input: ByteArray): Pair<WebSocketStreams, ByteArrayOutputStream> {
        val out = ByteArrayOutputStream()
        return WebSocketStreams(ByteArrayInputStream(input), out) to out
    }

    private fun frame(op: Int, text: String, fin: Boolean = true, masked: Boolean = true) =
        frame(op, text.toByteArray(Charsets.UTF_8), fin, masked)

    companion object {
        /** A client's frame: masked, as browsers send them. */
        fun frame(op: Int, payload: ByteArray, fin: Boolean = true, masked: Boolean = true): ByteArray {
            val b = ByteArrayOutputStream()
            b.write((if (fin) 0x80 else 0) or op)
            val m = if (masked) 0x80 else 0
            when {
                payload.size < 126 -> b.write(m or payload.size)
                else -> { b.write(m or 126); b.write(payload.size shr 8); b.write(payload.size and 0xff) }
            }
            if (!masked) { b.write(payload); return b.toByteArray() }
            val mask = byteArrayOf(0x12, 0x34, 0x56, 0x78)
            b.write(mask)
            b.write(ByteArray(payload.size) { (payload[it].toInt() xor mask[it and 3].toInt()).toByte() })
            return b.toByteArray()
        }

        /** The server's frames in [bytes], as (opcode, text). */
        fun frames(bytes: ByteArray): List<Pair<Int, String>> {
            val inp = DataInputStream(ByteArrayInputStream(bytes))
            val out = mutableListOf<Pair<Int, String>>()
            while (inp.available() > 0) {
                val b0 = inp.readUnsignedByte()
                var len = inp.readUnsignedByte()
                if (len == 126) len = inp.readUnsignedShort()
                val p = ByteArray(len).also(inp::readFully)
                out += (b0 and 0x0f) to p.toString(Charsets.UTF_8)
            }
            return out
        }
    }
}
