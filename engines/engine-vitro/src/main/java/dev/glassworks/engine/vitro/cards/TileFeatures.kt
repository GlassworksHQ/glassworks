/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.cards

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.HaDisplay
import dev.glassworks.engine.vitro.data.HaFormat
import dev.glassworks.engine.vitro.data.HaText
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.A11yRole
import dev.glassworks.engine.vitro.ui.CardSurface.Companion.withAlpha
import dev.glassworks.engine.vitro.ui.MdiIconView
import dev.glassworks.engine.vitro.ui.VitroAccess
import dev.glassworks.engine.vitro.ui.VitroCardContext
import dev.glassworks.engine.vitro.ui.VitroFonts
import dev.glassworks.ui.Inter
import kotlin.math.floor

/** HA's cover helpers (frontend 20261028.5, `data/cover.ts`, `common/entity/cover_icon.ts`). */
internal object CoverState {
    private fun position(s: EntityState): Number? = s.attributes["current_position"] as? Number

    private fun fullyOpen(s: EntityState): Boolean =
        if (s.attributes.containsKey("current_position")) position(s)?.toDouble() == 100.0 else s.state == "open"

    private fun fullyClosed(s: EntityState): Boolean =
        if (s.attributes.containsKey("current_position")) position(s)?.toDouble() == 0.0 else s.state == "closed"

    private fun assumed(s: EntityState) = s.attributes["assumed_state"] == true

    fun canOpen(s: EntityState): Boolean =
        s.state != "unavailable" && (assumed(s) || (!fullyOpen(s) && s.state != "opening"))

    fun canClose(s: EntityState): Boolean =
        s.state != "unavailable" && (assumed(s) || (!fullyClosed(s) && s.state != "closing"))

    private val SIDEWAYS = setOf("awning", "door", "gate", "curtain")

    fun openIcon(s: EntityState): String = if (s.deviceClass in SIDEWAYS) "mdi:arrow-expand-horizontal" else "mdi:arrow-up"

    fun closeIcon(s: EntityState): String = if (s.deviceClass in SIDEWAYS) "mdi:arrow-collapse-horizontal" else "mdi:arrow-down"
}

/** HA's fan speeds (`data/fan.ts`): a fan with few steps is set by named or numbered speeds instead of a slider. */
internal object FanSpeeds {
    private const val MAX_FOR_BUTTONS = 6
    private val NAMED = mapOf(
        2 to listOf("off", "on"),
        3 to listOf("off", "low", "high"),
        4 to listOf("off", "low", "medium", "high"),
    )

    private fun jsRound(v: Double): Long = floor(v + 0.5).toLong()

    /** `computeFanSpeeds`: the speeds for a `percentage_step` of [step], or null when there are too many for buttons. */
    fun speeds(step: Double): List<String>? {
        val count = (jsRound(100.0 / step) + 1).toInt()
        if (count > MAX_FOR_BUTTONS) return null
        return NAMED[count] ?: (listOf("off") + (1 until count).map { "$it" })
    }

    /** `isNumberedFanSpeed`. */
    fun numbered(speed: String): Boolean = speed.toDoubleOrNull() != null

    /** `fanPercentageToSpeed`. */
    fun fromPercentage(value: Double, speeds: List<String>, step: Double): String =
        speeds.getOrNull(jsRound(value / step).toInt()) ?: "off"

    /** `fanSpeedToPercentage`. */
    fun toPercentage(speed: String, speeds: List<String>, step: Double): Long {
        val i = speeds.indexOf(speed)
        return if (i < 0) 0 else floor(i * step).toLong()
    }

    /** `computeFanSpeedIcon`: none for a numbered speed (its number shows). */
    fun icon(speed: String, speeds: List<String>): String? = when {
        speed == "on" -> "mdi:fan"
        speed == "off" -> "mdi:fan-off"
        numbered(speed) -> null
        else -> listOf("mdi:fan-speed-1", "mdi:fan-speed-2", "mdi:fan-speed-3").getOrNull(speeds.indexOf(speed) - 1)
    }

    /** The feature's `_localizeSpeed`, each option's accessible name. */
    fun label(s: EntityState, speed: String): String = when {
        speed == "on" || speed == "off" -> HaDisplay.stateLabel(s.copy(state = speed)) ?: s.copy(state = speed).plainState()
        numbered(speed) -> HaText.ui("ui.card.fan.speed.numbered", "speed" to HaFormat.formatNumber(speed))
        speed == "low" -> HaText.ui("ui.card.fan.speed.low")
        speed == "medium" -> HaText.ui("ui.card.fan.speed.medium")
        speed == "high" -> HaText.ui("ui.card.fan.speed.high")
        else -> speed
    }
}

/**
 * HA's `ha-control-select` as a card feature styles it (`card-feature-styles.ts`): the options side by side with no gap,
 * on the background color at 20%, each a 12 px rounded box; the selected one filled with the feature color and its
 * content white. An option shows its 20 px icon, its label (14 px / 500, up to two lines), or both.
 */
internal class HaControlSelect(private val ctx: VitroCardContext) : LinearLayout(ctx.context) {
    class Option(val value: String, val icon: String?, val label: String?, val ariaLabel: String?, val onSelect: (String) -> Unit)

    private val fill = GradientDrawable().apply { cornerRadius = 12f * ctx.dp }
    private var options: List<Option> = emptyList()
    private val views = ArrayList<Triple<FrameLayout, MdiIconView?, TextView?>>()
    private var selected: String? = null
    private var color: Int = ctx.theme.primaryColor
    private var disabled = false

    init {
        orientation = HORIZONTAL
        background = fill
    }

    fun setOptions(list: List<Option>) {
        options = list
        removeAllViews()
        views.clear()
        for (o in list) {
            val content = LinearLayout(ctx.context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER
                // `.option .content`: 2 px padding, 4 px (ha-space-1) at the sides.
                setPadding(ctx.px(4f), ctx.px(2f), ctx.px(4f), ctx.px(2f))
            }
            val icon = o.icon?.let { MdiIconView(ctx.context).also { v -> content.addView(v, LayoutParams(ctx.px(20f), ctx.px(20f))) } }
            val label = o.label?.let { text ->
                TextView(ctx.context).apply {
                    this.text = text
                    textSize = ctx.scaledSp(14f)
                    typeface = VitroFonts.typeface(ctx.context, Inter.MEDIUM, ctx.scaledSp(14f))
                    includeFontPadding = false
                    gravity = Gravity.CENTER
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    // --ha-line-height-condensed.
                    setLineSpacing(0f, 1.2f)
                }.also { v ->
                    content.addView(v, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                        if (icon != null) topMargin = ctx.px(4f)
                    })
                }
            }
            val box = FrameLayout(ctx.context).apply {
                isClickable = true
                isFocusable = true
                applyHaRipple(this, 12f * ctx.dp, ctx.theme.dark)
                contentDescription = o.ariaLabel ?: o.label
                addView(content, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
                VitroAccess.role(this, A11yRole.RADIO) { selected == o.value }
                setOnClickListener {
                    if (disabled) return@setOnClickListener
                    // HA's select takes the new value at once, then tells the feature.
                    selected = o.value
                    paint()
                    o.onSelect(o.value)
                }
            }
            addView(box, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            views += Triple(box, icon, label)
        }
        paint()
    }

    /** Selects [value] (null: none) in [featureColor]; [unavailable] greys the select as HA's `disabled` does. */
    fun bind(value: String?, featureColor: Int, unavailable: Boolean, background: Int = featureColor) {
        selected = value
        color = featureColor
        disabled = unavailable
        fill.setColor(withAlpha(background, 0.2f))
        paint()
    }

    private fun disabledColor(): Int =
        ThemeResolver.parseColor(ctx.theme.vars["disabled-color"]?.let { ThemeResolver.resolveValue(it, ctx.theme.vars, 0) })
            ?: if (ctx.theme.dark) 0xFF464646.toInt() else 0xFFBDBDBD.toInt()

    private fun paint() {
        val selectColor = if (disabled) disabledColor() else color
        val text = if (disabled) disabledColor() else ctx.theme.primaryTextColor
        options.forEachIndexed { i, o ->
            val (box, icon, label) = views[i]
            val on = o.value == selected
            box.background = if (on) GradientDrawable().apply { cornerRadius = 12f * ctx.dp; setColor(selectColor) } else null
            val fg = if (on) Color.WHITE else text
            o.icon?.let { icon?.setIcon(it, fg) }
            label?.setTextColor(fg)
            box.isEnabled = !disabled
        }
    }
}
