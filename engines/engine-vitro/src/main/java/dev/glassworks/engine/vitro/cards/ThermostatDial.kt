/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.cards

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import dev.glassworks.engine.vitro.data.HaFormat
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.HaDisplay
import dev.glassworks.engine.vitro.data.HaText
import dev.glassworks.engine.vitro.ui.CardSurface
import dev.glassworks.engine.vitro.ui.KeyedText
import dev.glassworks.engine.vitro.ui.MdiIcons
import dev.glassworks.engine.vitro.ui.NumberText
import dev.glassworks.engine.vitro.ui.VitroAccess
import dev.glassworks.engine.vitro.ui.VitroCardContext
import dev.glassworks.engine.vitro.ui.VitroFonts
import dev.glassworks.platform.Platform
import dev.glassworks.ui.Inter
import org.json.JSONObject
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** HA's size classes of a state control (`xs` < 130 px, `sm` < 190, `md` < 250, `lg`). */
internal enum class DialSize {
    XS, SM, MD, LG;

    companion object {
        fun of(widthCss: Float): DialSize = when {
            widthCss < 130f -> XS
            widthCss < 190f -> SM
            widthCss < 250f -> MD
            else -> LG
        }
    }
}

/** `ha-control-circular-slider`'s modes: from the start, to the end, or the whole ring. */
internal enum class SliderMode { START, END, FULL }

/** One end of a heat/cool range: HA's `low` (`target_temp_low`) and `high` (`target_temp_high`) targets. */
internal enum class ClimateHandle { LOW, HIGH }

/**
 * A climate entity's targets the way HA's controls read them (`ha-state-control-climate-temperature`,
 * `hui-target-temperature-card-feature`, Mushroom's `climate-temperature-control`): one `temperature`, or, when there
 * is none and the entity supports `TARGET_TEMPERATURE_RANGE`, the `target_temp_low` / `target_temp_high` range that
 * `heat_cool` and `auto` use.
 */
internal data class ClimateTargets(val value: Double?, val low: Double?, val high: Double?) {
    /** HA's dual control: no single target, both ends of the range. */
    val isRange: Boolean get() = value == null && low != null && high != null

    companion object {
        /** `ClimateEntityFeature.TARGET_TEMPERATURE_RANGE`. */
        private const val TARGET_TEMPERATURE_RANGE = 2

        /** HA's names for the targets (`state_attributes.*.name`), which it gives the sliders and buttons. */
        const val LOW_NAME = "Lower target temperature"
        const val HIGH_NAME = "Upper target temperature"
        const val VALUE_NAME = "Target temperature"

        fun of(attrs: Map<String, Any?>): ClimateTargets {
            // HA's supportsFeature: the range needs TARGET_TEMPERATURE_RANGE in the bitmask (none means none).
            val features = (attrs["supported_features"] as? Number)?.toInt()
            val range = features != null && features and TARGET_TEMPERATURE_RANGE != 0
            fun num(key: String) = (attrs[key] as? Number)?.toDouble()
            return ClimateTargets(
                num("temperature"),
                num("target_temp_low")?.takeIf { range },
                num("target_temp_high")?.takeIf { range },
            )
        }

        /** `target_temp_step`, else HA's default: 1 in °F, 0.5 otherwise. */
        fun stepOf(attrs: Map<String, Any?>, unit: String): Double =
            (attrs["target_temp_step"] as? Number)?.toDouble()?.takeIf { it > 0 } ?: if ("F" in unit) 1.0 else 0.5

        /**
         * HA's range rule (`ha-control-circular-slider._boundedValue`, `_handleButton`): each end stays within the
         * device's [min] and [max], and the low end never passes the high one (they may meet, as HA's climate service
         * allows).
         */
        fun bound(handle: ClimateHandle, v: Double, low: Double, high: Double, min: Double, max: Double): Double {
            val lo = if (handle == ClimateHandle.HIGH) maxOf(min, low) else min
            val hi = if (handle == ClimateHandle.LOW) minOf(max, high) else max
            return maxOf(lo, minOf(hi, v))
        }

        /** [handle] one step of [delta] from where it is: HA's `_handleButton`, then [bound]. */
        fun stepped(handle: ClimateHandle, delta: Double, low: Double, high: Double, min: Double, max: Double): Pair<Double, Double> {
            val from = if (handle == ClimateHandle.LOW) low else high
            val next = bound(handle, roundToStep(from + delta, delta), low, high, min, max)
            return if (handle == ClimateHandle.LOW) next to high else low to next
        }

        /**
         * The range to send for an edit of [handle] that proposes [low] / [high]: the edited end, bounded against the
         * other end as the entity reports it now ([s], newer than what a control last showed), so a change HA made to
         * the other end meanwhile isn't overwritten. Null, so nothing is sent, when [s] no longer has a range (the mode
         * changed) or there's no state at all (the entity went).
         */
        fun rangeToSend(handle: ClimateHandle, low: Double, high: Double, s: EntityState?): Pair<Double, Double>? {
            val attrs = s?.attributes ?: return null
            val now = of(attrs)
            val nl = now.low
            val nh = now.high
            if (!now.isRange || nl == null || nh == null) return null
            val min = (attrs["min_temp"] as? Number)?.toDouble() ?: Double.NEGATIVE_INFINITY
            val max = (attrs["max_temp"] as? Number)?.toDouble() ?: Double.POSITIVE_INFINITY
            // The untouched end as reported, brought within the device's limits (an integration can report one
            // outside them); the edited end is then bounded by it, so both ends are in range and in order.
            return if (handle == ClimateHandle.LOW) {
                val peer = maxOf(min, minOf(max, nh))
                bound(ClimateHandle.LOW, low, nl, peer, min, max) to peer
            } else {
                val peer = maxOf(min, minOf(max, nl))
                peer to bound(ClimateHandle.HIGH, high, peer, nh, min, max)
            }
        }

        /** `climate.set_temperature` for a range, as HA sends it: both ends, always. */
        fun rangeData(entityId: String, low: Double, high: Double): JSONObject =
            JSONObject().put("entity_id", entityId).put("target_temp_low", low).put("target_temp_high", high)

        /** What the entity looks like once HA takes the range: the optimistic write's state. */
        fun withRange(s: EntityState?, low: Double, high: Double): EntityState? =
            s?.copy(attributes = s.attributes + ("target_temp_low" to low) + ("target_temp_high" to high))

        /** What the entity looks like once HA takes a single target. */
        fun withValue(s: EntityState?, value: Double): EntityState? =
            s?.copy(attributes = s.attributes + ("temperature" to value))

        /** The decimals [step] has (`0.125` → 3, `0.5` → 1, `1` → 0): HA's `step.toString().split(".")[1].length`. */
        fun decimalsOf(step: Double): Int =
            runCatching { java.math.BigDecimal(kotlin.math.abs(step).toString()).stripTrailingZeros().scale().coerceAtLeast(0) }.getOrDefault(1)

        /** [v] rounded to [step]'s decimals, so float sums (20 + 0.1 + 0.1) don't send 20.200000000000003. */
        fun roundToStep(v: Double, step: Double): Double =
            java.math.BigDecimal(v).setScale(decimalsOf(step), java.math.RoundingMode.HALF_UP).toDouble()
    }
}

/**
 * The thermostat's 270° dial (`ha-control-circular-slider` as `ha-state-control-climate-temperature` drives it).
 *
 * With one target: a 14 dp track from 135°, a soft fill up to the lower of current and target, a solid bridge between
 * them with a dot at the current temperature and a white thumb at the target. Dragging along the ring moves the
 * target in `target_temp_step` steps and commits it on release.
 *
 * With a heat/cool range (`bind` with `low` and `high` and no target): two thumbs, the low one in the heat color and the
 * high one in the cool color, each with HA's half-strength arc to its end of the ring and a solid one to the current
 * temperature when the room is past it. A finger moves the nearer thumb, never past the other one; the two targets
 * in the centre pick which one the − and + buttons move ([selected]). Off, the arcs go and the thumbs turn grey.
 *
 * With [showCurrentInDial] off (the More-Info sheet, which prints the current temperature above the dial) the
 * thermometer row in the centre is left out.
 *
 * TalkBack reads it as HA's page does: one slider for a single target, or two (lower and upper target temperature) for
 * a range, each with its value and the scroll actions; keys move the selected target (arrows one step, page keys a
 * tenth, Home and End to the ends) and commit on release, and Enter switches between the two ends of a range.
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
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private fun textPaint(size: Float, weight: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, size, ctx.context.resources.displayMetrics)
        typeface = VitroFonts.typeface(ctx.context, weight, size)
    }
    // The info texts: sized for the dial's size class in [sizeTexts].
    private val labelPaint = textPaint(16f, Inter.MEDIUM).apply { textAlign = Paint.Align.CENTER }
    private val bigPaint = textPaint(57f, Inter.REGULAR)
    private val unitPaint = textPaint(57f * 0.33f, Inter.REGULAR)
    private val decPaint = textPaint(57f * 0.42f, Inter.REGULAR)
    private val curPaint = textPaint(16f, Inter.MEDIUM)
    private val offPaint = textPaint(36f, Inter.REGULAR).apply { textAlign = Paint.Align.CENTER }
    private val offCurPaint = textPaint(16f, Inter.MEDIUM)
    private var sizedFor: DialSize? = null

    /** CSS px (`sp`, so the font scale applies as HA's `--ha-font-size-scale` does) to device px. */
    private fun cssText(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, ctx.context.resources.displayMetrics)

    // What onDraw reuses instead of building: formatted texts (rebuilt only when their value changes), the font
    // metrics, the thermometer glyph and one point on the ring.
    private val offCurMemo = NumberText()
    private val curMemo = NumberText()
    private val labelMemo = KeyedText()
    private var splitValue = Double.NaN
    private var splitStep = Double.NaN
    private var splitWhole = ""
    private var splitDec = ""
    private val lowWhole = NumberText()
    private val lowDec = NumberText()
    private val highWhole = NumberText()
    private val highDec = NumberText()
    private val fmBuf = Paint.FontMetrics()
    private val thermometer: Path by lazy { MdiIcons.pathFor(if (humidifier) "mdi:water-percent" else "mdi:thermometer") }
    private val pt = FloatArray(2)

    /** The thermometer and the current temperature, centered on [centerY]. */
    private fun drawCurrent(canvas: Canvas, cur: Double, color: Int, paint: Paint, iconSize: Float, centerY: Float) {
        val curText = offCurText(cur)
        paint.color = color
        val textW = paint.measureText(curText)
        val gap = 4f * dp
        val left = cx - (iconSize + gap + textW) / 2f
        iconPath.set(thermometer)
        iconMatrix.setScale(iconSize / 24f, iconSize / 24f)
        iconMatrix.postTranslate(left, centerY - iconSize / 2f)
        iconPath.transform(iconMatrix)
        fill.color = color
        canvas.drawPath(iconPath, fill)
        paint.textAlign = Paint.Align.LEFT
        val fm = fmBuf.also { paint.getFontMetrics(it) }
        canvas.drawText(curText, left + iconSize + gap, centerY - (fm.ascent + fm.descent) / 2f, paint)
    }
    private val iconMatrix = Matrix()
    private val iconPath = Path()
    private val oval = RectF()

    // The texts the dial draws, built once per value (onDraw and the description share them).
    private fun offCurText(cur: Double) =
        offCurMemo.of(cur) { v -> HaDisplay.formatFixed(v, 1) + " " + unit }
    private fun curText(cur: Double) = curMemo.of(cur) { v ->
        // A water heater's line: "Currently" and the temperature as HA formats the attribute.
        if (humidifier) HaFormat.formatNumberWithUnit(v, unit)
        else if (waterHeater) HaText.ui("ui.card.water_heater.currently") + " " + HaFormat.formatNumberWithUnit(v, unit)
        else HaDisplay.formatFixed(v, 1) + " " + unit
    }

    /**
     * A water heater's dial (`ha-state-control-water_heater-temperature`): the slider fills from the start in the
     * state's color, the label says "Target", the current temperature is "Currently …" with no icon, and with no target
     * the state is the label, not a big value.
     */
    var waterHeater = false

    /**
     * A humidifier's dial (`ha-state-control-humidifier-humidity`): the target in whole percents with the % under its
     * side, the current humidity after a water-percent icon, blue while on, the arc from the start (to the end for
     * a dehumidifier).
     */
    var humidifier = false

    /** A dehumidifier (`device_class: dehumidifier`): HA's slider fills to the end. */
    var dehumidifier = false

    /**
     * HA's label over the target: the HVAC action while it runs (anything but `off`), else the state as HA names it
     * ("Heat/Cool"), else the mode.
     */
    private fun labelText() = labelMemo.of(action, mode) {
        val a = action
        when {
            waterHeater -> if (target != null) HaText.ui("ui.card.water_heater.target") else stateLabel
            // The humidifier's label: the action while there is one (off aside), else the state beside a target.
            humidifier -> when {
                mode == "unavailable" -> stateLabel
                a != null && a != "off" -> humanize(a)
                target != null -> stateLabel
                else -> ""
            }
            a != null && a != "off" -> humanize(a)
            stateLabel.isNotEmpty() -> stateLabel
            else -> humanize(a ?: mode)
        }
    }

    private fun humanize(s: String) = s.replace('_', ' ').replaceFirstChar { it.titlecase(Locale.ROOT) }

    /** A target as the dial prints it, `21.5 °C` (a humidifier's `45%`). */
    private fun targetText(v: Double): String {
        val (whole, dec) = shownParts(v)
        return if (humidifier) "$whole$dec$unit" else "$whole$dec $unit"
    }

    /** The step the big number is formatted to: a humidifier's target is whole percents (`maximumFractionDigits: 0`). */
    private val shownStep: Double get() = if (humidifier) 1.0 else step

    /**
     * The big number's parts: a humidifier's target rounded to a whole percent (HA's `maximumFractionDigits: 0`), a
     * 45.5 % target reads 46 % while the control keeps 45.5; a temperature at the step's decimals ([splitTemp]).
     */
    private fun shownParts(v: Double): Pair<String, String> =
        if (humidifier) HaDisplay.formatFixed(v, 0) to "" else splitTemp(v, step)

    /**
     * The texts the dial draws, in order, as its content description: what HA's page has as text (the state or the
     * action, the target or the two ends of the range, the current temperature), so TalkBack reads them and the
     * parity harness finds them.
     */
    private fun describe() {
        val cur = current
        val t = target
        val parts = ArrayList<String>(4)
        val lo = low
        val hi = high
        if (t == null && lo != null && hi != null) {
            parts += labelText()
            parts += targetText(lo)
            parts += targetText(hi)
            if (cur != null && showCurrentInDial) parts += curText(cur)
        } else if (t == null) {
            if (stateLabel.isNotEmpty()) parts += stateLabel
            if (cur != null && showCurrentInDial) parts += offCurText(cur)
        } else {
            parts += labelText()
            parts += targetText(t)
            if (cur != null && showCurrentInDial) parts += curText(cur)
        }
        val text = parts.joinToString(", ")
        if (contentDescription?.toString() != text) contentDescription = text
    }

    private var min = 7.0
    private var max = 35.0
    private var step = 0.5
    private var current: Double? = null
    private var target: Double? = null

    /** The ends of a heat/cool range, as shown (a drag or a key moves them before HA hears about it). */
    private var low: Double? = null
    private var high: Double? = null

    /** The range Home Assistant last reported, kept during a drag so a cancelled one goes back to it. */
    private var boundLow: Double? = null
    private var boundHigh: Double? = null

    /** The target as drawn on the ring; eases towards [target] when it changes from a nudge or from HA. */
    private var shownTarget: Double? = null
    private var targetAnimator: ValueAnimator? = null
    private var mode = "off"
    private var hvacModes: List<String> = emptyList()
    private var action: String? = null
    private var dragging = false
    private var glowKey = 0
    private var cx = 0f
    private var cy = 0f
    private var radius = 0f

    /** The thumb a finger or a key is moving now (HA's `_activeSlider`). */
    private var activeHandle: ClimateHandle? = null

    /** The range target a press on the centre started on: it selects that end when the press ends on it. */
    private var pressedNumber: ClimateHandle? = null

    /** Where the two range targets were drawn, for a press on them. */
    private val lowRect = RectF()
    private val highRect = RectF()

    /** A key moved a target: its release commits it, as HA's `keyup` does. */
    private var keyPending = false

    /** The key whose release ends the edit, and the end of a range it moves. */
    private var keyCodePending = 0
    private var keyHandle = ClimateHandle.LOW

    /** The value the last key asked for, unclipped by the other end as shown (End on the low end: the maximum). */
    private var keyProposal = 0.0

    /** The virtual node (a range end) TalkBack's focus is on, or [NO_NODE]. */
    private var a11yFocused = NO_NODE

    /**
     * The end of a range the − and + buttons and the keys move: HA's `_selectTargetTemperature`. The low end first;
     * a press on a target in the centre, or dragging a thumb, selects it.
     */
    var selected = ClimateHandle.LOW
        private set

    var onCommit: (Double) -> Unit = {}

    /**
     * A range was set: the end that moved, and the low and high ends as the dial proposes them. The card finishes it
     * against the entity's current state ([ClimateTargets.rangeToSend]) and sends both ends together, as HA's
     * `set_temperature` takes them.
     */
    var onRangeCommit: (ClimateHandle, Double, Double) -> Unit = { _, _, _ -> }

    /** The selected end changed (the card colors its − and + after it). */
    var onSelect: (ClimateHandle) -> Unit = {}

    /** The target Home Assistant last reported, kept during a drag so a cancelled one goes back to it. */
    private var boundTarget: Double? = null

    /** The state as HA shows it big when there's no target ("Off"). */
    private var stateLabel = ""

    /** Whether the dial shows a heat/cool range: two thumbs and no single target. */
    val isRange: Boolean get() = target == null && low != null && high != null

    /** HA's `stateActive`: off (or unavailable) greys the range out and hides its arcs. */
    private val isActive: Boolean get() = mode != "off" && mode != "unavailable" && mode != "unknown"

    init {
        isFocusable = true
    }

    fun bind(
        min: Double,
        max: Double,
        step: Double,
        current: Double?,
        target: Double?,
        mode: String,
        action: String?,
        stateLabel: String = "",
        low: Double? = null,
        high: Double? = null,
        hvacModes: List<String> = emptyList(),
    ) {
        val wasRange = isRange
        this.hvacModes = hvacModes
        this.stateLabel = stateLabel
        this.min = min
        this.max = if (max > min) max else min + 1.0
        if (step != this.step) {
            lowWhole.reset(); lowDec.reset(); highWhole.reset(); highDec.reset()
        }
        this.step = step
        this.current = current
        boundTarget = target
        boundLow = low
        boundHigh = high
        // No target any more (turned off, say): a single target's key edit has nothing to set, so it's dropped now, not
        // sent by a release that comes after the target is back.
        if (keyPending && !wasRange && target == null) dropEdit()
        if (!dragging && !keyPending) {
            setTarget(target)
            this.low = low
            this.high = high
        }
        this.mode = mode
        this.action = action
        describe()
        if (wasRange != isRange) {
            // The virtual sliders went (or came): no stale accessibility focus or hover on them.
            a11yFocused = NO_NODE
            hovered = NO_NODE
            notifyA11y()
        }
        invalidate()
    }

    /**
     * One `target_temp_step` up ([sign] > 0) or down, committed straight away: the single target, or the selected end
     * of a range, which stops at the other end.
     */
    fun nudge(sign: Int) {
        if (isRange) {
            val (lo, hi) = ClimateTargets.stepped(selected, sign * step, low ?: return, high ?: return, min, max)
            if (lo == low && hi == high) return
            low = lo
            high = hi
            afterRangeMove()
            onRangeCommit(selected, lo, hi)
            return
        }
        val t = target ?: return
        // HA's _handleButton: the target plus a step, kept within the limits (not snapped to a grid).
        commitTarget(ClimateTargets.roundToStep(t + sign * step, step).coerceIn(min, max))
    }

    /** Sends a single target at once (a button, TalkBack); a key edit under way is replaced by it. False if unchanged. */
    private fun commitTarget(next: Double): Boolean {
        val t = target ?: return false
        if (next == t) return false
        keyPending = false
        keyCodePending = 0
        setTarget(next)
        describe()
        invalidate()
        onCommit(next)
        VitroAccess.valueChanged(this)
        return true
    }

    /** Picks the end of a range the − and + buttons move. */
    fun select(handle: ClimateHandle) {
        if (selected == handle) return
        selected = handle
        onSelect(handle)
        invalidate()
        if (isRange) sendVirtualEvent(nodeOf(handle), AccessibilityEvent.TYPE_VIEW_SELECTED)
    }

    private fun afterRangeMove() {
        describe()
        invalidate()
        sendVirtualEvent(nodeOf(selected), AccessibilityEvent.TYPE_VIEW_SELECTED)
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
        describe()
    }

    /** [handle] of the range moved to [value], kept on its side of the other end. */
    private fun moveHandle(handle: ClimateHandle, value: Double) {
        val lo = low ?: return
        val hi = high ?: return
        val v = ClimateTargets.bound(handle, value, lo, hi, min, max)
        if (handle == ClimateHandle.LOW) low = v else high = v
        describe()
    }

    override fun onDetachedFromWindow() {
        if (keyPending) dropEdit()
        targetAnimator?.cancel()
        targetAnimator = null
        shownTarget = target
        super.onDetachedFromWindow()
    }

    private fun snap(v: Double): Double {
        // The device's own ends stay reachable: a 7.3 minimum on a 0.5 step is 7.3 at the ring's start, not 7.5.
        if (v <= min) return min
        if (v >= max) return max
        // ha-control-circular-slider's _steppedValue: whole steps from 0, not from the minimum.
        val snapped = Math.round(v / step) * step
        // Rounded to the step's decimals, then kept within the device's limits (a 30.25 maximum stays 30.25).
        return ClimateTargets.roundToStep(snapped, step).coerceIn(min, max)
    }

    /**
     * HA's `stateColorCss` for a climate mode (`--state-climate-<mode>-color`): the slider's color for that mode, or
     * null for one HA gives no color (off).
     */
    private fun modeColor(m: String?): Int? = if (humidifier) {
        // `--state-humidifier-on-color`.
        if (m == "on") COOL_COLOR else null
    } else if (waterHeater) when (m) {
        // `--state-water_heater-<state>-color`.
        "eco" -> AUTO_MODE_COLOR
        "electric", "gas", "heat_pump" -> DRY_COLOR
        "high_demand", "performance" -> HEAT_COLOR
        else -> null
    } else when (m) {
        "heat" -> HEAT_COLOR
        "cool" -> COOL_COLOR
        "heat_cool" -> AUTO_COLOR
        "auto" -> AUTO_MODE_COLOR
        "dry" -> DRY_COLOR
        "fan_only" -> FAN_COLOR
        else -> null
    }

    /**
     * HA's `--action-color`: the running action's mode color (`CLIMATE_HVAC_ACTION_TO_MODE`) while the thermostat is
     * on and the action is neither idle nor off. It colors the label, the current temperature and the glow.
     */
    private fun actionColor(): Int? {
        if (!isActive) return null
        if (humidifier) return if (action == "humidifying" || action == "drying") modeColor("on") else null
        return modeColor(when (action) {
            "heating", "preheating", "defrosting" -> "heat"
            "cooling" -> "cool"
            "drying" -> "dry"
            "fan" -> "fan_only"
            else -> null
        })
    }

    /** A range end's color: heat for the low end, cool for the high one, grey while the thermostat is off. */
    fun handleColor(handle: ClimateHandle): Int = when {
        !isActive -> ctx.theme.secondaryTextColor
        handle == ClimateHandle.LOW -> HEAT_COLOR
        else -> COOL_COLOR
    }

    private fun angleOf(v: Double): Float = 135f + 270f * ((v - min) / (max - min)).toFloat().coerceIn(0f, 1f)

    /** HA's size class (`createStateControlCircularSliderController`), from the dial's width in CSS px. */
    private val sizeClass: DialSize get() = DialSize.of(minOf(width, height) / dp)


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

    /** One unit of the slider's 320-unit geometry, in px. */
    private val unit320: Float get() = minOf(width, height) / 320f

    /** `--disabled-color` at the slider background's 0.3 opacity: HA's track. */
    private fun trackColor(): Int = CardSurface.withAlpha(
        dev.glassworks.engine.vitro.theme.ThemeResolver.parseColor(ctx.theme.vars["disabled-color"])
            ?: if (ctx.theme.dark) 0xFF464646.toInt() else 0xFFBDBDBD.toInt(),
        0.3f,
    )

    /** `--clear-background-color`, under each half-strength arc. */
    private fun clearColor(): Int =
        dev.glassworks.engine.vitro.theme.ThemeResolver.parseColor(ctx.theme.vars["clear-background-color"])
            ?: if (ctx.theme.dark) 0xFF111111.toInt() else Color.WHITE

    /** The slider's mode (`SLIDER_MODES`): heat fills from the start, cool to the end, the rest the whole ring. */
    private fun sliderMode(): SliderMode {
        // The water heater's slider has no mode: ha-control-circular-slider fills from the start.
        if (waterHeater) return SliderMode.START
        if (humidifier) return if (dehumidifier) SliderMode.END else SliderMode.START
        val heatCool = hvacModes.filter { it == "heat" || it == "cool" || it == "heat_cool" }
        val m = if (heatCool.size == 1 && (mode == "off" || mode == "auto")) heatCool[0] else mode
        return when (m) {
            "heat" -> SliderMode.START
            "cool" -> SliderMode.END
            else -> SliderMode.FULL
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        oval.set(cx - radius, cy - radius, cx + radius, cy + radius)
        sizeTexts()
        val u = unit320
        arcPaint.strokeWidth = 24f * u
        arcPaint.color = trackColor()
        canvas.drawArc(oval, 135f, 270f, false, arcPaint)
        val cur = current
        // The current temperature on the track (`.current`): the text color at half strength, under the arcs.
        if (cur != null && cur != 0.0) {
            fill.color = CardSurface.withAlpha(ctx.theme.primaryTextColor, 0.5f)
            pointAt(angleOf(cur))
            canvas.drawCircle(pt[0], pt[1], 4f * u, fill)
        }
        val unavailable = mode == "unavailable"
        val actionTint = actionColor()

        if (isRange && !unavailable) {
            drawRange(canvas, actionTint)
            return
        }

        val tgt = shownTarget ?: target
        if (tgt != null && !unavailable) {
            val color = modeColor(mode)
            if (isActive && color != null) {
                val sm = sliderMode()
                val limit = if (sm == SliderMode.END) max else min
                val c = cur ?: limit
                when (sm) {
                    SliderMode.FULL -> halfArc(canvas, min, max, color)
                    SliderMode.END -> halfArc(canvas, tgt, max, color)
                    SliderMode.START -> halfArc(canvas, min, tgt, color)
                }
                val showActive = when (sm) {
                    SliderMode.END -> tgt <= c
                    SliderMode.START -> c <= tgt
                    SliderMode.FULL -> false
                }
                if (showActive) {
                    if (sm == SliderMode.END) arc(canvas, tgt, c, color) else arc(canvas, c, tgt, color)
                }
                if (cur != null && cur in min..max && (showActive || sm == SliderMode.FULL)) onArcMarker(canvas, cur)
            }
            drawThumb(canvas, tgt, color ?: ctx.theme.secondaryTextColor, isFocused)
        }
        drawGlow(canvas, actionTint)

        if (target == null || unavailable) {
            if (unavailable || stateLabel.isEmpty() || waterHeater) {
                // HA's third control with the entity unavailable (or a water heater with no target): the state as the
                // label (grey while unavailable), no big value.
                val labelColor = if (unavailable) ctx.theme.secondaryTextColor else ctx.theme.primaryTextColor
                drawInfo(canvas, stateLabel, labelColor, null, cur, ctx.theme.primaryTextColor)
                return
            }
            // ha-state-control-climate-temperature with no target: the state big (36 px) in the middle, the current
            // temperature (16 px / 500, primary color) 49 px under it, no label above.
            offPaint.color = ctx.theme.primaryTextColor
            val fm = fmBuf.also { offPaint.getFontMetrics(it) }
            canvas.drawText(stateLabel, cx, cy - (fm.ascent + fm.descent) / 2f - 1.6f * dp, offPaint)
            if (cur != null && showCurrentInDial) drawCurrent(canvas, cur, ctx.theme.primaryTextColor, offCurPaint, 16f * dp, cy + 48.8f * dp)
            return
        }
        val tint = actionTint ?: ctx.theme.primaryTextColor
        drawInfo(canvas, labelText(), tint, Primary.SINGLE, cur, tint)
    }

    /** HA's radial glow in the running action's color (the slider's `::after`, 120 % of it, at 0.15), over the arcs. */
    private fun drawGlow(canvas: Canvas, tint: Int?) {
        if (tint == null) return
        val r = 0.6f * minOf(width, height)
        if (glowKey != tint) {
            glowKey = tint
            glowPaint.shader = RadialGradient(cx, cy, r, CardSurface.withAlpha(tint, 0.15f), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        }
        canvas.drawCircle(cx, cy, r, glowPaint)
    }

    /** The current temperature on a colored arc (`.arc-current`): the clear background at half strength. */
    private fun onArcMarker(canvas: Canvas, cur: Double) {
        fill.color = CardSurface.withAlpha(clearColor(), 0.5f)
        pointAt(angleOf(cur))
        canvas.drawCircle(pt[0], pt[1], 4f * unit320, fill)
    }

    /** HA's half-strength arc (`.arc-clear` under `.arc-colored` at 0.5) from [from] to [to]. */
    private fun halfArc(canvas: Canvas, from: Double, to: Double, color: Int) {
        arc(canvas, from, to, clearColor())
        arc(canvas, from, to, CardSurface.withAlpha(color, 0.5f))
    }

    private enum class Primary { SINGLE, RANGE }

    /** Sets the texts' sizes for the dial's size class (`state-control-circular-slider-style`), once per class. */
    private fun sizeTexts() {
        val sc = sizeClass
        if (sizedFor == sc) return
        sizedFor = sc
        val big = when (sc) { DialSize.LG -> 57f; DialSize.MD -> 44f; else -> 32f }
        val info = if (sc == DialSize.SM) 14f else 16f
        bigPaint.textSize = cssText(big)
        decPaint.textSize = cssText(big * 0.42f)
        unitPaint.textSize = cssText(big * 0.33f)
        // ha-big-number's letter-spacing, -0.25 px; the info's, 0.1 px.
        for (pp in listOf(bigPaint, decPaint, unitPaint)) pp.letterSpacing = -0.25f / (pp.textSize / dp)
        labelPaint.textSize = cssText(info)
        curPaint.textSize = cssText(info)
        labelPaint.letterSpacing = 0.1f / info
        curPaint.letterSpacing = 0.1f / info
        lowWhole.reset(); lowDec.reset(); highWhole.reset(); highDec.reset()
    }

    /** The baseline of [p]'s text in a CSS line box of [lineH] px at [top]: Blink's half-leading. */
    private fun lineBaseline(top: Float, lineH: Float, p: Paint): Float {
        p.getFontMetrics(fmBuf)
        val a = -fmBuf.ascent
        val d = fmBuf.descent
        return top + (lineH - (a + d)) / 2f + a
    }

    /**
     * HA's `.info`: the label, the target (or the two ends of a range) and the current temperature, each a block, the
     * size class's gap between them, centered on the dial (`.container.md .info { margin-top: 12px }` and the slider's
     * `margin-bottom: -16px` below the large size). The label and the current-temperature line keep their
     * `min-height` when empty.
     */
    private fun drawInfo(canvas: Canvas, label: String, labelColor: Int, primary: Primary?, cur: Double?, curColor: Int) {
        val sc = sizeClass
        val size = minOf(width, height).toFloat()
        val infoLine = 1.6f * labelPaint.textSize
        val bigLine = 1.12f * bigPaint.textSize
        val gap = dp * when (sc) { DialSize.LG -> 8f; DialSize.MD -> 6f; DialSize.SM -> 2f; DialSize.XS -> 8f }
        val dialTop = cy - size / 2f
        val regionTop = dialTop + if (sc == DialSize.LG) 0f else 12f * dp
        val regionBottom = dialTop + if (sc == DialSize.LG) size else size - 16f * dp
        val showLabel = sc != DialSize.XS
        var total = infoLine + (if (primary != null) gap + bigLine else 0f)
        if (showLabel) total += infoLine + gap
        var y = (regionTop + regionBottom) / 2f - total / 2f
        if (showLabel) {
            labelPaint.color = labelColor
            if (label.isNotEmpty()) canvas.drawText(label, cx, lineBaseline(y, infoLine, labelPaint), labelPaint)
            y += infoLine + gap
        }
        when (primary) {
            Primary.SINGLE -> {
                val t = target
                if (t != null) {
                    if (t != splitValue || shownStep != splitStep) {
                        val parts = shownParts(t)
                        splitValue = t
                        splitStep = shownStep
                        splitWhole = parts.first
                        splitDec = parts.second
                    }
                    val w = numberWidth(splitWhole, splitDec)
                    drawNumber(canvas, splitWhole, splitDec, cx - w / 2f, y, 1f, lowRect)
                }
                y += bigLine + gap
            }
            Primary.RANGE -> {
                val lo = low
                val hi = high
                if (lo != null && hi != null) {
                    val between = dp * when (sc) { DialSize.LG -> 24f; DialSize.MD -> 16f; else -> 8f }
                    val lw = lowWhole.of(lo) { splitTemp(it, step).first }
                    val ld = lowDec.of(lo) { splitTemp(it, step).second }
                    val hw = highWhole.of(hi) { splitTemp(it, step).first }
                    val hd = highDec.of(hi) { splitTemp(it, step).second }
                    val lowW = numberWidth(lw, ld)
                    val highW = numberWidth(hw, hd)
                    val x0 = cx - (lowW + between + highW) / 2f
                    drawNumber(canvas, lw, ld, x0, y, if (selected == ClimateHandle.LOW) 1f else UNSELECTED_ALPHA, lowRect)
                    drawNumber(canvas, hw, hd, x0 + lowW + between, y, if (selected == ClimateHandle.HIGH) 1f else UNSELECTED_ALPHA, highRect)
                }
                y += bigLine + gap
            }
            null -> Unit
        }
        // A humidity of 0 % is a reading HA shows; a temperature of exactly 0 isn't drawn (as before).
        if (cur != null && (cur != 0.0 || humidifier) && showCurrentInDial) drawCurrentRow(canvas, cur, curColor, y, infoLine)
    }

    /**
     * The current temperature's line (`.label.secondary`): the thermometer at the info's icon size (16 px, 14 px when
     * small), the space the markup leaves, then the temperature, centered, in [color].
     */
    private fun drawCurrentRow(canvas: Canvas, cur: Double, color: Int, top: Float, lineH: Float) {
        val curText = curText(cur)
        curPaint.color = color
        curPaint.textAlign = Paint.Align.LEFT
        val iconSize = if (waterHeater) 0f else if (sizeClass == DialSize.SM) 14f * dp else 16f * dp
        val space = if (waterHeater) 0f else curPaint.measureText(" ")
        val textW = curPaint.measureText(curText)
        val left = cx - (iconSize + space + textW) / 2f
        val baseline = lineBaseline(top, lineH, curPaint)
        // ha-svg-icon sits on the line's middle, raised 5 % (`bottom: 5%`).
        if (iconSize > 0f) {
            val iconTop = top + (lineH - iconSize) / 2f - 0.05f * iconSize
            iconPath.set(thermometer)
            iconMatrix.setScale(iconSize / 24f, iconSize / 24f)
            iconMatrix.postTranslate(left, iconTop)
            iconPath.transform(iconMatrix)
            fill.color = color
            canvas.drawPath(iconPath, fill)
        }
        canvas.drawText(curText, left + iconSize + space, baseline, curPaint)
    }

    /**
     * A thumb at [value]: HA's `.target-border` (24 units, in [color]) under `.target` (18 units, white), ringed while it
     * has the keyboard's focus.
     */
    private fun drawThumb(canvas: Canvas, value: Double, color: Int, focused: Boolean) {
        pointAt(angleOf(value))
        val x = pt[0]
        val y = pt[1]
        val u = unit320
        if (focused) {
            // :focus-visible: HA widens the thumb's stroke; a ring in the text color reads the same on a panel.
            ring.color = ctx.theme.primaryTextColor
            ring.strokeWidth = 2f * dp
            canvas.drawCircle(x, y, 14f * u + dp, ring)
        }
        fill.color = color
        canvas.drawCircle(x, y, 12f * u, fill)
        fill.color = Color.WHITE
        canvas.drawCircle(x, y, 9f * u, fill)
    }

    /** The ring's arc from [from] to [to] in [color] (nothing when it's empty). */
    private fun arc(canvas: Canvas, from: Double, to: Double, color: Int) {
        val a = angleOf(from)
        val b = angleOf(to)
        if (b <= a) return
        arcPaint.color = color
        canvas.drawArc(oval, a, b - a, false, arcPaint)
    }

    /**
     * The dual slider (`ha-control-circular-slider dual`): the low end's arc from the ring's start ("start" mode) and the
     * high end's to its end ("end"), each at half strength, with a solid one to the current temperature while the room
     * is below the low target or above the high one (with no current temperature, the whole of each). Off, the arcs go
     * and the thumbs turn grey. In the centre, HA's label and the two targets, the one not selected at 70 %.
     */
    private fun drawRange(canvas: Canvas, actionTint: Int?) {
        val lo = low ?: return
        val hi = high ?: return
        val cur = current
        if (isActive) {
            halfArc(canvas, min, lo, HEAT_COLOR)
            val cLow = cur ?: min
            if (cLow <= lo) {
                arc(canvas, cLow, lo, HEAT_COLOR)
                if (cur != null && cur in min..max) onArcMarker(canvas, cur)
            }
            halfArc(canvas, hi, max, COOL_COLOR)
            val cHigh = cur ?: max
            if (hi <= cHigh) {
                arc(canvas, hi, cHigh, COOL_COLOR)
                if (cur != null && cur in min..max) onArcMarker(canvas, cur)
            }
        }
        drawThumb(canvas, lo, handleColor(ClimateHandle.LOW), isFocused && selected == ClimateHandle.LOW)
        drawThumb(canvas, hi, handleColor(ClimateHandle.HIGH), isFocused && selected == ClimateHandle.HIGH)
        drawGlow(canvas, actionTint)
        val tint = actionTint ?: ctx.theme.primaryTextColor
        drawInfo(canvas, labelText(), tint, Primary.RANGE, cur, tint)
    }

    /** One ha-big-number's width: the whole part, then the wider of the unit and the decimals, side by side. */
    private fun numberWidth(whole: String, dec: String): Float {
        val decW = if (dec.isEmpty()) 0f else decPaint.measureText(dec)
        // unit-position="bottom" (the humidifier): the decimals and the unit side by side, 2 px apart.
        return if (humidifier) bigPaint.measureText(whole) + decW + 2f * dp + unitPaint.measureText(unit)
        else bigPaint.measureText(whole) + maxOf(unitPaint.measureText(unit), decW)
    }

    /**
     * Draws one ha-big-number with its box's top at [top] and its left at [x], and records the box a press on it
     * selects into [hit]: the whole part on a 1.12 line, and beside it, bottom-aligned with 4 px of padding, the
     * decimals (0.42 em on a 1.33 line) under the unit (0.33 em on a 1.2 line).
     */
    private fun drawNumber(canvas: Canvas, whole: String, dec: String, x: Float, top: Float, alpha: Float, hit: RectF) {
        val color = CardSurface.withAlpha(ctx.theme.primaryTextColor, alpha)
        bigPaint.color = color
        unitPaint.color = color
        decPaint.color = color
        bigPaint.textAlign = Paint.Align.LEFT
        unitPaint.textAlign = Paint.Align.LEFT
        decPaint.textAlign = Paint.Align.LEFT
        val bigLine = 1.12f * bigPaint.textSize
        canvas.drawText(whole, x, lineBaseline(top, bigLine, bigPaint), bigPaint)
        val sideX = x + bigPaint.measureText(whole)
        val decLine = 1.33f * decPaint.textSize
        val decTop = top + bigLine - 4f * dp - decLine
        if (dec.isNotEmpty()) canvas.drawText(dec, sideX, lineBaseline(decTop, decLine, decPaint), decPaint)
        val unitLine = 1.2f * unitPaint.textSize
        if (humidifier) {
            // `.addon.bottom`: a baseline-aligned row; the empty decimals' box has its baseline at its bottom, and
            // the unit has 4 px under it, so the shared baseline sits the unit's descent and 4 px above the row's end.
            val unitAbove = lineBaseline(0f, unitLine, unitPaint)
            val baseline = top + bigLine - 4f * dp - (unitLine - unitAbove + 4f * dp)
            val unitX = sideX + (if (dec.isEmpty()) 0f else decPaint.measureText(dec)) + 2f * dp
            canvas.drawText(unit, unitX, baseline, unitPaint)
        } else {
            canvas.drawText(unit, sideX, lineBaseline(decTop - unitLine, unitLine, unitPaint), unitPaint)
        }
        hit.set(x - 4f * dp, top, x + numberWidth(whole, dec) + 4f * dp, top + bigLine)
    }

    /** The point on the ring at [angleDeg], into [pt]. */
    private fun pointAt(angleDeg: Float) {
        val rad = Math.toRadians(angleDeg.toDouble())
        pt[0] = (cx + radius * cos(rad)).toFloat()
        pt[1] = (cy + radius * sin(rad)).toFloat()
    }

    /** The value under a point on the ring, before snapping to a step. */
    private fun rawValueAt(x: Float, y: Float): Double {
        var deg = Math.toDegrees(atan2((y - cy).toDouble(), (x - cx).toDouble()))
        if (deg < 0) deg += 360.0
        var rel = (deg - 135.0 + 360.0) % 360.0
        // In the bottom opening, snap to the nearer end.
        if (rel > 270.0) rel = if (rel > 315.0) 0.0 else 270.0
        return min + rel / 270.0 * (max - min)
    }

    private fun valueAt(x: Float, y: Float): Double = snap(rawValueAt(x, y))

    /**
     * HA's `_findActiveSlider`: below the low end moves the low thumb, above the high end the high one, and between
     * them the nearer one (the low one on a tie).
     */
    private fun handleFor(raw: Double): ClimateHandle {
        val lo = maxOf(low ?: min, min)
        val hi = minOf(high ?: max, max)
        return when {
            lo >= raw -> ClimateHandle.LOW
            hi <= raw -> ClimateHandle.HIGH
            kotlin.math.abs(raw - lo) <= kotlin.math.abs(raw - hi) -> ClimateHandle.LOW
            else -> ClimateHandle.HIGH
        }
    }

    private fun onRing(x: Float, y: Float): Boolean {
        val d = hypot((x - cx).toDouble(), (y - cy).toDouble())
        return d >= radius - 32f * dp && d <= radius + 24f * dp
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isRange) return onRangeTouch(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (target == null) return false
                if (!onRing(event.x, event.y)) return false
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
                // HA reports a range now (the mode changed mid-drag): nothing is sent, its range shows.
                if (boundTarget == null) dropEdit() else target?.let(onCommit)
                // TalkBack reads the target the drag left, as it does a SeekBar's.
                VitroAccess.valueChanged(this)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                // Nothing was sent: back to everything Home Assistant reported last (a range, if it moved to one), and
                // TalkBack reads that, not the preview.
                if (dragging) dropEdit()
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** A range: a press on a target in the centre selects it; one on the ring drags the nearer thumb. */
    /**
     * Sends the end [handle] moved, with the other end as Home Assistant last reported it (a change it reported
     * meanwhile isn't overwritten), bounded so the two don't cross. If HA no longer reports a range (the mode changed),
     * the edit is dropped.
     */
    private fun commitRange(handle: ClimateHandle, proposed: Double) {
        val bl = boundLow
        val bh = boundHigh
        if (low == null || high == null || bl == null || bh == null || boundTarget != null) {
            dropEdit()
            return
        }
        // The proposal is bounded here, against the latest reported other end, not the one shown when it started.
        val (cl, ch) = if (handle == ClimateHandle.LOW) {
            ClimateTargets.bound(ClimateHandle.LOW, proposed, bl, bh, min, max) to bh
        } else {
            bl to ClimateTargets.bound(ClimateHandle.HIGH, proposed, bl, bh, min, max)
        }
        low = cl
        high = ch
        describe()
        invalidate()
        // The card bounds the proposal against the entity's state as the store holds it now, which may be newer
        // than this bind: it gets the edited end unbounded, so a raised other end doesn't clip it.
        if (cl != bl || ch != bh || proposed != (if (handle == ClimateHandle.LOW) cl else ch)) {
            if (handle == ClimateHandle.LOW) onRangeCommit(handle, proposed, bh) else onRangeCommit(handle, bl, proposed)
        }
    }

    /** An edit nothing was sent for: back to what Home Assistant reported last. */
    private fun dropEdit() {
        keyPending = false
        keyCodePending = 0
        setTarget(boundTarget)
        low = boundLow
        high = boundHigh
        describe()
        invalidate()
    }

    private fun onRangeTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedNumber = when {
                    lowRect.contains(event.x, event.y) -> ClimateHandle.LOW
                    highRect.contains(event.x, event.y) -> ClimateHandle.HIGH
                    else -> null
                }
                if (pressedNumber != null) return true
                if (!onRing(event.x, event.y)) return false
                val raw = rawValueAt(event.x, event.y)
                val handle = handleFor(raw)
                activeHandle = handle
                select(handle)
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                moveHandle(handle, snap(raw))
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (pressedNumber != null) return true
                val handle = activeHandle ?: return false
                moveHandle(handle, valueAt(event.x, event.y))
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                pressedNumber?.let { pressed ->
                    pressedNumber = null
                    val rect = if (pressed == ClimateHandle.LOW) lowRect else highRect
                    if (rect.contains(event.x, event.y)) {
                        select(pressed)
                        performClick()
                    }
                    return true
                }
                val handle = activeHandle ?: return false
                activeHandle = null
                parent?.requestDisallowInterceptTouchEvent(false)
                dragging = false
                commitRange(handle, valueAt(event.x, event.y))
                // TalkBack reads the end the drag left, as it does a SeekBar's.
                sendVirtualEvent(nodeOf(handle), AccessibilityEvent.TYPE_VIEW_SELECTED)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedNumber = null
                // Nothing was sent: back to the range Home Assistant has.
                if (activeHandle != null) dropEdit()
                activeHandle = null
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return false
    }

    // Keyboard: HA's slider keys (A11Y_KEY_CODES), moving the single target or the selected end of a range.

    /** The value a key moves the target to, or null for a key the dial doesn't take. */
    private fun keyValue(keyCode: Int, from: Double): Double? {
        val tenth = maxOf(step, (max - min) / 10.0)
        // Left and right, as on Android's SeekBar: up and down stay free for moving between controls.
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> from + step
            KeyEvent.KEYCODE_DPAD_LEFT -> from - step
            KeyEvent.KEYCODE_PAGE_UP -> from + tenth
            KeyEvent.KEYCODE_PAGE_DOWN -> from - tenth
            KeyEvent.KEYCODE_MOVE_HOME -> min
            KeyEvent.KEYCODE_MOVE_END -> max
            else -> null
        }
    }

    /** Where a key takes the value: Home and End to the device's exact limits, the others snapped to a step. */
    private fun keyTarget(keyCode: Int, v: Double): Double =
        if (keyCode == KeyEvent.KEYCODE_MOVE_HOME || keyCode == KeyEvent.KEYCODE_MOVE_END) v.coerceIn(min, max) else snap(v)

    private fun isConfirmKey(keyCode: Int) =
        keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isRange) {
            if (isConfirmKey(keyCode)) {
                // Enter moves the − and + (and the arrow keys) to the other end of the range.
                select(if (selected == ClimateHandle.LOW) ClimateHandle.HIGH else ClimateHandle.LOW)
                return true
            }
            // A key already moving one end keeps moving it, whichever end Enter selected since.
            val handle = if (keyPending) keyHandle else selected
            val from = (if (handle == ClimateHandle.LOW) low else high) ?: return super.onKeyDown(keyCode, event)
            val next = keyValue(keyCode, from) ?: return super.onKeyDown(keyCode, event)
            val proposal = keyTarget(keyCode, next)
            moveHandle(handle, proposal)
            // At an end (or against the other thumb) a new press moves focus on, as SeekBar's does; the held key of an
            // edit under way stays with it.
            if ((if (handle == ClimateHandle.LOW) low else high) == from) {
                if (!keyPending || keyCode != keyCodePending) return super.onKeyDown(keyCode, event)
                // A repeat of the held key still asks for more: the release bounds that against the other end HA
                // reports by then, which may have moved out of the way.
                keyProposal = proposal
                return true
            }
            keyPending = true
            keyHandle = handle
            keyCodePending = keyCode
            // What the key asked for, before the shown peer clipped it: the release bounds it against the latest one.
            keyProposal = proposal
            invalidate()
            sendVirtualEvent(nodeOf(handle), AccessibilityEvent.TYPE_VIEW_SELECTED)
            return true
        }
        val t = target ?: return super.onKeyDown(keyCode, event)
        val next = keyTarget(keyCode, keyValue(keyCode, t) ?: return super.onKeyDown(keyCode, event))
        if (next == t) return if (keyPending && keyCode == keyCodePending) true else super.onKeyDown(keyCode, event)
        dragTarget(next)
        keyPending = true
        keyCodePending = keyCode
        invalidate()
        VitroAccess.valueChanged(this)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        // Only the release of the key that made the edit ends it (not a Shift let go meanwhile).
        if (!keyPending || keyCode != keyCodePending) return super.onKeyUp(keyCode, event)
        // A cancelled release sends nothing.
        if (event.isCanceled) {
            dropEdit()
            return true
        }
        keyPending = false
        keyCodePending = 0
        if (low != null && high != null && target == null) {
            commitRange(keyHandle, keyProposal)
        } else if (boundTarget != null) {
            // Within the limits HA reports now: they may have narrowed while the key was held.
            // The preview settles on what's allowed now even when nothing is sent (61 % held against a 60 % maximum
            // that HA also reports as the target: the dial reads 60 %, and no write goes).
            target?.coerceIn(min, max)?.let { v ->
                if (v != target) {
                    setTarget(v)
                    describe()
                    invalidate()
                }
                if (v != boundTarget) onCommit(v)
            }
        } else {
            dropEdit()
        }
        return true
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        // Focus left before the key came up: the edit is dropped, HA's target shows again.
        if (!gainFocus && keyPending) dropEdit()
        invalidate()
    }

    // Accessibility: one slider for a single target; for a range, two virtual sliders (HA's two `role="slider"`
    // arcs, named after the targets) that also act as the low / high selection buttons (`aria-pressed`).

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        val t = target
        if (isRange || t == null) return
        info.className = SEEK_BAR
        info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, min.toFloat(), max.toFloat(), t.toFloat())
        if (t < max) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        if (t > min) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        // The humidity control is AdjustableAccess's slider, as it was before it moved here: a value can be set.
        if (humidifier) VitroAccess.offerSetProgress(info)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        val t = target
        if (!isRange && t != null) {
            when (action) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> {
                    val sign = if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) 1 else -1
                    // A thermostat moves a step, as its − and + do. The humidity slider moves HA's tenth of the range
                    // (`_tenPercentStep`, as Page Up and Down do), as AdjustableAccess's sliders do.
                    if (!humidifier) nudge(sign)
                    else commitTarget(snap(t + sign * maxOf(step, (max - min) / 10.0)))
                    return true
                }
                android.R.id.accessibilityActionSetProgress -> if (humidifier && Platform.hasSetProgressAction) {
                    val p = arguments?.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, Float.NaN) ?: Float.NaN
                    return !p.isNaN() && commitTarget(snap(p.toDouble()))
                }
            }
        }
        return super.performAccessibilityAction(action, arguments)
    }

    private val provider = object : AccessibilityNodeProvider() {
        override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfo? {
            if (virtualViewId == AccessibilityNodeProvider.HOST_VIEW_ID) {
                val info = AccessibilityNodeInfo.obtain(this@ThermostatDialView)
                onInitializeAccessibilityNodeInfo(info)
                if (isRange) {
                    info.addChild(this@ThermostatDialView, LOW_NODE)
                    info.addChild(this@ThermostatDialView, HIGH_NODE)
                }
                return info
            }
            val handle = handleOf(virtualViewId) ?: return null
            if (!isRange) return null
            val value = (if (handle == ClimateHandle.LOW) low else high) ?: return null
            val info = AccessibilityNodeInfo.obtain(this@ThermostatDialView, virtualViewId)
            info.setParent(this@ThermostatDialView)
            info.packageName = context.packageName
            info.className = SEEK_BAR
            info.contentDescription = describeHandle(handle, value)
            info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(
                AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, min.toFloat(), max.toFloat(), value.toFloat(),
            )
            info.isEnabled = true
            info.isFocusable = true
            info.isClickable = true
            info.isSelected = selected == handle
            info.isVisibleToUser = true
            info.isAccessibilityFocused = a11yFocused == virtualViewId
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
            val lo = low ?: min
            val hi = high ?: max
            val ceiling = if (handle == ClimateHandle.LOW) minOf(max, hi) else max
            val floor = if (handle == ClimateHandle.HIGH) maxOf(min, lo) else min
            if (value < ceiling) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
            if (value > floor) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
            info.addAction(
                if (a11yFocused == virtualViewId) AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS
                else AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS,
            )
            val box = thumbBounds(value)
            info.setBoundsInParent(box)
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            box.offset(loc[0], loc[1])
            info.setBoundsInScreen(box)
            return info
        }

        override fun performAction(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
            if (virtualViewId == AccessibilityNodeProvider.HOST_VIEW_ID) return this@ThermostatDialView.performAccessibilityAction(action, arguments)
            val handle = handleOf(virtualViewId) ?: return false
            if (!isRange) return false
            return when (action) {
                AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS -> {
                    if (a11yFocused == virtualViewId) return false
                    a11yFocused = virtualViewId
                    invalidate()
                    sendVirtualEvent(virtualViewId, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
                    true
                }
                AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> {
                    if (a11yFocused != virtualViewId) return false
                    a11yFocused = NO_NODE
                    invalidate()
                    sendVirtualEvent(virtualViewId, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)
                    true
                }
                AccessibilityNodeInfo.ACTION_CLICK -> {
                    select(handle)
                    sendVirtualEvent(virtualViewId, AccessibilityEvent.TYPE_VIEW_CLICKED)
                    true
                }
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> {
                    select(handle)
                    nudge(if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) 1 else -1)
                    true
                }
                else -> false
            }
        }

        override fun findFocus(focus: Int): AccessibilityNodeInfo? =
            if (focus == AccessibilityNodeInfo.FOCUS_ACCESSIBILITY && isRange && a11yFocused != NO_NODE) {
                createAccessibilityNodeInfo(a11yFocused)
            } else {
                null
            }
    }

    override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider? = if (isRange) provider else null

    /** The thumb under a finger exploring the dial, so TalkBack hears it entered and left. */
    private var hovered = NO_NODE

    /** Touch exploration over a range: the thumb under the finger is the node TalkBack reads. */
    override fun dispatchHoverEvent(event: MotionEvent): Boolean {
        if (!isRange || !a11yOn()) return super.dispatchHoverEvent(event)
        val lo = low ?: return super.dispatchHoverEvent(event)
        val hi = high ?: return super.dispatchHoverEvent(event)
        val lowBox = thumbBounds(lo)
        val highBox = thumbBounds(hi)
        val x = event.x.toInt()
        val y = event.y.toInt()
        val inLow = lowBox.contains(x, y)
        val inHigh = highBox.contains(x, y)
        val node = when {
            event.actionMasked == MotionEvent.ACTION_HOVER_EXIT -> NO_NODE
            // Close thumbs' boxes overlap: the finger is on the nearer one.
            inLow && inHigh -> if (hypot((event.x - lowBox.exactCenterX()).toDouble(), (event.y - lowBox.exactCenterY()).toDouble()) <=
                hypot((event.x - highBox.exactCenterX()).toDouble(), (event.y - highBox.exactCenterY()).toDouble())) LOW_NODE else HIGH_NODE
            inLow || lowRect.contains(event.x, event.y) -> LOW_NODE
            inHigh || highRect.contains(event.x, event.y) -> HIGH_NODE
            else -> NO_NODE
        }
        if (node != hovered) {
            // Left one thumb (exit), reached another (enter), as ExploreByTouchHelper sends them.
            if (hovered != NO_NODE) sendVirtualEvent(hovered, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT)
            hovered = node
            if (node != NO_NODE) sendVirtualEvent(node, AccessibilityEvent.TYPE_VIEW_HOVER_ENTER)
        }
        return if (node == NO_NODE) super.dispatchHoverEvent(event) else true
    }

    /** "Lower target temperature, 20.5 °C": HA's name for the target and its value. */
    private fun describeHandle(handle: ClimateHandle, value: Double): String =
        (if (handle == ClimateHandle.LOW) ClimateTargets.LOW_NAME else ClimateTargets.HIGH_NAME) + ", " + targetText(value)

    /** A thumb's touch box: 48 dp around it. */
    private fun thumbBounds(value: Double): Rect {
        pointAt(angleOf(value))
        val half = 24f * dp
        return Rect((pt[0] - half).toInt(), (pt[1] - half).toInt(), (pt[0] + half).toInt(), (pt[1] + half).toInt())
    }

    private fun handleOf(node: Int): ClimateHandle? = when (node) {
        LOW_NODE -> ClimateHandle.LOW
        HIGH_NODE -> ClimateHandle.HIGH
        else -> null
    }

    private fun nodeOf(handle: ClimateHandle) = if (handle == ClimateHandle.LOW) LOW_NODE else HIGH_NODE

    private fun a11yOn(): Boolean =
        (context.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager)?.isEnabled == true

    private fun sendVirtualEvent(node: Int, type: Int) {
        if (!a11yOn() || !isRange) return
        val handle = handleOf(node) ?: return
        val value = (if (handle == ClimateHandle.LOW) low else high) ?: return
        val event = AccessibilityEvent.obtain(type)
        event.packageName = context.packageName
        event.className = SEEK_BAR
        event.contentDescription = describeHandle(handle, value)
        event.isEnabled = true
        event.setSource(this, node)
        parent?.requestSendAccessibilityEvent(this, event)
    }

    /** The dial switched between one target and a range: TalkBack rereads its nodes. */
    private fun notifyA11y() {
        if (a11yOn()) sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    }

    companion object {
        /** HA's `--state-climate-*-color`s: heat (deep orange), cool (blue), heat/cool (amber), auto, dry, fan. */
        private const val AUTO_COLOR = 0xFFFFC107.toInt()
        private const val AUTO_MODE_COLOR = 0xFF4CAF50.toInt()
        private const val DRY_COLOR = 0xFFFF9800.toInt()
        private const val FAN_COLOR = 0xFF00BCD4.toInt()
        private const val HEAT_COLOR = 0xFFFF6F22.toInt()
        private const val COOL_COLOR = 0xFF2196F3.toInt()
        private const val TARGET_MS = 200L

        /** `.target-button`: the target not selected at 70 % opacity. */
        private const val UNSELECTED_ALPHA = 0.7f
        private const val SEEK_BAR = "android.widget.SeekBar"
        private const val NO_NODE = Int.MIN_VALUE

        /** The range's virtual nodes. */
        internal const val LOW_NODE = 1
        internal const val HIGH_NODE = 2

        /** `21.5` → `("21", ".5")`, with as many decimals as the step has (HA's `digits`); whole steps drop them. */
        fun splitTemp(v: Double, step: Double): Pair<String, String> {
            // The step's decimals, or the value's own when it has more (a 30.25 maximum on a 0.5 step), up to 3.
            val digits = maxOf(ClimateTargets.decimalsOf(step), minOf(ClimateTargets.decimalsOf(v), 3))
            if (digits == 0) return HaDisplay.formatFixed(v, 0) to ""
            // ha-big-number splits formatNumber's parts at the language's decimal mark.
            val text = HaDisplay.formatFixed(v, digits)
            val mark = HaFormat.decimalMark()
            val at = text.lastIndexOf(mark)
            return if (at < 0) text to "" else text.substring(0, at) to text.substring(at)
        }
    }
}
