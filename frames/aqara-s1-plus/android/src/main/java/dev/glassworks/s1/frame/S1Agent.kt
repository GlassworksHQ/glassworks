package dev.glassworks.s1.frame

import dev.glassworks.frame.AgentCapability
import dev.glassworks.frame.AgentCapability.APP_UPDATES
import dev.glassworks.frame.AgentCapability.BLUETOOTH_ADDRESS
import dev.glassworks.frame.AgentCapability.FRAME_BUNDLE
import dev.glassworks.frame.AgentCapability.HOTSPOT_NAME
import dev.glassworks.frame.AgentCapability.HOTSPOT_PORTAL
import dev.glassworks.frame.AgentCapability.LOADER_INSTALL
import dev.glassworks.frame.AgentCapability.RADIO_FIREWALL
import dev.glassworks.frame.AgentCapability.RADIO_FIRMWARE
import dev.glassworks.frame.AgentCapability.RADIO_MODE
import dev.glassworks.frame.AgentCapability.TIME_ZONE
import dev.glassworks.frame.BundleAgent
import dev.glassworks.frame.FrameAgent
import dev.glassworks.frame.RadioStatus
import dev.glassworks.radio.PropsBundle
import dev.glassworks.radio.PropsRadio
import dev.glassworks.radio.RadioReports
import dev.glassworks.radio.ShellProps
import dev.glassworks.radio.SysProps
import java.io.File

/**
 * The S1's frame agent (docs/frame-agent.md#the-s1-the-reference): the root helper `s1kiosk_radio` and its sibling
 * init services `s1kiosk_update` and `s1kiosk_bundle`, in boot images up to 0.4.3 (contract 4). Every check is the one
 * the app made before the contract (#641), on the same properties and files. #549's names
 * (`glassworks_agent`, `sys.glassworks.*`) on the frame loader image: [names] picks the set ([S1Names]).
 *
 * The radio pane talks to the radio through [radio], the bundle install through [bundle], and the time zone pane
 * through [timeZone], each by the names of [names].
 */
class S1Agent(
    private val props: SysProps = ShellProps,
    /** The bundle install, by default the image's own ([S1Names.bundleService]). */
    bundle: BundleAgent? = null,
    /** The Bluetooth address file, by default the image's own ([S1Names.bluetoothFile]). */
    private val bluetoothFile: File? = null,
    appUpdates: RootUpdateInstaller? = null,
) : FrameAgent {
    /** The boot image's names, picked once: `ro.*` doesn't change while the app runs. */
    val names: S1Names by lazy { S1Names.of(props::peek) }

    override val declared: Set<AgentCapability> = DECLARED

    override val radio: PropsRadio by lazy { PropsRadio(props, names.radio) }

    override val timeZone: S1TimeZone by lazy { S1TimeZone(props, names) }

    /** The bundle install and its state, from the root-owned file. The radio pane installs only a bundle in [S1Names.bundleFormat]. */
    override val bundle: BundleAgent by lazy {
        bundle ?: PropsBundle(props, names.bundleService, names.bundleStatus, names.bundleStage, File(names.bundleState), format = names.bundleFormat,
            stateProp = names.radio.status.getValue(RadioStatus.BUNDLE))
    }

    override val appUpdates: RootUpdateInstaller by lazy { appUpdates ?: RootUpdateInstaller(props, names) }

    /** The helper's contract version, 1 for a helper from before it said one; null without the helper. */
    override fun version(): Int? = if (radio.state().isEmpty()) null else radio.version()

    override fun offers(capability: AgentCapability): Boolean = when (capability) {
        RADIO_MODE, RADIO_FIREWALL, RADIO_FIRMWARE -> radio.offers(capability) == true
        TIME_ZONE -> (version() ?: 0) >= RadioReports.BUNDLE_VERSION
        // This boot's marker as well as the file: an undo keeps /data, and with it the file (docs/frame-agent.md#discovery).
        FRAME_BUNDLE -> props.get(names.bundleMarker.first).trim() == names.bundleMarker.second &&
            bundle.state()?.trim()?.substringBefore(' ') in BUNDLE_STATES
        APP_UPDATES -> props.get(names.updateVersion).trim().toIntOrNull()?.let { it >= 1 } == true
        HOTSPOT_PORTAL -> props.get(names.portalStatus).isNotEmpty()
        HOTSPOT_NAME -> props.get(names.hotspotNameStatus).isNotEmpty()
        BLUETOOTH_ADDRESS -> bluetoothAddress() != null
        LOADER_INSTALL -> false
    }

    /** The helper's file (`bt_publish`, root 0644), as it says it; the Bluetooth proxy checks the format. */
    override fun bluetoothAddress(): String? = try { (bluetoothFile ?: File(names.bluetoothFile)).readText() } catch (_: Exception) { null }

    companion object {
        /** frame.json's `agent.capabilities` (the agent contract test checks it). */
        val DECLARED: Set<AgentCapability> =
            setOf(RADIO_MODE, RADIO_FIREWALL, RADIO_FIRMWARE, FRAME_BUNDLE, APP_UPDATES, TIME_ZONE, HOTSPOT_PORTAL, HOTSPOT_NAME, BLUETOOTH_ADDRESS)

        /** The first word of a bundle state file, in both images' formats. */
        private val BUNDLE_STATES = setOf("ok", "bad", "none")
    }
}
