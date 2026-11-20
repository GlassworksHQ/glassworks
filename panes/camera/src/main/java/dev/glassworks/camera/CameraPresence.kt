/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.camera

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.glassworks.screen.PresenceSource

/**
 * One greyscale camera frame: the Y plane of a YUV image. Rows are [rowStride] bytes apart, of which
 * the first [width] are pixels. Unsigned: 0 is black, 255 white.
 */
class LumaFrame(val width: Int, val height: Int, val rowStride: Int, val luma: ByteArray) {
    init {
        require(width > 0 && height > 0 && rowStride >= width) { "bad frame ${width}x$height stride $rowStride" }
        require(luma.size >= rowStride * (height - 1) + width) { "frame needs more than ${luma.size} bytes" }
    }
}

/**
 * Where frames go. Called on one thread that isn't the main thread. While the source is open, frames
 * keep coming; if none comes for [CameraPresence.STALE_MS] (the first one: [CameraPresence.FIRST_FRAME_MS]
 * after [started]), presence counts the camera as gone.
 */
interface FrameSink {
    fun frame(frame: LumaFrame)

    /** The camera opened and was asked for frames: the first may take [CameraPresence.FIRST_FRAME_MS]. */
    fun started() {}

    /** The camera stopped delivering (closed by another app, disconnected, an error). No frames follow. */
    fun lost(why: String)
}

/** The camera, as presence sees it. The frame's implementation picks the camera and the size. */
fun interface FrameSource {
    /**
     * Starts delivering about [fps] frames a second to [sink]. Closing stops them and releases the
     * camera; it may be called from any thread, also before the first frame.
     */
    fun open(fps: Float, sink: FrameSink): AutoCloseable
}

/**
 * A [PresenceSource] from camera frames, for frames with a camera and no presence sensor: someone near
 * means motion that isn't a lighting change ([MotionDetectorCore], the maintainer's detector from
 * android-ip-camera). It looks at about [fps] frames a second and drops the rest. Frames go to the
 * detector and nowhere else: none is kept or sent.
 *
 * The raw value of each reading is the share of the detector's grid that changed, or -1 when there is
 * no reading: the camera failed to open, was lost, or sent nothing for [STALE_MS]. Each of those reads
 * as far, and [onProblem] gets why, once until a frame is seen again. [onMotion] gets each change of
 * motion, for the ONVIF events. [sensitivity] (1 to 10) is read on every frame.
 *
 * [clock] is `SystemClock.elapsedRealtime`; [post] runs a callback on the main thread, and [after] runs
 * one there after a delay.
 */
class CameraPresence(
    private val camera: FrameSource,
    sensitivity: () -> Int = { DEFAULT_SENSITIVITY },
    private val fps: Float = DEFAULT_FPS,
    holdMs: Long = HOLD_MS,
    private val clock: () -> Long = android.os.SystemClock::elapsedRealtime,
    private val post: (Runnable) -> Unit = Handler(Looper.getMainLooper())::post,
    private val after: (delayMs: Long, Runnable) -> Unit = { ms, r -> Handler(Looper.getMainLooper()).postDelayed(r, ms) },
    private val onProblem: (why: String) -> Unit = {},
    private val onMotion: (Boolean) -> Unit = {},
) : PresenceSource {
    init { require(fps > 0f) { "fps must be positive" } }

    // One lock for the sessions and the detector. Readings are posted while it is held, so they reach
    // the main thread in the order the state changed. The detector is cheap (a 48x27 grid).
    private val lock = Any()
    private var session: Session? = null
    private var motion = false
    private val detector = MotionDetectorCore(sensitivity, holdMs) { active -> motion = active }

    override fun start(context: Context, onReading: (near: Boolean, raw: Float) -> Unit) {
        val s = Session(onReading)
        // The old session ends in the same hold of the lock that replaces it, so nothing it does can
        // reach the new one; only its camera handle closes outside.
        synchronized(lock) { session.also { session = s }?.closeLocked() }?.close()
        s.begin()
        val h = try {
            camera.open(fps, s)
        } catch (e: Exception) {
            s.fail("couldn't open the camera: ${e.javaClass.simpleName}")
            return
        }
        s.attach(h)
    }

    override fun stop(context: Context) {
        synchronized(lock) { session.also { session = null }?.closeLocked() }?.close()
    }

    /** A camera has no distance to set: what counts is how much of the picture moves. */
    override fun setRange(context: Context, metres: Float): String? = if (metres <= 0f) null else NO_RANGE

    /** Under [lock]: tells [onMotion] when the detector's state differs from what it last heard. */
    private var told = false
    private fun tellMotion() {
        if (motion == told) return
        told = motion
        val m = motion
        post { onMotion(m) }
    }

    // Every field is guarded by [lock].
    private inner class Session(private val onReading: (Boolean, Float) -> Unit) : FrameSink {
        private var handle: AutoCloseable? = null
        private var closed = false
        private var lastSeenMs = Long.MIN_VALUE
        private val deadline = FrameDeadline()
        private var watching = false
        private var failing = false

        /** Starts the silence watchdog before the camera opens: a source that never sends counts as gone too. */
        fun begin() = synchronized(lock) {
            deadline.started(clock())
            watch()
        }

        /** Under [lock]: arms the watchdog unless it is armed. */
        private fun watch() {
            if (watching) return
            watching = true
            after(deadline.nextCheck(clock()), ::checkStale)
        }

        /** The camera [open] returned. A [close] that came first closes it at once. */
        fun attach(h: AutoCloseable) {
            val lateClose = synchronized(lock) { if (!closed) handle = h; closed }
            if (lateClose) h.close()
        }

        override fun frame(frame: LumaFrame) = synchronized(lock) {
            if (closed) return
            val now = clock()
            deadline.frame(now)
            watch()
            if (lastSeenMs != Long.MIN_VALUE && now - lastSeenMs < (1000f / fps).toLong()) return
            lastSeenMs = now
            failing = false
            val luma = frame.luma
            detector.process({ i -> luma[i].toInt() and 0xff }, frame.width, frame.height, frame.rowStride, 1, now)
            tellMotion()
            val near = motion
            val raw = detector.lastChangedFraction.toFloat()
            post { if (isOpen()) onReading(near, raw) }
        }

        override fun started() = synchronized(lock) {
            if (closed) return
            deadline.started(clock())
            watch()
        }

        override fun lost(why: String) = fail("the camera stopped: $why")

        /** Far, and why, once until a frame is seen again. */
        fun fail(why: String) = synchronized(lock) {
            if (closed) return
            failLocked(why)
        }

        private fun failLocked(why: String) {
            detector.reset()
            tellMotion()
            val first = !failing
            failing = true
            post {
                if (!isOpen()) return@post
                if (first) onProblem(why)
                onReading(false, -1f)
            }
        }

        private fun checkStale() = synchronized(lock) {
            watching = false
            if (closed) return
            val now = clock()
            if (deadline.left(now) > 0) { watch(); return }
            failLocked(deadline.why(now))
        }

        private fun isOpen() = synchronized(lock) { !closed }

        /** Under [lock]: ends the session and returns its camera handle, for the caller to close outside. */
        fun closeLocked(): AutoCloseable? {
            closed = true
            detector.reset()
            tellMotion()
            return handle.also { handle = null }
        }
    }

    companion object {
        /** The maintainer's detector samples every 200 ms; it needs 2 changed frames to start. */
        const val DEFAULT_FPS = 5f

        /** Motion ends this long after the last changed frame (MotionDetectorCore's default). */
        const val HOLD_MS = 8_000L

        /** 1 (least) to 10 (most sensitive): `camera_sensitivity`. */
        const val DEFAULT_SENSITIVITY = 5

        /** A camera that sends nothing this long counts as gone, so a stuck source can't keep the screen on. */
        const val STALE_MS = 5_000L

        /**
         * The first frame's grace, from the open (or the start, until the camera says it opened). The Mirror's
         * LEGACY camera sent nothing for more than 5 s after it opened, then streamed (#674). Twice [STALE_MS]
         * covers that and still ends a first session that never sends (#599) within 10 s.
         */
        const val FIRST_FRAME_MS = 10_000L

        const val NO_RANGE = "The camera has no range setting"
    }
}

/**
 * When a camera's next frame is late: [firstMs] after it [started], [silentMs] after each [frame]. Both the hub's
 * reopen watchdog and presence keep one, so they see the same silence. Not thread-safe: its owner's thread or lock.
 */
internal class FrameDeadline(
    private val firstMs: Long = CameraPresence.FIRST_FRAME_MS,
    private val silentMs: Long = CameraPresence.STALE_MS,
) {
    private var markMs = 0L
    private var framed = false

    fun started(now: Long) { markMs = now; framed = false }

    fun frame(now: Long) { markMs = now; framed = true }

    /** How long until the frame is late: 0 or less when it is. */
    fun left(now: Long) = (if (framed) silentMs else firstMs) - (now - markMs)

    /**
     * When to look again: never later than [silentMs], so a first frame that comes during the grace and is then
     * followed by silence is still caught [silentMs] after it, not when the grace would have run out.
     */
    fun nextCheck(now: Long) = minOf(left(now), silentMs)

    /** The silence, in words, for the log and Home Assistant. */
    fun why(now: Long): String {
        val s = (now - markMs) / 1000
        return if (framed) "no frames for $s s" else "no first frame in $s s"
    }
}
