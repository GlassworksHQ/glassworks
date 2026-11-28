/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.data

import java.math.BigDecimal
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

/**
 * The browser's `Intl`, as HA's frontend uses it. HA's page gets number marks, plural rules, relative-time phrases and
 * date patterns from the browser it runs in, not from Home Assistant; Vitro has no browser, so they come from a
 * [Source], one swappable class. The default, [TableIntlSource], is a copy of the answers of the Chromium HA's
 * references are captured with (`tools/vitro-parity/locale-data.mjs`), so every language matches HA's page; the
 * maintainer chose it over the panel's own ICU, whose older CLDR data differs in places (2026-11-25).
 *
 * [HaFormat] is HA's functions on top of [Data].
 */
object HaIntl {
    /** Where a locale's data comes from. */
    interface Source {
        fun data(tag: String): Data
    }

    @Volatile var source: Source = TableIntlSource
        set(value) { field = value; cache.clear() }

    private val cache = ConcurrentHashMap<String, Data>()

    /** The data for [tag] (`de`, `pt-BR`, `de-CH`), as `Intl` resolves it in the current [source]. */
    fun of(tag: String?): Data {
        val t = tag?.takeIf { it.isNotEmpty() } ?: "en"
        return cache.getOrPut(t) { source.data(t) }
    }

    /** One locale, as HA's frontend reads it from `Intl`. */
    interface Data {
        val decimal: String
        val group: String
        val minus: String

        /** `useAmPm`'s test: the locale writes "10" for 22:00. */
        val amPm: Boolean

        /** `Intl.Locale(...).weekInfo.firstDay % 7`: 0 is Sunday. */
        val firstDay: Int

        /**
         * `Intl.NumberFormat(locale, {minimumFractionDigits, maximumFractionDigits, useGrouping}).format(value)`,
         * rounding half away from zero (`halfExpand`). [negative] carries a negative zero's sign ("-0").
         */
        fun formatNumber(value: BigDecimal, minDigits: Int, maxDigits: Int, grouping: Boolean = true, negative: Boolean = value.signum() < 0): String

        /** An integer as `Intl.NumberFormat` writes it: grouped, in the locale's digits. */
        fun formatInteger(n: Long): String = formatNumber(BigDecimal.valueOf(n), 0, 0)

        /** `Intl.PluralRules(locale).select(n)` for an integer: z, 1, 2, f, m or o. */
        fun pluralOf(n: Long): Char

        /** `Intl.RelativeTimeFormat(locale, {numeric: "auto", style}).format(value, unit)`. */
        fun relativeTime(value: Long, unit: String, style: String = "long"): String

        /** `Intl.NumberFormat(locale, {style: "unit", unit, unitDisplay: style}).format(n)` for an integer. */
        fun unitAmount(n: Long, unit: String, style: String = "long"): String

        /** The format HA's option set [name] gets (`time.h12`, `date`, …), or null when there is none. */
        fun dateFormat(name: String): DateTemplate?
    }

    /**
     * One `Intl.DateTimeFormat`: its fields and literals. A field's [Token.Field.name] is what HA's code asks for
     * (`y`, `M`, `MM`, `Mt` for a month name, `d`, `dd`, `E`, `h`, `hh`, `H`, `HH`, `mm`, `ss`, `a`); how its text is
     * written is the source's.
     */
    abstract class DateTemplate(val tokens: List<Token>) {
        sealed class Token {
            data class Literal(val text: String) : Token()
            data class Field(val name: String, val pattern: String = name) : Token()
        }

        /** The text of [field] for the date in [c]. */
        protected abstract fun text(field: Token.Field, c: Calendar): String

        /** The date at [ms] in [zone], as `format(date)`. */
        fun format(ms: Long, zone: TimeZone): String {
            val c = calendar(ms, zone)
            val sb = StringBuilder()
            for (t in tokens) when (t) {
                is Token.Literal -> sb.append(t.text)
                is Token.Field -> sb.append(text(t, c))
            }
            return sb.toString()
        }

        /** Each field's text, in order: `formatToParts` with the field names of the template. */
        fun parts(ms: Long, zone: TimeZone): List<Pair<String, String>> {
            val c = calendar(ms, zone)
            return tokens.map { t ->
                when (t) {
                    is Token.Literal -> "literal" to t.text
                    is Token.Field -> t.name to text(t, c)
                }
            }
        }

        /** One field ([name] as above) of the date at [ms] in [zone]: the template's own, else that field alone. */
        fun field(name: String, ms: Long, zone: TimeZone): String {
            val f = tokens.firstOrNull { it is Token.Field && it.name == name } as? Token.Field ?: Token.Field(name)
            return text(f, calendar(ms, zone))
        }

        // Gregorian whatever the panel's locale (a Thai one's default calendar is Buddhist): the table applies its
        // own calendar to the Gregorian date.
        private fun calendar(ms: Long, zone: TimeZone): Calendar = java.util.GregorianCalendar(zone).apply { timeInMillis = ms }
    }
}
