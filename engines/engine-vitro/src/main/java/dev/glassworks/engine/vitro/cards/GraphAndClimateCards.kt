/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.cards

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
import dev.glassworks.engine.vitro.data.HistoryPoint
import dev.glassworks.engine.vitro.model.ActionConfig
import dev.glassworks.engine.vitro.model.CardConfig
import dev.glassworks.engine.vitro.ui.CardSurface
import dev.glassworks.engine.vitro.ui.MdiIconView
import dev.glassworks.engine.vitro.ui.MdiIcons
import dev.glassworks.engine.vitro.ui.VitroCardContext
import dev.glassworks.engine.vitro.ui.bindHoldAction
import dev.glassworks.ui.Inter
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
        typeface = Inter.typeface(ctx.context, weight, sp)
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
                    val series = entityIds.mapNotNull { map[it] }.filter { it.size >= 2 }
                    ctx.main.post { chart.setSeries(series) }
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

        series.forEachIndexed { idx, pts ->
            if (pts.size < 2) return@forEachIndexed
            val color = colors[idx % colors.size]
            linePath.reset()
            fillPath.reset()
            val n = pts.size
            var prevX = 0f
            var prevY = 0f
            for (i in 0 until n) {
                val x = (i.toFloat() / (n - 1).toFloat()) * w
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
        typeface = Inter.typeface(context, Inter.REGULAR, 34f)
        textAlign = Paint.Align.CENTER
    }
    private val oval = RectF()
    private var fraction = 0f
    private var text = "–"

    fun update(frac: Float, label: String, color: Int = activeColor) {
        fraction = frac; text = label; activeColor = color; invalidate()
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

    init {
        val col = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.px(12f), ctx.px(8f), ctx.px(12f), ctx.px(12f))
        }
        val eid = config.entity

        // Header: the name centred, a ⋮ button for more-info pinned to the right.
        val header = FrameLayout(ctx.context)
        header.addView(titleView, LayoutParams(MATCH_PARENT, ctx.px(36f)).apply {
            leftMargin = ctx.px(40f); rightMargin = ctx.px(40f)
        })
        val dots = FrameLayout(ctx.context).apply {
            isClickable = true
            contentDescription = "More info"
            addView(MdiIconView(ctx.context).apply { setIcon("mdi:dots-vertical", ctx.theme.primaryTextColor) },
                LayoutParams(ctx.px(20f), ctx.px(20f), Gravity.CENTER))
            setOnClickListener { eid?.let { ctx.performAction(ActionConfig("more-info"), it) } }
        }
        header.addView(dots, LayoutParams(ctx.px(36f), ctx.px(36f), Gravity.END or Gravity.CENTER_VERTICAL))
        col.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, ctx.px(36f)))

        // Dial, with the outlined − and + in the opening at its bottom.
        val dialFrame = FrameLayout(ctx.context)
        dialFrame.addView(dial, LayoutParams(MATCH_PARENT, ctx.px(240f)))
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
            setOnClickListener { dial.nudge(sign) }
        }
        val btn = ctx.px(42f)
        steppers.addView(stepBtn("−", -1), LinearLayout.LayoutParams(btn, btn).apply { marginEnd = ctx.px(16f) })
        steppers.addView(stepBtn("+", 1), LinearLayout.LayoutParams(btn, btn))
        dialFrame.addView(steppers, LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        col.addView(dialFrame, LinearLayout.LayoutParams(MATCH_PARENT, ctx.px(240f)))

        col.addView(modeBar, LinearLayout.LayoutParams(MATCH_PARENT, ctx.px(46f)).apply { topMargin = ctx.px(8f) })
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
                )
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
    private val strokeW = 14f * dp
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeW
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private fun textPaint(size: Float, weight: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = spToPx(ctx.context, size)
        typeface = Inter.typeface(ctx.context, weight, size)
    }
    private val labelPaint = textPaint(14f, Inter.MEDIUM).apply { textAlign = Paint.Align.CENTER }
    private val bigPaint = textPaint(44f, Inter.REGULAR)
    private val unitPaint = textPaint(13f, Inter.REGULAR)
    private val decPaint = textPaint(20f, Inter.REGULAR)
    private val curPaint = textPaint(14f, Inter.MEDIUM)
    private val iconMatrix = Matrix()
    private val iconPath = Path()
    private val oval = RectF()

    private var min = 7.0
    private var max = 35.0
    private var step = 0.5
    private var current: Double? = null
    private var target: Double? = null
    private var mode = "off"
    private var action: String? = null
    private var dragging = false
    private var glowKey = 0
    private var cx = 0f
    private var cy = 0f
    private var radius = 0f

    var onCommit: (Double) -> Unit = {}

    fun bind(min: Double, max: Double, step: Double, current: Double?, target: Double?, mode: String, action: String?) {
        this.min = min
        this.max = if (max > min) max else min + 1.0
        this.step = step
        this.current = current
        if (!dragging) this.target = target
        this.mode = mode
        this.action = action
        invalidate()
    }

    /** One `target_temp_step` up ([sign] > 0) or down, committed straight away. */
    fun nudge(sign: Int) {
        val t = target ?: return
        val next = snap(t + sign * step)
        if (next == t) return
        target = next
        invalidate()
        onCommit(next)
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
        radius = (minOf(w, h) / 2f - strokeW / 2f - 4f * dp).coerceAtLeast(10f)
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
        val tgt = target
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
        }
        if (tgt != null) {
            val (x, y) = pointAt(angleOf(tgt))
            fill.color = accent?.first ?: ctx.theme.secondaryTextColor
            canvas.drawCircle(x, y, 6.5f * dp, fill)
            fill.color = Color.WHITE
            canvas.drawCircle(x, y, 5f * dp, fill)
        }

        // Centre: action label, split target temperature, current temperature.
        val labelText = (action ?: mode).replace('_', ' ').replaceFirstChar { it.titlecase(Locale.ROOT) }
        labelPaint.color = activeTint ?: ctx.theme.secondaryTextColor
        canvas.drawText(labelText, cx, cy - 34f * dp, labelPaint)

        val baseline = cy + 16f * dp
        bigPaint.color = ctx.theme.primaryTextColor
        unitPaint.color = ctx.theme.primaryTextColor
        decPaint.color = ctx.theme.primaryTextColor
        if (tgt == null) {
            bigPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("–", cx, baseline, bigPaint)
        } else {
            bigPaint.textAlign = Paint.Align.LEFT
            val (whole, dec) = splitTemp(tgt, step)
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
                target = valueAt(event.x, event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                target = valueAt(event.x, event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) return false
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                target = valueAt(event.x, event.y)
                invalidate()
                target?.let(onCommit)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
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
        val icon = WeatherIconView(ctx.context)
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
                        renderForecast(days, u)
                    }
                }
            }
        }
    }

    private fun renderForecast(days: List<ForecastDay>, unit: String) {
        forecastRow.removeAllViews()
        val hourly = days.size >= 2 && sameDay(days[0].datetime, days[1].datetime)
        for (day in days.take(5)) {
            val cell = LinearLayout(ctx.context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            cell.addView(label(ctx, 12f, ctx.theme.secondaryTextColor).apply { text = forecastLabel(day.datetime, hourly) },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            cell.addView(WeatherIconView(ctx.context).apply { setCondition(day.condition.orEmpty()) },
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

/** HA-style multi-color weather glyph drawn on Canvas: sun, moon, layered clouds, rain, snow, bolt, fog, wind. */
class WeatherIconView(context: Context) : View(context) {
    private var condition = ""
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    fun setCondition(value: String) {
        if (value == condition) return
        condition = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val size = minOf(width, height).toFloat()
        if (size <= 0f) return
        canvas.save()
        canvas.translate((width - size) / 2f, (height - size) / 2f)
        canvas.scale(size / 24f, size / 24f)
        when (condition) {
            "sunny" -> sun(canvas, 12f, 12f, 5f, rays = true)
            "clear-night" -> moon(canvas)
            "partlycloudy" -> { sun(canvas, 9f, 9f, 4.2f, rays = true); cloud(canvas, 14f, 15.5f, 1f, CLOUD_LIGHT) }
            "cloudy" -> { cloud(canvas, 15f, 10.5f, 0.8f, CLOUD_DARK); cloud(canvas, 11f, 14.5f, 1f, CLOUD_LIGHT) }
            "rainy" -> { cloud(canvas, 12f, 10f, 1f, CLOUD_DARK); drops(canvas, 3) }
            "pouring" -> { cloud(canvas, 12f, 10f, 1f, CLOUD_DARK); drops(canvas, 5) }
            "snowy" -> { cloud(canvas, 12f, 10f, 1f, CLOUD_LIGHT); flakes(canvas) }
            "snowy-rainy", "hail" -> { cloud(canvas, 12f, 10f, 1f, CLOUD_LIGHT); drops(canvas, 2); flakes(canvas, sparse = true) }
            "lightning" -> { cloud(canvas, 12f, 10f, 1f, CLOUD_DARK); bolt(canvas) }
            "lightning-rainy" -> { cloud(canvas, 12f, 10f, 1f, CLOUD_DARK); bolt(canvas); drops(canvas, 2) }
            "fog" -> { cloud(canvas, 12f, 9.5f, 1f, CLOUD_LIGHT); fog(canvas) }
            "windy", "windy-variant" -> wind(canvas)
            else -> cloud(canvas, 12f, 12.5f, 1f, CLOUD_LIGHT)
        }
        canvas.restore()
    }

    private fun sun(c: Canvas, x: Float, y: Float, r: Float, rays: Boolean) {
        paint.style = Paint.Style.FILL
        paint.color = SUN
        c.drawCircle(x, y, r, paint)
        if (!rays) return
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.3f
        paint.strokeCap = Paint.Cap.ROUND
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            val r1 = r + 1.6f
            val r2 = r + 3.2f
            c.drawLine(x + r1 * cos(a).toFloat(), y + r1 * sin(a).toFloat(), x + r2 * cos(a).toFloat(), y + r2 * sin(a).toFloat(), paint)
        }
        paint.style = Paint.Style.FILL
    }

    private fun moon(c: Canvas) {
        path.reset()
        path.addCircle(12f, 12f, 7f, Path.Direction.CW)
        val bite = Path().apply { addCircle(15.5f, 9f, 6f, Path.Direction.CW) }
        path.op(bite, Path.Op.DIFFERENCE)
        paint.style = Paint.Style.FILL
        paint.color = MOON
        c.drawPath(path, paint)
    }

    /** A cloud about 16 units wide at [scale] 1, centred on ([x], [y]). */
    private fun cloud(c: Canvas, x: Float, y: Float, scale: Float, color: Int) {
        paint.style = Paint.Style.FILL
        paint.color = color
        c.save()
        c.translate(x, y)
        c.scale(scale, scale)
        c.drawCircle(-3.5f, 0.5f, 3.5f, paint)
        c.drawCircle(1f, -1.5f, 4.8f, paint)
        c.drawCircle(5f, 1f, 3.2f, paint)
        c.drawRoundRect(RectF(-7f, 0.5f, 8.2f, 4.2f), 1.8f, 1.8f, paint)
        c.restore()
    }

    private fun drops(c: Canvas, n: Int) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.4f
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = RAIN
        val xs = when (n) {
            1 -> listOf(12f)
            2 -> listOf(9f, 15f)
            3 -> listOf(8f, 12f, 16f)
            else -> listOf(6.5f, 9.25f, 12f, 14.75f, 17.5f)
        }
        xs.forEachIndexed { i, x ->
            val y = if (i % 2 == 0) 16.5f else 18f
            c.drawLine(x, y, x - 1f, y + 3.2f, paint)
        }
        paint.style = Paint.Style.FILL
    }

    private fun flakes(c: Canvas, sparse: Boolean = false) {
        paint.style = Paint.Style.FILL
        paint.color = SNOW
        val pts = if (sparse) listOf(12f to 18.5f, 17f to 19.5f) else listOf(8f to 17.5f, 12f to 19.5f, 16f to 17.5f, 10f to 21.5f, 14f to 21.5f)
        for ((x, y) in pts) c.drawCircle(x, y, 1.1f, paint)
    }

    private fun bolt(c: Canvas) {
        path.reset()
        path.moveTo(13f, 13f)
        path.lineTo(9.5f, 18.5f)
        path.lineTo(12f, 18.5f)
        path.lineTo(10.5f, 23f)
        path.lineTo(15.5f, 16.5f)
        path.lineTo(12.8f, 16.5f)
        path.lineTo(14.5f, 13f)
        path.close()
        paint.style = Paint.Style.FILL
        paint.color = SUN
        c.drawPath(path, paint)
    }

    private fun fog(c: Canvas) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.4f
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = CLOUD_DARK
        c.drawLine(5f, 17f, 19f, 17f, paint)
        c.drawLine(7f, 20f, 17f, 20f, paint)
        paint.style = Paint.Style.FILL
    }

    private fun wind(c: Canvas) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.6f
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = CLOUD_DARK
        path.reset()
        path.moveTo(3f, 9f); path.lineTo(15f, 9f); path.cubicTo(19f, 9f, 19f, 4f, 15.5f, 4.5f)
        path.moveTo(3f, 13f); path.lineTo(19f, 13f); path.cubicTo(23f, 13f, 23f, 18f, 19.5f, 17.5f)
        path.moveTo(5f, 17f); path.lineTo(12f, 17f)
        c.drawPath(path, paint)
        paint.style = Paint.Style.FILL
    }

    private companion object {
        const val SUN = 0xFFFBC02D.toInt()
        const val MOON = 0xFFFFE082.toInt()
        const val CLOUD_LIGHT = 0xFFCFD8DC.toInt()
        const val CLOUD_DARK = 0xFFB0BEC5.toInt()
        const val RAIN = 0xFF42A5F5.toInt()
        const val SNOW = 0xFF90CAF9.toInt()
    }
}
