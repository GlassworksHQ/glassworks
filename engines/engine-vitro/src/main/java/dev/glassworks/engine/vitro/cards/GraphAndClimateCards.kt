/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.cards

import dev.glassworks.engine.vitro.ui.HaWeatherSvg
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import dev.glassworks.engine.vitro.data.ForecastDay
import dev.glassworks.engine.vitro.data.HaDisplay
import dev.glassworks.engine.vitro.data.HistoryPoint
import dev.glassworks.engine.vitro.model.ActionConfig
import dev.glassworks.engine.vitro.model.CardConfig
import dev.glassworks.engine.vitro.ui.CardSurface
import dev.glassworks.engine.vitro.ui.MdiIconView
import dev.glassworks.engine.vitro.ui.MdiIcons
import dev.glassworks.engine.vitro.ui.VitroCardContext
import dev.glassworks.engine.vitro.ui.bindHoldAction
import dev.glassworks.ui.Inter
import dev.glassworks.engine.vitro.ui.VitroFonts
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** HA's `hold_action` for a card: the configured one, else `more-info` when there's an entity to show. */
private fun holdFor(raw: JSONObject, entityId: String?): ActionConfig =
    ActionConfig.parse(raw.optJSONObject("hold_action"), if (entityId != null) "more-info" else "none")

private fun spToPx(context: Context, sp: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, context.resources.displayMetrics)

private fun label(ctx: VitroCardContext, sp: Float, color: Int, weight: Int = Inter.REGULAR) =
    TextView(ctx.context).apply {
        textSize = sp
        setTextColor(color)
        typeface = VitroFonts.typeface(ctx.context, weight, sp)
        includeFontPadding = false
    }

/** Native Canvas sparkline / multi-series line graph for `sensor`, `history-graph`, and `custom:mini-graph-card`. */
class SensorGraphCardView(ctx: VitroCardContext, config: CardConfig) : CardSurface(ctx) {
    private val titleView = label(ctx, 14f, ctx.theme.secondaryTextColor, Inter.MEDIUM)
    private val valueView = label(ctx, 24f, ctx.theme.primaryTextColor, Inter.LIGHT)
    private val chart = SparklineCanvasView(ctx.context, ctx.theme.primaryColor, ctx.theme.accentColor, ctx.theme.secondaryTextColor)

    init {
        val col = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.px(14f), ctx.px(14f), ctx.px(14f), ctx.px(12f))
        }
        val header = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val icon = MdiIconView(ctx.context)
        header.addView(titleView, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        header.addView(icon, LinearLayout.LayoutParams(ctx.px(20f), ctx.px(20f)))
        col.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        col.addView(valueView, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ctx.px(4f) })
        val legend = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = GONE
        }
        col.addView(
            HorizontalScrollView(ctx.context).apply {
                isHorizontalScrollBarEnabled = false
                addView(legend, LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ctx.px(6f) },
        )
        col.addView(chart, LinearLayout.LayoutParams(MATCH_PARENT, ctx.px(72f)).apply { topMargin = ctx.px(8f) })
        addView(col, LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val entityIds = buildList {
            config.entity?.let(::add)
            config.entities().forEach { if (it.isEntity && it.entity !in this) add(it.entity) }
        }
        val primaryId = entityIds.firstOrNull()
        val hours = config.raw.optInt("hours_to_show", 24).coerceIn(1, 168)

        if (config.type == "history-graph") {
            titleView.text = config.title ?: "History"
            valueView.visibility = GONE
            icon.setIcon("mdi:gauge", ctx.theme.primaryColor)
            if (entityIds.size > 1) buildLegend(legend, entityIds)
        } else if (primaryId != null) {
            observe(primaryId) { s ->
                titleView.text = config.name ?: config.title ?: s?.friendlyName ?: primaryId
                valueView.text = s?.formattedState() ?: "…"
                icon.setIcon(MdiIcons.resolveIconName(config.icon, s), ctx.theme.primaryColor)
            }
        }

        if (entityIds.isNotEmpty()) {
            val showGraph = config.raw.optString("graph", "line") != "none"
            if (showGraph) {
                ctx.fetchHistory(entityIds, hours) { map ->
                    // One slot per entity, empty when it has too little history, so each keeps its legend color.
                    val series = entityIds.map { id -> map[id]?.takeIf { it.size >= 2 } ?: emptyList() }
                    ctx.main.post { chart.setSeries(if (series.all { it.isEmpty() }) emptyList() else series) }
                }
            } else {
                chart.visibility = GONE
            }
            isClickable = true
            setOnClickListener { ctx.performAction(ActionConfig.parse(config.raw.optJSONObject("tap_action")), primaryId) }
            bindHoldAction(holdFor(config.raw, primaryId), primaryId)
        }
    }

    /** HA's history-graph legend: a colored dot and name per series; tapping one opens its more-info. */
    private fun buildLegend(legend: LinearLayout, entityIds: List<String>) {
        legend.visibility = VISIBLE
        entityIds.forEachIndexed { idx, id ->
            val color = SparklineCanvasView.seriesColor(idx, ctx.theme.primaryColor, ctx.theme.accentColor)
            val item = LinearLayout(ctx.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, ctx.px(4f), ctx.px(12f), ctx.px(4f))
                isClickable = true
                setOnClickListener { ctx.performAction(ActionConfig("more-info"), id) }
            }
            item.addView(View(ctx.context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                }
            }, LinearLayout.LayoutParams(ctx.px(8f), ctx.px(8f)).apply { marginEnd = ctx.px(6f) })
            val name = label(ctx, 12f, ctx.theme.secondaryTextColor).apply { maxLines = 1 }
            item.addView(name)
            observe(id) { s -> name.text = s?.friendlyName ?: id }
            legend.addView(item)
        }
    }
}

private class SparklineCanvasView(
    context: Context,
    primaryColor: Int,
    secondaryColor: Int,
    private val labelColor: Int,
) : View(context) {
    private val dp = resources.displayMetrics.density
    private val colors = intArrayOf(primaryColor, secondaryColor, EXTRA_COLORS[0], EXTRA_COLORS[1])
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * dp
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = CardSurface.withAlpha(labelColor, 0.55f)
        textSize = 9f * dp
        textAlign = Paint.Align.LEFT
    }
    private val linePath = Path()
    private val fillPath = Path()
    private var series: List<List<HistoryPoint>> = emptyList()

    fun setSeries(data: List<List<HistoryPoint>>) {
        series = data
        invalidate()
    }

    private fun formatValue(v: Double): String =
        if (kotlin.math.abs(v) >= 100.0 || v == Math.rint(v)) String.format(Locale.US, "%.0f", v)
        else String.format(Locale.US, "%.1f", v)

    override fun onDraw(canvas: Canvas) {
        if (series.isEmpty() || width <= 4 || height <= 4) return
        var minV = Double.POSITIVE_INFINITY
        var maxV = Double.NEGATIVE_INFINITY
        for (s in series) {
            for (p in s) {
                if (p.value < minV) minV = p.value
                if (p.value > maxV) maxV = p.value
            }
        }
        val span = (maxV - minV).takeIf { it > 1e-6 } ?: 1.0
        val padY = 4f * dp
        val usableH = (height - padY * 2f).coerceAtLeast(1f)
        val w = width.toFloat()
        val h = height.toFloat()
        // A time axis shared by every series, up to now as HA draws it; sample order only when a time is missing.
        var tMin = Double.POSITIVE_INFINITY
        var tMax = Double.NEGATIVE_INFINITY
        var timed = true
        for (s in series) for (p in s) {
            if (p.epochSec <= 0.0) timed = false
            if (p.epochSec < tMin) tMin = p.epochSec
            if (p.epochSec > tMax) tMax = p.epochSec
        }
        tMax = maxOf(tMax, System.currentTimeMillis() / 1000.0)
        val tSpan = tMax - tMin
        if (tSpan <= 0.0) timed = false

        series.forEachIndexed { idx, pts ->
            if (pts.size < 2) return@forEachIndexed
            val color = colors[idx % colors.size]
            linePath.reset()
            fillPath.reset()
            val n = pts.size
            var prevX = 0f
            var prevY = 0f
            for (i in 0 until n) {
                val x = if (timed) (((pts[i].epochSec - tMin) / tSpan).toFloat() * w) else (i.toFloat() / (n - 1).toFloat()) * w
                val norm = ((pts[i].value - minV) / span).toFloat()
                val y = h - padY - norm * usableH
                if (i == 0) {
                    linePath.moveTo(x, y)
                    fillPath.moveTo(x, h)
                    fillPath.lineTo(x, y)
                } else {
                    // Smooth S-curve with horizontal tangents at each sample: no overshoot past min/max.
                    val midX = (prevX + x) / 2f
                    linePath.cubicTo(midX, prevY, midX, y, x, y)
                    fillPath.cubicTo(midX, prevY, midX, y, x, y)
                }
                prevX = x
                prevY = y
            }
            if (timed && prevX < w) {
                // The last state holds until now.
                linePath.lineTo(w, prevY)
                fillPath.lineTo(w, prevY)
            }
            if (idx == 0) {
                fillPath.lineTo(w, h)
                fillPath.close()
                fillPaint.shader = LinearGradient(
                    0f, 0f, 0f, h,
                    CardSurface.withAlpha(color, 0.28f),
                    Color.TRANSPARENT,
                    Shader.TileMode.CLAMP,
                )
                canvas.drawPath(fillPath, fillPaint)
            }
            linePaint.color = color
            canvas.drawPath(linePath, linePaint)
        }

        // Faint min/max labels in the left-hand corners.
        canvas.drawText(formatValue(maxV), 2f * dp, labelPaint.textSize, labelPaint)
        canvas.drawText(formatValue(minV), 2f * dp, h - 2f * dp, labelPaint)
    }

    companion object {
        private val EXTRA_COLORS = intArrayOf(0xFF4CAF50.toInt(), 0xFFE91E63.toInt())

        /** The line color of series [index], shared with the history-graph legend. */
        fun seriesColor(index: Int, primaryColor: Int, secondaryColor: Int): Int = when (index % 4) {
            0 -> primaryColor
            1 -> secondaryColor
            else -> EXTRA_COLORS[index % 4 - 2]
        }
    }
}

/** Native HA `gauge` card (`hui-gauge-card`): a thick, flat-ended half ring drawn on Canvas. */
class GaugeCardView(ctx: VitroCardContext, config: CardConfig) : CardSurface(ctx) {
    private val minVal = config.raw.optDouble("min", 0.0)
    private val maxVal = config.raw.optDouble("max", 100.0)
    private val dial = GaugeCanvasView(
        ctx.context,
        ctx.theme.primaryColor,
        if (ctx.theme.dark) GAUGE_TRACK_DARK else GAUGE_TRACK_LIGHT,
        ctx.theme.primaryTextColor,
    )
    private val nameView = label(ctx, 14f, ctx.theme.primaryTextColor).apply { gravity = Gravity.CENTER }
    private val severity = parseSeverity(config.raw.optJSONObject("severity"))

    init {
        val col = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ctx.px(12f), ctx.px(16f), ctx.px(12f), ctx.px(14f))
        }
        col.addView(dial, LinearLayout.LayoutParams(MATCH_PARENT, ctx.px(116f)))
        col.addView(nameView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ctx.px(8f) })
        addView(col, LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val eid = config.entity
        if (eid != null) {
            isClickable = true
            setOnClickListener { ctx.performAction(ActionConfig.parse(config.raw.optJSONObject("tap_action")), eid) }
            bindHoldAction(holdFor(config.raw, eid), eid)
            observe(eid) { s ->
                val v = s?.state?.toDoubleOrNull() ?: minVal
                val frac = ((v - minVal) / (maxVal - minVal).coerceAtLeast(1e-6)).toFloat().coerceIn(0f, 1f)
                val unit = config.raw.optString("unit", "").ifEmpty { s?.unit.orEmpty() }
                val arcColor = s?.state?.toDoubleOrNull()?.let { severityColor(severity, it) } ?: ctx.theme.primaryColor
                val rawVal = s?.state ?: "–"
                val formatted = when {
                    unit.isEmpty() -> rawVal
                    unit == "%" -> "$rawVal%"
                    else -> "$rawVal $unit"
                }
                dial.update(frac, formatted, arcColor)
                nameView.text = config.name ?: s?.friendlyName ?: eid
            }
        }
    }

    companion object {
        /** HA's gauge severity colors (`hui-gauge-card`). */
        const val SEVERITY_GREEN = 0xFF43A047.toInt()
        const val SEVERITY_YELLOW = 0xFFFFA600.toInt()
        const val SEVERITY_RED = 0xFFDB4437.toInt()
        private const val GAUGE_TRACK_LIGHT = 0xFFF0F0F0.toInt()
        private const val GAUGE_TRACK_DARK = 0xFF2C2C2C.toInt()

        /** Reads `severity: { green, yellow, red }` into `(threshold, color)` pairs, highest threshold first. */
        fun parseSeverity(raw: JSONObject?): List<Pair<Double, Int>> {
            if (raw == null) return emptyList()
            return listOf("green" to SEVERITY_GREEN, "yellow" to SEVERITY_YELLOW, "red" to SEVERITY_RED)
                .mapNotNull { (key, color) ->
                    if (!raw.has(key) || raw.isNull(key)) return@mapNotNull null
                    raw.optDouble(key).takeIf { !it.isNaN() }?.let { it to color }
                }
                .sortedByDescending { it.first }
        }

        /** HA's rule: the color of the highest threshold at or below [value], or null below every threshold. */
        fun severityColor(thresholds: List<Pair<Double, Int>>, value: Double): Int? =
            thresholds.firstOrNull { value >= it.first }?.second
    }
}

private class GaugeCanvasView(context: Context, private var activeColor: Int, private val trackColor: Int, textColor: Int) : View(context) {
    private val dp = resources.displayMetrics.density
    private val stroke = 22f * dp
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.BUTT
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textSize = spToPx(context, 34f)
        typeface = VitroFonts.typeface(context, Inter.REGULAR, 34f)
        textAlign = Paint.Align.CENTER
    }
    private val oval = RectF()
    private var fraction = 0f
    private var targetFraction = -1f
    private var animator: ValueAnimator? = null
    private var text = "–"

    /** `hui-gauge-card`'s needle sweep: the arc eases to [frac] over 240 ms on screen and snaps before attach. */
    fun update(frac: Float, label: String, color: Int = activeColor) {
        text = label
        activeColor = color
        val to = frac.coerceIn(0f, 1f)
        if (to == targetFraction) {
            invalidate()
            return
        }
        targetFraction = to
        animator?.cancel()
        val from = fraction
        animator = HaMotion.run(this, SWEEP_MS) { t ->
            fraction = HaMotion.lerp(from, to, t)
            invalidate()
        }
        invalidate()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        if (targetFraction >= 0f) fraction = targetFraction
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        // The flat ends sit on the bottom edge, so the ring's centre is the bottom of the view.
        val r = minOf(width / 2f - stroke / 2f - 2f * dp, height - stroke / 2f - 2f * dp).coerceAtLeast(24f)
        val cx = width / 2f
        val cy = height.toFloat()
        oval.set(cx - r, cy - r, cx + r, cy + r)
        arcPaint.color = trackColor
        canvas.drawArc(oval, 180f, 180f, false, arcPaint)
        arcPaint.color = activeColor
        if (fraction > 0f) canvas.drawArc(oval, 180f, 180f * fraction, false, arcPaint)
        // Shrink long values so they stay inside the inner radius.
        val inner = (r - stroke / 2f) * 1.7f
        val base = spToPx(context, 34f)
        textPaint.textSize = base
        val w = textPaint.measureText(text)
        if (w > inner && w > 0f) textPaint.textSize = base * inner / w
        canvas.drawText(text, cx, cy - 4f * dp, textPaint)
    }

    private companion object {
        const val SWEEP_MS = 240L
    }
}

private const val HEAT_COLOR = 0xFFFF6F22.toInt()
private const val HEAT_SOFT = 0xFFFFAD7A.toInt()
private const val COOL_COLOR = 0xFF2196F3.toInt()
private const val COOL_SOFT = 0xFF90CAF9.toInt()
private const val OFF_COLOR = 0xFF757575.toInt()

/** Native HA `thermostat` (`hui-thermostat-card`) + `custom:mushroom-climate-card`: a 270° dial and a mode bar. */
class ThermostatCardView(ctx: VitroCardContext, config: CardConfig) : CardSurface(ctx) {
    private val titleView = label(ctx, 16f, ctx.theme.primaryTextColor).apply {
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val dial = ThermostatDialView(ctx, temperatureUnit(ctx))
    private val modeBar = LinearLayout(ctx.context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val inset = ctx.px(4f)
        setPadding(inset, inset, inset, inset)
        background = GradientDrawable().apply {
            cornerRadius = 12f * ctx.dp
            setColor(if (ctx.theme.dark) MODE_BAR_DARK else MODE_BAR_LIGHT)
        }
    }
    private var modes: List<String> = emptyList()
    private val modeButtons = ArrayList<Pair<FrameLayout, MdiIconView>>()
    private var steppers: View? = null

    init {
        // hui-thermostat-card: a 48 px title row over the 262 px dial; the mode bar only when its feature is set
        // (Mushroom's climate card: when `hvac_modes` is).
        val showModes = if (config.type == "thermostat") {
            config.raw.optJSONArray("features")?.let { a -> (0 until a.length()).any { a.optJSONObject(it)?.optString("type") == "climate-hvac-modes" } } == true
        } else {
            config.raw.has("hvac_modes")
        }
        val col = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.px(12f), 0, ctx.px(12f), if (showModes) ctx.px(12f) else 0)
        }
        val eid = config.entity

        // Header: the name centred, a ⋮ button for more-info pinned to the right.
        val header = FrameLayout(ctx.context)
        header.addView(titleView, LayoutParams(MATCH_PARENT, ctx.px(48f)).apply {
            leftMargin = ctx.px(40f); rightMargin = ctx.px(40f)
        })
        val dots = FrameLayout(ctx.context).apply {
            isClickable = true
            applyHaRipple(this, 999f, ctx.theme.dark)
            contentDescription = "More info"
            addView(MdiIconView(ctx.context).apply { setIcon("mdi:dots-vertical", ctx.theme.primaryTextColor) },
                LayoutParams(ctx.px(20f), ctx.px(20f), Gravity.CENTER))
            setOnClickListener { eid?.let { ctx.performAction(ActionConfig("more-info"), it) } }
        }
        header.addView(dots, LayoutParams(ctx.px(48f), ctx.px(48f), Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = -ctx.px(11f) })
        col.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, ctx.px(48f)))

        // Dial, with the outlined − and + in the opening at its bottom.
        val dialFrame = FrameLayout(ctx.context)
        dialFrame.addView(dial, LayoutParams(MATCH_PARENT, ctx.px(262f)))
        val steppers = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun stepBtn(symbol: String, sign: Int) = label(ctx, 22f, ctx.theme.primaryTextColor).apply {
            text = symbol
            gravity = Gravity.CENTER
            contentDescription = if (sign > 0) "Raise target temperature" else "Lower target temperature"
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke(ctx.px(1f).coerceAtLeast(1), withAlpha(ctx.theme.secondaryTextColor, 0.5f))
            }
            isClickable = true
            applyHaRipple(this, 999f, ctx.theme.dark)
            setOnClickListener { dial.nudge(sign) }
        }
        val btn = ctx.px(42f)
        steppers.addView(stepBtn("−", -1), LinearLayout.LayoutParams(btn, btn).apply { marginEnd = ctx.px(16f) })
        steppers.addView(stepBtn("+", 1), LinearLayout.LayoutParams(btn, btn))
        dialFrame.addView(steppers, LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        col.addView(dialFrame, LinearLayout.LayoutParams(MATCH_PARENT, ctx.px(262f)))

        if (showModes) col.addView(modeBar, LinearLayout.LayoutParams(MATCH_PARENT, ctx.px(46f)).apply { topMargin = ctx.px(8f) })
        this.steppers = steppers
        addView(col, LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        if (eid != null) {
            bindHoldAction(holdFor(config.raw, eid), eid)
            dial.onCommit = { target ->
                ctx.callService("climate", "set_temperature", JSONObject().put("entity_id", eid).put("temperature", target), null)
            }
            observe(eid) { s ->
                titleView.text = config.name ?: s?.friendlyName ?: eid
                val attrs = s?.attributes.orEmpty()
                val mode = s?.state ?: "off"
                val action = attrs["hvac_action"] as? String
                dial.bind(
                    min = (attrs["min_temp"] as? Number)?.toDouble() ?: 7.0,
                    max = (attrs["max_temp"] as? Number)?.toDouble() ?: 35.0,
                    step = (attrs["target_temp_step"] as? Number)?.toDouble()?.takeIf { it > 0 } ?: 0.5,
                    current = (attrs["current_temperature"] as? Number)?.toDouble(),
                    target = (attrs["temperature"] as? Number)?.toDouble(),
                    mode = mode,
                    action = action,
                    stateLabel = s?.let { HaDisplay.stateLabel(it) } ?: mode.replace('_', ' ').replaceFirstChar { it.titlecase(Locale.ROOT) },
                )
                // HA shows the steppers only while there's a target to step.
                this.steppers?.visibility = if (attrs["temperature"] is Number) VISIBLE else GONE
                val list = (attrs["hvac_modes"] as? List<*>)?.mapNotNull { it?.toString() }?.takeIf { it.isNotEmpty() }
                    ?: listOf("off", "heat", "cool")
                renderModes(list, mode, eid)
            }
        }
    }

    /** HA's segmented hvac-mode bar: one icon per mode, the current one filled in its mode color. */
    private fun renderModes(list: List<String>, current: String, eid: String) {
        if (list != modes) {
            modes = list
            modeBar.removeAllViews()
            modeButtons.clear()
            for (m in list) {
                val icon = MdiIconView(ctx.context)
                val cell = FrameLayout(ctx.context).apply {
                    isClickable = true
                    applyHaRipple(this, 10f * ctx.dp, ctx.theme.dark)
                    contentDescription = m.replace('_', ' ')
                    addView(icon, LayoutParams(ctx.px(22f), ctx.px(22f), Gravity.CENTER))
                    setOnClickListener {
                        ctx.callService("climate", "set_hvac_mode", JSONObject().put("entity_id", eid).put("hvac_mode", m), null)
                    }
                }
                modeBar.addView(cell, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
                modeButtons += cell to icon
            }
        }
        list.forEachIndexed { i, m ->
            val (cell, icon) = modeButtons[i]
            val active = m == current
            cell.background = if (active) {
                GradientDrawable().apply {
                    cornerRadius = 10f * ctx.dp
                    setColor(modeColor(m))
                }
            } else {
                null
            }
            icon.setIcon(modeIcon(m), if (active) Color.WHITE else ctx.theme.primaryTextColor)
        }
    }

    private fun modeColor(mode: String): Int = when (mode) {
        "heat" -> HEAT_COLOR
        "cool" -> COOL_COLOR
        "off" -> OFF_COLOR
        else -> ctx.theme.primaryColor
    }

    companion object {
        private const val MODE_BAR_LIGHT = 0xFFF4F4F4.toInt()
        private const val MODE_BAR_DARK = 0xFF2A2A2A.toInt()

        /** HA's hvac-mode icons. */
        fun modeIcon(mode: String): String = when (mode) {
            "off" -> "mdi:power"
            "heat" -> "mdi:fire"
            "cool" -> "mdi:snowflake"
            "heat_cool", "auto" -> "mdi:thermostat"
            "dry" -> "mdi:water"
            "fan_only" -> "mdi:fan"
            else -> "mdi:thermostat"
        }

        /** `hass.config.unit_system.temperature`, else °C. */
        fun temperatureUnit(ctx: VitroCardContext): String =
            runCatching { ctx.haConfig().optJSONObject("unit_system")?.optString("temperature", "") }.getOrNull()
                ?.ifEmpty { null } ?: "°C"
    }
}

/**
 * The thermostat's 270° dial: a 14 dp track from 135°, a soft fill up to the lower of current and target, a solid
 * bridge between them with a dot at the current temperature and a white thumb at the target. Dragging along the
 * ring moves the target in `target_temp_step` steps and commits it on release. With [showCurrentInDial] off (the
 * More-Info sheet, which prints the current temperature above the dial) the thermometer row in the centre is left out.
 */
internal class ThermostatDialView(
    private val ctx: VitroCardContext,
    private val unit: String,
    private val showCurrentInDial: Boolean = true,
) : View(ctx.context) {
    private val dp = ctx.dp
    /** ha-control-circular-slider: a 24 / 320 ring at radius 145 / 320 of the dial's size. */
    private var strokeW = 14f * dp
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeW
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private fun textPaint(size: Float, weight: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = spToPx(ctx.context, size)
        typeface = VitroFonts.typeface(ctx.context, weight, size)
    }
    private val labelPaint = textPaint(14f, Inter.MEDIUM).apply { textAlign = Paint.Align.CENTER }
    private val bigPaint = textPaint(44f, Inter.REGULAR)
    private val unitPaint = textPaint(13f, Inter.REGULAR)
    private val decPaint = textPaint(20f, Inter.REGULAR)
    private val curPaint = textPaint(14f, Inter.MEDIUM)
    private val offPaint = textPaint(36f, Inter.REGULAR).apply { textAlign = Paint.Align.CENTER }
    private val offCurPaint = textPaint(16f, Inter.MEDIUM)

    /** The thermometer and the current temperature, centered on [centerY]. */
    private fun drawCurrent(canvas: Canvas, cur: Double, color: Int, paint: Paint, iconSize: Float, centerY: Float) {
        val curText = HaDisplay.formatValue(cur, 1).let { if ('.' in it) it else String.format(Locale.US, "%.1f", cur) } + " " + unit
        paint.color = color
        val textW = paint.measureText(curText)
        val gap = 4f * dp
        val left = cx - (iconSize + gap + textW) / 2f
        iconPath.set(MdiIcons.pathFor("mdi:thermometer"))
        iconMatrix.setScale(iconSize / 24f, iconSize / 24f)
        iconMatrix.postTranslate(left, centerY - iconSize / 2f)
        iconPath.transform(iconMatrix)
        fill.color = color
        canvas.drawPath(iconPath, fill)
        paint.textAlign = Paint.Align.LEFT
        val fm = paint.fontMetrics
        canvas.drawText(curText, left + iconSize + gap, centerY - (fm.ascent + fm.descent) / 2f, paint)
    }
    private val iconMatrix = Matrix()
    private val iconPath = Path()
    private val oval = RectF()

    private var min = 7.0
    private var max = 35.0
    private var step = 0.5
    private var current: Double? = null
    private var target: Double? = null

    /** The target as drawn on the ring; eases towards [target] when it changes from a nudge or from HA. */
    private var shownTarget: Double? = null
    private var targetAnimator: ValueAnimator? = null
    private var mode = "off"
    private var action: String? = null
    private var dragging = false
    private var glowKey = 0
    private var cx = 0f
    private var cy = 0f
    private var radius = 0f

    var onCommit: (Double) -> Unit = {}

    /** The target Home Assistant last reported, kept during a drag so a cancelled one goes back to it. */
    private var boundTarget: Double? = null

    /** The state as HA shows it big when there's no target ("Off"). */
    private var stateLabel = ""

    fun bind(min: Double, max: Double, step: Double, current: Double?, target: Double?, mode: String, action: String?, stateLabel: String = "") {
        this.stateLabel = stateLabel
        this.min = min
        this.max = if (max > min) max else min + 1.0
        this.step = step
        this.current = current
        boundTarget = target
        if (!dragging) setTarget(target)
        this.mode = mode
        this.action = action
        invalidate()
    }

    /** One `target_temp_step` up ([sign] > 0) or down, committed straight away. */
    fun nudge(sign: Int) {
        val t = target ?: return
        val next = snap(t + sign * step)
        if (next == t) return
        setTarget(next)
        invalidate()
        onCommit(next)
    }

    /** Sets the target and eases the thumb there over 200 ms on screen; snaps before attach or from nothing. */
    private fun setTarget(value: Double?) {
        if (value == target && (targetAnimator != null || shownTarget == value)) return
        target = value
        targetAnimator?.cancel()
        targetAnimator = null
        val from = shownTarget
        if (value == null || from == null || !isAttachedToWindow) {
            shownTarget = value
            return
        }
        targetAnimator = HaMotion.run(this, TARGET_MS) { t ->
            shownTarget = from + (value - from) * t
            invalidate()
        }
    }

    /** The target from a finger on the ring: follows it exactly, with no easing. */
    private fun dragTarget(value: Double) {
        targetAnimator?.cancel()
        targetAnimator = null
        target = value
        shownTarget = value
    }

    override fun onDetachedFromWindow() {
        targetAnimator?.cancel()
        targetAnimator = null
        shownTarget = target
        super.onDetachedFromWindow()
    }

    private fun snap(v: Double): Double {
        val snapped = min + Math.round((v - min) / step) * step
        return (Math.round(snapped.coerceIn(min, max) * 100.0) / 100.0)
    }

    /** The heat, cool or auto/heat_cool accent this thermostat uses, or null when off. */
    private fun accent(): Pair<Int, Int>? = when {
        action == "heating" || mode == "heat" -> HEAT_COLOR to HEAT_SOFT
        action == "cooling" || mode == "cool" -> COOL_COLOR to COOL_SOFT
        mode == "heat_cool" || mode == "auto" -> AUTO_COLOR to AUTO_SOFT
        else -> null
    }

    private fun angleOf(v: Double): Float = 135f + 270f * ((v - min) / (max - min)).toFloat().coerceIn(0f, 1f)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cx = w / 2f
        cy = h / 2f
        val size = minOf(w, h).toFloat()
        strokeW = size * 24f / 320f
        arcPaint.strokeWidth = strokeW
        radius = (size * 145f / 320f).coerceAtLeast(10f)
        glowKey = 0
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        oval.set(cx - radius, cy - radius, cx + radius, cy + radius)
        val accent = accent()
        val activeTint = when (action) {
            "heating" -> HEAT_COLOR
            "cooling" -> COOL_COLOR
            else -> null
        }

        // Soft radial glow while the HVAC is actually running.
        if (activeTint != null) {
            if (glowKey != activeTint) {
                glowKey = activeTint
                glowPaint.shader = RadialGradient(
                    cx, cy, radius,
                    CardSurface.withAlpha(activeTint, 0.12f), Color.TRANSPARENT, Shader.TileMode.CLAMP,
                )
            }
            canvas.drawCircle(cx, cy, radius, glowPaint)
        }

        val isAutoIdle = (mode == "heat_cool" || mode == "auto") && activeTint == null
        arcPaint.color = when {
            isAutoIdle -> AUTO_SOFT
            ctx.theme.dark -> TRACK_DARK
            else -> TRACK_LIGHT
        }
        canvas.drawArc(oval, 135f, 270f, false, arcPaint)

        val cur = current
        val tgt = shownTarget ?: target
        if (accent != null && tgt != null && !isAutoIdle) {
            val (solid, soft) = accent
            val low = if (cur != null) minOf(cur, tgt) else tgt
            val lowAngle = angleOf(low)
            if (lowAngle > 135f) {
                arcPaint.color = soft
                canvas.drawArc(oval, 135f, lowAngle - 135f, false, arcPaint)
            }
            if (cur != null && cur != tgt) {
                val a = angleOf(cur)
                val b = angleOf(tgt)
                arcPaint.color = solid
                canvas.drawArc(oval, minOf(a, b), kotlin.math.abs(b - a), false, arcPaint)
                fill.color = Color.WHITE
                pointAt(a).let { (x, y) -> canvas.drawCircle(x, y, 2.5f * dp, fill) }
            }
        } else if (isAutoIdle && cur != null) {
            fill.color = CardSurface.withAlpha(Color.WHITE, 0.7f)
            pointAt(angleOf(cur)).let { (x, y) -> canvas.drawCircle(x, y, 3f * dp, fill) }
        } else if (tgt == null && cur != null) {
            // With no target HA still marks the current temperature on the ring.
            fill.color = ctx.theme.secondaryTextColor
            pointAt(angleOf(cur)).let { (x, y) -> canvas.drawCircle(x, y, 4f * dp, fill) }
        }
        if (tgt != null) {
            val (x, y) = pointAt(angleOf(tgt))
            fill.color = accent?.first ?: ctx.theme.secondaryTextColor
            canvas.drawCircle(x, y, 6.5f * dp, fill)
            fill.color = Color.WHITE
            canvas.drawCircle(x, y, 5f * dp, fill)
        }

        // Centre: action label, split target temperature, current temperature.
        if (target == null) {
            // ha-state-control-climate-temperature with no target: the state big (36 px) in the middle, the current
            // temperature (16 px / 500, primary color) 49 px under it, no label above.
            offPaint.color = ctx.theme.primaryTextColor
            val fm = offPaint.fontMetrics
            canvas.drawText(stateLabel, cx, cy - (fm.ascent + fm.descent) / 2f - 1.6f * dp, offPaint)
            if (cur != null && showCurrentInDial) drawCurrent(canvas, cur, ctx.theme.primaryTextColor, offCurPaint, 16f * dp, cy + 48.8f * dp)
            return
        }
        val labelText = (action ?: mode).replace('_', ' ').replaceFirstChar { it.titlecase(Locale.ROOT) }
        labelPaint.color = activeTint ?: ctx.theme.secondaryTextColor
        canvas.drawText(labelText, cx, cy - 34f * dp, labelPaint)

        val baseline = cy + 16f * dp
        bigPaint.color = ctx.theme.primaryTextColor
        unitPaint.color = ctx.theme.primaryTextColor
        decPaint.color = ctx.theme.primaryTextColor
        val shownValue = target
        if (shownValue == null) {
            bigPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("–", cx, baseline, bigPaint)
        } else {
            bigPaint.textAlign = Paint.Align.LEFT
            val (whole, dec) = splitTemp(shownValue, step)
            val wholeW = bigPaint.measureText(whole)
            val sideW = maxOf(unitPaint.measureText(unit), if (dec.isEmpty()) 0f else decPaint.measureText(dec))
            val gap = 2f * dp
            val x0 = cx - (wholeW + gap + sideW) / 2f
            canvas.drawText(whole, x0, baseline, bigPaint)
            val sideX = x0 + wholeW + gap
            canvas.drawText(unit, sideX, baseline - 20f * dp, unitPaint)
            if (dec.isNotEmpty()) canvas.drawText(dec, sideX, baseline, decPaint)
        }

        if (cur != null && showCurrentInDial) {
            val curText = String.format(Locale.US, "%.1f %s", cur, unit)
            val curColor = if (action == "heating") HEAT_COLOR else if (action == "cooling") COOL_COLOR else ctx.theme.secondaryTextColor
            curPaint.color = curColor
            val iconSize = 14f * dp
            val textW = curPaint.measureText(curText)
            val total = iconSize + 4f * dp + textW
            val left = cx - total / 2f
            val rowBase = cy + 46f * dp
            iconPath.set(MdiIcons.pathFor("mdi:thermometer"))
            iconMatrix.setScale(iconSize / 24f, iconSize / 24f)
            iconMatrix.postTranslate(left, rowBase - iconSize + 2f * dp)
            iconPath.transform(iconMatrix)
            fill.color = curColor
            canvas.drawPath(iconPath, fill)
            curPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(curText, left + iconSize + 4f * dp, rowBase, curPaint)
        }
    }

    private fun pointAt(angleDeg: Float): Pair<Float, Float> {
        val rad = Math.toRadians(angleDeg.toDouble())
        return (cx + radius * cos(rad)).toFloat() to (cy + radius * sin(rad)).toFloat()
    }

    private fun valueAt(x: Float, y: Float): Double {
        var deg = Math.toDegrees(atan2((y - cy).toDouble(), (x - cx).toDouble()))
        if (deg < 0) deg += 360.0
        var rel = (deg - 135.0 + 360.0) % 360.0
        // In the bottom opening, snap to the nearer end.
        if (rel > 270.0) rel = if (rel > 315.0) 0.0 else 270.0
        return snap(min + rel / 270.0 * (max - min))
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (target == null) return false
                val d = hypot((event.x - cx).toDouble(), (event.y - cy).toDouble())
                if (d < radius - 32f * dp || d > radius + 24f * dp) return false
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                dragTarget(valueAt(event.x, event.y))
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                dragTarget(valueAt(event.x, event.y))
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) return false
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                dragTarget(valueAt(event.x, event.y))
                invalidate()
                target?.let(onCommit)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    // Nothing was sent: drop the preview and show the target Home Assistant has.
                    setTarget(boundTarget)
                    invalidate()
                }
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    companion object {
        private const val TRACK_LIGHT = 0xFFE8E8E8.toInt()
        private const val TRACK_DARK = 0xFF333333.toInt()
        private const val AUTO_COLOR = 0xFFFFC107.toInt()
        private const val AUTO_SOFT = 0xFFFFE082.toInt()
        private const val TARGET_MS = 200L

        /** `21.5` → `("21", ".5")`; whole steps drop the decimal. */
        fun splitTemp(v: Double, step: Double): Pair<String, String> {
            if (step >= 1.0) return String.format(Locale.US, "%.0f", v) to ""
            val text = String.format(Locale.US, "%.1f", v)
            return text.substringBefore('.') to "." + text.substringAfter('.')
        }
    }
}

/** Native HA `weather-forecast` (`hui-weather-forecast-card`) + `custom:clock-weather-card`. */
class WeatherCardView(ctx: VitroCardContext, config: CardConfig) : CardSurface(ctx) {
    private val forecastRow = LinearLayout(ctx.context).apply { orientation = LinearLayout.HORIZONTAL }
    private var forecastFromSocket = false

    init {
        val col = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.px(16f), ctx.px(16f), ctx.px(16f), ctx.px(14f))
        }
        val top = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val icon = WeatherIconView(ctx.context).apply { themeVars = ctx.theme.vars; fallbackColor = ctx.theme.primaryTextColor }
        val condition = label(ctx, 22f, ctx.theme.primaryTextColor).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val name = label(ctx, 13f, ctx.theme.secondaryTextColor).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val temp = label(ctx, 22f, ctx.theme.primaryTextColor).apply { gravity = Gravity.END }
        val metric = label(ctx, 12f, ctx.theme.secondaryTextColor).apply { gravity = Gravity.END }
        top.addView(icon, LinearLayout.LayoutParams(ctx.px(52f), ctx.px(52f)).apply { marginEnd = ctx.px(12f) })
        top.addView(LinearLayout(ctx.context).apply {
            orientation = LinearLayout.VERTICAL
            addView(condition)
            addView(name, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ctx.px(4f) })
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        top.addView(LinearLayout(ctx.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            addView(temp, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(metric, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ctx.px(4f) })
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = ctx.px(8f) })
        col.addView(top, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val showForecast = config.raw.optBoolean("show_forecast", true)
        if (showForecast) {
            col.addView(forecastRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ctx.px(16f) })
        }
        addView(col, LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val eid = config.entity
        if (eid != null) {
            isClickable = true
            setOnClickListener { ctx.performAction(ActionConfig.parse(config.raw.optJSONObject("tap_action"), "more-info"), eid) }
            bindHoldAction(holdFor(config.raw, eid), eid)
            observe(eid) { s ->
                val state = s?.state.orEmpty()
                icon.setCondition(state)
                condition.text = conditionLabel(state)
                name.text = config.name ?: s?.friendlyName ?: eid
                val t = s?.attributes?.get("temperature")
                val u = (s?.attributes?.get("temperature_unit") as? String) ?: "°C"
                temp.text = if (t != null) "$t $u" else "–"
                val hum = s?.attributes?.get("humidity")
                val precip = s?.attributes?.get("precipitation")
                val precipUnit = (s?.attributes?.get("precipitation_unit") as? String).orEmpty()
                metric.text = when {
                    precip != null -> "$precip $precipUnit".trim()
                    hum != null -> "$hum%"
                    else -> ""
                }
                metric.visibility = if (metric.text.isNullOrEmpty()) GONE else VISIBLE
                // Older integrations still carry `forecast` as an attribute: show it until the socket answers.
                if (showForecast && !forecastFromSocket) {
                    val legacy = (s?.attributes?.get("forecast") as? List<*>)?.mapNotNull { item ->
                        (item as? Map<*, *>)?.let { m -> ForecastDay.parse(JSONObject(m.mapKeys { it.key.toString() })) }
                    }.orEmpty()
                    if (legacy.isNotEmpty()) renderForecast(legacy, u)
                }
            }
            if (showForecast) {
                val type = config.raw.optString("forecast_type", "daily").ifEmpty { "daily" }
                ctx.fetchForecast(eid, type) { days ->
                    ctx.main.post {
                        if (days.isEmpty()) return@post
                        forecastFromSocket = true
                        val u = (ctx.store[eid]?.attributes?.get("temperature_unit") as? String) ?: "°C"
                        renderForecast(days, u, hourly = type == "hourly")
                    }
                }
            }
        }
    }

    /** [hourly] comes from the `forecast_type` asked for, as HA decides; only the legacy attribute has to guess. */
    private fun renderForecast(days: List<ForecastDay>, unit: String, hourly: Boolean = days.size >= 2 && sameDay(days[0].datetime, days[1].datetime)) {
        forecastRow.removeAllViews()
        for (day in days.take(5)) {
            val cell = LinearLayout(ctx.context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            cell.addView(label(ctx, 12f, ctx.theme.secondaryTextColor).apply { text = forecastLabel(day.datetime, hourly) },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            cell.addView(WeatherIconView(ctx.context).apply { themeVars = ctx.theme.vars; fallbackColor = ctx.theme.primaryTextColor; setCondition(day.condition.orEmpty()) },
                LinearLayout.LayoutParams(ctx.px(32f), ctx.px(32f)).apply { topMargin = ctx.px(6f); bottomMargin = ctx.px(6f) })
            cell.addView(label(ctx, 13f, ctx.theme.primaryTextColor, Inter.MEDIUM).apply {
                text = day.temperature?.let { formatDegrees(it) } ?: "–"
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            day.templow?.let { low ->
                cell.addView(label(ctx, 12f, ctx.theme.secondaryTextColor).apply { text = formatDegrees(low) },
                    LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ctx.px(3f) })
            }
            forecastRow.addView(cell, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        forecastRow.contentDescription = "Forecast in $unit"
    }

    companion object {
        /** HA's English condition names. */
        fun conditionLabel(state: String): String = when (state) {
            "clear-night" -> "Clear, night"
            "partlycloudy" -> "Partly cloudy"
            "snowy-rainy" -> "Snowy, rainy"
            "lightning-rainy" -> "Lightning, rainy"
            "windy-variant" -> "Windy"
            "exceptional" -> "Exceptional"
            "" -> "Weather"
            else -> state.replace('-', ' ').replace('_', ' ').replaceFirstChar { it.titlecase(Locale.ROOT) }
        }

        private fun formatDegrees(v: Double): String = String.format(Locale.US, "%.0f°", v)

        private fun parseIso(iso: String): Date? = runCatching {
            val clean = iso.replace(Regex("\\.\\d+"), "").replace("Z", "+00:00")
            val fixed = if (clean.length > 19 && (clean[19] == '+' || clean[19] == '-')) clean.substring(0, 22) + clean.substring(23) else clean
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).parse(fixed)
        }.getOrNull()

        private fun sameDay(a: String, b: String): Boolean = a.length >= 10 && b.length >= 10 && a.substring(0, 10) == b.substring(0, 10)

        /** `Wed` for a daily forecast, `14:00` for an hourly one. */
        fun forecastLabel(iso: String, hourly: Boolean): String {
            val date = parseIso(iso) ?: return iso.take(10)
            return SimpleDateFormat(if (hourly) "HH:mm" else "EEE", Locale.getDefault()).format(date)
        }
    }
}

/**
 * HA's weather picture for a condition ([HaWeatherSvg], from the frontend's `getWeatherStateSVG`), or for a condition
 * HA has no picture for ("exceptional"), its MDI glyph in [fallbackColor], as HA's `getWeatherStateIcon` does.
 */
class WeatherIconView(context: Context) : View(context) {
    private var condition = ""
    private var night = false

    /** Theme variables (no `--`), for HA's `--weather-icon-*-color` overrides. */
    var themeVars: Map<String, String> = emptyMap()
    var fallbackColor: Int = 0xFFE1E1E1.toInt()
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyphPath = Path()
    private val glyphMatrix = Matrix()

    fun setCondition(value: String, nightTime: Boolean = false) {
        if (value == condition && nightTime == night) return
        condition = value
        night = nightTime
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val size = minOf(width, height).toFloat()
        if (size <= 0f) return
        canvas.save()
        canvas.translate((width - size) / 2f, (height - size) / 2f)
        val svg = HaWeatherSvg.forCondition(condition, night, themeVars)
        if (svg != null) {
            svg.draw(canvas, size, size, context)
        } else if (condition.isNotEmpty()) {
            glyphMatrix.setScale(size / 24f, size / 24f)
            glyphPath.set(MdiIcons.pathFor(MdiIcons.weatherIcon(condition).removePrefix("mdi:")))
            glyphPath.transform(glyphMatrix)
            glyph.color = fallbackColor
            canvas.drawPath(glyphPath, glyph)
        }
        canvas.restore()
    }
}

/** A CSS color, or `[r, g, b]` as modern-circular-gauge and bar-card accept, or null. */
private fun configColor(raw: Any?): Int? = when (raw) {
    is String -> raw.takeIf { it.isNotBlank() }?.let { MarkdownInline.parseCssColor(it) }
    is org.json.JSONArray -> if (raw.length() >= 3) {
        Color.rgb(raw.optInt(0).coerceIn(0, 255), raw.optInt(1).coerceIn(0, 255), raw.optInt(2).coerceIn(0, 255))
    } else {
        null
    }
    else -> null
}

/** A number from config, or [fallback] when it's missing or not numeric (a template / entity id). */
private fun configNumber(raw: JSONObject, key: String, fallback: Double): Double {
    val v = raw.opt(key) ?: return fallback
    return when (v) {
        is Number -> v.toDouble()
        is String -> v.trim().toDoubleOrNull() ?: fallback
        else -> fallback
    }.takeIf { !it.isNaN() && !it.isInfinite() } ?: fallback
}

/** `12.0` → `12`, `12.345` → `12.3`; [decimals] pins the digits when set. */
private fun formatNumber(v: Double, decimals: Int? = null): String = when {
    decimals != null -> String.format(Locale.getDefault(), "%.${decimals.coerceIn(0, 6)}f", v)
    v == Math.rint(v) && kotlin.math.abs(v) < 1e12 -> v.toLong().toString()
    kotlin.math.abs(v) >= 100.0 -> String.format(Locale.getDefault(), "%.0f", v)
    else -> String.format(Locale.getDefault(), "%.1f", v)
}

/**
 * Native `custom:modern-circular-gauge` (Tier 2): a round-capped Canvas ring (`gauge_type` `standard` = 270°,
 * `half` = 180°, `full` = 360°) whose arc eases to the state over 240 ms, colored by `segments`, with the value,
 * unit, an optional `secondary` line and the name in its middle.
 */
class ModernCircularGaugeCardView(ctx: VitroCardContext, private val config: CardConfig) : CardSurface(ctx) {
    /** One `segments` entry: from [from] up, the arc is [color]. */
    data class Segment(val from: Double, val color: Int)

    val gaugeType: String = config.raw.optString("gauge_type", "standard").lowercase(Locale.ROOT).let {
        if (it in setOf("standard", "half", "full")) it else "standard"
    }
    val minVal: Double = configNumber(config.raw, "min", 0.0)
    val maxVal: Double = configNumber(config.raw, "max", 100.0)
    val segments: List<Segment> = parseSegments(config.raw.optJSONArray("segments"))
    private val smooth = config.raw.optBoolean("smooth_segments", false)
    private val startFromZero = config.raw.optBoolean("start_from_zero", false)
    private val showState = config.raw.optBoolean("show_state", true)
    private val showName = config.raw.optBoolean("show_name", true)
    private val showIcon = config.raw.optBoolean("show_icon", true)
    private val decimals: Int? = config.raw.opt("decimals")?.let { (it as? Number)?.toInt() ?: it.toString().toIntOrNull() }

    /** `secondary` as an entity id plus its own unit and show flag, or null. */
    private val secondaryEntity: String?
    private val secondaryUnit: String?
    private val secondaryShowState: Boolean

    internal val ring = GaugeRingView(
        ctx.context,
        trackColor = ctx.theme.dividerColor,
        textColor = ctx.theme.primaryTextColor,
        secondaryColor = ctx.theme.secondaryTextColor,
        sweep = sweepFor(gaugeType),
        fontScale = ctx.theme.fontScale,
    )
    internal val nameView = label(ctx, ctx.scaledSp(14f), ctx.theme.primaryTextColor, Inter.MEDIUM).apply {
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    init {
        val sec = config.raw.opt("secondary")
        when (sec) {
            is JSONObject -> {
                secondaryEntity = sec.optString("entity", "").ifEmpty { null }
                secondaryUnit = sec.optString("unit", "").ifEmpty { null }
                secondaryShowState = sec.optBoolean("show_state", true)
            }
            is String -> {
                secondaryEntity = sec.takeIf { '.' in it && !isTemplate(it) }
                secondaryUnit = null
                secondaryShowState = true
            }
            else -> {
                secondaryEntity = config.raw.optString("secondary_entity", "").ifEmpty { null }
                secondaryUnit = null
                secondaryShowState = true
            }
        }

        val col = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ctx.px(12f), ctx.px(14f), ctx.px(12f), ctx.px(12f))
        }
        val ringHeight = if (gaugeType == "half") 92f else 136f
        col.addView(ring, LinearLayout.LayoutParams(MATCH_PARENT, ctx.px(ringHeight)))
        if (showName) col.addView(nameView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ctx.px(6f) })
        addView(col, LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val eid = config.entity
        if (eid != null) {
            isClickable = true
            enablePressFeedback()
            setOnClickListener { ctx.performAction(ActionConfig.parse(config.raw.optJSONObject("tap_action")), eid) }
            bindHoldAction(holdFor(config.raw, eid), eid)
            observe(eid) { s -> bindPrimary(eid, s) }
        }
        secondaryEntity?.let { sid ->
            observe(sid) { s ->
                if (!secondaryShowState || s == null) {
                    ring.setSecondary(null)
                } else {
                    val num = s.state.toDoubleOrNull()
                    val unit = secondaryUnit ?: s.unit.orEmpty()
                    val suffix = if (unit.isEmpty()) "" else " $unit"
                    ring.setSecondary(if (num != null) formatNumber(num, decimals) + suffix else s.formattedState())
                }
            }
        }
    }

    private fun bindPrimary(eid: String, s: dev.glassworks.engine.vitro.data.EntityState?) {
        val v = s?.state?.toDoubleOrNull()
        val span = (maxVal - minVal).takeIf { kotlin.math.abs(it) > 1e-9 } ?: 1.0
        fun frac(x: Double) = ((x - minVal) / span).toFloat().coerceIn(0f, 1f)
        val to = if (v != null) frac(v) else 0f
        val from = if (startFromZero) frac(0.0) else 0f
        val color = v?.let { segmentColor(segments, it, smooth) } ?: ctx.theme.primaryColor
        val unit = config.raw.optString("unit", "").ifEmpty { s?.unit.orEmpty() }
        val valueText = when {
            !showState -> ""
            v != null -> formatNumber(v, decimals)
            s == null -> "–"
            else -> s.formattedState()
        }
        val icon = if (showIcon && (config.icon != null || s != null)) MdiIcons.resolveIconName(config.icon, s) else null
        ring.update(from, to, color, valueText, if (v != null && showState) unit else "", icon)
        nameView.text = config.name ?: s?.friendlyName ?: eid
    }

    companion object {
        fun sweepFor(type: String): Float = when (type) {
            "full" -> 360f
            "half" -> 180f
            else -> 270f
        }

        fun parseSegments(arr: org.json.JSONArray?): List<Segment> {
            if (arr == null) return emptyList()
            val out = ArrayList<Segment>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val from = configNumber(o, "from", Double.NaN).takeIf { !it.isNaN() } ?: continue
                val color = configColor(o.opt("color")) ?: continue
                out += Segment(from, color)
            }
            return out.sortedBy { it.from }
        }

        /**
         * The arc color at [value]: the last segment at or below it, or, with [smooth], a blend between the two
         * segments around it. Below every segment the first one's color holds; no segments gives null.
         */
        fun segmentColor(segments: List<Segment>, value: Double, smooth: Boolean = false): Int? {
            if (segments.isEmpty()) return null
            val idx = segments.indexOfLast { value >= it.from }
            if (idx < 0) return segments.first().color
            val cur = segments[idx]
            if (!smooth || idx == segments.lastIndex) return cur.color
            val next = segments[idx + 1]
            val t = ((value - cur.from) / (next.from - cur.from).coerceAtLeast(1e-9)).toFloat().coerceIn(0f, 1f)
            return HaMotion.lerpColor(cur.color, next.color, t)
        }
    }
}

/** The ring of [ModernCircularGaugeCardView]: track, round-capped active arc and centered labels. */
internal class GaugeRingView(
    context: Context,
    private val trackColor: Int,
    textColor: Int,
    secondaryColor: Int,
    val sweep: Float,
    private val fontScale: Float,
) : View(context) {
    private val dp = resources.displayMetrics.density
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        typeface = VitroFonts.typeface(context, Inter.MEDIUM, 32f)
        textAlign = Paint.Align.LEFT
        fontFeatureSettings = "tnum"
    }
    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = secondaryColor
        typeface = VitroFonts.typeface(context, Inter.REGULAR, 14f)
        textAlign = Paint.Align.LEFT
    }
    private val secondaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = secondaryColor
        typeface = VitroFonts.typeface(context, Inter.REGULAR, 12f)
        textAlign = Paint.Align.CENTER
        fontFeatureSettings = "tnum"
    }
    private val iconColor = secondaryColor
    private val oval = RectF()
    private var startFrac = 0f
    private var fraction = 0f
    private var animator: ValueAnimator? = null
    private var icon: String? = null
    private var iconDrawable: MdiIconDrawable? = null

    /** The arc's end as 0..1 of the sweep, once any animation settles. */
    var targetFraction = -1f
        private set
    var activeColor: Int = 0
        private set
    var valueText: String = "–"
        private set
    var unitText: String = ""
        private set
    var secondaryText: String? = null
        private set

    fun update(from: Float, to: Float, color: Int, value: String, unit: String, iconName: String?) {
        startFrac = from.coerceIn(0f, 1f)
        activeColor = color
        valueText = value
        unitText = unit
        if (iconName != icon) {
            icon = iconName
            iconDrawable = null
        }
        val target = to.coerceIn(0f, 1f)
        if (target == targetFraction) {
            invalidate()
            return
        }
        targetFraction = target
        animator?.cancel()
        val start = fraction
        animator = HaMotion.run(this, SWEEP_MS) { t ->
            fraction = HaMotion.lerp(start, target, t)
            invalidate()
        }
        invalidate()
    }

    fun setSecondary(text: String?) {
        secondaryText = text
        invalidate()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        if (targetFraction >= 0f) fraction = targetFraction
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        val half = sweep <= 180f
        // A half ring sits on the bottom edge; the others are centered (a 270° arc drops a little for balance).
        val maxR = if (half) minOf(width / 2f, height.toFloat()) else minOf(width, height) / 2f
        val stroke = (maxR * 0.13f).coerceIn(6f * dp, 16f * dp)
        val r = (maxR - stroke / 2f - 2f * dp).coerceAtLeast(12f * dp)
        val cx = width / 2f
        val cy = when {
            half -> height - stroke / 2f - 2f * dp
            sweep < 360f -> height / 2f + r * 0.08f
            else -> height / 2f
        }
        oval.set(cx - r, cy - r, cx + r, cy + r)
        val startAngle = when {
            sweep >= 360f -> 270f
            half -> 180f
            else -> 135f
        }
        arcPaint.strokeWidth = stroke
        arcPaint.color = trackColor
        canvas.drawArc(oval, startAngle, sweep, false, arcPaint)
        val lo = minOf(startFrac, fraction)
        val hi = maxOf(startFrac, fraction)
        if (hi - lo > 0.0005f) {
            arcPaint.color = activeColor
            canvas.drawArc(oval, startAngle + sweep * lo, sweep * (hi - lo), false, arcPaint)
        }

        val inner = (r - stroke) * if (half) 1.6f else 1.5f
        val baseValue = (r * if (half) 0.5f else 0.42f).coerceAtMost(40f * dp * fontScale)
        valuePaint.textSize = baseValue
        unitPaint.textSize = baseValue * 0.42f
        var vw = valuePaint.measureText(valueText)
        var uw = if (unitText.isEmpty()) 0f else unitPaint.measureText(unitText) + 3f * dp
        if (vw + uw > inner && vw + uw > 0f) {
            val k = inner / (vw + uw)
            valuePaint.textSize *= k
            unitPaint.textSize *= k
            vw *= k
            uw *= k
        }
        val baseline = if (half) cy - 4f * dp else cy + valuePaint.textSize * 0.34f
        val x0 = cx - (vw + uw) / 2f
        canvas.drawText(valueText, x0, baseline, valuePaint)
        if (unitText.isNotEmpty()) {
            canvas.drawText(unitText, x0 + vw + 3f * dp, baseline - valuePaint.textSize * 0.38f, unitPaint)
        }
        secondaryText?.takeIf { it.isNotEmpty() }?.let { sec ->
            secondaryPaint.textSize = (baseValue * 0.36f).coerceAtLeast(10f * dp)
            val y = if (half) baseline - valuePaint.textSize - 2f * dp else baseline + secondaryPaint.textSize + 6f * dp
            canvas.drawText(sec, cx, y, secondaryPaint)
        }
        icon?.let { name ->
            if (half) return@let
            val size = (r * 0.3f).toInt().coerceIn((14f * dp).toInt(), (28f * dp).toInt())
            val d = iconDrawable ?: MdiIconDrawable(name, iconColor, size).also { iconDrawable = it }
            val top = (baseline - valuePaint.textSize - size - 4f * dp).toInt()
            d.setBounds((cx - size / 2f).toInt(), top, (cx + size / 2f).toInt(), top + size)
            d.draw(canvas)
        }
    }

    private companion object {
        const val SWEEP_MS = 240L
    }
}

/**
 * Native `custom:bar-card` (Tier 2): one rounded horizontal bar per entity (`entity` or `entities`), its fill easing
 * to the state, colored by `severity` ranges or `color`, with the icon outside and the name and value inside by
 * default (`positions: { icon, name, value }` take `inside`, `outside` or `off`).
 */
class BarCardView(ctx: VitroCardContext, private val config: CardConfig) : CardSurface(ctx) {
    /** One `severity` range: values in [from]..[to] draw in [color]. */
    data class Severity(val from: Double, val to: Double, val color: Int)

    /** One bar's settings, card-level values filled in. */
    data class BarSpec(
        val entity: String,
        val name: String?,
        val icon: String?,
        val color: Int?,
        val min: Double,
        val max: Double,
        val unit: String?,
        val severity: List<Severity>,
        val tapAction: JSONObject?,
        val holdAction: JSONObject?,
    )

    /** One rendered bar, for live updates (and tests). */
    internal class Row(val spec: BarSpec, val fill: BarFillView, val nameView: TextView, val valueView: TextView, val iconView: MdiIconView)

    val specs: List<BarSpec> = parseSpecs(config.raw)
    private val heightDp = configNumber(config.raw, "height", 36.0).toFloat().coerceIn(8f, 120f)
    private val positions = config.raw.optJSONObject("positions")
    private val iconPos = positions?.optString("icon", "")?.ifEmpty { null } ?: "outside"
    private val namePos = positions?.optString("name", "")?.ifEmpty { null } ?: "inside"
    private val valuePos = positions?.optString("value", "")?.ifEmpty { null } ?: "inside"
    private val decimals: Int? = config.raw.opt("decimal")?.let { (it as? Number)?.toInt() ?: it.toString().toIntOrNull() }
    internal val rows = ArrayList<Row>()

    init {
        val col = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.px(14f), ctx.px(12f), ctx.px(14f), ctx.px(12f))
        }
        config.title?.let { t ->
            col.addView(label(ctx, ctx.scaledSp(16f), ctx.theme.primaryTextColor, Inter.MEDIUM).apply { text = t },
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = ctx.px(10f) })
        }
        specs.forEachIndexed { i, spec ->
            col.addView(buildRow(spec), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                if (i > 0) topMargin = ctx.px(8f)
            })
        }
        addView(col, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    private fun buildRow(spec: BarSpec): View {
        val barColor = spec.color ?: ctx.theme.primaryColor
        val row = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setOnClickListener {
                ctx.performAction(ActionConfig.parse(spec.tapAction ?: config.raw.optJSONObject("tap_action")), spec.entity)
            }
            val hold = spec.holdAction ?: config.raw.optJSONObject("hold_action")
            bindHoldAction(ctx, ActionConfig.parse(hold, "more-info"), spec.entity)
        }
        val textSp = ctx.scaledSp(if (heightDp < 26f) 12f else 14f)
        val iconView = MdiIconView(ctx.context)
        val nameView = label(ctx, textSp, ctx.theme.primaryTextColor, Inter.MEDIUM).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val valueView = label(ctx, textSp, ctx.theme.primaryTextColor, Inter.MEDIUM).apply {
            maxLines = 1
            fontFeatureSettings = "tnum"
        }
        val fill = BarFillView(ctx.context, CardSurface.withAlpha(barColor, 0.22f), minOf(ctx.theme.cardBorderRadiusDp, heightDp / 2f) * ctx.dp)
        val iconSize = ctx.px(minOf(24f, heightDp * 0.66f).coerceAtLeast(14f))

        if (iconPos == "outside") row.addView(iconView, LinearLayout.LayoutParams(iconSize, iconSize).apply { marginEnd = ctx.px(10f) })
        if (namePos == "outside") row.addView(nameView, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = ctx.px(10f) })
        val bar = FrameLayout(ctx.context)
        bar.addView(fill, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val inside = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.px(10f), 0, ctx.px(10f), 0)
        }
        if (iconPos == "inside") inside.addView(iconView, LinearLayout.LayoutParams(iconSize, iconSize).apply { marginEnd = ctx.px(8f) })
        if (namePos == "inside") inside.addView(nameView, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        else inside.addView(View(ctx.context), LinearLayout.LayoutParams(0, 1, 1f))
        if (valuePos == "inside") inside.addView(valueView, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = ctx.px(8f) })
        bar.addView(inside, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        row.addView(bar, LinearLayout.LayoutParams(0, ctx.px(heightDp), 1f))
        if (valuePos == "outside") row.addView(valueView, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = ctx.px(10f) })

        val r = Row(spec, fill, nameView, valueView, iconView)
        rows += r
        observe(spec.entity) { s -> bindRow(r, s) }
        return row
    }

    private fun bindRow(row: Row, s: dev.glassworks.engine.vitro.data.EntityState?) {
        val spec = row.spec
        val v = s?.state?.toDoubleOrNull()
        val span = (spec.max - spec.min).takeIf { kotlin.math.abs(it) > 1e-9 } ?: 1.0
        val frac = if (v != null) ((v - spec.min) / span).toFloat().coerceIn(0f, 1f) else 0f
        val color = v?.let { severityColor(spec.severity, it) } ?: spec.color ?: ctx.theme.primaryColor
        row.fill.update(frac, color)
        row.nameView.text = spec.name ?: s?.friendlyName ?: spec.entity
        val unit = spec.unit ?: s?.unit.orEmpty()
        row.valueView.text = when {
            v != null -> formatNumber(v, decimals) + when {
                unit.isEmpty() -> ""
                unit == "%" -> "%"
                else -> " $unit"
            }
            s == null -> "–"
            else -> s.formattedState()
        }
        row.iconView.setIcon(MdiIcons.resolveIconName(spec.icon, s), color)
    }

    companion object {
        fun parseSeverity(arr: org.json.JSONArray?): List<Severity> {
            if (arr == null) return emptyList()
            val out = ArrayList<Severity>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val color = configColor(o.opt("color")) ?: continue
                out += Severity(
                    configNumber(o, "from", Double.NEGATIVE_INFINITY),
                    configNumber(o, "to", Double.POSITIVE_INFINITY),
                    color,
                )
            }
            return out
        }

        /** bar-card's rule: the first range holding [value] (both ends inclusive), or null. */
        fun severityColor(ranges: List<Severity>, value: Double): Int? = ranges.firstOrNull { value >= it.from && value <= it.to }?.color

        /** Every bar the card draws: `entities` (strings or objects) or the single `entity`, card values as defaults. */
        fun parseSpecs(raw: JSONObject): List<BarSpec> {
            val min = configNumber(raw, "min", 0.0)
            val max = configNumber(raw, "max", 100.0)
            val color = configColor(raw.opt("color"))
            val severity = parseSeverity(raw.optJSONArray("severity"))
            val unit = raw.optString("unit_of_measurement", "").ifEmpty { null }
            fun base(entity: String) = BarSpec(
                entity, raw.optString("name", "").ifEmpty { null }, raw.optString("icon", "").ifEmpty { null },
                color, min, max, unit, severity, null, null,
            )
            val arr = raw.optJSONArray("entities")
            if (arr == null || arr.length() == 0) {
                val e = raw.optString("entity", "").ifEmpty { return emptyList() }
                return listOf(base(e))
            }
            val out = ArrayList<BarSpec>(arr.length())
            for (i in 0 until arr.length()) {
                when (val item = arr.opt(i)) {
                    is String -> if (item.isNotBlank()) out += base(item).copy(name = null, icon = null)
                    is JSONObject -> {
                        val e = item.optString("entity", "").ifEmpty { continue }
                        out += BarSpec(
                            entity = e,
                            name = item.optString("name", "").ifEmpty { null },
                            icon = item.optString("icon", "").ifEmpty { null },
                            color = configColor(item.opt("color")) ?: color,
                            min = configNumber(item, "min", min),
                            max = configNumber(item, "max", max),
                            unit = item.optString("unit_of_measurement", "").ifEmpty { null } ?: unit,
                            severity = item.optJSONArray("severity")?.let(::parseSeverity) ?: severity,
                            tapAction = item.optJSONObject("tap_action"),
                            holdAction = item.optJSONObject("hold_action"),
                        )
                    }
                }
            }
            return out
        }
    }
}

/** A bar-card bar: a rounded translucent track and a fill that eases to its fraction over 240 ms. */
internal class BarFillView(context: Context, private val trackColor: Int, private val radiusPx: Float) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rect = RectF()
    private val clip = Path()
    private var fraction = 0f
    private var animator: ValueAnimator? = null

    var targetFraction = -1f
        private set
    var fillColor: Int = 0
        private set

    fun update(frac: Float, color: Int) {
        fillColor = color
        val to = frac.coerceIn(0f, 1f)
        if (to == targetFraction) {
            invalidate()
            return
        }
        targetFraction = to
        animator?.cancel()
        val from = fraction
        animator = HaMotion.run(this, FILL_MS) { t ->
            fraction = HaMotion.lerp(from, to, t)
            invalidate()
        }
        invalidate()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        if (targetFraction >= 0f) fraction = targetFraction
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val r = radiusPx.coerceAtMost(h / 2f)
        rect.set(0f, 0f, w, h)
        paint.color = trackColor
        canvas.drawRoundRect(rect, r, r, paint)
        if (fraction <= 0f) return
        clip.reset()
        clip.addRoundRect(rect, r, r, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        paint.color = fillColor
        rect.set(0f, 0f, w * fraction, h)
        canvas.drawRect(rect, paint)
        canvas.restore()
    }

    private companion object {
        const val FILL_MS = 240L
    }
}
