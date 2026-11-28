/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.fonts

import java.net.URI
import java.util.Locale

/** One `src` entry of an `@font-face` rule: an absolute URL (or a `data:` URI) and its `format()` hint, lowercased. */
data class FontSource(val url: String, val format: String?) {
    /**
     * Whether Vitro can draw this source: TrueType, OpenType and WOFF. WOFF2 needs Brotli and its own table transforms,
     * which Vitro doesn't decode; a browser picks the first source it supports, and so does Vitro.
     */
    val supported: Boolean
        get() {
            val f = format?.substringBefore('-')
            if (f != null) return f in SUPPORTED_FORMATS
            val path = url.substringBefore('?').substringBefore('#').lowercase(Locale.ROOT)
            if (url.startsWith("data:")) return !url.startsWith("data:font/woff2") && !url.startsWith("data:application/font-woff2")
            return !path.endsWith(".woff2")
        }

    companion object {
        private val SUPPORTED_FORMATS = setOf("truetype", "opentype", "woff", "ttf", "otf", "collection")
    }
}

/**
 * One `@font-face` rule: the family it names (lowercased, unquoted), its weight range (a static face has
 * `weightMin == weightMax`; a variable one declares `font-weight: 100 900`), whether it's italic, and its sources.
 */
data class FontFace(
    val family: String,
    val weightMin: Int,
    val weightMax: Int,
    val italic: Boolean,
    val sources: List<FontSource>,
    /** `unicode-range` as inclusive code point ranges; empty means every character. */
    val unicodeRanges: List<IntRange> = emptyList(),
) {
    val variable: Boolean get() = weightMin < weightMax

    /** The first source Vitro can draw, or null when the face has none (WOFF2 only, `local()` only). */
    val source: FontSource? get() = sources.firstOrNull { it.supported }

    /** Every source Vitro can draw, in declaration order: tried one after another until one loads. */
    val supportedUrls: List<String> get() = sources.filter { it.supported }.map { it.url }

    /** Whether the face covers Latin letters: of several faces split by `unicode-range` (Google Fonts), the one to use. */
    val coversLatin: Boolean get() = unicodeRanges.isEmpty() || unicodeRanges.any { 'A'.code in it }
}

/** A parsed stylesheet: its `@font-face` rules and the sheets it `@import`s (absolute URLs). */
data class FontSheet(val faces: List<FontFace>, val imports: List<String>)

/**
 * Reads the font rules of a stylesheet the way the browser does when a Lovelace `type: css` resource puts it in the
 * page's `<head>` (HA's `loadCSS`): `@font-face` anywhere in the sheet (also inside `@media` or `@supports`, which
 * Vitro treats as always matching) and `@import`. URLs resolve against the sheet's own URL. Nothing else in the
 * sheet matters for fonts.
 */
object CssFontFaces {
    private val IMPORT = Regex("""@import\s+(?:url\(\s*(["']?)(.*?)\1\s*\)|(["'])(.*?)\3)""", RegexOption.IGNORE_CASE)
    private val FONT_FACE = Regex("""@font-face\s*\{""", RegexOption.IGNORE_CASE)
    private val HEX_ESCAPE = Regex("""\\([0-9a-fA-F]{1,6})\s?|\\(.)""")
    private val SRC_URL = Regex("""url\(\s*(["']?)(.*?)\1\s*\)""", RegexOption.IGNORE_CASE)
    private val SRC_FORMAT = Regex("""format\(\s*(["']?)(.*?)\1\s*\)""", RegexOption.IGNORE_CASE)

    fun parse(css: String, sheetUrl: String): FontSheet {
        val (text, atRules) = scan(css)
        val imports = ArrayList<String>()
        val faces = ArrayList<FontFace>()
        val importAt = IMPORT.toPattern().matcher(text)
        val faceAt = FONT_FACE.toPattern().matcher(text)
        // `@import` counts only before the sheet's first block: after a rule the browser ignores it.
        val firstBlock = text.indexOf('{').let { if (it < 0) Int.MAX_VALUE else it }
        for (at in atRules) {
            // Anchored at the `@`: a rule that doesn't start right there is no match, with no search ahead.
            if (importAt.region(at, text.length).lookingAt()) {
                if (at > firstBlock) continue
                val raw = (importAt.group(2)?.takeIf { it.isNotEmpty() } ?: importAt.group(4)).orEmpty().trim()
                resolve(sheetUrl, decodeEscapes(raw))?.let(imports::add)
            } else if (faceAt.region(at, text.length).lookingAt()) {
                val start = faceAt.end()
                val end = blockEnd(text, start) ?: continue
                parseFace(text.substring(start, end), sheetUrl)?.let(faces::add)
            }
        }
        return FontSheet(faces, imports)
    }

    /**
     * [css] with its comments blanked, and where an at-rule (`@`) starts outside a string: text that only looks like
     * a rule inside a string (`content: "@font-face { ... }"`) or a comment is never read as one.
     */
    private fun scan(css: String): Pair<String, List<Int>> {
        val out = StringBuilder(css.length)
        val atRules = ArrayList<Int>()
        var quote = 0.toChar()
        var i = 0
        while (i < css.length) {
            val c = css[i]
            when {
                quote != 0.toChar() -> {
                    out.append(c)
                    if (c == '\\' && i + 1 < css.length) out.append(css[++i]) else if (c == quote) quote = 0.toChar()
                }
                c == '/' && i + 1 < css.length && css[i + 1] == '*' -> {
                    val close = css.indexOf("*/", i + 2).let { if (it < 0) css.length else it + 2 }
                    repeat(close - i) { out.append(' ') }
                    i = close - 1
                }
                c == '"' || c == '\'' -> { quote = c; out.append(c) }
                c == '@' -> { atRules += out.length; out.append(c) }
                else -> out.append(c)
            }
            i++
        }
        return out.toString() to atRules
    }

    /** The index of the `}` that closes the block opened just before [start], skipping strings and parentheses. */
    private fun blockEnd(text: String, start: Int): Int? {
        var depth = 0
        var quote = 0.toChar()
        var i = start
        while (i < text.length) {
            val c = text[i]
            when {
                quote != 0.toChar() -> if (c == quote) quote = 0.toChar() else if (c == '\\') i++
                c == '"' || c == '\'' -> quote = c
                c == '(' || c == '{' -> depth++
                c == ')' -> depth = maxOf(0, depth - 1)
                c == '}' -> if (depth == 0) return i else depth--
            }
            i++
        }
        return null
    }

    private fun parseFace(body: String, sheetUrl: String): FontFace? {
        val decls = HashMap<String, String>()
        for (d in splitTopLevel(body, ';')) {
            val colon = d.indexOf(':')
            if (colon <= 0) continue
            decls[d.substring(0, colon).trim().lowercase(Locale.ROOT)] = d.substring(colon + 1).trim()
        }
        val family = decls["font-family"]?.let(::unquote)?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        val src = decls["src"] ?: return null
        val sources = splitTopLevel(src, ',').mapNotNull { entry ->
            // A local() entry names an installed font (never one on a panel), even if its name holds "url(".
            if (entry.trimStart().startsWith("local(", ignoreCase = true)) return@mapNotNull null
            val m = SRC_URL.find(entry) ?: return@mapNotNull null
            // CSS escapes decoded first (`my\20 font.ttf` is "my font.ttf"); percent-encoded bytes stay as they are.
            val abs = resolve(sheetUrl, decodeEscapes(m.groupValues[2].trim())) ?: return@mapNotNull null
            // The hint follows the url(): "format(" inside the URL itself is part of its path.
            val format = SRC_FORMAT.find(entry, m.range.last + 1)?.groupValues?.get(2)?.trim()?.lowercase(Locale.ROOT)
            FontSource(abs, format)
        }
        if (sources.isEmpty()) return null
        val (wMin, wMax) = parseWeightRange(decls["font-weight"])
        val style = decls["font-style"]?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return FontFace(
            family = family,
            weightMin = wMin,
            weightMax = wMax,
            italic = style.startsWith("italic") || style.startsWith("oblique"),
            sources = sources,
            unicodeRanges = decls["unicode-range"]?.let(::parseUnicodeRanges).orEmpty(),
        )
    }

    /** `font-weight` in an `@font-face`: `normal`, `bold`, a number, or a range (`100 900`). 400 when absent. */
    fun parseWeightRange(value: String?): Pair<Int, Int> {
        val parts = value?.trim()?.lowercase(Locale.ROOT)?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }.orEmpty()
        fun one(v: String): Int? = when (v) {
            "normal" -> 400
            "bold" -> 700
            else -> v.toFloatOrNull()?.toInt()?.coerceIn(1, 1000)
        }
        val a = parts.getOrNull(0)?.let(::one) ?: return 400 to 400
        val b = parts.getOrNull(1)?.let(::one) ?: a
        return minOf(a, b) to maxOf(a, b)
    }

    private fun parseUnicodeRanges(value: String): List<IntRange> = value.split(',').mapNotNull { r ->
        val t = r.trim().removePrefix("U+").removePrefix("u+")
        when {
            '-' in t -> {
                val lo = t.substringBefore('-').toIntOrNull(16) ?: return@mapNotNull null
                val hi = t.substringAfter('-').toIntOrNull(16) ?: return@mapNotNull null
                lo..hi
            }
            '?' in t -> {
                val lo = t.replace('?', '0').toIntOrNull(16) ?: return@mapNotNull null
                val hi = t.replace('?', 'F').toIntOrNull(16) ?: return@mapNotNull null
                lo..hi
            }
            else -> t.toIntOrNull(16)?.let { it..it }
        }
    }

    /** The families of a CSS `font-family` list, unquoted, in order (`"Lora", serif` → `Lora`, `serif`). */
    fun families(fontFamily: String): List<String> = familyList(fontFamily).map { it.name }

    /** One family of a `font-family` list: its name, and whether it's a generic keyword (bare `serif`, not `"serif"`). */
    data class Family(val name: String, val generic: Boolean)

    private val GENERIC = setOf(
        "serif", "sans-serif", "monospace", "cursive", "fantasy", "system-ui", "ui-serif", "ui-sans-serif",
        "ui-monospace", "ui-rounded", "math", "emoji", "fangsong",
    )

    /** A `font-family` list as CSS reads it: a quoted name is always a family, a bare generic keyword never is. */
    fun familyList(fontFamily: String): List<Family> = splitTopLevel(fontFamily, ',').mapNotNull { raw ->
        val t = raw.trim()
        val quoted = t.startsWith('"') || t.startsWith('\'')
        val name = unquote(t).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        Family(name, generic = !quoted && name.lowercase(Locale.ROOT) in GENERIC)
    }

    /**
     * A family name as CSS reads it: quotes off, escapes decoded (`"Pilot\" Sans"`, `\26` for `&`). Unquoted, its
     * identifiers are joined by one space (`Brand   Sans` is "Brand Sans"); an escaped space stays.
     */
    private fun unquote(v: String): String {
        val t = v.trim()
        val quoted = t.length >= 2 && (t[0] == '"' || t[0] == '\'') && t.last() == t[0]
        val inner = if (quoted) t.substring(1, t.length - 1) else joinIdentifiers(t)
        return decodeEscapes(inner).trim()
    }

    /**
     * Unquoted identifiers joined by one space, escape-aware: a hex escape keeps the one whitespace that ends it
     * (`Brand\26  Sans` is `Brand\26 ` then ` Sans`, so "Brand& Sans"), and an escaped character stays as it is.
     */
    private fun joinIdentifiers(t: String): String {
        val sb = StringBuilder(t.length)
        var space = false
        var i = 0
        fun flush() { if (space && sb.isNotEmpty()) sb.append(' '); space = false }
        while (i < t.length) {
            val c = t[i]
            if (c == '\\' && i + 1 < t.length) {
                flush()
                var j = i + 1
                while (j < t.length && j - i <= 6 && t[j].isHexDigitChar()) j++
                if (j > i + 1) {
                    sb.append(t, i, j)
                    if (j < t.length && t[j].isWhitespace()) { sb.append(t[j]); j++ }
                    i = j
                } else {
                    sb.append(c).append(t[i + 1])
                    i += 2
                }
                continue
            }
            if (c.isWhitespace()) { space = true; i++; continue }
            flush()
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private fun Char.isHexDigitChar(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    /** CSS escapes decoded (`\20 ` a space, `\"` a quote); everything else, percent-encoding included, as it is. */
    private fun decodeEscapes(inner: String): String =
        HEX_ESCAPE.replace(inner) { m ->
            m.groupValues[1].takeIf { it.isNotEmpty() }?.toIntOrNull(16)
                ?.let { cp -> if (cp in 1..0x10FFFF) String(Character.toChars(cp)) else "\uFFFD" }
                ?: m.groupValues[2]
        }

    /** [s] split on [sep] outside quotes and parentheses (a `data:` URI holds `;` and `,`). */
    fun splitTopLevel(s: String, sep: Char): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var quote = 0.toChar()
        val cur = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                quote != 0.toChar() -> {
                    cur.append(c)
                    // An escaped character (`\\"`) never ends the string.
                    if (c == '\\' && i + 1 < s.length) cur.append(s[++i]) else if (c == quote) quote = 0.toChar()
                }
                c == '"' || c == '\'' -> { quote = c; cur.append(c) }
                // An escaped character (`Foo\, Bar`) is part of the name, a separator included.
                c == '\\' && i + 1 < s.length -> { cur.append(c).append(s[++i]) }
                c == '(' -> { depth++; cur.append(c) }
                c == ')' -> { depth = maxOf(0, depth - 1); cur.append(c) }
                c == sep && depth == 0 -> { out += cur.toString().trim(); cur.setLength(0) }
                else -> cur.append(c)
            }
            i++
        }
        if (cur.isNotBlank()) out += cur.toString().trim()
        return out.filter { it.isNotEmpty() }
    }

    /** [ref] against [base], as the browser resolves a URL in a stylesheet; null for anything but http(s) and data. */
    fun resolve(base: String, ref: String): String? {
        if (ref.isEmpty()) return null
        if (ref.startsWith("data:", ignoreCase = true)) return ref
        return try {
            val u = URI(base).resolve(ref.replace(" ", "%20"))
            if (u.scheme?.lowercase(Locale.ROOT) in setOf("http", "https")) u.toString() else null
        } catch (_: Exception) {
            null
        }
    }
}
