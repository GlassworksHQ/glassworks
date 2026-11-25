/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.cards

import android.graphics.Canvas
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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.glassworks.engine.vitro.model.CardConfig
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.A11yRole
import dev.glassworks.engine.vitro.ui.CardSurface
import dev.glassworks.engine.vitro.ui.MdiIconView
import dev.glassworks.engine.vitro.ui.VitroCardContext
import dev.glassworks.engine.vitro.ui.VitroAccess
import dev.glassworks.engine.vitro.ui.VitroFonts
import dev.glassworks.ui.Inter
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.floor
import kotlin.math.roundToInt

/** One calendar event: times in epoch ms, an all-day event from its first day's local midnight. */
data class CalendarEvent(
    val summary: String,
    val startMs: Long,
    val endMs: Long,
    val allDay: Boolean,
    val calendarId: String,
)

/**
 * Native HA `calendar` card, as HA 2026's `hui-calendar-card` and `ha-full-calendar` draw it: 6 grid rows, the
 * view's title with previous and next, a Today button and the month / day / list toggle, then FullCalendar's view
 * (`initial_view`, the month grid by default; the list is 7 days from the shown day). Events come from
 * `calendar/event/subscribe` over the shown range, as HA asks for them; each calendar takes its color from HA's
 * graph palette by its place among the calendars.
 */
class CalendarCardView(ctx: VitroCardContext, private val config: CardConfig) : CardSurface(ctx) {
    private val ids: List<String> = calendarIds(config)
    private var view: String = config.raw.optString("initial_view", MONTH).takeIf { it in VIEWS } ?: MONTH
    /** A day inside the shown range: today, then wherever previous / next / Today take it. */
    private var anchor: Long = ctx.now()
    private val events = HashMap<String, List<CalendarEvent>>()
    private val unsubs = ArrayList<() -> Unit>()
    private var generation = 0

    private val titleView = label(24f, ctx.theme.primaryTextColor)
    private val body = FrameLayout(ctx.context)
    private val toggles = ArrayList<Pair<String, FrameLayout>>()

    private val divider = ctx.theme.dividerColor
    private val accentText = theme("ha-color-on-primary-normal") ?: if (ctx.theme.dark) 0xFF37C8FD.toInt() else 0xFF006787.toInt()
    private val toggleFill = theme("ha-color-fill-primary-normal-resting") ?: if (ctx.theme.dark) 0xFF002E3E.toInt() else 0xFFDFF3FC.toInt()
    private val dimText = theme("disabled-text-color") ?: if (ctx.theme.dark) 0xFF6F6F6F.toInt() else 0xFFBDBDBD.toInt()

    init {
        // ha-card: `padding: 0 8px 8px`; ha-full-calendar's header `padding: 0 8px 8px` inside it.
        val col = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.px(8f), 0, ctx.px(8f), ctx.px(8f))
        }
        config.title?.let { t ->
            col.addView(label(24f, ctx.theme.primaryTextColor).apply {
                text = t
                setPadding(ctx.px(8f), ctx.px(16f), ctx.px(8f), 0)
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        val header = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.px(8f), 0, ctx.px(8f), ctx.px(8f))
        }
        // The title and previous / next, 61 px.
        val top = FrameLayout(ctx.context)
        titleView.maxLines = 1
        titleView.ellipsize = TextUtils.TruncateAt.END
        top.addView(titleView, FrameLayout.LayoutParams(WRAP_CONTENT, (28.8f * ctx.dp).roundToInt(), Gravity.CENTER_VERTICAL or Gravity.START))
        top.addView(iconButton("mdi:chevron-left", "Previous") { step(-1) }, FrameLayout.LayoutParams(ctx.px(32f), ctx.px(32f), Gravity.CENTER_VERTICAL or Gravity.END).apply {
            rightMargin = ctx.px(35.5f)
        })
        top.addView(iconButton("mdi:chevron-right", "Next") { step(1) }, FrameLayout.LayoutParams(ctx.px(32f), ctx.px(32f), Gravity.CENTER_VERTICAL or Gravity.END))
        header.addView(top, LinearLayout.LayoutParams(MATCH_PARENT, ctx.px(61f)))
        // Today and the view toggle, 37 px (32 px buttons at its top).
        val second = FrameLayout(ctx.context)
        second.addView(label(14f, accentText, Inter.MEDIUM).apply {
            text = "Today"
            gravity = Gravity.CENTER
            setPadding(ctx.px(12f), 0, ctx.px(12f), 0)
            isClickable = true
            applyHaRipple(this, ctx.px(16f).toFloat(), ctx.theme.dark)
            setOnClickListener { anchor = ctx.now(); refresh() }
        }, FrameLayout.LayoutParams(WRAP_CONTENT, ctx.px(32f), Gravity.TOP or Gravity.START))
        val group = LinearLayout(ctx.context).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(MONTH to ("mdi:view-module" to "Month View"), DAY to ("mdi:view-day" to "Day View"), LIST to ("mdi:view-agenda" to "List View"))
            .forEachIndexed { i, (v, iconLabel) ->
                val b = FrameLayout(ctx.context).apply {
                    isClickable = true
                    contentDescription = iconLabel.second
                    addView(MdiIconView(ctx.context), FrameLayout.LayoutParams(ctx.px(20f), ctx.px(20f), Gravity.CENTER))
                    tag = iconLabel.first
                    setOnClickListener { view = v; refresh() }
                }
                // HA's view buttons: the one showing stays pressed.
                VitroAccess.role(b, A11yRole.TOGGLE_BUTTON) { view == v }
                toggles += v to b
                group.addView(b, LinearLayout.LayoutParams(ctx.px(46f), ctx.px(32f)).apply { if (i > 0) marginStart = ctx.px(1f) })
            }
        second.addView(group, FrameLayout.LayoutParams(WRAP_CONTENT, ctx.px(32f), Gravity.TOP or Gravity.END))
        header.addView(second, LinearLayout.LayoutParams(MATCH_PARENT, ctx.px(37f)))
        col.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        col.addView(body, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        addView(col, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        refreshChrome()
        renderBody()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Out of a grid, FullCalendar's own aspect ratio (1.35) sizes the view.
        val lp = body.layoutParams as LinearLayout.LayoutParams
        val grid = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY
        val h = if (grid) 0 else (MeasureSpec.getSize(widthMeasureSpec) / 1.35f).roundToInt()
        val w = if (grid) 1f else 0f
        if (lp.height != h || lp.weight != w) { lp.height = h; lp.weight = w }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        subscribe()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    /** A cached view another one hides stays attached: it drops its subscriptions, and opens them again when shown. */
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (!isAttachedToWindow) return
        if (isShown) subscribe() else stop()
    }

    private fun stop() {
        generation++
        unsubs.forEach { runCatching { it() } }
        unsubs.clear()
    }

    private fun refresh() {
        refreshChrome()
        renderBody()
        if (isAttachedToWindow) subscribe()
    }

    /** `calendar/event/subscribe` per calendar over the shown range, as `subscribeCalendarEvents` asks. */
    private fun subscribe() {
        stop()
        val gen = generation
        val (start, end) = range(view, anchor, TimeZone.getDefault())
        for (id in ids) {
            val msg = JSONObject().put("type", "calendar/event/subscribe").put("entity_id", id)
                .put("start", LogbookCardView.isoMillis(start)).put("end", LogbookCardView.isoMillis(end))
            unsubs += ctx.subscribeWs(msg, { ev ->
                val list = parseEvents(ev.optJSONArray("events"), id)
                ctx.main.post {
                    if (gen != generation) return@post
                    events[id] = list
                    renderBody()
                }
            }, { _ -> })
        }
    }

    private fun step(dir: Int) {
        val c = Calendar.getInstance().apply { timeInMillis = anchor }
        when (view) {
            MONTH -> c.add(Calendar.MONTH, dir)
            DAY -> c.add(Calendar.DAY_OF_MONTH, dir)
            else -> c.add(Calendar.DAY_OF_MONTH, 7 * dir)
        }
        anchor = c.timeInMillis
        refresh()
    }

    private fun refreshChrome() {
        titleView.text = title(view, anchor, TimeZone.getDefault())
        toggles.forEachIndexed { i, (v, b) ->
            val on = v == view
            val r = ctx.px(16f).toFloat()
            b.background = GradientDrawable().apply {
                setColor(if (on) ctx.theme.primaryColor else toggleFill)
                cornerRadii = when (i) {
                    0 -> floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
                    toggles.size - 1 -> floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
                    else -> floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
                }
            }
            (b.getChildAt(0) as MdiIconView).setIcon(b.tag as String, if (on) Color.WHITE else accentText)
        }
    }

    private fun allEvents(): List<CalendarEvent> = ids.flatMap { events[it].orEmpty() }

    private fun colorOf(calendarId: String): Int {
        // getCalendars: the calendars HA has, sorted, each its graph color by that place.
        val all = ctx.store.all().keys.filter { it.startsWith("calendar.") && ctx.store[it]?.state != "unavailable" }.sorted()
        val i = all.indexOf(calendarId).takeIf { it >= 0 } ?: ids.indexOf(calendarId).coerceAtLeast(0)
        return HaGraphColors.byIndex(ctx.theme, i)
    }

    private fun renderBody() {
        body.removeAllViews()
        val tz = TimeZone.getDefault()
        when (view) {
            LIST -> body.addView(listView(tz), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            else -> body.addView(MonthGrid(if (view == DAY) 1 else 7, if (view == DAY) 1 else 6, tz), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
    }

    // --------------------------------------------------------------------------------------------- list

    /**
     * FullCalendar's list (`listWeek`, 7 days): per day with events, a 39.8 px header (the weekday at 16 px on the
     * left, the date on the right, a line above), then 44 px rows: the time ("All-day"), the calendar's 10 px dot and
     * the summary, 14 px.
     */
    private fun listView(tz: TimeZone): View {
        val scroll = ScrollView(ctx.context).apply { isVerticalScrollBarEnabled = false }
        val col = LinearLayout(ctx.context).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(col, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val (start, end) = range(LIST, anchor, tz)
        val all = sortEvents(allEvents())
        var day = start
        var any = false
        while (day < end) {
            val next = addDays(day, 1, tz)
            val todays = all.filter { overlaps(it, day, next) }
            if (todays.isNotEmpty()) {
                any = true
                col.addView(listHeader(day, tz), LinearLayout.LayoutParams(MATCH_PARENT, (39.8f * ctx.dp).roundToInt()))
                for (e in todays) col.addView(listRow(e, tz), LinearLayout.LayoutParams(MATCH_PARENT, ctx.px(44f)))
            }
            day = next
        }
        if (!any) {
            return label(14f, ctx.theme.primaryTextColor).apply {
                text = "No events to display"
                gravity = Gravity.CENTER
            }
        }
        return scroll
    }

    private fun listHeader(day: Long, tz: TimeZone): View = FrameLayout(ctx.context).apply {
        setBackgroundColor(ctx.theme.cardBackgroundColor)
        addView(View(ctx.context).apply { setBackgroundColor(divider) }, FrameLayout.LayoutParams(MATCH_PARENT, ctx.px(1f).coerceAtLeast(1), Gravity.TOP))
        addView(label(16f, ctx.theme.primaryTextColor).apply {
            text = fmt("EEEE", tz).format(Date(day))
            gravity = Gravity.CENTER_VERTICAL
        }, FrameLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT, Gravity.START).apply { leftMargin = ctx.px(12f); topMargin = ctx.px(1f) })
        addView(label(16f, ctx.theme.primaryTextColor).apply {
            text = fmt("MMMM d, yyyy", tz).format(Date(day))
            gravity = Gravity.CENTER_VERTICAL
        }, FrameLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT, Gravity.END).apply { rightMargin = ctx.px(12f); topMargin = ctx.px(1f) })
    }

    private fun listRow(e: CalendarEvent, tz: TimeZone): View = LinearLayout(ctx.context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ctx.px(12f), 0, ctx.px(12f), 0)
        addView(label(14f, ctx.theme.primaryTextColor).apply {
            text = if (e.allDay) "All-day" else "${timeText(e.startMs, tz)} - ${timeText(e.endMs, tz)}"
            maxLines = 1
        }, LinearLayout.LayoutParams(ctx.px(114.5f), WRAP_CONTENT))
        addView(View(ctx.context).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(colorOf(e.calendarId)) }
        }, LinearLayout.LayoutParams(ctx.px(10f), ctx.px(10f)).apply { marginStart = ctx.px(12f); marginEnd = ctx.px(12f) })
        addView(label(14f, ctx.theme.primaryTextColor).apply {
            text = e.summary
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    }

    // --------------------------------------------------------------------------------------------- month / day grid

    /**
     * FullCalendar's day grid in HA's look: a 1 px bordered box (radius 4), a 15.9 px row of 10 px bold weekday names,
     * then equal week rows split by 1 px lines. Each day's number (12 px) sits centered at its top, today's
     * on a primary disc, other months' dimmed; under it the day's events if they all fit, else
     * as many as fit and a "+N more" link (half opacity on a past day).
     */
    private inner class MonthGrid(private val cols: Int, private val rows: Int, private val tz: TimeZone) : ViewGroup(ctx.context) {
        private val line = Paint().apply { color = divider }
        private val headerH = 15.9f * ctx.dp
        private val start = range(if (cols == 1) DAY else MONTH, anchor, tz).first
        private val month = Calendar.getInstance(tz).apply { timeInMillis = anchor }.get(Calendar.MONTH)
        private val today = startOfDay(ctx.now(), tz)
        private val names = ArrayList<TextView>()
        private val numbers = ArrayList<TextView>()
        private val cellViews = ArrayList<List<View>>()

        init {
            setWillNotDraw(false)
            background = GradientDrawable().apply {
                setColor(ctx.theme.cardBackgroundColor)
                setStroke(ctx.px(1f).coerceAtLeast(1), divider)
                cornerRadius = 4f * ctx.dp
            }
            val all = sortEvents(allEvents())
            for (c in 0 until cols) {
                val day = addDays(start, c, tz)
                names += label(10f, ctx.theme.primaryTextColor, 700).apply {
                    text = fmt(if (cols == 1) "EEEE" else "EEE", tz).format(Date(day))
                    isAllCaps = true
                    gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
                }.also { addView(it) }
            }
            for (i in 0 until cols * rows) {
                val day = addDays(start, i, tz)
                val inMonth = cols == 1 || Calendar.getInstance(tz).apply { timeInMillis = day }.get(Calendar.MONTH) == month
                val isToday = day == today
                numbers += label(12f, if (isToday) Color.WHITE else if (inMonth) ctx.theme.primaryTextColor else dimText).apply {
                    text = Calendar.getInstance(tz).apply { timeInMillis = day }.get(Calendar.DAY_OF_MONTH).toString()
                    gravity = Gravity.CENTER
                    if (isToday) background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ctx.theme.primaryColor) }
                }.also { addView(it) }
                val next = addDays(day, 1, tz)
                val todays = all.filter { overlaps(it, day, next) }
                cellViews += todays.map { e -> eventView(e).also { addView(it) } } + if (todays.isNotEmpty()) {
                    listOf(label(11f, ctx.theme.primaryTextColor).apply {
                        tag = MORE
                        text = "+${todays.size} more"
                        gravity = Gravity.CENTER
                        if (next <= today) alpha = 0.5f
                        isClickable = true
                        setOnClickListener { anchor = day; view = DAY; refresh() }
                    }.also { addView(it) })
                } else {
                    emptyList()
                }
            }
        }

        private fun eventView(e: CalendarEvent): View = label(12f, contrast(colorOf(e.calendarId))).apply {
            text = e.summary
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.px(4f), 0, ctx.px(4f), 0)
            background = GradientDrawable().apply { cornerRadius = 4f * ctx.dp; setColor(colorOf(e.calendarId)) }
        }

        private fun colW() = (width - 2f * ctx.dp) / cols
        private fun rowH() = (height - 2f * ctx.dp - headerH) / rows

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val h = MeasureSpec.getSize(heightMeasureSpec)
            setMeasuredDimension(w, h)
            val cw = (w - 2f * ctx.dp) / cols
            val any = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            names.forEach { it.measure(MeasureSpec.makeMeasureSpec(cw.roundToInt(), MeasureSpec.EXACTLY), any) }
            // Today's number fills its 24 x 26 disc; the others are as wide as their text, 25.2 px tall.
            numbers.forEach { n ->
                if (n.background != null) n.measure(MeasureSpec.makeMeasureSpec(ctx.px(24f), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ctx.px(26f), MeasureSpec.EXACTLY))
                else n.measure(any, MeasureSpec.makeMeasureSpec((25.2f * ctx.dp).roundToInt(), MeasureSpec.EXACTLY))
            }
            cellViews.flatten().forEach { it.measure(MeasureSpec.makeMeasureSpec((cw - 3f * ctx.dp).roundToInt().coerceAtLeast(0), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ctx.px(22f), MeasureSpec.EXACTLY)) }
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val cw = colW()
            val rh = rowH()
            val inset = 1f * ctx.dp
            names.forEachIndexed { c, v ->
                val x = (inset + c * cw).roundToInt()
                // The 10 px names' text 2 px under the box's top, as HA's 10.9 px line puts it.
                val y = (inset + 0.4f * ctx.dp).roundToInt()
                v.layout(x, y, x + cw.roundToInt(), y + v.measuredHeight)
            }
            for (i in numbers.indices) {
                val c = i % cols
                val row = i / cols
                val cellX = inset + c * cw
                val cellY = inset + headerH + row * rh
                // Measured in HA: the first week's number text 5 px into the cell, the later weeks' 10, centered in its
                // 25.2 px box (5.6 px down).
                val top = cellY + (if (row == 0) -0.6f else 4.4f) * ctx.dp
                val n = numbers[i]
                // ha-fc-day-number: 25.2 px tall (today's disc 24 x 26), centered in the cell.
                val today = n.background != null
                val nw = if (today) 24f * ctx.dp else n.measuredWidth.toFloat()
                val nh = if (today) 26f * ctx.dp else 25.2f * ctx.dp
                val nx = cellX + (cw - nw) / 2f
                n.layout(nx.roundToInt(), top.roundToInt(), (nx + nw).roundToInt(), (top + nh).roundToInt())
                // The events under it, 24 px apart, if all fit with room; else as many as fit and the "+N more" link.
                val views = cellViews[i]
                val evs = views.filter { it.tag != MORE }
                val more = views.firstOrNull { it.tag == MORE }
                val eventsTop = top + 33.8f * ctx.dp
                val room = cellY + rh - eventsTop
                val fitAll = evs.size * 24f * ctx.dp <= room
                val shown = if (fitAll) evs.size else floor(((room - 24f * ctx.dp) / (24f * ctx.dp)).toDouble()).toInt().coerceIn(0, evs.size)
                var y = eventsTop
                val x = (cellX + 1.5f * ctx.dp).roundToInt()
                evs.forEachIndexed { k, v ->
                    v.visibility = if (k < shown) VISIBLE else GONE
                    if (k < shown) {
                        v.layout(x, y.roundToInt(), x + v.measuredWidth, y.roundToInt() + ctx.px(22f))
                        y += 24f * ctx.dp
                    }
                }
                more?.let { m ->
                    m.visibility = if (fitAll) GONE else VISIBLE
                    if (!fitAll) m.layout(x, y.roundToInt(), x + m.measuredWidth, y.roundToInt() + ctx.px(22f))
                    (m as TextView).text = "+${evs.size - shown} more"
                }
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cw = colW()
            val rh = rowH()
            val px = ctx.px(1f).coerceAtLeast(1).toFloat()
            val inset = 1f * ctx.dp
            // The header's bottom line and each week's, then the lines between days.
            canvas.drawRect(inset, inset + headerH - px, width - inset, inset + headerH, line)
            for (r in 1 until rows) {
                val y = inset + headerH + r * rh - px
                canvas.drawRect(inset, y, width - inset, y + px, line)
            }
            for (c in 1 until cols) {
                val x = inset + c * cw
                canvas.drawRect(x, inset, x + px, height - inset, line)
            }
        }
    }

    // --------------------------------------------------------------------------------------------- helpers

    private fun label(sp: Float, color: Int, weight: Int = Inter.REGULAR): TextView = TextView(ctx.context).apply {
        val scaled = ctx.scaledSp(sp)
        textSize = scaled
        setTextColor(color)
        typeface = VitroFonts.typeface(ctx.context, weight, scaled)
        includeFontPadding = false
    }

    private fun iconButton(icon: String, description: String, onClick: () -> Unit): FrameLayout = FrameLayout(ctx.context).apply {
        isClickable = true
        contentDescription = description
        addView(MdiIconView(ctx.context).apply { setIcon(icon, ctx.theme.primaryTextColor) }, FrameLayout.LayoutParams(ctx.px(24f), ctx.px(24f), Gravity.CENTER))
        applyHaRipple(this, ctx.px(16f).toFloat(), ctx.theme.dark)
        setOnClickListener { onClick() }
    }

    private fun theme(name: String): Int? = ThemeResolver.parseColor(ctx.theme.vars[name])

    private fun contrast(bg: Int): Int = if (HaGraphColors.luminosity(bg) > 0.179) Color.BLACK else Color.WHITE

    companion object {
        const val MONTH = "dayGridMonth"
        const val DAY = "dayGridDay"
        const val LIST = "listWeek"
        private val VIEWS = setOf(MONTH, DAY, LIST)
        private const val MORE = "more"
        private val DATE_ONLY = Regex("""^\d{4}-\d{2}-\d{2}$""")

        /** The calendar entities the card names: `entities` (strings or objects), else `entity`. */
        fun calendarIds(config: CardConfig): List<String> {
            val fromList = config.entities().filter { it.isEntity }.map { it.entity }
            return fromList.ifEmpty { listOfNotNull(config.entity) }.filter { it.startsWith("calendar.") }.distinct()
        }

        /**
         * A subscription event's (or the REST answer's) events: `start` / `end` as strings, or `{dateTime}` /
         * `{date}`. A date is all day, from that day's local midnight.
         */
        fun parseEvents(arr: JSONArray?, calendarId: String, tz: TimeZone = TimeZone.getDefault()): List<CalendarEvent> {
            if (arr == null) return emptyList()
            val out = ArrayList<CalendarEvent>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val (start, allDay) = parseWhen(o.opt("start"), tz) ?: continue
                val end = parseWhen(o.opt("end"), tz)?.first ?: start
                out += CalendarEvent(o.optString("summary", "").ifEmpty { "Untitled" }, start, end, allDay, calendarId)
            }
            return out
        }

        private fun parseWhen(v: Any?, tz: TimeZone): Pair<Long, Boolean>? {
            val text = when (v) {
                is JSONObject -> v.optString("dateTime", "").ifEmpty { v.optString("date", "") }
                is String -> v
                else -> ""
            }.trim()
            if (text.isEmpty()) return null
            if (DATE_ONLY.matches(text)) {
                val parts = text.split('-').map { it.toInt() }
                return Calendar.getInstance(tz).apply { clear(); set(parts[0], parts[1] - 1, parts[2]) }.timeInMillis to true
            }
            val sec = dev.glassworks.engine.vitro.data.EntityStore.parseTimestamp(text)
            return if (sec > 0) (sec * 1000).toLong() to false else null
        }

        /**
         * The range a view shows (and asks HA for): the month grid's 6 weeks from the Sunday on or before the 1st, a
         * day, or the list's 7 days from the shown day; local midnights.
         */
        fun range(view: String, anchorMs: Long, tz: TimeZone): Pair<Long, Long> {
            val day = startOfDay(anchorMs, tz)
            return when (view) {
                MONTH -> {
                    val c = Calendar.getInstance(tz).apply { timeInMillis = day; set(Calendar.DAY_OF_MONTH, 1) }
                    val back = (c.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY + 7) % 7
                    val start = addDays(c.timeInMillis, -back, tz)
                    start to addDays(start, 42, tz)
                }
                DAY -> day to addDays(day, 1, tz)
                else -> day to addDays(day, 7, tz)
            }
        }

        /** FullCalendar's title: "November 2026", "November 24, 2026", or the list's "Nov 24 – 30, 2026". */
        fun title(view: String, anchorMs: Long, tz: TimeZone): String {
            val (start, end) = range(view, anchorMs, tz)
            return when (view) {
                MONTH -> fmt("MMMM yyyy", tz).format(Date(anchorMs))
                DAY -> fmt("MMMM d, yyyy", tz).format(Date(start))
                else -> {
                    val last = addDays(end, -1, tz)
                    val a = Calendar.getInstance(tz).apply { timeInMillis = start }
                    val b = Calendar.getInstance(tz).apply { timeInMillis = last }
                    when {
                        a.get(Calendar.YEAR) != b.get(Calendar.YEAR) ->
                            "${fmt("MMM d, yyyy", tz).format(Date(start))} – ${fmt("MMM d, yyyy", tz).format(Date(last))}"
                        a.get(Calendar.MONTH) != b.get(Calendar.MONTH) ->
                            "${fmt("MMM d", tz).format(Date(start))} – ${fmt("MMM d, yyyy", tz).format(Date(last))}"
                        else -> "${fmt("MMM d", tz).format(Date(start))} – ${fmt("d, yyyy", tz).format(Date(last))}"
                    }
                }
            }
        }

        /** FullCalendar's event order: start, longer first, all-day first, title. */
        fun sortEvents(events: List<CalendarEvent>): List<CalendarEvent> = events.sortedWith(
            compareBy<CalendarEvent> { it.startMs }.thenByDescending { it.endMs - it.startMs }.thenBy { !it.allDay }.thenBy { it.summary },
        )

        private fun overlaps(e: CalendarEvent, dayStart: Long, dayEnd: Long): Boolean =
            e.startMs < dayEnd && (e.endMs > dayStart || (e.endMs == e.startMs && e.startMs >= dayStart))

        private fun fmt(pattern: String, tz: TimeZone) = SimpleDateFormat(pattern, Locale.US).apply { timeZone = tz }

        private fun timeText(ms: Long, tz: TimeZone): String = fmt("h:mm a", tz).format(Date(ms))

        private fun startOfDay(ms: Long, tz: TimeZone): Long =
            Calendar.getInstance(tz).apply { timeInMillis = ms; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

        private fun addDays(ms: Long, days: Int, tz: TimeZone): Long =
            Calendar.getInstance(tz).apply { timeInMillis = ms; add(Calendar.DAY_OF_MONTH, days) }.timeInMillis
    }
}
