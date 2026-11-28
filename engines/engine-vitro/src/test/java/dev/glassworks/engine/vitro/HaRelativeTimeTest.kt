/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import dev.glassworks.engine.vitro.cards.HaRelativeTime
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** HA's `relativeTime` (selectUnit + `Intl.RelativeTimeFormat`, numeric auto), as its to-do due lines show it. */
class HaRelativeTimeTest {
    private val tz = TimeZone.getTimeZone("America/Los_Angeles")

    private fun at(y: Int, m: Int, d: Int, h: Int = 23, min: Int = 59, s: Int = 59, ms: Int = 999): Long =
        Calendar.getInstance(tz).apply { clear(); set(y, m - 1, d, h, min, s); set(Calendar.MILLISECOND, ms) }.timeInMillis

    private val now = at(2026, 11, 24, 10, 8, 0, 0)

    private fun due(y: Int, m: Int, d: Int) = HaRelativeTime.format(at(y, m, d), now, tz, capitalize = true)

    @Test
    fun dueDatesReadAsHasTodoCard() {
        // The maintenance list HA drew on 2026-11-24: "In 2 days", "Last week", "Next month", "In 3 months".
        assertEquals("In 2 days", due(2026, 11, 26))
        assertEquals("Last week", due(2026, 11, 19))
        assertEquals("Next month", due(2026, 12, 20))
        assertEquals("In 3 months", due(2027, 2, 15))
        assertEquals("Tomorrow", due(2026, 11, 25))
        // Under 22 hours HA counts hours, even across midnight; from 22 on, calendar days.
        assertEquals("10 hours ago", due(2026, 11, 23))
        assertEquals("Yesterday", HaRelativeTime.format(at(2026, 11, 23, 8, 0, 0, 0), now, tz, capitalize = true))
        assertEquals("Next week", due(2026, 12, 1))
        assertEquals("In 2 weeks", due(2026, 12, 8))
    }

    @Test
    fun shortDistancesUseSecondsMinutesAndHours() {
        assertEquals("now", HaRelativeTime.format(now, now, tz))
        assertEquals("in 30 seconds", HaRelativeTime.format(now + 30_000, now, tz))
        assertEquals("5 minutes ago", HaRelativeTime.format(now - 5 * 60_000, now, tz))
        assertEquals("in 3 hours", HaRelativeTime.format(now + 3 * 3_600_000, now, tz))
        assertEquals("1 hour ago", HaRelativeTime.format(now - 3_600_000, now, tz))
        assertEquals("next year", HaRelativeTime.phrase(1, "year"))
        assertEquals("in 2 years", HaRelativeTime.phrase(2, "year"))
    }
}
