package dev.glassworks.hawebrtc

/**
 * A Home Assistant camera's live WebRTC stream, from the first request to the last frame: what HA's frontend
 * `ha-web-rtc-player` does, plus what a wall panel needs on top (a timeout, reconnecting, pausing). Plain Kotlin:
 * the peer comes from a [WebRtcPeer.Factory] ([HaWebRtcPlayer] brings libwebrtc's), and time and threads from a
 * [StreamScheduler], so all of it runs in JVM tests.
 *
 * One connection, as the frontend makes it:
 * 1. `camera/webrtc/get_client_config` (via [HaWebRtcSignaling]) gives the ICE servers.
 * 2. A peer with recvonly video, and recvonly audio when [Options.audio]; the data channel the camera asks for.
 * 3. The offer goes to HA (`camera/webrtc/offer`). The answer is set as soon as it comes; the camera's candidates are
 *    added once it is set (any that come earlier wait for it). This side's candidates trickle to HA.
 * 4. The first frame on screen is [Listener.onFirstFrame] (HA's `hasVideo: true`).
 *
 * What it does beyond the frontend:
 * - **Timeout.** No frame within [Options.connectTimeoutMs] fails the connection ([TIMEOUT]).
 * - **Reconnecting.** A stream that has played reconnects when its connection fails (ICE or DTLS `failed`, or
 *   `disconnected` for [Options.disconnectedGraceMs]): a new peer and a new HA session, up to [Options.maxReconnects]
 *   times in a row, waiting [Options.reconnectDelayMs] and doubling. The frontend calls `restartIce()` instead, and
 *   its renegotiation sends a new offer, which HA answers with a new session and a new peer on the camera's side
 *   (go2rtc's, for one); so a fresh connection costs the same and doesn't depend on a DTLS restart. A stream that
 *   never played fails at once, so a caller can fall back to HLS or MJPEG quickly, as the frontend does.
 * - **Waiting for Home Assistant.** A reconnection that fails because the channel has no connection to HA
 *   ([HaError.NOT_CONNECTED]: HA restarting, the panel's Wi-Fi gone) doesn't use one of those tries: the stream
 *   keeps trying at the backed-off wait, as the frontend's offer waits for its socket to come back. [connectionRestored]
 *   (the channel is back) makes it try at once.
 * - **Pausing.** [setActive] false stops drawing at once, and after [Options.idleTeardownMs] (60 s, the frontend's
 *   wait when the page is hidden) closes the connection; true resumes, reconnecting if it was closed.
 *
 * Every method must be called on the [StreamScheduler]'s thread, and every [Listener] call comes on it. After
 * [Listener.onError] the stream is over: [close] it. After [close], nothing is called back.
 */
public class HaWebRtcStream(
    private val channel: HaCommandChannel,
    /** The camera entity (`camera.front_door`). */
    public val entityId: String,
    private val peers: WebRtcPeer.Factory,
    private val scheduler: StreamScheduler,
    private val options: Options = Options(),
) {
    /** How the stream behaves. The defaults follow HA's frontend where it has a rule, and are a panel's otherwise. */
    public class Options(
        /** Receive the camera's audio too (a recvonly audio transceiver). Off: cameras on a dashboard are muted. */
        public val audio: Boolean = false,
        /**
         * No frame this long after a connection starts fails it ([TIMEOUT]). It counts from the connection's first
         * request to HA: [HaWebRtcPlayer]'s first connection makes libwebrtc's shared parts and its renderer while that
         * request is out, so they count too (only the renderer when [HaWebRtcPlayer.prewarm] made the parts already).
         */
        public val connectTimeoutMs: Long = 20_000,
        /** How long a paused stream keeps its connection before it closes it. 0 closes it at once. */
        public val idleTeardownMs: Long = 60_000,
        /**
         * Reconnections in a row (without a frame between them) before the stream gives up. Tries that failed for want
         * of a connection to HA ([HaError.NOT_CONNECTED]) don't count.
         */
        public val maxReconnects: Int = 3,
        /** The wait before the first reconnection; it doubles for each one after, up to 32 times it. */
        public val reconnectDelayMs: Long = 1_000,
        /** How long `disconnected` may last before the connection counts as failed. */
        public val disconnectedGraceMs: Long = 5_000,
    )

    /** What the stream reports, on the scheduler's thread. */
    public interface Listener {
        /** The first frame since [start] or the last resume is on screen, [sinceStartMs] after it. */
        public fun onFirstFrame(sinceStartMs: Long, width: Int, height: Int)

        /** Each frame after the first. */
        public fun onFrame(width: Int, height: Int) {}

        /** The connection failed with [error] and the stream is making connection number [attempt] of a row. */
        public fun onReconnecting(attempt: Int, error: HaError) {}

        /** HA's session id for the current connection, when it comes (for logs). */
        public fun onSession(sessionId: String) {}

        /** The stream can't play, or stopped and couldn't come back: [START_FAILED], [CONNECT_FAILED], [ICE_FAILED] or [TIMEOUT]. Last call. */
        public fun onError(error: HaError)
    }

    /** Where the stream reports. Set it before [start]. */
    public var listener: Listener? = null

    /**
     * A frame of a run that plays on, at the size of the one before, taken on the thread that drew it (the main
     * thread) instead of going through the stream's own: true when taken. False sends it the usual way, to
     * [Listener.onFrame] on the scheduler's thread. Such a frame changes nothing of the stream's own (its timeout is
     * off, its tries counted afresh): only its report hops threads and allocates, at 15 or more frames a second.
     */
    internal fun interface SteadyFrames {
        fun onSteadyFrame(width: Int, height: Int): Boolean
    }

    /** [HaWebRtcPlayer]'s [SteadyFrames]; none for any other listener, which hears of every frame on the scheduler's thread. */
    @Volatile
    internal var steadyFrames: SteadyFrames? = null

    /**
     * The connection and run whose frames may go to [steadyFrames]: set on the scheduler's thread once a frame of that
     * run was reported, cleared when it pauses or the connection goes. Read on the thread a frame is drawn on.
     */
    @Volatile private var steadyAttempt: Attempt? = null
    @Volatile private var steadyRun = -1

    private inner class Attempt {
        var signaling: HaWebRtcSignaling? = null
        var peer: WebRtcPeer? = null
        var answered = false
        val early = ArrayList<IceCandidateInit>()
        var timeout: StreamScheduler.Cancellable? = null
        var disconnected: StreamScheduler.Cancellable? = null

        /** A frame came on this connection (since it started): it played, so a failure now reconnects. */
        var playing = false

        /** No frame yet in this run (since the connection started, or the last resume): the timeout is armed. */
        var awaitingFrame = true
    }

    private var started = false
    private var closed = false
    private var failed = false
    private var active = true
    private var attempt: Attempt? = null
    private var played = false

    /** Reconnections since the last frame that count against [Options.maxReconnects]. */
    private var reconnects = 0

    /** Every reconnection since the last frame, those waiting for HA's connection too: the backoff. */
    private var retries = 0
    private var runStartMs = 0L
    private var runReported = false

    /**
     * The viewing run: bumped by each resume. A frame carries the run it was decoded in from the peer's thread, so one
     * still queued from before a pause can't count as the first of the next run (and cancel its timeout).
     */
    @Volatile private var run = 0
    private var idle: StreamScheduler.Cancellable? = null
    private var reconnect: StreamScheduler.Cancellable? = null

    /** Connects and plays. Once: a second call does nothing. */
    public fun start() {
        if (started || closed) return
        started = true
        runStartMs = scheduler.nowMs()
        if (active) connect()
    }

    /**
     * False pauses: no frames are drawn, and the connection closes after [Options.idleTeardownMs]. True resumes, and
     * the next frame is a new [Listener.onFirstFrame]. A stream starts active.
     */
    public fun setActive(active: Boolean) {
        if (this.active == active) return
        this.active = active
        if (!started || closed || failed) return
        if (!active) {
            steadyAttempt = null
            reconnect?.cancel()
            reconnect = null
            attempt?.let { a ->
                a.peer?.setReceiving(false)
                a.timeout?.cancel()
                a.timeout = null
            }
            if (options.idleTeardownMs <= 0) dropConnection()
            else idle = scheduler.postDelayed(options.idleTeardownMs) { idle = null; dropConnection() }
        } else {
            idle?.cancel()
            idle = null
            runStartMs = scheduler.nowMs()
            runReported = false
            run++
            val a = attempt
            if (a == null) {
                reconnects = 0
                retries = 0
                connect()
            } else {
                // Kept through the pause: it must show a new frame in time too, or it counts as failed (a peer that
                // stays connected but stopped sending video would otherwise never be noticed).
                a.peer?.setReceiving(true)
                armTimeout(a)
            }
        }
    }

    /** Ends the stream and frees the connection. Idempotent; nothing is reported after it. */
    public fun close() {
        if (closed) return
        closed = true
        listener = null
        idle?.cancel()
        reconnect?.cancel()
        idle = null
        reconnect = null
        dropConnection()
    }

    /**
     * The channel's connection to Home Assistant is back after it dropped. A stream waiting to reconnect connects now,
     * with its tries counted afresh; a connection still being set up starts over, since what it sent went through the
     * connection that dropped and its answer won't come. A connection that has played is left alone: it ends on its
     * own if HA closed its session ([Options.disconnectedGraceMs]). Nothing happens to a paused, failed or closed one.
     */
    public fun connectionRestored() {
        if (!started || closed || failed || !active) return
        val a = attempt
        if (a?.playing == true) return
        reconnect?.cancel()
        reconnect = null
        reconnects = 0
        retries = 0
        if (a != null) {
            teardown(a)
            attempt = null
        }
        connect()
    }

    private fun dropConnection() {
        attempt?.let(::teardown)
        attempt = null
    }

    private fun connect() {
        val a = Attempt()
        attempt = a
        armTimeout(a)
        val signaling = HaWebRtcSignaling(channel, entityId, SignalingEvents(a))
        a.signaling = signaling
        signaling.fetchClientConfig(
            { config -> scheduler.post { onConfig(a, config) } },
            { error -> scheduler.post { failAttempt(a, startFailed(error), offline = error.code == HaError.NOT_CONNECTED) } },
        )
    }

    private fun onConfig(a: Attempt, config: WebRtcClientConfig) {
        if (attempt !== a) return
        val peer = try {
            peers.create(config, options.audio, PeerEvents(a))
        } catch (e: Exception) {
            return failAttempt(a, HaError(START_FAILED, e.message ?: e.javaClass.simpleName))
        }
        a.peer = peer
        if (!active) peer.setReceiving(false)
        peer.createOffer(
            { sdp -> scheduler.post { if (attempt === a) a.signaling?.offer(sdp) } },
            { reason -> scheduler.post { failAttempt(a, HaError(START_FAILED, reason)) } },
        )
    }

    private fun armTimeout(a: Attempt) {
        a.timeout?.cancel()
        a.awaitingFrame = true
        a.timeout = scheduler.postDelayed(options.connectTimeoutMs) {
            if (attempt === a && a.awaitingFrame) failAttempt(a, HaError(TIMEOUT, "No video within ${options.connectTimeoutMs} ms"))
        }
    }

    private fun onFrame(a: Attempt, frameRun: Int, width: Int, height: Int) {
        if (attempt !== a || !active || frameRun != run) return
        if (a.awaitingFrame) {
            a.awaitingFrame = false
            a.timeout?.cancel()
            a.timeout = null
        }
        if (!a.playing) {
            a.playing = true
            played = true
            reconnects = 0
            retries = 0
        }
        if (!runReported) {
            runReported = true
            listener?.onFirstFrame(scheduler.nowMs() - runStartMs, width, height)
        } else {
            listener?.onFrame(width, height)
        }
        // The run plays on: its next frames may skip this thread ([steadyFrames]). The run first, so a frame that
        // reads the attempt already sees this run.
        if (steadyFrames != null && (steadyAttempt !== a || steadyRun != frameRun)) {
            steadyRun = frameRun
            steadyAttempt = a
        }
    }

    private fun onState(a: Attempt, state: PeerState) {
        if (attempt !== a) return
        when (state) {
            PeerState.FAILED -> failAttempt(a, HaError(ICE_FAILED, "The connection to the camera failed"))
            PeerState.DISCONNECTED -> if (a.disconnected == null) {
                a.disconnected = scheduler.postDelayed(options.disconnectedGraceMs) {
                    if (attempt === a) failAttempt(a, HaError(ICE_FAILED, "The connection to the camera was lost"))
                }
            }
            PeerState.CONNECTED -> {
                a.disconnected?.cancel()
                a.disconnected = null
            }
            else -> Unit
        }
    }

    /** [offline]: it failed because the channel has no connection to HA, which isn't the camera failing. */
    private fun failAttempt(a: Attempt, error: HaError, offline: Boolean = false) {
        if (attempt !== a || closed || failed) return
        teardown(a)
        attempt = null
        if (played && (offline || reconnects < options.maxReconnects)) {
            if (!offline) reconnects++
            retries++
            val delay = options.reconnectDelayMs shl (retries - 1).coerceAtMost(MAX_BACKOFF_SHIFT)
            // The timer first, so a listener that closes the stream from onReconnecting cancels it.
            reconnect = scheduler.postDelayed(delay) {
                reconnect = null
                if (!closed && !failed && active && attempt == null) connect()
            }
            listener?.onReconnecting(retries, error)
        } else {
            failed = true
            idle?.cancel()
            idle = null
            listener?.onError(error)
        }
    }

    private fun teardown(a: Attempt) {
        if (steadyAttempt === a) steadyAttempt = null
        a.timeout?.cancel()
        a.disconnected?.cancel()
        a.timeout = null
        a.disconnected = null
        a.early.clear()
        // Each step whatever the one before did: a channel whose unsubscribe throws still leaves the peer closed.
        runCatching { a.signaling?.close() }
        runCatching { a.peer?.close() }
    }

    private fun startFailed(error: HaError) = HaError(START_FAILED, listOf(error.code, error.message).filter { it.isNotEmpty() }.joinToString(": "))

    private inner class SignalingEvents(private val a: Attempt) : HaWebRtcSignaling.Listener {
        override fun onSession(sessionId: String) {
            scheduler.post { if (attempt === a) listener?.onSession(sessionId) }
        }

        override fun onAnswer(sdp: String) {
            scheduler.post {
                val peer = a.peer
                if (attempt !== a || peer == null) return@post
                peer.setRemoteAnswer(sdp, {
                    scheduler.post {
                        if (attempt !== a) return@post
                        a.answered = true
                        a.early.forEach(peer::addRemoteCandidate)
                        a.early.clear()
                    }
                }, { reason -> scheduler.post { failAttempt(a, HaError(CONNECT_FAILED, reason)) } })
            }
        }

        override fun onRemoteCandidate(candidate: IceCandidateInit) {
            scheduler.post {
                if (attempt !== a) return@post
                if (a.answered) a.peer?.addRemoteCandidate(candidate) else a.early += candidate
            }
        }

        override fun onError(error: HaError) {
            scheduler.post { failAttempt(a, startFailed(error), offline = error.code == HaError.NOT_CONNECTED) }
        }
    }

    private inner class PeerEvents(private val a: Attempt) : WebRtcPeer.Events {
        override fun onLocalCandidate(candidate: IceCandidateInit) {
            scheduler.post { if (attempt === a) a.signaling?.addLocalCandidate(candidate) }
        }

        override fun onConnectionState(state: PeerState) {
            scheduler.post { onState(a, state) }
        }

        override fun onFrame(width: Int, height: Int) {
            val frameRun = run
            val steady = steadyFrames
            if (steady != null && steadyAttempt === a && steadyRun == frameRun && steady.onSteadyFrame(width, height)) return
            scheduler.post { onFrame(a, frameRun, width, height) }
        }
    }

    /** The error codes the stream reports. */
    public companion object {
        /** HA's frontend's `start_failed`: the configuration, the offer or HA's answer to it failed. */
        public const val START_FAILED: String = "start_failed"

        /** HA's frontend's `connect_failed`: the camera's answer couldn't be set. */
        public const val CONNECT_FAILED: String = "connect_failed"

        /** ICE or DTLS failed, or the connection stayed lost. */
        public const val ICE_FAILED: String = "ice_failed"

        /** No video in time. */
        public const val TIMEOUT: String = "timeout"

        private const val MAX_BACKOFF_SHIFT = 5
    }
}
