/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.Socket

/**
 * Line breaks the server doesn't split on are refused: a bare CR or LF in the request head (another parser on the way may
 * read another header there), and a CR or LF in an answer's header (response splitting).
 */
class HttpLineBreaksTest {
    private fun read(head: String) = HttpServer.read(ByteArrayInputStream(head.toByteArray(Charsets.ISO_8859_1)))

    @Test fun aCleanRequestStillReads() {
        val r = read("GET /signin?a=1 HTTP/1.1\r\nHost: 192.0.2.40:8099\r\nX-Empty:\r\n\r\n")
        assertNotNull(r)
        r!!
        assertEquals("/signin", r.path)
        assertEquals("192.0.2.40:8099", r.headers["host"])
        assertEquals("", r.headers["x-empty"])
    }

    @Test fun aBareCrOrLfInTheRequestHeadIsABadRequest() {
        for (head in listOf(
            "GET / HTTP/1.1\nHost: 192.0.2.40\r\n\r\n", // bare LF ending the request line
            "GET /\r HTTP/1.1\r\nHost: 192.0.2.40\r\n\r\n", // bare CR in the request line
            "GET / HTTP/1.1\r\nHost: 192.0.2.40\nX-Injected: 1\r\n\r\n", // bare LF in a value
            "GET / HTTP/1.1\r\nHost: 192.0.2.40\rX-Injected: 1\r\n\r\n", // bare CR in a value
            "GET / HTTP/1.1\r\nX\nInjected: 1\r\nHost: 192.0.2.40\r\n\r\n", // bare LF in a name
            "GET / HTTP/1.1\r\nHost: 192.0.2.40\r\n folded\r\n\r\n", // an obsolete folded line
            "GET / HTTP/1.1\r\nHost : 192.0.2.40\r\n\r\n", // whitespace before the colon
        )) {
            assertThrows(head, HttpServer.BadRequest::class.java) { read(head) }
        }
    }

    @Test fun anAnswerHeaderWithALineBreakIsNeverWritten() {
        for (v in listOf("ok\r\nSet-Cookie: x=1", "ok\nX: 1", "ok\rX: 1")) {
            val r = HttpResponse(200, mapOf("X-Test" to v), ByteArray(0))
            assertThrows(v, IllegalArgumentException::class.java) { HttpServer.write(ByteArrayOutputStream(), r) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            HttpServer.write(ByteArrayOutputStream(), HttpResponse(200, mapOf("X\r\nY" to "1"), ByteArray(0)))
        }
        // Set-Cookie's values are joined by LF on purpose; a CR inside one of them is still refused.
        val out = ByteArrayOutputStream()
        HttpServer.write(out, HttpResponse(200, mapOf("Set-Cookie" to "a=1; Path=/\nb=2; Path=/"), ByteArray(0)))
        val text = out.toString("UTF-8")
        assertTrue(text, text.contains("Set-Cookie: a=1; Path=/\r\nSet-Cookie: b=2; Path=/\r\n"))
        assertThrows(IllegalArgumentException::class.java) {
            HttpServer.write(ByteArrayOutputStream(), HttpResponse(200, mapOf("Set-Cookie" to "a=1\r\nX: 1"), ByteArray(0)))
        }
    }

    @Test fun theServerAnswers400ToABareLfAnd500ToAnAnswerThatWouldSplit() {
        val loopback = InetAddress.getByName("127.0.0.1")
        var handled = 0
        val server = HttpServer(0, { req ->
            handled++
            HttpResponse(200, mapOf("Location" to (req.query["to"] ?: "/")), ByteArray(0))
        }, bindAddress = loopback, workers = 1)
        server.start()
        fun ask(head: String): String = Socket(loopback, server.localPort).use { s ->
            s.soTimeout = 3_000
            s.getOutputStream().write(head.toByteArray(Charsets.ISO_8859_1))
            s.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
        }
        try {
            val bad = ask("GET / HTTP/1.1\r\nHost: 192.0.2.40\nX-Injected: 1\r\n\r\n")
            assertTrue(bad, bad.startsWith("HTTP/1.1 400"))
            assertEquals("the handler never saw it", 0, handled)
            // A handler that echoes a decoded query value into a header: the CRLF never reaches the wire.
            val split = ask("GET /?to=%2F%0D%0ASet-Cookie:%20x%3D1 HTTP/1.1\r\nHost: 192.0.2.40\r\n\r\n")
            assertTrue(split, split.startsWith("HTTP/1.1 500"))
            assertFalse(split, split.contains("Set-Cookie"))
            assertTrue(ask("GET /?to=%2Fok HTTP/1.1\r\nHost: 192.0.2.40\r\n\r\n").contains("Location: /ok\r\n"))
        } finally {
            server.stop()
        }
    }
}
