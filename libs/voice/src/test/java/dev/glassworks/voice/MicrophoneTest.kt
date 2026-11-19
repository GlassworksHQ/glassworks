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

    @Test
    fun withoutThePermissionItMakesNoRecorder() {
        assertEquals(0, recordersMade(allowed = false))
    }

    @Test
    fun withThePermissionItMakesOne() {
        assertEquals(1, recordersMade(allowed = true))
    }
}
