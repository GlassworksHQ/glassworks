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
    private val COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
    private val IMPORT = Regex("""@import\s+(?:url\(\s*(["']?)(.*?)\1\s*\)|(["'])(.*?)\3)""", RegexOption.IGNORE_CASE)
    private val FONT_FACE = Regex("""@font-face\s*\{""", RegexOption.IGNORE_CASE)
    private val SRC_URL = Regex("""url\(\s*(["']?)(.*?)\1\s*\)""", RegexOption.IGNORE_CASE)
    private val SRC_FORMAT = Regex("""format\(\s*(["']?)(.*?)\1\s*\)""", RegexOption.IGNORE_CASE)

    fun parse(css: String, sheetUrl: String): FontSheet {
        val text = COMMENT.replace(css, " ")
        val imports = IMPORT.findAll(text).mapNotNull { m ->
            val raw = m.groupValues[2].ifEmpty { m.groupValues[4] }.trim()
            resolve(sheetUrl, raw)
        }.toList()
        val faces = ArrayList<FontFace>()
        for (m in FONT_FACE.findAll(text)) {
            val start = m.range.last + 1
            val end = blockEnd(text, start) ?: break
            parseFace(text.substring(start, end), sheetUrl)?.let(faces::add)
        }
        return FontSheet(faces, imports)
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
            val url = SRC_URL.find(entry)?.groupValues?.get(2)?.trim() ?: return@mapNotNull null
            val abs = resolve(sheetUrl, url) ?: return@mapNotNull null
            FontSource(abs, SRC_FORMAT.find(entry)?.groupValues?.get(2)?.trim()?.lowercase(Locale.ROOT))
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
    fun families(fontFamily: String): List<String> =
        splitTopLevel(fontFamily, ',').map(::unquote).filter { it.isNotEmpty() }

    private fun unquote(v: String): String = v.trim().trim('"', '\'').trim()

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
                    if (c == quote) quote = 0.toChar()
                    cur.append(c)
                }
                c == '"' || c == '\'' -> { quote = c; cur.append(c) }
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
