package dev.glassworks.improv

import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast

/**
 * The Android version differences this library handles, in one file. `ImprovBleServer` and
 * `WifiProvisioner` ask for a capability here instead of reading `Build.VERSION.SDK_INT`. The
 * library stays standalone, so this is its own copy and does not use the app's `dev.glassworks.platform`.
 * `@ChecksSdkIntAtLeast` lets lint (NewApi) treat each check as a version guard.
 */
internal val Compat = CompatApi(Build.VERSION.SDK_INT)

/** The capabilities for one API [level]; [Compat] is the running device's. Tests pass a level. */
internal class CompatApi(private val level: Int) {
    /**
     * A network `addNetwork` adds lasts past a restart only once the app calls `saveConfiguration` (Android 7 and
     * earlier). From Android 8 the framework saves it itself, and `saveConfiguration` is deprecated.
     */
    val mustSaveNetworks: Boolean get() = level < 26

    /**
     * `getConfiguredNetworks` gives every saved network: always before Android 10. From Android 10 an app that isn't
     * privileged gets an empty list, and without location access a filtered one, so only the device owner with location
     * access gets them all.
     */
    fun listsSavedNetworks(deviceOwner: Boolean, locationAccess: Boolean): Boolean = level < 29 || (deviceOwner && locationAccess)

    /** `Settings.Global.BOOT_COUNT` exists (Android 7). */
    @get:ChecksSdkIntAtLeast(api = 24)
    val hasBootCount: Boolean get() = level >= 24

    /** `AdvertisingSet` exists (Android 8). Below it, advertising uses `startAdvertising` only. */
    @get:ChecksSdkIntAtLeast(api = 26)
    val hasAdvertisingSets: Boolean get() = level >= 26

    /** `LocationManager.isLocationEnabled` exists (Android 9). Below it, the location mode setting says. */
    @get:ChecksSdkIntAtLeast(api = 28)
    val hasIsLocationEnabled: Boolean get() = level >= 28

    /** `WifiNetworkSuggestion` exists, and `addNetwork` is restricted for apps (Android 10). */
    @get:ChecksSdkIntAtLeast(api = 29)
    val hasNetworkSuggestions: Boolean get() = level >= 29

    /** Exactly Android 10: the last version where an app with the overlay permission may still `addNetwork`. */
    val isAndroid10: Boolean get() = level == 29

    /** `WifiConfiguration.setSecurityParams` exists (Android 11). Below it, `requirePMF` is set by reflection. */
    @get:ChecksSdkIntAtLeast(api = 30)
    val hasSecurityParams: Boolean get() = level >= 30

    /** `BLUETOOTH_ADVERTISE` and `BLUETOOTH_CONNECT` are runtime permissions (Android 12). */
    @get:ChecksSdkIntAtLeast(api = 31)
    val hasBluetoothRuntimePermissions: Boolean get() = level >= 31

    /** An app may turn Bluetooth on with `BluetoothAdapter.enable()`. From Android 13 the call does nothing. */
    val appsCanEnableBluetooth: Boolean get() = level < 33

    /** `BluetoothGattServer.notifyCharacteristicChanged` takes the value as an argument (Android 13). */
    @get:ChecksSdkIntAtLeast(api = 33)
    val hasValueNotify: Boolean get() = level >= 33
}
