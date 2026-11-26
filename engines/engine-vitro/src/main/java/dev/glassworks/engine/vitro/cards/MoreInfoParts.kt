/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.cards

import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.HaFormat
import dev.glassworks.engine.vitro.data.HaLocale
import dev.glassworks.engine.vitro.data.HaText
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.CardSurface
import dev.glassworks.engine.vitro.ui.MdiIconView
import dev.glassworks.engine.vitro.ui.VitroCardContext
import dev.glassworks.ui.Inter
import java.util.TimeZone

/*
 * The pieces HA's newer More Info controls are built from (frontend 20261028.5), drawn at HA's CSS sizes:
 * `ha-more-info-state-header`, `ha-control-button` in an `ha-control-button-group`, and `ha-control-select-menu` in an
 * `ha-more-info-control-select-container`.
 */

/** HA's `--disabled-color`, the surface of a control button or select menu (at 20 %). */
internal fun disabledColor(ctx: VitroCardContext): Int =
    ThemeResolver.parseColor(ctx.theme.vars["disabled-color"]) ?: if (ctx.theme.dark) 0xFF464646.toInt() else 0xFFBDBDBD.toInt()

/** A text sized as HA's CSS, its line box [lineHeight] times its size tall, the text centred in it. */
internal fun cssLine(ctx: VitroCardContext, sizeCss: Float, color: Int, weight: Int = Inter.REGULAR): TextView =
    cssText(ctx, sizeCss, color, weight).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER
    }

/** [lineHeight] × [sizeCss] in pixels: a CSS line box. */
internal fun lineBoxPx(ctx: VitroCardContext, sizeCss: Float, lineHeight: Float): Int =
    Math.round(sizeCss * lineHeight * ctx.dp * ctx.theme.fontScale)

/**
 * `ha-more-info-state-header`: the state (36 px, `--ha-line-height-condensed`) over the time row: when it last changed
 * (16 px / 500, `ha-relative-time capitalize`; a tap shows `ha-absolute-time` instead, and back), 20 px above what
 * follows, with an optional view at its end (the `after-time` slot, centred on the row).
 */
internal class MoreInfoStateHeader(private val ctx: VitroCardContext) : LinearLayout(ctx.context) {
    private val stateText = cssLine(ctx, 36f, ctx.theme.primaryTextColor)
    private val timeText = cssLine(ctx, 16f, ctx.theme.primaryTextColor, Inter.MEDIUM).apply { letterSpacing = 0.1f / 16f }
    private val timeRow = FrameLayout(ctx.context)
    private var absolute = false
    private var changedMs = 0L

    init {
        orientation = VERTICAL
        addView(stateText, LayoutParams(MATCH_PARENT, lineBoxPx(ctx, 36f, 1.2f)))
        // .last-changed: a 25.6 px line with 4 px above and below.
        timeRow.addView(timeText, FrameLayout.LayoutParams(WRAP_CONTENT, lineBoxPx(ctx, 16f, 1.6f) + ctx.px(8f), Gravity.CENTER))
        timeText.isClickable = true
        timeText.setOnClickListener {
            absolute = !absolute
            showTime()
        }
        addView(timeRow, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = ctx.px(20f) })
    }

    /** Puts [v] in the `after-time` slot: at the row's end, centred on it. */
    fun setAfterTime(v: View) {
        timeRow.addView(v, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL))
    }

    /** Shows [state] and [s]'s last change ([changedOverrideMs] in its place, as HA's `changedOverride`). */
    fun bind(s: EntityState?, state: String, changedOverrideMs: Long? = null) {
        if (stateText.text?.toString() != state) stateText.text = state
        changedMs = changedOverrideMs ?: ((s?.lastChanged ?: 0.0) * 1000).toLong()
        showTime()
    }

    private fun showTime() {
        val t = when {
            changedMs <= 0L -> if (absolute) HaText.ui("ui.components.absolute_time.never") else ""
            absolute -> absoluteTime(changedMs, ctx.now(), HaLocale.timeZone())
            else -> HaFormat.capitalize(HaFormat.relativeTime(changedMs, ctx.now()))
        }
        if (timeText.text?.toString() != t) timeText.text = t
    }

    companion object {
        /**
         * The text `ha-more-info-state-header` shows for [s]: [override] (a vacuum's status), else, for a `timestamp`
         * sensor that has one, `hui-timestamp-display` relative and capitalized, else HA's formatted state.
         */
        fun stateOf(s: EntityState?, nowMs: Long, override: String? = null): String {
            override?.let { return it }
            if (s == null) return HaText.ui("state.default.unknown")
            if (s.deviceClass == "timestamp" && !s.isUnavailable) {
                EntityStore.parseIsoEpochSeconds(s.state)?.let { return HaFormat.capitalize(HaFormat.relativeTime((it * 1000).toLong(), nowMs)) }
            }
            return s.formattedState()
        }

        /**
         * `state-card-display`'s state, the dialog row's: a `timestamp` sensor's date and time (`hui-timestamp-display`
         * `datetime`), an `uptime` sensor's time since (`total`, capitalized), else HA's formatted state.
         */
        fun stateCardDisplay(s: EntityState, nowMs: Long): String {
            if (s.domain == "sensor" && (s.deviceClass == "timestamp" || s.deviceClass == "uptime") && !s.isUnavailable) {
                val sec = EntityStore.parseIsoEpochSeconds(s.state)
                    ?: return HaText.ui("ui.panel.lovelace.components.timestamp-display.invalid")
                val ms = (sec * 1000).toLong()
                return if (s.deviceClass == "uptime") HaFormat.capitalize(HaFormat.relativeTime(nowMs, ms, includeTense = false))
                else HaFormat.formatDateTime(ms)
            }
            return s.formattedState()
        }

        /** `absoluteTime`: the time today, the short date and time this year, else with the year. */
        fun absoluteTime(ms: Long, nowMs: Long, tz: TimeZone): String {
            fun day(t: Long) = (t + tz.getOffset(t)).floorDiv(86_400_000L)
            if (day(ms) == day(nowMs)) return HaFormat.formatTime(ms)
            val year = { t: Long -> java.util.Calendar.getInstance(tz).apply { timeInMillis = t }.get(java.util.Calendar.YEAR) }
            return if (year(ms) == year(nowMs)) HaFormat.formatShortDateTime(ms) else HaFormat.formatShortDateTimeWithYear(ms)
        }
    }
}

/**
 * One `ha-control-button`: a [sizeDp] square with `--control-button-border-radius` corners, `--disabled-color` at 20 %
 * behind a 20 px icon in `--primary-text-color` (`--disabled-text-color` when disabled), named [label] for TalkBack.
 */
internal class HaControlButton(private val ctx: VitroCardContext, icon: String, label: String, radiusDp: Float) : FrameLayout(ctx.context) {
    private val iconView = MdiIconView(ctx.context)
    private var iconName = icon

    init {
        contentDescription = label
        background = GradientDrawable().apply {
            cornerRadius = radiusDp * ctx.dp
            setColor(CardSurface.withAlpha(disabledColor(ctx), 0.2f))
        }
        addView(iconView, LayoutParams(ctx.px(20f), ctx.px(20f), Gravity.CENTER))
        isClickable = true
        isFocusable = true
        applyHaRipple(this, radiusDp * ctx.dp, ctx.theme.dark)
        paint()
    }

    fun setIcon(name: String) {
        iconName = name
        paint()
    }

    fun setLabel(label: String) {
        contentDescription = label
    }

    override fun setEnabled(enabled: Boolean) {
        if (enabled == isEnabled) return
        super.setEnabled(enabled)
        paint()
    }

    private fun paint() {
        // `.button ::slotted(*)` draws the icon at 95 %.
        val color = if (isEnabled) ctx.theme.primaryTextColor else ctx.theme.disabledTextColor
        iconView.setIcon(iconName, CardSurface.withAlpha(color, 0.95f * android.graphics.Color.alpha(color) / 255f))
    }
}

/**
 * `ha-control-select-menu` (no arrow): a 48 px row with `--ha-border-radius-lg` corners on `--disabled-color` at 20 %,
 * padded 6 × 10 px, its 20 px icon, then the label (12 px) over the selected option's label (14 px), 10 px apart. A tap
 * lists the options ([PopupMenu], as HA's dropdown lists them), the selected one checked; picking one calls [onPick].
 */
internal class HaControlSelectMenu(private val ctx: VitroCardContext, icon: String, label: String) : LinearLayout(ctx.context) {
    private val iconView = MdiIconView(ctx.context).apply { setIcon(icon, ctx.theme.primaryTextColor) }
    private val labelText = cssText(ctx, 12f, ctx.theme.primaryTextColor).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        letterSpacing = 0.4f / 12f
        text = label
    }
    private val valueText = cssText(ctx, 14f, ctx.theme.primaryTextColor).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        letterSpacing = 0.25f / 14f
    }
    private var options: List<Pair<String, String>> = emptyList()
    private var selected: String? = null
    var onPick: ((String) -> Unit)? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ctx.px(10f), ctx.px(6f), ctx.px(10f), ctx.px(6f))
        background = GradientDrawable().apply {
            cornerRadius = 12f * ctx.dp
            setColor(CardSurface.withAlpha(disabledColor(ctx), 0.2f))
        }
        addView(iconView, LayoutParams(ctx.px(20f), ctx.px(20f)).apply { marginEnd = ctx.px(10f) })
        val content = LinearLayout(ctx.context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            // line-height 1.4 on both lines.
            addView(labelText, LayoutParams(MATCH_PARENT, lineBoxPx(ctx, 12f, 1.4f)))
            addView(valueText, LayoutParams(MATCH_PARENT, lineBoxPx(ctx, 14f, 1.4f)))
        }
        addView(content, LayoutParams(0, MATCH_PARENT, 1f))
        labelText.gravity = Gravity.CENTER_VERTICAL
        valueText.gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        applyHaRipple(this, 12f * ctx.dp, ctx.theme.dark)
        contentDescription = label
        setOnClickListener { open() }
    }

    /** HA's `label`: what the menu sets (the attribute's name). */
    fun setLabel(label: String) {
        labelText.text = label
        contentDescription = label
    }

    /** `ha-more-info-control-select-container ::slotted(*)`: 120 to 160 px wide, its content's width between. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(MeasureSpec.makeMeasureSpec(ctx.px(160f), MeasureSpec.AT_MOST), heightMeasureSpec)
        val w = measuredWidth.coerceIn(ctx.px(120f), ctx.px(160f))
        super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), heightMeasureSpec)
    }

    /** The options as (value, label), and the one selected. Without a match, the label alone (`.select-no-value`). */
    fun bind(options: List<Pair<String, String>>, value: String?, enabled: Boolean) {
        this.options = options
        selected = value
        val label = options.firstOrNull { it.first == value }?.second
        valueText.text = label.orEmpty()
        valueText.visibility = if (label == null) GONE else VISIBLE
        // No value: the label takes the value's size (`.select-no-value .label`).
        labelText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, (if (label == null) 14f else 12f) * ctx.theme.fontScale)
        labelText.layoutParams = (labelText.layoutParams as LayoutParams).apply {
            height = if (label == null) lineBoxPx(ctx, 14f, 1.4f) else lineBoxPx(ctx, 12f, 1.4f)
        }
        isEnabled = enabled
        val color = if (enabled) ctx.theme.primaryTextColor else disabledColor(ctx)
        labelText.setTextColor(color)
        valueText.setTextColor(color)
        iconView.tintColor = color
    }

    private fun open() {
        if (!isEnabled || options.isEmpty()) return
        val menu = PopupMenu(ctx.context, this)
        // The options as the menu shows them: a pick means what the user saw, even if a bind changes them meanwhile.
        val shown = options.toList()
        shown.forEachIndexed { i, (value, label) ->
            menu.menu.add(0, i, i, label).apply {
                isCheckable = true
                isChecked = value == selected
            }
        }
        menu.setOnMenuItemClickListener { item ->
            shown.getOrNull(item.itemId)?.first?.let { v -> onPick?.invoke(v) }
            true
        }
        menu.show()
    }
}
