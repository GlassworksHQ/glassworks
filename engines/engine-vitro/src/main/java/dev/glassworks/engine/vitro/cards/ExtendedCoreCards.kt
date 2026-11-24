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
import dev.glassworks.engine.vitro.model.CardConfig
import dev.glassworks.engine.vitro.ui.CardSurface
import dev.glassworks.engine.vitro.ui.MdiIconView
import dev.glassworks.engine.vitro.ui.MdiIcons
import dev.glassworks.engine.vitro.ui.VitroCardContext
import dev.glassworks.engine.vitro.ui.bindHoldAction
import dev.glassworks.ui.Inter
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
        typeface = Inter.typeface(ctx.context, weight, sp)
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

/** Native HA `entity` card (`hui-entity-card`): name and icon on top, the big live value and unit below. */
class EntityCardView(ctx: VitroCardContext, private val config: CardConfig) : CardSurface(ctx) {
    private val nameView = extLabel(ctx, 16f, ctx.theme.secondaryTextColor, Inter.MEDIUM).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val iconView = MdiIconView(ctx.context)
    private val valueView = extLabel(ctx, 28f, ctx.theme.primaryTextColor, Inter.REGULAR).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val unitView = extLabel(ctx, 16f, ctx.theme.secondaryTextColor)

    init {
        val pad = ctx.px(16f)
        val col = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val header = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(nameView, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(iconView, LinearLayout.LayoutParams(ctx.px(24f), ctx.px(24f)).apply { marginStart = ctx.px(8f) })
        }
        col.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val valueRow = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            addView(valueView, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, 0f))
            addView(unitView, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                marginStart = ctx.px(4f)
                bottomMargin = ctx.px(3f)
            })
        }
        col.addView(valueRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ctx.px(12f) })
        addView(col, LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val eid = config.entity
        isClickable = true
        setOnClickListener { ctx.performAction(ActionConfig.parse(config.raw.optJSONObject("tap_action"), "more-info"), eid) }
        bindHoldAction(ActionConfig.parse(config.raw.optJSONObject("hold_action"), "more-info"), eid)
        enablePressFeedback()
        if (eid != null) observe(eid, ::render) else render(null)
    }

    private fun render(s: EntityState?) {
        nameView.text = config.name ?: s?.friendlyName ?: config.entity.orEmpty()
        iconView.setIcon(MdiIcons.resolveIconName(config.icon, s), iconColorFor(ctx, s))
        val (value, unit) = valueAndUnit(config.raw, s)
        valueView.text = value
        unitView.text = unit
        unitView.visibility = if (unit.isEmpty()) GONE else VISIBLE
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
            if (unit.isNotEmpty() && !s.isUnavailable) return s.state to unit
            return s.formattedState() to ""
        }
    }
}

// ---------------------------------------------------------------------------------------------
// light
// ---------------------------------------------------------------------------------------------

/** Native HA `light` card (`hui-light-card`): a 270° brightness dial around a bulb toggle. */
class LightCardView(ctx: VitroCardContext, private val config: CardConfig) : CardSurface(ctx) {
    private val nameView = extLabel(ctx, 15f, ctx.theme.primaryTextColor, Inter.MEDIUM).apply {
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val stateView = extLabel(ctx, 13f, ctx.theme.secondaryTextColor).apply { gravity = Gravity.CENTER }
    private val dial = BrightnessDialView(ctx.context, ctx.theme.activeColor("light"), ctx.theme.dividerColor, ctx.theme.primaryTextColor)

    init {
        val pad = ctx.px(16f)
        val col = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad, pad, pad)
        }
        col.addView(dial, LinearLayout.LayoutParams(MATCH_PARENT, ctx.px(160f)))
        val labels = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(nameView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(stateView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ctx.px(3f) })
        }
        col.addView(labels, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ctx.px(8f) })
        addView(col, LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val eid = config.entity
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
            cur?.let { ctx.store.putLocal(it.copy(state = "off")) }
            ctx.callService("light", "turn_off", JSONObject().put("entity_id", eid), null)
            return
        }
        val bri = (pct * 255 / 100).coerceIn(1, 255)
        cur?.let { ctx.store.putLocal(it.copy(state = "on", attributes = it.attributes + ("brightness" to bri))) }
        ctx.callService("light", "turn_on", JSONObject().put("entity_id", eid).put("brightness", bri), null)
    }

    private var lastPct: Int? = null

    private fun render(s: EntityState?) {
        nameView.text = config.name ?: s?.friendlyName ?: config.entity.orEmpty()
        val on = s?.state == "on"
        val reportedPct = s?.brightnessPct
        if (reportedPct != null && reportedPct > 0) lastPct = reportedPct
        val pct = reportedPct ?: if (on) (lastPct ?: 100) else 0
        stateView.text = when {
            s == null -> "–"
            s.isUnavailable -> s.formattedState()
            on -> if (reportedPct != null || lastPct != null) "$pct%" else "On"
            else -> "Off"
        }
        dial.update(on, if (on) pct / 100f else 0f, MdiIcons.resolveIconName(config.icon, s))
    }
}

/** Canvas brightness dial: a 270° arc (drag to set brightness) around a round toggle button. */
internal class BrightnessDialView(
    context: Context,
    private val activeColor: Int,
    private val trackColor: Int,
    private val textColor: Int,
) : View(context) {
    private val dp = resources.displayMetrics.density
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 14f * dp
        strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val oval = RectF()
    private val iconMatrix = Matrix()
    private val iconPath = Path()
    private var basePath: Path = MdiIcons.pathFor("lightbulb")
    private var on = false
    private var fraction = 0f
    private var dragFraction: Float? = null
    private var touchMode = TOUCH_NONE

    var onToggle: (() -> Unit)? = null
    var onBrightness: ((Int) -> Unit)? = null

    fun update(isOn: Boolean, frac: Float, iconName: String) {
        on = isOn
        fraction = frac.coerceIn(0f, 1f)
        basePath = MdiIcons.pathFor(iconName)
        if (dragFraction == null) invalidate()
    }

    private val radius: Float get() = (minOf(width, height) / 2f - arcPaint.strokeWidth).coerceAtLeast(8f * dp)

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = radius
        oval.set(cx - r, cy - r, cx + r, cy + r)
        arcPaint.color = trackColor
        canvas.drawArc(oval, START_ANGLE, SWEEP, false, arcPaint)
        val shown = dragFraction ?: if (on) fraction else 0f
        if (shown > 0f) {
            arcPaint.color = activeColor
            canvas.drawArc(oval, START_ANGLE, SWEEP * shown, false, arcPaint)
            val a = Math.toRadians((START_ANGLE + SWEEP * shown).toDouble())
            fillPaint.color = 0xFFFFFFFF.toInt()
            canvas.drawCircle(cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat(), arcPaint.strokeWidth * 0.32f, fillPaint)
        }
        val inner = r * 0.55f
        val lit = on || (dragFraction ?: 0f) > 0f
        fillPaint.color = if (lit) CardSurface.withAlpha(activeColor, 0.2f) else CardSurface.withAlpha(textColor, 0.08f)
        canvas.drawCircle(cx, cy, inner, fillPaint)
        val iconSize = inner * 0.9f
        val scale = iconSize / 24f
        iconMatrix.reset()
        iconMatrix.setScale(scale, scale)
        iconMatrix.postTranslate(cx - iconSize / 2f, cy - iconSize / 2f)
        iconPath.set(basePath)
        iconPath.transform(iconMatrix)
        fillPaint.color = if (lit) activeColor else CardSurface.withAlpha(textColor, 0.55f)
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
                    d <= r * 1.35f && d >= r * 0.6f -> TOUCH_DRAG
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
data class TodoItem(val uid: String, val summary: String, val completed: Boolean)

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
            typeface = Inter.typeface(ctx.context, Inter.REGULAR, 14f)
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

        val list = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, ctx.px(8f), 0, ctx.px(8f))
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
        ctx.callService("todo", "remove_completed_items", JSONObject().put("entity_id", eid), null)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val eid = config.entity ?: return
        // `observe` delivers the current state at once, so this fetches on attach and again on every change.
        observe(eid) { _ -> fetch(eid) }
    }

    private fun fetch(eid: String) {
        val gen = ++fetchGeneration
        ctx.callWs(JSONObject().put("type", "todo/item/list").put("entity_id", eid)) { ok, result, _ ->
            if (!ok) return@callWs
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
        if (item.uid.startsWith(LOCAL_UID)) return
        val now = !item.completed
        items = items.map { if (it.uid == item.uid) it.copy(completed = now) else it }
        fetchGeneration++ // a fetch already in flight must not undo the optimistic flip
        renderItems()
        callTodo(
            "update_item",
            JSONObject().put("entity_id", eid).put("item", item.uid).put("status", if (now) "completed" else "needs_action"),
        ) { fetch(eid) }
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
        val local = TodoItem(uid = "$LOCAL_UID${System.nanoTime()}", summary = text, completed = false)
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
// statistics-graph
// ---------------------------------------------------------------------------------------------

/** One line of a statistics chart: a label, a color and `(epochMs, value)` points. */
data class StatSeries(val label: String, val color: Int, val points: List<Pair<Double, Double>>)

/** Native HA `statistics-graph` card (`hui-statistics-graph-card`) drawn from `recorder/statistics_during_period`. */
class StatisticsGraphCardView(ctx: VitroCardContext, private val config: CardConfig) : CardSurface(ctx) {
    private val titleView = extLabel(ctx, 16f, ctx.theme.primaryTextColor, Inter.MEDIUM)
    private val legend = LinearLayout(ctx.context).apply { orientation = LinearLayout.HORIZONTAL }
    private val chart = StatisticsChartView(ctx.context, ctx.theme.dividerColor, ctx.theme.secondaryTextColor)
    private val ids: List<String> = statisticIds(config)
    private val days = config.raw.optInt("days_to_show", 30).coerceIn(1, 365)
    private val period = config.raw.optString("period", "hour").ifEmpty { "hour" }
    private val statTypes: List<String> = readStatTypes(config.raw.optJSONArray("stat_types"))
    private val refresh = Runnable { fetch() }

    init {
        val pad = ctx.px(16f)
        val col = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        titleView.text = config.title ?: ""
        titleView.visibility = if (config.title == null) GONE else VISIBLE
        col.addView(titleView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = ctx.px(8f) })
        col.addView(legend, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = ctx.px(8f) })
        col.addView(chart, LinearLayout.LayoutParams(MATCH_PARENT, ctx.px(160f)))
        addView(col, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
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

    /** Bumped per request and on detach: an answer from an earlier attachment doesn't overwrite a newer one. */
    private var generation = 0

    private fun fetch() {
        ctx.main.removeCallbacks(refresh)
        if (ids.isEmpty()) return
        val gen = ++generation
        val start = Date(System.currentTimeMillis() - days * 86_400_000L)
        val msg = JSONObject()
            .put("type", "recorder/statistics_during_period")
            .put("start_time", isoUtc(start))
            .put("statistic_ids", JSONArray(ids))
            .put("period", period)
            .put("types", JSONArray(statTypes))
        ctx.callWs(msg) { ok, result, _ ->
            val res = if (ok) result as? JSONObject else null
            ctx.main.post {
                if (gen != generation) return@post
                if (res != null) render(res)
                if (isAttachedToWindow) ctx.main.postDelayed(refresh, REFRESH_MS)
            }
        }
    }

    private fun render(result: JSONObject) {
        val series = ArrayList<StatSeries>()
        ids.forEachIndexed { idx, id ->
            val name = config.entities().firstOrNull { it.entity == id }?.name ?: ctx.store[id]?.friendlyName ?: id
            for ((tIdx, type) in statTypes.withIndex()) {
                val pts = parsePoints(result.optJSONArray(id), type)
                if (pts.isEmpty()) continue
                val label = if (statTypes.size > 1) "$name ($type)" else name
                series += StatSeries(label, PALETTE[(idx * statTypes.size + tIdx) % PALETTE.size], pts)
            }
        }
        legend.removeAllViews()
        series.forEach { s ->
            val item = LinearLayout(ctx.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            item.addView(View(ctx.context).apply { background = pillBackground(s.color) }, LinearLayout.LayoutParams(ctx.px(10f), ctx.px(10f)))
            item.addView(extLabel(ctx, 12f, ctx.theme.secondaryTextColor).apply {
                text = s.label
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = ctx.px(5f) })
            legend.addView(item, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = ctx.px(12f) })
        }
        val unit = ids.firstNotNullOfOrNull { ctx.store[it]?.unit }.orEmpty()
        chart.update(series, unit)
    }

    companion object {
        private const val REFRESH_MS = 5 * 60_000L
        private val VALID_TYPES = setOf("mean", "min", "max", "sum", "state", "change")
        private val PALETTE = intArrayOf(
            0xFF44739E.toInt(), 0xFF984315.toInt(), 0xFF00A28C.toInt(), 0xFFB84B8A.toInt(),
            0xFFC48A00.toInt(), 0xFF6A5ACD.toInt(), 0xFF2E7D32.toInt(), 0xFFD84315.toInt(),
        )

        fun statisticIds(config: CardConfig): List<String> {
            val fromList = config.entities().filter { it.isEntity }.map { it.entity }
            return (fromList.ifEmpty { listOfNotNull(config.entity) }).distinct()
        }

        fun readStatTypes(arr: JSONArray?): List<String> {
            if (arr == null) return listOf("mean")
            val out = (0 until arr.length()).mapNotNull { arr.optString(it, "").takeIf { t -> t in VALID_TYPES } }
            return out.ifEmpty { listOf("mean") }
        }

        fun isoUtc(date: Date): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(date)

        /** Points of one stat type from a statistic's rows; `start` is epoch ms (or an ISO string on old cores). */
        fun parsePoints(rows: JSONArray?, type: String): List<Pair<Double, Double>> {
            if (rows == null) return emptyList()
            val out = ArrayList<Pair<Double, Double>>(rows.length())
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                if (!row.has(type) || row.isNull(type)) continue
                val v = row.optDouble(type)
                if (v.isNaN()) continue
                val st = row.opt("start")
                val t = (st as? Number)?.toDouble() ?: (st as? String)?.let(::parseIso) ?: continue
                out += t to v
            }
            return out
        }

        private fun parseIso(s: String): Double? = runCatching {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .parse(s.take(19))?.time?.toDouble()
        }.getOrNull()
    }
}

/** Canvas multi-series line chart with min/max labels and faint gridlines. */
internal class StatisticsChartView(context: Context, private val gridColor: Int, private val labelColor: Int) : View(context) {
    private val dp = resources.displayMetrics.density
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * dp
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * dp
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f * dp }
    private val path = Path()
    private var series: List<StatSeries> = emptyList()
    private var unit = ""

    fun update(s: List<StatSeries>, unitLabel: String) {
        series = s
        unit = unitLabel
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        gridPaint.color = gridColor
        textPaint.color = labelColor
        val all = series.flatMap { it.points }
        val top = 6f * dp
        val bottom = height - 4f * dp
        for (i in 0..3) {
            val y = top + (bottom - top) * i / 3f
            canvas.drawLine(0f, y, width.toFloat(), y, gridPaint)
        }
        if (all.isEmpty()) {
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("No statistics", width / 2f, height / 2f, textPaint)
            return
        }
        val minT = all.minOf { it.first }
        val maxT = all.maxOf { it.first }
        var minV = all.minOf { it.second }
        var maxV = all.maxOf { it.second }
        if (maxV - minV < 1e-9) { minV -= 1.0; maxV += 1.0 }
        val spanT = (maxT - minT).coerceAtLeast(1.0)
        val spanV = maxV - minV
        for (s in series) {
            path.reset()
            s.points.forEachIndexed { i, (t, v) ->
                val x = ((t - minT) / spanT * width).toFloat()
                val y = (bottom - (v - minV) / spanV * (bottom - top)).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            linePaint.color = s.color
            canvas.drawPath(path, linePaint)
        }
        textPaint.textAlign = Paint.Align.LEFT
        val suffix = if (unit.isEmpty()) "" else " $unit"
        canvas.drawText(formatNum(maxV) + suffix, 2f * dp, top + textPaint.textSize, textPaint)
        canvas.drawText(formatNum(minV) + suffix, 2f * dp, bottom - 3f * dp, textPaint)
    }

    private fun formatNum(v: Double): String =
        if (kotlin.math.abs(v) >= 100) v.roundToInt().toString() else String.format(Locale.ROOT, "%.1f", v)
}

// ---------------------------------------------------------------------------------------------
// area
// ---------------------------------------------------------------------------------------------

/**
 * Native HA `area` card, compact: a tinted header band with the area name and icon, then a live summary of the
 * entities the card names (`temperature_entity`, `humidity_entity`, `entity`, `entities`). Vitro has no area
 * registry, so `sensor_classes` / `alert_classes` only apply to those named entities.
 */
class AreaCardView(ctx: VitroCardContext, private val config: CardConfig) : CardSurface(ctx) {
    private val summary = LinearLayout(ctx.context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val summaryIds: List<String> = summaryEntities(config.raw)

    init {
        val col = LinearLayout(ctx.context).apply { orientation = LinearLayout.VERTICAL }
        val accent = ctx.theme.primaryColor
        val radius = ctx.theme.cardBorderRadiusDp * ctx.dp
        val band = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.px(16f), ctx.px(14f), ctx.px(16f), ctx.px(14f))
            background = GradientDrawable().apply {
                cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
                setColor(CardSurface.withAlpha(accent, 0.14f))
            }
        }
        val icon = MdiIconView(ctx.context).apply { setIcon(config.icon ?: "mdi:sofa", accent) }
        band.addView(icon, LinearLayout.LayoutParams(ctx.px(24f), ctx.px(24f)).apply { marginEnd = ctx.px(12f) })
        band.addView(extLabel(ctx, 16f, ctx.theme.primaryTextColor, Inter.MEDIUM).apply {
            text = areaName(config)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        col.addView(band, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        summary.setPadding(ctx.px(16f), ctx.px(10f), ctx.px(16f), ctx.px(12f))
        col.addView(summary, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(col, LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val nav = config.raw.optString("navigation_path", "").ifEmpty { null }
        val tap = when {
            config.raw.has("tap_action") -> ActionConfig.parse(config.raw.optJSONObject("tap_action"), "more-info")
            nav != null -> ActionConfig("navigate", navigationPath = nav)
            else -> ActionConfig("more-info")
        }
        val first = summaryIds.firstOrNull()
        isClickable = true
        setOnClickListener { ctx.performAction(tap, first) }
        enablePressFeedback()

        if (summaryIds.isEmpty()) {
            summary.visibility = GONE
        } else {
            summaryIds.forEach { eid -> observe(eid) { renderSummary() } }
        }
    }

    private fun renderSummary() {
        summary.removeAllViews()
        for (eid in summaryIds) {
            val s = ctx.store[eid] ?: continue
            val chip = LinearLayout(ctx.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            chip.addView(MdiIconView(ctx.context).apply { setIcon(MdiIcons.resolveIconName(null, s), iconColorFor(ctx, s)) },
                LinearLayout.LayoutParams(ctx.px(16f), ctx.px(16f)).apply { marginEnd = ctx.px(4f) })
            chip.addView(extLabel(ctx, 13f, ctx.theme.secondaryTextColor, Inter.MEDIUM).apply { text = s.formattedState() })
            summary.addView(chip, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = ctx.px(14f) })
        }
    }

    companion object {
        /** The card's `name`, else the area id in words (`living_room` → `Living room`). */
        fun areaName(config: CardConfig): String =
            config.name ?: config.raw.optString("area", "").ifEmpty { "Area" }
                .replace('_', ' ').replaceFirstChar { it.titlecase(Locale.ROOT) }

        /** Entity ids an area card names directly, in display order. */
        fun summaryEntities(raw: JSONObject): List<String> {
            val out = LinkedHashSet<String>()
            for (key in listOf("temperature_entity", "humidity_entity", "entity")) {
                raw.optString(key, "").takeIf { it.contains('.') }?.let { out += it }
            }
            val arr = raw.optJSONArray("entities")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    when (val item = arr.opt(i)) {
                        is String -> if (item.contains('.')) out += item
                        is JSONObject -> item.optString("entity", "").takeIf { it.contains('.') }?.let { out += it }
                    }
                }
            }
            return out.toList()
        }
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
    private var loadedUrl: String? = null

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
        if (url == loadedUrl) return
        loadedUrl = url
        ctx.loadImage(url) { bmp ->
            if (bmp == null || url != loadedUrl) return@loadImage
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
        typeface = Inter.typeface(ctx.context, weight, sp)
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
        if (dragTarget == null) invalidate()
    }

    fun nudge(sign: Int) {
        val t = target ?: return
        val next = HumidifierCardView.snapHumidity(t + sign, min, max)
        if (next.toDouble() == t) return
        target = next.toDouble()
        invalidate()
        onCommit(next)
    }

    private val radius: Float get() = (minOf(width, height) / 2f - strokeW).coerceAtLeast(8f * dp)

    private fun fracOf(v: Double): Float = ((v - min) / (max - min)).toFloat().coerceIn(0f, 1f)

    private fun pointAt(frac: Float, r: Float): Pair<Float, Float> {
        val a = Math.toRadians((START_ANGLE + SWEEP * frac).toDouble())
        return (width / 2f + r * cos(a).toFloat()) to (height / 2f + r * sin(a).toFloat())
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
            val (x, y) = pointAt(fracOf(c), r)
            fill.color = ctx.theme.cardBackgroundColor
            canvas.drawCircle(x, y, strokeW * 0.3f, fill)
            fill.color = ctx.theme.primaryTextColor
            canvas.drawCircle(x, y, strokeW * 0.18f, fill)
        }
        if (shown != null) {
            val (x, y) = pointAt(fracOf(shown), r)
            fill.color = Color.WHITE
            canvas.drawCircle(x, y, strokeW * 0.42f, fill)
        }
        canvas.drawText(status, cx, cy - 30f * dp, statusPaint)
        canvas.drawText(shown?.let { "${it.roundToInt()}%" } ?: "–", cx, cy + 14f * dp, bigPaint)
        current?.let { canvas.drawText("Currently ${it.roundToInt()}%", cx, cy + 36f * dp, subPaint) }
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
                results[i] = parseEvents(json as? JSONArray, eid)
                if (--pending == 0) {
                    render(upcoming(results.filterNotNull().flatten(), System.currentTimeMillis()))
                    if (isAttachedToWindow) ctx.main.postDelayed(refresh, REFRESH_MS)
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
                if (isAttachedToWindow) ctx.main.postDelayed(refresh, REFRESH_MS)
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
