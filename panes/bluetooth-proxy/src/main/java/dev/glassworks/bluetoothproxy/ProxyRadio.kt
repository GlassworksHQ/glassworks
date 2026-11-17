/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.bluetoothproxy

import android.content.Context
import android.provider.Settings
import dev.glassworks.esphome.bluetoothproxy.AndroidBleScanner
import dev.glassworks.esphome.bluetoothproxy.AndroidGattRadio
import dev.glassworks.esphome.bluetoothproxy.BleScanner
import dev.glassworks.esphome.bluetoothproxy.GattRadio
import dev.glassworks.esphome.bluetoothproxy.RecentDevices
import dev.glassworks.esphome.bluetoothproxy.ScanFailure

/** The device's Bluetooth as the pane uses it: [AndroidProxyRadio] on a panel, a fake in tests. */
interface ProxyRadio {
    /**
     * A scanner of its own for one engine. Not shared: a closing engine stops and releases its
     * scanner on its own thread, after the next engine may have started (#226 review).
     */
    fun newScanner(): BleScanner
    /** GATT connections, to the devices the scanners heard. */
    fun newGattRadio(): GattRadio
    /** Why a scan can't run now, or null. Cheap, any thread. */
    fun unavailable(): ScanFailure?
    /** Turns Bluetooth on. False where Android doesn't let apps. */
    fun turnBluetoothOn(): Boolean
    /** Turns location on. False unless the app may write secure settings. */
    fun turnLocationOn(): Boolean
}

class AndroidProxyRadio(context: Context) : ProxyRadio {
    private val app = context.applicationContext
    /** For the questions only ([unavailable], [turnBluetoothOn]); it never scans. */
    private val android = AndroidBleScanner(app)
    /** What every scanner heard, for the connections: a connect uses the device from a scan result. */
    private val recent = RecentDevices()
    override fun newScanner(): BleScanner = AndroidBleScanner(app, recent)
    override fun newGattRadio(): GattRadio = AndroidGattRadio(app, recent)
    override fun unavailable() = android.unavailable()
    override fun turnBluetoothOn() = android.turnBluetoothOn()

    /**
     * Location on, as `adb shell settings put secure location_mode 3` does. Needs WRITE_SECURE_SETTINGS,
     * which the installer grants on the S1 (a panel with no Settings app to do it by hand).
     */
    @Suppress("DEPRECATION")
    override fun turnLocationOn(): Boolean = try {
        Settings.Secure.putInt(app.contentResolver, Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_HIGH_ACCURACY)
    } catch (_: SecurityException) {
        false
    }
}
