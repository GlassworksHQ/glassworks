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
 *   times in a row, waiting [Options.reconnectDelayMs] and doubling. The frontend calls `restartIce()` instead, but
 *   every offer to HA is a new session with a new peer on the camera's side (go2rtc's, for one), so a fresh
 *   connection costs the same and doesn't depend on a DTLS restart. A stream that never played fails at once, so a
 *   caller can fall back to HLS or MJPEG quickly, as the frontend does.
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
        /** No frame this long after a connection starts fails it ([TIMEOUT]). */
        public val connectTimeoutMs: Long = 20_000,
        /** How long a paused stream keeps its connection before it closes it. 0 closes it at once. */
        public val idleTeardownMs: Long = 60_000,
        /** Reconnections in a row (without a frame between them) before the stream gives up. */
        public val maxReconnects: Int = 3,
        /** The wait before the first reconnection; it doubles for each one after. */
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

    private inner class Attempt {
        var signaling: HaWebRtcSignaling? = null
        var peer: WebRtcPeer? = null
        var answered = false
        val early = ArrayList<IceCandidateInit>()
        var timeout: StreamScheduler.Cancellable? = null
        var disconnected: StreamScheduler.Cancellable? = null
        var playing = false
    }

    private var started = false
    private var closed = false
    private var failed = false
    private var active = true
    private var attempt: Attempt? = null
    private var played = false
    private var reconnects = 0
    private var runStartMs = 0L
    private var runReported = false
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
            val a = attempt
            if (a == null) {
                reconnects = 0
                connect()
            } else {
                a.peer?.setReceiving(true)
                if (!a.playing) armTimeout(a)
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
            { error -> scheduler.post { failAttempt(a, startFailed(error)) } },
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
        a.timeout = scheduler.postDelayed(options.connectTimeoutMs) {
            if (attempt === a && !a.playing) failAttempt(a, HaError(TIMEOUT, "No video within ${options.connectTimeoutMs} ms"))
        }
    }

    private fun onFrame(a: Attempt, width: Int, height: Int) {
        if (attempt !== a || !active) return
        if (!a.playing) {
            a.playing = true
            a.timeout?.cancel()
            a.timeout = null
            played = true
            reconnects = 0
        }
        if (!runReported) {
            runReported = true
            listener?.onFirstFrame(scheduler.nowMs() - runStartMs, width, height)
        } else {
            listener?.onFrame(width, height)
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

    private fun failAttempt(a: Attempt, error: HaError) {
        if (attempt !== a || closed || failed) return
        teardown(a)
        attempt = null
        if (played && reconnects < options.maxReconnects) {
            reconnects++
            listener?.onReconnecting(reconnects, error)
            val delay = options.reconnectDelayMs shl (reconnects - 1).coerceAtMost(MAX_BACKOFF_SHIFT)
            reconnect = scheduler.postDelayed(delay) {
                reconnect = null
                if (!closed && !failed && active && attempt == null) connect()
            }
        } else {
            failed = true
            idle?.cancel()
            idle = null
            listener?.onError(error)
        }
    }

    private fun teardown(a: Attempt) {
        a.timeout?.cancel()
        a.disconnected?.cancel()
        a.timeout = null
        a.disconnected = null
        a.early.clear()
        a.signaling?.close()
        a.peer?.close()
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
            scheduler.post { failAttempt(a, startFailed(error)) }
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
            scheduler.post { onFrame(a, width, height) }
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
