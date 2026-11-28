package dev.glassworks.assist

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * The voice chimes' shape: length, level, a silent start and end (no clicks), and the same bytes every time.
 * To listen after a change, set GLASSWORKS_CHIME_DIR and run this test: it writes wake.wav and timer.wav there.
 */
class ChimesTest {

    private fun samples(pcm: ByteArray): ShortArray {
        val out = ShortArray(pcm.size / 2)
        ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out)
        return out
    }

    private fun peak(s: ShortArray, from: Int = 0, to: Int = s.size) = (from until to).maxOf { abs(s[it].toInt()) } / 32767.0

    @Test
    fun lengthsAndLevels() {
        val wake = samples(Chimes.wake)
        val timer = samples(Chimes.timer)
        assertEquals((1.1 * Chimes.RATE).toInt(), wake.size)
        assertEquals((1.8 * Chimes.RATE).toInt(), timer.size)
        assertEquals(0.20, peak(wake), 0.002)
        assertEquals(0.28, peak(timer), 0.002)
        // Shorter than the 3 s between the timers pane's repeats.
        assertTrue(timer.size < 3 * Chimes.RATE)
    }

    @Test
    fun swellsInAndEndsAtSilence() {
        for (s in listOf(samples(Chimes.wake), samples(Chimes.timer))) {
            val ms = Chimes.RATE / 1000
            assertTrue("the first ms is near silence", peak(s, 0, ms) < 0.002)
            assertTrue("the last 10 ms are near silence", peak(s, s.size - 10 * ms) < 0.001)
            assertEquals("ends at zero", 0, s.last().toInt())
        }
    }

    @Test
    fun theSameBytesEveryTime() {
        // Seeded: every panel plays exactly the sound that was listened to.
        assertArrayEquals(Chimes.makeWake(), Chimes.makeWake())
        assertArrayEquals(Chimes.makeTimer(), Chimes.makeTimer())
    }

    @Test
    fun writesWavsToListen() {
        val dir = System.getenv("GLASSWORKS_CHIME_DIR") ?: return
        File(dir).mkdirs()
        wav(File(dir, "wake.wav"), Chimes.wake)
        wav(File(dir, "timer.wav"), Chimes.timer)
    }

    private fun wav(f: File, pcm: ByteArray) {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(Chimes.RATE).putInt(Chimes.RATE * 2)
            .putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(pcm.size)
        f.writeBytes(h.array() + pcm)
    }
}
