/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.platform

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.net.wifi.WifiManager

/** The Wi-Fi radio as the setup hotspot needs it (#197). */
object WifiRadio {
    /**
     * The radio is off, and this app can't turn it on ([PlatformApi.appCanEnableWifi], or the device owner). Then a
     * setup hotspot could start but never finish: the panel couldn't join the network a phone sends.
     */
    fun lockedOff(context: Context, platform: PlatformApi = Platform): Boolean {
        val ctx = context.applicationContext
        val on = runCatching { ctx.getSystemService(WifiManager::class.java)?.isWifiEnabled }.getOrNull() ?: return false
        if (on || platform.appCanEnableWifi) return false
        val owner = runCatching { ctx.getSystemService(DevicePolicyManager::class.java)?.isDeviceOwnerApp(ctx.packageName) }.getOrNull() == true
        return !owner
    }
}
