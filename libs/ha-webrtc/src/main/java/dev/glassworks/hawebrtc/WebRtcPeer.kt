package dev.glassworks.hawebrtc

/**
 * The receiving end of one WebRTC connection, as [HaWebRtcStream] drives it: recvonly video (and audio, if asked),
 * an offer, the camera's answer and candidates both ways. [HaWebRtcPlayer] makes them on libwebrtc; a test or another
 * WebRTC stack can bring its own.
 *
 * Calls come from the stream's thread. Callbacks and [Events] may come on any thread; the stream moves them to its
 * own. After [close], nothing is called back.
 */
public interface WebRtcPeer {
    /** Creates the offer and sets it as the local description, then gives its SDP to [onSdp], or a reason to [onError]. */
    public fun createOffer(onSdp: (sdp: String) -> Unit, onError: (reason: String) -> Unit)

    /** Sets the camera's answer as the remote description: [onDone] once set, [onError] if it can't be. */
    public fun setRemoteAnswer(sdp: String, onDone: () -> Unit, onError: (reason: String) -> Unit)

    /** Adds a candidate the camera trickled. Only called after [setRemoteAnswer] succeeded. */
    public fun addRemoteCandidate(candidate: IceCandidateInit)

    /** False stops drawing (and, where the stack can, decoding) frames; true resumes. A peer starts receiving. */
    public fun setReceiving(receiving: Boolean)

    /** Closes the connection and frees everything it holds. Idempotent. */
    public fun close()

    /** What the peer reports. */
    public interface Events {
        /** One of this side's candidates, to send to HA. */
        public fun onLocalCandidate(candidate: IceCandidateInit)

        /** The connection's state (`RTCPeerConnection.connectionState`). */
        public fun onConnectionState(state: PeerState)

        /**
         * A video frame of [width] by [height] (as shown, rotation applied) is on screen: drawn, not just decoded. A
         * frame dropped on the way (no surface to draw on) isn't reported, so a stream showing nothing times out.
         */
        public fun onFrame(width: Int, height: Int)
    }

    /** Makes peers. */
    public fun interface Factory {
        /** A peer for [config], with recvonly audio when [audio], reporting to [events]. May throw if it can't. */
        public fun create(config: WebRtcClientConfig, audio: Boolean, events: Events): WebRtcPeer
    }
}

/** `RTCPeerConnectionState`. */
public enum class PeerState {
    /** Not connecting yet. */
    NEW,

    /** ICE or DTLS is connecting. */
    CONNECTING,

    /** Media can flow. */
    CONNECTED,

    /** Connectivity was lost; it may come back. */
    DISCONNECTED,

    /** ICE or DTLS failed for good. */
    FAILED,

    /** Closed. */
    CLOSED,
}

/** Where [HaWebRtcStream] runs: one thread (or a serial queue) and its clock. */
public interface StreamScheduler {
    /** Runs [task] on the stream's thread, after what is already queued. */
    public fun post(task: Runnable)

    /** Runs [task] on the stream's thread after [delayMs]; the returned handle cancels it. */
    public fun postDelayed(delayMs: Long, task: Runnable): Cancellable

    /** A monotonic clock in milliseconds (`SystemClock.elapsedRealtime`). */
    public fun nowMs(): Long

    /** A delayed task. */
    public fun interface Cancellable {
        /** Stops the task if it hasn't run. */
        public fun cancel()
    }
}
