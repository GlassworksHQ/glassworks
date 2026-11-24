/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.cards

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import dev.glassworks.engine.vitro.model.CardConfig
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.CardSurface
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
    private val use12h = when (raw.optString("time_format", "")) {
        "12" -> true
        "24" -> false
        else -> !DateFormat.is24HourFormat(ctx.context)
    }
    private val hourFormat = SimpleDateFormat(if (use12h) "h" else "HH", Locale.getDefault())
    private val minuteFormat = SimpleDateFormat("mm", Locale.getDefault())
    private val secondFormat = SimpleDateFormat("ss", Locale.getDefault())
    private val ampmFormat = SimpleDateFormat("a", Locale.getDefault())
    private val wrapper = FlexColumn(ctx.context)
    private lateinit var hourView: TextView
    private lateinit var minuteView: TextView
    private var secondView: TextView? = null
    private var ampmView: TextView? = null
    private var appliedZone: String? = null
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

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            render(Date())
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
            background = null
            elevation = 0f
            clipToOutline = false
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
        wrapper.addView(row)
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

    /** HA's clock `time_zone`: a zone name, `local` (the panel's), or the server's (default). */
    private fun applyZone() {
        val configured = raw.optString("time_zone", "").trim()
        val tzId = when (configured) {
            "", "server" -> runCatching { ctx.haConfig().optString("time_zone", "") }.getOrDefault("").trim()
            "local" -> ""
            else -> configured
        }
        // `local` follows the panel's zone, which may change while the card stays up: key it by that zone's id.
        val local = if (tzId.isEmpty()) TimeZone.getDefault() else null
        val key = local?.let { "local:" + it.id } ?: tzId
        if (key == appliedZone) return
        appliedZone = key
        val zone = local ?: TimeZone.getTimeZone(tzId)
        listOf(hourFormat, minuteFormat, secondFormat, ampmFormat).forEach { it.timeZone = zone }
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
        hourView.setLine(hourFormat.format(now))
        minuteView.setLine(minuteFormat.format(now))
        secondView?.setLine(secondFormat.format(now))
        ampmView?.setLine(ampmFormat.format(now).uppercase(Locale.getDefault()))
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
            // The digits span both rows: what they need beyond the rows' own content is shared between the rows.
            val extra = (h - topH - bottomH).coerceAtLeast(0)
            rowTop = topH + extra / 2
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

        /** `hui-clock-card-digital`'s styles (the date line isn't drawn yet). */
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
            """.trimIndent(),
        )
    }
}
