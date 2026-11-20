package dev.glassworks.s1.glowbench

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import kotlin.math.ceil
import kotlin.math.pow

/**
 * THROWAWAY (test-build/glowbench-s1, never merged): draws the #691 edge glow the way #694 proposes for the build,
 * and times it. Baked once per theme: a core, a glow and a haze sprite, tinted per light (a, b, white, rose), and a
 * caustic per light. Each frame only stamps those bitmaps along the border with an alpha, additively (PLUS), plus a
 * few caustics, the sheen band (a gradient moved by a matrix), the bevel line and the dim. No blur per frame.
 *
 * [play] draws a [Source] until it ends; [onFrame] gets each frame's interval and draw time.
 */
class GlowBenchView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val colours = intArrayOf(0xff38bdf8.toInt(), 0xffa78bfa.toInt(), Color.WHITE, 0xfffb7185.toInt()) // Glassworks dark
    private val ground = 0xff0a0b0f.toInt()

    /** core (4 dp, every 4 dp), glow (20 dp, every 8 dp), haze (64 dp, every 16 dp): [radius dp, step dp, gain]. */
    private val bands = arrayOf(floatArrayOf(4f, 4f, .6f), floatArrayOf(20f, 8f, .22f), floatArrayOf(64f, 16f, .11f))
    private val sprites = Array(4) { c -> Array(bands.size) { b -> dot(bands[b][0], colours[c]) } }
    private val flares = Array(4) { c -> flare(colours[c]) }

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { blendMode = BlendMode.PLUS }
    private val plain = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dst = RectF()
    private val out = FloatArray(5)
    private val xy = FloatArray(3)
    private var samples = FloatArray(0)

    /** What to draw: the frame [ms] after the start and its phase's name, or null when it has ended. */
    fun interface Source { fun at(ms: Float): Pair<String, GlowModel.Frame>? }

    /** Each frame's phase, interval since the previous frame (Choreographer, ms) and the previous draw's time (ms). */
    var onFrame: ((phase: String, intervalMs: Float, drawMs: Float) -> Unit)? = null

    private var source: Source? = null
    private var onEnd: (() -> Unit)? = null
    var model: GlowModel? = null
        private set
    private var startNs = 0L
    private var lastNs = 0L
    private var frame: GlowModel.Frame? = null
    private var lastDrawMs = 0f

    init {
        visibility = INVISIBLE
    }

    /** A model for this view's size, once it has one. */
    fun modelFor(variant: GlowModel.Variant): GlowModel? =
        if (width == 0 || height == 0) null else GlowModel(width / density, height / density, variant).also {
            model = it; samples = FloatArray(ceil(it.p / 4f).toInt() * 5)
        }

    fun play(source: Source, onEnd: () -> Unit) {
        Choreographer.getInstance().removeFrameCallback(tick)
        this.source = source; this.onEnd = onEnd
        startNs = 0L; lastNs = 0L; frame = null
        visibility = VISIBLE
        Choreographer.getInstance().postFrameCallback(tick)
    }

    fun stop() {
        Choreographer.getInstance().removeFrameCallback(tick)
        source = null; frame = null
        visibility = INVISIBLE
    }

    private val tick = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val src = source ?: return
            if (startNs == 0L) startNs = frameTimeNanos
            val next = src.at((frameTimeNanos - startNs) / 1e6f)
            if (next == null) {
                // Ended: nothing on screen, as the overlay would be GONE.
                val end = onEnd
                stop(); end?.invoke()
                return
            }
            // The interval belongs to the frame that just started; the first frame of a run has none.
            if (lastNs != 0L) onFrame?.invoke(next.first, (frameTimeNanos - lastNs) / 1e6f, lastDrawMs)
            lastNs = frameTimeNanos
            frame = next.second
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun hasOverlappingRendering() = false

    // the dim and the bevel's gradient, baked per size
    private var dimShader: Shader? = null
    private var bevelShader: Shader? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        dimShader = LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(alpha(ground, .16f), alpha(ground, .28f), alpha(ground, .6f)), floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
        bevelShader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), colours[0], colours[1], Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val m = model ?: return
        val f = frame ?: return
        val t0 = SystemClock.elapsedRealtimeNanos()
        val w = width.toFloat(); val h = height.toFloat()

        // the dim, deeper at the bottom (one gradient fill)
        plain.shader = dimShader
        plain.alpha = (255 * f.presence).toInt().coerceIn(0, 255)
        canvas.drawRect(0f, 0f, w, h, plain)
        plain.shader = null; plain.alpha = 255

        // sample the light every 4 dp, once; the bands use every 1st, 2nd and 4th sample
        val n = samples.size / 5
        for (i in 0 until n) {
            f.eval(i * 4f, out)
            System.arraycopy(out, 0, samples, i * 5, 5)
        }
        for ((b, band) in bands.withIndex()) {
            val every = (band[1] / 4f).toInt()
            val size = band[0] * 2 * density * (if (b == 0) 1f else f.thick)
            var i = 0
            while (i < n) {
                val o = i * 5
                val inten = samples[o]
                if (inten >= .004f) {
                    m.pos(i * 4f, xy)
                    val x = xy[0] * density; val y = xy[1] * density
                    val tot = (samples[o + 1] + samples[o + 2] + samples[o + 3] + samples[o + 4]).takeIf { it > 0f } ?: 1f
                    dst.set(x - size / 2, y - size / 2, x + size / 2, y + size / 2)
                    for (c in 0 until 4) {
                        val a = inten * (samples[o + 1 + c] / tot) * band[2]
                        if (a < .003f) continue
                        paint.alpha = (255 * a.coerceAtMost(1f)).toInt()
                        canvas.drawBitmap(sprites[c][b], null, dst, paint)
                    }
                }
                i += every
            }
        }

        // the sheen: one gradient across the screen, along the axis where diag() grows
        if (!f.sheenQ.isNaN() && f.sheenA > 0f) {
            val wd = w / density; val hd = h / density
            fun axis(q: Float): FloatArray { val l = 2 * q / (1 / (wd * wd) + 1 / (hd * hd)); return floatArrayOf(l / wd * density, l / hd * density) }
            val p0 = axis(f.sheenQ - .16f); val p1 = axis(f.sheenQ + .16f)
            val a = f.sheenA
            plain.shader = LinearGradient(p0[0], p0[1], p1[0], p1[1],
                intArrayOf(alpha(colours[0], 0f), alpha(colours[0], a * .6f), alpha(Color.WHITE, a), alpha(colours[1], a * .6f), alpha(colours[1], 0f)),
                floatArrayOf(0f, .42f, .5f, .58f, 1f), Shader.TileMode.CLAMP)
            plain.blendMode = BlendMode.PLUS
            canvas.drawRect(0f, 0f, w, h, plain)
            plain.shader = null; plain.blendMode = null
        }

        // the bevel: the pane's inner edge catching the light
        plain.style = Paint.Style.STROKE; plain.strokeWidth = density
        plain.shader = bevelShader
        plain.alpha = (255 * .2f * f.presence).toInt()
        val inset = 7 * density
        canvas.drawRoundRect(inset, inset, w - inset, h - inset, 10 * density, 10 * density, plain)
        plain.shader = null; plain.style = Paint.Style.FILL; plain.alpha = 255

        // caustics
        for (fl in f.flares) {
            val k = fl.age / fl.life
            if (k < 0f || k >= 1f) continue
            val a = fl.amp * (if (fl.grow) 1 - k * k else (1 - k).pow(1.6f))
            val sc = fl.size * (if (fl.grow) 1f else .7f + .5f * GlowModel.easeOut(k))
            m.pos(fl.s, xy)
            val bmp = flares[fl.col]
            val half = bmp.width * sc / 2
            canvas.save()
            canvas.translate(xy[0] * density, xy[1] * density)
            if (xy[2].toInt() % 2 == 1) canvas.rotate(90f)
            dst.set(-half, -half, half, half)
            paint.alpha = (255 * a.coerceIn(0f, 1f)).toInt()
            canvas.drawBitmap(bmp, null, dst, paint)
            canvas.restore()
        }
        lastDrawMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6f
    }

    // ------------------------------------------------------------------ baking
    private fun dot(rDp: Float, colour: Int): Bitmap {
        val s = ceil(rDp * 2 * density).toInt().coerceAtLeast(2)
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(s / 2f, s / 2f, s / 2f,
            intArrayOf(alpha(colour, 1f), alpha(colour, .55f), alpha(colour, .14f), alpha(colour, 0f)), floatArrayOf(0f, .35f, .7f, 1f), Shader.TileMode.CLAMP)
        Canvas(bmp).drawRect(0f, 0f, s.toFloat(), s.toFloat(), p)
        return bmp
    }

    private fun flare(colour: Int): Bitmap {
        val r = 120 * density; val s = ceil(2 * r).toInt(); val m = s / 2f
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { blendMode = BlendMode.PLUS }
        p.shader = RadialGradient(m, m, r * .55f, intArrayOf(alpha(colour, .55f), alpha(colour, .18f), alpha(colour, 0f)), floatArrayOf(0f, .4f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, s.toFloat(), s.toFloat(), p)
        p.shader = RadialGradient(m, m, 16 * density, intArrayOf(alpha(colour, 1f), alpha(colour, .5f), alpha(colour, 0f)), floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, s.toFloat(), s.toFloat(), p)
        fun streak(len: Float, thick: Float, a: Float, vertical: Boolean) {
            p.shader = if (vertical) LinearGradient(0f, m - len, 0f, m + len, intArrayOf(alpha(colour, 0f), alpha(colour, a), alpha(colour, 0f)), null, Shader.TileMode.CLAMP)
            else LinearGradient(m - len, 0f, m + len, 0f, intArrayOf(alpha(colour, 0f), alpha(colour, a), alpha(colour, 0f)), null, Shader.TileMode.CLAMP)
            if (vertical) c.drawRect(m - thick, m - len, m + thick, m + len, p) else c.drawRect(m - len, m - thick, m + len, m + thick, p)
        }
        streak(r, 1.6f * density, .9f, false); streak(r * .8f, 4 * density, .35f, false); streak(r * .35f, 1.4f * density, .6f, true)
        return bmp
    }

    private fun alpha(colour: Int, a: Float): Int = (colour and 0x00ffffff) or ((255 * a.coerceIn(0f, 1f)).toInt() shl 24)
}
