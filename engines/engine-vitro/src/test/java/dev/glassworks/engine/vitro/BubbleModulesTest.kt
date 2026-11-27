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
        file: BubbleModules.FileProbe?,
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
            fileProbe = { url -> fetched += url; file },
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
        val list = start(file = MODULES_FILE)
        assertFalse(list.bubbleModules)
        assertTrue(server.commands(BubbleModules.TOOLS_COMMAND).isEmpty())
        assertTrue(fetched.isEmpty())
    }

    @Test
    fun noToolsNoSensorAndNoFileMeansNoModules() {
        withBubble()
        val list = start(file = MISSING)
        assertFalse(list.bubbleModules)
        assertEquals(1, server.commands(BubbleModules.TOOLS_COMMAND).size)
        // The sensor is asked for on its own, and its subscription ends once answered.
        val sub = server.commands("subscribe_entities").single { it.optJSONArray("entity_ids")?.toString() == """["${BubbleModules.SENSOR}"]""" }
        awaitCondition("unsubscribe") { server.commands("unsubscribe_events").any { it.optInt("subscription") == sub.getInt("id") } }
        assertEquals(listOf("http://192.0.2.10:8123/local/bubble/bubble-modules.yaml"), fetched.toList())
        assertEquals(listOf(LovelaceResource("/hacsfiles/Bubble-Card/bubble-card.js?hacstag=343", "module")), list.toList())
    }

    @Test
    fun theLegacyFileMeansModules() {
        withBubble()
        assertTrue(start(file = MODULES_FILE).bubbleModules)
    }

    @Test
    fun theLegacyFileCountsOnlyWhereBubbleWouldReadIt() {
        // Bubble Card 3.4.3 skips an answer that isn't ok and catches a failed fetch: no modules from the file, so
        // Vitro draws its cards. So does a 200 page for a file that isn't there (a proxy's, or the fake HA's before
        // tablet Vitro run 3): every Bubble card went to an island on it.
        withBubble()
        for (file in listOf(null, BubbleModules.FileProbe(500), BubbleModules.FileProbe(403), BubbleModules.FileProbe(302),
            BubbleModules.FileProbe(200, "<!DOCTYPE html>"))) {
            resources.clear()
            assertFalse("$file", start(file = file).bubbleModules)
        }
    }

    @Test
    fun theModulesSensorMeansModules() {
        withBubble()
        server.initialEntities = JSONObject().put(BubbleModules.SENSOR, JSONObject().put("s", "3").put("a", JSONObject().put("modules", JSONObject())))
        assertTrue(start(file = MISSING).bubbleModules)
        assertTrue(fetched.isEmpty())
    }

    @Test
    fun bubbleCardToolsMeansModules() {
        withBubble()
        assertTrue(start(file = MISSING, connect = toolsConnect(JSONArray())).bubbleModules)
        assertTrue(server.commands("subscribe_entities").none { it.optJSONArray("entity_ids")?.toString() == """["${BubbleModules.SENSOR}"]""" })
        assertTrue(fetched.isEmpty())
    }

    @Test
    fun aLateAnswerFromAReplacedConnectionDoesntOverwriteTheCurrentOne() {
        withBubble()
        val release = java.util.concurrent.CountDownLatch(1)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val c = HaDashboardClient(
            haBase = FakeHaServer.BASE,
            tokenProvider = { _, done -> done(FakeHaServer.TOKEN) },
            store = EntityStore(),
            listener = object : HaDashboardClient.Listener {
                override fun onResources(resources: List<LovelaceResource>) { this@BubbleModulesTest.resources += resources }
            },
            // The first connection's file answer (none: 404) is held until the next connection has asked; that one
            // finds the file.
            fileProbe = { if (calls.incrementAndGet() == 1) { release.await(10, java.util.concurrent.TimeUnit.SECONDS); MISSING } else MODULES_FILE },
            connect = server::connect,
        )
        clients += c
        c.start(null)
        awaitCondition("the first connection's file probe") { calls.get() == 1 }
        server.dropConnection()
        // The replaced connection's answer is stale once the next connection asks (its generation moved on).
        awaitCondition("the second connection's probe") { server.commands(BubbleModules.TOOLS_COMMAND).size == 2 &&
            server.commands("subscribe_entities").count { it.optJSONArray("entity_ids")?.toString() == """["${BubbleModules.SENSOR}"]""" } == 2 }
        release.countDown()
        // The probes run one at a time on one executor, each with its answer: once the second has answered, the first's
        // answer (dropped or not) has run before it. No sleep (Sourcery on #952).
        awaitCondition("the current connection's answer") { calls.get() == 2 && resources.isNotEmpty() }
        // Only the current connection's verdict: the late 404 of the replaced one is dropped.
        assertEquals(1, resources.size)
        assertTrue((resources.single() as LovelaceResourceList).bubbleModules)
    }

    @Test
    fun theResourceListIsAListLikeAnyOther() {
        val items = listOf(LovelaceResource("/hacsfiles/Bubble-Card/bubble-card.js", "module"))
        val withModules = LovelaceResourceList(items, bubbleModules = true)
        assertEquals(ArrayList(items), withModules)
        assertEquals(withModules, ArrayList(items))
        assertEquals(items.hashCode(), withModules.hashCode())
        assertEquals(LovelaceResourceList(items, bubbleModules = false), withModules)
    }

    @Test
    fun answersAreReadConservatively() {
        assertFalse(BubbleModules.toolsAnswer(JSONObject("""{"success": false, "error": {"code": "unknown_command"}}""")))
        assertTrue(BubbleModules.toolsAnswer(JSONObject("""{"success": false, "error": {"code": "unauthorized"}}""")))
        assertTrue(BubbleModules.toolsAnswer(JSONObject("""{"success": true, "result": []}""")))
        assertFalse(BubbleModules.sensorAnswer(JSONObject("""{"a": {}}""")))
        assertTrue(BubbleModules.sensorAnswer(JSONObject("""{"a": {"sensor.bubble_card_modules": {"s": "1"}}}""")))
        assertFalse(BubbleModules.fileAnswer(MISSING))
        assertTrue(BubbleModules.fileAnswer(MODULES_FILE))
        assertTrue(BubbleModules.fileAnswer(BubbleModules.FileProbe(206, "my_module:")))
        // As Bubble's `if (!response.ok) continue` and its caught fetch: none.
        assertFalse(BubbleModules.fileAnswer(null))
        assertFalse(BubbleModules.fileAnswer(BubbleModules.FileProbe(500, "default:")))
        assertFalse(BubbleModules.fileAnswer(BubbleModules.FileProbe(304, "default:")))
        assertFalse(BubbleModules.fileAnswer(BubbleModules.FileProbe(200, "")))
        assertTrue(BubbleModules.loaded(listOf(LovelaceResource("/hacsfiles/Bubble-Card/bubble-card.js", "module"))))
        assertFalse(BubbleModules.loaded(listOf(LovelaceResource("/local/bubble-card.css", "css"))))
    }

    @Test
    fun onlyAYamlMappingHoldsModules() {
        // Bubble parses the body as YAML and takes modules from a mapping alone (Sourcery on #952: the content type and
        // a 4 KiB window decided it before).
        for (yes in listOf("default:", "my_module:", "default: {name: x}", "\"my module\": 1", "'a''s': 1", "? complex", "{a: 1}",
            "{ default: { name: x } }", "-dash-key: 1", "key:   # a comment after",
            // Node properties in front of the mapping or its first key (Greptile on #952).
            "&modules default:", "!!map default:", "&m !!map {a: 1}", "!<tag:yaml.org,2002:map> default:")) {
            assertTrue(yes, BubbleModules.isMapping(yes))
        }
        for (no in listOf("", "{}", "{ }  # empty", "- default", "[default]", "just text", "<!DOCTYPE html>", "<html><head>",
            "404 Not Found", "\"quoted scalar\"", "http://x", "a:b", "|", "& anchor", "&modules", "!!map", "&m - default")) {
            assertFalse(no, BubbleModules.isMapping(no))
        }
    }

    @Test
    fun theFirstContentLineSkipsWhatYamlSkipsWithoutAFixedWindow() {
        fun first(body: String) = BubbleModules.firstContentLine(body.byteInputStream())
        assertEquals("default:", first("default:\n  name: x\n"))
        // 8 KiB of blank lines and comments, a directive and a document start before the content: still found.
        assertEquals("default:", first(" \n".repeat(4096) + "# modules\n%YAML 1.2\n---\ndefault:\n"))
        assertEquals("{a: 1}", first("--- {a: 1}\n"))
        assertEquals("  default:", first("\uFEFF\r\n  default:\r\n"))
        assertEquals("", first("\n\n# only comments\n"))
        // An anchor or tag on its own line belongs to the mapping below it.
        assertEquals("default:", first("&modules\ndefault:\n  name: x\n"))
        assertEquals("default:", first("--- !!map\ndefault:\n"))
        assertEquals("", first("&modules\n"))
        // Past the cap with nothing read: none (YAML would read nothing there either).
        assertEquals("", first(" ".repeat(BubbleModules.MAX_BODY_CHARS + 10) + "\ndefault:"))
    }

    @Test
    fun theProbeReadsTheFileAsBubblesFetchDoes() {
        val http = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        fun route(path: String, status: Int, type: String, body: String, location: String? = null) = http.createContext(path) { ex ->
            val bytes = body.toByteArray()
            ex.responseHeaders.add("Content-Type", type)
            location?.let { ex.responseHeaders.add("Location", it) }
            ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        route("/yaml", 200, "text/yaml", "\n\n  default:\n    name: Default\n" + "x".repeat(10_000))
        route("/page", 200, "text/html; charset=utf-8", "<!DOCTYPE html><html></html>")
        // Valid YAML labelled as HTML is still YAML to Bubble; a list or a scalar holds no modules whatever its type.
        route("/yaml-as-html", 200, "text/html", "default:\n  name: x\n")
        route("/list", 200, "text/yaml", "- default\n- other\n")
        route("/scalar", 200, "text/yaml", "modules go here\n")
        // A mapping after more than 4 KiB of blank lines.
        route("/late", 200, "text/yaml", "\n".repeat(6000) + "default:\n")
        route("/missing", 404, "text/plain", "404: Not Found")
        http.start()
        val base = "http://127.0.0.1:${http.address.port}"
        // Redirects within the origin are followed, as fetch follows them, up to a few hops; another origin's fails.
        route("/moved", 301, "text/plain", "", "/yaml")
        route("/hop1", 302, "text/plain", "", "$base/hop2")
        route("/hop2", 307, "text/plain", "", "/yaml")
        route("/loop", 302, "text/plain", "", "/loop")
        val other = java.net.ServerSocket(0).use { it.localPort }
        route("/away", 302, "text/plain", "", "http://127.0.0.1:$other/yaml")
        try {
            assertEquals(BubbleModules.FileProbe(200, "  default:"), BubbleModules.probe("$base/yaml"))
            assertTrue(BubbleModules.fileAnswer(BubbleModules.probe("$base/yaml")))
            assertFalse(BubbleModules.fileAnswer(BubbleModules.probe("$base/page")))
            assertTrue(BubbleModules.fileAnswer(BubbleModules.probe("$base/yaml-as-html")))
            assertFalse(BubbleModules.fileAnswer(BubbleModules.probe("$base/list")))
            assertFalse(BubbleModules.fileAnswer(BubbleModules.probe("$base/scalar")))
            assertTrue(BubbleModules.fileAnswer(BubbleModules.probe("$base/late")))
            assertEquals(BubbleModules.FileProbe(404, ""), BubbleModules.probe("$base/missing"))
            assertTrue(BubbleModules.fileAnswer(BubbleModules.probe("$base/moved")))
            assertTrue("two hops", BubbleModules.fileAnswer(BubbleModules.probe("$base/hop1")))
            assertEquals("a redirect loop gives up", null, BubbleModules.probe("$base/loop"))
            assertEquals("another origin: no file", null, BubbleModules.probe("$base/away"))
        } finally {
            http.stop(0)
        }
        // Nothing listening: the request fails, and so the file gives no modules.
        val closed = java.net.ServerSocket(0).use { it.localPort }
        assertEquals(null, BubbleModules.probe("http://127.0.0.1:$closed/x"))
    }

    private companion object {
        val MISSING = BubbleModules.FileProbe(404)
        val MODULES_FILE = BubbleModules.FileProbe(200, "default:")
    }
}
