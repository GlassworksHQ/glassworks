/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.cards

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.format.DateFormat
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.model.ActionConfig
import dev.glassworks.engine.vitro.model.CardConfig
import dev.glassworks.engine.vitro.model.EntityRowConfig
import dev.glassworks.engine.vitro.model.VisibilityCondition
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.CardSurface
import dev.glassworks.engine.vitro.ui.MdiIconView
import dev.glassworks.engine.vitro.ui.MdiIcons
import dev.glassworks.engine.vitro.ui.VitroCardContext
import dev.glassworks.engine.vitro.ui.bindHoldAction
import dev.glassworks.ui.Inter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.roundToInt

/** Inter's semibold weight (the variable font takes any weight; core's [Inter] names only up to medium). */
private const val INTER_SEMIBOLD = 600

/**
 * HA 2026's motion tokens (Material Web): the standard ease for state changes, the decelerate ease for things
 * entering and the accelerate ease for things leaving.
 */
internal object HaMotion {
    fun standard() = PathInterpolator(0.4f, 0f, 0.2f, 1f)
    fun decelerate() = PathInterpolator(0f, 0f, 0.2f, 1f)
    fun accelerate() = PathInterpolator(0.4f, 0f, 1f, 1f)

    /** Straight per-channel ARGB blend from [from] (t = 0) to [to] (t = 1). */
    fun lerpColor(from: Int, to: Int, t: Float): Int {
        if (t <= 0f) return from
        if (t >= 1f) return to
        fun ch(shift: Int): Int {
            val a = (from ushr shift) and 0xFF
            val b = (to ushr shift) and 0xFF
            return ((a + (b - a) * t).roundToInt() and 0xFF) shl shift
        }
        return ch(24) or ch(16) or ch(8) or ch(0)
    }

    fun lerp(from: Float, to: Float, t: Float): Float = from + (to - from) * t

    /**
     * Runs [onFrame] with 0 → 1 over [durationMs] and returns the animator, or calls `onFrame(1f)` straight away
     * (and returns null) when [view] isn't on screen, so binds before attach never animate.
     */
    fun run(view: View, durationMs: Long, onFrame: (Float) -> Unit): ValueAnimator? {
        if (!view.isAttachedToWindow) {
            onFrame(1f)
            return null
        }
        return ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = standard()
            addUpdateListener { onFrame(it.animatedValue as Float) }
            start()
        }
    }
}

/**
 * HA's `mwc-ripple` / `ha-ripple`: a Material ripple clipped to [cornerRadiusPx] (999 for pills and circles), set
 * as the view's foreground so it draws over whatever background the view already has. Cards never scale on press.
 */
internal fun applyHaRipple(view: View, cornerRadiusPx: Float, isDark: Boolean) {
    val mask = GradientDrawable().apply {
        cornerRadius = cornerRadiusPx
        setColor(Color.WHITE)
    }
    val tint = ColorStateList.valueOf(if (isDark) 0x22FFFFFF else 0x1A000000)
    view.foreground = RippleDrawable(tint, null, mask)
}

/** HA's `hold_action` for a card: the configured one, else `more-info` when there's an entity to show. */
private fun holdActionFor(raw: JSONObject, entityId: String?): ActionConfig =
    ActionConfig.parse(raw.optJSONObject("hold_action"), if (entityId != null) "more-info" else "none")

private fun textView(ctx: VitroCardContext, sp: Float, color: Int, weight: Int = Inter.REGULAR): TextView =
    TextView(ctx.context).apply {
        val scaled = ctx.scaledSp(sp)
        textSize = scaled
        setTextColor(color)
        typeface = Inter.typeface(ctx.context, weight, scaled)
        includeFontPadding = false
    }

/** True when [s] holds Jinja2 that has to go through HA's `render_template`. */
internal fun isTemplate(s: String?): Boolean = s != null && ("{{" in s || "{%" in s)

private val ENTITY_VARIABLE = Regex("""\bentity\b""")

/**
 * Mushroom templates see the card's `entity` as a variable. HA's `render_template` has no card context, so a
 * `{% set entity = … %}` prefix supplies it when the template uses it.
 */
internal fun withEntityVariable(template: String, entityId: String?): String =
    if (entityId != null && ENTITY_VARIABLE.containsMatchIn(template)) "{% set entity = '$entityId' %}$template" else template

/** Mushroom's color names (`--rgb-<name>`), plus `primary` / `accent` from the theme and any CSS color. */
internal object MushroomColors {
    private val NAMED = mapOf(
        "red" to 0xFFF44336, "pink" to 0xFFE91E63, "purple" to 0xFF926BC7, "deep-purple" to 0xFF6E41AB,
        "indigo" to 0xFF3F51B5, "blue" to 0xFF2196F3, "light-blue" to 0xFF03A9F4, "cyan" to 0xFF00BCD4,
        "teal" to 0xFF009688, "green" to 0xFF4CAF50, "light-green" to 0xFF8BC34A, "lime" to 0xFFCDDC39,
        "yellow" to 0xFFFFEB3B, "amber" to 0xFFFFC107, "orange" to 0xFFFF9800, "deep-orange" to 0xFFFF6F22,
        "brown" to 0xFF795548, "grey" to 0xFF9E9E9E, "gray" to 0xFF9E9E9E, "blue-grey" to 0xFF607D8B,
        "black" to 0xFF000000, "white" to 0xFFFFFFFF, "disabled" to 0xFFBDBDBD,
    ).mapValues { it.value.toInt() }

    /** The color [raw] names, or null for blank / unknown values (the caller's default applies). */
    fun parse(raw: String?, theme: dev.glassworks.engine.vitro.theme.ResolvedTheme): Int? {
        val s = raw?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() && it != "none" } ?: return null
        return when (s) {
            "primary" -> theme.primaryColor
            "accent" -> theme.accentColor
            else -> NAMED[s] ?: MarkdownInline.parseCssColor(s)
        }
    }
}

/**
 * Native HA `tile` card + Tier 2 HACS fast-path for `custom:mushroom-entity-card`,
 * `custom:mushroom-light-card`, `custom:mushroom-fan-card`, `custom:mushroom-cover-card`,
 * `custom:mushroom-person-card`, and `custom:mushroom-template-card`.
 */
class TileCardView(ctx: VitroCardContext, private val config: CardConfig) : CardSurface(ctx) {
    /** Live state binders for the inline features added by [addExtraFeatures]. */
    private val featureBinders = ArrayList<(EntityState?) -> Unit>()
    private val iconBadge = MdiIconView(ctx.context)
    private val iconWrap = LinearLayout(ctx.context)
    private val titleView = textView(ctx, 14f, ctx.theme.primaryTextColor, Inter.MEDIUM)
    private val stateView = textView(ctx, 12f, withAlpha(ctx.theme.primaryTextColor, 0.8f), Inter.REGULAR)

    /** The 20% oval behind the badge icon, kept so its tint can cross-fade instead of being replaced. */
    private val badgeOval = GradientDrawable().apply { shape = GradientDrawable.OVAL }
    private var badgeColor: Int? = null
    private var badgeAnimator: ValueAnimator? = null

    /** The last real `brightness` (1..255) this tile saw, so an optimistic `on` restores it instead of 100%. */
    private var lastBrightness: Int? = null

    /** The Mushroom template card's views, when this tile renders `custom:mushroom-template-card`. */
    internal var mushroom: MushroomTemplateViews? = null
        private set

    init {
        if (config.type == MUSHROOM_TEMPLATE) buildMushroomTemplate() else buildTile()
    }

    private fun buildTile() {
        val pad = ctx.px(10f)
        val vertical = config.raw.optBoolean("vertical", false) || config.raw.optString("layout") == "vertical"
        val row = LinearLayout(ctx.context).apply {
            orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = if (vertical) Gravity.CENTER else Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, pad)
            minimumHeight = ctx.px(56f)
        }

        val badgeSize = ctx.px(36f)
        val iconPad = ctx.px(8f)
        iconWrap.apply {
            gravity = Gravity.CENTER
            setPadding(iconPad, iconPad, iconPad, iconPad)
            addView(iconBadge, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        row.addView(iconWrap, LinearLayout.LayoutParams(badgeSize, badgeSize).apply {
            if (vertical) bottomMargin = ctx.px(8f) else marginEnd = ctx.px(12f)
        })

        val labels = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (vertical) Gravity.CENTER_HORIZONTAL else Gravity.START
            titleView.maxLines = 1
            titleView.ellipsize = TextUtils.TruncateAt.END
            stateView.maxLines = 1
            stateView.ellipsize = TextUtils.TruncateAt.END
            addView(titleView)
            addView(stateView, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ctx.px(1f) })
        }
        row.addView(labels, LinearLayout.LayoutParams(if (vertical) WRAP_CONTENT else 0, WRAP_CONTENT, if (vertical) 0f else 1f))

        val featuresArr = config.raw.optJSONArray("features")
        val featureTypes = if (featuresArr != null) {
            (0 until featuresArr.length()).mapNotNull { featuresArr.optJSONObject(it)?.optString("type", "")?.takeIf(String::isNotEmpty) }
        } else {
            emptyList()
        }
        val rootCol = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.VERTICAL
            addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        val entityId = config.entity
        var brightnessSlider: HaHorizontalSliderView? = null
        var selectBox: TextView? = null
        if ("light-brightness" in featureTypes && entityId != null) {
            brightnessSlider = HaHorizontalSliderView(
                ctx = ctx,
                accentColor = if (ctx.theme.dark) BRIGHTNESS_DARK else BRIGHTNESS_LIGHT,
                onCommit = { frac ->
                    val bri = (frac * 255f).toInt().coerceIn(0, 255)
                    if (bri <= 0) ctx.callService("light", "turn_off", JSONObject().put("entity_id", entityId), null)
                    else ctx.callService("light", "turn_on", JSONObject().put("entity_id", entityId).put("brightness", bri), null)
                },
            )
            rootCol.addView(brightnessSlider, LinearLayout.LayoutParams(MATCH_PARENT, ctx.px(42f)).apply {
                leftMargin = pad; rightMargin = pad; bottomMargin = ctx.px(10f)
            })
        }
        if ("select-options" in featureTypes && entityId != null) {
            // HA 2026's select feature: a filled dropdown box showing the current option; tap for the list.
            val current = textView(ctx, 14f, ctx.theme.primaryTextColor, Inter.REGULAR).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val box = LinearLayout(ctx.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ctx.px(12f), 0, ctx.px(8f), 0)
                background = featureFill(ctx)
                applyHaRipple(this, 10f * ctx.dp, ctx.theme.dark)
                isClickable = true
                addView(current, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(MdiIconView(ctx.context).apply { setIcon("mdi:menu-down", ctx.theme.primaryTextColor) },
                    LinearLayout.LayoutParams(ctx.px(24f), ctx.px(24f)))
                setOnClickListener { v -> showSelectMenu(v, entityId) }
            }
            selectBox = current
            rootCol.addView(box, LinearLayout.LayoutParams(MATCH_PARENT, ctx.px(42f)).apply {
                leftMargin = pad; rightMargin = pad; bottomMargin = ctx.px(10f)
            })
        }
        if ("cover-open-close" in featureTypes && entityId != null) {
            // HA 2026's cover feature: three icon-only rounded squares (open, stop, close).
            val coverRow = LinearLayout(ctx.context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(pad, 0, pad, ctx.px(10f))
            }
            listOf("mdi:arrow-up" to "open_cover", "mdi:stop" to "stop_cover", "mdi:arrow-down" to "close_cover")
                .forEachIndexed { idx, (iconName, service) ->
                    val btn = LinearLayout(ctx.context).apply {
                        gravity = Gravity.CENTER
                        background = featureFill(ctx)
                        applyHaRipple(this, 10f * ctx.dp, ctx.theme.dark)
                        isClickable = true
                        contentDescription = service.substringBefore('_').replaceFirstChar { it.titlecase(Locale.ROOT) }
                        addView(MdiIconView(ctx.context).apply { setIcon(iconName, ctx.theme.primaryTextColor) },
                            LinearLayout.LayoutParams(ctx.px(20f), ctx.px(20f)))
                        setOnClickListener {
                            ctx.callService("cover", service, JSONObject().put("entity_id", entityId), null)
                        }
                    }
                    coverRow.addView(btn, LinearLayout.LayoutParams(0, ctx.px(42f), 1f).apply {
                        if (idx > 0) marginStart = ctx.px(8f)
                    })
                }
            rootCol.addView(coverRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        if (entityId != null) {
            addExtraFeatures(rootCol, featuresArr, featureTypes, entityId, pad)
        }
        addView(rootCol, LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // HA's `hui-tile-card`: the body opens more-info; the icon toggles what can be toggled.
        val tapAction = ActionConfig.parse(config.raw.optJSONObject("tap_action"), "more-info")
        val iconDefault = if (VitroCardContext.isToggleable(entityId)) "toggle" else "more-info"
        val iconTapAction = ActionConfig.parse(config.raw.optJSONObject("icon_tap_action"), iconDefault)
        val holdAction = holdActionFor(config.raw, entityId)

        isClickable = true
        setOnClickListener { ctx.performAction(tapAction, entityId) }
        bindHoldAction(holdAction, entityId)
        applyHaRipple(this, ctx.theme.cardBorderRadiusDp * ctx.dp, ctx.theme.dark)
        iconWrap.isClickable = true
        applyHaRipple(iconWrap, 999f, ctx.theme.dark)
        iconWrap.setOnClickListener { ctx.performAction(iconTapAction, entityId) }
        iconWrap.bindHoldAction(ctx, holdAction, entityId)

        if (entityId != null) {
            observe(entityId) { s ->
                renderState(s)
                brightnessSlider?.let { sb ->
                    sb.setAccentColor(s?.lightTintColor() ?: if (ctx.theme.dark) BRIGHTNESS_DARK else BRIGHTNESS_LIGHT)
                    val on = s?.isActive == true
                    val bri = (s?.attributes?.get("brightness") as? Number)?.toInt()?.takeIf { it in 1..255 }
                    when {
                        !on -> sb.setValue(0f, active = false)
                        bri != null -> {
                            lastBrightness = bri
                            sb.setValue(bri / 255f, active = true)
                        }
                        // An optimistic `on` before HA reports `brightness`: restore the last level, or hold
                        // still until the real value lands, never flash to 100%.
                        else -> sb.setValue(lastBrightness?.let { it / 255f } ?: sb.value, active = true)
                    }
                }
                selectBox?.text = s?.state ?: "…"
                featureBinders.forEach { it(s) }
            }
        } else {
            titleView.text = config.name ?: config.title ?: "Tile"
            stateView.visibility = GONE
            updateBadge(null, active = false)
        }
    }

    /**
     * HA 2026's other inline tile features: `climate-hvac-modes`, `target-temperature`, `fan-speed`,
     * `cover-position`, `light-color-temp`, `media-player-volume-slider`, `toggle`, `media-player-playback`,
     * `light-color-favorites`, `numeric-input` and `vacuum-commands`, in the order the card lists them.
     */
    private fun addExtraFeatures(
        rootCol: LinearLayout,
        featuresArr: JSONArray?,
        featureTypes: List<String>,
        entityId: String,
        pad: Int,
    ) {
        fun lp() = LinearLayout.LayoutParams(MATCH_PARENT, ctx.px(40f)).apply {
            leftMargin = pad; rightMargin = pad; bottomMargin = ctx.px(10f)
        }
        fun data() = JSONObject().put("entity_id", entityId)
        fun featureConfig(type: String): JSONObject? = featuresArr?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.firstOrNull { it.optString("type") == type }
        }
        fun num(s: EntityState?, key: String): Double? = (s?.attributes?.get(key) as? Number)?.toDouble()

        fun segmentBar() = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(ctx.px(3f), ctx.px(3f), ctx.px(3f), ctx.px(3f))
            background = GradientDrawable().apply {
                cornerRadius = 10f * ctx.dp
                setColor(if (ctx.theme.dark) SEGMENT_DARK else SEGMENT_LIGHT)
            }
        }
        /** One icon segment of a bar: `paint(selected, color)` fills it like HA's selected segment. */
        class Segment(val button: LinearLayout, val icon: MdiIconView) {
            fun paint(selected: Boolean, color: Int, textColor: Int) {
                icon.tintColor = if (selected) Color.WHITE else textColor
                button.background = if (selected) GradientDrawable().apply {
                    cornerRadius = 8f * button.resources.displayMetrics.density
                    setColor(color)
                } else null
            }
        }
        fun segment(bar: LinearLayout, iconName: String, label: String, onTap: () -> Unit): Segment {
            val icon = MdiIconView(ctx.context).apply { setIcon(iconName, ctx.theme.primaryTextColor) }
            val btn = LinearLayout(ctx.context).apply {
                gravity = Gravity.CENTER
                isClickable = true
                applyHaRipple(this, 8f * ctx.dp, ctx.theme.dark)
                contentDescription = label
                addView(icon, LinearLayout.LayoutParams(ctx.px(20f), ctx.px(20f)))
                setOnClickListener { onTap() }
            }
            bar.addView(btn, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
            return Segment(btn, icon)
        }
        val domain = entityId.substringBefore('.')

        for (type in featureTypes.distinct()) {
            when (type) {
                "climate-hvac-modes" -> {
                    val configured = featureConfig(type)?.optJSONArray("hvac_modes")
                        ?.let { arr -> (0 until arr.length()).map { arr.optString(it) }.filter(String::isNotEmpty) }
                    val bar = LinearLayout(ctx.context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(ctx.px(3f), ctx.px(3f), ctx.px(3f), ctx.px(3f))
                        background = GradientDrawable().apply {
                            cornerRadius = 10f * ctx.dp
                            setColor(if (ctx.theme.dark) SEGMENT_DARK else SEGMENT_LIGHT)
                        }
                    }
                    var shownModes: List<String> = emptyList()
                    val buttons = ArrayList<Pair<String, Pair<LinearLayout, MdiIconView>>>()
                    rootCol.addView(bar, lp())
                    featureBinders += { s ->
                        val modes = configured?.takeIf { it.isNotEmpty() }
                            ?: (s?.attributes?.get("hvac_modes") as? List<*>)?.mapNotNull { it?.toString() }?.takeIf { it.isNotEmpty() }
                            ?: DEFAULT_HVAC_MODES
                        if (modes != shownModes) {
                            shownModes = modes
                            bar.removeAllViews()
                            buttons.clear()
                            modes.forEach { mode ->
                                val icon = MdiIconView(ctx.context)
                                val btn = LinearLayout(ctx.context).apply {
                                    gravity = Gravity.CENTER
                                    isClickable = true
                                    applyHaRipple(this, 8f * ctx.dp, ctx.theme.dark)
                                    contentDescription = mode.replace('_', ' ').replaceFirstChar { it.titlecase(Locale.ROOT) }
                                    addView(icon, LinearLayout.LayoutParams(ctx.px(20f), ctx.px(20f)))
                                    setOnClickListener {
                                        ctx.callService("climate", "set_hvac_mode", data().put("hvac_mode", mode), null)
                                    }
                                }
                                bar.addView(btn, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
                                buttons += mode to (btn to icon)
                            }
                        }
                        buttons.forEach { (mode, views) ->
                            val (btn, icon) = views
                            val selected = s?.state == mode
                            icon.setIcon(ThermostatCardView.modeIcon(mode), if (selected) Color.WHITE else ctx.theme.primaryTextColor)
                            btn.background = if (selected) GradientDrawable().apply {
                                cornerRadius = 8f * ctx.dp
                                setColor(hvacModeColor(mode))
                            } else null
                        }
                    }
                }
                "target-temperature" -> {
                    val unit = ThermostatCardView.temperatureUnit(ctx)
                    val value = textView(ctx, 14f, ctx.theme.primaryTextColor, Inter.MEDIUM).apply {
                        gravity = Gravity.CENTER
                        fontFeatureSettings = "tnum"
                    }
                    var pending: Double? = null
                    fun step(sign: Int) {
                        val st = ctx.store[entityId]
                        val stepSize = num(st, "target_temp_step")?.takeIf { it > 0 } ?: 0.5
                        val cur = pending ?: num(st, "temperature") ?: return
                        val next = (Math.round((cur + sign * stepSize) / stepSize) * stepSize)
                            .coerceIn(num(st, "min_temp") ?: 7.0, num(st, "max_temp") ?: 35.0)
                        pending = next
                        value.text = formatTemp(next, unit)
                        ctx.callService("climate", "set_temperature", data().put("temperature", next), null)
                    }
                    fun stepButton(iconName: String, sign: Int, label: String) = LinearLayout(ctx.context).apply {
                        gravity = Gravity.CENTER
                        isClickable = true
                        applyHaRipple(this, 10f * ctx.dp, ctx.theme.dark)
                        contentDescription = label
                        addView(MdiIconView(ctx.context).apply { setIcon(iconName, ctx.theme.primaryTextColor) },
                            LinearLayout.LayoutParams(ctx.px(20f), ctx.px(20f)))
                        setOnClickListener { step(sign) }
                    }
                    val stepper = LinearLayout(ctx.context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        background = GradientDrawable().apply {
                            cornerRadius = 10f * ctx.dp
                            setColor(if (ctx.theme.dark) SEGMENT_DARK else SEGMENT_LIGHT)
                        }
                        addView(stepButton("mdi:minus", -1, "Lower target temperature"), LinearLayout.LayoutParams(ctx.px(48f), MATCH_PARENT))
                        addView(value, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                        addView(stepButton("mdi:plus", 1, "Raise target temperature"), LinearLayout.LayoutParams(ctx.px(48f), MATCH_PARENT))
                    }
                    rootCol.addView(stepper, lp())
                    featureBinders += { s ->
                        pending = null
                        value.text = num(s, "temperature")?.let { formatTemp(it, unit) } ?: "–"
                    }
                }
                "fan-speed" -> {
                    val slider = HaHorizontalSliderView(ctx, ctx.theme.activeColor("fan")) { frac ->
                        val pct = (frac * 100f).roundToInt().coerceIn(0, 100)
                        if (pct <= 0) ctx.callService("fan", "turn_off", data(), null)
                        else ctx.callService("fan", "set_percentage", data().put("percentage", pct), null)
                    }
                    rootCol.addView(slider, lp())
                    var lastPct: Double? = null
                    featureBinders += { s ->
                        val on = s?.state == "on"
                        val pct = num(s, "percentage")?.takeIf { it > 0.0 }
                        when {
                            !on -> slider.setValue(0f, active = false)
                            pct != null -> {
                                lastPct = pct
                                slider.setValue(pct.toFloat() / 100f, active = true)
                            }
                            // Optimistic `on` before HA reports `percentage`: no flash to 100%.
                            else -> slider.setValue(lastPct?.let { it.toFloat() / 100f } ?: slider.value, active = true)
                        }
                    }
                }
                "cover-position" -> {
                    val slider = HaHorizontalSliderView(ctx, ctx.theme.activeColor("cover")) { frac ->
                        val pos = (frac * 100f).roundToInt().coerceIn(0, 100)
                        ctx.callService("cover", "set_cover_position", data().put("position", pos), null)
                    }
                    rootCol.addView(slider, lp())
                    featureBinders += { s ->
                        val pos = num(s, "current_position") ?: if (s?.state == "open") 100.0 else 0.0
                        slider.setValue(pos.toFloat() / 100f, active = pos > 0)
                    }
                }
                "light-color-temp" -> {
                    val bar = HaColorTempBarView(ctx) { kelvin ->
                        ctx.callService("light", "turn_on", data().put("color_temp_kelvin", kelvin), null)
                    }
                    rootCol.addView(bar, lp())
                    featureBinders += { s ->
                        val minK = num(s, "min_color_temp_kelvin")?.toInt() ?: HaColorTempBarView.DEFAULT_MIN_K
                        val maxK = num(s, "max_color_temp_kelvin")?.toInt() ?: HaColorTempBarView.DEFAULT_MAX_K
                        bar.bind(minK, maxK, num(s, "color_temp_kelvin")?.toInt(), s?.state == "on")
                    }
                }
                "media-player-volume-slider" -> {
                    val slider = HaHorizontalSliderView(ctx, ctx.theme.primaryColor) { frac ->
                        val vol = (frac * 100f).roundToInt() / 100.0
                        ctx.callService("media_player", "volume_set", data().put("volume_level", vol), null)
                    }
                    rootCol.addView(slider, lp())
                    featureBinders += { s ->
                        val vol = num(s, "volume_level")
                        val live = s != null && s.state != "off" && !s.isUnavailable && vol != null
                        slider.setValue(vol?.toFloat() ?: 0f, active = live)
                    }
                }
                "toggle" -> {
                    // HA's `ha-control-switch` as two halves; the half matching the state fills with its color.
                    val (offLabel, onLabel) = when (domain) {
                        "lock" -> "Lock" to "Unlock"
                        "cover", "valve" -> "Close" to "Open"
                        else -> "Off" to "On"
                    }
                    fun service(on: Boolean): Pair<String, String> = when (domain) {
                        "lock" -> "lock" to if (on) "unlock" else "lock"
                        "cover" -> "cover" to if (on) "open_cover" else "close_cover"
                        "valve" -> "valve" to if (on) "open_valve" else "close_valve"
                        in TOGGLE_FEATURE_DOMAINS -> domain to if (on) "turn_on" else "turn_off"
                        else -> "homeassistant" to if (on) "turn_on" else "turn_off"
                    }
                    val bar = segmentBar()
                    val halves = listOf(false to offLabel, true to onLabel).map { (on, label) ->
                        val labelView = textView(ctx, 13f, ctx.theme.primaryTextColor, Inter.MEDIUM).apply { text = label }
                        val half = LinearLayout(ctx.context).apply {
                            gravity = Gravity.CENTER
                            isClickable = true
                            applyHaRipple(this, 8f * ctx.dp, ctx.theme.dark)
                            contentDescription = label
                            addView(labelView)
                        }
                        bar.addView(half, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
                        Triple(on, half, labelView)
                    }
                    fun paint(on: Boolean) {
                        val onColor = when (domain) {
                            "lock" -> UNLOCKED_RED
                            else -> ctx.store[entityId]?.lightTintColor() ?: ctx.theme.activeColor(domain)
                        }
                        val offColor = if (domain == "lock") LOCKED_GREEN else withAlpha(ctx.theme.primaryTextColor, 0.18f)
                        halves.forEach { (isOn, half, text) ->
                            val selected = isOn == on
                            half.background = if (selected) GradientDrawable().apply {
                                cornerRadius = 8f * ctx.dp
                                setColor(if (isOn) onColor else offColor)
                            } else null
                            half.isSelected = selected
                            text.setTextColor(
                                if (selected && (isOn || domain == "lock")) Color.WHITE else ctx.theme.primaryTextColor,
                            )
                        }
                    }
                    halves.forEach { (on, half, _) ->
                        half.setOnClickListener {
                            paint(on)
                            val (d, svc) = service(on)
                            ctx.callService(d, svc, data(), null)
                        }
                    }
                    rootCol.addView(bar, lp())
                    featureBinders += { s -> paint(s?.isActive == true) }
                }
                "media-player-playback" -> {
                    val bar = segmentBar()
                    segment(bar, "mdi:skip-previous", "Previous") {
                        ctx.callService("media_player", "media_previous_track", data(), null)
                    }
                    val playPause = segment(bar, "mdi:play", "Play") {
                        ctx.callService("media_player", "media_play_pause", data(), null)
                    }
                    segment(bar, "mdi:skip-next", "Next") {
                        ctx.callService("media_player", "media_next_track", data(), null)
                    }
                    segment(bar, "mdi:stop", "Stop") {
                        ctx.callService("media_player", "media_stop", data(), null)
                    }
                    rootCol.addView(bar, lp())
                    featureBinders += { s ->
                        val playing = s?.state == "playing"
                        playPause.icon.setIcon(if (playing) "mdi:pause" else "mdi:play")
                        playPause.button.contentDescription = if (playing) "Pause" else "Play"
                        playPause.paint(playing, ctx.theme.primaryColor, ctx.theme.primaryTextColor)
                    }
                }
                "light-color-favorites" -> {
                    val cfg = featureConfig(type)
                    val configured = (cfg?.optJSONArray("favorites") ?: cfg?.optJSONArray("favorite_colors")
                        ?: cfg?.optJSONArray("color_favorites"))
                        ?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it) } }
                        ?.takeIf { it.isNotEmpty() }
                    val row = LinearLayout(ctx.context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    var shown: List<JSONObject>? = null
                    rootCol.addView(row, lp())
                    featureBinders += { s ->
                        val favs = configured ?: defaultFavorites(s)
                        if (favs.map { it.toString() } != shown?.map { it.toString() }) {
                            shown = favs
                            row.removeAllViews()
                            favs.take(MAX_FAVORITES).forEachIndexed { i, fav ->
                                val swatch = View(ctx.context).apply {
                                    background = GradientDrawable().apply {
                                        shape = GradientDrawable.OVAL
                                        setColor(favoriteColor(fav))
                                        setStroke(ctx.px(1f).coerceAtLeast(1), ctx.theme.dividerColor)
                                    }
                                    isClickable = true
                                    applyHaRipple(this, 999f, ctx.theme.dark)
                                    contentDescription = "Favorite color ${i + 1}"
                                    setOnClickListener {
                                        val d = data()
                                        for (key in FAVORITE_KEYS) if (fav.has(key)) d.put(key, fav.get(key))
                                        ctx.callService("light", "turn_on", d, null)
                                    }
                                }
                                val cell = FrameLayout(ctx.context).apply {
                                    addView(swatch, FrameLayout.LayoutParams(ctx.px(32f), ctx.px(32f), Gravity.CENTER))
                                }
                                row.addView(cell, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
                            }
                        }
                        row.alpha = if (s?.state == "on") 1f else 0.6f
                    }
                }
                "numeric-input" -> {
                    val isCounter = domain == "counter"
                    fun lowOf(st: EntityState?) = num(st, if (isCounter) "minimum" else "min") ?: if (isCounter) null else 0.0
                    fun highOf(st: EntityState?) = num(st, if (isCounter) "maximum" else "max") ?: if (isCounter) null else 100.0
                    fun stepOf(st: EntityState?) = num(st, "step")?.takeIf { it > 0 } ?: 1.0
                    fun clamp(v: Double, st: EntityState?): Double {
                        var out = v
                        lowOf(st)?.let { out = out.coerceAtLeast(it) }
                        highOf(st)?.let { out = out.coerceAtMost(it) }
                        return out
                    }
                    fun format(v: Double, st: EntityState?): String {
                        val step = stepOf(st)
                        val decimals = if (step % 1.0 == 0.0) 0 else step.toBigDecimal().stripTrailingZeros().scale().coerceIn(1, 4)
                        val n = String.format(Locale.US, "%.${decimals}f", v)
                        return st?.unit?.let { "$n $it" } ?: n
                    }
                    fun setValue(v: Double) {
                        ctx.callService(domain, "set_value", data().put("value", if (v % 1.0 == 0.0) v.toLong() else v), null)
                    }
                    if (featureConfig(type)?.optString("style") == "slider" && !isCounter) {
                        val slider = HaHorizontalSliderView(ctx, ctx.theme.primaryColor) { frac ->
                            val st = ctx.store[entityId]
                            val lo = lowOf(st) ?: 0.0
                            val hi = highOf(st) ?: 100.0
                            val step = stepOf(st)
                            setValue(clamp(lo + Math.round(frac * (hi - lo) / step) * step, st))
                        }
                        rootCol.addView(slider, lp())
                        featureBinders += { s ->
                            val v = s?.state?.toDoubleOrNull()
                            val lo = lowOf(s) ?: 0.0
                            val hi = highOf(s) ?: 100.0
                            slider.setValue(if (v != null && hi > lo) ((v - lo) / (hi - lo)).toFloat() else 0f, active = v != null)
                        }
                    } else {
                        val value = textView(ctx, 14f, ctx.theme.primaryTextColor, Inter.MEDIUM).apply {
                            gravity = Gravity.CENTER
                            fontFeatureSettings = "tnum"
                        }
                        var pending: Double? = null
                        fun step(sign: Int) {
                            val st = ctx.store[entityId]
                            if (isCounter) {
                                ctx.callService("counter", if (sign > 0) "increment" else "decrement", data(), null)
                                val cur = pending ?: st?.state?.toDoubleOrNull() ?: return
                                pending = clamp(cur + sign * stepOf(st), st)
                                value.text = format(pending!!, st)
                                return
                            }
                            val cur = pending ?: st?.state?.toDoubleOrNull() ?: return
                            val step = stepOf(st)
                            val next = clamp(Math.round((cur + sign * step) / step) * step, st)
                            pending = next
                            value.text = format(next, st)
                            setValue(next)
                        }
                        fun stepButton(iconName: String, sign: Int, label: String) = LinearLayout(ctx.context).apply {
                            gravity = Gravity.CENTER
                            isClickable = true
                            applyHaRipple(this, 10f * ctx.dp, ctx.theme.dark)
                            contentDescription = label
                            addView(MdiIconView(ctx.context).apply { setIcon(iconName, ctx.theme.primaryTextColor) },
                                LinearLayout.LayoutParams(ctx.px(20f), ctx.px(20f)))
                            setOnClickListener { step(sign) }
                        }
                        val stepper = LinearLayout(ctx.context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            background = GradientDrawable().apply {
                                cornerRadius = 10f * ctx.dp
                                setColor(if (ctx.theme.dark) SEGMENT_DARK else SEGMENT_LIGHT)
                            }
                            addView(stepButton("mdi:minus", -1, "Decrease"), LinearLayout.LayoutParams(ctx.px(48f), MATCH_PARENT))
                            addView(value, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                            addView(stepButton("mdi:plus", 1, "Increase"), LinearLayout.LayoutParams(ctx.px(48f), MATCH_PARENT))
                        }
                        rootCol.addView(stepper, lp())
                        featureBinders += { s ->
                            pending = null
                            value.text = s?.state?.toDoubleOrNull()?.let { format(it, s) } ?: "–"
                        }
                    }
                }
                "vacuum-commands" -> {
                    val commands = featureConfig(type)?.optJSONArray("commands")
                        ?.let { arr -> (0 until arr.length()).map { arr.optString(it) }.filter { it in VACUUM_COMMANDS } }
                        ?.takeIf { it.isNotEmpty() }
                        ?: VACUUM_COMMANDS.keys.toList()
                    val bar = segmentBar()
                    var startPause: Segment? = null
                    commands.forEach { cmd ->
                        val (iconName, label) = VACUUM_COMMANDS.getValue(cmd)
                        val seg = segment(bar, iconOr(iconName, VACUUM_FALLBACK_ICONS[cmd] ?: "mdi:robot-vacuum"), label) {
                            val svc = when (cmd) {
                                "start_pause" -> if (ctx.store[entityId]?.state == "cleaning") "pause" else "start"
                                "return_home" -> "return_to_base"
                                else -> cmd
                            }
                            ctx.callService("vacuum", svc, data(), null)
                        }
                        if (cmd == "start_pause") startPause = seg
                    }
                    rootCol.addView(bar, lp())
                    featureBinders += { s ->
                        val cleaning = s?.state == "cleaning"
                        startPause?.let { seg ->
                            seg.icon.setIcon(if (cleaning) "mdi:pause" else "mdi:play")
                            seg.button.contentDescription = if (cleaning) "Pause" else "Start"
                            seg.paint(cleaning, ctx.theme.activeColor("vacuum"), ctx.theme.primaryTextColor)
                        }
                    }
                }
            }
        }
    }

    /** HA's default favorites when the light has none: color temperatures for white-only lights, else six hues. */
    private fun defaultFavorites(s: EntityState?): List<JSONObject> {
        val modes = (s?.attributes?.get("supported_color_modes") as? List<*>)?.map { it.toString() }.orEmpty()
        val colorModes = setOf("hs", "rgb", "rgbw", "rgbww", "xy")
        val hasColor = modes.isEmpty() || modes.any { it in colorModes }
        if (!hasColor && "color_temp" in modes) {
            val lo = (s?.attributes?.get("min_color_temp_kelvin") as? Number)?.toInt() ?: HaColorTempBarView.DEFAULT_MIN_K
            val hi = (s?.attributes?.get("max_color_temp_kelvin") as? Number)?.toInt() ?: HaColorTempBarView.DEFAULT_MAX_K
            return (0 until 6).map { i -> JSONObject().put("color_temp_kelvin", lo + (hi - lo) * i / 5) }
        }
        return DEFAULT_RGB_FAVORITES.map { (r, g, b) -> JSONObject().put("rgb_color", JSONArray(listOf(r, g, b))) }
    }

    private fun favoriteColor(fav: JSONObject): Int {
        fun ints(key: String) = fav.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.optDouble(it) } }
        ints("rgb_color")?.takeIf { it.size >= 3 }?.let { return dev.glassworks.engine.vitro.data.LightColor.pack(it[0].toInt(), it[1].toInt(), it[2].toInt()) }
        ints("rgbw_color")?.takeIf { it.size >= 3 }?.let { return dev.glassworks.engine.vitro.data.LightColor.pack(it[0].toInt(), it[1].toInt(), it[2].toInt()) }
        ints("rgbww_color")?.takeIf { it.size >= 3 }?.let { return dev.glassworks.engine.vitro.data.LightColor.pack(it[0].toInt(), it[1].toInt(), it[2].toInt()) }
        ints("hs_color")?.takeIf { it.size >= 2 }?.let {
            return dev.glassworks.engine.vitro.data.LightColor.hsvToArgb(it[0].toFloat(), (it[1] / 100.0).toFloat(), 1f)
        }
        if (fav.has("color_temp_kelvin")) return dev.glassworks.engine.vitro.data.LightColor.kelvinToArgb(fav.optInt("color_temp_kelvin"))
        return ctx.theme.stateInactiveColor
    }

    /** [preferred] when [MdiIcons] knows it, else [fallback] (an unknown name would draw the home glyph). */
    private fun iconOr(preferred: String, fallback: String): String {
        val key = preferred.removePrefix("mdi:")
        return if (key != "home" && MdiIcons.pathDataFor(preferred) == MdiIcons.pathDataFor("home")) fallback else preferred
    }

    private fun hvacModeColor(mode: String): Int = when (mode) {
        "heat" -> HVAC_HEAT
        "cool" -> HVAC_COOL
        "off" -> HVAC_OFF
        "dry" -> HVAC_DRY
        "fan_only" -> ctx.theme.activeColor("fan")
        else -> ctx.theme.primaryColor
    }

    private fun formatTemp(t: Double, unit: String): String =
        if (t % 1.0 == 0.0) String.format(Locale.US, "%.0f %s", t, unit) else String.format(Locale.US, "%.1f %s", t, unit)

    /**
     * `custom:mushroom-template-card`: a 40 dp shape avatar (icon tinted by `icon_color`, or a round `picture`)
     * with an optional 16 dp `badge_icon` on its bottom-right, then `primary` / `secondary`. Every field can be a
     * live Jinja2 template; `layout: vertical` stacks the avatar over centered text, and `multiline_secondary`
     * lets the secondary line wrap.
     */
    private fun buildMushroomTemplate() {
        val raw = config.raw
        val vertical = raw.optString("layout") == "vertical"
        val multiline = raw.optBoolean("multiline_secondary", false)
        val pad = ctx.px(12f)
        val avatarSize = ctx.px(40f)
        val primaryText = ctx.theme.primaryTextColor

        val avatarBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(withAlpha(primaryText, 0.05f))
        }
        val icon = MdiIconView(ctx.context)
        val picture = ImageView(ctx.context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = GONE
            outlineProvider = OVAL_OUTLINE
            clipToOutline = true
        }
        val avatar = FrameLayout(ctx.context).apply {
            background = avatarBg
            addView(icon, LayoutParams(ctx.px(22f), ctx.px(22f), Gravity.CENTER))
            addView(picture, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        val badgeIcon = MdiIconView(ctx.context)
        val badgeOvalBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ctx.theme.primaryColor)
        }
        val badge = FrameLayout(ctx.context).apply {
            background = badgeOvalBg
            visibility = GONE
            addView(badgeIcon, LayoutParams(ctx.px(10f), ctx.px(10f), Gravity.CENTER))
        }
        val avatarFrame = FrameLayout(ctx.context).apply {
            clipChildren = false
            clipToPadding = false
            addView(avatar, LayoutParams(avatarSize, avatarSize))
            addView(badge, LayoutParams(ctx.px(16f), ctx.px(16f), Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = -ctx.px(3f)
                bottomMargin = -ctx.px(3f)
            })
        }
        val primary = textView(ctx, 14f, primaryText, INTER_SEMIBOLD).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val secondary = textView(ctx, 12f, ctx.theme.secondaryTextColor, Inter.MEDIUM).apply {
            maxLines = if (multiline) Int.MAX_VALUE else 1
            ellipsize = if (multiline) null else TextUtils.TruncateAt.END
            setLineSpacing(ctx.px(2f).toFloat(), 1f)
        }
        if (vertical) {
            primary.gravity = Gravity.CENTER_HORIZONTAL
            secondary.gravity = Gravity.CENTER_HORIZONTAL
            primary.textAlignment = TEXT_ALIGNMENT_CENTER
            secondary.textAlignment = TEXT_ALIGNMENT_CENTER
        }
        val labels = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (vertical) Gravity.CENTER_HORIZONTAL else Gravity.START
            addView(primary, LinearLayout.LayoutParams(if (vertical) MATCH_PARENT else WRAP_CONTENT, WRAP_CONTENT))
            addView(secondary, LinearLayout.LayoutParams(if (vertical) MATCH_PARENT else WRAP_CONTENT, WRAP_CONTENT).apply {
                topMargin = ctx.px(2f)
            })
        }
        val row = LinearLayout(ctx.context).apply {
            orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = if (vertical) Gravity.CENTER else Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(pad, pad, pad, pad)
            minimumHeight = ctx.px(56f)
            addView(avatarFrame, LinearLayout.LayoutParams(avatarSize, avatarSize).apply {
                if (vertical) bottomMargin = ctx.px(10f) else marginEnd = ctx.px(12f)
            })
            addView(labels, LinearLayout.LayoutParams(if (vertical) MATCH_PARENT else 0, WRAP_CONTENT, if (vertical) 0f else 1f))
        }
        addView(row, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        mushroom = MushroomTemplateViews(row, avatar, icon, picture, badge, badgeIcon, primary, secondary)

        val entityId = config.entity
        val tapAction = ActionConfig.parse(raw.optJSONObject("tap_action"), if (entityId != null) "more-info" else "none")
        isClickable = true
        setOnClickListener { ctx.performAction(tapAction, entityId) }
        bindHoldAction(holdActionFor(raw, entityId), entityId)
        applyHaRipple(this, ctx.theme.cardBorderRadiusDp * ctx.dp, ctx.theme.dark)

        var iconName = ""
        var iconColor: Int? = null
        var pictureUrl = ""
        var badgeIconName = ""
        var badgeColorValue: Int? = null
        fun refreshAvatar() {
            val showPicture = pictureUrl.isNotBlank()
            picture.visibility = if (showPicture) VISIBLE else GONE
            icon.visibility = if (showPicture) GONE else VISIBLE
            avatar.background = if (showPicture) null else avatarBg
            val tint = iconColor ?: primaryText
            avatarBg.setColor(if (iconColor != null) withAlpha(tint, 0.2f) else withAlpha(primaryText, 0.05f))
            icon.setIcon(iconName.trim().ifBlank { "mdi:home" }, tint)
            mushroom?.iconColor = tint
        }
        fun refreshBadge() {
            badge.visibility = if (badgeIconName.isBlank()) GONE else VISIBLE
            val bg = badgeColorValue ?: ctx.theme.primaryColor
            badgeOvalBg.setColor(bg)
            badgeIcon.setIcon(badgeIconName.trim().ifBlank { "mdi:home" }, Color.WHITE)
            mushroom?.badgeColor = bg
        }

        fun bindTpl(key: String, fallback: String, apply: (String) -> Unit) {
            val tpl = raw.optString(key, fallback)
            if (isTemplate(tpl)) {
                track(ctx.subscribeTemplate(withEntityVariable(tpl, entityId)) { rendered -> ctx.main.post { apply(rendered.trim()) } })
            } else {
                apply(tpl)
            }
        }
        bindTpl("primary", config.name.orEmpty()) { v ->
            primary.text = v
            primary.visibility = if (v.isEmpty()) GONE else VISIBLE
        }
        bindTpl("secondary", "") { v ->
            secondary.text = v
            secondary.visibility = if (v.isEmpty()) GONE else VISIBLE
        }
        bindTpl("icon", config.icon ?: "") { v -> iconName = v; refreshAvatar() }
        bindTpl("icon_color", "") { v -> iconColor = MushroomColors.parse(v, ctx.theme); refreshAvatar() }
        bindTpl("picture", "") { v ->
            pictureUrl = v
            refreshAvatar()
            if (v.isNotBlank()) ctx.loadImage(v) { bmp -> if (bmp != null && pictureUrl == v) picture.setImageBitmap(bmp) }
        }
        bindTpl("badge_icon", "") { v -> badgeIconName = v; refreshBadge() }
        bindTpl("badge_color", "") { v -> badgeColorValue = MushroomColors.parse(v, ctx.theme); refreshBadge() }
        refreshAvatar()
        refreshBadge()
        entityId?.let { eid -> observe(eid) { /* keep the entity warm for tap actions */ } }
    }

    private fun renderState(s: EntityState?) {
        titleView.text = config.name ?: s?.friendlyName ?: config.entity.orEmpty()
        val hideState = config.raw.optBoolean("hide_state", false)
        stateView.visibility = if (hideState) GONE else VISIBLE
        stateView.text = s?.formattedState() ?: "…"
        updateBadge(s, active = s?.isActive == true)
    }

    /**
     * HA's `hui-tile-card`: toggleable and stateful domains get a 36 dp badge tinted at 20% (amber when active,
     * grey when inactive); plain `sensor`, `weather`, `text` and `select` tiles draw a bare 24 dp icon in the
     * steel-blue `--state-icon-color`, with no circle.
     */
    private fun updateBadge(s: EntityState?, active: Boolean) {
        val domain = s?.domain ?: config.entity?.substringBefore('.').orEmpty()
        val iconName = MdiIcons.resolveIconName(config.icon, s)
        if (domain in BARE_ICON_DOMAINS) {
            val bare = ctx.px(6f)
            iconWrap.setPadding(bare, bare, bare, bare)
            badgeAnimator?.cancel()
            badgeAnimator = null
            badgeColor = null
            iconWrap.background = null
            iconBadge.setIcon(iconName, ctx.theme.stateIconColor)
            return
        }
        val inset = ctx.px(8f)
        iconWrap.setPadding(inset, inset, inset, inset)
        val color = when {
            domain == "lock" && s?.state == "locked" -> LOCKED_GREEN
            domain == "lock" && (s?.state == "unlocked" || s?.state == "open") -> UNLOCKED_RED
            active -> s?.lightTintColor() ?: ctx.theme.activeColor(domain)
            else -> ctx.theme.stateInactiveColor
        }
        iconBadge.setIcon(iconName, iconBadge.tintColor)
        tintBadge(color)
    }

    /**
     * Tints the badge icon and its 20% oval, cross-fading over 180 ms on screen (`hui-tile-card`'s
     * `transition: color 180ms, background-color 180ms`) and snapping before the card is attached.
     */
    private fun tintBadge(color: Int) {
        iconWrap.background = badgeOval
        val from = badgeColor
        if (from == color && badgeAnimator == null) return
        badgeAnimator?.cancel()
        badgeAnimator = null
        badgeColor = color
        if (from == null || !isAttachedToWindow) {
            iconBadge.tintColor = color
            badgeOval.setColor(withAlpha(color, 0.2f))
            return
        }
        val start = iconBadge.tintColor
        badgeAnimator = HaMotion.run(this, BADGE_FADE_MS) { t ->
            val c = HaMotion.lerpColor(start, color, t)
            iconBadge.tintColor = c
            badgeOval.setColor(withAlpha(c, 0.2f))
        }?.apply {
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (badgeAnimator === animation) badgeAnimator = null
                }
            })
        }
    }

    override fun onDetachedFromWindow() {
        badgeAnimator?.cancel()
        badgeAnimator = null
        badgeColor?.let { c ->
            iconBadge.tintColor = c
            badgeOval.setColor(withAlpha(c, 0.2f))
        }
        super.onDetachedFromWindow()
    }

    /** HA's select dropdown: a popup with every option; picking one calls `select.select_option`. */
    private fun showSelectMenu(anchor: View, entityId: String) {
        val opts = (ctx.store[entityId]?.attributes?.get("options") as? List<*>)?.mapNotNull { it?.toString() }.orEmpty()
        if (opts.isEmpty()) return
        val menu = PopupMenu(ctx.context, anchor)
        opts.forEachIndexed { i, opt -> menu.menu.add(0, i, i, opt) }
        menu.setOnMenuItemClickListener { item ->
            val opt = opts.getOrNull(item.itemId) ?: return@setOnMenuItemClickListener false
            ctx.callService("select", "select_option", JSONObject().put("entity_id", entityId).put("option", opt), null)
            true
        }
        menu.show()
    }

    /** The views of a Mushroom template tile, for live updates and tests. */
    internal class MushroomTemplateViews(
        val row: LinearLayout,
        val avatar: FrameLayout,
        val icon: MdiIconView,
        val picture: ImageView,
        val badge: FrameLayout,
        val badgeIcon: MdiIconView,
        val primary: TextView,
        val secondary: TextView,
    ) {
        var iconColor: Int = 0
        var badgeColor: Int = 0
    }

    companion object {
        const val MUSHROOM_TEMPLATE = "custom:mushroom-template-card"
        private val OVAL_OUTLINE = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        private val BARE_ICON_DOMAINS = setOf("sensor", "weather", "text", "select")
        const val LOCKED_GREEN = 0xFF4CAF50.toInt()
        const val UNLOCKED_RED = 0xFFF44336.toInt()
        private const val BRIGHTNESS_LIGHT = 0xFFFF9800.toInt()
        private const val BRIGHTNESS_DARK = 0xFFFFAA55.toInt()
        private const val FEATURE_FILL_LIGHT = 0x0F000000
        private const val FEATURE_FILL_DARK = 0x16FFFFFF
        private const val SEGMENT_LIGHT = 0xFFF0F0F0.toInt()
        private const val SEGMENT_DARK = 0xFF2A2A2A.toInt()
        private const val HVAC_HEAT = 0xFFFF6F22.toInt()
        private const val HVAC_COOL = 0xFF2196F3.toInt()
        private const val HVAC_OFF = 0xFF757575.toInt()
        private const val HVAC_DRY = 0xFFEFBD07.toInt()
        private val DEFAULT_HVAC_MODES = listOf("off", "heat", "cool")
        private val TOGGLE_FEATURE_DOMAINS = setOf(
            "light", "switch", "fan", "input_boolean", "automation", "siren", "humidifier", "climate",
            "media_player", "remote", "water_heater", "script",
        )
        private const val MAX_FAVORITES = 8
        private val FAVORITE_KEYS = listOf("rgb_color", "rgbw_color", "rgbww_color", "hs_color", "xy_color", "color_temp_kelvin")

        /** HA's six default favorite hues, as `rgb_color`. */
        private val DEFAULT_RGB_FAVORITES = listOf(
            Triple(127, 172, 255), Triple(215, 150, 255), Triple(255, 158, 243),
            Triple(255, 110, 84), Triple(255, 202, 84), Triple(184, 255, 84),
        )

        /** HA's vacuum commands, in its order: icon and label. */
        private val VACUUM_COMMANDS = linkedMapOf(
            "start_pause" to ("mdi:play" to "Start"),
            "stop" to ("mdi:stop" to "Stop"),
            "clean_spot" to ("mdi:target-variant" to "Clean spot"),
            "locate" to ("mdi:map-marker" to "Locate"),
            "return_home" to ("mdi:home-map-marker" to "Return home"),
        )
        private val VACUUM_FALLBACK_ICONS = mapOf("clean_spot" to "mdi:robot-vacuum", "locate" to "mdi:bell", "return_home" to "mdi:home")
        private const val BADGE_FADE_MS = 180L

        /** The 10 dp rounded fill under HA 2026's tile features (dropdowns, cover buttons). */
        fun featureFill(ctx: VitroCardContext) = GradientDrawable().apply {
            cornerRadius = 10f * ctx.dp
            setColor(if (ctx.theme.dark) FEATURE_FILL_DARK else FEATURE_FILL_LIGHT)
        }
    }
}

/**
 * Rounded horizontal slider matching HA 2026's inline `hui-light-brightness-card-feature`:
 * a 42 dp rounded track tinted at 20%, solid filled progress from the left, and a crisp white vertical
 * drag bar inset from the right edge of the fill.
 */
internal class HaHorizontalSliderView(
    private val ctx: VitroCardContext,
    private var accentColor: Int,
    private val onCommit: (Float) -> Unit,
) : View(ctx.context) {
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val clipPath = Path()
    private val bounds = RectF()
    private val handleRect = RectF()
    private var fraction = 0f
    private var active = false
    private var dragging = false

    /** Where the fill is heading: the last value set or dragged to, even while it is still animating there. */
    private var targetFraction = 0f
    private var targetColor = accentColor
    private var fractionAnimator: ValueAnimator? = null
    private var colorAnimator: ValueAnimator? = null

    /** The level (0..1) the slider shows, or is animating to. */
    val value: Float get() = targetFraction

    /** Re-tints the fill, e.g. when a light's RGB color or color temperature changes; cross-fades over 180 ms. */
    fun setAccentColor(color: Int) {
        if (color == targetColor) return
        targetColor = color
        colorAnimator?.cancel()
        val from = accentColor
        colorAnimator = HaMotion.run(this, COLOR_MS) { t ->
            accentColor = HaMotion.lerpColor(from, color, t)
            invalidate()
        }
    }

    /** Moves the fill to [value]: animated over 160 ms on screen, snapped before attach. Ignored mid-drag. */
    fun setValue(value: Float, active: Boolean) {
        if (dragging) return
        val clamped = value.coerceIn(0f, 1f)
        if (clamped == targetFraction && active == this.active) return
        this.active = active
        if (clamped == targetFraction) {
            invalidate()
            return
        }
        targetFraction = clamped
        fractionAnimator?.cancel()
        val from = fraction
        fractionAnimator = HaMotion.run(this, VALUE_MS) { t ->
            fraction = HaMotion.lerp(from, clamped, t)
            invalidate()
        }
    }

    private fun dragTo(x: Float, w: Float) {
        fractionAnimator?.cancel()
        fractionAnimator = null
        fraction = (x / w).coerceIn(0f, 1f)
        targetFraction = fraction
        active = fraction > 0f
        invalidate()
    }

    override fun onDetachedFromWindow() {
        fractionAnimator?.cancel()
        fractionAnimator = null
        colorAnimator?.cancel()
        colorAnimator = null
        fraction = targetFraction
        accentColor = targetColor
        super.onDetachedFromWindow()
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val w = width.toFloat().coerceAtLeast(1f)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                dragTo(event.x, w)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                dragTo(event.x, w)
                return true
            }
            MotionEvent.ACTION_UP -> {
                dragTo(event.x, w)
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                onCommit(fraction)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val r = 10f * ctx.dp
        bounds.set(0f, 0f, w, h)
        clipPath.reset()
        clipPath.addRoundRect(bounds, r, r, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clipPath)
        trackPaint.color = if (active) CardSurface.withAlpha(accentColor, 0.20f) else CardSurface.withAlpha(ctx.theme.primaryTextColor, 0.08f)
        canvas.drawRect(bounds, trackPaint)
        val fillW = (w * fraction).coerceIn(0f, w)
        if (fillW > 0f) {
            fillPaint.color = accentColor
            canvas.drawRect(0f, 0f, fillW, h, fillPaint)
            val barW = 3.5f * ctx.dp
            val barH = (h * 0.48f).coerceAtLeast(10f * ctx.dp)
            val barRight = (fillW - 8f * ctx.dp).coerceAtLeast(barW + 2f * ctx.dp)
            handleRect.set(barRight - barW, (h - barH) / 2f, barRight, (h + barH) / 2f)
            canvas.drawRoundRect(handleRect, barW / 2f, barW / 2f, handlePaint)
        }
        canvas.restore()
    }

    private companion object {
        const val VALUE_MS = 160L
        const val COLOR_MS = 180L
    }
}

/**
 * HA 2026's inline `light-color-temp` tile feature: a 10 dp rounded warm-to-cool Kelvin gradient
 * (#FF9329 → #FFFDF7 → #A6C8FF) with a white vertical handle; releasing calls back with the picked Kelvin.
 */
internal class HaColorTempBarView(
    private val ctx: VitroCardContext,
    private val onCommit: (Int) -> Unit,
) : View(ctx.context) {
    private val gradientPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val handleShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x33000000
    }
    private val bounds = RectF()
    private val handleRect = RectF()
    private var shaderWidth = -1f
    private var minK = DEFAULT_MIN_K
    private var maxK = DEFAULT_MAX_K
    private var fraction = 0.5f
    private var hasValue = false
    private var dragging = false

    fun bind(minKelvin: Int, maxKelvin: Int, kelvin: Int?, on: Boolean) {
        if (dragging) return
        minK = minKelvin
        maxK = maxKelvin.coerceAtLeast(minKelvin + 1)
        hasValue = on && kelvin != null
        if (kelvin != null) fraction = ((kelvin - minK).toFloat() / (maxK - minK)).coerceIn(0f, 1f)
        alpha = if (on) 1f else 0.5f
        invalidate()
    }

    private fun kelvinAt(frac: Float): Int = (minK + frac * (maxK - minK)).roundToInt().coerceIn(minK, maxK)

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val w = width.toFloat().coerceAtLeast(1f)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                hasValue = true
                fraction = (event.x / w).coerceIn(0f, 1f)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                fraction = (event.x / w).coerceIn(0f, 1f)
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                onCommit(kelvinAt(fraction))
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        if (w != shaderWidth) {
            shaderWidth = w
            gradientPaint.shader = android.graphics.LinearGradient(
                0f, 0f, w, 0f,
                intArrayOf(WARM, MID, COOL), floatArrayOf(0f, 0.5f, 1f),
                android.graphics.Shader.TileMode.CLAMP,
            )
        }
        val r = 10f * ctx.dp
        bounds.set(0f, 0f, w, h)
        canvas.drawRoundRect(bounds, r, r, gradientPaint)
        if (!hasValue) return
        val barW = 4f * ctx.dp
        val barH = (h * 0.56f).coerceAtLeast(12f * ctx.dp)
        val inset = 8f * ctx.dp
        val cx = (inset + fraction * (w - 2f * inset)).coerceIn(inset, w - inset)
        handleRect.set(cx - barW / 2f - 1f * ctx.dp, (h - barH) / 2f - 1f * ctx.dp, cx + barW / 2f + 1f * ctx.dp, (h + barH) / 2f + 1f * ctx.dp)
        canvas.drawRoundRect(handleRect, barW, barW, handleShadow)
        handleRect.set(cx - barW / 2f, (h - barH) / 2f, cx + barW / 2f, (h + barH) / 2f)
        canvas.drawRoundRect(handleRect, barW / 2f, barW / 2f, handlePaint)
    }

    companion object {
        const val DEFAULT_MIN_K = 2000
        const val DEFAULT_MAX_K = 6500
        private const val WARM = 0xFFFF9329.toInt()
        private const val MID = 0xFFFFFDF7.toInt()
        private const val COOL = 0xFFA6C8FF.toInt()
    }
}

/**
 * Crisp Canvas toggle matching HA 2026's outlined `ha-switch`: a 46x26 dp pill with a 1.5 dp outline and a
 * 16.4 dp round thumb. On: 18% primary fill, primary outline and thumb. Off: faint grey fill, 40% grey outline,
 * and a mid-grey thumb (#757575 light, #9e9e9e dark).
 */
internal class HaSwitchView(private val ctx: VitroCardContext) : View(ctx.context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ctx.dp * 1.5f
    }
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rect = RectF()
    private var on = false

    /** 0 = off, 1 = on; animated between them as `ha-switch` slides its thumb. */
    private var progress = 0f
    private var animator: ValueAnimator? = null

    val isOn: Boolean get() = on

    /** Slides to [value] over 180 ms on screen; snaps on the first bind before the row is attached. */
    fun setOn(value: Boolean) {
        if (value == on) return
        on = value
        animator?.cancel()
        val from = progress
        val to = if (value) 1f else 0f
        animator = HaMotion.run(this, SLIDE_MS) { t ->
            progress = HaMotion.lerp(from, to, t)
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        progress = if (on) 1f else 0f
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(ctx.px(46f), ctx.px(26f))
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val theme = ctx.theme
        val p = progress
        fill.color = HaMotion.lerpColor(
            CardSurface.withAlpha(theme.secondaryTextColor, 0.08f), CardSurface.withAlpha(theme.primaryColor, 0.18f), p,
        )
        stroke.color = HaMotion.lerpColor(CardSurface.withAlpha(theme.secondaryTextColor, 0.40f), theme.primaryColor, p)
        thumb.color = HaMotion.lerpColor(if (theme.dark) OFF_THUMB_DARK else OFF_THUMB_LIGHT, theme.primaryColor, p)
        val half = stroke.strokeWidth / 2f
        rect.set(half, half, w - half, h - half)
        val radius = rect.height() / 2f
        canvas.drawRoundRect(rect, radius, radius, fill)
        canvas.drawRoundRect(rect, radius, radius, stroke)
        val thumbR = ctx.dp * 8.2f
        val cx = HaMotion.lerp(h / 2f, w - h / 2f, p)
        canvas.drawCircle(cx, h / 2f, thumbR, thumb)
    }

    private companion object {
        const val OFF_THUMB_LIGHT = 0xFF757575.toInt()
        const val OFF_THUMB_DARK = 0xFF9E9E9E.toInt()
        const val SLIDE_MS = 180L
    }
}

/**
 * Native HA `entities` card with live rows and interactive switches, matching `hui-entities-card`: no dividers
 * between rows, but `divider` and `section` special rows, `secondary_info` under the name and inline
 * open / stop / close buttons on cover rows.
 */
class EntitiesCardView(ctx: VitroCardContext, config: CardConfig) : CardSurface(ctx) {
    init {
        val col = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, ctx.px(8f))
        }
        val title = config.title
        val rows = config.entities()
        // HA's header toggle: shown when the card has a title and holds toggleable entities, on while any is on.
        val toggleIds = rows.filter { it.isEntity }.map { it.entity }.filter { it.substringBefore('.') in SWITCH_DOMAINS }.distinct()
        val masterSwitch: HaSwitchView? =
            if (title != null && config.raw.optBoolean("show_header_toggle", true) && toggleIds.isNotEmpty()) HaSwitchView(ctx) else null
        if (title != null) {
            val header = LinearLayout(ctx.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ctx.px(16f), ctx.px(16f), ctx.px(16f), ctx.px(8f))
            }
            header.addView(textView(ctx, 22f, ctx.theme.primaryTextColor, Inter.REGULAR).apply {
                text = title.orEmpty()
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            if (masterSwitch != null) {
                masterSwitch.isClickable = true
                masterSwitch.setOnClickListener {
                    val turnOn = !masterSwitch.isOn
                    masterSwitch.setOn(turnOn)
                    ctx.callService(
                        "homeassistant",
                        if (turnOn) "turn_on" else "turn_off",
                        JSONObject().put("entity_id", JSONArray(toggleIds)),
                        null,
                    )
                }
                header.addView(masterSwitch, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = ctx.px(8f) })
            }
            col.addView(header)
        } else {
            col.setPadding(0, ctx.px(8f), 0, ctx.px(8f))
        }
        fun refreshMaster() {
            masterSwitch?.setOn(toggleIds.any { ctx.store[it]?.isActive == true })
        }

        // `entity-filter` support: when `state_filter` is present, rows whose state isn't listed are hidden.
        val filterArr = config.raw.optJSONArray("state_filter")
        val stateFilter: Set<String>? = filterArr?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                when (val item = arr.opt(i)) {
                    is JSONObject -> item.optString("value", "").ifEmpty { null }
                    null -> null
                    else -> item.toString()
                }
            }.toSet()
        }
        val rowViews = ArrayList<LinearLayout>()
        val rowVisible = ArrayList<Boolean>()
        fun refreshFilter() {
            rowViews.forEachIndexed { i, row -> row.visibility = if (rowVisible[i]) VISIBLE else GONE }
        }

        var prevWasDivider = false
        for (rowCfg in rows) {
            if (rowCfg.rowType == "divider") {
                col.addView(dividerLine(ctx), LinearLayout.LayoutParams(MATCH_PARENT, ctx.px(1f).coerceAtLeast(1)).apply {
                    marginStart = ctx.px(16f); marginEnd = ctx.px(16f)
                    topMargin = ctx.px(8f); bottomMargin = ctx.px(8f)
                })
                prevWasDivider = true
                continue
            }
            if (rowCfg.rowType == "section") {
                if (!prevWasDivider) {
                    col.addView(dividerLine(ctx), LinearLayout.LayoutParams(MATCH_PARENT, ctx.px(1f).coerceAtLeast(1)).apply {
                        marginStart = ctx.px(16f); marginEnd = ctx.px(16f)
                        topMargin = ctx.px(8f)
                    })
                }
                rowCfg.name?.let { label ->
                    col.addView(textView(ctx, 14f, ctx.theme.primaryTextColor, Inter.MEDIUM).apply {
                        text = label
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        setPadding(ctx.px(16f), ctx.px(14f), ctx.px(16f), ctx.px(6f))
                    }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                }
                prevWasDivider = false
                continue
            }
            if (!rowCfg.isEntity) continue
            prevWasDivider = false
            val idx = rowViews.size
            val row = LinearLayout(ctx.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = ctx.px(44f)
                setPadding(ctx.px(16f), 0, ctx.px(16f), 0)
                isClickable = true
                applyHaRipple(this, 4f * ctx.dp, ctx.theme.dark)
            }
            rowViews += row
            rowVisible += (stateFilter == null)
            val iconView = MdiIconView(ctx.context)
            val nameView = textView(ctx, 14f, ctx.theme.primaryTextColor).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val valueView = textView(ctx, 14f, ctx.theme.primaryTextColor, Inter.REGULAR).apply {
                maxLines = 1
                gravity = Gravity.END
            }
            val switchView = HaSwitchView(ctx)
            val isSwitchable = rowCfg.entity.substringBefore('.') in SWITCH_DOMAINS
            val isCover = rowCfg.entity.startsWith("cover.")
            val secondaryView = if (rowCfg.secondaryInfo == "last-changed" || rowCfg.secondaryInfo == "last-updated") {
                textView(ctx, 12f, ctx.theme.secondaryTextColor).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
            } else {
                null
            }

            row.addView(iconView, LinearLayout.LayoutParams(ctx.px(24f), ctx.px(24f)).apply { marginEnd = ctx.px(16f) })
            if (secondaryView != null) {
                val labels = LinearLayout(ctx.context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, ctx.px(4f), 0, ctx.px(4f))
                    addView(nameView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                    addView(secondaryView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ctx.px(2f) })
                }
                row.addView(labels, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = ctx.px(8f) })
            } else {
                row.addView(nameView, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = ctx.px(8f) })
            }
            when {
                isSwitchable -> row.addView(switchView, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
                isCover -> COVER_BUTTONS.forEach { (iconName, service) ->
                    val btn = android.widget.FrameLayout(ctx.context).apply {
                        isClickable = true
                        contentDescription = service.substringBefore('_').replaceFirstChar { it.titlecase(Locale.ROOT) }
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(Color.TRANSPARENT)
                        }
                        applyHaRipple(this, 999f, ctx.theme.dark)
                        addView(MdiIconView(ctx.context).apply { setIcon(iconName, ctx.theme.primaryTextColor) },
                            android.widget.FrameLayout.LayoutParams(ctx.px(20f), ctx.px(20f), Gravity.CENTER))
                        setOnClickListener {
                            ctx.callService("cover", service, JSONObject().put("entity_id", rowCfg.entity), null)
                        }
                    }
                    row.addView(btn, LinearLayout.LayoutParams(ctx.px(36f), ctx.px(36f)))
                }
                else -> row.addView(valueView, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            }

            // HA's entity rows: the row opens more-info; only the switch itself toggles.
            val tap = rowCfg.tapAction ?: ActionConfig("more-info")
            row.setOnClickListener { ctx.performAction(tap, rowCfg.entity) }
            row.bindHoldAction(ctx, rowCfg.holdAction ?: ActionConfig("more-info"), rowCfg.entity)
            if (isSwitchable) {
                switchView.isClickable = true
                switchView.setOnClickListener { ctx.performAction(ActionConfig("toggle"), rowCfg.entity) }
            }

            observe(rowCfg.entity) { s ->
                if (stateFilter != null) {
                    rowVisible[idx] = s != null && s.state in stateFilter
                    refreshFilter()
                }
                val active = s?.isActive == true
                val tint = if (active) s.lightTintColor() ?: ctx.theme.activeColor(s.domain) else ctx.theme.stateIconColor
                iconView.setIcon(MdiIcons.resolveIconName(rowCfg.icon, s), tint)
                nameView.text = rowCfg.name ?: s?.friendlyName ?: rowCfg.entity
                secondaryView?.text = if (rowCfg.secondaryInfo == "last-updated") {
                    s?.copy(lastChanged = s.lastUpdated.takeIf { it > 0.0 } ?: s.lastChanged)?.relativeLastChanged()
                } else {
                    s?.relativeLastChanged()
                }.orEmpty()
                when {
                    isSwitchable -> {
                        switchView.setOn(active)
                        refreshMaster()
                    }
                    isCover -> Unit
                    else -> valueView.text = s?.formattedState() ?: "…"
                }
            }
            col.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        refreshFilter()
        addView(col, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    private companion object {
        val SWITCH_DOMAINS = setOf("light", "switch", "input_boolean", "fan", "automation", "humidifier", "siren")
        val COVER_BUTTONS = listOf("mdi:arrow-up" to "open_cover", "mdi:stop" to "stop_cover", "mdi:arrow-down" to "close_cover")

        fun dividerLine(ctx: VitroCardContext): View = View(ctx.context).apply { setBackgroundColor(ctx.theme.dividerColor) }
    }
}

/** Native HA `glance` card, in `hui-glance-card`'s order: name on top, icon in the middle, state at the bottom. */
class GlanceCardView(ctx: VitroCardContext, config: CardConfig) : CardSurface(ctx) {
    init {
        val pad = ctx.px(14f)
        val root = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        config.title?.let { t ->
            root.addView(textView(ctx, 22f, ctx.theme.primaryTextColor, Inter.REGULAR).apply {
                text = t
                setPadding(0, 0, 0, ctx.px(8f))
            })
        }
        val showName = config.raw.optBoolean("show_name", true)
        val showState = config.raw.optBoolean("show_state", true)
        val entities = config.entities().filter { it.isEntity }
        val cols = config.raw.optInt("columns", minOf(entities.size.coerceAtLeast(1), 5)).coerceIn(1, 6)

        entities.chunked(cols).forEach { chunk ->
            val row = LinearLayout(ctx.context).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = cols.toFloat()
            }
            for (e in chunk) {
                val cell = LinearLayout(ctx.context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(ctx.px(4f), ctx.px(6f), ctx.px(4f), ctx.px(6f))
                    isClickable = true
                    applyHaRipple(this, 8f * ctx.dp, ctx.theme.dark)
                }
                val icon = MdiIconView(ctx.context)
                val name = textView(ctx, 13f, ctx.theme.primaryTextColor).apply {
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER
                }
                val state = textView(ctx, 13f, ctx.theme.primaryTextColor, Inter.REGULAR).apply {
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER
                }
                if (showName) cell.addView(name, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                cell.addView(icon, LinearLayout.LayoutParams(ctx.px(24f), ctx.px(24f)).apply {
                    topMargin = ctx.px(10f); bottomMargin = ctx.px(10f)
                })
                if (showState) cell.addView(state, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                cell.setOnClickListener {
                    ctx.performAction(e.tapAction ?: ActionConfig("more-info"), e.entity)
                }
                cell.bindHoldAction(ctx, e.holdAction ?: ActionConfig("more-info"), e.entity)
                observe(e.entity) { s ->
                    val active = s?.isActive == true
                    val tint = if (active) s.lightTintColor() ?: ctx.theme.activeColor(s.domain) else ctx.theme.stateIconColor
                    icon.setIcon(MdiIcons.resolveIconName(e.icon, s), tint)
                    name.text = e.name ?: s?.friendlyName ?: e.entity
                    state.text = s?.formattedState() ?: "…"
                }
                row.addView(cell, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            }
            root.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        addView(root, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }
}

/** Native HA `button` card. */
class ButtonCardView(ctx: VitroCardContext, config: CardConfig) : CardSurface(ctx) {
    init {
        val col = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(ctx.px(14f), ctx.px(16f), ctx.px(14f), ctx.px(16f))
        }
        val icon = MdiIconView(ctx.context)
        val label = textView(ctx, 14f, ctx.theme.primaryTextColor, Inter.MEDIUM).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        col.addView(icon, LinearLayout.LayoutParams(ctx.px(36f), ctx.px(36f)))
        if (config.raw.optBoolean("show_name", true)) {
            col.addView(label, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ctx.px(8f) })
        }
        addView(col, LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val eid = config.entity
        val tap = ActionConfig.parse(
            config.raw.optJSONObject("tap_action"),
            if (VitroCardContext.isToggleable(eid)) "toggle" else "more-info",
        )
        isClickable = true
        setOnClickListener { ctx.performAction(tap, eid) }
        bindHoldAction(holdActionFor(config.raw, eid), eid)
        applyHaRipple(this, ctx.theme.cardBorderRadiusDp * ctx.dp, ctx.theme.dark)
        if (eid != null) {
            observe(eid) { s ->
                val active = s?.isActive == true
                icon.setIcon(MdiIcons.resolveIconName(config.icon, s), if (active) ctx.theme.activeColor(s.domain) else ctx.theme.secondaryTextColor)
                label.text = config.name ?: s?.friendlyName ?: eid
            }
        } else {
            icon.setIcon(config.icon ?: "mdi:home", ctx.theme.primaryColor)
            label.text = config.name ?: "Button"
        }
    }
}

/** Native HA `heading` card + `custom:mushroom-title-card`. */
class HeadingCardView(private val ctx: VitroCardContext, config: CardConfig) : LinearLayout(ctx.context) {
    private val unsubs = ArrayList<() -> Unit>()

    init {
        orientation = VERTICAL
        setPadding(ctx.px(4f), ctx.px(10f), ctx.px(4f), ctx.px(4f))
        val heading = config.raw.optString("heading", config.raw.optString("title", "")).ifEmpty { config.name.orEmpty() }
        val subtitle = config.raw.optString("subtitle", "")
        val isSubtitle = config.raw.optString("heading_style", "title") == "subtitle"
        val textColor = if (isSubtitle) ctx.theme.secondaryTextColor else ctx.theme.primaryTextColor

        // HA's `hui-heading-card`: [icon] heading ........ [entity badges]
        val row = LinearLayout(ctx.context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        config.icon?.takeIf { it.isNotBlank() }?.let { iconName ->
            row.addView(MdiIconView(ctx.context).apply { setIcon(iconName, textColor) },
                LayoutParams(ctx.px(20f), ctx.px(20f)).apply { marginEnd = ctx.px(8f) })
        }
        val hView = textView(ctx, if (isSubtitle) 14f else 16f, textColor, Inter.MEDIUM).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        if ("{{" in heading) {
            unsubs += ctx.subscribeTemplate(heading) { rendered -> ctx.main.post { hView.text = rendered } }
        } else {
            hView.text = heading
        }
        row.addView(hView, LayoutParams(0, WRAP_CONTENT, 1f))
        parseBadges(config.raw.optJSONArray("badges")).forEach { (eid, icon) -> row.addView(buildBadge(eid, icon)) }
        if (heading.isNotEmpty() || row.childCount > 1) addView(row, LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        if (subtitle.isNotEmpty()) {
            val subView = textView(ctx, 13f, ctx.theme.secondaryTextColor)
            if ("{{" in subtitle) {
                unsubs += ctx.subscribeTemplate(subtitle) { rendered -> ctx.main.post { subView.text = rendered } }
            } else {
                subView.text = subtitle
            }
            addView(subView, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ctx.px(2f) })
        }
    }

    /** One right-side heading badge: a 16 dp icon and the live formatted state; tapping opens more-info. */
    private fun buildBadge(eid: String, explicitIcon: String?): View {
        val icon = MdiIconView(ctx.context)
        val state = textView(ctx, 13f, ctx.theme.secondaryTextColor).apply { maxLines = 1 }
        val item = LinearLayout(ctx.context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = ctx.px(12f) }
            isClickable = true
            setOnClickListener { ctx.performAction(ActionConfig("more-info"), eid) }
            addView(icon, LayoutParams(ctx.px(16f), ctx.px(16f)).apply { marginEnd = ctx.px(4f) })
            addView(state)
        }
        val render = { s: EntityState? ->
            icon.setIcon(MdiIcons.resolveIconName(explicitIcon, s), ctx.theme.secondaryTextColor)
            state.text = s?.formattedState() ?: "…"
        }
        render(ctx.store[eid])
        unsubs += ctx.store.subscribe(eid) { s -> ctx.main.post { render(s) } }
        return item
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        unsubs.forEach { runCatching { it() } }
        unsubs.clear()
    }

    companion object {
        /** A heading's `badges`: `{type: entity, entity, icon}` objects or bare entity ids, as `(entity, icon)`. */
        fun parseBadges(arr: JSONArray?): List<Pair<String, String?>> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i)
                val eid = o?.optString("entity", "") ?: arr.optString(i, "")
                if (eid.isEmpty() || '.' !in eid) null
                else eid to o?.optString("icon", "")?.ifEmpty { null }
            }
        }
    }
}

/**
 * Native HA `clock` card (`hui-clock-card`): a centered 42 sp time (52 sp with `clock_size: large`), with seconds
 * and AM/PM stacked in a 16 sp (20 sp) column beside it. Without `time_format` it follows the device's 12/24 hour setting.
 * A card-mod `font-size: Npx` sets the digits' size instead (18–220 sp, the side column at 26% of it), and
 * `font-weight: 300` (or `200`, `light`) draws them in Inter Light, for big wall-clock layouts.
 */
class ClockCardView(ctx: VitroCardContext, config: CardConfig) : CardSurface(ctx) {
    private val showSeconds = config.raw.optBoolean("show_seconds", false)
    private val format24 = when (config.raw.optString("time_format", "")) {
        "12" -> false
        "24" -> true
        else -> DateFormat.is24HourFormat(ctx.context)
    }
    private val timeFormatter = SimpleDateFormat(if (format24) "HH:mm" else "h:mm", Locale.getDefault())
    private val secondsFormatter = SimpleDateFormat("ss", Locale.getDefault())
    private val ampmFormatter = SimpleDateFormat("a", Locale.getDefault())
    private val showDate = config.raw.optBoolean("show_date", false)
    private val large = config.raw.optString("clock_size", "") == "large"
    private val dateFormatter = SimpleDateFormat("EEE, MMM d", Locale.getDefault())
    private val timeView: TextView
    private val secondsView: TextView
    private val ampmView: TextView
    private val dateView: TextView
    /** `card_mod.style` as plain text: a `font-size: Npx` / `font-weight: 300` there sizes the digits. */
    private val cardModStyle = config.raw.optJSONObject("card_mod")?.opt("style")?.toString().orEmpty()
    private val fontOverrideSp = cardModFontSizeSp(cardModStyle)
    private val lightDigits = cardModLightWeight(cardModStyle)
    private val baseTimeSp = fontOverrideSp ?: if (large) 52f else 42f
    private val baseSideSp = if (fontOverrideSp != null) (fontOverrideSp * 0.26f).coerceIn(12f, 44f) else if (large) 20f else 16f
    private val parsedMod = ThemeResolver.parseCardMod(config.raw.optJSONObject("card_mod"), ctx.theme.vars)
    /** `text-align` from card-mod: [Gravity.START], [Gravity.END] or [Gravity.CENTER_HORIZONTAL]. */
    private val alignGravity = when (parsedMod.textAlign ?: CARD_MOD_TEXT_ALIGN.find(cardModStyle)?.groupValues?.get(1)?.lowercase(Locale.ROOT)) {
        "left", "start" -> Gravity.START
        "right", "end" -> Gravity.END
        else -> Gravity.CENTER_HORIZONTAL
    }
    private val zeroPadding = parsedMod.paddingDp == 0f
    private val haConfig = ctx.haConfig
    private var appliedZone: String? = null
    private var appliedScale = 1f
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            render(Date())
            val interval = if (showSeconds) 1_000L else 15_000L
            ctx.main.postDelayed(this, interval)
        }
    }

    init {
        val col = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = alignGravity or Gravity.CENTER_VERTICAL
            val side = if (zeroPadding) 0 else ctx.px(16f)
            val vertical = if (zeroPadding) 0 else ctx.px(18f)
            setPadding(side, vertical, side, vertical)
        }
        // AM/PM alone (no seconds) sits on the digits' baseline, as "8:52 PM"; a seconds stack stays centered.
        val ampmOnly = !showSeconds && !format24
        val row = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = alignGravity or if (ampmOnly) Gravity.BOTTOM else Gravity.CENTER_VERTICAL
        }
        // Sized to fit a half-width sections card; `clock_size: large` grows it.
        val timeSp = baseTimeSp
        val sideSp = baseSideSp
        timeView = textView(ctx, timeSp, ctx.theme.primaryTextColor, if (lightDigits) Inter.LIGHT else INTER_SEMIBOLD).apply {
            letterSpacing = if (fontOverrideSp != null && fontOverrideSp >= 80f) -0.04f else -0.03f
            fontFeatureSettings = "tnum"
        }
        secondsView = textView(ctx, sideSp, ctx.theme.secondaryTextColor, Inter.MEDIUM).apply { fontFeatureSettings = "tnum" }
        ampmView = textView(ctx, sideSp, ctx.theme.secondaryTextColor, Inter.MEDIUM)
        dateView = textView(ctx, 13f, ctx.theme.secondaryTextColor, Inter.REGULAR)
        row.addView(timeView)
        if (showSeconds || !format24) {
            val side = LinearLayout(ctx.context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = if (ampmOnly) Gravity.BOTTOM else Gravity.CENTER_VERTICAL
                // Lift the suffix off the digits' descender gap so it lines up with their baseline.
                if (ampmOnly) setPadding(0, 0, 0, (ctx.dp * baseTimeSp * 0.14f).roundToInt())
                if (showSeconds) addView(secondsView)
                // Seconds and AM/PM sit tight together, as in HA's stacked clock column.
                if (!format24) addView(ampmView, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                    if (showSeconds) topMargin = -ctx.px(2f)
                })
            }
            row.addView(side, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                marginStart = ctx.px(8f)
                if (ampmOnly) gravity = Gravity.BOTTOM
            })
        }
        col.addView(row, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { gravity = alignGravity })
        if (showDate) col.addView(dateView, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            topMargin = ctx.px(4f)
            gravity = alignGravity
        })
        addView(col, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        render(Date())
    }

    /**
     * Narrow cards (a half-width tile in the S1 Plus's 3-column 1440×720 landscape grid is under 195 dp) shrink the
     * time and the side column together so the widest time, like "18:50 43" or "12:58 PM", never clips.
     */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0) return
        val scale = fitScale(w)
        if (abs(scale - appliedScale) < 0.01f) return
        appliedScale = scale
        // Resizing text from inside a layout pass; apply it on the next frame.
        post {
            timeView.textSize = ctx.scaledSp(baseTimeSp) * scale
            secondsView.textSize = ctx.scaledSp(baseSideSp) * scale
            ampmView.textSize = ctx.scaledSp(baseSideSp) * scale
        }
    }

    private fun spToPx(sp: Float): Float =
        android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, sp, ctx.context.resources.displayMetrics)

    private fun fitScale(widthPx: Int): Float {
        val available = widthPx - ctx.px(32f) - ctx.px(4f)
        val probe = Paint(timeView.paint).apply { textSize = spToPx(ctx.scaledSp(baseTimeSp)) }
        var needed = probe.measureText(if (format24) "88:88" else "12:88")
        if (showSeconds || !format24) {
            probe.textSize = spToPx(ctx.scaledSp(baseSideSp))
            probe.typeface = secondsView.typeface
            val side = maxOf(if (showSeconds) probe.measureText("88") else 0f, if (!format24) probe.measureText("PM") else 0f)
            needed += ctx.px(8f) + side
        }
        if (needed <= 0f || available >= needed) return 1f
        return (available / needed).coerceIn(MIN_CLOCK_SCALE, 1f)
    }

    /**
     * Formats in Home Assistant's `time_zone` (from `get_config`), not the device's: a panel whose OS stays on UTC
     * must still show the house's time. Re-checked each tick, since the config can arrive after the card is built.
     */
    private fun applyZone() {
        val tzId = runCatching { haConfig().optString("time_zone", "") }.getOrDefault("").trim()
        if (tzId == appliedZone) return
        appliedZone = tzId
        val zone = if (tzId.isEmpty()) TimeZone.getDefault() else TimeZone.getTimeZone(tzId)
        listOf(timeFormatter, secondsFormatter, ampmFormatter, dateFormatter).forEach { it.timeZone = zone }
    }

    private fun render(now: Date) {
        applyZone()
        timeView.text = timeFormatter.format(now)
        if (showSeconds) secondsView.text = secondsFormatter.format(now)
        if (!format24) ampmView.text = ampmFormatter.format(now).uppercase(Locale.getDefault())
        if (showDate) dateView.text = dateFormatter.format(now)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        tick.run()
    }

    override fun onDetachedFromWindow() {
        running = false
        ctx.main.removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    /** The digits' base size in sp (before the theme's text scale) and whether they're light, for tests. */
    internal val digitsSp: Float get() = baseTimeSp
    internal val sideSp: Float get() = baseSideSp
    internal val digitsLight: Boolean get() = lightDigits
    internal val horizontalGravity: Int get() = alignGravity
    internal val renderedTime: String get() = timeView.text.toString()
    internal val renderedAmPm: String get() = ampmView.text.toString()

    /** Re-renders [now] (tests). */
    internal fun renderAt(now: Date) = render(now)

    internal companion object {
        /** 42 sp shrinks to no less than 30 sp (16 sp side text to ~11.5 sp). */
        private const val MIN_CLOCK_SCALE = 30f / 42f
        private val CARD_MOD_FONT_SIZE = Regex("""font-size\s*:\s*(\d+(?:\.\d+)?)\s*px""", RegexOption.IGNORE_CASE)
        private val CARD_MOD_TEXT_ALIGN = Regex("""text-align\s*:\s*(left|start|right|end|center)""", RegexOption.IGNORE_CASE)
        private val CARD_MOD_LIGHT = Regex("""font-weight\s*:\s*(100|200|300|light|lighter|thin|extralight)\b""", RegexOption.IGNORE_CASE)

        /** The digits' size from a card-mod `font-size: Npx` (clamped to 18..220 sp), or null without one. */
        fun cardModFontSizeSp(style: String): Float? =
            CARD_MOD_FONT_SIZE.find(style)?.groupValues?.get(1)?.toFloatOrNull()?.coerceIn(18f, 220f)

        /** True when card-mod asks for light digits (`font-weight: 300`, `200`, `light`…). */
        fun cardModLightWeight(style: String): Boolean = CARD_MOD_LIGHT.containsMatchIn(style)
    }
}

/**
 * Native HA `markdown` card with live Jinja2 template support via `render_template`. The content is rendered as
 * real views by [MarkdownRenderer] (headings, tables, lists, quotes, rules, colored text, inline `<ha-icon>`) and
 * rebuilt on every template update. `text_only: true` drops the card background like HA does.
 */
class MarkdownCardView(ctx: VitroCardContext, config: CardConfig) : CardSurface(ctx) {
    /** The rendered blocks, rebuilt on each update. */
    val body = LinearLayout(ctx.context).apply { orientation = LinearLayout.VERTICAL }
    private val renderer = MarkdownRenderer(ctx)

    init {
        if (config.raw.optBoolean("text_only", false)) {
            background = null
            elevation = 0f
        }
        val col = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.px(16f), ctx.px(14f), ctx.px(16f), ctx.px(14f))
        }
        config.title?.let { t ->
            col.addView(textView(ctx, 16f, ctx.theme.primaryTextColor, Inter.MEDIUM).apply {
                text = t
                setPadding(0, 0, 0, ctx.px(8f))
            })
        }
        col.addView(body, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(col, LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val rawContent = config.raw.optString("content", "")
        if ("{{" in rawContent || "{%" in rawContent) {
            renderer.render("…", body)
            track(ctx.subscribeTemplate(rawContent) { rendered ->
                ctx.main.post { renderer.render(rendered, body) }
            })
        } else {
            renderer.render(rawContent, body)
        }
    }
}

/**
 * Native HA `picture`, `picture-entity`, and `picture-glance` card.
 * With `camera_view: live` (or a `camera.*` entity) it plays HA's MJPEG stream while attached, falling back to a
 * snapshot every [LiveCameraFeed.POLL_MS] when the stream isn't available, and shows a green "LIVE" badge.
 * A `picture-glance` with `entities` gets HA's row of tappable entity icons on the right of its caption bar.
 */
class PictureCardView(ctx: VitroCardContext, config: CardConfig) : CardSurface(ctx) {
    private val img = ImageView(ctx.context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val caption = textView(ctx, 13f, Color.WHITE, Inter.MEDIUM).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val liveEntityId: String? = (config.entity ?: config.raw.optString("camera_image", "").ifEmpty { null })
        ?.takeIf { config.raw.optString("camera_view") == "live" || it.startsWith("camera.") }
    private val isLive = liveEntityId != null
    private var latestPicture: String? = null
    private val feed: LiveCameraFeed? = liveEntityId?.let { id ->
        LiveCameraFeed(ctx, id, { latestPicture }) { bmp -> img.setImageBitmap(bmp) }
    }

    /** Width / height, from `aspect_ratio` (`16:9`, `4:3`, `50%` = height as % of width, or a decimal). */
    private val aspect: Float = parseAspectRatio(config.raw.opt("aspect_ratio")?.toString())

    init {
        addView(img, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val showName = config.raw.optBoolean("show_name", true)
        val showState = config.raw.optBoolean("show_state", true)
        val glanceEntities = if (config.type == "picture-glance") config.entities().filter { it.isEntity } else emptyList()
        if (config.type != "picture" && (showName || showState || glanceEntities.isNotEmpty())) {
            val bar = LinearLayout(ctx.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(0x88000000.toInt())
                setPadding(ctx.px(12f), ctx.px(8f), ctx.px(8f), ctx.px(8f))
                addView(caption, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            }
            if (glanceEntities.isNotEmpty()) {
                bar.setPadding(ctx.px(12f), ctx.px(2f), ctx.px(4f), ctx.px(2f))
                bar.addView(buildGlanceIcons(glanceEntities), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            }
            addView(bar, LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        }
        if (isLive && showState) addView(buildLiveBadge(), LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            topMargin = ctx.px(8f); marginEnd = ctx.px(8f)
        })

        val staticImage = config.raw.optString("image", "").ifEmpty { null }
        if (staticImage != null) {
            ctx.loadImage(staticImage) { bmp -> if (bmp != null) img.setImageBitmap(bmp) }
        }
        // HA's picture-glance labels its bar with `title`; the other picture cards use `name`.
        val label = if (config.type == "picture-glance") config.title ?: config.name else config.name
        if (label != null) caption.text = label
        val eid = config.entity ?: config.raw.optString("camera_image", "").ifEmpty { null }
        if (eid != null) {
            isClickable = true
            setOnClickListener { ctx.performAction(ActionConfig.parse(config.raw.optJSONObject("tap_action")), eid) }
            bindHoldAction(holdActionFor(config.raw, eid), eid)
            observe(eid) { s ->
                val parts = buildList {
                    if (showName) add(label ?: s?.friendlyName ?: eid)
                    if (showState && s != null && glanceEntities.isEmpty()) add(s.formattedState())
                }
                caption.text = parts.joinToString(" · ")
                if (staticImage == null) {
                    latestPicture = s?.entityPicture
                    // Live cards are refreshed by the feed; others load once per state change.
                    if (!isLive) latestPicture?.let { pic ->
                        ctx.loadImage(pic) { bmp -> if (bmp != null) img.setImageBitmap(bmp) }
                    }
                }
            }
        }
    }

    /** HA picture-glance's entity icons: 24 dp icons in 36 dp touch targets, 4 dp apart, each with its own actions. */
    private fun buildGlanceIcons(entries: List<EntityRowConfig>): LinearLayout = LinearLayout(ctx.context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL or Gravity.END
        entries.forEachIndexed { index, entry ->
            val icon = MdiIconView(ctx.context)
            val target = FrameLayout(ctx.context).apply {
                isClickable = true
                isFocusable = true
                applyHaRipple(this, 999f, true)
                addView(icon, LayoutParams(ctx.px(24f), ctx.px(24f), Gravity.CENTER))
                setOnClickListener { ctx.performAction(glanceTapAction(entry), entry.entity) }
                bindHoldAction(ctx, entry.holdAction ?: ActionConfig("more-info"), entry.entity)
            }
            addView(target, LinearLayout.LayoutParams(ctx.px(36f), ctx.px(36f)).apply {
                if (index > 0) marginStart = ctx.px(4f)
            })
            observe(entry.entity) { s ->
                val name = s?.friendlyName ?: entry.entity
                target.contentDescription = entry.name ?: name
                val tint = if (s != null && s.isActive) ctx.theme.activeColor(s.domain) else GLANCE_INACTIVE
                icon.setIcon(MdiIcons.resolveIconName(entry.icon, s), tint)
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = MeasureSpec.getMode(widthMeasureSpec)
        val width = MeasureSpec.getSize(widthMeasureSpec)
        if (mode == MeasureSpec.UNSPECIFIED || width <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val height = (width / aspect).toInt().coerceAtLeast(1)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
    }

    private fun buildLiveBadge(): LinearLayout = LinearLayout(ctx.context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ctx.px(7f), ctx.px(3f), ctx.px(8f), ctx.px(3f))
        background = GradientDrawable().apply {
            cornerRadius = 999f
            setColor(0x88000000.toInt())
        }
        val dot = View(ctx.context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF4CAF50.toInt())
            }
        }
        addView(dot, LinearLayout.LayoutParams(ctx.px(6f), ctx.px(6f)).apply { marginEnd = ctx.px(5f) })
        addView(textView(ctx, 10f, Color.WHITE, Inter.MEDIUM).apply {
            text = "LIVE"
            letterSpacing = 0.08f
        })
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        feed?.start()
    }

    override fun onDetachedFromWindow() {
        feed?.stop()
        super.onDetachedFromWindow()
    }

    internal companion object {
        private const val GLANCE_INACTIVE = 0xCCFFFFFF.toInt()
        private val GLANCE_TOGGLE_DOMAINS = setOf("light", "switch", "fan", "cover", "lock", "input_boolean", "media_player")

        /** picture-glance's per-icon `tap_action`: the configured one, else `toggle` for switchable domains. */
        fun glanceTapAction(entry: EntityRowConfig): ActionConfig = entry.tapAction
            ?: ActionConfig(if (entry.entity.substringBefore('.') in GLANCE_TOGGLE_DOMAINS) "toggle" else "more-info")

        const val DEFAULT_ASPECT = 16f / 9f

        fun parseAspectRatio(raw: String?): Float {
            val s = raw?.trim()?.lowercase(Locale.ROOT).orEmpty()
            if (s.isEmpty()) return DEFAULT_ASPECT
            val ratio = when {
                s.endsWith("%") -> s.dropLast(1).trim().toFloatOrNull()?.let { if (it > 0f) 100f / it else null }
                ':' in s || 'x' in s -> {
                    val parts = s.split(':', 'x')
                    val w = parts.getOrNull(0)?.trim()?.toFloatOrNull()
                    val h = parts.getOrNull(1)?.trim()?.toFloatOrNull()
                    if (w != null && h != null && h > 0f) w / h else null
                }
                else -> s.toFloatOrNull()
            }
            return ratio?.takeIf { it.isFinite() && it > 0f } ?: DEFAULT_ASPECT
        }
    }
}

/**
 * Native Tier 2 fast-path for `custom:mushroom-chips-card`: entity, template, weather, light, action, menu, back
 * and spacer chips. Template chips render `content`, `icon` and `icon_color` live through `render_template`;
 * `conditional` chips and any chip's `visibility` show and hide with the entities they reference.
 */
class MushroomChipsCardView(private val ctx: VitroCardContext, config: CardConfig) : LinearLayout(ctx.context) {
    private val subscriptions = ArrayList<() -> Unit>()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL or when (config.raw.optString("alignment")) {
            "center" -> Gravity.CENTER_HORIZONTAL
            "end" -> Gravity.END
            else -> Gravity.START
        }
        setPadding(0, ctx.px(4f), 0, ctx.px(4f))
        val chips = config.raw.optJSONArray("chips")
        if (chips != null) {
            for (i in 0 until chips.length()) chips.optJSONObject(i)?.let(::addChip)
        }
    }

    private fun addChip(c: JSONObject) {
        val conditional = c.optString("type") == "conditional"
        val inner = if (conditional) c.optJSONObject("chip") ?: return else c
        val conditions = buildList {
            if (conditional) addAll(VisibilityCondition.parseList(c.optJSONArray("conditions")))
            addAll(VisibilityCondition.parseList(c.optJSONArray("visibility")))
            if (inner !== c) addAll(VisibilityCondition.parseList(inner.optJSONArray("visibility")))
        }
        val view = if (inner.optString("type") == "spacer") {
            View(ctx.context).also { addView(it, LayoutParams(0, 1, 1f)) }
        } else {
            buildChip(inner).also {
                addView(it, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = ctx.px(8f) })
            }
        }
        if (conditions.isEmpty()) return
        val widthDp = ctx.context.resources.configuration.screenWidthDp.takeIf { it > 0 } ?: 1024
        fun refresh() {
            val uid = ctx.haUser().optString("id", "").ifEmpty { null }
            val show = conditions.all { it.evaluate({ id -> ctx.store[id] }, widthDp, userId = uid) }
            view.visibility = if (show) VISIBLE else GONE
        }
        refresh()
        val ids = LinkedHashSet<String>()
        conditions.forEach { it.referencedEntities(ids) }
        ids.forEach { id -> subscriptions += ctx.store.subscribe(id) { ctx.main.post { refresh() } } }
    }

    private fun buildChip(c: JSONObject): LinearLayout {
        val type = c.optString("type", "entity")
        val chip = LinearLayout(ctx.context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 999f
                setColor(ctx.theme.cardBackgroundColor)
                setStroke(ctx.px(1f).coerceAtLeast(1), ctx.theme.cardBorderColor)
            }
            minimumHeight = ctx.px(36f)
            setPadding(ctx.px(10f), ctx.px(8f), ctx.px(12f), ctx.px(8f))
            isClickable = true
            applyHaRipple(this, 999f, ctx.theme.dark)
        }
        val icon = MdiIconView(ctx.context)
        val label = textView(ctx, 12f, ctx.theme.primaryTextColor, Inter.MEDIUM).apply { maxLines = 1 }
        chip.addView(icon, LayoutParams(ctx.px(18f), ctx.px(18f)))
        chip.addView(label, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = ctx.px(6f) })
        fun setLabel(text: String) {
            label.text = text
            label.visibility = if (text.isEmpty()) GONE else VISIBLE
            chip.setPadding(ctx.px(10f), ctx.px(8f), ctx.px(if (text.isEmpty()) 10f else 12f), ctx.px(8f))
        }

        val eid = c.optString("entity", "").ifEmpty { null }
        bindChipCardMod(c, eid, chip, label)
        val defaultTap = when {
            type == "back" || type == "menu" || type == "action" -> "none"
            eid != null -> "more-info"
            else -> "none"
        }
        val tap = ActionConfig.parse(c.optJSONObject("tap_action"), defaultTap)
        chip.setOnClickListener { ctx.performAction(tap, eid) }
        chip.bindHoldAction(ctx, ActionConfig.parse(c.optJSONObject("hold_action"), "none"), eid)
        val staticColor = MushroomColors.parse(c.optString("icon_color", "").takeUnless(::isTemplate), ctx.theme)

        when (type) {
            "template" -> {
                var iconName = "mdi:home"
                var iconColor: Int? = null
                fun paintIcon() = icon.setIcon(iconName.ifBlank { "mdi:home" }, iconColor ?: ctx.theme.primaryTextColor)
                bindChipField(c, "content", eid) { setLabel(it) }
                bindChipField(c, "icon", eid) { iconName = it; icon.visibility = if (it.isBlank()) GONE else VISIBLE; paintIcon() }
                bindChipField(c, "icon_color", eid) { iconColor = MushroomColors.parse(it, ctx.theme); paintIcon() }
                if (c.optString("icon").isBlank()) icon.visibility = GONE
                paintIcon()
            }
            "back", "menu", "action" -> {
                val fallback = when (type) {
                    "back" -> "mdi:arrow-left"
                    "menu" -> "mdi:menu"
                    else -> "mdi:flash"
                }
                icon.setIcon(c.optString("icon", "").ifEmpty { fallback }, staticColor ?: ctx.theme.primaryTextColor)
                setLabel("")
            }
            else -> if (eid != null) {
                val render: (EntityState?) -> Unit = { s ->
                    val active = s?.isActive == true
                    if (type == "weather") {
                        icon.setIcon(MdiIcons.weatherIcon(s?.state.orEmpty()), staticColor ?: ctx.theme.stateIconColor)
                        val temp = s?.attributes?.get("temperature")?.toString()
                        val unit = s?.attributes?.get("temperature_unit")?.toString().orEmpty()
                        val parts = buildList {
                            if (c.optBoolean("show_temperature", true) && temp != null) add("$temp $unit".trim())
                            if (c.optBoolean("show_conditions", false) && s != null) add(s.state.replace('-', ' '))
                        }
                        setLabel(parts.joinToString(" · "))
                    } else {
                        val tint = staticColor ?: when {
                            !active -> ctx.theme.secondaryTextColor
                            else -> s.lightTintColor() ?: ctx.theme.activeColor(s.domain)
                        }
                        icon.setIcon(MdiIcons.resolveIconName(c.optString("icon", "").ifEmpty { null }, s), tint)
                        setLabel(
                            when (c.optString("content_info", "state")) {
                                "name" -> c.optString("name", "").ifEmpty { s?.friendlyName ?: eid }
                                "last-changed", "last-updated" -> s?.relativeLastChanged().orEmpty()
                                "none" -> ""
                                else -> s?.formattedState() ?: "…"
                            },
                        )
                    }
                }
                render(ctx.store[eid])
                subscriptions += ctx.store.subscribe(eid) { s -> ctx.main.post { render(s) } }
            } else {
                icon.setIcon(c.optString("icon", "mdi:home"), staticColor ?: ctx.theme.primaryColor)
                setLabel(c.optString("content", "").takeUnless(::isTemplate).orEmpty())
            }
        }
        return chip
    }

    /**
     * A chip's own `card_mod.style` restyles its pill (fill or gradient, border, radius) and its label's `color`.
     * A Jinja style is rendered by HA and re-applied each time it changes.
     */
    private fun bindChipCardMod(c: JSONObject, entityId: String?, chip: View, label: TextView) {
        val raw = ThemeResolver.cardModStyleString(c.optJSONObject("card_mod")) ?: return
        if (isTemplate(raw)) {
            subscriptions += ctx.subscribeTemplate(withEntityVariable(raw, entityId)) { rendered ->
                ctx.main.post { applyChipStyle(rendered, chip, label) }
            }
        } else {
            applyChipStyle(raw, chip, label)
        }
    }

    private fun applyChipStyle(css: String, chip: View, label: TextView) {
        val mod = ThemeResolver.parseCardMod(css, ctx.theme.vars)
        val gradient = mod.backgroundGradient
        val pill = chip.background as? GradientDrawable
        if (pill != null) {
            if (gradient != null) {
                pill.orientation = CardSurface.orientationFor(gradient.angleDeg)
                pill.colors = gradient.colors
            } else {
                mod.backgroundColor?.let { pill.setColor(it) }
            }
            if (mod.borderWidthDp != null || mod.borderColor != null) {
                val widthPx = mod.borderWidthDp?.let { (it * ctx.dp).roundToInt() } ?: ctx.px(1f).coerceAtLeast(1)
                pill.setStroke(widthPx, mod.borderColor ?: ctx.theme.cardBorderColor)
            }
            mod.borderRadiusDp?.let { pill.cornerRadius = it * ctx.dp }
        }
        mod.textColor?.let { label.setTextColor(it) }
    }

    /** Applies a chip field now when static, or every time HA re-renders it when it is a template. */
    private fun bindChipField(c: JSONObject, key: String, entityId: String?, apply: (String) -> Unit) {
        val raw = c.optString(key, "")
        if (isTemplate(raw)) {
            apply("")
            subscriptions += ctx.subscribeTemplate(withEntityVariable(raw, entityId)) { rendered ->
                ctx.main.post { apply(rendered.trim()) }
            }
        } else {
            apply(raw)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        subscriptions.forEach { runCatching { it() } }
        subscriptions.clear()
    }
}

/** Native Tier 2 fast-path for `custom:bubble-card` (button & separator). */
class BubbleCardView(ctx: VitroCardContext, config: CardConfig) : CardSurface(ctx) {
    init {
        val cardType = config.raw.optString("card_type", "button")
        if (cardType == "separator") {
            background = null
            val row = LinearLayout(ctx.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ctx.px(4f), ctx.px(10f), ctx.px(4f), ctx.px(6f))
            }
            val icon = MdiIconView(ctx.context).apply { setIcon(config.icon ?: "mdi:home", ctx.theme.secondaryTextColor) }
            val title = textView(ctx, 14f, ctx.theme.primaryTextColor, Inter.MEDIUM).apply { text = config.name.orEmpty() }
            val line = View(ctx.context).apply { setBackgroundColor(ctx.theme.dividerColor) }
            row.addView(icon, LinearLayout.LayoutParams(ctx.px(18f), ctx.px(18f)).apply { marginEnd = ctx.px(8f) })
            row.addView(title, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = ctx.px(12f) })
            row.addView(line, LinearLayout.LayoutParams(0, ctx.px(2f), 1f))
            addView(row, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        } else {
            // Pill-shaped Bubble button card
            background = GradientDrawable().apply {
                cornerRadius = 999f
                setColor(ctx.theme.cardBackgroundColor)
                setStroke(ctx.px(1f).coerceAtLeast(1), ctx.theme.cardBorderColor)
            }
            val row = LinearLayout(ctx.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ctx.px(10f), ctx.px(10f), ctx.px(16f), ctx.px(10f))
            }
            val icon = MdiIconView(ctx.context)
            val circle = LinearLayout(ctx.context).apply {
                gravity = Gravity.CENTER
                setPadding(ctx.px(8f), ctx.px(8f), ctx.px(8f), ctx.px(8f))
                addView(icon, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            }
            val name = textView(ctx, 14f, ctx.theme.primaryTextColor, Inter.MEDIUM)
            val sub = textView(ctx, 12f, ctx.theme.secondaryTextColor)
            val textCol = LinearLayout(ctx.context).apply {
                orientation = LinearLayout.VERTICAL
                addView(name); addView(sub)
            }
            row.addView(circle, LinearLayout.LayoutParams(ctx.px(38f), ctx.px(38f)).apply { marginEnd = ctx.px(12f) })
            row.addView(textCol, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            bubbleSubButtons(ctx, config) { id, onState -> observe(id, onState) }?.let { subs ->
                row.setPadding(ctx.px(10f), ctx.px(10f), ctx.px(10f), ctx.px(10f))
                row.addView(subs, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = ctx.px(8f) })
            }
            addView(row, LayoutParams(MATCH_PARENT, WRAP_CONTENT))

            val eid = config.entity
            val tap = ActionConfig.parse(config.raw.optJSONObject("tap_action"), "toggle")
            isClickable = true
            setOnClickListener { ctx.performAction(tap, eid) }
            applyHaRipple(this, 999f, ctx.theme.dark)
            if (eid != null) {
                observe(eid) { s ->
                    val active = s?.isActive == true
                    val tint = if (active) ctx.theme.activeColor(s.domain) else ctx.theme.secondaryTextColor
                    icon.setIcon(MdiIcons.resolveIconName(config.icon, s), tint)
                    circle.background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(withAlpha(tint, if (active) 0.24f else 0.12f))
                    }
                    name.text = config.name ?: s?.friendlyName ?: eid
                    sub.text = s?.formattedState() ?: "…"
                }
            } else {
                icon.setIcon(config.icon ?: "mdi:home", ctx.theme.primaryColor)
                name.text = config.name ?: "Bubble"
                sub.visibility = GONE
            }
        }
    }
}

/**
 * Native HA 2026 `media-control` card (`hui-media-control-card`): the album art fills the card under a dark
 * vertical scrim, with a header (player icon, name, power), the track and artist beside previous / play-pause /
 * next, a volume row with mute, and a 3 dp playback progress line along the bottom edge. Without artwork a
 * playing player gets a primary-tinted surface instead.
 */
class MediaControlCardView(ctx: VitroCardContext, config: CardConfig) : CardSurface(ctx) {
    private val artwork = ImageView(ctx.context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        visibility = GONE
    }
    private val scrim = View(ctx.context).apply {
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(SCRIM_TOP, SCRIM_BOTTOM))
        visibility = GONE
    }
    private val tint = View(ctx.context).apply { visibility = GONE }
    private val headerIcon = MdiIconView(ctx.context)
    private val nameView = textView(ctx, 12f, ctx.theme.primaryTextColor, Inter.MEDIUM).apply {
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
    }
    private val powerIcon = MdiIconView(ctx.context)
    private val powerBtn = iconButton(powerIcon, 22f)
    private val title = textView(ctx, 17f, ctx.theme.primaryTextColor, Inter.MEDIUM).apply {
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
    }
    private val artist = textView(ctx, 13f, ctx.theme.primaryTextColor).apply {
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
    }
    private val prevIcon = MdiIconView(ctx.context)
    private val prevBtn = iconButton(prevIcon, 24f)
    private val playIcon = MdiIconView(ctx.context)
    private val playBtn = iconButton(playIcon, 24f)
    private val nextIcon = MdiIconView(ctx.context)
    private val nextBtn = iconButton(nextIcon, 24f)
    private val transport = LinearLayout(ctx.context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val muteIcon = MdiIconView(ctx.context)
    private val muteBtn = iconButton(muteIcon, 20f)
    private val volumeRow = LinearLayout(ctx.context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = GONE
    }
    private val progress = MediaProgressView(ctx)
    private val eid = config.entity
    private var volumeSlider: HaHorizontalSliderView? = null
    private var artUrl: String? = null
    private var last: EntityState? = null
    private var ticking = false

    private val progressTick = object : Runnable {
        override fun run() {
            if (!ticking) return
            updateProgress(last)
            ctx.main.postDelayed(this, 1_000L)
        }
    }

    init {
        minimumHeight = ctx.px(112f)
        addView(artwork, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(tint, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(scrim, LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val content = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.px(16f), ctx.px(10f), ctx.px(10f), ctx.px(12f))
        }
        val header = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(headerIcon, LinearLayout.LayoutParams(ctx.px(18f), ctx.px(18f)).apply { marginEnd = ctx.px(8f) })
            addView(nameView, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(powerBtn, LinearLayout.LayoutParams(ctx.px(36f), ctx.px(36f)))
        }
        content.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val info = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(artist, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ctx.px(3f) })
        }
        transport.addView(prevBtn, LinearLayout.LayoutParams(ctx.px(36f), ctx.px(36f)))
        transport.addView(playBtn, LinearLayout.LayoutParams(ctx.px(42f), ctx.px(42f)).apply {
            marginStart = ctx.px(4f); marginEnd = ctx.px(4f)
        })
        transport.addView(nextBtn, LinearLayout.LayoutParams(ctx.px(36f), ctx.px(36f)))
        val middle = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(info, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = ctx.px(8f) })
            addView(transport, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
        content.addView(middle, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ctx.px(10f) })

        volumeRow.addView(muteBtn, LinearLayout.LayoutParams(ctx.px(32f), ctx.px(32f)).apply { marginEnd = ctx.px(6f) })
        content.addView(volumeRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ctx.px(8f) })
        addView(content, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(progress, LayoutParams(MATCH_PARENT, ctx.px(3f), Gravity.BOTTOM))

        if (eid != null) {
            val slider = HaHorizontalSliderView(ctx, ctx.theme.primaryColor) { frac ->
                val vol = (frac * 100f).roundToInt() / 100.0
                ctx.callService("media_player", "volume_set", JSONObject().put("entity_id", eid).put("volume_level", vol), null)
            }
            volumeSlider = slider
            volumeRow.addView(slider, LinearLayout.LayoutParams(0, ctx.px(28f), 1f).apply { marginEnd = ctx.px(6f) })

            isClickable = true
            setOnClickListener { ctx.performAction(ActionConfig.parse(config.raw.optJSONObject("tap_action")), eid) }
            bindHoldAction(holdActionFor(config.raw, eid), eid)
            fun call(service: String, extra: (JSONObject) -> Unit = {}) {
                ctx.callService("media_player", service, JSONObject().put("entity_id", eid).also(extra), null)
            }
            powerBtn.contentDescription = "Power"
            powerBtn.setOnClickListener { call("toggle") }
            prevBtn.contentDescription = "Previous track"
            prevBtn.setOnClickListener { call("media_previous_track") }
            playBtn.setOnClickListener { call("media_play_pause") }
            nextBtn.contentDescription = "Next track"
            nextBtn.setOnClickListener { call("media_next_track") }
            muteBtn.setOnClickListener {
                val muted = ctx.store[eid]?.attributes?.get("is_volume_muted") == true
                call("volume_mute") { it.put("is_volume_muted", !muted) }
            }
            observe(eid) { s -> render(s) }
        } else {
            nameView.text = config.name ?: "Media"
            render(null)
        }
    }

    private fun iconButton(icon: MdiIconView, iconDp: Float): FrameLayout = FrameLayout(ctx.context).apply {
        isClickable = true
        addView(icon, LayoutParams(ctx.px(iconDp), ctx.px(iconDp), Gravity.CENTER))
    }

    private fun render(s: EntityState?) {
        last = s
        val attrs = s?.attributes.orEmpty()
        val off = s == null || s.state == "off" || s.isUnavailable
        val playing = s?.state == "playing"
        val picture = if (off) null else s.entityPicture
        setArtwork(picture)
        val onArt = picture != null && artwork.drawable != null

        tint.visibility = if (!onArt && !off && playing) VISIBLE else GONE
        tint.setBackgroundColor(CardSurface.withAlpha(ctx.theme.primaryColor, if (ctx.theme.dark) 0.22f else 0.14f))

        val fg = if (onArt) Color.WHITE else ctx.theme.primaryTextColor
        val fgSoft = CardSurface.withAlpha(fg, 0.75f)
        headerIcon.setIcon(MdiIcons.resolveIconName(null, s).takeIf { s != null } ?: "mdi:speaker", fgSoft)
        nameView.setTextColor(fgSoft)
        nameView.text = s?.friendlyName ?: nameView.text
        powerIcon.setIcon("mdi:power", if (off) fgSoft else fg)

        val mediaTitle = (attrs["media_title"] as? String)?.takeIf { it.isNotBlank() }
        val sub = listOf("media_artist", "media_album_name", "app_name")
            .firstNotNullOfOrNull { (attrs[it] as? String)?.takeIf(String::isNotBlank) }
        title.setTextColor(fg)
        artist.setTextColor(fgSoft)
        title.text = mediaTitle ?: s?.formattedState() ?: "…"
        artist.text = if (mediaTitle != null) sub.orEmpty() else ""
        artist.visibility = if (artist.text.isNullOrEmpty()) GONE else VISIBLE

        transport.visibility = if (off) GONE else VISIBLE
        prevIcon.setIcon("mdi:skip-previous", fg)
        nextIcon.setIcon("mdi:skip-next", fg)
        playIcon.setIcon(if (playing) "mdi:pause" else "mdi:play", Color.WHITE)
        playBtn.contentDescription = if (playing) "Pause" else "Play"
        playBtn.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ctx.theme.primaryColor)
        }

        val vol = (attrs["volume_level"] as? Number)?.toFloat()
        volumeRow.visibility = if (vol != null && !off) VISIBLE else GONE
        if (vol != null) {
            val muted = attrs["is_volume_muted"] == true
            muteIcon.setIcon(if (muted) "mdi:volume-off" else "mdi:volume-high", fg)
            muteBtn.contentDescription = if (muted) "Unmute" else "Mute"
            volumeSlider?.setValue(vol, active = !muted && vol > 0f)
        }

        updateProgress(s)
        setTicking(playing && isAttachedToWindow)
    }

    private fun setArtwork(url: String?) {
        if (url == artUrl) return
        artUrl = url
        if (url == null) {
            artwork.setImageDrawable(null)
            artwork.visibility = GONE
            scrim.visibility = GONE
            return
        }
        ctx.loadImage(url) { bmp ->
            if (artUrl != url) return@loadImage
            if (bmp != null) {
                artwork.setImageBitmap(bmp)
                artwork.visibility = VISIBLE
                scrim.visibility = VISIBLE
            } else {
                artwork.setImageDrawable(null)
                artwork.visibility = GONE
                scrim.visibility = GONE
            }
            render(last)
        }
    }

    private fun updateProgress(s: EntityState?) {
        val attrs = s?.attributes.orEmpty()
        val duration = (attrs["media_duration"] as? Number)?.toDouble() ?: 0.0
        val position = (attrs["media_position"] as? Number)?.toDouble()
        if (s == null || duration <= 0.0 || position == null || s.state == "off" || s.isUnavailable) {
            progress.visibility = GONE
            return
        }
        var pos = position
        if (s.state == "playing") {
            val updatedAt = (attrs["media_position_updated_at"] as? String)?.let { raw ->
                runCatching { java.time.OffsetDateTime.parse(raw).toInstant().toEpochMilli() }.getOrNull()
            }
            if (updatedAt != null) pos += (System.currentTimeMillis() - updatedAt).coerceAtLeast(0L) / 1000.0
        }
        progress.visibility = VISIBLE
        progress.setFraction((pos / duration).toFloat().coerceIn(0f, 1f), artwork.visibility == VISIBLE)
    }

    private fun setTicking(on: Boolean) {
        if (on == ticking) return
        ticking = on
        ctx.main.removeCallbacks(progressTick)
        if (on) ctx.main.postDelayed(progressTick, 1_000L)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        setTicking(last?.state == "playing")
    }

    override fun onDetachedFromWindow() {
        setTicking(false)
        super.onDetachedFromWindow()
    }

    private companion object {
        const val SCRIM_TOP = 0x66000000
        const val SCRIM_BOTTOM = 0xCC000000.toInt()
    }
}

/** The 3 dp playback progress line along the bottom of the media card, filled in the theme's primary color. */
internal class MediaProgressView(private val ctx: VitroCardContext) : View(ctx.context) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var fraction = 0f
    private var onArt = false

    fun setFraction(value: Float, overArtwork: Boolean) {
        if (value == fraction && overArtwork == onArt) return
        fraction = value
        onArt = overArtwork
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        track.color = CardSurface.withAlpha(if (onArt) Color.WHITE else ctx.theme.primaryTextColor, 0.18f)
        fill.color = ctx.theme.primaryColor
        canvas.drawRect(0f, 0f, w, h, track)
        canvas.drawRect(0f, 0f, w * fraction, h, fill)
    }
}
