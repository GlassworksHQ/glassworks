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
 * the order HA sent them, and stop at [close] or after the first [Listener.onError].
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

    private val lock = Any()
    private var offered = false
    private var done = false // closed, or failed
    private var subscription: HaCommandChannel.Subscription? = null
    private var session: String? = null
    private val waiting = ArrayList<IceCandidateInit>()

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
            if (done) return
        }
        val command = JSONObject().put("type", TYPE_OFFER).put("entity_id", entityId).put("offer", sdp)
        val sub = channel.subscribe(command, ::onEvent) { error -> fail(error) }
        // HA's answer may already have come, or close() may have run meanwhile: then the subscription goes now.
        val cancel = synchronized(lock) {
            if (done) true else { subscription = sub; false }
        }
        if (cancel) sub.unsubscribe()
    }

    /**
     * Sends one of this side's candidates to HA (`camera/webrtc/candidate`), or keeps it until HA's session id comes.
     * An empty [IceCandidateInit.candidate] (the end of candidates) isn't sent: HA's frontend doesn't send one.
     */
    public fun addLocalCandidate(candidate: IceCandidateInit) {
        if (candidate.candidate.isEmpty()) return
        val sid = synchronized(lock) {
            if (done) return
            session ?: run { waiting += candidate; return }
        }
        send(sid, candidate)
    }

    /** Ends the negotiation: closes the subscription (and so HA's session), drops waiting candidates. Idempotent. */
    public fun close() {
        val sub = synchronized(lock) {
            if (done) return
            done = true
            waiting.clear()
            subscription.also { subscription = null }
        }
        sub?.unsubscribe()
    }

    private fun onEvent(event: JSONObject) {
        when (event.optString("type")) {
            "session" -> {
                val sid = event.optStringOrNull("session_id")
                    ?: return fail(HaError(HaError.BAD_ANSWER, "session event without session_id"))
                val flush = synchronized(lock) {
                    if (done || session != null) return
                    session = sid
                    ArrayList(waiting).also { waiting.clear() }
                }
                listener.onSession(sid)
                flush.forEach { send(sid, it) }
            }
            "answer" -> {
                val sdp = event.optStringOrNull("answer") ?: return fail(HaError(HaError.BAD_ANSWER, "answer event without answer"))
                if (live()) listener.onAnswer(sdp)
            }
            "candidate" -> {
                val c = IceCandidateInit.fromJson(event.optJSONObject("candidate")) ?: return
                if (live()) listener.onRemoteCandidate(c)
            }
            "error" -> fail(HaError.fromJson(event, fallbackCode = TYPE_ERROR_FALLBACK))
        }
    }

    private fun send(sid: String, candidate: IceCandidateInit) {
        val command = JSONObject().put("type", TYPE_CANDIDATE).put("entity_id", entityId).put("session_id", sid)
            .put("candidate", candidate.toJson())
        channel.call(command, {}) { error -> if (live()) listener.onCandidateError(candidate, error) }
    }

    private fun live(): Boolean = synchronized(lock) { !done }

    private fun fail(error: HaError) {
        val sub = synchronized(lock) {
            if (done) return
            done = true
            waiting.clear()
            subscription.also { subscription = null }
        }
        sub?.unsubscribe()
        listener.onError(error)
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
