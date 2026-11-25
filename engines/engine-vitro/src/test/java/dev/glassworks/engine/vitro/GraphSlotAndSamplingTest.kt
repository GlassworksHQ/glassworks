/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import dev.glassworks.engine.vitro.cards.ChartScene
import dev.glassworks.engine.vitro.cards.ChartView
import dev.glassworks.engine.vitro.cards.GraphWork
import dev.glassworks.engine.vitro.cards.SlotChart
import dev.glassworks.engine.vitro.cards.StatisticsChartData
import dev.glassworks.engine.vitro.cards.measureInSlot
import dev.glassworks.engine.vitro.ui.VitroFonts
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** Graph cards in a fixed grid slot, and the statistics chart's down-sampling off the main thread. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GraphSlotAndSamplingTest {
    private val context: Context = org.robolectric.RuntimeEnvironment.getApplication()

    // --- fixed slots -----------------------------------------------------------------------------------------------

    /** A legend of a fixed height, like `ChartLegendView` (which takes an exact height when a slot caps it). */
    private class FixedLegend(context: Context, private val natural: Int) : View(context) {
        override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(View.MeasureSpec.getSize(w),
            if (View.MeasureSpec.getMode(h) == View.MeasureSpec.EXACTLY) View.MeasureSpec.getSize(h) else natural)
    }

    /** A card like the graph cards: a header, then the chart and its legend, measured into its slot. */
    private class Card(context: Context, val chart: ChartView, val legend: View, headerPx: Int) : FrameLayout(context) {
        init {
            val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, 16) }
            col.addView(View(context), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, headerPx))
            col.addView(chart, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            col.addView(legend, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(col, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        override fun onMeasure(w: Int, h: Int) =
            measureInSlot(this, listOf(SlotChart(chart, legend, 233f)), w, h, 1f) { a, b -> super.onMeasure(a, b) }
    }

    private fun layout(v: View, w: Int, h: Int) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
    }

    @Test
    fun aFixedSlotShrinksTheChartSoChartAndLegendFillIt() {
        val heights = ArrayList<Float>()
        val chart = ChartView(context, 1f).apply { heightCss = 233f; setSizedScene { _, h -> heights += h; ChartScene() } }
        val card = Card(context, chart, FixedLegend(context, 36), headerPx = 44)
        // A 4-row slot: 4 x 56 + 3 x 8 = 248 px. Header 44, padding 16, legend 36: the chart gets 152.
        layout(card, 466, 248)
        assertEquals(152, chart.measuredHeight)
        assertEquals(44 + 152, chart.bottom)
        assertEquals(248 - 16, (chart.parent as ViewGroup).getChildAt(2).bottom)
        // The paths were built for the new height, and again when the slot changes.
        assertEquals(152f, heights.last(), 0f)
        layout(card, 466, 304)
        assertEquals(208, chart.measuredHeight)
        assertEquals(208f, heights.last(), 0f)
    }

    @Test
    fun aLegendTakesAtMostSixtyPercentOfItsChartsShare() {
        val chart = ChartView(context, 1f).apply { heightCss = 233f; setScene { ChartScene() } }
        val legend = FixedLegend(context, 200)
        val card = Card(context, chart, legend, headerPx = 0)
        // The share is 116 px: the legend keeps 69 (60 %, floored), the chart the other 47.
        layout(card, 466, 132)
        assertEquals(69, legend.measuredHeight)
        assertEquals(47, chart.measuredHeight)
    }

    // --- statistics down-sampling ---------------------------------------------------------------------------------

    private val start = 1_790_000_000_000L
    private val fiveMinutes = 300_000L

    /**
     * 30 days of 5-minute statistics (8640 rows): a slow wave with noisy min and max around the mean, and two
     * missing hours, where the chart has gaps.
     */
    private fun statistics(): JSONObject {
        val rows = JSONArray()
        var i = 0
        val rnd = java.util.Random(7)
        while (i < 8640) {
            if (i in 3000 until 3012 || i in 6000 until 6012) { i++; continue }
            val t = start + i * fiveMinutes
            val mean = 20 + 3 * kotlin.math.sin(i / 288.0 * Math.PI)
            rows.put(JSONObject().put("start", t).put("end", t + fiveMinutes)
                .put("mean", mean).put("min", mean - 0.5 - rnd.nextDouble()).put("max", mean + 0.5 + rnd.nextDouble()))
            i++
        }
        return JSONObject().put("sensor.t", rows)
    }

    private fun input(result: JSONObject) = StatisticsChartData.Input(
        ids = listOf("sensor.t"), result = result, statTypes = listOf("mean", "min", "max"), hideLegend = false,
        names = mapOf("sensor.t" to "T"), current = mapOf("sensor.t" to 21.0), statUnits = mapOf("sensor.t" to "°C"),
        unit = "°C", colors = listOf(0xFF4269D0.toInt()), nowMs = start + 8640 * fiveMinutes,
        minYAxis = null, maxYAxis = null, fitYData = false,
    )

    @Test
    fun statisticsAreDownSampledKeepingTheBandsEdgesAndGaps() {
        val inp = input(statistics())
        val built = StatisticsChartData.build(inp)
        val out = StatisticsChartData.prepare(inp, 466f, 1.5f, VitroFonts.system(400))
        // min (LTTB), mean (minmax), max (LTTB, with the band's area).
        assertEquals(3, out.lines.size)
        for ((raw, line) in built.series.zip(out.lines)) {
            assertTrue("sampled to ${line.xs.size} of ${raw.xs.size}", line.xs.size < raw.xs.size / 2)
            // Both gaps survive: a null right where each missing hour starts.
            val gaps = line.xs.indices.filter { line.ys[it].isNaN() }.map { line.xs[it] }
            assertEquals(listOf((start + 3000 * fiveMinutes).toDouble(), (start + 6000 * fiveMinutes).toDouble()), gaps)
        }
        // minmax keeps every frame's lowest and highest mean, so the extremes stay.
        val meanRaw = built.series[1].ys.filter { !it.isNaN() }
        val mean = out.lines[1].ys.filter { !it.isNaN() }
        assertEquals(meanRaw.max(), mean.max(), 0.0)
        assertEquals(meanRaw.min(), mean.min(), 0.0)
        // The band's area runs between the top edge's points and the bottom edge's values at the same rows.
        val top = out.lines[2]
        val bottom = top.bandBottom!!
        assertArrayEquals(top.xs, bottom.xs, 0.0)
        // (A gap's x holds two rows, the value held to it and the null: the value is the one the area uses.)
        val minAt = built.series[0].xs.indices.filter { !built.series[0].ys[it].isNaN() }
            .associateBy({ built.series[0].xs[it] }, { built.series[0].ys[it] })
        for (k in top.xs.indices) {
            if (top.ys[k].isNaN()) continue
            assertEquals(minAt.getValue(top.xs[k]), bottom.ys[k], 0.0)
            assertTrue(top.ys[k] >= bottom.ys[k])
        }
    }

    @Test
    fun hourlyStatisticsOverAWeekAreNotSampled() {
        val rows = JSONArray()
        for (i in 0 until 168) {
            val t = start + i * 3_600_000L
            rows.put(JSONObject().put("start", t).put("end", t + 3_600_000L).put("mean", 20.0 + i % 5).put("min", 19.0).put("max", 26.0))
        }
        val inp = input(JSONObject().put("sensor.t", rows))
        val built = StatisticsChartData.build(inp)
        val out = StatisticsChartData.prepare(inp, 466f, 1.5f, VitroFonts.system(400))
        for ((raw, line) in built.series.zip(out.lines)) assertArrayEquals(raw.xs, line.xs, 0.0)
    }

    @Test
    fun theGraphThreadDrawsTheSameAsTheMainThread() {
        val inp = input(statistics())
        val sync = StatisticsChartData.prepare(inp, 466f, 1.5f, VitroFonts.system(400))
        var async: StatisticsChartData.Prepared? = null
        GraphWork.run(Handler(Looper.getMainLooper()), { StatisticsChartData.prepare(inp, 466f, 1.5f, VitroFonts.system(400)) }) { async = it }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (async == null && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        val a = async!!
        assertEquals(0, GraphWork.pending.get())
        assertEquals(sync.lines.size, a.lines.size)
        for ((s, b) in sync.lines.zip(a.lines)) {
            assertArrayEquals(s.xs, b.xs, 0.0)
            assertArrayEquals(s.ys, b.ys, 0.0)
            assertEquals(s.color, b.color)
            s.bandBottom?.let { assertArrayEquals(it.ys, b.bandBottom!!.ys, 0.0) }
        }
        assertEquals(sync.legend.map { it.name }, a.legend.map { it.name })
        assertEquals(sync.minX, a.minX)
        assertEquals(sync.maxX, a.maxX)
    }

    @Test
    fun lttbKeepsTheFirstAndLastPointAndAFramesFirstNull() {
        val v = DoubleArray(100) { if (it in 40..41) Double.NaN else (it % 7).toDouble() }
        val keep = StatisticsChartData.lttbIndices(v, 25f)
        assertEquals(0, keep.first())
        assertEquals(99, keep.last())
        assertTrue(40 in keep.toList())
        assertTrue(keep.toList() == keep.sorted())
        // No more than about one point per pixel: nothing to do.
        assertEquals(100, StatisticsChartData.lttbIndices(v, 80f).size)
    }

    @Test
    fun aBucketMissingABandEdgeStaysANullForLttb() {
        val rows = JSONArray()
        for (i in 0 until 4) {
            val t = start + i * fiveMinutes
            val row = JSONObject().put("start", t).put("end", t + fiveMinutes).put("mean", -5.0).put("min", -10.0)
            if (i != 2) row.put("max", 0.0)
            rows.put(row)
        }
        val top = StatisticsChartData.build(input(JSONObject().put("sensor.t", rows))).series.single { it.bandBottom != null }
        // Rows 0, 1 and 3 have both edges (height 10); row 2 has no max, so no height to choose by.
        assertEquals(listOf(10.0, 10.0, null, 10.0), top.sampleBy.take(4).map { if (it.isNaN()) null else it })
    }
}
