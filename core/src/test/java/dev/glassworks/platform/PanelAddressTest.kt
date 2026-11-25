/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.platform

import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import dev.glassworks.platform.PanelAddress.ClientNetwork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.net.InetAddress

/**
 * The panel's LAN address comes only from a Wi-Fi or Ethernet network Android lists, never from an access point's
 * interface. The Mirror in its stock setup mode (2026-11-25): `wlan0_ap` up with `192.168.43.1`, the Wi-Fi client on
 * but not connected, and Android listing no network; the setup screen offered `http://192.168.43.1:8099/setup`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PanelAddressTest {
    private val app = RuntimeEnvironment.getApplication()
    private val cm = shadowOf(app.getSystemService(ConnectivityManager::class.java))

    @Before fun noNetworks() {
        cm.clearAllNetworks()
        cm.setActiveNetworkInfo(null)
    }

    // ---- the pure pick

    @Test fun theMirrorsOwnAccessPointWithNoNetworkListedIsNoAddress() {
        // Android lists nothing: wlan0_ap's 192.168.43.1 never reaches the pick.
        assertNull(PanelAddress.pick(emptyList()))
    }

    @Test fun aWifiClientGivesItsAddress() {
        assertEquals("192.0.2.10", PanelAddress.pick(listOf(ClientNetwork(lan = true, ipv4 = listOf("192.0.2.10"), active = true))))
    }

    @Test fun withATetheredAccessPointUpTooTheClientsAddressWins() {
        // Tethering adds no network to Android's list: only the client's link is there.
        val client = ClientNetwork(lan = true, ipv4 = listOf("192.0.2.10"), active = true)
        assertEquals("192.0.2.10", PanelAddress.pick(listOf(client)))
    }

    @Test fun ethernetCounts() {
        assertEquals("192.0.2.20", PanelAddress.pick(listOf(ClientNetwork(lan = true, ipv4 = listOf("192.0.2.20")))))
    }

    @Test fun theDefaultNetworkGoesFirst() {
        val other = ClientNetwork(lan = true, ipv4 = listOf("192.0.2.20"))
        val default = ClientNetwork(lan = true, ipv4 = listOf("192.0.2.10"), active = true)
        assertEquals("192.0.2.10", PanelAddress.pick(listOf(other, default)))
    }

    @Test fun mobileDataAndVpnsDontCount() {
        assertNull(PanelAddress.pick(listOf(ClientNetwork(lan = false, ipv4 = listOf("198.51.100.7"), active = true))))
    }

    @Test fun linkLocalAndLoopbackAddressesAreSkipped() {
        assertNull(PanelAddress.pick(listOf(ClientNetwork(lan = true, ipv4 = listOf("169.254.10.2", "127.0.0.1")))))
        assertEquals("192.0.2.10", PanelAddress.pick(listOf(ClientNetwork(lan = true, ipv4 = listOf("169.254.10.2", "192.0.2.10")))))
    }

    // ---- what Android lists

    /**
     * A connected network of [type] with [transports] and [ip] on its link; the default one when [active]. Robolectric's
     * default network is the one whose id is the active network info's type.
     */
    private fun network(type: Int, ip: String, vararg transports: Int, active: Boolean = false) {
        val n = ShadowNetwork.newInstance(type)
        val ni = ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED, type, 0, true, NetworkInfo.State.CONNECTED)
        cm.addNetwork(n, ni)
        if (active) cm.setActiveNetworkInfo(ni)
        cm.setNetworkCapabilities(n, ShadowNetworkCapabilities.newInstance().also { c -> transports.forEach { shadowOf(c).addTransportType(it) } })
        val address = ReflectionHelpers.callConstructor(
            LinkAddress::class.java, ClassParameter.from(InetAddress::class.java, InetAddress.getByName(ip)), ClassParameter.from(Int::class.java, 24),
        )
        cm.setLinkProperties(n, LinkProperties().also { ReflectionHelpers.callInstanceMethod<Any>(it, "addLinkAddress", ClassParameter.from(LinkAddress::class.java, address)) })
    }

    @Test fun noNetworkListedIsOfflineWhateverInterfacesAreUp() {
        // The JVM's own interfaces are up here, as wlan0_ap was on the Mirror: the old interface scan took one of them.
        assertNull(PanelAddress.now(app))
    }

    @Test fun androidsWifiNetworkGivesItsAddress() {
        network(ConnectivityManager.TYPE_WIFI, "192.0.2.10", NetworkCapabilities.TRANSPORT_WIFI, active = true)
        assertEquals("192.0.2.10", PanelAddress.now(app))
    }

    @Test fun androidsEthernetNetworkGivesItsAddress() {
        network(ConnectivityManager.TYPE_ETHERNET, "192.0.2.20", NetworkCapabilities.TRANSPORT_ETHERNET, active = true)
        assertEquals("192.0.2.20", PanelAddress.now(app))
    }

    @Test fun mobileDataAloneIsNoLanAddress() {
        network(ConnectivityManager.TYPE_MOBILE, "198.51.100.7", NetworkCapabilities.TRANSPORT_CELLULAR, active = true)
        assertNull(PanelAddress.now(app))
    }

    @Test fun aVpnOverWifiIsSkippedForTheWifiItself() {
        network(ConnectivityManager.TYPE_VPN, "198.51.100.7", NetworkCapabilities.TRANSPORT_VPN, NetworkCapabilities.TRANSPORT_WIFI, active = true)
        network(ConnectivityManager.TYPE_WIFI, "192.0.2.10", NetworkCapabilities.TRANSPORT_WIFI)
        assertEquals("192.0.2.10", PanelAddress.now(app))
    }

    @Test fun theDefaultNetworksAddressComesBeforeAnotherLanNetworks() {
        network(ConnectivityManager.TYPE_WIFI, "192.0.2.10", NetworkCapabilities.TRANSPORT_WIFI)
        network(ConnectivityManager.TYPE_ETHERNET, "192.0.2.20", NetworkCapabilities.TRANSPORT_ETHERNET, active = true)
        assertEquals("192.0.2.20", PanelAddress.now(app))
    }
}
