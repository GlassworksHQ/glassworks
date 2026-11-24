/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.cards

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.AlignmentSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.MetricAffectingSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import android.text.style.UnderlineSpan
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.CardSurface
import dev.glassworks.engine.vitro.ui.MdiIcons
import dev.glassworks.engine.vitro.ui.VitroCardContext
import dev.glassworks.ui.Inter
import java.util.Locale
import kotlin.math.roundToInt

/** One block of an HA markdown card, as [parseMarkdownBlocks] reads it. Inline markup stays in [text] fields. */
sealed class MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock()
    object Rule : MdBlock() {
        override fun toString() = "Rule"
    }
    data class Paragraph(val text: String) : MdBlock()

    /** One list item; [depth] is its nesting (0 = top level), [marker] is `•` or `3.`. */
    data class ListItem(val ordered: Boolean, val marker: String, val text: String, val depth: Int) : MdBlock()
    data class Quote(val text: String) : MdBlock()

    /** A pipe table; [aligns] holds one of `start`, `center`, `end` per column. */
    data class Table(val header: List<String>, val rows: List<List<String>>, val aligns: List<String>) : MdBlock()
    data class Center(val blocks: List<MdBlock>) : MdBlock()

    /**
     * An HTML flex / progress bar written in a markdown card (`<div style="display:flex;height:10px">` holding
     * `<div style="width:45%;background:#38bdf8"></div>` segments), drawn natively as weighted segments.
     * [trackColor] is the outer div's background, null when it has none.
     */
    data class StackedBar(
        val heightDp: Float,
        val radiusDp: Float,
        val trackColor: Int?,
        val gapDp: Float,
        val segments: List<BarSegment>,
        val align: String = "start",
        val animated: Boolean = false,
    ) : MdBlock()

    /**
     * A standalone HTML `<img>` line (optionally inside a `<div style="text-align:right">` / `<p>` wrapper), drawn as
     * a native image. Sizes come from the `width` / `height` attributes or the `style`; null means "fit".
     * [align] is `start`, `center` or `end`.
     */
    data class Image(
        val src: String,
        val widthDp: Float?,
        val heightDp: Float?,
        val radiusDp: Float,
        val align: String,
    ) : MdBlock()
}

/** One segment of a [MdBlock.StackedBar]: [pct] of the track's width (or fixed [widthDp]), in [color]. */
data class BarSegment(
    val pct: Float,
    val color: Int?,
    val widthDp: Float? = null,
    val heightPct: Float? = null,
    val radiusDp: Float = 0f,
)

/** The small slice of CSS HA dashboards write in markdown `style` attributes. */
object MdCss {
    /** `a: b; c: d` → `{a=b, c=d}`, keys lowercased, `!important` dropped. */
    fun parse(style: String?): Map<String, String> {
        if (style.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (decl in style.split(';')) {
            val colon = decl.indexOf(':')
            if (colon <= 0) continue
            val key = decl.substring(0, colon).trim().lowercase(Locale.ROOT)
            val value = decl.substring(colon + 1).trim().removeSuffix("!important").trim()
            if (key.isNotEmpty() && value.isNotEmpty()) out[key] = value
        }
        return out
    }

    private val LENGTH = Regex("""^\s*(-?\d*\.?\d+)\s*(px|em|rem|%|pt|dp|sp)?\s*$""", RegexOption.IGNORE_CASE)

    /** A CSS length as `(number, unit)`; a bare number has unit `px`. */
    fun length(value: String?): Pair<Float, String>? {
        val m = value?.let { LENGTH.find(it) } ?: return null
        val n = m.groupValues[1].toFloatOrNull() ?: return null
        return n to m.groupValues[2].lowercase(Locale.ROOT).ifEmpty { "px" }
    }

    /** A CSS length in dp (CSS px == dp; em / rem are 16 px). The first value of a shorthand counts. */
    fun lengthDp(value: String?): Float? {
        val (n, unit) = length(value?.trim()?.substringBefore(' ')) ?: return null
        return when (unit) {
            "px", "dp", "sp" -> n
            "pt" -> n * 4f / 3f
            "em", "rem" -> n * 16f
            else -> null
        }
    }

    private val WEIGHTS = mapOf(
        "thin" to 100, "hairline" to 100, "extralight" to 200, "extra-light" to 200, "ultralight" to 200,
        "light" to 300, "lighter" to 300, "normal" to 400, "regular" to 400, "medium" to 500,
        "semibold" to 600, "semi-bold" to 600, "demibold" to 600, "bold" to 700, "bolder" to 700,
        "extrabold" to 800, "extra-bold" to 800, "black" to 900, "heavy" to 900,
    )

    /** `font-weight` as 100..900, or null when unreadable. */
    fun weight(value: String?): Int? {
        val v = value?.trim()?.lowercase(Locale.ROOT) ?: return null
        v.toIntOrNull()?.let { return it.coerceIn(100, 900) }
        return WEIGHTS[v]
    }

    private val COLOR_TOKEN = Regex("""#[0-9a-fA-F]{3,8}\b|rgba?\([^)]*\)|hsla?\([^)]*\)""")

    /** A `background` / `background-color` value: a plain color, or the first color of a gradient. */
    fun backgroundColor(css: Map<String, String>): Int? {
        val raw = css["background-color"] ?: css["background"] ?: return null
        MarkdownInline.parseCssColor(raw)?.let { return it }
        return COLOR_TOKEN.find(raw)?.value?.let { MarkdownInline.parseCssColor(it) }
    }
}

private const val BAR_STYLE_ATTR = """style\s*=\s*(?:"([^"]*)"|'([^']*)')"""
private const val BAR_INNER_SRC = """<div\b[^>]*?$BAR_STYLE_ATTR[^>]*>\s*</div>"""
private val BAR_OUTER = Regex(
    """<div\b[^>]*?$BAR_STYLE_ATTR[^>]*>((?:\s*$BAR_INNER_SRC)+)\s*</div>""",
    RegexOption.IGNORE_CASE,
)
private val BAR_INNER = Regex(BAR_INNER_SRC, RegexOption.IGNORE_CASE)
private val ANY_TAG = Regex("""<[^>]*>""")

/** Reads one bar match, or null when it isn't a bar (supports `%` progress bars and fixed-px equalizer bars). */
private fun stackedBarOf(m: MatchResult): MdBlock.StackedBar? {
    val outer = MdCss.parse(m.groupValues[1].ifEmpty { m.groupValues[2] })
    val flex = outer["display"]?.trim()?.lowercase(Locale.ROOT) == "flex"
    val segments = BAR_INNER.findAll(m.groupValues[3]).mapNotNull { inner ->
        val css = MdCss.parse(inner.groupValues[1].ifEmpty { inner.groupValues[2] })
        val rawW = css["width"] ?: css["flex-basis"]
        val (n, unit) = MdCss.length(rawW) ?: return@mapNotNull null
        val hPair = MdCss.length(css["height"])
        val hPct = if (hPair != null && hPair.second == "%" && hPair.first < 99.9f) hPair.first.coerceIn(5f, 100f) else null
        val segRadius = (MdCss.lengthDp(css["border-radius"]) ?: 0f).coerceIn(0f, 32f)
        when {
            unit == "%" -> BarSegment(n.coerceIn(0f, 100f), MdCss.backgroundColor(css), null, hPct, segRadius)
            flex && (unit == "px" || unit == "dp") -> BarSegment(0f, MdCss.backgroundColor(css), n.coerceIn(1f, 64f), hPct, segRadius)
            else -> null
        }
    }.toList()
    if (segments.isEmpty()) return null
    if (!flex && segments.size > 1) return null
    val height = (MdCss.lengthDp(outer["height"]) ?: 8f).coerceIn(1f, 64f)
    val justify = outer["justify-content"]?.trim()?.lowercase(Locale.ROOT)
    val ml = outer["margin-left"]?.trim()?.lowercase(Locale.ROOT)
    val mr = outer["margin-right"]?.trim()?.lowercase(Locale.ROOT)
    val align = when {
        justify == "flex-end" || justify == "end" || justify == "right" || (ml == "auto" && mr != "auto") -> "end"
        justify == "center" || (ml == "auto" && mr == "auto") -> "center"
        else -> "start"
    }
    val anim = outer["animation"]?.trim()?.lowercase(Locale.ROOT)
    val animated = !anim.isNullOrEmpty() && anim != "none"
    return MdBlock.StackedBar(
        heightDp = height,
        radiusDp = (MdCss.lengthDp(outer["border-radius"]) ?: 0f).coerceIn(0f, height / 2f),
        trackColor = MdCss.backgroundColor(outer),
        gapDp = (MdCss.lengthDp(outer["gap"] ?: outer["column-gap"]) ?: 0f).coerceIn(0f, 24f),
        segments = segments,
        align = align,
        animated = animated,
    )
}

/** Splits one paragraph around the HTML bars in it; text around a bar stays a paragraph of its own. */
private fun splitStackedBars(text: String): List<MdBlock> {
    if (!text.contains("width", ignoreCase = true) || !text.contains("<div", ignoreCase = true)) {
        return listOf(MdBlock.Paragraph(text))
    }
    val out = ArrayList<MdBlock>()
    var pos = 0
    fun addText(chunk: String) {
        val t = chunk.trim().removePrefix("<br>").removeSuffix("<br>").trim()
        // Drop fragments that are nothing but tags (the wrapper div a bar sat in).
        if (t.isNotEmpty() && ANY_TAG.replace(t, "").isNotBlank()) out += MdBlock.Paragraph(t)
    }
    var found = false
    for (m in BAR_OUTER.findAll(text)) {
        val bar = stackedBarOf(m) ?: continue
        found = true
        addText(text.substring(pos, m.range.first))
        out += bar
        pos = m.range.last + 1
    }
    if (!found) return listOf(MdBlock.Paragraph(text))
    addText(text.substring(pos))
    return out
}

/** The `</name>` closing the tag opened just before [from], honoring nesting of the same tag. */
internal fun findHtmlClose(s: String, name: String, from: Int, to: Int): IntRange? {
    val re = Regex("""<(/?)$name\b[^>]*>""", RegexOption.IGNORE_CASE)
    var depth = 1
    var pos = from
    while (true) {
        val m = re.find(s, pos) ?: return null
        if (m.range.last >= to) return null
        if (m.groupValues[1] == "/") {
            depth--
            if (depth == 0) return m.range
        } else if (!m.value.endsWith("/>")) {
            depth++
        }
        pos = m.range.last + 1
    }
}

private val BLOCK_OPEN = Regex("""^\s*<(p|div)\b([^>]*)>""", RegexOption.IGNORE_CASE)

/**
 * The CSS of a `<p style>` / `<div style>` that wraps the whole of [text], or null. Its block-level properties
 * (`text-align`, `letter-spacing`) go on the paragraph's TextView.
 */
fun blockWrapperCss(text: String): Map<String, String>? {
    val m = BLOCK_OPEN.find(text) ?: return null
    val close = findHtmlClose(text, m.groupValues[1], m.range.last + 1, text.length) ?: return null
    if (text.substring(close.last + 1).isNotBlank()) return null
    return MdCss.parse(MarkdownInline.attr(m.groupValues[2], "style")).takeIf { it.isNotEmpty() }
}

/** CSS `letter-spacing` as a span: [em] of the text size, or [px] absolute (converted against the text size). */
class LetterSpacingSpan(val em: Float?, val px: Float?) : MetricAffectingSpan() {
    private fun applyTo(p: TextPaint) {
        val size = p.textSize
        if (em != null) {
            p.letterSpacing = em
        } else if (px != null && size > 0f) {
            p.letterSpacing = px / size
        }
    }

    override fun updateDrawState(tp: TextPaint) = applyTo(tp)
    override fun updateMeasureState(textPaint: TextPaint) = applyTo(textPaint)
}

/** A real font weight (Inter Light, Medium…) for a run of text, where a [StyleSpan] can only fake bold. */
class FontWeightSpan(val weight: Int, private val face: Typeface) : MetricAffectingSpan() {
    override fun updateDrawState(tp: TextPaint) {
        tp.typeface = face
    }

    override fun updateMeasureState(textPaint: TextPaint) {
        textPaint.typeface = face
    }
}

private val HEADING = Regex("""^(#{1,6})\s+(.*?)\s*#*\s*$""")
private val RULE = Regex("""^\s{0,3}(?:(?:\*\s*){3,}|(?:-\s*){3,}|(?:_\s*){3,})$""")
private val HTML_RULE = Regex("""^\s*<hr\s*/?>\s*$""", RegexOption.IGNORE_CASE)
private val BULLET = Regex("""^(\s*)[-*+]\s+(.*)$""")
private val ORDERED = Regex("""^(\s*)(\d{1,9})[.)]\s+(.*)$""")
private val QUOTE = Regex("""^\s{0,3}>\s?(.*)$""")
private val TABLE_SEPARATOR = Regex("""^\s*\|?\s*:?-{1,}:?\s*(\|\s*:?-{1,}:?\s*)*\|?\s*$""")
private val IMG_LINE = Regex(
    """^(?:<(div|p)\b([^>]*)>\s*)?<img\b([^>]*?)/?>\s*(?:</(?:div|p)>)?$""",
    RegexOption.IGNORE_CASE,
)
private val WRAPPER_ONLY = Regex("""^(?:\s*</?(?:div|p)\b[^>]*>\s*)+$""", RegexOption.IGNORE_CASE)
private val WRAPPER_OPEN = Regex("""^\s*<(?:div|p)\b([^>]*)>\s*$""", RegexOption.IGNORE_CASE)

/** `left`/`center`/`right` (CSS or the old `align` attribute) → `start`/`center`/`end`, or null. */
private fun mdAlign(value: String?): String? = when (value?.trim()?.lowercase(Locale.ROOT)) {
    "left", "start" -> "start"
    "center", "middle" -> "center"
    "right", "end" -> "end"
    else -> null
}

/** One `<img>` tag's attributes (plus its wrapper's) as an [MdBlock.Image], or null without a `src`. */
internal fun parseHtmlImage(imgAttrs: String, wrapperAttrs: String?): MdBlock.Image? {
    val src = MarkdownInline.attr(imgAttrs, "src")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val css = MdCss.parse(MarkdownInline.attr(imgAttrs, "style"))
    fun dim(key: String): Float? =
        (MdCss.lengthDp(css[key]) ?: MarkdownInline.attr(imgAttrs, key)?.let { MdCss.lengthDp(it) })
            ?.takeIf { it > 0f }?.coerceAtMost(2000f)
    val radius = (MdCss.lengthDp(css["border-radius"]) ?: 0f).coerceIn(0f, 1000f)
    val ml = css["margin-left"]?.trim()?.lowercase(Locale.ROOT)
    val mr = css["margin-right"]?.trim()?.lowercase(Locale.ROOT)
    val margin = css["margin"]?.trim()?.lowercase(Locale.ROOT)?.split(' ')?.filter { it.isNotEmpty() }.orEmpty()
    val marginAuto = margin.size in 2..4 && margin[1] == "auto"
    val own = when {
        ml == "auto" && mr == "auto" || marginAuto -> "center"
        ml == "auto" -> "end"
        mr == "auto" -> "start"
        else -> mdAlign(css["float"]) ?: mdAlign(MarkdownInline.attr(imgAttrs, "align"))
    }
    val wrapperCss = wrapperAttrs?.let { MdCss.parse(MarkdownInline.attr(it, "style")) }.orEmpty()
    val wrap = mdAlign(wrapperCss["text-align"]) ?: wrapperAttrs?.let { mdAlign(MarkdownInline.attr(it, "align")) }
    return MdBlock.Image(src, dim("width"), dim("height"), radius, own ?: wrap ?: "start")
}

private val CENTER_OPEN = Regex("""<center>""", RegexOption.IGNORE_CASE)
private val CENTER_CLOSE = Regex("""</center>""", RegexOption.IGNORE_CASE)

/**
 * Splits HA markdown into blocks: headings, rules (`---`, `***`, `<hr>`), pipe tables, bullet and numbered lists,
 * blockquotes, `<center>` regions and paragraphs. Like HA's `ha-markdown` (marked with `breaks: true`), single
 * newlines inside a paragraph stay line breaks.
 */
fun parseMarkdownBlocks(md: String): List<MdBlock> {
    val text = md.replace("\r\n", "\n").replace('\r', '\n')
    // Pull <center> regions out first so they can span several blocks.
    val out = ArrayList<MdBlock>()
    var rest = text
    while (true) {
        val open = CENTER_OPEN.find(rest) ?: break
        val close = CENTER_CLOSE.find(rest, open.range.last + 1) ?: break
        out += parseLines(rest.substring(0, open.range.first))
        out += MdBlock.Center(parseLines(rest.substring(open.range.last + 1, close.range.first)))
        rest = rest.substring(close.range.last + 1)
    }
    out += parseLines(rest)
    return out
}

private fun splitRow(line: String): List<String> {
    var s = line.trim()
    if (s.startsWith("|")) s = s.substring(1)
    if (s.endsWith("|") && !s.endsWith("\\|")) s = s.dropLast(1)
    val cells = ArrayList<String>()
    val cur = StringBuilder()
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '\\' && i + 1 < s.length && s[i + 1] == '|') {
            cur.append('|'); i += 2; continue
        }
        if (c == '|') {
            cells += cur.toString().trim(); cur.setLength(0)
        } else {
            cur.append(c)
        }
        i++
    }
    cells += cur.toString().trim()
    return cells
}

private fun parseLines(chunk: String): List<MdBlock> {
    val lines = chunk.split('\n')
    val out = ArrayList<MdBlock>()
    val para = ArrayList<String>()
    fun flush() {
        if (para.isNotEmpty()) {
            val joined = para.joinToString("\n").trim()
            if (joined.isNotEmpty()) out += splitStackedBars(joined)
            para.clear()
        }
    }
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()
        when {
            trimmed.isEmpty() -> flush()
            IMG_LINE.matches(trimmed) -> {
                val m = IMG_LINE.find(trimmed)!!
                // A wrapper opened on the line before (`<div style="text-align:right">` alone) sets the alignment.
                var wrapper = m.groupValues[2].takeIf { m.groupValues[1].isNotEmpty() }
                if (wrapper == null && para.size == 1) {
                    WRAPPER_OPEN.find(para[0].trim())?.let { wrapper = it.groupValues[1]; para.clear() }
                }
                flush()
                val img = parseHtmlImage(m.groupValues[3], wrapper)
                if (img != null) out += img else para += line
            }
            WRAPPER_ONLY.matches(trimmed) && para.isEmpty() && out.lastOrNull() is MdBlock.Image -> Unit
            HTML_RULE.matches(line) || RULE.matches(line) -> { flush(); out += MdBlock.Rule }
            HEADING.matches(trimmed) -> {
                flush()
                val m = HEADING.find(trimmed)!!
                out += MdBlock.Heading(m.groupValues[1].length, m.groupValues[2])
            }
            '|' in trimmed && i + 1 < lines.size && '|' in lines[i + 1] && '-' in lines[i + 1] &&
                TABLE_SEPARATOR.matches(lines[i + 1]) -> {
                flush()
                val header = splitRow(line)
                val aligns = splitRow(lines[i + 1]).map { c ->
                    val l = c.startsWith(":")
                    val r = c.endsWith(":")
                    when {
                        l && r -> "center"
                        r -> "end"
                        else -> "start"
                    }
                }
                i += 2
                val rows = ArrayList<List<String>>()
                while (i < lines.size && lines[i].isNotBlank() && '|' in lines[i]) {
                    val cells = splitRow(lines[i])
                    rows += List(header.size) { idx -> cells.getOrElse(idx) { "" } }
                    i++
                }
                out += MdBlock.Table(header, rows, List(header.size) { aligns.getOrElse(it) { "start" } })
                continue
            }
            QUOTE.matches(line) -> {
                flush()
                val quoted = ArrayList<String>()
                while (i < lines.size && QUOTE.matches(lines[i])) {
                    quoted += QUOTE.find(lines[i])!!.groupValues[1]
                    i++
                }
                out += MdBlock.Quote(quoted.joinToString("\n").trim())
                continue
            }
            BULLET.matches(line) -> {
                flush()
                val m = BULLET.find(line)!!
                out += MdBlock.ListItem(false, "•", m.groupValues[2], depthOf(m.groupValues[1]))
            }
            ORDERED.matches(line) -> {
                flush()
                val m = ORDERED.find(line)!!
                out += MdBlock.ListItem(true, "${m.groupValues[2]}.", m.groupValues[3], depthOf(m.groupValues[1]))
            }
            else -> {
                // A lazy continuation line of a list item belongs to that item.
                val last = out.lastOrNull()
                if (para.isEmpty() && last is MdBlock.ListItem && line.startsWith("  ")) {
                    out[out.size - 1] = last.copy(text = last.text + "\n" + trimmed)
                } else {
                    para += line
                }
            }
        }
        i++
    }
    flush()
    return out
}

private fun depthOf(indent: String): Int = indent.replace("\t", "    ").length / 2

/** An inline `<ha-icon>`: an MDI path drawn at text size in the span's color, centered on the line. */
class MdiIconSpan(val iconName: String, val color: Int, sizePx: Int) :
    ImageSpan(MdiIconDrawable(iconName, color, sizePx)) {
    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        val d = drawable
        val rect = d.bounds
        if (fm != null) {
            val pfm = paint.fontMetricsInt
            fm.ascent = pfm.ascent
            fm.descent = pfm.descent
            fm.top = pfm.top
            fm.bottom = pfm.bottom
        }
        return rect.right
    }

    override fun draw(
        canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint,
    ) {
        val d = drawable
        val fm = paint.fontMetricsInt
        val transY = y + (fm.descent + fm.ascent) / 2 - d.bounds.bottom / 2
        canvas.save()
        canvas.translate(x, transY.toFloat())
        d.draw(canvas)
        canvas.restore()
    }
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

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * Inline markdown + the HTML HA dashboards use, as spans: bold, italic, strikethrough, code, links,
 * `<font color>` / `<span style="color:…">`, `<br>` and `<ha-icon>`. Unknown tags are dropped, their text kept.
 *
 * `style` on `<span>`, `<font>`, `<p>` and `<div>` carries the typography HA dashboards use: `font-size` (px, pt,
 * em, rem, %), `font-weight` (100–900 and keywords), `letter-spacing`, `text-transform`, `opacity` and, on blocks,
 * `text-align`. [density] and [fontScale] turn CSS px into screen px; [typefaceFor] gives a real font for a weight.
 */
class MarkdownInline(
    private val linkColor: Int,
    private val codeBackground: Int,
    private val iconSizePx: Int,
    private val density: Float = 1f,
    private val fontScale: Float = 1f,
    private val typefaceFor: ((Int) -> Typeface?)? = null,
) {
    /** The active `text-transform` while rendering a styled element's content. */
    private var transform: String? = null

    /** The product of every enclosing `opacity`, folded into nested colors. */
    private var alphaMul = 1f

    fun render(text: String, baseColor: Int): SpannableStringBuilder {
        val sb = SpannableStringBuilder()
        transform = null
        alphaMul = 1f
        renderInto(sb, text, 0, text.length, baseColor)
        while (sb.isNotEmpty() && sb[sb.length - 1] == '\n') sb.delete(sb.length - 1, sb.length)
        return sb
    }

    private fun span(sb: SpannableStringBuilder, what: Any, start: Int) {
        if (sb.length > start) sb.setSpan(what, start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun renderInto(sb: SpannableStringBuilder, s: String, from: Int, to: Int, color: Int) {
        var i = from
        val literal = StringBuilder()
        fun flushLiteral() {
            if (literal.isNotEmpty()) {
                sb.append(applyTransform(decodeEntities(literal.toString()), transform))
                literal.setLength(0)
            }
        }
        while (i < to) {
            val c = s[i]
            // Backslash escape.
            if (c == '\\' && i + 1 < to && s[i + 1] in ESCAPABLE) {
                literal.append(s[i + 1]); i += 2; continue
            }
            if (c == '`') {
                val close = s.indexOf('`', i + 1).takeIf { it in 0 until to }
                if (close != null) {
                    flushLiteral()
                    val start = sb.length
                    sb.append(s, i + 1, close)
                    span(sb, TypefaceSpan("monospace"), start)
                    span(sb, BackgroundColorSpan(codeBackground), start)
                    i = close + 1; continue
                }
            }
            if ((c == '*' || c == '_') && i + 1 < to && s[i + 1] == c) {
                val marker = "$c$c"
                val close = findClosing(s, marker, i + 2, to)
                if (close != null && (c == '*' || boundaryBefore(s, i))) {
                    flushLiteral()
                    val start = sb.length
                    renderInto(sb, s, i + 2, close, color)
                    span(sb, StyleSpan(Typeface.BOLD), start)
                    i = close + 2; continue
                }
            }
            if ((c == '*' || c == '_') && i + 1 < to && !s[i + 1].isWhitespace()) {
                val close = findClosing(s, c.toString(), i + 1, to)
                if (close != null && (c == '*' || (boundaryBefore(s, i) && boundaryAfter(s, close + 1)))) {
                    flushLiteral()
                    val start = sb.length
                    renderInto(sb, s, i + 1, close, color)
                    span(sb, StyleSpan(Typeface.ITALIC), start)
                    i = close + 1; continue
                }
            }
            if (c == '~' && s.startsWith("~~", i)) {
                val close = findClosing(s, "~~", i + 2, to)
                if (close != null) {
                    flushLiteral()
                    val start = sb.length
                    renderInto(sb, s, i + 2, close, color)
                    span(sb, StrikethroughSpan(), start)
                    i = close + 2; continue
                }
            }
            if (c == '!' && i + 1 < to && s[i + 1] == '[') {
                val link = parseLink(s, i + 1, to)
                if (link != null) {
                    // Images aren't fetched inline; their alt text stands in.
                    flushLiteral()
                    renderInto(sb, s, link.labelStart, link.labelEnd, color)
                    i = link.end; continue
                }
            }
            if (c == '[') {
                val link = parseLink(s, i, to)
                if (link != null) {
                    flushLiteral()
                    val start = sb.length
                    renderInto(sb, s, link.labelStart, link.labelEnd, linkColor)
                    span(sb, ForegroundColorSpan(linkColor), start)
                    span(sb, UnderlineSpan(), start)
                    span(sb, URLSpanNoStyle(link.url), start)
                    i = link.end; continue
                }
            }
            if (c == '<') {
                val m = TAG_PATTERN.matcher(s).region(i, to)
                if (m.lookingAt()) {
                    flushLiteral()
                    val tag = Tag(m.group(1).orEmpty() == "/", m.group(2).orEmpty(), m.group(3).orEmpty(), m.end())
                    i = handleTag(sb, s, tag, to, color); continue
                }
            }
            if (c == '\n') {
                literal.append('\n'); i++; continue
            }
            literal.append(c)
            i++
        }
        flushLiteral()
    }

    /** Renders one HTML tag (and, for paired tags, its content) and returns where scanning continues. */
    private class Tag(val closing: Boolean, val name: String, val attrs: String, val end: Int)

    private fun handleTag(sb: SpannableStringBuilder, s: String, tag: Tag, to: Int, color: Int): Int {
        val closing = tag.closing
        val name = tag.name.lowercase(Locale.ROOT)
        val attrs = tag.attrs
        val selfClosed = s[tag.end - 2] == '/'
        val after = tag.end
        if (closing) return after // stray closing tag
        when (name) {
            "br" -> { sb.append('\n'); return after }
            "hr" -> { sb.append('\n'); return after }
            "ha-icon" -> {
                val icon = attr(attrs, "icon")
                val end = if (selfClosed) after else findClose(s, name, after, to)?.let { it.last + 1 } ?: after
                if (!icon.isNullOrBlank()) {
                    val start = sb.length
                    sb.append('￼')
                    sb.setSpan(MdiIconSpan(icon, color, iconSizePx), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                return end
            }
            "img" -> {
                attr(attrs, "alt")?.let { sb.append(decodeEntities(it)) }
                return after
            }
        }
        if (selfClosed) return after
        val close = findClose(s, name, after, to) ?: return after // unclosed: drop the tag, keep the text
        val innerEnd = close.first
        val start = sb.length
        when (name) {
            "b", "strong" -> { renderInto(sb, s, after, innerEnd, color); span(sb, StyleSpan(Typeface.BOLD), start) }
            "i", "em" -> { renderInto(sb, s, after, innerEnd, color); span(sb, StyleSpan(Typeface.ITALIC), start) }
            "s", "del", "strike" -> { renderInto(sb, s, after, innerEnd, color); span(sb, StrikethroughSpan(), start) }
            "u", "ins" -> { renderInto(sb, s, after, innerEnd, color); span(sb, UnderlineSpan(), start) }
            "code" -> {
                sb.append(decodeEntities(s.substring(after, innerEnd)))
                span(sb, TypefaceSpan("monospace"), start)
                span(sb, BackgroundColorSpan(codeBackground), start)
            }
            "a" -> {
                renderInto(sb, s, after, innerEnd, linkColor)
                span(sb, ForegroundColorSpan(linkColor), start)
                span(sb, UnderlineSpan(), start)
                attr(attrs, "href")?.let { span(sb, URLSpanNoStyle(it), start) }
            }
            "font", "span" -> {
                val css = MdCss.parse(attr(attrs, "style"))
                val c = (if (name == "font") attr(attrs, "color") else null) ?: css["color"]
                styled(sb, s, after, innerEnd, color, c, css, block = false)
            }
            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                renderInto(sb, s, after, innerEnd, color)
                span(sb, StyleSpan(Typeface.BOLD), start)
                if (!sb.endsWith("\n")) sb.append('\n')
            }
            "p", "div", "li" -> {
                if (sb.isNotEmpty() && !sb.endsWith("\n")) sb.append('\n')
                val css = MdCss.parse(attr(attrs, "style"))
                if (css.isEmpty()) {
                    renderInto(sb, s, after, innerEnd, color)
                    if (!sb.endsWith("\n")) sb.append('\n')
                } else {
                    styled(sb, s, after, innerEnd, color, css["color"], css, block = true)
                }
            }
            else -> renderInto(sb, s, after, innerEnd, color)
        }
        return close.last + 1
    }

    /**
     * Renders a styled element's content with its color, opacity and text-transform in force, then lays the
     * typography spans over it. [block] elements (`p`, `div`) end their line and may carry `text-align`.
     */
    private fun styled(
        sb: SpannableStringBuilder, s: String, from: Int, to: Int, color: Int,
        colorValue: String?, css: Map<String, String>, block: Boolean,
    ) {
        val start = sb.length
        val parsed = colorValue?.let(::parseCssColor)?.let { scaleAlpha(it, alphaMul) }
        val opacity = css["opacity"]?.let(::parseOpacity)
        var effective = parsed ?: color
        if (opacity != null) effective = scaleAlpha(effective, opacity)
        val prevTransform = transform
        val prevAlpha = alphaMul
        css["text-transform"]?.trim()?.lowercase(Locale.ROOT)?.let { transform = if (it == "none") null else it }
        if (opacity != null) alphaMul *= opacity
        renderInto(sb, s, from, to, effective)
        transform = prevTransform
        alphaMul = prevAlpha
        if (parsed != null || opacity != null) span(sb, ForegroundColorSpan(effective), start)
        typography(sb, start, css)
        if (block) {
            if (!sb.endsWith("\n")) sb.append('\n')
            alignment(css["text-align"])?.let { span(sb, AlignmentSpan.Standard(it), start) }
        }
    }

    /** `font-size`, `font-weight` and `letter-spacing` as spans over `[start, end)`. */
    private fun typography(sb: SpannableStringBuilder, start: Int, css: Map<String, String>) {
        val size = css["font-size"]
        if (size != null) {
            val keyword = FONT_SIZE_KEYWORDS[size.trim().lowercase(Locale.ROOT)]
            val len = MdCss.length(size)
            if (keyword != null) {
                span(sb, RelativeSizeSpan(keyword), start)
            } else if (len != null && len.first > 0f) {
                if (len.second == "em" || len.second == "rem") {
                    span(sb, RelativeSizeSpan(len.first), start)
                } else if (len.second == "%") {
                    span(sb, RelativeSizeSpan(len.first / 100f), start)
                } else {
                    val dp = MdCss.lengthDp(size)
                    if (dp != null && dp > 0f) {
                        span(sb, AbsoluteSizeSpan((dp * density * fontScale).roundToInt().coerceAtLeast(1), false), start)
                    }
                }
            }
        }
        val w = MdCss.weight(css["font-weight"])
        if (w != null) {
            if (w >= 600) {
                span(sb, StyleSpan(Typeface.BOLD), start)
            } else if (w != 400) {
                val face = typefaceFor?.invoke(w)
                if (face != null) span(sb, FontWeightSpan(w, face), start)
            }
        }
        val ls = css["letter-spacing"]
        val lsLen = MdCss.length(ls)
        if (ls != null && lsLen != null) {
            if (lsLen.second == "em" || lsLen.second == "rem") {
                span(sb, LetterSpacingSpan(lsLen.first, null), start)
            } else if (lsLen.second != "%") {
                val dp = MdCss.lengthDp(ls)
                if (dp != null) span(sb, LetterSpacingSpan(null, dp * density * fontScale), start)
            }
        }
    }

    private class Link(val labelStart: Int, val labelEnd: Int, val url: String, val end: Int)

    private fun parseLink(s: String, open: Int, to: Int): Link? {
        var depth = 0
        var j = open
        while (j < to) {
            when (s[j]) {
                '[' -> depth++
                ']' -> { depth--; if (depth == 0) break }
            }
            j++
        }
        if (j >= to || j + 1 >= to || s[j + 1] != '(') return null
        val urlEnd = s.indexOf(')', j + 2).takeIf { it in 0 until to } ?: return null
        val url = s.substring(j + 2, urlEnd).trim().substringBefore(' ')
        return Link(open + 1, j, url, urlEnd + 1)
    }

    private fun findClosing(s: String, marker: String, from: Int, to: Int): Int? {
        var j = from
        while (j < to) {
            val k = s.indexOf(marker, j)
            if (k < 0 || k + marker.length > to) return null
            // A closing marker can't follow whitespace, and mustn't be the start of a longer run (`**` vs `*`).
            val longer = marker.length == 1 && k + 1 < to && s[k + 1] == marker[0]
            if (k > from && !s[k - 1].isWhitespace() && !longer) return k
            if (longer) { j = k + 2; continue }
            j = k + 1
        }
        return null
    }

    private fun boundaryBefore(s: String, i: Int) = i == 0 || !s[i - 1].isLetterOrDigit()
    private fun boundaryAfter(s: String, i: Int) = i >= s.length || !s[i].isLetterOrDigit()

    private fun findClose(s: String, name: String, from: Int, to: Int): IntRange? = findHtmlClose(s, name, from, to)

    /** A link span that keeps the color and underline set beside it instead of the theme's link style. */
    class URLSpanNoStyle(url: String) : URLSpan(url) {
        override fun updateDrawState(ds: android.text.TextPaint) {}
    }

    companion object {
        private const val ESCAPABLE = "\\`*_{}[]()#+-.!|~<>"
        private val TAG_PATTERN = java.util.regex.Pattern.compile("""<(/?)([a-zA-Z][a-zA-Z0-9-]*)((?:\s+[^>]*?)?)\s*/?>""")
        private val ATTR_CACHE = HashMap<String, Regex>()

        fun attr(attrs: String, key: String): String? {
            val re = synchronized(ATTR_CACHE) {
                ATTR_CACHE.getOrPut(key) { Regex("""(?:^|\s)$key\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>/]+))""", RegexOption.IGNORE_CASE) }
            }
            val m = re.find(attrs) ?: return null
            return m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: ""
        }

        private val FONT_SIZE_KEYWORDS = mapOf(
            "xx-small" to 0.6f, "x-small" to 0.75f, "small" to 0.89f, "smaller" to 0.83f, "medium" to 1f,
            "large" to 1.125f, "larger" to 1.2f, "x-large" to 1.5f, "xx-large" to 2f, "xxx-large" to 3f,
        )

        /** `text-align` → a [Layout.Alignment] (`center`, `right`/`end`, `left`/`start`/`justify`). */
        fun alignment(value: String?): Layout.Alignment? = when (value?.trim()?.lowercase(Locale.ROOT)) {
            "center" -> Layout.Alignment.ALIGN_CENTER
            "right", "end" -> Layout.Alignment.ALIGN_OPPOSITE
            "left", "start", "justify" -> Layout.Alignment.ALIGN_NORMAL
            else -> null
        }

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

        /** CSS `text-transform` on one run of text. `capitalize` uppercases the first letter of each word. */
        fun applyTransform(text: String, transform: String?): String = when (transform) {
            "uppercase" -> text.uppercase(Locale.getDefault())
            "lowercase" -> text.lowercase(Locale.getDefault())
            "capitalize" -> text.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(Locale.getDefault()) } }
            else -> text
        }

        fun styleColor(style: String): String? =
            Regex("""(?:^|;)\s*color\s*:\s*([^;]+)""", RegexOption.IGNORE_CASE).find(style)?.groupValues?.get(1)?.trim()

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
        )

        private val ENTITIES = mapOf(
            "&nbsp;" to " ", "&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&#39;" to "'",
            "&apos;" to "'", "&deg;" to "°", "&middot;" to "·", "&bull;" to "•", "&mdash;" to "—", "&ndash;" to "–",
            "&hellip;" to "…", "&times;" to "×", "&copy;" to "©", "&euro;" to "€",
        )
        private val NUMERIC_ENTITY = Regex("""&#(x?)([0-9a-fA-F]+);""")

        fun decodeEntities(s: String): String {
            if ('&' !in s) return s
            var out = s
            ENTITIES.forEach { (k, v) -> out = out.replace(k, v) }
            return NUMERIC_ENTITY.replace(out) { m ->
                val code = m.groupValues[2].toIntOrNull(if (m.groupValues[1].isEmpty()) 10 else 16)
                code?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) } ?: m.value
            }
        }
    }
}

/**
 * Renders HA markdown as native views into a vertical [LinearLayout]: sized headings, hairline rules, tables,
 * hanging-indent lists, accent-barred quotes and centered regions, each with rich inline spans.
 */
class MarkdownRenderer(private val ctx: VitroCardContext) {
    private val theme = ctx.theme
    private val inline = MarkdownInline(
        linkColor = theme.primaryColor,
        codeBackground = CardSurface.withAlpha(theme.primaryTextColor, 0.08f),
        iconSizePx = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_SP, BODY_SP * 1.15f, ctx.context.resources.displayMetrics,
        ).toInt(),
        density = ctx.dp,
        fontScale = theme.fontScale,
        typefaceFor = { w -> Inter.typeface(ctx.context, w, ctx.scaledSp(BODY_SP)) },
    )

    /** Clears [into] and fills it with [md] rendered as blocks. */
    fun render(md: String, into: LinearLayout) {
        into.removeAllViews()
        renderBlocks(parseMarkdownBlocks(md), into, centered = false)
    }

    private fun renderBlocks(blocks: List<MdBlock>, into: LinearLayout, centered: Boolean) {
        blocks.forEachIndexed { index, block ->
            val first = into.childCount == 0 && index == 0
            when (block) {
                is MdBlock.Heading -> {
                    val (sp, weight) = when (block.level) {
                        1 -> 22f to 700
                        2 -> 18f to SEMIBOLD
                        3 -> 16f to SEMIBOLD
                        else -> 15f to SEMIBOLD
                    }
                    into.addView(text(block.text, sp, weight, theme.primaryTextColor, centered), lp(top = if (first) 0f else 12f, bottom = 6f))
                }
                MdBlock.Rule -> into.addView(View(ctx.context).apply {
                    setBackgroundColor(theme.dividerColor)
                    tag = RULE_TAG
                }, LinearLayout.LayoutParams(MATCH_PARENT, ctx.px(1f).coerceAtLeast(1)).apply {
                    topMargin = ctx.px(8f); bottomMargin = ctx.px(8f)
                })
                is MdBlock.Paragraph -> into.addView(paragraph(block.text, centered), lp(bottom = 8f))
                is MdBlock.StackedBar -> {
                    val fixedBars = block.segments.all { it.widthDp != null }
                    into.addView(stackedBar(block), LinearLayout.LayoutParams(
                        if (fixedBars) WRAP_CONTENT else MATCH_PARENT,
                        ctx.px(block.heightDp).coerceAtLeast(1),
                    ).apply {
                        topMargin = ctx.px(if (first) 0f else 4f)
                        bottomMargin = ctx.px(8f)
                        if (fixedBars) {
                            gravity = when (if (centered && block.align == "start") "center" else block.align) {
                                "center" -> Gravity.CENTER_HORIZONTAL
                                "end" -> Gravity.END
                                else -> Gravity.START
                            }
                        }
                    })
                }
                is MdBlock.ListItem -> into.addView(listRow(block, centered), lp(bottom = 3f).apply {
                    leftMargin = ctx.px(LIST_INDENT_DP * block.depth)
                })
                is MdBlock.Quote -> into.addView(quote(block), lp(bottom = 8f))
                is MdBlock.Table -> into.addView(table(block), lp(bottom = 8f))
                is MdBlock.Image -> into.addView(image(block), LinearLayout.LayoutParams(
                    block.widthDp?.let { ctx.px(it) } ?: MATCH_PARENT,
                    block.heightDp?.let { ctx.px(it) } ?: WRAP_CONTENT,
                ).apply {
                    gravity = when (if (centered && block.align == "start") "center" else block.align) {
                        "center" -> Gravity.CENTER_HORIZONTAL
                        "end" -> Gravity.END
                        else -> Gravity.START
                    }
                    bottomMargin = ctx.px(8f)
                })
                is MdBlock.Center -> {
                    val inner = LinearLayout(ctx.context).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER_HORIZONTAL
                    }
                    renderBlocks(block.blocks, inner, centered = true)
                    into.addView(inner, lp())
                }
            }
        }
    }

    private fun lp(top: Float = 0f, bottom: Float = 0f) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
        topMargin = ctx.px(top); bottomMargin = ctx.px(bottom)
    }

    private fun text(md: String, sp: Float, weight: Int, color: Int, centered: Boolean): TextView = TextView(ctx.context).apply {
        val scaled = ctx.scaledSp(sp)
        textSize = scaled
        setTextColor(color)
        typeface = Inter.typeface(ctx.context, weight, scaled)
        includeFontPadding = false
        setLineSpacing(ctx.px(3f).toFloat(), 1f)
        text = inline.render(md, color)
        if (centered) {
            gravity = Gravity.CENTER_HORIZONTAL
            textAlignment = View.TEXT_ALIGNMENT_CENTER
        }
    }

    /**
     * A paragraph. When one `<p style>` / `<div style>` wraps all of it, its `text-align` sets the TextView's
     * gravity and its `letter-spacing` the TextView's letter spacing, as a block box does in HA's markdown card.
     */
    private fun paragraph(md: String, centered: Boolean): TextView {
        val tv = text(md, BODY_SP, Inter.REGULAR, theme.primaryTextColor, centered)
        val css = blockWrapperCss(md) ?: return tv
        when (css["text-align"]?.trim()?.lowercase(Locale.ROOT)) {
            "center" -> {
                tv.gravity = Gravity.CENTER_HORIZONTAL
                tv.textAlignment = View.TEXT_ALIGNMENT_CENTER
            }
            "right", "end" -> {
                tv.gravity = Gravity.END
                tv.textAlignment = View.TEXT_ALIGNMENT_VIEW_END
            }
            "left", "start" -> {
                tv.gravity = Gravity.START
                tv.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            }
        }
        val ls = css["letter-spacing"]
        val len = MdCss.length(ls)
        if (ls != null && len != null) {
            if (len.second == "em" || len.second == "rem") {
                tv.letterSpacing = len.first
            } else {
                val dp = MdCss.lengthDp(ls)
                if (dp != null && tv.textSize > 0f) tv.letterSpacing = dp * ctx.dp * theme.fontScale / tv.textSize
            }
        }
        return tv
    }

    /** A [MdBlock.StackedBar]: either weighted progress segments or fixed-width (optionally animated) equalizer bars. */
    private fun stackedBar(bar: MdBlock.StackedBar): LinearLayout = LinearLayout(ctx.context).apply {
        orientation = LinearLayout.HORIZONTAL
        tag = STACKED_BAR_TAG
        val fixedBars = bar.segments.all { it.widthDp != null }
        val totalH = ctx.px(bar.heightDp).coerceAtLeast(1)
        if (fixedBars) {
            gravity = Gravity.BOTTOM
        } else {
            weightSum = maxOf(100f, bar.segments.sumOf { it.pct.toDouble() }.toFloat())
            background = GradientDrawable().apply {
                cornerRadius = bar.radiusDp * ctx.dp
                setColor(bar.trackColor ?: android.graphics.Color.TRANSPARENT)
            }
            clipToOutline = true
        }
        val animators = ArrayList<android.animation.ValueAnimator>()
        bar.segments.forEachIndexed { i, seg ->
            val segView = View(ctx.context).apply {
                tag = STACKED_SEGMENT_TAG
                val col = seg.color ?: theme.primaryColor
                if (seg.radiusDp > 0f) {
                    background = GradientDrawable().apply {
                        cornerRadius = seg.radiusDp * ctx.dp
                        setColor(col)
                    }
                } else {
                    setBackgroundColor(col)
                }
            }
            val segH = if (seg.heightPct != null && !bar.animated) {
                (totalH * (seg.heightPct / 100f)).roundToInt().coerceAtLeast(1)
            } else {
                MATCH_PARENT
            }
            val lp = if (fixedBars) {
                LinearLayout.LayoutParams(ctx.px(seg.widthDp!!).coerceAtLeast(1), segH)
            } else {
                LinearLayout.LayoutParams(0, MATCH_PARENT, seg.pct)
            }.apply {
                if (i > 0 && bar.gapDp > 0f) marginStart = ctx.px(bar.gapDp)
            }
            addView(segView, lp)
            if (bar.animated) {
                segView.pivotY = totalH.toFloat()
                val minScale = 0.22f + (i % 2) * 0.08f
                val maxScale = 0.95f - (i % 3) * 0.1f
                animators += android.animation.ValueAnimator.ofFloat(minScale, maxScale).apply {
                    duration = 420L + (i % 3) * 130L
                    startDelay = (i * 120L) % 480L
                    repeatMode = android.animation.ValueAnimator.REVERSE
                    repeatCount = android.animation.ValueAnimator.INFINITE
                    addUpdateListener { a ->
                        segView.pivotY = segView.height.toFloat().coerceAtLeast(1f)
                        segView.scaleY = a.animatedValue as Float
                    }
                }
            }
        }
        if (animators.isNotEmpty()) {
            addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) { animators.forEach { it.start() } }
                override fun onViewDetachedFromWindow(v: View) { animators.forEach { it.cancel() } }
            })
        }
    }

    private fun listRow(item: MdBlock.ListItem, centered: Boolean): LinearLayout = LinearLayout(ctx.context).apply {
        orientation = LinearLayout.HORIZONTAL
        if (centered) gravity = Gravity.CENTER_HORIZONTAL
        val marker = TextView(ctx.context).apply {
            textSize = BODY_SP
            setTextColor(theme.secondaryTextColor)
            typeface = Inter.typeface(ctx.context, Inter.REGULAR, BODY_SP)
            includeFontPadding = false
            gravity = Gravity.END
            text = item.marker
        }
        addView(marker, LinearLayout.LayoutParams(ctx.px(if (item.ordered) 22f else 14f), WRAP_CONTENT).apply {
            marginEnd = ctx.px(8f)
        })
        addView(text(item.text, BODY_SP, Inter.REGULAR, theme.primaryTextColor, false),
            LinearLayout.LayoutParams(if (centered) WRAP_CONTENT else 0, WRAP_CONTENT, if (centered) 0f else 1f))
    }

    private fun quote(q: MdBlock.Quote): LinearLayout = LinearLayout(ctx.context).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(View(ctx.context).apply {
            setBackgroundColor(theme.primaryColor)
            tag = QUOTE_BAR_TAG
        }, LinearLayout.LayoutParams(ctx.px(3f).coerceAtLeast(1), MATCH_PARENT).apply { marginEnd = ctx.px(10f) })
        val body = LinearLayout(ctx.context).apply { orientation = LinearLayout.VERTICAL }
        // A quote can hold its own markdown (lists, headings); render it through the same path, then grey it.
        renderBlocks(parseMarkdownBlocks(q.text), body, centered = false)
        for (i in 0 until body.childCount) recolor(body.getChildAt(i))
        addView(body, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    }

    private fun recolor(v: View) {
        when (v) {
            is TextView -> v.setTextColor(theme.secondaryTextColor)
            is LinearLayout -> for (i in 0 until v.childCount) recolor(v.getChildAt(i))
        }
    }

    /** An [MdBlock.Image]: cropped to its box (rounded when it has a radius), loaded from HA in the background. */
    private fun image(img: MdBlock.Image): ImageView = ImageView(ctx.context).apply {
        tag = IMAGE_TAG
        val sized = img.widthDp != null && img.heightDp != null
        scaleType = if (sized) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
        adjustViewBounds = !sized
        if (img.radiusDp > 0f) {
            val r = img.radiusDp * ctx.dp
            background = GradientDrawable().apply { cornerRadius = r }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) =
                    outline.setRoundRect(0, 0, view.width, view.height, r)
            }
            clipToOutline = true
        }
        ctx.loadImage(img.src) { bmp -> if (bmp != null) setImageBitmap(bmp) }
    }

    private fun table(t: MdBlock.Table): TableLayout = TableLayout(ctx.context).apply {
        val hasHeader = t.header.any { it.isNotBlank() }
        if (hasHeader) {
            isStretchAllColumns = true
            isShrinkAllColumns = true
        } else {
            // Borderless key/value tables stretch the label column so right-aligned value columns hug the right edge.
            setColumnStretchable(0, true)
            setColumnShrinkable(0, true)
        }
        fun cell(md: String, header: Boolean, align: String, colIdx: Int) = TextView(ctx.context).apply {
            val color = theme.primaryTextColor
            val scaled = ctx.scaledSp(BODY_SP)
            textSize = scaled
            setTextColor(color)
            typeface = Inter.typeface(ctx.context, if (header) SEMIBOLD else Inter.REGULAR, scaled)
            includeFontPadding = false
            text = inline.render(md, color)
            if (hasHeader) {
                setPadding(ctx.px(8f), ctx.px(6f), ctx.px(8f), ctx.px(6f))
            } else {
                val startPad = if (colIdx == 0) 0 else ctx.px(12f)
                setPadding(startPad, ctx.px(8f), 0, ctx.px(8f))
            }
            gravity = when (align) {
                "center" -> Gravity.CENTER_HORIZONTAL
                "end" -> Gravity.END
                else -> Gravity.START
            }
        }
        fun hairline() = View(ctx.context).apply { setBackgroundColor(theme.dividerColor) }
        val hairlineLp = { TableLayout.LayoutParams(MATCH_PARENT, ctx.px(1f).coerceAtLeast(1)) }
        if (hasHeader) {
            addView(TableRow(ctx.context).apply {
                t.header.forEachIndexed { i, h -> addView(cell(h, true, t.aligns[i], i)) }
            })
            addView(hairline(), hairlineLp())
        }
        t.rows.forEachIndexed { r, row ->
            addView(TableRow(ctx.context).apply {
                row.forEachIndexed { i, c -> addView(cell(c, false, t.aligns.getOrElse(i) { "start" }, i)) }
            })
            if (hasHeader && r < t.rows.size - 1) addView(hairline(), hairlineLp())
        }
    }

    companion object {
        const val BODY_SP = 14f
        private const val SEMIBOLD = 600
        private const val LIST_INDENT_DP = 16f
        const val RULE_TAG = "md-rule"
        const val QUOTE_BAR_TAG = "md-quote-bar"
        const val STACKED_BAR_TAG = "md-stacked-bar"
        const val STACKED_SEGMENT_TAG = "md-stacked-segment"
        const val IMAGE_TAG = "md-image"
    }
}
