/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.cards

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.HaDisplay
import dev.glassworks.engine.vitro.model.ActionConfig
import dev.glassworks.engine.vitro.model.CardConfig
import dev.glassworks.engine.vitro.ui.CardSurface
import dev.glassworks.engine.vitro.ui.MdiIconView
import dev.glassworks.engine.vitro.ui.VitroCardContext
import dev.glassworks.engine.vitro.ui.VitroFonts
import dev.glassworks.ui.Inter
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// ---------------------------------------------------------------------------------------------
// The data: HA's computeHistory, mergeHistoryResults and the chart data modules
// ---------------------------------------------------------------------------------------------

/** A state of a line chart (`LineChartState`): its value text, when it took it (ms), and kept attributes. */
class LineState(val state: String, val lastChanged: Double)

/** One entity of a line chart (`LineChartEntity`), with the statistics that come before its first state. */
class LineEntity(val entityId: String, val name: String, val states: List<LineState>, var statistics: List<LineState>? = null)

/** One line chart: entities sharing a unit (`LineChartUnit`). */
class LineUnit(val unit: String, val data: MutableList<LineEntity>)

/** One timeline row (`TimelineEntity`). */
class TimelineRow(val entityId: String, val name: String, val data: List<TimelineState>)
class TimelineState(val state: String, val label: String, val lastChanged: Double)

class HistoryResult(val line: List<LineUnit>, val timeline: List<TimelineRow>)

/** `src/data/history.ts` and the chart data modules of `src/components/chart/`, for the history and statistics cards. */
object HaHistory {
    private const val BLANK_UNIT = " "
    private val NUMERIC_DOMAINS = setOf("counter", "input_number", "number")
    private val USE_LAST_UPDATED = setOf("climate", "humidifier", "water_heater")

    /** HA's numeric sensor device classes (`isNumericSensorDeviceClass`): every one but these. */
    private val NON_NUMERIC_SENSOR_CLASSES = setOf("date", "enum", "timestamp")

    /** `computeHistory`: line charts per unit, timelines for the rest. [forceNumeric] for statistics. */
    fun compute(store: (String) -> EntityState?, history: Map<String, List<HistoryState>>, entityIds: List<String>, temperatureUnit: String?, forceNumeric: Boolean = false): HistoryResult {
        val local = LinkedHashMap<String, List<HistoryState>>()
        for (id in LinkedHashSet(entityIds + history.keys)) {
            val h = history[id]
            if (h != null) local[id] = h
            else store(id)?.let { st -> local[id] = listOf(HistoryState(st.state, st.lastUpdated)) }
        }
        val lineGroups = LinkedHashMap<String, LinkedHashMap<String, MutableList<HistoryState>>>()
        val timeline = ArrayList<TimelineRow>()
        for ((id, states) in local) {
            if (states.isEmpty()) continue
            val domain = id.substringBefore('.')
            val current = store(id)
            val fromHistory = if (current != null || domain in NUMERIC_DOMAINS) null
                else states.firstOrNull { it.a != null && (it.a.has("unit_of_measurement") || it.a.has("state_class")) }
            val numeric = forceNumeric || domain in NUMERIC_DOMAINS ||
                (current != null && (current.attributes.containsKey("unit_of_measurement") || current.attributes.containsKey("state_class"))) ||
                (current != null && domain == "sensor" && current.deviceClass != null && current.deviceClass !in NON_NUMERIC_SENSOR_CLASSES) ||
                fromHistory != null
            val unit: String? = if (numeric) {
                current?.unit ?: fromHistory?.a?.optString("unit_of_measurement")?.takeIf { it.isNotEmpty() } ?: BLANK_UNIT
            } else when (domain) {
                "climate", "water_heater" -> temperatureUnit
                "humidifier" -> "%"
                // A zone's state is how many people are in it (`ui.dialogs.more_info_control.zone.graph_unit`).
                "zone" -> "People in zone"
                else -> null
            }
            if (unit == null) {
                timeline += timelineRow(id, states, current)
            } else {
                lineGroups.getOrPut(unit) { LinkedHashMap() }.getOrPut(id) { ArrayList() }.addAll(states)
            }
        }
        val lines = lineGroups.map { (unit, entities) ->
            LineUnit(unit, entities.map { (id, states) -> lineEntity(id, states, store(id)) }.toMutableList())
        }
        return HistoryResult(lines, timeline)
    }

    private fun lineEntity(id: String, states: List<HistoryState>, current: EntityState?): LineEntity {
        val useLu = id.substringBefore('.') in USE_LAST_UPDATED
        val out = ArrayList<LineState>(states.size)
        for (s in states) {
            val p = LineState(s.s, (if (useLu) s.lu else (s.lc.takeIf { !it.isNaN() && it != 0.0 } ?: s.lu)) * 1000)
            val n = out.size
            if (n > 1 && out[n - 1].state == p.state && out[n - 2].state == p.state) continue
            out += p
        }
        return LineEntity(id, chartName(id, current), out)
    }

    private fun timelineRow(id: String, states: List<HistoryState>, current: EntityState?): TimelineRow {
        val data = ArrayList<TimelineState>()
        for (s in states) {
            if (data.isNotEmpty() && data.last().state == s.s) continue
            val label = current?.copy(state = s.s)?.plainState() ?: s.s
            data += TimelineState(s.s, label, (s.lc.takeIf { !it.isNaN() && it != 0.0 } ?: s.lu) * 1000)
        }
        return TimelineRow(id, chartName(id, current), data)
    }

    /** The default name of a chart's entity: `formatEntityName` with HA's chart separator, "Device ▸ Entity". */
    private fun chartName(id: String, current: EntityState?): String =
        if (current == null) id else HaDisplay.deviceEntityName(id, " ▸ ") ?: current.friendlyName

    /** `convertStatisticsToHistory`: hourly means (or states) as the statistics part of line charts. */
    fun statisticsToHistory(store: (String) -> EntityState?, statistics: JSONObject, statisticIds: List<String>): HistoryResult {
        val hist = LinkedHashMap<String, List<HistoryState>>()
        for (id in statisticIds) {
            val rows = statistics.optJSONArray(id) ?: continue
            val list = ArrayList<HistoryState>(rows.length())
            for (i in 0 until rows.length()) {
                val r = rows.optJSONObject(i) ?: continue
                val v = if (r.has("mean") && !r.isNull("mean")) r.opt("mean") else r.opt("state")
                val end = r.optDouble("end") / 1000.0
                list += HistoryState(jsString(v), end, JSONObject()).also { it.lc = end }
            }
            hist[id] = list
        }
        val res = compute(store, hist, emptyList(), null, forceNumeric = true)
        for (u in res.line) for (e in u.data) {
            e.statistics = e.states
        }
        return HistoryResult(res.line.map { u -> LineUnit(u.unit, u.data.map { LineEntity(it.entityId, it.name, emptyList(), it.statistics) }.toMutableList()) }, res.timeline)
    }

    /** JavaScript's `String(number)` for the values HA turns back into numbers. */
    private fun jsString(v: Any?): String = when (v) {
        null, JSONObject.NULL -> "null"
        is Number -> v.toDouble().let { d -> if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) d.toLong().toString() else d.toString() }
        else -> v.toString()
    }

    /** `mergeHistoryResults`: statistics fill each entity's line before its first recorded state. */
    fun merge(history: HistoryResult, lts: HistoryResult?): HistoryResult {
        if (lts == null) return history
        val lookup = LinkedHashMap<String, Pair<LineUnit?, LineUnit?>>()
        for (u in history.line) lookup[u.unit + "_"] = u to null
        fun addLts(u: LineUnit) {
            val key = u.unit + "_"
            val e = lookup[key]
            if (e?.second != null) e.second!!.data.addAll(u.data)
            else lookup[key] = (e?.first) to LineUnit(u.unit, u.data.toMutableList())
        }
        // Statistics fetched before an entity's state arrived have no unit: each takes the unit of the history line
        // its entity is in (or stays apart when there is none).
        val historyUnitOf = HashMap<String, String>()
        for (u in history.line) for (e in u.data) historyUnitOf[e.entityId] = u.unit
        for (u in lts.line) {
            if (u.unit != BLANK_UNIT) { addLts(u); continue }
            for (e in u.data) addLts(LineUnit(historyUnitOf[e.entityId] ?: u.unit, mutableListOf(e)))
        }
        val out = ArrayList<LineUnit>()
        for ((h, l) in lookup.values) {
            if (h == null || l == null) { out += (h ?: l)!!; continue }
            val merged = LineUnit(h.unit, ArrayList())
            val hBy = h.data.associateBy { it.entityId }
            val lBy = l.data.associateBy { it.entityId }
            for (id in LinkedHashSet(hBy.keys + lBy.keys)) {
                val hd = hBy[id]
                val ld = lBy[id]
                if (hd == null || ld == null) { merged.data += (hd ?: ld)!!; continue }
                val stats = ld.statistics.orEmpty()
                val oldest = hd.states.firstOrNull()?.lastChanged?.takeIf { it != 0.0 } ?: ((stats.lastOrNull()?.lastChanged ?: 0.0) + 1)
                val kept = stats.takeWhile { it.lastChanged < oldest }
                merged.data += if (kept.isEmpty()) hd else LineEntity(hd.entityId, hd.name, hd.states, kept)
            }
            out += merged
        }
        return HistoryResult(out, history.timeline)
    }

    /** JavaScript's `parseFloat` as `safeParseFloat` uses it: the leading number, or null when there's none. */
    fun parseFloatOrNull(s: String): Double? {
        val m = LEADING_FLOAT.find(s.trimStart()) ?: return null
        val v = m.value.toDoubleOrNull() ?: return null
        return v.takeIf { it.isFinite() }
    }

    private val LEADING_FLOAT = Regex("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?")
}

/** The history panel's address for [entityIds] from [startMs], as the cards' header links build it (`createSearchParam`). */
internal fun historyPath(entityIds: List<String>, startMs: Long): String {
    fun enc(v: String) = java.net.URLEncoder.encode(v, "UTF-8")
    return "/history?entity_id=" + enc(entityIds.joinToString(",")) + "&back=1&start_date=" + enc(HaGraph.isoString(startMs))
}

// ---------------------------------------------------------------------------------------------
// Shared views: the card header and the chart legend
// ---------------------------------------------------------------------------------------------

private fun cssText(ctx: VitroCardContext, sizeCss: Float, color: Int, weight: Int = Inter.REGULAR) = TextView(ctx.context).apply {
    setTextSize(TypedValue.COMPLEX_UNIT_DIP, sizeCss * ctx.theme.fontScale)
    setTextColor(color)
    typeface = VitroFonts.typeface(ctx.context, weight, sizeCss)
    includeFontPadding = false
}

/** Makes [tv]'s text (set before) one CSS line box [lineHeight] times its font size, its leading split as Blink does. */
private fun lineBox(ctx: VitroCardContext, tv: TextView, sizeCss: Float, lineHeight: Float) {
    val fm = tv.paint.fontMetrics
    val total = lineHeight * sizeCss * ctx.dp * ctx.theme.fontScale
    val above = CssLineBoxSpan.above(-fm.ascent, fm.descent, total, ctx.dp)
    val text = android.text.SpannableString(tv.text)
    text.setSpan(CssLineBoxSpan(emptyList(), CssLineBoxSpan.Run(0, 0, above, total - above)), 0, text.length, android.text.Spanned.SPAN_INCLUSIVE_INCLUSIVE)
    tv.text = text
}

/**
 * The `h1.card-header` of the history and statistics cards: 24 px, `--ha-line-height-condensed`, `-0.012em`, under
 * 20 px of padding (the cards drop its bottom padding), and the link to the history panel as a 24 px chevron.
 */
private fun cardHeader(ctx: VitroCardContext, title: String, onLink: () -> Unit): View {
    val row = LinearLayout(ctx.context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(ctx.px(16f), Math.round(20f * ctx.dp), ctx.px(16f), 0)
    }
    val tv = cssText(ctx, 24f, ctx.theme.primaryTextColor).apply {
        text = title
        letterSpacing = -0.012f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    lineBox(ctx, tv, 24f, 1.2f)
    row.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    val chevron = MdiIconView(ctx.context).apply {
        setIcon("mdi:chevron-right", ctx.theme.primaryTextColor)
        // HA's aria-label and tooltip for the link: the panel's name.
        contentDescription = "History"
        isClickable = true
        setOnClickListener { onLink() }
    }
    row.addView(chevron, LinearLayout.LayoutParams(ctx.px(24f), ctx.px(24f)).apply { gravity = Gravity.CENTER_VERTICAL })
    return row
}

/** One legend entry: its color, name and (history graph) current value. */
class LegendItem(val id: String, val name: String, val color: Int, val value: String?)

/**
 * `ha-chart-base`'s HTML legend: centered rows (8 px apart) of 24 px items, each a check-circle in the series color
 * (18 px), the name (12 px) and its value in the secondary color; 12 px above it.
 */
class ChartLegendView(private val ctx: VitroCardContext) : ViewGroup(ctx.context) {
    private val gap = 8f * ctx.dp
    var onLabel: ((String) -> Unit)? = null

    fun setItems(items: List<LegendItem>) {
        removeAllViews()
        val multiple = items.size > 1
        for (it in items) {
            val li = LinearLayout(ctx.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ctx.px(2f), 0, ctx.px(2f), 0)
            }
            li.addView(MdiIconView(ctx.context).apply { setIcon("mdi:check-circle", it.color) },
                LinearLayout.LayoutParams(Math.round(18f * ctx.dp), Math.round(18f * ctx.dp)).apply { marginEnd = Math.round(4f * ctx.dp) })
            val label = cssText(ctx, 12f, ctx.theme.primaryTextColor).apply {
                text = it.name
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                isClickable = true
                setOnClickListener { _ -> onLabel?.invoke(it.id) }
            }
            lineBox(ctx, label, 12f, 1.2f)
            if (multiple) label.maxWidth = Math.round(((if (it.value != null) 300f else 220f) - 26f) * ctx.dp)
            li.addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            if (!it.value.isNullOrEmpty()) {
                li.addView(cssText(ctx, 12f, ctx.theme.secondaryTextColor).apply { text = it.value; maxLines = 1 },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = Math.round(4f * ctx.dp) })
            }
            addView(li)
        }
        requestLayout()
    }

    private val itemH get() = Math.round(24f * ctx.dp)
    private val topPad get() = Math.round(12f * ctx.dp)

    private fun rows(width: Int): List<List<View>> {
        val rows = ArrayList<MutableList<View>>()
        var used = 0f
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val w = c.measuredWidth
            if (rows.isEmpty() || used + gap + w > width) {
                rows += mutableListOf(c)
                used = w.toFloat()
            } else {
                rows.last() += c
                used += gap + w
            }
        }
        return rows
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        for (i in 0 until childCount) {
            getChildAt(i).measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(itemH, MeasureSpec.EXACTLY))
        }
        val n = rows(w).size
        setMeasuredDimension(w, if (n == 0) 0 else topPad + n * itemH + Math.round((n - 1) * gap))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var y = topPad.toFloat()
        for (row in rows(r - l)) {
            val total = row.sumOf { it.measuredWidth } + gap * (row.size - 1)
            var x = ((r - l) - total) / 2f
            for (c in row) {
                c.layout(Math.round(x), Math.round(y), Math.round(x) + c.measuredWidth, Math.round(y) + c.measuredHeight)
                x += c.measuredWidth + gap
            }
            y += itemH + gap
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Chart scenes: the line chart, the timeline and the statistics chart
// ---------------------------------------------------------------------------------------------

/** The text measurements HA's chart layout depends on (CSS px, measured in HA's render: ECharts 6 text boxes). */
private object ChartText {
    /** The box of a 12 px axis label, which ECharts' outer bounds keep inside the chart. */
    const val LABEL_H = 13.4f
    /** The box of a 12 px axis name (the unit above the value axis). */
    const val NAME_H = 12f
    /** The box of a 12 px time label below the axis. */
    const val X_LABEL_H = 12f
}

/** One line series of a chart: points (ms, value; NaN breaks the line), its color and how it's drawn. */
class ChartLine(
    val xs: DoubleArray, val ys: DoubleArray, val color: Int,
    val width: Float = 1.5f, val step: Boolean = false, val smooth: Double = 0.0,
    /** A band: the area down to this other line, filled with [areaColor]. */
    val bandBottom: ChartLine? = null, val areaColor: Int = 0,
)

private fun theme(ctx: VitroCardContext) = ctx.theme

/** The time labels under a chart, after ECharts' overlap hiding (higher level first, then in order). */
private fun timeLabels(ticks: List<ECharts.TimeTick>, xOf: (Long) -> Double, minutes: Double, y: Float, color: Int): List<ChartScene.Text> {
    class L(val tick: ECharts.TimeTick, val text: String, val bold: Boolean, val x: Double, val w: Float)
    val labels = ticks.filter { !it.notNice }.map {
        val (text, bold) = HaChartFormat.timeLabel(it.value, minutes)
        L(it, text, bold, xOf(it.value), ChartView.measure(text, 12f, bold))
    }
    val shown = ArrayList<L>()
    for (l in labels.sortedWith(compareByDescending<L> { it.tick.level })) {
        val overlaps = shown.any { o -> l.x - l.w / 2 < o.x + o.w / 2 - 0.05 && o.x - o.w / 2 < l.x + l.w / 2 - 0.05 }
        if (!overlaps) shown += l
    }
    return shown.sortedBy { it.x }.map { ChartScene.Text(it.text, it.x.toFloat(), y, Paint.Align.CENTER, color, it.bold) }
}

/** `boundaryFilter`: the points inside [lo]..[hi] and the nearest one on each side, for the value axis' data extent. */
private fun extentIn(lines: List<ChartLine>, lo: Double, hi: Double): Pair<Double, Double> {
    var min = Double.POSITIVE_INFINITY
    var max = Double.NEGATIVE_INFINITY
    for (l in lines) {
        val n = l.xs.size
        var first = -1
        var last = -1
        for (i in 0 until n) if (l.xs[i] >= lo && l.xs[i] <= hi) { if (first < 0) first = i; last = i }
        val from = if (first < 0) (0 until n).lastOrNull { l.xs[it] < lo } ?: 0 else max(0, first - 1)
        val to = if (first < 0) (0 until n).firstOrNull { l.xs[it] > hi } ?: (n - 1) else min(n - 1, last + 1)
        for (i in from..to) {
            val v = l.ys.getOrNull(i) ?: continue
            if (v.isNaN()) continue
            if (v < min) min = v
            if (v > max) max = v
        }
    }
    return min to max
}

/**
 * A line chart as `state-history-chart-line` and `statistics-chart` set ECharts up: a time x axis with split
 * lines (no axis line), a value y axis with HA's bounds, ticks and labels, the unit as the axis name, and the lines
 * clipped to the grid. [containLabel] lays the grid out like `statistics-chart` (labels inside a 15/1/1/0 rect,
 * with the y axis line), else like the history graph (`grid` 15/yWidth/1/20 inside ECharts 6's outer bounds).
 */
class LineChartSpec(
    val lines: List<ChartLine>,
    val xMin: Long, val xMax: Long,
    val unit: String?,
    val heightCss: Float,
    val containLabel: Boolean,
    val minYAxis: Double? = null,
    val maxYAxis: Double? = null,
    /** The y-axis width shared by the charts of one card (`paddingYAxis`). */
    val paddingYAxis: Float = 0f,
    val minInterval: Long? = null,
    /** The span the time labels are formatted for (`_minutesDifference`), when it isn't the axis' own. */
    val labelMinutes: Double? = null,
    /** `fit_y_data`: a configured bound widens to take in the data (rounded out to a whole number past 1). */
    val fitYData: Boolean = false,
)

object HaLineChart {
    /** The y labels and the width `_formatYAxisLabel` reports for them (`measureTextWidth + 5`, at least 25). */
    fun yAxis(spec: LineChartSpec): Triple<ECharts.ValueScale, List<String>, Float> {
        val (dMin, dMax) = extentIn(spec.lines, spec.xMin.toDouble(), spec.xMax.toDouble())
        // fit_y_data: min(roundYAxis(min, floor), min_y_axis) and max(roundYAxis(max, ceil), max_y_axis), as HA's charts do.
        fun roundY(v: Double, f: (Double) -> Double) = if (kotlin.math.abs(v) < 1) v else f(v)
        val cfgMin = spec.minYAxis?.let { m -> if (spec.fitYData && dMin.isFinite()) min(roundY(dMin, ::floor), m) else m }
        val cfgMax = spec.maxYAxis?.let { m -> if (spec.fitYData && dMax.isFinite()) max(roundY(dMax, ::ceil), m) else m }
        val bounds = HaYBounds.of(dMin, dMax, spec.unit, false, cfgMin, cfgMax)
        val scale = if (dMin.isFinite()) ECharts.valueScale(dMin, dMax, bounds.fixMin, bounds.fixMax, 0.02)
            else ECharts.ValueScale(0.0, 1.0, listOf(0.0, 1.0))
        val labels = scale.ticks.map { v -> HaChartFormat.number(v, if (v == 0.0) 0 else bounds.fractionDigits, bounds.fractionDigits) }
        val width = labels.maxOfOrNull { max(ChartView.measure(it) + 5f, 25f) } ?: 25f
        return Triple(scale, labels, width)
    }

    fun scene(spec: LineChartSpec, widthCss: Float, ctx: VitroCardContext): ChartScene {
        val t = theme(ctx)
        val s = ChartScene()
        val (scale, labels, yWidth) = yAxis(spec)
        val hasName = !spec.unit.isNullOrBlank()
        val left: Float
        val top: Float
        val bottom: Float
        val right: Float
        val labelMargin: Float
        if (spec.containLabel) {
            labelMargin = 8f
            val widest = labels.maxOfOrNull { ChartView.measure(it) } ?: 0f
            left = 1f + widest + labelMargin
            top = 15f + ChartText.LABEL_H / 2
            bottom = 8f + ChartText.X_LABEL_H
            right = 1f
        } else {
            labelMargin = 5f
            left = max(spec.paddingYAxis, yWidth)
            top = max(15f, ChartText.LABEL_H / 2 + if (hasName) ChartText.NAME_H else 0f)
            bottom = max(20f, 8f + ChartText.X_LABEL_H)
            right = 1f
        }
        val grid = RectF(left, top, widthCss - right, spec.heightCss - bottom)
        val span = (spec.xMax - spec.xMin).toDouble().coerceAtLeast(1.0)
        fun xOf(ms: Double) = grid.left + (ms - spec.xMin) / span * grid.width()
        val vSpan = (scale.max - scale.min).takeIf { it != 0.0 } ?: 1.0
        fun yOf(v: Double) = grid.bottom - (v - scale.min) / vSpan * grid.height()
        val divider = t.dividerColor

        // Split lines: horizontal at the value ticks, vertical at the time ticks (extent ends included).
        val grids = Path()
        for (v in scale.ticks) { val y = yOf(v).toFloat(); grids.moveTo(grid.left, y); grids.lineTo(grid.right, y) }
        val ticks = ECharts.timeTicks(spec.xMin, spec.xMax, minInterval = spec.minInterval)
        for (tk in ticks) { val x = xOf(tk.value.toDouble()).toFloat(); grids.moveTo(x, grid.top); grids.lineTo(x, grid.bottom) }
        s.stroke(ChartScene.Stroke(grids, divider, 1f, null))
        // Axis ticks, 5 px outward: the value axis' on the left, the time axis' below.
        val tickPath = Path()
        for (v in scale.ticks) { val y = yOf(v).toFloat(); tickPath.moveTo(grid.left - 5f, y); tickPath.lineTo(grid.left, y) }
        for (tk in ticks) { val x = xOf(tk.value.toDouble()).toFloat(); tickPath.moveTo(x, grid.bottom); tickPath.lineTo(x, grid.bottom + 5f) }
        s.stroke(ChartScene.Stroke(tickPath, divider, 1f, null))
        if (spec.containLabel) {
            s.stroke(ChartScene.Stroke(Path().apply { moveTo(grid.left, grid.top); lineTo(grid.left, grid.bottom) }, divider, 1f, null))
        }

        // The series, clipped to the grid: bands (area between two lines) under their lines, in series order.
        for (l in spec.lines) {
            val xs = DoubleArray(l.xs.size) { xOf(l.xs[it]) }
            val ys = DoubleArray(l.ys.size) { if (l.ys[it].isNaN()) Double.NaN else yOf(l.ys[it]) }
            val (px, py) = if (l.step) ECharts.stepEnd(xs, ys) else xs to ys
            val b = l.bandBottom
            if (b != null && b.xs.size == l.xs.size) {
                val bx = DoubleArray(b.xs.size) { xOf(b.xs[it]) }
                val by = DoubleArray(b.ys.size) { if (b.ys[it].isNaN()) Double.NaN else yOf(b.ys[it]) }
                // One closed area per run of points both edges have: a gap in the statistics stays unfilled.
                val area = Path()
                var i = 0
                while (i < px.size) {
                    while (i < px.size && (py[i].isNaN() || by[i].isNaN())) i++
                    val from = i
                    while (i < px.size && !py[i].isNaN() && !by[i].isNaN()) i++
                    if (i - from < 1) continue
                    ECharts.polyline(px.copyOfRange(from, i), py.copyOfRange(from, i), l.smooth, area, 1f)
                    ECharts.polyline(bx.copyOfRange(from, i), by.copyOfRange(from, i), b.smooth, area, 1f, reverse = true, continuePath = true)
                    area.close()
                }
                s.fill(ChartScene.Fill(area, l.areaColor, grid))
            }
            val path = Path()
            ECharts.polyline(px, py, l.smooth, path, 1f)
            s.stroke(ChartScene.Stroke(path, l.color, l.width, grid))
        }

        // Labels: values right-aligned [labelMargin] left of the axis, times centered 8 px below it.
        val text = t.primaryTextColor
        scale.ticks.forEachIndexed { i, v ->
            s.text(ChartScene.Text(labels[i], grid.left - labelMargin, yOf(v).toFloat(), Paint.Align.RIGHT, text, false))
        }
        if (hasName) {
            // The name sits on top of the top label (ECharts moves it off the label it would overlap).
            s.text(ChartScene.Text(spec.unit!!, grid.left, grid.top - ChartText.LABEL_H / 2 - ChartText.NAME_H / 2, Paint.Align.LEFT, text, false))
        }
        val minutes = spec.labelMinutes ?: floor(span / 60000.0)
        for (lbl in timeLabels(ticks, { xOf(it.toDouble()) }, minutes, grid.bottom + 8f + ChartText.X_LABEL_H / 2, text)) s.text(lbl)
        return s
    }
}

/**
 * `state-history-chart-timeline`: one 20 px bar row per entity, 30 px apart, its name on the left, the states as
 * colored rectangles with their label when it fits, and the time axis below with ticks but no split lines.
 */
object HaTimelineChart {
    const val ROW_HEIGHT = 30f
    const val GRID_BOTTOM = 30f

    /** `labelWidth` of a narrow card: each name's width + 5, at most 105; none when names are hidden (`show_names`). */
    fun labelWidth(rows: List<TimelineRow>, names: Map<String, String>, showNames: Boolean = true): Float =
        if (!showNames) 0f else rows.maxOfOrNull { r -> min(ChartView.measure(names[r.entityId] ?: r.name) + 5f, 105f) } ?: 0f

    fun height(rows: Int) = rows * ROW_HEIGHT + GRID_BOTTOM

    fun scene(rows: List<TimelineRow>, names: Map<String, String>, start: Long, end: Long, paddingYAxis: Float, widthCss: Float, ctx: VitroCardContext, showNames: Boolean = true): ChartScene {
        val t = theme(ctx)
        val s = ChartScene()
        val labelW = if (showNames) max(paddingYAxis, labelWidth(rows, names)) else paddingYAxis
        val h = height(rows.size)
        val grid = RectF(labelW, 10f, widthCss - 1f, h - GRID_BOTTOM)
        val span = (end - start).toDouble().coerceAtLeast(1.0)
        fun xOf(ms: Double) = grid.left + (ms - start) / span * grid.width()
        val band = grid.height() / rows.size.coerceAtLeast(1)
        // Frames are as wide as one device pixel of the chart, snapped to the clock (downSampleTimelineSegments).
        val rawFrame = ceil(span / floor(widthCss * ctx.dp))
        val frameMs = if (rawFrame.isFinite() && rawFrame > 0) HaGraph.snapFrameSize(rawFrame) else 0.0
        rows.forEachIndexed { i, row ->
            val cy = grid.top + band * (i + 0.5f)
            val segs = segments(row, start.toDouble(), end.toDouble())
            for (seg in downSample(segs, frameMs)) {
                val x0 = max(xOf(seg.start).toFloat(), grid.left)
                val x1 = min(xOf(seg.end).toFloat(), grid.right)
                if (x1 <= x0) continue
                val color = stateColor(ctx, row.entityId, seg.state)
                val r = RectF(x0, cy - 10f, x1, cy + 10f)
                s.rect(ChartScene.Rect(r, color))
                val label = seg.label.replace("\n", " ")
                if (ChartView.measure(label) < r.width() - 8f) {
                    val fg = if (HaGraphColors.luminosity(color) > 0.5) Color.BLACK else Color.WHITE
                    s.text(ChartScene.Text(label, r.left + 4f, cy, Paint.Align.LEFT, fg, false))
                }
            }
            if (showNames) s.text(ChartScene.Text(names[row.entityId] ?: row.name, grid.left - 5f, cy, Paint.Align.RIGHT, t.primaryTextColor, false, maxWidth = labelW))
        }
        val ticks = ECharts.timeTicks(start, end)
        val tickPath = Path()
        for (tk in ticks) { val x = xOf(tk.value.toDouble()).toFloat(); tickPath.moveTo(x, grid.bottom); tickPath.lineTo(x, grid.bottom + 5f) }
        s.stroke(ChartScene.Stroke(tickPath, t.dividerColor, 1f, null))
        for (lbl in timeLabels(ticks, { xOf(it.toDouble()) }, floor(span / 60000.0), grid.bottom + 8f + ChartText.X_LABEL_H / 2, t.primaryTextColor)) s.text(lbl)
        return s
    }

    class Segment(val state: String, val label: String, var start: Double, var end: Double)

    private fun segments(row: TimelineRow, start: Double, end: Double): List<Segment> {
        val out = ArrayList<Segment>()
        var prev: TimelineState? = null
        var prevChanged = start
        for (st in row.data) {
            if (st.lastChanged > end) continue
            val p = prev
            if (p == null) {
                prev = st; prevChanged = st.lastChanged
            } else if (st.state != p.state) {
                out += Segment(p.state, p.label, prevChanged, st.lastChanged)
                prev = st; prevChanged = st.lastChanged
            }
        }
        prev?.let { out += Segment(it.state, it.label, prevChanged, end) }
        return out
    }

    /** `downSampleTimelineSegments`: segments narrower than a frame resolve to the state covering most of it. */
    fun downSample(segments: List<Segment>, frameMs: Double): List<Segment> {
        if (frameMs <= 0) return segments
        val result = ArrayList<Segment>()
        fun push(seg: Segment) {
            val last = result.lastOrNull()
            if (last != null && last.state == seg.state && last.end == seg.start) { last.end = seg.end; return }
            result += seg
        }
        var index = 0
        while (index < segments.size) {
            if (segments[index].end - segments[index].start >= frameMs) {
                val g = segments[index]
                push(Segment(g.state, g.label, g.start, g.end))
                index++
                continue
            }
            val from = index
            index++
            while (index < segments.size && segments[index].end - segments[index].start < frameMs &&
                segments[index].start - segments[index - 1].end < frameMs) index++
            collapse(segments, from, index, frameMs, ::push)
        }
        return result
    }

    private fun collapse(segs: List<Segment>, from: Int, to: Int, frameMs: Double, push: (Segment) -> Unit) {
        val runEnd = segs[to - 1].end
        var frameStart = segs[from].start
        var index = from
        while (frameStart < runEnd) {
            val boundary = (floor(frameStart / frameMs) + 1) * frameMs
            val next = if (boundary > frameStart) boundary else frameStart + frameMs
            val frameEnd = if (next < runEnd) next else runEnd
            val durations = LinkedHashMap<String, Double>()
            val labels = HashMap<String, String>()
            var best: String? = null
            var bestDur = 0.0
            var cursor = index
            while (cursor < to && segs[cursor].start < frameEnd) {
                val g = segs[cursor]
                cursor++
                val os = max(g.start, frameStart)
                val oe = min(g.end, frameEnd)
                if (oe <= os) continue
                val d = (durations[g.state] ?: 0.0) + (oe - os)
                durations[g.state] = d
                if (g.state !in labels) labels[g.state] = g.label
                if (d > bestDur) { bestDur = d; best = g.state }
            }
            while (index < to && segs[index].end <= frameEnd) index++
            best?.let { push(Segment(it, labels.getValue(it), frameStart, frameEnd)) }
            frameStart = frameEnd
        }
    }

    /** The domains HA colors by state (`STATE_COLORED_DOMAIN`, `common/entity/state_color.ts`). */
    private val STATE_COLORED = setOf(
        "alarm_control_panel", "alert", "automation", "binary_sensor", "calendar", "camera", "climate", "cover",
        "device_tracker", "fan", "group", "humidifier", "input_boolean", "lawn_mower", "light", "lock", "media_player",
        "person", "plant", "remote", "schedule", "script", "siren", "sun", "switch", "timer", "update", "vacuum",
        "valve", "water_heater", "weather",
    )

    /** Whether HA gives [s] a state color (`stateColorProperties`): a state-colored domain, or a battery sensor. */
    fun isStateColored(s: EntityState): Boolean =
        s.domain in STATE_COLORED || (s.domain == "sensor" && s.deviceClass == "battery")

    /**
     * `computeTimelineColor`'s generic colors: one graph-palette index per state value, in the order they're first
     * seen, resolved against the current theme on every call (a theme change recolors them).
     */
    private val genericColors = HashMap<String, Int>()

    /**
     * `computeTimelineColor`: unavailable and unknown; a state-colored domain's active or inactive color; an option of
     * an `input_select`, `select` or enum sensor by its index among the options; any other value a graph color of its
     * own.
     */
    fun stateColor(ctx: VitroCardContext, entityId: String, state: String): Int {
        val st = ctx.store[entityId] ?: return Color.TRANSPARENT
        if (state == "unavailable") return Color.TRANSPARENT
        if (state == "unknown") return 0xFF606060.toInt()
        if (st.domain in STATE_COLORED) {
            return if (st.copy(state = state).isActive) ctx.theme.activeColor(st.domain) else ctx.theme.stateInactiveColor
        }
        val enumLike = st.domain == "select" || st.domain == "input_select" || (st.domain == "sensor" && st.deviceClass == "enum")
        val options = (st.attributes["options"] as? List<*>)?.map { it.toString() }
        if (enumLike && options != null) {
            val i = options.indexOf(state)
            if (i >= 0) return HaGraphColors.byIndex(ctx.theme, i)
        }
        val index = synchronized(genericColors) { genericColors.getOrPut(state) { genericColors.size } }
        return HaGraphColors.byIndex(ctx.theme, index)
    }
}

// ---------------------------------------------------------------------------------------------
// history-graph
// ---------------------------------------------------------------------------------------------

/**
 * Native HA `history-graph` card (`hui-history-graph-card`): the entities' history from `history/stream` (merged with
 * hourly statistics before it, as HA does), drawn as HA's charts stack them (`state-history-charts`): one timeline
 * for the entities without a unit, then one line chart per unit with its legend. The charts share the y-axis width.
 */
class HistoryGraphCardView(ctx: VitroCardContext, private val config: CardConfig) : CardSurface(ctx) {
    private val entities = config.entities().filter { it.isEntity }
    private val entityIds = entities.map { it.entity }
    private val names: Map<String, String> = entities.mapNotNull { e -> e.name?.let { e.entity to it } }.toMap()
    private val colors: Map<String, Int> = entities.mapNotNull { e ->
        (config.raw.optJSONArray("entities")?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.firstOrNull { it.optString("entity") == e.entity } })
            ?.optString("color")?.takeIf { it.isNotEmpty() }?.let { c -> dev.glassworks.engine.vitro.theme.ThemeResolver.parseColor(c) }?.let { e.entity to it }
    }.toMap()
    private val hours: Double = config.raw.optDouble("hours_to_show", 24.0).takeIf { !it.isNaN() && it != 0.0 } ?: 24.0
    private val charts = LinearLayout(ctx.context).apply { orientation = LinearLayout.VERTICAL }
    private val showNames = config.raw.optBoolean("show_names", true)
    private val info = cssText(ctx, 14f, ctx.theme.secondaryTextColor).apply { gravity = Gravity.CENTER }

    private var stream: HaGraph.Stream? = null
    private var unsubscribe: (() -> Unit)? = null
    private var stateHistory: HistoryResult? = null
    private var statsHistory: HistoryResult? = null
    private var generation = 0

    /** The rejected stream's message: it stays on screen over any history held until a stream delivers again. */
    private var streamError: String? = null

    /** A render skipped while the card was hidden (a cached view): done when it shows again. */
    private var renderPending = false

    init {
        val col = LinearLayout(ctx.context).apply { orientation = LinearLayout.VERTICAL }
        config.title?.let {
            col.addView(cardHeader(ctx, it) {
                // The header's link: the history panel for these entities from the window's start (local hours).
                val cal = Calendar.getInstance().apply { timeInMillis = ctx.now() }
                cal.set(Calendar.HOUR_OF_DAY, (cal.get(Calendar.HOUR_OF_DAY) - hours).toInt())
                ctx.navigate(historyPath(entityIds, cal.timeInMillis))
            })
        }
        val content = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.px(16f), 0, ctx.px(16f), ctx.px(8f))
        }
        info.text = "Loading state history…"
        info.minHeight = Math.round(60f * ctx.dp)
        content.addView(info, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(60f * ctx.dp)))
        content.addView(charts, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(col, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** `_redrawGraph`: once a minute the window follows the clock, from the history already held. */
    private val redraw: Runnable = object : Runnable {
        override fun run() {
            if (stateHistory != null) render()
            ctx.main.postDelayed(this, 60_000L)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        subscribe()
        ctx.main.removeCallbacks(redraw)
        ctx.main.postDelayed(redraw, 60_000L)
    }

    override fun onDetachedFromWindow() {
        ctx.main.removeCallbacks(redraw)
        unsubscribe?.invoke()
        unsubscribe = null
        stream = null
        generation++
        super.onDetachedFromWindow()
    }

    private fun subscribe() {
        if (entityIds.isEmpty() || unsubscribe != null) return
        val gen = ++generation
        val s = HaGraph.Stream(hours)
        stream = s
        val now = ctx.now()
        val msg = HaGraph.historyStreamMessage(entityIds, hours, now) { ctx.store[it] != null }
        unsubscribe = ctx.subscribeWs(msg, { ev ->
            ctx.main.post {
                if (stream !== s) return@post
                streamError = null
                val combined = s.process(ev, ctx.now())
                stateHistory = HaHistory.compute({ ctx.store[it] }, combined, entityIds, temperatureUnit())
                render()
            }
        }, { error ->
            ctx.main.post {
                if (stream !== s) return@post
                // HA shows the error instead of the charts (`ui.components.history_charts.error`).
                stream = null
                unsubscribe = null
                val why = error?.optString("message")?.takeIf { it.isNotEmpty() } ?: error?.optString("code").orEmpty()
                streamError = if (why.isEmpty()) "Unable to load history" else "Unable to load history: $why"
                render()
            }
        })
        if (hours >= 1) {
            // _fetchStatistics: from an hour before the window (setHours on the local clock) to now, hourly.
            val cal = Calendar.getInstance().apply { timeInMillis = now }
            cal.set(Calendar.HOUR_OF_DAY, (cal.get(Calendar.HOUR_OF_DAY) - hours - 1).toInt())
            val stats = JSONObject()
                .put("type", "recorder/statistics_during_period")
                .put("start_time", HaGraph.isoString(cal.timeInMillis))
                .put("end_time", HaGraph.isoString(now))
                .put("statistic_ids", JSONArray(entityIds))
                .put("period", "hour")
                .put("types", JSONArray(listOf("mean", "state")))
            ctx.callWs(stats) { ok, result, _ ->
                ctx.main.post {
                    if (gen != generation) return@post
                    if (ok && result is JSONObject) statsHistory = HaHistory.statisticsToHistory({ ctx.store[it] }, result, entityIds)
                    render()
                }
            }
        }
    }

    private fun temperatureUnit(): String? = ctx.haConfig().optJSONObject("unit_system")?.optString("temperature")?.takeIf { it.isNotEmpty() }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (renderPending && isShown) render()
    }

    private fun render() {
        streamError?.let {
            renderPending = false
            charts.removeAllViews()
            info.text = it
            info.visibility = VISIBLE
            return
        }
        val h = stateHistory ?: return
        // A hidden card (a cached view) keeps its history but builds nothing until it shows.
        if (!isShown && isAttachedToWindow) {
            renderPending = true
            return
        }
        renderPending = false
        val history = HaHistory.merge(h, statsHistory)
        charts.removeAllViews()
        if (history.line.isEmpty() && history.timeline.isEmpty()) {
            info.text = "No state history found."
            info.visibility = VISIBLE
            return
        }
        info.visibility = GONE
        // state-history-charts: the window ends now, or at the newest state when the server is ahead of the clock.
        val newest = max(
            history.timeline.maxOfOrNull { it.data.lastOrNull()?.lastChanged ?: 0.0 } ?: 0.0,
            history.line.maxOfOrNull { u -> u.data.maxOfOrNull { it.states.lastOrNull()?.lastChanged ?: 0.0 } ?: 0.0 } ?: 0.0,
        )
        val end = max(ctx.now(), ceil(newest).toLong())
        val start = (end - hours * HaGraph.HOUR_MS).toLong()

        val lineSpecs = history.line.map { u -> lineSpec(u, start, end) }
        // The charts share the widest y axis (paddingYAxis).
        val padding = max(
            lineSpecs.maxOfOrNull { HaLineChart.yAxis(it.first).third } ?: 0f,
            if (history.timeline.isNotEmpty()) HaTimelineChart.labelWidth(history.timeline, names, showNames) else 0f,
        )
        var index = 0
        if (history.timeline.isNotEmpty()) {
            val chart = ChartView(ctx.context, ctx.dp).apply { heightCss = HaTimelineChart.height(history.timeline.size) }
            val rows = history.timeline
            chart.setScene { w -> HaTimelineChart.scene(rows, names, start, end, padding, w, ctx, showNames) }
            charts.addView(entry(chart, timeline = true, first = index++ == 0))
        }
        for ((spec, legend) in lineSpecs) {
            val withPad = LineChartSpec(spec.lines, spec.xMin, spec.xMax, spec.unit, spec.heightCss, false, config.raw.optDouble("min_y_axis").takeIf { !it.isNaN() }, config.raw.optDouble("max_y_axis").takeIf { !it.isNaN() }, padding,
                fitYData = config.raw.optBoolean("fit_y_data", false))
            val box = LinearLayout(ctx.context).apply { orientation = LinearLayout.VERTICAL }
            val chart = LineChartHost(ctx, withPad)
            box.addView(chart, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            if (showNames) {
                box.addView(ChartLegendView(ctx).apply {
                    setItems(legend)
                    onLabel = { id -> ctx.openMoreInfo(id) }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            charts.addView(entry(box, timeline = false, first = index++ == 0))
        }
    }

    /** `.entry-container`: a line chart 8 px down, a first timeline 16 px down; later ones under a 2 px divider. */
    private fun entry(child: View, timeline: Boolean, first: Boolean): View {
        val box = LinearLayout(ctx.context).apply { orientation = LinearLayout.VERTICAL }
        if (!first) {
            box.addView(View(ctx.context).apply { setBackgroundColor(ctx.theme.dividerColor) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(2f * ctx.dp)))
        }
        if (!timeline) box.setPadding(0, if (first) Math.round(8f * ctx.dp) else 0, 0, 0)
        box.addView(child, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            if (!first && !timeline) topMargin = Math.round(8f * ctx.dp)
        })
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = Math.round((if (first) (if (timeline) 16f else 0f) else if (timeline) 32f else 16f) * ctx.dp)
        box.layoutParams = lp
        return box
    }

    /** `generateStateHistoryChartLineData` for plain numeric entities: step lines with gaps, held to the end. */
    private fun lineSpec(u: LineUnit, start: Long, end: Long): Pair<LineChartSpec, List<LegendItem>> {
        var colorIndex = 0
        val lines = ArrayList<ChartLine>()
        val legend = ArrayList<LegendItem>()
        val endD = end.toDouble()
        for (e in u.data) {
            val color = colors[e.entityId] ?: HaGraphColors.byIndex(ctx.theme, colorIndex++)
            val xs = ArrayList<Double>()
            val ys = ArrayList<Double>()
            var prev: Double? = null
            var hasPrev = false
            fun push(t: Double, v: Double?) {
                if (t > endD) return
                if (v == null && hasPrev && prev != null) { xs += t; ys += prev!! }
                xs += t; ys += v ?: Double.NaN
                prev = v
                hasPrev = true
            }
            var lastValue: Double? = null
            var lastDate = 0.0
            var lastNull: Double? = null
            fun process(st: LineState) {
                val v = HaHistory.parseFloatOrNull(st.state)
                val date = st.lastChanged
                val ln = lastNull
                if (v != null && ln != null) {
                    val lv = lastValue ?: v
                    val tmp = (v - lv) * ((ln - lastDate) / (date - lastDate)) + lv
                    push(ln, tmp)
                    push(ln + 1, null)
                    push(date, v)
                    lastDate = date; lastValue = v; lastNull = null
                } else if (v != null) {
                    push(date, v)
                    lastDate = date; lastValue = v
                } else if (lastNull == null && lastValue != null) {
                    lastNull = date
                }
            }
            val stop = e.states.firstOrNull()?.lastChanged ?: 0.0
            for (st in e.statistics.orEmpty()) {
                if (stop != 0.0 && st.lastChanged >= stop) break
                process(st)
            }
            e.states.forEach(::process)
            lastNull?.let { push(it, null) }
            if (hasPrev) push(endD, prev)
            val state = ctx.store[e.entityId]
            if (e.entityId.startsWith("sensor.")) {
                // The current state at now, for a chart up to now.
                state?.state?.let(HaHistory::parseFloatOrNull)?.let { xs += max(ctx.now().toDouble(), endD); ys += it }
            }
            // ha-chart-base's minmax down-sampling: one frame per device pixel of the chart's width.
            val sampled = HaGraph.downSample(xs.indices.map { doubleArrayOf(xs[it], ys[it]) }, chartWidthCss() * ctx.dp, start.toDouble(), endD, false)
            lines += ChartLine(sampled.map { it[0] }.toDoubleArray(), sampled.map { it[1] }.toDoubleArray(), color, 1.5f, step = true)
            legend += LegendItem(e.entityId, names[e.entityId] ?: e.name, color, state?.formattedState())
        }
        val chartHeight = defaultChartHeight()
        // The same bounds as the chart drawn, so the shared y-axis width is measured from its labels.
        return LineChartSpec(lines, start, end, u.unit.takeIf { it.isNotBlank() }, chartHeight, false,
            config.raw.optDouble("min_y_axis").takeIf { !it.isNaN() }, config.raw.optDouble("max_y_axis").takeIf { !it.isNaN() },
            fitYData = config.raw.optBoolean("fit_y_data", false)) to legend
    }

    /** The charts' width (CSS px): the card's less its border and the content's 16 px sides. */
    private fun chartWidthCss(): Double = (width.takeIf { it > 0 }?.let { it / ctx.dp - 2.0 - 32.0 }) ?: 466.0

    /** `_getDefaultHeight`: half the chart's width, at least 200, at most `--chart-max-height` (350). */
    private fun defaultChartHeight(): Float = min(max(chartWidthCss().toFloat() / 2f, 200f), 350f)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw && stateHistory != null) post { render() }
    }
}

/** A line chart whose height comes from its spec. */
private class LineChartHost(ctx: VitroCardContext, spec: LineChartSpec) : LinearLayout(ctx.context) {
    init {
        val chart = ChartView(ctx.context, ctx.dp).apply { heightCss = spec.heightCss }
        chart.setScene { w -> HaLineChart.scene(spec, w, ctx) }
        addView(chart, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
}

// ---------------------------------------------------------------------------------------------
// statistics-graph
// ---------------------------------------------------------------------------------------------

/**
 * Native HA `statistics-graph` card (`hui-statistics-graph-card` and `statistics-chart`): the statistics' metadata,
 * then `recorder/statistics_during_period` over `days_to_show` (30) days by `period` (hour), drawn as HA draws a
 * line chart of them: per statistic its mean line, and its min and max as a band (both lines at half opacity, the
 * area between at a quarter), smoothed at 0.4, with the y axis' line and the legend under it.
 */
class StatisticsGraphCardView(ctx: VitroCardContext, private val config: CardConfig) : CardSurface(ctx) {
    private val ids: List<String> = statisticIds(config)
    private val days: Double = config.raw.optDouble("days_to_show", 30.0).takeIf { !it.isNaN() && it != 0.0 } ?: 30.0
    /** The recorder period: `auto` (without an energy date selection) and none are HA's default, `hour`. */
    private val period: String = config.raw.optString("period", "hour").takeIf { it.isNotEmpty() && it != "auto" } ?: "hour"
    private val statTypes: List<String> = readStatTypes(config.raw.opt("stat_types"))
    private val chart = ChartView(ctx.context, ctx.dp)
    private val legend = ChartLegendView(ctx)
    private val info = cssText(ctx, 14f, ctx.theme.secondaryTextColor)
    private val hideLegend = config.raw.optBoolean("hide_legend", false)
    private var generation = 0
    private val refresh = Runnable { fetch() }

    init {
        val col = LinearLayout(ctx.context).apply { orientation = LinearLayout.VERTICAL }
        config.title?.let {
            col.addView(cardHeader(ctx, it) {
                ctx.navigate(historyPath(ids, (ctx.now() - (24 * days + 1) * HaGraph.HOUR_MS).toLong()))
            })
        }
        val content = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.px(16f), if (config.title != null) 0 else ctx.px(16f), ctx.px(16f), ctx.px(16f))
        }
        chart.heightCss = 233f
        // statistics-chart's `.info`: centered on a 60 px line.
        info.gravity = Gravity.CENTER
        info.visibility = GONE
        content.addView(info, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(60f * ctx.dp)))
        content.addView(chart, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(legend, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        legend.onLabel = { id -> ctx.openMoreInfo(id.substringBeforeLast('-').takeIf { ctx.store[it] != null } ?: id) }
        col.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(col, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** Whether the card showed the last time its visibility was looked at: only a hidden → shown change refetches. */
    private var statsShown = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        statsShown = isShown
        if (isShown) fetch()
    }

    override fun onDetachedFromWindow() {
        ctx.main.removeCallbacks(refresh)
        generation++
        super.onDetachedFromWindow()
    }

    /**
     * A cached view another one hides stays attached: it stops polling Home Assistant (an answer still on its way is
     * dropped), and asks once again when it shows. Attaching already fetched, so it doesn't ask twice.
     */
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (!isAttachedToWindow) return
        val shown = isShown
        if (shown == statsShown) return
        statsShown = shown
        if (shown) {
            fetch()
        } else {
            ctx.main.removeCallbacks(refresh)
            generation++
        }
    }

    private fun fetch() {
        ctx.main.removeCallbacks(refresh)
        if (ids.isEmpty()) return
        val gen = ++generation
        ctx.callWs(JSONObject().put("type", "recorder/get_statistics_metadata").put("statistic_ids", JSONArray(ids))) { _, meta, _ ->
            ctx.main.post {
                if (gen != generation) return@post
                val metadata = (meta as? JSONArray)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.associateBy { it.optString("statistic_id") } }.orEmpty()
                fetchStatistics(gen, metadata)
            }
        }
    }

    private fun fetchStatistics(gen: Int, metadata: Map<String, JSONObject>) {
        val now = ctx.now()
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        cal.add(Calendar.HOUR_OF_DAY, -(24 * days + 1).toInt())
        // The card's `unit` picks the statistic whose display unit it is; else the first statistic's unit class.
        fun classOf(m: JSONObject?) = m?.optString("unit_class")?.takeIf { it.isNotEmpty() && it != "null" }
        val cfgUnit = config.raw.optString("unit").takeIf { it.isNotEmpty() }
        val matching = cfgUnit?.let { u -> metadata.entries.firstOrNull { (id, m) -> displayUnit(id, m) == u } }
        val unitClass: String?
        val unit: String?
        if (matching != null && classOf(matching.value) != null) {
            unitClass = classOf(matching.value)
            unit = cfgUnit
        } else {
            val first = metadata[ids[0]]
            unitClass = classOf(first)
            unit = if (unitClass != null) displayUnit(ids[0], first) else null
        }
        val msg = JSONObject()
            .put("type", "recorder/statistics_during_period")
            .put("start_time", HaGraph.isoString(cal.timeInMillis))
            .put("statistic_ids", JSONArray(ids))
            .put("period", period)
        if (unitClass != null) msg.put("units", JSONObject().put(unitClass, unit ?: JSONObject.NULL))
        msg.put("types", JSONArray(statTypes))
        ctx.callWs(msg) { ok, result, _ ->
            ctx.main.post {
                if (gen != generation) return@post
                if (ok && result is JSONObject) render(result, metadata, unit)
                if (isAttachedToWindow && isShown) ctx.main.postDelayed(refresh, if (period == "5minute") 5 * 60_000L else 60 * 60_000L)
            }
        }
    }

    /** `getDisplayUnit`: the entity's unit, else the statistic's display unit. */
    private fun displayUnit(id: String, meta: JSONObject?): String? =
        ctx.store[id]?.unit ?: meta?.optString("display_unit_of_measurement")?.takeIf { it.isNotEmpty() && it != "null" }
            ?: meta?.optString("statistics_unit_of_measurement")?.takeIf { it.isNotEmpty() && it != "null" }

    private fun render(result: JSONObject, metadata: Map<String, JSONObject>, cardUnit: String?) {
        val lines = ArrayList<ChartLine>()
        val legendItems = ArrayList<LegendItem>()
        var colorIndex = 0
        var minX = Long.MAX_VALUE
        var maxX = Long.MIN_VALUE
        val unit = cardUnit ?: ids.mapNotNull { displayUnit(it, metadata[it]) }.distinct().singleOrNull()
        // The chart's end (statistics-chart-data): the latest bucket start of any statistic, never after now.
        val limit = min(ctx.now().toDouble(), ids.mapNotNull { id ->
            result.optJSONArray(id)?.takeIf { it.length() > 0 }?.let { a -> rowTime(a.optJSONObject(a.length() - 1)?.opt("start")) }
        }.maxOrNull() ?: ctx.now().toDouble())
        val statLegend = ArrayList<LegendItem>()
        for (id in ids) {
            val rows = result.optJSONArray(id) ?: continue
            if (rows.length() == 0) continue
            val stats = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
            val color = HaGraphColors.byIndex(ctx.theme, colorIndex++)
            val name = config.entities().firstOrNull { it.entity == id }?.name ?: ctx.store[id]?.displayName ?: id
            fun has(type: String) = stats.any { it.has(type) && !it.isNull(type) }
            val hasMean = "mean" in statTypes && has("mean")
            val hasMax = "max" in statTypes && has("max")
            val hasMin = "min" in statTypes && has("min")
            val drawBands = listOf(hasMean, hasMax, hasMin).count { it } > 1
            val bandTop = if (hasMax) "max" else "mean"
            val bandBottom = if (hasMin) "min" else "mean"
            val sorted = if (drawBands) sortBandTypes(statTypes) else statTypes
            val types = sorted.filter { has(it) }
            // Points per type: each stat at its start, gaps where a stat doesn't start where the last one ended,
            // then the last one's end.
            val xs = types.associateWith { ArrayList<Double>() }
            val ys = types.associateWith { ArrayList<Double>() }
            var prevStart: Double? = null
            var prevEnd: Double? = null
            var prevVals: Map<String, Double>? = null
            // A cumulative `sum` starts at zero in the window (its first value is the baseline).
            var firstSum: Double? = null
            for (st in stats) {
                val start = rowTime(st.opt("start")) ?: continue
                if (prevStart == start) continue
                val rowEnd = rowTime(st.opt("end")) ?: continue
                prevStart = start
                // pushData's limit: a bucket ends at its end or at the chart's end, whichever comes first.
                if (start > limit) continue
                val end = min(rowEnd, limit)
                val vals = types.associateWith { t ->
                    val v = if (st.has(t) && !st.isNull(t)) st.optDouble(t) else Double.NaN
                    if (t != "sum") v
                    else if (firstSum == null) { firstSum = v.takeIf { !it.isNaN() }; 0.0 }
                    else (if (v.isNaN()) 0.0 else v) - firstSum!!
                }
                if (prevEnd != null && prevVals != null && prevEnd != start) {
                    for (t in types) { xs.getValue(t) += prevEnd; ys.getValue(t) += prevVals.getValue(t); xs.getValue(t) += prevEnd; ys.getValue(t) += Double.NaN }
                }
                for (t in types) { xs.getValue(t) += start; ys.getValue(t) += vals.getValue(t) }
                prevEnd = end
                prevVals = vals
            }
            if (prevEnd != null && prevVals != null) for (t in types) { xs.getValue(t) += prevEnd; ys.getValue(t) += prevVals.getValue(t) }
            // The current state at now when the statistics reach within 10 minutes of it.
            val now = ctx.now().toDouble()
            val current = ctx.store[id]?.state?.toDoubleOrNull()?.takeIf { it.isFinite() }
            // Only in the chart's unit (or when either is unknown): a live value in another unit isn't converted.
            val statUnit = displayUnit(id, metadata[id])
            val sameUnit = unit == null || statUnit == null || unit == statUnit
            if (now - limit <= 600_000 && current != null && sameUnit) {
                for (t in types) if (t != "sum" && t != "change") { xs.getValue(t) += now; ys.getValue(t) += current }
            }
            val built = HashMap<String, ChartLine>()
            for (t in types) {
                val band = drawBands && (t == bandTop || t == bandBottom)
                val dim = (band && hasMin && hasMax && hasMean)
                // A hidden legend hides the band's edges too (`color + "00"`); shown, they're at half opacity.
                val lineColor = if (dim) CardSurface.withAlpha(color, if (hideLegend) 0f else 0x7F / 255f) else color
                val line = ChartLine(xs.getValue(t).toDoubleArray(), ys.getValue(t).toDoubleArray(), lineColor, 1.5f, smooth = 0.4,
                    bandBottom = if (band && t == bandTop && bandTop != bandBottom) built[bandBottom] else null,
                    areaColor = CardSurface.withAlpha(color, 0x3F / 255f))
                built[t] = line
                lines += line
                legendItems += LegendItem("$id-$t", "$name (${t})", lineColor, null)
                // With several statistics, one entry each: its mean, else its first series.
                if (if (hasMean) t == "mean" else statLegend.none { it.id == id }) statLegend += LegendItem(id, name, lineColor, null)
                line.xs.forEach { x -> if (!x.isNaN()) { minX = min(minX, x.toLong()); maxX = max(maxX, x.toLong()) } }
            }
        }
        if (lines.isEmpty()) {
            // A successful answer with nothing in the window: HA's "No statistics found." in place of the chart.
            legend.setItems(emptyList())
            chart.setScene { ChartScene() }
            chart.visibility = GONE
            info.text = "No statistics found."
            info.visibility = VISIBLE
            return
        }
        chart.visibility = VISIBLE
        info.visibility = GONE
        // One statistic: the base chart's legend lists every series; several: one entry per statistic.
        legend.setItems(if (hideLegend) emptyList() else if (ids.size > 1) statLegend else legendItems)
        // ha-chart-base: differenceInMinutes(axis.max || new Date(), axis.min); the statistics chart sets no max.
        val minutes = floor((ctx.now() - minX) / 60000.0)
        val dayDiff = minutes / 60 / 24
        val minInterval = if (dayDiff >= 89) 28 * ECharts.DAY else if (dayDiff > 2) ECharts.DAY else null
        val spec = LineChartSpec(lines, minX, maxX, unit, 233f, true,
            config.raw.optDouble("min_y_axis").takeIf { !it.isNaN() }, config.raw.optDouble("max_y_axis").takeIf { !it.isNaN() },
            minInterval = minInterval, labelMinutes = minutes, fitYData = config.raw.optBoolean("fit_y_data", false))
        chart.setScene { w -> HaLineChart.scene(spec, w, ctx) }
        chart.requestLayout()
    }

    companion object {
        private val VALID_TYPES = setOf("mean", "min", "max", "sum", "state", "change")

        fun statisticIds(config: CardConfig): List<String> {
            val fromList = config.entities().filter { it.isEntity }.map { it.entity }
            return (fromList.ifEmpty { listOfNotNull(config.entity) }).distinct()
        }

        /** `stat_types`: a string or a list; HA's default asks for all six. */
        fun readStatTypes(raw: Any?): List<String> {
            if (raw is String) return listOf(raw).filter { it in VALID_TYPES }.ifEmpty { DEFAULT_TYPES }
            val arr = raw as? JSONArray ?: return DEFAULT_TYPES
            return (0 until arr.length()).mapNotNull { arr.optString(it, "").takeIf { t -> t in VALID_TYPES } }.ifEmpty { DEFAULT_TYPES }
        }

        private val DEFAULT_TYPES = listOf("change", "state", "sum", "min", "max", "mean")

        /** A recorder row's time in ms: epoch ms, or an ISO string from an older core; null when it's neither. */
        fun rowTime(v: Any?): Double? = when (v) {
            is Number -> v.toDouble().takeIf { it.isFinite() }
            is String -> dev.glassworks.engine.vitro.data.EntityStore.parseTimestamp(v).takeIf { it > 0 }?.times(1000.0)
            else -> null
        }

        /** An ISO time without milliseconds, as the logbook and calendar cards send it. */
        fun isoUtc(date: java.util.Date): String = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.ROOT)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(date)

        /** The band's order (`statistics-chart-data.ts`): min first, max last, the rest as they were. */
        fun sortBandTypes(types: List<String>): List<String> =
            types.filter { it == "min" } + types.filter { it != "min" && it != "max" } + types.filter { it == "max" }
    }
}
