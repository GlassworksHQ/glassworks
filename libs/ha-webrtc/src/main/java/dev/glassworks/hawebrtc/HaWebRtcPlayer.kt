package dev.glassworks.hawebrtc

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.View
import androidx.annotation.MainThread

/**
 * Plays a Home Assistant camera over WebRTC in a [view], as HA's frontend does: [HaWebRtcStream] on libwebrtc, with
 * hardware H.264 decoding where the device has it, drawn into a TextureView.
 *
 * Main thread only, and every [Listener] call comes on the main thread. The view is transparent until the first
 * frame. Each player runs its connection on a thread of its own; libwebrtc's factory and EGL context are shared by
 * every player in the process and freed 30 s after the last one closes, so a player opened again
 * soon (a view rebuilt, a dialog opened) doesn't make them again. [prewarm] makes them ahead of a player.
 *
 * - [start] connects and plays (once).
 * - [setActive] false stops drawing, and closes the connection after [HaWebRtcStream.Options.idleTeardownMs];
 *   true resumes.
 * - [setRendering] false keeps playing but stops drawing (the view is off screen); true draws again at once.
 * - [close] frees everything: the connection, HA's session, the decoder, the render thread and the player's thread.
 *   No listener call comes after it.
 */
public class HaWebRtcPlayer @JvmOverloads public constructor(
    context: Context,
    private val channel: HaCommandChannel,
    /** The camera entity (`camera.front_door`). */
    public val entityId: String,
    private val options: Options = Options(),
) {
    /** How the player behaves. */
    public class Options @JvmOverloads public constructor(
        /** The connection's behaviour: audio, timeout, reconnecting, pausing. */
        public val stream: HaWebRtcStream.Options = HaWebRtcStream.Options(),
        /** How the picture fills [view]. */
        public val scaling: VideoScaling = VideoScaling.FIT,
        /** The view the picture draws into: a TextureView unless asked ([VideoViewKind]). */
        public val view: VideoViewKind = VideoViewKind.TEXTURE_VIEW,
    )

    /** What the player reports, on the main thread. */
    public interface Listener {
        /** The first frame since [start] or the last resume is on screen, [sinceStartMs] after it. */
        public fun onFirstFrame(sinceStartMs: Long, width: Int, height: Int)

        /** Each frame drawn after the first. */
        public fun onFrameRendered() {}

        /** The connection failed and the player is reconnecting (connection [attempt] of a row). */
        public fun onReconnecting(attempt: Int, error: HaError) {}

        /** The camera can't play, or stopped and couldn't come back ([HaWebRtcStream]'s codes). Last call. */
        public fun onError(error: HaError)
    }

    /**
     * Optional for a [Listener]: whether it wants [Listener.onFrameRendered] for each frame now. While [wantsFrames] is
     * false, the player skips the report of a frame that changes nothing (same run, same size), and the main-thread
     * task that would bring it; the first frame, a new size and errors are still reported. A listener that doesn't
     * implement it hears of every frame. Its own interface, not a member of [Listener], so a listener compiled before
     * it existed keeps working. Read on the main thread.
     */
    public interface FrameDemand {
        /** True while the listener wants a report of every rendered frame; false lets the player skip unchanged ones. */
        public val wantsFrames: Boolean
    }

    private val appContext: Context = context.applicationContext ?: context
    private val main = Handler(Looper.getMainLooper())
    private val video: VideoOutput = when (options.view.resolved()) {
        VideoViewKind.SURFACE_VIEW -> VideoSurfaceView(context, options.scaling)
        else -> VideoTextureView(context, options.scaling)
    }
    private var thread: HandlerThread? = null
    private var worker: Handler? = null
    private var started = false
    private var closed = false
    private var active = true

    // The player's thread only.
    private var runtime: WebRtcRuntime.Handle? = null
    private var stream: HaWebRtcStream? = null

    /** Where the report goes. Main thread. */
    public var listener: Listener? = null

    /**
     * Called on the main thread when the view gets a surface to draw on (true) or loses it (false): a frame can be on
     * screen only while it has one. A property rather than a [Listener] member, so listeners compiled against an
     * earlier release keep working. Main thread.
     */
    public var onSurface: ((ready: Boolean) -> Unit)? = null

    init {
        video.onSurface = { ready -> if (!closed) onSurface?.invoke(ready) }
    }

    /**
     * The picture's width in pixels as shown (rotation applied), 0 until the first frame. Main thread; it is up to date
     * when [Listener.onFirstFrame] and [Listener.onFrameRendered] are called.
     */
    public var videoWidth: Int = 0
        private set

    /** The picture's height in pixels, as [videoWidth]. */
    public var videoHeight: Int = 0
        private set

    /**
     * The view has a surface to draw on: a frame can be on screen from now on. The view gets one once it is laid
     * out in a window; [onSurface] says when. Main thread.
     */
    public val surfaceReady: Boolean get() = video.surfaceReady

    /** The picture. Place it before [start]; it stays usable until [close]. */
    public val view: View get() = video.view

    /** How the picture fills [view]. Main thread. */
    public var scaling: VideoScaling
        get() = video.scaling
        set(value) {
            video.scaling = value
        }

    /** Connects and plays. Once: a second call, or one after [close], does nothing. */
    @MainThread
    public fun start() {
        if (started || closed) return
        started = true
        val t = HandlerThread("ha-webrtc").apply { start() }
        val w = Handler(t.looper)
        thread = t
        worker = w
        val startActive = active
        w.post {
            WebRtcRuntime.rendererUnavailable?.let { why ->
                return@post report { it.onError(HaError(HaWebRtcStream.START_FAILED, "renderer: $why")) }
            }
            val rt = try {
                WebRtcRuntime.acquire(appContext)
            } catch (e: RuntimeException) {
                return@post report { it.onError(HaError(HaWebRtcStream.START_FAILED, "libwebrtc: ${e.message}")) }
            } catch (e: LinkageError) {
                return@post report { it.onError(HaError(HaWebRtcStream.START_FAILED, "libwebrtc: ${e.message}")) }
            }
            // The render thread's EGL context can fail too (no EGL config, a lost display): give the runtime back and
            // report it, as for the runtime itself, so the caller closes the player. The check, the start and the
            // record of a failure are one step across every player, so after one failure no other player tries.
            val rendererFailed = synchronized(WebRtcRuntime.rendererLock) {
                WebRtcRuntime.rendererUnavailable ?: try {
                    video.init(rt.egl)
                    null
                } catch (e: RuntimeException) {
                    (e.message ?: e.javaClass.simpleName).also { WebRtcRuntime.rendererUnavailable = it }
                }
            }
            if (rendererFailed != null) {
                WebRtcRuntime.release()
                return@post report { it.onError(HaError(HaWebRtcStream.START_FAILED, "renderer: $rendererFailed")) }
            }
            runtime = rt
            val s = HaWebRtcStream(channel, entityId, { config, audio, events ->
                LibWebRtcPeer(rt.factory, config, audio, video, events)
            }, HandlerScheduler(w), options.stream)
            s.listener = Events()
            s.steadyFrames = steady
            stream = s
            s.setActive(startActive)
            s.start()
        }
    }

    /** False pauses (nothing drawn; the connection closes after a while), true resumes. Main thread. */
    @MainThread
    public fun setActive(active: Boolean) {
        if (closed || this.active == active) return
        this.active = active
        // A frame report the player's thread posts before it applies this belongs to the run that just ended: the
        // new run reaches that thread with the change itself.
        val run = ++viewRun
        val w = worker
        if (w == null) workerRun = run else w.post {
            workerRun = run
            stream?.setActive(active)
        }
    }

    /**
     * False: the stream plays on (its connection and decoder stay, so it shows again at once) but its frames aren't
     * drawn, as for a view off screen; the first frame is drawn anyway, as it's [Listener.onFirstFrame]. True draws
     * again. Main thread.
     */
    @MainThread
    public fun setRendering(rendering: Boolean) {
        if (closed) return
        video.rendering = rendering
    }

    /**
     * The [HaCommandChannel]'s connection to Home Assistant is back after a drop: a player waiting to reconnect tries
     * now ([HaWebRtcStream.connectionRestored]). Main thread.
     */
    @MainThread
    public fun connectionRestored() {
        if (closed) return
        worker?.post { stream?.connectionRestored() }
    }

    /** Ends playback and frees everything. Idempotent. Main thread. */
    @MainThread
    public fun close() {
        if (closed) return
        closed = true
        listener = null
        val w = worker ?: return
        val t = thread
        w.post {
            // Every step runs, whatever an earlier one throws (a channel's unsubscribe, say).
            try {
                stream?.close()
            } finally {
                stream = null
                try {
                    video.release()
                } finally {
                    if (runtime != null) WebRtcRuntime.release()
                    runtime = null
                    t?.quitSafely()
                }
            }
        }
    }

    /** What every player shares: libwebrtc's parts, made ahead of one with [prewarm]. */
    public companion object {
        /**
         * Makes libwebrtc's shared parts (its factory and EGL context) off the main thread, ahead of a player that is
         * about to start: they stay 30 s for one to take them. Any thread; a failure is left
         * for the player to report.
         */
        @JvmStatic
        public fun prewarm(context: Context) {
            val app = context.applicationContext ?: context
            WebRtcRuntime.prewarm(app)
        }
    }

    private fun report(call: (Listener) -> Unit) {
        main.post { if (!closed) listener?.let(call) }
    }

    /** Like [report], with the picture's size updated first. */
    private fun frame(width: Int, height: Int, call: (Listener) -> Unit) {
        val run = workerRun
        main.post {
            // A report from before a pause or resume (still queued here when it happened) is dropped, size and all.
            if (closed || run != viewRun) return@post
            videoWidth = width
            videoHeight = height
            deliveredRun = run
            listener?.let(call)
        }
    }

    /** The [viewRun] of the last frame report delivered, the size it set ([videoWidth]) included. Main thread. */
    private var deliveredRun = -1

    /** Frames [steady] took since [steadyReport] last ran. Main thread. */
    private var steadyPending = 0

    /**
     * Each steady frame's [Listener.onFrameRendered]: one Runnable for the player's life, posted once for the frames
     * that came before it ran. Posted, not called, as a TextureView reports its frame from inside its own draw.
     */
    private val steadyReport = Runnable {
        val n = steadyPending
        steadyPending = 0
        if (closed || deliveredRun != viewRun) return@Runnable
        repeat(n) { listener?.onFrameRendered() }
    }

    /**
     * A frame of a run that plays on (the stream says so), drawn on the main thread: when the main thread already had
     * this run's report at this size, it's reported from here, with nothing allocated and no hop to the player's
     * thread and back. Anything else (a new size, a pause or resume since) goes the usual way, through [frame].
     */
    internal val steady = HaWebRtcStream.SteadyFrames { width, height ->
        if (closed || Looper.myLooper() !== main.looper || deliveredRun != viewRun || width != videoWidth || height != videoHeight) {
            false
        } else {
            // Nobody listens for each frame ([FrameDemand]): the frame is drawn, and taken, with nothing posted.
            if ((listener as? FrameDemand)?.wantsFrames != false && steadyPending++ == 0) main.post(steadyReport)
            true
        }
    }

    /**
     * The viewing run on the main thread: bumped by each pause and resume. A frame report carries the run it was
     * posted in (read on the player's thread), so one still queued from the run before isn't delivered.
     */
    @Volatile
    private var viewRun = 0

    /** [viewRun] as the player's thread has applied it: frame reports are stamped with this. Its thread only. */
    private var workerRun = 0

    internal inner class Events : HaWebRtcStream.Listener {
        override fun onFirstFrame(sinceStartMs: Long, width: Int, height: Int) = frame(width, height) { it.onFirstFrame(sinceStartMs, width, height) }
        override fun onFrame(width: Int, height: Int) = frame(width, height) { it.onFrameRendered() }
        override fun onReconnecting(attempt: Int, error: HaError) = report { it.onReconnecting(attempt, error) }
        override fun onError(error: HaError) = report { it.onError(error) }
    }

    private class HandlerScheduler(private val handler: Handler) : StreamScheduler {
        override fun post(task: Runnable) {
            handler.post(task)
        }

        override fun postDelayed(delayMs: Long, task: Runnable): StreamScheduler.Cancellable {
            val r = Runnable { task.run() }
            handler.postDelayed(r, delayMs)
            return StreamScheduler.Cancellable { handler.removeCallbacks(r) }
        }

        override fun nowMs(): Long = SystemClock.elapsedRealtime()
    }
}
