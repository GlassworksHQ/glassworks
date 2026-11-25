/* SPDX-License-Identifier: MIT
 *
 * Ported from weather-card (bramkragten/weather-card, tag v1.5.0, Copyright (c) 2019 Bram Kragten) and swipe-card
 * (bramkragten/swipe-card, tag v5.0.0, MIT) with Swiper 6's pagination (MIT, Copyright (c) Vladimir Kharlampidi).
 * The licence text is in THIRD-PARTY-NOTICES.md and LICENSES/MIT.txt.
 * Changes: rewritten in Kotlin for Vitro's native views; HA's error card added. */

package dev.glassworks.engine.vitro.cards

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.model.CardConfig
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.CardSurface
import dev.glassworks.engine.vitro.ui.ErrorCardView
import dev.glassworks.engine.vitro.ui.MdiIconView
import dev.glassworks.engine.vitro.ui.VitroCardContext
import dev.glassworks.engine.vitro.ui.VitroFonts
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Native fast paths for three more HACS cards the island census found often: swipe-card (bramkragten, v5.0.0, MIT:
// its Swiper 6 pager), weather-card (bramkragten, v1.5.0, MIT) and HA's own error card for a card config HA rejects.
// mushroom-empty-card draws nothing and is a plain view in VitroView.

/**
 * HA's `hui-error-card` for a card config HA's own card rejects in `setConfig`: [ErrorCardView], keeping the message
 * HA logs (it shows it only in the editor's preview). An administrator sees "Configuration error", anyone else the icon.
 */
class HaErrorCardView(ctx: VitroCardContext, message: String) : ErrorCardView(ctx, null, message)

/**
 * `custom:swipe-card`: its cards side by side in a Swiper pager, one page (or `slidesPerView`) at a time. The pager is as
 * tall as its tallest card, each card keeps its own height at the top, and the pagination (bullets or `1 / 3`) floats
 * 10 px over the bottom, as Swiper 6 draws them. Swipe and `autoplay` change the page; `reset_after` goes back to the
 * first one. Only the parameters [dev.glassworks.engine.vitro.compat.VitroSupport.SWIPE_CARD_PARAMETERS] lists reach
 * here natively.
 */
class SwipeCardView(private val ctx: VitroCardContext, private val config: CardConfig, build: CardBuilder) : ViewGroup(ctx.context) {
    private val params: JSONObject = config.raw.optJSONObject("parameters") ?: JSONObject()
    private val slides: List<View> = config.childCards.map(build)
    private val perView: Float = (params.opt("slidesPerView") as? Number)?.toFloat()?.takeIf { it > 0f } ?: 1f
    private val space: Float = (params.opt("spaceBetween") as? Number)?.toFloat() ?: 0f
    private val centered = params.optBoolean("centeredSlides", false)
    private val loop = params.optBoolean("loop", false)
    private val speedMs = (params.opt("speed") as? Number)?.toLong() ?: 300L
    private val pagination: String? = if (params.has("pagination")) {
        (params.opt("pagination") as? JSONObject)?.optString("type", "bullets")?.ifEmpty { "bullets" } ?: "bullets"
    } else null
    private val clickable = (params.opt("pagination") as? JSONObject)?.optBoolean("clickable", false) == true
    private val initial: Int = (config.raw.opt("start_card") as? Number)?.toInt()?.minus(1)
        ?: (params.opt("initialSlide") as? Number)?.toInt() ?: 0
    private val autoplayMs: Long? = when (val a = params.opt("autoplay")) {
        is JSONObject -> (a.opt("delay") as? Number)?.toLong() ?: 3000L
        true -> 3000L
        else -> null
    }
    private val resetAfterMs: Long? = (config.raw.opt("reset_after") as? Number)?.toDouble()?.let { (it * 1000).toLong() }

    private var index = 0
    /** Drag offset in px while a finger moves the pager. */
    private var drag = 0f
    private var animated = 0f
    private var anim: ValueAnimator? = null
    private var slideW = 0
    private val touchSlop = ViewConfiguration.get(ctx.context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ctx.theme.primaryTextColor
        textSize = ctx.scaledSp(14f) * ctx.dp
        typeface = VitroFonts.typeface(ctx.context, 400, ctx.scaledSp(14f))
        textAlign = Paint.Align.CENTER
    }
    private val autoplay = Runnable { goTo(if (loop || index < pages() - 1) index + 1 else 0, true); scheduleAutoplay() }
    private val reset = Runnable { goTo(clampIndex(initial), true) }

    init {
        slides.forEach { addView(it) }
        index = clampIndex(initial)
        setWillNotDraw(false)
    }

    /** Swiper's snap points: one per slide with `loop`, else up to the last full page. */
    private fun pages(): Int = when {
        slides.isEmpty() -> 0
        loop || centered -> slides.size
        else -> (slides.size - ceil(perView).toInt() + 1).coerceAtLeast(1)
    }

    private fun clampIndex(i: Int): Int = if (pages() == 0) 0 else if (loop) i.mod(slides.size) else i.coerceIn(0, pages() - 1)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val gap = space * ctx.dp
        slideW = ((w - gap * (ceil(perView) - 1).coerceAtLeast(0f)) / perView).toInt()
        var h = 0
        for (s in slides) {
            if (s.visibility == GONE) continue
            s.measure(MeasureSpec.makeMeasureSpec(slideW, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            h = max(h, s.measuredHeight)
        }
        setMeasuredDimension(w, if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(heightMeasureSpec) else h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) = place()

    private fun step(): Float = slideW + space * ctx.dp

    private fun place() {
        val n = slides.size
        if (n == 0) return
        val origin = (if (centered) (width - slideW) / 2f else 0f) + drag + animated
        for ((i, s) in slides.withIndex()) {
            var rel = i - index
            // With `loop`, each slide sits on whichever side of the current one is nearer, as Swiper's duplicates do.
            if (loop) {
                rel = rel.mod(n)
                if (rel > n / 2) rel -= n
            }
            val x = (origin + rel * step()).roundToInt()
            s.layout(x, 0, x + s.measuredWidth, s.measuredHeight)
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y; dragging = false; interacted() }
            MotionEvent.ACTION_MOVE -> if (!dragging && abs(ev.x - downX) > touchSlop && abs(ev.x - downX) > abs(ev.y - downY)) {
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
            }
        }
        return dragging
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y; return true }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(ev.x - downX) > touchSlop) dragging = true
                if (dragging) {
                    var d = ev.x - downX
                    // Swiper resists past the first and last page (no loop).
                    if (!loop && ((index == 0 && d > 0) || (index == pages() - 1 && d < 0))) d /= 3f
                    drag = d
                    place()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    val moved = drag
                    drag = 0f
                    val target = when {
                        moved < -step() / 2f || moved < -ctx.px(40f) -> index + 1
                        moved > step() / 2f || moved > ctx.px(40f) -> index - 1
                        else -> index
                    }
                    animated = moved
                    goTo(target, true)
                } else if (ev.actionMasked == MotionEvent.ACTION_UP && clickable && pagination == "bullets") {
                    bulletAt(ev.x, ev.y)?.let { goTo(it, true) }
                }
                dragging = false
            }
        }
        return true
    }

    private fun goTo(target: Int, animate: Boolean) {
        val next = clampIndex(target)
        anim?.cancel()
        val from = animated + (index - next) * step().let { if (loop && abs(index - next) > slides.size / 2) 0f else it }
        index = next
        if (!animate || !isAttachedToWindow) {
            animated = 0f
            place()
            invalidate()
            return
        }
        anim = ValueAnimator.ofFloat(from, 0f).apply {
            duration = speedMs
            interpolator = DecelerateInterpolator()
            addUpdateListener { animated = it.animatedValue as Float; place(); invalidate() }
            start()
        }
    }

    private fun interacted() {
        removeCallbacks(reset)
        resetAfterMs?.let { postDelayed(reset, it) }
        // Swiper's autoplay stops on interaction by default (`disableOnInteraction: true`).
        removeCallbacks(autoplay)
    }

    private fun scheduleAutoplay() {
        removeCallbacks(autoplay)
        autoplayMs?.let { postDelayed(autoplay, it) }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scheduleAutoplay()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(autoplay)
        removeCallbacks(reset)
        anim?.cancel()
    }

    /** Swiper's bullets: 8 px dots 8 px apart, centered, 10 px from the bottom; the current one in the theme color. */
    private fun bulletX(i: Int, count: Int): Float {
        val dot = 8f * ctx.dp
        val pitch = 16f * ctx.dp
        val total = count * pitch
        return (width - total) / 2f + i * pitch + (pitch - dot) / 2f + dot / 2f
    }

    private fun bulletAt(x: Float, y: Float): Int? {
        val count = pages()
        val cy = height - (10f + 8f) * ctx.dp
        if (abs(y - (cy + 4f * ctx.dp)) > 12f * ctx.dp) return null
        return (0 until count).minByOrNull { abs(bulletX(it, count) - x) }?.takeIf { abs(bulletX(it, count) - x) <= 8f * ctx.dp }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val count = pages()
        if (count == 0) return
        when (pagination) {
            "bullets" -> {
                val theme = ThemeResolver.parseColor(ctx.theme.vars["swiper-pagination-color"] ?: ctx.theme.vars["swiper-theme-color"]) ?: ctx.theme.primaryColor
                val cy = height - (10f + 4f) * ctx.dp
                for (i in 0 until count) {
                    // `.swiper-pagination-bullet { background: #000; opacity: .2 }`, the active one opaque in the theme color.
                    dotPaint.color = if (i == index) theme else Color.argb(51, 0, 0, 0)
                    canvas.drawCircle(bulletX(i, count), cy, 4f * ctx.dp, dotPaint)
                }
            }
            "fraction" -> {
                // `1 / 3`, 14 px, on a 16 px line 10 px from the bottom.
                val baseline = height - 10f * ctx.dp - 16f * ctx.dp / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
                canvas.drawText("${index + 1} / $count", width / 2f, baseline, textPaint)
            }
        }
    }
}

/**
 * bramkragten's `custom:weather-card` (v1.5.0): the current conditions (its animated icon, `name`, the temperature) and
 * the details (humidity, wind, pressure, visibility, sunrise and sunset from `sun.sun`), laid out as its stylesheet
 * does. Its forecast reads the entity's `forecast` attribute, which Home Assistant no longer sets, so it shows none
 * there and none here. The icons are the card's own SVGs, from its `icons:` folder or its CDN, as the browser loads
 * them; until one arrives (or if it can't be read) the spot stays empty, as a missing background image does.
 */
class LegacyWeatherCardView(ctx: VitroCardContext, private val config: CardConfig) : CardSurface(ctx) {
    private val raw = config.raw
    private val entityId = raw.optString("entity")
    private val body = Body()
    private var current: EntityState? = null
    private var sun: EntityState? = null

    init {
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        isClickable = true
        setOnClickListener { ctx.openMoreInfo(entityId) }
        applyHaRipple(this, ctx.theme.cardBorderRadiusDp * ctx.dp, ctx.theme.dark)
        observe("sun.sun") { s -> sun = s; body.update() }
        observe(entityId) { s -> current = s; body.update() }
    }

    override fun clearDefaultInnerPadding() = Unit

    private fun unit(measure: String): String {
        val units = ctx.haConfig().optJSONObject("unit_system") ?: JSONObject()
        val length = units.optString("length", "km")
        return when (measure) {
            "air_pressure" -> if (length == "km") "hPa" else "inHg"
            "length" -> length
            else -> units.optString(measure, "")
        }
    }

    private fun iconUrl(condition: String, night: Boolean): String? {
        val name = (if (night) NIGHT[condition] ?: DAY[condition] else DAY[condition]) ?: return null
        val base = raw.optString("icons", "").ifEmpty { "https://cdn.jsdelivr.net/gh/bramkragten/weather-card/dist/icons/" }
        return "$base$name.svg"
    }

    /** The card's content, laid out in CSS px from its stylesheet (`em` is 14 px). */
    private inner class Body : ViewGroup(ctx.context) {
        private val icon = SvgView()
        private val title = text(42f, 300)
        private val temp = text(56f, 300)
        private val tempc = text(21f, 300)
        private val rows = ArrayList<Pair<MdiIconView, TextView>>()
        private var iconUrl: String? = null

        init {
            clipChildren = true
            addView(icon); addView(title); addView(temp); addView(tempc)
            repeat(6) {
                val i = MdiIconView(ctx.context)
                val t = text(14f, 300).apply { maxLines = 1 }
                addView(i); addView(t)
                rows += i to t
            }
        }

        private fun text(px: Float, weight: Int) = TextView(ctx.context).apply {
            includeFontPadding = false
            setTextColor(ctx.theme.primaryTextColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ctx.scaledSp(px))
            typeface = VitroFonts.typeface(ctx.context, weight, ctx.scaledSp(px))
            maxLines = 1
        }

        private val showCurrent get() = raw.opt("current") != false
        private val showDetails get() = raw.opt("details") != false

        fun update() {
            val s = current
            val show = s != null
            icon.visibility = if (show && showCurrent) VISIBLE else GONE
            title.visibility = if (show && showCurrent && raw.optString("name").isNotEmpty()) VISIBLE else GONE
            temp.visibility = if (show && showCurrent) VISIBLE else GONE
            tempc.visibility = temp.visibility
            rows.forEach { (i, t) -> i.visibility = GONE; t.visibility = GONE }
            if (s == null) return
            title.text = raw.optString("name")
            val t = s.attributes["temperature"]
            temp.text = if (unit("temperature") == "°F" && t is Number) t.toDouble().roundToInt().toString() else jsString(t ?: JSONObject.NULL).takeIf { t != null } ?: "undefined"
            tempc.text = unit("temperature")
            val night = sun?.state == "below_horizon"
            iconUrl(s.state.lowercase(Locale.ROOT), night).let { url ->
                if (url != iconUrl) {
                    iconUrl = url
                    icon.svg = null
                    url?.let { u -> ctx.loadSvg(u) { svg -> if (iconUrl == u) icon.svg = svg } }
                }
            }
            if (showDetails) {
                val a = s.attributes
                val items = ArrayList<Pair<String, CharSequence>>()
                items += "mdi:water-percent" to withUnit(jsAttr(a["humidity"]), " % ")
                val bearing = (a["wind_bearing"] as? Number)?.toDouble()
                val dir = bearing?.let { DIRECTIONS.getOrNull(((it + 11.25) / 22.5).toInt()) } ?: "undefined"
                items += "mdi:weather-windy" to withUnit(" $dir ${jsAttr(a["wind_speed"])}", " ${unit("length")}/h ")
                items += "mdi:gauge" to withUnit("${jsAttr(a["pressure"])} ", " ${unit("air_pressure")} ")
                items += "mdi:weather-fog" to withUnit(" ${jsAttr(a["visibility"])}", " ${unit("length")} ")
                sun?.let { sn ->
                    parseTime(sn.attributes["next_rising"])?.let { items += "mdi:weather-sunset-up" to " ${timeText(it)}" }
                    parseTime(sn.attributes["next_setting"])?.let { items += "mdi:weather-sunset-down" to " ${timeText(it)}" }
                }
                items.forEachIndexed { k, (iconName, txt) ->
                    val (iv, tv) = rows[k]
                    iv.visibility = VISIBLE
                    tv.visibility = VISIBLE
                    iv.setIcon(iconName, ctx.theme.primaryTextColor)
                    tv.text = styled(txt)
                }
            }
            requestLayout()
        }

        private fun jsAttr(v: Any?): String = if (v == null) "undefined" else jsString(v)

        /** The value, then its unit at 0.8em (`.unit`). */
        private fun withUnit(value: String, unit: String): CharSequence = "$value\u0000$unit"

        /** The text as the browser collapses its white space, its `.unit` part at 0.8em. */
        private fun styled(marked: CharSequence): CharSequence {
            val s = marked.toString()
            val cut = s.indexOf('\u0000')
            if (cut < 0) return s.replace(Regex("""\s+"""), " ").trim()
            val value = s.substring(0, cut).replace(Regex("""\s+"""), " ").trim()
            val unit = s.substring(cut + 1).replace(Regex("""\s+"""), " ").trim()
            val text = if (unit.isEmpty()) value else "$value $unit"
            return android.text.SpannableString(text).apply {
                if (unit.isNotEmpty()) setSpan(android.text.style.RelativeSizeSpan(0.8f), text.length - unit.length, text.length, 0)
            }
        }

        private fun parseTime(v: Any?): Date? = (v as? String)?.let { s ->
            dev.glassworks.engine.vitro.data.EntityStore.parseIsoEpochSeconds(s)?.let { Date((it * 1000).toLong()) }
        }

        /** `toLocaleTimeString()` in HA's language (en-US: `6:42:00 AM`). */
        private fun timeText(d: Date): String = SimpleDateFormat("h:mm:ss a", Locale.US).format(d)

        private fun px(cssPx: Float) = (cssPx * ctx.dp).roundToInt()

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            for (i in 0 until childCount) getChildAt(i).let { c -> if (c.visibility != GONE) c.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)) }
            icon.measure(MeasureSpec.makeMeasureSpec(px(140f), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(px(140f), MeasureSpec.EXACTLY))
            val liW = ((w - px(56f)) / 2).coerceAtLeast(0)
            for ((iv, tv) in rows) {
                iv.measure(MeasureSpec.makeMeasureSpec(px(24f), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(px(24f), MeasureSpec.EXACTLY))
                if (tv.visibility != GONE) tv.measure(MeasureSpec.makeMeasureSpec((liW - px(29f)).coerceAtLeast(0), MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            }
            var h = px(PAD_V * 2)
            if (current != null) {
                if (showCurrent) h += px(CURRENT_H + CURRENT_MARGIN)
                if (showDetails) h += px(24f) * ((rows.count { it.second.visibility != GONE } + 1) / 2)
            } else {
                h = 0
            }
            setMeasuredDimension(w, h)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val w = r - l
            // The padding box (left 0) and content box (1em in, 1.3em down) of ha-card, in this view's coordinates.
            val contentTop = px(PAD_V)
            val currentTop = contentTop + px(16.8f)
            if (showCurrent) {
                // `.icon.bigger`: 10em square at left 0, `margin-top: -4em` (out of the top; the card clips it).
                icon.layout(0, currentTop - px(56f), px(140f), currentTop - px(56f) + px(140f))
                // The texts take HA's page line-height (1.6), so half its leading sits above each glyph line.
                fun lead(t: TextView) = ((1.6f * t.textSize - (t.paint.fontMetrics.descent - t.paint.fontMetrics.ascent)) / 2f).roundToInt()
                val ty = currentTop + lead(title)
                title.layout(px(126f), ty, px(126f) + title.measuredWidth, ty + title.measuredHeight)
                // `right: 1em` in each text's own em: 56 px and 21 px (+ `margin-right: 7px`); `°C` 14 px higher.
                val tr = w - px(56f)
                val y8 = currentTop + lead(temp)
                temp.layout(tr - temp.measuredWidth, y8, tr, y8 + temp.measuredHeight)
                val cr = w - px(21f) - px(7f)
                val yc = currentTop - px(14f) + lead(tempc)
                tempc.layout(cr - tempc.measuredWidth, yc, cr, yc + tempc.measuredHeight)
            }
            var y = contentTop + if (showCurrent) px(CURRENT_H + CURRENT_MARGIN) else 0
            // ul.variations sits in the content box (1em in) with its own 1em padding; two 50% items a row.
            val liW = (w - px(56f)) / 2
            rows.forEachIndexed { k, (iv, tv) ->
                if (tv.visibility == GONE) return@forEachIndexed
                val left = k % 2 == 0
                val x0 = px(28f) + if (left) 0 else liW
                val rowTop = y + (k / 2) * px(24f)
                val ty = rowTop + (px(24f) - tv.measuredHeight) / 2
                if (left) {
                    iv.layout(x0, rowTop, x0 + px(24f), rowTop + px(24f))
                    // The icon's 5 px margin, then the space the template leaves before the value.
                    val tx = x0 + px(29f) + tv.paint.measureText(" ").roundToInt()
                    tv.layout(tx, ty, tx + tv.measuredWidth, ty + tv.measuredHeight)
                } else {
                    val right = x0 + liW
                    iv.layout(right - px(24f), rowTop, right, rowTop + px(24f))
                    val te = right - px(32f)
                    tv.layout(te - tv.measuredWidth, ty, te, ty + tv.measuredHeight)
                }
            }
        }
    }

    /** One SVG drawn to fit its box (`background-size: contain`, centered). */
    private inner class SvgView : View(ctx.context) {
        var svg: MdSvg? = null
            set(v) { field = v; invalidate() }

        override fun onDraw(canvas: Canvas) {
            val s = svg ?: return
            s.draw(canvas, width.toFloat(), height.toFloat(), context)
        }
    }

    // ha-card { padding: 1.3em 1em } and the current block's padding (1.2em top and bottom) and 3.5em margin.
    private companion object {
        const val PAD_V = 18.2f
        const val CURRENT_H = 33.6f
        const val CURRENT_MARGIN = 49f
        val DAY = mapOf(
            "clear" to "day", "clear-night" to "night", "cloudy" to "cloudy", "fog" to "cloudy", "hail" to "rainy-7",
            "lightning" to "thunder", "lightning-rainy" to "thunder", "partlycloudy" to "cloudy-day-3", "pouring" to "rainy-6",
            "rainy" to "rainy-5", "snowy" to "snowy-6", "snowy-rainy" to "rainy-7", "sunny" to "day", "windy" to "cloudy",
            "windy-variant" to "cloudy-day-3", "exceptional" to "!!",
        )
        val NIGHT = DAY + mapOf("clear" to "night", "sunny" to "night", "partlycloudy" to "cloudy-night-3", "windy-variant" to "cloudy-night-3")
        val DIRECTIONS = listOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW", "N")
    }
}
