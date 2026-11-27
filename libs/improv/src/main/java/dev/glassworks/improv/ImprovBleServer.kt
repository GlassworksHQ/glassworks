package dev.glassworks.improv

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import android.os.ParcelUuid
import android.util.Log
import androidx.annotation.RequiresApi
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Improv GATT service + BLE advertisement on Android's peripheral APIs.
 *
 * Advertisement (spec "Bluetooth LE Advertisement"): the 128-bit service UUID and service data
 * 0x4677 = [state, capabilities, 0, 0, 0, 0] in the SAME advertising PDU (not the scan response):
 * flags 3 + UUID128 18 + service data 10 = exactly 31 bytes. The device name goes in the scan
 * response. HA's `improv_ble` bluetooth matcher needs both the service UUID and the 0x4677 service
 * data (manifest.json), and py-improv-ble-client rejects service data that isn't exactly 6 bytes or
 * whose state isn't 1..4. Below Android 8 the advertisement can't be updated in place, so a state change
 * restarts it, but only once no client is connected (a restart dropped the connected client on Android 6).
 *
 * Characteristics (same properties as ESPHome improv_ble): state R/N, error R/N, RPC command W,
 * RPC result R/N, capabilities R. Notifications are queued one at a time (Android drops a
 * notification sent before onNotificationSent of the previous one).
 *
 * All public methods: main thread. GATT callbacks arrive on binder threads and are posted to [main].
 *
 * It carries bytes only: run an [ImprovSession] on top, feeding it [Listener.onRpcWrite] and
 * publishing its state, errors and results here. Needs the Bluetooth permissions
 * (`BLUETOOTH_ADVERTISE` and `BLUETOOTH_CONNECT` from Android 12).
 *
 * @param ctx Any context.
 * @param main The main thread's handler; callbacks run on it.
 * @param listener Gets the client's writes and connection events.
 */
@SuppressLint("MissingPermission")
public class ImprovBleServer(
    private val ctx: Context,
    private val main: Handler,
    private val listener: Listener,
) {
    /** What the server reports, on the main thread. */
    public interface Listener {
        /** A client wrote [chunk] to the RPC command characteristic; a command may take several writes. */
        public fun onRpcWrite(chunk: ByteArray)
        /** A client at Bluetooth [address] connected. */
        public fun onClientConnected(address: String)
        /** The client at [address] disconnected. */
        public fun onClientDisconnected(address: String)
        /** Advertising started (or failed with a message). */
        public fun onAdvertising(ok: Boolean, detail: String)
    }

    private val manager = ctx.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? = manager?.adapter
    private var server: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var advSet: AdvertisingSet? = null
    private var useLegacyAdvertise = !Compat.hasAdvertisingSets
    private var advertising = false
    private var running = false
    /** Advertising has started once in this run: a later failure is a refresh's, not the start's. */
    private var advertisedOnce = false
    /** A legacy refresh waits for the last client to leave ([updateAdvertisement]). */
    private var refreshPending = false
    /** A failed legacy refresh's tries ([REFRESH_RETRY_MS]): each runs again now, or after the last client leaves. */
    private val refreshRetry = RefreshRetry(main, REFRESH_RETRY_MS) {
        if (running && !advertising) { if (devices.isNotEmpty()) refreshPending = true else restartLegacyAdvertising() }
    }

    private val values = ConcurrentHashMap<UUID, ByteArray>()
    private val chars = HashMap<UUID, BluetoothGattCharacteristic>()
    private val subscribed = ConcurrentHashMap<String, MutableSet<UUID>>()
    private val mtu = ConcurrentHashMap<String, Int>()
    private val devices = ConcurrentHashMap<String, BluetoothDevice>()
    private val prepared = PreparedWrites()

    private data class Pending(val device: BluetoothDevice, val uuid: UUID, val value: ByteArray)
    private val queue = ArrayDeque<Pending>()
    private var inFlight = false
    private val inFlightTimeout = Runnable { inFlight = false; pump() }

    private var state: ImprovState = ImprovState.AUTHORIZATION_REQUIRED
    private var capabilities = 0

    internal val connectedCount: Int get() = devices.size

    /** Smallest (MTU - 3) among connected clients: the largest notification everyone receives whole. */
    public fun maxNotify(): Int = (devices.keys.map { (mtu[it] ?: 23) - 3 }.minOrNull() ?: Improv.MAX_PACKET).coerceAtLeast(20)

    /**
     * One line on what the Bluetooth adapter supports (LE advertising, extended advertising, the
     * permissions), for a log at startup.
     */
    public fun capabilityReport(): String {
        val a = adapter ?: return "no BluetoothAdapter"
        val adv = runCatching { a.bluetoothLeAdvertiser != null }.getOrDefault(false)
        val multi = runCatching { a.isMultipleAdvertisementSupported }.getOrDefault(false)
        val ext = Compat.hasAdvertisingSets && runCatching { a.isLeExtendedAdvertisingSupported }.getOrDefault(false)
        val maxLen = if (Compat.hasAdvertisingSets) runCatching { a.leMaximumAdvertisingDataLength }.getOrDefault(-1) else -1
        return "enabled=${a.isEnabled} isMultipleAdvertisementSupported=$multi bluetoothLeAdvertiser=${if (adv) "present" else "null"} " +
            "leExtendedAdvertising=$ext leMaxAdvDataLength=$maxLen permissions=${permissionsOk()}"
    }

    private fun permissionsOk(): Boolean = if (Compat.hasBluetoothRuntimePermissions) {
        listOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
            .all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    } else true

    private var btReceiver: BroadcastReceiver? = null

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun enableBluetoothThenStart(a: BluetoothAdapter): Boolean {
        if (!Compat.appsCanEnableBluetooth) return false // enable() is a no-op for apps on 33+
        if (btReceiver == null) {
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    if (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) != BluetoothAdapter.STATE_ON) return
                    runCatching { ctx.unregisterReceiver(this) }
                    btReceiver = null
                    Log.i(logTag, "Bluetooth is on: starting Improv")
                    // the session may have moved on (e.g. authorized) while Bluetooth came up
                    if (!start(state, capabilities)) Log.e(logTag, "Improv did not start after Bluetooth came on")
                }
            }
            btReceiver = r
            ctx.registerReceiver(r, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        }
        return runCatching { a.enable() }.getOrDefault(false).also { Log.i(logTag, "BluetoothAdapter.enable() = $it") }
    }

    /**
     * Opens the GATT server and, once the service is registered, starts advertising with the [initial]
     * state and the [caps] capability flags ([Improv] `CAP_*`). If Bluetooth is off, it turns it on
     * where Android allows and starts once it is on. False if it can't start; [Listener.onAdvertising]
     * says why. True if it is running already.
     */
    public fun start(initial: ImprovState, caps: Int): Boolean {
        if (running) return true
        val a = adapter
        if (a == null) { listener.onAdvertising(false, "no Bluetooth adapter"); return false }
        if (!a.isEnabled) {
            // A factory-reset panel boots with Bluetooth off, and Improv is how it gets Wi-Fi: turn it on
            // (BLUETOOTH_ADMIN, API <= 32) and start as soon as it's up. That is a pending start, not a
            // failure: the session (and its Allow button) stays up meanwhile; setState() keeps `state`
            // current so advertising begins with the session's state at that moment.
            state = initial
            capabilities = caps
            if (enableBluetoothThenStart(a)) {
                Log.i(logTag, "Bluetooth is off: turning it on; Improv starts when it is up")
                return true
            }
            listener.onAdvertising(false, "Bluetooth is off and could not be turned on")
            return false
        }
        if (!permissionsOk()) { listener.onAdvertising(false, "missing BLUETOOTH_ADVERTISE/CONNECT"); return false }
        advertiser = a.bluetoothLeAdvertiser
        if (advertiser == null) { listener.onAdvertising(false, "bluetoothLeAdvertiser is null (no LE peripheral mode)"); return false }
        state = initial
        capabilities = caps
        values[U_STATE] = byteArrayOf(initial.code.toByte())
        values[U_ERROR] = byteArrayOf(0)
        values[U_RESULT] = ByteArray(0)
        values[U_CAPS] = byteArrayOf(caps.toByte())
        val s = manager.openGattServer(ctx, callback)
        if (s == null) { listener.onAdvertising(false, "openGattServer returned null"); return false }
        server = s
        running = true
        val service = BluetoothGattService(UUID.fromString(Improv.SERVICE_UUID), BluetoothGattService.SERVICE_TYPE_PRIMARY)
        fun add(uuid: UUID, props: Int, perms: Int, cccd: Boolean) {
            val c = BluetoothGattCharacteristic(uuid, props, perms)
            if (cccd) c.addDescriptor(BluetoothGattDescriptor(CCCD, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
            chars[uuid] = c
            service.addCharacteristic(c)
        }
        val rn = BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY
        val r = BluetoothGattCharacteristic.PERMISSION_READ
        add(U_CAPS, BluetoothGattCharacteristic.PROPERTY_READ, r, cccd = false)
        add(U_STATE, rn, r, cccd = true)
        add(U_ERROR, rn, r, cccd = true)
        add(U_RPC, BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE, cccd = false)
        add(U_RESULT, rn, r, cccd = true)
        if (!s.addService(service)) {
            stop()
            listener.onAdvertising(false, "addService failed")
            return false
        }
        return true // advertising starts in onServiceAdded
    }

    /** Stops advertising, disconnects every client and closes the GATT server. */
    public fun stop() {
        btReceiver?.let { runCatching { ctx.unregisterReceiver(it) } } // a pending start must not fire later
        btReceiver = null
        stopAdvertising()
        main.removeCallbacks(inFlightTimeout)
        refreshRetry.reset()
        queue.clear()
        inFlight = false
        server?.let { s ->
            devices.values.forEach { runCatching { s.cancelConnection(it) } }
            runCatching { s.clearServices() }
            runCatching { s.close() }
        }
        server = null
        running = false
        advertisedOnce = false
        refreshPending = false
        devices.clear(); subscribed.clear(); mtu.clear(); prepared.clear(); chars.clear()
    }

    /** Publishes the state: notifies subscribed clients and updates the advertisement. */
    public fun setState(s: ImprovState) {
        state = s
        setValue(U_STATE, byteArrayOf(s.code.toByte()))
        updateAdvertisement()
    }

    /** Publishes the error to subscribed clients. */
    public fun setError(e: ImprovError): Unit = setValue(U_ERROR, byteArrayOf(e.code.toByte()))

    /** Publishes an RPC result packet to subscribed clients. */
    public fun sendResult(packet: ByteArray): Unit = setValue(U_RESULT, packet)

    private fun setValue(uuid: UUID, v: ByteArray) {
        values[uuid] = v
        for ((addr, subs) in subscribed) {
            if (uuid !in subs) continue
            val d = devices[addr] ?: continue
            queue.addLast(Pending(d, uuid, v))
        }
        pump()
    }

    private fun pump() {
        if (inFlight) return
        val p = queue.removeFirstOrNull() ?: return
        val s = server ?: return
        val c = chars[p.uuid] ?: return
        inFlight = true
        val ok = if (Compat.hasValueNotify) {
            s.notifyCharacteristicChanged(p.device, c, false, p.value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            c.value = p.value
            @Suppress("DEPRECATION")
            s.notifyCharacteristicChanged(p.device, c, false)
        }
        if (!ok) Log.w(logTag, "notify ${short(p.uuid)} to ${p.device.address} failed")
        main.postDelayed(inFlightTimeout, if (ok) 300 else 50)
    }

    // ---- advertising

    private fun advData(): AdvertiseData = AdvertiseData.Builder()
        .setIncludeDeviceName(false)
        .setIncludeTxPowerLevel(false)
        .addServiceUuid(ParcelUuid.fromString(Improv.SERVICE_UUID))
        .addServiceData(ParcelUuid.fromString(Improv.SERVICE_DATA_UUID), Improv.serviceData(state, capabilities))
        .build()

    private fun scanResponse(): AdvertiseData = AdvertiseData.Builder().setIncludeDeviceName(true).build()

    private fun startAdvertising() {
        val adv = advertiser ?: return
        val cb = setCallback
        if (Compat.hasAdvertisingSets && cb != null && !useLegacyAdvertise) {
            val params = AdvertisingSetParameters.Builder()
                .setLegacyMode(true)
                .setConnectable(true)
                .setScannable(true)
                .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
                .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_MEDIUM)
                .build()
            try {
                adv.startAdvertisingSet(params, advData(), scanResponse(), null, null, cb)
                return
            } catch (e: Exception) {
                Log.w(logTag, "startAdvertisingSet threw; using startAdvertising", e)
                useLegacyAdvertise = true
            }
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()
        runCatching { adv.startAdvertising(settings, advData(), scanResponse(), legacyCallback) }
            .onFailure { listener.onAdvertising(false, "startAdvertising threw: $it") }
    }

    private fun stopAdvertising() {
        val adv = advertiser ?: return
        val cb = setCallback
        if (Compat.hasAdvertisingSets && cb != null) runCatching { adv.stopAdvertisingSet(cb) }
        runCatching { adv.stopAdvertising(legacyCallback) }
        advSet = null
        advertising = false
    }

    private fun updateAdvertisement() {
        if (!running) return
        val set = advSet
        if (Compat.hasAdvertisingSets && set != null) {
            set.setAdvertisingData(advData())
        } else if (useLegacyAdvertise && (advertising || advertisedOnce)) {
            // a failed refresh's retry is on its way, and starts with the data as it is then
            if (!advertising && refreshRetry.pending) return
            // The legacy API can't update in place, so the new service data means stop and start again. Not while a
            // client is connected: on Android 6 stopping the advertiser dropped the connected client ~270 ms later
            // (Mirror, 2026-11-25), so the Wi-Fi result never reached it. The client has the state from the
            // characteristic; the advertisement catches up when the last client leaves.
            if (devices.isNotEmpty()) {
                refreshPending = true
                return
            }
            restartLegacyAdvertising()
        }
    }

    private fun restartLegacyAdvertising() {
        refreshPending = false
        runCatching { advertiser?.stopAdvertising(legacyCallback) }
        advertising = false
        startAdvertising()
    }

    // Null below Android 8: creating an AdvertisingSetCallback there throws NoClassDefFoundError.
    private val setCallback: AdvertisingSetCallback? = if (Compat.hasAdvertisingSets) advertisingSetCallback() else null

    @RequiresApi(26)
    private fun advertisingSetCallback() = object : AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, status: Int) {
            main.post {
                if (status == ADVERTISE_SUCCESS && set != null) {
                    if (!running) { runCatching { advertiser?.stopAdvertisingSet(this) }; return@post }
                    advSet = set
                    advertising = true
                    listener.onAdvertising(true, "AdvertisingSet (legacy PDU), txPower=$txPower dBm")
                } else {
                    Log.w(logTag, "startAdvertisingSet failed: status=$status; falling back to startAdvertising")
                    useLegacyAdvertise = true
                    if (running) startAdvertising()
                }
            }
        }

        override fun onAdvertisingDataSet(set: AdvertisingSet?, status: Int) {
            if (status != ADVERTISE_SUCCESS) Log.w(logTag, "setAdvertisingData failed: $status")
        }

        override fun onAdvertisingSetStopped(set: AdvertisingSet?) {
            Log.i(logTag, "advertising set stopped")
        }
    }

    private val legacyCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            main.post {
                advertising = true
                advertisedOnce = true
                refreshRetry.reset()
                listener.onAdvertising(true, "startAdvertising (legacy API)")
            }
        }

        override fun onStartFailure(errorCode: Int) {
            main.post {
                val why = "startAdvertising failed: ${advertiseError(errorCode)}"
                when {
                    !advertisedOnce -> listener.onAdvertising(false, why)
                    !running -> Unit
                    // A refresh that fails leaves the GATT server and its clients up but nothing advertised: try again
                    // a few times, then say so, so setup doesn't claim it can still be found (#944)
                    else -> {
                        val wait = refreshRetry.failed()
                        if (wait != null) Log.w(logTag, "$why (refreshing the advertisement; try ${refreshRetry.tries} of ${refreshRetry.size} in $wait ms)")
                        else listener.onAdvertising(false, "$why (refreshing the advertisement, after ${refreshRetry.size} tries)")
                    }
                }
            }
        }
    }

    // ---- GATT server callbacks (binder threads)

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            main.post {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(logTag, "GATT service added")
                    if (running) startAdvertising()
                } else {
                    listener.onAdvertising(false, "onServiceAdded status=$status")
                }
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val addr = device.address
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                devices[addr] = device
                main.post { listener.onClientConnected(addr) }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                devices.remove(addr); subscribed.remove(addr); mtu.remove(addr); prepared.drop(addr)
                main.post {
                    listener.onClientDisconnected(addr)
                    // Some stacks stop legacy advertising once a central connected; make sure it runs.
                    if (Compat.hasAdvertisingSets && running) runCatching { advSet?.enableAdvertising(true, 0, 0) }
                    // A legacy refresh held back while clients were connected runs now, with the current state.
                    if (running && refreshPending && devices.isEmpty()) restartLegacyAdvertising()
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, m: Int) {
            mtu[device.address] = m
            Log.i(logTag, "MTU ${device.address} = $m")
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, c: BluetoothGattCharacteristic) {
            val v = values[c.uuid] ?: ByteArray(0)
            val s = server ?: return
            if (offset > v.size) {
                s.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
            } else {
                s.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, v.copyOfRange(offset, v.size))
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, c: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            val s = server ?: return
            val v = value ?: ByteArray(0)
            if (c.uuid != U_RPC) {
                if (responseNeeded) s.sendResponse(device, requestId, BluetoothGatt.GATT_WRITE_NOT_PERMITTED, offset, null)
                return
            }
            if (preparedWrite) {
                // Long write: queue the pieces until onExecuteWrite. Response echoes the value. One longer than any
                // Improv command is dropped whole, so a client can't grow it without end.
                if (!prepared.append(device.address, v)) {
                    Log.w(logTag, "${device.address} long write over ${PreparedWrites.MAX_BYTES} bytes: dropped")
                    if (responseNeeded) s.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH, offset, null)
                    return
                }
                if (responseNeeded) s.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, v)
                return
            }
            if (responseNeeded) s.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            main.post { listener.onRpcWrite(v) }
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            val v = prepared.take(device.address)
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            if (execute && v != null) main.post { listener.onRpcWrite(v) }
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, d: BluetoothGattDescriptor) {
            val on = subscribed[device.address]?.contains(d.characteristic.uuid) == true
            val v = if (on) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, v)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, d: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            if (d.uuid == CCCD) {
                val subs = subscribed.getOrPut(device.address) { Collections.newSetFromMap(ConcurrentHashMap<UUID, Boolean>()) }
                val enable = value != null && value.isNotEmpty() && (value[0].toInt() and 0x03) != 0
                if (enable) subs += d.characteristic.uuid else subs -= d.characteristic.uuid
                Log.i(logTag, "${device.address} ${if (enable) "subscribed to" else "unsubscribed from"} ${short(d.characteristic.uuid)}")
            }
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            main.post {
                main.removeCallbacks(inFlightTimeout)
                inFlight = false
                pump()
            }
        }
    }

    /** Settings shared by every server. */
    public companion object {
        /** Logcat tag for this server; set it before starting to use your app's own. */
        @Volatile public var logTag: String = "ImprovBle"
        /** How long a failed legacy refresh waits before each new try; after the last, the server reports the failure. */
        internal val REFRESH_RETRY_MS: LongArray = longArrayOf(1_000L, 2_000L, 4_000L)
        internal val U_STATE: UUID = UUID.fromString(Improv.STATE_UUID)
        internal val U_ERROR: UUID = UUID.fromString(Improv.ERROR_UUID)
        internal val U_RPC: UUID = UUID.fromString(Improv.RPC_COMMAND_UUID)
        internal val U_RESULT: UUID = UUID.fromString(Improv.RPC_RESULT_UUID)
        internal val U_CAPS: UUID = UUID.fromString(Improv.CAPABILITIES_UUID)
        internal val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private fun short(u: UUID) = u.toString().takeLast(4)

        internal fun advertiseError(code: Int) = when (code) {
            AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "DATA_TOO_LARGE"
            AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "TOO_MANY_ADVERTISERS"
            AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "ALREADY_STARTED"
            AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR"
            AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED"
            else -> "error $code"
        }
    }
}
