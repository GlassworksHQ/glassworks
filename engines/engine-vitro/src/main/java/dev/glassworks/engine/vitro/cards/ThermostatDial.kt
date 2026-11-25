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
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.HaDisplay
import dev.glassworks.engine.vitro.ui.CardSurface
import dev.glassworks.engine.vitro.ui.KeyedText
import dev.glassworks.engine.vitro.ui.MdiIcons
import dev.glassworks.engine.vitro.ui.NumberText
import dev.glassworks.engine.vitro.ui.VitroCardContext
import dev.glassworks.engine.vitro.ui.VitroFonts
import dev.glassworks.ui.Inter
import org.json.JSONObject
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

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
         * the other end meanwhile isn't overwritten. Null when [s] no longer has a range (the mode changed). With no
         * state at all, the proposal as it is.
         */
        fun rangeToSend(handle: ClimateHandle, low: Double, high: Double, s: EntityState?): Pair<Double, Double>? {
            val attrs = s?.attributes ?: return low to high
            val now = of(attrs)
            val nl = now.low
            val nh = now.high
            if (!now.isRange || nl == null || nh == null) return null
            val min = (attrs["min_temp"] as? Number)?.toDouble() ?: Double.NEGATIVE_INFINITY
            val max = (attrs["max_temp"] as? Number)?.toDouble() ?: Double.POSITIVE_INFINITY
            return if (handle == ClimateHandle.LOW) bound(ClimateHandle.LOW, low, nl, nh, min, max) to nh
            else nl to bound(ClimateHandle.HIGH, high, nl, nh, min, max)
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
    private val labelPaint = textPaint(14f, Inter.MEDIUM).apply { textAlign = Paint.Align.CENTER }
    private val bigPaint = textPaint(44f, Inter.REGULAR)
    private val unitPaint = textPaint(13f, Inter.REGULAR)
    private val decPaint = textPaint(20f, Inter.REGULAR)
    private val curPaint = textPaint(14f, Inter.MEDIUM)
    private val offPaint = textPaint(36f, Inter.REGULAR).apply { textAlign = Paint.Align.CENTER }
    private val offCurPaint = textPaint(16f, Inter.MEDIUM)
    private val bigSize = bigPaint.textSize
    private val unitSize = unitPaint.textSize
    private val decSize = decPaint.textSize

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
    private val thermometer: Path by lazy { MdiIcons.pathFor("mdi:thermometer") }
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
        offCurMemo.of(cur) { v -> HaDisplay.formatValue(v, 1).let { if ('.' in it) it else String.format(Locale.US, "%.1f", v) } + " " + unit }
    private fun curText(cur: Double) = curMemo.of(cur) { v -> String.format(Locale.US, "%.1f %s", v, unit) }

    /**
     * HA's label over the target: the HVAC action while it runs (anything but `off`), else the state as HA names it
     * ("Heat/Cool"), else the mode.
     */
    private fun labelText() = labelMemo.of(action, mode) {
        val a = action
        when {
            a != null && a != "off" -> humanize(a)
            stateLabel.isNotEmpty() -> stateLabel
            else -> humanize(a ?: mode)
        }
    }

    private fun humanize(s: String) = s.replace('_', ' ').replaceFirstChar { it.titlecase(Locale.ROOT) }

    /** A target as the dial prints it, `21.5 °C`. */
    private fun targetText(v: Double): String {
        val (whole, dec) = splitTemp(v, step)
        return "$whole$dec $unit"
    }

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
    ) {
        val wasRange = isRange
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
        val next = snap(t + sign * step)
        if (next == t) return
        setTarget(next)
        describe()
        invalidate()
        onCommit(next)
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
        val snapped = min + Math.round((v - min) / step) * step
        // Rounded to the step's decimals, then kept within the device's limits (a 30.25 maximum stays 30.25).
        return ClimateTargets.roundToStep(snapped, step).coerceIn(min, max)
    }

    /** The heat, cool or auto/heat_cool accent this thermostat uses, or null when off. */
    private fun accent(): Pair<Int, Int>? = when {
        action == "heating" || mode == "heat" -> HEAT_COLOR to HEAT_SOFT
        action == "cooling" || mode == "cool" -> COOL_COLOR to COOL_SOFT
        mode == "heat_cool" || mode == "auto" -> AUTO_COLOR to AUTO_SOFT
        else -> null
    }

    /** A range end's color: heat for the low end, cool for the high one, grey while the thermostat is off. */
    fun handleColor(handle: ClimateHandle): Int = when {
        !isActive -> ctx.theme.secondaryTextColor
        handle == ClimateHandle.LOW -> HEAT_COLOR
        else -> COOL_COLOR
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

        if (isRange) {
            drawRange(canvas, activeTint)
            return
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
                pointAt(a)
                canvas.drawCircle(pt[0], pt[1], 2.5f * dp, fill)
            }
        } else if (isAutoIdle && cur != null) {
            fill.color = CardSurface.withAlpha(Color.WHITE, 0.7f)
            pointAt(angleOf(cur))
            canvas.drawCircle(pt[0], pt[1], 3f * dp, fill)
        } else if (tgt == null && cur != null) {
            // With no target HA still marks the current temperature on the ring.
            fill.color = ctx.theme.secondaryTextColor
            pointAt(angleOf(cur))
            canvas.drawCircle(pt[0], pt[1], 4f * dp, fill)
        }
        if (tgt != null) drawThumb(canvas, tgt, accent?.first ?: ctx.theme.secondaryTextColor, isFocused)

        // Centre: action label, split target temperature, current temperature.
        if (target == null) {
            // ha-state-control-climate-temperature with no target: the state big (36 px) in the middle, the current
            // temperature (16 px / 500, primary color) 49 px under it, no label above.
            offPaint.color = ctx.theme.primaryTextColor
            val fm = fmBuf.also { offPaint.getFontMetrics(it) }
            canvas.drawText(stateLabel, cx, cy - (fm.ascent + fm.descent) / 2f - 1.6f * dp, offPaint)
            if (cur != null && showCurrentInDial) drawCurrent(canvas, cur, ctx.theme.primaryTextColor, offCurPaint, 16f * dp, cy + 48.8f * dp)
            return
        }
        val labelText = labelText()
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
            if (shownValue != splitValue || step != splitStep) {
                val parts = splitTemp(shownValue, step)
                splitValue = shownValue
                splitStep = step
                splitWhole = parts.first
                splitDec = parts.second
            }
            val whole = splitWhole
            val dec = splitDec
            val wholeW = bigPaint.measureText(whole)
            val sideW = maxOf(unitPaint.measureText(unit), if (dec.isEmpty()) 0f else decPaint.measureText(dec))
            val gap = 2f * dp
            val x0 = cx - (wholeW + gap + sideW) / 2f
            canvas.drawText(whole, x0, baseline, bigPaint)
            val sideX = x0 + wholeW + gap
            canvas.drawText(unit, sideX, baseline - 20f * dp, unitPaint)
            if (dec.isNotEmpty()) canvas.drawText(dec, sideX, baseline, decPaint)
        }

        if (cur != null && showCurrentInDial) drawCurrentRow(canvas, cur)
    }

    /** The thermometer row under the target: 14 px, in the running action's color. */
    private fun drawCurrentRow(canvas: Canvas, cur: Double) {
        val curText = curText(cur)
        val curColor = if (action == "heating") HEAT_COLOR else if (action == "cooling") COOL_COLOR else ctx.theme.secondaryTextColor
        curPaint.color = curColor
        val iconSize = 14f * dp
        val textW = curPaint.measureText(curText)
        val total = iconSize + 4f * dp + textW
        val left = cx - total / 2f
        val rowBase = cy + 46f * dp
        iconPath.set(thermometer)
        iconMatrix.setScale(iconSize / 24f, iconSize / 24f)
        iconMatrix.postTranslate(left, rowBase - iconSize + 2f * dp)
        iconPath.transform(iconMatrix)
        fill.color = curColor
        canvas.drawPath(iconPath, fill)
        curPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(curText, left + iconSize + 4f * dp, rowBase, curPaint)
    }

    /** A thumb at [value]: a white dot in a [color] border, ringed while it has the keyboard's focus. */
    private fun drawThumb(canvas: Canvas, value: Double, color: Int, focused: Boolean) {
        pointAt(angleOf(value))
        val x = pt[0]
        val y = pt[1]
        if (focused) {
            // :focus-visible: HA widens the thumb's stroke; a ring in the text color reads the same on a panel.
            ring.color = ctx.theme.primaryTextColor
            ring.strokeWidth = 2f * dp
            canvas.drawCircle(x, y, 9.5f * dp, ring)
        }
        fill.color = color
        canvas.drawCircle(x, y, 6.5f * dp, fill)
        fill.color = Color.WHITE
        canvas.drawCircle(x, y, 5f * dp, fill)
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
     * The dual slider (`ha-control-circular-slider dual`): the low thumb's arc runs from the ring's start ("start"
     * mode) and the high thumb's to its end ("end"), each at half strength, with a solid arc to the current temperature
     * while the room is below the low target or above the high one. In the centre, HA's label and the two targets,
     * the one not selected at 70 %.
     */
    private fun drawRange(canvas: Canvas, activeTint: Int?) {
        val lo = low ?: return
        val hi = high ?: return
        arcPaint.color = if (ctx.theme.dark) TRACK_DARK else TRACK_LIGHT
        canvas.drawArc(oval, 135f, 270f, false, arcPaint)
        val cur = current?.takeIf { it in min..max }
        var curInArc = false
        if (isActive) {
            arc(canvas, min, lo, HEAT_SOFT)
            arc(canvas, hi, max, COOL_SOFT)
            if (cur != null && cur <= lo) {
                arc(canvas, cur, lo, HEAT_COLOR)
                curInArc = cur < lo
            }
            if (cur != null && hi <= cur) {
                arc(canvas, hi, cur, COOL_COLOR)
                curInArc = cur > hi
            }
        }
        if (cur != null) {
            // HA's current marker: an 8 px dot in the text color at half strength, cleared to white on a solid arc.
            fill.color = if (curInArc) Color.WHITE else CardSurface.withAlpha(ctx.theme.primaryTextColor, 0.5f)
            pointAt(angleOf(cur))
            canvas.drawCircle(pt[0], pt[1], if (curInArc) 2.5f * dp else 4f * dp, fill)
        }
        drawThumb(canvas, lo, handleColor(ClimateHandle.LOW), isFocused && selected == ClimateHandle.LOW)
        drawThumb(canvas, hi, handleColor(ClimateHandle.HIGH), isFocused && selected == ClimateHandle.HIGH)

        labelPaint.color = activeTint ?: ctx.theme.secondaryTextColor
        canvas.drawText(labelText(), cx, cy - 34f * dp, labelPaint)

        // The two targets side by side, 24 px apart (16 px in a medium dial, 8 px in a small one), shrunk to fit.
        val baseline = cy + 16f * dp
        val gap = when {
            width < 190f * dp -> 8f * dp
            width < 250f * dp -> 16f * dp
            else -> 24f * dp
        }
        val lw = lowWhole.of(lo) { splitTemp(it, step).first }
        val ld = lowDec.of(lo) { splitTemp(it, step).second }
        val hw = highWhole.of(hi) { splitTemp(it, step).first }
        val hd = highDec.of(hi) { splitTemp(it, step).second }
        var lowW = numberWidth(lw, ld)
        var highW = numberWidth(hw, hd)
        val room = radius * 1.5f
        val scale = (room / (lowW + gap + highW)).coerceAtMost(1f)
        if (scale < 1f) {
            bigPaint.textSize = bigSize * scale
            unitPaint.textSize = unitSize * scale
            decPaint.textSize = decSize * scale
            lowW = numberWidth(lw, ld)
            highW = numberWidth(hw, hd)
        }
        val x0 = cx - (lowW + gap + highW) / 2f
        drawNumber(canvas, lw, ld, x0, baseline, if (selected == ClimateHandle.LOW) 1f else UNSELECTED_ALPHA, scale, lowRect)
        drawNumber(canvas, hw, hd, x0 + lowW + gap, baseline, if (selected == ClimateHandle.HIGH) 1f else UNSELECTED_ALPHA, scale, highRect)
        if (scale < 1f) {
            bigPaint.textSize = bigSize
            unitPaint.textSize = unitSize
            decPaint.textSize = decSize
        }
        val cur2 = current
        if (cur2 != null && showCurrentInDial) drawCurrentRow(canvas, cur2)
    }

    /** One ha-big-number's width: the whole part, a 2 dp gap, then the wider of the unit and the decimals. */
    private fun numberWidth(whole: String, dec: String): Float =
        bigPaint.measureText(whole) + 2f * dp + maxOf(unitPaint.measureText(unit), if (dec.isEmpty()) 0f else decPaint.measureText(dec))

    /** Draws one ha-big-number at [x] and records the box a press on it selects into [hit]. */
    private fun drawNumber(canvas: Canvas, whole: String, dec: String, x: Float, baseline: Float, alpha: Float, scale: Float, hit: RectF) {
        val color = CardSurface.withAlpha(ctx.theme.primaryTextColor, alpha)
        bigPaint.color = color
        unitPaint.color = color
        decPaint.color = color
        bigPaint.textAlign = Paint.Align.LEFT
        unitPaint.textAlign = Paint.Align.LEFT
        decPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(whole, x, baseline, bigPaint)
        val sideX = x + bigPaint.measureText(whole) + 2f * dp
        canvas.drawText(unit, sideX, baseline - 20f * dp * scale, unitPaint)
        if (dec.isNotEmpty()) canvas.drawText(dec, sideX, baseline, decPaint)
        val fm = fmBuf.also { bigPaint.getFontMetrics(it) }
        hit.set(x - 4f * dp, baseline + fm.ascent, x + numberWidth(whole, dec) + 4f * dp, baseline + fm.descent)
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
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    // Nothing was sent: drop the preview and show the target Home Assistant has.
                    setTarget(boundTarget)
                    // TalkBack reads the restored target, not the preview.
                    describe()
                    invalidate()
                }
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
        if (cl != bl || ch != bh) onRangeCommit(handle, cl, ch)
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
            moveHandle(handle, snap(next))
            // At an end (or against the other thumb) the key moves focus on instead, as SeekBar's does.
            if ((if (handle == ClimateHandle.LOW) low else high) == from) return super.onKeyDown(keyCode, event)
            keyPending = true
            keyHandle = handle
            keyCodePending = keyCode
            invalidate()
            return true
        }
        val t = target ?: return super.onKeyDown(keyCode, event)
        val next = snap(keyValue(keyCode, t) ?: return super.onKeyDown(keyCode, event))
        if (next == t) return super.onKeyDown(keyCode, event)
        dragTarget(next)
        keyPending = true
        keyCodePending = keyCode
        invalidate()
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
        val edited = if (keyHandle == ClimateHandle.LOW) low else high
        if (low != null && high != null && target == null && edited != null) {
            commitRange(keyHandle, edited)
        } else if (boundTarget != null) {
            target?.takeIf { it != boundTarget }?.let(onCommit)
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
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (!isRange && target != null) {
            when (action) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> { nudge(1); return true }
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> { nudge(-1); return true }
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
        val node = when {
            event.actionMasked == MotionEvent.ACTION_HOVER_EXIT -> NO_NODE
            thumbBounds(lo).contains(event.x.toInt(), event.y.toInt()) || lowRect.contains(event.x, event.y) -> LOW_NODE
            thumbBounds(hi).contains(event.x.toInt(), event.y.toInt()) || highRect.contains(event.x, event.y) -> HIGH_NODE
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
        private const val TRACK_LIGHT = 0xFFE8E8E8.toInt()
        private const val TRACK_DARK = 0xFF333333.toInt()
        private const val AUTO_COLOR = 0xFFFFC107.toInt()
        private const val AUTO_SOFT = 0xFFFFE082.toInt()
        private const val HEAT_COLOR = 0xFFFF6F22.toInt()
        private const val HEAT_SOFT = 0xFFFFAD7A.toInt()
        private const val COOL_COLOR = 0xFF2196F3.toInt()
        private const val COOL_SOFT = 0xFF90CAF9.toInt()
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
            val digits = ClimateTargets.decimalsOf(step)
            if (digits == 0) return String.format(Locale.US, "%.0f", v) to ""
            val text = String.format(Locale.US, "%.${digits}f", v)
            return text.substringBefore('.') to "." + text.substringAfter('.')
        }
    }
}
