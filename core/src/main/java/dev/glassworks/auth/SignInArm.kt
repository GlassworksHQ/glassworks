package dev.glassworks.auth

/**
 * The panel's own screen asks for a Home Assistant sign-in: its setup screen shows the sign-in QR code. Until [ttlMs]
 * after the last [arm], a sign-in through the QR pages may replace a stored one ([SignInServer]), as the person at the
 * panel asked for. Otherwise only a browser signed in to Manage may, once the panel is signed in. The screen re-arms it
 * each time it draws the code; [disarm] when the code goes. Pure JVM; thread-safe.
 */
class SignInArm(
    private val ttlMs: Long = 10 * 60_000L,
    /** Monotonic, in ms: a wall clock that jumps mustn't stretch it. */
    private val monoMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    @Volatile private var until: Long? = null

    fun arm() { until = monoMs() + ttlMs }

    fun disarm() { until = null }

    fun armed(): Boolean = until?.let { monoMs() < it } == true
}
