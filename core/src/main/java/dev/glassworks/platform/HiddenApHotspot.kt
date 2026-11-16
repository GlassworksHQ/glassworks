/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Handler
import java.lang.reflect.InvocationTargetException
import java.security.SecureRandom

/**
 * The setup hotspot on Android 6 and 7, which have no local-only hotspot: the hidden
 * `WifiManager.setWifiApEnabled`, through [ApControls]. On Android 6.0.1 and 7 any app with
 * `CHANGE_WIFI_STATE` and `CHANGE_NETWORK_STATE` may call it, unless the device has a carrier's tethering
 * check; 6.0.0 wants `WRITE_SETTINGS`. A refusal comes back as [HotspotFailure.NOT_ALLOWED].
 *
 * The panel names the network itself (`Glassworks-XXXX`, a fresh random WPA2 key each start). Two things
 * change on the device, and [stop] puts both back (the undo rule in #197):
 * - Android saves the network given to `setWifiApEnabled` as the device's own hotspot settings. The ones from
 *   before go back.
 * - Android turns the Wi-Fi client off to start the AP. When it was on before, it goes back on.
 *
 * The same happens when Android ends the AP by itself ([Hotspot.Event.Stopped]) or it fails to start.
 */
class HiddenApHotspot<C>(
    private val ap: ApControls<C>,
    private val handler: Handler,
    private val newNetwork: () -> HotspotNetwork = ::randomNetwork,
    private val startTimeoutMs: Long = 20_000,
) : Hotspot {
    /** What the start changed, to put back: the device's hotspot settings and whether Wi-Fi was on. */
    private class Before<C>(val config: C?, val wifiOn: Boolean)

    private var before: Before<C>? = null
    private var unwatch: (() -> Unit)? = null
    private var started = false
    private var timeout: Runnable? = null

    override fun start(onEvent: (Hotspot.Event) -> Unit) {
        stop()
        val saved = Before(ap.savedConfig(), ap.wifiEnabled())
        before = saved
        started = false
        val net = newNetwork()
        unwatch = ap.watch { state ->
            when (state) {
                ApControls.State.ENABLED -> if (!started) { started = true; clearTimeout(); onEvent(Hotspot.Event.Started(net)) }
                ApControls.State.FAILED -> { restore(); onEvent(Hotspot.Event.Failed(HotspotFailure.FAILED)) }
                ApControls.State.DISABLED -> if (started) { restore(); onEvent(Hotspot.Event.Stopped) }
                ApControls.State.OTHER -> Unit
            }
        }
        val ok = try {
            ap.setAp(net, true)
        } catch (_: Exception) {
            // A SecurityException, or a device without the hidden method (NoSuchMethodException).
            false
        }
        if (!ok) {
            restore()
            handler.post { onEvent(Hotspot.Event.Failed(HotspotFailure.NOT_ALLOWED)) }
            return
        }
        timeout = Runnable { if (before === saved && !started) { restore(); onEvent(Hotspot.Event.Failed(HotspotFailure.FAILED)) } }
            .also { handler.postDelayed(it, startTimeoutMs) }
    }

    override fun stop() = restore()

    /** Ends the AP, then puts back what [start] changed. Does nothing when nothing is changed. */
    private fun restore() {
        val b = before ?: return
        before = null
        started = false
        clearTimeout()
        unwatch?.invoke()
        unwatch = null
        runCatching { ap.setAp(null, false) }
        runCatching { ap.restoreConfig(b.config) }
        if (b.wifiOn && !ap.wifiEnabled()) runCatching { ap.setWifiEnabled(true) }
    }

    private fun clearTimeout() {
        timeout?.let(handler::removeCallbacks)
        timeout = null
    }

    companion object {
        private const val LETTERS = "ABCDEFGHJKMNPQRSTUVWXYZ23456789" // no 0/O, 1/I/L: the key may be typed from the screen
        private val random = SecureRandom()

        private fun pick(n: Int) = String(CharArray(n) { LETTERS[random.nextInt(LETTERS.length)] })

        /** `Glassworks-XXXX` and a 12-character key. */
        fun randomNetwork() = HotspotNetwork("Glassworks-${pick(4)}", pick(12).lowercase())
    }
}

/** The hidden hotspot calls of Android 6 and 7, behind a seam so a test can stand in for the device. */
interface ApControls<C> {
    enum class State { ENABLED, DISABLED, FAILED, OTHER }

    fun wifiEnabled(): Boolean

    fun setWifiEnabled(on: Boolean)

    /** The device's own hotspot settings, to put back with [restoreConfig]. */
    fun savedConfig(): C?

    fun restoreConfig(config: C?)

    /** Starts the AP as [net], or stops it. False, or a [SecurityException], when Android refuses. */
    fun setAp(net: HotspotNetwork?, on: Boolean): Boolean

    /** Calls [onChange] with each change of the AP's state, until the returned function is called. */
    fun watch(onChange: (State) -> Unit): () -> Unit
}

/**
 * [ApControls] on a real device: reflection on `WifiManager` (no hidden-API blocks before Android 9). The AP's
 * state comes on [handler].
 */
class ReflectedAp(context: Context, private val handler: Handler) : ApControls<WifiConfiguration> {
    private val ctx = context.applicationContext
    private val wifi = ctx.getSystemService(WifiManager::class.java)

    override fun wifiEnabled() = wifi.isWifiEnabled

    @Suppress("DEPRECATION")
    override fun setWifiEnabled(on: Boolean) { wifi.isWifiEnabled = on }

    override fun savedConfig(): WifiConfiguration? = runCatching { call("getWifiApConfiguration") as WifiConfiguration? }.getOrNull()

    override fun restoreConfig(config: WifiConfiguration?) {
        if (config != null) call("setWifiApConfiguration", WifiConfiguration::class.java to config)
    }

    override fun setAp(net: HotspotNetwork?, on: Boolean): Boolean {
        @Suppress("DEPRECATION")
        val config = net?.let {
            WifiConfiguration().apply {
                SSID = it.ssid // an AP's settings take the name bare, unlike a saved network's
                preSharedKey = it.passphrase
                allowedKeyManagement.set(WPA2_PSK)
            }
        }
        return call("setWifiApEnabled", WifiConfiguration::class.java to config, Boolean::class.javaPrimitiveType!! to on) as Boolean
    }

    override fun watch(onChange: (ApControls.State) -> Unit): () -> Unit {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = onChange(when (i.getIntExtra(EXTRA_STATE, -1)) {
                ENABLED -> ApControls.State.ENABLED
                DISABLED -> ApControls.State.DISABLED
                FAILED -> ApControls.State.FAILED
                else -> ApControls.State.OTHER
            })
        }
        ctx.registerReceiver(receiver, IntentFilter(ACTION), null, handler)
        return { runCatching { ctx.unregisterReceiver(receiver) } }
    }

    /** A hidden `WifiManager` method; a refusal inside it comes out as the [SecurityException] it was. */
    private fun call(name: String, vararg args: Pair<Class<*>, Any?>): Any? = try {
        WifiManager::class.java.getMethod(name, *args.map { it.first }.toTypedArray()).invoke(wifi, *args.map { it.second }.toTypedArray())
    } catch (e: InvocationTargetException) {
        throw e.targetException
    }

    private companion object {
        // Hidden in Android 6 and 7: WifiManager.WIFI_AP_STATE_CHANGED_ACTION, EXTRA_WIFI_AP_STATE and its values,
        // and WifiConfiguration.KeyMgmt.WPA2_PSK.
        const val ACTION = "android.net.wifi.WIFI_AP_STATE_CHANGED"
        const val EXTRA_STATE = "wifi_state"
        const val DISABLED = 11
        const val ENABLED = 13
        const val FAILED = 14
        const val WPA2_PSK = 4
    }
}
