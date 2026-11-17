package dev.glassworks.esphome

/**
 * The Bluetooth proxy's messages (`option (id)` in aioesphomeapi 46.25.0 `api.proto`, the version
 * Home Assistant 2026.11 pins). Only the passive proxy's messages so far: advertisements, no GATT.
 */
internal object BluetoothMsg {
    const val SUBSCRIBE_LE_ADVERTISEMENTS_REQUEST = 66
    const val UNSUBSCRIBE_LE_ADVERTISEMENTS_REQUEST = 87
    const val LE_RAW_ADVERTISEMENTS_RESPONSE = 93
}

/**
 * `BluetoothProxyFeature` (aioesphomeapi model.py), sent as `DeviceInfoResponse`
 * `bluetooth_proxy_feature_flags` (field 15). Home Assistant 2026.11 connects a Bluetooth scanner
 * for a device with any flag set (esphome manager.py). bleak-esphome 4.0.0 (connect.py
 * `connect_scanner`) then subscribes to raw advertisements with [RAW_ADVERTISEMENTS] and makes the
 * scanner connectable with [ACTIVE_CONNECTIONS]. Nothing there reads [PASSIVE_SCAN]; ESPHome always
 * sets it on a proxy (bluetooth_proxy.h), and so does this one.
 */
public object BluetoothProxyFeature {
    /** The proxy scans passively. */
    public const val PASSIVE_SCAN: Int = 1 shl 0
    /** The proxy makes GATT connections for Home Assistant. */
    public const val ACTIVE_CONNECTIONS: Int = 1 shl 1
    /** The proxy caches GATT services. */
    public const val REMOTE_CACHING: Int = 1 shl 2
    /** The proxy pairs with devices. */
    public const val PAIRING: Int = 1 shl 3
    /** The proxy clears its GATT cache on request. */
    public const val CACHE_CLEARING: Int = 1 shl 4
    /** The proxy sends advertisements raw, in batches ([ApiServer.sendBluetoothAdvertisements]). */
    public const val RAW_ADVERTISEMENTS: Int = 1 shl 5
    /** Scanner state pushes and a passive/active switch. Android can't switch its scan type, so we leave it out. */
    public const val STATE_AND_MODE: Int = 1 shl 6
    /** The proxy sets a connection's parameters. */
    public const val CONNECTION_PARAMS_SETTING: Int = 1 shl 7
}

/**
 * One advertisement as the proxy forwards it (`BluetoothLERawAdvertisement`).
 *
 * @property address The 48-bit device address, big-endian (`AA:BB:CC:DD:EE:FF` is `0xAABBCCDDEEFF`).
 * @property rssi The signal strength in dBm.
 * @property addressType ESPHome's `esp_ble_addr_type_t`: 0 public, 1 random.
 * @property data The advertising data and the scan response, as AD structures, at most
 *   [MAX_DATA] bytes.
 */
public class RawAdvertisement(public val address: Long, public val rssi: Int, public val addressType: Int, public val data: ByteArray) {
    /** Limits of the proxy's messages. */
    public companion object {
        /** `fixed_array_size` of `data` in api.proto: a legacy advertisement plus its scan response. */
        public const val MAX_DATA: Int = 62
        /** ESPHome's `BLUETOOTH_PROXY_ADVERTISEMENT_BATCH_SIZE` (bluetooth_proxy/__init__.py). */
        public const val BATCH_SIZE: Int = 16
    }
}

/** `BluetoothLERawAdvertisementsResponse { repeated BluetoothLERawAdvertisement advertisements = 1 }`. */
internal fun rawAdvertisements(batch: List<RawAdvertisement>): ProtoWriter {
    val w = ProtoWriter()
    for (a in batch) {
        // address = 1, rssi = 2, address_type = 3, data = 4
        val adv = ProtoWriter()
            .uint64(1, a.address)
            .sint32(2, a.rssi)
            .uint32(3, a.addressType)
            .bytes(4, a.data, force = true)
        w.message(1, adv)
    }
    return w
}

/**
 * The device side of the Bluetooth proxy. [ApiServer] calls it on connection threads.
 *
 * One client holds the advertisement subscription at a time, as in ESPHome's bluetooth_proxy.cpp
 * `subscribe_api_connection`: a newer subscriber replaces an older one (almost always a stale
 * connection from the same Home Assistant).
 */
public interface BluetoothProxyHandler {
    /** [BluetoothProxyFeature] flags for `DeviceInfoResponse`; 0 hides the proxy from Home Assistant. */
    public val featureFlags: Int

    /** True when the first client subscribes to advertisements, false when the last one leaves. */
    public fun onSubscribed(subscribed: Boolean)

    /**
     * Only an authenticated connection (Noise with the provisioned key) may subscribe, as for
     * [ApiEntity.authOnly]: advertisements carry the addresses of the devices near the panel, and a
     * newer subscriber takes the feed from the older one. On a panel without a key, anyone on the LAN
     * could otherwise listen in plaintext.
     */
    public val authOnly: Boolean get() = true

    /**
     * A client asked to subscribe and was refused for [authOnly]: the device has no key it holds.
     * [clientInfo] is its `HelloRequest.client_info`, so the device can tell Home Assistant from a
     * script on the LAN.
     */
    public fun onRefused(clientInfo: String) {}
}
