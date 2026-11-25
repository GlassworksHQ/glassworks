/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address

/**
 * The panel's IPv4 address on the LAN: the one its setup page, sign-in QR code, Thread join link, radio and camera
 * hand out, and Home Assistant's "IP address" sensor shows. Null while it has none (offline).
 *
 * It comes only from a network Android lists as the panel's own: Wi-Fi or Ethernet, the client side. An access
 * point's interface never counts. On a Mirror in its stock setup mode, `wlan0_ap` has `192.168.43.1` while the Wi-Fi
 * client is on but not connected, and Android lists no network: before, the first address on any interface was taken,
 * and the setup screen told people to open `http://192.168.43.1:8099/setup` "on this network". The panel's own setup
 * hotspot ([HotspotAddress]) and Android's tethering are the same case: Android lists no network for either.
 */
object PanelAddress {
    /**
     * One network Android lists: whether it is the panel on a LAN ([lan]: Wi-Fi or Ethernet, not a VPN or mobile
     * data), whether it is Android's default network ([active]), the IPv4 addresses of its link, and whether it is
     * Wi-Fi ([wifi]; false for Ethernet).
     */
    data class ClientNetwork(val lan: Boolean, val ipv4: List<String>, val active: Boolean = false, val wifi: Boolean = false)

    /**
     * The first usable address of the LAN networks in [networks], the default network's first. With [wifiOnly], only a
     * Wi-Fi network counts: "the panel is on Wi-Fi", which Ethernet alone doesn't make true (Improv's initial state).
     */
    fun pick(networks: List<ClientNetwork>, wifiOnly: Boolean = false): String? = networks
        .filter { it.lan && (!wifiOnly || it.wifi) }
        .sortedByDescending { it.active }
        .flatMap { it.ipv4 }
        .firstOrNull(::usable)

    /** Not loopback, not "any", not link-local (`169.254.x`, what a link gets without DHCP). */
    private fun usable(ip: String): Boolean =
        ip.isNotEmpty() && !ip.startsWith("127.") && !ip.startsWith("169.254.") && ip != "0.0.0.0"

    /** [pick] over the networks [context]'s Android lists now; null if it can't say. API 23 and newer. */
    fun now(context: Context, wifiOnly: Boolean = false): String? = runCatching {
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return null
        val active = cm.activeNetwork
        @Suppress("DEPRECATION") // allNetworks: Wi-Fi can be up while the default network is another one
        val all = (listOfNotNull(active) + cm.allNetworks).distinct()
        pick(all.map { n ->
            val caps = cm.getNetworkCapabilities(n)
            val vpn = caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            ClientNetwork(
                lan = caps != null && !vpn &&
                    (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)),
                ipv4 = cm.getLinkProperties(n)?.linkAddresses.orEmpty().mapNotNull { (it.address as? Inet4Address)?.hostAddress },
                active = n == active,
                wifi = caps != null && !vpn && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            )
        }, wifiOnly)
    }.getOrNull()
}
