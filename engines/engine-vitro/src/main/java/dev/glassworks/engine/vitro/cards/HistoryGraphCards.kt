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
import dev.glassworks.engine.vitro.data.HaText
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.HaDisplay
import dev.glassworks.engine.vitro.model.ActionConfig
import dev.glassworks.engine.vitro.model.CardConfig
import dev.glassworks.engine.vitro.theme.ResolvedTheme
import dev.glassworks.engine.vitro.theme.ThemeResolver
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

/**
 * A state of a line chart (`LineChartState`): its value text, when it took it (ms), and for climate, humidifier and
 * water-heater entities the attributes their charts draw (`LINE_ATTRIBUTES_TO_KEEP`); null for every other entity.
 */
class LineState(val state: String, val lastChanged: Double, val attributes: JSONObject? = null)

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
            // limitedHistoryFromStateObj: the current state with its attributes, which a climate's chart draws.
            else store(id)?.let { st -> local[id] = listOf(HistoryState(st.state, st.lastUpdated, attributesJson(st.attributes))) }
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
                "zone" -> HaText.ui("ui.dialogs.more_info_control.zone.graph_unit")
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

    /** The attributes a climate, humidifier or water-heater chart draws (`LINE_ATTRIBUTES_TO_KEEP`). */
    val LINE_ATTRIBUTES_TO_KEEP = listOf(
        "temperature", "current_temperature", "target_temp_low", "target_temp_high", "hvac_action", "humidity", "mode",
        "action", "current_humidity",
    )

    /**
     * `processLineChartEntities`: a state is dropped only when it repeats the two before it, attributes included
     * (`equalState`), so a climate whose mode stays the same keeps every recorded temperature.
     */
    /** An entity's attributes as the JSON a history state carries (a null stays a JSON null). */
    private fun attributesJson(attrs: Map<String, Any?>): JSONObject {
        val o = JSONObject()
        for ((k, v) in attrs) o.put(k, if (v == null) JSONObject.NULL else JSONObject.wrap(v) ?: JSONObject.NULL)
        return o
    }

    private fun lineEntity(id: String, states: List<HistoryState>, current: EntityState?): LineEntity {
        val useLu = id.substringBefore('.') in USE_LAST_UPDATED
        val out = ArrayList<LineState>(states.size)
        for (s in states) {
            val p = if (useLu) {
                val kept = JSONObject()
                s.a?.let { a -> for (k in LINE_ATTRIBUTES_TO_KEEP) if (a.has(k)) kept.put(k, a.opt(k)) }
                LineState(s.s, s.lu * 1000, kept)
            } else {
                LineState(s.s, (s.lc.takeIf { !it.isNaN() && it != 0.0 } ?: s.lu) * 1000)
            }
            val n = out.size
            if (n > 1 && equalState(p, out[n - 1]) && equalState(p, out[n - 2])) continue
            out += p
        }
        return LineEntity(id, chartName(id, current), out)
    }

    /** `equalState`: the same state and, when both have them, the same kept attributes. */
    fun equalState(a: LineState, b: LineState): Boolean {
        if (a.state != b.state) return false
        val aa = a.attributes ?: return true
        val ba = b.attributes ?: return true
        return LINE_ATTRIBUTES_TO_KEEP.all { jsStrictEquals(aa.opt(it), ba.opt(it)) }
    }

    /** JavaScript's `===` on JSON values: a missing value (null) equals only another missing one, numbers by value. */
    fun jsStrictEquals(x: Any?, y: Any?): Boolean {
        if (x == null || y == null) return x === y
        if (x === JSONObject.NULL || y === JSONObject.NULL) return x === y
        if (x is Number && y is Number) return x.toDouble() == y.toDouble()
        if (x is JSONObject || x is JSONArray || y is JSONObject || y is JSONArray) return x === y
        return x == y
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

/** One series of a history line chart: its points (ms, value; NaN for `null`), and whether it's a fill. */
class HistorySeries(val id: String, val entityId: String, val name: String, val color: Int, val fill: Boolean) {
    val xs = ArrayList<Double>()
    val ys = ArrayList<Double>()

    fun add(x: Double, y: Double) { xs += x; ys += y }
}

/**
 * `generateStateHistoryChartLineData` (`state-history-chart-line-data.ts`): one entity's series. A climate or water
 * heater draws its current temperature, a fill under it while it heats, cools, dries or runs its fan, and its target
 * temperature (or the high and low ends of a target range); a humidifier its target and current humidity and a fill
 * while it humidifies, dries or is on; anything else its numeric state, with gaps for the states that aren't numbers.
 */
object HaHistoryLines {
    /** `CLIMATE_MODE_CONFIGS`: the HVAC mode, its action, and the theme variable of its fill, with HA's default. */
    private class ModeConfig(val mode: String, val action: String, val cssVar: String, val fallback: Int, val showName: String, val plainName: String)

    private val CLIMATE_MODES = listOf(
        ModeConfig("heat", "heating", "state-climate-heat-color", 0xFFFF6F22.toInt(), "heating", "Heating"),
        ModeConfig("cool", "cooling", "state-climate-cool-color", 0xFF2196F3.toInt(), "cooling", "Cooling"),
        ModeConfig("dry", "drying", "state-climate-dry-color", 0xFFFF9800.toInt(), "drying", "Drying"),
        ModeConfig("fan_only", "fan", "state-climate-fan_only-color", 0xFF00BCD4.toInt(), "fan only", "Fan"),
    )

    /** `CLIMATE_HVAC_ACTION_TO_MODE`. */
    private val HVAC_ACTION_TO_MODE = mapOf(
        "cooling" to "cool", "defrosting" to "heat", "drying" to "dry", "fan" to "fan_only", "heating" to "heat",
        "idle" to "off", "off" to "off", "preheating" to "heat",
    )

    /** The series' ids that show a temperature attribute in the legend (`state-history-chart-line`), by suffix. */
    private val LEGEND_ATTRIBUTE = listOf(
        "-current_temperature" to "current_temperature",
        "-target_temperature_mode_low" to "target_temp_low",
        "-target_temperature_mode" to "target_temp_high",
        "-target_temperature" to "temperature",
    )

    /** JavaScript's truthiness of a JSON value: missing, null, false, 0, NaN and "" are false. */
    private fun truthy(v: Any?): Boolean = when (v) {
        null, JSONObject.NULL -> false
        is Boolean -> v
        is Number -> v.toDouble().let { it != 0.0 && !it.isNaN() }
        is String -> v.isNotEmpty()
        else -> true
    }

    /** `safeParseFloat`: `parseFloat` of a JSON value, null unless it's a finite number. */
    fun safeParseFloat(v: Any?): Double? = when (v) {
        null, JSONObject.NULL, is Boolean -> null
        is Number -> v.toDouble().takeIf { it.isFinite() }
        else -> HaHistory.parseFloatOrNull(v.toString())
    }

    private fun color(theme: ResolvedTheme, cssVar: String, fallback: Int): Int =
        ThemeResolver.parseColor(theme.vars[cssVar]) ?: fallback

    /**
     * The series of [entity] up to [endMs]; [colorIndex] is the chart's next graph color, shared by its entities.
     * [current] is the entity's state now ([nowMs]), which a sensor's line ends on.
     */
    fun series(
        entity: LineEntity, endMs: Double, nowMs: Double, name: String, color: Int?, showNames: Boolean,
        theme: ResolvedTheme, colorIndex: IntArray, current: EntityState?,
    ): List<HistorySeries> {
        val domain = entity.entityId.substringBefore('.')
        val data = ArrayList<HistorySeries>()
        var prevValues: DoubleArray? = null
        fun pushData(t: Double, values: DoubleArray?) {
            if (values == null || t > endMs) return
            val prev = prevValues
            for (i in data.indices) {
                // A null after a value ends that value's line here, so the gap starts where the value stopped.
                if (values[i].isNaN() && prev != null && !prev[i].isNaN()) data[i].add(t, prev[i])
                data[i].add(t, values[i])
            }
            prevValues = values
        }
        fun addDataSet(id: String, label: String, clr: Int? = null, fill: Boolean = false) {
            val c = clr ?: HaGraphColors.byIndex(theme, colorIndex[0]++)
            data += HistorySeries(id, entity.entityId, label, c, fill)
        }
        val id = entity.entityId
        val states = entity.states
        if (domain == "climate" || domain == "water_heater") {
            val hasHvacAction = states.any { truthy(it.attributes?.opt("hvac_action")) }
            fun isActive(m: ModeConfig, st: LineState): Boolean =
                if (domain == "climate" && hasHvacAction) HVAC_ACTION_TO_MODE[st.attributes?.opt("hvac_action") as? String] == m.mode
                else st.state == m.mode
            val active = CLIMATE_MODES.filter { m -> states.any { isActive(m, it) } }
            val hasTargetRange = states.any { st ->
                val a = st.attributes
                a != null && !HaHistory.jsStrictEquals(a.opt("target_temp_high"), a.opt("target_temp_low"))
            }
            addDataSet("$id-current_temperature", if (showNames) "$name current temperature" else "Current temperature")
            for (m in active) {
                addDataSet("$id-${m.action}", if (showNames) "$name ${m.showName}" else m.plainName, color(theme, m.cssVar, m.fallback), fill = true)
            }
            if (hasTargetRange) {
                addDataSet("$id-target_temperature_mode", if (showNames) "$name target temperature high" else "Upper target temperature")
                addDataSet("$id-target_temperature_mode_low", if (showNames) "$name target temperature low" else "Lower target temperature")
            } else {
                addDataSet("$id-target_temperature", if (showNames) "$name target temperature" else "Target temperature")
            }
            for (st in states) {
                val a = st.attributes ?: continue
                val curTemp = safeParseFloat(a.opt("current_temperature")) ?: Double.NaN
                val values = DoubleArray(data.size)
                var i = 0
                values[i++] = curTemp
                for (m in active) values[i++] = if (isActive(m, st)) curTemp else Double.NaN
                if (hasTargetRange) {
                    values[i++] = safeParseFloat(a.opt("target_temp_high")) ?: Double.NaN
                    values[i] = safeParseFloat(a.opt("target_temp_low")) ?: Double.NaN
                } else {
                    values[i] = safeParseFloat(a.opt("temperature")) ?: Double.NaN
                }
                pushData(st.lastChanged, values)
            }
        } else if (domain == "humidifier") {
            val hasAction = states.any { truthy(it.attributes?.opt("action")) }
            val hasCurrent = states.any { truthy(it.attributes?.opt("current_humidity")) }
            val hasHumidifying = hasAction && states.any { it.attributes?.opt("action") == "humidifying" }
            val hasDrying = hasAction && states.any { it.attributes?.opt("action") == "drying" }
            val onColor = color(theme, "state-humidifier-on-color", 0xFF2196F3.toInt())
            addDataSet("$id-target_humidity", if (showNames) "$name target humidity" else "Target humidity")
            if (hasCurrent) addDataSet("$id-current_humidity", if (showNames) "$name current humidity" else "Current humidity")
            when {
                hasHumidifying -> addDataSet("$id-humidifying", if (showNames) "$name humidifying" else "Humidifying", onColor, fill = true)
                hasDrying -> addDataSet("$id-drying", if (showNames) "$name drying" else "Drying", onColor, fill = true)
                else -> addDataSet("$id-on", if (showNames) "$name on" else "On", null, fill = true)
            }
            for (st in states) {
                val a = st.attributes ?: continue
                val target = safeParseFloat(a.opt("humidity")) ?: Double.NaN
                // Without a current humidity, the fill goes up to the target.
                val cur = if (hasCurrent) safeParseFloat(a.opt("current_humidity")) ?: Double.NaN else target
                val values = ArrayList<Double>(3)
                values += target
                if (hasCurrent) values += cur
                values += when {
                    hasHumidifying -> if (a.opt("action") == "humidifying") cur else Double.NaN
                    hasDrying -> if (a.opt("action") == "drying") cur else Double.NaN
                    else -> if (st.state == "on") cur else Double.NaN
                }
                pushData(st.lastChanged, values.toDoubleArray())
            }
        } else {
            addDataSet(id, name, color)
            var lastValue: Double? = null
            var lastDate = 0.0
            var lastNull: Double? = null
            fun pushValue(t: Double, v: Double) {
                if (t > endMs) return
                data[0].add(t, v)
                prevValues = doubleArrayOf(v)
            }
            fun process(st: LineState) {
                val v = HaHistory.parseFloatOrNull(st.state)
                val date = st.lastChanged
                val ln = lastNull
                if (v != null && ln != null) {
                    val lv = lastValue ?: v
                    val tmp = (v - lv) * ((ln - lastDate) / (date - lastDate)) + lv
                    pushData(ln, doubleArrayOf(tmp))
                    pushData(ln + 1, doubleArrayOf(Double.NaN))
                    pushData(date, doubleArrayOf(v))
                    lastDate = date; lastValue = v; lastNull = null
                } else if (v != null) {
                    pushValue(date, v)
                    lastDate = date; lastValue = v
                } else if (lastNull == null && lastValue != null) {
                    lastNull = date
                }
            }
            val stop = states.firstOrNull()?.lastChanged ?: 0.0
            for (st in entity.statistics.orEmpty()) {
                if (stop != 0.0 && st.lastChanged >= stop) break
                process(st)
            }
            states.forEach(::process)
            lastNull?.let { pushData(it, doubleArrayOf(Double.NaN)) }
        }
        // The last values held to the end of the window.
        pushData(endMs, prevValues)
        // A sensor's line ends on its current state when the chart reaches now (a second's leeway).
        if (domain == "sensor" && nowMs - endMs <= 1000 && data.size == 1) {
            current?.state?.let(HaHistory::parseFloatOrNull)?.let { data[0].add(max(nowMs, endMs), it) }
        }
        return data
    }

    /**
     * The legend's value for a series (`state-history-chart-line`): a climate's temperature series show the attribute
     * they draw (`computeAttributeValueDisplay`, "Unknown" when it's missing), every other one the entity's state.
     */
    fun legendValue(seriesId: String, current: EntityState?, temperatureUnit: String?): String? {
        current ?: return null
        val attr = LEGEND_ATTRIBUTE.firstOrNull { seriesId.endsWith(it.first) }?.second ?: return current.formattedState()
        return when (val v = current.attributes[attr]) {
            null, JSONObject.NULL -> HaText.ui("state.default.unknown")
            is Number -> {
                val number = HaChartFormat.number(v.toDouble(), 0, 2)
                val unit = temperatureUnit?.takeIf { it.isNotEmpty() }
                if (unit == null) number else number + (if (unit == "°") "" else "\u00A0") + unit
            }
            else -> v.toString()
        }
    }
}

/** The history panel's address for [entityIds] from [startMs], as the cards' header links build it (`createSearchParam`). */
internal fun historyPath(entityIds: List<String>, startMs: Long): String {
    fun enc(v: String) = java.net.URLEncoder.encode(v, "UTF-8")
    return "/history?entity_id=" + enc(entityIds.joinToString(",")) + "&back=1&start_date=" + enc(HaGraph.isoString(startMs))
}

// ---------------------------------------------------------------------------------------------
// Shared views: the card header and the chart legend
// ---------------------------------------------------------------------------------------------

internal fun cssText(ctx: VitroCardContext, sizeCss: Float, color: Int, weight: Int = Inter.REGULAR) = TextView(ctx.context).apply {
    setTextSize(TypedValue.COMPLEX_UNIT_DIP, sizeCss * ctx.theme.fontScale)
    setTextColor(color)
    typeface = VitroFonts.typeface(ctx.context, weight, sizeCss)
    includeFontPadding = false
}

/** Makes [tv]'s text (set before) one CSS line box [lineHeight] times its font size, its leading split as Blink does. */
internal fun lineBox(ctx: VitroCardContext, tv: TextView, sizeCss: Float, lineHeight: Float) {
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
        contentDescription = HaText.ui("ui.dialogs.more_info_control.history")
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
 * (18 px), the name (12 px) and its value in the secondary color; 12 px above it. On a touch screen
 * ([VitroCardContext.touch], HA's `@media (pointer: coarse)`) the entries are 40 px high with no row gap and their
 * toggle gets 11 px before it, so a finger can hit it; the chip keeps its 24 px. Collapsed, it shows only the
 * entries that fit in two rows and a "More (N)" chip after them (`LegendFitController`); the chip expands it to every
 * entry and a "Less" chip. [expanded] starts from the card's `expand_legend`. Expanding or collapsing only lays the
 * legend out again: the chart keeps its paths, unless a fixed slot gives it a new height.
 */
class ChartLegendView(private val ctx: VitroCardContext) : ViewGroup(ctx.context) {
    private val gap = 8f * ctx.dp
    private val touch = ctx.touch

    /** `row-gap`: none on a touch screen, else the 8 px gap. */
    private val rowGap = if (touch) 0f else gap
    var onLabel: ((String) -> Unit)? = null

    /** Whether every entry shows (`expandLegend`). */
    var expanded: Boolean = false
        set(v) { if (field != v) { field = v; updateChip(); requestLayout() } }

    /** Called when the chip expands or collapses the legend, for a card that keeps the state across redraws. */
    var onExpandedChange: ((Boolean) -> Unit)? = null

    private val entries = ArrayList<View>()

    /** The entries that fit in two rows with the chip after them, as the last measure found. */
    private var fit = 0

    private val chipLabel = cssText(ctx, CHIP_TEXT, ctx.theme.primaryTextColor, 500).apply { maxLines = 1 }
    private val chipIcon = MdiIconView(ctx.context)

    /**
     * The `legend-more` item: an `li` (24 px high, 2 px sides) holding HA's filled assist chip: 8 px, the label in
     * 12.25 px medium, 4 px, an 18 px chevron, 8 px, on the primary text color at 15 % (a pill).
     */
    private val chip: View = android.widget.FrameLayout(ctx.context).apply {
        setPadding(ctx.px(2f), 0, ctx.px(2f), 0)
        val pill = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Math.round(8f * ctx.dp), 0, Math.round(8f * ctx.dp), 0)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 12f * ctx.dp
                setColor(CardSurface.withAlpha(ctx.theme.primaryTextColor, 0.15f))
            }
            isClickable = true
            setOnClickListener {
                expanded = !expanded
                onExpandedChange?.invoke(expanded)
            }
        }
        pill.addView(chipLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        pill.addView(chipIcon, LinearLayout.LayoutParams(Math.round(18f * ctx.dp), Math.round(18f * ctx.dp)).apply { marginStart = Math.round(4f * ctx.dp) })
        addView(pill, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** The items drawn now ([setItems]). */
    private var items: List<LegendItem> = emptyList()

    /** Whether the legend draws [other] already: the same series, names, colors and values, in order. */
    fun shows(other: List<LegendItem>): Boolean = items.size == other.size && items.indices.all { i ->
        val a = items[i]
        val b = other[i]
        a.id == b.id && a.name == b.name && a.color == b.color && a.value == b.value
    }

    fun setItems(items: List<LegendItem>) {
        this.items = items
        removeAllViews()
        entries.clear()
        val multiple = items.size > 1
        for (it in items) {
            val li = LinearLayout(ctx.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                // Touch: `li { padding: 0 }` and the toggle's own 11 px before the icon.
                if (!touch) setPadding(ctx.px(2f), 0, ctx.px(2f), 0)
            }
            li.addView(MdiIconView(ctx.context).apply { setIcon("mdi:check-circle", it.color) },
                LinearLayout.LayoutParams(Math.round(18f * ctx.dp), Math.round(18f * ctx.dp)).apply {
                    if (touch) marginStart = Math.round(11f * ctx.dp)
                    marginEnd = Math.round(4f * ctx.dp)
                })
            val label = cssText(ctx, 12f, ctx.theme.primaryTextColor).apply {
                text = it.name
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                isClickable = true
                setOnClickListener { _ -> onLabel?.invoke(it.id) }
            }
            lineBox(ctx, label, 12f, 1.2f)
            val value = it.value?.takeIf { v -> v.isNotEmpty() }?.let { v -> cssText(ctx, 12f, ctx.theme.secondaryTextColor).apply { text = v; maxLines = 1 } }
            if (multiple) {
                // `li { max-width }` (300 px with a value, else 220) holds the whole entry: its 2 px sides, the 18 px
                // toggle and 4 px (on touch, no sides and an 11 + 18 + 4 px toggle), and the value
                // (`flex-shrink: 0`), so the name shrinks to what they leave.
                val valuePx = value?.let { v -> Math.round(4f * ctx.dp) + ceil(v.paint.measureText(v.text.toString()).toDouble()).toInt() } ?: 0
                val around = if (touch) 33f else 26f
                label.maxWidth = max(0, Math.round(((if (it.value != null) 300f else 220f) - around) * ctx.dp) - valuePx)
            }
            li.addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            if (value != null) {
                li.addView(value, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = Math.round(4f * ctx.dp) })
            }
            entries += li
            addView(li)
        }
        if (items.isNotEmpty()) addView(chip)
        fit = items.size
        updateChip()
        requestLayout()
    }

    /** The chip's label (`expand_legend` / `collapse_legend`) and chevron for the current state and [fit]. */
    private fun chipText(fitNow: Int): String = if (expanded) "Less" else "More (${entries.size - fitNow})"

    private fun updateChip() {
        val text = chipText(fit)
        if (chipLabel.text.toString() != text) chipLabel.text = text
        chipIcon.setIcon(if (expanded) "mdi:chevron-up" else "mdi:chevron-down", ctx.theme.primaryTextColor)
    }

    /**
     * The chip's width with [text] as its label: 2 + 8 + label + 4 + 18 + 8 + 2 px, each part rounded as the chip's
     * views round it (the label to its TextView's whole pixels), so the width the fit counts on is the width the
     * chip measures and a chip the fit puts in row two stays there.
     */
    internal fun chipWidth(text: String): Float {
        val label = ceil(chipLabel.paint.measureText(text).toDouble()).toInt() + chipLabel.paddingLeft + chipLabel.paddingRight
        return (2 * ctx.px(2f) + 2 * Math.round(8f * ctx.dp) + label + Math.round(4f * ctx.dp) + Math.round(18f * ctx.dp)).toFloat()
    }

    private val itemH get() = Math.round(24f * ctx.dp)

    /** An entry's height: 24 px, or 40 on a touch screen (`li:has(.legend-toggle)`). */
    private val entryH get() = Math.round((if (touch) 40f else 24f) * ctx.dp)
    private val topPad get() = Math.round(12f * ctx.dp)

    /** The views in the flow: collapsed, the entries that fit and the chip; expanded, all of them and the chip. */
    private fun shown(): List<View> {
        if (entries.isEmpty()) return emptyList()
        val visible = if (expanded) entries else entries.subList(0, fit)
        return if (fit < entries.size) visible + chip else visible
    }

    private fun rows(width: Int): List<List<View>> {
        val rows = ArrayList<MutableList<View>>()
        var used = 0f
        for (c in shown()) {
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
        val itemSpec = MeasureSpec.makeMeasureSpec(itemH, MeasureSpec.EXACTLY)
        val entrySpec = MeasureSpec.makeMeasureSpec(entryH, MeasureSpec.EXACTLY)
        for (e in entries) e.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.AT_MOST), entrySpec)
        if (entries.isNotEmpty()) {
            // LegendFitController: the chip's width depends on how many entries it hides, which depends on its width;
            // the frontend settles that over its resize observer, here in a few rounds.
            val widths = FloatArray(entries.size) { entries[it].measuredWidth.toFloat() }
            var f = entries.size
            repeat(4) {
                val next = fitInRows(widths, chipWidth(chipText(f)), w.toFloat(), gap, MAX_ROWS)
                if (next == f) return@repeat
                f = next
            }
            fit = f
            updateChip()
            chip.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.AT_MOST), itemSpec)
        }
        val rowList = rows(w)
        // Each row is as high as its tallest item (`align-items: center`): a row of only the chip is 24 px.
        val natural = if (rowList.isEmpty()) 0 else topPad + rowList.sumOf { r -> r.maxOf { it.measuredHeight } } + Math.round((rowList.size - 1) * rowGap)
        // A fixed slot caps the legend (`max-height: 60%`): the rows past it are cut off, as HA's scroll out of view.
        setMeasuredDimension(w, if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(heightMeasureSpec) else natural)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val shown = shown().toSet()
        // Entries past the fit stay children (they're measured for it), out of the flow and hidden, as HA's
        // `li.overflow` (`position: absolute; visibility: hidden`).
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c in shown) {
                if (c.visibility != VISIBLE) c.visibility = VISIBLE
            } else {
                if (c.visibility != INVISIBLE) c.visibility = INVISIBLE
                c.layout(0, 0, 0, 0)
            }
        }
        var y = topPad.toFloat()
        for (row in rows(r - l)) {
            val total = row.sumOf { it.measuredWidth } + gap * (row.size - 1)
            val rowH = row.maxOf { it.measuredHeight }
            var x = ((r - l) - total) / 2f
            for (c in row) {
                val top = Math.round(y + (rowH - c.measuredHeight) / 2f)
                c.layout(Math.round(x), top, Math.round(x) + c.measuredWidth, top + c.measuredHeight)
                x += c.measuredWidth + gap
            }
            y += rowH + rowGap
        }
    }

    companion object {
        /** `MAX_ROWS` of `legend-fit-controller.ts`. */
        const val MAX_ROWS = 2

        /** The chip label's size: HA's small button text. */
        const val CHIP_TEXT = 12.25f

        /**
         * `fitInRows`: how many items of [widths] fit in [maxRows] wrapping rows of [rowWidth], [gap] apart, leaving
         * room for the "more" item ([moreWidth]) after them when not all of them fit.
         */
        fun fitInRows(widths: FloatArray, moreWidth: Float, rowWidth: Float, gap: Float, maxRows: Int): Int {
            fun fits(end: Float, width: Float) = end == 0f || end + gap + width <= rowWidth
            var rows = 1
            var end = 0f
            var fit = 0
            for ((index, width) in widths.withIndex()) {
                if (rows < maxRows || fits(end, moreWidth)) fit = index
                if (fits(end, width)) {
                    end += (if (end == 0f) 0f else gap) + width
                } else {
                    rows++
                    end = width
                }
                if (rows > maxRows) return fit
            }
            return widths.size
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Chart scenes: the line chart, the timeline and the statistics chart
// ---------------------------------------------------------------------------------------------

/**
 * The boxes ECharts 6 lays a 12 px axis label out in (CSS px, read from its label rects in HA's render). Its outer
 * bounds (`outerBoundsMode: 'auto'`) keep every box inside the chart, moving the grid in when one would leave it.
 */
private object ChartText {
    /** A 12 px label's box height: value and time labels and the axis name. */
    const val LABEL_H = 12.252f
    /** The axis name's box. */
    const val NAME_H = LABEL_H
    /** A time label's box below the axis. */
    const val X_LABEL_H = LABEL_H
    /** A label's box is its text plus this much on the left and the right. */
    const val LABEL_PAD_X = 3f
    /**
     * The axis name moves up off the top value label it would overlap, until the two boxes overlap by no more than
     * ECharts' touch threshold (0.05 px for each box).
     */
    const val NAME_OVERLAP = 0.1f
}

/** One line series of a chart: points (ms, value; NaN breaks the line), its color and how it's drawn. */
class ChartLine(
    val xs: DoubleArray, val ys: DoubleArray, val color: Int,
    val width: Float = 1.5f, val step: Boolean = false, val smooth: Double = 0.0,
    /** A band: the area down to this other line, filled with [areaColor]. */
    val bandBottom: ChartLine? = null, val areaColor: Int = 0,
    /** An area down to the value axis' origin (ECharts' `areaStyle` with `origin: auto`), filled with [areaColor]. */
    val areaToOrigin: Boolean = false,
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
    fun yAxis(spec: LineChartSpec, measure: (String) -> Float = { ChartView.measure(it) }): Triple<ECharts.ValueScale, List<String>, Float> {
        val (dMin, dMax) = extentIn(spec.lines, spec.xMin.toDouble(), spec.xMax.toDouble())
        // fit_y_data: min(roundYAxis(min, floor), min_y_axis) and max(roundYAxis(max, ceil), max_y_axis), as HA's charts do.
        fun roundY(v: Double, f: (Double) -> Double) = if (kotlin.math.abs(v) < 1) v else f(v)
        val cfgMin = spec.minYAxis?.let { m -> if (spec.fitYData && dMin.isFinite()) min(roundY(dMin, ::floor), m) else m }
        val cfgMax = spec.maxYAxis?.let { m -> if (spec.fitYData && dMax.isFinite()) max(roundY(dMax, ::ceil), m) else m }
        val bounds = HaYBounds.of(dMin, dMax, spec.unit, false, cfgMin, cfgMax)
        val scale = if (dMin.isFinite()) ECharts.valueScale(dMin, dMax, bounds.fixMin, bounds.fixMax, 0.02)
            else ECharts.ValueScale(0.0, 1.0, listOf(0.0, 1.0))
        val labels = scale.ticks.map { v -> HaChartFormat.number(v, if (v == 0.0) 0 else bounds.fractionDigits, bounds.fractionDigits) }
        val width = labels.maxOfOrNull { max(measure(it) + 5f, 25f) } ?: 25f
        return Triple(scale, labels, width)
    }

    /** The chart for [widthCss] x [heightCss] (its spec's height unless a fixed slot sizes it). */
    fun scene(spec: LineChartSpec, widthCss: Float, ctx: VitroCardContext, heightCss: Float = spec.heightCss): ChartScene {
        val t = theme(ctx)
        val s = ChartScene()
        val (scale, labels, yWidth) = yAxis(spec)
        val hasName = !spec.unit.isNullOrBlank()
        val left: Float
        val top: Float
        val bottom: Float
        val right: Float
        val labelMargin: Float
        // The widest value label's box, which the outer bounds keep inside the chart's left edge.
        val widest = (labels.maxOfOrNull { ChartView.width(it) } ?: 0f) + ChartText.LABEL_PAD_X
        if (spec.containLabel) {
            labelMargin = 8f
            left = 1f + widest + labelMargin
            top = 15f + ChartText.LABEL_H / 2
            bottom = 8f + ChartText.X_LABEL_H
            right = 1f
        } else {
            labelMargin = 5f
            left = max(max(spec.paddingYAxis, yWidth), widest + labelMargin)
            top = max(15f, ChartText.LABEL_H / 2 + if (hasName) ChartText.NAME_H - ChartText.NAME_OVERLAP else 0f)
            bottom = max(20f, 8f + ChartText.X_LABEL_H)
            right = 1f
        }
        val grid = RectF(left, top, widthCss - right, max(top, heightCss - bottom))
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
            if (l.areaToOrigin) {
                // getValueStart: the axis' start when it's all positive, its end when all negative, else zero.
                val origin = yOf(if (scale.min > 0) scale.min else if (scale.max < 0) scale.max else 0.0).toFloat()
                // One closed area per run of points: a null ends it.
                val area = Path()
                var i = 0
                while (i < px.size) {
                    while (i < px.size && (py[i].isNaN() || px[i].isNaN())) i++
                    val from = i
                    while (i < px.size && !py[i].isNaN() && !px[i].isNaN()) i++
                    if (i - from < 1) continue
                    ECharts.polyline(px.copyOfRange(from, i), py.copyOfRange(from, i), l.smooth, area, 1f)
                    area.lineTo(px[i - 1].toFloat(), origin)
                    area.lineTo(px[from].toFloat(), origin)
                    area.close()
                }
                s.fill(ChartScene.Fill(area, l.areaColor, grid))
            }
            if (l.width > 0f) {
                val path = Path()
                ECharts.polyline(px, py, l.smooth, path, 1f)
                s.stroke(ChartScene.Stroke(path, l.color, l.width, grid))
            }
        }

        // Labels: values right-aligned [labelMargin] left of the axis, times centered 8 px below it.
        val text = t.primaryTextColor
        scale.ticks.forEachIndexed { i, v ->
            s.text(ChartScene.Text(labels[i], grid.left - labelMargin, yOf(v).toFloat(), Paint.Align.RIGHT, text, false))
        }
        if (hasName) {
            // The name sits on top of the top label (ECharts moves it off the label it would overlap).
            val nameY = grid.top - ChartText.LABEL_H / 2 + ChartText.NAME_OVERLAP - ChartText.NAME_H / 2
            s.text(ChartScene.Text(spec.unit!!, grid.left, nameY, Paint.Align.LEFT, text, false))
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
        // ECharts' outer bounds: the widest name's box (5 px left of the grid, cut at labelW) stays inside the chart.
        val widest = if (showNames) rows.maxOfOrNull { r -> min(ChartView.width(names[r.entityId] ?: r.name), labelW) } ?: 0f else 0f
        val left = if (showNames) max(labelW, widest + ChartText.LABEL_PAD_X + 5f) else labelW
        val grid = RectF(left, 10f, widthCss - 1f, h - GRID_BOTTOM)
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

    /** Forgets the generic colors handed out, as a fresh HA page starts its own map (the parity harness, per case). */
    internal fun resetGenericColors() = synchronized(genericColors) { genericColors.clear() }

    /**
     * `computeTimelineColor`: unavailable and unknown; a state-colored domain's active or inactive color; an option of
     * an `input_select`, `select` or enum sensor by its index among the options; any other value a graph color of its
     * own.
     */
    fun stateColor(ctx: VitroCardContext, entityId: String, state: String): Int {
        val st = ctx.store[entityId] ?: return Color.TRANSPARENT
        if (state == "unavailable") return Color.TRANSPARENT
        if (state == "unknown") return 0xFF606060.toInt()
        // A person's or tracker's zone (any state but home and not_home): its own `--state-<domain>-<zone>-color` when
        // the theme sets one, else a graph color of its own, as for any other value.
        val zoned = (st.domain == "person" || st.domain == "device_tracker") && state != "home" && state != "not_home"
        if (zoned) {
            val slug = state.lowercase(java.util.Locale.ROOT).replace(Regex("[^a-z0-9]+"), "_").trim('_')
            ThemeResolver.parseColor(ctx.theme.vars["state-${st.domain}-$slug-color"])?.let { return it }
        } else if (st.domain in STATE_COLORED) {
            // `stateColorProperties`: the state's own color first (an alert on red, acknowledged orange; a lock locked
            // green), then the domain's active or inactive one.
            return HaStateColor.of(ctx, st, state)
                ?: if (st.copy(state = state).isActive) ctx.theme.activeColor(st.domain) else ctx.theme.stateInactiveColor
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
    /** The configured names, read when drawn: a name-picker `name` follows the registries and states. */
    private val names: Map<String, String>
        get() = entities.mapNotNull { e -> e.nameFor(ctx.store[e.entity])?.let { e.entity to it } }.toMap()
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
    /** The history drawn: the stream's states merged with the statistics before them, built on [GraphWork]'s thread. */
    private var history: HistoryResult? = null
    /** The graph thread's own copies of the stream's history and the statistics, which each answer merges. */
    private var workerStates: HistoryResult? = null
    private var workerStats: HistoryResult? = null
    @Volatile private var generation = 0

    /**
     * The first statistics are still on their way while nothing is drawn: the history's first answer waits for them
     * (at most [STATS_WAIT_MS]), so the charts are built once with both rather than once for each.
     */
    private var awaitingStats = false
    private var statsWaitPosted = false
    private val statsWaitOver = Runnable {
        statsWaitPosted = false
        awaitingStats = false
        if (history != null) render()
    }

    private fun stopStatsWait() {
        ctx.main.removeCallbacks(statsWaitOver)
        statsWaitPosted = false
    }

    /** The charts on screen, by kind and unit: a history with the same ones redraws them in place ([render]). */
    private var shown: ShownCharts? = null

    /** The rejected stream's message: it stays on screen over any history held until a stream delivers again. */
    private var streamError: String? = null

    /** A render skipped while the card was hidden (a cached view): done when it shows again. */
    private var renderPending = false

    /** `expand_legend`: the legends start expanded. */
    private val expandLegend = config.raw.optBoolean("expand_legend", false)

    /** Each line chart's legend expanded or collapsed by its chip, by unit. */
    private val legendExpanded = HashMap<String, Boolean>()

    /** The line charts and legends a fixed slot sizes ([measureInSlot]). */
    private val slotCharts = ArrayList<SlotChart>()
    private val fixedHeight = hasFixedHeight(config)

    override fun measureCard(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (fixedHeight && slotCharts.isNotEmpty() && MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            measureInSlot(this, slotCharts, widthMeasureSpec, heightMeasureSpec, ctx.dp) { w, h -> super.measureCard(w, h) }
        } else {
            super.measureCard(widthMeasureSpec, heightMeasureSpec)
        }
    }

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
        info.text = HaText.ui("ui.components.history_charts.loading_history")
        info.minHeight = Math.round(60f * ctx.dp)
        content.addView(info, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(60f * ctx.dp)))
        content.addView(charts, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(col, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** `_redrawGraph`: once a minute the window follows the clock, from the history already held. */
    private val redraw: Runnable = object : Runnable {
        override fun run() {
            if (failure != null) return
            if (history != null && !awaitingStats) render()
            ctx.main.postDelayed(this, 60_000L)
        }
    }

    override fun onCardFailed() {
        ctx.main.removeCallbacks(redraw)
        stopStatsWait()
        unsubscribe?.invoke()
        unsubscribe = null
        stream = null
        generation++
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        subscribe()
        ctx.main.removeCallbacks(redraw)
        ctx.main.postDelayed(redraw, 60_000L)
    }

    override fun onDetachedFromWindow() {
        ctx.main.removeCallbacks(redraw)
        stopStatsWait()
        awaitingStats = false
        unsubscribe?.invoke()
        unsubscribe = null
        stream = null
        generation++
        super.onDetachedFromWindow()
    }

    private fun subscribe() {
        if (entityIds.isEmpty() || unsubscribe != null || failure != null) return
        val gen = ++generation
        val s = HaGraph.Stream(hours)
        stream = s
        val now = ctx.now()
        // Built again for each connection: a reconnect asks for the window as it is then, and the stream starts over.
        unsubscribe = ctx.subscribeWsFresh({
            HaGraph.historyStreamMessage(entityIds, hours, ctx.now()) { ctx.store[it] != null }
        }, { ev, first ->
            ctx.main.post {
                if (stream !== s) return@post
                streamError = null
                // The states the history is read with, taken here: the graph thread doesn't read the store.
                val states = stateSnapshot()
                val unit = temperatureUnit()
                val at = ctx.now()
                GraphWork.run(ctx.main, {
                    if (first) s.restart()
                    val combined = s.process(ev, at)
                    val computed = HaHistory.compute({ states[it] }, combined, entityIds, unit)
                    workerStates = computed
                    HaHistory.merge(computed, workerStats)
                }) { merged ->
                    if (stream !== s) return@run
                    history = merged
                    if (!awaitingStats) {
                        render()
                    } else if (!statsWaitPosted) {
                        statsWaitPosted = true
                        ctx.main.postDelayed(statsWaitOver, STATS_WAIT_MS)
                    }
                }
            }
        }, { error ->
            ctx.main.post {
                if (stream !== s) return@post
                // HA shows the error instead of the charts (`ui.components.history_charts.error`).
                stream = null
                unsubscribe = null
                val why = error?.optString("message")?.takeIf { it.isNotEmpty() } ?: error?.optString("code").orEmpty()
                streamError = HaText.ui("ui.components.history_charts.error").let { if (why.isEmpty()) it else "$it: $why" }
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
            // Nothing drawn yet: the first history waits for these.
            if (history == null) awaitingStats = true
            ctx.callWs(stats) { ok, result, _ ->
                ctx.main.post {
                    if (gen != generation) return@post
                    if (!ok || result !is JSONObject) {
                        statsArrived(null)
                        return@post
                    }
                    val states = stateSnapshot()
                    GraphWork.run(ctx.main, {
                        // A newer subscription's statistics replace these: drop them unread.
                        if (gen != generation) return@run null
                        val lts = HaHistory.statisticsToHistory({ states[it] }, result, entityIds)
                        workerStats = lts
                        workerStates?.let { HaHistory.merge(it, lts) }
                    }) { merged -> if (gen == generation) statsArrived(merged) }
                }
            }
        }
    }

    /** The statistics answered (or failed: [merged] null and no history yet): draw what there is, once. */
    private fun statsArrived(merged: HistoryResult?) {
        if (merged != null) history = merged
        val waited = awaitingStats
        awaitingStats = false
        stopStatsWait()
        // A history that waited for these draws now; one already drawn draws again with them.
        if (history != null && (waited || merged != null)) render()
    }

    /** The card's entities as the store has them now, for the graph thread. */
    private fun stateSnapshot(): Map<String, dev.glassworks.engine.vitro.data.EntityState?> {
        val out = HashMap<String, dev.glassworks.engine.vitro.data.EntityState?>(entityIds.size * 2)
        for (id in entityIds) out[id] = ctx.store[id]
        return out
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
            slotCharts.clear()
            shown = null
            info.text = it
            info.visibility = VISIBLE
            return
        }
        val history = this.history ?: return
        // A hidden card (a cached view) keeps its history but builds nothing until it shows.
        if (!isShown && isAttachedToWindow) {
            renderPending = true
            return
        }
        renderPending = false
        if (history.line.isEmpty() && history.timeline.isEmpty()) {
            charts.removeAllViews()
            slotCharts.clear()
            shown = null
            info.text = HaText.ui("ui.components.history_charts.no_history_found")
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

        // The names once per render: the shared axis is sized with the names it draws.
        val names = this.names
        val lineSpecs = history.line.map { u -> lineSpec(u, start, end, names) }
        // The charts share the widest y axis (paddingYAxis).
        val padding = max(
            lineSpecs.maxOfOrNull { HaLineChart.yAxis(it.first).third } ?: 0f,
            if (history.timeline.isNotEmpty()) HaTimelineChart.labelWidth(history.timeline, names, showNames) else 0f,
        )
        val padded = lineSpecs.map { (spec, legend) ->
            LineChartSpec(spec.lines, spec.xMin, spec.xMax, spec.unit, spec.heightCss, false, config.raw.optDouble("min_y_axis").takeIf { !it.isNaN() }, config.raw.optDouble("max_y_axis").takeIf { !it.isNaN() }, padding,
                fitYData = config.raw.optBoolean("fit_y_data", false)) to legend
        }
        val rows = history.timeline
        val kinds = buildList {
            if (rows.isNotEmpty()) add("timeline")
            padded.forEach { add("line:" + it.first.unit.orEmpty()) }
        }
        // The same charts as on screen (a new answer, the minute's redraw, a new width): their scenes are drawn again
        // where they are, as the chart elements of HA's page update in place.
        val current = shown
        if (current != null && current.kinds == kinds) {
            current.timeline?.let { chart ->
                chart.heightCss = HaTimelineChart.height(rows.size)
                chart.setScene { w -> HaTimelineChart.scene(rows, names, start, end, padding, w, ctx, showNames) }
            }
            slotCharts.clear()
            current.lines.forEachIndexed { i, line ->
                val (spec, legend) = padded[i]
                line.host.update(spec)
                line.legend?.let { if (!it.shows(legend)) it.setItems(legend) }
                slotCharts += SlotChart(line.host.chart, line.legendBox, spec.heightCss)
            }
            return
        }
        charts.removeAllViews()
        slotCharts.clear()
        var index = 0
        var timelineChart: ChartView? = null
        if (rows.isNotEmpty()) {
            val chart = ChartView(ctx.context, ctx.dp).apply { heightCss = HaTimelineChart.height(rows.size) }
            chart.setScene { w -> HaTimelineChart.scene(rows, names, start, end, padding, w, ctx, showNames) }
            charts.addView(entry(chart, timeline = true, first = index++ == 0))
            timelineChart = chart
        }
        val lines = ArrayList<ShownLine>()
        for ((withPad, legend) in padded) {
            val box = LinearLayout(ctx.context).apply { orientation = LinearLayout.VERTICAL }
            val chart = LineChartHost(ctx, withPad)
            box.addView(chart, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            var legendView: View? = null
            var legendChips: ChartLegendView? = null
            if (showNames) {
                val unit = withPad.unit.orEmpty()
                legendChips = ChartLegendView(ctx).apply {
                    // The chip's state outlives the redraw each minute, as the frontend's chart element does.
                    expanded = legendExpanded[unit] ?: expandLegend
                    onExpandedChange = { legendExpanded[unit] = it }
                    setItems(legend)
                    onLabel = { id -> ctx.openMoreInfo(id) }
                }
                legendView = scrollingLegend(legendChips)
                box.addView(legendView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            slotCharts += SlotChart(chart.chart, legendView, withPad.heightCss)
            lines += ShownLine(chart, legendChips, legendView)
            charts.addView(entry(box, timeline = false, first = index++ == 0))
        }
        shown = ShownCharts(kinds, timelineChart, lines)
    }

    /** The charts [render] built, so a history with the same kinds and units redraws them where they are. */
    private class ShownCharts(val kinds: List<String>, val timeline: ChartView?, val lines: List<ShownLine>)
    private class ShownLine(val host: LineChartHost, val legend: ChartLegendView?, val legendBox: View?)

    private fun entry(child: View, timeline: Boolean, first: Boolean): View = historyEntry(ctx, child, timeline, first)

    private fun lineSpec(u: LineUnit, start: Long, end: Long, names: Map<String, String>): Pair<LineChartSpec, List<LegendItem>> =
        stateHistoryLineSpec(ctx, u, start, end, chartWidthCss(), names, colors, showNames, temperatureUnit(),
            config.raw.optDouble("min_y_axis").takeIf { !it.isNaN() }, config.raw.optDouble("max_y_axis").takeIf { !it.isNaN() },
            config.raw.optBoolean("fit_y_data", false))

    /** The charts' width (CSS px): the card's less its border and the content's 16 px sides. */
    private fun chartWidthCss(): Double = (width.takeIf { it > 0 }?.let { it / ctx.dp - 2.0 - 32.0 }) ?: 466.0

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw && history != null && !awaitingStats) post { render() }
    }
}

/** How long a history graph's first answer waits for its statistics before it draws without them. */
private const val STATS_WAIT_MS = 500L

/** `.entry-container`: a line chart 8 px down, a first timeline 16 px down; later ones under a 2 px divider. */
internal fun historyEntry(ctx: VitroCardContext, child: View, timeline: Boolean, first: Boolean, timelineTopMargin: Float = 16f): View {
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
    // `.entry-container.timeline:first-child { margin-top: var(--timeline-top-margin) }`: the history graph card sets it
    // to 16 px; More Info leaves it unset ([timelineTopMargin] 0).
    lp.topMargin = Math.round((if (first) (if (timeline) timelineTopMargin else 0f) else if (timeline) 32f else 16f) * ctx.dp)
    box.layoutParams = lp
    return box
}

/** `_getDefaultHeight`: half the chart's width, at least 200, at most `--chart-max-height` (350). */
internal fun defaultHistoryChartHeight(widthCss: Double): Float = min(max(widthCss.toFloat() / 2f, 200f), 350f)

/**
 * `generateStateHistoryChartLineData`: [u]'s series over [start]..[end], down-sampled for a [widthCss] wide chart, and
 * the legend (fills aren't in it). Shared by the history graph card and the More Info dialog's history (#885).
 */
internal fun stateHistoryLineSpec(
    ctx: VitroCardContext, u: LineUnit, start: Long, end: Long, widthCss: Double,
    names: Map<String, String>, colors: Map<String, Int>, showNames: Boolean, tempUnit: String?,
    minYAxis: Double?, maxYAxis: Double?, fitYData: Boolean,
): Pair<LineChartSpec, List<LegendItem>> {
    val colorIndex = intArrayOf(0)
    val lines = ArrayList<ChartLine>()
    val legend = ArrayList<LegendItem>()
    val endD = end.toDouble()
    for (e in u.data) {
        val state = ctx.store[e.entityId]
        val series = HaHistoryLines.series(e, endD, ctx.now().toDouble(), names[e.entityId] ?: e.name, colors[e.entityId],
            showNames, ctx.theme, colorIndex, state)
        for (sr in series) {
            // ha-chart-base's minmax down-sampling: one frame per device pixel of the chart's width.
            val sampled = HaGraph.downSample(sr.xs.indices.map { doubleArrayOf(sr.xs[it], sr.ys[it]) }, widthCss * ctx.dp, start.toDouble(), endD, false)
            val xs = DoubleArray(sampled.size) { sampled[it][0] }
            val ys = DoubleArray(sampled.size) { sampled[it][1] }
            lines += if (sr.fill) {
                // A fill: no line, the area down to the axis at half opacity (`color + "7F"`).
                ChartLine(xs, ys, sr.color, 0f, step = true, areaColor = CardSurface.withAlpha(sr.color, 0x7F / 255f), areaToOrigin = true)
            } else {
                ChartLine(xs, ys, sr.color, 1.5f, step = true)
            }
            if (!sr.fill) legend += LegendItem(e.entityId, sr.name, sr.color, HaHistoryLines.legendValue(sr.id, state, tempUnit))
        }
    }
    // The same bounds as the chart drawn, so the shared y-axis width is measured from its labels.
    return LineChartSpec(lines, start, end, u.unit.takeIf { it.isNotBlank() }, defaultHistoryChartHeight(widthCss), false,
        minYAxis, maxYAxis, fitYData = fitYData) to legend
}

/** A line chart, as high as its spec unless a fixed slot sizes it. */
internal class LineChartHost(private val ctx: VitroCardContext, spec: LineChartSpec) : LinearLayout(ctx.context) {
    val chart = ChartView(ctx.context, ctx.dp).apply { heightCss = spec.heightCss }

    init {
        chart.setSizedScene { w, h -> HaLineChart.scene(spec, w, ctx, h) }
        addView(chart, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** Draws [spec] in this chart instead (a new answer or width for the same unit). */
    fun update(spec: LineChartSpec) {
        chart.heightCss = spec.heightCss
        chart.setSizedScene { w, h -> HaLineChart.scene(spec, w, ctx, h) }
    }
}

/**
 * [legend] in a vertical scroller, as HA's `.chart-legend` (`max-height: 60%; overflow-y: auto`): as tall as the legend
 * anywhere but a fixed slot, where [measureInSlot] caps it and its rows past the cap (an expanded legend's "Less"
 * included) scroll into view.
 */
internal fun scrollingLegend(legend: View): View = android.widget.ScrollView(legend.context).apply {
    isFillViewport = false
    overScrollMode = View.OVER_SCROLL_NEVER
    addView(legend, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
}

/** A graph card's line chart and its legend, for [measureInSlot]; [naturalCss] is the chart's height outside one. */
internal class SlotChart(val chart: ChartView, val legend: View?, val naturalCss: Float)

/** Whether a graph card sits in a fixed slot: `grid_options.rows` is a number (HA's `hasFixedHeight`). */
internal fun hasFixedHeight(config: CardConfig): Boolean = config.raw.optJSONObject("grid_options")?.opt("rows") is Number

/**
 * HA's graph cards in a fixed `grid_options.rows` slot (`height: 100%`, `--chart-max-height: 100%`): each line chart
 * takes an equal share of what the rest of the card leaves of the slot (`flex: 1`), and its legend keeps its own
 * height, at most 60 % of that share (`.chart-legend`), so the chart shrinks until chart and legend fill the slot.
 * [card] is measured at its natural height first, then at the slot's with the charts sized ([superMeasure] is the
 * card's own `super.onMeasure`). Each chart builds its paths again for its new height.
 */
internal fun measureInSlot(card: ViewGroup, charts: List<SlotChart>, widthSpec: Int, heightSpec: Int, dp: Float, superMeasure: (Int, Int) -> Unit) {
    // Views between a chart and the card measure again even when their own measure spec stays the same.
    fun touch(v: View) {
        var p: View? = v
        while (p != null && p !== card) { p.forceLayout(); p = p.parent as? View }
    }
    for (c in charts) {
        c.chart.sizeInMeasure(c.naturalCss)
        c.legend?.let { it.layoutParams?.height = ViewGroup.LayoutParams.WRAP_CONTENT; touch(it) }
        touch(c.chart)
    }
    superMeasure(widthSpec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
    fun legendH(c: SlotChart) = c.legend?.takeIf { it.visibility != View.GONE }?.measuredHeight ?: 0
    val used = charts.sumOf { it.chart.measuredHeight + legendH(it) }
    val rest = card.measuredHeight - used
    val share = max(0, View.MeasureSpec.getSize(heightSpec) - rest).toFloat() / charts.size
    for (c in charts) {
        val legend = legendH(c)
        val shown = min(legend.toFloat(), floor(0.6f * share))
        if (shown < legend) c.legend?.layoutParams?.height = shown.toInt()
        c.chart.sizeInMeasure(max(0f, share - shown) / dp)
        c.legend?.let(::touch)
        touch(c.chart)
    }
    superMeasure(widthSpec, heightSpec)
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

    /** The legend in its scroller ([scrollingLegend]): what the card lays out and a fixed slot caps. */
    private val legendBox = scrollingLegend(legend)
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
        // Until the width is known: ha-chart-base's default for the S1's card (half its 466 px chart).
        chart.heightCss = 233f
        // statistics-chart's `.info`: centered on a 60 px line.
        info.gravity = Gravity.CENTER
        info.visibility = GONE
        content.addView(info, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(60f * ctx.dp)))
        content.addView(chart, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(legendBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        legend.onLabel = { id -> ctx.openMoreInfo(id.substringBeforeLast('-').takeIf { ctx.store[it] != null } ?: id) }
        legend.expanded = config.raw.optBoolean("expand_legend", false)
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

    override fun onCardFailed() {
        ctx.main.removeCallbacks(refresh)
        generation++
        // A chart being prepared off the main thread doesn't land on the failed card.
        prepareGeneration++
    }

    private fun fetch() {
        ctx.main.removeCallbacks(refresh)
        if (ids.isEmpty() || failure != null) return
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

    /** The last statistics drawn, sampled again for a new width. */
    private var lastInput: StatisticsChartData.Input? = null
    private var prepareGeneration = 0
    private var preparedWidth = Float.NaN

    /**
     * The chart's width (CSS px): its own once laid out and shown, else the card's less its border and 16 px sides.
     * A hidden chart ("No statistics found.") keeps the width of its last layout, which a resize since has outdated.
     */
    private fun chartWidthCss(): Float? =
        chart.width.takeIf { it > 0 && chart.visibility != GONE }?.let { it / ctx.dp }
            ?: width.takeIf { it > 0 }?.let { (it - paddingLeft - paddingRight) / ctx.dp - 32f }

    /** `_getDefaultHeight`: half the chart's width, at least 200, at most `--chart-max-height` (350). */
    private fun defaultChartHeight(widthCss: Float): Float = min(max(widthCss / 2f, 200f), 350f)

    private val fixedHeight = hasFixedHeight(config)

    override fun measureCard(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (fixedHeight && chart.visibility == VISIBLE && MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            val natural = chartWidthCss()?.let(::defaultChartHeight) ?: chart.heightCss
            measureInSlot(this, listOf(SlotChart(chart, legendBox, natural)), widthMeasureSpec, heightMeasureSpec, ctx.dp) { w, h -> super.measureCard(w, h) }
        } else {
            super.measureCard(widthMeasureSpec, heightMeasureSpec)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw && lastInput != null) post { prepare() }
    }

    /** What the chart needs from the card and the entities now, read on the main thread for [prepare]. */
    private fun render(result: JSONObject, metadata: Map<String, JSONObject>, cardUnit: String?) {
        val unit = cardUnit ?: ids.mapNotNull { displayUnit(it, metadata[it]) }.distinct().singleOrNull()
        lastInput = StatisticsChartData.Input(
            ids = ids,
            result = result,
            statTypes = statTypes,
            hideLegend = hideLegend,
            names = ids.associateWith { id -> config.entities().firstOrNull { it.entity == id }?.nameFor(ctx.store[id]) ?: ctx.store[id]?.displayName ?: id },
            current = ids.associateWith { id -> ctx.store[id]?.state?.toDoubleOrNull()?.takeIf { it.isFinite() } },
            statUnits = ids.associateWith { id -> displayUnit(id, metadata[id]) },
            unit = unit,
            colors = ids.indices.map { HaGraphColors.byIndex(ctx.theme, it) },
            nowMs = ctx.now(),
            minYAxis = config.raw.optDouble("min_y_axis").takeIf { !it.isNaN() },
            maxYAxis = config.raw.optDouble("max_y_axis").takeIf { !it.isNaN() },
            fitYData = config.raw.optBoolean("fit_y_data", false),
        )
        preparedWidth = Float.NaN
        prepare()
    }

    /**
     * Builds the chart's series and down-samples them as HA's chart does, on [GraphWork]'s thread: a `period:
     * 5minute` chart over weeks holds thousands of points per series. The paths are built on the main thread from
     * what comes back. Without a width yet, [onSizeChanged] starts it.
     */
    private fun prepare() {
        val input = lastInput ?: return
        if (failure != null) return
        val w = chartWidthCss() ?: return
        if (w == preparedWidth) return
        preparedWidth = w
        val gen = ++prepareGeneration
        val typeface = VitroFonts.system(400)
        val dp = ctx.dp
        GraphWork.run(ctx.main, { StatisticsChartData.prepare(input, w, dp, typeface) }) { out ->
            if (gen == prepareGeneration && input === lastInput) apply(out)
        }
    }

    private fun apply(out: StatisticsChartData.Prepared) {
        if (out.lines.isEmpty()) {
            // A successful answer with nothing in the window: HA's "No statistics found." in place of the chart.
            legend.setItems(emptyList())
            chart.setScene { ChartScene() }
            chart.visibility = GONE
            info.text = HaText.ui("ui.components.statistics_charts.no_statistics_found")
            info.visibility = VISIBLE
            return
        }
        chart.visibility = VISIBLE
        info.visibility = GONE
        legend.setItems(out.legend)
        val spec = out.spec(defaultChartHeight(preparedWidth))
        chart.heightCss = spec.heightCss
        chart.setSizedScene { w, h -> HaLineChart.scene(spec, w, ctx, h) }
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
