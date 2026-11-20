package dev.glassworks.s1.frame

import android.app.Application
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
import dev.glassworks.radio.PropsBundle
import dev.glassworks.s1.radio.RadioHelper
import dev.glassworks.radio.RadioReports
import dev.glassworks.radio.SysProps
import dev.glassworks.testing.FrameAgentContract
import dev.glassworks.update.UpdateHelper
import java.io.File
import java.nio.file.Files
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The S1's driver for the frame agent contract: its agent over a fake helper (properties, the bundle state and the Bluetooth file). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class S1AgentContractTest : FrameAgentContract("aqara-s1-plus") {
    /** What the newest S1 root side shows (boot image 0.4.3 with its radio bundle). The address is a documentation one (RFC 7042). */
    private fun side(without: AgentCapability? = null, none: Boolean = false): FrameAgent {
        val p = HashMap<String, String>()
        if (!none) {
            p[RadioHelper.PROP_SVC_STATE] = "running"
            p[RadioHelper.PROP_VERSION] = "${RadioReports.VERSION}"
            p[UpdateHelper.PROP_VERSION] = "${UpdateHelper.VERSION}"
            p[RadioHelper.PROP_BUNDLE_LOADER] = "1"
            p[S1Names.OLD.portalStatus] = "off"
            p[S1Names.OLD.hotspotNameStatus] = "glassworks"
        }
        when (without) {
            RADIO_MODE -> p.remove(RadioHelper.PROP_SVC_STATE)
            RADIO_FIREWALL -> p[RadioHelper.PROP_VERSION] = "${RadioReports.FIREWALL_VERSION - 1}"
            RADIO_FIRMWARE, TIME_ZONE -> p[RadioHelper.PROP_VERSION] = "${RadioReports.BUNDLE_VERSION - 1}"
            APP_UPDATES -> p.remove(UpdateHelper.PROP_VERSION)
            HOTSPOT_PORTAL -> p.remove(S1Names.OLD.portalStatus)
            HOTSPOT_NAME -> p.remove(S1Names.OLD.hotspotNameStatus)
            // An undo: the boot image's marker is gone, the state file stays in /data.
            FRAME_BUNDLE -> p.remove(RadioHelper.PROP_BUNDLE_LOADER)
            BLUETOOTH_ADDRESS, LOADER_INSTALL, null -> Unit
        }
        val props = object : SysProps {
            override fun get(name: String) = p[name] ?: ""
            override fun set(name: String, value: String) { p[name] = value }
        }
        val state = File(Files.createTempDirectory("s1-bundle").toFile(), "radio-bundle").apply { if (!none) writeText("ok 5 5\n") }
        val bundle = PropsBundle(props, RadioHelper.BUNDLE_SERVICE, RadioHelper.PROP_BUNDLE_STATUS, RadioHelper.BUNDLE_STAGE_DIR, state, ownerUid = { if (it.exists()) 0 else null })
        val bt = File(Files.createTempDirectory("s1-agent").toFile(), "bluetooth-address")
        if (!none && without != BLUETOOTH_ADDRESS) bt.writeText("00:00:5E:00:53:01\n")
        return S1Agent(props, bundle = bundle, bluetoothFile = bt)
    }

    override fun none() = side(none = true)
    override fun newest() = side()
    override fun without(capability: AgentCapability) = side(without = capability)
}
