/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.web

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** One HTTP request. Header names are lowercase. */
class HttpRequest(
    val method: String,
    /** The raw path, without the query. */
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: ByteArray = ByteArray(0),
    /** The client's IP address, for the log. Null in tests that build requests by hand. */
    val remote: String? = null,
) {
    /** The `Cookie` header's pairs. */
    val cookies: Map<String, String> by lazy { HttpServer.parseCookies(headers["cookie"]) }

    /** An `application/x-www-form-urlencoded` body; empty for anything else. */
    val form: Map<String, String> by lazy {
        if (headers["content-type"]?.substringBefore(';')?.trim()?.lowercase() != "application/x-www-form-urlencoded") emptyMap()
        else HttpServer.parseQuery(String(body, Charsets.UTF_8))
    }

    /** The path with the query, as the browser asked for it. */
    val target: String get() = if (query.isEmpty()) path else path + "?" + query.entries.joinToString("&") { (k, v) -> HttpServer.enc(k) + "=" + HttpServer.enc(v) }
}

class HttpResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: ByteArray,
    /** `101 Switching Protocols`: the connection is handed to this once the answer is written ([upgrade]). */
    val upgrade: Upgrade? = null,
    /**
     * Runs once the server is done with this answer: written, failed to write, or replaced before writing. What the
     * body holds on to (a memory budget, say) is released here, not when the handler returns.
     */
    val onDone: (() -> Unit)? = null,
) {
    fun with(extra: Map<String, String>) = HttpResponse(status, headers + extra, body, upgrade, onDone)

    /**
     * What runs on an upgraded connection until it returns; then the connection closes. It runs on the request's
     * worker, or on a thread of its own when the server has [HttpServer]'s `upgradeThreads`. No request deadline any
     * more: a read waits at most [idleMs].
     */
    class Upgrade(val idleMs: Int, val run: (InputStream, OutputStream) -> Unit)

    companion object {
        fun html(status: Int, html: String, extra: Map<String, String> = emptyMap()) =
            HttpResponse(status, mapOf("Content-Type" to "text/html; charset=utf-8", "Cache-Control" to "no-store") + extra,
                html.toByteArray(Charsets.UTF_8))

        fun text(status: Int, text: String) =
            HttpResponse(status, mapOf("Content-Type" to "text/plain; charset=utf-8", "Cache-Control" to "no-store"), text.toByteArray(Charsets.UTF_8))

        /** `303 See Other`: after a form, the browser GETs [location]. */
        fun seeOther(location: String, extra: Map<String, String> = emptyMap()) =
            HttpResponse(303, mapOf("Location" to location, "Cache-Control" to "no-store", "Content-Type" to "text/plain; charset=utf-8") + extra,
                ByteArray(0))

        /** `101 Switching Protocols` to a WebSocket: [run] gets the connection as [WebSocketStreams]. */
        fun webSocket(key: String, idleMs: Int, run: (WebSocketStreams) -> Unit) = HttpResponse(
            101, mapOf("Upgrade" to "websocket", "Connection" to "Upgrade", "Sec-WebSocket-Accept" to WebSocketStreams.accept(key)),
            ByteArray(0), Upgrade(idleMs) { i, o -> run(WebSocketStreams(i, o)) },
        )
    }
}

/**
 * A small HTTP/1.1 server: one request per connection, a few worker threads. Enough for a page on the
 * LAN that a handful of browsers open. Bodies are capped at [maxBody] bytes; a bigger one is refused.
 * Pure JVM, so tests run it on loopback.
 */
class HttpServer(
    private val port: Int,
    private val handler: (HttpRequest) -> HttpResponse,
    /** The address to listen on, or null for every address. Tests pass loopback (#99). */
    private val bindAddress: InetAddress? = null,
    private val log: (String) -> Unit = {},
    private val maxBody: Int = 16 * 1024,
    workers: Int = 4,
    /** Connections that may wait for a worker. Beyond that, a connection gets 503 at once and is closed. */
    queued: Int = 32,
    /**
     * The whole request (line, headers, body) must arrive within this, so a slow client can't hold a worker.
     * It counts from when a worker picks the connection up, not from accept: time in the queue comes first.
     */
    private val requestDeadlineMs: Long = 10_000L,
    /**
     * Upgraded connections (WebSockets) that may run at once on threads of their own, off the worker pool, so
     * long-lived ones never keep plain requests waiting. Past it an upgrade is answered 503. Zero (the default) runs
     * them on the request's worker.
     */
    upgradeThreads: Int = 0,
) {
    private val upgradeSlots: java.util.concurrent.Semaphore? =
        upgradeThreads.takeIf { it > 0 }?.let { java.util.concurrent.Semaphore(it) }
    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false
    private val pool = ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(queued)) { r ->
        Thread(r, "glassworks-http").apply { isDaemon = true }
    }
    /** Turns connections away when [pool] is full, so a slow refused client never holds up `accept()`. */
    private val refusals = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(BUSY_QUEUED)) { r ->
        Thread(r, "glassworks-http-busy").apply { isDaemon = true }
    }
    private val ids = AtomicInteger()

    /** Sees every request before [handler]; null passes it on. Set before [start] (the panel's setup over the LAN, #34). */
    @Volatile var front: ((HttpRequest) -> HttpResponse?)? = null
    /** The thread accepting connections, while the server listens ([stop] waits for it). */
    @Volatile private var acceptThread: Thread? = null

    /** Every connection accepted and not yet closed, also those still waiting for a worker, so [stop] can close them. */
    private val open: MutableSet<Socket> = ConcurrentHashMap.newKeySet()

    val localPort: Int get() = serverSocket?.localPort ?: -1

    @Throws(IOException::class)
    fun start() {
        val ss = ServerSocket()
        ss.reuseAddress = true
        try {
            ss.bind(InetSocketAddress(bindAddress, port))
        } catch (e: IOException) {
            // A port another socket holds: the caller may try another, so this one isn't left open.
            try { ss.close() } catch (_: IOException) {}
            throw e
        }
        serverSocket = ss
        running = true
        acceptThread = Thread({
            while (running) {
                val s = try { ss.accept() } catch (e: IOException) { if (running) log("accept failed: ${e.message}"); break }
                open += s
                try {
                    pool.execute { serve(s) }
                } catch (_: RejectedExecutionException) {
                    busy(s)
                }
            }
        }, "glassworks-http-accept").apply { isDaemon = true; start() }
        log("listening on ${ss.localPort}")
    }

    /**
     * Stops listening and closes every connection. The port is free again when this returns: closing a socket that
     * a thread is blocked accepting on only releases it once that thread leaves `accept()` (the JDK hands the descriptor
     * over to it on Linux), so this waits for the accept thread, up to [STOP_WAIT_MS].
     */
    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: IOException) {}
        serverSocket = null
        acceptThread?.takeIf { it !== Thread.currentThread() }?.let { t ->
            try { t.join(STOP_WAIT_MS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            if (t.isAlive) log("the listener didn't stop within $STOP_WAIT_MS ms")
        }
        acceptThread = null
        // Tasks still in the queue never run: close their connections here, and the running ones' too.
        pool.shutdownNow()
        refusals.shutdownNow()
        for (s in open) try { s.close() } catch (_: IOException) {}
        open.clear()
    }

    private fun serve(s: Socket) {
        val id = ids.incrementAndGet()
        var handedOff = false
        try {
            handedOff = serveOn(s, id)
        } finally {
            if (!handedOff) {
                try { s.close() } catch (_: IOException) {}
                open -= s
            }
        }
    }

    /** Serves one request on [sock]; true when an upgrade thread now owns the connection (and closes it). */
    private fun serveOn(sock: Socket, id: Int): Boolean {
        var slot = false
        try {
            val deadline = DeadlineInput(sock, System.nanoTime() + requestDeadlineMs * 1_000_000)
            val input = BufferedInputStream(deadline)
            val req = try { read(input, maxBody, sock.inetAddress?.hostAddress) } catch (e: TooLarge) {
                write(sock.getOutputStream(), HttpResponse.text(413, "Too large"), head = false); return false
            } catch (e: BadRequest) {
                log("[$id] refused: ${e.message}")
                write(sock.getOutputStream(), HttpResponse.text(400, "Bad request"), head = false); return false
            } ?: return false
            // A header value with a line break would split the answer (response splitting): checked before it is written.
            var res = try { (front?.invoke(req) ?: handler(req)).also(::checkHeaders) } catch (t: Throwable) {
                log("[$id] ${req.method} ${req.path} failed: $t")
                HttpResponse.text(500, "Something went wrong on the panel.")
            }
            val slots = upgradeSlots
            if (res.upgrade != null && slots != null) {
                slot = slots.tryAcquire()
                if (!slot) {
                    res.onDone?.invoke()
                    res = HttpResponse.text(503, "Too many open connections. Try again in a moment.")
                }
            }
            log("[$id] ${req.method} ${req.path} -> ${res.status}")
            try {
                write(sock.getOutputStream(), res, head = req.method == "HEAD")
            } finally {
                res.onDone?.invoke()
            }
            val up = res.upgrade ?: return false
            deadline.idleMs = up.idleMs
            if (slots == null) {
                runUpgrade(up, input, sock, req.path, id)
                return false
            }
            val t = Thread({
                try {
                    runUpgrade(up, input, sock, req.path, id)
                } finally {
                    slots.release()
                    try { sock.close() } catch (_: IOException) {}
                    open -= sock
                }
            }, "glassworks-http-upgrade").apply { isDaemon = true }
            t.start()
            slot = false // the thread releases it now
            return true
        } catch (e: IOException) {
            log("[$id] ${e.message}")
            return false
        } finally {
            if (slot) upgradeSlots?.release()
        }
    }

    private fun runUpgrade(up: HttpResponse.Upgrade, input: InputStream, sock: Socket, path: String, id: Int) {
        try {
            up.run(input, sock.getOutputStream())
        } catch (t: RuntimeException) {
            log("[$id] $path upgraded connection failed: $t")
        } catch (e: IOException) {
            log("[$id] $path upgraded connection ended: ${e.message}")
        }
    }

    /** Reads from [sock] until [deadlineNanos], however the bytes trickle in; then a [SocketTimeoutException]. */
    private class DeadlineInput(private val sock: Socket, private val deadlineNanos: Long) : InputStream() {
        private val inp = sock.getInputStream()

        /** Once set (an upgraded connection), no deadline: each read waits at most this long. */
        @Volatile var idleMs: Int? = null

        private fun arm() {
            idleMs?.let { sock.soTimeout = it; return }
            val left = (deadlineNanos - System.nanoTime()) / 1_000_000
            if (left <= 0) throw SocketTimeoutException("request took too long")
            sock.soTimeout = left.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }

        override fun read(): Int { arm(); return inp.read() }

        override fun read(b: ByteArray, off: Int, len: Int): Int { arm(); return inp.read(b, off, len) }
    }

    /**
     * Every worker is busy and the queue is full: say so and close, without handling the request. Runs on the accept
     * thread, so it must not wait on the client. The 503 is small and the socket new, so the write lands in the send
     * buffer at once. Closing with the request unread would reset the connection before the client reads the 503,
     * so [refusals] reads it away first; when that is full too, the socket closes at once and the client may see
     * only the reset.
     */
    private fun busy(s: Socket) {
        try {
            write(s.getOutputStream(), HttpResponse.text(503, "The panel is busy. Try again in a moment."))
        } catch (_: IOException) {}
        try {
            refusals.execute { drainAndClose(s) }
        } catch (_: RejectedExecutionException) {
            try { s.close() } catch (_: IOException) {}
            open -= s
        }
        log("busy: refused a connection")
    }

    /** Reads the refused client's request for at most [BUSY_DRAIN_MS], however slowly its bytes come, then closes. */
    private fun drainAndClose(s: Socket) {
        try {
            s.use { sock ->
                sock.shutdownOutput()
                val drain = DeadlineInput(sock, System.nanoTime() + BUSY_DRAIN_MS * 1_000_000)
                val buf = ByteArray(4096)
                var total = 0
                while (total < 16 * 1024 && drain.read(buf, 0, buf.size).also { total += it } >= 0) Unit
            }
        } catch (_: IOException) {
        } finally {
            open -= s
        }
    }

    private class TooLarge : IOException("request too large")

    /** A request line or header with a bare CR or LF, or a header line that isn't `name: value`. */
    class BadRequest(why: String) : IOException(why)

    companion object {
        /** How long the refusal thread reads a refused connection's request, in all. */
        private const val BUSY_DRAIN_MS = 300L

        /** How long [stop] waits for the accept thread to leave `accept()` and release the port. */
        private const val STOP_WAIT_MS = 2_000L

        /** Refused connections that may wait for the refusal thread. Beyond that they close without the drain. */
        private const val BUSY_QUEUED = 8

        private val REASONS = mapOf(
            101 to "Switching Protocols", 200 to "OK", 202 to "Accepted", 204 to "No Content", 302 to "Found", 303 to "See Other", 400 to "Bad Request", 401 to "Unauthorized", 403 to "Forbidden",
            404 to "Not Found", 405 to "Method Not Allowed", 409 to "Conflict", 413 to "Payload Too Large",
            422 to "Unprocessable Content", 429 to "Too Many Requests", 500 to "Internal Server Error", 502 to "Bad Gateway", 503 to "Service Unavailable",
        )

        fun reason(status: Int) = REASONS[status] ?: "Status"

        /** Reads one request, or null when the connection closed first. Public for tests. */
        fun read(input: InputStream, maxBody: Int = 16 * 1024, remote: String? = null): HttpRequest? {
            val head = ByteArrayOutputStream()
            var matched = 0
            val end = "\r\n\r\n".toByteArray()
            while (matched < 4) {
                val b = input.read()
                if (b < 0) return null
                head.write(b)
                matched = if (b.toByte() == end[matched]) matched + 1 else if (b.toByte() == end[0]) 1 else 0
                if (head.size() > 16 * 1024) throw TooLarge()
            }
            // The head ends in CRLF CRLF, so the last two pieces are empty. A CR or LF left inside a line is a bare one:
            // other parsers (a proxy, the browser) may split there and read another header than this server does.
            val lines = head.toString("ISO-8859-1").removeSuffix("\r\n\r\n").split("\r\n")
            if (lines.any { l -> l.any { it == '\r' || it == '\n' } }) throw BadRequest("a bare CR or LF in the request head")
            val parts = lines.first().split(' ')
            if (parts.size < 2) return null
            val headers = HashMap<String, String>()
            for (l in lines.drop(1)) {
                val i = l.indexOf(':')
                // An obsolete folded line (starts with a space or tab) or a name with a space: refused (RFC 9112 5.1, 5.2).
                if (l.startsWith(" ") || l.startsWith("\t")) throw BadRequest("a folded header line")
                if (i > 0) {
                    val name = l.substring(0, i)
                    if (name.any { it == ' ' || it == '\t' }) throw BadRequest("whitespace in a header name")
                    headers[name.lowercase()] = l.substring(i + 1).trim()
                }
            }
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            if (length < 0 || length > maxBody) throw TooLarge()
            val body = ByteArray(length)
            var got = 0
            while (got < length) {
                val n = input.read(body, got, length - got)
                if (n < 0) return null
                got += n
            }
            val target = try { URI(parts[1]) } catch (_: Exception) { return HttpRequest(parts[0], "?", emptyMap(), headers, body, remote) }
            return HttpRequest(parts[0], target.rawPath ?: "/", parseQuery(target.rawQuery), headers, body, remote)
        }

        /**
         * Throws when a header of [r] could split the answer: a CR or LF in a name or a value (Set-Cookie's values are
         * joined by LF on purpose, each of them is checked), or a name with a colon. [write] checks too; the server
         * checks before it writes, so such an answer becomes a 500 instead.
         */
        fun checkHeaders(r: HttpResponse) {
            for ((k, v) in r.headers) {
                require(k.isNotEmpty() && k.none { it == '\r' || it == '\n' || it == ':' || it == ' ' }) { "bad response header name" }
                val values = if (k.equals("Set-Cookie", ignoreCase = true)) v.split('\n') else listOf(v)
                for (one in values) require(one.none { it == '\r' || it == '\n' }) { "line break in response header $k" }
            }
        }

        fun write(out: OutputStream, r: HttpResponse, head: Boolean = false) {
            checkHeaders(r)
            val sb = StringBuilder("HTTP/1.1 ${r.status} ${reason(r.status)}\r\n")
            for ((k, v) in r.headers) {
                // Set-Cookie can repeat: the value holds them joined by "\n".
                if (k.equals("Set-Cookie", ignoreCase = true)) v.split('\n').forEach { sb.append(k).append(": ").append(it).append("\r\n") }
                else sb.append(k).append(": ").append(v).append("\r\n")
            }
            // An upgrade's own headers say what happens to the connection.
            if (r.upgrade == null) sb.append("Content-Length: ").append(r.body.size).append("\r\nConnection: close\r\n")
            sb.append("\r\n")
            out.write(sb.toString().toByteArray(Charsets.UTF_8))
            if (!head) out.write(r.body)
            out.flush()
        }

        /** `a=1&b=x%20y`; repeated keys keep the first value. */
        fun parseQuery(query: String?): Map<String, String> {
            if (query.isNullOrEmpty()) return emptyMap()
            val out = LinkedHashMap<String, String>()
            for (part in query.split('&')) {
                if (part.isEmpty()) continue
                val i = part.indexOf('=')
                val k = dec(if (i < 0) part else part.substring(0, i))
                if (k !in out) out[k] = if (i < 0) "" else dec(part.substring(i + 1)) // the first wins (putIfAbsent is API 24)
            }
            return out
        }

        fun parseCookies(header: String?): Map<String, String> {
            if (header.isNullOrEmpty()) return emptyMap()
            val out = LinkedHashMap<String, String>()
            for (part in header.split(';')) {
                val i = part.indexOf('=')
                if (i <= 0) continue
                val k = part.substring(0, i).trim()
                if (k !in out) out[k] = part.substring(i + 1).trim() // the first wins (putIfAbsent is API 24)
            }
            return out
        }

        fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

        private fun dec(s: String): String = try { URLDecoder.decode(s, "UTF-8") } catch (_: Exception) { s }
    }
}
