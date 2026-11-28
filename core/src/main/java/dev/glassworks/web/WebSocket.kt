/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.web

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64

/**
 * The server side of a WebSocket (RFC 6455) as a byte stream, so a line protocol written for a socket
 * runs over it unchanged: [input] gives the payload bytes of the client's data frames in order (text or
 * binary, fragments joined), and [output] sends what was written since the last flush as one text frame.
 * Pings are answered; a close ends [input] after closing back. Frames bigger than [maxFrame] close the
 * connection (1009). Client frames must be masked, as the RFC says. Pure JVM.
 */
class WebSocketStreams(private val raw: InputStream, private val rawOut: OutputStream, private val maxFrame: Int = 64 * 1024) {
    /** Writes come from the protocol's thread and from [input]'s reader (pongs, the close). */
    private val lock = Any()

    @Volatile private var closed = false

    val input: InputStream = object : InputStream() {
        private var payload = ByteArray(0)
        private var pos = 0

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (pos >= payload.size) {
                payload = nextData() ?: return -1
                pos = 0
            }
            val n = minOf(len, payload.size - pos)
            System.arraycopy(payload, pos, b, off, n)
            pos += n
            return n
        }
    }

    val output: OutputStream = object : OutputStream() {
        private val buf = ByteArrayOutputStream()

        override fun write(b: Int) = synchronized(buf) { buf.write(b) }

        override fun write(b: ByteArray, off: Int, len: Int) = synchronized(buf) { buf.write(b, off, len) }

        override fun flush() {
            val bytes = synchronized(buf) { buf.toByteArray().also { buf.reset() } }
            if (bytes.isNotEmpty()) send(OP_TEXT, bytes)
        }
    }

    /** Closes with [code] (1000 normal, 1013 try again later) and ends [input]. */
    fun close(code: Int = 1000) {
        if (closed) return
        closed = true
        runCatching { send(OP_CLOSE, byteArrayOf((code shr 8).toByte(), code.toByte())) }
    }

    /**
     * Sends a ping. The browser answers it with a pong on its own network thread, without waking the page's script,
     * and the pong is a read: a server whose reads have a deadline keeps a quiet client's connection open with it.
     */
    fun ping() {
        if (closed) return
        send(OP_PING, ByteArray(0))
    }

    /**
     * Drops the connection from another thread, with no close frame: a read or a write blocked on the socket ends at
     * once. It never takes the write lock, which a write to a browser that stopped reading can hold.
     */
    fun abort() {
        closed = true
        runCatching { rawOut.close() }
        runCatching { raw.close() }
    }

    /** The next data frame's payload, answering control frames on the way; null once the connection is over. */
    private fun nextData(): ByteArray? {
        while (!closed) {
            val f = try { readFrame() } catch (_: EOFException) { closed = true; return null } ?: return null
            when (f.op) {
                OP_CONT, OP_TEXT, OP_BINARY -> if (f.payload.isNotEmpty()) return f.payload
                OP_PING -> send(OP_PONG, f.payload)
                OP_PONG -> Unit
                OP_CLOSE -> { close(); return null }
                else -> { close(1002); return null }
            }
        }
        return null
    }

    private class Frame(val op: Int, val payload: ByteArray)

    private fun readFrame(): Frame? {
        val b0 = byte()
        val b1 = byte()
        val op = b0 and 0x0f
        val control = op >= 0x8
        val masked = b1 and 0x80 != 0
        var len = (b1 and 0x7f).toLong()
        if (len == 126L) len = (byte().toLong() shl 8) or byte().toLong()
        else if (len == 127L) { len = 0; repeat(8) { len = (len shl 8) or byte().toLong() } }
        // Reserved bits without an extension, an unmasked client, or a fragmented or long control frame.
        if (b0 and 0x70 != 0 || !masked || (control && (b0 and 0x80 == 0 || len > 125))) { close(1002); return null }
        // A 64-bit length with its top bit set (the RFC forbids it) reads as negative.
        if (len < 0 || len > maxFrame) { close(1009); return null }
        val mask = ByteArray(4).also(::fill)
        val payload = ByteArray(len.toInt()).also(::fill)
        for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
        return Frame(op, payload)
    }

    private fun byte(): Int = raw.read().also { if (it < 0) throw EOFException() }

    private fun fill(b: ByteArray) {
        var got = 0
        while (got < b.size) {
            val n = raw.read(b, got, b.size - got)
            if (n < 0) throw EOFException()
            got += n
        }
    }

    private fun send(op: Int, payload: ByteArray) = synchronized(lock) {
        val head = ByteArrayOutputStream()
        head.write(0x80 or op)
        when {
            payload.size < 126 -> head.write(payload.size)
            payload.size <= 0xffff -> { head.write(126); head.write(payload.size shr 8); head.write(payload.size and 0xff) }
            else -> { head.write(127); for (s in 56 downTo 0 step 8) head.write(((payload.size.toLong() shr s) and 0xff).toInt()) }
        }
        rawOut.write(head.toByteArray())
        rawOut.write(payload)
        rawOut.flush()
    }

    companion object {
        const val OP_CONT = 0x0
        const val OP_TEXT = 0x1
        const val OP_BINARY = 0x2
        const val OP_CLOSE = 0x8
        const val OP_PING = 0x9
        const val OP_PONG = 0xA

        private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

        /** `Sec-WebSocket-Accept` for the client's `Sec-WebSocket-Key`. */
        fun accept(key: String): String =
            Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key.trim() + GUID).toByteArray(Charsets.US_ASCII)))

        /**
         * A WebSocket handshake [req] asks for, by its key, or null when it isn't one: a GET with `Upgrade:
         * websocket`, `Connection: upgrade`, version 13 and a 16-byte key.
         */
        fun key(req: HttpRequest): String? {
            if (req.method != "GET") return null
            if (!req.headers["upgrade"].orEmpty().equals("websocket", ignoreCase = true)) return null
            if (req.headers["connection"].orEmpty().split(',').none { it.trim().equals("upgrade", ignoreCase = true) }) return null
            if (req.headers["sec-websocket-version"]?.trim() != "13") return null
            val key = req.headers["sec-websocket-key"]?.trim() ?: return null
            val decoded = runCatching { Base64.getDecoder().decode(key) }.getOrNull() ?: return null
            return key.takeIf { decoded.size == 16 }
        }
    }
}
