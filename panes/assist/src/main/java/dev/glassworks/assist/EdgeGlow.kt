package dev.glassworks.assist

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import dev.glassworks.theme.Themes
import kotlin.math.ceil
import kotlin.math.exp

/**
 * Spark, the edge glow (#691's "edge-lit glass"): the screen is a pane of glass whose edges catch the theme's two
 * lights. They ignite at the mic (the middle of the top edge), run both ways round the edge and flare where they meet;
 * on the way out they run back into the mic. Listening, the mic glows with the voice and loud words send ripples along
 * the edge; thinking, the lights shuttle along the top and bottom edges and flare where they cross; speaking is calm, a
 * soft light that breathes with the reply; an error is rose (a status colour).
 *
 * Built so RenderThread does the work (measured on the S1, the tablet and the Mirror, #694): about 30 child views, each
 * drawing one bitmap baked once, so a frame only changes alpha, scale and translation. The entrance, the exit, the
 * ripples and the thinking laps are ViewPropertyAnimator animations with no listeners or end actions, which Android runs
 * on RenderThread; only the voice level comes from the UI thread ([level], [speaking]). The border is two half rings that
 * split at the mic, each three strips (top, side, bottom) of a 1 px colour profile stretched along its edge; strips grow
 * and shrink by scale from a pivot. On a light ground ([Themes.Palette.darkGround] false) the white-hot accents become the
 * theme's own colours, which show there.
 */
class EdgeGlow(context: Context, palette: Themes.Palette) : FrameLayout(context) {
    private val d = resources.displayMetrics.density
    private val a = palette.a; private val b = palette.b
    private val light = !palette.darkGround
    private val rose = palette.readable(0xfffb7185.toInt())
    /** What glows white-hot on a dark ground; on a light one, the lights' middle, which shows there. */
    private val hotColour = if (light) palette.readable(Themes.mix(a, b, .5f)) else Color.WHITE
    private val hotK = if (light) 0f else 1f
    private val depth = (72 * d).toInt()
    private val lin = LinearInterpolator()
    private val dec = DecelerateInterpolator(1.6f)

    private val dim = plain().apply {
        val g = palette.ground
        // A light dim that deepens to the bottom, where the caption sits: the dashboard stays in view.
        val stops = if (light) intArrayOf(26, 56, 140) else intArrayOf(41, 72, 153)
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, IntArray(3) { Color.argb(stops[it], Color.red(g), Color.green(g), Color.blue(g)) })
    }
    private val bevel = object : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = d }
        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { p.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), a, b, Shader.TileMode.CLAMP) }
        override fun onDraw(c: Canvas) { val i = 7 * d; c.drawRoundRect(i, i, width - i, height - i, 10 * d, 10 * d, p) }
        override fun hasOverlappingRendering() = false
    }

    // half rings: [top, side, bottom] for a (left) and b (right); and the rose ring for errors
    private val ringA = Array(3) { strip(a, it, right = false, toMid = true) }
    private val ringB = Array(3) { strip(b, it, right = true, toMid = true) }
    private val roseRing = Array(4) { strip(rose, if (it < 2) it * 2 else 1, right = it == 3) } // top, bottom, left, right
    private val halfA = layer(); private val halfB = layer()

    private val comets = Array(2) { i -> sprite(dot(if (i == 0) a else b, 26f, hot = .55f * hotK)) } // entrance heads
    private val cometBoxes = Array(2) { FrameLayout(context).apply { clipChildren = false } }
    private val shuttles = Array(4) { i -> sprite(dot(if (i % 2 == 0) a else b, 22f, hot = .45f * hotK)) } // thinking: top a/b, bottom a/b
    private val micGlow = sprite(ellipse(hotColour, 130f, 30f))
    private val ripples = Array(6) { i -> sprite(ellipse(if (i < 3) a else b, 70f, 14f)) } // 3 going left (a), 3 right (b)
    private var nextRipple = intArrayOf(0, 0)
    private val flareBox = Array(3) { FrameLayout(context).apply { clipChildren = false } } // ignite, meet, cross
    private val flares = Array(3) { sprite(flare(hotColour)) }
    private val roseFlare = sprite(flare(rose))

    init {
        clipChildren = false
        isClickable = false; isFocusable = false
        addView(dim, LayoutParams(-1, -1))
        addView(halfA, LayoutParams(-1, -1)); addView(halfB, LayoutParams(-1, -1))
        for ((i, s) in ringA.withIndex()) halfA.addView(s, stripParams(i, left = true))
        for ((i, s) in ringB.withIndex()) halfB.addView(s, stripParams(i, left = false))
        for ((i, s) in roseRing.withIndex()) addView(s, roseParams(i))
        addView(bevel, LayoutParams(-1, -1))
        addView(micGlow, LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        for (r in ripples) addView(r, LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        for (s in shuttles) addView(s, LayoutParams(-2, -2))
        for ((i, box) in cometBoxes.withIndex()) { box.addView(comets[i], LayoutParams(-2, -2)); addView(box, LayoutParams(-2, -2)) }
        for ((i, box) in flareBox.withIndex()) { box.addView(flares[i], LayoutParams(-2, -2, Gravity.CENTER)); addView(box, LayoutParams(-2, -2)) }
        addView(roseFlare, LayoutParams(-2, -2))
        reset()
    }

    /** Everything hidden, strips at full length; the layer is ready for [enter]. */
    fun reset() {
        for (v in all()) { v.animate().cancel(); v.alpha = 0f; v.translationX = 0f; v.translationY = 0f; v.scaleX = 1f; v.scaleY = 1f }
        halfA.alpha = 1f; halfB.alpha = 1f
        for (s in ringA + ringB) s.alpha = 1f
        visibility = INVISIBLE
    }

    private fun all(): List<View> = listOf(dim, bevel, micGlow, roseFlare, halfA, halfB) + ringA + ringB + roseRing + comets + cometBoxes + shuttles + ripples + flareBox + flares

    // ------------------------------------------------------------------ the moments (all RenderThread animations)
    /** Spark coming on: the light ignites at the mic, splits both ways round the edge, flares where the halves meet. */
    fun enter() {
        reset(); visibility = VISIBLE
        val w = width.toFloat(); val h = height.toFloat()
        val half = w / 2 + h + w / 2
        val t1 = (430 * (w / 2) / half).toLong(); val t2 = (430 * h / half).toLong(); val t3 = t1
        dim.animate().alpha(1f).setDuration(400).setInterpolator(lin).start()
        bevel.animate().alpha(if (light) .3f else .2f).setDuration(650).setInterpolator(lin).start()
        // ignite at the mic
        flash(0, w / 2, 0f, from = .3f, to = .9f, delay = 0, dur = 260)
        // the strips run out from the mic, one after the other
        for (left in listOf(true, false)) {
            val ring = if (left) ringA else ringB
            val (top, side, bottom) = ring
            top.pivotX = if (left) top.width.toFloat() else 0f; top.scaleX = 0f
            side.pivotY = 0f; side.scaleY = 0f
            bottom.pivotX = if (left) 0f else bottom.width.toFloat(); bottom.scaleX = 0f
            top.animate().scaleX(1f).setStartDelay(90).setDuration(t1).setInterpolator(lin).start()
            side.animate().scaleY(1f).setStartDelay(90 + t1).setDuration(t2).setInterpolator(lin).start()
            bottom.animate().scaleX(1f).setStartDelay(90 + t1 + t2).setDuration(t3).setInterpolator(lin).start()
            // the head: the box runs along the top and down the side, the comet in it along the bottom
            val i = if (left) 0 else 1
            val box = cometBoxes[i]; val c = comets[i]
            box.alpha = 1f; c.alpha = 1f
            box.translationX = w / 2 - c.width / 2f; box.translationY = -c.height / 2f
            box.animate().translationX(if (left) -c.width / 2f else w - c.width / 2f).setStartDelay(90).setDuration(t1).setInterpolator(lin).start()
            box.animate().translationY(h - c.height / 2f).setStartDelay(90 + t1).setDuration(t2).setInterpolator(lin).start()
            c.animate().translationX(if (left) w / 2 else -w / 2).setStartDelay(90 + t1 + t2).setDuration(t3).setInterpolator(lin).start()
            box.animate().alpha(0f).setStartDelay(90 + t1 + t2 + t3).setDuration(120).start()
        }
        // the caustic where the halves meet, at the bottom middle
        flash(1, w / 2, h, from = .7f, to = 1.5f, delay = 520, dur = 650)
    }

    /** Listening: the mic glows with the voice, [l] 0 to 1. From the UI thread, each frame. */
    fun level(l: Float) {
        micGlow.alpha = .35f + .65f * l
        micGlow.scaleX = 1 + 1.4f * l; micGlow.scaleY = 1 + .6f * l
        halfA.alpha = .75f + .25f * l; halfB.alpha = halfA.alpha
    }

    /** A loud word: one ripple each way along the top edge from the mic (reused sprites). */
    fun ripple(l: Float) {
        val w = width.toFloat()
        for (side in 0..1) {
            val r = ripples[side * 3 + nextRipple[side]]; nextRipple[side] = (nextRipple[side] + 1) % 3
            r.animate().cancel()
            r.translationX = 0f; r.translationY = -r.height / 2f; r.alpha = (.9f * l).coerceAtMost(1f)
            r.animate().translationX(if (side == 0) -w / 2 else w / 2).alpha(0f).setStartDelay(0).setDuration(1500).setInterpolator(dec).start()
        }
    }

    fun listening() {
        micGlow.translationY = -micGlow.height / 2f
        for (s in shuttles) { s.animate().cancel(); s.alpha = 0f }
        roseFlare.alpha = 0f
    }

    /** Thinking: light trapped in the glass, shuttling along the top and bottom edges, flaring where it crosses. */
    fun thinking(lap: Int) {
        val w = width.toFloat(); val h = height.toFloat()
        if (lap == 0) {
            micGlow.animate().alpha(0f).setStartDelay(0).setDuration(300).start()
            halfA.animate().alpha(.35f).setStartDelay(0).setDuration(300).start(); halfB.animate().alpha(.35f).setStartDelay(0).setDuration(300).start()
        }
        for ((i, s) in shuttles.withIndex()) {
            val bottom = i >= 2; val fromLeft = i % 2 == 0
            val delay = if (bottom) 650L else 0L
            s.animate().cancel()
            s.alpha = 1f
            s.translationY = (if (bottom) h else 0f) - s.height / 2f
            s.translationX = (if (fromLeft) 0f else w) - s.width / 2f
            s.animate().translationX((if (fromLeft) w else 0f) - s.width / 2f).setStartDelay(delay).setDuration(1300).setInterpolator(lin).start()
        }
        flash(2, w / 2, 0f, from = .5f, to = 1.2f, delay = 650, dur = 450) // they cross at the mic
        flash(1, w / 2, h, from = .5f, to = 1.2f, delay = 1300, dur = 450) // and at the bottom
    }

    /** Speaking: calm. A soft light that breathes slowly with the reply, [l] 0 to 1 smoothed. From the UI thread, each frame. */
    fun speaking(l: Float) {
        halfA.alpha = .6f + .25f * l; halfB.alpha = halfA.alpha
    }

    fun speakingStart() {
        for (s in shuttles) { s.animate().cancel(); s.alpha = 0f }
        micGlow.animate().alpha(0f).setDuration(300).start()
    }

    /** An error: rose, a status colour. */
    fun error() {
        for (s in shuttles) { s.animate().cancel(); s.alpha = 0f }
        micGlow.animate().alpha(0f).setDuration(200).start()
        halfA.animate().alpha(0f).setStartDelay(0).setDuration(240).start(); halfB.animate().alpha(0f).setStartDelay(0).setDuration(240).start()
        for (s in roseRing) s.animate().alpha(.9f).setStartDelay(0).setDuration(240).start()
        roseFlare.translationX = width / 2f - roseFlare.width / 2f; roseFlare.translationY = -roseFlare.height / 2f
        roseFlare.alpha = 1f; roseFlare.scaleX = .4f; roseFlare.scaleY = .4f
        roseFlare.animate().alpha(0f).scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(360).start()
    }

    /** Spark going away: the light runs back into the mic and blinks out (400 ms). */
    fun exit() {
        val w = width.toFloat(); val h = height.toFloat()
        val half = w / 2 + h + w / 2
        val t1 = (320 * (w / 2) / half).toLong(); val t2 = (320 * h / half).toLong()
        for (left in listOf(true, false)) {
            val (top, side, bottom) = if (left) ringA else ringB
            bottom.pivotX = if (left) 0f else bottom.width.toFloat()
            side.pivotY = 0f
            top.pivotX = if (left) top.width.toFloat() else 0f
            bottom.animate().scaleX(0f).setStartDelay(0).setDuration(t1).setInterpolator(lin).start()
            side.animate().scaleY(0f).setStartDelay(t1).setDuration(t2).setInterpolator(lin).start()
            top.animate().scaleX(0f).setStartDelay(t1 + t2).setDuration(t1).setInterpolator(lin).start()
        }
        for (s in roseRing) s.animate().alpha(0f).setStartDelay(0).setDuration(320).start()
        for (s in shuttles) { s.animate().cancel(); s.alpha = 0f }
        micGlow.animate().alpha(0f).setStartDelay(0).setDuration(200).start()
        dim.animate().alpha(0f).setStartDelay(0).setDuration(400).start()
        bevel.animate().alpha(0f).setStartDelay(0).setDuration(400).start()
        flash(0, w / 2, 0f, from = .2f, to = .5f, delay = 300, dur = 140)
    }

    /** A caustic in box [i] centred at ([x], [y]): it appears at [delay] and grows from [from] to [to] while fading. */
    private fun flash(i: Int, x: Float, y: Float, from: Float, to: Float, delay: Long, dur: Long) {
        val box = flareBox[i]; val f = flares[i]
        box.animate().cancel(); f.animate().cancel()
        box.translationX = x - f.width / 2f; box.translationY = y - f.height / 2f
        box.alpha = 0f
        f.alpha = 1f; f.scaleX = from; f.scaleY = from
        box.animate().alpha(1f).setStartDelay(delay).setDuration(1).start()
        f.animate().alpha(0f).scaleX(to).scaleY(to).setStartDelay(delay).setDuration(dur).setInterpolator(dec).start()
    }

    // ------------------------------------------------------------------ baking (once)
    private fun plain() = object : View(context) { override fun hasOverlappingRendering() = false }
    private fun layer() = object : FrameLayout(context) { override fun hasOverlappingRendering() = false }.apply { clipChildren = false }

    private fun sprite(bmp: Bitmap) = object : ImageView(context) { override fun hasOverlappingRendering() = false }.apply {
        setImageBitmap(bmp); scaleType = ImageView.ScaleType.FIT_XY
        layoutParams = LayoutParams(bmp.width, bmp.height)
        minimumWidth = bmp.width; minimumHeight = bmp.height
    }

    /** One strip: [kind] 0 top, 1 side, 2 bottom; a 1 px profile (edge to inside) stretched along the edge. A [right] side's edge is on its right. */
    private fun strip(colour: Int, kind: Int, right: Boolean, toMid: Boolean = false): ImageView {
        // Top and bottom strips are 256 px along the edge, so a half can run from its corner's light to the a/b middle
        // at the centre, where it meets the other half in the same colour (no seam). Sides are one colour, 1 px long.
        val vertical = kind != 1
        val len = if (vertical) 256 else 1
        val bw = if (vertical) len else depth; val bh = if (vertical) depth else 1
        val px = IntArray(bw * bh)
        val mid = Color.rgb(lerp(Color.red(a), Color.red(b), .5f), lerp(Color.green(a), Color.green(b), .5f), lerp(Color.blue(a), Color.blue(b), .5f))
        for (x in 0 until len) {
            // k: 0 at the corner, 1 at the centre (a left half's corner is on its left, a right half's on its right)
            val k = if (!toMid || len == 1) 0f else if (right) 1f - x / (len - 1f) else x / (len - 1f)
            val r = lerp(Color.red(colour), Color.red(mid), k); val g = lerp(Color.green(colour), Color.green(mid), k); val bl = lerp(Color.blue(colour), Color.blue(mid), k)
            for (i in 0 until depth) {
                val y = i / d // dp from the edge
                val core = exp(-(y / 2f) * (y / 2f)); val glow = .45f * exp(-(y / 10f) * (y / 10f)); val haze = .22f * exp(-(y / 32f) * (y / 32f))
                val al = (core + glow + haze).coerceAtMost(1f)
                val hot = core * .5f * hotK // the edge itself is whiter on a dark ground
                val c = Color.argb((255 * al).toInt(), mix(r, hot), mix(g, hot), mix(bl, hot))
                if (vertical) px[(if (kind == 2) depth - 1 - i else i) * bw + x] = c else px[if (right) depth - 1 - i else i] = c
            }
        }
        val bmp = Bitmap.createBitmap(px, bw, bh, Bitmap.Config.ARGB_8888)
        return object : ImageView(context) { override fun hasOverlappingRendering() = false }.apply { setImageBitmap(bmp); scaleType = ImageView.ScaleType.FIT_XY }
    }

    private fun mix(c: Int, k: Float) = (c + (255 - c) * k).toInt().coerceIn(0, 255)

    private fun lerp(x: Int, y: Int, k: Float) = (x + (y - x) * k).toInt()

    private fun stripParams(kind: Int, left: Boolean): LayoutParams = when (kind) {
        0 -> LayoutParams(0, depth, Gravity.TOP or if (left) Gravity.LEFT else Gravity.RIGHT)
        1 -> LayoutParams(depth, -1, if (left) Gravity.LEFT else Gravity.RIGHT)
        else -> LayoutParams(0, depth, Gravity.BOTTOM or if (left) Gravity.LEFT else Gravity.RIGHT)
    }

    private fun roseParams(i: Int): LayoutParams = when (i) {
        0 -> LayoutParams(-1, depth, Gravity.TOP)
        1 -> LayoutParams(-1, depth, Gravity.BOTTOM)
        2 -> LayoutParams(depth, -1, Gravity.LEFT)
        else -> LayoutParams(depth, -1, Gravity.RIGHT)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // the half strips are half the width
        val w = MeasureSpec.getSize(widthMeasureSpec)
        for (s in listOf(ringA[0], ringA[2], ringB[0], ringB[2])) (s.layoutParams as LayoutParams).width = w / 2
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun dot(colour: Int, rDp: Float, hot: Float): Bitmap {
        val s = ceil(rDp * 2 * d).toInt(); val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val core = Color.rgb(mix(Color.red(colour), hot), mix(Color.green(colour), hot), mix(Color.blue(colour), hot))
        Canvas(bmp).drawRect(0f, 0f, s.toFloat(), s.toFloat(), Paint().apply {
            shader = RadialGradient(s / 2f, s / 2f, s / 2f, intArrayOf(withAlpha(core, 1f), withAlpha(colour, .55f), withAlpha(colour, .12f), withAlpha(colour, 0f)), floatArrayOf(0f, .25f, .6f, 1f), Shader.TileMode.CLAMP)
        })
        return bmp
    }

    private fun ellipse(colour: Int, wDp: Float, hDp: Float): Bitmap {
        val w = ceil(wDp * d).toInt(); val h = ceil(hDp * d).toInt(); val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); c.scale(1f, h.toFloat() / w)
        c.drawRect(0f, 0f, w.toFloat(), w.toFloat(), Paint().apply {
            shader = RadialGradient(w / 2f, w / 2f, w / 2f, intArrayOf(withAlpha(colour, .9f), withAlpha(colour, .35f), withAlpha(colour, 0f)), floatArrayOf(0f, .4f, 1f), Shader.TileMode.CLAMP)
        })
        return bmp
    }

    private fun flare(colour: Int): Bitmap {
        val r = 110 * d; val s = ceil(2 * r).toInt(); val m = s / 2f
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888); val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(m, m, r * .55f, intArrayOf(withAlpha(colour, .5f), withAlpha(colour, .16f), withAlpha(colour, 0f)), floatArrayOf(0f, .4f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, s.toFloat(), s.toFloat(), p)
        p.shader = RadialGradient(m, m, 15 * d, intArrayOf(withAlpha(colour, 1f), withAlpha(colour, .45f), withAlpha(colour, 0f)), floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, s.toFloat(), s.toFloat(), p)
        p.shader = LinearGradient(m - r, 0f, m + r, 0f, intArrayOf(withAlpha(colour, 0f), withAlpha(colour, .9f), withAlpha(colour, 0f)), null, Shader.TileMode.CLAMP)
        c.drawRect(m - r, m - 1.6f * d, m + r, m + 1.6f * d, p)
        return bmp
    }

    private fun withAlpha(c: Int, a: Float) = (c and 0x00ffffff) or ((255 * a.coerceIn(0f, 1f)).toInt() shl 24)
}
