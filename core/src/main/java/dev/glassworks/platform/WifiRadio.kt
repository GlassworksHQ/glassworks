/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.platform

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.net.wifi.WifiManager
import android.provider.Settings

/** The Wi-Fi radio as the setup hotspot needs it (#197). */
object WifiRadio {
    // Settings.Global.WIFI_ON's values (AOSP WifiSettingsStore): 0 off, 1 on, 2 on in airplane mode, 3 off because
    // airplane mode is on.
    private const val WIFI_DISABLED = 0
    private const val WIFI_DISABLED_AIRPLANE_ON = 3

    /**
     * The user's Wi-Fi switch is off: `Settings.Global.WIFI_ON` says off, also through airplane mode. Not the client's
     * state: without STA+AP concurrency Android parks the client while our own access point runs, and
     * `isWifiEnabled` then reads false with the switch on (S1 retest on b8f827fe). Unreadable counts as on.
     *
     * Keep in step with `wifiSwitchOn` in `libs/improv`'s WifiProvisioner.kt, which reads the same setting and values for
     * a join (the library can't depend on the core). There an unreadable switch reads as off, on purpose.
     */
    fun switchedOff(context: Context): Boolean {
        val v = runCatching { Settings.Global.getInt(context.applicationContext.contentResolver, Settings.Global.WIFI_ON) }.getOrNull()
            ?: return false
        return v == WIFI_DISABLED || v == WIFI_DISABLED_AIRPLANE_ON
    }

    /**
     * Wi-Fi is switched off ([switchedOff]), and this app can't switch it on ([PlatformApi.appCanEnableWifi], or the
     * device owner). Then a setup hotspot could start but never finish: the panel couldn't join the network a phone
     * sends.
     */
    fun lockedOff(context: Context, platform: PlatformApi = Platform): Boolean =
        switchedOff(context) && !mayEnable(context, platform)

    /** This app may switch Wi-Fi on: [PlatformApi.appCanEnableWifi] (Android 9 and older), or it is the device owner. */
    fun mayEnable(context: Context, platform: PlatformApi = Platform): Boolean {
        if (platform.appCanEnableWifi) return true
        val ctx = context.applicationContext
        return runCatching { ctx.getSystemService(DevicePolicyManager::class.java)?.isDeviceOwnerApp(ctx.packageName) }.getOrNull() == true
    }

    /**
     * Switches Wi-Fi on where this app may ([PlatformApi.appCanEnableWifi], or the device owner), so a setup hotspot
     * started there can scan and join (#418 verifier, A-N2). True when the switch is on after the call; a switch
     * that is already on is left alone. Where the app may not, it changes nothing and says false: [lockedOff] keeps the
     * hotspot from starting then.
     */
    @Suppress("DEPRECATION") // setWifiEnabled: the call this is about
    fun ensureOn(context: Context, platform: PlatformApi = Platform): Boolean {
        if (!switchedOff(context)) return true
        if (lockedOff(context, platform)) return false
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java) ?: return false
        return runCatching { wifi.setWifiEnabled(true) }.getOrDefault(false)
    }
}
