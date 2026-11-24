/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.cards

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import android.text.style.UnderlineSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
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
}

private val HEADING = Regex("""^(#{1,6})\s+(.*?)\s*#*\s*$""")
private val RULE = Regex("""^\s{0,3}(?:(?:\*\s*){3,}|(?:-\s*){3,}|(?:_\s*){3,})$""")
private val HTML_RULE = Regex("""^\s*<hr\s*/?>\s*$""", RegexOption.IGNORE_CASE)
private val BULLET = Regex("""^(\s*)[-*+]\s+(.*)$""")
private val ORDERED = Regex("""^(\s*)(\d{1,9})[.)]\s+(.*)$""")
private val QUOTE = Regex("""^\s{0,3}>\s?(.*)$""")
private val TABLE_SEPARATOR = Regex("""^\s*\|?\s*:?-{1,}:?\s*(\|\s*:?-{1,}:?\s*)*\|?\s*$""")
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
            if (joined.isNotEmpty()) out += MdBlock.Paragraph(joined)
            para.clear()
        }
    }
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()
        when {
            trimmed.isEmpty() -> flush()
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
 */
class MarkdownInline(
    private val linkColor: Int,
    private val codeBackground: Int,
    private val iconSizePx: Int,
) {
    fun render(text: String, baseColor: Int): SpannableStringBuilder {
        val sb = SpannableStringBuilder()
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
                sb.append(decodeEntities(literal.toString()))
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
                val c = (if (name == "font") attr(attrs, "color") else null)
                    ?: attr(attrs, "style")?.let { styleColor(it) }
                val parsed = c?.let(::parseCssColor)
                renderInto(sb, s, after, innerEnd, parsed ?: color)
                if (parsed != null) span(sb, ForegroundColorSpan(parsed), start)
                if (attr(attrs, "style")?.contains(Regex("""font-weight\s*:\s*(bold|[6-9]00)""")) == true) {
                    span(sb, StyleSpan(Typeface.BOLD), start)
                }
            }
            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                renderInto(sb, s, after, innerEnd, color)
                span(sb, StyleSpan(Typeface.BOLD), start)
                if (!sb.endsWith("\n")) sb.append('\n')
            }
            "p", "div", "li" -> {
                if (sb.isNotEmpty() && !sb.endsWith("\n")) sb.append('\n')
                renderInto(sb, s, after, innerEnd, color)
                if (!sb.endsWith("\n")) sb.append('\n')
            }
            else -> renderInto(sb, s, after, innerEnd, color)
        }
        return close.last + 1
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

    /** The `</name>` closing the tag opened just before [from], honoring nesting of the same tag. */
    private fun findClose(s: String, name: String, from: Int, to: Int): IntRange? {
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
                is MdBlock.Paragraph -> into.addView(text(block.text, BODY_SP, Inter.REGULAR, theme.primaryTextColor, centered), lp(bottom = 8f))
                is MdBlock.ListItem -> into.addView(listRow(block, centered), lp(bottom = 3f).apply {
                    leftMargin = ctx.px(LIST_INDENT_DP * block.depth)
                })
                is MdBlock.Quote -> into.addView(quote(block), lp(bottom = 8f))
                is MdBlock.Table -> into.addView(table(block), lp(bottom = 8f))
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

    private fun table(t: MdBlock.Table): TableLayout = TableLayout(ctx.context).apply {
        isStretchAllColumns = true
        isShrinkAllColumns = true
        fun cell(md: String, header: Boolean, align: String) = TextView(ctx.context).apply {
            val color = theme.primaryTextColor
            textSize = BODY_SP
            setTextColor(color)
            typeface = Inter.typeface(ctx.context, if (header) SEMIBOLD else Inter.REGULAR, BODY_SP)
            includeFontPadding = false
            text = inline.render(md, color)
            setPadding(ctx.px(8f), ctx.px(6f), ctx.px(8f), ctx.px(6f))
            gravity = when (align) {
                "center" -> Gravity.CENTER_HORIZONTAL
                "end" -> Gravity.END
                else -> Gravity.START
            }
        }
        fun hairline() = View(ctx.context).apply { setBackgroundColor(theme.dividerColor) }
        val hairlineLp = { TableLayout.LayoutParams(MATCH_PARENT, ctx.px(1f).coerceAtLeast(1)) }
        addView(TableRow(ctx.context).apply {
            t.header.forEachIndexed { i, h -> addView(cell(h, true, t.aligns[i])) }
        })
        addView(hairline(), hairlineLp())
        t.rows.forEachIndexed { r, row ->
            addView(TableRow(ctx.context).apply {
                row.forEachIndexed { i, c -> addView(cell(c, false, t.aligns.getOrElse(i) { "start" })) }
            })
            if (r < t.rows.size - 1) addView(hairline(), hairlineLp())
        }
    }

    companion object {
        const val BODY_SP = 14f
        private const val SEMIBOLD = 600
        private const val LIST_INDENT_DP = 16f
        const val RULE_TAG = "md-rule"
        const val QUOTE_BAR_TAG = "md-quote-bar"
    }
}
