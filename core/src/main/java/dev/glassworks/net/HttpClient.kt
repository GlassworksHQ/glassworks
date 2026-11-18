/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.net

import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.Socket
import java.net.URI
import java.net.URISyntaxException
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket

/**
 * One HTTP/1.1 request per connection, over a socket from [connector], for the app's own calls to Home Assistant.
 * HttpURLConnection picks the address itself and tries them one at a time, each with the whole connect timeout;
 * this connects as a browser does ([HappyEyeballs]). `https://` uses HttpsURLConnection's default socket factory
 * and hostname verifier, as HttpURLConnection would ([dev.glassworks.platform.HttpsTrust] installs its roots
 * there). No redirects, no compression, no proxy. Pure JVM.
 */
class HttpClient(
    private val connector: HappyEyeballs = HappyEyeballs(),
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 10_000,
) {
    /** [body] holds at most the `maxBytes` the request asked for; [tooLarge] says there was more. */
    class Response(val status: Int, private val headers: Map<String, String>, val body: ByteArray, val tooLarge: Boolean) {
        fun header(name: String): String? = headers[name.lowercase()]
    }

    /**
     * Throws [IOException] when Home Assistant can't be reached or the answer isn't HTTP. Blocking. The body is read
     * only when [bodyIf] accepts the status; else the answer has an empty one and the connection closes.
     */
    @Throws(IOException::class)
    fun request(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        maxBytes: Int = 64 * 1024,
        bodyIf: (status: Int) -> Boolean = { true },
    ): Response {
        val uri = try { URI(url) } catch (e: URISyntaxException) { throw IOException("not a URL: ${e.message}") }
        val tls = when (uri.scheme?.lowercase()) {
            "http" -> false
            "https" -> true
            else -> throw IOException("only http:// and https:// are supported")
        }
        val host = uri.host?.removeSurrounding("[", "]") ?: throw IOException("no host in the URL")
        val port = if (uri.port > 0) uri.port else if (tls) 443 else 80
        val lines = (headers.entries.map { it.key to it.value } + listOfNotNull(body?.let { "Content-Length" to it.size.toString() }))
        if (lines.any { (k, v) -> k.any { it == '\r' || it == '\n' || it == ':' } || v.any { it == '\r' || it == '\n' } }) {
            throw IOException("a header holds a line break")
        }
        val plain = connector.connect(host, port, connectTimeoutMs)
        var socket: Socket = plain
        try {
            if (tls) socket = tls(plain, host, port)
            socket.soTimeout = readTimeoutMs
            val hostHeader = (if (host.contains(':')) "[$host]" else host) + (if (uri.port > 0) ":${uri.port}" else "")
            val target = (uri.rawPath?.ifEmpty { "/" } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
            val head = StringBuilder("$method $target HTTP/1.1\r\nHost: $hostHeader\r\nConnection: close\r\n")
            if (lines.none { it.first.equals("Accept-Encoding", ignoreCase = true) }) head.append("Accept-Encoding: identity\r\n")
            for ((k, v) in lines) head.append(k).append(": ").append(v).append("\r\n")
            head.append("\r\n")
            socket.getOutputStream().apply {
                write(head.toString().toByteArray(Charsets.UTF_8))
                if (body != null) write(body)
                flush()
            }
            return read(BufferedInputStream(socket.getInputStream()), method, maxBytes, bodyIf)
        } finally {
            runCatching { socket.close() }
            runCatching { plain.close() }
        }
    }

    private fun tls(plain: Socket, host: String, port: Int): Socket {
        val ssl = HttpsURLConnection.getDefaultSSLSocketFactory().createSocket(plain, host, port, true) as SSLSocket
        ssl.soTimeout = connectTimeoutMs
        ssl.startHandshake()
        if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.session)) {
            throw SSLPeerUnverifiedException("the certificate doesn't match $host")
        }
        return ssl
    }

    private companion object {
        /** All of an answer's heads together, 1xx ones included; HA's are well under 2 KiB. */
        const val MAX_HEAD = 64 * 1024

        fun read(input: InputStream, method: String, maxBytes: Int, bodyIf: (Int) -> Boolean): Response {
            var status: Int
            var headBytes = 0
            fun next(): String = line(input).also {
                headBytes += it.length + 2
                if (headBytes > MAX_HEAD) throw IOException("the answer's head is too long")
            }
            val headers = HashMap<String, String>()
            do {
                status = next().split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("not an HTTP answer")
                headers.clear()
                while (true) {
                    val l = next()
                    if (l.isEmpty()) break
                    val i = l.indexOf(':')
                    if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
                }
            } while (status in 100..199)
            val noBody = method == "HEAD" || status == 204 || status == 304 || !bodyIf(status)
            val bodyInput = when {
                noBody -> ByteArrayInputStream(ByteArray(0))
                headers["transfer-encoding"]?.lowercase()?.contains("chunked") == true -> Chunked(input)
                else -> headers["content-length"]?.toLongOrNull()?.let { Limited(input, it) } ?: input
            }
            val buf = ByteArrayOutputStream()
            val chunk = ByteArray(16 * 1024)
            var tooLarge = false
            while (true) {
                val n = bodyInput.read(chunk)
                if (n < 0) break
                if (buf.size() + n > maxBytes) { buf.write(chunk, 0, maxBytes - buf.size()); tooLarge = true; break }
                buf.write(chunk, 0, n)
            }
            return Response(status, headers, buf.toByteArray(), tooLarge)
        }

        fun line(input: InputStream): String {
            val sb = StringBuilder()
            while (true) {
                val c = input.read()
                if (c < 0) throw EOFException("the connection closed mid-answer")
                if (c == '\n'.code) return sb.toString()
                if (c != '\r'.code) sb.append(c.toChar())
                if (sb.length > 16 * 1024) throw IOException("a header line is too long")
            }
        }
    }

    /** The first [length] bytes of [input]; ending sooner is an error. */
    private class Limited(private val input: InputStream, private var length: Long) : InputStream() {
        override fun read(): Int = ByteArray(1).let { if (read(it, 0, 1) < 0) -1 else it[0].toInt() and 0xff }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (length <= 0) return -1
            val n = input.read(b, off, minOf(len.toLong(), length).toInt())
            if (n < 0) throw EOFException("the connection closed mid-body")
            length -= n
            return n
        }
    }

    /** A `Transfer-Encoding: chunked` body, decoded. */
    private class Chunked(private val input: InputStream) : InputStream() {
        private var left = 0L
        private var done = false

        override fun read(): Int = ByteArray(1).let { if (read(it, 0, 1) < 0) -1 else it[0].toInt() and 0xff }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (done) return -1
            if (left == 0L) {
                left = line(input).substringBefore(';').trim().toLongOrNull(16)?.takeIf { it >= 0 } ?: throw IOException("bad chunk size")
                if (left == 0L) {
                    while (line(input).isNotEmpty()) Unit
                    done = true
                    return -1
                }
            }
            val n = input.read(b, off, minOf(len.toLong(), left).toInt())
            if (n < 0) throw EOFException("the connection closed mid-chunk")
            left -= n
            if (left == 0L) line(input)
            return n
        }
    }
}
