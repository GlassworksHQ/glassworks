/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.cards

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
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.model.ActionConfig
import dev.glassworks.engine.vitro.model.CardConfig
import dev.glassworks.engine.vitro.model.CardRouter
import dev.glassworks.engine.vitro.model.LovelaceParser
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
import kotlin.math.roundToInt

// Tier 2 HACS parity wave: the rest of the Mushroom suite, mini-media-player, auto-entities, the seamless stack
// containers (stack-in-card, vertical-stack-in-card, layout-card) and Bubble Card's sub-buttons. Every card here
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
        val column = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.px(10f), ctx.px(10f), ctx.px(10f), ctx.px(10f))
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
            gravity = Gravity.CENTER_VERTICAL
            if (!config.raw.optBoolean("hide_icon", false)) {
                addView(shape, LinearLayout.LayoutParams(ctx.px(36f), ctx.px(36f)).apply { marginEnd = ctx.px(10f) })
            }
            addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            // The state item keeps its own 10 px end padding inside its half (HA: a 129 px info box in a 400 px card).
            if (horizontal) setPadding(0, 0, ctx.px(10f), 0)
        }
        val header = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(stateItem, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            if (horizontal) addView(controls, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = ctx.px(10f) })
        }
        if (hideName) primary.visibility = GONE
        if (hideState) secondary.visibility = GONE
        column.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        if (!horizontal) column.addView(controls, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(column, LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER_VERTICAL))

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
        val id = eid
        if (id != null) observe(id) { s -> renderShell(s) } else renderShell(null)
    }

    private fun renderShell(s: EntityState?) {
        primary.text = config.name ?: s?.friendlyName ?: eid ?: ""
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
        ctx.loadImage(url) { bmp ->
            if (pictureUrl != url) return@loadImage
            shapePicture.setImageBitmap(bmp)
            shapePicture.visibility = if (bmp != null) VISIBLE else GONE
            shapeIcon.visibility = if (bmp != null) GONE else VISIBLE
        }
    }

    protected fun setSecondary(text: String) {
        secondary.text = text
    }

    /** A new horizontal control row in [controls]. */
    protected fun controlRow(): LinearLayout = LinearLayout(ctx.context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL or (if (horizontal) Gravity.END else Gravity.START)
        controls.addView(this, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            if (!horizontal || controls.childCount > 0) topMargin = ctx.px(10f)
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
            setOnClickListener { onClick() }
            applyHaRipple(this, ctx.px(12f).toFloat(), ctx.theme.dark)
        }

    /** Adds [button] to [row] as an equal-width cell. */
    /** Adds [button] to [row]: an equal-width cell, or a fixed square ([weight] squares wide) beside the info. */
    protected fun addCell(row: LinearLayout, button: View, weight: Float = 1f) {
        val lp = if (horizontal) LinearLayout.LayoutParams(ctx.px(controlHeight * weight), ctx.px(controlHeight))
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
    private val mediaControls = stringList(config.raw, "media_controls")
    private val volumeControls = stringList(config.raw, "volume_controls")
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
                    addCell(row, s, 3f)
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
 * Native `custom:mushroom-alarm-control-panel-card`: the alarm's state with a shield icon, and pills for each of
 * `states:` (`armed_home`, `armed_away`, `armed_night`, `armed_vacation`, `armed_custom_bypass`) while disarmed,
 * or one Disarm pill while armed. A panel that needs a code reads it from the card's `code:` when set.
 */
class MushroomAlarmControlPanelCardView(ctx: VitroCardContext, config: CardConfig) : MushroomShellCardView(ctx, config) {
    private val states = stringList(config.raw, "states", listOf("armed_home", "armed_away"))
    private val row = controlRow()
    private val armButtons = states.associateWith { st ->
        pill(ARM_ICONS[st] ?: "mdi:shield-lock", "Arm " + st.removePrefix("armed_").replace('_', ' ')) {
            call("alarm_control_panel", "alarm_arm_${st.removePrefix("armed_")}", ::withCode)
        }
    }
    private val disarmButton = pill("mdi:shield-off-outline", "Disarm") { call("alarm_control_panel", "alarm_disarm", ::withCode) }

    init {
        armButtons.values.forEach { addCell(row, it) }
        addCell(row, disarmButton)
        start()
    }

    private fun withCode(data: JSONObject) {
        config.raw.optString("code", "").takeIf { it.isNotEmpty() }?.let { data.put("code", it) }
    }

    private fun pill(icon: String, text: String, onClick: () -> Unit): LinearLayout = LinearLayout(ctx.context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        isClickable = true
        contentDescription = text
        setPadding(ctx.px(10f), 0, ctx.px(10f), 0)
        background = GradientDrawable().apply {
            cornerRadius = ctx.px(12f).toFloat()
            setColor(CardSurface.withAlpha(ctx.theme.primaryTextColor, 0.06f))
        }
        addView(MdiIconView(ctx.context).apply { setIcon(icon, ctx.theme.primaryTextColor) },
            LinearLayout.LayoutParams(ctx.px(18f), ctx.px(18f)).apply { marginEnd = ctx.px(6f) })
        addView(label(ctx, 13f, ctx.theme.primaryTextColor, Inter.MEDIUM).apply { this.text = titleCase(text.removePrefix("Arm ")) })
        setOnClickListener { onClick() }
    }

    override fun render(s: EntityState?) {
        val state = s?.state.orEmpty()
        val color = when {
            state == "disarmed" -> LOCKED_GREEN
            state == "triggered" -> ALERT_RED
            state == "pending" || state == "arming" || state == "disarming" -> PENDING_ORANGE
            state.startsWith("armed") -> ALERT_RED
            else -> ctx.theme.secondaryTextColor
        }
        val icon = when {
            state == "disarmed" -> "mdi:shield-off-outline"
            state == "triggered" -> "mdi:bell-ring"
            state == "pending" || state == "arming" -> iconOr("mdi:shield-sync", "mdi:shield-lock")
            else -> ARM_ICONS[state] ?: "mdi:shield-home"
        }
        setShape(iconOr(icon, "mdi:shield-home"), color, s != null && !s.isUnavailable)
        setSecondary(s?.let { titleCase(it.state) } ?: "…")
        val disarmed = state == "disarmed"
        armButtons.values.forEach { it.visibility = if (disarmed) VISIBLE else GONE }
        disarmButton.visibility = if (!disarmed && s != null && !s.isUnavailable) VISIBLE else GONE
    }

    private companion object {
        val ARM_ICONS = mapOf(
            "armed_home" to "mdi:shield-home",
            "armed_away" to "mdi:shield-lock",
            "armed_night" to "mdi:shield-moon",
            "armed_vacation" to "mdi:shield-airplane",
            "armed_custom_bypass" to "mdi:security",
        )
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
    private val commands = stringList(config.raw, "commands")
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
        setShape(iconOr("mdi:robot-vacuum", "mdi:robot-vacuum"), ctx.theme.activeColor("vacuum"), active)
        val battery = (s?.attributes?.get("battery_level") as? Number)?.toInt()
        val status = (s?.attributes?.get("status") as? String)?.takeIf { it.isNotBlank() } ?: s?.let { titleCase(it.state) } ?: "…"
        setSecondary(if (battery != null) "$status · $battery%" else status)
        val cleaning = s?.state == "cleaning"
        startPauseIcon.setIcon(if (cleaning) "mdi:pause" else "mdi:play", ctx.theme.primaryTextColor)
        startPause?.contentDescription = if (cleaning) "Pause" else "Start"
        controls.visibility = if (s == null || s.isUnavailable) GONE else VISIBLE
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
        shape.contentDescription = "Toggle"
        if (!config.raw.has("icon_tap_action")) shape.setOnClickListener { call("humidifier", "toggle") }
        if (config.raw.optBoolean("show_target_humidity_control", false)) {
            val row = controlRow()
            val s = HaHorizontalSliderView(ctx, ctx.theme.activeColor("humidifier")) { frac ->
                val st = eid?.let { ctx.store[it] }
                val (min, max) = range(st)
                call("humidifier", "set_humidity") { it.put("humidity", (min + frac * (max - min)).roundToInt()) }
            }
            slider = s
            addCell(row, s)
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
    private val valueLabel = label(ctx, 15f, ctx.theme.primaryTextColor, Inter.MEDIUM).apply { gravity = Gravity.CENTER }
    private var slider: HaHorizontalSliderView? = null

    init {
        val row = controlRow()
        if (buttons) {
            addCell(row, mushroomButton("mdi:minus", "Decrease") { step(-1) })
            addCell(row, valueLabel, 1.4f)
            addCell(row, mushroomButton("mdi:plus", "Increase") { step(+1) })
        } else {
            val s = HaHorizontalSliderView(ctx, ctx.theme.primaryColor) { frac ->
                val st = eid?.let { ctx.store[it] }
                val (min, max, stepSize) = bounds(st)
                setValue(snap(min + frac * (max - min), min, max, stepSize))
            }
            slider = s
            addCell(row, s)
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
        setShape(MdiIcons.resolveIconName(null, s).takeIf { s != null } ?: "mdi:tune", ctx.theme.primaryColor, s != null && !s.isUnavailable)
        setSecondary(s?.formattedState() ?: "…")
        val (min, max, stepSize) = bounds(s)
        val v = s?.state?.toDoubleOrNull()
        // HA reported the value the last press asked for: nothing is pending any more (an earlier press's value
        // isn't it, so an ack of that one keeps the target).
        if (v != null && pendingValue == v) pendingValue = null
        val text = when {
            v == null -> s?.state ?: "…"
            stepSize % 1.0 == 0.0 -> v.roundToInt().toString()
            else -> v.toString()
        }
        valueLabel.text = s?.unit?.let { "$text $it" } ?: text
        if (v != null) slider?.setValue(((v - min) / (max - min)).toFloat().coerceIn(0f, 1f), active = true)
        controls.visibility = if (s == null || s.isUnavailable) GONE else VISIBLE
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
    private val current = label(ctx, 14f, ctx.theme.primaryTextColor, Inter.MEDIUM)
    private val dropdown = LinearLayout(ctx.context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        contentDescription = "Choose option"
        setPadding(ctx.px(12f), 0, ctx.px(8f), 0)
        background = GradientDrawable().apply {
            cornerRadius = ctx.px(12f).toFloat()
            setColor(CardSurface.withAlpha(ctx.theme.primaryTextColor, 0.06f))
        }
    }

    init {
        dropdown.addView(current, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        dropdown.addView(MdiIconView(ctx.context).apply { setIcon("mdi:menu-down", ctx.theme.secondaryTextColor) },
            LinearLayout.LayoutParams(ctx.px(22f), ctx.px(22f)))
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
        setShape(MdiIcons.resolveIconName(null, s).takeIf { s != null } ?: "mdi:format-list-bulleted", ctx.theme.primaryColor,
            s != null && !s.isUnavailable)
        setSecondary(if (s == null) "…" else s.state)
        // The dropdown already shows the option; the state line only says when there is none to show.
        if (!config.raw.optBoolean("hide_state", false)) secondary.visibility = if (s != null && !s.isUnavailable) GONE else VISIBLE
        current.text = s?.state.orEmpty()
        controls.visibility = if (s == null || s.isUnavailable) GONE else VISIBLE
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

        val name = config.name ?: s?.friendlyName ?: eid.orEmpty()
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
        setTicking(playing && isAttachedToWindow)
    }

    private fun setArtwork(url: String?) {
        if (url == artUrl) return
        artUrl = url
        // The previous track's art goes at once: until (or unless) the new one loads, the icon shows.
        backdrop.setImageDrawable(null); backdrop.visibility = GONE; scrim.visibility = GONE
        thumb.setImageDrawable(null); thumb.visibility = GONE; iconView.visibility = VISIBLE
        iconBox.background = null
        if (url == null) return
        ctx.loadImage(url) { bmp ->
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
        val f = if (hidden("progress")) null else mediaProgressFraction(s)
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
        setTicking(last?.state == "playing")
    }

    override fun onDetachedFromWindow() {
        setTicking(false)
        super.onDetachedFromWindow()
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

    private val RULE_KEYS = setOf("domain", "entity_id", "state", "name", "attributes", "group", "not", "or", "and", "options", "sort")

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
                    "attributes" -> (rule.opt(k) as? JSONObject)?.let { a -> a.keys().asSequence().all { isScalar(a.opt(it)) } } ?: false
                    "options", "sort" -> true
                    // domain, entity_id, state, name, group: one value to match (`state: null` or an object is no rule).
                    else -> isScalar(rule.opt(k))
                }
            }

    private fun isScalar(v: Any?): Boolean = v is String || v is Number || v is Boolean

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
            true -> "on"
            false -> "off"
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

    init {
        refresh()
        unsubscribe = ctx.store.subscribeAny { scheduleRefresh() }
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

    /** Re-runs the filter now and rebuilds the inner card if the matches changed. */
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
        val inner = innerCard(matches)
        addView(build(inner), LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    private fun innerCard(matches: List<AutoEntitiesFilter.Match>): CardConfig {
        val base = config.raw.optJSONObject("card")?.let { JSONObject(it.toString()) } ?: JSONObject()
        if (!base.has("type")) base.put("type", "entities")
        val param = config.raw.optString("card_param", "entities").ifEmpty { "entities" }
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
        val vars = dev.glassworks.engine.vitro.theme.ThemeResolver.parseCardMod(config.raw.optJSONObject("card_mod"), ctx.theme.vars).customProperties
        val gapVar = listOf(if (horizontal) "horizontal-stack-card-gap" else "vertical-stack-card-gap", "stack-card-gap")
            .firstNotNullOfOrNull { vars[it] ?: ctx.theme.vars[it] }
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
 * grid-gap / gap, mediaquery }` and each card's `view_layout: { grid-column }`; `custom:horizontal-layout` deals
 * the cards into up to `max_cols` columns of at least `width` px; `custom:vertical-layout` stacks them, starting a
 * new column at each `view_layout: { column_break: true }`. The children keep their own surfaces, as in HA.
 */
class LayoutCardView(
    private val ctx: VitroCardContext,
    private val config: CardConfig,
    private val build: CardBuilder,
    widthDp: Int = (ctx.context.resources.displayMetrics.widthPixels / ctx.dp).toInt(),
) : LinearLayout(ctx.context) {
    /** Each built row (grid) or column (horizontal / vertical layouts), for tests. */
    val tracks = ArrayList<LinearLayout>()

    init {
        orientation = VERTICAL
        val layoutType = config.raw.optString("layout_type", "custom:masonry-layout").removePrefix("custom:")
        val layout = mergedLayout(config.raw.optJSONObject("layout") ?: JSONObject(), widthDp)
        val gap = parseGap(layout) ?: ctx.theme.cardGapDp
        when (layoutType) {
            "grid-layout", "grid" -> buildGrid(layout, gap, widthDp)
            "vertical-layout", "vertical" -> buildColumns(splitAtBreaks(), gap)
            else -> {
                // horizontal-layout and masonry-layout: deal the cards into columns, left to right.
                val minWidth = layout.optInt("width", config.raw.optInt("width", 300)).coerceAtLeast(50)
                val maxCols = layout.optInt("max_cols", config.raw.optInt("max_cols", 4)).coerceIn(1, 12)
                val cols = (widthDp / minWidth).coerceIn(1, maxCols).coerceAtMost(config.childCards.size.coerceAtLeast(1))
                val columns = List(cols) { ArrayList<CardConfig>() }
                config.childCards.forEachIndexed { i, c -> columns[i % cols] += c }
                buildColumns(columns, gap)
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

    private fun splitAtBreaks(): List<List<CardConfig>> {
        val out = ArrayList<ArrayList<CardConfig>>().apply { add(ArrayList()) }
        for (c in config.childCards) {
            if (c.raw.optJSONObject("view_layout")?.optBoolean("column_break", false) == true && out.last().isNotEmpty()) out.add(ArrayList())
            if (c.type != CardRouter.LAYOUT_BREAK) out.last() += c
        }
        return out.filter { it.isNotEmpty() }
    }

    private fun buildColumns(columns: List<List<CardConfig>>, gap: Float) {
        val row = LinearLayout(ctx.context).apply { orientation = HORIZONTAL }
        columns.forEachIndexed { ci, cards ->
            val col = LinearLayout(ctx.context).apply { orientation = VERTICAL }
            cards.forEachIndexed { i, c ->
                col.addView(build(c), LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { if (i > 0) topMargin = ctx.px(gap) })
            }
            tracks += col
            row.addView(col, LayoutParams(0, WRAP_CONTENT, 1f).apply { if (ci > 0) marginStart = ctx.px(gap) })
        }
        addView(row, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    /** One grid track: a share of the free width ([fr]) or a fixed width in dp, at least [minDp] wide when auto-filled. */
    data class Track(val fr: Float, val fixedDp: Float, val minDp: Float = 0f)

    private fun buildGrid(layout: JSONObject, gap: Float, widthDp: Int) {
        val template = layout.optString("grid-template-columns", "").ifEmpty { config.raw.optString("grid-template-columns", "") }
        val tracksSpec = parseColumns(template, widthDp).ifEmpty { listOf(Track(1f, 0f)) }
        val cols = tracksSpec.size
        var row: LinearLayout? = null
        var used = 0
        fun closeRow() {
            val r = row ?: return
            if (used < cols) {
                val rest = tracksSpec.subList(used, cols)
                // The rest's own gutters too, so the weighted columns keep their widths on a short row.
                r.addView(Space(ctx.context), LayoutParams(ctx.px(rest.sumOf { it.fixedDp.toDouble() }.toFloat() + gap * (rest.size - 1)), 1, rest.sumOf { it.fr.toDouble() }.toFloat()).apply {
                    marginStart = ctx.px(gap)
                })
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
                row!!.addView(Space(ctx.context), LayoutParams(
                    ctx.px(skipped.sumOf { it.fixedDp.toDouble() }.toFloat() + gap * (skipped.size - 1)), 1,
                    skipped.sumOf { it.fr.toDouble() }.toFloat(),
                ).apply { if (used > 0) marginStart = ctx.px(gap) })
                used = start
            }
            val spanned = tracksSpec.subList(used, used + span)
            val fixed = spanned.sumOf { it.fixedDp.toDouble() }.toFloat() + gap * (span - 1)
            val weight = spanned.sumOf { it.fr.toDouble() }.toFloat()
            row!!.addView(build(child), LayoutParams(ctx.px(fixed), WRAP_CONTENT, weight).apply { if (used > 0) marginStart = ctx.px(gap) })
            used += span
        }
        closeRow()
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
                    val inner = splitTop(repeat.groupValues[2].trim()).map { parseTrack(it) }
                    val n = count.toIntOrNull() ?: run {
                        // auto-fill / auto-fit: as many tracks as fit at their minimum width.
                        val minDp = inner.sumOf { t -> (t.minDp.takeIf { it > 0f } ?: t.fixedDp.takeIf { it > 0f } ?: 100f).toDouble() }
                        (widthDp / minDp).toInt().coerceAtLeast(1)
                    }
                    repeat(n.coerceIn(1, 24)) { out += inner }
                } else {
                    out += parseTrack(tok)
                }
            }
            return out
        }

        private fun parseTrack(tok: String): Track {
            Regex("""^minmax\(\s*([^,]+)\s*,\s*(.+)\)$""").find(tok)?.let { m ->
                val lo = parseTrack(m.groupValues[1].trim())
                val hi = parseTrack(m.groupValues[2].trim())
                return if (hi.fr > 0f) Track(hi.fr, 0f, lo.fixedDp) else lo
            }
            val fr = Regex("""^(\d+(?:\.\d+)?)fr$""").find(tok)?.groupValues?.get(1)?.toFloatOrNull()
            if (fr != null) return Track(fr, 0f)
            val px = Regex("""^(\d+(?:\.\d+)?)px$""").find(tok)?.groupValues?.get(1)?.toFloatOrNull()
            if (px != null) return Track(0f, px)
            val pct = Regex("""^(\d+(?:\.\d+)?)%$""").find(tok)?.groupValues?.get(1)?.toFloatOrNull()
            if (pct != null) return Track(pct / 100f, 0f)
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

// ---------------------------------------------------------------------------------------------------------------
// custom:bubble-card sub_button
// ---------------------------------------------------------------------------------------------------------------

/**
 * Bubble Card's `sub_button:` row: round (icon only) or pill (icon + name/state/attribute) buttons on the right of
 * a button card. Each follows its own `entity` (the card's when unset) and reads `icon`, `name`, `show_icon`,
 * `show_name`, `show_state`, `show_attribute` + `attribute`, `show_background` and `state_background`; `tap_action`
 * defaults to `more-info`, `hold_action` to none. Takes the list or Bubble 3's `{ main: [...] }`. Null when empty.
 */
internal fun bubbleSubButtons(
    ctx: VitroCardContext,
    config: CardConfig,
    observe: (String, (EntityState?) -> Unit) -> Unit,
): LinearLayout? {
    val raw = config.raw.opt("sub_button")
    val arr = when (raw) {
        is JSONArray -> raw
        is JSONObject -> raw.optJSONArray("main") ?: raw.optJSONArray("buttons")
        null -> null
        else -> null
    } ?: return null
    if (arr.length() == 0) return null
    val row = LinearLayout(ctx.context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    for (i in 0 until arr.length()) {
        val sub = arr.optJSONObject(i) ?: continue
        val entity = sub.optString("entity", "").ifEmpty { config.entity }
        val showIcon = sub.optBoolean("show_icon", true)
        val showName = sub.optBoolean("show_name", false)
        val showState = sub.optBoolean("show_state", false)
        val showAttribute = sub.optBoolean("show_attribute", false)
        val attribute = sub.optString("attribute", "")
        val showBackground = sub.optBoolean("show_background", true)
        val stateBackground = sub.optBoolean("state_background", true)
        val icon = MdiIconView(ctx.context)
        val text = label(ctx, 13f, ctx.theme.primaryTextColor, Inter.MEDIUM).apply { visibility = View.GONE }
        val button = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            isClickable = true
            minimumWidth = ctx.px(36f)
            contentDescription = sub.optString("name", "").ifEmpty { entity ?: "Sub-button ${i + 1}" }
            if (showIcon) addView(icon, LinearLayout.LayoutParams(ctx.px(18f), ctx.px(18f)))
            addView(text, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { if (showIcon) marginStart = ctx.px(6f) })
        }
        val tap = ActionConfig.parse(sub.optJSONObject("tap_action"), "more-info")
        val hold = ActionConfig.parse(sub.optJSONObject("hold_action"), "none")
        button.setOnClickListener { ctx.performAction(tap, entity) }
        button.bindHoldAction(ctx, hold, entity)
        fun render(s: EntityState?) {
            val active = s?.isActive == true
            val tint = if (active) ctx.theme.activeColor(s.domain) else ctx.theme.primaryTextColor
            icon.setIcon(MdiIcons.resolveIconName(sub.optString("icon", "").ifEmpty { null }, s), tint)
            val parts = ArrayList<String>()
            if (showName) parts += sub.optString("name", "").ifEmpty { s?.friendlyName ?: entity.orEmpty() }
            if (showState && s != null) parts += s.formattedState()
            if (showAttribute && s != null && attribute.isNotEmpty()) s.attributes[attribute]?.let { parts += it.toString() }
            text.text = parts.joinToString(" · ")
            text.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
            val padH = if (parts.isEmpty()) 0 else ctx.px(12f)
            button.setPadding(padH, 0, padH, 0)
            button.background = if (!showBackground) null else GradientDrawable().apply {
                cornerRadius = ctx.px(18f).toFloat()
                setColor(
                    if (active && stateBackground) CardSurface.withAlpha(tint, 0.24f)
                    else CardSurface.withAlpha(ctx.theme.primaryTextColor, 0.07f),
                )
            }
        }
        if (entity != null) observe(entity, ::render) else render(null)
        row.addView(button, LinearLayout.LayoutParams(WRAP_CONTENT, ctx.px(36f)).apply { if (row.childCount > 0) marginStart = ctx.px(6f) })
    }
    return row.takeIf { it.childCount > 0 }
}
