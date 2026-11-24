/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.cards

import dev.glassworks.engine.vitro.ui.NumberText
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.data.HaDisplay
import dev.glassworks.engine.vitro.model.ActionConfig
import dev.glassworks.engine.vitro.model.AreaCardConfig
import dev.glassworks.engine.vitro.model.CardConfig
import dev.glassworks.engine.vitro.ui.CardSurface
import dev.glassworks.engine.vitro.ui.MdiIconView
import dev.glassworks.engine.vitro.ui.MdiIcons
import dev.glassworks.engine.vitro.ui.VitroCardContext
import dev.glassworks.engine.vitro.ui.bindHoldAction
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.ui.Inter
import dev.glassworks.engine.vitro.ui.VitroFonts
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

private fun extLabel(ctx: VitroCardContext, sp: Float, color: Int, weight: Int = Inter.REGULAR) =
    TextView(ctx.context).apply {
        textSize = sp
        setTextColor(color)
        typeface = VitroFonts.typeface(ctx.context, weight, sp)
        includeFontPadding = false
    }

private fun pillBackground(color: Int, radius: Float = 999f) = GradientDrawable().apply {
    cornerRadius = radius
    setColor(color)
}

/** The icon color of an entity in HA: the domain's active color when on, else `--state-icon-color`. */
private fun iconColorFor(ctx: VitroCardContext, s: EntityState?): Int =
    if (s?.isActive == true) ctx.theme.activeColor(s.domain) else ctx.theme.stateIconColor

// ---------------------------------------------------------------------------------------------
// entity
// ---------------------------------------------------------------------------------------------

/**
 * Native HA `entity` card (`hui-entity-card`), and the `sensor` card, which is an entity card with a graph footer
 * (`hui-sensor-card`): the name (16 px, medium, secondary) on a 40 px line under 8 px of padding with the icon on
 * its right, the value (28 px) and its unit (16 px, secondary) on one baseline 4 px higher, 16 px in from the
 * sides and above the bottom. A `footer: {type: graph}` (or the sensor card's `graph: line`) draws
 * [GraphFooterView] along the bottom: pinned to it in a sections grid, where the card fills its slot (2 rows by
 * default), and below the value elsewhere.
 */
class EntityCardView(ctx: VitroCardContext, private val config: CardConfig) : CardSurface(ctx) {
    private val nameView = extLabel(ctx, 16f, ctx.theme.secondaryTextColor, Inter.MEDIUM).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_VERTICAL
    }
    private val iconView = MdiIconView(ctx.context)
    private val valueView = extLabel(ctx, 28f, ctx.theme.primaryTextColor, Inter.REGULAR).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val unitView = extLabel(ctx, 16f, ctx.theme.secondaryTextColor).apply { maxLines = 1 }
    private val footerConfig: JSONObject? =
        if (config.type == "sensor") GraphFooterView.sensorFooter(config.raw)
        else config.raw.optJSONObject("footer")?.takeIf { it.optString("type") == "graph" }
    private val footer: GraphFooterView? = footerConfig?.let { GraphFooterView(ctx, it) }

    /** HA pins the footer to the bottom only in a sections grid (`layout === "grid"`), where the card fills its slot. */
    private val fixedFooter = footer != null && ctx.cardLayout == "grid"

    init {
        val col = LinearLayout(ctx.context).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.px(16f), ctx.px(8f), ctx.px(16f), 0)
            addView(nameView, LinearLayout.LayoutParams(0, ctx.px(40f), 1f))
            addView(iconView, LinearLayout.LayoutParams(ctx.px(24f), ctx.px(24f)))
        }
        col.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        // `.info`: baseline-aligned value and unit, line-height 1.2, 4 px up into the header's padding.
        valueView.setLineSpacing(0f, 1f)
        val info = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = true
            setPadding(ctx.px(16f), 0, ctx.px(16f), ctx.px(16f))
            addView(valueView, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, 0f))
            addView(unitView, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = ctx.px(4f) })
        }
        lineBox(valueView, 28f)
        lineBox(unitView, 16f)
        col.addView(info, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = -ctx.px(4f) })
        if (footer != null && !fixedFooter) col.addView(footer, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(col, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        if (footer != null && fixedFooter) addView(footer, LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))

        val eid = config.entity
        isClickable = true
        setOnClickListener { ctx.performAction(ActionConfig.parse(config.raw.optJSONObject("tap_action"), "more-info"), eid) }
        bindHoldAction(ActionConfig.parse(config.raw.optJSONObject("hold_action"), "more-info"), eid)
        enablePressFeedback()
        if (eid != null) observe(eid, ::render) else render(null)
    }

    /** A line box of `--ha-line-height-condensed` (1.2) around [tv]'s [sizeCss] px text, as `.info` sets it. */
    private fun lineBox(tv: TextView, sizeCss: Float) {
        val fm = tv.paint.fontMetrics
        val content = fm.descent - fm.ascent
        val half = ((1.2f * sizeCss * ctx.dp * ctx.theme.fontScale - content) / 2f).coerceAtLeast(0f).toInt()
        tv.setPadding(0, half, 0, half)
    }

    /** HA's `_getStateColor`: `color`, `state_color`, or (the entity card's default) a light; never for the sensor card. */
    private fun colored(s: EntityState?): Boolean {
        if (s == null) return false
        val raw = config.raw
        if (raw.optString("color").isNotEmpty()) return true
        if (raw.has("state_color")) return raw.optBoolean("state_color")
        return config.type != "sensor" && s.domain == "light"
    }

    /**
     * HA's `_computeColor`: an explicit `color` (a theme color name or any CSS color) while the entity is active, else
     * none; `color: state` (or no color) the state's own color.
     */
    private fun stateColor(s: EntityState?): Int? {
        if (s == null) return null
        val cfg = config.raw.optString("color").trim()
        if (cfg.isNotEmpty() && cfg != "state") {
            if (!s.isActive) return null
            return ThemeResolver.parseColor(ctx.theme.vars["$cfg-color"]) ?: MushroomColors.parse(cfg, ctx.theme)
        }
        // A domain HA doesn't color by state (a plain sensor; a battery sensor is) has no state color.
        if (!HaTimelineChart.isStateColored(s)) return null
        // The reported color comes before the domain's (`rgb_color`, as a light reports it).
        val rgb = (s.attributes["rgb_color"] as? List<*>)?.mapNotNull { (it as? Number)?.toInt() }
        if (rgb != null && rgb.size >= 3) return android.graphics.Color.rgb(rgb[0], rgb[1], rgb[2])
        return iconColorFor(ctx, s)
    }

    private fun render(s: EntityState?) {
        nameView.text = config.name ?: s?.displayName ?: config.entity.orEmpty()
        val color = if (colored(s)) stateColor(s) else null
        iconView.setIcon(MdiIcons.resolveIconName(config.icon, s), color ?: ctx.theme.stateIconColor)
        val (value, unit) = valueAndUnit(config.raw, s)
        valueView.text = value
        unitView.text = unit
        unitView.visibility = if (unit.isEmpty()) GONE else VISIBLE
        // The footer takes the card's color only when the card sets `color` and has one (HA's `--accent-color`
        // override); otherwise, on every render, the theme's accent.
        footer?.color = color?.takeIf { config.raw.optString("color").isNotEmpty() } ?: ctx.theme.accentColor
    }

    companion object {
        /** HA's entity card value: the `attribute` if set, else the state; the unit from config or the entity. */
        fun valueAndUnit(raw: JSONObject, s: EntityState?): Pair<String, String> {
            if (s == null) return "–" to ""
            val unitCfg = raw.optString("unit", "")
            val attr = raw.optString("attribute", "")
            if (attr.isNotEmpty()) {
                val v = s.attributes[attr]?.toString() ?: "–"
                return v to unitCfg
            }
            val unit = unitCfg.ifEmpty { s.unit.orEmpty() }
            if (unit.isNotEmpty() && !s.isUnavailable) {
                // formatEntityStateToParts: the number as HA formats it (display precision, grouping), the unit apart.
                val number = s.state.trim().toBigDecimalOrNull()?.let {
                    HaDisplay.formatState(s.state, HaDisplay.displayPrecision(s.entityId), (s.attributes["step"] as? Number)?.toDouble())
                }
                return (number ?: s.state) to unit
            }
            return s.formattedState() to ""
        }
    }
}

// ---------------------------------------------------------------------------------------------
// light
// ---------------------------------------------------------------------------------------------

/**
 * Native HA `light` card (`hui-light-card`): full width; a 200 px `ha-control-circular-slider` (brightness) around a
 * 120 px light button with the entity's icon (no dial for an on/off light), the ⋮ more-info button top right, and
 * under them the brightness (kept in place when hidden) and the name, 16.8 px, pulled up 56 px over the controls.
 */
class LightCardView(ctx: VitroCardContext, private val config: CardConfig) : CardSurface(ctx) {
    // Both lines sit on HA's 1.6 line height (26.9 px).
    private val nameView = extLabel(ctx, 16.8f, ctx.theme.primaryTextColor).apply {
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        minHeight = ctx.px(26.9f)
    }
    private val brightnessView = extLabel(ctx, 16.8f, ctx.theme.primaryTextColor).apply {
        gravity = Gravity.CENTER
        minHeight = ctx.px(26.9f)
    }
    private val dial = BrightnessDialView(ctx.context, ctx.theme.activeColor("light"), ctx.theme.disabledTextColor, ctx.theme.stateIconColor, ctx.theme.primaryColor)

    init {
        val col = LinearLayout(ctx.context).apply { orientation = LinearLayout.VERTICAL }
        // #controls: 16 px around the 200 px slider.
        col.addView(FrameLayout(ctx.context).apply {
            setPadding(ctx.px(16f), ctx.px(16f), ctx.px(16f), ctx.px(16f))
            addView(dial, FrameLayout.LayoutParams(ctx.px(200f), ctx.px(200f), Gravity.CENTER_HORIZONTAL))
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        // #info: margin-top -56 px, 16 px padding, the brightness then the name.
        val labels = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ctx.px(16f), ctx.px(16f), ctx.px(16f), ctx.px(16f))
            addView(brightnessView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(nameView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        col.addView(labels, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = -ctx.px(56f) })
        addView(col, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val eid = config.entity
        // The more-info button: a 48 px ⋮ in the secondary text color, top right.
        addView(FrameLayout(ctx.context).apply {
            isClickable = true
            contentDescription = "More info"
            addView(MdiIconView(ctx.context).apply { setIcon("mdi:dots-vertical", ctx.theme.secondaryTextColor) },
                FrameLayout.LayoutParams(ctx.px(24f), ctx.px(24f), Gravity.CENTER))
            setOnClickListener { eid?.let(ctx.openMoreInfo) }
            applyHaRipple(this, ctx.px(24f).toFloat(), ctx.theme.dark)
        }, LayoutParams(ctx.px(48f), ctx.px(48f), Gravity.TOP or Gravity.END))

        labels.isClickable = true
        labels.setOnClickListener { ctx.performAction(ActionConfig.parse(config.raw.optJSONObject("tap_action"), "more-info"), eid) }
        labels.bindHoldAction(ctx, ActionConfig.parse(config.raw.optJSONObject("hold_action"), "more-info"), eid)
        if (eid != null) {
            dial.onToggle = { ctx.toggleEntity(eid) }
            dial.onBrightness = { pct -> setBrightness(eid, pct) }
            observe(eid, ::render)
        } else {
            render(null)
        }
    }

    private fun setBrightness(eid: String, pct: Int) {
        val cur = ctx.store[eid]
        if (pct <= 0) {
            ctx.callOptimistic(cur?.copy(state = "off"), "light", "turn_off", JSONObject().put("entity_id", eid))
            return
        }
        val bri = (pct * 255 / 100).coerceIn(1, 255)
        ctx.callOptimistic(cur?.let { it.copy(state = "on", attributes = it.attributes + ("brightness" to bri)) }, "light", "turn_on", JSONObject().put("entity_id", eid).put("brightness", bri))
    }

    private fun render(s: EntityState?) {
        nameView.text = config.name ?: s?.displayName ?: config.entity.orEmpty()
        val on = s?.state == "on"
        val pct = s?.brightnessPct
        // HA's brightness line: the percentage while on, hidden (its place kept) otherwise.
        brightnessView.text = if (on && pct != null) "$pct%" else ""
        brightnessView.visibility = if (on && pct != null) VISIBLE else INVISIBLE
        val modes = (s?.attributes?.get("supported_color_modes") as? List<*>)?.map { it.toString() }
        val dims = modes == null || modes.any { it != "onoff" }
        dial.update(on, if (on && pct != null) pct / 100f else 0f, MdiIcons.resolveIconName(config.icon, s), s?.lightTintColor(), dims)
        dial.contentDescription = when {
            s == null || s.isUnavailable -> s?.formattedState() ?: "–"
            !on -> "Off"
            dims && pct != null -> "Brightness $pct%"
            else -> "On"
        }
    }
}

/**
 * HA's `ha-control-circular-slider` for a light (320-unit geometry): a 270° track (radius 145, stroke 24) in the
 * disabled color, the brightness arc in the light's color, a white handle (radius 9) in a 3-unit ring of the
 * primary color; the light button's icon (118 px of 200) centered, in the light's color when on, else the
 * state-icon color. Without [dims] (an on/off light) only the icon. Drag the ring to set brightness, tap the icon
 * to toggle.
 */
internal class BrightnessDialView(
    context: Context,
    private val activeColor: Int,
    private val trackColor: Int,
    private val offIconColor: Int,
    private val handleRingColor: Int,
) : View(context) {
    private val dp = resources.displayMetrics.density
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val oval = RectF()
    private val iconMatrix = Matrix()
    private val iconPath = Path()
    private var basePath: Path = MdiIcons.pathFor("lightbulb")
    private var on = false
    private var fraction = 0f
    private var tint: Int? = null
    private var dims = true
    private var dragFraction: Float? = null
    private var touchMode = TOUCH_NONE

    var onToggle: (() -> Unit)? = null
    var onBrightness: ((Int) -> Unit)? = null

    fun update(isOn: Boolean, frac: Float, iconName: String, lightColor: Int? = null, dimmable: Boolean = true) {
        on = isOn
        fraction = frac.coerceIn(0f, 1f)
        basePath = MdiIcons.pathFor(iconName)
        tint = lightColor
        dims = dimmable
        if (dragFraction == null) invalidate()
    }

    /** The dial's size in px (the view is square). */
    private val size: Float get() = minOf(width, height).toFloat()
    private val radius: Float get() = size * 145f / 320f

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val u = size / 320f
        val color = tint ?: activeColor
        if (dims) {
            val r = radius
            oval.set(cx - r, cy - r, cx + r, cy + r)
            arcPaint.strokeWidth = 24f * u
            arcPaint.color = trackColor
            canvas.drawArc(oval, START_ANGLE, SWEEP, false, arcPaint)
            val shown = dragFraction ?: if (on) fraction else 0f
            if (shown > 0f) {
                arcPaint.color = color
                canvas.drawArc(oval, START_ANGLE, SWEEP * shown, false, arcPaint)
            }
            val a = Math.toRadians((START_ANGLE + SWEEP * shown).toDouble())
            val hx = cx + r * cos(a).toFloat()
            val hy = cy + r * sin(a).toFloat()
            fillPaint.color = handleRingColor
            canvas.drawCircle(hx, hy, 11.6f * u, fillPaint)
            fillPaint.color = 0xFFFFFFFF.toInt()
            canvas.drawCircle(hx, hy, 8.8f * u, fillPaint)
        }
        val lit = on || (dragFraction ?: 0f) > 0f
        // The light button's icon: 118 px of the 200 px dial.
        val scale = (118f / 200f) * size / 24f
        iconMatrix.reset()
        iconMatrix.setScale(scale, scale)
        iconMatrix.postTranslate(cx - 12f * scale, cy - 12f * scale)
        iconPath.set(basePath)
        iconPath.transform(iconMatrix)
        fillPaint.color = if (lit) color else offIconColor
        canvas.drawPath(iconPath, fillPaint)
    }

    private fun fractionAt(x: Float, y: Float): Float {
        val deg = Math.toDegrees(atan2((y - height / 2f).toDouble(), (x - width / 2f).toDouble())).toFloat()
        val rel = ((deg - START_ANGLE) % 360f + 360f) % 360f
        val clamped = if (rel > SWEEP) (if (rel > SWEEP + (360f - SWEEP) / 2f) 0f else SWEEP) else rel
        return clamped / SWEEP
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val d = hypot(event.x - width / 2f, event.y - height / 2f)
        val r = radius
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchMode = when {
                    d <= r * 0.55f -> TOUCH_TOGGLE
                    dims && d <= r * 1.35f && d >= r * 0.6f -> TOUCH_DRAG
                    else -> TOUCH_NONE
                }
                if (touchMode == TOUCH_NONE) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                if (touchMode == TOUCH_DRAG) {
                    dragFraction = fractionAt(event.x, event.y)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> if (touchMode == TOUCH_DRAG) {
                dragFraction = fractionAt(event.x, event.y)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                when (touchMode) {
                    TOUCH_DRAG -> dragFraction?.let { onBrightness?.invoke((it * 100f).roundToInt()) }
                    TOUCH_TOGGLE -> if (d <= r * 0.6f) {
                        performClick()
                        onToggle?.invoke()
                    }
                }
                dragFraction = null
                touchMode = TOUCH_NONE
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                dragFraction = null
                touchMode = TOUCH_NONE
                invalidate()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    companion object {
        private const val START_ANGLE = 135f
        private const val SWEEP = 270f
        private const val TOUCH_NONE = 0
        private const val TOUCH_TOGGLE = 1
        private const val TOUCH_DRAG = 2
    }
}

// ---------------------------------------------------------------------------------------------
// alarm-panel
// ---------------------------------------------------------------------------------------------

/**
 * Native HA `alarm-panel` card (`hui-alarm-panel-card`), at HA's measured sizes: a 24 px title with the state chip
 * (32 px, the state's color, its shield icon), the arm or disarm buttons centered under it (32 px tonal pills), and
 * when the panel takes a code, a 150 x 56 code field over a keypad of 56 px round keys 16 px apart.
 */
class AlarmPanelCardView(ctx: VitroCardContext, private val config: CardConfig) : CardSurface(ctx) {
    private val titleView = extLabel(ctx, 24f, ctx.theme.primaryTextColor, Inter.REGULAR).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val chipIcon = MdiIconView(ctx.context)
    private val chipLabel = extLabel(ctx, 12.25f, 0xFFFFFFFF.toInt(), Inter.REGULAR).apply { maxLines = 1 }
    private val chip = LinearLayout(ctx.context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ctx.px(8f), 0, ctx.px(16f), 0)
        addView(chipIcon, LinearLayout.LayoutParams(ctx.px(18f), ctx.px(18f)).apply { marginEnd = ctx.px(8f) })
        addView(chipLabel)
    }
    private val actionsRow = LinearLayout(ctx.context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_HORIZONTAL
    }
    private val codeLabel = extLabel(ctx, 14f, ctx.theme.secondaryTextColor).apply { text = "Code" }
    private val codeView = extLabel(ctx, 14f, ctx.theme.primaryTextColor).apply { maxLines = 1 }
    private val codeField = FrameLayout(ctx.context).apply {
        // ha-input: a filled field, 4 px top corners, a 1 px indicator line along the bottom.
        background = android.graphics.drawable.LayerDrawable(arrayOf(
            GradientDrawable().apply {
                cornerRadii = floatArrayOf(4f * ctx.dp, 4f * ctx.dp, 4f * ctx.dp, 4f * ctx.dp, 0f, 0f, 0f, 0f)
                setColor(if (ctx.theme.dark) 0xFF363636.toInt() else 0xFFF5F5F5.toInt())
            },
            GradientDrawable().apply { setColor(if (ctx.theme.dark) 0xFFB1B1B1.toInt() else 0xFF6B6B6B.toInt()) },
        )).apply { setLayerInsetTop(1, ctx.px(55f)) }
        setPadding(ctx.px(16f), 0, ctx.px(16f), 0)
        addView(codeLabel, LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER_VERTICAL))
        addView(codeView, LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM).apply { bottomMargin = ctx.px(10f) })
    }
    private val keypad = LinearLayout(ctx.context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ctx.px(12f), ctx.px(12f), ctx.px(12f), ctx.px(12f))
    }

    /** HA's masked text field, for an alarm whose `code_format` is `text` (the keypad is for `number` codes). */
    private val textCode = EditText(ctx.context).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        setSingleLine(true)
        hint = "Code"
        setTextColor(ctx.theme.primaryTextColor)
        setHintTextColor(ctx.theme.secondaryTextColor)
        visibility = GONE
    }
    private val code = StringBuilder()
    private val armStates: List<String> = readStates(config.raw.optJSONArray("states"))

    init {
        val col = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, ctx.px(16f))
        }
        // h1.card-header: 20 / 16 / 24 px around the title and the chip.
        val header = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.px(16f), ctx.px(20f), ctx.px(16f), ctx.px(24f))
            addView(titleView, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(chip, LinearLayout.LayoutParams(WRAP_CONTENT, ctx.px(32f)).apply { marginStart = ctx.px(8f) })
        }
        col.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        col.addView(actionsRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        col.addView(codeField, LinearLayout.LayoutParams(ctx.px(150f), ctx.px(56f)).apply { topMargin = ctx.px(8f) })
        col.addView(textCode, LinearLayout.LayoutParams(ctx.px(200f), WRAP_CONTENT).apply { topMargin = ctx.px(8f) })
        buildKeypad()
        col.addView(keypad, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ctx.px(8f) })
        addView(col, LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val eid = config.entity
        chip.isClickable = true
        chip.setOnClickListener { eid?.let(ctx.openMoreInfo) }
        if (eid != null) observe(eid, ::render) else render(null)
    }

    private fun buildKeypad() {
        // HA's keypad: 1-9, then clear and 0; the last cell stays empty.
        val keys = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf(KEY_CLEAR, "0", ""))
        keys.forEachIndexed { rIdx, row ->
            val r = LinearLayout(ctx.context).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEachIndexed { cIdx, key ->
                val b: View = when (key) {
                    "" -> View(ctx.context)
                    KEY_CLEAR -> FrameLayout(ctx.context).apply {
                        addView(MdiIconView(ctx.context).apply { setIcon("mdi:close", ctx.theme.disabledTextColor) },
                            LayoutParams(ctx.px(24f), ctx.px(24f), Gravity.CENTER))
                    }
                    else -> extLabel(ctx, 24f, ctx.theme.primaryTextColor, Inter.MEDIUM).apply {
                        text = key
                        gravity = Gravity.CENTER
                    }
                }
                if (key.isNotEmpty()) {
                    b.background = TileCardView.featureFill(ctx).apply { cornerRadius = 28f * ctx.dp }
                    b.isClickable = true
                    b.contentDescription = if (key == KEY_CLEAR) "Clear" else key
                    b.setOnClickListener { onKey(key) }
                    applyHaRipple(b, 28f * ctx.dp, ctx.theme.dark)
                }
                r.addView(b, LinearLayout.LayoutParams(ctx.px(56f), ctx.px(56f)).apply { if (cIdx > 0) marginStart = ctx.px(16f) })
            }
            keypad.addView(r, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { if (rIdx > 0) topMargin = ctx.px(16f) })
        }
    }

    private fun onKey(key: String) {
        when (key) {
            KEY_CLEAR -> code.setLength(0)
            KEY_BACK -> if (code.isNotEmpty()) code.setLength(code.length - 1)
            else -> if (code.length < MAX_CODE) code.append(key)
        }
        renderCode()
    }

    private fun renderCode() {
        // The label floats to the top of the field once there's a code, as the input's label does.
        val empty = code.isEmpty()
        codeView.text = "•".repeat(code.length)
        codeView.visibility = if (empty) GONE else VISIBLE
        codeLabel.textSize = if (empty) 14f else 12f
        (codeLabel.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = if (empty) Gravity.CENTER_VERTICAL else Gravity.TOP
            topMargin = if (empty) 0 else ctx.px(8f)
            codeLabel.layoutParams = this
        }
    }

    private fun render(s: EntityState?) {
        titleView.text = config.name ?: s?.friendlyName ?: "Alarm"
        val state = s?.state ?: "unknown"
        chipLabel.text = s?.let { HaDisplay.stateLabel(it) }?.takeIf { it != state } ?: stateLabel(state)
        chipIcon.setIcon(stateIcon(state), 0xFFFFFFFF.toInt())
        chip.background = pillBackground(stateColor(state), 16f * ctx.dp)
        val codeFormat = s?.attributes?.get("code_format")?.toString()
        val needsCode = codeFormat != null
        val textFormat = codeFormat == "text"
        keypad.visibility = if (needsCode && !textFormat) VISIBLE else GONE
        codeField.visibility = if (needsCode && !textFormat) VISIBLE else GONE
        textCode.visibility = if (textFormat) VISIBLE else GONE
        renderCode()

        actionsRow.removeAllViews()
        val buttons = if (state == "disarmed" || s == null || s.isUnavailable) armStates else listOf("disarm")
        buttons.forEach { action ->
            // ha-button: a 32 px tonal pill, 14 px / 500, 12 px padding (disarm in HA's danger colors).
            val danger = action == "disarm"
            val fg = if (danger) 0xFFF3676C.toInt() else ctx.theme.primaryColor
            val bg = if (danger) 0xFF3E0913.toInt() else CardSurface.withAlpha(ctx.theme.primaryColor, 0.2f)
            val b = extLabel(ctx, 14f, fg, Inter.MEDIUM).apply {
                text = actionLabel(action)
                gravity = Gravity.CENTER
                setPadding(ctx.px(12f), 0, ctx.px(12f), 0)
                background = pillBackground(bg)
                setOnClickListener { run(action) }
                // After the listener, which makes the view clickable again: an unavailable alarm takes no action.
                isClickable = s != null && !s.isUnavailable
                isEnabled = isClickable
            }
            actionsRow.addView(b, LinearLayout.LayoutParams(WRAP_CONTENT, ctx.px(32f)).apply {
                setMargins(ctx.px(4f), ctx.px(4f), ctx.px(4f), ctx.px(4f))
            })
        }
    }

    private fun run(action: String) {
        val eid = config.entity ?: return
        val data = JSONObject().put("entity_id", eid)
        val entered = if (textCode.visibility == VISIBLE) textCode.text?.toString().orEmpty() else code.toString()
        if (entered.isNotEmpty()) data.put("code", entered)
        ctx.callService("alarm_control_panel", serviceFor(action), data, null)
        code.setLength(0)
        textCode.setText("")
        renderCode()
    }

    companion object {
        private const val KEY_CLEAR = "Clear"
        private const val KEY_BACK = "⌫"
        private const val MAX_CODE = 12

        /** HA's default `states` for the alarm panel card. */
        val DEFAULT_STATES = listOf("arm_home", "arm_away")

        fun readStates(arr: JSONArray?): List<String> {
            if (arr == null || arr.length() == 0) return DEFAULT_STATES
            return (0 until arr.length()).mapNotNull { arr.optString(it, "").ifEmpty { null } }.ifEmpty { DEFAULT_STATES }
        }

        /** `arm_home` → `alarm_arm_home`, `disarm` → `alarm_disarm`. */
        fun serviceFor(action: String): String = "alarm_$action"

        fun actionLabel(action: String): String = when (action) {
            "disarm" -> "Disarm"
            "arm_home" -> "Arm home"
            "arm_away" -> "Arm away"
            "arm_night" -> "Arm night"
            "arm_vacation" -> "Arm vacation"
            "arm_custom_bypass" -> "Custom bypass"
            else -> action.replace('_', ' ').replaceFirstChar { it.titlecase(Locale.ROOT) }
        }

        fun stateLabel(state: String): String = when (state) {
            "disarmed" -> "Disarmed"
            "armed_home" -> "Armed home"
            "armed_away" -> "Armed away"
            "armed_night" -> "Armed night"
            "armed_vacation" -> "Armed vacation"
            "armed_custom_bypass" -> "Armed custom"
            "pending" -> "Pending"
            "arming" -> "Arming"
            "disarming" -> "Disarming"
            "triggered" -> "Triggered"
            else -> state.replace('_', ' ').replaceFirstChar { it.titlecase(Locale.ROOT) }
        }

        /** HA's `--state-alarm_control_panel-*-color`: armed green, changing orange, triggered red, else inactive. */
        fun stateColor(state: String): Int = when {
            state.startsWith("armed") -> 0xFF4CAF50.toInt()
            state == "pending" || state == "arming" || state == "disarming" -> 0xFFFF9800.toInt()
            state == "triggered" -> 0xFFF44336.toInt()
            else -> 0xFF9E9E9E.toInt()
        }

        /** HA's alarm_control_panel state icons. */
        fun stateIcon(state: String): String = when (state) {
            "armed_home" -> "mdi:shield-home"
            "armed_away" -> "mdi:shield-lock"
            "armed_night" -> "mdi:shield-moon"
            "armed_vacation" -> "mdi:shield-airplane"
            "armed_custom_bypass" -> "mdi:security"
            "pending", "arming" -> "mdi:shield-sync"
            "disarming" -> "mdi:shield-sync"
            "triggered" -> "mdi:bell-ring"
            "disarmed" -> "mdi:shield-off"
            else -> "mdi:shield"
        }
    }
}

// ---------------------------------------------------------------------------------------------
// todo-list
// ---------------------------------------------------------------------------------------------

/** One item of a `todo.*` list, as `todo/item/list` returns it. */
/** [pending]: added here and not yet listed by Home Assistant (its [uid] is made up; a real one can look alike). */
data class TodoItem(val uid: String, val summary: String, val completed: Boolean, val pending: Boolean = false)

/** Native HA `todo-list` card (`hui-todo-list-card`): live items, checkboxes, completed section and add row. */
class TodoListCardView(ctx: VitroCardContext, private val config: CardConfig) : CardSurface(ctx) {
    private val titleView = extLabel(ctx, 24f, ctx.theme.primaryTextColor)
    private val activeHeader = sectionHeader("Active", null)
    private val activeBox = LinearLayout(ctx.context).apply { orientation = LinearLayout.VERTICAL }
    private val completedDivider = View(ctx.context).apply { setBackgroundColor(ctx.theme.dividerColor) }
    private val completedHeader = sectionHeader("Completed") { anchor ->
        android.widget.PopupMenu(ctx.context, anchor).apply {
            menu.add("Clear checked items").setOnMenuItemClickListener { clearCompleted(); true }
            show()
        }
    }
    private val completedBox = LinearLayout(ctx.context).apply { orientation = LinearLayout.VERTICAL }
    private val input = EditText(ctx.context)
    private var items: List<TodoItem> = emptyList()
    private var fetchGeneration = 0
    private val hideCompleted = config.raw.optBoolean("hide_completed", false)
    /** `hide_create: true` drops the add-item row (display-only dashboards). */
    private val hideCreate = config.raw.optBoolean("hide_create", false)
    /** `max_items` caps the active rows; the rest collapse into a "+ N more" line. 0 shows them all. */
    private val maxItems = config.raw.optInt("max_items", 0).coerceAtLeast(0)
    private val addRow: LinearLayout

    /**
     * hui-todo-list-card as measured in HA: the add field on top (a 56 px filled input with its + button, 16 px
     * in), then 48 px "Active" / "Completed" headers with their menus over 40 px rows with 20 px square
     * checkboxes, a divider between the sections, and no title unless the card sets one.
     */
    init {
        val col = LinearLayout(ctx.context).apply { orientation = LinearLayout.VERTICAL }
        titleView.setPadding(ctx.px(16f), ctx.px(20f), ctx.px(16f), 0)
        titleView.visibility = if (config.title != null) VISIBLE else GONE
        titleView.text = config.title.orEmpty()
        col.addView(titleView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        addRow = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(ctx.px(16f), ctx.px(16f), ctx.px(16f), ctx.px(8f))
            if (hideCreate) visibility = GONE
        }
        input.apply {
            hint = "Add item"
            textSize = 14f
            typeface = VitroFonts.typeface(ctx.context, Inter.REGULAR, 14f)
            setTextColor(ctx.theme.primaryTextColor)
            setHintTextColor(ctx.theme.secondaryTextColor)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_DONE
            setPadding(ctx.px(16f), 0, ctx.px(56f), 0)
            background = null
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    addItem()
                    true
                } else {
                    false
                }
            }
        }
        val plus = FrameLayout(ctx.context).apply {
            isClickable = true
            contentDescription = "Add item"
            addView(MdiIconView(ctx.context).apply { setIcon("mdi:plus", 0xFFCCCCCC.toInt()) },
                FrameLayout.LayoutParams(ctx.px(24f), ctx.px(24f), Gravity.CENTER))
            setOnClickListener { addItem() }
            applyHaRipple(this, 999f, ctx.theme.dark)
        }
        val field = FrameLayout(ctx.context).apply {
            // ha-input: a filled field with 4 px top corners and a 1 px indicator line along the bottom.
            background = android.graphics.drawable.LayerDrawable(arrayOf(
                GradientDrawable().apply {
                    cornerRadii = floatArrayOf(4f * ctx.dp, 4f * ctx.dp, 4f * ctx.dp, 4f * ctx.dp, 0f, 0f, 0f, 0f)
                    setColor(if (ctx.theme.dark) 0xFF363636.toInt() else 0xFFF5F5F5.toInt())
                },
                GradientDrawable().apply { setColor(if (ctx.theme.dark) 0xFFB1B1B1.toInt() else 0xFF6B6B6B.toInt()) },
            )).apply { setLayerInsetTop(1, ctx.px(55f)) }
            addView(input, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(plus, FrameLayout.LayoutParams(ctx.px(32f), ctx.px(32f), Gravity.CENTER_VERTICAL or Gravity.END).apply {
                marginEnd = ctx.px(16f)
            })
        }
        addRow.addView(field, LinearLayout.LayoutParams(MATCH_PARENT, ctx.px(56f)))
        col.addView(addRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // The list starts 24 px under the field (measured: 8 px under the add row, then 16 to the first header).
        val list = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, ctx.px(16f), 0, ctx.px(8f))
        }
        list.addView(activeHeader, LinearLayout.LayoutParams(MATCH_PARENT, ctx.px(48f)))
        list.addView(activeBox, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        list.addView(completedDivider, LinearLayout.LayoutParams(MATCH_PARENT, ctx.px(1f).coerceAtLeast(1)).apply {
            topMargin = ctx.px(10f); bottomMargin = ctx.px(10f)
        })
        list.addView(completedHeader, LinearLayout.LayoutParams(MATCH_PARENT, ctx.px(48f)))
        list.addView(completedBox, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        col.addView(list, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(col, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        renderItems()
    }

    /** A 48 px section header: the 14 px / 500 title and, at the end, a 48 px menu button. */
    private fun sectionHeader(title: String, onMenu: ((View) -> Unit)?): LinearLayout = LinearLayout(ctx.context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ctx.px(16f), 0, ctx.px(16f), 0)
        addView(extLabel(ctx, 14f, ctx.theme.primaryTextColor, Inter.MEDIUM).apply { text = title }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(FrameLayout(ctx.context).apply {
            contentDescription = "$title menu"
            addView(MdiIconView(ctx.context).apply { setIcon("mdi:dots-vertical", ctx.theme.primaryTextColor) },
                FrameLayout.LayoutParams(ctx.px(24f), ctx.px(24f), Gravity.CENTER))
            if (onMenu != null) {
                isClickable = true
                applyHaRipple(this, 999f, ctx.theme.dark)
                setOnClickListener { onMenu(this) }
            }
        }, LinearLayout.LayoutParams(ctx.px(48f), ctx.px(48f)))
    }

    private fun clearCompleted() {
        val eid = config.entity ?: return
        items = items.filter { !it.completed }
        fetchGeneration++
        renderItems()
        // Rejected: the completed items are still on the server; read the list back so they (and the menu) return.
        callTodo("remove_completed_items", JSONObject().put("entity_id", eid)) { fetch(eid) }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val eid = config.entity ?: return
        // `observe` delivers the current state at once, so this fetches on attach and again on every change.
        observe(eid) { _ -> fetch(eid) }
    }

    private fun fetch(eid: String, retry: Boolean = true) {
        val gen = ++fetchGeneration
        ctx.callWs(JSONObject().put("type", "todo/item/list").put("entity_id", eid)) { ok, result, _ ->
            if (!ok) {
                // A row added here waits for this list to get its uid: ask once more rather than leave it untoggleable.
                if (retry) ctx.main.postDelayed({
                    if (gen == fetchGeneration && isAttachedToWindow && items.any { it.pending }) fetch(eid, retry = false)
                }, RETRY_MS)
                return@callWs
            }
            val parsed = parseItems(result as? JSONObject)
            ctx.main.post {
                if (gen != fetchGeneration) return@post
                items = parsed
                renderItems()
            }
        }
    }

    private fun toggle(item: TodoItem) {
        val eid = config.entity ?: return
        // A row just added has no Home Assistant uid yet: nothing to update until the list comes back with it.
        if (item.pending) return
        val now = !item.completed
        val flipped = item.copy(completed = now)
        items = items.map { if (it.uid == item.uid) flipped else it }
        fetchGeneration++ // a fetch already in flight must not undo the optimistic flip
        renderItems()
        callTodo(
            "update_item",
            JSONObject().put("entity_id", eid).put("item", item.uid).put("status", if (now) "completed" else "needs_action"),
        ) {
            // Rejected: undo this flip at once (unless the row changed since), then read the list to reconcile.
            if (items.any { it === flipped }) {
                items = items.map { if (it === flipped) item else it }
                renderItems()
            }
            fetch(eid)
        }
    }

    /** `todo.<service>` with HA's answer: a rejected write runs [onFailed] on the main thread. */
    private fun callTodo(service: String, data: JSONObject, onFailed: () -> Unit) {
        val msg = JSONObject().put("type", "call_service").put("domain", "todo").put("service", service)
            .put("service_data", data)
        ctx.callWs(msg) { ok, _, _ -> if (!ok) ctx.main.post(onFailed) }
    }

    private fun addItem() {
        val eid = config.entity ?: return
        val text = input.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        input.setText("")
        val local = TodoItem(uid = "$LOCAL_UID${System.nanoTime()}", summary = text, completed = false, pending = true)
        items = items + local
        fetchGeneration++
        renderItems()
        callTodo("add_item", JSONObject().put("entity_id", eid).put("item", text)) {
            // Rejected: take the row back out, and give the text back for another try.
            items = items.filter { it.uid != local.uid }
            renderItems()
            if (input.text.isNullOrEmpty()) input.setText(text)
            fetch(eid)
        }
    }

    private fun renderItems() {
        activeBox.removeAllViews()
        completedBox.removeAllViews()
        val active = items.filter { !it.completed }
        val done = items.filter { it.completed }
        val shown = if (maxItems > 0) active.take(maxItems) else active
        shown.forEach { activeBox.addView(itemRow(it), LinearLayout.LayoutParams(MATCH_PARENT, ctx.px(40f))) }
        val hidden = active.size - shown.size
        if (hidden > 0) {
            activeBox.addView(extLabel(ctx, 12f, ctx.theme.secondaryTextColor).apply {
                text = "+ $hidden more"
                setPadding(ctx.px(48f), ctx.px(2f), 0, ctx.px(4f))
            })
        }
        activeHeader.visibility = if (active.isNotEmpty()) VISIBLE else GONE
        if (active.isEmpty()) {
            activeBox.addView(extLabel(ctx, 14f, ctx.theme.primaryTextColor).apply {
                text = "No items"
                setPadding(ctx.px(16f), ctx.px(12f), ctx.px(16f), ctx.px(12f))
            })
        }
        val showCompleted = done.isNotEmpty() && !hideCompleted
        completedDivider.visibility = if (showCompleted) VISIBLE else GONE
        completedHeader.visibility = if (showCompleted) VISIBLE else GONE
        completedBox.visibility = if (showCompleted) VISIBLE else GONE
        if (showCompleted) {
            done.forEach { completedBox.addView(itemRow(it), LinearLayout.LayoutParams(MATCH_PARENT, ctx.px(40f))) }
        }
    }

    /** ha-check-list-item: 20 px in, a 20 px square checkbox, 8 px, then the summary (struck through when done). */
    private fun itemRow(item: TodoItem): View {
        val row = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.px(20f), 0, ctx.px(20f), 0)
            isClickable = true
            setOnClickListener { toggle(item) }
        }
        val box = FrameLayout(ctx.context).apply {
            background = GradientDrawable().apply {
                cornerRadius = 4f * ctx.dp
                setStroke(ctx.px(2f), if (item.completed) CHECKED else 0xFF7A7A7A.toInt())
                setColor(if (item.completed) CHECKED else Color.TRANSPARENT)
            }
            if (item.completed) addView(MdiIconView(ctx.context).apply { setIcon("mdi:check-bold", Color.WHITE) },
                FrameLayout.LayoutParams(ctx.px(14f), ctx.px(14f), Gravity.CENTER))
        }
        row.addView(box, LinearLayout.LayoutParams(ctx.px(20f), ctx.px(20f)).apply { marginEnd = ctx.px(8f) })
        val text = extLabel(ctx, 14f, ctx.theme.primaryTextColor).apply {
            this.text = item.summary
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            if (item.completed) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        }
        row.addView(text, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        return row
    }

    /** Test hooks: whether the add row shows, and the active box's rows (items plus any "+ N more" line). */
    internal val addRowVisible: Boolean get() = addRow.visibility == VISIBLE
    internal val activeRowCount: Int get() = activeBox.childCount
    internal fun setItemsForTest(list: List<TodoItem>) {
        items = list
        renderItems()
    }

    companion object {
        /** The uid prefix of a row added here that Home Assistant hasn't listed yet. */
        private const val LOCAL_UID = "local-"

        /** The wait before a failed list read is tried again (once). */
        private const val RETRY_MS = 2_000L
        /** HA's checked checkbox: its brand fill (#009ac7, measured). */
        private const val CHECKED = 0xFF009AC7.toInt()

        /** Reads `{ items: [{ uid, summary, status }] }` from `todo/item/list`. */
        fun parseItems(result: JSONObject?): List<TodoItem> {
            val arr = result?.optJSONArray("items") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val summary = o.optString("summary", "")
                val uid = o.optString("uid", "").ifEmpty { summary }
                if (uid.isEmpty()) null else TodoItem(uid, summary, o.optString("status") == "completed")
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// area
// ---------------------------------------------------------------------------------------------

/**
 * Native HA `area` card, as HA 2026's `hui-area-card` draws it. The default (`display_type: picture`) is a header
 * that takes the card's spare height (16:9 outside a grid) over a tile row. The header shows the area's picture,
 * the first camera of the area (`display_type: camera`), or else the area's icon, 48 px, on the tile color at 12%.
 * The tile row is the area's icon (`mdi:texture-box` without one) and its name, with the `sensor_classes` line
 * under it. `display_type: compact` keeps only the tile row (`vertical` stacks it). Active `alert_classes` sit as
 * orange boxes over the header, or as a badge on the icon when compact. The area itself (name, icon, picture,
 * temperature and humidity sensors) and its entities come from HA's registries ([HaDisplay]). `features` aren't
 * drawn yet.
 */
class AreaCardView(ctx: VitroCardContext, private val config: CardConfig) : CardSurface(ctx) {
    private val raw = config.raw
    private val areaId = raw.optString("area", "")
    private val displayType = AreaCardConfig.displayType(raw)
    private val vertical = displayType == "compact" && raw.optBoolean("vertical", false)
    private val sensorClasses = strings(raw.optJSONArray("sensor_classes"))
    private val alertClasses = strings(raw.optJSONArray("alert_classes"))
    private val excluded = strings(raw.optJSONArray("exclude_entities")).toSet()
    private val tileColor: Int = raw.optString("color", "").takeIf { it.isNotEmpty() }?.let { c ->
        ThemeResolver.parseColor(ctx.theme.vars["$c-color"]) ?: MushroomColors.parse(c, ctx.theme)
    } ?: ctx.theme.stateIconColor
    private val ratio = PictureCardView.parseAspectRatio(raw.opt("aspect_ratio")?.toString())

    private val header = FrameLayout(ctx.context)
    private val headerIcon = MdiIconView(ctx.context)
    private val picture = ImageView(ctx.context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; visibility = GONE }
    private val alerts = LinearLayout(ctx.context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(ctx.px(8f), ctx.px(8f), ctx.px(8f), ctx.px(8f))
    }
    private val tileIcon = MdiIconView(ctx.context)
    private val badge = FrameLayout(ctx.context).apply {
        visibility = GONE
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ALERT_ORANGE) }
    }
    private val badgeIcon = MdiIconView(ctx.context)
    private val nameView = extLabel(ctx, 14f, ctx.theme.primaryTextColor, Inter.MEDIUM)
    private val secondaryView = extLabel(ctx, 12f, ctx.theme.primaryTextColor)

    /** The area's entities the card reads (sensors, alerts, its camera), for the state changes it follows. */
    private var watched: Set<String> = emptySet()
    private var pictureUrl: String? = null

    private val col = LinearLayout(ctx.context).apply { orientation = LinearLayout.VERTICAL }

    init {
        if (displayType != "compact") {
            header.setBackgroundColor(CardSurface.withAlpha(tileColor, 0.12f))
            header.addView(picture, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            header.addView(headerIcon, FrameLayout.LayoutParams(ctx.px(48f), ctx.px(48f), Gravity.CENTER))
            header.addView(alerts, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.START))
            col.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        col.addView(tileRow(), LinearLayout.LayoutParams(MATCH_PARENT, if (displayType == "compact") 0 else WRAP_CONTENT,
            if (displayType == "compact") 1f else 0f))
        addView(col, LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // HA: `tap_action` defaults to none (`navigation_path` makes it navigate); the header has its own
        // `image_tap_action` (more-info on the camera for display_type camera), else the card's.
        val nav = raw.optString("navigation_path", "").ifEmpty { null }
        val tap = when {
            raw.has("tap_action") -> ActionConfig.parse(raw.optJSONObject("tap_action"), "none")
            nav != null -> ActionConfig("navigate", navigationPath = nav)
            else -> ActionConfig("none")
        }
        val imageTap = when {
            raw.has("image_tap_action") -> ActionConfig.parse(raw.optJSONObject("image_tap_action"), "none")
            displayType == "camera" -> ActionConfig("more-info")
            else -> null
        }
        if (tap.action != "none") {
            isClickable = true
            setOnClickListener { ctx.performAction(tap, null) }
            enablePressFeedback()
        }
        if (displayType != "compact" && (imageTap ?: tap).action != "none") {
            header.isClickable = true
            header.setOnClickListener {
                if (imageTap != null && imageTap.action != "none") ctx.performAction(imageTap, cameraEntity()) else ctx.performAction(tap, null)
            }
        }

        render()
        // The area and its entities arrive with HA's registries: the store renotifies its listeners then.
        observe(EntityStore.DISPLAY_KEY) { render() }
        track(ctx.store.subscribeAny { changed -> if (changed.any { it in watched }) ctx.main.post { render() } })
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (displayType != "compact") {
            // In a grid the header takes the height the row leaves; elsewhere it keeps the aspect ratio (16:9).
            val lp = header.layoutParams as LinearLayout.LayoutParams
            val grid = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY
            val h = if (grid) 0 else (MeasureSpec.getSize(widthMeasureSpec) / ratio).roundToInt()
            val w = if (grid) 1f else 0f
            if (lp.height != h || lp.weight != w) { lp.height = h; lp.weight = w }
            // In a grid HA's header and 56 px tile row fill the card's whole height, border included (the row runs
            // over the bottom border): the header is the border's width taller than the inside would leave.
            val over = if (grid) -2 * drawnBorderPx else 0
            val colLp = col.layoutParams as LayoutParams
            if (colLp.bottomMargin != over) colLp.bottomMargin = over
            clipToPadding = !grid
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /** ha-tile-container: a 36 px icon 10 px in, the name 14 px / 500 and the sensors line, 56 px tall. */
    private fun tileRow(): LinearLayout {
        val pad = ctx.px(10f)
        val row = LinearLayout(ctx.context).apply {
            orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = if (vertical) Gravity.CENTER else Gravity.CENTER_VERTICAL
            if (vertical) setPadding(pad, pad, pad, pad) else setPadding(pad, 0, pad, 0)
            minimumHeight = ctx.px(56f)
            clipChildren = false
            clipToPadding = false
        }
        val iconPad = ctx.px(6f)
        val iconFrame = FrameLayout(ctx.context).apply {
            clipChildren = false
            clipToPadding = false
            addView(tileIcon, LayoutParams(MATCH_PARENT, MATCH_PARENT).apply { setMargins(iconPad, iconPad, iconPad, iconPad) })
            badge.addView(badgeIcon, LayoutParams(ctx.px(12f), ctx.px(12f), Gravity.CENTER))
            addView(badge, LayoutParams(ctx.px(16f), ctx.px(16f), Gravity.TOP or Gravity.END).apply {
                topMargin = -ctx.px(3f); rightMargin = -ctx.px(3f)
            })
        }
        row.addView(iconFrame, LinearLayout.LayoutParams(ctx.px(36f), ctx.px(36f)).apply {
            if (vertical) bottomMargin = ctx.px(8f) else marginEnd = ctx.px(10f)
        })
        val info = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (vertical) Gravity.CENTER_HORIZONTAL else Gravity.START
            for (t in listOf(nameView, secondaryView)) {
                t.maxLines = 1
                t.ellipsize = TextUtils.TruncateAt.END
                t.gravity = Gravity.CENTER_VERTICAL or (if (vertical) Gravity.CENTER_HORIZONTAL else Gravity.START)
            }
            nameView.letterSpacing = 0.1f / 14f
            secondaryView.letterSpacing = 0.4f / 12f
            val w = if (vertical) WRAP_CONTENT else MATCH_PARENT
            addView(nameView, LinearLayout.LayoutParams(w, (22.4f * ctx.dp).roundToInt()))
            addView(secondaryView, LinearLayout.LayoutParams(w, (14.4f * ctx.dp).roundToInt()))
        }
        row.addView(info, LinearLayout.LayoutParams(if (vertical) WRAP_CONTENT else 0, WRAP_CONTENT, if (vertical) 0f else 1f))
        return row
    }

    private fun render() {
        val area = HaDisplay.areas[areaId]
        nameView.text = config.name ?: area?.name ?: areaName(config)
        val icon = area?.icon
        tileIcon.setIcon(icon ?: "mdi:texture-box", tileColor)
        headerIcon.visibility = if (icon != null) VISIBLE else GONE
        icon?.let { headerIcon.setIcon(it, tileColor) }

        val members = if (area != null) HaDisplay.areaEntityIds(areaId).filter { it !in excluded } else emptyList()
        val sensors = sensorLine(area, members)
        secondaryView.text = sensors
        secondaryView.visibility = if (sensors.isEmpty()) GONE else VISIBLE
        val active = activeAlerts(members)
        if (displayType == "compact") {
            badge.visibility = if (active.isEmpty()) GONE else VISIBLE
            active.firstOrNull()?.let { badgeIcon.setIcon(MdiIcons.resolveIconName(null, it), Color.WHITE) }
        } else {
            renderAlerts(active)
        }
        val camera = cameraEntity()
        watched = (members + listOfNotNull(area?.temperatureEntityId, area?.humidityEntityId)).toSet()
        if (displayType != "compact") showPicture(camera?.let { ctx.store[it]?.entityPicture } ?: area?.picture)
        contentDescription = listOf(nameView.text, sensors).filter { it.isNotEmpty() }.joinToString(", ")
    }

    private fun renderAlerts(active: List<EntityState>) {
        alerts.removeAllViews()
        for ((i, s) in active.withIndex()) {
            val box = FrameLayout(ctx.context).apply {
                background = GradientDrawable().apply { cornerRadius = ctx.px(12f).toFloat(); setColor(ALERT_ORANGE) }
                addView(MdiIconView(ctx.context).apply { setIcon(MdiIcons.resolveIconName(null, s), Color.WHITE) },
                    LayoutParams(ctx.px(16f), ctx.px(16f), Gravity.CENTER))
            }
            alerts.addView(box, LinearLayout.LayoutParams(ctx.px(24f), ctx.px(24f)).apply { if (i > 0) marginStart = ctx.px(8f) })
        }
    }

    private fun showPicture(url: String?) {
        if (url == pictureUrl) return
        pictureUrl = url
        if (url == null) {
            picture.setImageDrawable(null)
            picture.visibility = GONE
            header.setBackgroundColor(CardSurface.withAlpha(tileColor, 0.12f))
            return
        }
        ctx.loadImage(url, this) { bmp ->
            if (bmp != null && pictureUrl == url) {
                picture.setImageBitmap(bmp)
                picture.visibility = VISIBLE
                headerIcon.visibility = GONE
                header.background = null
            }
        }
    }

    /** The area's first camera, for `display_type: camera`. */
    private fun cameraEntity(): String? =
        if (displayType != "camera" || HaDisplay.areas[areaId] == null) null
        else HaDisplay.areaEntityIds(areaId).firstOrNull { it.startsWith("camera.") && ctx.store[it] != null }

    /** The `sensor_classes` line: per class, the area's temperature/humidity sensor, else the median (or sum). */
    private fun sensorLine(area: HaDisplay.Area?, members: List<String>): String {
        if (area == null || sensorClasses.isEmpty()) return ""
        return sensorClasses.mapNotNull { cls -> sensorValue(cls, area, members) }.filter { it.isNotEmpty() }.joinToString(" · ")
    }

    private fun sensorValue(cls: String, area: HaDisplay.Area, members: List<String>): String? {
        val own = when (cls) { "temperature" -> area.temperatureEntityId; "humidity" -> area.humidityEntityId; else -> null }
        if (own != null) {
            val s = ctx.store[own] ?: return ""
            return if (s.isUnavailable) "" else s.formattedState()
        }
        val values = ArrayList<Double>()
        var unit: String? = null
        val devicesWithValues = HashSet<String>()
        for (id in members) {
            if (!id.startsWith("sensor.")) continue
            val s = ctx.store[id] ?: continue
            if (s.deviceClass != cls || s.isUnavailable) continue
            val v = s.state.toDoubleOrNull()?.takeIf { it.isFinite() } ?: continue
            if (unit == null) unit = s.unit
            if (s.unit == unit) {
                values += v
                HaDisplay.registry[id]?.deviceId?.let { devicesWithValues += it }
            }
        }
        // HA also reads climate (and humidifier) entities' current temperature / humidity, once per device.
        val attr = ATTRIBUTE_SOURCES[cls]
        if (attr != null) {
            for (id in members) {
                val dom = id.substringBefore('.')
                val attribute = attr[dom] ?: continue
                if (HaDisplay.registry[id]?.deviceId?.let { it in devicesWithValues } == true) continue
                val v = (ctx.store[id]?.attributes?.get(attribute) as? Number)?.toDouble()
                    ?: (ctx.store[id]?.attributes?.get(attribute) as? String)?.toDoubleOrNull() ?: continue
                if (unit == null) unit = if (cls == "temperature") ThermostatCardView.temperatureUnit(ctx) else "%"
                values += v
            }
        }
        if (values.isEmpty()) return null
        val value = if (cls in SUM_CLASSES) values.sum() else median(values)
        return formatWithUnit(value, unit)
    }

    /** Per `alert_classes` entry, the first of the area's binary sensors of that class that is on. */
    private fun activeAlerts(members: List<String>): List<EntityState> {
        if (alertClasses.isEmpty()) return emptyList()
        return alertClasses.mapNotNull { cls ->
            members.asSequence().filter { it.startsWith("binary_sensor.") }.mapNotNull { ctx.store[it] }
                .firstOrNull { it.deviceClass == cls && it.state == "on" }
        }
    }

    companion object {
        private val ALERT_ORANGE = 0xFFFF9800.toInt()
        private val SUM_CLASSES = setOf("power", "apparent_power", "reactive_power", "energy", "reactive_energy",
            "current", "gas", "monetary", "volume", "water")
        private val ATTRIBUTE_SOURCES = mapOf(
            "temperature" to mapOf("climate" to "current_temperature"),
            "humidity" to mapOf("climate" to "current_humidity", "humidifier" to "current_humidity"),
        )

        /** The card's `name`, else the area id in words (`living_room` → `Living room`), until HA's registry is in. */
        fun areaName(config: CardConfig): String =
            config.name ?: config.raw.optString("area", "").ifEmpty { "Area" }
                .replace('_', ' ').replaceFirstChar { it.titlecase(Locale.ROOT) }

        internal fun median(values: List<Double>): Double {
            val s = values.sorted()
            return if (s.size % 2 == 0) (s[s.size / 2] + s[s.size / 2 - 1]) / 2 else s[s.size / 2]
        }

        /** HA's `formatNumberWithUnit` (en, at most one decimal): no space before `%` or `°`. */
        internal fun formatWithUnit(value: Double, unit: String?): String {
            // Intl's halfExpand on the number's exact value: 0.25 → 0.3, 21.45 (21.4499…) → 21.4.
            val n = java.text.DecimalFormat("#,##0.#", java.text.DecimalFormatSymbols(Locale.US))
                .apply { roundingMode = java.math.RoundingMode.HALF_UP }.format(value)
            return when {
                unit.isNullOrEmpty() -> n
                unit == "%" || unit == "°" -> n + unit
                else -> "$n $unit"
            }
        }

        private fun strings(arr: JSONArray?): List<String> =
            if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { arr.optString(it).ifEmpty { null } }
    }
}

private fun extSpPx(ctx: VitroCardContext, sp: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, ctx.context.resources.displayMetrics)

/** The header both dial cards share: the name centered, a `⋮` More-Info button pinned to the end. */
private fun dialHeader(ctx: VitroCardContext, title: TextView, onMore: () -> Unit): FrameLayout {
    val header = FrameLayout(ctx.context)
    header.addView(title, FrameLayout.LayoutParams(MATCH_PARENT, ctx.px(36f)).apply {
        leftMargin = ctx.px(40f); rightMargin = ctx.px(40f)
    })
    val dots = FrameLayout(ctx.context).apply {
        isClickable = true
        contentDescription = "More info"
        addView(MdiIconView(ctx.context).apply { setIcon("mdi:dots-vertical", ctx.theme.primaryTextColor) },
            FrameLayout.LayoutParams(ctx.px(20f), ctx.px(20f), Gravity.CENTER))
        setOnClickListener { onMore() }
    }
    header.addView(dots, FrameLayout.LayoutParams(ctx.px(36f), ctx.px(36f), Gravity.END or Gravity.CENTER_VERTICAL))
    return header
}

// ---------------------------------------------------------------------------------------------
// picture-elements
// ---------------------------------------------------------------------------------------------

/** One overlay of a `picture-elements` card, positioned by its `style.left` / `style.top` percentages. */
data class PictureElement(
    val type: String,
    val entity: String?,
    val icon: String?,
    val title: String?,
    val prefix: String,
    val suffix: String,
    val xFrac: Float,
    val yFrac: Float,
    val raw: JSONObject,
)

/**
 * Native HA `picture-elements` card (`hui-picture-elements-card`): a floorplan image with live `state-icon`,
 * `state-label`, `state-badge`, `icon` and `service-button` overlays centered on their `left` / `top` percentages.
 * The image is `image`, else the `camera_image` entity's picture. `conditional` and `image` elements are not drawn.
 */
class PictureElementsCardView(ctx: VitroCardContext, private val config: CardConfig) : CardSurface(ctx) {
    private val floorplan = FloorplanLayout(ctx.context)
    private val imageView = ImageView(ctx.context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val fixedRatio: Float? = parseAspectRatio(config.raw.optString("aspect_ratio", ""))
    /** The picture on screen, and the one being loaded (cleared when that load fails, so a later update retries it). */
    private var shownUrl: String? = null
    private var loadingUrl: String? = null
    private var imageRequest = 0

    init {
        floorplan.ratio = fixedRatio ?: DEFAULT_RATIO
        floorplan.addView(imageView, FloorplanLayout.Params(fill = true))
        addView(floorplan, LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val image = config.raw.optString("image", "").ifEmpty { null }
        val camera = config.raw.optString("camera_image", "").ifEmpty { null }
        when {
            image != null -> load(image)
            camera != null -> observe(camera) { s -> s?.entityPicture?.let(::load) }
        }
        parseElements(config.raw.optJSONArray("elements")).forEach(::addElement)
    }

    private fun load(url: String) {
        if (url == loadingUrl) return
        // Every request after this one (or a return to the picture on screen) takes ownership from it: only the newest
        // call may clear [loadingUrl] or show its picture.
        val request = ++imageRequest
        if (url == shownUrl) {
            loadingUrl = null
            return
        }
        loadingUrl = url
        ctx.loadImage(url, this) { bmp ->
            if (request != imageRequest) return@loadImage
            loadingUrl = null
            if (bmp == null) return@loadImage
            shownUrl = url
            imageView.setImageBitmap(bmp)
            if (fixedRatio == null && bmp.width > 0) {
                // No aspect_ratio: follow the picture's own shape, so FIT_XY is exact and nothing is cropped.
                imageView.scaleType = ImageView.ScaleType.FIT_XY
                floorplan.ratio = bmp.height.toFloat() / bmp.width
                floorplan.requestLayout()
            }
        }
    }

    private fun addElement(el: PictureElement) {
        val view: View = when (el.type) {
            "state-icon" -> stateIcon(el)
            "state-label" -> stateLabel(el)
            "state-badge" -> stateBadge(el)
            "icon" -> plainIcon(el)
            "service-button" -> serviceButton(el)
            else -> return
        }
        el.title?.let { view.contentDescription = it }
        floorplan.addView(view, FloorplanLayout.Params(el.xFrac, el.yFrac))
    }

    private fun bindActions(view: View, el: PictureElement, defaultTap: String) {
        val tap = ActionConfig.parse(el.raw.optJSONObject("tap_action"), defaultTap)
        val hold = ActionConfig.parse(el.raw.optJSONObject("hold_action"), "more-info")
        view.isClickable = true
        view.setOnClickListener { ctx.performAction(tap, el.entity) }
        view.bindHoldAction(ctx, hold, el.entity)
    }

    private fun overlayColor(alpha: Float) = CardSurface.withAlpha(ctx.theme.cardBackgroundColor, alpha)

    private fun tintFor(s: EntityState?): Int = s?.lightTintColor() ?: iconColorFor(ctx, s)

    private fun stateIcon(el: PictureElement): View {
        val icon = MdiIconView(ctx.context)
        val halo = FrameLayout(ctx.context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(overlayColor(0.55f))
            }
            addView(icon, LayoutParams(ctx.px(22f), ctx.px(22f), Gravity.CENTER))
        }
        halo.layoutParams = LayoutParams(ctx.px(36f), ctx.px(36f))
        bindActions(halo, el, if (VitroCardContext.isToggleable(el.entity)) "toggle" else "more-info")
        val eid = el.entity
        if (eid != null) {
            observe(eid) { s -> icon.setIcon(MdiIcons.resolveIconName(el.icon, s), tintFor(s)) }
        } else {
            icon.setIcon(el.icon ?: "mdi:home", ctx.theme.stateIconColor)
        }
        return halo
    }

    private fun stateLabel(el: PictureElement): View {
        val label = extLabel(ctx, 12f, ctx.theme.primaryTextColor, Inter.MEDIUM).apply {
            maxLines = 1
            setPadding(ctx.px(8f), ctx.px(3f), ctx.px(8f), ctx.px(3f))
            background = pillBackground(overlayColor(0.75f))
        }
        bindActions(label, el, "more-info")
        val eid = el.entity
        if (eid != null) {
            observe(eid) { s -> label.text = labelText(el, s) }
        } else {
            label.text = labelText(el, null)
        }
        return label
    }

    private fun stateBadge(el: PictureElement): View {
        val value = extLabel(ctx, 11f, ctx.theme.primaryTextColor, Inter.MEDIUM).apply {
            gravity = Gravity.CENTER
            maxLines = 1
        }
        val icon = MdiIconView(ctx.context)
        val ring = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(overlayColor(0.85f))
        }
        val badge = FrameLayout(ctx.context).apply {
            background = ring
            addView(value, LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(icon, LayoutParams(ctx.px(20f), ctx.px(20f), Gravity.CENTER))
        }
        badge.layoutParams = LayoutParams(ctx.px(40f), ctx.px(40f))
        bindActions(badge, el, "more-info")
        val eid = el.entity ?: return badge
        observe(eid) { s ->
            val color = if (s?.isActive == true) ctx.theme.activeColor(s.domain) else ctx.theme.dividerColor
            ring.setStroke(ctx.px(2f).coerceAtLeast(1), s?.lightTintColor() ?: color)
            val shown = s?.takeIf { !it.isUnavailable && (it.unit != null || it.state.toDoubleOrNull() != null) }
            value.visibility = if (shown != null) VISIBLE else GONE
            icon.visibility = if (shown != null) GONE else VISIBLE
            if (shown != null) {
                value.text = "${shown.state}${shown.unit.orEmpty()}"
            } else {
                icon.setIcon(MdiIcons.resolveIconName(el.icon, s), tintFor(s))
            }
        }
        return badge
    }

    private fun plainIcon(el: PictureElement): View {
        val icon = MdiIconView(ctx.context)
        icon.layoutParams = LayoutParams(ctx.px(24f), ctx.px(24f))
        bindActions(icon, el, "more-info")
        val eid = el.entity
        if (eid != null) {
            observe(eid) { s -> icon.setIcon(MdiIcons.resolveIconName(el.icon, s), tintFor(s)) }
        } else {
            icon.setIcon(el.icon ?: "mdi:home", ctx.theme.primaryTextColor)
        }
        return icon
    }

    private fun serviceButton(el: PictureElement): View {
        val button = extLabel(ctx, 13f, ctx.theme.primaryColor, Inter.MEDIUM).apply {
            text = el.title ?: "Run"
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(ctx.px(12f), ctx.px(6f), ctx.px(12f), ctx.px(6f))
            background = pillBackground(overlayColor(0.85f), 8f * ctx.dp)
            isClickable = true
        }
        val service = el.raw.optString("service", "").ifEmpty { el.raw.optString("action", "") }
        if (service.contains('.') && !el.raw.has("tap_action")) {
            val data = el.raw.optJSONObject("service_data") ?: el.raw.optJSONObject("data")
            val call = ActionConfig("perform-action", service = service, serviceData = data, target = el.raw.optJSONObject("target"))
            button.setOnClickListener { ctx.performAction(call, el.entity) }
        } else {
            bindActions(button, el, "none")
        }
        return button
    }

    companion object {
        /** HA's 16:9 until the image tells us its own shape. */
        private const val DEFAULT_RATIO = 9f / 16f

        /** `"35%"` → `0.35`; anything that isn't a 0–100 percentage → `0.5` (the center). */
        fun parsePercent(v: String?): Float {
            val t = v?.trim().orEmpty()
            if (!t.endsWith("%")) return 0.5f
            val n = t.dropLast(1).trim().toFloatOrNull() ?: return 0.5f
            return if (n in 0f..100f) n / 100f else 0.5f
        }

        /** HA's `aspect_ratio` (`16:9`, `16x9`, `1.78` or `56%`) as height ÷ width, or null when unset or invalid. */
        fun parseAspectRatio(v: String?): Float? {
            val t = v?.trim().orEmpty()
            if (t.isEmpty()) return null
            if (t.endsWith("%")) return t.dropLast(1).trim().toFloatOrNull()?.takeIf { it > 0f }?.div(100f)
            val parts = t.split(':', 'x', 'X')
            if (parts.size == 2) {
                val w = parts[0].trim().toFloatOrNull() ?: return null
                val h = parts[1].trim().toFloatOrNull() ?: return null
                return if (w > 0f && h > 0f) h / w else null
            }
            return t.toFloatOrNull()?.takeIf { it > 0f }?.let { 1f / it }
        }

        /** The drawable `elements[]` of a card, in order, each with its fractional position. */
        fun parseElements(arr: JSONArray?): List<PictureElement> {
            if (arr == null) return emptyList()
            val out = ArrayList<PictureElement>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val style = o.optJSONObject("style")
                out += PictureElement(
                    type = o.optString("type", ""),
                    entity = o.optString("entity", "").ifEmpty { null },
                    icon = o.optString("icon", "").ifEmpty { null },
                    title = o.optString("title", "").ifEmpty { null },
                    prefix = o.optString("prefix", ""),
                    suffix = o.optString("suffix", ""),
                    xFrac = parsePercent(style?.optString("left", "")),
                    yFrac = parsePercent(style?.optString("top", "")),
                    raw = o,
                )
            }
            return out
        }

        /** A `state-label`'s text: `prefix`, the state (or `attribute`), `suffix`; `…` while the state is unknown. */
        fun labelText(el: PictureElement, s: EntityState?): String {
            val attr = el.raw.optString("attribute", "")
            val value = when {
                s == null -> "…"
                attr.isNotEmpty() -> s.attributes[attr]?.toString() ?: "…"
                else -> s.formattedState()
            }
            return "${el.prefix}$value${el.suffix}"
        }
    }
}

/**
 * The floorplan box: as tall as [ratio] × its width, the image filling it, and every other child centered on its
 * fractional `(x, y)` point.
 */
internal class FloorplanLayout(context: Context) : FrameLayout(context) {
    var ratio: Float = 9f / 16f

    class Params(val xFrac: Float = 0.5f, val yFrac: Float = 0.5f, val fill: Boolean = false) :
        FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)

    override fun addView(child: View, params: android.view.ViewGroup.LayoutParams) {
        // Keep an element's own size (set when it was built) and add its position.
        val own = child.layoutParams
        val p = params as? Params ?: Params()
        if (own != null && !p.fill) {
            p.width = own.width
            p.height = own.height
        }
        super.addView(child, p)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            MeasureSpec.getSize(heightMeasureSpec)
        } else {
            (w * ratio).roundToInt()
        }
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val p = c.layoutParams as? Params
            if (p?.fill == true) {
                c.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
            } else {
                measureChild(c, MeasureSpec.makeMeasureSpec(w, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(h, MeasureSpec.AT_MOST))
            }
        }
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val w = right - left
        val h = bottom - top
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val p = c.layoutParams as? Params ?: Params()
            if (p.fill) {
                c.layout(0, 0, w, h)
                continue
            }
            val cx = (p.xFrac * w).roundToInt()
            val cy = (p.yFrac * h).roundToInt()
            val l = cx - c.measuredWidth / 2
            val t = cy - c.measuredHeight / 2
            c.layout(l, t, l + c.measuredWidth, t + c.measuredHeight)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// humidifier
// ---------------------------------------------------------------------------------------------

/**
 * Native HA `humidifier` card (`hui-humidifier-card`): the thermostat's 270° dial for the target humidity, `−` / `+`
 * in its opening, and a bar with the power button and the mode picker below.
 */
class HumidifierCardView(ctx: VitroCardContext, private val config: CardConfig) : CardSurface(ctx) {
    private val titleView = extLabel(ctx, 16f, ctx.theme.primaryTextColor).apply {
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val dial = HumidityDialView(ctx)
    private val powerIcon = MdiIconView(ctx.context)
    private val powerCell = FrameLayout(ctx.context)
    private val modePill = extLabel(ctx, 13f, ctx.theme.primaryTextColor, Inter.MEDIUM).apply {
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(ctx.px(12f), 0, ctx.px(12f), 0)
    }

    init {
        val col = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.px(12f), ctx.px(8f), ctx.px(12f), ctx.px(12f))
        }
        val eid = config.entity
        col.addView(dialHeader(ctx, titleView) { eid?.let { ctx.performAction(ActionConfig("more-info"), it) } },
            LinearLayout.LayoutParams(MATCH_PARENT, ctx.px(36f)))

        val dialFrame = FrameLayout(ctx.context)
        dialFrame.addView(dial, LayoutParams(MATCH_PARENT, ctx.px(220f)))
        val steppers = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun stepBtn(symbol: String, sign: Int) = extLabel(ctx, 22f, ctx.theme.primaryTextColor).apply {
            text = symbol
            gravity = Gravity.CENTER
            contentDescription = if (sign > 0) "Raise target humidity" else "Lower target humidity"
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke(ctx.px(1f).coerceAtLeast(1), CardSurface.withAlpha(ctx.theme.secondaryTextColor, 0.5f))
            }
            isClickable = true
            setOnClickListener { dial.nudge(sign) }
        }
        val btn = ctx.px(42f)
        steppers.addView(stepBtn("−", -1), LinearLayout.LayoutParams(btn, btn).apply { marginEnd = ctx.px(16f) })
        steppers.addView(stepBtn("+", 1), LinearLayout.LayoutParams(btn, btn))
        dialFrame.addView(steppers, LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        col.addView(dialFrame, LinearLayout.LayoutParams(MATCH_PARENT, ctx.px(220f)))

        val bar = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val inset = ctx.px(4f)
            setPadding(inset, inset, inset, inset)
            background = pillBackground(if (ctx.theme.dark) BAR_DARK else BAR_LIGHT, 12f * ctx.dp)
        }
        powerCell.apply {
            isClickable = true
            contentDescription = "Power"
            addView(powerIcon, LayoutParams(ctx.px(22f), ctx.px(22f), Gravity.CENTER))
        }
        bar.addView(powerCell, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        bar.addView(modePill, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { marginStart = ctx.px(4f) })
        col.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, ctx.px(46f)).apply { topMargin = ctx.px(8f) })
        addView(col, LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        if (eid != null) {
            bindHoldAction(ActionConfig.parse(config.raw.optJSONObject("hold_action"), "more-info"), eid)
            dial.onCommit = { target ->
                ctx.callService("humidifier", "set_humidity", JSONObject().put("entity_id", eid).put("humidity", target), null)
            }
            powerCell.setOnClickListener { ctx.callService("humidifier", "toggle", JSONObject().put("entity_id", eid), null) }
            modePill.isClickable = true
            modePill.setOnClickListener { showModes(it, eid) }
            observe(eid, ::render)
        } else {
            render(null)
        }
    }

    private fun render(s: EntityState?) {
        titleView.text = config.name ?: s?.friendlyName ?: config.entity.orEmpty()
        val attrs = s?.attributes.orEmpty()
        val on = s?.state == "on"
        dial.bind(
            min = (attrs["min_humidity"] as? Number)?.toDouble() ?: 0.0,
            max = (attrs["max_humidity"] as? Number)?.toDouble() ?: 100.0,
            current = (attrs["current_humidity"] as? Number)?.toDouble(),
            target = (attrs["humidity"] as? Number)?.toDouble(),
            on = on,
            status = statusLabel(s),
        )
        powerCell.background = if (on) pillBackground(HUMID_COLOR, 10f * ctx.dp) else null
        powerIcon.setIcon("mdi:power", if (on) Color.WHITE else ctx.theme.primaryTextColor)
        val modes = availableModes(s)
        modePill.visibility = if (modes.isEmpty()) GONE else VISIBLE
        modePill.text = (attrs["mode"] as? String)?.let(::modeLabel) ?: "Mode"
    }

    private fun showModes(anchor: View, eid: String) {
        val modes = availableModes(ctx.store[eid])
        if (modes.isEmpty()) return
        val menu = PopupMenu(ctx.context, anchor)
        modes.forEachIndexed { i, m -> menu.menu.add(0, i, i, modeLabel(m)) }
        menu.setOnMenuItemClickListener { item ->
            ctx.callService("humidifier", "set_mode", JSONObject().put("entity_id", eid).put("mode", modes[item.itemId]), null)
            true
        }
        menu.show()
    }

    companion object {
        /** HA's humidifier accent. */
        const val HUMID_COLOR = 0xFF03A9F4.toInt()
        private const val BAR_LIGHT = 0xFFF4F4F4.toInt()
        private const val BAR_DARK = 0xFF2A2A2A.toInt()

        /** The line above the big number: what the humidifier is doing, else On / Off. */
        fun statusLabel(s: EntityState?): String = when {
            s == null -> "–"
            s.isUnavailable -> s.formattedState()
            s.state == "off" -> "Off"
            else -> when (s.attributes["action"] as? String) {
                "humidifying" -> "Humidifying"
                "drying" -> "Drying"
                "idle" -> "Idle"
                "off" -> "Off"
                else -> "On"
            }
        }

        fun availableModes(s: EntityState?): List<String> =
            (s?.attributes?.get("available_modes") as? List<*>)?.mapNotNull { it?.toString() }.orEmpty()

        fun modeLabel(mode: String): String = mode.replace('_', ' ').replaceFirstChar { it.titlecase(Locale.ROOT) }

        /** A target humidity snapped to whole [step]s inside `min..max`. */
        fun snapHumidity(v: Double, min: Double, max: Double, step: Double = 1.0): Int {
            val snapped = min + Math.round((v - min) / step) * step
            return snapped.coerceIn(min, max).roundToInt()
        }
    }
}

/** The humidifier's 270° dial: track, the target arc in [HumidifierCardView.HUMID_COLOR], a current-humidity dot. */
internal class HumidityDialView(private val ctx: VitroCardContext) : View(ctx.context) {
    private val dp = ctx.dp
    private val strokeW = 14f * dp
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeW
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private fun textPaint(sp: Float, weight: Int, color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = extSpPx(ctx, sp)
        typeface = VitroFonts.typeface(ctx.context, weight, sp)
        textAlign = Paint.Align.CENTER
        this.color = color
    }
    private val statusPaint = textPaint(14f, Inter.MEDIUM, ctx.theme.secondaryTextColor)
    private val bigPaint = textPaint(44f, Inter.REGULAR, ctx.theme.primaryTextColor)
    private val subPaint = textPaint(13f, Inter.REGULAR, ctx.theme.secondaryTextColor)
    private val oval = RectF()

    private var min = 0.0
    private var max = 100.0
    private var current: Double? = null
    private var target: Double? = null
    private var on = false
    private var status = ""
    private var dragTarget: Double? = null

    var onCommit: (Int) -> Unit = {}

    fun bind(min: Double, max: Double, current: Double?, target: Double?, on: Boolean, status: String) {
        this.min = min
        this.max = if (max > min) max else min + 1.0
        this.current = current
        this.target = target
        this.on = on
        this.status = status
        describe()
        if (dragTarget == null) invalidate()
    }

    fun nudge(sign: Int) {
        val t = target ?: return
        val next = HumidifierCardView.snapHumidity(t + sign, min, max)
        if (next.toDouble() == t) return
        target = next.toDouble()
        describe()
        invalidate()
        onCommit(next)
    }

    private val radius: Float get() = (minOf(width, height) / 2f - strokeW).coerceAtLeast(8f * dp)

    private fun fracOf(v: Double): Float = ((v - min) / (max - min)).toFloat().coerceIn(0f, 1f)

    // Reused by onDraw: one point on the ring and the texts, rebuilt only when their value changes.
    private val pt = FloatArray(2)
    private val shownMemo = NumberText()
    private val currentMemo = NumberText()
    private fun shownText(shown: Double?) = if (shown != null) shownMemo.of(shown) { "${it.roundToInt()}%" } else "–"
    private fun currentText(c: Double) = currentMemo.of(c) { "Currently ${it.roundToInt()}%" }

    /**
     * The texts the dial draws, in order, as its content description (the status, the target, the current humidity):
     * what HA's page has as text, so TalkBack reads them and the parity harness finds them.
     */
    private fun describe() {
        val text = listOfNotNull(status.takeIf { it.isNotEmpty() }, shownText(dragTarget ?: target), current?.let(::currentText))
            .joinToString(", ")
        if (contentDescription?.toString() != text) contentDescription = text
    }

    /** The point at [frac] of the ring of radius [r], into [pt]. */
    private fun pointAt(frac: Float, r: Float) {
        val a = Math.toRadians((START_ANGLE + SWEEP * frac).toDouble())
        pt[0] = width / 2f + r * cos(a).toFloat()
        pt[1] = height / 2f + r * sin(a).toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = radius
        oval.set(cx - r, cy - r, cx + r, cy + r)
        arcPaint.color = ctx.theme.dividerColor
        canvas.drawArc(oval, START_ANGLE, SWEEP, false, arcPaint)
        val accent = if (on) HumidifierCardView.HUMID_COLOR else CardSurface.withAlpha(ctx.theme.secondaryTextColor, 0.6f)
        val shown = dragTarget ?: target
        if (shown != null) {
            val f = fracOf(shown)
            arcPaint.color = accent
            if (f > 0f) canvas.drawArc(oval, START_ANGLE, SWEEP * f, false, arcPaint)
        }
        current?.let { c ->
            pointAt(fracOf(c), r)
            val x = pt[0]
            val y = pt[1]
            fill.color = ctx.theme.cardBackgroundColor
            canvas.drawCircle(x, y, strokeW * 0.3f, fill)
            fill.color = ctx.theme.primaryTextColor
            canvas.drawCircle(x, y, strokeW * 0.18f, fill)
        }
        if (shown != null) {
            pointAt(fracOf(shown), r)
            fill.color = Color.WHITE
            canvas.drawCircle(pt[0], pt[1], strokeW * 0.42f, fill)
        }
        canvas.drawText(status, cx, cy - 30f * dp, statusPaint)
        canvas.drawText(shownText(shown), cx, cy + 14f * dp, bigPaint)
        val c = current
        if (c != null) canvas.drawText(currentText(c), cx, cy + 36f * dp, subPaint)
    }

    private fun valueAt(x: Float, y: Float): Double {
        val deg = Math.toDegrees(atan2((y - height / 2f).toDouble(), (x - width / 2f).toDouble())).toFloat()
        val rel = ((deg - START_ANGLE) % 360f + 360f) % 360f
        val clamped = if (rel > SWEEP) (if (rel > SWEEP + (360f - SWEEP) / 2f) 0f else SWEEP) else rel
        return min + (max - min) * (clamped / SWEEP)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val d = hypot(event.x - width / 2f, event.y - height / 2f)
        val r = radius
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (target == null || d < r * 0.65f || d > r * 1.35f) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                dragTarget = HumidifierCardView.snapHumidity(valueAt(event.x, event.y), min, max).toDouble()
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (dragTarget != null) {
                dragTarget = HumidifierCardView.snapHumidity(valueAt(event.x, event.y), min, max).toDouble()
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                dragTarget?.let { t ->
                    target = t
                    performClick()
                    onCommit(t.roundToInt())
                }
                dragTarget = null
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                dragTarget = null
                invalidate()
            }
        }
        describe()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    companion object {
        private const val START_ANGLE = 135f
        private const val SWEEP = 270f
    }
}

// ---------------------------------------------------------------------------------------------
// calendar
// ---------------------------------------------------------------------------------------------

/** One event from `GET /api/calendars/<entity_id>`: times in epoch ms, all-day events from local midnight. */
data class CalendarEvent(
    val summary: String,
    val startMs: Long,
    val endMs: Long,
    val allDay: Boolean,
    val calendarId: String,
)

/**
 * Native HA `calendar` card as an agenda: the next 7 days of the named `calendar.*` entities, one row per event
 * with a date badge, the calendar's color dot, the summary and the time range. Read over HA's REST calendar API.
 */
class CalendarCardView(ctx: VitroCardContext, private val config: CardConfig) : CardSurface(ctx) {
    private val titleView = extLabel(ctx, 16f, ctx.theme.primaryTextColor, Inter.MEDIUM)
    private val list = LinearLayout(ctx.context).apply { orientation = LinearLayout.VERTICAL }
    private val ids: List<String> = calendarIds(config)
    /** Each calendar's last successful answer, kept through a failed refresh. */
    private val lastEvents = HashMap<String, List<CalendarEvent>>()
    private val refresh = Runnable { fetch() }
    private var generation = 0

    init {
        val pad = ctx.px(16f)
        val col = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        titleView.text = config.title ?: ""
        titleView.visibility = if (config.title == null) GONE else VISIBLE
        col.addView(titleView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = ctx.px(10f) })
        col.addView(list, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(col, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        render(emptyList())
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        fetch()
    }

    override fun onDetachedFromWindow() {
        ctx.main.removeCallbacks(refresh)
        generation++
        super.onDetachedFromWindow()
    }

    /**
     * A cached view another one hides stays attached: it stops polling Home Assistant (an answer still on its way is
     * dropped), and asks once again when it shows.
     */
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (!isAttachedToWindow) return
        if (isShown) {
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
        val startCal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val start = StatisticsGraphCardView.isoUtc(startCal.time)
        // Seven calendar days, not 7 x 24 h: a DST change in between must not cut off the last hour.
        val endCal = (startCal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, DAYS) }
        val end = StatisticsGraphCardView.isoUtc(endCal.time)
        val results = arrayOfNulls<List<CalendarEvent>>(ids.size)
        var pending = ids.size
        ids.forEachIndexed { i, eid ->
            val path = "/api/calendars/$eid?start=${URLEncoder.encode(start, "UTF-8")}&end=${URLEncoder.encode(end, "UTF-8")}"
            ctx.fetchJson(path) { json ->
                if (gen != generation) return@fetchJson
                // A failed or timed-out read keeps this calendar's last answer: only HA saying "no events" empties it.
                val arr = json as? JSONArray
                results[i] = if (arr != null) parseEvents(arr, eid).also { lastEvents[eid] = it } else lastEvents[eid].orEmpty()
                if (--pending == 0) {
                    render(upcoming(results.filterNotNull().flatten(), System.currentTimeMillis()))
                    if (isAttachedToWindow && isShown) ctx.main.postDelayed(refresh, REFRESH_MS)
                }
            }
        }
    }

    private fun render(events: List<CalendarEvent>) {
        list.removeAllViews()
        if (events.isEmpty()) {
            list.addView(extLabel(ctx, 14f, ctx.theme.secondaryTextColor).apply { text = "No upcoming events" })
            return
        }
        val tz = TimeZone.getDefault()
        var lastDay = ""
        events.forEachIndexed { idx, ev ->
            val (weekday, day) = dateBadge(ev.startMs, tz)
            val dayKey = "$weekday $day"
            val row = LinearLayout(ctx.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                setOnClickListener { ctx.openMoreInfo(ev.calendarId) }
            }
            val badge = LinearLayout(ctx.context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            if (dayKey != lastDay) {
                badge.addView(extLabel(ctx, 12f, ctx.theme.secondaryTextColor, Inter.MEDIUM).apply { text = weekday })
                badge.addView(extLabel(ctx, 20f, ctx.theme.primaryTextColor).apply { text = day })
                lastDay = dayKey
            }
            row.addView(badge, LinearLayout.LayoutParams(ctx.px(44f), WRAP_CONTENT))
            val color = PALETTE[ids.indexOf(ev.calendarId).coerceAtLeast(0) % PALETTE.size]
            row.addView(View(ctx.context).apply { background = pillBackground(color) },
                LinearLayout.LayoutParams(ctx.px(8f), ctx.px(8f)).apply { marginEnd = ctx.px(10f) })
            val text = LinearLayout(ctx.context).apply { orientation = LinearLayout.VERTICAL }
            text.addView(extLabel(ctx, 14f, ctx.theme.primaryTextColor, Inter.MEDIUM).apply {
                this.text = ev.summary
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            text.addView(extLabel(ctx, 12f, ctx.theme.secondaryTextColor).apply { this.text = timeRange(ev, tz) },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ctx.px(3f) })
            row.addView(text, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            list.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { if (idx > 0) topMargin = ctx.px(10f) })
        }
    }

    companion object {
        private const val DAYS = 7
        private const val MAX_EVENTS = 20
        private const val REFRESH_MS = 15 * 60_000L
        private val PALETTE = intArrayOf(
            0xFF03A9F4.toInt(), 0xFFFF9800.toInt(), 0xFF4CAF50.toInt(), 0xFFE91E63.toInt(),
            0xFF9C27B0.toInt(), 0xFF009688.toInt(), 0xFFFFC107.toInt(), 0xFF795548.toInt(),
        )

        /** The card's `entities` (ids or `{entity}` objects), else its single `entity`; only `calendar.*` ids. */
        fun calendarIds(config: CardConfig): List<String> {
            val fromList = config.entities().filter { it.isEntity }.map { it.entity }
            return fromList.ifEmpty { listOfNotNull(config.entity) }.filter { it.startsWith("calendar.") }.distinct()
        }

        /** Events of one calendar from the REST result; `start` / `end` are `{dateTime}`, `{date}` or bare strings. */
        fun parseEvents(arr: JSONArray?, calendarId: String, tz: TimeZone = TimeZone.getDefault()): List<CalendarEvent> {
            if (arr == null) return emptyList()
            val out = ArrayList<CalendarEvent>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val (start, allDay) = parseWhen(o.opt("start"), tz) ?: continue
                val end = parseWhen(o.opt("end"), tz)?.first ?: start
                out += CalendarEvent(o.optString("summary", "").ifEmpty { "Untitled" }, start, end, allDay, calendarId)
            }
            return out
        }

        /** Events still to come (or under way) at [nowMs], soonest first, capped for the card. */
        fun upcoming(events: List<CalendarEvent>, nowMs: Long): List<CalendarEvent> =
            events.filter { it.endMs > nowMs || it.startMs >= nowMs }
                .sortedWith(compareBy({ it.startMs }, { !it.allDay }))
                .take(MAX_EVENTS)

        private fun parseWhen(v: Any?, tz: TimeZone): Pair<Long, Boolean>? {
            val text = when (v) {
                is JSONObject -> v.optString("dateTime", "").ifEmpty { v.optString("date", "") }
                is String -> v
                else -> ""
            }.trim()
            if (text.isEmpty()) return null
            if (DATE_ONLY.matches(text)) {
                val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { timeZone = tz; isLenient = false }
                return runCatching { fmt.parse(text)?.time }.getOrNull()?.let { it to true }
            }
            val sec = EntityStore.parseIsoEpochSeconds(text) ?: return null
            return (sec * 1000.0).toLong() to false
        }

        private val DATE_ONLY = Regex("""^\d{4}-\d{2}-\d{2}$""")

        /** `("Mon", "24")` for the date badge. */
        fun dateBadge(ms: Long, tz: TimeZone): Pair<String, String> {
            val wd = SimpleDateFormat("EEE", Locale.US).apply { timeZone = tz }.format(Date(ms))
            val d = SimpleDateFormat("d", Locale.US).apply { timeZone = tz }.format(Date(ms))
            return wd to d
        }

        /** `10:00 – 11:00`, or `All day`. */
        fun timeRange(ev: CalendarEvent, tz: TimeZone): String {
            if (ev.allDay) return "All day"
            val fmt = SimpleDateFormat("HH:mm", Locale.ROOT).apply { timeZone = tz }
            val s = fmt.format(Date(ev.startMs))
            return if (ev.endMs > ev.startMs) "$s – ${fmt.format(Date(ev.endMs))}" else s
        }
    }
}

// ---------------------------------------------------------------------------------------------
// logbook
// ---------------------------------------------------------------------------------------------

/** One `logbook/get_events` row. [whenSec] is epoch seconds. */
data class LogbookEntry(
    val entityId: String,
    val name: String?,
    val message: String,
    val state: String?,
    val whenSec: Double,
)

/**
 * Native HA `logbook` card: the newest 12 logbook rows of the card's `entities` over `hours_to_show`, each with
 * the domain icon, "<name> turned on" and how long ago. A row opens that entity's More-Info.
 */
class LogbookCardView(ctx: VitroCardContext, private val config: CardConfig) : CardSurface(ctx) {
    private val list = LinearLayout(ctx.context).apply { orientation = LinearLayout.VERTICAL }
    private val ids: List<String> = config.entities().filter { it.isEntity }.map { it.entity }
        .ifEmpty { listOfNotNull(config.entity) }.distinct()
    private val hours = config.raw.optInt("hours_to_show", 24).coerceIn(1, 24 * 30)
    private val refresh = Runnable { fetch() }
    private var generation = 0

    init {
        val pad = ctx.px(16f)
        val col = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        col.addView(extLabel(ctx, 16f, ctx.theme.primaryTextColor, Inter.MEDIUM).apply { text = config.title ?: "Logbook" },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = ctx.px(10f) })
        col.addView(list, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(col, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        render(emptyList())
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        fetch()
    }

    override fun onDetachedFromWindow() {
        ctx.main.removeCallbacks(refresh)
        generation++
        super.onDetachedFromWindow()
    }

    /**
     * A cached view another one hides stays attached: it stops polling Home Assistant (an answer still on its way is
     * dropped), and asks once again when it shows.
     */
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (!isAttachedToWindow) return
        if (isShown) {
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
        val now = System.currentTimeMillis()
        val msg = JSONObject()
            .put("type", "logbook/get_events")
            .put("start_time", StatisticsGraphCardView.isoUtc(Date(now - hours * 3_600_000L)))
            .put("end_time", StatisticsGraphCardView.isoUtc(Date(now)))
            .put("entity_ids", JSONArray(ids))
        ctx.callWs(msg) { ok, result, _ ->
            val rows = if (ok) parseEntries(result as? JSONArray) else null
            ctx.main.post {
                if (gen != generation) return@post
                if (rows != null) render(rows)
                if (isAttachedToWindow && isShown) ctx.main.postDelayed(refresh, REFRESH_MS)
            }
        }
    }

    private fun render(entries: List<LogbookEntry>) {
        list.removeAllViews()
        if (entries.isEmpty()) {
            list.addView(extLabel(ctx, 14f, ctx.theme.secondaryTextColor).apply { text = "No logbook events found" })
            return
        }
        val nowSec = System.currentTimeMillis() / 1000.0
        entries.forEachIndexed { idx, e ->
            val live = ctx.store[e.entityId]
            val iconState = EntityState(e.entityId, e.state ?: live?.state.orEmpty(), live?.attributes.orEmpty())
            val row = LinearLayout(ctx.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                setOnClickListener { ctx.openMoreInfo(e.entityId) }
            }
            row.addView(MdiIconView(ctx.context).apply {
                setIcon(MdiIcons.resolveIconName(null, iconState), iconColorFor(ctx, iconState))
            }, LinearLayout.LayoutParams(ctx.px(20f), ctx.px(20f)).apply { marginEnd = ctx.px(12f) })
            val text = LinearLayout(ctx.context).apply { orientation = LinearLayout.VERTICAL }
            val name = e.name ?: live?.friendlyName ?: e.entityId
            text.addView(extLabel(ctx, 13f, ctx.theme.primaryTextColor).apply {
                this.text = "$name ${e.message}"
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            })
            text.addView(extLabel(ctx, 11f, ctx.theme.secondaryTextColor).apply {
                this.text = relativeTime(e.whenSec, nowSec)
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ctx.px(3f) })
            row.addView(text, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            list.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { if (idx > 0) topMargin = ctx.px(10f) })
        }
    }

    companion object {
        private const val MAX_ROWS = 12
        private const val REFRESH_MS = 60_000L

        /** The newest [MAX_ROWS] rows with an entity, newest first. */
        fun parseEntries(arr: JSONArray?): List<LogbookEntry> {
            if (arr == null) return emptyList()
            val out = ArrayList<LogbookEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val eid = o.optString("entity_id", "").ifEmpty { continue }
                val state = if (o.has("state") && !o.isNull("state")) o.optString("state") else null
                val message = o.optString("message", "").ifEmpty { stateMessage(state) }
                out += LogbookEntry(eid, o.optString("name", "").ifEmpty { null }, message, state, EntityStore.parseTimestamp(o.opt("when")))
            }
            return out.sortedByDescending { it.whenSec }.take(MAX_ROWS)
        }

        /** HA's wording for a state change with no `message`. */
        fun stateMessage(state: String?): String = when (state) {
            null -> "changed"
            "on" -> "turned on"
            "off" -> "turned off"
            "unavailable" -> "became unavailable"
            "unknown" -> "became unknown"
            else -> "changed to ${state.replace('_', ' ')}"
        }

        /** "5 minutes ago", by the same rules as an entity's last-changed line. */
        fun relativeTime(whenSec: Double, nowSec: Double): String =
            EntityState("logbook.entry", "", emptyMap(), lastChanged = whenSec).relativeLastChanged(nowSec) ?: ""
    }
}
