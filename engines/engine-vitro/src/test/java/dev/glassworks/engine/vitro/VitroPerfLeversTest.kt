/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.app.Application
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import dev.glassworks.engine.vitro.cards.EntitiesCardView
import dev.glassworks.engine.vitro.cards.IframeCardView
import dev.glassworks.engine.vitro.cards.MarkdownCardView
import dev.glassworks.engine.vitro.cards.MoreInfoSheet
import dev.glassworks.engine.vitro.cards.PictureCardView
import dev.glassworks.engine.vitro.cards.TileCardView
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.model.LovelaceParser
import dev.glassworks.engine.vitro.theme.ThemeCatalog
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.VitroCardContext
import dev.glassworks.engine.vitro.ui.VitroView
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * The performance levers of the #799 review (G-performance.md L3, L8, L10, L11, L12), each counted rather than timed:
 * renders, text sets, builds and layers. Timing is the Clarity run's job, on a panel.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VitroPerfLeversTest {
    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }

    private fun ctx(store: EntityStore) = VitroCardContext(
        context = activity, haBase = FakeHaServer.BASE, store = store,
        theme = ThemeResolver.resolve(ThemeCatalog(), true), resources = emptyList(),
        accessToken = { FakeHaServer.TOKEN }, callService = { _, _, _, _ -> }, navigate = {}, openMoreInfo = {},
        fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
    )

    private fun vitroView(store: EntityStore, dashboard: String, holdMs: Long = 0, builds: MutableList<Boolean>? = null): VitroView {
        val vv = VitroView(activity, store, 0xFF000000.toInt())
        vv.holdFirstBuildForStatesMs = holdMs
        builds?.let { b -> vv.onMetrics = { m -> b += m.cacheHit } }
        vv.contextFactory = { theme ->
            VitroCardContext(
                context = activity, haBase = FakeHaServer.BASE, store = store, theme = theme, resources = emptyList(),
                accessToken = { FakeHaServer.TOKEN }, callService = { _, _, _, _ -> }, navigate = {}, openMoreInfo = {},
                fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
            )
        }
        vv.setDashboard(LovelaceParser.parseDashboard(JSONObject(dashboard)), null)
        return vv
    }

    private fun idle() = ShadowLooper.idleMainLooper()
    private fun all(v: View): List<View> = listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())
    private fun put(store: EntityStore, id: String, state: String, vararg attrs: Pair<String, Any?>) =
        store.putLocal(EntityState(id, state, mapOf("friendly_name" to "Kitchen", *attrs)))

    /** Counts every text set on [v]'s TextViews (a TextWatcher hears each setText, the same words included). */
    private fun countTextSets(v: View): IntArray {
        val n = IntArray(1)
        all(v).filterIsInstance<TextView>().forEach { tv ->
            tv.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { n[0]++ }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        return n
    }

    private fun texts(v: View) = all(v).filterIsInstance<TextView>().map { it.text.toString() }

    // ---- L10: one render per card per pass, and setText only for a new text

    @Test
    fun aBurstOfChangesRendersTheCardOnceWithTheLastState() {
        val store = EntityStore()
        put(store, "sensor.kitchen", "20.0")
        val card = EntitiesCardView(ctx(store), LovelaceParser.parseCard(JSONObject("""{"type": "entities", "entities": ["sensor.kitchen"]}""")))
        val sets = countTextSets(card)
        // Five changes before the main thread runs (a snapshot, a busy second): one render, from the newest state.
        for (v in listOf("20.1", "20.2", "20.3", "20.4", "20.5")) put(store, "sensor.kitchen", v)
        idle()
        assertTrue(texts(card).toString(), "20.5" in texts(card))
        assertEquals("one text set for five changes", 1, sets[0])
    }

    @Test
    fun aChangeThatLeavesTheRowsWordsSetsNoText() {
        val store = EntityStore()
        put(store, "sensor.kitchen", "20.0")
        put(store, "switch.fan", "off")
        val card = EntitiesCardView(ctx(store), LovelaceParser.parseCard(JSONObject(
            """{"type": "entities", "entities": ["sensor.kitchen", {"entity": "sensor.kitchen", "secondary_info": "last-changed"}, "switch.fan"]}""",
        )))
        val sets = countTextSets(card)
        // An attribute no row shows: the names, values and secondary lines are the same words.
        put(store, "sensor.kitchen", "20.0", "icon_color" to "red")
        put(store, "switch.fan", "off", "icon_color" to "red")
        idle()
        assertEquals("no text set when the words are the same", 0, sets[0])
        put(store, "sensor.kitchen", "21.0")
        idle()
        assertTrue("21.0" in texts(card))
        // The two values, and the secondary line: with no last_changed to show it shows the state, as HA's
        // state-display does (#926), so it changes with it.
        assertEquals("only the two values and the secondary line that changed", 3, sets[0])
    }

    // ---- L8: a hidden cached view stops rendering, and is current the moment it shows

    @Test
    fun aHiddenCachedViewRendersNothingAndShowsTheCurrentStateAtOnce() {
        val store = EntityStore()
        put(store, "sensor.kitchen", "20.0")
        val vv = vitroView(store, """{"views": [
            {"title": "One", "path": "one", "cards": [{"type": "entities", "entities": ["sensor.kitchen"]}]},
            {"title": "Two", "path": "two", "cards": [{"type": "entities", "entities": ["sensor.kitchen"]}]}]}""")
        activity.setContentView(vv)
        idle()
        val first = all(vv).filterIsInstance<EntitiesCardView>().single()
        vv.navigateToView("two")
        idle()
        val second = all(vv).filterIsInstance<EntitiesCardView>().single { it !== first }
        assertFalse(first.isShown)
        val hiddenSets = countTextSets(first)
        val hiddenRenders = first.rendersRun
        val shownRenders = second.rendersRun
        for (v in listOf("20.1", "20.2", "20.3")) {
            put(store, "sensor.kitchen", v)
            idle()
        }
        assertEquals("the hidden view renders nothing", 0, hiddenSets[0])
        assertEquals(hiddenRenders, first.rendersRun)
        assertEquals("the view on screen renders each change", shownRenders + 3, second.rendersRun)
        assertTrue("20.3" in texts(second))
        assertFalse("20.3" in texts(first))
        // Shown again: the current state before anything else runs (no idle), with one render.
        vv.navigateToView("one")
        assertTrue(first.isShown)
        assertTrue(texts(first).toString(), "20.3" in texts(first))
        assertEquals(hiddenRenders + 1, first.rendersRun)
        // And it follows live changes again; the view now hidden stops.
        val secondRenders = second.rendersRun
        put(store, "sensor.kitchen", "20.4")
        idle()
        assertTrue("20.4" in texts(first))
        assertEquals(secondRenders, second.rendersRun)
    }

    /**
     * L3 fix B: the whole dashboard's entities are subscribed, so changes to another view's entities arrive too. On a
     * hidden cached view they cost the parse alone: no card anywhere renders.
     */
    @Test
    fun aChangeToAnEntityOfAHiddenViewRendersNothing() {
        val store = EntityStore()
        put(store, "sensor.kitchen", "20.0")
        put(store, "sensor.hall", "15.0")
        val vv = vitroView(store, """{"views": [
            {"title": "One", "path": "one", "cards": [{"type": "entities", "entities": ["sensor.kitchen"]}]},
            {"title": "Two", "path": "two", "cards": [{"type": "entities", "entities": ["sensor.hall"]}]}]}""")
        activity.setContentView(vv)
        idle()
        val hidden = all(vv).filterIsInstance<EntitiesCardView>().single()
        vv.navigateToView("two")
        idle()
        val shown = all(vv).filterIsInstance<EntitiesCardView>().single { it !== hidden }
        val hiddenRenders = hidden.rendersRun
        val shownRenders = shown.rendersRun
        val sets = countTextSets(vv)
        for (v in listOf("20.1", "20.2", "20.3")) {
            put(store, "sensor.kitchen", v)
            idle()
        }
        assertEquals("the hidden view renders nothing", hiddenRenders, hidden.rendersRun)
        assertEquals("the view on screen doesn't name it", shownRenders, shown.rendersRun)
        assertEquals("no text set anywhere", 0, sets[0])
        // Shown again, it has the newest state at once.
        vv.navigateToView("one")
        assertTrue(texts(hidden).toString(), "20.3" in texts(hidden))
    }

    @Test
    fun aHiddenViewsCardThatLeftTheWindowCatchesUpOnAttachAsBefore() {
        val store = EntityStore()
        put(store, "sensor.kitchen", "20.0")
        val card = EntitiesCardView(ctx(store), LovelaceParser.parseCard(JSONObject("""{"type": "entities", "entities": ["sensor.kitchen"]}""")))
        val host = FrameLayout(activity)
        host.addView(card)
        activity.setContentView(host)
        idle()
        // Hidden, then taken out of the window and put back while hidden: the attach catches up, the show adds nothing stale.
        card.setViewHidden(true)
        put(store, "sensor.kitchen", "20.1")
        idle()
        host.removeView(card)
        put(store, "sensor.kitchen", "20.2")
        host.addView(card)
        assertTrue("20.2" in texts(card))
        val renders = card.rendersRun
        card.setViewHidden(false)
        assertEquals("nothing left to catch up", renders, card.rendersRun)
    }

    // ---- L3: the first build waits until every entity of the view is known

    @Test
    fun aFirstBuildWaitsForItsOwnStatesNotOnlyTheSharedOnes() {
        val store = EntityStore()
        // The head every view shares is in the store; the view's own entity isn't yet.
        put(store, "sensor.head", "1")
        val builds = ArrayList<Boolean>()
        val vv = vitroView(store, """{"views": [{"title": "Heavy", "cards": [
            {"type": "entities", "entities": ["sensor.head", "sensor.own"]}]}]}""", holdMs = 1_000, builds = builds)
        activity.setContentView(vv)
        idle()
        assertTrue("not built with one of its two states", builds.isEmpty())
        put(store, "sensor.own", "2")
        idle()
        assertEquals("built once its states are in", listOf(false), builds)
        assertTrue(texts(vv).toString(), "2" in texts(vv))
    }

    @Test
    fun anEntityHaSaysDoesntExistEndsTheWaitBeforeItsTimeout() {
        val store = EntityStore()
        put(store, "sensor.head", "1")
        store.resetSnapshot()
        val builds = ArrayList<Boolean>()
        val vv = vitroView(store, """{"views": [{"title": "Heavy", "cards": [
            {"type": "entities", "entities": ["sensor.head", "sensor.gone"]}]}]}""", holdMs = 1_000, builds = builds)
        activity.setContentView(vv)
        idle()
        assertTrue(builds.isEmpty())
        // HA's first event for the view's subscription: sensor.gone isn't in it.
        assertTrue(store.markSnapshot(setOf("sensor.head", "sensor.gone")))
        idle()
        assertEquals("built at HA's answer, not after the wait", listOf(false), builds)
    }

    @Test
    fun aViewWithNoKnownStatesStillBuildsAfterTheWait() {
        val store = EntityStore()
        val builds = ArrayList<Boolean>()
        val vv = vitroView(store, """{"views": [{"title": "Heavy", "cards": [
            {"type": "entities", "entities": ["sensor.own"]}]}]}""", holdMs = 1_000, builds = builds)
        activity.setContentView(vv)
        idle()
        assertTrue(builds.isEmpty())
        ShadowLooper.idleMainLooper(1_100, TimeUnit.MILLISECONDS)
        assertEquals(listOf(false), builds)
    }

    // ---- L11: More Info's entrance runs on a hardware layer

    @Test
    fun theSheetsEntranceRunsOnAHardwareLayerThatGoesAfter() {
        val store = EntityStore()
        put(store, "sensor.kitchen", "20.0")
        val sheet = MoreInfoSheet(ctx(store)) {}
        activity.setContentView(sheet)
        sheet.show("sensor.kitchen")
        sheet.measure(View.MeasureSpec.makeMeasureSpec(1440, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
        sheet.layout(0, 0, 1440, 720)
        sheet.viewTreeObserver.dispatchOnPreDraw()
        assertEquals("on a layer while it runs", View.LAYER_TYPE_HARDWARE, sheet.surface.layerType)
        ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS)
        assertEquals("the layer goes when it ends", View.LAYER_TYPE_NONE, sheet.surface.layerType)
    }

    // ---- L12: masonry and sidebar views get the big-card layer rule; a live camera never gets a layer

    @Test
    @Config(qualifiers = "w1280dp-h800dp-mdpi")
    fun masonryAndSidebarCardsFollowTheBigCardRuleAndCamerasStayOff() {
        val store = EntityStore()
        put(store, "light.lounge", "on")
        put(store, "camera.porch", "streaming", "supported_features" to 2)
        val cards = """{"type": "tile", "entity": "light.lounge"},
            {"type": "markdown", "content": "# Big\n\nA\n\nB\n\nC\n\nD\n\nE\n\nF\n\nG\n\nH\n\nI"},
            {"type": "picture-entity", "entity": "camera.porch", "camera_view": "live", "aspect_ratio": "4:3"},
            {"type": "vertical-stack", "cards": [{"type": "picture-entity", "entity": "camera.porch", "camera_view": "live", "aspect_ratio": "4:3"}]}"""
        for (type in listOf("masonry", "sidebar")) {
            val vv = vitroView(store, """{"views": []}""")
            vv.cardLayers = VitroView.CardLayers.BIG
            vv.setDashboard(LovelaceParser.parseDashboard(JSONObject("""{"views": [{"title": "Home", "type": "$type", "cards": [$cards]}]}""")), null)
            vv.measure(View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
            vv.layout(0, 0, 1280, 800)
            val tile = all(vv).filterIsInstance<TileCardView>().single()
            val markdown = all(vv).filterIsInstance<MarkdownCardView>().single()
            // Each card as its column holds it (a stack's camera inside the stack's view).
            fun inColumn(v: View) = generateSequence(v) { it.parent as? View }.first { it.parent?.javaClass?.simpleName == "CardColumn" }
            val (camera, stacked) = all(vv).filterIsInstance<PictureCardView>().partition { inColumn(it) === it }
            assertEquals(1, camera.size)
            assertEquals(1, stacked.size)
            assertEquals("$type: a small card has no layer", View.LAYER_TYPE_NONE, inColumn(tile).layerType)
            assertFalse("$type: the tile is small", VitroView.isBigCard(tile.width, tile.height, 1280, 800))
            assertEquals("$type: a big card has its layer", View.LAYER_TYPE_HARDWARE, inColumn(markdown).layerType)
            assertTrue("$type: the camera is big", VitroView.isBigCard(camera[0].width, camera[0].height, 1280, 800))
            assertEquals("$type: a live camera has none", View.LAYER_TYPE_NONE, camera[0].layerType)
            val stack = inColumn(stacked[0])
            assertTrue("$type: the stack is big", VitroView.isBigCard(stack.width, stack.height, 1280, 800))
            assertEquals("$type: nor a stack around one", View.LAYER_TYPE_NONE, stack.layerType)
        }
    }

    // A view's card layers stay within its budget: on the S1, heavy's masonry made 39 entities cards big, and their 36 MB
    // of layers filled HWUI's GPU cache, so Skia purged its path masks and issued every scrolling frame three times slower.
    @Test
    @Config(qualifiers = "w1280dp-h800dp-mdpi")
    fun aViewsCardLayersStayWithinItsBudgetFirstLaidOutFirst() {
        val store = EntityStore()
        val big = """{"type": "markdown", "content": "# Big\n\nA\n\nB\n\nC\n\nD\n\nE\n\nF\n\nG\n\nH\n\nI"}"""
        for (mode in VitroView.CardLayers.values()) for (type in listOf("masonry", "sections")) {
            val vv = vitroView(store, """{"views": []}""")
            vv.cardLayers = mode
            val cards = List(80) { big }.joinToString()
            val body = if (type == "sections") """"sections": [{"type": "grid", "cards": [$cards]}]""" else """"cards": [$cards]"""
            vv.setDashboard(LovelaceParser.parseDashboard(JSONObject("""{"views": [{"title": "Home", "type": "$type", $body}]}""")), null)
            vv.measure(View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
            vv.layout(0, 0, 1280, 800)
            // Each card as its column or grid holds it: the view that gets the layer.
            val held = all(vv).filterIsInstance<MarkdownCardView>().map { md ->
                generateSequence(md as View) { it.parent as? View }.first { it.parent?.javaClass?.simpleName in setOf("CardColumn", "HaGridLayout") }
            }
            assertEquals("$mode $type", 80, held.size)
            assertTrue("$mode $type: every card is big", held.all { VitroView.isBigCard(it.width, it.height, 1280, 800) })
            val layered = held.filter { it.layerType == View.LAYER_TYPE_HARDWARE }
            val limit = if (mode == VitroView.CardLayers.BIG) VitroView.BIG_LAYER_BUDGET_BYTES else 1280L * 800 * VitroView.HWUI_CACHE_BYTES_PER_PIXEL / 2
            val used = layered.sumOf { VitroView.layerBytes(it.width, it.height) }
            assertTrue("$mode $type: some cards have their layer", layered.size >= 4)
            assertTrue("$mode $type: not all of them (${held.sumOf { VitroView.layerBytes(it.width, it.height) }} bytes wanted)", layered.size < held.size)
            assertTrue("$mode $type: $used bytes of layers within $limit", used <= limit)
            assertTrue("$mode $type: the next card wouldn't have fit", used + VitroView.layerBytes(held[0].width, held[0].height) > limit)
        }
    }

    @Test
    fun aLayerCountsAtTheSizeSkiaAllocatesIt() {
        assertEquals(512L * 512 * 4, VitroView.layerBytes(348, 314))
        assertEquals(16L * 64 * 4, VitroView.layerBytes(3, 64))
        assertEquals(listOf(16, 256, 256, 512, 1024, 1536, 1536, 2048), listOf(1, 129, 256, 257, 1000, 1025, 1536, 1537).map(VitroView::approxSide))
    }

    @Test
    fun aCardThatLeavesTheViewOrLosesItsLayerGivesItsShareBack() {
        val root = android.widget.FrameLayout(activity)
        val cards = List(3) { View(activity).also(root::addView) }
        // Sides of 16 and 32 px, which Skia allocates as they are.
        val budget = VitroView.CardLayerBudget(limit = VitroView.layerBytes(32, 32))
        budget.roots += root
        fun claim(i: Int, w: Int, h: Int) = budget.claim(cards[i], w, h).also { if (it) cards[i].setLayerType(View.LAYER_TYPE_HARDWARE, null) }
        assertTrue(claim(0, 32, 16))
        assertTrue(claim(1, 32, 16))
        assertFalse("full", claim(2, 16, 16))
        assertTrue("a card's new size replaces its old share", claim(1, 16, 16))
        assertTrue(claim(2, 16, 16))
        assertEquals(VitroView.layerBytes(32, 32), budget.used)

        root.removeView(cards[0])
        assertTrue("a card that left the view freed its share", claim(2, 32, 16))
        cards[1].setLayerType(View.LAYER_TYPE_NONE, null)   // an island lifted it (liftLayersAbove)
        assertEquals("a lifted card holds nothing", VitroView.layerBytes(32, 16), budget.run { claim(cards[2], 32, 16); used })
        budget.release(cards[2])
        assertEquals(0L, budget.used)
    }

    // An island is a browser's SurfaceView: under a layer its picture never shows (every masonry island was blank on the
    // S1 at 2bdca2e31, its page drawing and sizing the card behind the layer's pixels).
    @Test
    @Config(qualifiers = "w1280dp-h800dp-mdpi")
    fun aCardHoldingAnIslandNeverGetsALayer() {
        val store = EntityStore()
        val cards = """{"type": "iframe", "url": "https://example.com/", "aspect_ratio": "60%"},
            {"type": "vertical-stack", "cards": [{"type": "iframe", "url": "https://example.com/", "aspect_ratio": "60%"}]},
            {"type": "markdown", "content": "# Big\n\nA\n\nB\n\nC\n\nD\n\nE\n\nF\n\nG\n\nH\n\nI"}"""
        for (mode in VitroView.CardLayers.values()) for (type in listOf("masonry", "sidebar", "sections")) {
            val vv = vitroView(store, """{"views": []}""")
            vv.cardLayers = mode
            val body = if (type == "sections") """"sections": [{"type": "grid", "cards": [$cards]}]""" else """"cards": [$cards]"""
            vv.setDashboard(LovelaceParser.parseDashboard(JSONObject("""{"views": [{"title": "Home", "type": "$type", $body}]}""")), null)
            vv.measure(View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
            vv.layout(0, 0, 1280, 800)
            val islands = all(vv).filterIsInstance<IframeCardView>()
            assertEquals("$mode $type", 2, islands.size)
            for (island in islands) {
                // The card as its column or grid holds it (the stack's iframe inside the stack's view).
                val held = generateSequence(island as View) { it.parent as? View }.takeWhile { it !== vv }.toList()
                assertTrue("$mode $type: the iframe is big", VitroView.isBigCard(island.width, island.height, 1280, 800))
                held.forEach { assertEquals("$mode $type: no layer on ${it.javaClass.simpleName} around an island", View.LAYER_TYPE_NONE, it.layerType) }
            }
            val markdown = generateSequence(all(vv).filterIsInstance<MarkdownCardView>().single() as View) { it.parent as? View }
                .takeWhile { it !== vv }.toList()
            assertTrue("$mode $type: the big card next to them keeps its layer", markdown.any { it.layerType == View.LAYER_TYPE_HARDWARE })
        }
    }

    // An island a card makes later (an auto-entities match, a conditional's child) inside a card whose size can't change
    // (sections, fixed grid_options.rows): the card's layer came first and no resize takes it off (Greptile on #947).
    @Test
    @Config(qualifiers = "w1280dp-h800dp-mdpi")
    fun anIslandAddedLaterLiftsTheLayerOfACardOfFixedSize() {
        val store = EntityStore()
        val vv = vitroView(store, """{"views": []}""")
        vv.cardLayers = VitroView.CardLayers.ALL
        activity.setContentView(vv)
        vv.setDashboard(LovelaceParser.parseDashboard(JSONObject("""{"views": [{"title": "Home", "type": "sections",
            "sections": [{"type": "grid", "cards": [{"type": "vertical-stack", "grid_options": {"rows": 4},
                "cards": [{"type": "markdown", "content": "Nothing matches yet"}]}]}]}]}""")), null)
        fun lay() {
            vv.measure(View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
            vv.layout(0, 0, 1280, 800)
            idle()
        }
        lay()
        val markdown = all(vv).filterIsInstance<MarkdownCardView>().single()
        val above = generateSequence(markdown.parent as View) { it.parent as? View }.takeWhile { it !== vv }.toList()
        assertTrue("the stack starts on a layer", above.any { it.layerType == View.LAYER_TYPE_HARDWARE })
        val sizes = above.map { it.width to it.height }

        // A match lands: the stack gains an iframe beside its markdown.
        val island = IframeCardView(ctx(store), LovelaceParser.parseDashboard(JSONObject(
            """{"views": [{"cards": [{"type": "iframe", "url": "https://example.com/", "aspect_ratio": "60%"}]}]}""",
        )).views.single().cards.single())
        (markdown.parent as ViewGroup).addView(island)
        lay()

        assertEquals("the stack kept its size (no resize to hear)", sizes, above.map { it.width to it.height })
        val held = generateSequence(island as View) { it.parent as? View }.takeWhile { it !== vv }.toList()
        held.forEach { assertEquals("no layer on ${it.javaClass.simpleName} over the island", View.LAYER_TYPE_NONE, it.layerType) }
    }

    @Test
    fun playsLiveVideoLooksInsideStacks() {
        fun card(json: String) = LovelaceParser.parseCard(JSONObject(json))
        assertTrue(VitroView.playsLiveVideo(card("""{"type": "picture-glance", "camera_image": "camera.porch", "camera_view": "live"}""")))
        assertTrue(VitroView.playsLiveVideo(card("""{"type": "grid", "cards": [{"type": "vertical-stack", "cards": [
            {"type": "picture-entity", "entity": "camera.porch", "camera_view": "live"}]}]}""")))
        assertFalse(VitroView.playsLiveVideo(card("""{"type": "picture-entity", "entity": "camera.porch"}""")))
        assertFalse(VitroView.playsLiveVideo(card("""{"type": "markdown", "content": "camera_view: live"}""")))
    }
}
