package dev.glassworks.esphome

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * #246 against the real client Home Assistant 2026.11 pins (aioesphomeapi 46.25.0), via ESPHOME_PYTHON
 * as in [EspHomeKeepaliveTest]. HA takes the proxy as connectable and makes a GATT connection through
 * it as bleak-esphome does, to a made-up device behind a [BluetoothConnectionHandler] with 3 slots.
 */
class EspHomeBluetoothConnectionsTest {
    private val identity = DeviceIdentity(
        name = "test-panel", friendlyName = "Test Panel", macAddress = "02:00:00:00:00:01",
        esphomeVersion = "2026.10.2", compilationTime = "test", manufacturer = "Test", model = "JVM",
        voiceFeatures = 0,
    )
    private val proxy = object : BluetoothProxyHandler {
        override val featureFlags = BluetoothProxyFeature.PASSIVE_SCAN or BluetoothProxyFeature.RAW_ADVERTISEMENTS
        override fun onSubscribed(subscribed: Boolean) = Unit
    }

    /**
     * One made-up device: a service at handle 10 with a characteristic (11, read, write, notify) and its
     * CCCD (12). Answers on its own thread, as a radio would. Writing the CCCD sends one notification.
     */
    private class OneDevice(private val server: ApiServer) : BluetoothConnectionHandler {
        val requests = CopyOnWriteArrayList<GattRequest>()
        private val radio = Executors.newSingleThreadExecutor()
        private val linked = HashSet<Long>()
        private var value = byteArrayOf(0x17)
        private var cccd = byteArrayOf(0, 0)

        @Synchronized override fun connectionsFree() = GattEvent.ConnectionsFree(LIMIT - linked.size, LIMIT, linked.toList())
        override fun onSubscriberChanged() = radio.execute { synchronized(this) { linked.clear() } }

        override fun onRequest(request: GattRequest) {
            requests += request
            radio.execute { synchronized(this) { answer(request) } }
        }

        private fun answer(r: GattRequest) {
            val a = r.address
            val event = when (r) {
                is GattRequest.Connect -> {
                    linked += a
                    server.sendBluetoothEvent(connectionsFree())
                    GattEvent.Connection(a, true, 247, 0)
                }
                is GattRequest.Disconnect -> {
                    linked -= a
                    server.sendBluetoothEvent(GattEvent.Connection(a, false, 0, 0))
                    connectionsFree()
                }
                is GattRequest.GetServices -> {
                    server.sendBluetoothEvent(GattEvent.Services(a, listOf(GattService(SERVICE, 10, listOf(
                        GattCharacteristic(CHAR, 11, 0x02 or 0x08 or 0x10, listOf(GattDescriptor(CCCD, 12))),
                    )))))
                    GattEvent.ServicesDone(a)
                }
                is GattRequest.Read -> GattEvent.Read(a, r.handle, value)
                is GattRequest.Write -> { value = r.data; GattEvent.Written(a, r.handle) }
                is GattRequest.ReadDescriptor -> GattEvent.Read(a, r.handle, cccd)
                is GattRequest.WriteDescriptor -> {
                    cccd = r.data
                    server.sendBluetoothEvent(GattEvent.Written(a, r.handle))
                    GattEvent.Notification(a, 11, byteArrayOf(0x55, 0x66))
                }
                is GattRequest.Notify -> GattEvent.NotifySet(a, r.handle)
            }
            server.sendBluetoothEvent(event)
        }

        fun close() = radio.shutdownNow()
    }

    companion object {
        const val LIMIT = 3
        /** A locally administered, made-up address. */
        const val ADDRESS = 0x02C0FFEE0001L
        val SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        val CHAR: UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef0")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    @Test
    fun homeAssistantsClientMakesAGattConnectionThroughTheProxy() {
        val python = System.getenv("ESPHOME_PYTHON")
        assumeTrue("set ESPHOME_PYTHON to a python with aioesphomeapi installed", !python.isNullOrBlank())

        val key = ByteArray(32) { (it * 3 + 5).toByte() }
        val s = ApiServer(0, { identity }, emptyList(), NoiseKeys(MemoryKeyStore(key)), InetAddress.getLoopbackAddress())
        val device = OneDevice(s)
        s.bluetooth = proxy
        s.bluetoothConnections = device
        s.start()
        try {
            val script = File(javaClass.classLoader!!.getResource("esphome_bluetooth_connections.py")!!.toURI())
            val log = File.createTempFile("esphome_bluetooth_connections", ".log").apply { deleteOnExit() }
            val proc = ProcessBuilder(python, "-I", script.absolutePath, s.localPort.toString(),
                Base64.getEncoder().encodeToString(key), ADDRESS.toString()).redirectErrorStream(true).redirectOutput(log).start()
            val finished = try { proc.waitFor(60, TimeUnit.SECONDS) } finally { if (proc.isAlive) proc.destroyForcibly() }
            val out = log.readText()
            assertTrue("client timed out:\n$out", finished)
            assertEquals("client failed:\n$out", 0, proc.exitValue())
            val r = JSONObject(out.lines().first { it.startsWith("RESULT ") }.removePrefix("RESULT "))

            assertTrue("HA makes the scanner connectable", r.getBoolean("connectable"))
            assertTrue("REMOTE_CACHING, without which aioesphomeapi won't connect", r.getBoolean("remote_caching"))
            assertFalse("no pairing: a PIN would raise Android's dialog over the kiosk", r.getBoolean("pairing"))
            assertFalse("no cache clearing", r.getBoolean("cache_clearing"))

            val svc = r.getJSONArray("services").getJSONObject(0)
            assertEquals(SERVICE.toString(), svc.getString("uuid"))
            assertEquals(10, svc.getInt("handle"))
            val ch = svc.getJSONArray("characteristics").getJSONObject(0)
            assertEquals(CHAR.toString(), ch.getString("uuid"))
            assertEquals(11, ch.getInt("handle"))
            assertEquals(0x1A, ch.getInt("properties"))
            val d = ch.getJSONArray("descriptors").getJSONObject(0)
            assertEquals(CCCD.toString(), d.getString("uuid"))
            assertEquals(12, d.getInt("handle"))

            assertEquals("17", r.getString("read"))
            assertEquals("2a", r.getString("read_after_write"))
            assertEquals("[11,\"5566\"]", r.getJSONArray("notification").toString())
            assertEquals("HA wrote the CCCD itself", "0100", r.getString("cccd"))

            val states = r.getJSONArray("states").toString()
            assertEquals("connected at MTU 247, then not, twice", "[[true,247,0],[false,0,0],[true,247,0],[false,0,0]]", states)
            val free = r.getJSONArray("free").toString()
            assertEquals("the slot table HA keeps: 3 free, 2 while linked, 3 again", "[[3,3,0,false],[2,3,1,true],[3,3,0,false],[2,3,1,true],[3,3,0,false]]", free)

            assertEquals("pair: failed, not connected (85)", "[false,-1]", r.getJSONArray("pair").toString())
            assertEquals("unpair: failed (86)", "[false,-1]", r.getJSONArray("unpair").toString())
            assertEquals("clear cache: failed (88)", "[false,-1]", r.getJSONArray("clear_cache").toString())
            assertEquals("a client without the subscription: GATT error 82, not connected", "[-1,\"Not connected\"]", r.getJSONArray("other_read").toString())

            val connects = device.requests.filterIsInstance<GattRequest.Connect>()
            assertEquals(listOf(false, true), connects.map { it.withCache })
            assertTrue(connects.all { it.address == ADDRESS && it.addressType == 0 })
        } finally {
            device.close()
            s.stop()
        }
    }
}
