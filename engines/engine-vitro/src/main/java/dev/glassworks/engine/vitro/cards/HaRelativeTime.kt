/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.cards

import dev.glassworks.engine.vitro.data.HaFormat
import dev.glassworks.engine.vitro.data.HaLocale
import java.util.TimeZone

/**
 * HA's `relativeTime` (common/datetime/relative_time.ts) in the user's language: [HaFormat.selectUnit] (HA's
 * thresholds, weeks from the user's first weekday) and `Intl.RelativeTimeFormat` with `numeric: "auto"` ("tomorrow",
 * "last week", "in 3 months"). [capitalize] is `ha-relative-time capitalize`.
 */
object HaRelativeTime {
    fun format(fromMs: Long, toMs: Long, tz: TimeZone = HaLocale.deviceTimeZone(), capitalize: Boolean = false): String {
        val text = HaFormat.relativeTime(fromMs, toMs, zone = tz)
        return if (capitalize) HaFormat.capitalize(text) else text
    }

    /** `selectUnit(from, to)`: the value and unit HA's frontend picks for the distance from [toMs] to [fromMs]. */
    fun selectUnit(fromMs: Long, toMs: Long, tz: TimeZone): Pair<Long, String> = HaFormat.selectUnit(fromMs, toMs, zone = tz)

    /** `Intl.RelativeTimeFormat` with `numeric: "auto"` in the user's language. */
    fun phrase(value: Long, unit: String): String =
        dev.glassworks.engine.vitro.data.HaIntl.of(HaLocale.current.language).relativeTime(value, unit)
}
