package dev.glassworks.radio

/**
 * The panel's EFR32 radio modes (frames/aqara-s1-plus/docs/radio.md). [id] is what is stored (app prefs and the agent's
 * mode setting, [dev.glassworks.frame.RadioSetting.MODE]); [label] is the option shown in Home Assistant's `Radio mode` select.
 *
 * There is no "Aqara (stock)" mode: with Aqara's launcher/apps disabled and its cloud blocked nothing
 * can use the hub's own Zigbee network. Stock comes back by uninstalling the app (or
 * `frames/aqara-s1-plus/tools/install.sh --undo`): Aqara's services are init services started on every boot.
 *
 * Roles: Zigbee coordinator (EZSP over TCP for ZHA), Zigbee router (repeater in an existing mesh, no
 * endpoints), Thread border router, Thread router (repeater joining an existing network).
 *
 * [needsHelper]: the mode runs processes as root (TCP bridge on the root-only EZSP pty, otbr-agent on
 * the root-only CPC socket) and so needs the frame agent ([dev.glassworks.frame.RadioAgent]). [OFF] only
 * stops the vendor's radio services, which the app uid can do on the S1 (SELinux permissive).
 * [minHelperVersion]: the radio contract version the mode needs (v2: router modes, see [RadioReports]).
 */
enum class RadioMode(val id: String, val label: String, val needsHelper: Boolean, val minHelperVersion: Int = 1) {
    OFF("off", "Off", false),
    ZIGBEE_TCP("zigbee_tcp", "Zigbee coordinator (TCP)", true),
    /** Repeater in an existing Zigbee mesh: the app's EZSP host (ZigbeeRouterHost) drives zigbeed. */
    ZIGBEE_ROUTER("zigbee_router", "Zigbee router (repeater)", true, minHelperVersion = 2),
    THREAD("thread", "Thread border router", true),
    /** Thread repeater (FTD) joining an existing network with a dataset from HA; no BR/REST/mDNS. */
    THREAD_ROUTER("thread_router", "Thread router (repeater)", true, minHelperVersion = 2);

    // Concurrent Zigbee + Thread on the one multi-PAN radio (shared channel) is deliberately not a
    // mode here: Home Assistant deprecated multiprotocol for the same hardware design (frames/aqara-s1-plus/docs/radio.md).
    // The helper still accepts the experimental id "zigbee_tcp_thread" when set by hand.

    companion object {
        val DEFAULT = OFF
        val LABELS: List<String> = entries.map { it.label }

        /**
         * Labels before 0.4.3 ("Zigbee router", "Thread router"): still accepted when they arrive (an
         * automation written for the old select options, a stored label), never offered.
         */
        val LEGACY_LABELS: Map<String, RadioMode> = mapOf("Zigbee router" to ZIGBEE_ROUTER, "Thread router" to THREAD_ROUTER)

        fun fromLabel(label: String): RadioMode? = entries.firstOrNull { it.label == label } ?: LEGACY_LABELS[label]
        fun fromId(id: String): RadioMode? = entries.firstOrNull { it.id == id }

        /**
         * Stored value -> mode. Accepts ids and labels (the stub stored labels); unknown or retired
         * values ("Aqara (stock)", the stub's "Thread (future)") fall back to [DEFAULT].
         */
        fun parse(stored: String?): RadioMode =
            stored?.let { fromId(it) ?: fromLabel(it) } ?: DEFAULT
    }
}

/**
 * The Zigbee stack behind the coordinator mode (helper v4, [dev.glassworks.frame.RadioSetting.ZIGBEE_STACK]). Aqara's
 * zigbeed 7.3.3 speaks EZSP 12 (ZHA only); Silicon Labs' zigbeed 8.2.2 speaks EZSP 18 (Zigbee2MQTT's
 * `ember` adapter, and ZHA), runs on the same stock radio firmware, and is downloaded by the panel from
 * Silicon Labs ([SisdkZigbeed]). The Zigbee router mode always uses Aqara's (its host speaks EZSP 12).
 */
enum class ZigbeeStack(val id: String, val label: String) {
    AQARA("aqara", "Aqara 7.3.3 (EZSP 12, ZHA)"),
    SISDK("sisdk", "Silicon Labs 8.2.2 (EZSP 18, Zigbee2MQTT, experimental)");

    companion object {
        val DEFAULT = AQARA
        val LABELS: List<String> = entries.map { it.label }
        fun fromLabel(label: String): ZigbeeStack? = entries.firstOrNull { it.label == label }
        fun parse(stored: String?): ZigbeeStack = entries.firstOrNull { it.id == stored || it.label == stored } ?: DEFAULT
    }
}

/**
 * The radio's report formats and the versions they came with (docs/frame-agent.md#capabilities): what the radio pane
 * parses from its frame agent ([dev.glassworks.frame.RadioAgent]). The names behind them are the frame's (the S1's
 * `dev.glassworks.radio.RadioHelper`, in its own app).
 */
object RadioReports {
    /** The radio contract version this app knows (the S1's helper v4). */
    const val VERSION = 4
    /** v3: the firewall (the allow list and what it enforces); otherwise the same as v2. */
    const val FIREWALL_VERSION = 3
    /**
     * v4 (boot image with the radio bundle loader): signed radio bundles ([RadioBundle]), the Zigbee
     * stack choice, radio firmware updates ([RadioFirmware]). Otherwise as v3.
     */
    const val BUNDLE_VERSION = 4
    /** EZSP-over-TCP port of the coordinator modes (bellows/ZHA: socket://<panel>:6638). */
    const val ZIGBEE_TCP_PORT = 6638
    /** zigbee_router: the helper's localhost-only EZSP bridge for [ZigbeeRouterHost]. */
    const val ZIGBEE_ROUTER_PORT = 6639
    /** thread (border-router build): REST API for Home Assistant's OTBR integration. */
    const val OTBR_REST_PORT = 8081

    /** [dev.glassworks.frame.RadioStatus.PEER] parsed. */
    sealed interface Peer {
        /** The helper doesn't report one: another mode, or a helper from before #217. */
        data object NotReported : Peer
        data object Nobody : Peer
        data class Connected(val address: String, val sinceEpochSeconds: Long) : Peer
    }

    fun parsePeer(raw: String?): Peer {
        val parts = raw?.trim().orEmpty().split(' ').filter { it.isNotEmpty() }
        return when {
            parts == listOf("none") -> Peer.Nobody
            parts.size == 2 -> {
                val address = RadioAllow.normalizeEntry(parts[0])?.takeIf { '/' !in it }
                val since = parts[1].toLongOrNull()
                if (address != null && since != null) Peer.Connected(address, since) else Peer.NotReported
            }
            else -> Peer.NotReported
        }
    }

    data class Status(val state: String, val mode: RadioMode?, val detail: String)

    /** [PROP_ALLOWED] parsed. [source]: "app" (from [PROP_ALLOW]) or "conf" (radio.conf RADIO_ALLOW). */
    data class Allowed(val source: String, val any: Boolean, val addresses: List<String>)

    /** Parses [PROP_ALLOWED]; null if unset (off, or a helper without the firewall) or malformed. */
    fun parseAllowed(raw: String?): Allowed? {
        val parts = raw?.trim().orEmpty().split(' ').filter { it.isNotEmpty() }
        if (parts.size < 2 || parts[0] !in setOf("app", "conf")) return null
        val rest = parts.drop(1)
        return when {
            rest == listOf(RadioAllow.ANY) -> Allowed(parts[0], true, emptyList())
            rest == listOf("none") -> Allowed(parts[0], false, emptyList())
            else -> Allowed(parts[0], false, rest)
        }
    }

    /** Parses [PROP_STATUS]; null if unset or malformed. */
    fun parseStatus(raw: String?): Status? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        val head = s.substringBefore(':').trim()
        val detail = if (':' in s) s.substringAfter(':').trim() else ""
        val parts = head.split(' ').filter { it.isNotEmpty() }
        if (parts.size != 2 || parts[0] !in setOf("applying", "ok", "error")) return null
        return Status(parts[0], RadioMode.fromId(parts[1]), detail)
    }
}
