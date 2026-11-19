package dev.glassworks.s1.frame

import android.os.SystemClock
import android.util.Log
import java.time.Instant

/**
 * Home Assistant's zone matched to the panel's ([TimeZoneChoices.homeAssistant]), once per rule and zone,
 * for the panel's time zone page and its twin on the Manage page (#269): both read the same answer. The
 * match reads a year of offsets, so it runs on [background] (the pane's own thread), never the caller's.
 * Any thread.
 */
internal class HomeAssistantZoneMatch(
    private val choices: TimeZoneChoices,
    /** Runs the match; false when it couldn't take the work (the pane's thread had stopped). */
    private val background: (Runnable) -> Boolean,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    /** The last answer, by (rule, zone). */
    private var done: Pair<Pair<String, String>, TimeZoneChoices.HomeAssistantZone?>? = null

    /** The rule and zone being matched now, and who waits for it. */
    private var running: Pair<String, String>? = null
    private var runningSince = 0L
    private val waiting = ArrayList<() -> Unit>()

    /**
     * The match for [rule] against [zone], or null until it is done; then [then] runs, on the background
     * thread. A refused match is tried again on the next call (#235 verifier), and so is one taken but never
     * run (the pane stopped and its thread dropped the work) after [LOST_MS] (CodeRabbit on #331).
     */
    fun get(rule: String, zone: String, then: () -> Unit = {}): TimeZoneChoices.HomeAssistantZone? {
        val key = rule to zone
        synchronized(this) {
            done?.let { (k, v) -> if (k == key) return v }
            waiting += then
            if (running == key && clock() - runningSince < LOST_MS) return null
            running = key
            runningSince = clock()
        }
        val queued = background(Runnable {
            val started = SystemClock.elapsedRealtimeNanos()
            val found = runCatching { choices.homeAssistant(rule, zone, Instant.now()) }.getOrNull()
            // The panel run records this (#235).
            Log.i(TimeZoneKeeper.TAG, "matched Home Assistant's zone in ${(SystemClock.elapsedRealtimeNanos() - started) / 1_000_000} ms: $found")
            val callbacks = synchronized(this) {
                if (running != key) return@Runnable
                running = null
                done = key to found
                waiting.toList().also { waiting.clear() }
            }
            callbacks.forEach { it() }
        })
        synchronized(this) {
            if (!queued && running == key) { running = null; waiting.clear() }
            // Run at once (a test's inline thread): the answer is here already.
            return done?.takeIf { it.first == key }?.second
        }
    }

    companion object {
        /** A match takes well under a second on the panel (#235): one still running after this was dropped. */
        const val LOST_MS = 30_000L
    }
}
