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
import dev.glassworks.engine.vitro.compat.BubbleFallback
import dev.glassworks.engine.vitro.compat.CompatChecker
import dev.glassworks.engine.vitro.compat.IslandBudget
import dev.glassworks.engine.vitro.compat.IslandFrontend
import dev.glassworks.engine.vitro.compat.IslandScripts
import dev.glassworks.engine.vitro.compat.VitroSupport
import dev.glassworks.engine.vitro.compat.WebFallback
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.data.HaDashboardClient
import dev.glassworks.engine.vitro.model.LovelaceParser
import dev.glassworks.engine.vitro.model.LovelaceResource
import dev.glassworks.engine.vitro.model.LovelaceResourceList
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
    /** The picture loads a test starts are all back before the next test: one in flight lands in its looper (D28). */
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
        // The servers and clients close whether or not the picture loads came back (that failure still fails the test).
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

    /** The page's own fallback (opened with the page's background); islands open theirs transparent. */
    private fun pageFallback(): RecordingPage? = fallback.opens.indexOfFirst { it.background == Color.BLACK }.takeIf { it >= 0 }?.let { fallback.pages[it] }
    private fun pageFallbackHost() = fallback.opens.single { it.background == Color.BLACK }.host
    private fun islandOpens() = fallback.opens.count { it.background == Color.TRANSPARENT }

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

    @Test fun anIframeOfAHomeAssistantPageAtItsFullAddressShowsInHomeAssistantsFrontend() {
        // #799 review: HA's X-Frame-Options: SAMEORIGIN refuses its own page in a loopback island, whether the card
        // names it by path or by its full address.
        server.dashboardConfig = views(living, """{"title": "Plan", "path": "plan", "cards": [
            {"type": "vertical-stack", "cards": [{"type": "iframe", "url": "$base/local/plan.html"}]}]}""")
        val p = page()
        p.load("$base/wall-test/living")
        awaitMain("ready") { host.messages("ready").isNotEmpty() }
        assertNull("the native view needs no fallback", pageFallback())

        p.load("$base/wall-test/plan")
        idle()
        assertEquals(listOf("$base/wall-test/plan"), pageFallback()!!.loads)
        assertEquals(View.GONE, vitroView(p).visibility)
        assertEquals("no island for the frame HA would refuse", 0, islandOpens())
    }

    @Test fun anIframeUrlIsHomeAssistantsWhenItIsOnItsOrigin() {
        val ha = "https://homeassistant.local:8123"
        assertTrue(WebFallback.sameOriginIframe("/local/plan.html", ha))
        assertTrue(WebFallback.sameOriginIframe("plan.html", ha))
        assertTrue(WebFallback.sameOriginIframe("https://homeassistant.local:8123/local/plan.html", ha))
        assertTrue("the scheme and host are case-insensitive", WebFallback.sameOriginIframe("HTTPS://HomeAssistant.local:8123/x", ha))
        assertTrue("scheme-relative takes HA's scheme", WebFallback.sameOriginIframe("//homeassistant.local:8123/x", ha))
        assertTrue("the default port is the same origin", WebFallback.sameOriginIframe("https://192.0.2.10/x", "https://192.0.2.10:443"))
        assertFalse("another port", WebFallback.sameOriginIframe("https://homeassistant.local/x", ha))
        assertFalse("another scheme", WebFallback.sameOriginIframe("http://homeassistant.local:8123/x", ha))
        assertFalse("another host", WebFallback.sameOriginIframe("https://example.com/", ha))
        assertFalse(WebFallback.sameOriginIframe("//example.com/x", ha))
        // Without HA's origin, only what HA resolves itself is its own (as the view's own reason reads it).
        assertTrue(WebFallback.sameOriginIframe("/local/plan.html"))
        assertFalse(WebFallback.sameOriginIframe("https://homeassistant.local:8123/local/plan.html"))

        fun reason(card: String) = WebFallback.haIframeReason(LovelaceParser.parseView(0, JSONObject("""{"cards": [$card]}""")), ha)
        assertEquals("an `iframe` card of a Home Assistant page", reason("""{"type": "iframe", "url": "$ha/local/plan.html"}"""))
        assertEquals("inside a stack too", "an `iframe` card of a Home Assistant page",
            reason("""{"type": "horizontal-stack", "cards": [{"type": "iframe", "url": "$ha/local/plan.html"}]}"""))
        assertNull(reason("""{"type": "iframe", "url": "https://example.com/"}"""))
        assertNull("no origin known: nothing to compare", WebFallback.haIframeReason(
            LovelaceParser.parseView(0, JSONObject("""{"cards": [{"type": "iframe", "url": "$ha/x"}]}""")), null))
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

    @Test fun aShownDashboardThatBecomesAStrategyIsFetchedAgainNotRestored() {
        val p = page()
        p.load("$base/wall-test/living")
        awaitMain("ready") { host.messages("ready").isNotEmpty() }

        // Edited in HA into a strategy dashboard: the live refresh hands it to HA's frontend.
        server.dashboardConfig = JSONObject("""{"strategy": {"type": "original-states"}}""")
        server.pushEvent(server.eventSubscriptionId("lovelace_updated")!!, JSONObject().put("event_type", "lovelace_updated"))
        awaitMain("fallback") { pageFallback() != null }
        assertEquals(listOf("$base/wall-test/living"), pageFallback()!!.loads)

        // Back to it (a host navigate): its old native views are stale. It's asked for again and HA's frontend shows it.
        val fetches = server.commands("lovelace/config").size
        host.ports.single().post(JSONObject().put("type", "navigate").put("url", "$base/wall-test/living"))
        awaitMain("fallback again") { pageFallback()!!.loads.size == 2 }
        assertEquals(fetches + 1, server.commands("lovelace/config").size)
        assertEquals(View.GONE, vitroView(p).visibility)
        assertEquals(View.VISIBLE, pageFallback()!!.view.visibility)
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

    // ---- How many islands one view runs (#799 D2) ----

    private val manyIslands = """{"title": "Many", "path": "many", "cards": [
        {"type": "custom:acme-a-card"},
        {"type": "vertical-stack", "cards": [{"type": "custom:acme-b-card"}, {"type": "custom:acme-c-card"}]}]}"""

    @Test fun aViewWithMoreIslandsThanTheDeviceRunsShowsInHomeAssistantsFrontend() {
        server.dashboardConfig = views(living, manyIslands)
        val p = page()
        p.load("$base/wall-test/living")
        awaitMain("ready") { host.messages("ready").isNotEmpty() }
        val vv = vitroView(p)
        vv.islandBudget = 2
        tab(vv, "Many").performClick()
        idle()
        assertEquals(listOf("$base/wall-test/many"), pageFallback()!!.loads)
        assertEquals("three browser pages would cost more than HA's one: none opens", 0, islandOpens())
    }

    @Test fun aViewWithinTheIslandBudgetIsDrawnWithItsIslands() {
        server.dashboardConfig = views(living, manyIslands)
        val p = page()
        p.load("$base/wall-test/living")
        awaitMain("ready") { host.messages("ready").isNotEmpty() }
        val vv = vitroView(p)
        vv.islandBudget = 3
        tab(vv, "Many").performClick()
        idle()
        assertNull("drawn natively", pageFallback())
        assertEquals(3, islandOpens())
    }

    // ---- A Bubble card no island can draw (#939) ----

    private val bubbleSubButtons = """{"title": "Bubble", "path": "bubble", "cards": [
        {"type": "vertical-stack", "cards": [{"type": "custom:bubble-card", "card_type": "button", "entity": "light.lounge_lamp",
          "sub_button": [{"entity": "light.lounge_lamp", "tap_action": {"action": "toggle"}}]}]}]}"""
    private val bubblePlain = """{"title": "Plain", "path": "plain", "cards": [
        {"type": "custom:bubble-card", "card_type": "button", "entity": "light.lounge_lamp"}]}"""

    @Test fun aBubbleCardVitroCantDrawWithNoBubbleScriptShowsInHomeAssistantsFrontend() {
        // The fake HA's resources don't load bubble-card.js: no island can draw the sub-button, and Vitro's own Bubble
        // button would drop it.
        server.dashboardConfig = views(living, bubbleSubButtons)
        val p = page()
        p.load("$base/wall-test/living")
        awaitMain("ready") { host.messages("ready").isNotEmpty() }
        val vv = vitroView(p)
        tab(vv, "Bubble").performClick()
        idle()
        assertEquals(listOf("$base/wall-test/bubble"), pageFallback()!!.loads)
        assertEquals(0, islandOpens())
    }

    @Test fun aBubbleCardVitroDrawsStaysNativeWithoutBubblesScript() {
        server.dashboardConfig = views(living, bubblePlain)
        val p = page()
        p.load("$base/wall-test/living")
        awaitMain("ready") { host.messages("ready").isNotEmpty() }
        val vv = vitroView(p)
        tab(vv, "Plain").performClick()
        idle()
        assertNull("drawn natively", pageFallback())
        // The same resources, now known to come with Bubble modules (only Bubble's script applies them): the view
        // is built again, in HA's frontend.
        val same = vv.resourcesList.toList()
        vv.resourcesList = LovelaceResourceList(same, bubbleModules = true)
        idle()
        assertEquals(listOf("$base/wall-test/plain"), pageFallback()!!.loads)
    }

    // ---- The first decision waits for the resources (tablet Vitro run 3) ----

    /**
     * A VitroView as the engine sets it up for a cold start: the first build waits for the resources' answer. Records
     * each view it hands to HA's frontend, and each view it builds.
     */
    private class ColdView(val vv: VitroView, val web: MutableList<String>, val built: MutableList<Int>)

    private fun coldView(dashboard: JSONObject, path: String, holdMs: Long = 5_000): ColdView {
        val store = EntityStore().apply { putLocal(EntityState("light.lounge_lamp", "on", mapOf("friendly_name" to "Lounge"))) }
        val vv = VitroView(activity, store, Color.BLACK)
        vv.holdFirstBuildForStatesMs = 0
        vv.holdFirstBuildForResourcesMs = holdMs
        val web = ArrayList<String>()
        val built = ArrayList<Int>()
        vv.onWebView = { v, reason -> web += "${v.path}: $reason" }
        vv.onMetrics = { built += vv.currentViewIndex }
        vv.contextFactory = { theme ->
            VitroCardContext(
                context = activity, haBase = base, store = store, theme = theme, resources = vv.resourcesList,
                accessToken = { FakeHaServer.TOKEN }, callService = { _, _, _, _ -> }, navigate = {}, openMoreInfo = {},
                fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
            )
        }
        activity.setContentView(vv)
        vv.setDashboard(LovelaceParser.parseDashboard(dashboard), path)
        idle()
        return ColdView(vv, web, built)
    }

    private val bubbleScript = LovelaceResource("/hacsfiles/Bubble-Card/bubble-card.js?hacstag=343", "module")

    @Test fun aColdStartDecidesOnceTheResourcesAreKnown() {
        // The config lands before the resources (Bubble's modules check holds them back): the view whose sub-button only
        // Bubble's island draws went to HA's frontend ("no Lovelace resource loads Bubble Card's script"), then came
        // back once they landed. Now it waits, and the first decision is the one that stands.
        val c = coldView(views(living, bubbleSubButtons), "bubble")
        assertEquals("nothing is decided before the resources", emptyList<String>(), c.web)
        assertEquals(emptyList<Int>(), c.built)
        assertFalse(c.vv.currentViewBuilt)
        c.vv.resourcesList = LovelaceResourceList(listOf(bubbleScript), bubbleModules = false)
        idle()
        assertEquals("an island draws it: Vitro's view", emptyList<String>(), c.web)
        assertEquals(listOf(1), c.built)
        assertTrue(c.vv.currentViewBuilt)
    }

    @Test fun aColdStartThatGoesToHomeAssistantsFrontendBuildsNoOtherView() {
        // Without Bubble's script the view goes to HA's frontend once the answer says so, and the dashboard's first
        // view, which the page never showed, isn't built behind it (tablet Vitro run 3 built `bubble` there).
        val c = coldView(views(living, bubbleSubButtons), "bubble")
        c.vv.resourcesList = LovelaceResourceList(emptyList(), bubbleModules = false)
        idle()
        assertEquals(1, c.web.size)
        assertTrue(c.web.single(), c.web.single().startsWith("bubble: a Bubble card Vitro doesn't draw as Bubble does"))
        assertEquals(emptyList<Int>(), c.built)
        // Resources that change later rebuild the view the page went to HA's frontend for, not the first one.
        c.vv.resourcesList = LovelaceResourceList(listOf(LovelaceResource("/local/other.js", "module")), bubbleModules = false)
        idle()
        assertEquals(2, c.web.size)
        assertEquals(emptyList<Int>(), c.built)
    }

    @Test fun aRebuildDuringTheWaitKeepsTheRequestedView() {
        // Greptile on #952: the user's light or dark choice (or any rebuild) landing while the requested view waits for
        // the resources rendered the index before it, which took the wait over: the page then opened the first view.
        val c = coldView(views(living, bubblePlain), "plain")
        assertEquals(1, c.vv.currentViewIndex)
        c.vv.setUserTheme(null, dark = !c.vv.darkMode)
        idle()
        assertEquals("nothing built while it waits", emptyList<Int>(), c.built)
        assertEquals(1, c.vv.currentViewIndex)
        c.vv.resourcesList = LovelaceResourceList(listOf(bubbleScript), bubbleModules = false)
        idle()
        assertEquals("the requested view, once", listOf(1), c.built)
        assertEquals(emptyList<String>(), c.web)
    }

    @Test fun theViewTabsTakeHomeAssistantsHeaderColorNotTheCards() {
        // HA's hui-root toolbar: --app-header-background-color, which HA's theme sets to --sidebar-background-color and
        // that to --card-background-color. A theme with transparent cards (--ha-card-background) keeps its toolbar
        // (Greptile on #952: the tabs took the card-only color, and went transparent).
        val vv = VitroView(activity, EntityStore(), Color.BLACK)
        fun theme(vararg vars: Pair<String, String>, dark: Boolean = true) = dev.glassworks.engine.vitro.theme.ThemeResolver.resolve(
            dev.glassworks.engine.vitro.theme.ThemeCatalog(themes = mapOf("t" to dev.glassworks.engine.vitro.theme.ThemeCatalog.ThemeDefinition(base = mapOf(*vars)))),
            dark, "t",
        )
        val glass = theme("ha-card-background" to "transparent")
        assertEquals("the card itself is transparent", Color.TRANSPARENT, glass.cardBackgroundColor and 0xFF000000.toInt())
        assertEquals("HA's dark --card-background-color", 0xFF1C1C1C.toInt(), vv.headerBackground(glass))
        assertEquals(0xFFFFFFFF.toInt(), vv.headerBackground(theme("ha-card-background" to "rgba(0, 0, 0, 0)", dark = false)))
        // A theme's own header, or its sidebar, wins, as in HA.
        assertEquals(0xFF123456.toInt(), vv.headerBackground(theme("ha-card-background" to "transparent", "app-header-background-color" to "#123456")))
        assertEquals(0xFF654321.toInt(), vv.headerBackground(theme("sidebar-background-color" to "#654321")))
        assertEquals(0xFF222233.toInt(), vv.headerBackground(theme("card-background-color" to "#222233")))
    }

    @Test fun aColdStartWithoutAnAnswerBuildsAfterTheWait() {
        val c = coldView(views(living, bubblePlain), "plain", holdMs = 2_000)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1_900))
        assertEquals(emptyList<Int>(), c.built)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200))
        assertEquals("built as if there were none", listOf(1), c.built)
        assertTrue(c.vv.resourcesKnown)
        // A later answer is a change like any other: the view is built again.
        c.vv.resourcesList = LovelaceResourceList(listOf(bubbleScript), bubbleModules = false)
        idle()
        assertEquals(listOf(1, 1), c.built)
    }

    @Test fun anEmptyFirstAnswerReleasesTheWait() {
        // The list the view assumed (none) is still an answer: the held build goes ahead.
        val c = coldView(views(living, bubblePlain), "plain")
        c.vv.resourcesList = emptyList()
        idle()
        assertEquals(listOf(1), c.built)
        assertEquals(emptyList<String>(), c.web)
    }

    @Test fun bubbleFallbackFollowsTheIslandsScriptsAndBubblesModules() {
        fun view(card: String) = LovelaceParser.parseView(0, JSONObject("""{"cards": [$card]}"""))
        val sub = view("""{"type": "custom:bubble-card", "entity": "switch.fan", "sub_button": [{"entity": "switch.fan"}]}""")
        val plain = view("""{"type": "custom:bubble-card", "entity": "switch.fan"}""")
        fun scripts(bubble: Boolean, modules: Boolean) = IslandScripts.of(LovelaceResourceList(
            if (bubble) listOf(LovelaceResource("/hacsfiles/Bubble-Card/bubble-card.js?hacstag=343", "module")) else emptyList(), modules,
        ))
        assertNotNull(BubbleFallback.reason(sub, scripts(bubble = false, modules = false)))
        assertNull("the island draws it", BubbleFallback.reason(sub, scripts(bubble = true, modules = false)))
        assertNull("Vitro draws it as Bubble does", BubbleFallback.reason(plain, scripts(bubble = false, modules = false)))
        assertNotNull("modules only Bubble's script applies", BubbleFallback.reason(plain, scripts(bubble = false, modules = true)))
        assertNull("the island applies them", BubbleFallback.reason(plain, scripts(bubble = true, modules = true)))
        assertNull(BubbleFallback.reason(sub, IslandScripts.ANY))
    }

    @Test fun theIslandCountIsWhatTheViewsBuildOpens() {
        fun count(cards: String) = IslandBudget.islandCount(
            LovelaceParser.parseView(0, JSONObject("""{"cards": [$cards]}""")), IslandScripts.ANY,
        )
        assertEquals(0, count("""{"type": "tile", "entity": "light.lounge_lamp"}"""))
        assertEquals(1, count("""{"type": "custom:acme-a-card"}"""))
        assertEquals("the cards a stack holds", 2, count("""{"type": "grid", "cards": [{"type": "custom:acme-a-card"},
            {"type": "conditional", "conditions": [], "card": {"type": "custom:acme-b-card"}}, {"type": "tile", "entity": "light.a"}]}"""))
        assertEquals("an island draws the cards inside it in its own page", 1, count("""{"type": "custom:acme-wrap-card",
            "cards": [{"type": "custom:acme-a-card"}, {"type": "custom:acme-b-card"}]}"""))
        assertEquals("an iframe is a page of its own", 1, count("""{"type": "iframe", "url": "https://example.com/"}"""))
        assertEquals("auto-entities' generated cards count once, inside the card they fill", 1, count("""{"type": "custom:auto-entities",
            "card_param": "cards", "card": {"type": "grid"},
            "filter": {"include": [{"domain": "light", "options": {"type": "iframe", "url": "https://example.com/"}}]}}"""))
        assertEquals("a panel view builds its first card", 1, IslandBudget.islandCount(LovelaceParser.parseView(0, JSONObject(
            """{"type": "panel", "cards": [{"type": "custom:acme-a-card"}, {"type": "custom:acme-b-card"}]}""")), IslandScripts.ANY))
    }

    @Test fun theBudgetFollowsTheDevicesMemory() {
        assertEquals(IslandBudget.SMALL, IslandBudget.forTotalMem(1_073_741_824L))
        // The S1 reads 2.07 GB: a 2 GB panel.
        assertEquals(IslandBudget.MEDIUM, IslandBudget.forTotalMem(2_070_000_000L))
        assertEquals(IslandBudget.LARGE, IslandBudget.forTotalMem(7_800_000_000L))
    }

    // ---- A link in an island (#799 D7) ----

    @Test fun aLinkInAnIslandOpensInThePanelsPageAndTheCardStays() {
        server.dashboardConfig = views("""{"title": "Home", "path": "home", "cards": [{"type": "custom:acme-a-card"}]}""")
        val p = page()
        p.load("$base/wall-test/home")
        awaitMain("ready") { host.messages("ready").isNotEmpty() }
        val i = fallback.opens.indexOfFirst { it.background == Color.TRANSPARENT }
        val islandHost = fallback.opens[i].host
        val islandUrl = fallback.pages[i].loads.single()
        assertTrue("its own page loads", islandHost.allowsLoad(islandUrl))
        assertTrue(islandHost.allowsLoad("about:blank"))
        // The island page is under the island server's capability (`/<cap>/__vitro/island/<id>`).
        val inside = islandUrl.substringBefore("/__vitro/")
        val origin = Regex("^http://[^/]+").find(islandUrl)!!.value
        assertFalse("another host that starts like the island's", islandHost.allowsLoad("$origin.example.com/"))
        assertFalse("a calendar event's link", islandHost.allowsLoad("https://www.example.com/events/1.html"))
        assertFalse("nothing but a web address is opened elsewhere", islandHost.allowsLoad("javascript:alert(1)"))
        // Another document the island server proxies from HA: HA's own page for it, never the card's window, and
        // never with the capability (HA's page never sees it), whether the card's link had it or not.
        assertFalse("a proxied HA document", islandHost.allowsLoad("$inside/local/help.html"))
        assertFalse("a proxied HA document by its absolute path", islandHost.allowsLoad("$origin/local/help.html"))
        idle()
        assertEquals("the island stays on its page", listOf(islandUrl), fallback.pages[i].loads)
        // Web addresses open in the panel's page, as a native card's url action does, in the order they came.
        assertEquals(
            listOf("$origin.example.com/", "https://www.example.com/events/1.html", "$base/local/help.html", "$base/local/help.html"),
            pageFallback()?.loads,
        )
    }

    @Test fun aBuiltInCardNestedInAStackOrAutoEntitiesCounts() {
        fun reason(card: String) = WebFallback.viewReason(LovelaceParser.parseView(0, JSONObject("""{"cards": [$card]}""")))
        assertEquals("the built-in `map` card", reason("""{"type": "vertical-stack", "cards": [{"type": "tile"}, {"type": "map"}]}"""))
        assertEquals("the built-in `energy-distribution` card", reason("""{"type": "conditional", "conditions": [], "card": {"type": "energy-distribution"}}"""))
        assertEquals("the built-in `map` card", reason("""{"type": "custom:auto-entities", "card": {"type": "map"}, "filter": {"include": [{"domain": "person"}]}}"""))
        assertEquals("the built-in `water-sankey` card", reason("""{"type": "custom:layout-card", "layout_type": "masonry", "cards": [{"type": "water-sankey"}]}"""))
        assertNull("a custom card goes to its island", reason("""{"type": "custom:acme-card"}"""))
        assertNull(reason("""{"type": "grid", "cards": [{"type": "tile"}, {"type": "entities", "entities": ["light.a"]}]}"""))
    }

    // ---- Custom cards that draw with HA's own elements (an island's needsFrontend) ----

    // An entity the fake HA has: the view's first build waits for its states.
    private val study = """{"type": "custom:better-thermostat-normal-climate-card", "entity": "light.lounge_lamp"}"""
    private val climateView = """{"title": "Climate", "path": "climate", "cards": [$study]}"""
    private val dialTags = listOf("ha-big-number", "ha-control-circular-slider")

    @Test fun noCustomCardIsNamedTheIslandsReportIsWhatSendsOne() {
        // No list of cards: Better Thermostat's climate card is an island like any other until its island reports.
        fun view(card: String) = LovelaceParser.parseView(0, JSONObject("""{"cards": [$card]}"""))
        assertNull(WebFallback.viewReason(view(study)))
        val learned = { c: dev.glassworks.engine.vitro.model.CardConfig ->
            if (c.type == "custom:better-thermostat-normal-climate-card") "a dial" else null
        }
        assertEquals("a dial", WebFallback.viewReason(view(study), learned))
        assertEquals("nested too", "a dial", WebFallback.viewReason(view("""{"type": "vertical-stack", "cards": [$study]}"""), learned))
        assertNull(
            "the mini card stays an island",
            WebFallback.viewReason(view("""{"type": "custom:better-thermostat-mini-climate-card", "entity": "climate.study"}"""), learned),
        )
        assertEquals(
            "the view's own reason comes first",
            "the built-in `map` card",
            WebFallback.viewReason(view("""{"type": "map"}, $study"""), learned),
        )
    }

    @Test fun theDecisionIsPerCardTypeAndScriptVersion() {
        val v1 = listOf(dev.glassworks.engine.vitro.model.LovelaceResource("/hacsfiles/bt/bt.js?hacstag=340", "module"))
        val v2 = listOf(dev.glassworks.engine.vitro.model.LovelaceResource("/hacsfiles/bt/bt.js?hacstag=350", "module"))
        val type = "custom:better-thermostat-normal-climate-card"
        assertTrue(IslandFrontend.record(v1, type, dialTags))
        assertFalse("once is enough: no flapping", IslandFrontend.record(v1, type, listOf("ha-big-number")))
        assertEquals(
            "a `$type` card (it draws with Home Assistant's `ha-big-number`, `ha-control-circular-slider`, which only its frontend has)",
            IslandFrontend.reason(v1, type),
        )
        assertEquals("the same scripts in another order", IslandFrontend.reason(v1, type), IslandFrontend.reason(v1.reversed(), type))
        assertNull("another card type", IslandFrontend.reason(v1, "custom:better-thermostat-mini-climate-card"))
        assertNull("a new release of the scripts is looked at again", IslandFrontend.reason(v2, type))
    }

    /** The island of [vv] that draws [type]. */
    private fun islandOf(vv: VitroView, type: String): WebIslandCardView {
        fun find(v: View): WebIslandCardView? {
            if (v is WebIslandCardView && v.config.type == type) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
            return null
        }
        return find(vv) ?: error("no island of $type")
    }

    private fun needsFrontend(tags: List<String>, cards: List<String>) = JSONObject()
        .put("type", "needsFrontend").put("tags", org.json.JSONArray(tags)).put("cards", org.json.JSONArray(cards))

    @Test fun anIslandThatReportsHasElementsSendsItsViewToHomeAssistantsFrontendForTheSession() {
        server.dashboardConfig = views(living, climateView)
        val p = page()
        p.load("$base/wall-test/climate")
        awaitMain("ready") { host.messages("ready").isNotEmpty() }
        val vv = vitroView(p)
        assertNull("drawn natively, with an island, until the island reports", pageFallback())
        assertEquals(1, islandOpens())
        val islandPage = fallback.pages[fallback.opens.indexOfFirst { it.background == Color.TRANSPARENT }]

        // What frontend-watch.js sends once the dial stayed undefined.
        islandOf(vv, "custom:better-thermostat-normal-climate-card")
            .handleIslandMessage(needsFrontend(dialTags, listOf("better-thermostat-normal-climate-card"))) { throw AssertionError("no reply") }
        idle()
        val fp = pageFallback()!!
        assertEquals(listOf("$base/wall-test/climate"), fp.loads)
        assertEquals(View.GONE, vv.visibility)
        assertTrue("the view's island closed: its tree never shows again", islandPage.closed)

        // HA's frontend reports the view it draws: not handed back to Vitro, which would only find the same again.
        pageFallbackHost().onLocationChange("$base/wall-test/climate")
        idle()
        assertEquals(View.GONE, vv.visibility)
        assertEquals(listOf("$base/wall-test/climate"), fp.loads)

        // The other view is native; back on the climate tab, the view goes to HA's frontend straight away, no island.
        p.load("$base/wall-test/living")
        awaitMain("living") { vv.visibility == View.VISIBLE && vv.currentViewIndex == 0 }
        tab(vv, "Climate").performClick()
        idle()
        assertEquals(View.GONE, vv.visibility)
        assertEquals("no new island for a card the session knows", 1, islandOpens())
        assertEquals("$base/wall-test/climate", pageFallback()!!.loads.last())
    }

    @Test fun aReportWithNoTagsOrAfterTheFirstChangesNothing() {
        server.dashboardConfig = views(climateView)
        val p = page()
        p.load("$base/wall-test/climate")
        awaitMain("ready") { host.messages("ready").isNotEmpty() }
        val island = islandOf(vitroView(p), "custom:better-thermostat-normal-climate-card")
        // Not element names (a page that sends anything): ignored.
        island.handleIslandMessage(needsFrontend(listOf("<b>", "Ha-X", "nohyphen"), emptyList())) { throw AssertionError("no reply") }
        idle()
        assertNull(pageFallback())
        assertTrue(IslandFrontend.isEmpty)
    }

    @Test fun aReportingIslandInAViewWithNoPageIsOnlyRemembered() {
        // A view with no page (no onWebView) can't leave: it stays as it is, and builds no new island to ask again.
        val store = EntityStore()
        val vv = VitroView(activity, store, Color.BLACK)
        vv.contextFactory = { theme ->
            VitroCardContext(
                context = activity, haBase = base, store = store, theme = theme, resources = emptyList(),
                accessToken = { null }, callService = { _, _, _, _ -> }, navigate = {}, openMoreInfo = {},
                fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
                needsFrontend = { card, tags, owners -> vv.cardNeedsFrontend(card, tags, owners) },
            )
        }
        vv.setDashboard(LovelaceParser.parseDashboard(views(climateView)), null)
        activity.setContentView(vv)
        idle()
        val island = islandOf(vv, "custom:better-thermostat-normal-climate-card")
        island.handleIslandMessage(needsFrontend(dialTags, emptyList())) { throw AssertionError("no reply") }
        idle()
        assertNotNull(IslandFrontend.reason(emptyList(), "custom:better-thermostat-normal-climate-card"))
        assertTrue("the same island still draws", islandOf(vv, "custom:better-thermostat-normal-climate-card") === island)
    }

    // ---- The way back ----

    private val mapView = """{"title": "Map", "path": "map", "cards": [{"type": "map", "entities": ["light.lounge_lamp"]}]}"""
    private val areasView = """{"title": "Areas", "path": "areas", "strategy": {"type": "areas"}}"""

    @Test fun backFromAMapViewReturnsToTheNativeViewBeforeIt() {
        server.dashboardConfig = views(living, mapView)
        val p = page()
        p.load("$base/wall-test/living")
        awaitMain("ready") { host.messages("ready").isNotEmpty() }
        val vv = vitroView(p)
        tab(vv, "Map").performClick()
        idle()
        val fp = pageFallback()!!
        assertEquals(View.VISIBLE, fp.view.visibility)

        // HA's frontend reports the map view it draws: no hand-back to Vitro (which would only send it here again).
        pageFallbackHost().onLocationChange("$base/wall-test/map")
        idle()
        assertEquals(View.GONE, vv.visibility)
        assertEquals("the map view loaded once", listOf("$base/wall-test/map"), fp.loads)

        // Back: HA's page has no history of its own, so it returns to the native view the tab tap left.
        p.goBack()
        idle()
        assertEquals(View.VISIBLE, vv.visibility)
        assertEquals(View.GONE, fp.view.visibility)
        assertEquals(0, vv.currentViewIndex)
        assertEquals("$base/wall-test/living", host.locations.last())
    }

    @Test fun backAndReloadFromAStrategyViewReturnToTheNativeViewBeforeIt() {
        server.dashboardConfig = views(living, areasView)
        val p = page()
        p.load("$base/wall-test/living")
        awaitMain("ready") { host.messages("ready").isNotEmpty() }
        val vv = vitroView(p)

        p.load("$base/wall-test/areas")
        idle()
        val fp = pageFallback()!!
        assertEquals(View.GONE, vv.visibility)
        assertTrue(p.canGoBack)

        // Reload dashboard returns to the native view, not to the strategy view the page shows.
        p.reload()
        idle()
        assertEquals(View.VISIBLE, vv.visibility)
        assertEquals(0, vv.currentViewIndex)
        assertEquals("not a reload of HA's page", 0, fp.reloads)

        // And Back from the strategy view does too.
        p.load("$base/wall-test/areas")
        idle()
        assertEquals(View.GONE, vv.visibility)
        p.goBack()
        idle()
        assertEquals(View.VISIBLE, vv.visibility)
        assertEquals(0, vv.currentViewIndex)
    }

    @Test fun startingOnAMapViewBackGoesToTheDashboardThePanelIsSetTo() {
        server.dashboardConfig = views(living, mapView)
        val p = page()
        p.load("$base/wall-test/map")
        awaitMain("fallback") { pageFallback() != null }
        val vv = vitroView(p)
        assertEquals(View.GONE, vv.visibility)

        // The map view is no return target: Back goes to the panel's dashboard (its living view).
        p.goBack()
        idle()
        assertEquals(View.VISIBLE, vv.visibility)
        assertEquals(0, vv.currentViewIndex)
    }

    @Test fun aStrategyDashboardHomeAssistantsFrontendReportsIsNotHandedBack() {
        server.dashboardConfig = JSONObject("""{"strategy": {"type": "original-states"}}""")
        val p = page()
        p.load("$base/wall-test")
        awaitMain("fallback") { pageFallback() != null }
        val fetches = server.commands("lovelace/config").size

        pageFallbackHost().onLocationChange("$base/wall-test/0")
        idle()
        assertEquals(View.GONE, vitroView(p).visibility)
        assertEquals("not fetched again", fetches, server.commands("lovelace/config").size)
        assertEquals(listOf("$base/wall-test"), pageFallback()!!.loads)
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
        for (t in listOf("attribute", "cast", "section", "toggle-entity", "custom:slider-entity-row")) {
            assertTrue(t, reason("""{"type": "$t", "name": "x", "entity": "light.b"}""")!!.startsWith("`entities` `entities[1].type: $t` isn't drawn natively"))
        }
        // In a conditional row too.
        assertEquals("`entities` `entities[1].row.type: attribute` isn't drawn natively: a row type Vitro doesn't draw as HA does (section, attribute, cast, an explicit *-entity type or a custom row)", reason(
            """{"type": "conditional", "conditions": [], "row": {"type": "attribute", "entity": "sun.sun", "attribute": "elevation"}}""",
        ))
        // A plain row of a domain HA draws with a control Vitro doesn't have (target temperatures, cover buttons, a
        // date picker, a timer, media controls).
        for (domain in listOf("climate", "cover", "input_datetime", "timer", "media_player", "input_text")) {
            assertTrue(domain, reason("\"$domain.x\"")!!.contains("(a `$domain` entity) isn't drawn natively"))
        }
        // The rows EntityRowControls draws: a slider or a box, a dropdown, Run, Activate, Press, Lock.
        for (domain in listOf("input_number", "number", "input_select", "select", "script", "scene", "button", "input_button", "lock")) {
            assertNull(domain, reason("\"$domain.x\""))
        }
        for (row in listOf(
            """{"type": "divider"}""", """{"entity": "light.b"}""", "\"light.b\"", "\"sensor.b\"", "\"input_boolean.b\"",
            """{"type": "button", "name": "Go", "tap_action": {"action": "toggle"}}""",
            """{"type": "call-service", "name": "Ring", "action": "script.ring"}""",
            """{"type": "text", "name": "Wi-Fi", "text": "Guest"}""",
            """{"type": "weblink", "url": "https://example.com/"}""",
            """{"type": "buttons", "entities": ["light.a", "light.b"]}""",
            """{"type": "conditional", "conditions": [{"condition": "state", "entity": "light.a", "state": "on"}], "row": {"type": "text", "name": "a", "text": "b"}}""",
        )) {
            assertNull(row, reason(row))
        }
        // A button row, or a buttons row's chip, with a double-tap action (Vitro has no double tap yet) or one that
        // opens Assist (Vitro's actions don't).
        assertEquals("`entities` `entities[1].double_tap_action.action: toggle` isn't drawn natively: Vitro has no double tap yet",
            reason("""{"type": "button", "name": "Go", "double_tap_action": {"action": "toggle"}}"""))
        assertEquals("`entities` `entities[1].entities[0].double_tap_action.action: more-info` isn't drawn natively: Vitro has no double tap yet",
            reason("""{"type": "buttons", "entities": [{"entity": "light.a", "double_tap_action": {"action": "more-info"}}]}"""))
        assertEquals("`entities` `entities[1].tap_action.action: assist` isn't drawn natively",
            reason("""{"type": "button", "name": "Ask", "tap_action": {"action": "assist"}}"""))
        assertEquals("`entities` `entities[1].entities[0].hold_action.action: assist` isn't drawn natively",
            reason("""{"type": "buttons", "entities": [{"entity": "light.a", "hold_action": {"action": "assist"}}]}"""))
        // In the cards an entity-filter builds too.
        assertNotNull(WebFallback.viewReason(LovelaceParser.parseView(0, JSONObject(
            """{"cards": [{"type": "entity-filter", "entities": ["light.a"], "card": {"type": "map"}}]}""",
        ))))
    }

    // ---- auto-entities' matches, checked at run time ----

    private fun autoEntitiesView(filter: String) =
        views("""{"title": "Auto", "path": "auto", "cards": [{"type": "custom:auto-entities", "filter": {"include": [$filter]}}]}""")

    private fun withEntity(id: String, state: String) {
        server.initialEntities = JSONObject(server.initialEntities.toString())
            .put(id, JSONObject("""{"s": "$state", "a": {"friendly_name": "Garage"}, "lc": 1700000000.0, "lu": 1700000000.0}"""))
    }

    @Test fun autoEntitiesMatchingOnlyRowsVitroDrawsStaysNative() {
        // A cover in the home, but the filter matches the lights only: every row is one Vitro draws.
        withEntity("cover.garage", "closed")
        server.dashboardConfig = autoEntitiesView("""{"domain": "light"}""")
        val p = page()
        p.load("$base/wall-test/auto")
        awaitMain("ready") { host.messages("ready").isNotEmpty() }
        idle()
        assertNull("no fallback page", pageFallback())
        assertEquals(View.VISIBLE, vitroView(p).visibility)
        assertTrue(hasText(vitroView(p), "Lounge Lamp"))
    }

    @Test fun autoEntitiesMatchingARowVitroDoesntDrawOpensTheViewInHomeAssistantsFrontend() {
        // The config passes the strict rule, but what it matches (a cover row, which HA draws with its buttons) doesn't.
        withEntity("cover.garage", "closed")
        server.dashboardConfig = autoEntitiesView("""{"domain": "cover"}""")
        assertNull("the config alone is native", WebFallback.viewReason(LovelaceParser.parseView(0, server.dashboardConfig.getJSONArray("views").getJSONObject(0))))
        page().load("$base/wall-test/auto")
        awaitMain("fallback") { pageFallback() != null }
        assertEquals(listOf("$base/wall-test/auto"), pageFallback()!!.loads)
    }

    // A slow Home Assistant, or a slow panel: HA's first `subscribe_entities` event (every state) lands late. Held back
    // ([FakeHaServer.holdEntitySnapshot]) and sent by the test, before or after the view's wait for it runs out.

    /** The page has asked HA for every entity (an auto-entities view follows them all). */
    private fun awaitEveryEntitySubscribed() =
        awaitMain("every entity subscribed") { server.commands("subscribe_entities").any { !it.has("entity_ids") } }

    /** HA's held first event for every entity: its snapshot, on the newest connection. */
    private fun sendHeldSnapshot() {
        val id = server.sockets.last().received.last { it.optString("type") == "subscribe_entities" && !it.has("entity_ids") }.optInt("id")
        server.pushEvent(id, JSONObject().put("a", server.initialEntities))
    }

    /** The view's wait for its first states runs out ([VitroView.holdFirstBuildForStatesMs]): it builds without them. */
    private fun waitRunsOut() = shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1_500))

    @Test fun anAutoEntitiesViewWaitsForItsStatesAndIsReadyWithItsRows() {
        // Built before its states, an auto-entities view would match nothing, and say it's ready with no rows.
        withEntity("cover.garage", "closed")
        server.holdEntitySnapshot = true
        server.dashboardConfig = autoEntitiesView("""{"domain": "light"}""")
        val p = page()
        p.load("$base/wall-test/auto")
        awaitEveryEntitySubscribed()
        idle()
        assertTrue("not ready before its states", host.messages("ready").isEmpty())

        sendHeldSnapshot()
        awaitMain("ready") { host.messages("ready").isNotEmpty() }
        assertNull("no fallback page", pageFallback())
        assertTrue("its rows are there when it's ready", hasText(vitroView(p), "Lounge Lamp"))
    }

    @Test fun anAutoEntitiesViewWhoseLateStatesMatchARowVitroDoesntDrawOpensInHomeAssistantsFrontend() {
        withEntity("cover.garage", "closed")
        server.holdEntitySnapshot = true
        server.dashboardConfig = autoEntitiesView("""{"domain": "cover"}""")
        page().load("$base/wall-test/auto")
        awaitEveryEntitySubscribed()
        idle()
        assertNull(pageFallback())

        sendHeldSnapshot()
        awaitMain("fallback") { pageFallback() != null }
        assertEquals(listOf("$base/wall-test/auto"), pageFallback()!!.loads)
    }

    @Test fun statesThatLandAfterTheWaitRanOutFillAnAutoEntitiesViewBuiltWithout() {
        withEntity("cover.garage", "closed")
        server.holdEntitySnapshot = true
        server.dashboardConfig = autoEntitiesView("""{"domain": "light"}""")
        val p = page()
        p.load("$base/wall-test/auto")
        awaitEveryEntitySubscribed()
        waitRunsOut()
        awaitMain("ready") { host.messages("ready").isNotEmpty() }
        assertFalse("built without states: nothing matched yet", hasText(vitroView(p), "Lounge Lamp"))

        sendHeldSnapshot()
        awaitMain("the matched row") { hasText(vitroView(p), "Lounge Lamp") }
        assertNull("no fallback page", pageFallback())
    }

    @Test fun statesThatLandAfterTheWaitRanOutAndMatchARowVitroDoesntDrawOpenTheViewInHomeAssistantsFrontend() {
        withEntity("cover.garage", "closed")
        server.holdEntitySnapshot = true
        server.dashboardConfig = autoEntitiesView("""{"domain": "cover"}""")
        page().load("$base/wall-test/auto")
        awaitEveryEntitySubscribed()
        waitRunsOut()
        awaitMain("ready") { host.messages("ready").isNotEmpty() }
        assertNull("nothing matched yet: native", pageFallback())

        sendHeldSnapshot()
        awaitMain("fallback") { pageFallback() != null }
        assertEquals(listOf("$base/wall-test/auto"), pageFallback()!!.loads)
    }

    @Test fun anEntityThatAppearsAndMatchesARowVitroDoesntDrawSendsTheViewOnScreenToHomeAssistantsFrontend() {
        server.dashboardConfig = autoEntitiesView("""{"entity_id": "/^(light|cover)\\./"}""")
        val p = page()
        p.load("$base/wall-test/auto")
        awaitMain("ready") { host.messages("ready").isNotEmpty() }
        idle()
        assertNull("only the lamp matches yet: native", pageFallback())

        // A cover joins Home Assistant: the filter matches it, and its row is one Vitro doesn't draw.
        server.pushEvent(server.lastId("subscribe_entities")!!, JSONObject().put("a", JSONObject().put(
            "cover.garage", JSONObject("""{"s": "open", "a": {"friendly_name": "Garage"}, "lc": 1700000000.0, "lu": 1700000000.0}"""),
        )))
        awaitMain("fallback") { pageFallback() != null }
        assertEquals(listOf("$base/wall-test/auto"), pageFallback()!!.loads)
    }

    @Test fun theMatchedRowsAreDecidedFromTheStatesOfNow() {
        val view = LovelaceParser.parseView(0, JSONObject(
            """{"cards": [{"type": "vertical-stack", "cards": [{"type": "custom:auto-entities", "filter": {"include": [{"domain": "/^(light|cover)$/"}]}}]}]}""",
        ))
        val lamp = EntityState("light.lamp", "on", mapOf("friendly_name" to "Lamp"))
        val garage = EntityState("cover.garage", "closed", mapOf("friendly_name" to "Garage"))
        fun reason(vararg states: EntityState) = WebFallback.matchedRowsReason(view, IslandScripts.ANY, states.toList()) { id -> states.firstOrNull { it.entityId == id } }
        assertNull(reason(lamp))
        assertEquals(
            "a row `custom:auto-entities` matched: `entities` `entities[0]` (a `cover` entity) isn't drawn natively: HA draws this domain's row with its own control or picture",
            reason(lamp, garage),
        )
        // The cover gone again: native again (nothing is remembered).
        assertNull(reason(lamp))
        // Under `card_param: cards` a matched card is checked with its entity: a tile of any domain is drawn.
        val tiles = LovelaceParser.parseView(0, JSONObject(
            """{"cards": [{"type": "custom:auto-entities", "card_param": "cards", "card": {"type": "grid"}, "filter": {"include": [{"domain": "cover"}]}}]}""",
        ))
        assertNull(WebFallback.matchedRowsReason(tiles, IslandScripts.ANY, listOf(garage)) { garage.takeIf { g -> g.entityId == it } })
        // A rule's own `options` are what the config can't check: a double tap on a matched row goes to HA's frontend.
        val options = LovelaceParser.parseView(0, JSONObject(
            """{"cards": [{"type": "custom:auto-entities", "filter": {"include": [{"domain": "light", "options": {"double_tap_action": {"action": "toggle"}}}]}}]}""",
        ))
        assertNull(WebFallback.viewReason(options))
        assertNotNull(WebFallback.matchedRowsReason(options, IslandScripts.ANY, listOf(lamp)) { lamp.takeIf { l -> l.entityId == it } })
        // A view without auto-entities isn't walked.
        assertNull(WebFallback.matchedRowsReason(LovelaceParser.parseView(0, JSONObject("""{"cards": [{"type": "tile", "entity": "cover.garage"}]}""")),
            IslandScripts.ANY, listOf(garage)) { null })
    }

    @Test fun aNestedAutoEntitiesHasItsOwnMatchesCheckedToo() {
        // The outer card matches lights; its `card:` is another auto-entities that adds covers to the lights it's given.
        val view = LovelaceParser.parseView(0, JSONObject(
            """{"cards": [{"type": "custom:auto-entities", "filter": {"include": [{"domain": "light"}]},
                "card": {"type": "custom:auto-entities", "filter": {"include": [{"domain": "cover"}]}}}]}""",
        ))
        val lamp = EntityState("light.lamp", "on", mapOf("friendly_name" to "Lamp"))
        val garage = EntityState("cover.garage", "closed", mapOf("friendly_name" to "Garage"))
        fun reason(v: dev.glassworks.engine.vitro.model.ViewConfig, vararg states: EntityState) =
            WebFallback.matchedRowsReason(v, IslandScripts.ANY, states.toList()) { id -> states.firstOrNull { it.entityId == id } }
        assertNull(reason(view, lamp))
        assertEquals(
            "a row `custom:auto-entities` matched: `entities` `entities[1]` (a `cover` entity) isn't drawn natively: HA draws this domain's row with its own control or picture",
            reason(view, lamp, garage),
        )
        // Two levels down, inside a stack: the innermost one's cover is found as well.
        val deep = LovelaceParser.parseView(0, JSONObject(
            """{"cards": [{"type": "vertical-stack", "cards": [{"type": "custom:auto-entities", "filter": {"include": [{"domain": "light"}]},
                "card": {"type": "custom:auto-entities", "filter": {"include": [{"domain": "switch"}]},
                  "card": {"type": "custom:auto-entities", "filter": {"include": [{"domain": "cover"}]}}}}]}]}""",
        ))
        assertNull(reason(deep, lamp))
        assertNotNull(reason(deep, lamp, garage))
    }

    @Test fun theReportSaysAnAutoEntitiesViewIsNativeIfItsMatchedRowsAre() {
        val view = LovelaceParser.parseView(0, JSONObject("""{"cards": [{"type": "custom:auto-entities", "filter": {"include": [{"domain": "light"}]}}]}"""))
        val report = CompatChecker.check(view)
        assertTrue(report.format(), report.notes.any { it.second == WebFallback.MATCHED_ROWS_NOTE })
        assertTrue(report.format(), WebFallback.MATCHED_ROWS_NOTE in report.format())
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
        )) {
            assertNull(card, reason(card))
        }
        // Vitro's actions don't open Assist, and it has no double tap yet.
        assertEquals("a `shortcut` card that opens Assist", reason("""{"type": "shortcut", "label": "Ask", "icon": "mdi:microphone", "tap_action": {"action": "assist"}}"""))
        assertEquals("`shortcut` `double_tap_action.action: navigate` isn't drawn natively: Vitro has no double tap yet",
            reason("""{"type": "shortcut", "label": "Go", "icon": "mdi:home", "double_tap_action": {"action": "navigate", "navigation_path": "/x"}}"""))
        // HA's own pages can't be framed from a loopback page (HA sends X-Frame-Options: SAMEORIGIN).
        assertEquals("an `iframe` card of a Home Assistant page", reason("""{"type": "iframe", "url": "/local/plan.html"}"""))
        assertEquals("an `iframe` card of a Home Assistant page", reason("""{"type": "iframe", "url": "plan.html"}"""))
        assertNull(reason("""{"type": "iframe", "url": "https://example.com/"}"""))
        assertEquals("`alert` `tap_action.action: assist` isn't drawn natively", reason("""{"type": "alert", "entity": "binary_sensor.d", "tap_action": {"action": "assist"}}"""))
        assertEquals("`statistic` `footer` isn't drawn natively", reason(
            """{"type": "statistic", "entity": "sensor.e", "stat_type": "change", "period": {}, "footer": {"type": "graph", "entity": "sensor.e"}}""",
        ))
        assertEquals("`alert` `double_tap_action.action: more-info` isn't drawn natively: Vitro has no double tap yet",
            reason("""{"type": "alert", "entity": "binary_sensor.d", "double_tap_action": {"action": "more-info"}}"""))
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
        "sensor" -> """{"type": "$type", "entity": "sensor.power"}"""
        "thermostat" -> """{"type": "$type", "entity": "climate.lounge"}"""
        "humidifier" -> """{"type": "$type", "entity": "humidifier.lounge"}"""
        "weather-forecast" -> """{"type": "$type", "entity": "weather.home", "forecast_type": "daily"}"""
        "media-control" -> """{"type": "$type", "entity": "media_player.lounge"}"""
        "alarm-panel" -> """{"type": "$type", "entity": "alarm_control_panel.home"}"""
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
