package dev.glassworks.hawebrtc

import org.json.JSONObject

/**
 * Home Assistant's side of the WebSocket, as the camera/webrtc handlers in HA core 2026.11
 * (homeassistant/components/camera/webrtc.py) and tools/perf/fakeha.py answer it: the test drives it by hand.
 */
internal class FakeChannel : HaCommandChannel {
    class Call(val command: JSONObject, val onResult: (Any?) -> Unit, val onError: (HaError) -> Unit)

    class Sub(val command: JSONObject, val onEvent: (JSONObject) -> Unit, val onError: (HaError) -> Unit) {
        var cancelled = false

        /** An event on the subscription, as HA sends it; dropped once cancelled, as a channel does. */
        fun event(e: JSONObject) {
            if (!cancelled) onEvent(e)
        }

        fun session(id: String) = event(JSONObject().put("type", "session").put("session_id", id))
        fun answer(sdp: String) = event(JSONObject().put("type", "answer").put("answer", sdp))
        fun candidate(c: JSONObject) = event(JSONObject().put("type", "candidate").put("candidate", c))
        fun error(code: String, message: String) = event(JSONObject().put("type", "error").put("code", code).put("message", message))
    }

    val calls = ArrayList<Call>()
    val subs = ArrayList<Sub>()

    /** When set, subscribe() refuses at once, as a channel without a connection does. */
    var refuseSubscribe: HaError? = null

    override fun call(command: JSONObject, onResult: (Any?) -> Unit, onError: (HaError) -> Unit) {
        calls += Call(command, onResult, onError)
    }

    override fun subscribe(command: JSONObject, onEvent: (JSONObject) -> Unit, onError: (HaError) -> Unit): HaCommandChannel.Subscription {
        val sub = Sub(command, onEvent, onError)
        subs += sub
        refuseSubscribe?.let(onError)
        return HaCommandChannel.Subscription { sub.cancelled = true }
    }

    fun callsOf(type: String) = calls.filter { it.command.optString("type") == type }

    /** Answers the last get_client_config with [config]. */
    fun answerConfig(config: JSONObject = CONFIG) = callsOf(HaWebRtcSignaling.TYPE_CLIENT_CONFIG).last().onResult(config)

    companion object {
        /** What tools/perf/webrtc.py's client_config gives behind its TURN relay (made-up credentials). */
        val CONFIG: JSONObject = JSONObject(
            """{"configuration":{"iceServers":[{"urls":"turn:localhost:3479?transport=tcp","username":"u","credential":"p"}],"iceTransportPolicy":"relay"}}""",
        )
    }
}

/** A [StreamScheduler] the test runs by hand: queued tasks run on [runAll], delayed ones on [advance]. */
internal class ManualScheduler : StreamScheduler {
    private class Timed(val at: Long, val seq: Int, val task: Runnable) {
        var cancelled = false
    }

    private val queue = ArrayDeque<Runnable>()
    private val timed = ArrayList<Timed>()
    private var seq = 0
    var now = 1_000L

    override fun post(task: Runnable) {
        queue += task
    }

    override fun postDelayed(delayMs: Long, task: Runnable): StreamScheduler.Cancellable {
        val t = Timed(now + delayMs, seq++, task)
        timed += t
        return StreamScheduler.Cancellable { t.cancelled = true }
    }

    override fun nowMs(): Long = now

    fun runAll() {
        while (queue.isNotEmpty()) queue.removeFirst().run()
    }

    /** Moves the clock on by [ms], running what falls due (and what that queues) in order. */
    fun advance(ms: Long) {
        val end = now + ms
        runAll()
        while (true) {
            val next = timed.filter { !it.cancelled && it.at <= end }.minWithOrNull(compareBy<Timed>({ it.at }, { it.seq })) ?: break
            timed -= next
            now = next.at
            next.task.run()
            runAll()
        }
        now = end
    }

    fun pendingTimers() = timed.count { !it.cancelled }
}

/** A [WebRtcPeer] the test drives: it records what the stream asks and reports what the test says. */
internal class FakePeer(val config: WebRtcClientConfig, val audio: Boolean, val events: WebRtcPeer.Events) : WebRtcPeer {
    var offerCallbacks: Pair<(String) -> Unit, (String) -> Unit>? = null
    var answer: String? = null
    var answerCallbacks: Pair<() -> Unit, (String) -> Unit>? = null
    val remote = ArrayList<IceCandidateInit>()
    var drawing = true
    var closed = false

    override fun createOffer(onSdp: (String) -> Unit, onError: (String) -> Unit) {
        offerCallbacks = onSdp to onError
    }

    override fun setRemoteAnswer(sdp: String, onDone: () -> Unit, onError: (String) -> Unit) {
        answer = sdp
        answerCallbacks = onDone to onError
    }

    override fun addRemoteCandidate(candidate: IceCandidateInit) {
        check(answer != null) { "a remote candidate before the answer" }
        remote += candidate
    }

    override fun setReceiving(receiving: Boolean) {
        drawing = receiving
    }

    override fun close() {
        closed = true
    }

    fun offer(sdp: String = OFFER) = offerCallbacks!!.first(sdp)
    fun answerSet() = answerCallbacks!!.first()

    companion object {
        const val OFFER = "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=recvonly\r\n"
    }
}

/** Every peer a stream made, in order. */
internal class FakePeers : WebRtcPeer.Factory {
    val made = ArrayList<FakePeer>()
    var fail: Exception? = null

    override fun create(config: WebRtcClientConfig, audio: Boolean, events: WebRtcPeer.Events): WebRtcPeer {
        fail?.let { throw it }
        return FakePeer(config, audio, events).also { made += it }
    }

    val last get() = made.last()
}
