package dev.glassworks.esphome.bluetoothproxy

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import dev.glassworks.esphome.EspHomeLog
import dev.glassworks.esphome.GattCharacteristic
import dev.glassworks.esphome.GattDescriptor
import dev.glassworks.esphome.GattService
import java.util.IdentityHashMap

/**
 * The devices the scanner heard last, so a connection uses the `BluetoothDevice` from a scan result.
 * Android 10 hides a device's address type from apps, but the device it hands over in a scan result
 * carries it. A device not heard recently falls back to `getRemoteDevice`, which assumes a public
 * address. In memory only, the last [MAX] devices; never stored or logged. Thread-safe.
 */
class RecentDevices {
    private val devices = object : LinkedHashMap<Long, BluetoothDevice>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, BluetoothDevice>) = size > MAX
    }

    @Synchronized fun heard(address: Long, device: BluetoothDevice) { devices[address] = device }
    @Synchronized operator fun get(address: Long): BluetoothDevice? = devices[address]

    private companion object {
        const val MAX = 256
    }
}

/**
 * [GattRadio] on Android's `BluetoothGatt`: `connectGatt(…, TRANSPORT_LE)` on the device the scanner
 * heard ([RecentDevices]). Handles are ATT handles: a service's and a characteristic's instance id is
 * its handle from Android 7 on (the characteristic's is its value handle, as ESP-IDF's `char_handle`).
 * A descriptor's id is hidden from apps, so its handle comes from the ATT layout: a characteristic's
 * descriptors are the attributes right after its value handle, in order.
 *
 * Android 6 (the Mirror) has no handles to give: an instance id there only tells attributes with the
 * same UUID apart, so it is 0 for nearly all of them. [GattTable] numbers the attributes itself then.
 *
 * It never pairs. A device that asks for encryption may still make Android start pairing by itself.
 */
class AndroidGattRadio(context: Context, private val recent: RecentDevices) : GattRadio {
    private val app = context.applicationContext
    private val adapter: BluetoothAdapter? = app.getSystemService(BluetoothManager::class.java)?.adapter

    @SuppressLint("MissingPermission") // checked: Compat.connectPermissions
    override fun connect(address: Long, listener: GattLink.Listener): GattLink? {
        val a = adapter ?: return null
        if (!a.isEnabled) return null
        if (Compat.connectPermissions.any { app.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) return null
        val device = recent[address] ?: try { a.getRemoteDevice(formatAddress(address)) } catch (_: IllegalArgumentException) { return null }
        val link = AndroidGattLink(listener)
        val gatt = try {
            device.connectGatt(app, false, link.callback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            EspHomeLog.w("Bluetooth proxy: connecting refused: ${e.message}")
            null
        } ?: return null
        link.gatt = gatt
        return link
    }
}

/**
 * The handles a link's services were given, both ways.
 *
 * [numbered]: Android gives no handles (below Android 7, where every instance id would collide and
 * Home Assistant would see one service with one characteristic). The table then numbers the attributes
 * from 1 in the order the ATT table lays them out: a service, then for each characteristic its
 * declaration, its value (the characteristic's handle) and its descriptors. The numbers aren't the
 * device's real handles, but each is unique and stable for the link, which is all a client needs.
 */
internal class GattTable(
    services: List<BluetoothGattService>,
    numbered: Boolean = !Compat.gattInstanceIdsAreHandles,
) {
    val characteristics = HashMap<Int, BluetoothGattCharacteristic>()
    val characteristicHandles = IdentityHashMap<BluetoothGattCharacteristic, Int>()
    val descriptors = HashMap<Int, BluetoothGattDescriptor>()
    val descriptorHandles = IdentityHashMap<BluetoothGattDescriptor, Int>()
    private var next = 1
    val services: List<GattService> = services.map { s ->
        val serviceHandle = if (numbered) next++ else s.instanceId
        GattService(s.uuid, serviceHandle, s.characteristics.map { c ->
            if (numbered) next++ // the characteristic's declaration
            val handle = if (numbered) next++ else c.instanceId
            characteristics[handle] = c
            characteristicHandles[c] = handle
            GattCharacteristic(c.uuid, handle, c.properties, c.descriptors.mapIndexed { i, d ->
                val descriptorHandle = if (numbered) next++ else handle + 1 + i
                descriptors[descriptorHandle] = d
                descriptorHandles[d] = descriptorHandle
                GattDescriptor(d.uuid, descriptorHandle)
            })
        })
    }
}

/** One `BluetoothGatt`. Android's callbacks come on a binder thread; the engine moves them to its own. */
@SuppressLint("MissingPermission") // the radio checked before it connected
@Suppress("DEPRECATION") // the pre-Android 13 calls; still there on 13+, and the only ones below it
internal class AndroidGattLink(private val listener: GattLink.Listener) : GattLink {
    @Volatile var gatt: BluetoothGatt? = null
    @Volatile internal var table = GattTable(emptyList()) // internal: a test gives it a table without a discovery

    val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) =
            listener.onConnectionState(status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED, status)

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) = listener.onMtu(mtu, status)

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) table = GattTable(g.services)
            listener.onServicesDiscovered(status)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            listener.onRead(table.characteristicHandles[c] ?: return, c.value ?: ByteArray(0), status)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            listener.onWritten(table.characteristicHandles[c] ?: return, status)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            listener.onNotification(table.characteristicHandles[c] ?: return, c.value ?: ByteArray(0))
        }

        override fun onDescriptorRead(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            listener.onDescriptorRead(table.descriptorHandles[d] ?: return, d.value ?: ByteArray(0), status)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            listener.onDescriptorWritten(table.descriptorHandles[d] ?: return, status)
        }
    }

    override fun requestMtu(mtu: Int) = gatt?.requestMtu(mtu) == true
    override fun discoverServices() = gatt?.discoverServices() == true
    override fun services() = table.services

    override fun read(handle: Int): Boolean {
        val c = table.characteristics[handle] ?: return false
        return gatt?.readCharacteristic(c) == true
    }

    override fun write(handle: Int, data: ByteArray, withResponse: Boolean): Boolean {
        val c = table.characteristics[handle] ?: return false
        c.writeType = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        c.value = data
        return gatt?.writeCharacteristic(c) == true
    }

    override fun readDescriptor(handle: Int): Boolean {
        val d = table.descriptors[handle] ?: return false
        return gatt?.readDescriptor(d) == true
    }

    override fun writeDescriptor(handle: Int, data: ByteArray): Boolean {
        val d = table.descriptors[handle] ?: return false
        d.value = data
        return gatt?.writeDescriptor(d) == true
    }

    override fun setNotify(handle: Int, enable: Boolean): Boolean {
        val c = table.characteristics[handle] ?: return false
        return gatt?.setCharacteristicNotification(c, enable) == true
    }

    override fun disconnect() { try { gatt?.disconnect() } catch (_: RuntimeException) {} }

    override fun close() { try { gatt?.close() } catch (_: RuntimeException) {} }
}

/** The 48-bit number as `AA:BB:CC:DD:EE:FF`, for `getRemoteDevice`. Locale.ROOT: an Arabic or Persian locale would write other digits. */
internal fun formatAddress(address: Long): String =
    (5 downTo 0).joinToString(":") { String.format(java.util.Locale.ROOT, "%02X", (address ushr (it * 8)) and 0xFF) }
