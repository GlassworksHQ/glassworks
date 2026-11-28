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
    fun `a SurfaceView when asked, clear until its first frame, and AUTO picks it from API 24`() {
        val player = HaWebRtcPlayer(context, FakeChannel(), "camera.front_door", HaWebRtcPlayer.Options(view = VideoViewKind.SURFACE_VIEW))
        val view = player.view
        assertTrue(view is android.view.SurfaceView)
        // Alpha 0 draws nothing, the hole in the window included: what's behind it shows until a frame is there.
        assertEquals(0f, view.alpha)
        player.close()
        assertEquals(VideoViewKind.SURFACE_VIEW, VideoViewKind.AUTO.resolved(CompatApi(24)))
        assertEquals(VideoViewKind.TEXTURE_VIEW, VideoViewKind.AUTO.resolved(CompatApi(23)))
        assertEquals(VideoViewKind.TEXTURE_VIEW, VideoViewKind.TEXTURE_VIEW.resolved(CompatApi(34)))
        assertEquals("the library's default stays a TextureView", VideoViewKind.TEXTURE_VIEW, HaWebRtcPlayer.Options().view)
    }

    @Test
    fun `a SurfaceView opens its hole with its first frame, and a frame from a surface that went never opens the next`() {
        val view = VideoSurfaceView(context, VideoScaling.STRETCH) { it.run() }
        val surfaces = ArrayList<Boolean>()
        view.onSurface = { surfaces += it }
        view.surfaceCreated(view.holder)
        assertTrue(view.surfaceReady)
        // The renderer isn't drawing on it yet (no render thread here): a render then doesn't count.
        view.frameRendered()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0f, view.alpha)
        assertFalse(view.drawnOnce)
        // Drawing: the first frame opens the hole.
        val drawing = VideoSurfaceView::class.java.getDeclaredField("drawing").apply { isAccessible = true }
        drawing.setBoolean(view, true)
        view.frameRendered()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1f, view.alpha)
        // A frame finished on the old surface, reported after it went and a new one came: the new one stays clear.
        view.frameRendered()
        view.surfaceDestroyed(view.holder)
        view.surfaceCreated(view.holder)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0f, view.alpha)
        assertFalse(view.drawnOnce)
        assertEquals(listOf(true, false, true), surfaces)
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
        view.onDrawn = FrameDrawn { w, h -> drawn += w to h }
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
    fun `a frame report queued before a pause or resume isn't delivered, one after it is`() {
        val player = HaWebRtcPlayer(context, FakeChannel(), "camera.front_door")
        val got = ArrayList<String>()
        player.listener = object : HaWebRtcPlayer.Listener {
            override fun onFirstFrame(sinceStartMs: Long, width: Int, height: Int) { got += "first $sinceStartMs ${width}x$height" }
            override fun onFrameRendered() { got += "frame" }
            override fun onError(error: HaError) {}
        }
        val events = player.Events()
        // Posted by the player's thread in the run before; the main thread pauses and resumes before it runs.
        events.onFirstFrame(500, 640, 480)
        player.setActive(false)
        player.setActive(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(emptyList<String>(), got)
        assertEquals("the stale size isn't taken either", 0, player.videoWidth)
        // The resumed run's own first frame.
        events.onFirstFrame(120, 1280, 720)
        events.onFrame(1280, 720)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("first 120 1280x720", "frame"), got)
        assertEquals(1280 to 720, player.videoWidth to player.videoHeight)
        player.close()
    }

    @Test
    fun `a steady frame is reported from the main thread at the size it had, once the run's first frame was delivered`() {
        val player = HaWebRtcPlayer(context, FakeChannel(), "camera.front_door")
        val got = ArrayList<String>()
        player.listener = object : HaWebRtcPlayer.Listener {
            override fun onFirstFrame(sinceStartMs: Long, width: Int, height: Int) { got += "first" }
            override fun onFrameRendered() { got += "frame" }
            override fun onError(error: HaError) {}
        }
        val events = player.Events()
        assertFalse("nothing delivered yet: the usual way", player.steady.onSteadyFrame(1280, 720))
        events.onFirstFrame(120, 1280, 720)
        assertFalse("the first frame's report hasn't run yet", player.steady.onSteadyFrame(1280, 720))
        shadowOf(Looper.getMainLooper()).idle()
        // Delivered: frames at that size are taken, and reported once each, in one post.
        assertTrue(player.steady.onSteadyFrame(1280, 720))
        assertTrue(player.steady.onSteadyFrame(1280, 720))
        assertFalse("a new size goes the usual way, which updates videoWidth", player.steady.onSteadyFrame(640, 480))
        assertEquals(listOf("first"), got)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("first", "frame", "frame"), got)
        // Taken just before a pause: dropped with it, as the usual way drops a stale report.
        assertTrue(player.steady.onSteadyFrame(1280, 720))
        player.setActive(false)
        assertFalse(player.steady.onSteadyFrame(1280, 720))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("first", "frame", "frame"), got)
        player.close()
        assertFalse(player.steady.onSteadyFrame(1280, 720))
    }

    @Test
    fun `a listener that wants no frames gets no steady reports, but still the first frame and a new size`() {
        val player = HaWebRtcPlayer(context, FakeChannel(), "camera.front_door")
        val got = ArrayList<String>()
        var wants = false
        player.listener = object : HaWebRtcPlayer.Listener, HaWebRtcPlayer.FrameDemand {
            override val wantsFrames: Boolean get() = wants
            override fun onFirstFrame(sinceStartMs: Long, width: Int, height: Int) { got += "first ${width}x$height" }
            override fun onFrameRendered() { got += "frame ${player.videoWidth}x${player.videoHeight}" }
            override fun onError(error: HaError) {}
        }
        val events = player.Events()
        events.onFirstFrame(120, 1280, 720)
        shadowOf(Looper.getMainLooper()).idle()
        // Steady frames at the delivered size: taken (drawn), with nothing posted.
        assertTrue(player.steady.onSteadyFrame(1280, 720))
        assertTrue(player.steady.onSteadyFrame(1280, 720))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("first 1280x720"), got)
        // A new size still goes the usual way, and is reported.
        assertFalse(player.steady.onSteadyFrame(640, 480))
        events.onFrame(640, 480)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("first 1280x720", "frame 640x480"), got)
        // Wanted again: steady frames are reported.
        wants = true
        assertTrue(player.steady.onSteadyFrame(640, 480))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("first 1280x720", "frame 640x480", "frame 640x480"), got)
        player.close()
    }

    @Test
    fun `a listener without FrameDemand hears of every steady frame`() {
        val player = HaWebRtcPlayer(context, FakeChannel(), "camera.front_door")
        var frames = 0
        player.listener = object : HaWebRtcPlayer.Listener {
            override fun onFirstFrame(sinceStartMs: Long, width: Int, height: Int) {}
            override fun onFrameRendered() { frames++ }
            override fun onError(error: HaError) {}
        }
        player.Events().onFirstFrame(120, 1280, 720)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(player.steady.onSteadyFrame(1280, 720))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, frames)
        player.close()
    }

    @Test
    fun `a destroyed surface is released off the main thread, after the renderer let go of it`() {
        val jobs = ArrayList<Runnable>()
        val view = VideoTextureView(context, VideoScaling.FIT) { jobs += it }
        var released = 0
        val texture = object : android.graphics.SurfaceTexture(0) {
            override fun release() {
                released++
                super.release()
            }
        }
        // The view keeps its hands off (false) and asks nothing of the renderer on the main thread.
        assertFalse(view.onSurfaceTextureDestroyed(texture))
        assertEquals(0, released)
        // On the releasing thread: the renderer (not started here) has nothing to let go of, then the texture goes.
        jobs.single().run()
        assertEquals(1, released)
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
    fun `fit draws over a letterbox of a pixel or two, as the browser shows none`() {
        // 641 x 360 is 16:9 rounded to whole pixels: the picture would be 640 px wide, a sliver short.
        assertEquals(1f to 1f, fitScale(VideoScaling.FIT, 1280, 720, 641, 360))
        assertEquals(1f to 1f, fitScale(VideoScaling.FIT, 1280, 720, 640, 361))
        // Five pixels short is a letterbox.
        val (sx, sy) = fitScale(VideoScaling.FIT, 1280, 720, 645, 360)
        assertEquals(1f, sy)
        assertTrue(sx < 1f)
    }

    @Test
    fun `fill and stretch, and no frame yet, draw over the whole view`() {
        assertEquals(1f to 1f, fitScale(VideoScaling.FILL, 1920, 1080, 400, 400))
        assertEquals(1f to 1f, fitScale(VideoScaling.STRETCH, 1920, 1080, 400, 400))
        assertEquals(1f to 1f, fitScale(VideoScaling.FIT, 0, 0, 400, 400))
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
}
