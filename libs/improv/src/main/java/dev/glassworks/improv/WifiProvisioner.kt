package dev.glassworks.improv

import android.Manifest
import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.SupplicantState
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Handler
import android.os.SystemClock
import android.provider.Settings
import android.util.Log

/**
 * Joins the Wi-Fi network received over Improv, and puts the previous one back on failure.
 *
 * Android 10 (API 29) with targetSdk >= 29: WifiManager.addNetwork/enableNetwork/removeNetwork/
 * reconnect return -1/false for normal apps. AOSP android10-release WifiServiceImpl
 * `isTargetSdkLessThanQOrPrivileged()` still allows them for (a) a device-owner/profile-owner app
 * and (b) an app holding the SYSTEM_ALERT_WINDOW app-op (`checkSystemAlertWindowPermission`, e.g.
 * `adb shell appops set <pkg> SYSTEM_ALERT_WINDOW allow`). A device owner can also grant itself the
 * location permission. Both use the "legacy" path below. Otherwise it falls
 * back to a WifiNetworkSuggestion, which on Android 10 needs the user to approve a notification and
 * is never preferred over an already-saved network that works.
 *
 * Seeing the current SSID/networkId and the scan results needs ACCESS_FINE_LOCATION *and* location
 * mode on (WifiPermissionsUtil.enforceCanAccessScanResults); without it WifiInfo is redacted.
 *
 * Main thread only.
 *
 * @param ctx Any context.
 * @param main The main thread's handler.
 * @param admin Your DeviceAdminReceiver, if the app can be device owner; null = look it up among the
 *   active admins.
 */
@SuppressLint("MissingPermission")
public class WifiProvisioner(
    private val ctx: Context,
    private val main: Handler,
    private val admin: ComponentName? = null,
) {

    /** How a [connect] ended. */
    public sealed class Outcome {
        /**
         * On the network.
         *
         * @property ssid The network joined.
         * @property ip The device's IPv4 address on it, or null if it isn't known yet.
         * @property method How it joined, for logs: `already-connected`, `device-owner`, `addNetwork` or
         *   `suggestion`.
         */
        public data class Connected(public val ssid: String, public val ip: String?, public val method: String) : Outcome() {
            /** For a log: the address and the method, never the network's name. */
            override fun toString(): String = "Connected(ip=$ip, method=$method)"
        }

        /**
         * Joining failed.
         *
         * @property reason Why, in words for the user or a log.
         * @property restored The network it went back to, or null for none.
         */
        public data class Failed(public val reason: String, public val restored: String?) : Outcome() {
            /** For a log: the reason, and whether it went back to a network, never that network's name. */
            override fun toString(): String = "Failed(reason=$reason, restored=${restored != null})"
        }
    }

    private val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val cm = ctx.getSystemService(ConnectivityManager::class.java)
    private val dpm = ctx.getSystemService(DevicePolicyManager::class.java)
    private var job: Attempt? = null

    /** Progress text for the overlay (e.g. "approve the notification"). */
    public var onProgress: ((String) -> Unit)? = null

    internal val isDeviceOwner: Boolean get() = runCatching { dpm.isDeviceOwnerApp(ctx.packageName) }.getOrDefault(false)

    internal val canDrawOverlays: Boolean get() = Settings.canDrawOverlays(ctx)

    /**
     * True when addNetwork/enableNetwork are expected to work (see the class comment). When false,
     * [connect] uses a network suggestion, which the user must approve in a notification.
     */
    public val legacyApiAllowed: Boolean
        get() = !Compat.hasNetworkSuggestions || isDeviceOwner || (Compat.isAndroid10 && canDrawOverlays)

    internal val hasLocationPermission: Boolean
        get() = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    internal val locationEnabled: Boolean
        get() = runCatching {
            if (Compat.hasIsLocationEnabled) {
                ctx.getSystemService(LocationManager::class.java).isLocationEnabled
            } else {
                @Suppress("DEPRECATION")
                Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF) !=
                    Settings.Secure.LOCATION_MODE_OFF
            }
        }.getOrDefault(false)

    /**
     * The app holds ACCESS_FINE_LOCATION and location is on: needed to read scan results and the
     * current SSID/networkId on API 29.
     */
    public val hasLocationAccess: Boolean get() = hasLocationPermission && locationEnabled

    /** One line on the provisioner's access (device owner, overlays, location, Wi-Fi), for logs. */
    public fun status(): String = "deviceOwner=$isDeviceOwner saw=$canDrawOverlays legacyApi=$legacyApiAllowed " +
        "fineLocation=$hasLocationPermission locationOn=$locationEnabled wifiOn=${wm.isWifiEnabled}"

    /** A device owner can grant itself the location permission (needed for SSIDs and scans). */
    internal fun ensureLocationIfDeviceOwner() {
        if (hasLocationPermission || !isDeviceOwner) return
        val admin = admin ?: dpm.activeAdmins?.firstOrNull { it.packageName == ctx.packageName } ?: run {
            Log.w(TAG, "device owner but no active admin component found; cannot grant location")
            return
        }
        for (p in listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) {
            val ok = runCatching {
                dpm.setPermissionGrantState(admin, ctx.packageName, p, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
            }.getOrElse { Log.w(TAG, "setPermissionGrantState($p) failed", it); false }
            Log.i(TAG, "device owner: grant $p -> $ok")
        }
    }

    /** Asks Wi-Fi for a new scan. False when it was refused (e.g. throttled): the cached results are all there is. */
    public fun requestScan(): Boolean {
        @Suppress("DEPRECATION")
        return runCatching { wm.startScan() }.getOrDefault(false)
    }

    /** Cached scan results as Improv triples; empty without location access. */
    public fun scan(): List<ScannedNetwork> {
        ensureLocationIfDeviceOwner()
        if (!hasLocationAccess) {
            Log.w(TAG, "scan: no location access (${status()}); returning no networks")
            return emptyList()
        }
        return runCatching {
            wm.scanResults.filter { !it.SSID.isNullOrEmpty() }
                .map { ScannedNetwork(it.SSID, it.level, WifiSecurityRules.improvAuth(it.capabilities ?: "")) }
        }.getOrElse { Log.w(TAG, "getScanResults failed", it); emptyList() }
    }

    /**
     * A fresh scan: turns Wi-Fi on if it is off, starts a scan and reports [scan] once results
     * arrive (SCAN_RESULTS_AVAILABLE) or after [timeoutMs], whichever is first. Main thread.
     */
    public fun scanFresh(timeoutMs: Long, cb: (List<ScannedNetwork>) -> Unit) {
        var finished = false
        lateinit var receiver: BroadcastReceiver
        val finish = Runnable {
            if (finished) return@Runnable
            finished = true
            runCatching { ctx.unregisterReceiver(receiver) }
            cb(scan())
        }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val updated = i.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, true)
                if (updated && !finished) { main.removeCallbacks(finish); main.post(finish) }
            }
        }
        runCatching { ctx.registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)) }
        main.postDelayed(finish, timeoutMs)
        if (!wm.isWifiEnabled) {
            val switchOn = wifiSwitchOn(ctx) // the client comes back by itself (see connect)
            @Suppress("DEPRECATION")
            val ok = switchOn || runCatching { wm.setWifiEnabled(true) }.getOrDefault(false)
            Log.i(TAG, if (switchOn) "scan: Wi-Fi client is down with the switch on: waiting for it" else "scan: Wi-Fi is off: setWifiEnabled(true) -> $ok")
            // Wi-Fi scans by itself once it is on; ask again shortly in case it doesn't.
            if (ok) main.postDelayed({ if (!finished) requestScan() }, 3_000L)
        } else if (!requestScan()) {
            Log.i(TAG, "scan: startScan refused (throttled?); using cached results")
            main.removeCallbacks(finish)
            main.post(finish)
        }
    }

    /** Cancels a [connect] in progress; its callback gets [Outcome.Failed] with `cancelled`. */
    public fun cancel() {
        preparing?.let { finishPreparing(it, Outcome.Failed("cancelled", null)) }
        job?.finish(Outcome.Failed("cancelled", null))
    }

    /** A connect waiting for Wi-Fi to turn on (and for a first scan) before [connectNow]. */
    private inner class Preparing(
        val ssid: String,
        val password: String,
        val hidden: Boolean?,
        val cb: (Outcome) -> Unit,
        /** The switch is on and the client is down: if it never comes back, the reason says so, not "turned off". */
        val waitingForClient: Boolean = false,
    ) : Runnable {
        val enableDeadline = SystemClock.elapsedRealtime() + WIFI_ENABLE_TIMEOUT_MS
        var scanDeadline = 0L

        override fun run() {
            if (preparing !== this) return
            val now = SystemClock.elapsedRealtime()
            if (!wm.isWifiEnabled) {
                if (now > enableDeadline) {
                    return finishPreparing(this, Outcome.Failed(if (waitingForClient) RADIO_DIDNT_COME_BACK else "Wi-Fi is turned off and could not be turned on", null))
                }
                main.postDelayed(this, 500)
                return
            }
            if (scanDeadline == 0L) {
                // Just turned on: give the first scan a moment, so the network's security type is known.
                Log.i(TAG, "Wi-Fi is on")
                scanDeadline = now + FIRST_SCAN_WAIT_MS
                requestScan()
            }
            val seen = hasLocationAccess && runCatching { wm.scanResults.any { it.SSID == ssid } }.getOrDefault(false)
            if (!seen && now < scanDeadline) {
                main.postDelayed(this, 500)
                return
            }
            preparing = null
            connectNow(ssid, password, hidden, cb)
        }
    }

    private var preparing: Preparing? = null

    private fun finishPreparing(p: Preparing, o: Outcome) {
        if (preparing !== p) return
        preparing = null
        main.removeCallbacks(p)
        Log.i(TAG, "outcome: $o")
        p.cb(o)
    }

    /**
     * Joins [ssid]. Turns Wi-Fi on first if it is off (allowed on API 29 for the same callers as
     * addNetwork, see the class comment). [hidden]: null = decide from the scan (not seen = hidden),
     * else as given. [password] is never logged, only its length. [cb] gets the outcome once, on the
     * main thread.
     */
    public fun connect(ssid: String, password: String, hidden: Boolean? = null, cb: (Outcome) -> Unit) {
        preparing?.let { finishPreparing(it, Outcome.Failed("superseded", null)) }
        job?.finish(Outcome.Failed("superseded", null))
        if (!wm.isWifiEnabled) {
            // The user's switch is on, but the client is down: Android brings it back by itself (e.g. a few seconds
            // after our setup hotspot stops on a device without STA+AP). Wait for it; asking to turn it on is refused
            // from Android 10 and would fail the join at once (#418 S1 retest 3, 2026-11-17).
            val switchOn = wifiSwitchOn(ctx)
            @Suppress("DEPRECATION")
            val ok = switchOn || runCatching { wm.setWifiEnabled(true) }.getOrDefault(false)
            Log.i(TAG, if (switchOn) "Wi-Fi client is down with the switch on: waiting for it" else "Wi-Fi is off: setWifiEnabled(true) -> $ok")
            if (!ok) return cb(Outcome.Failed("Wi-Fi is turned off and could not be turned on", null))
            onProgress?.invoke(if (switchOn) "Waiting for Wi-Fi…" else "Turning Wi-Fi on…")
            val p = Preparing(ssid, password, hidden, cb, waitingForClient = switchOn)
            preparing = p
            main.postDelayed(p, 500)
            return
        }
        connectNow(ssid, password, hidden, cb)
    }

    /**
     * Entries holding an attempt's untested credential (still running, failed, or another network came up): no save yet.
     * Android 7 and earlier only: from Android 8 the framework saves, so the gate is never made and writes nothing.
     */
    private val saveGate by lazy { SaveGate.of(ctx) }

    /**
     * Saves the configured networks (Android 7 and earlier forget what addNetwork added until the app saves). Tries twice;
     * a failure is logged and the join still counts, since the network works until the next restart.
     */
    @Suppress("DEPRECATION")
    private fun saveNetworks(joined: Int?): Boolean {
        if (!saveGate.maySave(joined)) {
            Log.w(TAG, "not saving Wi-Fi networks: a failed attempt may have changed a saved network's password, which a restart puts back")
            return false
        }
        for (attempt in 1..2) {
            val saved = runCatching { wm.saveConfiguration() }.getOrElse { false }
            Log.i(TAG, "saveConfiguration (try $attempt) -> $saved")
            if (saved) return true
        }
        Log.w(TAG, "couldn't save the Wi-Fi network: it works now, but Android may forget it after a restart")
        return false
    }

    private fun connectNow(ssid: String, password: String, hidden: Boolean?, cb: (Outcome) -> Unit) {
        ensureLocationIfDeviceOwner()
        Log.i(TAG, "connect to the chosen network (${ssid.length} char name, ${password.length} char password); ${status()}")
        if (!wm.isWifiEnabled) return cb(Outcome.Failed("Wi-Fi is turned off", null))

        val loc = hasLocationAccess
        val info = wm.connectionInfo
        val wifiUpAtStart = wifiNetworkUp() && info?.supplicantState == SupplicantState.COMPLETED
        val prevNetId = if (loc) info?.networkId ?: -1 else -1
        val prevSsid = if (loc) WifiSecurityRules.unquote(info?.ssid) else null

        if (loc && wifiUpAtStart && prevSsid == ssid) {
            // Already there: don't touch the saved config (we couldn't restore its password).
            Log.i(TAG, "already on the chosen network; reporting success without changes")
            // an earlier join's save may have failed: saving again keeps the network past a restart on Android 7 and earlier
            if (Compat.mustSaveNetworks) saveNetworks(info.networkId)
            return cb(Outcome.Connected(ssid, WifiSecurityRules.ipv4(info.ipAddress), "already-connected"))
        }
        if (!loc && wifiUpAtStart) {
            // Without location access WifiInfo is redacted, so we couldn't tell our network from the
            // old one. Refuse rather than risk reporting a false success.
            return cb(Outcome.Failed(
                "Panel is on Wi-Fi but can't verify networks without the location permission", null))
        }

        val scan = if (loc) runCatching { wm.scanResults.filter { it.SSID == ssid }.maxByOrNull { it.level } }.getOrNull() else null
        val security = WifiSecurityRules.choose(scan?.capabilities, password)
        WifiSecurityRules.validate(security, password)?.let { return cb(Outcome.Failed(it, null)) }
        Log.i(TAG, "security=$security inScan=${scan != null} prevNetId=$prevNetId onAnother=${prevSsid != null} wifiUpAtStart=$wifiUpAtStart")

        val attempt = Attempt(ssid, loc, wifiUpAtStart, prevNetId, prevSsid, cb)
        job = attempt
        val isHidden = hidden ?: (scan == null)
        if (legacyApiAllowed && attempt.startLegacy(security, password, hidden = isHidden)) return
        attempt.startSuggestion(security, password, hidden = isHidden)
    }

    /**
     * SAE/OWE need PMF. API 30+: setSecurityParams(type). API 29: the field `requirePMF` is hidden
     * (@UnsupportedAppUsage, greylisted), so set it reflectively.
     */
    @SuppressLint("DiscouragedPrivateApi", "SoonBlockedPrivateApi")
    private fun requirePmf(c: WifiConfiguration, securityType: Int) {
        runCatching {
            if (Compat.hasSecurityParams) c.setSecurityParams(securityType)
            else WifiConfiguration::class.java.getField("requirePMF").setBoolean(c, true)
        }.onFailure { Log.w(TAG, "could not require PMF", it) }
    }

    private fun wifiNetworkUp(): Boolean = runCatching {
        @Suppress("DEPRECATION")
        cm.allNetworks.any { n -> cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    private inner class Attempt(
        val ssid: String,
        val loc: Boolean,
        val wifiUpAtStart: Boolean,
        val prevNetId: Int,
        val prevSsid: String?,
        val cb: (Outcome) -> Unit,
    ) {
        var netId = -1
        var addedNew = false
        var suggestion: WifiNetworkSuggestion? = null
        var method = ""
        var deadline = 0L
        var authErrors = 0
        var done = false

        private val poll = object : Runnable {
            override fun run() {
                if (done) return
                check()?.let { finish(it); return }
                main.postDelayed(this, 500)
            }
        }

        private val supplicant = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.getIntExtra(WifiManager.EXTRA_SUPPLICANT_ERROR, -1) == WifiManager.ERROR_AUTHENTICATING) {
                    authErrors++
                    Log.i(TAG, "supplicant: authentication error #$authErrors")
                }
            }
        }

        fun startLegacy(security: WifiSecurity, password: String, hidden: Boolean): Boolean {
            val conf = WifiConfiguration().apply {
                SSID = WifiSecurityRules.quote(ssid)
                hiddenSSID = hidden
                when (security) {
                    WifiSecurity.OPEN -> allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                    WifiSecurity.OWE -> { allowedKeyManagement.set(WifiConfiguration.KeyMgmt.OWE); requirePmf(this, 6 /* SECURITY_TYPE_OWE */) }
                    WifiSecurity.WEP -> {
                        allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                        allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN)
                        allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.SHARED)
                        wepKeys[0] = WifiSecurityRules.wepKey(password)
                        wepTxKeyIndex = 0
                    }
                    WifiSecurity.PSK -> {
                        allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                        preSharedKey = WifiSecurityRules.pskValue(password)
                    }
                    WifiSecurity.SAE -> {
                        allowedKeyManagement.set(WifiConfiguration.KeyMgmt.SAE)
                        requirePmf(this, 4 /* SECURITY_TYPE_SAE */)
                        preSharedKey = WifiSecurityRules.quote(password)
                    }
                    WifiSecurity.EAP -> return false
                }
            }
            // the ids saved before this attempt: whether it made the entry addNetwork returns. Only a list Android gives in
            // full counts (from Android 10 an app that isn't the device owner gets an empty or filtered one)
            val idsBefore = runCatching { wm.configuredNetworks?.map { it.networkId }?.toSet() }.getOrNull()
            // Android 7 and earlier: record that a change is coming, durably, before addNetwork makes it; a change that
            // can't be recorded isn't made, so a later save can never keep a credential nothing tested
            if (Compat.mustSaveNetworks && !saveGate.beginChange()) {
                finish(Outcome.Failed("couldn't record the Wi-Fi change, so nothing was changed", null))
                return true
            }
            netId = wm.addNetwork(conf)
            if (netId == -1) {
                if (Compat.mustSaveNetworks) saveGate.changeRefused()
                Log.w(TAG, "addNetwork refused (not device owner / no SYSTEM_ALERT_WINDOW?); trying a suggestion")
                return false
            }
            // from here until this network connects itself, or its entry is removed, its credential is untested: no save
            // (this attempt's or another's) may keep it
            if (Compat.mustSaveNetworks) saveGate.unconfirmed(netId)
            // unknown counts as not ours: a failed attempt may leave its entry behind, but never removes one saved earlier
            addedNew = SavedNetworks.madeHere(netId, idsBefore, Compat.listsSavedNetworks(isDeviceOwner, loc)) == true
            val enabled = wm.enableNetwork(netId, true)
            wm.reconnect()
            method = if (isDeviceOwner) "device-owner" else "addNetwork"
            Log.i(TAG, "addNetwork -> $netId (new=$addedNew), enableNetwork -> $enabled")
            if (!enabled) {
                // don't leave our new entry behind: a later save (below API 26) would keep it for good
                if (addedNew) removeOurs(netId)
                finish(Outcome.Failed("enableNetwork refused", null))
                return true
            }
            begin(CONNECT_TIMEOUT_MS)
            return true
        }

        fun startSuggestion(security: WifiSecurity, password: String, hidden: Boolean) {
            if (!Compat.hasNetworkSuggestions) return finish(Outcome.Failed("cannot add networks on this Android version", null))
            if (security == WifiSecurity.WEP || security == WifiSecurity.EAP) {
                return finish(Outcome.Failed("$security networks can't be suggested; make the app device owner", null))
            }
            val s = suggestion(ssid, security, password, hidden)
                ?: return finish(Outcome.Failed(UNUSABLE_PASSWORD, null))
            runCatching { wm.removeNetworkSuggestions(listOf(s)) }
            val status = wm.addNetworkSuggestions(listOf(s))
            Log.i(TAG, "addNetworkSuggestions -> $status")
            if (status != WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
                return finish(Outcome.Failed("network suggestion refused (status $status)", null))
            }
            suggestion = s
            method = "suggestion"
            onProgress?.invoke("Approve “Allow suggested Wi-Fi networks?” in the notification shade")
            begin(SUGGESTION_TIMEOUT_MS)
        }

        private fun begin(timeoutMs: Long) {
            deadline = SystemClock.elapsedRealtime() + timeoutMs
            runCatching { ctx.registerReceiver(supplicant, IntentFilter(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION)) }
            main.postDelayed(poll, 1000)
        }

        /** Non-null once the attempt has an outcome. */
        private fun check(): Outcome? {
            val i = wm.connectionInfo
            val ip = WifiSecurityRules.ipv4(i?.ipAddress ?: 0)
            val onTarget = if (loc) {
                (netId != -1 && i?.networkId == netId) || WifiSecurityRules.unquote(i?.ssid) == ssid
            } else {
                !wifiUpAtStart // fresh panel: whatever Wi-Fi comes up was ours
            }
            if (i?.supplicantState == SupplicantState.COMPLETED && onTarget && ip != null && wifiNetworkUp()) {
                // joined: keep it past a restart. Android 7 and earlier forget a network addNetwork added until the app
                // saves it (a Mirror on its stock Android 6 asked for Wi-Fi again on every boot, 2026-11-25)
                // only once the network this attempt set up is the one that came up: without location access onTarget
                // takes any network. When another one came up, this attempt's entry is untested, so no later save may
                // keep it until it connects itself
                if (netId != -1 && Compat.mustSaveNetworks) {
                    if (i.networkId == netId) saveNetworks(netId)
                }
                return Outcome.Connected(ssid, ip, method)
            }
            if (authErrors >= 2) return failAndRestore("wrong password (authentication failed)")
            if (SystemClock.elapsedRealtime() > deadline) return failAndRestore("timed out")
            return null
        }

        /** Removes an entry this attempt made; only a removal Android confirms lets later saves go ahead. */
        private fun removeOurs(id: Int) {
            val removed = runCatching { wm.removeNetwork(id) }.getOrDefault(false)
            Log.i(TAG, "removeNetwork($id) -> $removed")
            if (removed && Compat.mustSaveNetworks) saveGate.removed(id)
        }

        private fun failAndRestore(reason: String): Outcome.Failed {
            var restored: String? = null
            // an entry addNetwork reused keeps this attempt's credential, so it stays unconfirmed: Android 7 and earlier put
            // the saved one back at the next restart, unless something saves first
            if (netId != -1 && addedNew) removeOurs(netId)
            if (Compat.hasNetworkSuggestions) suggestion?.let { Log.i(TAG, "removeNetworkSuggestions -> ${wm.removeNetworkSuggestions(listOf(it))}") }
            if (netId != -1) {
                if (prevNetId != -1) {
                    Log.i(TAG, "re-select previous network $prevNetId -> ${wm.enableNetwork(prevNetId, true)}")
                    restored = prevSsid ?: "previous network"
                } else if (wifiUpAtStart) {
                    restored = "previous network (auto-join)"
                }
                wm.reconnect()
            } else if (wifiUpAtStart) {
                restored = prevSsid ?: "previous network" // suggestions never displace saved networks
            }
            return Outcome.Failed(reason, restored)
        }

        fun finish(o: Outcome) {
            if (done) return
            done = true
            main.removeCallbacks(poll)
            runCatching { ctx.unregisterReceiver(supplicant) }
            if (job === this) job = null
            Log.i(TAG, "outcome: $o")
            if (o is Outcome.Failed && (o.reason == "cancelled" || o.reason == "superseded")) {
                // Undo a half-done attempt the same way as a failure.
                failAndRestore(o.reason)
            }
            cb(o)
        }
    }

    /** Settings shared by every provisioner. */
    public companion object {
        /** Logcat tag; set it before use to log under your app's own. */
        @Volatile public var logTag: String = "ImprovWifi"
        private val TAG get() = logTag
        /** Same as ESPHome's improv_ble connect timeout. */
        internal const val CONNECT_TIMEOUT_MS: Long = 30_000L
        /** Suggestions wait for the user to approve a notification. */
        internal const val SUGGESTION_TIMEOUT_MS: Long = 120_000L
        /** setWifiEnabled(true) → WIFI_STATE_ENABLED takes a few seconds on the panel. */
        internal const val WIFI_ENABLE_TIMEOUT_MS: Long = 15_000L
        /** After turning Wi-Fi on: wait this long at most for the network to show up in a scan. */
        internal const val FIRST_SCAN_WAIT_MS: Long = 6_000L

        /**
         * The user's switch was on but the Wi-Fi client didn't come back within [WIFI_ENABLE_TIMEOUT_MS] (for example after
         * the panel's own setup hotspot). Not a password problem: callers say "try again", not "check the password".
         */
        public const val RADIO_DIDNT_COME_BACK: String = "The Wi-Fi radio didn't come back. Try again"

        /** Android refused the password for the network's security (a non-ASCII WPA2 or WPA3 password, CodeRabbit on #418). */
        public const val UNUSABLE_PASSWORD: String = "Android can't use that password for this network. Check it, and send it again"

        /**
         * True when a [Outcome.Failed] reason already says what to do ([RADIO_DIDNT_COME_BACK], the unusable password), so a
         * caller adds no "check the password" of its own (#418 verifier).
         */
        public fun saysWhatToDo(reason: String): Boolean =
            reason.startsWith(RADIO_DIDNT_COME_BACK) || reason.startsWith(UNUSABLE_PASSWORD)
    }
}

/**
 * The user's Wi-Fi switch (`Settings.Global.WIFI_ON`): 1 on, 2 on with airplane mode; 0 off, 3 off by airplane mode
 * (AOSP WifiSettingsStore, the same values from Android 6 to 10). Unreadable reads as off here, so the old path
 * (asking Android to turn it on) still runs.
 *
 * Keep in step with `dev.glassworks.platform.WifiRadio.switchedOff` in the core (this library can't depend on it): the
 * same setting and values. The one difference is on purpose: there an unreadable switch counts as on, so the setup
 * hotspot isn't blocked on a guess.
 */
internal fun wifiSwitchOn(ctx: Context): Boolean =
    runCatching { Settings.Global.getInt(ctx.contentResolver, Settings.Global.WIFI_ON) }.getOrNull().let { it == 1 || it == 2 }

/**
 * A suggestion for [ssid] with [password], or null when Android refuses the password for [security]: its builder throws
 * for a WPA2 or WPA3 password that isn't ASCII, and the join runs on the main thread, where a throw is a crash
 * (CodeRabbit on #418). Neither the password nor the reason is logged. WEP and EAP never come here.
 */
internal fun suggestion(ssid: String, security: WifiSecurity, password: String, hidden: Boolean): WifiNetworkSuggestion? {
    if (!Compat.hasNetworkSuggestions) return null // Android 10+, as its caller checks too; lint reads this one
    return runCatching { suggestionOrThrow(ssid, security, password, hidden) }.getOrNull()
}

@androidx.annotation.RequiresApi(29)
private fun suggestionOrThrow(ssid: String, security: WifiSecurity, password: String, hidden: Boolean): WifiNetworkSuggestion {
    val b = WifiNetworkSuggestion.Builder().setSsid(ssid).setIsHiddenSsid(hidden)
    when (security) {
        WifiSecurity.PSK -> b.setWpa2Passphrase(password)
        WifiSecurity.SAE -> b.setWpa3Passphrase(password)
        WifiSecurity.OWE -> b.setIsEnhancedOpen(true)
        WifiSecurity.OPEN, WifiSecurity.WEP, WifiSecurity.EAP -> {}
    }
    return b.build()
}

/** Which saved Wi-Fi network an attempt made, from the ids saved before it (pure, so tests cover it). */
internal object SavedNetworks {
    /**
     * True when [netId] wasn't among [idsBefore], false when it was, null when that can't be known: the list wasn't
     * readable, or Android filters it for this app ([listTrusted] false).
     */
    fun madeHere(netId: Int, idsBefore: Set<Int>?, listTrusted: Boolean): Boolean? =
        if (!listTrusted || idsBefore == null) null else netId !in idsBefore
}

/**
 * Keeps an untested credential from being saved (Android 7 and earlier). `addNetwork` updates a saved network with the
 * same name in place, and its old password can't be read back to restore it; until a restart Android holds the change
 * in memory only, and a restart puts the saved password back, unless `saveConfiguration`, which writes every network,
 * runs first. So every entry an attempt adds or updates is unconfirmed from that moment, whether the attempt is still
 * running, failed, or another network came up instead, and no save runs while any is. Joining that network confirms it;
 * removing an entry the attempt made clears it. A network joined meanwhile lasts until the next restart, as before.
 */
internal class SaveGate(private val store: Store, private val bootId: () -> String?, private val elapsedMs: () -> Long) {

    /** What a gate keeps: the boot it belongs to ([bootId] null when unknown, [elapsedMs] then tells), and the ids. */
    data class Kept(val bootId: String?, val elapsedMs: Long, val ids: Set<Int>)

    /** Where the unconfirmed ids outlive this provisioner and the app's process. [write] says whether it was kept. */
    interface Store {
        fun read(): Kept?
        fun write(kept: Kept): Boolean
    }

    // only this boot's: a restart put every saved credential back, so an older boot's ids mean nothing now
    // A change another process began and never named (it died between beginChange and addNetwork's answer, or its
    // write of the name failed) can't be told apart from a new one's: it becomes ORPHAN, which only a reboot clears
    private val unconfirmed: MutableSet<Int>

    init {
        val kept = store.read()
        val thisBoot = kept != null && sameBoot(kept)
        unconfirmed = (if (thisBoot) kept!!.ids else emptySet()).map { if (it == CHANGING) ORPHAN else it }.toMutableSet()
        // an earlier boot's record is replaced at once with this boot's, so a later process in this boot, when the time
        // since boot has passed the old record's, can't take it for its own ([SaveGateBootReceiver] reads it early)
        if (kept != null && !thisBoot) keep()
    }

    /**
     * Same boot: two ids from the same source ("kernel:", "firstboot:") agree. With one unknown, or ids from different
     * sources (the kernel's was readable then and not now), the clock since boot hasn't gone back (no wall clock).
     */
    private fun sameBoot(k: Kept): Boolean {
        val now = bootId()
        val then = k.bootId
        return if (now != null && then != null && now.substringBefore(':') == then.substringBefore(':')) now == then
        else elapsedMs() >= k.elapsedMs
    }

    /**
     * Records, durably, that an attempt is about to change a network, before it does: false when that can't be kept,
     * and then the attempt mustn't change anything. Until [unconfirmed] or [changeRefused] names the outcome, every save
     * is held, also after the app's process restarts in the same boot.
     */
    @Synchronized fun beginChange(): Boolean {
        unconfirmed += CHANGING
        if (keep()) return true
        unconfirmed -= CHANGING
        return false
    }

    /** addNetwork refused: nothing changed. */
    @Synchronized fun changeRefused() { if (unconfirmed.remove(CHANGING)) keep() }

    @Synchronized fun unconfirmed(netId: Int) {
        unconfirmed -= CHANGING
        unconfirmed += netId
        keep()
    }

    /** The entry is gone (Android confirmed its removal): nothing of it is left to save. */
    @Synchronized fun removed(netId: Int) { if (unconfirmed.remove(netId)) keep() }

    /** Whether saving is safe now; [joined] is the network just joined, which that confirms. */
    @Synchronized fun maySave(joined: Int?): Boolean {
        if (joined != null && unconfirmed.remove(joined)) keep()
        return unconfirmed.isEmpty()
    }

    private fun keep(): Boolean = runCatching { store.write(Kept(bootId(), elapsedMs(), unconfirmed.toSet())) }.getOrDefault(false)

    companion object {
        /** A change recorded before addNetwork returned its id. */
        private const val CHANGING = Int.MIN_VALUE

        /** An earlier process's unnamed change: holds every save until a reboot. */
        private const val ORPHAN = Int.MIN_VALUE + 1

        /**
         * One gate per process, kept in the app's own preferences, so a new provisioner or a restarted app still holds.
         * Tests clear it, since a Robolectric sandbox keeps it from one test to the next.
         */
        @Volatile internal var shared: SaveGate? = null

        fun of(ctx: Context): SaveGate = shared ?: synchronized(this) {
            val app = ctx.applicationContext
            shared ?: SaveGate(PrefsStore(app), { currentBootId(app) }, SystemClock::elapsedRealtime).also { shared = it }
        }

        /** This boot's id: the kernel's, else the wall time system_server noted at boot (fixed for the boot), else unknown. */
        private fun currentBootId(ctx: Context): String? =
            runCatching { java.io.File("/proc/sys/kernel/random/boot_id").readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
                ?.let { "kernel:$it" }
                ?: (if (Compat.hasBootCount) runCatching {
                    Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT)
                }.getOrNull()?.let { "bootcount:$it" } else null)
                ?: runCatching {
                    @SuppressLint("PrivateApi")
                    val sp = Class.forName("android.os.SystemProperties")
                    sp.getMethod("get", String::class.java).invoke(null, "ro.runtime.firstboot") as String
                }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { "firstboot:$it" }
    }

    private class PrefsStore(ctx: Context) : Store {
        private val prefs = ctx.getSharedPreferences("glassworks_improv_save_gate", Context.MODE_PRIVATE)
        override fun read(): Kept? {
            if (!prefs.contains("elapsed")) return null
            return Kept(
                prefs.getString("boot", null), prefs.getLong("elapsed", 0),
                prefs.getStringSet("ids", emptySet()).orEmpty().mapNotNull { it.toIntOrNull() }.toSet(),
            )
        }
        override fun write(kept: Kept): Boolean = prefs.edit().putString("boot", kept.bootId).putLong("elapsed", kept.elapsedMs)
            .putStringSet("ids", kept.ids.map { it.toString() }.toSet()).commit()
    }
}

/**
 * Reads [SaveGate]'s record early in every boot, so a hold an earlier boot kept is recognised as that boot's while the
 * time since boot is still short (the fallback when no boot id can be read), and replaced with this boot's.
 */
internal class SaveGateBootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: android.content.Intent) {
        // Android 8 and later never hold anything (the framework saves networks itself): nothing to read
        if (intent.action == android.content.Intent.ACTION_BOOT_COMPLETED && Compat.mustSaveNetworks) SaveGate.of(context)
    }
}
