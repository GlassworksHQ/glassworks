package dev.glassworks.esphome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The zone in a `GetTimeResponse` (#337): the `timezone` string while Home Assistant sends it, else the
 * rule rebuilt from `parsed_timezone`. The parsed zones below are what aioesphomeapi 46.25.0's
 * `_build_parsed_tz_proto` makes of each rule (POSIX sign: west of UTC is positive).
 */
class TimeZoneOfTest {
    private data class Rule(val type: Int, val time: Int = 7200, val day: Int = 0, val month: Int = 0, val week: Int = 0, val dow: Int = 0)

    private fun rule(r: Rule) = ProtoWriter().sint32(1, r.time).uint32(2, r.day).uint32(3, r.type)
        .uint32(4, r.month).uint32(5, r.week).uint32(6, r.dow)

    private fun parsed(std: Int, dst: Int = 0, start: Rule? = null, end: Rule? = null) = ProtoWriter()
        .sint32(1, std).sint32(2, dst).message(3, start?.let(::rule) ?: ProtoWriter()).message(4, end?.let(::rule) ?: ProtoWriter())

    private fun answer(zone: String? = null, parsed: ProtoWriter? = null): String? {
        val w = ProtoWriter().fixed32(1, 1_800_000_000, force = true).string(2, zone)
        parsed?.let { w.message(3, it) }
        return timeZoneOf(ProtoMessage(w.toByteArray()))
    }

    private val madrid = parsed(-3600, -7200, Rule(1, month = 3, week = 5), Rule(1, time = 3 * 3600, month = 10, week = 5))

    @Test fun theStringAlone() = assertEquals("CET-1CEST,M3.5.0,M10.5.0/3", answer("CET-1CEST,M3.5.0,M10.5.0/3"))

    @Test fun theParsedZoneAlone() = assertEquals("<+01>-1<+02>-2,M3.5.0/2,M10.5.0/3", answer(parsed = madrid))

    @Test fun withBothTheStringWins() = assertEquals("CET-1CEST,M3.5.0,M10.5.0/3", answer("CET-1CEST,M3.5.0,M10.5.0/3", madrid))

    @Test fun aBlankStringFallsBackToTheParsedZone() = assertEquals("<+01>-1<+02>-2,M3.5.0/2,M10.5.0/3", answer(" ", madrid))

    @Test fun neitherIsNoZone() = assertNull(answer())

    @Test fun aFixedOffset() {
        assertEquals("<+0530>-5:30", answer(parsed = parsed(-19800)))
        assertEquals("<-03>3", answer(parsed = parsed(3 * 3600)))
        assertEquals("<+00>0", answer(parsed = parsed(0)))
    }

    @Test fun oddOffsetsAndTimes() {
        // Pacific/Chatham: <+1245>-12:45<+1345>,M9.5.0/2:45,M4.1.0/3:45
        val chatham = parsed(-45900, -49500, Rule(1, time = 9900, month = 9, week = 5), Rule(1, time = 13500, month = 4, week = 1))
        assertEquals("<+1245>-12:45<+1345>-13:45,M9.5.0/2:45,M4.1.0/3:45", answer(parsed = chatham))
        // America/Nuuk: <-02>2<-01>,M3.5.0/-1,M10.5.0/0
        val nuuk = parsed(7200, 3600, Rule(1, time = -3600, month = 3, week = 5), Rule(1, time = 0, month = 10, week = 5))
        assertEquals("<-02>2<-01>1,M3.5.0/-1,M10.5.0/0", answer(parsed = nuuk))
        // Europe/Dublin's summer is its standard time: IST-1GMT0,M10.5.0,M3.5.0/1
        val dublin = parsed(-3600, 0, Rule(1, month = 10, week = 5), Rule(1, time = 3600, month = 3, week = 5))
        assertEquals("<+01>-1<+00>0,M10.5.0/2,M3.5.0/1", answer(parsed = dublin))
        assertEquals("<+00>0<+01>-1,J60/2,300/1:02:03", answer(parsed = parsed(0, -3600, Rule(2, day = 60), Rule(3, time = 3723, day = 300))))
    }

    @Test fun anUnknownRuleKeepsTheStandardOffset() =
        assertEquals("<+01>-1", answer(parsed = parsed(-3600, -7200, Rule(9), Rule(1, month = 10, week = 5))))
}
