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
 * Android names the network (`AndroidShare_<n>` on Android 10, `Glassworks_<n>` on an S1 with its name overlay) and picks a random WPA2 key; the
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
    /** Android's call; a test stands in for it. */
    private val ap: LocalOnlyAp = WifiLocalOnlyAp(context, platform),
) : Hotspot {
    private val ctx = context.applicationContext
    private var reservation: LocalOnlyAp.Reservation? = null

    /** Who hears the hotspot now; null once [stop] ran. */
    private var current: ((Hotspot.Event) -> Unit)? = null

    /**
     * The request that is out and Android hasn't answered, or null. A new start waits for that answer instead of asking
     * again: Android refuses a second request from the same app while one is pending (CodeRabbit on #418).
     */
    private var pending: Int? = null

    /** The request whose reservation we hold, or null. Only its stop counts. */
    private var holding: Int? = null

    /** Each Android request's number: an answer counts only for the request it belongs to. */
    private var requests = 0

    /** Each start's number, for the refusals [start] posts itself. */
    private var generation = 0

    /**
     * One listener per Android request. Its answer goes to whoever [current] is when it comes, and its terminal events
     * only while it is the request out ([pending]) or held ([holding]): a stop Android queued for an earlier request
     * never reaches a later one (CodeRabbit on #418).
     */
    private fun listener(id: Int) = object : LocalOnlyAp.Listener {
        override fun onStarted(r: LocalOnlyAp.Reservation) {
            if (pending != id) { runCatching { r.close() }; return } // not the request out: nobody's
            pending = null
            // Nobody wants it any more (stop() ran since): Android's reservation goes back at once.
            val to = current ?: run { runCatching { r.close() }; return }
            reservation = r
            holding = id
            // Unreadable, or reading it threw: closed again by stop(), never left open.
            val net = runCatching { r.network() }.getOrNull()
            if (net == null) { stop(); to(Hotspot.Event.Failed(HotspotFailure.FAILED)); return }
            to(Hotspot.Event.Started(net))
        }

        override fun onStopped() {
            if (holding != id) return
            holding = null
            reservation = null
            current?.invoke(Hotspot.Event.Stopped)
        }

        override fun onFailed(reason: Int) {
            if (pending != id) return
            pending = null
            current?.invoke(Hotspot.Event.Failed(failure(reason)))
        }
    }

    override fun start(onEvent: (Hotspot.Event) -> Unit) {
        stop()
        val mine = ++generation
        fun fail(why: HotspotFailure) = handler.post { if (generation == mine) onEvent(Hotspot.Event.Failed(why)) }
        platform.hotspotPermission?.let { p ->
            if (ctx.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) { fail(HotspotFailure.NO_PERMISSION); return }
        }
        if (platform.hotspotNeedsLocationOn && !locationOn()) { fail(HotspotFailure.LOCATION_OFF); return }
        current = onEvent
        if (pending != null) return // the request still out answers this start
        val id = ++requests
        pending = id
        try {
            if (!ap.start(listener(id), handler)) { pending = null; fail(HotspotFailure.NOT_ALLOWED) }
        } catch (_: SecurityException) {
            pending = null
            fail(if (platform.hotspotNeedsLocationOn && !locationOn()) HotspotFailure.LOCATION_OFF else HotspotFailure.NO_PERMISSION)
        } catch (_: IllegalStateException) {
            // This app already holds a reservation Android hasn't let go of.
            pending = null
            fail(HotspotFailure.FAILED)
        }
    }

    override fun stop() {
        generation++
        current = null
        holding = null
        reservation?.let { runCatching { it.close() } }
        reservation = null
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

/** Android's local-only hotspot call, behind a seam so a test can stand in for the device. */
interface LocalOnlyAp {
    /** What Android hands over once the hotspot is up. */
    interface Reservation {
        /** Its network, or null when Android doesn't say. May throw. */
        fun network(): HotspotNetwork?

        /** Gives it back: Android stops the hotspot. */
        fun close()
    }

    interface Listener {
        fun onStarted(r: Reservation)
        fun onStopped()
        /** A `WifiManager.LocalOnlyHotspotCallback.ERROR_*` value. */
        fun onFailed(reason: Int)
    }

    /** Asks for the hotspot; false when the device has no Wi-Fi. Throws as `WifiManager.startLocalOnlyHotspot` does. */
    fun start(listener: Listener, handler: Handler): Boolean
}

/** [LocalOnlyAp] on a real device: `WifiManager.startLocalOnlyHotspot`. */
class WifiLocalOnlyAp(context: Context, private val platform: PlatformApi = Platform) : LocalOnlyAp {
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)

    override fun start(listener: LocalOnlyAp.Listener, handler: Handler): Boolean {
        val wifi = wifi ?: return false
        wifi.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
            override fun onStarted(r: WifiManager.LocalOnlyHotspotReservation) = listener.onStarted(object : LocalOnlyAp.Reservation {
                override fun network() = network(r)
                override fun close() = r.close()
            })

            override fun onStopped() = listener.onStopped()
            override fun onFailed(reason: Int) = listener.onFailed(reason)
        }, handler)
        return true
    }

    @Suppress("DEPRECATION") // getWifiConfiguration below Android 11: still answers
    private fun network(r: WifiManager.LocalOnlyHotspotReservation): HotspotNetwork? {
        val (ssid, key) = if (platform.hasSoftApConfiguration) Api30.ssidAndKey(r)
        else r.wifiConfiguration?.let { it.SSID to it.preSharedKey } ?: (null to null)
        val s = ssid?.trim('"')?.takeIf { it.isNotEmpty() } ?: return null
        val k = key?.trim('"')?.takeIf { it.isNotEmpty() } ?: return null
        return HotspotNetwork(s, k)
    }

    /** SoftApConfiguration (Android 11) in a class of its own, so Android 10 verifies this one at install (StartPathVerifyTest). */
    private object Api30 {
        @Suppress("DEPRECATION") // SoftApConfiguration.getSsid from 13: still answers
        fun ssidAndKey(r: WifiManager.LocalOnlyHotspotReservation): Pair<String?, String?> =
            r.softApConfiguration.let { it.ssid to it.passphrase }
    }
}
