/* SPDX-License-Identifier: MIT
 *
 * Follows upstream DigitallyRefined/android-ip-camera c58342b2 (OnvifServerTest: subscription setup, Notify
 * delivery, Unsubscribe). Copyright (c) 2025 DigitallyRefined and contributors. The licence text is in
 * THIRD-PARTY-NOTICES.md and LICENSES/MIT.txt.
 * Changes: package; signed in as our server needs; the local-network rule, the push cap, the 1 h end, and a
 * consumer that never answers. */

package dev.glassworks.camera.onvif

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class OnvifPushTest {
    private val user = "admin"
    private val pass = "secret"
    private var server: OnvifServer? = null
    private val logged = java.util.Collections.synchronizedList(mutableListOf<String>())
    /** What was logged so far, copied under the list's lock (iterating a synchronized list needs it). */
    private fun logs(): List<String> = synchronized(logged) { logged.toList() }
    private val basic = "Basic " + OnvifBase64.encode("$user:$pass".toByteArray())

    /** What the fake [post] got: the consumer and the Notify's body. */
    private val posted = LinkedBlockingQueue<Pair<OnvifServer.PushConsumer, String>>()

    private fun newServer(
        withCreds: Boolean = true,
        resolve: (String) -> List<InetAddress> = { listOf(InetAddress.getByName(it)) },
        fakePost: Boolean = true,
        own: ((InetAddress) -> Boolean)? = null,
    ) = OnvifServer(
        port = 0,
        host = { "192.0.2.41" },
        info = { OnvifDeviceInfo("Google", "Pixel Tablet", "0.14.0", "SER123", "tangorpro", "a1b2c3d4e5f6", "Living Room Tablet") },
        stream = { OnvifStreamInfo("rtsp://192.0.2.41:8554/live", 1920, 1080, 30, 6000) },
        credentials = { if (withCreds) user to pass else null },
        enableDiscovery = false,
        onLog = { logged += it },
        resolve = resolve,
        post = if (fakePost) { c, body -> posted += c to String(body, Charsets.UTF_8); 200 } else null,
        ownAddress = own ?: { a -> try { java.net.NetworkInterface.getByInetAddress(a) != null } catch (_: Exception) { false } },
    ).also { server = it }

    @After fun tearDown() { server?.stop() }

    private fun soap(body: String) =
        "<s:Envelope xmlns:s=\"${OnvifNs.SOAP}\" xmlns:tds=\"${OnvifNs.TDS}\" xmlns:tev=\"${OnvifNs.TEV}\" " +
            "xmlns:wsnt=\"${OnvifNs.WSNT}\" xmlns:wsa=\"${OnvifNs.WSA}\"><s:Body>$body</s:Body></s:Envelope>"

    private fun call(s: OnvifServer, body: String, path: String = "/onvif/events_service", auth: Boolean = true) =
        s.handle("POST", path, if (auth) mapOf("authorization" to basic) else emptyMap(), soap(body).toByteArray())

    private fun subscribe(consumer: String, term: String = "PT600S") =
        "<wsnt:Subscribe><wsnt:ConsumerReference><wsa:Address>$consumer</wsa:Address></wsnt:ConsumerReference>" +
            "<wsnt:InitialTerminationTime>$term</wsnt:InitialTerminationTime></wsnt:Subscribe>"

    private fun next(): Pair<OnvifServer.PushConsumer, String> =
        posted.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("no Notify sent")

    private fun isMotion(notify: String) = Regex("Name=\"IsMotion\" Value=\"(\\w+)\"").find(notify)!!.groupValues[1]

    private fun ip(s: String) = InetAddress.getByName(s)

    private val MAX_RACE = OnvifServer.MAX_PUSH_SUBSCRIPTIONS

    @Test fun `a consumer must be on the local network or the subscriber itself`() {
        for (a in listOf("10.1.2.3", "172.16.0.1", "172.31.255.254", "192.168.1.20", "169.254.10.1", "fe80::1", "fd12:3456::1", "::ffff:10.0.0.7")) {
            assertTrue(a, OnvifServer.localConsumer(ip(a), null))
        }
        for (a in listOf("203.0.113.5", "198.51.100.9", "192.0.2.10", "8.8.8.8", "172.32.0.1", "2001:db8::1", "127.0.0.1", "::1", "0.0.0.0", "224.0.0.251", "255.255.255.255")) {
            assertFalse(a, OnvifServer.localConsumer(ip(a), null))
        }
        // The subscriber's own address passes, whatever it is: an NVR on a routed subnet names itself.
        assertTrue(OnvifServer.localConsumer(ip("198.51.100.9"), ip("198.51.100.9")))
        assertTrue(OnvifServer.localConsumer(ip("127.0.0.1"), ip("127.0.0.1")))
        assertFalse(OnvifServer.localConsumer(ip("198.51.100.9"), ip("198.51.100.10")))
    }

    @Test fun `a consumer address is checked, resolved once and pinned`() {
        val names = mapOf(
            "nvr.example" to listOf(ip("203.0.113.7"), ip("10.0.0.20")),
            "far.example" to listOf(ip("203.0.113.8")),
        )
        // Literals as Android takes them (no lookup); made-up names from the map only.
        val s = newServer(resolve = { n -> names[n] ?: if (n.first().isDigit() || ':' in n) listOf(ip(n)) else throw java.net.UnknownHostException(n) })
        val (c, _) = s.pushConsumer("http://nvr.example:8080/onvif/notify?ch=1", null)
        assertNotNull(c)
        assertEquals(ip("10.0.0.20"), c!!.address) // the local one of its addresses
        assertEquals(8080, c.port)
        assertEquals("nvr.example:8080", c.hostHeader)
        assertEquals("/onvif/notify?ch=1", c.target)
        assertEquals("http://nvr.example:8080", c.label) // never the path

        val (bare, _) = s.pushConsumer("http://10.0.0.21", null)
        assertEquals(80, bare!!.port)
        assertEquals("/", bare.target)
        assertEquals("10.0.0.21", bare.hostHeader)
        val (v6, _) = s.pushConsumer("http://[fd00::5]:9000/x", null)
        assertEquals(ip("fd00::5"), v6!!.address)
        assertEquals("[fd00::5]:9000", v6.hostHeader)

        for (bad in listOf("http://far.example/", "https://10.0.0.20/", "ftp://10.0.0.20/", "http://user:pw@10.0.0.20/",
            "http://nobody.example/", "not a url", "", "http:///path", "http://10.0.0.20:0/", "http://127.0.0.1:6053/")) {
            assertNull(bad, s.pushConsumer(bad, null).first)
        }
    }

    @Test fun `the panel's own addresses are refused, unless the subscriber is the panel itself`() {
        val s = newServer(own = { it == ip("10.0.0.9") || it.isLoopbackAddress })
        assertNull(s.pushConsumer("http://10.0.0.9:6053/", null).first)
        assertNull(s.pushConsumer("http://10.0.0.9:6053/", ip("10.0.0.20")).first)
        assertNotNull(s.pushConsumer("http://10.0.0.9:8089/", ip("10.0.0.9")).first)
        assertNotNull(s.pushConsumer("http://10.0.0.8/", null).first)
        assertNull(s.pushConsumer("http://10.0.0.8/" + "a".repeat(2_100), null).first)
    }

    @Test fun `a new subscriber's first state never comes after a newer change`() {
        val s = newServer()
        // Motion flips while subscriptions arrive: each consumer's Notify must end on the state motion ended on.
        val flipper = Thread { repeat(200) { s.setMotion(it % 2 == 0) } }
        flipper.start()
        repeat(MAX_RACE) { i -> assertEquals(200, call(s, subscribe("http://10.0.1.${i + 1}/n")).status) }
        flipper.join()
        s.setMotion(true); s.setMotion(false) // a last change everyone sees
        val last = HashMap<String, String>()
        while (true) {
            val (c, body) = posted.poll(1, TimeUnit.SECONDS) ?: break
            last[c.address.hostAddress] = isMotion(body)
        }
        assertEquals(MAX_RACE, last.size)
        assertTrue(last.toString(), last.values.all { it == "false" })
    }

    @Test fun `subscribe answers with a reference, then the state now, then each change in order`() {
        val s = newServer()
        val r = call(s, subscribe("http://10.0.0.20:8080/notify"))
        assertEquals(r.text, 200, r.status)
        val doc = OnvifXml.parse(r.text)!!.documentElement
        assertTrue(r.text.contains("<wsa:Action>http://docs.oasis-open.org/wsn/bw-2/NotificationProducer/SubscribeResponse</wsa:Action>"))
        val ref = OnvifXml.text(OnvifXml.find(doc, "SubscriptionReference"), "Address")!!
        assertTrue(ref, ref.startsWith("http://192.0.2.41:${s.localPort}/onvif/subscription/"))
        val end = OnvifXml.parseDateTime(OnvifXml.text(doc, "TerminationTime")!!)!!
        val now = OnvifXml.parseDateTime(OnvifXml.text(doc, "CurrentTime")!!)!!
        assertEquals(600_000L, end - now)

        val (c, first) = next()
        assertEquals(ip("10.0.0.20"), c.address)
        assertTrue(first, first.contains("<wsnt:Notify>"))
        assertTrue(first.contains("PropertyOperation=\"Initialized\""))
        assertTrue(first.contains("<wsa:Action>http://docs.oasis-open.org/wsn/bw-2/NotificationConsumer/Notify</wsa:Action>"))
        assertTrue(first.contains("<wsa:To>http://10.0.0.20:8080/notify</wsa:To>"))
        assertTrue(first.contains("tns1:VideoSource/MotionAlarm"))
        assertEquals("false", isMotion(first))
        assertNotNull("a Notify is well-formed", OnvifXml.parse(first))

        s.setMotion(true)
        s.setMotion(false)
        s.setMotion(true)
        assertEquals(listOf("true", "false", "true"), List(3) { next().second }.map { assertTrue(it.contains("\"Changed\"")); isMotion(it) })

        // Renew and Unsubscribe on the reference's path; afterwards no more Notify.
        val path = ref.substringAfter(":${s.localPort}")
        val renewed = call(s, "<wsnt:Renew><wsnt:TerminationTime>PT120S</wsnt:TerminationTime></wsnt:Renew>", path)
        assertEquals(renewed.text, 200, renewed.status)
        assertTrue(renewed.text.contains("SubscriptionManager/RenewResponse"))
        assertEquals(200, call(s, "<tev:SetSynchronizationPoint/>", path).status)
        assertTrue(next().second.contains("\"Initialized\""))
        val gone = call(s, "<wsnt:Unsubscribe/>", path)
        assertEquals(200, gone.status)
        assertTrue(gone.text.contains("SubscriptionManager/UnsubscribeResponse"))
        s.setMotion(false)
        assertNull(posted.poll(500, TimeUnit.MILLISECONDS))
        assertEquals(400, call(s, "<wsnt:Renew/>", path).status)
    }

    @Test fun `subscribe follows the pull point's sign-in rules`() {
        val s = newServer()
        val refused = call(s, subscribe("http://10.0.0.20/notify"), auth = false)
        assertEquals(401, refused.status)
        assertEquals(0, refused.body.size) // the bare 401 every event call gets
        val none = newServer(withCreds = false)
        assertEquals(401, call(none, subscribe("http://10.0.0.20/notify")).status)
        assertNull(posted.poll(500, TimeUnit.MILLISECONDS))
    }

    @Test fun `a consumer off the local network is a fault, and nothing is sent`() {
        val s = newServer()
        for (bad in listOf("http://203.0.113.5/notify", "https://10.0.0.20/notify", "http://127.0.0.1:6053/")) {
            val r = call(s, subscribe(bad))
            assertEquals(bad, 400, r.status)
            assertTrue(r.text.contains("ter:InvalidArgVal"))
        }
        s.setMotion(true)
        assertNull(posted.poll(500, TimeUnit.MILLISECONDS))
        assertTrue(logs().any { it.contains("refused: ConsumerReference must be on the local network") })
    }

    @Test fun `push subscriptions have their own cap and an end at most an hour away`() {
        val s = newServer()
        val pull = call(s, "<tev:CreatePullPointSubscription><tev:InitialTerminationTime>PT60S</tev:InitialTerminationTime></tev:CreatePullPointSubscription>")
        val pullPath = OnvifXml.text(OnvifXml.parse(pull.text)!!.documentElement, "Address")!!.substringAfter(":${s.localPort}")
        repeat(OnvifServer.MAX_PUSH_SUBSCRIPTIONS + 3) { i -> assertEquals(200, call(s, subscribe("http://10.0.0.${i + 1}/n", "PT${600 + i}S")).status) }
        // Each kept one's first Notify (a pushed-out one's is never sent), until the queue is quiet.
        while (posted.poll(1, TimeUnit.SECONDS) != null) Unit
        s.setMotion(true)
        val got = List(OnvifServer.MAX_PUSH_SUBSCRIPTIONS) { next().first.address.hostAddress }.toSet()
        assertNull("only the cap's worth get it", posted.poll(500, TimeUnit.MILLISECONDS))
        // The ones ending soonest made room: the last ones stay.
        assertEquals((4..OnvifServer.MAX_PUSH_SUBSCRIPTIONS + 3).map { "10.0.0.$it" }.toSet(), got)
        // The pull point isn't pushed out by them.
        val pulled = call(s, "<tev:PullMessages><tev:Timeout>PT0S</tev:Timeout><tev:MessageLimit>10</tev:MessageLimit></tev:PullMessages>", pullPath)
        assertEquals(pulled.text, 200, pulled.status)

        val long = OnvifXml.parse(call(s, subscribe("http://10.0.0.50/n", "PT24H")).text)!!.documentElement
        val end = OnvifXml.parseDateTime(OnvifXml.text(long, "TerminationTime")!!)!!
        val now = OnvifXml.parseDateTime(OnvifXml.text(long, "CurrentTime")!!)!!
        assertEquals(OnvifServer.MAX_PUSH_TERM_MS, end - now)
    }

    @Test fun `capabilities say push is offered`() {
        val s = newServer()
        val caps = s.handle("POST", "/onvif/device_service", emptyMap(), soap("<tds:GetCapabilities/>").toByteArray())
        assertTrue(caps.text.contains("<tt:WSSubscriptionPolicySupport>true</tt:WSSubscriptionPolicySupport>"))
        val svc = s.handle("POST", "/onvif/device_service", emptyMap(), soap("<tds:GetServices><tds:IncludeCapability>true</tds:IncludeCapability></tds:GetServices>").toByteArray())
        assertTrue(svc.text.contains("WSSubscriptionPolicySupport=\"true\""))
        assertTrue(svc.text.contains("MaxNotificationProducers=\"${OnvifServer.MAX_PUSH_SUBSCRIPTIONS}\""))
    }

    // ---------------- over sockets ----------------

    private fun readLine(input: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0 || c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
    }

    /** One POST a consumer got: its request line, headers and body. */
    private class Got(val line: String, val headers: Map<String, String>, val body: String)

    /** A consumer on 127.0.0.1 that answers each POST with [status] and hands what it got to [into]. */
    private fun consumer(into: LinkedBlockingQueue<Got>, status: Int = 200): ServerSocket {
        val ss = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        Thread {
            while (!ss.isClosed) {
                val sock = try { ss.accept() } catch (_: Exception) { break }
                sock.use {
                    val input = it.getInputStream()
                    val line = readLine(input)
                    val headers = HashMap<String, String>()
                    while (true) {
                        val h = readLine(input)
                        if (h.isEmpty()) break
                        headers[h.substringBefore(':').trim().lowercase()] = h.substringAfter(':').trim()
                    }
                    val body = ByteArray(headers["content-length"]!!.toInt())
                    var off = 0
                    while (off < body.size) off += input.read(body, off, body.size - off).also { n -> check(n > 0) }
                    into += Got(line, headers, String(body, Charsets.UTF_8))
                    it.getOutputStream().write("HTTP/1.1 $status OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                }
            }
        }.apply { isDaemon = true; start() }
        return ss
    }

    private fun subscribeOverSocket(s: OnvifServer, consumer: String): Int {
        val c = URL("http://127.0.0.1:${s.localPort}/onvif/events_service").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/soap+xml")
        c.setRequestProperty("Authorization", basic)
        c.outputStream.use { it.write(soap(subscribe(consumer)).toByteArray()) }
        return c.responseCode
    }

    @Test fun `a real consumer gets each Notify as a framed POST`() {
        val got = LinkedBlockingQueue<Got>()
        consumer(got).use { nvr ->
            val s = newServer(fakePost = false)
            s.start()
            // The subscriber is 127.0.0.1 and names itself, so loopback passes here and nowhere else.
            assertEquals(200, subscribeOverSocket(s, "http://127.0.0.1:${nvr.localPort}/onvif/notify?x=1"))
            val first = got.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("no Notify")
            assertEquals("POST /onvif/notify?x=1 HTTP/1.1", first.line)
            assertEquals("127.0.0.1:${nvr.localPort}", first.headers["host"])
            assertEquals("application/soap+xml; charset=utf-8", first.headers["content-type"])
            assertEquals(first.body.toByteArray(Charsets.UTF_8).size.toString(), first.headers["content-length"])
            assertTrue(first.body.contains("\"Initialized\""))
            s.setMotion(true)
            val second = got.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("no Notify on motion")
            assertEquals("true", isMotion(second.body))
            assertFalse(logs().any { it.contains("Notify to") })
        }
    }

    @Test fun `a consumer that never answers can't hold up motion`() {
        // Takes the connection and says nothing: each Notify waits out the read timeout on the notifier's thread.
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { dead ->
            val held = java.util.Collections.synchronizedList(mutableListOf<java.net.Socket>())
            Thread { while (!dead.isClosed) try { held += dead.accept() } catch (_: Exception) { break } }.apply { isDaemon = true; start() }
            val s = newServer(fakePost = false)
            s.start()
            assertEquals(200, subscribeOverSocket(s, "http://127.0.0.1:${dead.localPort}/n"))
            val t0 = System.nanoTime()
            repeat(20) { s.setMotion(it % 2 == 0) }
            assertTrue("setMotion never waits on the consumer", (System.nanoTime() - t0) / 1_000_000 < 500)
            val deadline = System.currentTimeMillis() + 8_000
            while (logs().none { it.contains("Notify to http://127.0.0.1:${dead.localPort} failed") } && System.currentTimeMillis() < deadline) Thread.sleep(50)
            assertTrue(logs().toString(), logs().any { it.contains("Notify to http://127.0.0.1:${dead.localPort} failed: SocketTimeoutException") })
            assertFalse("the log never names the path", logs().any { it.contains("/n failed") })
            synchronized(held) { held.toList() }.forEach { it.close() }
        }
    }
}
