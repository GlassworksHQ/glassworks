package sh.edm.s1kiosk.esphome

import java.util.Locale

/**
 * What the panel calls itself to Home Assistant (#260, backported from #262 to 0.4.4). Up to 0.4.3
 * every panel announced `s1-panel`, so adding a second one met HA's "Name conflict" dialog (#503).
 * The node name now follows ESPHome's `name_add_mac_suffix`: what the device is, then the last three
 * bytes of the MAC it reports. HA keys its config entry and entity unique ids by that MAC, not by the
 * name, so a panel HA already has keeps its device and entity ids (docs/esphome.md#names).
 */
object DeviceName {
    /** The frame's id, as on main and in the mDNS `board` record. */
    const val BOARD = "aqara-s1-plus"

    /** What Home Assistant shows for the panel, until the user renames it there. */
    const val FRIENDLY = "Aqara S1 Plus"

    private val MAC = Regex("^(?:[0-9A-Fa-f]{2}:){3}([0-9A-Fa-f]{2}):([0-9A-Fa-f]{2}):([0-9A-Fa-f]{2})$")

    /** `aqara-s1-plus-a1b2c3` for the MAC `..:A1:B2:C3`; null when [mac] isn't `AA:BB:CC:DD:EE:FF`. */
    fun node(mac: String): String? {
        val suffix = MAC.matchEntire(mac)?.groupValues?.drop(1)?.joinToString("") ?: return null
        return "$BOARD-${suffix.lowercase(Locale.ROOT)}"
    }
}
