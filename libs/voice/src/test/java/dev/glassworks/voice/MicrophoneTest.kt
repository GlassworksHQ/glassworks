package dev.glassworks.voice

import android.app.Application
import android.media.AudioFormat
import android.media.AudioRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** #583: on Android 6 a recorder made without RECORD_AUDIO crashed the app in native code. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MicrophoneTest {
    private fun recordersMade(allowed: Boolean): Int {
        val made = AtomicInteger()
        val tried = CountDownLatch(1)
        val mic = Microphone(
            gain = 1f,
            allowed = { allowed },
            onError = {},
            onFrame = { tried.countDown() },
            onOpenFailed = { tried.countDown() },
            newRecorder = { source, bytes -> made.incrementAndGet(); AudioRecord(source, Microphone.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bytes) },
        )
        mic.start()
        try {
            assertTrue("it tried to open the microphone", tried.await(5, TimeUnit.SECONDS))
        } finally {
            mic.stop()
        }
        return made.get()
    }

    /** The arrays the first [n] frames came in. */
    private fun frameArrays(reuse: Boolean, n: Int = 3): List<ShortArray> {
        val got = java.util.Collections.synchronizedList(ArrayList<ShortArray>())
        val done = CountDownLatch(n)
        val mic = Microphone(
            gain = 1f,
            allowed = { true },
            onError = {},
            onFrame = { got += it; done.countDown() },
            reuseFrame = reuse,
        )
        mic.start()
        try {
            assertTrue("it read $n frames", done.await(5, TimeUnit.SECONDS))
        } finally {
            mic.stop()
        }
        return got.take(n)
    }

    @Test
    fun aConsumerThatCopiesGetsOneArrayAndOneThatKeepsGetsNewOnes() {
        val reused = frameArrays(reuse = true)
        assertTrue("one array for every frame", reused.all { it === reused[0] })
        val kept = frameArrays(reuse = false)
        assertEquals("a new array each frame", kept.size, kept.map { System.identityHashCode(it) }.toSet().size)
    }

    @Test
    fun withoutThePermissionItMakesNoRecorder() {
        assertEquals(0, recordersMade(allowed = false))
    }

    @Test
    fun withThePermissionItMakesOne() {
        assertEquals(1, recordersMade(allowed = true))
    }
}
