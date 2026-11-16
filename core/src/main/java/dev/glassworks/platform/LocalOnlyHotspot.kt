/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.platform

import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.provider.Settings

/**
 * The setup hotspot on Android 8 and newer: `WifiManager.startLocalOnlyHotspot`, a public API for any app.
 * Android names the network (`AndroidShare_<n>` on the S1's Android 10) and picks a random WPA2 key; the
 * panel shows both as a QR code. It shares no internet, and changes no setting: closing the reservation
 * ([stop]) or the app's process dying ends it.
 *
 * What Android asks for: `CHANGE_WIFI_STATE`, the permission in [PlatformApi.hotspotPermission], location on
 * where [PlatformApi.hotspotNeedsLocationOn], and the app in the foreground when it starts (the kiosk's
 * foreground service counts). On most devices, the S1 among them, the panel's Wi-Fi client stops while the
 * hotspot runs (#38).
 */
class LocalOnlyHotspot(
    context: Context,
    private val handler: Handler,
    private val platform: PlatformApi = Platform,
) : Hotspot {
    private val ctx = context.applicationContext
    private val wifi = ctx.getSystemService(WifiManager::class.java)
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    /** Each start's number: a callback from a start that [stop] has ended since is dropped. */
    private var generation = 0

    override fun start(onEvent: (Hotspot.Event) -> Unit) {
        stop()
        val mine = ++generation
        fun fail(why: HotspotFailure) = handler.post { if (generation == mine) onEvent(Hotspot.Event.Failed(why)) }
        platform.hotspotPermission?.let { p ->
            if (ctx.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) { fail(HotspotFailure.NO_PERMISSION); return }
        }
        if (platform.hotspotNeedsLocationOn && !locationOn()) { fail(HotspotFailure.LOCATION_OFF); return }
        val wifi = wifi ?: run { fail(HotspotFailure.NOT_ALLOWED); return }
        val callback = object : WifiManager.LocalOnlyHotspotCallback() {
            override fun onStarted(r: WifiManager.LocalOnlyHotspotReservation) {
                if (generation != mine) { r.close(); return }
                reservation = r
                val net = network(r)
                if (net == null) { stop(); onEvent(Hotspot.Event.Failed(HotspotFailure.FAILED)); return }
                onEvent(Hotspot.Event.Started(net))
            }

            override fun onStopped() {
                if (generation != mine) return
                reservation = null
                onEvent(Hotspot.Event.Stopped)
            }

            override fun onFailed(reason: Int) {
                if (generation == mine) onEvent(Hotspot.Event.Failed(failure(reason)))
            }
        }
        try {
            wifi.startLocalOnlyHotspot(callback, handler)
        } catch (_: SecurityException) {
            fail(if (platform.hotspotNeedsLocationOn && !locationOn()) HotspotFailure.LOCATION_OFF else HotspotFailure.NO_PERMISSION)
        } catch (_: IllegalStateException) {
            // This app already holds a reservation Android hasn't let go of.
            fail(HotspotFailure.FAILED)
        }
    }

    override fun stop() {
        generation++
        reservation?.let { runCatching { it.close() } }
        reservation = null
    }

    @Suppress("DEPRECATION") // getWifiConfiguration below Android 11, SoftApConfiguration.getSsid from 13: both still answer
    private fun network(r: WifiManager.LocalOnlyHotspotReservation): HotspotNetwork? {
        val (ssid, key) = if (platform.hasSoftApConfiguration) r.softApConfiguration.let { it.ssid to it.passphrase }
        else r.wifiConfiguration?.let { it.SSID to it.preSharedKey } ?: (null to null)
        val s = ssid?.trim('"')?.takeIf { it.isNotEmpty() } ?: return null
        val k = key?.trim('"')?.takeIf { it.isNotEmpty() } ?: return null
        return HotspotNetwork(s, k)
    }

    @Suppress("DEPRECATION") // LOCATION_MODE: LocationManager.isLocationEnabled is Android 9+, and this is needed from 8
    private fun locationOn(): Boolean =
        runCatching { Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.LOCATION_MODE) }.getOrDefault(1) != Settings.Secure.LOCATION_MODE_OFF

    private fun failure(reason: Int) = when (reason) {
        WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL -> HotspotFailure.NO_CHANNEL
        WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE -> HotspotFailure.TETHERING_ON
        WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED -> HotspotFailure.NOT_ALLOWED
        else -> HotspotFailure.FAILED
    }
}
