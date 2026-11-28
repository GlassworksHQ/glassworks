/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.cards

import dev.glassworks.engine.vitro.ui.KeyedText
import dev.glassworks.engine.vitro.ui.NumberText
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
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
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
import dev.glassworks.engine.vitro.data.HaText
import dev.glassworks.engine.vitro.data.HaFormat
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.ForecastDay
import dev.glassworks.engine.vitro.data.HaDisplay
import dev.glassworks.engine.vitro.data.HistoryPoint
import dev.glassworks.engine.vitro.model.ActionConfig
import dev.glassworks.engine.vitro.model.CardConfig
import dev.glassworks.engine.vitro.ui.CardSurface
import dev.glassworks.engine.vitro.ui.HaErrorSeverity
import dev.glassworks.engine.vitro.ui.errorCardContent
import dev.glassworks.engine.vitro.ui.MdiIconView
import dev.glassworks.engine.vitro.ui.MdiIcons
import dev.glassworks.engine.vitro.ui.VitroCardContext
import dev.glassworks.engine.vitro.ui.bindHoldAction
import dev.glassworks.ui.Inter
import dev.glassworks.engine.vitro.ui.VitroFonts
import dev.glassworks.engine.vitro.ui.A11yRole
import dev.glassworks.engine.vitro.ui.VitroAccess
import org.json.JSONArray
import kotlin.math.roundToInt
import dev.glassworks.engine.vitro.theme.ThemeResolver
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

/**
 * Native Canvas sparkline / multi-series line graph for `custom:mini-graph-card`. HA's own `sensor` and
 * `history-graph` cards are drawn as HA draws them: [EntityCardView] with [GraphFooterView], and
 * [HistoryGraphCardView].
 */
class SensorGraphCardView(ctx: VitroCardContext, private val config: CardConfig) : CardSurface(ctx) {
    private val titleView = label(ctx, 14f, ctx.theme.secondaryTextColor, Inter.MEDIUM)
    private val valueView = label(ctx, 24f, ctx.theme.primaryTextColor, Inter.LIGHT)
    private val chart = SparklineCanvasView(ctx.context, ctx.theme.primaryColor, ctx.theme.accentColor, ctx.theme.secondaryTextColor, ctx.now)

    // Declared before `init`, which sets them: an initializer after it would reset them.
    private var historyIds: List<String> = emptyList()
    private var historyHours = 24

    /** Bumped per history request and when the card stops showing: only the newest answer is drawn. */
    private var historyGeneration = 0

    /** The number of the last history read started, and of the newest one whose answer is on screen. */
    private var historyRequest = 0
    private var historyApplied = 0
    private val historyRefresh = Runnable { loadHistory() }

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

        if (primaryId != null) {
            observe(primaryId) { s ->
                // Resolved for the entity the graph names and its state: an entity on no registry entry is its
                // friendly name, as in HA.
                titleView.text = config.nameFor(primaryId, s) ?: config.title ?: s?.friendlyName ?: primaryId
                valueView.text = s?.formattedState() ?: "…"
                icon.setIcon(MdiIcons.resolveIconName(config.icon, s), ctx.theme.primaryColor)
            }
        }
        if (entityIds.size > 1) buildLegend(legend, entityIds)

        if (entityIds.isNotEmpty()) {
            val showGraph = config.raw.optString("graph", "line") != "none"
            if (showGraph) {
                historyIds = entityIds
                historyHours = hours
                loadHistory()
            } else {
                chart.visibility = GONE
            }
            isClickable = true
            setOnClickListener { ctx.performAction(ActionConfig.parse(config.raw.optJSONObject("tap_action")), primaryId) }
            bindHoldAction(holdFor(config.raw, primaryId), primaryId)
        }
    }

    /**
     * Reads the graph's history and draws it; while the card shows, again every [HISTORY_REFRESH_MS] (HA's graphs
     * follow the history live), and once more when a hidden cached view shows again.
     */
    private fun loadHistory() {
        ctx.main.removeCallbacks(historyRefresh)
        if (historyIds.isEmpty()) return
        val ids = historyIds
        // Answers from this showing all count (a later read that fails doesn't discard an earlier one); hiding the
        // card bumps the generation, which drops the ones still on their way.
        val gen = historyGeneration
        // Reads can overlap (the refresh doesn't wait for a slow one): only an answer newer than the one on screen lands.
        val seq = ++historyRequest
        // The next read is set now, not when this one answers: a failed request (which answers nothing) mustn't stop
        // the refresh while the card shows.
        if (isAttachedToWindow && isShown) ctx.main.postDelayed(historyRefresh, HISTORY_REFRESH_MS)
        ctx.fetchHistory(ids, historyHours) { map ->
            // One slot per entity, empty when it has too little history, so each keeps its legend color.
            val series = ids.map { id -> map[id]?.takeIf { it.size >= 2 } ?: emptyList() }
            ctx.main.post {
                if (gen != historyGeneration || seq <= historyApplied) return@post
                historyApplied = seq
                chart.setSeries(if (series.all { it.isEmpty() }) emptyList() else series)
            }
        }
    }

    /** Whether the card showed the last time its visibility was looked at: only a hidden → shown change reloads. */
    private var historyShown = false

    /** Attached before: the constructor's read is long gone, and a return reads the history again at once. */
    private var attachedBefore = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        historyShown = isShown
        val again = attachedBefore
        attachedBefore = true
        if (historyIds.isNotEmpty() && isShown) {
            if (again) {
                // Back on screen: fresh history now (loadHistory sets the next refresh), not five minutes on.
                loadHistory()
            } else {
                ctx.main.removeCallbacks(historyRefresh)
                ctx.main.postDelayed(historyRefresh, HISTORY_REFRESH_MS)
            }
        }
    }

    override fun onDetachedFromWindow() {
        ctx.main.removeCallbacks(historyRefresh)
        // A read still on its way belongs to the showing that ended: it doesn't land.
        historyGeneration++
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (!isAttachedToWindow || historyIds.isEmpty()) return
        val shown = isShown
        if (shown == historyShown) return // attaching (already loaded by the constructor), or no change
        historyShown = shown
        if (shown) {
            loadHistory()
        } else {
            ctx.main.removeCallbacks(historyRefresh)
            historyGeneration++
        }
    }

    /** mini-graph-card's legend: a colored dot and name per series; tapping one opens its more-info. */
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
            // mini-graph-card's computeName: the series' own `name`, else its friendly name, else its id.
            val row = config.entities().firstOrNull { it.isEntity && it.entity == id }
            observe(id) { s -> name.text = row?.nameFor(s) ?: s?.friendlyName ?: id }
            legend.addView(item)
        }
    }

    private companion object {
        /** How often a shown graph reads its history again. */
        const val HISTORY_REFRESH_MS = 5 * 60_000L
    }
}

private class SparklineCanvasView(
    context: Context,
    primaryColor: Int,
    secondaryColor: Int,
    private val labelColor: Int,
    /** The card's clock (VitroCardContext.now): the time axis runs up to it. */
    private val now: () -> Long = System::currentTimeMillis,
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
    private var series: List<List<HistoryPoint>> = emptyList()

    // Built once per data or size change, never in onDraw: one line path per series, the first one's fill and its
    // gradient, and the min/max labels.
    private val linePaths = ArrayList<Path>()
    private val lineColors = ArrayList<Int>()
    private val fillPath = Path()
    private var hasFill = false
    private var minText = ""
    private var maxText = ""
    private var builtW = -1
    private var builtH = -1

    fun setSeries(data: List<List<HistoryPoint>>) {
        series = data
        builtW = -1
        invalidate()
    }

    private fun formatValue(v: Double): String =
        HaDisplay.formatFixed(v, if (kotlin.math.abs(v) >= 100.0 || v == Math.rint(v)) 0 else 1)

    private fun rebuild(w: Float, h: Float) {
        builtW = width
        builtH = height
        linePaths.clear()
        lineColors.clear()
        fillPath.reset()
        hasFill = false
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
        val usableH = (h - padY * 2f).coerceAtLeast(1f)
        // A time axis shared by every series, up to now as HA draws it; sample order only when a time is missing.
        var tMin = Double.POSITIVE_INFINITY
        var tMax = Double.NEGATIVE_INFINITY
        var timed = true
        for (s in series) for (p in s) {
            if (p.epochSec <= 0.0) timed = false
            if (p.epochSec < tMin) tMin = p.epochSec
            if (p.epochSec > tMax) tMax = p.epochSec
        }
        tMax = maxOf(tMax, now() / 1000.0)
        val tSpan = tMax - tMin
        if (tSpan <= 0.0) timed = false
        series.forEachIndexed { idx, pts ->
            if (pts.size < 2) return@forEachIndexed
            val color = colors[idx % colors.size]
            val linePath = Path()
            val n = pts.size
            var prevX = 0f
            var prevY = 0f
            for (i in 0 until n) {
                val x = if (timed) (((pts[i].epochSec - tMin) / tSpan).toFloat() * w) else (i.toFloat() / (n - 1).toFloat()) * w
                val norm = ((pts[i].value - minV) / span).toFloat()
                val y = h - padY - norm * usableH
                if (i == 0) {
                    linePath.moveTo(x, y)
                    if (idx == 0) { fillPath.moveTo(x, h); fillPath.lineTo(x, y) }
                } else {
                    // Smooth S-curve with horizontal tangents at each sample: no overshoot past min/max.
                    val midX = (prevX + x) / 2f
                    linePath.cubicTo(midX, prevY, midX, y, x, y)
                    if (idx == 0) fillPath.cubicTo(midX, prevY, midX, y, x, y)
                }
                prevX = x
                prevY = y
            }
            if (timed && prevX < w) {
                // The last state holds until now.
                linePath.lineTo(w, prevY)
                if (idx == 0) fillPath.lineTo(w, prevY) // the fill is series zero's alone
            }
            if (idx == 0) {
                fillPath.lineTo(w, h)
                fillPath.close()
                hasFill = true
                fillPaint.shader = LinearGradient(0f, 0f, 0f, h, CardSurface.withAlpha(color, 0.28f), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            }
            linePaths += linePath
            lineColors += color
        }
        minText = formatValue(minV)
        maxText = formatValue(maxV)
    }

    override fun onDraw(canvas: Canvas) {
        if (series.isEmpty() || width <= 4 || height <= 4) return
        val h = height.toFloat()
        if (builtW != width || builtH != height) rebuild(width.toFloat(), h)
        if (hasFill) canvas.drawPath(fillPath, fillPaint)
        for (i in linePaths.indices) {
            linePaint.color = lineColors[i]
            canvas.drawPath(linePaths[i], linePaint)
        }
        // Faint min/max labels in the left-hand corners.
        canvas.drawText(maxText, 2f * dp, labelPaint.textSize, labelPaint)
        canvas.drawText(minText, 2f * dp, h - 2f * dp, labelPaint)
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

/**
 * Native HA `gauge` card, as HA 2026's `hui-gauge-card` and `ha-gauge` draw it: 12 px padding, the gauge (at most
 * 250 px wide) over the name on a 28 px line. The gauge is a half ring of radius 40 and stroke 12 in a 100 x 55 box:
 * the track in `--primary-background-color`, the value arc in the severity color (`--info-color` without one), or,
 * with `needle: true`, the severity levels as arcs under a needle. The value is fitted into 55% of the gauge's width
 * and 40% of its height, its box ending 10% above the bottom. In place of the card, HA's `hui-warning` when the
 * entity is missing, unavailable, or its value isn't a number (`isNaN`: unknown, a word, a missing attribute).
 */
class GaugeCardView(ctx: VitroCardContext, private val config: CardConfig) : CardSurface(ctx) {
    private val raw = config.raw
    private val minVal = raw.optDouble("min", 0.0).takeIf { !it.isNaN() } ?: 0.0
    private val maxVal = raw.optDouble("max", 100.0).takeIf { !it.isNaN() } ?: 100.0
    private val needle = raw.optBoolean("needle", false)
    private val info = themeColor("info-color", INFO)
    private val segments = parseSegments(raw.optJSONArray("segments"))
    private val severity = parseSeverity(raw.optJSONObject("severity"), ::severityColorOf)
    private val dial = GaugeCanvasView(
        ctx.context,
        track = ctx.theme.backgroundColor,
        textColor = ctx.theme.primaryTextColor,
        needleStroke = ctx.theme.cardBackgroundColor,
    )
    private val nameView = label(ctx, 14f, ctx.theme.primaryTextColor).apply {
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    private val col = LinearLayout(ctx.context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
    }
    private var warning: View? = null
    private var warningTitle: String? = null

    init {
        // `ha-card { padding: var(--ha-space-3); justify-content: center }`.
        col.setPadding(ctx.px(12f), ctx.px(12f), ctx.px(12f), ctx.px(12f))
        col.addView(dial, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        col.addView(nameView, LinearLayout.LayoutParams(MATCH_PARENT, ctx.px(28f)))
        addView(col, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        dial.setLevels(if (needle) levels() else null, minVal, maxVal)

        val eid = config.entity
        if (eid != null) {
            isClickable = true
            setOnClickListener { ctx.performAction(ActionConfig.parse(raw.optJSONObject("tap_action")), eid) }
            bindHoldAction(holdFor(raw, eid), eid)
            observe(eid) { s -> render(s) }
        }
    }

    override fun measureCard(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // A fixed grid height: the gauge also fits the height the name leaves (`100cqh * 100 / 55`).
        dial.maxHeightPx = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            (MeasureSpec.getSize(heightMeasureSpec) - paddingTop - paddingBottom - ctx.px(12f) * 2 - ctx.px(28f)).coerceAtLeast(0)
        } else {
            Int.MAX_VALUE
        }
        // The warning covers the card's border too (its margins undo the card's padding): hui-error-card has none.
        (warning?.layoutParams as? MarginLayoutParams)?.let { lp ->
            if (lp.leftMargin != -paddingLeft || lp.topMargin != -paddingTop || lp.rightMargin != -paddingRight || lp.bottomMargin != -paddingBottom) {
                lp.setMargins(-paddingLeft, -paddingTop, -paddingRight, -paddingBottom)
            }
        }
        super.measureCard(widthMeasureSpec, heightMeasureSpec)
    }

    /**
     * HA's `hui-warning` in place of the gauge, titled [title] for an administrator (the icon alone for anyone
     * else), or the gauge again for null. The card shrinks to it, and it takes no action.
     */
    private fun showWarning(title: String?) {
        if (title == warningTitle) return
        warningTitle = title
        warning?.let { removeView(it) }
        warning = null
        col.visibility = if (title == null) VISIBLE else GONE
        isClickable = title == null && config.entity != null
        isLongClickable = isClickable
        if (title == null) return
        val w = errorCardContent(ctx, HaErrorSeverity.WARNING, title, over = ctx.theme.cardBackgroundColor)
        addView(w, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { setMargins(-paddingLeft, -paddingTop, -paddingRight, -paddingBottom) })
        warning = w
    }

    private fun render(s: EntityState?) {
        nameView.text = config.name(s) ?: s?.displayName ?: config.entity
        val attribute = raw.optString("attribute", "").ifEmpty { null }
        val eid = config.entity.orEmpty()
        val rawValue = if (attribute != null) s?.attributes?.get(attribute) else s?.state
        // HA's isNaN(value): a missing attribute is undefined, so NaN too.
        val value = when {
            s == null -> null
            attribute != null && !s.attributes.containsKey(attribute) -> null
            else -> jsNumberOf(rawValue)
        }
        // hui-gauge-card's warnings, in its order.
        showWarning(when {
            s == null -> HaText.ui("ui.card.common.entity_not_found")
            s.state == "unavailable" -> HaText.ui("ui.panel.lovelace.warning.entity_unavailable", "entity" to eid)
            value != null -> null
            attribute != null -> HaText.ui("ui.panel.lovelace.warning.attribute_not_numeric", "entity" to eid, "attribute" to attribute)
            else -> HaText.ui("ui.panel.lovelace.warning.entity_non_numeric", "entity" to eid)
        })
        if (s == null || value == null || warningTitle != null) return
        val customUnit = raw.optString("unit", "").ifEmpty { null }
        val text = when {
            // A custom unit can't keep a locale's position: the number, then the unit (HA's ha-gauge `label`).
            customUnit != null -> numberText(rawValue) + if (customUnit == "%") "%" else " $customUnit"
            attribute != null -> numberText(rawValue)
            else -> s.formattedState()
        }
        val segmentLabel = if (needle) segmentLabel(value) else null
        dial.update(value, segmentLabel ?: text, if (needle) info else severityOf(value), segmentLabel)
    }

    /** JavaScript's `Number(v)` as `isNaN` reads it: null for NaN (a word, unknown, an object), 0 for null. */
    private fun jsNumberOf(v: Any?): Double? = when (v) {
        null -> 0.0
        is Number -> v.toDouble().takeIf { !it.isNaN() }
        is Boolean -> if (v) 1.0 else 0.0
        is String -> HaFormat.jsNumber(v)?.takeIf { !it.isNaN() }
        else -> null
    }

    private fun numberText(v: Any?): String {
        val d = (v as? Number)?.toDouble() ?: v?.toString()?.toDoubleOrNull() ?: return v?.toString().orEmpty()
        return HaDisplay.formatValue(d, 3)
    }

    /** HA's `_computeSeverity`: the segment (or old severity threshold) the value is in, else `--info-color`. */
    internal fun severityOf(v: Double): Int {
        if (segments != null) return segments.lastOrNull { v >= it.from }?.color ?: info
        if (severity.isEmpty()) return raw.optString("color", "").ifEmpty { null }?.let(::cssColor) ?: info
        return severityColor(severity, v) ?: info
    }

    /** HA's `_severityLevels` for the needle: the segments, the severity thresholds, or one level in `color`. */
    internal fun levels(): List<GaugeLevel> {
        val out = when {
            segments != null -> segments.map { GaugeLevel(it.from, it.color, it.label) }
            raw.optJSONObject("severity") != null -> {
                val sev = raw.optJSONObject("severity")!!
                sev.keys().asSequence().mapNotNull { k ->
                    val level = sev.optDouble(k).takeIf { !it.isNaN() } ?: return@mapNotNull null
                    GaugeLevel(level, severityColorOf(k) ?: return@mapNotNull null, null)
                }.toList()
            }
            else -> listOf(GaugeLevel(minVal, raw.optString("color", "").ifEmpty { null }?.let(::cssColor) ?: info, null))
        }.sortedBy { it.level }
        // ha-gauge: a first level that doesn't start at min gets one in `--info-color` before it.
        return if (out.isNotEmpty() && out[0].level != minVal) listOf(GaugeLevel(minVal, info, null)) + out else out
    }

    private fun segmentLabel(v: Double): String? =
        levels().lastOrNull { v >= it.level }?.label?.takeIf { it.isNotEmpty() }

    private fun severityColorOf(key: String): Int? = when (key) {
        "green" -> themeColor("success-color", SEVERITY_GREEN)
        "yellow" -> themeColor("warning-color", SEVERITY_YELLOW)
        "red" -> themeColor("error-color", SEVERITY_RED)
        else -> null
    }

    /** HA's `computeCssColor`: a theme color name is its `--<name>-color`, anything else a CSS color. */
    private fun cssColor(c: String): Int? =
        ThemeResolver.parseColor(ctx.theme.vars["$c-color"]) ?: MushroomColors.parse(c, ctx.theme)

    private fun themeColor(name: String, fallback: Int): Int = ThemeResolver.parseColor(ctx.theme.vars[name]) ?: fallback

    private fun parseSegments(arr: JSONArray?): List<Segment>? {
        if (arr == null) return null
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val from = o.optDouble("from").takeIf { !it.isNaN() } ?: return@mapNotNull null
            Segment(from, o.optString("color", "").ifEmpty { null }?.let(::cssColor) ?: info, o.optString("label", "").ifEmpty { null })
        }.sortedBy { it.from }
    }

    private class Segment(val from: Double, val color: Int, val label: String?)

    companion object {
        /** HA's gauge severity colors (`--success-color`, `--warning-color`, `--error-color`, `--info-color`). */
        const val SEVERITY_GREEN = 0xFF43A047.toInt()
        const val SEVERITY_YELLOW = 0xFFFFA600.toInt()
        const val SEVERITY_RED = 0xFFDB4437.toInt()
        const val INFO = 0xFF039BE5.toInt()

        /** Reads `severity: { green, yellow, red }` into `(threshold, color)` pairs, highest threshold first. */
        fun parseSeverity(raw: JSONObject?, color: (String) -> Int? = ::defaultSeverityColor): List<Pair<Double, Int>> {
            if (raw == null) return emptyList()
            return listOf("green", "yellow", "red")
                .mapNotNull { key ->
                    if (!raw.has(key) || raw.isNull(key)) return@mapNotNull null
                    val c = color(key) ?: return@mapNotNull null
                    raw.optDouble(key).takeIf { !it.isNaN() }?.let { it to c }
                }
                .sortedByDescending { it.first }
        }

        private fun defaultSeverityColor(key: String): Int? = when (key) {
            "green" -> SEVERITY_GREEN
            "yellow" -> SEVERITY_YELLOW
            "red" -> SEVERITY_RED
            else -> null
        }

        /** HA's rule: the color of the highest threshold at or below [value], or null below every threshold. */
        fun severityColor(thresholds: List<Pair<Double, Int>>, value: Double): Int? =
            thresholds.firstOrNull { value >= it.first }?.second
    }
}

/** One of `ha-gauge`'s levels: from [level] to the end of the arc, in [color]; [label] replaces the value there. */
internal class GaugeLevel(val level: Double, val color: Int, val label: String?)

/**
 * `ha-gauge`: the 100 x 55 SVG box (radius 40, stroke 12, centered at 50, 50), as wide as the card allows up to 250
 * px, plus the 6.4 px the inline SVG's line box adds under it; the value text fitted over it.
 */
private class GaugeCanvasView(
    context: Context,
    private val track: Int,
    textColor: Int,
    needleStroke: Int,
) : View(context) {
    private val dp = resources.displayMetrics.density
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }
    private val needleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = textColor }
    private val needleLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = needleStroke
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        typeface = VitroFonts.typeface(context, Inter.REGULAR, 16f)
        textAlign = Paint.Align.CENTER
    }
    private val oval = RectF()
    private val needlePath = Path()
    private var levels: List<GaugeLevel>? = null
    private var min = 0.0
    private var max = 100.0
    private var value = 0.0
    private var color = 0
    private var angle = 0f
    private var targetAngle = -1f
    private var animator: ValueAnimator? = null
    private var text = "–"

    /** The fixed height a grid row leaves the gauge, or none. */
    var maxHeightPx = Int.MAX_VALUE
        set(v) { if (field != v) { field = v; requestLayout() } }

    fun setLevels(levels: List<GaugeLevel>?, min: Double, max: Double) {
        this.levels = levels
        this.min = min
        this.max = max
    }

    /** The value, its text, the arc's color, and the level label shown instead of the value (if any). */
    fun update(v: Double, label: String, arcColor: Int, segmentLabel: String?) {
        text = label
        // The value as HA's page has it as text: for TalkBack and the parity harness.
        if (contentDescription?.toString() != label) contentDescription = label
        value = v
        color = arcColor
        val to = angleOf(v)
        if (to == targetAngle) {
            invalidate()
            return
        }
        targetAngle = to
        animator?.cancel()
        val from = angle
        // `transition: stroke-dashoffset 1s ease` (the needle: `all 1s ease`).
        animator = HaMotion.run(this, SWEEP_MS) { t ->
            angle = HaMotion.lerp(from, to, t)
            invalidate()
        }
        invalidate()
    }

    /** `getAngle`: the value's place on the half ring, 0 to 180 degrees from the left. */
    private fun angleOf(v: Double): Float {
        if (max <= min) return 0f
        return (((v.coerceIn(min, max) - min) / (max - min)) * 180.0).toFloat()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        if (targetAngle >= 0f) angle = targetAngle
        super.onDetachedFromWindow()
    }

    private fun gaugeWidth(available: Int): Float {
        var w = minOf(available.toFloat(), MAX_WIDTH * dp)
        if (maxHeightPx != Int.MAX_VALUE) w = minOf(w, maxHeightPx * 100f / 55f)
        return w.coerceAtLeast(0f)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val gw = gaugeWidth(w)
        setMeasuredDimension(w, (gw * 0.55f + LINE_GAP * dp).roundToInt())
    }

    override fun onDraw(canvas: Canvas) {
        val gw = gaugeWidth(width)
        if (gw <= 0f) return
        val u = gw / 100f
        val left = (width - gw) / 2f
        val cx = left + 50f * u
        val cy = 50f * u
        val r = 40f * u
        oval.set(cx - r, cy - r, cx + r, cy + r)
        arcPaint.strokeWidth = 12f * u
        arcPaint.color = track
        canvas.drawArc(oval, 180f, 180f, false, arcPaint)
        val lv = levels
        if (lv == null) {
            arcPaint.color = color
            if (angle > 0f) canvas.drawArc(oval, 180f, angle, false, arcPaint)
        } else {
            // Each level from its start to the end of the ring, the later ones over the earlier; the last one again
            // over its final half degree, as ha-gauge does.
            for ((i, level) in lv.withIndex()) {
                val start = angleOf(level.level)
                arcPaint.color = level.color
                canvas.drawArc(oval, 180f + start, 180f - start, false, arcPaint)
                if (i == lv.size - 1) canvas.drawArc(oval, 180f + 179.5f, 0.5f, false, arcPaint)
            }
            // The needle: "M -34,-3 L -40,-1 A 1,1 0 0 0 -40,1 L -34,3 A 2,2 0 0 0 -34,-3 Z" (that last radius too
            // small for its chord, so SVG draws it at 3), turned by the value's angle.
            needlePath.reset()
            needlePath.moveTo(-34f, -3f)
            needlePath.lineTo(-40f, -1f)
            needlePath.arcTo(RectF(-41f, -1f, -39f, 1f), 270f, -180f)
            needlePath.lineTo(-34f, 3f)
            needlePath.arcTo(RectF(-37f, -3f, -31f, 3f), 90f, -180f)
            needlePath.close()
            canvas.save()
            canvas.translate(cx, cy)
            canvas.scale(u, u)
            canvas.rotate(angle)
            canvas.drawPath(needlePath, needleFill)
            needleLine.strokeWidth = 1f
            canvas.drawPath(needlePath, needleLine)
            canvas.restore()
        }
        // The value: HA measures it at 16 px and scales that box into 55% x 40% of the gauge, its bottom at 90%. The
        // box (getBBox) holds the space ha-gauge's template leaves on each side of the value (its line breaks, and
        // the " ${label}" after it, collapse to one space each), so the glyphs fill less than the 55%.
        val h = height.toFloat()
        textPaint.textSize = 16f * dp
        if (text.isBlank()) return
        val w16 = textPaint.measureText(" $text ")
        if (w16 <= 0f) return
        val h16 = BOX_EM * 16f * dp
        val k = minOf(0.55f * gw / w16, 0.4f * h / h16)
        textPaint.textSize = 16f * dp * k
        canvas.drawText(text, width / 2f, 0.9f * h - DESCENT_EM * textPaint.textSize, textPaint)
    }

    private companion object {
        const val SWEEP_MS = 1_000L
        const val MAX_WIDTH = 250f
        /** The inline SVG's line box: ha-gauge is 143.9 px tall for a 137.5 px SVG. */
        const val LINE_GAP = 6.4f
        /** The value text's box (SVG getBBox) at 16 px: 19.02 px tall, its baseline 4.02 px above the bottom. */
        const val BOX_EM = 19.02f / 16f
        const val DESCENT_EM = 4.02f / 16f
    }
}

private const val HEAT_COLOR = 0xFFFF6F22.toInt()
private const val COOL_COLOR = 0xFF2196F3.toInt()
private const val OFF_COLOR = 0xFF757575.toInt()

/**
 * Native HA `thermostat` (`hui-thermostat-card`), and through [HumidifierCardView] `humidifier`
 * (`hui-humidifier-card`, laid out the same): a 270° dial, and a mode bar for Mushroom's climate card.
 */
open class ThermostatCardView(ctx: VitroCardContext, config: CardConfig) : CardSurface(ctx) {
    /** A humidifier's card: HA's humidity control in the dial, humidifier.set_humidity. */
    private val humid = config.entity?.startsWith("humidifier.") == true
    private val titleView = label(ctx, 16f, ctx.theme.primaryTextColor).apply {
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val dial = ThermostatDialView(ctx, if (humid) "%" else temperatureUnit(ctx))
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
    private var currentMode: String? = null
    private var steppers: View? = null
    private var stepButtons: Pair<View, View>? = null
    private var hasTarget = false

    /** The dial's size class at the card's last width: HA shows the − and + only at the large size. */
    private var dialSize = DialSize.LG

    private fun showSteppers() {
        val v = if (hasTarget && dialSize == DialSize.LG) VISIBLE else GONE
        if (steppers?.visibility != v) steppers?.visibility = v
    }

    private val dialFrame = FrameLayout(ctx.context)
    private var showModes = false

    /**
     * hui-thermostat-card's `.container` (flex: 1, at least as tall as it is wide, as wide as what it holds) holding the
     * state control (8 px of padding, a 320 px dial at most, as wide as the container's height, shrunk to the card's
     * width): with no fixed height (a masonry column) the container is a square around the control, the dial 16 px
     * narrower than the card up to 320 px; with one (a sections grid's rows) it's what the title leaves and the dial
     * as tall as it, overflowing its padding.
     */
    override fun measureCard(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            val w = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
            val fixed = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY
            val bar = if (showModes) ctx.px(66f) else 0
            val containerH = if (fixed) {
                (MeasureSpec.getSize(heightMeasureSpec) - paddingTop - paddingBottom - ctx.px(48f) - bar).coerceAtLeast(0)
            } else {
                minOf(w, ctx.px(336f))
            }
            val dialPx = minOf(ctx.px(320f), w - ctx.px(16f), if (fixed) containerH else w).coerceAtLeast(0)
            (dialFrame.layoutParams as? LinearLayout.LayoutParams)?.let { if (it.height != containerH) it.height = containerH }
            (dial.layoutParams as? LayoutParams)?.let { if (it.height != dialPx) it.height = dialPx }
            // `.buttons` sit 10 px above the dial's bottom, the dial centered in the container.
            (steppers?.layoutParams as? LayoutParams)?.let { lp ->
                val m = ctx.px(10f) + (containerH - dialPx) / 2
                if (lp.bottomMargin != m) lp.bottomMargin = m
            }
            dialSize = DialSize.of(dialPx / ctx.dp)
            showSteppers()
        }
        super.measureCard(widthMeasureSpec, heightMeasureSpec)
    }

    init {
        // hui-thermostat-card: a 48 px title row over the 262 px dial; the mode bar only when its feature is set
        // (Mushroom's climate card: when `hvac_modes` is).
        showModes = if (config.type == "thermostat") {
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
            contentDescription = HaText.ui("ui.panel.lovelace.editor.action-editor.actions.more-info")
            addView(MdiIconView(ctx.context).apply { setIcon("mdi:dots-vertical", ctx.theme.primaryTextColor) },
                LayoutParams(ctx.px(20f), ctx.px(20f), Gravity.CENTER))
            setOnClickListener { eid?.let { ctx.performAction(ActionConfig("more-info"), it) } }
        }
        header.addView(dots, LayoutParams(ctx.px(48f), ctx.px(48f), Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = -ctx.px(11f) })
        col.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, ctx.px(48f)))

        // Dial, with the outlined − and + in the opening at its bottom.
        dialFrame.addView(dial, LayoutParams(MATCH_PARENT, ctx.px(262f), Gravity.CENTER_VERTICAL))
        val steppers = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        // ha-outlined-icon-button: 48 px, a 1 px outline and a 24 px icon in the secondary text color.
        fun stepBtn(icon: String, sign: Int) = FrameLayout(ctx.context).apply {
            contentDescription = stepperLabel(sign, null)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke(ctx.px(1f).coerceAtLeast(1), ctx.theme.secondaryTextColor)
            }
            addView(MdiIconView(ctx.context).apply { setIcon(icon, ctx.theme.secondaryTextColor) },
                LayoutParams(ctx.px(24f), ctx.px(24f), Gravity.CENTER))
            isClickable = true
            applyHaRipple(this, 999f, ctx.theme.dark)
            setOnClickListener { dial.nudge(sign) }
        }
        val btn = ctx.px(48f)
        val minus = stepBtn("mdi:minus", -1)
        val plus = stepBtn("mdi:plus", 1)
        stepButtons = minus to plus
        // `.buttons`: 24 px apart, 10 px above the dial's bottom.
        steppers.addView(minus, LinearLayout.LayoutParams(btn, btn).apply { marginEnd = ctx.px(24f) })
        steppers.addView(plus, LinearLayout.LayoutParams(btn, btn))
        dialFrame.addView(steppers, LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = ctx.px(10f)
        })
        // The container spans the card's width: the column's 12 px padding doesn't hold it.
        col.addView(dialFrame, LinearLayout.LayoutParams(MATCH_PARENT, ctx.px(262f)).apply {
            leftMargin = -ctx.px(12f); rightMargin = -ctx.px(12f)
        })

        if (showModes) col.addView(modeBar, LinearLayout.LayoutParams(MATCH_PARENT, ctx.px(46f)).apply { topMargin = ctx.px(8f) })
        this.steppers = steppers
        addView(col, LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        if (eid != null) {
            bindHoldAction(holdFor(config.raw, eid), eid)
            // A water heater sets its tank through its own domain's service, and has no heat/cool range.
            val waterHeater = eid.startsWith("water_heater.")
            dial.waterHeater = waterHeater
            dial.humidifier = humid
            dial.onCommit = commit@{ target ->
                if (humid) {
                    // ha-state-control-humidifier-humidity: humidifier.set_humidity with the target, a whole number
                    // when it is one.
                    val s = ctx.store[eid] ?: return@commit
                    // An unavailable humidifier has no target for HA to set (a TalkBack step on a kept edit): nothing sent.
                    if (s.state == "unavailable") return@commit
                    val value: Number = if (target % 1.0 == 0.0) target.toInt() else target
                    ctx.callOptimistic(s.copy(attributes = s.attributes + ("humidity" to value)), "humidifier", "set_humidity",
                        JSONObject().put("entity_id", eid).put("humidity", value))
                    return@commit
                }
                val domain = if (waterHeater) "water_heater" else "climate"
                val s = ctx.store[eid]
                // Only while the store still holds a single target: not after HA moved to a range (its state, not the
                // dial's last bind), and not for an entity the store no longer has.
                if (s == null || ClimateTargets.of(s.attributes).value == null) return@commit
                ctx.callOptimistic(ClimateTargets.withValue(s, target), domain, "set_temperature",
                    JSONObject().put("entity_id", eid).put("temperature", target))
            }
            dial.onRangeCommit = { handle, low, high ->
                // Finished against the entity as the store holds it now: HA's newer other end wins.
                val s = ctx.store[eid]
                ClimateTargets.rangeToSend(handle, low, high, s)?.let { (lo, hi) ->
                    ctx.callOptimistic(ClimateTargets.withRange(s, lo, hi), "climate", "set_temperature", ClimateTargets.rangeData(eid, lo, hi))
                }
            }
            dial.onSelect = { styleSteppers() }
            observe(eid) { s ->
                titleView.text = config.name(s) ?: s?.displayName ?: eid
                if (humid) {
                    bindHumidifier(s)
                    return@observe
                }
                val attrs = s?.attributes.orEmpty()
                val mode = s?.state ?: "off"
                val action = attrs["hvac_action"] as? String
                val unit = temperatureUnit(ctx)
                val targets = ClimateTargets.of(attrs).let { if (waterHeater) it.copy(low = null, high = null) else it }
                dial.bind(
                    min = (attrs["min_temp"] as? Number)?.toDouble() ?: 7.0,
                    max = (attrs["max_temp"] as? Number)?.toDouble() ?: 35.0,
                    step = ClimateTargets.stepOf(attrs, unit),
                    current = (attrs["current_temperature"] as? Number)?.toDouble(),
                    target = targets.value,
                    mode = mode,
                    action = action,
                    stateLabel = s?.let { HaDisplay.stateLabel(it) } ?: mode.replace('_', ' ').replaceFirstChar { it.titlecase(Locale.ROOT) },
                    low = targets.low,
                    high = targets.high,
                    hvacModes = (attrs["hvac_modes"] as? List<*>)?.mapNotNull { it?.toString() }.orEmpty(),
                )
                // HA shows the steppers only while there's a target (or a range) to step, and only at the dial's
                // large size.
                hasTarget = s?.state != "unavailable" && (targets.value != null || targets.isRange)
                showSteppers()
                styleSteppers()
                val list = (attrs["hvac_modes"] as? List<*>)?.mapNotNull { it?.toString() }?.takeIf { it.isNotEmpty() }
                    ?: listOf("off", "heat", "cool")
                renderModes(list, mode, eid)
            }
        }
    }

    /**
     * HA's humidity control: `min_humidity`..`max_humidity` (0..100), `target_humidity_step` (1), the target while the
     * humidifier is available, `current_humidity`, and a dehumidifier's slider filling to the end.
     */
    private fun bindHumidifier(s: EntityState?) {
        val attrs = s?.attributes.orEmpty()
        val state = s?.state ?: "unavailable"
        fun num(key: String) = (attrs[key] as? Number)?.toDouble()
        val target = num("humidity")?.takeIf { state != "unavailable" }
        dial.dehumidifier = attrs["device_class"] == "dehumidifier"
        dial.bind(
            min = num("min_humidity") ?: 0.0,
            max = num("max_humidity") ?: 100.0,
            step = num("target_humidity_step")?.takeIf { it > 0 } ?: 1.0,
            current = num("current_humidity"),
            target = target,
            mode = state,
            action = attrs["action"] as? String,
            stateLabel = s?.formattedState().orEmpty(),
        )
        hasTarget = target != null
        showSteppers()
        stepButtons?.let { (minus, plus) ->
            minus.contentDescription = "Lower target humidity"
            plus.contentDescription = "Raise target humidity"
        }
    }

    /**
     * HA's − and + for the target, or for the selected end of a range: named after it ("Increase lower target
     * temperature") and outlined in its color (heat for the low end, cool for the high one) while the thermostat is on.
     */
    private fun styleSteppers() {
        if (humid) return
        val (minus, plus) = stepButtons ?: return
        val handle = dial.selected.takeIf { dial.isRange }
        val outline = handle?.let { dial.handleColor(it) } ?: ctx.theme.secondaryTextColor
        for ((view, sign) in listOf(minus to -1, plus to 1)) {
            view.contentDescription = stepperLabel(sign, handle)
            (view.background as? GradientDrawable)?.setStroke(ctx.px(1f).coerceAtLeast(1), outline)
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
                    // ha-control-select: one radio per mode, checked on the current one.
                    VitroAccess.role(this, A11yRole.RADIO) { m == currentMode }
                    addView(icon, LayoutParams(ctx.px(22f), ctx.px(22f), Gravity.CENTER))
                    setOnClickListener {
                        ctx.callService("climate", "set_hvac_mode", JSONObject().put("entity_id", eid).put("hvac_mode", m), null)
                    }
                }
                modeBar.addView(cell, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
                modeButtons += cell to icon
            }
        }
        currentMode = current
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

        /** HA's names for the − and + (`ui.components.climate-control.*`): the target, or one end of a range. */
        internal fun stepperLabel(sign: Int, handle: ClimateHandle?): String {
            val verb = if (sign > 0) "Increase" else "Decrease"
            return when (handle) {
                null -> "$verb temperature"
                ClimateHandle.LOW -> "$verb lower target temperature"
                ClimateHandle.HIGH -> "$verb upper target temperature"
            }
        }

        /** `hass.config.unit_system.temperature`, else °C. */
        fun temperatureUnit(ctx: VitroCardContext): String =
            runCatching { ctx.haConfig().optJSONObject("unit_system")?.optString("temperature", "") }.getOrNull()
                ?.ifEmpty { null } ?: "°C"
    }
}

/** Native HA `weather-forecast` (`hui-weather-forecast-card`) + `custom:clock-weather-card`. */
class WeatherCardView(ctx: VitroCardContext, config: CardConfig) : CardSurface(ctx) {
    private val forecastRow = LinearLayout(ctx.context).apply { orientation = LinearLayout.HORIZONTAL }
    private var forecastFromSocket = false

    /**
     * HA's size classes (`_sizeController`): `tall` from 235 px of height (a 64 px icon, a 28 px state and
     * temperature, 40 px forecast icons), else `short` (48, 24 and 32 px). A card the grid gives a height is measured
     * by it; one sized by its content is short, as HA's own resize settles.
     */
    private var tall = false
    private val headerIcon = WeatherIconView(ctx.context).apply { themeVars = ctx.theme.vars; fallbackColor = ctx.theme.primaryTextColor }
    private val condition = label(ctx, 24f, ctx.theme.primaryTextColor).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val temp = label(ctx, 24f, ctx.theme.primaryTextColor)
    private val tempUnit = label(ctx, 24f, ctx.theme.primaryTextColor)
    private var lastDays: List<ForecastDay> = emptyList()
    private var lastHourly = false
    private var lastUnit = "°C"

    /** The forecast shown (empty without one, or with show_forecast: false), for the line under the temperature. */
    private var forecast: List<ForecastDay> = emptyList()

    /** Fills the line under the temperature from the entity's state and [forecast] (set by the init block). */
    private var updateMetric: () -> Unit = {}

    /** HA's weather-forecast card warns while its entity is unavailable; clock-weather-card (same view) doesn't. */
    private val warnsUnavailable = config.type == "weather-forecast"

    /** The conditions and the forecast; hidden while [unavailable]. */
    private val body = LinearLayout(ctx.context)

    /**
     * HA's `ha-card.unavailable`: "Entity is currently unavailable: <name> (<entity>)" in 16 px, centered in a
     * 100 px card (`height: 100px; padding: 10px 20px`), whatever its grid slot's height ([UnavailableSurface]).
     */
    private val unavailableText = label(ctx, 16f, ctx.theme.primaryTextColor).apply {
        gravity = Gravity.CENTER
        visibility = GONE
        setPadding(ctx.px(20f), ctx.px(10f), ctx.px(20f), ctx.px(10f))
    }
    private var unavailable = false

    init {
        // hui-weather-forecast-card: 16 px around; the current conditions centered in the card's height.
        val col = body.apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.px(16f), ctx.px(16f), ctx.px(16f), ctx.px(16f))
        }
        val top = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val icon = headerIcon
        // The state (24 px short, 28 px tall, on a 1.2 line) over the name (14 px / 16.8); the temperature the same
        // size with its unit in 24 px, 1 px down from its top, over the attribute (a 20 px icon and 14 px text); the
        // icon (48 or 64 px) 16 px before them, 12 px between the two columns.
        val name = label(ctx, 14f, ctx.theme.secondaryTextColor).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val metric = label(ctx, 14f, ctx.theme.secondaryTextColor).apply { gravity = Gravity.END }
        val metricIcon = MdiIconView(ctx.context)
        fun line(view: View, heightDp: Float) = FrameLayout(ctx.context).apply {
            addView(view, LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER_VERTICAL))
            minimumHeight = ctx.px(heightDp)
        }
        top.addView(icon, LinearLayout.LayoutParams(ctx.px(48f), ctx.px(48f)).apply { marginEnd = ctx.px(16f) })
        top.addView(LinearLayout(ctx.context).apply {
            orientation = LinearLayout.VERTICAL
            addView(condition, LinearLayout.LayoutParams(WRAP_CONTENT, (28.8f * ctx.dp).roundToInt()))
            addView(line(name, 16.8f))
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        condition.gravity = Gravity.CENTER_VERTICAL
        temp.gravity = Gravity.CENTER_VERTICAL
        tempUnit.gravity = Gravity.CENTER_VERTICAL
        val metricRow = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            minimumHeight = ctx.px(20f)
            addView(metricIcon, LinearLayout.LayoutParams(ctx.px(20f), ctx.px(20f)))
            addView(metric, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
        top.addView(LinearLayout(ctx.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            // `.temp span { position: absolute; top: 1px }` after the number, in its 24 px `margin-right`.
            addView(LinearLayout(ctx.context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(temp, LinearLayout.LayoutParams(WRAP_CONTENT, (28.8f * ctx.dp).roundToInt()))
                addView(tempUnit, LinearLayout.LayoutParams(WRAP_CONTENT, (28.8f * ctx.dp).roundToInt()).apply { topMargin = ctx.px(1f) })
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { gravity = Gravity.END })
            addView(metricRow, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { gravity = Gravity.END })
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = ctx.px(12f) })
        col.addView(top, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val showForecast = config.raw.optBoolean("show_forecast", true)
        if (showForecast) {
            col.addView(forecastRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ctx.px(12f) })
        }
        addView(col, LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER_VERTICAL))
        // The 100 px box is the card's border box: the text centers in what's inside the border.
        addView(unavailableText, LayoutParams(MATCH_PARENT, ctx.px(UNAVAILABLE_DP) - 2 * (ctx.theme.cardBorderWidthDp * ctx.dp).toInt(), Gravity.TOP))

        val eid = config.entity
        if (eid != null) {
            isClickable = true
            setOnClickListener { ctx.performAction(ActionConfig.parse(config.raw.optJSONObject("tap_action"), "more-info"), eid) }
            bindHoldAction(holdFor(config.raw, eid), eid)
            observe(eid) { s ->
                val state = s?.state.orEmpty()
                setUnavailable(warnsUnavailable && state == "unavailable")
                if (unavailable) {
                    unavailableText.text = HaText.ui(
                        "ui.panel.lovelace.warning.entity_unavailable",
                        "entity" to "${config.name(s) ?: s?.displayName ?: eid} ($eid)",
                    )
                }
                icon.setCondition(state)
                // HA's formatEntityState: the weather domain's own words, in the user's language.
                condition.text = s?.let { HaDisplay.stateLabel(it) }?.takeIf { it.isNotEmpty() && it != state } ?: conditionLabel(state)
                // HA's weather-forecast card: formatEntityName (device and entity names) when no `name` is set.
                name.text = config.name(s) ?: s?.displayName ?: eid
                val t = s?.attributes?.get("temperature")
                val u = (s?.attributes?.get("temperature_unit") as? String) ?: "°C"
                temp.text = if (t != null) "${(t as? Number)?.let { HaDisplay.formatValue(it.toDouble(), 1) } ?: t} " else "–"
                tempUnit.text = if (t != null) u else ""
                updateMetric = { showMetric(s, u, metric, metricIcon, metricRow) }
                updateMetric()
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
                // Held open while the card shows, as HA's card holds `subscribeForecast`: each list the integration
                // pushes (most do every half hour or hour) is drawn, so the first column follows the clock. Ended on
                // detach, asked for again on attach, and sent again by the client on a new connection.
                renewing {
                    ctx.subscribeForecast(eid, type) { days ->
                        ctx.main.post {
                            // An empty push keeps what shows: before the first list, the legacy `forecast` attribute;
                            // after it, the last list the integration sent.
                            if (days.isEmpty()) return@post
                            forecastFromSocket = true
                            val u = (ctx.store[eid]?.attributes?.get("temperature_unit") as? String) ?: "°C"
                            renderForecast(days, u, hourly = type == "hourly")
                        }
                    }
                }
            }
        }
    }

    /**
     * The line under the temperature, as HA's weather card builds it (no secondary_info_attribute): today's high and
     * low from the forecast ("15.2 °C / 8.1 °C"), else the first forecast entry's precipitation, else the humidity,
     * these two with their icon.
     */
    private fun showMetric(s: EntityState?, unit: String, metric: TextView, icon: MdiIconView, row: View) {
        val (high, low) = extrema(forecast, ctx.now())
        val text: String
        var iconName: String? = null
        // A humidity an integration reports as text ("55", or a word) is a number only if it reads as one: no cast
        // that throws on the main thread for a template or custom weather entity.
        val humidity = when (val h = s?.attributes?.get("humidity")) {
            is Number -> h.toDouble()
            is String -> h.trim().toDoubleOrNull()
            else -> null
        }?.takeIf { it.isFinite() }
        when {
            high != null || low != null -> text = listOfNotNull(high, low).joinToString(" / ") { "${formatNumber(it)} $unit" }
            forecast.firstOrNull()?.precipitation != null -> {
                val precipUnit = (s?.attributes?.get("precipitation_unit") as? String).orEmpty()
                text = "${formatNumber(forecast.first().precipitation!!)} $precipUnit".trim()
                iconName = "mdi:weather-rainy"
            }
            humidity != null -> {
                text = "${formatNumber(humidity)}%"
                iconName = "mdi:water-percent"
            }
            else -> text = ""
        }
        metric.text = text
        icon.visibility = if (iconName != null) VISIBLE else GONE
        iconName?.let { icon.setIcon(it, ctx.theme.secondaryTextColor) }
        row.visibility = if (text.isEmpty()) GONE else VISIBLE
    }

    /** [hourly] comes from the `forecast_type` asked for, as HA decides; only the legacy attribute has to guess. */
    private fun renderForecast(days: List<ForecastDay>, unit: String, hourly: Boolean = days.size >= 2 && sameDay(days[0].datetime, days[1].datetime)) {
        forecast = days
        lastDays = days
        lastHourly = hourly
        lastUnit = unit
        updateMetric()
        forecastRow.removeAllViews()
        // `.forecast-item`: a 4 px-gap column of the day (12 px), the icon (40 px with 6 px around when tall, 32 with
        // 4 short), the high (16 px) and the low (14 px on a 22.4 px line), each as HA formats the number.
        val iconDp = if (tall) 40f else 32f
        val iconPad = if (tall) 6f else 4f
        for (day in days.take(5)) {
            val cell = LinearLayout(ctx.context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            cell.addView(label(ctx, 12f, ctx.theme.secondaryTextColor).apply { text = forecastLabel(day.datetime, hourly); gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(WRAP_CONTENT, ctx.px(12f)))
            cell.addView(WeatherIconView(ctx.context).apply { themeVars = ctx.theme.vars; fallbackColor = ctx.theme.primaryTextColor; setCondition(day.condition.orEmpty()) },
                LinearLayout.LayoutParams(ctx.px(iconDp), ctx.px(iconDp)).apply { topMargin = ctx.px(4f + iconPad); bottomMargin = ctx.px(iconPad) })
            cell.addView(label(ctx, 16f, ctx.theme.primaryTextColor).apply {
                text = day.temperature?.let { forecastNumber(it) + "°" } ?: "–"
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(WRAP_CONTENT, ctx.px(16f)).apply { topMargin = ctx.px(4f) })
            day.templow?.let { low ->
                cell.addView(label(ctx, 14f, ctx.theme.secondaryTextColor).apply { text = forecastNumber(low) + "°"; gravity = Gravity.CENTER },
                    LinearLayout.LayoutParams(WRAP_CONTENT, (22.4f * ctx.dp).roundToInt()).apply { topMargin = ctx.px(4f) })
            }
            forecastRow.addView(cell, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        forecastRow.contentDescription = "Forecast in $unit"
    }

    override fun measureCard(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val t = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY && MeasureSpec.getSize(heightMeasureSpec) / ctx.dp >= 235f
        if (t != tall) {
            tall = t
            applySize()
        }
        if (unavailable && MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.EXACTLY) {
            // Sized by its content (masonry): the 100 px card, as HA's.
            super.measureCard(widthMeasureSpec, MeasureSpec.makeMeasureSpec(ctx.px(UNAVAILABLE_DP), MeasureSpec.EXACTLY))
            return
        }
        super.measureCard(widthMeasureSpec, heightMeasureSpec)
    }

    override fun layoutCard(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.layoutCard(changed, left, top, right, bottom)
        fitSurface()
    }

    private fun setUnavailable(on: Boolean) {
        if (on == unavailable) return
        unavailable = on
        body.visibility = if (on) GONE else VISIBLE
        unavailableText.visibility = if (on) VISIBLE else GONE
        requestLayout()
    }

    /**
     * A grid slot taller than 100 px keeps its height (HA's `hui-card` fills it), but the card's surface, border and
     * outline end 100 px down, as `ha-card.unavailable`'s `height: 100px` does: the theme's (or card-mod's) surface
     * drawn inset from the bottom. Back to the whole slot once the entity is available.
     */
    private fun fitSurface() {
        val bg = background
        val base = (bg as? UnavailableSurface)?.surface ?: bg
        val inset = if (unavailable) height - ctx.px(UNAVAILABLE_DP) else 0
        if (inset <= 0 || base == null) {
            if (bg is UnavailableSurface) background = base
            return
        }
        if (bg is UnavailableSurface && bg.inset == inset) return
        background = UnavailableSurface(base, inset)
    }

    /** [surface] drawn [inset] px short of the bottom; adds no padding to the card (an [InsetDrawable] would). */
    private class UnavailableSurface(val surface: Drawable, val inset: Int) : InsetDrawable(surface, 0, 0, 0, inset) {
        override fun getPadding(padding: Rect): Boolean {
            padding.set(0, 0, 0, 0)
            return false
        }
    }

    private fun applySize() {
        val iconDp = if (tall) 64f else 48f
        headerIcon.layoutParams = (headerIcon.layoutParams as LinearLayout.LayoutParams).apply { width = ctx.px(iconDp); height = ctx.px(iconDp) }
        val big = if (tall) 28f else 24f
        for (v in listOf(condition, temp)) {
            v.textSize = ctx.scaledSp(big)
            v.typeface = VitroFonts.typeface(ctx.context, Inter.REGULAR, ctx.scaledSp(big))
            v.layoutParams = v.layoutParams.apply { height = (big * 1.2f * ctx.dp).roundToInt() }
        }
        (forecastRow.layoutParams as? LinearLayout.LayoutParams)?.topMargin = ctx.px(if (tall) 16f else 12f)
        if (lastDays.isNotEmpty()) renderForecast(lastDays, lastUnit, lastHourly)
    }

    companion object {
        /** `ha-card.unavailable`'s height. */
        private const val UNAVAILABLE_DP = 100f

        /**
         * HA's `getWeatherExtrema` (`data/weather.ts`): today's high and low over the forecast's first entries that
         * fall on today ([nowMs]'s day of the month, in the device's time zone as the browser's `getDate()`), stopping at
         * the first that doesn't. JavaScript's falsy checks are kept: a 0 °C high or low counts as none, as in HA, and
         * an entry without a `templow` (or with 0) offers its temperature as the low.
         */
        internal fun extrema(forecast: List<ForecastDay>, nowMs: Long): Pair<Double?, Double?> {
            val cal = java.util.Calendar.getInstance()
            cal.timeInMillis = nowMs
            val today = cal.get(java.util.Calendar.DAY_OF_MONTH)
            var high: Double? = null
            var low: Double? = null
            fun unset(v: Double?) = v == null || v == 0.0 || v.isNaN()
            for (day in forecast) {
                val date = parseIso(day.datetime) ?: break
                cal.time = date
                if (cal.get(java.util.Calendar.DAY_OF_MONTH) != today) break
                val t = day.temperature
                if (t != null && (unset(high) || t > high!!)) high = t
                val tl = day.templow
                if (tl != null && (unset(low) || tl < low!!)) low = tl
                if (unset(tl) && t != null && (unset(low) || t < low!!)) low = t
            }
            return high.takeUnless { unset(it) } to low.takeUnless { unset(it) }
        }

        /** HA's English condition names, until the weather domain's translations are in. */
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

        /** HA's `formatNumber` of a forecast value: as given, up to three decimals, grouped ("16.7", "1,012"). */
        internal fun forecastNumber(v: Double): String = HaDisplay.formatValue(v, 3)

        /** A number as HA's formatNumber shows it: no trailing ".0", one decimal kept ("76", "12.2"). */
        private fun formatNumber(v: Double): String = HaDisplay.formatValue(v, 1)

        private fun parseIso(iso: String): Date? = runCatching {
            val clean = iso.replace(Regex("\\.\\d+"), "").replace("Z", "+00:00")
            val fixed = if (clean.length > 19 && (clean[19] == '+' || clean[19] == '-')) clean.substring(0, 22) + clean.substring(23) else clean
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).parse(fixed)
        }.getOrNull()

        private fun sameDay(a: String, b: String): Boolean = a.length >= 10 && b.length >= 10 && a.substring(0, 10) == b.substring(0, 10)

        /** HA's forecast label: `formatDateWeekdayShort` ("Wed") for a day, `formatTime` ("2:00 PM") for an hour. */
        fun forecastLabel(iso: String, hourly: Boolean): String {
            val date = parseIso(iso) ?: return iso.take(10)
            return if (hourly) HaFormat.formatTime(date.time) else HaFormat.formatDateWeekdayShort(date.time)
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
    decimals != null -> HaDisplay.formatFixed(v, decimals.coerceIn(0, 6))
    v == Math.rint(v) && kotlin.math.abs(v) < 1e12 -> HaDisplay.formatFixed(v, 0)
    kotlin.math.abs(v) >= 100.0 -> HaDisplay.formatFixed(v, 0)
    else -> HaDisplay.formatFixed(v, 1)
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
        nameView.text = config.name(s) ?: s?.friendlyName ?: eid
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
