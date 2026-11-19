package dev.glassworks.radio

import dev.glassworks.s1.radio.RadioHelper
import dev.glassworks.frame.AgentCapability
import dev.glassworks.frame.FrameAgent
import dev.glassworks.frame.RadioAgent
import java.io.File

/**
 * The radio pane's tests run over the S1's names (s1/radio/RadioHelper.kt in these tests, a byte-for-byte copy of the S1
 * app's, which `S1RadioNamesTest` checks), through the same [PropsRadio] the S1's agent uses.
 */
fun s1Agent(props: SysProps): FrameAgent = object : FrameAgent {
    private val r = PropsRadio(props, RadioHelper.NAMES)
    override val declared = setOf(AgentCapability.RADIO_MODE, AgentCapability.RADIO_FIREWALL, AgentCapability.RADIO_FIRMWARE)
    override fun version(): Int? = if (r.state().isEmpty()) null else r.version()
    override fun offers(capability: AgentCapability) = r.offers(capability) == true
    override val radio: RadioAgent = r
}

/** The S1's bundle install over [props], its state in [stateFile] owned as [ownerUid] says. */
fun s1Bundle(props: SysProps, stateFile: File, ownerUid: (File) -> Int?) =
    PropsBundle(props, RadioHelper.BUNDLE_SERVICE, RadioHelper.PROP_BUNDLE_STATUS, RadioHelper.BUNDLE_STAGE_DIR, stateFile, ownerUid)
