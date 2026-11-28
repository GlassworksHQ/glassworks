/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.app.Application
import android.graphics.Color
import android.os.Looper
import dev.glassworks.engine.EngineSettings
import dev.glassworks.engine.Page
import dev.glassworks.engine.vitro.compat.CompatChecker
import dev.glassworks.engine.vitro.compat.IslandFrontend
import dev.glassworks.engine.vitro.data.HaDashboardClient
import dev.glassworks.engine.vitro.ui.VitroView
import android.view.ViewGroup
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
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

/**
 * What Vitro tells Settings → Labs about the page ([VitroEngine.drawing]): the view on screen with its islands and why,
 * HA's frontend and why when Vitro leaves the page to it, each view of the dashboard, and nothing while Vitro is off.
 * Over a scripted Home Assistant ([FakeHaServer]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VitroDrawingTest {
    private val pendingImagesBefore = dev.glassworks.engine.vitro.ui.VitroCardContext.pendingImageLoads.get()

    private val base = FakeHaServer.BASE
    private val settings = EngineSettings(secureHosts = listOf("192.0.2.10"), zoom = 1f, remoteDebugging = false, dashboard = "$base/wall-test/living")
    private lateinit var activity: Activity
    private val host = RecordingHost()
    private val fallback = RecordingEngine()
    private val server = FakeHaServer()
    private val engines = ArrayList<VitroEngine>()
    private val opened = ArrayList<Page>()

    @Before fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        IslandFrontend.clear()
    }

    @After fun tearDown() {
        try {
            awaitImageLoads(pendingImagesBefore)
        } finally {
            IslandFrontend.clear()
            engines.forEach { runCatching { it.islandServer.stop() } }
            opened.forEach { page ->
                runCatching {
                    val f = page.javaClass.getDeclaredField("client"); f.isAccessible = true
                    (f.get(page) as? HaDashboardClient)?.close()
                }
            }
            server.shutdown()
        }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun awaitMain(message: String, cond: () -> Boolean) = awaitCondition(message, tick = { idle() }, cond = cond)

    private fun engine(enabled: Boolean = true) = VitroEngine(
        fallback = fallback, settings = { settings }, enabled = { enabled },
        tokenProvider = { _, done -> done(FakeHaServer.TOKEN) }, socketFactory = server::connect,
    ).also { engines += it }

    private fun open(engine: VitroEngine): Page = engine.open(activity, host, Color.BLACK).also {
        opened += it
        activity.setContentView(it.view)
        idle()
    }

    private fun ready(page: Page, url: String) {
        val before = host.messages("ready").size
        page.load(url)
        awaitMain("ready") { host.messages("ready").size > before }
    }

    private fun views(vararg views: String) = JSONObject("""{"views": [${views.joinToString(",")}]}""")

    private val living = """{"title": "Living", "path": "living", "cards": [{"type": "tile", "entity": "light.lounge_lamp"}]}"""
    private val wall = """{"title": "Wall", "path": "wall", "cards": [
        {"type": "tile", "entity": "light.lounge_lamp"},
        {"type": "custom:acme-wall-card"},
        {"type": "vertical-stack", "cards": [{"type": "custom:acme-clock-card"}]}]}"""
    private val map = """{"title": "Map", "path": "map", "cards": [{"type": "map", "entities": ["light.lounge_lamp"]}]}"""
    private val web = """{"title": "Web", "path": "web", "type": "panel", "cards": [{"type": "iframe", "url": "https://example.com/"}]}"""

    @Test fun nothingWhileVitroIsOff() {
        val engine = engine(enabled = false)
        assertNull("before the first page: the engine isn't Vitro yet", engine.drawing())
        open(engine).load("$base/wall-test/living")
        idle()
        assertNull("the browser draws the dashboard: Labs shows nothing of Vitro's", engine.drawing())
    }

    @Test fun nothingOnScreenUntilTheFirstViewIsBuilt() {
        val engine = engine()
        open(engine)
        val d = need(engine.drawing())
        assertNull(d.shown)
        assertTrue(d.views.isEmpty())
    }

    @Test fun theViewOnScreenCountsItsIslandsWithWhereAndWhy() {
        server.dashboardConfig = views(living, wall)
        val engine = engine()
        val page = open(engine)
        ready(page, "$base/wall-test/living")
        val first = need(engine.drawing()?.shown)
        assertEquals("Living", first.name)
        assertNull(first.frontend)
        assertTrue("a tile is native", first.islands.isEmpty())

        ready(page, "$base/wall-test/wall")
        val d = need(engine.drawing())
        val shown = need(d.shown)
        assertEquals("Wall", shown.name)
        assertNull(shown.frontend)
        // Each island is one card, with its place and type; the native tile isn't one.
        assertEquals(
            listOf("wall › card 2 (custom:acme-wall-card)", "wall › card 3 (vertical-stack) › card 1 (custom:acme-clock-card)"),
            shown.islands.map { it.where },
        )
        assertTrue(shown.islands.toString(), shown.islands.all { it.why.isNotBlank() })
        // The same count the build logs (`islands=` is its routed tier count).
        assertEquals(2, shown.islands.size)
        // Each view of the dashboard, the one on screen as it was built.
        assertEquals(listOf("Living", "Wall"), d.views.map { it.name })
        assertSame(shown, d.views[1])
        assertTrue(d.views[0].islands.isEmpty())
    }

    @Test fun eachViewIsListedFromItsConfigBeforeItOpens() {
        server.dashboardConfig = views(living, wall, map, web)
        val engine = engine()
        ready(open(engine), "$base/wall-test/living")
        val v = engine.drawing()!!.views
        assertEquals(listOf("Living", "Wall", "Map", "Web"), v.map { it.name })
        assertEquals(2, v[1].islands.size)
        // The map card sends its whole view to HA's frontend: no islands are counted for it.
        assertEquals("Labs shows the reason without Markdown", "the built-in map card", v[2].frontend)
        assertTrue(v[2].islands.isEmpty())
        // An iframe card is drawn natively, but its page takes an island of its own.
        assertNull(v[3].frontend)
        assertEquals(listOf(CompatChecker.IFRAME_ISLAND.replace("`", "")), v[3].islands.map { it.why })
    }

    @Test fun aViewLeftToHomeAssistantsFrontendSaysWhyAndCountsNoIslands() {
        server.dashboardConfig = views(living, map)
        val engine = engine()
        val page = open(engine)
        ready(page, "$base/wall-test/living")
        page.load("$base/wall-test/map")
        idle()
        val d = engine.drawing()!!
        val shown = need(d.shown)
        assertEquals("Map", shown.name)
        assertEquals("the built-in map card", shown.frontend)
        assertTrue(shown.islands.isEmpty())
        assertSame("the view list shows it in its place", shown, d.views[1])

        // Back to a view Vitro draws: native again.
        ready(page, "$base/wall-test/living")
        assertEquals("Living", engine.drawing()!!.shown!!.name)
        assertNull(engine.drawing()!!.shown!!.frontend)
    }

    @Test fun aPageThatIsNotADashboardIsHomeAssistantsWithNoViews() {
        val engine = engine()
        val page = open(engine)
        ready(page, "$base/wall-test/living")
        page.load("$base/config/devices/dashboard")
        idle()
        val d = engine.drawing()!!
        assertEquals(VitroDrawing.NOT_A_DASHBOARD_PAGE, d.shown)
        assertTrue(d.views.isEmpty())
    }

    @Test fun aDashboardHomeAssistantHasNoConfigForIsHomeAssistantsWhole() {
        val engine = engine()
        val page = open(engine)
        page.load("$base/missing")
        awaitMain("fallback") { engine.drawing()?.shown != null }
        val shown = engine.drawing()!!.shown!!
        assertEquals(VitroDrawing.THIS_DASHBOARD, shown.name)
        assertEquals(VitroDrawing.NO_CONFIG, shown.frontend)
        assertTrue(engine.drawing()!!.views.isEmpty())
    }

    @Test fun aStrategyDashboardIsHomeAssistantsWhole() {
        server.dashboardConfig = JSONObject("""{"strategy": {"type": "original-states"}}""")
        val engine = engine()
        open(engine).load("$base/wall-test")
        awaitMain("fallback") { engine.drawing()?.shown != null }
        val shown = engine.drawing()!!.shown!!
        assertEquals(VitroDrawing.THIS_DASHBOARD, shown.name)
        org.junit.Assert.assertNotNull(shown.frontend)
        assertTrue(shown.islands.isEmpty())
    }

    private fun vitroView(page: Page): VitroView = (page.view as ViewGroup).getChildAt(0) as VitroView

    /** The host the page's own fallback reports to (islands open theirs transparent). */
    private fun frontendHost() = fallback.opens.single { it.background == Color.BLACK }.host

    @Test fun whenHomeAssistantsFrontendMovesOnLabsFollowsIt() {
        server.dashboardConfig = views(living, map)
        val engine = engine()
        val page = open(engine)
        ready(page, "$base/wall-test/living")
        page.load("$base/wall-test/map")
        idle()
        assertEquals("Map", engine.drawing()!!.shown!!.name)
        // A dialog over the same view: what Labs says stays.
        frontendHost().onLocationChange("$base/wall-test/map?more-info-entity-id=light.lounge_lamp")
        assertEquals("Map", engine.drawing()!!.shown!!.name)
        // Into HA's settings, which never hand the page back to Vitro.
        frontendHost().onLocationChange("$base/config/areas/dashboard")
        idle()
        val d = engine.drawing()!!
        assertEquals(VitroDrawing.NOT_A_DASHBOARD_PAGE, d.shown)
        assertTrue(d.views.isEmpty())
        // Back to the map view, still HA's.
        frontendHost().onLocationChange("$base/wall-test/map")
        idle()
        assertEquals("Map", engine.drawing()!!.shown!!.name)
        assertEquals(2, engine.drawing()!!.views.size)
    }

    @Test fun eachViewCountsWhatIslandsLearnedThisSession() {
        val kitchen = """{"title": "Kitchen", "path": "kitchen", "cards": [{"type": "tile", "entity": "light.lounge_lamp"}]}"""
        server.dashboardConfig = views(living, wall, kitchen)
        val engine = engine()
        val page = open(engine)
        ready(page, "$base/wall-test/living")
        assertNull(engine.drawing()!!.views[1].frontend)
        // An island found the wall card drawing with HA's own elements: its view goes to HA's frontend from now on.
        IslandFrontend.recordReason(vitroView(page).resourcesList, "custom:acme-wall-card", "it draws with `ha-big-number`")
        ready(page, "$base/wall-test/kitchen")
        val wallNow = engine.drawing()!!.views[1]
        org.junit.Assert.assertNotNull(wallNow.frontend)
        assertTrue(wallNow.islands.isEmpty())
    }

    @Test fun subviewsAndViewsHiddenFromThisUserAreNotListedUnlessOnScreen() {
        val secret = """{"title": "Secret", "path": "secret", "visible": false, "cards": [{"type": "tile", "entity": "light.lounge_lamp"}]}"""
        val sub = """{"title": "Detail", "path": "detail", "subview": true, "cards": [{"type": "tile", "entity": "light.lounge_lamp"}]}"""
        server.dashboardConfig = views(living, secret, sub)
        val engine = engine()
        val page = open(engine)
        ready(page, "$base/wall-test/living")
        assertEquals(listOf("Living"), engine.drawing()!!.views.map { it.name })
        ready(page, "$base/wall-test/detail")
        assertEquals("the view on screen is listed, tab or not", listOf("Living", "Detail"), engine.drawing()!!.views.map { it.name })
    }

    /** [value], failing the test when it's null. */
    private fun <T> need(value: T?): T {
        org.junit.Assert.assertNotNull(value)
        return value!!
    }
}
