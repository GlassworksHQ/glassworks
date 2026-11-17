/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.bluetoothproxy

import android.content.SharedPreferences

class BluetoothProxyPrefs(private val sp: SharedPreferences) {

    /** The pane is on ([BluetoothProxyDefinition.switch] writes it). Off until someone turns it on (#67, #121). */
    val enabled: Boolean get() = sp.getBoolean(KEY_ENABLED, DEFAULT_ENABLED)

    /**
     * "Listen and connect": Home Assistant may also make GATT connections through the proxy (#246). Off,
     * "Listen only", until someone picks it on the pane's page.
     */
    var connect: Boolean
        get() = sp.getBoolean(KEY_CONNECT, DEFAULT_CONNECT)
        set(value) { sp.edit().putBoolean(KEY_CONNECT, value).apply() }

    /**
     * The Bluetooth adapter's MAC the proxy first sent Home Assistant ([AdapterMac]), kept so the scanner
     * HA keys on it never changes back. Null until one was found. Never logged.
     */
    var adapterMac: String?
        get() = sp.getString(KEY_ADAPTER_MAC, null)
        set(value) { sp.edit().putString(KEY_ADAPTER_MAC, value).apply() }

    companion object {
        /** Panels keep it, and it is the adb extra: never rename it. */
        const val KEY_ENABLED = "bluetooth_proxy_enabled"
        const val DEFAULT_ENABLED = false
        /** Panels keep it: never rename it. */
        const val KEY_CONNECT = "bluetooth_proxy_connect"
        const val DEFAULT_CONNECT = false
        /** Panels keep it: never rename it. */
        const val KEY_ADAPTER_MAC = "bluetooth_proxy_adapter_mac"
    }
}
