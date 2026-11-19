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
import dev.glassworks.radio.RadioBundle
import dev.glassworks.radio.PropsBundle
import dev.glassworks.radio.PropsRadio
import dev.glassworks.s1.radio.RadioHelper
import dev.glassworks.radio.RadioReports
import dev.glassworks.radio.ShellProps
import dev.glassworks.radio.SysProps
import dev.glassworks.update.UpdateHelper
import java.io.File

/**
 * The S1's frame agent (docs/frame-agent.md#the-s1-the-reference): the root helper `s1kiosk_radio` and its sibling
 * init services `s1kiosk_update` and `s1kiosk_bundle`, in boot images up to 0.4.3 (contract 4). Every check is the one
 * the app made before the contract (#641), on the same properties and files. #549's names
 * (`glassworks_agent`, `sys.glassworks.*`) come here and in [RadioHelper] and [S1TimeZone].
 *
 * The radio pane talks to the radio through [radio] ([RadioHelper]'s names), the bundle install through [bundle], and
 * the time zone pane through [timeZone].
 */
class S1Agent(
    private val props: SysProps = ShellProps,
    /** The radio bundle's install and its state, from the root-owned file. The radio pane still installs on the file alone. */
    override val bundle: BundleAgent = PropsBundle(
        props, RadioHelper.BUNDLE_SERVICE, RadioHelper.PROP_BUNDLE_STATUS, RadioHelper.BUNDLE_STAGE_DIR, File(RadioHelper.BUNDLE_STATE_FILE),
    ),
    private val bluetoothFile: File = File(BLUETOOTH_ADDRESS_FILE),
    override val appUpdates: RootUpdateInstaller = RootUpdateInstaller(props),
) : FrameAgent {
    override val declared: Set<AgentCapability> = DECLARED

    override val radio = PropsRadio(props, RadioHelper.NAMES)

    override val timeZone = S1TimeZone(props)

    /** The helper's contract version, 1 for a helper from before it said one; null without the helper. */
    override fun version(): Int? = if (radio.state().isEmpty()) null else radio.version()

    override fun offers(capability: AgentCapability): Boolean = when (capability) {
        RADIO_MODE, RADIO_FIREWALL, RADIO_FIRMWARE -> radio.offers(capability) == true
        TIME_ZONE -> (version() ?: 0) >= RadioReports.BUNDLE_VERSION
        // This boot's marker as well as the file: an undo keeps /data, and with it the file (docs/frame-agent.md#discovery).
        FRAME_BUNDLE -> props.get(RadioHelper.PROP_BUNDLE_LOADER).trim() == "1" && bundle.state()?.let(RadioBundle::parsePub) != null
        APP_UPDATES -> props.get(UpdateHelper.PROP_VERSION).trim().toIntOrNull()?.let { it >= 1 } == true
        HOTSPOT_PORTAL -> props.get(PORTAL_STATUS).isNotEmpty()
        HOTSPOT_NAME -> props.get(HOTSPOT_NAME_STATUS).isNotEmpty()
        BLUETOOTH_ADDRESS -> bluetoothAddress() != null
        LOADER_INSTALL -> false
    }

    /** The helper's file (`bt_publish`, root 0644), as it says it; the Bluetooth proxy checks the format. */
    override fun bluetoothAddress(): String? = try { bluetoothFile.readText() } catch (_: Exception) { null }

    companion object {
        /** frame.json's `agent.capabilities` (the agent contract test checks it). */
        val DECLARED: Set<AgentCapability> =
            setOf(RADIO_MODE, RADIO_FIREWALL, RADIO_FIRMWARE, FRAME_BUNDLE, APP_UPDATES, TIME_ZONE, HOTSPOT_PORTAL, HOTSPOT_NAME, BLUETOOTH_ADDRESS)

        /** The helpers' app-readable directory (`PUB_DIR` in `s1kiosk_radio.sh` and `s1kiosk_bundle.sh`). */
        const val PUB_DIR = "/data/s1kiosk-pub"

        /** The Bluetooth adapter's MAC, as the helper publishes it (`bt_publish`): Android 10 hides it from apps (#246). */
        const val BLUETOOTH_ADDRESS_FILE = "$PUB_DIR/bluetooth-address"

        /** The setup hotspot's portal (#197): "off" | "on <iface>" | "error: …". */
        const val PORTAL_STATUS = "sys.s1kiosk.portal.status"

        /** The setup hotspot's name (#197, #430). */
        const val HOTSPOT_NAME_STATUS = "sys.s1kiosk.hotspot_name.status"
    }
}
