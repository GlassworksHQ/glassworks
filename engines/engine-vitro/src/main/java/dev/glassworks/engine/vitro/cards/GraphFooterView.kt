/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.cards

import dev.glassworks.engine.vitro.data.HaText
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import dev.glassworks.engine.vitro.ui.CardSurface
import dev.glassworks.engine.vitro.ui.VitroCardContext
import org.json.JSONObject

/**
 * HA's graph footer (`hui-graph-header-footer` drawing `hui-graph-base`), as the sensor card and an entity card's
 * `footer: {type: graph}` show it: the entity's history over `hours_to_show` (24) from `history/stream`, one mean
 * point per hour (`detail: 1`; `detail: 2` keeps min and max per 5 px), a smooth line in `--accent-color` at 80 %
 * opacity, 2 px wide, over a 10 % fill down to the bottom. As wide as the card, a fifth of that high.
 *
 * The stream's messages are merged and the path built on [GraphWork]'s thread, when the history or the width changes
 * (and each minute, as the window moves), never while drawing: the view draws the last path built for its width.
 */
class GraphFooterView(private val ctx: VitroCardContext, config: JSONObject) : View(ctx.context) {
    private val entityId: String? = config.optString("entity").takeIf { it.isNotBlank() }
    private val hoursToShow: Double = config.optDouble("hours_to_show", 24.0).takeIf { !it.isNaN() && it != 0.0 } ?: 24.0
    private val detail: Int = config.optInt("detail", 1).takeIf { it == 1 || it == 2 } ?: 1
    private val limits = config.optJSONObject("limits")
    private val minY: Double? = limits?.optDouble("min")?.takeIf { !it.isNaN() }
    private val maxY: Double? = limits?.optDouble("max")?.takeIf { !it.isNaN() }

    /** The color the line and fill take: `--accent-color`, or the card's state color when its `color` asks for it. */
    var color: Int = ctx.theme.accentColor
        set(v) {
            field = v
            linePaint.color = CardSurface.withAlpha(v, 0.8f)
            fillPaint.color = CardSurface.withAlpha(v, 0.1f)
            invalidate()
        }

    private val dp = ctx.dp
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * dp
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val errorPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ctx.theme.secondaryTextColor
        textSize = 14f * dp
        textAlign = Paint.Align.CENTER
    }

    /** HA's `.info` line (secondary text) for a completed history with nothing to plot. */
    private val infoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ctx.theme.secondaryTextColor
        textSize = 14f * dp
        textAlign = Paint.Align.CENTER
    }

    /** True when the history arrived and holds nothing plottable (only `unavailable`, say). */
    private var noHistory = false

    /** HA's footer error (`ui.components.history_charts.error`) while the stream is rejected; null otherwise. */
    private var error: String? = null

    /** The stream; read and merged on [GraphWork]'s thread only, in the order its messages came. */
    private var stream: HaGraph.Stream? = null
    /** The states drawn. Read on the graph thread only (merging the stream changes them there). */
    private var history: List<HistoryState>? = null
    private var unsubscribe: (() -> Unit)? = null
    private var line: Path? = null
    private var fill: Path? = null
    /** The width [line] was built for (-1: none yet). */
    private var builtFor = -1
    /** The newest path asked of the graph thread: an older answer is dropped. */
    private var buildToken = 0

    init {
        color = ctx.theme.accentColor
        setOnClickListener { entityId?.let(ctx.openMoreInfo) }
    }

    /**
     * For TalkBack: a button that opens the entity's More Info, named after its history ("Kitchen temperature
     * history") unless the graph shows a message instead. The name stays in the node, not in `contentDescription`,
     * which holds only the texts the graph draws.
     */
    override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.Button"
        if (contentDescription.isNullOrBlank()) {
            val name = entityId?.let { ctx.store[it]?.displayName ?: it }
            if (name != null) info.contentDescription = "$name history"
        }
    }

    /** CSS px: the footer's height is a fifth of its width (`hui-graph-base`'s `width / 5`). */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, Math.round(w / 5f))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        subscribe()
    }

    override fun onDetachedFromWindow() {
        ctx.main.removeCallbacks(redraw)
        unsubscribe?.invoke()
        unsubscribe = null
        stream = null
        super.onDetachedFromWindow()
    }

    /**
     * `_redrawGraph`, every minute (every hour past 24 hours shown): states older than the window go, the last of them
     * kept at the window's start, and the path follows the clock even when the entity stays quiet.
     */
    private val redraw: Runnable = object : Runnable {
        override fun run() {
            requestBuild(purgeFirst = true)
            ctx.main.postDelayed(this, if (hoursToShow > 24) HaGraph.HOUR_MS.toLong() else 60_000L)
        }
    }

    private fun subscribe() {
        val id = entityId ?: return
        if (unsubscribe != null) return
        ctx.main.removeCallbacks(redraw)
        ctx.main.postDelayed(redraw, if (hoursToShow > 24) HaGraph.HOUR_MS.toLong() else 60_000L)
        val s = HaGraph.Stream(hoursToShow)
        stream = s
        // Built again for each connection: a reconnect asks for the window as it is then, and the stream starts over.
        unsubscribe = ctx.subscribeWsFresh({
            HaGraph.historyStreamMessage(listOf(id), hoursToShow, ctx.now()) { ctx.store[it] != null }
        }, { ev, first ->
            ctx.main.post {
                if (stream !== s) return@post
                if (first) s.restart()
                if (error != null) {
                    // Recovered: the error and its description go with it.
                    error = null
                    contentDescription = null
                }
                val now = ctx.now()
                // No history: the current state alone (limitedHistoryFromStateObj).
                val current = ctx.store[id]?.let { st -> HistoryState(st.state, st.lastUpdated) }
                GraphWork.run(ctx.main, {
                    if (first) s.restart()
                    val states: List<HistoryState> = s.process(ev, now)[id].orEmpty()
                    if (states.isEmpty() && current != null) listOf(current) else states
                }) { states ->
                    if (stream !== s) return@run
                    history = states
                    requestBuild()
                }
            }
        }, { err ->
            // A rejected stream: HA's footer shows the error instead of a line.
            ctx.main.post {
                if (stream !== s) return@post
                stream = null
                unsubscribe = null
                history = null
                buildToken++
                builtFor = -1
                line = null
                fill = null
                val why = err?.optString("message")?.takeIf { it.isNotEmpty() } ?: err?.optString("code").orEmpty()
                error = HaText.ui("ui.components.history_charts.error").let { if (why.isEmpty()) it else "$it: $why" }
                contentDescription = error
                invalidate()
            }
        })
    }

    /** A path built for one width: the line and its fill, or [noHistory] when there was nothing to plot. */
    private class Built(val width: Int, val line: Path?, val fill: Path?, val noHistory: Boolean, val states: List<HistoryState>)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw) requestBuild()
    }

    /**
     * Builds the path for the history and width now on the graph thread ([purgeFirst]: after dropping the states the
     * window has moved past), and shows it when it's still the newest asked for.
     */
    private fun requestBuild(purgeFirst: Boolean = false) {
        val states = history ?: return
        val w = width
        if (w <= 0) return
        val token = ++buildToken
        val now = ctx.now()
        GraphWork.run(ctx.main, {
            val kept = if (purgeFirst && states.isNotEmpty()) purge(states, (now - hoursToShow * HaGraph.HOUR_MS) / 1000.0) else states
            build(kept, w, now)
        }) { built ->
            if (token != buildToken) return@run
            history = built.states
            builtFor = built.width
            line = built.line
            fill = built.fill
            noHistory = built.noHistory
            if (noHistory) contentDescription = NO_HISTORY else if (contentDescription == NO_HISTORY) contentDescription = null
            invalidate()
        }
    }

    /** The footer's path for [states] at [width] px and [nowMs], as HA's `hui-graph-base` draws it. Any thread. */
    private fun build(states: List<HistoryState>, width: Int, nowMs: Long): Built {
        val wCss = width / dp.toDouble()
        val hCss = wCss / 5.0
        // sample to 1 point per hour or 1 point per 5 pixels
        val maxDetails = maxOf(10.0, if (detail > 1) maxOf(wCss / 5, hoursToShow) else hoursToShow)
        val now = nowMs.toDouble()
        val coords = HaGraph.footerCoordinates(states, wCss, hCss, maxDetails, now - hoursToShow * HaGraph.HOUR_MS, now, minY, maxY, detail != 2)
        // A completed answer with no numbers in it: HA's "No state history found." (not loading, not an error).
        if (coords.isEmpty()) return Built(width, null, null, noHistory = true, states)
        val p = HaGraph.smoothPath(coords, dp) ?: return Built(width, null, null, noHistory = false, states)
        val fill = Path()
        fill.addPath(p)
        val lastX = (coords.last()[0] * dp).toFloat()
        val bottom = (hCss * dp).toFloat()
        fill.lineTo(lastX, bottom)
        fill.lineTo(0f, bottom)
        fill.close()
        return Built(width, p, fill, noHistory = false, states)
    }

    override fun onDraw(canvas: Canvas) {
        error?.let {
            // Cut to the footer's width (its start first, so "Unable to load history" stays); the description keeps it all.
            val fit = android.text.TextUtils.ellipsize(it, errorPaint, (width - 16f * dp).coerceAtLeast(0f), android.text.TextUtils.TruncateAt.END)
            canvas.drawText(fit, 0, fit.length, width / 2f, height / 2f - (errorPaint.ascent() + errorPaint.descent()) / 2f, errorPaint)
            return
        }
        // The path for another width is being built: nothing until it's there (a footer's width rarely changes).
        if (builtFor != width) return
        if (noHistory) {
            // `.info { position: absolute; top: calc(50% - 16px) }`, centered.
            canvas.drawText(NO_HISTORY, width / 2f, height / 2f - 16f * dp - infoPaint.ascent(), infoPaint)
            return
        }
        val p = line ?: return
        fill?.let { canvas.drawPath(it, fillPaint) }
        canvas.drawPath(p, linePaint)
    }

    companion object {
        /** `ui.components.history_charts.no_history_found`. */
        /** HA's "No state history found.", in the user's language. */
        val NO_HISTORY: String get() = HaText.ui("ui.components.history_charts.no_history_found")

        /** States from [cutoff] (seconds) on, the last earlier one moved to [cutoff] so the line starts at the window. */
        fun purge(states: List<HistoryState>, cutoff: Double): List<HistoryState> {
            val kept = states.filter { it.lu >= cutoff }
            if (kept.size == states.size) return states
            if (kept.isNotEmpty() && kept[0].lu == cutoff) return kept
            val last = states[states.size - kept.size - 1]
            return listOf(HistoryState(last.s, cutoff, last.a)) + kept
        }

        /** The footer config the sensor card builds (`hui-sensor-card`'s setConfig), or null for `graph: none`. */
        fun sensorFooter(raw: JSONObject): JSONObject? {
            if (raw.optString("graph") != "line") return null
            val footer = JSONObject().put("type", "graph").put("entity", raw.optString("entity"))
            val detail = raw.optInt("detail", 0)
            footer.put("detail", if (detail != 0) detail else 1)
            val hours = raw.optDouble("hours_to_show", Double.NaN)
            footer.put("hours_to_show", if (!hours.isNaN() && hours != 0.0) hours else 24.0)
            raw.optJSONObject("limits")?.let { footer.put("limits", it) }
            return footer
        }

    }
}
