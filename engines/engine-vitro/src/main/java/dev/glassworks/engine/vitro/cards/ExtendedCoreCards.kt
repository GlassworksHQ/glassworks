/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.cards

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import dev.glassworks.engine.vitro.data.EntityState
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
import java.text.SimpleDateFormat
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

    private fun render(s: EntityState?) {
        nameView.text = config.name ?: s?.friendlyName ?: config.entity.orEmpty()
        val on = s?.state == "on"
        val pct = s?.brightnessPct ?: if (on) 100 else 0
        stateView.text = when {
            s == null -> "–"
            s.isUnavailable -> s.formattedState()
            on -> "$pct%"
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

/** Native HA `alarm-panel` card (`hui-alarm-panel-card`): state badge, arm/disarm buttons and a PIN pad. */
class AlarmPanelCardView(ctx: VitroCardContext, private val config: CardConfig) : CardSurface(ctx) {
    private val titleView = extLabel(ctx, 16f, ctx.theme.primaryTextColor, Inter.MEDIUM).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val badgeView = extLabel(ctx, 12f, 0xFFFFFFFF.toInt(), Inter.MEDIUM).apply {
        setPadding(ctx.px(10f), ctx.px(5f), ctx.px(10f), ctx.px(5f))
    }
    private val actionsRow = LinearLayout(ctx.context).apply { orientation = LinearLayout.HORIZONTAL }
    private val codeView = extLabel(ctx, 20f, ctx.theme.primaryTextColor, Inter.MEDIUM).apply {
        gravity = Gravity.CENTER
        letterSpacing = 0.3f
    }
    private val keypad = LinearLayout(ctx.context).apply { orientation = LinearLayout.VERTICAL }
    private val code = StringBuilder()
    private val armStates: List<String> = readStates(config.raw.optJSONArray("states"))

    init {
        val pad = ctx.px(16f)
        val col = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val header = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(titleView, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(badgeView, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = ctx.px(8f) })
        }
        col.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        col.addView(actionsRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ctx.px(14f) })
        col.addView(codeView, LinearLayout.LayoutParams(MATCH_PARENT, ctx.px(28f)).apply { topMargin = ctx.px(12f) })
        buildKeypad()
        col.addView(keypad, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ctx.px(6f) })
        addView(col, LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val eid = config.entity
        header.isClickable = true
        header.setOnClickListener { eid?.let(ctx.openMoreInfo) }
        if (eid != null) observe(eid, ::render) else render(null)
    }

    private fun buildKeypad() {
        val keys = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf(KEY_CLEAR, "0", KEY_BACK))
        keys.forEachIndexed { rIdx, row ->
            val r = LinearLayout(ctx.context).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEachIndexed { cIdx, key ->
                val b = extLabel(ctx, if (key.length == 1) 18f else 13f, ctx.theme.primaryTextColor, Inter.MEDIUM).apply {
                    text = key
                    gravity = Gravity.CENTER
                    background = pillBackground(CardSurface.withAlpha(ctx.theme.primaryTextColor, 0.06f), 12f * ctx.dp)
                    isClickable = true
                    setOnClickListener { onKey(key) }
                }
                r.addView(b, LinearLayout.LayoutParams(0, ctx.px(44f), 1f).apply { if (cIdx > 0) marginStart = ctx.px(8f) })
            }
            keypad.addView(r, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { if (rIdx > 0) topMargin = ctx.px(8f) })
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
        codeView.text = "●".repeat(code.length)
    }

    private fun render(s: EntityState?) {
        titleView.text = config.name ?: s?.friendlyName ?: "Alarm"
        val state = s?.state ?: "unknown"
        badgeView.text = stateLabel(state)
        badgeView.background = pillBackground(stateColor(state))
        val needsCode = s?.attributes?.get("code_format") != null
        keypad.visibility = if (needsCode) VISIBLE else GONE
        codeView.visibility = if (needsCode) VISIBLE else GONE
        renderCode()

        actionsRow.removeAllViews()
        val buttons = if (state == "disarmed" || s == null || s.isUnavailable) armStates else listOf("disarm")
        buttons.forEachIndexed { idx, action ->
            val b = extLabel(ctx, 13f, ctx.theme.primaryTextColor, Inter.MEDIUM).apply {
                text = actionLabel(action)
                gravity = Gravity.CENTER
                setPadding(ctx.px(8f), ctx.px(10f), ctx.px(8f), ctx.px(10f))
                background = GradientDrawable().apply {
                    cornerRadius = 999f
                    setColor(CardSurface.withAlpha(ctx.theme.primaryColor, 0.12f))
                    setStroke(ctx.px(1f), CardSurface.withAlpha(ctx.theme.primaryColor, 0.5f))
                }
                isClickable = s != null && !s.isUnavailable
                setOnClickListener { run(action) }
            }
            actionsRow.addView(b, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { if (idx > 0) marginStart = ctx.px(8f) })
        }
    }

    private fun run(action: String) {
        val eid = config.entity ?: return
        val data = JSONObject().put("entity_id", eid)
        if (code.isNotEmpty()) data.put("code", code.toString())
        ctx.callService("alarm_control_panel", serviceFor(action), data, null)
        code.setLength(0)
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

        fun stateColor(state: String): Int = when (state) {
            "disarmed" -> 0xFF4CAF50.toInt()
            "armed_home" -> 0xFF2196F3.toInt()
            "armed_away" -> 0xFFFFA000.toInt()
            "armed_night" -> 0xFF3F51B5.toInt()
            "armed_vacation", "armed_custom_bypass" -> 0xFF2196F3.toInt()
            "pending", "arming", "disarming" -> 0xFFFF9800.toInt()
            "triggered" -> 0xFFF44336.toInt()
            else -> 0xFF9E9E9E.toInt()
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
    private val titleView = extLabel(ctx, 16f, ctx.theme.primaryTextColor, Inter.MEDIUM)
    private val activeBox = LinearLayout(ctx.context).apply { orientation = LinearLayout.VERTICAL }
    private val completedHeader = extLabel(ctx, 13f, ctx.theme.secondaryTextColor, Inter.MEDIUM).apply {
        setPadding(0, ctx.px(10f), 0, ctx.px(6f))
        isClickable = true
    }
    private val completedBox = LinearLayout(ctx.context).apply { orientation = LinearLayout.VERTICAL }
    private val input = EditText(ctx.context)
    private var items: List<TodoItem> = emptyList()
    private var completedExpanded = true
    private var fetchGeneration = 0
    private val hideCompleted = config.raw.optBoolean("hide_completed", false)

    init {
        val pad = ctx.px(16f)
        val col = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        col.addView(titleView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = ctx.px(8f) })
        col.addView(activeBox, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        col.addView(completedHeader, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        col.addView(completedBox, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        completedHeader.setOnClickListener {
            completedExpanded = !completedExpanded
            renderItems()
        }

        val addRow = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
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
            setPadding(ctx.px(12f), ctx.px(8f), ctx.px(12f), ctx.px(8f))
            background = GradientDrawable().apply {
                cornerRadius = 10f * ctx.dp
                setStroke(ctx.px(1f), ctx.theme.dividerColor)
            }
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    addItem()
                    true
                } else {
                    false
                }
            }
        }
        val plus = MdiIconView(ctx.context).apply {
            setIcon("mdi:plus", ctx.theme.primaryColor)
            val p = ctx.px(6f)
            setPadding(p, p, p, p)
            isClickable = true
            setOnClickListener { addItem() }
        }
        addRow.addView(input, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addRow.addView(plus, LinearLayout.LayoutParams(ctx.px(36f), ctx.px(36f)).apply { marginStart = ctx.px(6f) })
        col.addView(addRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ctx.px(10f) })
        addView(col, LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        titleView.text = config.title ?: config.entity?.let { ctx.store[it]?.friendlyName } ?: "To-do"
        renderItems()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val eid = config.entity ?: return
        // `observe` delivers the current state at once, so this fetches on attach and again on every change.
        observe(eid) { s ->
            titleView.text = config.title ?: s?.friendlyName ?: "To-do"
            fetch(eid)
        }
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
        val now = !item.completed
        items = items.map { if (it.uid == item.uid) it.copy(completed = now) else it }
        fetchGeneration++ // a fetch already in flight must not undo the optimistic flip
        renderItems()
        ctx.callService(
            "todo",
            "update_item",
            JSONObject().put("entity_id", eid).put("item", item.uid).put("status", if (now) "completed" else "needs_action"),
            null,
        )
    }

    private fun addItem() {
        val eid = config.entity ?: return
        val text = input.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        input.setText("")
        items = items + TodoItem(uid = "local-${System.nanoTime()}", summary = text, completed = false)
        fetchGeneration++
        renderItems()
        ctx.callService("todo", "add_item", JSONObject().put("entity_id", eid).put("item", text), null)
    }

    private fun renderItems() {
        activeBox.removeAllViews()
        completedBox.removeAllViews()
        val active = items.filter { !it.completed }
        val done = items.filter { it.completed }
        active.forEach { activeBox.addView(itemRow(it), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }
        if (active.isEmpty()) {
            activeBox.addView(extLabel(ctx, 13f, ctx.theme.secondaryTextColor).apply {
                text = if (items.isEmpty()) "No items" else "All done"
                setPadding(0, ctx.px(6f), 0, ctx.px(6f))
            })
        }
        val showCompleted = done.isNotEmpty() && !hideCompleted
        completedHeader.visibility = if (showCompleted) VISIBLE else GONE
        completedHeader.text = "${if (completedExpanded) "▾" else "▸"}  Completed (${done.size})"
        completedBox.visibility = if (showCompleted && completedExpanded) VISIBLE else GONE
        if (showCompleted && completedExpanded) {
            done.forEach { completedBox.addView(itemRow(it), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }
        }
    }

    private fun itemRow(item: TodoItem): View {
        val row = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, ctx.px(6f), 0, ctx.px(6f))
            isClickable = true
            setOnClickListener { toggle(item) }
        }
        val box = MdiIconView(ctx.context).apply {
            if (item.completed) setIcon("mdi:checkbox-marked-circle", ctx.theme.primaryColor)
            else setIcon("mdi:checkbox-blank-circle-outline", ctx.theme.secondaryTextColor)
        }
        row.addView(box, LinearLayout.LayoutParams(ctx.px(22f), ctx.px(22f)).apply { marginEnd = ctx.px(12f) })
        val text = extLabel(ctx, 14f, if (item.completed) ctx.theme.secondaryTextColor else ctx.theme.primaryTextColor).apply {
            this.text = item.summary
            if (item.completed) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        }
        row.addView(text, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        return row
    }

    companion object {
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
        super.onDetachedFromWindow()
    }

    private fun fetch() {
        ctx.main.removeCallbacks(refresh)
        if (ids.isEmpty()) return
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
