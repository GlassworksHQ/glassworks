package dev.glassworks.esphome

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.util.Base64
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * #337 against the real client Home Assistant 2026.11 pins (aioesphomeapi), via ESPHOME_PYTHON as in
 * [EspHomeKeepaliveTest]. HA reads the Bluetooth proxy's flags from DeviceCapabilities (API 1.15+),
 * and a time answer with only `parsed_timezone` still reaches [ApiServer.onTimeZone]: for every IANA
 * zone, the rule the server rebuilds is the one aioesphomeapi parsed it from.
 */
class EspHomeCapsTimeTest {
    private val identity = DeviceIdentity(
        name = "test-panel", friendlyName = "Test Panel", macAddress = "02:00:00:00:00:01",
        esphomeVersion = "2026.10.2", compilationTime = "test", manufacturer = "Test", model = "JVM",
        voiceFeatures = 0,
    )
    private val proxy = object : BluetoothProxyHandler {
        override val featureFlags = BluetoothProxyFeature.PASSIVE_SCAN or BluetoothProxyFeature.RAW_ADVERTISEMENTS
        override fun onSubscribed(subscribed: Boolean) = Unit
    }

    @Test
    fun homeAssistantsClientReadsTheProxyFlagsAndTheParsedZone() {
        val python = System.getenv("ESPHOME_PYTHON")
        assumeTrue("set ESPHOME_PYTHON to a python with aioesphomeapi installed", !python.isNullOrBlank())

        val key = ByteArray(32) { it.toByte() }
        val zones = Collections.synchronizedList(ArrayList<String>())
        val s = ApiServer(0, { identity }, emptyList(), NoiseKeys(MemoryKeyStore(key)), InetAddress.getLoopbackAddress())
        s.bluetooth = proxy
        s.onTimeZone = { zones += it }
        s.start()
        try {
            val script = File(javaClass.classLoader!!.getResource("esphome_caps_time.py")!!.toURI())
            val proc = ProcessBuilder(python, "-I", script.absolutePath, s.localPort.toString(),
                Base64.getEncoder().encodeToString(key)).redirectErrorStream(true).start()
            val out = StringBuilder()
            val reader = Thread {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(out) { out.appendLine(line) }
                    if (line == "SENT") {
                        proc.outputStream.bufferedWriter().apply { write(JSONArray(zones.toList()).toString()); newLine(); flush() }
                    }
                }
            }.apply { isDaemon = true; start() }
            val finished = try { proc.waitFor(90, TimeUnit.SECONDS) } finally { if (proc.isAlive) proc.destroyForcibly() }
            reader.join(5_000)
            val log = synchronized(out) { out.toString() }
            assertTrue("client timed out:\n$log", finished)
            assertEquals("client failed:\n$log", 0, proc.exitValue())
            val r = JSONObject(log.lines().first { it.startsWith("RESULT ") }.removePrefix("RESULT "))
            assertEquals("1.20", r.getString("api_version"))
            assertEquals(proxy.featureFlags, r.getInt("bluetooth_proxy_feature_flags"))
            assertEquals("with both, the string wins", r.getString("first_sent"), r.getString("first"))
            assertTrue("a few hundred zones, got ${r.getInt("zones")}", r.getInt("zones") > 300)
            assertEquals(r.getInt("zones"), r.getInt("rebuilt"))
            assertEquals("rebuilt rules aioesphomeapi reads differently: ${r.getJSONArray("mismatches")}", 0, r.getInt("mismatch_count"))
        } finally {
            s.stop()
        }
    }
}
