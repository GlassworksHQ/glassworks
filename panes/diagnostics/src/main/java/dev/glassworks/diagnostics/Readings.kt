/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.diagnostics

import android.app.ApplicationExitInfo as E
import java.io.File
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.round

/** One reading of the panel's health, taken once per period. A null field is one this device can't tell. */
data class Sample(
    /** `SystemClock.elapsedRealtime()`. */
    val elapsedMs: Long,
    /** `System.currentTimeMillis()`. */
    val wallMs: Long,
    /** The `cpu` line of `/proc/stat`, all cores together. */
    val cpu: CpuTimes?,
    /** User and system clock ticks of each of the app's processes, by pid. */
    val appTicks: Map<Int, Long>,
    /** Clock ticks per second (`_SC_CLK_TCK`). */
    val ticksPerSecond: Long,
    /** CPU cores (`_SC_NPROCESSORS_CONF`). */
    val cpus: Int,
    val contentProcesses: Int?,
    /** °C. */
    val cpuTemperature: Float?,
    val memoryAvailableBytes: Long?,
    /** PSS of all of the app's processes. */
    val appPssBytes: Long?,
    /** Free space on `/data` for the app. */
    val storageFreeBytes: Long?,
    /** dBm. */
    val wifiRssi: Int?,
    /** Mbit/s. */
    val wifiLinkSpeed: Int?,
    /** When this process started (`Process.getStartElapsedRealtime()`); null when it can't be told (Android 6, no `/proc/self/stat`). */
    val appStartElapsedMs: Long?,
    /** The size of `/data`, for the settings page's storage meter. */
    val storageTotalBytes: Long? = null,
)

/** Clock ticks since boot: all of them, and the idle ones (idle plus I/O wait). */
data class CpuTimes(val total: Long, val idle: Long)

/** The first line of `/proc/stat`: `cpu user nice system idle iowait irq softirq steal guest guest_nice`. */
fun parseCpuLine(procStat: String): CpuTimes? {
    val f = procStat.lineSequence().firstOrNull()?.trim()?.split(Regex("\\s+")) ?: return null
    if (f.firstOrNull() != "cpu" || f.size < 9) return null
    val n = f.subList(1, 9).map { it.toLongOrNull() ?: return null }
    // guest and guest_nice are already counted in user and nice.
    return CpuTimes(total = n.sum(), idle = n[3] + n[4])
}

/** utime + stime from `/proc/<pid>/stat`. The name in parentheses may hold spaces and `)`, so count from the last `)`. */
fun parseProcessTicks(procPidStat: String): Long? {
    val f = procPidStat.substringAfterLast(')', "").trim().split(Regex("\\s+"))
    if (f.size < 13) return null
    val utime = f[11].toLongOrNull() ?: return null
    val stime = f[12].toLongOrNull() ?: return null
    return utime + stime
}

/**
 * `starttime` from `/proc/<pid>/stat` (field 22): when the process started, in clock ticks since boot. Android 6 has
 * no `Process.getStartElapsedRealtime` (#198). Counted from the last `)`, as [parseProcessTicks].
 */
fun parseProcessStartTicks(procPidStat: String): Long? = dev.glassworks.platform.procStatStartTicks(procPidStat)

/** The `Pss:` line of `/proc/<pid>/smaps_rollup`, in bytes. */
fun parsePss(smapsRollup: String): Long? = smapsRollup.lineSequence()
    .firstOrNull { it.startsWith("Pss:") }
    ?.removePrefix("Pss:")?.trim()?.substringBefore(' ')?.toLongOrNull()?.times(1024)

/**
 * The sum of the `Pss:` lines of `/proc/<pid>/smaps`, one per mapping, in bytes; null when there are none or one has
 * no number. It scans bytes, not lines: Gecko's main process has some 27,000 lines, and reading them as strings took
 * 250-440 ms of CPU a sample on the Android 6 emulator (2026-11-17).
 */
fun sumPss(smaps: InputStream): Long? {
    val buf = ByteArray(64 * 1024)
    var sum: Long? = null
    // How much of "Pss:" this line has matched; -1 once it can't be a Pss line.
    var matched = 0
    // The line's number, -1 before its first digit; [ended] once a byte after its digits came.
    var kib = -1L
    var ended = false
    while (true) {
        val n = smaps.read(buf)
        if (n < 0) break
        for (i in 0 until n) {
            val c = buf[i].toInt()
            if (c == '\n'.code) {
                if (matched == PSS.size) { if (kib < 0) return null; sum = (sum ?: 0L) + kib * 1024 }
                matched = 0; kib = -1; ended = false
            } else if (matched in 0 until PSS.size) {
                matched = if (c == PSS[matched].toInt()) matched + 1 else -1
            } else if (matched == PSS.size && !ended) {
                if (c in '0'.code..'9'.code) kib = (if (kib < 0) 0 else kib) * 10 + (c - '0'.code) else if (kib >= 0) ended = true
            }
        }
    }
    if (matched == PSS.size) { if (kib < 0) return null; sum = (sum ?: 0L) + kib * 1024 }
    return sum
}

private val PSS = "Pss:".toByteArray()

/** The sum of [parts], or null when there are none or one is unknown. A subtotal is not the total. */
fun totalOrNull(parts: List<Long?>): Long? =
    if (parts.isEmpty() || parts.any { it == null }) null else parts.sumOf { it!! }

/** The `temp` file of the zone under [thermalRoot] whose `type` is [type], or null. */
fun findThermalZone(thermalRoot: File, type: String): File? =
    thermalRoot.listFiles { f -> f.name.startsWith("thermal_zone") }.orEmpty().sortedBy { it.name }
        .firstOrNull { runCatching { File(it, "type").readText().trim() }.getOrNull() == type }
        ?.let { File(it, "temp") }

/** Share of the whole CPU, all cores, that was busy between [prev] and [cur], in percent. */
fun systemCpuPercent(prev: CpuTimes?, cur: CpuTimes?): Float? {
    if (prev == null || cur == null) return null
    val total = cur.total - prev.total
    if (total <= 0) return null
    val busy = total - (cur.idle - prev.idle)
    return (100f * busy / total).coerceIn(0f, 100f)
}

/**
 * Share of the whole CPU, all cores, that the app's processes used between [prev] and [cur], in
 * percent. A process counts from the sample it first appears in; one that ended since [prev] drops
 * out, with its last ticks. A pid with fewer ticks than before is a new process that reused it.
 */
fun appCpuPercent(prev: Sample?, cur: Sample): Float? {
    if (prev == null || cur.appTicks.isEmpty()) return null
    val seconds = (cur.elapsedMs - prev.elapsedMs) / 1000f
    if (seconds <= 0f || cur.ticksPerSecond <= 0 || cur.cpus <= 0) return null
    val ticks = cur.appTicks.entries.sumOf { (pid, t) -> (t - (prev.appTicks[pid] ?: 0L)).takeIf { it >= 0 } ?: t }
    return (100f * ticks / (seconds * cur.ticksPerSecond * cur.cpus)).coerceIn(0f, 100f)
}

/** The value to publish: [new] when it moved at least [band] from [old], or appeared or went away. */
fun settle(old: Float?, new: Float?, band: Float): Float? = when {
    new == null || old == null -> new
    abs(new - old) >= band -> new
    else -> old
}

/** [v] rounded to [decimals] places. */
fun roundTo(v: Float, decimals: Int): Float {
    val p = 10.0.pow(decimals)
    return (round(v * p) / p).toFloat()
}

/**
 * `ApplicationExitInfo.REASON_*` as words. The constants are compile-time ints, so this runs on
 * Android 10 too, where the class does not exist.
 */
fun exitReasonText(reason: Int?): String = when (reason) {
    E.REASON_EXIT_SELF -> "exited"
    E.REASON_SIGNALED -> "killed by signal"
    E.REASON_LOW_MEMORY -> "low memory"
    E.REASON_CRASH -> "crash"
    E.REASON_CRASH_NATIVE -> "native crash"
    E.REASON_ANR -> "not responding"
    E.REASON_INITIALIZATION_FAILURE -> "failed to start"
    E.REASON_PERMISSION_CHANGE -> "permission changed"
    E.REASON_EXCESSIVE_RESOURCE_USAGE -> "too many resources"
    E.REASON_USER_REQUESTED -> "stopped by user"
    E.REASON_USER_STOPPED -> "stopped"
    E.REASON_DEPENDENCY_DIED -> "dependency died"
    E.REASON_OTHER -> "other"
    E.REASON_FREEZER -> "frozen"
    E.REASON_PACKAGE_STATE_CHANGE -> "package state changed"
    E.REASON_PACKAGE_UPDATED -> "app updated"
    else -> "unknown"
}
