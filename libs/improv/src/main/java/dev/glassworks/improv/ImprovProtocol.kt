package dev.glassworks.improv

/**
 * Improv Wi-Fi via BLE: the GATT UUIDs, capability flags and command ids.
 *
 * Sources (checked 2026-11-09):
 *  - Spec: https://www.improv-wifi.com/ble/ (rev 2.4)
 *  - improv-wifi/sdk-cpp `src/improv.h` / `improv.cpp` (UUIDs, enums, parse_improv_data, build_rpc_response)
 *  - home-assistant-libs py-improv-ble-client 2.0.1 `protocol.py` (what HA's `improv_ble` accepts)
 *  - esphome `components/improv_ble/improv_ble_component.cpp` (reference gadget behaviour)
 *
 * RPC packet (command and result): `[command, dataLength, data..., checksum]`, where the checksum is
 * the sum of all previous bytes, keeping only the low byte. Result data is a list of strings, each
 * `[len, utf8 bytes...]`.
 */
public object Improv {
    /** The Improv GATT service. */
    public const val SERVICE_UUID: String = "00467768-6228-2272-4663-277478268000"
    /** The current state characteristic (read, notify): an [ImprovState] code. */
    public const val STATE_UUID: String = "00467768-6228-2272-4663-277478268001"
    /** The error characteristic (read, notify): an [ImprovError] code. */
    public const val ERROR_UUID: String = "00467768-6228-2272-4663-277478268002"
    /** The RPC command characteristic (write): the client's commands. */
    public const val RPC_COMMAND_UUID: String = "00467768-6228-2272-4663-277478268003"
    /** The RPC result characteristic (read, notify): the device's answers. */
    public const val RPC_RESULT_UUID: String = "00467768-6228-2272-4663-277478268004"
    /** The capabilities characteristic (read): the `CAP_*` flags. */
    public const val CAPABILITIES_UUID: String = "00467768-6228-2272-4663-277478268005"

    /** 16-bit service data UUID 0x4677 (spec rev 2.0), as a full 128-bit UUID. */
    public const val SERVICE_DATA_UUID: String = "00004677-0000-1000-8000-00805f9b34fb"

    // Capabilities bitmask (capabilities characteristic and service data byte 2).
    /** Capability: the device can identify itself (blink, beep, show something). */
    public const val CAP_IDENTIFY: Int = 0x01
    /** Capability: the device answers [CMD_DEVICE_INFO]. */
    public const val CAP_DEVICE_INFO: Int = 0x02
    /** Capability: the device lists the Wi-Fi networks it sees ([CMD_SCAN_WIFI]). */
    public const val CAP_SCAN_WIFI: Int = 0x04
    /** Capability: the device's hostname can be read and set ([CMD_HOSTNAME]). */
    public const val CAP_HOSTNAME: Int = 0x08

    // RPC commands.
    /** Command: join this Wi-Fi network (SSID, password). */
    public const val CMD_WIFI_SETTINGS: Int = 0x01
    /** Command: identify yourself. */
    public const val CMD_IDENTIFY: Int = 0x02
    /** Command: report firmware, version, chip and device name. */
    public const val CMD_DEVICE_INFO: Int = 0x03
    /** Command: list the Wi-Fi networks you see. */
    public const val CMD_SCAN_WIFI: Int = 0x04
    /** Command: read or set the hostname. */
    public const val CMD_HOSTNAME: Int = 0x05
    /** Command: read or set the device name. */
    public const val CMD_DEVICE_NAME: Int = 0x06

    /** Max encoded result: command + length + 255 data bytes + checksum (sdk-cpp RPC_RESPONSE_MAX_SIZE). */
    public const val MAX_PACKET: Int = 258

    internal fun checksum(data: ByteArray, end: Int = data.size): Byte {
        var sum = 0
        for (i in 0 until end) sum += data[i].toInt() and 0xFF
        return sum.toByte()
    }

    /** Encodes an RPC packet whose data is a list of length-prefixed strings (commands and results). */
    internal fun encode(command: Int, strings: List<String>): ByteArray = encodeRaw(command, strings.map { it.toByteArray(Charsets.UTF_8) })

    internal fun encodeRaw(command: Int, strings: List<ByteArray>): ByteArray {
        val dataLen = strings.sumOf { 1 + it.size }
        require(dataLen <= 255) { "RPC data too long ($dataLen > 255)" }
        strings.forEach { require(it.size <= 255) { "string too long" } }
        val out = ByteArray(3 + dataLen)
        out[0] = command.toByte()
        out[1] = dataLen.toByte()
        var p = 2
        for (s in strings) {
            out[p++] = s.size.toByte()
            s.copyInto(out, p)
            p += s.size
        }
        out[p] = checksum(out, p)
        return out
    }

    /** 6-byte service data for UUID 0x4677: state, capabilities, 4 reserved zero bytes. */
    internal fun serviceData(state: ImprovState, capabilities: Int): ByteArray =
        byteArrayOf(state.code.toByte(), capabilities.toByte(), 0, 0, 0, 0)

    /**
     * Parses one complete RPC command packet. Mirrors sdk-cpp parse_improv_data: the length byte must
     * match, the checksum must match, and Wi-Fi settings must hold two strings that fit.
     */
    internal fun parse(packet: ByteArray): RpcCommand {
        if (packet.size < 3 || (packet[1].toInt() and 0xFF) != packet.size - 3) return RpcCommand.Invalid("bad length")
        if (checksum(packet, packet.size - 1) != packet[packet.size - 1]) return RpcCommand.Invalid("bad checksum")
        val cmd = packet[0].toInt() and 0xFF
        val strings = strings(packet) ?: return RpcCommand.Invalid("bad strings")
        return when (cmd) {
            CMD_WIFI_SETTINGS -> if (strings.size == 2) {
                RpcCommand.WifiSettings(strings[0].toString(Charsets.UTF_8), strings[1].toString(Charsets.UTF_8))
            } else RpcCommand.Invalid("wifi settings need 2 strings")
            CMD_IDENTIFY -> RpcCommand.Identify
            CMD_DEVICE_INFO -> RpcCommand.DeviceInfo
            CMD_SCAN_WIFI -> RpcCommand.ScanWifi
            else -> RpcCommand.Unknown(cmd)
        }
    }

    /** Splits the data part into length-prefixed strings; null if they don't tile the data exactly. */
    internal fun strings(packet: ByteArray): List<ByteArray>? {
        val end = packet.size - 1
        var p = 2
        val out = mutableListOf<ByteArray>()
        while (p < end) {
            val len = packet[p].toInt() and 0xFF
            if (p + 1 + len > end) return null
            out += packet.copyOfRange(p + 1, p + 1 + len)
            p += 1 + len
        }
        return if (p == end) out else null
    }

    /**
     * Scan result (spec rev 2.2): ONE result holding triples (SSID, RSSI, auth type), strongest first,
     * truncated to what fits in [maxPacket] bytes (255 data bytes, and the notification MTU).
     * An empty list means no networks.
     */
    internal fun encodeScan(networks: List<ScannedNetwork>, maxPacket: Int = MAX_PACKET): ByteArray {
        val limit = minOf(maxPacket, MAX_PACKET) - 3
        val strings = mutableListOf<ByteArray>()
        var size = 0
        val seen = HashSet<String>()
        for (n in networks.sortedByDescending { it.rssi }) {
            if (n.ssid.isEmpty() || !seen.add(n.ssid)) continue
            val triple = listOf(n.ssid, n.rssi.toString(), n.auth).map { it.toByteArray(Charsets.UTF_8) }
            if (triple[0].size > 32) continue
            val add = triple.sumOf { 1 + it.size }
            if (size + add > limit) break
            strings += triple
            size += add
        }
        return encodeRaw(CMD_SCAN_WIFI, strings)
    }
}

/**
 * One Wi-Fi network from a scan, as Improv reports it.
 *
 * @property ssid The network's name.
 * @property rssi The signal strength in dBm.
 * @property auth The security, as Improv labels it: `NO` for an open network, else labels such as
 *   `WPA2` or `WPA2/WPA3` ([WifiSecurityRules.improvAuth]).
 */
public data class ScannedNetwork(public val ssid: String, public val rssi: Int, public val auth: String)

/** Improv's state, as the state characteristic and the advertisement report it. */
public enum class ImprovState(
    /** The wire value. */
    public val code: Int,
) {
    /** Waiting for a physical action on the device before it takes Wi-Fi settings. */
    AUTHORIZATION_REQUIRED(0x01),
    /** Ready to take Wi-Fi settings. */
    AUTHORIZED(0x02),
    /** Joining the network it was given. */
    PROVISIONING(0x03),
    /** On the network. */
    PROVISIONED(0x04),
}

/** Improv's error, as the error characteristic reports it. */
public enum class ImprovError(
    /** The wire value. */
    public val code: Int,
) {
    /** No error. */
    NONE(0x00),
    /** The command was malformed or its checksum was wrong. */
    INVALID_RPC(0x01),
    /** The device doesn't know the command. */
    UNKNOWN_RPC(0x02),
    /** Joining the network failed. */
    UNABLE_TO_CONNECT(0x03),
    /** The command needs authorization first. */
    NOT_AUTHORIZED(0x04),
    /** The hostname isn't valid. */
    BAD_HOSTNAME(0x05),
    /** Any other failure. */
    UNKNOWN(0xFF),
}

internal sealed class RpcCommand {
    data class WifiSettings(val ssid: String, val password: String) : RpcCommand() {
        override fun toString(): String = "WifiSettings(<${ssid.length} char name>, <${password.length} char password>)" // no network name in a log
    }
    object Identify : RpcCommand()
    object DeviceInfo : RpcCommand()
    object ScanWifi : RpcCommand()
    data class Unknown(val command: Int) : RpcCommand()
    data class Invalid(val reason: String) : RpcCommand()
}

/**
 * Reassembles an RPC command written in several pieces (writes longer than MTU-3 arrive as
 * separate writes; see the spec's "over 20 bytes" note). Same rule as ESPHome
 * process_incoming_data_: wait until `size - 3 == length`, drop the buffer if it overshoots.
 */
internal class RpcAssembler {
    private var buf = ByteArray(0)

    /** Appends [chunk]; returns a complete packet when one is ready, else null. */
    fun add(chunk: ByteArray): ByteArray? {
        if (chunk.isEmpty()) return null
        buf += chunk
        if (buf.size < 3) return null
        val len = buf[1].toInt() and 0xFF
        return when {
            buf.size - 3 == len -> buf.also { buf = ByteArray(0) }
            buf.size - 3 > len -> { buf = ByteArray(0); ByteArray(0) } // overshoot: report as invalid
            else -> null
        }
    }

    fun clear() { buf = ByteArray(0) }
    val pending: Int get() = buf.size
}
