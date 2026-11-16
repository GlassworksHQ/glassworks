package dev.glassworks.bluetoothproxy

import android.app.Application
import android.content.Context
import dev.glassworks.esphome.ApiServer
import dev.glassworks.esphome.DeviceIdentity
import dev.glassworks.esphome.ProtoMessage
import dev.glassworks.esphome.ProtoWriter
import dev.glassworks.esphome.bluetoothproxy.BleScanner
import dev.glassworks.esphome.bluetoothproxy.GattRadio
import dev.glassworks.esphome.bluetoothproxy.ScanFailure
import dev.glassworks.esphome.bluetoothproxy.ScanPower
import dev.glassworks.pane.PaneContext
import dev.glassworks.platform.AppBuild
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * #246, the maintainer's condition: the real Bluetooth MAC goes to Home Assistant only when it is one,
 * and any failure to get it falls back to today (no `bluetooth_mac_address`, HA keys the scanner on the
 * device's ESPHome MAC). Each case runs the definition's pane on a real server and reads DeviceInfo.
 * Made-up, locally administered MACs only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AdapterMacTest {
    private val app = RuntimeEnvironment.getApplication()
    private val file = app.getSharedPreferences("adapter-mac-test", Context.MODE_PRIVATE)
    private val identity = DeviceIdentity(
        name = "test-panel", friendlyName = "Test Panel", macAddress = "02:00:00:00:00:01",
        esphomeVersion = "2026.10.2", compilationTime = "test", manufacturer = "Test", model = "JVM", voiceFeatures = 0,
    )
    private val device = Executors.newSingleThreadScheduledExecutor()
    private val ctx = PaneContext(
        app, prefs = { throw IllegalArgumentException(it) }, identity,
        publishStates = {}, emit = {}, entitiesChanged = {}, sendEvent = { _, _ -> },
        build = AppBuild("1.0.0", 10000, debug = false),
    )
    private val radio = object : ProxyRadio {
        override fun newScanner() = object : BleScanner {
            override fun start(power: ScanPower, listener: BleScanner.Listener) {}
            override fun stop() {}
        }
        override fun newGattRadio() = GattRadio { _, _ -> null }
        override fun unavailable(): ScanFailure? = null
        override fun turnBluetoothOn() = false
        override fun turnLocationOn() = false
    }
    private val servers = ArrayList<ApiServer>()

    @Before fun clean() { file.edit().clear().commit() }
    @After fun stop() { servers.forEach { it.stop() }; device.shutdownNow() }

    /** What Home Assistant reads: DeviceInfo field 18 from the pane of a definition with [read], started on a real server. */
    private fun field18(read: () -> String?): String? {
        val def = BluetoothProxyDefinition(file, radio, readAdapterMac = read)
        val server = ApiServer(0, { identity }, emptyList(), keys = null, bindAddress = InetAddress.getLoopbackAddress()).also { servers += it }
        val pane = def.pane(ctx, device)
        pane.bind(server)
        pane.start()
        server.start()
        return try { deviceInfo(server.localPort).let { if (it.has(18)) it.string(18) else null } } finally { pane.stop() }
    }

    @Test fun noRootHelperNoField() = assertNull(field18 { throw FileNotFoundException("/data/s1kiosk-pub/bluetooth-address") })

    @Test fun aFailingReadNoField() {
        assertNull(field18 { throw IOException("read failed") })
        assertNull(field18 { throw SecurityException("denied") })
    }

    @Test fun noAnswerInTimeNoField() {
        val started = System.nanoTime()
        assertNull(field18 { Thread.sleep(60_000); "02:00:00:00:00:02" })
        assertTrue("gave up after its timeout", System.nanoTime() - started < 10_000_000_000L)
    }

    @Test fun androidsPlaceholderNoField() = assertNull(field18 { "02:00:00:00:00:00" })

    @Test fun nothingOrNonsenseNoField() {
        for (bad in listOf(null, "", "\n", "00:00:00:00:00:00", "FF:FF:FF:FF:FF:FF", "02:00:00:00:00", "02-00-00-00-00-02", "02:00:00:00:00:0G", "02:00:00:00:00:02; x")) {
            assertNull("'$bad'", field18 { bad })
        }
    }

    @Test fun aRealMacIsSentAsHomeAssistantReadsIt() = assertEquals("02:AA:00:00:00:02", field18 { "02:aa:00:00:00:02\n" })

    @Test fun readOnceThenFixedForTheProcess() {
        val reads = AtomicInteger()
        val mac = AdapterMac({ reads.incrementAndGet(); null }, BluetoothProxyPrefs(file))
        assertNull(mac.value)
        assertNull(mac.value)
        assertEquals("a helper that comes up later doesn't change this run's answer", 1, reads.get())
    }

    @Test fun onceFoundItNeverFlipsBack() {
        assertEquals("02:AA:00:00:00:02", field18 { "02:AA:00:00:00:02" })
        assertEquals("a later start whose read fails keeps the MAC HA knows", "02:AA:00:00:00:02", field18 { throw IOException("gone") })
        assertEquals("and a later placeholder can't replace it", "02:AA:00:00:00:02", field18 { "02:00:00:00:00:00" })
    }

    @Test fun aKeptValueThatIsntAMacIsIgnored() {
        BluetoothProxyPrefs(file).adapterMac = "02:00:00:00:00:00"
        assertNull(field18 { null })
    }

    /** A plaintext client: hello, then DeviceInfoRequest (9) → DeviceInfoResponse (10). */
    private fun deviceInfo(port: Int): ProtoMessage = Socket(InetAddress.getLoopbackAddress(), port).use { s ->
        s.soTimeout = 10_000
        val out = s.getOutputStream()
        val inp = DataInputStream(s.getInputStream())
        fun send(type: Int, w: ProtoWriter) {
            val p = w.toByteArray()
            val o = ByteArrayOutputStream()
            o.write(0); varint(o, p.size); varint(o, type); o.write(p)
            out.write(o.toByteArray()); out.flush()
        }
        fun read(): Pair<Int, ByteArray> {
            check(inp.read() == 0)
            val len = varint(inp); val type = varint(inp)
            return type to ByteArray(len).also { inp.readFully(it) }
        }
        send(1, ProtoWriter().string(1, "test").uint32(2, 1).uint32(3, 10))
        while (read().first != 2) Unit
        send(9, ProtoWriter())
        var info: ProtoMessage? = null
        while (info == null) { val (t, d) = read(); if (t == 10) info = ProtoMessage(d) }
        info
    }

    private fun varint(o: ByteArrayOutputStream, v: Int) {
        var x = v
        while (x >= 0x80) { o.write((x and 0x7F) or 0x80); x = x ushr 7 }
        o.write(x)
    }

    private fun varint(i: DataInputStream): Int {
        var v = 0; var shift = 0
        while (true) {
            val b = i.readUnsignedByte()
            v = v or ((b and 0x7F) shl shift)
            if (b < 0x80) return v
            shift += 7
        }
    }
}
