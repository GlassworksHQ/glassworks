/* SPDX-License-Identifier: MIT
 *
 * Ported from button-card: custom-cards/button-card, tag v7.0.1. Copyright (c) 2018, Alexandre Garcia.
 * The licence text is in THIRD-PARTY-NOTICES.md and LICENSES/MIT.txt.
 * Changes: rewritten in Kotlin for Vitro's native views; the card drawn with Android views, its colors and actions without JavaScript. */

package dev.glassworks.engine.vitro.cards

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import dev.glassworks.engine.vitro.compat.ButtonCardCompat
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.HaDisplay
import dev.glassworks.engine.vitro.model.ActionConfig
import dev.glassworks.engine.vitro.model.CardConfig
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.CardSurface
import dev.glassworks.engine.vitro.ui.MdiIconView
import dev.glassworks.engine.vitro.ui.MdiIcons
import dev.glassworks.engine.vitro.ui.VitroCardContext
import dev.glassworks.engine.vitro.ui.VitroFonts
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

// custom:button-card (custom-cards/button-card v7.0.1, MIT) drawn natively: its `_cardHtml`, `_gridHtml` and
// `_getIconHtml`, its stylesheet (src/styles.ts: the grid of each `layout`) and its color rules, ported. Configs with
// JavaScript templates never get here ([dev.glassworks.engine.vitro.compat.ButtonCardCompat] sends them to an island).

/**
 * A native `custom:button-card`. The view is the card's host; the card itself ([surface], `ha-card`) keeps its own
 * height inside it, as button-card's does: a stack that stretches the host doesn't stretch the card.
 */
class CustomButtonCardView(private val ctx: VitroCardContext, private val config: CardConfig) : FrameLayout(ctx.context) {
    private val raw: JSONObject = config.raw
    private val entityId: String? = raw.optString("entity", "").ifEmpty { null }?.let(ButtonCardJs::literal)
    private val layout: String = raw.optString("layout", "vertical").ifEmpty { "vertical" }
    private val colorType: String = raw.optString("color_type", "icon").ifEmpty { "icon" }
    private val blank = colorType == "blank-card"
    private val sectionMode = raw.optBoolean("section_mode", false)
    private val aspectRatio: Float? = ButtonCardCompat.aspectRatio(raw.opt("aspect_ratio"))
    private val states: JSONArray? = raw.optJSONArray("state")

    private val surface = Surface()
    private val grid = ButtonGridLayout(ctx.context)
    private val imgCell = ImgCell(ctx.context)
    private val icon = MdiIconView(ctx.context)
    private val picture = ImageView(ctx.context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val nameView = text()
    private val stateView = text()
    private val labelView = text()
    private val fields = LinkedHashMap<String, TextView>()
    private var lockOverlay: LockOverlay? = null

    /** The card's own CSS height in px, from `styles.card` (`height: 24px`); null when it sizes to its content. */
    private var cardHeight: ButtonCardCss.Length? = null
    private var cardMinHeight: ButtonCardCss.Length? = null
    private var cardPadding: List<ButtonCardCss.Length> = DEFAULT_PADDING
    private var translate: Pair<ButtonCardCss.Length, ButtonCardCss.Length>? = null
    private var pictureUrl: String? = null
    private var pictureLoading = false
    private var pictureLoaded = false
    private var pictureFailures = 0
    private var cardAnimator: ValueAnimator? = null
    private var iconAnimator: ValueAnimator? = null
    private var lastRender: EntityState? = null
    private val animKeys = HashMap<ValueAnimator, String?>()
    private var rendered = false

    init {
        // `vw` and `vh` are the panel's screen, the kiosk's full-screen viewport, in CSS px.
        context.resources.displayMetrics.let { m -> ButtonCardCss.viewport(m.widthPixels / m.density, m.heightPixels / m.density) }
        clipChildren = false
        addView(surface, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        if (!blank) {
            surface.addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))
            imgCell.addView(icon)
            imgCell.addView(picture)
            grid.addView(imgCell, ButtonGridLayout.Params("i"))
            grid.addView(nameView, ButtonGridLayout.Params("n"))
            grid.addView(stateView, ButtonGridLayout.Params("s"))
            grid.addView(labelView, ButtonGridLayout.Params("l"))
            for (key in fieldKeys()) {
                val tv = text()
                fields[key] = tv
                grid.addView(tv, ButtonGridLayout.Params(key, isField = true))
            }
            if (lockEnabled()) {
                lockOverlay = LockOverlay().also { addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)) }
            }
            wireActions()
        } else {
            surface.isClickable = false
        }
        val eid = entityId
        if (eid != null) surface.follow(eid) { s -> render(s) } else render(null)
        // card_mod on ha-card, under the card's own inline styles.
        surface.applyCardMod(config)
    }

    /** CSS px to device px, rounded (the browser lays out in fractions; truncating loses a pixel per box). */
    private fun px(css: Float): Int = (css * ctx.dp).roundToInt()

    private fun text(): TextView = TextView(ctx.context).apply {
        includeFontPadding = false
        gravity = Gravity.CENTER_HORIZONTAL
    }

    private fun fieldKeys(): Set<String> {
        val out = LinkedHashSet<String>()
        raw.optJSONObject("custom_fields")?.keys()?.forEach { out += it }
        states?.let { for (i in 0 until it.length()) it.optJSONObject(i)?.optJSONObject("custom_fields")?.keys()?.forEach { k -> out += k } }
        return out
    }

    private fun lockEnabled(): Boolean = raw.optJSONObject("lock")?.let { truthy(ButtonCardJs.value(it.opt("enabled"))) } == true

    // -------------------------------------------------------------------------------------------------------------
    // Rendering (`_cardHtml`)
    // -------------------------------------------------------------------------------------------------------------

    /** button-card's `hidden:` (a plain value; JavaScript ones go to an island). */
    private fun hidden(): Boolean = truthy(ButtonCardJs.value(raw.opt("hidden")))

    private fun render(s: EntityState?) {
        lastRender = s
        rendered = true
        visibility = if (hidden()) GONE else VISIBLE
        val configState = ButtonCardStates.match(states, s)
        val vars = vars(s)
        val cardDecls = ButtonCardCss.part(raw, configState, "card")
        // Custom properties set on the card reach everything in it, the card's own background included.
        val cardVars = vars.toMutableMap()
        for (d in cardDecls.values) if (d.name.startsWith("--")) cardVars[d.name.removePrefix("--")] = ThemeResolver.resolveValue(d.value, vars, 0)

        val color = mainColor(s, configState, cardVars)
        val colorInt = resolveColor(color, cardVars) ?: ctx.theme.primaryTextColor
        var fontColor: Int? = null
        val cardCss = StringBuilder()
        // The theme's ha-card variables a card overrides (`--ha-card-background: transparent`).
        for ((v, prop) in CARD_VAR_PROPS) {
            val value = cardVars[v] ?: continue
            if (cardVars[v] != vars[v] && cardDecls.keys.none { it == prop }) cardCss.append("$prop: $value;")
        }
        if (blank) {
            cardCss.append("background: none; box-shadow: none; border-style: none; border-width: 0;")
        } else if (colorType == "card" || colorType == "label-card") {
            fontColor = contrastFont(colorInt)
            cardCss.append("background-color: ${cssColor(colorInt)};")
        }
        cardVars["button-card-color"] = if (fontColor != null) cssColor(fontColor) else color
        val surfaceProps = setOf("background", "background-color", "background-image", "border", "border-width", "border-style",
            "border-color", "border-radius", "box-shadow", "opacity")
        for (d in cardDecls.values) {
            val n = d.name.lowercase(Locale.ROOT)
            if (n !in surfaceProps) continue
            val v = ThemeResolver.resolveValue(d.value, cardVars, 0).trim()
            // `border-style: none` takes the theme's border off; `solid` keeps it.
            if (n == "border-style") { if (v == "none" || v == "hidden") cardCss.append("border-width: 0;"); continue }
            cardCss.append("$n: $v${if (d.important) " !important" else ""};")
        }
        surface.setOwnCss(if (cardCss.isEmpty()) "" else "ha-card { $cardCss }")

        // Card box: height, padding, transform, animation, display.
        cardHeight = cardDecls["height"]?.let { ButtonCardCss.length(ThemeResolver.resolveValue(it.value, cardVars, 0), auto = true) }?.takeIf { !it.isAuto }
        cardMinHeight = cardDecls["min-height"]?.let { ButtonCardCss.length(ThemeResolver.resolveValue(it.value, cardVars, 0)) }
        cardPadding = cardDecls["padding"]?.let { ButtonCardCss.box(ThemeResolver.resolveValue(it.value, cardVars, 0)) } ?: if (blank) ZERO_BOX else DEFAULT_PADDING
        cardPadding = sides(cardPadding, cardDecls, "padding", cardVars)
        translate = cardDecls["transform"]?.let { translateOf(ThemeResolver.resolveValue(it.value, cardVars, 0)) }
        surface.visibility = if (cardDecls["display"]?.value?.trim() == "none") GONE else VISIBLE
        cardAnimator = animate(surface, cardDecls["animation"]?.value, cardAnimator)
        if (blank) {
            requestLayout()
            return
        }

        // Typography the card's texts inherit.
        val cardStyle = TextStyle(
            color = cardDecls["color"]?.let { resolveColor(it.value, cardVars) } ?: fontColor ?: ctx.theme.primaryTextColor,
            sizePx = cardDecls["font-size"]?.let { fontSize(it.value, BASE_FONT_PX, cardVars) } ?: BASE_FONT_PX,
            weight = cardDecls["font-weight"]?.let { weight(it.value, 400) } ?: 400,
            italic = cardDecls["font-style"]?.value?.trim() == "italic" || cardDecls["font-style"]?.value?.trim() == "oblique",
            family = cardDecls["font-family"]?.value,
            transform = cardDecls["text-transform"]?.value?.trim(),
            letterSpacing = cardDecls["letter-spacing"]?.value,
            lineHeight = cardDecls["line-height"]?.value,
        )

        // What the card shows.
        val name = buildName(s, configState)
        val stateDisplay = if (raw.optBoolean("show_state", false)) {
            (configState?.opt("state_display") as? String)?.takeIf { it.isNotEmpty() } ?: (raw.opt("state_display") as? String)?.takeIf { it.isNotEmpty() }
        } else null
        val stateString = stateDisplay?.let { ButtonCardCompat.decodeText(ButtonCardJs.literal(it)) } ?: stateString(s)
        val concat = layout == "icon_name_state" || layout == "name_state"
        val nameShown = if (concat) concat(name, stateString) else name
        val stateShown = if (concat) null else stateString
        val lastChanged = raw.optBoolean("show_last_changed", false) && s != null
        val label = if (lastChanged) relative(s) else buildLabel(configState)
        val showIcon = raw.optBoolean("show_icon", true)
        val iconName = buildIcon(configState)
        val picUrl = buildPicture(s, configState)
        val hasImg = (showIcon && (iconName != null || s != null)) || picUrl != null

        // The grid: button-card's stylesheet for this layout, then `styles.grid` over it.
        val classes = LinkedHashSet<String>().apply {
            add(layout)
            if (!hasImg) add("no-icon")
            if (nameShown.isNullOrEmpty()) add("no-name")
            if (stateShown.isNullOrEmpty()) add("no-state")
            if (label.isNullOrEmpty()) add("no-label")
            if (sectionMode) add("section")
        }
        val sheet = ButtonCardSheet.resolve(classes)
        val gridDecls = ButtonCardCss.part(raw, configState, "grid")
        val areas = gridDecls["grid-template-areas"]?.let { CssGridSpec.areas(it.value) } ?: sheet.areas
        val cols = gridDecls["grid-template-columns"]?.let { CssGridSpec.tracks(it.value) } ?: sheet.columns
        val rows = gridDecls["grid-template-rows"]?.let { CssGridSpec.tracks(it.value) } ?: sheet.rows
        val gap = gridDecls["gap"] ?: gridDecls["grid-gap"]
        val gapParts = gap?.let { ButtonCardCss.box(it.value) }
        grid.spec(
            areas, cols, rows,
            rowGap = (gridDecls["row-gap"] ?: gridDecls["grid-row-gap"])?.let { ButtonCardCss.length(it.value) } ?: gapParts?.get(0),
            colGap = (gridDecls["column-gap"] ?: gridDecls["grid-column-gap"])?.let { ButtonCardCss.length(it.value) } ?: gapParts?.get(1),
            alignItems = gridDecls["align-items"]?.value?.trim() ?: "center",
            justifyItems = gridDecls["justify-items"]?.value?.trim(),
            alignContentCenter = sectionMode,
        )

        // The icon cell.
        val cellDecls = ButtonCardCss.part(raw, configState, "img_cell")
        val iconDecls = ButtonCardCss.part(raw, configState, "icon")
        imgCell.visibility = if (hasImg && cellDecls["display"]?.value?.trim() != "none") VISIBLE else GONE
        styleItem(imgCell, sheet.items["img-cell"].orEmpty(), cellDecls, cardStyle, cardVars, isText = false)
        imgCell.applyBox(cellDecls, cardVars)
        val inheritedIcon = cellDecls["color"]?.let { resolveColor(it.value, cardVars) } ?: cardStyle.color
        val iconColor = iconDecls["color"]?.let { resolveColor(it.value, cardVars, inheritedIcon) }
            ?: if (fontColor != null) inheritedIcon else colorInt
        val size = raw.optString("size", "40%").ifEmpty { "40%" }
        imgCell.iconWidth = iconDecls["width"]?.let { ButtonCardCss.length(it.value, auto = true) } ?: ButtonCardCss.length(size, auto = true)
        imgCell.iconHeight = iconDecls["height"]?.let { ButtonCardCss.length(it.value, auto = true) }?.takeIf { !it.isAuto }
        imgCell.fixedCard = cardHeight != null || aspectRatio != null
        icon.alpha = iconDecls["opacity"]?.let { opacity(it.value) } ?: 1f
        if (picUrl != null) {
            icon.visibility = GONE
            picture.visibility = VISIBLE
            imgCell.showingPicture = true
            // `img#icon`: the icon's styles, then `styles.entity_picture` over them.
            val picDecls = ButtonCardCss.part(raw, configState, "entity_picture")
            picDecls["width"]?.let { ButtonCardCss.length(it.value, auto = true) }?.let { imgCell.iconWidth = it }
            picture.alpha = picDecls["opacity"]?.let { opacity(it.value) } ?: icon.alpha
            imgCell.picturePadding = sides(picDecls["padding"]?.let { ButtonCardCss.box(it.value) } ?: ZERO_BOX, picDecls, "padding", cardVars)
            val radius = picDecls["border-radius"]?.value?.trim()
            picture.outlineProvider = if (radius == null) android.view.ViewOutlineProvider.BACKGROUND else object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    val r = if (radius.endsWith("%")) min(view.width, view.height) * (radius.removeSuffix("%").toFloatOrNull() ?: 0f) / 100f
                        else (ButtonCardCss.length(radius)?.px(0f) ?: 0f) * ctx.dp
                    outline.setRoundRect(0, 0, view.width, view.height, r)
                }
            }
            picture.clipToOutline = radius != null
            loadPicture(picUrl)
        } else {
            imgCell.showingPicture = false
            picture.visibility = GONE
            icon.visibility = if (showIcon && iconDecls["display"]?.value?.trim() != "none") VISIBLE else GONE
            icon.setIcon(MdiIcons.resolveIconName(iconName, s), iconColor)
        }
        val rotate = truthy(ButtonCardJs.value(configState?.opt("rotate") ?: raw.opt("rotate")))
        iconAnimator = animate(icon, if (rotate) "rotating 2s linear infinite" else iconDecls["animation"]?.value, iconAnimator)

        // The texts.
        setText(nameView, nameShown, sheet.items["name"].orEmpty(), ButtonCardCss.part(raw, configState, "name"), cardStyle, cardVars)
        setText(stateView, stateShown, sheet.items["state"].orEmpty(), ButtonCardCss.part(raw, configState, "state"), cardStyle, cardVars)
        setText(labelView, label, sheet.items["label"].orEmpty(), ButtonCardCss.part(raw, configState, "label"), cardStyle, cardVars)
        val values = LinkedHashMap<String, Any?>()
        raw.optJSONObject("custom_fields")?.let { f -> for (k in f.keys()) values[k] = f.opt(k) }
        configState?.optJSONObject("custom_fields")?.let { f -> for (k in f.keys()) values[k] = f.opt(k) }
        for ((key, tv) in fields) {
            val v = values[key]
            val shown = if (v == null || v == JSONObject.NULL) null else ButtonCardCompat.decodeText(ButtonCardJs.literal(jsString(v)))
            val decls = ButtonCardCss.part(raw, configState, "custom_fields", key)
            // A custom field is always shown when set, even empty; it has no justify-self of its own (it stretches).
            setText(tv, shown, emptyMap(), decls, cardStyle, cardVars, showEmpty = true)
            (tv.layoutParams as ButtonGridLayout.Params).area = decls["grid-area"]?.value?.trim() ?: key
        }
        lockOverlay?.style(ButtonCardCss.part(raw, configState, "lock"), fontColor, cardVars)
        requestLayout()
        invalidate()
    }

    /** One text part: hidden when empty, else styled from the sheet's rules and its own declarations. */
    private fun setText(
        tv: TextView,
        value: String?,
        sheetDecls: Map<String, String>,
        decls: Map<String, ButtonCardCss.Decl>,
        card: TextStyle,
        vars: Map<String, String>,
        showEmpty: Boolean = false,
    ) {
        if (value == null || (value.isEmpty() && !showEmpty) || decls["display"]?.value?.trim() == "none") {
            tv.visibility = GONE
            return
        }
        tv.visibility = VISIBLE
        styleItem(tv, sheetDecls, decls, card, vars, isText = true)
        val st = card.with(decls, vars)
        tv.setTextColor(st.color)
        val sp = st.sizePx
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, ctx.scaledSp(sp))
        tv.typeface = VitroFonts.typeface(ctx.context, st.weight, ctx.scaledSp(sp), st.italic, st.family)
        tv.letterSpacing = st.letterSpacing?.let { ls ->
            if (ls.trim() == "normal") 0f else ButtonCardCss.length(ls)?.let { it.px(sp, sp) / sp } ?: 0f
        } ?: 0f
        st.lineHeight?.trim()?.takeIf { it != "normal" }?.let { lh ->
            val px = lh.toFloatOrNull()?.let { it * sp } ?: ButtonCardCss.length(lh)?.px(sp, sp) ?: return@let
            tv.setLineSpacing(0f, 1f)
            tv.setLineHeightCompat(px(px).coerceAtLeast(1))
        }
        val whiteSpace = decls["white-space"]?.value?.trim() ?: "nowrap"
        val wraps = whiteSpace !in setOf("nowrap", "pre")
        tv.maxLines = if (wraps) Int.MAX_VALUE else 1
        tv.setHorizontallyScrolling(!wraps)
        val clip = decls["text-overflow"]?.value?.trim() == "clip" || decls["overflow"]?.value?.trim() == "visible"
        tv.ellipsize = if (wraps || clip) null else TextUtils.TruncateAt.END
        val align = decls["text-align"]?.value?.trim() ?: "center"
        tv.gravity = when (align) {
            "left", "start" -> Gravity.START
            "right", "end" -> Gravity.END
            else -> Gravity.CENTER_HORIZONTAL
        } or Gravity.CENTER_VERTICAL
        tv.text = transform(value, st.transform)
        tv.alpha = decls["opacity"]?.let { opacity(it.value) } ?: 1f
        decls["background-color"]?.let { resolveColor(it.value, vars) }?.let { bg ->
            tv.background = GradientDrawable().apply {
                setColor(bg)
                cornerRadius = decls["border-radius"]?.let { ButtonCardCss.length(it.value)?.px(0f) }?.let { ctx.dp * it } ?: 0f
            }
        } ?: run { tv.background = null }
        val pad = sides(decls["padding"]?.let { ButtonCardCss.box(it.value) } ?: ZERO_BOX, decls, "padding", vars)
        // `line-height: normal` as Chrome lays a line out: its ascent and descent each rounded to a CSS px (16.8 px
        // Roboto is a 20 px line), the baseline at the rounded ascent. Android's line is the unrounded sum.
        var extraTop = 0
        var extraBottom = 0
        if (st.lineHeight == null || st.lineHeight.trim() == "normal") {
            val fm = tv.paint.fontMetrics
            val asc = Math.round(-fm.ascent / ctx.dp).toFloat()
            val desc = Math.round(fm.descent / ctx.dp).toFloat()
            // Against the integer metrics the TextView lays its line out with.
            val fmi = tv.paint.fontMetricsInt
            extraTop = (asc * ctx.dp).roundToInt() + fmi.ascent
            extraBottom = ((asc + desc) * ctx.dp).roundToInt() - (fmi.descent - fmi.ascent) - extraTop
        }
        tv.setPadding(px(pad[3].px(0f, sp)), px(pad[0].px(0f, sp)) + extraTop, px(pad[1].px(0f, sp)), px(pad[2].px(0f, sp)) + extraBottom)
    }

    /** Grid placement of one item: the sheet's `align-self` and `justify-self`, its own styles over them. */
    private fun styleItem(
        v: View,
        sheetDecls: Map<String, String>,
        decls: Map<String, ButtonCardCss.Decl>,
        card: TextStyle,
        vars: Map<String, String>,
        isText: Boolean,
    ) {
        val lp = v.layoutParams as ButtonGridLayout.Params
        lp.alignSelf = decls["align-self"]?.value?.trim() ?: sheetDecls["align-self"]
        lp.justifySelf = decls["justify-self"]?.value?.trim() ?: sheetDecls["justify-self"]
        lp.maxWidth = decls["max-width"]?.let { ButtonCardCss.length(it.value) } ?: sheetDecls["max-width"]?.let { ButtonCardCss.length(it) }
        lp.minWidth = decls["min-width"]?.let { ButtonCardCss.length(it.value) }
        lp.cssWidth = decls["width"]?.let { ButtonCardCss.length(it.value, auto = true) }?.takeIf { !it.isAuto }
            ?: sheetDecls["width"]?.let { ButtonCardCss.length(it) }
        lp.cssHeight = decls["height"]?.let { ButtonCardCss.length(it.value, auto = true) }?.takeIf { !it.isAuto }
            ?: sheetDecls["height"]?.let { ButtonCardCss.length(it) }
        val fontPx = card.with(decls, vars).sizePx
        lp.fontPx = fontPx
        lp.marginTop = sheetDecls["margin-top"]?.let { ButtonCardCss.length(it) }
        val margins = decls["margin"]?.let { ButtonCardCss.box(it.value, auto = true) } ?: ZERO_BOX
        lp.margins = sides(margins, decls, "margin", vars, auto = true).let { m ->
            if (decls["margin"] == null && decls["margin-top"] == null && lp.marginTop != null) listOf(lp.marginTop!!, m[1], m[2], m[3]) else m
        }
        lp.positionAbsolute = decls["position"]?.value?.trim() == "absolute"
        lp.insets = listOf("top", "right", "bottom", "left").map { side -> decls[side]?.let { ButtonCardCss.length(it.value) } }
        lp.isText = isText
    }

    private fun sides(base: List<ButtonCardCss.Length>, decls: Map<String, ButtonCardCss.Decl>, prop: String, vars: Map<String, String>, auto: Boolean = false): List<ButtonCardCss.Length> {
        // In declaration order, as the browser applies them: a later shorthand resets every side, a later longhand
        // only its own.
        var out = base.toMutableList()
        val sideNames = listOf("top", "right", "bottom", "left").map { "$prop-$it" }
        for (d in decls.values) {
            if (d.name == prop) {
                ButtonCardCss.box(ThemeResolver.resolveValue(d.value, vars, 0), auto)?.let { out = it.toMutableList() }
                continue
            }
            val i = sideNames.indexOf(d.name)
            if (i >= 0) ButtonCardCss.length(ThemeResolver.resolveValue(d.value, vars, 0), auto)?.let { out[i] = it }
        }
        return out
    }

    // -------------------------------------------------------------------------------------------------------------
    // What the card says (`_buildName`, `_buildStateString`, `_buildLabel`, `_buildIcon`)
    // -------------------------------------------------------------------------------------------------------------

    private fun buildName(s: EntityState?, configState: JSONObject?): String? {
        if (raw.opt("show_name") == false) return null
        val n = (configState?.opt("name") as? String)?.takeIf { it.isNotEmpty() }
            ?: (raw.opt("name") as? String)?.takeIf { it.isNotEmpty() }
            ?: s?.let { (it.attributes["friendly_name"] as? String)?.takeIf { f -> f.isNotEmpty() } ?: it.entityId.substringAfter('.') }
        return n?.let { ButtonCardCompat.decodeText(ButtonCardJs.literal(it)) }
    }

    private fun buildLabel(configState: JSONObject?): String? {
        if (!raw.optBoolean("show_label", false)) return null
        val l = (configState?.opt("label") as? String)?.takeIf { it.isNotEmpty() } ?: (raw.opt("label") as? String)
        return l?.let { ButtonCardCompat.decodeText(ButtonCardJs.literal(it)) }
    }

    private fun buildIcon(configState: JSONObject?): String? {
        if (!raw.optBoolean("show_icon", true)) return null
        val i = (configState?.opt("icon") as? String)?.takeIf { it.isNotEmpty() } ?: (raw.opt("icon") as? String)?.takeIf { it.isNotEmpty() }
        return i?.let(ButtonCardJs::literal)
    }

    private fun buildPicture(s: EntityState?, configState: JSONObject?): String? {
        if (!raw.optBoolean("show_entity_picture", false)) return null
        val p = (configState?.opt("entity_picture") as? String)?.takeIf { it.isNotEmpty() }
            ?: (raw.opt("entity_picture") as? String)?.takeIf { it.isNotEmpty() }
            ?: s?.entityPicture
        return p?.let(ButtonCardJs::literal)
    }

    /** `computeStateDisplay` as button-card has it: numbers with its `units`, `show_units` and `numeric_precision`. */
    private fun stateString(s: EntityState?): String? {
        if (!raw.optBoolean("show_state", false) || s == null || s.state.isEmpty()) return null
        return stateDisplay(s, raw)
    }

    private fun relative(s: EntityState): String? {
        if (s.lastChanged <= 0.0) return null
        return HaRelativeTime.format((s.lastChanged * 1000).toLong(), ctx.now())
    }

    private fun concat(name: String?, state: String?): String? = when {
        name.isNullOrEmpty() && state.isNullOrEmpty() -> null
        !state.isNullOrEmpty() -> if (!name.isNullOrEmpty()) "$name: $state" else state
        else -> name
    }

    private fun loadPicture(url: String) {
        // Once loaded (or loading), the same address isn't fetched again; a failed load may be retried a few times.
        if (url == pictureUrl && (pictureLoaded || pictureLoading)) return
        if (url != pictureUrl) pictureFailures = 0
        if (pictureFailures >= MAX_PICTURE_RETRIES) return
        pictureUrl = url
        pictureLoading = true
        pictureLoaded = false
        picture.setImageDrawable(null)
        surface.load(url) { bmp: Bitmap? ->
            if (pictureUrl != url) return@load
            pictureLoading = false
            if (bmp == null) {
                pictureFailures++
                return@load
            }
            pictureLoaded = true
            picture.setImageBitmap(bmp)
            imgCell.pictureAspect = bmp?.let { it.height.toFloat() / it.width.coerceAtLeast(1) }
            requestLayout()
        }
    }

    // -------------------------------------------------------------------------------------------------------------
    // Colors (`_cardHtml`, `_buildCssColorAttribute`, `_getColorForLightEntity`)
    // -------------------------------------------------------------------------------------------------------------

    /** The color `_cardHtml` computes: the state's, the config's (when active), or the entity's state color. */
    private fun mainColor(s: EntityState?, configState: JSONObject?, vars: Map<String, String>): String {
        val stateColor = (configState?.opt("color") as? String)?.takeIf { it.isNotEmpty() }
        val cfgColor = (raw.opt("color") as? String)?.takeIf { it.isNotEmpty() }
        return when {
            stateColor != null && stateColor !in AUTO -> stateColor
            cfgColor != null && cfgColor !in AUTO -> when {
                s == null -> cfgColor
                ButtonCardColors.active(s) -> cfgColor
                else -> ButtonCardColors.stateColorCss(s, s.state, colorType) ?: DEFAULT_COLOR
            }
            else -> {
                var value = stateColor ?: cfgColor ?: ""
                if (value in AUTO && (s == null || s.domain != "light")) value = ""
                when {
                    value in AUTO -> lightColor(s!!, value != "auto-no-temperature", vars)
                    value.isNotEmpty() -> value
                    s != null -> ButtonCardColors.stateColorCss(s, s.state, colorType) ?: DEFAULT_COLOR
                    else -> DEFAULT_COLOR
                }
            }
        }
    }

    private fun lightColor(s: EntityState, useTemperature: Boolean, vars: Map<String, String>): String {
        var color: String = DEFAULT_COLOR
        if (ButtonCardColors.active(s)) {
            val rgb = s.attributes["rgb_color"] as? List<*>
            val ct = (s.attributes["color_temp"] as? Number)?.toFloat()
            val minM = (s.attributes["min_mireds"] as? Number)?.toFloat()
            val maxM = (s.attributes["max_mireds"] as? Number)?.toFloat()
            color = when {
                rgb != null && rgb.size >= 3 -> "rgb(${rgb.take(3).joinToString(",") { jsString((it as? Number)?.toDouble() ?: 0.0) }})"
                useTemperature && ct != null && ct != 0f && minM != null && minM != 0f && maxM != null && maxM != 0f &&
                    ct.isFinite() && minM.isFinite() && maxM.isFinite() && maxM > minM ->
                    ButtonCardColors.temperatureColor(ct, minM, maxM)
                else -> ButtonCardColors.stateColorCss(s, s.state, colorType) ?: DEFAULT_COLOR
            }
            val brightness = (s.attributes["brightness"] as? Number)?.toFloat()
            if (brightness != null && brightness != 0f) {
                resolveColor(color, vars)?.let { c -> color = cssColor(ButtonCardColors.mixBlack(c, 100f - (brightness + 245f) / 5f)) }
            }
        } else {
            color = ButtonCardColors.stateColorCss(s, s.state, colorType) ?: DEFAULT_COLOR
        }
        return color
    }

    /** The variables a color may name: HA's global ones, the theme's, and the card's own (`:host`). */
    private fun vars(s: EntityState?): Map<String, String> {
        val out = LinkedHashMap<String, String>(ButtonCardColors.HA_GLOBALS)
        out.putAll(ctx.theme.vars)
        out.putAll(ctx.cardVars)
        // button-card's `:host { --state-inactive-color: var(--state-icon-color) }`.
        out["state-inactive-color"] = out["state-icon-color"] ?: "#44739e"
        if (s != null && s.domain == "light") {
            out["button-card-light-color"] = lightColor(s, true, out)
            out["button-card-light-color-no-temperature"] = lightColor(s, false, out)
        }
        return out
    }

    private fun resolveColor(raw: String, vars: Map<String, String>, inherited: Int? = null): Int? {
        val v = ThemeResolver.resolveValue(raw, vars, 0).trim().lowercase(Locale.ROOT)
        if (v == "inherit" || v == "currentcolor") return inherited
        return ButtonCardCss.color(v, vars)
    }

    /** `getFontColorBasedOnBackgroundColor`: a light or a dark text, whichever contrasts more (TinyColor's luminance). */
    private fun contrastFont(bg: Int): Int {
        val l = ButtonCardColors.luminance(bg)
        val light = Color.rgb(225, 225, 225)
        val dark = Color.rgb(28, 28, 28)
        if (l == 0.0) return light
        val ll = ButtonCardColors.luminance(light)
        val dl = ButtonCardColors.luminance(dark)
        val white = (max(l, ll) + 0.05) / min(l, ll + 0.05)
        val black = (max(l, dl) + 0.05) / min(l, dl + 0.05)
        return if (white > black) light else dark
    }

    // -------------------------------------------------------------------------------------------------------------
    // Actions
    // -------------------------------------------------------------------------------------------------------------

    private fun wireActions() {
        val tap = actionFor("tap_action", defaultTap())
        val hold = actionFor("hold_action", null)
        val double = actionFor("double_tap_action", null)
        val clickable = tap != null || hold != null || double != null
        val ripple = raw.opt("show_ripple").let { if (it is Boolean) it else clickable }
        if (ripple) applyHaRipple(surface, ctx.theme.cardBorderRadiusDp * ctx.dp, ctx.theme.dark)
        if (!clickable) return
        // The tap runs through the view's click, so an accessibility or keyboard activation (`performClick`) runs it
        // too. A touch's own click is skipped when the gesture detector already ran something for it: the tap after a
        // possible double tap, the double tap, or the hold.
        var touchHandled = false
        val detector = GestureDetector(ctx.context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                touchHandled = double != null
                return true
            }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (double != null) tap?.let(::run)
                return double != null
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                double?.let(::run)
                return double != null
            }
            override fun onLongPress(e: MotionEvent) {
                hold?.let {
                    touchHandled = true
                    surface.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    run(it)
                }
            }
        })
        surface.isClickable = true
        // The detector times the hold; the view's own long click would let the touch's click through after it.
        surface.isLongClickable = false
        surface.setOnClickListener {
            if (touchHandled) {
                touchHandled = false
                return@setOnClickListener
            }
            // The lock covers the card for touches; an accessibility click honors it too.
            if (lockOverlay?.isLocked == true) return@setOnClickListener
            tap?.let(::run)
        }
        @Suppress("ClickableViewAccessibility")
        surface.setOnTouchListener { v, ev ->
            detector.onTouchEvent(ev)
            // A cancelled touch posts no click: its suppression must not swallow the next keyboard or accessibility one.
            if (ev.actionMasked == MotionEvent.ACTION_CANCEL) touchHandled = false
            // The ripple, and the click on release.
            v.onTouchEvent(ev)
            // The suppression covers only the click this touch's release posts (View posts it before this runs). A
            // release that posts none (outside the card, past the touch slop) leaves nothing to swallow, so clear it.
            if (ev.actionMasked == MotionEvent.ACTION_UP) v.post { touchHandled = false }
            true
        }
    }

    /**
     * Every gesture's action goes through here: none while the card is locked, whatever reached the card (a hold or a
     * double tap on a part a `transform` moved out from under the lock, an accessibility click). The action itself
     * goes through the same service call HA's frontend makes, so HA's own permissions still apply.
     */
    private fun run(a: Pair<ActionConfig, String?>) {
        if (lockOverlay?.isLocked == true) return
        ctx.performAction(a.first, a.second)
    }

    /** `_finishSetup`'s default `tap_action`: toggle, press, more-info, or nothing. */
    private fun defaultTap(): JSONObject = when {
        entityId == null -> JSONObject().put("action", "none")
        entityId.substringBefore('.') in TOGGLE_DOMAINS -> JSONObject().put("action", "toggle")
        entityId.substringBefore('.') == "input_button" -> JSONObject().put("action", "call-service")
            .put("service", "input_button.press").put("target", JSONObject().put("entity_id", entityId))
        else -> JSONObject().put("action", "more-info")
    }

    /** One of the card's actions as Vitro runs it, with the entity it acts on, or null when it does nothing. */
    private fun actionFor(key: String, default: JSONObject?): Pair<ActionConfig, String?>? {
        val a = raw.optJSONObject(key) ?: default ?: return null
        val type = a.optString("action", "")
        if (type.isEmpty() || type == "none") return null
        // A PIN- or password-protected action only runs in an island, where button-card asks for it. A card that stays
        // native without its script (no Lovelace resource for it) never runs it unprotected.
        if (truthy(a.opt("protect")) || truthy(raw.opt("protect"))) return null
        val copy = JSONObject(a.toString())
        // button-card's `entity_id: entity` in the data is the card's entity.
        copy.optJSONObject("data")?.let { if (it.optString("entity_id") == "entity") it.put("entity_id", entityId) }
        copy.optJSONObject("service_data")?.let { if (it.optString("entity_id") == "entity") it.put("entity_id", entityId) }
        // `confirmation` on the action, else the card's.
        if (!copy.has("confirmation") && raw.has("confirmation")) copy.put("confirmation", raw.opt("confirmation"))
        // A toggle or more-info may name its own `entity`.
        val target = copy.optString("entity", "").ifEmpty { null }?.takeIf { type == "toggle" || type == "more-info" }
        return ActionConfig.parse(copy, "none") to (target ?: entityId)
    }

    // -------------------------------------------------------------------------------------------------------------
    // Layout
    // -------------------------------------------------------------------------------------------------------------

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec).takeIf { MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED }
            ?: px(200f)
        val wCss = w / ctx.dp
        val hostH = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(heightMeasureSpec) else null
        // The card's height: its aspect ratio, its own `height`, or (section mode, `height: 100%`) the slot's.
        val definite: Int? = when {
            aspectRatio != null -> (w / aspectRatio).roundToInt()
            cardHeight?.unit == "%" -> hostH?.let { (it * cardHeight!!.value / 100f).roundToInt() }
            cardHeight != null -> px(cardHeight!!.px(0f, BASE_FONT_PX))
            sectionMode -> hostH
            else -> null
        }
        surface.cssPadding(cardPadding.map { px(it.px(wCss)) })
        val minH = cardMinHeight?.let { px(it.px(0f)) } ?: 0
        surface.measure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            if (definite != null) MeasureSpec.makeMeasureSpec(max(definite, minH), MeasureSpec.EXACTLY) else MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        if (surface.measuredHeight < minH) {
            surface.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(minH, MeasureSpec.EXACTLY))
        }
        val cardH = if (surface.visibility == GONE) 0 else surface.measuredHeight
        lockOverlay?.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(cardH, MeasureSpec.EXACTLY))
        setMeasuredDimension(w, hostH ?: cardH)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        surface.layout(0, 0, surface.measuredWidth, surface.measuredHeight)
        lockOverlay?.layout(0, 0, surface.measuredWidth, surface.measuredHeight)
        val t = translate
        val tx = t?.first?.let { px(it.px(surface.measuredWidth / ctx.dp)) } ?: 0
        val ty = t?.second?.let { px(it.px(surface.measuredHeight / ctx.dp)) } ?: 0
        surface.translationX = tx.toFloat()
        surface.translationY = ty.toFloat()
        // The lock stays where HA draws it, over the card's own box (button-card's `#overlay` is the card's sibling,
        // not moved by its transform); `run` checks the lock for any touch that reaches the moved card.
    }

    private fun translateOf(v: String): Pair<ButtonCardCss.Length, ButtonCardCss.Length>? {
        val m = Regex("""^translate(x|y)?\(([^)]*)\)$""", RegexOption.IGNORE_CASE).matchEntire(v.trim()) ?: return null
        val parts = m.groupValues[2].split(',').map { ButtonCardCss.length(it.trim()) ?: return null }
        val zero = ButtonCardCss.Length(0f, "px")
        return when (m.groupValues[1].lowercase(Locale.ROOT)) {
            "x" -> parts[0] to zero
            "y" -> zero to parts[0]
            else -> parts[0] to (parts.getOrNull(1) ?: zero)
        }
    }

    /** button-card's own keyframes on [v]: `blink` (opacity 1, 0, 1) or `rotating` (a full turn), repeated. */
    private fun animate(v: View, value: String?, running: ValueAnimator?): ValueAnimator? {
        val spec = value?.let { ButtonCardCss.animation(it.trim().lowercase(Locale.ROOT)) }
        val key = spec?.let { "${it.first}/${it.second}" }
        if (running != null && animKeys[running] == key) return running
        running?.cancel()
        running?.let { animKeys.remove(it) }
        v.rotation = 0f
        if (spec == null || spec.second <= 0L) return null
        val anim = when (spec.first) {
            "rotating" -> ObjectAnimator.ofFloat(v, View.ROTATION, 0f, 360f)
            else -> ObjectAnimator.ofFloat(v, View.ALPHA, v.alpha, 0f, v.alpha)
        }.apply {
            duration = spec.second
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
        }
        animKeys[anim] = key
        // Only while it still owns its slot: a later render may have replaced it before this runs.
        post { if (isAttachedToWindow && (anim === cardAnimator || anim === iconAnimator)) anim.start() }
        return anim
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cardAnimator?.cancel()
        iconAnimator?.cancel()
        removeCallbacks(tick)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        cardAnimator?.takeIf { !it.isStarted }?.start()
        iconAnimator?.takeIf { !it.isStarted }?.start()
        if (raw.optBoolean("show_last_changed", false)) postDelayed(tick, 60_000L)
    }

    /** `ha-relative-time` refreshes once a minute. */
    private val tick: Runnable = object : Runnable {
        override fun run() {
            if (rendered) render(entityId?.let { ctx.store[it] })
            postDelayed(this, 60_000L)
        }
    }

    // -------------------------------------------------------------------------------------------------------------
    // The card (`ha-card.button-card-main`)
    // -------------------------------------------------------------------------------------------------------------

    private inner class Surface : CardSurface(ctx) {
        private var ownCss = ""
        private var cardModCss = ""
        private var applying = false
        private var pad = IntArray(4)

        fun follow(entityId: String, onState: (EntityState?) -> Unit) = observe(entityId, onState)

        fun load(url: String, done: (Bitmap?) -> Unit) = ctx.loadImage(url, this, done = done)

        fun setOwnCss(css: String) {
            if (css == ownCss && rendered && applying) return
            ownCss = css
            applyBoth()
        }

        private fun applyBoth() {
            applying = true
            try {
                applyStyleCss(listOf(cardModCss, ownCss).filter { it.isNotBlank() }.joinToString("\n"))
            } finally {
                applying = false
            }
            // Padding is the card's own (percentages of its width), set on every measure.
            super.setPadding(pad[0], pad[1], pad[2], pad[3])
        }

        override fun onCardModApplied(mod: dev.glassworks.engine.vitro.theme.CardModStyle) {
            if (applying) return
            cardModCss = activeCardCss.orEmpty()
            applyBoth()
        }

        /** The card's CSS padding (top, right, bottom, left), px. */
        fun cssPadding(p: List<Int>) {
            val next = intArrayOf(p[3], p[0], p[1], p[2])
            if (next.contentEquals(pad)) return
            pad = next
            setPadding(pad[0], pad[1], pad[2], pad[3])
        }

        // The grid fills the card's content box; with a definite height it centers in it (ha-card is a flex column).
        override fun measureCard(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val inner = (w - paddingLeft - paddingRight).coerceAtLeast(0)
            val exact = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY
            if (childCount == 0 || getChildAt(0).visibility == GONE) {
                setMeasuredDimension(w, if (exact) MeasureSpec.getSize(heightMeasureSpec) else paddingTop + paddingBottom)
                return
            }
            val child = getChildAt(0)
            if (exact) {
                val h = MeasureSpec.getSize(heightMeasureSpec)
                child.measure(MeasureSpec.makeMeasureSpec(inner, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec((h - paddingTop - paddingBottom).coerceAtLeast(0), MeasureSpec.EXACTLY))
                setMeasuredDimension(w, h)
            } else {
                child.measure(MeasureSpec.makeMeasureSpec(inner, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                setMeasuredDimension(w, child.measuredHeight + paddingTop + paddingBottom)
            }
        }

        override fun layoutCard(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            val child = getChildAt(0) ?: return
            child.layout(paddingLeft, paddingTop, paddingLeft + child.measuredWidth, paddingTop + child.measuredHeight)
        }

        override fun clearDefaultInnerPadding() = Unit
    }

    // -------------------------------------------------------------------------------------------------------------
    // The icon cell (`#img-cell`)
    // -------------------------------------------------------------------------------------------------------------

    /**
     * `#img-cell`: a flex box that centers the icon (or the entity picture). The icon is [iconWidth] wide (button-card's
     * `size`, a share of the cell) and as tall as the cell; its glyph is the smaller side. While the row sizes to its
     * content, the cell's content is a square icon (or the picture at its aspect).
     */
    private inner class ImgCell(context: Context) : FrameLayout(context), ButtonGridLayout.NaturalHeight {
        var iconWidth: ButtonCardCss.Length? = null
        var iconHeight: ButtonCardCss.Length? = null
        var fixedCard = false
        var showingPicture = false
        var pictureAspect: Float? = null
        /** `styles.entity_picture` padding: inside the picture's box, in % of the cell's width. */
        var picturePadding: List<ButtonCardCss.Length> = ZERO_BOX
        private var justify = "center"
        private var alignItems = "center"
        private var padCss = ZERO_BOX

        fun applyBox(decls: Map<String, ButtonCardCss.Decl>, vars: Map<String, String>) {
            justify = decls["justify-content"]?.value?.trim() ?: "center"
            alignItems = decls["align-items"]?.value?.trim() ?: "center"
            val bg = decls["background-color"]?.let { resolveColor(it.value, vars) }
            val radius = decls["border-radius"]?.value
            background = if (bg != null) GradientDrawable().apply {
                setColor(bg)
                // `50%` of a square cell is a circle; Android draws the largest radius as a pill.
                cornerRadius = radius?.let { r -> if (r.trim().endsWith("%")) 10_000f else ButtonCardCss.length(r)?.px(0f)?.let { it * ctx.dp } } ?: 0f
            } else null
            clipToOutline = background != null
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            alpha = decls["opacity"]?.let { opacity(it.value) } ?: 1f
            padCss = sides(decls["padding"]?.let { ButtonCardCss.box(it.value) } ?: ZERO_BOX, decls, "padding", vars)
        }

        private fun iconBoxWidth(cellW: Int): Int {
            val inner = (cellW - padPx(1, cellW) - padPx(3, cellW)).coerceAtLeast(0)
            val l = iconWidth ?: return inner
            if (l.isAuto) return inner
            return px(l.px(inner / ctx.dp, BASE_FONT_PX))
        }

        private fun padPx(i: Int, cellW: Int) = px(padCss[i].px(cellW / ctx.dp))

        /** The cell's content height at [cellW]: the square icon, a fixed icon height, or the picture at its aspect. */
        override fun naturalHeight(width: Int): Int {
            val cellW = width
            val iw = iconBoxWidth(cellW)
            val h = when {
                showingPicture -> (iw * (pictureAspect ?: 1f)).roundToInt()
                iconHeight != null -> px(iconHeight!!.px(0f, BASE_FONT_PX))
                // A card of fixed height puts the icon out of the flow (`position: absolute`): it sizes nothing.
                fixedCard -> 0
                else -> iw
            }
            return h + padPx(0, cellW) + padPx(2, cellW)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val h = MeasureSpec.getSize(heightMeasureSpec)
            val innerH = (h - padPx(0, w) - padPx(2, w)).coerceAtLeast(0)
            val iw = iconBoxWidth(w)
            val ih = when {
                showingPicture -> min((iw * (pictureAspect ?: 1f)).roundToInt(), innerH.takeIf { !fixedCard } ?: Int.MAX_VALUE)
                iconHeight != null -> px(iconHeight!!.px(innerH / ctx.dp, BASE_FONT_PX))
                else -> innerH
            }
            if (showingPicture) {
                val p = picturePadding.map { px(it.px(w / ctx.dp)) }
                picture.setPadding(p[3], p[0], p[1], p[2])
            }
            for (i in 0 until childCount) {
                getChildAt(i).measure(MeasureSpec.makeMeasureSpec(iw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ih.coerceAtLeast(0), MeasureSpec.EXACTLY))
            }
            setMeasuredDimension(w, h)
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            val w = right - left
            val h = bottom - top
            val pl = padPx(3, w); val pr = padPx(1, w); val pt = padPx(0, w); val pb = padPx(2, w)
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                if (c.visibility == GONE) continue
                val x = when (justify) {
                    "flex-start", "start", "left" -> pl
                    "flex-end", "end", "right" -> w - pr - c.measuredWidth
                    else -> pl + (w - pl - pr - c.measuredWidth) / 2
                }
                val y = when (alignItems) {
                    "flex-start", "start" -> pt
                    "flex-end", "end" -> h - pb - c.measuredHeight
                    else -> pt + (h - pt - pb - c.measuredHeight) / 2
                }
                c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------
    // The lock (`#overlay`, `#lock`)
    // -------------------------------------------------------------------------------------------------------------

    private inner class LockOverlay : FrameLayout(ctx.context) {
        private val lock = raw.optJSONObject("lock") ?: JSONObject()
        private val lockIcon = lock.optString("lock_icon", "mdi:lock-outline").ifEmpty { "mdi:lock-outline" }
        private val unlockIcon = lock.optString("unlock_icon", "mdi:lock-open-outline").ifEmpty { "mdi:lock-open-outline" }
        private val duration = (lock.opt("duration") as? Number)?.toLong() ?: 5L
        private val unlock = lock.optString("unlock", "tap").ifEmpty { "tap" }
        private val keepIcon = lock.optBoolean("keep_unlock_icon", false)
        private val iconView = MdiIconView(ctx.context)
        private var locked = true
        val isLocked: Boolean get() = locked
        private val relock = Runnable { setLocked(true) }

        init {
            addView(iconView, LayoutParams(px(24f), px(24f), Gravity.TOP or Gravity.END))
            alpha = 0.5f
            iconView.setIcon(lockIcon, ctx.theme.primaryTextColor)
            val detector = GestureDetector(ctx.context, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent) = true
                override fun onSingleTapUp(e: MotionEvent): Boolean { if (unlock == "tap") tryUnlock(); return true }
                override fun onDoubleTap(e: MotionEvent): Boolean { if (unlock == "double_tap") tryUnlock(); return true }
                override fun onLongPress(e: MotionEvent) { if (unlock == "hold") tryUnlock() }
            })
            @Suppress("ClickableViewAccessibility")
            setOnTouchListener { _, ev -> if (locked) detector.onTouchEvent(ev) || true else false }
            // Without touch (a keyboard, TalkBack), activating the locked card unlocks it: the overlay is a button
            // that says it's locked and offers "Unlock". Touch keeps the configured gesture.
            setOnClickListener { if (locked) tryUnlock() }
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = android.widget.Button::class.java.name
                    if (locked) {
                        info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(
                            android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, UNLOCK_LABEL))
                    }
                }
            }
            accessibilityState()
        }

        /** Locked: a focusable, clickable "Locked" button over the card. Unlocked: out of the way of the card. */
        private fun accessibilityState() {
            contentDescription = if (locked) LOCKED_LABEL else null
            isClickable = locked
            isFocusable = locked
            importantForAccessibility = if (locked) IMPORTANT_FOR_ACCESSIBILITY_YES else IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        fun style(decls: Map<String, ButtonCardCss.Decl>, fontColor: Int?, vars: Map<String, String>) {
            val color = decls["color"]?.let { resolveColor(it.value, vars) } ?: fontColor ?: ctx.theme.primaryTextColor
            iconView.tintColor = color
            alpha = decls["opacity"]?.let { opacity(it.value) } ?: 0.5f
            val pad = sides(decls["padding"]?.let { ButtonCardCss.box(it.value) } ?: LOCK_PADDING, decls, "padding", vars)
            setPadding(px(pad[3].px(0f)), px(pad[0].px(0f)), px(pad[1].px(0f)), px(pad[2].px(0f)))
            val h = when (decls["justify-content"]?.value?.trim()) {
                "flex-start", "start", "left" -> Gravity.START
                "center" -> Gravity.CENTER_HORIZONTAL
                else -> Gravity.END
            }
            val v = when (decls["align-items"]?.value?.trim()) {
                "flex-end", "end" -> Gravity.BOTTOM
                "center" -> Gravity.CENTER_VERTICAL
                else -> Gravity.TOP
            }
            (iconView.layoutParams as LayoutParams).gravity = h or v
        }

        private fun tryUnlock() {
            val exemptions = lock.optJSONArray("exemptions")
            if (exemptions != null) {
                val user = ctx.haUser()
                val id = user.optString("id"); val name = user.optString("name")
                if (id.isEmpty() || name.isEmpty()) return
                val ok = (0 until exemptions.length()).any { i ->
                    val e = exemptions.optJSONObject(i) ?: return@any false
                    e.optString("user") == id || e.optString("username") == name
                }
                if (!ok) {
                    ObjectAnimator.ofFloat(iconView, View.ALPHA, 1f, 0f, 1f).apply { duration = 1000; repeatCount = 2 }.start()
                    return
                }
            }
            setLocked(false)
            removeCallbacks(relock)
            postDelayed(relock, duration * 1000L)
        }

        private fun setLocked(on: Boolean) {
            val changed = locked != on
            locked = on
            accessibilityState()
            if (changed) announceForAccessibility(if (on) LOCKED_LABEL else UNLOCKED_LABEL)
            iconView.setIcon(if (on) lockIcon else unlockIcon, iconView.tintColor)
            iconView.animate().cancel()
            if (on) iconView.alpha = 1f
            else if (!keepIcon) iconView.animate().alpha(0f).setStartDelay(0).setDuration(1000).start()
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            removeCallbacks(relock)
            setLocked(true)
        }
    }

    /** The typography a text part has: the card's, then its own declarations. */
    private data class TextStyle(
        val color: Int,
        val sizePx: Float,
        val weight: Int,
        val italic: Boolean,
        val family: String?,
        val transform: String?,
        val letterSpacing: String?,
        val lineHeight: String?,
    )

    private fun TextStyle.with(decls: Map<String, ButtonCardCss.Decl>, vars: Map<String, String>): TextStyle = copy(
        color = decls["color"]?.let { resolveColor(it.value, vars, color) } ?: color,
        sizePx = decls["font-size"]?.let { fontSize(it.value, sizePx, vars) } ?: sizePx,
        weight = decls["font-weight"]?.let { weight(it.value, weight) } ?: weight,
        italic = decls["font-style"]?.value?.trim()?.let { it == "italic" || it == "oblique" } ?: italic,
        family = decls["font-family"]?.value ?: family,
        transform = decls["text-transform"]?.value?.trim() ?: transform,
        letterSpacing = decls["letter-spacing"]?.value ?: letterSpacing,
        lineHeight = decls["line-height"]?.value ?: lineHeight,
    )

    private fun fontSize(v: String, parentPx: Float, vars: Map<String, String>): Float? {
        val s = ThemeResolver.resolveValue(v, vars, 0).trim().lowercase(Locale.ROOT)
        FONT_KEYWORDS[s]?.let { return it }
        if (s == "smaller") return parentPx / 1.2f
        if (s == "larger") return parentPx * 1.2f
        return ButtonCardCss.length(s)?.px(parentPx, parentPx)
    }

    private fun weight(v: String, parent: Int): Int? = when (val s = v.trim().lowercase(Locale.ROOT)) {
        "normal" -> 400
        "bold" -> 700
        "bolder" -> if (parent < 400) 400 else if (parent < 600) 700 else 900
        "lighter" -> if (parent < 600) 100 else if (parent < 800) 400 else 700
        else -> s.toIntOrNull()
    }

    private fun opacity(v: String): Float? {
        val s = v.trim()
        return (if (s.endsWith("%")) s.removeSuffix("%").toFloatOrNull()?.div(100f) else s.toFloatOrNull())?.coerceIn(0f, 1f)
    }

    private fun transform(text: String, t: String?): String = when (t) {
        "uppercase" -> text.uppercase(Locale.getDefault())
        "lowercase" -> text.lowercase(Locale.getDefault())
        "capitalize" -> text.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(Locale.getDefault()) } }
        else -> text
    }

    private fun TextView.setLineHeightCompat(px: Int) {
        val fm = paint.fontMetricsInt
        val natural = fm.descent - fm.ascent
        if (px != natural) setLineSpacing((px - natural).toFloat(), 1f)
    }

    companion object {
        /** `ha-card.button-card-main { font-size: 1.2rem }`. */
        const val BASE_FONT_PX = 1.2f * ButtonCardCss.ROOT_FONT_PX
        private val DEFAULT_PADDING = listOf(
            ButtonCardCss.Length(4f, "%"), ButtonCardCss.Length(0f, "px"), ButtonCardCss.Length(4f, "%"), ButtonCardCss.Length(0f, "px"),
        )
        private val ZERO_BOX = List(4) { ButtonCardCss.Length(0f, "px") }
        private const val LOCKED_LABEL = "Locked"
        private const val UNLOCKED_LABEL = "Unlocked"
        private const val UNLOCK_LABEL = "Unlock"

        /** How often a picture that failed to load is asked for again (at the next renders). */
        private const val MAX_PICTURE_RETRIES = 3
        private val LOCK_PADDING = listOf(
            ButtonCardCss.Length(8f, "px"), ButtonCardCss.Length(7f, "px"), ButtonCardCss.Length(8f, "px"), ButtonCardCss.Length(7f, "px"),
        )
        private val AUTO = setOf("auto", "auto-no-temperature")
        private const val DEFAULT_COLOR = "var(--primary-text-color)"
        private val TOGGLE_DOMAINS = setOf("fan", "input_boolean", "light", "switch", "group", "automation", "humidifier")
        private val FONT_KEYWORDS = mapOf(
            "xx-small" to 9f, "x-small" to 10f, "small" to 13f, "medium" to 16f, "large" to 18f, "x-large" to 24f, "xx-large" to 32f,
        )

        /** The theme's `ha-card` variables a card can set on itself, and the property each one feeds. */
        private val CARD_VAR_PROPS = listOf(
            "ha-card-background" to "background-color",
            "ha-card-border-radius" to "border-radius",
            "ha-card-border-color" to "border-color",
            "ha-card-border-width" to "border-width",
            "ha-card-box-shadow" to "box-shadow",
        )

        private fun truthy(v: Any?): Boolean = when (v) {
            null, JSONObject.NULL -> false
            is Boolean -> v
            is Number -> v.toDouble() != 0.0
            is String -> v.isNotEmpty()
            else -> true
        }

        /** A color as CSS writes it, for the card surface's parser. */
        fun cssColor(c: Int): String = "rgba(${Color.red(c)}, ${Color.green(c)}, ${Color.blue(c)}, ${"%.4f".format(Locale.ROOT, Color.alpha(c) / 255f)})"
    }
}

/** `computeStateDisplay` as button-card v7 has it: [cfg]'s `units`, `show_units` and `numeric_precision`. */
internal fun stateDisplay(s: EntityState, cfg: JSONObject): String {
    if (s.state == "unknown" || s.state == "unavailable") return s.state.replaceFirstChar { it.titlecase(Locale.ROOT) }
    val numeric = s.unit != null || s.attributes["state_class"] != null
    if (numeric) {
        val precision = (cfg.opt("numeric_precision") as? Number)?.toInt() ?: HaDisplay.displayPrecision(s.entityId)
        val showUnits = cfg.optBoolean("show_units", true)
        val units = (cfg.opt("units") as? String)?.takeIf { it.isNotEmpty() }
        if (s.deviceClass == "monetary" && s.state.trim().toDoubleOrNull() != null) {
            runCatching {
                val currency = java.util.Currency.getInstance(units ?: s.unit)
                val f = java.text.NumberFormat.getCurrencyInstance(Locale.US)
                f.currency = currency
                f.minimumFractionDigits = precision ?: 2
                f.maximumFractionDigits = precision ?: 2
                return f.format(s.state.trim().toDouble())
            }
        }
        if (s.state.trim().toBigDecimalOrNull() != null) {
            val n = HaDisplay.formatState(s.state, precision, (s.attributes["step"] as? Number)?.toDouble())
            val unit = if (!showUnits) null else units ?: s.unit
            return when {
                unit.isNullOrEmpty() -> n
                unit == "%" -> "$n%"
                else -> "$n $unit"
            }
        }
    }
    return s.plainState()
}

/** button-card's state colors: `stateActive`, `stateColorCss` and the light color helpers (TinyColor's math). */
internal object ButtonCardColors {
    private val STATE_COLORED = setOf(
        "alarm_control_panel", "alert", "automation", "binary_sensor", "calendar", "camera", "climate", "cover",
        "device_tracker", "fan", "group", "humidifier", "input_boolean", "light", "lock", "media_player", "person",
        "plant", "remote", "schedule", "script", "siren", "sun", "switch", "timer", "update", "vacuum",
    )

    /** button-card's `stateActive` (HA's, as button-card ships it). */
    fun active(s: EntityState, state: String = s.state): Boolean {
        val domain = s.domain
        if (domain in setOf("button", "event", "input_button", "scene")) return state != "unavailable"
        if (state == "unavailable" || state == "unknown") return false
        if (state == "off" && domain != "alert") return false
        return when (domain) {
            "alarm_control_panel" -> state != "disarmed"
            "alert" -> state != "idle"
            "cover" -> state != "closed"
            "device_tracker", "person" -> state != "not_home"
            "lock" -> state != "locked"
            "media_player" -> state != "standby"
            "vacuum" -> state !in setOf("idle", "docked", "paused")
            "plant" -> state == "problem"
            "group" -> state in setOf("on", "home", "open", "locked", "problem")
            "timer" -> state == "active"
            "camera" -> state == "streaming"
            else -> true
        }
    }

    /** `stateColorCss`: a `var()` chain for the state's color, or null for a domain HA doesn't color. */
    fun stateColorCss(s: EntityState, state: String, colorType: String?): String? {
        if (state == "unavailable") return "var(--state-unavailable-color)"
        val props = properties(s, state, colorType) ?: return null
        return props.reversed().fold(null as String?) { acc, v -> "var($v${if (acc != null) ", $acc" else ""})" }
    }

    private val OVERRIDE = listOf("--ha-card-background", "--card-background-color")

    private fun properties(s: EntityState, state: String, colorType: String?): List<String>? {
        val dc = s.deviceClass
        if (s.domain == "sensor" && dc == "battery") {
            val v = state.toDoubleOrNull()
            if (v != null) return listOf(when { v >= 70 -> "--state-sensor-battery-high-color"; v >= 30 -> "--state-sensor-battery-medium-color"; else -> "--state-sensor-battery-low-color" })
        }
        if (s.domain == "group") {
            val ids = (s.attributes["entity_id"] as? List<*>)?.map { it.toString().substringBefore('.') }?.toSet()
            val g = ids?.singleOrNull()
            if (g != null && g in STATE_COLORED) return domainProps(g, s, state, colorType)
        }
        if (s.domain in STATE_COLORED) return domainProps(s.domain, s, state, colorType)
        if (colorType == "card" || colorType == "label-card") return OVERRIDE
        return null
    }

    private fun domainProps(domain: String, s: EntityState, state: String, colorType: String?): List<String> {
        val activeKey = if (active(s, state)) "active" else "inactive"
        if ((colorType == "card" || colorType == "label-card") && activeKey == "inactive") return OVERRIDE
        val key = slugify(state)
        val out = ArrayList<String>()
        s.deviceClass?.let { out += "--state-$domain-$it-$key-color" }
        out += "--state-$domain-$key-color"
        out += "--state-$domain-$activeKey-color"
        out += "--state-$activeKey-color"
        return out
    }

    /** button-card's `slugify(value, '_')` for the state words HA uses. */
    fun slugify(v: String): String = v.lowercase(Locale.ROOT).replace(Regex("""\s+"""), "_").replace("&", "_and_")
        .replace(Regex("""[^\w-]+"""), "").replace('-', '_').replace(Regex("""(_)\1+"""), "_").trim('_')

    /** `getLightColorBasedOnTemperature`: blue-ish to white to orange-ish across the light's mireds. */
    fun temperatureColor(current: Float, min: Float, max: Float): String {
        val mix = (current - min) / (max - min) * 100f
        val low = Color.rgb(166, 209, 255)
        val white = Color.WHITE
        val high = Color.rgb(255, 160, 0)
        val c = if (mix < 50) mixColors(low, white, mix * 2) else mixColors(white, high, (mix - 50) * 2)
        return "rgb(${Color.red(c)}, ${Color.green(c)}, ${Color.blue(c)})"
    }

    /** TinyColor's `mix(color2, amount)`: [amount] percent of the way from [a] to [b]. */
    fun mixColors(a: Int, b: Int, amount: Float): Int {
        val p = amount / 100f
        fun ch(x: Int, y: Int) = ((y - x) * p + x).roundToInt().coerceIn(0, 255)
        val alpha = ((Color.alpha(b) - Color.alpha(a)) * p + Color.alpha(a)).roundToInt().coerceIn(0, 255)
        return Color.argb(alpha, ch(Color.red(a), Color.red(b)), ch(Color.green(a), Color.green(b)), ch(Color.blue(a), Color.blue(b)))
    }

    /** `applyBrightnessToColor`'s `mix('black', amount)`. */
    fun mixBlack(c: Int, amount: Float): Int = mixColors(c, Color.BLACK, amount)

    /** TinyColor's `getLuminance` (WCAG relative luminance, alpha ignored). */
    fun luminance(c: Int): Double {
        fun ch(v: Int): Double { val s = v / 255.0; return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * ch(Color.red(c)) + 0.7152 * ch(Color.green(c)) + 0.0722 * ch(Color.blue(c))
    }

    /** HA's frontend globals the state colors name (2026.11's `color.globals`), under any theme. */
    val HA_GLOBALS: Map<String, String> = linkedMapOf(
        "red-color" to "#f44336", "pink-color" to "#e91e63", "purple-color" to "#926bc7", "deep-purple-color" to "#6e41ab",
        "indigo-color" to "#3f51b5", "blue-color" to "#2196f3", "light-blue-color" to "#03a9f4", "cyan-color" to "#00bcd4",
        "teal-color" to "#009688", "green-color" to "#4caf50", "light-green-color" to "#8bc34a", "lime-color" to "#cddc39",
        "yellow-color" to "#ffeb3b", "amber-color" to "#ffc107", "orange-color" to "#ff9800", "deep-orange-color" to "#ff6f22",
        "brown-color" to "#795548", "light-grey-color" to "#bdbdbd", "grey-color" to "#9e9e9e", "dark-grey-color" to "#606060",
        "blue-grey-color" to "#607d8b", "black-color" to "#000000", "white-color" to "#ffffff",
        "state-active-color" to "var(--amber-color)", "state-inactive-color" to "var(--grey-color)",
        "state-icon-color" to "#44739e",
        "state-unavailable-color" to "var(--state-icon-unavailable-color, var(--disabled-text-color))",
        "state-alarm_control_panel-armed_away-color" to "var(--green-color)",
        "state-alarm_control_panel-armed_custom_bypass-color" to "var(--green-color)",
        "state-alarm_control_panel-armed_home-color" to "var(--green-color)",
        "state-alarm_control_panel-armed_night-color" to "var(--green-color)",
        "state-alarm_control_panel-armed_vacation-color" to "var(--green-color)",
        "state-alarm_control_panel-arming-color" to "var(--orange-color)",
        "state-alarm_control_panel-disarming-color" to "var(--orange-color)",
        "state-alarm_control_panel-pending-color" to "var(--orange-color)",
        "state-alarm_control_panel-triggered-color" to "var(--red-color)",
        "state-alert-off-color" to "var(--orange-color)", "state-alert-on-color" to "var(--red-color)",
        "state-binary_sensor-active-color" to "var(--amber-color)",
        "state-binary_sensor-battery-on-color" to "var(--red-color)",
        "state-binary_sensor-carbon_monoxide-on-color" to "var(--red-color)",
        "state-binary_sensor-gas-on-color" to "var(--red-color)",
        "state-binary_sensor-glass_break-on-color" to "var(--red-color)",
        "state-binary_sensor-heat-on-color" to "var(--red-color)",
        "state-binary_sensor-lock-on-color" to "var(--red-color)",
        "state-binary_sensor-moisture-on-color" to "var(--red-color)",
        "state-binary_sensor-problem-on-color" to "var(--red-color)",
        "state-binary_sensor-safety-on-color" to "var(--red-color)",
        "state-binary_sensor-smoke-on-color" to "var(--red-color)",
        "state-binary_sensor-sound-on-color" to "var(--red-color)",
        "state-binary_sensor-tamper-on-color" to "var(--red-color)",
        "state-climate-auto-color" to "var(--green-color)", "state-climate-cool-color" to "var(--blue-color)",
        "state-climate-dry-color" to "var(--orange-color)", "state-climate-fan_only-color" to "var(--cyan-color)",
        "state-climate-heat-color" to "var(--deep-orange-color)", "state-climate-heat-cool-color" to "var(--amber-color)",
        "state-cover-active-color" to "var(--purple-color)",
        "state-device_tracker-active-color" to "var(--blue-color)", "state-device_tracker-home-color" to "var(--green-color)",
        "state-fan-active-color" to "var(--cyan-color)", "state-humidifier-on-color" to "var(--blue-color)",
        "state-lawn_mower-active-color" to "var(--teal-color)", "state-lawn_mower-error-color" to "var(--red-color)",
        "state-light-active-color" to "var(--amber-color)",
        "state-lock-jammed-color" to "var(--red-color)", "state-lock-locked-color" to "var(--green-color)",
        "state-lock-locking-color" to "var(--orange-color)", "state-lock-open-color" to "var(--red-color)",
        "state-lock-opening-color" to "var(--orange-color)", "state-lock-unlocked-color" to "var(--red-color)",
        "state-lock-unlocking-color" to "var(--orange-color)",
        "state-media_player-active-color" to "var(--light-blue-color)",
        "state-person-active-color" to "var(--blue-color)", "state-person-home-color" to "var(--green-color)",
        "state-plant-active-color" to "var(--red-color)",
        "state-sensor-battery-high-color" to "var(--green-color)", "state-sensor-battery-low-color" to "var(--red-color)",
        "state-sensor-battery-medium-color" to "var(--orange-color)",
        "state-siren-active-color" to "var(--red-color)",
        "state-sun-above_horizon-color" to "var(--amber-color)", "state-sun-below_horizon-color" to "var(--indigo-color)",
        "state-switch-active-color" to "var(--amber-color)", "state-update-active-color" to "var(--orange-color)",
        "state-vacuum-active-color" to "var(--teal-color)", "state-vacuum-error-color" to "var(--red-color)",
        "state-valve-active-color" to "var(--blue-color)",
    )
}
