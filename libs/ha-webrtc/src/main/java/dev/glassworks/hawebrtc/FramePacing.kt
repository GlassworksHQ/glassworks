package dev.glassworks.hawebrtc

import android.os.Handler
import android.os.HandlerThread
import android.os.Trace
import android.view.Choreographer
import livekit.org.webrtc.VideoFrame
import java.util.concurrent.atomic.AtomicBoolean

/**
 * When the frames of several players that draw into TextureViews ([HaWebRtcPlayer.Options.pacedMaxFps]) are drawn:
 * together, on one display frame, at most [maxFps] times a second.
 *
 * A TextureView makes its window draw again for each frame it gets. Four cameras at 25 frames a second, each on a
 * clock of its own, land on different display frames: nearly every display frame (40 or more a second on a 60 Hz
 * panel) then redraws the window for one camera's picture. Here a frame that comes in waits for the next tick, the
 * newest replacing an older one still waiting, and on a tick every waiting frame is drawn: the window then redraws
 * once for all of them.
 *
 * Ticks come every `n` display frames: as few as keep the fastest camera's frames (with a tenth to spare, so jitter
 * doesn't drop one) and as many as keep the rate under [maxFps]. At 60 Hz that is every 2nd (30 a second) for 25 or
 * 30 fps cameras, every 3rd for 15 fps, every 5th for 10 fps. A camera faster than [maxFps] loses frames: that is
 * the cap.
 *
 * One player alone isn't paced: there is nothing to line its frames up with, and pacing would only make them later.
 * A player counts as playing for [ACTIVE_NS] after its last frame.
 *
 * Plain Kotlin over the frame type [F], so the JVM tests drive it with a fake clock; [VsyncPacer] runs it on the
 * display's vsync. Thread-safe: frames come in on each player's decoder thread, ticks on the pacer's thread.
 */
internal class FramePacing<F : Any>(
    /** The most ticks a second (and so the most window redraws for the paced players). */
    val maxFps: Int,
    private val retain: (F) -> Unit,
    private val release: (F) -> Unit,
) {
    init {
        require(maxFps > 0) { "maxFps must be above 0" }
    }

    /** One paced player: [draw] draws a frame now (any thread; on a tick, the pacer's). */
    inner class Client internal constructor(internal val draw: (F) -> Unit) {
        internal var pending: F? = null
        internal var lastArrivalNs = Long.MIN_VALUE
        /** The time between its frames, smoothed; 0 until two have come. */
        internal var intervalNs = 0L
        internal var registered = true
    }

    private val lock = Any()
    private val clients = ArrayList<Client>()

    /** The display frame's length, measured from the ticks themselves (60 Hz until measured). */
    @Volatile internal var vsyncNs = DEFAULT_VSYNC_NS
        private set
    private var lastTickNs = Long.MIN_VALUE
    private var lastFlushNs = Long.MIN_VALUE

    /** Frames a newer one replaced before a tick drew it (tests, traces). */
    @Volatile internal var dropped = 0L
        private set

    fun register(draw: (F) -> Unit): Client = Client(draw).also { synchronized(lock) { clients += it } }

    /** [client] goes: a frame it has waiting is released, and it is never drawn again. */
    fun unregister(client: Client) {
        val left = synchronized(lock) {
            client.registered = false
            clients -= client
            client.pending.also { client.pending = null }
        }
        left?.let(release)
    }

    /**
     * A frame for [client] came in at [nowNs]. False: draw it now (one player alone, [immediate] asked, or [client]
     * gone); a frame of [client]'s still waiting is released. True: it waits for a tick (retained here; the caller's
     * own reference is the caller's) and the caller makes sure ticks come ([VsyncPacer.ensureTicking]).
     */
    fun offer(client: Client, frame: F, nowNs: Long, immediate: Boolean = false): Boolean {
        var stale: F? = null
        val paced = synchronized(lock) {
            if (!client.registered) return false
            val last = client.lastArrivalNs
            if (last != Long.MIN_VALUE) {
                val dt = nowNs - last
                if (dt in 1 until ACTIVE_NS) client.intervalNs = if (client.intervalNs == 0L) dt else (client.intervalNs * 7 + dt) / 8
            }
            client.lastArrivalNs = nowNs
            val paced = !immediate && playing(nowNs) >= 2
            stale = client.pending
            if (paced) {
                retain(frame)
                client.pending = frame
                if (stale != null) dropped++
            } else {
                client.pending = null
            }
            paced
        }
        stale?.let(release)
        return paced
    }

    /** The players that had a frame in the last [ACTIVE_NS]. Under [lock]. */
    private fun playing(nowNs: Long): Int {
        var n = 0
        for (i in clients.indices) {
            val c = clients[i]
            if (c.lastArrivalNs != Long.MIN_VALUE && nowNs - c.lastArrivalNs < ACTIVE_NS) n++
        }
        return n
    }

    /** A client has a frame waiting. Under [lock]; index loops, as a tick runs on every display frame. */
    private fun waiting(): Boolean {
        for (i in clients.indices) if (clients[i].pending != null) return true
        return false
    }

    /** Display frames per tick now: see the class. Under [lock]. */
    internal fun framesPerTick(nowNs: Long): Int {
        val vsync = vsyncNs.toDouble()
        val capFrames = Math.ceil(NS_PER_S / maxFps / vsync - 0.05).toInt().coerceAtLeast(1)
        var fastest = Long.MAX_VALUE
        for (i in clients.indices) {
            val c = clients[i]
            if (c.intervalNs > 0 && nowNs - c.lastArrivalNs < ACTIVE_NS && c.intervalNs < fastest) fastest = c.intervalNs
        }
        val streamFrames = if (fastest == Long.MAX_VALUE) 1 else Math.floor(fastest / (STREAM_HEADROOM * vsync)).toInt().coerceAtLeast(1)
        return maxOf(capFrames, streamFrames)
    }

    /**
     * A display frame starts at [frameTimeNs]: when a tick is due, every waiting frame is drawn (outside the lock),
     * then released. True while ticks are still wanted: a frame waits, or a player played lately.
     */
    fun tick(frameTimeNs: Long): Boolean {
        dueClients.clear()
        dueFrames.clear()
        val keep = synchronized(lock) {
            val sinceTick = frameTimeNs - lastTickNs
            if (lastTickNs != Long.MIN_VALUE && sinceTick > MIN_VSYNC_NS && sinceTick < vsyncNs * 3 / 2) {
                vsyncNs = (vsyncNs * 7 + sinceTick) / 8
            }
            lastTickNs = frameTimeNs
            if (waiting()) {
                val frames = if (lastFlushNs == Long.MIN_VALUE) Int.MAX_VALUE else Math.round((frameTimeNs - lastFlushNs).toDouble() / vsyncNs).toInt()
                if (frames >= framesPerTick(frameTimeNs)) {
                    lastFlushNs = frameTimeNs
                    for (i in clients.indices) {
                        val c = clients[i]
                        val f = c.pending ?: continue
                        c.pending = null
                        dueClients.add(c)
                        dueFrames.add(f)
                    }
                }
            }
            waiting() || playing(frameTimeNs) > 0
        }
        for (i in dueFrames.indices) {
            val f = dueFrames[i]
            try {
                dueClients[i].draw(f)
            } finally {
                release(f)
            }
        }
        dueClients.clear()
        dueFrames.clear()
        return keep
    }

    /** A tick's frames, drawn outside the lock: [tick]'s own, kept so a tick allocates nothing. Ticks run on one thread. */
    private val dueClients = ArrayList<Client>()
    private val dueFrames = ArrayList<F>()

    /** A frame waits for a tick. */
    fun hasPending(): Boolean = synchronized(lock) { waiting() }

    companion object {
        /** How long after its last frame a player still counts as playing. */
        const val ACTIVE_NS = 500_000_000L
        const val DEFAULT_VSYNC_NS = 16_666_667L
        private const val MIN_VSYNC_NS = 4_000_000L
        private const val NS_PER_S = 1_000_000_000.0

        /** Ticks come at least this much faster than the fastest camera's frames, so jitter doesn't drop one. */
        private const val STREAM_HEADROOM = 1.1
    }
}

/**
 * [FramePacing] on the display's vsync: a thread of its own ("ha-webrtc-pace") with a [Choreographer], so neither
 * the main thread nor a decoder's waits on it. One per [FramePacing.maxFps] in the process; idle (no callback
 * asked for) while no paced player plays. Each tick that draws is an `ha-webrtc pace` trace section.
 */
internal class VsyncPacer private constructor(maxFps: Int) {
    val pacing = FramePacing<VideoFrame>(maxFps, VideoFrame::retain, VideoFrame::release)

    private val ticking = AtomicBoolean(false)
    private val handler: Handler by lazy { Handler(THREAD.looper) }
    private val choreographer: Choreographer by lazy { Choreographer.getInstance() }

    private val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val keep = if (!pacing.hasPending()) {
                pacing.tick(frameTimeNanos)
            } else {
                Trace.beginSection(TRACE_TICK)
                try {
                    pacing.tick(frameTimeNanos)
                } finally {
                    Trace.endSection()
                }
            }
            if (keep) {
                choreographer.postFrameCallback(this)
            } else {
                ticking.set(false)
                // A frame that came in between the tick and now would wait for good.
                if (pacing.hasPending()) ensureTicking()
            }
        }
    }

    /** Ticks come from the next display frame on. Any thread. */
    fun ensureTicking() {
        if (ticking.compareAndSet(false, true)) handler.post { choreographer.postFrameCallback(callback) }
    }

    companion object {
        private const val TRACE_TICK = "ha-webrtc pace"

        private val THREAD: HandlerThread by lazy { HandlerThread("ha-webrtc-pace").apply { isDaemon = true; start() } }
        private val pacers = HashMap<Int, VsyncPacer>()

        /** The process's pacer for [maxFps]. */
        fun forFps(maxFps: Int): VsyncPacer = synchronized(pacers) { pacers.getOrPut(maxFps) { VsyncPacer(maxFps) } }
    }
}
