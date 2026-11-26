/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.cards

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.HaFormat
import dev.glassworks.engine.vitro.data.HaIntl
import dev.glassworks.engine.vitro.data.HaLocale
import dev.glassworks.engine.vitro.data.HaText
import dev.glassworks.engine.vitro.model.ActionConfig
import dev.glassworks.engine.vitro.model.CardConfig
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.A11yRole
import dev.glassworks.engine.vitro.ui.CardSurface
import dev.glassworks.engine.vitro.ui.MdiIconView
import dev.glassworks.engine.vitro.ui.MdiIcons
import dev.glassworks.engine.vitro.ui.VitroAccess
import dev.glassworks.engine.vitro.ui.VitroCardContext
import dev.glassworks.engine.vitro.ui.VitroFonts
import dev.glassworks.engine.vitro.ui.bindHoldAction
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Bubble Card 3.4.3 (Clooos/Bubble-Card, MIT), the configs Vitro draws as Bubble does (`compat/native-options.json`):
 * the `separator` and the `button` card types `switch`, `state` and `name`, without sub-buttons, styles or a slider.
 * Every size, color and rule here is Bubble's own, from its source at v3.4.3: `components/base-card` (structure,
 * `styles.css`, `changes.js`), `cards/button` and `cards/separator`, `tools/icon.js` (`getIconColor`), `tools/utils.js`
 * (`isStateOn`, `getStateSurfaceColor`, `setLayout`, `formatDateTime`), `tools/state-content.js` and
 * `components/base-card/state-line.js` (the state line), `tools/style.js` (`createBubbleDefaultColor`) and
 * `tools/text-scrolling.js` (the marquee). Bubble's modules (global restyles) are ruled out before a card gets here
 * ([dev.glassworks.engine.vitro.data.BubbleModules]).
 */
class BubbleCardView(ctx: VitroCardContext, private val config: CardConfig) : CardSurface(ctx) {
    override val contentInsideBorder: Boolean get() = false
    private val raw = config.raw
    private val palette = BubbleColors(ctx)

    /** `setLayout`: `card_layout`, else `large` in every view but a masonry one (and outside a view, `normal`). */
    private val large: Boolean = when (raw.optString("card_layout")) {
        "large" -> true
        "normal" -> false
        else -> ctx.viewType != null && ctx.viewType != "masonry"
    }

    /** `--row-height`: a sections view's grid sets it (56 px); elsewhere each stylesheet's own fallback applies. */
    private val rowHeightVar: Float? = if (ctx.viewType == "sections") 56f else null

    /**
     * `applyRowSize`: the card's `rows` or `grid_options.rows` as `--row-size` (a number; `auto` leaves it). The compat
     * rule admits only sizes that leave the card its content ([BUBBLE_MIN_ROWS]); anything else (`NaN`, 0, a negative
     * or tiny size) keeps the default rather than lay out a card with no height.
     */
    private fun rowSize(default: Float, min: Float): Float {
        val v = raw.opt("rows").takeUnless { it == null || it == JSONObject.NULL } ?: raw.optJSONObject("grid_options")?.opt("rows")
        val n = (v as? Number)?.toFloat() ?: (v as? String)?.toFloatOrNull() ?: return default
        return if (n.isFinite() && n >= min) n else default
    }

    private val buttonType: String = raw.optString("button_type").ifEmpty { if (config.entity != null) "switch" else "name" }
    private val showName = raw.optBoolean("show_name", true)
    private val showIcon = raw.optBoolean("show_icon", true)

    /** The icon's tap target and the background's (`bubble-action`), null when that part has no action. */
    internal var iconTarget: View? = null
        private set
    internal var buttonTarget: View? = null
        private set

    /** The entity picture shown now (loaded), the one being fetched, and the last one that failed and when. */
    private var pictureShown: String? = null
    private var pictureLoading: String? = null
    private var pictureFailed: String? = null
    private var pictureFailedAt = 0L

    /** The button's icon and picture views (null for a separator). */
    private var iconView: View? = null
    private var picture: ImageView? = null

    // Declared after every property it reads: Kotlin runs initializers in order.
    init {
        // `ha-card { background: none }`: Bubble draws its own container.
        dropSurface()
        if (raw.optString("card_type", "button") == "separator") buildSeparator() else buildButton()
    }

    private fun dpx(v: Float): Int = (v * ctx.dp).roundToInt()

    // ---- separator (cards/separator/styles.css)

    private fun buildSeparator() {
        // `.bubble-container { height: 40px }`; `.large`: `calc(var(--row-height,44px) * var(--row-size,0.8) +
        // var(--row-gap,8px) * (var(--row-size,0.8) - 1))`.
        val height = if (large) {
            val size = rowSize(0.8f, BUBBLE_MIN_ROWS_SEPARATOR)
            (rowHeightVar ?: 44f) * size + 8f * (size - 1f)
        } else {
            40f
        }
        val row = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val iconName = raw.optString("icon").trim()
        val icon = MdiIconView(ctx.context).apply { setIcon(iconName.ifEmpty { "mdi:blank" }, ctx.theme.primaryTextColor) }
        // No icon: `margin: 0px 8px; width: 0px`.
        if (iconName.isEmpty()) {
            row.addView(View(ctx.context), LinearLayout.LayoutParams(0, 0).apply { marginStart = dpx(8f); marginEnd = dpx(8f) })
        } else {
            row.addView(icon, LinearLayout.LayoutParams(dpx(24f), dpx(24f)).apply { marginStart = dpx(8f); marginEnd = dpx(22f) })
        }
        val name = raw.optString("name")
        if (name.isNotEmpty()) {
            // h4.bubble-name: 16 px, 600, on ha-card's 1.6 line, cut with an ellipsis; `:empty` hides it.
            val title = bubbleText(ctx, 16f, 600, ctx.theme.primaryTextColor).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                text = name
                lineBox(ctx, this, 16f, 1.6f)
            }
            row.addView(title, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dpx(30f) })
        }
        // `.bubble-line`: 6 px, 6 px corners, 60% opaque, to 14 px before the end.
        val line = View(ctx.context).apply {
            background = GradientDrawable().apply {
                cornerRadius = 6f * ctx.dp
                setColor(palette.lineColor())
            }
            alpha = 0.6f
        }
        row.addView(line, LinearLayout.LayoutParams(0, dpx(6f), 1f).apply { marginEnd = dpx(14f) })
        addView(row, LayoutParams(MATCH_PARENT, (height * ctx.dp).roundToInt()))
    }

    // ---- button (components/base-card, cards/button)

    private fun buildButton() {
        val eid = config.entity
        // `.bubble-container`: 50 px; `.large`: `calc(var(--row-height,56px) * var(--row-size,1) + var(--row-gap,8px) *
        // (var(--row-size,1) - 1))`. Its corners: `--bubble-button-border-radius`, `--bubble-border-radius`, else half of
        // `--row-height` (56 px unless a grid sets it).
        val height = if (large) {
            val size = rowSize(1f, BUBBLE_MIN_ROWS)
            (rowHeightVar ?: 56f) * size + 8f * (size - 1f)
        } else {
            50f
        }
        val radius = palette.buttonRadius(rowHeightVar ?: 56f) * ctx.dp
        val container = FrameLayout(ctx.context).apply {
            background = GradientDrawable().apply {
                cornerRadius = radius
                setColor(palette.mainBackground())
            }
            clipToOutline = true
        }
        // `.bubble-background`: the switch's on color, at the opacity `changeButton` gives it.
        val backgroundLayer = View(ctx.context)
        container.addView(backgroundLayer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val row = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // `.bubble-icon-container`: 38 px (42 px large, 8 px from the start), 6 px around, round, on the icon
        // background color.
        val iconSize = if (large) 42f else 38f
        val iconContainer = FrameLayout(ctx.context).apply {
            background = GradientDrawable().apply {
                val r = palette.iconRadius()
                if (r == null) shape = GradientDrawable.OVAL else cornerRadius = r * ctx.dp
                setColor(palette.iconBackground())
            }
            clipToOutline = true
        }
        val icon = MdiIconView(ctx.context)
        iconContainer.addView(icon, FrameLayout.LayoutParams(dpx(24f), dpx(24f), Gravity.CENTER))
        val picture = ImageView(ctx.context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = GONE
        }
        iconContainer.addView(picture, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        this.picture = picture
        iconView = icon
        if (showIcon) {
            row.addView(iconContainer, LinearLayout.LayoutParams(dpx(iconSize), dpx(iconSize)).apply {
                setMargins(dpx(if (large) 8f else 6f), dpx(6f), dpx(6f), dpx(6f))
            })
        }
        // `.bubble-name-container`: a column centered in the rest, 4 px after the icon (16 px without one) and 16 px
        // before the end. The name is 13 px / 600 and the state 12 px at 70%, each on an 18 px line.
        val nameColumn = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val scrolling = raw.optBoolean("scrolling_effect", true)
        val name = BubbleTextLine(ctx, 13f, 600, scrolling)
        val stateLine = BubbleTextLine(ctx, 12f, 400, scrolling).apply { alpha = 0.7f }
        nameColumn.addView(name, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        nameColumn.addView(stateLine, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        if (!showName) name.shown = false
        row.addView(nameColumn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
            marginStart = dpx(if (showIcon) 4f else 16f)
            marginEnd = dpx(16f)
        })
        container.addView(row, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(container, LayoutParams(MATCH_PARENT, (height * ctx.dp).roundToInt()))

        // Actions (`createStructure`): the icon runs the card's own tap/hold, the background `button_action`, each
        // with the button type's defaults.
        val iconDefaults = when (buttonType) {
            "state" -> Triple("more-info", "none", "none")
            "name" -> Triple("none", "none", "none")
            else -> Triple("more-info", "none", "none")
        }
        val buttonDefaults = when (buttonType) {
            "switch" -> Triple("toggle", "more-info", "none")
            "state" -> Triple("more-info", "more-info", "none")
            else -> Triple("none", "none", "none")
        }
        val iconTap = bubbleAction(raw.optJSONObject("tap_action"), iconDefaults.first, eid)
        val iconHold = bubbleAction(raw.optJSONObject("hold_action"), iconDefaults.second, eid)
        val buttonAction = raw.optJSONObject("button_action")
        val buttonTap = bubbleAction(buttonAction?.optJSONObject("tap_action"), buttonDefaults.first, eid)
        val buttonHold = bubbleAction(buttonAction?.optJSONObject("hold_action"), buttonDefaults.second, eid)
        // A target's name: the card's name, else its entity's, else what its tap does (an icon-only `name` button).
        fun label(tap: ActionConfig): () -> CharSequence? = {
            name.text?.toString()?.ifEmpty { null } ?: eid?.let { ctx.store[it]?.friendlyName } ?: eid ?: actionLabel(tap)
        }
        if (showIcon && wireActions(iconContainer, iconTap, iconHold, eid, oval = palette.iconRadius() == null, radius = (palette.iconRadius() ?: 0f) * ctx.dp, label(iconTap))) {
            iconTarget = iconContainer
        }
        if (wireActions(backgroundLayer, buttonTap, buttonHold, eid, oval = false, radius = radius, label(buttonTap))) buttonTarget = backgroundLayer

        val ticking: (EntityState, Long) -> String? = { s, now -> stateText(s, now)?.takeIf { hasTimestamps(s) } }
        val render: (EntityState?) -> Unit = { s ->
            val now = ctx.now()
            // Name (`changeName`): the configured name, else the entity's friendly_name attribute (not HA's
            // computed name); a `name` button shows the configured one alone.
            name.setLine(
                if (buttonType == "name") raw.optString("name")
                else raw.optString("name").ifEmpty { s?.attributes?.get("friendly_name") as? String ?: "" },
            )
            // State line (`changeState`).
            val content = stateContent(eid)
            if (content == null || content.isEmpty()) {
                stateLine.shown = false
            } else {
                stateLine.shown = true
                // `.state-without-name`: full opacity at 14 px.
                if (!showName) { stateLine.alpha = 1f; stateLine.setSize(14f) }
                val text = if (s == null) "" else stateText(s, now).orEmpty()
                stateLine.setLine(text)
                if (s != null && hasTimestamps(s)) stateLine.tickWith(ctx, s, ticking)
            }
            // Icon (`changeIcon`) and colors (`changeButton`).
            val on = s != null && palette.isStateOn(s)
            val noColor = buttonType == "name"
            val iconColor = if (on && !noColor) palette.iconColor(s!!, raw) else ctx.theme.primaryTextColor
            icon.setIcon(MdiIcons.resolveIconName(raw.optString("icon").ifEmpty { null }, s), iconColor)
            showPicture(s?.let { bubbleImage(it) })
            // `.is-off .bubble-main-icon { opacity: .6 }`: the class is set only for a known state without a degree unit.
            val classed = s != null && s.unit?.contains('°') != true
            icon.alpha = if (classed && !on) 0.6f else 1f
            if (buttonType != "name") {
                val (color, opacity) = palette.buttonBackground(buttonType, s, raw)
                backgroundLayer.background = GradientDrawable().apply {
                    cornerRadius = radius
                    setColor(color)
                }
                backgroundLayer.alpha = opacity
                // `.is-unavailable { opacity: .5 }` on the card.
                this@BubbleCardView.alpha = if (s?.state == "unavailable") 0.5f else 1f
            }
        }
        if (eid != null) observe(eid) { s -> render(s) } else render(null)
    }

    /** Gives [view] Bubble's tap and hold actions (`addActions`), when either does something; false when neither does. */
    /**
     * `changeIcon` with an entity picture: the picture once it has loaded, the icon until then. A picture that fails
     * keeps the icon and is asked for again on a later update, at most every [PICTURE_RETRY_MS].
     */
    private fun showPicture(url: String?) {
        if (url == null || url != pictureShown) {
            if (url != pictureShown) {
                pictureShown = null
                picture?.setImageDrawable(null)
            }
            picture?.visibility = GONE
            iconView?.visibility = VISIBLE
        }
        if (url == null) {
            pictureLoading = null
            return
        }
        if (url == pictureShown) {
            picture?.visibility = VISIBLE
            iconView?.visibility = GONE
            return
        }
        val now = android.os.SystemClock.uptimeMillis()
        if (url == pictureLoading || (url == pictureFailed && now - pictureFailedAt < PICTURE_RETRY_MS)) return
        pictureLoading = url
        val target = picture ?: return
        // Loaded when the card shows (the picture itself stays hidden until it has loaded).
        ctx.loadImage(url, this) { bmp ->
            if (pictureLoading != url) return@loadImage
            pictureLoading = null
            if (bmp == null) {
                pictureFailed = url
                pictureFailedAt = android.os.SystemClock.uptimeMillis()
                return@loadImage
            }
            pictureShown = url
            target.setImageBitmap(bmp)
            target.visibility = VISIBLE
            iconView?.visibility = GONE
        }
    }

    /** What a target's tap does, in HA's words (its action editor's), for a target with no other name. */
    private fun actionLabel(tap: ActionConfig): String? = when (tap.action) {
        "more-info" -> HaText.ui("ui.panel.lovelace.editor.action-editor.actions.more-info")
        "toggle" -> HaText.ui("ui.panel.lovelace.editor.action-editor.actions.toggle")
        "navigate" -> HaText.ui("ui.panel.lovelace.editor.action-editor.actions.navigate")
        "url" -> HaText.ui("ui.panel.lovelace.editor.action-editor.actions.url")
        "perform-action", "call-service" -> HaText.ui("ui.panel.lovelace.editor.action-editor.actions.perform-action")
        else -> null
    }?.ifEmpty { null }

    private fun wireActions(view: View, tap: ActionConfig, hold: ActionConfig, eid: String?, oval: Boolean, radius: Float, label: () -> CharSequence?): Boolean {
        val acts = tap.action != "none" || hold.action != "none"
        if (!acts) return false
        view.isClickable = true
        view.setOnClickListener { if (tap.action != "none") ctx.performAction(tap, eid) }
        if (hold.action != "none") view.bindHoldAction(ctx, hold, eid)
        applyHaRipple(view, if (oval) 999f else radius, ctx.theme.dark)
        VitroAccess.role(view, A11yRole.BUTTON, name = label)
        return true
    }

    /**
     * One of Bubble's actions (`sendActionEvent`): a service call whose `target.entity_id` is the word `entity` acts on
     * the card's entity.
     */
    private fun bubbleAction(raw: JSONObject?, default: String, eid: String?): ActionConfig {
        val action = ActionConfig.parse(raw, default)
        val target = action.target
        if (eid == null || action.service == null || target?.opt("entity_id") != "entity") return action
        return action.copy(target = JSONObject(target.toString()).put("entity_id", eid))
    }

    /** `getImage`: the entity's picture, unless the card names an icon or sets `force_icon`. */
    private fun bubbleImage(s: EntityState): String? {
        if (raw.optBoolean("force_icon", false) || raw.optString("icon").isNotEmpty()) return null
        return (s.attributes["entity_picture_local"] as? String)?.takeIf { it.isNotEmpty() } ?: s.entityPicture
    }

    /** `resolveStateContent(config, 'card')` without `state_content` (which isn't drawn natively). */
    private fun stateContent(eid: String?): List<String>? = BubbleStateLine.content(raw, buttonType, eid)

    private fun hasTimestamps(s: EntityState): Boolean =
        stateContent(s.entityId)?.any { it in BubbleStateLine.TIMESTAMP_CONTENTS } == true

    private fun stateText(s: EntityState, now: Long): String? {
        val content = stateContent(s.entityId) ?: return null
        return BubbleStateLine.render(s, content, now, ctx.haConfig())
    }

    private fun bubbleText(ctx: VitroCardContext, size: Float, weight: Int, color: Int): TextView =
        TextView(ctx.context).apply {
            val scaled = ctx.scaledSp(size)
            textSize = scaled
            setTextColor(color)
            typeface = VitroFonts.typeface(ctx.context, weight, scaled)
            includeFontPadding = false
        }
}

/** The smallest `rows` a large Bubble button keeps its content in: one grid row (56 px), its icon and text. */
internal const val BUBBLE_MIN_ROWS = 1f

/** The smallest `rows` of a large separator: Bubble's own default, 0.8. */
internal const val BUBBLE_MIN_ROWS_SEPARATOR = 0.8f

/** How long a Bubble picture that failed to load waits before an update asks for it again. */
private const val PICTURE_RETRY_MS = 30_000L

/**
 * Bubble's state line (`resolveStateContent` and `renderStateLine` with `capitalizeTimes`): the legacy keys
 * (`show_state`, `show_attribute` with `attribute`, `show_last_changed`, `show_last_updated`), or a `state` button's
 * domain default, each part as HA formats it, joined by " • ".
 */
internal object BubbleStateLine {
    private val DEFAULT_CONTENT = mapOf(
        "climate" to listOf("state", "current_temperature"),
        "cover" to listOf("state", "current_position"),
        "fan" to listOf("percentage"),
        "humidifier" to listOf("state", "current_humidity"),
        "light" to listOf("brightness"),
        "timer" to listOf("remaining_time"),
        "update" to listOf("install_status"),
        "valve" to listOf("state", "current_position"),
    )
    private val HIDDEN_ZERO = mapOf(
        "valve" to setOf("current_position"), "cover" to setOf("current_position"),
        "fan" to setOf("percentage"), "light" to setOf("brightness"),
    )
    val TIMESTAMP_CONTENTS = setOf("last_changed", "last_updated", "last_triggered")
    private val LEGACY_KEYS = listOf("show_state", "show_attribute", "attribute", "show_last_changed", "show_last_updated")
    const val SEPARATOR = " • "

    fun content(raw: JSONObject, buttonType: String, eid: String?): List<String>? {
        val stateButton = buttonType == "state"
        if (LEGACY_KEYS.any { raw.has(it) }) {
            val showState = if (raw.has("show_state")) raw.optBoolean("show_state") else stateButton
            val showAttribute = if (raw.has("show_attribute")) raw.optBoolean("show_attribute") else stateButton
            val showLastChanged = raw.optBoolean("show_last_changed", false)
            val showLastUpdated = raw.optBoolean("show_last_updated", false)
            if (!showState && !showAttribute && !showLastChanged && !showLastUpdated) return null
            val out = ArrayList<String>()
            if (showState) out += "state"
            val attribute = raw.optString("attribute").trim()
            if (showAttribute && attribute.isNotEmpty()) out += attribute
            if (showLastChanged) out += "last_changed"
            if (showLastUpdated) out += "last_updated"
            return out
        }
        if (!stateButton) return null
        return DEFAULT_CONTENT[eid?.substringBefore('.', "")] ?: listOf("state")
    }

    fun render(s: EntityState, content: List<String>, now: Long, haConfig: JSONObject): String {
        val parts = content.mapNotNull { item(s, it, now, haConfig).takeIf { t -> t.isNotEmpty() } }
        if (parts.isNotEmpty()) return parts.joinToString(SEPARATOR)
        return if (s.domain == "timer") HaStateDisplay.timerDisplay(s, now) {} else HaStateDisplay.formatEntityState(s)
    }

    private fun missing(v: Any?): Boolean = v == null || v == JSONObject.NULL || v == "" || v == "unknown" || v == "unavailable"

    private fun item(s: EntityState, item: String, now: Long, haConfig: JSONObject): String {
        if (item == "state") return if (s.domain == "timer") HaStateDisplay.timerDisplay(s, now) {} else HaStateDisplay.formatEntityState(s)
        if (item == "remaining_time" && s.domain == "timer") return HaStateDisplay.timerDisplay(s, now) {}
        if (item == "install_status") return HaStateDisplay.formatEntityState(s)
        if (item == "last_changed" || item == "last_updated") {
            val sec = if (item == "last_changed") s.lastChanged else s.lastUpdated
            return if (sec > 0.0) HaFormat.capitalize(relativeTime((sec * 1000).toLong(), now)) else ""
        }
        if (item == "last_triggered") {
            val v = s.attributes["last_triggered"]
            if (missing(v)) return ""
            val ms = dev.glassworks.engine.vitro.data.jsDateMs(v.toString()) ?: return ""
            return HaFormat.capitalize(relativeTime(ms, now))
        }
        val v = s.attributes[item]
        if (missing(v)) return ""
        if (HIDDEN_ZERO[s.domain]?.contains(item) == true && (v as? Number)?.toDouble() == 0.0) return ""
        return HaStateDisplay.attributeValue(s, item, v!!, haConfig)
    }

    /**
     * Bubble's `formatDateTime`: seconds up to a minute (one more than elapsed), then minutes, hours, days, weeks,
     * 30-day months and 365-day years, each rounded, through `Intl.RelativeTimeFormat` with `numeric: "auto"`.
     */
    fun relativeTime(ms: Long, now: Long): String {
        val diff = floor((now - ms) / 1000.0).toLong()
        val future = diff < 0
        val age = abs(diff)
        val (unit, value) = when {
            age < 60 -> "second" to age + 1
            age < 3_600 -> "minute" to jsRound(age / 60.0)
            age < 86_400 -> "hour" to jsRound(age / 3_600.0)
            age < 7 * 86_400 -> "day" to jsRound(age / 86_400.0)
            age < 30 * 86_400 -> "week" to jsRound(age / (7 * 86_400.0))
            age < 365 * 86_400 -> "month" to jsRound(age / (30 * 86_400.0))
            else -> "year" to jsRound(age / (365 * 86_400.0))
        }
        val signed = if (future) -value else value
        return HaIntl.of(HaLocale.current.language).relativeTime(-signed, unit)
    }

    private fun jsRound(v: Double): Long = floor(v + 0.5).toLong()
}

/** Bubble's colors and radii, from the theme's variables (or a card's own) and Bubble's defaults. */
internal class BubbleColors(private val ctx: VitroCardContext) {
    private fun v(name: String): String? = (ctx.cardVars[name] ?: ctx.theme.vars[name])?.trim()?.takeIf { it.isNotEmpty() }
    private fun color(name: String): Int? = ThemeResolver.parseColor(v(name))
    private fun firstColor(vararg names: String): Int? = names.firstNotNullOfOrNull { color(it) }
    private fun px(name: String): Float? = v(name)?.removeSuffix("px")?.trim()?.toFloatOrNull()

    private val primaryText: Int get() = ctx.theme.primaryTextColor
    private val textLight: Boolean get() = luminance(primaryText) > 0.5

    /** `--bubble-button-main-background-color`, `--bubble-main-background-color`, `--background-color-2`, `--secondary-background-color`. */
    fun mainBackground(): Int = firstColor("bubble-button-main-background-color", "bubble-main-background-color", "background-color-2")
        ?: ctx.theme.secondaryBackgroundColor

    /** `--bubble-button-icon-background-color` … `--card-background-color`, `--ha-card-background`. */
    fun iconBackground(): Int = firstColor(
        "bubble-button-icon-background-color", "bubble-icon-background-color", "bubble-secondary-background-color",
        "card-background-color", "ha-card-background",
    ) ?: ctx.theme.cardBackgroundColor

    /** The separator's line: `--bubble-line-background-color`, `--ha-card-background-color`, `--secondary-background-color`. */
    fun lineColor(): Int = firstColor("bubble-line-background-color", "ha-card-background-color") ?: ctx.theme.secondaryBackgroundColor

    fun buttonRadius(rowHeight: Float): Float = px("bubble-button-border-radius") ?: px("bubble-border-radius") ?: rowHeight / 2f

    /** The icon container's corners in px, or null for Bubble's default 50% (a circle). */
    fun iconRadius(): Float? = px("bubble-button-icon-border-radius") ?: px("bubble-icon-border-radius") ?: px("bubble-border-radius")

    /** `createBubbleDefaultColor`: Bubble's blue (0, 145, 255) mixed 70/30 with `--primary-background-color`. */
    private fun defaultColor(): Int {
        val bg = color("primary-background-color") ?: ctx.theme.backgroundColor
        fun mix(c: Int, b: Int) = jsRound(c * 0.7 + b * 0.3).toInt()
        return rgb(mix(0, r(bg)), mix(145, g(bg)), mix(255, b(bg)))
    }

    /** `var(--bubble-accent-color, var(--bubble-default-color))` (with the button's own first on its background). */
    private fun accent(button: Boolean): Int =
        (if (button) color("bubble-button-accent-color") else null) ?: color("bubble-accent-color") ?: defaultColor()

    /** `isStateOn`. */
    fun isStateOn(s: EntityState): Boolean {
        val state = s.state.lowercase()
        if (s.domain == "water_heater") return state != "" && state != "off" && state != "unknown" && state != "unavailable"
        if (state in ACTIVE) return true
        val n = jsNumber(state)
        return (n != null && n != 0.0) || s.unit?.contains('°') == true
    }

    /**
     * The icon's color while on (`changeIcon`): `var(--bubble-icon-color, getIconColor)`. `getIconColor`: no entity, a
     * degree unit or a digit in the state gives `var(--bubble-icon-color)`, unset by default, so the text color;
     * a light its own color (Bubble's warm white without one), scaled down a step on a light theme; anything else
     * the accent color.
     */
    fun iconColor(s: EntityState, raw: JSONObject): Int {
        color("bubble-icon-color")?.let { return it }
        return baseIconColor(s, raw) ?: primaryText
    }

    /** `getIconColor` resolved, or null for its `var(--bubble-icon-color)` (unset). */
    private fun baseIconColor(s: EntityState, raw: JSONObject): Int? {
        if (s.unit?.contains('°') == true || DIGIT.containsMatchIn(s.state)) return null
        val useAccent = raw.optBoolean("use_accent_color", false)
        if (s.domain != "light" || useAccent) return accent(false)
        val themeLight = luminance(iconBackground()) > 0.5
        val brightness = if (themeLight) 0.8 else 1.0
        val white = adjust(rgb(225, 225, 210), brightness)
        val lightWhite = color("bubble-light-color") ?: color("bubble-light-white-color") ?: white
        val rgb = rgbColor(s) ?: return lightWhite
        return if (closeToWhite(rgb)) lightWhite else (color("bubble-light-color") ?: adjust(rgb, brightness))
    }

    /** `changeButton`: the background's color and opacity. Only a `switch` that is on is colored. */
    fun buttonBackground(buttonType: String, s: EntityState?, raw: JSONObject): Pair<Int, Float> {
        if (buttonType != "switch" || s == null || !isStateOn(s)) return 0 to 0.5f
        val state = s.state.lowercase()
        if (state == "unlocked" || state == "error") {
            return (color("red-color") ?: color("error-color") ?: 0xFFF44336.toInt()) to 1f
        }
        val useAccent = raw.optBoolean("use_accent_color", false)
        if (s.domain == "light" && !useAccent) {
            // `getStateSurfaceColor`: the light's color, a step darker under a light text (lighter under a dark one).
            val base = baseIconColor(s, raw) ?: accent(false)
            return adjust(base, if (textLight) 0.84 else 1.16) to 0.7f
        }
        return accent(true) to 1f
    }

    private fun rgbColor(s: EntityState): Int? {
        val a = s.attributes["rgb_color"] ?: return null
        val list = when (a) {
            is org.json.JSONArray -> (0 until a.length()).map { a.opt(it) }
            is List<*> -> a
            else -> return null
        }
        if (list.size != 3) return null
        val c = list.map { (it as? Number)?.toDouble() ?: return null }
        return rgb(c[0].roundToInt(), c[1].roundToInt(), c[2].roundToInt())
    }

    private fun closeToWhite(c: Int): Boolean = listOf(r(c), g(c), b(c)).all { abs(it - 255) <= 40 }

    companion object {
        private val ACTIVE = setOf(
            "on", "open", "opening", "closing", "cleaning", "true", "home", "playing", "locked", "unlocked", "occupied",
            "available", "running", "active", "connected", "online", "mowing", "edgecut", "starting", "heat", "cool", "dry",
            "heat_cool", "fan_only", "auto", "alarm", "error",
        )
        private val DIGIT = Regex("\\d+")

        fun r(c: Int) = (c shr 16) and 0xFF
        fun g(c: Int) = (c shr 8) and 0xFF
        fun b(c: Int) = c and 0xFF
        fun rgb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

        /** Bubble's `calculateLuminance` (gamma kept): (0.2126 R + 0.7152 G + 0.0722 B) / 255. */
        fun luminance(c: Int): Double = (0.2126 * r(c) + 0.7152 * g(c) + 0.0722 * b(c)) / 255.0

        /** `adjustColor`: each channel times [k], rounded, capped at 255. */
        fun adjust(c: Int, k: Double): Int = rgb(min(255L, jsRound(r(c) * k)).toInt(), min(255L, jsRound(g(c) * k)).toInt(), min(255L, jsRound(b(c) * k)).toInt())

        private fun jsRound(v: Double): Long = floor(v + 0.5).toLong()

        /** JavaScript's `Number(text)` for a state: null for NaN. */
        fun jsNumber(text: String): Double? {
            val t = text.trim()
            if (t.isEmpty()) return 0.0
            return t.toDoubleOrNull()?.takeIf { !it.isNaN() && !t.endsWith("f", true) && !t.endsWith("d", true) }
        }
    }
}

/**
 * One of Bubble's text lines (`.bubble-name`, `.bubble-state`) on its 18 px line. A text wider than its box scrolls as
 * Bubble's marquee (`text-scrolling.js`): the text, a 30% " | " (8 px before, 6 px after), the text again and another
 * separator, moving left by half its width every `width / 16` seconds, faded over 8 px at both edges. With
 * `scrolling_effect: false` it wraps onto two lines and ends with an ellipsis instead.
 */
internal class BubbleTextLine(
    private val ctx: VitroCardContext,
    size: Float,
    private val weight: Int,
    private val scrolling: Boolean,
) : TextView(ctx.context) {
    private var cssSize = size
    private val fadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val sepPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var startedAt = -1L

    init {
        setTextColor(ctx.theme.primaryTextColor)
        includeFontPadding = false
        gravity = Gravity.CENTER_VERTICAL
        setSize(size)
        if (scrolling) {
            maxLines = 1
            setHorizontallyScrolling(true)
        } else {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
    }

    /** False hides the line whatever it says (`show_name: false`, a state line with no content). */
    var shown: Boolean = true
        set(v) { field = v; updateVisibility() }

    fun setSize(size: Float) {
        cssSize = size
        val scaled = ctx.scaledSp(size)
        textSize = scaled
        typeface = VitroFonts.typeface(ctx.context, weight, scaled)
        text?.toString()?.let { if (it.isNotEmpty()) lineBox(ctx, this, cssSize, 18f / cssSize) }
    }

    fun setLine(value: String) {
        if (text?.toString() == value) return
        text = value
        // Each line of text on an 18 px line box; an empty line has no box at all (a flex item with no content).
        if (value.isNotEmpty()) lineBox(ctx, this, cssSize, 18f / cssSize)
        updateVisibility()
        startedAt = -1L
        invalidate()
    }

    private fun updateVisibility() {
        visibility = if (shown && !text.isNullOrEmpty()) VISIBLE else GONE
    }

    /** Keeps a relative time current each second while shown. */
    fun tickWith(ctx: VitroCardContext, s: EntityState, relative: (EntityState, Long) -> String?) {
        TimestampTextBridge.show(ctx, this, s, relative)
    }

    private fun textWidth(): Float = paint.measureText(text?.toString().orEmpty())

    /** `content > available + 0.5`: the text is wider than its box. */
    private fun overflows(): Boolean = scrolling && textWidth() > (width - paddingLeft - paddingRight) + 0.5f * ctx.dp

    override fun onDraw(canvas: Canvas) {
        if (!overflows()) {
            super.onDraw(canvas)
            return
        }
        val t = text?.toString().orEmpty()
        val p = paint
        p.color = currentTextColor
        sepPaint.set(p)
        sepPaint.alpha = (0.3f * 255).roundToInt()
        val sep = " | "
        val sepWidth = 8f * ctx.dp + p.measureText(sep) + 6f * ctx.dp
        val half = p.measureText(t) + sepWidth
        val now = android.os.SystemClock.uptimeMillis()
        if (startedAt < 0) startedAt = now
        val durationMs = max(1.0, (half / ctx.dp) / 16.0) * 1000.0
        val shift = -(((now - startedAt) % durationMs.toLong()) / durationMs * half).toFloat()
        val w = width.toFloat()
        val h = height.toFloat()
        val save = canvas.saveLayer(0f, 0f, w, h, null)
        val fm = p.fontMetrics
        val baseline = h / 2f - (fm.ascent + fm.descent) / 2f
        var x = shift
        repeat(2) {
            canvas.drawText(t, x, baseline, p)
            x += p.measureText(t) + 8f * ctx.dp
            canvas.drawText(sep, x, baseline, sepPaint)
            x += p.measureText(sep) + 6f * ctx.dp
        }
        // `mask-image: linear-gradient(to right, transparent 1px, black 9px, black calc(100% - 9px), transparent calc(100% - 1px))`.
        val one = ctx.dp
        val nine = 9f * ctx.dp
        if (w > 2 * nine) {
            fadePaint.shader = LinearGradient(
                0f, 0f, w, 0f,
                intArrayOf(0, -0x1000000, -0x1000000, 0),
                floatArrayOf(one / w, nine / w, (w - nine) / w, (w - one) / w),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, w, h, fadePaint)
        }
        canvas.restoreToCount(save)
        if (isAttachedToWindow) postInvalidateOnAnimation()
    }
}

/** [dev.glassworks.engine.vitro.ui.TimestampText] for a Bubble line: the card's own relative form, ticking each second. */
internal object TimestampTextBridge {
    fun show(ctx: VitroCardContext, view: TextView, s: EntityState, relative: (EntityState, Long) -> String?) {
        dev.glassworks.engine.vitro.ui.TimestampText.show(view, s, view.text?.toString().orEmpty(), ctx.now, { ctx.store[it] }, ctx.main, relative)
    }
}
