/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.cards

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.Space
import android.widget.TextView
import dev.glassworks.engine.vitro.data.HaText
import dev.glassworks.engine.vitro.data.HaDisplay
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.HaFormat
import dev.glassworks.engine.vitro.data.HaLocale
import dev.glassworks.engine.vitro.model.ActionConfig
import dev.glassworks.engine.vitro.model.CardConfig
import dev.glassworks.engine.vitro.model.CardRouter
import dev.glassworks.engine.vitro.model.HaCardSize
import dev.glassworks.engine.vitro.model.LovelaceParser
import dev.glassworks.engine.vitro.compat.WebFallback
import dev.glassworks.engine.vitro.ui.CardSurface
import dev.glassworks.engine.vitro.ui.MdiIconView
import dev.glassworks.engine.vitro.ui.MdiIcons
import dev.glassworks.engine.vitro.ui.VitroCardContext
import dev.glassworks.engine.vitro.ui.bindHoldAction
import dev.glassworks.ui.Inter
import dev.glassworks.engine.vitro.ui.VitroFonts
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

// Tier 2 HACS parity wave: the rest of the Mushroom suite, mini-media-player, auto-entities, the seamless stack
// containers (stack-in-card, vertical-stack-in-card, layout-card). Every card here
// reads the same YAML its HACS original does and calls the same Home Assistant services, with no web view.

/** Builds one child card through `VitroView.buildCard`, visibility included. Containers get it from the view. */
typealias CardBuilder = (CardConfig) -> View

private const val LOCKED_GREEN = 0xFF4CAF50.toInt()
private const val ALERT_RED = 0xFFF44336.toInt()
private const val PENDING_ORANGE = 0xFFFF9800.toInt()

private fun label(ctx: VitroCardContext, sp: Float, color: Int, weight: Int = Inter.REGULAR): TextView =
    TextView(ctx.context).apply {
        val scaled = ctx.scaledSp(sp)
        textSize = scaled
        setTextColor(color)
        typeface = VitroFonts.typeface(ctx.context, weight, scaled)
        includeFontPadding = false
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

private fun cardHoldAction(raw: JSONObject, entityId: String?): ActionConfig =
    ActionConfig.parse(raw.optJSONObject("hold_action"), if (entityId != null) "more-info" else "none")

/** A config list of strings (`commands: [start_pause, stop]`), or [default] when the key is absent. */
private fun stringList(raw: JSONObject, key: String, default: List<String> = emptyList()): List<String> {
    val arr = raw.optJSONArray(key) ?: return default
    return (0 until arr.length()).mapNotNull { i ->
        when (val v = arr.opt(i)) {
            is String -> v.ifBlank { null }
            is JSONObject -> v.optString("type", "").ifBlank { null } // mushroom-chips style `{type: …}`
            else -> null
        }
    }
}

private fun titleCase(s: String): String =
    s.replace('_', ' ').replaceFirstChar { it.titlecase(Locale.ROOT) }

/** [name] when the icon set knows it, else [fallback]: keeps a newer icon name from drawing the `home` glyph. */
private fun iconOr(name: String, fallback: String): String = if (MdiIcons.has(name)) name else fallback

// ---------------------------------------------------------------------------------------------------------------
// Mushroom: shared card shell
// ---------------------------------------------------------------------------------------------------------------

/**
 * The Mushroom card shell every control card shares, at Mushroom's sizes: `--spacing` (10 px) around a 36 px
 * (`--icon-size`) tinted shape, the primary (14 px / 500 on a 20 px line) and secondary (12 px on 16 px, also in
 * the primary text color) lines, and a row of 42 px (`--control-height`) rounded buttons under them. With
 * `layout: horizontal` the buttons sit beside the info instead, at the icon's size and aligned to the end. `name`, `icon`, `hide_name`, `hide_state`, `hide_icon`,
 * `fill_container`, `tap_action`, `hold_action` and `icon_tap_action` read as in Mushroom. Subclasses build their
 * controls in their own `init`, then call [start].
 */
abstract class MushroomShellCardView(ctx: VitroCardContext, protected val config: CardConfig) : CardSurface(ctx) {
    // HA's tile and Mushroom's cards lay their content out from the card's outer edge.
    override val contentInsideBorder: Boolean get() = false
    protected val eid: String? = config.entity
    /** Mushroom's off color, from the card scope in force while the card is built. */
    protected val mushroomDisabled = mushroomDisabledColor(ctx)
    protected val shape = FrameLayout(ctx.context)
    protected val shapeIcon = MdiIconView(ctx.context)
    protected val shapePicture = ImageView(ctx.context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        visibility = GONE
    }
    protected val primary = label(ctx, 14f, ctx.theme.primaryTextColor, Inter.MEDIUM).apply {
        gravity = Gravity.CENTER_VERTICAL; letterSpacing = 0.1f / 14f
    }
    protected val secondary = label(ctx, 12f, ctx.theme.primaryTextColor).apply {
        gravity = Gravity.CENTER_VERTICAL; letterSpacing = 0.4f / 12f
    }
    /** mushroom-badge-icon: a 16 px disc with a white 12 px icon on the shape's top right ([setBadge]). */
    protected val badge = FrameLayout(ctx.context).apply { visibility = GONE }
    private val badgeIcon = MdiIconView(ctx.context).also { badge.addView(it, FrameLayout.LayoutParams(ctx.px(12f), ctx.px(12f), Gravity.CENTER)) }
    /** `layout: horizontal`: the controls share the info's row. */
    protected val horizontal = config.raw.optString("layout") == "horizontal"
    /** `--control-height`: 42 px, or the icon's 36 px beside the info. */
    private val controlHeight = if (horizontal) 36f else 42f
    /** The control rows, stacked under the header. */
    protected val controls = LinearLayout(ctx.context).apply { orientation = LinearLayout.VERTICAL }
    private val hideName = config.raw.optBoolean("hide_name", false)
    private val hideState = config.raw.optBoolean("hide_state", false)
    private var pictureUrl: String? = null

    init {
        // Beside the info (horizontal), the card is one padded row. Under it, as mushroom-card lays it out: the state
        // item (10 px around) takes the height the actions leave, its content centered; the actions are 0 12 12 px
        // around 42 px controls.
        val column = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.VERTICAL
            if (horizontal) setPadding(ctx.px(10f), ctx.px(10f), ctx.px(10f), ctx.px(10f))
        }
        shape.addView(shapeIcon, FrameLayout.LayoutParams(ctx.px(22f), ctx.px(22f), Gravity.CENTER))
        shape.addView(shapePicture, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        shape.clipToOutline = true
        val texts = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.VERTICAL
            // 20 / 16 px lines at Mushroom's sizes, growing when card_mod sets a larger font.
            primary.minHeight = ctx.px(20f)
            secondary.minHeight = ctx.px(16f)
            addView(primary, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(secondary, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        // mushroom-state-item: the shape and the info. With `layout: horizontal` it and the actions are flex: 1
        // halves of the row, so a long title truncates at the middle as in HA.
        val stateItem = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            gravity = Gravity.CENTER_VERTICAL
            if (!config.raw.optBoolean("hide_icon", false)) {
                // The shape, and Mushroom's badge on its top right (16 px, 3 px past the shape's edge).
                addView(FrameLayout(ctx.context).apply {
                    clipChildren = false
                    clipToPadding = false
                    addView(shape, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                    addView(badge, FrameLayout.LayoutParams(ctx.px(16f), ctx.px(16f), Gravity.TOP or Gravity.END).apply {
                        topMargin = -ctx.px(3f); rightMargin = -ctx.px(3f)
                    })
                }, LinearLayout.LayoutParams(ctx.px(36f), ctx.px(36f)).apply { marginEnd = ctx.px(10f) })
            }
            addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            // The state item keeps its own 10 px end padding inside its half (HA: a 129 px info box in a 400 px card).
            if (horizontal) setPadding(0, 0, ctx.px(10f), 0)
        }
        val header = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(stateItem, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            // The state item's own 10 px end padding is the gap: a slider starts right after it (mushroom-light-horizontal).
            if (horizontal) addView(controls, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        if (hideName) primary.visibility = GONE
        if (hideState) secondary.visibility = GONE
        if (horizontal) {
            column.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(column, LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER_VERTICAL))
        } else {
            header.setPadding(ctx.px(10f), ctx.px(10f), ctx.px(10f), ctx.px(10f))
            column.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            controls.setPadding(ctx.px(12f), 0, ctx.px(12f), ctx.px(12f))
            column.addView(controls, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(column, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }

        // Standard Mushroom `--mush-icon-border-radius`: circular by default, rounded square when a px/dp value is set.
        val iconRadius = mushIconRadiusPx(ctx)
        if (iconRadius != null) {
            shape.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, iconRadius)
            }
        }

        isClickable = true
        setOnClickListener { ctx.performAction(ActionConfig.parse(config.raw.optJSONObject("tap_action")), eid) }
        bindHoldAction(cardHoldAction(config.raw, eid), eid)
        applyHaRipple(this, ctx.theme.cardBorderRadiusDp * ctx.dp, ctx.theme.dark)
        config.raw.optJSONObject("icon_tap_action")?.let { ActionConfig.parse(it) }?.let { iconTap ->
            shape.isClickable = true
            shape.setOnClickListener { ctx.performAction(iconTap, eid) }
        }
    }

    override fun onCardModApplied(mod: dev.glassworks.engine.vitro.theme.CardModStyle) {
        val sp = mod.fontSizeSp ?: return
        val scaled = ctx.scaledSp(sp)
        primary.textSize = scaled
        primary.typeface = VitroFonts.typeface(ctx.context, Inter.MEDIUM, scaled)
        val sub = ctx.scaledSp((sp * 0.75f).coerceAtLeast(12f))
        secondary.textSize = sub
        secondary.typeface = VitroFonts.typeface(ctx.context, Inter.REGULAR, sub)
    }

    /** Subscribes to the card's entity (or renders once without one). Call last in the subclass `init`. */
    protected fun start() {
        // No control rows: no actions block (its padding would add 12 px under the info).
        if (controls.childCount == 0) controls.visibility = GONE
        val id = eid
        if (id != null) observe(id) { s -> renderShell(s) } else renderShell(null)
    }

    private fun renderShell(s: EntityState?) {
        primary.text = config.name(s) ?: s?.displayName ?: eid ?: ""
        render(s)
    }

    /** Draws the state: the shape, [secondary] and the controls. */
    protected abstract fun render(s: EntityState?)

    /** Tints the shape icon: [color] at full strength on a 20% disc while [active], muted otherwise. */
    protected fun setShape(iconName: String, color: Int, active: Boolean) {
        val tint = if (active) color else ctx.theme.secondaryTextColor
        shapeIcon.setIcon(config.icon ?: iconName, tint)
        val radius = mushIconRadiusPx(ctx)
        shape.background = GradientDrawable().apply {
            if (radius != null) {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = radius
            } else {
                shape = GradientDrawable.OVAL
            }
            setColor(CardSurface.withAlpha(tint, if (active) 0.2f else 0.08f))
        }
    }

    /** Shows [url] (an `entity_picture`) in the shape instead of the icon, or the icon again when null. */
    protected fun setShapePicture(url: String?, round: Boolean = true) {
        if (url == pictureUrl) return
        pictureUrl = url
        if (url == null) {
            shapePicture.setImageDrawable(null)
            shapePicture.visibility = GONE
            shapeIcon.visibility = VISIBLE
            return
        }
        shape.background = GradientDrawable().apply {
            if (round) shape = GradientDrawable.OVAL else cornerRadius = ctx.px(8f).toFloat()
            setColor(CardSurface.withAlpha(ctx.theme.primaryTextColor, 0.08f))
        }
        // The previous picture (another track's artwork) goes now: the icon shows until this one has loaded.
        shapePicture.setImageDrawable(null)
        shapePicture.visibility = GONE
        shapeIcon.visibility = VISIBLE
        ctx.loadImage(url, this) { bmp ->
            if (pictureUrl != url) return@loadImage
            shapePicture.setImageBitmap(bmp)
            shapePicture.visibility = if (bmp != null) VISIBLE else GONE
            shapeIcon.visibility = if (bmp != null) GONE else VISIBLE
        }
    }

    protected fun setSecondary(text: String) {
        secondary.text = text
    }

    /** Shows Mushroom's badge on the shape: [icon] (white) on a [color] disc; null hides it. */
    protected fun setBadge(icon: String?, color: Int) {
        badge.visibility = if (icon == null) GONE else VISIBLE
        if (icon == null) return
        badgeIcon.setIcon(icon, Color.WHITE)
        badge.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
    }

    /** Mushroom's `.shape.disabled` (an off light, a closed cover): `--rgb-disabled`, its disc at 20%. */
    protected fun setShapeDisabled(iconName: String) {
        val c = mushroomDisabled
        shapeIcon.setIcon(config.icon ?: iconName, c)
        val radius = mushIconRadiusPx(ctx)
        shape.background = GradientDrawable().apply {
            if (radius != null) {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = radius
            } else {
                shape = GradientDrawable.OVAL
            }
            setColor(CardSurface.withAlpha(c, 0.2f))
        }
    }

    /** A `mushroom-slider` in [color]: its track at 20%, the fill from the left; off, the disabled color. */
    internal fun mushroomSlider(color: Int, onCommit: (Float) -> Unit): HaHorizontalSliderView =
        HaHorizontalSliderView(ctx, color, onCommit = onCommit).apply {
            mushroom = true
            inactiveColor = mushroomDisabled
        }

    /** A new horizontal control row in [controls]. */
    protected fun controlRow(): LinearLayout = LinearLayout(ctx.context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL or (if (horizontal) Gravity.END else Gravity.START)
        controls.addView(this, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            if (controls.childCount > 0) topMargin = ctx.px(if (horizontal) 10f else 12f)
        })
    }

    /** `--control-icon-size`: half the control height. */
    protected val controlIconPx: Int get() = ctx.px(controlHeight / 2f)

    /** A Mushroom control button: a 42 dp rounded square with one icon. [description] names it for TalkBack and tests. */
    protected fun mushroomButton(icon: String, description: String, onClick: () -> Unit): FrameLayout =
        FrameLayout(ctx.context).apply {
            isClickable = true
            contentDescription = description
            background = GradientDrawable().apply {
                cornerRadius = ctx.px(12f).toFloat()
                setColor(CardSurface.withAlpha(ctx.theme.primaryTextColor, 0.05f))
            }
            addView(MdiIconView(ctx.context).apply { setIcon(icon, ctx.theme.primaryTextColor) },
                FrameLayout.LayoutParams(controlIconPx, controlIconPx, Gravity.CENTER))
            // A disabled `<button>` sends no click ([setMushroomButtonEnabled]).
            setOnClickListener { if (isEnabled) onClick() }
            applyHaRipple(this, ctx.px(12f).toFloat(), ctx.theme.dark)
        }

    /**
     * `mushroom-button`'s `disabled`: `--rgb-disabled` at 20 % behind the icon, the icon in `--rgb-disabled`, and no
     * tap; enabled again, its own look ([mushroomButton]).
     */
    protected fun setMushroomButtonEnabled(button: FrameLayout, enabled: Boolean) {
        if (button.isEnabled == enabled) return
        button.isEnabled = enabled
        (button.background as? GradientDrawable)?.setColor(
            if (enabled) CardSurface.withAlpha(ctx.theme.primaryTextColor, 0.05f) else CardSurface.withAlpha(mushroomDisabled, 0.2f),
        )
        (button.getChildAt(0) as? MdiIconView)?.let { v -> v.setIcon(v.icon, if (enabled) ctx.theme.primaryTextColor else mushroomDisabled) }
    }

    /**
     * Adds [button] to [row]: an equal-width cell, or a fixed square ([weight] squares wide) beside the info. A slider
     * ([fill]) takes the row's free width beside the info too, as `mushroom-slider`'s `flex: 1` does (parity case
     * mushroom-light-horizontal).
     */
    protected fun addCell(row: LinearLayout, button: View, weight: Float = 1f, fill: Boolean = false) {
        val lp = if (horizontal && !fill) LinearLayout.LayoutParams(ctx.px(controlHeight * weight), ctx.px(controlHeight))
        else LinearLayout.LayoutParams(0, ctx.px(controlHeight), weight)
        row.addView(button, lp.apply { if (row.childCount > 0) marginStart = ctx.px(10f) })
    }

    protected fun call(domain: String, service: String, extra: (JSONObject) -> Unit = {}) {
        val id = eid ?: return
        ctx.callService(domain, service, JSONObject().put("entity_id", id).also(extra), null)
    }

    /** Hides the controls while the entity is off when `collapsible_controls: true`. */
    protected fun collapseWhen(off: Boolean) {
        val collapse = config.raw.optBoolean("collapsible_controls", false) && off
        controls.visibility = if (collapse) GONE else VISIBLE
    }
}

// ---------------------------------------------------------------------------------------------------------------
// custom:mushroom-media-player-card
// ---------------------------------------------------------------------------------------------------------------

/**
 * Native `custom:mushroom-media-player-card`: the player icon (or the album art with `use_media_info`), the title
 * and artist, `media_controls` (`on_off`, `shuffle`, `previous`, `play_pause_stop`, `next`, `repeat`) and
 * `volume_controls` (`volume_mute`, `volume_set`, `volume_buttons`); `show_volume_level` adds the level.
 */
class MushroomMediaPlayerCardView(ctx: VitroCardContext, config: CardConfig) : MushroomShellCardView(ctx, config) {
    private val useMediaInfo = config.raw.optBoolean("use_media_info", false)
    private val showVolumeLevel = config.raw.optBoolean("show_volume_level", false)
    // Each control once: its button owns a single icon view, and a repeated entry would attach that view twice.
    private val mediaControls = stringList(config.raw, "media_controls").distinct()
    private val volumeControls = stringList(config.raw, "volume_controls").distinct()
    private val mediaRow = if (mediaControls.isNotEmpty()) controlRow() else null
    private val volumeRow = if (volumeControls.isNotEmpty()) controlRow() else null
    private val playIcon = MdiIconView(ctx.context)
    private val muteIcon = MdiIconView(ctx.context)
    private var slider: HaHorizontalSliderView? = null
    private var playButton: View? = null

    init {
        mediaRow?.let { row ->
            for (c in mediaControls) when (c) {
                "on_off" -> addCell(row, mushroomButton("mdi:power", "Power") {
                    call("media_player", if (current()?.state == "off") "turn_on" else "turn_off")
                })
                "shuffle" -> addCell(row, mushroomButton("mdi:shuffle", "Shuffle") {
                    val on = current()?.attributes?.get("shuffle") == true
                    call("media_player", "shuffle_set") { it.put("shuffle", !on) }
                })
                "previous" -> addCell(row, mushroomButton("mdi:skip-previous", "Previous track") { call("media_player", "media_previous_track") })
                "play_pause_stop" -> addCell(row, mushroomButton("mdi:play", "Play") { call("media_player", "media_play_pause") }.also { b ->
                    b.removeAllViews()
                    b.addView(playIcon, FrameLayout.LayoutParams(controlIconPx, controlIconPx, Gravity.CENTER))
                    playButton = b
                })
                "next" -> addCell(row, mushroomButton("mdi:skip-next", "Next track") { call("media_player", "media_next_track") })
                "repeat" -> addCell(row, mushroomButton("mdi:repeat", "Repeat") {
                    val next = when (current()?.attributes?.get("repeat")) { "off" -> "all"; "all" -> "one"; else -> "off" }
                    call("media_player", "repeat_set") { it.put("repeat", next) }
                })
            }
        }
        volumeRow?.let { row ->
            for (c in volumeControls) when (c) {
                "volume_mute" -> addCell(row, mushroomButton("mdi:volume-high", "Mute") {
                    val muted = current()?.attributes?.get("is_volume_muted") == true
                    call("media_player", "volume_mute") { it.put("is_volume_muted", !muted) }
                }.also { b ->
                    b.removeAllViews()
                    b.addView(muteIcon, FrameLayout.LayoutParams(controlIconPx, controlIconPx, Gravity.CENTER))
                })
                "volume_set" -> {
                    val s = HaHorizontalSliderView(ctx, ctx.theme.activeColor("media_player")) { frac ->
                        call("media_player", "volume_set") { it.put("volume_level", (frac * 100f).roundToInt() / 100.0) }
                    }
                    slider = s
                    addCell(row, s, 3f, fill = true)
                }
                "volume_buttons" -> {
                    addCell(row, mushroomButton("mdi:volume-minus", "Volume down") { call("media_player", "volume_down") })
                    addCell(row, mushroomButton("mdi:volume-plus", "Volume up") { call("media_player", "volume_up") })
                }
            }
        }
        start()
    }

    private fun current(): EntityState? = eid?.let { ctx.store[it] }

    override fun render(s: EntityState?) {
        val attrs = s?.attributes.orEmpty()
        val off = s == null || s.state == "off" || s.isUnavailable
        val playing = s?.state == "playing"
        val color = ctx.theme.activeColor("media_player")
        setShape(MdiIcons.resolveIconName(null, s).takeIf { s != null } ?: "mdi:speaker", color, !off)
        setShapePicture(if (useMediaInfo && s != null && !off) s.entityPicture else null)
        val title = (attrs["media_title"] as? String)?.takeIf { it.isNotBlank() }
        val artist = (attrs["media_artist"] as? String)?.takeIf { it.isNotBlank() }
        if (useMediaInfo && title != null) {
            primary.text = title
        }
        val vol = (attrs["volume_level"] as? Number)?.toDouble()
        val base = if (useMediaInfo && title != null) artist ?: s?.friendlyName.orEmpty() else s?.formattedState() ?: "…"
        setSecondary(if (showVolumeLevel && vol != null && !off) "$base · ${(vol * 100).roundToInt()}%" else base)
        playIcon.setIcon(if (playing) "mdi:pause" else "mdi:play", ctx.theme.primaryTextColor)
        playButton?.contentDescription = if (playing) "Pause" else "Play"
        val muted = attrs["is_volume_muted"] == true
        muteIcon.setIcon(if (muted) "mdi:volume-off" else "mdi:volume-high", ctx.theme.primaryTextColor)
        if (vol != null) slider?.setValue(vol.toFloat(), active = !muted && vol > 0)
        volumeRow?.visibility = if (off) GONE else VISIBLE
        collapseWhen(off)
    }
}

// ---------------------------------------------------------------------------------------------------------------
// custom:mushroom-lock-card
// ---------------------------------------------------------------------------------------------------------------

/** Native `custom:mushroom-lock-card`: green while locked, red while unlocked, one button that does the opposite. */
class MushroomLockCardView(ctx: VitroCardContext, config: CardConfig) : MushroomShellCardView(ctx, config) {
    private val row = controlRow()
    private val lockButton = mushroomButton("mdi:lock-outline", "Lock") { call("lock", "lock") }
    private val unlockButton = mushroomButton(iconOr("mdi:lock-open-variant-outline", "mdi:lock-open"), "Unlock") { call("lock", "unlock") }
    private val openButton = mushroomButton(iconOr("mdi:door-open", "mdi:lock-open"), "Open") { call("lock", "open") }

    init {
        addCell(row, lockButton)
        addCell(row, unlockButton)
        addCell(row, openButton)
        start()
    }

    override fun render(s: EntityState?) {
        val state = s?.state.orEmpty()
        val (icon, color) = when (state) {
            "locked" -> "mdi:lock" to LOCKED_GREEN
            "unlocked", "open" -> "mdi:lock-open" to ALERT_RED
            "jammed" -> iconOr("mdi:lock-alert", "mdi:lock") to ALERT_RED
            "locking", "unlocking", "opening" -> iconOr("mdi:lock-clock", "mdi:lock") to PENDING_ORANGE
            else -> "mdi:lock" to ctx.theme.secondaryTextColor
        }
        setShape(icon, color, s != null && !s.isUnavailable)
        setSecondary(s?.let { titleCase(it.state) } ?: "…")
        val supportsOpen = ((s?.attributes?.get("supported_features") as? Number)?.toInt() ?: 0) and 1 != 0
        lockButton.visibility = if (state == "locked" || state == "locking") GONE else VISIBLE
        unlockButton.visibility = if (state == "unlocked" || state == "unlocking" || state == "open") GONE else VISIBLE
        openButton.visibility = if (supportsOpen && state == "locked") VISIBLE else GONE
        controls.visibility = if (s == null || s.isUnavailable) GONE else VISIBLE
    }
}

// ---------------------------------------------------------------------------------------------------------------
// custom:mushroom-alarm-control-panel-card
// ---------------------------------------------------------------------------------------------------------------

/**
 * Native `custom:mushroom-alarm-control-panel-card` (Mushroom 5.3.0): the alarm's state with HA's icon for it, and
 * one button for each of `states:` while disarmed, or a single Disarm button otherwise; without `states` no buttons
 * at all. An unavailable alarm keeps its button, disabled. A button whose service needs a code (arming with
 * `code_arm_required`, disarming with a `code_format`) asks for it first, unless the entity's registry options hold a
 * default code, as Mushroom's `_onTap` does.
 */
class MushroomAlarmControlPanelCardView(ctx: VitroCardContext, config: CardConfig) : MushroomShellCardView(ctx, config) {
    private val states = stringList(config.raw, "states").filter { it in BUTTON_ICONS }
    private val row = if (states.isNotEmpty()) controlRow() else null
    // Mushroom's buttons: one icon each (its `_n` map), no label.
    private val armButtons = states.associateWith { st ->
        mushroomButton(BUTTON_ICONS.getValue(st), "Arm " + st.removePrefix("armed_").replace('_', ' ')) { tap(st) }
    }
    private val disarmButton = if (row != null) mushroomButton("mdi:shield-off", "Disarm") { tap("disarmed") } else null

    init {
        row?.let { r ->
            armButtons.values.forEach { addCell(r, it) }
            disarmButton?.let { addCell(r, it) }
        }
        start()
    }

    /** Mushroom's `_onTap`: the mode's service, with a code when the alarm needs one and has no default code. */
    private fun tap(mode: String) {
        val id = eid ?: return
        val s = ctx.store[id] ?: return
        val service = if (mode == "disarmed") "alarm_disarm" else "alarm_${mode.replace("armed_", "arm_")}"
        fun send(code: String?) {
            val data = JSONObject().put("entity_id", id)
            if (code != null) data.put("code", code)
            ctx.callService("alarm_control_panel", service, data, null)
        }
        val disarm = mode == "disarmed"
        val needsCode = if (disarm) s.attributes["code_format"] != null else s.attributes["code_arm_required"] == true
        if (!needsCode) return send(null)
        ctx.callWs(JSONObject().put("type", "config/entity_registry/get").put("entity_id", id)) { ok, result, _ ->
            val defaultCode = (result as? JSONObject)?.optJSONObject("options")?.optJSONObject("alarm_control_panel")
                ?.opt("default_code")?.takeIf { ok && it != JSONObject.NULL && it.toString().isNotEmpty() }
            ctx.main.post {
                if (defaultCode != null) send(null)
                else HaCodeDialog.ask(
                    ctx,
                    codeFormat = s.attributes["code_format"]?.toString(),
                    title = if (disarm) HaText.ui("ui.card.alarm_control_panel.disarm") else HaText.ui("ui.card.alarm_control_panel.arm"),
                ) { code -> send(code) }
            }
        }
    }

    override fun render(s: EntityState?) {
        val state = s?.state.orEmpty()
        // Mushroom's alarm colors, by the state's first word: disarmed --rgb-info, armed --rgb-success, triggered
        // --rgb-danger, unavailable --rgb-warning, the rest grey. The icon is HA's for the state.
        val color = when (state.substringBefore('_')) {
            "disarmed" -> INFO_BLUE
            "armed" -> SUCCESS_GREEN
            "triggered" -> DANGER_RED
            "unavailable" -> WARNING_ORANGE
            else -> GREY
        }
        setShape(MdiIcons.resolveIconName(null, s).takeIf { s != null } ?: "mdi:shield", color, s != null)
        setSecondary(s?.formattedState() ?: "…")
        val disarmed = state == "disarmed"
        armButtons.values.forEach { it.visibility = if (disarmed) VISIBLE else GONE }
        disarmButton?.visibility = if (disarmed) GONE else VISIBLE
        // `.disabled=${!isAvailable}`: only an unavailable alarm's button is disabled.
        val enabled = state != "unavailable"
        (armButtons.values + listOfNotNull(disarmButton)).forEach { setMushroomButtonEnabled(it, enabled) }
    }

    private companion object {
        val BUTTON_ICONS = mapOf(
            "armed_home" to "mdi:home",
            "armed_away" to "mdi:lock",
            "armed_night" to "mdi:moon-waning-crescent",
            "armed_vacation" to "mdi:airplane",
            "armed_custom_bypass" to "mdi:shield",
        )
        val INFO_BLUE = 0xFF2196F3.toInt()
        val SUCCESS_GREEN = 0xFF4CAF50.toInt()
        val DANGER_RED = 0xFFDB4437.toInt()
        val WARNING_ORANGE = 0xFFFFA600.toInt()
        val GREY = 0xFF9E9E9E.toInt()
    }
}

// ---------------------------------------------------------------------------------------------------------------
// custom:mushroom-vacuum-card
// ---------------------------------------------------------------------------------------------------------------

/**
 * Native `custom:mushroom-vacuum-card`: status and battery, with a button per `commands:` entry (`on_off`,
 * `start_pause`, `stop`, `locate`, `clean_spot`, `return_home`).
 */
class MushroomVacuumCardView(ctx: VitroCardContext, config: CardConfig) : MushroomShellCardView(ctx, config) {
    private val commands = stringList(config.raw, "commands").distinct()
    private val startPauseIcon = MdiIconView(ctx.context)
    private var startPause: View? = null

    init {
        if (commands.isNotEmpty()) {
            val row = controlRow()
            for (c in commands) when (c) {
                "on_off" -> addCell(row, mushroomButton("mdi:power", "Power") {
                    call("vacuum", if (current()?.state == "off") "turn_on" else "turn_off")
                })
                "start_pause" -> addCell(row, mushroomButton("mdi:play", "Start") {
                    call("vacuum", if (current()?.state == "cleaning") "pause" else "start")
                }.also { b ->
                    b.removeAllViews()
                    b.addView(startPauseIcon, FrameLayout.LayoutParams(controlIconPx, controlIconPx, Gravity.CENTER))
                    startPause = b
                })
                "stop" -> addCell(row, mushroomButton("mdi:stop", "Stop") { call("vacuum", "stop") })
                "locate" -> addCell(row, mushroomButton("mdi:map-marker", "Locate") { call("vacuum", "locate") })
                "clean_spot" -> addCell(row, mushroomButton(iconOr("mdi:target-variant", "mdi:broom"), "Clean spot") { call("vacuum", "clean_spot") })
                "return_home" -> addCell(row, mushroomButton("mdi:home-map-marker".let { iconOr(it, "mdi:home") }, "Return home") {
                    call("vacuum", "return_to_base")
                })
            }
        }
        start()
    }

    private fun current(): EntityState? = eid?.let { ctx.store[it] }

    override fun render(s: EntityState?) {
        val active = s?.isActive == true
        // Mushroom: HA's icon for the entity (its own `icon` first), teal (--rgb-state-vacuum) while active, the
        // disabled look otherwise; HA's state label under the name ("Docked").
        val icon = MdiIcons.resolveIconName(null, s).takeIf { s != null } ?: "mdi:robot-vacuum"
        if (active) setShape(icon, TEAL, true) else setShapeDisabled(icon)
        setSecondary(s?.formattedState() ?: "…")
        val cleaning = s?.state == "cleaning"
        startPauseIcon.setIcon(if (cleaning) "mdi:pause" else "mdi:play", ctx.theme.primaryTextColor)
        startPause?.contentDescription = if (cleaning) "Pause" else "Start"
        controls.visibility = if (s == null || s.isUnavailable) GONE else VISIBLE
    }

    private companion object {
        val TEAL = 0xFF009688.toInt()
    }
}

// ---------------------------------------------------------------------------------------------------------------
// custom:mushroom-humidifier-card
// ---------------------------------------------------------------------------------------------------------------

/**
 * Native `custom:mushroom-humidifier-card`: the humidity and target, a toggle on the icon, and with
 * `show_target_humidity_control` a target slider (`humidifier.set_humidity`, within `min_humidity`..`max_humidity`).
 */
class MushroomHumidifierCardView(ctx: VitroCardContext, config: CardConfig) : MushroomShellCardView(ctx, config) {
    private var slider: HaHorizontalSliderView? = null

    init {
        shape.isClickable = true
        shape.contentDescription = HaText.ui("ui.card.common.toggle")
        if (!config.raw.has("icon_tap_action")) shape.setOnClickListener { call("humidifier", "toggle") }
        if (config.raw.optBoolean("show_target_humidity_control", false)) {
            val row = controlRow()
            val s = HaHorizontalSliderView(ctx, ctx.theme.activeColor("humidifier")) { frac ->
                val st = eid?.let { ctx.store[it] }
                val (min, max) = range(st)
                call("humidifier", "set_humidity") { it.put("humidity", (min + frac * (max - min)).roundToInt()) }
            }
            slider = s
            addCell(row, s, fill = true)
        }
        start()
    }

    private fun range(s: EntityState?): Pair<Float, Float> {
        val min = (s?.attributes?.get("min_humidity") as? Number)?.toFloat() ?: 0f
        val max = (s?.attributes?.get("max_humidity") as? Number)?.toFloat() ?: 100f
        return min to if (max > min) max else min + 1f
    }

    override fun render(s: EntityState?) {
        val on = s?.state == "on"
        val action = s?.attributes?.get("action") as? String
        setShape(if (action == "drying") iconOr("mdi:air-humidifier-off", "mdi:water") else iconOr("mdi:air-humidifier", "mdi:water"),
            ctx.theme.activeColor("humidifier"), on)
        val target = (s?.attributes?.get("humidity") as? Number)?.toInt()
        val currentH = (s?.attributes?.get("current_humidity") as? Number)?.toInt()
        val parts = listOfNotNull(
            s?.let { titleCase(it.state) },
            currentH?.let { "$it%" },
            target?.let { "Target $it%" }.takeIf { on },
        )
        setSecondary(parts.joinToString(" · ").ifEmpty { "…" })
        if (target != null) {
            val (min, max) = range(s)
            slider?.setValue(((target - min) / (max - min)).coerceIn(0f, 1f), active = on)
        }
        collapseWhen(!on)
    }
}

// ---------------------------------------------------------------------------------------------------------------
// custom:mushroom-number-card
// ---------------------------------------------------------------------------------------------------------------

/**
 * Native `custom:mushroom-number-card` for `number` and `input_number`: a slider (`display_mode: slider`, the
 * default) or `−` value `+` buttons (`display_mode: buttons`), both within the entity's `min`/`max`/`step`.
 */
class MushroomNumberCardView(ctx: VitroCardContext, config: CardConfig) : MushroomShellCardView(ctx, config) {
    private val buttons = config.raw.optString("display_mode", "").let { it == "buttons" }
    /** `--rgb-state-number`: Mushroom's blue, unless `icon_color` sets one. */
    private val color = MushroomColors.parse(config.raw.optString("icon_color").ifEmpty { "blue" }, ctx.theme) ?: ctx.theme.primaryColor
    private val valueLabel = label(ctx, 15f, ctx.theme.primaryTextColor, Inter.MEDIUM).apply { gravity = Gravity.CENTER }
    private var slider: HaHorizontalSliderView? = null

    init {
        val row = controlRow()
        if (buttons) {
            addCell(row, mushroomButton("mdi:minus", "Decrease") { step(-1) })
            addCell(row, valueLabel, 1.4f)
            addCell(row, mushroomButton("mdi:plus", "Increase") { step(+1) })
        } else {
            val s = HaHorizontalSliderView(ctx, color) { frac ->
                val st = eid?.let { ctx.store[it] }
                val (min, max, stepSize) = bounds(st)
                setValue(snap(min + frac * (max - min), min, max, stepSize))
            }
            s.mushroom = true
            s.inactiveColor = mushroomDisabled
            slider = s
            addCell(row, s, fill = true)
        }
        start()
    }

    private fun bounds(s: EntityState?): Triple<Double, Double, Double> {
        val min = (s?.attributes?.get("min") as? Number)?.toDouble() ?: 0.0
        val max = (s?.attributes?.get("max") as? Number)?.toDouble() ?: 100.0
        val st = (s?.attributes?.get("step") as? Number)?.toDouble()?.takeIf { it > 0 } ?: 1.0
        return Triple(min, if (max > min) max else min + st, st)
    }

    private fun snap(v: Double, min: Double, max: Double, step: Double): Double =
        (min + Math.round((v - min) / step) * step).coerceIn(min, max)

    /** The value the last press sent, until HA reports a state: quick presses add up instead of repeating. */
    private var pendingValue: Double? = null
    private var pendingSince = 0L

    private companion object {
        /** How long a pressed value waits for HA to report it before the buttons count from HA's state again. */
        const val PENDING_MS = 5_000L
    }

    private fun step(dir: Int) {
        val s = eid?.let { ctx.store[it] } ?: return
        val (min, max, stepSize) = bounds(s)
        // The pending target holds until HA reports it (an earlier press's ack doesn't end it), or for 5 s at most,
        // so a refused write doesn't keep the buttons ahead of the device. The 5 s count from the first unconfirmed press.
        val now = android.os.SystemClock.uptimeMillis()
        if (pendingValue == s.state.toDoubleOrNull() || now - pendingSince > PENDING_MS) pendingValue = null
        val cur = pendingValue ?: s.state.toDoubleOrNull() ?: min
        val next = snap(cur + dir * stepSize, min, max, stepSize)
        if (pendingValue == null) pendingSince = now
        pendingValue = next
        setValue(next)
    }

    private fun setValue(v: Double) {
        val id = eid ?: return
        val domain = id.substringBefore('.')
        val svcDomain = if (domain == "input_number") "input_number" else "number"
        val clean: Number = if (abs(v - Math.rint(v)) < 1e-9) v.toLong() else Math.round(v * 1e6) / 1e6
        call(svcDomain, "set_value") { it.put("value", clean) }
    }

    override fun render(s: EntityState?) {
        setShape(MdiIcons.resolveIconName(null, s).takeIf { s != null } ?: "mdi:tune", color, s != null && !s.isUnavailable)
        setSecondary(s?.formattedState() ?: "…")
        val (min, max, stepSize) = bounds(s)
        val v = s?.state?.toDoubleOrNull()
        // HA reported the value the last press asked for: nothing is pending any more (an earlier press's value
        // isn't it, so an ack of that one keeps the target).
        if (v != null && pendingValue == v) pendingValue = null
        val text = when {
            v == null -> s?.state ?: "…"
            // Whole numbers only when the step, the range's start and the value all are: `min: 0.5, step: 1` shows 1.5.
            stepSize % 1.0 == 0.0 && min % 1.0 == 0.0 && v % 1.0 == 0.0 -> v.roundToInt().toString()
            else -> v.toString()
        }
        valueLabel.text = s?.unit?.let { "$text $it" } ?: text
        if (v != null) slider?.setValue(((v - min) / (max - min)).toFloat().coerceIn(0f, 1f), active = true)
        controls.visibility = if (s == null || s.isUnavailable) GONE else VISIBLE
    }
}

// ---------------------------------------------------------------------------------------------------------------
// custom:mushroom-light-card, -fan-card, -cover-card with their controls
// ---------------------------------------------------------------------------------------------------------------

/** Mushroom's state separator in a secondary line ("Open ⸱ 100%"). */
private const val MUSH_SEPARATOR = " \u2E31 "

/** Mushroom 5.3.0's `isActive` (`qo`): what its cards draw in color rather than with the disabled look. */
internal fun mushroomActive(s: EntityState): Boolean {
    if (s.domain in setOf("button", "input_button", "scene")) return s.state != "unavailable"
    if (s.state in setOf("unavailable", "unknown", "off")) return false
    return when (s.domain) {
        "cover", "valve" -> s.state != "closed" && s.state != "closing"
        "device_tracker", "person" -> s.state != "not_home"
        "media_player" -> s.state != "standby"
        "vacuum" -> s.state !in setOf("idle", "docked", "paused")
        "plant" -> s.state == "problem"
        else -> true
    }
}

/**
 * The icon color of Mushroom 5.3.0's entity, light, fan and cover cards (their `renderIcon`), or null for Mushroom's
 * `--rgb-disabled`: the entity card `--rgb-state-entity` (blue) while active, the light card `--rgb-state-light`
 * (orange, or the light's own color with `use_light_color`) while on, the fan card `--rgb-state-fan` (green) while on,
 * the cover card `--rgb-state-cover-open` (blue) while open or opening.
 */
internal fun mushroomIconColor(type: String, raw: org.json.JSONObject, s: EntityState?, theme: dev.glassworks.engine.vitro.theme.ResolvedTheme): Int? {
    if (s == null) return null
    fun named(name: String, fallback: Long) = MushroomColors.parse(name, theme) ?: fallback.toInt()
    return when (type) {
        "custom:mushroom-cover-card" -> if (s.state == "open" || s.state == "opening") named("blue", 0xFF2196F3) else null
        "custom:mushroom-light-card" -> if (!mushroomActive(s)) null
            else (if (raw.optBoolean("use_light_color", false)) s.lightTintColor() else null) ?: named("orange", 0xFFFF9800)
        "custom:mushroom-fan-card" -> if (mushroomActive(s)) named("green", 0xFF4CAF50) else null
        else -> if (mushroomActive(s)) named("blue", 0xFF2196F3) else null
    }
}

/**
 * The state line Mushroom 5.3.0 writes under a card's name: HA's `formatEntityState` (no tile extras), except that
 * its light card shows the brightness whenever the light reports one, its fan card the percentage of a fan that is
 * on, and its cover card the state and a position that isn't 0 ("Open ⸱ 50%", "Closing ⸱ 30%").
 */
internal fun mushroomStateLine(type: String, s: EntityState): String {
    fun percent(n: Int) = "${HaFormat.formatNumber(n)}${HaFormat.blankBeforePercent(HaLocale.current)}%"
    val plain = s.plainState()
    return when (type) {
        "custom:mushroom-light-card" -> s.brightnessPct?.let { percent(it) } ?: plain
        "custom:mushroom-fan-card" -> (s.attributes["percentage"] as? Number)?.toDouble()?.takeIf { s.state == "on" }
            ?.let { percent(it.roundToInt()) } ?: plain
        "custom:mushroom-cover-card" -> (s.attributes["current_position"] as? Number)?.toDouble()?.roundToInt()
            ?.takeIf { it != 0 }?.let { plain + MUSH_SEPARATOR + percent(it) } ?: plain
        else -> plain
    }
}

/**
 * `custom:mushroom-light-card` with controls (without, [TileCardView] draws it): Mushroom's light card, the state
 * (or the brightness) under the name and, under them, the brightness, color temperature or color slider; with more
 * than one, buttons switch between them. Off, the shape and the slider take the disabled color.
 */
class MushroomLightCardView(ctx: VitroCardContext, config: CardConfig) : MushroomShellCardView(ctx, config) {
    private val wanted = listOfNotNull(
        "brightness_control".takeIf { config.raw.optBoolean("show_brightness_control") },
        "color_temp_control".takeIf { config.raw.optBoolean("show_color_temp_control") },
        "color_control".takeIf { config.raw.optBoolean("show_color_control") },
    )
    private val row = controlRow()
    private var activeControl: String? = null
    private val slider = mushroomSlider(ORANGE) { frac -> commit(frac) }
    private val switchButtons = LinkedHashMap<String, View>()

    init {
        addCell(row, slider, fill = true)
        start()
    }

    /** Mushroom's `_controls`: the wanted controls the light supports. */
    private fun controlsFor(s: EntityState?): List<String> {
        val modes = (s?.attributes?.get("supported_color_modes") as? List<*>)?.map { it.toString() }.orEmpty()
        return wanted.filter { c ->
            when (c) {
                "brightness_control" -> modes.any { it != "onoff" } || s?.attributes?.containsKey("brightness") == true
                "color_temp_control" -> "color_temp" in modes
                else -> modes.any { it in COLOR_MODES }
            }
        }
    }

    private fun commit(frac: Float) {
        when (activeControl) {
            "color_temp_control" -> {
                val s = eid?.let { ctx.store[it] }
                val min = (s?.attributes?.get("min_color_temp_kelvin") as? Number)?.toDouble() ?: 2700.0
                val max = (s?.attributes?.get("max_color_temp_kelvin") as? Number)?.toDouble() ?: 6500.0
                call("light", "turn_on") { it.put("color_temp_kelvin", (min + frac * (max - min)).roundToInt()) }
            }
            "color_control" -> call("light", "turn_on") { it.put("hs_color", JSONArray().put((frac * 360f).roundToInt()).put(100)) }
            else -> {
                val pct = (frac * 100f).roundToInt()
                if (pct == 0) call("light", "turn_off") else call("light", "turn_on") { it.put("brightness_pct", pct) }
            }
        }
    }

    override fun render(s: EntityState?) {
        val on = s?.isActive == true
        val color = (if (config.raw.optBoolean("use_light_color", false)) s?.lightTintColor() else null) ?: ORANGE
        val icon = MdiIcons.resolveIconName(null, s)
        if (on) setShape(icon, color, true) else setShapeDisabled(icon)
        val brightness = (s?.attributes?.get("brightness") as? Number)?.toDouble()
        setSecondary(s?.let { mushroomStateLine(config.type, it) } ?: "…")
        val available = controlsFor(s)
        if (activeControl !in available) activeControl = available.firstOrNull()
        // The other controls as buttons beside the active one.
        if (switchButtons.keys != available.toSet()) {
            switchButtons.values.forEach { row.removeView(it) }
            switchButtons.clear()
            if (available.size > 1) available.forEach { c ->
                val b = mushroomButton(CONTROL_ICONS.getValue(c), c) { activeControl = c; eid?.let { render(ctx.store[it]) } }
                switchButtons[c] = b
                row.addView(b, LinearLayout.LayoutParams(ctx.px(42f), ctx.px(42f)).apply { marginStart = ctx.px(12f) })
            }
        }
        switchButtons.forEach { (c, b) -> b.visibility = if (c == activeControl) GONE else VISIBLE }
        when (activeControl) {
            "color_temp_control" -> {
                val min = (s?.attributes?.get("min_color_temp_kelvin") as? Number)?.toInt() ?: 2700
                val max = (s?.attributes?.get("max_color_temp_kelvin") as? Number)?.toInt() ?: 6500
                slider.trackGradient = IntArray(5) { i -> dev.glassworks.engine.vitro.data.LightColor.kelvinToArgb(min + (max - min) * i / 4) }
                val k = (s?.attributes?.get("color_temp_kelvin") as? Number)?.toDouble()
                slider.setValue(if (k == null) 0f else ((k - min) / (max - min)).toFloat(), active = on && k != null)
            }
            "color_control" -> {
                slider.trackGradient = IntArray(7) { i -> dev.glassworks.engine.vitro.data.LightColor.hsvToArgb(i * 60f, 1f, 1f) }
                val hue = ((s?.attributes?.get("hs_color") as? List<*>)?.firstOrNull() as? Number)?.toFloat()
                slider.setValue((hue ?: 0f) / 360f, active = on && hue != null)
            }
            else -> {
                slider.trackGradient = null
                slider.setAccentColor(color)
                slider.setValue(if (on && brightness != null) (brightness / 255.0).toFloat() else 0f, active = on)
            }
        }
        controls.visibility = if (available.isEmpty()) GONE else VISIBLE
        collapseWhen(!on)
        if (available.isEmpty()) controls.visibility = GONE
    }

    private companion object {
        /** `--rgb-state-light`: Mushroom's orange. */
        val ORANGE = 0xFFFF9800.toInt()
        val COLOR_MODES = setOf("hs", "rgb", "rgbw", "rgbww", "xy")
        val CONTROL_ICONS = mapOf("brightness_control" to "mdi:brightness-4", "color_temp_control" to "mdi:thermometer", "color_control" to "mdi:palette")
    }
}

/**
 * `custom:mushroom-fan-card` with controls: the percentage under the name, and the percentage slider (green), the
 * oscillate and the direction buttons Mushroom shows for `show_percentage_control`, `show_oscillate_control` and
 * `show_direction_control`.
 */
class MushroomFanCardView(ctx: VitroCardContext, config: CardConfig) : MushroomShellCardView(ctx, config) {
    private val row = controlRow()
    private val slider = if (config.raw.optBoolean("show_percentage_control")) mushroomSlider(GREEN) { frac ->
        val pct = (frac * 100f).roundToInt()
        if (pct == 0) call("fan", "turn_off") else call("fan", "set_percentage") { it.put("percentage", pct) }
    } else null
    private val oscillate = if (config.raw.optBoolean("show_oscillate_control")) mushroomButton("mdi:arrow-oscillating", "Oscillate") {
        val on = eid?.let { ctx.store[it] }?.attributes?.get("oscillating") == true
        call("fan", "oscillate") { it.put("oscillating", !on) }
    } else null
    private val direction = if (config.raw.optBoolean("show_direction_control")) mushroomButton("mdi:rotate-right", "Direction") {
        val forward = eid?.let { ctx.store[it] }?.attributes?.get("direction") != "reverse"
        call("fan", "set_direction") { it.put("direction", if (forward) "reverse" else "forward") }
    } else null

    init {
        slider?.let { addCell(row, it, fill = true) }
        listOfNotNull(oscillate, direction).forEach { b ->
            row.addView(b, LinearLayout.LayoutParams(if (slider == null) 0 else ctx.px(42f), ctx.px(42f), if (slider == null) 1f else 0f).apply {
                if (row.childCount > 0) marginStart = ctx.px(12f)
            })
        }
        start()
    }

    override fun render(s: EntityState?) {
        val on = s?.isActive == true
        val icon = MdiIcons.resolveIconName(null, s)
        if (on) setShape(icon, GREEN, true) else setShapeDisabled(icon)
        val pct = (s?.attributes?.get("percentage") as? Number)?.toDouble()
        setSecondary(s?.let { mushroomStateLine(config.type, it) } ?: "…")
        slider?.setValue(if (on && pct != null) (pct / 100.0).toFloat() else 0f, active = on)
        collapseWhen(!on)
    }

    private companion object {
        /** `--rgb-state-fan`: Mushroom's green. */
        val GREEN = 0xFF4CAF50.toInt()
    }
}

/**
 * `custom:mushroom-cover-card` with controls: the state and position under the name ("Open ⸱ 100%"), and the open /
 * stop / close buttons (`show_buttons_control`) or the position slider (`show_position_control`).
 */
class MushroomCoverCardView(ctx: VitroCardContext, config: CardConfig) : MushroomShellCardView(ctx, config) {
    private val row = controlRow()
    /** Mushroom's `_controls`, in its order: with both, the buttons show first and a button switches to the other. */
    private val coverControls = listOfNotNull(
        "buttons_control".takeIf { config.raw.optBoolean("show_buttons_control") },
        "position_control".takeIf { config.raw.optBoolean("show_position_control") },
    )
    private var activeControl = coverControls.firstOrNull()
    private val slider = if ("position_control" in coverControls)
        mushroomSlider(BLUE) { frac -> call("cover", "set_cover_position") { it.put("position", (frac * 100f).roundToInt()) } } else null
    /**
     * `mushroom-cover-buttons-control` (Mushroom 5.3.0): open, stop and close, each only when the cover supports it
     * (`supported_features` OPEN 1, STOP 8, CLOSE 2) and each disabled while it can't act. Open and close: an
     * unavailable cover, or one fully open (`current_position` 100, else `open`) or opening (closed or closing), unless
     * its state is assumed ([CoverState]); stop: an unavailable cover only. Open and close take the device class's
     * icons (sideways for an awning, curtain, door or gate).
     */
    private val buttons: List<Pair<Int, FrameLayout>> = if ("buttons_control" in coverControls) listOf(
        COVER_OPEN to mushroomButton("mdi:arrow-up", "Open") { call("cover", "open_cover") },
        COVER_STOP to mushroomButton("mdi:stop", "Stop") { call("cover", "stop_cover") },
        COVER_CLOSE to mushroomButton("mdi:arrow-down", "Close") { call("cover", "close_cover") },
    ) else emptyList()
    private val switchButtons = LinkedHashMap<String, View>()

    /**
     * The state the controls were last chosen for ([available]). Declared before `init`: `start()` renders the stored
     * state there, and an initializer after it would reset what that render set (Greptile on #799: switching controls
     * then found no state, and hid every control).
     */
    private var lastState: EntityState? = null

    init {
        buttons.forEach { (_, b) -> addCell(row, b) }
        slider?.let { addCell(row, it, fill = true) }
        // The other control as a button beside the active one (Mushroom's _renderOtherControls).
        if (coverControls.size > 1) coverControls.forEach { c ->
            val b = mushroomButton(COVER_CONTROL_ICONS.getValue(c), c) { activeControl = c; showActive() }
            switchButtons[c] = b
            row.addView(b, LinearLayout.LayoutParams(ctx.px(42f), ctx.px(42f)).apply { marginStart = ctx.px(12f) })
        }
        showActive()
        start()
    }

    /**
     * Mushroom's `_controls` for [s]: the configured controls the cover supports (buttons with OPEN, CLOSE or STOP;
     * the position slider with SET_POSITION 4), none without a state.
     */
    private fun available(s: EntityState?): List<String> = if (s == null) emptyList() else coverControls.filter { c ->
        when (c) {
            "buttons_control" -> s.supports(COVER_OPEN or COVER_CLOSE or COVER_STOP)
            "position_control" -> s.supports(COVER_SET_POSITION)
            else -> false
        }
    }

    private fun showActive() {
        val available = available(lastState)
        // Mushroom's updateActiveControl: the active control stays while the cover has it, else the first it has.
        if (activeControl !in available) activeControl = available.firstOrNull()
        bindButtons(lastState)
        slider?.visibility = if (activeControl == "position_control") VISIBLE else GONE
        // Mushroom's next-control button: only while the cover has more than one control.
        switchButtons.forEach { (c, b) -> b.visibility = if (c == activeControl || c !in available || available.size < 2) GONE else VISIBLE }
    }

    override fun render(s: EntityState?) {
        // Mushroom: blue while open or opening, the disabled color otherwise; the controls collapse unless active.
        val open = s != null && mushroomActive(s)
        val icon = MdiIcons.resolveIconName(null, s)
        if (mushroomIconColor(config.type, config.raw, s, ctx.theme) != null) setShape(icon, BLUE, true) else setShapeDisabled(icon)
        val pos = (s?.attributes?.get("current_position") as? Number)?.toInt()
        // Mushroom: HA's state label, then a position that isn't 0 ("Open ⸱ 100%").
        setSecondary(s?.let { mushroomStateLine(config.type, it) } ?: "…")
        slider?.setValue((pos ?: 0) / 100f, active = open)
        lastState = s
        showActive()
        collapseWhen(!open)
    }

    /** The buttons [s] supports, shown while they are the active control, each enabled while it can act. */
    private fun bindButtons(s: EntityState?) {
        var first = true
        for ((bit, b) in buttons) {
            val shown = activeControl == "buttons_control" && s != null && s.supports(bit)
            b.visibility = if (shown) VISIBLE else GONE
            if (!shown || s == null) continue
            // mushroom-button-group: no gap where a button isn't drawn.
            (b.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                val start = if (first) 0 else ctx.px(10f)
                if (lp.marginStart != start) { lp.marginStart = start; b.layoutParams = lp }
            }
            first = false
            when (bit) {
                COVER_OPEN -> (b.getChildAt(0) as? MdiIconView)?.let { it.setIcon(CoverState.openIcon(s), it.tintColor) }
                COVER_CLOSE -> (b.getChildAt(0) as? MdiIconView)?.let { it.setIcon(CoverState.closeIcon(s), it.tintColor) }
            }
            setMushroomButtonEnabled(b, when (bit) {
                COVER_OPEN -> CoverState.canOpen(s)
                COVER_CLOSE -> CoverState.canClose(s)
                else -> s.state != "unavailable"
            })
        }
    }

    private companion object {
        /** `CoverEntityFeature`: OPEN, CLOSE and STOP. */
        const val COVER_OPEN = 1
        const val COVER_CLOSE = 2
        const val COVER_STOP = 8
        const val COVER_SET_POSITION = 4

        /** `--rgb-state-cover`: Mushroom's blue. */
        val BLUE = 0xFF2196F3.toInt()

        /** Mushroom's cover CONTROLS_ICONS. */
        val COVER_CONTROL_ICONS = mapOf("buttons_control" to "mdi:gesture-tap-button", "position_control" to "mdi:gesture-swipe-horizontal")
    }
}

/**
 * `custom:mushroom-climate-card`: Mushroom's climate card. The shape in the HVAC mode's color with the HVAC action's
 * badge, the state and the current temperature (and humidity) under the name ("Heat ⸱ 19.5 °C"), and the target
 * temperature control (− value +) or the `hvac_modes` buttons, with a button to switch between them.
 */
class MushroomClimateCardView(ctx: VitroCardContext, config: CardConfig) : MushroomShellCardView(ctx, config) {
    private val configModes = stringList(config.raw, "hvac_modes")
    private val row = controlRow()
    private var activeControl: String? = null
    private val tempValue = label(ctx, 14f, ctx.theme.primaryTextColor, 700).apply { gravity = Gravity.CENTER }
    /** mushroom-input-number: − value + on one 42 px pill. */
    private val tempControl = inputNumber(tempValue, null, "Decrease", "Increase") { step(it) }

    /**
     * A heat/cool range: Mushroom's two input numbers side by side, the low end washed in the heat color and the high
     * end in the cool color (`--rgb-state-climate-heat` / `-cool` at 5 %, the icons and the value in it).
     */
    private val lowValue = label(ctx, 14f, HEAT, 700).apply { gravity = Gravity.CENTER }
    private val highValue = label(ctx, 14f, COOL, 700).apply { gravity = Gravity.CENTER }
    private val rangeControl = android.widget.HorizontalScrollView(ctx.context).apply {
        // Mushroom's `.actions` scroll sideways (`overflow-x: auto`) when the inputs can't shrink further: a narrow
        // horizontal card keeps both values whole and lets the row scroll, as Mushroom does.
        isHorizontalScrollBarEnabled = false
        isFillViewport = true
        addView(LinearLayout(ctx.context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(inputNumber(lowValue, HEAT, "Decrease lower target temperature", "Increase lower target temperature") {
                stepRange(ClimateHandle.LOW, it)
            }, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
            addView(inputNumber(highValue, COOL, "Decrease upper target temperature", "Increase upper target temperature") {
                stepRange(ClimateHandle.HIGH, it)
            }, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { marginStart = ctx.px(10f) })
        }, FrameLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT))
    }

    /**
     * One mushroom-input-number around [value]: 6 px in, − and + as their icons with 4 px on the outer side, the value
     * between them at least 20 px wide (`flex-basis: 20px`, no shrinking), in [tint] (else the text color) on its 5 %
     * wash. It shrinks to that and no further.
     */
    private fun inputNumber(value: TextView, tint: Int?, minusLabel: String, plusLabel: String, onStep: (Int) -> Unit) =
        LinearLayout(ctx.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = ctx.px(6f)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = ctx.px(12f).toFloat()
                setColor(CardSurface.withAlpha(tint ?: ctx.theme.primaryTextColor, 0.05f))
            }
            fun stepper(icon: String, description: String, dir: Int) = FrameLayout(ctx.context).apply {
                isClickable = true
                contentDescription = description
                val outer = ctx.px(4f)
                if (dir < 0) setPadding(outer, 0, 0, 0) else setPadding(0, 0, outer, 0)
                addView(MdiIconView(ctx.context).apply { setIcon(icon, tint ?: ctx.theme.primaryTextColor) },
                    FrameLayout.LayoutParams(controlIconPx, controlIconPx, Gravity.CENTER))
                setOnClickListener { onStep(dir) }
                applyHaRipple(this, ctx.px(12f).toFloat(), ctx.theme.dark)
            }
            addView(stepper("mdi:minus", minusLabel, -1), LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT))
            value.minWidth = ctx.px(20f)
            addView(value, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(stepper("mdi:plus", plusLabel, +1), LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT))
            minimumWidth = 2 * pad + 2 * (ctx.px(4f) + controlIconPx) + ctx.px(20f)
        }

    private val modeRow = LinearLayout(ctx.context).apply { orientation = LinearLayout.HORIZONTAL }
    private val modeButtons = LinkedHashMap<String, FrameLayout>()
    private val switchButtons = LinkedHashMap<String, View>()

    init {
        row.addView(tempControl, LinearLayout.LayoutParams(0, ctx.px(42f), 1f))
        row.addView(rangeControl, LinearLayout.LayoutParams(0, ctx.px(42f), 1f))
        row.addView(modeRow, LinearLayout.LayoutParams(0, ctx.px(42f), 1f))
        CONTROL_ICONS.forEach { (c, icon) ->
            val b = mushroomButton(icon, if (c == "temperature_control") "Temperature" else "HVAC modes") {
                activeControl = c
                eid?.let { render(ctx.store[it]) }
            }
            switchButtons[c] = b
            row.addView(b, LinearLayout.LayoutParams(ctx.px(42f), ctx.px(42f)).apply { marginStart = ctx.px(12f) })
        }
        start()
    }

    private fun step(dir: Int) {
        val id = eid ?: return
        val s = ctx.store[id] ?: return
        val cur = (s.attributes["temperature"] as? Number)?.toDouble() ?: return
        val step = stepOf(s)
        val min = (s.attributes["min_temp"] as? Number)?.toDouble() ?: Double.NEGATIVE_INFINITY
        val max = (s.attributes["max_temp"] as? Number)?.toDouble() ?: Double.POSITIVE_INFINITY
        val next = (Math.round((cur + dir * step) / step) * step).coerceIn(min, max)
        // Optimistic, so a second press steps from the first one's value, as Mushroom's own pending value does.
        ctx.callOptimistic(ClimateTargets.withValue(s, next), "climate", "set_temperature",
            JSONObject().put("entity_id", id).put("temperature", next))
    }

    /**
     * One end of the range a step up or down: Mushroom sends that end with the other one as it is; the end stays
     * within the device's min and max and, as HA's climate service requires, the low end never passes the high one.
     */
    private fun stepRange(handle: ClimateHandle, dir: Int) {
        val id = eid ?: return
        val s = ctx.store[id] ?: return
        val targets = ClimateTargets.of(s.attributes)
        // A single target wins: a range button left from before the state changed sends nothing.
        if (!targets.isRange) return
        val low = targets.low ?: return
        val high = targets.high ?: return
        val min = (s.attributes["min_temp"] as? Number)?.toDouble() ?: Double.NEGATIVE_INFINITY
        val max = (s.attributes["max_temp"] as? Number)?.toDouble() ?: Double.POSITIVE_INFINITY
        val (lo, hi) = ClimateTargets.stepped(handle, dir * stepOf(s), low, high, min, max)
        if (lo == low && hi == high) return
        ctx.callOptimistic(ClimateTargets.withRange(s, lo, hi), "climate", "set_temperature", ClimateTargets.rangeData(id, lo, hi))
    }

    /** `target_temp_step`, else HA's default: 0.5 in °C, 1 in °F. */
    private fun stepOf(s: EntityState): Double = (s.attributes["target_temp_step"] as? Number)?.toDouble()
        ?: if ((s.attributes["temperature_unit"] as? String)?.contains("F") == true) 1.0 else 0.5

    /** Mushroom's `_controls`: the temperature with a target to show, the modes the entity has and the card lists. */
    private fun controlsFor(s: EntityState?): List<String> {
        if (s == null) return emptyList()
        val out = ArrayList<String>(2)
        // A target to show, read as the control reads it (a range needs its feature bit).
        val target = ClimateTargets.of(s.attributes).let { it.value != null || it.isRange }
        if (target && config.raw.optBoolean("show_temperature_control")) out += "temperature_control"
        if (modesOf(s).isNotEmpty()) out += "hvac_mode_control"
        return out
    }

    private fun modesOf(s: EntityState): List<String> =
        (s.attributes["hvac_modes"] as? List<*>)?.map { it.toString() }.orEmpty()
            .filter { it in configModes }.sortedBy { MODE_ORDER.indexOf(it).let { i -> if (i < 0) 99 else i } }

    override fun render(s: EntityState?) {
        val state = s?.state.orEmpty()
        val color = MODE_COLORS[state] ?: ctx.theme.disabledTextColor
        val icon = MdiIcons.resolveIconName(null, s).takeIf { s != null } ?: "mdi:thermostat"
        if (s != null && !s.isUnavailable) setShape(icon, color, true) else setShapeDisabled(icon)
        val action = s?.attributes?.get("hvac_action") as? String
        val badgeIcon = action?.takeIf { it != "off" && s?.isUnavailable == false }?.let { ACTION_ICONS[it] }
        setBadge(badgeIcon, ACTION_COLORS[action] ?: ctx.theme.disabledTextColor)
        val unit = (s?.attributes?.get("temperature_unit") as? String) ?: "°C"
        // HA's label for the mode ("Heat"), not the action.
        val line = StringBuilder(s?.let { dev.glassworks.engine.vitro.data.HaDisplay.stateLabel(it) ?: it.state.replace('_', ' ').replaceFirstChar { c -> c.titlecase(Locale.ROOT) } } ?: "…")
        (s?.attributes?.get("current_temperature") as? Number)?.let { line.append(MUSH_SEPARATOR).append("${formatNumber(it.toDouble())} $unit") }
        (s?.attributes?.get("current_humidity") as? Number)?.let { line.append(MUSH_SEPARATOR).append("${formatNumber(it.toDouble())} %") }
        setSecondary(line.toString())

        val available = controlsFor(s)
        if (activeControl !in available) activeControl = available.firstOrNull()
        // Mushroom: the single target when there is one, else the range.
        val targets = s?.let { ClimateTargets.of(it.attributes) }
        val range = targets?.isRange == true
        tempControl.visibility = if (activeControl == "temperature_control" && !range) VISIBLE else GONE
        rangeControl.visibility = if (activeControl == "temperature_control" && range) VISIBLE else GONE
        modeRow.visibility = if (activeControl == "hvac_mode_control") VISIBLE else GONE
        switchButtons.forEach { (c, b) -> b.visibility = if (available.size > 1 && c != activeControl && c in available) VISIBLE else GONE }
        if (s != null) {
            val target = (s.attributes["temperature"] as? Number)?.toDouble()
            val decimals = if (stepOf(s) % 1.0 == 0.0) 0 else 1
            tempValue.text = target?.let { HaDisplay.formatFixed(it, decimals) } ?: "–"
            lowValue.text = targets?.low?.let { HaDisplay.formatFixed(it, decimals) } ?: "–"
            highValue.text = targets?.high?.let { HaDisplay.formatFixed(it, decimals) } ?: "–"
            val modes = modesOf(s)
            if (modeButtons.keys.toList() != modes) {
                modeRow.removeAllViews()
                modeButtons.clear()
                modes.forEach { m ->
                    val b = mushroomButton(MODE_ICONS[m] ?: "mdi:thermostat", m) { call("climate", "set_hvac_mode") { it.put("hvac_mode", m) } }
                    // Mushroom's mode buttons pick one mode: radios, checked on the current one.
                    dev.glassworks.engine.vitro.ui.VitroAccess.role(b, dev.glassworks.engine.vitro.ui.A11yRole.RADIO) { eid?.let { ctx.store[it] }?.state == m }
                    modeButtons[m] = b
                    addCell(modeRow, b)
                }
            }
            // The current mode: its icon in the mode's color, the button washed with it at 20% (off: grey).
            modeButtons.forEach { (m, b) ->
                val mc = if (m == "off") GREY else MODE_COLORS[m] ?: ctx.theme.disabledTextColor
                val current = m == state
                (b.getChildAt(0) as? MdiIconView)?.setIcon(MODE_ICONS[m] ?: "mdi:thermostat", if (current) mc else ctx.theme.primaryTextColor)
                (b.background as? GradientDrawable)?.setColor(CardSurface.withAlpha(if (current) mc else ctx.theme.primaryTextColor, if (current) 0.2f else 0.05f))
            }
        }
        controls.visibility = if (available.isEmpty()) GONE else VISIBLE
        if (available.isNotEmpty()) collapseWhen(state == "off")
    }

    private companion object {
        val MODE_ORDER = listOf("auto", "heat_cool", "heat", "cool", "dry", "fan_only", "off")
        val MODE_COLORS = mapOf(
            "auto" to 0xFF4CAF50.toInt(), "cool" to 0xFF2196F3.toInt(), "dry" to 0xFFFF9800.toInt(),
            "fan_only" to 0xFF009688.toInt(), "heat" to 0xFFFF6F22.toInt(), "heat_cool" to 0xFF4CAF50.toInt(),
        )
        val ACTION_COLORS = mapOf("cooling" to 0xFF2196F3.toInt(), "drying" to 0xFFFF9800.toInt(), "heating" to 0xFFFF6F22.toInt())
        val MODE_ICONS = mapOf(
            "auto" to "mdi:thermostat-auto", "cool" to "mdi:snowflake", "dry" to "mdi:water-percent", "fan_only" to "mdi:fan",
            "heat" to "mdi:fire", "heat_cool" to "mdi:sun-snowflake-variant", "off" to "mdi:power",
        )
        val ACTION_ICONS = mapOf("cooling" to "mdi:snowflake", "drying" to "mdi:water-percent", "heating" to "mdi:fire", "idle" to "mdi:clock-outline")
        val CONTROL_ICONS = mapOf("temperature_control" to "mdi:thermometer", "hvac_mode_control" to "mdi:thermostat")
        val GREY = 0xFF9E9E9E.toInt()
        val HEAT = MODE_COLORS.getValue("heat")
        val COOL = MODE_COLORS.getValue("cool")

        fun formatNumber(v: Double): String =
            dev.glassworks.engine.vitro.data.HaDisplay.formatValue(v, 1)
    }
}

// ---------------------------------------------------------------------------------------------------------------
// custom:mushroom-select-card
// ---------------------------------------------------------------------------------------------------------------

/**
 * Native `custom:mushroom-select-card` for `select` and `input_select`: the current option in a dropdown pill;
 * picking another calls `select_option` on the entity's own domain.
 */
class MushroomSelectCardView(ctx: VitroCardContext, config: CardConfig) : MushroomShellCardView(ctx, config) {
    // ha-control-select-menu: --control-select-menu-background-color (the disabled color) at 20%, the value 14 px
    // regular 20 px in, a 20 px arrow 10 px from the end, both in the text color.
    private val current = label(ctx, 14f, ctx.theme.primaryTextColor)
    private val dropdown = LinearLayout(ctx.context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        contentDescription = "Choose option"
        setPadding(ctx.px(20f), 0, ctx.px(10f), 0)
        val menuColor = dev.glassworks.engine.vitro.theme.ThemeResolver.parseColor(
            ctx.theme.vars["disabled-color"]?.let { dev.glassworks.engine.vitro.theme.ThemeResolver.resolveValue(it, ctx.theme.vars, 0) },
        ) ?: if (ctx.theme.dark) 0xFF464646.toInt() else 0xFFBDBDBD.toInt()
        background = GradientDrawable().apply {
            cornerRadius = ctx.px(12f).toFloat()
            setColor(CardSurface.withAlpha(menuColor, 0.2f))
        }
    }

    init {
        dropdown.addView(current, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = ctx.px(10f) })
        dropdown.addView(MdiIconView(ctx.context).apply { setIcon("mdi:menu-down", ctx.theme.primaryTextColor) },
            LinearLayout.LayoutParams(ctx.px(20f), ctx.px(20f)))
        dropdown.setOnClickListener { showMenu() }
        addCell(controlRow(), dropdown)
        start()
    }

    /** The entity's `options`. */
    fun options(): List<String> =
        (eid?.let { ctx.store[it] }?.attributes?.get("options") as? List<*>)?.mapNotNull { it?.toString() }.orEmpty()

    /** Picks [option] as the dropdown does. */
    fun choose(option: String) {
        val id = eid ?: return
        val domain = if (id.substringBefore('.') == "input_select") "input_select" else "select"
        call(domain, "select_option") { it.put("option", option) }
    }

    private fun showMenu() {
        val opts = options()
        if (opts.isEmpty()) return
        runCatching {
            PopupMenu(ctx.context, dropdown).apply {
                opts.forEachIndexed { i, o -> menu.add(0, i, i, o) }
                setOnMenuItemClickListener { item -> choose(opts[item.itemId]); true }
            }.show()
        }
    }

    override fun render(s: EntityState?) {
        // `--rgb-state-entity`: Mushroom's blue. The state shows under the name as well as in the dropdown.
        setShape(MdiIcons.resolveIconName(null, s).takeIf { s != null } ?: "mdi:format-list-bulleted", BLUE, s != null && !s.isUnavailable)
        setSecondary(if (s == null) "…" else s.state)
        current.text = s?.state.orEmpty()
        controls.visibility = if (s == null || s.isUnavailable) GONE else VISIBLE
    }

    private companion object {
        val BLUE = 0xFF2196F3.toInt()
    }
}

// ---------------------------------------------------------------------------------------------------------------
// custom:mini-media-player
// ---------------------------------------------------------------------------------------------------------------

/**
 * Native `custom:mini-media-player` (kalkih): `artwork: default` (thumbnail), `cover` / `full-cover` (art behind a
 * dark scrim), `material` (art on the right) or `none`; a header with the icon, name, "title — artist" and power;
 * mute + volume slider (or ± with `volume_stateless`), previous / play-pause / next (and stop with
 * `hide: { stop: false }`), and a ticking progress line. `hide:` turns off `icon`, `name`, `info`, `power`,
 * `volume`, `mute`, `controls`, `prev`, `next`, `play_pause` and `progress`.
 */
class MiniMediaPlayerCardView(ctx: VitroCardContext, private val config: CardConfig) : CardSurface(ctx) {
    private val eid = config.entity
    private val hide = config.raw.optJSONObject("hide") ?: JSONObject()
    private val artworkMode = config.raw.optString("artwork", "default").ifEmpty { "default" }
    private val stateless = config.raw.optBoolean("volume_stateless", false)
    private val backdrop = ImageView(ctx.context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; visibility = GONE }
    private val scrim = View(ctx.context).apply {
        visibility = GONE
        background = if (artworkMode == "material") {
            GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(ctx.theme.cardBackgroundColor, CardSurface.withAlpha(ctx.theme.cardBackgroundColor, 0f)))
        } else {
            GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x66000000, 0xCC000000.toInt()))
        }
    }
    private val iconView = MdiIconView(ctx.context)
    private val thumb = ImageView(ctx.context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; visibility = GONE; clipToOutline = true }
    private val iconBox = FrameLayout(ctx.context)
    private val nameView = label(ctx, 14f, ctx.theme.primaryTextColor, Inter.MEDIUM)
    private val infoView = label(ctx, 12f, ctx.theme.secondaryTextColor)
    private val powerIcon = MdiIconView(ctx.context)
    private val powerBtn = iconButton(powerIcon, "Power")
    private val muteIcon = MdiIconView(ctx.context)
    private val muteBtn = iconButton(muteIcon, "Mute")
    private val prevIcon = MdiIconView(ctx.context)
    private val prevBtn = iconButton(prevIcon, "Previous track")
    private val playIcon = MdiIconView(ctx.context)
    private val playBtn = iconButton(playIcon, "Play")
    private val nextIcon = MdiIconView(ctx.context)
    private val nextBtn = iconButton(nextIcon, "Next track")
    private val stopIcon = MdiIconView(ctx.context)
    private val stopBtn = iconButton(stopIcon, "Stop")
    private val volDownIcon = MdiIconView(ctx.context)
    private val volDownBtn = iconButton(volDownIcon, "Volume down")
    private val volUpIcon = MdiIconView(ctx.context)
    private val volUpBtn = iconButton(volUpIcon, "Volume up")
    private val controlsRow = LinearLayout(ctx.context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val progress = MediaProgressView(ctx)
    private var slider: HaHorizontalSliderView? = null
    private var artUrl: String? = null
    private var last: EntityState? = null
    private var ticking = false
    private val tick = object : Runnable {
        override fun run() {
            if (!ticking) return
            updateProgress(last)
            ctx.main.postDelayed(this, 1_000L)
        }
    }

    private fun hidden(key: String, default: Boolean = false): Boolean = hide.optBoolean(key, default)

    init {
        if (artworkMode == "full-cover") minimumHeight = ctx.px(160f)
        if (artworkMode == "material") {
            addView(backdrop, LayoutParams(ctx.px(150f), MATCH_PARENT, Gravity.END))
            addView(scrim, LayoutParams(ctx.px(150f), MATCH_PARENT, Gravity.END))
        } else {
            addView(backdrop, LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(scrim, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        val content = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (artworkMode == "full-cover") Gravity.BOTTOM else Gravity.TOP
            setPadding(ctx.px(14f), ctx.px(12f), ctx.px(10f), ctx.px(10f))
        }
        iconBox.addView(iconView, FrameLayout.LayoutParams(ctx.px(24f), ctx.px(24f), Gravity.CENTER))
        iconBox.addView(thumb, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val texts = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.VERTICAL
            addView(nameView)
            addView(infoView, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ctx.px(3f) })
        }
        val header = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            if (!hidden("icon")) addView(iconBox, LinearLayout.LayoutParams(ctx.px(40f), ctx.px(40f)).apply { marginEnd = ctx.px(12f) })
            addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            if (!hidden("power")) addView(powerBtn, LinearLayout.LayoutParams(ctx.px(36f), ctx.px(36f)))
        }
        if (hidden("name")) nameView.visibility = GONE
        if (hidden("info")) infoView.visibility = GONE
        content.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val btn = ctx.px(36f)
        if (!hidden("volume")) {
            if (!hidden("mute")) controlsRow.addView(muteBtn, LinearLayout.LayoutParams(btn, btn))
            if (stateless) {
                controlsRow.addView(volDownBtn, LinearLayout.LayoutParams(btn, btn))
                controlsRow.addView(volUpBtn, LinearLayout.LayoutParams(btn, btn))
                controlsRow.addView(Space(ctx.context), LinearLayout.LayoutParams(0, 1, 1f))
            } else {
                val s = HaHorizontalSliderView(ctx, ctx.theme.primaryColor) { frac ->
                    call("volume_set") { it.put("volume_level", (frac * 100f).roundToInt() / 100.0) }
                }
                slider = s
                controlsRow.addView(s, LinearLayout.LayoutParams(0, ctx.px(28f), 1f).apply { marginStart = ctx.px(4f); marginEnd = ctx.px(8f) })
            }
        } else {
            controlsRow.addView(Space(ctx.context), LinearLayout.LayoutParams(0, 1, 1f))
        }
        if (!hidden("controls")) {
            if (!hidden("prev")) controlsRow.addView(prevBtn, LinearLayout.LayoutParams(btn, btn))
            if (!hidden("play_pause")) controlsRow.addView(playBtn, LinearLayout.LayoutParams(btn, btn))
            if (!hidden("stop", true)) controlsRow.addView(stopBtn, LinearLayout.LayoutParams(btn, btn))
            if (!hidden("next")) controlsRow.addView(nextBtn, LinearLayout.LayoutParams(btn, btn))
        }
        content.addView(controlsRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ctx.px(6f) })
        addView(content, LayoutParams(MATCH_PARENT, if (artworkMode == "full-cover") MATCH_PARENT else WRAP_CONTENT))
        addView(progress, LayoutParams(MATCH_PARENT, ctx.px(3f), Gravity.BOTTOM))

        isClickable = true
        setOnClickListener { ctx.performAction(ActionConfig.parse(config.raw.optJSONObject("tap_action")), eid) }
        bindHoldAction(cardHoldAction(config.raw, eid), eid)
        powerBtn.setOnClickListener { call(if (last?.state == "off" || last == null) "turn_on" else "turn_off") }
        muteBtn.setOnClickListener {
            val muted = last?.attributes?.get("is_volume_muted") == true
            call("volume_mute") { it.put("is_volume_muted", !muted) }
        }
        prevBtn.setOnClickListener { call("media_previous_track") }
        playBtn.setOnClickListener { call("media_play_pause") }
        nextBtn.setOnClickListener { call("media_next_track") }
        stopBtn.setOnClickListener { call("media_stop") }
        volDownBtn.setOnClickListener { call("volume_down") }
        volUpBtn.setOnClickListener { call("volume_up") }
        if (eid != null) observe(eid) { render(it) } else render(null)
    }

    private fun iconButton(icon: MdiIconView, description: String): FrameLayout = FrameLayout(ctx.context).apply {
        isClickable = true
        contentDescription = description
        addView(icon, FrameLayout.LayoutParams(ctx.px(22f), ctx.px(22f), Gravity.CENTER))
    }

    private fun call(service: String, extra: (JSONObject) -> Unit = {}) {
        val id = eid ?: return
        ctx.callService("media_player", service, JSONObject().put("entity_id", id).also(extra), null)
    }

    private fun render(s: EntityState?) {
        last = s
        val attrs = s?.attributes.orEmpty()
        val off = s == null || s.state == "off" || s.isUnavailable
        val playing = s?.state == "playing"
        val picture = if (s == null || off || artworkMode == "none") null else s.entityPicture
        setArtwork(picture)
        val backdropShown = backdrop.visibility == VISIBLE && artworkMode in setOf("cover", "full-cover")
        val fg = if (backdropShown) Color.WHITE else ctx.theme.primaryTextColor
        val fgSoft = if (backdropShown) CardSurface.withAlpha(Color.WHITE, 0.8f) else ctx.theme.secondaryTextColor

        val name = config.name(s) ?: s?.friendlyName ?: eid.orEmpty()
        nameView.text = name
        nameView.setTextColor(fg)
        val title = (attrs["media_title"] as? String)?.takeIf { it.isNotBlank() }
        val artist = (attrs["media_artist"] as? String)?.takeIf { it.isNotBlank() }
        infoView.text = when {
            off -> s?.let { titleCase(it.state) } ?: "…"
            title != null && artist != null -> "$title — $artist"
            title != null -> title
            else -> titleCase(s.state)
        }
        infoView.setTextColor(fgSoft)
        iconView.setIcon(config.icon ?: MdiIcons.resolveIconName(null, s).takeIf { s != null } ?: "mdi:cast", if (playing) ctx.theme.primaryColor else fgSoft)
        powerIcon.setIcon("mdi:power", if (off) fgSoft else fg)
        for (i in listOf(prevIcon to "mdi:skip-previous", nextIcon to "mdi:skip-next", stopIcon to "mdi:stop",
            volDownIcon to "mdi:volume-minus", volUpIcon to "mdi:volume-plus")) {
            i.first.setIcon(i.second, fg)
        }
        playIcon.setIcon(if (playing) "mdi:pause" else "mdi:play", fg)
        playBtn.contentDescription = if (playing) "Pause" else "Play"
        val muted = attrs["is_volume_muted"] == true
        muteIcon.setIcon(if (muted) "mdi:volume-off" else "mdi:volume-high", fg)
        muteBtn.contentDescription = if (muted) "Unmute" else "Mute"
        (attrs["volume_level"] as? Number)?.toFloat()?.let { slider?.setValue(it, active = !muted && it > 0f) }
        controlsRow.visibility = if (off) GONE else VISIBLE
        updateProgress(s)
        setTicking(playing && isAttachedToWindow && isShown)
    }

    private fun setArtwork(url: String?) {
        if (url == artUrl) return
        artUrl = url
        // The previous track's art goes at once: until (or unless) the new one loads, the icon shows.
        backdrop.setImageDrawable(null); backdrop.visibility = GONE; scrim.visibility = GONE
        thumb.setImageDrawable(null); thumb.visibility = GONE; iconView.visibility = VISIBLE
        iconBox.background = null
        if (url == null) return
        ctx.loadImage(url, this) { bmp ->
            if (artUrl != url) return@loadImage
            if (bmp == null) return@loadImage
            if (artworkMode == "default") {
                thumb.setImageBitmap(bmp)
                thumb.visibility = VISIBLE
                iconView.visibility = GONE
                iconBox.background = GradientDrawable().apply { cornerRadius = ctx.px(6f).toFloat(); setColor(Color.BLACK) }
            } else {
                backdrop.setImageBitmap(bmp)
                backdrop.visibility = VISIBLE
                scrim.visibility = VISIBLE
            }
            render(last)
        }
    }

    private fun updateProgress(s: EntityState?) {
        val f = if (hidden("progress")) null else mediaProgressFraction(s, ctx.now())
        if (f == null) {
            progress.visibility = GONE
            return
        }
        progress.visibility = VISIBLE
        progress.setFraction(f, backdrop.visibility == VISIBLE)
    }

    private fun setTicking(on: Boolean) {
        if (on == ticking) return
        ticking = on
        ctx.main.removeCallbacks(tick)
        if (on) ctx.main.postDelayed(tick, 1_000L)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        setTicking(last?.state == "playing" && isShown)
    }

    override fun onDetachedFromWindow() {
        setTicking(false)
        super.onDetachedFromWindow()
    }

    /** A cached view another one hides stays attached: the position stops ticking, and is caught up on return. */
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (!isAttachedToWindow) return
        if (isShown) updateProgress(last)
        setTicking(last?.state == "playing" && isShown)
    }
}

/** How far through its track a media player is (0..1), advanced live while playing; null without duration. */
internal fun mediaProgressFraction(s: EntityState?, nowMs: Long = System.currentTimeMillis()): Float? {
    if (s == null || s.state == "off" || s.isUnavailable) return null
    val attrs = s.attributes
    val duration = (attrs["media_duration"] as? Number)?.toDouble() ?: return null
    val position = (attrs["media_position"] as? Number)?.toDouble() ?: return null
    if (duration <= 0.0) return null
    var pos = position
    if (s.state == "playing") {
        val updatedAt = (attrs["media_position_updated_at"] as? String)?.let { raw ->
            runCatching { java.time.OffsetDateTime.parse(raw).toInstant().toEpochMilli() }.getOrNull()
        }
        if (updatedAt != null) pos += (nowMs - updatedAt).coerceAtLeast(0L) / 1000.0
    }
    return (pos / duration).toFloat().coerceIn(0f, 1f)
}

// ---------------------------------------------------------------------------------------------------------------
// custom:auto-entities
// ---------------------------------------------------------------------------------------------------------------

/**
 * auto-entities' filter, sort and value rules over [EntityState]s. Pure, so it is tested off-device.
 *
 * Rules (`filter.include` / `filter.exclude`) match on `domain`, `entity_id`, `state`, `name`, `attributes`,
 * `group`, and the `not` / `or` / `and` combinators. Values are exact, `*` globs, `/regex/`, or a comparison
 * (`< 5`, `>= 20`, `!= off`, `== on`). A rule with a key Vitro can't evaluate (`area`, `device`, `integration`,
 * `label`, `template`, …) matches nothing, so an include never widens to everything.
 */
object AutoEntitiesFilter {
    /** One matched entity and the `options` its include rule gives its row or card. */
    data class Match(val entityId: String, val options: JSONObject?)

    /** The rule keys Vitro evaluates; the compatibility check reports the others. */
    internal val RULE_KEYS = setOf("domain", "entity_id", "state", "name", "attributes", "group", "not", "or", "and", "options", "sort")

    fun evaluate(raw: JSONObject, states: Collection<EntityState>, lookup: (String) -> EntityState?): List<Match> {
        val out = ArrayList<Match>()
        // Plain `entities:` first, as auto-entities lists them.
        raw.optJSONArray("entities")?.let { arr ->
            for (i in 0 until arr.length()) {
                when (val e = arr.opt(i)) {
                    is String -> out += Match(e, null)
                    is JSONObject -> e.optString("entity", "").ifEmpty { null }?.let { out += Match(it, JSONObject(e.toString()).apply { remove("entity") }) }
                }
            }
        }
        val filter = raw.optJSONObject("filter") ?: JSONObject()
        val include = rules(filter.optJSONArray("include"))
        val exclude = rules(filter.optJSONArray("exclude"))
        val ordered = states.sortedBy { it.entityId }
        for (rule in include) {
            val opts = rule.optJSONObject("options")
            var ruleMatches = ordered.filter { matches(rule, it, lookup) }.map { Match(it.entityId, opts) }
            // A rule's own `sort` (and its `count`) applies to its matches before they join the others.
            rule.optJSONObject("sort")?.let { ruleMatches = sort(ruleMatches, it, lookup) }
            out += ruleMatches
        }
        var result = out.filter { m -> val s = lookup(m.entityId); s == null || exclude.none { matches(it, s, lookup) } }
        if (raw.optBoolean("unique", false)) result = result.distinctBy { it.entityId }
        raw.optJSONObject("sort")?.let { result = sort(result, it, lookup) }
        return result
    }

    private fun rules(arr: JSONArray?): List<JSONObject> =
        if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }

    fun matches(rule: JSONObject, s: EntityState, lookup: (String) -> EntityState?): Boolean {
        val keys = rule.keys().asSequence().toList()
        if (keys.none { it != "options" && it != "sort" }) return false
        for (k in keys) {
            val v = rule.opt(k)
            val ok = when (k) {
                "options", "sort" -> true
                "domain" -> matchValue(v, s.domain)
                "entity_id" -> matchValue(v, s.entityId)
                "state" -> matchValue(v, s.state)
                "name" -> matchValue(v, s.friendlyName)
                "attributes" -> (v as? JSONObject)?.let { attrs ->
                    attrs.keys().asSequence().all { a -> matchValue(attrs.opt(a), attributeString(s, a)) }
                } ?: false
                "group" -> (lookup(v?.toString().orEmpty())?.attributes?.get("entity_id") as? List<*>)?.any { it?.toString() == s.entityId } == true
                // `not` over a rule Vitro can't evaluate is no match either, never everything.
                "not" -> (v as? JSONObject)?.let { supported(it) && !matches(it, s, lookup) } ?: false
                "or" -> (v as? JSONArray)?.let { arr -> (0 until arr.length()).any { i -> arr.optJSONObject(i)?.let { matches(it, s, lookup) } == true } } ?: false
                "and" -> (v as? JSONArray)?.let { arr -> (0 until arr.length()).all { i -> arr.optJSONObject(i)?.let { matches(it, s, lookup) } == true } } ?: false
                else -> false
            }
            if (!ok) return false
        }
        return keys.all { it in RULE_KEYS }
    }

    /** True when every key of [rule], nested ones included, is one Vitro evaluates. */
    private fun supported(rule: JSONObject): Boolean =
        // An empty rule, or one whose value has the wrong shape (`attributes: "x"`), matches nothing; negated it
        // would match everything.
        rule.keys().asSequence().any { it != "options" && it != "sort" } &&
            rule.keys().asSequence().all { k ->
                k in RULE_KEYS && when (k) {
                    "not" -> (rule.opt(k) as? JSONObject)?.let(::supported) ?: false
                    "or", "and" -> (rule.opt(k) as? JSONArray)?.let { arr ->
                        (0 until arr.length()).all { i -> arr.optJSONObject(i)?.let(::supported) ?: false }
                    } ?: false
                    "attributes" -> (rule.opt(k) as? JSONObject)?.let { a -> a.keys().asSequence().all { isPattern(a.opt(it)) } } ?: false
                    "options", "sort" -> true
                    // domain, entity_id, state, name, group: one value to match (`state: null` or an object is no rule).
                    else -> isPattern(rule.opt(k))
                }
            }

    /**
     * A value a rule can match with: a scalar, and for a `/regex/` one that compiles. An invalid regex matches
     * nothing, so it can't be told apart from a valid miss once evaluated: under `not` it would match everything.
     */
    private fun isPattern(v: Any?): Boolean {
        if (v !is String) return v is Number || v is Boolean
        if (v.length >= 2 && v.startsWith("/") && v.endsWith("/")) return runCatching { Regex(v.substring(1, v.length - 1)) }.isSuccess
        return true
    }

    /** An attribute as text, `a:b` reaching into a nested map. */
    private fun attributeString(s: EntityState, key: String): String? {
        var cur: Any? = s.attributes
        for (part in key.split(':')) {
            cur = (cur as? Map<*, *>)?.get(part) ?: return null
        }
        return cur?.toString()
    }

    private val COMPARE = Regex("""^\s*(<=|>=|==|!=|<|>|=)\s*(.*)$""")

    /** auto-entities' value match: exact, glob, `/regex/`, or a comparison; JSON true/false read as on/off. */
    fun matchValue(pattern: Any?, value: String?): Boolean {
        if (value == null) return false
        val p = when (pattern) {
            null, JSONObject.NULL -> return false
            // YAML reads `on` as true: a boolean pattern matches a state's on/off and an attribute's true/false.
            true -> return value == "on" || value == "true"
            false -> return value == "off" || value == "false"
            is Number -> {
                val d = value.toDoubleOrNull()
                return if (d != null) d == pattern.toDouble() else value == pattern.toString()
            }
            else -> pattern.toString()
        }
        if (p.length >= 2 && p.startsWith("/") && p.endsWith("/")) {
            return runCatching { Regex(p.substring(1, p.length - 1)).containsMatchIn(value) }.getOrDefault(false)
        }
        COMPARE.find(p)?.let { m ->
            val op = m.groupValues[1]
            val rhs = m.groupValues[2].trim()
            val a = value.toDoubleOrNull()
            val b = rhs.toDoubleOrNull()
            return when (op) {
                "==", "=" -> if (a != null && b != null) a == b else value == rhs
                "!=" -> if (a != null && b != null) a != b else value != rhs
                else -> {
                    if (a == null || b == null) return false
                    when (op) { "<" -> a < b; "<=" -> a <= b; ">" -> a > b; else -> a >= b }
                }
            }
        }
        if ('*' in p) {
            val re = p.split('*').joinToString(".*") { Regex.escape(it) }
            return Regex("^$re$").matches(value)
        }
        return value == p
    }

    /**
     * The card auto-entities builds for [matches]: its `card:` (an `entities` card by default) with its `card_param`
     * field (`entities` by default) holding one row per match, or under `card_param: cards` one card per match (a
     * `tile` by default) from its rule's `options`.
     */
    fun innerCard(raw: JSONObject, matches: List<Match>): CardConfig {
        val base = raw.optJSONObject("card")?.let { JSONObject(it.toString()) } ?: JSONObject()
        if (!base.has("type")) base.put("type", "entities")
        val param = raw.optString("card_param", "entities").ifEmpty { "entities" }
        val items = JSONArray()
        for (m in matches) {
            if (param == "cards") {
                val card = m.options?.let { JSONObject(it.toString()) } ?: JSONObject()
                if (!card.has("type")) card.put("type", "tile")
                items.put(card.put("entity", m.entityId))
            } else {
                items.put(m.options?.let { JSONObject(it.toString()).put("entity", m.entityId) } ?: m.entityId)
            }
        }
        base.put(param, items)
        return LovelaceParser.parseCard(base)
    }

    /** `sort: { method, reverse, numeric, ignore_case, attribute }`, stable. */
    fun sort(list: List<Match>, sort: JSONObject, lookup: (String) -> EntityState?): List<Match> {
        val method = sort.optString("method", "none")
        val numeric = sort.optBoolean("numeric", false)
        val ignoreCase = sort.optBoolean("ignore_case", false)
        val attribute = sort.optString("attribute", "")
        fun key(m: Match): String? {
            val s = lookup(m.entityId)
            return when (method) {
                "domain" -> m.entityId.substringBefore('.')
                "entity_id" -> m.entityId
                "name", "friendly_name" -> s?.friendlyName ?: m.entityId
                "state" -> s?.state
                "attribute" -> s?.let { attributeString(it, attribute) }
                "last_changed" -> s?.lastChanged?.toString()
                "last_updated" -> s?.lastUpdated?.toString()
                else -> null
            }
        }
        // auto-entities' `first` and `count`: a window over the sorted list.
        fun window(l: List<Match>): List<Match> {
            val first = sort.optInt("first", 0).coerceAtLeast(0)
            val count = if (sort.has("count")) sort.optInt("count", -1) else -1
            val from = l.drop(first)
            return if (count >= 0) from.take(count) else from
        }
        if (method == "none" || method.isEmpty()) return window(if (sort.optBoolean("reverse", false)) list.reversed() else list)
        val cmp: Comparator<Match> = if (numeric || method == "last_changed" || method == "last_updated") {
            compareBy(nullsLast()) { m: Match -> key(m)?.toDoubleOrNull() }
        } else {
            compareBy(nullsLast()) { m: Match -> key(m)?.let { if (ignoreCase) it.lowercase(Locale.ROOT) else it } }
        }
        val sorted = list.sortedWith(cmp)
        return window(if (sort.optBoolean("reverse", false)) sorted.reversed() else sorted)
    }
}

/**
 * Native `custom:auto-entities`: filters every entity in the store with `filter.include` / `filter.exclude`,
 * orders them with `sort`, and hands the result to its `card:` (default `entities`) under `card_param`
 * (default `entities`; `cards` makes one card per entity from the rule's `options`). It re-filters as states change
 * and rebuilds the inner card only when the matched list does. `show_empty: false` hides it when nothing matches.
 */
class AutoEntitiesCardView(
    private val ctx: VitroCardContext,
    private val config: CardConfig,
    private val build: CardBuilder,
) : FrameLayout(ctx.context) {
    private var signature: String? = null
    private var pending = false
    private var unsubscribe: (() -> Unit)? = null

    /** The entity ids the inner card shows now, in order. */
    var matchedEntities: List<String> = emptyList()
        private set

    /**
     * Runs on every inner card built from now on, and on the one shown now: an enclosing stack-in-card takes its
     * surface off a replacement as it did the first.
     */
    var onInnerBuilt: ((View) -> Unit)? = null
        set(v) {
            field = v
            if (v != null && childCount > 0) v(getChildAt(0))
        }

    init {
        // Listening before the first read: HA's snapshot is written on the socket's thread, and one that lands while
        // the card reads the store or builds its first match would otherwise reach no listener, leaving the card (and
        // its view's fallback check) on the empty store for good. HA's auto-entities renders again whenever hass changes.
        unsubscribe = ctx.store.subscribeAny { scheduleRefresh() }
        // A first build that throws aborts the card before anything can detach it: its listener goes with it.
        try {
            refresh()
        } catch (t: Throwable) {
            unsubscribe?.invoke()
            unsubscribe = null
            throw t
        }
    }

    private fun scheduleRefresh() {
        if (pending) return
        pending = true
        ctx.main.post {
            pending = false
            // Detached meanwhile: build nothing (its cards would subscribe and never detach); attaching refreshes.
            if (unsubscribe != null) refresh()
        }
    }

    /**
     * Re-runs the filter now and rebuilds the inner card if the matches changed. A match Vitro doesn't draw natively
     * (the strict rule a config is checked by, [WebFallback.matchedReason]) is reported ([VitroCardContext.matchedNeedFrontend]):
     * the view goes to HA's frontend, as a view whose config holds such a row does. Until it's gone (a view with no
     * page stays), the card draws what it matched as it can.
     */
    fun refresh() {
        val matches = AutoEntitiesFilter.evaluate(config.raw, ctx.store.all().values, ctx.store::get)
        val sig = matches.joinToString("\n") { it.entityId + "|" + (it.options?.toString() ?: "") }
        if (sig == signature) return
        signature = sig
        matchedEntities = matches.map { it.entityId }
        removeAllViews()
        if (matches.isEmpty() && !config.raw.optBoolean("show_empty", true)) {
            visibility = GONE
            return
        }
        visibility = VISIBLE
        val innerConfig = AutoEntitiesFilter.innerCard(config.raw, matches)
        WebFallback.matchedReason(innerConfig)?.let { ctx.matchedNeedFrontend(config, it) }
        val inner = build(innerConfig)
        onInnerBuilt?.invoke(inner)
        addView(inner, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        // A new inner card may be one a culled masonry view must always draw (a camera, an island).
        dev.glassworks.engine.vitro.ui.ViewportCull.contentChanged(this)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        unsubscribe?.invoke()
        unsubscribe = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (unsubscribe == null) {
            unsubscribe = ctx.store.subscribeAny { scheduleRefresh() }
            refresh()
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// custom:stack-in-card, custom:vertical-stack-in-card
// ---------------------------------------------------------------------------------------------------------------

/**
 * Native `custom:stack-in-card` (`mode: vertical | horizontal`), after stack-in-card 0.2.0: the cards sit in a
 * plain vertical (or horizontal) stack inside this one surface, with the stack's usual gap
 * (`--vertical-stack-card-gap`, 8 px by default), and lose their own backgrounds and shadows so they read as one
 * card. `keep: { background: true }` keeps the backgrounds (and `border_radius: true` their rounding);
 * `keep: { margin: true }` also pads the stack 8 px. An optional `title` heads the card.
 */
open class StackInCardView(
    ctx: VitroCardContext,
    protected val config: CardConfig,
    build: CardBuilder,
    horizontal: Boolean = config.raw.optString("mode", "vertical") == "horizontal",
) : CardSurface(ctx) {
    private val keep = config.raw.optJSONObject("keep") ?: JSONObject()

    /** The built child cards, in order. */
    val children: List<View>
    private val stack: LinearLayout

    init {
        // A child whose content overflows its slot (a big heading in a 1-row Markdown card) paints past it, as in
        // the browser; only the card's own rounded outline clips.
        val column = LinearLayout(ctx.context).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        config.title?.let { t ->
            column.addView(label(ctx, 16f, ctx.theme.primaryTextColor, Inter.MEDIUM).apply {
                text = t
                setPadding(ctx.px(16f), ctx.px(14f), ctx.px(16f), ctx.px(4f))
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        // stack-in-card 0.2.0 wraps the cards in a plain vertical/horizontal stack; `keep.margin` turns on
        // `outer_padding`, 8 px around that stack.
        val outerPadding = if (keep.has("outer_padding")) keep.optBoolean("outer_padding") else keep.optBoolean("margin", false)
        stack = LinearLayout(ctx.context).apply {
            orientation = if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            if (outerPadding) {
                val pad = ctx.px(8f)
                setPadding(pad, pad, pad, pad)
            }
        }
        // The inner stack's gap: `--vertical-stack-card-gap` (or the horizontal one), then `--stack-card-gap`, 8 px.
        // Each name from the stack's own card_mod first, then the enclosing cards' (inherited), then the theme's.
        val vars = dev.glassworks.engine.vitro.theme.ThemeResolver.parseCardMod(config.raw.optJSONObject("card_mod"), ctx.theme.vars).customProperties
        val gapVar = listOf(if (horizontal) "horizontal-stack-card-gap" else "vertical-stack-card-gap", "stack-card-gap")
            .firstNotNullOfOrNull { vars[it] ?: ctx.cardVars[it] ?: ctx.theme.vars[it] }
        val gap = ctx.px(dev.glassworks.engine.vitro.theme.ThemeResolver.parseDimensionDp(gapVar, 8f))
        children = config.childCards.mapIndexed { idx, child ->
            val v = build(child)
            strip(v)
            val lp = if (horizontal) {
                LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { if (idx > 0) marginStart = gap }
            } else {
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { if (idx > 0) topMargin = gap }
            }
            stack.addView(v, lp)
            v
        }
        column.addView(stack, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(column, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    // card-mod padding on the outer ha-card adds to the inner stack's own padding, as in the browser.
    override fun clearDefaultInnerPadding() = Unit

    /** Takes the surface off every card in [v]'s tree, so the children share this card's one surface. */
    private fun strip(v: View) {
        if (v is CardSurface) {
            v.makeSeamless(keepBackground = keep.optBoolean("background", false), keepRadius = keep.optBoolean("border_radius", false))
        }
        // auto-entities replaces its inner card when its matches change: the replacement shares the surface too.
        if (v is AutoEntitiesCardView) {
            v.onInnerBuilt = { inner -> strip(inner) }
            return
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) strip(v.getChildAt(i))
    }
}

/** Native `custom:vertical-stack-in-card` (`horizontal: true` lays it out in a row): [StackInCardView] by another name. */
class VerticalStackInCardView(ctx: VitroCardContext, config: CardConfig, build: CardBuilder) :
    StackInCardView(ctx, config, build, horizontal = config.raw.optBoolean("horizontal", false))

// ---------------------------------------------------------------------------------------------------------------
// custom:layout-card
// ---------------------------------------------------------------------------------------------------------------

/**
 * Native `custom:layout-card`. `layout_type: custom:grid-layout` (or `grid`) reads `layout: { grid-template-columns,
 * grid-gap / gap, mediaquery }` and each card's `view_layout: { grid-column }`; the column layouts (masonry,
 * horizontal, vertical) place the cards into columns as layout-card does ([placeColumns]). The children keep their
 * own surfaces, as in HA.
 */
class LayoutCardView(
    private val ctx: VitroCardContext,
    private val config: CardConfig,
    private val build: CardBuilder,
    widthDp: Int = (ctx.context.resources.displayMetrics.widthPixels / ctx.dp).toInt(),
) : LinearLayout(ctx.context) {
    /** Each built row (grid) or column (horizontal / vertical layouts), for tests. */
    val tracks = ArrayList<LinearLayout>()

    /**
     * The empty host a pinned card (card-mod `position: fixed`) leaves where it was: its card draws in the view's
     * overlay. In the grid layout it keeps its cell and margins, as in HA (2026-11-25, fake HA: a 12 px row and its
     * gaps). In a column its margins collapse through it as an empty block's do: between two cards it takes no space,
     * and at a column's start or end it only raises the edge margin to the larger of the top and bottom margins.
     */
    class PinnedSlot(context: Context) : View(context) {
        // No content: zero height whatever its row offers (a bare View would take all of an AT_MOST height).
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
            setMeasuredDimension(getDefaultSize(0, widthMeasureSpec), 0)
    }

    /**
     * A grid whose tracks have percentages: its template, gap, each placed view (a card or a filler) with its first
     * track and span, and the width (dp) the fixed sizes were last resolved at. The build only knows the display's
     * width; [onMeasure] resolves the percentages again against the grid's own.
     */
    private var percentTemplate: String? = null
    private var gridGap = 0f
    private var gridCols = 0
    private val placed = ArrayList<Triple<View, Int, Int>>()
    private var resolvedAtDp = -1

    /** A column layout's mode ("masonry", "horizontal", "vertical"), or null for the grid. */
    private var columnMode: String? = null
    private var columnMargin = floatArrayOf(4f, 4f, 8f, 4f)
    private var columnWidthDp = 300f
    private var columnMaxDp = 500f
    private var maxCols = 4
    private var minHeight = 5
    /** The column layout's cards (a break as null), in order, built once. */
    private val columnCards = ArrayList<Pair<CardConfig, View?>>()
    private var columnRow: LinearLayout? = null
    private var placedCols = -1
    private var placedAtPx = -1

    /** The grid's card margin (top, right, bottom, left, dp), inside each track; none for the column layouts. */
    private var cardMargin = floatArrayOf(0f, 0f, 0f, 0f)
    private val marginX get() = cardMargin[1] + cardMargin[3]

    /** A grid cell's layout params: [fixedDp] of tracks and gutters less the card's side margins, and its margins. */
    private fun cellParams(fixedDp: Float, weight: Float, height: Int, first: Boolean, gap: Float): LayoutParams =
        LayoutParams(ctx.px((fixedDp - marginX).coerceAtLeast(0f)), height, weight).apply {
            setMargins(ctx.px(cardMargin[3]), ctx.px(cardMargin[0]), ctx.px(cardMargin[1]), ctx.px(cardMargin[2]))
            if (!first) marginStart = ctx.px(cardMargin[3] + gap)
        }

    init {
        orientation = VERTICAL
        val layoutType = config.raw.optString("layout_type", "custom:masonry-layout").removePrefix("custom:")
        val layout = mergedLayout(config.raw.optJSONObject("layout") ?: JSONObject(), widthDp)
        val gap = parseGap(layout) ?: ctx.theme.cardGapDp
        when (layoutType) {
            // grid-layout: `#root { margin: var(--layout-margin); padding: var(--layout-padding) }` (layout.margin
            // and layout.padding, default 0 4 and 4 0), only the layout's own `grid-gap`, and every card in
            // `margin: var(--masonry-view-card-margin, 4px 4px 8px)` inside its track.
            "grid-layout", "grid" -> {
                val m = MushroomTitleCardView.paddingDp(layout.optString("margin", "").ifEmpty { null }) ?: floatArrayOf(0f, 4f, 0f, 4f)
                val p = MushroomTitleCardView.paddingDp(layout.optString("padding", "").ifEmpty { null }) ?: floatArrayOf(4f, 0f, 4f, 0f)
                setPadding(ctx.px(m[3] + p[3]), ctx.px(m[0] + p[0]), ctx.px(m[1] + p[1]), ctx.px(m[2] + p[2]))
                cardMargin = MushroomTitleCardView.paddingDp(ctx.theme.vars["masonry-view-card-margin"]) ?: floatArrayOf(4f, 4f, 8f, 4f)
                buildGrid(layout, parseGap(layout) ?: 0f, widthDp)
            }
            else -> {
                // The column layouts (masonry, horizontal, vertical): the cards are built now and placed into columns
                // once the card knows its width ([placeColumns]).
                columnMode = when (layoutType) {
                    "vertical-layout", "vertical" -> "vertical"
                    "horizontal-layout", "horizontal" -> "horizontal"
                    else -> "masonry"
                }
                val m = MushroomTitleCardView.paddingDp(layout.optString("margin", "").ifEmpty { null }) ?: floatArrayOf(0f, 4f, 0f, 4f)
                val p = MushroomTitleCardView.paddingDp(layout.optString("padding", "").ifEmpty { null }) ?: floatArrayOf(4f, 0f, 4f, 0f)
                setPadding(ctx.px(m[3] + p[3]), ctx.px(m[0] + p[0]), ctx.px(m[1] + p[1]), ctx.px(m[2] + p[2]))
                columnMargin = MushroomTitleCardView.paddingDp(layout.optString("card_margin", "").ifEmpty { null })
                    ?: MushroomTitleCardView.paddingDp(ctx.theme.vars["masonry-view-card-margin"]) ?: floatArrayOf(4f, 4f, 8f, 4f)
                // `--column-width` (layout.width, 300) and `--column-max-width` (max_width, else 1.5 x width, else 500).
                columnWidthDp = layout.optDouble("width", Double.NaN).takeIf { !it.isNaN() && it > 0 }?.toFloat() ?: 300f
                columnMaxDp = layout.optDouble("max_width", Double.NaN).takeIf { !it.isNaN() && it > 0 }?.toFloat()
                    ?: if (layout.has("width")) kotlin.math.ceil(1.5f * columnWidthDp) else 500f
                // An inverted pair (`width: 400, max_width: 350`): CSS's minmax() takes the larger as the max too.
                if (columnMaxDp < columnWidthDp) columnMaxDp = columnWidthDp
                maxCols = layout.optInt("max_cols", 4).coerceIn(1, 12)
                minHeight = layout.optInt("min_height", 5)
                for (c in config.childCards) columnCards += c to (if (isBreak(c)) null else build(c))
                columnRow = LinearLayout(ctx.context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_HORIZONTAL }
                addView(columnRow, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            }
        }
    }

    private fun mergedLayout(base: JSONObject, widthDp: Int): JSONObject {
        val queries = base.optJSONObject("mediaquery") ?: return base
        val merged = JSONObject(base.toString())
        for (q in queries.keys()) {
            val min = Regex("""min-width\s*:\s*(\d+)px""").find(q)?.groupValues?.get(1)?.toIntOrNull()
            val max = Regex("""max-width\s*:\s*(\d+)px""").find(q)?.groupValues?.get(1)?.toIntOrNull()
            if ((min == null || widthDp >= min) && (max == null || widthDp <= max)) {
                val o = queries.optJSONObject(q) ?: continue
                for (k in o.keys()) merged.put(k, o.get(k))
                break
            }
        }
        return merged
    }

    private fun parseGap(layout: JSONObject): Float? {
        val raw = layout.optString("grid-gap", "").ifEmpty { layout.optString("gap", "") }.ifEmpty { layout.optString("grid-column-gap", "") }
        return Regex("""(\d+(?:\.\d+)?)px""").find(raw)?.groupValues?.get(1)?.toFloatOrNull()
    }

    private fun isBreak(c: CardConfig) = c.type == CardRouter.LAYOUT_BREAK || c.type == "custom:layout-break"

    /**
     * layout-card's column layouts at [widthPx]: `floor(width / column width)` columns, at most `max_cols` (4), the
     * cards dealt by the mode (masonry: into the first column under `min_height` card-size units, else the shortest;
     * horizontal: round robin, `view_layout.column` and breaks restart it; vertical: one column until each break),
     * empty columns dropped, the rest `minmax(width, max_width)` wide and centered. In a column the cards' margins
     * collapse as blocks: `--card-margin` around each, the larger of the bottom and top margins between two.
     */
    private fun placeColumns(widthPx: Int) {
        val row = columnRow ?: return
        val w = widthPx / ctx.dp
        val n = floor(w / columnWidthDp).toInt().coerceIn(1, maxCols)
        if (n == placedCols && widthPx == placedAtPx) return
        val cols = List(n) { ArrayList<View>() }
        when (columnMode) {
            "vertical" -> {
                var r = 1
                for ((c, v) in columnCards) {
                    c.raw.optJSONObject("view_layout")?.optInt("column", 0)?.takeIf { it > 0 }?.let { r = it }
                    if (v != null) cols[(r - 1) % n] += v
                    if (v == null) r += 1
                }
            }
            "horizontal" -> {
                var o = 0
                for ((c, v) in columnCards) {
                    o += 1
                    c.raw.optJSONObject("view_layout")?.optInt("column", 0)?.takeIf { it > 0 }?.let { o = it }
                    if (v != null) cols[(o - 1) % n] += v else o = 0
                }
            }
            else -> {
                val length = DoubleArray(n)
                for ((c, v) in columnCards) {
                    if (v == null) continue
                    var pick = 0
                    for (k in 0 until n) {
                        if (length[k] > 0 && length[k] < minHeight) { pick = k; break }
                        if (length[k] < length[pick]) pick = k
                    }
                    cols[pick] += v
                    length[pick] += HaCardSize.of(c) { it.entity?.let { id -> ctx.store[id] } }
                }
            }
        }
        val used = cols.filter { it.isNotEmpty() }
        val inner = w - (paddingLeft + paddingRight) / ctx.dp
        val colW = (inner / used.size.coerceAtLeast(1)).coerceIn(columnWidthDp, columnMaxDp)
        row.removeAllViews()
        tracks.clear()
        val edge = kotlin.math.max(columnMargin[0], columnMargin[2])
        for (views in used) {
            val col = LinearLayout(ctx.context).apply { orientation = VERTICAL }
            // Pinned slots ([PinnedSlot]) take no space: they go in with no size and no margins (so they attach and
            // detach with the column, and their visibility listener with them), and a slot's margins count only at
            // the edges, on the first and last card.
            val cards = views.filter { it !is PinnedSlot }
            val slotFirst = views.first() is PinnedSlot
            val slotLast = views.last() is PinnedSlot
            var k = -1
            for (v in views) {
                (v.parent as? ViewGroup)?.removeView(v)
                if (v is PinnedSlot) {
                    col.addView(v, LayoutParams(MATCH_PARENT, 0))
                    continue
                }
                k++
                col.addView(v, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    leftMargin = ctx.px(columnMargin[3]); rightMargin = ctx.px(columnMargin[1])
                    topMargin = ctx.px(when {
                        k > 0 -> kotlin.math.max(0f, columnMargin[0] - columnMargin[2])
                        slotFirst -> edge
                        else -> columnMargin[0]
                    })
                    bottomMargin = ctx.px(if (k == cards.lastIndex && slotLast) edge else columnMargin[2])
                })
            }
            tracks += col
            row.addView(col, LayoutParams(ctx.px(colW), WRAP_CONTENT))
        }
        placedCols = n
        placedAtPx = widthPx
    }

    /** One grid track: a share of the free width ([fr]) or a fixed width in dp, at least [minDp] wide when auto-filled. */
    data class Track(val fr: Float, val fixedDp: Float, val minDp: Float = 0f)

    private fun buildGrid(layout: JSONObject, gap: Float, widthDp: Int) {
        val template = layout.optString("grid-template-columns", "").ifEmpty { config.raw.optString("grid-template-columns", "") }
        val tracksSpec = parseColumns(template, widthDp).ifEmpty { listOf(Track(1f, 0f)) }
        val cols = tracksSpec.size
        // Every grid's cells get exact widths at measure time (CSS tracks, not weights shared after margins).
        percentTemplate = template.ifEmpty { "1fr" }
        gridGap = gap
        gridCols = cols
        var row: LinearLayout? = null
        var used = 0
        fun closeRow() {
            val r = row ?: return
            if (used < cols) {
                val rest = tracksSpec.subList(used, cols)
                // The rest's own gutters too, so the weighted columns keep their widths on a short row.
                val filler = Space(ctx.context)
                r.addView(filler, cellParams(rest.sumOf { it.fixedDp.toDouble() }.toFloat() + gap * (rest.size - 1), rest.sumOf { it.fr.toDouble() }.toFloat(), 1, false, gap))
                placed += Triple(filler, used, cols - used)
            }
            row = null
        }
        for (child in config.childCards) {
            val span = spanOf(child, cols)
            // An explicit start line (`grid-column: 3 / 4`, `3 / span 2`, `3`) puts the card in that column.
            val start = startOf(child, cols)?.coerceAtMost(cols - span)
            if (row == null || used + span > cols || (start != null && start < used)) {
                closeRow()
                row = LinearLayout(ctx.context).apply { orientation = HORIZONTAL }
                addView(row, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { if (tracks.isNotEmpty()) topMargin = ctx.px(gap) })
                tracks += row!!
                used = 0
            }
            if (start != null && start > used) {
                val skipped = tracksSpec.subList(used, start)
                val filler = Space(ctx.context)
                placed += Triple(filler, used, start - used)
                row!!.addView(filler, cellParams(
                    skipped.sumOf { it.fixedDp.toDouble() }.toFloat() + gap * (skipped.size - 1),
                    skipped.sumOf { it.fr.toDouble() }.toFloat(), 1, used == 0, gap,
                ))
                used = start
            }
            val spanned = tracksSpec.subList(used, used + span)
            val fixed = spanned.sumOf { it.fixedDp.toDouble() }.toFloat() + gap * (span - 1)
            val weight = spanned.sumOf { it.fr.toDouble() }.toFloat()
            val view = build(child)
            placed += Triple(view, used, span)
            row!!.addView(view, cellParams(fixed, weight, WRAP_CONTENT, used == 0, gap))
            used += span
        }
        closeRow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (columnMode != null && MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            placeColumns(MeasureSpec.getSize(widthMeasureSpec))
        }
        val template = percentTemplate
        if (template != null && MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            // Percentages are of this grid's width (a card in a half-width column), not the display's.
            val w = ((MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight) / ctx.dp).toInt()
            if (w > 0 && w != resolvedAtDp) {
                val spec = parseColumns(template, w)
                // Only the sizes change here; a track count that depends on the width (auto-fill) keeps its build.
                if (spec.size == gridCols) {
                    // CSS grid: fixed tracks first, the `fr` ones share what's left after them and the gutters.
                    val free = (w - spec.sumOf { it.fixedDp.toDouble() }.toFloat() - gridGap * (gridCols - 1)).coerceAtLeast(0f)
                    val frs = spec.sumOf { it.fr.toDouble() }.toFloat()
                    for ((v, first, span) in placed) {
                        val tracks = spec.subList(first, first + span)
                        val dp = tracks.sumOf { (it.fixedDp + if (frs > 0f) free * it.fr / frs else 0f).toDouble() }.toFloat() +
                            gridGap * (span - 1) - marginX
                        (v.layoutParams as? LayoutParams)?.apply {
                            width = ctx.px(dp.coerceAtLeast(0f))
                            weight = 0f
                        }
                    }
                    resolvedAtDp = w
                }
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /** The 0-based column of an explicit `grid-column` start line, or null for automatic placement. */
    private fun startOf(child: CardConfig, cols: Int): Int? {
        val raw = child.raw.optJSONObject("view_layout")?.optString("grid-column", "").orEmpty().trim()
        val line = Regex("""^(\d+)\s*(?:/.*)?$""").find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return (line - 1).takeIf { it in 0 until cols }
    }

    private fun spanOf(child: CardConfig, cols: Int): Int {
        val raw = child.raw.optJSONObject("view_layout")?.optString("grid-column", "").orEmpty().trim()
        val span = Regex("""span\s+(\d+)""").find(raw)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""^(-?\d+)\s*/\s*(-?\d+)$""").find(raw)?.let { m ->
                val a = m.groupValues[1].toInt()
                val b = m.groupValues[2].toInt()
                val end = if (b < 0) cols + 2 + b else b
                (end - a).takeIf { it > 0 }
            }
            ?: 1
        return span.coerceIn(1, cols)
    }

    companion object {
        /** CSS `grid-template-columns` as tracks: `1fr 2fr`, `200px 1fr`, `repeat(3, 1fr)`, `repeat(auto-fill, minmax(200px, 1fr))`. */
        fun parseColumns(template: String, widthDp: Int): List<Track> {
            val out = ArrayList<Track>()
            for (tok in splitTop(template.trim())) {
                val repeat = Regex("""^repeat\(\s*([^,]+)\s*,\s*(.+)\)$""").find(tok)
                if (repeat != null) {
                    val count = repeat.groupValues[1].trim()
                    val inner = splitTop(repeat.groupValues[2].trim()).map { parseTrack(it, widthDp) }
                    val n = count.toIntOrNull() ?: run {
                        // auto-fill / auto-fit: as many tracks as fit at their minimum width.
                        val minDp = inner.sumOf { t -> (t.minDp.takeIf { it > 0f } ?: t.fixedDp.takeIf { it > 0f } ?: 100f).toDouble() }
                        (widthDp / minDp).toInt().coerceAtLeast(1)
                    }
                    repeat(n.coerceIn(1, 24)) { out += inner }
                } else {
                    out += parseTrack(tok, widthDp)
                }
            }
            return out
        }

        /** One track; a percentage is of the grid's width ([widthDp]), a fixed size like `px`, never a share. */
        private fun parseTrack(tok: String, widthDp: Int): Track {
            Regex("""^minmax\(\s*([^,]+)\s*,\s*(.+)\)$""").find(tok)?.let { m ->
                val lo = parseTrack(m.groupValues[1].trim(), widthDp)
                val hi = parseTrack(m.groupValues[2].trim(), widthDp)
                // A flexible maximum shares the free space above the minimum; a fixed one (`50%`, `200px`) sizes
                // the track up to it (never below the minimum).
                return when {
                    hi.fr > 0f -> Track(hi.fr, 0f, lo.fixedDp)
                    hi.fixedDp > 0f -> Track(0f, maxOf(lo.fixedDp, hi.fixedDp))
                    else -> lo
                }
            }
            val fr = Regex("""^(\d+(?:\.\d+)?)fr$""").find(tok)?.groupValues?.get(1)?.toFloatOrNull()
            if (fr != null) return Track(fr, 0f)
            val px = Regex("""^(\d+(?:\.\d+)?)px$""").find(tok)?.groupValues?.get(1)?.toFloatOrNull()
            if (px != null) return Track(0f, px)
            val pct = Regex("""^(\d+(?:\.\d+)?)%$""").find(tok)?.groupValues?.get(1)?.toFloatOrNull()
            if (pct != null) return Track(0f, widthDp * pct / 100f)
            return Track(1f, 0f) // auto, min-content, …
        }

        /** Splits on whitespace (and top-level commas inside `repeat`) outside parentheses. */
        private fun splitTop(s: String): List<String> {
            val out = ArrayList<String>()
            val cur = StringBuilder()
            var depth = 0
            for (c in s) {
                when {
                    c == '(' -> { depth++; cur.append(c) }
                    c == ')' -> { depth--; cur.append(c) }
                    (c.isWhitespace() || c == ',') && depth == 0 -> { if (cur.isNotEmpty()) { out += cur.toString(); cur.clear() } }
                    else -> cur.append(c)
                }
            }
            if (cur.isNotEmpty()) out += cur.toString()
            return out
        }
    }
}

/**
 * `custom:mushroom-title-card`, as Mushroom 5 draws it: no card surface, `--title-padding` (24 12 8) around a 24 px /
 * 400 title on a 32 px line (-0.288 px letter spacing) and a 16 px / 400 subtitle in the secondary color on a 24 px
 * line, 8 px apart (`--title-spacing`). Each can come from the theme's `--mush-title-*` / `--mush-subtitle-*`
 * variables; `alignment` aligns the text; a tap action puts a chevron after its line. Both lines may be templates,
 * and wrap (`white-space: pre-wrap`).
 */
class MushroomTitleCardView(private val ctx: VitroCardContext, private val config: CardConfig) : LinearLayout(ctx.context) {
    private val unsubs = ArrayList<() -> Unit>()
    private val binders = ArrayList<() -> (() -> Unit)>()

    init {
        orientation = VERTICAL
        val vars = ctx.theme.vars
        fun dp(name: String, default: Float) =
            vars[name]?.let { dev.glassworks.engine.vitro.theme.ThemeResolver.parseDimensionDp(it, -1f) }?.takeIf { it >= 0f } ?: default
        val pad = paddingDp(vars["mush-title-padding"]) ?: floatArrayOf(24f, 12f, 8f, 12f)
        setPadding(ctx.px(pad[3]), ctx.px(pad[0]), ctx.px(pad[1]), ctx.px(pad[2]))
        val spacing = dp("mush-title-spacing", 8f)
        val gravityX = when (config.raw.optString("alignment", "")) {
            "center" -> Gravity.CENTER_HORIZONTAL
            "end" -> Gravity.END
            else -> Gravity.START
        }
        val title = config.raw.optString("title", "")
        val subtitle = config.raw.optString("subtitle", "")
        val lines = listOf(
            Line(title, "title_tap_action", dp("mush-title-font-size", 24f), weightOf(vars["mush-title-font-weight"]),
                dp("mush-title-line-height", 32f), colorOf(vars["mush-title-color"]) ?: ctx.theme.primaryTextColor,
                dp("mush-title-letter-spacing", -0.288f, allowNegative = true)),
            Line(subtitle, "subtitle_tap_action", dp("mush-subtitle-font-size", 16f), weightOf(vars["mush-subtitle-font-weight"]),
                dp("mush-subtitle-line-height", 24f), colorOf(vars["mush-subtitle-color"]) ?: ctx.theme.secondaryTextColor,
                dp("mush-subtitle-letter-spacing", 0f, allowNegative = true)),
        ).filter { it.text.isNotEmpty() }
        for ((i, line) in lines.withIndex()) {
            val view = lineView(line, gravityX)
            addView(view, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                // `.header div:not(:last-of-type) { margin-bottom: var(--title-spacing) }`.
                if (i < lines.size - 1) bottomMargin = ctx.px(spacing)
            })
        }
    }

    private fun dp(name: String, default: Float, allowNegative: Boolean): Float =
        ctx.theme.vars[name]?.let { dev.glassworks.engine.vitro.theme.ThemeResolver.parseDimensionDp(it, Float.NaN) }
            ?.takeIf { !it.isNaN() && (allowNegative || it >= 0f) } ?: default

    private class Line(val text: String, val actionKey: String, val sizeDp: Float, val weight: Int, val lineDp: Float, val color: Int, val letterDp: Float)

    private fun lineView(line: Line, gravityX: Int): View {
        val tv = TextView(ctx.context).apply {
            val scaled = ctx.scaledSp(line.sizeDp)
            textSize = scaled
            typeface = VitroFonts.typeface(ctx.context, line.weight, scaled)
            includeFontPadding = false
            setTextColor(line.color)
            gravity = gravityX
            letterSpacing = if (line.sizeDp > 0f) line.letterDp / line.sizeDp else 0f
        }
        val action = config.raw.optJSONObject(line.actionKey)?.let { ActionConfig.parse(it, "none") }
        val actionable = action != null && action.action != "none"
        fun show(value: String) {
            // CSS line boxes: each line `line-height` tall, the leading split above and below the glyphs.
            val fm = tv.paint.fontMetrics
            val lh = line.lineDp * ctx.dp
            val half = (lh - (fm.descent - fm.ascent)) / 2f
            val strut = CssLineBoxSpan.Run(0, 0, -fm.ascent + half, fm.descent + half)
            val text = android.text.SpannableStringBuilder(value)
            if (actionable) {
                // Mushroom's chevron (`ha-icon`, the font's size, 4 px after the text).
                text.append(" ")
                val icon = android.graphics.drawable.BitmapDrawable(resources, chevron(line))
                icon.setBounds(0, 0, ctx.px(line.sizeDp), ctx.px(line.sizeDp))
                val at = text.length
                text.append(" ")
                text.setSpan(android.text.style.ImageSpan(icon, android.text.style.DynamicDrawableSpan.ALIGN_BOTTOM), at, at + 1, 0)
            }
            text.setSpan(CssLineBoxSpan(emptyList(), strut), 0, text.length, android.text.Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            tv.text = text
        }
        if (isTemplate(line.text)) {
            show("")
            bind { ctx.subscribeTemplate(line.text) { rendered -> ctx.main.post { show(rendered) } } }
        } else {
            show(line.text)
        }
        if (actionable) {
            tv.isClickable = true
            tv.setOnClickListener { ctx.performAction(action!!, null) }
        }
        return tv
    }

    private fun chevron(line: Line): android.graphics.Bitmap {
        val px = ctx.px(line.sizeDp).coerceAtLeast(1)
        val bmp = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
        val v = MdiIconView(ctx.context).apply { setIcon("mdi:chevron-right", line.color) }
        v.measure(MeasureSpec.makeMeasureSpec(px, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(px, MeasureSpec.EXACTLY))
        v.layout(0, 0, px, px)
        v.draw(android.graphics.Canvas(bmp))
        return bmp
    }

    private fun bind(binder: () -> (() -> Unit)) {
        binders += binder
        unsubs += binder()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (unsubs.isEmpty()) binders.forEach { unsubs += it() }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        unsubs.forEach { runCatching { it() } }
        unsubs.clear()
    }

    private fun weightOf(raw: String?): Int = when (raw?.trim()?.lowercase(Locale.ROOT)) {
        null, "", "normal" -> Inter.REGULAR
        "bold" -> 700
        else -> raw.trim().toIntOrNull()?.coerceIn(100, 900) ?: Inter.REGULAR
    }

    private fun colorOf(raw: String?): Int? =
        raw?.takeIf { it.isNotBlank() }?.let { dev.glassworks.engine.vitro.theme.ThemeResolver.parseColor(it) }

    companion object {
        /** A CSS padding shorthand in px, as (top, right, bottom, left), or null when Vitro can't read it all. */
        internal fun paddingDp(raw: String?): FloatArray? {
            val parts = raw?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() } ?: return null
            val v = parts.map { dev.glassworks.engine.vitro.theme.ThemeResolver.parseDimensionDp(it, -1f).takeIf { d -> d >= 0f } ?: return null }
            return when (v.size) {
                1 -> floatArrayOf(v[0], v[0], v[0], v[0])
                2 -> floatArrayOf(v[0], v[1], v[0], v[1])
                3 -> floatArrayOf(v[0], v[1], v[2], v[1])
                4 -> floatArrayOf(v[0], v[1], v[2], v[3])
                else -> null
            }
        }
    }
}
