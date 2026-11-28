/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.manage

import dev.glassworks.platform.Platform
import dev.glassworks.platform.PlatformApi
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.Calendar
import java.util.TimeZone

/** One line of the panel's log. [source] is what it is about, in words ("Voice", "Radio"). */
data class LogEntry(val timeMs: Long, val level: Level, val source: String, val text: String) {
    enum class Level { INFO, WARNING, ERROR }
}

/**
 * The log the Manage page shows: kept in the app's files, so it survives an app restart and a reboot,
 * for [keepMs] (7 days). Two files of at most [fileBytes] each: when the current one is full it becomes
 * the old one, and the old one goes. Text is redacted before it is written. Thread-safe.
 */
class ManageLog(
    private val dir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val keepMs: Long = 7L * 24 * 3600 * 1000,
    private val fileBytes: Long = 256L * 1024,
) {
    private val current get() = File(dir, "log.0")
    private val old get() = File(dir, "log.1")

    @Synchronized
    fun append(e: LogEntry) {
        dir.mkdirs()
        prune(full = false)
        if (current.length() >= fileBytes) {
            old.delete()
            current.renameTo(old)
        }
        val line = "${e.timeMs}\t${e.level.name[0]}\t${clean(e.source)}\t${clean(Redact.text(e.text, keepAdbKeys = true))}\n"
        current.appendText(line, Charsets.UTF_8)
    }

    /** Newest first, at most [limit]. [level]: only that level and worse. [search]: case-insensitive, in source or text. */
    @Synchronized
    fun read(level: LogEntry.Level = LogEntry.Level.INFO, search: String = "", limit: Int = 500): List<LogEntry> {
        prune(full = false)
        val since = clock() - keepMs
        val all = (readFile(old) + readFile(current)).filter { it.timeMs >= since && it.level >= level }
        val q = search.trim().lowercase()
        return all.asReversed().asSequence()
            .filter { q.isEmpty() || q in it.text.lowercase() || q in it.source.lowercase() }
            .take(limit).toList()
    }

    /**
     * Takes lines older than [keepMs] off the disk. [full]: look at every line, so an old line after a newer
     * one goes too (the clock can jump at boot); the app calls this when it starts and every hour, so the
     * disk keeps 7 days also while nothing is written or read ([LogcatTail]). Otherwise only each file's
     * first line is checked, which is cheap enough for every append.
     */
    @Synchronized
    fun prune(full: Boolean = true) {
        val since = clock() - keepMs
        for (f in listOf(old, current)) {
            if (!f.exists()) continue
            if (!full) {
                val first = f.bufferedReader(Charsets.UTF_8).use { it.readLine() }?.let(::parse) ?: continue
                if (first.timeMs >= since) continue
            }
            val lines = f.readLines(Charsets.UTF_8)
            val keep = lines.filter { (parse(it)?.timeMs ?: 0) >= since }
            if (keep.size == lines.size) continue
            if (keep.isEmpty()) f.delete() else f.writeText(keep.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
        }
    }

    private fun readFile(f: File): List<LogEntry> = if (!f.exists()) emptyList() else f.readLines(Charsets.UTF_8).mapNotNull(::parse)

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    companion object {
        fun parse(line: String): LogEntry? {
            val f = line.split('\t', limit = 4)
            if (f.size < 4) return null
            val t = f[0].toLongOrNull() ?: return null
            val level = when (f[1]) { "W" -> LogEntry.Level.WARNING; "E" -> LogEntry.Level.ERROR; "I" -> LogEntry.Level.INFO; else -> return null }
            return LogEntry(t, level, f[2], f[3])
        }

        private val LOGCAT_EPOCH = Regex("^\\s*(\\d+)\\.(\\d{3})\\s+\\d+\\s+\\d+\\s+([VDIWEF])\\s+([^:]+?)\\s*:\\s?(.*)$")
        private val LOGCAT_THREADTIME = Regex("^\\s*(\\d{2})-(\\d{2})\\s+(\\d{2}):(\\d{2}):(\\d{2})\\.(\\d{3})\\s+\\d+\\s+\\d+\\s+([VDIWEF])\\s+([^:]+?)\\s*:\\s?(.*)$")
        private const val YEAR_WRAP_AHEAD_MS = 24L * 3600 * 1000

        /**
         * One `logcat -v epoch` (`1731456000.123  1234  1250 I Glassworks.Voice: text`) or `logcat -v threadtime`
         * (`11-27 23:55:12.345  1234  1250 I Glassworks.Voice: text`) line as an entry. [source] names the tag in
         * words, or returns null for a tag the log leaves out. Verbose and debug lines are left out.
         *
         * `threadtime` omits the year, so [clock] and [zone] supply the current year; if the parsed timestamp is more
         * than 24 hours ahead of [clock], it belongs to the previous year.
         */
        fun fromLogcat(
            line: String,
            source: (String) -> String?,
            clock: () -> Long = System::currentTimeMillis,
            zone: TimeZone = TimeZone.getDefault(),
        ): LogEntry? {
            LOGCAT_EPOCH.matchEntire(line)?.let { m ->
                val (sec, ms, prio, tag, text) = m.destructured
                val level = levelOf(prio) ?: return null
                val src = source(tag.trim()) ?: return null
                val s = sec.toLongOrNull() ?: return null
                return LogEntry(s * 1000 + ms.toLong(), level, src, text)
            }
            LOGCAT_THREADTIME.matchEntire(line)?.let { m ->
                val (mo, d, h, mi, s, ms, prio, tag, text) = m.destructured
                val level = levelOf(prio) ?: return null
                val src = source(tag.trim()) ?: return null
                val timeMs = threadtimeMs(mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), s.toInt(), ms.toInt(), clock(), zone) ?: return null
                return LogEntry(timeMs, level, src, text)
            }
            return null
        }

        private fun levelOf(prio: String): LogEntry.Level? = when (prio) {
            "I" -> LogEntry.Level.INFO
            "W" -> LogEntry.Level.WARNING
            "E", "F" -> LogEntry.Level.ERROR
            else -> null
        }

        private fun threadtimeMs(month: Int, day: Int, hour: Int, minute: Int, second: Int, ms: Int, now: Long, zone: TimeZone): Long? {
            if (month !in 1..12 || day !in 1..31 || hour !in 0..23 || minute !in 0..59 || second !in 0..59) return null
            val year = Calendar.getInstance(zone).apply { timeInMillis = now }.get(Calendar.YEAR)
            fun at(y: Int): Long? {
                val cal = Calendar.getInstance(zone).apply {
                    clear()
                    set(y, month - 1, day, hour, minute, second)
                    set(Calendar.MILLISECOND, ms)
                }
                if (cal.get(Calendar.MONTH) != month - 1 || cal.get(Calendar.DAY_OF_MONTH) != day) return null
                return cal.timeInMillis
            }
            val current = at(year)
            return if (current != null && current <= now + YEAR_WRAP_AHEAD_MS) current else at(year - 1)
        }
    }
}

/**
 * Copies the app's own log lines into [log] while the app runs: `logcat -v epoch -T 1` on Android 7+ and
 * `logcat -v threadtime -T 1` on older Android, which on Android shows an app only its own lines. [source] picks the
 * tags and names them. While it runs it also prunes the log every [pruneEveryMs] (an hour), and once at start.
 */
class LogcatTail(
    private val log: ManageLog,
    private val source: (String) -> String?,
    private val onError: (String) -> Unit = {},
    private val pruneEveryMs: Long = 3600_000L,
    /** Starts the reader; tests pass a fake logcat. */
    private val logcat: () -> Process = { ProcessBuilder(command()).redirectErrorStream(true).start() },
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: TimeZone = TimeZone.getDefault(),
) {
    private var running = false
    /** Which start() the running workers belong to: a worker from an earlier one never publishes its process. */
    private var generation = 0
    private var process: Process? = null

    @Synchronized
    fun start() {
        if (running) return
        running = true
        val mine = ++generation
        Thread({
            try {
                while (synchronized(this) { running && generation == mine }) {
                    runCatching { log.prune() }.onFailure { onError("log prune: ${it.message}") }
                    Thread.sleep(pruneEveryMs)
                }
            } catch (_: InterruptedException) {}
        }, "glassworks-log-prune").apply { isDaemon = true; start() }
        Thread({
            try {
                // -T 1: from now on; earlier lines were copied by the previous run, or were before the app started.
                val p = logcat()
                // stop() may have run while logcat started, and start() again: then this process is not wanted.
                val keep = synchronized(this) { if (running && generation == mine) { process = p; true } else false }
                if (!keep) { p.destroy(); return@Thread }
                BufferedReader(InputStreamReader(p.inputStream)).useLines { lines ->
                    for (l in lines) ManageLog.fromLogcat(l, source, clock, zone)?.let { runCatching { log.append(it) } }
                }
            } catch (e: Exception) {
                onError("logcat: ${e.message}")
            }
        }, "glassworks-log").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stop() {
        running = false
        generation++
        process?.destroy()
        process = null
    }

    companion object {
        /** The `logcat` command for [platform]: `-v epoch` on Android 7+, `-v threadtime` below. */
        fun command(platform: PlatformApi = Platform): List<String> =
            listOf("logcat", "-v", if (platform.hasLogcatEpoch) "epoch" else "threadtime", "-T", "1")
    }
}
