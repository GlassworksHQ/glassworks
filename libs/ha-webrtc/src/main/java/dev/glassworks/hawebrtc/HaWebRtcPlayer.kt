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
 * every player in the process and freed with the last one.
 *
 * - [start] connects and plays (once).
 * - [setActive] false stops drawing, and closes the connection after [HaWebRtcStream.Options.idleTeardownMs];
 *   true resumes.
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
    public class Options(
        /** The connection's behaviour: audio, timeout, reconnecting, pausing. */
        public val stream: HaWebRtcStream.Options = HaWebRtcStream.Options(),
        /** How the picture fills [view]. */
        public val scaling: VideoScaling = VideoScaling.FIT,
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

    private val appContext: Context = context.applicationContext ?: context
    private val main = Handler(Looper.getMainLooper())
    private val video = VideoTextureView(context, options.scaling)
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
     * The picture's width in pixels as shown (rotation applied), 0 until the first frame. Main thread; it is up to date
     * when [Listener.onFirstFrame] and [Listener.onFrameRendered] are called.
     */
    public var videoWidth: Int = 0
        private set

    /** The picture's height in pixels, as [videoWidth]. */
    public var videoHeight: Int = 0
        private set

    /** The picture. Place it before [start]; it stays usable until [close]. */
    public val view: View get() = video

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
        // A frame report the player's thread posted before this belongs to the run that just ended.
        viewRun++
        worker?.post { stream?.setActive(active) }
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
            stream?.close()
            stream = null
            video.release()
            if (runtime != null) WebRtcRuntime.release()
            runtime = null
            t?.quitSafely()
        }
    }

    private fun report(call: (Listener) -> Unit) {
        main.post { if (!closed) listener?.let(call) }
    }

    /** Like [report], with the picture's size updated first. */
    private fun frame(width: Int, height: Int, call: (Listener) -> Unit) {
        val run = viewRun
        main.post {
            // A report from before a pause or resume (still queued here when it happened) is dropped, size and all.
            if (closed || run != viewRun) return@post
            videoWidth = width
            videoHeight = height
            listener?.let(call)
        }
    }

    /**
     * The viewing run on the main thread: bumped by each pause and resume. A frame report carries the run it was
     * posted in (read on the player's thread), so one still queued from the run before isn't delivered.
     */
    @Volatile
    private var viewRun = 0

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
