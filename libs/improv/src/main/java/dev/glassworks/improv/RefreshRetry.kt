package dev.glassworks.improv

import android.os.Handler

/**
 * The tries of a failed legacy advertising refresh ([ImprovBleServer], Android 6 and 7): after each failure, [run] again
 * after the next of [delaysMs]; once they are used up, [failed] says to give up, and the count starts over. All calls on
 * [main]'s thread.
 */
internal class RefreshRetry(
    private val main: Handler,
    private val delaysMs: LongArray,
    private val run: () -> Unit,
) {
    /** Tries so far in this round. */
    var tries: Int = 0
        private set

    /** A try is posted and hasn't run yet. */
    var pending: Boolean = false
        private set

    private val tick = Runnable { pending = false; run() }

    /**
     * A refresh failed: posts the next try and returns its delay, or returns null when every try is used up (the caller
     * reports the failure; a later refresh starts a new round).
     */
    fun failed(): Long? {
        if (tries >= delaysMs.size) { tries = 0; return null }
        val wait = delaysMs[tries++]
        main.removeCallbacks(tick)
        pending = true
        main.postDelayed(tick, wait)
        return wait
    }

    /** A start succeeded, or the server stopped: no try is pending and the next failure starts a new round. */
    fun reset() {
        main.removeCallbacks(tick)
        pending = false
        tries = 0
    }

    /** How many tries a round has. */
    val size: Int get() = delaysMs.size
}
