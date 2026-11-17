package sh.edm.s1kiosk.improv

/**
 * The card the panel shows once Improv's session ends with the panel online (#460). Improv also ends that way when
 * the Wi-Fi came another way (its "provisioned externally"), so the card's mark and words follow the way it came:
 * Bluetooth only for Improv over BLE.
 */
object ImprovDone {
    /** How the panel's Wi-Fi was sent. */
    enum class Via {
        /** Improv over BLE: Home Assistant's "Improv via BLE", or improv-wifi.com on a phone. */
        BLUETOOTH,

        /** Kiln on a computer, over USB (Improv Serial or the setup channel). */
        COMPUTER,

        /** The panel's own setup page, on a phone that joined the panel's setup Wi-Fi. */
        SETUP_PAGE,

        /** Not sent to the app: Android's Wi-Fi settings, Ethernet, or a saved network coming back. */
        OTHER,
    }

    enum class Mark { BLUETOOTH, WIFI }

    data class Card(val label: String, val title: String, val body: String, val mark: Mark)

    /** [ssid]: the network joined, when the app knows it. [url]: where setup goes on (Improv over BLE gives one). */
    fun card(via: Via, ssid: String?, url: String?): Card {
        val title = ssid?.takeIf { it.isNotEmpty() }?.let { "Connected to $it" } ?: "Panel is online"
        return when (via) {
            Via.BLUETOOTH -> Card("Wi-Fi setup · done", title, url?.let { "Continue setup at $it" } ?: "Wi-Fi is set up.", Mark.BLUETOOTH)
            Via.SETUP_PAGE -> Card("Wi-Fi setup · done", title, "Sent from the setup page on your phone. Setup carries on here.", Mark.WIFI)
            Via.COMPUTER -> Card("Wi-Fi setup · done", title, "Sent from Kiln on your computer.", Mark.WIFI)
            Via.OTHER -> Card("Wi-Fi · connected", title, "Wi-Fi is set up.", Mark.WIFI)
        }
    }
}
