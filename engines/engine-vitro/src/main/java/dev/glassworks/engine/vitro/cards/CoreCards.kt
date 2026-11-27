/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.cards

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.format.DateFormat
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.Space
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import dev.glassworks.engine.vitro.data.HaText
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.HaDisplay
import dev.glassworks.engine.vitro.data.HaFormat
import dev.glassworks.engine.vitro.model.ActionConfig
import dev.glassworks.engine.vitro.model.CardConfig
import dev.glassworks.engine.vitro.model.EntityRowConfig
import dev.glassworks.engine.vitro.model.VisibilityCondition
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.CardSurface
import dev.glassworks.engine.vitro.ui.HaErrorSeverity
import dev.glassworks.engine.vitro.ui.LocalRelayout
import dev.glassworks.engine.vitro.ui.errorCardContent
import dev.glassworks.engine.vitro.ui.TimestampText
import dev.glassworks.engine.vitro.ui.showRelativeOr
import dev.glassworks.engine.vitro.ui.showTimestampOr
import dev.glassworks.engine.vitro.ui.MdiIconView
import dev.glassworks.engine.vitro.ui.MdiIcons
import dev.glassworks.engine.vitro.ui.HaWeatherSvg
import dev.glassworks.engine.vitro.ui.VitroCardContext
import dev.glassworks.engine.vitro.ui.bindHoldAction
import dev.glassworks.ui.Inter
import dev.glassworks.engine.vitro.ui.VitroFonts
import dev.glassworks.engine.vitro.ui.A11yRole
import dev.glassworks.engine.vitro.ui.VitroAccess
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.roundToInt

/** Inter's semibold weight (the variable font takes any weight; core's [Inter] names only up to medium). */
private const val INTER_SEMIBOLD = 600

/**
 * HA 2026's motion tokens (Material Web): the standard ease for state changes, the decelerate ease for things
 * entering and the accelerate ease for things leaving.
 */
internal object HaMotion {
    fun standard() = PathInterpolator(0.4f, 0f, 0.2f, 1f)
    fun decelerate() = PathInterpolator(0f, 0f, 0.2f, 1f)
    fun accelerate() = PathInterpolator(0.4f, 0f, 1f, 1f)

    /** Straight per-channel ARGB blend from [from] (t = 0) to [to] (t = 1). */
    fun lerpColor(from: Int, to: Int, t: Float): Int {
        if (t <= 0f) return from
        if (t >= 1f) return to
        fun ch(shift: Int): Int {
            val a = (from ushr shift) and 0xFF
            val b = (to ushr shift) and 0xFF
            return ((a + (b - a) * t).roundToInt() and 0xFF) shl shift
        }
        return ch(24) or ch(16) or ch(8) or ch(0)
    }

    fun lerp(from: Float, to: Float, t: Float): Float = from + (to - from) * t

    /**
     * Runs [onFrame] with 0 → 1 over [durationMs] and returns the animator, or calls `onFrame(1f)` straight away
     * (and returns null) when [view] isn't on screen, so binds before attach never animate.
     */
    fun run(view: View, durationMs: Long, onFrame: (Float) -> Unit): ValueAnimator? {
        if (!view.isAttachedToWindow) {
            onFrame(1f)
            return null
        }
        return ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = standard()
            addUpdateListener { onFrame(it.animatedValue as Float) }
            start()
        }
    }
}

/**
 * HA's `mwc-ripple` / `ha-ripple`: a Material ripple clipped to [cornerRadiusPx] (999 for pills and circles), set
 * as the view's foreground so it draws over whatever background the view already has. Cards never scale on press.
 */
internal fun applyHaRipple(view: View, cornerRadiusPx: Float, isDark: Boolean) {
    view.foreground = HaRipple(cornerRadiusPx, isDark)
}

/**
 * [applyHaRipple]'s foreground: the same [RippleDrawable], made the first time the view is touched, focused or hovered
 * (its hotspot set, or a state the ripple shows), so a dashboard's hundreds of tiles and rows don't each build one
 * they may never show. At rest neither draws anything; once made, it's the ripple itself that draws and animates.
 */
internal class HaRipple(private val cornerRadiusPx: Float, private val isDark: Boolean) : android.graphics.drawable.DrawableWrapper(null) {
    /** The ripple, made now if it isn't yet ([DrawableWrapper.setDrawable] hands it this drawable's bounds and state). */
    internal fun ripple(): android.graphics.drawable.Drawable = drawable ?: RippleDrawable(
        ColorStateList.valueOf(if (isDark) 0x22FFFFFF else 0x1A000000),
        null,
        GradientDrawable().apply {
            cornerRadius = cornerRadiusPx
            setColor(Color.WHITE)
        },
    ).also { drawable = it }

    override fun isStateful(): Boolean = true

    override fun onStateChange(state: IntArray): Boolean {
        if (drawable == null) {
            if (state.none { it == android.R.attr.state_pressed || it == android.R.attr.state_focused || it == android.R.attr.state_hovered }) return false
            ripple()
            return true
        }
        return super.onStateChange(state)
    }

    // A touch sets the hotspot before the pressed state: the ripple starts from where the finger is.
    override fun setHotspot(x: Float, y: Float) {
        ripple().setHotspot(x, y)
    }

    override fun setHotspotBounds(left: Int, top: Int, right: Int, bottom: Int) {
        ripple().setHotspotBounds(left, top, right, bottom)
    }

    override fun getOpacity(): Int = drawable?.opacity ?: android.graphics.PixelFormat.TRANSPARENT
}

/** HA's `hold_action` for a card: the configured one, else `more-info` when there's an entity to show. */
private fun holdActionFor(raw: JSONObject, entityId: String?): ActionConfig =
    ActionConfig.parse(raw.optJSONObject("hold_action"), if (entityId != null) "more-info" else "none")

/**
 * Sets [text] only when it differs from what [this] shows: [TextView.setText] lays the text out again, and asks for a
 * layout up to the root, even when the words are the same.
 */
internal fun TextView.setTextIfChanged(text: CharSequence) {
    if (!TextUtils.equals(this.text, text)) this.text = text
}

private fun textView(ctx: VitroCardContext, sp: Float, color: Int, weight: Int = Inter.REGULAR): TextView =
    TextView(ctx.context).apply {
        val scaled = ctx.scaledSp(sp)
        textSize = scaled
        setTextColor(color)
        typeface = VitroFonts.typeface(ctx.context, weight, scaled)
        includeFontPadding = false
    }

/** True when [s] holds Jinja2 that has to go through HA's `render_template`. */
internal fun isTemplate(s: String?): Boolean = s != null && ("{{" in s || "{%" in s)

private val ENTITY_VARIABLE = Regex("""\bentity\b""")

/**
 * Mushroom templates see the card's `entity` as a variable. HA's `render_template` has no card context, so a
 * `{% set entity = … %}` prefix supplies it when the template uses it.
 */
internal fun withEntityVariable(template: String, entityId: String?): String =
    if (entityId != null && ENTITY_VARIABLE.containsMatchIn(template)) "{% set entity = '$entityId' %}$template" else template

/** Standard Mushroom `--mush-icon-border-radius` in px when set to a non-circular length (e.g. `12px`); null = oval. */
internal fun mushIconRadiusPx(ctx: VitroCardContext): Float? {
    val raw = ctx.theme.vars["mush-icon-border-radius"]?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
    if (raw.endsWith("%")) return null
    val dp = ThemeResolver.parseDimensionDp(raw, -1f)
    return if (dp >= 0f) dp * ctx.dp else null
}

/**
 * Mushroom's `--rgb-disabled` (an off entity's icon, its disc at 20%, a slider's track): `--mush-rgb-disabled` as the
 * card inherits it (its own or an enclosing card's card_mod, else the theme), else Mushroom's own default, 111, 111,
 * 111 dark and 189, 189, 189 light. HA's `--state-inactive-color` (#9e9e9e) isn't Mushroom's. Call it while the card
 * is built: the card scope ([VitroCardContext.cardVars]) is the build's only.
 */
internal fun mushroomDisabledColor(ctx: VitroCardContext): Int {
    for (v in listOf(ctx.cardVars["mush-rgb-disabled"], ctx.theme.vars["mush-rgb-disabled"])) {
        // Three whole numbers or nothing: one bad channel drops the whole value, as an invalid custom property does.
        val parts = v?.split(',')?.map { it.trim().toIntOrNull() }
        if (parts != null && parts.size == 3 && parts.all { it != null && it in 0..255 }) return Color.rgb(parts[0]!!, parts[1]!!, parts[2]!!)
    }
    return if (ctx.theme.dark) Color.rgb(111, 111, 111) else Color.rgb(189, 189, 189)
}

/** Mushroom's color names (`--rgb-<name>`), plus `primary` / `accent` from the theme and any CSS color. */
internal object MushroomColors {
    private val NAMED = mapOf(
        "red" to 0xFFF44336, "pink" to 0xFFE91E63, "purple" to 0xFF926BC7, "deep-purple" to 0xFF6E41AB,
        "indigo" to 0xFF3F51B5, "blue" to 0xFF2196F3, "light-blue" to 0xFF03A9F4, "cyan" to 0xFF00BCD4,
        "teal" to 0xFF009688, "green" to 0xFF4CAF50, "light-green" to 0xFF8BC34A, "lime" to 0xFFCDDC39,
        "yellow" to 0xFFFFEB3B, "amber" to 0xFFFFC107, "orange" to 0xFFFF9800, "deep-orange" to 0xFFFF6F22,
        "brown" to 0xFF795548, "grey" to 0xFF9E9E9E, "gray" to 0xFF9E9E9E, "blue-grey" to 0xFF607D8B,
        "black" to 0xFF000000, "white" to 0xFFFFFFFF, "disabled" to 0xFFBDBDBD,
    ).mapValues { it.value.toInt() }

    /** The color [raw] names, or null for blank / unknown values (the caller's default applies). */
    fun parse(raw: String?, theme: dev.glassworks.engine.vitro.theme.ResolvedTheme): Int? {
        val s = raw?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() && it != "none" } ?: return null
        return when (s) {
            "primary" -> theme.primaryColor
            "accent" -> theme.accentColor
            else -> NAMED[s] ?: MarkdownInline.parseCssColor(s)
        }
    }
}

/**
 * Native HA `tile` card + Tier 2 HACS fast-path for `custom:mushroom-entity-card`,
 * `custom:mushroom-light-card`, `custom:mushroom-fan-card`, `custom:mushroom-cover-card`,
 * `custom:mushroom-person-card`, and `custom:mushroom-template-card`.
 */
class TileCardView(ctx: VitroCardContext, private val config: CardConfig) : CardSurface(ctx) {
    // HA's tile and Mushroom's cards lay their content out from the card's outer edge.
    override val contentInsideBorder: Boolean get() = false
    /** Live state binders for the inline features added by [addExtraFeatures]. */
    private val featureBinders = ArrayList<(EntityState?) -> Unit>()
    /** Mushroom's off color, from the card scope in force while the card is built. */
    private val mushroomDisabled = mushroomDisabledColor(ctx)
    private val iconBadge = MdiIconView(ctx.context)
    private val iconWrap = LinearLayout(ctx.context)
    private val titleView = textView(ctx, 14f, ctx.theme.primaryTextColor, Inter.MEDIUM)
    private val stateView = textView(ctx, 12f, ctx.theme.primaryTextColor, Inter.REGULAR)

    /** The 20% oval behind the badge icon, kept so its tint can cross-fade instead of being replaced. */
    private val badgeOval = GradientDrawable().apply { shape = GradientDrawable.OVAL }
    private var badgeColor: Int? = null
    private var badgeAnimator: ValueAnimator? = null

    /** The last real `brightness` (1..255) this tile saw, so an optimistic `on` restores it instead of 100%. */
    private var lastBrightness: Int? = null

    /** The Mushroom template card's views, when this tile renders `custom:mushroom-template-card`. */
    internal var mushroom: MushroomTemplateViews? = null
        private set

    /** HA's `ha-tile-badge`: a 16 px disc with a 12 px white icon, over the icon's top-right ([renderTileBadge]). */
    // Made the first time a state shows them ([badge]), as most tiles never do.
    private val tileBadgeFill = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFFF9800.toInt()) }
    private var tileBadgeIcon: MdiIconView? = null
    private var unavailableBadge: FrameLayout? = null

    /** `show_entity_picture`: the entity's picture in the icon's place (a 36 px rounded square), once it has loaded. */
    private var pictureView: ImageView? = null
    private var pictureUrl: String? = null

    /** The box the icon, the picture and the badge share; null for a Mushroom template card, which has neither. */
    private var iconFrame: FrameLayout? = null

    /** The badge, made (hidden, over the icon's top-right) the first time it shows. */
    private fun badge(): FrameLayout = unavailableBadge ?: FrameLayout(ctx.context).apply {
        visibility = GONE
        background = tileBadgeFill
        val icon = MdiIconView(ctx.context).apply { setIcon("mdi:exclamation-thick", Color.WHITE) }
        tileBadgeIcon = icon
        addView(icon, LayoutParams(ctx.px(12f), ctx.px(12f), Gravity.CENTER))
        // Last in the box, over the icon and the picture.
        iconFrame?.addView(this, LayoutParams(ctx.px(16f), ctx.px(16f), Gravity.TOP or Gravity.END).apply {
            topMargin = -ctx.px(3f); rightMargin = -ctx.px(3f)
        })
        unavailableBadge = this
    }

    /** The picture, made (hidden, over the icon and under the badge) the first time one loads. */
    private fun picture(): ImageView = pictureView ?: ImageView(ctx.context).apply {
        scaleType = ImageView.ScaleType.FIT_XY
        visibility = GONE
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        // Right after the icon's box: under a badge made before it.
        iconFrame?.addView(this, 1, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        pictureView = this
    }

    /** The zones this tile follows for its badge ([followZones]); declared before the first draw, in the constructor. */
    private val followedZones = HashSet<String>()

    /**
     * The tile's state line when it changes on its own (a relative time, a timer counting down), for [TimestampText] to
     * draw again each second while on screen; null when it doesn't (or the state is hidden).
     */
    private val tileStateTicking: (EntityState, Long) -> String? = { s, now ->
        if (config.raw.optBoolean("hide_state", false)) null
        else HaStateDisplay.of(s, HaStateDisplay.contentOf(config.raw.opt("state_content")), now, ctx.haConfig()).takeIf { it.tickMs != null }?.text
    }

    /** HA draws the tile icon's tinted shape only when the icon has an action. */
    private var iconInteractive = true

    /** The icon's action is HA's toggle (its default for the domains that have one). */
    private var iconToggles = false

    /** The tile's own content, hidden while HA would show "Entity not found" instead. */
    private var tileBody: View? = null
    /** HA's `hui-warning` for a tile whose entity doesn't exist, built the first time it's needed. */
    internal var missingWarning: View? = null
        private set

    /** The features slot beside the info (`features_position: inline`), sized to HA's half-card width. */
    private var inlineFeatures: View? = null

    override fun measureCard(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        mushroom?.row?.let { row ->
            // Mushroom 5's `.container { margin: calc(-1 * var(--ha-card-border-width, 1px)) }`: the variable, not the
            // border drawn. With the theme's border the content covers it; with `border: none` (card-mod) it runs past
            // each edge by the variable, and the card is that much shorter than its 56 px content (the S1's alarm row).
            val m = drawnBorderPx - (ctx.theme.cardBorderWidthDp * ctx.dp).toInt()
            val lp = row.layoutParams as MarginLayoutParams
            if (lp.topMargin != m || lp.leftMargin != m) lp.setMargins(m, m, m, m)
        }
        inlineFeatures?.let { slot ->
            if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
                // hui-card-features beside the content: calc(50% - var(--column-gap) / 2 - 12px), 12 px end padding.
                val w = (MeasureSpec.getSize(widthMeasureSpec) / 2 - ctx.px(16f)).coerceAtLeast(0)
                if (slot.layoutParams.width != w) slot.layoutParams.width = w
            }
        }
        super.measureCard(widthMeasureSpec, heightMeasureSpec)
    }

    /**
     * Shows HA's `hui-warning` in place of the tile while [missing]: hui-error-card as a warning, titled "Entity not
     * found" for an administrator and the icon alone for anyone else ([errorCardContent]).
     */
    private fun showMissing(missing: Boolean) {
        if (!missing && missingWarning == null) return
        val warning = missingWarning ?: errorCardContent(ctx, HaErrorSeverity.WARNING, HaText.ui("ui.card.common.entity_not_found"),
            over = ctx.theme.cardBackgroundColor).also {
            addView(it, LayoutParams(MATCH_PARENT, MATCH_PARENT))
            missingWarning = it
        }
        warning.visibility = if (missing) VISIBLE else GONE
        tileBody?.visibility = if (missing) INVISIBLE else VISIBLE
    }

    init {
        if (config.type == MUSHROOM_TEMPLATE) buildMushroomTemplate() else buildTile()
    }

    // Mushroom 5 lays its own padding inside the card: card-mod padding on ha-card adds to it.
    override fun clearDefaultInnerPadding() {
        if (mushroom == null) super.clearDefaultInnerPadding()
    }

    override fun onCardModApplied(mod: dev.glassworks.engine.vitro.theme.CardModStyle) {
        val sp = mod.fontSizeSp ?: return
        val scaled = ctx.scaledSp(sp)
        val sub = ctx.scaledSp((sp * 0.75f).coerceAtLeast(12f))
        // Mushroom 5's tile info sets its own font sizes; a font-size on ha-card doesn't reach it.
        if (mushroom == null) {
            titleView.textSize = scaled
            titleView.typeface = VitroFonts.typeface(ctx.context, Inter.MEDIUM, scaled)
            stateView.textSize = sub
            stateView.typeface = VitroFonts.typeface(ctx.context, Inter.REGULAR, sub)
            // The line boxes grow with the text (line-height 1.6 and 1.2).
            titleView.layoutParams?.let { it.height = (scaled * 1.6f * ctx.dp).roundToInt(); titleView.layoutParams = it }
            stateView.layoutParams?.let { it.height = (sub * 1.2f * ctx.dp).roundToInt(); stateView.layoutParams = it }
        }
    }

    private fun buildTile() {
        val pad = ctx.px(10f)
        val vertical = config.raw.optBoolean("vertical", false) || config.raw.optString("layout") == "vertical"
        val row = LinearLayout(ctx.context).apply {
            orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = if (vertical) Gravity.CENTER else Gravity.CENTER_VERTICAL
            // hui-tile-card's `.content`: `min-height: 56px; padding: 0 10px`, children centred, so the 36.8 px info
            // column fits in 56 px (a 10 px top and bottom padding would make the tile 56.8).
            if (vertical) setPadding(pad, pad, pad, pad) else setPadding(pad, 0, pad, 0)
            minimumHeight = ctx.px(56f)
        }

        val badgeSize = ctx.px(36f)
        val iconPad = ctx.px(8f)
        iconWrap.apply {
            gravity = Gravity.CENTER
            setPadding(iconPad, iconPad, iconPad, iconPad)
            addView(iconBadge, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        // ha-tile-badge: a 16 px orange disc with a 12 px exclamation over the shape's top-right while unavailable.
        // The picture and the badge join it when first shown ([picture], [badge]).
        val iconFrame = FrameLayout(ctx.context).apply {
            clipChildren = false
            clipToPadding = false
            addView(iconWrap, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        this.iconFrame = iconFrame
        row.clipChildren = false
        row.clipToPadding = false
        row.addView(iconFrame, LinearLayout.LayoutParams(badgeSize, badgeSize).apply {
            if (vertical) bottomMargin = ctx.px(8f) else marginEnd = ctx.px(10f)
        })

        val labels = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (vertical) Gravity.CENTER_HORIZONTAL else Gravity.START
            titleView.maxLines = 1
            titleView.ellipsize = TextUtils.TruncateAt.END
            stateView.maxLines = 1
            stateView.ellipsize = TextUtils.TruncateAt.END
            // ha-tile-info: 14 px / 500 on a 22.4 px line over 12 px on a 14.4 px line, both centered in their box.
            titleView.gravity = Gravity.CENTER_VERTICAL or (if (vertical) Gravity.CENTER_HORIZONTAL else Gravity.START)
            stateView.gravity = titleView.gravity
            titleView.letterSpacing = 0.1f / 14f
            stateView.letterSpacing = 0.4f / 12f
            addView(titleView, LinearLayout.LayoutParams(if (vertical) WRAP_CONTENT else MATCH_PARENT, (22.4f * ctx.dp).roundToInt()))
            addView(stateView, LinearLayout.LayoutParams(if (vertical) WRAP_CONTENT else MATCH_PARENT, (14.4f * ctx.dp).roundToInt()))
        }
        row.addView(labels, LinearLayout.LayoutParams(if (vertical) WRAP_CONTENT else 0, WRAP_CONTENT, if (vertical) 0f else 1f))

        val featuresArr = config.raw.optJSONArray("features")
        val featureTypes = if (featuresArr != null) {
            (0 until featuresArr.length()).mapNotNull { featuresArr.optJSONObject(it)?.optString("type", "")?.takeIf(String::isNotEmpty) }
        } else {
            emptyList()
        }
        // HA's tile: the info row takes the height the features leave (centered in it), the features sit at the bottom.
        val rootCol = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.VERTICAL
            addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, 1f))
        }
        val entityId = config.entity
        var brightnessSlider: HaHorizontalSliderView? = null
        var selectBox: TextView? = null
        if ("light-brightness" in featureTypes && entityId != null) {
            // HA's brightness feature is a 1..100 slider of brightness_pct.
            brightnessSlider = HaHorizontalSliderView(
                ctx = ctx,
                accentColor = ctx.theme.activeColor("light"),
                haGeometry = true,
                onCommit = { frac ->
                    val pct = (1f + frac * 99f).roundToInt().coerceIn(1, 100)
                    ctx.callService("light", "turn_on", JSONObject().put("entity_id", entityId).put("brightness_pct", pct), null)
                },
            )
            brightnessSlider.tag = FEATURE_TAG + "light-brightness"
            rootCol.addView(brightnessSlider, LinearLayout.LayoutParams(MATCH_PARENT, ctx.px(42f)).apply {
                leftMargin = ctx.px(12f); rightMargin = ctx.px(12f); bottomMargin = ctx.px(12f)
            })
        }
        if ("select-options" in featureTypes && entityId != null) {
            // HA 2026's select feature: a filled dropdown box showing the current option; tap for the list.
            // ha-control-select-menu: the value (p.value) is 14 px, 20 px in, centered; a 20 px arrow 10 px from the end.
            val current = textView(ctx, 14f, ctx.theme.primaryTextColor, Inter.REGULAR).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val box = LinearLayout(ctx.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ctx.px(20f), 0, ctx.px(10f), 0)
                background = featureFill(ctx)
                applyHaRipple(this, 10f * ctx.dp, ctx.theme.dark)
                isClickable = true
                addView(current, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(MdiIconView(ctx.context).apply { setIcon("mdi:menu-down", ctx.theme.primaryTextColor) },
                    LinearLayout.LayoutParams(ctx.px(20f), ctx.px(20f)).apply { marginStart = ctx.px(10f) })
                setOnClickListener { v -> showSelectMenu(v, entityId) }
                // HA's visually hidden "Option" label, for TalkBack.
                contentDescription = "Option"
            }
            selectBox = current
            box.tag = FEATURE_TAG + "select-options"
            rootCol.addView(box, LinearLayout.LayoutParams(MATCH_PARENT, ctx.px(42f)).apply {
                leftMargin = ctx.px(12f); rightMargin = ctx.px(12f); bottomMargin = ctx.px(12f)
            })
        }
        if ("cover-open-close" in featureTypes && entityId != null) {
            // hui-cover-open-close-card-feature: an ha-control-button-group of the open, stop and close buttons the
            // cover supports, 12 px apart, each disabled while it can't act (canOpen, canStop, canClose).
            val coverRow = LinearLayout(ctx.context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(ctx.px(12f), 0, ctx.px(12f), ctx.px(12f))
            }
            class CoverButton(val bit: Int, val service: String, val view: LinearLayout, val icon: MdiIconView)
            val buttons = listOf(
                Triple(COVER_OPEN, "open_cover", "ui.card.cover.open_cover"),
                Triple(COVER_STOP, "stop_cover", "ui.card.cover.stop_cover"),
                Triple(COVER_CLOSE, "close_cover", "ui.card.cover.close_cover"),
            ).map { (bit, service, label) ->
                val icon = MdiIconView(ctx.context).apply { alpha = 0.95f }
                val btn = LinearLayout(ctx.context).apply {
                    gravity = Gravity.CENTER
                    background = featureFill(ctx)
                    applyHaRipple(this, 12f * ctx.dp, ctx.theme.dark)
                    contentDescription = HaText.ui(label)
                    addView(icon, LinearLayout.LayoutParams(ctx.px(20f), ctx.px(20f)))
                    // A disabled ha-control-button sends nothing.
                    setOnClickListener { v -> if (v.isEnabled) ctx.callService("cover", service, JSONObject().put("entity_id", entityId), null) }
                }
                coverRow.addView(btn, LinearLayout.LayoutParams(0, ctx.px(42f), 1f))
                CoverButton(bit, service, btn, icon)
            }
            coverRow.tag = FEATURE_TAG + "cover-open-close"
            rootCol.addView(coverRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            featureBinders += { s ->
                val features = (s?.attributes?.get("supported_features") as? Number)?.toInt() ?: 0
                val supported = s != null && s.domain == "cover" && (features and (COVER_OPEN or COVER_CLOSE)) != 0
                coverRow.visibility = if (supported) VISIBLE else GONE
                var first = true
                for (b in buttons) {
                    val shown = supported && (features and b.bit) != 0
                    b.view.visibility = if (shown) VISIBLE else GONE
                    if (!shown || s == null) continue
                    (b.view.layoutParams as LinearLayout.LayoutParams).marginStart = if (first) 0 else ctx.px(12f)
                    first = false
                    val enabled = when (b.bit) {
                        COVER_OPEN -> CoverState.canOpen(s)
                        COVER_CLOSE -> CoverState.canClose(s)
                        else -> s.state != "unavailable"
                    }
                    b.view.isEnabled = enabled
                    b.view.isClickable = enabled
                    val iconName = when (b.bit) {
                        COVER_OPEN -> CoverState.openIcon(s)
                        COVER_CLOSE -> CoverState.closeIcon(s)
                        else -> "mdi:stop"
                    }
                    // ha-control-button:disabled: the icon in --disabled-text-color on the same fill.
                    b.icon.setIcon(iconName, if (enabled) ctx.theme.primaryTextColor else ctx.theme.disabledTextColor)
                }
                coverRow.requestLayout()
            }
        }
        if (entityId != null) {
            addExtraFeatures(rootCol, featuresArr, featureTypes, entityId, pad)
        }
        // `features_position: inline`: HA shows the first feature beside the info, 36 px tall and centered, and the
        // others below it in up to two columns (frontend 20261028's computeCardFeatureLayout).
        if (!vertical && config.raw.optString("features_position") == "inline" && rootCol.childCount > 1) {
            val built = (1 until rootCol.childCount).map { rootCol.getChildAt(it) }
            val first = featureTypes.firstNotNullOfOrNull { t -> built.firstOrNull { it.tag == FEATURE_TAG + t } } ?: built.first()
            // The rest in config order.
            val order = { v: View -> featureTypes.indexOfFirst { v.tag == FEATURE_TAG + it }.let { if (it < 0) Int.MAX_VALUE else it } }
            val rest = built.filter { it !== first }.sortedBy(order)
            while (rootCol.childCount > 1) rootCol.removeViewAt(1)
            if (rest.size == 1) {
                rootCol.addView(rest[0])
            } else {
                for (pair in rest.chunked(2)) {
                    val line = LinearLayout(ctx.context).apply { orientation = LinearLayout.HORIZONTAL }
                    for (v in pair) {
                        val lp = v.layoutParams as? LinearLayout.LayoutParams
                        line.addView(v, LinearLayout.LayoutParams(0, lp?.height ?: WRAP_CONTENT, 1f).apply {
                            if (lp != null) { topMargin = lp.topMargin; bottomMargin = lp.bottomMargin }
                        })
                    }
                    // A lone last one keeps its column's width.
                    if (pair.size == 1) line.addView(Space(ctx.context), LinearLayout.LayoutParams(0, 0, 1f))
                    rootCol.addView(line, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                }
            }
            if (first.paddingBottom == ctx.px(12f)) first.setPadding(0, 0, 0, 0)
            val slot = FrameLayout(ctx.context).apply {
                setPadding(0, 0, ctx.px(12f), 0)
                addView(first, LayoutParams(MATCH_PARENT, ctx.px(36f), Gravity.CENTER_VERTICAL))
            }
            row.setPadding(pad, pad, 0, pad)
            (labels.layoutParams as LinearLayout.LayoutParams).marginEnd = pad
            row.addView(slot, LinearLayout.LayoutParams(0, ctx.px(36f)))
            inlineFeatures = slot
        }
        addView(rootCol, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        tileBody = rootCol

        // HA's `hui-tile-card`: the body opens more-info; the icon toggles what can be toggled.
        val tapAction = ActionConfig.parse(config.raw.optJSONObject("tap_action"), "more-info")
        // HA's tile icon acts (and draws its tinted shape) only when it has an action: its icon_tap_action, or a
        // toggle / press for the domains that have one. Otherwise a tap on it is a tap on the card.
        val iconDefault = if (entityId?.substringBefore('.') in ICON_TOGGLE_DOMAINS || entityId?.substringBefore('.') in PRESS_DOMAINS) "toggle" else "none"
        val iconTapAction = ActionConfig.parse(config.raw.optJSONObject("icon_tap_action"), iconDefault)
        iconInteractive = iconTapAction.action != "none"
        iconToggles = iconTapAction.action == "toggle"
        val holdAction = holdActionFor(config.raw, entityId)

        isClickable = true
        setOnClickListener { ctx.performAction(tapAction, entityId) }
        bindHoldAction(holdAction, entityId)
        applyHaRipple(this, ctx.theme.cardBorderRadiusDp * ctx.dp, ctx.theme.dark)
        if (iconInteractive) {
            iconWrap.isClickable = true
            applyHaRipple(iconWrap, 999f, ctx.theme.dark)
            iconWrap.setOnClickListener { ctx.performAction(iconTapAction, entityId) }
            iconWrap.bindHoldAction(ctx, holdAction, entityId)
        }

        if (entityId != null) {
            observe(entityId) { s ->
                showMissing(s == null && ctx.store.isMissing(entityId))
                renderTileBadge(s)
                renderState(s)
                brightnessSlider?.let { sb ->
                    // HA draws the feature only for a light that can dim (supportsLightBrightnessCardFeature); without
                    // it the info row takes the whole card, centred.
                    sb.visibility = if (s != null && lightSupportsBrightness(s)) VISIBLE else GONE
                    // HA's feature color: the tile's (`--feature-color: var(--tile-color)`).
                    sb.setAccentColor(if (isTile) tileColor(s) else s?.lightTintColor() ?: ctx.theme.activeColor("light"))
                    sb.inactiveColor = if (s?.state == "unavailable") ctx.theme.disabledTextColor else ctx.theme.stateInactiveColor
                    val on = s?.isActive == true
                    sb.showHandle = on
                    val bri = (s?.attributes?.get("brightness") as? Number)?.toInt()?.takeIf { it in 1..255 }
                    when {
                        !on -> sb.setValue(0f, active = false)
                        bri != null -> {
                            lastBrightness = bri
                            sb.setValue(brightnessFraction(bri), active = true)
                        }
                        // An optimistic `on` before HA reports `brightness`: restore the last level, or hold
                        // still until the real value lands, never flash to 100%.
                        else -> sb.setValue(lastBrightness?.let { brightnessFraction(it) } ?: sb.value, active = true)
                    }
                }
                selectBox?.setTextIfChanged(s?.let { HaDisplay.stateLabel(it) ?: it.state } ?: "…")
                featureBinders.forEach { it(s) }
                // A feature the entity doesn't support draws nothing; with none shown, HA's features box stays, empty,
                // with its 12 px bottom padding, and the info centres above it.
                if (inlineFeatures == null && rootCol.childCount > 1) {
                    val anyShown = (1 until rootCol.childCount).any { rootCol.getChildAt(it).visibility == VISIBLE }
                    rootCol.setPadding(0, 0, 0, if (anyShown) 0 else ctx.px(12f))
                }
            }
        } else {
            titleView.text = config.name(null) ?: config.title ?: "Tile"
            // No entity: the icon's action still needs a name, the tile's own or what it does.
            if (iconInteractive) iconWrap.contentDescription = config.name(null) ?: config.title
                ?: ActionConfig.parse(config.raw.optJSONObject("icon_tap_action"), "none").action.replace('-', ' ').replaceFirstChar { it.titlecase(Locale.ROOT) }
            stateView.visibility = GONE
            updateBadge(null, active = false)
        }
    }

    /**
     * HA 2026's other inline tile features: `climate-hvac-modes`, `target-temperature`, `fan-speed`,
     * `cover-position`, `light-color-temp`, `media-player-volume-slider`, `toggle`, `media-player-playback`,
     * `light-color-favorites`, `numeric-input` and `vacuum-commands`, in the order the card lists them.
     */
    private fun addExtraFeatures(
        rootCol: LinearLayout,
        featuresArr: JSONArray?,
        featureTypes: List<String>,
        entityId: String,
        pad: Int,
    ) {
        // hui-card-features: 42 px features, 12 px in from the sides and the bottom.
        fun lp() = LinearLayout.LayoutParams(MATCH_PARENT, ctx.px(42f)).apply {
            leftMargin = ctx.px(12f); rightMargin = ctx.px(12f); bottomMargin = ctx.px(12f)
        }
        fun data() = JSONObject().put("entity_id", entityId)
        fun featureConfig(type: String): JSONObject? = featuresArr?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.firstOrNull { it.optString("type") == type }
        }
        fun num(s: EntityState?, key: String): Double? = (s?.attributes?.get(key) as? Number)?.toDouble()

        fun segmentBar() = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(ctx.px(3f), ctx.px(3f), ctx.px(3f), ctx.px(3f))
            background = GradientDrawable().apply {
                cornerRadius = 10f * ctx.dp
                setColor(if (ctx.theme.dark) SEGMENT_DARK else SEGMENT_LIGHT)
            }
        }
        /** One icon segment of a bar: `paint(selected, color)` fills it like HA's selected segment. */
        class Segment(val button: LinearLayout, val icon: MdiIconView) {
            fun paint(selected: Boolean, color: Int, textColor: Int) {
                icon.tintColor = if (selected) Color.WHITE else textColor
                button.background = if (selected) GradientDrawable().apply {
                    cornerRadius = 8f * button.resources.displayMetrics.density
                    setColor(color)
                } else null
            }
        }
        fun segment(bar: LinearLayout, iconName: String, label: String, onTap: () -> Unit): Segment {
            val icon = MdiIconView(ctx.context).apply { setIcon(iconName, ctx.theme.primaryTextColor) }
            val btn = LinearLayout(ctx.context).apply {
                gravity = Gravity.CENTER
                isClickable = true
                applyHaRipple(this, 8f * ctx.dp, ctx.theme.dark)
                contentDescription = label
                addView(icon, LinearLayout.LayoutParams(ctx.px(20f), ctx.px(20f)))
                setOnClickListener { onTap() }
            }
            bar.addView(btn, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
            return Segment(btn, icon)
        }
        val domain = entityId.substringBefore('.')

        for (type in featureTypes.distinct()) {
            val before = rootCol.childCount
            when (type) {
                "climate-hvac-modes" -> {
                    val configured = featureConfig(type)?.optJSONArray("hvac_modes")
                        ?.let { arr -> (0 until arr.length()).map { arr.optString(it) }.filter(String::isNotEmpty) }
                    val bar = LinearLayout(ctx.context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(ctx.px(3f), ctx.px(3f), ctx.px(3f), ctx.px(3f))
                        background = GradientDrawable().apply {
                            cornerRadius = 10f * ctx.dp
                            setColor(if (ctx.theme.dark) SEGMENT_DARK else SEGMENT_LIGHT)
                        }
                    }
                    var shownModes: List<String> = emptyList()
                    val buttons = ArrayList<Pair<String, Pair<LinearLayout, MdiIconView>>>()
                    rootCol.addView(bar, lp())
                    featureBinders += { s ->
                        val modes = configured?.takeIf { it.isNotEmpty() }
                            ?: (s?.attributes?.get("hvac_modes") as? List<*>)?.mapNotNull { it?.toString() }?.takeIf { it.isNotEmpty() }
                            ?: DEFAULT_HVAC_MODES
                        if (modes != shownModes) {
                            shownModes = modes
                            bar.removeAllViews()
                            buttons.clear()
                            modes.forEach { mode ->
                                val icon = MdiIconView(ctx.context)
                                val btn = LinearLayout(ctx.context).apply {
                                    gravity = Gravity.CENTER
                                    isClickable = true
                                    applyHaRipple(this, 8f * ctx.dp, ctx.theme.dark)
                                    contentDescription = mode.replace('_', ' ').replaceFirstChar { it.titlecase(Locale.ROOT) }
                                    // ha-control-select: a radio per mode, checked on the current one.
                                    VitroAccess.role(this, A11yRole.RADIO) { ctx.store[entityId]?.state == mode }
                                    addView(icon, LinearLayout.LayoutParams(ctx.px(20f), ctx.px(20f)))
                                    setOnClickListener {
                                        ctx.callService("climate", "set_hvac_mode", data().put("hvac_mode", mode), null)
                                    }
                                }
                                bar.addView(btn, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
                                buttons += mode to (btn to icon)
                            }
                        }
                        buttons.forEach { (mode, views) ->
                            val (btn, icon) = views
                            val selected = s?.state == mode
                            icon.setIcon(ThermostatCardView.modeIcon(mode), if (selected) Color.WHITE else ctx.theme.primaryTextColor)
                            btn.background = if (selected) GradientDrawable().apply {
                                cornerRadius = 8f * ctx.dp
                                setColor(hvacModeColor(mode))
                            } else null
                        }
                    }
                }
                "target-temperature" -> {
                    // hui-target-temperature-card-feature: ha-control-number-buttons for the target, or two side by
                    // side (12 px apart) for a heat/cool range, each kept on its side of the other. Each is the value
                    // centered across its whole width with 35 px − and + over its ends; at 100 px or less the unit
                    // goes (`@container (max-width: 100px)`), so narrow tiles keep both numbers.
                    val unit = ThermostatCardView.temperatureUnit(ctx)
                    fun step(handle: ClimateHandle?, sign: Int) {
                        val st = ctx.store[entityId] ?: return
                        val t = ClimateTargets.of(st.attributes)
                        val stepSize = ClimateTargets.stepOf(st.attributes, unit)
                        val min = num(st, "min_temp") ?: 7.0
                        val max = num(st, "max_temp") ?: 35.0
                        // Written through the shared optimistic path: every view of the entity shows the new value at
                        // once, the next press steps from it, and a refusal puts HA's value back everywhere.
                        if (handle == null) {
                            val cur = t.value ?: return
                            // Rounded to the step's decimals, then kept within the device's limits.
                            val next = ClimateTargets.roundToStep(Math.round((cur + sign * stepSize) / stepSize) * stepSize, stepSize).coerceIn(min, max)
                            if (next == cur) return
                            ctx.callOptimistic(ClimateTargets.withValue(st, next), "climate", "set_temperature", data().put("temperature", next))
                        } else {
                            if (!t.isRange) return
                            val (lo, hi) = ClimateTargets.stepped(handle, sign * stepSize, t.low ?: return, t.high ?: return, min, max)
                            if (lo == t.low && hi == t.high) return
                            ctx.callOptimistic(ClimateTargets.withRange(st, lo, hi), "climate", "set_temperature", ClimateTargets.rangeData(entityId, lo, hi))
                        }
                    }
                    /** One ha-control-number-buttons: its value view, and whether it's narrow enough to drop the unit. */
                    class NumberButtons(val root: FrameLayout, val value: TextView) {
                        var narrow = false
                    }
                    var show: () -> Unit = {}
                    fun numberButtons(handle: ClimateHandle?): NumberButtons {
                        val value = textView(ctx, 14f, ctx.theme.primaryTextColor, Inter.MEDIUM).apply {
                            gravity = Gravity.CENTER
                            fontFeatureSettings = "tnum"
                            maxLines = 1
                        }
                        fun stepButton(iconName: String, sign: Int) = FrameLayout(ctx.context).apply {
                            isClickable = true
                            isFocusable = true
                            applyHaRipple(this, 10f * ctx.dp, ctx.theme.dark)
                            contentDescription = ThermostatCardView.stepperLabel(sign, handle)
                            addView(MdiIconView(ctx.context).apply { setIcon(iconName, ctx.theme.primaryTextColor) },
                                FrameLayout.LayoutParams(ctx.px(16f), ctx.px(16f), Gravity.CENTER))
                            setOnClickListener { step(handle, sign) }
                        }
                        var group: NumberButtons? = null
                        val root = object : FrameLayout(ctx.context) {
                            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                                super.onSizeChanged(w, h, oldw, oldh)
                                val narrow = w <= ctx.px(100f)
                                group?.let { g -> if (g.narrow != narrow) { g.narrow = narrow; post { show() } } }
                            }
                        }.apply {
                            background = GradientDrawable().apply {
                                cornerRadius = 10f * ctx.dp
                                setColor(if (ctx.theme.dark) SEGMENT_DARK else SEGMENT_LIGHT)
                            }
                            addView(value, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                            addView(stepButton("mdi:minus", -1), FrameLayout.LayoutParams(ctx.px(35f), MATCH_PARENT, Gravity.START))
                            addView(stepButton("mdi:plus", 1), FrameLayout.LayoutParams(ctx.px(35f), MATCH_PARENT, Gravity.END))
                        }
                        return NumberButtons(root, value).also { group = it }
                    }
                    val single = numberButtons(null)
                    val low = numberButtons(ClimateHandle.LOW)
                    val high = numberButtons(ClimateHandle.HIGH)
                    val rangeRow = LinearLayout(ctx.context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        addView(low.root, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
                        addView(high.root, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { marginStart = ctx.px(12f) })
                    }
                    val holder = FrameLayout(ctx.context).apply {
                        addView(single.root, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                        addView(rangeRow, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                    }
                    rootCol.addView(holder, lp())
                    show = {
                        val st = ctx.store[entityId]
                        val t = st?.let { ClimateTargets.of(it.attributes) }
                        val digits = ClimateTargets.decimalsOf(st?.let { ClimateTargets.stepOf(it.attributes, unit) } ?: 0.5)
                        val range = t?.isRange == true
                        single.root.visibility = if (range) GONE else VISIBLE
                        rangeRow.visibility = if (range) VISIBLE else GONE
                        // HA's number: the step's decimals, then the unit unless the buttons are narrow.
                        fun fill(g: NumberButtons, v: Double?, name: String) {
                            val number = v?.let { HaDisplay.formatFixed(it, digits) }
                            g.value.setTextIfChanged(number?.let { if (g.narrow) it else "$it $unit" } ?: "–")
                            // HA's spin button, named after the target, with its unit always.
                            g.value.contentDescription = "$name, " + (number?.let { "$it $unit" } ?: "–")
                        }
                        fill(single, t?.value, ClimateTargets.VALUE_NAME)
                        fill(low, t?.low, ClimateTargets.LOW_NAME)
                        fill(high, t?.high, ClimateTargets.HIGH_NAME)
                    }
                    featureBinders += { _ -> show() }
                }
                "fan-speed" -> {
                    // hui-fan-speed-card-feature: a slider of `percentage` on the fan's `percentage_step`, or, for a fan
                    // with 6 speeds or fewer (off included), an ha-control-select of them. Only for a fan that can
                    // set its speed.
                    fun step(st: EntityState?) = num(st, "percentage_step")?.takeIf { it > 0 } ?: 1.0
                    fun setPercentage(pct: Double) {
                        val v: Any = if (pct % 1.0 == 0.0) pct.toLong() else pct
                        ctx.callService("fan", "set_percentage", data().put("percentage", v), null)
                    }
                    val slider = HaHorizontalSliderView(ctx, ctx.theme.activeColor("fan"), haGeometry = true) { frac ->
                        // ha-control-slider's steppedValue: snapped to the step, then kept in 0..100. A fan that went
                        // unavailable during the drag gets nothing, as HA's disabled slider sends nothing.
                        val cur = ctx.store[entityId]
                        if (cur == null || cur.state == "unavailable") return@HaHorizontalSliderView
                        val st = step(cur)
                        setPercentage((Math.round(frac * 100.0 / st) * st).coerceIn(0.0, 100.0))
                    }
                    val select = HaControlSelect(ctx)
                    val holder = FrameLayout(ctx.context).apply {
                        addView(slider, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                        addView(select, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                    }
                    rootCol.addView(holder, lp())
                    var lastPct: Double? = null
                    var shownSpeeds: List<String>? = null
                    featureBinders += { s ->
                        val features = (s?.attributes?.get("supported_features") as? Number)?.toInt() ?: 0
                        val supported = s != null && s.domain == "fan" && (features and FAN_SET_SPEED) != 0
                        holder.visibility = if (supported) VISIBLE else GONE
                        if (s != null && supported) {
                            val color = if (isTile) tileColor(s) else ctx.theme.activeColor("fan")
                            val active = HaStateActive.of(s)
                            val speeds = FanSpeeds.speeds(step(s))
                            slider.visibility = if (speeds == null) VISIBLE else GONE
                            select.visibility = if (speeds == null) GONE else VISIBLE
                            // HA disables both while the fan is unavailable (off, it still sets a speed).
                            slider.isEnabled = s.state != "unavailable"
                            if (speeds != null) {
                                if (speeds != shownSpeeds) {
                                    shownSpeeds = speeds
                                    select.setOptions(speeds.map { sp ->
                                        HaControlSelect.Option(sp, FanSpeeds.icon(sp, speeds), if (FanSpeeds.numbered(sp)) HaFormat.formatNumber(sp) else null,
                                            FanSpeeds.label(s, sp)) { v ->
                                            // One state for the whole tap: the option means a speed of the steps it was
                                            // built for. A step changed since (the buttons not redrawn yet) sends nothing.
                                            val cur = ctx.store[entityId] ?: return@Option
                                            val st = step(cur)
                                            if (cur.state == "unavailable" || FanSpeeds.speeds(st) != speeds) return@Option
                                            setPercentage(FanSpeeds.toPercentage(v, speeds, st).toDouble())
                                        }
                                    })
                                }
                                val pct = if (active) num(s, "percentage") ?: 0.0 else 0.0
                                select.bind(FanSpeeds.fromPercentage(pct, speeds, step(s)), color, s.state == "unavailable")
                            } else {
                                slider.setAccentColor(color)
                                val pct = num(s, "percentage")?.takeIf { it > 0.0 }
                                when {
                                    !active -> slider.setValue(0f, active = false)
                                    pct != null -> {
                                        lastPct = pct
                                        slider.setValue(pct.toFloat() / 100f, active = true)
                                    }
                                    // Optimistic `on` before HA reports `percentage`: no flash to 100%.
                                    else -> slider.setValue(lastPct?.let { it.toFloat() / 100f } ?: slider.value, active = true)
                                }
                            }
                        }
                    }
                }
                "cover-position" -> {
                    val slider = HaHorizontalSliderView(ctx, ctx.theme.activeColor("cover")) { frac ->
                        val pos = (frac * 100f).roundToInt().coerceIn(0, 100)
                        ctx.callService("cover", "set_cover_position", data().put("position", pos), null)
                    }
                    rootCol.addView(slider, lp())
                    featureBinders += { s ->
                        val pos = num(s, "current_position") ?: if (s?.state == "open") 100.0 else 0.0
                        slider.setValue(pos.toFloat() / 100f, active = pos > 0)
                    }
                }
                "light-color-temp" -> {
                    val bar = HaColorTempBarView(ctx) { kelvin ->
                        ctx.callService("light", "turn_on", data().put("color_temp_kelvin", kelvin), null)
                    }
                    rootCol.addView(bar, lp())
                    featureBinders += { s ->
                        val minK = num(s, "min_color_temp_kelvin")?.toInt() ?: HaColorTempBarView.DEFAULT_MIN_K
                        val maxK = num(s, "max_color_temp_kelvin")?.toInt() ?: HaColorTempBarView.DEFAULT_MAX_K
                        bar.bind(minK, maxK, num(s, "color_temp_kelvin")?.toInt(), s?.state == "on")
                    }
                }
                "media-player-volume-slider" -> {
                    // HA's feature: the slider in the player's state color, then a 36 px mute button 12 px after it.
                    val slider = HaHorizontalSliderView(ctx, ctx.theme.activeColor("media_player"), haGeometry = true) { frac ->
                        val vol = (frac * 100f).roundToInt() / 100.0
                        ctx.callService("media_player", "volume_set", data().put("volume_level", vol), null)
                    }
                    val muteIcon = MdiIconView(ctx.context)
                    val mute = FrameLayout(ctx.context).apply {
                        background = featureFill(ctx)
                        applyHaRipple(this, 12f * ctx.dp, ctx.theme.dark)
                        isClickable = true
                        contentDescription = HaText.ui("ui.card.media_player.volume_mute")
                        addView(muteIcon, LayoutParams(ctx.px(20f), ctx.px(20f), Gravity.CENTER))
                        setOnClickListener {
                            val muted = ctx.store[entityId]?.attributes?.get("is_volume_muted") == true
                            ctx.callService("media_player", "volume_mute", data().put("is_volume_muted", !muted), null)
                        }
                    }
                    val bar = LinearLayout(ctx.context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        addView(slider, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
                        addView(mute, LinearLayout.LayoutParams(ctx.px(36f), MATCH_PARENT).apply { marginStart = ctx.px(12f) })
                    }
                    rootCol.addView(bar, lp())
                    featureBinders += { s ->
                        val vol = num(s, "volume_level")
                        val live = s != null && s.state != "off" && !s.isUnavailable && vol != null
                        slider.setValue(vol?.toFloat() ?: 0f, active = live)
                        val muted = s?.attributes?.get("is_volume_muted") == true
                        muteIcon.setIcon(if (muted) "mdi:volume-off" else "mdi:volume-high", ctx.theme.primaryTextColor)
                    }
                }
                "toggle" -> {
                    // HA's `ha-control-switch` as two halves; the half matching the state fills with its color.
                    val (offLabel, onLabel) = when (domain) {
                        "lock" -> HaText.ui("ui.card.lock.lock") to HaText.ui("ui.card.lock.unlock")
                        "cover", "valve" -> "Close" to "Open"
                        else -> "Off" to "On"
                    }
                    fun service(on: Boolean): Pair<String, String> = when (domain) {
                        "lock" -> "lock" to if (on) "unlock" else "lock"
                        "cover" -> "cover" to if (on) "open_cover" else "close_cover"
                        "valve" -> "valve" to if (on) "open_valve" else "close_valve"
                        in TOGGLE_FEATURE_DOMAINS -> domain to if (on) "turn_on" else "turn_off"
                        else -> "homeassistant" to if (on) "turn_on" else "turn_off"
                    }
                    // ha-control-switch: the state color at 20% under a half-width knob in that color, holding the
                    // white 20 px icon of the state it shows (the domain's icon, not the entity's own).
                    val knob = FrameLayout(ctx.context).apply {
                        background = GradientDrawable().apply { cornerRadius = 12f * ctx.dp }
                    }
                    val bar = object : FrameLayout(ctx.context) {
                        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                            knob.layoutParams.width = MeasureSpec.getSize(widthMeasureSpec) / 2
                            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                        }
                    }
                    val track = GradientDrawable().apply { cornerRadius = 12f * ctx.dp }
                    bar.background = track
                    val knobIcon = MdiIconView(ctx.context)
                    knob.addView(knobIcon, LayoutParams(ctx.px(20f), ctx.px(20f), Gravity.CENTER))
                    bar.addView(knob, LayoutParams(0, MATCH_PARENT))
                    val hits = LinearLayout(ctx.context).apply { orientation = LinearLayout.HORIZONTAL }
                    bar.addView(hits, LayoutParams(MATCH_PARENT, MATCH_PARENT))
                    val halves = listOf(false to offLabel, true to onLabel).map { (on, label) ->
                        val half = LinearLayout(ctx.context).apply {
                            isClickable = true
                            applyHaRipple(this, 12f * ctx.dp, ctx.theme.dark)
                            contentDescription = label
                        }
                        // ha-control-switch's two states as TalkBack choices, the one it shows checked.
                        VitroAccess.role(half, A11yRole.RADIO) { half.isSelected }
                        hits.addView(half, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
                        on to half
                    }
                    fun paint(on: Boolean) {
                        val st = ctx.store[entityId]
                        val color = when {
                            domain == "lock" -> if (on) UNLOCKED_RED else LOCKED_GREEN
                            isTile -> tileColor(st)
                            on -> st?.lightTintColor() ?: ctx.theme.activeColor(domain)
                            else -> ctx.theme.stateInactiveColor
                        }
                        track.setColor(withAlpha(color, 0.2f))
                        (knob.background as GradientDrawable).setColor(color)
                        (knob.layoutParams as LayoutParams).gravity = if (on) Gravity.END else Gravity.START
                        knob.requestLayout()
                        val probe = EntityState(entityId, if (on) "on" else "off", st?.attributes?.filterKeys { it == "device_class" } ?: emptyMap())
                        knobIcon.setIcon(HaDisplay.domainIcon(probe) ?: toggleIcon(domain, on), Color.WHITE)
                        halves.forEach { (isOn, half) -> half.isSelected = isOn == on }
                    }
                    halves.forEach { (on, half) ->
                        half.setOnClickListener {
                            paint(on)
                            val (d, svc) = service(on)
                            ctx.callService(d, svc, data(), null)
                        }
                    }
                    rootCol.addView(bar, lp())
                    featureBinders += { s -> paint(s?.isActive == true) }
                }
                "media-player-playback" -> {
                    val bar = segmentBar()
                    segment(bar, "mdi:skip-previous", "Previous") {
                        ctx.callService("media_player", "media_previous_track", data(), null)
                    }
                    val playPause = segment(bar, "mdi:play", "Play") {
                        ctx.callService("media_player", "media_play_pause", data(), null)
                    }
                    segment(bar, "mdi:skip-next", "Next") {
                        ctx.callService("media_player", "media_next_track", data(), null)
                    }
                    segment(bar, "mdi:stop", "Stop") {
                        ctx.callService("media_player", "media_stop", data(), null)
                    }
                    rootCol.addView(bar, lp())
                    featureBinders += { s ->
                        val playing = s?.state == "playing"
                        playPause.icon.setIcon(if (playing) "mdi:pause" else "mdi:play")
                        playPause.button.contentDescription = if (playing) "Pause" else "Play"
                        playPause.paint(playing, ctx.theme.primaryColor, ctx.theme.primaryTextColor)
                    }
                }
                "light-color-favorites" -> {
                    val cfg = featureConfig(type)
                    val configured = (cfg?.optJSONArray("favorites") ?: cfg?.optJSONArray("favorite_colors")
                        ?: cfg?.optJSONArray("color_favorites"))
                        ?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it) } }
                        ?.takeIf { it.isNotEmpty() }
                    val row = LinearLayout(ctx.context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    var shown: List<JSONObject>? = null
                    rootCol.addView(row, lp())
                    featureBinders += { s ->
                        val favs = configured ?: defaultFavorites(s)
                        if (favs.map { it.toString() } != shown?.map { it.toString() }) {
                            shown = favs
                            row.removeAllViews()
                            favs.take(MAX_FAVORITES).forEachIndexed { i, fav ->
                                val swatch = View(ctx.context).apply {
                                    background = GradientDrawable().apply {
                                        shape = GradientDrawable.OVAL
                                        setColor(favoriteColor(fav))
                                        setStroke(ctx.px(1f).coerceAtLeast(1), ctx.theme.dividerColor)
                                    }
                                    isClickable = true
                                    applyHaRipple(this, 999f, ctx.theme.dark)
                                    contentDescription = "Favorite color ${i + 1}"
                                    setOnClickListener {
                                        val d = data()
                                        for (key in FAVORITE_KEYS) if (fav.has(key)) d.put(key, fav.get(key))
                                        ctx.callService("light", "turn_on", d, null)
                                    }
                                }
                                val cell = FrameLayout(ctx.context).apply {
                                    addView(swatch, FrameLayout.LayoutParams(ctx.px(32f), ctx.px(32f), Gravity.CENTER))
                                }
                                row.addView(cell, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
                            }
                        }
                        row.alpha = if (s?.state == "on") 1f else 0.6f
                    }
                }
                "numeric-input" -> {
                    val isCounter = domain == "counter"
                    fun lowOf(st: EntityState?) = num(st, if (isCounter) "minimum" else "min") ?: if (isCounter) null else 0.0
                    fun highOf(st: EntityState?) = num(st, if (isCounter) "maximum" else "max") ?: if (isCounter) null else 100.0
                    fun stepOf(st: EntityState?) = num(st, "step")?.takeIf { it > 0 } ?: 1.0
                    fun clamp(v: Double, st: EntityState?): Double {
                        var out = v
                        lowOf(st)?.let { out = out.coerceAtLeast(it) }
                        highOf(st)?.let { out = out.coerceAtMost(it) }
                        return out
                    }
                    // HA's ha-control-number-buttons: formatNumber with the registry's precision, no decimals for a
                    // whole step and value, else up to two with trailing zeros dropped ("1 x", "0.9").
                    fun format(v: Double, st: EntityState?): String {
                        val dp = HaDisplay.displayPrecision(entityId)
                        val n = when {
                            dp != null -> HaDisplay.formatState(v.toBigDecimal().toPlainString(), dp, null)
                            stepOf(st) % 1.0 == 0.0 && v % 1.0 == 0.0 -> HaDisplay.formatValue(v, 0)
                            else -> HaDisplay.formatValue(v)
                        }
                        return st?.unit?.let { "$n $it" } ?: n
                    }
                    fun setValue(v: Double) {
                        ctx.callService(domain, "set_value", data().put("value", if (v % 1.0 == 0.0) v.toLong() else v), null)
                    }
                    if (featureConfig(type)?.optString("style") == "slider" && !isCounter) {
                        val slider = HaHorizontalSliderView(ctx, ctx.theme.primaryColor) { frac ->
                            val st = ctx.store[entityId]
                            val lo = lowOf(st) ?: 0.0
                            val hi = highOf(st) ?: 100.0
                            val step = stepOf(st)
                            setValue(clamp(lo + Math.round(frac * (hi - lo) / step) * step, st))
                        }
                        rootCol.addView(slider, lp())
                        featureBinders += { s ->
                            val v = s?.state?.toDoubleOrNull()
                            val lo = lowOf(s) ?: 0.0
                            val hi = highOf(s) ?: 100.0
                            slider.setValue(if (v != null && hi > lo) ((v - lo) / (hi - lo)).toFloat() else 0f, active = v != null)
                        }
                    } else {
                        val value = textView(ctx, 14f, ctx.theme.primaryTextColor, Inter.MEDIUM).apply {
                            gravity = Gravity.CENTER
                            fontFeatureSettings = "tnum"
                        }
                        var pending: Double? = null
                        fun step(sign: Int) {
                            val st = ctx.store[entityId]
                            if (isCounter) {
                                ctx.callService("counter", if (sign > 0) "increment" else "decrement", data(), null)
                                val cur = pending ?: st?.state?.toDoubleOrNull() ?: return
                                pending = clamp(cur + sign * stepOf(st), st)
                                value.setTextIfChanged(format(pending!!, st))
                                return
                            }
                            val cur = pending ?: st?.state?.toDoubleOrNull() ?: return
                            val step = stepOf(st)
                            // The same grid as the slider: steps counted from the minimum (min 0.5, step 1: 1.5 -> 2.5).
                            val lo = lowOf(st) ?: 0.0
                            val next = clamp(lo + Math.round((cur + sign * step - lo) / step) * step, st)
                            pending = next
                            value.setTextIfChanged(format(next, st))
                            setValue(next)
                        }
                        fun stepButton(iconName: String, sign: Int, label: String) = LinearLayout(ctx.context).apply {
                            gravity = Gravity.CENTER
                            isClickable = true
                            applyHaRipple(this, 12f * ctx.dp, ctx.theme.dark)
                            contentDescription = label
                            addView(MdiIconView(ctx.context).apply { setIcon(iconName, ctx.theme.primaryTextColor) },
                                LinearLayout.LayoutParams(ctx.px(16f), ctx.px(16f)))
                            setOnClickListener { step(sign) }
                        }
                        // ha-control-number-buttons: 35 px buttons with 16 px glyphs on the feature fill.
                        val stepper = LinearLayout(ctx.context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            background = featureFill(ctx)
                            addView(stepButton("mdi:minus", -1, "Decrease"), LinearLayout.LayoutParams(ctx.px(35f), MATCH_PARENT))
                            addView(value, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                            addView(stepButton("mdi:plus", 1, "Increase"), LinearLayout.LayoutParams(ctx.px(35f), MATCH_PARENT))
                        }
                        rootCol.addView(stepper, lp())
                        featureBinders += { s ->
                            pending = null
                            value.setTextIfChanged(s?.state?.toDoubleOrNull()?.let { format(it, s) } ?: "–")
                        }
                    }
                }
                "vacuum-commands" -> {
                    val commands = featureConfig(type)?.optJSONArray("commands")
                        ?.let { arr -> (0 until arr.length()).map { arr.optString(it) }.filter { it in VACUUM_COMMANDS } }
                        ?.takeIf { it.isNotEmpty() }
                        ?: VACUUM_COMMANDS.keys.toList()
                    val bar = segmentBar()
                    var startPause: Segment? = null
                    commands.forEach { cmd ->
                        val (iconName, label) = VACUUM_COMMANDS.getValue(cmd)
                        val seg = segment(bar, iconOr(iconName, VACUUM_FALLBACK_ICONS[cmd] ?: "mdi:robot-vacuum"), label) {
                            val svc = when (cmd) {
                                "start_pause" -> if (ctx.store[entityId]?.state == "cleaning") "pause" else "start"
                                "return_home" -> "return_to_base"
                                else -> cmd
                            }
                            ctx.callService("vacuum", svc, data(), null)
                        }
                        if (cmd == "start_pause") startPause = seg
                    }
                    rootCol.addView(bar, lp())
                    featureBinders += { s ->
                        val cleaning = s?.state == "cleaning"
                        startPause?.let { seg ->
                            seg.icon.setIcon(if (cleaning) "mdi:pause" else "mdi:play")
                            seg.button.contentDescription = if (cleaning) "Pause" else "Start"
                            seg.paint(cleaning, ctx.theme.activeColor("vacuum"), ctx.theme.primaryTextColor)
                        }
                    }
                }
            }
            // Tag what this feature added, so `features_position: inline` can pick the first configured one.
            for (c in before until rootCol.childCount) rootCol.getChildAt(c).tag = FEATURE_TAG + type
        }
    }

    /** HA's default favorites when the light has none: color temperatures for white-only lights, else six hues. */
    private fun defaultFavorites(s: EntityState?): List<JSONObject> {
        val modes = (s?.attributes?.get("supported_color_modes") as? List<*>)?.map { it.toString() }.orEmpty()
        val colorModes = setOf("hs", "rgb", "rgbw", "rgbww", "xy")
        val hasColor = modes.isEmpty() || modes.any { it in colorModes }
        if (!hasColor && "color_temp" in modes) {
            val lo = (s?.attributes?.get("min_color_temp_kelvin") as? Number)?.toInt() ?: HaColorTempBarView.DEFAULT_MIN_K
            val hi = (s?.attributes?.get("max_color_temp_kelvin") as? Number)?.toInt() ?: HaColorTempBarView.DEFAULT_MAX_K
            return (0 until 6).map { i -> JSONObject().put("color_temp_kelvin", lo + (hi - lo) * i / 5) }
        }
        return DEFAULT_RGB_FAVORITES.map { (r, g, b) -> JSONObject().put("rgb_color", JSONArray(listOf(r, g, b))) }
    }

    private fun favoriteColor(fav: JSONObject): Int {
        fun ints(key: String) = fav.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.optDouble(it) } }
        ints("rgb_color")?.takeIf { it.size >= 3 }?.let { return dev.glassworks.engine.vitro.data.LightColor.pack(it[0].toInt(), it[1].toInt(), it[2].toInt()) }
        ints("rgbw_color")?.takeIf { it.size >= 3 }?.let { return dev.glassworks.engine.vitro.data.LightColor.pack(it[0].toInt(), it[1].toInt(), it[2].toInt()) }
        ints("rgbww_color")?.takeIf { it.size >= 3 }?.let { return dev.glassworks.engine.vitro.data.LightColor.pack(it[0].toInt(), it[1].toInt(), it[2].toInt()) }
        ints("hs_color")?.takeIf { it.size >= 2 }?.let {
            return dev.glassworks.engine.vitro.data.LightColor.hsvToArgb(it[0].toFloat(), (it[1] / 100.0).toFloat(), 1f)
        }
        if (fav.has("color_temp_kelvin")) return dev.glassworks.engine.vitro.data.LightColor.kelvinToArgb(fav.optInt("color_temp_kelvin"))
        return ctx.theme.stateInactiveColor
    }

    /** [preferred] when [MdiIcons] knows it, else [fallback] (an unknown name would draw the home glyph). */
    private fun iconOr(preferred: String, fallback: String): String {
        val key = preferred.removePrefix("mdi:")
        return if (key != "home" && MdiIcons.pathDataFor(preferred) == MdiIcons.pathDataFor("home")) fallback else preferred
    }

    private fun hvacModeColor(mode: String): Int = when (mode) {
        "heat" -> HVAC_HEAT
        "cool" -> HVAC_COOL
        "off" -> HVAC_OFF
        "dry" -> HVAC_DRY
        "fan_only" -> ctx.theme.activeColor("fan")
        else -> ctx.theme.primaryColor
    }

    private fun formatTemp(t: Double, unit: String): String =
        "${HaDisplay.formatFixed(t, if (t % 1.0 == 0.0) 0 else 1)} $unit"

    /**
     * `custom:mushroom-template-card`: a 40 dp shape avatar (icon tinted by `icon_color`, or a round `picture`)
     * with an optional 16 dp `badge_icon` on its bottom-right, then `primary` / `secondary`. Every field can be a
     * live Jinja2 template; `layout: vertical` stacks the avatar over centered text, and `multiline_secondary`
     * lets the secondary line wrap.
     */
    /**
     * Mushroom 5's template card, which draws HA's tile parts: an `ha-tile-icon` (a 36 px shape with 6 px around
     * it, the 24 px icon or a round picture inside; the tinted shape only shows when the icon has its own
     * action) and `ha-tile-info` (14 px / 500 primary over a 12 px secondary, both in the primary text color).
     * `layout: vertical` stacks them centered. card-mod padding on `ha-card` adds to this layout's own.
     */
    private fun buildMushroomTemplate() {
        val raw = config.raw
        val vertical = raw.optString("layout") == "vertical" || raw.optBoolean("vertical", false)
        val multiline = raw.optBoolean("multiline_secondary", false)
        // Mushroom 5's `fixed-info-height` (HA 2026.8+, a card straight in a sections grid whose rows aren't "auto"):
        // a vertical card's primary sits on a 16 px line in a 32 px box, with 2 px gaps. Anywhere else (in a stack,
        // a grid card) the vertical info keeps ha-tile-info's own lines, 10 px under the icon.
        val fixedInfo = vertical && ctx.cardLayout == "grid" && haAtLeast(2026, 8) &&
            raw.optJSONObject("grid_options")?.opt("rows") != "auto"
        val shapeBox = ctx.px(48f)
        val shapeSize = ctx.px(36f)
        val primaryText = ctx.theme.primaryTextColor
        // Mushroom 5's default icon action: toggle for an entity of a toggling domain, else none.
        val iconAction = ActionConfig.parse(raw.optJSONObject("icon_tap_action"), mushroomIconDefault(config.entity))
        val interactiveIcon = iconAction.action != "none"

        val avatarBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = mushIconRadiusPx(ctx) ?: (shapeSize / 2f)
            setColor(Color.TRANSPARENT)
        }
        val icon = MdiIconView(ctx.context)
        // The shape crops the picture to a circle (`border-radius: 9999px; overflow: hidden`): drawn so, whatever
        // renders it (an outline clip is the GPU's only).
        val picture = ImageView(ctx.context).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            visibility = GONE
        }
        val avatar = FrameLayout(ctx.context).apply {
            background = avatarBg
            addView(icon, LayoutParams(ctx.px(24f), ctx.px(24f), Gravity.CENTER))
            addView(picture, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        val badgeIcon = MdiIconView(ctx.context)
        val badgeOvalBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ctx.theme.primaryColor)
        }
        val badge = FrameLayout(ctx.context).apply {
            background = badgeOvalBg
            visibility = GONE
            addView(badgeIcon, LayoutParams(ctx.px(12f), ctx.px(12f), Gravity.CENTER))
        }
        val avatarFrame = FrameLayout(ctx.context).apply {
            clipChildren = false
            clipToPadding = false
            addView(avatar, LayoutParams(shapeSize, shapeSize, Gravity.CENTER))
            // ha-tile-badge: top right of the shape, hanging over its edge.
            addView(badge, LayoutParams(ctx.px(16f), ctx.px(16f), Gravity.TOP or Gravity.END).apply {
                topMargin = ctx.px(3f)
                rightMargin = ctx.px(3f)
            })
        }
        val dp = ctx.dp
        fun infoStyle(size: Float, weight: Int, lh: Float, ls: Float) = MdStyle().apply {
            fontSize = size; fontWeight = weight; lineHeight = lh; letterSpacing = ls; color = primaryText
        }
        // `fixed-info-height`: the primary sits on a 16 px line, centered in a 32 px box.
        val primary = MarkdownRenderer.cssLine(ctx.context, dp, "", infoStyle(14f, 500, if (fixedInfo) 16f else 22.4f, 0.1f)).apply {
            ellipsize = TextUtils.TruncateAt.END
        }
        val secondary = MarkdownRenderer.cssLine(ctx.context, dp, "", infoStyle(12f, 400, 14.4f, 0.4f)).apply {
            maxLines = if (multiline) Int.MAX_VALUE else 1
            ellipsize = if (multiline) null else TextUtils.TruncateAt.END
        }
        if (vertical) {
            primary.gravity = Gravity.CENTER_HORIZONTAL
            secondary.gravity = Gravity.CENTER_HORIZONTAL
            primary.textAlignment = TEXT_ALIGNMENT_CENTER
            secondary.textAlignment = TEXT_ALIGNMENT_CENTER
        }
        val labels = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (vertical) Gravity.CENTER_HORIZONTAL else Gravity.START
            if (fixedInfo) {
                addView(FrameLayout(ctx.context).apply {
                    addView(primary, LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER_VERTICAL))
                }, LinearLayout.LayoutParams(MATCH_PARENT, ctx.px(32f)).apply { bottomMargin = ctx.px(2f) })
            } else {
                addView(primary, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            }
            addView(secondary, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        val gap = ctx.px(4f)
        val row = LinearLayout(ctx.context).apply {
            orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = if (vertical) Gravity.CENTER_HORIZONTAL or Gravity.TOP else Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            // HA's tile content: vertical is a centered column with 10 / 8 px padding and 2 px gaps.
            if (vertical) setPadding(ctx.px(8f), ctx.px(10f), ctx.px(8f), ctx.px(10f)) else setPadding(gap, 0, ctx.px(10f), 0)
            minimumHeight = ctx.px(56f)
            addView(avatarFrame, LinearLayout.LayoutParams(shapeBox, shapeBox).apply {
                when {
                    fixedInfo -> bottomMargin = ctx.px(2f)
                    // ha-tile-icon's 6 px padding is taken back by a -6 px margin: the column lays out the 36 px
                    // shape, then the content's 10 px gap.
                    vertical -> { topMargin = -ctx.px(6f); bottomMargin = ctx.px(10f - 6f) }
                    else -> marginEnd = gap
                }
            })
            addView(labels, LinearLayout.LayoutParams(if (vertical) MATCH_PARENT else 0, WRAP_CONTENT, if (vertical) 0f else 1f))
            // HA's vertical tile draws its info 6 px into the icon's 48 px box (measured in HA's DOM: the info
            // starts 44 px under the icon box's top while the column is centered as if it didn't).
            if (fixedInfo) labels.translationY = -6f * ctx.dp
        }
        // Mushroom 5's horizontal content (`.content`, min-height 56 px) starts at the card's padding: card-mod padding
        // moves it, it doesn't shrink it (HA's DOM: `padding: 8px` puts it 8 px lower and lets it run 8 px past the
        // bottom). A 56 px card with that padding has 40 px inside, so the height is set, not left to the content.
        val fixedHeight = !vertical && !multiline
        // The container runs into the card's bottom padding: drawn there, as HA's overflow shows it.
        if (fixedHeight) clipToPadding = false
        addView(row, LayoutParams(MATCH_PARENT, if (fixedHeight) ctx.px(56f) else WRAP_CONTENT, if (fixedHeight) Gravity.TOP else Gravity.CENTER_VERTICAL))
        mushroom = MushroomTemplateViews(row, avatar, icon, picture, badge, badgeIcon, primary, secondary)
        if (interactiveIcon) {
            avatar.isClickable = true
            avatar.setOnClickListener { ctx.performAction(iconAction, config.entity) }
            // Named for TalkBack like the tile's icon: "Toggle AC" for Mushroom's default toggle, else the card's name.
            VitroAccess.role(avatar, A11yRole.BUTTON, name = {
                val n = primary.text?.toString()?.takeIf { it.isNotBlank() }
                    ?: config.entity?.let { ctx.store[it]?.displayName ?: it }
                when {
                    iconAction.action == "toggle" && n != null -> "Toggle $n"
                    n != null -> n
                    // No name to give it: what it does ("Navigate", "Perform action").
                    else -> iconAction.action.replace('-', ' ').replaceFirstChar { it.titlecase(Locale.ROOT) }
                }
            })
        }

        val entityId = config.entity
        val tapAction = ActionConfig.parse(raw.optJSONObject("tap_action"), if (entityId != null) "more-info" else "none")
        isClickable = true
        setOnClickListener { ctx.performAction(tapAction, entityId) }
        bindHoldAction(holdActionFor(raw, entityId), entityId)
        applyHaRipple(this, ctx.theme.cardBorderRadiusDp * ctx.dp, ctx.theme.dark)

        var iconName = ""
        var iconColor: Int? = null
        var pictureUrl = ""
        /** The picture of [pictureUrl] has loaded: until then (or when it fails) the icon shows. */
        var pictureLoaded = false
        var badgeIconName = ""
        var badgeColorValue: Int? = null
        fun refreshAvatar() {
            val showPicture = pictureUrl.isNotBlank() && pictureLoaded
            picture.visibility = if (showPicture) VISIBLE else GONE
            icon.visibility = if (showPicture) GONE else VISIBLE
            avatar.background = if (showPicture) null else avatarBg
            val tint = iconColor ?: ctx.theme.stateInactiveColor
            // HA's tile icon only draws its tinted shape when the icon is interactive.
            avatarBg.setColor(if (interactiveIcon) withAlpha(tint, 0.2f) else Color.TRANSPARENT)
            icon.setIcon(iconName.trim().ifBlank { "mdi:home" }, tint)
            mushroom?.iconColor = tint
        }
        fun refreshBadge() {
            badge.visibility = if (badgeIconName.isBlank()) GONE else VISIBLE
            val bg = badgeColorValue ?: ctx.theme.primaryColor
            badgeOvalBg.setColor(bg)
            badgeIcon.setIcon(badgeIconName.trim().ifBlank { "mdi:home" }, Color.WHITE)
            mushroom?.badgeColor = bg
        }

        fun bindTpl(key: String, fallback: String, apply: (String) -> Unit) {
            val tpl = raw.optString(key, fallback)
            if (isTemplate(tpl)) {
                renewing { ctx.subscribeTemplate(withEntityVariable(tpl, entityId)) { rendered -> ctx.main.post { apply(rendered.trim()) } } }
            } else {
                apply(tpl)
            }
        }
        fun TextView.setLine(v: String) {
            val old = text as? android.text.Spanned
            val span = old?.getSpans(0, old.length, CssLineBoxSpan::class.java)?.firstOrNull()
            val sb = android.text.SpannableStringBuilder(v)
            if (span != null) sb.setSpan(span, 0, sb.length, android.text.Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            text = sb
            visibility = if (v.isEmpty()) GONE else VISIBLE
        }
        bindTpl("primary", config.name(null).orEmpty()) { v -> primary.setLine(v) }
        bindTpl("secondary", "") { v -> secondary.setLine(v) }
        bindTpl("icon", config.icon ?: "") { v -> iconName = v; refreshAvatar() }
        bindTpl("icon_color", "") { v -> iconColor = MushroomColors.parse(v, ctx.theme); refreshAvatar() }
        bindTpl("picture", "") { v ->
            if (v == pictureUrl) return@bindTpl
            // A new picture: the old one goes at once, and the icon shows until this one loads (or if it fails).
            pictureUrl = v
            pictureLoaded = false
            picture.setImageDrawable(null)
            refreshAvatar()
            if (v.isNotBlank()) ctx.loadImage(v, this) { bmp ->
                if (pictureUrl != v) return@loadImage
                if (bmp != null) {
                    picture.setImageDrawable(CircleCropDrawable(bmp))
                    pictureLoaded = true
                    refreshAvatar()
                } else {
                    // Failed: the icon stays, and the next delivery of the same picture asks again.
                    pictureUrl = ""
                }
            }
        }
        bindTpl("badge_icon", "") { v -> badgeIconName = v; refreshBadge() }
        bindTpl("badge_color", "") { v -> badgeColorValue = MushroomColors.parse(v, ctx.theme); refreshBadge() }
        refreshAvatar()
        refreshBadge()
        entityId?.let { eid -> observe(eid) { /* keep the entity warm for tap actions */ } }
    }

    /** True when the HA this card shows is [year].[month] or later (or doesn't say: the current one). */
    private fun haAtLeast(year: Int, month: Int): Boolean {
        val parts = ctx.haConfig().optString("version", "").split('.')
        val y = parts.getOrNull(0)?.toIntOrNull() ?: return true
        val m = parts.getOrNull(1)?.toIntOrNull() ?: return true
        return y > year || (y == year && m >= month)
    }

    private fun renderState(s: EntityState?) {
        // HA's tile names an entity on a device "<device> <entity>" (the device alone for its main entity).
        titleView.setTextIfChanged(config.name(s) ?: config.entity?.let(HaDisplay::deviceEntityName) ?: s?.friendlyName ?: config.entity.orEmpty())
        val hideState = config.raw.optBoolean("hide_state", false)
        stateView.visibility = if (hideState) GONE else VISIBLE
        when {
            // HA's <state-display>: the `state_content` parts (or the domain's own), joined by " · ". A relative time
            // ("In 3 hours") or a running timer is drawn again each second, while on screen.
            isTile && s != null -> ctx.showRelativeOr(
                stateView, s,
                HaStateDisplay.of(s, HaStateDisplay.contentOf(config.raw.opt("state_content")), ctx.now(), ctx.haConfig()).text,
                tileStateTicking,
            )
            // Mushroom's cards write HA's formatEntityState ("On") with their own percentages, and a timestamp
            // sensor's, a scene's or a button's state relative.
            config.type.startsWith("custom:mushroom-") ->
                ctx.showRelativeOr(stateView, s, s?.let { mushroomStateLine(config.type, it) } ?: "…", TimestampText.MUSHROOM)
            else -> stateView.setTextIfChanged(s?.formattedState() ?: "…")
        }
        if (isTile) renderPicture(s)
        updateBadge(s, active = s?.isActive == true)
        // The icon's own action, named for TalkBack: "Toggle Desk lamp" for HA's default toggle, else the entity.
        if (iconInteractive) iconWrap.contentDescription = if (iconToggles) "Toggle ${titleView.text}" else titleView.text
    }

    /** This view draws HA's `tile` card (not a Mushroom card drawn with the tile's parts). */
    private val isTile: Boolean get() = config.type == "tile"

    /**
     * HA's `--tile-color` (`hui-tile-card`'s `_computeStateColor`, then its CSS defaults): the card's `color` while the
     * entity is active (`--state-inactive-color` otherwise); none for a person or device tracker (their badge has it);
     * a light's own color, lifted for contrast; else the state color, and `--state-icon-color` or
     * `--state-inactive-color` for a domain without one.
     */
    internal fun tileColor(s: EntityState?): Int {
        if (s == null) return ctx.theme.stateInactiveColor
        val active = HaStateActive.of(s)
        val fallback = if (active) ctx.theme.stateIconColor else ctx.theme.stateInactiveColor
        config.raw.optString("color", "").takeIf { it.isNotEmpty() }?.let { c ->
            return if (active) HaStateColor.cssColor(ctx, c) ?: fallback else fallback
        }
        if (s.domain == "person" || s.domain == "device_tracker") return fallback
        if (s.domain == "light") {
            val rgb = (s.attributes["rgb_color"] as? List<*>)?.mapNotNull { (it as? Number)?.toDouble() }
            if (rgb != null && rgb.size >= 3) return contrastLightColor(rgb[0], rgb[1], rgb[2])
        }
        return HaStateColor.of(ctx, s) ?: fallback
    }

    /**
     * HA's `renderTileBadge`: an orange `!` while unavailable; a person's or device tracker's zone (its icon, `mdi:home`
     * or `mdi:home-export-outline`) on its state color; a climate's or humidifier's current action on the color of
     * the mode it runs in. Nothing for an unknown state or any other domain.
     */
    private fun renderTileBadge(s: EntityState?) {
        fun show(icon: String?, color: Int?) {
            val badge = badge()
            badge.visibility = VISIBLE
            tileBadgeFill.setColor(color ?: ctx.theme.primaryColor)
            val glyph = tileBadgeIcon!!
            if (icon != null) { glyph.visibility = VISIBLE; glyph.setIcon(icon, Color.WHITE) }
            else glyph.visibility = INVISIBLE
        }
        unavailableBadge?.contentDescription = null
        when {
            s == null || s.state == "unknown" -> unavailableBadge?.visibility = GONE
            s.state == "unavailable" -> show("mdi:exclamation-thick", HaStateColor.property(ctx, "orange-color") ?: 0xFFFF9800.toInt())
            !isTile -> unavailableBadge?.visibility = GONE
            s.domain == "person" || s.domain == "device_tracker" -> {
                followZones(s)
                val zone = if (s.state == "home" || s.state == "not_home") null else zoneOf(s)
                val icon = (zone?.attributes?.get("icon") as? String)?.takeIf { it.isNotEmpty() }
                    ?: if (s.state == "not_home") "mdi:home-export-outline" else "mdi:home"
                show(icon, HaStateColor.of(ctx, s))
            }
            s.domain == "climate" || s.domain == "humidifier" -> {
                val attribute = if (s.domain == "climate") "hvac_action" else "action"
                val action = s.attributes[attribute] as? String
                if (action.isNullOrEmpty() || action == "off") { unavailableBadge?.visibility = GONE; return }
                val mode = (if (s.domain == "climate") CLIMATE_ACTION_MODE else HUMIDIFIER_ACTION_MODE)[action]
                // No mode for the action: HA's `stateColorCss(stateObj, undefined)` is the entity's own state color.
                show(HaDisplay.attributeIcon(s, attribute, action), HaStateColor.of(ctx, s, mode ?: s.state))
                // role="img", named by the action ("Heating").
                badge().contentDescription = HaDisplay.attributeLabel(s, attribute, action) ?: action
            }
            else -> unavailableBadge?.visibility = GONE
        }
    }

    /**
     * A person's or device tracker's badge reads its zone's state (`getZone`): the tile follows the zones it may be
     * in, its `in_zones` and the registry's zones, each asked of HA on its own ([VitroCardContext.watchEntity]), never
     * every entity. A zone's change draws the badge again.
     */
    private fun followZones(s: EntityState) {
        val ids = ((s.attributes["in_zones"] as? List<*>)?.mapNotNull { it as? String }.orEmpty()) +
            HaDisplay.registry.keys.filter { it.startsWith("zone.") }
        for (id in ids) {
            if (!followedZones.add(id)) continue
            ctx.watchEntity(id)
            observe(id) { _ -> config.entity?.let { e -> ctx.store[e] }?.let { renderTileBadge(it) } }
        }
    }

    /** HA's person badge `getZone`: the zone whose tracker state (its name; "home" for zone.home) is the person's. */
    private fun zoneOf(s: EntityState): EntityState? {
        val ids = ((s.attributes["in_zones"] as? List<*>)?.mapNotNull { it as? String }.orEmpty()) +
            ctx.store.all().keys.filter { it.startsWith("zone.") }.sorted()
        return ids.asSequence().mapNotNull { ctx.store[it] }.firstOrNull { z ->
            s.state == (if (z.entityId == "zone.home") "home" else z.friendlyName)
        }
    }

    /**
     * `show_entity_picture`: HA's `getTileImageUrl` (`entity_picture_local`, else `entity_picture`; a camera's at 80 px)
     * in a 36 px box with HA's picture corners (`--ha-border-radius-lg`; square for an update, 4 px for a media
     * player), with no tinted shape. The icon stays until the picture has loaded, or when there is none.
     */
    private fun renderPicture(s: EntityState?) {
        val raw = if (config.raw.optBoolean("show_entity_picture", false) && s != null) {
            ((s.attributes["entity_picture_local"] as? String)?.takeIf { it.isNotEmpty() } ?: s.entityPicture)
        } else null
        val url = raw?.let { if (s?.domain == "camera") cameraUrl(it, 80) else it }
        if (url == pictureUrl) return
        pictureUrl = url
        pictureView?.setImageDrawable(null)
        showPicture(false)
        if (url == null) return
        ctx.loadImage(url, this) { bmp ->
            if (pictureUrl != url) return@loadImage
            // A failed load (HA's image server down) isn't remembered: the next draw with this picture asks again.
            if (bmp == null) { pictureUrl = null; return@loadImage }
            val radius = when (s?.domain) {
                "update" -> 0f
                "media_player" -> 4f
                else -> 12f
            } * ctx.dp
            picture().setImageDrawable(RoundedCoverDrawable(bmp, radius))
            showPicture(true)
        }
    }

    /**
     * The picture over the icon: the glyph and its tinted shape go (HA's `.container.background` only without an
     * image), but the icon's box stays to take its own actions; the picture takes no touches of its own.
     */
    private fun showPicture(on: Boolean) {
        pictureShown = on
        if (on) picture().visibility = VISIBLE else pictureView?.visibility = GONE
        iconBadge.visibility = if (on) INVISIBLE else VISIBLE
        if (on) {
            badgeAnimator?.cancel()
            badgeAnimator = null
            badgeColor = null
            iconWrap.background = null
        }
    }

    private var pictureShown = false

    /** Not on screen yet since it was built: the first attach finds the state `init` just drew. */
    private var attachedBefore = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val first = !attachedBefore
        attachedBefore = true
        // The first time, only a line that counts on its own ("5 minutes ago") is drawn again, at the time it shows.
        if (first && !TimestampText.isTicking(stateView)) return
        // Back on screen: the state line catches up and ticks again.
        if (mushroom == null) config.entity?.let { id -> ctx.store[id]?.let { guarded("tick") { renderState(it) } } }
    }

    /**
     * HA's `hui-tile-card`: toggleable and stateful domains get a 36 dp badge tinted at 20% (amber when active,
     * grey when inactive); plain `sensor`, `weather`, `text` and `select` tiles draw a bare 24 dp icon in the
     * steel-blue `--state-icon-color`, with no circle.
     */
    private fun updateBadge(s: EntityState?, active: Boolean) {
        val domain = s?.domain ?: config.entity?.substringBefore('.').orEmpty()
        val iconName = MdiIcons.resolveIconName(config.icon, s)
        if (isTile && s != null) {
            // ha-tile-icon: a 24 px glyph in the 36 px shape, in `--tile-color`; the shape only when the icon acts.
            val inset = ctx.px(6f)
            iconWrap.setPadding(inset, inset, inset, inset)
            val color = tileColor(s)
            iconBadge.setIcon(iconName, iconBadge.tintColor)
            if (!iconInteractive || s.state == "unavailable" || pictureShown) {
                badgeAnimator?.cancel()
                badgeAnimator = null
                badgeColor = null
                iconWrap.background = null
                iconBadge.tintColor = color
                return
            }
            tintBadge(color)
            return
        }
        if (config.type.startsWith("custom:mushroom-") && config.type != MUSHROOM_TEMPLATE) {
            // Mushroom's shape always shows, in its own colors (not HA's tile colors).
            val inset = ctx.px(6f)
            iconWrap.setPadding(inset, inset, inset, inset)
            iconBadge.setIcon(iconName, iconBadge.tintColor)
            tintBadge(mushroomIconColor(config.type, config.raw, s, ctx.theme) ?: mushroomDisabled)
            return
        }
        if (domain in BARE_ICON_DOMAINS) {
            val bare = ctx.px(6f)
            iconWrap.setPadding(bare, bare, bare, bare)
            badgeAnimator?.cancel()
            badgeAnimator = null
            badgeColor = null
            iconWrap.background = null
            iconBadge.setIcon(iconName, ctx.theme.stateIconColor)
            return
        }
        // ha-tile-icon: a 24 px glyph in the 36 px shape.
        val inset = ctx.px(6f)
        iconWrap.setPadding(inset, inset, inset, inset)
        val color = when {
            domain == "lock" && s?.state == "locked" -> LOCKED_GREEN
            domain == "lock" && (s?.state == "unlocked" || s?.state == "open") -> UNLOCKED_RED
            // HA colors a tile only for its state-colored domains; the rest keep --state-icon-color.
            // --state-unavailable-color: the theme's disabled text color.
            s?.state == "unavailable" -> ctx.theme.disabledTextColor
            domain !in STATE_COLORED_DOMAINS -> ctx.theme.stateIconColor
            active -> s?.lightTintColor() ?: ctx.theme.activeColor(domain)
            config.type.startsWith("custom:mushroom-") -> mushroomDisabled
            else -> ctx.theme.stateInactiveColor
        }
        iconBadge.setIcon(iconName, iconBadge.tintColor)
        // No tinted shape while the icon has no action, or the entity is unavailable (HA disables the icon).
        if (!iconInteractive || s?.state == "unavailable") {
            badgeAnimator?.cancel()
            badgeAnimator = null
            badgeColor = null
            iconWrap.background = null
            iconBadge.tintColor = color
            return
        }
        tintBadge(color)
    }

    /**
     * Tints the badge icon and its 20% oval, cross-fading over 180 ms on screen (`hui-tile-card`'s
     * `transition: color 180ms, background-color 180ms`) and snapping before the card is attached.
     */
    private fun tintBadge(color: Int) {
        iconWrap.background = badgeOval
        val from = badgeColor
        if (from == color && badgeAnimator == null) return
        badgeAnimator?.cancel()
        badgeAnimator = null
        badgeColor = color
        if (from == null || !isAttachedToWindow) {
            iconBadge.tintColor = color
            badgeOval.setColor(withAlpha(color, 0.2f))
            return
        }
        val start = iconBadge.tintColor
        badgeAnimator = HaMotion.run(this, BADGE_FADE_MS) { t ->
            val c = HaMotion.lerpColor(start, color, t)
            iconBadge.tintColor = c
            badgeOval.setColor(withAlpha(c, 0.2f))
        }?.apply {
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (badgeAnimator === animation) badgeAnimator = null
                }
            })
        }
    }

    override fun onDetachedFromWindow() {
        badgeAnimator?.cancel()
        badgeAnimator = null
        badgeColor?.let { c ->
            iconBadge.tintColor = c
            badgeOval.setColor(withAlpha(c, 0.2f))
        }
        super.onDetachedFromWindow()
    }

    /** HA's select dropdown: a popup with every option; picking one calls `select.select_option`. */
    private fun showSelectMenu(anchor: View, entityId: String) {
        val opts = (ctx.store[entityId]?.attributes?.get("options") as? List<*>)?.mapNotNull { it?.toString() }.orEmpty()
        if (opts.isEmpty()) return
        val menu = PopupMenu(ctx.context, anchor)
        opts.forEachIndexed { i, opt -> menu.menu.add(0, i, i, opt) }
        menu.setOnMenuItemClickListener { item ->
            val opt = opts.getOrNull(item.itemId) ?: return@setOnMenuItemClickListener false
            // `input_select.select_option` for an input_select, `select.select_option` for a select, as HA's feature does.
            ctx.callService(entityId.substringBefore('.'), "select_option", JSONObject().put("entity_id", entityId).put("option", opt), null)
            true
        }
        menu.show()
    }

    /** The views of a Mushroom template tile, for live updates and tests. */
    internal class MushroomTemplateViews(
        val row: LinearLayout,
        val avatar: FrameLayout,
        val icon: MdiIconView,
        val picture: ImageView,
        val badge: FrameLayout,
        val badgeIcon: MdiIconView,
        val primary: TextView,
        val secondary: TextView,
    ) {
        var iconColor: Int = 0
        var badgeColor: Int = 0
    }

    companion object {
        const val MUSHROOM_TEMPLATE = "custom:mushroom-template-card"

        /** The domains whose entity's icon Mushroom's template card toggles by default (its `_e` and buttons). */
        private val MUSHROOM_TOGGLE_DOMAINS = setOf(
            "fan", "input_boolean", "light", "switch", "group", "automation", "humidifier", "valve", "button", "input_button", "scene",
        )

        /** Mushroom 5's template card: `icon_tap_action` defaults to `toggle` for [MUSHROOM_TOGGLE_DOMAINS], else none. */
        internal fun mushroomIconDefault(entity: String?): String =
            if (entity != null && entity.substringBefore('.') in MUSHROOM_TOGGLE_DOMAINS) "toggle" else "none"
        private val BARE_ICON_DOMAINS = setOf("sensor", "weather", "text", "select")
        /** HA's `STATE_COLORED_DOMAIN`: the domains whose tiles take a state color. */
        private val STATE_COLORED_DOMAINS = setOf(
            "alarm_control_panel", "alert", "automation", "binary_sensor", "calendar", "camera", "climate", "cover",
            "device_tracker", "fan", "group", "humidifier", "input_boolean", "lawn_mower", "light", "lock",
            "media_player", "person", "plant", "remote", "schedule", "script", "siren", "sun", "switch", "timer",
            "update", "vacuum", "valve", "water_heater", "weather",
        )
        /** The toggle knob's icon before HA's icon data arrives. */
        fun toggleIcon(domain: String, on: Boolean): String = when (domain) {
            "light" -> if (on) "mdi:lightbulb" else "mdi:lightbulb-off"
            "switch" -> if (on) "mdi:toggle-switch-variant" else "mdi:toggle-switch-variant-off"
            "fan" -> if (on) "mdi:fan" else "mdi:fan-off"
            "lock" -> if (on) "mdi:lock-open-variant" else "mdi:lock"
            else -> if (on) "mdi:power" else "mdi:power-off"
        }

        /** HA's `CLIMATE_HVAC_ACTION_TO_MODE`. */
        private val CLIMATE_ACTION_MODE = mapOf(
            "cooling" to "cool", "defrosting" to "heat", "drying" to "dry", "fan" to "fan_only", "heating" to "heat",
            "idle" to "off", "off" to "off", "preheating" to "heat",
        )

        /** HA's `HUMIDIFIER_ACTION_MODE`. */
        private val HUMIDIFIER_ACTION_MODE = mapOf("drying" to "on", "humidifying" to "on", "idle" to "off", "off" to "off")

        /**
         * `hui-tile-card`'s light color: the light's `rgb_color` with a pale one lifted for contrast (HSV saturation
         * under 0.1 drawn at value 225, under 0.4 raised to 0.4), as `rgb2hex(hsv2rgb(...))` rounds it.
         */
        internal fun contrastLightColor(r: Double, g: Double, b: Double): Int {
            val v = maxOf(r, g, b)
            val c = v - minOf(r, g, b)
            val h0 = if (c == 0.0) 0.0 else when (v) { r -> (g - b) / c; g -> 2 + (b - r) / c; else -> 4 + (r - g) / c }
            val h = 60 * (if (h0 < 0) h0 + 6 else h0)
            var sat = if (v == 0.0) 0.0 else c / v
            var value = v
            if (sat < 0.4) { if (sat < 0.1) value = 225.0 else sat = 0.4 }
            fun f(n: Int): Double {
                val k = (n + h / 60) % 6
                return value - value * sat * maxOf(minOf(k, 4 - k, 1.0), 0.0)
            }
            fun ch(x: Double) = Math.round(x.coerceIn(0.0, 255.0)).toInt()
            return dev.glassworks.engine.vitro.data.LightColor.pack(ch(f(5)), ch(f(3)), ch(f(1)))
        }

        /** HA's `cameraUrlWithWidthHeight`: the size appended to the proxy URL's query (it always has a token). */
        private fun cameraUrl(url: String, width: Int): String = "$url&width=$width&height=$width"

        /** `CoverEntityFeature` and `FanEntityFeature` bits the features read. */
        private const val COVER_OPEN = 1
        private const val COVER_CLOSE = 2
        private const val COVER_STOP = 8
        private const val FAN_SET_SPEED = 1

        /** View tag prefix naming the feature type a feature view was built for. */
        private const val FEATURE_TAG = "tile-feature:"

        /** HA's `DOMAINS_TOGGLE`: the domains whose tile icon toggles by default (not covers or locks). */
        private val ICON_TOGGLE_DOMAINS = ActionConfig.DOMAINS_TOGGLE

        /** Domains whose tile icon presses by default (`toggle` on them presses / activates). */
        private val PRESS_DOMAINS = setOf("button", "input_button", "scene")
        const val LOCKED_GREEN = 0xFF4CAF50.toInt()
        const val UNLOCKED_RED = 0xFFF44336.toInt()
        /** HA's default `--warning-color`. */
        const val WARNING_ORANGE = 0xFFFFA600.toInt()
        private const val SEGMENT_LIGHT = 0xFFF0F0F0.toInt()
        private const val SEGMENT_DARK = 0xFF2A2A2A.toInt()
        private const val HVAC_HEAT = 0xFFFF6F22.toInt()
        private const val HVAC_COOL = 0xFF2196F3.toInt()
        private const val HVAC_OFF = 0xFF757575.toInt()
        private const val HVAC_DRY = 0xFFEFBD07.toInt()
        private val DEFAULT_HVAC_MODES = listOf("off", "heat", "cool")
        private val TOGGLE_FEATURE_DOMAINS = setOf(
            "light", "switch", "fan", "input_boolean", "automation", "siren", "humidifier", "climate",
            "media_player", "remote", "water_heater", "script",
        )
        private const val MAX_FAVORITES = 8
        private val FAVORITE_KEYS = listOf("rgb_color", "rgbw_color", "rgbww_color", "hs_color", "xy_color", "color_temp_kelvin")

        /** HA's six default favorite hues, as `rgb_color`. */
        private val DEFAULT_RGB_FAVORITES = listOf(
            Triple(127, 172, 255), Triple(215, 150, 255), Triple(255, 158, 243),
            Triple(255, 110, 84), Triple(255, 202, 84), Triple(184, 255, 84),
        )

        /** HA's vacuum commands, in its order: icon and label. */
        private val VACUUM_COMMANDS = linkedMapOf(
            "start_pause" to ("mdi:play" to "Start"),
            "stop" to ("mdi:stop" to "Stop"),
            "clean_spot" to ("mdi:target-variant" to "Clean spot"),
            "locate" to ("mdi:map-marker" to "Locate"),
            "return_home" to ("mdi:home-map-marker" to "Return home"),
        )
        private val VACUUM_FALLBACK_ICONS = mapOf("clean_spot" to "mdi:robot-vacuum", "locate" to "mdi:bell", "return_home" to "mdi:home")
        private const val BADGE_FADE_MS = 180L

        /** The 10 dp rounded fill under HA 2026's tile features (dropdowns, cover buttons). */
        /** HA's feature controls' fill: `--disabled-color` (#464646 dark, #bdbdbd light) at 20%, 12 px corners. */
        fun featureFill(ctx: VitroCardContext) = GradientDrawable().apply {
            cornerRadius = 12f * ctx.dp
            val base = ThemeResolver.parseColor(ctx.theme.vars["disabled-color"]?.let { ThemeResolver.resolveValue(it, ctx.theme.vars, 0) })
                ?: if (ctx.theme.dark) 0xFF464646.toInt() else 0xFFBDBDBD.toInt()
            setColor(withAlpha(base, 0.2f))
        }
    }
}

/**
 * A canvas slider TalkBack and keys reach (API 23 and up). What it tells them and how keys move it is
 * [dev.glassworks.engine.vitro.ui.AdjustableAccess], as for More Info's sliders.
 */
internal object SliderAccess {
    fun install(view: View) {
        view.isFocusable = true
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }
}

/**
 * Rounded horizontal slider matching HA 2026's inline `hui-light-brightness-card-feature`:
 * a 42 dp rounded track tinted at 20%, solid filled progress from the left, and a crisp white vertical
 * drag bar inset from the right edge of the fill.
 */
internal class HaHorizontalSliderView(
    private val ctx: VitroCardContext,
    private var accentColor: Int,
    /**
     * HA's `ha-control-slider` geometry, as the tile features draw it: a 12 px track, the value bar a full-width
     * 8 px-rounded rectangle slid so its end sits at `v·W`, or with [showHandle] at `v·S + 2m + 4` (`m` = thickness / 8,
     * `S` = width - 2m - 4), and a 4 px white handle `m` inside that end at half the height. Off, Mushroom's and older
     * sliders keep the plain fill.
     */
    private val haGeometry: Boolean = false,
    private val onCommit: (Float) -> Unit,
) : View(ctx.context) {
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val clipPath = Path()
    private val bounds = RectF()
    private val handleRect = RectF()
    private var fraction = 0f
    private var active = false

    /**
     * Mushroom's `mushroom-slider`: a 12 px radius, the fill from the left with no indicator (Mushroom shows one only
     * with `showIndicator`, its color-temperature control).
     */
    var mushroom: Boolean = false
        set(v) { if (field != v) { field = v; invalidate() } }

    /**
     * A Mushroom gradient track (the color-temperature and color controls): drawn across the whole slider, with a
     * white indicator at the value instead of a fill. Null: the plain fill.
     */
    var trackGradient: IntArray? = null
        set(v) { field = v; gradientShader = null; invalidate() }
    private var gradientShader: android.graphics.Shader? = null
    private var gradientWidth = 0f

    /** `show-handle`: the bar keeps room for the handle at 0 (HA's brightness feature while the light is on). */
    var showHandle: Boolean = false
        set(v) { if (field != v) { field = v; invalidate() } }

    /** The track's color while off: `--state-inactive-color`, or the unavailable color. */
    var inactiveColor: Int = ctx.theme.stateInactiveColor
        set(v) { if (field != v) { field = v; invalidate() } }
    private var dragging = false

    /** Where the fill is heading: the last value set or dragged to, even while it is still animating there. */
    private var targetFraction = 0f
    private var targetColor = accentColor
    private var fractionAnimator: ValueAnimator? = null
    private var colorAnimator: ValueAnimator? = null

    /** The level (0..1) the slider shows, or is animating to. */
    val value: Float get() = targetFraction

    /** Re-tints the fill, e.g. when a light's RGB color or color temperature changes; cross-fades over 180 ms. */
    fun setAccentColor(color: Int) {
        if (color == targetColor) return
        targetColor = color
        colorAnimator?.cancel()
        val from = accentColor
        colorAnimator = HaMotion.run(this, COLOR_MS) { t ->
            accentColor = HaMotion.lerpColor(from, color, t)
            invalidate()
        }
    }

    /** The latest value bound from Home Assistant, kept during a drag so a cancelled one goes back to it. */
    private var boundFraction = 0f
    private var boundActive = false

    /** Moves the fill to [value]: animated over 160 ms on screen, snapped before attach. Held back mid-drag. */
    fun setValue(value: Float, active: Boolean) {
        boundFraction = value.coerceIn(0f, 1f)
        boundActive = active
        // A drag or a key edit keeps what it shows until it ends.
        if (dragging || access.editing) return
        val clamped = value.coerceIn(0f, 1f)
        if (clamped == targetFraction && active == this.active) return
        this.active = active
        if (clamped == targetFraction) {
            invalidate()
            return
        }
        targetFraction = clamped
        // A new value from HA: TalkBack reads it, as it would a SeekBar's.
        dev.glassworks.engine.vitro.ui.VitroAccess.valueChanged(this)
        fractionAnimator?.cancel()
        val from = fraction
        fractionAnimator = HaMotion.run(this, VALUE_MS) { t ->
            fraction = HaMotion.lerp(from, clamped, t)
            invalidate()
        }
    }

    private fun dragTo(x: Float, w: Float) {
        fractionAnimator?.cancel()
        fractionAnimator = null
        fraction = (x / w).coerceIn(0f, 1f)
        targetFraction = fraction
        active = fraction > 0f
        invalidate()
    }

    override fun onDetachedFromWindow() {
        fractionAnimator?.cancel()
        fractionAnimator = null
        colorAnimator?.cancel()
        colorAnimator = null
        fraction = targetFraction
        accentColor = targetColor
        super.onDetachedFromWindow()
    }

    init {
        SliderAccess.install(this)
    }

    /**
     * TalkBack and keys: a 0–100 % slider, as HA's `ha-control-slider` is. A key edit is sent when its key comes up, as
     * a drag is when it's let go (HA's `keyup`); TalkBack's steps are sent at once.
     */
    private val access = dev.glassworks.engine.vitro.ui.AdjustableAccess(
        min = { 0 }, max = { 100 }, step = { 1 },
        value = { Math.round(targetFraction * 100f) },
        preview = { v ->
            fractionAnimator?.cancel()
            fractionAnimator = null
            fraction = v / 100f
            targetFraction = fraction
            active = v > 0
            invalidate()
            dev.glassworks.engine.vitro.ui.VitroAccess.valueChanged(this)
        },
        commit = { v -> onCommit(v / 100f) },
        // A cancelled key or focus gone mid-edit: back to HA's value, nothing sent.
        restore = { setValue(boundFraction, boundActive) },
    )

    override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        access.onInitialize(info)
    }

    override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean =
        access.perform(action, arguments) ?: super.performAccessibilityAction(action, arguments)

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean =
        access.keyDown(keyCode) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean =
        access.keyUp(event) || super.onKeyUp(keyCode, event)

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (!gainFocus) access.cancel()
    }

    /**
     * Disabled mid-drag (the entity went unavailable): the drag ends as a cancel does, sending nothing, so the value
     * Home Assistant reports later is shown again. Android keeps a custom view's drag state when it's disabled.
     */
    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        if (!enabled && dragging) {
            dragging = false
            parent?.requestDisallowInterceptTouchEvent(false)
            setValue(boundFraction, boundActive)
            invalidate()
        }
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Disabled (an unavailable entity): no drag and nothing sent.
        if (!isEnabled) return false
        val w = width.toFloat().coerceAtLeast(1f)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                dragTo(event.x, w)
                return true
            }
            // Only a live drag moves or sends: one cancelled meanwhile (the entity went unavailable, then came back
            // while the finger stayed down) ignores the rest of its touch stream until a fresh press.
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                dragTo(event.x, w)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) return false
                dragTo(event.x, w)
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                onCommit(fraction)
                // TalkBack reads the value the drag left (HA's bind that follows matches it, so it says nothing).
                dev.glassworks.engine.vitro.ui.VitroAccess.valueChanged(this)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                // Nothing was sent: drop the preview and show the value Home Assistant reported.
                setValue(boundFraction, boundActive)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val r = (if (haGeometry || mushroom) 12f else 10f) * ctx.dp
        bounds.set(0f, 0f, w, h)
        clipPath.reset()
        clipPath.addRoundRect(bounds, r, r, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clipPath)
        // ha-control-slider: the track is the slider's color at 20%; off, that's the state's inactive color.
        trackPaint.color = CardSurface.withAlpha(if (active) accentColor else inactiveColor, 0.20f)
        canvas.drawRect(bounds, trackPaint)
        if (haGeometry) {
            if (active) {
                val m = h / 8f
                val hs = 4f * ctx.dp
                val v = fraction.coerceIn(0f, 1f)
                val end = if (showHandle) v * (w - 2f * m - hs) + 2f * m + hs else v * w
                fillPaint.color = accentColor
                val br = 8f * ctx.dp
                canvas.drawRoundRect(end - w, 0f, end, h, br, br, fillPaint)
                handleRect.set(end - m - hs, h / 4f, end - m, h * 3f / 4f)
                canvas.drawRoundRect(handleRect, hs, hs, handlePaint)
            }
            canvas.restore()
            return
        }
        val fillW = (w * fraction).coerceIn(0f, w)
        val gradient = trackGradient
        if (mushroom && gradient != null) {
            if (gradientShader == null || gradientWidth != w) {
                gradientShader = android.graphics.LinearGradient(0f, 0f, w, 0f, gradient, null, android.graphics.Shader.TileMode.CLAMP)
                gradientWidth = w
            }
            fillPaint.shader = gradientShader
            canvas.drawRect(bounds, fillPaint)
            fillPaint.shader = null
            if (active) {
                // .slider-track-indicator: a 4 px white bar at the value, half the height.
                val iw = 4f * ctx.dp
                val x = (w * fraction).coerceIn(iw, w - iw)
                handleRect.set(x - iw / 2f, h / 4f, x + iw / 2f, h * 3f / 4f)
                canvas.drawRoundRect(handleRect, iw / 2f, iw / 2f, handlePaint)
            }
        } else if (mushroom) {
            // .slider-track-active: the color itself (the inactive one while off) over the 20% track.
            fillPaint.color = if (active) accentColor else inactiveColor
            if (fillW > 0f) canvas.drawRect(0f, 0f, fillW, h, fillPaint)
        } else if (fillW > 0f) {
            fillPaint.color = accentColor
            canvas.drawRect(0f, 0f, fillW, h, fillPaint)
            val barW = 3.5f * ctx.dp
            val barH = (h * 0.48f).coerceAtLeast(10f * ctx.dp)
            val barRight = (fillW - 8f * ctx.dp).coerceAtLeast(barW + 2f * ctx.dp)
            handleRect.set(barRight - barW, (h - barH) / 2f, barRight, (h + barH) / 2f)
            canvas.drawRoundRect(handleRect, barW / 2f, barW / 2f, handlePaint)
        }
        canvas.restore()
    }

    private companion object {
        const val VALUE_MS = 160L
        const val COLOR_MS = 180L
    }
}

/**
 * HA 2026's inline `light-color-temp` tile feature: a 10 dp rounded warm-to-cool Kelvin gradient
 * (#FF9329 → #FFFDF7 → #A6C8FF) with a white vertical handle; releasing calls back with the picked Kelvin.
 */
internal class HaColorTempBarView(
    private val ctx: VitroCardContext,
    private val onCommit: (Int) -> Unit,
) : View(ctx.context) {
    private val gradientPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val handleShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x33000000
    }
    private val bounds = RectF()
    private val handleRect = RectF()
    private var shaderWidth = -1f
    private var minK = DEFAULT_MIN_K
    private var maxK = DEFAULT_MAX_K
    private var fraction = 0.5f
    private var hasValue = false
    private var dragging = false

    /** What HA last reported (kept during a drag, which ignores it): a cancelled drag goes back to it. */
    private var reported: (() -> Unit)? = null

    fun bind(minKelvin: Int, maxKelvin: Int, kelvin: Int?, on: Boolean) {
        val apply = {
            val before = if (hasValue) kelvinAt(fraction) else null
            minK = minKelvin
            maxK = maxKelvin.coerceAtLeast(minKelvin + 1)
            hasValue = on && kelvin != null
            if (kelvin != null) fraction = ((kelvin - minK).toFloat() / (maxK - minK)).coerceIn(0f, 1f)
            alpha = if (on) 1f else 0.5f
            invalidate()
            // A new value from HA: TalkBack reads it, as it would a SeekBar's.
            if (hasValue && kelvinAt(fraction) != before) dev.glassworks.engine.vitro.ui.VitroAccess.valueChanged(this)
        }
        reported = apply
        // A drag or a key edit keeps what it shows until it ends, but takes HA's new limits: a key edit keeps its
        // Kelvin (within them), a drag its place on the bar. The release sends a value inside the limits HA has now.
        if (dragging || access.editing) {
            val k = kelvinAt(fraction)
            minK = minKelvin
            maxK = maxKelvin.coerceAtLeast(minKelvin + 1)
            if (access.editing) fraction = ((k - minK).toFloat() / (maxK - minK)).coerceIn(0f, 1f)
            invalidate()
        } else {
            apply()
        }
    }

    private fun kelvinAt(frac: Float): Int = (minK + frac * (maxK - minK)).roundToInt().coerceIn(minK, maxK)

    init {
        SliderAccess.install(this)
    }

    /**
     * TalkBack and keys: the color temperature in Kelvin, an arrow a hundredth of the range (as More Info's). A key
     * edit is sent when its key comes up, as a drag is when it's let go; TalkBack's steps are sent at once.
     */
    private val access = dev.glassworks.engine.vitro.ui.AdjustableAccess(
        min = { minK }, max = { maxK }, step = { maxOf(1, (maxK - minK) / 100) },
        value = { kelvinAt(fraction) },
        preview = { k ->
            hasValue = true
            fraction = ((k - minK).toFloat() / (maxK - minK)).coerceIn(0f, 1f)
            invalidate()
            dev.glassworks.engine.vitro.ui.VitroAccess.valueChanged(this)
        },
        commit = { k -> onCommit(k) },
        // A cancelled key or focus gone mid-edit: back to what HA reported, nothing sent.
        restore = { reported?.invoke() ?: invalidate() },
        valueText = { k -> "$k K" },
    )

    override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        access.onInitialize(info)
    }

    override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean =
        access.perform(action, arguments) ?: super.performAccessibilityAction(action, arguments)

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean =
        access.keyDown(keyCode) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean =
        access.keyUp(event) || super.onKeyUp(keyCode, event)

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (!gainFocus) access.cancel()
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val w = width.toFloat().coerceAtLeast(1f)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                hasValue = true
                fraction = (event.x / w).coerceIn(0f, 1f)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                fraction = (event.x / w).coerceIn(0f, 1f)
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                onCommit(kelvinAt(fraction))
                // TalkBack reads the value the drag left (HA's bind that follows matches it, so it says nothing).
                dev.glassworks.engine.vitro.ui.VitroAccess.valueChanged(this)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                // Nothing was sent: back to the value (and handle) HA reported, as HaHorizontalSliderView does.
                reported?.invoke() ?: invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        if (w != shaderWidth) {
            shaderWidth = w
            gradientPaint.shader = android.graphics.LinearGradient(
                0f, 0f, w, 0f,
                intArrayOf(WARM, MID, COOL), floatArrayOf(0f, 0.5f, 1f),
                android.graphics.Shader.TileMode.CLAMP,
            )
        }
        val r = 10f * ctx.dp
        bounds.set(0f, 0f, w, h)
        canvas.drawRoundRect(bounds, r, r, gradientPaint)
        if (!hasValue) return
        val barW = 4f * ctx.dp
        val barH = (h * 0.56f).coerceAtLeast(12f * ctx.dp)
        // At most half the width: on a tile narrower than two insets the range would run backwards (and throw).
        val inset = minOf(8f * ctx.dp, w / 2f)
        val cx = (inset + fraction * (w - 2f * inset)).coerceIn(inset, w - inset)
        handleRect.set(cx - barW / 2f - 1f * ctx.dp, (h - barH) / 2f - 1f * ctx.dp, cx + barW / 2f + 1f * ctx.dp, (h + barH) / 2f + 1f * ctx.dp)
        canvas.drawRoundRect(handleRect, barW, barW, handleShadow)
        handleRect.set(cx - barW / 2f, (h - barH) / 2f, cx + barW / 2f, (h + barH) / 2f)
        canvas.drawRoundRect(handleRect, barW / 2f, barW / 2f, handlePaint)
    }

    companion object {
        const val DEFAULT_MIN_K = 2000
        const val DEFAULT_MAX_K = 6500
        private const val WARM = 0xFFFF9329.toInt()
        private const val MID = 0xFFFFFDF7.toInt()
        private const val COOL = 0xFFA6C8FF.toInt()
    }
}

/**
 * Crisp Canvas toggle matching HA 2026's outlined `ha-switch`: a 46x26 dp pill with a 1.5 dp outline and a
 * 16.4 dp round thumb. On: 18% primary fill, primary outline and thumb. Off: faint grey fill, 40% grey outline,
 * and a mid-grey thumb (#757575 light, #9e9e9e dark).
 */
internal class HaSwitchView(private val ctx: VitroCardContext) : View(ctx.context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ctx.dp * 1f
    }
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rect = RectF()
    private var on = false

    /** 0 = off, 1 = on; animated between them as `ha-switch` slides its thumb. */
    private var progress = 0f
    private var animator: ValueAnimator? = null

    val isOn: Boolean get() = on

    /** `ha-switch`: a switch for TalkBack, checked while on. */
    override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.Switch"
        info.isCheckable = true
        info.isChecked = on
    }

    /** Slides to [value] over 180 ms on screen; snaps on the first bind before the row is attached. */
    fun setOn(value: Boolean) {
        if (value == on) return
        on = value
        animator?.cancel()
        val from = progress
        val to = if (value) 1f else 0f
        animator = HaMotion.run(this, SLIDE_MS) { t ->
            progress = HaMotion.lerp(from, to, t)
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        progress = if (on) 1f else 0f
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // ha-switch: a 38 x 20 track with a 1 px border and a 14 px thumb 3 px in (measured).
        setMeasuredDimension(ctx.px(38f), ctx.px(20f))
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val theme = ctx.theme
        val p = progress
        val offTrack = if (theme.dark) OFF_TRACK_DARK else CardSurface.withAlpha(theme.secondaryTextColor, 0.08f)
        val offBorder = if (theme.dark) OFF_BORDER_DARK else CardSurface.withAlpha(theme.secondaryTextColor, 0.40f)
        fill.color = HaMotion.lerpColor(offTrack, CardSurface.withAlpha(theme.primaryColor, 0.18f), p)
        stroke.color = HaMotion.lerpColor(offBorder, theme.primaryColor, p)
        thumb.color = HaMotion.lerpColor(if (theme.dark) OFF_THUMB_DARK else OFF_THUMB_LIGHT, theme.primaryColor, p)
        val half = stroke.strokeWidth / 2f
        rect.set(half, half, w - half, h - half)
        val radius = rect.height() / 2f
        canvas.drawRoundRect(rect, radius, radius, fill)
        canvas.drawRoundRect(rect, radius, radius, stroke)
        val thumbR = ctx.dp * 7f
        val cx = HaMotion.lerp(h / 2f, w - h / 2f, p)
        canvas.drawCircle(cx, h / 2f, thumbR, thumb)
    }

    private companion object {
        private const val OFF_TRACK_DARK = 0xFF202020.toInt()
        private const val OFF_BORDER_DARK = 0xFF7A7A7A.toInt()
        const val OFF_THUMB_LIGHT = 0xFF757575.toInt()
        const val OFF_THUMB_DARK = 0xFF9E9E9E.toInt()
        const val SLIDE_MS = 180L
    }
}

/**
 * Native HA `entities` card with live rows and interactive switches, matching `hui-entities-card`: no dividers
 * between rows, but `divider` and `section` special rows, `secondary_info` under the name and inline
 * open / stop / close buttons on cover rows.
 */
class EntitiesCardView(ctx: VitroCardContext, config: CardConfig) : CardSurface(ctx) {
    init {
        // A section in system traces (Perfetto): what one entities card costs to build, rows and first renders included.
        android.os.Trace.beginSection("Vitro entities card: ${config.raw.optJSONArray("entities")?.length() ?: 0} rows")
        try {
            build(config)
        } finally {
            android.os.Trace.endSection()
        }
    }

    private fun build(config: CardConfig) {
        // hui-entities-card: a 20 / 16 / 24 header (24 px title) whose content is pulled up 8 px, then 40 px rows
        // 8 px apart in a `0 16px 16px` box (16 px all round without a header).
        val col = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, ctx.px(16f))
        }
        val title = config.title
        val rows = config.entities()
        // HA's header toggle (hui-entities-card's `_showHeaderToggle`, frontend 20261028.5): with a title and no
        // `show_header_toggle`, shown once two of the rows that name an entity (`"entity" in conf`, a button row's
        // included) are of a toggle domain (DOMAINS_TOGGLE). An entity filter builds its card from the rows that pass,
        // so only those count, and the switch comes and goes as they change. `hui-entities-toggle` then switches the
        // rows' toggle-domain entities that exist, and is on while any of them is "on".
        val toggleRows = ArrayList<Pair<String, () -> Boolean>>()
        val explicitToggle = if (config.raw.has("show_header_toggle") && !config.raw.isNull("show_header_toggle")) {
            config.raw.optBoolean("show_header_toggle", false)
        } else {
            null
        }
        val masterSwitch: HaSwitchView? =
            if (title != null && explicitToggle != false && rows.any { it.countsForHeaderToggle() }) HaSwitchView(ctx) else null
        fun shownToggleIds(): List<String> = toggleRows.filter { it.second() }.map { it.first }
        fun headerToggleIds(): List<String> = shownToggleIds().filter { ctx.store[it] != null }.distinct()
        if (title != null) {
            val header = LinearLayout(ctx.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ctx.px(16f), ctx.px(20f), ctx.px(16f), ctx.px(16f))
                // Rounded, not truncated: at 2x a truncated 64.8 px puts every row half a pixel high (entities-select).
                minimumHeight = ((20f + 28.8f + 16f) * ctx.dp).roundToInt()
            }
            header.addView(textView(ctx, 24f, ctx.theme.primaryTextColor, Inter.REGULAR).apply {
                text = title.orEmpty()
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            if (masterSwitch != null) {
                masterSwitch.isClickable = true
                masterSwitch.contentDescription = "Toggle entities"
                masterSwitch.setOnClickListener {
                    val ids = headerToggleIds()
                    if (ids.isEmpty()) return@setOnClickListener
                    val turnOn = !masterSwitch.isOn
                    masterSwitch.setOn(turnOn)
                    ctx.callService(
                        "homeassistant",
                        if (turnOn) "turn_on" else "turn_off",
                        JSONObject().put("entity_id", JSONArray(ids)),
                        null,
                    )
                }
                header.addView(masterSwitch, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = ctx.px(8f) })
            }
            col.addView(header)
        } else {
            col.setPadding(0, ctx.px(16f), 0, ctx.px(16f))
        }
        // While the rows are built, each one's first render would refresh the header switch and the filter again for
        // every row before it: they run once, when the card is complete.
        var building = true
        fun refreshMaster() {
            if (building) return
            val sw = masterSwitch ?: return
            val ids = headerToggleIds()
            val shown = (explicitToggle ?: (shownToggleIds().size >= 2)) && ids.isNotEmpty()
            sw.visibility = if (shown) VISIBLE else GONE
            sw.setOn(ids.any { ctx.store[it]?.state == "on" })
        }

        // `entity-filter` support: when `state_filter` is present, rows whose state isn't listed are hidden.
        val filterArr = config.raw.optJSONArray("state_filter")
        val stateFilter: Set<String>? = filterArr?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                when (val item = arr.opt(i)) {
                    is JSONObject -> item.optString("value", "").ifEmpty { null }
                    null -> null
                    else -> item.toString()
                }
            }.toSet()
        }
        val rowViews = ArrayList<LinearLayout>()
        // The rows (entity or special) that take the 8 px gap after another row.
        val gapRows = HashSet<View>()
        val rowVisible = ArrayList<Boolean>()
        // HA's `#states` is a flex column with an 8 px gap: between two rows that show, none around a hidden one.
        fun fixGaps() {
            var prevRow = false
            for (i in 0 until col.childCount) {
                val child = col.getChildAt(i)
                if (child.visibility == GONE) continue
                if (child in gapRows) {
                    val lp = child.layoutParams as LinearLayout.LayoutParams
                    val want = if (prevRow) ctx.px(8f) else 0
                    if (lp.topMargin != want) { lp.topMargin = want; child.layoutParams = lp }
                    prevRow = true
                } else {
                    prevRow = false
                }
            }
        }
        fun refreshFilter() {
            if (building) return
            rowViews.forEachIndexed { i, row -> row.visibility = if (rowVisible[i]) VISIBLE else GONE }
            fixGaps()
            refreshMaster()
        }

        fun entityRow(rowCfg: EntityRowConfig): View {
            val idx = rowViews.size
            val row = EntityRowLayout(ctx.context, ctx.px(8f)).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = ctx.px(40f)
                setPadding(ctx.px(16f), 0, ctx.px(16f), 0)
                isClickable = true
                applyHaRipple(this, 4f * ctx.dp, ctx.theme.dark)
            }
            rowViews += row
            rowVisible += (stateFilter == null)
            val iconView = MdiIconView(ctx.context)
            val nameView = textView(ctx, 14f, ctx.theme.primaryTextColor).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val isSwitchable = rowCfg.entity.substringBefore('.') in SWITCH_DOMAINS
            val isCover = rowCfg.entity.startsWith("cover.")
            // A slider, a dropdown or HA's plain buttons (EntityRowControls), for the domains HA draws with one.
            val control = EntityRowControls.build(ctx, rowCfg, { nameView.text.toString() }, { row.contentWidth })
            // The row's end: its control, a toggle, cover buttons or the state text. Only the one it shows is made.
            val switchView = if (control == null && isSwitchable) HaSwitchView(ctx) else null
            val valueView = if (control == null && !isSwitchable && !isCover) {
                textView(ctx, 14f, ctx.theme.primaryTextColor, Inter.REGULAR).apply {
                    maxLines = 1
                    gravity = Gravity.END
                }
            } else {
                null
            }
            // HA's `.secondary` line: 14 px, as HA's page draws it (the locale-* cases measure it).
            val secondaryView = if (control?.hideName != true && rowCfg.secondaryInfo !in setOf(null, "", "none")) {
                textView(ctx, 14f, ctx.theme.secondaryTextColor).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
            } else {
                null
            }
            // A secondary line that is a relative time: the same instance each render, so its ticker carries on.
            val secondaryRelative: ((EntityState, Long) -> String?)? =
                if (rowCfg.secondaryInfo in EntityRowControls.RELATIVE_SECONDARY_INFO) {
                    { st, now -> EntityRowControls.secondaryText(st, rowCfg.secondaryInfo, now) }
                } else {
                    null
                }
            control?.rowHeightDp?.let { row.minimumHeight = ctx.px(it) }

            // state-badge: a 24 px icon in a 40 px box, then the name 16 px after it (a dropdown takes the name's
            // place right after the box: HA's `hide-name`).
            row.addView(iconView, LinearLayout.LayoutParams(ctx.px(24f), ctx.px(24f)).apply {
                marginStart = ctx.px(8f); marginEnd = ctx.px(if (control?.hideName == true) 8f else 24f)
            })
            if (control?.hideName == true) {
                row.addView(control.view, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            } else if (secondaryView != null) {
                // HA's name and `.secondary` lines as its page lays them out (the locale-* and entities-secondary-info
                // cases measure them), placed at fractional positions so no density rounds a row off.
                val labels = TwoLineLabels(ctx, nameView, secondaryView)
                row.addView(labels, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = ctx.px(8f) })
            } else {
                row.addView(nameView, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = ctx.px(8f) })
                row.name = nameView
            }
            when {
                control != null -> if (!control.hideName) {
                    row.addView(control.view, (control.view.layoutParams as? LinearLayout.LayoutParams)
                        ?: LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
                }
                isSwitchable -> row.addView(switchView!!, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
                // ha-cover-controls: 48 px icon buttons (24 px icons) with a space between them (inline buttons), the
                // group 8 px into the row's end padding, so a cover row is 48 px tall where a plain one is 40.
                isCover -> COVER_BUTTONS.forEachIndexed { i, (iconName, service) ->
                    val btn = android.widget.FrameLayout(ctx.context).apply {
                        isClickable = true
                        contentDescription = service.substringBefore('_').replaceFirstChar { it.titlecase(Locale.ROOT) }
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(Color.TRANSPARENT)
                        }
                        applyHaRipple(this, 999f, ctx.theme.dark)
                        addView(MdiIconView(ctx.context).apply { setIcon(iconName, ctx.theme.primaryTextColor) },
                            android.widget.FrameLayout.LayoutParams(ctx.px(24f), ctx.px(24f), Gravity.CENTER))
                        setOnClickListener {
                            ctx.callService("cover", service, JSONObject().put("entity_id", rowCfg.entity), null)
                        }
                    }
                    row.addView(btn, LinearLayout.LayoutParams(ctx.px(48f), ctx.px(48f)).apply {
                        if (i > 0) marginStart = nameView.paint.measureText(" ").roundToInt()
                        if (i == COVER_BUTTONS.lastIndex) marginEnd = -ctx.px(8f)
                    })
                }
                else -> row.addView(valueView!!, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            }

            // HA's entity rows: the row opens more-info; only the switch itself toggles.
            val tap = rowCfg.tapAction ?: ActionConfig("more-info")
            row.setOnClickListener { ctx.performAction(tap, rowCfg.entity) }
            row.bindHoldAction(ctx, rowCfg.holdAction ?: ActionConfig("more-info"), rowCfg.entity)
            switchView?.apply {
                isClickable = true
                setOnClickListener { ctx.performAction(ActionConfig("toggle"), rowCfg.entity) }
            }

            fun renderRow(s: EntityState?) {
                val active = s?.isActive == true
                val tint = when {
                    control != null -> controlRowTint(ctx, s)
                    active -> s.lightTintColor() ?: ctx.theme.activeColor(s.domain)
                    else -> ctx.theme.stateIconColor
                }
                iconView.setIcon(MdiIcons.resolveIconName(rowCfg.icon, s), tint)
                // Only a text that changed is set: setText lays the row out again even for the same words.
                nameView.setTextIfChanged(rowCfg.nameFor(s) ?: s?.displayName ?: rowCfg.entity)
                // ha-entity-toggle's label: "Toggle Desk lamp".
                switchView?.contentDescription = "Toggle ${nameView.text}"
                // A dropdown row shows its name in the field (HA's `hide-name`): the row, which opens More Info,
                // is named by it for TalkBack.
                if (control?.hideName == true) row.contentDescription = nameView.text
                // A relative time (last-changed, …) keeps counting each second while shown, as HA's
                // `hui-timestamp-display` does; TimestampText sets only a text that changed.
                if (secondaryView != null) {
                    if (secondaryRelative != null) ctx.showRelativeOr(secondaryView, s, "", secondaryRelative)
                    else secondaryView.setTextIfChanged(EntityRowControls.secondaryText(s, rowCfg.secondaryInfo, ctx.now()).orEmpty())
                }
                when {
                    control != null -> control.update(s)
                    // The header switch follows its entities through its own subscriptions (below), not the rows'.
                    switchView != null -> switchView.setOn(active)
                    isCover -> Unit
                    // hui-sensor-entity-row and hui-simple-entity-row draw a timestamp with hui-timestamp-display.
                    valueView != null -> ctx.showTimestampOr(valueView, s, s?.formattedState() ?: "…")
                }
            }

            observe(rowCfg.entity) { s ->
                if (stateFilter != null) {
                    rowVisible[idx] = s != null && s.state in stateFilter
                    refreshFilter()
                }
                // What follows changes only the row's content: the row's own visibility (the filter's) is set above.
                row.updateContent { renderRow(s) }
            }
            gapRows += row
            return row
        }

        /** A special row ([SpecialRows]), or a conditional row around one (or around an entity row). */
        fun specialRow(rowCfg: EntityRowConfig): View? {
            if (rowCfg.rowType != "conditional") return SpecialRows.build(ctx, rowCfg) { id, cb -> observe(id, cb) }
            val inner = rowCfg.inner ?: return null
            val built = when {
                inner.isEntity -> entityRow(inner)
                inner.rowType == "conditional" -> specialRow(inner)
                // A divider or a section heading, as the row loop draws them.
                inner.rowType == "divider" || inner.rowType == "section" -> LinearLayout(ctx.context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(dividerLine(ctx), LinearLayout.LayoutParams(MATCH_PARENT, ctx.px(1f).coerceAtLeast(1)).apply {
                        marginStart = ctx.px(16f); marginEnd = ctx.px(16f)
                        topMargin = ctx.px(8f); if (inner.rowType == "divider") bottomMargin = ctx.px(8f)
                    })
                    if (inner.rowType == "section") inner.name?.let { label ->
                        addView(textView(ctx, 14f, ctx.theme.primaryTextColor, Inter.MEDIUM).apply {
                            text = label
                            maxLines = 1
                            ellipsize = TextUtils.TruncateAt.END
                            setPadding(ctx.px(16f), ctx.px(14f), ctx.px(16f), ctx.px(6f))
                        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                    }
                }
                else -> SpecialRows.build(ctx, inner) { id, cb -> observe(id, cb) }
            } ?: return null
            gapRows -= built
            // hui-conditional-row: HA's visibility conditions, checked again as their entities change. A hidden inner
            // conditional leaves its outer one's holder shown at zero height, taking the gap, as HA's wrapper div does
            // (the card hides `row.parentElement`, the outer row): parity case entities-nested-conditional.
            val holder = FrameLayout(ctx.context).apply { addView(built, LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }
            val widthDp = (ctx.context.resources.displayMetrics.widthPixels / ctx.dp).toInt()
            val update = {
                val userId = ctx.haUser().optString("id", "").ifEmpty { null }
                val shown = rowCfg.conditions.all { VisibilityCondition.evaluateAt(it, ctx.now(), ctx.store::get, widthDp, userId) }
                val want = if (shown) VISIBLE else GONE
                if (holder.visibility != want) {
                    holder.visibility = want
                    fixGaps()
                }
            }
            val refs = LinkedHashSet<String>()
            rowCfg.conditions.forEach { it.referencedEntities(refs) }
            refs.forEach { id -> observe(id) { update() } }
            if (rowCfg.conditions.any { it.dependsOnTime() || it.dependsOnUser() }) {
                renewing {
                    val tick = object : Runnable {
                        override fun run() { update(); ctx.main.postDelayed(this, 1_000L) }
                    }
                    ctx.main.post(tick)
                    ({ ctx.main.removeCallbacks(tick) })
                }
            }
            update()
            return holder
        }

        var prevWasDivider = false
        var prevWasRow = false
        for (rowCfg in rows) {
            if (rowCfg.rowType == "divider") {
                col.addView(dividerLine(ctx), LinearLayout.LayoutParams(MATCH_PARENT, ctx.px(1f).coerceAtLeast(1)).apply {
                    marginStart = ctx.px(16f); marginEnd = ctx.px(16f)
                    topMargin = ctx.px(8f); bottomMargin = ctx.px(8f)
                })
                prevWasDivider = true
                prevWasRow = false
                continue
            }
            if (rowCfg.rowType == "section") {
                if (!prevWasDivider) {
                    col.addView(dividerLine(ctx), LinearLayout.LayoutParams(MATCH_PARENT, ctx.px(1f).coerceAtLeast(1)).apply {
                        marginStart = ctx.px(16f); marginEnd = ctx.px(16f)
                        topMargin = ctx.px(8f)
                    })
                }
                rowCfg.name?.let { label ->
                    col.addView(textView(ctx, 14f, ctx.theme.primaryTextColor, Inter.MEDIUM).apply {
                        text = label
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        setPadding(ctx.px(16f), ctx.px(14f), ctx.px(16f), ctx.px(6f))
                    }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                }
                prevWasDivider = false
                prevWasRow = false
                continue
            }
            if (!rowCfg.isEntity) {
                val built = specialRow(rowCfg) ?: continue
                // A conditional divider or section brings its own margins, as the plain ones do.
                val structural = rowCfg.inner?.rowType in setOf("divider", "section")
                prevWasDivider = false
                val gapAbove = prevWasRow && !structural
                prevWasRow = !structural
                if (!structural) gapRows += built
                col.addView(built, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { if (gapAbove) topMargin = ctx.px(8f) })
                continue
            }
            prevWasDivider = false
            val gapAbove = prevWasRow
            prevWasRow = true
            col.addView(entityRow(rowCfg), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { if (gapAbove) topMargin = ctx.px(8f) })
            if (rowCfg.countsForHeaderToggle()) {
                val idx = rowViews.lastIndex
                toggleRows += rowCfg.entity to { rowVisible[idx] }
            }
        }
        // A button row names its entity too, and is never filtered out.
        rows.filter { it.rowType == "button" && it.countsForHeaderToggle() }.forEach { toggleRows += it.entity to { true } }
        // Every toggle entity keeps the header switch current, a button row's included (its own row doesn't).
        if (masterSwitch != null) toggleRows.map { it.first }.distinct().forEach { id -> observe(id) { refreshMaster() } }
        building = false
        refreshFilter()
        addView(col, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    private companion object {
        val SWITCH_DOMAINS = setOf("light", "switch", "input_boolean", "fan", "automation", "humidifier", "siren")

        /**
         * state-badge's icon color on a row with a control: `--state-unavailable-color` when unavailable, else HA's
         * `stateColorCss` for the state-colored domains among them (a lock's locked, unlocked and jammed colors, a
         * running script's active color), where an inactive state (`--state-inactive-color: initial` in the badge)
         * keeps `--state-icon-color`.
         */
        fun controlRowTint(ctx: VitroCardContext, s: EntityState?): Int {
            if (s == null) return ctx.theme.stateIconColor
            if (s.state == "unavailable") return HaStateColor.of(ctx, s) ?: ctx.theme.stateIconColor
            if (s.domain != "lock" && s.domain != "script") return ctx.theme.stateIconColor
            val key = s.state.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "_")
            val activeKey = if (s.isActive) "active" else "inactive"
            val props = buildList {
                s.deviceClass?.let { add("state-${s.domain}-$it-$key-color") }
                add("state-${s.domain}-$key-color")
                add("state-${s.domain}-$activeKey-color")
                if (s.isActive) add("state-active-color")
            }
            for (p in props) HaStateColor.property(ctx, p)?.let { return it }
            return ctx.theme.stateIconColor
        }
        /** The header toggle counts and switches HA's DOMAINS_TOGGLE ([ActionConfig.DOMAINS_TOGGLE]). */
        fun EntityRowConfig.countsForHeaderToggle(): Boolean =
            (isEntity || rowType == "button") && entity.substringBefore('.') in ActionConfig.DOMAINS_TOGGLE
        val COVER_BUTTONS = listOf("mdi:arrow-up" to "open_cover", "mdi:stop" to "stop_cover", "mdi:arrow-down" to "close_cover")

        fun dividerLine(ctx: VitroCardContext): View = View(ctx.context).apply { setBackgroundColor(ctx.theme.dividerColor) }
    }
}

/**
 * `hui-generic-entity-row`'s `.info` with a `.secondary` line, as HA's page lays it out: a 44.78 px box with the name's
 * 16 px line 3 px down and the secondary's 6.39 px under it (line boxes that grow with the theme's text size). Each
 * line is centered where HA's is, at fractional positions.
 */
private class TwoLineLabels(private val ctx: VitroCardContext, private val first: TextView, private val second: TextView) : ViewGroup(ctx.context) {
    init {
        addView(first)
        addView(second)
    }

    private val line: Float get() = 16f * ctx.theme.fontScale

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val child = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY)
        val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        first.measure(child, unspecified)
        second.measure(child, unspecified)
        setMeasuredDimension(w, ((3f + line + 6.39f + line + 3.39f) * ctx.dp).roundToInt())
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        fun place(v: View, centerCss: Float) {
            val top = (centerCss * ctx.dp - v.measuredHeight / 2f).roundToInt()
            v.layout(0, top, r - l, top + v.measuredHeight)
        }
        place(first, 3f + line / 2f)
        place(second, 3f + line + 6.39f + line / 2f)
    }
}

/**
 * An entities-card row as hui-generic-entity-row lays it out. Its `.info` (`flex: 1 1 30%`) shrinks down to its
 * padding when the controls need the room, and the controls run on past the row. Chrome still draws the name's first
 * letter (an ellipsis keeps one character), so it shows in the 8 px end padding, cut, where Vitro's ellipsis would
 * leave nothing: a squeezed [name] is drawn whole across [endPadPx], clipped.
 */
private class EntityRowLayout(context: android.content.Context, private val endPadPx: Int) : LinearLayout(context) {
    var name: TextView? = null

    /** The row's width inside its padding, as last measured: what HA's flex row shares between a name and a slider. */
    var contentWidth: Int = 0
        private set

    /** Null while LinearLayout's constructor runs (it asks for a layout before this class is set up). */
    private var local: LocalRelayout? = null

    init {
        local = LocalRelayout(this)
    }

    /**
     * Runs [update], a state change to what's inside the row (its icon, texts, control): a value whose width changes
     * lays out this row again, not the dashboard, as long as the row keeps its height ([LocalRelayout]).
     */
    fun updateContent(update: () -> Unit) {
        val l = local
        if (l == null) update() else l.run(update)
    }

    override fun requestLayout() {
        if (local?.take() != true) super.requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        local?.onMeasure(widthMeasureSpec, heightMeasureSpec)
        contentWidth = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        val n = name ?: return
        val squeezed = n.width == 0 && n.text.isNotEmpty()
        // Each change asks for one more layout; the next pass finds it already set.
        n.ellipsize = if (squeezed) null else TextUtils.TruncateAt.END
        n.setHorizontallyScrolling(squeezed)
        if (squeezed) {
            n.measure(MeasureSpec.makeMeasureSpec(endPadPx, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(n.height, MeasureSpec.EXACTLY))
            n.layout(n.left, n.top, n.left + endPadPx, n.bottom)
        }
    }
}

/** Native HA `glance` card, in `hui-glance-card`'s order: name on top, icon in the middle, state at the bottom. */
class GlanceCardView(ctx: VitroCardContext, config: CardConfig) : CardSurface(ctx) {
    /**
     * hui-glance-card as measured: the entities card's header (20 / 16 / 24, 24 px title), then items in a
     * `0 16px 4px` box (16 px on top without a header), each `0 4px` wide with 12 px under it: the name on a 22.4 px
     * line, the 40 px state badge 8 px above and below, the state (as HA formats it, no tile extras) on 22.4 px.
     */
    init {
        val root = LinearLayout(ctx.context).apply { orientation = LinearLayout.VERTICAL }
        val title = config.title
        if (title != null) {
            root.addView(textView(ctx, 24f, ctx.theme.primaryTextColor, Inter.REGULAR).apply {
                text = title
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ctx.px(16f), ctx.px(20f), ctx.px(16f), ctx.px(24f))
            }, LinearLayout.LayoutParams(MATCH_PARENT, ctx.px(20f + 28.8f + 24f)))
        }
        val grid = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.px(16f), if (title == null) ctx.px(16f) else 0, ctx.px(16f), ctx.px(4f))
        }
        root.addView(grid, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val showName = config.raw.optBoolean("show_name", true)
        val showState = config.raw.optBoolean("show_state", true)
        val showIcon = config.raw.optBoolean("show_icon", true)
        val entities = config.entities().filter { it.isEntity }
        val cols = config.raw.optInt("columns", minOf(entities.size.coerceAtLeast(1), 5)).coerceIn(1, 6)
        val line = (22.4f * ctx.dp).roundToInt()

        entities.chunked(cols).forEach { chunk ->
            val row = LinearLayout(ctx.context).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = cols.toFloat()
            }
            for (e in chunk) {
                val cell = LinearLayout(ctx.context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(ctx.px(4f), 0, ctx.px(4f), 0)
                    isClickable = true
                    applyHaRipple(this, 8f * ctx.dp, ctx.theme.dark)
                }
                val icon = MdiIconView(ctx.context)
                val name = textView(ctx, 14f, ctx.theme.primaryTextColor).apply {
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER
                }
                val state = textView(ctx, 14f, ctx.theme.primaryTextColor, Inter.REGULAR).apply {
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER
                }
                if (showName) cell.addView(name, LinearLayout.LayoutParams(MATCH_PARENT, line))
                if (showIcon) cell.addView(icon, LinearLayout.LayoutParams(ctx.px(24f), ctx.px(24f)).apply {
                    topMargin = ctx.px(16f); bottomMargin = ctx.px(16f)
                })
                if (showState) cell.addView(state, LinearLayout.LayoutParams(MATCH_PARENT, line))
                cell.setOnClickListener {
                    ctx.performAction(e.tapAction ?: ActionConfig("more-info"), e.entity)
                }
                cell.bindHoldAction(ctx, e.holdAction ?: ActionConfig("more-info"), e.entity)
                observe(e.entity) { s ->
                    // HA's <state-badge> with the glance card's `color: "state"`.
                    icon.setIcon(MdiIcons.resolveIconName(e.icon, s), stateColorBrightness(s, HaStateColor.icon(ctx, s, hvacOverRgb = true)))
                    name.text = e.nameFor(s) ?: s?.displayName ?: e.entity
                    // hui-glance-card: a timestamp state through hui-timestamp-display ("In 3 hours").
                    ctx.showTimestampOr(state, s, s?.plainState() ?: "…")
                }
                row.addView(cell, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { bottomMargin = ctx.px(12f) })
            }
            grid.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        addView(root, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }
}

/**
 * Native HA `button` card (`hui-button-card`), at its CSS: `padding: 4% 0`, the icon 40% of the card's width (at
 * most 80% of its content height when the card's height is fixed), in the state color or `--state-icon-color` when
 * off; the name (16 px on 19.2 px, 8 px under the icon) and, with `show_state`, the state (12.6 px, secondary).
 */
class ButtonCardView(ctx: VitroCardContext, config: CardConfig) : CardSurface(ctx) {
    private val icon = MdiIconView(ctx.context)
    // Content taller than the card (a 2-row button with its state) spills evenly above and below the center, as
    // HA's flex column does, instead of squeezing its last line.
    private val col = object : LinearLayout(ctx.context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.AT_MOST) MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED) else heightMeasureSpec
            super.onMeasure(widthMeasureSpec, h)
        }
    }.apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
    }
    private val iconLp = LinearLayout.LayoutParams(ctx.px(40f), ctx.px(40f))

    init {
        val showName = config.raw.optBoolean("show_name", true)
        val showState = config.raw.optBoolean("show_state", false)
        val showIcon = config.raw.optBoolean("show_icon", true)
        // The name and the state are spans in HA's column: a long one wraps onto more lines ("Kitchen / Pendant"),
        // each a 1.2 line box, and the card grows with them where its height isn't fixed (masonry).
        val label = textView(ctx, 16f, ctx.theme.primaryTextColor, Inter.REGULAR).apply { gravity = Gravity.CENTER }
        val state = textView(ctx, 12.6f, ctx.theme.secondaryTextColor, Inter.REGULAR).apply { gravity = Gravity.CENTER }
        if (showIcon) col.addView(icon, iconLp)
        if (showName) col.addView(label, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            if (showIcon) topMargin = ctx.px(8f)
        })
        if (showState) col.addView(state, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(col, LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER))
        fun TextView.line(text: String, size: Float) {
            this.text = text
            lineBox(ctx, this, size, 1.2f)
        }

        val eid = config.entity
        // hui-button-card: `tap_action` defaults to toggle for HA's DOMAINS_TOGGLE, else more-info (a lock, a cover,
        // a script or a siren opens More Info and never runs on a tap nobody configured).
        val tap = ActionConfig.parse(config.raw.optJSONObject("tap_action"), ActionConfig.defaultTap(eid))
        isClickable = true
        setOnClickListener { ctx.performAction(tap, eid) }
        bindHoldAction(holdActionFor(config.raw, eid), eid)
        applyHaRipple(this, ctx.theme.cardBorderRadiusDp * ctx.dp, ctx.theme.dark)
        if (eid != null) {
            observe(eid) { s ->
                // HA's `_computeColor` with no `color` (the compat rule claims none) and `state_color` on.
                icon.setIcon(MdiIcons.resolveIconName(config.icon, s), stateColorBrightness(s, HaStateColor.icon(ctx, s, hvacOverRgb = false)))
                label.line(config.name(s) ?: s?.displayName ?: eid, 16f)
                state.line(s?.plainState().orEmpty(), 12.6f)
            }
        } else {
            icon.setIcon(config.icon ?: "mdi:home", ctx.theme.stateIconColor)
            label.line(config.name(null).orEmpty(), 16f)
        }
    }

    override fun measureCard(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED && w > 0) {
            val border = (ctx.theme.cardBorderWidthDp * ctx.dp).roundToInt()
            val pad = (w * 0.04f).roundToInt()
            col.setPadding(0, pad, 0, pad)
            var size = (0.4f * (w - 2 * border)).roundToInt()
            if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
                val content = MeasureSpec.getSize(heightMeasureSpec) - 2 * border - 2 * pad
                size = minOf(size, (0.8f * content).roundToInt())
            }
            if (iconLp.width != size) { iconLp.width = size; iconLp.height = size }
        }
        super.measureCard(widthMeasureSpec, heightMeasureSpec)
    }
}

/**
 * HA's `stateColorBrightness` on the button card's icon: `filter: brightness((brightness + 245) / 5 %)` for an entity
 * with a `brightness` attribute (not a plant), which scales the icon's color channels.
 */
internal fun stateColorBrightness(s: EntityState?, color: Int): Int {
    val b = (s?.attributes?.get("brightness") as? Number)?.toDouble()?.takeIf { it != 0.0 } ?: return color
    if (s.domain == "plant") return color
    val f = ((b + 245) / 500).toFloat()
    fun ch(c: Int) = (c * f).roundToInt().coerceIn(0, 255)
    return Color.argb(Color.alpha(color), ch(Color.red(color)), ch(Color.green(color)), ch(Color.blue(color)))
}

/** Native HA `heading` card (`custom:mushroom-title-card` is [MushroomTitleCardView]). */
class HeadingCardView(private val ctx: VitroCardContext, config: CardConfig) : LinearLayout(ctx.context) {
    private val unsubs = ArrayList<() -> Unit>()

    /** Every subscription as a way to make it again: a view that is detached and attached again listens again. */
    private val binders = ArrayList<() -> (() -> Unit)>()

    /** Each badge drawn again from the store, which also starts its ticker (a timer, a relative time) once attached. */
    private val refreshers = ArrayList<() -> Unit>()

    private fun bind(binder: () -> (() -> Unit)) {
        binders += binder
        unsubs += binder()
    }

    init {
        orientation = VERTICAL
        val heading = config.raw.optString("heading", config.raw.optString("title", "")).ifEmpty { config.name(null).orEmpty() }
        val subtitle = config.raw.optString("subtitle", "")
        val isSubtitle = config.raw.optString("heading_style", "title") == "subtitle"
        // hui-heading-card: `0 4px` around a row with 8 px gaps and an 18 px icon; the title is 16 px / 400 on a
        // 25.6 px line, the subtitle 14 px / 500 on a 16.8 px one, 20 px badges beside them. The card is at least 24 px
        // tall with the row at its bottom (`min-height: 24px; justify-content: flex-end`), so a short row sits lower.
        val hasBadges = (config.raw.optJSONArray("badges")?.length() ?: 0) > 0
        val rowHeight = maxOf(if (isSubtitle) 16.8f else 25.6f, if (config.icon.isNullOrBlank()) 0f else 18f, if (hasBadges) 20f else 0f)
        setPadding(ctx.px(4f), ((24f - rowHeight).coerceAtLeast(0f) * ctx.dp).roundToInt(), ctx.px(4f), 0)
        val textColor = if (isSubtitle) ctx.theme.secondaryTextColor else ctx.theme.primaryTextColor

        // HA's `hui-heading-card`: [icon] heading ........ [entity badges]
        val row = LinearLayout(ctx.context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = (rowHeight * ctx.dp).roundToInt()
        }
        config.icon?.takeIf { it.isNotBlank() }?.let { iconName ->
            row.addView(MdiIconView(ctx.context).apply { setIcon(iconName, textColor) },
                LayoutParams(ctx.px(18f), ctx.px(18f)).apply { marginEnd = ctx.px(8f) })
        }
        val hView = textView(ctx, if (isSubtitle) 14f else 16f, textColor, if (isSubtitle) Inter.MEDIUM else Inter.REGULAR).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            letterSpacing = 0.1f / (if (isSubtitle) 14f else 16f)
        }
        if ("{{" in heading) {
            bind { ctx.subscribeTemplate(heading) { rendered -> ctx.main.post { hView.text = rendered } } }
        } else {
            hView.text = heading
        }
        row.addView(hView, LayoutParams(0, WRAP_CONTENT, 1f))
        // HA's heading tap_action: `.content` (the icon, the heading and an ha-icon-next chevron after it) is one
        // button, as wide as what it holds; the badges stay at the end.
        val headingAction = ActionConfig.parse(config.raw.optJSONObject("tap_action"), "none")
        if (headingAction.action != "none") {
            val content = LinearLayout(ctx.context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            while (row.childCount > 0) {
                val v = row.getChildAt(0)
                val lp = v.layoutParams as LayoutParams
                row.removeViewAt(0)
                if (v === hView) { lp.width = WRAP_CONTENT; lp.weight = 0f }
                content.addView(v, lp)
            }
            content.addView(MdiIconView(ctx.context).apply { setIcon("mdi:chevron-right", textColor) },
                LayoutParams(ctx.px(18f), ctx.px(18f)).apply { marginStart = ctx.px(8f) })
            content.isClickable = true
            content.isFocusable = true
            content.contentDescription = heading
            VitroAccess.role(content, A11yRole.BUTTON)
            content.setOnClickListener { ctx.performAction(headingAction, null) }
            val slot = FrameLayout(ctx.context).apply {
                addView(content, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL))
            }
            row.addView(slot, LayoutParams(0, WRAP_CONTENT, 1f))
        }
        val badgeConfigs = config.raw.optJSONArray("badges")
        for (i in 0 until (badgeConfigs?.length() ?: 0)) {
            // A bare entity id is an entity badge of that entity, with the defaults.
            val badge = badgeConfigs?.optJSONObject(i)
                ?: badgeConfigs?.opt(i)?.let { it as? String }?.let { JSONObject().put("type", "entity").put("entity", it) }
            badge?.takeIf { it.optString("entity", "").contains('.') }?.let { row.addView(buildBadge(it)) }
        }
        if (heading.isNotEmpty() || row.childCount > 1) addView(row, LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        if (subtitle.isNotEmpty()) {
            val subView = textView(ctx, 13f, ctx.theme.secondaryTextColor)
            if ("{{" in subtitle) {
                bind { ctx.subscribeTemplate(subtitle) { rendered -> ctx.main.post { subView.text = rendered } } }
            } else {
                subView.text = subtitle
            }
            addView(subView, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ctx.px(2f) })
        }
    }

    /**
     * One right-side heading badge, HA's `hui-entity-heading-badge` (frontend 20261028.5): the entity's 16 px icon
     * (`show_icon`), 3 px, then its `<state-display dash-unavailable>` (`show_state`, `state_content`), 14 px / 400 in
     * the secondary text color with 0.1 px letter spacing on a 20 px row, badges 8 px apart. The icon takes `color`
     * (`state`: the state color, a light's own; a theme or CSS color while active; none by default). It acts only on
     * its own `tap_action` and `hold_action` (none by default). An entity HA doesn't have is a red alert icon and "-".
     */
    private fun buildBadge(cfg: JSONObject): View {
        val eid = cfg.optString("entity")
        val explicitIcon = cfg.optString("icon", "").ifEmpty { null }
        val showIcon = cfg.optBoolean("show_icon", true)
        val showState = cfg.optBoolean("show_state", true)
        val colorSpec = cfg.optString("color", "").ifEmpty { null }
        val content = HaStateDisplay.contentOf(cfg.opt("state_content"))
        val configuredName = cfg.optString("name", "").ifEmpty { null }
        val tap = ActionConfig.parse(cfg.optJSONObject("tap_action"), "none")
        val hold = ActionConfig.parse(cfg.optJSONObject("hold_action"), "none")
        val icon = MdiIconView(ctx.context)
        val state = textView(ctx, 14f, ctx.theme.secondaryTextColor).apply { maxLines = 1; letterSpacing = 0.1f / 14f }
        val item = LinearLayout(ctx.context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ctx.px(20f)
            layoutParams = LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = ctx.px(8f) }
            addView(icon, LayoutParams(ctx.px(16f), ctx.px(16f)))
            addView(state, LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
        // ha-heading-badge's type: a button only with an action.
        if (tap.action != "none" || hold.action != "none") {
            item.isClickable = true
            if (tap.action != "none") item.setOnClickListener { ctx.performAction(tap, eid) }
            if (hold.action != "none") item.bindHoldAction(ctx, hold, eid)
        }
        fun iconColor(s: EntityState): Int? = when {
            colorSpec == null || colorSpec == "none" -> null
            colorSpec == "state" -> {
                val rgb = (s.attributes["rgb_color"] as? List<*>)?.mapNotNull { (it as? Number)?.toDouble() }
                if (s.domain == "light" && rgb != null && rgb.size >= 3) TileCardView.contrastLightColor(rgb[0], rgb[1], rgb[2])
                else HaStateColor.of(ctx, s, inactiveDefault = false)
            }
            HaStateActive.of(s) -> HaStateColor.cssColor(ctx, colorSpec)
            else -> null
        }
        // The state-display text when it changes on its own (a relative time, a running timer), for TimestampText to
        // draw again each second while on screen; null when it doesn't, or the state is hidden.
        val ticking: (EntityState, Long) -> String? = { s, now ->
            if (!showState) null
            else HaStateDisplay.of(s, content, now, ctx.haConfig(), dashUnavailable = true, name = configuredName ?: s.displayName)
                .takeIf { it.tickMs != null }?.text
        }
        fun render(s: EntityState?) {
            if (s == null && ctx.store.isMissing(eid)) {
                icon.visibility = VISIBLE
                state.visibility = VISIBLE
                (state.layoutParams as LayoutParams).marginStart = ctx.px(3f)
                icon.setIcon("mdi:alert-circle", HaStateColor.property(ctx, "red-color") ?: 0xFFF44336.toInt())
                ctx.showRelativeOr(state, null, "-", ticking)
                return
            }
            icon.visibility = if (showIcon) VISIBLE else GONE
            state.visibility = if (showState) VISIBLE else GONE
            // flex `gap: 3px` between the two, only when both show.
            (state.layoutParams as LayoutParams).marginStart = if (showIcon && showState) ctx.px(3f) else 0
            icon.setIcon(MdiIcons.resolveIconName(explicitIcon, s), s?.let(::iconColor) ?: ctx.theme.secondaryTextColor)
            if (s == null) { ctx.showRelativeOr(state, null, "…", ticking); return }
            val name = configuredName ?: s.displayName
            item.contentDescription = name
            ctx.showRelativeOr(state, s, HaStateDisplay.of(s, content, ctx.now(), ctx.haConfig(), dashUnavailable = true, name = name).text, ticking)
        }
        bind {
            render(ctx.store[eid])
            ctx.store.subscribe(eid) { ctx.main.post { render(ctx.store[eid]) } }
        }
        refreshers += { render(ctx.store[eid]) }
        return item
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Bound in the constructor, before attachment: the badges are drawn again, with the current state.
        if (unsubs.isEmpty()) binders.forEach { unsubs += it() } else refreshers.forEach { it() }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        unsubs.forEach { runCatching { it() } }
        unsubs.clear()
    }

    companion object {
        /** A heading's `badges`: `{type: entity, entity, icon}` objects or bare entity ids, as `(entity, icon)`. */
        fun parseBadges(arr: JSONArray?): List<Pair<String, String?>> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i)
                val eid = o?.optString("entity", "") ?: arr.optString(i, "")
                if (eid.isEmpty() || '.' !in eid) null
                else eid to o?.optString("icon", "")?.ifEmpty { null }
            }
        }
    }
}

/**
 * Native HA `markdown` card with live Jinja2 template support via `render_template`. The content goes through the
 * same pipeline as HA's `ha-markdown` ([MarkdownRenderer]): `marked`, HA's sanitizer (inline `style` and `class`
 * are dropped), then the browser defaults, HA's markdown styles and the card's card-mod sheets. The card's `"."`
 * sheet styles `ha-card` and `ha-markdown` (and its custom properties, such as `--card-text-align`, flow into the
 * Markdown); the `"ha-markdown$"` sheet styles the rendered elements. Templates in the content and in either sheet
 * re-render the card when they change. `text_only: true` drops the card surface and pads `2px 4px`, like HA.
 */
class MarkdownCardView(ctx: VitroCardContext, config: CardConfig) : CardSurface(ctx) {
    /** The `ha-markdown-element` box the rendered content fills, rebuilt on each update. */
    val body = LinearLayout(ctx.context).apply { orientation = LinearLayout.VERTICAL; unclip() }

    /** The `ha-markdown` host: its padding comes from HA's card styles and the card's sheet. */
    private val host = LinearLayout(ctx.context).apply { orientation = LinearLayout.VERTICAL; unclip() }
    private val renderer = MarkdownRenderer(ctx)
    private val textOnly = config.raw.optBoolean("text_only", false)
    private val hasHeader = !textOnly && config.title != null
    private var content: String? = null
    private var cardCss: String? = null
    private var cardSheet = MdSheet.EMPTY
    private var markdownCss: String? = null
    private var markdownSheet = MdSheet.EMPTY

    init {
        dropSurfaceIfTextOnly()
        // Content taller than the card's slot overflows visibly, as `ha-markdown` does in the browser (its padding
        // box is no clip): a 56 px text-only card with a big heading keeps its descenders.
        val col = object : LinearLayout(ctx.context) {
            // Laid out at its natural height even when the card's slot is shorter: a squeezed last line would
            // lose its descenders, where the browser lets the content overflow.
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.AT_MOST) MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED) else heightMeasureSpec
                super.onMeasure(widthMeasureSpec, h)
            }
        }.apply { orientation = LinearLayout.VERTICAL; unclip() }
        if (textOnly) unclip()
        if (hasHeader) {
            col.addView(TextView(ctx.context).apply {
                text = config.title
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 24f * ctx.dp)
                setTextColor(ctx.theme.primaryTextColor)
                typeface = VitroFonts.typeface(ctx.context, Inter.REGULAR, 24f)
                includeFontPadding = false
                letterSpacing = -0.012f
                setPadding(ctx.px(16f), ctx.px(20f), ctx.px(16f), ctx.px(24f))
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        host.addView(body, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        col.addView(host, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(col, LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val cardMod = config.raw.optJSONObject("card_mod")
        ThemeResolver.cardModStyleString(cardMod)?.takeIf { !isTemplate(it) }?.let { css ->
            cardCss = css
            cardSheet = MdSheet.parse(css)
        }
        ThemeResolver.cardModSheet(cardMod, "ha-markdown$")?.let { css ->
            if (isTemplate(css)) {
                renewing { ctx.subscribeTemplate(css) { rendered -> ctx.main.post { setMarkdownCss(rendered) } } }
            } else {
                markdownCss = css
                markdownSheet = MdSheet.parse(css)
            }
        }
        val rawContent = config.raw.optString("content", "")
        if (isTemplate(rawContent)) {
            // HA re-sends a template on reconnects and resubscribes: only a changed text is laid out again.
            renewing { ctx.subscribeTemplate(rawContent) { rendered -> ctx.main.post { if (rendered != content) { content = rendered; rerender() } } } }
        } else {
            content = rawContent
            rerender()
        }
    }

    private fun isTemplate(s: String) = "{{" in s || "{%" in s

    private fun android.view.ViewGroup.unclip() {
        clipChildren = false
        clipToPadding = false
    }

    private fun dropSurfaceIfTextOnly() {
        // HA's `.text-only { background: none; border: none; box-shadow: none }` outranks card-mod's `ha-card`.
        if (textOnly) {
            dropSurface()
            elevation = 0f
        }
    }

    private fun setMarkdownCss(css: String) {
        if (css == markdownCss) return
        markdownCss = css
        markdownSheet = MdSheet.parse(css)
        rerender()
    }

    override fun onCardModApplied(mod: dev.glassworks.engine.vitro.theme.CardModStyle) {
        dropSurfaceIfTextOnly()
        val css = activeCardCss
        if (css != cardCss) {
            cardCss = css
            cardSheet = MdSheet.parse(css)
            rerender()
        } else {
            // card-mod just rebuilt the surface: the cascade's fill goes back on it.
            cascadeFill?.let { fillFromCascade(it) }
        }
    }

    /** The card's fill from the cascade (a `:has()` rule), when it has one; see [rerender]. */
    private var cascadeFill: Int? = null

    private fun rerender() {
        val md = content ?: return
        // The card's host > ha-card > ha-markdown, styled by HA's card styles and the card's "." sheet (whose `:host`
        // is that host: its custom properties, `--card-text-align` say, flow into the card).
        val classes = listOfNotNull("text-only".takeIf { textOnly }, "with-header".takeIf { hasHeader }).joinToString(" ")
        val cardHost = MdNode("hui-markdown-card", mapOf(CssSelector.HOST_ATTR to ""))
        val haCard = cardHost.add(MdNode("ha-card", if (classes.isEmpty()) emptyMap() else mapOf("class" to classes)))
        val haMarkdown = haCard.add(MdNode("ha-markdown"))
        MdCascade.apply(cardHost, renderer.defaultRootStyle(), listOf(CARD_HOST to 1, cardSheet to 2, CARD_STYLES to 3), ctx.theme.vars)
        // A card rule the surface's own reading can't place (`ha-card:has(ha-markdown) { background }`, which needs
        // the card's content): the cascade's background for ha-card fills the card.
        cascadeFill = if (!textOnly && cardCss?.contains(":has(") == true) haCard.style?.background else null
        cascadeFill?.let { fillFromCascade(it) }
        val ms = haMarkdown.style!!
        host.setPadding(
            (ms.padding[3] * ctx.dp).roundToInt(), (ms.padding[0] * ctx.dp).roundToInt(),
            (ms.padding[1] * ctx.dp).roundToInt(), (ms.padding[2] * ctx.dp).roundToInt(),
        )
        renderer.render(md, body, ms, markdownSheet)
        relayoutFromRoot()
    }

    /**
     * New content must reach a layout pass even when an ancestor still holds a layout request from an earlier pass
     * (requestLayout stops at such an ancestor, and the card would keep its old, maybe empty, size until something
     * else relaid that branch out: seen live on the tablet's horizontal stack-in-card). Marks the path up to the
     * root for layout, then asks the root for a pass.
     */
    private fun relayoutFromRoot() {
        if (!isAttachedToWindow) return
        var v: View? = this
        var top: View = this
        while (v != null) {
            v.forceLayout()
            top = v
            v = v.parent as? View
        }
        top.requestLayout()
        requestLayout()
    }

    private companion object {
        /** `ha-card`'s own `:host` styles: below anything card-mod says about `ha-card`. */
        val CARD_HOST = MdSheet.parse("ha-card { color: var(--primary-text-color); display: block }")

        /** `hui-markdown-card`'s styles: adopted, so they win ties against card-mod. */
        val CARD_STYLES = MdSheet.parse(
            """
            ha-markdown { display: block; padding: 16px; text-align: var(--card-text-align, inherit) }
            .with-header ha-markdown { padding: 0 16px 16px }
            .text-only ha-markdown { padding: 2px 4px }
            """.trimIndent(),
        )
    }
}

/**
 * Native HA `picture`, `picture-entity`, and `picture-glance` card.
 * With `camera_view: live` it plays the camera as HA's `ha-camera-stream` does while it's on screen (WebRTC, HLS or
 * MJPEG, [LiveCameraFeed]) over a still, falling back to a snapshot every [LiveCameraFeed.POLL_MS] when no stream
 * plays (no badge: HA draws none). A `camera.*` source without it (`auto`, HA's default) shows a snapshot refreshed
 * every [LiveCameraFeed.SNAPSHOT_MS], as HA's `hui-image` does.
 * A `picture-glance` with `entities` gets HA's row of tappable entity icons on the right of its caption bar.
 */
class PictureCardView(ctx: VitroCardContext, config: CardConfig) : CardSurface(ctx) {
    private val img = ImageView(ctx.context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val caption = textView(ctx, 13f, Color.WHITE, Inter.MEDIUM).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    /** The footer's state, at the end when the name shows too. */
    private val stateCaption = textView(ctx, 16f, Color.WHITE, Inter.REGULAR).apply {
        maxLines = 1
        gravity = Gravity.CENTER_VERTICAL or Gravity.END
    }
    /** Where the picture comes from: `camera_image` when set (the entity keeps the caption and actions), else the entity. */
    private val pictureSource: String? = config.raw.optString("camera_image", "").ifEmpty { null } ?: config.entity
    private val liveEntityId: String? = pictureSource
        ?.takeIf { config.raw.optString("camera_view") == "live" || it.startsWith("camera.") }
    private val isLive = liveEntityId != null
    private var latestPicture: String? = null
    /** The live players' views, over [img] and under the caption bar. */
    private val streamHost = FrameLayout(ctx.context)
    private val feed: LiveCameraFeed? = liveEntityId?.let { id ->
        LiveCameraFeed(
            ctx, id, { latestPicture },
            stream = config.raw.optString("camera_view") == "live",
            host = streamHost,
            fitMode = config.raw.optString("fit_mode").ifEmpty { null },
            onVideoSize = { w, h -> takeAspect(w, h) },
            cardType = config.type,
            still = img,
        ) { bmp -> showBitmap(bmp) }
    }

    /** Width / height, from `aspect_ratio` (`16:9`, `4:3`, `50%` = height as % of width, or a decimal). */
    private val aspect: Float = parseAspectRatio(config.raw.opt("aspect_ratio")?.toString())
    /** `state_filter`: a CSS filter per state. */
    private val stateFilters: JSONObject? = config.raw.optJSONObject("state_filter")
    /**
     * Without `aspect_ratio`, hui-image is the image at full width and its own shape once it has loaded: picture,
     * picture-entity and picture-glance alike.
     */
    private val naturalAspect = !config.raw.has("aspect_ratio")
    private var imageAspect: Float? = null

    /**
     * hui-image's filter: the card's `state_filter` for the state, else `grayscale(100%)` while the entity is missing
     * or closed / locked / not_home / off.
     */
    private fun applyStateFilter(s: EntityState?) {
        val state = s?.state ?: "unavailable"
        val configured = stateFilters?.optString(state)?.takeIf { it.isNotBlank() }
        val css = configured ?: if (s == null || state in STATES_OFF) "grayscale(100%)" else null
        val filter = CssFilter.matrix(css)?.let { android.graphics.ColorMatrixColorFilter(it) }
        img.colorFilter = filter
        // hui-image filters its whole container, the live video included: its players draw in the window (a
        // TextureView, or MJPEG's image), so the stream host's layer carries the filter to them.
        if (isLive) {
            if (filter != null) {
                streamHost.setLayerType(LAYER_TYPE_HARDWARE, android.graphics.Paint().apply { colorFilter = filter })
            } else if (streamHost.layerType != LAYER_TYPE_NONE) {
                streamHost.setLayerType(LAYER_TYPE_NONE, null)
            }
        }
    }

    private fun showBitmap(bmp: android.graphics.Bitmap) {
        img.setImageBitmap(bmp)
        takeAspect(bmp.width, bmp.height)
    }

    /** Without `aspect_ratio` the card takes the picture's shape: the still's, then the live video's. */
    private fun takeAspect(width: Int, height: Int) {
        if (!naturalAspect || width <= 0 || height <= 0) return
        val a = width.toFloat() / height
        if (imageAspect == null || kotlin.math.abs(imageAspect!! - a) > 0.01f) {
            imageAspect = a
            requestLayout()
        }
    }

    init {
        addView(img, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        if (isLive) addView(streamHost, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val showName = config.raw.optBoolean("show_name", true)
        val showState = config.raw.optBoolean("show_state", true)
        val glanceEntities = if (config.type == "picture-glance") config.entities().filter { it.isEntity } else emptyList()
        if (config.type == "picture-entity" && (showName || showState)) {
            // hui-picture-entity-card's footer: 16 px white text on rgba(0,0,0,.3), 16 px padding; name and state
            // spread apart, or one of them centered (`.single`).
            caption.textSize = ctx.scaledSp(16f)
            caption.typeface = VitroFonts.typeface(ctx.context, Inter.REGULAR, ctx.scaledSp(16f))
            caption.gravity = Gravity.CENTER_VERTICAL or if (showName && showState) Gravity.START else Gravity.CENTER_HORIZONTAL
            val bar = FrameLayout(ctx.context).apply {
                setBackgroundColor(0x4D000000)
                setPadding(ctx.px(16f), 0, ctx.px(16f), 0)
                addView(caption, LayoutParams(MATCH_PARENT, ctx.px(48f)))
                if (showName && showState) addView(stateCaption, LayoutParams(MATCH_PARENT, ctx.px(48f)))
            }
            addView(bar, LayoutParams(MATCH_PARENT, ctx.px(48f), Gravity.BOTTOM))
        } else if (config.type != "picture" && (showName || showState || glanceEntities.isNotEmpty())) {
            // hui-picture-glance-card's .box: rgba(0,0,0,.3), 4 px 8 px padding, 48 px tall; the title 16 px / 500 on
            // a 32 px line 8 px in; then the entities' 40 px icon buttons in two rows, the ones that open More Info and
            // the ones that toggle, spread out as the box's `justify-content: space-between` does.
            caption.textSize = ctx.scaledSp(16f)
            caption.typeface = VitroFonts.typeface(ctx.context, Inter.MEDIUM, ctx.scaledSp(16f))
            val forceDialog = config.raw.optBoolean("force_dialog", false)
            val (toggles, dialogs) = glanceEntities.partition { !forceDialog && it.entity.substringBefore('.') in ActionConfig.DOMAINS_TOGGLE }
            val bar = GlanceBar(ctx.context).apply {
                setBackgroundColor(0x4D000000)
                setPadding(ctx.px(16f), ctx.px(4f), ctx.px(8f), ctx.px(4f))
                minimumHeight = ctx.px(48f)
                addView(caption, ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
                addView(buildGlanceIcons(dialogs, dialog = true), ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
                addView(buildGlanceIcons(toggles, dialog = false), ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            }
            addView(bar, LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        }

        val staticImage = config.raw.optString("image", "").ifEmpty { null }
        if (staticImage != null) {
            ctx.loadImage(staticImage, this) { bmp -> if (bmp != null) showBitmap(bmp) }
        }
        // HA's picture-glance labels its bar with `title`; the other picture cards use `name`.
        fun label(s: EntityState?): String? = if (config.type == "picture-glance") config.title ?: config.name(s) else config.name(s)
        label(null)?.let { caption.text = it }
        val eid = config.entity ?: config.raw.optString("camera_image", "").ifEmpty { null }
        // Bumped by every picture change: only the newest request lands, even when the URL came back to an older one.
        var pictureGeneration = 0
        /** Shows [pic] (the source's `entity_picture`): only the newest request may land, and none clears the image. */
        fun showPicture(pic: String?) {
            val generation = ++pictureGeneration
            latestPicture = pic
            // Live cards are refreshed by the feed; others load once per state change.
            if (isLive) return
            if (pic == null) {
                img.setImageDrawable(null)
                return
            }
            ctx.loadImage(pic, this) { bmp -> if (bmp != null && generation == pictureGeneration) showBitmap(bmp) }
        }
        if (staticImage == null && pictureSource != null && pictureSource != eid) {
            observe(pictureSource) { s -> showPicture(s?.entityPicture) }
        }
        if (eid != null) {
            isClickable = true
            setOnClickListener { ctx.performAction(ActionConfig.parse(config.raw.optJSONObject("tap_action")), eid) }
            bindHoldAction(holdActionFor(config.raw, eid), eid)
            observe(eid) { s ->
                applyStateFilter(s)
                // hui-picture-entity-card: HA's formatEntityState, without the tile's extras ("On", not "65%").
                if (config.type == "picture-entity" && showName && showState) {
                    caption.text = label(s) ?: s?.displayName ?: eid
                    stateCaption.text = s?.plainState().orEmpty()
                } else {
                    val parts = buildList {
                        if (showName) add(label(s) ?: s?.displayName ?: eid)
                        if (showState && s != null && glanceEntities.isEmpty()) add(s.plainState())
                    }
                    caption.text = parts.joinToString(" · ")
                }
                if (staticImage == null && pictureSource == eid) showPicture(s?.entityPicture)
            }
        }
    }

    /**
     * One of HA picture-glance's two icon rows: 24 dp icons in 40 dp touch targets, each with its own actions. A
     * [dialog] row's icons open More Info by default, the other row's toggle.
     */
    private fun buildGlanceIcons(entries: List<EntityRowConfig>, dialog: Boolean): LinearLayout = LinearLayout(ctx.context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL or Gravity.END
        entries.forEach { entry ->
            val icon = MdiIconView(ctx.context)
            val target = FrameLayout(ctx.context).apply {
                isClickable = true
                isFocusable = true
                applyHaRipple(this, 999f, true)
                addView(icon, LayoutParams(ctx.px(24f), ctx.px(24f), Gravity.CENTER))
                setOnClickListener { ctx.performAction(glanceTapAction(entry, dialog), entry.entity) }
                bindHoldAction(ctx, entry.holdAction ?: ActionConfig("more-info"), entry.entity)
            }
            addView(target, LinearLayout.LayoutParams(ctx.px(40f), ctx.px(40f)))
            observe(entry.entity) { s ->
                val name = s?.friendlyName ?: entry.entity
                target.contentDescription = entry.nameFor(s) ?: name
                // hui-picture-glance-card: `--ha-picture-icon-button-color` (#a9a9a9), white when the entity is on.
                val tint = if (s != null && s.isActive) GLANCE_ACTIVE else GLANCE_INACTIVE
                icon.setIcon(MdiIcons.resolveIconName(entry.icon, s), tint)
            }
        }
    }

    override fun measureCard(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = MeasureSpec.getMode(widthMeasureSpec)
        val width = MeasureSpec.getSize(widthMeasureSpec)
        if (mode == MeasureSpec.UNSPECIFIED || width <= 0) {
            super.measureCard(widthMeasureSpec, heightMeasureSpec)
            return
        }
        // hui-image takes the card's width inside its border: the picture's height comes from that, then the border.
        val inner = (width - paddingLeft - paddingRight).coerceAtLeast(1)
        val height = (Math.round(inner / (imageAspect ?: aspect)) + paddingTop + paddingBottom).coerceAtLeast(1)
        super.measureCard(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Android calls onWindowVisibilityChanged on attach only when the window isn't GONE: read it here, so a card
        // attached to a hidden window starts nothing, and one attached to a visible window starts now.
        windowVisible = windowVisibility == VISIBLE
        followShown()
        if (feed != null) {
            viewTreeObserver.addOnScrollChangedListener(onScrolled)
            viewTreeObserver.addOnGlobalLayoutListener(onLaidOut)
            followOnScreen()
        }
    }

    override fun onDetachedFromWindow() {
        if (feed != null) {
            viewTreeObserver.removeOnScrollChangedListener(onScrolled)
            viewTreeObserver.removeOnGlobalLayoutListener(onLaidOut)
        }
        // The card went: its players close (it starts afresh if it comes back).
        feed?.stop()
        super.onDetachedFromWindow()
    }

    private val visibleRect = android.graphics.Rect()
    private val onScrolled = android.view.ViewTreeObserver.OnScrollChangedListener { followOnScreen() }
    private val onLaidOut = android.view.ViewTreeObserver.OnGlobalLayoutListener { followOnScreen() }

    /**
     * Whether any of the card is inside the window (a scrolled view clips the rest): a camera scrolled out of sight
     * stops drawing, and its slot goes to one on screen ([LiveCameraFeed.setOnScreen]). A hidden card counts as on
     * screen here: [followShown] pauses it.
     */
    private fun followOnScreen() {
        val f = feed ?: return
        if (!isAttachedToWindow) return
        val on = !isShown || width == 0 || height == 0 || getGlobalVisibleRect(visibleRect)
        guarded("camera") { f.setOnScreen(on) }
    }

    /**
     * A cached view that is hidden (another view shown, HA's own page in front) stays attached: its players pause
     * until it shows again, and a card that was never on screen opens none.
     */
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (isAttachedToWindow) followShown()
    }

    /** Whether the window shows: false while it's gone (the panel's screen off, the app in the background). */
    private var windowVisible = false

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        windowVisible = visibility == VISIBLE
        if (isAttachedToWindow) followShown()
    }

    /** Plays (starting if it hasn't) while the card and its window show, and pauses otherwise. */
    private fun followShown() {
        val f = feed ?: return
        guarded("camera") {
            if (isShown && windowVisible) {
                f.start()
                f.setActive(true)
            } else {
                f.setActive(false)
            }
        }
    }

    /**
     * Starts a live camera as its view builds, before the view is attached ([VitroView] calls it for a card its build
     * shows), when the connection knows the camera's capabilities: its players open and connect while the rest of the
     * view builds, rather than once it's on screen. Attached, it plays or pauses as usual ([followShown]); one the
     * build never attached (a card replaced or dropped) closes what it opened on the next turn of the main loop.
     */
    internal fun startEarly() {
        val f = feed ?: return
        if (isAttachedToWindow || !f.capabilitiesKnown) return
        guarded("camera") { f.start() }
        ctx.main.post { if (!isAttachedToWindow) guarded("camera") { f.stop() } }
    }

    override fun onCardFailed() {
        feed?.stop()
    }

    internal companion object {
        private const val GLANCE_INACTIVE = 0xFFA9A9A9.toInt()
        private const val GLANCE_ACTIVE = Color.WHITE

        /**
         * picture-glance's per-icon `tap_action`: the configured one, else what its row does, `more-info` in the
         * [dialog] row (every domain outside HA's DOMAINS_TOGGLE, or all of them with `force_dialog`), `toggle` in the other.
         */
        fun glanceTapAction(entry: EntityRowConfig, dialog: Boolean): ActionConfig =
            entry.tapAction ?: ActionConfig(if (dialog) "more-info" else "toggle")

        const val DEFAULT_ASPECT = 16f / 9f
        /** hui-image's STATES_OFF: the states its default grey filter applies to. */
        private val STATES_OFF = setOf("closed", "locked", "not_home", "off")

        fun parseAspectRatio(raw: String?): Float {
            val s = raw?.trim()?.lowercase(Locale.ROOT).orEmpty()
            if (s.isEmpty()) return DEFAULT_ASPECT
            val ratio = when {
                s.endsWith("%") -> s.dropLast(1).trim().toFloatOrNull()?.let { if (it > 0f) 100f / it else null }
                ':' in s || 'x' in s -> {
                    val parts = s.split(':', 'x')
                    val w = parts.getOrNull(0)?.trim()?.toFloatOrNull()
                    val h = parts.getOrNull(1)?.trim()?.toFloatOrNull()
                    if (w != null && h != null && h > 0f) w / h else null
                }
                else -> s.toFloatOrNull()
            }
            return ratio?.takeIf { it.isFinite() && it > 0f } ?: DEFAULT_ASPECT
        }
    }
}

/**
 * Native Tier 2 fast-path for `custom:mushroom-chips-card`: entity, template, weather, light, action, menu, back
 * and spacer chips. Template chips render `content`, `icon` and `icon_color` live through `render_template`;
 * `conditional` chips (`conditions` + `chip`) show and hide with the entities they reference. Like Mushroom, a
 * chip's own `visibility` and `card_mod` are ignored. The card is Mushroom's bare `ha-card` around a wrapping flex
 * row (`gap: --chip-spacing`, `alignment` start / end / center / justify); each chip follows [ChipStyle].
 */
class MushroomChipsCardView(private val ctx: VitroCardContext, config: CardConfig) : ViewGroup(ctx.context) {
    private val subscriptions = ArrayList<() -> Unit>()

    /** Every subscription as a way to make it again: a view that is detached and attached again listens again. */
    private val binders = ArrayList<() -> (() -> Unit)>()

    /** Conditional chips whose rules read the clock or the user: re-checked on a timer while attached. */
    private val timedRefreshes = ArrayList<() -> Unit>()

    /** The chips' conditions with `time` rules: the next check also comes just after their next bound. */
    private val timedConditions = ArrayList<VisibilityCondition>()
    private val timedTick = object : Runnable {
        override fun run() {
            timedRefreshes.forEach { it() }
            postDelayed(this, nextTimedCheck())
        }
    }

    private fun nextTimedCheck(): Long =
        minOf(TIMED_CHECK_MS, VisibilityCondition.msUntilNextTimeBound(timedConditions) ?: TIMED_CHECK_MS)

    private fun bind(binder: () -> (() -> Unit)) {
        binders += binder
        subscriptions += binder()
    }

    private val style = ChipStyle.resolve(ctx, ownVars(ctx, config))
    private val alignment = config.raw.optString("alignment")
    /**
     * A chip length in device px, rounded: Chrome lays chips out in fractional px, and truncating each part (a 9 px
     * padding is 13.5 px at 1.5x) made every chip about a pixel narrower than HA's, adding up along the row.
     * Borders still truncate, as Chrome snaps them down.
     */
    private fun chipPx(v: Float): Int = Math.round(v * ctx.dp)

    private val spacingPx = chipPx(style.spacing)
    private val spacers = HashSet<View>()

    init {
        // card_mod's padding on the chips card's `ha-card` (Mushroom's own is none).
        ThemeResolver.parseCardMod(config.raw.optJSONObject("card_mod"), ctx.theme.vars).paddingInsets?.let { p ->
            setPadding(ctx.px(p.left), ctx.px(p.top), ctx.px(p.right), ctx.px(p.bottom))
        }
        val chips = config.raw.optJSONArray("chips")
        if (chips != null) {
            for (i in 0 until chips.length()) chips.optJSONObject(i)?.let(::addChip)
        }
    }

    /**
     * The card's own custom properties, with `var()` resolved against the theme, the enclosing cards' variables
     * and the card's other declarations (a second pass, so `--a: var(--b)` on the same card works).
     */
    private fun ownVars(ctx: VitroCardContext, config: CardConfig): Map<String, String> {
        val mod = config.raw.optJSONObject("card_mod")
        // The same scopes as `theme + card vars` and `+ its own`, reusing a map when there's nothing to add, so a
        // parse already made (by the card's surface, or the first pass) is reused.
        val outer = if (ctx.cardVars.isEmpty()) ctx.theme.vars else ctx.theme.vars + ctx.cardVars
        val first = ThemeResolver.parseCardMod(mod, outer).customProperties
        return if (first.isEmpty()) first else ThemeResolver.parseCardMod(mod, outer + first).customProperties
    }

    private fun addChip(c: JSONObject) {
        val conditional = c.optString("type") == "conditional"
        val inner = if (conditional) c.optJSONObject("chip") ?: return else c
        val conditions = if (conditional) VisibilityCondition.parseList(c.optJSONArray("conditions")) else emptyList()
        val view = if (inner.optString("type") == "spacer") {
            View(ctx.context).also { spacers += it; addView(it, LayoutParams(0, 1)) }
        } else {
            buildChip(inner).also { addView(it, LayoutParams(WRAP_CONTENT, WRAP_CONTENT)) }
        }
        if (conditions.isEmpty()) return
        val widthDp = ctx.context.resources.configuration.screenWidthDp.takeIf { it > 0 } ?: 1024
        fun refresh() {
            val uid = ctx.haUser().optString("id", "").ifEmpty { null }
            val nowMs = ctx.now()
            val show = conditions.all { VisibilityCondition.evaluateAt(it, nowMs, { id -> ctx.store[id] }, widthDp, uid) }
            view.visibility = if (show) VISIBLE else GONE
        }
        refresh()
        val ids = LinkedHashSet<String>()
        conditions.forEach { it.referencedEntities(ids) }
        ids.forEach { id ->
            bind {
                refresh()
                ctx.store.subscribe(id) { ctx.main.post { refresh() } }
            }
        }
        if (conditions.any { it.dependsOnTime() || it.dependsOnUser() }) timedRefreshes += ::refresh
        conditions.filterTo(timedConditions) { it.dependsOnTime() }
    }

    /** The visible children split into flex lines, as `flex-wrap: wrap` breaks them for [innerWidth]. */
    private fun lines(innerWidth: Int): List<List<View>> {
        val out = ArrayList<List<View>>()
        var line = ArrayList<View>()
        var used = 0
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            if (v.visibility == GONE) continue
            val w = if (v in spacers) 0 else v.measuredWidth
            val need = if (line.isEmpty()) w else used + spacingPx + w
            if (line.isNotEmpty() && need > innerWidth) {
                out += line; line = ArrayList(); used = w
            } else {
                used = need
            }
            line += v
        }
        if (line.isNotEmpty()) out += line
        return out
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val unbounded = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED
        val inner = if (unbounded) Int.MAX_VALUE else (width - paddingLeft - paddingRight).coerceAtLeast(0)
        val childSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            if (v.visibility == GONE) continue
            if (v in spacers) v.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(1, MeasureSpec.EXACTLY))
            else v.measure(childSpec, childSpec)
        }
        val ls = lines(inner)
        var h = 0
        var maxW = 0
        ls.forEachIndexed { i, line ->
            if (i > 0) h += spacingPx
            h += line.maxOf { if (it in spacers) 0 else it.measuredHeight }
            maxW = maxOf(maxW, line.sumOf { if (it in spacers) 0 else it.measuredWidth } + spacingPx * (line.size - 1))
        }
        val w = if (unbounded) maxW + paddingLeft + paddingRight else width
        setMeasuredDimension(w, resolveSize(h + paddingTop + paddingBottom, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val inner = (r - l - paddingLeft - paddingRight).coerceAtLeast(0)
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        var y = paddingTop
        for (line in lines(inner)) {
            val lineH = line.maxOf { if (it in spacers) 0 else it.measuredHeight }
            val content = line.sumOf { if (it in spacers) 0 else it.measuredWidth } + spacingPx * (line.size - 1)
            val free = (inner - content).coerceAtLeast(0)
            val nSpacers = line.count { it in spacers }
            // Spacer chips grow (flex-grow: 1) and take the free space; otherwise `justify-content` places the line.
            var x = when {
                nSpacers > 0 -> 0
                alignment == "end" -> free
                alignment == "center" -> free / 2
                else -> 0
            }
            val between = if (nSpacers == 0 && alignment == "justify" && line.size > 1) free / (line.size - 1) else 0
            for (v in line) {
                val w = if (v in spacers) free / nSpacers else v.measuredWidth
                // align-items: flex-start: chips sit at the top of the line.
                val left = if (rtl) paddingLeft + inner - x - w else paddingLeft + x
                v.layout(left, y, left + w, y + if (v in spacers) 1 else v.measuredHeight)
                x += w + spacingPx + between
            }
            y += lineH + spacingPx
        }
    }

    /** An icon-only chip's name: its entity's, else its type's ("Back", "Menu"). */
    private fun chipName(c: JSONObject, type: String): String =
        c.optString("entity", "").ifEmpty { null }?.let { ctx.store[it]?.displayName ?: it }
            ?: c.optString("name", "").ifEmpty { null }
            ?: type.replaceFirstChar { it.titlecase(Locale.ROOT) }

    private fun buildChip(c: JSONObject): LinearLayout {
        val type = c.optString("type", "entity")
        val st = style
        val border = if (st.borderWidth > 0f) ctx.px(st.borderWidth).coerceAtLeast(1) else 0
        val radiusPx = ctx.dp * st.radius
        val chip = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = radiusPx
                setColor(st.background)
                if (border > 0) setStroke(border, st.borderColor)
            }
            // box-sizing: content-box: the border sits outside --chip-height.
            val h = chipPx(st.height)
            minimumHeight = h + 2 * border
            minimumWidth = h + 2 * border
            setPadding(chipPx(st.paddingX) + border, border, chipPx(st.paddingX) + border, border)
            isClickable = true
            applyHaRipple(this, radiusPx, ctx.theme.dark)
        }
        val icon = MdiIconView(ctx.context)
        val label = TextView(ctx.context).apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, st.fontSize * ctx.dp * ctx.theme.fontScale)
            setTextColor(st.textColor)
            typeface = MdFonts.typeface(ctx.context, st.fontWeight, false, null, st.fontSize)
            includeFontPadding = false
            maxLines = 1
        }
        val iconSize = chipPx(st.iconSize)
        // ::slotted(*:not(:last-child)) { margin-right: 0.15em }: the gap after the icon when a label follows.
        val iconLp = LinearLayout.LayoutParams(iconSize, iconSize)
        chip.addView(icon, iconLp)
        chip.addView(label, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        fun syncGap() {
            iconLp.marginEnd = if (label.visibility == VISIBLE && icon.visibility == VISIBLE) chipPx(st.height * 0.15f) else 0
            icon.layoutParams = iconLp
        }
        fun setLabel(text: String) {
            label.text = text
            label.visibility = if (text.isEmpty()) GONE else VISIBLE
            // A chip with an icon alone is named for TalkBack after its entity, or what it does.
            chip.contentDescription = if (text.isEmpty()) chipName(c, type) else null
            syncGap()
        }

        val eid = c.optString("entity", "").ifEmpty { null }
        val defaultTap = when {
            type == "back" || type == "menu" || type == "action" -> "none"
            eid != null -> "more-info"
            else -> "none"
        }
        val tap = ActionConfig.parse(c.optJSONObject("tap_action"), defaultTap)
        chip.setOnClickListener { ctx.performAction(tap, eid) }
        chip.bindHoldAction(ctx, ActionConfig.parse(c.optJSONObject("hold_action"), "none"), eid)
        val staticColor = MushroomColors.parse(c.optString("icon_color", "").takeUnless(::isTemplate), ctx.theme)

        when (type) {
            "template" -> {
                var iconName = "mdi:home"
                var iconColor: Int? = null
                fun paintIcon() = icon.setIcon(iconName.ifBlank { "mdi:home" }, iconColor ?: st.textColor)
                bindChipField(c, "content", eid) { setLabel(it) }
                bindChipField(c, "icon", eid) { iconName = it; icon.visibility = if (it.isBlank()) GONE else VISIBLE; syncGap(); paintIcon() }
                bindChipField(c, "icon_color", eid) { iconColor = MushroomColors.parse(it, ctx.theme); paintIcon() }
                if (c.optString("icon").isBlank()) icon.visibility = GONE
                syncGap()
                paintIcon()
            }
            "back", "menu", "action" -> {
                val fallback = when (type) {
                    "back" -> "mdi:arrow-left"
                    "menu" -> "mdi:menu"
                    else -> "mdi:flash"
                }
                icon.setIcon(c.optString("icon", "").ifEmpty { fallback }, staticColor ?: st.textColor)
                setLabel("")
            }
            else -> if (eid != null) {
                val render: (EntityState?) -> Unit = { s ->
                    if (type == "weather") {
                        // Mushroom's weather chip: its copy of HA's weather picture (night variant), then the
                        // conditions and the temperature, each only when asked for, joined by " ⸱ ".
                        icon.setIcon(MdiIcons.weatherIcon(s?.state.orEmpty()), staticColor ?: ctx.theme.stateIconColor)
                        icon.picture = s?.let { HaWeatherSvg.forCondition(it.state, night = true, vars = ctx.theme.vars, mushroom = true) }
                        setLabel(weatherChipLabel(c, s))
                    } else {
                        // Mushroom's entity chip: `ha-state-icon.active { color: var(--color) }`, where `--color` is
                        // only its `icon_color`; otherwise the icon takes the chip's text color (--primary-text-color),
                        // whatever the state (a person at home, a light that is on: parity case mushroom-chips-person).
                        val tint = staticColor?.takeIf { s != null && mushroomActive(s) } ?: ctx.theme.primaryTextColor
                        icon.setIcon(MdiIcons.resolveIconName(c.optString("icon", "").ifEmpty { null }, s), tint)
                        setLabel(
                            when (c.optString("content_info", "state")) {
                                "name" -> c.optString("name", "").ifEmpty { s?.friendlyName ?: eid }
                                "last-changed" -> s?.relativeLastChanged(ctx.now() / 1000.0).orEmpty()
                                "last-updated" -> s?.let { it.copy(lastChanged = it.lastUpdated.takeIf { u -> u > 0.0 } ?: it.lastChanged) }
                                    ?.relativeLastChanged(ctx.now() / 1000.0).orEmpty()
                                "none" -> ""
                                // Mushroom's entity chip: HA's formatEntityState, without the tile's extras.
                                else -> s?.plainState() ?: "…"
                            },
                        )
                        // Its `state` info draws a timestamp sensor's, a scene's or a button's state relative.
                        if (c.optString("content_info", "state") !in setOf("name", "last-changed", "last-updated", "none")) {
                            ctx.showRelativeOr(label, s, label.text.toString(), TimestampText.MUSHROOM)
                        }
                    }
                }
                bind {
                    render(ctx.store[eid])
                    ctx.store.subscribe(eid) { s -> ctx.main.post { render(s) } }
                }
            } else {
                icon.setIcon(c.optString("icon", "mdi:home"), staticColor ?: ctx.theme.primaryColor)
                setLabel(c.optString("content", "").takeUnless(::isTemplate).orEmpty())
            }
        }
        return chip
    }

    /** Applies a chip field now when static, or every time HA re-renders it when it is a template. */
    private fun bindChipField(c: JSONObject, key: String, entityId: String?, apply: (String) -> Unit) {
        val raw = c.optString(key, "")
        if (isTemplate(raw)) {
            apply("")
            bind {
                ctx.subscribeTemplate(withEntityVariable(raw, entityId)) { rendered ->
                    ctx.main.post { apply(rendered.trim()) }
                }
            }
        } else {
            apply(raw)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (subscriptions.isEmpty()) binders.forEach { subscriptions += it() }
        if (timedRefreshes.isNotEmpty()) {
            timedRefreshes.forEach { it() }
            removeCallbacks(timedTick)
            postDelayed(timedTick, nextTimedCheck())
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(timedTick)
        subscriptions.forEach { runCatching { it() } }
        subscriptions.clear()
    }

    private companion object {
        /** How often a time- or user-gated chip re-checks; the user arrives after the first render. */
        const val TIMED_CHECK_MS = 15_000L
    }
}

/**
 * Where HA's brightness slider sits for a `brightness` (0..255): `max(round(b * 100 / 255), 1)` percent on a 1..100
 * slider, so 5 % is (5 - 1) / 99 of the way.
 */
internal fun brightnessFraction(brightness: Int): Float =
    (maxOf((brightness * 100f / 255f).roundToInt(), 1) - 1) / 99f

/** HA's `lightSupportsBrightness`: a supported color mode other than `onoff` (and `unknown`). */
internal fun lightSupportsBrightness(s: EntityState): Boolean =
    (s.attributes["supported_color_modes"] as? List<*>)?.any { it in BRIGHTNESS_MODES } == true

private val BRIGHTNESS_MODES = setOf("brightness", "color_temp", "hs", "xy", "rgb", "rgbw", "rgbww", "white")

/**
 * Mushroom's weather chip label: HA's state display when `show_conditions`, then the temperature attribute as HA's
 * `formatEntityAttributeValue` writes it when `show_temperature` (both off by default), joined by " ⸱ ".
 */
internal fun weatherChipLabel(c: JSONObject, s: EntityState?): String {
    if (s == null) return ""
    val parts = ArrayList<String>(2)
    if (c.optBoolean("show_conditions", false)) parts += s.plainState()
    if (c.optBoolean("show_temperature", false)) {
        val raw = s.attributes["temperature"]
        val v = (raw as? Number)?.toDouble() ?: raw?.toString()?.toDoubleOrNull()
        val unit = s.attributes["temperature_unit"]?.toString().orEmpty()
        when {
            v != null -> parts += HaDisplay.formatValue(v, 3) + when (unit) { "" -> ""; "%", "°" -> unit; else -> " $unit" }
            raw != null -> parts += raw.toString()
        }
    }
    return parts.joinToString(" \u2E31 ")
}

/** The 3 dp playback progress line along the bottom of the media card, filled in the theme's primary color. */
internal class MediaProgressView(private val ctx: VitroCardContext) : View(ctx.context) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var fraction = 0f
    private var onArt = false

    fun setFraction(value: Float, overArtwork: Boolean) {
        if (value == fraction && overArtwork == onArt) return
        fraction = value
        onArt = overArtwork
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        track.color = CardSurface.withAlpha(if (onArt) Color.WHITE else ctx.theme.primaryTextColor, 0.18f)
        fill.color = ctx.theme.primaryColor
        canvas.drawRect(0f, 0f, w, h, track)
        canvas.drawRect(0f, 0f, w * fraction, h, fill)
    }
}

/**
 * Mushroom's chip variables for one chips card, in CSS px: `--chip-height` (36), `--chip-padding` (`0 0.25em`),
 * `--chip-icon-size` (0.5em), `--chip-font-size` (0.3em), `--chip-font-weight` (bold), `--chip-spacing` (8),
 * `--chip-border-radius` (19), `--chip-background`, `--chip-border-width` / `-color` (the card's) and the text and
 * icon color (`--primary-text-color`). Each is read from the card's own and inherited `card_mod` variables, then
 * the theme, then the theme's `--mush-chip-*`, then Mushroom's default; `em` is the chip height, as Mushroom sets
 * `font-size: var(--chip-height)` on the chip.
 */
internal class ChipStyle(
    val height: Float,
    val paddingX: Float,
    val iconSize: Float,
    val fontSize: Float,
    val fontWeight: Int,
    val spacing: Float,
    val radius: Float,
    val background: Int,
    val borderWidth: Float,
    val borderColor: Int,
    val textColor: Int,
) {
    companion object {
        private val CHIP_LENGTH = Regex("""^(-?[\d.]+)(px|em|rem)?$""")
        private val CHIP_SPACES = Regex("""\s+""")

        fun resolve(ctx: VitroCardContext, own: Map<String, String>): ChipStyle {
            val theme = ctx.theme
            val vars = theme.vars + ctx.cardVars + own
            fun raw(name: String): String? = (vars["chip-$name"] ?: vars["mush-chip-$name"])
                ?.let { ThemeResolver.resolveValue(it, vars, 0) }?.trim()?.takeIf { it.isNotEmpty() }
            fun len(v: String?, em: Float): Float? {
                val m = CHIP_LENGTH.find(v?.trim().orEmpty()) ?: return null
                val n = m.groupValues[1].toFloatOrNull() ?: return null
                return when (m.groupValues[2]) { "em" -> n * em; "rem" -> n * 16f; else -> n }
            }
            fun color(v: String?): Int? {
                val s = v?.trim()?.lowercase(Locale.ROOT) ?: return null
                return if (s == "none" || s == "transparent") 0 else ThemeResolver.parseColor(s)
            }
            fun varColor(vararg names: String): Int? =
                names.firstNotNullOfOrNull { n -> vars[n]?.let { color(ThemeResolver.resolveValue(it, vars, 0)) } }
            val h = len(raw("height"), 16f) ?: 36f
            val pad = raw("padding")?.split(CHIP_SPACES)?.let { p -> len(p.getOrNull(1) ?: p[0], h) } ?: (0.25f * h)
            val weight = when (val w = raw("font-weight")?.lowercase(Locale.ROOT)) {
                null, "bold" -> 700
                "normal" -> 400
                else -> w.toIntOrNull()?.coerceIn(100, 900) ?: 700
            }
            val text = varColor("primary-text-color") ?: theme.primaryTextColor
            return ChipStyle(
                height = h,
                paddingX = pad,
                iconSize = len(raw("icon-size"), h) ?: (0.5f * h),
                fontSize = len(raw("font-size"), h) ?: (0.3f * h),
                fontWeight = weight,
                spacing = len(raw("spacing"), h) ?: 8f,
                radius = len(raw("border-radius"), h) ?: 19f,
                background = color(raw("background")) ?: varColor("ha-card-background", "card-background-color") ?: theme.cardBackgroundColor,
                borderWidth = len(raw("border-width"), h) ?: vars["ha-card-border-width"]?.let { len(ThemeResolver.resolveValue(it, vars, 0), h) } ?: theme.cardBorderWidthDp,
                borderColor = color(raw("border-color")) ?: varColor("ha-card-border-color", "divider-color") ?: theme.cardBorderColor,
                textColor = text,
            )
        }
    }
}

/** A bitmap center-cropped into the circle that fills its bounds, as CSS `object-fit: cover` in a round box. */
internal class CircleCropDrawable(private val bitmap: android.graphics.Bitmap) : android.graphics.drawable.Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        shader = android.graphics.BitmapShader(bitmap, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
    }
    private val matrix = android.graphics.Matrix()

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        val scale = maxOf(w / bitmap.width, h / bitmap.height)
        matrix.setScale(scale, scale)
        matrix.postTranslate(bounds.left + (w - bitmap.width * scale) / 2f, bounds.top + (h - bitmap.height * scale) / 2f)
        paint.shader.setLocalMatrix(matrix)
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), minOf(b.width(), b.height()) / 2f, paint)
    }

    override fun getIntrinsicWidth() = bitmap.width
    override fun getIntrinsicHeight() = bitmap.height
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Drawable API") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

/**
 * hui-picture-glance-card's `.box`: a flex row with `justify-content: space-between` holding the title (when it has
 * one) and the two icon rows. The icon rows take their width first and the title what is left (it ellipsizes); the
 * free space goes evenly between the items, each centered vertically. An empty title takes no slot, as HA renders no
 * title element then; an empty icon row keeps its (zero-width) slot, as HA's empty `.row` does.
 */
internal class GlanceBar(context: android.content.Context) : ViewGroup(context) {
    private fun present(v: View): Boolean = v.visibility != GONE && !(v is TextView && v.text.isNullOrEmpty())

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val avail = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        val unbounded = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        var used = 0
        var tallest = 0
        val texts = ArrayList<View>()
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            if (c is TextView) { texts += c; continue }
            c.measure(unbounded, unbounded)
            used += c.measuredWidth
            tallest = maxOf(tallest, c.measuredHeight)
        }
        for (c in texts) {
            c.measure(MeasureSpec.makeMeasureSpec((avail - used).coerceAtLeast(0), MeasureSpec.AT_MOST), unbounded)
            if (present(c)) tallest = maxOf(tallest, c.measuredHeight)
        }
        val height = maxOf(tallest + paddingTop + paddingBottom, minimumHeight)
        setMeasuredDimension(width, resolveSize(height, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val items = (0 until childCount).map(::getChildAt).filter(::present).ifEmpty { return }
        val avail = (r - l - paddingLeft - paddingRight).coerceAtLeast(0)
        val free = (avail - items.sumOf { it.measuredWidth }).coerceAtLeast(0)
        val gaps = items.size - 1
        val inner = b - t - paddingTop - paddingBottom
        var x = paddingLeft.toFloat()
        items.forEachIndexed { i, c ->
            val top = paddingTop + (inner - c.measuredHeight) / 2
            val left = Math.round(x)
            c.layout(left, top, left + c.measuredWidth, top + c.measuredHeight)
            x += c.measuredWidth + if (gaps > 0 && i < gaps) free.toFloat() / gaps else 0f
        }
    }
}

/** A picture covering its bounds (`object-fit: cover`, centered), cut to a rounded rectangle of [radius] px. */
internal class RoundedCoverDrawable(private val bitmap: android.graphics.Bitmap, private val radius: Float) : android.graphics.drawable.Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        shader = android.graphics.BitmapShader(bitmap, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
    }
    private val matrix = android.graphics.Matrix()
    private val rect = RectF()

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        val scale = maxOf(w / bitmap.width, h / bitmap.height)
        matrix.setScale(scale, scale)
        matrix.postTranslate(bounds.left + (w - bitmap.width * scale) / 2f, bounds.top + (h - bitmap.height * scale) / 2f)
        paint.shader.setLocalMatrix(matrix)
        rect.set(bounds)
    }

    override fun draw(canvas: Canvas) {
        if (radius <= 0f) canvas.drawRect(rect, paint) else canvas.drawRoundRect(rect, radius, radius, paint)
    }

    override fun getIntrinsicWidth() = bitmap.width
    override fun getIntrinsicHeight() = bitmap.height
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Drawable API") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}
