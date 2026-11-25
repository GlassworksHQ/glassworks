/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import dev.glassworks.engine.vitro.model.CardConfig
import dev.glassworks.engine.vitro.theme.ThemeResolver
import kotlin.math.roundToInt

/**
 * HA's error card (hui-error-card) for a card it can't make: one whose build threw, or whose config HA's own card
 * rejects in `setConfig` ([message], such as "Entity must be specified"). HA shows the same card for both: titled
 * "Configuration error", the message only in the dashboard editor's preview, which Vitro has none of. A card that fails
 * later, once built, turns into the same card ([CardSurface.showErrorTile]).
 */
open class ErrorCardView(ctx: VitroCardContext, val config: CardConfig?, val message: String? = null) : CardSurface(ctx) {
    init {
        cardType = config?.type ?: TYPE
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

        /** hui-error-card's title as a warning (`ui.errors.config.configuration_warning`), for TalkBack. */
        const val WARNING_TITLE = "Configuration warning"
    }
}

/** HA's `--ha-font-weight-bold` (Vitro's HA tokens, MarkdownCss). */
private const val BOLD = 700

/** HA's `--error-color` default. */
private const val ERROR_RED = 0xFFDB4437.toInt()

/** HA's `--warning-color` default. */
private const val WARNING_ORANGE = 0xFFFFA600.toInt()

/**
 * hui-error-card's `severity`: `error` (a card HA can't make) or `warning` (hui-warning, a card whose entity is
 * missing). Each has its icon (`ERROR_ICONS`) and its color, the icon's and the 12% wash's.
 */
enum class HaErrorSeverity(internal val icon: String, internal val colorVar: String, internal val fallback: Int) {
    ERROR("mdi:alert-circle-outline", "error-color", ERROR_RED),
    WARNING("mdi:alert-outline", "warning-color", WARNING_ORANGE),
}

/**
 * Whether HA's frontend shows the signed-in user an error card's title: hui-error-card's `showTitle`, true only for an
 * administrator (`hass.user.is_admin`, from `auth/current_user`). Anyone else, a user HA hasn't named yet included,
 * sees the icon alone. Its other cases, no Home Assistant config and the editor's preview, never happen in Vitro.
 */
internal fun showsErrorTitle(ctx: VitroCardContext): Boolean =
    runCatching { ctx.haUser().optBoolean("is_admin", false) }.getOrDefault(false)

/**
 * The inside of hui-error-card: the card washed with the [severity]'s color at 12%, and a 16 px padded row (a flex
 * box, its items centered vertically, 8 px apart) with the icon (24 px, in that color) and the bold one-line [title].
 * The title shows only to an administrator ([showsErrorTitle]); anyone else sees the icon alone, where it is for an
 * administrator: the card's `no-title` class sets `justify-content: center` on ha-card, a block box, so it moves
 * nothing.
 *
 * Laid over a card that stays in place (a tile whose entity is missing: HA draws hui-warning instead of the card),
 * [over] is that card's background: the wash is then opaque, that background with the color 12% over it, and covers the
 * card's border, as hui-error-card has `border-width: 0`.
 */
internal fun errorCardContent(
    ctx: VitroCardContext,
    severity: HaErrorSeverity = HaErrorSeverity.ERROR,
    title: String = ErrorCardView.TITLE,
    over: Int? = null,
): View = LinearLayout(ctx.context).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    val pad = ctx.px(16f)
    setPadding(pad, pad, pad, pad)
    minimumHeight = ctx.px(CardSurface.ERROR_CARD_MIN_DP)
    val vars = ctx.theme.vars
    val color = vars[severity.colorVar]?.let { ThemeResolver.parseColor(ThemeResolver.resolveValue(it, vars, 0)) }
        ?: severity.fallback
    background = GradientDrawable().apply {
        cornerRadius = ctx.theme.cardBorderRadiusDp * ctx.dp
        setColor(if (over != null) washOver(over, color) else CardSurface.withAlpha(color, 0.12f))
    }
    val showTitle = showsErrorTitle(ctx)
    // TalkBack reads what HA shows: the title to an administrator, the card's generic name to anyone else.
    contentDescription = when {
        showTitle -> title
        severity == HaErrorSeverity.WARNING -> ErrorCardView.WARNING_TITLE
        else -> ErrorCardView.TITLE
    }
    addView(MdiIconView(ctx.context).apply { setIcon(severity.icon, color) },
        LinearLayout.LayoutParams(ctx.px(24f), ctx.px(24f)).apply { if (showTitle) marginEnd = ctx.px(8f) })
    if (showTitle) {
        addView(TextView(ctx.context).apply {
            val size = ctx.scaledSp(14f)
            textSize = size
            setTextColor(ctx.theme.primaryTextColor)
            typeface = VitroFonts.typeface(ctx.context, BOLD, size)
            includeFontPadding = false
            text = title
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    }
}

/**
 * [color] at 12% over [under] (`ha-card::after`, `opacity: 0.12`), composited once ("over"): an opaque [under] stays
 * opaque, and a transparent one leaves the wash as HA draws it, the color at 12%.
 */
internal fun washOver(under: Int, color: Int): Int {
    val a = 0.12f
    val ua = Color.alpha(under) / 255f
    val outA = a + ua * (1 - a)
    if (outA <= 0f) return Color.TRANSPARENT
    fun mix(u: Int, o: Int) = ((o * a + u * ua * (1 - a)) / outA).roundToInt().coerceIn(0, 255)
    return Color.argb(
        (outA * 255).roundToInt(),
        mix(Color.red(under), Color.red(color)), mix(Color.green(under), Color.green(color)), mix(Color.blue(under), Color.blue(color)),
    )
}
