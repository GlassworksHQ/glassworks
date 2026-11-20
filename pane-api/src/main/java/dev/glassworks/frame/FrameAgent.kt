/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.frame

/**
 * What a frame agent can do (docs/frame-agent.md#capabilities): the words of frame.json's `agent.capabilities` ([id]).
 * packages/frame-card's `AGENT_CAPABILITIES` has the same words, and `FrameAgentGateTest` checks that it does.
 */
enum class AgentCapability(val id: String) {
    RADIO_MODE("radio-mode"),
    RADIO_FIREWALL("radio-firewall"),
    RADIO_FIRMWARE("radio-firmware"),
    FRAME_BUNDLE("frame-bundle"),
    APP_UPDATES("app-updates"),
    TIME_ZONE("time-zone"),
    HOTSPOT_PORTAL("hotspot-portal"),
    HOTSPOT_NAME("hotspot-name"),
    BLUETOOTH_ADDRESS("bluetooth-address"),

    /** The frame loader's install into the boot partition (#139). Planned: no frame declares it yet. */
    LOADER_INSTALL("loader-install");

    companion object {
        fun of(id: String): AgentCapability? = entries.firstOrNull { it.id == id }
    }
}

/**
 * A frame's agent ([Frame.agent], docs/frame-agent.md): the service outside the app that does what needs root. How
 * the app reaches it is the frame's business; the core asks only this. A frame without one has [NoAgent].
 */
interface FrameAgent {
    /** frame.json's `agent.capabilities`: what this frame's agent can do. Empty: the frame has none. */
    val declared: Set<AgentCapability>

    /** The version the agent on this device reports, or null when none runs (a boot image without it). It may read the device. */
    fun version(): Int?

    /** Whether the agent on this device does [capability] now. Never true for one not in [declared]. It may read the device. */
    fun offers(capability: AgentCapability): Boolean

    /**
     * The way to [AgentCapability.APP_UPDATES]: app updates without Android's confirmation. Null when the frame doesn't
     * declare it, and the app installs with PackageInstaller. Use it only while [offers] says so.
     */
    val appUpdates: RootUpdates? get() = null

    /**
     * The Bluetooth adapter's address as the agent publishes it ([AgentCapability.BLUETOOTH_ADDRESS]), for the
     * Bluetooth proxy, or null when it has none. It reads a file: not on the main thread.
     */
    fun bluetoothAddress(): String? = null
}

/** A frame without an agent (generic Android, the Mirror): nothing declared, nothing runs, nothing offered. */
object NoAgent : FrameAgent {
    override val declared: Set<AgentCapability> = emptySet()
    override fun version(): Int? = null
    override fun offers(capability: AgentCapability) = false
}
