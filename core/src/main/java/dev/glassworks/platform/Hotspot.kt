/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.util.Log
import dev.glassworks.secrets.SecretStore
import java.io.File
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
            HotspotKind.HIDDEN_AP -> HiddenApHotspot(
                ReflectedAp(context, handler), handler, address = { HotspotAddress.now(context) },
                undo = ApUndoFile(File(context.applicationContext.noBackupFilesDir, UNDO_FILE), SecretStore.keystore()) { Log.w(TAG, it) },
            )
        }

        /** [ApUndoFile]'s file in `noBackupFilesDir`. Panels keep it: never rename it. */
        const val UNDO_FILE = "setup_hotspot_undo"
        private const val TAG = "Glassworks.Hotspot"
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
    NOT_TETHERED("The setup Wi-Fi started, but Android gave it no address, so a phone couldn't reach the panel."),
    WIFI_OFF("The panel's Wi-Fi is turned off, and the panel can't turn it on itself. Turn on Wi-Fi first."),
    FAILED("The setup Wi-Fi didn't start."),
}

/** The address a phone on the hotspot reaches the panel on. */
object HotspotAddress {
    /** One network interface: its name, whether it's up, and its IPv4 addresses. */
    data class Interface(val name: String, val up: Boolean, val ipv4: List<String>)

    /**
     * The panel's IPv4 address on its hotspot, or null before it has one. Only Wi-Fi interfaces count (`wlan0`,
     * `ap0`, `swlan0`, `softap0`), and only private addresses: a tablet's mobile data has a 10.x address too,
     * on `rmnet`.
     *
     * [client] holds the IPv4 addresses of Android's own Wi-Fi networks (the client side). Those never count, but the
     * interface they sit on still does. On the S1 the AP itself is `wlan0` (measured, docs/research/setup-hotspot-portal-and-name.md).
     * That Android still lists a client network on the AP's interface is a precaution, not measured: if it does, the AP's
     * address still counts (#418 verifier).
     *
     * Among the addresses left, name order picks the interface: one named for an access point first, then the highest
     * `wlan` (with the client still up on `wlan0`, AOSP puts the AP on `wlan1`). Within an interface, an address in a
     * subnet Android gives its hotspots ([AP_SUBNETS]) wins over the order the interface lists them in.
     */
    fun pick(interfaces: List<Interface>, client: Set<String> = emptySet()): String? = interfaces
        .filter { it.up && WIFI.matches(it.name) }
        .sortedWith(compareBy({ it.name.startsWith("wlan") }, { -(it.name.removePrefix("wlan").toIntOrNull() ?: 0) }))
        .firstNotNullOfOrNull { i ->
            val left = i.ipv4.filter { private(it) && it !in client }
            left.firstOrNull { ip -> AP_SUBNETS.any { ip.startsWith(it) } } ?: left.firstOrNull()
        }

    /**
     * Subnets Android gives its own access points: `192.168.43.0/24`, the Wi-Fi hotspot's on older Android (the S1's
     * local-only hotspot on Android 10 took `192.168.43.x`, measured), and `192.168.49.0/24`, Wi-Fi Direct's group
     * owner. Newer Android may use another `192.168.x.0/24`; then name order alone decides.
     */
    private val AP_SUBNETS = listOf("192.168.43.", "192.168.49.")

    /** [pick] over the device's interfaces now, leaving out the addresses [context]'s Wi-Fi client networks have. */
    fun now(context: Context): String? = runCatching {
        pick(NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().map { i ->
            Interface(i.name, i.isUp, i.inetAddresses.toList().filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress })
        }, clientAddresses(context))
    }.getOrNull()

    /** The IPv4 addresses of Android's own Wi-Fi networks: the client's, never our AP's (Android lists no network for it). */
    @Suppress("DEPRECATION") // allNetworks: every Wi-Fi network, not only the default one
    private fun clientAddresses(context: Context): Set<String> = runCatching {
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return emptySet()
        cm.allNetworks.filter { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
            .flatMap { cm.getLinkProperties(it)?.linkAddresses.orEmpty() }
            .mapNotNull { (it.address as? Inet4Address)?.hostAddress }.toSet()
    }.getOrDefault(emptySet())

    /**
     * [remote] is another device on the hotspot's network: an IPv4 address in the subnet of [ap], the hotspot's own
     * address with its [prefix] length. The panel's own address doesn't count.
     */
    fun onSubnet(remote: String?, ap: String, prefix: Int): Boolean {
        val r = remote?.removePrefix("::ffff:")?.let(::bits) ?: return false
        val a = bits(ap) ?: return false
        if (r == a || prefix !in 1..32) return false
        val mask = (-1L shl (32 - prefix)) and 0xFFFF_FFFFL
        return (r and mask) == (a and mask)
    }

    /** One IPv4 address on the device, with its prefix length. */
    data class Address(val ip: String, val prefix: Int)

    /**
     * [remote] is another device on the hotspot's network: [ap] is the hotspot's address ([now]), and [addresses] the
     * device's IPv4 addresses, where its prefix length is found. False while the hotspot has no address.
     */
    fun isClient(remote: String?, ap: String?, addresses: List<Address>): Boolean {
        val own = addresses.firstOrNull { it.ip == ap } ?: return false
        return onSubnet(remote, own.ip, own.prefix)
    }

    /** [isClient] for the hotspot and the device's addresses as they are now. */
    fun isClient(context: Context, remote: String?): Boolean = runCatching {
        val addresses = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().flatMap { it.interfaceAddresses }
            .mapNotNull { a -> (a.address as? Inet4Address)?.hostAddress?.let { Address(it, a.networkPrefixLength.toInt()) } }
        isClient(remote, now(context), addresses)
    }.getOrDefault(false)

    private val WIFI = Regex("""(wlan|ap|swlan|softap)\d+""")

    private fun bits(ip: String): Long? {
        val o = ip.split('.').map { it.toIntOrNull() ?: return null }
        if (o.size != 4 || o.any { it !in 0..255 }) return null
        return o.fold(0L) { acc, b -> (acc shl 8) or b.toLong() }
    }

    private fun private(ip: String): Boolean {
        val o = ip.split('.').mapNotNull { it.toIntOrNull() }
        if (o.size != 4) return false
        return o[0] == 10 || (o[0] == 172 && o[1] in 16..31) || (o[0] == 192 && o[1] == 168)
    }
}
