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
    fun `a frame counts when the view shows it, not when it is decoded`() {
        val view = VideoTextureView(context, VideoScaling.FIT)
        val drawn = ArrayList<Pair<Int, Int>>()
        view.onDrawn = { w, h -> drawn += w to h }
        val texture = android.graphics.SurfaceTexture(0)
        // An update with no frame received yet (nothing of the camera's on it) isn't a frame.
        view.onSurfaceTextureUpdated(texture)
        assertEquals(emptyList<Pair<Int, Int>>(), drawn)
        // Frames are coming in, but none drawn yet (the renderer has no surface): nothing to report.
        view.frameSize(1280, 720)
        assertEquals(emptyList<Pair<Int, Int>>(), drawn)
        // The TextureView took a drawn frame: reported, with its size.
        view.onSurfaceTextureUpdated(texture)
        assertEquals(listOf(1280 to 720), drawn)
        view.onDrawn = null
        view.onSurfaceTextureUpdated(texture)
        assertEquals(1, drawn.size)
        texture.release()
        view.release()
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

/** A surface given back while the renderer runs, or while it shuts down (the player closing as the view goes). */
class SurfaceHandoffTest {
    @Test
    fun `while the renderer runs, the render thread's release says when`() {
        var queued: Runnable? = null
        val handoff = SurfaceHandoff { done -> queued = done }
        var released = 0
        handoff.whenReleased { released++ }
        assertEquals(0, released)
        queued!!.run()
        assertEquals(1, released)
        handoff.beginShutdown()
        handoff.endShutdown()
        assertEquals("once", 1, released)
    }

    @Test
    fun `during a shutdown, an answer at once from libwebrtc doesn't count, the shutdown's end does`() {
        // EglRenderer.release() has forgotten its thread, so releaseEglSurface would answer at once.
        val handoff = SurfaceHandoff { done -> done.run() }
        handoff.beginShutdown()
        var released = 0
        handoff.whenReleased { released++ }
        assertEquals("EGL may still be tearing down", 0, released)
        handoff.endShutdown()
        assertEquals(1, released)
    }

    @Test
    fun `a shutdown that overtakes a queued surface release ends it`() {
        var queued: Runnable? = null
        val handoff = SurfaceHandoff { done -> queued = done }
        var released = 0
        handoff.whenReleased { released++ }
        handoff.beginShutdown()
        handoff.endShutdown()
        assertEquals(1, released)
        queued!!.run()
        assertEquals("not twice", 1, released)
    }

    @Test
    fun `after the shutdown, at once`() {
        val handoff = SurfaceHandoff { throw AssertionError("no renderer to ask") }
        handoff.beginShutdown()
        handoff.endShutdown()
        var released = 0
        handoff.whenReleased { released++ }
        assertEquals(1, released)
    }

    @Test
    fun `the view's texture during a shutdown is released by the shutdown, never twice`() {
        val handoff = SurfaceHandoff { done -> done.run() }
        handoff.beginShutdown()
        var texture = 0
        assertFalse("the view mustn't release it", releaseAfter(handoff::whenReleased, { texture++ }, 10))
        assertEquals(0, texture)
        handoff.endShutdown()
        assertEquals(1, texture)
    }
}
