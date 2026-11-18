/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.net

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket

/** [HttpClient] against a server that answers each connection with fixed bytes, whatever was asked. */
class HttpClientTest {
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val url = "http://127.0.0.1:${server.localPort}/manifest.json"
    private val http = HttpClient(HappyEyeballs(), 2_000, 2_000)

    @After fun close() = server.close()

    private fun answer(raw: String) {
        Thread {
            server.accept().use { s ->
                val input = s.getInputStream()
                var tail = 0
                while (tail != 0x0d0a0d0a) tail = (tail shl 8) or input.read()
                s.getOutputStream().apply { write(raw.toByteArray(Charsets.ISO_8859_1)); flush() }
            }
        }.apply { isDaemon = true; start() }
    }

    @Test fun `an interim answer is skipped for the final one`() {
        answer("HTTP/1.1 103 Early Hints\r\nLink: </app.js>; rel=preload\r\n\r\nHTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok")
        val r = http.request("GET", url)
        assertEquals(200, r.status)
        assertEquals("ok", String(r.body))
        assertFalse(r.tooLarge)
    }

    @Test fun `a negative chunk size is an IOException`() {
        answer("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n-1\r\nx\r\n0\r\n\r\n")
        try { http.request("GET", url); fail() } catch (_: IOException) {}
    }

    @Test fun `a status whose body isn't wanted is returned without reading it`() {
        answer("HTTP/1.1 401 Unauthorized\r\nContent-Length: 100\r\n\r\n")
        val r = http.request("GET", url, bodyIf = { it == 200 })
        assertEquals(401, r.status)
        assertEquals(0, r.body.size)
    }

    @Test fun `a body cut short is an IOException when it is wanted`() {
        answer("HTTP/1.1 401 Unauthorized\r\nContent-Length: 100\r\n\r\n")
        try { http.request("GET", url); fail() } catch (_: IOException) {}
    }

    @Test fun `a port out of range is an IOException`() {
        try { http.request("GET", "http://127.0.0.1:65536/auth/token"); fail() } catch (_: IOException) {}
    }

    @Test fun `a head without end is an IOException, not a heap full of headers`() {
        answer("HTTP/1.1 200 OK\r\n" + (0 until 20).joinToString("") { "X-Filler-$it: ${"a".repeat(8 * 1024)}\r\n" } + "\r\n")
        try { http.request("GET", url); fail() } catch (_: IOException) {}
    }

    @Test fun `it asks for no compression, unless the caller does`() {
        assertEquals("identity", requested { http.request("GET", url) }["accept-encoding"])
        assertEquals("gzip", requested { http.request("GET", url, mapOf("Accept-Encoding" to "gzip")) }["accept-encoding"])
    }

    /** The request's headers, lowercase, as the server read them. */
    private fun requested(call: () -> Unit): Map<String, String> {
        val seen = java.util.concurrent.ArrayBlockingQueue<Map<String, String>>(1)
        Thread {
            server.accept().use { s ->
                val input = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val headers = generateSequence { input.readLine() }.drop(1).takeWhile { it.isNotEmpty() }
                    .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
                s.getOutputStream().apply { write("HTTP/1.1 204 No Content\r\n\r\n".toByteArray()); flush() }
                seen.put(headers)
            }
        }.apply { isDaemon = true; start() }
        call()
        return seen.poll(2, java.util.concurrent.TimeUnit.SECONDS)!!
    }
}
