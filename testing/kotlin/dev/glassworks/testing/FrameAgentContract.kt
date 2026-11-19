/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.testing

import dev.glassworks.frame.AgentCapability
import dev.glassworks.frame.FrameAgent
import dev.glassworks.frame.RadioFile
import dev.glassworks.frame.RadioSetting
import dev.glassworks.frame.RadioStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The frame agent contract (docs/frame-agent.md#the-contract-test, #641): what every frame that declares an agent in
 * its frame.json passes. A frame's driver extends this with its id and gives the suite its agent over a fake root
 * side. `FrameAgentGateTest` (pane-api) fails when a frame declares an agent and has no driver.
 */
abstract class FrameAgentContract(private val frameId: String) {
    /** The frame's agent with no root side at all: a device without the agent (an S1 on a stock boot image). */
    protected abstract fun none(): FrameAgent

    /** The frame's agent on the newest root side, which offers everything the frame declares. */
    protected abstract fun newest(): FrameAgent

    /** The newest root side with [capability]'s own discovery gone (on the S1, for `time-zone`: a helper below version 4). */
    protected abstract fun without(capability: AgentCapability): FrameAgent

    private val frameJson: Set<AgentCapability> by lazy {
        val agent = JSONObject(RepoFiles.file("frames/$frameId/design/frame.json").readText()).optJSONObject("agent")
            ?: throw AssertionError("frames/$frameId/design/frame.json has no agent, but $frameId has an agent contract driver")
        val words = agent.getJSONArray("capabilities").let { a -> (0 until a.length()).map(a::getString) }
        words.map { AgentCapability.of(it) ?: throw AssertionError("$frameId's frame.json: \"$it\" isn't an AgentCapability") }.toSet()
            .also { assertEquals("$frameId's frame.json lists a capability twice", words.size, it.size) }
    }

    private fun sides(): List<Pair<String, FrameAgent>> =
        listOf("no root side" to none(), "the newest" to newest()) + frameJson.map { "without $it" to without(it) }

    @Test fun declaresWhatItsFrameJsonSays() {
        for ((side, agent) in sides()) assertEquals("$frameId ($side): declared", frameJson, agent.declared)
    }

    @Test fun withNoRootSideItReportsNoVersionAndOffersNothing() {
        val agent = none()
        assertNull("$frameId: version with no root side", agent.version())
        for (c in AgentCapability.entries) assertFalse("$frameId: offers $c with no root side", agent.offers(c))
        assertNull("$frameId: a Bluetooth address with no root side", agent.bluetoothAddress())
    }

    @Test fun theNewestReportsAVersionAndOffersEveryDeclaredCapability() {
        val agent = newest()
        assertNotNull("$frameId: the newest root side reports a version", agent.version())
        for (c in frameJson) assertTrue("$frameId: the newest root side offers $c", agent.offers(c))
    }

    @Test fun neverOffersWhatItDoesntDeclare() {
        for ((side, agent) in sides()) for (c in AgentCapability.entries - frameJson) {
            assertFalse("$frameId ($side): offers $c, which its frame.json doesn't declare", agent.offers(c))
        }
    }

    @Test fun eachCapabilityGoesWithItsOwnDiscovery() {
        for (c in frameJson) assertFalse("$frameId: still offers $c without its discovery", without(c).offers(c))
    }

    private val radioCaps = setOf(AgentCapability.RADIO_MODE, AgentCapability.RADIO_FIREWALL, AgentCapability.RADIO_FIRMWARE)

    @Test fun itsRadioTimeZoneAndBundleFollowWhatItDeclares() {
        for ((side, agent) in sides()) {
            assertEquals("$frameId ($side): radio only with a radio capability", (radioCaps intersect frameJson).isNotEmpty(), agent.radio != null)
            assertEquals("$frameId ($side): timeZone only with time-zone", AgentCapability.TIME_ZONE in frameJson, agent.timeZone != null)
            assertEquals("$frameId ($side): bundle only with frame-bundle", AgentCapability.FRAME_BUNDLE in frameJson, agent.bundle != null)
        }
    }

    @Test fun withNoRootSideNothingIsReported() {
        val agent = none()
        agent.radio?.let { r ->
            assertEquals("$frameId: the radio agent's state with no root side", "", r.state())
            for (s in RadioStatus.entries) assertEquals("$frameId: $s with no root side", "", r.status(s))
        }
        agent.timeZone?.let { t ->
            assertEquals("$frameId: the time zone status with no root side", "", t.status())
            assertNull("$frameId: the time zone's version with no root side", t.version())
        }
        assertNull("$frameId: a bundle state with no root side", agent.bundle?.state())
    }

    @Test fun theNewestReportsItsRadioTimeZoneAndBundle() {
        val agent = newest()
        agent.radio?.let { assertTrue("$frameId: the radio agent runs on the newest root side", it.state().isNotEmpty()) }
        agent.timeZone?.let { assertNotNull("$frameId: the time zone's version on the newest root side", it.version()) }
        agent.bundle?.let { assertNotNull("$frameId: a bundle state on the newest root side", it.state()) }
    }

    /** Requests are desired state: what the app asks for reads back as asked, and asking again changes nothing. */
    @Test fun requestsReadBackAsDesiredState() {
        val agent = newest()
        agent.radio?.let { r ->
            for ((s, v) in listOf(RadioSetting.MODE to "off", RadioSetting.ZIGBEE_STACK to "aqara", RadioSetting.ALLOW to "192.0.2.10")) {
                r.set(s, v); r.set(s, v)
                assertEquals("$frameId: $s reads back", v, r.setting(s))
            }
            for (f in RadioFile.entries) assertTrue("$frameId: $f is a path in the app's files dir", r.file(f).isNotBlank() && !r.file(f).startsWith("/"))
        }
        agent.timeZone?.let { t ->
            t.want("Etc/UTC"); t.want("Etc/UTC")
            assertEquals("$frameId: the wanted zone reads back", "Etc/UTC", t.wanted())
        }
        agent.bundle?.let { assertTrue("$frameId: the bundle stages in the app's files dir", it.stageDir.isNotBlank() && !it.stageDir.startsWith("/")) }
    }

    @Test fun itsTransportsFollowWhatItDeclares() {
        for ((side, agent) in sides()) {
            assertEquals("$frameId ($side): appUpdates only with app-updates", AgentCapability.APP_UPDATES in frameJson, agent.appUpdates != null)
            assertEquals(
                "$frameId ($side): a Bluetooth address exactly when it offers bluetooth-address",
                agent.offers(AgentCapability.BLUETOOTH_ADDRESS), agent.bluetoothAddress() != null,
            )
        }
    }
}
