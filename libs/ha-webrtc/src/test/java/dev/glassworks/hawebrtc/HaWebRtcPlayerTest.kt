package dev.glassworks.hawebrtc

import android.os.Looper
import android.view.TextureView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The Android player where Robolectric can reach it. libwebrtc's native library isn't on the JVM, so a player here
 * reports start_failed, the way one on a device without it would; the media itself is tested end to end against
 * tools/perf/fakeha.py.
 */
@RunWith(RobolectricTestRunner::class)
class HaWebRtcPlayerTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `the view is a transparent TextureView before anything plays`() {
        val player = HaWebRtcPlayer(context, FakeChannel(), "camera.front_door")
        val view = player.view
        assertTrue(view is TextureView)
        assertFalse((view as TextureView).isOpaque)
        assertEquals(VideoScaling.FIT, player.scaling)
        assertEquals(0 to 0, player.videoWidth to player.videoHeight)
        player.scaling = VideoScaling.FILL
        assertEquals(VideoScaling.FILL, player.scaling)
        player.close()
    }

    @Test
    fun `without libwebrtc's native library the player reports start_failed on the main thread`() {
        val player = HaWebRtcPlayer(context, FakeChannel(), "camera.front_door")
        val done = CountDownLatch(1)
        var got: HaError? = null
        var onMain = false
        player.listener = object : HaWebRtcPlayer.Listener {
            override fun onFirstFrame(sinceStartMs: Long, width: Int, height: Int) = throw AssertionError()
            override fun onError(error: HaError) {
                onMain = Looper.myLooper() == Looper.getMainLooper()
                got = error
                done.countDown()
            }
        }
        player.start()
        val deadline = System.currentTimeMillis() + 10_000
        while (done.count > 0 && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            done.await(20, TimeUnit.MILLISECONDS)
        }
        assertEquals(HaWebRtcStream.START_FAILED, got?.code)
        assertTrue(onMain)
        player.close()
    }

    @Test
    fun `close before start, twice, and calls after close do nothing`() {
        val player = HaWebRtcPlayer(context, FakeChannel(), "camera.front_door")
        player.close()
        player.close()
        player.start()
        player.setActive(false)
    }
}

/** Picture scaling for a 16:9 camera in views of other shapes. */
class FitScaleTest {
    @Test
    fun `fit letterboxes the axis with room to spare`() {
        // 16:9 in a square: full width, 9/16 of the height.
        assertEquals(1f to 0.5625f, fitScale(VideoScaling.FIT, 1920, 1080, 400, 400))
        // 16:9 in a 32:9 strip: half the width.
        assertEquals(0.5f to 1f, fitScale(VideoScaling.FIT, 1920, 1080, 1600, 450))
        // Same shape: nothing to do.
        assertEquals(1f to 1f, fitScale(VideoScaling.FIT, 1280, 720, 640, 360))
    }

    @Test
    fun `fill and stretch, and no frame yet, draw over the whole view`() {
        assertEquals(1f to 1f, fitScale(VideoScaling.FILL, 1920, 1080, 400, 400))
        assertEquals(1f to 1f, fitScale(VideoScaling.STRETCH, 1920, 1080, 400, 400))
        assertEquals(1f to 1f, fitScale(VideoScaling.FIT, 0, 0, 400, 400))
    }
}

/** Who releases a TextureView's SurfaceTexture when its renderer is still drawing into it. */
class ReleaseAfterTest {
    @Test
    fun `a render thread that lets go in time leaves the texture to the view`() {
        var released = 0
        assertTrue(releaseAfter({ done -> done.run() }, { released++ }, 1_000))
        assertEquals("the view releases it, not us", 0, released)
    }

    @Test
    fun `a slow render thread releases the texture itself, after it let go, and the view keeps its hands off`() {
        var later: Runnable? = null
        var released = 0
        // The render thread hasn't finished when the wait ends.
        assertFalse(releaseAfter({ done -> later = done }, { released++ }, 10))
        assertEquals("not while it may still draw", 0, released)
        later!!.run()
        assertEquals(1, released)
    }

    @Test
    fun `a render thread that finishes on another thread just in time`() {
        val released = java.util.concurrent.atomic.AtomicInteger()
        assertTrue(releaseAfter({ done -> Thread { Thread.sleep(5); done.run() }.start() }, { released.incrementAndGet() }, 2_000))
        assertEquals(0, released.get())
    }
}
