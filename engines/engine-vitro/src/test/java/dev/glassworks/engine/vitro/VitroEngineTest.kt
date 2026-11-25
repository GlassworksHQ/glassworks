/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import dev.glassworks.engine.EngineSettings
import dev.glassworks.engine.Page
import dev.glassworks.engine.vitro.cards.WebIslandCardView
import dev.glassworks.engine.vitro.clarity.ClarityHttp
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.data.HaDashboardClient
import dev.glassworks.engine.vitro.model.LovelaceParser
import dev.glassworks.engine.vitro.model.LovelaceResource
import dev.glassworks.engine.vitro.island.VitroIslandServer
import dev.glassworks.engine.vitro.theme.ThemeCatalog
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.VitroCardContext
import dev.glassworks.engine.vitro.ui.VitroView
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Vitro as a Glassworks [dev.glassworks.engine.Engine]: when it is off it is the fallback engine, and when it is
 * on it draws Lovelace itself over a scripted HA ([FakeHaServer]) and opens the fallback only for pages that
 * are not dashboards. Also the Tier 3 island's HTML.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VitroEngineTest {

    private val base = FakeHaServer.BASE
    private val settings = EngineSettings(
        secureHosts = listOf("192.0.2.10"),
        zoom = 1f,
        remoteDebugging = false,
        dashboard = "$base/wall-test/living",
    )

    private lateinit var activity: Activity
    private val host = RecordingHost()
    private val fallback = RecordingEngine()
    private val server = FakeHaServer()
    private val opened = ArrayList<Page>()

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    }

    /** Every engine a test made: their island servers (real loopback listeners) stop in [tearDown]. */
    private val engines = ArrayList<VitroEngine>()

    @After
    fun tearDown() {
        engines.forEach { runCatching { it.islandServer.stop() } }
        // Stop the page's reader thread, then hang up on it, so nothing keeps reconnecting after the test.
        opened.forEach { page ->
            runCatching {
                val f = page.javaClass.getDeclaredField("client")
                f.isAccessible = true
                (f.get(page) as? HaDashboardClient)?.close()
            }
        }
        server.shutdown()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun awaitMain(message: String, cond: () -> Boolean) = awaitCondition(message, tick = { idle() }, cond = cond)

    private fun vitro(
        fb: RecordingEngine = fallback,
        enabled: (Context) -> Boolean = { true },
        onSettings: () -> Unit = {},
        testDashboard: (Context) -> String? = { null },
    ) = VitroEngine(
        fallback = fb,
        settings = { onSettings(); settings },
        enabled = enabled,
        tokenProvider = { _, done -> done(FakeHaServer.TOKEN) },
        socketFactory = server::connect,
        testDashboard = testDashboard,
    ).also { engines += it }

    private fun openPage(engine: VitroEngine): Page =
        engine.open(activity, host, Color.BLACK).also { opened += it; idle() }

    private fun vitroView(page: Page): VitroView = (page.view as ViewGroup).getChildAt(0) as VitroView

    /** A page showing the test dashboard's first view, ready. */
    private fun readyPage(engine: VitroEngine = vitro()): Page {
        val page = openPage(engine)
        page.load("$base/wall-test/living")
        awaitMain("first ready") { host.messages("ready").isNotEmpty() }
        return page
    }

    // ---- Off: the fallback engine, untouched ----

    @Test
    fun disabledOpensTheFallbackPageDirectlyWithTheSameHost() {
        var settingsCalls = 0
        val engine = vitro(enabled = { false }, onSettings = { settingsCalls++ })
        val page = engine.open(activity, host, Color.BLACK)
        idle()

        assertEquals(1, fallback.opens.size)
        assertSame(fallback.pages.single(), page)
        assertSame(activity, fallback.opens[0].activity)
        assertSame(host, fallback.opens[0].host)
        assertEquals(Color.BLACK, fallback.opens[0].background)
        assertEquals("Vitro never reads its settings when it is off", 0, settingsCalls)
        assertTrue("no port was connected: no Vitro page exists", host.ports.isEmpty())
    }

    @Test
    fun disabledDelegatesNameStartedLockedDownAndContentProcesses() {
        val legacy = RecordingEngine(name = "Legacy Gecko 1.0", lockedDown = true)
        val engine = vitro(fb = legacy, enabled = { false })
        assertEquals("Legacy Gecko 1.0", engine.name)
        assertNull(engine.started)
        assertTrue(engine.lockedDown)
        assertTrue(engine.isContentProcess("sh.edm.s1kiosk:tab"))
        assertFalse(engine.isContentProcess("sh.edm.s1kiosk"))

        engine.open(activity, host, Color.BLACK)
        assertNotNull(legacy.started)
        assertSame(legacy.started, engine.started)
        assertEquals("Legacy Gecko 1.0", engine.name)
    }

    @Test
    fun theDecisionToUseVitroIsMadeOnceForTheProcess() {
        var asked = 0
        val engine = vitro(enabled = { asked++ > 0 }) // false the first time, true after
        engine.open(activity, host, Color.BLACK)
        engine.open(activity, host, Color.BLACK)
        assertEquals("a second open() keeps the first answer", 2, fallback.opens.size)
        assertEquals(1, asked)
        assertTrue(host.ports.isEmpty())
    }

    @Test
    fun enabledIsAskedWithTheApplicationContext() {
        var seen: Context? = null
        vitro(enabled = { seen = it; false }).open(activity, host, Color.BLACK)
        assertSame(activity.applicationContext, seen)
    }

    // ---- On: native rendering ----

    @Test
    fun enabledReportsItsOwnNameAndSettingsAfterOpen() {
        val engine = vitro()
        assertEquals("before the first open it is still the fallback's", "FakeGecko 1.0", engine.name)
        assertNull(engine.started)
        openPage(engine)
        assertEquals(VitroEngine.VERSION_NAME, engine.name)
        assertSame(settings, engine.started)
        assertEquals("the content-process question is still the fallback's", true, engine.isContentProcess("x:tab"))
        assertTrue("it never opened the fallback", fallback.opens.isEmpty())
    }

    @Test
    fun enabledConnectsAPortAndRendersWithoutEverOpeningTheFallback() {
        val engine = vitro()
        val page = readyPage(engine)

        assertEquals(1, host.ports.size)
        assertTrue(host.ports[0].topLevel)
        assertEquals("$base/wall-test/living", host.ports[0].url)
        assertTrue("never calls fallback.open(...)", fallback.opens.isEmpty())
        assertTrue(fallback.pages.isEmpty())
        assertEquals(1, (page.view as ViewGroup).childCount) // just Vitro's own view

        val vv = vitroView(page)
        assertNotNull(vv.dashboard)
        assertEquals(listOf("Living", "Wall"), vv.dashboard!!.views.map { it.title })
        assertEquals(0, vv.currentViewIndex)
    }

    @Test
    @Config(qualifiers = "night") // Vitro follows Android's night mode; the server's default dark theme applies in it
    fun enabledReportsReadyDashboardsAndTheHaThemeOverThePort() {
        val engine = vitro()
        readyPage(engine)
        awaitMain("dashboards and theme") { host.messages("dashboards").isNotEmpty() && host.messages("haTheme").isNotEmpty() }

        val url = "$base/wall-test/living"
        assertEquals(listOf(url), host.pageStarts.toList())
        assertEquals(url, host.locations.first())

        val ready = host.messages("ready").single()
        assertEquals("vitro", ready.getString("why"))
        assertEquals(1, host.firstPaints.get())
        assertEquals(listOf(true), host.pageStops.toList())

        val dashboards = JSONArray(host.messages("dashboards").single().getString("json"))
        assertEquals(listOf("wall-test", "admin-only"), (0 until dashboards.length()).map { dashboards.getJSONObject(it).getString("url_path") })
        assertEquals("Wall Test", dashboards.getJSONObject(0).getString("title"))

        // The theme of the view, from the server's default dark theme, for the Glassworks overlays.
        val theme = host.messages("haTheme").first()
        assertTrue(theme.getBoolean("dark"))
        assertEquals("#ff5722", theme.getString("primary"))
        assertEquals("#ff9800", theme.getString("accent"))
        assertEquals("#0a0a14", theme.getString("background"))

        // What the first view cost: two core cards, one mushroom card, no island, three entities.
        val m = engine.lastMetrics
        assertEquals("Living", m.viewTitle)
        assertEquals("sections", m.viewType)
        assertEquals(2, m.nativeCoreCards)
        assertEquals(1, m.nativeHacsCards)
        assertEquals(0, m.webIslandCards)
        assertEquals(3, m.subscribedEntities)
    }

    @Test
    fun theViewsEntitiesAreSubscribedToAlone() {
        readyPage()
        awaitMain("view-scoped subscription") {
            server.commands("subscribe_entities").any { it.has("entity_ids") }
        }
        val scoped = server.commands("subscribe_entities").last { it.has("entity_ids") }.getJSONArray("entity_ids")
        assertEquals(
            setOf("light.lounge_lamp", "sensor.kitchen_temp", "binary_sensor.front_door"),
            (0 until scoped.length()).map { scoped.getString(it) }.toSet(),
        )
    }

    @Test
    fun anEntityAnIslandLooksUpAtRuntimeJoinsTheViewsSubscription() {
        val page = readyPage()
        awaitMain("view-scoped subscription") { server.commands("subscribe_entities").any { it.has("entity_ids") } }
        val before = server.commands("subscribe_entities").size
        val watch = page.javaClass.getDeclaredMethod("watchRuntimeEntity", String::class.java).apply { isAccessible = true }
        watch.invoke(page, "sensor.outdoor_temp")
        watch.invoke(page, "sensor.indoor_temp") // batched with the first: one resubscription
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300))
        awaitMain("resubscribed with the runtime entities") { server.commands("subscribe_entities").size == before + 1 }
        val ids = server.commands("subscribe_entities").last().getJSONArray("entity_ids")
        assertEquals(
            setOf("light.lounge_lamp", "sensor.kitchen_temp", "binary_sensor.front_door", "sensor.outdoor_temp", "sensor.indoor_temp"),
            (0 until ids.length()).map { ids.getString(it) }.toSet(),
        )
    }

    /** #838: an island that walked `hass.states` makes its view follow every entity, when shown again from the cache too. */
    @Test
    fun aViewWhoseIslandWalksTheStatesFollowsEveryEntity() {
        val page = readyPage()
        awaitMain("view-scoped subscription") { server.commands("subscribe_entities").any { it.has("entity_ids") } }
        val followAll = page.javaClass.getDeclaredMethod("followAllForIsland", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val gen = page.javaClass.getDeclaredField("walkGeneration").apply { isAccessible = true }.getInt(page)
        val before = server.commands("subscribe_entities").size
        followAll.invoke(page, 0, gen - 1) // an island of an earlier dashboard config, reporting late: nothing
        idle()
        assertEquals(before, server.commands("subscribe_entities").size)
        followAll.invoke(page, 0, gen)
        followAll.invoke(page, 0, gen) // once is enough
        awaitMain("every entity") { !server.commands("subscribe_entities").last().has("entity_ids") }
        val all = server.commands("subscribe_entities").size

        // Another view is scoped again; back on this one, it follows every entity without being asked again.
        page.load("$base/wall-test/wall")
        awaitMain("the wall view's own") { server.commands("subscribe_entities").size > all }
        page.load("$base/wall-test/living")
        awaitMain("every entity again") { !server.commands("subscribe_entities").last().has("entity_ids") }
    }

    /** #838: every island shares one registry feed on the client, started by the first and ended by the last. */
    @Test
    fun islandsShareOneRegistryFeed() {
        server.floorRegistry = org.json.JSONArray("""[{"floor_id": "ground", "name": "Ground floor"}]""")
        val page = readyPage()
        val watch = page.javaClass.getDeclaredMethod("watchIslandRegistries", Function1::class.java).apply { isAccessible = true }
        val a = CopyOnWriteArrayList<JSONObject>()
        val b = CopyOnWriteArrayList<JSONObject>()
        @Suppress("UNCHECKED_CAST")
        val releaseA = watch.invoke(page, { set: JSONObject -> a += set }) as () -> Unit
        awaitMain("the first island's registries") { a.isNotEmpty() }
        @Suppress("UNCHECKED_CAST")
        val releaseB = watch.invoke(page, { set: JSONObject -> b += set }) as () -> Unit
        awaitMain("the second island's, from the same feed") { b.isNotEmpty() }
        assertEquals("ground", b.single().getJSONObject("floors").getJSONObject("ground").getString("floor_id"))
        assertEquals(1, server.commands("config/floor_registry/list").size)
        assertEquals(1, server.received.count { it.optString("type") == "subscribe_events" && it.optString("event_type") == "floor_registry_updated" })

        val floorSub = server.eventSubscriptionId("floor_registry_updated")!!
        fun cancelled() = server.commands("unsubscribe_events").any { it.getInt("subscription") == floorSub }
        releaseA()
        idle()
        Thread.sleep(100)
        assertFalse(cancelled())
        releaseB()
        awaitMain("the feed ends with the last island") { cancelled() }
    }

    @Test
    fun aViewlessUrlAnnouncesTheFirstViewsAddress() {
        val page = openPage(vitro())
        page.load("$base/wall-test")
        awaitMain("ready") { host.messages("ready").isNotEmpty() }
        assertTrue(host.locations.toString(), host.locations.contains("$base/wall-test/living"))
    }

    @Test
    fun switchingToAnotherViewOfTheSameDashboardIsInstantAndOffline() {
        val engine = vitro()
        val page = readyPage(engine)

        page.load("$base/wall-test/wall")
        // Synchronous: no request, no waiting on the socket.
        val ready = host.messages("ready")
        assertEquals(2, ready.size)
        assertEquals("view", ready[1].getString("why"))
        assertEquals("Wall", engine.lastMetrics.viewTitle)
        assertEquals("panel", engine.lastMetrics.viewType)
        assertEquals("the unknown custom card is the one island", 1, engine.lastMetrics.webIslandCards)
        assertEquals(1, vitroView(page).currentViewIndex)
        assertEquals(1, server.commands("lovelace/config").size)
        assertEquals(1, server.connectUrls.size)
        assertEquals(1, host.firstPaints.get())
        assertTrue(host.pageStarts.contains("$base/wall-test/wall"))
        // The Tier 3 custom card opens one GeckoView Micro-Island on the loopback server (never HA's SPA).
        assertEquals(1, fallback.opens.size)
        val islandLoad = fallback.pages.single().loads.single()
        assertTrue("expected loopback island URL, got $islandLoad", "/__vitro/island/" in islandLoad)
    }

    @Test
    fun anotherDashboardOnTheSameOriginRefetchesOverTheSameSocket() {
        val page = readyPage()
        page.load("$base/clarity-ref/typical")
        awaitMain("second config") { server.commands("lovelace/config").size == 2 }
        assertEquals("clarity-ref", server.commands("lovelace/config")[1].getString("url_path"))
        assertEquals(1, server.connectUrls.size)
        assertTrue(fallback.opens.isEmpty())
    }

    @Test
    fun clarityMeasuresOnlyTheReferenceDashboardOnTheTestHoldsOrigin() {
        // docs/perf.md "Vitro": the native harness runs only while the test hold shows Clarity's reference dashboard.
        var test: String? = null
        // The harness's own requests go nowhere: a fake transport that records them and answers 404 (its worker keeps
        // asking for the config), so the test touches no network.
        val asked = CopyOnWriteArrayList<String>()
        val engine = vitro(testDashboard = { test }).also { it.clarityHttp = ClarityHttp { url, _ -> asked += url; 404 to null } }
        val page = readyPage(engine)
        val view = vitroView(page)
        assertNull("no test hold: nothing measures", view.clarity)
        page.load("$base/clarity-ref/start")
        assertNull("the reference dashboard alone isn't a Clarity run", view.clarity)
        test = "$base/clarity-ref/start"
        page.load("$base/clarity-ref/typical")
        val harness = view.clarity
        assertNotNull(harness)
        assertEquals(base, harness!!.fake)
        page.load("$base/clarity-ref/light")
        assertSame("one harness for the run's views", harness, view.clarity)
        page.load("$base/wall-test/living")
        assertNull("another dashboard: the harness stops", view.clarity)
        test = "http://127.0.0.1:8089/clarity-ref/start"
        page.load("$base/clarity-ref/start")
        assertNull("the test hold's dashboard is on another origin", view.clarity)
        // The test hold moved to another fake (another port): a new harness polls that one, never the old.
        test = "$base/clarity-ref/start"
        page.load("$base/clarity-ref/start")
        val first = view.clarity!!
        val moved = "http://192.0.2.20:8123"
        test = "$moved/clarity-ref/start"
        page.load("$moved/clarity-ref/start")
        val second = view.clarity
        assertNotNull(second)
        assertEquals(moved, second!!.fake)
        assertTrue("the old harness was replaced", first !== second)
        awaitMain("the harness asks its fake for the config") { asked.any { it.startsWith("$moved/clarity/config.json") } }
        // The hold ends with no page load after it (a panel with no saved dashboard): the harness stops by itself.
        test = null
        awaitMain("the harness stopped when the hold ended") { view.clarity == null }
        assertTrue("only the fakes were asked: $asked", asked.all { it.startsWith("$base/clarity/") || it.startsWith("$moved/clarity/") })
    }

    /** The main looper with its clock moving (the harness checks the hold every 500 ms with postDelayed). */
    private fun awaitClock(message: String, cond: () -> Boolean) =
        awaitCondition(message, tick = { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100)) }, cond = cond)

    private fun clarityLines() = org.robolectric.shadows.ShadowLog.getLogsForTag("Glassworks.Clarity").map { it.msg }

    @Test
    fun aHoldThatEndsStopsTheHarnessWithItsConfigInFlightAndMidRun() {
        // CodeRabbit on #835: the hold's end must cancel what is under way, not only what comes next, with no page load.
        var test: String? = "$base/clarity-ref/start"
        val asked = CopyOnWriteArrayList<String>()
        val release = java.util.concurrent.CountDownLatch(1)
        val holdConfig = java.util.concurrent.atomic.AtomicBoolean(true)
        val engine = vitro(testDashboard = { test }).also {
            it.clarityHttp = ClarityHttp { url, _ ->
                asked += url
                if (url.endsWith("/clarity/config.json")) {
                    // The first: held until the test lets it go. The second: a moment late, so the test stops idling
                    // the main thread before the harness's next step is queued there.
                    if (holdConfig.get()) release.await(10, java.util.concurrent.TimeUnit.SECONDS) else Thread.sleep(300)
                    200 to """{"VIEWS":["start","light"],"HEAD_CARDS":10}"""
                } else {
                    204 to null
                }
            }
        }
        val page = openPage(engine)
        org.robolectric.shadows.ShadowLog.clear()
        page.load("$base/clarity-ref/start")
        val view = vitroView(page)

        // 1. The config request is in flight when the hold ends; it then answers 200: nothing starts.
        val first = view.clarity!!
        awaitClock("the config asked for") { asked.any { it.endsWith("/clarity/config.json") } }
        test = null
        awaitClock("the harness stopped with the hold") { view.clarity == null }
        release.countDown()
        awaitClock("its worker ended after the config came") { !first.workerAlive }

        // 2. A harness mid-run (its config read, its next step queued for the main thread) when the hold ends: the
        // watcher's own check, on the main thread, ends it, and the queued step never runs. (Robolectric's paused looper
        // runs a frame callback that asks for the next one again at once, so a ticking harness can't be idled here.)
        holdConfig.set(false)
        test = "$base/clarity-ref/start"
        page.load("$base/clarity-ref/light")
        val second = view.clarity!!
        assertTrue(first !== second)
        // Idled until the second config is asked (its hold check runs on the main thread), then not again.
        awaitMain("the second config asked for") { asked.count { it.endsWith("/clarity/config.json") } >= 2 }
        Thread.sleep(600)
        assertTrue("mid-run: waiting on the main thread", second.workerAlive)
        test = null
        assertFalse("the hold's end is seen", second.checkHold())
        assertNull("and the page let it go", view.clarity)
        awaitCondition("its worker ended mid-run") { !second.workerAlive }
        idle()

        assertEquals("nothing measured or logged after the hold ended", emptyList<String>(), clarityLines())
        assertFalse("no command asked for", asked.any { "/clarity/next" in it })
    }

    @Test
    fun aNewOriginStartsANewConnection() {
        val page = readyPage()
        page.load("http://192.0.2.20:8123/wall-test/living")
        awaitMain("second connection") { server.connectUrls.size == 2 }
        assertEquals(
            listOf("ws://192.0.2.10:8123/api/websocket", "ws://192.0.2.20:8123/api/websocket"),
            server.connectUrls.toList(),
        )
        assertTrue(fallback.opens.isEmpty())
    }

    // ---- On: pages that are not dashboards ----

    @Test
    fun aViewStillWaitingForItsStatesSignalsNothingOnceTheFallbackHasThePage() {
        // No states ever come: the first build waits its whole second.
        server.initialEntities = JSONObject()
        val page = openPage(vitro())
        page.load("$base/wall-test/living")
        awaitMain("dashboard shown") { vitroView(page).dashboard != null }
        assertTrue("not ready while the view waits", host.messages("ready").isEmpty())
        // The fallback takes the page during the wait; the wait's build then happens behind it and says nothing.
        page.load("$base/auth/authorize?client_id=abc&redirect_uri=def")
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1_500))
        assertTrue("no ready behind the fallback", host.messages("ready").isEmpty())
        assertTrue(host.pageStops.isEmpty())
        // Back to the dashboard: its view is built, so it's ready at once.
        page.load("$base/wall-test/living")
        assertEquals("view", host.messages("ready").single().getString("why"))
    }

    @Test
    fun aDisposedPageBuildsNothingWhenItsWaitEnds() {
        server.initialEntities = JSONObject()
        val engine = vitro()
        val page = openPage(engine)
        page.load("$base/wall-test/living")
        awaitMain("dashboard shown") { vitroView(page).dashboard != null }
        assertFalse(vitroView(page).currentViewBuilt)
        // A new page replaces it (the old one is disposed) while its view waits for states.
        openPage(engine)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1_500))
        assertFalse("the wait was cancelled: nothing built for a page that's gone", vitroView(page).currentViewBuilt)
    }

    @Test
    fun aDashboardWithNoViewsIsReady() {
        server.dashboardConfig = JSONObject("""{"views": []}""")
        val page = openPage(vitro())
        page.load("$base/wall-test/living")
        awaitMain("ready") { host.messages("ready").isNotEmpty() }
        assertEquals("vitro", host.messages("ready").single().getString("why"))
        assertEquals(listOf(true), host.pageStops.toList())
        fun all(v: View): List<View> = listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())
        assertFalse(
            "no \"Connecting…\" left on a ready page",
            all(vitroView(page)).any { it is TextView && it.isShown && it.text.startsWith("Connecting") },
        )
    }

    @Test
    fun onlyANonDashboardUrlOpensTheFallbackAndItIsReusedAfterwards() {
        val page = readyPage()
        assertTrue("a dashboard alone never opens the fallback", fallback.opens.isEmpty())
        val vv = vitroView(page)

        val signIn = "$base/auth/authorize?client_id=abc&redirect_uri=def"
        page.load(signIn)
        assertEquals(1, fallback.opens.size)
        assertSame(activity, fallback.opens[0].activity)
        val fp = fallback.pages.single()
        assertEquals(listOf(signIn), fp.loads.toList())
        assertEquals(View.VISIBLE, fp.view.visibility)
        assertEquals(View.GONE, vv.visibility)
        assertEquals(2, (page.view as ViewGroup).childCount)
        assertFalse("the fallback reports its own page starts", host.pageStarts.contains(signIn))

        // A second non-dashboard page reuses the one fallback page.
        val blueprint = "$base/config/blueprint/import"
        page.load(blueprint)
        assertEquals(1, fallback.opens.size)
        assertEquals(listOf(signIn, blueprint), fp.loads.toList())
        assertEquals("the second page starts with no history", 1, fp.restarts)

        // Back to the native dashboard view: Vitro's view returns without reloading or opening another fallback.
        page.load("$base/wall-test/living")
        assertEquals(View.VISIBLE, vv.visibility)
        assertEquals(View.GONE, fp.view.visibility)
        assertEquals(2, fp.loads.size)
        assertEquals("view", host.messages("ready").last().getString("why"))
        assertEquals(1, fallback.opens.size)
    }

    /**
     * The Mirror's legacy engine under Vitro (2026-11-25): HA's pages sat on "Connecting" through the loopback proxy (no
     * WebSocket, no external auth, integrations' paths refused). They load from Home Assistant itself now, as with Vitro
     * off; the loopback origin is only the islands' ([VitroEngine.islandOrigin]).
     */
    @Test
    fun aLockedDownFallbackLoadsHomeAssistantsPagesFromHomeAssistant() {
        val legacy = RecordingEngine(name = "Legacy Gecko 1.0", lockedDown = true)
        val engine = vitro(fb = legacy)
        try {
            val page = readyPage(engine)
            val loopback = engine.islandOrigin()
            assertTrue(loopback, loopback.startsWith("http://127.0.0.1:"))

            val pages = listOf(
                "$base/auth/authorize?client_id=abc&redirect_uri=def", "$base/history", "$base/logbook",
                "$base/profile/general", "$base/config/dashboard", "https://example.com/config/blueprint/import",
            )
            pages.forEach(page::load)
            assertEquals("as asked, never through the loopback server", pages, legacy.pages.single().loads.toList())
            assertTrue(legacy.pages.single().loads.none { it.startsWith(loopback) })
        } finally {
            engine.islandServer.stop()
        }
    }

    /**
     * The Mirror's Android 6 doesn't hide a SurfaceView when an ancestor goes GONE: an island's GeckoView drew over the
     * fallback page. Its surfaces go while the fallback shows a page, and come back with the dashboard.
     */
    @Test
    fun theIslandsSurfacesHideWhileTheFallbackShowsAPage() {
        val page = readyPage()
        val vv = vitroView(page)
        val island = android.widget.FrameLayout(vv.context)
        val surface = android.view.SurfaceView(vv.context)
        val alreadyGone = android.view.SurfaceView(vv.context).apply { visibility = View.GONE }
        island.addView(surface)
        island.addView(alreadyGone)
        vv.addView(island)

        page.load("$base/history")
        assertEquals(View.INVISIBLE, surface.visibility)
        assertEquals("one hidden by its own card stays as it was", View.GONE, alreadyGone.visibility)
        page.load("$base/logbook")
        assertEquals(View.INVISIBLE, surface.visibility)

        page.load("$base/wall-test/living")
        assertEquals(View.VISIBLE, vv.visibility)
        assertEquals(View.VISIBLE, surface.visibility)
        assertEquals(View.GONE, alreadyGone.visibility)
        vv.removeView(island)
    }

    @Test
    fun aNonDashboardFirstLoadNeverStartsTheDataPlane() {
        val page = openPage(vitro())
        page.load("$base/auth/authorize?client_id=abc")
        assertEquals(1, fallback.opens.size)
        Thread.sleep(150)
        idle()
        assertTrue(server.connectUrls.isEmpty())
        assertTrue(host.messages("ready").isEmpty())
    }

    @Test
    fun theFallbackPageGetsBackWithinItsHistoryAndPostsWhileItIsShowing() {
        // Reload and Back past its own history return to the native view (VitroFallbackReturnTest).
        val page = readyPage()
        page.load("$base/config/dashboard")
        val fp = fallback.pages.single()

        fallback.pages.single().canGoBack = true
        page.goBack()
        val msg = JSONObject().put("type", "config").put("config", JSONObject().put("hideHeader", false))
        page.post(msg)
        assertEquals(1, fp.backs)
        assertEquals(listOf(msg), fp.posted.toList())
    }

    @Test
    fun anAddressTheHostRedirectsIsLoadedInsteadAndAboutBlankIsDropped() {
        val page = openPage(vitro())
        host.redirectTo = { url ->
            when {
                url.endsWith("/old") -> "$base/wall-test/living"
                url == "$base/blank" -> "about:blank"
                else -> null
            }
        }
        page.load("$base/blank")
        assertTrue(host.pageStarts.isEmpty())
        assertTrue(fallback.opens.isEmpty())

        page.load("$base/old")
        awaitMain("ready via redirect") { host.messages("ready").isNotEmpty() }
        assertEquals(listOf("$base/wall-test/living"), host.pageStarts.toList())
    }

    // ---- On: sign-in and failures ----

    @Test
    fun aRejectedTokenHandsOverToTheSignInAddress() {
        server.acceptedTokens = emptySet()
        val page = openPage(vitro())
        page.load("$base/wall-test/living")
        awaitMain("sign-in handoff") { host.locations.contains("$base/auth/authorize") }
        assertTrue(host.messages("ready").isEmpty())
        assertTrue("the host decides what shows the sign-in", fallback.opens.isEmpty())
    }

    @Test
    fun anUnreachableServerStopsThePageAsFailed() {
        server.refuseConnections = true
        val page = openPage(vitro())
        page.load("$base/wall-test/living")
        awaitMain("failed stop") { host.pageStops.contains(false) }
        assertTrue(host.messages("ready").isEmpty())
        assertEquals(0, host.firstPaints.get())
    }

    @Test
    fun aLostConnectionAfterTheFallbackTookOverDoesNotFailItsPage() {
        // The dashboard's connection fails only after the page moved on to a fallback page: that page isn't failed.
        val attempts = java.util.concurrent.atomic.AtomicInteger(0)
        val engine = VitroEngine(
            fallback = fallback,
            settings = { settings },
            enabled = { true },
            tokenProvider = { _, done -> done(FakeHaServer.TOKEN) },
            socketFactory = { attempts.incrementAndGet(); throw java.io.IOException("Connection refused") },
        ).also { engines += it }
        val page = openPage(engine)
        page.load("$base/wall-test/living")
        page.load("$base/config/blueprint/import") // before the main thread hears of the failure
        awaitMain("the connection was tried") { attempts.get() >= 1 }
        Thread.sleep(100)
        idle()
        assertFalse(host.pageStops.toString(), host.pageStops.contains(false))
    }

    @Test
    fun aNavigationQueuedBeforeTheActivityWentAwayStartsNothing() {
        val page = openPage(vitro())
        page.javaClass.getDeclaredMethod("dispose").apply { isAccessible = true }.invoke(page)
        page.load("$base/wall-test/living")
        idle()
        assertTrue("no connection for a disposed page", server.connectUrls.isEmpty())
        val client = page.javaClass.getDeclaredField("client").apply { isAccessible = true }.get(page)
        assertNull(client)
    }

    // ---- Messages from the host and Page API ----

    @Test
    fun hostMessagesConfigureTheHeaderAndNavigate() {
        val page = readyPage()
        val vv = vitroView(page)

        page.post(JSONObject().put("type", "config").put("config", JSONObject().put("hideHeader", false)))
        idle()
        assertFalse(vv.hideHeader)

        // The port's own post() takes the same messages (the web extension side).
        host.ports[0].post(JSONObject().put("type", "navigate").put("url", "$base/wall-test/wall"))
        idle()
        assertEquals(1, vv.currentViewIndex)
        assertEquals("view", host.messages("ready").last().getString("why"))

        host.ports[0].post(JSONObject().put("type", "unknown"))
        host.ports[0].post(JSONObject().put("type", "navigate")) // no url
        idle()
        assertEquals(1, vv.currentViewIndex)
    }

    @Test
    fun reloadFetchesTheDashboardAgain() {
        val page = readyPage()
        page.reload()
        awaitMain("refetch") { server.commands("lovelace/config").size == 2 }
        assertEquals(1, server.connectUrls.size)
    }

    @Test
    fun goBackReturnsToThePreviousViewAddress() {
        val page = readyPage()
        page.load("$base/wall-test/wall")
        host.pageStarts.clear()

        page.goBack()
        assertEquals(listOf("$base/wall-test/living"), host.pageStarts.toList())
        assertEquals(0, vitroView(page).currentViewIndex)
    }

    @Test
    fun logLinesLeaveOutQueriesAndProxiedPaths() {
        assertEquals("http://…/auth/…?…", VitroEngine.forLog("http://192.0.2.10:8123/auth/authorize?client_id=a&state=secret"))
        // No Home Assistant address either: an origin, a host or an IP a connection error names.
        assertEquals("Unable to resolve host \"…\": No address", VitroEngine.forLog("Unable to resolve host \"homeassistant.local\": No address"))
        assertEquals("failed to connect to …/… (port 8123)", VitroEngine.forLog("failed to connect to ha.example/192.0.2.10 (port 8123)"))
        assertEquals("connect to … refused", VitroEngine.forLog("connect to fd00::12:8123 refused"))
        assertEquals("Closed by Home Assistant", VitroEngine.forLog("Closed by Home Assistant"))
        assertEquals("Starting Vitro 0.0.1", VitroEngine.forLog("Starting Vitro 0.0.1"))
        assertEquals("[3] GET /api/… -> 200", VitroEngine.forLog("[3] GET /api/states/sensor.private_thing -> 200"))
        assertEquals("GET /__vitro/island/… -> 200", VitroEngine.forLog("GET /__vitro/island/s0123abcd -> 200"))
        assertEquals("Connection reset", VitroEngine.forLog("Connection reset"))
    }

    @Test
    fun backFollowsTabTapsAndLoadsInTheOrderTheyHappened() {
        val page = readyPage()
        val vv = vitroView(page)
        // A tab tap after the load: back returns to the tab's previous view first, not the older load.
        val wallTab = (0 until vv.childCount).asSequence().flatMap { vv.getChildAt(it).let { c -> if (c is android.view.ViewGroup) c.allViews() else sequenceOf(c) } }
            .first { it.tag == 1 && it.isClickable }
        wallTab.performClick()
        idle()
        assertEquals(1, vv.currentViewIndex)
        page.goBack()
        idle()
        assertEquals(0, vv.currentViewIndex)
        // A reload keeps the tab now shown, not the one the route was loaded with.
        wallTab.performClick()
        idle()
        val readies = host.messages("ready").size
        page.reload()
        awaitMain("the reloaded config is on screen") { host.messages("ready").size > readies }
        assertEquals(1, vv.currentViewIndex)
    }

    private fun android.view.ViewGroup.allViews(): Sequence<View> = sequence {
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            yield(c)
            if (c is android.view.ViewGroup) yieldAll(c.allViews())
        }
    }

    @Test
    fun captureWithoutALayoutGivesNull() {
        val page = readyPage()
        var called = false
        var bitmap: Bitmap? = null
        page.capture { called = true; bitmap = it }
        idle()
        assertTrue(called)
        assertNull(bitmap) // the view has no size here
    }

    @Test
    fun restartReconnectsToTheCurrentAddress() {
        val page = readyPage()
        page.restart()
        awaitMain("second connection") { server.commands("auth").size == 2 }
        assertEquals(2, server.connectUrls.size)
    }

    @Test
    fun viewAddressesJoinOriginDashboardAndView() {
        assertEquals("$base/wall-test/living", VitroEngine.buildViewUrl("$base/", "wall-test", "living"))
        assertEquals("$base/lovelace/0", VitroEngine.buildViewUrl(base, null, "0"))
        assertEquals("$base/lovelace/0", VitroEngine.buildViewUrl(base, "  ", "0"))
        assertNull(VitroEngine.buildViewUrl(null, "wall-test", "living"))
        assertNull(VitroEngine.buildViewUrl("", "wall-test", "living"))
        // And the parser reads it back.
        assertEquals("wall-test" to "living", LovelaceParser.parseRoute(VitroEngine.buildViewUrl(base, "wall-test", "living")!!))
    }

    // ---- WebIslandCardView ----

    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))

    private fun cardContext(
        dark: Boolean = true,
        resources: List<LovelaceResource> = emptyList(),
        token: String? = FakeHaServer.TOKEN,
        navigate: (String) -> Unit = {},
        store: EntityStore = EntityStore(),
    ) = VitroCardContext(
        context = activity,
        haBase = base,
        store = store,
        theme = ThemeResolver.resolve(ThemeCatalog(), dark),
        resources = resources,
        accessToken = { token },
        haConfig = { JSONObject().put("location_name", "Test Home") },
        callService = { _, _, _, _ -> },
        navigate = navigate,
        openMoreInfo = {},
        fetchHistory = { _, _, _ -> },
        subscribeTemplate = { _, _ -> {} },
    )

    private fun islandCard() = LovelaceParser.parseCard(
        JSONObject("""{"type": "custom:acme-floorplan-card", "rooms": [{"name": "Lounge", "lights": ["light.lounge_lamp"]}]}"""),
    )

    @Test
    fun decodeInlineModuleReadsABase64DataUri() {
        val src = "export const hi = () => 'héllo ✓';"
        assertEquals(src, WebIslandCardView.decodeInlineModule("data:text/javascript;base64," + b64(src)))
        assertEquals(src, WebIslandCardView.decodeInlineModule("data:text/javascript;charset=utf-8;base64," + b64(src)))
        // The scheme and the base64 marker are case-insensitive.
        assertEquals(src, WebIslandCardView.decodeInlineModule("DATA:text/javascript;BASE64," + b64(src)))
        // A base64 body wrapped over several lines still decodes.
        assertEquals(src, WebIslandCardView.decodeInlineModule("data:text/javascript;base64," + b64(src).chunked(8).joinToString("\n")))
    }

    @Test
    fun decodeInlineModuleReadsAPercentEncodedDataUri() {
        assertEquals("console.log('hi');", WebIslandCardView.decodeInlineModule("data:text/javascript,console.log(%27hi%27)%3B"))
    }

    @Test
    fun decodeInlineModuleIgnoresNormalUrlsAndBrokenOnes() {
        assertNull(WebIslandCardView.decodeInlineModule("/hacsfiles/acme/acme-card.js"))
        assertNull(WebIslandCardView.decodeInlineModule("$base/local/card.js"))
        assertNull(WebIslandCardView.decodeInlineModule(""))
        assertNull(WebIslandCardView.decodeInlineModule("data:text/javascript;base64")) // no comma
        assertNull(WebIslandCardView.decodeInlineModule("data:text/javascript;base64,@@@@")) // not base64
    }

    @Test
    fun islandHtmlCarriesTheThemeTheBridgeAndTheConfig() {
        val html = WebIslandCardView.buildIslandHtml(cardContext(), islandCard())

        assertTrue(html.startsWith("<!DOCTYPE html>"))
        // The resolved theme as CSS variables, so a web card matches the native ones.
        assertTrue(html.contains(":root {"))
        assertTrue(html.contains("--primary-color: #009ac7;"))
        assertTrue(html.contains("--primary-background-color: #111111;"))
        assertTrue(html.contains("--rgb-primary-color: 0, 154, 199;"))
        // hass.callWS goes over the loopback WebSocket bridge, and states are watched through a proxy.
        assertTrue(html.contains("callWS(msg)"))
        assertTrue(html.contains("sendBridge({ type: 'callWs', id, msg: msg || {} }, id)"))
        // A call that can't go out soon is rejected and leaves the queue, never sent much later.
        assertTrue(html.contains("reject(new Error('Not connected'));"))
        assertTrue(html.contains("const QUEUED_CALL_MS = 5000;"))
        assertTrue(html.contains("const statesProxy = new Proxy(rawStates"))
        assertTrue(html.contains("sendBridge({ type: 'watch', entity_id: prop })"))
        // hass.callService is HA's call_service over the same bridge, so its promise carries HA's answer.
        assertTrue(html.contains("const msg = { type: 'call_service', domain, service, service_data: data || {} };"))
        // A call that went out on a socket that closed is rejected, not left hanging.
        assertTrue(html.contains("entry.reject(new Error('Connection lost'))"))
        // An action with a confirmation is asked natively.
        assertTrue(html.contains("sendBridge({ type: 'action', action, entity: entity || '' })"))
        assertTrue(html.contains("/__vitro/ws/"))
        assertTrue(html.contains("m.type === 'patch'"))
        assertTrue(html.contains("m.type === 'wsReply'"))
        assertTrue(html.contains("sendBridge({ type: 'moreInfo', entity_id: id })"))
        assertTrue(html.contains("sendBridge({ type: 'resize', height: h })"))
        // The card element, its config, hass.config and the token.
        assertTrue(html.contains("const tag = \"acme-floorplan-card\";"))
        assertTrue(html.contains("const cfg = {"))
        assertTrue(html.contains("\"rooms\""))
        assertTrue(html.contains("const haConfig = {\"location_name\":\"Test Home\"};"))
        assertTrue(html.contains("const accessToken = \"test-token\";"))
        assertTrue(html.contains("themes: { darkMode: true, default_theme: "))
        // The HA shims, instead of HA's frontend.
        assertTrue(html.contains("customElements.define('ha-card'"))
        assertTrue(html.contains("customElements.define('ha-icon'"))
        assertTrue(html.contains("window.loadCardHelpers"))
    }

    @Test
    fun islandHtmlFollowsTheLightTheme() {
        val html = WebIslandCardView.buildIslandHtml(cardContext(dark = false), islandCard())
        assertTrue(html.contains("--primary-background-color: #fafafa;"))
        assertTrue(html.contains("themes: { darkMode: false, default_theme: "))
    }

    @Test
    fun islandHtmlWithoutATokenHasAnEmptyOne() {
        val html = WebIslandCardView.buildIslandHtml(cardContext(token = null), islandCard())
        assertTrue(html.contains("const accessToken = \"\";"))
    }

    @Test
    fun islandHtmlInlinesDecodedModulesAndEscapesAScriptEnd() {
        val inline = "console.log(\"</script>\"); export const marker = \"inline-island-test\";"
        val resources = listOf(
            LovelaceResource("data:text/javascript;base64," + b64(inline), "module"),
            LovelaceResource("/local/island.css", "css"),
            LovelaceResource("/hacsfiles/acme/acme-card.js", "module"),
            LovelaceResource("/local/legacy.js", "js"),
        )
        val html = WebIslandCardView.buildIslandHtml(cardContext(resources = resources), islandCard())

        // The decoded module is in the page, in its own module script.
        assertTrue(html.contains("<script type=\"module\">\nconsole.log("))
        assertTrue(html.contains("export const marker = \"inline-island-test\";"))
        assertFalse("the base64 itself is not left in the page", html.contains(b64(inline)))
        // A `</script>` inside it cannot end the script early.
        assertTrue(html.contains("console.log(\"<\\/script>\")"))
        assertFalse(html.contains("console.log(\"</script>\")"))
        // Normal resources keep their kind.
        assertTrue(html.contains("rel=\"stylesheet\" href="))
        assertTrue(html.contains("island.css"))
        assertTrue(html.contains("<script type=\"module\" src="))
        assertTrue(html.contains("acme-card.js"))
        assertTrue(html.contains("<script src="))
        assertTrue(html.contains("legacy.js"))
    }

    @Test
    fun islandResourceUrlsAreHtmlAttributesNotJsonStrings() {
        val resources = listOf(
            LovelaceResource("/local/custom-card.js", "module"),
            LovelaceResource("/local/legacy.js", "js"),
            LovelaceResource("/local/a.css?v=1&b=\"x\"", "css"),
        )
        val html = WebIslandCardView.buildIslandHtml(cardContext(resources = resources), islandCard())
        assertTrue(html.contains("<script type=\"module\" src=\"/local/custom-card.js\"></script>"))
        assertTrue(html.contains("<script src=\"/local/legacy.js\"></script>"))
        assertTrue(html.contains("href=\"/local/a.css?v=1&amp;b=&quot;x&quot;\""))
        assertFalse(html.contains("\\/local\\/"))
    }

    @Test
    fun aFullViewportIslandFillsTheScreen() {
        val full = WebIslandCardView.buildIslandHtml(cardContext(), islandCard(), fillViewport = true)
        assertTrue(full.contains("height: 100vh; width: 100vw;"))
        assertTrue(full.contains("#mount { width: 100%; height: 100vh; }"))
        val inline = WebIslandCardView.buildIslandHtml(cardContext(), islandCard(), fillViewport = false)
        assertFalse(inline.contains("100vh"))
        assertFalse(inline.contains("100vw"))
    }

    /**
     * #839: a panel card sized as `calc(100vh - var(--header-height))` filled the screen but for 56 px, over white. In
     * the kiosk's HA the header is hidden and its height 0 (the GeckoView extension's `--header-height: 0px`), and the
     * view's background shows under a short card.
     */
    @Test
    fun anIslandHasNoHeaderHeightAndAFullViewportOneTheViewsBackground() {
        for (fill in listOf(true, false)) {
            val html = WebIslandCardView.buildIslandHtml(cardContext(), islandCard(), fillViewport = fill)
            val root = html.indexOf(":root { --header-height: 0px; }")
            assertTrue("fill=$fill", root > 0)
            // After the theme's own variables, so a theme that sets one doesn't put the strip back.
            assertTrue(root > html.indexOf("--primary-background-color: #111111;"))
        }
        val full = WebIslandCardView.buildIslandHtml(cardContext(), islandCard(), fillViewport = true)
        val bg = full.indexOf("html { background-color: #111111; }")
        assertTrue(bg > 0)
        // After the page's `background: transparent`, which it would otherwise lose to.
        assertTrue(bg > full.indexOf("html, body { margin: 0; padding: 0; background: transparent;"))
        assertTrue(WebIslandCardView.buildIslandHtml(cardContext(dark = false), islandCard(), fillViewport = true)
            .contains("html { background-color: #fafafa; }"))
        val inline = WebIslandCardView.buildIslandHtml(cardContext(), islandCard(), fillViewport = false)
        assertFalse("an inline island sits in a card", inline.contains("html { background-color"))
    }

    @Test
    fun anIslandViewHoldsOneChildAndSurvivesWithoutAWebView() {
        val island = WebIslandCardView(cardContext(), islandCard(), fillViewport = true)
        assertEquals(1, island.childCount)
        val child = island.getChildAt(0)
        // No island server or page opener in this fixture: the note naming the card.
        assertTrue(child is TextView && child.text.toString().contains("custom:acme-floorplan-card"))
    }

    @Test
    fun islandHtmlCarriesTheCustomCardRuntime() {
        val html = WebIslandCardView.buildIslandHtml(cardContext(), islandCard())
        // loadCardHelpers for stack-in-card, auto-entities, mod-card and friends.
        assertTrue(html.contains("window.loadCardHelpers = () => Promise.resolve({"))
        assertTrue(html.contains("createCardElement: vitroCreateCardElement"))
        assertTrue(html.contains("createHuiElement: (c) => vitroCreateCardElement(c)"))
        assertTrue(html.contains("importMoreInfoControl: () => Promise.resolve()"))
        assertTrue(html.contains("customElements.whenDefined(t)"))
        // The hass surface real cards read.
        assertTrue(html.contains("callApi: vitroCallApi"))
        assertTrue(html.contains("fetch('/api/' + String(path || '').replace(/^\\/+/, ''), init)"))
        assertTrue(html.contains("fetchWithAuth(path, init) { return fetch(path, init || {}); }"))
        assertTrue(html.contains("localize: vitroLocalize"))
        assertTrue(html.contains("formatEntityState: vitroFormatState"))
        assertTrue(html.contains("formatEntityAttributeValue: vitroFormatAttribute"))
        assertTrue(html.contains("locale: { language: 'en', number_format: 'language', time_format: 'language' }"))
        assertTrue(html.contains("themes: {} }"))
        assertTrue(html.contains("user: { id: 'vitro', name: 'Panel', is_admin: false, is_owner: false }"))
        // HA's registries, asked for on a card's first read of one; and every state once a card walks them.
        assertTrue(html.contains("const registries = { entities: {}, devices: {}, areas: {}, floors: {} };"))
        // Each hass keeps the registries it was made with: a card comparing old and new sees a change.
        assertTrue(html.contains("const snapshot = Object.assign({}, registries);"))
        assertTrue(html.contains("return snapshot[key];"))
        assertTrue(html.contains("sendBridge({ type: 'registries' });"))
        assertTrue(html.contains("m.type === 'registries' && m.registries"))
        assertTrue(html.contains("ownKeys(target) {"))
        assertTrue(html.contains("sendBridge({ type: 'watchAll' });"))
        assertTrue("no lookup watch once it follows all", html.contains("if (!walksAll && typeof prop === 'string' && prop.includes('.'))"))
        assertTrue(html.contains("auth: { data: { access_token: accessToken, hassUrl: location.origin }"))
        // Navigation and actions go back over the bridge.
        assertTrue(html.contains("window.addEventListener('location-changed'"))
        assertTrue(html.contains("window.addEventListener('hass-navigate'"))
        assertTrue(html.contains("window.addEventListener('hass-action'"))
        assertTrue(html.contains("sendBridge({ type: 'navigate', path })"))
        // Resizes are debounced, and a 0 px first measurement is never sent.
        assertTrue(html.contains("timer = setTimeout(reportSize, 50);"))
        assertTrue(html.contains("if (h <= 0 || h === lastH) return;"))
    }

    @Test
    fun anIslandNavigateMessageGoesToVitrosNavigation() {
        val paths = mutableListOf<String>()
        val island = WebIslandCardView(cardContext(navigate = { paths += it }), islandCard())
        val noReply: (JSONObject) -> Unit = { throw AssertionError("navigate needs no reply") }
        island.handleIslandMessage(JSONObject().put("type", "navigate").put("path", "/lovelace/energy"), noReply)
        // Never the island's own page, and never an empty path.
        island.handleIslandMessage(JSONObject().put("type", "navigate").put("path", "/__vitro/island/s1"), noReply)
        island.handleIslandMessage(JSONObject().put("type", "navigate").put("path", ""), noReply)
        idle()
        assertEquals(listOf("/lovelace/energy"), paths)
    }

    @Test
    fun islandHtmlShimsTheHaElementsUpstreamCardsUse() {
        val html = WebIslandCardView.buildIslandHtml(
            cardContext(resources = listOf(LovelaceResource("/hacsfiles/button-card/button-card.js", "module"))),
            islandCard(),
        )
        for (name in listOf(
            "ha-state-icon", "iron-icon", "ha-icon-button", "paper-icon-button", "ha-slider", "paper-slider",
            "ha-switch", "ha-relative-time", "ha-ripple", "ha-spinner", "ha-formfield", "ha-input", "mwc-menu",
            "mwc-list-item",
        )) {
            assertTrue("defines $name", html.contains("vitroDefine('$name'"))
        }
        // Defined only when the card bundle didn't bring its own, and before any card bundle loads:
        // mini-media-player extends customElements.get('paper-slider') at import time.
        assertTrue(html.contains("if (!customElements.get(name)) customElements.define(name, cls);"))
        assertTrue(html.indexOf("vitroDefine('paper-slider'") < html.indexOf("button-card.js"))
        assertTrue(html.indexOf("vitroDefine('paper-icon-button'") < html.indexOf("button-card.js"))
        // ha-state-icon: explicit icon, then the entity's, then the domain's.
        assertTrue(html.contains("if (st && st.attributes && st.attributes.icon) return st.attributes.icon;"))
        assertTrue(html.contains("light: 'mdi:lightbulb'"))
        assertTrue(html.contains("return VITRO_DOMAIN_ICONS[domain] || 'mdi:home';"))
        // Icon buttons draw an mdi path or an ha-icon in a plain round button.
        assertTrue(html.contains("border:0;background:transparent;color:inherit;cursor:pointer;padding:6px;display:inline-flex;align-items:center;justify-content:center;border-radius:50%"))
        assertTrue(html.contains("svg.setAttribute('viewBox', '0 0 24 24');"))
        // Sliders and switches wrap native inputs and bubble their events.
        assertTrue(html.contains("i.type = 'range';"))
        assertTrue(html.contains("i.type = 'checkbox';"))
        assertTrue(html.contains("new Event(type, { bubbles: true, composed: true })"))
        assertTrue(html.contains("new Event('change', { bubbles: true, composed: true })"))
        assertTrue(html.contains("if (abs < 45) return 'just now';"))
        // The original shims stay.
        assertTrue(html.contains("customElements.define('ha-card'"))
        assertTrue(html.contains("customElements.define('ha-icon'"))
    }

    @Test
    fun theFallbackCardCarriesItsLayoutInlineForShadowRoots() {
        val html = WebIslandCardView.buildIslandHtml(cardContext(), islandCard())
        assertTrue(html.contains("card.style.cssText = 'padding:12px 16px;display:block';"))
        assertTrue(html.contains("<div class=\"vitro-title\" style=\"font-size:1.15em;font-weight:500;padding-bottom:8px\">"))
        assertTrue(html.contains("<div class=\"vitro-row\" style=\"display:flex;justify-content:space-between;align-items:center;gap:12px;padding:6px 0\">"))
        assertTrue(html.contains("<span class=\"vitro-state\" style=\"color:var(--secondary-text-color)\">"))
        // Names and states still only go in as text.
        assertTrue(html.contains("rowEls[i].children[0].textContent = "))
        assertTrue(html.contains("rowEls[i].children[1].textContent = "))
    }

    private fun storeWith(vararg ids: String) = EntityStore().apply {
        val arr = org.json.JSONArray()
        for (id in ids) arr.put(JSONObject().put("entity_id", id).put("state", "on").put("attributes", JSONObject()))
        applyStatesArray(arr)
    }

    private fun islandWith(store: EntityStore, card: String) =
        WebIslandCardView(cardContext(store = store), LovelaceParser.parseCard(JSONObject(card)))

    @Test
    fun patchesReachEverySocketStillOpenForAnIslandAndRuntimeLookupsJoinTheSubscription() {
        val store = storeWith("light.lounge_lamp", "light.kitchen")
        val server = VitroIslandServer(haBase = { base }, accessToken = { null })
        try {
            val watched = CopyOnWriteArrayList<String>()
            val ctx = VitroCardContext(
                context = activity, haBase = base, store = store, theme = ThemeResolver.resolve(ThemeCatalog(), true),
                resources = emptyList(), accessToken = { null }, callService = { _, _, _, _ -> }, navigate = {},
                openMoreInfo = {}, fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
                islandServer = server, openIslandPage = { RecordingPage(activity) }, watchEntity = { watched += it },
            )
            val island = WebIslandCardView(ctx, LovelaceParser.parseCard(JSONObject("""{"type": "custom:button-card", "entity": "light.lounge_lamp"}""")))
            @Suppress("UNCHECKED_CAST")
            val handler = (server.javaClass.getDeclaredField("slots").apply { isAccessible = true }.get(server) as Map<String, VitroIslandServer.SlotHandler>).values.single()
            val older = CopyOnWriteArrayList<JSONObject>()
            val newer = CopyOnWriteArrayList<JSONObject>()
            val sendOlder: (JSONObject) -> Unit = { older += it }
            val sendNewer: (JSONObject) -> Unit = { newer += it }
            handler.onConnected(sendOlder)
            handler.onConnected(sendNewer) // a replacement connects before the old socket closes
            handler.onDisconnected(sendOlder)
            older.clear(); newer.clear()
            store.putLocal(EntityState("light.lounge_lamp", "off", emptyMap()))
            assertEquals("patch", newer.single().getString("type"))
            assertTrue("the closed socket gets nothing", older.isEmpty())

            // A lookup beyond the config joins the view's subscription; one the config names doesn't need to.
            island.handleIslandMessage(JSONObject().put("type", "watch").put("entity_id", "light.kitchen")) {}
            island.handleIslandMessage(JSONObject().put("type", "watch").put("entity_id", "light.kitchen")) {}
            assertEquals(listOf("light.kitchen"), watched.toList())
        } finally {
            server.stop()
        }
    }

    @Test
    fun aFilterIslandGetsEveryEntityAndFollowsThemAll() {
        val store = storeWith("light.kitchen", "light.porch", "sensor.studio_power")
        val island = islandWith(store, """{"type": "custom:auto-entities", "card": {"type": "entities"}, "filter": {"include": [{"domain": "light"}]}}""")
        assertTrue(island.followsAllEntities)

        val sent = mutableListOf<JSONObject>()
        island.seedInitialStates { sent += it }
        assertEquals(1, sent.size)
        val seed = sent.single().getJSONObject("states")
        assertEquals("snapshot", sent.single().getString("type"))
        assertEquals(setOf("light.kitchen", "light.porch", "sensor.studio_power"), seed.keys().asSequence().toSet())
        assertEquals("on", seed.getJSONObject("light.kitchen").getString("state"))

        // A change to any entity reaches it, and a removed one goes out as null.
        val patch = island.patchFor(setOf("light.porch", "light.gone"))!!.getJSONObject("states")
        assertEquals("on", patch.getJSONObject("light.porch").getString("state"))
        assertTrue(patch.isNull("light.gone"))
    }

    @Test
    fun anIslandNamingItsEntitiesStillSeesTheWholeStoreButOnlyFollowsItsOwn() {
        val store = storeWith("light.lounge_lamp", "light.kitchen")
        val island = islandWith(store, """{"type": "custom:button-card", "entity": "light.lounge_lamp"}""")
        assertFalse(island.followsAllEntities)

        val sent = mutableListOf<JSONObject>()
        island.seedInitialStates { sent += it }
        assertEquals(setOf("light.lounge_lamp", "light.kitchen"), sent.single().getJSONObject("states").keys().asSequence().toSet())

        assertNull(island.patchFor(setOf("light.kitchen")))
        assertEquals(setOf("light.lounge_lamp"), island.patchFor(setOf("light.lounge_lamp", "light.kitchen"))!!.getJSONObject("states").keys().asSequence().toSet())
        // A card that read another entity through hass.states starts following it.
        island.handleIslandMessage(JSONObject().put("type", "watch").put("entity_id", "light.kitchen")) {}
        assertTrue(island.patchFor(setOf("light.kitchen"))!!.getJSONObject("states").has("light.kitchen"))
        // A watched entity that is removed goes out as null too.
        island.handleIslandMessage(JSONObject().put("type", "watch").put("entity_id", "light.gone")) {}
        assertTrue(island.patchFor(setOf("light.gone"))!!.getJSONObject("states").isNull("light.gone"))
    }

    /** An island on a real slot of [server], its context reporting what the island asks of the engine. */
    private fun liveIsland(
        server: VitroIslandServer,
        store: EntityStore,
        card: String,
        watchAll: () -> Unit = {},
        watchRegistries: ((JSONObject) -> Unit) -> () -> Unit = { { } },
    ): Pair<WebIslandCardView, VitroIslandServer.SlotHandler> {
        val ctx = VitroCardContext(
            context = activity, haBase = base, store = store, theme = ThemeResolver.resolve(ThemeCatalog(), true),
            resources = emptyList(), accessToken = { null }, callService = { _, _, _, _ -> }, navigate = {},
            openMoreInfo = {}, fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
            islandServer = server, openIslandPage = { RecordingPage(activity) },
            watchAllEntities = watchAll, watchRegistries = watchRegistries,
        )
        val island = WebIslandCardView(ctx, LovelaceParser.parseCard(JSONObject(card)))
        @Suppress("UNCHECKED_CAST")
        val handler = (server.javaClass.getDeclaredField("slots").apply { isAccessible = true }.get(server) as Map<String, VitroIslandServer.SlotHandler>).values.last()
        return island to handler
    }

    /** #838: a card that walks `hass.states` (the tablet's to-do summary) gets every state, as in HA's frontend. */
    @Test
    fun anIslandThatWalksTheStatesFollowsEveryEntity() {
        val store = storeWith("light.lounge_lamp", "sensor.oil_boards")
        val server = VitroIslandServer(haBase = { base }, accessToken = { null })
        try {
            var asked = 0
            val (island, handler) = liveIsland(server, store, """{"type": "custom:tablet-home-card", "entity": "light.lounge_lamp"}""", watchAll = { asked++ })
            assertFalse(island.followsAllEntities)
            val sent = CopyOnWriteArrayList<JSONObject>()
            handler.onConnected { sent += it }
            sent.clear()
            assertNull("before it walks them, only its own entities", island.patchFor(setOf("sensor.oil_boards")))

            handler.onMessage(JSONObject().put("type", "watchAll")) {}
            assertEquals("the engine widens the view's subscription", 1, asked)
            store.putLocal(EntityState("sensor.oil_boards", "overdue", emptyMap()))
            assertEquals("overdue", sent.single().getJSONObject("states").getJSONObject("sensor.oil_boards").getString("state"))
        } finally {
            server.stop()
        }
    }

    /** #838: `hass.entities`, `devices`, `areas` and `floors` come from the shared feed, asked for on first read. */
    @Test
    fun anIslandGetsTheRegistriesOnceItReadsOne() {
        val server = VitroIslandServer(haBase = { base }, accessToken = { null })
        try {
            val feeds = CopyOnWriteArrayList<(JSONObject) -> Unit>()
            var released = 0
            val (island, handler) = liveIsland(server, storeWith(), """{"type": "custom:tablet-home-card"}""", watchRegistries = { cb ->
                feeds += cb
                ({ released++ })
            })
            val first = CopyOnWriteArrayList<JSONObject>()
            handler.onConnected { first += it }
            assertTrue("nothing asked before a card reads one", feeds.isEmpty())

            handler.onMessage(JSONObject().put("type", "registries")) {}
            handler.onMessage(JSONObject().put("type", "registries")) {} // a second read asks nothing more
            assertEquals(1, feeds.size)
            val set = JSONObject().put("entities", JSONObject().put("light.ball_lamp", JSONObject().put("device_id", "dev-1")))
                .put("devices", JSONObject()).put("areas", JSONObject()).put("floors", JSONObject())
            feeds.single()(set)
            val msg = first.last()
            assertEquals("registries", msg.getString("type"))
            assertEquals("dev-1", msg.getJSONObject("registries").getJSONObject("entities").getJSONObject("light.ball_lamp").getString("device_id"))

            // A socket that connects later (a reconnect) gets them right after its snapshot.
            val later = CopyOnWriteArrayList<JSONObject>()
            handler.onConnected { later += it }
            assertEquals(listOf("snapshot", "registries"), later.map { it.getString("type") })

            // Gone with the island.
            val parent = FrameLayout(activity)
            activity.setContentView(parent)
            parent.addView(island)
            parent.removeView(island)
            assertEquals(1, released)
        } finally {
            server.stop()
        }
    }

    @Test
    fun aConnectGetsASnapshotEvenWhenTheStoreIsEmpty() {
        // The island replaces its states with it: an entity removed while the bridge was down doesn't linger.
        val island = islandWith(storeWith(), """{"type": "custom:button-card", "entity": "light.gone"}""")
        val sent = mutableListOf<JSONObject>()
        island.seedInitialStates { sent += it }
        assertEquals("snapshot", sent.single().getString("type"))
        assertEquals(0, sent.single().getJSONObject("states").length())
        val html = WebIslandCardView.buildIslandHtml(cardContext(), islandCard())
        assertTrue("the page replaces its states on a snapshot", html.contains("m.type === 'snapshot'"))
    }

    @Test
    fun anInlineModuleWithAMixedCaseScriptEndStaysOneScript() {
        val inline = "const a = \"</SCRIPT>\"; const b = \"</Script >\"; export const marker = \"mixed-case-end\";"
        val resources = listOf(LovelaceResource("data:text/javascript;base64," + b64(inline), "module"))
        val html = WebIslandCardView.buildIslandHtml(cardContext(resources = resources), islandCard())
        // Where an HTML parser ends the module's script: the first `</script` in any case after its start.
        val start = html.indexOf("<script type=\"module\">\n")
        assertTrue(start >= 0)
        val end = html.indexOf("</script", start, ignoreCase = true)
        val body = html.substring(start, end)
        assertTrue("the whole module is inside its script element", body.contains("export const marker = \"mixed-case-end\";"))
        assertTrue(body.contains("<\\/SCRIPT>") && body.contains("<\\/Script >"))
    }

    @Test
    fun anInlineModuleKeepsItsPlusSigns() {
        assertEquals("a + b", WebIslandCardView.decodeInlineModule("data:text/javascript,a%20+%20b"))
    }

    @Test
    fun originsCompareByTheirEffectivePort() {
        val same = dev.glassworks.engine.vitro.ui.VitroCardContext.Companion::sameOrigin
        assertTrue(same("https://homeassistant.local/api/image/x", "https://homeassistant.local:443"))
        assertTrue(same("http://homeassistant.local:80/local/a.png", "http://homeassistant.local"))
        assertTrue(same("http://192.0.2.10:8123/a", "http://192.0.2.10:8123"))
        assertFalse(same("https://homeassistant.local:8443/a", "https://homeassistant.local"))
        assertFalse(same("http://homeassistant.local/a", "https://homeassistant.local"))
        assertFalse(same("https://images.example/a.png", "https://homeassistant.local"))
    }

    @Test
    fun aTokenThatArrivesAfterThePanelMovedIsNotDeliveredOrKept() {
        // The panel is set to this HA when the token is asked for, and to another one by the time it arrives.
        var current = settings
        val engine = VitroEngine(
            fallback = fallback,
            settings = { current },
            enabled = { true },
            tokenProvider = { _, done ->
                current = settings.copy(dashboard = "http://192.0.2.99:8123/lovelace/0")
                done(FakeHaServer.TOKEN)
            },
            socketFactory = server::connect,
        ).also { engines += it }
        val page = openPage(engine)
        page.load("$base/wall-test/living")
        awaitMain("auth sent") { server.commands("auth").isNotEmpty() }
        // The client got no token: the auth carries none, and nothing serves it to images or the island proxy.
        assertEquals("", server.commands("auth").first().optString("access_token"))
        assertEquals(null, engine.islandServer.let { _ ->
            val f = page.javaClass.getDeclaredMethod("tokenFor", String::class.java)
            f.invoke(page, base) as String?
        })
    }

    @Test
    fun theImageAndStreamTokenIsOnlyTheOneIssuedForTheirOrigin() {
        val engine = vitro()
        val page = openPage(engine)
        page.load("$base/wall-test/living")
        awaitMain("connected") { server.commands("subscribe_entities").isNotEmpty() }
        val tokenFor = page.javaClass.getDeclaredMethod("tokenFor", String::class.java)
        assertEquals(FakeHaServer.TOKEN, tokenFor.invoke(page, base))
        assertEquals(FakeHaServer.TOKEN, tokenFor.invoke(page, "$base/"))
        // A request still queued for another Home Assistant gets nothing.
        assertEquals(null, tokenFor.invoke(page, "http://192.0.2.99:8123"))
    }
}
