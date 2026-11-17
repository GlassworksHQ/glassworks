package sh.edm.s1kiosk.setup

import android.net.wifi.WifiManager
import dev.glassworks.frame.Input

/**
 * What the loading screen says when the dashboard didn't load because the panel has no network.
 *
 * After a reboot the panel joins its saved Wi-Fi a few seconds after the app starts. Saying "No network
 * yet" plus how to set up Wi-Fi in that window is wrong and alarming, so while Wi-Fi is on and a join is
 * underway or expected, it says "Connecting to Wi-Fi…" instead. The setup hint (setup screen, Improv)
 * comes only when there is nothing to wait for: Wi-Fi off, no saved network, or no connection within
 * [graceMs] (the same 30 s after which Improv starts by itself, ImprovController.BOOT_GRACE_MS).
 * "Wi-Fi is off" only when Android said so: a read that failed says "No network yet" (#448).
 * Never shows an SSID. Pure, for tests; MainActivity gathers the [Facts].
 */
object OfflineText {

    data class Facts(
        /** Wi-Fi is on (true), off (false), or Android didn't say (null, see [wifiEnabled]). */
        val wifiEnabled: Boolean?,
        /** Android lists a saved network (true), lists none (false), or won't tell this app (null). */
        val savedNetwork: Boolean?,
        /** Wi-Fi is associating, authenticating or getting an address right now. */
        val joining: Boolean,
        /** The panel was online over Wi-Fi before (or reached Home Assistant): it has a network to rejoin. */
        val wasOnlineBefore: Boolean,
        /** How long the panel has been without a network (from app start at boot). */
        val offlineForMs: Long,
    )

    /** [action]: the button under the text, null on a screen that takes no tap. */
    data class Text(val title: String, val detail: String, val connecting: Boolean, val action: String? = null)

    const val CONNECTING_TITLE = "Connecting to Wi-Fi…"
    const val CONNECTING_DETAIL = "Home Assistant loads as soon as it's connected"
    const val NO_NETWORK_TITLE = "No network yet"
    const val WIFI_OFF_TITLE = "Wi-Fi is off"
    const val SETUP_HINT = "Set it up from the setup screen, or from Home Assistant."
    /** The button under [SETUP_HINT]: Android's Wi-Fi settings, where Wi-Fi can be turned on and a network chosen. */
    const val SETUP_ACTION = "Set up Wi-Fi"

    /**
     * Wi-Fi is joining a network right now, from WifiManager's SupplicantState and ConnectivityManager's
     * Wi-Fi NetworkInfo.DetailedState (enum names; both readable without location on Android 10).
     * COMPLETED / CONNECTED while the panel is still offline = associated, waiting for an address.
     */
    fun joining(supplicantState: String?, detailedState: String?): Boolean =
        supplicantState in JOINING_SUPPLICANT || detailedState in JOINING_DETAILED

    private val JOINING_SUPPLICANT = setOf(
        "AUTHENTICATING", "ASSOCIATING", "ASSOCIATED", "FOUR_WAY_HANDSHAKE", "GROUP_HANDSHAKE", "COMPLETED",
    )
    private val JOINING_DETAILED = setOf("CONNECTING", "AUTHENTICATING", "OBTAINING_IPADDR", "CONNECTED")

    /**
     * Whether Wi-Fi is on, from WifiManager.getWifiState: null when the read failed or Android says
     * WIFI_STATE_UNKNOWN. Turning on counts as on, turning off as off.
     */
    fun wifiEnabled(wifiState: Int?): Boolean? = when (wifiState) {
        WifiManager.WIFI_STATE_ENABLED, WifiManager.WIFI_STATE_ENABLING -> true
        WifiManager.WIFI_STATE_DISABLED, WifiManager.WIFI_STATE_DISABLING -> false
        else -> null
    }

    /** A join is underway or expected: Wi-Fi not off and either joining now or with a network to join. */
    fun expectsJoin(f: Facts): Boolean =
        f.wifiEnabled != false && (f.joining || f.savedNetwork == true || (f.savedNetwork == null && f.wasOnlineBefore))

    /** [input]: the frame's. Without touch there is no [SETUP_ACTION]: nobody could press it. */
    fun decide(f: Facts, graceMs: Long, input: Input): Text {
        val action = SETUP_ACTION.takeIf { input == Input.TOUCH }
        return when {
            expectsJoin(f) && f.offlineForMs < graceMs -> Text(CONNECTING_TITLE, CONNECTING_DETAIL, connecting = true)
            f.wifiEnabled == false -> Text(WIFI_OFF_TITLE, SETUP_HINT, connecting = false, action)
            else -> Text(NO_NETWORK_TITLE, SETUP_HINT, connecting = false, action)
        }
    }
}
