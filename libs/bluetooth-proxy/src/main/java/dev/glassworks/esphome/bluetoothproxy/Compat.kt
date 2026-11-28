package dev.glassworks.esphome.bluetoothproxy

import android.Manifest
import android.annotation.SuppressLint
import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast

/**
 * The Android version differences this library handles, in one file. The library stays standalone,
 * so this is its own copy and does not use the app's `dev.glassworks.platform`.
 */
internal val Compat = CompatApi(Build.VERSION.SDK_INT)

/**
 * The runtime permissions the proxy needs on this device: to scan ([CompatApi.scanPermissions]) and to
 * connect (`BLUETOOTH_CONNECT`, Android 12+). An app asks for them while the proxy is on; the manifest
 * entries come with the library.
 */
val bluetoothProxyPermissions: Set<String> get() = Compat.runtimePermissions

/** The capabilities for one API [level]; [Compat] is the running device's. Tests pass a level. */
internal class CompatApi(private val level: Int) {
    /** `LocationManager.isLocationEnabled` exists (Android 9). Below it, the location mode setting says. */
    @get:ChecksSdkIntAtLeast(api = 28)
    val hasIsLocationEnabled: Boolean get() = level >= 28

    /**
     * A GATT service's and a characteristic's instance id is its ATT handle (Android 7). Below it an instance
     * id only tells attributes with the same UUID apart, so it is 0 for nearly all of them (the Mirror,
     * Android 6.0.1: frames/lululemon-mirror/research/gatt-handles-android6.md).
     */
    @get:ChecksSdkIntAtLeast(api = 24)
    val gattInstanceIdsAreHandles: Boolean get() = level >= 24

    /** An app may turn Bluetooth on with `BluetoothAdapter.enable()`. From Android 13 the call does nothing. */
    val appsCanEnableBluetooth: Boolean get() = level < 33

    /**
     * The runtime permissions a scan needs to get results: COARSE location up to Android 9, FINE from
     * Android 10 (GattService `checkCallerHasFineLocation`), plus `BLUETOOTH_SCAN` from Android 12.
     */
    @get:SuppressLint("InlinedApi") // a string constant, asked for only on Android 12+
    val scanPermissions: List<String> get() = when {
        level >= 31 -> listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.ACCESS_FINE_LOCATION)
        level >= 29 -> listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        else -> listOf(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    /** The runtime permissions a GATT connection needs: `BLUETOOTH_CONNECT` from Android 12; none below (install-time). */
    @get:SuppressLint("InlinedApi") // a string constant, asked for only on Android 12+
    val connectPermissions: List<String> get() = if (level >= 31) listOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyList()

    /** Everything the proxy asks for at runtime: [scanPermissions] and [connectPermissions]. */
    val runtimePermissions: Set<String> get() = (scanPermissions + connectPermissions).toSet()
}
