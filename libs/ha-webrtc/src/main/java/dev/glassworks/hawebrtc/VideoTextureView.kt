package dev.glassworks.hawebrtc

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import livekit.org.webrtc.EglBase
import livekit.org.webrtc.EglRenderer
import livekit.org.webrtc.GlRectDrawer
import livekit.org.webrtc.VideoFrame
import livekit.org.webrtc.VideoSink
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** How a picture fills its view, as a `<video>`'s `object-fit`. */
public enum class VideoScaling {
    /** All of the picture, letterboxed (`contain`, the browser's default for video). */
    FIT,

    /** The view filled, the picture cropped to it (`cover`). */
    FILL,

    /** The picture stretched to the view (`fill`). */
    STRETCH,
}

/**
 * Draws libwebrtc frames into a [TextureView] with libwebrtc's own [EglRenderer], so the picture is part of the view
 * tree: it scrolls, clips to rounded corners and fades with its card (a SurfaceView, libwebrtc's own renderer, is a
 * window of its own and does none of that on Android 6). It is transparent until the first frame, so whatever is
 * behind it (a still) shows while the stream connects.
 */
@SuppressLint("ViewConstructor")
internal class VideoTextureView(context: Context, scaling: VideoScaling) : TextureView(context), TextureView.SurfaceTextureListener, VideoSink {
    private val renderer = EglRenderer("ha-webrtc")
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var frameWidth = 0
    @Volatile private var frameHeight = 0

    /** How the picture fills the view. Main thread. */
    var scaling: VideoScaling = scaling
        set(value) {
            field = value
            updateLayout()
        }

    init {
        isOpaque = false
        surfaceTextureListener = this
    }

    /** Starts the render thread on [egl]'s context (the decoders' textures). Any thread. */
    fun init(egl: EglBase.Context) {
        renderer.init(egl, EglBase.CONFIG_RGBA, GlRectDrawer())
        ready = true
        main.post { surfaceTexture?.takeIf { isAvailable }?.let { attach(it) } }
    }

    /** Stops the render thread. Any thread but the render thread; blocks until it has stopped. */
    fun release() {
        renderer.release()
    }

    override fun onFrame(frame: VideoFrame) {
        val w = frame.rotatedWidth
        val h = frame.rotatedHeight
        if (w != frameWidth || h != frameHeight) {
            frameWidth = w
            frameHeight = h
            main.post(::updateLayout)
        }
        renderer.onFrame(frame)
    }

    /** The render thread runs ([init] was called): only then can it take a surface. */
    @Volatile private var ready = false
    private var attached = false

    private fun attach(texture: SurfaceTexture) {
        if (attached || !ready) return
        attached = true
        renderer.createEglSurface(texture)
        updateLayout()
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) = attach(surface)

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = updateLayout()

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        attached = false
        // The render thread lets go of the texture before anyone releases it: the view, if that is done in time,
        // else the render thread itself once it has.
        return releaseAfter(renderer::releaseEglSurface, surface::release, RELEASE_WAIT_MS)
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed) updateLayout()
    }

    private fun updateLayout() {
        val vw = width
        val vh = height
        if (vw <= 0 || vh <= 0) return
        renderer.setLayoutAspectRatio(if (scaling == VideoScaling.FILL) vw.toFloat() / vh else 0f)
        val (sx, sy) = fitScale(scaling, frameWidth, frameHeight, vw, vh)
        setTransform(Matrix().apply { setScale(sx, sy, vw / 2f, vh / 2f) })
    }

    private companion object {
        const val RELEASE_WAIT_MS = 1_000L
    }
}

/**
 * Asks the render thread to let go of a surface ([releaseSurface], which calls its argument once it has) and decides
 * who releases the surface itself, as `onSurfaceTextureDestroyed` must: true when the render thread finished within
 * [waitMs], so the view releases it; false otherwise, and then [releaseTexture] runs on the render thread as soon as
 * it has finished, never before. The main thread waits at most [waitMs].
 */
internal fun releaseAfter(releaseSurface: (Runnable) -> Unit, releaseTexture: () -> Unit, waitMs: Long): Boolean {
    val pending = 0
    val done = 1
    val ours = 2
    val state = AtomicInteger(pending)
    val finished = CountDownLatch(1)
    releaseSurface(Runnable {
        // Past the wait, the view has handed the texture to us: release it here, now that nothing draws into it.
        if (!state.compareAndSet(pending, done)) releaseTexture()
        finished.countDown()
    })
    if (finished.await(waitMs, TimeUnit.MILLISECONDS)) return true
    // Still drawing: the render thread releases it when it's done, unless it finished just now.
    return !state.compareAndSet(pending, ours)
}

/**
 * The scale of a picture drawn stretched over a [viewWidth] by [viewHeight] view, so it shows as [scaling] asks: for
 * [VideoScaling.FIT] the axis with room to spare shrinks. [VideoScaling.FILL] crops in the renderer instead, so both
 * stay 1, as they do for [VideoScaling.STRETCH] and before the first frame.
 */
internal fun fitScale(scaling: VideoScaling, frameWidth: Int, frameHeight: Int, viewWidth: Int, viewHeight: Int): Pair<Float, Float> {
    if (scaling != VideoScaling.FIT || frameWidth <= 0 || frameHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) return 1f to 1f
    val frame = frameWidth.toFloat() / frameHeight
    val view = viewWidth.toFloat() / viewHeight
    return if (frame > view) 1f to view / frame else frame / view to 1f
}
