/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.diagnostics

import android.app.ActivityManager
import android.app.Application
import android.net.wifi.WifiManager
import android.os.DeadSystemException
import android.os.Debug
import android.os.Environment
import android.os.Process
import android.system.Os
import android.system.OsConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowActivityManager
import org.robolectric.shadows.ShadowProcess
import org.robolectric.shadows.ShadowStatFs
import org.robolectric.shadows.ShadowWifiInfo
import java.io.File

/** The probe on Android 10, the S1's, with `/proc` and `/sys/class/thermal` in a temp tree. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class AndroidProbeTest {
    @get:Rule val tmp = TemporaryFolder()

    private val app = RuntimeEnvironment.getApplication()

    private fun probe(): AndroidProbe {
        val thermal = tmp.newFolder("thermal")
        File(thermal, "thermal_zone0").apply { mkdirs(); File(this, "type").writeText("cpu_thermal_zone\n"); File(this, "temp").writeText("47999\n") }
        return AndroidProbe(app, "cpu_thermal_zone", { false }, proc = tmp.newFolder("proc"), thermal = thermal)
    }

    private fun wifi(rssi: Int, linkSpeed: Int) {
        val info = ShadowWifiInfo.newInstance()
        shadowOf(info).setRssi(rssi)
        shadowOf(info).setLinkSpeed(linkSpeed)
        shadowOf(app.getSystemService(WifiManager::class.java)).setConnectionInfo(info)
    }

    @Test fun temperatureIsInDegrees() {
        // The S1's zone reads millidegrees (47999 on 2026-11-12).
        assertEquals(47.999f, probe().sample().cpuTemperature!!, 0.0005f)
    }

    @Test fun storageIsWhatTheAppMayUseOnData() {
        ShadowStatFs.registerStats(Environment.getDataDirectory(), 1000, 600, 500)
        assertEquals(500L * ShadowStatFs.BLOCK_SIZE, probe().sample().storageFreeBytes)
    }

    @Test fun wifiOnlyCountsWhenConnected() {
        val p = probe()
        wifi(rssi = -55, linkSpeed = 72)
        p.sample().let { assertEquals(-55, it.wifiRssi); assertEquals(72, it.wifiLinkSpeed) }
        wifi(rssi = -126, linkSpeed = 1)
        p.sample().let { assertEquals(-126, it.wifiRssi); assertEquals(1, it.wifiLinkSpeed) }
        // WifiInfo.INVALID_RSSI and an unknown link speed: Android's values while Wi-Fi is down.
        wifi(rssi = -127, linkSpeed = -1)
        p.sample().let { assertNull(it.wifiRssi); assertNull(it.wifiLinkSpeed) }
    }

    @Test fun availableMemoryComesFromActivityManager() {
        shadowOf(app.getSystemService(ActivityManager::class.java)).setMemoryInfo(ActivityManager.MemoryInfo().apply { availMem = 512L shl 20 })
        assertEquals(512L shl 20, probe().sample().memoryAvailableBytes)
    }

    /** A kernel with `smaps_rollup` (the S1's 4.9): the app's RAM comes from it, not from Android's count. */
    @Test @Config(shadows = [ProcessPssShadow::class])
    fun appRamComesFromSmapsRollupWhereTheKernelHasIt() {
        val proc = tmp.newFolder("proc-rollup")
        appProcesses(proc, 4242 to 100_000L, 4243 to 50_000L)
        File(proc, "self").mkdirs()
        File(proc, "self/smaps_rollup").writeText("Pss: 1 kB\n")
        val probe = AndroidProbe(app, null, { false }, proc = proc, thermal = tmp.newFolder("thermal-rollup"))
        assertEquals(150_000L * 1024, probe.sample().appPssBytes)
    }

    /** A framework restart (frames/aqara-s1-plus/research/framework-restart.md): the binder call throws, as getMemoryInfo rethrows it. */
    @Implements(ActivityManager::class)
    class DeadSystemActivityManager : ShadowActivityManager() {
        @Implementation
        override fun getMemoryInfo(outInfo: ActivityManager.MemoryInfo?) {
            throw RuntimeException(DeadSystemException())
        }
    }

    @Test @Config(shadows = [DeadSystemActivityManager::class])
    fun aDeadSystemServerLosesOnlyTheMemoryReading() {
        val s = probe().sample()
        assertNull(s.memoryAvailableBytes)
        assertEquals(47.999f, s.cpuTemperature!!, 0.0005f)
    }
}

/**
 * The app's processes as Android lists them, each with a `smaps_rollup` of [pss] KiB under [proc]. [ProcessPssShadow]
 * gives Android's count for the same pids as twice that, so a test shows which source a reading came from.
 */
private fun appProcesses(proc: File, vararg pss: Pair<Int, Long>) {
    val am = RuntimeEnvironment.getApplication().getSystemService(ActivityManager::class.java)
    shadowOf(am).setProcesses(pss.map { (pid, _) -> ActivityManager.RunningAppProcessInfo("dev.glassworks.generic_android.legacy", pid, null) })
    for ((pid, kib) in pss) File(proc, "$pid").apply { mkdirs(); File(this, "smaps_rollup").writeText("Rss: 1 kB\nPss: $kib kB\n") }
    ProcessPssShadow.pssKib = pss.associate { (pid, kib) -> pid to (kib * 2).toInt() }
}

/** Android's own PSS count, `getProcessMemoryInfo`, which Robolectric doesn't have: [pssKib] by pid. */
@Implements(ActivityManager::class)
class ProcessPssShadow : ShadowActivityManager() {
    @Implementation
    fun getProcessMemoryInfo(pids: IntArray): Array<Debug.MemoryInfo> =
        pids.map { pid -> Debug.MemoryInfo().apply { otherPss = pssKib[pid] ?: 0 } }.toTypedArray()

    companion object {
        var pssKib: Map<Int, Int> = emptyMap()
    }
}

/** The probe on Android 6 (#198): the app's start time comes from `/proc/self/stat`, as Android 6 has no API for it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23], application = Application::class)
class AndroidProbeApi23Test {
    @get:Rule val tmp = TemporaryFolder()

    /**
     * The generic app's legacy build on the lululemon Mirror: Android 6 on a 3.10 kernel, which has no `smaps_rollup`.
     * Every reading the pane sends still has a value, App RAM from Android's own count.
     */
    @Test @Config(shadows = [ProcessPssShadow::class])
    fun everyReadingHasAValueOnAndroid6WithoutSmapsRollup() {
        val app = RuntimeEnvironment.getApplication()
        val proc = tmp.newFolder("proc-6")
        appProcesses(proc, 4242 to 100_000L, 4243 to 50_000L)
        File(proc, "4242/smaps_rollup").delete()
        File(proc, "4243/smaps_rollup").delete()
        File(proc, "stat").writeText("cpu  100 0 50 800 50 0 0 0 0 0\ncpu0 100 0 50 800 50 0 0 0 0 0\n")
        File(proc, "self").mkdirs()
        File(proc, "self/stat").writeText("4242 (dev.glassworks.generic_android.legacy) S 1 1 0 0 -1 4194624 1 0 0 0 7 3 0 0 20 0 50 0 360000 1000 200\n")
        shadowOf(app.getSystemService(ActivityManager::class.java)).setMemoryInfo(ActivityManager.MemoryInfo().apply { availMem = 300L shl 20 })
        ShadowStatFs.registerStats(Environment.getDataDirectory(), 1000, 600, 500)
        val info = ShadowWifiInfo.newInstance()
        shadowOf(info).setRssi(-60)
        shadowOf(info).setLinkSpeed(65)
        shadowOf(app.getSystemService(WifiManager::class.java)).setConnectionInfo(info)

        val s = AndroidProbe(app, null, { false }, proc = proc, thermal = tmp.newFolder("thermal-6")).sample()
        assertEquals(CpuTimes(total = 1000, idle = 850), s.cpu)
        assertEquals(300L shl 20, s.memoryAvailableBytes)
        assertEquals("Android's count, twice the rollups", 300_000L * 1024, s.appPssBytes)
        assertEquals(500L * ShadowStatFs.BLOCK_SIZE, s.storageFreeBytes)
        assertEquals(-60, s.wifiRssi)
        assertEquals(65, s.wifiLinkSpeed)
        assertEquals(360000L * 1000 / Os.sysconf(OsConstants._SC_CLK_TCK), s.appStartElapsedMs)
        assertNull("no thermal zone, no temperature", s.cpuTemperature)
    }

    /** A process Android can't count (0) leaves App RAM unknown: a subtotal is not the total. */
    @Test @Config(shadows = [ProcessPssShadow::class])
    fun aProcessAndroidCantCountLeavesAppRamUnknown() {
        val proc = tmp.newFolder("proc-gone")
        appProcesses(proc, 4242 to 100_000L)
        File(proc, "4242/smaps_rollup").delete()
        ProcessPssShadow.pssKib = emptyMap()
        assertNull(AndroidProbe(RuntimeEnvironment.getApplication(), null, { false }, proc = proc, thermal = tmp.newFolder("thermal-gone")).sample().appPssBytes)
    }

    /** A failed read is unknown, not "now"; once read, the start stays, whatever later reads do (#380 review). */
    @Test fun appStartIsUnknownUntilReadThenKept() {
        val proc = tmp.newFolder("proc2")
        val probe = AndroidProbe(RuntimeEnvironment.getApplication(), null, { false }, proc = proc, thermal = tmp.newFolder("thermal2"))
        assertEquals(null, probe.sample().appStartElapsedMs)
        File(proc, "self").mkdirs()
        File(proc, "self/stat").writeText("4242 (sh.edm.s1kiosk) S 1 1 0 0 -1 4194624 1 0 0 0 7 3 0 0 20 0 50 0 360000 1000 200\n")
        val ticks = Os.sysconf(OsConstants._SC_CLK_TCK)
        assertEquals(360000L * 1000 / ticks, probe.sample().appStartElapsedMs)
        File(proc, "self/stat").writeText("garbage")
        assertEquals(360000L * 1000 / ticks, probe.sample().appStartElapsedMs)
    }

    @Test fun appStartComesFromProcSelfStat() {
        val proc = tmp.newFolder("proc")
        File(proc, "self").mkdirs()
        File(proc, "self/stat").writeText("4242 (sh.edm.s1kiosk) S 1 1 0 0 -1 4194624 1 0 0 0 7 3 0 0 20 0 50 0 360000 1000 200\n")
        val ticks = Os.sysconf(OsConstants._SC_CLK_TCK)
        val probe = AndroidProbe(RuntimeEnvironment.getApplication(), null, { false }, proc = proc, thermal = tmp.newFolder("thermal"))
        assertEquals(360000L * 1000 / ticks, probe.sample().appStartElapsedMs)
    }
}

/** Robolectric's Process has no start time (0): this one says 4242, so the probe's use of it shows. */
@Implements(Process::class)
class StartTimeShadow : ShadowProcess() {
    companion object {
        @JvmStatic @Implementation
        fun getStartElapsedRealtime(): Long = 4242L
    }
}

/** From Android 7 the probe takes the start time from Process (#380's modern branch), not from `/proc`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class, shadows = [StartTimeShadow::class])
class AndroidProbeStartTimeTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun appStartComesFromProcess() {
        val probe = AndroidProbe(RuntimeEnvironment.getApplication(), null, { false }, proc = tmp.newFolder("proc"), thermal = tmp.newFolder("thermal"))
        assertEquals(4242L, probe.sample().appStartElapsedMs)
    }
}
