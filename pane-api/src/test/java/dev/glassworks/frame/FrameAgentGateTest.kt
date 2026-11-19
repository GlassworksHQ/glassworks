/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.frame

import dev.glassworks.testing.RepoFiles
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate (#641, docs/frame-agent.md#the-contract-test): a frame that declares an agent in its frame.json has a
 * `FrameAgentContract` driver, and a driver has a frame that declares one. Kiln's gate (#621) is the model.
 */
class FrameAgentGateTest {
    private val frames = RepoFiles.file("frames").listFiles { f -> File(f, "design/frame.json").isFile }.orEmpty().map { it.name }.sorted()

    private fun withAgent(): List<String> = frames.filter { id ->
        JSONObject(RepoFiles.file("frames/$id/design/frame.json").readText()).has("agent")
    }

    private val driver = Regex("""FrameAgentContract\("([a-z0-9-]+)"\)""")

    /** The frame ids the contract drivers name, from every module's tests, with the file each is in. */
    private fun drivers(): List<Pair<String, String>> =
        RepoFiles.sources(atLeast = 50, sourceSet = "test") { it.extension == "kt" }
            .flatMap { f -> driver.findAll(f.readText()).map { it.groupValues[1] to RepoFiles.rel(f) } }

    @Test fun everyFrameThatDeclaresAnAgentHasADriverAndEveryDriverAFrame() {
        assertTrue("the frames are found", frames.size >= 4)
        val declared = withAgent()
        assertTrue("the S1 declares one", "aqara-s1-plus" in declared)
        val driven = drivers()
        assertEquals("frames with an agent and the frames their drivers name", declared, driven.map { it.first }.sorted())
        assertEquals("one driver per frame: $driven", driven.size, driven.map { it.first }.toSet().size)
    }

    @Test fun theCapabilityWordsAreFrameCardsWords() {
        val ts = RepoFiles.file("packages/frame-card/src/frame.ts").readText()
        val list = Regex("""AGENT_CAPABILITIES = \[([^\]]*)\]""").find(ts)?.groupValues?.get(1)
            ?: throw AssertionError("no AGENT_CAPABILITIES in packages/frame-card/src/frame.ts")
        val words = Regex("""'([a-z-]+)'""").findAll(list).map { it.groupValues[1] }.toList()
        assertEquals(words, AgentCapability.entries.map { it.id })
    }

    @Test fun noAgentDeclaresNothingAndOffersNothing() {
        assertTrue(NoAgent.declared.isEmpty())
        assertNull(NoAgent.version())
        for (c in AgentCapability.entries) assertFalse("$c", NoAgent.offers(c))
        assertNull(NoAgent.appUpdates)
        assertNull(NoAgent.bluetoothAddress())
    }

}
