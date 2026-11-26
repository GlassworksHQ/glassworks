/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.cards

import java.util.Locale

/**
 * One node of a sanitized Markdown document: an element, or a text node ([tag] == [TEXT]). The tree is what
 * Home Assistant's `ha-markdown-element` holds after `marked` and the `xss` sanitizer ran: the root is an
 * element named `ha-markdown-element`, so card-mod sheets written against HA's DOM match it.
 */
class MdNode(val tag: String, val attrs: Map<String, String> = emptyMap(), val text: String = "") {
    var parent: MdNode? = null
        private set
    val children = ArrayList<MdNode>(2)

    val isText: Boolean get() = tag == TEXT

    /** The computed style, once [MdCascade.apply] ran (a text node shares its parent's). */
    var style: MdStyle? = null

    fun add(child: MdNode): MdNode {
        child.parent = this
        children += child
        return child
    }

    /** Element children only (text nodes don't count for `:first-child` and friends). */
    fun elementChildren(): List<MdNode> = children.filter { !it.isText }

    /** All the text below this node, as the DOM's `textContent`. */
    fun textContent(): String = if (isText) text else buildString { children.forEach { append(it.textContent()) } }

    override fun toString(): String = if (isText) "\"$text\"" else "<$tag$attrs>$children"

    companion object {
        const val TEXT = "#text"
        const val ROOT = "ha-markdown-element"
    }
}

/**
 * Markdown to HTML the way Home Assistant's markdown worker does it: `marked` with GFM and `breaks: true`
 * (every newline inside a paragraph is a `<br>`), raw HTML blocks and inline tags passed through, pipe tables
 * wrapped in a `<div>`. The result goes through [MdHtml.parse], which applies HA's `xss` whitelist.
 */
object MarkedLite {
    /**
     * [breaks]: `marked`'s option, as `ha-markdown`'s `breaks` passes it (the markdown card sets it; HA's other uses,
     * an update's release notes say, leave it off). Off, a newline inside a paragraph is a space, and only a hard
     * break (two spaces or a backslash before it) is a `<br>`.
     */
    fun toHtml(markdown: String, breaks: Boolean = true): String {
        val before = hardBreaksOnly.get()
        hardBreaksOnly.set(!breaks)
        try {
            return blocksOf(markdown)
        } finally {
            hardBreaksOnly.set(before)
        }
    }

    /** The current [toHtml]'s `breaks` off, for the inline pass deep under it (any thread). */
    private val hardBreaksOnly = object : ThreadLocal<Boolean>() {
        override fun initialValue() = false
    }

    private fun blocksOf(markdown: String): String {
        val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ").split('\n')
        val out = StringBuilder()
        blocks(lines, out, tight = false)
        return out.toString()
    }

    private val ATX = Regex("""^ {0,3}(#{1,6})(?:[ \t]+(.*?))?(?:[ \t]+#+)?[ \t]*$""")
    private val HR = Regex("""^ {0,3}(?:(?:\*[ \t]*){3,}|(?:-[ \t]*){3,}|(?:_[ \t]*){3,})$""")
    private val SETEXT = Regex("""^ {0,3}(=+|-+)[ \t]*$""")
    private val ESCAPED_PUNCT = Regex("""\\([!-/:-@\[-`{-~])""")
    private val FENCE = Regex("""^( {0,3})(`{3,}|~{3,})(.*)$""")
    private val QUOTE = Regex("""^ {0,3}> ?(.*)$""")
    private val BULLET = Regex("""^( {0,3})([-*+])( +|$)(.*)$""")
    private val ORDERED = Regex("""^( {0,3})(\d{1,9})([.)])( +|$)(.*)$""")
    private val TABLE_DELIM = Regex("""^ {0,3}\|?[ \t]*:?-+:?[ \t]*(?:\|[ \t]*:?-+:?[ \t]*)*\|?[ \t]*$""")
    private val HTML_TYPE1 = Regex("""^ {0,3}<(script|pre|style|textarea)(?:[\s>]|$)""", RegexOption.IGNORE_CASE)
    private val HTML_TYPE6 = Regex("""^ {0,3}</?([a-zA-Z][a-zA-Z0-9-]*)(?:[\s/>]|$)""")
    private val HTML_TYPE7 = Regex(
        """^ {0,3}(?:<[a-zA-Z][a-zA-Z0-9-]*(?:\s+[a-zA-Z_:][a-zA-Z0-9_.:-]*(?:\s*=\s*(?:[^\s"'=<>`]+|'[^']*'|"[^"]*"))?)*\s*/?>|</[a-zA-Z][a-zA-Z0-9-]*\s*>)[ \t]*$""",
    )
    private val BLOCK_TAGS = setOf(
        "address", "article", "aside", "base", "basefont", "blockquote", "body", "caption", "center", "col",
        "colgroup", "dd", "details", "dialog", "dir", "div", "dl", "dt", "fieldset", "figcaption", "figure",
        "footer", "form", "frame", "frameset", "h1", "h2", "h3", "h4", "h5", "h6", "head", "header", "hr", "html",
        "iframe", "legend", "li", "link", "main", "menu", "menuitem", "nav", "noframes", "ol", "optgroup", "option",
        "p", "param", "search", "section", "summary", "table", "tbody", "td", "tfoot", "th", "thead", "title", "tr",
        "track", "ul",
    )

    private fun isBlank(s: String) = s.isBlank()

    /** The kind of HTML block [line] opens (1, 2 or 6, 7 when [canBe7]), or 0. */
    private fun htmlBlockKind(line: String, canBe7: Boolean): Int {
        val t = line.trimStart()
        if (!t.startsWith("<")) return 0
        if (HTML_TYPE1.containsMatchIn(line)) return 1
        if (t.startsWith("<!--")) return 2
        HTML_TYPE6.find(line)?.let { m -> if (m.groupValues[1].lowercase(Locale.ROOT) in BLOCK_TAGS) return 6 }
        if (canBe7 && HTML_TYPE7.matches(line)) {
            val name = Regex("""</?([a-zA-Z][a-zA-Z0-9-]*)""").find(t)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
            if (name !in setOf("script", "style", "pre")) return 7
        }
        return 0
    }

    /** True when [line] ends the paragraph above it (CommonMark's interrupting blocks). */
    /** How many leading characters make [line] an indented code line (four spaces, or a tab), or null. */
    private fun codeIndent(line: String): Int? = when {
        line.startsWith("    ") -> 4
        line.startsWith("\t") -> 1
        else -> null
    }

    private fun interruptsParagraph(line: String): Boolean =
        ATX.matches(line) || HR.matches(line) || FENCE.matches(line) || QUOTE.matches(line) ||
            htmlBlockKind(line, canBe7 = false) != 0 ||
            BULLET.find(line)?.let { it.groupValues[4].isNotBlank() } == true ||
            ORDERED.find(line)?.let { it.groupValues[2] == "1" && it.groupValues[5].isNotBlank() } == true

    private fun blocks(lines: List<String>, out: StringBuilder, tight: Boolean) {
        var i = 0
        val n = lines.size
        while (i < n) {
            val line = lines[i]
            if (isBlank(line)) { i++; continue }

            // An indented code block (four spaces or a tab): literal text, as marked reads it. It can't interrupt a
            // paragraph, so it's only checked where a block starts.
            if (codeIndent(line) != null) {
                val body = ArrayList<String>()
                var j = i
                while (j < n && (isBlank(lines[j]) || codeIndent(lines[j]) != null)) {
                    body += if (isBlank(lines[j])) "" else lines[j].substring(codeIndent(lines[j])!!)
                    j++
                }
                while (body.isNotEmpty() && body.last().isEmpty()) body.removeAt(body.lastIndex)
                out.append("<pre><code>").append(escapeText(body.joinToString("\n") + "\n")).append("</code></pre>\n")
                i = j
                continue
            }

            val fence = FENCE.find(line)
            if (fence != null && (fence.groupValues[2][0] == '~' || '`' !in fence.groupValues[3])) {
                val marker = fence.groupValues[2]
                // Each content line loses up to the opening fence's own indentation; more than that is kept.
                val fenceIndent = fence.groupValues[1].length
                val body = StringBuilder()
                var j = i + 1
                while (j < n) {
                    val t = lines[j].trim()
                    // A closing fence is indented three spaces at most; four makes it code.
                    val indent = lines[j].length - lines[j].trimStart().length
                    if (indent <= 3 && t.length >= marker.length && t.all { it == marker[0] }) break
                    val lead = lines[j].takeWhile { it == ' ' }.length.coerceAtMost(fenceIndent)
                    body.append(lines[j], lead, lines[j].length).append('\n')
                    j++
                }
                out.append("<pre><code>").append(escapeText(body.toString())).append("</code></pre>\n")
                i = j + 1
                continue
            }

            ATX.find(line)?.let { m ->
                val level = m.groupValues[1].length
                out.append("<h").append(level).append('>').append(inline(m.groupValues[2].trim()))
                    .append("</h").append(level).append(">\n")
                i++
            }?.let { continue }

            if (HR.matches(line)) { out.append("<hr>\n"); i++; continue }

            val html = htmlBlockKind(line, canBe7 = true)
            if (html != 0) {
                var j = i
                val end: (String) -> Boolean = when (html) {
                    1 -> { l -> Regex("""</(script|pre|style|textarea)>""", RegexOption.IGNORE_CASE).containsMatchIn(l) }
                    2 -> { l -> "-->" in l }
                    else -> { _ -> false }
                }
                val buf = StringBuilder()
                while (j < n) {
                    if ((html == 6 || html == 7) && isBlank(lines[j])) break
                    buf.append(lines[j]).append('\n')
                    if (end(lines[j])) { j++; break }
                    j++
                }
                out.append(buf)
                i = j
                continue
            }

            if (QUOTE.matches(line)) {
                val inner = ArrayList<String>()
                var j = i
                while (j < n) {
                    val q = QUOTE.find(lines[j])
                    if (q != null) {
                        inner += q.groupValues[1]
                    } else if (!isBlank(lines[j]) && inner.isNotEmpty() && !isBlank(inner.last()) && !interruptsParagraph(lines[j])) {
                        inner += lines[j] // lazy continuation
                    } else {
                        break
                    }
                    j++
                }
                out.append("<blockquote>\n")
                blocks(inner, out, tight = false)
                out.append("</blockquote>\n")
                i = j
                continue
            }

            val bullet = BULLET.find(line)
            val ordered = if (bullet == null) ORDERED.find(line) else null
            if (bullet != null || ordered != null) {
                i = list(lines, i, out)
                continue
            }

            // A GFM pipe table: a header row, then a delimiter row with as many cells.
            if ('|' in line && i + 1 < n && TABLE_DELIM.matches(lines[i + 1])) {
                val header = splitRow(line)
                val delim = splitRow(lines[i + 1])
                if (header.size == delim.size) {
                    val aligns = delim.map { c ->
                        val l = c.startsWith(":")
                        val r = c.endsWith(":")
                        when {
                            l && r -> "center"
                            r -> "right"
                            l -> "left"
                            else -> null
                        }
                    }
                    fun cell(tag: String, idx: Int, md: String) =
                        "<$tag${aligns.getOrNull(idx)?.let { " align=\"$it\"" } ?: ""}>${inline(md)}</$tag>"
                    out.append("<div><table>\n<thead>\n<tr>")
                    header.forEachIndexed { idx, h -> out.append(cell("th", idx, h)) }
                    out.append("</tr>\n</thead>\n")
                    var j = i + 2
                    var body = false
                    while (j < n && !isBlank(lines[j]) && !interruptsParagraph(lines[j])) {
                        if (!body) { out.append("<tbody>"); body = true }
                        val cells = splitRow(lines[j])
                        out.append("<tr>")
                        for (idx in header.indices) out.append(cell("td", idx, cells.getOrElse(idx) { "" }))
                        out.append("</tr>\n")
                        j++
                    }
                    if (body) out.append("</tbody>")
                    out.append("</table>\n</div>")
                    i = j
                    continue
                }
            }

            // A paragraph: lines until a blank line or a block that may interrupt it.
            val para = ArrayList<String>()
            var j = i
            var setext = 0
            while (j < n && !isBlank(lines[j])) {
                if (para.isNotEmpty()) {
                    val s = SETEXT.find(lines[j])
                    if (s != null) { setext = if (s.groupValues[1][0] == '=') 1 else 2; j++; break }
                    if (interruptsParagraph(lines[j])) break
                    if ('|' in lines[j] && j + 1 < n && TABLE_DELIM.matches(lines[j + 1])) break
                }
                // Leading spaces go; trailing ones stay for the inline pass (two before a newline are a hard break).
                para += lines[j].trimStart()
                j++
            }
            val text = para.joinToString("\n").trimEnd()
            when {
                setext != 0 -> out.append("<h$setext>").append(inline(text)).append("</h$setext>\n")
                tight -> out.append(inline(text)).append('\n')
                else -> out.append("<p>").append(inline(text)).append("</p>\n")
            }
            i = j
        }
    }

    /** One list starting at [start]; returns the line after it. */
    private fun list(lines: List<String>, start: Int, out: StringBuilder): Int {
        val first = BULLET.find(lines[start])
        val ordered = first == null
        val firstMatch = first ?: ORDERED.find(lines[start])!!
        val marker = if (ordered) firstMatch.groupValues[3] else firstMatch.groupValues[2]
        val startNo = if (ordered) firstMatch.groupValues[2].toIntOrNull() ?: 1 else 1
        val items = ArrayList<List<String>>()
        var loose = false
        var i = start
        val n = lines.size
        while (i < n) {
            val m = if (ordered) ORDERED.find(lines[i])?.takeIf { it.groupValues[3] == marker }
            else BULLET.find(lines[i])?.takeIf { it.groupValues[2] == marker }
            if (m == null) break
            val indent = m.groupValues[1].length
            val markerLen = if (ordered) m.groupValues[2].length + 1 else 1
            val spaces = m.groupValues[if (ordered) 4 else 3].length.coerceIn(1, 4)
            val contentIndent = indent + markerLen + spaces
            val item = ArrayList<String>()
            item += m.groupValues[if (ordered) 5 else 4]
            i++
            var sawBlank = false
            while (i < n) {
                val l = lines[i]
                if (isBlank(l)) { sawBlank = true; item += ""; i++; continue }
                val lead = l.length - l.trimStart().length
                if (lead >= contentIndent) {
                    if (sawBlank) loose = true
                    item += l.substring(contentIndent)
                    sawBlank = false
                    i++
                    continue
                }
                if (!sawBlank && !interruptsParagraph(l) && BULLET.find(l) == null && ORDERED.find(l) == null) {
                    item += l.trimStart() // lazy continuation
                    i++
                    continue
                }
                break
            }
            // Blank lines at the end of an item belong between items: they make the list loose if more follow.
            while (item.isNotEmpty() && item.last().isEmpty()) item.removeAt(item.size - 1)
            if (sawBlank && i < n) {
                val next = if (ordered) ORDERED.find(lines[i]) else BULLET.find(lines[i])
                if (next != null) loose = true
            }
            items += item
            if (sawBlank && (i >= n || (if (ordered) ORDERED.find(lines[i]) else BULLET.find(lines[i])) == null)) break
        }
        val tag = if (ordered) "ol" else "ul"
        out.append('<').append(tag)
        if (ordered && startNo != 1) out.append(" start=\"").append(startNo).append('"')
        out.append(">\n")
        for (item in items) {
            out.append("<li>")
            blocks(item, out, tight = !loose)
            out.append("</li>\n")
        }
        out.append("</").append(tag).append(">\n")
        return i
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
            if (c == '\\' && i + 1 < s.length && s[i + 1] == '|') { cur.append('|'); i += 2; continue }
            if (c == '|') { cells += cur.toString().trim(); cur.setLength(0) } else cur.append(c)
            i++
        }
        cells += cur.toString().trim()
        return cells
    }

    private const val PUNCT = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"
    private val INLINE_TAG = Regex(
        """<[a-zA-Z][a-zA-Z0-9-]*(?:\s+[a-zA-Z_:][a-zA-Z0-9_.:-]*(?:\s*=\s*(?:[^\s"'=<>`]+|'[^']*'|"[^"]*"))?)*\s*/?>|</[a-zA-Z][a-zA-Z0-9-]*\s*>|<!--[\s\S]*?-->""",
    )
    private val AUTOLINK = Regex("""<((?:https?|mailto|ftp):[^\s<>]*)>""", RegexOption.IGNORE_CASE)
    private val ENTITY = Regex("""&(?:#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z][a-zA-Z0-9]{1,31});""")
    private val BARE_URL = Regex("""(?:https?://|www\.)[^\s<]*[^\s<?!.,:*_~)]""")

    /** Inline Markdown to HTML: code spans, emphasis, links, images, raw tags and entities; newlines become `<br>`. */
    fun inline(src: String): String {
        val sb = StringBuilder()
        inlineInto(src, 0, src.length, sb)
        return sb.toString()
    }

    private fun inlineInto(s: String, from: Int, to: Int, sb: StringBuilder) {
        var i = from
        while (i < to) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < to && s[i + 1] in PUNCT -> { sb.append(escapeText(s[i + 1].toString())); i += 2 }
                c == '\\' && i + 1 < to && s[i + 1] == '\n' -> { sb.append("<br>"); i += 2 }
                c == '\n' -> {
                    // `breaks: true`: every newline is a hard break; trailing spaces before it go. Without it, only
                    // two or more trailing spaces make one; else the newline stays, a space once laid out.
                    var spaces = 0
                    while (sb.endsWith(" ")) { sb.setLength(sb.length - 1); spaces++ }
                    sb.append(if (!hardBreaksOnly.get() || spaces >= 2) "<br>" else "\n")
                    i++
                    while (i < to && s[i] == ' ') i++
                }
                c == '`' -> {
                    var run = 0
                    while (i + run < to && s[i + run] == '`') run++
                    val ticks = "`".repeat(run)
                    // The closer is a whole run of exactly as many backticks: a longer run is skipped as a whole.
                    var close = -1
                    var k = i + run
                    while (k < to) {
                        val p = s.indexOf('`', k)
                        if (p < 0 || p >= to) break
                        var len = 0
                        while (p + len < to && s[p + len] == '`') len++
                        if (len == run) { close = p; break }
                        k = p + len
                    }
                    if (close in 0 until to) {
                        var code = s.substring(i + run, close).replace('\n', ' ')
                        if (code.length > 2 && code.startsWith(" ") && code.endsWith(" ") && code.isNotBlank()) code = code.substring(1, code.length - 1)
                        sb.append("<code>").append(escapeText(code)).append("</code>")
                        i = close + run
                    } else {
                        sb.append(ticks); i += run
                    }
                }
                c == '<' -> {
                    val auto = AUTOLINK.matchAt(s, i)
                    val tag = if (auto == null) INLINE_TAG.matchAt(s, i) else null
                    when {
                        auto != null && auto.range.last < to -> {
                            val url = auto.groupValues[1]
                            sb.append("<a href=\"").append(escapeAttr(url)).append("\">").append(escapeText(url)).append("</a>")
                            i = auto.range.last + 1
                        }
                        tag != null && tag.range.last < to -> { sb.append(tag.value); i = tag.range.last + 1 }
                        else -> { sb.append("&lt;"); i++ }
                    }
                }
                c == '&' -> {
                    val e = ENTITY.matchAt(s, i)
                    if (e != null && e.range.last < to) { sb.append(e.value); i = e.range.last + 1 } else { sb.append("&amp;"); i++ }
                }
                c == '!' && i + 1 < to && s[i + 1] == '[' -> {
                    val link = link(s, i + 1, to)
                    if (link != null) {
                        sb.append("<img src=\"").append(escapeAttr(link.url)).append("\" alt=\"")
                            .append(escapeAttr(s.substring(link.labelStart, link.labelEnd))).append("\">")
                        i = link.end
                    } else {
                        sb.append('!'); i++
                    }
                }
                c == '[' -> {
                    val link = link(s, i, to)
                    if (link != null) {
                        sb.append("<a href=\"").append(escapeAttr(link.url)).append("\">")
                        inlineInto(s, link.labelStart, link.labelEnd, sb)
                        sb.append("</a>")
                        i = link.end
                    } else {
                        sb.append('['); i++
                    }
                }
                (c == '*' || c == '_' || c == '~') -> {
                    val used = emphasis(s, i, to, sb)
                    if (used > 0) i += used else { sb.append(c); i++ }
                }
                (c == 'h' || c == 'w') && (i == from || !s[i - 1].isLetterOrDigit()) -> {
                    val m = BARE_URL.matchAt(s, i)
                    if (m != null && m.range.last < to) {
                        val href = if (m.value.startsWith("www.")) "http://${m.value}" else m.value
                        sb.append("<a href=\"").append(escapeAttr(href)).append("\">").append(escapeText(m.value)).append("</a>")
                        i = m.range.last + 1
                    } else {
                        sb.append(c); i++
                    }
                }
                c == '>' -> { sb.append("&gt;"); i++ }
                c == '"' -> { sb.append("&quot;"); i++ }
                else -> { sb.append(c); i++ }
            }
        }
    }

    /** Emphasis at [i] (`**`, `__`, `*`, `_`, `~~`, `~`); appends it and returns the characters used, or 0. */
    private fun emphasis(s: String, i: Int, to: Int, sb: StringBuilder): Int {
        val c = s[i]
        var run = 0
        while (i + run < to && s[i + run] == c) run++
        val len = when {
            c == '~' -> if (run <= 2) run else return 0
            run >= 2 -> 2
            else -> 1
        }
        val after = s.getOrNull(i + len)
        if (after == null || after.isWhitespace()) return 0
        // `_` only opens at a word boundary (snake_case stays literal).
        if (c == '_' && i > 0 && s[i - 1].isLetterOrDigit()) return 0
        val marker = c.toString().repeat(len)
        var j = i + len
        while (j < to) {
            val k = s.indexOf(marker, j)
            if (k < 0 || k + len > to) return 0
            val longer = k + len < to && s[k + len] == c
            val prevOk = !s[k - 1].isWhitespace()
            val closeOk = c != '_' || k + len >= s.length || !s[k + len].isLetterOrDigit()
            if (k > i + len - 1 && prevOk && closeOk && !(longer && len == 1)) {
                val tag = when {
                    c == '~' -> "del"
                    len == 2 -> "strong"
                    else -> "em"
                }
                sb.append('<').append(tag).append('>')
                inlineInto(s, i + len, k, sb)
                sb.append("</").append(tag).append('>')
                return k + len - i
            }
            j = k + (if (longer) 2 else 1)
        }
        return 0
    }

    private class Link(val labelStart: Int, val labelEnd: Int, val url: String, val end: Int)

    private fun link(s: String, open: Int, to: Int): Link? {
        var depth = 0
        var j = open
        while (j < to) {
            when (s[j]) {
                '\\' -> j++
                '[' -> depth++
                ']' -> { depth--; if (depth == 0) break }
            }
            j++
        }
        if (j >= to || j + 1 >= to || s[j + 1] != '(') return null
        // The destination's own balanced (or escaped) parentheses stay in it: `/local/plan(v2).png`.
        var close = -1
        var parens = 0
        var k = j + 2
        while (k < to && (s[k] == ' ' || s[k] == '\t')) k++
        if (k < to && s[k] == '<') {
            // `<…>` destination: anything up to `>` is the URL (parentheses too), then the `)` that ends the link.
            val gt = s.indexOf('>', k).takeIf { it in 0 until to } ?: return null
            k = gt + 1
            parens = -1 // no balancing after the `<…>`: the next `)` closes
        }
        while (k < to) {
            // A quoted title after the destination (`"Floor (draft"`): its parentheses don't count.
            if ((s[k] == '"' || s[k] == '\'') && k > 0 && (s[k - 1] == ' ' || s[k - 1] == '\t')) {
                // The closing quote, past any escaped one (`\"`).
                var end = k + 1
                while (end < to && s[end] != s[k]) end += if (s[end] == '\\') 2 else 1
                if (end in 0 until to) {
                    k = end + 1
                    continue
                }
            }
            when (s[k]) {
                '\\' -> k++
                '(' -> if (parens >= 0) parens++
                ')' -> if (parens <= 0) { close = k; break } else parens--
            }
            k++
        }
        if (close < 0) return null
        val dest = s.substring(j + 2, close).trim()
        val raw = if (dest.startsWith("<") && ">" in dest) dest.substring(1, dest.indexOf('>')) else dest.substringBefore(' ')
        // Backslash escapes of punctuation come off (`plan\(v2\).png` is `plan(v2).png`), as marked does.
        val url = ESCAPED_PUNCT.replace(raw, "$1")
        return Link(open + 1, j, url, close + 1)
    }

    fun escapeText(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun escapeAttr(s: String): String = escapeText(s).replace("\"", "&quot;")
}

/**
 * A tolerant HTML parser with Home Assistant's sanitizer rules built in. HA runs every Markdown block through
 * `xss` with its default whitelist plus a few HA tags (`resources/markdown-worker.ts`): tags outside it are
 * escaped (shown as text), and every attribute outside the tag's own list is dropped. So `style`, `class` and
 * `id` never reach the page, which is why a Markdown card is styled through card-mod sheets instead.
 */
object MdHtml {
    /** The `xss` default whitelist plus HA's additions: tag to the attributes it keeps. */
    val WHITELIST: Map<String, Set<String>> = buildMap {
        val none = emptySet<String>()
        listOf(
            "abbr", "address", "article", "aside", "b", "big", "br", "caption", "center", "cite", "code", "dd",
            "div", "dl", "dt", "em", "figcaption", "figure", "footer", "h1", "h2", "h3", "h4", "h5", "h6", "header",
            "hr", "i", "kbd", "li", "mark", "nav", "ol", "p", "pre", "s", "section", "small", "span", "sub",
            "summary", "sup", "strong", "strike", "tt", "u", "ul", "picture",
        ).forEach { put(it, none) }
        put("abbr", setOf("title"))
        put("a", setOf("target", "href", "title"))
        put("area", setOf("shape", "coords", "href", "alt"))
        put("audio", setOf("autoplay", "controls", "crossorigin", "loop", "muted", "preload", "src"))
        put("bdi", setOf("dir"))
        put("bdo", setOf("dir"))
        put("blockquote", setOf("cite"))
        put("col", setOf("align", "valign", "span", "width"))
        put("colgroup", setOf("align", "valign", "span", "width"))
        put("del", setOf("datetime"))
        put("details", setOf("open"))
        put("font", setOf("color", "size", "face"))
        put("img", setOf("src", "alt", "title", "width", "height", "loading"))
        put("ins", setOf("datetime"))
        put("table", setOf("width", "border", "align", "valign", "role"))
        put("tbody", setOf("align", "valign"))
        put("tfoot", setOf("align", "valign"))
        put("thead", setOf("align", "valign"))
        put("td", setOf("width", "rowspan", "colspan", "align", "valign"))
        put("th", setOf("width", "rowspan", "colspan", "align", "valign"))
        put("tr", setOf("rowspan", "align", "valign"))
        put("video", setOf("autoplay", "controls", "crossorigin", "loop", "muted", "playsinline", "poster", "preload", "src", "height", "width"))
        put("input", setOf("type", "disabled", "checked"))
        put("source", setOf("src", "srcset", "sizes", "media", "type"))
        put("ha-icon", setOf("icon"))
        put("ha-svg-icon", setOf("path"))
        put("ha-alert", setOf("alert-type", "title"))
        put("ha-qr-code", setOf("data", "scale", "width", "margin", "error-correction-level", "center-image"))
    }

    val VOID = setOf("br", "hr", "img", "input", "source", "col", "wbr", "area")

    /** Opening one of these closes an open `<p>`, as the HTML parser does. */
    private val CLOSES_P = setOf(
        "address", "article", "aside", "blockquote", "center", "details", "div", "dl", "fieldset", "figcaption",
        "figure", "footer", "h1", "h2", "h3", "h4", "h5", "h6", "header", "hr", "nav", "ol", "p", "pre",
        "section", "table", "ul", "summary",
    )
    private val TABLE_SECTIONS = setOf("thead", "tbody", "tfoot")

    private val TAG = Regex(
        """<(/?)([a-zA-Z][a-zA-Z0-9-]*)((?:\s+[^\s"'>/=]+(?:\s*=\s*(?:"[^"]*"|'[^']*'|[^\s"'=<>`]+))?)*)\s*(/?)>""",
    )
    private val ATTR = Regex("""([^\s"'>/=]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>`]+)))?""")
    private val COMMENT = Regex("""<!--[\s\S]*?(?:-->|$)""")

    /** Markdown (already template-rendered) to a sanitized DOM rooted at `ha-markdown-element`. */
    fun fromMarkdown(markdown: String, breaks: Boolean = true): MdNode = parse(MarkedLite.toHtml(markdown, breaks))

    /** Parses [html] into a sanitized DOM rooted at `ha-markdown-element`. */
    fun parse(html: String): MdNode {
        val root = MdNode(MdNode.ROOT)
        val stack = ArrayList<MdNode>().apply { add(root) }
        val cur = { stack.last() }
        val text = StringBuilder()
        fun flushText() {
            if (text.isEmpty()) return
            cur().add(MdNode(MdNode.TEXT, text = decodeEntities(text.toString())))
            text.setLength(0)
        }
        fun open(name: String): Int = stack.indexOfLast { it.tag == name }
        fun popTo(index: Int) { while (stack.size > index && stack.size > 1) stack.removeAt(stack.size - 1) }
        /** Index of the nearest open [name] that isn't hidden behind a table boundary. */
        fun inScope(name: String, boundary: Set<String> = setOf("table", "td", "th")): Int {
            for (k in stack.indices.reversed()) {
                val t = stack[k].tag
                if (t == name) return k
                if (t in boundary) return -1
            }
            return -1
        }

        var i = 0
        while (i < html.length) {
            val c = html[i]
            if (c != '<') { text.append(c); i++; continue }
            if (html.startsWith("<!--", i)) {
                val m = COMMENT.matchAt(html, i)!!
                flushText(); i = m.range.last + 1; continue
            }
            val m = TAG.matchAt(html, i)
            if (m == null) { text.append(c); i++; continue }
            val closing = m.groupValues[1] == "/"
            val name = m.groupValues[2].lowercase(Locale.ROOT)
            val allowed = WHITELIST[name]
            if (allowed == null) {
                // xss escapes a tag outside the whitelist: the browser shows it as text.
                text.append(m.value.replace("&", "&amp;"))
                i = m.range.last + 1
                continue
            }
            flushText()
            i = m.range.last + 1
            if (closing) {
                when (name) {
                    "br" -> cur().add(MdNode("br"))
                    "p" -> {
                        val k = inScope("p")
                        if (k >= 0) popTo(k) else cur().add(MdNode("p"))
                    }
                    else -> {
                        val boundary = when (name) {
                            "table" -> emptySet()
                            "td", "th", "tr", in TABLE_SECTIONS -> setOf("table")
                            else -> setOf("table", "td", "th")
                        }
                        val k = inScope(name, boundary)
                        if (k >= 1) popTo(k)
                    }
                }
                continue
            }
            val attrs = LinkedHashMap<String, String>()
            ATTR.findAll(m.groupValues[3]).forEach { a ->
                val key = a.groupValues[1].lowercase(Locale.ROOT)
                if (key in allowed && key !in attrs) {
                    val v = a.groupValues[2].ifEmpty { a.groupValues[3].ifEmpty { a.groupValues[4] } }
                    attrs[key] = decodeEntities(v)
                }
            }
            // Implied end tags and table structure, as the HTML parser builds them.
            if (name in CLOSES_P) inScope("p").takeIf { it >= 0 }?.let { popTo(it) }
            when (name) {
                "li" -> inScope("li", setOf("ul", "ol", "table")).takeIf { it >= 0 }?.let { popTo(it) }
                "dt", "dd" -> (inScope("dt", setOf("dl")).takeIf { it >= 0 } ?: inScope("dd", setOf("dl")).takeIf { it >= 0 })?.let { popTo(it) }
                "td", "th" -> {
                    val cell = maxOf(inScope("td", setOf("table", "tr")), inScope("th", setOf("table", "tr")))
                    if (cell >= 0) popTo(cell)
                    if (cur().tag != "tr") {
                        if (cur().tag == "table") stack += cur().add(MdNode("tbody"))
                        if (cur().tag in TABLE_SECTIONS) stack += cur().add(MdNode("tr"))
                    }
                }
                "tr" -> {
                    val row = inScope("tr", setOf("table"))
                    if (row >= 0) popTo(row)
                    if (cur().tag == "table") stack += cur().add(MdNode("tbody"))
                }
                in TABLE_SECTIONS -> {
                    val t = inScope("table", emptySet())
                    if (t >= 0) popTo(t + 1)
                }
            }
            val node = cur().add(MdNode(name, attrs))
            // HTML ignores the `/` of `<ha-icon .../>`: only void elements close themselves.
            if (name !in VOID) stack += node
        }
        flushText()
        return root
    }

    private val NAMED_ENTITIES = mapOf(
        "nbsp" to " ", "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "deg" to "°",
        "middot" to "·", "bull" to "•", "mdash" to "—", "ndash" to "–", "hellip" to "…", "times" to "×",
        "copy" to "©", "reg" to "®", "trade" to "™", "euro" to "€", "pound" to "£", "yen" to "¥", "cent" to "¢",
        "rarr" to "→", "larr" to "←", "uarr" to "↑", "darr" to "↓", "harr" to "↔", "laquo" to "«", "raquo" to "»",
        "ensp" to " ", "emsp" to " ", "thinsp" to " ", "plusmn" to "±", "frac12" to "½",
        "frac14" to "¼", "frac34" to "¾", "sup2" to "²", "sup3" to "³", "micro" to "µ", "para" to "¶",
        "sect" to "§", "divide" to "÷", "minus" to "−", "le" to "≤", "ge" to "≥", "ne" to "≠", "infin" to "∞",
        "hearts" to "♥", "check" to "✓", "starf" to "★", "star" to "☆",
    )
    private val ENTITY = Regex("""&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z][a-zA-Z0-9]{1,31});""")

    fun decodeEntities(s: String): String {
        if ('&' !in s) return s
        return ENTITY.replace(s) { m ->
            val body = m.groupValues[1]
            if (body.startsWith("#")) {
                val code = if (body.length > 1 && (body[1] == 'x' || body[1] == 'X')) body.substring(2).toIntOrNull(16)
                else body.substring(1).toIntOrNull()
                code?.takeIf { Character.isValidCodePoint(it) && it != 0 }?.let { String(Character.toChars(it)) } ?: m.value
            } else {
                NAMED_ENTITIES[body] ?: NAMED_ENTITIES[body.lowercase(Locale.ROOT)] ?: m.value
            }
        }
    }
}
