/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.data


/**
 * HA's frontend UI strings (`hass.localize` for `ui.*`, `state.default.*`, `state_badge.*`), in the user's language
 * ([HaLocale]), from the signed-in Home Assistant's own translation files ([HaFrontend] fetches and caches them). The
 * frontend's build fills a language's missing keys with English, so one language's file is all there is to read.
 *
 * Until HA's files have been read once (a first start with no cache), a key HA's page would show is empty, as
 * `localize` is before its resources load, except [BUILT_IN].
 *
 * Placeholders are HA's message-format arguments: `{name}` is replaced with [ui]'s argument of that name, and a
 * `{count, plural, one {…} other {…}}` block picks its branch by the language's plural rules, with `#` the count.
 */
object HaText {
    /**
     * The only English Vitro carries itself: what it must be able to show on a very first start, before any of HA's
     * translation files has been fetched or cached (a state it can't read, a connection it lost), and the names
     * TalkBack reads for icon-only buttons, which would otherwise have none until then. Everything else comes from HA.
     */
    private val BUILT_IN = mapOf(
        "state.default.unavailable" to "Unavailable",
        "state.default.unknown" to "Unknown",
        "ui.notification_toast.connection_lost" to "Connection lost. Reconnecting…",
        // The tile's cover-open-close buttons (HA's own English for these keys).
        "ui.card.cover.open_cover" to "Open cover",
        "ui.card.cover.stop_cover" to "Stop cover",
        "ui.card.cover.close_cover" to "Close cover",
    )

    /**
     * English on the classpath, when there is one: the unit tests put HA's own English for the keys Vitro names
     * there (`src/test/resources`), so a card reads in them as on a panel that has read HA once. The APK has none.
     */
    private val classpathEnglish: Map<String, String> by lazy {
        runCatching {
            HaText::class.java.getResourceAsStream("/dev/glassworks/engine/vitro/locale/strings-en.json")?.use { s ->
                val o = org.json.JSONObject(s.readBytes().toString(Charsets.UTF_8))
                HashMap<String, String>(o.length() * 2).apply { for (k in o.keys()) put(k, o.optString(k)) }
            }
        }.getOrNull().orEmpty()
    }

    @Volatile private var strings: Map<String, String> = emptyMap()

    /** The language [strings] are in; null before any are installed. */
    @Volatile var language: String? = null
        private set

    /** Counts installs: a view drawn with an older version draws again. */
    @Volatile var version: Long = 0
        private set

    /** HA's strings for [language] ([HaFrontend], or a test's). */
    fun install(language: String, strings: Map<String, String>) {
        this.strings = strings
        this.language = language
        version++
    }

    /** Every string installed now ([install]), keyed as HA's `localize` reads them: what a web island's page gets. */
    fun installed(): Map<String, String> = strings

    /** Forgets the installed strings (a new server, or tests). */
    fun reset() {
        strings = emptyMap()
        language = null
        version++
    }

    /** The string for [key] with [args] filled in: HA's installed strings, else [BUILT_IN], else empty. */
    fun ui(key: String, vararg args: Pair<String, Any?>, language: String = HaLocale.current.language): String {
        val raw = strings[key] ?: classpathEnglish[key] ?: BUILT_IN[key] ?: return ""
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
