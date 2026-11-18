/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import dev.glassworks.platform.Platform
import dev.glassworks.platform.WifiLink
import dev.glassworks.esphome.EspHomeLog
import java.io.File

/** Where the pane gets its readings. */
interface Probe {
    fun sample(): Sample

    /** Why the app's main process last ended, as an `ApplicationExitInfo.REASON_*`. Null when the platform doesn't say. */
    fun lastExitReason(): Int?
}

/**
 * Reads the running Android: files in `/proc` and `/sys`, and a few system services. No shell
 * commands and no root. `/proc/stat` needs SELinux to let the app read it: the S1's is permissive.
 * Where a read fails, that field is null.
 */
class AndroidProbe(
    private val context: Context,
    private val cpuThermalZone: String?,
    private val isContentProcess: (String) -> Boolean,
    private val proc: File = File("/proc"),
    private val thermal: File = File("/sys/class/thermal"),
) : Probe {
    private val am = context.getSystemService(ActivityManager::class.java)
    private val ticksPerSecond = Os.sysconf(OsConstants._SC_CLK_TCK)
    private val cpus = Os.sysconf(OsConstants._SC_NPROCESSORS_CONF).toInt()
    private val temperature by lazy { cpuThermalZone?.let { findThermalZone(thermal, it) } }

    override fun sample(): Sample {
        // Android 5.1 and later list only this app's own processes.
        val processes = runCatching { am?.runningAppProcesses }.getOrNull().orEmpty()
        val pids = processes.map { it.pid }
        // A failed query leaves the Wi-Fi sensors unknown, not "not connected".
        val wifi = runCatching { WifiLink.of(context) }.onFailure { EspHomeLog.w("diagnostics: can't read the Wi-Fi link", it) }.getOrNull()
        return Sample(
            elapsedMs = SystemClock.elapsedRealtime(),
            wallMs = System.currentTimeMillis(),
            cpu = read(File(proc, "stat"))?.let(::parseCpuLine),
            appTicks = pids.mapNotNull { pid -> read(File(proc, "$pid/stat"))?.let(::parseProcessTicks)?.let { pid to it } }.toMap(),
            ticksPerSecond = ticksPerSecond,
            cpus = cpus,
            contentProcesses = processes.takeIf { it.isNotEmpty() }?.count { isContentProcess(it.processName) },
            cpuTemperature = temperature?.let(::read)?.trim()?.toIntOrNull()?.let { it / 1000f },
            memoryAvailableBytes = runCatching { am?.let { ActivityManager.MemoryInfo().also(it::getMemoryInfo).availMem } }.getOrNull(),
            appPssBytes = appPss(pids),
            storageFreeBytes = runCatching { StatFs(Environment.getDataDirectory().path).availableBytes }.getOrNull(),
            wifiRssi = wifi?.rssi,
            wifiLinkSpeed = wifi?.linkSpeedMbps,
            appStartElapsedMs = appStartElapsedMs(),
            storageTotalBytes = runCatching { StatFs(Environment.getDataDirectory().path).totalBytes }.getOrNull(),
        )
    }

    /** The process's start, once read: it never changes, so a later failed read doesn't lose it. */
    private var appStart: Long? = null

    /**
     * When this process started, on the elapsed-realtime clock: from Android 7 Android says; below, `/proc/self/stat`.
     * Null while that can't be read: unknown, not a guess.
     */
    private fun appStartElapsedMs(): Long? = appStart ?: (
        if (Platform.hasProcessStartTime) Process.getStartElapsedRealtime()
        else read(File(proc, "self/stat"))?.let(::parseProcessStartTicks)?.let { it * 1000 / ticksPerSecond }
        ).also { appStart = it }

    /** Linux 4.14 has `smaps_rollup`, as do Android's 4.4 and 4.9 kernels; older ones (the Mirror's 3.10) don't. */
    private val hasSmapsRollup by lazy { File(proc, "self/smaps_rollup").exists() }

    /**
     * The PSS of [pids], from `smaps_rollup`, or on a kernel without it the sum over `smaps`, a longer file. Not
     * Android's own count (`getProcessMemoryInfo`): on the Android 6 emulator's 3.18 kernel it is 0 for every process.
     */
    private fun appPss(pids: List<Int>): Long? = totalOrNull(pids.map { pid ->
        if (hasSmapsRollup) read(File(proc, "$pid/smaps_rollup"))?.let(::parsePss)
        else runCatching { File(proc, "$pid/smaps").inputStream().use(::sumPss) }.getOrNull()
    })

    override fun lastExitReason(): Int? = runCatching { Platform.lastExitReason(context, context.packageName) }.getOrNull()

    private fun read(f: File): String? = runCatching { f.readText() }.getOrNull()
}
