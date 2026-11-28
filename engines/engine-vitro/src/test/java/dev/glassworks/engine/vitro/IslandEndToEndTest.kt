/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.widget.LinearLayout
import dev.glassworks.engine.vitro.cards.WebIslandCardView
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.island.VitroIslandServer
import dev.glassworks.engine.vitro.model.LovelaceParser
import dev.glassworks.engine.vitro.model.LovelaceResource
import dev.glassworks.engine.vitro.theme.ThemeCatalog
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.VitroCardContext
import dev.glassworks.testing.RepoFiles
import dev.glassworks.web.HttpRequest
import dev.glassworks.web.HttpResponse
import dev.glassworks.web.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetAddress
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin

/**
 * Community cards in islands, end to end: the parity corpus's fixture states in Vitro's own store, Vitro's island
 * server and bridge (WebIslandCardView), and each island page drawn by headless Chromium (`src/test/js/island-e2e.mjs`)
 * with the pinned card bundles from a fake Home Assistant on loopback. Each card must show its states: no "Entity not
 * found", no blank card. Robolectric can't run an island page, and island-probe.mjs answers the bridge itself, so this
 * is the one check of the real path (#799's real-S1 run found Mushroom islands empty).
 *
 * Needs node, the parity tools' Chromium (`npm ci --prefix tools/vitro-parity`, Playwright's Chromium) and the pinned
 * cards (`python3 tools/vitro-parity/cards.py fetch`). Skipped without GW_VITRO_ISLAND_E2E=1; with it (CI's app job),
 * anything missing fails it.
 *
 * Touches: loopback servers it starts and stops, a throwaway headless Chromium, and the card cache it reads.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class IslandEndToEndTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private lateinit var ha: HttpServer
    private lateinit var haOrigin: String
    private val islands = ArrayList<VitroIslandServer>()

    /** Requests the island servers refused for want of the capability (#799): a card's that never reached Home Assistant. */
    private val refusals = java.util.concurrent.CopyOnWriteArrayList<String>()

    @After fun tearDown() {
        islands.forEach { runCatching { it.stop() } }
        if (::ha.isInitialized) ha.stop()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** The pinned cards: name to (version, main file). */
    private val pins: Map<String, Pair<String, String>> by lazy {
        val cards = JSONObject(File(RepoFiles.root, "tools/vitro-parity/cards.json").readText()).getJSONArray("cards")
        (0 until cards.length()).map { cards.getJSONObject(it) }.associate { it.getString("name") to (it.getString("version") to it.getString("main")) }
    }

    private val cache: File by lazy {
        File(System.getenv("GW_PERF_CACHE") ?: File(System.getProperty("user.home"), ".cache/glassworks-perf").path, "cards")
    }

    private fun cardFile(name: String, file: String): File? {
        val (version, _) = pins[name] ?: return null
        return File(cache, "$name-$version/$file").takeIf { it.isFile }
    }

    /** The resource a card's island loads, as HACS registers it. */
    private fun resource(name: String): LovelaceResource {
        val (version, main) = pins.getValue(name)
        return LovelaceResource("/hacsfiles/$name/$main?hacstag=$version", "module")
    }

    /** The fake Home Assistant: the cards at /hacsfiles/, and REST history for a graph (a day's wave of each case's history spec). */
    private fun startHa(history: Map<String, JSONObject>) {
        ha = HttpServer(0, { req -> serve(req, history) }, bindAddress = loopback, workers = 8)
        ha.start()
        haOrigin = "http://127.0.0.1:${ha.localPort}"
    }

    private fun serve(req: HttpRequest, history: Map<String, JSONObject>): HttpResponse {
        if (req.path.startsWith("/hacsfiles/")) {
            val parts = req.path.removePrefix("/hacsfiles/").split('/', limit = 2)
            val f = parts.takeIf { it.size == 2 && ".." !in it[1] }?.let { cardFile(it[0], it[1]) }
                ?: return HttpResponse.text(404, "not pinned")
            return HttpResponse(200, mapOf("Content-Type" to "application/javascript; charset=utf-8"), f.readBytes())
        }
        if (req.path.startsWith("/api/history/period")) {
            if (req.headers["authorization"] != "Bearer $TOKEN") return HttpResponse.text(401, "401: Unauthorized")
            val ids = req.query["filter_entity_id"].orEmpty().split(',').filter { it.isNotBlank() }
            val out = JSONArray()
            for (id in ids) out.put(wave(id, history[id]))
            return HttpResponse(200, mapOf("Content-Type" to "application/json"), out.toString().toByteArray())
        }
        return HttpResponse.text(404, "nope")
    }

    /** A day of points between the spec's min and max, every 10 minutes, the last one now: HA's minimal REST history. */
    private fun wave(id: String, spec: JSONObject?): JSONArray {
        val w = spec?.optJSONObject("wave")
        val min = w?.optDouble("min", 0.0) ?: 0.0
        val max = w?.optDouble("max", 1.0) ?: 1.0
        val now = Instant.now().epochSecond
        val points = JSONArray()
        for (i in 0..144) {
            val t = now - (144 - i) * 600L
            val v = min + (max - min) * (0.5 + 0.5 * sin(2 * PI * i / 144.0))
            val p = JSONObject().put("state", "%.2f".format(java.util.Locale.ROOT, v)).put("last_changed", Instant.ofEpochSecond(t).toString())
            if (i == 0) p.put("entity_id", id)
            points.put(p)
        }
        return points
    }

    private class Case(val name: String, val card: JSONObject, val fixture: JSONObject, val module: String, val expect: List<String>)

    private fun case(file: String, module: String, vararg expect: String): Case {
        val c = JSONObject(File(RepoFiles.root, "engines/engine-vitro/src/test/parity/corpus/$file.json").readText())
        return Case(file, c.getJSONObject("card"), c.getJSONObject("fixture"), module, expect.toList())
    }

    /** HA's config as `get_config` gives it: its `version` is what Mushroom reads on every render. */
    private val config = JSONObject().put("version", "2026.11.0").put("time_zone", "UTC").put("location_name", "Home")
        .put("unit_system", JSONObject().put("temperature", "°C").put("length", "km"))

    /** A context on Vitro's island server, as the engine makes one ([config] and states as the case asks). */
    private fun context(
        activity: Activity,
        store: EntityStore,
        server: VitroIslandServer,
        resources: List<LovelaceResource>,
        haConfig: () -> JSONObject,
        watchConfig: (onChange: (JSONObject) -> Unit) -> () -> Unit = { { } },
        registries: JSONObject,
    ) = VitroCardContext(
        context = activity, haBase = haOrigin, store = store, theme = ThemeResolver.resolve(ThemeCatalog(), true),
        resources = resources, accessToken = { TOKEN }, haConfig = haConfig,
        callService = { _, _, _, _ -> }, navigate = {}, openMoreInfo = {}, fetchHistory = { _, _, _ -> },
        subscribeTemplate = { _, _ -> {} },
        // What HA answers the cards' own calls with: nothing they need here (Bubble's modules, translations).
        callWs = { msg, done ->
            when (msg.optString("type")) {
                "frontend/get_translations" -> done(true, JSONObject().put("resources", JSONObject()), null)
                "get_services" -> done(true, JSONObject(), null)
                else -> done(false, null, JSONObject().put("code", "unknown_command").put("message", "Unknown command."))
            }
        },
        // The fixture's registries, as the client's feed hands them to islands (HassRegistries).
        watchRegistries = { onChange ->
            onChange(registries)
            val stop: () -> Unit = {}
            stop
        },
        watchConfig = watchConfig,
        islandServer = server,
        openIslandPage = { RecordingPage(activity) },
    )

    private fun islandUrl(card: WebIslandCardView): String =
        WebIslandCardView::class.java.getDeclaredField("islandUrl").apply { isAccessible = true }.get(card) as String

    @Test
    fun communityCardsInIslandsDrawTheirStates() {
        if (System.getenv("GW_VITRO_ISLAND_E2E") != "1") assumeTrue("set GW_VITRO_ISLAND_E2E=1 (CI's app job does)", false)
        val node = IslandNode.require()
        val playwright = File(RepoFiles.root, "tools/vitro-parity/node_modules/playwright-core")
        if (!playwright.isDirectory) fail("playwright-core isn't installed: npm ci --prefix tools/vitro-parity")

        val cases = listOf(
            case("mushroom-entity", "lovelace-mushroom", "Bedroom Strip Accent", "Off"),
            case("mushroom-media-playing", "lovelace-mushroom", "Evening Tide"),
            // HA's name (device and entity, from the registries) and HA's attribute format (20%, not 20).
            case("mushroom-fan", "lovelace-mushroom", "Ceiling Fan", "20%"),
            case("mini-graph-card", "mini-graph-card", "Living room", "19.72"),
            case("bubble-card-button", "Bubble-Card", "Living room lamp"),
        )
        for (c in cases) {
            val pin = pins[c.module]
            if (pin == null) fail("${c.module} isn't pinned in cards.json")
            if (cardFile(c.module, pin!!.second) == null) fail("${c.module} ${pin.first} isn't in the card cache: python3 tools/vitro-parity/cards.py fetch")
        }
        val history = HashMap<String, JSONObject>()
        for (c in cases) c.fixture.optJSONObject("history")?.let { h -> for (k in h.keys()) history[k] = h.getJSONObject(k) }
        startHa(history)

        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        activity.setContentView(column)
        val jobs = JSONArray()
        // The guard (#799's real-S1 run): pages made before the panel had HA's config, which arrives a moment later
        // over the bridge. Mushroom 5.3 reads hass.config.version on every render with its entity: with no config it
        // threw on each update and kept the "Entity not found" its first render (before the states) had drawn.
        val late = ArrayList<(JSONObject) -> Unit>()
        var configNow = JSONObject()
        // The guard's island: config goes to it once its bridge has connected without one.
        var guardIsland: WebIslandCardView? = null
        val guards = listOf(case("mushroom-entity", "lovelace-mushroom", "Bedroom Strip Accent", "Off"))
        for ((i, c) in (cases.map { it to false } + guards.map { it to true }).withIndex()) {
            val (case, guard) = c
            val store = EntityStore()
            store.applyStatesArray(JSONArray(case.fixture.getJSONObject("states").let { s ->
                s.keys().asSequence().map { id ->
                    val st = s.getJSONObject(id)
                    JSONObject().put("entity_id", id).put("state", st.getString("state"))
                        .put("attributes", st.optJSONObject("attributes") ?: JSONObject())
                        .put("last_changed", Instant.now().minusSeconds(600).toString()).put("last_updated", Instant.now().minusSeconds(600).toString())
                }.toList()
            }))
            // HA's answer, as a subscription for every entity gives it: what the fixture lacks doesn't exist, and the
            // island's seed is complete (the page makes its card with it).
            store.markSnapshot(emptySet())
            // The browser's own favicon request isn't a card's.
            val server = VitroIslandServer(session = { haOrigin to TOKEN }, log = { line ->
                if (line.startsWith("refused: no capability") && "/favicon.ico" !in line) refusals += line
            }).also { islands += it }
            val ctx = context(
                activity, store, server, listOf(resource(case.module)),
                haConfig = if (guard) ({ configNow }) else ({ config }),
                watchConfig = if (guard) ({ onChange -> late += onChange; { late -= onChange } }) else ({ { } }),
                registries = JSONObject()
                    .put("entities", dev.glassworks.engine.vitro.data.HassRegistries.entities(
                        JSONObject().put("entities", case.fixture.optJSONArray("entities") ?: JSONArray())))
                    .put("devices", dev.glassworks.engine.vitro.data.HassRegistries.keyed(case.fixture.optJSONArray("devices"), "id"))
                    .put("areas", dev.glassworks.engine.vitro.data.HassRegistries.keyed(case.fixture.optJSONArray("areas"), "area_id"))
                    .put("floors", JSONObject()),
            )
            val island = WebIslandCardView(ctx, LovelaceParser.parseCard(case.card))
            if (guard) guardIsland = island
            column.addView(island)
            jobs.put(JSONObject().put("name", if (guard) "${case.name} (config after the page)" else case.name)
                .put("url", islandUrl(island)).put("tag", case.card.getString("type").removePrefix("custom:"))
                .put("expect", JSONArray(case.expect)))
            assertTrue("case $i has a page", islandUrl(island).startsWith("http://127.0.0.1:"))
            assertTrue("case $i's page is under its server's capability", server.isIslandPage(islandUrl(island)))
        }
        idle()
        val jobFile = File.createTempFile("island-e2e", ".json").apply { writeText(JSONObject().put("cases", jobs).toString()) }

        // Node drives Chromium; the main looper keeps turning meanwhile (the bridge posts to it), and HA's config
        // reaches the guard's page half a second after its bridge connected without it (the late-config watcher).
        var result: Pair<Int, String>? = null
        var failure: Throwable? = null
        val runner = thread {
            try { result = IslandNode.run(node, listOf(File(RepoFiles.root, "engines/engine-vitro/src/test/js/island-e2e.mjs").path, jobFile.path), timeoutSeconds = 240) }
            catch (t: Throwable) { failure = t }
        }
        var seededAt = 0L
        var told = false
        while (runner.isAlive) {
            idle()
            if (seededAt == 0L && guardConnected(guardIsland)) seededAt = System.nanoTime()
            if (!told && seededAt != 0L && System.nanoTime() - seededAt > TimeUnit.MILLISECONDS.toNanos(500)) {
                told = true
                configNow = config
                late.toList().forEach { it(config) }
            }
            Thread.sleep(50)
        }
        assertTrue("the guard's page connected before HA's config (its seed had none), so the late-config path ran", told)
        jobFile.delete()
        failure?.let { throw it }
        val (code, out) = result!!
        assertEquals(out, 0, code)

        val reports = out.lines().filter { it.startsWith("{") }.map(::JSONObject).associateBy { it.getString("name") }
        val problems = ArrayList<String>()
        for (j in (0 until jobs.length()).map { jobs.getJSONObject(it) }) {
            val name = j.getString("name")
            val r = reports[name] ?: run { problems += "$name: no report"; continue }
            val text = r.optString("text")
            if (!r.optBoolean("card")) problems += "$name: no card (${r.optString("notice")}${r.optString("failure")})"
            if (r.optInt("height") <= 0) problems += "$name: drew nothing"
            for (bad in listOf("Entity not found", "Entity not available")) if (text.contains(bad, ignoreCase = true)) problems += "$name: \"$bad\" ($text)"
            for (want in (0 until j.getJSONArray("expect").length()).map { j.getJSONArray("expect").getString(it) }) {
                if (want !in text) problems += "$name: no \"$want\" in \"${text.take(300)}\" (errors: ${r.optJSONArray("errors")})"
            }
        }
        // mini-graph draws its graph from HA's history: a path, not only its number.
        reports["mini-graph-card"]?.let { r ->
            val tags = r.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            if ("path" !in tags) problems += "mini-graph-card: no graph (elements: $tags)"
        }
        // Every request the cards made reached Home Assistant: their own absolute paths too (by the page's Referer).
        for (r in refusals) problems += "island server $r"
        assertTrue(problems.joinToString("\n") + "\n\n" + out, problems.isEmpty())
    }

    private companion object {
        const val TOKEN = "made-up-island-e2e-token"
    }

    /** True once the guard island's page has a bridge open (its seed went out, with no config yet). */
    private fun guardConnected(island: WebIslandCardView?): Boolean {
        island ?: return false
        val senders = WebIslandCardView::class.java.getDeclaredField("senders").apply { isAccessible = true }.get(island) as Collection<*>
        return senders.isNotEmpty()
    }

}
