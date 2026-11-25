/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.ui

import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import dev.glassworks.engine.vitro.model.CardConfig
import dev.glassworks.engine.vitro.theme.ThemeResolver

/**
 * HA's error card (hui-error-card) for a card whose build threw: what Home Assistant shows for a card it can't make,
 * in the same slot. A card that fails later, once built, turns into the same card ([CardSurface.showErrorTile]).
 */
class ErrorCardView(ctx: VitroCardContext, val config: CardConfig) : CardSurface(ctx) {
    init {
        cardType = config.type
        showErrorTile()
    }

    companion object {
        /**
         * The type [HaGridSize] sizes it as in a sections grid: hui-error-card's `getGridOptions()`, 6 columns by 1
         * row, under the card's own `grid_options` (hui-card merges the two, the config's winning).
         */
        const val TYPE = "error"

        /** hui-error-card's title (`ui.errors.config.configuration_error`). */
        const val TITLE = "Configuration error"
    }
}

/** `--ha-font-weight-bold` (Vitro's HA tokens, MarkdownCss). */
private const val BOLD = 700

/** HA's `--error-color` default. */
private const val ERROR_RED = 0xFFDB4437.toInt()

/**
 * The inside of hui-error-card: the card washed with `--error-color` at 12%, a 16 px padded row with the error
 * icon (24 px, `--error-color`) and, 8 px after it, the bold one-line title. HA shows the title to an administrator
 * (and before it knows the user); anyone else sees the icon alone, centered.
 */
internal fun errorCardContent(ctx: VitroCardContext): View = LinearLayout(ctx.context).apply {
    orientation = LinearLayout.HORIZONTAL
    val pad = ctx.px(16f)
    setPadding(pad, pad, pad, pad)
    minimumHeight = ctx.px(CardSurface.ERROR_CARD_MIN_DP)
    val vars = ctx.theme.vars
    val color = vars["error-color"]?.let { ThemeResolver.parseColor(ThemeResolver.resolveValue(it, vars, 0)) } ?: ERROR_RED
    background = GradientDrawable().apply {
        cornerRadius = ctx.theme.cardBorderRadiusDp * ctx.dp
        setColor(CardSurface.withAlpha(color, 0.12f))
    }
    val user = runCatching { ctx.haUser() }.getOrNull()
    val showTitle = user == null || !user.has("is_admin") || user.optBoolean("is_admin", false)
    gravity = if (showTitle) Gravity.CENTER_VERTICAL else Gravity.CENTER
    contentDescription = ErrorCardView.TITLE
    addView(MdiIconView(ctx.context).apply { setIcon("mdi:alert-octagram", color) },
        LinearLayout.LayoutParams(ctx.px(24f), ctx.px(24f)).apply { if (showTitle) marginEnd = ctx.px(8f) })
    if (showTitle) {
        addView(TextView(ctx.context).apply {
            val size = ctx.scaledSp(14f)
            textSize = size
            setTextColor(ctx.theme.primaryTextColor)
            typeface = VitroFonts.typeface(ctx.context, BOLD, size)
            includeFontPadding = false
            text = ErrorCardView.TITLE
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    }
}
