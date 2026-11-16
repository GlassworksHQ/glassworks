package dev.glassworks.esphome

import java.util.UUID

/**
 * The Bluetooth proxy's connection messages (`option (id)` in aioesphomeapi 46.25.0 `api.proto`).
 * Pairing (85, 86) and cache clearing (88) are only refused: their flags aren't sent.
 */
internal object BluetoothConnectionMsg {
    const val DEVICE_REQUEST = 68
    const val DEVICE_CONNECTION_RESPONSE = 69
    const val GATT_GET_SERVICES_REQUEST = 70
    const val GATT_GET_SERVICES_RESPONSE = 71
    const val GATT_GET_SERVICES_DONE_RESPONSE = 72
    const val GATT_READ_REQUEST = 73
    const val GATT_READ_RESPONSE = 74
    const val GATT_WRITE_REQUEST = 75
    const val GATT_READ_DESCRIPTOR_REQUEST = 76
    const val GATT_WRITE_DESCRIPTOR_REQUEST = 77
    const val GATT_NOTIFY_REQUEST = 78
    const val GATT_NOTIFY_DATA_RESPONSE = 79
    const val SUBSCRIBE_CONNECTIONS_FREE_REQUEST = 80
    const val CONNECTIONS_FREE_RESPONSE = 81
    const val GATT_ERROR_RESPONSE = 82
    const val GATT_WRITE_RESPONSE = 83
    const val GATT_NOTIFY_RESPONSE = 84
    const val DEVICE_PAIRING_RESPONSE = 85
    const val DEVICE_UNPAIRING_RESPONSE = 86
    const val DEVICE_CLEAR_CACHE_RESPONSE = 88

    /** `BluetoothDeviceRequestType`. */
    const val REQUEST_CONNECT_V1 = 0
    const val REQUEST_DISCONNECT = 1
    const val REQUEST_PAIR = 2
    const val REQUEST_UNPAIR = 3
    const val REQUEST_CONNECT_V3_WITH_CACHE = 4
    const val REQUEST_CONNECT_V3_WITHOUT_CACHE = 5
    const val REQUEST_CLEAR_CACHE = 6
}

/**
 * A request from Home Assistant to the proxy's connections.
 *
 * @property address The device's 48-bit address, big-endian, as in [RawAdvertisement.address].
 */
public sealed class GattRequest(public val address: Long) {
    /**
     * A request about one attribute.
     *
     * @property handle Its ATT handle, from [GattEvent.Services].
     */
    public sealed class Handle(address: Long, public val handle: Int) : GattRequest(address)

    /**
     * Connect (`BluetoothDeviceRequest` CONNECT_V3_*). Answer with [GattEvent.Connection] once the
     * link is up and its services are known, or failed.
     *
     * @property addressType 0 public, 1 random.
     * @property withCache Home Assistant has the services cached and won't ask for them.
     */
    public class Connect(address: Long, public val addressType: Int, public val withCache: Boolean) : GattRequest(address)

    /** Disconnect. Answer with a [GattEvent.Connection] that says not connected. */
    public class Disconnect(address: Long) : GattRequest(address)

    /** Send the services: one or more [GattEvent.Services], then [GattEvent.ServicesDone]. */
    public class GetServices(address: Long) : GattRequest(address)

    /** Read a characteristic: [GattEvent.Read] or [GattEvent.Error]. */
    public class Read(address: Long, handle: Int) : Handle(address, handle)

    /**
     * Write a characteristic: [GattEvent.Written] (also without [withResponse]) or [GattEvent.Error].
     *
     * @property data The value to write.
     * @property withResponse A write request, which the device acknowledges; else a write command.
     */
    public class Write(address: Long, handle: Int, public val data: ByteArray, public val withResponse: Boolean) : Handle(address, handle)

    /** Read a descriptor: [GattEvent.Read] or [GattEvent.Error]. */
    public class ReadDescriptor(address: Long, handle: Int) : Handle(address, handle)

    /**
     * Write a descriptor: [GattEvent.Written] or [GattEvent.Error].
     *
     * @property data The value to write.
     */
    public class WriteDescriptor(address: Long, handle: Int, public val data: ByteArray) : Handle(address, handle)

    /**
     * Turn notifications for a characteristic on or off on the proxy's side: [GattEvent.NotifySet] or
     * [GattEvent.Error]. With remote caching, Home Assistant writes the CCCD itself
     * (bleak-esphome client.py `start_notify`).
     *
     * @property enable On or off.
     */
    public class Notify(address: Long, handle: Int, public val enable: Boolean) : Handle(address, handle)
}

/**
 * A GATT descriptor.
 *
 * @property uuid Its type.
 * @property handle Its ATT handle.
 */
public class GattDescriptor(public val uuid: UUID, public val handle: Int)

/**
 * A GATT characteristic.
 *
 * @property uuid Its type.
 * @property handle Its value's ATT handle, as ESP-IDF's `char_handle`.
 * @property properties The Bluetooth characteristic properties bits (read 0x02, write 0x08, notify 0x10, …).
 * @property descriptors Its descriptors, in handle order.
 */
public class GattCharacteristic(
    public val uuid: UUID,
    public val handle: Int,
    public val properties: Int,
    public val descriptors: List<GattDescriptor>,
)

/**
 * A GATT service.
 *
 * @property uuid Its type.
 * @property handle Its first ATT handle.
 * @property characteristics Its characteristics, in handle order.
 */
public class GattService(public val uuid: UUID, public val handle: Int, public val characteristics: List<GattCharacteristic>)

/** What the proxy tells Home Assistant about its connections, through [ApiServer.sendBluetoothEvent]. */
public sealed class GattEvent {
    /**
     * A link's state (`BluetoothDeviceConnectionResponse`).
     *
     * @property address The device.
     * @property connected Up, with its services known.
     * @property mtu The ATT MTU while connected, else 0.
     * @property error While not connected: 0 for a disconnect that was asked for, else a code such as [CONN_TIMEOUT].
     */
    public class Connection(public val address: Long, public val connected: Boolean, public val mtu: Int, public val error: Int) : GattEvent()

    /**
     * Some of a device's services (`BluetoothGATTGetServicesResponse`).
     *
     * @property address The device.
     * @property services The services in this message.
     */
    public class Services(public val address: Long, public val services: List<GattService>) : GattEvent()

    /**
     * All services sent (`BluetoothGATTGetServicesDoneResponse`).
     *
     * @property address The device.
     */
    public class ServicesDone(public val address: Long) : GattEvent()

    /**
     * A characteristic's or a descriptor's value (`BluetoothGATTReadResponse`).
     *
     * @property address The device.
     * @property handle The attribute read.
     * @property data Its value.
     */
    public class Read(public val address: Long, public val handle: Int, public val data: ByteArray) : GattEvent()

    /**
     * A write is done (`BluetoothGATTWriteResponse`).
     *
     * @property address The device.
     * @property handle The attribute written.
     */
    public class Written(public val address: Long, public val handle: Int) : GattEvent()

    /**
     * A notification turned on or off (`BluetoothGATTNotifyResponse`).
     *
     * @property address The device.
     * @property handle The characteristic.
     */
    public class NotifySet(public val address: Long, public val handle: Int) : GattEvent()

    /**
     * A notification or an indication from the device (`BluetoothGATTNotifyDataResponse`).
     *
     * @property address The device.
     * @property handle The characteristic.
     * @property data Its new value.
     */
    public class Notification(public val address: Long, public val handle: Int, public val data: ByteArray) : GattEvent()

    /**
     * A request failed (`BluetoothGATTErrorResponse`).
     *
     * @property address The device.
     * @property handle The attribute, or 0 for a request about none.
     * @property error An ATT error code (1–255), or [NOT_CONNECTED].
     */
    public class Error(public val address: Long, public val handle: Int, public val error: Int) : GattEvent()

    /**
     * The slot table (`BluetoothConnectionsFreeResponse`).
     *
     * @property free Slots free now.
     * @property limit Slots in all.
     * @property allocated The addresses that hold the others.
     */
    public class ConnectionsFree(public val free: Int, public val limit: Int, public val allocated: List<Long>) : GattEvent()

    /** Error codes Home Assistant reads (aioesphomeapi core.py `ESPHOME_GATT_ERRORS`, ble_defs.py). */
    public companion object {
        /** ESPHome's own: no link to that address (`GATT_ERR_NOT_CONNECTED`). */
        public const val NOT_CONNECTED: Int = -1
        /** The link timed out (`ESP_GATT_CONN_TIMEOUT`, the HCI code Android reports too). */
        public const val CONN_TIMEOUT: Int = 0x08
        /** The proxy ended the link (`ESP_GATT_CONN_TERMINATE_LOCAL_HOST`). */
        public const val CONN_TERMINATE_LOCAL_HOST: Int = 0x16
        /** The link didn't come up (`ESP_GATT_CONN_FAIL_ESTABLISH`). */
        public const val CONN_FAIL_ESTABLISH: Int = 0x3E
        /** A generic GATT failure (`ESP_GATT_ERROR`, Android's `GATT_ERROR` 133). */
        public const val GATT_ERROR: Int = 0x85
    }
}

/**
 * The proxy's active connections. Set it as [ApiServer.bluetoothConnections] next to
 * [ApiServer.bluetooth]: the server then adds [BluetoothProxyFeature.ACTIVE_CONNECTIONS] and
 * [BluetoothProxyFeature.REMOTE_CACHING] to the flags. aioesphomeapi 46.25.0 connects only through a
 * proxy with REMOTE_CACHING (client.py `bluetooth_device_connect`), so both go together.
 *
 * Requests come only from the client that holds the advertisement subscription, so they follow
 * [BluetoothProxyHandler.authOnly]; answers go to that client, as in ESPHome's bluetooth_proxy.cpp.
 * The server calls this on connection threads and under a lock: don't block.
 */
public interface BluetoothConnectionHandler {
    /** The slot table now, for a client that subscribes to it (`SubscribeBluetoothConnectionsFreeRequest`). */
    public fun connectionsFree(): GattEvent.ConnectionsFree

    /** A request from the subscribed client. Answer with [ApiServer.sendBluetoothEvent]. */
    public fun onRequest(request: GattRequest)

    /**
     * The advertisement subscriber changed or left. Close every link: they belong to a client that
     * no longer gets their events (ESPHome `disconnect_all_connections_`).
     */
    public fun onSubscriberChanged()
}

/** The connection request in a message of [type], or null if it isn't one. */
internal fun gattRequest(type: Int, m: ProtoMessage): GattRequest? {
    val address = m.long(1)
    return when (type) {
        BluetoothConnectionMsg.DEVICE_REQUEST -> when (m.uint32(2)) {
            BluetoothConnectionMsg.REQUEST_CONNECT_V3_WITH_CACHE -> GattRequest.Connect(address, m.uint32(4), withCache = true)
            BluetoothConnectionMsg.REQUEST_CONNECT_V3_WITHOUT_CACHE -> GattRequest.Connect(address, m.uint32(4), withCache = false)
            BluetoothConnectionMsg.REQUEST_DISCONNECT -> GattRequest.Disconnect(address)
            else -> null
        }
        BluetoothConnectionMsg.GATT_GET_SERVICES_REQUEST -> GattRequest.GetServices(address)
        BluetoothConnectionMsg.GATT_READ_REQUEST -> GattRequest.Read(address, m.uint32(2))
        BluetoothConnectionMsg.GATT_WRITE_REQUEST -> GattRequest.Write(address, m.uint32(2), m.bytes(4), m.bool(3))
        BluetoothConnectionMsg.GATT_READ_DESCRIPTOR_REQUEST -> GattRequest.ReadDescriptor(address, m.uint32(2))
        BluetoothConnectionMsg.GATT_WRITE_DESCRIPTOR_REQUEST -> GattRequest.WriteDescriptor(address, m.uint32(2), m.bytes(3))
        BluetoothConnectionMsg.GATT_NOTIFY_REQUEST -> GattRequest.Notify(address, m.uint32(2), m.bool(3))
        else -> null
    }
}

/**
 * The answer to a `BluetoothDeviceRequest` the proxy doesn't do: v1 connect (removed from ESPHome),
 * pair, unpair, clear cache. Each says it failed with [GattEvent.NOT_CONNECTED], as an ESPHome build
 * without that support does, so the client doesn't wait for its timeout. Null for the other types.
 */
internal fun unsupportedDeviceRequest(m: ProtoMessage): Frame? {
    val address = m.long(1)
    fun failed(type: Int) = Frame(type, ProtoWriter().uint64(1, address).bool(2, false).int32(3, GattEvent.NOT_CONNECTED).toByteArray())
    return when (m.uint32(2)) {
        BluetoothConnectionMsg.REQUEST_CONNECT_V1 -> GattEvent.Connection(address, false, 0, GattEvent.NOT_CONNECTED).frame()
        BluetoothConnectionMsg.REQUEST_PAIR -> failed(BluetoothConnectionMsg.DEVICE_PAIRING_RESPONSE)
        BluetoothConnectionMsg.REQUEST_UNPAIR -> failed(BluetoothConnectionMsg.DEVICE_UNPAIRING_RESPONSE)
        BluetoothConnectionMsg.REQUEST_CLEAR_CACHE -> failed(BluetoothConnectionMsg.DEVICE_CLEAR_CACHE_RESPONSE)
        else -> null
    }
}

/** The answer for a [request] from a client that may not make it: not connected. */
internal fun refusal(request: GattRequest): GattEvent = when (request) {
    is GattRequest.Connect, is GattRequest.Disconnect -> GattEvent.Connection(request.address, false, 0, GattEvent.NOT_CONNECTED)
    is GattRequest.GetServices -> GattEvent.Error(request.address, 0, GattEvent.NOT_CONNECTED)
    is GattRequest.Handle -> GattEvent.Error(request.address, request.handle, GattEvent.NOT_CONNECTED)
}

internal fun GattEvent.frame(): Frame = when (this) {
    is GattEvent.Connection -> Frame(
        BluetoothConnectionMsg.DEVICE_CONNECTION_RESPONSE,
        ProtoWriter().uint64(1, address).bool(2, connected).uint32(3, mtu).int32(4, error).toByteArray(),
    )
    is GattEvent.Services -> Frame(
        BluetoothConnectionMsg.GATT_GET_SERVICES_RESPONSE,
        ProtoWriter().uint64(1, address).apply { services.forEach { message(2, it.encode()) } }.toByteArray(),
    )
    is GattEvent.ServicesDone -> Frame(BluetoothConnectionMsg.GATT_GET_SERVICES_DONE_RESPONSE, ProtoWriter().uint64(1, address).toByteArray())
    is GattEvent.Read -> handleFrame(BluetoothConnectionMsg.GATT_READ_RESPONSE, address, handle) { bytes(3, data) }
    is GattEvent.Written -> handleFrame(BluetoothConnectionMsg.GATT_WRITE_RESPONSE, address, handle) {}
    is GattEvent.NotifySet -> handleFrame(BluetoothConnectionMsg.GATT_NOTIFY_RESPONSE, address, handle) {}
    is GattEvent.Notification -> handleFrame(BluetoothConnectionMsg.GATT_NOTIFY_DATA_RESPONSE, address, handle) { bytes(3, data) }
    is GattEvent.Error -> handleFrame(BluetoothConnectionMsg.GATT_ERROR_RESPONSE, address, handle) { int32(3, error) }
    is GattEvent.ConnectionsFree -> Frame(
        BluetoothConnectionMsg.CONNECTIONS_FREE_RESPONSE,
        ProtoWriter().uint32(1, free).uint32(2, limit).apply { allocated.forEach { uint64(3, it) } }.toByteArray(),
    )
}

private inline fun handleFrame(type: Int, address: Long, handle: Int, rest: ProtoWriter.() -> Unit) =
    Frame(type, ProtoWriter().uint64(1, address).uint32(2, handle).apply(rest).toByteArray())

/**
 * `repeated uint64 uuid = 1`: the high 64 bits, then the low (aioesphomeapi model.py `_join_split_uuid`).
 * Packed, so a zero half keeps its place: [ProtoWriter.uint64] leaves zeros out.
 */
private fun ProtoWriter.uuid(u: UUID): ProtoWriter {
    val packed = java.io.ByteArrayOutputStream(20)
    for (half in longArrayOf(u.mostSignificantBits, u.leastSignificantBits)) {
        var v = half
        while (v and 0x7FL.inv() != 0L) { packed.write(((v and 0x7F) or 0x80).toInt()); v = v ushr 7 }
        packed.write(v.toInt())
    }
    return bytes(1, packed.toByteArray(), force = true)
}

private fun GattService.encode(): ProtoWriter = ProtoWriter().uuid(uuid).uint32(2, handle).apply {
    for (c in characteristics) {
        val cw = ProtoWriter().uuid(c.uuid).uint32(2, c.handle).uint32(3, c.properties)
        for (d in c.descriptors) cw.message(4, ProtoWriter().uuid(d.uuid).uint32(2, d.handle))
        message(3, cw)
    }
}
