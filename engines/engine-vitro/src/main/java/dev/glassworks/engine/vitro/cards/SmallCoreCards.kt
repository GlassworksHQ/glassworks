/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.cards

import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.glassworks.engine.vitro.compat.ShortcutDefaults
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.HaDisplay
import dev.glassworks.engine.vitro.data.HaText
import dev.glassworks.engine.vitro.model.ActionConfig
import dev.glassworks.engine.vitro.model.CardConfig
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.A11yRole
import dev.glassworks.engine.vitro.ui.CardSurface
import dev.glassworks.engine.vitro.ui.HaErrorSeverity
import dev.glassworks.engine.vitro.ui.errorCardContent
import dev.glassworks.engine.vitro.ui.MdiIconView
import dev.glassworks.engine.vitro.ui.MdiIcons
import dev.glassworks.engine.vitro.ui.VitroAccess
import dev.glassworks.engine.vitro.ui.VitroCardContext
import dev.glassworks.engine.vitro.ui.VitroFonts
import dev.glassworks.engine.vitro.ui.bindHoldAction
import dev.glassworks.ui.Inter
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max
import kotlin.math.roundToInt

// The small built-in cards of Home Assistant 2026.11 (frontend 20261028.8), each drawn as its `hui-*-card.ts` draws
// it: statistic, date, plant-status, toggle-group, shortcut and alert, and HA's error card for a config HA can't build.

/** HA's string for [key] in the user's language, with [english] (same `{arg}` syntax) until HA's strings are in. */
internal fun haUi(key: String, english: String, vararg args: Pair<String, Any?>): String =
    dev.glassworks.engine.vitro.data.HaText.ui(key, *args).ifEmpty {
        if (args.isEmpty()) english else dev.glassworks.engine.vitro.data.HaText.format(english, args.toMap())
    }

private fun smallText(ctx: VitroCardContext, cssPx: Float, color: Int, weight: Int = Inter.REGULAR): TextView =
    TextView(ctx.context).apply {
        val sp = ctx.scaledSp(cssPx)
        textSize = sp
        setTextColor(color)
        typeface = VitroFonts.typeface(ctx.context, weight, sp)
        includeFontPadding = false
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

/** A [cssPx] text on a line box of [lineHeight] (a CSS `line-height` factor), the text centered in it. */
private fun lineBoxHeight(ctx: VitroCardContext, cssPx: Float, lineHeight: Float): Int =
    (cssPx * lineHeight * ctx.dp * ctx.theme.fontScale).roundToInt()

/**
 * HA's state colors (`stateColorCss`, `common/entity/state_color.ts`): for a state-colored domain the first of
 * `--state-<domain>-<device_class>-<state>-color`, `--state-<domain>-<state>-color`,
 * `--state-<domain>-<active|inactive>-color` and `--state-<active|inactive>-color` the theme or HA's defaults set; a
 * battery sensor by its level; `--state-unavailable-color` while unavailable; none for any other domain.
 */
internal object HaStateColor {
    private val STATE_COLORED = setOf(
        "alarm_control_panel", "alert", "automation", "binary_sensor", "calendar", "camera", "climate", "cover",
        "device_tracker", "fan", "group", "humidifier", "input_boolean", "lawn_mower", "light", "lock", "media_player",
        "person", "plant", "remote", "schedule", "script", "siren", "sun", "switch", "timer", "update", "vacuum",
        "valve", "water_heater", "weather",
    )

    /** HA's named colors (`--<name>-color`, `color.globals.ts`). */
    private val NAMED = mapOf(
        "red" to 0xFFF44336, "pink" to 0xFFE91E63, "purple" to 0xFF926BC7, "deep-purple" to 0xFF6E41AB,
        "indigo" to 0xFF3F51B5, "blue" to 0xFF2196F3, "light-blue" to 0xFF03A9F4, "cyan" to 0xFF00BCD4,
        "teal" to 0xFF009688, "green" to 0xFF4CAF50, "light-green" to 0xFF8BC34A, "lime" to 0xFFCDDC39,
        "yellow" to 0xFFFFEB3B, "amber" to 0xFFFFC107, "orange" to 0xFFFF9800, "deep-orange" to 0xFFFF6F22,
        "brown" to 0xFF795548, "light-grey" to 0xFFBDBDBD, "grey" to 0xFF9E9E9E, "dark-grey" to 0xFF606060,
        "blue-grey" to 0xFF607D8B, "black" to 0xFF000000, "white" to 0xFFFFFFFF, "disabled" to 0xFFBDBDBD,
    ).mapValues { it.value.toInt() }

    /** HA's default `--state-*` colors, by property, as named colors. */
    private val DEFAULTS = mapOf(
        "state-active-color" to "amber", "state-inactive-color" to "grey",
        "state-alarm_control_panel-armed_away-color" to "green", "state-alarm_control_panel-armed_custom_bypass-color" to "green",
        "state-alarm_control_panel-armed_home-color" to "green", "state-alarm_control_panel-armed_night-color" to "green",
        "state-alarm_control_panel-armed_vacation-color" to "green", "state-alarm_control_panel-arming-color" to "orange",
        "state-alarm_control_panel-disarming-color" to "orange", "state-alarm_control_panel-pending-color" to "orange",
        "state-alarm_control_panel-triggered-color" to "red",
        "state-alert-off-color" to "orange", "state-alert-on-color" to "red",
        "state-binary_sensor-active-color" to "amber",
        "state-binary_sensor-battery-on-color" to "red", "state-binary_sensor-carbon_monoxide-on-color" to "red",
        "state-binary_sensor-gas-on-color" to "red", "state-binary_sensor-glass_break-on-color" to "red",
        "state-binary_sensor-heat-on-color" to "red", "state-binary_sensor-lock-on-color" to "red",
        "state-binary_sensor-moisture-on-color" to "red", "state-binary_sensor-problem-on-color" to "red",
        "state-binary_sensor-safety-on-color" to "red", "state-binary_sensor-smoke-on-color" to "red",
        "state-binary_sensor-sound-on-color" to "red", "state-binary_sensor-tamper-on-color" to "red",
        "state-climate-auto-color" to "green", "state-climate-cool-color" to "blue", "state-climate-dry-color" to "orange",
        "state-climate-fan_only-color" to "cyan", "state-climate-heat-color" to "deep-orange", "state-climate-heat-cool-color" to "amber",
        "state-cover-active-color" to "purple", "state-device_tracker-active-color" to "blue", "state-device_tracker-home-color" to "green",
        "state-fan-active-color" to "cyan", "state-humidifier-on-color" to "blue", "state-lawn_mower-active-color" to "teal",
        "state-lawn_mower-error-color" to "red", "state-light-active-color" to "amber",
        "state-lock-jammed-color" to "red", "state-lock-locked-color" to "green", "state-lock-locking-color" to "orange",
        "state-lock-unlocked-color" to "red", "state-lock-unlocking-color" to "orange", "state-lock-open-color" to "red",
        "state-lock-opening-color" to "orange", "state-media_player-active-color" to "light-blue",
        "state-person-active-color" to "blue", "state-person-home-color" to "green", "state-plant-active-color" to "red",
        "state-siren-active-color" to "red", "state-sun-above_horizon-color" to "amber", "state-sun-below_horizon-color" to "indigo",
        "state-switch-active-color" to "amber", "state-update-active-color" to "orange", "state-vacuum-active-color" to "teal",
        "state-vacuum-error-color" to "red", "state-valve-active-color" to "blue",
        "state-sensor-battery-high-color" to "green", "state-sensor-battery-low-color" to "red", "state-sensor-battery-medium-color" to "orange",
        "state-water_heater-eco-color" to "green", "state-water_heater-electric-color" to "orange", "state-water_heater-gas-color" to "orange",
        "state-water_heater-heat_pump-color" to "orange", "state-water_heater-high_demand-color" to "deep-orange",
        "state-water_heater-performance-color" to "deep-orange",
        "state-weather-clear_night-color" to "deep-purple", "state-weather-cloudy-color" to "light-grey",
        "state-weather-exceptional-color" to "red", "state-weather-fog-color" to "grey", "state-weather-hail-color" to "cyan",
        "state-weather-lightning-color" to "yellow", "state-weather-lightning_rainy-color" to "lime",
        "state-weather-partlycloudy-color" to "blue-grey", "state-weather-pouring-color" to "indigo",
        "state-weather-rainy-color" to "blue", "state-weather-snowy_rainy-color" to "light-blue",
        "state-weather-sunny-color" to "amber", "state-weather-windy-color" to "green", "state-weather-windy_variant-color" to "green",
    )

    /** A `--<name>` custom property's color: the theme's, else HA's default. */
    fun property(ctx: VitroCardContext, name: String): Int? {
        ThemeResolver.parseColor(ctx.theme.vars[name]?.let { ThemeResolver.resolveValue(it, ctx.theme.vars, 0) })?.let { return it }
        DEFAULTS[name]?.let { return named(ctx, it) }
        if (name.endsWith("-color")) NAMED[name.removeSuffix("-color")]?.let { return it }
        return null
    }

    private fun named(ctx: VitroCardContext, color: String): Int? =
        ThemeResolver.parseColor(ctx.theme.vars["$color-color"]) ?: NAMED[color]

    /** HA's `computeCssColor`: a theme color name as its `--<name>-color`, any other value as a CSS color. */
    fun cssColor(ctx: VitroCardContext, value: String?): Int? {
        val v = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val key = v.lowercase(Locale.ROOT)
        if (key == "primary") return ctx.theme.primaryColor
        if (key == "accent") return ctx.theme.accentColor
        named(ctx, key)?.let { return it }
        return MarkdownInline.parseCssColor(v) ?: ThemeResolver.parseColor(v)
    }

    /**
     * `stateColorCss(stateObj, state)`, or null where HA gives none. Without [inactiveDefault] the chain stops short of
     * `--state-inactive-color`, as under a heading badge's `--state-inactive-color: initial`.
     */
    fun of(ctx: VitroCardContext, s: EntityState, state: String = s.state, inactiveDefault: Boolean = true): Int? {
        // HA's iconColorCSS reads --state-unavailable-color (a theme may set it); HA's default is the disabled text color.
        if (state == "unavailable") return property(ctx, "state-unavailable-color") ?: ctx.theme.disabledTextColor
        val domain = s.domain
        val dc = s.deviceClass
        if (domain == "sensor" && dc == "battery") {
            val v = state.toDoubleOrNull()
            if (v != null) {
                return property(ctx, when {
                    v >= 70 -> "state-sensor-battery-high-color"
                    v >= 30 -> "state-sensor-battery-medium-color"
                    else -> "state-sensor-battery-low-color"
                })
            }
        }
        if (domain !in STATE_COLORED) return null
        val active = HaStateActive.of(s, state)
        val key = state.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "_")
        val activeKey = if (active) "active" else "inactive"
        val props = buildList {
            if (dc != null) add("state-$domain-$dc-$key-color")
            add("state-$domain-$key-color")
            add("state-$domain-$activeKey-color")
            if (active || inactiveDefault) add("state-$activeKey-color")
        }
        for (p in props) property(ctx, p)?.let { return it }
        return null
    }

    /** HA's `CLIMATE_HVAC_ACTION_TO_MODE` (`data/climate.ts`). */
    private val HVAC_ACTION_TO_MODE = mapOf(
        "cooling" to "cool", "defrosting" to "heat", "drying" to "dry", "fan" to "fan_only", "heating" to "heat",
        "idle" to "off", "off" to "off", "preheating" to "heat",
    )

    /**
     * The color HA draws a state icon in when it follows the state: `<state-badge>` with `color: "state"` (the glance
     * card's default) and the button card's `_computeColor` (no `color` set). Both set `--state-inactive-color` to the
     * icon color (`initial` under the badge, `--state-icon-color` on the button), so an inactive state with no color of
     * its own draws in `--state-icon-color`; `rgb_color` wins over the state's color, and `hvac_action` takes its mode's
     * color (or none for an action HA doesn't map). The badge applies `hvac_action` after `rgb_color`, the button
     * before ([hvacOverRgb]). An unavailable icon is `--state-unavailable-color` either way (`iconColorCSS`). The
     * brightness filter ([stateColorBrightness]) comes on top.
     */
    fun icon(ctx: VitroCardContext, s: EntityState?, hvacOverRgb: Boolean): Int {
        val iconColor = ctx.theme.stateIconColor
        if (s == null) return iconColor
        if (s.state == "unavailable") return of(ctx, s) ?: iconColor
        val rgb = (s.attributes["rgb_color"] as? List<*>)?.takeIf { it.size >= 3 }?.map { (it as? Number)?.toInt() }
            ?.takeIf { c -> c.all { it != null } }?.let { Color.rgb(it[0]!!.coerceIn(0, 255), it[1]!!.coerceIn(0, 255), it[2]!!.coerceIn(0, 255)) }
        val hvac = (s.attributes["hvac_action"] as? String)?.takeIf { it.isNotEmpty() }
        fun hvacColor(): Int = HVAC_ACTION_TO_MODE[hvac]?.let { of(ctx, s, it, inactiveDefault = false) } ?: iconColor
        if (hvac != null && (hvacOverRgb || rgb == null)) return hvacColor()
        if (rgb != null) return rgb
        return of(ctx, s, inactiveDefault = false) ?: iconColor
    }
}

/**
 * HA's `ha-tile-container` with an `ha-tile-icon` and `ha-tile-info`, as the toggle-group, shortcut and alert cards
 * use it: a 56 px row (`padding: 0 10px`, 10 px gap), or a centered column when [vertical], with the 36 px icon (a
 * 24 px glyph, on a 20% disc of `--tile-color` when the icon has its own action) and the 14 px primary line over the
 * 12 px secondary one. The same geometry as Vitro's tile card ([TileCardView]).
 */
abstract class HaTileLikeCardView(ctx: VitroCardContext, protected val vertical: Boolean) : CardSurface(ctx) {
    override val contentInsideBorder: Boolean get() = false

    protected val iconView = MdiIconView(ctx.context)
    protected val iconFrame = FrameLayout(ctx.context)
    protected val primary: TextView = smallText(ctx, 14f, ctx.theme.primaryTextColor, Inter.MEDIUM)
    protected val secondary: TextView = smallText(ctx, 12f, ctx.theme.primaryTextColor)
    private val disc = GradientDrawable().apply { shape = GradientDrawable.OVAL }
    protected val row = LinearLayout(ctx.context)

    init {
        val pad = ctx.px(10f)
        row.apply {
            orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = if (vertical) Gravity.CENTER else Gravity.CENTER_VERTICAL
            if (vertical) setPadding(pad, pad, pad, pad) else setPadding(pad, 0, pad, 0)
            minimumHeight = ctx.px(56f)
        }
        val inset = ctx.px(6f)
        iconFrame.setPadding(inset, inset, inset, inset)
        iconFrame.addView(iconView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        row.addView(iconFrame, LinearLayout.LayoutParams(ctx.px(36f), ctx.px(36f)).apply {
            if (vertical) bottomMargin = ctx.px(8f) else marginEnd = pad
        })
        val info = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (vertical) Gravity.CENTER_HORIZONTAL else Gravity.START
            primary.gravity = Gravity.CENTER_VERTICAL or (if (vertical) Gravity.CENTER_HORIZONTAL else Gravity.START)
            secondary.gravity = primary.gravity
            primary.letterSpacing = 0.1f / 14f
            secondary.letterSpacing = 0.4f / 12f
            addView(primary, LinearLayout.LayoutParams(if (vertical) WRAP_CONTENT else MATCH_PARENT, (22.4f * ctx.dp).roundToInt()))
            addView(secondary, LinearLayout.LayoutParams(if (vertical) WRAP_CONTENT else MATCH_PARENT, (14.4f * ctx.dp).roundToInt()))
        }
        row.addView(info, LinearLayout.LayoutParams(if (vertical) WRAP_CONTENT else 0, WRAP_CONTENT, if (vertical) 0f else 1f))
        addView(row, LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    /** Colors the icon [color]; [disc] draws its 20% shape behind it, as an `interactive` `ha-tile-icon` does. */
    protected fun paintIcon(icon: String, color: Int, withDisc: Boolean) {
        iconView.setIcon(icon, color)
        if (withDisc) {
            disc.setColor(withAlpha(color, 0.2f))
            iconFrame.background = disc
        } else {
            iconFrame.background = null
        }
    }

    protected fun setLines(first: String, second: String?) {
        primary.text = first
        secondary.text = second.orEmpty()
        secondary.visibility = if (second == null) GONE else VISIBLE
    }
}

// ---------------------------------------------------------------------------------------------
// toggle-group
// ---------------------------------------------------------------------------------------------

/**
 * HA 2026's `toggle-group` card (`hui-toggle-group-card`): no card surface, the power icon (on its disc, in the first
 * on entity's state color or the card's `color`, else `--state-inactive-color`) turns all its entities on when none is
 * on, else all off, in one call to the first entity's domain; the title (or the count) and the count under it.
 */
class ToggleGroupCardView(ctx: VitroCardContext, private val config: CardConfig) :
    HaTileLikeCardView(ctx, config.raw.optBoolean("vertical", false)) {
    private val entities: List<String> = config.raw.optJSONArray("entities")?.let { arr ->
        (0 until arr.length()).mapNotNull { i ->
            when (val e = arr.opt(i)) {
                is String -> e.takeIf { it.isNotBlank() }
                is JSONObject -> e.optString("entity", "").ifEmpty { null }
                else -> null
            }
        }
    }.orEmpty()

    init {
        // `background: none; border: none; box-shadow: none`. The tile container still sits a `--ha-card-border-width`
        // out from the card's edges (`margin: calc(-1 * var(--ha-card-border-width, 1px))`), the border gone or not.
        dropSurface()
        elevation = 0f
        val out = -(ctx.theme.cardBorderWidthDp * ctx.dp).roundToInt()
        (row.layoutParams as LayoutParams).setMargins(out, out, out, out)
        iconFrame.isClickable = true
        applyHaRipple(iconFrame, 999f, ctx.theme.dark)
        VitroAccess.role(iconFrame, A11yRole.BUTTON)
        iconFrame.setOnClickListener { toggle() }
        entities.distinct().forEach { id -> observe(id) { render() } }
        render()
    }

    private fun onEntities(): List<EntityState> = entities.mapNotNull { ctx.store[it] }.filter { it.isActive }

    private fun render() {
        val on = onEntities()
        val total = entities.size
        val label = when (on.size) {
            0 -> haUi("ui.card.toggle-group.all_off", "All off")
            total -> haUi("ui.card.toggle-group.all_on", "All on")
            else -> haUi("ui.card.toggle-group.some_on", "{on_count} of {total_count} on", "on_count" to on.size, "total_count" to total)
        }
        val title = config.raw.optString("title", "").ifEmpty { null }
        setLines(title ?: label, if (title != null) label else null)
        val color = if (on.isEmpty()) null else HaStateColor.cssColor(ctx, config.raw.optString("color", "").ifEmpty { null })
            ?: HaStateColor.of(ctx, on.first())
        paintIcon("mdi:power", color ?: ctx.theme.stateInactiveColor, withDisc = true)
        val turnOn = on.isEmpty()
        iconFrame.contentDescription = when {
            title != null -> if (turnOn) haUi("ui.components.entity.entity-toggle.turn_on", "Turn {name} on", "name" to title)
                else haUi("ui.components.entity.entity-toggle.turn_off", "Turn {name} off", "name" to title)
            else -> if (turnOn) haUi("ui.card.toggle-group.turn_all_on", "Turn all on") else haUi("ui.card.toggle-group.turn_all_off", "Turn all off")
        }
    }

    private fun toggle() {
        val first = entities.firstOrNull() ?: return
        val domain = first.substringBefore('.')
        val service = toggleAction(domain, onEntities().isEmpty())
        ctx.callService(domain, service, JSONObject().put("entity_id", JSONArray(entities)), null)
    }

    companion object {
        /** HA's `getToggleAction`: the domain's own on and off services, else `turn_on` / `turn_off`. */
        fun toggleAction(domain: String, on: Boolean): String {
            val special = mapOf(
                "button" to ("press" to null), "input_button" to ("press" to null), "scene" to ("turn_on" to null),
                "cover" to ("open_cover" to "close_cover"), "lock" to ("unlock" to "lock"),
                "valve" to ("open_valve" to "close_valve"),
            )[domain]
            return if (special != null) (if (on) special.first else special.second ?: special.first) else if (on) "turn_on" else "turn_off"
        }
    }
}

// ---------------------------------------------------------------------------------------------
// shortcut
// ---------------------------------------------------------------------------------------------

/**
 * HA 2026's `shortcut` card (`hui-shortcut-card`): a tile with an icon in `--tile-color` (the card's `color`, else
 * `--primary-color`) and no disc, its `label` (else its action's default, [ShortcutDefaults]) and `description`. The
 * whole card runs its actions, and is a button only when it has one.
 */
class ShortcutCardView(ctx: VitroCardContext, private val config: CardConfig) :
    HaTileLikeCardView(ctx, config.raw.optBoolean("vertical", false)) {
    private val raw = config.raw
    private val tap = ActionConfig.parse(raw.optJSONObject("tap_action"), "none")
    private val hold = ActionConfig.parse(raw.optJSONObject("hold_action"), "none")
    private val defaults = ShortcutDefaults.of(raw.optJSONObject("tap_action")) ?: ShortcutDefaults.NONE

    init {
        val color = HaStateColor.cssColor(ctx, raw.optString("color", "").ifEmpty { null }) ?: ctx.theme.primaryColor
        val label = raw.optString("label", "").ifEmpty { null } ?: defaultLabel()
        val description = raw.optString("description", "").ifEmpty { null }
        setLines(label, description)
        paintIcon(raw.optString("icon", "").ifEmpty { null } ?: defaultIcon(), color, withDisc = false)
        if (tap.action != "none" || hold.action != "none" || raw.optJSONObject("double_tap_action")?.optString("action", "none").let { it != null && it != "none" }) {
            isClickable = true
            applyHaRipple(this, ctx.theme.cardBorderRadiusDp * ctx.dp, ctx.theme.dark)
            setOnClickListener { ctx.performAction(tap, null) }
            bindHoldAction(hold, null)
            VitroAccess.role(this, A11yRole.BUTTON)
            contentDescription = label
        }
    }

    private fun defaultLabel(): String = when (defaults.kind) {
        "area" -> HaDisplay.areaName(defaults.label) ?: defaults.label
        "device" -> HaDisplay.deviceName(defaults.label) ?: defaults.label
        else -> defaults.label
    }

    private fun defaultIcon(): String =
        if (defaults.kind == "area") HaDisplay.areaIcon(defaults.label) ?: defaults.icon else defaults.icon
}

// ---------------------------------------------------------------------------------------------
// alert
// ---------------------------------------------------------------------------------------------

/**
 * HA 2026's `alert` card (`hui-alert-card`): a tile for an alarm panel, binary sensor, cover or lock. While the entity
 * is in its alert state (triggered, on, open; jammed, unlocked or open for a lock) the card is washed in
 * `alert_color` (its state color by default) at 30%, pulsing with `pulse: true`; otherwise in `clear_color` (none by
 * default). The icon takes that color, else `--secondary-text-color`; the state, how long ago it changed and the
 * area show under the name unless `hide_state`.
 */
class AlertCardView(ctx: VitroCardContext, private val config: CardConfig) :
    HaTileLikeCardView(ctx, config.raw.optBoolean("vertical", false)) {
    private val raw = config.raw
    private val entityId = config.entity.orEmpty()
    private val wash = GradientDrawable().apply { cornerRadius = ctx.theme.cardBorderRadiusDp * ctx.dp }
    private val washView = View(ctx.context).apply { background = wash; isClickable = false; isFocusable = false }
    private var pulse: ValueAnimator? = null
    private var pulsing = false

    init {
        // `ha-card::before { inset: 0 }`: the padding box, inside the card's border.
        val border = (ctx.theme.cardBorderWidthDp * ctx.dp).roundToInt()
        wash.cornerRadius = (ctx.theme.cardBorderRadiusDp * ctx.dp - border).coerceAtLeast(0f)
        addView(washView, 0, LayoutParams(MATCH_PARENT, MATCH_PARENT).apply { setMargins(border, border, border, border) })
        val tap = ActionConfig.parse(raw.optJSONObject("tap_action"), "more-info")
        val hold = ActionConfig.parse(raw.optJSONObject("hold_action"), "none")
        if (tap.action != "none" || hold.action != "none") {
            isClickable = true
            applyHaRipple(this, ctx.theme.cardBorderRadiusDp * ctx.dp, ctx.theme.dark)
            setOnClickListener { ctx.performAction(tap, entityId) }
            bindHoldAction(hold, entityId)
            VitroAccess.role(this, A11yRole.BUTTON)
        }
        observe(entityId, ::render)
    }

    private fun render(s: EntityState?) {
        // HA draws nothing for an entity it doesn't have (and nothing pulses for one).
        row.visibility = if (s == null) INVISIBLE else VISIBLE
        visibility = if (s == null) GONE else VISIBLE
        if (s == null) {
            pulsing = false
            updatePulse()
            return
        }
        val active = isAlertActive(s)
        val spec = if (active) raw.optString("alert_color", "").ifEmpty { "state" } else raw.optString("clear_color", "").ifEmpty { "none" }
        val color = when (spec) {
            "none" -> null
            "state" -> HaStateColor.of(ctx, s)
            else -> HaStateColor.cssColor(ctx, spec)
        }
        pulsing = active && raw.optBoolean("pulse", false) && color != null
        if (color != null) wash.setColor(color) else wash.setColor(Color.TRANSPARENT)
        updatePulse()
        val name = config.name(s) ?: s.displayName
        val area = HaDisplay.areaOf(entityId)?.let { HaDisplay.areaName(it) }
        val second = if (raw.optBoolean("hide_state", false)) null else buildString {
            append(s.formattedState())
            if (s.lastChanged > 0.0) append(" · ").append(shortRelative((s.lastChanged * 1000).toLong(), ctx.now()))
            if (area != null) append(" · ").append(area)
        }
        setLines(name, second)
        paintIcon(MdiIcons.resolveIconName(config.icon, s), color ?: ctx.theme.secondaryTextColor, withDisc = false)
        contentDescription = name
    }

    private fun updatePulse() {
        pulse?.cancel()
        pulse = null
        if (!pulsing) {
            washView.alpha = 0.3f
            return
        }
        if (!isAttachedToWindow) {
            washView.alpha = 0.3f
            return
        }
        // `pulse-opacity` from 0 to 0.3 over 1 s, ease-in-out, back and forth.
        pulse = ValueAnimator.ofFloat(0f, 0.3f).apply {
            duration = 1000L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { washView.alpha = it.animatedValue as Float }
            start()
        }
    }

    /** "5 min. ago" moves on with the clock while the entity stays as it is: redrawn each minute while attached. */
    private val tick = object : Runnable {
        override fun run() {
            render(ctx.store[entityId])
            ctx.main.postDelayed(this, 60_000L)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updatePulse()
        ctx.main.removeCallbacks(tick)
        ctx.main.postDelayed(tick, 60_000L)
    }

    override fun onDetachedFromWindow() {
        pulse?.cancel()
        pulse = null
        ctx.main.removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    companion object {
        val ALERT_DOMAINS = setOf("alarm_control_panel", "binary_sensor", "cover", "lock")

        /** HA's `isAlertActive`. */
        fun isAlertActive(s: EntityState): Boolean = s.state in when (s.domain) {
            "alarm_control_panel" -> setOf("triggered")
            "cover" -> setOf("open")
            "lock" -> setOf("jammed", "unlocked", "open")
            else -> setOf("on")
        }

        /**
         * `ha-relative-time format="short"`: `Intl.RelativeTimeFormat` with `numeric: auto, style: short`. Vitro's locale
         * tables hold the long style only, so English is written here ("5 min. ago"), and other languages take
         * HA's long form from the tables until the short one is captured.
         */
        fun shortRelative(fromMs: Long, nowMs: Long): String {
            val locale = dev.glassworks.engine.vitro.data.HaLocale.current
            val zone = dev.glassworks.engine.vitro.data.HaLocale.timeZone()
            if (!locale.language.startsWith("en")) {
                return dev.glassworks.engine.vitro.data.HaFormat.relativeTime(fromMs, nowMs, locale, style = "short", zone = zone)
            }
            val (value, unit) = dev.glassworks.engine.vitro.data.HaFormat.selectUnit(fromMs, nowMs, locale, zone)
            val abbr = mapOf("second" to "sec.", "minute" to "min.", "hour" to "hr.", "week" to "wk.", "month" to "mo.", "year" to "yr.")
            when (unit) {
                "second" -> if (value == 0L) return "now"
                "minute", "hour" -> if (value == 0L) return "this ${abbr[unit]}"
                "day" -> when (value) { 0L -> return "today"; 1L -> return "tomorrow"; -1L -> return "yesterday" }
                else -> when (value) {
                    0L -> return "this ${abbr[unit]}"
                    1L -> return "next ${abbr[unit]}"
                    -1L -> return "last ${abbr[unit]}"
                }
            }
            val n = kotlin.math.abs(value)
            val word = if (unit == "day") (if (n == 1L) "day" else "days") else abbr.getValue(unit)
            return if (value > 0) "in $n $word" else "$n $word ago"
        }
    }
}

// ---------------------------------------------------------------------------------------------
// date
// ---------------------------------------------------------------------------------------------

/**
 * HA 2026's `date` card (`hui-date-card`): today's date, centered, with an optional title over it. The default is the
 * locale's weekday, day and month (`Intl.DateTimeFormat` with `weekday: long, day: numeric, month: long`); a
 * `date_format` list is written part by part as the clock card writes it ([ClockDate]). The zone is the card's
 * `time_zone`, else the server's; the text changes at midnight there. `date_size` medium and large make both bigger.
 */
class DateCardView(ctx: VitroCardContext, private val config: CardConfig) : CardSurface(ctx) {
    private val raw = config.raw
    private val size = raw.optString("date_size", "").takeIf { it == "medium" || it == "large" }
    private val parts: ClockDate? = (raw.opt("date_format") as? JSONArray)?.let { ClockDate.of(it) }
    private val dateView: TextView
    private var running = false
    private val midnight = Runnable { tick() }

    init {
        if (raw.optBoolean("no_background", false)) {
            dropSurface()
            elevation = 0f
        }
        val titleSize = when (size) { "medium" -> 16f; "large" -> 24f; else -> 14f }
        val dateSize = when (size) { "medium" -> 28f; "large" -> 40f; else -> 20f }
        val big = size != null
        val col = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val v = ctx.px(if (big) 16f else 6f)
            val h = ctx.px(if (big) 16f else 8f)
            setPadding(h, v, h, v)
        }
        val gap = ctx.px(if (big) 12f else 6f)
        if (raw.has("title") && !raw.isNull("title")) {
            col.addView(smallText(ctx, titleSize, ctx.theme.primaryTextColor).apply {
                text = raw.optString("title")
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(MATCH_PARENT, lineBoxHeight(ctx, titleSize, 1.2f)).apply { bottomMargin = gap })
        }
        dateView = smallText(ctx, dateSize, ctx.theme.primaryTextColor, Inter.MEDIUM).apply {
            gravity = Gravity.CENTER
            // `white-space: pre-line`: a `separator-new-line` part starts a line.
            maxLines = Int.MAX_VALUE
            ellipsize = null
            setLineSpacing(0f, 1f)
        }
        // `max-width: 100%; text-align: center`: centered within the card's width.
        col.addView(dateView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(col, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        render(Date(ctx.now()))
    }

    /**
     * The zone the card writes the date in: its `time_zone`, else the user's profile choice, the panel's or HA's
     * (`resolveTimeZone`, [dev.glassworks.engine.vitro.data.HaLocale.timeZone]).
     */
    internal fun zone(): TimeZone {
        val configured = raw.optString("time_zone", "").trim()
        return if (configured.isNotEmpty()) TimeZone.getTimeZone(configured) else dev.glassworks.engine.vitro.data.HaLocale.timeZone()
    }

    /** The text for [now] (tests): HA's weekday, day and month in the user's language, or the `date_format` parts. */
    internal fun textAt(now: Date): String {
        val tz = zone()
        parts?.let { return it.format(now, dev.glassworks.engine.vitro.data.HaLocale.current.language, tz) }
        // HA's own `weekday: long, day: numeric, month: long` in the user's language (HaIntl's data); a card with its
        // own `time_zone` formats the same fields in that zone.
        if (raw.optString("time_zone", "").isEmpty()) {
            dev.glassworks.engine.vitro.data.HaFormat.formatDateWeekdayDay(now.time).takeIf { it.isNotEmpty() }?.let { return it }
        }
        return weekdayMonthDay(now, tz)
    }

    /** `Intl.DateTimeFormat(language, {weekday: long, day: numeric, month: long, timeZone})`. */
    private fun weekdayMonthDay(now: Date, tz: TimeZone): String {
        val locale = Locale.forLanguageTag(dev.glassworks.engine.vitro.data.HaLocale.current.language)
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEEMMMMd")
        return SimpleDateFormat(pattern, locale).apply { timeZone = tz }.format(now)
    }

    private fun render(now: Date) {
        val t = textAt(now)
        if (dateView.text.toString() != t) dateView.text = t
    }

    private fun tick() {
        if (!running) return
        val now = ctx.now()
        render(Date(now))
        val cal = Calendar.getInstance(zone()).apply {
            timeInMillis = now
            add(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        ctx.main.postDelayed(midnight, max(1_000L, cal.timeInMillis - now))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        ctx.main.removeCallbacks(midnight)
        tick()
    }

    override fun onDetachedFromWindow() {
        running = false
        ctx.main.removeCallbacks(midnight)
        super.onDetachedFromWindow()
    }
}

// ---------------------------------------------------------------------------------------------
// statistic
// ---------------------------------------------------------------------------------------------

/**
 * HA's `statistic` card (`hui-statistic-card`): the entity card's layout (the name, 16 px medium and secondary, on a
 * 40 px line with the icon right of it; the value at 28 px with its unit at 16 px on one baseline) showing one
 * statistic of the entity or external statistic over its `period`: `recorder/statistic_during_period`'s `stat_type`
 * (`change`, `max`, `mean`, `min`), fetched again every five minutes. A blank value until it answers, `?` when it has
 * no value, HA's error in place of the card when the call fails. The card opens more-info, except for an external
 * statistic (an id with `:`).
 */
class StatisticCardView(ctx: VitroCardContext, private val config: CardConfig) : CardSurface(ctx) {
    private val raw = config.raw
    private val statisticId = config.entity.orEmpty()
    private val external = ':' in statisticId
    private val nameView = smallText(ctx, 16f, ctx.theme.secondaryTextColor, Inter.MEDIUM).apply { gravity = Gravity.CENTER_VERTICAL }
    private val iconView = MdiIconView(ctx.context)
    private val valueView = smallText(ctx, 28f, ctx.theme.primaryTextColor)
    private val unitView = smallText(ctx, 16f, ctx.theme.secondaryTextColor)
    private val body = LinearLayout(ctx.context).apply { orientation = LinearLayout.VERTICAL }
    private val error = smallText(ctx, 14f, ctx.theme.primaryTextColor).apply {
        maxLines = 3
        visibility = GONE
        val p = ctx.px(16f)
        setPadding(p, p, p, p)
    }
    private var metadata: JSONObject? = null

    /** What the last fetch gave: null before it answered, NaN for HA's `null` (shown as `?`). */
    internal var value: Double? = null
        private set
    private var running = false
    private val refetch = Runnable { fetch(); scheduleRefetch() }

    init {
        val header = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.px(16f), ctx.px(8f), ctx.px(16f), 0)
            addView(nameView, LinearLayout.LayoutParams(0, ctx.px(40f), 1f))
            addView(iconView, LinearLayout.LayoutParams(ctx.px(24f), ctx.px(24f)))
        }
        body.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val info = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = true
            setPadding(ctx.px(16f), 0, ctx.px(16f), ctx.px(16f))
            addView(valueView, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(unitView, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = ctx.px(4f) })
        }
        lineBox(valueView, 28f)
        lineBox(unitView, 16f)
        // `ha-card { height: 100%; flex-direction: column; justify-content: space-between }`: in a taller slot (a
        // sections grid's two rows) the value sits at the bottom.
        body.addView(View(ctx.context), LinearLayout.LayoutParams(0, 0, 1f))
        body.addView(info, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = -ctx.px(4f) })
        addView(body, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(error, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        if (!external) {
            isClickable = true
            setOnClickListener { ctx.openMoreInfo(statisticId) }
            VitroAccess.role(this, A11yRole.BUTTON)
        }
        if (statisticId.isNotEmpty() && !external) observe(statisticId) { render() } else render()
        fetchMetadata()
        fetch()
    }

    private fun lineBox(tv: TextView, cssPx: Float) {
        val fm = tv.paint.fontMetrics
        val half = ((1.2f * cssPx * ctx.dp * ctx.theme.fontScale - (fm.descent - fm.ascent)) / 2f).coerceAtLeast(0f).toInt()
        tv.setPadding(0, half, 0, half)
    }

    /** The `period` HA sends: the config's object, else `{calendar: {period: month}}` when it has none. */
    internal fun periodFields(msg: JSONObject) {
        val period = raw.optJSONObject("period") ?: JSONObject().put("calendar", JSONObject().put("period", "month"))
        period.optJSONObject("fixed_period")?.let { fp ->
            msg.put("fixed_period", JSONObject().apply {
                fp.opt("start")?.let { put("start_time", it) }
                fp.opt("end")?.let { put("end_time", it) }
            })
        }
        period.optJSONObject("calendar")?.let { c ->
            val cal = JSONObject(c.toString())
            if (cal.optString("period") == "week" && !cal.has("first_weekday")) {
                // HA's first weekday for the user's locale; the panel's locale stands in for it.
                val first = Calendar.getInstance(Locale.getDefault()).firstDayOfWeek
                cal.put("first_weekday", listOf("sun", "mon", "tue", "wed", "thu", "fri", "sat")[(first - 1).coerceIn(0, 6)])
            }
            msg.put("calendar", cal)
        }
        period.optJSONObject("rolling_window")?.let { msg.put("rolling_window", it) }
    }

    /** Bumped by each fetch: a reply to an older one (still in flight across a detach) is dropped. */
    private var fetchGeneration = 0

    private fun fetch() {
        if (statisticId.isEmpty()) return
        val msg = JSONObject().put("type", "recorder/statistic_during_period").put("statistic_id", statisticId)
        periodFields(msg)
        val generation = ++fetchGeneration
        ctx.callWs(msg) { ok, result, err ->
            ctx.main.post {
                if (generation != fetchGeneration) return@post
                if (!ok) {
                    showError(err?.optString("message", "").orEmpty().ifEmpty { "Unknown error" })
                    return@post
                }
                val stats = result as? JSONObject
                val type = raw.optString("stat_type", "")
                value = when {
                    stats == null || !stats.has(type) || stats.isNull(type) -> Double.NaN
                    else -> stats.optDouble(type, Double.NaN)
                }
                showError(null)
                render()
            }
        }
    }

    private fun fetchMetadata() {
        if (statisticId.isEmpty()) return
        val msg = JSONObject().put("type", "recorder/get_statistics_metadata").put("statistic_ids", JSONArray().put(statisticId))
        ctx.callWs(msg) { ok, result, _ ->
            ctx.main.post {
                if (!ok) return@post
                metadata = (result as? JSONArray)?.optJSONObject(0)
                render()
            }
        }
    }

    private fun showError(message: String?) {
        error.text = message.orEmpty()
        error.visibility = if (message == null) GONE else VISIBLE
        body.visibility = if (message == null) VISIBLE else INVISIBLE
    }

    private fun render() {
        val s = if (external) null else ctx.store[statisticId]
        nameView.text = config.nameFor(statisticId, s)
            ?: s?.displayName
            ?: metadata?.optString("name", "")?.takeIf { it.isNotEmpty() && metadata?.isNull("name") == false }
            ?: statisticId
        iconView.setIcon(MdiIcons.resolveIconName(config.icon, s), ctx.theme.stateIconColor)
        valueView.text = when (val v = value) {
            null -> ""
            // `formatNumber(value, locale, getNumberFormatOptions(undefined, entity))`: the user's number format and
            // the entity's display precision.
            else -> if (v.isNaN()) "?" else dev.glassworks.engine.vitro.data.HaFormat.formatNumber(
                v, options = HaDisplay.displayPrecision(statisticId)?.let { dev.glassworks.engine.vitro.data.HaFormat.NumberOptions(it, it) },
            )
        }
        val unit = raw.optString("unit", "").ifEmpty { null }
            ?: (s?.attributes?.get("unit_of_measurement") as? String)
            ?: metadata?.optString("statistics_unit_of_measurement", "")?.takeIf { metadata?.isNull("statistics_unit_of_measurement") == false }
        unitView.text = unit.orEmpty()
        unitView.visibility = if (unit.isNullOrEmpty()) GONE else VISIBLE
        contentDescription = nameView.text
    }

    private fun scheduleRefetch() {
        ctx.main.removeCallbacks(refetch)
        if (running) ctx.main.postDelayed(refetch, 5 * 60_000L)
    }

    /** Attached before: a later attach (a layout that moved the card) fetches again, as HA does on reconnect. */
    private var attachedBefore = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        if (attachedBefore) fetch()
        attachedBefore = true
        scheduleRefetch()
    }

    override fun onDetachedFromWindow() {
        running = false
        ctx.main.removeCallbacks(refetch)
        super.onDetachedFromWindow()
    }
}

// ---------------------------------------------------------------------------------------------
// plant-status
// ---------------------------------------------------------------------------------------------

/**
 * HA's `plant-status` card (`hui-plant-status-card`): the plant's name over its readings (moisture, temperature,
 * brightness, conductivity, battery: the ones its attributes have), each an icon over the value and its unit. The
 * value is the sensor's state when the plant names one, else the attribute; a reading in the plant's `problem` is in
 * `--error-color`, bold. With a picture the name sits on it, in white over a dark band. Each reading opens its
 * sensor's more-info.
 */
class PlantStatusCardView(ctx: VitroCardContext, private val config: CardConfig) : CardSurface(ctx) {
    private val entityId = config.entity.orEmpty()
    private val root = LinearLayout(ctx.context).apply { orientation = LinearLayout.VERTICAL }
    private var builtFor: String? = null
    private val values = ArrayList<Triple<String, MdiIconView, TextView>>()

    /** The sensors followed now, with their handles: dropped on detach, made again for the current ones on attach. */
    private val sensorHandles = HashMap<String, () -> Unit>()

    /**
     * HA's hui-warning, drawn in place of the whole card while its plant is missing. Declared before [init]: the first
     * [render] runs inside it, and a later initializer would forget the warning it added.
     */
    private var missingWarning: View? = null

    init {
        addView(root, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        observe(entityId, ::render)
    }

    /** A rebuild key: what decides the card's layout (readings, picture, name, problems), not the values. */
    private fun key(s: EntityState?): String = if (s == null) "missing" else listOf(
        ATTRIBUTES.filter { it in s.attributes }.joinToString(","), s.entityPicture.orEmpty(), config.name(s) ?: s.displayName,
        s.attributes["problem"]?.toString().orEmpty(), s.attributes["unit_of_measurement_dict"]?.toString().orEmpty(),
    ).joinToString("|")

    /**
     * Follows every sensor the plant names now (one it swaps in included), keeping the handles it has; a sensor it
     * no longer names is dropped. The entity subscription names only the plant (its config does), so each sensor joins
     * it too ([VitroCardContext.watchEntity]), as HA's card reads them from `hass.states`.
     */
    private fun followSensors(s: EntityState) {
        val ids = (s.attributes["sensors"] as? Map<*, *>)?.values?.mapNotNull { it as? String }?.toSet().orEmpty()
        (sensorHandles.keys - ids).forEach { sensorHandles.remove(it)?.invoke() }
        for (id in ids) {
            if (id in sensorHandles) continue
            sensorHandles[id] = ctx.store.subscribe(id) { _ -> ctx.main.post { ctx.store[entityId]?.let(::fillValues) } }
            ctx.watchEntity(id)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Back from a move: follow the plant's sensors again, and show what they read meanwhile.
        ctx.store[entityId]?.let { followSensors(it); fillValues(it) }
    }

    override fun onDetachedFromWindow() {
        sensorHandles.values.forEach { it() }
        sensorHandles.clear()
        super.onDetachedFromWindow()
    }

    override fun measureCard(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Over the card's padding (its border, a card_mod padding set since): the warning is in place of all of it.
        (missingWarning?.layoutParams as? MarginLayoutParams)?.let { lp ->
            if (lp.leftMargin != -paddingLeft || lp.topMargin != -paddingTop || lp.rightMargin != -paddingRight || lp.bottomMargin != -paddingBottom) {
                lp.setMargins(-paddingLeft, -paddingTop, -paddingRight, -paddingBottom)
            }
        }
        super.measureCard(widthMeasureSpec, heightMeasureSpec)
    }

    private fun render(s: EntityState?) {
        val k = key(s)
        if (k == builtFor) { if (s != null) { followSensors(s); fillValues(s) }; return }
        builtFor = k
        root.removeAllViews()
        values.clear()
        missingWarning?.let { removeView(it) }
        missingWarning = null
        clipToPadding = s != null
        if (s == null) {
            // HA's hui-warning in place of the card: "Entity not found" for an administrator, the icon for anyone else.
            // It covers the card's border too (its margins undo the card's padding): hui-error-card has none.
            val warning = errorCardContent(ctx, HaErrorSeverity.WARNING, HaText.ui("ui.card.common.entity_not_found"),
                over = ctx.theme.cardBackgroundColor)
            addView(warning, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                setMargins(-paddingLeft, -paddingTop, -paddingRight, -paddingBottom)
            })
            missingWarning = warning
            return
        }
        val hasPicture = s.entityPicture != null
        val banner = FrameLayout(ctx.context)
        val header = smallText(ctx, if (hasPicture) 16f else 24f, if (hasPicture) Color.WHITE else ctx.theme.primaryTextColor,
            if (hasPicture) Inter.MEDIUM else Inter.REGULAR).apply {
            text = config.name(s) ?: s.displayName
            gravity = Gravity.CENTER_VERTICAL
            if (hasPicture) {
                val p = ctx.px(16f)
                setPadding(p, p, p, p)
                setBackgroundColor(Color.argb((0.54f * 255).roundToInt(), 0, 0, 0))
            } else {
                setPadding(ctx.px(16f), ctx.px(8f), ctx.px(16f), ctx.px(8f))
            }
        }
        if (hasPicture) {
            // `.has-plant-image .banner { padding-top: 30% }`: a picture band 30% of the card's width tall, then the name.
            val band = object : FrameLayout(ctx.context) {
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    val w = MeasureSpec.getSize(widthMeasureSpec)
                    header.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                    val h = (w * 0.3f).roundToInt() + header.measuredHeight
                    super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
                }
            }
            // A new picture view per build: the last one stays in the band that was just dropped.
            val picture = android.widget.ImageView(ctx.context).apply { scaleType = android.widget.ImageView.ScaleType.CENTER_CROP }
            band.addView(picture, LayoutParams(MATCH_PARENT, MATCH_PARENT))
            band.addView(header, LayoutParams(MATCH_PARENT, (16f * ctx.dp).roundToInt() + ctx.px(32f), Gravity.BOTTOM))
            banner.addView(band, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            // A CSS background in HA's card: fetched with no bearer, as the browser fetches it.
            ctx.loadImage(s.entityPicture!!, owner = picture, public = true) { bmp -> if (bmp != null) picture.setImageBitmap(bmp) }
        } else {
            banner.setPadding(0, ctx.px(12f), 0, 0)
            banner.addView(header, LayoutParams(MATCH_PARENT, lineBoxHeight(ctx, 24f, 1.6f) + ctx.px(16f)))
        }
        root.addView(banner, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val content = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(ctx.px(32f), ctx.px(16f), ctx.px(32f), ctx.px(if (hasPicture) 16f else 24f))
        }
        val shown = ATTRIBUTES.filter { it in s.attributes }
        val problems = s.attributes["problem"]?.toString().orEmpty()
        shown.forEachIndexed { i, item ->
            val cell = LinearLayout(ctx.context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                isClickable = true
                applyHaRipple(this, 999f, ctx.theme.dark)
                VitroAccess.role(this, A11yRole.BUTTON)
                setOnClickListener {
                    val sensor = sensorOf(ctx.store[entityId], item)
                    if (sensor != null) ctx.openMoreInfo(sensor)
                }
            }
            val icon = MdiIconView(ctx.context)
            cell.addView(icon, LinearLayout.LayoutParams(ctx.px(24f), ctx.px(24f)).apply { bottomMargin = ctx.px(8f) })
            val problem = problems.contains(item)
            val value = smallText(ctx, 14f, if (problem) errorColor() else ctx.theme.primaryTextColor, if (problem) 700 else Inter.REGULAR).apply {
                gravity = Gravity.CENTER
            }
            cell.addView(value, LinearLayout.LayoutParams(WRAP_CONTENT, lineBoxHeight(ctx, 14f, 1.6f)))
            val uom = smallText(ctx, 14f, ctx.theme.secondaryTextColor).apply {
                gravity = Gravity.CENTER
                text = ((s.attributes["unit_of_measurement_dict"] as? Map<*, *>)?.get(item) as? String).orEmpty()
            }
            cell.addView(uom, LinearLayout.LayoutParams(WRAP_CONTENT, lineBoxHeight(ctx, 14f, 1.6f)))
            values += Triple(item, icon, value)
            // `justify-content: space-between`.
            if (i > 0) content.addView(View(ctx.context), LinearLayout.LayoutParams(0, 0, 1f))
            content.addView(cell, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
        root.addView(content, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        fillValues(s)
        // The sensors' states feed the values.
        followSensors(s)
    }

    private fun errorColor(): Int = ThemeResolver.parseColor(ctx.theme.vars["error-color"]) ?: 0xFFDB4437.toInt()

    private fun sensorOf(s: EntityState?, item: String): String? = (s?.attributes?.get("sensors") as? Map<*, *>)?.get(item) as? String

    private fun fillValues(s: EntityState) {
        for ((item, icon, value) in values) {
            if (item == "battery") {
                val level = s.attributes["battery"]
                val color = level?.toString()?.toDoubleOrNull()?.let { v ->
                    HaStateColor.property(ctx, when { v >= 70 -> "state-sensor-battery-high-color"; v >= 30 -> "state-sensor-battery-medium-color"; else -> "state-sensor-battery-low-color" })
                }
                icon.setIcon(batteryLevelIcon(level), color ?: ctx.theme.stateIconColor)
            } else {
                icon.setIcon(ICONS.getValue(item), ctx.theme.stateIconColor)
            }
            val sensor = sensorOf(s, item)?.let { ctx.store[it] }
            value.text = if (sensor != null) EntityCardView.valueAndUnit(JSONObject(), sensor).first
                else s.attributes[item]?.let { HaDisplayValue.plain(it) }.orEmpty()
        }
    }

    companion object {
        private val ATTRIBUTES = listOf("moisture", "temperature", "brightness", "conductivity", "battery")
        private val ICONS = mapOf(
            "moisture" to "mdi:water-percent", "temperature" to "mdi:thermometer",
            "brightness" to "mdi:white-balance-sunny", "conductivity" to "mdi:sprout",
        )

        /** HA's `batteryLevelIcon`. */
        fun batteryLevelIcon(level: Any?): String {
            val v = level?.toString()?.toDoubleOrNull()
            if (v == null) return when (level?.toString()) { "off" -> "mdi:battery"; "on" -> "mdi:battery-alert"; else -> "mdi:battery-unknown" }
            if (v <= 5) return "mdi:battery-alert-variant-outline"
            val round = (Math.round(v / 10) * 10).toInt()
            return if (round >= 100) "mdi:battery" else "mdi:battery-$round"
        }
    }
}

/** An attribute as JavaScript writes it into a template (`${value}`): a whole number without `.0`. */
internal object HaDisplayValue {
    fun plain(v: Any): String = when (v) {
        is Double -> if (v % 1.0 == 0.0 && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()
        is Float -> plain(v.toDouble())
        else -> v.toString()
    }
}
