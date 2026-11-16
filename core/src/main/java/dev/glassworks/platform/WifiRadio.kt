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

    /**
     * Turns the radio on where this app may ([PlatformApi.appCanEnableWifi], or the device owner), so a setup hotspot
     * started there can scan and join (#418 verifier, A-N2). True when the radio is on after the call. Where the app
     * may not, it changes nothing and says false: [lockedOff] keeps the hotspot from starting then.
     */
    @Suppress("DEPRECATION") // setWifiEnabled: the call this is about
    fun ensureOn(context: Context, platform: PlatformApi = Platform): Boolean {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java) ?: return false
        if (runCatching { wifi.isWifiEnabled }.getOrDefault(false)) return true
        if (lockedOff(context, platform)) return false
        return runCatching { wifi.setWifiEnabled(true) }.getOrDefault(false)
    }
}
