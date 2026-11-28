/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.app.Application
import android.graphics.Color
import android.os.Looper
import android.view.ViewGroup
import dev.glassworks.engine.EngineSettings
import dev.glassworks.engine.Page
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.data.HaDashboardClient
import dev.glassworks.engine.vitro.ui.VitroView
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * G-performance L3, fix B: Vitro follows every entity the dashboard references, once per connection, as HA's frontend
 * subscribes once at startup. A view switch asks HA for nothing and waits for no snapshot; a config change asks only
 * for its new ids; a dashboard above the cap goes back to one view's entities at a time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DashboardSubscriptionTest {
    /** Its clients never go to the network for HA's frontend files ([NoHaFrontendFetch]). */
    @get:org.junit.Rule val noFrontendFetch = NoHaFrontendFetch()

    private val base = FakeHaServer.BASE
    private val settings = EngineSettings(secureHosts = listOf("192.0.2.10"), zoom = 1f, remoteDebugging = false, dashboard = "$base/wall-test/living")

    private lateinit var activity: Activity
    private val host = RecordingHost()
    private val server = FakeHaServer()
    private val engines = ArrayList<VitroEngine>()
    private val opened = ArrayList<Page>()

    /** Four views, a subview among them; the shopping list with no entity of its own follows every entity. */
    private val dashboard = """{"title": "Test Home", "views": [
        {"title": "Living", "path": "living", "cards": [{"type": "tile", "entity": "light.lounge_lamp"}]},
        {"title": "Kitchen", "path": "kitchen", "cards": [{"type": "entities", "entities": ["sensor.kitchen_temp"]}]},
        {"title": "Hall", "path": "hall", "subview": true, "cards": [{"type": "tile", "entity": "binary_sensor.front_door"}]},
        {"title": "List", "path": "list", "cards": [{"type": "shopping-list"}]}]}"""

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        server.dashboardConfig = JSONObject(dashboard)
    }

    @After
    fun tearDown() {
        engines.forEach { runCatching { it.islandServer.stop() } }
        opened.forEach { page ->
            runCatching { (page.javaClass.getDeclaredField("client").apply { isAccessible = true }.get(page) as? HaDashboardClient)?.close() }
        }
        server.shutdown()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun awaitMain(message: String, cond: () -> Boolean) = awaitCondition(message, tick = { idle() }, cond = cond)

    private fun readyPage(view: String = "living"): Page {
        val engine = VitroEngine(
            fallback = RecordingEngine(),
            settings = { settings },
            enabled = { true },
            tokenProvider = { _, done -> done(FakeHaServer.TOKEN) },
            socketFactory = server::connect,
            testDashboard = { null },
        ).also { engines += it }
        val page = engine.open(activity, host, Color.BLACK).also { opened += it; idle() }
        page.load("$base/wall-test/$view")
        awaitMain("first ready") { host.messages("ready").isNotEmpty() }
        return page
    }

    private fun vitroView(page: Page): VitroView = (page.view as ViewGroup).getChildAt(0) as VitroView
    private fun store(page: Page) = page.javaClass.getDeclaredField("store").apply { isAccessible = true }.get(page) as EntityStore
    private fun subs() = server.commands("subscribe_entities")
    private fun ids(m: JSONObject): Set<String> = m.optJSONArray("entity_ids")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty()
    private val union = setOf("light.lounge_lamp", "sensor.kitchen_temp", "binary_sensor.front_door")

    /** Switches to [path] and runs only what's due now: a build that waited for states would still be waiting. */
    private fun switchTo(page: Page, path: String, index: Int) {
        page.load("$base/wall-test/$path")
        idle()
        val vv = vitroView(page)
        assertEquals(index, vv.currentViewIndex)
    }

    @Test
    fun theDashboardIsSubscribedOnceWithTheViewOnScreenFirst() {
        readyPage()
        awaitMain("both parts") { subs().size == 2 }
        assertEquals(setOf("light.lounge_lamp"), ids(subs()[0]))
        assertEquals(setOf("sensor.kitchen_temp", "binary_sensor.front_door"), ids(subs()[1]))
    }

    @Test
    fun aViewSwitchSendsNoSubscribeAndWaitsForNoSnapshot() {
        val page = readyPage()
        val store = store(page)
        awaitMain("every state of the dashboard") { subs().size == 2 && union.all { store[it] != null } }
        // From now on a subscription would never be answered: a view that waited for one would never build.
        server.holdEntitySnapshot = true
        val before = server.received.size
        switchTo(page, "kitchen", 1)
        assertTrue("built at once, with its states", vitroView(page).currentViewBuilt)
        assertEquals("Kitchen", engines.single().lastMetrics.viewTitle)
        switchTo(page, "hall", 2) // a subview
        assertTrue(vitroView(page).currentViewBuilt)
        switchTo(page, "living", 0)
        assertEquals(2, subs().size)
        assertTrue("no entity subscription dropped", server.commands("unsubscribe_events").none { u -> subs().any { it.getInt("id") == u.optInt("subscription") } })
        assertTrue(server.received.drop(before).none { it.optString("type") == "subscribe_entities" })
    }

    @Test
    fun aConfigChangeSubscribesOnlyItsNewIds() {
        val page = readyPage()
        awaitMain("both parts") { subs().size == 2 }
        val updated = JSONObject(dashboard)
        updated.getJSONArray("views").getJSONObject(1).getJSONArray("cards")
            .put(JSONObject("""{"type": "entities", "entities": ["sensor.unreferenced_power"]}"""))
        server.dashboardConfig = updated
        server.pushEvent(server.eventSubscriptionId("lovelace_updated")!!, JSONObject().put("event_type", "lovelace_updated"))
        awaitMain("the new id") { subs().size == 3 }
        assertEquals(setOf("sensor.unreferenced_power"), ids(subs()[2]))
        awaitMain("its state") { store(page)["sensor.unreferenced_power"] != null }
        assertTrue(server.commands("unsubscribe_events").none { u -> subs().any { it.getInt("id") == u.optInt("subscription") } })
    }

    @Test
    fun aMalformedIdInAnotherViewNeverStopsTheDashboardsEntities() {
        // HA refuses a whole subscribe_entities for one malformed id (the fake does too): a typo in a third view must
        // not cost the view on screen, or the kitchen's, their states.
        val bad = JSONObject(dashboard)
        bad.getJSONArray("views").put(JSONObject("""{"title": "Typo", "path": "typo", "cards": [{"type": "tile", "entity": "not-an-id"}]}"""))
        server.dashboardConfig = bad
        val page = readyPage()
        val store = store(page)
        awaitMain("every valid state") { union.all { store[it] != null } }
        assertTrue("never sent", subs().none { "not-an-id" in ids(it) })
        assertTrue("HA's frontend shows it as not found", store.isMissing("not-an-id"))
        switchTo(page, "kitchen", 1)
        assertTrue("the kitchen's view builds with its state", vitroView(page).currentViewBuilt)
        assertEquals("21.5", store["sensor.kitchen_temp"]?.state)
    }

    @Test
    fun aViewThatFollowsEveryEntityDoesSoOnlyWhileItShows() {
        val page = readyPage()
        awaitMain("both parts") { subs().size == 2 }
        page.load("$base/wall-test/list")
        awaitMain("every entity") { subs().size == 3 }
        assertFalse(subs()[2].has("entity_ids"))
        page.load("$base/wall-test/kitchen")
        awaitMain("the dashboard's again") { subs().size == 5 }
        // The view on screen first, then the rest of the dashboard.
        assertEquals(setOf("sensor.kitchen_temp"), ids(subs()[3]))
        assertEquals(union - "sensor.kitchen_temp", ids(subs()[4]))
    }

    @Test
    fun anIslandsRuntimeReadAddsOnlyThatEntity() {
        val page = readyPage()
        awaitMain("both parts") { subs().size == 2 }
        val watch = page.javaClass.getDeclaredMethod("watchRuntimeEntity", String::class.java).apply { isAccessible = true }
        // A person tile's zone (#935): asked for on its own, never every entity.
        watch.invoke(page, "zone.work")
        watch.invoke(page, "sensor.kitchen_temp") // on another view, followed already
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300))
        awaitMain("the zone") { subs().size == 3 }
        assertEquals(setOf("zone.work"), ids(subs()[2]))
        // A view switch after it asks for nothing.
        page.load("$base/wall-test/kitchen")
        idle()
        assertEquals(3, subs().size)
    }

    @Test
    fun aDashboardAboveTheCapFollowsOneViewAtATime() {
        // Two views whose entities together pass the cap. Conditions name them: cheap to build, all counted.
        fun view(path: String, prefix: String, n: Int, entity: String): JSONObject {
            val conditions = JSONArray()
            for (i in 0 until n) conditions.put(JSONObject().put("condition", "state").put("entity", "sensor.${prefix}_$i").put("state", "on"))
            return JSONObject().put("title", path).put("path", path).put("cards", JSONArray()
                .put(JSONObject().put("type", "tile").put("entity", entity))
                .put(JSONObject().put("type", "conditional").put("conditions", conditions).put("card", JSONObject("""{"type": "markdown", "content": "x"}"""))))
        }
        val big = VitroEngine.MAX_DASHBOARD_ENTITIES
        server.dashboardConfig = JSONObject().put("views", JSONArray()
            .put(view("living", "a", big * 3 / 4, "light.lounge_lamp"))
            .put(view("kitchen", "b", big / 2, "sensor.kitchen_temp")))
        val page = readyPage()
        awaitMain("the view's own") { subs().isNotEmpty() }
        assertEquals(1, subs().size)
        val first = ids(subs()[0])
        assertEquals(big * 3 / 4 + 1, first.size)
        assertTrue("light.lounge_lamp" in first && "sensor.kitchen_temp" !in first)
        page.load("$base/wall-test/kitchen")
        awaitMain("the next view's own, in place of the first") { subs().size == 2 && server.commands("unsubscribe_events").any { it.optInt("subscription") == subs()[0].getInt("id") } }
        val second = ids(subs()[1])
        assertEquals(big / 2 + 1, second.size)
        assertTrue("sensor.kitchen_temp" in second && "light.lounge_lamp" !in second)
    }
}
