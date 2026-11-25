/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.cards

import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.util.Log
import dev.glassworks.engine.vitro.ui.CardSurface
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Graph work off the main thread: building and down-sampling a chart's series. One thread for every card, so a
 * dashboard of graphs never competes with itself, and each result reaches its card through the main looper.
 */
object GraphWork {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "vitro-graph").apply { isDaemon = true } }

    /** Jobs started and not yet handed to the main thread (the parity harness waits for none before drawing). */
    val pending = AtomicInteger()

    /** Runs [work] on the graph thread and [done] with its result on [main]; a failed job is logged and dropped. */
    fun <T> run(main: Handler, work: () -> T, done: (T) -> Unit) {
        pending.incrementAndGet()
        executor.execute {
            val result = runCatching(work)
            main.post {
                try {
                    result.onSuccess(done).onFailure { Log.w("VitroGraph", "graph data failed", it) }
                } finally {
                    pending.decrementAndGet()
                }
            }
        }
    }
}

/**
 * The statistics graph's data (`statistics-chart-data.ts`) and the down-sampling HA's chart applies to it before it
 * draws, as pure functions of what the card read on the main thread, so they can run on [GraphWork]'s thread:
 *
 * - every series but a band's edges: `ha-chart-base`'s `minmax` (`downSampleLineData`), one frame per device pixel of
 *   the chart's width, which keeps each frame's lowest and highest point and its gap markers;
 * - a band's edges (`sampling: "lttb"`): ECharts' own `dataSample` processor when a series has more points than the
 *   grid has device pixels, which keeps the gaps (a frame's first null) and leaves the band's area on the top edge's
 *   points, as ECharts' stack does.
 */
object StatisticsChartData {
    /** What the card read on the main thread: the answer, and the names, states and units of its statistics. */
    class Input(
        val ids: List<String>,
        val result: JSONObject,
        val statTypes: List<String>,
        val hideLegend: Boolean,
        val names: Map<String, String>,
        /** Each statistic's entity state now, when it's a number. */
        val current: Map<String, Double?>,
        /** Each statistic's display unit (`getDisplayUnit`). */
        val statUnits: Map<String, String?>,
        /** The chart's unit. */
        val unit: String?,
        /** The graph colors by index. */
        val colors: List<Int>,
        val nowMs: Long,
        val minYAxis: Double?,
        val maxYAxis: Double?,
        val fitYData: Boolean,
    )

    /** One series before sampling: its points, and for a band's top edge the band's bottom edge at the same rows. */
    class Series(
        val xs: DoubleArray, val ys: DoubleArray, val color: Int, val areaColor: Int,
        /** Sampled by ECharts' LTTB (a band's edge), else by `minmax`. */
        val lttb: Boolean,
        /** The values LTTB chooses by: a band top's height over its bottom (`|top - bottom|`), else [ys]. */
        val sampleBy: DoubleArray,
        /** A band's top edge: the bottom edge's values at the same rows, the area's lower side. */
        val bandBottom: DoubleArray?,
    )

    class Built(val series: List<Series>, val legend: List<LegendItem>, val minX: Long, val maxX: Long)

    /** The chart, ready to draw: its sampled lines, legend and the time axis' span. */
    class Prepared(
        val lines: List<ChartLine>, val legend: List<LegendItem>, val minX: Long, val maxX: Long,
        private val input: Input,
    ) {
        /** The line chart's spec for a [heightCss] high chart. */
        fun spec(heightCss: Float): LineChartSpec {
            // ha-chart-base: differenceInMinutes(axis.max || new Date(), axis.min); the statistics chart sets no max.
            val minutes = floor((input.nowMs - minX) / 60000.0)
            val dayDiff = minutes / 60 / 24
            val minInterval = if (dayDiff >= 89) 28 * ECharts.DAY else if (dayDiff > 2) ECharts.DAY else null
            return LineChartSpec(lines, minX, maxX, input.unit, heightCss, true, input.minYAxis, input.maxYAxis,
                minInterval = minInterval, labelMinutes = minutes, fitYData = input.fitYData)
        }
    }

    /** [build] then [sample], for a [widthCss] wide chart at [density]; [typeface] measures the y labels. */
    fun prepare(input: Input, widthCss: Float, density: Float, typeface: Typeface?): Prepared {
        val built = build(input)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.typeface = typeface; textSize = 12f }
        val measure: (String) -> Float = { ceil(paint.measureText(it).toDouble()).toFloat() }
        return sample(input, built, widthCss, density, measure)
    }

    /** `generateStatisticsChartData` for line charts: per statistic a series per type, its band and the legend. */
    fun build(input: Input): Built {
        val out = ArrayList<Series>()
        val legendItems = ArrayList<LegendItem>()
        val statLegend = ArrayList<LegendItem>()
        var colorIndex = 0
        var minX = Long.MAX_VALUE
        var maxX = Long.MIN_VALUE
        val result = input.result
        val statTypes = input.statTypes
        // The chart's end (statistics-chart-data): the latest bucket start of any statistic, never after now.
        val nowD = input.nowMs.toDouble()
        val limit = min(nowD, input.ids.mapNotNull { id ->
            result.optJSONArray(id)?.takeIf { it.length() > 0 }?.let { a -> StatisticsGraphCardView.rowTime(a.optJSONObject(a.length() - 1)?.opt("start")) }
        }.maxOrNull() ?: nowD)
        for (id in input.ids) {
            val rows = result.optJSONArray(id) ?: continue
            if (rows.length() == 0) continue
            val stats = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
            val color = input.colors.getOrElse(colorIndex++) { input.colors.lastOrNull() ?: 0 }
            val name = input.names[id] ?: id
            fun has(type: String) = stats.any { it.has(type) && !it.isNull(type) }
            val hasMean = "mean" in statTypes && has("mean")
            val hasMax = "max" in statTypes && has("max")
            val hasMin = "min" in statTypes && has("min")
            val drawBands = listOf(hasMean, hasMax, hasMin).count { it } > 1
            val bandTop = if (hasMax) "max" else "mean"
            val bandBottom = if (hasMin) "min" else "mean"
            val sorted = if (drawBands) StatisticsGraphCardView.sortBandTypes(statTypes) else statTypes
            val types = sorted.filter { has(it) }
            val isBandTop: (String) -> Boolean = { t -> drawBands && t == bandTop && bandTop != bandBottom }
            // Points per type: each stat at its start, gaps where a stat doesn't start where the last one ended,
            // then the last one's end. A band's top also keeps its bottom edge and its height at the same rows.
            val xs = types.associateWith { ArrayList<Double>() }
            val ys = types.associateWith { ArrayList<Double>() }
            val heights = types.associateWith { ArrayList<Double>() }
            val bottoms = types.associateWith { ArrayList<Double>() }
            fun push(t: String, x: Double, y: Double, bottom: Double, height: Double) {
                xs.getValue(t) += x; ys.getValue(t) += y
                if (isBandTop(t)) { bottoms.getValue(t) += bottom; heights.getValue(t) += height }
            }
            var prevStart: Double? = null
            var prevEnd: Double? = null
            var prevVals: Map<String, Double>? = null
            var prevBottom = Double.NaN
            var prevHeight = Double.NaN
            // A cumulative `sum` starts at zero in the window (its first value is the baseline).
            var firstSum: Double? = null
            for (st in stats) {
                val start = StatisticsGraphCardView.rowTime(st.opt("start")) ?: continue
                if (prevStart == start) continue
                val rowEnd = StatisticsGraphCardView.rowTime(st.opt("end")) ?: continue
                prevStart = start
                // pushData's limit: a bucket ends at its end or at the chart's end, whichever comes first.
                if (start > limit) continue
                val end = min(rowEnd, limit)
                fun value(t: String) = if (st.has(t) && !st.isNull(t)) st.optDouble(t) else Double.NaN
                val vals = types.associateWith { t ->
                    val v = value(t)
                    if (t != "sum") v
                    else if (firstSum == null) { firstSum = v.takeIf { !it.isNaN() }; 0.0 }
                    else (if (v.isNaN()) 0.0 else v) - firstSum!!
                }
                // The band's top row is [|top - bottom|, top]: LTTB chooses by the height. A bucket missing either
                // edge has none, so it stays a null for LTTB and the gap Vitro draws there survives sampling.
                val bottom = value(bandBottom)
                val topValue = value(bandTop)
                val height = if (topValue.isNaN() || bottom.isNaN()) Double.NaN else abs(topValue - bottom)
                if (prevEnd != null && prevVals != null && prevEnd != start) {
                    for (t in types) {
                        push(t, prevEnd, prevVals.getValue(t), prevBottom, prevHeight)
                        push(t, prevEnd, Double.NaN, Double.NaN, Double.NaN)
                    }
                }
                for (t in types) push(t, start, vals.getValue(t), bottom, height)
                prevEnd = end
                prevVals = vals
                prevBottom = bottom
                prevHeight = height
            }
            if (prevEnd != null && prevVals != null) for (t in types) push(t, prevEnd, prevVals.getValue(t), prevBottom, prevHeight)
            // The current state at now when the statistics reach within 10 minutes of it, in the chart's unit (or
            // when either is unknown): a live value in another unit isn't converted.
            val current = input.current[id]
            val statUnit = input.statUnits[id]
            val sameUnit = input.unit == null || statUnit == null || input.unit == statUnit
            if (nowD - limit <= 600_000 && current != null && sameUnit) {
                // For a band, the current value is both its top and its bottom: its height is 0.
                for (t in types) if (t != "sum" && t != "change") push(t, nowD, current, current, 0.0)
            }
            for (t in types) {
                val band = drawBands && (t == bandTop || t == bandBottom)
                val dim = band && hasMin && hasMax && hasMean
                // A hidden legend hides the band's edges too (`color + "00"`); shown, they're at half opacity.
                val lineColor = if (dim) CardSurface.withAlpha(color, if (input.hideLegend) 0f else 0x7F / 255f) else color
                val lx = xs.getValue(t).toDoubleArray()
                val ly = ys.getValue(t).toDoubleArray()
                val top = isBandTop(t)
                out += Series(lx, ly, lineColor, CardSurface.withAlpha(color, 0x3F / 255f), lttb = band,
                    sampleBy = if (top) heights.getValue(t).toDoubleArray() else ly,
                    bandBottom = if (top) bottoms.getValue(t).toDoubleArray() else null)
                legendItems += LegendItem("$id-$t", "$name ($t)", lineColor, null)
                // With several statistics, one entry each: its mean, else its first series.
                if (if (hasMean) t == "mean" else statLegend.none { it.id == id }) statLegend += LegendItem(id, name, lineColor, null)
                lx.forEach { x -> if (!x.isNaN()) { minX = min(minX, x.toLong()); maxX = max(maxX, x.toLong()) } }
            }
        }
        // One statistic: the base chart's legend lists every series; several: one entry per statistic.
        val legend = if (input.hideLegend) emptyList() else if (input.ids.size > 1) statLegend else legendItems
        return Built(out, legend, minX, maxX)
    }

    /**
     * Down-samples [built] for a [widthCss] wide chart at [density], as HA's chart does before it draws (see the
     * object's comment). [measure] gives a y label's width, which sets the grid's width for LTTB's rate.
     */
    fun sample(input: Input, built: Built, widthCss: Float, density: Float, measure: (String) -> Float): Prepared {
        if (built.series.isEmpty()) return Prepared(emptyList(), built.legend, built.minX, built.maxX, input)
        // The grid ECharts samples for: the chart less its labels (containLabel), measured on the unsampled data.
        val raw = built.series.map { ChartLine(it.xs, it.ys, it.color) }
        val labels = HaLineChart.yAxis(Prepared(raw, built.legend, built.minX, built.maxX, input).spec(0f), measure).second
        val gridWidth = widthCss - 1f - (labels.maxOfOrNull(measure) ?: 0f) - 8f - 1f
        val frames = widthCss * density
        // ha-chart-base's minmax frames run from the x axis' min (the earliest point); the statistics chart sets no max.
        val minX = built.minX.toDouble()
        val lines = built.series.map { s ->
            if (s.lttb) {
                val keep = lttbIndices(s.sampleBy, gridWidth * density)
                val xs = DoubleArray(keep.size) { s.xs[keep[it]] }
                val ys = DoubleArray(keep.size) { s.ys[keep[it]] }
                val bottom = s.bandBottom?.let { b -> ChartLine(xs, DoubleArray(keep.size) { b[keep[it]] }, 0, smooth = 0.4) }
                ChartLine(xs, ys, s.color, 1.5f, smooth = 0.4, bandBottom = bottom, areaColor = s.areaColor)
            } else {
                val sampled = HaGraph.downSample(s.xs.indices.map { doubleArrayOf(s.xs[it], s.ys[it]) }, frames.toDouble(), minX, null, false)
                ChartLine(DoubleArray(sampled.size) { sampled[it][0] }, DoubleArray(sampled.size) { sampled[it][1] }, s.color, 1.5f, smooth = 0.4)
            }
        }
        return Prepared(lines, built.legend, built.minX, built.maxX, input)
    }

    /**
     * ECharts' `dataSample` for `sampling: "lttb"`: nothing for 10 points or fewer, or when there are no more than
     * about one per device pixel of the grid ([sizePx]); else `DataStore.lttbDownSample` at that rate. Returns the
     * indices of [values] kept (NaN is a null).
     */
    fun lttbIndices(values: DoubleArray, sizePx: Float): IntArray {
        val len = values.size
        val all = IntArray(len) { it }
        if (len <= 10 || sizePx <= 0f) return all
        val rate = (len / sizePx.toDouble()).let { if (it.isFinite()) jsRound(it) else return all }
        if (rate <= 1) return all
        val frameSize = rate.toInt()
        val out = ArrayList<Int>(min((ceil(len.toDouble() / frameSize).toInt() + 2) * 2, len))
        var current = 0
        out += current
        var i = 1
        while (i < len - 1) {
            val nextFrameStart = min(i + frameSize, len - 1)
            val nextFrameEnd = min(i + frameSize * 2, len)
            val avgX = (nextFrameEnd + nextFrameStart) / 2.0
            var avgY = 0.0
            for (idx in nextFrameStart until nextFrameEnd) {
                val y = values[idx]
                if (y.isNaN()) continue
                avgY += y
            }
            avgY /= (nextFrameEnd - nextFrameStart)
            val frameStart = i
            val frameEnd = min(i + frameSize, len)
            val pointAX = (i - 1).toDouble()
            val pointAY = values[current]
            var maxArea = -1.0
            var next = frameStart
            var firstNaN = -1
            var countNaN = 0
            for (idx in frameStart until frameEnd) {
                val y = values[idx]
                if (y.isNaN()) {
                    countNaN++
                    if (firstNaN < 0) firstNaN = idx
                    continue
                }
                val area = abs((pointAX - avgX) * (y - pointAY) - (pointAX - idx) * (avgY - pointAY))
                if (area > maxArea) { maxArea = area; next = idx }
            }
            if (countNaN > 0 && countNaN < frameEnd - frameStart) {
                // A frame's first null stays, in order, so a gap survives.
                out += min(firstNaN, next)
                next = max(firstNaN, next)
            }
            out += next
            current = next
            i += frameSize
        }
        out += len - 1
        return out.toIntArray()
    }

    /** JavaScript's `Math.round`: halves round up. */
    private fun jsRound(v: Double): Double = floor(v + 0.5)
}
