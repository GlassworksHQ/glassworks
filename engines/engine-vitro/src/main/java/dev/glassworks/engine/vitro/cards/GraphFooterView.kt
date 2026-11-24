/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.cards

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
 * The path is built when the history or the width changes, never while drawing.
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

    private var stream: HaGraph.Stream? = null
    private var history: List<HistoryState>? = null
    private var unsubscribe: (() -> Unit)? = null
    private var line: Path? = null
    private val fill = Path()
    private var builtFor = -1

    init {
        color = ctx.theme.accentColor
        setOnClickListener { entityId?.let(ctx.openMoreInfo) }
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
        unsubscribe?.invoke()
        unsubscribe = null
        stream = null
        super.onDetachedFromWindow()
    }

    private fun subscribe() {
        val id = entityId ?: return
        if (unsubscribe != null) return
        val s = HaGraph.Stream(hoursToShow)
        stream = s
        val msg = HaGraph.historyStreamMessage(listOf(id), hoursToShow, ctx.now()) { ctx.store[it] != null }
        unsubscribe = ctx.subscribeWs(msg) { ev ->
            ctx.main.post {
                if (stream !== s) return@post
                val combined = s.process(ev, ctx.now())
                var states: List<HistoryState> = combined[id].orEmpty()
                // No history: the current state alone (limitedHistoryFromStateObj).
                if (states.isEmpty()) ctx.store[id]?.let { st -> states = listOf(HistoryState(st.state, st.lastUpdated)) }
                history = states
                builtFor = -1
                invalidate()
            }
        }
    }

    private fun rebuild() {
        builtFor = width
        line = null
        val states = history ?: return
        val wCss = width / dp.toDouble()
        val hCss = wCss / 5.0
        // sample to 1 point per hour or 1 point per 5 pixels
        val maxDetails = maxOf(10.0, if (detail > 1) maxOf(wCss / 5, hoursToShow) else hoursToShow)
        val now = ctx.now().toDouble()
        val coords = HaGraph.footerCoordinates(states, wCss, hCss, maxDetails, now - hoursToShow * HaGraph.HOUR_MS, now, minY, maxY, detail != 2)
        if (coords.isEmpty()) return
        val p = HaGraph.smoothPath(coords, dp) ?: return
        line = p
        fill.reset()
        fill.addPath(p)
        val lastX = (coords.last()[0] * dp).toFloat()
        val bottom = (hCss * dp).toFloat()
        fill.lineTo(lastX, bottom)
        fill.lineTo(0f, bottom)
        fill.close()
    }

    override fun onDraw(canvas: Canvas) {
        if (builtFor != width) rebuild()
        val p = line ?: return
        canvas.drawPath(fill, fillPaint)
        canvas.drawPath(p, linePaint)
    }

    companion object {
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
