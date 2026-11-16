/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.setup

import dev.glassworks.web.HttpResponse
import dev.glassworks.web.HttpServer
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface

/** Where the portal answers: the hotspot's IPv4 address, and its IPv6 one when it has one. */
data class PortalAddresses(val v4: Inet4Address, val v6: Inet6Address? = null) {
    override fun toString() = "PortalAddresses" // no address in a log

    companion object {
        /**
         * [v4] (the hotspot's, [dev.glassworks.platform.HotspotAddress]) and an IPv6 address on the same interface that
         * isn't link-local: the one Android's RA names as the hotspot's DNS server. [interfaces]: each interface's addresses.
         */
        fun pick(v4: String?, interfaces: List<List<InetAddress>>): PortalAddresses? {
            val own = interfaces.firstOrNull { a -> a.any { it is Inet4Address && it.hostAddress == v4 } } ?: return null
            val a4 = own.first { it is Inet4Address && it.hostAddress == v4 } as Inet4Address
            val a6 = own.filterIsInstance<Inet6Address>().firstOrNull { !it.isLinkLocalAddress && !it.isLoopbackAddress }
            return PortalAddresses(a4, a6)
        }

        /** [pick] over the device's interfaces now. */
        fun now(): PortalAddresses? = runCatching {
            pick(dev.glassworks.platform.HotspotAddress.now(), NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().map { it.inetAddresses.toList() })
        }.getOrNull()
    }
}

/**
 * The setup hotspot's captive portal (#197): a phone that joins the hotspot opens the setup page by itself, as on
 * hotel Wi-Fi. Phones open their sign-in page when their connectivity check gets a redirect: iOS fetches
 * `http://captive.apple.com/hotspot-detect.html` and Android `http://connectivitycheck.gstatic.com/generate_204`.
 * Android's local-only hotspot gives phones the panel as DNS, but its DNS has no upstream, so nothing resolves and
 * no phone ever sees a portal (docs/research/setup-hotspot-portal-and-name.md).
 *
 * While [start]ed, this answers instead: a DNS responder on udp [DNS_PORT] that answers every name with the hotspot's
 * IPv4 address, and a web server on tcp [HTTP_PORT] that sends every request to [page]. Both bind to the hotspot's own
 * addresses only, never to the home network. An app can't listen on ports 53 and 80, so something with root must send
 * the hotspot's traffic there: on the S1 the radio helper does (`Frame.hotspotPortal`), and only while these
 * sockets exist. Without that, this changes nothing a phone sees.
 */
class CaptivePortal(
    /** The setup page's address on the hotspot, for its IPv4 address. */
    private val page: (String) -> String,
    private val log: (String) -> Unit = {},
    private val dnsPort: Int = DNS_PORT,
    private val httpPort: Int = HTTP_PORT,
) : Portal {
    private var dns: List<DatagramSocket> = emptyList()
    private var http: HttpServer? = null

    /** Answers on [at] from now on, instead of where it answered before. */
    @Synchronized
    override fun start(at: PortalAddresses) {
        stop()
        val to = page(at.v4.hostAddress!!)
        dns = listOfNotNull(at.v4, at.v6).mapNotNull { a ->
            try {
                DatagramSocket(InetSocketAddress(a, dnsPort)).also { s -> Thread({ serve(s, at.v4) }, "portal-dns").apply { isDaemon = true; start() } }
            } catch (e: IOException) {
                log("portal: no DNS on ${if (a is Inet4Address) "IPv4" else "IPv6"}: ${e.message}"); null
            }
        }
        http = HttpServer(httpPort, { redirect(to) }, bindAddress = at.v4, workers = 2, queued = 8, requestDeadlineMs = 5_000L).let { s ->
            try { s.start(); s } catch (e: IOException) { log("portal: no web redirect: ${e.message}"); null }
        }
        log("portal on (DNS ${dns.size}, redirect ${if (http != null) "on" else "off"})")
    }

    @Synchronized
    override fun stop() {
        if (dns.isEmpty() && http == null) return
        dns.forEach { it.close() }
        dns = emptyList()
        http?.stop()
        http = null
        log("portal off")
    }

    private fun serve(s: DatagramSocket, ap: Inet4Address) {
        val buf = ByteArray(MAX_QUERY)
        while (!s.isClosed) {
            val p = DatagramPacket(buf, buf.size)
            try {
                s.receive(p)
                val out = answer(buf, p.length, ap) ?: continue
                s.send(DatagramPacket(out, out.size, p.socketAddress))
            } catch (_: IOException) {
                // closed by stop(), or one reply that couldn't go: the loop's condition decides
            }
        }
    }

    companion object {
        /** Where the hotspot's DNS (udp 53) goes. The S1's radio helper has the same number (PORTAL_DNS). */
        const val DNS_PORT = 8053

        /** Where the hotspot's web traffic (tcp 80) goes. The S1's radio helper has the same number (PORTAL_HTTP). */
        const val HTTP_PORT = 8098

        private const val MAX_QUERY = 512

        /** Every request: a `302` to the setup page. What makes iOS and Android show their sign-in page. */
        fun redirect(to: String): HttpResponse = HttpResponse(
            302,
            mapOf("Location" to to, "Cache-Control" to "no-store", "Content-Type" to "text/html; charset=utf-8"),
            """<!doctype html><title>Set up the panel</title><a href="$to">Set up the panel</a>""".toByteArray(Charsets.UTF_8),
        )

        /**
         * The DNS answer to the [length] bytes of [query]: for an A question, [ap] (TTL 0, so a phone keeps nothing
         * once it leaves); for any other type, no records. A query that isn't one standard question gets an error, and
         * anything that isn't a query gets nothing.
         */
        fun answer(query: ByteArray, length: Int, ap: Inet4Address): ByteArray? {
            if (length < 12 || length > query.size) return null
            val flags = u16(query, 2)
            if (flags and 0x8000 != 0) return null // a response, not a query
            val rd = flags and 0x0100
            fun header(rcode: Int, qd: Int, an: Int) = byteArrayOf(
                query[0], query[1], (0x84 or (flags shr 8 and 0x78) or (rd shr 8)).toByte(), (0x80 or rcode).toByte(),
                0, qd.toByte(), 0, an.toByte(), 0, 0, 0, 0,
            )
            if ((flags shr 11) and 0xF != 0) return header(NOTIMP, 0, 0)
            if (u16(query, 4) != 1) return header(FORMERR, 0, 0)
            var i = 12
            var name = 0
            while (true) {
                if (i >= length) return header(FORMERR, 0, 0)
                val l = query[i].toInt() and 0xFF
                if (l == 0) break
                if (l > 63) return header(FORMERR, 0, 0) // a pointer or a reserved label type: not in a question
                name += l + 1
                if (name > 254) return header(FORMERR, 0, 0)
                i += l + 1
            }
            val end = i + 5 // the root label, the type and the class
            if (end > length) return header(FORMERR, 0, 0)
            val a = u16(query, i + 1) == TYPE_A && (u16(query, i + 3) == CLASS_IN)
            val question = query.copyOfRange(12, end)
            val record = if (!a) ByteArray(0) else byteArrayOf(
                0xC0.toByte(), 12, 0, TYPE_A.toByte(), 0, CLASS_IN.toByte(), 0, 0, 0, 0, 0, 4,
            ) + ap.address
            return header(0, 1, if (a) 1 else 0) + question + record
        }

        private fun u16(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

        private const val TYPE_A = 1
        private const val CLASS_IN = 1
        private const val FORMERR = 1
        private const val NOTIMP = 4
    }
}

/** What [PortalHotspot] runs while the hotspot is up; [CaptivePortal] on a device. */
interface Portal {
    fun start(at: PortalAddresses)

    /** Safe when it isn't running. */
    fun stop()
}
