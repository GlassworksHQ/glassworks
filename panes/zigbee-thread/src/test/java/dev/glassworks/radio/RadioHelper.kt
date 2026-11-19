package dev.glassworks.radio

import dev.glassworks.frame.RadioFile
import dev.glassworks.frame.RadioSetting
import dev.glassworks.frame.RadioStatus

// The S1's radio names: its frame agent's side of the radio (docs/frame-agent.md#the-s1-the-reference). They sit in
// dev.glassworks.radio, where they were before #641, so the S1 code and tests that name them stay as they are.

/**
 * Contract with the root helper that the boot-image patch installs (frames/aqara-s1-plus/docs/radio.md).
 * The app writes [PROP_MODE] and `ctl.restart`s [SERVICE]; the helper (frames/aqara-s1-plus/tools/radio/s1kiosk_radio.sh)
 * applies the mode, supervises its processes and reports in [PROP_STATUS]. The formats are [RadioReports].
 */
object RadioHelper {
    /** Init service name; `init.svc.<SERVICE>` is non-empty iff the .rc snippet is installed. */
    const val SERVICE = "s1kiosk_radio"
    const val PROP_SVC_STATE = "init.svc.$SERVICE"
    /** Wanted mode, a [RadioMode.id]; persistent so the helper applies it at boot before the app runs. */
    const val PROP_MODE = "persist.s1kiosk.radio_mode"
    /** Helper -> app: "<state> <mode-id>[: detail]", state = applying | ok | error. */
    const val PROP_STATUS = "sys.s1kiosk.radio.status"
    /**
     * Helper -> app: contract version the installed helper implements. v2 (frames/aqara-s1-plus/tools/radio/s1kiosk_radio.sh
     * 2026-11): zigbee_router = EZSP bridge on 127.0.0.1:[ZIGBEE_ROUTER_PORT] for the app's host,
     * thread_router = dataset hand-off from the app ([DATASET_FILE]), "applying <mode>: waiting for …"
     * until the stack answers, [PROP_OTBR].
     */
    const val PROP_VERSION = "sys.s1kiosk.radio.version"
    /** Helper -> app (v4): which helper runs: "boot[ (why)]" | "bundle <version>" | "dev". */
    const val PROP_SOURCE = "sys.s1kiosk.radio.source"
    /** Helper -> app (v4): the installed radio bundle: "none" | "ok <version>" | "bad: <why>". */
    const val PROP_BUNDLE = "sys.s1kiosk.radio.bundle"
    /** App -> helper (v4): Zigbee stack for the coordinator mode, a [ZigbeeStack] id (persistent). */
    const val PROP_ZIGBEE_STACK = "persist.s1kiosk.radio_zigbee"
    /**
     * Helper -> app (v4): "aqara" | "sisdk ready <ver>" | "sisdk missing <ver> <sha256>" (the app should
     * download it) | "sisdk no-runtime" (the radio bundle lacks it) | "sisdk bad <why>".
     */
    const val PROP_ZIGBEE = "sys.s1kiosk.radio.zigbee"
    /** Helper -> app (v4): "<idle|flashing|ok|error> <id>[: detail]" ([RadioFirmware]). */
    const val PROP_FW = "sys.s1kiosk.radio.fw"
    /** Helper -> app (v4): flashable images, "<id>:<sha256 prefix>:<APP version> ..." (stock first). */
    const val PROP_FW_TARGETS = "sys.s1kiosk.radio.fw_targets"
    /** App -> helper (v4): "<id> <sha256 prefix>\n" in the app's files dir; taken within 3 s, ignored after 2 min. */
    const val FW_REQUEST_FILE = "radio/fw-request"
    /** App -> helper (v4): Silicon Labs' zigbeed, downloaded by the app; taken only with the pinned SHA-256. */
    const val ZIGBEED_FILE = "radio/zigbeed-${SisdkZigbeed.VERSION}"
    /** Radio bundle install service (boot image with the loader): oneshot, started by the app. */
    const val BUNDLE_SERVICE = "s1kiosk_bundle"
    /** Set to 1 by init at boot when [BUNDLE_SERVICE] exists. Informational only: any app can set sys.*
     *  props, so the app decides from [BUNDLE_STATE_FILE] instead. */
    const val PROP_BUNDLE_LOADER = "sys.s1kiosk.bundle.loader"
    /** Service -> app: "requested" (app) | "running: …" | "ok: …" | "error: …". */
    const val PROP_BUNDLE_STATUS = "sys.s1kiosk.bundle.status"
    /** App -> helper (v4): "Leave Thread network" request in the app's files dir (any mode). */
    const val THREAD_LEAVE_FILE = "radio/thread-leave"
    /** Helper -> app (v4): "<ok|error> <epoch seconds>: <detail>" for the last Thread leave. */
    const val PROP_THREAD_LEAVE = "sys.s1kiosk.radio.thread_leave"
    /** Where the app stages the bundle for [BUNDLE_SERVICE] (files/radio/bundle-in). */
    const val BUNDLE_STAGE_DIR = "radio/bundle-in"
    /**
     * Helper -> app (0.4.3 bundle): "yes" when a Thread router network is stored (a dataset or the
     * router's settings, what `Leave Thread network` deletes), "no" when not; unset with older helpers.
     */
    const val PROP_THREAD_STORED = "sys.s1kiosk.radio.thread_stored"
    /** Helper -> app (v2): which otbr-agent is installed: `br` (border router, REST), `agent` (proof build), `none`. */
    const val PROP_OTBR = "sys.s1kiosk.radio.otbr"
    /**
     * thread_router: the app writes the active dataset (hex TLVs) here, in its own files dir
     * (`files/radio/thread-dataset`); the helper moves it to its root-only dir and deletes this copy.
     */
    const val DATASET_FILE = "radio/thread-dataset"
    /**
     * App -> helper (v3): who may reach tcp:[ZIGBEE_TCP_PORT] and tcp:[OTBR_REST_PORT] (iptables, every
     * mode but off). "" = nobody but loopback (no Home Assistant address known yet), "any" = no firewall,
     * else space-separated IPv4/IPv6 addresses or CIDRs ([RadioAllow.decide]). Persistent so the helper
     * has it at boot before the app runs; re-read every 3 s (no helper restart). `RADIO_ALLOW` in the
     * helper's radio.conf wins over it.
     */
    const val PROP_ALLOW = "persist.s1kiosk.radio_allow"
    /** Helper -> app (v3): what the firewall enforces now: "<app|conf> <any|none|addr ...>"; "" in off. */
    const val PROP_ALLOWED = "sys.s1kiosk.radio.allowed"
    /**
     * Helper -> app (#217): who is connected to the EZSP port now, in the coordinator modes: "none", or
     * "<address> <epoch seconds first seen>" (one client at most). "" in every other mode and with older
     * helpers. Re-read every 3 s by the helper.
     */
    const val PROP_PEER = "sys.s1kiosk.radio.peer"

    /** Where the radio bundle service publishes the installed bundle: "<ok|bad|none> <version> <floor>", root 0644. */
    const val BUNDLE_STATE_FILE = "/data/s1kiosk-pub/radio-bundle"

    /** The radio pane's names for these ([PropsRadio]). */
    val NAMES = RadioNames(
        service = SERVICE,
        version = PROP_VERSION,
        status = mapOf(
            RadioStatus.MODE to PROP_STATUS,
            RadioStatus.ALLOWED to PROP_ALLOWED,
            RadioStatus.PEER to PROP_PEER,
            RadioStatus.THREAD_STORED to PROP_THREAD_STORED,
            RadioStatus.THREAD_LEAVE to PROP_THREAD_LEAVE,
            RadioStatus.ZIGBEE to PROP_ZIGBEE,
            RadioStatus.FIRMWARE to PROP_FW,
            RadioStatus.FIRMWARE_TARGETS to PROP_FW_TARGETS,
            RadioStatus.SOURCE to PROP_SOURCE,
            RadioStatus.BUNDLE to PROP_BUNDLE,
        ),
        settings = mapOf(RadioSetting.MODE to PROP_MODE, RadioSetting.ZIGBEE_STACK to PROP_ZIGBEE_STACK, RadioSetting.ALLOW to PROP_ALLOW),
        files = mapOf(
            RadioFile.THREAD_DATASET to DATASET_FILE,
            RadioFile.THREAD_LEAVE to THREAD_LEAVE_FILE,
            RadioFile.FIRMWARE_REQUEST to FW_REQUEST_FILE,
            RadioFile.ZIGBEED to ZIGBEED_FILE,
        ),
        vendorStopOrder = AqaraRadio.STOP_ORDER,
        vendorWanted = { id -> AqaraRadio.servicesFor(RadioMode.fromId(id) ?: RadioMode.OFF) },
    )
}

/** Aqara's radio init services (/vendor/etc/init/gateway.rc), root, u:r:su:s0. */
object AqaraRadio {
    const val CPCD = "zigbee_cpcd"     // cpcd -c /etc/cpcd.conf (CPC daemon, owns /dev/spidev2.0)
    const val SOCAT = "zigbee_socat"   // socat pty,link=/dev/ttyZigbeeNCP pty,link=/tmp/ttyZigbeeNCP
    const val ZIGBEED = "zigbee_zigbeed" // zigbeed: spinel iid=1 <-> EZSP on /tmp/ttyZigbeeNCP
    const val HOST = "zigbee_host"     // Z3Gateway -p ttyZigbeeNCP (EZSP host on /dev/ttyZigbeeNCP)
    const val AGENT = "zigbee_agent"   // zigbee_agent (Aqara's MQTT glue around Z3Gateway)
    const val MATTER = "ha_matter"     // Aqara's Matter bridge (Wi-Fi, :5540) for its Zigbee devices

    /** Services [servicesFor] manages; `frames/aqara-s1-plus/tools/install.sh --undo` starts them again. */
    val ALL = listOf(CPCD, SOCAT, ZIGBEED, HOST, AGENT, MATTER)

    /**
     * Consumers first. The wrappers of socat/zigbeed/Z3Gateway run `setprop sys.gateway.config 11`
     * when their binary exits *by itself*, which restarts the whole stack; stopping a provider before
     * its consumer would make the consumer exit and trigger that.
     */
    val STOP_ORDER = listOf(MATTER, AGENT, HOST, ZIGBEED, SOCAT, CPCD)

    /** Providers first (the wrappers also sleep 3-8 s so their providers are up). */
    val START_ORDER = STOP_ORDER.reversed()

    /**
     * Wanted state (true = running) of each of Aqara's radio services in this mode. Aqara's own
     * consumers (Z3Gateway, zigbee_agent, the ha_matter bridge) never run; our modes reuse only
     * cpcd (the CPC daemon) and, for Zigbee, the socat pty pair + zigbeed.
     */
    fun servicesFor(mode: RadioMode): Map<String, Boolean> {
        val cpcd = mode != RadioMode.OFF
        val zigbeed = mode == RadioMode.ZIGBEE_TCP || mode == RadioMode.ZIGBEE_ROUTER
        return linkedMapOf(
            CPCD to cpcd,
            SOCAT to zigbeed, // the pty pair zigbeed's EZSP interface lives on
            ZIGBEED to zigbeed,
            HOST to false,   // Z3Gateway, Aqara's coordinator app
            AGENT to false,
            MATTER to false,
        )
    }
}
