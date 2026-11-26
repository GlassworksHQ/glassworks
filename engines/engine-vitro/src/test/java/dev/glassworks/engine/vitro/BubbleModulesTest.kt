/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import dev.glassworks.engine.vitro.data.BubbleModules
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.data.HaDashboardClient
import dev.glassworks.engine.vitro.data.HaDisplay
import dev.glassworks.engine.vitro.model.LovelaceResource
import dev.glassworks.engine.vitro.model.LovelaceResourceList
import dev.glassworks.auth.HaSocket
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Whether Bubble Card modules may restyle Bubble cards ([BubbleModules]), as [HaDashboardClient] asks a Home Assistant
 * ([FakeHaServer]) before it hands over the resources: Bubble Card Tools, the modules sensor, then the legacy file.
 */
class BubbleModulesTest {
    private val server = FakeHaServer()
    private val clients = CopyOnWriteArrayList<HaDashboardClient>()
    private val resources = CopyOnWriteArrayList<List<LovelaceResource>>()
    private val fetched = CopyOnWriteArrayList<String>()

    @After
    fun tearDown() {
        clients.forEach { it.close() }
        server.shutdown()
        HaDisplay.reset()
    }

    private fun start(
        fileStatus: Int?,
        connect: (String) -> HaSocket = server::connect,
    ): LovelaceResourceList {
        val c = HaDashboardClient(
            haBase = FakeHaServer.BASE,
            tokenProvider = { _, done -> done(FakeHaServer.TOKEN) },
            store = EntityStore(),
            connect = connect,
            listener = object : HaDashboardClient.Listener {
                override fun onResources(resources: List<LovelaceResource>) { this@BubbleModulesTest.resources += resources }
            },
            httpStatus = { url -> fetched += url; fileStatus },
        )
        clients += c
        c.start(null)
        awaitCondition("resources") { resources.isNotEmpty() }
        return resources.single() as LovelaceResourceList
    }

    private fun withBubble() {
        server.resources = JSONArray("""[{"url": "/hacsfiles/Bubble-Card/bubble-card.js?hacstag=343", "type": "module"}]""")
    }

    /** Answers Bubble Card Tools' command ([toolsReply]: a result, or null to leave it to the fake's `unknown_command`). */
    private fun toolsConnect(toolsReply: Any?): (String) -> HaSocket = { url ->
        val inner = server.connect(url)
        val socket = server.sockets.last()
        object : HaSocket {
            override fun send(text: String) {
                val msg = JSONObject(text)
                if (msg.optString("type") == BubbleModules.TOOLS_COMMAND && toolsReply != null) {
                    socket.push(JSONObject().put("id", msg.getInt("id")).put("type", "result").put("success", true).put("result", toolsReply))
                }
                inner.send(text)
            }

            override fun receive(): String? = inner.receive()
            override fun close() = inner.close()
        }
    }

    @Test
    fun aHomeAssistantWithoutBubbleIsNeverAsked() {
        val list = start(fileStatus = 200)
        assertFalse(list.bubbleModules)
        assertTrue(server.commands(BubbleModules.TOOLS_COMMAND).isEmpty())
        assertTrue(fetched.isEmpty())
    }

    @Test
    fun noToolsNoSensorAndNoFileMeansNoModules() {
        withBubble()
        val list = start(fileStatus = 404)
        assertFalse(list.bubbleModules)
        assertEquals(1, server.commands(BubbleModules.TOOLS_COMMAND).size)
        // The sensor is asked for on its own, and its subscription ends once answered.
        val sub = server.commands("subscribe_entities").single { it.optJSONArray("entity_ids")?.toString() == """["${BubbleModules.SENSOR}"]""" }
        awaitCondition("unsubscribe") { server.commands("unsubscribe_events").any { it.optInt("subscription") == sub.getInt("id") } }
        assertEquals(listOf("http://192.0.2.10:8123/local/bubble/bubble-modules.yaml"), fetched.toList())
        assertEquals(listOf(LovelaceResource("/hacsfiles/Bubble-Card/bubble-card.js?hacstag=343", "module")), list.toList())
    }

    @Test
    fun theLegacyFileOrAnUnreadableAnswerMeansModules() {
        withBubble()
        assertTrue(start(fileStatus = 200).bubbleModules)
        resources.clear()
        assertTrue(start(fileStatus = null).bubbleModules)
        resources.clear()
        assertTrue(start(fileStatus = 500).bubbleModules)
    }

    @Test
    fun theModulesSensorMeansModules() {
        withBubble()
        server.initialEntities = JSONObject().put(BubbleModules.SENSOR, JSONObject().put("s", "3").put("a", JSONObject().put("modules", JSONObject())))
        assertTrue(start(fileStatus = 404).bubbleModules)
        assertTrue(fetched.isEmpty())
    }

    @Test
    fun bubbleCardToolsMeansModules() {
        withBubble()
        assertTrue(start(fileStatus = 404, connect = toolsConnect(JSONArray())).bubbleModules)
        assertTrue(server.commands("subscribe_entities").none { it.optJSONArray("entity_ids")?.toString() == """["${BubbleModules.SENSOR}"]""" })
        assertTrue(fetched.isEmpty())
    }

    @Test
    fun answersAreReadConservatively() {
        assertFalse(BubbleModules.toolsAnswer(JSONObject("""{"success": false, "error": {"code": "unknown_command"}}""")))
        assertTrue(BubbleModules.toolsAnswer(JSONObject("""{"success": false, "error": {"code": "unauthorized"}}""")))
        assertTrue(BubbleModules.toolsAnswer(JSONObject("""{"success": true, "result": []}""")))
        assertFalse(BubbleModules.sensorAnswer(JSONObject("""{"a": {}}""")))
        assertTrue(BubbleModules.sensorAnswer(JSONObject("""{"a": {"sensor.bubble_card_modules": {"s": "1"}}}""")))
        assertFalse(BubbleModules.fileAnswer(404))
        assertTrue(BubbleModules.fileAnswer(200))
        assertTrue(BubbleModules.fileAnswer(null))
        assertTrue(BubbleModules.loaded(listOf(LovelaceResource("/hacsfiles/Bubble-Card/bubble-card.js", "module"))))
        assertFalse(BubbleModules.loaded(listOf(LovelaceResource("/local/bubble-card.css", "css"))))
    }
}
