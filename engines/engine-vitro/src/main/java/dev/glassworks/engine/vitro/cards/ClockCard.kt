/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.cards

import dev.glassworks.engine.vitro.data.FrontendLocale
import dev.glassworks.engine.vitro.data.HaIntl
import dev.glassworks.engine.vitro.data.HaLocale
import dev.glassworks.engine.vitro.data.HaFormat
import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import dev.glassworks.engine.vitro.model.CardConfig
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.CardSurface
import dev.glassworks.engine.vitro.ui.LocalRelayout
import dev.glassworks.engine.vitro.ui.VitroCardContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * HA's `clock` card (digital style), built from the same DOM and styles as `hui-clock-card` and
 * `hui-clock-card-digital`: `clock_size`, `time_format`, `time_zone`, `show_seconds`, `no_background` and `title`,
 * the `--ha-clock-card-digital-font-size-*` variables, and card-mod's `"."` sheet (the card, `.time-wrapper`) and
 * `"hui-clock-card-digital$"` sheet (`.clock-container`, `.time-parts`, `.time-part`). The styles go through the
 * Markdown engine's cascade, so card-mod rules apply the way they do in the browser.
 */
class ClockCardView(ctx: VitroCardContext, private val config: CardConfig) : CardSurface(ctx) {
    private val raw = config.raw
    private val size = raw.optString("clock_size", "").takeIf { it == "medium" || it == "large" }
    private val noBackground = raw.optBoolean("no_background", false)
    private val showSeconds = raw.optBoolean("show_seconds", false)
    /** The user's locale with the card's `time_format` over it (`clock-date-format.ts`'s first step). */
    private val locale: FrontendLocale = HaLocale.current.let { l ->
        raw.optString("time_format", "").takeIf { it.isNotEmpty() }?.let { l.copy(timeFormat = it) } ?: l
    }
    private val use12h = HaFormat.useAmPm(locale)
    /** HA's digits: `Intl.DateTimeFormat(language, {hour, minute, second, hourCycle})`'s parts. */
    private val timeParts = HaIntl.of(locale.language).dateFormat(if (use12h) "timeSeconds.h12" else "timeSeconds.h23")
        ?: HaIntl.of("en").dateFormat(if (use12h) "timeSeconds.h12" else "timeSeconds.h23")!!
    private val hourField = timeParts.tokens.filterIsInstance<HaIntl.DateTemplate.Token.Field>().firstOrNull { it.name.lowercase() in setOf("h", "hh") }?.name ?: "H"
    private val wrapper = FlexColumn(ctx.context)
    private lateinit var hourView: TextView
    private lateinit var minuteView: TextView
    private var secondView: TextView? = null
    private var ampmView: TextView? = null

    /** HA's `date_format`: a preset, or the parts of the date line ([ClockDate]); null when the card shows no date. */
    private val dateFormat: ClockDate? = ClockDate.of(raw.opt("date_format"))
    private val dateLines = ArrayList<DateLine>()
    private var appliedZone: String? = null
    /**
     * The zone the date line is written in. Declared before `init`, which renders and sets it: a property initialized
     * after `init` would overwrite it with the device's zone, and later ticks (same [appliedZone]) never fix it.
     */
    private var dateZone: TimeZone = HaLocale.timeZone(locale)
    private var running = false
    private var cardCss: String? = null

    /** The resolved horizontal placement of the digits, for tests: [Gravity.START], center or [Gravity.END]. */
    internal var horizontalGravity: Int = Gravity.CENTER_HORIZONTAL
        private set

    /** The digits' font size in CSS px and weight, for tests. */
    internal var digitsPx: Float = 0f
        private set
    internal var digitsWeight: Int = 0
        private set

    /**
     * The tick's new digits lay out this card again, not the dashboard: the card keeps its size as the time changes
     * ([LocalRelayout]). Null while CardSurface's constructor runs.
     */
    private var local: LocalRelayout? = LocalRelayout(this)

    override fun requestLayout() {
        if (local?.take() != true) super.requestLayout()
    }

    override fun measureCard(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        local?.onMeasure(widthMeasureSpec, heightMeasureSpec)
        super.measureCard(widthMeasureSpec, heightMeasureSpec)
    }

    private val tick = object : Runnable {
        override fun run() {
            // Attached but hidden (a cached tab behind another): no ticking; showing it again resumes.
            if (!running || !isShown) return
            // Only the digits and the date change here.
            val l = local
            if (l != null) l.run { render(Date()) } else render(Date())
            ctx.main.postDelayed(this, if (showSeconds) 1_000L else 5_000L)
        }
    }

    init {
        addView(wrapper, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        cardCss = ThemeResolver.cardModStyleString(raw.optJSONObject("card_mod"))?.takeIf { "{{" !in it && "{%" !in it }
        build()
    }

    // card-mod padding on ha-card adds to the wrapper's padding, as in the browser.
    override fun clearDefaultInnerPadding() = Unit

    override fun onCardModApplied(mod: dev.glassworks.engine.vitro.theme.CardModStyle) {
        dropBackground()
        if (activeCardCss != cardCss) {
            cardCss = activeCardCss
            build()
        }
    }

    // HA's `.no-background` (a class) outranks card-mod's `ha-card { background: … }`.
    private fun dropBackground() {
        if (noBackground) {
            dropSurface()
            elevation = 0f
        }
    }

    private fun build() {
        dropBackground()
        wrapper.removeAllViews()
        val vars = ctx.theme.vars
        val sizeClass = size?.let { " size-$it" } ?: ""
        // The card's shadow root: ha-card > .time-wrapper > hui-clock-card-digital.
        val card = MdNode("ha-card", if (noBackground) mapOf("class" to "no-background") else emptyMap())
        val timeWrapper = card.add(MdNode("div", mapOf("class" to "time-wrapper$sizeClass")))
        val title = raw.optString("title", "").takeIf { raw.has("title") }?.let { timeWrapper.add(MdNode("div", mapOf("class" to "time-title"))) }
        val digital = timeWrapper.add(MdNode("hui-clock-card-digital"))
        val cardSheet = MdSheet.parse(cardCss)
        MdCascade.apply(card, MarkdownRenderer(ctx).defaultRootStyle(), listOf(HOST to 1, cardSheet to 2, CARD_STYLES to 3), vars)
        // The digital clock's own shadow root.
        val shadow = MdNode("shadow-root")
        val container = shadow.add(MdNode("div", mapOf("class" to "clock-container")))
        val parts = container.add(MdNode("div", mapOf("class" to "time-parts$sizeClass")))
        val hour = parts.add(MdNode("div", mapOf("class" to "time-part hour")))
        val minute = parts.add(MdNode("div", mapOf("class" to "time-part minute")))
        val second = if (showSeconds) parts.add(MdNode("div", mapOf("class" to "time-part second"))) else null
        val ampm = if (use12h) parts.add(MdNode("div", mapOf("class" to "time-part am-pm"))) else null
        // `.date-container > .date > .date-line` per line, when the card shows a date.
        val date = dateFormat?.let {
            shadow.add(MdNode("div", mapOf("class" to "date-container"))).add(MdNode("div", mapOf("class" to "date$sizeClass")))
        }
        val digitalSheet = MdSheet.parse(ThemeResolver.cardModSheet(raw.optJSONObject("card_mod"), "hui-clock-card-digital$"))
        MdCascade.apply(shadow, digital.style!!, listOf(digitalSheet to 2, DIGITAL_STYLES to 3), vars)

        val dp = ctx.dp
        val ws = timeWrapper.style!!
        wrapper.setPadding((ws.padding[3] * dp).roundToInt(), (ws.padding[0] * dp).roundToInt(), (ws.padding[1] * dp).roundToInt(), (ws.padding[2] * dp).roundToInt())
        val cross = flexGravity(ws.alignItems ?: "center", horizontal = true)
        // `.time-wrapper { height: calc(100% - 12px) }`, `- 32px` for medium and large: HA keeps it even when
        // card-mod changes the padding, so the digits center in that box.
        wrapper.heightInset = ((if (size != null) 32f else 12f) * dp).roundToInt()
        wrapper.justify = flexGravity(ws.justifyContent ?: "center", horizontal = false)
        wrapper.align = cross
        clipChildren = false
        val rowGap = ((if (size != null) 12f else 6f) * dp).roundToInt()

        title?.let { t ->
            val tv = MarkdownRenderer.cssLine(ctx.context, dp, raw.optString("title"), t.style!!)
            tv.gravity = Gravity.CENTER_HORIZONTAL
            wrapper.gap = rowGap
            wrapper.addView(tv)
        }

        // .clock-container is a full-width flex row; its justify-content places the digits.
        val cs = container.style!!
        val row = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            gravity = flexGravity(cs.justifyContent ?: "normal", horizontal = true)
        }
        horizontalGravity = when {
            cross == Gravity.START && (cs.justifyContent == null || cs.justifyContent in START_KEYWORDS) -> Gravity.START
            cs.justifyContent in END_KEYWORDS -> Gravity.END
            cs.justifyContent in START_KEYWORDS -> Gravity.START
            else -> Gravity.CENTER_HORIZONTAL
        }
        val ps = parts.style!!
        digitsPx = ps.fontSize
        digitsWeight = ps.fontWeight
        val partsView = ClockParts(ctx.context, ps.alignItems ?: "center")
        hourView = MarkdownRenderer.cssLine(ctx.context, dp, "", hour.style!!)
        val colon = MarkdownRenderer.cssLine(ctx.context, dp, ":", hour.style!!)
        minuteView = MarkdownRenderer.cssLine(ctx.context, dp, "", minute.style!!)
        val digits = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.HORIZONTAL
            // `line-height: 0.8`: the digits paint above and below their line boxes, so nothing up the tree clips.
            clipChildren = false
            addView(hourView)
            // `.hour:after { content: ":"; margin: 0 2px }`
            addView(colon, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = (2 * dp).roundToInt(); rightMargin = (2 * dp).roundToInt()
            })
            addView(minuteView)
        }
        partsView.addView(digits)
        secondView = second?.let { n -> MarkdownRenderer.cssLine(ctx.context, dp, "", n.style!!).also { partsView.addSide(it, n.style!!, top = true) } }
        ampmView = ampm?.let { n -> MarkdownRenderer.cssLine(ctx.context, dp, "", n.style!!).also { partsView.addSide(it, n.style!!, top = false) } }
        row.addView(partsView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        dateLines.clear()
        val column = if (date == null) row else LinearLayout(ctx.context).apply {
            // hui-clock-card-digital: the clock container, then the date container under it.
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            val ds = date.style!!
            val top = (date.parent!!.style!!.margin[0].takeIf { !it.isNaN() } ?: 0f) * dp
            repeat(dateFormat!!.lineCount) { i ->
                val line = DateLine(ctx.context, MarkdownRenderer.cssLine(ctx.context, dp, "", ds))
                dateLines += line
                addView(line, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    if (i == 0) topMargin = top.roundToInt()
                })
            }
        }
        wrapper.addView(column)
        appliedZone = null
        render(Date())
    }

    /** A flex `justify-content` / `align-items` keyword as gravity on one axis. */
    private fun flexGravity(v: String, horizontal: Boolean): Int = when (v) {
        in START_KEYWORDS -> if (horizontal) Gravity.START else Gravity.TOP
        in END_KEYWORDS -> if (horizontal) Gravity.END else Gravity.BOTTOM
        "center" -> if (horizontal) Gravity.CENTER_HORIZONTAL else Gravity.CENTER_VERTICAL
        else -> if (horizontal) Gravity.START else Gravity.TOP
    }

    /**
     * HA's clock `time_zone`: a zone name, `local` (the panel's), `server` (HA's); with none, the user's own choice of
     * the panel's or HA's zone (`resolveTimeZone`).
     */
    private fun applyZone() {
        val configured = raw.optString("time_zone", "").trim()
        val server = runCatching { ctx.haConfig().optString("time_zone", "") }.getOrDefault("").trim()
        val tzId = when (configured) {
            "" -> if (locale.timeZone == "server") server else ""
            "server" -> server
            "local" -> ""
            else -> configured
        }
        // `local` follows the panel's zone, which may change while the card stays up: key it by that zone's id.
        val local = if (tzId.isEmpty()) HaLocale.deviceTimeZone() else null
        val key = local?.let { "local:" + it.id } ?: tzId
        if (key == appliedZone) return
        appliedZone = key
        val zone = local ?: TimeZone.getTimeZone(tzId)
        dateZone = zone
    }

    private fun TextView.setLine(t: String) {
        val old = text as? Spanned
        val span = old?.getSpans(0, old.length, CssLineBoxSpan::class.java)?.firstOrNull()
        val sb = SpannableStringBuilder(t)
        if (span != null) sb.setSpan(span, 0, sb.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        if (sb.toString() != text?.toString()) text = sb
    }

    private fun render(now: Date) {
        applyZone()
        hourView.setLine(timeParts.field(hourField, now.time, dateZone))
        minuteView.setLine(timeParts.field("mm", now.time, dateZone))
        secondView?.setLine(timeParts.field("ss", now.time, dateZone))
        ampmView?.setLine(timeParts.field("a", now.time, dateZone))
        dateFormat?.let { f ->
            val lines = f.format(now, locale.language, dateZone).split('\n')
            dateLines.forEachIndexed { i, line -> line.text.setLine(lines.getOrElse(i) { "" }) }
        }
    }

    /** The date text after the clock's last render (tests). */
    internal val renderedDate: String get() = dateLines.joinToString("\n") { it.text.text.toString() }

    /**
     * One line of the date: `.date { overflow: hidden; white-space: nowrap; text-align: center }` around HA's
     * `ha-marquee-text`. A line that fits is centered. A longer one scrolls in HA, and rests with its end at the box's
     * end and its start cut off (the large clock's "onday November 23"): Vitro draws that resting position, without
     * the scroll. The box is the font's normal line height, the line box (`line-height: 1.1`) at its bottom.
     */
    private class DateLine(context: Context, val text: TextView) : ViewGroup(context) {
        init {
            addView(text)
        }

        // `overflow: hidden`: the line is cut at the box (clipChildren only cuts a child at its own bounds).
        override fun dispatchDraw(canvas: android.graphics.Canvas) {
            canvas.save()
            canvas.clipRect(0, 0, width, height)
            super.dispatchDraw(canvas)
            canvas.restore()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            text.measure(unspecified, unspecified)
            val fm = text.paint.fontMetrics
            val normal = kotlin.math.ceil(fm.descent - fm.ascent).toInt()
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), max(normal, text.measuredHeight))
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val w = r - l
            val tw = text.measuredWidth
            val x = if (tw <= w) (w - tw) / 2 else w - tw
            val y = (b - t) - text.measuredHeight
            text.layout(x, y, x + tw, y + text.measuredHeight)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        tick.run()
    }

    override fun onDetachedFromWindow() {
        running = false
        ctx.main.removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    /** Hidden: the timer stops. Shown again: the current time at once, then the timer. */
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (!running) return
        ctx.main.removeCallbacks(tick)
        if (isShown) tick.run()
    }

    internal val renderedTime: String get() = "${hourView.text}:${minuteView.text}"
    internal val renderedAmPm: String get() = ampmView?.text?.toString().orEmpty()

    /** Re-renders [now] (tests). */
    internal fun renderAt(now: Date) = render(now)

    /**
     * `.time-wrapper`: a flex column whose children (title, `.clock-container` at full width) are placed by
     * `justify-content` and may overflow the card evenly, as in the browser, instead of being clipped.
     */
    private class FlexColumn(context: Context) : ViewGroup(context) {
        var justify = Gravity.CENTER_VERTICAL
        var align = Gravity.CENTER_HORIZONTAL
        var gap = 0

        /** `height: calc(100% - Npx)`: the content box is the card's height less this, whatever the padding. */
        var heightInset = 0

        init { clipChildren = false; clipToPadding = false }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val inner = (w - paddingLeft - paddingRight).coerceAtLeast(0)
            var total = 0
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                c.measure(MeasureSpec.makeMeasureSpec(inner, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                total += c.measuredHeight + if (i > 0) gap else 0
            }
            val own = total + paddingTop + paddingBottom
            val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(heightMeasureSpec) else own
            setMeasuredDimension(w, h)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            var total = 0
            for (i in 0 until childCount) total += getChildAt(i).measuredHeight + if (i > 0) gap else 0
            val space = (b - t) - heightInset - total
            var y = paddingTop + when (justify) {
                Gravity.TOP -> 0
                Gravity.BOTTOM -> space
                else -> space / 2
            }
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                c.layout(paddingLeft, y, paddingLeft + c.measuredWidth, y + c.measuredHeight)
                y += c.measuredHeight + gap
            }
        }
    }

    /**
     * `.time-parts`: a grid of "hour minute second" over "hour minute am-pm". The digits span both rows; the
     * second sits in the top row and AM/PM in the bottom one, each aligned in its row by `align-self` (else the
     * grid's `align-items`), with its `margin-left`.
     */
    private class ClockParts(context: Context, private val alignItems: String) : ViewGroup(context) {
        private class Side(val view: View, val marginLeft: Int, val align: String, val top: Boolean)
        private val sides = ArrayList<Side>()

        fun addSide(view: TextView, s: MdStyle, top: Boolean) {
            sides += Side(view, (s.margin[3].takeIf { !it.isNaN() } ?: 0f).let { (it * resources.displayMetrics.density).roundToInt() }, s.alignSelf ?: alignItems, top)
            addView(view)
        }

        private var rowTop = 0
        private var rowBottom = 0

        init { clipChildren = false }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            val digits = getChildAt(0)
            digits.measure(unspecified, unspecified)
            var sideW = 0
            var topH = 0
            var bottomH = 0
            for (s in sides) {
                s.view.measure(unspecified, unspecified)
                sideW = max(sideW, s.view.measuredWidth + s.marginLeft)
                if (s.top) topH = max(topH, s.view.measuredHeight) else bottomH = max(bottomH, s.view.measuredHeight)
            }
            val h = max(digits.measuredHeight, topH + bottomH)
            // The digits span both rows: what they need beyond the rows' own content goes to the rows, as the grid does
            // it. A row with nothing in it (no seconds, or no AM/PM) has no limit and takes it all, so a lone AM/PM
            // sits at the bottom (HA's DOM); with both rows filled it is shared.
            val extra = (h - topH - bottomH).coerceAtLeast(0)
            val hasTop = sides.any { it.top }
            val hasBottom = sides.any { !it.top }
            rowTop = topH + when {
                hasBottom && !hasTop -> extra
                hasTop && !hasBottom -> 0
                else -> extra / 2
            }
            rowBottom = h - rowTop
            setMeasuredDimension(digits.measuredWidth + sideW, h)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val digits = getChildAt(0)
            val h = b - t
            val dy = alignedOffset(alignItems, h, digits.measuredHeight)
            digits.layout(0, dy, digits.measuredWidth, dy + digits.measuredHeight)
            for (s in sides) {
                val areaTop = if (s.top) 0 else rowTop
                val areaH = if (s.top) rowTop else rowBottom
                val y = areaTop + alignedOffset(s.align, areaH, s.view.measuredHeight)
                val x = digits.measuredWidth + s.marginLeft
                s.view.layout(x, y, x + s.view.measuredWidth, y + s.view.measuredHeight)
            }
        }

        private fun alignedOffset(align: String, area: Int, size: Int): Int = when (align) {
            in START_KEYWORDS -> 0
            in END_KEYWORDS -> area - size
            "stretch", "normal" -> 0
            else -> (area - size) / 2
        }
    }

    private companion object {
        val START_KEYWORDS = setOf("start", "flex-start", "self-start", "left", "baseline", "first baseline")
        val END_KEYWORDS = setOf("end", "flex-end", "self-end", "right", "last baseline")

        val HOST = MdSheet.parse("ha-card { color: var(--primary-text-color); display: block }")

        /** `hui-clock-card`'s styles. */
        val CARD_STYLES = MdSheet.parse(
            """
            .no-background { background: none; border: none }
            .time-wrapper { display: flex; align-items: center; justify-content: center; padding: 6px 8px }
            .time-wrapper.size-medium, .time-wrapper.size-large { padding: 16px }
            .time-title { color: var(--primary-text-color); font-size: var(--ha-font-size-m); font-weight: var(--ha-font-weight-normal); line-height: var(--ha-line-height-condensed); text-align: center }
            .time-wrapper.size-medium .time-title { font-size: var(--ha-font-size-l) }
            .time-wrapper.size-large .time-title { font-size: var(--ha-font-size-2xl) }
            hui-clock-card-digital { display: block }
            """.trimIndent(),
        )

        /** `hui-clock-card-digital`'s styles. */
        val DIGITAL_STYLES = MdSheet.parse(
            """
            .clock-container { display: flex; justify-content: center }
            .time-parts { align-items: center; font-size: var(--ha-clock-card-digital-font-size-small, var(--ha-font-size-2xl)); font-weight: var(--ha-font-weight-medium); line-height: 0.8 }
            .time-parts.size-medium { font-size: var(--ha-clock-card-digital-font-size-medium, calc(48px * var(--ha-font-size-scale))) }
            .time-parts.size-large { font-size: var(--ha-clock-card-digital-font-size-large, calc(64px * var(--ha-font-size-scale))) }
            .time-parts.size-medium .time-part.second, .time-parts.size-medium .time-part.am-pm { font-size: var(--ha-font-size-l); margin-left: 6px }
            .time-parts.size-large .time-part.second, .time-parts.size-large .time-part.am-pm { font-size: var(--ha-font-size-2xl); margin-left: 8px }
            .time-parts .time-part.second { line-height: 0.9; opacity: 0.4 }
            .time-parts .time-part.am-pm { line-height: 0.9; opacity: 0.6 }
            .time-parts .time-part.second, .time-parts .time-part.am-pm { font-size: var(--ha-font-size-xs); margin-left: 4px }
            .date-container { margin-top: var(--ha-space-1) }
            .date { text-align: center; opacity: 0.8; font-size: var(--ha-font-size-s); line-height: 1.1 }
            .date.size-medium { font-size: var(--ha-font-size-l) }
            .date.size-large { font-size: var(--ha-font-size-2xl) }
            """.trimIndent(),
        )
    }
}

/**
 * HA's clock `date_format` (`clock-date-format.ts`): a preset (`short`, `medium`, `long`, `full`: the locale's date
 * styles; `iso`: YYYY-MM-DD), or a list of parts. Value parts are the locale's field forms, separator parts are written
 * as they are (`separator-new-line` starts a line), and adjacent values get one space between them.
 */
internal class ClockDate private constructor(private val preset: String?, private val parts: List<String>) {
    /** How many lines the date has (one per `separator-new-line`, plus one). */
    val lineCount: Int get() = if (preset != null) 1 else parts.count { it == "separator-new-line" } + 1

    // Formatters for the last locale and zone, and the last minute's text: the clock ticks every few seconds and the
    // date only changes with the minute (HA formats it once per minute too).
    private var formatsFor: Pair<String, TimeZone>? = null
    private var formats: List<((Date) -> String)?> = emptyList()
    private var lastMinute = Long.MIN_VALUE
    private var last = ""

    /** The date line for [date] in [language]'s forms, in [zone]. */
    fun format(date: Date, language: String, zone: TimeZone): String {
        val locale = language
        val minute = date.time / 60_000L
        if (formatsFor == locale to zone && minute == lastMinute) return last
        if (formatsFor != locale to zone) {
            formatsFor = locale to zone
            formats = if (preset != null) listOf(presetFormat(preset, locale, zone)) else parts.map { p ->
                PATTERNS[p]?.let { name ->
                    val t = HaIntl.of(locale).dateFormat(name) ?: HaIntl.of("en").dateFormat(name)
                    t?.let { f -> { d: Date -> f.format(d.time, zone) } }
                }
            }
        }
        lastMinute = minute
        last = if (preset != null) formats[0]!!(date) else buildString {
            var lastWasValue = false
            parts.forEachIndexed { i, part ->
                val separator = SEPARATORS[part]
                if (separator != null) {
                    append(separator)
                    lastWasValue = false
                } else {
                    val value = formats[i]?.invoke(date).orEmpty()
                    if (value.isNotEmpty()) {
                        if (lastWasValue) append(' ')
                        append(value)
                        lastWasValue = true
                    }
                }
            }
        }
        return last
    }

    /**
     * A preset (an older card's `date_format` string; HA 2026.11 reads only a list of parts): the date styles of the
     * user's language, or ISO.
     */
    private fun presetFormat(preset: String, language: String, zone: TimeZone): (Date) -> String {
        val f = if (preset == "iso") SimpleDateFormat("yyyy-MM-dd", Locale.ROOT) else {
            val style = when (preset) {
                "short" -> java.text.DateFormat.SHORT
                "medium" -> java.text.DateFormat.MEDIUM
                "long" -> java.text.DateFormat.LONG
                else -> java.text.DateFormat.FULL
            }
            java.text.DateFormat.getDateInstance(style, Locale.forLanguageTag(language))
        }
        f.timeZone = zone
        return { d -> f.format(d) }
    }

    companion object {
        private val PRESETS = setOf("short", "medium", "long", "full", "iso")
        /** Each part's own `Intl.DateTimeFormat` options, by the name of their captured template ([HaIntl]). */
        private val PATTERNS = mapOf(
            "weekday-short" to "weekdayShort", "weekday-long" to "weekday",
            "day-numeric" to "dayNumeric", "day-2-digit" to "day2",
            "month-short" to "monthShort", "month-long" to "month", "month-numeric" to "monthNumeric", "month-2-digit" to "month2",
            "year-2-digit" to "year2", "year-numeric" to "year",
        )
        private val SEPARATORS = mapOf(
            "separator-dash" to "-", "separator-slash" to "/", "separator-dot" to ".",
            "separator-comma" to ", ", "separator-new-line" to "\n",
        )

        /** The card's `date_format`, or null when it shows no date (none, an empty list, an unknown value). */
        fun of(value: Any?): ClockDate? = when (value) {
            is String -> if (value in PRESETS) ClockDate(value, emptyList()) else null
            is org.json.JSONArray -> (0 until value.length()).map { value.optString(it) }
                .filter { it in PATTERNS || it in SEPARATORS }
                .takeIf { it.isNotEmpty() }?.let { ClockDate(null, it) }
            else -> null
        }
    }
}

