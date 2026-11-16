package dev.glassworks.esphome.bluetoothproxy

import dev.glassworks.esphome.GattCharacteristic
import dev.glassworks.esphome.GattEvent
import dev.glassworks.esphome.GattRequest
import dev.glassworks.esphome.GattService
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** Records what the engine asks of each link; the test plays Android through [listeners]. */
internal class FakeGattRadio : GattRadio {
    val calls = ArrayList<String>()
    val listeners = HashMap<Long, GattLink.Listener>()
    /** The next connect fails to start. */
    var refuse = false
    /** Operations the next link refuses to start. */
    val refused = HashSet<String>()
    var services = listOf(
        GattService(UUID(1, 1), 10, listOf(GattCharacteristic(UUID(1, 2), 11, 0x12, emptyList()))),
        GattService(UUID(1, 3), 20, emptyList()),
    )

    override fun connect(address: Long, listener: GattLink.Listener): GattLink? {
        if (refuse) return null
        listeners[address] = listener
        calls += "connect ${address.toString(16)}"
        return object : GattLink {
            fun call(what: String): Boolean { calls += what; return what.substringBefore(' ') !in refused }
            override fun requestMtu(mtu: Int) = call("mtu $mtu")
            override fun discoverServices() = call("discover")
            override fun services() = services
            override fun read(handle: Int) = call("read $handle")
            override fun write(handle: Int, data: ByteArray, withResponse: Boolean) = call("write $handle ${data.size} $withResponse")
            override fun readDescriptor(handle: Int) = call("readDescriptor $handle")
            override fun writeDescriptor(handle: Int, data: ByteArray) = call("writeDescriptor $handle")
            override fun setNotify(handle: Int, enable: Boolean) = call("notify $handle $enable")
            override fun disconnect() { calls += "disconnect ${address.toString(16)}" }
            override fun close() { calls += "close ${address.toString(16)}" }
        }
    }
}

class BluetoothConnectionEngineTest {
    private val loop = ManualLoop()
    private val radio = FakeGattRadio()
    private val sent = ArrayList<GattEvent>()
    private var detached = false
    private val engine = BluetoothConnectionEngine(
        radio, { sent += it; true }, loop, limit = 3, BluetoothConnectionEngine.Timeouts(), detach = { detached = true },
    )

    private companion object {
        /** Made-up, locally administered addresses. */
        const val A = 0x02C0FFEE0001L
        const val B = 0x02C0FFEE0002L
        const val C = 0x02C0FFEE0003L
        const val D = 0x02C0FFEE0004L
    }

    private fun request(r: GattRequest) { engine.onRequest(r); loop.advanceBy(0) }
    private fun android(address: Long, play: GattLink.Listener.() -> Unit) { radio.listeners.getValue(address).play(); loop.advanceBy(0) }

    private fun connected(address: Long, mtu: Int = 247) {
        request(GattRequest.Connect(address, 0, withCache = false))
        android(address) { onConnectionState(true, 0) }
        android(address) { onMtu(mtu, 0) }
        android(address) { onServicesDiscovered(0) }
    }

    private inline fun <reified T : GattEvent> events() = sent.filterIsInstance<T>()
    private fun connections() = events<GattEvent.Connection>().map { Triple(it.address, it.connected, it.error) }
    private fun lastFree() = events<GattEvent.ConnectionsFree>().last()

    @Test fun aLinkIsConnectedOnlyOnceItsMtuAndServicesAreKnown() {
        request(GattRequest.Connect(A, 0, withCache = true))
        assertEquals("a slot is taken at once", 2, lastFree().free)
        assertEquals(listOf(A), lastFree().allocated)
        android(A) { onConnectionState(true, 0) }
        android(A) { onMtu(247, 0) }
        assertTrue("not yet: services unknown", events<GattEvent.Connection>().isEmpty())
        android(A) { onServicesDiscovered(0) }
        assertEquals(listOf("connect 2c0ffee0001", "mtu 517", "discover"), radio.calls)
        val c = events<GattEvent.Connection>().single()
        assertTrue(c.connected)
        assertEquals(247, c.mtu)
        assertEquals(1, engine.inUse)
    }

    @Test fun aHealthyLinkOutlivesTheConnectTimeout() {
        connected(A)
        loop.advanceBy(60_000)
        assertEquals("still connected, no drop at 20 s", listOf(Triple(A, true, 0)), connections())
        assertEquals(1, engine.inUse)
        assertFalse(radio.calls.contains("close 2c0ffee0001"))
    }

    @Test fun aFailedMtuExchangeKeepsTheDefault() {
        request(GattRequest.Connect(A, 0, withCache = false))
        android(A) { onConnectionState(true, 0) }
        android(A) { onMtu(247, 133) }
        android(A) { onServicesDiscovered(0) }
        assertEquals(23, events<GattEvent.Connection>().single().mtu)
    }

    @Test fun aConnectWhileTheLinkIsClosingIsAnswered() {
        connected(A)
        request(GattRequest.Disconnect(A))
        request(GattRequest.Connect(A, 0, withCache = false))
        assertEquals("not connected yet; HA tries again", Triple(A, false, 0), connections().last())
        assertEquals("no second link", 1, radio.calls.count { it == "connect 2c0ffee0001" })
    }

    @Test fun aRadioThatThrowsFreesTheSlotAndTheLoopGoesOn() {
        val throwing = BluetoothConnectionEngine(
            GattRadio { _, _ -> throw IllegalStateException("Bluetooth went away") }, { sent += it; true }, loop, limit = 3,
            BluetoothConnectionEngine.Timeouts(), detach = {},
        )
        throwing.onRequest(GattRequest.Connect(A, 0, withCache = false)); loop.advanceBy(0)
        assertEquals(Triple(A, false, GattEvent.CONN_FAIL_ESTABLISH), connections().single())
        assertEquals(0, throwing.inUse)
        throwing.onRequest(GattRequest.Read(B, 11)); loop.advanceBy(0)
        assertEquals("the next request is still answered", GattEvent.NOT_CONNECTED, events<GattEvent.Error>().single().error)
    }

    @Test fun anMtuAndroidRefusesLeavesTheDefault() {
        radio.refused += "mtu"
        request(GattRequest.Connect(A, 0, withCache = false))
        android(A) { onConnectionState(true, 0) }
        android(A) { onServicesDiscovered(0) }
        assertEquals(23, events<GattEvent.Connection>().single().mtu)
    }

    @Test fun aFourthDeviceFindsNoSlot() {
        connected(A); connected(B); connected(C)
        assertEquals(0, lastFree().free)
        request(GattRequest.Connect(D, 0, withCache = false))
        assertEquals("refused as ESPHome does: not connected, error 0", Triple(D, false, 0), connections().last())
        assertFalse(radio.calls.contains("connect 2c0ffee0004"))
        assertEquals(3, engine.inUse)
    }

    @Test fun aDisconnectFreesTheSlot() {
        connected(A)
        request(GattRequest.Disconnect(A))
        assertTrue(radio.calls.contains("disconnect 2c0ffee0001"))
        assertEquals("still held until Android says it's gone", 2, lastFree().free)
        android(A) { onConnectionState(false, 0) }
        assertEquals(Triple(A, false, 0), connections().last())
        assertTrue(radio.calls.contains("close 2c0ffee0001"))
        assertEquals(3, lastFree().free)
        assertEquals(0, engine.inUse)
    }

    @Test fun aDisconnectAndroidNeverConfirmsStillFreesTheSlot() {
        connected(A)
        request(GattRequest.Disconnect(A))
        loop.advanceBy(4_999)
        assertEquals(2, lastFree().free)
        loop.advanceBy(1)
        assertEquals(3, lastFree().free)
        assertTrue(radio.calls.contains("close 2c0ffee0001"))
    }

    @Test fun disconnectingAnUnknownDeviceAnswersAtOnce() {
        request(GattRequest.Disconnect(A))
        assertEquals(Triple(A, false, 0), connections().single())
        assertEquals(3, lastFree().free)
    }

    @Test fun aConnectionThatNeverComesUpTimesOut() {
        request(GattRequest.Connect(A, 0, withCache = false))
        loop.advanceBy(19_999)
        assertTrue(connections().isEmpty())
        loop.advanceBy(1)
        assertEquals(Triple(A, false, GattEvent.CONN_TIMEOUT), connections().single())
        assertEquals(3, lastFree().free)
        assertTrue(radio.calls.contains("close 2c0ffee0001"))
    }

    @Test fun aConnectThatCantStartFailsAtOnce() {
        radio.refuse = true
        request(GattRequest.Connect(A, 0, withCache = false))
        assertEquals(Triple(A, false, GattEvent.CONN_FAIL_ESTABLISH), connections().single())
        assertEquals(3, lastFree().free)
    }

    @Test fun theDeviceGoingAwayFreesTheSlotWithAndroidsReason() {
        connected(A)
        android(A) { onConnectionState(false, 0x13) }
        assertEquals(Triple(A, false, 0x13), connections().last())
        assertEquals(3, lastFree().free)
    }

    @Test fun oneOperationAtATimePerLink() {
        connected(A)
        radio.calls.clear()
        request(GattRequest.Read(A, 11))
        request(GattRequest.Write(A, 11, byteArrayOf(1, 2), withResponse = true))
        assertEquals("the write waits for the read", listOf("read 11"), radio.calls)
        android(A) { onRead(11, byteArrayOf(9), 0) }
        assertEquals(listOf("read 11", "write 11 2 true"), radio.calls)
        android(A) { onWritten(11, 0) }
        val read = events<GattEvent.Read>().single()
        assertArrayEquals(byteArrayOf(9), read.data)
        assertEquals(11, events<GattEvent.Written>().single().handle)
    }

    @Test fun linksDontWaitForEachOther() {
        connected(A); connected(B)
        radio.calls.clear()
        request(GattRequest.Read(A, 11))
        request(GattRequest.Read(B, 11))
        assertEquals(listOf("read 11", "read 11"), radio.calls)
    }

    @Test fun anOperationWithNoAnswerDropsTheLink() {
        connected(A)
        request(GattRequest.Read(A, 11))
        loop.advanceBy(9_999)
        assertTrue(events<GattEvent.Error>().isEmpty())
        loop.advanceBy(1)
        assertEquals(GattEvent.GATT_ERROR, events<GattEvent.Error>().single().error)
        assertEquals(Triple(A, false, GattEvent.CONN_TIMEOUT), connections().last())
        assertEquals(3, lastFree().free)
    }

    @Test fun anOperationAndroidRefusesFailsAndTheNextRuns() {
        connected(A)
        radio.refused += "read"
        request(GattRequest.Read(A, 11))
        request(GattRequest.WriteDescriptor(A, 12, byteArrayOf(1, 0)))
        assertEquals(11, events<GattEvent.Error>().single().handle)
        assertTrue(radio.calls.contains("writeDescriptor 12"))
    }

    @Test fun attErrorsPassThroughAndAndroidsOwnBecomeGeneric() {
        connected(A)
        request(GattRequest.Read(A, 11))
        android(A) { onRead(11, ByteArray(0), 5) }
        request(GattRequest.ReadDescriptor(A, 12))
        android(A) { onDescriptorRead(12, ByteArray(0), 257) }
        assertEquals(listOf(5, GattEvent.GATT_ERROR), events<GattEvent.Error>().map { it.error })
    }

    @Test fun nothingRunsOnALinkThatIsntReady() {
        request(GattRequest.Read(A, 11))
        request(GattRequest.GetServices(A))
        request(GattRequest.Connect(B, 0, withCache = false))
        request(GattRequest.Read(B, 11))
        assertEquals(listOf(GattEvent.NOT_CONNECTED, GattEvent.NOT_CONNECTED, GattEvent.NOT_CONNECTED), events<GattEvent.Error>().map { it.error })
        assertFalse(radio.calls.any { it.startsWith("read") })
    }

    @Test fun servicesGoOutOneAMessageThenDone() {
        connected(A)
        request(GattRequest.GetServices(A))
        assertEquals(listOf(10, 20), events<GattEvent.Services>().map { it.services.single().handle })
        assertEquals(A, events<GattEvent.ServicesDone>().single().address)
    }

    @Test fun notifyIsLocalAndNotificationsReachHomeAssistant() {
        connected(A)
        request(GattRequest.Notify(A, 11, enable = true))
        assertTrue(radio.calls.contains("notify 11 true"))
        assertEquals(11, events<GattEvent.NotifySet>().single().handle)
        android(A) { onNotification(11, byteArrayOf(4)) }
        assertArrayEquals(byteArrayOf(4), events<GattEvent.Notification>().single().data)
    }

    @Test fun aNewSubscriberClosesEveryLink() {
        connected(A); connected(B)
        engine.onSubscriberChanged(); loop.advanceBy(0)
        assertEquals(
            listOf(Triple(A, false, GattEvent.CONN_TERMINATE_LOCAL_HOST), Triple(B, false, GattEvent.CONN_TERMINATE_LOCAL_HOST)),
            connections().filter { !it.second },
        )
        assertTrue(radio.calls.containsAll(listOf("disconnect 2c0ffee0001", "close 2c0ffee0001", "disconnect 2c0ffee0002", "close 2c0ffee0002")))
        assertEquals(3, lastFree().free)
    }

    @Test fun aDroppedLinksLateCallbacksAreIgnored() {
        request(GattRequest.Connect(A, 0, withCache = false))
        val old = radio.listeners.getValue(A)
        loop.advanceBy(20_000) // timed out
        request(GattRequest.Connect(A, 0, withCache = false))
        val before = sent.size
        old.onConnectionState(true, 0); old.onConnectionState(false, 8); loop.advanceBy(0)
        assertEquals("the new link's slot holds", sent.size, before)
        assertEquals(1, engine.inUse)
    }

    @Test fun connectingAgainWhileConnectedSaysSoAgain() {
        connected(A)
        request(GattRequest.Connect(A, 0, withCache = true))
        assertEquals(listOf(Triple(A, true, 0), Triple(A, true, 0)), connections())
        request(GattRequest.Connect(B, 0, withCache = false))
        request(GattRequest.Connect(B, 0, withCache = false))
        assertEquals("a second connect while connecting is ignored", 1, radio.calls.count { it == "connect 2c0ffee0002" })
    }

    @Test fun closeTakesItOutAndClosesTheLinks() {
        connected(A)
        engine.close(); loop.advanceBy(0)
        assertTrue(detached)
        assertTrue(radio.calls.contains("close 2c0ffee0001"))
        request(GattRequest.Connect(B, 0, withCache = false))
        assertFalse("closed for good", radio.calls.contains("connect 2c0ffee0002"))
    }
}
