/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.testing

import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException

/**
 * The network a Pixel Tablet met on 2026-11-18: the panel sits on a VLAN without IPv6, and an mDNS reflector relays
 * Home Assistant's link-local IPv6 address from another VLAN. Its name resolves to that address, which the panel
 * can't reach, ahead of its IPv4 address. Here [HOST] resolves to the link-local address without a scope, the same
 * with a scope, then 127.0.0.1. A connect to either IPv6 address hangs until the socket is closed, as a SYN that
 * nobody answers does, so a client that tries only the first address, or one at a time, stalls.
 *
 * Hand [resolve] and [connectOne] to `dev.glassworks.net.HappyEyeballs`.
 */
object UnreachableIpv6 {
    /** `.test` never resolves for real (RFC 6761), so a client that resolves the name itself fails. */
    const val HOST = "homeassistant.test"

    private val LINK_LOCAL = byteArrayOf(0xfe.toByte(), 0x80.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)

    val resolve: (String) -> List<InetAddress> = { name ->
        check(name == HOST) { "only $HOST resolves here, not $name" }
        listOf(
            Inet6Address.getByAddress(null, LINK_LOCAL, 0),
            Inet6Address.getByAddress(null, LINK_LOCAL, 1),
            InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)),
        )
    }

    val connectOne: (Socket, InetSocketAddress, Int) -> Unit = { socket, address, timeoutMs ->
        if (address.address is Inet6Address) {
            while (!socket.isClosed) Thread.sleep(5)
            throw SocketException("closed while connecting to ${address.address}")
        }
        socket.connect(address, timeoutMs)
    }

    /** A client on this network must answer well inside this, not after its connect timeout. */
    const val FAST_MS = 1_500L
}
