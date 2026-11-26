/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.glassworks.engine.vitro.cards.TileCardView
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.model.LovelaceParser
import dev.glassworks.engine.vitro.ui.GridCardLayout
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
import kotlin.math.abs

/**
 * HA's stack and grid cards (`hui-stack-card`, `hui-grid-card`): the grid's square cells (HA's default), the stacks'
 * and the grid's `title`, the gaps around hidden cards, and a `position: fixed` card in a layout-card view.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StackGridLayoutTest {
    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }
    private val density get() = activity.resources.displayMetrics.density

    private fun store() = EntityStore().apply {
        for (n in listOf("a", "b", "c", "d")) putLocal(EntityState("light.$n", "on", mapOf("friendly_name" to "Lamp ${n.uppercase()}")))
        putLocal(EntityState("input_boolean.guest", "off", emptyMap()))
    }

    private fun render(store: EntityStore, viewJson: String): VitroView {
        val vv = VitroView(activity, store, 0xFF000000.toInt())
        vv.holdFirstBuildForStatesMs = 0
        vv.contextFactory = { theme ->
            VitroCardContext(
                context = activity,
                haBase = FakeHaServer.BASE,
                store = store,
                theme = theme,
                resources = emptyList(),
                accessToken = { FakeHaServer.TOKEN },
                callService = { _, _, _, _ -> },
                navigate = {},
                openMoreInfo = {},
                fetchHistory = { _, _, _ -> },
                subscribeTemplate = { _, _ -> {} },
            )
        }
        vv.setDashboard(LovelaceParser.parseDashboard(JSONObject("""{"views": [$viewJson]}""")), null)
        ShadowLooper.idleMainLooper()
        vv.measure(View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        vv.layout(0, 0, 1280, 800)
        return vv
    }

    private fun all(v: View): List<View> = listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())

    private fun tiles(v: View) = all(v).filterIsInstance<TileCardView>()

    /** [v]'s left and top inside [ancestor]. */
    private fun offsetIn(v: View, ancestor: View): Pair<Int, Int> {
        var x = 0
        var y = 0
        var cur: View = v
        while (cur !== ancestor) {
            x += cur.left
            y += cur.top
            cur = cur.parent as View
        }
        return x to y
    }

    private fun gridView(grid: String) = """{"title": "Home", "cards": [$grid]}"""

    @Test
    fun aGridIsSquareUnlessTheConfigTurnsItOff() {
        // HA's `square` is on unless `square: false`: every cell is as tall as a column is wide.
        val cards = """[{"type": "tile", "entity": "light.a"}, {"type": "tile", "entity": "light.b"}, {"type": "tile", "entity": "light.c"}, {"type": "tile", "entity": "light.d"}]"""
        for (square in listOf("", "\"square\": true,")) {
            val vv = render(store(), gridView("""{"type": "grid", "columns": 3, $square "cards": $cards}"""))
            val shown = tiles(vv)
            assertEquals(4, shown.size)
            for (t in shown) assertTrue("square '$square': ${t.width}x${t.height}", abs(t.width - t.height) <= 1)
            // Two rows of the same height, the grid's 8 px gap between them.
            val grid = all(vv).filterIsInstance<GridCardLayout>().single()
            assertEquals(shown[0].height, shown[3].height)
            assertEquals(shown[0].height + Math.round(8 * density), offsetIn(shown[3], grid).second - offsetIn(shown[0], grid).second)
        }

        // `square: false`: each row as tall as its tallest card (a tile is 56 px).
        val flat = render(store(), gridView("""{"type": "grid", "columns": 3, "square": false, "cards": $cards}"""))
        for (t in tiles(flat)) {
            assertEquals(Math.round(56 * density), t.height)
            assertTrue(t.width > t.height)
        }
    }

    @Test
    fun aSquareRowGrowsToItsTallestCard() {
        // A card taller than a column is wide makes every row that tall (`grid-auto-rows: 1fr`).
        val vv = render(store(), gridView("""{"type": "grid", "columns": 6, "cards": [
            {"type": "markdown", "content": "one\n\ntwo\n\nthree\n\nfour\n\nfive\n\nsix\n\nseven"},
            {"type": "tile", "entity": "light.a"}, {"type": "tile", "entity": "light.b"}, {"type": "tile", "entity": "light.c"},
            {"type": "tile", "entity": "light.d"}, {"type": "tile", "entity": "light.a"}, {"type": "tile", "entity": "light.b"}]}"""))
        val grid = all(vv).filterIsInstance<GridCardLayout>().single()
        val rows = grid.rowHeights
        assertEquals(2, rows.size)
        assertEquals(rows[0], rows[1])
        val t = tiles(vv)
        assertTrue("${rows[0]} > ${t[0].width}", rows[0] > t[0].width)
        for (tile in t) assertEquals(rows[0], tile.height)
    }

    @Test
    fun aHiddenCardTakesNoGridCell() {
        // `[hidden]` cards take no cell: the cards after it move up a place.
        val vv = render(store(), gridView("""{"type": "grid", "columns": 2, "square": false, "cards": [
            {"type": "tile", "entity": "light.a", "visibility": [{"condition": "state", "entity": "input_boolean.guest", "state": "on"}]},
            {"type": "tile", "entity": "light.b"}, {"type": "tile", "entity": "light.c"}]}"""))
        val grid = all(vv).filterIsInstance<GridCardLayout>().single()
        val (b, c) = tiles(vv).filter { it.visibility == View.VISIBLE }
        assertEquals(0 to 0, offsetIn(b, grid))
        assertEquals(0, offsetIn(c, grid).second)
        assertEquals(1, grid.rowHeights.size)
    }

    @Test
    fun aHiddenCardLeavesNoStackGap() {
        // The stack's `gap` is between shown cards only: a hidden first card leaves none above the next.
        for (type in listOf("vertical-stack", "horizontal-stack")) {
            val vv = render(store(), gridView("""{"type": "$type", "cards": [
                {"type": "tile", "entity": "light.a", "visibility": [{"condition": "state", "entity": "input_boolean.guest", "state": "on"}]},
                {"type": "tile", "entity": "light.b"}, {"type": "tile", "entity": "light.c"}]}"""))
            val (_, b, c) = tiles(vv)
            val stack = b.parent as View
            assertEquals(type, 0 to 0, offsetIn(b, stack))
            val gap = Math.round(8 * density)
            if (type == "vertical-stack") assertEquals(b.height + gap, offsetIn(c, stack).second)
            else assertEquals(b.width + gap, offsetIn(c, stack).first)
        }
    }

    @Test
    fun stacksAndTheGridShowTheirTitleAsHaDoes() {
        for (type in listOf("vertical-stack", "horizontal-stack", "grid")) {
            val vv = render(store(), gridView("""{"type": "$type", "title": "Lights", "cards": [{"type": "tile", "entity": "light.a"}, {"type": "tile", "entity": "light.b"}]}"""))
            val title = all(vv).filterIsInstance<TextView>().single { it.text.toString() == "Lights" }
            // h1.card-header: 24 px, `padding: 24px 16px 16px`, above the cards.
            assertEquals(type, 24f * density, title.textSize, 0.5f)
            assertEquals(Math.round(24 * density), title.paddingTop)
            assertEquals(Math.round(16 * density), title.paddingBottom)
            assertEquals(Math.round(16 * density), title.paddingStart)
            val host = title.parent as View
            val firstTile = tiles(vv).first()
            assertEquals(type, title.height, offsetIn(firstTile, host).second)
        }
        // No title, no header.
        val untitled = render(store(), gridView("""{"type": "vertical-stack", "title": "", "cards": [{"type": "tile", "entity": "light.a"}]}"""))
        assertFalse(all(untitled).filterIsInstance<TextView>().any { it.paddingTop == Math.round(24 * density) && it.textSize == 24f * density })
    }

    @Test
    fun aTitledSquareGridKeepsItsCellsSquare() {
        val vv = render(store(), gridView("""{"type": "grid", "title": "Lights", "columns": 2, "cards": [{"type": "tile", "entity": "light.a"}, {"type": "tile", "entity": "light.b"}]}"""))
        for (t in tiles(vv)) assertTrue("${t.width}x${t.height}", abs(t.width - t.height) <= 1)
    }

    @Test
    fun aFixedCardInALayoutCardViewIsPinned() {
        // card-mod `position: fixed` in a layout-card view: the card goes to the overlay (as in the masonry, sidebar
        // and sections views), and its host keeps an empty slot in the layout.
        for (layout in listOf("custom:masonry-layout", "custom:grid-layout", "custom:vertical-layout", "custom:horizontal-layout")) {
            val vv = render(store(), """{"title": "Home", "type": "$layout", "cards": [
                {"type": "tile", "entity": "light.a", "name": "Scrolling"},
                {"type": "tile", "entity": "light.b", "name": "Pinned",
                 "card_mod": {"style": "ha-card { position: fixed; right: 24px; bottom: 24px; width: 132px; }"}},
                {"type": "tile", "entity": "light.c", "name": "After"}]}""")
            val pinned = tiles(vv.overlayLayer)
            assertEquals(layout, 1, pinned.size)
            assertTrue(layout, all(vv.overlayLayer).filterIsInstance<TextView>().any { it.text.toString() == "Pinned" })
            val scrolling = tiles(vv).filter { it !in pinned }
            assertEquals(layout, 2, scrolling.size)
            // The empty slot it leaves, as HA lays it out (the view-layout-fixed parity cases): in a column its margins
            // collapse through it, so the next card sits the usual 8 px below; the grid keeps its cell, 4 + 8 px of
            // margins, between the two cards' own margins (8 below, 4 above).
            val (before, after) = scrolling.map { offsetIn(it, vv).second }
            val gap = after - before - scrolling[0].height
            when (layout) {
                "custom:masonry-layout", "custom:vertical-layout" -> assertEquals(layout, Math.round(8 * density), gap)
                "custom:grid-layout" -> assertEquals(layout, Math.round(24 * density), gap)
            }
        }
    }

    @Test
    fun aPinnedSlotsVisibilityListenerEndsWithTheView() {
        // A pinned card with a visibility condition leaves a slot that follows it too. That slot must be in the tree
        // (attached with its column), so its store listener ends when the view is detached, in every layout.
        @Suppress("UNCHECKED_CAST")
        fun listeners(store: EntityStore, id: String): Int =
            (EntityStore::class.java.getDeclaredField("listeners").apply { isAccessible = true }.get(store) as Map<String, List<*>>)[id]?.size ?: 0
        for (layout in listOf("custom:masonry-layout", "custom:grid-layout", "custom:vertical-layout", "custom:horizontal-layout")) {
            val store = store()
            val vv = render(store, """{"title": "Home", "type": "$layout", "cards": [
                {"type": "tile", "entity": "light.a"},
                {"type": "tile", "entity": "light.b", "visibility": [{"condition": "state", "entity": "input_boolean.guest", "state": "on"}],
                 "card_mod": {"style": "ha-card { position: fixed; bottom: 0; }"}}]}""")
            activity.setContentView(vv)
            ShadowLooper.idleMainLooper()
            val slot = all(vv).filterIsInstance<dev.glassworks.engine.vitro.cards.LayoutCardView.PinnedSlot>().single()
            assertTrue(layout, slot.isAttachedToWindow)
            assertTrue(layout, listeners(store, "input_boolean.guest") > 0)
            activity.setContentView(View(activity))
            ShadowLooper.idleMainLooper()
            assertEquals(layout, 0, listeners(store, "input_boolean.guest"))
        }
    }

    @Test
    fun aFixedFirstCardRaisesTheColumnsTopMargin() {
        // Its empty slot's 8 px bottom margin collapses with the next card's 4 px top margin: 8 px above the first.
        fun firstTop(cards: String) = render(store(), """{"title": "Home", "type": "custom:masonry-layout", "cards": [$cards]}""")
            .let { vv -> tiles(vv).first { it.parent !== vv.overlayLayer }.let { offsetIn(it, vv).second } }
        val plain = firstTop("""{"type": "tile", "entity": "light.a"}""")
        val pinnedFirst = firstTop("""{"type": "tile", "entity": "light.b", "card_mod": {"style": "ha-card { position: fixed; bottom: 0; }"}},
            {"type": "tile", "entity": "light.a"}""")
        assertEquals(Math.round(4 * density), pinnedFirst - plain)
    }
}
