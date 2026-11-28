/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.cards

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.glassworks.engine.vitro.data.HaIntl
import dev.glassworks.engine.vitro.data.HaText
import dev.glassworks.engine.vitro.data.HaLocale
import dev.glassworks.engine.vitro.data.HaFormat
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.data.HaDisplay
import dev.glassworks.engine.vitro.data.LightColor
import dev.glassworks.engine.vitro.model.ActionConfig
import dev.glassworks.engine.vitro.model.AreaCardConfig
import dev.glassworks.engine.vitro.model.CardConfig
import dev.glassworks.engine.vitro.ui.A11yRole
import dev.glassworks.engine.vitro.ui.AdjustableAccess
import dev.glassworks.engine.vitro.ui.CardSurface
import dev.glassworks.engine.vitro.ui.LocalRelayout
import dev.glassworks.engine.vitro.ui.MdiIconView
import dev.glassworks.engine.vitro.ui.MdiIcons
import dev.glassworks.engine.vitro.ui.VitroAccess
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

    /**
     * A new value or unit (text sized to itself) lays out this card again, not the dashboard, as long as the card keeps
     * its size ([LocalRelayout]): the 5 Hz sensor of a long view no longer measures the whole view. Null while
     * CardSurface's constructor runs.
     */
    private var local: LocalRelayout? = LocalRelayout(this)

    override fun requestLayout() {
        if (local?.take() != true) super.requestLayout()
    }

    override fun measureCard(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        local?.onMeasure(widthMeasureSpec, heightMeasureSpec)
        super.measureCard(widthMeasureSpec, heightMeasureSpec)
    }

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
        // `ha-card`: a column with `justify-content: space-between` over the header, `.info` and `.footer` (an empty
        // div without one), so a card taller than its content (a sections slot, `height: 100%`) splits the rest into
        // the two gaps: the value line sits lower than right under the header. With a pinned footer
        // (`.with-fixed-footer`) it's `flex-start`. The gaps only grow when the card has an exact height.
        val spaced = !fixedFooter
        if (spaced) col.addView(View(ctx.context), LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        col.addView(info, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = -ctx.px(4f) })
        if (spaced) col.addView(View(ctx.context), LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        if (footer != null && !fixedFooter) col.addView(footer, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(col, LayoutParams(MATCH_PARENT, if (spaced) MATCH_PARENT else WRAP_CONTENT))
        if (footer != null && fixedFooter) addView(footer, LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))

        val eid = config.entity
        isClickable = true
        setOnClickListener { ctx.performAction(ActionConfig.parse(config.raw.optJSONObject("tap_action"), "more-info"), eid) }
        bindHoldAction(ActionConfig.parse(config.raw.optJSONObject("hold_action"), "more-info"), eid)
        enablePressFeedback()
        if (eid != null) observe(eid, ::update) else render(null)
    }

    /** A state change: only what's inside the card changes ([render]), so [local] lays out only the card. */
    private fun update(s: EntityState?) {
        val l = local
        if (l == null) render(s) else l.run { render(s) }
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
        // The same text isn't set again: setText lays the text out again, and asks for a layout, even then.
        nameView.setTextIfChanged(config.name(s) ?: s?.displayName ?: config.entity.orEmpty())
        val color = if (colored(s)) stateColor(s) else null
        // HA's `filter: stateColorBrightness`: a colored light's icon dims with its brightness.
        iconView.setIcon(MdiIcons.resolveIconName(config.icon, s), color?.let { stateColorBrightness(s, it) } ?: ctx.theme.stateIconColor)
        val (value, unit) = valueAndUnit(config.raw, s)
        valueView.setTextIfChanged(value)
        unitView.setTextIfChanged(unit)
        val unitVisibility = if (unit.isEmpty()) GONE else VISIBLE
        if (unitView.visibility != unitVisibility) unitView.visibility = unitVisibility
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
            // formatEntityStateToParts: HA's formatEntityState, without the tile's extras ("On", not "65%").
            return s.plainState() to ""
        }
    }
}

// ---------------------------------------------------------------------------------------------
// light
// ---------------------------------------------------------------------------------------------

/**
 * Native HA `light` card (`hui-light-card`): full width; a 200 px `ha-control-circular-slider` (brightness, in the
 * primary color) around a 120 px light button with the entity's icon (no dial for a light that doesn't dim), the ⋮
 * more-info button top right, and under them a line that is hidden but while a drag shows the brightness, or the state
 * while unavailable or unknown, and the name, 16.8 px, pulled up 56 px over the controls. The light button runs
 * `tap_action` (toggle by default) and `hold_action` (more-info); the name has none.
 */
class LightCardView(ctx: VitroCardContext, private val config: CardConfig) : CardSurface(ctx) {
    // Both lines sit on HA's 1.6 line height (26.9 px). HA's name is a plain text node: a long one wraps.
    private val nameView = extLabel(ctx, 16.8f, ctx.theme.primaryTextColor).apply {
        gravity = Gravity.CENTER
        minHeight = ctx.px(26.9f)
    }
    private val brightnessView = extLabel(ctx, 16.8f, ctx.theme.primaryTextColor).apply {
        gravity = Gravity.CENTER
        minHeight = ctx.px(26.9f)
    }
    private val dial = BrightnessDialView(ctx.context, ctx.theme.activeColor("light"), ctx.theme.disabledTextColor, ctx.theme.stateIconColor, ctx.theme.primaryColor)
    private val hideBrightness = Runnable { if (showsBrightness) brightnessView.visibility = INVISIBLE }
    private var showsBrightness = false

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
            contentDescription = HaText.ui("ui.panel.lovelace.editor.action-editor.actions.more-info")
            addView(MdiIconView(ctx.context).apply { setIcon("mdi:dots-vertical", ctx.theme.secondaryTextColor) },
                FrameLayout.LayoutParams(ctx.px(24f), ctx.px(24f), Gravity.CENTER))
            setOnClickListener { eid?.let(ctx.openMoreInfo) }
            applyHaRipple(this, ctx.px(24f).toFloat(), ctx.theme.dark)
        }, LayoutParams(ctx.px(48f), ctx.px(48f), Gravity.TOP or Gravity.END))

        // hui-light-card: the center button runs `tap_action` (toggle unless configured); the name and brightness
        // under it take no taps (the ⋮ button opens More Info).
        val tap = ActionConfig.parse(config.raw.optJSONObject("tap_action"), "toggle")
        if (eid != null) {
            dial.onToggle = { ctx.performAction(tap, eid) }
            // The same button's hold: `hold_action`, more-info unless configured.
            dial.bindHoldAction(ctx, ActionConfig.parse(config.raw.optJSONObject("hold_action"), "more-info"), eid)
            dial.onBrightness = { pct -> setBrightness(eid, pct) }
            dial.onDragging = { pct ->
                showDragBrightness(pct)
                describeDial(ctx.store[eid])
            }
            observe(eid, ::render)
        } else {
            render(null)
        }
    }

    /** HA's slider runs 1–100 and sends `brightness_pct`: a drag to its start dims the light, it never turns it off. */
    private fun setBrightness(eid: String, pct: Int) {
        val value = pct.coerceIn(1, 100)
        val bri = (value * 255.0 / 100).roundToInt().coerceIn(1, 255)
        val current = ctx.store[eid]
        // An unavailable light takes no write: a release can land before the render that disables the dial.
        if (current?.isUnavailable == true) return
        ctx.callOptimistic(
            current?.let { it.copy(state = "on", attributes = it.attributes + ("brightness" to bri)) },
            "light", "turn_on", JSONObject().put("entity_id", eid).put("brightness_pct", value),
        )
    }

    /** HA's `.brightness` line: "<value> %" while a drag is under way, gone half a second after it ends. */
    private fun showDragBrightness(pct: Int?) {
        if (!showsBrightness) return
        removeCallbacks(hideBrightness)
        if (pct != null) {
            brightnessView.text = "${pct.coerceIn(1, 100)} %"
            brightnessView.visibility = VISIBLE
        } else {
            postDelayed(hideBrightness, 500)
        }
    }

    private fun render(s: EntityState?) {
        val name = config.name(s) ?: s?.displayName ?: config.entity.orEmpty()
        if (nameView.text.toString() != name) {
            nameView.text = name
            // Every line of a wrapped name on HA's 1.6 line height.
            lineBox(ctx, nameView, 16.8f, 1.6f)
        }
        val on = s?.state == "on"
        val unavailable = s?.state == "unavailable"
        val pct = s?.brightnessPct
        // HA's line above the name: the state while unavailable or unknown, else the brightness, hidden (its place
        // kept) but while a drag shows it.
        val wasShowing = showsBrightness
        showsBrightness = s != null && !s.isUnavailable
        if (showsBrightness && wasShowing) {
            // Still available: a drag's percentage (and its half-second linger) stays through HA's updates.
        } else {
            removeCallbacks(hideBrightness)
            brightnessView.text = if (showsBrightness) "" else s?.formattedState() ?: ""
            brightnessView.visibility = if (showsBrightness) INVISIBLE else VISIBLE
        }
        val dims = s != null && lightSupportsBrightness(s)
        dial.update(on, if (on && pct != null) BrightnessDialView.fractionOf(pct) else 0f, MdiIcons.resolveIconName(config.icon, s), lightButtonColor(s), dims, enabled = !unavailable)
        describeDial(s)
    }

    /** What TalkBack reads for the dial: the brightness an edit under way shows (a drag, a held key), else HA's. */
    private fun describeDial(s: EntityState?) {
        val on = s?.state == "on"
        val pct = dial.editPercent ?: s?.brightnessPct
        val dims = s != null && lightSupportsBrightness(s)
        dial.contentDescription = when {
            s == null || s.isUnavailable -> s?.formattedState() ?: "–"
            !on && dial.editPercent == null -> s.formattedState()
            dims && pct != null -> "${HaText.ui("ui.card.light.brightness")} ${HaFormat.formatNumber(pct)}${HaFormat.blankBeforePercent(HaLocale.current)}%"
            else -> s.formattedState()
        }
    }

    /**
     * HA's light button color: `rgb_color` while on, else `--state-light-active-color`, through `stateColorBrightness`
     * (dimmer with the brightness); the state icon color while off; HA's disabled button color while unavailable.
     */
    private fun lightButtonColor(s: EntityState?): Int = when {
        s?.state == "unavailable" -> disabledButtonColor(ctx.theme)
        s?.state == "on" -> stateColorBrightness(s, rgbColor(s) ?: ctx.theme.activeColor("light"))
        else -> ctx.theme.stateIconColor
    }

    companion object {
        private val BRIGHTNESS_MODES = setOf("hs", "xy", "rgb", "rgbw", "rgbww", "color_temp", "brightness", "white")

        /** HA's `lightSupportsBrightness`: a supported color mode that dims. No modes: no dial. */
        fun lightSupportsBrightness(s: EntityState): Boolean =
            (s.attributes["supported_color_modes"] as? List<*>)?.any { it?.toString() in BRIGHTNESS_MODES } == true

        /** `rgb_color` as HA's light card reads it, if the light reports one. */
        fun rgbColor(s: EntityState): Int? {
            val c = (s.attributes["rgb_color"] as? List<*>)?.take(3)?.map { (it as? Number)?.toInt() } ?: return null
            return if (c.size == 3 && c.all { it != null }) LightColor.pack(c[0]!!, c[1]!!, c[2]!!) else null
        }

        /**
         * A disabled `ha-icon-button`: `--ha-color-on-disabled-quiet` (neutral-40 dark, neutral-80 light) at half
         * opacity, as HA's unavailable light button draws (measured: #3d3d3d on the dark card).
         */
        fun disabledButtonColor(theme: dev.glassworks.engine.vitro.theme.ResolvedTheme): Int {
            val c = ThemeResolver.parseColor(theme.vars["ha-color-on-disabled-quiet"])
                ?: if (theme.dark) 0xFF5E5E5E.toInt() else 0xFFCCCCCC.toInt()
            return CardSurface.withAlpha(c, 0.5f)
        }
    }
}

/**
 * HA's `ha-control-circular-slider` for a light (320-unit geometry): a 270° track (radius 145, stroke 24) in the
 * disabled color, the brightness arc in the primary color (`--control-circular-slider-color`), a white handle
 * (radius 9) in a 3-unit ring of the primary color; the light button's icon (118 px of 200) centered, in the color
 * the card gives. Without [dims] (a light that doesn't dim) only the icon. Disabled (unavailable), the track alone,
 * and neither a drag nor a tap does anything. Drag the ring to set brightness, tap the icon to toggle, hold it for
 * the hold action.
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
    private var enabled = true
    private var dragFraction: Float? = null

    /**
     * The brightness last sent, shown and stepped from until the next [update] (the optimistic render, or HA's value
     * again if the write is turned down): a second TalkBack step before that render goes on from it.
     */
    private var sentFraction: Float? = null
    private var touchMode = TOUCH_NONE
    /** Whether the press on the light button became a hold ([performLongClick]), so its release isn't also a tap. */
    private var held = false
    private val holdCheck = Runnable { if (touchMode == TOUCH_TOGGLE) held = performLongClick() }

    var onToggle: (() -> Unit)? = null
    var onBrightness: ((Int) -> Unit)? = null

    /**
     * The brightness under a drag, in percent, as it moves; null when it ends. A key edit tells it each step, then null
     * (HA's `value-changing` per keydown: the percentage shows, and goes half a second after the last one).
     */
    var onDragging: ((Int?) -> Unit)? = null

    /** The brightness an edit under way shows (a drag or a held key), in percent; null with none. */
    val editPercent: Int? get() = dragFraction?.let { percentAt(it) }

    /**
     * [iconColor] is the light button's color; without one, the active color while on (or dragged up), else the
     * state icon color.
     */
    fun update(isOn: Boolean, frac: Float, iconName: String, iconColor: Int? = null, dimmable: Boolean = true, enabled: Boolean = true) {
        on = isOn
        fraction = frac.coerceIn(0f, 1f)
        basePath = MdiIcons.pathFor(iconName)
        tint = iconColor
        dims = dimmable
        val dragEnded = !enabled && touchMode == TOUCH_DRAG
        if (!enabled && touchMode != TOUCH_NONE) {
            // The light went unavailable mid-gesture: the whole gesture ends (no MOVE or UP of it acts later, no hold
            // fires), and the dial waits for a fresh press.
            touchMode = TOUCH_NONE
            removeCallbacks(holdCheck)
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        if (!enabled) {
            dragFraction = null
            // A key edit under way ends too: its release must not send (an off light's fallback would turn it on at 1 %).
            access.cancel()
        }
        // Told once the preview is gone, so the card describes what HA reports, not the dropped drag.
        if (dragEnded) onDragging?.invoke(null)
        this.enabled = enabled
        isEnabled = enabled
        sentFraction = null
        if (dragFraction == null) invalidate()
    }

    /** The dial's size in px (the view is square). */
    private val size: Float get() = minOf(width, height).toFloat()
    private val radius: Float get() = size * 145f / 320f

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val u = size / 320f
        if (dims) {
            val r = radius
            oval.set(cx - r, cy - r, cx + r, cy + r)
            arcPaint.strokeWidth = 24f * u
            arcPaint.color = trackColor
            canvas.drawArc(oval, START_ANGLE, SWEEP, false, arcPaint)
        }
        if (dims && enabled) {
            val r = radius
            val shown = dragFraction ?: sentFraction ?: if (on) fraction else 0f
            if (shown > 0f) {
                arcPaint.color = handleRingColor
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
        val lit = on || (dragFraction ?: sentFraction ?: 0f) > 0f
        // The light button's icon: 118 px of the 200 px dial.
        val scale = (118f / 200f) * size / 24f
        iconMatrix.reset()
        iconMatrix.setScale(scale, scale)
        iconMatrix.postTranslate(cx - 12f * scale, cy - 12f * scale)
        iconPath.set(basePath)
        iconPath.transform(iconMatrix)
        fillPaint.color = tint ?: if (lit) activeColor else offIconColor
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
                    !enabled -> TOUCH_NONE
                    d <= r * 0.55f -> TOUCH_TOGGLE
                    dims && d <= r * 1.35f && d >= r * 0.6f -> TOUCH_DRAG
                    else -> TOUCH_NONE
                }
                if (touchMode == TOUCH_NONE) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                held = false
                // The light button's hold, timed as Android times a long press (its listener runs hold_action).
                if (touchMode == TOUCH_TOGGLE) postDelayed(holdCheck, ViewConfiguration.getLongPressTimeout().toLong())
                if (touchMode == TOUCH_DRAG) {
                    dragFraction = fractionAt(event.x, event.y)
                    onDragging?.invoke(dragPercent())
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> if (touchMode == TOUCH_DRAG) {
                dragFraction = fractionAt(event.x, event.y)
                onDragging?.invoke(dragPercent())
                invalidate()
            } else if (touchMode == TOUCH_TOGGLE && d > r * 0.6f) {
                removeCallbacks(holdCheck)
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(holdCheck)
                if (touchMode == TOUCH_DRAG) onDragging?.invoke(null)
                when (touchMode) {
                    TOUCH_DRAG -> dragFraction?.let {
                        val pct = percentAt(it)
                        // Before the call: a render it triggers at once clears it.
                        sentFraction = fractionOf(pct)
                        onBrightness?.invoke(pct)
                        // TalkBack reads the brightness the drag left, as it does a SeekBar's.
                        VitroAccess.valueChanged(this)
                    }
                    // One path for a tap, a keyboard and TalkBack: performClick toggles.
                    TOUCH_TOGGLE -> if (!held && d <= r * 0.6f) performClick()
                }
                dragFraction = null
                touchMode = TOUCH_NONE
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(holdCheck)
                // Nothing was sent: the preview goes first, so the card describes HA's brightness again.
                dragFraction = null
                if (touchMode == TOUCH_DRAG) onDragging?.invoke(null)
                touchMode = TOUCH_NONE
                invalidate()
            }
        }
        return true
    }

    private fun dragPercent(): Int? = dragFraction?.let(::percentAt)

    init {
        isClickable = true
        isFocusable = true
    }

    override fun performClick(): Boolean {
        super.performClick()
        // HA's light button is disabled while the light is unavailable.
        if (enabled) onToggle?.invoke()
        return true
    }

    /**
     * TalkBack and keys, for a light that dims: HA's `role="slider"` (0–100 %), whose click still toggles. A key edit
     * is sent when its key comes up, as a drag is when it's let go; TalkBack's steps are sent at once. An on/off light
     * stays a button.
     */
    private val access = AdjustableAccess(
        min = { 1 }, max = { 100 }, step = { 1 },
        value = { percentAt(dragFraction ?: sentFraction ?: fraction) },
        preview = { v ->
            dragFraction = fractionOf(v)
            invalidate()
            onDragging?.invoke(v)
            onDragging?.invoke(null)
            VitroAccess.valueChanged(this)
        },
        commit = { v ->
            dragFraction = null
            // Before the call: a render it triggers at once clears it.
            sentFraction = fractionOf(v)
            onBrightness?.invoke(v)
            invalidate()
        },
        // A cancelled key or focus gone mid-edit: back to HA's brightness, nothing sent.
        restore = {
            dragFraction = null
            invalidate()
            // The card describes HA's brightness again.
            onDragging?.invoke(null)
        },
    )

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        if (dims && enabled) access.onInitialize(info) else info.className = A11yRole.BUTTON.className
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean =
        (if (dims && enabled) access.perform(action, arguments) else null) ?: super.performAccessibilityAction(action, arguments)

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        (dims && enabled && access.keyDown(keyCode)) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        access.keyUp(event) || super.onKeyUp(keyCode, event)

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (!gainFocus) access.cancel()
    }

    companion object {
        private const val START_ANGLE = 135f
        private const val SWEEP = 270f
        private const val TOUCH_NONE = 0
        private const val TOUCH_TOGGLE = 1
        private const val TOUCH_DRAG = 2

        /**
         * HA's light slider runs 1–100 (`ha-control-circular-slider` min 1, max 100): the arc's start is 1 %, its end
         * 100 %, so a quarter sweep is 26 %. Touch, the handle, keys and TalkBack all map through these.
         */
        fun percentAt(fraction: Float): Int = (1f + fraction.coerceIn(0f, 1f) * 99f).roundToInt().coerceIn(1, 100)

        fun fractionOf(percent: Int): Float = (percent.coerceIn(1, 100) - 1) / 99f
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
    private val codeLabel = extLabel(ctx, 14f, ctx.theme.secondaryTextColor).apply { text = HaText.ui("ui.card.alarm_control_panel.code") }
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
        hint = HaText.ui("ui.card.alarm_control_panel.code")
        setTextColor(ctx.theme.primaryTextColor)
        setHintTextColor(ctx.theme.secondaryTextColor)
        visibility = GONE
    }
    private val code = StringBuilder()
    private val armStates: List<String> = readStates(config.raw.optJSONArray("states"))

    /**
     * The arm buttons: the card's `states` as written, or, without them, HA's defaults (arm home, arm away) that the
     * alarm supports (`supported_features`), as hui-alarm-panel-card filters them.
     */
    private fun shownArmStates(s: EntityState?): List<String> =
        if (config.raw.optJSONArray("states") != null) armStates else DEFAULT_STATES.filter { supports(s, it) }

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
                    b.contentDescription = if (key == KEY_CLEAR) HaText.ui("ui.card.alarm_control_panel.clear_code") else key
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
        // HA's hui-alarm-panel-card: formatEntityName, the device and entity names when no `name` is set.
        titleView.text = config.name(s) ?: s?.displayName ?: "Alarm"
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
        // hui-alarm-panel-card: the arm buttons only while disarmed, Disarm in any other state (unavailable too).
        val buttons = if (state == "disarmed" || s == null) shownArmStates(s) else listOf("disarm")
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

    /** Forgets a code that wasn't submitted: on a shared panel the next person must not find it entered. */
    private fun clearCode() {
        if (code.isEmpty() && textCode.text.isNullOrEmpty()) return
        code.setLength(0)
        textCode.setText("")
        renderCode()
    }

    // Called for this card and for any ancestor (a cached view hidden behind another): API 23 has no aggregate.
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) clearCode()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE) clearCode()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        clearCode()
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

        /** The card's `states`, as HA uses them: an empty list shows no arm buttons; no `states`, HA's defaults. */
        fun readStates(arr: JSONArray?): List<String> {
            if (arr == null) return DEFAULT_STATES
            return (0 until arr.length()).mapNotNull { arr.optString(it, "").ifEmpty { null } }
        }

        /** HA's AlarmControlPanelEntityFeature bit for each arm action. */
        private val ARM_FEATURE = mapOf("arm_home" to 1, "arm_away" to 2, "arm_night" to 4, "arm_custom_bypass" to 16, "arm_vacation" to 32)

        /** Whether [s] supports [action] (HA's `supportsFeature`); an entity without `supported_features` supports none. */
        fun supports(s: EntityState?, action: String): Boolean {
            val bits = (s?.attributes?.get("supported_features") as? Number)?.toInt() ?: return false
            val bit = ARM_FEATURE[action] ?: return true
            return bits and bit != 0
        }

        /** `arm_home` → `alarm_arm_home`, `disarm` → `alarm_disarm`. */
        fun serviceFor(action: String): String = "alarm_$action"

        /** HA's button label: `ui.card.alarm_control_panel.<action>`. */
        fun actionLabel(action: String): String = when (action) {
            "disarm" -> HaText.ui("ui.card.alarm_control_panel.disarm")
            "arm_home" -> HaText.ui("ui.card.alarm_control_panel.arm_home")
            "arm_away" -> HaText.ui("ui.card.alarm_control_panel.arm_away")
            "arm_night" -> HaText.ui("ui.card.alarm_control_panel.arm_night")
            "arm_vacation" -> HaText.ui("ui.card.alarm_control_panel.arm_vacation")
            "arm_custom_bypass" -> HaText.ui("ui.card.alarm_control_panel.arm_custom_bypass")
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

/** One item of a `todo.*` list, as `todo/item/subscribe` sends it. */
/** [pending]: added here and not yet listed by Home Assistant (its [uid] is made up; a real one can look alike). */
/** [due]: a date (`2026-11-26`) or a date and time; [description] as HA keeps it. */
data class TodoItem(
    val uid: String,
    val summary: String,
    val completed: Boolean,
    val pending: Boolean = false,
    val due: String? = null,
    val description: String? = null,
)

/** Native HA `todo-list` card (`hui-todo-list-card`): live items, checkboxes, completed section and add row. */
class TodoListCardView(ctx: VitroCardContext, private val config: CardConfig) : CardSurface(ctx) {
    private val titleView = extLabel(ctx, 24f, ctx.theme.primaryTextColor)
    private val activeHeader = sectionHeader(HaText.ui("ui.panel.lovelace.cards.todo-list.unchecked_items"), null)
    private val activeBox = LinearLayout(ctx.context).apply { orientation = LinearLayout.VERTICAL }
    private val completedDivider = View(ctx.context).apply { setBackgroundColor(ctx.theme.dividerColor) }
    private val completedHeader = sectionHeader(HaText.ui("ui.panel.lovelace.cards.todo-list.checked_items")) { anchor ->
        android.widget.PopupMenu(ctx.context, anchor).apply {
            menu.add(HaText.ui("ui.panel.lovelace.cards.todo-list.clear_items")).setOnMenuItemClickListener { clearCompleted(); true }
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

    /** The list's `supported_features` (CREATE 1, DELETE 2, UPDATE 4, MOVE 8): what HA offers on it. */
    private var features = 0
    private val sortMode = config.raw.optString("display_order", "none").ifEmpty { "none" }

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
            // Shown once the list says it can create items (HA's CREATE_TODO_ITEM), unless `hide_create`.
            visibility = GONE
        }
        input.apply {
            hint = HaText.ui("ui.panel.lovelace.cards.todo-list.add_item")
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
            contentDescription = HaText.ui("ui.panel.lovelace.cards.todo-list.add_item")
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
        list.addView(activeHeader, LinearLayout.LayoutParams(MATCH_PARENT, HEADER_PX(ctx)))
        list.addView(activeBox, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        list.addView(completedDivider, LinearLayout.LayoutParams(MATCH_PARENT, ctx.px(1f).coerceAtLeast(1)).apply {
            topMargin = ctx.px(10f); bottomMargin = ctx.px(10f)
        })
        list.addView(completedHeader, LinearLayout.LayoutParams(MATCH_PARENT, HEADER_PX(ctx)))
        list.addView(completedBox, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        col.addView(list, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(col, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        renderItems()
    }

    /** A section header (44.2 px: the 14 px / 500 title on a 21 px line, 11.6 px above and below) and its menu. */
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
        if (failure != null) return
        val eid = config.entity ?: return
        // While the item subscription is down, an entity change reads the list again (the old path).
        observe(eid) { s ->
            applyFeatures(s)
            if (!itemsLive) fetch(eid)
        }
        subscribeItems(eid)
        track { itemsSub?.invoke(); itemsSub = null; ctx.main.removeCallbacks(resubscribe) }
    }

    /** The item subscription now; null while it's down or the card is off screen. */
    private var itemsSub: (() -> Unit)? = null

    /** True while the item subscription is up: it brings every change, renames included. */
    private var itemsLive = true

    private val resubscribe = Runnable { config.entity?.let { if (isAttachedToWindow && itemsSub == null && failure == null) subscribeItems(it) } }

    override fun onCardFailed() {
        itemsSub?.invoke()
        itemsSub = null
        ctx.main.removeCallbacks(resubscribe)
        // A read already on its way lands on nothing.
        fetchGeneration++
    }

    /**
     * HA's card keeps one `todo/item/subscribe` while it shows: every event is the whole list, so a rename (which
     * leaves the entity's count alone) shows too. Rejected, it falls back to a read (and reads on entity changes)
     * and tries again later.
     */
    private fun subscribeItems(eid: String) {
        itemsSub = ctx.subscribeWs(JSONObject().put("type", "todo/item/subscribe").put("entity_id", eid), { ev ->
            val parsed = parseItems(ev)
            ctx.main.post {
                if (!isAttachedToWindow) return@post
                itemsLive = true
                // Newer than any one-shot read still on its way.
                fetchGeneration++
                items = parsed
                renderItems()
            }
        }, { _ ->
            ctx.main.post {
                itemsSub = null
                if (!isAttachedToWindow || failure != null) return@post
                itemsLive = false
                fetch(eid)
                ctx.main.postDelayed(resubscribe, RESUBSCRIBE_MS)
            }
        })
    }

    /**
     * Reads the list as HA's card does, through `todo/item/subscribe`: its first event is the whole list, and the
     * subscription is dropped right after (a state change reads it again, as before).
     */
    private fun fetch(eid: String, retry: Boolean = true) {
        // A failed card reads nothing more; a healthy one whose item subscription was rejected still does.
        if (failure != null) return
        val gen = ++fetchGeneration
        var unsub: (() -> Unit)? = null
        var done = false
        fun finish() {
            done = true
            unsub?.let { u -> ctx.main.post { runCatching { u() } } }
        }
        unsub = ctx.subscribeWs(JSONObject().put("type", "todo/item/subscribe").put("entity_id", eid), { ev ->
            if (done) return@subscribeWs
            finish()
            val parsed = parseItems(ev)
            ctx.main.post {
                if (gen != fetchGeneration) return@post
                items = parsed
                renderItems()
            }
        }, { _ ->
            if (done) return@subscribeWs
            finish()
            // A row added here waits for this list to get its uid: ask once more rather than leave it untoggleable.
            if (retry) ctx.main.postDelayed({
                if (gen == fetchGeneration && isAttachedToWindow && items.any { it.pending }) fetch(eid, retry = false)
            }, RETRY_MS)
        })
        if (done) unsub?.let { u -> ctx.main.post { runCatching { u() } } }
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

    /**
     * HA shows what the list supports: the add row with CREATE (and no `hide_create`), the reorder menu with MOVE
     * (unsorted), the clear menu with DELETE.
     */
    private fun applyFeatures(s: EntityState?) {
        features = (s?.attributes?.get("supported_features") as? Number)?.toInt() ?: 0
        addRow.visibility = if (!hideCreate && features and CREATE != 0) VISIBLE else GONE
        activeHeader.getChildAt(1).visibility = if (features and MOVE != 0 && sortMode == "none") VISIBLE else GONE
        completedHeader.getChildAt(1).visibility = if (features and DELETE != 0) VISIBLE else GONE
    }

    private fun renderItems() {
        activeBox.removeAllViews()
        completedBox.removeAllViews()
        val active = items.filter { !it.completed }
        val done = items.filter { it.completed }
        val shown = if (maxItems > 0) active.take(maxItems) else active
        shown.forEach { activeBox.addView(itemRow(it), LinearLayout.LayoutParams(MATCH_PARENT, rowHeight(it))) }
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
                text = HaText.ui("ui.panel.lovelace.cards.todo-list.no_unchecked_items")
                setPadding(ctx.px(16f), ctx.px(12f), ctx.px(16f), ctx.px(12f))
            })
        }
        val showCompleted = done.isNotEmpty() && !hideCompleted
        completedDivider.visibility = if (showCompleted) VISIBLE else GONE
        completedHeader.visibility = if (showCompleted) VISIBLE else GONE
        completedBox.visibility = if (showCompleted) VISIBLE else GONE
        if (showCompleted) {
            done.forEach { completedBox.addView(itemRow(it), LinearLayout.LayoutParams(MATCH_PARENT, rowHeight(it))) }
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
        // ha-check-list-item: a checkbox named by its summary, checked once done.
        dev.glassworks.engine.vitro.ui.VitroAccess.role(row, dev.glassworks.engine.vitro.ui.A11yRole.CHECKBOX) { item.completed }
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
            gravity = Gravity.CENTER_VERTICAL
            if (item.completed) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        }
        if (!isMultiline(item)) {
            row.addView(text, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            return row
        }
        // A multiline row: the summary and each extra line 21 px tall (14 / 12 px text), 8 px above and below.
        val column = LinearLayout(ctx.context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(text, LinearLayout.LayoutParams(MATCH_PARENT, ctx.px(21f)))
        item.description?.let { d ->
            column.addView(extLabel(ctx, 12f, ctx.theme.secondaryTextColor).apply {
                this.text = d
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(MATCH_PARENT, ctx.px(21f)))
        }
        dueText(item)?.let { (label, overdue) ->
            // `.due`: a 14 px clock, then the time, in the secondary color (`--warning-color` once overdue, unless
            // the item is done: `.completed .due.overdue` is the secondary color again).
            val color = if (overdue && !item.completed) ThemeResolver.parseColor(ctx.theme.vars["warning-color"]) ?: WARNING else ctx.theme.secondaryTextColor
            column.addView(LinearLayout(ctx.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(MdiIconView(ctx.context).apply { setIcon("mdi:clock", color) }, LinearLayout.LayoutParams(ctx.px(14f), ctx.px(14f)).apply { marginEnd = ctx.px(4f) })
                addView(extLabel(ctx, 12f, color).apply { this.text = label; maxLines = 1 })
            }, LinearLayout.LayoutParams(MATCH_PARENT, ctx.px(21f)))
        }
        row.addView(column, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        return row
    }

    private fun isMultiline(item: TodoItem) = item.due != null || item.description != null

    private fun rowHeight(item: TodoItem): Int =
        if (isMultiline(item)) ctx.px(16f + 21f * (1 + (if (item.due != null) 1 else 0) + (if (item.description != null) 1 else 0))) else ctx.px(40f)

    /**
     * HA's due line: "Today" for a date due today, else the relative time ("In 2 days", "Last week"), and whether
     * it's past. A date is due at the end of its day; a date and time at that instant.
     */
    private fun dueText(item: TodoItem): Pair<String, Boolean>? {
        val raw = item.due ?: return null
        val tz = HaLocale.deviceTimeZone()
        val dateOnly = 'T' !in raw
        val dueMs = if (dateOnly) {
            val parts = raw.split('-').mapNotNull { it.toIntOrNull() }
            if (parts.size < 3) return null
            Calendar.getInstance(tz).apply { clear(); set(parts[0], parts[1] - 1, parts[2], 23, 59, 59); set(Calendar.MILLISECOND, 999) }.timeInMillis
        } else {
            (EntityStore.parseTimestamp(raw) * 1000).toLong().takeIf { it > 0 } ?: return null
        }
        val now = ctx.now()
        val today = dateOnly && Calendar.getInstance(tz).run {
            timeInMillis = now
            val d = get(Calendar.YEAR) * 400 + get(Calendar.DAY_OF_YEAR)
            timeInMillis = dueMs
            d == get(Calendar.YEAR) * 400 + get(Calendar.DAY_OF_YEAR)
        }
        val label = if (today) HaText.ui("ui.panel.lovelace.cards.todo-list.today")
        else HaFormat.capitalize(HaFormat.relativeTime(dueMs, now, zone = tz))
        return label to (dueMs < now)
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

        /** How long a rejected item subscription waits before it's asked for again. */
        private const val RESUBSCRIBE_MS = 30_000L
        /** HA's checked checkbox: its brand fill (#009ac7, measured). */
        private const val CHECKED = 0xFF009AC7.toInt()
        private const val WARNING = 0xFFFFA600.toInt()
        private const val CREATE = 1
        private const val DELETE = 2
        private const val MOVE = 8
        private val HEADER_PX = { ctx: VitroCardContext -> (44.2f * ctx.dp).roundToInt() }

        /** Reads `{ items: [{ uid, summary, status }] }`, a `todo/item/subscribe` event. */
        fun parseItems(result: JSONObject?): List<TodoItem> {
            val arr = result?.optJSONArray("items") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val summary = o.optString("summary", "")
                val uid = o.optString("uid", "").ifEmpty { summary }
                fun str(k: String) = if (o.isNull(k)) null else o.optString(k).ifEmpty { null }
                if (uid.isEmpty()) null else TodoItem(uid, summary, o.optString("status") == "completed", due = str("due"), description = str("description"))
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
        renewing { ctx.store.subscribeAny { changed -> if (changed.any { it in watched }) ctx.main.post { render() } } }
    }

    override fun measureCard(widthMeasureSpec: Int, heightMeasureSpec: Int) {
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
        super.measureCard(widthMeasureSpec, heightMeasureSpec)
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
        nameView.text = config.name(null) ?: area?.name ?: areaName(config)
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
            if (pictureUrl != url) return@loadImage
            if (bmp != null) {
                picture.setImageBitmap(bmp)
                picture.visibility = VISIBLE
                headerIcon.visibility = GONE
                header.background = null
            } else {
                // Failed: no older picture stays on screen, the fallback look comes back, and the next render of the
                // same picture tries again.
                picture.setImageDrawable(null)
                picture.visibility = GONE
                headerIcon.visibility = if (HaDisplay.areas[areaId]?.icon != null) VISIBLE else GONE
                header.setBackgroundColor(CardSurface.withAlpha(tileColor, 0.12f))
                pictureUrl = null
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
            config.name(null) ?: config.raw.optString("area", "").ifEmpty { "Area" }
                .replace('_', ' ').replaceFirstChar { it.titlecase(Locale.ROOT) }

        internal fun median(values: List<Double>): Double {
            val s = values.sorted()
            return if (s.size % 2 == 0) (s[s.size / 2] + s[s.size / 2 - 1]) / 2 else s[s.size / 2]
        }

        /** HA's `formatNumberWithUnit` (at most one decimal), with `blankBeforeUnit`. */
        internal fun formatWithUnit(value: Double, unit: String?): String {
            // Intl's halfExpand on the number's exact value: 0.25 → 0.3, 21.45 (21.4499…) → 21.4.
            val n = HaDisplay.formatValue(value, 1)
            return if (unit.isNullOrEmpty()) n else n + HaFormat.blankBeforeUnit(unit) + unit
        }

        private fun strings(arr: JSONArray?): List<String> =
            if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { arr.optString(it).ifEmpty { null } }
    }
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
        // HA's tooltip (computeTooltip) when the element has no title: "Window light: Off", what TalkBack reads.
        val eid = el.entity
        if (el.title == null && eid != null && view !is TextView) {
            observe(eid) { s -> view.contentDescription = s?.let { "${it.displayName}: ${it.formattedState()}" } ?: eid }
        }
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
        // hui-state-icon-element: a bare 40 px state-badge, its 24 px icon in the middle, nothing behind it.
        val halo = FrameLayout(ctx.context).apply {
            addView(icon, LayoutParams(ctx.px(24f), ctx.px(24f), Gravity.CENTER))
        }
        halo.layoutParams = LayoutParams(ctx.px(40f), ctx.px(40f))
        // hui-state-icon-element: more-info on a tap, whatever the domain (it never toggles by default).
        bindActions(halo, el, "more-info")
        val eid = el.entity
        if (eid != null) {
            observe(eid) { s -> icon.setIcon(MdiIcons.resolveIconName(el.icon, s), tintFor(s)) }
        } else {
            icon.setIcon(el.icon ?: "mdi:home", ctx.theme.stateIconColor)
        }
        return halo
    }

    private fun stateLabel(el: PictureElement): View {
        // hui-state-label-element: `div { padding: 8px; white-space: nowrap }` in the card's 14 px / 400 on a 22.4 px
        // line, nothing behind it.
        val label = extLabel(ctx, 14f, ctx.theme.primaryTextColor).apply {
            maxLines = 1
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.px(8f), ctx.px(8f), ctx.px(8f), ctx.px(8f))
            minHeight = (38.4f * ctx.dp).roundToInt()
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
            text = el.title ?: HaText.ui("ui.card.script.run")
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
 * Native HA `humidifier` card (`hui-humidifier-card`): laid out as the thermostat card (a title row, the 262 px dial
 * with `−` / `+` in its opening, the ⋮ for more-info), with HA's humidity control in the dial
 * ([ThermostatDialView.humidifier]). HA draws a bar under it only for `features`, which fall back to HA's frontend.
 */
class HumidifierCardView(ctx: VitroCardContext, config: CardConfig) : ThermostatCardView(ctx, config)

// ---------------------------------------------------------------------------------------------
// logbook
// ---------------------------------------------------------------------------------------------

/**
 * One logbook row (`logbook/event_stream`). [whenSec] is epoch seconds; the `context*` fields are what HA tells its
 * cause from (the user and run ids aren't kept).
 */
data class LogbookEntry(
    val entityId: String?,
    val name: String?,
    val message: String?,
    val state: String?,
    val whenSec: Double,
    val domain: String? = null,
    val source: String? = null,
    val contextEventType: String? = null,
    val contextDomain: String? = null,
    val contextEntityId: String? = null,
    val contextEntityIdName: String? = null,
    val contextName: String? = null,
    val contextState: String? = null,
    val contextMessage: String? = null,
    /** Who made the change (`context_user_id`): HA's cause names the user from its users and persons. */
    val contextUserId: String? = null,
    /** The entry's own icon (`icon`), for the timeline's glyph. */
    val icon: String? = null,
    /** An event entity's `attributes.event_type`: what its row says (`localizeStateMessage`). */
    val eventType: String? = null,
)

/**
 * Native HA `logbook` card, as HA 2026's `hui-logbook-card` and `ha-logbook` draw it (`narrow`, `no-icon`): 6 grid
 * rows, the card's title if it has one, and its rows newest first under day headers ("Today · November 24, 2026"),
 * scrolling inside the card. One entity's rows are inline (`ha-logbook-entry` with no name: a state dot on a rail,
 * the value, what caused it and the time); several entities' rows name the entity. A row opens the entity's
 * More-Info.
 */
class LogbookCardView(ctx: VitroCardContext, private val config: CardConfig) : CardSurface(ctx) {
    private val list = LinearLayout(ctx.context).apply { orientation = LinearLayout.VERTICAL }
    private val ids: List<String> = config.entities().filter { it.isEntity }.map { it.entity }
        .ifEmpty { listOfNotNull(config.entity) }.distinct()
    private val hours = config.raw.optInt("hours_to_show", 24).coerceIn(1, 24 * 30)
    /** HA's `_getAutoNameDetail`: one entity needs no name. */
    private val inline = ids.size <= 1

    /** Re-renders the day headers ("Today", "Yesterday"); the rows themselves come live from the subscription. */
    private val refresh = Runnable { tick() }
    private var generation = 0
    private var unsub: (() -> Unit)? = null
    private var rows: List<LogbookEntry> = emptyList()
    private var startSec = 0.0

    /** `.floating-date`: the day of the first row in view, pinned over the list's top on the card's color. */
    private val floating = extLabel(ctx, 14f, ctx.theme.primaryTextColor, Inter.MEDIUM).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ctx.px(16f), ctx.px(8f), ctx.px(16f), ctx.px(8f))
        setBackgroundColor(ctx.theme.cardBackgroundColor)
        visibility = GONE
    }
    private val scroll = android.widget.ScrollView(ctx.context)

    /** The rendered rows, each with the entry it shows: what the floating header reads its day from. */
    private val rowViews = ArrayList<Pair<View, LogbookEntry>>()

    init {
        val col = LinearLayout(ctx.context).apply { orientation = LinearLayout.VERTICAL }
        config.title?.let { t ->
            // hui-card's header: 24 px, 24 / 16 / 16 around it.
            col.addView(extLabel(ctx, 24f, ctx.theme.primaryTextColor).apply {
                text = t
                setPadding(ctx.px(16f), ctx.px(24f), ctx.px(16f), ctx.px(16f))
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        // `.content { padding: 16px 0 }` (no top padding under a title), the list scrolling in what's left.
        scroll.apply {
            isVerticalScrollBarEnabled = false
            addView(list, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            setOnScrollChangeListener { _, _, _, _, _ -> updateFloating() }
        }
        // The floating header comes first, as in HA's page (TalkBack reads it first), and draws over the list.
        floating.translationZ = 1f
        floating.outlineProvider = null
        val frame = FrameLayout(ctx.context).apply {
            addView(floating, FrameLayout.LayoutParams(MATCH_PARENT, (38.4f * ctx.dp).roundToInt(), Gravity.TOP))
            addView(scroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        col.addView(frame, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply {
            topMargin = if (config.title == null) ctx.px(16f) else 0
            bottomMargin = ctx.px(16f)
        })
        addView(col, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        render(emptyList())
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        fetch()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private fun stop() {
        ctx.main.removeCallbacks(refresh)
        generation++
        unsub?.let { runCatching { it() } }
        unsub = null
    }

    override fun onCardFailed() = stop()

    /**
     * A cached view another one hides stays attached: it drops its subscription, and opens it again when it shows.
     */
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (!isAttachedToWindow) return
        if (isShown) fetch() else stop()
    }

    /**
     * ha-logbook's stream, as HA's card opens it: `logbook/event_stream` from `hours_to_show` ago to a year ahead,
     * for the card's entities. The first messages are the history (in parts), later ones the new rows.
     */
    private fun fetch() {
        stop()
        if (ids.isEmpty() || failure != null) return
        val gen = generation
        val now = ctx.now()
        startSec = (now - hours * 3_600_000L) / 1000.0
        rows = emptyList()
        val msg = streamRequest(ids, hours, now)
        unsub = ctx.subscribeWs(msg, { ev ->
            val incoming = parseEntries(ev.optJSONArray("events"))
            ctx.main.post {
                if (gen != generation) return@post
                rows = (incoming + rows).distinct().filter { it.whenSec > startSec }.sortedByDescending { it.whenSec }.take(MAX_ROWS)
                render(rows)
                ctx.main.removeCallbacks(refresh)
                if (isAttachedToWindow && isShown) ctx.main.postDelayed(refresh, REFRESH_MS)
            }
        }, { _ -> })
    }

    private fun tick() {
        if (!isAttachedToWindow || !isShown || failure != null) return
        render(rows)
        ctx.main.postDelayed(refresh, REFRESH_MS)
    }

    private fun updateFloating() {
        val y = scroll.scrollY
        val first = rowViews.firstOrNull { (v, _) -> v.bottom > y }?.second ?: rows.firstOrNull()
        floating.visibility = if (first != null) VISIBLE else GONE
        if (first != null) floating.text = dateHeader((first.whenSec * 1000).toLong(), ctx.now(), HaLocale.timeZone())
    }

    private fun render(entries: List<LogbookEntry>) {
        list.removeAllViews()
        rowViews.clear()
        updateFloating()
        if (entries.isEmpty()) {
            list.addView(extLabel(ctx, 14f, ctx.theme.secondaryTextColor).apply {
                text = HaText.ui("ui.components.logbook.entries_not_found")
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            return
        }
        // One zone for the days, their words and their dates: the one HA's dates are written in.
        val tz = HaLocale.timeZone()
        val now = ctx.now()
        entries.forEachIndexed { i, e ->
            val firstOfDay = i == 0 || !sameDay(e, entries[i - 1], tz)
            val lastOfDay = i == entries.size - 1 || !sameDay(e, entries[i + 1], tz)
            if (firstOfDay) {
                // `.date`: 14 px / 500 on a 22.4 px line, 8 px margin and 8 px padding above: 38.4 px. The floating
                // copy covers the first one.
                list.addView(extLabel(ctx, 14f, ctx.theme.primaryTextColor, Inter.MEDIUM).apply {
                    text = dateHeader((e.whenSec * 1000).toLong(), now, tz)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(ctx.px(16f), ctx.px(16f), ctx.px(16f), 0)
                }, LinearLayout.LayoutParams(MATCH_PARENT, (38.4f * ctx.dp).roundToInt()))
            }
            val v = row(e, firstOfDay, lastOfDay)
            rowViews += v to e
            list.addView(v, LinearLayout.LayoutParams(MATCH_PARENT, ctx.px(if (inline) 40f else 60f)))
        }
        updateFloating()
    }

    /**
     * One `ha-logbook-entry`: 16 px in, the 28 px node column (its dot on the rail, the rail trimmed at the day's
     * ends), 8 px, then the body: the value (and, listed, the entity's name), the cause's 20 px icon and the time
     * (12 px, at least 6em, right-aligned), and a 24 px chevron. Rows of a day are split by a 1 px line under the body.
     */
    private fun row(e: LogbookEntry, firstOfDay: Boolean, lastOfDay: Boolean): View {
        val unavailable = e.state == "unavailable"
        val color = when {
            unavailable -> Color.TRANSPARENT
            e.entityId != null && e.state != null -> HaTimelineChart.stateColor(ctx, e.entityId, e.state).takeIf { it != Color.TRANSPARENT }
                ?: ctx.theme.secondaryTextColor
            category(e) == "automation" -> ThemeResolver.parseColor(ctx.theme.vars["light-blue-color"]) ?: 0xFF03A9F4.toInt()
            else -> ThemeResolver.parseColor(ctx.theme.vars["teal-color"]) ?: 0xFF009688.toInt()
        }
        val row = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(ctx.px(16f), 0, ctx.px(16f), 0)
            isClickable = true
            // HA opens its logbook detail dialog, which only its frontend has (HaLogbook.openEntryDetail).
            e.entityId?.let { eid -> setOnClickListener { HaLogbook.openEntryDetail(ctx, eid) } }
        }
        row.addView(LogbookNode(ctx.context, color, ctx.theme.dividerColor, ThemeResolver.parseColor(ctx.theme.vars["disabled-color"]) ?: if (ctx.theme.dark) 0xFF464646.toInt() else 0xFFBDBDBD.toInt(), unavailable, firstOfDay, lastOfDay),
            LinearLayout.LayoutParams(ctx.px(28f), MATCH_PARENT).apply { marginEnd = ctx.px(8f) })

        val body = FrameLayout(ctx.context)
        val line = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val value = valueText(e).replaceFirstChar { it.uppercaseChar() }
        val text = if (inline) value else listOfNotNull(entityName(e), value.ifEmpty { null }).joinToString(" ")
        line.addView(extLabel(ctx, 14f, ctx.theme.primaryTextColor).apply {
            this.text = text
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        cause(e)?.let { icon ->
            line.addView(MdiIconView(ctx.context).apply { setIcon(icon, ctx.theme.secondaryTextColor) },
                LinearLayout.LayoutParams(ctx.px(20f), ctx.px(20f)).apply { marginStart = ctx.px(8f) })
        }
        line.addView(extLabel(ctx, 12f, ctx.theme.secondaryTextColor).apply {
            this.text = timeText((e.whenSec * 1000).toLong())
            gravity = Gravity.END
            minWidth = ctx.px(72f)
            maxLines = 1
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = ctx.px(8f) })
        line.addView(MdiIconView(ctx.context).apply { setIcon("mdi:chevron-right", ctx.theme.secondaryTextColor) },
            LinearLayout.LayoutParams(ctx.px(24f), ctx.px(24f)).apply { marginStart = ctx.px(8f) })
        body.addView(line, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        if (!lastOfDay) {
            body.addView(View(ctx.context).apply { setBackgroundColor(ctx.theme.dividerColor) },
                FrameLayout.LayoutParams(MATCH_PARENT, ctx.px(1f).coerceAtLeast(1), Gravity.BOTTOM))
        }
        row.addView(body, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        row.contentDescription = listOfNotNull(entityName(e), value, timeText((e.whenSec * 1000).toLong())).joinToString(", ")
        return row
    }

    private fun entityName(e: LogbookEntry): String? =
        e.name ?: e.entityId?.let { ctx.store[it]?.displayName ?: it }

    /** HA's `_renderValue`: the state as HA shows it for a state change, else the row's message. */
    private fun valueText(e: LogbookEntry): String {
        val state = e.state
        val eid = e.entityId
        if (state != null && eid != null) {
            val live = ctx.store[eid] ?: EntityState(eid, state, emptyMap())
            return live.copy(state = state).plainState()
        }
        return e.message.orEmpty()
    }

    /** HA's `classifyLogbookEntry`. */
    private fun category(e: LogbookEntry): String = when {
        e.entityId != null && e.state != null -> "entity"
        e.domain in TRIGGER_DOMAINS -> "automation"
        else -> "integration"
    }

    /**
     * The cause's icon, from HA's `computeContextCause` (a user's cause needs the user list Vitro doesn't read):
     * a robot for an automation run, a script for a script, a state machine for a state change, a clock for a
     * schedule, HA's logo for its start or stop, else the integration's own icon or a puzzle piece.
     */
    private fun cause(e: LogbookEntry): String? {
        if (e.contextEventType == "automation_triggered" || e.contextEventType == "script_started") {
            val name = e.contextEntityId?.let { ctx.store[it]?.displayName } ?: e.contextName
            if (name != null) return if (e.contextEventType == "script_started") "mdi:script-text" else "mdi:robot"
        }
        if (e.contextEventType == "call_service" && e.contextDomain != null) return domainIcon(e.contextDomain)
        if (e.contextState != null && e.contextEntityId != null) {
            val name = ctx.store[e.contextEntityId]?.displayName ?: e.contextEntityIdName
            if (name != null) return "mdi:state-machine"
        }
        val hasContext = e.contextEventType != null || e.contextState != null || e.contextMessage != null
        if (e.domain in TRIGGER_DOMAINS && !hasContext && e.source != null) {
            val src = e.source
            return when {
                src.startsWith("state of ") || src.startsWith("numeric state of ") -> "mdi:state-machine"
                src.startsWith("time") -> "mdi:clock-outline"
                src.startsWith("Home Assistant") -> "mdi:home-assistant"
                else -> "mdi:puzzle"
            }
        }
        if (e.contextName != null) return e.contextDomain?.let(::domainIcon) ?: "mdi:puzzle"
        return null
    }

    /** `ha-domain-icon`: the domain's own icon. */
    private fun domainIcon(domain: String): String = MdiIcons.resolveIconName(null, EntityState("$domain.cause", "", emptyMap()))

    companion object {
        private const val MAX_ROWS = 200
        private const val REFRESH_MS = 60_000L
        private val TRIGGER_DOMAINS = setOf("automation", "script")

        /** HA's `subscribeLogbook` request: its times as JavaScript's `toISOString()` writes them. */
        fun streamRequest(ids: List<String>, hours: Int, nowMs: Long): JSONObject = JSONObject()
            .put("type", "logbook/event_stream")
            .put("start_time", isoMillis(nowMs - hours * 3_600_000L))
            .put("end_time", isoMillis(nowMs + 86_400_000L * 365))
            .put("entity_ids", JSONArray(ids))

        /** `Date.toISOString()`: UTC with milliseconds. */
        fun isoMillis(ms: Long): String = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

        /** The stream's rows in the order HA sent them (oldest first). */
        fun parseInOrder(arr: JSONArray?): List<LogbookEntry> {
            if (arr == null) return emptyList()
            val out = ArrayList<LogbookEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                fun str(k: String) = if (o.has(k) && !o.isNull(k)) o.optString(k).ifEmpty { null } else null
                out += LogbookEntry(
                    entityId = str("entity_id"), name = str("name"), message = str("message"), state = str("state"),
                    whenSec = EntityStore.parseTimestamp(o.opt("when")), domain = str("domain"), source = str("source"),
                    contextEventType = str("context_event_type"), contextDomain = str("context_domain"),
                    contextEntityId = str("context_entity_id"), contextEntityIdName = str("context_entity_id_name"),
                    contextName = str("context_name"), contextState = str("context_state"), contextMessage = str("context_message"),
                    contextUserId = str("context_user_id"), icon = str("icon"),
                    eventType = o.optJSONObject("attributes")?.let { a -> if (a.has("event_type") && !a.isNull("event_type")) a.opt("event_type")?.toString() else null },
                )
            }
            return out
        }

        /** The stream's rows, newest first. */
        fun parseEntries(arr: JSONArray?): List<LogbookEntry> = parseInOrder(arr).sortedByDescending { it.whenSec }

        /** `formatTimeWithSeconds`: "12:06:51 AM". */
        fun timeText(ms: Long): String = HaFormat.formatTimeWithSeconds(ms)

        /**
         * `_formatDateHeader`: "Today · November 24, 2026", "Yesterday · …", else `formatDate`. The day word and the
         * date both count in [tz] (the zone HA's dates are written in, `HaLocale.timeZone()`), so they name the same
         * day; "Today" is `Intl.RelativeTimeFormat`'s, capitalized.
         */
        fun dateHeader(ms: Long, nowMs: Long, tz: TimeZone): String {
            val full = HaIntl.of(HaLocale.current.language).dateFormat("date")?.format(ms, tz) ?: HaFormat.formatDate(ms)
            val diff = (nowMs + tz.getOffset(nowMs)).floorDiv(86_400_000L) - (ms + tz.getOffset(ms)).floorDiv(86_400_000L)
            if (diff != 0L && diff != 1L) return full
            val word = HaIntl.of(HaLocale.current.language).relativeTime(if (diff == 0L) 0 else -1, "day")
            return "${HaFormat.capitalize(word)} · $full"
        }

        private fun sameDay(a: LogbookEntry, b: LogbookEntry, tz: TimeZone): Boolean {
            fun day(e: LogbookEntry): Long { val ms = (e.whenSec * 1000).toLong(); return (ms + tz.getOffset(ms)).floorDiv(86_400_000L) }
            return day(a) == day(b)
        }
    }
}

/** `.node`: the logbook's rail (2 px, the divider color, 9 px clear of the dot) and its 10 px dot. */
internal class LogbookNode(
    context: Context,
    private val color: Int,
    private val rail: Int,
    private val disabled: Int,
    private val unavailable: Boolean,
    private val trimTop: Boolean,
    private val trimBottom: Boolean,
) : View(context) {
    private val dp = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        paint.style = Paint.Style.FILL
        paint.color = rail
        if (!trimTop) canvas.drawRect(cx - dp, 0f, cx + dp, cy - 9f * dp, paint)
        if (!trimBottom) canvas.drawRect(cx - dp, cy + 9f * dp, cx + dp, height.toFloat(), paint)
        if (unavailable) {
            // `.dot.unavailable`: transparent inside a 2 px `--disabled-color` ring.
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f * dp
            paint.color = disabled
            canvas.drawCircle(cx, cy, 4f * dp, paint)
        } else {
            paint.color = color
            canvas.drawCircle(cx, cy, 5f * dp, paint)
        }
    }
}
