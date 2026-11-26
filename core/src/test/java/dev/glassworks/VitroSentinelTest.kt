/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.glassworks.manage.AppManage
import dev.glassworks.manage.Tile
import dev.glassworks.settings.CoreSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * The way back from Vitro: crashes right after the start, twice in a row, turn it off for the next start; one crash,
 * a crash later on, or a plain restart never do. Each "start" here is a new sentinel on the same settings, as each
 * process start makes one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VitroSentinelTest {
    private val app = RuntimeEnvironment.getApplication()
    private var now = 1_000_000L
    private val wall = 1_700_000_000_000L
    private val previousHandler = Thread.getDefaultUncaughtExceptionHandler()

    @Before fun clean() {
        app.getSharedPreferences("kiosk", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun restore() {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
    }

    private fun prefs() = KioskPrefs(app)

    /** A new process: the sentinel starts on the settings as the last one left them. */
    private fun start(): Pair<VitroSentinel, Boolean> {
        now += 10_000L // the restart itself
        val s = VitroSentinel(prefs(), clock = { now }, wallClock = { wall })
        return s to s.start()
    }

    /** What a card in Vitro throws on the main thread (the review's claim 1). */
    private fun vitroCrash(): Throwable = ClassCastException("java.lang.String cannot be cast to java.lang.Number").apply {
        stackTrace = arrayOf(
            StackTraceElement("dev.glassworks.engine.vitro.cards.WeatherCardView", "showMetric", "GraphAndClimateCards.kt", 1106),
            StackTraceElement("android.os.Looper", "loop", "Looper.java", 1),
        )
    }

    @Test fun twoCrashesRightAfterStartTurnVitroOffForTheNextStart() {
        prefs().vitroEnabled = true
        val (first, on1) = start()
        assertTrue(on1)
        now += 3_000L
        assertFalse("one crash isn't a loop", first.onCrash(vitroCrash()))
        assertTrue(prefs().vitroEnabled)

        val (second, on2) = start()
        assertTrue("still on for the second try", on2)
        now += 2_000L
        assertTrue(second.onCrash(vitroCrash()))

        val p = prefs()
        assertFalse(p.vitroEnabled)
        assertEquals("ClassCastException in WeatherCardView.showMetric", p.vitroTurnedOff?.reason)
        assertEquals(wall, p.vitroTurnedOff?.atMs)
        val (_, on3) = start()
        assertFalse("the next start runs on the browser engine", on3)
        // The panel says so once; Labs keeps why.
        assertTrue(p.takeVitroOffNotice())
        assertFalse(p.takeVitroOffNotice())
        assertNotNull(p.vitroTurnedOff)
    }

    @Test fun oneCrashThenAStartThatStaysUpNeverTurnsVitroOff() {
        prefs().vitroEnabled = true
        val (first, _) = start()
        now += 3_000L
        first.onCrash(vitroCrash())
        assertEquals(1, prefs().vitroCrashStrikes)

        // The next start stays up through the window: the strike goes.
        val (second, _) = start()
        now += VitroSentinel.WINDOW_MS
        second.onStable()
        assertEquals(0, prefs().vitroCrashStrikes)

        // Another single crash much later, just after a start: one strike again, Vitro stays on.
        val (third, _) = start()
        now += 1_000L
        assertFalse(third.onCrash(vitroCrash()))
        assertTrue(prefs().vitroEnabled)
        assertNull(prefs().vitroTurnedOff)
    }

    @Test fun aCrashAfterTheWindowIsntAStartLoop() {
        prefs().vitroEnabled = true
        repeat(3) {
            val (s, _) = start()
            now += VitroSentinel.WINDOW_MS + 1
            assertFalse(s.onCrash(vitroCrash()))
        }
        assertTrue(prefs().vitroEnabled)
        assertEquals(0, prefs().vitroCrashStrikes)
    }

    @Test fun twoThreadsDyingInOneProcessAreOneStrike() {
        prefs().vitroEnabled = true
        val (s, _) = start()
        now += 1_000L
        assertFalse(s.onCrash(vitroCrash()))
        assertFalse("the same process ending: not a second strike", s.onCrash(vitroCrash()))
        assertTrue(prefs().vitroEnabled)
        assertEquals(1, prefs().vitroCrashStrikes)
    }

    @Test fun aSecondThreadWaitsUntilTheFirstCrashIsRecorded() {
        prefs().vitroEnabled = true
        val inFirst = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        // The first crash's record is held at the clock read, inside onCrash.
        val slow = VitroSentinel(prefs(), clock = {
            if (Thread.currentThread().name == "first") { inFirst.countDown(); release.await() }
            now
        })
        slow.start()
        val first = Thread({ slow.onCrash(vitroCrash()) }, "first").apply { start() }
        val secondDone = java.util.concurrent.atomic.AtomicBoolean(false)
        var second: Thread? = null
        try {
            assertTrue("the first crash reached its record", inFirst.await(5, java.util.concurrent.TimeUnit.SECONDS))
            second = Thread({ slow.onCrash(vitroCrash()); secondDone.set(true) }, "second").apply { start() }
            second.join(200)
            assertFalse("the second thread waits for the first record", secondDone.get())
        } finally {
            release.countDown() // never leave the first thread blocked, whatever failed above
        }
        // Bounded: a deadlock fails the test instead of hanging the suite.
        first.join(5_000); second?.join(5_000)
        assertFalse("no deadlock", first.isAlive || second?.isAlive == true)
        assertTrue(secondDone.get())
        assertEquals("one strike for the one process end", 1, prefs().vitroCrashStrikes)
    }

    @Test fun theStableMarkKeepsAStrikeThisProcessCounted() {
        // A crash just inside the window, recorded on another thread, then the stable mark runs on the main thread.
        prefs().vitroEnabled = true
        val (s, _) = start()
        now += VitroSentinel.WINDOW_MS - 10
        assertFalse(s.onCrash(vitroCrash()))
        s.onStable()
        assertEquals("the counted crash keeps its strike", 1, prefs().vitroCrashStrikes)
        // The next start crashes early too: two in a row, Vitro is off.
        val (next, _) = start()
        now += 1_000L
        assertTrue(next.onCrash(vitroCrash()))
        assertFalse(prefs().vitroEnabled)
    }

    @Test fun aCrashAfterTheWindowClearsTheStrikesBeforeIt() {
        // The stable mark couldn't run (a callback held the main thread across the window), then that callback threw.
        prefs().vitroEnabled = true
        val (first, _) = start()
        now += 1_000L
        first.onCrash(vitroCrash())
        val (late, _) = start()
        now += VitroSentinel.WINDOW_MS + 10
        assertFalse(late.onCrash(vitroCrash()))
        assertEquals(0, prefs().vitroCrashStrikes)
        val (next, _) = start()
        now += 1_000L
        assertFalse("one early crash after a start that stayed up isn't a loop", next.onCrash(vitroCrash()))
        assertTrue(prefs().vitroEnabled)
    }

    @Test fun plainRestartsChangeNothing() {
        prefs().vitroEnabled = true
        repeat(5) {
            val (s, on) = start()
            assertTrue(on)
            now += 1_000L
            if (it % 2 == 0) s.onStable() // some stay up, others restart soon (a setting that restarts the app)
        }
        assertTrue(prefs().vitroEnabled)
        assertEquals(0, prefs().vitroCrashStrikes)
        assertNull(prefs().vitroTurnedOff)
        assertFalse(prefs().takeVitroOffNotice())
    }

    @Test fun withVitroOffTheSentinelStaysOut() {
        val (s, on) = start()
        assertFalse(on)
        assertFalse(s.onCrash(vitroCrash()))
        assertEquals(0, prefs().vitroCrashStrikes)
        assertFalse(VitroSentinel.vitroAtStart(app, prefs()))
        assertSame("no handler installed", previousHandler, Thread.getDefaultUncaughtExceptionHandler())
    }

    @Test fun turningVitroOnAgainStartsClean() {
        prefs().vitroEnabled = true
        prefs().vitroCrashStrikes = 1
        prefs().turnVitroOffAfterCrashes("ClassCastException in WeatherCardView.showMetric", wall)
        assertNotNull(prefs().vitroTurnedOff)
        // Settings → Labs (or `--ez vitro true`).
        prefs().vitroEnabled = true
        assertNull(prefs().vitroTurnedOff)
        assertEquals(0, prefs().vitroCrashStrikes)
        assertFalse(prefs().takeVitroOffNotice())
    }

    @Test fun theInstalledHandlerCountsTheCrashThenHandsItOn() {
        prefs().vitroEnabled = true
        // Android's handler, which ends the process: by the time it runs, the sentinel has written the crash down.
        val seen = ArrayList<Pair<Int, Boolean>>()
        val android = Thread.UncaughtExceptionHandler { _, _ -> seen += prefs().vitroCrashStrikes to prefs().vitroEnabled }
        val main = Handler(Looper.getMainLooper())
        repeat(2) {
            // Each start is a new process: the sentinel chains onto Android's handler, never the last start's sentinel.
            Thread.setDefaultUncaughtExceptionHandler(android)
            val s = VitroSentinel(prefs(), clock = { now }, wallClock = { wall })
            assertTrue(s.start())
            s.install(main, watchFreezes = false)
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), vitroCrash())
        }
        assertEquals("first: one strike, still on; second: off", listOf(1 to true, 0 to false), seen)
        assertNotNull(prefs().vitroTurnedOff)
    }

    @Test fun withoutAPreviousHandlerOnlyTheThreadEnds() {
        // A plain JVM (this test's) has no default handler: the sentinel records the crash and ends nothing else.
        prefs().vitroEnabled = true
        Thread.setDefaultUncaughtExceptionHandler(null)
        val s = VitroSentinel(prefs(), clock = { now }, wallClock = { wall })
        s.start()
        s.install(Handler(Looper.getMainLooper()), watchFreezes = false)
        val worker = Thread { throw IllegalStateException("background boom") }
        worker.start()
        worker.join(5_000)
        assertFalse(worker.isAlive)
        assertEquals("the crash counted", 1, prefs().vitroCrashStrikes)
        // Still here: the process (this test) didn't end.
        assertTrue(prefs().vitroEnabled)
    }

    @Test fun theStableMarkRunsOnceTheWindowHasPassed() {
        prefs().vitroEnabled = true
        prefs().vitroCrashStrikes = 1
        val s = VitroSentinel(prefs(), clock = { now })
        s.start()
        s.install(Handler(Looper.getMainLooper()), watchFreezes = false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(VitroSentinel.WINDOW_MS - 1))
        assertEquals(1, prefs().vitroCrashStrikes)
        now += VitroSentinel.WINDOW_MS // the elapsed clock, which the timer's uptime never runs ahead of
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertEquals(0, prefs().vitroCrashStrikes)
    }

    @Test fun aStartThatSleptPastTheWindowIsStableWhenTheAppResumes() {
        // One strike inherited; the start runs 5 s, the panel sleeps 65 s (uptime stops, the timer doesn't fire), and the
        // app comes back to the front: on the elapsed clock the start stayed up through the window.
        prefs().vitroEnabled = true
        prefs().vitroCrashStrikes = 1
        val (s, _) = start()
        now += 5_000L
        s.onStable()
        assertEquals("before the window: nothing", 1, prefs().vitroCrashStrikes)
        now += 65_000L
        s.onStable() // what VitroSentinel.checkStable runs on resume, or before the app restarts itself
        assertEquals(0, prefs().vitroCrashStrikes)
        // The next start's early crash is its first strike, not the second.
        val (next, _) = start()
        now += 1_000L
        assertFalse(next.onCrash(vitroCrash()))
        assertTrue(prefs().vitroEnabled)
    }

    @Test fun aFailedFallbackWriteIsReportedAsFailed() {
        prefs().vitroEnabled = true
        prefs().vitroCrashStrikes = 1
        org.robolectric.shadows.ShadowLog.clear()
        // Storage refuses the write: the next start would read Vitro on.
        val s = VitroSentinel(prefs(), clock = { now }, wallClock = { wall }, turnOff = { _, _ -> false })
        s.start()
        now += 1_000L
        assertFalse("not reported as turned off", s.onCrash(vitroCrash()))
        assertTrue(prefs().vitroEnabled)
        val logs = org.robolectric.shadows.ShadowLog.getLogsForTag("Glassworks").map { it.msg }
        assertTrue(logs.toString(), logs.any { "couldn't be saved" in it })
        assertFalse(logs.toString(), logs.any { "Vitro is off from the next start" in it })
    }

    @Test fun aCrashOutsideVitroCountsToo() {
        // The app crashing at start while Vitro is on, wherever the throw comes from: Vitro may have caused it.
        prefs().vitroEnabled = true
        repeat(2) {
            val (s, _) = start()
            now += 1_000L
            s.onCrash(IllegalStateException("elsewhere"))
        }
        assertFalse(prefs().vitroEnabled)
        assertTrue(prefs().vitroTurnedOff!!.reason.startsWith("IllegalStateException"))
    }

    @Test fun theManageOverviewsEngineTileSaysSoAndOpensLabs() {
        val ok = AppManage.engineTile("GeckoView 145.0.1")
        assertEquals(Tile("Engine", "GeckoView 145", "Version 145.0.1", Tile.Tone.OK), ok)
        val off = AppManage.engineTile("GeckoView 145.0.1", vitroTurnedOff = true)
        assertEquals(Tile("Engine", "GeckoView 145", AppManage.VITRO_OFF_TILE, Tile.Tone.PROBLEM, href = "/${CoreSettings.LABS}"), off)
        assertEquals(Tile("Engine", "Unknown", "", Tile.Tone.OK), AppManage.engineTile(""))
    }
}
