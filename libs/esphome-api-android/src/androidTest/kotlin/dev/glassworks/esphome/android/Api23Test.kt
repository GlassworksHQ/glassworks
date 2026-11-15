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
import java.io.ByteArrayOutputStream
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
                s.getOutputStream().write(plaintextFrame(Msg.NOISE_ENCRYPTION_SET_KEY_REQUEST, request))
                val (type, response) = readPlaintextFrame(DataInputStream(s.getInputStream()))
                assertEquals(Msg.NOISE_ENCRYPTION_SET_KEY_RESPONSE, type)
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

    /** A plaintext API frame: `0x00`, varint payload length, varint type, payload. */
    private fun plaintextFrame(type: Int, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0)
        writeVarint(out, payload.size)
        writeVarint(out, type)
        out.write(payload)
        return out.toByteArray()
    }

    /** Reads one plaintext API frame: (type, payload). */
    private fun readPlaintextFrame(input: DataInputStream): Pair<Int, ByteArray> {
        assertEquals("plaintext indicator", 0, input.readUnsignedByte())
        val length = readVarint(input)
        val type = readVarint(input)
        return type to ByteArray(length).also { input.readFully(it) }
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Int) {
        var v = value
        while (v and 0x7F.inv() != 0) {
            out.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        out.write(v)
    }

    private fun readVarint(input: DataInputStream): Int {
        var result = 0
        var shift = 0
        while (true) {
            val b = input.readUnsignedByte()
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
    }
}
