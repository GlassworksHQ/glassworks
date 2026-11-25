/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.data

import java.math.BigDecimal
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * HA frontend 2026.11's formatting functions, read from its build (common/number/format_number.ts,
 * common/translations/blank_before_unit.ts, common/datetime, common/util/select-unit.ts), on [HaIntl]'s
 * tables instead of the browser's `Intl`. Every function takes the user's [FrontendLocale] ([HaLocale.current] by
 * default) and, for dates, the time zone HA would use ([HaLocale.timeZone]).
 */
object HaFormat {
    /** `Intl.NumberFormatOptions` as HA passes them: fraction digits and grouping. */
    data class NumberOptions(val minimumFractionDigits: Int? = null, val maximumFractionDigits: Int? = null, val useGrouping: Boolean? = null)

    /** HA's `numberFormatToLocale`: the locale a `number_format` formats with; null is the browser's (system). */
    fun numberLocale(locale: FrontendLocale): String? = when (locale.numberFormat) {
        "comma_decimal" -> "en-US"
        "decimal_comma" -> "de"
        "space_comma" -> "fr"
        "quote_decimal" -> "de-CH"
        "system" -> null
        else -> locale.language
    }

    /** What `Intl` uses for `undefined`: the browser's locale, the panel's here. */
    fun systemTag(): String = HaLocale.browserTag(HaLocale.deviceLocale())

    private fun numberData(locale: FrontendLocale) = HaIntl.of(numberLocale(locale) ?: systemTag())

    /**
     * HA's `formatNumber(num, localeOptions, options)`: `none` writes en-US digits without grouping, a value that
     * isn't a number comes back as it is, and a string keeps the decimals it carries unless [options] set them.
     */
    fun formatNumber(num: Any?, locale: FrontendLocale = HaLocale.current, options: NumberOptions? = null): String {
        val raw = num as? String
        val value: Double? = when (num) {
            is String -> jsNumber(num)
            is Number -> num.toDouble()
            else -> null
        }
        val none = locale.numberFormat == "none"
        if (none || value == null || value.isNaN()) {
            if (value == null || value.isNaN() || raw == "" || !none) return raw ?: (value?.toString() ?: num.toString())
            return format(HaIntl.of("en-US"), num, value, options?.copy(useGrouping = false) ?: NumberOptions(useGrouping = false))
        }
        return format(numberData(locale), num, value, options)
    }

    /** The decimal mark formatNumber writes in the user's number format. */
    fun decimalMark(locale: FrontendLocale = HaLocale.current): String =
        if (locale.numberFormat == "none") "." else numberData(locale).decimal

    /** `formatNumber` then the unit, with HA's `blankBeforeUnit` between. */
    fun formatNumberWithUnit(num: Any?, unit: String?, locale: FrontendLocale = HaLocale.current, options: NumberOptions? = null): String {
        val n = formatNumber(num, locale, options)
        return if (unit.isNullOrEmpty()) n else "$n${blankBeforeUnit(unit, locale)}$unit"
    }

    /** HA's `getDefaultFormatOptions` and `Intl.NumberFormat`'s format. */
    private fun format(data: HaIntl.Data, num: Any?, value: Double, options: NumberOptions?): String {
        var max = options?.maximumFractionDigits ?: 2
        var min = options?.minimumFractionDigits ?: 0
        if (num is String && (options == null || (options.minimumFractionDigits == null && options.maximumFractionDigits == null))) {
            val digits = if ('.' in num) num.split('.')[1].length else 0
            min = digits
            max = digits
        }
        if (min > max) max = min
        if (value.isInfinite()) return (if (value < 0) data.minus else "") + "∞"
        val bd = decimalOf(num, value)
        val negative = value < 0 || (value == 0.0 && 1.0 / value < 0)
        return data.formatNumber(bd, min, max, grouping = options?.useGrouping ?: true, negative = negative)
    }

    /** The decimal `Intl` formats for [value]: the string's own digits when it is a plain decimal, else the double's. */
    private fun decimalOf(num: Any?, value: Double): BigDecimal {
        if (num is String) {
            val t = num.trim()
            if (PLAIN_DECIMAL.matches(t)) runCatching { return BigDecimal(t).abs() }
        }
        return BigDecimal(value.toString()).abs()
    }

    private val PLAIN_DECIMAL = Regex("""[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?""")

    /** JavaScript's `Number(string)`: null for NaN. */
    fun jsNumber(s: String): Double? {
        val t = s.trim()
        if (t.isEmpty()) return 0.0
        when (t) { "Infinity", "+Infinity" -> return Double.POSITIVE_INFINITY; "-Infinity" -> return Double.NEGATIVE_INFINITY }
        if (t.length > 2 && t[0] == '0') {
            val radix = when (t[1]) { 'x', 'X' -> 16; 'o', 'O' -> 8; 'b', 'B' -> 2; else -> 0 }
            if (radix != 0) return t.substring(2).toBigIntegerOrNull(radix)?.toDouble()
        }
        if (!PLAIN_DECIMAL.matches(t)) return null
        return t.toDoubleOrNull()
    }

    /**
     * HA's `getNumberFormatOptions(entityState, entity)`: the registry's display precision, else no decimals when the
     * step and the state are whole, else none (the string's own decimals).
     */
    fun numberOptions(state: String?, step: Any?, displayPrecision: Int?): NumberOptions? {
        if (displayPrecision != null) return NumberOptions(displayPrecision, displayPrecision)
        val stepN = when (step) { is Number -> step.toDouble(); is String -> jsNumber(step); null -> Double.NaN; else -> jsNumber(step.toString()) }
        val stateN = state?.let { jsNumber(it) }
        if (stepN != null && isInteger(stepN) && stateN != null && isInteger(stateN)) return NumberOptions(maximumFractionDigits = 0)
        return null
    }

    private fun isInteger(d: Double) = d.isFinite() && d == Math.floor(d)

    /** HA's `blankBeforePercent`. */
    fun blankBeforePercent(locale: FrontendLocale): String = when (locale.language) {
        "cs", "de", "fi", "fr", "sk", "sv" -> " "
        else -> ""
    }

    /** HA's `blankBeforeUnit`: none before "°", the language's before "%", a space before anything else. */
    fun blankBeforeUnit(unit: String, locale: FrontendLocale? = HaLocale.current): String = when {
        unit == "°" -> ""
        locale != null && unit == "%" -> blankBeforePercent(locale)
        else -> " "
    }

    /** HA's `useAmPm`: the language's (or the browser's, for `system`) 12-hour test, or the user's choice. */
    fun useAmPm(locale: FrontendLocale = HaLocale.current): Boolean = when (locale.timeFormat) {
        "language" -> HaIntl.of(locale.language).amPm
        "system" -> HaIntl.of(systemTag()).amPm
        else -> locale.timeFormat == "12"
    }

    private val WEEKDAYS = listOf("sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday")

    /** HA's `firstWeekdayIndex`: 0 is Sunday. */
    fun firstWeekdayIndex(locale: FrontendLocale = HaLocale.current): Int =
        if (locale.firstWeekday == "language") HaIntl.of(locale.language).firstDay
        else WEEKDAYS.indexOf(locale.firstWeekday).takeIf { it >= 0 } ?: 1

    // --- Dates and times ---------------------------------------------------------------------------------------

    private fun dateData(locale: FrontendLocale) = HaIntl.of(locale.language)

    private fun hc(locale: FrontendLocale) = if (useAmPm(locale)) "h12" else "h23"

    private fun fmt(name: String, ms: Long, locale: FrontendLocale, tag: String = locale.language): String {
        val t = HaIntl.of(tag).dateFormat(name) ?: HaIntl.of("en").dateFormat(name) ?: return ""
        return t.format(ms, HaLocale.timeZone(locale))
    }

    /** `formatDateWeekdayDay`: "Thursday, January 5". */
    fun formatDateWeekdayDay(ms: Long, locale: FrontendLocale = HaLocale.current) = fmt("weekdayMonthDay", ms, locale)
    /** `formatDate`: "January 5, 2023". */
    fun formatDate(ms: Long, locale: FrontendLocale = HaLocale.current) = fmt("date", ms, locale)
    /** `formatDateShort`: "Jan 5, 2023". */
    fun formatDateShort(ms: Long, locale: FrontendLocale = HaLocale.current) = fmt("dateShort", ms, locale)
    /** `formatDateVeryShort`: "Jan 5". */
    fun formatDateVeryShort(ms: Long, locale: FrontendLocale = HaLocale.current) = fmt("dateVeryShort", ms, locale)
    /** `formatDateMonthYear`: "January 2023". */
    fun formatDateMonthYear(ms: Long, locale: FrontendLocale = HaLocale.current) = fmt("monthYear", ms, locale)
    /** `formatDateMonth`: "January". */
    fun formatDateMonth(ms: Long, locale: FrontendLocale = HaLocale.current) = fmt("month", ms, locale)
    /** `formatDateMonthShort`: "Jan". */
    fun formatDateMonthShort(ms: Long, locale: FrontendLocale = HaLocale.current) = fmt("monthShort", ms, locale)
    /** `formatDateYear`: "2023". */
    fun formatDateYear(ms: Long, locale: FrontendLocale = HaLocale.current) = fmt("year", ms, locale)
    /** `formatDateWeekday`: "Thursday". */
    fun formatDateWeekday(ms: Long, locale: FrontendLocale = HaLocale.current) = fmt("weekday", ms, locale)
    /** `formatDateWeekdayShort`: "Thu". */
    fun formatDateWeekdayShort(ms: Long, locale: FrontendLocale = HaLocale.current) = fmt("weekdayShort", ms, locale)
    /** `formatDateWeekdayVeryShortDate`: "Thu, Jan 5". */
    fun formatDateWeekdayShortDate(ms: Long, locale: FrontendLocale = HaLocale.current) = fmt("weekdayShortDate", ms, locale)
    /** `formatDateWeekdayShortDateYear`: "Thu, Jan 5, 2023". */
    fun formatDateWeekdayShortDateYear(ms: Long, locale: FrontendLocale = HaLocale.current) = fmt("weekdayShortDateYear", ms, locale)

    /** `formatDateNumeric`: "1/5/2023", or the user's `date_format` order with the language's separator. */
    fun formatDateNumeric(ms: Long, locale: FrontendLocale = HaLocale.current): String {
        val tag = if (locale.dateFormat == "system") systemTag() else locale.language
        val t = HaIntl.of(tag).dateFormat("dateNumeric") ?: return fmt("dateNumeric", ms, locale)
        val zone = HaLocale.timeZone(locale)
        if (locale.dateFormat == "language" || locale.dateFormat == "system") return t.format(ms, zone)
        val parts = t.parts(ms, zone)
        val sep = parts.firstOrNull { it.first == "literal" }?.second.orEmpty()
        val day = parts.firstOrNull { it.first == "d" || it.first == "dd" }?.second.orEmpty()
        val month = parts.firstOrNull { it.first == "M" || it.first == "MM" }?.second.orEmpty()
        val year = parts.firstOrNull { it.first == "y" || it.first == "yy" }?.second.orEmpty()
        var suffix = parts.lastOrNull()?.takeIf { it.first == "literal" }?.second.orEmpty()
        if (locale.language == "bg" && locale.dateFormat == "YMD") suffix = ""
        return when (locale.dateFormat) {
            "DMY" -> "$day$sep$month$sep$year$suffix"
            "MDY" -> "$month$sep$day$sep$year$suffix"
            "YMD" -> "$year$sep$month$sep$day$suffix"
            else -> t.format(ms, zone)
        }
    }

    /** `formatTime`: "9:07 AM" or "09:07", by `useAmPm`. */
    fun formatTime(ms: Long, locale: FrontendLocale = HaLocale.current) = fmt("time.${hc(locale)}", ms, locale)
    /** `formatTimeWithSeconds`. */
    fun formatTimeWithSeconds(ms: Long, locale: FrontendLocale = HaLocale.current) = fmt("timeSeconds.${hc(locale)}", ms, locale)
    /** `formatTimeWeekday`: "Thursday 9:07 AM". */
    fun formatTimeWeekday(ms: Long, locale: FrontendLocale = HaLocale.current) = fmt("weekdayTime.${hc(locale)}", ms, locale)
    /** `formatTime24h`: en-GB's "9:07", whatever the language. */
    fun formatTime24h(ms: Long, locale: FrontendLocale = HaLocale.current) = fmt("time.h23", ms, locale, "en-GB")
    /** The hour alone (`{hour: "numeric"}`), as HA's charts label an axis. */
    fun formatHour(ms: Long, locale: FrontendLocale = HaLocale.current) = fmt("hour.${hc(locale)}", ms, locale)
    /** `formatDateTime`: "January 5, 2023 at 9:07 AM". */
    fun formatDateTime(ms: Long, locale: FrontendLocale = HaLocale.current) = fmt("dateTime.${hc(locale)}", ms, locale)
    /** `formatShortDateTimeWithYear`: "Jan 5, 2023, 9:07 AM". */
    fun formatShortDateTimeWithYear(ms: Long, locale: FrontendLocale = HaLocale.current) = fmt("dateTimeShortYear.${hc(locale)}", ms, locale)
    /** `formatShortDateTime`: "Jan 5, 9:07 AM". */
    fun formatShortDateTime(ms: Long, locale: FrontendLocale = HaLocale.current) = fmt("shortDateTime.${hc(locale)}", ms, locale)
    /** `formatShortDateTimeWithSeconds`. */
    fun formatShortDateTimeWithSeconds(ms: Long, locale: FrontendLocale = HaLocale.current) = fmt("shortDateTimeSeconds.${hc(locale)}", ms, locale)
    /** `formatDateTimeWithSeconds`. */
    fun formatDateTimeWithSeconds(ms: Long, locale: FrontendLocale = HaLocale.current) = fmt("dateTimeSeconds.${hc(locale)}", ms, locale)
    /** `formatDateTimeNumeric`: `formatDateNumeric`, a comma, `formatTime`. */
    fun formatDateTimeNumeric(ms: Long, locale: FrontendLocale = HaLocale.current) = "${formatDateNumeric(ms, locale)}, ${formatTime(ms, locale)}"

    /** `formatShortDateTimeWithConditionalYear`: no year within this year (the browser's clock and zone). */
    fun formatShortDateTimeWithConditionalYear(ms: Long, nowMs: Long = System.currentTimeMillis(), locale: FrontendLocale = HaLocale.current): String {
        val zone = HaLocale.deviceTimeZone()
        val y = Calendar.getInstance(zone).apply { timeInMillis = ms }.get(Calendar.YEAR)
        val now = Calendar.getInstance(zone).apply { timeInMillis = nowMs }.get(Calendar.YEAR)
        return if (y == now) formatShortDateTime(ms, locale) else formatShortDateTimeWithYear(ms, locale)
    }

    // --- Relative time -----------------------------------------------------------------------------------------

    /**
     * HA's `selectUnit(from, to, locale)`: the value and unit for the distance from [toMs] to [fromMs]. Days and weeks
     * count in the browser's own zone (the panel's), weeks from the user's first weekday.
     */
    fun selectUnit(fromMs: Long, toMs: Long, locale: FrontendLocale = HaLocale.current, zone: TimeZone = HaLocale.deviceTimeZone()): Pair<Long, String> {
        val secs = (fromMs - toMs) / 1000.0
        if (abs(secs) < 59) return jsRound(secs) to "second"
        val mins = secs / 60
        if (abs(mins) < 59) return jsRound(mins) to "minute"
        val hours = secs / 3600
        if (abs(hours) < 22) return jsRound(hours) to "hour"
        val fromDay = epochDay(fromMs, zone)
        val toDay = epochDay(toMs, zone)
        val days = fromDay - toDay
        if (days == 0L) return jsRound(hours) to "hour"
        if (abs(days) < 5) return days to "day"
        val first = firstWeekdayIndex(locale)
        val weeks = (weekStart(fromDay, first) - weekStart(toDay, first)) / 7
        if (weeks == 0L) return days to "day"
        if (abs(weeks) < 4) return weeks to "week"
        val from = Calendar.getInstance(zone).apply { timeInMillis = fromMs }
        val to = Calendar.getInstance(zone).apply { timeInMillis = toMs }
        val years = (from.get(Calendar.YEAR) - to.get(Calendar.YEAR)).toLong()
        val months = years * 12 + from.get(Calendar.MONTH) - to.get(Calendar.MONTH)
        if (months == 0L) return weeks to "week"
        if (abs(months) < 11 || years == 0L) return months to "month"
        return years to "year"
    }

    /**
     * HA's `relativeTime(from, locale, to, includeTense, style)`: "in 3 hours", "yesterday" (`Intl.RelativeTimeFormat`
     * with `numeric: "auto"`), or without the tense the amount alone ("3 hours").
     */
    fun relativeTime(fromMs: Long, toMs: Long, locale: FrontendLocale = HaLocale.current, includeTense: Boolean = true, style: String = "long", zone: TimeZone = HaLocale.deviceTimeZone()): String {
        val (value, unit) = selectUnit(fromMs, toMs, locale, zone)
        val data = HaIntl.of(locale.language)
        return if (includeTense) data.relativeTime(value, unit, style) else data.unitAmount(abs(value), unit, style)
    }

    /** HA's `capitalizeFirstLetter`. */
    fun capitalize(s: String): String = if (s.isEmpty()) s else s.substring(0, 1).uppercase(java.util.Locale.ROOT) + s.substring(1)

    /** JavaScript's `Math.round`: halves go up. */
    private fun jsRound(v: Double): Long = Math.floor(v + 0.5).roundToLong()

    // Kotlin's floorDiv and mod: java.lang.Math's long overloads need API 24, and Vitro runs on Android 6.
    private fun epochDay(ms: Long, tz: TimeZone): Long = (ms + tz.getOffset(ms)).floorDiv(86_400_000L)

    /** date-fns `startOfWeek` with `weekStartsOn` [first] (0 Sunday); epoch day 0 (1970-01-01) was a Thursday. */
    private fun weekStart(epochDay: Long, first: Int): Long = epochDay - (epochDay + 4 - first).mod(7L)
}
