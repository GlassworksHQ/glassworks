package dev.glassworks.hawebrtc

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import livekit.org.webrtc.EglBase
import livekit.org.webrtc.EglRenderer
import livekit.org.webrtc.GlRectDrawer
import livekit.org.webrtc.VideoFrame
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Draws libwebrtc frames into a [SurfaceView] with libwebrtc's own [EglRenderer] ([VideoViewKind.SURFACE_VIEW]): the
 * system composites the picture under the window, so a new frame doesn't make the app's window draw again (a
 * [VideoTextureView]'s does: with four cameras, the whole dashboard is drawn again some 30 to 60 times a second).
 *
 * The picture shows through the hole the view leaves in the window, so the window's own clip shapes it (a card's
 * rounded corners) and what the window draws over it covers it. The view stays at alpha 0, which leaves no hole, until
 * its surface has a frame: whatever is behind it (a still) shows meanwhile, as through a clear [VideoTextureView]. A
 * new surface (the view hidden and shown again) is clear again until its first frame.
 *
 * Off screen ([rendering] false) frames that come in aren't drawn, but the first one is: it's what says the stream
 * plays.
 */
@SuppressLint("ViewConstructor")
internal class VideoSurfaceView(context: Context, scaling: VideoScaling) : SurfaceView(context), SurfaceHolder.Callback, VideoOutput {
    private val renderer = EglRenderer("ha-webrtc")
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var frameWidth = 0
    @Volatile private var frameHeight = 0

    override val view: android.view.View get() = this

    override var scaling: VideoScaling = scaling
        set(value) {
            field = value
            updateLayout()
        }

    @Volatile override var onDrawn: ((width: Int, height: Int) -> Unit)? = null

    @Volatile override var rendering: Boolean = true

    /** A frame has been drawn on this surface (render thread writes, any thread reads). */
    @Volatile internal var drawnOnce = false
        private set

    /** The render thread runs ([init] was called): only then can it take a surface. */
    @Volatile private var ready = false

    /** The holder's surface exists (main thread). */
    private var surfaceAlive = false
    private var attached = false

    private val handoff = SurfaceHandoff(renderer::releaseEglSurface)

    /** One main-thread report queued at a time: a busy main thread hears of the newest frame, as a TextureView does. */
    private val drawnQueued = AtomicBoolean(false)
    private val reportDrawn = Runnable {
        drawnQueued.set(false)
        if (!surfaceAlive || !drawnOnce) return@Runnable
        // The surface has a picture: the hole can open.
        if (alpha != 1f) alpha = 1f
        val w = frameWidth
        val h = frameHeight
        if (w > 0 && h > 0) onDrawn?.invoke(w, h)
    }

    /** After each frame the renderer swapped onto the surface (render thread). */
    private val rendered = EglRenderer.RenderListener {
        drawnOnce = true
        if (drawnQueued.compareAndSet(false, true)) main.post(reportDrawn)
    }

    init {
        // No hole until a frame is there: alpha 0 draws nothing, the punch included.
        alpha = 0f
        holder.addCallback(this)
    }

    override fun init(egl: EglBase.Context) {
        renderer.init(egl, EglBase.CONFIG_PLAIN, GlRectDrawer())
        renderer.addRenderListener(rendered)
        ready = true
        main.post(::attach)
    }

    override fun release() {
        handoff.beginShutdown()
        try {
            renderer.release()
        } finally {
            handoff.endShutdown()
        }
    }

    override fun onFrame(frame: VideoFrame) {
        val w = frame.rotatedWidth
        val h = frame.rotatedHeight
        if (w != frameWidth || h != frameHeight) {
            frameWidth = w
            frameHeight = h
            main.post(::updateLayout)
        }
        if (rendering || !drawnOnce) renderer.onFrame(frame)
    }

    private fun attach() {
        if (attached || !ready || !surfaceAlive) return
        val surface = holder.surface ?: return
        if (!surface.isValid) return
        attached = true
        renderer.createEglSurface(surface)
        updateLayout()
    }

    override val surfaceReady: Boolean get() = surfaceAlive

    override var onSurfaceReady: (() -> Unit)? = null

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceAlive = true
        attach()
        onSurfaceReady?.invoke()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = updateLayout()

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceAlive = false
        attached = false
        drawnOnce = false
        alpha = 0f
        // The surface goes when this returns: the render thread lets go of it first (libwebrtc's SurfaceViewRenderer
        // waits the same way), bounded so a stuck render thread can't hang the main thread for good.
        val released = CountDownLatch(1)
        handoff.whenReleased { released.countDown() }
        released.await(RELEASE_WAIT_MS, TimeUnit.MILLISECONDS)
    }

    private fun updateLayout() {
        val vw = width
        val vh = height
        if (vw <= 0 || vh <= 0) return
        // FILL crops in the renderer; STRETCH (and FIT, which the caller sizes the view for) draws the whole frame.
        renderer.setLayoutAspectRatio(if (scaling == VideoScaling.FILL) vw.toFloat() / vh else 0f)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed) updateLayout()
    }

    private companion object {
        /** How long [surfaceDestroyed] waits for the render thread to let go of the surface. */
        const val RELEASE_WAIT_MS = 1_000L
    }
}
