/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.app.Application
import android.view.View
import android.view.ViewGroup
import dev.glassworks.engine.vitro.cards.WebIslandCardView
import dev.glassworks.engine.vitro.compat.IslandCardSizes
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.model.HaCardSize
import dev.glassworks.engine.vitro.model.LovelaceParser
import dev.glassworks.engine.vitro.model.LovelaceResource
import dev.glassworks.engine.vitro.model.LovelaceResourceList
import dev.glassworks.engine.vitro.ui.HaMasonry
import dev.glassworks.engine.vitro.ui.VitroCardContext
import dev.glassworks.engine.vitro.ui.VitroView
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * What tablet Vitro run 3 found in island cards against HA's render: the column count of a masonry view of island
 * cards (their own `getCardSize`, [IslandCardSizes]), and the layout Bubble Card picks from HA's page
 * ([WebIslandCardView.islandConfig]). The island page's own elements (ha-card, ha-state-icon) are checked in a browser:
 * tools/vitro-parity/test/island-detect.e2e.test.mjs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class IslandParityTest {
    @After fun tearDown() = IslandCardSizes.clear()

    private fun card(json: String) = LovelaceParser.parseCard(JSONObject(json))

    /** The run's `islands-12` view: a markdown card, then twelve custom cards whose getCardSize answers 2. */
    private val islandsView = listOf(card("""{"type": "markdown", "content": "Island view: 12 custom cards."}""")) +
        (1..12).map { card("""{"type": "custom:vitro-test-plain-card", "entity": "sensor.island_%02d"}""".format(it)) }

    @Test fun aMasonryViewWeighsAnIslandCardByItsOwnGetCardSize() {
        // Before an island says, a custom card weighs 1 (HA's computeCardSize for a card without an answer): the cards
        // fill three of the tablet's four columns and the fourth is dropped. HA asks the card, which says 2: four.
        assertEquals(1.0, HaCardSize.of(islandsView[1]), 0.0)
        assertEquals(3, HaMasonry.place(islandsView, 1280).second)
        for (c in islandsView.drop(1)) assertTrue(IslandCardSizes.record(c.raw, 2.0))
        assertFalse("the same answer again changes nothing", IslandCardSizes.record(islandsView[1].raw, 2.0))
        assertEquals(2.0, HaCardSize.of(islandsView[1]), 0.0)
        val (cols, n) = HaMasonry.place(islandsView, 1280)
        assertEquals(4, n)
        // As HA's render: the markdown card and 18.5, 19.0 and 24.0 in the first column, three in each other.
        assertEquals(listOf(0, 0, 0, 1, 1, 1, 2, 2, 2, 3, 3, 3, 0), cols.toList())
        // The size is the card's: another config (another entity) isn't known.
        assertEquals(1.0, HaCardSize.of(card("""{"type": "custom:vitro-test-plain-card", "entity": "sensor.other"}""")), 0.0)
        // A stack of island cards adds them up, as HA's vertical stack does.
        val stack = card("""{"type": "vertical-stack", "cards": [${islandsView[1].raw}, ${islandsView[2].raw}]}""")
        assertEquals(4.0, HaCardSize.of(stack), 0.0)
    }

    @Test @Config(qualifiers = "w1280dp-h800dp-mdpi")
    fun theViewOnScreenIsPlacedAgainWhenAnIslandsSizeMovesACard() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val store = EntityStore().apply {
            for (i in 1..12) putLocal(EntityState("sensor.island_%02d".format(i), "${17 + i * 0.5}", emptyMap()))
        }
        val vv = VitroView(activity, store, 0xFF000000.toInt())
        vv.holdFirstBuildForStatesMs = 0
        vv.resourcesList = LovelaceResourceList(listOf(LovelaceResource("/local/test-cards.js", "module")), bubbleModules = false)
        var builds = 0
        vv.onMetrics = { builds++ }
        vv.contextFactory = { theme ->
            VitroCardContext(
                context = activity, haBase = FakeHaServer.BASE, store = store, theme = theme, resources = vv.resourcesList,
                accessToken = { FakeHaServer.TOKEN }, callService = { _, _, _, _ -> }, navigate = {}, openMoreInfo = {},
                fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
            )
        }
        activity.setContentView(vv)
        val view = JSONObject().put("cards", org.json.JSONArray(islandsView.map { it.raw }))
        vv.setDashboard(LovelaceParser.parseDashboard(JSONObject().put("views", org.json.JSONArray().put(view))), null)
        ShadowLooper.idleMainLooper()
        val width = (activity.resources.displayMetrics.widthPixels / activity.resources.displayMetrics.density).toInt()
        val (_, before) = HaMasonry.place(islandsView, width)
        assertEquals(1, builds)
        val root = vv.currentRoot()
        // An answer that places every card the same (one card, still under 5 in its column) lays nothing out again.
        vv.islandCardSize(islandsView[12], 1.0)
        ShadowLooper.idleMainLooper()
        assertEquals(1, builds)
        assertSame(root, vv.currentRoot())
        // Every island answers 2: on a screen with more than one column, the columns change, and the view is placed again.
        for (c in islandsView.drop(1)) vv.islandCardSize(c, 2.0)
        ShadowLooper.idleMainLooper()
        val (_, after) = HaMasonry.place(islandsView, width)
        assertEquals(1280, width)
        assertEquals(3 to 4, before to after)
        assertEquals("placed again, once per answer that moved a card", true, builds in 2..12)
        assertEquals(4, vv.masonryColumns())
    }

    // ---- The sizes islands gave, kept on disk for the next start ----

    private val home = "http://192.0.2.10:8123"

    @Test fun theSizesIslandsGaveAreThereAtTheNextStartForTheSameHomeAssistantOnly() {
        val cache = java.nio.file.Files.createTempDirectory("vitro-card-sizes").toFile()
        try {
            IslandCardSizes.useStore(cache, home)
            for (c in islandsView.drop(1)) IslandCardSizes.record(c.raw, 2.0)
            // A built-in card never goes to an island: nothing is kept for it.
            IslandCardSizes.record(islandsView[0].raw, 4.0)
            IslandCardSizes.awaitIo()
            val file = java.io.File(cache, "vitro-card-sizes/sizes.json")
            val text = file.readText()
            assertFalse("digests only, never a config", text.contains("sensor.island") || text.contains("vitro-test-plain-card"))
            assertFalse("nor the Home Assistant's address", text.contains("192.0.2.10"))

            // The next start: nothing in memory, the same Home Assistant.
            IslandCardSizes.clear()
            assertEquals(1.0, HaCardSize.of(islandsView[1]), 0.0)
            IslandCardSizes.useStore(cache, home)
            IslandCardSizes.awaitIo()
            assertEquals(2.0, HaCardSize.of(islandsView[1]), 0.0)
            assertEquals("placed as HA places them, before any island answers", 4, HaMasonry.place(islandsView, 1280).second)
            assertFalse("the same answer again changes nothing", IslandCardSizes.record(islandsView[1].raw, 2.0))
            assertTrue("another answer does", IslandCardSizes.record(islandsView[1].raw, 3.0))
            // The built-in card wasn't kept (its size is its own getCardSize, 1 for this Markdown).
            assertEquals(HaCardSize.of(card("""{"type": "markdown", "content": "x"}""")), HaCardSize.of(islandsView[0]), 0.0)

            // Another Home Assistant: the same configs are other cards there.
            IslandCardSizes.clear()
            IslandCardSizes.useStore(cache, "http://192.0.2.20:8123")
            IslandCardSizes.awaitIo()
            assertEquals(1.0, HaCardSize.of(islandsView[2]), 0.0)

            // An unreadable file is no size at all.
            IslandCardSizes.clear()
            file.writeText("{not json")
            IslandCardSizes.useStore(cache, home)
            IslandCardSizes.awaitIo()
            assertEquals(1.0, HaCardSize.of(islandsView[2]), 0.0)
        } finally {
            IslandCardSizes.clear()
            cache.deleteRecursively()
        }
    }

    @Test @Config(qualifiers = "w1280dp-h800dp-mdpi")
    fun aViewWhoseIslandsGaveTheirSizesBeforeIsPlacedOnce() {
        val cache = java.nio.file.Files.createTempDirectory("vitro-card-sizes").toFile()
        try {
            // An earlier start: every island answered 2.
            IslandCardSizes.useStore(cache, home)
            for (c in islandsView.drop(1)) IslandCardSizes.record(c.raw, 2.0)
            IslandCardSizes.awaitIo()
            IslandCardSizes.clear()
            IslandCardSizes.useStore(cache, home)
            IslandCardSizes.awaitIo()

            val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
            val store = EntityStore().apply {
                for (i in 1..12) putLocal(EntityState("sensor.island_%02d".format(i), "${17 + i * 0.5}", emptyMap()))
            }
            val vv = VitroView(activity, store, 0xFF000000.toInt())
            vv.holdFirstBuildForStatesMs = 0
            vv.resourcesList = LovelaceResourceList(listOf(LovelaceResource("/local/test-cards.js", "module")), bubbleModules = false)
            var builds = 0
            vv.onMetrics = { builds++ }
            vv.contextFactory = { theme ->
                VitroCardContext(
                    context = activity, haBase = FakeHaServer.BASE, store = store, theme = theme, resources = vv.resourcesList,
                    accessToken = { FakeHaServer.TOKEN }, callService = { _, _, _, _ -> }, navigate = {}, openMoreInfo = {},
                    fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
                )
            }
            activity.setContentView(vv)
            val view = JSONObject().put("cards", org.json.JSONArray(islandsView.map { it.raw }))
            vv.setDashboard(LovelaceParser.parseDashboard(JSONObject().put("views", org.json.JSONArray().put(view))), null)
            ShadowLooper.idleMainLooper()
            assertEquals(1, builds)
            assertEquals("HA's four columns from the first build", 4, vv.masonryColumns())
            val root = vv.currentRoot()
            // The islands answer as last time: nothing is placed again.
            for (c in islandsView.drop(1)) vv.islandCardSize(c, 2.0)
            ShadowLooper.idleMainLooper()
            assertEquals(1, builds)
            assertSame(root, vv.currentRoot())
        } finally {
            IslandCardSizes.clear()
            cache.deleteRecursively()
        }
    }

    /** The columns of the masonry view on screen (its row of `MasonryColumnsLayout`). */
    private fun VitroView.masonryColumns(): Int {
        fun find(v: View): dev.glassworks.engine.vitro.ui.MasonryColumnsLayout? =
            v as? dev.glassworks.engine.vitro.ui.MasonryColumnsLayout ?: (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it)) } }
        return find(this)?.childCount ?: -1
    }

    @Test fun aSizeFromAnotherHomeAssistantsIslandIsDropped() {
        // Sourcery on #952: a cardSize queued by an island of the Home Assistant before a reset could land on the next
        // one's dashboard, where the same config is another card on another hass.
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val vv = VitroView(activity, EntityStore(), 0xFF000000.toInt())
        val before = vv.serverGeneration
        vv.islandCardSize(islandsView[1], 2.0, before)
        assertEquals(2.0, HaCardSize.of(islandsView[1]), 0.0)
        vv.resetForServer()
        assertEquals("a reset forgets every size", 1.0, HaCardSize.of(islandsView[1]), 0.0)
        assertTrue(vv.serverGeneration != before)
        vv.islandCardSize(islandsView[1], 3.0, before)
        assertEquals("the old island's late report is dropped", 1.0, HaCardSize.of(islandsView[1]), 0.0)
        vv.islandCardSize(islandsView[1], 3.0, vv.serverGeneration)
        assertEquals(3.0, HaCardSize.of(islandsView[1]), 0.0)
    }

    @Test fun aCardModFontContextKeepsTheIslandSizeCallback() {
        // A card that sets its own font-family builds its cards (islands too) on withFontFamily's copy of the context,
        // which dropped islandCardSize: their sizes skipped the server check and the relayout.
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val sizes = ArrayList<Double>()
        val ctx = VitroCardContext(
            context = activity, haBase = FakeHaServer.BASE, store = EntityStore(),
            theme = dev.glassworks.engine.vitro.theme.ThemeResolver.resolve(dev.glassworks.engine.vitro.theme.ThemeCatalog(), true),
            resources = emptyList(), accessToken = { null }, callService = { _, _, _, _ -> }, navigate = {}, openMoreInfo = {},
            fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
            islandCardSize = { _, size -> sizes += size },
        )
        ctx.withFontFamily("serif").islandCardSize(islandsView[1], 2.0)
        assertEquals(listOf(2.0), sizes)
    }

    private fun VitroView.currentRoot(): View? = (0 until childCount).map { getChildAt(it) }
        .firstOrNull { it is ViewGroup && it.visibility == View.VISIBLE }

    @Test fun anIslandsBubbleCardTakesTheLayoutBubblePicksForTheView() {
        val slider = JSONObject("""{"type": "custom:bubble-card", "card_type": "button", "button_type": "slider", "entity": "light.a"}""")
        // Bubble 3.4.3's setLayout: `large` in a view other than masonry (it finds hui-sections-view under hui-root).
        assertEquals("large", WebIslandCardView.islandConfig(slider, "sections").getString("card_layout"))
        assertEquals("large", WebIslandCardView.islandConfig(slider, "panel").getString("card_layout"))
        // In a masonry view, or outside a view, `normal`: the island hands the config as it is.
        assertSame(slider, WebIslandCardView.islandConfig(slider, "masonry"))
        assertSame(slider, WebIslandCardView.islandConfig(slider, null))
        // The card's own layout stands, but a `normal` with bottom sub-buttons, which Bubble drops in such a view.
        val normal = JSONObject(slider.toString()).put("card_layout", "normal")
        assertEquals("normal", WebIslandCardView.islandConfig(normal, "sections").getString("card_layout"))
        val bottom = JSONObject(normal.toString()).put("sub_button", JSONObject("""{"main": [], "bottom": [{"entity": "light.a"}]}"""))
        assertEquals("large", WebIslandCardView.islandConfig(bottom, "sections").getString("card_layout"))
        // Every Bubble card inside another island card, and nothing else.
        val stack = JSONObject("""{"type": "custom:vertical-stack-in-card", "cards": [$slider, {"type": "custom:mushroom-light-card", "entity": "light.a"}]}""")
        val out = WebIslandCardView.islandConfig(stack, "sections")
        assertEquals("large", out.getJSONArray("cards").getJSONObject(0).getString("card_layout"))
        assertFalse(out.getJSONArray("cards").getJSONObject(1).has("card_layout"))
        assertFalse(out.has("card_layout"))
        assertFalse("the config Vitro holds is untouched", slider.has("card_layout"))
    }
}
