package dev.glassworks.esphome.bluetoothproxy

import dev.glassworks.esphome.GattService

/** GATT connections: [AndroidGattRadio] on a device, a fake in tests. */
fun interface GattRadio {
    /**
     * Starts connecting to [address] (48-bit, big-endian). Null if it can't start (Bluetooth off, no
     * permission). The link then reports through [listener], on any thread.
     */
    fun connect(address: Long, listener: GattLink.Listener): GattLink?
}

/**
 * One link. Android runs one GATT operation at a time per link, so the engine never starts one before
 * the last one's callback. Each start returns false if Android refused it; no callback follows then.
 * Handles are ATT handles, from [services].
 */
interface GattLink {
    fun requestMtu(mtu: Int): Boolean
    fun discoverServices(): Boolean
    /** After [Listener.onServicesDiscovered]: the services, with their handles. */
    fun services(): List<GattService>
    fun read(handle: Int): Boolean
    fun write(handle: Int, data: ByteArray, withResponse: Boolean): Boolean
    fun readDescriptor(handle: Int): Boolean
    fun writeDescriptor(handle: Int, data: ByteArray): Boolean
    /** Turns notifications for a characteristic on or off on this side only. No callback. */
    fun setNotify(handle: Int, enable: Boolean): Boolean
    /** Asks the device to disconnect: [Listener.onConnectionState] false follows. */
    fun disconnect()
    /** Frees the link at once. No callback follows. */
    fun close()

    /** Android's callbacks, on any thread. A status is Android's GATT status: 0 is success. */
    interface Listener {
        fun onConnectionState(connected: Boolean, status: Int)
        fun onMtu(mtu: Int, status: Int)
        fun onServicesDiscovered(status: Int)
        fun onRead(handle: Int, data: ByteArray, status: Int)
        fun onWritten(handle: Int, status: Int)
        fun onDescriptorRead(handle: Int, data: ByteArray, status: Int)
        fun onDescriptorWritten(handle: Int, status: Int)
        fun onNotification(handle: Int, data: ByteArray)
    }
}
