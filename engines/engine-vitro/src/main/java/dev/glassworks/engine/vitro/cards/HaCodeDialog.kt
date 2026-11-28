/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.cards

import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.glassworks.engine.vitro.data.HaText
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.A11yRole
import dev.glassworks.engine.vitro.ui.CardSurface
import dev.glassworks.engine.vitro.ui.MdiIconView
import dev.glassworks.engine.vitro.ui.VitroAccess
import dev.glassworks.engine.vitro.ui.VitroCardContext
import dev.glassworks.engine.vitro.ui.VitroFonts
import dev.glassworks.ui.Inter
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * HA's `showEnterCodeDialog` (`dialog-enter-code`, frontend 20261028.5), as the alarm and lock controls open it
 * (`setProtectedAlarmControlPanelMode`, `callProtectedLockService`): an `ha-adaptive-dialog` titled with the action,
 * holding a masked `ha-input` labelled "Code".
 *
 * - **A `number` code** (any format but `text`): the field has HA's show/hide toggle, and under it HA's keypad of
 *   `ha-control-button`s, 56 px round (`--ha-border-radius-4xl`), 24 px apart in a 12 px padded grid, digits at
 *   `--ha-font-size-2xl`: 1–9, then a red Clear (`mdi:close`, disabled until something is typed), 0 and a green
 *   Submit (`mdi:check`). On a window at most 450 px tall the grid has six columns, Clear ending the first row. There
 *   is no footer: the keypad's Submit, Enter in the field, or the header's Close (cancel).
 * - **A `text` code**: the field alone, and the footer's Cancel and Submit (`ha-button`s: plain, then filled).
 *
 * `ha-adaptive-dialog` decides the frame as the More Info sheet does ([MoreInfoSheet]): a 320 px dialog
 * (`width="small"`) centred over the scrim on a window wider than 870 and taller than 500 CSS px, else a bottom sheet
 * the window's width, as tall as its content (at most 90 % of the window, and 48 px under its top). The field takes
 * focus only where HA's `autofocus` does (not on a window at most 450 px wide or 500 px tall), so a wall panel's
 * keyboard stays down and the keypad is what a finger uses.
 *
 * As HA's: [onCode] runs once on Submit with what was typed (an empty code too: HA sends it and lets the integration
 * refuse it), [onCancel] once on Close, the scrim, Back or Cancel. The code lives only in the dialog, which forgets
 * it when it closes.
 */
internal object HaCodeDialog {
    /** The last dialog shown, for tests. */
    @Volatile internal var lastShown: Dialog? = null

    /** The last dialog's content, for tests. */
    @Volatile internal var lastView: EnterCodeView? = null

    /**
     * Opens the dialog over everything (the More Info sheet included, as HA's sits above other dialogs). [title] heads
     * it; [submitText] names Submit (HA's `ui.common.submit` when null).
     */
    fun ask(
        ctx: VitroCardContext,
        codeFormat: String?,
        title: String,
        submitText: String? = title,
        onCancel: () -> Unit = {},
        onCode: (String) -> Unit,
    ) {
        var settled = false
        lateinit var dialog: Dialog
        fun settle(code: String?) {
            if (settled) return
            settled = true
            if (code != null) onCode(code) else onCancel()
            runCatching { if (dialog.isShowing) dialog.dismiss() }
        }
        val shown = runCatching {
            dialog = Dialog(ctx.context, android.R.style.Theme_Translucent_NoTitleBar)
            val view = EnterCodeView(ctx, numeric = codeFormat != "text", title = title, submitText = submitText,
                onSubmit = { settle(it) }, onCancel = { settle(null) })
            dialog.setContentView(view)
            dialog.setOnCancelListener { settle(null) }
            dialog.window?.let { w ->
                w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                w.setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                        if (view.autofocus && codeFormat == "text") WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
                        else WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN,
                )
            }
            dialog.show()
            lastShown = dialog
            lastView = view
        }
        if (shown.isFailure) settle(null)
    }
}

/**
 * The content of [HaCodeDialog]: the scrim and HA's dialog (or sheet) on it. [numeric]: the keypad version; else the
 * text version with its footer.
 */
@SuppressLint("ViewConstructor")
internal class EnterCodeView(
    private val ctx: VitroCardContext,
    val numeric: Boolean,
    title: String,
    submitText: String?,
    private val onSubmit: (String) -> Unit,
    private val onCancel: () -> Unit,
) : FrameLayout(ctx.context) {
    private val theme = ctx.theme
    private val config = ctx.context.resources.configuration

    /** `ha-adaptive-dialog`'s dialog mode: not `(max-width: 870px), (max-height: 500px)`. */
    val wide = config.screenWidthDp > SHEET_MAX_WIDTH && config.screenHeightDp > SHEET_MAX_HEIGHT

    /** `dialog-enter-code`'s `_narrow`: `(max-width: 450px), (max-height: 500px)`, where the field isn't focused. */
    val autofocus = !(config.screenWidthDp <= 450 || config.screenHeightDp <= 500)

    /** The keypad's columns: six on a window at most 450 px tall, else three. */
    val columns = if (config.screenHeightDp <= 450) 6 else 3

    /** The masked field, as typed or keyed. */
    val codeInput: EditText = EditText(ctx.context)
    private val label = cssText(14f, labelColor())
    private val eye = MdiIconView(ctx.context)
    private var showing = false

    /** The keypad's keys by value (`1`…`0`, `clear`, `submit`); empty for a text code. */
    val keys = LinkedHashMap<String, View>()

    /** The footer's buttons (a text code only). */
    var cancelButton: View? = null
        private set
    var submitButton: View? = null
        private set

    /** The header's Close. */
    val closeButton: View

    /** The dialog (or sheet) itself, without the scrim. */
    val panel = CappedColumn()

    init {
        background = ColorDrawable(ThemeResolver.parseColor(theme.vars["mdc-dialog-scrim-color"]) ?: SCRIM)
        isClickable = true
        setOnClickListener { onCancel() }
        VitroAccess.notAControl(this)

        val dp = ctx.dp
        // `--ha-border-radius-3xl` on the dialog, `--ha-border-radius-2xl` on the sheet's top corners.
        val radius = (if (wide) 24f else 20f) * dp
        panel.apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            VitroAccess.notAControl(this)
            background = GradientDrawable().apply {
                setColor(dialogSurface(theme))
                cornerRadii = if (wide) FloatArray(8) { radius } else floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            }
            clipToOutline = true
            if (!wide) handle = GradientDrawable().apply {
                cornerRadius = 8f * dp
                setColor(theme.dividerColor)
            }
        }

        // ha-dialog-header: a 48 px row (`min-height: --ha-space-12`), 4 px at the sides (8 px on a window at least
        // 450 × 500), the close button, then the title (20 px / 500, 10 px × 4 px around it).
        val header = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val side = ctx.px(if (config.screenWidthDp >= 450 && config.screenHeightDp >= 500) 8f else 4f)
            setPadding(side, 0, side, 0)
        }
        closeButton = FrameLayout(ctx.context).apply {
            addView(MdiIconView(ctx.context).apply { setIcon("mdi:close", theme.primaryTextColor) }, LayoutParams(ctx.px(24f), ctx.px(24f), Gravity.CENTER))
            applyHaRipple(this, 999f * dp, theme.dark)
            VitroAccess.role(this, A11yRole.BUTTON, HaText.ui("ui.common.close").ifEmpty { "Close" })
            setOnClickListener { onCancel() }
        }
        header.addView(closeButton, LinearLayout.LayoutParams(ctx.px(48f), ctx.px(48f)))
        val titleView = cssText(20f, theme.primaryTextColor, Inter.MEDIUM).apply {
            text = title
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(ctx.px(4f), ctx.px(10f), ctx.px(4f), ctx.px(10f))
        }
        header.addView(titleView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(header, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, ctx.px(48f)))

        // The dialog's body: `0 --ha-space-6 --ha-space-6`.
        val body = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ctx.px(24f), 0, ctx.px(24f), ctx.px(24f))
        }
        body.addView(codeField(), LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, ctx.px(56f)))
        if (numeric) {
            body.addView(keypad(submitText), LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = ctx.px(12f)
            })
        }
        val scroll = ScrollView(ctx.context).apply {
            isVerticalScrollBarEnabled = false
            addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        panel.addView(scroll, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, 1f))
        if (!numeric) panel.addView(footer(submitText), LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        if (wide) {
            // `min(var(--ha-dialog-width-sm, 320px), 95vw)`, centred; at most the window less 80 px tall.
            val w = min(ctx.px(320f), (ctx.context.resources.displayMetrics.widthPixels * 0.95f).toInt())
            panel.capBelow = ctx.px(80f)
            addView(panel, LayoutParams(w, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        } else {
            // ha-bottom-sheet: `min(90vh, 100vh - 48px)`.
            panel.capFraction = 0.9f
            panel.capBelow = ctx.px(48f)
            addView(panel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        }
        if (autofocus) codeInput.requestFocus() else closeButton.requestFocus()
        updateClear()
    }

    /** Back and Escape close it, as Escape closes HA's dialog. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (event.action == KeyEvent.ACTION_UP) onCancel()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** What the field holds now. */
    val code: String get() = codeInput.text?.toString().orEmpty()

    /** The keypad's digit: `_numberClick`, appended to what's in the field. */
    fun press(value: String) {
        when (value) {
            "clear" -> codeInput.setText("")
            "submit" -> onSubmit(code)
            else -> codeInput.append(value)
        }
    }

    // ---- ha-input ----

    /**
     * `ha-input` (material): 56 px in `--ha-color-form-background` with 4 px top corners and a 1 px bottom line in
     * `--ha-color-border-neutral-loud` (2 px in the primary color while focused). Its label sits 20 px down at 14 px,
     * and rises to 12 px, 12 px down, once there's a value or focus; the value is masked. [numeric] adds the show/hide
     * button at the end.
     */
    private fun codeField(): View {
        val box = object : FrameLayout(ctx.context) {
            private val line = Paint().apply { style = Paint.Style.FILL }

            init { setWillNotDraw(false) }

            override fun dispatchDraw(canvas: Canvas) {
                super.dispatchDraw(canvas)
                val focused = codeInput.isFocused
                line.color = if (focused) theme.primaryColor else fieldLine()
                val h = (if (focused) 2f else 1f) * ctx.dp
                canvas.drawRect(0f, height - max(h, 1f), width.toFloat(), height.toFloat(), line)
            }
        }
        val r = 4f * ctx.dp
        box.background = GradientDrawable().apply {
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            setColor(formBackground())
        }
        label.text = HaText.ui("ui.dialogs.enter_code.input_label").ifEmpty { "Code" }
        label.maxLines = 1
        box.addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = ctx.px(16f) })
        codeInput.apply {
            background = null
            setSingleLine(true)
            setTextColor(theme.primaryTextColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ctx.scaledSp(16f))
            typeface = VitroFonts.typeface(ctx.context, Inter.REGULAR, ctx.scaledSp(16f))
            includeFontPadding = false
            setPadding(ctx.px(16f), ctx.px(24f), ctx.px(if (numeric) 48f else 16f), ctx.px(6f))
            inputType = codeInputType()
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            contentDescription = label.text
            setOnEditorActionListener { _, action, event ->
                val enter = action == EditorInfo.IME_ACTION_DONE || (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                if (enter) onSubmit(code)
                enter
            }
            setOnFocusChangeListener { _, _ -> placeLabel(); box.invalidate() }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    placeLabel()
                    updateClear()
                }
            })
        }
        box.addView(codeInput, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        if (numeric) {
            val toggle = FrameLayout(ctx.context).apply {
                addView(eye, LayoutParams(ctx.px(24f), ctx.px(24f), Gravity.CENTER))
                applyHaRipple(this, 999f * ctx.dp, theme.dark)
                VitroAccess.role(this, A11yRole.BUTTON)
                setOnClickListener {
                    showing = !showing
                    val at = codeInput.selectionEnd
                    codeInput.inputType = codeInputType()
                    codeInput.setSelection(at.coerceIn(0, codeInput.length()))
                    paintEye(this)
                }
            }
            paintEye(toggle)
            box.addView(toggle, LayoutParams(ctx.px(40f), ctx.px(40f), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = ctx.px(8f) })
        }
        placeLabel()
        return box
    }

    private fun codeInputType(): Int = when {
        numeric && showing -> InputType.TYPE_CLASS_NUMBER
        numeric -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }

    /** ha-input's toggle: `mdi:eye` to show the code, `mdi:eye-off` to hide it, named for what it does. */
    private fun paintEye(button: View) {
        eye.setIcon(if (showing) "mdi:eye-off" else "mdi:eye", theme.secondaryTextColor)
        button.contentDescription = if (showing) HaText.ui("ui.components.input.hide_password").ifEmpty { "Hide password" }
        else HaText.ui("ui.components.input.show_password").ifEmpty { "Show password" }
    }

    private fun placeLabel() {
        val raised = codeInput.isFocused || !codeInput.text.isNullOrEmpty()
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, ctx.scaledSp(if (raised) 12f else 14f))
        label.setTextColor(if (raised && codeInput.isFocused) theme.primaryColor else labelColor())
        (label.layoutParams as LayoutParams).topMargin = ctx.px(if (raised) 8f else 19f)
        label.requestLayout()
    }

    // ---- the keypad ----

    /** `.keypad`: HA's `BUTTONS` in its grid ([KeypadGrid]), each an `ha-control-button`. */
    private fun keypad(submitText: String?): View {
        val grid = KeypadGrid(ctx.context, columns, ctx.px(56f), ctx.px(24f)).apply { setPadding(ctx.px(12f), ctx.px(12f), ctx.px(12f), ctx.px(12f)) }
        for (value in KeypadGrid.order(columns)) {
            val key: View = when (value) {
                "clear" -> controlButton(icon = "mdi:close", tint = red(), name = HaText.ui("ui.common.clear").ifEmpty { "Clear" })
                "submit" -> controlButton(
                    icon = "mdi:check", tint = green(),
                    name = submitText?.ifEmpty { null } ?: HaText.ui("ui.common.submit").ifEmpty { "Submit" },
                )
                else -> controlButton(digit = value, name = value)
            }
            key.setOnClickListener { if (key.isEnabled) press(value) }
            keys[value] = key
            grid.addView(key)
        }
        return grid
    }

    /**
     * An `ha-control-button` 56 px round: its `::before` fill ([tint], or `--disabled-color`) at 20 %, the content in
     * [tint] (or the primary text color); a digit at 24 px / 500, an icon at 24 px. Named [name] for TalkBack.
     */
    private fun controlButton(digit: String? = null, icon: String? = null, tint: Int? = null, name: String): View {
        val b = FrameLayout(ctx.context)
        val fill = GradientDrawable().apply { cornerRadius = 28f * ctx.dp }
        b.background = fill
        if (digit != null) {
            b.addView(cssText(24f, theme.primaryTextColor, Inter.MEDIUM).apply {
                text = digit
                gravity = Gravity.CENTER
                alpha = 0.95f
            }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        if (icon != null) {
            b.addView(MdiIconView(ctx.context).apply { alpha = 0.95f }, LayoutParams(ctx.px(24f), ctx.px(24f), Gravity.CENTER))
        }
        b.tag = ControlLook(fill, icon, tint)
        applyHaRipple(b, 28f * ctx.dp, theme.dark)
        VitroAccess.role(b, A11yRole.BUTTON, name)
        paintControl(b, enabled = true)
        return b
    }

    private class ControlLook(val fill: GradientDrawable, val icon: String?, val tint: Int?)

    /** `ha-control-button:disabled`: `--disabled-color` at 20 %, the content in `--disabled-text-color`. */
    private fun paintControl(b: View, enabled: Boolean) {
        val look = b.tag as ControlLook
        b.isEnabled = enabled
        val tint = look.tint
        look.fill.setColor(CardSurface.withAlpha(if (enabled && tint != null) tint else disabledColor(), 0.2f))
        val fg = if (!enabled) theme.disabledTextColor else tint ?: theme.primaryTextColor
        val child = (b as ViewGroup).getChildAt(0)
        if (child is MdiIconView && look.icon != null) child.setIcon(look.icon, fg)
        if (child is TextView) child.setTextColor(fg)
    }

    /** `_showClearButton`: Clear is enabled only while the field holds something. */
    private fun updateClear() {
        keys["clear"]?.let { paintControl(it, enabled = code.isNotEmpty()) }
    }

    // ---- the text code's footer ----

    /** `ha-dialog-footer`: `12px 16px 16px`, the buttons at the end 12 px apart: Cancel (plain), then Submit. */
    private fun footer(submitText: String?): View {
        val row = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(ctx.px(16f), ctx.px(12f), ctx.px(16f), ctx.px(16f))
        }
        val cancel = HaButton(ctx, accent = false).apply {
            setLabel(HaText.ui("ui.common.cancel").ifEmpty { "Cancel" })
            setOnClickListener { onCancel() }
        }
        val submit = HaButton(ctx, accent = true).apply {
            setLabel(submitText?.ifEmpty { null } ?: HaText.ui("ui.common.submit").ifEmpty { "Submit" })
            setOnClickListener { onSubmit(code) }
        }
        row.addView(cancel, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, ctx.px(40f)))
        row.addView(submit, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, ctx.px(40f)).apply { marginStart = ctx.px(12f) })
        cancelButton = cancel
        submitButton = submit
        return row
    }

    // ---- colors ----

    private fun themeColor(name: String): Int? =
        theme.vars[name]?.let { ThemeResolver.parseColor(ThemeResolver.resolveValue(it, theme.vars, 0)) }

    private fun disabledColor(): Int = themeColor("disabled-color") ?: if (theme.dark) 0xFF464646.toInt() else 0xFFBDBDBD.toInt()
    private fun red(): Int = themeColor("red-color") ?: 0xFFF44336.toInt()
    private fun green(): Int = themeColor("green-color") ?: 0xFF4CAF50.toInt()
    private fun formBackground(): Int = themeColor("ha-color-form-background") ?: if (theme.dark) 0xFF363636.toInt() else 0xFFF3F3F3.toInt()
    private fun fieldLine(): Int = themeColor("ha-color-border-neutral-loud") ?: if (theme.dark) 0xFFB1B1B1.toInt() else 0xFF5E5E5E.toInt()
    private fun labelColor(): Int = if (theme.dark) 0x99FFFFFF.toInt() else 0x99000000.toInt()

    private fun cssText(sizeSp: Float, color: Int, weight: Int = Inter.REGULAR): TextView = TextView(ctx.context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ctx.scaledSp(sizeSp))
        typeface = VitroFonts.typeface(ctx.context, weight, ctx.scaledSp(sizeSp))
        includeFontPadding = false
        setTextColor(color)
    }

    /**
     * A column as tall as its content, at most [capFraction] of what its parent offers and [capBelow] less than it
     * (the dialog's `100vh - 80px`, the sheet's `min(90vh, 100vh - 48px)`), with the sheet's [handle] drawn on top.
     */
    inner class CappedColumn : LinearLayout(ctx.context) {
        var capFraction = 1f
        var capBelow = 0
        var handle: android.graphics.drawable.Drawable? = null

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val offered = MeasureSpec.getSize(heightMeasureSpec)
            if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED || offered <= 0) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                return
            }
            val cap = min((offered * capFraction).roundToInt(), offered - capBelow).coerceAtLeast(0)
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST))
        }

        override fun dispatchDraw(canvas: Canvas) {
            super.dispatchDraw(canvas)
            // ha-bottom-sheet's handle: 40 × 4 px in the divider color, centred on the sheet's top 16 px.
            handle?.let { h ->
                val w = ctx.px(40f)
                val left = (width - w) / 2
                val top = ctx.px(6f)
                h.setBounds(left, top, left + w, top + ctx.px(4f))
                h.draw(canvas)
            }
        }
    }

    private companion object {
        const val SHEET_MAX_WIDTH = 870
        const val SHEET_MAX_HEIGHT = 500
        const val SCRIM = 0x40000000
    }
}

/**
 * `.keypad`: a grid of [columns] equal cells, [cell] px square, [gap] px apart, as wide as its content. HA's CSS grid
 * places its keys ([order]).
 */
@SuppressLint("ViewConstructor")
internal class KeypadGrid(context: android.content.Context, private val columns: Int, private val cell: Int, private val gap: Int) : ViewGroup(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val spec = MeasureSpec.makeMeasureSpec(cell, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(spec, spec)
        val rows = (childCount + columns - 1) / columns
        setMeasuredDimension(
            paddingLeft + paddingRight + columns * cell + (columns - 1) * gap,
            paddingTop + paddingBottom + rows * cell + (rows - 1).coerceAtLeast(0) * gap,
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        // `direction: ltr`: the keypad reads left to right in every language.
        for (i in 0 until childCount) {
            val x = paddingLeft + (i % columns) * (cell + gap)
            val y = paddingTop + (i / columns) * (cell + gap)
            getChildAt(i).layout(x, y, x + cell, y + cell)
        }
    }

    companion object {
        /**
         * HA's `BUTTONS` as its grid places them. Three columns: Clear is pinned to row 4 (`grid-row-start: 4`; its
         * `grid-column-start: 0` is invalid and ignored), so it takes that row's first cell, and 0 and Submit follow
         * it: 1–9, then Clear, 0, Submit. Six columns (a window at most 450 px tall): Clear is pinned to row 1,
         * column 6, so the rows read 1–5, Clear and 6–9, 0, Submit. In reading order.
         */
        fun order(columns: Int): List<String> =
            if (columns == 6) listOf("1", "2", "3", "4", "5", "clear", "6", "7", "8", "9", "0", "submit")
            else listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "clear", "0", "submit")
    }
}
