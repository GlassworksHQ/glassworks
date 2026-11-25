/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.data

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

/**
 * The browser's `Intl`, as HA's frontend uses it, for the languages the pinned frontend ships. Vitro has no browser,
 * so `tools/vitro-parity/locale-data.mjs` asks the Chromium the parity harness renders HA with for the answers and
 * writes them to `resources/dev/glassworks/engine/vitro/locale/intl/<tag>.json`: number symbols and grouping, plural
 * categories, relative-time phrases, and a template per `Intl.DateTimeFormat` option set HA's datetime functions use.
 * This class reads those tables and formats with them; [HaFormat] is HA's functions on top.
 *
 * A tag with no table falls back the way `Intl` does: to its language, then to English.
 */
object HaIntl {
    private const val ROOT = "/dev/glassworks/engine/vitro/locale"

    /** The languages HA's frontend has translations for (`translationMetadata`), in its own spelling. */
    val languages: List<String> by lazy {
        val arr = readJson("$ROOT/languages.json")?.optJSONArray("languages")
        if (arr == null) listOf("en") else List(arr.length()) { arr.optString(it) }
    }

    private val tags: Set<String> by lazy {
        val arr = readJson("$ROOT/languages.json")?.optJSONArray("tags") ?: return@lazy setOf("en")
        HashSet<String>().apply { for (i in 0 until arr.length()) add(arr.optString(i)) }
    }

    private val cache = ConcurrentHashMap<String, Data>()

    /** The table for [tag], or the nearest one `Intl` would use: the tag, its language, English. */
    fun of(tag: String?): Data {
        val t = resolve(tag)
        return cache.getOrPut(t) { Data(readJson("$ROOT/intl/$t.json") ?: JSONObject()) }
    }

    /** The table [tag] uses: the tag itself, then shorter tags, then English (`Intl`'s lookup). */
    fun resolve(tag: String?): String {
        var t = tag?.takeIf { it.isNotEmpty() } ?: return "en"
        while (true) {
            if (t in tags) return t
            tags.firstOrNull { it.equals(t, ignoreCase = true) }?.let { return it }
            if ('-' !in t) return "en"
            t = t.substringBeforeLast('-')
        }
    }

    internal fun readJson(path: String): JSONObject? = runCatching {
        HaIntl::class.java.getResourceAsStream(path)?.use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
    }.getOrNull()

    /** One locale's table. */
    class Data(json: JSONObject) {
        private val number = json.optJSONObject("number") ?: JSONObject()
        val decimal: String = number.optString("decimal", ".")
        val group: String = number.optString("group", ",")
        val minus: String = number.optString("minus", "-")
        private val minusPrefix: String = number.optString("prefix", "")
        private val primary: Int = number.optInt("primary", 3).coerceAtLeast(1)
        private val secondary: Int = number.optInt("secondary", 3).coerceAtLeast(1)
        private val minGrouping: Int = number.optInt("minGrouping", 1).coerceAtLeast(1)
        private val zero: Int = number.optString("zero", "0").ifEmpty { "0" }.codePointAt(0)
        private val plural: String = json.optString("plural", "")
        private val relative = json.optJSONObject("relative") ?: JSONObject()
        private val unit = json.optJSONObject("unit") ?: JSONObject()
        private val date = json.optJSONObject("date") ?: JSONObject()
        val amPm: Boolean = json.optBoolean("amPm", false)
        /** `Intl.Locale(...).weekInfo.firstDay % 7`: 0 is Sunday. */
        val firstDay: Int = json.optInt("firstDay", 1)
        private val buddhist = json.optString("calendar") == "buddhist"
        private val dateZero: Int = json.optString("dateZero", "0").ifEmpty { "0" }.codePointAt(0)

        /**
         * `Intl.NumberFormat(locale, {minimumFractionDigits, maximumFractionDigits, useGrouping}).format(value)`,
         * rounding half away from zero (`halfExpand`). [negative] carries a negative zero's sign ("-0", as `Intl`
         * writes it).
         */
        fun formatNumber(value: BigDecimal, minDigits: Int, maxDigits: Int, grouping: Boolean = true, negative: Boolean = value.signum() < 0): String {
            val max = maxDigits.coerceIn(0, 20)
            val min = minDigits.coerceIn(0, max)
            var r = value.abs().setScale(max, RoundingMode.HALF_UP)
            // Drop trailing zeros down to the minimum.
            while (r.scale() > min && r.unscaledValue().mod(java.math.BigInteger.TEN).signum() == 0) r = r.setScale(r.scale() - 1)
            val plain = r.toPlainString()
            val intPart = plain.substringBefore('.')
            val frac = if ('.' in plain) plain.substringAfter('.') else ""
            val sb = StringBuilder()
            if (negative) sb.append(minusPrefix).append(minus)
            sb.append(digits(if (grouping) groupDigits(intPart) else intPart))
            if (frac.isNotEmpty()) sb.append(decimal).append(digits(frac))
            return sb.toString()
        }

        private fun groupDigits(intPart: String): String {
            if (intPart.length < primary + minGrouping) return intPart
            val out = ArrayList<String>()
            var end = intPart.length
            out += intPart.substring(end - primary, end)
            end -= primary
            while (end > 0) {
                val start = (end - secondary).coerceAtLeast(0)
                out += intPart.substring(start, end)
                end = start
            }
            return out.asReversed().joinToString(GROUP_MARK)
        }

        /** ASCII digits (and [GROUP_MARK]) to this locale's digits and group mark. */
        private fun digits(s: String): String {
            if (zero == '0'.code && GROUP_MARK !in s) return s
            val sb = StringBuilder(s.length)
            for (c in s) {
                when {
                    c == GROUP_MARK[0] -> sb.append(group)
                    c in '0'..'9' -> sb.appendCodePoint(zero + (c - '0'))
                    else -> sb.append(c)
                }
            }
            return sb.toString()
        }

        /** An integer as `Intl.NumberFormat` writes it in this locale: grouped, in its digits. */
        fun formatInteger(n: Long): String = formatNumber(BigDecimal.valueOf(n), 0, 0)

        /** `Intl.PluralRules(locale).select(n)` for an integer: z, 1, 2, f, m or o. */
        fun pluralOf(n: Long): Char {
            if (plural.isEmpty()) return if (n == 1L) '1' else 'o'
            val a = kotlin.math.abs(n)
            val i = if (a < plural.length) a.toInt() else 100 + (a % 100).toInt()
            return plural.getOrElse(i) { 'o' }
        }

        /** `Intl.RelativeTimeFormat(locale, {numeric: "auto", style}).format(value, unit)`. */
        fun relativeTime(value: Long, unit: String, style: String = "long"): String {
            val u = relative.optJSONObject(style)?.optJSONObject(unit) ?: relative.optJSONObject("long")?.optJSONObject(unit)
                ?: return "$value $unit"
            u.optJSONObject("special")?.optString(value.toString())?.takeIf { it.isNotEmpty() }?.let { return it }
            val side = u.optJSONObject(if (value < 0) "past" else "future") ?: return "$value $unit"
            val pattern = side.optString(pluralOf(value).toString()).ifEmpty { side.optString("o") }
            return pattern.replace("{0}", formatInteger(kotlin.math.abs(value)))
        }

        /** `Intl.NumberFormat(locale, {style: "unit", unit, unitDisplay: style}).format(n)` for an integer. */
        fun unitAmount(n: Long, unit: String, style: String = "long"): String {
            val u = this.unit.optJSONObject(style)?.optJSONObject(unit) ?: return "$n $unit"
            val pattern = u.optString(pluralOf(n).toString()).ifEmpty { u.optString("o") }
            return pattern.replace("{0}", formatInteger(n))
        }

        /** The template HA's option set [name] gets (`time.h12`, `date`, …), or null when the table has none. */
        fun dateFormat(name: String): DateTemplate? = date.optJSONObject(name)?.let { DateTemplate(it, dateZero, buddhist) }
    }

    /**
     * One `Intl.DateTimeFormat`: its fields and literals, as captured, and the names its month, weekday and day-period
     * fields take in this format.
     */
    class DateTemplate(json: JSONObject, private val zero: Int, private val buddhist: Boolean) {
        val tokens: List<Token> = parse(json.optString("t"))
        private val months = json.optJSONArray("months").strings()
        private val weekdays = json.optJSONArray("weekdays").strings()
        private val dayPeriods = json.optJSONArray("dayPeriods").strings()

        sealed class Token {
            data class Literal(val text: String) : Token()
            data class Field(val name: String) : Token()
        }

        /** The date at [ms] in [zone], as `format(date)`. */
        fun format(ms: Long, zone: TimeZone): String = tokens.joinToString("") { t ->
            when (t) {
                is Token.Literal -> t.text
                is Token.Field -> field(t.name, ms, zone)
            }
        }

        /** Each field's text, in order: `formatToParts` with the field names of the template. */
        fun parts(ms: Long, zone: TimeZone): List<Pair<String, String>> = tokens.map { t ->
            when (t) {
                is Token.Literal -> "literal" to t.text
                is Token.Field -> t.name to field(t.name, ms, zone)
            }
        }

        fun field(name: String, ms: Long, zone: TimeZone): String {
            val c = Calendar.getInstance(zone).apply { timeInMillis = ms }
            val hour = c.get(Calendar.HOUR_OF_DAY)
            return when (name) {
                "y" -> num(c.get(Calendar.YEAR) + if (buddhist) 543 else 0, 1)
                "yy" -> num((c.get(Calendar.YEAR) + if (buddhist) 543 else 0) % 100, 2)
                "M" -> num(c.get(Calendar.MONTH) + 1, 1)
                "MM" -> num(c.get(Calendar.MONTH) + 1, 2)
                "Mt" -> months.getOrElse(c.get(Calendar.MONTH)) { "" }
                "d" -> num(c.get(Calendar.DAY_OF_MONTH), 1)
                "dd" -> num(c.get(Calendar.DAY_OF_MONTH), 2)
                "E" -> weekdays.getOrElse(c.get(Calendar.DAY_OF_WEEK) - 1) { "" }
                "H" -> num(hour, 1)
                "HH" -> num(hour, 2)
                "h" -> num(if (hour % 12 == 0) 12 else hour % 12, 1)
                "hh" -> num(if (hour % 12 == 0) 12 else hour % 12, 2)
                "mm" -> num(c.get(Calendar.MINUTE), 2)
                "ss" -> num(c.get(Calendar.SECOND), 2)
                "a" -> dayPeriods.getOrElse(if (hour < 12) 0 else 1) { "" }
                else -> ""
            }
        }

        private fun num(v: Int, width: Int): String {
            val s = v.toString().padStart(width, '0')
            if (zero == '0'.code) return s
            val sb = StringBuilder()
            for (ch in s) sb.appendCodePoint(zero + (ch - '0'))
            return sb.toString()
        }

        private companion object {
            fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else List(length()) { optString(it) }

            fun parse(t: String): List<Token> {
                val out = ArrayList<Token>()
                val lit = StringBuilder()
                var i = 0
                while (i < t.length) {
                    val c = t[i]
                    if (c == '{' && i + 1 < t.length && t[i + 1] == '{') { lit.append('{'); i += 2; continue }
                    if (c == '{') {
                        val end = t.indexOf('}', i)
                        if (end > i) {
                            if (lit.isNotEmpty()) { out += Token.Literal(lit.toString()); lit.clear() }
                            out += Token.Field(t.substring(i + 1, end))
                            i = end + 1
                            continue
                        }
                    }
                    lit.append(c)
                    i++
                }
                if (lit.isNotEmpty()) out += Token.Literal(lit.toString())
                return out
            }
        }
    }

    /** Stands for a group mark between digit groups until [Data.digits] writes the locale's. */
    private const val GROUP_MARK = "\u0001"
}
