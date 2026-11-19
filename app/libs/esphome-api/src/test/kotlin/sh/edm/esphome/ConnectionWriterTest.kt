package sh.edm.esphome

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FilterOutputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Each connection's writer and queue (#213, backported to 0.4.4 without the camera and actions 0.4.3
 * doesn't have): a client that stops reading (a stuck Home Assistant, a half-open connection) holds up
 * only itself. Its socket is one whose writes the test stops, as if every buffer on the way were full.
 */
class ConnectionWriterTest {
    private val identity = DeviceIdentity(
        name = "test-panel", friendlyName = "Test Panel", macAddress = "02:00:00:00:00:01",
        esphomeVersion = "2026.10.2", compilationTime = "test", manufacturer = "Test", model = "JVM",
        voiceFeatures = 0,
    )
    private val sockets = CopyOnWriteArrayList<StoppableSocket>()
    private val servers = ArrayList<ApiServer>()
    /** Runs server calls that blocked on a stuck socket before #213, so such a hang fails a read instead of the test. */
    private val driver = Executors.newSingleThreadExecutor { r -> Thread(r, "test-driver").apply { isDaemon = true } }

    @After fun stop() {
        sockets.forEach { it.resume() }
        servers.forEach { it.stop() }
        driver.shutdownNow()
    }

    private val level = AtomicInteger()
    private val sensor = SensorEntity("level", "Level") { level.get().toFloat() }

    private fun server(vararg entities: ApiEntity, keys: NoiseKeys? = null) =
        ApiServer(0, { identity }, listOf(sensor) + entities, keys = keys).apply {
            // The stuck client is never dropped by the keepalive during a test.
            keepaliveMs = 600_000; unresponsiveMs = 1_200_000
            newServerSocket = {
                object : ServerSocket() {
                    override fun accept(): Socket = StoppableSocket().also { implAccept(it); sockets += it }
                }
            }
            start()
        }.also { servers += it }

    /** A subscribed client that has read its first states and then stops reading; returns its socket on the server. */
    private fun stuckClient(s: ApiServer): Pair<PlainClient, StoppableSocket> {
        val c = PlainClient(s.localPort)
        c.send(Msg.SUBSCRIBE_STATES_REQUEST)
        c.roundTrip()
        val sock = sockets.last()
        sock.stop()
        return c to sock
    }

    private fun awaitBlocked(sock: StoppableSocket) =
        assertTrue("the stuck client's socket never blocked a write\n${serverThreads()}", sock.blocked.await(10, TimeUnit.SECONDS))

    /** Waits until no thread named [name] runs: each one waits on a lock or has ended. */
    private fun awaitIdle(name: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (Thread.getAllStackTraces().keys.any { it.name == name && it.state in setOf(Thread.State.RUNNABLE, Thread.State.BLOCKED) }) {
            assertTrue("$name never stopped running\n${serverThreads()}", System.nanoTime() < deadline)
            Thread.yield()
        }
    }

    @Test
    fun aClientThatStopsReadingDelaysNothingForTheOthers() {
        val s = server()
        val (stuck, stuckSock) = stuckClient(s)
        stuck.use {
            level.set(-1); s.publishStates()
            awaitBlocked(stuckSock)
            PlainClient(s.localPort).use { c ->
                c.send(Msg.SUBSCRIBE_STATES_REQUEST)
                c.await(Msg.SENSOR_STATE)
                c.roundTrip()
                for (round in 1..20) {
                    level.set(round)
                    driver.execute { s.publishStates() }
                    assertEquals("state $round", round.toFloat(), c.await(Msg.SENSOR_STATE).float(2))
                    c.roundTrip()
                }
                assertEquals("the stuck client is still connected, not dropped", 2, s.connectionCount)
            }
        }
    }

    @Test
    fun statesForAClientThatStopsReadingWaitAsTheNewestValue() {
        val s = server()
        val (stuck, stuckSock) = stuckClient(s)
        stuck.use {
            level.set(1); s.publishStates()
            awaitBlocked(stuckSock)
            val last = ApiServer.MAX_QUEUED_BYTES / 4
            for (v in 2..last) { level.set(v); s.publishStates() }
            assertEquals("dropped a client whose only waiting messages were states", 1, s.connectionCount)

            stuckSock.resume()
            val seen = ArrayList<Float>()
            while (seen.lastOrNull() != last.toFloat()) seen += stuck.await(Msg.SENSOR_STATE).float(2)
            assertEquals("states after the client stopped reading", listOf(1f, last.toFloat()), seen)
            assertTrue(stuck.roundTrip().isEmpty())
        }
    }

    @Test
    fun aClientThatStopsReadingIsDroppedOnceItsQueueIsFull() {
        val bell = EventEntity("bell", "Bell", eventTypes = listOf("press"))
        val s = server(bell)
        val (stuck, stuckSock) = stuckClient(s)
        stuck.use {
            s.sendEvent(bell, "press")
            awaitBlocked(stuckSock)
            // Each event is more than 16 bytes in the queue, so this many can't all fit.
            val limit = ApiServer.MAX_QUEUED_BYTES / 16
            var sent = 1
            while (sent < limit && s.connectionCount > 0) { s.sendEvent(bell, "press"); sent++ }
            assertEquals("still connected after $sent events nobody read", 0, s.connectionCount)
            assertTrue("dropped after $sent events: before the queue was full", sent > ApiServer.MAX_QUEUED_BYTES / 64)

            PlainClient(s.localPort).use { c ->
                c.send(Msg.SUBSCRIBE_STATES_REQUEST)
                c.await(Msg.SENSOR_STATE)
            }
        }
    }

    @Test
    fun aDisconnectRequestIsAnsweredBeforeTheSocketCloses() {
        val s = server()
        val (c, sock) = stuckClient(s)
        c.use {
            c.send(Msg.DISCONNECT_REQUEST)
            awaitBlocked(sock)
            awaitIdle("esphome-conn-${sockets.size}")
            sock.resume()
            assertEquals(Msg.DISCONNECT_RESPONSE, c.read()?.type)
            assertTrue("the socket is closed after the answer", c.closedByServer())
        }
    }

    /**
     * Activating a new key closes every connection and drops what still waits for it, so the answer to
     * the request that set the key must be written first. While its client doesn't read, the key waits.
     */
    @Test
    fun aKeyActivatesOnlyOnceItsAnswerIsWritten() {
        val activated = CountDownLatch(1)
        val s = server(keys = NoiseKeys(MemoryKeyStore())).apply { onKeyActivated = { activated.countDown() } }
        val (c, sock) = stuckClient(s)
        c.use {
            c.send(Msg.NOISE_ENCRYPTION_SET_KEY_REQUEST, ProtoWriter().string(1, java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 1 })))
            awaitBlocked(sock)
            // Long enough for activation (100 ms) and the retired connection's close (500 ms) without the fix.
            assertFalse("the key activated while its answer was still waiting", activated.await(1_500, TimeUnit.MILLISECONDS))
            sock.resume()
            assertTrue("the key answer", c.await(Msg.NOISE_ENCRYPTION_SET_KEY_RESPONSE).bool(1))
            assertTrue("the key never activated\n${serverThreads()}", activated.await(10, TimeUnit.SECONDS))
        }
    }

    /** Writes go through until [stop]; after it they block until [resume]. */
    private class StoppableSocket : Socket() {
        @Volatile private var stopped = false
        private val resumed = CountDownLatch(1)
        val blocked = CountDownLatch(1)

        fun stop() { stopped = true }
        fun resume() { stopped = false; resumed.countDown() }

        private val out by lazy {
            object : FilterOutputStream(super.getOutputStream()) {
                override fun write(b: ByteArray, off: Int, len: Int) {
                    if (stopped) { blocked.countDown(); resumed.await() }
                    out.write(b, off, len)
                }
            }
        }
        override fun getOutputStream(): OutputStream = out
    }
}
