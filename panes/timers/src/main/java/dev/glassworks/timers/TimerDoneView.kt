package dev.glassworks.timers

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.glassworks.TimerBoard
import dev.glassworks.setup.Backdrop
import dev.glassworks.ui.Bakery
import dev.glassworks.ui.Dither
import dev.glassworks.ui.Inter
import dev.glassworks.assist.Fader
import dev.glassworks.assist.MainTextFit
import dev.glassworks.assist.OrbArt
import dev.glassworks.assist.OrbBakes
import dev.glassworks.assist.OrbBakes.Companion.bitmap
import dev.glassworks.assist.ThemedBackdrop
import dev.glassworks.theme.Themes
import kotlin.math.sin

/**
 * "Timer done" (0.4.3; mock-ups 0.4.3-timers/05-done*): the voice overlay's stage, the backdrop as a
 * 90 % scrim and the glass orb, here with a ripple going out every 1.5 s (the chimes), "TIMER DONE", the
 * name(s), the length, and the timers still running. In the overlays' theme ([theme], #691): the orb and its
 * first ripple in the theme's light a, the second in b, the scrim in its ground, the text in its ink. A tap anywhere
 * dismisses it ([TimerBoard.dismissFinished], which also stops the chimes); it also ends by itself
 * 60 s after the last finish. Same layout numbers as VoiceOverlay (orb centre 480,162 dp; label 252;
 * main text 282). Main thread.
 */
class TimerDoneView(
    context: Context,
    /** The orb's sphere and shine, shared with the critical alert's orb (#118). */
    private val orbs: OrbBakes = OrbBakes(),
) : FrameLayout(context) {

    /** After a tap dismissed it (the overlay refreshes the timers). */
    var onDismissed: (() -> Unit)? = null

    private val dp = resources.displayMetrics.density
    private var art = OrbArt(dp)
    private var pick: Themes.Pick? = null
    private var tx = TX; private var mu = MU; private var fa = FA
    private fun px(v: Float) = art.px(v)
    private val interp = PathInterpolator(0.65f, 0f, 0.35f, 1f)
    private val ease: (Float) -> Float = { interp.getInterpolation(it.coerceIn(0f, 1f)) }

    private val scrim = object : View(context) { override fun hasOverlappingRendering() = false }.apply { background = Backdrop() }
    private val stage = FrameLayout(context).apply { clipChildren = false; clipToPadding = false }
    private val glow = layer()
    private val rings = Array(2) { Ripple(context) }
    private val sphere = layer()
    private val clip = FrameLayout(context).apply {
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setOval(0, 0, view.width, view.height)
        }
        clipToOutline = true
    }
    private val core = layer()
    private val shine = layer()
    private val label = text()
    private val main = text()
    private val sub = text()
    private val restHead = text()
    private val rest = text()
    private val restRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
    private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
    private val hint = text()

    private val vis = Fader(450f, 550f)
    private var active = false
    private var shownAt = 0L
    private var looping = false
    private var baked = false
    private var lastTexts: TimerTexts.DoneTexts? = null
    private val frame = Choreographer.FrameCallback { looping = false; render(SystemClock.uptimeMillis()) }

    init {
        visibility = View.GONE
        clipChildren = false
        clipToPadding = false
        outlineProvider = null
        isClickable = false
        setOnClickListener { if (active) dismiss() }

        addView(scrim, LayoutParams(-1, -1))
        addView(stage, LayoutParams(px(OrbArt.GLOW_DP), px(OrbArt.GLOW_DP), Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            .apply { topMargin = px(162f - OrbArt.GLOW_DP / 2) })
        fun centred(v: View, sideDp: Float, parent: FrameLayout) = parent.addView(v, LayoutParams(px(sideDp), px(sideDp), Gravity.CENTER))
        centred(glow, OrbArt.GLOW_DP, stage)
        for (r in rings) centred(r, OrbArt.SPHERE_DP, stage)
        centred(sphere, OrbArt.SPHERE_BACK_DP, stage)
        centred(clip, OrbArt.SPHERE_DP - 2, stage)
        centred(core, OrbArt.CORE_DP, clip)
        centred(shine, OrbArt.SPHERE_DP - 2, clip)

        styleLabel(label, AMBER)
        addView(label, LayoutParams(px(840f), -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = px(252f) })

        main.gravity = Gravity.CENTER_HORIZONTAL
        sub.gravity = Gravity.CENTER_HORIZONTAL
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        sub.typeface = Inter.typeface(context, Inter.REGULAR, 17f)
        sub.setTextColor(MU)
        styleLabel(restHead, FA)
        restHead.text = "Still running"
        styleLabel(rest, MU)
        restRow.addView(restHead, LinearLayout.LayoutParams(-2, -2))
        restRow.addView(rest, LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(14f) })
        column.addView(main, LinearLayout.LayoutParams(px(820f), -2))
        column.addView(sub, LinearLayout.LayoutParams(px(820f), -2).apply { topMargin = px(11f) })
        column.addView(restRow, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(28f) })
        addView(column, LayoutParams(px(820f), -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = px(282f) })

        styleLabel(hint, FA)
        hint.gravity = Gravity.CENTER
        hint.text = TAP_HINT
        addView(hint, LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = px(22f) })
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // After the backdrop and the voice orb (1.5 s): nothing needs this until a timer finishes.
        postDelayed({ bake() }, 4_000)
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(frame)
        looping = false
        super.onDetachedFromWindow()
    }

    val showing: Boolean get() = active

    /**
     * A touch screen: a tap dismisses it, and the hint says so. Without touch (the Mirror) nothing can be tapped, so
     * the hint says how it ends instead: by itself, [TimerBoard.DONE_HOLD_MS] after the last timer finished.
     */
    var touch = true
        set(v) { if (v != field) { field = v; hint.text = if (v) TAP_HINT else SELF_HINT; isClickable = active && v } }

    /** The texts' column: [COLUMN_DP], or the screen less a [GUTTER_DP] each side (the Mirror, 540 dp wide). */
    private var columnPx = px(COLUMN_DP)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        if (w > 0) fit(w)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /** Sizes the texts to a screen [screenPx] wide, as the voice overlay does, and fits the name again. */
    private fun fit(screenPx: Int) {
        val col = minOf(px(COLUMN_DP), screenPx - 2 * px(GUTTER_DP)).coerceAtLeast(1)
        if (col == columnPx) return
        columnPx = col
        label.layoutParams.width = minOf(px(COLUMN_DP + 20f), col)
        column.layoutParams.width = col
        main.layoutParams.width = col
        sub.layoutParams.width = col
        rest.maxWidth = (col - px(14f) - restHead.paint.measureText(restHead.text.toString().uppercase()).toInt()).coerceAtLeast(1)
        lastTexts?.let { styleMain(main, it.main) }
    }

    /** Shows, updates or hides from [TimerBoard.done]; call when timers change and once a second. */
    fun refresh(now: Long = SystemClock.elapsedRealtime()) {
        TimerBoard.expire(now)
        val done = TimerBoard.done
        if (done.isEmpty()) { hide(); return }
        val texts = TimerTexts.done(done, TimerBoard.timers, now)
        if (texts != lastTexts) apply(texts)
        show()
    }

    private fun apply(t: TimerTexts.DoneTexts) {
        lastTexts = t
        label.text = t.label
        styleMain(main, t.main)
        main.text = t.main
        sub.text = t.sub
        sub.visibility = if (t.sub.isEmpty()) View.GONE else View.VISIBLE
        rest.text = t.rest
        restRow.visibility = if (t.rest.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun dismiss() {
        TimerBoard.dismissFinished()
        hide()
        onDismissed?.invoke()
    }

    private fun show() {
        if (active) return
        active = true
        isClickable = touch
        val now = SystemClock.uptimeMillis()
        if (visibility != View.VISIBLE) {
            vis.snap(0f)
            shownAt = now
            visibility = View.VISIBLE
            if (!baked) bake()
        }
        vis.set(1f, now)
        startLoop()
    }

    private fun hide() {
        if (!active) return
        active = false
        isClickable = false
        vis.set(0f, SystemClock.uptimeMillis())
        startLoop()
    }

    private fun startLoop() {
        if (looping || visibility != View.VISIBLE) return
        looping = true
        Choreographer.getInstance().postFrameCallback(frame)
    }

    private fun render(now: Long) {
        val v = vis.update(now, ease)
        val t = (now - shownAt).toFloat()
        scrim.alpha = 0.9f * v
        val s = 0.82f + 0.18f * v
        stage.scaleX = s; stage.scaleY = s
        stage.translationY = (1f - v) * 10f * dp
        val pulse = 0.5f + 0.5f * sin(t / 1500f * 2f * Math.PI.toFloat()) // in step with the ripples
        glow.alpha = v * (0.8f + 0.2f * pulse); glow.scaleX = 1.08f + 0.06f * pulse; glow.scaleY = glow.scaleX
        core.alpha = v; core.scaleX = 1.02f + 0.08f * pulse; core.scaleY = core.scaleX
        sphere.alpha = v; shine.alpha = v
        for ((i, r) in rings.withIndex()) r.ripple(((t / RIPPLE_MS + i / 2f) % 1f), v * 0.6f)
        label.alpha = v; column.alpha = v; hint.alpha = v * 0.9f
        if (!active && v <= 0f) {
            visibility = View.GONE
            lastTexts = null
            return
        }
        startLoop()
    }

    /**
     * Takes [p]'s colors: the orb's light, the ripples, the scrim and the text. Glassworks dark keeps the onboarding
     * backdrop itself, as the voice overlay does. A new theme bakes the orb again (once it was baked).
     */
    fun theme(p: Themes.Pick) {
        if (pick?.id == p.id && pick?.palette == p.palette) return
        pick = p
        val palette = p.palette
        val light = !palette.darkGround
        art = OrbArt(dp, palette, light)
        tx = palette.ink
        mu = Themes.mix(tx, palette.ground, 0.35f)
        fa = Themes.mix(tx, palette.ground, 0.58f)
        scrim.background = if (p.id == Themes.DEFAULT.id) Backdrop() else ThemedBackdrop(palette)
        rings[0].tint(palette.a); rings[1].tint(palette.b)
        label.setTextColor(palette.readable(palette.a, 4.5)) // 11.5 sp: small text
        main.setTextColor(tx); sub.setTextColor(mu); rest.setTextColor(mu)
        restHead.setTextColor(fa); hint.setTextColor(fa)
        if (baked) { baked = false; if (isAttachedToWindow) bake() }
    }

    private fun bake() {
        if (baked) return
        baked = true
        val art = art // this theme's, even if another is picked while it bakes (that one bakes after)
        val light = pick?.palette?.darkGround == false
        // The orb's light is the timer's own; a dark glass's sphere and shine are shared (OrbBakes), a light one's
        // (a light theme) are baked here.
        Bakery.bake("timer orb", {
            val d = Dither(0x51ED270B)
            listOf(art.glow(OrbArt.Tint.AMBER).bitmap(d), art.core(OrbArt.Tint.AMBER).bitmap(d)) +
                (if (light) listOf(art.sphere().bitmap(d), art.shine().bitmap(d)) else emptyList())
        }) { b ->
            // A bake for a theme that was replaced meanwhile: the newer one's bake (already asked for) wins.
            if (art === this.art) listOf(glow, core, sphere, shine).zip(b).forEach { (view, bmp) -> view.setImageBitmap(bmp) }
        }
        if (!light) orbs.layers(art) { l -> sphere.setImageBitmap(l.sphere); shine.setImageBitmap(l.shine) }
    }

    // ---------------------------------------------------------------- pieces

    private fun layer() = object : ImageView(context) { override fun hasOverlappingRendering() = false }
        .apply { scaleType = ImageView.ScaleType.FIT_XY }

    private fun text() = TextView(context).apply { includeFontPadding = false }

    private fun styleLabel(tv: TextView, color: Int) {
        tv.typeface = Inter.typeface(context, Inter.MEDIUM, 11.5f)
        tv.letterSpacing = 0.14f
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        tv.setTextColor(color)
        tv.gravity = Gravity.CENTER_HORIZONTAL
        tv.setSingleLine(true)
        tv.ellipsize = TextUtils.TruncateAt.END
        // Last: setSingleLine() replaces the transformation method, which is what all-caps is.
        tv.isAllCaps = true
    }

    /** As VoiceOverlay's main text: 36 sp light on up to 2 lines, then 30, then 26 on 3. */
    private fun styleMain(tv: TextView, text: String) {
        tv.letterSpacing = -0.02f
        tv.setTextColor(tx)
        val width = columnPx
        val paint = TextPaint(tv.paint)
        fun sizePx(sp: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)
        fun extra(p: TextPaint) = p.fontMetricsInt.let { 1.2f * p.textSize - (it.descent - it.ascent) }
        val fit = MainTextFit.choose { sp ->
            paint.textSize = sizePx(sp)
            paint.typeface = Inter.typeface(context, Inter.LIGHT, sp)
            StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).setLineSpacing(extra(paint), 1f)
                .build().lineCount
        }
        tv.typeface = Inter.typeface(context, Inter.LIGHT, fit.sizeSp)
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, fit.sizeSp)
        tv.setLineSpacing(extra(tv.paint), 1f)
        tv.maxLines = fit.maxLines
        tv.ellipsize = TextUtils.TruncateAt.END
    }

    /** A 1 dp ring around the sphere in one of the theme's lights: scale 1 → 1.85 while fading out. */
    private inner class Ripple(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp; color = AMBER }
        override fun hasOverlappingRendering() = false
        fun tint(c: Int) { paint.color = c or 0xff000000.toInt(); invalidate() }
        override fun onDraw(c: Canvas) = c.drawCircle(width / 2f, height / 2f, width / 2f - dp / 2, paint)
        fun ripple(p: Float, strength: Float) {
            val k = ease(p)
            alpha = (1f - k) * strength
            scaleX = 1f + 0.85f * k; scaleY = scaleX
        }
    }

    companion object {
        private const val RIPPLE_MS = 1500f
        const val COLUMN_DP = 820f
        /** As the voice overlay's: room each side of the texts on a narrow screen. */
        const val GUTTER_DP = 32f
        const val TAP_HINT = "Tap anywhere to dismiss"
        /** [TimerBoard.DONE_HOLD_MS] in words: how it ends where nothing can be tapped. */
        const val SELF_HINT = "Closes by itself after a minute"
        private val TX = Color.parseColor("#f4f4f5")
        private val MU = Color.parseColor("#a1a1aa")
        private val FA = Color.parseColor("#63636b")
        private val AMBER = Color.parseColor("#fbbf24")
    }
}
