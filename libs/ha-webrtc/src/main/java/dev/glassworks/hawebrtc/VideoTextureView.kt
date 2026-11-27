package dev.glassworks.hawebrtc

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.os.Trace
import android.view.TextureView
import livekit.org.webrtc.EglBase
import livekit.org.webrtc.EglRenderer
import livekit.org.webrtc.GlRectDrawer
import livekit.org.webrtc.VideoFrame
import livekit.org.webrtc.VideoSink
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

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
 * behind it (a still) shows while the stream connects; once a frame covers the whole view it is opaque, so the
 * compositor doesn't blend it with what's behind on every frame.
 *
 * While [rendering] is false (the view is off screen) frames that come in aren't drawn, but the first one is: it's
 * what says the stream plays.
 */
@SuppressLint("ViewConstructor")
internal class VideoTextureView(
    context: Context,
    scaling: VideoScaling,
    /** Where a destroyed surface is given back and released, off the main thread ([TEXTURE_RELEASER]). */
    private val releaser: Executor = TEXTURE_RELEASER,
) : TextureView(context), TextureView.SurfaceTextureListener, VideoOutput {
    private val renderer = EglRenderer("ha-webrtc")
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var frameWidth = 0
    @Volatile private var frameHeight = 0

    override val view: android.view.View get() = this

    /** How the picture fills the view. Main thread. */
    override var scaling: VideoScaling = scaling
        set(value) {
            field = value
            updateLayout()
        }

    init {
        isOpaque = false
        surfaceTextureListener = this
    }

    /**
     * Starts the render thread on [egl]'s context (the decoders' textures). Any thread.
     *
     * RGBA, though the picture is opaque once drawn: [isOpaque] already tells the window so, and that is what spares
     * the blend, so `CONFIG_PLAIN` (VideoSurfaceView's) would buy nothing here. It asks EGL for no alpha, and libwebrtc
     * takes the first config EGL lists, which EGL's sort makes a 24-bit RGB 888 one wherever the driver has it: a
     * buffer format the app's window would then sample as a texture on every frame, which a SurfaceView never does,
     * and which hasn't been tried on these panels' GPUs. RGBA 8888 is what a TextureView's producers get by default.
     */
    override fun init(egl: EglBase.Context) {
        renderer.init(egl, EglBase.CONFIG_RGBA, GlRectDrawer())
        ready = true
        main.post { surfaceTexture?.takeIf { isAvailable }?.let { attach(it) } }
    }

    /** Stops the render thread. Any thread but the render thread; blocks until it has stopped. */
    override fun release() {
        paced?.let { pacer?.pacing?.unregister(it) }
        handoff.beginShutdown()
        try {
            renderer.release()
        } finally {
            handoff.endShutdown()
        }
    }

    private val handoff = SurfaceHandoff(renderer::releaseEglSurface)

    /**
     * Called on the main thread each time a new picture is on screen (the TextureView took a frame the renderer drew),
     * with the picture's size, rotation applied. A frame the renderer drops, with no surface to draw on, isn't one.
     */
    @Volatile
    override var onDrawn: FrameDrawn? = null

    /** False: frames aren't drawn (off screen), once one has been. Any thread reads it. */
    @Volatile
    override var rendering: Boolean = true

    /** A frame has been drawn on this surface. */
    @Volatile
    internal var drawnOnce = false
        private set

    /** The picture covers the whole view (no letterbox): opaque once drawn. Main thread. */
    private var coversView = true

    override fun onFrame(frame: VideoFrame) {
        frameSize(frame.rotatedWidth, frame.rotatedHeight)
        if (!draws()) return
        val p = pacer
        val client = paced
        // The first frame goes at once: it's what says the stream plays.
        if (p != null && client != null && p.pacing.offer(client, frame, System.nanoTime(), immediate = !drawnOnce)) {
            p.ensureTicking()
        } else {
            renderer.onFrame(frame)
        }
    }

    /**
     * Where its frames are drawn on a shared tick with every other paced view's ([HaWebRtcPlayer.Options.pacedMaxFps],
     * [FramePacing]), or null: each frame as it comes. Set once, by [pace], before frames come.
     */
    @Volatile private var pacer: VsyncPacer? = null

    /** This view on [pacer]: a tick draws its waiting frame, unless it has gone off screen meanwhile. */
    @Volatile private var paced: FramePacing<VideoFrame>.Client? = null

    /** Its frames go through [pacer] from now on. Once, before [init]. */
    fun pace(pacer: VsyncPacer) {
        check(this.pacer == null) { "paced already" }
        paced = pacer.pacing.register { f -> if (draws()) renderer.onFrame(f) }
        this.pacer = pacer
    }

    /** Whether a frame coming in now is drawn: while [rendering], and the first one in any case. */
    internal fun draws(): Boolean = rendering || !drawnOnce

    /** The size of the frames coming in (rotation applied); the layout follows it. */
    internal fun frameSize(w: Int, h: Int) {
        if (w != frameWidth || h != frameHeight) {
            frameWidth = w
            frameHeight = h
            main.post(::updateLayout)
        }
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

    override val surfaceReady: Boolean get() = isAvailable

    override var onSurface: ((ready: Boolean) -> Unit)? = null

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        attach(surface)
        onSurface?.invoke(true)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = updateLayout()

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        onSurface?.invoke(false)
        attached = false
        // A new surface starts empty: transparent again until its first frame.
        drawnOnce = false
        updateOpaque()
        // The texture is ours to release (false), once the render thread has let go of it. Asking it to let go can
        // block (EglRenderer's lock, held through a buffer swap), so even the asking happens off the main thread.
        releaser.execute { handoff.whenReleased { surface.release() } }
        return false
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        val w = frameWidth
        val h = frameHeight
        if (w > 0 && h > 0) {
            Trace.beginSection(TRACE_SHOWN)
            try {
                drawnOnce = true
                updateOpaque()
                onDrawn?.onDrawn(w, h)
            } finally {
                Trace.endSection()
            }
        }
    }

    /** Opaque once a frame covers the whole view; transparent before, or while the picture is letterboxed. */
    private fun updateOpaque() {
        val opaque = drawnOnce && coversView
        if (isOpaque != opaque) isOpaque = opaque
    }

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
        coversView = sx >= 1f && sy >= 1f
        updateOpaque()
    }

    private companion object {
        /** A trace section for each new picture the view shows (main thread, inside the window's draw). */
        const val TRACE_SHOWN = "ha-webrtc frame shown"
    }
}

/**
 * When nothing draws into a view's surface any more, whether the renderer is running (it lets go of that one surface)
 * or shutting down: the player closing can overlap the view's surface going. libwebrtc's `EglRenderer.release()` forgets
 * its render thread before that thread has torn EGL down, and from then on `releaseEglSurface` answers at once, while
 * EGL may still be using the surface. Here a surface given back during a shutdown waits for the shutdown to finish.
 */
internal class SurfaceHandoff(private val releaseEglSurface: (Runnable) -> Unit) {
    private val lock = Any()
    private var state = RUNNING
    private val afterShutdown = ArrayList<Runnable>()

    /** Calls [onReleased] once, when nothing draws into the surface any more. Any thread. */
    fun whenReleased(onReleased: Runnable) {
        val fired = AtomicBoolean(false)
        val once = Runnable { if (fired.compareAndSet(false, true)) onReleased.run() }
        val now = synchronized(lock) {
            when (state) {
                SHUT_DOWN -> true
                SHUTTING_DOWN -> {
                    afterShutdown += once
                    false
                }
                else -> {
                    // Also on the shutdown's list: a shutdown that starts before the render thread gets to this release
                    // tears EGL down instead, and the queued release may never run.
                    afterShutdown += once
                    releaseEglSurface(Runnable {
                        synchronized(lock) { afterShutdown.remove(once) }
                        once.run()
                    })
                    false
                }
            }
        }
        if (now) once.run()
    }

    /** The renderer is shutting down (before `EglRenderer.release()`). */
    fun beginShutdown() {
        synchronized(lock) { state = SHUTTING_DOWN }
    }

    /** The renderer has shut down and EGL is gone (after `EglRenderer.release()` returned). */
    fun endShutdown() {
        val waiting = synchronized(lock) {
            state = SHUT_DOWN
            ArrayList(afterShutdown).also { afterShutdown.clear() }
        }
        waiting.forEach { it.run() }
    }

    private companion object {
        const val RUNNING = 0
        const val SHUTTING_DOWN = 1
        const val SHUT_DOWN = 2
    }
}

/**
 * The thread that gives destroyed surfaces back: it waits for the render thread to let go of each, then releases the
 * texture. One for every view in the process; it lives as long as the process, idle but for those moments.
 */
internal val TEXTURE_RELEASER: Executor = Executors.newSingleThreadExecutor { r ->
    Thread(r, "ha-webrtc-texture").apply { isDaemon = true }
}

/**
 * The scale of a picture drawn stretched over a [viewWidth] by [viewHeight] view, so it shows as [scaling] asks: for
 * [VideoScaling.FIT] the axis with room to spare shrinks. [VideoScaling.FILL] crops in the renderer instead, so both
 * stay 1, as they do for [VideoScaling.STRETCH] and before the first frame. A view sized to the picture's shape in
 * whole pixels is a fraction of a pixel off it; a letterbox of [FIT_SNAP_PX] or less in all is drawn over instead,
 * so no sliver of what is behind the view shows along its edges.
 */
internal fun fitScale(scaling: VideoScaling, frameWidth: Int, frameHeight: Int, viewWidth: Int, viewHeight: Int): Pair<Float, Float> {
    if (scaling != VideoScaling.FIT || frameWidth <= 0 || frameHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) return 1f to 1f
    val frame = frameWidth.toFloat() / frameHeight
    val view = viewWidth.toFloat() / viewHeight
    return if (frame > view) {
        val sy = view / frame
        1f to (if (viewHeight * (1f - sy) <= FIT_SNAP_PX) 1f else sy)
    } else {
        val sx = frame / view
        (if (viewWidth * (1f - sx) <= FIT_SNAP_PX) 1f else sx) to 1f
    }
}

/** The widest letterbox, in pixels over both edges, that [fitScale] draws over. */
internal const val FIT_SNAP_PX = 2f
