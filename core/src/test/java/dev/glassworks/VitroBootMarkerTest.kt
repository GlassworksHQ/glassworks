/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.glassworks.platform.Platform
import dev.glassworks.platform.PlatformApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivityManager

/**
 * The boot marker: a start with Vitro on that ends any way but a Java crash (a native crash in libwebrtc or GeckoView,
 * an ANR, a kill, a freeze) is counted at the next start, so a panel that fails on every start still gets Vitro off.
 * Each "start" is a new process: a new sentinel, a new pid, on the settings the last one left.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VitroBootMarkerTest {
    private val app = RuntimeEnvironment.getApplication()
    private var now = 1_000_000L
    private var wall = 1_700_000_000_000L
    private var pid = 4_000
    private var boot: String? = "count:7"
    private var updatedAt = 0L
    private var killed = 0

    /** How each pid ended, as Android 11+ keeps it. */
    private val exits = HashMap<Int, PlatformApi.ProcessExit>()
    private val previousHandler = Thread.getDefaultUncaughtExceptionHandler()

    @Before fun clean() {
        app.getSharedPreferences("kiosk", Context.MODE_PRIVATE).edit().clear().commit()
        prefs().vitroEnabled = true
    }

    @After fun restore() {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
    }

    private fun prefs() = KioskPrefs(app)

    /** A new process, [ms] after the last one began. */
    private fun start(ms: Long = 5_000L): Pair<VitroSentinel, Boolean> {
        now += ms; wall += ms; pid += 1
        val myPid = pid
        val s = VitroSentinel(
            prefs(), clock = { now }, wallClock = { wall }, boot = { boot }, pid = { myPid },
            exitOf = { exits[it] }, updatedAt = { updatedAt }, kill = { killed++ },
        )
        return s to s.start()
    }

    /** The process that just ran ended [ms] after its start, for [reason], as Android records it. */
    private fun ended(reason: Int, ms: Long = 3_000L, description: String? = null) {
        exits[pid] = PlatformApi.ProcessExit(reason, description, wall + ms)
    }

    @Test fun aStartThatDiesBeforeItDrawsTwiceTurnsVitroOff() {
        // A native crash in libwebrtc before the dashboard: no Java exception, nothing the handler sees.
        val (_, on1) = start()
        assertTrue(on1)
        assertNotNull("begun before Vitro draws", prefs().vitroStart)
        val (_, on2) = start()
        assertTrue("one failed start isn't a loop", on2)
        assertEquals(1, prefs().vitroCrashStrikes)
        val (_, on3) = start()
        assertFalse("the second failed start in a row: this start runs on the browser engine", on3)
        val p = prefs()
        assertFalse(p.vitroEnabled)
        assertEquals("Stopped before the dashboard was drawn", p.vitroTurnedOff?.reason)
        assertTrue("the panel says so, as for a Java crash", p.takeVitroOffNotice())
        assertNull("nothing left open", p.vitroStart)
    }

    @Test fun androidNamesANativeCrashOrAnAnr() {
        val (first, _) = start()
        first.onPageDrawn()
        ended(ApplicationExitInfo.REASON_CRASH_NATIVE, description = "crash in libjingle_peerconnection_so.so")
        start()
        assertEquals("a native crash after the first draw still counts", 1, prefs().vitroCrashStrikes)
        ended(ApplicationExitInfo.REASON_ANR, description = "Input dispatching timed out")
        val (_, on) = start()
        assertFalse(on)
        assertEquals("Not responding (ANR) (Input dispatching timed out)", prefs().vitroTurnedOff?.reason)
    }

    @Test fun aKillForMemoryOrByTheUserIsntAFailedStart() {
        repeat(2) { start(); ended(ApplicationExitInfo.REASON_LOW_MEMORY) }
        repeat(2) { start(); ended(ApplicationExitInfo.REASON_USER_REQUESTED) } // am force-stop, a measuring run
        repeat(2) { start(); ended(ApplicationExitInfo.REASON_EXIT_SELF) }
        repeat(2) { start(); ended(ApplicationExitInfo.REASON_OTHER) } // the system's own, e.g. after an update
        start()
        assertTrue(prefs().vitroEnabled)
        assertEquals(0, prefs().vitroCrashStrikes)
    }

    @Test fun afterTheFirstDrawAKillAndroidDoesntNameDoesntCount() {
        // Below Android 11 nothing says how a process ended: after the dashboard drew, a kill can be anyone's.
        repeat(3) { start().first.onPageDrawn() }
        repeat(2) { start().first.onPageDrawn(); ended(ApplicationExitInfo.REASON_SIGNALED) }
        start()
        assertTrue(prefs().vitroEnabled)
        assertEquals(0, prefs().vitroCrashStrikes)
    }

    @Test fun aRebootOrPowerLossMidStartDoesntCount() {
        repeat(3) {
            start()
            boot = "count:${8 + it}" // the panel lost power and booted again
        }
        start()
        assertTrue(prefs().vitroEnabled)
        assertEquals(0, prefs().vitroCrashStrikes)
    }

    @Test fun withoutABootIdTheElapsedClockTellsABoot() {
        boot = null // Android 6, and /proc says nothing
        start()
        now = 20_000L // the elapsed clock started over: a new boot, though the wall clock ran on
        start()
        assertEquals("a reboot", 0, prefs().vitroCrashStrikes)
        start()
        assertEquals("the same boot: the elapsed clock and the boot's wall time agree", 1, prefs().vitroCrashStrikes)
    }

    @Test fun anUpdateDoesntCount() {
        repeat(2) {
            start()
            updatedAt = wall + 1_000L // installed over the running app
        }
        start()
        assertTrue(prefs().vitroEnabled)
        assertEquals(0, prefs().vitroCrashStrikes)
    }

    @Test fun theSetupAndSignInScreensDontCount() {
        repeat(3) { start().first.onSetupShown() } // no dashboard yet: setup, or the sign-in step
        start()
        assertTrue(prefs().vitroEnabled)
        assertEquals(0, prefs().vitroCrashStrikes)
    }

    @Test fun theAppsOwnRestartDoesntCount() {
        repeat(3) { start().first.beforeRestart() }
        start()
        assertEquals(0, prefs().vitroCrashStrikes)
    }

    @Test fun aStartThatStaysUpThroughTheWindowSettles() {
        start() // one failed start
        val (up, _) = start()
        assertEquals(1, prefs().vitroCrashStrikes)
        now += VitroSentinel.WINDOW_MS
        up.onStable()
        assertNull(prefs().vitroStart)
        assertEquals(0, prefs().vitroCrashStrikes)
        start()
        assertEquals("killed later, after the window: not counted", 0, prefs().vitroCrashStrikes)
    }

    @Test fun anEndAndroidDatesAfterTheWindowDoesntCount() {
        start(); ended(ApplicationExitInfo.REASON_CRASH_NATIVE, ms = VitroSentinel.WINDOW_MS + 5_000L)
        start()
        assertEquals(0, prefs().vitroCrashStrikes)
    }

    @Test fun aJavaCrashCountsOnceNotAgainAtTheNextStart() {
        val (first, _) = start()
        first.onCrash(IllegalStateException("boom"))
        ended(ApplicationExitInfo.REASON_CRASH)
        start()
        assertEquals("the handler's strike, and no second one for its marker", 1, prefs().vitroCrashStrikes)
    }

    @Test fun turningVitroOffOrOnClearsTheMarker() {
        start()
        prefs().vitroEnabled = false
        assertNull(prefs().vitroStart)
        prefs().vitroEnabled = true
        start()
        assertEquals(0, prefs().vitroCrashStrikes)
        assertNotNull(prefs().vitroStart)
        prefs().vitroEnabled = true // Labs again, or `--ez vitro true`
        assertNull(prefs().vitroStart)
    }

    @Test fun aFactoryResetClearsTheMarker() {
        start(); start()
        assertEquals(1, prefs().vitroCrashStrikes)
        assertTrue(prefs().resetSettings())
        assertNull(prefs().vitroStart)
        assertEquals(0, prefs().vitroCrashStrikes)
        assertFalse("reset leaves Vitro off", prefs().vitroEnabled)
    }

    @Test fun aFrozenStartCountsAndEnds() {
        val (s, _) = start()
        now += 40_000L
        assertTrue(s.onFrozen(30_000L))
        assertEquals("the sentinel ended the frozen process", 1, killed)
        assertEquals(1, prefs().vitroCrashStrikes)
        assertNull("counted already: the next start doesn't count it again", prefs().vitroStart)
        val (next, _) = start()
        now += 40_000L
        assertTrue(next.onFrozen(30_000L))
        assertFalse(prefs().vitroEnabled)
        assertTrue(prefs().vitroTurnedOff!!.reason.startsWith("Froze at start"))
    }

    @Test fun aFreezeAfterTheWindowOrAfterSetupIsntAStartFreeze() {
        val (late, _) = start()
        now += VitroSentinel.WINDOW_MS + 40_000L
        assertFalse(late.onFrozen(30_000L))
        val (setup, _) = start()
        setup.onSetupShown()
        now += 40_000L
        assertFalse(setup.onFrozen(30_000L))
        assertEquals(0, killed)
    }

    @Test fun theFreezeWatchFiresOnlyWhenTheMainThreadStopsAnswering() {
        val main = Handler(Looper.getMainLooper())
        var uptime = 0L
        var stalled = -1L
        // The main thread answers: the watch runs its rounds and never fires.
        var rounds = 0
        VitroSentinel.FreezeWatch(main, uptime = { uptime }, sleep = { uptime += it; shadowOf(Looper.getMainLooper()).idle() },
            armed = { rounds++ < 40 }, onFrozen = { stalled = it }).run()
        assertEquals(-1L, stalled)
        // The main thread is stuck (this test's thread is the main looper's, and it doesn't idle): it fires once.
        VitroSentinel.FreezeWatch(main, uptime = { uptime }, sleep = { uptime += it }, armed = { true }, onFrozen = { stalled = it }).run()
        assertTrue(stalled >= VitroSentinel.FREEZE_MS)
    }

    @Test fun androidsExitRecordReachesTheSentinel() {
        // The glue on Android 11+: Platform reads ApplicationExitInfo for the marker's pid.
        val am = app.getSystemService(ActivityManager::class.java)
        shadowOf(am).addApplicationExitInfo(
            ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder().setPid(4242).setProcessName(app.packageName)
                .setReason(ApplicationExitInfo.REASON_CRASH_NATIVE).setDescription("signal 11").setTimestamp(wall).build(),
        )
        val exit = Platform.processExit(app, 4242)
        assertEquals(ApplicationExitInfo.REASON_CRASH_NATIVE, exit?.reason)
        assertEquals("signal 11", exit?.description)
        assertNull(Platform.processExit(app, 4243))
    }
}
