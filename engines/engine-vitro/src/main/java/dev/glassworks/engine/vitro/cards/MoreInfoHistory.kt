/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.cards

import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import dev.glassworks.engine.vitro.data.HaText
import dev.glassworks.engine.vitro.ui.VitroCardContext
import dev.glassworks.engine.vitro.ui.VitroFonts
import dev.glassworks.ui.Inter
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import kotlin.math.ceil
import kotlin.math.max

/**
 * HA's `ha-more-info-history` (frontend 20261028), the history section of a More Info dialog (#885):
 *
 * - The header: "History", "5-minute aggregated" under it while statistics are drawn, and "Show more" on the right,
 *   a link to `/history` for the entity from the start of yesterday ([showMoreHref]), which [onShowMore] opens.
 * - A sensor with a `state_class` whose statistics the recorder has (HA's config lists `recorder`, and
 *   `recorder/get_statistics_metadata` answers something): the last 24 hours of 5-minute statistics (`state`, `min`, `mean`, `max`), as `statistics-chart`
 *   draws them without a legend, fetched again every minute.
 * - Anything else: `state-history-charts up-to-now`, the last 24 hours of the entity's states from a `history/stream`
 *   subscription, without names, its time axis following the clock once a minute.
 *
 * Its sides are the dialog's 24 px (`padding-inline: var(--ha-space-6)`): the caller adds it at the dialog's full
 * width. [onGraph] is told when a chart got its data (Clarity's trace).
 */
internal class MoreInfoHistoryView(
    private val ctx: VitroCardContext,
    private val entityId: String,
    private val onShowMore: (String) -> Unit,
    private val onGraph: (View) -> Unit = {},
) : LinearLayout(ctx.context) {
    private val theme = ctx.theme
    private val aggregate: TextView
    private val charts = LinearLayout(ctx.context).apply { orientation = VERTICAL }
    private val info = cssText(ctx, 14f, theme.secondaryTextColor).apply { gravity = Gravity.CENTER }
    private var statistics: JSONObject? = null
    private var metadata: JSONObject? = null
    private var stream: HaGraph.Stream? = null
    private var unsubscribe: (() -> Unit)? = null
    private var stateHistory: HistoryResult? = null
    private var generation = 0
    private var prepareGeneration = 0
    private var started = false

    /** This shows the state history (not statistics), whether or not its subscription is up now: [reconnected]. */
    private var historyMode = false

    /**
     * Counts the statistics fetches ([fetchStatistics]): every minute's refresh is one, all in the same [generation].
     * An answer draws only if it is newer than the last one drawn ([statsApplied]), so a slow answer to an earlier
     * refresh can't put older data over a newer chart, and answers slower than a minute still draw.
     */
    private var statsAttempt = 0

    /** The attempt whose answer the chart shows now. */
    private var statsApplied = 0

    /** The first statistics attempt's deadline ([fetchStatistics]), until it settles. */
    private var pendingDeadline: Runnable? = null

    /**
     * The connection came back while this shows. A live state history's subscription is held by the client, which sends
     * it again itself; one Home Assistant refused has no subscription left, so it is asked for again here. Statistics
     * are plain requests: the next tick fetches them.
     */
    fun reconnected() {
        // By the mode, not the stream: a subscription HA refused has no stream left to renew.
        if (!started || !historyMode || unsubscribe != null) return
        unsubscribe?.invoke()
        unsubscribe = null
        stream = null
        subscribeHistory(generation)
    }

    /** `_setUpdateTimer`: statistics are fetched again, a state history's window follows the clock. */
    private val tick: Runnable = object : Runnable {
        override fun run() {
            if (statistics != null) fetchStatistics(generation) else if (stateHistory != null) render()
            ctx.main.postDelayed(this, MINUTE_MS)
        }
    }

    init {
        orientation = VERTICAL
        val side = px(24f)
        // .header: History (and the aggregate note) on the left, Show more on the right, centred on each other.
        val header = LinearLayout(ctx.context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(side, 0, side, 0)
        }
        val titles = LinearLayout(ctx.context).apply { orientation = VERTICAL }
        val h2 = cssText(ctx, 20f, theme.primaryTextColor, Inter.MEDIUM)
        line(h2, HaText.ui("ui.dialogs.more_info_control.history"), 20f, 1.6f)
        titles.addView(h2, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        aggregate = cssText(ctx, 12f, theme.secondaryTextColor)
        line(aggregate, HaText.ui("ui.dialogs.more_info_control.aggregate"), 12f, 1.6f)
        aggregate.visibility = GONE
        titles.addView(aggregate, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        header.addView(titles, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val showMore = cssText(ctx, 14f, theme.primaryColor).apply {
            paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
            isClickable = true
            isFocusable = true
            setOnClickListener { onShowMore(showMoreHref(ctx.now())) }
        }
        line(showMore, HaText.ui("ui.dialogs.more_info_control.show_more"), 14f, 1.6f)
        header.addView(showMore, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(header, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = px(8f) })

        val body = LinearLayout(ctx.context).apply {
            orientation = VERTICAL
            setPadding(side, 0, side, 0)
        }
        // Until data comes: HA's "Loading history…" on state-history-charts' 60 px line.
        line(info, HaText.ui("ui.components.history_charts.loading_history"), 14f, 1.6f)
        body.addView(info, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(60f)))
        body.addView(charts, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(body, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!started) start()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    /** Stops every request and timer: the dialog closed, or showed something else. */
    fun stop() {
        started = false
        historyMode = false
        generation++
        prepareGeneration++
        ctx.main.removeCallbacks(tick)
        pendingDeadline?.let(ctx.main::removeCallbacks)
        pendingDeadline = null
        unsubscribe?.invoke()
        unsubscribe = null
        stream = null
    }

    /** `_getStateHistory`: statistics for a sensor with a state class that has them, else the state history. */
    private fun start() {
        started = true
        val gen = ++generation
        val state = ctx.store[entityId]
        val stateClass = state?.attributes?.get("state_class")
        // _getStateHistory: statistics only with the recorder loaded (isComponentLoaded(hass.config, "recorder")), for a
        // sensor with a state class; else the state history.
        if (recorderLoaded() && entityId.substringBefore('.') == "sensor" && stateClass != null && stateClass != JSONObject.NULL &&
            stateClass.toString().isNotEmpty()
        ) {
            fetchStatistics(gen, first = true)
        } else {
            subscribeHistory(gen)
        }
        ctx.main.removeCallbacks(tick)
        ctx.main.postDelayed(tick, MINUTE_MS)
    }

    /**
     * `_fetchStatistics`: the metadata and the last 24 hours of 5-minute statistics, asked for together. Statistics
     * without metadata are no statistics: the first time, the state history is drawn instead.
     */
    private fun fetchStatistics(gen: Int, first: Boolean = false) {
        val attempt = ++statsAttempt
        var meta: Any? = null
        var stats: JSONObject? = null
        var answers = 0
        var settled = false
        // The first attempt always ends: without both answers in time, the state history is drawn instead (a later
        // answer to this attempt is ignored). A refresh keeps the chart it has.
        val deadline = Runnable {
            if (settled || gen != generation) return@Runnable
            settled = true
            if (first) subscribeHistory(gen)
        }
        if (first) {
            pendingDeadline = deadline
            ctx.main.postDelayed(deadline, FIRST_ANSWER_MS)
        }
        val done = {
            if (++answers == 2 && gen == generation && attempt > statsApplied && !settled) {
                settled = true
                ctx.main.removeCallbacks(deadline)
                val m = (meta as? JSONArray)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
                val s = stats
                if (m.isNotEmpty() && s != null) {
                    // Only statistics drawn count as the newest: a failed refresh leaves an earlier one free to draw.
                    statsApplied = attempt
                    metadata = m.firstOrNull { it.optString("statistic_id") == entityId } ?: m.first()
                    statistics = s
                    aggregate.visibility = VISIBLE
                    renderStatistics()
                } else if (first) {
                    subscribeHistory(gen)
                }
            }
        }
        ctx.callWs(JSONObject().put("type", "recorder/get_statistics_metadata").put("statistic_ids", JSONArray(listOf(entityId)))) { _, result, _ ->
            ctx.main.post { meta = result; done() }
        }
        // fetchStatistics(callWS, subHours(new Date(), 24), undefined, [id], "5minute", undefined, statTypes).
        val msg = JSONObject()
            .put("type", "recorder/statistics_during_period")
            .put("start_time", HaGraph.isoString(HaGraph.jsDateMs((ctx.now() - DAY_MS).toDouble())))
            .put("statistic_ids", JSONArray(listOf(entityId)))
            .put("period", "5minute")
            .put("types", JSONArray(STAT_TYPES))
        ctx.callWs(msg) { ok, result, _ ->
            ctx.main.post { stats = if (ok) result as? JSONObject else null; done() }
        }
    }

    /** `subscribeHistoryStatesTimeWindow(hass, …, 24, [id])`, computed as `computeHistory` does. */
    private fun subscribeHistory(gen: Int) {
        if (gen != generation || unsubscribe != null) return
        historyMode = true
        val s = HaGraph.Stream(HOURS)
        stream = s
        // Built again for each connection: a reconnect asks for the window as it is then, and the stream starts over.
        unsubscribe = ctx.subscribeWsFresh({
            HaGraph.historyStreamMessage(listOf(entityId), HOURS, ctx.now()) { ctx.store[it] != null }
        }, { ev, first ->
            ctx.main.post {
                if (stream !== s) return@post
                if (first) s.restart()
                val combined = s.process(ev, ctx.now())
                stateHistory = HaHistory.compute({ ctx.store[it] }, combined, listOf(entityId), temperatureUnit())
                render()
            }
        }, { error ->
            ctx.main.post {
                if (stream !== s) return@post
                stream = null
                unsubscribe = null
                // The error stays until a new subscription delivers: the minute tick mustn't redraw the old history.
                stateHistory = null
                // HA's alert: "Unable to load history: <message>".
                val why = error?.optString("message")?.takeIf { it.isNotEmpty() } ?: error?.optString("code").orEmpty()
                charts.removeAllViews()
                line(info, HaText.ui("ui.components.history_charts.error").let { if (why.isEmpty()) it else "$it: $why" }, 14f, 1.6f)
                info.visibility = VISIBLE
            }
        })
    }

    /**
     * HA's `isComponentLoaded(hass.config, "recorder")`, from HA's config's `components`. A config that doesn't list them
     * (a test's) counts as one with the recorder, as MoreInfoSheet counts history.
     */
    private fun recorderLoaded(): Boolean {
        val components = ctx.haConfig().optJSONArray("components") ?: return true
        return (0 until components.length()).any { components.optString(it) == "recorder" }
    }

    private fun temperatureUnit(): String? = ctx.haConfig().optJSONObject("unit_system")?.optString("temperature")?.takeIf { it.isNotEmpty() }

    /** The charts' width, CSS px: the dialog's less its 24 px sides. */
    private fun chartWidthCss(): Double = (width.takeIf { it > 0 }?.let { it / ctx.dp - 48.0 }) ?: FALLBACK_WIDTH

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == oldw) return
        if (statistics != null) post { renderStatistics() } else if (stateHistory != null) post { render() }
    }

    /** `statistics-chart` with `hide-legend` and the statistic's own unit, sampled for the chart's width off the main thread. */
    private fun renderStatistics() {
        val result = statistics ?: return
        val meta = metadata
        val unit = ctx.store[entityId]?.unit
            ?: meta?.optString("display_unit_of_measurement")?.takeIf { it.isNotEmpty() && it != "null" }
            ?: meta?.optString("statistics_unit_of_measurement")?.takeIf { it.isNotEmpty() && it != "null" }
        val input = StatisticsChartData.Input(
            ids = listOf(entityId),
            result = result,
            statTypes = STAT_TYPES,
            hideLegend = true,
            // `_statNames = { [entityId]: "" }`
            names = mapOf(entityId to ""),
            current = mapOf(entityId to ctx.store[entityId]?.state?.toDoubleOrNull()?.takeIf { it.isFinite() }),
            statUnits = mapOf(entityId to unit),
            unit = unit,
            colors = listOf(HaGraphColors.byIndex(theme, 0)),
            nowMs = ctx.now(),
            minYAxis = null,
            maxYAxis = null,
            fitYData = false,
        )
        val w = chartWidthCss().toFloat()
        val gen = ++prepareGeneration
        val dp = ctx.dp
        GraphWork.run(ctx.main, { StatisticsChartData.prepare(input, w, dp, VitroFonts.system(400)) }) { out ->
            if (gen != prepareGeneration) return@run
            charts.removeAllViews()
            if (out.lines.isEmpty()) {
                line(info, HaText.ui("ui.components.statistics_charts.no_statistics_found"), 14f, 1.6f)
                info.visibility = VISIBLE
                return@run
            }
            info.visibility = GONE
            val spec = out.spec(defaultHistoryChartHeight(w.toDouble()))
            val chart = ChartView(ctx.context, ctx.dp).apply { heightCss = spec.heightCss }
            chart.setSizedScene { cw, ch -> HaLineChart.scene(spec, cw, ctx, ch) }
            charts.addView(chart, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            onGraph(chart)
        }
    }

    /** `state-history-charts up-to-now`, without names: a timeline for text states, a line chart per unit. */
    private fun render() {
        val history = stateHistory ?: return
        charts.removeAllViews()
        if (history.line.isEmpty() && history.timeline.isEmpty()) {
            line(info, HaText.ui("ui.components.history_charts.no_history_found"), 14f, 1.6f)
            info.visibility = VISIBLE
            return
        }
        info.visibility = GONE
        // up-to-now: the window ends now, or at the newest state when the server is ahead of the clock.
        val newest = max(
            history.timeline.maxOfOrNull { it.data.lastOrNull()?.lastChanged ?: 0.0 } ?: 0.0,
            history.line.maxOfOrNull { u -> u.data.maxOfOrNull { it.states.lastOrNull()?.lastChanged ?: 0.0 } ?: 0.0 } ?: 0.0,
        )
        val end = max(ctx.now(), ceil(newest).toLong())
        val start = (end - HOURS * HaGraph.HOUR_MS).toLong()
        val width = chartWidthCss()
        val tempUnit = temperatureUnit()
        val specs = history.line.map { u -> stateHistoryLineSpec(ctx, u, start, end, width, emptyMap(), emptyMap(), false, tempUnit, null, null, false).first }
        val padding = max(
            specs.maxOfOrNull { HaLineChart.yAxis(it).third } ?: 0f,
            if (history.timeline.isNotEmpty()) HaTimelineChart.labelWidth(history.timeline, emptyMap(), false) else 0f,
        )
        var index = 0
        var drawn: View? = null
        if (history.timeline.isNotEmpty()) {
            val rows = history.timeline
            // HA's timeline takes its states' colors as its data arrives, before the Activity rows under it draw: a
            // state with no color of its own (a button's press time) gets the next graph color in the timeline's order,
            // which the Activity's dots then share (`computeTimeLineGenericColor`'s one map). The chart draws later, at
            // its first layout, so its states claim theirs here.
            for (r in rows) for (st in r.data) HaTimelineChart.stateColor(ctx, r.entityId, st.state)
            val chart = ChartView(ctx.context, ctx.dp).apply { heightCss = HaTimelineChart.height(rows.size) }
            chart.setScene { w -> HaTimelineChart.scene(rows, emptyMap(), start, end, padding, w, ctx, false) }
            charts.addView(historyEntry(ctx, chart, timeline = true, first = index++ == 0, timelineTopMargin = 0f))
            drawn = chart
        }
        for (spec in specs) {
            val padded = LineChartSpec(spec.lines, spec.xMin, spec.xMax, spec.unit, spec.heightCss, false, null, null, padding)
            val host = LineChartHost(ctx, padded)
            charts.addView(historyEntry(ctx, host, timeline = false, first = index++ == 0))
            drawn = drawn ?: host.chart
        }
        drawn?.let(onGraph)
    }

    private fun px(css: Float): Int = Math.round(css * ctx.dp)

    /** Sets [tv]'s text as one CSS line box ([lineHeight] × [sizeCss]), as HA's text sits in its line. */
    private fun line(tv: TextView, text: String, sizeCss: Float, lineHeight: Float) {
        tv.text = text
        lineBox(ctx, tv, sizeCss, lineHeight)
    }

    companion object {
        /** HA's `statTypes` for the dialog's statistics chart. */
        val STAT_TYPES = listOf("state", "min", "mean", "max")
        const val HOURS = 24.0

        /** How long the first statistics attempt may take before the state history is drawn instead. */
        const val FIRST_ANSWER_MS = 8_000L
        const val MINUTE_MS = 60_000L
        const val DAY_MS = 24 * 3_600_000L

        /** Until laid out: the width of the S1's sheet less its sides. */
        const val FALLBACK_WIDTH = 912.0

        /**
         * `/history?entity_id=…&start_date=…&back=1` as `createSearchParam` writes it, with `startOfYesterday()`: the
         * start of yesterday on the device's clock (date-fns uses the browser's zone), as an ISO time in UTC.
         */
        fun showMoreHref(entityId: String, nowMs: Long, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): String {
            val cal = Calendar.getInstance(zone).apply {
                timeInMillis = nowMs
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                add(Calendar.DAY_OF_MONTH, -1)
            }
            fun enc(v: String) = java.net.URLEncoder.encode(v, "UTF-8")
            return "/history?entity_id=" + enc(entityId) + "&start_date=" + enc(HaGraph.isoString(cal.timeInMillis)) + "&back=1"
        }
    }

    private fun showMoreHref(nowMs: Long): String = showMoreHref(entityId, nowMs)
}
