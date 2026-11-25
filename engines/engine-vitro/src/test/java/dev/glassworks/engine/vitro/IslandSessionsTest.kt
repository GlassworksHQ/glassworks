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
import android.widget.FrameLayout
import dev.glassworks.engine.EngineSettings
import dev.glassworks.engine.Page
import dev.glassworks.engine.vitro.cards.WebIslandCardView
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.data.HaDashboardClient
import dev.glassworks.engine.vitro.island.VitroIslandServer
import dev.glassworks.engine.vitro.model.LovelaceParser
import dev.glassworks.engine.vitro.theme.ThemeCatalog
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.VitroCardContext
import dev.glassworks.engine.vitro.ui.VitroView
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.net.HttpURLConnection
import java.net.URL

/**
 * One browser page per island card, open only while the card is built (the external review's claim 2 on #799): a
 * reconnect, an unchanged config or a theme change keeps the pages; a card that changes or goes closes its page;
 * a view that isn't on screen pauses its islands.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class IslandSessionsTest {
    private val base = FakeHaServer.BASE
    private val settings = EngineSettings(secureHosts = listOf("192.0.2.10"), zoom = 1f, remoteDebugging = false, dashboard = "$base/wall-test/living")
    private lateinit var controller: ActivityController<Activity>
    private lateinit var activity: Activity
    private val host = RecordingHost()
    private val fallback = RecordingEngine()
    private val server = FakeHaServer()
    private val engines = ArrayList<VitroEngine>()
    private val opened = ArrayList<Page>()
    private val islandServers = ArrayList<VitroIslandServer>()

    @Before fun setUp() {
        controller = Robolectric.buildActivity(Activity::class.java).setup()
        activity = controller.get()
    }

    @After fun tearDown() {
        engines.forEach { runCatching { it.islandServer.stop() } }
        islandServers.forEach { runCatching { it.stop() } }
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

    private fun engine() = VitroEngine(
        fallback = fallback, settings = { settings }, enabled = { true },
        tokenProvider = { _, done -> done(FakeHaServer.TOKEN) }, socketFactory = server::connect,
    ).also { engines += it }

    private fun page(e: VitroEngine = engine()): Page = e.open(activity, host, Color.BLACK).also {
        opened += it
        activity.setContentView(it.view)
        idle()
    }

    private fun vitroView(page: Page): VitroView = (page.view as ViewGroup).getChildAt(0) as VitroView

    /** The pages opened for islands (with the island host, not the activity's), oldest first. */
    private fun islandPages(): List<RecordingPage> =
        fallback.opens.indices.filter { fallback.opens[it].host !== host }.map { fallback.pages[it] }

    private fun openIslandPages() = islandPages().filterNot { it.closed }

    /** The island cards built in [page]'s views, shown or cached. */
    private fun islandCards(page: Page): List<WebIslandCardView> {
        val out = ArrayList<WebIslandCardView>()
        fun walk(v: View) {
            if (v is WebIslandCardView) out += v else if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(vitroView(page))
        return out
    }

    @Suppress("UNCHECKED_CAST")
    private fun slots(s: VitroIslandServer): Map<String, VitroIslandServer.SlotHandler> =
        s.javaClass.getDeclaredField("slots").apply { isAccessible = true }.get(s) as Map<String, VitroIslandServer.SlotHandler>

    private fun status(url: String): Int = (URL(url).openConnection() as HttpURLConnection).run {
        try { responseCode } finally { disconnect() }
    }

    private fun readyCount() = host.messages("ready").size

    private fun lovelaceUpdated() =
        server.pushEvent(server.eventSubscriptionId("lovelace_updated")!!, JSONObject().put("event_type", "lovelace_updated"))

    /** Every island page still open is one a built card holds, and on screen in the tree. */
    private fun assertOnePagePerIsland(page: Page) {
        val open = openIslandPages()
        assertEquals("one open page per island card", islandCards(page).size, open.size)
        assertTrue("every open page is in the view tree", open.all { it.view.isAttachedToWindow })
        assertTrue("every closed page left it", islandPages().filter { it.closed }.none { it.view.isAttachedToWindow })
    }

    @Test fun aReconnectALovelaceUpdatedAndAThemeChangeKeepTheIslandsPage() {
        val p = page()
        p.load("$base/wall-test/wall")
        awaitMain("ready") { readyCount() == 1 && islandPages().size == 1 }
        val first = islandPages().single()
        val card = islandCards(p).single()

        // HA restarts or Wi-Fi blips: the client reconnects and fetches the same config.
        server.dropConnection()
        awaitMain("refetched after reconnect") { readyCount() >= 2 }
        idle()
        assertSame("the same config builds nothing", card, islandCards(p).single())

        // lovelace_updated for an edit that didn't change this dashboard.
        lovelaceUpdated()
        awaitMain("refetched after lovelace_updated") { readyCount() >= 3 }
        idle()
        assertSame(card, islandCards(p).single())

        // The user's theme changes: the native cards are built again, the island keeps its page.
        val slotsBefore = slots(engines.single().islandServer).toMap()
        vitroView(p).setUserTheme("night", true)
        idle()
        assertNotSame("the view was built again in the new theme", card, islandCards(p).single())
        assertEquals("one island card, four builds: one browser page", 1, islandPages().size)
        assertFalse(first.closed)
        assertTrue(first.view.isAttachedToWindow)
        assertEquals("only the page's first load", 1, first.loads.size)

        // Its slot now answers to the new card, which restyles the page to the new theme first thing on connect.
        val slotsAfter = slots(engines.single().islandServer)
        assertEquals(slotsBefore.keys, slotsAfter.keys)
        assertNotSame(slotsBefore.values.single(), slotsAfter.values.single())
        val sent = ArrayList<JSONObject>()
        slotsAfter.values.single().onConnected { sent += it }
        assertEquals("theme", sent.first().getString("type"))
        assertTrue(sent.first().getBoolean("dark"))
        assertTrue("night's primary color", sent.first().getString("css").contains("#123456"))
        assertEquals(listOf("theme", "auth", "snapshot"), sent.take(3).map { it.getString("type") })
        assertOnePagePerIsland(p)
    }

    @Test fun anEditedIslandCardGetsANewPageAndTheOldOneClosesForGood() {
        val p = page()
        p.load("$base/wall-test/wall")
        awaitMain("ready") { readyCount() == 1 && islandPages().size == 1 }
        val first = islandPages().single()
        val firstUrl = first.loads.single()
        assertEquals(200, status(firstUrl))

        server.dashboardConfig = JSONObject(FakeHaServer.DASHBOARD_JSON.replace("\"Lounge\"", "\"Living room\""))
        lovelaceUpdated()
        awaitMain("the edit is built") { readyCount() >= 2 && islandPages().size == 2 }
        idle()

        assertTrue("the edited card's old page is closed", first.closed)
        assertFalse(islandPages()[1].closed)
        // Its slot is gone: the page's bridge (were it still running) gets a 404 and stops retrying.
        assertEquals(404, status(firstUrl))
        assertEquals(404, status(firstUrl.replace("/__vitro/island/", "/__vitro/ws/")))
        assertOnePagePerIsland(p)
    }

    @Test fun anEditToAnotherCardKeepsTheIslandsPage() {
        val p = page()
        p.load("$base/wall-test/wall")
        awaitMain("ready") { readyCount() == 1 && islandPages().size == 1 }
        val first = islandPages().single()

        server.dashboardConfig = JSONObject(FakeHaServer.DASHBOARD_JSON.replace("\"title\": \"Lights\"", "\"title\": \"Lamps\""))
        lovelaceUpdated()
        awaitMain("the edit is built") { readyCount() >= 2 }
        idle()

        assertEquals(1, islandPages().size)
        assertFalse(first.closed)
        assertOnePagePerIsland(p)
    }

    @Test fun aCardRemovedFromTheDashboardClosesItsPage() {
        val p = page()
        p.load("$base/wall-test/wall")
        awaitMain("ready") { readyCount() == 1 && islandPages().size == 1 }
        val first = islandPages().single()

        server.dashboardConfig = JSONObject("""{"views": [{"path": "wall", "cards": [{"type": "tile", "entity": "light.lounge_lamp"}]}]}""")
        lovelaceUpdated()
        awaitMain("the edit is built") { readyCount() >= 2 }
        idle()

        assertTrue(first.closed)
        assertTrue(openIslandPages().isEmpty())
        assertTrue(slots(engines.single().islandServer).isEmpty())
    }

    @Test fun aReloadBuildsTheDashboardAgainAndTheIslandKeepsItsPage() {
        val p = page()
        p.load("$base/wall-test/wall")
        awaitMain("ready") { readyCount() == 1 && islandPages().size == 1 }
        val card = islandCards(p).single()

        p.reload()
        awaitMain("reloaded") { readyCount() >= 2 }
        idle()

        assertNotSame("a reload the user asked for builds again", card, islandCards(p).single())
        assertEquals(1, islandPages().size)
        assertOnePagePerIsland(p)
    }

    @Test fun aThemeTheDashboardDoesNotUseBuildsNothing() {
        val p = page()
        p.load("$base/wall-test/wall")
        awaitMain("ready") { readyCount() == 1 && islandPages().size == 1 }
        val view = vitroView(p)
        val card = islandCards(p).single()
        val catalog = view.themeCatalog

        view.setThemes(catalog.copy(themes = catalog.themes + ("unused" to ThemeCatalog.ThemeDefinition(mapOf("primary-color" to "#00ff00")))))
        idle()
        assertSame(card, islandCards(p).single())

        // The theme in force changes: built again.
        val inForce = ThemeResolver.pickedName(view.themeCatalog, view.darkMode, view.userThemeName)
        view.setThemes(view.themeCatalog.copy(themes = view.themeCatalog.themes + (inForce to ThemeCatalog.ThemeDefinition(mapOf("primary-color" to "#0000ff")))))
        idle()
        assertNotSame(card, islandCards(p).single())
        assertEquals(1, islandPages().size)
    }

    private val twoViews = """{"views": [
        {"path": "a", "cards": [{"type": "custom:acme-a-card", "entity": "light.lounge_lamp"}]},
        {"path": "b", "cards": [{"type": "custom:acme-b-card", "entity": "sensor.kitchen_temp"}]}]}"""

    @Test fun aViewSwitchPausesTheHiddenViewsIslandsAndShowsThemAgain() {
        server.dashboardConfig = JSONObject(twoViews)
        val p = page()
        p.load("$base/wall-test/a")
        awaitMain("ready") { readyCount() == 1 && islandPages().size == 1 }
        val a = islandPages().single()
        assertTrue(a.active)

        p.load("$base/wall-test/b")
        awaitMain("view b built") { islandPages().size == 2 }
        val b = islandPages()[1]
        assertFalse("a's view is hidden: its island stops", a.active)
        assertTrue(b.active)
        assertFalse("a hidden view's island keeps its page", a.closed)

        p.load("$base/wall-test/a")
        idle()
        assertTrue(a.active)
        assertFalse(b.active)
        assertEquals("a cached view shows its islands again, with no new page", 2, islandPages().size)
        assertOnePagePerIsland(p)
    }

    @Test fun aPageInTheFallbackPausesEveryIsland() {
        server.dashboardConfig = JSONObject(twoViews)
        val p = page()
        p.load("$base/wall-test/a")
        awaitMain("ready") { readyCount() == 1 && islandPages().size == 1 }
        val a = islandPages().single()

        p.load("$base/energy")
        idle()
        assertFalse("under Home Assistant's own page the islands stop", a.active)
        assertFalse(a.closed)

        p.load("$base/wall-test/a")
        idle()
        assertTrue(a.active)

        // Behind another app no island runs, and the one on screen comes back with the panel.
        controller.pause().stop()
        idle()
        assertFalse(a.active)
        controller.restart().resume()
        idle()
        assertTrue(a.active)
    }

    @Test fun theActivityGoingClosesEveryIslandAndTheFallbackPage() {
        server.dashboardConfig = JSONObject(twoViews)
        val p = page()
        p.load("$base/wall-test/a")
        awaitMain("ready") { readyCount() == 1 && islandPages().size == 1 }
        p.load("$base/wall-test/b")
        awaitMain("view b built") { islandPages().size == 2 }
        p.load("$base/energy")
        idle()
        val fallbackPage = fallback.pages[fallback.opens.indexOfFirst { it.host === host }]

        controller.pause().stop().destroy()
        idle()

        assertTrue(islandPages().all { it.closed })
        assertTrue(fallbackPage.closed)
        assertTrue(slots(engines.single().islandServer).isEmpty())
    }

    // ---- The card on its own

    private fun context(
        store: EntityStore,
        server: VitroIslandServer,
        dark: Boolean,
        pages: MutableList<RecordingPage>,
        token: () -> String? = { null },
        calls: MutableList<String> = ArrayList(),
    ) = VitroCardContext(
        context = activity, haBase = base, store = store, theme = ThemeResolver.resolve(ThemeCatalog(), dark),
        resources = emptyList(), accessToken = token, callService = { d, s, _, _ -> calls += "$d.$s" }, navigate = {},
        openMoreInfo = {}, fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
        islandServer = server, openIslandPage = { RecordingPage(activity).also { pages += it } },
        watchAllEntities = { calls += "watchAll" },
        watchRegistries = { _ -> calls += "registries"; { calls += "registries released" } },
    )

    private fun lamp(state: String) = dev.glassworks.engine.vitro.data.EntityState("light.lounge_lamp", state, emptyMap())

    private val cardJson = """{"type": "custom:acme-card", "entity": "light.lounge_lamp"}"""

    @Test fun aCardMovedWithinTheLayoutKeepsItsIslandAndOneThatLeavesClosesIt() {
        val islands = VitroIslandServer(haBase = { base }, accessToken = { null }).also { islandServers += it }
        val pages = ArrayList<RecordingPage>()
        val island = WebIslandCardView(context(EntityStore(), islands, dark = true, pages), LovelaceParser.parseCard(JSONObject(cardJson)))
        val left = FrameLayout(activity)
        val right = FrameLayout(activity)
        activity.setContentView(FrameLayout(activity).apply { addView(left); addView(right) })
        left.addView(island)
        idle()

        val sent = ArrayList<JSONObject>()
        slots(islands).values.single().onConnected { sent += it }
        sent.clear()

        // A layout that moves its cards between columns detaches and attaches them at once.
        left.removeView(island)
        right.addView(island)
        idle()
        assertFalse(pages.single().closed)
        assertEquals(1, slots(islands).size)
        // The page catches up on what changed while the card was off the tree.
        assertEquals(listOf("snapshot"), sent.map { it.getString("type") })

        right.removeView(island)
        idle()
        assertTrue("a card that left the tree closes its page", pages.single().closed)
        assertTrue(slots(islands).isEmpty())
    }

    @Test fun aHandedOffIslandMovesToTheNextBuildOfTheCardInItsTheme() {
        val islands = VitroIslandServer(haBase = { base }, accessToken = { null }).also { islandServers += it }
        val pages = ArrayList<RecordingPage>()
        val calls = ArrayList<String>()
        val store = EntityStore()
        val config = LovelaceParser.parseCard(JSONObject(cardJson))
        val old = WebIslandCardView(context(store, islands, dark = true, pages, calls = calls), config)
        val page = pages.single()
        // The page walks hass.states and reads the registries: it asks for both once.
        old.handleIslandMessage(JSONObject().put("type", "watchAll")) {}
        old.handleIslandMessage(JSONObject().put("type", "registries")) {}
        assertEquals(listOf("watchAll", "registries"), calls)
        calls.clear()

        val handed = old.handOff()!!
        assertEquals("the old card gives the registry feed back", listOf("registries released"), calls)
        calls.clear()
        // Until the next build takes it, what the page sends reaches no card.
        slots(islands).values.single().onMessage(JSONObject().put("type", "callService").put("domain", "light").put("service", "turn_off")) {}
        assertTrue(calls.isEmpty())
        assertEquals(WebIslandCardView.keyOf(config, false), handed.key)
        assertTrue("the page left the old card", page.view.parent == null)
        old.release() // the old card going does nothing to the page it handed off
        assertFalse(page.closed)

        val next = WebIslandCardView(context(store, islands, dark = false, pages, calls = calls), LovelaceParser.parseCard(JSONObject(cardJson)), adopted = handed)
        assertEquals("no new page", 1, pages.size)
        assertEquals("the new card keeps up what the page asked for", listOf("watchAll", "registries"), calls)
        assertSame(next, page.view.parent)
        assertEquals("loaded once, by the first card", 1, page.loads.size)
        val sent = ArrayList<JSONObject>()
        slots(islands).values.single().onConnected { sent += it }
        assertEquals("theme", sent.first().getString("type"))
        assertFalse("the new card's light theme", sent.first().getBoolean("dark"))

        next.release()
        assertTrue(page.closed)
        assertTrue(slots(islands).isEmpty())
    }

    @Test fun aHandedOffIslandWhoseSlotIsGoneClosesAndTheCardOpensANewPage() {
        val islands = VitroIslandServer(haBase = { base }, accessToken = { null }).also { islandServers += it }
        val pages = ArrayList<RecordingPage>()
        val store = EntityStore()
        val old = WebIslandCardView(context(store, islands, dark = true, pages), LovelaceParser.parseCard(JSONObject(cardJson)))
        val handed = old.handOff()!!
        handed.close()

        WebIslandCardView(context(store, islands, dark = true, pages), LovelaceParser.parseCard(JSONObject(cardJson)), adopted = handed)
        assertEquals(2, pages.size)
        assertTrue(pages[0].closed)
        assertFalse(pages[1].closed)
        assertEquals(1, slots(islands).size)
    }

    @Test fun aRefreshedSignInReachesAPageThatStays() {
        val islands = VitroIslandServer(haBase = { base }, accessToken = { null }).also { islandServers += it }
        val store = EntityStore().apply { putLocal(lamp("on")) }
        var token = "first-token"
        WebIslandCardView(context(store, islands, dark = true, ArrayList(), token = { token }), LovelaceParser.parseCard(JSONObject(cardJson)))
        val sent = ArrayList<JSONObject>()
        slots(islands).values.single().onConnected { sent += it }
        assertEquals(listOf("theme", "auth", "snapshot"), sent.map { it.getString("type") })
        assertEquals("first-token", sent[1].getString("token"))
        sent.clear()

        // A reconnect refreshed the token, and the dashboard (and this page) stayed.
        token = "second-token"
        store.putLocal(lamp("off"))
        assertEquals(listOf("auth", "patch"), sent.map { it.getString("type") })
        assertEquals("second-token", sent[0].getString("token"))
        sent.clear()
        store.putLocal(lamp("on"))
        assertEquals("sent once", listOf("patch"), sent.map { it.getString("type") })
    }

    @Test fun anIslandPageStopsRetryingItsBridgeOnceItsSlotIsGone() {
        val html = WebIslandCardView.buildIslandHtml(
            context(EntityStore(), VitroIslandServer(haBase = { base }, accessToken = { null }).also { islandServers += it }, true, ArrayList()),
            LovelaceParser.parseCard(JSONObject(cardJson)),
        )
        // The retry asks the slot first, and a 404 (the card closed it) ends the loop.
        assertTrue(html.contains("function retryBridge()"))
        assertTrue(html.contains("if (r.status !== 404) connectBridge();"))
        assertTrue(html.contains("<style id=\"vitro-theme\">"))
    }
}
