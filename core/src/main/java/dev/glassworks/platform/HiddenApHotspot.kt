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
import android.os.Build
import android.os.Handler
import android.os.Parcel
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import java.lang.reflect.InvocationTargetException
import java.security.SecureRandom

/**
 * The setup hotspot on Android 6 and 7, which have no local-only hotspot: the hidden
 * `WifiManager.setWifiApEnabled`, through [ApControls]. Android 6 and 7 check `CHANGE_WIFI_STATE`, then
 * `WRITE_SETTINGS` (the app-op, or the permission, which a system app gets at install). A device with a carrier's
 * tethering check (`config_mobile_hotspot_provision_app`) wants `CONNECTIVITY_INTERNAL` (6) or `TETHER_PRIVILEGED`
 * (7.1) instead. Read from AOSP 6.0.1 and 7.1.2 and the Mirror's image: frames/lululemon-mirror/research/privileged-app.md.
 * A refusal comes back as [HotspotFailure.NOT_ALLOWED], and its reason goes to [log].
 *
 * The panel names the network itself (`Glassworks-XXXX`, a fresh random WPA2 key each start). Two things
 * change on the device, and [stop] puts both back (the undo rule in #197):
 * - Android saves the network given to `setWifiApEnabled` as the device's own hotspot settings. The ones from
 *   before go back.
 * - Android turns the Wi-Fi client off to start the AP. When it was on before, it goes back on.
 *
 * The same happens when Android ends the AP by itself ([Hotspot.Event.Stopped]) or it fails to start.
 *
 * Android keeps the AP up after the app dies, and while it is up it ignores both another start and the Wi-Fi switch
 * (`WifiController.ApEnabledState`, AOSP 6.0.1; 7.1 turns the AP off for the switch). So what a start changes is kept
 * in [undo] until it is put back, and a new hotspot puts back what a run that died left ([init]). An AP that is up
 * with no record of ours is the device's own hotspot: the start fails as [HotspotFailure.TETHERING_ON] and leaves it be.
 */
class HiddenApHotspot(
    private val ap: ApControls,
    private val handler: Handler,
    /**
     * The hotspot's address once its interface has one ([HotspotAddress.now]). "Started" waits for it: on Android 7.1,
     * `setWifiApEnabled` can bring the AP up without tethering its interface (no address, no DHCP), and a phone could
     * then join a network that can't reach the setup page (CodeRabbit on #418). No address in [addressTimeoutMs] is a
     * clear failure, with everything put back.
     */
    private val address: () -> String?,
    private val undo: ApUndo,
    private val newNetwork: () -> HotspotNetwork = ::randomNetwork,
    private val startTimeoutMs: Long = 20_000,
    private val addressTimeoutMs: Long = 5_000,
    private val log: (String) -> Unit = { Log.w(TAG, it) },
) : Hotspot {
    /** What this run's start changed, also in [undo]. Compared by identity: a callback of an older start finds another. */
    private var before: ApBefore? = null

    /** What a stop couldn't put back (the AP stayed up, or the settings were refused): tried again at each stop and start. */
    private var left: ApBefore? = null

    /**
     * What the last stop put back, and when ([SystemClock.uptimeMillis]). For [WIFI_BACK_MS] its Wi-Fi state counts for a
     * start: Android may still be turning the client on (CodeRabbit on #712), and a start then would keep "off" as the
     * state to go back to. After that the switch is the person's again.
     */
    private var putBack: ApBefore? = null
    private var putBackAt = 0L
    private var unwatch: (() -> Unit)? = null
    private var started = false
    private var timeout: Runnable? = null
    private var addressWait: Runnable? = null

    init {
        restore() // a run that died with the AP up
    }

    override fun start(onEvent: (Hotspot.Event) -> Unit) {
        if (!restore()) {
            // An earlier start isn't undone yet: a new one would keep the setup network as the device's own settings.
            log("setup hotspot: an earlier run isn't put back yet; not starting")
            handler.post { onEvent(Hotspot.Event.Failed(HotspotFailure.FAILED)) }
            return
        }
        if (runCatching { ap.apEnabled() }.getOrDefault(false)) {
            log("setup hotspot: an access point is already on, not ours")
            handler.post { onEvent(Hotspot.Event.Failed(HotspotFailure.TETHERING_ON)) }
            return
        }
        val turningOn = putBack?.wifiOn == true && SystemClock.uptimeMillis() - putBackAt < WIFI_BACK_MS
        val saved = ApBefore(ap.savedConfig(), ap.wifiEnabled() || turningOn)
        putBack = null
        before = saved
        undo.write(saved)
        started = false
        val net = newNetwork()
        unwatch = ap.watch { state ->
            when (state) {
                ApControls.State.ENABLED -> if (!started && addressWait == null) waitForAddress(saved, net, onEvent)
                ApControls.State.FAILED -> { restore(); onEvent(Hotspot.Event.Failed(HotspotFailure.FAILED)) }
                ApControls.State.DISABLED -> when {
                    started -> { restore(); onEvent(Hotspot.Event.Stopped) }
                    // Up, then down again before it had an address: it never started, and the address isn't why
                    // (#418 verifier). Before ENABLED a DISABLED is the sticky broadcast's old state, not news.
                    addressWait != null -> { restore(); onEvent(Hotspot.Event.Failed(HotspotFailure.FAILED)) }
                }
                ApControls.State.OTHER -> Unit
            }
        }
        val refused = try {
            if (ap.setAp(net, true)) null else "setWifiApEnabled returned false"
        } catch (e: Exception) {
            // A SecurityException, or a device without the hidden method (NoSuchMethodException).
            "${e.javaClass.name}: ${e.message}"
        }
        if (refused != null) {
            log("setup hotspot refused: ${refused.replace(net.passphrase, "<key>")}")
            restore()
            handler.post { onEvent(Hotspot.Event.Failed(HotspotFailure.NOT_ALLOWED)) }
            return
        }
        timeout = Runnable { if (before === saved && !started) { restore(); onEvent(Hotspot.Event.Failed(HotspotFailure.FAILED)) } }
            .also { handler.postDelayed(it, startTimeoutMs) }
    }

    override fun stop() {
        restore()
    }

    /** Up, as far as Android says: started once the interface has an address, failed if it never gets one. */
    private fun waitForAddress(saved: ApBefore, net: HotspotNetwork, onEvent: (Hotspot.Event) -> Unit) {
        var tries = (addressTimeoutMs / ADDRESS_POLL_MS).toInt()
        val check = object : Runnable {
            override fun run() {
                if (before !== saved || started) return
                if (address() != null) {
                    addressWait = null
                    started = true
                    onEvent(Hotspot.Event.Started(net))
                } else if (tries-- > 0) {
                    handler.postDelayed(this, ADDRESS_POLL_MS)
                } else {
                    restore()
                    onEvent(Hotspot.Event.Failed(HotspotFailure.NOT_TETHERED))
                }
            }
        }
        clearTimeout() // the AP is up: from here the address wait has its own clock
        addressWait = check
        check.run()
    }

    /**
     * Ends the AP, then puts back what a start changed: this run's, one a stop couldn't finish, or one that died ([undo]).
     * True once nothing is left to put back. The record goes only then: the AP off (or not up) and the device's settings
     * as they were. Turning the Wi-Fi client back on doesn't hold it, since Android does that itself when the AP stops.
     */
    private fun restore(): Boolean {
        val b = before ?: left ?: when (val kept = undo.read()) {
            ApUndo.Kept.None -> return true
            ApUndo.Kept.Unreadable -> return false
            is ApUndo.Kept.Found -> kept.before.also { log("setup hotspot: putting back what a run that died left") }
        }
        before = null
        started = false
        clearTimeout()
        addressWait?.let(handler::removeCallbacks)
        addressWait = null
        unwatch?.invoke()
        unwatch = null
        val apOff = runCatching { ap.setAp(null, false) }.getOrDefault(false) || !runCatching { ap.apEnabled() }.getOrDefault(true)
        val configBack = runCatching { ap.restoreConfig(b.config) }.getOrDefault(false) || runCatching { ap.savedConfig() == b.config }.getOrDefault(false)
        if (b.wifiOn && !ap.wifiEnabled()) runCatching { ap.setWifiEnabled(true) }
        putBack = b
        putBackAt = SystemClock.uptimeMillis()
        if (!apOff || !configBack) {
            left = b
            log("setup hotspot: couldn't put everything back (AP off $apOff, settings back $configBack); trying again at the next start")
            return false
        }
        left = null
        undo.clear()
        return true
    }

    private fun clearTimeout() {
        timeout?.let(handler::removeCallbacks)
        timeout = null
    }

    companion object {
        private const val LETTERS = "ABCDEFGHJKMNPQRSTUVWXYZ23456789" // no 0/O, 1/I/L: the key may be typed from the screen
        private val random = SecureRandom()
        private const val ADDRESS_POLL_MS = 500L
        private const val WIFI_BACK_MS = 30_000L
        private const val TAG = "Glassworks.Hotspot"

        private fun pick(n: Int) = String(CharArray(n) { LETTERS[random.nextInt(LETTERS.length)] })

        /** `Glassworks-XXXX` and a 12-character key. */
        fun randomNetwork() = HotspotNetwork("Glassworks-${pick(4)}", pick(12).lowercase())
    }
}

/** The hidden hotspot calls of Android 6 and 7, behind a seam so a test can stand in for the device. */
interface ApControls {
    enum class State { ENABLED, DISABLED, FAILED, OTHER }

    fun wifiEnabled(): Boolean

    fun setWifiEnabled(on: Boolean)

    /** An access point is up: ours, or the device's own hotspot. Throws when Android can't say. */
    fun apEnabled(): Boolean

    /** The device's own hotspot settings as text, kept in a file ([ApUndo]) until [restoreConfig] puts them back. */
    fun savedConfig(): String?

    /**
     * Puts back settings [savedConfig] gave. False when Android refused them. Settings read on another build of Android
     * can never go back (their parcel wouldn't read): true, and they are dropped, since keeping them would block every start.
     */
    fun restoreConfig(config: String?): Boolean

    /** Starts the AP as [net], or stops it. False, or a [SecurityException], when Android refuses. */
    fun setAp(net: HotspotNetwork?, on: Boolean): Boolean

    /** Calls [onChange] with each change of the AP's state, until the returned function is called. */
    fun watch(onChange: (State) -> Unit): () -> Unit
}

/**
 * [ApControls] on a real device: reflection on `WifiManager` (no hidden-API blocks before Android 9). The AP's
 * state comes on [handler].
 */
class ReflectedAp(context: Context, private val handler: Handler) : ApControls {
    private val ctx = context.applicationContext
    private val wifi = ctx.getSystemService(WifiManager::class.java)

    override fun wifiEnabled() = wifi.isWifiEnabled

    @Suppress("DEPRECATION")
    override fun setWifiEnabled(on: Boolean) { wifi.isWifiEnabled = on }

    override fun apEnabled() = call("isWifiApEnabled") as Boolean

    override fun savedConfig(): String? = runCatching { (call("getWifiApConfiguration") as WifiConfiguration?)?.let(::encode) }.getOrNull()

    override fun restoreConfig(config: String?): Boolean {
        val c = config?.let(::decode) ?: return true
        return call("setWifiApConfiguration", WifiConfiguration::class.java to c) as Boolean
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

    companion object {
        /**
         * [c] as text: its parcel, which keeps every field (the band and channel are hidden ones), and the build it was
         * read on. A parcel only reads back on the same Android, so [decode] gives null for another build.
         */
        internal fun encode(c: WifiConfiguration): String {
            val p = Parcel.obtain()
            try {
                p.writeParcelable(c, 0)
                return Build.FINGERPRINT + "\n" + Base64.encodeToString(p.marshall(), Base64.NO_WRAP)
            } finally {
                p.recycle()
            }
        }

        internal fun decode(text: String): WifiConfiguration? {
            val build = text.substringBefore('\n')
            if (build != Build.FINGERPRINT) return null
            val p = Parcel.obtain()
            return try {
                val bytes = Base64.decode(text.substringAfter('\n'), Base64.NO_WRAP)
                p.unmarshall(bytes, 0, bytes.size)
                p.setDataPosition(0)
                @Suppress("DEPRECATION") // the typed one is Android 13's
                p.readParcelable<WifiConfiguration>(WifiConfiguration::class.java.classLoader)
            } catch (e: RuntimeException) {
                null
            } finally {
                p.recycle()
            }
        }

        // Hidden in Android 6 and 7: WifiManager.WIFI_AP_STATE_CHANGED_ACTION, EXTRA_WIFI_AP_STATE and its values,
        // and WifiConfiguration.KeyMgmt.WPA2_PSK.
        private const val ACTION = "android.net.wifi.WIFI_AP_STATE_CHANGED"
        private const val EXTRA_STATE = "wifi_state"
        private const val DISABLED = 11
        private const val ENABLED = 13
        private const val FAILED = 14
        private const val WPA2_PSK = 4
    }
}
