/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * The way back from Vitro (Settings → Labs) when it keeps the app from starting. Vitro is read once per process, so a
 * dashboard that crashes it would crash every start, and the Manage page, in the same process, would go down with
 * it. While Vitro is on, the sentinel watches for the app crashing (an uncaught exception on any thread, the main
 * thread's included) within [WINDOW_MS] of its start. [STRIKES] such crashes in a row turn Vitro off for the next
 * start, which then runs on the browser engine, and the panel and the Manage page say why. A start that stays up
 * for the window clears the count, so one crash, or a restart, never turns it off. Settings → Labs turns it on again.
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
) {
    private var startedAt = 0L

    /**
     * This process has counted its crash: a second thread dying in the same process ending adds nothing. Read and
     * written under [lock], which [onCrash] holds while it records: a second thread waits there until the first
     * one's strike is written, so its hand-off to Android's handler can't end the process before that.
     */
    private var counted = false
    private val lock = Any()

    /** Whether this start runs on Vitro: its setting, as [VitroSentinel] left it. Starts the window when it does. */
    fun start(): Boolean {
        if (!prefs.vitroEnabled) return false
        startedAt = clock()
        return true
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
            prefs.vitroCrashStrikes = 0
            Log.w(TAG, "Vitro: the app crashed ${upMs / 1000} s after it started, outside the start window; Vitro stays on")
            return@runCatching false
        }
        val strikes = prefs.vitroCrashStrikes + 1
        if (strikes < STRIKES) {
            prefs.vitroCrashStrikes = strikes
            Log.w(TAG, "Vitro: the app crashed ${upMs / 1000} s after it started (${reason(error)}, in Vitro: $inVitro); " +
                "strike $strikes of $STRIKES")
            return@runCatching false
        }
        if (!turnOff(reason(error), wallClock())) {
            // The next start would read Vitro on again: say so, and don't claim the way back worked. Its next crash
            // tries the write again.
            Log.e(TAG, "Vitro: the app crashed $strikes times in a row just after it started (${reason(error)}), but " +
                "turning Vitro off couldn't be saved; the next start may use Vitro again")
            return@runCatching false
        }
        Log.e(TAG, "Vitro: the app crashed $strikes times in a row just after it started (${reason(error)}, in Vitro: " +
            "$inVitro); Vitro is off from the next start, which uses the browser engine. Settings → Labs turns it on again")
        true
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
            if (prefs.vitroEnabled && prefs.vitroCrashStrikes != 0) prefs.vitroCrashStrikes = 0
        }
    }

    /**
     * Watches this process: [onCrash] runs before [Thread]'s previous default handler (Android's, which ends the
     * process), and [onStable] once the window has passed on [main].
     */
    fun install(main: Handler = Handler(Looper.getMainLooper())) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            onCrash(error)
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
        main.postDelayed(::onStable, WINDOW_MS)
    }

    companion object {
        private const val TAG = "Glassworks"
        private const val VITRO_PACKAGE = "dev.glassworks.engine.vitro"

        /** A crash this soon after the start counts ([onCrash]). Vitro's dashboard is up in a few seconds. */
        const val WINDOW_MS = 60_000L

        /** Crashes in a row, each soon after its start, that turn Vitro off. */
        const val STRIKES = 2

        /**
         * At process start (the app's runtime): whether Vitro draws the dashboard in this process. With Vitro on, the
         * sentinel watches the process from here. With Vitro turned off by the sentinel, the log says so once.
         */
        fun vitroAtStart(context: Context, prefs: KioskPrefs = KioskPrefs(context)): Boolean {
            prefs.vitroTurnedOff?.let { off ->
                Log.w(TAG, "Vitro is off: it was turned off after the app crashed at start (${off.reason}). " +
                    "Settings → Labs turns it on again")
            }
            val sentinel = VitroSentinel(prefs)
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

        /** The exception and the first of Glassworks' frames it came from: `ClassCastException in WeatherCardView.showMetric`. */
        internal fun reason(error: Throwable): String {
            val root = generateSequence(error) { it.cause }.take(8).last()
            val frame = root.stackTrace.firstOrNull { it.className.startsWith("dev.glassworks") } ?: root.stackTrace.firstOrNull()
            val where = frame?.let { " in ${it.className.substringAfterLast('.').substringBefore('$')}.${it.methodName}" }.orEmpty()
            return "${root.javaClass.simpleName}$where"
        }
    }
}
