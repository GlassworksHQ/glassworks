/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.net

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Opens a TCP connection to a host name the way a browser does (Happy Eyeballs, RFC 8305). A name such as
 * `homeassistant.local` can resolve to an IPv6 address the device can't reach, for example Home Assistant's
 * link-local address relayed by an mDNS reflector from another VLAN. Connecting to the first address alone then
 * stalls until the timeout. Here every address gets a try:
 *
 * - Link-local IPv6 addresses without a scope are dropped: no interface can reach them.
 * - The rest alternate between IPv6 and IPv4, starting with the family the resolver put first ([order]).
 * - A new attempt starts every [attemptDelayMs], or at once when one fails. The first to connect wins and the
 *   others are closed.
 * - All of it fits in the caller's timeout.
 *
 * Pure JVM. [resolve] and [connectOne] are the system's unless a test passes fakes.
 */
class HappyEyeballs(
    private val resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    private val attemptDelayMs: Long = ATTEMPT_DELAY_MS,
    private val connectOne: (Socket, InetSocketAddress, Int) -> Unit = { s, a, t -> s.connect(a, t) },
) {
    /**
     * A socket connected to [host]:[port] within [timeoutMs], not counting the name lookup. Blocking. Throws the
     * last attempt's [IOException] when every address failed, or [SocketTimeoutException] at the deadline.
     */
    @Throws(IOException::class)
    fun connect(host: String, port: Int, timeoutMs: Int): Socket {
        if (port !in 0..65535) throw IOException("port $port is out of range")
        val addresses = order(resolve(host.removeSurrounding("[", "]")))
        if (addresses.isEmpty()) throw ConnectException("$host has no address this device can reach")
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        val outcomes = LinkedBlockingQueue<Outcome>()
        val attempts = ArrayList<Socket>(addresses.size)
        var winner: Socket? = null
        var failures = 0
        var nextStart = System.nanoTime()
        try {
            while (true) {
                val now = System.nanoTime()
                if (now >= deadline) throw SocketTimeoutException("connect to $host:$port timed out after $timeoutMs ms")
                if (attempts.size < addresses.size && now >= nextStart) {
                    val leftMs = ((deadline - now) / 1_000_000L).coerceAtLeast(1L).toInt()
                    attempts += start(InetSocketAddress(addresses[attempts.size], port), leftMs, outcomes)
                    nextStart = now + attemptDelayMs * 1_000_000L
                    continue
                }
                val until = if (attempts.size < addresses.size) minOf(nextStart, deadline) else deadline
                val o = try { outcomes.poll(until - now, TimeUnit.NANOSECONDS) } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException("interrupted while connecting to $host:$port")
                } ?: continue
                if (o.error == null) { winner = o.socket; return o.socket }
                if (++failures == addresses.size) throw o.error
                nextStart = System.nanoTime()
            }
        } finally {
            for (s in attempts) if (s !== winner) runCatching { s.close() }
        }
    }

    private class Outcome(val socket: Socket, val error: IOException?)

    private fun start(address: InetSocketAddress, timeoutMs: Int, outcomes: LinkedBlockingQueue<Outcome>): Socket {
        val socket = Socket()
        Thread({
            val error = try {
                connectOne(socket, address, timeoutMs)
                null
            } catch (e: IOException) {
                e
            } catch (e: RuntimeException) {
                IOException(e.message, e)
            }
            outcomes.put(Outcome(socket, error))
        }, "glassworks-connect").apply { isDaemon = true; start() }
        return socket
    }

    companion object {
        /** RFC 8305 section 5's recommended Connection Attempt Delay. */
        const val ATTEMPT_DELAY_MS = 250L

        /**
         * The order to try [addresses] in: without link-local IPv6 addresses that carry no scope, alternating
         * families from the first one's (RFC 8305 section 4, First Address Family Count 1).
         */
        fun order(addresses: List<InetAddress>): List<InetAddress> {
            val usable = addresses.filterNot { it is Inet6Address && it.isLinkLocalAddress && it.scopeId == 0 }.distinctBy { it.hostAddress }
            val firstIs6 = usable.firstOrNull() is Inet6Address
            val (first, other) = usable.partition { (it is Inet6Address) == firstIs6 }
            return (0 until maxOf(first.size, other.size)).flatMap { i -> listOfNotNull(first.getOrNull(i), other.getOrNull(i)) }
        }
    }
}
