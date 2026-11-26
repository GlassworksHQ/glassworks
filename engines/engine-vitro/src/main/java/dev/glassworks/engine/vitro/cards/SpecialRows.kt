/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.cards

import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.HaEntityName
import dev.glassworks.engine.vitro.model.ActionConfig
import dev.glassworks.engine.vitro.model.EntityRowConfig
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.A11yRole
import dev.glassworks.engine.vitro.ui.MdiIconView
import dev.glassworks.engine.vitro.ui.MdiIcons
import dev.glassworks.engine.vitro.ui.VitroAccess
import dev.glassworks.engine.vitro.ui.VitroCardContext
import dev.glassworks.engine.vitro.ui.VitroFonts
import dev.glassworks.engine.vitro.ui.bindHoldAction
import dev.glassworks.ui.Inter
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * HA's special `entities` rows that carry no state of their own (`special-rows/hui-*-row.ts`, frontend 20261028.8):
 * `button` (and `call-service` / `perform-action`, the same row), `text`, `weblink` and `buttons`. The entities card
 * places them like its entity rows ([EntitiesCardView]): a 40 px row, 16 px in from the card's sides, 8 px apart; a
 * `conditional` row is one of these (or an entity row) shown while its conditions hold.
 *
 * [observe] is the card's own subscription ([dev.glassworks.engine.vitro.ui.CardSurface]'s), so a row follows its
 * entity as long as the card does.
 */
internal object SpecialRows {
    /** The row view for [row], or null for a type this file doesn't draw (entity rows, dividers, sections). */
    fun build(
        ctx: VitroCardContext,
        row: EntityRowConfig,
        observe: (String, (EntityState?) -> Unit) -> Unit,
    ): View? = when (row.rowType) {
        "button" -> buttonRow(ctx, row, observe)
        "text" -> textRow(ctx, row)
        "weblink" -> weblinkRow(ctx, row)
        "buttons" -> buttonsRow(ctx, row, observe)
        else -> null
    }

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

    /** A row as the entities card holds it: 40 px tall (the icon's 8 px padding round 24), 16 px in from the card. */
    private fun rowLayout(ctx: VitroCardContext): LinearLayout = LinearLayout(ctx.context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = ctx.px(40f)
        setPadding(ctx.px(16f), 0, ctx.px(16f), 0)
    }

    /** `ha-icon { padding: 8px; color: var(--state-icon-color) }` and the 16 px margin of what follows it. */
    private fun addIcon(ctx: VitroCardContext, parent: LinearLayout): MdiIconView {
        val icon = MdiIconView(ctx.context)
        parent.addView(icon, LinearLayout.LayoutParams(ctx.px(24f), ctx.px(24f)).apply {
            marginStart = ctx.px(8f); marginEnd = ctx.px(8f + 16f)
        })
        return icon
    }

    /**
     * `hui-button-row`: the entity's icon (or the row's), the name, and at the end HA's small filled button
     * (`ha-button size="s"`: 32 px, 12 px in, a 14 px medium label) with `action_name`, else "Run". Only the button
     * acts: its tap runs `tap_action` (toggle for HA's toggle domains, else more-info), a hold `hold_action`.
     */
    private fun buttonRow(ctx: VitroCardContext, row: EntityRowConfig, observe: (String, (EntityState?) -> Unit) -> Unit): View {
        val layout = rowLayout(ctx)
        val icon = addIcon(ctx, layout)
        val name = text(ctx, 14f, ctx.theme.primaryTextColor)
        layout.addView(name, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = ctx.px(8f) })
        val label = row.raw?.optString("action_name", "")?.ifEmpty { null } ?: haUi("ui.card.service.run", "Run")
        val button = text(ctx, 14f, onPrimary(ctx), Inter.MEDIUM).apply {
            text = label
            gravity = Gravity.CENTER
            val p = ctx.px(12f)
            setPadding(p, 0, p, 0)
            background = GradientDrawable().apply {
                cornerRadius = 999f * ctx.dp
                setColor(fillPrimary(ctx))
            }
            isClickable = true
            applyHaRipple(this, 999f, ctx.theme.dark)
            VitroAccess.role(this, A11yRole.BUTTON)
        }
        layout.addView(button, LinearLayout.LayoutParams(WRAP_CONTENT, ctx.px(32f)))
        val entity = row.entity.ifEmpty { null }
        val tap = row.tapAction ?: ActionConfig("more-info")
        button.setOnClickListener { ctx.performAction(tap, entity) }
        button.bindHoldAction(ctx, row.holdAction ?: ActionConfig("more-info"), entity)
        fun render(s: EntityState?) {
            // hui-button-row names an entity-less row by its `name`; with an entity, HA's entity name.
            name.text = row.nameFor(s) ?: s?.displayName ?: entity.orEmpty()
            val iconName = row.icon ?: s?.let { MdiIcons.resolveIconName(null, it) }
            icon.visibility = if (iconName != null) View.VISIBLE else View.INVISIBLE
            iconName?.let { icon.setIcon(it, ctx.theme.stateIconColor) }
            button.contentDescription = "$label ${name.text}".trim()
        }
        if (entity != null) observe(entity, ::render) else render(null)
        return layout
    }

    /** `hui-text-row`: the icon, then the name and the text sharing the rest equally, the text at the end. */
    private fun textRow(ctx: VitroCardContext, row: EntityRowConfig): View {
        val layout = rowLayout(ctx)
        val icon = addIcon(ctx, layout)
        row.icon?.let { icon.setIcon(it, ctx.theme.stateIconColor) } ?: run { icon.visibility = View.INVISIBLE }
        layout.addView(text(ctx, 14f, ctx.theme.primaryTextColor).apply { text = row.name }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        layout.addView(text(ctx, 14f, ctx.theme.primaryTextColor).apply {
            text = row.raw?.optString("text", "").orEmpty()
            gravity = Gravity.END
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        return layout
    }

    /**
     * `hui-weblink-row`: a link (its `name`, else the URL, in `--primary-color` and underlined, as a link's text is)
     * after its icon (`mdi:link` by default). A tap opens it as HA's `url` action does: a dashboard path in place, an
     * address elsewhere.
     */
    private fun weblinkRow(ctx: VitroCardContext, row: EntityRowConfig): View {
        val layout = rowLayout(ctx).apply {
            isClickable = true
            applyHaRipple(this, 4f * ctx.dp, ctx.theme.dark)
        }
        val icon = addIcon(ctx, layout)
        icon.setIcon(row.icon ?: "mdi:link", ctx.theme.stateIconColor)
        val name = text(ctx, 14f, ctx.theme.primaryColor).apply {
            text = row.name
            paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
        }
        layout.addView(name, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val url = row.raw?.optString("url", "").orEmpty()
        layout.setOnClickListener { ctx.performAction(ActionConfig("url", urlPath = url), null) }
        VitroAccess.role(layout, A11yRole.BUTTON, label = row.name)
        return layout
    }

    /**
     * `hui-buttons-row` (`hui-buttons-base`): one filled assist chip per entity (32 px, 16 px radius, 15% of the text
     * color), its 18 px state badge in the state color (else `--secondary-text-color`) and its 12.25 px name, 8 px apart in a
     * box 12 px in and 8 px from the row's edges. They wrap, except on a panel at most 450 px wide or 500 px tall,
     * where they scroll sideways in one line. Each chip runs its own actions: a tap toggles HA's toggle domains (else
     * more-info), a hold opens more-info.
     */
    private fun buttonsRow(ctx: VitroCardContext, row: EntityRowConfig, observe: (String, (EntityState?) -> Unit) -> Unit): View {
        val metrics = ctx.context.resources.displayMetrics
        val widthDp = metrics.widthPixels / ctx.dp
        val heightDp = metrics.heightPixels / ctx.dp
        val oneLine = widthDp <= 450f || heightDp <= 500f
        val gap = ctx.px(8f)
        val chips = row.buttons.map { chip(ctx, it, observe) }
        val box: ViewGroup = if (oneLine) {
            val line = LinearLayout(ctx.context).apply { orientation = LinearLayout.HORIZONTAL }
            chips.forEachIndexed { i, c -> line.addView(c, LinearLayout.LayoutParams(WRAP_CONTENT, ctx.px(32f)).apply { if (i > 0) marginStart = gap }) }
            HorizontalScrollView(ctx.context).apply {
                isHorizontalScrollBarEnabled = false
                addView(line, ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            }
        } else {
            WrapRow(ctx.context, gap).apply { chips.forEach { addView(it, ViewGroup.LayoutParams(WRAP_CONTENT, ctx.px(32f))) } }
        }
        return FrameLayout(ctx.context).apply {
            // 16 px of the card's content padding, then the chip set's 12 px; 8 px above and below.
            setPadding(ctx.px(16f + 12f), ctx.px(8f), ctx.px(16f + 12f), ctx.px(8f))
            addView(box, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
    }

    private fun chip(ctx: VitroCardContext, conf: JSONObject, observe: (String, (EntityState?) -> Unit) -> Unit): View {
        val entity = conf.optString("entity", "").ifEmpty { null }
        val showIcon = conf.opt("show_icon") != false
        val chip = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.px(if (showIcon) 8f else 16f), 0, ctx.px(16f), 0)
            background = GradientDrawable().apply {
                cornerRadius = 16f * ctx.dp
                val c = ctx.theme.primaryTextColor
                setColor(Color.argb((0.15f * 255).roundToInt(), Color.red(c), Color.green(c), Color.blue(c)))
            }
            isClickable = true
            applyHaRipple(this, 16f * ctx.dp, ctx.theme.dark)
        }
        val icon = MdiIconView(ctx.context)
        if (showIcon) chip.addView(icon, LinearLayout.LayoutParams(ctx.px(18f), ctx.px(18f)))
        // `.label`: 0.875rem (HA's root font is 14 px: 12.25 px) on a 1.25rem line.
        val label = text(ctx, 12.25f, ctx.theme.primaryTextColor).apply { setLineSpacing(0f, 1f) }
        chip.addView(label, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { if (showIcon) marginStart = ctx.px(8f) })
        val tap = ActionConfig.parse(conf.optJSONObject("tap_action"), ActionConfig.defaultTap(entity))
        val hold = ActionConfig.parse(conf.optJSONObject("hold_action"), "more-info")
        chip.setOnClickListener { ctx.performAction(tap, entity) }
        chip.bindHoldAction(ctx, hold, entity)
        VitroAccess.role(chip, A11yRole.BUTTON)
        val nameSpec = conf.opt("name")
        val showName = conf.opt("show_name")
        fun render(s: EntityState?) {
            val configName = if (HaEntityName.isStructured(nameSpec)) HaEntityName.format(nameSpec, entity, s?.friendlyName)
                else conf.optString("name", "").ifEmpty { null }
            // `(show_name && stateObj) || (name && show_name !== false)`: HA's entity name, else the configured name.
            val named = (showName == true && s != null) || (configName != null && showName != false)
            val text = if (!named) "" else s?.let { configName ?: it.displayName } ?: configName.orEmpty()
            label.text = text
            label.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
            chip.contentDescription = text.ifEmpty { s?.displayName ?: entity }
            if (showIcon) {
                val rgb = (s?.attributes?.get("rgb_color") as? List<*>)?.mapNotNull { (it as? Number)?.toInt() }
                val color = when {
                    s == null -> null
                    rgb != null && rgb.size >= 3 -> Color.rgb(rgb[0], rgb[1], rgb[2])
                    // `--state-inactive-color: initial` in the badge: an inactive state takes the chip's own color.
                    !s.isActive && s.state != "unavailable" -> null
                    else -> HaStateColor.of(ctx, s)
                }
                icon.setIcon(MdiIcons.resolveIconName(conf.optString("icon", "").ifEmpty { null }, s), color ?: ctx.theme.secondaryTextColor)
            }
        }
        if (entity != null) observe(entity, ::render) else render(null)
        return chip
    }

    /** `--ha-color-fill-primary-normal-resting`: a filled button's background. */
    private fun fillPrimary(ctx: VitroCardContext): Int =
        ThemeResolver.parseColor(ctx.theme.vars["ha-color-fill-primary-normal-resting"]) ?: if (ctx.theme.dark) 0xFF002E3E.toInt() else 0xFFDFF3FC.toInt()

    /** `--ha-color-on-primary-normal`: a filled button's label. */
    private fun onPrimary(ctx: VitroCardContext): Int =
        ThemeResolver.parseColor(ctx.theme.vars["ha-color-on-primary-normal"]) ?: if (ctx.theme.dark) 0xFF37C8FD.toInt() else 0xFF006787.toInt()

    /** `display: flex; flex-wrap: wrap; gap`: children in lines, each line as tall as its tallest child. */
    private class WrapRow(context: android.content.Context, private val gap: Int) : ViewGroup(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val max = MeasureSpec.getSize(widthMeasureSpec).takeIf { MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED } ?: Int.MAX_VALUE
            var x = 0
            var y = 0
            var lineH = 0
            var widest = 0
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                if (c.visibility == GONE) continue
                c.measure(MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(c.layoutParams.height.coerceAtLeast(0), MeasureSpec.EXACTLY))
                if (x > 0 && x + gap + c.measuredWidth > max) {
                    y += lineH + gap
                    x = 0
                    lineH = 0
                }
                x += (if (x > 0) gap else 0) + c.measuredWidth
                lineH = maxOf(lineH, c.measuredHeight)
                widest = maxOf(widest, x)
            }
            val w = if (max == Int.MAX_VALUE) widest else max
            setMeasuredDimension(w, y + lineH)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val max = r - l
            var x = 0
            var y = 0
            var lineH = 0
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                if (c.visibility == GONE) continue
                if (x > 0 && x + gap + c.measuredWidth > max) {
                    y += lineH + gap
                    x = 0
                    lineH = 0
                }
                if (x > 0) x += gap
                c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
                x += c.measuredWidth
                lineH = maxOf(lineH, c.measuredHeight)
            }
        }
    }
}
