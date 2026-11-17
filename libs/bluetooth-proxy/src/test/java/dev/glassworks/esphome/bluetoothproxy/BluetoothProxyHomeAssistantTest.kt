package dev.glassworks.esphome.bluetoothproxy

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import dev.glassworks.esphome.ApiServer
import dev.glassworks.esphome.DeviceIdentity
import dev.glassworks.esphome.GattCharacteristic
import dev.glassworks.esphome.GattDescriptor
import dev.glassworks.esphome.GattService
import dev.glassworks.esphome.MemoryKeyStore
import dev.glassworks.esphome.NoiseKeys
import java.io.File
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The proxy as Home Assistant meets it: the real client HA 2026.11 pins (aioesphomeapi 46.25.0), via
 * ESPHOME_PYTHON as in esphome-api's EspHomeKeepaliveTest. It takes the panel as a passive,
 * not connectable Bluetooth scanner with raw advertisements (bleak-esphome connect.py), and gets
 * what a fake radio hears. The engine scans only while HA is subscribed.
 */
class BluetoothProxyHomeAssistantTest {
    private val identity = DeviceIdentity(
        name = "test-panel", friendlyName = "Test Panel", macAddress = "02:00:00:00:00:01",
        esphomeVersion = "2026.10.2", compilationTime = "test", manufacturer = "Test", model = "JVM",
        voiceFeatures = 0,
    )

    /** Hears [COUNT] made-up advertisements, 5 ms apart, each time a scan starts. */
    private class RadioWithNeighbours : BleScanner {
        val started = CountDownLatch(1)
        val stopped = CountDownLatch(1)

        override fun start(power: ScanPower, listener: BleScanner.Listener) {
            started.countDown()
            Thread {
                for (i in 0 until COUNT) {
                    listener.onAdvertisement(0xC0FFEE000000L + i, -40 - i, advertising(i) + ByteArray(50))
                    Thread.sleep(5)
                }
            }.apply { isDaemon = true }.start()
        }

        override fun stop() = stopped.countDown()
    }

    companion object {
        const val COUNT = 40
        /** Flags, then manufacturer data under 0xFFFF (the id the Bluetooth SIG keeps for tests). */
        fun advertising(i: Int) = byteArrayOf(2, 1, 6, 4, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), i.toByte())
    }

    @Test
    fun homeAssistantSeesAPassiveProxyAndItsAdvertisements() {
        val python = System.getenv("ESPHOME_PYTHON")
        assumeTrue("set ESPHOME_PYTHON to a python with aioesphomeapi installed", !python.isNullOrBlank())

        // The key Home Assistant would have provisioned: the proxy answers only an authenticated connection.
        val psk = ByteArray(32) { (it * 7 + 1).toByte() }
        val server = ApiServer(0, { identity }, emptyList(), keys = NoiseKeys(MemoryKeyStore(psk)), bindAddress = InetAddress.getLoopbackAddress())
        val radio = RadioWithNeighbours()
        val engine = BluetoothProxyEngine(
            radio, server::sendBluetoothAdvertisements, ScanPower.LOW_LATENCY,
            BluetoothProxyEngine.ExecutorLoop(), ScanSchedule.Limits(lingerMs = 300), { server.removeBluetooth(it) },
        )
        server.bluetooth = engine
        server.start()
        try {
            val script = File(javaClass.classLoader!!.getResource("esphome_bluetooth_proxy.py")!!.toURI())
            val log = File.createTempFile("esphome_bluetooth_proxy", ".log").apply { deleteOnExit() }
            val proc = ProcessBuilder(python, "-I", script.absolutePath, server.localPort.toString(), COUNT.toString(), java.util.Base64.getEncoder().encodeToString(psk))
                .redirectErrorStream(true).redirectOutput(log).start()
            val finished = try { proc.waitFor(60, TimeUnit.SECONDS) } finally { if (proc.isAlive) proc.destroyForcibly() }
            val out = log.readText()
            assertTrue("client timed out:\n$out", finished)
            assertEquals("client failed:\n$out", 0, proc.exitValue())
            val r = JSONObject(out.lines().first { it.startsWith("RESULT ") }.removePrefix("RESULT "))

            assertTrue("HA connects a Bluetooth scanner (any flag does)", r.getBoolean("scanner"))
            assertTrue("PASSIVE_SCAN set, as on an ESPHome proxy", r.getBoolean("passive_scan"))
            assertTrue("and subscribes to raw advertisements", r.getBoolean("raw"))
            assertFalse("passive: not connectable", r.getBoolean("connectable"))
            assertFalse("no scanner state or mode (Android can't switch to a passive scan)", r.getBoolean("state_and_mode"))
            assertEquals("the scanner's source is the device's MAC", identity.macAddress, r.getString("source"))

            val advs = r.getJSONArray("advertisements")
            assertEquals(COUNT, advs.length())
            for (i in 0 until COUNT) {
                val a = advs.getJSONObject(i)
                assertEquals("C0:FF:EE:00:00:%02X".format(i), a.getString("address"))
                assertEquals(-40 - i, a.getInt("rssi"))
                assertEquals("Android's zero padding is gone", advertising(i).joinToString("") { "%02x".format(it) }, a.getString("data"))
            }
            val batches = r.getJSONArray("batches").let { b -> (0 until b.length()).map { b.getInt(it) } }
            assertTrue("batched, at most 16 each: $batches", batches.all { it in 1..16 } && batches.size < COUNT)

            assertTrue("scanning started for HA", radio.started.await(0, TimeUnit.SECONDS))
            assertTrue("and stopped once HA left", radio.stopped.await(5, TimeUnit.SECONDS))
            assertFalse(engine.status.subscribed)
        } finally {
            engine.close()
            server.stop()
        }
    }

    /**
     * A radio with one made-up device that answers on its own thread, as Android does: a service at
     * handle 10, a characteristic (11, read, write, notify) and its CCCD (12). Writing the CCCD sends one
     * notification.
     */
    private class OneDeviceRadio : GattRadio {
        private val android = Executors.newSingleThreadExecutor()
        var connects = 0

        override fun connect(address: Long, listener: GattLink.Listener): GattLink {
            connects++
            android.execute { listener.onConnectionState(true, 0) }
            return object : GattLink {
                var value = byteArrayOf(0x17)
                var cccd = byteArrayOf(0, 0)
                fun later(task: () -> Unit): Boolean { android.execute(task); return true }
                override fun requestMtu(mtu: Int) = later { listener.onMtu(247, 0) }
                override fun discoverServices() = later { listener.onServicesDiscovered(0) }
                override fun services() = listOf(GattService(UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb"), 10, listOf(
                    GattCharacteristic(UUID.fromString("12345678-1234-5678-1234-56789abcdef0"), 11, 0x1A,
                        listOf(GattDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"), 12))),
                )))
                override fun read(handle: Int) = later { listener.onRead(handle, value, 0) }
                override fun write(handle: Int, data: ByteArray, withResponse: Boolean) = later { value = data; listener.onWritten(handle, 0) }
                override fun readDescriptor(handle: Int) = later { listener.onDescriptorRead(handle, cccd, 0) }
                override fun writeDescriptor(handle: Int, data: ByteArray) = later {
                    cccd = data
                    listener.onDescriptorWritten(handle, 0)
                    listener.onNotification(11, byteArrayOf(0x55, 0x66))
                }
                override fun setNotify(handle: Int, enable: Boolean) = true
                override fun disconnect() { android.execute { listener.onConnectionState(false, 0) } }
                override fun close() = Unit
            }
        }

        fun close() = android.shutdownNow()
    }

    /**
     * Home Assistant's client makes a GATT connection through the engine (#246): the same script as
     * esphome-api's EspHomeBluetoothConnectionsTest, against [BluetoothConnectionEngine] with a radio
     * that answers as Android does. HA sees 3 slots, 2 while linked, and 3 again after each disconnect.
     */
    @Test
    fun homeAssistantConnectsToADeviceThroughTheEngine() {
        val python = System.getenv("ESPHOME_PYTHON")
        assumeTrue("set ESPHOME_PYTHON to a python with aioesphomeapi installed", !python.isNullOrBlank())

        val psk = ByteArray(32) { (it * 5 + 3).toByte() }
        val server = ApiServer(0, { identity }, emptyList(), keys = NoiseKeys(MemoryKeyStore(psk)), bindAddress = InetAddress.getLoopbackAddress())
        val scan = BluetoothProxyEngine(
            RadioWithNeighbours(), server::sendBluetoothAdvertisements, ScanPower.LOW_LATENCY,
            BluetoothProxyEngine.ExecutorLoop(), ScanSchedule.Limits(lingerMs = 300), { server.removeBluetooth(it) },
        )
        val radio = OneDeviceRadio()
        val links = BluetoothConnectionEngine(server, radio)
        server.bluetooth = scan
        server.bluetoothConnections = links
        server.start()
        try {
            // Shared with esphome-api, whose own test drives the protocol with a stand-in handler.
            val script = File("../esphome-api/src/test/resources/esphome_bluetooth_connections.py")
            assertTrue("the script is at ${script.absolutePath}", script.isFile)
            val log = File.createTempFile("esphome_bluetooth_connections", ".log").apply { deleteOnExit() }
            val proc = ProcessBuilder(python, "-I", script.absolutePath, server.localPort.toString(),
                java.util.Base64.getEncoder().encodeToString(psk), 0x02C0FFEE0001L.toString())
                .redirectErrorStream(true).redirectOutput(log).start()
            val finished = try { proc.waitFor(60, TimeUnit.SECONDS) } finally { if (proc.isAlive) proc.destroyForcibly() }
            val out = log.readText()
            assertTrue("client timed out:\n$out", finished)
            assertEquals("client failed:\n$out", 0, proc.exitValue())
            val r = JSONObject(out.lines().first { it.startsWith("RESULT ") }.removePrefix("RESULT "))

            assertTrue("connectable", r.getBoolean("connectable"))
            assertTrue(r.getBoolean("remote_caching"))
            assertFalse(r.getBoolean("pairing"))
            assertEquals(12, r.getJSONArray("services").getJSONObject(0).getJSONArray("characteristics").getJSONObject(0)
                .getJSONArray("descriptors").getJSONObject(0).getInt("handle"))
            assertEquals("17", r.getString("read"))
            assertEquals("2a", r.getString("read_after_write"))
            assertEquals("[11,\"5566\"]", r.getJSONArray("notification").toString())
            assertEquals("0100", r.getString("cccd"))
            assertEquals("connected at the MTU Android settled on, twice", "[[true,247,0],[false,0,0],[true,247,0],[false,0,0]]", r.getJSONArray("states").toString())
            assertEquals("[[3,3,0,false],[2,3,1,true],[3,3,0,false],[2,3,1,true],[3,3,0,false]]", r.getJSONArray("free").toString())
            assertEquals(2, radio.connects)
            assertEquals("every slot free again", 0, links.inUse)
        } finally {
            links.close()
            scan.close()
            radio.close()
            server.stop()
        }
    }
}
