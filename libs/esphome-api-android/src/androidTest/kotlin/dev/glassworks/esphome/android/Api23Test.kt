package dev.glassworks.esphome.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import dev.glassworks.esphome.ApiServer
import dev.glassworks.esphome.DeviceIdentity
import dev.glassworks.esphome.MemoryKeyStore
import dev.glassworks.esphome.Msg
import dev.glassworks.esphome.NoiseCipher
import dev.glassworks.esphome.NoiseKeys
import dev.glassworks.esphome.ProtoMessage
import dev.glassworks.esphome.ProtoWriter
import java.io.DataInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/** esphome-api and this module on the device they run on; CI runs it on an API 23 emulator (#151). */
@RunWith(AndroidJUnit4::class)
class Api23Test {
    private val key = ByteArray(32) { (it + 1).toByte() }
    private val identity = DeviceIdentity("t", "T", "02:00:00:00:00:01", "2026.10.2", "", "Me", "Test", 0)

    @Test fun aKeyFromHomeAssistantIsAccepted() {
        val store = MemoryKeyStore()
        val loopback = InetAddress.getByName("127.0.0.1")
        val server = ApiServer(0, { identity }, emptyList(), NoiseKeys(store), loopback)
        try {
            server.start()
            Socket().use { s ->
                s.connect(InetSocketAddress(loopback, server.localPort), 2_000)
                s.soTimeout = 5_000
                // NoiseEncryptionSetKeyRequest { key = base64 of the 32-byte key }, as Home Assistant sends it.
                val request = ProtoWriter().bytes(1, "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA=".toByteArray()).toByteArray()
                s.getOutputStream().write(byteArrayOf(0, request.size.toByte(), Msg.NOISE_ENCRYPTION_SET_KEY_REQUEST.toByte()) + request)
                val input = s.getInputStream()
                val header = ByteArray(3).also { DataInputStream(input).readFully(it) }
                assertEquals(Msg.NOISE_ENCRYPTION_SET_KEY_RESPONSE, header[2].toInt())
                val response = ByteArray(header[1].toInt()).also { DataInputStream(input).readFully(it) }
                assertTrue("the key is accepted", ProtoMessage(response).bool(1))
            }
            assertArrayEquals(key, store.load())
        } finally {
            server.stop()
        }
    }

    @Test fun noiseCipherRoundTrips() {
        val sealed = NoiseCipher(key).encrypt(byteArrayOf(), "hello".toByteArray())
        assertEquals("hello", String(NoiseCipher(key).decrypt(byteArrayOf(), sealed)))
    }

    @Test fun theServerAnswersHelloAndIsAdvertised() {
        val loopback = InetAddress.getByName("127.0.0.1")
        val server = ApiServer(0, { identity }, emptyList(), NoiseKeys(MemoryKeyStore()), loopback)
        val mdns = EspHomeMdns(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            server.start()
            mdns.start(identity, server)
            Socket().use { s ->
                s.connect(InetSocketAddress(loopback, server.localPort), 2_000)
                s.soTimeout = 5_000
                s.getOutputStream().write(byteArrayOf(0, 4, Msg.HELLO_REQUEST.toByte(), 0x0a, 2, 't'.code.toByte(), 't'.code.toByte()))
                assertEquals("Hello is answered with a plaintext frame", 0, s.getInputStream().read())
            }
        } finally {
            mdns.stop()
            server.stop()
        }
    }
}
