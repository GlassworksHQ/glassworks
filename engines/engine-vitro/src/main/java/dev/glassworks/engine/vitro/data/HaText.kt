/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.data


/**
 * HA's frontend UI strings (`hass.localize` for `ui.*`, `state.default.*`, `state_badge.*`), in the user's language
 * ([HaLocale]), from the pinned frontend's own translation files. `tools/vitro-parity/locale-data.mjs` copies every
 * key Vitro's code names into each language's `resources/dev/glassworks/engine/vitro/locale/<language>.json`
 * (`strings`, only where it differs from English); a key a language lacks is English's, as the frontend's build fills it.
 *
 * Placeholders are HA's message-format arguments: `{name}` is replaced with [ui]'s argument of that name, and a
 * `{count, plural, one {…} other {…}}` block picks its branch by the language's plural rules, with `#` the count.
 */
object HaText {
    private fun table(language: String): Map<String, String> = HaIntl.of(language).strings

    /** True when English has [key]: every key Vitro uses does (a test checks it). */
    fun has(key: String): Boolean = key in table("en")

    /** The string for [key] in [language] (the user's by default), with [args] filled in; the key itself if unknown. */
    fun ui(key: String, vararg args: Pair<String, Any?>, language: String = HaLocale.current.language): String {
        val raw = table(language)[key] ?: table("en")[key] ?: return key
        return if (args.isEmpty() && '{' !in raw) raw else format(raw, args.toMap(), language)
    }

    /** Fills HA's message-format [pattern]: `{name}` and `{name, plural, … }`. */
    fun format(pattern: String, args: Map<String, Any?>, language: String = HaLocale.current.language): String {
        val sb = StringBuilder()
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            if (c == '\'' && i + 1 < pattern.length && pattern[i + 1] == '\'') { sb.append('\''); i += 2; continue }
            if (c != '{') { sb.append(c); i++; continue }
            val end = matching(pattern, i)
            if (end < 0) { sb.append(pattern, i, pattern.length); break }
            val body = pattern.substring(i + 1, end)
            val name = body.substringBefore(',').trim()
            val value = args[name]
            if (',' in body && body.substringAfter(',').trim().startsWith("plural")) {
                sb.append(plural(body.substringAfter(',').trim().removePrefix("plural").trimStart().removePrefix(",").trim(), value, args, language))
            } else if (',' in body && body.substringAfter(',').trim().startsWith("select")) {
                sb.append(select(body.substringAfter(',').trim().removePrefix("select").trimStart().removePrefix(",").trim(), value, args, language))
            } else {
                sb.append(value?.toString() ?: "")
            }
            i = end + 1
        }
        return sb.toString()
    }

    private fun matching(s: String, open: Int): Int {
        var depth = 0
        for (j in open until s.length) {
            when (s[j]) { '{' -> depth++; '}' -> { depth--; if (depth == 0) return j } }
        }
        return -1
    }

    /** `{selector {text} …}` pairs. */
    private fun options(s: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var i = 0
        while (i < s.length) {
            while (i < s.length && s[i].isWhitespace()) i++
            val open = s.indexOf('{', i)
            if (open < 0) break
            val sel = s.substring(i, open).trim()
            val close = matching(s, open)
            if (close < 0) break
            out[sel] = s.substring(open + 1, close)
            i = close + 1
        }
        return out
    }

    private fun plural(spec: String, value: Any?, args: Map<String, Any?>, language: String): String {
        val n = (value as? Number)?.toLong() ?: value?.toString()?.toLongOrNull() ?: 0L
        val opts = options(spec)
        val category = when (HaIntl.of(language).pluralOf(n)) {
            'z' -> "zero"; '1' -> "one"; '2' -> "two"; 'f' -> "few"; 'm' -> "many"; else -> "other"
        }
        val text = opts["=$n"] ?: opts[category] ?: opts["other"] ?: ""
        return format(text, args, language).replace("#", HaIntl.of(language).formatInteger(n))
    }

    private fun select(spec: String, value: Any?, args: Map<String, Any?>, language: String): String {
        val opts = options(spec)
        return format(opts[value?.toString()] ?: opts["other"] ?: "", args, language)
    }
}
