/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.cards

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.HistoryPoint
import dev.glassworks.engine.vitro.model.ActionConfig
import dev.glassworks.engine.vitro.model.CardConfig
import dev.glassworks.engine.vitro.ui.CardSurface
import dev.glassworks.engine.vitro.ui.MdiIconView
import dev.glassworks.engine.vitro.ui.MdiIcons
import dev.glassworks.engine.vitro.ui.VitroCardContext
import dev.glassworks.ui.Inter
import org.json.JSONObject
import java.util.Locale

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
        col.addView(chart, LinearLayout.LayoutParams(MATCH_PARENT, ctx.px(72f)).apply { topMargin = ctx.px(8f) })
        addView(col, LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val entityIds = buildList {
            config.entity?.let(::add)
            config.entities().forEach { if (it.entity !in this) add(it.entity) }
        }
        val primaryId = entityIds.firstOrNull()
        val hours = config.raw.optInt("hours_to_show", 24).coerceIn(1, 168)

        if (config.type == "history-graph") {
            titleView.text = config.title ?: "History"
            valueView.visibility = GONE
            icon.setIcon("mdi:gauge", ctx.theme.primaryColor)
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
    private val colors = intArrayOf(primaryColor, secondaryColor, 0xFF4CAF50.toInt(), 0xFFE91E63.toInt())
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
}

/** Native HA `gauge` card drawn directly on Canvas. */
class GaugeCardView(ctx: VitroCardContext, config: CardConfig) : CardSurface(ctx) {
    private val minVal = config.raw.optDouble("min", 0.0)
    private val maxVal = config.raw.optDouble("max", 100.0)
    private val dial = GaugeCanvasView(ctx.context, ctx.theme.primaryColor, ctx.theme.dividerColor, ctx.theme.primaryTextColor)
    private val nameView = label(ctx, 13f, ctx.theme.secondaryTextColor, Inter.MEDIUM).apply { gravity = Gravity.CENTER }

    init {
        val col = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ctx.px(12f), ctx.px(12f), ctx.px(12f), ctx.px(12f))
        }
        col.addView(dial, LinearLayout.LayoutParams(MATCH_PARENT, ctx.px(110f)))
        col.addView(nameView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ctx.px(4f) })
        addView(col, LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val eid = config.entity
        if (eid != null) {
            isClickable = true
            setOnClickListener { ctx.performAction(ActionConfig.parse(config.raw.optJSONObject("tap_action")), eid) }
            observe(eid) { s ->
                val v = s?.state?.toDoubleOrNull() ?: minVal
                val frac = ((v - minVal) / (maxVal - minVal).coerceAtLeast(1e-6)).toFloat().coerceIn(0f, 1f)
                val unit = config.raw.optString("unit", "").ifEmpty { s?.unit.orEmpty() }
                dial.update(frac, if (unit.isNotEmpty()) "${s?.state ?: "–"} $unit" else s?.state ?: "–")
                nameView.text = config.name ?: s?.friendlyName ?: eid
            }
        }
    }
}

private class GaugeCanvasView(context: Context, private val activeColor: Int, private val trackColor: Int, private val textColor: Int) : View(context) {
    private val dp = resources.displayMetrics.density
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 12f * dp
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textSize = 22f * dp
        textAlign = Paint.Align.CENTER
    }
    private val oval = RectF()
    private var fraction = 0f
    private var text = "–"

    fun update(frac: Float, label: String) {
        fraction = frac; text = label; invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val r = minOf(width / 2f - 16f * dp, height - 20f * dp).coerceAtLeast(24f)
        val cx = width / 2f
        val cy = height - 14f * dp
        oval.set(cx - r, cy - r, cx + r, cy + r)
        arcPaint.color = trackColor
        canvas.drawArc(oval, 180f, 180f, false, arcPaint)
        arcPaint.color = activeColor
        canvas.drawArc(oval, 180f, 180f * fraction, false, arcPaint)
        canvas.drawText(text, cx, cy - 8f * dp, textPaint)
    }
}

/** Native HA `thermostat` + `custom:mushroom-climate-card`. */
class ThermostatCardView(ctx: VitroCardContext, config: CardConfig) : CardSurface(ctx) {
    private val titleView = label(ctx, 15f, ctx.theme.primaryTextColor, Inter.MEDIUM)
    private val modeBadge = label(ctx, 12f, ctx.theme.secondaryTextColor, Inter.MEDIUM)
    private val tempView = label(ctx, 36f, ctx.theme.primaryTextColor, Inter.LIGHT)
    private val currentView = label(ctx, 13f, ctx.theme.secondaryTextColor)
    private var targetTemp = 21.0
    private var step = 0.5
    private var glowAction: String? = null

    init {
        val col = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ctx.px(16f), ctx.px(16f), ctx.px(16f), ctx.px(16f))
        }
        col.addView(titleView)
        col.addView(modeBadge, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ctx.px(2f) })
        col.addView(tempView, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ctx.px(10f) })
        col.addView(currentView, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ctx.px(2f) })

        val controls = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, ctx.px(12f), 0, 0)
        }
        val eid = config.entity
        fun stepBtn(symbol: String, delta: Double) = label(ctx, 20f, ctx.theme.primaryTextColor, Inter.MEDIUM).apply {
            text = symbol
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(withAlpha(ctx.theme.primaryTextColor, 0.10f))
            }
            isClickable = true
            setOnClickListener {
                if (eid == null) return@setOnClickListener
                targetTemp += delta
                tempView.text = String.format(Locale.US, "%.1f°", targetTemp)
                ctx.callService("climate", "set_temperature", JSONObject().put("entity_id", eid).put("temperature", targetTemp), null)
            }
        }
        val btnSize = ctx.px(42f)
        controls.addView(stepBtn("−", -step), LinearLayout.LayoutParams(btnSize, btnSize).apply { marginEnd = ctx.px(20f) })
        controls.addView(stepBtn("+", step), LinearLayout.LayoutParams(btnSize, btnSize))
        col.addView(controls)
        addView(col, LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        if (eid != null) {
            observe(eid) { s ->
                titleView.text = config.name ?: s?.friendlyName ?: eid
                val action = (s?.attributes?.get("hvac_action") as? String) ?: s?.state ?: "off"
                val color = if (action == "heating") ctx.theme.activeColor("climate") else ctx.theme.primaryColor
                applyActionGlow(if (action == "heating" || action == "cooling") action else null)
                modeBadge.text = action.replaceFirstChar { it.titlecase(Locale.ROOT) }
                modeBadge.setTextColor(if (s?.isActive == true) color else ctx.theme.secondaryTextColor)
                (s?.attributes?.get("target_temp_step") as? Number)?.toDouble()?.let { if (it > 0) step = it }
                val tgt = (s?.attributes?.get("temperature") as? Number)?.toDouble()
                if (tgt != null) targetTemp = tgt
                tempView.text = tgt?.let { String.format(Locale.US, "%.1f°", it) } ?: "–"
                val cur = s?.attributes?.get("current_temperature")
                currentView.text = if (cur != null) "Currently $cur°" else ""
            }
        }
    }

    /** Soft tinted border and top glow while the HVAC is actively heating or cooling. */
    private fun applyActionGlow(action: String?) {
        if (action == glowAction) return
        glowAction = action
        if (action == null) {
            applyTheme(ctx.theme)
            return
        }
        val tint = if (action == "heating") ctx.theme.activeColor("climate") else COOLING_COLOR
        val base = ctx.theme.cardBackgroundColor
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(blend(base, tint, 0.20f), base),
        ).apply {
            cornerRadius = ctx.theme.cardBorderRadiusDp * ctx.dp
            setStroke(ctx.px(2f).coerceAtLeast(2), withAlpha(tint, 0.65f))
        }
    }

    private fun blend(base: Int, over: Int, fraction: Float): Int {
        val f = fraction.coerceIn(0f, 1f)
        fun mix(a: Int, b: Int) = (a + (b - a) * f).toInt().coerceIn(0, 255)
        return Color.argb(
            Color.alpha(base),
            mix(Color.red(base), Color.red(over)),
            mix(Color.green(base), Color.green(over)),
            mix(Color.blue(base), Color.blue(over)),
        )
    }

    private companion object {
        const val COOLING_COLOR = 0xFF2196F3.toInt()
    }
}

/** Native HA `weather-forecast` + `custom:clock-weather-card`. */
class WeatherCardView(ctx: VitroCardContext, config: CardConfig) : CardSurface(ctx) {
    init {
        val row = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.px(16f), ctx.px(16f), ctx.px(16f), ctx.px(16f))
        }
        val icon = MdiIconView(ctx.context)
        val temp = label(ctx, 28f, ctx.theme.primaryTextColor, Inter.LIGHT)
        val condition = label(ctx, 14f, ctx.theme.secondaryTextColor, Inter.MEDIUM)
        val details = label(ctx, 12f, ctx.theme.secondaryTextColor)
        val col = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.VERTICAL
            addView(condition); addView(details)
        }
        row.addView(icon, LinearLayout.LayoutParams(ctx.px(44f), ctx.px(44f)).apply { marginEnd = ctx.px(14f) })
        row.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(temp)
        addView(row, LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val eid = config.entity
        if (eid != null) {
            isClickable = true
            setOnClickListener { ctx.performAction(ActionConfig.parse(config.raw.optJSONObject("tap_action")), eid) }
            observe(eid) { s ->
                icon.setIcon(MdiIcons.weatherIcon(s?.state.orEmpty()), ctx.theme.primaryColor)
                val t = s?.attributes?.get("temperature")
                val u = (s?.attributes?.get("temperature_unit") as? String) ?: "°"
                temp.text = if (t != null) "$t$u" else "–"
                condition.text = (s?.state ?: "Weather").replace('-', ' ').replaceFirstChar { it.titlecase(Locale.ROOT) }
                val hum = s?.attributes?.get("humidity")
                val wind = s?.attributes?.get("wind_speed")
                details.text = buildList {
                    if (hum != null) add("Humidity $hum%")
                    if (wind != null) add("Wind $wind")
                }.joinToString(" · ")
            }
        }
    }
}

/** Native in-app More-Info overlay sheet (toggles, light brightness slider, live attributes). */
class MoreInfoSheet(private val ctx: VitroCardContext, private val onDismiss: () -> Unit) : FrameLayout(ctx.context) {
    private var unsub: (() -> Unit)? = null
    private val titleView = label(ctx, 18f, ctx.theme.primaryTextColor, Inter.MEDIUM)
    private val stateView = label(ctx, 14f, ctx.theme.secondaryTextColor)
    private val toggleBtn = label(ctx, 14f, Color.WHITE, Inter.MEDIUM)
    private val slider = SeekBar(ctx.context).apply { max = 255 }
    private val attrsView = label(ctx, 12f, ctx.theme.secondaryTextColor)

    init {
        setBackgroundColor(0x99000000.toInt())
        isClickable = true
        setOnClickListener { dismiss() }

        val panel = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 18f * ctx.dp
                setColor(ctx.theme.cardBackgroundColor)
                setStroke(ctx.px(1f).coerceAtLeast(1), ctx.theme.cardBorderColor)
            }
            setPadding(ctx.px(20f), ctx.px(18f), ctx.px(20f), ctx.px(18f))
            isClickable = true
        }
        panel.addView(titleView)
        panel.addView(stateView, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ctx.px(4f) })

        toggleBtn.apply {
            gravity = Gravity.CENTER
            setPadding(ctx.px(18f), ctx.px(10f), ctx.px(18f), ctx.px(10f))
            background = GradientDrawable().apply { cornerRadius = 999f; setColor(ctx.theme.primaryColor) }
            isClickable = true
        }
        panel.addView(toggleBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ctx.px(14f) })
        panel.addView(slider, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ctx.px(12f) })
        panel.addView(attrsView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ctx.px(12f) })

        addView(panel, LayoutParams(ctx.px(360f), WRAP_CONTENT, Gravity.CENTER))
    }

    fun show(entityId: String) {
        unsub?.invoke()
        val domain = entityId.substringBefore('.')
        val toggleable = domain in setOf("light", "switch", "fan", "input_boolean", "automation")
        toggleBtn.visibility = if (toggleable) VISIBLE else GONE
        toggleBtn.setOnClickListener {
            ctx.callService(domain, "toggle", JSONObject().put("entity_id", entityId), null)
        }
        slider.visibility = if (domain == "light") VISIBLE else GONE
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                val bri = sb?.progress ?: return
                ctx.callService("light", "turn_on", JSONObject().put("entity_id", entityId).put("brightness", bri), null)
            }
        })
        val render: (EntityState?) -> Unit = { s ->
            titleView.text = s?.friendlyName ?: entityId
            stateView.text = s?.formattedState() ?: "Unknown"
            toggleBtn.text = if (s?.isActive == true) "Turn off" else "Turn on"
            val bri = (s?.attributes?.get("brightness") as? Number)?.toInt() ?: 0
            if (domain == "light") slider.progress = bri
            attrsView.text = s?.attributes?.entries?.take(6)?.joinToString("\n") { "${it.key}: ${it.value}" }.orEmpty()
        }
        render(ctx.store[entityId])
        unsub = ctx.store.subscribe(entityId) { s -> ctx.main.post { render(s) } }
    }

    fun dismiss() {
        unsub?.invoke()
        unsub = null
        onDismiss()
    }
}
