package dev.glassworks.esphome

import kotlin.math.abs

/**
 * The time zone in a `GetTimeResponse` (37), as the POSIX `TZ` rule [ApiServer.onTimeZone] hands on.
 * Home Assistant sends it twice: as the `timezone` string (2) and as `parsed_timezone` (3), the same
 * rule taken apart. ESPHome 2026.9 deprecated the string, so when it is missing the rule is written
 * back from the parsed one, with `<+01>`-style names. Null when the answer has neither.
 */
internal fun timeZoneOf(getTime: ProtoMessage): String? {
    getTime.string(2).takeIf { it.isNotBlank() }?.let { return it }
    if (!getTime.has(3)) return null
    val tz = ProtoMessage(getTime.bytes(3))
    // ParsedTimezone: std_offset_seconds = 1, dst_offset_seconds = 2 (sint32, POSIX sign: west is
    // positive), dst_start = 3, dst_end = 4.
    val std = sint32(tz, 1)
    val fixed = posixName(std) + posixTime(std)
    val start = dstRule(tz, 3) ?: return fixed
    val end = dstRule(tz, 4) ?: return fixed
    val dst = sint32(tz, 2)
    return "$fixed${posixName(dst)}${posixTime(dst)},$start,$end"
}

/**
 * A DSTRule as `Mm.w.d/time`, `Jn/time` or `n/time`; null for type NONE (no summer time) or a type
 * this doesn't know. Fields: time_seconds = 1 (sint32), day = 2, type = 3, month = 4, week = 5,
 * day_of_week = 6.
 */
private fun dstRule(tz: ProtoMessage, field: Int): String? {
    if (!tz.has(field)) return null
    val r = ProtoMessage(tz.bytes(field))
    val day = when (r.uint32(3)) {
        1 -> "M${r.uint32(4)}.${r.uint32(5)}.${r.uint32(6)}"
        2 -> "J${r.uint32(2)}"
        3 -> "${r.uint32(2)}"
        else -> return null
    }
    return "$day/${posixTime(sint32(r, 1))}"
}

private fun sint32(m: ProtoMessage, field: Int): Int = ProtoMessage.zigzag(m.long(field))

private fun two(n: Int): String = n.toString().padStart(2, '0')

/** `[-]h[:mm[:ss]]`. */
private fun posixTime(seconds: Int): String {
    val s = abs(seconds)
    val hms = when {
        s % 60 != 0 -> "${s / 3600}:${two(s / 60 % 60)}:${two(s % 60)}"
        s % 3600 != 0 -> "${s / 3600}:${two(s / 60 % 60)}"
        else -> "${s / 3600}"
    }
    return if (seconds < 0) "-$hms" else hms
}

/** The quoted name POSIX allows for a zone without one, with east of UTC as positive: `<+0545>`, `<-03>`. */
private fun posixName(posixOffset: Int): String {
    val s = abs(posixOffset)
    val minutes = s / 60 % 60
    return "<${if (posixOffset > 0) "-" else "+"}${two(s / 3600)}${if (minutes != 0) two(minutes) else ""}>"
}
