package dev.glassworks.assist

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
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
import kotlin.math.max
import kotlin.math.min

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
 *
 * Its look follows the approved renders (#694's edge.js) as closely as baked bitmaps allow: the lights run a to b
 * diagonally round the whole border (a at the top left, b at the bottom right); on a dark ground they add up, as light
 * does, so where they meet they go white-hot; the voice swells and brightens the whole border and blooms at the mic
 * ([level], through [voice]); thinking's lights are comets with long tails, with glints catching here and there; speaking
 * rests in the corners; an error's rose pulse runs out from the mic.
 */
class EdgeGlow(context: Context, palette: Themes.Palette) : FrameLayout(context) {
    private val d = resources.displayMetrics.density
    private val a = palette.a; private val b = palette.b
    private val light = !palette.darkGround
    private val rose = palette.readable(0xfffb7185.toInt())
    /** What glows white-hot on a dark ground; on a light one, the lights' middle, which shows there. */
    private val hotColour = if (light) palette.readable(Themes.mix(a, b, .5f)) else Color.WHITE
    private val hotK = if (light) 0f else 1f
    private val depth = (88 * d).toInt()
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
    // thinking: comets with long tails, top a/b, bottom a/b (baked at half length, stretched: the tail is smooth)
    private val cometA = comet(a, right = true); private val cometB = comet(b, right = false)
    private val shuttles = Array(4) { i -> stretched(if (i % 2 == 0) cometA else cometB, 2) }
    private val glints = Array(6) { i -> sprite(dot(if (i % 2 == 0) a else b, 12f, hot = .7f * hotK)) }
    private var nextGlint = 0
    // listening: the bloom at the mic, in the lights' colour and white-hot; it widens, brightens and whitens with the voice
    private val micGlow = stretched(bloom(), 2)
    private val micHot = stretched(bloom(hot = true), 2)
    // a loud word: ripples that run from the mic round the border, three each way; and an error's rose pulse
    private val rippleBoxes = Array(8) { FrameLayout(context).apply { clipChildren = false } }
    private val ripples = Array(8) { i -> sprite(dot(when { i >= 6 -> rose; i % 2 == 0 -> a; else -> b }, 40f, hot = (if (i >= 6) 0f else .25f) * hotK)) }
    private var nextRipple = intArrayOf(0, 0)
    // speaking: the light rests in the corners (top left a, bottom right b, the others between)
    private val corners = Array(4) { i -> stretched(corner(Themes.mix(a, b, floatArrayOf(0f, .5f, .5f, 1f)[i]), i), 2, 2) }
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
        for ((i, c) in corners.withIndex()) addView(c, LayoutParams(c.layoutParams.width, c.layoutParams.height, (if (i < 2) Gravity.TOP else Gravity.BOTTOM) or (if (i % 2 == 0) Gravity.LEFT else Gravity.RIGHT)))
        // the stretched sprites keep their own size (wrap_content would measure the half-length bitmap)
        for (m in listOf(micGlow, micHot)) addView(m, LayoutParams(m.layoutParams.width, m.layoutParams.height, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        for ((i, box) in rippleBoxes.withIndex()) { box.addView(ripples[i], LayoutParams(-2, -2)); addView(box, LayoutParams(-2, -2)) }
        for (s in shuttles) addView(s)
        for (g in glints) addView(g)
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
        for (b in rippleBoxes) b.alpha = 1f
        visibility = INVISIBLE
    }

    private fun all(): List<View> = listOf(dim, bevel, micGlow, micHot, roseFlare, halfA, halfB) + ringA + ringB + roseRing + comets + cometBoxes +
        shuttles + glints + ripples + rippleBoxes + corners + flareBox + flares

    /** The border's six strips (top, side, bottom of each half): what the voice swells. For the overlay's tests too. */
    val edges: List<View> get() = (ringA + ringB).toList()

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

    /**
     * Listening: the voice, [v] 0 to 1 ([voice] of the smoothed mic level). From the UI thread, each frame. As in the
     * render, the whole border answers it: every edge brightens and swells (thicker toward the inside), and at the mic the
     * light blooms wider, brighter and whiter. Only alpha and scale change, so RenderThread does the drawing.
     */
    fun level(v: Float) {
        halfA.alpha = .5f + .5f * v; halfB.alpha = halfA.alpha
        swell(1f + .6f * v)
        micGlow.alpha = min(1f, (.3f + v) / 1.1f)
        micGlow.scaleX = (34f + 120f * v) / 154f; micGlow.scaleY = 1f + .5f * v
        micHot.alpha = .6f * v
        micHot.scaleX = micGlow.scaleX; micHot.scaleY = micGlow.scaleY
    }

    /** Every strip [k] times as thick, from its edge inward. */
    private fun swell(k: Float) {
        for ((i, s) in edges.withIndex()) {
            val kind = i % 3; val right = i >= 3
            if (kind == 1) { s.pivotX = if (right) s.width.toFloat() else 0f; s.scaleX = k }
            else { s.pivotY = if (kind == 0) 0f else s.height.toFloat(); s.scaleY = k }
        }
    }

    /** A word: one ripple each way from the mic, running along the top and round the corner down the side (reused sprites). */
    fun ripple(v: Float) {
        for (side in 0..1) {
            val i = side + 2 * nextRipple[side]; nextRipple[side] = (nextRipple[side] + 1) % 3
            travel(i, left = side == 0, alpha = (.95f * v).coerceAtMost(1f), durMs = 1600)
        }
    }

    /** Ripple [i]: from the mic along the top to the corner, then down the side, fading; the render's .62 dp a millisecond. */
    private fun travel(i: Int, left: Boolean, alpha: Float, durMs: Long) {
        val box = rippleBoxes[i]; val r = ripples[i]
        val w = width.toFloat(); val h = height.toFloat()
        val speed = .62f * d // px a millisecond
        val t1 = min(durMs, ((w / 2) / speed).toLong())
        val down = min(h, speed * (durMs - t1))
        box.animate().cancel(); r.animate().cancel()
        box.translationX = w / 2 - r.width / 2f; box.translationY = -r.height / 2f
        r.alpha = alpha
        r.animate().alpha(0f).setStartDelay(0).setDuration(durMs).setInterpolator(dec).start()
        box.animate().translationX(if (left) -r.width / 2f else w - r.width / 2f).setStartDelay(0).setDuration(t1).setInterpolator(lin).start()
        if (durMs > t1) box.animate().translationY(down - r.height / 2f).setStartDelay(t1).setDuration(durMs - t1).setInterpolator(lin).start()
    }

    fun listening() {
        micGlow.translationY = 0f; micGlow.pivotY = 0f
        micHot.translationY = 0f; micHot.pivotY = 0f
        for (s in shuttles) { s.animate().cancel(); s.alpha = 0f }
        for (c in corners) { c.animate().cancel(); c.alpha = 0f }
        for (s in edges) s.alpha = 1f
        roseFlare.alpha = 0f
    }

    /**
     * Thinking: light trapped in the glass, shuttling along the top and bottom edges, flaring where it crosses. Each
     * edge runs its own lap ([LAP_MS]), the bottom half a lap behind the top ([BOTTOM_LAG_MS]): the overlay calls
     * [thinkingTop] and [thinkingBottom] on their own schedules, so a lap always finishes before the next one starts.
     */
    fun thinkingTop(lap: Int) {
        if (lap == 0) {
            micGlow.animate().alpha(0f).setStartDelay(0).setDuration(300).start()
            micHot.animate().alpha(0f).setStartDelay(0).setDuration(300).start()
            // the border dims (the render's .07 between the comets) and settles back to its own thickness
            halfA.animate().alpha(.28f).setStartDelay(0).setDuration(300).start(); halfB.animate().alpha(.28f).setStartDelay(0).setDuration(300).start()
            swell(1f)
        }
        shuttle(bottom = false)
        flash(2, width / 2f, 0f, from = .5f, to = 1.2f, delay = LAP_MS / 2, dur = 450) // they cross at the mic
    }

    fun thinkingBottom() {
        shuttle(bottom = true)
        flash(1, width / 2f, height.toFloat(), from = .5f, to = 1.2f, delay = LAP_MS / 2, dur = 450) // and at the bottom
    }

    /**
     * One lap of a lane's two comets: the left one (a) runs right, the right one (b) runs left, each with its tail behind
     * it (baked facing its way, [comet]). The bottom lane's are flipped so their edge is at the bottom.
     */
    private fun shuttle(bottom: Boolean) {
        val w = width.toFloat(); val h = height.toFloat()
        for (fromLeft in listOf(true, false)) {
            val s = shuttles[(if (bottom) 2 else 0) + (if (fromLeft) 0 else 1)]
            val len = s.layoutParams.width.toFloat() // its own size (stretched), not wrap_content
            val head = if (fromLeft) headFrac * len else (1 - headFrac) * len // where the head is in the view
            s.animate().cancel()
            s.pivotY = s.layoutParams.height / 2f; s.scaleY = if (bottom) -1f else 1f
            check(len > 0) { "a comet's size is its own" }
            s.alpha = 1f
            s.translationY = if (bottom) h - s.layoutParams.height else 0f
            s.translationX = (if (fromLeft) 0f else w) - head
            s.animate().translationX((if (fromLeft) w else 0f) - head).setStartDelay(0).setDuration(LAP_MS).setInterpolator(lin).start()
        }
    }

    /** Thinking: a glint catches at [x] on the top edge or the [bottom] one, in light a or b (reused sprites; 300 ms). */
    fun glint(x: Float, bottom: Boolean, inA: Boolean) {
        val g = glints[(nextGlint++ % 3) * 2 + if (inA) 0 else 1]
        g.animate().cancel()
        g.translationX = x - g.layoutParams.width / 2f
        g.translationY = (if (bottom) height.toFloat() else 0f) - g.layoutParams.height / 2f
        g.alpha = .9f; g.scaleX = .4f; g.scaleY = .4f
        g.animate().alpha(0f).scaleX(1.1f).scaleY(1.1f).setStartDelay(0).setDuration(300).setInterpolator(dec).start()
    }

    /** Speaking: calm. A soft light that breathes slowly with the reply, [l] 0 to 1 smoothed. From the UI thread, each frame. */
    fun speaking(l: Float) {
        // the render's (.34 + .36 corner)(.88 + .3L): an even border, its light pooled in the corners, breathing a little
        halfA.alpha = .5f + .2f * l; halfB.alpha = halfA.alpha
        for (c in corners) c.alpha = .6f + .25f * l
        swell(1f + .12f * l)
    }

    fun speakingStart() {
        for (s in shuttles) { s.animate().cancel(); s.alpha = 0f }
        micGlow.animate().alpha(0f).setDuration(300).start()
        micHot.animate().alpha(0f).setDuration(300).start()
        // the bottom edge, under the caption, stays dimmer
        ringA[2].alpha = .6f; ringB[2].alpha = .6f
    }

    /** An error: rose, a status colour. */
    fun error() {
        for (s in shuttles) { s.animate().cancel(); s.alpha = 0f }
        micGlow.animate().alpha(0f).setDuration(200).start()
        micHot.animate().alpha(0f).setDuration(200).start()
        for (c in corners) c.animate().alpha(0f).setStartDelay(0).setDuration(240).start()
        halfA.animate().alpha(0f).setStartDelay(0).setDuration(240).start(); halfB.animate().alpha(0f).setStartDelay(0).setDuration(240).start()
        for (s in roseRing) s.animate().alpha(.9f).setStartDelay(0).setDuration(240).start()
        // one rose pulse leaves the mic both ways
        travel(6, left = true, alpha = .9f, durMs = 1100); travel(7, left = false, alpha = .9f, durMs = 1100)
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
        micHot.animate().alpha(0f).setStartDelay(0).setDuration(200).start()
        for (c in corners) c.animate().alpha(0f).setStartDelay(0).setDuration(300).start()
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

    private fun sprite(bmp: Bitmap) = stretched(bmp, 1)

    /** A sprite drawn [k] times as long ([kh] as tall) as its bitmap (baked smaller where it changes smoothly: less memory). */
    private fun stretched(bmp: Bitmap, k: Int, kh: Int = 1) = object : ImageView(context) { override fun hasOverlappingRendering() = false }.apply {
        setImageBitmap(bmp); scaleType = ImageView.ScaleType.FIT_XY
        layoutParams = LayoutParams(bmp.width * k, bmp.height * kh)
        minimumWidth = bmp.width * k; minimumHeight = bmp.height * kh
        lit()
    }

    /**
     * On a dark ground the light adds to what is under it, as the render's `lighter` does: where two lights or a light and
     * a flare meet they go white-hot, instead of the top one covering the other. Per bitmap paint, so it costs nothing
     * per frame. On a light ground the lights draw over (added light wouldn't show on white).
     */
    private fun ImageView.lit() { if (!light) (drawable as? BitmapDrawable)?.paint?.xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }

    /**
     * One strip: [kind] 0 top, 1 side, 2 bottom; a profile from the edge inward, 256 px along the edge and stretched to
     * it. A [right] side's edge is on its right; a [right] half's top and bottom start at the middle. The half rings
     * ([toMid]) carry the render's diagonal light, a at the top left to b at the bottom right: the top from 0 to .5 of
     * the way, the sides from their top corner to the bottom one, the bottom from .5 to 1, so the halves meet in one
     * colour at the mic and at the bottom middle (no seam). The rose ring is one colour.
     */
    private fun strip(colour: Int, kind: Int, right: Boolean, toMid: Boolean = false): ImageView {
        val len = 256
        val bw = if (kind != 1) len else depth; val bh = if (kind != 1) depth else len
        val px = IntArray(bw * bh)
        for (x in 0 until len) {
            val t = x / (len - 1f) // along the strip: left to right for a top or bottom, top to bottom for a side
            val k = when {
                !toMid -> -1f
                kind == 0 -> if (right) .25f + .25f * t else .25f * t
                kind == 1 -> if (right) .5f + .5f * t else .5f * t
                else -> if (right) .75f + .25f * t else .5f + .25f * t
            }
            val c0 = if (k < 0) colour else Themes.mix(a, b, k)
            for (i in 0 until depth) {
                val (al, hot) = profile(i / d)
                val c = Color.argb((255 * al).toInt(), mix(Color.red(c0), hot), mix(Color.green(c0), hot), mix(Color.blue(c0), hot))
                if (kind != 1) px[(if (kind == 2) depth - 1 - i else i) * bw + x] = c else px[x * bw + (if (right) depth - 1 - i else i)] = c
            }
        }
        val bmp = Bitmap.createBitmap(px, bw, bh, Bitmap.Config.ARGB_8888)
        return object : ImageView(context) { override fun hasOverlappingRendering() = false }.apply { setImageBitmap(bmp); scaleType = ImageView.ScaleType.FIT_XY; lit() }
    }

    /**
     * The light [y] dp in from the edge, as the render's three stamped sprites add up there (a 4 dp core, a 20 dp glow and a
     * 64 dp haze): its alpha, and how white-hot it is (the core, on a dark ground).
     */
    private fun profile(y: Float): Pair<Float, Float> {
        val core = exp(-(y / 1.8f) * (y / 1.8f)); val glow = .5f * exp(-(y / 12f) * (y / 12f)); val haze = .26f * exp(-(y / 38f) * (y / 38f))
        return (core + glow + haze).coerceAtMost(1f) to core * .6f * hotK
    }

    /**
     * The bloom at the mic, at its widest (the render's sigma 154 dp): the edge's profile, a gaussian along the edge, in
     * the lights' colour round the mic (a left, b right); or [hot], white (on a dark ground) and narrower. Baked at half
     * length.
     */
    private fun bloom(hot: Boolean = false): Bitmap {
        val sigma = (if (hot) 100f else 154f) * d
        val full = (6 * 154f * d).toInt(); val w = full / 2; val h = depth
        val px = IntArray(w * h)
        for (x in 0 until w) {
            val dx = (x * 2 - full / 2f)
            val g = exp(-(dx * dx) / (2 * sigma * sigma))
            val c0 = if (hot) hotColour else Themes.mix(a, b, (.25f + .25f * dx / (full / 2f)).coerceIn(0f, 1f))
            for (i in 0 until h) {
                val (al, wh) = profile(i / d)
                val c = if (hot) c0 else Color.rgb(mix(Color.red(c0), wh), mix(Color.green(c0), wh), mix(Color.blue(c0), wh))
                px[i * w + x] = withAlpha(c, al * g)
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * A thinking comet running [right] or left, as the render's heads: a bright head [headFrac] of the way along its run
     * (28 dp ahead of it), white-hot at its tip on a dark ground, and a long tail behind (340 dp to fall to a third),
     * tapering to nothing. On the top edge; the bottom lane flips it. Baked at half length.
     */
    private fun comet(colour: Int, right: Boolean): Bitmap {
        val full = (COMET_DP * d).toInt(); val w = full / 2; val h = depth
        val headX = headFrac * full
        val px = IntArray(w * h)
        for (x in 0 until w) {
            val xr = if (right) x * 2 else full - 1 - x * 2 // along the run
            val dd = (xr - headX) / d // dp ahead of the head (> 0) or behind it
            // the tail tapers to nothing by the bitmap's end, so it has no edge
            val tail = headFrac * COMET_DP; val left = ((tail + dd) / (.35f * tail)).coerceIn(0f, 1f)
            val v = if (dd > 0) exp(-dd * dd / (2 * 28f * 28f)) else exp(dd / 340f) * (.5f + .5f * exp(dd / 40f)) * left * left * (3 - 2 * left)
            val tip = .6f * hotK * exp(-dd * dd / (2 * 16f * 16f))
            for (i in 0 until h) {
                val (al, wh) = profile(i / d)
                val hot = max(wh, tip * al)
                px[i * w + x] = withAlpha(Color.rgb(mix(Color.red(colour), hot), mix(Color.green(colour), hot), mix(Color.blue(colour), hot)), (al * v).coerceAtMost(1f))
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Speaking: the light pooled in corner [i] (0 top left, 1 top right, 2 bottom left, 3 bottom right), 180 dp across; baked at half size. */
    private fun corner(colour: Int, i: Int): Bitmap {
        val s = (90 * d).toInt(); val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val cx = if (i % 2 == 0) 0f else s.toFloat(); val cy = if (i < 2) 0f else s.toFloat()
        Canvas(bmp).drawRect(0f, 0f, s.toFloat(), s.toFloat(), Paint().apply {
            shader = RadialGradient(cx, cy, s.toFloat(), intArrayOf(withAlpha(colour, .55f), withAlpha(colour, .18f), withAlpha(colour, 0f)), floatArrayOf(0f, .35f, 1f), Shader.TileMode.CLAMP)
        })
        return bmp
    }

    private fun mix(c: Int, k: Float) = (c + (255 - c) * k).toInt().coerceIn(0, 255)


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

    private val headFrac = .9f

    companion object {
        /** A comet's length, head and tail. */
        private const val COMET_DP = 950f

        /**
         * The glow's voice, 0 to 1, from the smoothed mic level ([VoiceLevels.mic]: −55 to −15 dBFS as 0 to 1). Speech at
         * a panel's distance sits well under the render's illustrative level (which peaks near 1), so the range real
         * voices use, from the listening floor to about .5, is opened up to the whole scale, eased at both ends.
         */
        fun voice(l: Float): Float {
            val x = ((l - AudioLevel.LISTENING_FLOOR) / .45f).coerceIn(0f, 1f)
            return x * x * (3 - 2 * x)
        }

        /** One shuttle's run along an edge, and so one thinking lap. */
        const val LAP_MS = 1300L

        /** The bottom edge's laps start this long after the top's. */
        const val BOTTOM_LAG_MS = 650L
    }
}
