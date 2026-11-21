package dev.glassworks.timers

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import dev.glassworks.TimerBoard
import dev.glassworks.ui.Inter
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * The timer board (#691's approved mockup): with [MIN_TIMERS] or more timers, the group is one glass card that lists
 * them, soonest first. Each row has a small ring (the theme's lights, amber in its last 10 seconds, dimmed when
 * paused), the name with its length under it, and the time left. Up to [MAX_ROWS] rows, then "+N more". "Hide" in
 * the heading on a touch screen only ([touch]). Drawn in one pass; the overlay redraws it once a second. Main thread.
 */
class BoardView(context: Context) : View(context) {

    var timers: List<TimerBoard.Shown> = emptyList()
        set(v) { val resize = heightDp(v.size) != heightDp(field.size); field = v; if (resize) requestLayout(); invalidate() }

    var look: TimerLook = TimerLook.DEFAULT
        set(v) { if (v !== field) { field = v; invalidate() } }

    /** A touch screen: the heading offers "Hide". */
    var touch = true
        set(v) { if (v != field) { field = v; invalidate() } }

    /** Where "Hide" is, in this view's coordinates (empty without [touch]). */
    val hideBox = RectF()

    private val dp = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val head = text(11.5f, Inter.MEDIUM).apply { letterSpacing = 0.14f }
    private val hide = text(13f, Inter.REGULAR).apply { textAlign = Paint.Align.RIGHT }
    private val name = text(15f, Inter.REGULAR)
    private val line = text(12f, Inter.REGULAR)
    private val time = text(17f, Inter.LIGHT).apply { textAlign = Paint.Align.RIGHT; fontFeatureSettings = "tnum" }
    private val card = RectF()
    private val arc = RectF()

    init {
        // A soft shadow from the card's round outline (the RenderThread draws it), so it stands off a light page too.
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) =
                outline.setRoundRect(0, 0, view.width, view.height, 24 * dp)
        }
        elevation = 6 * dp
    }

    override fun hasOverlappingRendering() = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension((WIDTH_DP * dp).toInt(), (heightDp(timers.size) * dp).toInt())

    override fun onDraw(c: Canvas) {
        val l = look
        val now = android.os.SystemClock.elapsedRealtime()
        val w = width.toFloat(); val h = height.toFloat()
        val r = 24 * dp
        card.set(0.5f * dp, 0.5f * dp, w - 0.5f * dp, h - 0.5f * dp)
        fill.shader = null; fill.color = l.glass; fill.alpha = 245
        c.drawRoundRect(card, r, r, fill)
        fill.alpha = 255
        stroke.shader = null; stroke.strokeWidth = 1 * dp; stroke.color = l.edge
        c.drawRoundRect(card, r, r, stroke)

        val pad = PAD_DP * dp
        val left = pad + 10 * dp; val right = w - pad - 10 * dp
        // Heading: "TIMERS · 4", and "Hide" on a touch screen.
        val hb = pad + 6 * dp + 14 * dp
        head.color = l.glassMu
        c.drawText(TimerTexts.boardTitle(timers.size).uppercase(Locale.getDefault()), left, hb, head)
        hideBox.setEmpty()
        if (touch) {
            hide.color = l.glassMu
            c.drawText(HIDE, right, hb, hide)
            val hw = hide.measureText(HIDE)
            // A finger-sized target around the word.
            hideBox.set(right - hw - 14 * dp, 0f, w, pad + HEAD_DP * dp + 4 * dp)
        }

        val rows = rowsFor(timers.size)
        var top = pad + HEAD_DP * dp
        for ((i, t) in timers.take(rows).withIndex()) {
            if (i > 0) { stroke.strokeWidth = 1 * dp; stroke.color = l.edge; c.drawLine(left, top, right, top, stroke) }
            row(c, t, now, left, right, top)
            top += ROW_DP * dp
        }
        val more = TimerTexts.boardMore(timers.size, rows)
        if (more.isNotEmpty()) {
            line.color = l.glassMu
            c.drawText(more, left, top + MORE_DP * dp * 0.62f, line)
        }
    }

    private fun row(c: Canvas, t: TimerBoard.Shown, now: Long, left: Float, right: Float, top: Float) {
        val l = look
        val hot = t.hot(now)
        val paused = !t.active
        val cy = top + ROW_DP * dp / 2
        // The small ring: 36 dp, its track, then what's left from 12 o'clock.
        val rr = 15 * dp
        val cx = left + 18 * dp
        arc.set(cx - rr, cy - rr, cx + rr, cy + rr)
        stroke.strokeWidth = 3 * dp; stroke.color = l.track
        c.drawCircle(cx, cy, rr, stroke)
        val frac = t.remaining(now)
        if (frac > 0f) {
            if (hot) stroke.color = l.hotRing
            else {
                stroke.color = 0xff000000.toInt()
                stroke.shader = LinearGradient(cx - rr, cy - rr, cx + rr, cy + rr, l.a or BLACK, l.b or BLACK, Shader.TileMode.CLAMP)
                stroke.alpha = if (paused) 102 else 255
            }
            c.drawArc(arc, -90f, max(360f * frac, 0.5f), false, stroke)
            stroke.shader = null; stroke.alpha = 255
        }
        // The time on the right, then the name and its line in what's left between.
        val shown = TimerTexts.boardTime(t, now)
        time.textSize = (if (paused) 15f else 17f) * dp
        time.typeface = Inter.typeface(context, if (paused) Inter.REGULAR else Inter.LIGHT, if (paused) 15f else 17f)
        time.color = when { hot -> l.hotDigits; paused -> l.glassMu; else -> l.glassInk }
        c.drawText(shown, right, baseline(time, cy), time)
        val textLeft = cx + rr + 15 * dp
        val room = max(0f, right - time.measureText(shown) - 12 * dp - textLeft)
        name.color = l.glassInk
        line.color = l.glassMu
        val sub = TimerTexts.boardLine(t, now)
        val nameY = if (sub.isEmpty()) baseline(name, cy) else cy - 2 * dp
        c.drawText(fit(t.label, name, room), textLeft, nameY, name)
        if (sub.isNotEmpty()) c.drawText(fit(sub, line, room), textLeft, cy + 15 * dp, line)
    }

    private fun fit(s: String, p: TextPaint, room: Float) = TextUtils.ellipsize(s, p, room, TextUtils.TruncateAt.END).toString()

    private fun baseline(p: Paint, cy: Float) = p.fontMetrics.let { cy - (it.ascent + it.descent) / 2 }

    private fun text(sp: Float, weight: Int) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp * dp
        typeface = Inter.typeface(context, weight, sp)
    }

    companion object {
        /** The group becomes a board at this many timers; fewer stay rings. */
        const val MIN_TIMERS = 3
        /** At most this many rows; the rest are "+N more". */
        const val MAX_ROWS = 5
        const val WIDTH_DP = 300f
        const val PAD_DP = 10f
        const val HEAD_DP = 34f
        const val ROW_DP = 54f
        const val MORE_DP = 30f
        const val HIDE = "Hide"
        private const val BLACK = 0xff000000.toInt()

        fun rowsFor(n: Int) = min(n, MAX_ROWS)

        /** The board's height for [n] timers. */
        fun heightDp(n: Int): Float = 2 * PAD_DP + HEAD_DP + rowsFor(n) * ROW_DP + (if (n > MAX_ROWS) MORE_DP else 0f)
    }
}
