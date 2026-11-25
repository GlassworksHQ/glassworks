/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.data

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

/**
 * [HaIntl]'s bundled source: what Chromium's `Intl` answers for every language HA's frontend ships, captured by
 * `tools/vitro-parity/locale-data.mjs` into `resources/dev/glassworks/engine/vitro/locale/<tag>.json` (number marks
 * and grouping, plural categories of 0-199, long relative-time phrases and unit amounts, and a template per
 * `Intl.DateTimeFormat` option set HA's datetime functions use, with the month, weekday and day-period names they
 * take). `languages.json` lists the tags. A tag with no table falls back the way `Intl` does: to a shorter tag, then
 * to English.
 */
object TableIntlSource : HaIntl.Source {
    private const val ROOT = "/dev/glassworks/engine/vitro/locale"

    private val tags: Set<String> by lazy {
        val arr = readJson("$ROOT/languages.json")?.optJSONArray("tags") ?: return@lazy setOf("en")
        HashSet<String>().apply { for (i in 0 until arr.length()) add(arr.optString(i)) }
    }

    /** The languages the pinned frontend had when the tables were captured (its `translationMetadata`). */
    val languages: List<String> by lazy {
        val arr = readJson("$ROOT/languages.json")?.optJSONArray("languages")
        if (arr == null) listOf("en") else List(arr.length()) { arr.optString(it) }
    }

    override fun data(tag: String): HaIntl.Data = Table(readJson("$ROOT/${resolve(tag)}.json") ?: JSONObject())

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

    private fun readJson(path: String): JSONObject? = runCatching {
        TableIntlSource::class.java.getResourceAsStream(path)?.use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
    }.getOrNull()

    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else List(length()) { optString(it) }

    private class Table(json: JSONObject) : HaIntl.Data {
        private val number = json.optJSONObject("number") ?: JSONObject()
        override val decimal: String = number.optString("decimal", ".")
        override val group: String = number.optString("group", ",")
        override val minus: String = number.optString("minus", "-")
        private val minusPrefix: String = number.optString("prefix", "")
        private val primary: Int = number.optInt("primary", 3).coerceAtLeast(1)
        private val secondary: Int = number.optInt("secondary", 3).coerceAtLeast(1)
        private val minGrouping: Int = number.optInt("minGrouping", 1).coerceAtLeast(1)
        private val zero: Int = number.optString("zero", "0").ifEmpty { "0" }.codePointAt(0)
        private val plural: String = json.optString("plural", "")
        private val relative = json.optJSONObject("relative") ?: JSONObject()
        private val unit = json.optJSONObject("unit") ?: JSONObject()
        private val date = json.optJSONObject("date") ?: JSONObject()
        private val names: List<List<String>> = json.optJSONArray("names")?.let { a -> List(a.length()) { a.optJSONArray(it).strings() } }.orEmpty()
        override val amPm: Boolean = json.optBoolean("amPm", false)
        override val firstDay: Int = json.optInt("firstDay", 1)
        private val buddhist = json.optString("calendar") == "buddhist"
        private val dateZero: Int = json.optString("dateZero", "0").ifEmpty { "0" }.codePointAt(0)

        override fun formatNumber(value: BigDecimal, minDigits: Int, maxDigits: Int, grouping: Boolean, negative: Boolean): String {
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

        override fun pluralOf(n: Long): Char {
            if (plural.isEmpty()) return if (n == 1L) '1' else 'o'
            val a = kotlin.math.abs(n)
            val i = if (a < plural.length) a.toInt() else 100 + (a % 100).toInt()
            return plural.getOrElse(i) { 'o' }
        }

        // The tables hold HA's default style, "long", the one ha-relative-time and Vitro use.
        override fun relativeTime(value: Long, unit: String, style: String): String {
            val u = relative.optJSONObject(unit) ?: return "$value $unit"
            u.optJSONObject("special")?.optString(value.toString())?.takeIf { it.isNotEmpty() }?.let { return it }
            val side = u.optJSONObject(if (value < 0) "past" else "future") ?: return "$value $unit"
            val pattern = side.optString(pluralOf(value).toString()).ifEmpty { side.optString("o") }
            return pattern.replace("{0}", formatInteger(kotlin.math.abs(value)))
        }

        override fun unitAmount(n: Long, unit: String, style: String): String {
            val u = this.unit.optJSONObject(unit) ?: return "$n $unit"
            val pattern = u.optString(pluralOf(n).toString()).ifEmpty { u.optString("o") }
            return pattern.replace("{0}", formatInteger(n))
        }

        private val templates = ConcurrentHashMap<String, Any>()

        override fun dateFormat(name: String): HaIntl.DateTemplate? {
            val t = templates.getOrPut(name) {
                date.optJSONObject(name)?.let { o ->
                    fun list(key: String) = if (o.has(key)) names.getOrElse(o.optInt(key, -1)) { emptyList() } else emptyList()
                    Template(parse(o.optString("t")), list("months"), list("weekdays"), list("dayPeriods"), dateZero, buddhist)
                } ?: NONE
            }
            return t as? HaIntl.DateTemplate
        }
    }

    /** A captured template: `{d}. {Mt} {y}`, its fields written from the names captured with it. */
    private class Template(
        tokens: List<HaIntl.DateTemplate.Token>,
        private val months: List<String>,
        private val weekdays: List<String>,
        private val dayPeriods: List<String>,
        private val zero: Int,
        private val buddhist: Boolean,
    ) : HaIntl.DateTemplate(tokens) {
        override fun text(field: HaIntl.DateTemplate.Token.Field, c: Calendar): String {
            val hour = c.get(Calendar.HOUR_OF_DAY)
            val year = c.get(Calendar.YEAR) + if (buddhist) 543 else 0
            return when (field.name) {
                "y" -> num(year, 1)
                "yy" -> num(year % 100, 2)
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
    }

    /** `{name}` fields and literals (`{{` is a literal brace). */
    private fun parse(t: String): List<HaIntl.DateTemplate.Token> {
        val out = ArrayList<HaIntl.DateTemplate.Token>()
        val lit = StringBuilder()
        var i = 0
        while (i < t.length) {
            val c = t[i]
            if (c == '{' && i + 1 < t.length && t[i + 1] == '{') { lit.append('{'); i += 2; continue }
            if (c == '{') {
                val end = t.indexOf('}', i)
                if (end > i) {
                    if (lit.isNotEmpty()) { out += HaIntl.DateTemplate.Token.Literal(lit.toString()); lit.clear() }
                    out += HaIntl.DateTemplate.Token.Field(t.substring(i + 1, end))
                    i = end + 1
                    continue
                }
            }
            lit.append(c)
            i++
        }
        if (lit.isNotEmpty()) out += HaIntl.DateTemplate.Token.Literal(lit.toString())
        return out
    }

    private val NONE = Any()

    /** Stands for a group mark between digit groups until [Table.digits] writes the locale's. */
    private const val GROUP_MARK = "\u0001"
}
