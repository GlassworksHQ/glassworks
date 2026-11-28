package dev.glassworks.esphome

import org.bouncycastle.math.ec.rfc7748.X25519
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.InetAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The Sendspin pairing token goes only to a connection that came in with the device's key. A client on the all-zeros
 * PSK (a device with no key yet) is Noise too, so "the device has a key now and this is Noise" was not enough: a key
 * that went in while that connection was still open (before activation drops every session) handed it a long-lived
 * credential.
 */
class SendspinTokenZeroPskTest {
    private val identity = DeviceIdentity(
        name = "s1-panel", friendlyName = "S1 Panel", macAddress = "02:11:22:33:44:55",
        esphomeVersion = "2026.10.2", compilationTime = "Glassworks test", manufacturer = "Aqara",
        model = "Panel Hub S1 Plus (Glassworks)", voiceFeatures = 0,
    )
    private val key = ByteArray(32) { (it + 1).toByte() }
    private val token = object : SendspinTokenSource {
        override fun clientIdIfStarted() = "client"
        override fun pairingToken(): Pair<Int, String?> = SendspinApi.STATUS_OK to "SP:0TESTTOKEN"
    }

    private fun ask(c: NoiseClient): Pair<Int, String> {
        c.send(Msg.SENDSPIN_PAIRING_TOKEN_REQUEST)
        val r = ProtoMessage(c.await(Msg.SENDSPIN_PAIRING_TOKEN_RESPONSE).data)
        return r.uint32(1) to r.string(2)
    }

    @Test fun aZeroPskConnectionGetsNoTokenAfterAKeyWentIn() {
        val keys = NoiseKeys(MemoryKeyStore())
        val server = ApiServer(0, { identity }, emptyList(), keys).apply { sendspin = this@SendspinTokenZeroPskTest.token }
        server.start()
        try {
            NoiseClient(server.localPort, NoiseKeys.ZERO_PSK).use { c ->
                assertEquals(SendspinApi.STATUS_ENCRYPTION_REQUIRED to "", ask(c))
                // The device has a key now, and this connection is still open: the moment between a key's activation
                // and the server dropping every session (ApiServer.scheduleActivation, replaceKey).
                keys.replace(key)
                assertEquals(SendspinApi.STATUS_ENCRYPTION_REQUIRED to "", ask(c))
            }
            NoiseClient(server.localPort, key).use { c -> assertEquals(SendspinApi.STATUS_OK to "SP:0TESTTOKEN", ask(c)) }
        } finally {
            server.stop()
        }
    }

    @Test fun aConnectionWithTheKeyGetsTheToken() {
        val server = ApiServer(0, { identity }, emptyList(), psk = key).apply { sendspin = this@SendspinTokenZeroPskTest.token }
        server.start()
        try {
            NoiseClient(server.localPort, key).use { c -> assertEquals(SendspinApi.STATUS_OK to "SP:0TESTTOKEN", ask(c)) }
        } finally {
            server.stop()
        }
    }
}

/**
 * The initiator side of `Noise_NNpsk0_25519_ChaChaPoly_SHA256` as aioesphomeapi speaks it (`_frame_helper/noise.py`),
 * from the Noise spec (rev. 34), standing in for Home Assistant on [psk].
 */
internal class NoiseClient(port: Int, private val psk: ByteArray) : AutoCloseable {
    private val socket = Socket(InetAddress.getLoopbackAddress(), port).apply { soTimeout = 10_000 }
    private val out = socket.getOutputStream()
    private val inp = DataInputStream(socket.getInputStream())
    private val tx: NoiseCipher
    private val rx: NoiseCipher

    private var ck: ByteArray
    private var h: ByteArray
    private var k: NoiseCipher? = null

    init {
        val clientHello = ByteArray(0)
        raw(clientHello)
        val serverHello = readRaw()
        check(serverHello[0].toInt() == 0x01) { "server chose no Noise protocol" }
        val name = ApiServer.NOISE_PROTOCOL.toByteArray(Charsets.US_ASCII)
        h = if (name.size <= 32) name.copyOf(32) else sha256(name)
        ck = h.copyOf()
        mixHash("NoiseAPIInit".toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 0) + clientHello)
        // -> psk, e
        mixKeyAndHash(psk)
        val priv = ByteArray(X25519.SCALAR_SIZE).also { X25519.generatePrivateKey(SecureRandom(), it) }
        val pub = ByteArray(X25519.POINT_SIZE).also { X25519.generatePublicKey(priv, 0, it, 0) }
        mixHash(pub)
        mixKey(pub)
        raw(byteArrayOf(0) + pub + encryptAndHash(ByteArray(0)))
        // <- e, ee
        val reply = readRaw()
        check(reply[0].toInt() == 0) { "handshake refused: ${String(reply, 1, reply.size - 1, Charsets.US_ASCII)}" }
        val re = reply.copyOfRange(1, 33)
        mixHash(re)
        mixKey(re)
        val shared = ByteArray(X25519.POINT_SIZE)
        check(X25519.calculateAgreement(priv, 0, re, 0, shared, 0))
        mixKey(shared)
        decryptAndHash(reply.copyOfRange(33, reply.size))
        val (k1, k2) = NoiseResponder.hkdf(ck, ByteArray(0), 2)
        tx = NoiseCipher(k1)
        rx = NoiseCipher(k2)
        send(Msg.HELLO_REQUEST, ProtoWriter().string(1, "Home Assistant test").uint32(2, 1).uint32(3, 10))
        await(Msg.HELLO_RESPONSE)
    }

    fun send(type: Int, w: ProtoWriter = ProtoWriter()) {
        val p = w.toByteArray()
        raw(tx.encrypt(ByteArray(0), byteArrayOf((type ushr 8).toByte(), type.toByte(), (p.size ushr 8).toByte(), p.size.toByte()) + p))
    }

    /** The next frame of [type], skipping others (pings, states). */
    fun await(type: Int): Frame {
        while (true) {
            val m = rx.decrypt(ByteArray(0), readRaw())
            val t = ((m[0].toInt() and 0xFF) shl 8) or (m[1].toInt() and 0xFF)
            if (t == type) return Frame(t, m.copyOfRange(4, m.size))
        }
    }

    private fun raw(data: ByteArray) {
        val o = ByteArrayOutputStream()
        o.write(0x01); o.write(data.size ushr 8); o.write(data.size and 0xFF); o.write(data)
        out.write(o.toByteArray()); out.flush()
    }

    private fun readRaw(): ByteArray {
        check(inp.read() == 0x01) { "not a Noise frame" }
        return ByteArray(inp.readUnsignedShort()).also { inp.readFully(it) }
    }

    private fun mixHash(data: ByteArray) { h = sha256(h + data) }
    private fun mixKey(ikm: ByteArray) { val (c, key) = NoiseResponder.hkdf(ck, ikm, 2); ck = c; k = NoiseCipher(key) }
    private fun mixKeyAndHash(ikm: ByteArray) { val (c, th, key) = NoiseResponder.hkdf(ck, ikm, 3); ck = c; mixHash(th); k = NoiseCipher(key) }
    private fun encryptAndHash(p: ByteArray) = k!!.encrypt(h, p).also(::mixHash)
    private fun decryptAndHash(c: ByteArray) = k!!.decrypt(h, c).also { mixHash(c) }

    override fun close() = socket.close()

    private companion object {
        fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
    }
}
