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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.data.HaDisplay
import dev.glassworks.engine.vitro.data.HaFormat
import dev.glassworks.engine.vitro.data.HaLocale
import dev.glassworks.engine.vitro.data.HaText
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.A11yRole
import dev.glassworks.engine.vitro.ui.MdiIconView
import dev.glassworks.engine.vitro.ui.VitroAccess
import dev.glassworks.engine.vitro.ui.VitroCardContext
import dev.glassworks.ui.Inter
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * HA's `ha-more-info-logbook` (frontend 20261028.5), the Activity section of a More Info dialog: the "Activity"
 * header with "Show more" (a link to `/logbook` for the entity from the start of yesterday), then `ha-logbook` as the
 * dialog sets it (`narrow`, `no-icon`, `name-detail="none"`, `graph-color`, `max-entries="100"`): the last 24 hours from
 * a `logbook/event_stream` subscription that stays open, newest first under day headers, each row a state dot on a
 * rail, the value, the cause's badge, the time and a chevron. A row's tap opens HA's detail dialog, which only HA's
 * frontend has ([onEntry]).
 *
 * [capped]: `--logbook-max-height: 250px`, the list scrolling inside it (a window more than 450 wide and 500 tall);
 * else as tall as its rows. [onUnsupported] is told, once, when a row needs what Vitro can't draw as HA does (an
 * integration's brand image): the dialog then belongs to HA's frontend.
 */
internal class MoreInfoLogbookView(
    private val ctx: VitroCardContext,
    private val entityId: String,
    private val capped: Boolean,
    private val onEntry: (LogbookEntry) -> Unit,
    private val onShowMore: (String) -> Unit,
    private val onUnsupported: (String) -> Unit,
) : LinearLayout(ctx.context) {
    private val theme = ctx.theme
    private val side = px(24f)
    private val list = LinearLayout(ctx.context).apply { orientation = VERTICAL }
    private val scroll = object : ScrollView(ctx.context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            // `.container { max-height: var(--logbook-max-height) }`: as tall as the rows, up to the cap.
            val spec = if (capped) MeasureSpec.makeMeasureSpec(px(MAX_HEIGHT), MeasureSpec.AT_MOST) else heightMeasureSpec
            super.onMeasure(widthMeasureSpec, spec)
        }
    }.apply {
        isVerticalScrollBarEnabled = capped
        isFillViewport = false
        addView(list, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }
    private val body = FrameLayout(ctx.context)

    /** The entries shown, newest first; null until the stream's first message (HA's spinner). */
    private var entries: List<LogbookEntry>? = null
    private var error: String? = null
    private var users = HaLogbook.Users.NONE
    private var authList: JSONArray? = null
    private var unsubscribe: (() -> Unit)? = null
    private var generation = 0
    private var started = false
    private var handedOff = false

    /** The persons followed for the users' names ([watchPersons]), and their store listeners' handles. */
    private val watchedPersons = HashSet<String>()
    private val personWatches = ArrayList<() -> Unit>()

    /** The day the headers were last written on: a new day renames "Today" and "Yesterday". */
    private var renderedDay = -1L

    /** The rows on screen, for tests and TalkBack: each row's view and its entry. */
    internal val rows = ArrayList<Pair<View, LogbookEntry>>()

    init {
        orientation = VERTICAL
        // .header: Activity on the left, Show more on the right, 8 px over the list, 24 px sides.
        val header = LinearLayout(ctx.context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(side, 0, side, 0)
        }
        val h2 = cssText(ctx, 20f, theme.primaryTextColor, Inter.MEDIUM)
        h2.text = HaText.ui("ui.dialogs.more_info_control.logbook")
        lineBox(ctx, h2, 20f, 1.6f)
        header.addView(h2, LayoutParams(0, WRAP_CONTENT, 1f))
        val showMore = cssText(ctx, 14f, theme.primaryColor).apply {
            paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
            isClickable = true
            isFocusable = true
            text = HaText.ui("ui.dialogs.more_info_control.show_more")
            setOnClickListener { onShowMore(showMoreHref(entityId, ctx.now())) }
        }
        lineBox(ctx, showMore, 14f, 1.6f)
        header.addView(showMore, LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        addView(header, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = px(8f) })
        addView(body, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        render()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!started) start()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    /** Ends the subscription: the dialog closed, or shows something else. */
    fun stop() {
        started = false
        generation++
        unsubscribe?.invoke()
        unsubscribe = null
        personWatches.forEach { it() }
        personWatches.clear()
        watchedPersons.clear()
    }

    /**
     * `_getLogBookData`: the users (an administrator's from `config/auth/list`, everyone's persons), then the stream
     * from 24 hours ago to a year ahead. It's built again for each connection: a reconnect starts the list over with
     * the window as it is then, as HA's `ready` listener does.
     */
    private fun start() {
        started = true
        val gen = ++generation
        entries = null
        error = null
        refreshUsers()
        watchPersons(gen)
        // HA's registry can land after the dialog opened: the persons it lists are followed then.
        personWatches += ctx.store.subscribe(EntityStore.DISPLAY_KEY) { ctx.main.post { if (gen == generation) watchPersons(gen) } }
        if (ctx.haUser().optBoolean("is_admin", false)) {
            ctx.callWs(JSONObject().put("type", "config/auth/list")) { ok, result, _ ->
                ctx.main.post {
                    if (gen != generation) return@post
                    if (ok) authList = result as? JSONArray
                    refreshUsers()
                    render()
                }
            }
        }
        unsubscribe = ctx.subscribeWsFresh({
            LogbookCardView.streamRequest(listOf(entityId), 24, ctx.now())
        }, { ev, first ->
            val incoming = LogbookCardView.parseInOrder(ev.optJSONArray("events"))
            ctx.main.post {
                if (gen != generation) return@post
                // A new connection's stream replays the window: the list starts over, as HA's does.
                if (first) entries = null
                val purge = (ctx.now() - DAY_MS) / 1000.0
                entries = HaLogbook.merge(entries, incoming, purge)
                render()
            }
        }, { err ->
            ctx.main.post {
                if (gen != generation) return@post
                unsubscribe = null
                // `${err}` of HA's error object, as its template writes it.
                error = if (err == null) null else "[object Object]"
                render()
            }
        })
    }

    /** Names the users again from the persons in the store; true when that changed who's who. */
    private fun refreshUsers(): Boolean {
        val next = HaLogbook.Users.of(ctx.store.all().values, authList)
        val changed = next.names != users.names || next.persons != users.persons || next.system != users.system
        users = next
        return changed
    }

    /**
     * `_getLogBookData` names an entry's user from every person in `hass.states`, which HA's frontend always holds;
     * the dashboard's store has only the entities it follows (a non-admin has no `config/auth/list` to fall back on).
     * So every person HA's entity registry lists is followed while the section shows, as an island's runtime read is
     * ([VitroCardContext.watchEntity]), and the rows are named again when one lands or changes its user.
     */
    private fun watchPersons(gen: Int) {
        for (id in HaDisplay.registry.keys) {
            if (!id.startsWith("person.") || !watchedPersons.add(id)) continue
            personWatches += ctx.store.subscribe(id) { _ ->
                ctx.main.post { if (gen == generation && refreshUsers()) render() }
            }
            if (ctx.store[id] == null) ctx.watchEntity(id)
        }
    }

    /** "Today" and "Yesterday" follow the clock: the sheet's ticker calls this. */
    fun tick() {
        if (dayOf(ctx.now()) != renderedDay) render()
    }

    private fun store(id: String): EntityState? = ctx.store[id]

    private fun render() {
        body.removeAllViews()
        list.removeAllViews()
        rows.clear()
        renderedDay = dayOf(ctx.now())
        error?.let {
            body.addView(noEntries("${HaText.ui("ui.components.logbook.retrieval_error")}: $it"), FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            return
        }
        val shown = entries
        if (shown == null) {
            // .progress-wrapper: ha-spinner (48 px), centred.
            val spinner = HaSpinner(ctx, 48f)
            body.addView(spinner, FrameLayout.LayoutParams(px(48f), px(48f), Gravity.CENTER_HORIZONTAL))
            return
        }
        if (shown.isEmpty()) {
            body.addView(noEntries(HaText.ui("ui.components.logbook.entries_not_found")), FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            return
        }
        val limited = shown.take(MAX_ENTRIES)
        // Every cause badge first: one HA would draw as the brand's image hands the dialog to HA's frontend.
        val causes = limited.map { HaLogbook.rowCause(it, users, ::store) }
        causes.firstOrNull { it != null && HaLogbook.causeIcon(it) == HaLogbook.UNSUPPORTED }?.let { c ->
            if (!handedOff) {
                handedOff = true
                onUnsupported("an Activity entry caused by ${c.brandDomain}, whose brand image Vitro doesn't draw")
            }
            return
        }
        val tz = HaLocale.timeZone()
        val now = ctx.now()
        limited.forEachIndexed { i, e ->
            val firstOfDay = i == 0 || !sameDay(e, limited[i - 1], tz)
            val lastOfDay = i == limited.size - 1 || !sameDay(e, limited[i + 1], tz)
            if (firstOfDay) {
                // `.date`: 14 px / 500 on a 22.4 px line, 8 px margin and 8 px padding over it.
                val date = cssText(ctx, 14f, theme.primaryTextColor, Inter.MEDIUM).apply {
                    text = LogbookCardView.dateHeader((e.whenSec * 1000).toLong(), now, tz)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(side, px(16f), side, 0)
                }
                list.addView(date, LayoutParams(MATCH_PARENT, (38.4f * ctx.dp).roundToInt()))
            }
            val row = row(e, causes[i], firstOfDay, lastOfDay)
            rows += row to e
            list.addView(row, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        body.addView(scroll, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    /** `.no-entries`: centred, 16 px around, the secondary text color. */
    private fun noEntries(text: String): TextView = cssText(ctx, 14f, theme.secondaryTextColor).apply {
        this.text = text
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(px(16f), px(16f), px(16f), px(16f))
        lineBox(ctx, this, 14f, 1.6f)
    }

    /**
     * One inline `ha-logbook-entry` (`layout-inline`, `node-dot`): 24 px in, the 28 px node column (the dot on the
     * rail, trimmed at the day's ends), 8 px, then the body (at least 40 px, a 1 px divider under it but on the day's
     * last row): the value (wrapping), the cause badge and the time chip, and the 24 px chevron.
     */
    private fun row(e: LogbookEntry, cause: HaLogbook.Cause?, firstOfDay: Boolean, lastOfDay: Boolean): View {
        val unavailable = e.state == "unavailable"
        val row = LinearLayout(ctx.context).apply {
            orientation = HORIZONTAL
            setPadding(side, 0, side, 0)
            minimumHeight = px(40f)
            isClickable = true
            setOnClickListener { onEntry(e) }
        }
        applyHaRipple(row, 0f, theme.dark)
        row.addView(LogbookNode(ctx.context, dotColor(e) ?: Color.TRANSPARENT, theme.dividerColor, disabledColor(), unavailable, firstOfDay, lastOfDay),
            LayoutParams(px(28f), ViewGroup.LayoutParams.MATCH_PARENT).apply { marginEnd = px(8f) })

        val body = FrameLayout(ctx.context)
        val line = LinearLayout(ctx.context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val value = valueText(e)
        val primary = cssText(ctx, 14f, theme.primaryTextColor).apply {
            // overflow-wrap: anywhere; a long value wraps (the row grows past its 40 px).
            text = value
        }
        lineBox(ctx, primary, 14f, 1.6f)
        line.addView(primary, LayoutParams(0, WRAP_CONTENT, 1f))
        // .trailing: the cause badge and the time chip, 8 px apart, 8 px after the value.
        cause?.let { c -> line.addView(causeBadge(c), LayoutParams(px(20f), px(20f)).apply { marginStart = px(8f) }) }
        val time = LogbookCardView.timeText((e.whenSec * 1000).toLong())
        line.addView(cssText(ctx, 12f, theme.secondaryTextColor).apply {
            text = time
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            // .time-chip: at least 4.5em (6em with AM/PM), tabular figures.
            minWidth = px(12f * if (HaFormat.useAmPm()) 6f else 4.5f)
            maxLines = 1
            fontFeatureSettings = "tnum"
        }, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = px(8f) })
        // ha-icon-next: the clickable row's chevron, 8 px after the content.
        line.addView(MdiIconView(ctx.context).apply {
            setIcon("mdi:chevron-right", theme.secondaryTextColor)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LayoutParams(px(24f), px(24f)).apply { marginStart = px(8f) })
        body.addView(line, FrameLayout.LayoutParams(MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (!lastOfDay) {
            body.addView(View(ctx.context).apply { setBackgroundColor(theme.dividerColor) },
                FrameLayout.LayoutParams(MATCH_PARENT, px(1f).coerceAtLeast(1), Gravity.BOTTOM))
        }
        row.addView(body, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        // role="button": TalkBack reads the value, what caused it and when, and offers the tap.
        val label = listOfNotNull(value.takeIf { it.isNotEmpty() }, cause?.let(HaLogbook::causePhrase)?.takeIf { it.isNotEmpty() }, time)
            .joinToString(", ")
        VitroAccess.role(row, A11yRole.BUTTON, label)
        return row
    }

    /** `_renderValue`, first letter capitalized (`.primary-text:first-letter`). */
    private fun valueText(e: LogbookEntry): String {
        val v = HaLogbook.value(e, ::store) ?: return ""
        val text = if (v.isState) v.text else HaLogbook.messageText(v.text, ::store)
        return HaFormat.capitalize(text)
    }

    /**
     * The dot's color: with `graph-color`, a state change's dot is the state's color in the history timeline
     * (`computeTimelineColor`); an unavailable one is a ring ([LogbookNode]); a row without a state takes its
     * category's (an automation run light blue, anything else HA logged teal).
     */
    private fun dotColor(e: LogbookEntry): Int? {
        val eid = e.entityId
        val state = e.state
        if (eid != null && !state.isNullOrEmpty() && state != "unavailable") {
            // No state for the entity now: HA's `--history-unavailable-color`, transparent.
            if (store(eid) == null) return Color.TRANSPARENT
            return HaTimelineChart.stateColor(ctx, eid, state)
        }
        return when (HaLogbook.category(e)) {
            "automation" -> ThemeResolver.parseColor(theme.vars["logbook-category-automation-color"])
                ?: ThemeResolver.parseColor(theme.vars["light-blue-color"]) ?: LIGHT_BLUE
            "integration" -> ThemeResolver.parseColor(theme.vars["logbook-category-integration-color"])
                ?: ThemeResolver.parseColor(theme.vars["teal-color"]) ?: TEAL
            else -> theme.secondaryTextColor
        }
    }

    private fun disabledColor(): Int =
        ThemeResolver.parseColor(theme.vars["disabled-color"]) ?: if (theme.dark) 0xFF464646.toInt() else 0xFFBDBDBD.toInt()

    /**
     * `renderLogbookCauseIcon` at 20 px (`--cause-icon-size`): the cause's icon in the secondary text color, or a
     * user's `ha-user-badge`: the person's picture, else their initials on `--light-primary-color`.
     */
    private fun causeBadge(c: HaLogbook.Cause): View {
        val icon = HaLogbook.causeIcon(c)
        if (icon != null) {
            return MdiIconView(ctx.context).apply {
                setIcon(icon, theme.secondaryTextColor)
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        }
        val radius = USER_BADGE_RADIUS * ctx.dp
        val picture = c.userId?.let { HaLogbook.userPicture(it, users, ::store) }
        if (picture != null) {
            val image = ImageView(ctx.context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                background = GradientDrawable().apply { cornerRadius = radius; setColor(Color.TRANSPARENT) }
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            ctx.loadImage(picture, image) { bmp -> if (bmp != null) image.setImageBitmap(bmp) }
            return image
        }
        val initials = HaLogbook.initials(c.name)
        return TextView(ctx.context).apply {
            text = initials
            gravity = Gravity.CENTER
            includeFontPadding = false
            maxLines = 1
            // .initials (9 px at the cause's size; `.long`, three letters, `--ha-font-size-s`).
            val size = if (initials.length > 2) 12f else 9f
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, size * theme.fontScale)
            typeface = dev.glassworks.engine.vitro.ui.VitroFonts.typeface(ctx.context, Inter.REGULAR, size)
            setTextColor(ThemeResolver.parseColor(theme.vars["text-light-primary-color"]) ?: TEXT_LIGHT_PRIMARY)
            background = GradientDrawable().apply {
                cornerRadius = radius
                setColor(ThemeResolver.parseColor(theme.vars["light-primary-color"]) ?: LIGHT_PRIMARY)
            }
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

    private fun dayOf(ms: Long): Long {
        val tz = HaLocale.timeZone()
        return (ms + tz.getOffset(ms)).floorDiv(DAY_MS)
    }

    private fun sameDay(a: LogbookEntry, b: LogbookEntry, tz: java.util.TimeZone): Boolean {
        fun day(e: LogbookEntry): Long { val ms = (e.whenSec * 1000).toLong(); return (ms + tz.getOffset(ms)).floorDiv(DAY_MS) }
        return day(a) == day(b)
    }

    private fun px(css: Float): Int = Math.round(css * ctx.dp)

    companion object {
        /** `max-entries="100"`. */
        const val MAX_ENTRIES = 100

        /** `--logbook-max-height` in the dialog. */
        const val MAX_HEIGHT = 250f
        const val DAY_MS = 86_400_000L

        /** `--ha-picture-border-radius` (`--ha-border-radius-lg`). */
        private const val USER_BADGE_RADIUS = 12f
        private const val LIGHT_PRIMARY = 0xFFB3E5FC.toInt()
        private const val TEXT_LIGHT_PRIMARY = 0xFF212121.toInt()
        private const val LIGHT_BLUE = 0xFF03A9F4.toInt()
        private const val TEAL = 0xFF009688.toInt()

        /**
         * `/logbook?entity_id=…&start_date=…&back=1` as `createSearchParam` writes it, from `startOfYesterday()` on the
         * device's clock: the history section's link ([MoreInfoHistoryView.showMoreHref]) on the logbook's page.
         */
        fun showMoreHref(entityId: String, nowMs: Long, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): String =
            "/logbook" + MoreInfoHistoryView.showMoreHref(entityId, nowMs, zone).removePrefix("/history")

        /**
         * `ha-logbook`'s list height cap applies unless the window is at most 450 CSS px wide or 500 tall
         * (`--logbook-max-height: unset` there).
         */
        fun capped(widthDp: Int, heightDp: Int): Boolean = widthDp > 450 && heightDp > 500
    }
}
