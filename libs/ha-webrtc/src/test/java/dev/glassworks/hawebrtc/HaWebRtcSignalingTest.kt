package dev.glassworks.hawebrtc

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The signaling against HA's camera/webrtc API as HA core 2026.11 (homeassistant/components/camera/webrtc.py) and
 * its frontend 20261028.5 (ha-web-rtc-player) speak it.
 */
class HaWebRtcSignalingTest {
    private val channel = FakeChannel()
    private val events = ArrayList<String>()
    private val listener = object : HaWebRtcSignaling.Listener {
        override fun onSession(sessionId: String) { events += "session $sessionId" }
        override fun onAnswer(sdp: String) { events += "answer $sdp" }
        override fun onRemoteCandidate(candidate: IceCandidateInit) { events += "candidate ${candidate.candidate} ${candidate.sdpMid} ${candidate.sdpMLineIndex}" }
        override fun onError(error: HaError) { events += "error ${error.code} ${error.message}" }
        override fun onCandidateError(candidate: IceCandidateInit, error: HaError) { events += "candidate-error ${error.code}" }
    }
    private val signaling = HaWebRtcSignaling(channel, "camera.front_door", listener)

    private fun local(n: Int) = IceCandidateInit("candidate:$n 1 udp 2122260223 192.0.2.10 5000$n typ host", "0", 0)

    @Test
    fun `client config asks for the camera and reads ice servers, policy and data channel`() {
        var got: WebRtcClientConfig? = null
        signaling.fetchClientConfig({ got = it }, { throw AssertionError(it.toString()) })
        val sent = channel.calls.single().command
        assertEquals("camera/webrtc/get_client_config", sent.getString("type"))
        assertEquals("camera.front_door", sent.getString("entity_id"))
        assertEquals(setOf("type", "entity_id"), sent.keys().asSequence().toSet())
        channel.calls.single().onResult(
            JSONObject("""{"configuration":{"iceServers":[{"urls":["stun:stun.example.com:3478","turn:192.0.2.1"],"username":"u","credential":"c"},{"urls":"stun:192.0.2.2"},{"username":"no-urls"}],"iceTransportPolicy":"relay"},"dataChannel":"cam"}"""),
        )
        val config = got!!
        assertEquals(2, config.iceServers.size)
        assertEquals(listOf("stun:stun.example.com:3478", "turn:192.0.2.1"), config.iceServers[0].urls)
        assertEquals("u", config.iceServers[0].username)
        assertEquals("c", config.iceServers[0].credential)
        assertEquals(listOf("stun:192.0.2.2"), config.iceServers[1].urls)
        assertNull(config.iceServers[1].username)
        assertEquals("relay", config.iceTransportPolicy)
        assertEquals("cam", config.dataChannel)
        // The credentials never show in a log line.
        assertTrue(!config.toString().contains("\"c\"") && !config.iceServers[0].toString().contains("credential=c"))
    }

    @Test
    fun `client config refused or unreadable is an error`() {
        var error: HaError? = null
        signaling.fetchClientConfig({ throw AssertionError() }, { error = it })
        channel.calls.single().onError(HaError("webrtc_get_client_config_failed", "Camera does not support WebRTC"))
        assertEquals("webrtc_get_client_config_failed", error!!.code)

        signaling.fetchClientConfig({ throw AssertionError() }, { error = it })
        channel.calls.last().onResult(JSONArray())
        assertEquals(HaError.BAD_ANSWER, error!!.code)
    }

    @Test
    fun `offer, session, answer and remote candidates arrive in order`() {
        signaling.offer("v=0 offer")
        val sub = channel.subs.single()
        assertEquals("camera/webrtc/offer", sub.command.getString("type"))
        assertEquals("camera.front_door", sub.command.getString("entity_id"))
        assertEquals("v=0 offer", sub.command.getString("offer"))

        sub.session("01JSESSION")
        sub.answer("v=0 answer")
        sub.candidate(JSONObject().put("candidate", "candidate:1 1 udp 1 192.0.2.20 6000 typ host").put("sdpMid", "0").put("sdpMLineIndex", 0))
        // go2rtc's candidates may come without a mid or index: they still reach the peer, which picks the section.
        sub.candidate(JSONObject().put("candidate", "candidate:2 1 udp 1 192.0.2.21 6001 typ host"))
        // An event of a type HA doesn't send is ignored, as the frontend ignores it.
        sub.event(JSONObject().put("type", "something_new"))

        assertEquals(
            listOf(
                "session 01JSESSION", "answer v=0 answer",
                "candidate candidate:1 1 udp 1 192.0.2.20 6000 typ host 0 0",
                "candidate candidate:2 1 udp 1 192.0.2.21 6001 typ host null null",
            ),
            events,
        )
        assertEquals("01JSESSION", signaling.sessionId)
    }

    @Test
    fun `local candidates wait for the session id, then trickle with it`() {
        signaling.offer("v=0")
        signaling.addLocalCandidate(local(1))
        signaling.addLocalCandidate(local(2))
        assertTrue("nothing goes out before the session", channel.callsOf(HaWebRtcSignaling.TYPE_CANDIDATE).isEmpty())

        channel.subs.single().session("01JS")
        signaling.addLocalCandidate(local(3))

        val sent = channel.callsOf(HaWebRtcSignaling.TYPE_CANDIDATE).map { it.command }
        assertEquals(3, sent.size)
        sent.forEachIndexed { i, c ->
            assertEquals("camera.front_door", c.getString("entity_id"))
            assertEquals("01JS", c.getString("session_id"))
            val cand = c.getJSONObject("candidate")
            assertEquals(local(i + 1).candidate, cand.getString("candidate"))
            assertEquals("0", cand.getString("sdpMid"))
            assertEquals(0, cand.getInt("sdpMLineIndex"))
            assertTrue("no usernameFragment when unknown", !cand.has("usernameFragment"))
        }
    }

    @Test
    fun `candidates found before the offer is sent wait too`() {
        signaling.addLocalCandidate(local(1))
        signaling.offer("v=0")
        channel.subs.single().session("01JS")
        assertEquals(1, channel.callsOf(HaWebRtcSignaling.TYPE_CANDIDATE).size)
    }

    @Test
    fun `the end-of-candidates marker isn't sent`() {
        signaling.offer("v=0")
        channel.subs.single().session("01JS")
        signaling.addLocalCandidate(IceCandidateInit(""))
        assertTrue(channel.callsOf(HaWebRtcSignaling.TYPE_CANDIDATE).isEmpty())
    }

    @Test
    fun `a refused candidate is reported and the negotiation goes on`() {
        signaling.offer("v=0")
        val sub = channel.subs.single()
        sub.session("01JS")
        signaling.addLocalCandidate(local(1))
        channel.callsOf(HaWebRtcSignaling.TYPE_CANDIDATE).single().onError(HaError("webrtc_candidate_failed", "x"))
        sub.answer("v=0 answer")
        assertEquals(listOf("session 01JS", "candidate-error webrtc_candidate_failed", "answer v=0 answer"), events)
        assertTrue(!sub.cancelled)
    }

    @Test
    fun `an error event fails once and closes the subscription`() {
        signaling.offer("v=0")
        val sub = channel.subs.single()
        sub.session("01JS")
        sub.error("webrtc_offer_failed", "Camera is offline")
        sub.onEvent(JSONObject().put("type", "answer").put("answer", "late"))
        sub.onEvent(JSONObject().put("type", "error").put("code", "again").put("message", ""))
        assertEquals(listOf("session 01JS", "error webrtc_offer_failed Camera is offline"), events)
        assertTrue(sub.cancelled)
    }

    @Test
    fun `HA refusing the offer is an error`() {
        signaling.offer("v=0")
        val sub = channel.subs.single()
        sub.onError(HaError("not_found", "Camera not found"))
        assertEquals(listOf("error not_found Camera not found"), events)
        assertTrue(sub.cancelled)
    }

    @Test
    fun `a channel that can't send refuses at once`() {
        channel.refuseSubscribe = HaError(HaError.NOT_CONNECTED, "Not connected")
        signaling.offer("v=0")
        assertEquals(listOf("error not_connected Not connected"), events)
        assertTrue(channel.subs.single().cancelled)
    }

    @Test
    fun `close mid-negotiation unsubscribes, drops waiting candidates and silences the rest`() {
        signaling.offer("v=0")
        val sub = channel.subs.single()
        signaling.addLocalCandidate(local(1))
        signaling.close()
        assertTrue(sub.cancelled)
        // HA's events that were already on their way.
        sub.onEvent(JSONObject().put("type", "session").put("session_id", "01JS"))
        sub.onEvent(JSONObject().put("type", "answer").put("answer", "v=0"))
        signaling.addLocalCandidate(local(2))
        signaling.close()
        assertEquals(emptyList<String>(), events)
        assertTrue(channel.callsOf(HaWebRtcSignaling.TYPE_CANDIDATE).isEmpty())
    }

    @Test
    fun `close before the offer means the offer never goes out`() {
        signaling.close()
        signaling.offer("v=0")
        assertTrue(channel.subs.isEmpty())
    }

    @Test
    fun `close while the channel is still subscribing cancels the subscription it returns`() {
        val slow = object : HaCommandChannel by channel {
            override fun subscribe(command: JSONObject, onEvent: (JSONObject) -> Unit, onError: (HaError) -> Unit): HaCommandChannel.Subscription {
                // HA's first event races the return, and the caller closes in between.
                onEvent(JSONObject().put("type", "session").put("session_id", "01JS"))
                s.close()
                return channel.subscribe(command, onEvent, onError)
            }

            lateinit var s: HaWebRtcSignaling
        }
        val s = HaWebRtcSignaling(slow, "camera.x", listener)
        slow.s = s
        s.offer("v=0")
        assertTrue(channel.subs.single().cancelled)
        assertEquals(listOf("session 01JS"), events)
    }

    @Test(expected = IllegalStateException::class)
    fun `one negotiation per instance`() {
        signaling.offer("v=0")
        signaling.offer("v=0 again")
    }

    @Test
    fun `malformed events fail the negotiation`() {
        signaling.offer("v=0")
        channel.subs.single().event(JSONObject().put("type", "answer"))
        assertEquals(listOf("error ${HaError.BAD_ANSWER} answer event without answer"), events)
    }

    @Test
    fun `a listener that closes in onSession stops the held candidates`() {
        val s = HaWebRtcSignaling(channel, "camera.x", object : HaWebRtcSignaling.Listener by listener {
            override fun onSession(sessionId: String) {
                events += "session $sessionId"
                closeRef[0]!!.close()
            }
        })
        closeRef[0] = s
        s.offer("v=0")
        s.addLocalCandidate(local(1))
        s.addLocalCandidate(local(2))
        channel.subs.single().session("01JS")
        assertEquals(listOf("session 01JS"), events)
        assertTrue(channel.callsOf(HaWebRtcSignaling.TYPE_CANDIDATE).isEmpty())
        assertTrue(channel.subs.single().cancelled)
    }

    @Test
    fun `an error HA sends while subscribe is still running is reported after the subscription is cancelled`() {
        var cancelledAtError: Boolean? = null
        val sync = object : HaCommandChannel by channel {
            override fun subscribe(command: JSONObject, onEvent: (JSONObject) -> Unit, onError: (HaError) -> Unit): HaCommandChannel.Subscription {
                val sub = channel.subscribe(command, onEvent, onError)
                // HA accepted it and failed at once, before subscribe returned.
                onEvent(JSONObject().put("type", "error").put("code", "webrtc_offer_failed").put("message", "offline"))
                return sub
            }
        }
        val s = HaWebRtcSignaling(sync, "camera.x", object : HaWebRtcSignaling.Listener by listener {
            override fun onError(error: HaError) {
                cancelledAtError = channel.subs.single().cancelled
                events += "error ${error.code}"
            }
        })
        s.offer("v=0")
        assertEquals(listOf("error webrtc_offer_failed"), events)
        assertEquals(true, cancelledAtError)
    }

    @Test
    fun `close during a deferred error means it is never reported`() {
        val sync = object : HaCommandChannel by channel {
            lateinit var s: HaWebRtcSignaling
            override fun subscribe(command: JSONObject, onEvent: (JSONObject) -> Unit, onError: (HaError) -> Unit): HaCommandChannel.Subscription {
                val sub = channel.subscribe(command, onEvent, onError)
                onEvent(JSONObject().put("type", "error").put("code", "x").put("message", ""))
                s.close()
                return sub
            }
        }
        val s = HaWebRtcSignaling(sync, "camera.x", listener)
        sync.s = s
        s.offer("v=0")
        assertEquals(emptyList<String>(), events)
        assertTrue(channel.subs.single().cancelled)
    }

    @Test
    fun `no listener call starts once close has returned on another thread`() {
        // A channel thread is inside onAnswer when another thread closes: close waits for it, and nothing starts after.
        val inListener = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val after = java.util.concurrent.atomic.AtomicInteger()
        val closed = java.util.concurrent.atomic.AtomicBoolean(false)
        val s = HaWebRtcSignaling(channel, "camera.x", object : HaWebRtcSignaling.Listener by listener {
            override fun onAnswer(sdp: String) {
                if (closed.get()) after.incrementAndGet()
                inListener.countDown()
                release.await(5, java.util.concurrent.TimeUnit.SECONDS)
            }
            override fun onRemoteCandidate(candidate: IceCandidateInit) {
                if (closed.get()) after.incrementAndGet()
            }
        })
        s.offer("v=0")
        val sub = channel.subs.single()
        val channelThread = Thread { sub.answer("v=0 answer") }
        channelThread.start()
        inListener.await(5, java.util.concurrent.TimeUnit.SECONDS)
        val closer = Thread { s.close(); closed.set(true) }
        closer.start()
        Thread.sleep(50)
        assertTrue("close waits for the listener call in progress", closer.isAlive)
        release.countDown()
        closer.join(5_000)
        assertFalse(closer.isAlive)
        assertTrue(closed.get())
        // close() has returned: an event that comes now, straight to the signaling, reaches no listener.
        sub.onEvent(JSONObject().put("type", "candidate").put("candidate", JSONObject().put("candidate", "candidate:2 1 udp 1 192.0.2.2 2 typ host")))
        channelThread.join(5_000)
        assertEquals(0, after.get())
    }

    @Test
    fun `a channel that answers inline doesn't nest listener calls`() {
        // HA refuses a candidate inline, from inside onSession (the listener sends one there).
        val inline = object : HaCommandChannel by channel {
            override fun call(command: JSONObject, onResult: (Any?) -> Unit, onError: (HaError) -> Unit) {
                onError(HaError("webrtc_candidate_failed", "no"))
            }
        }
        val s = HaWebRtcSignaling(inline, "camera.x", object : HaWebRtcSignaling.Listener by listener {
            override fun onSession(sessionId: String) {
                events += "session start"
                closeRef[0]!!.addLocalCandidate(local(1))
                events += "session end"
            }
        })
        closeRef[0] = s
        s.offer("v=0")
        channel.subs.single().session("01JS")
        assertEquals(listOf("session start", "session end", "candidate-error webrtc_candidate_failed"), events)
    }

    @Test
    fun `a nested call that comes after the listener closed is dropped`() {
        val inline = object : HaCommandChannel by channel {
            override fun call(command: JSONObject, onResult: (Any?) -> Unit, onError: (HaError) -> Unit) {
                onError(HaError("webrtc_candidate_failed", "no"))
            }
        }
        val s = HaWebRtcSignaling(inline, "camera.x", object : HaWebRtcSignaling.Listener by listener {
            override fun onSession(sessionId: String) {
                events += "session"
                closeRef[0]!!.addLocalCandidate(local(1))
                closeRef[0]!!.close()
            }
        })
        closeRef[0] = s
        s.offer("v=0")
        channel.subs.single().session("01JS")
        assertEquals(listOf("session"), events)
    }

    private val closeRef = arrayOfNulls<HaWebRtcSignaling>(1)
}
