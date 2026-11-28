package dev.glassworks.hawebrtc

import android.graphics.Matrix
import livekit.org.webrtc.RendererCommon
import livekit.org.webrtc.VideoFrame
import livekit.org.webrtc.VideoFrameDrawer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.lang.management.ManagementFactory

/**
 * [TextureFrameDrawer] draws a texture frame exactly as libwebrtc's [VideoFrameDrawer] does, with nothing allocated per
 * frame. Real Skia matrices ([GraphicsMode.Mode.NATIVE]), as on a device; the allocations are counted by the JVM
 * (`ThreadMXBean.getThreadAllocatedBytes`) over many frames, the way EglRenderer calls it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TextureFrameDrawerTest {
    /** What a drawer was asked to draw: the last call's arguments, copied into arrays it keeps (nothing allocated). */
    private class Recording : RendererCommon.GlDrawer {
        var calls = 0
        var kind = ""
        var texture = 0
        val matrix = FloatArray(16)
        val sizes = IntArray(6)

        private fun record(kind: String, texture: Int, m: FloatArray, fw: Int, fh: Int, vx: Int, vy: Int, vw: Int, vh: Int) {
            calls++
            this.kind = kind
            this.texture = texture
            System.arraycopy(m, 0, matrix, 0, 16)
            sizes[0] = fw; sizes[1] = fh; sizes[2] = vx; sizes[3] = vy; sizes[4] = vw; sizes[5] = vh
        }

        override fun drawOes(id: Int, m: FloatArray, fw: Int, fh: Int, vx: Int, vy: Int, vw: Int, vh: Int) = record("oes", id, m, fw, fh, vx, vy, vw, vh)
        override fun drawRgb(id: Int, m: FloatArray, fw: Int, fh: Int, vx: Int, vy: Int, vw: Int, vh: Int) = record("rgb", id, m, fw, fh, vx, vy, vw, vh)
        override fun drawYuv(ids: IntArray, m: FloatArray, fw: Int, fh: Int, vx: Int, vy: Int, vw: Int, vh: Int) = record("yuv", ids[0], m, fw, fh, vx, vy, vw, vh)
        override fun release() {}
    }

    /** A decoder's texture: its SurfaceTexture's transform, as `SurfaceTextureHelper` hands it on. */
    private class Texture(
        private val w: Int,
        private val h: Int,
        private val kind: VideoFrame.TextureBuffer.Type,
        private val transform: Matrix,
    ) : VideoFrame.TextureBuffer {
        override fun getType() = kind
        override fun getTextureId() = 7
        override fun getTransformMatrix() = transform
        override fun getWidth() = w
        override fun getHeight() = h
        override fun toI420(): VideoFrame.I420Buffer? = null
        override fun retain() {}
        override fun release() {}
        override fun cropAndScale(cropX: Int, cropY: Int, cropWidth: Int, cropHeight: Int, scaleWidth: Int, scaleHeight: Int): VideoFrame.Buffer = this
        override fun applyTransformMatrix(transformMatrix: Matrix, newWidth: Int, newHeight: Int): VideoFrame.TextureBuffer = this
    }

    /** A SurfaceTexture's usual transform (flipped vertically), as `RendererCommon.convertMatrixToAndroidGraphicsMatrix` makes it. */
    private fun decoderTransform(): Matrix = RendererCommon.convertMatrixToAndroidGraphicsMatrix(
        floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f),
    )

    /** EglRenderer's drawMatrix for a frame of [frameAspect] in a layout of [layoutAspect], mirrored as asked. */
    private fun drawMatrix(frameAspect: Float, layoutAspect: Float, mirror: Boolean = false): Matrix {
        val sx = if (frameAspect > layoutAspect) layoutAspect / frameAspect else 1f
        val sy = if (frameAspect > layoutAspect) 1f else frameAspect / layoutAspect
        return Matrix().apply {
            preTranslate(0.5f, 0.5f)
            preScale(if (mirror) -1f else 1f, 1f)
            preScale(sx, sy)
            preTranslate(-0.5f, -0.5f)
        }
    }

    @Test
    fun `a texture frame is drawn exactly as libwebrtc draws it`() {
        val cases = listOf(
            Triple(1280 to 720, 0, drawMatrix(16f / 9f, 16f / 9f)),
            Triple(1280 to 720, 0, drawMatrix(16f / 9f, 1f)),
            Triple(640 to 360, 90, drawMatrix(9f / 16f, 4f / 3f)),
            Triple(640 to 360, 180, drawMatrix(16f / 9f, 2f, mirror = true)),
            Triple(1920 to 1080, 270, drawMatrix(9f / 16f, 9f / 16f)),
        )
        for ((size, rotation, extra) in cases) for (kind in listOf(VideoFrame.TextureBuffer.Type.OES, VideoFrame.TextureBuffer.Type.RGB)) {
            val frame = VideoFrame(Texture(size.first, size.second, kind, decoderTransform()), rotation, 0)
            val theirs = Recording()
            val ours = Recording()
            VideoFrameDrawer().drawFrame(frame, theirs, extra, 0, 0, 480, 270)
            TextureFrameDrawer().drawFrame(frame, ours, extra, 0, 0, 480, 270)
            val what = "${size.first}x${size.second} at $rotation, $kind"
            assertEquals(what, 1, ours.calls)
            assertEquals(what, theirs.kind, ours.kind)
            assertEquals(what, theirs.texture, ours.texture)
            assertArrayEquals(what, theirs.matrix, ours.matrix, 0f)
            assertArrayEquals(what, theirs.sizes, ours.sizes)
        }
        // No extra matrix: the frame's own size.
        val frame = VideoFrame(Texture(640, 360, VideoFrame.TextureBuffer.Type.OES, decoderTransform()), 90, 0)
        val theirs = Recording()
        val ours = Recording()
        VideoFrameDrawer().drawFrame(frame, theirs, null, 0, 0, 360, 640)
        TextureFrameDrawer().drawFrame(frame, ours, null, 0, 0, 360, 640)
        assertArrayEquals(theirs.matrix, ours.matrix, 0f)
        assertArrayEquals(theirs.sizes, ours.sizes)
    }

    @Test
    fun `a texture frame is drawn with nothing allocated, where libwebrtc allocates on every one`() {
        val frame = VideoFrame(Texture(1280, 720, VideoFrame.TextureBuffer.Type.OES, decoderTransform()), 0, 0)
        val extra = drawMatrix(16f / 9f, 1f)
        val theirs = allocatedPerFrame(VideoFrameDrawer(), frame, extra)
        val ours = allocatedPerFrame(TextureFrameDrawer(), frame, extra)
        println("bytes allocated per texture frame drawn: libwebrtc's VideoFrameDrawer $theirs, TextureFrameDrawer $ours")
        // libwebrtc's: a Matrix with its native peer and cleaner, and two float arrays, every frame.
        assertTrue("libwebrtc's drawer allocated $theirs bytes a frame", theirs >= 64)
        assertEquals("TextureFrameDrawer allocated $ours bytes a frame", 0L, ours)
    }

    /** Bytes the drawing thread allocates per [VideoFrameDrawer.drawFrame], over [FRAMES] frames after a warm-up. */
    private fun allocatedPerFrame(drawer: VideoFrameDrawer, frame: VideoFrame, extra: Matrix): Long {
        val gl = Recording()
        repeat(WARM_UP) { drawer.drawFrame(frame, gl, extra, 0, 0, 480, 270) }
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val id = Thread.currentThread().id
        // The counter itself allocates nothing per call; measured twice back to back to make sure.
        val base = threads.getThreadAllocatedBytes(id)
        val overhead = threads.getThreadAllocatedBytes(id) - base
        val before = threads.getThreadAllocatedBytes(id)
        for (i in 0 until FRAMES) drawer.drawFrame(frame, gl, extra, 0, 0, 480, 270)
        val after = threads.getThreadAllocatedBytes(id)
        return (after - before - overhead) / FRAMES
    }

    private companion object {
        const val WARM_UP = 2_000
        const val FRAMES = 20_000
    }
}
