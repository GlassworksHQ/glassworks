/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The server takes only so many connections at once; the rest get 503 straight away instead of piling up. */
class HttpServerTest {
    private val loopback = InetAddress.getByName("127.0.0.1")

    private fun open(port: Int): Socket = Socket(loopback, port).apply {
        soTimeout = 3_000
        getOutputStream().write("GET / HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
    }

    @Test fun aFullServerSaysBusy() {
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val server = HttpServer(0, { started.countDown(); release.await(5, TimeUnit.SECONDS); HttpResponse.text(200, "ok") },
            bindAddress = loopback, workers = 1, queued = 1)
        server.start()
        try {
            val busy = open(server.localPort)
            assertTrue(started.await(3, TimeUnit.SECONDS))
            val waiting = open(server.localPort)
            Thread.sleep(200)
            val third = open(server.localPort)
            val answer = third.getInputStream().readBytes().toString(Charsets.UTF_8)
            assertTrue(answer, answer.startsWith("HTTP/1.1 503"))
            release.countDown()
            assertTrue(busy.getInputStream().readBytes().toString(Charsets.UTF_8).startsWith("HTTP/1.1 200"))
            assertTrue(waiting.getInputStream().readBytes().toString(Charsets.UTF_8).startsWith("HTTP/1.1 200"))
            listOf(busy, waiting, third).forEach { it.close() }
        } finally {
            release.countDown()
            server.stop()
        }
    }

    private fun statusLine(sock: Socket): String {
        val sb = StringBuilder()
        val inp = sock.getInputStream()
        while (true) {
            val c = inp.read()
            if (c < 0 || c == '\n'.code) return sb.toString().trim()
            sb.append(c.toChar())
        }
    }

    private fun upgrade(port: Int): Socket = Socket(loopback, port).apply {
        soTimeout = 3_000
        getOutputStream().write(
            ("GET /ws HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n").toByteArray(),
        )
    }

    @Test fun aResponsesOnDoneRunsOnceItIsWritten() {
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        val server = HttpServer(0, { HttpResponse(200, mapOf("Content-Type" to "text/plain"), "hello".toByteArray(), onDone = { done.incrementAndGet() }) },
            bindAddress = loopback, workers = 1)
        server.start()
        try {
            val s = open(server.localPort)
            assertTrue(s.getInputStream().readBytes().toString(Charsets.UTF_8).endsWith("hello"))
            s.close()
            val deadline = System.nanoTime() + 3_000_000_000L
            while (done.get() == 0 && System.nanoTime() < deadline) Thread.sleep(10)
            assertEquals(1, done.get())
        } finally {
            server.stop()
        }
    }

    @Test fun upgradedConnectionsRunOffTheWorkersUpToTheirOwnLimit() {
        val release = CountDownLatch(1)
        val running = CountDownLatch(1)
        val server = HttpServer(0, { req ->
            if (req.path == "/ws") {
                // Held until the test releases it (the finally does too): a slow runner can't free the slot early.
                HttpResponse.webSocket(req.headers["sec-websocket-key"]!!, 5_000) { running.countDown(); release.await() }
            } else {
                HttpResponse.text(200, "ok")
            }
        }, bindAddress = loopback, workers = 1, queued = 1, upgradeThreads = 1)
        server.start()
        try {
            val ws = upgrade(server.localPort)
            assertTrue(statusLine(ws).startsWith("HTTP/1.1 101"))
            assertTrue(running.await(3, TimeUnit.SECONDS))
            // The one worker is free while the socket stays open: a plain request is answered at once.
            val plain = open(server.localPort)
            assertTrue(plain.getInputStream().readBytes().toString(Charsets.UTF_8).startsWith("HTTP/1.1 200"))
            // Past the upgrade limit: refused, not queued behind the open socket.
            val second = upgrade(server.localPort)
            assertTrue(statusLine(second).startsWith("HTTP/1.1 503"))
            release.countDown()
            // The first socket's thread ends and closes it; its slot is free again.
            ws.getInputStream().readBytes() // the rest of the 101 headers, then the close
            Thread.sleep(100)
            val third = upgrade(server.localPort)
            assertTrue(statusLine(third).startsWith("HTTP/1.1 101"))
            listOf(ws, plain, second, third).forEach { it.close() }
        } finally {
            release.countDown()
            server.stop()
        }
    }

    @Test fun aClientThatTricklesItsRequestIsCutOffAndTheWorkerFreed() {
        var handled = 0
        val server = HttpServer(0, { handled++; HttpResponse.text(200, "ok") }, bindAddress = loopback, workers = 1, queued = 4,
            requestDeadlineMs = 600)
        server.start()
        try {
            val slow = Socket(loopback, server.localPort).apply { soTimeout = 5_000 }
            val started = System.nanoTime()
            val out = slow.getOutputStream()
            out.write("GET / HTTP/1.1\r\n".toByteArray())
            // One header byte every 100 ms: each read is quick, the whole head never ends in time.
            val closed = try {
                repeat(30) { out.write('x'.code); out.flush(); Thread.sleep(100) }
                false
            } catch (_: java.io.IOException) { true }
            val tookMs = (System.nanoTime() - started) / 1_000_000
            assertTrue("cut off after $tookMs ms (closed=$closed)", closed || slow.getInputStream().read() == -1)
            assertTrue("within about the deadline, not the old 15 s per read: $tookMs ms", tookMs < 3_500)
            assertTrue("the handler never saw it", handled == 0)
            // The one worker is free again.
            val ok = open(server.localPort).getInputStream().readBytes().toString(Charsets.UTF_8)
            assertTrue(ok, ok.startsWith("HTTP/1.1 200"))
            slow.close()
        } finally {
            server.stop()
        }
    }

    @Test fun aTricklerTurnedAwayDoesntStopTheNextConnectionBeingAnswered() {
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val server = HttpServer(0, { started.countDown(); release.await(10, TimeUnit.SECONDS); HttpResponse.text(200, "ok") },
            bindAddress = loopback, workers = 1, queued = 1)
        server.start()
        var trickler: Socket? = null
        try {
            val busy = open(server.localPort)
            assertTrue(started.await(3, TimeUnit.SECONDS))
            val waiting = open(server.localPort)
            Thread.sleep(200)
            trickler = Socket(loopback, server.localPort)
            // Turned away (503), then sends a byte every 150 ms for 3 s: the old drain read on, on the accept thread.
            val t = Thread {
                runCatching { repeat(20) { trickler!!.getOutputStream().write('x'.code); trickler!!.getOutputStream().flush(); Thread.sleep(150) } }
            }.apply { isDaemon = true; start() }
            Thread.sleep(300)
            val startNs = System.nanoTime()
            val next = open(server.localPort)
            val answer = next.getInputStream().readBytes().toString(Charsets.UTF_8)
            val tookMs = (System.nanoTime() - startNs) / 1_000_000
            assertTrue(answer, answer.startsWith("HTTP/1.1 503"))
            // Only the time counts: the refused trickler's sender may already have stopped on its closed socket.
            assertTrue("answered in $tookMs ms", tookMs < 1_500)
            t.interrupt()
            release.countDown()
            listOf(busy, waiting, next).forEach { it.close() }
        } finally {
            release.countDown()
            trickler?.close()
            server.stop()
        }
    }

    @Test fun aHundredTurnedAwayTricklersDontHoldUpTheNextGoodConnection() {
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val server = HttpServer(0, { if (started.count > 0) { started.countDown(); release.await(10, TimeUnit.SECONDS) }; HttpResponse.text(200, "ok") },
            bindAddress = loopback, workers = 1, queued = 1)
        server.start()
        val tricklers = mutableListOf<Socket>()
        var t: Thread? = null
        try {
            val busy = open(server.localPort)
            assertTrue(started.await(3, TimeUnit.SECONDS))
            val waiting = open(server.localPort)
            Thread.sleep(200)
            val startNs = System.nanoTime()
            repeat(100) { runCatching { Socket(loopback, server.localPort) }.getOrNull()?.let(tricklers::add) }
            // Each sends a byte every 100 ms, so a drain on the accept thread would read on for its whole 300 ms.
            t = Thread {
                while (true) {
                    for (s in tricklers) runCatching { s.getOutputStream().write('x'.code); s.getOutputStream().flush() }
                    try { Thread.sleep(100) } catch (_: InterruptedException) { return@Thread }
                }
            }.apply { isDaemon = true; start() }
            val deadlineNs = startNs + 1_500_000_000L
            val turnedAway = tricklers.count { s ->
                val left = ((deadlineNs - System.nanoTime()) / 1_000_000).toInt()
                left > 0 && try { s.soTimeout = left; s.getInputStream().read(); true }
                    catch (_: java.net.SocketTimeoutException) { false }
                    catch (_: java.io.IOException) { true } // reset: refused without the drain
            }
            assertTrue("$turnedAway of ${tricklers.size} (of 100 opened) turned away within 1.5 s", tricklers.size == 100 && turnedAway == 100)
            release.countDown()
            assertTrue(busy.getInputStream().readBytes().toString(Charsets.UTF_8).startsWith("HTTP/1.1 200"))
            assertTrue(waiting.getInputStream().readBytes().toString(Charsets.UTF_8).startsWith("HTTP/1.1 200"))
            val goodNs = System.nanoTime()
            val answer = open(server.localPort).use { it.getInputStream().readBytes().toString(Charsets.UTF_8) }
            val tookMs = (System.nanoTime() - goodNs) / 1_000_000
            assertTrue("the good connection got: $answer", answer.startsWith("HTTP/1.1 200"))
            assertTrue("answered in $tookMs ms", tookMs < 1_000)
            listOf(busy, waiting).forEach { it.close() }
        } finally {
            t?.interrupt()
            release.countDown()
            tricklers.forEach { runCatching { it.close() } }
            server.stop()
        }
    }

    @Test fun stopClosesConnectionsStillWaitingForAWorker() {
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val server = HttpServer(0, { started.countDown(); release.await(10, TimeUnit.SECONDS); HttpResponse.text(200, "ok") },
            bindAddress = loopback, workers = 1, queued = 4)
        server.start()
        try {
            val busy = open(server.localPort)
            assertTrue(started.await(3, TimeUnit.SECONDS))
            val waiting = Socket(loopback, server.localPort).apply { soTimeout = 3_000 }
            Thread.sleep(200) // accepted and queued behind the busy one
            server.stop()
            val gone = try { waiting.getInputStream().read() == -1 } catch (_: java.net.SocketTimeoutException) { false } catch (_: java.io.IOException) { true }
            assertTrue("the queued connection was closed, not left waiting", gone)
            busy.close(); waiting.close()
        } finally {
            release.countDown()
            server.stop()
        }
    }

    /**
     * The port is free once stop() returns, so the same port can be bound again at once (an island server keeps its
     * port between starts, #881). Linux used to keep the listening socket until the accept thread left accept().
     */
    @Test fun stopReleasesThePortBeforeItReturns() {
        repeat(50) {
            val s = HttpServer(0, { HttpResponse.text(200, "ok") }, bindAddress = loopback)
            s.start()
            val port = s.localPort
            s.stop()
            java.net.ServerSocket().use { again ->
                again.reuseAddress = true
                again.bind(java.net.InetSocketAddress(loopback, port))
            }
        }
    }
}
