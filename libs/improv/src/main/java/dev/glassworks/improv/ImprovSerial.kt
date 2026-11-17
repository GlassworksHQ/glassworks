package dev.glassworks.improv

/**
 * Improv Serial (https://www.improv-wifi.com/serial/): the packet types, commands, states and errors.
 * Pure Kotlin and transport-agnostic: feed bytes to [SerialPacketReader] and write what
 * [ImprovSerialSession] sends to any byte stream (a UART, a socket, an adb stream). S1 Kiosk carries
 * it over adb ([ImprovAdbServer]); the browser client is kiln/packages/improv-serial (keep in sync).
 *
 * Packet: `"IMPROV" | version 1 | type | length | data[length] | checksum`, the checksum being the
 * low byte of the sum of all previous bytes. RPC data is `[command, length, payload...]`, the
 * payload being length-prefixed UTF-8 strings (as over BLE, minus the inner checksum).
 */
public object ImprovSerial {
    internal val HEADER: ByteArray = "IMPROV".toByteArray(Charsets.US_ASCII)
    /** The protocol version every packet carries. */
    public const val VERSION: Int = 1

    /** Packet type: the device's current state, one of the `STATE_*` values. */
    public const val TYPE_STATE: Int = 0x01
    /** Packet type: an error, one of the `ERROR_*` values. */
    public const val TYPE_ERROR: Int = 0x02
    /** Packet type: an RPC command from the client. */
    public const val TYPE_RPC: Int = 0x03
    /** Packet type: the device's answer to an RPC command. */
    public const val TYPE_RPC_RESULT: Int = 0x04

    /** RPC: join this Wi-Fi network (SSID, password). */
    public const val RPC_WIFI_SETTINGS: Int = 0x01
    /** RPC: send the current state. */
    public const val RPC_REQUEST_STATE: Int = 0x02
    /** RPC: send firmware, version, chip and device name. */
    public const val RPC_REQUEST_INFO: Int = 0x03
    /** RPC: list the Wi-Fi networks the device sees. */
    public const val RPC_SCAN_WIFI: Int = 0x04

    /** State: ready to take Wi-Fi settings. */
    public const val STATE_READY: Int = 0x02
    /** State: joining the network. */
    public const val STATE_PROVISIONING: Int = 0x03
    /** State: on the network. */
    public const val STATE_PROVISIONED: Int = 0x04

    /** Error: the packet or the command was malformed. */
    public const val ERROR_INVALID_RPC: Int = 0x01
    /** Error: the device doesn't know the command. */
    public const val ERROR_UNKNOWN_RPC: Int = 0x02
    /** Error: joining the network failed. */
    public const val ERROR_UNABLE_TO_CONNECT: Int = 0x03
    /** Error: any other failure. */
    public const val ERROR_UNKNOWN: Int = 0xFF

    /** Header (6) + version + type + length. */
    private const val PREFIX = 9

    internal fun frame(type: Int, data: ByteArray): ByteArray {
        require(data.size <= 255) { "Improv packet too large (${data.size})" }
        val out = ByteArray(PREFIX + data.size + 1)
        HEADER.copyInto(out)
        out[6] = VERSION.toByte()
        out[7] = type.toByte()
        out[8] = data.size.toByte()
        data.copyInto(out, PREFIX)
        out[out.size - 1] = Improv.checksum(out, out.size - 1)
        return out
    }

    /** `[command, length, strings...]` as RPC commands and results carry it. */
    internal fun rpcData(command: Int, strings: List<String>): ByteArray {
        val parts = strings.map { it.toByteArray(Charsets.UTF_8) }
        parts.forEach { require(it.size <= 255) { "Improv string longer than 255 bytes" } }
        val len = parts.sumOf { 1 + it.size }
        require(len <= 253) { "Improv RPC data too long ($len)" }
        val out = ByteArray(2 + len)
        out[0] = command.toByte()
        out[1] = len.toByte()
        var p = 2
        for (s in parts) {
            out[p++] = s.size.toByte()
            s.copyInto(out, p)
            p += s.size
        }
        return out
    }

    internal fun state(state: Int): ByteArray = frame(TYPE_STATE, byteArrayOf(state.toByte()))
    internal fun error(code: Int): ByteArray = frame(TYPE_ERROR, byteArrayOf(code.toByte()))
    internal fun result(command: Int, strings: List<String>): ByteArray = frame(TYPE_RPC_RESULT, rpcData(command, strings))
    /** An RPC command packet with [strings] as its arguments, as a client sends it: for a client or a test. */
    public fun rpc(command: Int, strings: List<String> = emptyList()): ByteArray = frame(TYPE_RPC, rpcData(command, strings))

    /** Data of an RPC (or RPC result) packet: command, length, then length-prefixed strings. */
    internal fun parseRpc(data: ByteArray): SerialRpc {
        if (data.size < 2) return SerialRpc.Invalid("short RPC")
        val cmd = data[0].toInt() and 0xFF
        val len = data[1].toInt() and 0xFF
        if (len != data.size - 2) return SerialRpc.Invalid("RPC length $len != ${data.size - 2}")
        val strings = mutableListOf<String>()
        var p = 2
        while (p < data.size) {
            val n = data[p].toInt() and 0xFF
            if (p + 1 + n > data.size) return SerialRpc.Invalid("string overruns the RPC")
            strings += String(data, p + 1, n, Charsets.UTF_8)
            p += 1 + n
        }
        return when (cmd) {
            RPC_WIFI_SETTINGS -> if (strings.size == 2) SerialRpc.WifiSettings(strings[0], strings[1])
            else SerialRpc.Invalid("Wi-Fi settings need 2 strings, got ${strings.size}")
            RPC_REQUEST_STATE -> SerialRpc.RequestState
            RPC_REQUEST_INFO -> SerialRpc.RequestInfo
            RPC_SCAN_WIFI -> SerialRpc.ScanWifi
            else -> SerialRpc.Unknown(cmd)
        }
    }

    /**
     * Scan results as the client reads them: one RPC result `[ssid, rssi, "YES"|"NO"]` per network
     * (hidden SSIDs skipped, one per SSID keeping the strongest, strongest first, SSIDs over 32
     * bytes skipped), then an empty result that ends the list.
     */
    internal fun scanResults(networks: List<SerialNetwork>): List<ByteArray> {
        val best = LinkedHashMap<String, SerialNetwork>()
        for (n in networks) {
            if (n.ssid.isEmpty() || n.ssid.toByteArray(Charsets.UTF_8).size > 32) continue
            val prev = best[n.ssid]
            if (prev == null || n.rssi > prev.rssi) best[n.ssid] = n
        }
        return best.values.sortedByDescending { it.rssi }
            .map { result(RPC_SCAN_WIFI, listOf(it.ssid, it.rssi.toString(), if (it.secure) "YES" else "NO")) } +
            result(RPC_SCAN_WIFI, emptyList())
    }

    /**
     * The answer to [RPC_REQUEST_INFO], for [ImprovSerialSession.Effects.info]. Each string is clipped
     * so the four fit one result (253 bytes of strings).
     */
    public fun info(firmware: String, version: String, chip: String, name: String): ByteArray =
        result(RPC_REQUEST_INFO, listOf(firmware, version, chip, name).map { clip(it, 60) })

    private fun clip(s: String, maxBytes: Int): String {
        var out = s
        while (out.toByteArray(Charsets.UTF_8).size > maxBytes) out = out.dropLast(1)
        return out
    }
}

/**
 * One Wi-Fi network as the serial scan reports it.
 *
 * @property ssid The network's name.
 * @property rssi The signal strength in dBm.
 * @property secure The network needs a password.
 */
public data class SerialNetwork(public val ssid: String, public val rssi: Int, public val secure: Boolean)

internal sealed class SerialRpc {
    data class WifiSettings(val ssid: String, val password: String) : SerialRpc() {
        override fun toString(): String = "WifiSettings(<${ssid.length} char name>, <${password.length} char password>)" // no network name in a log
    }
    object RequestState : SerialRpc()
    object RequestInfo : SerialRpc()
    object ScanWifi : SerialRpc()
    data class Unknown(val command: Int) : SerialRpc()
    data class Invalid(val reason: String) : SerialRpc()
}

/**
 * Accumulates bytes and yields complete packets. Garbage before a header is skipped silently; a
 * complete packet with a bad checksum or version is reported as [Item.Bad] and the reader resyncs on
 * the next header (as the client's PacketReader does). Not thread-safe.
 *
 * @param maxBuffer The most bytes it holds while waiting for a complete packet; past that it drops them.
 */
public class SerialPacketReader(private val maxBuffer: Int = 4096) {
    /** What [push] found in the bytes. */
    public sealed class Item {
        /**
         * A complete packet with a good checksum.
         *
         * @property type An [ImprovSerial] `TYPE_*` value.
         * @property data The packet's data.
         */
        public data class Packet(public val type: Int, public val data: ByteArray) : Item() {
            /** Equal when the type and the data's bytes are. */
            override fun equals(other: Any?): Boolean = other is Packet && other.type == type && other.data.contentEquals(data)
            /** From the type and the data's bytes, as [equals] compares them. */
            override fun hashCode(): Int = type * 31 + data.contentHashCode()
        }

        /**
         * A complete packet that failed its checksum or version check.
         *
         * @property reason Which check failed, for a log.
         */
        public data class Bad(public val reason: String) : Item()
    }

    private var buf = ByteArray(0)

    /** Adds the first [len] bytes of [chunk]; returns the packets they completed, in order. */
    public fun push(chunk: ByteArray, len: Int = chunk.size): List<Item> {
        buf += chunk.copyOf(len)
        val out = mutableListOf<Item>()
        while (true) {
            val start = findHeader()
            if (start < 0) {
                // Keep a possible partial header at the end.
                buf = buf.copyOfRange(maxOf(0, buf.size - (ImprovSerial.HEADER.size - 1)), buf.size)
                break
            }
            if (start > 0) buf = buf.copyOfRange(start, buf.size)
            if (buf.size < 9) break
            val total = 9 + (buf[8].toInt() and 0xFF) + 1
            if (buf.size < total) break
            val okSum = Improv.checksum(buf, total - 1) == buf[total - 1]
            val okVersion = (buf[6].toInt() and 0xFF) == ImprovSerial.VERSION
            if (okSum && okVersion) {
                out += Item.Packet(buf[7].toInt() and 0xFF, buf.copyOfRange(9, total - 1))
                buf = buf.copyOfRange(total, buf.size)
            } else {
                out += Item.Bad(if (!okSum) "bad checksum" else "version ${buf[6].toInt() and 0xFF}")
                buf = buf.copyOfRange(1, buf.size) // resync on the next header
            }
        }
        if (buf.size > maxBuffer) buf = ByteArray(0)
        return out
    }

    private fun findHeader(): Int {
        val h = ImprovSerial.HEADER
        outer@ for (i in 0..buf.size - h.size) {
            for (j in h.indices) if (buf[i + j] != h[j]) continue@outer
            return i
        }
        return -1
    }
}

/**
 * One Improv Serial connection (pure; the Android side is behind [Effects]). Thread-safe: bytes come
 * in on the connection's thread, Wi-Fi outcomes on the main thread.
 *
 * - Starts in Ready (authorized): the USB connection, authorized with the installer's adb key, is
 *   the physical authorization. If the panel is already on Wi-Fi, the state is Provisioned instead
 *   (as ESPHome's improv_serial does), so the installer can say so; Wi-Fi settings are still taken.
 * - Wi-Fi settings: state Provisioning, then Provisioned + an RPC result with the redirect URL, or
 *   error 0x03 (unable to connect) and back to Ready.
 * - Request state: the state (+ the redirect URL as an RPC result when provisioned).
 * - Request info: firmware, version, chip, device name.
 * - Scan: one result per network, then an empty result ([ImprovSerial.scanResults]).
 * - A malformed packet gets error 0x01, an unknown command 0x02. Passwords are never logged.
 *
 * @param fx The device side: sending, Wi-Fi and device info.
 * @param log Takes the session's log lines.
 */
public class ImprovSerialSession(private val fx: Effects, private val log: (String) -> Unit = {}) {
    /** What the session needs from the device. */
    public interface Effects {
        /** Writes one packet to the client (may throw if the client is gone). */
        public fun send(packet: ByteArray)
        /** The panel is on Wi-Fi now (for the initial state). */
        public fun online(): Boolean
        /** Where to continue setup once on Wi-Fi ("" = none). */
        public fun redirectUrl(): String
        /** The device info packet: [ImprovSerial.info]. */
        public fun info(): ByteArray
        /** Fresh Wi-Fi scan; may block for a few seconds. */
        public fun scan(): List<SerialNetwork>
        /** Starts joining; [done] (any thread) with the redirect URL on success, null on failure. */
        public fun connect(ssid: String, password: String, done: (redirect: String?) -> Unit)
    }

    private val reader = SerialPacketReader()
    private var seq = 0
    private var redirect = ""

    /** The session's [ImprovSerial] `STATE_*` value, or -1 until the client first asks for it. */
    @Volatile public var state: Int = -1
        private set

    private fun ensureState() {
        if (state == -1) state = if (fx.online()) ImprovSerial.STATE_PROVISIONED else ImprovSerial.STATE_READY
    }

    /** Bytes from the client. */
    public fun onBytes(chunk: ByteArray, len: Int = chunk.size) {
        for (item in reader.push(chunk, len)) {
            when (item) {
                is SerialPacketReader.Item.Bad -> { log("bad packet: ${item.reason}"); sendError(ImprovSerial.ERROR_INVALID_RPC) }
                is SerialPacketReader.Item.Packet ->
                    if (item.type != ImprovSerial.TYPE_RPC) {
                        log("unexpected packet type ${item.type}")
                        sendError(ImprovSerial.ERROR_INVALID_RPC)
                    } else handle(ImprovSerial.parseRpc(item.data))
            }
        }
    }

    private fun handle(rpc: SerialRpc) {
        log("rpc $rpc")
        when (rpc) {
            is SerialRpc.Invalid -> sendError(ImprovSerial.ERROR_INVALID_RPC)
            is SerialRpc.Unknown -> sendError(ImprovSerial.ERROR_UNKNOWN_RPC)
            SerialRpc.RequestState -> synchronized(this) {
                ensureState()
                send(ImprovSerial.state(state))
                if (state == ImprovSerial.STATE_PROVISIONED) {
                    send(ImprovSerial.result(ImprovSerial.RPC_REQUEST_STATE, urls(redirect.ifEmpty { fx.redirectUrl() })))
                }
            }
            SerialRpc.RequestInfo -> send(fx.info())
            SerialRpc.ScanWifi -> {
                val packets = ImprovSerial.scanResults(fx.scan())
                synchronized(this) { packets.forEach { send(it) } }
            }
            is SerialRpc.WifiSettings -> wifi(rpc)
        }
    }

    private fun wifi(rpc: SerialRpc.WifiSettings) {
        val bytes = rpc.ssid.toByteArray(Charsets.UTF_8).size
        if (bytes == 0 || bytes > 32) return sendError(ImprovSerial.ERROR_INVALID_RPC)
        val mine = synchronized(this) {
            ensureState()
            state = ImprovSerial.STATE_PROVISIONING
            send(ImprovSerial.state(state))
            ++seq
        }
        fx.connect(rpc.ssid, rpc.password) { url -> onWifiResult(mine, url) }
    }

    private fun onWifiResult(attempt: Int, url: String?) = synchronized(this) {
        if (attempt != seq) return@synchronized // a newer request on this connection owns the state
        if (url != null) {
            redirect = url
            state = ImprovSerial.STATE_PROVISIONED
            send(ImprovSerial.state(state))
            send(ImprovSerial.result(ImprovSerial.RPC_WIFI_SETTINGS, urls(url)))
        } else {
            state = ImprovSerial.STATE_READY
            send(ImprovSerial.error(ImprovSerial.ERROR_UNABLE_TO_CONNECT))
            send(ImprovSerial.state(state))
        }
    }

    private fun urls(url: String) = listOf(url).filter { it.isNotEmpty() }

    private fun sendError(code: Int) = synchronized(this) { send(ImprovSerial.error(code)) }

    private fun send(p: ByteArray) {
        try { fx.send(p) } catch (e: Exception) { log("send failed: ${e.message}") }
    }
}
