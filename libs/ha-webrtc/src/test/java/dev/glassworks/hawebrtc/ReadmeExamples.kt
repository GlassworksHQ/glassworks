package dev.glassworks.hawebrtc

import android.content.Context
import android.widget.FrameLayout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** The README's examples, compiled. Keep in sync with README.md. */
internal object ReadmeExamples {
    fun play(context: Context, channel: HaCommandChannel, container: FrameLayout): HaWebRtcPlayer {
        val player = HaWebRtcPlayer(context, channel, "camera.front_door")
        container.addView(player.view) // transparent until the first frame: put the still under it
        player.listener = object : HaWebRtcPlayer.Listener {
            override fun onFirstFrame(sinceStartMs: Long, width: Int, height: Int) {
                println("live after $sinceStartMs ms: ${width}x$height")
            }

            override fun onError(error: HaError) {
                player.close() // fall back to HLS or MJPEG, as HA's frontend does
            }
        }
        player.start()
        return player
    }

    /** A client with callbacks, as many HA WebSocket clients have. */
    interface MyHaClient {
        fun send(command: JSONObject, onAnswer: (JSONObject) -> Unit)
        fun subscribe(command: JSONObject, onEvent: (JSONObject) -> Unit, onError: (JSONObject?) -> Unit): Int
        fun unsubscribe(id: Int)
    }

    class ClientChannel(private val client: MyHaClient) : HaCommandChannel {
        override fun call(command: JSONObject, onResult: (Any?) -> Unit, onError: (HaError) -> Unit) {
            // The client must answer every command once, also when the connection drops: a lost connection answers
            // with success false and no error, which HaError.fromJson reads as HaError.NOT_CONNECTED.
            client.send(command) { answer ->
                if (answer.optBoolean("success")) onResult(answer.opt("result"))
                else onError(HaError.fromJson(answer.optJSONObject("error")))
            }
        }

        override fun subscribe(command: JSONObject, onEvent: (JSONObject) -> Unit, onError: (HaError) -> Unit): HaCommandChannel.Subscription {
            val id = client.subscribe(command, onEvent) { error -> onError(HaError.fromJson(error)) }
            return HaCommandChannel.Subscription { client.unsubscribe(id) }
        }
    }

    /** Another WebRTC stack's peer. */
    interface MyPeer {
        fun setAnswer(sdp: String)
        fun addCandidate(candidate: IceCandidateInit)
        fun close()
        fun start(config: WebRtcClientConfig, onOffer: (String) -> Unit)
    }

    fun signalingOnly(channel: HaCommandChannel, peer: MyPeer): HaWebRtcSignaling {
        val signaling = HaWebRtcSignaling(channel, "camera.front_door", object : HaWebRtcSignaling.Listener {
            override fun onAnswer(sdp: String) = peer.setAnswer(sdp)
            override fun onRemoteCandidate(candidate: IceCandidateInit) = peer.addCandidate(candidate)
            override fun onError(error: HaError) = peer.close()
        })
        signaling.fetchClientConfig({ config ->
            peer.start(config) { offer -> signaling.offer(offer) }
        }, { error -> println("no WebRTC: ${error.code}") })
        // For each candidate your peer finds:
        signaling.addLocalCandidate(IceCandidateInit("candidate:1 1 udp 2122260223 192.0.2.10 50000 typ host", "0", 0))
        return signaling
    }
}

@RunWith(RobolectricTestRunner::class)
class ReadmeExamplesTest {
    @Test
    fun `the client channel maps answers and errors`() {
        val sent = ArrayList<JSONObject>()
        var unsubscribed = -1
        val client = object : ReadmeExamples.MyHaClient {
            override fun send(command: JSONObject, onAnswer: (JSONObject) -> Unit) {
                sent += command
                onAnswer(
                    if (sent.size == 1) JSONObject().put("success", true).put("result", JSONObject().put("configuration", JSONObject()))
                    else if (sent.size == 2) JSONObject().put("success", false).put("error", JSONObject().put("code", "not_found").put("message", "Camera not found"))
                    else JSONObject().put("success", false), // the connection dropped before HA answered
                )
            }

            override fun subscribe(command: JSONObject, onEvent: (JSONObject) -> Unit, onError: (JSONObject?) -> Unit): Int {
                onEvent(JSONObject().put("type", "session").put("session_id", "01JS"))
                return 7
            }

            override fun unsubscribe(id: Int) {
                unsubscribed = id
            }
        }
        val channel = ReadmeExamples.ClientChannel(client)
        var config: WebRtcClientConfig? = null
        var error: HaError? = null
        val signaling = HaWebRtcSignaling(channel, "camera.x", object : HaWebRtcSignaling.Listener {
            override fun onAnswer(sdp: String) {}
            override fun onRemoteCandidate(candidate: IceCandidateInit) {}
            override fun onError(error: HaError) {}
        })
        signaling.fetchClientConfig({ config = it }, { error = it })
        signaling.fetchClientConfig({ config = it }, { error = it })
        assertEquals(WebRtcClientConfig(), config)
        assertEquals(HaError("not_found", "Camera not found"), error)
        signaling.fetchClientConfig({ config = it }, { error = it })
        assertEquals(HaError.NOT_CONNECTED, error!!.code)
        signaling.offer("v=0")
        assertEquals("01JS", signaling.sessionId)
        signaling.close()
        assertEquals(7, unsubscribed)
    }

    @Test
    fun `signaling only drives another stack's peer`() {
        val channel = FakeChannel()
        val log = ArrayList<String>()
        val peer = object : ReadmeExamples.MyPeer {
            override fun setAnswer(sdp: String) { log += "answer" }
            override fun addCandidate(candidate: IceCandidateInit) { log += "candidate" }
            override fun close() { log += "close" }
            override fun start(config: WebRtcClientConfig, onOffer: (String) -> Unit) { log += "start"; onOffer("v=0") }
        }
        ReadmeExamples.signalingOnly(channel, peer)
        channel.answerConfig()
        val sub = channel.subs.single()
        sub.session("01JS")
        sub.answer("v=0")
        assertEquals(listOf("start", "answer"), log)
        assertEquals("01JS", channel.callsOf(HaWebRtcSignaling.TYPE_CANDIDATE).single().command.getString("session_id"))
    }

    @Test
    fun `the player example builds a view and, without libwebrtc's native library, reports start_failed`() {
        val context = RuntimeEnvironment.getApplication()
        val container = FrameLayout(context)
        val player = ReadmeExamples.play(context, FakeChannel(), container)
        assertTrue(container.getChildAt(0) === player.view)
        player.close()
    }
}
