/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.net

import dev.glassworks.testing.UnreachableIpv6
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.ConnectException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList

class HappyEyeballsTest {
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))

    @After fun close() = server.close()

    private fun v6(last: Int, scope: Int) =
        Inet6Address.getByAddress(null, ByteArray(16).also { it[0] = 0xfe.toByte(); it[1] = 0x80.toByte(); it[15] = last.toByte() }, scope)

    private fun v4(last: Int) = InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, last.toByte()))

    private fun elapsedMs(block: () -> Unit): Long {
        val t0 = System.nanoTime()
        block()
        return (System.nanoTime() - t0) / 1_000_000
    }

    @Test fun `order drops unscoped link-local IPv6 and alternates families from the first`() {
        val order = HappyEyeballs.order(listOf(v6(1, 0), v6(2, 3), v6(3, 3), v4(1), v4(2), v4(3), v4(1)))
        assertEquals(listOf(v6(2, 3), v4(1), v6(3, 3), v4(2), v4(3)), order)
        assertEquals(listOf(v4(1), v6(2, 3)), HappyEyeballs.order(listOf(v4(1), v6(2, 3))))
        assertEquals(emptyList<InetAddress>(), HappyEyeballs.order(listOf(v6(1, 0))))
        assertEquals("a scoped twin of a dropped address stays", listOf(v6(1, 2)), HappyEyeballs.order(listOf(v6(1, 0), v6(1, 2))))
    }

    @Test fun `an IPv6 address that never answers doesn't hold up IPv4`() {
        val he = HappyEyeballs(UnreachableIpv6.resolve, connectOne = UnreachableIpv6.connectOne)
        var socket: Socket? = null
        val ms = elapsedMs { socket = he.connect(UnreachableIpv6.HOST, server.localPort, 8_000) }
        socket!!.use { assertEquals(InetAddress.getByName("127.0.0.1"), it.inetAddress) }
        assertTrue("connected in $ms ms, after the IPv6 attempt's head start", ms in HappyEyeballs.ATTEMPT_DELAY_MS..UnreachableIpv6.FAST_MS)
    }

    @Test fun `the losing attempts are closed`() {
        val tried = CopyOnWriteArrayList<Socket>()
        val he = HappyEyeballs({ listOf(v6(2, 1), InetAddress.getByName("127.0.0.1")) }) { s, a, t ->
            tried += s
            UnreachableIpv6.connectOne(s, a, t)
        }
        he.connect("ha", server.localPort, 8_000).use { winner ->
            assertEquals(2, tried.size)
            assertTrue("the IPv6 attempt is closed", tried.single { it !== winner }.isClosed)
            assertFalse(winner.isClosed)
        }
    }

    @Test fun `a failed attempt starts the next one at once`() {
        val he = HappyEyeballs({ listOf(v6(2, 1), InetAddress.getByName("127.0.0.1")) }, attemptDelayMs = 5_000) { s, a, t ->
            if (a.address is Inet6Address) throw SocketException("unreachable") else s.connect(a, t)
        }
        val ms = elapsedMs { he.connect("ha", server.localPort, 8_000).close() }
        assertTrue("connected in $ms ms", ms < UnreachableIpv6.FAST_MS)
    }

    @Test fun `every address failing throws the last error before the timeout`() {
        val he = HappyEyeballs({ listOf(v6(2, 1), v4(1)) }) { _, a, _ -> throw ConnectException("refused by ${a.address}") }
        val ms = elapsedMs {
            try { he.connect("ha", 80, 8_000); fail() } catch (e: ConnectException) { assertTrue(e.message!!, e.message!!.contains("192.0.2.1")) }
        }
        assertTrue("failed in $ms ms", ms < UnreachableIpv6.FAST_MS)
    }

    @Test fun `addresses that never answer time out at the caller's timeout`() {
        val he = HappyEyeballs({ listOf(v6(2, 1)) }, connectOne = UnreachableIpv6.connectOne)
        val ms = elapsedMs { try { he.connect("ha", 80, 400); fail() } catch (_: SocketTimeoutException) {} }
        assertTrue("timed out after $ms ms", ms in 380..UnreachableIpv6.FAST_MS)
    }

    @Test fun `only unreachable link-local addresses is a fast failure`() {
        val he = HappyEyeballs({ listOf(v6(1, 0)) })
        try { he.connect("ha", 80, 8_000); fail() } catch (e: ConnectException) { assertTrue(e.message!!.contains("no address")) }
    }

    @Test fun `an IP literal connects, and a bracketed IPv6 one loses its brackets`() {
        HappyEyeballs().connect("127.0.0.1", server.localPort, 2_000).close()
        val asked = CopyOnWriteArrayList<String>()
        HappyEyeballs({ asked += it; listOf(InetAddress.getByName("127.0.0.1")) }).connect("[::1]", server.localPort, 2_000).close()
        assertEquals(listOf("::1"), asked)
    }
}
