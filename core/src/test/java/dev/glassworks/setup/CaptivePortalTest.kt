/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.setup

import dev.glassworks.platform.Hotspot
import dev.glassworks.platform.HotspotFailure
import dev.glassworks.platform.HotspotNetwork
import dev.glassworks.testing.RepoFiles
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URL

/**
 * The setup hotspot's captive portal (#197): every name resolves to the hotspot, every web request goes to the setup
 * page, and the portal runs exactly while the hotspot is up.
 */
class CaptivePortalTest {
    private val ap = InetAddress.getByName("192.168.43.1") as Inet4Address

    /** A DNS query: id 0x1234, recursion desired, one question for [name] of [type] in class IN. */
    private fun query(name: String, type: Int = 1, flags: Int = 0x0100, qd: Int = 1): ByteArray {
        val q = name.split('.').filter { it.isNotEmpty() }.flatMap { l -> listOf(l.length.toByte()) + l.toByteArray().toList() } + 0.toByte()
        return byteArrayOf(0x12, 0x34, (flags shr 8).toByte(), flags.toByte(), 0, qd.toByte(), 0, 0, 0, 0, 0, 0) +
            q.toByteArray() + byteArrayOf(0, type.toByte(), 0, 1)
    }

    private fun u16(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    @Test
    fun `every name the phones check resolves to the hotspot`() {
        for (name in listOf("captive.apple.com", "connectivitycheck.gstatic.com", "www.google.com", "example.org")) {
            val q = query(name)
            val r = CaptivePortal.answer(q, q.size, ap)!!
            assertEquals("id", 0x1234, u16(r, 0))
            assertEquals("a response, authoritative, recursion desired and available, no error", 0x8580, u16(r, 2))
            assertEquals("one question, one answer", listOf(1, 1, 0, 0), listOf(u16(r, 4), u16(r, 6), u16(r, 8), u16(r, 10)))
            assertArrayEquals("the question as asked", q.copyOfRange(12, q.size), r.copyOfRange(12, q.size))
            val a = r.copyOfRange(q.size, r.size)
            assertEquals("a pointer to the name, A, IN", listOf(0xC00C, 1, 1), listOf(u16(a, 0), u16(a, 2), u16(a, 4)))
            assertEquals("TTL 0: a phone keeps nothing once it leaves", 0, u16(a, 6) or u16(a, 8))
            assertArrayEquals("the hotspot's address", byteArrayOf(0, 4) + ap.address, a.copyOfRange(10, a.size))
        }
    }

    @Test
    fun `other types get no records, so a phone uses IPv4`() {
        for (type in listOf(28, 65, 255)) { // AAAA, HTTPS, ANY
            val q = query("captive.apple.com", type)
            val r = CaptivePortal.answer(q, q.size, ap)!!
            assertEquals("no error", 0, u16(r, 2) and 0xF)
            assertEquals("no answer", 0, u16(r, 6))
            assertEquals("just the question", q.size, r.size)
        }
    }

    @Test
    fun `what isn't one plain question gets an error, and a response gets nothing`() {
        val q = query("a.example")
        assertNull("too short", CaptivePortal.answer(q, 11, ap))
        assertNull("a response", CaptivePortal.answer(query("a.example", flags = 0x8000), q.size, ap))
        val notify = query("a.example", flags = 0x2000) // opcode 4
        assertEquals("another opcode: not implemented", 4, u16(CaptivePortal.answer(notify, notify.size, ap)!!, 2) and 0xF)
        val two = query("a.example", qd = 2)
        assertEquals("two questions: format error", 1, u16(CaptivePortal.answer(two, two.size, ap)!!, 2) and 0xF)
        assertEquals("cut short: format error", 1, u16(CaptivePortal.answer(q, q.size - 2, ap)!!, 2) and 0xF)
        // A label byte of 0xC0 is a pointer, never a 192-byte label, even when 192 bytes follow it.
        val pointer = q.copyOfRange(0, 12) + byteArrayOf(0xC0.toByte()) + ByteArray(192) { 'a'.code.toByte() } + byteArrayOf(0, 0, 1, 0, 1)
        assertEquals("a compressed name: format error", 1, u16(CaptivePortal.answer(pointer, pointer.size, ap)!!, 2) and 0xF)
        val long = query((1..5).joinToString(".") { "a".repeat(60) })
        assertEquals("a name over 255 bytes: format error", 1, u16(CaptivePortal.answer(long, long.size, ap)!!, 2) and 0xF)
    }

    /** A free port on loopback, for a portal the test runs. */
    private fun freePort() = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

    @Test
    fun `on loopback, the portal answers DNS and sends the phone's check to the setup page, and stops`() {
        val lo = InetAddress.getByName("127.0.0.1") as Inet4Address
        val dns = freePort()
        val web = freePort()
        val portal = CaptivePortal({ "http://$it:8099/setup" }, dnsPort = dns, httpPort = web)
        portal.start(PortalAddresses(lo))
        try {
            DatagramSocket().use { s ->
                s.soTimeout = 5_000
                val q = query("captive.apple.com")
                s.send(DatagramPacket(q, q.size, InetSocketAddress(lo, dns)))
                val p = DatagramPacket(ByteArray(512), 512)
                s.receive(p)
                assertArrayEquals(lo.address, p.data.copyOfRange(p.length - 4, p.length))
            }
            val c = URL("http://127.0.0.1:$web/hotspot-detect.html").openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.setRequestProperty("Host", "captive.apple.com")
            assertEquals(302, c.responseCode)
            assertEquals("http://127.0.0.1:8099/setup", c.getHeaderField("Location"))
            c.disconnect()
        } finally {
            portal.stop()
        }
        // Its DNS socket is gone when stop() returns: the port is free at once.
        DatagramSocket(InetSocketAddress(lo, dns)).close()
        // The web server's too, within a moment (its accept thread wakes up). SO_REUSEADDR as the portal's own server
        // sets it: Linux keeps the closed connection in TIME_WAIT, but still refuses the bind while a listener is there.
        val until = System.nanoTime() + 3_000_000_000L
        while (true) {
            val freed = runCatching { ServerSocket().use { it.reuseAddress = true; it.bind(InetSocketAddress(lo, web)) } }
            if (freed.isSuccess) break
            if (System.nanoTime() > until) throw freed.exceptionOrNull()!!
            Thread.sleep(50)
        }
    }

    @Test
    fun `a new start on the same IPv4 address rebinds DNS on the same port and keeps the redirect`() {
        val lo = InetAddress.getByName("127.0.0.1") as Inet4Address
        val dns = freePort()
        val web = freePort()
        val portal = CaptivePortal({ "http://$it:8099/setup" }, dnsPort = dns, httpPort = web)
        portal.start(PortalAddresses(lo))
        try {
            repeat(3) { portal.start(PortalAddresses(lo)) } // what PortalHotspot does when the IPv6 address comes
            DatagramSocket().use { s ->
                s.soTimeout = 5_000
                val q = query("captive.apple.com")
                s.send(DatagramPacket(q, q.size, InetSocketAddress(lo, dns)))
                s.receive(DatagramPacket(ByteArray(512), 512))
            }
            val c = URL("http://127.0.0.1:$web/generate_204").openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            assertEquals(302, c.responseCode)
            c.disconnect()
        } finally {
            portal.stop()
        }
    }

    @Test
    fun `the hotspot's addresses are its IPv4 one and, on the same interface, an IPv6 one that isn't link-local`() {
        val home = listOf(InetAddress.getByName("192.0.2.10"), InetAddress.getByName("2001:db8::10"))
        val hotspot = listOf(InetAddress.getByName("fe80::1"), ap, InetAddress.getByName("2001:db8:43::1"))
        val got = PortalAddresses.pick("192.168.43.1", listOf(home, hotspot))!!
        assertEquals(ap, got.v4)
        assertEquals(InetAddress.getByName("2001:db8:43::1"), got.v6)
        assertNull("only link-local: IPv4 alone", PortalAddresses.pick("192.168.43.1", listOf(listOf(ap, InetAddress.getByName("fe80::1"))))!!.v6)
        assertNull("no such address", PortalAddresses.pick("192.168.43.1", listOf(home)))
        assertNull("no hotspot", PortalAddresses.pick(null, listOf(home, hotspot)))
    }

    /** Records what the portal was asked. */
    private class FakePortal : Portal {
        val calls = mutableListOf<String>()
        override fun start(at: PortalAddresses) { calls += "start ${at.v4.hostAddress} ${at.v6?.hostAddress}" }
        override fun stop() { calls += "stop" }
    }

    private class FakeHotspot : Hotspot {
        var listener: ((Hotspot.Event) -> Unit)? = null
        override fun start(onEvent: (Hotspot.Event) -> Unit) { listener = onEvent }
        override fun stop() { listener = null }
    }

    private val net = HotspotNetwork("Example-Setup", "k3yk3yk3yk3y")

    @Test
    fun `the portal runs exactly while the hotspot is up, and follows its IPv6 address when it comes`() {
        val portal = FakePortal()
        val inner = FakeHotspot()
        val wakes = mutableListOf<() -> Unit>()
        var addresses: PortalAddresses? = PortalAddresses(ap)
        val events = mutableListOf<Hotspot.Event>()
        val h = PortalHotspot(inner, portal, { _, r -> wakes += r; { wakes.remove(r) } }, { addresses })
        h.start { events += it }
        assertEquals("nothing before Android says it's up", listOf("stop"), portal.calls)
        inner.listener!!(Hotspot.Event.Started(net))
        assertEquals(listOf(Hotspot.Event.Started(net)), events)
        assertEquals("start 192.168.43.1 null", portal.calls.last())
        val v6 = InetAddress.getByName("2001:db8:43::1") as Inet6Address
        addresses = PortalAddresses(ap, v6)
        wakes.removeAt(0).also { assertEquals(0, wakes.size) }()
        assertEquals("moved to the IPv6 address too", "start 192.168.43.1 2001:db8:43:0:0:0:0:1", portal.calls.last())
        h.stop()
        assertEquals("stop", portal.calls.last())

        portal.calls.clear()
        h.start { events += it }
        inner.listener!!(Hotspot.Event.Started(net))
        inner.listener!!(Hotspot.Event.Stopped)
        assertEquals("Android ended it: off", "stop", portal.calls.last())
        assertEquals("...and the screen still hears it", Hotspot.Event.Stopped, events.last())
        assertEquals("the recheck of a stopped hotspot is cancelled", 0, wakes.size)

        portal.calls.clear()
        h.start { events += it }
        inner.listener!!(Hotspot.Event.Failed(HotspotFailure.NO_CHANNEL))
        assertEquals("a failed start never runs it", listOf("stop", "stop"), portal.calls)

        portal.calls.clear()
        addresses = null
        h.start { events += it }
        inner.listener!!(Hotspot.Event.Started(net))
        assertEquals("no address yet: not started", listOf("stop"), portal.calls)
        addresses = PortalAddresses(ap)
        wakes.removeAt(0).also { assertEquals(0, wakes.size) }()
        assertEquals("...until the recheck finds one", "start 192.168.43.1 null", portal.calls.last())
    }

    @Test
    fun `the app's ports are the ones the S1's radio helper sends the hotspot's traffic to`() {
        val helper = File(RepoFiles.root, "frames/aqara-s1-plus/tools/radio/s1kiosk_radio.sh").readText()
        assertEquals(CaptivePortal.DNS_PORT.toString(), Regex("""(?m)^PORTAL_DNS=(\d+)$""").find(helper)?.groupValues?.get(1))
        assertEquals(CaptivePortal.HTTP_PORT.toString(), Regex("""(?m)^PORTAL_HTTP=(\d+)$""").find(helper)?.groupValues?.get(1))
    }
}
