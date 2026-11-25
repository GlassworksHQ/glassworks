/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.cards

import dev.glassworks.engine.vitro.theme.ThemeResolver
import java.util.Locale

/** A CSS length that may be a percentage of its container. */
data class CssLen(val value: Float, val percent: Boolean) {
    fun resolve(base: Float): Float = if (percent) base * value / 100f else value
}

/**
 * The computed style of one [MdNode], in CSS px. Only the properties Vitro's Markdown layout draws are kept:
 * typography, color and opacity, the box (margin, padding, width, height, background, radius, borders) and the
 * table model (border-spacing, border-collapse, table-layout, vertical-align).
 */
class MdStyle {
    var display = "inline"
    var fontSize = 14f
    var fontWeight = 400
    var italic = false
    /** `font-family` as a CSS list, or null for the card's own font ([dev.glassworks.engine.vitro.ui.VitroFonts]). */
    var fontFamily: String? = null

    /** Line height in px, or -1 for `normal`. */
    var lineHeight = -1f

    /** Unitless line-height factor (inherits as a factor), or -1 when the line height is a length or `normal`. */
    var lineHeightFactor = -1f
    var letterSpacing = 0f
    var textTransform: String? = null
    var textAlign = "start"
    var color = 0xFFFFFFFF.toInt()
    var opacity = 1f
    var underline = false
    var strike = false
    var whiteSpacePre = false
    var hidden = false

    /** Top, right, bottom, left; NaN is `auto`. */
    val margin = FloatArray(4)
    val padding = FloatArray(4)
    val borderWidth = FloatArray(4)
    val borderColor = IntArray(4)
    var width: CssLen? = null
    var height: CssLen? = null
    var maxWidth: CssLen? = null
    var background: Int? = null
    var borderRadius = 0f
    var borderSpacingH = 0f
    var borderSpacingV = 0f
    var borderCollapse = false
    var tableLayoutFixed = false
    var verticalAlign = "baseline"
    /** `position` and its `top` / `bottom` offsets (CSS px); only `relative` moves anything. */
    var position = "static"
    var offsetTop: Float? = null
    var offsetBottom: Float? = null

    /** How far `position: relative` moves the box down (CSS px): `top`, else minus `bottom`. */
    fun relativeShiftY(): Float = if (position != "relative") 0f else offsetTop ?: offsetBottom?.let { -it } ?: 0f
    var overflowHidden = false
    var listStyleNone = false

    /** Flexbox and grid alignment, as written (`center`, `flex-start`, `end`, ...); null when unset. */
    var alignItems: String? = null
    var alignSelf: String? = null
    var justifyContent: String? = null

    /** Custom properties in force here (`--name` without the dashes), inherited like the browser does. */
    var custom: Map<String, String> = emptyMap()

    /**
     * The CSS line height in device px. `normal` is [normalPx], the font's own ascent + descent + line gap as the
     * browser takes them (Roboto's 1.17 em, Comic Neue's 1.15, Inter's 1.21), from the font this element draws in.
     */
    fun lineHeightPx(dp: Float, normalPx: Float): Float = if (lineHeight >= 0f) lineHeight * dp else normalPx

    val isBlockLevel: Boolean get() = display != "inline" && display != "inline-block" && display != "inline-flex"
}

/** One selector of a selector list, matched right to left against an [MdNode]. */
class CssSelector private constructor(
    private val parts: List<Compound>,
    private val combinators: List<Char>,
    val specificity: Int,
) {
    private class AttrTest(val name: String, val op: String?, val value: String)
    private class Pseudo(
        val name: String,
        val a: Int = 0,
        val b: Int = 0,
        val inner: List<Compound> = emptyList(),
        /** `:has()`'s relative selectors: the combinator before each (' ' when none is written) and the selector. */
        val relative: List<Pair<Char, CssSelector>> = emptyList(),
    )
    private class Compound(val tag: String?, val attrs: List<AttrTest>, val pseudos: List<Pseudo>, val never: Boolean)

    fun matches(node: MdNode): Boolean = !node.isText && matchFrom(parts.size - 1, node, null)

    /**
     * Matches right to left. [leftmost], for a `:has()` relative selector, says where its leftmost compound may be
     * (inside the subject, its child, its next sibling ...); null for an ordinary selector.
     */
    private fun matchFrom(idx: Int, node: MdNode, leftmost: ((MdNode) -> Boolean)?): Boolean {
        if (!compoundMatches(parts[idx], node)) return false
        if (idx == 0) return leftmost?.invoke(node) ?: true
        when (combinators[idx - 1]) {
            '>' -> return node.parent?.let { matchFrom(idx - 1, it, leftmost) } ?: false
            '+' -> return previousElement(node)?.let { matchFrom(idx - 1, it, leftmost) } ?: false
            '~' -> {
                var p = previousElement(node)
                while (p != null) {
                    if (matchFrom(idx - 1, p, leftmost)) return true
                    p = previousElement(p)
                }
                return false
            }
            else -> {
                var p = node.parent
                while (p != null) {
                    if (matchFrom(idx - 1, p, leftmost)) return true
                    p = p.parent
                }
                return false
            }
        }
    }

    /**
     * `:has(<relative selector>)` for [subject]: some element, placed against the subject by [comb] (a descendant, a
     * child, the next sibling, a later sibling), starts a chain [sel] matches.
     */
    private fun hasMatch(subject: MdNode, comb: Char, sel: CssSelector): Boolean {
        val leftmost: (MdNode) -> Boolean = when (comb) {
            '>' -> { n -> n.parent === subject }
            '+' -> { n -> previousElement(n) === subject }
            '~' -> { n -> generateSequence(previousElement(n)) { previousElement(it) }.any { it === subject } }
            else -> { n -> generateSequence(n.parent) { it.parent }.any { it === subject } }
        }
        // Where the rightmost element can be: inside the subject, or (for sibling combinators) inside its parent.
        val scope = if (comb == '+' || comb == '~') subject.parent ?: return false else subject
        fun walk(n: MdNode): Boolean {
            for (c in n.children) {
                if (c.isText) continue
                if (sel.matchFrom(sel.parts.size - 1, c, leftmost)) return true
                if (walk(c)) return true
            }
            return false
        }
        return walk(scope)
    }

    private fun compoundMatches(c: Compound, node: MdNode): Boolean {
        if (c.never) return false
        if (c.tag != null && c.tag != node.tag) return false
        for (a in c.attrs) {
            val v = node.attrs[a.name] ?: return false
            val ok = when (a.op) {
                null -> true
                "=" -> v == a.value
                "~=" -> v.split(' ').contains(a.value)
                "|=" -> v == a.value || v.startsWith(a.value + "-")
                "^=" -> a.value.isNotEmpty() && v.startsWith(a.value)
                "$=" -> a.value.isNotEmpty() && v.endsWith(a.value)
                "*=" -> a.value.isNotEmpty() && a.value in v
                else -> false
            }
            if (!ok) return false
        }
        for (p in c.pseudos) if (!pseudoMatches(p, node)) return false
        return true
    }

    private fun pseudoMatches(p: Pseudo, node: MdNode): Boolean {
        val parent = node.parent
        return when (p.name) {
            "first-child" -> parent != null && parent.children.firstOrNull { !it.isText } === node
            "last-child" -> parent != null && parent.children.lastOrNull { !it.isText } === node
            "only-child" -> parent != null && parent.children.count { !it.isText } == 1
            "first-of-type" -> parent != null && parent.children.firstOrNull { it.tag == node.tag } === node
            "last-of-type" -> parent != null && parent.children.lastOrNull { it.tag == node.tag } === node
            "only-of-type" -> parent != null && parent.children.count { it.tag == node.tag } == 1
            "nth-child" -> parent != null && nth(p.a, p.b, parent.children.filter { !it.isText }.indexOf(node) + 1)
            "nth-last-child" -> parent != null && parent.children.filter { !it.isText }.let { nth(p.a, p.b, it.size - it.indexOf(node)) }
            "nth-of-type" -> parent != null && nth(p.a, p.b, parent.children.filter { it.tag == node.tag }.indexOf(node) + 1)
            "nth-last-of-type" -> parent != null && parent.children.filter { it.tag == node.tag }.let { nth(p.a, p.b, it.size - it.indexOf(node)) }
            "empty" -> node.children.none { !it.isText || it.text.isNotEmpty() }
            "root" -> parent == null
            "not" -> p.inner.none { compoundMatches(it, node) }
            "is", "where" -> p.inner.any { compoundMatches(it, node) }
            // The shadow host the sheet belongs to (a card's host element), optionally with a compound it must match.
            "host" -> HOST_ATTR in node.attrs && p.inner.all { compoundMatches(it, node) }
            "has" -> p.relative.any { (comb, sel) -> hasMatch(node, comb, sel) }
            else -> false
        }
    }

    private fun previousElement(node: MdNode): MdNode? {
        val siblings = node.parent?.children ?: return null
        val i = siblings.indexOf(node)
        for (k in i - 1 downTo 0) if (!siblings[k].isText) return siblings[k]
        return null
    }

    companion object {
        private fun nth(a: Int, b: Int, pos: Int): Boolean {
            if (pos <= 0) return false
            if (a == 0) return pos == b
            val n = (pos - b) / a
            return n >= 0 && n * a + b == pos
        }

        /**
         * Marks the element a sheet's `:host` is (the card's host element in a card's cascade tree). An attribute no
         * Markdown can carry: HA's sanitizer keeps no `data-` attributes.
         */
        const val HOST_ATTR = "data-vitro-host"

        private val ATTRIBUTE_TEST = Regex("""^([\w-]+)\s*(?:([~|^$*]?=)\s*(?:"([^"]*)"|'([^']*)'|([^\s\]]+))\s*(?:\s[iIsS])?)?$""")

        private val NTH = Regex("""^([+-]?\d*)n\s*(?:([+-])\s*(\d+))?$""")

        private fun parseNth(arg: String): Pair<Int, Int>? {
            val s = arg.trim().lowercase(Locale.ROOT)
            if (s == "odd") return 2 to 1
            if (s == "even") return 2 to 0
            s.toIntOrNull()?.let { return 0 to it }
            val m = NTH.matchEntire(s) ?: return null
            // Out-of-range numbers make the selector unsupported instead of aborting the sheet.
            val a = when (m.groupValues[1]) {
                "", "+" -> 1
                "-" -> -1
                else -> m.groupValues[1].toIntOrNull() ?: return null
            }
            val bRaw = m.groupValues[3]
            val b = if (bRaw.isEmpty()) 0 else (bRaw.toIntOrNull() ?: return null).let { if (m.groupValues[2] == "-") -it else it }
            return a to b
        }

        /** Parses a comma-separated selector list; selectors Vitro can't read are left out. */
        fun parseList(list: String): List<CssSelector> = splitTopLevel(list, ',').mapNotNull { parse(it.trim()) }

        fun parse(sel: String): CssSelector? {
            if (sel.isEmpty()) return null
            val parts = ArrayList<Compound>()
            val combs = ArrayList<Char>()
            var spec = 0
            var i = 0
            var pendingComb: Char? = null
            while (i < sel.length) {
                val c = sel[i]
                // CSS whitespace only: a non-breaking space is no combinator.
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000C') {
                    if (parts.isNotEmpty() && pendingComb == null) pendingComb = ' '
                    i++
                    continue
                }
                if (c == '>' || c == '+' || c == '~') {
                    // A second explicit combinator before the next compound (`div > > p`) is invalid CSS.
                    if (parts.isEmpty() || pendingComb != null && pendingComb != ' ') return null
                    pendingComb = c
                    i++
                    continue
                }
                val (compound, end, s) = parseCompound(sel, i) ?: return null
                if (parts.isNotEmpty()) combs += (pendingComb ?: ' ')
                pendingComb = null
                parts += compound
                spec += s
                i = end
            }
            if (parts.isEmpty() || pendingComb != null && pendingComb != ' ') return null
            return CssSelector(parts, combs, spec)
        }

        /** One compound selector at [start]: the compound, where it ends, and its specificity. */
        private fun parseCompound(sel: String, start: Int): Triple<Compound, Int, Int>? {
            var i = start
            var tag: String? = null
            val attrs = ArrayList<AttrTest>()
            val pseudos = ArrayList<Pseudo>()
            var never = false
            var spec = 0
            fun ident(): String {
                val b = i
                while (i < sel.length && (sel[i].isLetterOrDigit() || sel[i] == '-' || sel[i] == '_' || sel[i] == '\\')) i++
                return sel.substring(b, i)
            }
            while (i < sel.length) {
                val c = sel[i]
                when {
                    c == '*' -> { i++ }
                    c.isLetter() || c == '-' || c == '_' -> {
                        tag = ident().lowercase(Locale.ROOT)
                        spec += 1
                    }
                    // The sanitizer strips `class` and `id`, so these only match the card's own host elements.
                    c == '.' -> { i++; attrs += AttrTest("class", "~=", ident()); spec += 100 }
                    c == '#' -> { i++; attrs += AttrTest("id", "=", ident()); spec += 10000 }
                    c == '[' -> {
                        val close = sel.indexOf(']', i)
                        if (close < 0) return null
                        val body = sel.substring(i + 1, close).trim()
                        val m = ATTRIBUTE_TEST.matchEntire(body)
                            ?: return null
                        val value = m.groupValues[3].ifEmpty { m.groupValues[4].ifEmpty { m.groupValues[5] } }
                        attrs += AttrTest(m.groupValues[1].lowercase(Locale.ROOT), m.groupValues[2].ifEmpty { null }, value)
                        spec += 100
                        i = close + 1
                    }
                    c == ':' -> {
                        i++
                        if (i < sel.length && sel[i] == ':') { i++; ident(); never = true; continue } // pseudo-element
                        val name = ident().lowercase(Locale.ROOT)
                        var arg: String? = null
                        if (i < sel.length && sel[i] == '(') {
                            var depth = 0
                            val b = i + 1
                            while (i < sel.length) {
                                if (sel[i] == '(') depth++ else if (sel[i] == ')') { depth--; if (depth == 0) break }
                                i++
                            }
                            arg = sel.substring(b, i.coerceAtMost(sel.length))
                            i++
                        }
                        when (name) {
                            "nth-child", "nth-last-child", "nth-of-type", "nth-last-of-type" -> {
                                val (a, b) = arg?.let { parseNth(it) } ?: return null
                                pseudos += Pseudo(name, a, b)
                                spec += 100
                            }
                            "not", "is", "where" -> {
                                val inner = splitTopLevel(arg.orEmpty(), ',').map { part ->
                                    val p = part.trim()
                                    val r = parseCompound(p, 0) ?: return null
                                    if (r.second != p.length) return null // only compound selectors inside
                                    r
                                }
                                pseudos += Pseudo(name, inner = inner.map { it.first })
                                if (name != "where") spec += inner.maxOfOrNull { it.third } ?: 0
                            }
                            "first-child", "last-child", "only-child", "first-of-type", "last-of-type",
                            "only-of-type", "empty", "root" -> { pseudos += Pseudo(name); spec += 100 }
                            // `:host` and `:host(<compound>)`: a pseudo-class, plus its argument's specificity.
                            "host" -> {
                                val inner = arg?.trim()?.takeIf { it.isNotEmpty() }?.let { a ->
                                    val r = parseCompound(a, 0) ?: return null
                                    if (r.second != a.length) return null
                                    r
                                }
                                pseudos += Pseudo(name, inner = listOfNotNull(inner?.first))
                                spec += 100 + (inner?.third ?: 0)
                            }
                            // `:has(<relative selector list>)`: as specific as its most specific argument.
                            "has" -> {
                                val relative = splitTopLevel(arg.orEmpty(), ',').map { part ->
                                    var p = part.trim()
                                    val comb = p.firstOrNull()?.takeIf { it == '>' || it == '+' || it == '~' } ?: ' '
                                    if (comb != ' ') p = p.substring(1).trim()
                                    comb to (parse(p) ?: return null)
                                }
                                if (relative.isEmpty()) return null
                                pseudos += Pseudo(name, relative = relative)
                                spec += relative.maxOf { it.second.specificity }
                            }
                            else -> { never = true; spec += 100 } // :hover, :focus … never match here
                        }
                    }
                    else -> break
                }
            }
            if (i == start) return null
            return Triple(Compound(tag, attrs, pseudos, never), i, spec)
        }

        fun splitTopLevel(s: String, sep: Char): List<String> {
            val out = ArrayList<String>()
            var depth = 0
            var quote: Char? = null
            var b = 0
            for (k in s.indices) {
                val c = s[k]
                when {
                    quote != null -> if (c == quote) quote = null
                    c == '"' || c == '\'' -> quote = c
                    c == '(' || c == '[' -> depth++
                    c == ')' || c == ']' -> depth--
                    c == sep && depth == 0 -> { out += s.substring(b, k); b = k + 1 }
                }
            }
            out += s.substring(b)
            return out
        }
    }
}

/** One rule of a sheet: a selector list and its declarations (names lowercased, `!important` dropped). */
class MdRule(val selectors: List<CssSelector>, val decls: List<Pair<String, String>>)

/** A parsed style sheet. Parse once and reuse it for every render. */
class MdSheet(val rules: List<MdRule>) {
    companion object {
        val EMPTY = MdSheet(emptyList())

        fun parse(css: String?): MdSheet {
            if (css.isNullOrBlank()) return EMPTY
            synchronized(cache) { cache[css] }?.let { return it }
            val sheet = MdSheet(ThemeResolver.cssRules(css).mapNotNull { (sel, decls) ->
                val selectors = CssSelector.parseList(sel)
                if (selectors.isEmpty() || decls.isEmpty()) null else MdRule(selectors, decls)
            })
            synchronized(cache) { cache[css] = sheet }
            return sheet
        }

        // A sheet is immutable, and dashboards repeat the same card_mod sheet on many cards: parse each text once,
        // keeping the most recently used [CACHE_SIZE].
        private const val CACHE_SIZE = 128
        private val cache = object : LinkedHashMap<String, MdSheet>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MdSheet>?) = size > CACHE_SIZE
        }
    }
}

/**
 * The cascade for a Markdown card: the browser's own defaults, the attribute hints the sanitizer keeps
 * (`<font color>`, `<td width align valign>`, `<img width height>`), then the card's `ha-markdown$` sheet and HA's
 * `ha-markdown` styles. HA's styles are adopted style sheets, so they win specificity ties against card-mod's
 * `<style>`; dashboards raise specificity (`ha-markdown-element h2 { … }`) to beat them, and so does Vitro.
 */
object MdCascade {
    /** The browser defaults (Chromium's UA sheet) for the elements HA's sanitizer lets through. */
    val UA: MdSheet = MdSheet.parse(
        """
        ha-markdown-element, div, p, h1, h2, h3, h4, h5, h6, ul, ol, blockquote, pre, center, hr, address, article,
        aside, footer, header, nav, section, figure, figcaption, details, summary, dl, dt, dd, ha-alert, picture { display: block }
        li { display: list-item }
        table { display: table; border-spacing: 2px; border-collapse: separate }
        thead, tbody, tfoot { display: table-row-group; vertical-align: middle }
        tr { display: table-row; vertical-align: inherit }
        td, th { display: table-cell; vertical-align: inherit; padding: 1px }
        th { font-weight: bold; text-align: center }
        caption { display: block; text-align: center }
        p { margin: 1em 0 }
        h1 { font-size: 2em; margin: 0.67em 0; font-weight: bold }
        h2 { font-size: 1.5em; margin: 0.83em 0; font-weight: bold }
        h3 { font-size: 1.17em; margin: 1em 0; font-weight: bold }
        h4 { margin: 1.33em 0; font-weight: bold }
        h5 { font-size: 0.83em; margin: 1.67em 0; font-weight: bold }
        h6 { font-size: 0.67em; margin: 2.33em 0; font-weight: bold }
        ul, ol { margin: 1em 0; padding-left: 40px }
        ul ul, ol ul, ul ol, ol ol { margin: 0 }
        dd { margin-left: 40px }
        blockquote { margin: 1em 40px }
        pre { white-space: pre; font-family: monospace; margin: 1em 0 }
        code, kbd, tt, samp { font-family: monospace }
        hr { margin: 0.5em auto; border-style: inset; border-width: 1px; border-color: gray }
        b, strong { font-weight: bolder }
        i, em, cite { font-style: italic }
        small, sub, sup { font-size: smaller }
        big { font-size: larger }
        s, strike, del { text-decoration: line-through }
        u, ins { text-decoration: underline }
        a { color: #0000ee; text-decoration: underline }
        center { text-align: center }
        mark { background-color: yellow; color: black }
        ha-icon { display: inline-block; vertical-align: middle; width: var(--mdc-icon-size, 24px); height: var(--mdc-icon-size, 24px) }
        """.trimIndent(),
    )

    /** HA's `ha-markdown` styles (`src/components/ha-markdown.ts`), the parts Vitro draws. */
    val HA: MdSheet = MdSheet.parse(
        """
        ha-markdown-element > *:first-child { margin-top: 0 }
        ha-markdown-element > *:last-child { margin-bottom: 0 }
        a { color: var(--markdown-link-color, var(--primary-color)) }
        img { background-color: var(--markdown-image-background-color); border-radius: var(--markdown-image-border-radius); max-width: 100% }
        p:first-child > img:first-child { vertical-align: top }
        p:first-child > img:last-child { vertical-align: top }
        ha-markdown-element > :is(ol, ul) { padding-inline-start: var(--markdown-list-indent, revert) }
        code, pre { background-color: var(--markdown-code-background-color, none); border-radius: var(--ha-border-radius-sm); color: var(--markdown-code-text-color, inherit) }
        code { font-size: var(--ha-font-size-s); padding: 0.2em 0.4em }
        pre code { padding: 0 }
        pre { padding: var(--ha-space-4); line-height: var(--ha-line-height-condensed); font-family: var(--ha-font-family-code) }
        h1, h2, h3, h4, h5, h6 { line-height: initial }
        h2 { font-size: var(--ha-font-size-xl); font-weight: var(--ha-font-weight-bold) }
        hr { border-color: var(--divider-color); border-bottom: none; margin: var(--ha-space-4) 0 }
        table[role="presentation"] { --markdown-table-border-collapse: separate; --markdown-table-border-width: 0; --markdown-table-padding-inline: 0; --markdown-table-padding-block: 0 }
        table[role="presentation"] th, table[role="presentation"] td { vertical-align: middle }
        table[role="presentation"] td[valign="top"], table[role="presentation"] th[valign="top"] { vertical-align: top }
        table[role="presentation"] td[valign="middle"], table[role="presentation"] th[valign="middle"] { vertical-align: middle }
        table[role="presentation"] td[valign="bottom"], table[role="presentation"] th[valign="bottom"] { vertical-align: bottom }
        table[role="presentation"] td[valign="baseline"], table[role="presentation"] th[valign="baseline"] { vertical-align: baseline }
        table[role="presentation"][border="0"] { --markdown-table-border-width: 0 }
        table[role="presentation"][border="1"] { --markdown-table-border-width: 1px }
        table[role="presentation"][border="2"] { --markdown-table-border-width: 2px }
        table[role="presentation"][border="3"] { --markdown-table-border-width: 3px }
        table { border-collapse: var(--markdown-table-border-collapse, collapse) }
        th { text-align: var(--markdown-table-text-align, start) }
        td, th { border-width: var(--markdown-table-border-width, 1px); border-style: var(--markdown-table-border-style, solid); border-color: var(--markdown-table-border-color, var(--divider-color)); padding-inline: var(--markdown-table-padding-inline, 0.5em); padding-block: var(--markdown-table-padding-block, 0.25em) }
        blockquote { border-left: 4px solid var(--divider-color); margin-inline: 0; padding-inline: 1em }
        """.trimIndent(),
    )

    /** HA's global design tokens (`core.globals.ts`, `typography.globals.ts`), used when the theme doesn't set one. */
    val HA_DEFAULT_VARS: Map<String, String> = mapOf(
        "ha-font-size-scale" to "1", "ha-font-size-xs" to "10px", "ha-font-size-s" to "12px",
        "ha-font-size-m" to "14px", "ha-font-size-l" to "16px", "ha-font-size-xl" to "20px",
        "ha-font-size-2xl" to "24px", "ha-font-size-3xl" to "28px", "ha-font-size-4xl" to "32px",
        "ha-font-size-5xl" to "40px", "ha-font-weight-light" to "300", "ha-font-weight-normal" to "400",
        "ha-font-weight-medium" to "500", "ha-font-weight-bold" to "700", "ha-line-height-condensed" to "1.2",
        "ha-line-height-normal" to "1.6", "ha-line-height-expanded" to "2", "ha-font-family-code" to "monospace",
        "ha-border-radius-sm" to "4px", "ha-border-radius-md" to "8px", "ha-border-radius-lg" to "12px",
        "ha-border-radius-xl" to "16px", "ha-space-1" to "4px", "ha-space-2" to "8px", "ha-space-3" to "12px",
        "ha-space-4" to "16px", "ha-space-5" to "20px", "ha-space-6" to "24px", "ha-space-8" to "32px",
    )

    /**
     * Computes every node's style under [root] (the `ha-markdown-element`), which inherits from [parent] (the
     * `ha-markdown` host: the card's font, color, alignment and custom properties). [cardSheet] is the card's
     * `ha-markdown$` sheet; [themeVars] backs `var()`.
     */
    fun apply(root: MdNode, parent: MdStyle, cardSheet: MdSheet, themeVars: Map<String, String>) =
        apply(root, parent, listOf(UA to 0, cardSheet to 2, HA to 3), themeVars)

    /**
     * The cascade over any small tree: [sheets] with their origin (0 browser, 1 host defaults, 2 or more author).
     * Author sheets are equal in origin; on a specificity tie the later sheet in [sheets] wins, so HA's adopted
     * sheets go after card-mod's.
     */
    fun apply(root: MdNode, parent: MdStyle, sheets: List<Pair<MdSheet, Int>>, themeVars: Map<String, String>) {
        val ctx = Ctx(themeVars, sheets)
        root.style = computeOne(root, parent, ctx, isRoot = true)
        walk(root, ctx)
    }

    private class Ctx(val themeVars: Map<String, String>, val sheets: List<Pair<MdSheet, Int>>)

    private fun walk(node: MdNode, ctx: Ctx) {
        val ps = node.style!!
        for (child in node.children) {
            if (child.isText) {
                child.style = ps
            } else {
                child.style = computeOne(child, ps, ctx, isRoot = false)
                walk(child, ctx)
            }
        }
    }

    private class Decl(val origin: Int, val spec: Int, val order: Int, val name: String, val value: String)

    private fun computeOne(node: MdNode, parent: MdStyle, ctx: Ctx, isRoot: Boolean): MdStyle {
        val decls = ArrayList<Decl>()
        var order = 0
        ctx.sheets.forEach { (sheet, origin) ->
            for (rule in sheet.rules) {
                var best = -1
                for (s in rule.selectors) if (s.specificity > best && s.matches(node)) best = s.specificity
                // card-mod's sheet and HA's own are both author styles: specificity decides, then sheet order.
                if (best >= 0) for ((n, v) in rule.decls) decls += Decl(minOf(origin, 2), best, order++, n, v)
                else order += rule.decls.size
            }
        }
        presentationalHints(node).forEach { (n, v) -> decls += Decl(1, 0, order++, n, v) }
        decls.sortWith(compareBy<Decl>({ it.origin }, { it.spec }, { it.order }))

        val s = MdStyle()
        inherit(s, parent)
        if (isRoot) s.display = "block"

        // 1. Custom properties, in cascade order.
        val custom = LinkedHashMap(s.custom)
        for (d in decls) if (d.name.startsWith("--")) {
            custom[d.name.substring(2)] = resolveVars(d.value, custom, ctx.themeVars) ?: d.value
        }
        s.custom = custom

        // 2. Everything else as longhands; later declarations win.
        // `revert` outside the browser's sheet rolls back to the browser's value (sorted first, so already known).
        val specified = LinkedHashMap<String, String>()
        val browser = HashMap<String, String>()
        for (d in decls) {
            if (d.name.startsWith("--")) continue
            // A failed substitution makes the declaration `unset`, for each longhand of a shorthand too.
            val v = resolveVars(d.value, custom, ctx.themeVars) ?: "unset"
            expand(d.name, v).forEach { (n, value) ->
                if (d.origin == 0) browser[n] = value
                specified[n] = if (d.origin > 0 && value.trim().equals("revert", ignoreCase = true)) browser[n] ?: "unset" else value
            }
        }
        applySpecified(s, parent, specified)
        return s
    }

    private fun inherit(s: MdStyle, p: MdStyle) {
        s.fontSize = p.fontSize
        s.fontWeight = p.fontWeight
        s.italic = p.italic
        s.fontFamily = p.fontFamily
        s.lineHeightFactor = p.lineHeightFactor
        s.lineHeight = p.lineHeight
        s.letterSpacing = p.letterSpacing
        s.textTransform = p.textTransform
        s.textAlign = p.textAlign
        s.color = p.color
        s.whiteSpacePre = p.whiteSpacePre
        s.borderSpacingH = p.borderSpacingH
        s.borderSpacingV = p.borderSpacingV
        s.borderCollapse = p.borderCollapse
        s.custom = p.custom
        s.hidden = p.hidden
        s.underline = p.underline
        s.strike = p.strike
        s.listStyleNone = p.listStyleNone
    }

    private val INHERITED = setOf(
        "font-size", "font-weight", "font-style", "font-family", "line-height", "letter-spacing", "text-transform",
        "text-align", "color", "white-space", "border-spacing", "border-collapse", "visibility", "list-style-type",
    )

    private fun applySpecified(s: MdStyle, p: MdStyle, sp: Map<String, String>) {
        fun kw(name: String): String? = sp[name]?.trim()?.lowercase(Locale.ROOT)
        // Font size first: other lengths in `em` depend on it.
        kw("font-size")?.let { v ->
            s.fontSize = when (v) {
                "inherit", "unset", "revert" -> p.fontSize
                "initial", "medium" -> 16f
                "smaller" -> p.fontSize / 1.2f
                "larger" -> p.fontSize * 1.2f
                "xx-small" -> 9f
                "x-small" -> 10f
                "small" -> 13f
                "large" -> 18f
                "x-large" -> 24f
                "xx-large" -> 32f
                "xxx-large" -> 48f
                else -> length(v, p.fontSize, p.fontSize)?.takeIf { it >= 0f } ?: p.fontSize
            }
        }
        // A unitless line height inherits as a factor and follows this element's font size.
        if (s.lineHeightFactor >= 0f) s.lineHeight = s.lineHeightFactor * s.fontSize
        val currentColorBorders = BooleanArray(4)
        for ((name, raw) in sp) {
            val v = raw.trim()
            val lower = v.lowercase(Locale.ROOT)
            val inheritKw = lower == "inherit" || ((lower == "unset" || lower == "revert") && name in INHERITED)
            val initialKw = lower == "initial" || ((lower == "unset" || lower == "revert") && name !in INHERITED)
            when (name) {
                "font-size" -> Unit
                "display" -> s.display = if (initialKw) "inline" else lower
                "font-weight" -> s.fontWeight = when {
                    inheritKw -> p.fontWeight
                    initialKw || lower == "normal" -> 400
                    lower == "bold" -> 700
                    lower == "bolder" -> when {
                        p.fontWeight < 350 -> 400
                        p.fontWeight < 550 -> 700
                        else -> 900
                    }
                    lower == "lighter" -> when {
                        p.fontWeight < 550 -> 100
                        p.fontWeight < 750 -> 400
                        else -> 700
                    }
                    else -> lower.toFloatOrNull()?.toInt()?.coerceIn(1, 1000) ?: s.fontWeight
                }
                "font-style" -> s.italic = if (inheritKw) p.italic else lower == "italic" || lower == "oblique"
                "font-family" -> s.fontFamily = when {
                    inheritKw -> p.fontFamily
                    // The browser's default font, not the card's: initial isn't inherit.
                    initialKw -> dev.glassworks.engine.vitro.theme.ThemeResolver.INITIAL_FONT_FAMILY
                    else -> lower
                }
                "line-height" -> when {
                    inheritKw -> { s.lineHeightFactor = p.lineHeightFactor; s.lineHeight = if (p.lineHeightFactor >= 0f) p.lineHeightFactor * s.fontSize else p.lineHeight }
                    initialKw || lower == "normal" -> { s.lineHeightFactor = -1f; s.lineHeight = -1f }
                    lower.toFloatOrNull() != null -> { s.lineHeightFactor = lower.toFloat(); s.lineHeight = s.lineHeightFactor * s.fontSize }
                    else -> length(lower, s.fontSize, s.fontSize)?.let { s.lineHeightFactor = -1f; s.lineHeight = it }
                }
                "letter-spacing" -> s.letterSpacing = when {
                    inheritKw -> p.letterSpacing
                    initialKw || lower == "normal" -> 0f
                    else -> length(lower, s.fontSize, s.fontSize) ?: s.letterSpacing
                }
                "text-transform" -> s.textTransform = when {
                    inheritKw -> p.textTransform
                    initialKw || lower == "none" -> null
                    else -> lower
                }
                "text-align" -> s.textAlign = when {
                    inheritKw || lower == "match-parent" -> p.textAlign
                    initialKw -> "start"
                    else -> lower.removePrefix("-webkit-").removePrefix("-internal-")
                }
                "color" -> s.color = when {
                    inheritKw || lower == "currentcolor" -> p.color
                    initialKw -> 0xFF000000.toInt()
                    else -> MarkdownInline.parseCssColor(v) ?: s.color
                }
                "opacity" -> s.opacity = if (initialKw || inheritKw) 1f else MarkdownInline.parseOpacity(lower) ?: 1f
                "text-decoration-line" -> {
                    if (lower == "none" || initialKw) { s.underline = false; s.strike = false }
                    if ("underline" in lower) s.underline = true
                    if ("line-through" in lower) s.strike = true
                }
                "white-space" -> s.whiteSpacePre = if (inheritKw) p.whiteSpacePre else lower.startsWith("pre") || lower == "break-spaces"
                "visibility" -> s.hidden = if (inheritKw) p.hidden else lower == "hidden" || lower == "collapse"
                "list-style-type" -> s.listStyleNone = lower == "none"
                "margin-top", "margin-right", "margin-bottom", "margin-left" -> {
                    val idx = SIDES.indexOf(name.substringAfter('-'))
                    s.margin[idx] = when {
                        lower == "auto" -> Float.NaN
                        initialKw || inheritKw -> 0f
                        else -> length(lower, s.fontSize, 0f) ?: 0f
                    }
                }
                "padding-top", "padding-right", "padding-bottom", "padding-left" -> {
                    val idx = SIDES.indexOf(name.substringAfter('-'))
                    s.padding[idx] = if (initialKw || inheritKw) 0f else (length(lower, s.fontSize, 0f) ?: 0f).coerceAtLeast(0f)
                }
                "border-top-width", "border-right-width", "border-bottom-width", "border-left-width" -> {
                    val idx = SIDES.indexOf(name.substringAfter('-').substringBefore('-'))
                    s.borderWidth[idx] = when (lower) {
                        "thin" -> 1f
                        "medium" -> 3f
                        "thick" -> 5f
                        else -> if (initialKw) 3f else length(lower, s.fontSize, 0f)?.coerceAtLeast(0f) ?: s.borderWidth[idx]
                    }
                }
                "border-top-color", "border-right-color", "border-bottom-color", "border-left-color" -> {
                    val idx = SIDES.indexOf(name.substringAfter('-').substringBefore('-'))
                    // `currentColor`, and what an invalid value (HA's `hr` without --divider-color) resets to: the
                    // text color, applied after the loop once `color` is known.
                    if (initialKw || lower == "currentcolor") currentColorBorders[idx] = true
                    else s.borderColor[idx] = MarkdownInline.parseCssColor(v) ?: s.borderColor[idx]
                }
                "width" -> s.width = if (lower == "auto" || initialKw) null else cssLen(lower, s.fontSize)
                "height" -> s.height = if (lower == "auto" || initialKw) null else cssLen(lower, s.fontSize)
                "max-width" -> s.maxWidth = if (lower == "none" || initialKw) null else cssLen(lower, s.fontSize)
                "background-color" -> s.background = if (initialKw || lower == "none" || lower == "transparent") null
                else MarkdownInline.parseCssColor(v) ?: COLOR_TOKEN.find(v)?.value?.let { MarkdownInline.parseCssColor(it) }
                "border-radius" -> s.borderRadius = if (initialKw) 0f else length(v.trim().substringBefore(' ').substringBefore('/'), s.fontSize, 0f)?.coerceAtLeast(0f) ?: 0f
                "border-spacing" -> {
                    if (inheritKw) { s.borderSpacingH = p.borderSpacingH; s.borderSpacingV = p.borderSpacingV }
                    else {
                        val parts = CssSelector.splitTopLevel(lower, ' ').filter { it.isNotBlank() }
                        val h = parts.getOrNull(0)?.let { length(it, s.fontSize, 0f) } ?: 0f
                        s.borderSpacingH = h
                        s.borderSpacingV = parts.getOrNull(1)?.let { length(it, s.fontSize, 0f) } ?: h
                    }
                }
                "border-collapse" -> s.borderCollapse = if (inheritKw) p.borderCollapse else lower == "collapse"
                "table-layout" -> s.tableLayoutFixed = lower == "fixed"
                "vertical-align" -> s.verticalAlign = if (inheritKw) p.verticalAlign else if (initialKw) "baseline" else lower
                "position" -> s.position = if (inheritKw) p.position else if (initialKw) "static" else lower
                "top" -> s.offsetTop = if (lower == "auto" || initialKw) null else length(lower, s.fontSize, 0f)
                "bottom" -> s.offsetBottom = if (lower == "auto" || initialKw) null else length(lower, s.fontSize, 0f)
                "overflow", "overflow-x", "overflow-y" -> if (lower == "hidden" || lower == "clip") s.overflowHidden = true
                "align-items" -> s.alignItems = if (initialKw) null else lower
                "align-self" -> s.alignSelf = if (initialKw) null else lower
                "justify-content" -> s.justifyContent = if (initialKw) null else lower
            }
        }
        for (i in 0 until 4) if (currentColorBorders[i]) s.borderColor[i] = s.color
        // `border-*-style: none` (the initial value) means no border, whatever the width says.
        for (k in 0 until 4) {
            val style = sp["border-${SIDES[k]}-style"]?.trim()?.lowercase(Locale.ROOT)
            if (style == null || style == "none" || style == "hidden" || style == "initial" || style == "unset") {
                s.borderWidth[k] = 0f
            } else {
                // A visible side with no width or color of its own takes the initial ones: `medium` (3 px) and
                // currentColor. An explicit width, zero included, stays.
                if ("border-${SIDES[k]}-width" !in sp) s.borderWidth[k] = 3f
                if ("border-${SIDES[k]}-color" !in sp) s.borderColor[k] = s.color
            }
        }
    }

    private val SIDES = listOf("top", "right", "bottom", "left")
    private val COLOR_TOKEN = Regex("""#[0-9a-fA-F]{3,8}\b|rgba?\([^)]*\)|hsla?\([^)]*\)""")

    /** Attribute hints the sanitizer keeps, as the browser maps them to CSS (lowest author precedence). */
    private fun presentationalHints(node: MdNode): List<Pair<String, String>> {
        if (node.attrs.isEmpty()) return emptyList()
        val out = ArrayList<Pair<String, String>>(2)
        val a = node.attrs
        when (node.tag) {
            "font" -> {
                a["color"]?.let { out += "color" to it }
                a["size"]?.trim()?.let { sz ->
                    val n = when {
                        sz.startsWith("+") -> 3 + (sz.drop(1).toIntOrNull() ?: 0)
                        sz.startsWith("-") -> 3 - (sz.drop(1).toIntOrNull() ?: 0)
                        else -> sz.toIntOrNull() ?: 3
                    }.coerceIn(1, 7)
                    out += "font-size" to listOf("10px", "13px", "16px", "18px", "24px", "32px", "48px")[n - 1]
                }
            }
            "td", "th", "tr", "tbody", "thead", "tfoot", "table", "img", "col" -> {
                a["align"]?.lowercase(Locale.ROOT)?.takeIf { node.tag != "table" && node.tag != "img" }?.let {
                    out += "text-align" to it
                }
                a["valign"]?.lowercase(Locale.ROOT)?.let { out += "vertical-align" to it }
                a["width"]?.let { attrLength(it) }?.let { out += "width" to it }
                a["height"]?.let { attrLength(it) }?.takeIf { node.tag == "img" || node.tag == "td" || node.tag == "th" || node.tag == "tr" }
                    ?.let { out += "height" to it }
            }
        }
        return out
    }

    private fun attrLength(v: String): String? {
        val t = v.trim()
        Regex("""^(\d+(?:\.\d+)?)\s*%$""").matchEntire(t)?.let { return "${it.groupValues[1]}%" }
        Regex("""^(\d+(?:\.\d+)?)(?:px)?$""").matchEntire(t)?.let { return "${it.groupValues[1]}px" }
        return null
    }

    /** Longhands for one declaration (`margin: 4px 0` → four `margin-*`). Unknown names pass through. */
    fun expand(name: String, value: String): List<Pair<String, String>> {
        fun tokens() = CssSelector.splitTopLevel(value.trim(), ' ').filter { it.isNotBlank() }
        fun box(prefix: String, suffix: String = ""): List<Pair<String, String>> {
            val t = tokens()
            if (t.size == 1 && t[0].lowercase(Locale.ROOT) in setOf("inherit", "initial", "unset", "revert")) {
                return SIDES.map { "$prefix-$it$suffix" to t[0] }
            }
            val (top, right, bottom, left) = when (t.size) {
                0 -> return emptyList()
                1 -> listOf(t[0], t[0], t[0], t[0])
                2 -> listOf(t[0], t[1], t[0], t[1])
                3 -> listOf(t[0], t[1], t[2], t[1])
                else -> listOf(t[0], t[1], t[2], t[3])
            }
            return listOf("$prefix-top$suffix" to top, "$prefix-right$suffix" to right, "$prefix-bottom$suffix" to bottom, "$prefix-left$suffix" to left)
        }
        fun pair(prefix: String, a: String, b: String): List<Pair<String, String>> {
            val t = tokens()
            if (t.isEmpty()) return emptyList()
            return listOf("$prefix-$a" to t[0], "$prefix-$b" to (t.getOrNull(1) ?: t[0]))
        }
        fun border(sides: List<String>): List<Pair<String, String>> {
            val t = tokens()
            var w = "medium"
            var st = "none"
            var col = "currentcolor"
            for (tok in t) {
                val l = tok.lowercase(Locale.ROOT)
                when {
                    l in BORDER_STYLES -> st = l
                    l in setOf("thin", "medium", "thick") || length(l, 16f, 0f) != null -> w = l
                    else -> col = tok
                }
            }
            return sides.flatMap { listOf("border-$it-width" to w, "border-$it-style" to st, "border-$it-color" to col) }
        }
        return when (name) {
            "margin" -> box("margin")
            "padding" -> box("padding")
            "margin-block" -> pair("margin", "top", "bottom")
            "margin-inline" -> pair("margin", "left", "right")
            "padding-block" -> pair("padding", "top", "bottom")
            "padding-inline" -> pair("padding", "left", "right")
            "margin-block-start" -> listOf("margin-top" to value)
            "margin-block-end" -> listOf("margin-bottom" to value)
            "margin-inline-start" -> listOf("margin-left" to value)
            "margin-inline-end" -> listOf("margin-right" to value)
            "padding-block-start" -> listOf("padding-top" to value)
            "padding-block-end" -> listOf("padding-bottom" to value)
            "padding-inline-start" -> listOf("padding-left" to value)
            "padding-inline-end" -> listOf("padding-right" to value)
            "border" -> border(SIDES)
            "border-top", "border-right", "border-bottom", "border-left" -> border(listOf(name.substringAfter('-')))
            "border-width" -> box("border", "-width")
            "border-style" -> box("border", "-style")
            "border-color" -> box("border", "-color")
            "background" -> {
                val l = value.trim().lowercase(Locale.ROOT)
                listOf("background-color" to if (l == "none") "none" else value)
            }
            "text-decoration" -> listOf("text-decoration-line" to value)
            "list-style" -> listOf("list-style-type" to value)
            "overflow" -> listOf("overflow" to value)
            else -> listOf(name to value)
        }
    }

    private val BORDER_STYLES = setOf("none", "hidden", "solid", "dashed", "dotted", "double", "groove", "ridge", "inset", "outset")

    private val VAR = Regex("""var\(\s*--([\w-]+)\s*(?:,\s*)?""")

    /**
     * Substitutes `var(--name, fallback)` from [custom], then [themeVars], then HA's defaults. Null when a
     * variable is missing and has no fallback: the declaration is then invalid, as in the browser.
     */
    fun resolveVars(
        value: String,
        custom: Map<String, String>,
        themeVars: Map<String, String>,
        depth: Int = 0,
        /** The properties being resolved around this value, outermost first: one met again closes a cycle. */
        visiting: List<String> = emptyList(),
        /** Every property found in a cycle so far: all of them are invalid (CSS Variables 1, §2.3). */
        cycle: MutableSet<String> = HashSet(),
    ): String? {
        if ("var(" !in value) return value
        if (depth > 8) return null
        val sb = StringBuilder()
        var i = 0
        while (i < value.length) {
            val m = VAR.find(value, i)
            if (m == null) { sb.append(value, i, value.length); break }
            sb.append(value, i, m.range.first)
            // Find the closing parenthesis of this var( … ).
            var depthP = 1
            var j = m.range.last + 1
            val fbStart = j
            while (j < value.length && depthP > 0) {
                if (value[j] == '(') depthP++ else if (value[j] == ')') depthP--
                if (depthP > 0) j++
            }
            val hasComma = value.substring(m.range.first, m.range.last + 1).contains(',')
            val fallback = if (hasComma) value.substring(fbStart, j.coerceAtMost(value.length)).trim() else null
            val name = m.groupValues[1]
            // In a cycle, the property is invalid: so is this value, whatever fallback it has here; the reference
            // outside the cycle takes its own fallback.
            val at = visiting.indexOf(name)
            if (at >= 0) {
                cycle += visiting.subList(at, visiting.size)
                return null
            }
            val found = custom[name] ?: themeVars[name] ?: HA_DEFAULT_VARS[name]
            val resolved = found?.let { resolveVars(it, custom, themeVars, depth + 1, visiting + name, cycle) }
            // Inside a cycle member's own value nothing rescues it: no fallback there.
            if (resolved == null && visiting.lastOrNull()?.let { it in cycle } == true) return null
            // A property that exists but is invalid (it needs one defined nowhere, or sits in a cycle) is as good as
            // missing: the fallback is tried. An empty but valid value stays.
            val sub = resolved
                ?: fallback?.let { resolveVars(it, custom, themeVars, depth + 1, visiting, cycle) }
                ?: return null
            sb.append(sub)
            i = j + 1
        }
        return sb.toString().trim()
    }

    /** A CSS length in px: px, em (of [em]), rem (16 px), %, pt, a bare 0, and simple `calc()`. */
    fun length(v: String, em: Float, percentBase: Float): Float? {
        val t = v.trim().lowercase(Locale.ROOT)
        if (t.startsWith("calc(") && t.endsWith(")")) return calc(t.substring(5, t.length - 1), em, percentBase)
        val m = LEN.matchEntire(t) ?: return null
        val n = m.groupValues[1].toFloatOrNull() ?: return null
        return when (m.groupValues[2]) {
            "px" -> n
            "" -> if (n == 0f) 0f else null
            "em" -> n * em
            "rem" -> n * 16f
            "%" -> n * percentBase / 100f
            "pt" -> n * 4f / 3f
            "vw", "vh" -> null
            else -> null
        }
    }

    private val LEN = Regex("""^([+-]?(?:\d+\.?\d*|\.\d+))(px|em|rem|%|pt|vw|vh)?$""")

    private fun cssLen(v: String, em: Float): CssLen? {
        val t = v.trim()
        if (t.endsWith("%")) return t.dropLast(1).trim().toFloatOrNull()?.let { CssLen(it, true) }
        return length(t, em, 0f)?.let { CssLen(it, false) }
    }

    /** `calc()` with + - * / over px/em/unitless terms, * and / binding tighter. */
    private fun calc(expr: String, em: Float, percentBase: Float): Float? =
        CalcParser(CALC_TOKEN.findAll(expr).map { it.value }.toList(), em, percentBase).sum()

    private val CALC_TOKEN = Regex("""[()*/+]|-(?=\s)|[+-]?(?:\d+\.?\d*|\.\d+)(?:px|em|rem|%|pt)?""")

    private class CalcParser(val tokens: List<String>, val em: Float, val percentBase: Float) {
        var pos = 0

        fun term(): Float? {
            val t = tokens.getOrNull(pos++) ?: return null
            if (t == "(") {
                val v = sum() ?: return null
                if (tokens.getOrNull(pos) == ")") pos++
                return v
            }
            return t.toFloatOrNull() ?: length(t, em, percentBase)
        }

        fun product(): Float? {
            var v = term() ?: return null
            while (pos < tokens.size && (tokens[pos] == "*" || tokens[pos] == "/")) {
                val op = tokens[pos++]
                val r = term() ?: return null
                v = if (op == "*") v * r else if (r != 0f) v / r else return null
            }
            return v
        }

        fun sum(): Float? {
            var v = product() ?: return null
            while (pos < tokens.size && (tokens[pos] == "+" || tokens[pos] == "-")) {
                val op = tokens[pos++]
                val r = product() ?: return null
                v = if (op == "+") v + r else v - r
            }
            return v
        }
    }
}
