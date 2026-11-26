/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.cards

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LineHeightSpan
import android.text.style.MetricAffectingSpan
import android.text.style.ReplacementSpan
import android.text.style.StrikethroughSpan
import android.text.style.UnderlineSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.MdiIcons
import dev.glassworks.engine.vitro.ui.VitroCardContext
import dev.glassworks.platform.Platform
import dev.glassworks.engine.vitro.ui.VitroFonts
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/** CSS color and opacity helpers shared by the Markdown engine and other cards. */
object MarkdownInline {
    /** `0.6` or `60%` → 0.6, clamped to 0..1. */
    fun parseOpacity(value: String): Float? {
        val v = value.trim()
        val n = if (v.endsWith("%")) v.dropLast(1).trim().toFloatOrNull()?.div(100f) else v.toFloatOrNull()
        // `NaN` and `Infinity` parse as floats but aren't CSS numbers: the caller's default applies.
        return n?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
    }

    fun scaleAlpha(color: Int, mul: Float): Int {
        if (mul >= 1f) return color
        val a = ((color ushr 24) * mul.coerceIn(0f, 1f)).roundToInt()
        return (a shl 24) or (color and 0x00FFFFFF)
    }

    /** CSS colors as HA dashboards write them: `#hex`, `rgb()`, `hsl()` and the CSS / HA named colors. */
    fun parseCssColor(raw: String): Int? {
        val s = raw.trim().removeSuffix("!important").trim()
        ThemeResolver.parseColor(s)?.let { return it }
        CSS_NAMED[s.lowercase(Locale.ROOT)]?.let { return it }
        return runCatching { android.graphics.Color.parseColor(s) }.getOrNull()
    }

    private val CSS_NAMED = mapOf(
        "gold" to 0xFFFFD700.toInt(), "silver" to 0xFFC0C0C0.toInt(), "maroon" to 0xFF800000.toInt(),
        "navy" to 0xFF000080.toInt(), "olive" to 0xFF808000.toInt(), "magenta" to 0xFFFF00FF.toInt(),
        "fuchsia" to 0xFFFF00FF.toInt(), "aqua" to 0xFF00FFFF.toInt(), "brown" to 0xFFA52A2A.toInt(),
        "darkgreen" to 0xFF006400.toInt(), "darkred" to 0xFF8B0000.toInt(), "darkorange" to 0xFFFF8C00.toInt(),
        "lightblue" to 0xFFADD8E6.toInt(), "lightgreen" to 0xFF90EE90.toInt(), "lightgray" to 0xFFD3D3D3.toInt(),
        "lightgrey" to 0xFFD3D3D3.toInt(), "darkgray" to 0xFFA9A9A9.toInt(), "darkgrey" to 0xFFA9A9A9.toInt(),
        "violet" to 0xFFEE82EE.toInt(), "crimson" to 0xFFDC143C.toInt(), "tomato" to 0xFFFF6347.toInt(),
        "dodgerblue" to 0xFF1E90FF.toInt(), "limegreen" to 0xFF32CD32.toInt(), "deepskyblue" to 0xFF00BFFF.toInt(),
        "salmon" to 0xFFFA8072.toInt(), "coral" to 0xFFFF7F50.toInt(), "khaki" to 0xFFF0E68C.toInt(),
        "gray" to 0xFF808080.toInt(), "grey" to 0xFF808080.toInt(),
    )
}

/** CSS `letter-spacing` as a span: [em] of the text size, or [px] absolute (converted against the text size). */
class LetterSpacingSpan(val em: Float?, val px: Float?) : MetricAffectingSpan() {
    private fun applyTo(p: TextPaint) {
        val size = p.textSize
        if (em != null) p.letterSpacing = em else if (px != null && size > 0f) p.letterSpacing = px / size
    }

    override fun updateDrawState(tp: TextPaint) = applyTo(tp)
    override fun updateMeasureState(textPaint: TextPaint) = applyTo(textPaint)
}

/** A typeface for a run of text (weight, italic, monospace) where a StyleSpan could only fake bold. */
class MdTypefaceSpan(val face: Typeface) : MetricAffectingSpan() {
    override fun updateDrawState(tp: TextPaint) { tp.typeface = face }
    override fun updateMeasureState(textPaint: TextPaint) { textPaint.typeface = face }
}

/** A tiny [Drawable] that draws one 24x24 [MdiIcons] path scaled to its bounds. */
class MdiIconDrawable(name: String, color: Int, sizePx: Int) : Drawable() {
    private val source: Path = MdiIcons.pathFor(name)
    private val path = Path()
    private val matrix = Matrix()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        this.color = color
    }

    init {
        setBounds(0, 0, sizePx, sizePx)
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val scale = minOf(b.width(), b.height()) / 24f
        matrix.reset()
        matrix.setScale(scale, scale)
        matrix.postTranslate(b.left.toFloat(), b.top.toFloat())
        path.set(source)
        path.transform(matrix)
        canvas.drawPath(path, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * An inline `<ha-icon>`: an MDI path [sizePx] square in [color]. HA's `ha-icon` is `vertical-align: middle`,
 * so its center sits half an x-height above the baseline.
 */
class MdIconSpan(
    val iconName: String,
    val color: Int,
    val sizePx: Int,
    private val xHeightPx: Float,
    /** `position: relative` nudge, in px: drawn there without moving the line (as the browser does). */
    private val shiftPx: Float = 0f,
) : ReplacementSpan() {
    private val drawable = MdiIconDrawable(iconName, color, sizePx)

    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        if (fm != null) {
            val top = -(xHeightPx / 2f + sizePx / 2f).roundToInt()
            fm.ascent = minOf(fm.ascent, top)
            fm.top = minOf(fm.top, top)
            fm.descent = maxOf(fm.descent, (sizePx / 2f - xHeightPx / 2f).roundToInt())
            fm.bottom = maxOf(fm.bottom, fm.descent)
        }
        return sizePx
    }

    override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        canvas.save()
        canvas.translate(x, y - xHeightPx / 2f - sizePx / 2f + shiftPx)
        drawable.draw(canvas)
        canvas.restore()
    }
}

/** The picture of one Markdown `<img>`: a vector [svg] (data URI) or a loaded [bitmap], drawn stretched to its box. */
class MdPicture(val svg: MdSvg?) {
    var bitmap: Bitmap? = null

    /** The image's own size in pixels when [bitmap] was decoded smaller (an embedded picture past the budget). */
    var intrinsicWidth = 0
    var intrinsicHeight = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private val matrix = Matrix()
    private var bitmapShader: BitmapShader? = null
    private var shaderBitmap: Bitmap? = null

    val ready: Boolean get() = svg != null || bitmap != null

    /** Draws into ([x], [y], [w], [h]) with corner [radius], like CSS `object-fit: fill`. */
    fun draw(canvas: Canvas, context: Context, x: Float, y: Float, w: Float, h: Float, radius: Float) {
        if (svg != null) {
            canvas.save()
            canvas.translate(x, y)
            svg.draw(canvas, w, h, context)
            canvas.restore()
            return
        }
        val bmp = bitmap ?: return
        rect.set(x, y, x + w, y + h)
        // One shader per bitmap; each draw only moves it.
        val shader = bitmapShader?.takeIf { shaderBitmap === bmp }
            ?: BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also { bitmapShader = it; shaderBitmap = bmp }
        matrix.reset()
        matrix.setScale(w / bmp.width, h / bmp.height)
        matrix.postTranslate(x, y)
        shader.setLocalMatrix(matrix)
        paint.shader = shader
        if (radius > 0f) canvas.drawRoundRect(rect, radius, radius, paint) else canvas.drawRect(rect, paint)
    }
}

/**
 * An inline `<img>` [w]x[h] px, drawn once loaded (rounded by [radiusPx]). Baseline-aligned like the browser,
 * or at the line top for `vertical-align: top`.
 */
class MdImageSpan(
    val src: String,
    var w: Int,
    var h: Int,
    private val radiusPx: Float,
    val alignTop: Boolean,
    val picture: MdPicture,
    private val context: Context,
) : ReplacementSpan() {
    /**
     * An `<img>` with no width or height: 0 x 0 until its picture loads, then its natural size (one CSS px per image
     * pixel), at most [maxWidthPx] wide (`max-width: 100%`) with the height kept in proportion, as the browser lays
     * it out. [run] is its line box, which grows with it.
     */
    var natural: Boolean = false
    var run: CssLineBoxSpan.Run? = null

    /** `visibility: hidden`: its room, nothing drawn. */
    var hidden: Boolean = false

    /** The combined `opacity` of the image and the inline elements around it: its picture drawn at that alpha. */
    var opacity: Float = 1f

    /** A natural image with one CSS dimension set (`<img width="100">`): the other follows the picture's shape. */
    var fixedWidthCss: Float? = null
    var fixedHeightCss: Float? = null

    /** Takes the loaded picture's natural size; true when it changed (the text needs laying out again). */
    fun applyNaturalSize(dp: Float, maxWidthPx: Int): Boolean {
        val bmp = picture.bitmap ?: return false
        val iw = picture.intrinsicWidth.takeIf { it > 0 } ?: bmp.width
        val ih = picture.intrinsicHeight.takeIf { it > 0 } ?: bmp.height
        val fw = fixedWidthCss
        val fh = fixedHeightCss
        var nw: Int
        var nh: Int
        when {
            fw != null -> {
                nw = (fw * dp).roundToInt().coerceAtLeast(1)
                nh = (nw.toLong() * ih / iw.coerceAtLeast(1)).toInt().coerceAtLeast(1)
            }
            fh != null -> {
                nh = (fh * dp).roundToInt().coerceAtLeast(1)
                nw = (nh.toLong() * iw / ih.coerceAtLeast(1)).toInt().coerceAtLeast(1)
            }
            else -> {
                nw = (iw * dp).roundToInt().coerceAtLeast(1)
                nh = (ih * dp).roundToInt().coerceAtLeast(1)
            }
        }
        if (maxWidthPx in 1 until nw) {
            nh = (nh.toLong() * maxWidthPx / nw).toInt().coerceAtLeast(1)
            nw = maxWidthPx
        }
        if (nw == w && nh == h) return false
        w = nw
        h = nh
        run?.let { r -> if (alignTop) r.topAligned = nh.toFloat() else r.above = nh.toFloat() }
        return true
    }

    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        if (fm != null && !alignTop) {
            fm.ascent = minOf(fm.ascent, -h)
            fm.top = minOf(fm.top, -h)
        }
        return w
    }

    override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        if (hidden) return
        val t = if (alignTop) top.toFloat() else (y - h).toFloat()
        if (opacity < 1f) {
            val saved = canvas.saveLayerAlpha(x, t, x + w, t + h, (opacity.coerceIn(0f, 1f) * 255f).roundToInt())
            picture.draw(canvas, context, x, t, w.toFloat(), h.toFloat(), radiusPx)
            canvas.restoreToCount(saved)
        } else {
            picture.draw(canvas, context, x, t, w.toFloat(), h.toFloat(), radiusPx)
        }
    }
}

/** A block-level `<img>` (`display: block`): CSS width (px or %) and height, aspect from the picture otherwise. */
class MdImageView(
    context: Context,
    private val picture: MdPicture,
    private val cssWidth: CssLen?,
    private val cssHeight: Float?,
    private val radiusPx: Float,
    private val dp: Float,
) : View(context) {
    init { tag = MarkdownRenderer.IMAGE_TAG }

    private fun ratio(): Float? = picture.svg?.let { s ->
        if (s.intrinsicWidth != null && s.intrinsicHeight != null && s.intrinsicHeight > 0f) s.intrinsicWidth / s.intrinsicHeight else s.ratio
    } ?: picture.bitmap?.let { it.width.toFloat() / it.height.coerceAtLeast(1) }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = MeasureSpec.getMode(widthMeasureSpec)
        val avail = MeasureSpec.getSize(widthMeasureSpec)
        val r = ratio()
        val hPx = cssHeight?.let { (it * dp).roundToInt() }
        var w = cssWidth?.let { l -> if (l.percent && mode == MeasureSpec.UNSPECIFIED) null else (l.resolve(avail / dp) * dp).roundToInt() }
            ?: if (hPx != null && r != null) (hPx * r).roundToInt() else null
            ?: picture.svg?.intrinsicWidth?.let { (it * dp).roundToInt() }
            // The image's own width (an embedded picture past the budget is drawn from a smaller bitmap).
            ?: picture.bitmap?.let { ((picture.intrinsicWidth.takeIf { w -> w > 0 } ?: it.width) * dp).roundToInt() }
            ?: (300 * dp).roundToInt()
        if (mode != MeasureSpec.UNSPECIFIED) w = minOf(w, avail)
        val h = hPx ?: if (r != null && r > 0f) (w / r).roundToInt() else (150 * dp).roundToInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        picture.draw(canvas, context, 0f, 0f, width.toFloat(), height.toFloat(), radiusPx)
    }
}

/** A blank [widthPx] wide (an inline element's margin). */
class MdSpacerSpan(private val widthPx: Int) : ReplacementSpan() {
    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int = widthPx
    override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) = Unit
}

/**
 * CSS line boxes for a TextView: each line is as tall as the tallest inline box on it, every text run
 * contributing its own `line-height` split evenly above and below its glyphs (half-leading), plus the block's
 * strut. Android's own line spacing can't express that, so this span sets each line's ascent and descent.
 */
class CssLineBoxSpan(private val runs: List<Run>, private val strut: Run) : LineHeightSpan {
    /** One inline box: chars [start, end), extending [above] px over the baseline and [below] px under it. */
    /** One inline box on a line. [above] and [topAligned] change once for an image that learns its natural size. */
    class Run(val start: Int, val end: Int, var above: Float, val below: Float, var topAligned: Float = 0f)

    override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int, lineHeight: Int, fm: Paint.FontMetricsInt) {
        var above = strut.above
        var below = strut.below
        var topAligned = 0f
        for (r in runs) {
            if (r.end <= start || r.start >= end) continue
            if (r.topAligned > 0f) { topAligned = max(topAligned, r.topAligned); continue }
            above = max(above, r.above)
            below = max(below, r.below)
        }
        if (topAligned > above + below) below = topAligned - above
        // Round the line's total once, so lines don't each gain a pixel.
        val top = above.roundToInt()
        fm.ascent = -top
        fm.descent = (above + below).roundToInt() - top
        fm.top = fm.ascent
        fm.bottom = fm.descent
    }

    companion object {
        /**
         * How far a line box of [lineHeight] device px reaches above its baseline, as Blink lays text out: the font's
         * [ascent] and [descent] (device px) rounded to whole CSS px, and the half-leading (the line height less both)
         * floored to one. HA's clock (`line-height: 0.8` on 64 px Roboto) puts its baseline 12 px under the line's
         * top less the rounded 59 px ascent, not the 11.9 an exact split gives.
         */
        fun above(ascent: Float, descent: Float, lineHeight: Float, dp: Float): Float {
            val a = (ascent / dp).roundToInt()
            val d = (descent / dp).roundToInt()
            return (kotlin.math.floor((lineHeight / dp - a - d) / 2f) + a) * dp
        }

        /** `line-height: normal` in device px: the font's ascent, descent and line gap, each rounded to whole CSS px. */
        fun normalLineHeight(ascent: Float, descent: Float, gap: Float, dp: Float): Float =
            ((ascent / dp).roundToInt() + (descent / dp).roundToInt() + (gap / dp).roundToInt()) * dp
    }
}

/**
 * A CSS block box: a vertical [LinearLayout] that honors `width` / `height` / `max-width` (content-box, so
 * padding adds to them) and draws its background, radius and borders.
 */
class MdBox(context: Context, private val dp: Float) : LinearLayout(context) {
    var cssWidth: CssLen? = null
    var cssHeight: CssLen? = null
    var cssMaxWidth: CssLen? = null

    init {
        orientation = VERTICAL
        clipChildren = false
        clipToPadding = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var ws = widthMeasureSpec
        var hs = heightMeasureSpec
        val mode = MeasureSpec.getMode(ws)
        val avail = MeasureSpec.getSize(ws)
        val hPad = paddingLeft + paddingRight
        var w: Int? = cssWidth?.let { l ->
            if (l.percent && mode == MeasureSpec.UNSPECIFIED) null else (l.resolve(avail / dp) * dp).roundToInt() + hPad
        }
        cssMaxWidth?.let { l ->
            if (!(l.percent && mode == MeasureSpec.UNSPECIFIED)) {
                val mx = (l.resolve(avail / dp) * dp).roundToInt() + hPad
                w = minOf(w ?: if (mode == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else avail, mx)
            }
        }
        w?.let { ws = MeasureSpec.makeMeasureSpec(if (mode == MeasureSpec.UNSPECIFIED) it else minOf(it, avail), MeasureSpec.EXACTLY) }
        cssHeight?.takeIf { !it.percent }?.let { hs = MeasureSpec.makeMeasureSpec((it.value * dp).roundToInt() + paddingTop + paddingBottom, MeasureSpec.EXACTLY) }
        super.onMeasure(ws, hs)
    }
}

/** Background, border-radius and per-side borders of a CSS box. */
class MdBoxDrawable(
    private val fill: Int?,
    private val radiusPx: Float,
    private val borders: FloatArray,
    private val colors: IntArray,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (fill != null) {
            paint.color = fill
            rect.set(b)
            if (radiusPx > 0f) canvas.drawRoundRect(rect, radiusPx, radiusPx, paint) else canvas.drawRect(rect, paint)
        }
        fun side(i: Int, l: Float, t: Float, r: Float, bt: Float) {
            if (borders[i] <= 0f) return
            paint.color = colors[i]
            canvas.drawRect(l, t, r, bt, paint)
        }
        side(0, b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.top + borders[0])
        side(2, b.left.toFloat(), b.bottom - borders[2], b.right.toFloat(), b.bottom.toFloat())
        side(3, b.left.toFloat(), b.top.toFloat(), b.left + borders[3], b.bottom.toFloat())
        side(1, b.right - borders[1], b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * An HTML table laid out the way Chromium does it for the tables HA dashboards write: `border-spacing`,
 * `table-layout: fixed` (widths from the first row, percentages of the table's inner width) and auto layout
 * (fixed and percent columns first, the rest shared in proportion to each column's max-content width), rows as
 * tall as their tallest cell, `height` spread over the rows, `vertical-align` per cell, collapsed borders, and the
 * table's own background, radius and `overflow: hidden`.
 */
class MdTableView(
    context: Context,
    private val style: MdStyle,
    private val rows: List<List<Cell>>,
    private val dp: Float,
) : ViewGroup(context) {
    /** One cell: its block box, its style and how many columns it spans. */
    class Cell(val view: MdBox, val style: MdStyle, val colspan: Int)

    private val ncol = rows.maxOfOrNull { r -> r.sumOf { it.colspan } } ?: 0
    private var colX = IntArray(0)
    private var colW = IntArray(0)
    private var rowY = IntArray(0)
    private var rowH = IntArray(0)
    private val borderPaint = Paint().apply {
        color = rows.flatten().firstOrNull { c -> c.style.borderWidth.any { it > 0f } }?.style?.borderColor?.get(0) ?: 0
    }
    private val collapseWidth: Int

    init {
        clipChildren = style.overflowHidden
        rows.forEach { r -> r.forEach { addView(it.view) } }
        collapseWidth = if (style.borderCollapse) {
            (rows.flatten().maxOfOrNull { c -> c.style.borderWidth.maxOrNull() ?: 0f } ?: 0f).let { (it * dp).roundToInt() }
        } else 0
        if (style.background != null || style.borderRadius > 0f) {
            background = MdBoxDrawable(style.background, style.borderRadius * dp, FloatArray(4), IntArray(4))
        }
        if (style.overflowHidden && style.borderRadius > 0f) {
            val r = style.borderRadius * dp
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, r)
            }
            clipToOutline = true
        }
        setWillNotDraw(collapseWidth == 0)
    }

    private val spacingH: Int get() = if (style.borderCollapse) collapseWidth else (style.borderSpacingH * dp).roundToInt()
    private val spacingV: Int get() = if (style.borderCollapse) collapseWidth else (style.borderSpacingV * dp).roundToInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = MeasureSpec.getMode(widthMeasureSpec)
        val avail = if (mode == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE / 4 else MeasureSpec.getSize(widthMeasureSpec)
        val sH = spacingH
        val sV = spacingV
        val spacing = sH * (ncol + 1)
        val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)

        // Each column's max-content width and any width its cells ask for.
        val maxC = IntArray(ncol)
        val px = arrayOfNulls<Int>(ncol)
        val pct = arrayOfNulls<Float>(ncol)
        val firstRowOnly = style.tableLayoutFixed && style.width != null
        rows.forEachIndexed { ri, r ->
            var c = 0
            for (cell in r) {
                if (cell.colspan == 1 && c < ncol) {
                    if (!firstRowOnly) {
                        cell.view.measure(unspecified, unspecified)
                        maxC[c] = max(maxC[c], cell.view.measuredWidth)
                    }
                    if (!firstRowOnly || ri == 0) {
                        cell.style.width?.let { w ->
                            val hPad = cell.view.paddingLeft + cell.view.paddingRight
                            if (w.percent) pct[c] = max(pct[c] ?: 0f, w.value)
                            else px[c] = max(px[c] ?: 0, (w.value * dp).roundToInt() + hPad)
                        }
                    }
                }
                c += cell.colspan
            }
        }
        for (c in 0 until ncol) px[c]?.let { maxC[c] = max(maxC[c], it) }
        // A cell spanning columns needs its content's width across them (less the spacing between): what the columns
        // lack goes to them evenly, as the browser's auto layout spreads a spanning cell's minimum.
        if (!firstRowOnly) rows.forEach { r ->
            var c = 0
            for (cell in r) {
                if (cell.colspan > 1 && c < ncol) {
                    val last = minOf(ncol, c + cell.colspan)
                    cell.view.measure(unspecified, unspecified)
                    val need = cell.view.measuredWidth - sH * (last - c - 1)
                    val have = (c until last).sumOf { maxC[it] }
                    if (need > have) {
                        val extra = need - have
                        val n = last - c
                        for (k in c until last) maxC[k] += extra / n + if (k - c < extra % n) 1 else 0
                    }
                }
                c += cell.colspan
            }
        }

        val tableW = style.width?.let { w ->
            if (w.percent && mode == MeasureSpec.UNSPECIFIED) null else (w.resolve(avail / dp) * dp).roundToInt()
        }?.coerceAtMost(avail) ?: run {
            val want = maxC.sum() + spacing
            minOf(avail, want)
        }
        val inner = (tableW - spacing).coerceAtLeast(0)
        val widths = FloatArray(ncol)
        var fixedSum = 0f
        val autos = ArrayList<Int>()
        for (c in 0 until ncol) {
            val w = pct[c]?.let { it * inner / 100f } ?: px[c]?.toFloat()
            if (w != null) { widths[c] = w; fixedSum += w } else autos += c
        }
        var rest = inner - fixedSum
        if (autos.isNotEmpty()) {
            if (firstRowOnly) {
                autos.forEach { widths[it] = (rest / autos.size).coerceAtLeast(0f) }
            } else {
                val sumMax = autos.sumOf { maxC[it] }.toFloat()
                autos.forEach { c -> widths[c] = (if (sumMax > 0f) rest * maxC[c] / sumMax else rest / autos.size).coerceAtLeast(0f) }
            }
        } else if (rest > 0.5f && fixedSum > 0f) {
            // Every column is sized but they don't fill the table: share the rest in proportion.
            for (c in 0 until ncol) widths[c] += rest * widths[c] / fixedSum
            rest = 0f
        }
        colW = IntArray(ncol)
        colX = IntArray(ncol)
        var x = sH.toFloat()
        for (c in 0 until ncol) {
            colX[c] = x.roundToInt()
            val next = x + widths[c]
            colW[c] = next.roundToInt() - colX[c]
            x = next + sH
        }

        // Row heights: the tallest cell; a table `height` spreads what's left over the rows.
        rowH = IntArray(rows.size)
        rows.forEachIndexed { ri, r ->
            var c = 0
            var h = 0
            for (cell in r) {
                val w = cellWidth(c, cell.colspan)
                cell.view.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), unspecified)
                h = max(h, cell.view.measuredHeight)
                cell.style.height?.takeIf { !it.percent }?.let { h = max(h, (it.value * dp).roundToInt() + cell.view.paddingTop + cell.view.paddingBottom) }
                c += cell.colspan
            }
            rowH[ri] = h
        }
        var totalH = if (rows.isEmpty()) 0 else rowH.sum() + sV * (rows.size + 1)
        style.height?.takeIf { !it.percent }?.let { hh ->
            val want = (hh.value * dp).roundToInt()
            if (rows.isEmpty()) totalH = maxOf(totalH, want)
            if (want > totalH && rows.isNotEmpty()) {
                val extra = want - totalH
                for (ri in rowH.indices) rowH[ri] += extra / rows.size + if (ri < extra % rows.size) 1 else 0
                totalH = want
            }
        }
        rowY = IntArray(rows.size)
        var y = sV
        rows.indices.forEach { ri -> rowY[ri] = y; y += rowH[ri] + sV }
        rows.forEachIndexed { ri, r ->
            var c = 0
            for (cell in r) {
                cell.view.measure(
                    MeasureSpec.makeMeasureSpec(cellWidth(c, cell.colspan), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(rowH[ri], MeasureSpec.EXACTLY),
                )
                c += cell.colspan
            }
        }
        setMeasuredDimension(tableW, totalH)
    }

    private fun cellWidth(col: Int, span: Int): Int {
        if (col >= ncol) return 0
        val last = (col + span - 1).coerceAtMost(ncol - 1)
        return colX[last] + colW[last] - colX[col]
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        rows.forEachIndexed { ri, row ->
            var c = 0
            for (cell in row) {
                if (c < ncol) {
                    val x = colX[c]
                    cell.view.layout(x, rowY[ri], x + cell.view.measuredWidth, rowY[ri] + cell.view.measuredHeight)
                }
                c += cell.colspan
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (collapseWidth <= 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        val bw = collapseWidth.toFloat()
        for (c in 0 until ncol) canvas.drawRect(colX[c] - bw, 0f, colX[c].toFloat(), h, borderPaint)
        if (ncol > 0) canvas.drawRect(colX[ncol - 1] + colW[ncol - 1].toFloat(), 0f, w, h, borderPaint)
        for (ri in rowY.indices) canvas.drawRect(0f, rowY[ri] - bw, w, rowY[ri].toFloat(), borderPaint)
        if (rowY.isNotEmpty()) canvas.drawRect(0f, (rowY.last() + rowH.last()).toFloat(), w, h, borderPaint)
    }
}

/**
 * Renders HA Markdown as native views, the way `ha-markdown` shows it: the content goes through `marked`
 * and HA's sanitizer ([MdHtml]), every element gets its style from the browser defaults, HA's markdown styles
 * and the card's `ha-markdown$` card-mod sheet ([MdCascade]), and blocks, inline text, tables, lists, rules,
 * icons and images are laid out from those styles in CSS px (1 CSS px = 1 dp).
 */
class MarkdownRenderer(private val ctx: VitroCardContext) {
    private val dp = ctx.dp
    private val metricsPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val metricsCache = HashMap<Long, FloatArray>()

    /** The style `ha-markdown` hands down when no card styles it: the theme's body text. */
    fun defaultRootStyle(): MdStyle = MdStyle().apply {
        val vars = ctx.theme.vars
        display = "block"
        fontSize = MdCascade.length(MdCascade.resolveVars(vars["ha-font-size-m"] ?: "14px", emptyMap(), vars) ?: "14px", 16f, 0f) ?: 14f
        lineHeightFactor = (vars["ha-line-height-normal"] ?: "1.6").trim().toFloatOrNull() ?: 1.6f
        lineHeight = lineHeightFactor * fontSize
        color = ctx.theme.primaryTextColor
        custom = emptyMap()
    }

    /**
     * Fills [into] (the `ha-markdown-element` box) with [markdown]. When only text changed since the last render into
     * the same box (the same elements, attributes, computed styles and sheet: a clock's minute, a sensor's value), the
     * text views of the changed runs get their new text in place ([updateInPlace]); anything else is built again.
     */
    fun render(markdown: String, into: LinearLayout, parentStyle: MdStyle = defaultRootStyle(), sheet: MdSheet = MdSheet.EMPTY, breaks: Boolean = true) {
        val root = MdHtml.fromMarkdown(markdown, breaks)
        MdCascade.apply(root, parentStyle, sheet, ctx.theme.vars)
        val prev = last
        if (prev != null && prev.into === into && prev.sheet === sheet && into.childCount > 0 &&
            MdTreeCompare.sameStyle(prev.parentStyle, parentStyle) && updateInPlace(prev, root)
        ) {
            prev.root = root
            prev.parentStyle = parentStyle
            return
        }
        into.removeAllViews()
        into.orientation = LinearLayout.VERTICAL
        into.clipChildren = false
        into.clipToPadding = false
        val rs = root.style!!
        into.alpha = rs.opacity
        val runs = ArrayList<RunRecord>()
        recording = runs
        try {
            fill(into, root)
        } finally {
            recording = null
        }
        last = Rendered(into, sheet, parentStyle, root, runs)
    }

    /** One inline run as built: its nodes and block (as child-index paths from the root) and the view it made. */
    private class RunRecord(val paths: List<IntArray>, val blockPath: IntArray, val view: View?)

    /** The last full render: what [updateInPlace] compares the next one with. */
    private class Rendered(val into: LinearLayout, val sheet: MdSheet, var parentStyle: MdStyle, var root: MdNode, val runs: List<RunRecord>)

    private var last: Rendered? = null
    private var recording: MutableList<RunRecord>? = null

    /**
     * Puts the new text of [root] into the views of [prev] when [root] differs from the last render only in the
     * content of text nodes. Each changed run is built as a full render would build it, and its text moved into the
     * existing view (its other settings come from the block's style, which is equal). False, with nothing changed, when
     * the structure or any style differs, or a changed run has no view or shows an image: then the caller rebuilds.
     */
    private fun updateInPlace(prev: Rendered, root: MdNode): Boolean {
        if (!MdTreeCompare.sameExceptText(prev.root, root)) return false
        val updates = ArrayList<Pair<TextView, CharSequence>>()
        for (rec in prev.runs) {
            val oldNodes = rec.paths.map { MdTreeCompare.nodeAt(prev.root, it) ?: return false }
            val newNodes = rec.paths.map { MdTreeCompare.nodeAt(root, it) ?: return false }
            if (oldNodes.indices.all { MdTreeCompare.sameText(oldNodes[it], newNodes[it]) }) continue
            val old = rec.view as? TextView ?: return false
            if (old.tag == IMAGE_TAG) return false
            val block = MdTreeCompare.nodeAt(root, rec.blockPath)?.style ?: return false
            val fresh = inlineView(newNodes, block) as? TextView ?: return false
            if (fresh.tag == IMAGE_TAG) return false
            updates += old to fresh.text
        }
        for ((view, text) in updates) view.text = text
        return true
    }

    /** Lays [node]'s children into [box]: block children as views, runs of inline content as text lines. */
    private fun fill(box: LinearLayout, node: MdNode) {
        val built = ArrayList<Built>()
        val run = ArrayList<MdNode>()
        fun flushRun() {
            if (run.isEmpty()) return
            val view = inlineView(run.toList(), node.style!!)
            recording?.add(RunRecord(run.map { MdTreeCompare.pathOf(it) }, MdTreeCompare.pathOf(node), view))
            view?.let { built += Built(it, 0f, 0f, 0f, 0f) }
            run.clear()
        }
        for (child in node.children) {
            val s = child.style ?: continue
            if (!child.isText && s.display == "none") continue
            if (!child.isText && isBlock(child)) {
                flushRun()
                block(child)?.let { built += it }
            } else {
                run += child
            }
        }
        flushRun()
        // Adjacent vertical margins collapse into the larger one.
        for (k in 1 until built.size) {
            val prev = built[k - 1]
            val cur = built[k]
            val merged = collapse(prev.bottom, cur.top)
            prev.bottom = 0f
            cur.top = merged
        }
        val parentAlign = node.style!!.textAlign
        for (b in built) {
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = (b.top * dp).roundToInt()
            lp.bottomMargin = (b.bottom * dp).roundToInt()
            lp.leftMargin = (b.left.takeIf { !it.isNaN() } ?: 0f).let { (it * dp).roundToInt() }
            lp.rightMargin = (b.right.takeIf { !it.isNaN() } ?: 0f).let { (it * dp).roundToInt() }
            if (b.shrinks) {
                lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
                lp.gravity = when {
                    b.left.isNaN() && b.right.isNaN() -> Gravity.CENTER_HORIZONTAL
                    b.left.isNaN() -> Gravity.END
                    parentAlign == "center" -> Gravity.CENTER_HORIZONTAL
                    parentAlign == "right" || parentAlign == "end" -> Gravity.END
                    else -> Gravity.START
                }
            }
            box.addView(b.view, lp)
        }
    }

    private class Built(val view: View, var top: Float, var bottom: Float, val left: Float, val right: Float, val shrinks: Boolean = false)

    private fun collapse(a: Float, b: Float): Float {
        val x = if (a.isNaN()) 0f else a
        val y = if (b.isNaN()) 0f else b
        // The larger positive margin plus the most negative one, as CSS collapses them.
        return max(max(x, 0f), max(y, 0f)) + minOf(minOf(x, 0f), minOf(y, 0f))
    }

    private fun isBlock(n: MdNode): Boolean {
        val s = n.style ?: return false
        if (n.tag == "img") return s.display == "block"
        if (n.tag == "ha-icon" || n.tag == "br") return false
        if (s.isBlockLevel) return true
        // An inline element holding blocks becomes a block, as the browser splits it around them.
        return n.children.any { !it.isText && isBlock(it) }
    }

    /** One block-level element as a view with its (collapsed) margins. */
    private fun block(node: MdNode): Built? {
        val s = node.style!!
        val m = s.margin
        return when {
            s.display == "table" -> table(node)?.let { Built(it, m[0], m[2], m[3], m[1], shrinks = s.width == null) }
            node.tag == "img" -> blockImage(node)?.let {
                val full = s.width?.let { w -> w.percent && w.value >= 100f } ?: false
                Built(it, m[0], m[2], m[3], m[1], shrinks = !full)
            }
            node.tag == "hr" -> {
                val h = (s.borderWidth[0] + s.borderWidth[2] + (s.height?.value ?: 0f))
                val px = (h * dp).roundToInt().coerceAtLeast(if (h > 0f) 1 else 0)
                // A plain View would take all the height an AT_MOST spec offers; a rule is exactly its borders.
                val v = object : View(ctx.context) {
                    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
                        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), px)
                }.apply {
                    setBackgroundColor(s.borderColor[0])
                    tag = RULE_TAG
                    alpha = s.opacity
                }
                Built(v, m[0], m[2], m[3], m[1])
            }
            else -> {
                val box = MdBox(ctx.context, dp)
                styleBox(box, s)
                if (s.display == "list-item") listItem(box, node) else fill(box, node)
                // position: relative moves the box where it's painted, not where it's laid out.
                s.relativeShiftY().takeIf { it != 0f }?.let { box.translationY = it * dp }
                var top = m[0]
                var bottom = m[2]
                // A first child's top margin escapes a box with no padding or border above it (and the same below).
                if (s.padding[0] == 0f && s.borderWidth[0] == 0f && box.childCount > 0) {
                    val lp = box.getChildAt(0).layoutParams as LinearLayout.LayoutParams
                    top = collapse(top, lp.topMargin / dp)
                    lp.topMargin = 0
                }
                if (s.padding[2] == 0f && s.borderWidth[2] == 0f && s.height == null && box.childCount > 0) {
                    val lp = box.getChildAt(box.childCount - 1).layoutParams as LinearLayout.LayoutParams
                    bottom = collapse(bottom, lp.bottomMargin / dp)
                    lp.bottomMargin = 0
                }
                val shrink = s.width != null && !s.width!!.percent && (m[3].isNaN() || m[1].isNaN())
                Built(box, top, bottom, m[3], m[1], shrinks = shrink)
            }
        }
    }

    private fun styleBox(box: MdBox, s: MdStyle) {
        box.setPadding(
            ((s.padding[3] + s.borderWidth[3]) * dp).roundToInt(),
            ((s.padding[0] + s.borderWidth[0]) * dp).roundToInt(),
            ((s.padding[1] + s.borderWidth[1]) * dp).roundToInt(),
            ((s.padding[2] + s.borderWidth[2]) * dp).roundToInt(),
        )
        box.cssWidth = s.width
        box.cssHeight = s.height
        box.cssMaxWidth = s.maxWidth
        if (!s.hidden && (s.background != null || s.borderWidth.any { it > 0f })) {
            box.background = MdBoxDrawable(s.background, s.borderRadius * dp, FloatArray(4) { s.borderWidth[it] * dp }, s.borderColor)
        }
        if (s.overflowHidden && s.borderRadius > 0f) {
            val r = s.borderRadius * dp
            box.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, r)
            }
            box.clipToOutline = true
        }
        if (s.opacity < 1f) box.alpha = s.opacity
    }

    /** A list item: its content, with the marker hanging in the list's start padding. */
    private fun listItem(box: MdBox, node: MdNode) {
        val s = node.style!!
        val content = MdBox(ctx.context, dp)
        fill(content, node)
        val parent = node.parent
        val ordered = parent?.tag == "ol"
        val marker = if (s.listStyleNone || !ordered) null else "${parent!!.children.filter { it.tag == "li" }.indexOf(node) + 1}."
        val frame = FrameLayout(ctx.context).apply { clipChildren = false; clipToPadding = false }
        frame.addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        if (!s.listStyleNone && !ordered) {
            // Disc, circle, square: Chromium paints a shape, not the bullet character ([MdListSymbol]).
            val depth = generateSequence(parent) { it.parent }.count { it.tag == "ul" || it.tag == "ol" }
            val symbol = MdListSymbol(ctx.context, content, depth.coerceIn(1, 3) - 1, s.color, typefaceFor(s), s.fontSize * dp, dp)
            frame.addView(symbol, FrameLayout.LayoutParams(symbol.boxWidth, FrameLayout.LayoutParams.MATCH_PARENT).apply { leftMargin = -symbol.boxWidth })
        }
        if (marker != null) {
            val tv = textView(s)
            val text = SpannableStringBuilder(marker)
            tv.text = text
            tv.gravity = Gravity.END
            val w = (s.fontSize * 2.5f * dp).roundToInt()
            frame.addView(tv, FrameLayout.LayoutParams(w, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = -w - (s.fontSize * 0.4f * dp).roundToInt()
            })
        }
        box.addView(frame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    /** A `<table>`: rows from its sections (or itself), each cell a block box. */
    private fun table(node: MdNode): View {
        val s = node.style!!
        val trs = ArrayList<MdNode>()
        for (c in node.children) {
            // A row or row group with `display: none` takes no space, as in the browser.
            if (c.style?.display == "none") continue
            if (c.tag == "tr") trs += c
            else if (c.tag == "thead" || c.tag == "tbody" || c.tag == "tfoot") c.children.filterTo(trs) { it.tag == "tr" && it.style?.display != "none" }
        }
        val rows = trs.map { tr ->
            tr.children.filter { (it.tag == "td" || it.tag == "th") && it.style?.display != "none" }.map { td ->
                val cs = td.style!!
                val box = MdBox(ctx.context, dp)
                styleBox(box, cs)
                box.cssWidth = null
                box.cssHeight = null
                box.gravity = when (cs.verticalAlign) {
                    "top", "text-top", "baseline" -> Gravity.TOP
                    "bottom", "text-bottom" -> Gravity.BOTTOM
                    else -> Gravity.CENTER_VERTICAL
                }
                fill(box, td)
                // Inside a cell, the first and last margins stay inside (a cell is its own formatting context).
                MdTableView.Cell(box, cs, td.attrs["colspan"]?.toIntOrNull()?.coerceIn(1, 64) ?: 1)
            }
        }.filter { it.isNotEmpty() }
        // A table whose cells are all hidden still has its own height, margins and background.
        return MdTableView(ctx.context, s, rows, dp).apply { if (s.opacity < 1f) alpha = s.opacity }
    }

    /**
     * The picture for [src]: data-URI SVGs are parsed here; an embedded raster (`data:image/…;base64`) and anything
     * else is loaded by [load], off the main thread.
     */
    private fun picture(src: String): MdPicture? {
        if (src.startsWith("data:", ignoreCase = true)) {
            MdSvg.fromDataUri(src)?.let { return MdPicture(it) }
            return if (isEmbeddedRaster(src)) MdPicture(null) else null
        }
        return MdPicture(null)
    }

    /**
     * Fetches [picture] once [owner] is on screen (a hidden card loads none, as on HA's page). An embedded raster
     * fetches nothing: it is decoded off the main thread at once.
     */
    private fun load(src: String, picture: MdPicture, owner: View, done: () -> Unit) {
        if (isEmbeddedRaster(src)) {
            ctx.decodeEmbedded(src) { decoded ->
                if (decoded != null) {
                    picture.intrinsicWidth = decoded.width
                    picture.intrinsicHeight = decoded.height
                    picture.bitmap = decoded.bitmap
                    done()
                }
            }
            return
        }
        ctx.loadImage(src, owner) { bmp -> if (bmp != null) { picture.bitmap = bmp; done() } }
    }

    private fun isEmbeddedRaster(src: String): Boolean =
        src.startsWith("data:image/", ignoreCase = true) && src.substringBefore(',').endsWith(";base64", ignoreCase = true)

    /** An `<img>` made a block by its sheet (`display: block`). */
    private fun blockImage(node: MdNode): View? {
        val s = node.style!!
        val src = node.attrs["src"]?.takeIf { it.isNotBlank() } ?: return null
        val picture = picture(src) ?: return null
        val view = MdImageView(ctx.context, picture, s.width, s.height?.takeIf { !it.percent }?.value, s.borderRadius * dp, dp)
        if (!picture.ready) load(src, picture, view) { view.requestLayout(); view.invalidate() }
        if (s.opacity < 1f) view.alpha = s.opacity
        // Its room, nothing drawn; still shown as far as loading goes, so the picture (and its size) arrives.
        if (s.hidden) view.alpha = 0f
        return view
    }

    private fun textView(s: MdStyle): TextView = TextView(ctx.context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, s.fontSize * dp)
        typeface = typefaceFor(s)
        setTextColor(s.color)
        includeFontPadding = false
        setLineSpacing(0f, 1f)
        setPadding(0, 0, 0, 0)
        if (Platform.hasFallbackLineSpacing) isFallbackLineSpacing = false
        if (s.letterSpacing != 0f && s.fontSize > 0f) letterSpacing = s.letterSpacing / s.fontSize
    }

    /** A run of inline content (text, inline elements, icons, images, line breaks) as one TextView. */
    private fun inlineView(nodes: List<MdNode>, block: MdStyle): View? {
        val sb = SpannableStringBuilder()
        val runs = ArrayList<CssLineBoxSpan.Run>()
        val images = ArrayList<MdImageSpan>()
        val state = InlineState()
        for (n in nodes) inline(n, sb, runs, images, state, 1f)
        // A `<br>` at the very end doesn't open another line; spaces at the end of the block are dropped.
        while (sb.isNotEmpty() && sb[sb.length - 1] == ' ') sb.delete(sb.length - 1, sb.length)
        if (sb.isNotEmpty() && sb[sb.length - 1] == '\n') sb.delete(sb.length - 1, sb.length)
        if (sb.isEmpty() || (sb.isBlank() && runs.isEmpty() && images.isEmpty() && sb.none { it == ' ' })) return null
        val tv = textView(block)
        val strut = lineBox(block, 0, 0)
        sb.setSpan(CssLineBoxSpan(runs, strut), 0, sb.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        tv.text = sb
        tv.gravity = when (block.textAlign) {
            "center" -> Gravity.CENTER_HORIZONTAL
            "right", "end" -> Gravity.END
            else -> Gravity.START
        }
        tv.textAlignment = when (block.textAlign) {
            "center" -> View.TEXT_ALIGNMENT_CENTER
            "right", "end" -> View.TEXT_ALIGNMENT_VIEW_END
            else -> View.TEXT_ALIGNMENT_VIEW_START
        }
        images.forEach { span ->
            if (!span.picture.ready) load(span.src, span.picture, tv) {
                if (span.natural && span.applyNaturalSize(dp, tv.width - tv.paddingLeft - tv.paddingRight)) {
                    // The line grows to the picture: lay the text out again with the same spans.
                    tv.text = tv.text
                } else {
                    tv.invalidate()
                }
            }
        }
        if (images.isNotEmpty()) tv.tag = IMAGE_TAG
        if (images.any { it.natural }) {
            // A natural-size picture follows the text's width: capped when the text gets narrower (or first gets a
            // width after the picture decoded), and back to its own size when there's room again.
            tv.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or, _ ->
                if (r - l == or - ol) return@addOnLayoutChangeListener
                val w = (r - l) - tv.paddingLeft - tv.paddingRight
                var changed = false
                for (span in images) if (span.natural && span.picture.ready && span.applyNaturalSize(dp, w)) changed = true
                if (changed) tv.post { tv.text = tv.text }
            }
        }
        // On every inline text view: an in-place update can give one its first link later (it only swaps the text).
        tv.setOnTouchListener(MdLinkSpan.Touch())
        return tv
    }

    private class InlineState {
        var lastSpace = true

        /** Text ranges of `visibility: hidden` content: painted invisible, so a link there takes no taps either. */
        val hidden = ArrayList<IntRange>()
    }

    private fun inline(
        n: MdNode,
        sb: SpannableStringBuilder,
        runs: MutableList<CssLineBoxSpan.Run>,
        images: MutableList<MdImageSpan>,
        st: InlineState,
        alphaMul: Float,
    ) {
        val s = n.style ?: return
        if (n.isText) {
            val start = sb.length
            appendText(sb, n.text, s, st)
            if (sb.length > start) {
                styleRun(sb, start, s, alphaMul)
                runs += lineBox(s, start, sb.length)
                if (s.hidden) st.hidden += start until sb.length
            }
            return
        }
        if (s.display == "none") return
        when (n.tag) {
            "br" -> {
                while (sb.isNotEmpty() && sb[sb.length - 1] == ' ') sb.delete(sb.length - 1, sb.length)
                sb.append('\n')
                st.lastSpace = true
            }
            "ha-icon" -> {
                val icon = n.attrs["icon"]
                if (!icon.isNullOrBlank()) {
                    val size = (s.width?.takeIf { !it.percent }?.value ?: 24f)
                    val px = (size * dp).roundToInt().coerceAtLeast(1)
                    val parentStyle = n.parent?.style ?: s
                    val xh = parentStyle.fontSize * X_HEIGHT * dp
                    val start = sb.length
                    sb.append('￼')
                    val color = MarkdownInline.scaleAlpha(s.color, if (s.hidden) 0f else alphaMul * s.opacity)
                    sb.setSpan(MdIconSpan(icon, color, px, xh, s.relativeShiftY() * dp), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (s.hidden) st.hidden += start until sb.length
                    runs += CssLineBoxSpan.Run(start, sb.length, xh / 2f + px / 2f, px / 2f - xh / 2f)
                    st.lastSpace = false
                }
            }
            "img" -> {
                val src = n.attrs["src"]?.takeIf { it.isNotBlank() }
                val picture = src?.let { picture(it) }
                val svg = picture?.svg
                var w = s.width?.takeIf { !it.percent }?.value
                var h = s.height?.takeIf { !it.percent }?.value
                if (svg != null) {
                    val ratio = if (svg.intrinsicWidth != null && svg.intrinsicHeight != null && svg.intrinsicHeight > 0f) svg.intrinsicWidth / svg.intrinsicHeight else svg.ratio
                    if (w == null && h == null) { w = svg.intrinsicWidth ?: 300f; h = svg.intrinsicHeight ?: (w / ratio) }
                    else if (w == null) w = h!! * ratio
                    else if (h == null) h = w / ratio
                }
                // A bitmap missing a CSS size takes its natural size once loaded (MdImageSpan.natural): both from the
                // picture, or the one not given from the picture's shape.
                val natural = src != null && picture != null && svg == null && (w == null || h == null)
                if (src != null && picture != null && ((w != null && h != null) || natural)) {
                    val wpx = if (natural) 0 else (w!! * dp).roundToInt().coerceAtLeast(1)
                    val hpx = if (natural) 0 else (h!! * dp).roundToInt().coerceAtLeast(1)
                    val top = s.verticalAlign == "top"
                    val span = MdImageSpan(src, wpx, hpx, s.borderRadius * dp, top, picture, ctx.context)
                    span.hidden = s.hidden
                    // The inline ancestors' opacity reaches the picture, as it reaches the text around it.
                    span.opacity = alphaMul * s.opacity
                    val start = sb.length
                    sb.append('\uFFFC')
                    sb.setSpan(span, start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    val run = if (top) CssLineBoxSpan.Run(start, sb.length, 0f, 0f, hpx.toFloat())
                    else CssLineBoxSpan.Run(start, sb.length, hpx.toFloat(), 0f)
                    runs += run
                    if (s.hidden) st.hidden += start until sb.length
                    if (natural) {
                        span.natural = true
                        span.run = run
                        span.fixedWidthCss = w
                        span.fixedHeightCss = h
                    }
                    images += span
                    st.lastSpace = false
                } else {
                    n.attrs["alt"]?.let { alt ->
                        val start = sb.length
                        appendText(sb, alt, s, st)
                        if (sb.length > start) { styleRun(sb, start, s, alphaMul); runs += lineBox(s, start, sb.length) }
                    }
                }
            }
            else -> {
                inlineMargin(sb, s.margin[3])
                val start = sb.length
                val mul = alphaMul * s.opacity
                for (c in n.children) inline(c, sb, runs, images, st, mul)
                // A link to a dashboard path navigates there in place, as HA's markdown does. Other schemes (http,
                // https, …) stay text: the panel has no browser to send them to.
                val href = if (n.tag == "a") n.attrs["href"]?.trim() else null
                if (href != null && href.startsWith("/") && !href.startsWith("//") && sb.length > start) {
                    // Only the visible parts: hidden text keeps its room but takes no tap (a descendant that is
                    // visible again does).
                    var from = start
                    for (h in st.hidden.filter { it.first >= start }.sortedBy { it.first }) {
                        if (h.first > from) sb.setSpan(MdLinkSpan(href, ctx.navigate), from, h.first, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        from = maxOf(from, h.last + 1)
                    }
                    if (sb.length > from) sb.setSpan(MdLinkSpan(href, ctx.navigate), from, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                inlineMargin(sb, s.margin[1])
                if (s.background != null && !s.hidden && sb.length > start) {
                    sb.setSpan(BackgroundColorSpan(MarkdownInline.scaleAlpha(s.background!!, mul)), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
    }

    /** An inline element's horizontal margin, as a blank of that width. */
    private fun inlineMargin(sb: SpannableStringBuilder, cssPx: Float) {
        if (cssPx.isNaN() || cssPx <= 0f) return
        val start = sb.length
        sb.append('\u200B')
        sb.setSpan(MdSpacerSpan((cssPx * dp).roundToInt()), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /** Appends [text] with CSS white-space collapsing (or verbatim for `pre`) and `text-transform`. */
    private fun appendText(sb: SpannableStringBuilder, text: String, s: MdStyle, st: InlineState) {
        if (s.whiteSpacePre) {
            sb.append(transform(text, s.textTransform))
            st.lastSpace = text.endsWith("\n")
            return
        }
        val out = StringBuilder(text.length)
        for (c in text) {
            if (c == ' ' || c == '\n' || c == '\t' || c == '\r' || c == '\u000C') {
                if (!st.lastSpace) { out.append(' '); st.lastSpace = true }
            } else {
                out.append(c)
                st.lastSpace = false
            }
        }
        sb.append(transform(out.toString(), s.textTransform))
    }

    private fun transform(text: String, t: String?): String = when (t) {
        // CSS case rules follow the page's language (`<html lang>`, HA's `hass.language`), not the panel's.
        "uppercase" -> text.uppercase(pageLocale())
        "lowercase" -> text.lowercase(pageLocale())
        "capitalize" -> text.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(pageLocale()) } }
        else -> text
    }

    private fun styleRun(sb: SpannableStringBuilder, start: Int, s: MdStyle, alphaMul: Float) {
        val end = sb.length
        fun span(what: Any) = sb.setSpan(what, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        span(AbsoluteSizeSpan((s.fontSize * dp).roundToInt().coerceAtLeast(1), false))
        span(MdTypefaceSpan(typefaceFor(s)))
        // `visibility: hidden` paints nothing and keeps its room (a descendant may be visible again).
        span(ForegroundColorSpan(MarkdownInline.scaleAlpha(s.color, if (s.hidden) 0f else alphaMul)))
        if (s.letterSpacing != 0f && s.fontSize > 0f) span(LetterSpacingSpan(s.letterSpacing / s.fontSize, null))
        if (s.underline && !s.hidden) span(UnderlineSpan())
        if (s.strike && !s.hidden) span(StrikethroughSpan())
    }

    /** The CSS inline box of text in style [s]: its line height split evenly around the glyphs. */
    private fun lineBox(s: MdStyle, start: Int, end: Int): CssLineBoxSpan.Run {
        val (ascent, descent, gap) = fontMetrics(s)
        val lh = s.lineHeightPx(dp, CssLineBoxSpan.normalLineHeight(ascent, descent, gap, dp))
        val above = CssLineBoxSpan.above(ascent, descent, lh, dp)
        return CssLineBoxSpan.Run(start, end, above, lh - above)
    }

    private fun fontMetrics(s: MdStyle): FloatArray {
        val tf = typefaceFor(s)
        val px = s.fontSize * dp
        val key = (System.identityHashCode(tf).toLong() shl 32) or java.lang.Float.floatToIntBits(px).toLong().and(0xFFFFFFFFL)
        return metricsCache.getOrPut(key) {
            metricsPaint.typeface = tf
            metricsPaint.textSize = px
            val fm = metricsPaint.fontMetrics
            floatArrayOf(-fm.ascent, fm.descent, fm.leading)
        }
    }

    private fun typefaceFor(s: MdStyle): Typeface = MdFonts.typeface(ctx.context, s.fontWeight, s.italic, s.fontFamily, s.fontSize)

    companion object {
        /**
         * One line of [text] in style [s] as a TextView exactly as tall as its CSS line box (`line-height`, with
         * the half-leading above and below the glyphs), for cards that lay out HA's DOM by hand.
         */
        fun cssLine(context: Context, dp: Float, text: CharSequence, s: MdStyle): TextView = CssLineText(context).apply {
            val tf = MdFonts.typeface(context, s.fontWeight, s.italic, s.fontFamily, s.fontSize)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, s.fontSize * dp)
            typeface = tf
            setTextColor(s.color)
            includeFontPadding = false
            setLineSpacing(0f, 1f)
            setPadding(0, 0, 0, 0)
            maxLines = 1
            if (Platform.hasFallbackLineSpacing) isFallbackLineSpacing = false
            if (s.letterSpacing != 0f && s.fontSize > 0f) letterSpacing = s.letterSpacing / s.fontSize
            val p = TextPaint().apply { typeface = tf; textSize = s.fontSize * dp }
            val fm = p.fontMetrics
            val lh = s.lineHeightPx(dp, CssLineBoxSpan.normalLineHeight(-fm.ascent, fm.descent, fm.leading, dp))
            val above = CssLineBoxSpan.above(-fm.ascent, fm.descent, lh, dp)
            val strut = CssLineBoxSpan.Run(0, 0, above, lh - above)
            val sb = SpannableStringBuilder(text)
            sb.setSpan(CssLineBoxSpan(emptyList(), strut), 0, sb.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            this.text = sb
            if (s.opacity < 1f) alpha = s.opacity
        }

        /** Roboto's x-height over its em: where `vertical-align: middle` centers an icon. */
        const val X_HEIGHT = 0.528f
        const val RULE_TAG = "md-rule"
        const val IMAGE_TAG = "md-image"
    }
}

/**
 * A one-line CSS text whose glyphs may paint outside its line box, as in the browser. A `line-height` below the
 * font's own height (HA's clock uses 0.8) makes the box shorter than the glyphs; a plain [TextView] clips them
 * to its bounds and slices the tops off the digits. Horizontal clipping stays.
 */
class CssLineText(context: Context) : TextView(context) {
    override fun onDraw(canvas: Canvas) {
        val l = layout
        if (l == null || l.lineCount != 1 || compoundDrawables.any { it != null }) return super.onDraw(canvas)
        paint.color = currentTextColor
        paint.drawableState = drawableState
        canvas.save()
        val big = height.toFloat() + 10_000f
        canvas.clipRect((compoundPaddingLeft + scrollX).toFloat(), -big, (width - compoundPaddingRight + scrollX).toFloat(), big)
        canvas.translate(compoundPaddingLeft.toFloat(), extendedPaddingTop.toFloat())
        l.draw(canvas)
        canvas.restore()
    }
}

/**
 * Typefaces for Markdown's styles: [fontFamily] (a CSS list, null for the card's own font) at a CSS weight, through
 * [VitroFonts], so a web font a stylesheet declares draws here too. [sizePx] picks Inter's optical size.
 */
object MdFonts {
    fun typeface(context: Context, weight: Int, italic: Boolean, fontFamily: String?, sizePx: Float): Typeface =
        VitroFonts.typeface(context, weight.coerceIn(100, 900), sizePx, italic, fontFamily)
}

/**
 * A `disc` / `circle` / `square` list marker as Chromium paints it (`ListMarker`'s symbol geometry): with the font's
 * whole-pixel ascent `a` and `o = 2a/3` (integer division), a `(o + 1) / 2` px shape whose box starts `o + 8` px before
 * the content, 1 px in, `3 (a - o) / 2` px below the top of the first line's ascent. A circle is a 1 px outline.
 */
class MdListSymbol(
    context: Context,
    private val content: View,
    /** 0 disc, 1 circle, 2 square. */
    private val kind: Int,
    private val color: Int,
    typeface: Typeface,
    textSizePx: Float,
    private val dp: Float,
) : View(context) {
    private val ascent: Int
    private val offset: Int
    /** The marker box's width in device px: it hangs this far before the content. */
    val boxWidth: Int

    init {
        val p = Paint().apply { this.typeface = typeface; textSize = textSizePx / dp }
        ascent = (-p.fontMetrics.ascent).roundToInt()
        offset = ascent * 2 / 3
        boxWidth = ((offset + 8) * dp).roundToInt()
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        content.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> invalidate() }
    }

    override fun onDraw(canvas: Canvas) {
        // The content and this view share the list item's frame.
        val baseline = (baselineIn(content) ?: return) + content.top - top
        val size = (offset + 1) / 2 * dp
        val left = 1f * dp
        val top = baseline - ascent * dp + 3 * (ascent - offset) / 2 * dp
        paint.color = color
        when (kind) {
            0 -> { paint.style = Paint.Style.FILL; canvas.drawOval(left, top, left + size, top + size, paint) }
            1 -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp
                val h = dp / 2f
                canvas.drawOval(left + h, top + h, left + size - h, top + size - h, paint)
            }
            else -> { paint.style = Paint.Style.FILL; canvas.drawRect(left, top, left + size, top + size, paint) }
        }
    }

    /** The first text line's baseline in [v]'s own coordinates. */
    private fun baselineIn(v: View): Float? {
        if (v.visibility == GONE) return null
        if (v is TextView) return if (v.text.isNotEmpty() && v.layout != null) v.baseline.toFloat() else null
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) {
                val c = v.getChildAt(i)
                baselineIn(c)?.let { return it + c.top + c.translationY }
            }
        }
        return null
    }
}

/**
 * Compares two cascaded Markdown trees for [MarkdownRenderer]'s in-place text updates: the same elements, attributes
 * and computed styles everywhere, text content aside. Styles are compared field by field (every [MdStyle] field,
 * found by reflection so a new one is never missed); a field it can't compare makes them different.
 */
internal object MdTreeCompare {
    private val styleFields: List<java.lang.reflect.Field> by lazy {
        MdStyle::class.java.declaredFields
            .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .onEach { it.isAccessible = true }
    }

    fun sameStyle(a: MdStyle?, b: MdStyle?): Boolean {
        if (a === b) return true
        if (a == null || b == null) return false
        for (f in styleFields) {
            val x = f.get(a)
            val y = f.get(b)
            val same = when (x) {
                is FloatArray -> y is FloatArray && x.contentEquals(y)
                is IntArray -> y is IntArray && x.contentEquals(y)
                null -> y == null
                is String, is Number, is Boolean, is Map<*, *>, is CssLen -> x == y
                else -> false
            }
            if (!same) return false
        }
        return true
    }

    /** Same tags, attributes, children and computed styles all the way down; text nodes' content may differ. */
    fun sameExceptText(a: MdNode, b: MdNode): Boolean {
        if (a.tag != b.tag || a.attrs != b.attrs || a.children.size != b.children.size) return false
        if (!a.isText && !sameStyle(a.style, b.style)) return false
        for (i in a.children.indices) if (!sameExceptText(a.children[i], b.children[i])) return false
        return true
    }

    /** Whether two nodes in the same place carry the same text, all the way down. */
    fun sameText(a: MdNode, b: MdNode): Boolean {
        if (a.text != b.text || a.children.size != b.children.size) return false
        for (i in a.children.indices) if (!sameText(a.children[i], b.children[i])) return false
        return true
    }

    /** [n]'s place from its root, as child indices. */
    fun pathOf(n: MdNode): IntArray {
        val out = ArrayList<Int>()
        var cur = n
        while (true) {
            val parent = cur.parent ?: break
            out += parent.children.indexOfFirst { it === cur }
            cur = parent
        }
        return out.asReversed().toIntArray()
    }

    fun nodeAt(root: MdNode, path: IntArray): MdNode? {
        var cur = root
        for (i in path) cur = cur.children.getOrNull(i) ?: return null
        return cur
    }
}

/**
 * A Markdown link to a dashboard path: a tap navigates there. Drawn as the cascade styled it (no link color or
 * underline of Android's own). Taps elsewhere in the text are left to the card ([Touch] only takes those on a link).
 */
internal class MdLinkSpan(val href: String, private val navigate: (String) -> Unit) : android.text.style.ClickableSpan() {
    override fun onClick(widget: View) = navigate(href)

    override fun updateDrawState(ds: android.text.TextPaint) {}

    /** Takes a gesture only when it starts on a link, and follows the link if it also ends there. One per view. */
    class Touch : View.OnTouchListener {
        private var down: MdLinkSpan? = null

        override fun onTouch(v: View, ev: android.view.MotionEvent): Boolean {
            val tv = v as? TextView ?: return false
            val text = tv.text as? Spanned ?: return false
            val layout = tv.layout ?: return false
            fun spanAt(x: Float, y: Float): MdLinkSpan? {
                val lx = x - tv.totalPaddingLeft + tv.scrollX
                val ly = y - tv.totalPaddingTop + tv.scrollY
                // Off the text (above, below, or beside a line's glyphs): no link, whatever offset is nearest.
                if (ly < 0f || ly > layout.height) return null
                val line = layout.getLineForVertical(ly.toInt())
                if (lx < layout.getLineLeft(line) || lx > layout.getLineRight(line)) return null
                val off = layout.getOffsetForHorizontal(line, lx)
                val lineStart = layout.getLineStart(line)
                val lineEnd = layout.getLineEnd(line)
                // The nearest caret can sit at a link's end while the finger is on the next (plain) glyph: a link
                // counts only where it is drawn on this line.
                return text.getSpans((off - 1).coerceAtLeast(lineStart), (off + 1).coerceAtMost(lineEnd), MdLinkSpan::class.java)
                    .firstOrNull { span ->
                        val s = maxOf(text.getSpanStart(span), lineStart)
                        val e = minOf(text.getSpanEnd(span), lineEnd)
                        if (s >= e) return@firstOrNull false
                        // Its drawn extent as the selection would highlight it: right for either direction (an RTL
                        // link's logical end is its left edge).
                        val outline = android.graphics.Path()
                        layout.getSelectionPath(s, e, outline)
                        val box = android.graphics.RectF().also { outline.computeBounds(it, true) }
                        lx >= box.left && lx <= box.right
                    }
            }
            return when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    down = spanAt(ev.x, ev.y)
                    down != null
                }
                android.view.MotionEvent.ACTION_UP -> {
                    val d = down
                    down = null
                    if (d != null && spanAt(ev.x, ev.y) === d) d.onClick(tv)
                    d != null
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    down = null
                    false
                }
                else -> down != null
            }
        }
    }
}

/** The language HA's page is in (`<html lang>`), for CSS case rules. */
private fun pageLocale(): Locale = Locale.forLanguageTag(dev.glassworks.engine.vitro.data.HaLocale.current.language)
