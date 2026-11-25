/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.app.Application
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.glassworks.engine.EngineSettings
import dev.glassworks.engine.Page
import dev.glassworks.engine.vitro.cards.WebIslandCardView
import dev.glassworks.engine.vitro.compat.CompatChecker
import dev.glassworks.engine.vitro.compat.VitroSupport
import dev.glassworks.engine.vitro.compat.WebFallback
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.data.HaDashboardClient
import dev.glassworks.engine.vitro.model.LovelaceParser
import dev.glassworks.engine.vitro.ui.VitroCardContext
import dev.glassworks.engine.vitro.ui.VitroView
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * What Vitro can't draw goes to Home Assistant's own frontend, never to an empty page, an empty island or a wrong
 * row ([WebFallback]): strategy dashboards, views and sections, built-in cards with no native renderer, and the
 * special `entities` rows Vitro doesn't draw.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WebFallbackTest {
    private val base = FakeHaServer.BASE
    private val settings = EngineSettings(secureHosts = listOf("192.0.2.10"), zoom = 1f, remoteDebugging = false, dashboard = "$base/wall-test/living")
    private lateinit var activity: Activity
    private val host = RecordingHost()
    private val fallback = RecordingEngine()
    private val server = FakeHaServer()
    private val engines = ArrayList<VitroEngine>()
    private val opened = ArrayList<Page>()

    @Before fun setUp() { activity = Robolectric.buildActivity(Activity::class.java).setup().get() }

    @After fun tearDown() {
        engines.forEach { runCatching { it.islandServer.stop() } }
        opened.forEach { page ->
            runCatching {
                val f = page.javaClass.getDeclaredField("client"); f.isAccessible = true
                (f.get(page) as? HaDashboardClient)?.close()
            }
        }
        server.shutdown()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun awaitMain(message: String, cond: () -> Boolean) = awaitCondition(message, tick = { idle() }, cond = cond)

    private fun page(): Page {
        val engine = VitroEngine(
            fallback = fallback, settings = { settings }, enabled = { true },
            tokenProvider = { _, done -> done(FakeHaServer.TOKEN) }, socketFactory = server::connect,
        ).also { engines += it }
        return engine.open(activity, host, Color.BLACK).also {
            opened += it
            activity.setContentView(it.view)
            idle()
        }
    }

    private fun vitroView(page: Page): VitroView = (page.view as ViewGroup).getChildAt(0) as VitroView

    /** The page's own fallback (opened with the activity's host); islands open theirs with the island host. */
    private fun pageFallback(): RecordingPage? = fallback.opens.indexOfFirst { it.host === host }.takeIf { it >= 0 }?.let { fallback.pages[it] }
    private fun islandOpens() = fallback.opens.count { it.host !== host }

    private fun views(vararg views: String) = JSONObject("""{"views": [${views.joinToString(",")}]}""")

    private val living = """{"title": "Living", "path": "living", "cards": [{"type": "tile", "entity": "light.lounge_lamp"}]}"""

    // ---- Strategies ----

    @Test fun aStrategyDashboardShowsInHomeAssistantsFrontend() {
        // What HA stores for a dashboard made from a strategy (Map, Areas, ...): no views, a strategy.
        server.dashboardConfig = JSONObject("""{"strategy": {"type": "original-states"}}""")
        val p = page()
        p.load("$base/wall-test")
        awaitMain("fallback") { pageFallback() != null }
        idle()
        assertEquals(listOf("$base/wall-test"), pageFallback()!!.loads)
        assertEquals(View.GONE, vitroView(p).visibility)
        assertEquals(View.VISIBLE, pageFallback()!!.view.visibility)
        assertTrue("Vitro signals no ready of its own: the fallback page does", host.messages("ready").isEmpty())
    }

    @Test fun aDashboardWithAStrategyIgnoresItsViews() {
        // HA generates a dashboard from its strategy whatever else the config holds.
        server.dashboardConfig = JSONObject("""{"strategy": {"type": "custom:my-strategy"}, "views": [$living]}""")
        val p = page()
        p.load("$base/wall-test/living")
        awaitMain("fallback") { pageFallback() != null }
        assertEquals(listOf("$base/wall-test/living"), pageFallback()!!.loads)
        assertEquals(View.GONE, vitroView(p).visibility)
    }

    @Test fun aStrategyViewShowsInHomeAssistantsFrontendAndTheOtherViewsStayNative() {
        server.dashboardConfig = views(living, """{"title": "Areas", "path": "areas", "strategy": {"type": "areas"}}""")
        val p = page()
        p.load("$base/wall-test/living")
        awaitMain("ready") { host.messages("ready").isNotEmpty() }
        assertNull("the native view needs no fallback", pageFallback())

        p.load("$base/wall-test/areas")
        idle()
        assertEquals(listOf("$base/wall-test/areas"), pageFallback()!!.loads)
        assertEquals(View.GONE, vitroView(p).visibility)

        // Back to a native view (a host navigate: idle return, a notification closing): drawn again from memory.
        val fetches = server.commands("lovelace/config").size
        host.ports.single().post(JSONObject().put("type", "navigate").put("url", "$base/wall-test/living"))
        idle()
        assertEquals(View.VISIBLE, vitroView(p).visibility)
        assertEquals(View.GONE, pageFallback()!!.view.visibility)
        assertEquals("no refetch for the dashboard it already has", fetches, server.commands("lovelace/config").size)
    }

    @Test fun aStrategySectionSendsItsViewToTheFallback() {
        server.dashboardConfig = views("""{"title": "Home", "path": "home", "type": "sections", "sections": [
            {"type": "grid", "cards": [{"type": "tile", "entity": "light.lounge_lamp"}]},
            {"strategy": {"type": "custom:room-section"}}]}""")
        val p = page()
        p.load("$base/wall-test/home")
        awaitMain("fallback") { pageFallback() != null }
        assertEquals(listOf("$base/wall-test/home"), pageFallback()!!.loads)
    }

    @Test fun aDefaultViewThatIsAStrategyOpensByItsIndex() {
        server.dashboardConfig = views("""{"title": "Overview", "strategy": {"type": "original-states"}}""")
        page().load("$base/wall-test")
        awaitMain("fallback") { pageFallback() != null }
        assertEquals(listOf("$base/wall-test/0"), pageFallback()!!.loads)
    }

    // ---- Built-in cards Vitro doesn't draw ----

    @Test fun aViewWithAMapCardShowsInHomeAssistantsFrontendWithNoIsland() {
        server.dashboardConfig = views(living, """{"title": "Map", "path": "map", "cards": [
            {"type": "map", "entities": ["light.lounge_lamp"]}]}""")
        val p = page()
        p.load("$base/wall-test/living")
        awaitMain("ready") { host.messages("ready").isNotEmpty() }

        // A tab tap, as the user does it.
        val vv = vitroView(p)
        tab(vv, "Map").performClick()
        idle()
        assertEquals(listOf("$base/wall-test/map"), pageFallback()!!.loads)
        assertEquals(View.GONE, vv.visibility)
        assertEquals("the map card costs no island session", 0, islandOpens())
    }

    @Test fun anIframeCardIsAnIslandOfItsOwnAndItsViewStaysNative() {
        server.dashboardConfig = views("""{"title": "Cam", "path": "cam", "type": "panel", "cards": [
            {"type": "iframe", "url": "https://example.com/", "allow": "fullscreen; autoplay"}]}""")
        page().load("$base/wall-test/cam")
        awaitMain("ready") { host.messages("ready").isNotEmpty() }
        assertNull("the view is drawn natively", pageFallback())
        assertEquals("one browser page, for the iframe's own page", 1, islandOpens())
        val island = fallback.pages[fallback.opens.indexOfFirst { it.host !== host }]
        assertTrue(island.loads.single(), island.loads.single().contains("/__vitro/island/"))
    }

    @Test fun aBuiltInCardNestedInAStackOrAutoEntitiesCounts() {
        fun reason(card: String) = WebFallback.viewReason(LovelaceParser.parseView(0, JSONObject("""{"cards": [$card]}""")))
        assertEquals("the built-in `map` card", reason("""{"type": "vertical-stack", "cards": [{"type": "tile"}, {"type": "map"}]}"""))
        assertEquals("the built-in `energy-distribution` card", reason("""{"type": "conditional", "conditions": [], "card": {"type": "energy-distribution"}}"""))
        assertEquals("the built-in `map` card", reason("""{"type": "custom:auto-entities", "card": {"type": "map"}, "filter": {"include": [{"domain": "person"}]}}"""))
        assertEquals("the built-in `water-sankey` card", reason("""{"type": "custom:layout-card", "cards": [{"type": "water-sankey"}]}"""))
        assertNull("a custom card goes to its island", reason("""{"type": "custom:acme-card"}"""))
        assertNull(reason("""{"type": "grid", "cards": [{"type": "tile"}, {"type": "entities", "entities": ["light.a"]}]}"""))
    }

    // ---- entities rows Vitro doesn't draw ----

    @Test fun aViewWithAnAttributeRowShowsInHomeAssistantsFrontend() {
        // Drawn as a plain row, it showed the entity's state ("Above horizon") where HA shows the attribute.
        server.dashboardConfig = views("""{"title": "Sun", "path": "sun", "cards": [{"type": "entities", "entities": [
            {"type": "attribute", "entity": "sun.sun", "attribute": "elevation", "suffix": "°"}]}]}""")
        page().load("$base/wall-test/sun")
        awaitMain("fallback") { pageFallback() != null }
        assertEquals(listOf("$base/wall-test/sun"), pageFallback()!!.loads)
    }

    @Test fun everySpecialRowVitroDoesntDrawCountsAndTheOnesItDrawsDont() {
        fun reason(row: String) = WebFallback.viewReason(LovelaceParser.parseView(0, JSONObject(
            """{"cards": [{"type": "entities", "entities": ["light.a", $row]}]}""",
        )))
        for (t in listOf("attribute", "cast")) {
            assertEquals(t, "an `$t` row in an entities card", reason("""{"type": "$t", "name": "x"}"""))
        }
        // In a conditional row too.
        assertEquals("an `attribute` row in an entities card", reason(
            """{"type": "conditional", "conditions": [], "row": {"type": "attribute", "entity": "sun.sun", "attribute": "elevation"}}""",
        ))
        for (row in listOf(
            """{"type": "divider"}""", """{"type": "section", "label": "Lights"}""", """{"entity": "light.b"}""",
            """{"type": "toggle-entity", "entity": "light.b"}""", "\"light.b\"",
            """{"type": "button", "name": "Go", "tap_action": {"action": "toggle"}}""",
            """{"type": "call-service", "name": "Ring", "action": "script.ring"}""",
            """{"type": "text", "name": "Wi-Fi", "text": "Guest"}""",
            """{"type": "weblink", "url": "https://example.com/"}""",
            """{"type": "buttons", "entities": ["light.a", "light.b"]}""",
            """{"type": "conditional", "conditions": [{"condition": "state", "entity": "light.a", "state": "on"}], "row": {"type": "text", "name": "a", "text": "b"}}""",
        )) {
            assertNull(row, reason(row))
        }
        // In the cards an entity-filter builds too.
        assertNotNull(WebFallback.viewReason(LovelaceParser.parseView(0, JSONObject(
            """{"cards": [{"type": "entity-filter", "entities": ["light.a"], "card": {"type": "map"}}]}""",
        ))))
    }

    // ---- View layouts ----

    @Test fun aViewLayoutVitroDoesntDrawGoesToTheFallbackAndHasLayoutsStayNative() {
        fun reason(type: String) = WebFallback.viewReason(LovelaceParser.parseView(0, JSONObject(
            """{"type": "$type", "cards": [{"type": "tile", "entity": "light.a"}]}""",
        )))
        for (t in listOf("masonry", "panel", "sidebar", "sections", "custom:masonry-layout", "custom:grid-layout",
            "custom:horizontal-layout", "custom:vertical-layout")) {
            assertNull(t, reason(t))
        }
        assertEquals("a `custom:kiosk-layout` view (a layout Vitro doesn't draw)", reason("custom:kiosk-layout"))
        assertEquals("a `grid-layout` view (a layout Vitro doesn't draw)", reason("grid-layout"))
    }

    @Test fun aViewWithAnUnknownLayoutOpensInTheFallback() {
        server.dashboardConfig = views("""{"title": "Odd", "path": "odd", "type": "custom:kiosk-layout", "cards": [{"type": "tile", "entity": "light.lounge_lamp"}]}""")
        page().load("$base/wall-test/odd")
        awaitMain("fallback") { pageFallback() != null }
        assertEquals(listOf("$base/wall-test/odd"), pageFallback()!!.loads)
    }

    // ---- Shortcut defaults only HA's frontend knows ----

    @Test fun aShortcutNamedByThePageItOpensGoesToTheFallback() {
        fun reason(card: String) = WebFallback.viewReason(LovelaceParser.parseView(0, JSONObject("""{"cards": [$card]}""")))
        assertEquals("a `shortcut` card that shows the page it opens by its title or icon",
            reason("""{"type": "shortcut", "tap_action": {"action": "navigate", "navigation_path": "/lovelace/kitchen"}}"""))
        assertEquals("a `shortcut` card that shows its action's name or icon",
            reason("""{"type": "shortcut", "label": "Lights out", "tap_action": {"action": "perform-action", "perform_action": "light.turn_off"}}"""))
        for (card in listOf(
            """{"type": "shortcut"}""",
            """{"type": "shortcut", "label": "Kitchen", "icon": "mdi:knife", "tap_action": {"action": "navigate", "navigation_path": "/lovelace/kitchen"}}""",
            """{"type": "shortcut", "tap_action": {"action": "navigate", "navigation_path": "/config/areas/area/kitchen"}}""",
            """{"type": "shortcut", "tap_action": {"action": "navigate", "navigation_path": "/config/automation"}}""",
            """{"type": "shortcut", "tap_action": {"action": "url", "url_path": "https://example.com/"}}""",
            """{"type": "shortcut", "tap_action": {"action": "assist"}}""",
        )) {
            assertNull(card, reason(card))
        }
    }

    // ---- The compatibility checker says so ----

    @Test fun theCheckerReportsWhatHomeAssistantsFrontendShowsInsteadOfAnIsland() {
        val report = CompatChecker.check(LovelaceParser.parseDashboard(views(living, """{"title": "Map", "path": "map", "cards": [{"type": "map"}]}""")))
        assertEquals("only the native view's cards are listed", 1, report.entries.size)
        assertTrue(report.islands.isEmpty())
        assertEquals(listOf("view map" to "the built-in `map` card"), report.webPages)
        assertTrue(report.format(), report.format().contains("HA FRONTEND view map: the built-in `map` card"))
        assertFalse(report.format().contains("fully native"))

        val strategy = CompatChecker.check(LovelaceParser.parseDashboard(JSONObject("""{"strategy": {"type": "areas"}}""")))
        assertEquals("the dashboard", strategy.webPages.single().first)
    }

    // ---- Every built-in card HA has ----

    /**
     * Every card type Home Assistant 2026.11's frontend creates (`create-card-element.ts` in frontend 20261028.8: its
     * always-loaded and lazy-loaded types). Each one is either drawn natively, or sends its view to HA's frontend:
     * never an island (which can't draw built-in cards) and never an empty element.
     */
    private val ha202611CardTypes = listOf(
        // ALWAYS_LOADED_TYPES
        "entity", "entities", "button", "entity-button", "glance", "grid", "section", "light", "sensor", "thermostat",
        "weather-forecast", "tile", "heading",
        // LAZY_LOAD_TYPES
        "alarm-panel", "area", "calendar", "conditional", "empty-state", "energy-compare", "energy-carbon-consumed-gauge",
        "energy-date-selection", "energy-devices-graph", "energy-devices-detail-graph", "energy-distribution",
        "energy-gas-graph", "energy-water-graph", "energy-grid-neutrality-gauge", "energy-grid-balance",
        "energy-solar-consumed-gauge", "energy-self-sufficiency-gauge", "energy-solar-graph", "energy-sources-table",
        "energy-usage-graph", "energy-sankey", "water-sankey", "water-flow-sankey", "power-sources-graph", "power-sankey",
        "entity-filter", "error", "home-summary", "shortcut", "discovered-devices", "repairs", "alert", "updates", "gauge",
        "history-graph", "horizontal-stack", "distribution", "humidifier", "iframe", "logbook", "map", "markdown", "clock",
        "date", "media-control", "picture-elements", "picture-entity", "picture-glance", "picture", "plant-status",
        "recovery-mode", "toggle-group", "todo-list", "shopping-list", "starting", "statistics-graph", "statistic",
        "vertical-stack",
    )

    @Test fun everyBuiltInCardOfHa2026_11IsNativeOrGoesToTheFallback() {
        assertEquals("the list has no duplicates", ha202611CardTypes.size, ha202611CardTypes.toSet().size)
        assertTrue(
            "Vitro's native set names only cards HA has: ${VitroSupport.CORE_CARD_TYPES - ha202611CardTypes.toSet()}",
            ha202611CardTypes.containsAll(VitroSupport.CORE_CARD_TYPES),
        )
        val store = EntityStore()
        store.putLocal(EntityState("light.lounge_lamp", "on", mapOf("friendly_name" to "Lounge Lamp")))
        val native = ArrayList<String>()
        val web = ArrayList<String>()
        for (type in ha202611CardTypes) {
            val card = JSONObject(minimalCard(type))
            val view = LovelaceParser.parseView(0, JSONObject().put("cards", org.json.JSONArray().put(card)))
            val reason = view.webFallbackReason
            if (type in VitroSupport.CORE_CARD_TYPES) {
                assertNull("$type is drawn natively", reason)
                assertFalse("$type is built native, not in an island", buildsAnIsland(store, card))
                native += type
            } else {
                assertEquals("$type goes to HA's frontend", "the built-in `$type` card", reason)
                web += type
            }
        }
        println("native: ${native.size} $native\nHA's frontend: ${web.size} $web")
        assertEquals(ha202611CardTypes.size, native.size + web.size)
    }

    /** A config each built-in type builds from; HA's own `getStubConfig` shapes, cut down. */
    private fun minimalCard(type: String): String = when (type) {
        "grid", "horizontal-stack", "vertical-stack" -> """{"type": "$type", "cards": [{"type": "tile", "entity": "light.lounge_lamp"}]}"""
        "conditional" -> """{"type": "$type", "conditions": [], "card": {"type": "tile", "entity": "light.lounge_lamp"}}"""
        "entities", "glance", "entity-filter", "logbook", "history-graph", "statistics-graph", "map" ->
            """{"type": "$type", "entities": ["light.lounge_lamp"]}"""
        "picture-elements" -> """{"type": "$type", "image": "/local/plan.png", "elements": []}"""
        "picture-glance" -> """{"type": "$type", "image": "/local/plan.png", "entities": ["light.lounge_lamp"]}"""
        "picture" -> """{"type": "$type", "image": "/local/plan.png"}"""
        "statistic" -> """{"type": "$type", "entity": "sensor.energy", "stat_type": "change", "period": {"calendar": {"period": "month"}}}"""
        "plant-status" -> """{"type": "$type", "entity": "plant.fern"}"""
        "alert" -> """{"type": "$type", "entity": "binary_sensor.door"}"""
        "toggle-group" -> """{"type": "$type", "entities": ["light.lounge_lamp"]}"""
        "iframe" -> """{"type": "$type", "url": "https://example.com/"}"""
        "date", "shortcut", "section", "shopping-list" -> """{"type": "$type"}"""
        "markdown" -> """{"type": "$type", "content": "Hello"}"""
        "heading" -> """{"type": "$type", "heading": "Lights"}"""
        "clock" -> """{"type": "$type"}"""
        "calendar" -> """{"type": "$type", "entities": ["calendar.home"]}"""
        "area" -> """{"type": "$type", "area": "lounge"}"""
        "todo-list" -> """{"type": "$type", "entity": "todo.shopping"}"""
        else -> """{"type": "$type", "entity": "light.lounge_lamp"}"""
    }

    private fun buildsAnIsland(store: EntityStore, card: JSONObject): Boolean {
        val vv = VitroView(activity, store, Color.BLACK)
        vv.contextFactory = { theme ->
            VitroCardContext(
                context = activity, haBase = base, store = store, theme = theme, resources = emptyList(),
                accessToken = { null }, callService = { _, _, _, _ -> }, navigate = {}, openMoreInfo = {},
                fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
            )
        }
        vv.setDashboard(LovelaceParser.parseDashboard(JSONObject().put("views", org.json.JSONArray().put(
            JSONObject().put("cards", org.json.JSONArray().put(card)),
        ))), null)
        activity.setContentView(vv)
        idle()
        fun walk(v: View): Boolean = v is WebIslandCardView || (v is ViewGroup && (0 until v.childCount).any { walk(v.getChildAt(it)) })
        return walk(vv)
    }

    /** The tab whose label is [title]. */
    private fun tab(vv: VitroView, title: String): View {
        fun find(v: View): View? {
            if (v.isClickable && v.tag is Int && v is ViewGroup && hasText(v, title)) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
            return null
        }
        return find(vv) ?: error("no tab $title")
    }

    private fun hasText(v: View, text: String): Boolean =
        (v is TextView && v.text.toString() == text) || (v is ViewGroup && (0 until v.childCount).any { hasText(v.getChildAt(it), text) })
}
