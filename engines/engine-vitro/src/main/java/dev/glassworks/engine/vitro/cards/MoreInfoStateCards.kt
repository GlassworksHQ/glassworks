/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.cards

import android.annotation.SuppressLint
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.HaDisplay
import dev.glassworks.engine.vitro.data.HaFormat
import dev.glassworks.engine.vitro.data.HaText
import dev.glassworks.engine.vitro.ui.A11yRole
import dev.glassworks.engine.vitro.ui.CardSurface
import dev.glassworks.engine.vitro.ui.MdiIconView
import dev.glassworks.engine.vitro.ui.MdiIcons
import dev.glassworks.engine.vitro.ui.VitroAccess
import dev.glassworks.engine.vitro.ui.VitroCardContext
import dev.glassworks.ui.Inter
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * The state row of HA's default More Info view (`ha-more-info-info`'s `state-card-content`, frontend 20261028.5) for
 * every domain the sheet draws there but a number's ([StateCardRow]): the row `stateCardType` picks, built again when
 * the state picks another (an alert going idle, a select going unavailable).
 *
 * | Type | HA's element | The row |
 * |---|---|---|
 * | `display` | `state-card-display` | `state-info`, the state on the right |
 * | `select` | `state-card-select`, `state-card-input_select` | the state badge, then the options' menu (its label hidden, its arrow shown) |
 * | `button` | `state-card-button`, `state-card-input_button` | `state-info`, then Press (`ha-control-button`) |
 * | `alert` | `state-card-alert` | `state-info`, then its toggle while active (on, or acknowledged), else its state |
 * | `event` | `state-card-event` | `state-info`, then the last event's time over its type |
 */
@SuppressLint("ViewConstructor")
internal class MoreInfoStateCard(private val ctx: VitroCardContext, private val entityId: String) : FrameLayout(ctx.context) {
    private var type: String? = null
    private var update: (EntityState?) -> Unit = {}
    private val theme get() = ctx.theme

    /** Shows [s], first building the row its type needs when that changed. */
    fun bind(s: EntityState?) {
        val t = typeOf(entityId, s)
        if (t != type) {
            type = t
            removeAllViews()
            update = build(t)
        }
        update(s)
    }

    /** The type of row showing ([typeOf]), for tests. */
    internal val shownType: String? get() = type

    private fun build(t: String): (EntityState?) -> Unit = when (t) {
        "select" -> buildSelect()
        "button" -> buildButton()
        "alert" -> buildAlert()
        "event" -> buildEvent()
        else -> buildDisplay()
    }

    // ---- state-info: the 40 px badge, 8 px, the name (16.8 px line) over the time (22.4 px line) ----

    private inner class StateInfo {
        val badge = FrameLayout(ctx.context)
        private val icon = MdiIconView(ctx.context)
        val labels = LinearLayout(ctx.context).apply { orientation = LinearLayout.VERTICAL }
        private val name = cssText(ctx, 14f, theme.primaryTextColor).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
        }
        private val time = cssText(ctx, 14f, theme.secondaryTextColor).apply {
            maxLines = 1
            gravity = Gravity.CENTER_VERTICAL
        }

        init {
            badge.addView(icon, LayoutParams(ctx.px(24f), ctx.px(24f), Gravity.CENTER))
            labels.addView(name, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, Math.round(16.8f * ctx.dp)))
            labels.addView(time, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, Math.round(22.4f * ctx.dp)))
        }

        /** The badge alone ([withLabels] false: a select's row) or with the name and the time. */
        fun bind(s: EntityState?, withLabels: Boolean = true) {
            // state-badge (`stateColor`): an active entity in its state's color, anything else in the icon color.
            val tint = s?.takeIf { it.isActive }?.let { HaStateColor.of(ctx, it) } ?: theme.stateIconColor
            icon.setIcon(MdiIcons.resolveIconName(null, s), tint)
            if (!withLabels) return
            name.text = entityName(s)
            // No age without a last change (0: HA never sent one), as ha-relative-time shows nothing then.
            time.text = s?.takeIf { it.lastChanged > 0.0 }?.let { HaFormat.relativeTime((it.lastChanged * 1000).toLong(), ctx.now()) }.orEmpty()
        }
    }

    /** `formatEntityName(stateObj, { type: "entity" })`: the entity's own name, else its device's. */
    private fun entityName(s: EntityState?): String {
        val parts = HaDisplay.nameParts(entityId, s?.friendlyName ?: entityId)
        return parts.entity ?: parts.device ?: s?.friendlyName ?: entityId
    }

    /** `.horizontal.justified.layout`: state-info, then [end] [endGap] px after it, the row [heightCss] tall. */
    private fun infoRow(info: StateInfo, end: View, endParams: LinearLayout.LayoutParams, endGap: Float, heightCss: Float = 40f) {
        val row = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(info.badge, LinearLayout.LayoutParams(ctx.px(40f), ctx.px(40f)))
        row.addView(info.labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = ctx.px(8f) })
        row.addView(end, endParams.apply { marginStart = ctx.px(endGap) })
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, Math.round(heightCss * ctx.dp)))
    }

    private fun stateText(): TextView = cssText(ctx, 14f, theme.primaryTextColor).apply {
        maxLines = 1
        gravity = Gravity.CENTER_VERTICAL or Gravity.END
    }

    // ---- state-card-display ----

    private fun buildDisplay(): (EntityState?) -> Unit {
        val info = StateInfo()
        val value = stateText()
        infoRow(info, value, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ctx.px(40f)), 8f)
        return { s ->
            info.bind(s)
            value.text = s?.let { MoreInfoStateHeader.stateCardDisplay(it, ctx.now()) } ?: HaText.ui("state.default.unknown")
        }
    }

    // ---- state-card-alert: ha-entity-toggle while active, else the state (`.state`, 16 px after state-info) ----

    private fun buildAlert(): (EntityState?) -> Unit {
        val info = StateInfo()
        val end = FrameLayout(ctx.context)
        val toggle = HaSwitchView(ctx).apply {
            isClickable = true
            isFocusable = true
        }
        val value = stateText()
        end.addView(toggle, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL or Gravity.END))
        end.addView(value, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT, Gravity.END))
        infoRow(info, end, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ctx.px(40f)), 16f)
        var shown: EntityState? = null
        toggle.setOnClickListener {
            val s = shown ?: return@setOnClickListener
            if (s.state == "unavailable") return@setOnClickListener
            val turnOn = !toggle.isOn
            // ha-entity-toggle: switched at once, the alert's turn_on or turn_off (turn_off acknowledges it), and
            // back to the state's own after 2 s if no new state came meanwhile.
            toggle.setOn(turnOn)
            ctx.callService("alert", if (turnOn) "turn_on" else "turn_off", JSONObject().put("entity_id", entityId), null)
            postDelayed({ if (shown === s) toggle.setOn(isOn(s)) }, TOGGLE_RESYNC_MS)
        }
        return { s ->
            shown = s
            info.bind(s)
            val active = s != null && HaStateActive.of(s)
            toggle.visibility = if (active) VISIBLE else GONE
            value.visibility = if (active) GONE else VISIBLE
            if (active) {
                toggle.setOn(isOn(s))
                // ha-switch's label is HA's "Toggle {name}"; Vitro's switches name what a tap does, as the toggle group's.
                toggle.contentDescription = if (isOn(s)) haUi("ui.components.entity.entity-toggle.turn_off", "Turn {name} off", "name" to entityName(s))
                else haUi("ui.components.entity.entity-toggle.turn_on", "Turn {name} on", "name" to entityName(s))
            } else {
                value.text = s?.formattedState() ?: HaText.ui("state.default.unknown")
            }
        }
    }

    // ---- state-card-event: the event's time (its state) over its type, both at the end ----

    private fun buildEvent(): (EntityState?) -> Unit {
        val info = StateInfo()
        val column = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        val line = Math.round(EVENT_LINE * ctx.dp)
        val time = stateText()
        val type = stateText().apply { setTextColor(theme.secondaryTextColor) }
        column.addView(time, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, line))
        column.addView(type, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, line))
        // `.container`: two body lines (22.4 px each), taller than the badge, so the row is too.
        infoRow(info, column, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT), 8f,
            heightCss = maxOf(40f, 2 * EVENT_LINE))
        return { s ->
            info.bind(s)
            time.text = s?.formattedState() ?: HaText.ui("state.default.unknown")
            // formatEntityAttributeValue(stateObj, "event_type"): its label, else the type as sent.
            type.text = s?.attributes?.get("event_type")?.let { HaStateDisplay.attributeValue(s, "event_type", it, ctx.haConfig()) }.orEmpty()
        }
    }

    // ---- state-card-button: Press, an ha-control-button with ha-action-result ----

    private fun buildButton(): (EntityState?) -> Unit {
        val info = StateInfo()
        val button = PressButton()
        infoRow(info, button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ctx.px(40f)), 8f)
        return { s ->
            info.bind(s)
            button.contentDescription = "${HaText.ui("ui.card.button.press")} ${entityName(s)}".trim()
        }
    }

    /**
     * `ha-control-button` (`width: auto`, at least 40 px, 16 px sides, 40 px tall): `--disabled-color` at 20 % with
     * `--ha-border-radius-md` corners behind its 14 px medium label. A press calls the domain's `press`; its result
     * (`ha-action-result`) takes the label's place for 2 s: a check, or the error octagram.
     */
    @SuppressLint("ViewConstructor")
    private inner class PressButton : FrameLayout(ctx.context) {
        private val label = cssText(ctx, 14f, theme.primaryTextColor, Inter.MEDIUM).apply {
            text = HaText.ui("ui.card.button.press")
            maxLines = 1
            gravity = Gravity.CENTER
        }
        private val result = MdiIconView(ctx.context).apply { visibility = GONE }
        private var busy = false
        private var generation = 0

        init {
            minimumWidth = ctx.px(40f)
            setPadding(ctx.px(16f), 0, ctx.px(16f), 0)
            background = GradientDrawable().apply {
                cornerRadius = 8f * ctx.dp
                setColor(CardSurface.withAlpha(disabledColor(ctx), 0.2f))
            }
            addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
            addView(result, LayoutParams(ctx.px(20f), ctx.px(20f), Gravity.CENTER))
            isClickable = true
            isFocusable = true
            applyHaRipple(this, 8f * ctx.dp, theme.dark)
            VitroAccess.role(this, A11yRole.BUTTON)
            setOnClickListener { press() }
        }

        private fun press() {
            // `if (this._result.busy) return`: one press at a time.
            if (busy) return
            busy = true
            val run = ++generation
            ctx.callServiceThen(entityId.substringBefore('.'), "press", JSONObject().put("entity_id", entityId), null) { ok ->
                ctx.main.post {
                    if (run != generation) return@post
                    busy = false
                    label.alpha = 0f
                    result.setIcon(if (ok) "mdi:check-bold" else "mdi:alert-octagram",
                        if (ok) theme.primaryTextColor else HaStateColor.property(ctx, "error-color") ?: theme.primaryTextColor)
                    result.visibility = VISIBLE
                    postDelayed({
                        if (run == generation) {
                            result.visibility = GONE
                            label.alpha = 1f
                        }
                    }, RESULT_MS)
                }
            }
        }

        override fun onDetachedFromWindow() {
            // `disconnectedCallback`: no result is left showing.
            generation++
            busy = false
            result.visibility = GONE
            label.alpha = 1f
            super.onDetachedFromWindow()
        }
    }

    // ---- state-card-select / state-card-input_select ----

    private fun buildSelect(): (EntityState?) -> Unit {
        val info = StateInfo()
        val menu = SelectMenu()
        addView(SelectRow(info.badge, menu), LayoutParams(LayoutParams.MATCH_PARENT, ctx.px(48f)))
        return { s ->
            info.bind(s, withLabels = false)
            menu.bind(s)
        }
    }

    /**
     * `:host { display: flex; gap: 8px }` of a 40 px `state-badge` and a menu `width: 100%`: the two overflow the row
     * by the badge and the gap, and both shrink by their share of it (flex-shrink 1, by their basis), so the badge is
     * a little under 40 px (38 on the S1) and the menu takes the rest. Both are centred on the row's 48 px.
     */
    @SuppressLint("ViewConstructor")
    private inner class SelectRow(private val badge: View, private val menu: View) : ViewGroup(ctx.context) {
        init {
            addView(badge)
            addView(menu)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val (badgeW, menuW) = shares(w / ctx.dp)
            val h = ctx.px(48f)
            badge.measure(MeasureSpec.makeMeasureSpec(Math.round(badgeW * ctx.dp), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ctx.px(40f), MeasureSpec.EXACTLY))
            menu.measure(MeasureSpec.makeMeasureSpec(Math.round(menuW * ctx.dp), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
            setMeasuredDimension(w, h)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val h = b - t
            val bt = (h - badge.measuredHeight) / 2
            badge.layout(0, bt, badge.measuredWidth, bt + badge.measuredHeight)
            val mx = (r - l) - menu.measuredWidth
            menu.layout(mx, 0, mx + menu.measuredWidth, menu.measuredHeight)
        }
    }

    /**
     * `ha-control-select-menu` with `hide-label` and `show-arrow`: 48 px, padded 6 × 10 px, `--disabled-color` at 20 %
     * with `--ha-border-radius-lg` corners; its icon box empty (only its 10 px gap shows), the selected option's label
     * (14 px), and a 20 px `mdi:menu-down` 10 px from the end. The label (the entity's name) is visually hidden: it names
     * the menu for TalkBack. A tap lists the options, the selected one checked; another one picked is selected.
     */
    @SuppressLint("ViewConstructor")
    private inner class SelectMenu : LinearLayout(ctx.context) {
        private val value = cssText(ctx, 14f, theme.primaryTextColor).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            letterSpacing = 0.25f / 14f
        }
        private val arrow = MdiIconView(ctx.context)
        private var options: List<Pair<String, String>> = emptyList()
        private var selected: String? = null

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.px(10f), ctx.px(6f), ctx.px(10f), ctx.px(6f))
            background = GradientDrawable().apply {
                cornerRadius = 12f * ctx.dp
                setColor(CardSurface.withAlpha(disabledColor(ctx), 0.2f))
            }
            addView(value, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply { marginStart = ctx.px(10f) })
            addView(arrow, LayoutParams(ctx.px(20f), ctx.px(20f)).apply { marginStart = ctx.px(10f) })
            isClickable = true
            isFocusable = true
            applyHaRipple(this, 12f * ctx.dp, theme.dark)
            VitroAccess.role(this, A11yRole.BUTTON)
            setOnClickListener { open() }
        }

        fun bind(s: EntityState?) {
            val opts = when (val v = s?.attributes?.get("options")) {
                is List<*> -> v.mapNotNull { it?.toString() }
                is org.json.JSONArray -> (0 until v.length()).map { v.optString(it) }
                else -> emptyList()
            }
            // A select's options as HA formats them (`formatEntityState(stateObj, option)`), an input_select's as written.
            options = opts.map { o -> o to (if (s != null && s.domain == "select") s.copy(state = o).formattedState() else o) }
            selected = s?.state
            val label = options.firstOrNull { it.first == selected }?.second
            value.text = label.orEmpty()
            // Only an unavailable entity disables it, and that one shows the display row instead (stateCardType).
            val color = theme.primaryTextColor
            value.setTextColor(color)
            arrow.setIcon("mdi:menu-down", color)
            contentDescription = listOfNotNull(entityName(s), label).joinToString(", ")
        }

        private fun open() {
            if (options.isEmpty()) return
            val shown = options.toList()
            runCatching {
                PopupMenu(ctx.context, this).apply {
                    shown.forEachIndexed { i, (v, l) ->
                        menu.add(0, i, i, l).apply {
                            isCheckable = true
                            isChecked = v == selected
                        }
                    }
                    setOnMenuItemClickListener { item ->
                        shown.getOrNull(item.itemId)?.first?.let { EntityRowControls.choose(ctx, entityId, it) }
                        true
                    }
                }.show()
            }
        }
    }

    companion object {
        /** `ha-entity-toggle`: back to the state's own after this long without a new state. */
        const val TOGGLE_RESYNC_MS = 2000L

        /** `ha-action-result`'s `RESULT_DURATION`. */
        const val RESULT_MS = 2000L

        /** haStyle's body line (14 px × 1.6): a `state-card-event` line. */
        const val EVENT_LINE = 22.4f

        /**
         * HA's `stateCardType` for [entityId] in state [s], among the rows this view draws: an unavailable (or missing)
         * entity's row is `display`; select, input_select, button, input_button, alert and event have their own.
         */
        fun typeOf(entityId: String, s: EntityState?): String {
            if (s == null || s.state == "unavailable") return "display"
            return when (entityId.substringBefore('.')) {
                "select", "input_select" -> "select"
                "button", "input_button" -> "button"
                "alert" -> "alert"
                "event" -> "event"
                else -> "display"
            }
        }

        /** `ha-entity-toggle`'s `isOn`: not off, closed or locked, nor unavailable or unknown. */
        fun isOn(s: EntityState?): Boolean =
            s != null && s.state !in setOf("off", "closed", "locked", "unavailable", "unknown")

        /**
         * The two widths (CSS px) of a select's row [rowCss] wide: a 40 px badge and a menu as wide as the row, 8 px
         * apart, each shrunk by its basis's share of what overflows.
         */
        fun shares(rowCss: Float): Pair<Float, Float> {
            // The overflow is the badge and the gap (48 px), whatever the row's width.
            val badge = 40f - 48f * 40f / (40f + rowCss)
            return badge to (rowCss - 8f - badge).coerceAtLeast(0f)
        }
    }
}
