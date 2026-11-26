package dev.glassworks.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The chime player's gain on a device whose volume is fixed: each sound reads the master gain as it opens, so a
 * critical alert raised just before it plays at full, with no wait for the device thread to pass the change on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TtsPlayerGainTest {

    private fun TtsPlayer.playOnce() {
        val done = CountDownLatch(1)
        begin(16_000, 2, 1)
        write(ByteArray(320))
        end { done.countDown() }
        assertTrue("played", done.await(5, TimeUnit.SECONDS))
    }

    @Test fun eachSoundOpensAtTheGainOfNow() {
        var master = 0f // muted
        val p = TtsPlayer { master }
        try {
            p.playOnce()
            assertEquals(0f, p.openedAt, 0f)
            master = 1f // a critical alert raised it; nothing told the player
            p.playOnce()
            assertEquals(1f, p.openedAt, 0f)
        } finally { p.release() }
    }

    @Test fun aGainThatFailsToReadPlaysAtFull() {
        val p = TtsPlayer { error("no volume") }
        try {
            p.playOnce()
            assertEquals(1f, p.openedAt, 0f)
        } finally { p.release() }
    }
}
