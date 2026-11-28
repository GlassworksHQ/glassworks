/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.cards

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.HaFormat
import dev.glassworks.engine.vitro.data.HaLocale
import dev.glassworks.engine.vitro.data.HaText
import dev.glassworks.engine.vitro.model.EntityRowConfig
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.A11yRole
import dev.glassworks.engine.vitro.ui.AdjustableAccess
import dev.glassworks.engine.vitro.ui.MdiIconView
import dev.glassworks.engine.vitro.ui.VitroAccess
import dev.glassworks.engine.vitro.ui.VitroCardContext
import dev.glassworks.engine.vitro.ui.VitroFonts
import dev.glassworks.ui.Inter
import org.json.JSONObject
import java.math.BigDecimal
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The entities-card rows whose control isn't a switch, as HA's frontend (20261028.5) draws them inside
 * `hui-generic-entity-row`: `hui-input-number-entity-row` and `hui-number-entity-row` (an `ha-slider` and the state, or
 * an `ha-input` box), `hui-input-select-entity-row` and `hui-select-entity-row` (an `ha-select` in place of the name),
 * and the plain `ha-button`s of `hui-script-entity-row` (Run, Cancel), `hui-scene-entity-row` (Activate),
 * `hui-button-entity-row` and `hui-input-button-entity-row` (Press) and `hui-lock-entity-row` (Lock, Unlock).
 *
 * Sizes and colors are HA's, measured on its page by the parity cases `entities-controls`, `entities-number`,
 * `entities-select`, `entities-buttons`, `entities-lock` and `entities-controls-light`. A domain drawn here is listed
 * in `native-options.json`'s `rowEntity`; one that isn't sends its view to HA's frontend.
 */
internal object EntityRowControls {
    /** The domains drawn here: HA's `DOMAIN_TO_ELEMENT_TYPE` rows that aren't a toggle. */
    val DOMAINS: Set<String> = setOf("input_number", "number", "input_select", "select", "script", "scene", "button", "input_button", "lock")

    /**
     * A row's control. [hideName]: the control takes the name's place (HA's `hide-name`, the dropdowns), so the icon is
     * followed by it directly. [rowHeightDp]: the row's height, when the control makes it taller than 40 px.
     */
    class Control(
        val view: View,
        val hideName: Boolean = false,
        val rowHeightDp: Float? = null,
        val update: (EntityState?) -> Unit,
    )

    /**
     * The control for [row]'s domain, or null when it isn't one of [DOMAINS]. [name] is the row's name as HA's
     * `formatEntityName` gives it (the dropdown's label, TalkBack's labels); [rowContentWidth] the row's width inside
     * its padding, which `hui-generic-entity-row`'s flex layout shares between the name and a slider.
     */
    fun build(ctx: VitroCardContext, row: EntityRowConfig, name: () -> String, rowContentWidth: () -> Int): Control? =
        when (row.entity.substringBefore('.')) {
            "input_number", "number" -> numberControl(ctx, row, name, rowContentWidth)
            "input_select", "select" -> selectControl(ctx, row, name)
            "script" -> scriptControl(ctx, row, name)
            "scene" -> singleButton(ctx, row, name, { HaText.ui("ui.card.scene.activate") }) { _ ->
                ctx.callService("scene", "turn_on", JSONObject().put("entity_id", row.entity), null)
            }
            "button", "input_button" -> singleButton(ctx, row, name, { HaText.ui("ui.card.button.press") }) { _ ->
                ctx.callService(row.entity.substringBefore('.'), "press", JSONObject().put("entity_id", row.entity), null)
            }
            "lock" -> lockControl(ctx, row, name)
            else -> null
        }

    // ---- colors (HA's `--ha-color-*`, light and dark) ----

    private fun themeColor(ctx: VitroCardContext, key: String): Int? =
        ThemeResolver.parseColor(ctx.theme.vars[key]?.let { ThemeResolver.resolveValue(it, ctx.theme.vars, 0) })

    /** A plain `ha-button`'s label: `--ha-color-on-primary-normal` (primary 60 dark, 40 light). */
    private fun brandText(ctx: VitroCardContext) =
        themeColor(ctx, "ha-color-on-primary-normal") ?: if (ctx.theme.dark) 0xFF37C8FD.toInt() else 0xFF009AC7.toInt()

    /** `variant="danger"`: `--ha-color-on-danger-normal` (red 60 dark, 40 light). */
    private fun dangerText(ctx: VitroCardContext) =
        themeColor(ctx, "ha-color-on-danger-normal") ?: if (ctx.theme.dark) 0xFFF3676C.toInt() else 0xFFB30532.toInt()

    /** A disabled plain button's label: `--ha-color-on-disabled-quiet` (neutral 40 dark, 80 light). */
    private fun disabledText(ctx: VitroCardContext) =
        themeColor(ctx, "ha-color-on-disabled-quiet") ?: if (ctx.theme.dark) 0xFF5E5E5E.toInt() else 0xFFCCCCCC.toInt()

    /** `--ha-color-form-background` (neutral 20 dark, 95 light): a field's fill. */
    private fun formBackground(ctx: VitroCardContext) =
        themeColor(ctx, "ha-color-form-background") ?: if (ctx.theme.dark) 0xFF363636.toInt() else 0xFFF3F3F3.toInt()

    /** `--ha-color-form-background-disabled` (neutral 20 dark, 80 light), drawn at half opacity. */
    private fun formBackgroundDisabled(ctx: VitroCardContext) =
        themeColor(ctx, "ha-color-form-background-disabled") ?: if (ctx.theme.dark) 0xFF363636.toInt() else 0xFFCCCCCC.toInt()

    /** `--ha-color-border-neutral-loud` (neutral 70 dark, 40 light): a field's bottom line. */
    private fun fieldLine(ctx: VitroCardContext) =
        themeColor(ctx, "ha-color-border-neutral-loud") ?: if (ctx.theme.dark) 0xFFB1B1B1.toInt() else 0xFF5E5E5E.toInt()

    /** An `ha-input`'s end slot (the unit): neutral 80 dark, 40 light. */
    private fun unitText(ctx: VitroCardContext) = if (ctx.theme.dark) 0xFFCCCCCC.toInt() else 0xFF5E5E5E.toInt()

    /** A field's floating label: 60 % white on dark, 60 % black on light. */
    private fun labelText(ctx: VitroCardContext) = if (ctx.theme.dark) 0x99FFFFFF.toInt() else 0x99000000.toInt()

    /** `--disabled-color`: an `ha-slider`'s track. */
    private fun sliderTrack(ctx: VitroCardContext) =
        themeColor(ctx, "ha-slider-track-color") ?: themeColor(ctx, "disabled-color")
            ?: if (ctx.theme.dark) 0xFF464646.toInt() else 0xFFBDBDBD.toInt()

    private fun text(ctx: VitroCardContext, cssPx: Float, color: Int, weight: Int = Inter.REGULAR): TextView =
        TextView(ctx.context).apply {
            val sp = ctx.scaledSp(cssPx)
            textSize = sp
            setTextColor(color)
            typeface = VitroFonts.typeface(ctx.context, weight, sp)
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

    // ---- plain buttons: script, scene, button, input_button, lock ----

    /**
     * `ha-button appearance="plain" size="s"`: a 14 px medium label 13 px in on both sides (two side by side have
     * 26 px between their labels), the last one 6.83 px past the row's content (`margin-inline-end: -0.57em`), so its
     * label ends 6.17 px inside it.
     */
    private fun plainButton(ctx: VitroCardContext): TextView = text(ctx, 14f, brandText(ctx), Inter.MEDIUM).apply {
        gravity = Gravity.CENTER
        val p = (13f * ctx.dp).roundToInt()
        setPadding(p, 0, p, 0)
        isClickable = true
        applyHaRipple(this, 999f, ctx.theme.dark)
        VitroAccess.role(this, A11yRole.BUTTON)
    }

    private fun buttonParams(ctx: VitroCardContext, last: Boolean) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ctx.px(32f)).apply {
            if (last) marginEnd = -(6.83f * ctx.dp).roundToInt()
        }

    private fun setEnabled(ctx: VitroCardContext, button: TextView, enabled: Boolean, color: Int) {
        button.isEnabled = enabled
        button.isClickable = enabled
        button.setTextColor(if (enabled) color else disabledText(ctx))
    }

    /** One button whose [label] and [press] are the domain's; disabled while the entity is unavailable. */
    private fun singleButton(
        ctx: VitroCardContext,
        row: EntityRowConfig,
        name: () -> String,
        label: (EntityState?) -> String,
        press: (EntityState) -> Unit,
    ): Control {
        val button = plainButton(ctx)
        var current: EntityState? = null
        // A disabled button doesn't press (HA's `.disabled`), even when clicked from code or TalkBack.
        button.setOnClickListener { current?.takeIf { it.state != "unavailable" }?.let(press) }
        return Control(button, update = { s ->
            current = s
            button.text = row.actionName?.takeIf { row.entity.startsWith("scene.") } ?: label(s)
            button.contentDescription = "${button.text} ${name()}".trim()
            setEnabled(ctx, button, s != null && s.state != "unavailable", brandText(ctx))
        }).also { button.layoutParams = buttonParams(ctx, last = true) }
    }

    /**
     * `hui-script-entity-row`: Cancel (danger, `Cancel 2` for a queued or parallel script with two runs) while it runs,
     * Run while it's off or can run again (`max`). Run opens More Info for a script with fields, as HA does, so they
     * can be filled in; else it turns the script on.
     */
    private fun scriptControl(ctx: VitroCardContext, row: EntityRowConfig, name: () -> String): Control {
        val box = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val cancel = plainButton(ctx).apply { setTextColor(dangerText(ctx)) }
        val run = plainButton(ctx)
        box.addView(cancel, buttonParams(ctx, last = false))
        box.addView(run, buttonParams(ctx, last = true))
        cancel.setOnClickListener {
            ctx.callService("script", "turn_off", JSONObject().put("entity_id", row.entity), null)
        }
        run.setOnClickListener { ctx.store[row.entity]?.takeIf { it.state != "unavailable" && canRun(it) }?.let { runScript(ctx, row.entity) } }
        return Control(box, update = { s ->
            val on = s?.state == "on"
            val maxRuns = (s?.attributes?.get("max") as? Number)?.toInt()
            val currentRuns = (s?.attributes?.get("current") as? Number)?.toInt() ?: 0
            val multiple = s?.attributes?.get("mode") != "single" && currentRuns > 0
            cancel.visibility = if (on) View.VISIBLE else View.GONE
            cancel.text = if (multiple) HaText.ui("ui.card.script.cancel_multiple", "number" to currentRuns)
                else HaText.ui("ui.card.script.cancel")
            cancel.contentDescription = "${cancel.text} ${name()}".trim()
            val showRun = s?.state == "off" || maxRuns != null
            run.visibility = if (showRun) View.VISIBLE else View.GONE
            run.text = row.actionName ?: HaText.ui("ui.card.script.run")
            run.contentDescription = "${run.text} ${name()}".trim()
            setEnabled(ctx, run, s != null && s.state != "unavailable" && canRun(s), brandText(ctx))
            // The last visible button reaches past the row's content.
            (cancel.layoutParams as LinearLayout.LayoutParams).marginEnd =
                if (showRun) 0 else -(6.83f * ctx.dp).roundToInt()
            cancel.requestLayout()
        })
    }

    /** HA's `canRun`: off, or running in `queued` / `parallel` mode below its `max`. */
    internal fun canRun(s: EntityState): Boolean {
        if (s.state == "off") return true
        val mode = s.attributes["mode"]
        val current = (s.attributes["current"] as? Number)?.toInt() ?: 0
        val maxRuns = (s.attributes["max"] as? Number)?.toInt() ?: return false
        return s.state == "on" && (mode == "queued" || mode == "parallel") && current < maxRuns
    }

    /**
     * Run, as `hui-script-entity-row` does: a script with fields ([scriptHasFields]) opens More Info ([onFields]; for a
     * script with fields that's HA's own dialog, with its form: [dev.glassworks.engine.vitro.ui.VitroView.openMoreInfo]),
     * else `script.turn_on`. A script whose fields Vitro couldn't learn (no answer from HA) never runs from here without
     * them: it goes the fields' way too.
     */
    internal fun runScript(ctx: VitroCardContext, entityId: String, onFields: () -> Unit = { ctx.openMoreInfo(entityId) }) {
        scriptHasFields(ctx, entityId) { fields ->
            if (fields == false) ctx.callService("script", "turn_on", JSONObject().put("entity_id", entityId), null)
            else onFields()
        }
    }

    /**
     * `get_services` answers by entity store (one per page), kept a minute. Only a "has fields" answer is taken from
     * it: a script reload can add fields, so "no fields", which runs the script directly, is always asked afresh.
     */
    private val servicesCache = java.util.WeakHashMap<dev.glassworks.engine.vitro.data.EntityStore, Pair<Long, JSONObject>>()
    private const val SERVICES_TTL_MS = 60_000L

    /**
     * HA's `hasScriptFields`: whether `script.<object id>` declares `fields` in `get_services`, on the main thread:
     * true or false, or null when Home Assistant didn't answer. False always comes from a fresh answer.
     */
    internal fun scriptHasFields(ctx: VitroCardContext, entityId: String, answer: (Boolean?) -> Unit) {
        val objectId = entityId.substringAfter('.')
        fun read(services: JSONObject): Boolean =
            (services.optJSONObject("script")?.optJSONObject(objectId)?.optJSONObject("fields")?.length() ?: 0) > 0
        val cached = synchronized(servicesCache) { servicesCache[ctx.store] }
        if (cached != null && System.currentTimeMillis() - cached.first < SERVICES_TTL_MS && read(cached.second)) {
            answer(true)
            return
        }
        ctx.callWs(JSONObject().put("type", "get_services")) { ok, result, _ ->
            val services = (result as? JSONObject)?.takeIf { ok }
            if (services != null) synchronized(servicesCache) { servicesCache[ctx.store] = System.currentTimeMillis() to services }
            ctx.main.post { answer(services?.let(::read)) }
        }
    }

    /**
     * `hui-lock-entity-row`: Unlock while locked, Lock otherwise. As HA's `callProtectedLockService`: a lock with a
     * `code_format` and no `default_code` in its entity registry options asks for the code first (HA's enter-code
     * dialog, titled and confirmed with the action), and the code goes with the call.
     */
    private fun lockControl(ctx: VitroCardContext, row: EntityRowConfig, name: () -> String): Control =
        singleButton(ctx, row, name, { s -> lockLabel(if (s?.state == "locked") "unlock" else "lock") }) { s ->
            val service = if (s.state == "locked") "unlock" else "lock"
            val send = { code: String? ->
                val data = JSONObject().put("entity_id", row.entity)
                if (code != null) data.put("code", code)
                ctx.callService("lock", service, data, null)
            }
            if (s.attributes["code_format"] == null) {
                send(null)
            } else {
                ctx.callWs(JSONObject().put("type", "config/entity_registry/get").put("entity_id", row.entity)) { ok, result, _ ->
                    val defaultCode = if (ok) (result as? JSONObject)?.optJSONObject("options")?.optJSONObject("lock")
                        ?.optString("default_code", "")?.takeIf { it.isNotEmpty() } else null
                    ctx.main.post { if (defaultCode != null) send(null) else askCode(ctx, service) { send(it) } }
                }
            }
        }

    /** `ui.card.lock.lock` / `ui.card.lock.unlock`: the button, and the code dialog's title and confirm button. */
    private fun lockLabel(service: String): String =
        if (service == "unlock") HaText.ui("ui.card.lock.unlock") else HaText.ui("ui.card.lock.lock")

    /** HA's `callProtectedLockService`: the code dialog's text version, titled and confirmed with the action. */
    private fun askCode(ctx: VitroCardContext, service: String, onCode: (String) -> Unit) =
        HaCodeDialog.ask(ctx, "text", lockLabel(service), lockLabel(service)) { onCode(it) }

    // ---- input_select / select: ha-select ----

    /**
     * `ha-select` (an `ha-picker-field`, `compact`) in the name's place: a 56 px field in `--ha-color-form-background`
     * with 4 px top corners and a 1 px bottom line, the name as a 10.5 px label over the value (14 px), both 16 px in,
     * and a 20 px `mdi:menu-down` in a 32 px box 8 px from the end. Unavailable, it's disabled at half opacity. A tap
     * lists the options; picking another calls `select_option` on the entity's domain.
     */
    private fun selectControl(ctx: VitroCardContext, row: EntityRowConfig, name: () -> String): Control {
        val field = SelectField(ctx)
        var current: EntityState? = null
        fun options(s: EntityState?): List<String> = (s?.attributes?.get("options") as? List<*>)?.mapNotNull { it?.toString() }.orEmpty()
        // `select` options are shown as HA formats them (`formatEntityState(stateObj, option)`); input_select's as written.
        fun label(s: EntityState, option: String): String =
            if (s.domain == "select") s.copy(state = option).formattedState() else option
        field.setOnClickListener {
            val s = current ?: return@setOnClickListener
            val opts = options(s)
            if (opts.isEmpty()) return@setOnClickListener
            runCatching {
                PopupMenu(ctx.context, field).apply {
                    opts.forEachIndexed { i, o -> menu.add(0, i, i, label(s, o)) }
                    setOnMenuItemClickListener { item -> choose(ctx, row.entity, opts[item.itemId]); true }
                }.show()
            }
        }
        return Control(field, hideName = true, rowHeightDp = 56f, update = { s ->
            current = s
            field.label.text = name()
            val opts = options(s)
            field.value.text = when {
                s == null -> ""
                s.state in opts -> label(s, s.state)
                else -> s.state
            }
            field.setDisabled(s == null || s.state == "unavailable")
            field.contentDescription = "${name()}, ${field.value.text}"
        })
    }

    /** HA's `_selectedChanged`: an option other than the current one, and among the options, is sent. */
    internal fun choose(ctx: VitroCardContext, entityId: String, option: String) {
        val s = ctx.store[entityId] ?: return
        val opts = (s.attributes["options"] as? List<*>)?.map { it?.toString() }.orEmpty()
        if (option == s.state || option !in opts) return
        ctx.callService(entityId.substringBefore('.'), "select_option", JSONObject().put("entity_id", entityId).put("option", option), null)
    }

    @SuppressLint("ViewConstructor")
    private class SelectField(private val ctx: VitroCardContext) : ViewGroup(ctx.context) {
        val label = text(ctx, 10.5f, labelText(ctx))
        val value = text(ctx, 14f, ctx.theme.primaryTextColor)
        private val arrow = MdiIconView(ctx.context).apply { setIcon("mdi:menu-down", ctx.theme.secondaryTextColor) }
        private val fill = GradientDrawable()
        private val linePaint = Paint().apply { style = Paint.Style.FILL }
        private var disabled = false

        init {
            setWillNotDraw(false)
            isClickable = true
            addView(label)
            addView(value)
            addView(arrow)
            val r = 4f * ctx.dp
            fill.cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            background = fill
            applyHaRipple(this, r, ctx.theme.dark)
            VitroAccess.role(this, A11yRole.BUTTON)
            setDisabled(false)
        }

        fun setDisabled(d: Boolean) {
            disabled = d
            isEnabled = !d
            isClickable = !d
            fill.setColor(if (d) formBackgroundDisabled(ctx) else formBackground(ctx))
            linePaint.color = fieldLine(ctx)
            alpha = if (d) 0.5f else 1f
            // ha-combo-box-item's own `--ha-combo-box-item-disabled-opacity: 0.5`, inside the field's half opacity.
            label.alpha = if (d) 0.5f else 1f
            value.alpha = if (d) 0.5f else 1f
            invalidate()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val inner = max(0, w - ctx.px(16f) - ctx.px(8f + 32f + 8f))
            val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            label.measure(MeasureSpec.makeMeasureSpec(inner, MeasureSpec.AT_MOST), unspecified)
            value.measure(MeasureSpec.makeMeasureSpec(inner, MeasureSpec.AT_MOST), unspecified)
            val icon = MeasureSpec.makeMeasureSpec(ctx.px(20f), MeasureSpec.EXACTLY)
            arrow.measure(icon, icon)
            setMeasuredDimension(w, ctx.px(56f))
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val start = ctx.px(16f)
            // HA's label line (13 px) starts 9.55 px down, the value's (16 px) 27.64 px: centered on 16.05 and 35.64.
            fun place(v: View, centerCss: Float) {
                val top = (centerCss * ctx.dp - v.measuredHeight / 2f).roundToInt()
                v.layout(start, top, start + v.measuredWidth, top + v.measuredHeight)
            }
            place(label, 16.05f)
            place(value, 35.64f)
            val iconSize = arrow.measuredWidth
            val boxEnd = width - ctx.px(8f)
            val left = boxEnd - ctx.px(32f) + (ctx.px(32f) - iconSize) / 2
            val top = (height - iconSize) / 2
            arrow.layout(left, top, left + iconSize, top + iconSize)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            // `.field:after`: the 1 px bottom line.
            canvas.drawRect(0f, height - max(1f, ctx.dp), width.toFloat(), height.toFloat(), linePaint)
        }
    }

    // ---- input_number / number: ha-slider or ha-input ----

    /** HA's `showNumberSlider`: `slider` mode, or `auto` with at most 256 steps; `box` and the rest are a box. */
    internal fun showsSlider(s: EntityState?): Boolean {
        if (s == null) return true
        val mode = s.attributes["mode"]
        if (s.domain == "input_number") return mode == "slider"
        if (mode == "slider") return true
        if (mode != "auto") return false
        val lo = (s.attributes["min"] as? Number)?.toDouble() ?: return false
        val hi = (s.attributes["max"] as? Number)?.toDouble() ?: return false
        val step = (s.attributes["step"] as? Number)?.toDouble() ?: return false
        return (hi - lo) / step <= 256
    }

    /**
     * The control of a number's state row in HA's More Info (`state-card-number`, `state-card-input_number`): the
     * slider and its state, or the box, in the `.flex` that takes the row's width after `state-info`. [flexWidthCss]
     * is that width (CSS px), [rowWidthCss] the row's (HA hides the state in a row 300 px wide or narrower).
     */
    fun stateCardNumber(ctx: VitroCardContext, entityId: String, name: () -> String, rowWidthCss: () -> Float, flexWidthCss: () -> Float): Control =
        numberControl(ctx, EntityRowConfig(entity = entityId), name, { (rowWidthCss() * ctx.dp).roundToInt() }, flexWidthCss)

    /**
     * A number row's control: a slider row or a box, by the entity's mode (an entity that changes mode swaps them).
     * [stateCardFlex]: the state card's `.flex` width in More Info, in place of the entities row's flex sharing.
     */
    private fun numberControl(
        ctx: VitroCardContext,
        row: EntityRowConfig,
        name: () -> String,
        rowContentWidth: () -> Int,
        stateCardFlex: (() -> Float)? = null,
    ): Control {
        val slider = SliderRow(ctx, row.entity, name, rowContentWidth, stateCardFlex)
        val box = NumberBox(ctx, row.entity, name)
        val holder = object : android.widget.FrameLayout(ctx.context) {}
        holder.addView(slider, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL or Gravity.END))
        holder.addView(box, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END))
        val control = Control(holder, update = { s ->
            val asSlider = showsSlider(s)
            slider.visibility = if (asSlider) View.VISIBLE else View.GONE
            box.visibility = if (asSlider) View.GONE else View.VISIBLE
            // `ha-input`'s field is 56 px, with its 8 px bottom padding under it: a box row is 64 px tall.
            val lp = holder.layoutParams
            if (lp != null) {
                val want = if (asSlider) ViewGroup.LayoutParams.WRAP_CONTENT else ctx.px(64f)
                if (lp.height != want) { lp.height = want; holder.layoutParams = lp }
            }
            if (asSlider) slider.bind(s) else box.bind(s)
        })
        return control
    }

    private fun numberAttr(s: EntityState?, key: String): Double? =
        (s?.attributes?.get(key) as? Number)?.toDouble() ?: (s?.attributes?.get(key) as? String)?.toDoubleOrNull()

    /** The value HA's `setValue` sends: a whole number without a fraction. */
    private fun jsonNumber(v: Double): Any = if (v == Math.floor(v) && abs(v) < 1e15) v.roundToLong() else v

    private fun setValue(ctx: VitroCardContext, entityId: String, value: Double) {
        val s = ctx.store[entityId]
        val next = s?.copy(state = jsNumberText(value))
        ctx.callOptimistic(next, entityId.substringBefore('.'), "set_value",
            JSONObject().put("entity_id", entityId).put("value", jsonNumber(value)))
    }

    /** JavaScript's `Number(x).toString()` for the values a number entity holds (`16.5`, `15`, `-1.25`). */
    internal fun jsNumberText(v: Double): String = when {
        v.isNaN() -> "NaN"
        v.isInfinite() -> if (v > 0) "Infinity" else "-Infinity"
        v == 0.0 -> "0"
        abs(v) >= 1e21 || abs(v) < 1e-6 -> v.toString()
        else -> BigDecimal(v.toString()).stripTrailingZeros().toPlainString()
    }

    /**
     * The slider and the state (`.flex`: `flex-grow: 2`) after the name (`.info`: `flex: 1 1 30%`, 16 + 8 px padding),
     * laid out as Chrome lays out that flex row: the slider's basis is its 100 px `min-width` plus its 8 px margins, the
     * state's its width (at least 45 px), and the space left over goes one third to the name and two thirds here. The
     * slider takes what the state leaves, up to 200 px, at the end. HA hides the state when the row is 300 px or
     * narrower.
     */
    @SuppressLint("ViewConstructor")
    private class SliderRow(
        private val ctx: VitroCardContext,
        private val entityId: String,
        private val name: () -> String,
        private val rowContentWidth: () -> Int,
        private val stateCardFlex: (() -> Float)? = null,
    ) : ViewGroup(ctx.context) {
        val state = text(ctx, 14f, ctx.theme.primaryTextColor).apply { gravity = Gravity.END }
        val slider = HaRowSlider(ctx) { v -> setValue(ctx, entityId, v) }

        init {
            addView(slider)
            addView(state)
        }

        fun bind(s: EntityState?) {
            val noValue = s == null || s.state == "unavailable" || s.state == "unknown"
            // hui-number-entity-row shows a dash for no value; hui-input-number-entity-row HA's state text.
            state.text = when {
                s == null -> ""
                // The state card writes HA's state text for every value.
                s.domain == "number" && noValue && stateCardFlex == null -> "—"
                else -> s.formattedState()
            }
            slider.bind(s, name())
            requestLayout()
        }

        private fun widthCss(): Float = rowContentWidth() / ctx.dp

        private fun stateShown(): Boolean = widthCss() > 300f

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            state.measure(unspecified, unspecified)
            val w = widthCss()
            val stateW = if (stateShown()) max(45f, state.measuredWidth / ctx.dp) else 0f
            val basis = 100f + 16f + stateW
            val info = 0.3f * w + 24f
            val free = w - 40f - info - basis
            // The state card's `.flex` takes what `state-info` leaves; the slider's 100 px minimum still holds.
            val flexW = stateCardFlex?.invoke()?.coerceAtLeast(basis) ?: if (free > 0f) basis + free * 2f / 3f else basis
            // The state's box is at least 45 px, its text at the end of it (`text-align: end`).
            if (stateW > 0f) state.measure(MeasureSpec.makeMeasureSpec((stateW * ctx.dp).roundToInt(), MeasureSpec.EXACTLY), unspecified)
            slider.measure(
                MeasureSpec.makeMeasureSpec(sliderWidthPx(flexW, stateW), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(ctx.px(32f), MeasureSpec.EXACTLY),
            )
            setMeasuredDimension((flexW * ctx.dp).roundToInt(), max(ctx.px(32f), state.measuredHeight))
        }

        /** The slider view: the track (flex width less the state and the margins, at most 200 px) and 8 px each side for the thumb. */
        private fun sliderWidthPx(flexW: Float, stateW: Float): Int {
            // The state card's slider has no side margins: its track takes the `.flex` up to the state.
            val margins = if (stateCardFlex != null) 0f else 16f
            return ((min(200f, max(0f, flexW - margins - stateW)) + 16f) * ctx.dp).roundToInt()
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val w = r - l
            val h = b - t
            val shown = stateShown()
            state.visibility = if (shown) View.VISIBLE else View.GONE
            val stateW = if (shown) state.measuredWidth else 0
            if (shown) {
                val top = (h - state.measuredHeight) / 2
                state.layout(w - stateW, top, w, top + state.measuredHeight)
            }
            val sw = slider.measuredWidth
            val sTop = (h - slider.measuredHeight) / 2
            // The view reaches 8 px past the track for the thumb; in the state card the track ends at the state.
            val shift = if (stateCardFlex != null) ctx.px(8f) else 0
            slider.layout(w - stateW - sw + shift, sTop, w - stateW + shift, sTop + slider.measuredHeight)
        }
    }

    /**
     * `ha-slider` (Web Awesome's slider, `size="s"`): a 4 px rounded track in `--disabled-color`, the indicator in
     * `--primary-color` from the start to the value, and a 16 px thumb in `--primary-color` centered on the value (the
     * view reaches 8 px past the track on both sides for it). Dragging moves it in steps; letting go sends the value
     * (`set_value`, as HA's `change` event does). Disabled (unavailable), it's drawn at half opacity and doesn't move.
     */
    @SuppressLint("ViewConstructor")
    class HaRowSlider(private val ctx: VitroCardContext, private val onCommit: (Double) -> Unit) : View(ctx.context) {
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val rect = RectF()
        private var lo = 0.0
        private var hi = 100.0
        private var step = 1.0
        private var bound = 0.0
        /** The value shown: HA's, or the one being dragged or keyed in. */
        var value = 0.0
            private set
        private var dragging = false
        private var disabled = false

        init {
            SliderAccess.install(this)
        }

        fun bind(s: EntityState?, label: String) {
            lo = numberAttr(s, "min") ?: 0.0
            hi = numberAttr(s, "max") ?: 100.0
            step = numberAttr(s, "step")?.takeIf { it > 0.0 } ?: 1.0
            disabled = s == null || s.state == "unavailable"
            bound = s?.state?.toDoubleOrNull() ?: lo
            contentDescription = label
            isEnabled = !disabled
            alpha = if (disabled) 0.5f else 1f
            if (!dragging && !access.editing) {
                value = bound
                invalidate()
            }
        }

        private val steps: Int get() = max(0, ((hi - lo) / step).roundToInt())
        private fun index(v: Double): Int = ((v - lo) / step).roundToInt().coerceIn(0, steps)
        private fun at(i: Int): Double = snap(lo + i * step)

        /** The value at step [i], rounded as the slider's decimal steps would be (`0.1 + 0.2` is `0.3`). */
        private fun snap(v: Double): Double {
            // The step's decimals and the minimum's: `min: 0.5` with `step: 1` moves through 0.5, 1.5, 2.5.
            fun scale(d: Double) = BigDecimal(d.toString()).stripTrailingZeros().scale().coerceAtLeast(0)
            val decimals = maxOf(scale(step), scale(lo))
            return BigDecimal(v).setScale(decimals, java.math.RoundingMode.HALF_UP).toDouble().coerceIn(min(lo, hi), max(lo, hi))
        }

        private val fraction: Float get() = if (hi > lo) ((value - lo) / (hi - lo)).toFloat().coerceIn(0f, 1f) else 0f

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val inset = 8f * ctx.dp
            val left = inset
            val right = width - inset
            val track = 4f * ctx.dp
            val cy = height / 2f
            val radius = track / 2f
            trackPaint.color = sliderTrack(ctx)
            fillPaint.color = ctx.theme.primaryColor
            rect.set(left, cy - track / 2f, right, cy + track / 2f)
            canvas.drawRoundRect(rect, radius, radius, trackPaint)
            val x = left + (right - left) * fraction
            rect.set(left, cy - track / 2f, x, cy + track / 2f)
            canvas.drawRoundRect(rect, radius, radius, fillPaint)
            canvas.drawCircle(x, cy, 8f * ctx.dp, fillPaint)
        }

        private fun dragTo(x: Float) {
            val inset = 8f * ctx.dp
            val f = ((x - inset) / max(1f, width - 2 * inset)).coerceIn(0f, 1f)
            value = at(index(lo + f * (hi - lo)))
            invalidate()
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (disabled) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    dragging = true
                    dragTo(event.x)
                }
                MotionEvent.ACTION_MOVE -> dragTo(event.x)
                MotionEvent.ACTION_UP -> {
                    dragTo(event.x)
                    dragging = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    if (value != bound) onCommit(value)
                    VitroAccess.valueChanged(this)
                }
                MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    value = bound
                    invalidate()
                }
            }
            return true
        }

        /** TalkBack and keys move it by the entity's steps; TalkBack reads the value as HA formats a number. */
        private val access = AdjustableAccess(
            min = { 0 }, max = { steps }, step = { 1 },
            value = { index(value) },
            preview = { i -> value = at(i); invalidate(); VitroAccess.valueChanged(this) },
            commit = { i -> if (at(i) != bound) onCommit(at(i)) },
            restore = { value = bound; invalidate() },
            valueText = { i -> HaFormat.formatNumber(at(i)) },
        )

        override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            if (!disabled) access.onInitialize(info)
        }

        override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean =
            (if (disabled) null else access.perform(action, arguments)) ?: super.performAccessibilityAction(action, arguments)

        override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
            (!disabled && access.keyDown(keyCode)) || super.onKeyDown(keyCode, event)

        override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
            (!disabled && access.keyUp(event)) || super.onKeyUp(keyCode, event)

        override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
            super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
            if (!gainFocus) access.cancel()
        }
    }

    /**
     * `ha-input type="number"` in box mode: a 56 px field like the dropdown's, the value (JavaScript's
     * `Number(state).toString()`, 14 px) right-aligned in an input as wide as Chrome makes a number input for the
     * entity's `min`, `max` and `step` (7 px a character plus 5 for its digits, sign, point and decimals), then the
     * unit 15.66 px after it in the end slot (27.66 in the light theme), 16 px in from both edges. Editing it and pressing Done (or leaving the
     * field) sends the value with `set_value` when it changed.
     */
    @SuppressLint("ViewConstructor")
    private class NumberBox(private val ctx: VitroCardContext, private val entityId: String, private val name: () -> String) : ViewGroup(ctx.context) {
        val input = EditText(ctx.context).apply {
            background = null
            setPadding(0, 0, 0, 0)
            val sp = ctx.scaledSp(14f)
            textSize = sp
            typeface = VitroFonts.typeface(ctx.context, Inter.REGULAR, sp)
            includeFontPadding = false
            setTextColor(ctx.theme.primaryTextColor)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        val unit = text(ctx, 14f, unitText(ctx))
        private val fill = GradientDrawable()
        private val linePaint = Paint().apply { style = Paint.Style.FILL; color = fieldLine(ctx) }
        private var chars = 20
        private var shown = ""

        /** The user typed in the field since it last showed [shown]: only then does leaving it send a value. */
        private var edited = false
        private var settingText = false

        init {
            setWillNotDraw(false)
            addView(input)
            addView(unit)
            val r = 4f * ctx.dp
            fill.cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            fill.setColor(formBackground(ctx))
            input.setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_DONE) { commit(); input.clearFocus() }
                false
            }
            input.setOnFocusChangeListener { _, focused -> if (!focused) commit() }
            input.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(t: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(t: CharSequence?, start: Int, before: Int, count: Int) { if (!settingText) edited = true }
                override fun afterTextChanged(e: android.text.Editable?) = Unit
            })
        }

        private fun show(text: String) {
            settingText = true
            try { input.setText(text) } finally { settingText = false }
            edited = false
        }

        /**
         * Done, or the field left: a value the user typed is sent once. An untouched field sends nothing and shows the
         * latest value (HA may have changed it while the field had focus).
         */
        private fun commit() {
            if (!edited) {
                if (input.text?.toString() != shown) show(shown)
                return
            }
            edited = false
            val typed = input.text?.toString()?.trim().orEmpty()
            val s = ctx.store[entityId] ?: return
            if (typed.isEmpty() || typed == shown) return
            val v = typed.toDoubleOrNull() ?: return
            if (typed != s.state) setValue(ctx, entityId, v)
        }

        fun bind(s: EntityState?) {
            chars = inputChars(s)
            unit.text = s?.unit.orEmpty()
            val disabled = s == null || s.state == "unavailable"
            shown = jsNumberText(s?.state?.toDoubleOrNull() ?: Double.NaN)
            if (!input.hasFocus()) show(shown)
            input.isEnabled = !disabled
            input.contentDescription = name()
            alpha = if (disabled) 0.5f else 1f
            fill.setColor(if (disabled) formBackgroundDisabled(ctx) else formBackground(ctx))
            requestLayout()
            invalidate()
        }

        /**
         * From the input's end to the unit: 15.66 px, and 12 more in the light theme, where Chrome lays out the number
         * input's spin buttons there (measured on every frame by `entities-number` and `entities-controls-light`).
         */
        private fun unitGap(): Float = if (ctx.theme.dark) 15.66f else 27.66f

        private fun inputWidthPx(): Int = ((7f * chars + 5f) * ctx.dp * ctx.theme.fontScale).roundToInt()

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            unit.measure(unspecified, unspecified)
            val h = ctx.px(56f)
            input.measure(MeasureSpec.makeMeasureSpec(inputWidthPx(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
            val w = (16f * ctx.dp + inputWidthPx() + unitGap() * ctx.dp + unit.measuredWidth + 16f * ctx.dp).roundToInt()
            setMeasuredDimension(w, ctx.px(64f))
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val h = ctx.px(56f)
            val start = ctx.px(16f)
            input.layout(start, 0, start + input.measuredWidth, h)
            val uEnd = (r - l) - ctx.px(16f)
            val uTop = (h - unit.measuredHeight) / 2
            unit.layout(uEnd - unit.measuredWidth, uTop, uEnd, uTop + unit.measuredHeight)
        }

        override fun onDraw(canvas: Canvas) {
            val h = ctx.px(56f)
            fill.setBounds(0, 0, width, h)
            fill.draw(canvas)
            canvas.drawRect(0f, h - max(1f, ctx.dp), width.toFloat(), h.toFloat(), linePaint)
            super.onDraw(canvas)
        }
    }

    /**
     * The characters Chrome sizes a number input for (`NumberInputType::SizeShouldIncludeDecoration`): the longer of
     * `min` and `max` in digits (a minus sign counts), and, with decimals in `step`, `min` or `max`, the point and
     * those decimals. Without a finite `min` and `max`, an input's default 20.
     */
    internal fun inputChars(s: EntityState?): Int {
        val lo = numberAttr(s, "min")?.takeIf { it.isFinite() } ?: return 20
        val hi = numberAttr(s, "max")?.takeIf { it.isFinite() } ?: return 20
        val step = numberAttr(s, "step")?.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
        fun digits(v: Double): Pair<Int, Int> {
            val text = BigDecimal(jsNumberText(v)).toPlainString()
            val sign = if (text.startsWith("-")) 1 else 0
            val body = text.removePrefix("-")
            val intDigits = body.substringBefore('.').length
            val fraction = if ('.' in body) body.substringAfter('.').length else 0
            return (sign + intDigits) to fraction
        }
        val (loInt, loFrac) = digits(lo)
        val (hiInt, hiFrac) = digits(hi)
        val stepFrac = digits(step).second
        val fraction = maxOf(loFrac, hiFrac, stepFrac)
        return max(loInt, hiInt) + if (fraction > 0) fraction + 1 else 0
    }

    // ---- secondary_info ----

    /**
     * What `secondary_info` shows under a row's name, as HA's entities card migrates it (`last-changed` to
     * `last_changed`, `last-triggered` to `last_triggered`, `position` to `current_position`, `tilt-position` to
     * `current_tilt_position`, `area` to `area_name`) and `state-display` draws that content: a relative time, the
     * entity id, the entity's area (formatEntityName's area part: its own area, else its device's), or the
     * attribute as HA formats it (a light's brightness and a position as a percentage). A content with nothing to show
     * (no such attribute, a light that's off, a cover at 0) falls back to the entity's state, as `state-display` does.
     * Null: no secondary line (`none`, or no `secondary_info`).
     */
    fun secondaryText(s: EntityState?, secondaryInfo: String?, nowMs: Long): String? {
        val content = when (secondaryInfo) {
            null, "", "none" -> return null
            "last-changed" -> "last_changed"
            "last-updated" -> "last_updated"
            "last-triggered" -> "last_triggered"
            "position" -> "current_position"
            "tilt-position" -> "current_tilt_position"
            "area" -> "area_name"
            else -> secondaryInfo
        }
        if (s == null) return ""
        fun relative(epochSec: Double): String =
            HaFormat.capitalize(HaFormat.relativeTime((epochSec * 1000).toLong(), nowMs))
        when (content) {
            "entity-id" -> return s.entityId
            // A name part, never an attribute: no area is the state.
            "area_name" -> return dev.glassworks.engine.vitro.data.HaEntityName
                .format(org.json.JSONObject().put("type", "area"), s.entityId, s.friendlyName)
                ?.takeIf { it.isNotEmpty() } ?: s.formattedState()
            "last_changed" -> if (s.lastChanged > 0.0) return relative(s.lastChanged)
            "last_updated" -> (s.lastUpdated.takeIf { it > 0.0 } ?: s.lastChanged).let { if (it > 0.0) return relative(it) }
            "last_triggered" -> {
                val ts = s.attributes["last_triggered"]?.let { dev.glassworks.engine.vitro.data.EntityStore.parseTimestamp(it) } ?: 0.0
                if (ts > 0.0) return relative(ts)
            }
        }
        val attr = s.attributes[content]
        val hiddenZero = (content == "current_position" && s.domain in setOf("cover", "valve")) ||
            (content == "brightness" && s.domain == "light") || (content == "percentage" && s.domain == "fan")
        val number = (attr as? Number)?.toDouble()
        if (attr == null || (hiddenZero && (number == null || number == 0.0))) return s.formattedState()
        val percent = "${HaFormat.blankBeforePercent(HaLocale.current)}%"
        return when {
            content == "brightness" && s.domain == "light" && number != null ->
                HaFormat.formatNumber((number / 255.0 * 100.0).roundToInt()) + percent
            (content == "current_position" || content == "current_tilt_position") && number != null ->
                HaFormat.formatNumber(number) + percent
            number != null -> HaFormat.formatNumber(number)
            else -> attr.toString()
        }
    }

    /** The secondary_info values that show a relative time, refreshed every second while the row is shown. */
    val RELATIVE_SECONDARY_INFO: Set<String> = setOf("last-changed", "last-updated", "last-triggered")

    /** The secondary_info values [secondaryText] draws as HA does. */
    val SECONDARY_INFO: Set<String> = setOf(
        "none", "last-changed", "last-updated", "last-triggered", "entity-id", "brightness", "position", "tilt-position", "area",
    )
}
