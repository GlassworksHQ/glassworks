/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.provisioning

import android.content.Context
import android.os.Handler
import android.util.Log
import dev.glassworks.improv.WifiProvisioner
import dev.glassworks.net.HappyEyeballs
import dev.glassworks.net.WifiChange
import dev.glassworks.platform.NetworkUp

/**
 * A Wi-Fi change from the settings (#660) on the device: [WifiChange.Radio] over Improv's provisioner
 * ([ImprovController], the one every join goes through), Home Assistant's address for [reaches], and the setup hotspot
 * for [armHotspot]. Main thread, but for the Home Assistant probe, which runs on a thread of its own.
 */
class SettingsWifiRadio(
    private val context: Context,
    private val main: Handler,
    /** Home Assistant's base address the panel uses now, or null while it has none. */
    private val haBase: () -> String?,
    private val armHotspot: () -> Unit,
    private val connector: HappyEyeballs = HappyEyeballs(),
) : WifiChange.Radio {
    private val p: WifiProvisioner? get() = ImprovController.provisioner

    override fun block(): WifiChange.Block? {
        val p = p ?: return WifiChange.Block.NOT_READY
        return when (runCatching { p.switchBlock() }.getOrDefault(WifiProvisioner.SwitchBlock.NO_LOCATION)) {
            null -> null
            WifiProvisioner.SwitchBlock.SUGGESTIONS_ONLY -> WifiChange.Block.SUGGESTIONS_ONLY
            WifiProvisioner.SwitchBlock.NO_LOCATION -> WifiChange.Block.NO_LOCATION
        }
    }

    override fun current(): WifiChange.Network? = runCatching { p?.onNetwork() }.getOrNull()?.let { WifiChange.Network(it.name, it.netId) }

    override fun isSaved(name: String): Boolean = runCatching { p?.isSaved(name) }.getOrNull() == true

    /** Names from the last scan, strongest first; asks for a new scan when read, for the next look. */
    override fun nearby(): List<String> {
        val p = p ?: return emptyList()
        val now = current()?.name
        val list = runCatching { p.scan() }.getOrDefault(emptyList())
            .sortedByDescending { it.rssi }.map { it.ssid }.distinct().filter { it != now }.take(NEARBY_MAX)
        p.requestScan()
        return list
    }

    override fun join(name: String, password: String, done: (WifiChange.Joined) -> Unit) {
        val started = ImprovController.joinFromSettings(name, password) { o ->
            done(when (o) {
                is WifiProvisioner.Outcome.Connected -> WifiChange.Joined.Ok
                is WifiProvisioner.Outcome.Failed -> joined(o.reason)
            })
        }
        if (!started) done(WifiChange.Joined.Failed(WifiChange.Why.NO_CONNECTION))
    }

    override fun cancel() { p?.cancel() }

    override fun goBack(to: WifiChange.Network, leave: String, forget: Boolean, done: (Boolean) -> Unit) {
        val started = ImprovController.goBackFromSettings(WifiProvisioner.OnNetwork(to.name, to.id), leave, forget) { o ->
            done(o is WifiProvisioner.Outcome.Connected)
        }
        if (!started) done(false)
    }

    /**
     * Home Assistant's port answers a connection at its address: reachable. A connection, not a request, so a
     * certificate the app doesn't trust (Home Assistant on https with its own) doesn't read as out of reach. Without an
     * address yet, being on a network is enough: there is nothing else to reach.
     */
    override fun reaches(done: (Boolean) -> Unit) {
        val uri = haBase()?.let { runCatching { java.net.URI(it) }.getOrNull() }
        val host = uri?.host
        if (host == null) return done(NetworkUp.now(context))
        val port = if (uri.port > 0) uri.port else if (uri.scheme.equals("https", ignoreCase = true)) 443 else 80
        Thread({
            val ok = runCatching { connector.connect(host, port, CONNECT_MS).close(); true }
                .onFailure { Log.i(TAG, "Wi-Fi change: Home Assistant didn't answer (${it.javaClass.simpleName})") }
                .getOrDefault(false)
            main.post { done(ok) }
        }, "gw-wifi-change-ha").apply { isDaemon = true; start() }
    }

    override fun armHotspot() = armHotspot.invoke()

    companion object {
        private const val TAG = "Glassworks.Wifi"
        private const val NEARBY_MAX = 12
        private const val CONNECT_MS = 4_000

        /** The provisioner's failure reasons as a change's [WifiChange.Joined]. */
        fun joined(reason: String): WifiChange.Joined = when {
            reason == "superseded" -> WifiChange.Joined.Superseded
            reason == "cancelled" -> WifiChange.Joined.Failed(WifiChange.Why.TIMED_OUT)
            // An authentication failure, a password Android refuses, or one that doesn't fit the network's security.
            reason.contains("password", ignoreCase = true) || reason.startsWith("WEP keys") -> WifiChange.Joined.Failed(WifiChange.Why.PASSWORD)
            else -> WifiChange.Joined.Failed(WifiChange.Why.NO_CONNECTION)
        }
    }
}

/** [WifiChange.Store] in its own file, `wifi_change.xml`: a factory reset (which clears `kiosk.xml`) keeps a change's way back. */
class WifiChangeStore(context: Context) : WifiChange.Store {
    private val sp = context.applicationContext.getSharedPreferences("wifi_change", Context.MODE_PRIVATE)

    override fun load(): WifiChange.Pending? {
        val target = sp.getString(TARGET, null) ?: return null
        val prev = sp.getString(PREVIOUS, null) ?: return null
        val id = sp.getInt(PREVIOUS_ID, -1).takeIf { it >= 0 } ?: return null
        return WifiChange.Pending(target, WifiChange.Network(prev, id), sp.getBoolean(FORGET, false))
    }

    override fun save(p: WifiChange.Pending?): Boolean = sp.edit().apply {
        if (p == null) clear()
        else putString(TARGET, p.target).putString(PREVIOUS, p.previous.name).putInt(PREVIOUS_ID, p.previous.id).putBoolean(FORGET, p.forget)
    }.commit()

    private companion object {
        const val TARGET = "target"
        const val PREVIOUS = "previous"
        const val PREVIOUS_ID = "previous_id"
        const val FORGET = "forget"
    }
}
