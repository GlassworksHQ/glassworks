/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.cards

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * HA's `relativeTime` (common/datetime/relative_time.ts) in English: `selectUnit` (common/util/select-unit.ts, its
 * default thresholds, weeks from Sunday) and `Intl.RelativeTimeFormat` with `numeric: "auto"` ("tomorrow", "last
 * week", "in 3 months"). [capitalize] is `ha-relative-time capitalize`.
 */
object HaRelativeTime {
    fun format(fromMs: Long, toMs: Long, tz: TimeZone = TimeZone.getDefault(), capitalize: Boolean = false): String {
        val (value, unit) = selectUnit(fromMs, toMs, tz)
        val text = phrase(value, unit)
        return if (capitalize) text.replaceFirstChar { it.uppercaseChar() } else text
    }

    /** `selectUnit(from, to)`: the value and unit HA's frontend picks for the distance from [toMs] to [fromMs]. */
    fun selectUnit(fromMs: Long, toMs: Long, tz: TimeZone): Pair<Long, String> {
        val secs = (fromMs - toMs) / 1000.0
        if (abs(secs) < 59) return jsRound(secs) to "second"
        val mins = secs / 60
        if (abs(mins) < 59) return jsRound(mins) to "minute"
        val hours = secs / 3600
        if (abs(hours) < 22) return jsRound(hours) to "hour"
        val fromDay = epochDay(fromMs, tz)
        val toDay = epochDay(toMs, tz)
        val days = fromDay - toDay
        if (days == 0L) return jsRound(hours) to "hour"
        if (abs(days) < 5) return days to "day"
        // startOfWeek on Sunday: epoch day 0 (1970-01-01) was a Thursday, so Sunday is (day + 4) % 7 == 0.
        val weeks = (weekStart(fromDay) - weekStart(toDay)) / 7
        if (weeks == 0L) return days to "day"
        if (abs(weeks) < 4) return weeks to "week"
        val from = Calendar.getInstance(tz).apply { timeInMillis = fromMs }
        val to = Calendar.getInstance(tz).apply { timeInMillis = toMs }
        val years = (from.get(Calendar.YEAR) - to.get(Calendar.YEAR)).toLong()
        val months = years * 12 + from.get(Calendar.MONTH) - to.get(Calendar.MONTH)
        if (months == 0L) return weeks to "week"
        if (abs(months) < 11 || years == 0L) return months to "month"
        return years to "year"
    }

    /** English `Intl.RelativeTimeFormat` with `numeric: "auto"`. */
    fun phrase(value: Long, unit: String): String {
        val special = when (unit) {
            "second" -> if (value == 0L) "now" else null
            "minute", "hour" -> if (value == 0L) "this $unit" else null
            "day" -> when (value) { 0L -> "today"; 1L -> "tomorrow"; -1L -> "yesterday"; else -> null }
            else -> when (value) { 0L -> "this $unit"; 1L -> "next $unit"; -1L -> "last $unit"; else -> null }
        }
        if (special != null) return special
        val n = abs(value)
        val units = if (n == 1L) unit else "${unit}s"
        return if (value > 0) "in $n $units" else "$n $units ago"
    }

    /** JavaScript's `Math.round`: halves go up. */
    private fun jsRound(v: Double): Long = Math.floor(v + 0.5).roundToLong()

    private fun epochDay(ms: Long, tz: TimeZone): Long = Math.floorDiv(ms + tz.getOffset(ms), 86_400_000L)

    private fun weekStart(epochDay: Long): Long = epochDay - Math.floorMod(epochDay + 4, 7L)
}
