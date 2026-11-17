/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.platform

import android.content.Context
import android.os.Handler
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * The panel's own Wi-Fi for setup (#197): a phone joins it and opens the setup page. One start at a time;
 * [stop] puts back anything the start changed. Events come on the handler the hotspot was made with.
 */
interface Hotspot {
    sealed interface Event {
        data class Started(val network: HotspotNetwork) : Event

        /** It didn't start. No [Stopped] follows. */
        data class Failed(val why: HotspotFailure) : Event

        /** Android stopped it (the user turned on tethering, Wi-Fi went away). Not sent after [stop]. */
        data object Stopped : Event
    }

    fun start(onEvent: (Event) -> Unit)

    /** Stops it, or the start under way, and puts back what the start changed. Safe to call when off. */
    fun stop()

    companion object {
        /** The way this Android allows ([PlatformApi.hotspotKind]). */
        fun of(context: Context, handler: Handler, platform: PlatformApi = Platform): Hotspot = when (platform.hotspotKind) {
            HotspotKind.LOCAL_ONLY -> LocalOnlyHotspot(context, handler, platform)
            HotspotKind.HIDDEN_AP -> HiddenApHotspot(ReflectedAp(context, handler), handler)
        }
    }
}

/** A hotspot's network: what a phone needs to join it. */
data class HotspotNetwork(val ssid: String, val passphrase: String) {
    /**
     * The text of a "join this Wi-Fi" QR code, which iOS and Android cameras read: `WIFI:T:WPA;S:…;P:…;;`.
     * Backslash, `;`, `,`, `:` and `"` are escaped with a backslash.
     */
    fun qrText(): String = "WIFI:T:WPA;S:${escape(ssid)};P:${escape(passphrase)};;"

    override fun toString() = "HotspotNetwork" // neither the name nor the key in a log

    private fun escape(s: String) = s.replace(Regex("""([\\;,:"])"""), """\\$1""")
}

/** Why a hotspot didn't start, in words for the setup screen. */
enum class HotspotFailure(val text: String) {
    NO_PERMISSION("The panel isn't allowed to open its own Wi-Fi."),
    LOCATION_OFF("Android opens the panel's own Wi-Fi only while location is on."),
    TETHERING_ON("Android's own hotspot is on. Turn it off to open the setup Wi-Fi."),
    NOT_ALLOWED("This device doesn't let apps open a Wi-Fi hotspot."),
    NO_CHANNEL("No free Wi-Fi channel for the setup Wi-Fi."),
    FAILED("The setup Wi-Fi didn't start."),
}

/** The address a phone on the hotspot reaches the panel on. */
object HotspotAddress {
    /** One network interface: its name, whether it's up, and its IPv4 addresses. */
    data class Interface(val name: String, val up: Boolean, val ipv4: List<String>)

    /**
     * The panel's IPv4 address on its hotspot, or null before it has one. Only Wi-Fi interfaces count (`wlan0`,
     * `ap0`, `swlan0`, `softap0`), and only private addresses: a tablet's mobile data has a 10.x address too,
     * on `rmnet`. An interface named for an access point wins over `wlan`.
     */
    fun pick(interfaces: List<Interface>): String? = interfaces
        .filter { it.up && WIFI.matches(it.name) }
        .sortedBy { if (it.name.startsWith("wlan")) 1 else 0 }
        .firstNotNullOfOrNull { i -> i.ipv4.firstOrNull(::private) }

    /** [pick] over the device's interfaces now. */
    fun now(): String? = runCatching {
        pick(NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().map { i ->
            Interface(i.name, i.isUp, i.inetAddresses.toList().filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress })
        })
    }.getOrNull()

    private val WIFI = Regex("""(wlan|ap|swlan|softap)\d+""")

    private fun private(ip: String): Boolean {
        val o = ip.split('.').mapNotNull { it.toIntOrNull() }
        if (o.size != 4) return false
        return o[0] == 10 || (o[0] == 172 && o[1] in 16..31) || (o[0] == 192 && o[1] == 168)
    }
}
