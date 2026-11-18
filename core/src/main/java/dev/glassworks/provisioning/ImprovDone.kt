package dev.glassworks.provisioning

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

    /**
     * How the Wi-Fi was last sent, and the network it named: Improv's own join over BLE and an installer's join (Kiln,
     * the setup page) both say so. A new Improv session forgets it, unless that join is still under way (Improv's boot
     * check can start a session mid-join, CodeRabbit on #470), and a join that fails forgets it too: Android may go back
     * to the old network, which the app didn't pick. A network that came another way gets the [Via.OTHER] card, with no
     * name. Main thread.
     */
    class JoinPath {
        private var via: Via? = null
        private var ssid: String? = null
        private var joining = false

        fun sessionStarted() { if (!joining) forget() }

        fun sent(via: Via, ssid: String) { this.via = via; this.ssid = ssid; joining = true }

        /** The join sent last went through. */
        fun joined() { joining = false }

        /**
         * A join sent [via] failed. Only the latest join's failure counts: a Bluetooth join that a newer installer join
         * superseded fails as "superseded" just after, and mustn't wipe the newer one.
         */
        fun failed(via: Via) { if (this.via == via) forget() }

        private fun forget() { via = null; ssid = null; joining = false }

        /** The done card for the session as it is; [url]: where setup goes on (Improv over BLE gives one). */
        fun card(url: String?): Card = via?.let { card(it, ssid, url) } ?: card(Via.OTHER, null, url)
    }

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
