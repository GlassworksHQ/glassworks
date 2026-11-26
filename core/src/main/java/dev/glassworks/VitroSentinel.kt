/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks

import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import dev.glassworks.platform.Platform
import dev.glassworks.platform.PlatformApi

/**
 * The way back from Vitro (Settings → Labs) when it keeps the app from starting. Vitro is read once per process, so a
 * dashboard that crashes it would crash every start, and the Manage page, in the same process, would go down with
 * it. While Vitro is on, the sentinel watches each start for [WINDOW_MS]. [STRIKES] failed starts in a row turn Vitro
 * off for the next start, which then runs on the browser engine, and the panel and the Manage page say why. A start
 * that stays up for the window clears the count, so one crash, or a restart, never turns it off. Settings → Labs
 * turns it on again.
 *
 * A failed start is one of these:
 * - an uncaught exception on any thread ([onCrash]), counted as it happens;
 * - the process ending any other way (a native crash in libwebrtc or GeckoView, an ANR, a kill) before the first page
 *   was drawn: [start] marks the start as begun ([KioskPrefs.beginVitroStart]) before Vitro draws, [onPageDrawn]
 *   marks it drawn, and the next start counts a marker it finds still open ([judgeLastStart]). After the first draw,
 *   only a native crash or an ANR that Android names (ApplicationExitInfo, Android 11+) counts: a kill there can be
 *   anyone's, a developer's force stop or a measuring run;
 * - the main thread not answering for [FREEZE_MS] inside the window ([FreezeWatch]): nothing else would end a frozen
 *   start, so the sentinel counts it and ends the process.
 *
 * What never counts: a reboot or power loss during the start (the marker is from another boot), an update of the app,
 * a kill Android says was for memory or by the user or the system, the app's own restart ([beforeRestart]), and a
 * start that shows the setup or sign-in screen instead of a dashboard ([onSetupShown]). Turning Vitro on or off, and
 * a factory reset, clear the marker with the rest.
 *
 * Cards contain their own failures first (engine-vitro's VitroFailures): this is for what gets past them.
 */
class VitroSentinel(
    private val prefs: KioskPrefs,
    /** Time since boot, ms: the window runs on it. */
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    /** Wall clock, ms since the epoch: when Vitro was turned off, as the Labs page says it. */
    private val wallClock: () -> Long = System::currentTimeMillis,
    /** Writes Vitro off with why and when; false when the write didn't reach storage ([KioskPrefs.turnVitroOffAfterCrashes]). */
    private val turnOff: (reason: String, atMs: Long) -> Boolean = prefs::turnVitroOffAfterCrashes,
    /** This boot, where Android tells one boot from the next ([bootId]); null where it can't. */
    private val boot: () -> String? = { null },
    /** This process. */
    private val pid: () -> Int = Process::myPid,
    /** How the process with that pid ended, where Android keeps it (Android 11+, [PlatformApi.processExit]). */
    private val exitOf: (pid: Int) -> PlatformApi.ProcessExit? = { null },
    /** When the app was last installed or updated, ms since the epoch: an update ends the process. */
    private val updatedAt: () -> Long = { 0L },
    /** Ends this process: a frozen start, once counted ([onFrozen]). */
    private val kill: () -> Unit = { Process.killProcess(Process.myPid()) },
) {
    private var startedAt = 0L

    /**
     * This process has counted its crash: a second thread dying in the same process ending adds nothing. Read and
     * written under [lock], which [onCrash] holds while it records: a second thread waits there until the first
     * one's strike is written, so its hand-off to Android's handler can't end the process before that.
     */
    private var counted = false
    private val lock = Any()

    /** This start's marker is settled: the setup screen, the app's own restart, or the window passed. Under [lock]. */
    private var settled = false

    /** This start drew its first page ([onPageDrawn]). Under [lock]. */
    private var drawn = false

    /**
     * Whether this start runs on Vitro: its setting, as [VitroSentinel] left it. With Vitro on, the last start is
     * judged first ([judgeLastStart]), which can turn Vitro off for this one; then this start is marked as begun and
     * the window starts.
     */
    fun start(): Boolean {
        if (!prefs.vitroEnabled) return false
        startedAt = clock()
        if (!runCatching { judgeLastStart() }.getOrDefault(true)) return false
        runCatching {
            val n = prefs.nextVitroStart
            val marked = prefs.beginVitroStart(KioskPrefs.VitroStart(n, boot(), pid(), startedAt, wallClock()))
            if (marked) Log.i(TAG, "Vitro start $n began")
            else Log.w(TAG, "Vitro start $n began, but its marker couldn't be saved: a crash outside Java won't count")
        }
        return true
    }

    /**
     * The last start with Vitro on left its marker open: it ended before it was settled. Counts it as a strike when it
     * failed ([verdict]), and says what it was either way. False when that strike turned Vitro off.
     */
    private fun judgeLastStart(): Boolean {
        val last = prefs.vitroStart ?: return true
        val failed = verdict(last)
        if (failed == null) {
            prefs.settleVitroStart()
            return true
        }
        return !strike(failed, "Vitro start ${last.n} ended without finishing")
    }

    /** Why [last] was a failed start, or null (and the log says why not) when its end doesn't count. */
    private fun verdict(last: KioskPrefs.VitroStart): String? {
        fun notCounted(why: String): String? {
            Log.i(TAG, "Vitro start ${last.n} ended without finishing: $why; not counted")
            return null
        }
        if (last.pid == pid() && sameBoot(last)) return null // this process: start() ran twice
        if (!sameBoot(last)) return notCounted("the panel restarted (a reboot or a power loss)")
        if (updatedAt() > last.atWallMs) return notCounted("the app was updated")
        // The pid's last end, if Android kept it, and only if it came after this start (a pid is reused).
        val exit = exitOf(last.pid)?.takeIf { it.atWallMs >= last.atWallMs - CLOCK_SLACK_MS }
        if (exit != null && exit.atWallMs - last.atWallMs > WINDOW_MS) {
            // It stayed up through the window, even if [onStable] couldn't run: the strikes before it weren't a loop.
            prefs.recordVitroStrikes(0)
            return notCounted("it ended ${(exit.atWallMs - last.atWallMs) / 1000} s after it started, outside the start window")
        }
        val kind = exit?.let { kindOf(it.reason) }
        val detail = exit?.description?.trim()?.take(DETAIL_MAX)?.takeIf { it.isNotEmpty() }?.let { " ($it)" }.orEmpty()
        return when (kind) {
            ExitKind.LOW_MEMORY -> notCounted("Android closed it to free memory")
            ExitKind.STOPPED -> notCounted("it was stopped (Android's reason ${exit?.reason}$detail)")
            ExitKind.NATIVE_CRASH -> "Native crash$detail"
            ExitKind.ANR -> "Not responding (ANR)$detail"
            // A Java crash the handler would have counted, had its write gone through.
            ExitKind.CRASH -> "Crash$detail"
            ExitKind.OTHER ->
                if (last.drawn) notCounted("after its first page, ended by Android's reason ${exit?.reason}$detail")
                else "Stopped before the dashboard was drawn (Android's reason ${exit?.reason}$detail)"
            null ->
                if (last.drawn) notCounted("after its first page, and Android doesn't say how")
                else "Stopped before the dashboard was drawn"
        }
    }

    /**
     * Whether [last] ran on this boot: Android's boot count where it has one; else the elapsed clock, which only runs
     * forward within a boot, and the boot's own time on the wall clock, which stays put within one.
     */
    private fun sameBoot(last: KioskPrefs.VitroStart): Boolean {
        val now = boot()
        if (now != null && last.boot != null) return now == last.boot
        val elapsed = clock()
        if (elapsed < last.atMs) return false
        return kotlin.math.abs((wallClock() - elapsed) - (last.atWallMs - last.atMs)) < CLOCK_SLACK_MS
    }

    /**
     * One failed start, [what] and [reason]: a strike, and the [STRIKES]th in a row turns Vitro off. Settles the open
     * marker in the same write, so a start is never counted twice. True when this one turned Vitro off.
     */
    private fun strike(reason: String, what: String): Boolean {
        val strikes = prefs.vitroCrashStrikes + 1
        if (strikes < STRIKES) {
            prefs.recordVitroStrikes(strikes)
            Log.w(TAG, "$what ($reason); strike $strikes of $STRIKES")
            return false
        }
        if (!turnOff(reason, wallClock())) {
            // The next start would read Vitro on again: say so, and don't claim the way back worked. Its next crash
            // tries the write again.
            Log.e(TAG, "$what ($reason), $strikes times in a row just after a start, but turning Vitro off couldn't " +
                "be saved; the next start may use Vitro again")
            return false
        }
        Log.e(TAG, "$what ($reason), $strikes times in a row just after a start; Vitro is off from the next start, " +
            "which uses the browser engine. Settings → Labs turns it on again")
        return true
    }

    /**
     * The first page of this start is drawn (Vitro's dashboard, or a page it handed to the web engine): from here a
     * kill doesn't count as a failed start, only a crash does. Once per process.
     */
    fun onPageDrawn() {
        synchronized(lock) {
            if (drawn || settled || counted) return
            drawn = true
            runCatching { prefs.markVitroStartDrawn() }
        }
    }

    /** The setup or sign-in screen is up: this start draws no dashboard, and its end isn't Vitro's. */
    fun onSetupShown() = settle("it showed the setup screen")

    /** The app is restarting itself: not a failed start. Then [onStable], for a start that stayed up. */
    fun beforeRestart() {
        settle("the app restarted itself")
        onStable()
    }

    private fun settle(why: String) {
        synchronized(lock) {
            if (settled || counted) return
            settled = true
            runCatching { if (prefs.vitroStart != null && prefs.settleVitroStart()) Log.i(TAG, "Vitro start settled: $why") }
        }
    }

    /**
     * The main thread hasn't answered for [stalledMs] (uptime): a start frozen inside the window is a failed start.
     * Counts it, then ends the process ([kill]), as nothing else would. True when it did.
     */
    internal fun onFrozen(stalledMs: Long): Boolean {
        val counts = synchronized(lock) {
            if (counted || settled || !prefs.vitroEnabled) return false
            // The stall began after the window: not a start that froze.
            if (clock() - stalledMs - startedAt > WINDOW_MS) return false
            if (Debug.isDebuggerConnected()) return false // a developer paused it
            counted = true
            runCatching { strike("Froze at start (no answer for ${stalledMs / 1000} s)", "Vitro: the app stopped responding") }
            true
        }
        if (counts) kill()
        return counts
    }

    /** [FreezeWatch] keeps watching: this start isn't settled or counted, and a stall now could still start in the window. */
    internal fun watchingFreezes(): Boolean = synchronized(lock) {
        !counted && !settled && clock() - startedAt < WINDOW_MS + FREEZE_MS
    }

    /**
     * The app is crashing with [error] (the process ends after this). A crash within [WINDOW_MS] of [start] is a
     * strike; the [STRIKES]th in a row turns Vitro off. True when this one did. Never throws.
     */
    fun onCrash(error: Throwable): Boolean = synchronized(lock) { record(error) }

    private fun record(error: Throwable): Boolean = runCatching {
        if (!prefs.vitroEnabled) return@runCatching false
        // One process end, one strike, however many threads die on the way.
        if (counted) return@runCatching false
        counted = true
        val upMs = clock() - startedAt
        val inVitro = generateSequence(error) { it.cause }.take(8)
            .any { t -> t.stackTrace.any { it.className.startsWith(VITRO_PACKAGE) } }
        if (upMs > WINDOW_MS) {
            // This start stayed up through the window, even if [onStable] couldn't run (a callback held the main
            // thread across it): the strikes before it weren't a loop.
            prefs.recordVitroStrikes(0)
            Log.w(TAG, "Vitro: the app crashed ${upMs / 1000} s after it started, outside the start window; Vitro stays on")
            return@runCatching false
        }
        strike(reason(error), "Vitro: the app crashed ${upMs / 1000} s after it started (in Vitro: $inVitro)")
    }.getOrDefault(false)

    /**
     * Clears the strikes once this start has stayed up through the window, measured on [clock], the clock a crash is
     * measured on: earlier, it does nothing. The process checks it after [WINDOW_MS] of uptime ([install]), and when
     * the app comes back to the front or restarts itself ([checkStable]): uptime stops in deep sleep, so the timer
     * alone could miss a start that slept past the window. Under [lock], and not once this process has counted a
     * crash: a crash just inside the window, recorded on another thread as the mark runs, keeps its strike.
     */
    fun onStable() {
        synchronized(lock) {
            if (counted || clock() - startedAt < WINDOW_MS) return
            settled = true
            if (prefs.vitroEnabled && (prefs.vitroCrashStrikes != 0 || prefs.vitroStart != null)) prefs.recordVitroStrikes(0)
        }
    }

    /**
     * Watches this process: [onCrash] runs before [Thread]'s previous default handler (Android's, which ends the
     * process; without one, the JVM's own behavior: the trace, and only the thread ends), and [onStable] once the window has passed on [main].
     */
    fun install(main: Handler = Handler(Looper.getMainLooper()), watchFreezes: Boolean = true) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            onCrash(error)
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                // No handler before this one (never on Android, whose runtime always sets the one that ends the
                // process; a plain JVM, a test's, has none): what the JVM does then, the thread's trace on stderr
                // and only that thread ending. Not the thread group's handler: it would call this one again.
                System.err.print("Exception in thread \"${thread.name}\" ")
                error.printStackTrace()
            }
        }
        main.postDelayed(::onStable, WINDOW_MS)
        if (watchFreezes) {
            Thread(FreezeWatch(main, armed = ::watchingFreezes, onFrozen = { onFrozen(it) }), "glassworks-vitro-freeze-watch")
                .apply { isDaemon = true }.start()
        }
    }

    /**
     * Watches [main] for a freeze, from its own thread: posts a ping, and when the ping hasn't run after [FREEZE_MS]
     * of uptime (which stops in deep sleep, as the main thread does), calls [onFrozen] with how long it waited. Runs
     * while [armed], and starts a new ping for at most the window's worth of rounds: each takes at least [TICK_MS], so
     * by then the window has passed on any clock.
     */
    internal class FreezeWatch(
        private val main: Handler,
        private val uptime: () -> Long = SystemClock::uptimeMillis,
        private val sleep: (Long) -> Unit = Thread::sleep,
        private val armed: () -> Boolean,
        private val onFrozen: (stalledMs: Long) -> Unit,
    ) : Runnable {
        override fun run() {
            runCatching {
                var rounds = 0L
                while (armed() && rounds++ < WINDOW_MS / TICK_MS) {
                    val sentAt = uptime()
                    val answered = java.util.concurrent.atomic.AtomicBoolean(false)
                    main.post { answered.set(true) }
                    while (!answered.get()) {
                        sleep(TICK_MS)
                        if (!armed()) return
                        val waited = uptime() - sentAt
                        if (waited >= FREEZE_MS && !answered.get()) {
                            onFrozen(waited)
                            return
                        }
                    }
                    sleep(TICK_MS)
                }
            }
        }
    }

    companion object {
        private const val TAG = "Glassworks"
        private const val VITRO_PACKAGE = "dev.glassworks.engine.vitro"

        /** A crash this soon after the start counts ([onCrash]). Vitro's dashboard is up in a few seconds. */
        const val WINDOW_MS = 60_000L

        /** Crashes in a row, each soon after its start, that turn Vitro off. */
        const val STRIKES = 2

        /** The main thread not answering this long inside the window is a frozen start ([FreezeWatch]). Android's own ANR is 5 s. */
        const val FREEZE_MS = 30_000L

        /** How often [FreezeWatch] looks. */
        private const val TICK_MS = 2_000L

        /** Clocks read in two processes agree to within this: a boot's own wall time, an exit after its start. */
        private const val CLOCK_SLACK_MS = 5_000L

        /** Android's words for an exit, at most this long in the log and the Labs record. */
        private const val DETAIL_MAX = 160

        /** How a process ended, by what it means here: a failure of the start, or an end that isn't Vitro's. */
        internal enum class ExitKind { CRASH, NATIVE_CRASH, ANR, LOW_MEMORY, STOPPED, OTHER }

        /** An `ApplicationExitInfo.REASON_*` as an [ExitKind]. The values are Android's, fixed since Android 11. */
        internal fun kindOf(reason: Int): ExitKind = when (reason) {
            ApplicationExitInfo.REASON_CRASH -> ExitKind.CRASH
            ApplicationExitInfo.REASON_CRASH_NATIVE -> ExitKind.NATIVE_CRASH
            ApplicationExitInfo.REASON_ANR -> ExitKind.ANR
            ApplicationExitInfo.REASON_LOW_MEMORY -> ExitKind.LOW_MEMORY
            ApplicationExitInfo.REASON_EXIT_SELF, ApplicationExitInfo.REASON_USER_REQUESTED,
            ApplicationExitInfo.REASON_USER_STOPPED, ApplicationExitInfo.REASON_PERMISSION_CHANGE,
            ApplicationExitInfo.REASON_DEPENDENCY_DIED, ApplicationExitInfo.REASON_FREEZER,
            ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE, ApplicationExitInfo.REASON_PACKAGE_UPDATED,
            // Android's "killed by the system for reasons that aren't the app's", e.g. it just finished an update.
            ApplicationExitInfo.REASON_OTHER -> ExitKind.STOPPED
            // Unknown, signaled, initialization failure, excessive resource use: Android can't say it wasn't Vitro.
            else -> ExitKind.OTHER
        }

        /** This boot: Android's boot count (Android 7+), else the kernel's boot id; null when neither can be read. */
        internal fun bootId(context: Context): String? =
            Platform.bootCount(context)?.let { "count:$it" }
                ?: runCatching { java.io.File("/proc/sys/kernel/random/boot_id").readText().trim() }.getOrNull()
                    ?.takeIf { it.isNotEmpty() }?.let { "id:$it" }

        /** When this app was installed or last updated, ms since the epoch; 0 when Android doesn't say. */
        @Suppress("DEPRECATION")
        private fun updatedAt(context: Context): Long =
            runCatching { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime }.getOrDefault(0L)

        /**
         * At process start (the app's runtime): whether Vitro draws the dashboard in this process. With Vitro on, the
         * sentinel watches the process from here. With Vitro turned off by the sentinel, the log says so once.
         */
        fun vitroAtStart(context: Context, prefs: KioskPrefs = KioskPrefs(context)): Boolean {
            prefs.vitroTurnedOff?.let { off ->
                Log.w(TAG, "Vitro is off: it was turned off after the app crashed at start (${off.reason}). " +
                    "Settings → Labs turns it on again")
            }
            val app = context.applicationContext ?: context
            val sentinel = VitroSentinel(
                prefs,
                boot = { bootId(app) },
                exitOf = { pid -> Platform.processExit(app, pid) },
                updatedAt = { updatedAt(app) },
            )
            if (!sentinel.start()) return false
            sentinel.install()
            current = sentinel
            return true
        }

        /** This process's sentinel, while Vitro is on. */
        @Volatile
        private var current: VitroSentinel? = null

        /**
         * The app came back to the front, or is about to restart itself: a start that has been up through the window
         * (on the elapsed clock, deep sleep included) clears its strikes now ([onStable]). Nothing without Vitro.
         */
        fun checkStable() {
            runCatching { current?.onStable() }
        }

        /** The first page is drawn: Vitro's dashboard, or one it handed to the web engine ([onPageDrawn]). */
        fun pageDrawn() {
            runCatching { current?.onPageDrawn() }
        }

        /** The setup or sign-in screen is up instead of a dashboard ([onSetupShown]). */
        fun setupShown() {
            runCatching { current?.onSetupShown() }
        }

        /** The app is about to restart itself ([beforeRestart]). */
        fun restarting() {
            runCatching { current?.beforeRestart() }
        }

        /** The exception and the first of Glassworks' frames it came from: `ClassCastException in WeatherCardView.showMetric`. */
        internal fun reason(error: Throwable): String {
            val root = generateSequence(error) { it.cause }.take(8).last()
            val frame = root.stackTrace.firstOrNull { it.className.startsWith("dev.glassworks") } ?: root.stackTrace.firstOrNull()
            val where = frame?.let { " in ${it.className.substringAfterLast('.').substringBefore('$')}.${it.methodName}" }.orEmpty()
            return "${root.javaClass.simpleName}$where"
        }
    }
}
