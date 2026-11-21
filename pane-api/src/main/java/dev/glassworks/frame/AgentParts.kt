/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.frame

/** What the agent reports about the radio (docs/frame-agent.md#capabilities), each a status line in the spec's format. */
enum class RadioStatus {
    /** `<applying|ok|error> <mode>[: detail]`. */
    MODE,
    /** The firewall in force: `<app|conf> <any|none|addr ...>`. */
    ALLOWED,
    /** Who uses the coordinator's port: `none` or `<address> <epoch seconds>`. */
    PEER,
    /** `yes` when a Thread router network is stored, `no` when not. */
    THREAD_STORED,
    /** The last *Leave Thread network*: `<ok|error> <epoch seconds>: <detail>`. */
    THREAD_LEAVE,
    /** The Zigbee stack: `aqara`, `sisdk ready <ver>`, `sisdk missing <ver> <sha256>`, … */
    ZIGBEE,
    /** `<idle|flashing|ok|error> <id>[: detail]`. */
    FIRMWARE,
    /** The images the agent can flash: `<id>:<sha256 prefix>:<version> ...`. */
    FIRMWARE_TARGETS,
    /** Which agent runs: `boot[ (why)]`, `bundle <version>`, `dev`. */
    SOURCE,
    /** The installed bundle as the agent says it, for display. */
    BUNDLE,
}

/** What the app asks of the radio, as desired state the agent keeps across its restarts and the device's. */
enum class RadioSetting {
    /** A mode id (`off`, `zigbee_tcp`, …). */
    MODE,
    /** The coordinator's Zigbee stack id. */
    ZIGBEE_STACK,
    /** Who may reach the radio's ports: `""`, `any`, or addresses and CIDRs. */
    ALLOW,
}

/** A hand-off or one-shot request the app writes in its own files dir, and the agent takes. */
enum class RadioFile { THREAD_DATASET, THREAD_LEAVE, FIRMWARE_REQUEST, ZIGBEED }

/**
 * The radio part of a frame's agent ([FrameAgent.radio]): `radio-mode`, `radio-firewall` and `radio-firmware`. The
 * radio pane talks to the radio only through this; the names behind it are the frame's.
 */
interface RadioAgent {
    /** The agent's own name, for status texts ("s1kiosk_radio"). */
    val name: String

    /** The agent's state as init says it (`running`, `stopped`, …); "" when the device has none. */
    fun state(): String

    /** The version the radio side reports, 1 when it says none. Read even when it doesn't run, for the status text. */
    fun version(): Int

    fun status(s: RadioStatus): String

    /** [status] without starting a process, for reads on the main thread. */
    fun peek(s: RadioStatus): String = status(s)

    /** The desired [s] as the agent will read it. */
    fun setting(s: RadioSetting): String

    fun set(s: RadioSetting, value: String)

    /** Restarts the agent, so it applies the wanted mode now. */
    fun restart()

    /** Where the app puts [f], relative to its files dir. */
    fun file(f: RadioFile): String

    /**
     * Without the agent, the app can still bring the vendor's own radio services to what mode [modeId] wants (on the S1:
     * stop Aqara's for Off). Returns the services that didn't get there.
     */
    fun applyVendor(modeId: String, log: (String) -> Unit): List<String>

    /** A vendor radio service's state as init says it, for the error text. */
    fun vendorState(service: String): String
}

/** The `frame-bundle` part of a frame's agent ([FrameAgent.bundle]): its signed bundle's state and its install. */
interface BundleAgent {
    /** The installed bundle's state line (`<ok|bad|none> <version> <floor>`), from a file only root writes; null when there is none. */
    fun state(): String?

    /** Where the app stages a bundle, relative to its files dir. */
    val stageDir: String

    /** The first line of a bundle manifest this agent installs (`S1RADIO-BUNDLE 1`); the app stages no other bundle. */
    val format: String

    /** Asks for the staged bundle to be installed. */
    fun request()

    /** Whether the install is running now. */
    fun running(): Boolean

    /** The last install's status: `requested`, `running: …`, `ok: …`, `error: …`. */
    fun status(): String

    /**
     * Restarts the device, so the frame's loader loads a staged bundle at boot (a frame update from Home Assistant,
     * #139). The way the frame's root side restarts it; returns only if the restart doesn't happen.
     */
    fun restart()
}

/** The `time-zone` part of a frame's agent ([FrameAgent.timeZone]). */
interface TimeZoneAgent {
    /** The zone the agent keeps, as set (an Olson id, `(off)` or ""). */
    fun wanted(): String

    fun want(zone: String)

    /** `<ok|error|none> [<id>][: detail]`. */
    fun status(): String

    /** The zone the device runs now, as Android says it. */
    fun current(): String

    /** The agent's version, or null when it reports none. */
    fun version(): Int?
}
