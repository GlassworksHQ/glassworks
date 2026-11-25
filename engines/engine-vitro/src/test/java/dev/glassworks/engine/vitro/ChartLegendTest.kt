/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import dev.glassworks.engine.vitro.cards.ChartLegendView
import dev.glassworks.engine.vitro.cards.ChartScene
import dev.glassworks.engine.vitro.cards.ChartView
import dev.glassworks.engine.vitro.cards.LegendItem
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.theme.ThemeCatalog
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.VitroCardContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** `ha-chart-base`'s legend: two rows and a "More (N)" chip (`LegendFitController`), which expands it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChartLegendTest {
    private fun ctx(touch: Boolean = false): VitroCardContext {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        return VitroCardContext(
            context = activity, haBase = "http://192.0.2.1", store = EntityStore(),
            theme = ThemeResolver.resolve(ThemeCatalog(), true),
            resources = emptyList(), accessToken = { null }, callService = { _, _, _, _ -> }, navigate = {}, openMoreInfo = {},
            fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} }, touch = touch,
        )
    }

    @Test
    fun fitInRowsIsTheFrontendsOwn() {
        // legend-fit-controller.test.ts
        assertEquals(4, ChartLegendView.fitInRows(floatArrayOf(100f, 100f, 100f, 100f), 60f, 300f, 12f, 2))
        assertEquals(4, ChartLegendView.fitInRows(floatArrayOf(100f, 100f, 100f, 100f, 100f), 60f, 300f, 12f, 2))
        assertEquals(3, ChartLegendView.fitInRows(floatArrayOf(100f, 100f, 100f, 100f, 100f), 100f, 300f, 12f, 2))
        // An item wider than the row still takes a row of its own.
        assertEquals(1, ChartLegendView.fitInRows(floatArrayOf(400f, 400f, 400f), 80f, 300f, 8f, 2))
    }

    /** The texts the legend shows (laid out and visible), in order. */
    private fun shownTexts(v: ViewGroup): List<String> {
        val out = ArrayList<String>()
        fun walk(x: View) {
            if (x.visibility != View.VISIBLE || x.width == 0) return
            if (x is TextView) out += x.text.toString()
            if (x is ViewGroup) for (i in 0 until x.childCount) walk(x.getChildAt(i))
        }
        walk(v)
        return out
    }

    @Test
    fun aLongLegendShowsTwoRowsAndAMoreChipThatExpandsIt() {
        val ctx = ctx()
        // A card holding a chart and its legend, as the history graph stacks them.
        val col = LinearLayout(ctx.context).apply { orientation = LinearLayout.VERTICAL }
        var scenes = 0
        val chart = ChartView(ctx.context, ctx.dp).apply { heightCss = 200f; setScene { scenes++; ChartScene() } }
        val legend = ChartLegendView(ctx)
        col.addView(chart, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(legend, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        legend.setItems((1..6).map { LegendItem("sensor.room_$it", "Room $it temperature sensor with a long name", 0xFF4269D0.toInt(), "21.$it °C") })
        var reported: Boolean? = null
        legend.onExpandedChange = { reported = it }
        fun layout() {
            col.measure(View.MeasureSpec.makeMeasureSpec(Math.round(466 * ctx.dp), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            col.layout(0, 0, col.measuredWidth, col.measuredHeight)
        }
        layout()
        val collapsed = shownTexts(legend)
        val shownEntries = collapsed.count { it.startsWith("Room ") }
        assertTrue("two rows hold fewer than six entries: $collapsed", shownEntries in 1..5)
        assertEquals("More (${6 - shownEntries})", collapsed.last())
        val collapsedHeight = legend.height
        // Two rows: 12 px above, 24 px rows 8 px apart.
        assertEquals(Math.round(12 * ctx.dp) + 2 * Math.round(24 * ctx.dp) + Math.round(8 * ctx.dp), collapsedHeight)
        val scenesBefore = scenes

        // The chip expands the legend: every entry and "Less", taller, and the chart keeps its paths.
        val chip = (0 until legend.childCount).map { legend.getChildAt(it) }.last() as ViewGroup
        assertTrue(chip.getChildAt(0).performClick())
        layout()
        val expanded = shownTexts(legend)
        assertEquals(6, expanded.count { it.startsWith("Room ") })
        assertEquals("Less", expanded.last())
        assertTrue(legend.height > collapsedHeight)
        assertEquals(true, reported)
        assertEquals(scenesBefore, scenes)

        // And collapses it again.
        assertTrue(chip.getChildAt(0).performClick())
        layout()
        assertEquals(collapsed, shownTexts(legend))
        assertEquals(false, reported)
    }

    @Test
    fun aLegendThatFitsHasNoChip() {
        val ctx = ctx()
        val legend = ChartLegendView(ctx)
        legend.setItems(listOf(LegendItem("a", "A", 0, "1"), LegendItem("b", "B", 0, "2")))
        legend.measure(View.MeasureSpec.makeMeasureSpec(Math.round(466 * ctx.dp), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        legend.layout(0, 0, legend.measuredWidth, legend.measuredHeight)
        val texts = shownTexts(legend)
        assertEquals(listOf("A", "1", "B", "2"), texts)
        assertFalse(texts.any { it.startsWith("More") || it == "Less" })
    }

    @Test
    fun aNameShrinksToWhatItsValueLeavesInThreeHundredPixels() {
        val ctx = ctx()
        val legend = ChartLegendView(ctx)
        val long = "Presence Multi-Sensor FP300 ▸ Temperature, with a name longer than any entry"
        legend.setItems(listOf(LegendItem("a", long, 0, "22.0 °C"), LegendItem("b", "B", 0, "2")))
        legend.measure(View.MeasureSpec.makeMeasureSpec(Math.round(466 * ctx.dp), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val entry = legend.getChildAt(0)
        // The whole entry (sides, toggle, name, value) stays within `li { max-width: 300px }`.
        assertTrue("entry ${entry.measuredWidth / ctx.dp} px", entry.measuredWidth <= Math.round(300 * ctx.dp) + 1)
    }

    @Test
    fun onATouchScreenEntriesAreFortyPixelsHighWithNoRowGap() {
        // HA's `@media (pointer: coarse)`: entries 40 px high, no row gap, no li padding and 11 px before the toggle;
        // the chip's li stays 24 px, centered in its row.
        val ctx = ctx(touch = true)
        val legend = ChartLegendView(ctx)
        legend.setItems((1..6).map { LegendItem("sensor.room_$it", "Room $it temperature sensor with a long name", 0xFF4269D0.toInt(), "21.$it °C") })
        legend.measure(View.MeasureSpec.makeMeasureSpec(Math.round(466 * ctx.dp), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        legend.layout(0, 0, legend.measuredWidth, legend.measuredHeight)
        val px = { v: Int -> Math.round(v * ctx.dp) }
        assertEquals(px(12) + 2 * px(40), legend.height)
        val first = legend.getChildAt(0) as ViewGroup
        assertEquals(px(40), first.height)
        assertEquals(px(12), first.top)
        assertEquals(px(11), first.getChildAt(0).left)
        // The second row: an entry and the chip, the chip 24 px high in the middle of the 40 px row.
        val second = legend.getChildAt(1)
        assertEquals(px(12) + px(40), second.top)
        val chip = legend.getChildAt(legend.childCount - 1)
        assertEquals(px(24), chip.height)
        assertEquals(second.top + (px(40) - px(24)) / 2, chip.top)
        // The entry, the toggle's 33 px included, still stays within 300 px.
        assertTrue(first.measuredWidth <= px(300) + 1)
    }

    @Test
    @Config(qualifiers = "170dpi")
    fun theFitCountsOnTheWidthTheChipMeasures() {
        // At density 1.0625 the chip's rounded parts add up to more than 42 px times the density: the fit must use
        // what the chip measures, or a chip it puts in row two wraps to a third.
        val ctx = ctx()
        assertEquals(1.0625f, ctx.dp, 0f)
        val legend = ChartLegendView(ctx)
        legend.setItems((1..6).map { LegendItem("sensor.room_$it", "Room $it temperature sensor with a long name", 0xFF4269D0.toInt(), "21.$it °C") })
        legend.measure(View.MeasureSpec.makeMeasureSpec(Math.round(466 * ctx.dp), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        legend.layout(0, 0, legend.measuredWidth, legend.measuredHeight)
        val chip = legend.getChildAt(legend.childCount - 1) as ViewGroup
        val label = (chip.getChildAt(0) as ViewGroup).getChildAt(0) as TextView
        assertEquals(chip.measuredWidth.toFloat(), legend.chipWidth(label.text.toString()), 0f)
        // Two rows: the chip didn't wrap to a third.
        assertEquals(Math.round(12 * ctx.dp) + 2 * Math.round(24 * ctx.dp) + Math.round(8 * ctx.dp), legend.height)
    }

    @Test
    fun anExpandedLegendScrollsToItsLessChipInAFixedSlot() {
        // HA's `.chart-legend { max-height: 60%; overflow-y: auto }`: capped by a fixed slot, the legend scrolls, so
        // its last entries and the chip that collapses it stay reachable.
        val ctx = ctx()
        val legend = ChartLegendView(ctx).apply { expanded = true }
        legend.setItems((1..6).map { LegendItem("sensor.room_$it", "Room $it temperature sensor with a long name", 0xFF4269D0.toInt(), "21.$it °C") })
        val box = dev.glassworks.engine.vitro.cards.scrollingLegend(legend)
        val chart = ChartView(ctx.context, ctx.dp).apply { heightCss = 233f; setScene { ChartScene() } }
        val card = object : android.widget.FrameLayout(ctx.context) {
            init {
                val col = LinearLayout(ctx.context).apply { orientation = LinearLayout.VERTICAL }
                col.addView(chart, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                col.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(col, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }

            override fun onMeasure(w: Int, h: Int) = dev.glassworks.engine.vitro.cards.measureInSlot(this,
                listOf(dev.glassworks.engine.vitro.cards.SlotChart(chart, box, 233f)), w, h, ctx.dp) { a, b -> super.onMeasure(a, b) }
        }
        val slot = Math.round(200 * ctx.dp)
        card.measure(View.MeasureSpec.makeMeasureSpec(Math.round(466 * ctx.dp), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(slot, View.MeasureSpec.EXACTLY))
        card.layout(0, 0, card.measuredWidth, card.measuredHeight)
        // The legend keeps 60 % of the slot and scrolls through the rest of its rows.
        assertEquals(Math.floor(0.6 * slot).toInt(), box.height)
        assertTrue(legend.height > box.height)
        assertTrue(box.canScrollVertically(1))
        val less = legend.getChildAt(legend.childCount - 1)
        box.scrollTo(0, legend.height - box.height)
        assertTrue("the Less chip scrolls into view", less.bottom - box.scrollY <= box.height && less.top - box.scrollY >= 0)
    }
}
