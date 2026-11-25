package dev.glassworks.hawebrtc

import org.json.JSONObject

/**
 * One WebRTC negotiation with a Home Assistant camera, as HA's frontend (`ha-web-rtc-player`) runs it, over any
 * [HaCommandChannel]. Plain Kotlin: no Android, no libwebrtc.
 *
 * 1. [fetchClientConfig]: `camera/webrtc/get_client_config` gives the ICE servers.
 * 2. [offer]: `camera/webrtc/offer` is a subscription. HA answers it with a `session` event (the session's id), then
 *    the camera's `answer`, `candidate` events as the camera trickles them, or an `error`.
 * 3. [addLocalCandidate]: this side's candidates go to HA with `camera/webrtc/candidate`. Those found before the
 *    session's id arrives wait for it, as the frontend's do.
 * 4. [close]: ends the subscription, which closes the camera's session in HA.
 *
 * One instance is one negotiation: HA makes a new session for every offer, so a new peer connection gets a new
 * [HaWebRtcSignaling]. The methods may be called from any thread. [Listener] calls come on the channel's threads, in
 * the order HA sent them, one at a time. None starts after [close] returns, and [Listener.onError] is the last one.
 * To keep that promise a listener call and [close] (or the terminal error) never overlap: a listener may call [close]
 * or any other method itself, but must not block waiting for another thread that is calling [close].
 */
public class HaWebRtcSignaling(
    private val channel: HaCommandChannel,
    /** The camera entity (`camera.front_door`). */
    public val entityId: String,
    private val listener: Listener,
) {
    /** What HA sends back on the offer's subscription. */
    public interface Listener {
        /** HA's session id for this negotiation (the `session` event). */
        public fun onSession(sessionId: String) {}

        /** The camera's SDP answer (the `answer` event). */
        public fun onAnswer(sdp: String)

        /** A candidate the camera trickled (a `candidate` event). */
        public fun onRemoteCandidate(candidate: IceCandidateInit)

        /**
         * The negotiation failed: HA refused the offer (`webrtc_offer_failed`, `not_found`, …), sent an `error`
         * event, or the connection went. The subscription is already closed. Called once, and last.
         */
        public fun onError(error: HaError)

        /** HA refused one of this side's candidates. The negotiation goes on, as the frontend's does. */
        public fun onCandidateError(candidate: IceCandidateInit, error: HaError) {}
    }

    /** The negotiation's state. */
    private val lock = Any()

    /** Held while a listener runs, and by [close] and the terminal error, so neither overlaps a listener call. */
    private val dispatch = Any()
    private var offered = false
    private var closed = false
    private var failed = false
    private var subscription: HaCommandChannel.Subscription? = null
    private var session: String? = null
    private val waiting = ArrayList<IceCandidateInit>()

    /**
     * Listener calls asked for while one runs on this thread (a channel that answers inline, say a candidate refused
     * from inside onSession): they run after it returns, one at a time. Only the thread holding [dispatch] uses them.
     */
    private val nested = ArrayDeque<() -> Unit>()
    private var inListener = false

    /** True while [HaCommandChannel.subscribe] runs: an error then waits for its handle to be cancelled first. */
    private var subscribing = false
    private var deferredError: HaError? = null

    /** HA's session id, once its `session` event came. */
    public val sessionId: String? get() = synchronized(lock) { session }

    /**
     * Asks HA for the camera's client configuration (`camera/webrtc/get_client_config`). Exactly one of [onConfig] or
     * [onError] is called, on the channel's thread, even after [close].
     */
    public fun fetchClientConfig(onConfig: (WebRtcClientConfig) -> Unit, onError: (HaError) -> Unit) {
        val command = JSONObject().put("type", TYPE_CLIENT_CONFIG).put("entity_id", entityId)
        channel.call(command, { result ->
            if (result is JSONObject) onConfig(WebRtcClientConfig.fromJson(result))
            else onError(HaError(HaError.BAD_ANSWER, "$TYPE_CLIENT_CONFIG answered without a configuration"))
        }, onError)
    }

    /**
     * Sends this side's SDP offer (`camera/webrtc/offer`) and listens for HA's answer. Once per instance: a second
     * call throws. After [close] it does nothing.
     */
    public fun offer(sdp: String) {
        synchronized(lock) {
            check(!offered) { "offer() was already called: a new negotiation needs a new HaWebRtcSignaling" }
            offered = true
            if (closed || failed) return
            subscribing = true
        }
        val command = JSONObject().put("type", TYPE_OFFER).put("entity_id", entityId).put("offer", sdp)
        val sub = channel.subscribe(command, ::onEvent) { error -> fail(error) }
        // HA may have answered (even with an error), or close() may have run, before subscribe returned: then the
        // subscription goes now, and an error that came meanwhile is reported after it.
        var report: HaError? = null
        val cancel = synchronized(lock) {
            subscribing = false
            report = deferredError
            deferredError = null
            if (closed || failed) true else { subscription = sub; false }
        }
        if (cancel) sub.unsubscribe()
        report?.let { error -> dispatchCall { if (!isClosed()) listener.onError(error) } }
    }

    /**
     * Sends one of this side's candidates to HA (`camera/webrtc/candidate`), or keeps it until HA's session id comes.
     * An empty [IceCandidateInit.candidate] (the end of candidates) isn't sent: HA's frontend doesn't send one.
     */
    public fun addLocalCandidate(candidate: IceCandidateInit) {
        if (candidate.candidate.isEmpty()) return
        val sid = synchronized(lock) {
            if (closed || failed) return
            session ?: run { waiting += candidate; return }
        }
        send(sid, candidate)
    }

    /**
     * Ends the negotiation: closes the subscription (and so HA's session), drops waiting candidates. Idempotent. No
     * listener call starts after it returns.
     */
    public fun close() {
        val sub = synchronized(dispatch) {
            synchronized(lock) {
                deferredError = null
                if (closed) return
                closed = true
                waiting.clear()
                subscription.also { subscription = null }
            }
        }
        sub?.unsubscribe()
    }

    private fun onEvent(event: JSONObject) {
        when (event.optString("type")) {
            "session" -> {
                val sid = event.optStringOrNull("session_id")
                    ?: return fail(HaError(HaError.BAD_ANSWER, "session event without session_id"))
                val flush = synchronized(lock) {
                    if (closed || failed || session != null) return
                    session = sid
                    ArrayList(waiting).also { waiting.clear() }
                }
                deliver { listener.onSession(sid) }
                // The listener may have closed the negotiation: then the held candidates stay unsent.
                for (c in flush) {
                    if (!live()) return
                    send(sid, c)
                }
            }
            "answer" -> {
                val sdp = event.optStringOrNull("answer") ?: return fail(HaError(HaError.BAD_ANSWER, "answer event without answer"))
                deliver { listener.onAnswer(sdp) }
            }
            "candidate" -> {
                val c = IceCandidateInit.fromJson(event.optJSONObject("candidate")) ?: return
                deliver { listener.onRemoteCandidate(c) }
            }
            "error" -> fail(HaError.fromJson(event, fallbackCode = TYPE_ERROR_FALLBACK))
        }
    }

    private fun send(sid: String, candidate: IceCandidateInit) {
        val command = JSONObject().put("type", TYPE_CANDIDATE).put("entity_id", entityId).put("session_id", sid)
            .put("candidate", candidate.toJson())
        channel.call(command, {}) { error -> deliver { listener.onCandidateError(candidate, error) } }
    }

    private fun live(): Boolean = synchronized(lock) { !closed && !failed }

    private fun isClosed(): Boolean = synchronized(lock) { closed }

    /** Calls the listener unless the negotiation is over (checked when the call runs). */
    private inline fun deliver(crossinline call: () -> Unit) = dispatchCall { if (live()) call() }

    /**
     * Runs [call] (a listener call) holding [dispatch], so it never overlaps [close], the terminal error or another
     * listener call. One asked for from inside a listener call on this thread waits until that one returns.
     */
    private fun dispatchCall(call: () -> Unit) {
        synchronized(dispatch) {
            if (inListener) {
                nested.addLast(call)
                return
            }
            inListener = true
            try {
                call()
                while (true) (nested.removeFirstOrNull() ?: break)()
            } finally {
                inListener = false
                nested.clear()
            }
        }
    }

    private fun fail(error: HaError) {
        val sub = synchronized(dispatch) {
            synchronized(lock) {
                if (closed || failed) return
                failed = true
                waiting.clear()
                // Still inside subscribe(): offer() cancels the handle it returns, then reports this.
                if (subscribing) {
                    deferredError = error
                    return
                }
                subscription.also { subscription = null }
            }
        }
        // The subscription goes before the report; a close() in between means no report.
        sub?.unsubscribe()
        dispatchCall { if (!isClosed()) listener.onError(error) }
    }

    /** HA's WebSocket command types. */
    public companion object {
        /** HA's command for the client configuration. */
        public const val TYPE_CLIENT_CONFIG: String = "camera/webrtc/get_client_config"

        /** HA's offer subscription. */
        public const val TYPE_OFFER: String = "camera/webrtc/offer"

        /** HA's command for this side's candidates. */
        public const val TYPE_CANDIDATE: String = "camera/webrtc/candidate"

        /** HA's code for a failed offer, also given to an `error` event that has no code. */
        private const val TYPE_ERROR_FALLBACK = "webrtc_offer_failed"
    }
}
