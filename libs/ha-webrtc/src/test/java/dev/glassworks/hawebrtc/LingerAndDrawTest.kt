package dev.glassworks.hawebrtc

import android.graphics.SurfaceTexture
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.Executor

/** libwebrtc's shared parts outlive the last player a while, so the next one soon after finds them made. */
class LingeringTest {
    private var made = 0
    private val disposed = ArrayList<Int>()
    private val tasks = ArrayList<Pair<Long, () -> Unit>>()
    private val parts = Lingering(30_000, { ++made }, { disposed += it }, { delay, task -> tasks += delay to task })

    private fun runLater() {
        val due = tasks.toList()
        tasks.clear()
        due.forEach { it.second() }
    }

    @Test
    fun `the last release keeps the value a while, and an acquire meanwhile gets the same one`() {
        val a = parts.acquire()
        parts.release()
        assertEquals(listOf(30_000L), tasks.map { it.first })
        assertTrue(parts.alive)
        assertSame(a, parts.acquire())
        assertEquals(1, made)
        // The disposal scheduled before that acquire does nothing.
        runLater()
        assertTrue(parts.alive)
        assertEquals(emptyList<Int>(), disposed)
        parts.release()
        runLater()
        assertFalse(parts.alive)
        assertEquals(listOf(1), disposed)
        // Asked for again after it went: made anew.
        assertEquals(2, parts.acquire())
    }

    @Test
    fun `only the last release schedules a disposal, and an extra release does nothing`() {
        parts.acquire()
        parts.acquire()
        parts.release()
        assertTrue(tasks.isEmpty())
        parts.release()
        parts.release()
        assertEquals(1, tasks.size)
        runLater()
        assertEquals(listOf(1), disposed)
    }

    @Test
    fun `a make that throws keeps nothing and counts nothing`() {
        var fail = true
        val p = Lingering(1, { if (fail) throw IllegalStateException("no libwebrtc") else "ok" }, {}, { _, _ -> })
        runCatching { p.acquire() }
        assertFalse(p.alive)
        p.release()
        fail = false
        assertEquals("ok", p.acquire())
    }

    @Test
    fun `alive doesn't wait for a make in progress on another thread`() {
        val making = java.util.concurrent.CountDownLatch(1)
        val finish = java.util.concurrent.CountDownLatch(1)
        val p = Lingering(1, { making.countDown(); finish.await(); "parts" }, {}, { _, _ -> })
        val warm = Thread { p.acquire() }.apply { isDaemon = true; start() }
        try {
            assertTrue(making.await(5, java.util.concurrent.TimeUnit.SECONDS))
            // The main thread's check (a second camera's prewarm) while the first one's make holds the lock.
            val asked = java.util.concurrent.CompletableFuture.supplyAsync { p.alive }
            assertFalse(asked.get(2, java.util.concurrent.TimeUnit.SECONDS))
        } finally {
            finish.countDown()
        }
        warm.join(5_000)
        assertTrue(p.alive)
    }
}

/** The picture's view: opaque once a frame covers it, and not drawing off screen once it has drawn. */
@RunWith(RobolectricTestRunner::class)
class VideoTextureViewDrawTest {
    private val context = RuntimeEnvironment.getApplication()
    private val noRelease = Executor { }

    @Test
    fun `transparent until a frame is drawn, opaque once one covers the view, transparent again on a new surface`() {
        val view = VideoTextureView(context, VideoScaling.STRETCH, noRelease)
        val texture = SurfaceTexture(0)
        view.layout(0, 0, 400, 225)
        assertFalse(view.isOpaque)
        // An update with no frame yet isn't a picture.
        view.onSurfaceTextureUpdated(texture)
        assertFalse(view.isOpaque)
        view.frameSize(1280, 720)
        shadowOf(Looper.getMainLooper()).idle()
        view.onSurfaceTextureUpdated(texture)
        assertTrue(view.isOpaque)
        view.onSurfaceTextureDestroyed(texture)
        assertFalse(view.isOpaque)
    }

    @Test
    fun `a letterboxed picture stays transparent around it`() {
        val view = VideoTextureView(context, VideoScaling.FIT, noRelease)
        val texture = SurfaceTexture(0)
        view.layout(0, 0, 400, 400)
        view.frameSize(1280, 720)
        shadowOf(Looper.getMainLooper()).idle()
        view.onSurfaceTextureUpdated(texture)
        assertFalse(view.isOpaque)
        // The view takes the picture's shape: nothing around it, opaque.
        view.layout(0, 0, 400, 225)
        assertTrue(view.isOpaque)
    }

    @Test
    fun `off screen, frames aren't drawn once one has been`() {
        val view = VideoTextureView(context, VideoScaling.STRETCH, noRelease)
        val texture = SurfaceTexture(0)
        view.rendering = false
        // The first frame is drawn anyway: it's what says the stream plays.
        assertTrue(view.draws())
        view.frameSize(1280, 720)
        view.onSurfaceTextureUpdated(texture)
        assertFalse(view.draws())
        view.rendering = true
        assertTrue(view.draws())
    }

    @Test
    fun `the player passes rendering on to its view`() {
        val player = HaWebRtcPlayer(context, FakeChannel(), "camera.front_door")
        val view = player.view as VideoTextureView
        player.setRendering(false)
        assertFalse(view.rendering)
        player.setRendering(true)
        assertTrue(view.rendering)
        player.close()
        player.setRendering(false)
        assertTrue("nothing after close", view.rendering)
    }
}
