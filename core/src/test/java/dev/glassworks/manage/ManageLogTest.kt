/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.manage

import dev.glassworks.platform.PlatformApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Calendar
import java.util.TimeZone

/** `ManageLog.fromLogcat` (both `epoch` and Android 6 `threadtime`) and `LogcatTail` compatibility. */
class ManageLogTest {
    private val utc: TimeZone = TimeZone.getTimeZone("UTC")
    private val src: (String) -> String? = { tag ->
        when (tag) {
            "Glassworks" -> "App"
            "Glassworks.Auth" -> "Sign-in"
            "Glassworks.Radio" -> "Radio"
            else -> null
        }
    }

    private fun utcMs(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int, ms: Int = 0): Long =
        Calendar.getInstance(utc).apply {
            clear()
            set(year, month - 1, day, hour, minute, second)
            set(Calendar.MILLISECOND, ms)
        }.timeInMillis

    @Test fun parsesEpochLines() {
        val e = ManageLog.fromLogcat("1731456000.123  1234  1250 W Glassworks.Radio: ZHA disconnected", src)!!
        assertEquals(1731456000123L, e.timeMs)
        assertEquals(LogEntry.Level.WARNING, e.level)
        assertEquals("Radio", e.source)
        assertEquals("ZHA disconnected", e.text)
    }

    @Test fun parsesThreadtimeLinesInGivenTimeZone() {
        val now = utcMs(2026, 11, 27, 23, 56, 0)
        val e = ManageLog.fromLogcat(
            "11-27 23:55:12.345  1234  1250 I Glassworks.Auth: token refreshed: ok",
            src,
            clock = { now },
            zone = utc,
        )!!
        assertEquals(utcMs(2026, 11, 27, 23, 55, 12, 345), e.timeMs)
        assertEquals(LogEntry.Level.INFO, e.level)
        assertEquals("Sign-in", e.source)
        assertEquals("token refreshed: ok", e.text)

        val ny = TimeZone.getTimeZone("America/New_York")
        val est = ManageLog.fromLogcat(
            "11-27 18:55:12.345  1234  1250 E Glassworks     : fatal error",
            src,
            clock = { now },
            zone = ny,
        )!!
        // 18:55:12.345 EST (UTC-5) == 23:55:12.345 UTC.
        assertEquals(utcMs(2026, 11, 27, 23, 55, 12, 345), est.timeMs)
        assertEquals(LogEntry.Level.ERROR, est.level)
        assertEquals("App", est.source)
        assertEquals("fatal error", est.text)
    }

    @Test fun threadtimeHandlesYearRolloverAndClockSkew() {
        val jan1 = utcMs(2026, 1, 1, 0, 5, 0)
        val dec31 = ManageLog.fromLogcat(
            "12-31 23:59:59.999  1234  1250 W Glassworks: midnight rollover",
            src,
            clock = { jan1 },
            zone = utc,
        )!!
        assertEquals(utcMs(2025, 12, 31, 23, 59, 59, 999), dec31.timeMs)

        // Within 24 hours ahead of clock: stays in the current year.
        val slightSkew = ManageLog.fromLogcat(
            "01-01 23:00:00.000  1234  1250 I Glassworks: within 24h",
            src,
            clock = { jan1 },
            zone = utc,
        )!!
        assertEquals(utcMs(2026, 1, 1, 23, 0, 0), slightSkew.timeMs)

        // More than 24 hours ahead of clock: wraps to the previous year.
        val past24h = ManageLog.fromLogcat(
            "01-02 01:00:00.000  1234  1250 I Glassworks: past 24h",
            src,
            clock = { jan1 },
            zone = utc,
        )!!
        assertEquals(utcMs(2025, 1, 2, 1, 0, 0), past24h.timeMs)

        // Leap day logged when clock is in a non-leap year falls back to the leap year before.
        val jan2025 = utcMs(2025, 1, 1, 0, 5, 0)
        val leapDay = ManageLog.fromLogcat(
            "02-29 12:00:00.000  1234  1250 I Glassworks: leap day",
            src,
            clock = { jan2025 },
            zone = utc,
        )!!
        assertEquals(utcMs(2024, 2, 29, 12, 0, 0), leapDay.timeMs)
    }

    @Test fun threadtimeFiltersPriorityTagsAndInvalidLines() {
        val now = utcMs(2026, 11, 27, 23, 56, 0)
        assertNull(ManageLog.fromLogcat("11-27 23:55:12.345  1234  1250 V Glassworks: verbose", src, { now }, utc))
        assertNull(ManageLog.fromLogcat("11-27 23:55:12.345  1234  1250 D Glassworks: debug", src, { now }, utc))
        assertNull(ManageLog.fromLogcat("11-27 23:55:12.345  1234  1250 I GeckoConsole: ignored", src, { now }, utc))
        assertNull(ManageLog.fromLogcat("--------- beginning of main", src, { now }, utc))
        assertNull(ManageLog.fromLogcat("Invalid parameter", src, { now }, utc))
        assertNull(ManageLog.fromLogcat("13-01 00:00:00.000  1234  1250 I Glassworks: bad month", src, { now }, utc))
        assertNull(ManageLog.fromLogcat("02-30 00:00:00.000  1234  1250 I Glassworks: bad day", src, { now }, utc))
        assertNull(ManageLog.fromLogcat("11-27 25:00:00.000  1234  1250 I Glassworks: bad hour", src, { now }, utc))

        val fatal = ManageLog.fromLogcat("11-27 23:55:12.345  1234  1250 F Glassworks: crash", src, { now }, utc)!!
        assertEquals(LogEntry.Level.ERROR, fatal.level)
    }

    @Test fun logcatTailCommandPicksThreadtimeBelowAndroid7AndEpochFromAndroid7() {
        assertEquals(listOf("logcat", "-v", "threadtime", "-T", "1"), LogcatTail.command(PlatformApi(23)))
        assertEquals(listOf("logcat", "-v", "epoch", "-T", "1"), LogcatTail.command(PlatformApi(24)))
        assertEquals(listOf("logcat", "-v", "epoch", "-T", "1"), LogcatTail.command(PlatformApi(29)))
        assertEquals(listOf("logcat", "-v", "epoch", "-T", "1"), LogcatTail.command(PlatformApi(34)))
    }

    @Test fun logcatTailIngestsThreadtimeOutput() {
        val dir: File = Files.createTempDirectory("manage-log-tail").toFile()
        val now = utcMs(2026, 11, 27, 23, 56, 0)
        val log = ManageLog(dir, clock = { now })
        val output = listOf(
            "--------- beginning of main",
            "11-27 23:55:10.100  1234  1250 D Glassworks: skipped debug",
            "11-27 23:55:11.200  1234  1250 I Glassworks: KioskService started",
            "11-27 23:55:12.345  1234  1250 W Glassworks.Auth: token rejected",
            "11-27 23:55:13.456  1234  1250 I GeckoConsole: ignored tag",
            "11-27 23:55:14.567  1234  1250 E Glassworks.Radio: adapter reset",
        ).joinToString("\n", postfix = "\n")

        val tail = LogcatTail(
            log = log,
            source = src,
            logcat = { ProcessBuilder("sh", "-c", "printf '%s' \"\$1\"", "--", output).start() },
            clock = { now },
            zone = utc,
        )
        tail.start()
        try {
            val deadline = System.currentTimeMillis() + 5_000
            while (log.read().size < 3 && System.currentTimeMillis() < deadline) Thread.sleep(20)
            val entries = log.read()
            assertEquals(3, entries.size)
            assertEquals(
                listOf(
                    LogEntry(utcMs(2026, 11, 27, 23, 55, 14, 567), LogEntry.Level.ERROR, "Radio", "adapter reset"),
                    LogEntry(utcMs(2026, 11, 27, 23, 55, 12, 345), LogEntry.Level.WARNING, "Sign-in", "token rejected"),
                    LogEntry(utcMs(2026, 11, 27, 23, 55, 11, 200), LogEntry.Level.INFO, "App", "KioskService started"),
                ),
                entries,
            )
        } finally {
            tail.stop()
        }
    }
}
