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
import dev.glassworks.frame.FrameAgent
import dev.glassworks.radio.RadioBundle
import dev.glassworks.radio.RadioHelper
import dev.glassworks.radio.ShellProps
import dev.glassworks.radio.SysProps
import dev.glassworks.update.UpdateHelper
import java.io.File
import java.io.FileNotFoundException

/**
 * The S1's frame agent (docs/frame-agent.md#the-s1-the-reference): the root helper `s1kiosk_radio` and its sibling
 * init services `s1kiosk_update` and `s1kiosk_bundle`, in boot images up to 0.4.3 (contract 4). Every check is the one
 * the app made before the contract (#641), on the same properties and files. #549's names
 * (`glassworks_agent`, `sys.glassworks.*`) come here.
 *
 * The radio and time zone panes still talk to the helper themselves ([RadioHelper], [TimeZoneKeeper]); this reads
 * the same properties to say what it offers.
 */
class S1Agent(
    private val props: SysProps = ShellProps,
    /** The radio bundle's state, from the root-owned file ([RadioBundle.installed]). */
    private val bundle: () -> RadioBundle.Installed? = RadioBundle(props, NoBundle, null)::installed,
    private val bluetoothFile: File = File(BLUETOOTH_ADDRESS_FILE),
    override val appUpdates: RootUpdateInstaller = RootUpdateInstaller(props),
) : FrameAgent {
    override val declared: Set<AgentCapability> = DECLARED

    /** The helper's contract version, 1 for a helper from before it said one; null without the helper. */
    override fun version(): Int? =
        if (props.get(RadioHelper.PROP_SVC_STATE).isEmpty()) null else props.get(RadioHelper.PROP_VERSION).trim().toIntOrNull() ?: 1

    override fun offers(capability: AgentCapability): Boolean = when (capability) {
        RADIO_MODE -> version() != null
        RADIO_FIREWALL -> (version() ?: 0) >= RadioHelper.FIREWALL_VERSION
        RADIO_FIRMWARE, TIME_ZONE -> (version() ?: 0) >= RadioHelper.BUNDLE_VERSION
        FRAME_BUNDLE -> bundle() != null
        APP_UPDATES -> props.get(UpdateHelper.PROP_VERSION).trim().toIntOrNull()?.let { it >= 1 } == true
        HOTSPOT_PORTAL -> props.get(PORTAL_STATUS).isNotEmpty()
        HOTSPOT_NAME -> props.get(HOTSPOT_NAME_STATUS).isNotEmpty()
        BLUETOOTH_ADDRESS -> bluetoothAddress() != null
        LOADER_INSTALL -> false
    }

    /** The helper's file (`bt_publish`, root 0644), as it says it; the Bluetooth proxy checks the format. */
    override fun bluetoothAddress(): String? = try { bluetoothFile.readText() } catch (_: Exception) { null }

    private object NoBundle : RadioBundle.Source {
        override fun manifest(): String? = null
        override fun open(path: String) = throw FileNotFoundException(path)
    }

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
