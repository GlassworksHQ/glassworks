package dev.glassworks.improv

import android.os.Handler
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * A failed legacy advertising refresh (Android 6 and 7) tries again on a schedule, then gives up so the server can say
 * so; a start that succeeds, or the server stopping, cancels what is pending (#944).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23])
class RefreshRetryTest {
    private val looper = shadowOf(Looper.getMainLooper())
    private var runs = 0
    private val retry = RefreshRetry(Handler(Looper.getMainLooper()), ImprovBleServer.REFRESH_RETRY_MS) { runs++ }

    private fun idle(ms: Long) = looper.idleFor(Duration.ofMillis(ms))

    @Test fun eachFailureTriesAgainLaterThenItGivesUp() {
        assertEquals(longArrayOf(1_000L, 2_000L, 4_000L).toList(), ImprovBleServer.REFRESH_RETRY_MS.toList())
        assertEquals(1_000L, retry.failed())
        assertTrue(retry.pending)
        idle(999); assertEquals(0, runs)
        idle(1); assertEquals(1, runs)
        assertFalse(retry.pending)
        assertEquals(2_000L, retry.failed())
        idle(2_000); assertEquals(2, runs)
        assertEquals(4_000L, retry.failed())
        idle(4_000); assertEquals(3, runs)
        assertNull(retry.failed()) // every try used: the server reports the failure
        assertFalse(retry.pending)
        idle(10_000); assertEquals(3, runs)
        assertEquals(1_000L, retry.failed()) // a later refresh starts a new round
    }

    @Test fun whileATryWaitsItIsPendingSoAStateChangeLeavesItToThatTry() {
        retry.failed()
        idle(500)
        assertTrue(retry.pending) // ImprovBleServer.updateAdvertisement returns early: the try starts with the new data
        idle(500)
        assertFalse(retry.pending)
        assertEquals(1, runs)
    }

    @Test fun aStartThatSucceedsOrAStopCancelsThePendingTryAndTheCount() {
        retry.failed(); retry.failed()
        assertEquals(2, retry.tries)
        retry.reset() // onStartSuccess, or stop()
        assertFalse(retry.pending)
        assertEquals(0, retry.tries)
        idle(10_000)
        assertEquals(0, runs)
        assertEquals(1_000L, retry.failed())
    }
}
