package dev.glassworks.esphome

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** The proxy's active connections on the wire (#246): flags, who may ask, what each message carries. */
class BluetoothConnectionServerTest {
    private val identity = DeviceIdentity(
        name = "test-panel", friendlyName = "Test Panel", macAddress = "02:00:00:00:00:01",
        esphomeVersion = "2026.10.2", compilationTime = "test", manufacturer = "Test", model = "JVM",
        voiceFeatures = 0,
    )
    private val proxy = object : BluetoothProxyHandler {
        override val featureFlags = BluetoothProxyFeature.PASSIVE_SCAN or BluetoothProxyFeature.RAW_ADVERTISEMENTS
        override fun onSubscribed(subscribed: Boolean) = Unit
        override val authOnly = false
    }
    private val requests = CopyOnWriteArrayList<GattRequest>()
    @Volatile private var subscriberChanges = 0
    private val links = object : BluetoothConnectionHandler {
        override fun connectionsFree() = GattEvent.ConnectionsFree(2, 3, listOf(ADDRESS))
        override fun onRequest(request: GattRequest) { requests += request }
        override fun onSubscriberChanged() { subscriberChanges++ }
    }
    private val server = ApiServer(0, { identity }, emptyList(), keys = null, bindAddress = InetAddress.getLoopbackAddress())

    @After fun stop() = server.stop()

    private fun start(): ApiServer {
        server.bluetooth = proxy
        server.bluetoothConnections = links
        server.start()
        return server
    }

    private fun PlainClient.subscribe() { send(BluetoothMsg.SUBSCRIBE_LE_ADVERTISEMENTS_REQUEST, ProtoWriter().uint32(1, 1)); roundTrip() }

    private fun waitFor(what: String, cond: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!cond()) {
            check(System.nanoTime() < until) { "timed out waiting for $what" }
            Thread.sleep(10)
        }
    }

    companion object {
        /** Made up, locally administered. */
        const val ADDRESS = 0x02C0FFEE0001L
        const val ALL = BluetoothProxyFeature.PASSIVE_SCAN or BluetoothProxyFeature.RAW_ADVERTISEMENTS or
            BluetoothProxyFeature.ACTIVE_CONNECTIONS or BluetoothProxyFeature.REMOTE_CACHING
    }

    @Test fun theConnectionFlagsGoOutOnlyWithTheHandler() {
        start()
        PlainClient(server.localPort).use { c ->
            c.send(Msg.DEVICE_INFO_REQUEST)
            assertEquals(ALL, c.await(Msg.DEVICE_INFO_RESPONSE).uint32(15))
            c.send(Msg.DEVICE_CAPABILITIES_REQUEST)
            assertEquals(ALL, ProtoMessage(c.await(Msg.DEVICE_CAPABILITIES_RESPONSE).bytes(1)).uint32(1))

            server.bluetoothConnections = null
            c.send(Msg.DEVICE_INFO_REQUEST)
            assertEquals("listen only", proxy.featureFlags, c.await(Msg.DEVICE_INFO_RESPONSE).uint32(15))
        }
    }

    @Test fun connectionsWithoutTheProxyAdvertiseNothing() {
        server.bluetoothConnections = links
        server.start()
        PlainClient(server.localPort).use { c ->
            c.send(Msg.DEVICE_INFO_REQUEST)
            assertFalse(c.await(Msg.DEVICE_INFO_RESPONSE).has(15))
            c.send(Msg.DEVICE_CAPABILITIES_REQUEST)
            assertFalse(c.await(Msg.DEVICE_CAPABILITIES_RESPONSE).has(1))
        }
    }

    @Test fun eachRequestReachesTheHandlerWithItsFields() {
        start()
        PlainClient(server.localPort).use { c ->
            c.subscribe()
            c.send(BluetoothConnectionMsg.DEVICE_REQUEST, ProtoWriter().uint64(1, ADDRESS).uint32(2, 4).bool(3, true).uint32(4, 1))
            c.send(BluetoothConnectionMsg.DEVICE_REQUEST, ProtoWriter().uint64(1, ADDRESS).uint32(2, 5).bool(3, true))
            c.send(BluetoothConnectionMsg.GATT_GET_SERVICES_REQUEST, ProtoWriter().uint64(1, ADDRESS))
            c.send(BluetoothConnectionMsg.GATT_READ_REQUEST, ProtoWriter().uint64(1, ADDRESS).uint32(2, 12))
            c.send(BluetoothConnectionMsg.GATT_WRITE_REQUEST, ProtoWriter().uint64(1, ADDRESS).uint32(2, 13).bool(3, true).bytes(4, byteArrayOf(1, 2)))
            c.send(BluetoothConnectionMsg.GATT_READ_DESCRIPTOR_REQUEST, ProtoWriter().uint64(1, ADDRESS).uint32(2, 14))
            c.send(BluetoothConnectionMsg.GATT_WRITE_DESCRIPTOR_REQUEST, ProtoWriter().uint64(1, ADDRESS).uint32(2, 15).bytes(3, byteArrayOf(1, 0)))
            c.send(BluetoothConnectionMsg.GATT_NOTIFY_REQUEST, ProtoWriter().uint64(1, ADDRESS).uint32(2, 16).bool(3, true))
            c.send(BluetoothConnectionMsg.DEVICE_REQUEST, ProtoWriter().uint64(1, ADDRESS).uint32(2, 1))
            c.roundTrip()
        }
        assertTrue(requests.all { it.address == ADDRESS })
        val connect = requests[0] as GattRequest.Connect
        assertEquals(1, connect.addressType)
        assertTrue(connect.withCache)
        val noCache = requests[1] as GattRequest.Connect
        assertEquals(0, noCache.addressType)
        assertFalse(noCache.withCache)
        assertTrue(requests[2] is GattRequest.GetServices)
        assertEquals(12, (requests[3] as GattRequest.Read).handle)
        val write = requests[4] as GattRequest.Write
        assertEquals(13, write.handle)
        assertTrue(write.withResponse)
        assertArrayEquals(byteArrayOf(1, 2), write.data)
        assertEquals(14, (requests[5] as GattRequest.ReadDescriptor).handle)
        val wd = requests[6] as GattRequest.WriteDescriptor
        assertEquals(15, wd.handle)
        assertArrayEquals(byteArrayOf(1, 0), wd.data)
        val notify = requests[7] as GattRequest.Notify
        assertEquals(16, notify.handle)
        assertTrue(notify.enable)
        assertTrue(requests[8] is GattRequest.Disconnect)
        assertEquals(9, requests.size)
    }

    /** Answers go to the advertisement subscriber, so only it may ask; anyone else hears "not connected". */
    @Test fun onlyTheSubscriberMayAsk() {
        start()
        PlainClient(server.localPort).use { subscriber ->
            subscriber.subscribe()
            PlainClient(server.localPort).use { other ->
                other.send(BluetoothConnectionMsg.DEVICE_REQUEST, ProtoWriter().uint64(1, ADDRESS).uint32(2, 5).bool(3, true))
                val answer = other.await(BluetoothConnectionMsg.DEVICE_CONNECTION_RESPONSE)
                assertEquals(ADDRESS, answer.long(1))
                assertFalse(answer.bool(2))
                assertEquals(GattEvent.NOT_CONNECTED, answer.uint32(4))
                other.send(BluetoothConnectionMsg.GATT_READ_REQUEST, ProtoWriter().uint64(1, ADDRESS).uint32(2, 12))
                val error = other.await(BluetoothConnectionMsg.GATT_ERROR_RESPONSE)
                assertEquals(12, error.uint32(2))
                assertEquals(GattEvent.NOT_CONNECTED, error.uint32(3))
            }
        }
        assertEquals("nothing reached the handler", emptyList<GattRequest>(), requests)
    }

    @Test fun beforeAnySubscriptionRequestsAreRefused() {
        start()
        PlainClient(server.localPort).use { c ->
            c.send(BluetoothConnectionMsg.GATT_GET_SERVICES_REQUEST, ProtoWriter().uint64(1, ADDRESS))
            assertEquals(GattEvent.NOT_CONNECTED, c.await(BluetoothConnectionMsg.GATT_ERROR_RESPONSE).uint32(3))
        }
        assertEquals(emptyList<GattRequest>(), requests)
    }

    @Test fun eventsReachTheSubscriberAsHomeAssistantDecodesThem() {
        start()
        assertFalse("nobody subscribed", server.sendBluetoothEvent(GattEvent.ServicesDone(ADDRESS)))
        PlainClient(server.localPort).use { c ->
            c.subscribe()
            assertTrue(server.sendBluetoothEvent(GattEvent.Connection(ADDRESS, true, 247, 0)))
            c.await(BluetoothConnectionMsg.DEVICE_CONNECTION_RESPONSE).let {
                assertEquals(ADDRESS, it.long(1)); assertTrue(it.bool(2)); assertEquals(247, it.uint32(3))
            }

            server.sendBluetoothEvent(GattEvent.Connection(ADDRESS, false, 0, GattEvent.CONN_TIMEOUT))
            assertEquals(GattEvent.CONN_TIMEOUT, c.await(BluetoothConnectionMsg.DEVICE_CONNECTION_RESPONSE).uint32(4))

            // 0x2A19 (battery level) on the Bluetooth base UUID, and a vendor UUID whose high half is 0.
            val battery = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
            val lowOnly = UUID(0L, 0x1234L)
            val cccd = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
            server.sendBluetoothEvent(GattEvent.Services(ADDRESS, listOf(
                GattService(lowOnly, 10, listOf(GattCharacteristic(battery, 11, 0x12, listOf(GattDescriptor(cccd, 12))))),
            )))
            val svc = c.await(BluetoothConnectionMsg.GATT_GET_SERVICES_RESPONSE).messages(2).single()
            assertEquals("a zero half keeps its place", listOf(0L, 0x1234L), svc.longs(1))
            assertEquals(10, svc.uint32(2))
            val ch = svc.messages(3).single()
            assertEquals(listOf(battery.mostSignificantBits, battery.leastSignificantBits), ch.longs(1))
            assertEquals(11, ch.uint32(2))
            assertEquals(0x12, ch.uint32(3))
            val d = ch.messages(4).single()
            assertEquals(listOf(cccd.mostSignificantBits, cccd.leastSignificantBits), d.longs(1))
            assertEquals(12, d.uint32(2))

            server.sendBluetoothEvent(GattEvent.ServicesDone(ADDRESS))
            assertEquals(ADDRESS, c.await(BluetoothConnectionMsg.GATT_GET_SERVICES_DONE_RESPONSE).long(1))
            server.sendBluetoothEvent(GattEvent.Read(ADDRESS, 11, byteArrayOf(87)))
            c.await(BluetoothConnectionMsg.GATT_READ_RESPONSE).let { assertEquals(11, it.uint32(2)); assertArrayEquals(byteArrayOf(87), it.bytes(3)) }
            server.sendBluetoothEvent(GattEvent.Written(ADDRESS, 13))
            assertEquals(13, c.await(BluetoothConnectionMsg.GATT_WRITE_RESPONSE).uint32(2))
            server.sendBluetoothEvent(GattEvent.NotifySet(ADDRESS, 11))
            assertEquals(11, c.await(BluetoothConnectionMsg.GATT_NOTIFY_RESPONSE).uint32(2))
            server.sendBluetoothEvent(GattEvent.Notification(ADDRESS, 11, byteArrayOf(5, 6)))
            assertArrayEquals(byteArrayOf(5, 6), c.await(BluetoothConnectionMsg.GATT_NOTIFY_DATA_RESPONSE).bytes(3))
            server.sendBluetoothEvent(GattEvent.Error(ADDRESS, 11, 5))
            assertEquals(5, c.await(BluetoothConnectionMsg.GATT_ERROR_RESPONSE).uint32(3))
            server.sendBluetoothEvent(GattEvent.ConnectionsFree(1, 3, listOf(ADDRESS, ADDRESS + 1)))
            c.await(BluetoothConnectionMsg.CONNECTIONS_FREE_RESPONSE).let {
                assertEquals(1, it.uint32(1)); assertEquals(3, it.uint32(2)); assertEquals(listOf(ADDRESS, ADDRESS + 1), it.longs(3))
            }
        }
    }

    @Test fun theSlotTableGoesToWhoeverAsks() {
        start()
        PlainClient(server.localPort).use { c ->
            c.send(BluetoothConnectionMsg.SUBSCRIBE_CONNECTIONS_FREE_REQUEST)
            val free = c.await(BluetoothConnectionMsg.CONNECTIONS_FREE_RESPONSE)
            assertEquals(2, free.uint32(1))
            assertEquals(3, free.uint32(2))
            assertEquals(listOf(ADDRESS), free.longs(3))
        }
    }

    @Test fun aKeyedProxyKeepsItsSlotTableFromAPlaintextClient() {
        server.bluetooth = object : BluetoothProxyHandler by proxy {
            override val authOnly = true
        }
        server.bluetoothConnections = links
        server.start()
        PlainClient(server.localPort).use { c ->
            c.send(BluetoothConnectionMsg.SUBSCRIBE_CONNECTIONS_FREE_REQUEST)
            assertTrue(c.roundTrip().none { it.type == BluetoothConnectionMsg.CONNECTIONS_FREE_RESPONSE })
        }
    }

    @Test fun everySubscriberChangeClosesTheLinks() {
        start()
        val first = PlainClient(server.localPort)
        first.subscribe()
        assertEquals("a first subscriber", 1, subscriberChanges)
        first.subscribe()
        assertEquals("the same one again changes nothing", 1, subscriberChanges)
        PlainClient(server.localPort).use { second ->
            second.subscribe()
            assertEquals("a newer one replaces it", 2, subscriberChanges)
            first.close()
            second.send(BluetoothMsg.UNSUBSCRIBE_LE_ADVERTISEMENTS_REQUEST); second.roundTrip()
            assertEquals("it leaves", 3, subscriberChanges)
            second.subscribe()
            assertEquals(4, subscriberChanges)
        }
        waitFor("the subscriber's connection to close") { subscriberChanges == 5 }
        PlainClient(server.localPort).use { c ->
            c.subscribe()
            server.bluetooth = null
            assertEquals("the proxy going away drops the subscriber", 7, subscriberChanges)
        }
    }

    /** #394 review: a replaced handler closes its links; it gets no more requests to answer them. */
    @Test fun aReplacedHandlerClosesItsLinks() {
        server.bluetoothConnections = links
        var closed = 0
        server.bluetoothConnections = object : BluetoothConnectionHandler by links {
            override fun onSubscriberChanged() { closed++ }
        }
        assertEquals("the old one was told", 1, subscriberChanges)
        server.bluetoothConnections = null
        assertEquals(1, closed)
        server.bluetoothConnections = null
        assertEquals("clearing twice tells nobody again", 1, closed)
    }

    /** #394 review: a plaintext subscriber kept across a swap to a keyed proxy can't make requests. */
    @Test fun swappingToAKeyedProxyEndsAPlaintextSubscription() {
        start()
        PlainClient(server.localPort).use { c ->
            c.subscribe()
            server.bluetooth = object : BluetoothProxyHandler by proxy {
                override val authOnly = true
            }
            assertFalse("no subscriber now", server.sendBluetoothEvent(GattEvent.ServicesDone(ADDRESS)))
            c.send(BluetoothConnectionMsg.GATT_READ_REQUEST, ProtoWriter().uint64(1, ADDRESS).uint32(2, 12))
            assertEquals(GattEvent.NOT_CONNECTED, c.await(BluetoothConnectionMsg.GATT_ERROR_RESPONSE).uint32(3))
        }
        assertEquals(emptyList<GattRequest>(), requests)
    }

    @Test fun removeBluetoothConnectionsRemovesOnlyTheHandlerItNames() {
        server.bluetoothConnections = links
        val other = object : BluetoothConnectionHandler by links {}
        assertFalse(server.removeBluetoothConnections(other))
        assertTrue(server.bluetoothConnections === links)
        assertTrue(server.removeBluetoothConnections(links))
        assertEquals(null, server.bluetoothConnections)
    }

    /** Pairing, unpairing, cache clearing and v1 connects are answered "failed", so a client doesn't wait out its timeout. */
    @Test fun unsupportedDeviceRequestsFailAtOnce() {
        start()
        PlainClient(server.localPort).use { c ->
            c.subscribe()
            for ((type, answer) in listOf(
                0 to BluetoothConnectionMsg.DEVICE_CONNECTION_RESPONSE,
                2 to BluetoothConnectionMsg.DEVICE_PAIRING_RESPONSE,
                3 to BluetoothConnectionMsg.DEVICE_UNPAIRING_RESPONSE,
                6 to BluetoothConnectionMsg.DEVICE_CLEAR_CACHE_RESPONSE,
            )) {
                c.send(BluetoothConnectionMsg.DEVICE_REQUEST, ProtoWriter().uint64(1, ADDRESS).uint32(2, type))
                val m = c.await(answer)
                assertEquals(ADDRESS, m.long(1))
                assertFalse("type $type failed", m.bool(2))
            }
        }
        assertEquals(emptyList<GattRequest>(), requests)
    }
}
