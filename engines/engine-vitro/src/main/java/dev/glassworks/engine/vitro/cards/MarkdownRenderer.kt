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
import dev.glassworks.ui.Inter
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/** CSS color and opacity helpers shared by the Markdown engine and other cards. */
object MarkdownInline {
    /** `0.6` or `60%` → 0.6, clamped to 0..1. */
    fun parseOpacity(value: String): Float? {
        val v = value.trim()
        val n = if (v.endsWith("%")) v.dropLast(1).trim().toFloatOrNull()?.div(100f) else v.toFloatOrNull()
        return n?.coerceIn(0f, 1f)
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
class MdIconSpan(val iconName: String, val color: Int, val sizePx: Int, private val xHeightPx: Float) : ReplacementSpan() {
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
        canvas.translate(x, y - xHeightPx / 2f - sizePx / 2f)
        drawable.draw(canvas)
        canvas.restore()
    }
}

/** The picture of one Markdown `<img>`: a vector [svg] (data URI) or a loaded [bitmap], drawn stretched to its box. */
class MdPicture(val svg: MdSvg?) {
    var bitmap: Bitmap? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private val matrix = Matrix()

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
        val shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
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
    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        if (fm != null && !alignTop) {
            fm.ascent = minOf(fm.ascent, -h)
            fm.top = minOf(fm.top, -h)
        }
        return w
    }

    override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val t = if (alignTop) top.toFloat() else (y - h).toFloat()
        picture.draw(canvas, context, x, t, w.toFloat(), h.toFloat(), radiusPx)
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
            ?: picture.bitmap?.let { (it.width * dp).roundToInt() }
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
    class Run(val start: Int, val end: Int, val above: Float, val below: Float, val topAligned: Float = 0f)

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

    /** Clears [into] (the `ha-markdown-element` box) and fills it with [markdown]. */
    fun render(markdown: String, into: LinearLayout, parentStyle: MdStyle = defaultRootStyle(), sheet: MdSheet = MdSheet.EMPTY) {
        val root = MdHtml.fromMarkdown(markdown)
        MdCascade.apply(root, parentStyle, sheet, ctx.theme.vars)
        into.removeAllViews()
        into.orientation = LinearLayout.VERTICAL
        into.clipChildren = false
        into.clipToPadding = false
        val rs = root.style!!
        into.alpha = rs.opacity
        fill(into, root)
    }

    /** Lays [node]'s children into [box]: block children as views, runs of inline content as text lines. */
    private fun fill(box: LinearLayout, node: MdNode) {
        val built = ArrayList<Built>()
        val run = ArrayList<MdNode>()
        fun flushRun() {
            if (run.isEmpty()) return
            inlineView(run.toList(), node.style!!)?.let { built += Built(it, 0f, 0f, 0f, 0f) }
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
        if (s.background != null || s.borderWidth.any { it > 0f }) {
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
        val marker = if (s.listStyleNone) null else when (parent?.tag) {
            "ol" -> "${parent.children.filter { it.tag == "li" }.indexOf(node) + 1}."
            else -> {
                val depth = generateSequence(parent) { it.parent }.count { it.tag == "ul" || it.tag == "ol" }
                when (depth) { 1 -> "•"; 2 -> "◦"; else -> "▪" }
            }
        }
        val frame = FrameLayout(ctx.context).apply { clipChildren = false; clipToPadding = false }
        frame.addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
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
            if (c.tag == "tr") trs += c
            else if (c.tag == "thead" || c.tag == "tbody" || c.tag == "tfoot") c.children.filterTo(trs) { it.tag == "tr" }
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

    /** The picture for [src]: data-URI SVGs are parsed here, anything else is fetched by [load]. */
    private fun picture(src: String): MdPicture? {
        if (src.startsWith("data:", ignoreCase = true)) {
            MdSvg.fromDataUri(src)?.let { return MdPicture(it) }
            if (src.substringBefore(',').endsWith(";base64", ignoreCase = true)) {
                val bmp = runCatching {
                    val bytes = android.util.Base64.decode(src.substringAfter(','), android.util.Base64.DEFAULT)
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }.getOrNull() ?: return null
                return MdPicture(null).apply { bitmap = bmp }
            }
            return null
        }
        return MdPicture(null)
    }

    private fun load(src: String, picture: MdPicture, done: () -> Unit) {
        ctx.loadImage(src) { bmp -> if (bmp != null) { picture.bitmap = bmp; done() } }
    }

    /** An `<img>` made a block by its sheet (`display: block`). */
    private fun blockImage(node: MdNode): View? {
        val s = node.style!!
        val src = node.attrs["src"]?.takeIf { it.isNotBlank() } ?: return null
        val picture = picture(src) ?: return null
        val view = MdImageView(ctx.context, picture, s.width, s.height?.takeIf { !it.percent }?.value, s.borderRadius * dp, dp)
        if (!picture.ready) load(src, picture) { view.requestLayout(); view.invalidate() }
        if (s.opacity < 1f) view.alpha = s.opacity
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
        images.forEach { span -> if (!span.picture.ready) load(span.src, span.picture) { tv.invalidate() } }
        if (images.isNotEmpty()) tv.tag = IMAGE_TAG
        return tv
    }

    private class InlineState { var lastSpace = true }

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
                    val color = MarkdownInline.scaleAlpha(s.color, alphaMul * s.opacity)
                    sb.setSpan(MdIconSpan(icon, color, px, xh), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
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
                if (src != null && picture != null && w != null && h != null) {
                    val wpx = (w * dp).roundToInt().coerceAtLeast(1)
                    val hpx = (h * dp).roundToInt().coerceAtLeast(1)
                    val top = s.verticalAlign == "top"
                    val span = MdImageSpan(src, wpx, hpx, s.borderRadius * dp, top, picture, ctx.context)
                    val start = sb.length
                    sb.append('\uFFFC')
                    sb.setSpan(span, start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    runs += if (top) CssLineBoxSpan.Run(start, sb.length, 0f, 0f, hpx.toFloat())
                    else CssLineBoxSpan.Run(start, sb.length, hpx.toFloat(), 0f)
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
                inlineMargin(sb, s.margin[1])
                if (s.background != null && sb.length > start) {
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
        "uppercase" -> text.uppercase(Locale.getDefault())
        "lowercase" -> text.lowercase(Locale.getDefault())
        "capitalize" -> text.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(Locale.getDefault()) } }
        else -> text
    }

    private fun styleRun(sb: SpannableStringBuilder, start: Int, s: MdStyle, alphaMul: Float) {
        val end = sb.length
        fun span(what: Any) = sb.setSpan(what, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        span(AbsoluteSizeSpan((s.fontSize * dp).roundToInt().coerceAtLeast(1), false))
        span(MdTypefaceSpan(typefaceFor(s)))
        span(ForegroundColorSpan(MarkdownInline.scaleAlpha(s.color, alphaMul)))
        if (s.letterSpacing != 0f && s.fontSize > 0f) span(LetterSpacingSpan(s.letterSpacing / s.fontSize, null))
        if (s.underline) span(UnderlineSpan())
        if (s.strike) span(StrikethroughSpan())
    }

    /** The CSS inline box of text in style [s]: its line height split evenly around the glyphs. */
    private fun lineBox(s: MdStyle, start: Int, end: Int): CssLineBoxSpan.Run {
        val (ascent, descent) = fontMetrics(s)
        val lh = s.lineHeightPx() * dp
        val half = (lh - (ascent + descent)) / 2f
        return CssLineBoxSpan.Run(start, end, ascent + half, descent + half)
    }

    private fun fontMetrics(s: MdStyle): FloatArray {
        val tf = typefaceFor(s)
        val px = s.fontSize * dp
        val key = (System.identityHashCode(tf).toLong() shl 32) or java.lang.Float.floatToIntBits(px).toLong().and(0xFFFFFFFFL)
        return metricsCache.getOrPut(key) {
            metricsPaint.typeface = tf
            metricsPaint.textSize = px
            val fm = metricsPaint.fontMetrics
            floatArrayOf(-fm.ascent, fm.descent)
        }
    }

    private fun typefaceFor(s: MdStyle): Typeface = MdFonts.typeface(ctx.context, s.fontWeight, s.italic, s.mono, s.fontSize)

    companion object {
        /**
         * One line of [text] in style [s] as a TextView exactly as tall as its CSS line box (`line-height`, with
         * the half-leading above and below the glyphs), for cards that lay out HA's DOM by hand.
         */
        fun cssLine(context: Context, dp: Float, text: CharSequence, s: MdStyle): TextView = CssLineText(context).apply {
            val tf = MdFonts.typeface(context, s.fontWeight, s.italic, s.mono, s.fontSize)
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
            val half = (s.lineHeightPx() * dp - (fm.descent - fm.ascent)) / 2f
            val strut = CssLineBoxSpan.Run(0, 0, -fm.ascent + half, fm.descent + half)
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

/** Typefaces for CSS weights: Inter where the platform can vary it, else Roboto's light / regular / medium / bold. */
object MdFonts {
    private val cache = HashMap<Long, Typeface>()

    fun typeface(context: Context, weight: Int, italic: Boolean, mono: Boolean, sizePx: Float): Typeface {
        val w = weight.coerceIn(100, 900)
        val key = (w.toLong() shl 8) or (if (italic) 1L else 0L) or (if (mono) 2L else 0L) or
            ((if (Platform.hasFontBuilder) sizePx.roundToInt().coerceIn(14, 32) else 0).toLong() shl 20)
        cache[key]?.let { return it }
        val base = when {
            mono -> Typeface.create(Typeface.MONOSPACE, if (w >= 600) Typeface.BOLD else Typeface.NORMAL)
            Platform.hasFontBuilder -> Inter.typeface(context, w, sizePx)
            w <= 350 -> Typeface.create("sans-serif-light", Typeface.NORMAL)
            w < 450 -> Typeface.create("sans-serif", Typeface.NORMAL)
            w < 550 -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
            else -> Typeface.create("sans-serif", Typeface.BOLD)
        }
        val tf = if (italic) Typeface.create(base, if (w >= 600 && !Platform.hasFontBuilder) Typeface.BOLD_ITALIC else Typeface.ITALIC) else base
        cache[key] = tf
        return tf
    }
}
