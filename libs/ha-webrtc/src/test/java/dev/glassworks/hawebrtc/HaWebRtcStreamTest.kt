package dev.glassworks.hawebrtc

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The stream around the signaling: one connection as HA's frontend makes it, and the timeout, reconnects and pausing. */
class HaWebRtcStreamTest {
    private val channel = FakeChannel()
    private val peers = FakePeers()
    private val clock = ManualScheduler()
    private val log = ArrayList<String>()

    private fun stream(options: HaWebRtcStream.Options = HaWebRtcStream.Options()) =
        HaWebRtcStream(channel, "camera.front_door", peers, clock, options).also {
            it.listener = object : HaWebRtcStream.Listener {
                override fun onFirstFrame(sinceStartMs: Long, width: Int, height: Int) { log += "first $sinceStartMs ${width}x$height" }
                override fun onFrame(width: Int, height: Int) { log += "frame" }
                override fun onReconnecting(attempt: Int, error: HaError) { log += "reconnecting $attempt ${error.code}" }
                override fun onSession(sessionId: String) { log += "session $sessionId" }
                override fun onError(error: HaError) { log += "error ${error.code}" }
            }
        }

    /** Runs one connection up to the answer being set; returns its peer and subscription. */
    private fun connect(s: HaWebRtcStream? = null, session: String = "01JS"): Pair<FakePeer, FakeChannel.Sub> {
        s?.start()
        clock.runAll()
        channel.answerConfig()
        clock.runAll()
        val peer = peers.last
        peer.offer()
        clock.runAll()
        val sub = channel.subs.last()
        sub.session(session)
        sub.answer("v=0 answer")
        clock.runAll()
        peer.answerSet()
        clock.runAll()
        return peer to sub
    }

    @Test
    fun `one connection as the frontend makes it`() {
        val s = stream()
        s.start()
        clock.runAll()
        assertEquals(1, channel.callsOf(HaWebRtcSignaling.TYPE_CLIENT_CONFIG).size)
        channel.answerConfig()
        clock.runAll()

        val peer = peers.last
        assertEquals("relay", peer.config.iceTransportPolicy)
        assertEquals(listOf("turn:localhost:3479?transport=tcp"), peer.config.iceServers.single().urls)
        assertFalse("muted by default: no audio transceiver", peer.audio)

        // A candidate found before the offer is out waits for HA's session id.
        peer.events.onLocalCandidate(IceCandidateInit("candidate:1 1 udp 1 192.0.2.10 5000 typ host", "0", 0))
        peer.offer()
        clock.runAll()
        val sub = channel.subs.single()
        assertEquals(FakePeer.OFFER, sub.command.getString("offer"))

        // HA's events: session, a candidate before the answer (kept until the answer is set), the answer.
        sub.session("01JS")
        sub.candidate(JSONObject().put("candidate", "candidate:9 1 udp 1 192.0.2.20 6000 typ host").put("sdpMid", "0"))
        sub.answer("v=0 answer")
        clock.runAll()
        assertEquals("v=0 answer", peer.answer)
        assertTrue(peer.remote.isEmpty())
        peer.answerSet()
        clock.runAll()
        assertEquals("candidate:9 1 udp 1 192.0.2.20 6000 typ host", peer.remote.single().candidate)
        sub.candidate(JSONObject().put("candidate", "candidate:10 1 udp 1 192.0.2.21 6001 typ host").put("sdpMLineIndex", 0))
        clock.runAll()
        assertEquals(2, peer.remote.size)

        val sent = channel.callsOf(HaWebRtcSignaling.TYPE_CANDIDATE).single().command
        assertEquals("01JS", sent.getString("session_id"))

        peer.events.onConnectionState(PeerState.CONNECTED)
        clock.now += 640
        peer.events.onFrame(1280, 720)
        peer.events.onFrame(1280, 720)
        clock.runAll()
        assertEquals(listOf("session 01JS", "first 640 1280x720", "frame"), log)
        // Playing: the timeout is gone.
        clock.advance(60_000)
        assertEquals(3, log.size)
    }

    @Test
    fun `audio when asked`() {
        stream(HaWebRtcStream.Options(audio = true)).start()
        clock.runAll()
        channel.answerConfig()
        clock.runAll()
        assertTrue(peers.last.audio)
    }

    @Test
    fun `HA refusing WebRTC fails at once without retrying`() {
        val s = stream()
        s.start()
        clock.runAll()
        channel.callsOf(HaWebRtcSignaling.TYPE_CLIENT_CONFIG).single()
            .onError(HaError("webrtc_get_client_config_failed", "Camera does not support WebRTC"))
        clock.runAll()
        assertEquals(listOf("error start_failed"), log)
        assertTrue(peers.made.isEmpty())
        clock.advance(120_000)
        assertEquals(1, log.size)
    }

    @Test
    fun `an error event before the first frame fails at once and frees the peer`() {
        val s = stream()
        s.start()
        clock.runAll()
        channel.answerConfig()
        clock.runAll()
        peers.last.offer()
        clock.runAll()
        channel.subs.single().error("webrtc_offer_failed", "go2rtc: camera unreachable")
        clock.runAll()
        assertEquals(listOf("error start_failed"), log)
        assertTrue(peers.last.closed)
        assertTrue(channel.subs.single().cancelled)
    }

    @Test
    fun `an answer the peer can't take is connect_failed`() {
        val s = stream()
        s.start()
        clock.runAll()
        channel.answerConfig()
        clock.runAll()
        val peer = peers.last
        peer.offer()
        clock.runAll()
        channel.subs.single().answer("not sdp")
        clock.runAll()
        peer.answerCallbacks!!.second("SessionDescription is invalid")
        clock.runAll()
        assertEquals(listOf("error connect_failed"), log)
        assertTrue(peer.closed)
    }

    @Test
    fun `a peer that can't be made is start_failed`() {
        peers.fail = IllegalStateException("no codec")
        stream().start()
        clock.runAll()
        channel.answerConfig()
        clock.runAll()
        assertEquals(listOf("error start_failed"), log)
    }

    @Test
    fun `no video in time is a timeout`() {
        val s = stream(HaWebRtcStream.Options(connectTimeoutMs = 10_000))
        val (peer, sub) = connect(s)
        clock.advance(9_999)
        assertTrue(log.none { it.startsWith("error") })
        clock.advance(1)
        assertEquals("error timeout", log.last())
        assertTrue(peer.closed)
        assertTrue(sub.cancelled)
    }

    @Test
    fun `close mid-negotiation frees everything and reports nothing`() {
        val s = stream()
        s.start()
        clock.runAll()
        channel.answerConfig()
        clock.runAll()
        val peer = peers.last
        peer.offer()
        clock.runAll()
        val sub = channel.subs.single()
        s.close()
        assertTrue(peer.closed)
        assertTrue(sub.cancelled)
        // What was already on its way.
        sub.onEvent(JSONObject().put("type", "answer").put("answer", "v=0"))
        peer.events.onFrame(640, 480)
        peer.events.onConnectionState(PeerState.FAILED)
        clock.advance(120_000)
        assertNull(peer.answer)
        assertEquals(emptyList<String>(), log)
        assertEquals(0, clock.pendingTimers())
    }

    @Test
    fun `a channel whose unsubscribe throws still leaves the peer closed`() {
        val throwing = object : HaCommandChannel by channel {
            override fun subscribe(command: JSONObject, onEvent: (JSONObject) -> Unit, onError: (HaError) -> Unit): HaCommandChannel.Subscription {
                channel.subscribe(command, onEvent, onError)
                return HaCommandChannel.Subscription { throw IllegalStateException("socket gone") }
            }
        }
        val s = HaWebRtcStream(throwing, "camera.x", peers, clock, HaWebRtcStream.Options())
        s.start()
        clock.runAll()
        channel.answerConfig()
        clock.runAll()
        val peer = peers.last
        peer.offer()
        clock.runAll()
        s.close()
        assertTrue(peer.closed)
        assertEquals(0, clock.pendingTimers())
    }

    @Test
    fun `close before the config answers never makes a peer`() {
        val s = stream()
        s.start()
        clock.runAll()
        s.close()
        channel.answerConfig()
        clock.runAll()
        assertTrue(peers.made.isEmpty())
        assertTrue(log.isEmpty())
    }

    @Test
    fun `a stream that played reconnects when ICE fails, with a new peer and session`() {
        val s = stream(HaWebRtcStream.Options(reconnectDelayMs = 1_000))
        val (peer1, sub1) = connect(s)
        peer1.events.onFrame(1280, 720)
        peer1.events.onConnectionState(PeerState.FAILED)
        clock.runAll()
        assertTrue(peer1.closed)
        assertTrue(sub1.cancelled)
        assertEquals("reconnecting 1 ice_failed", log.last())

        clock.advance(999)
        assertEquals(1, channel.callsOf(HaWebRtcSignaling.TYPE_CLIENT_CONFIG).size)
        clock.advance(1)
        assertEquals(2, channel.callsOf(HaWebRtcSignaling.TYPE_CLIENT_CONFIG).size)
        val (peer2, _) = connect(session = "01JS2")
        assertTrue(peer2 !== peer1)
        peer2.events.onFrame(1280, 720)
        clock.runAll()
        // The same run: the picture comes back as a frame, not a new first frame.
        assertEquals(listOf("session 01JS", "first 0 1280x720", "reconnecting 1 ice_failed", "session 01JS2", "frame"), log)
    }

    @Test
    fun `disconnected that doesn't come back counts as failed, one that does is fine`() {
        val s = stream(HaWebRtcStream.Options(disconnectedGraceMs = 5_000))
        val (peer, _) = connect(s)
        peer.events.onFrame(640, 480)
        peer.events.onConnectionState(PeerState.DISCONNECTED)
        clock.advance(4_000)
        peer.events.onConnectionState(PeerState.CONNECTED)
        clock.advance(10_000)
        assertFalse(peer.closed)

        peer.events.onConnectionState(PeerState.DISCONNECTED)
        clock.advance(5_000)
        assertTrue(peer.closed)
        assertEquals("reconnecting 1 ice_failed", log.last())
    }

    @Test
    fun `reconnecting gives up after the limit, backing off`() {
        val s = stream(HaWebRtcStream.Options(maxReconnects = 2, reconnectDelayMs = 1_000, connectTimeoutMs = 10_000))
        val (peer, _) = connect(s)
        peer.events.onFrame(640, 480)
        peer.events.onConnectionState(PeerState.FAILED)
        clock.runAll()
        // Attempt 1 after 1 s, its config never answers: it times out.
        clock.advance(1_000)
        clock.advance(10_000)
        assertEquals("reconnecting 2 timeout", log.last())
        // Attempt 2 after 2 s.
        clock.advance(1_999)
        assertEquals(2, channel.callsOf(HaWebRtcSignaling.TYPE_CLIENT_CONFIG).size)
        clock.advance(1)
        assertEquals(3, channel.callsOf(HaWebRtcSignaling.TYPE_CLIENT_CONFIG).size)
        clock.advance(10_000)
        assertEquals("error timeout", log.last())
        clock.advance(600_000)
        assertEquals(3, channel.callsOf(HaWebRtcSignaling.TYPE_CLIENT_CONFIG).size)
    }

    @Test
    fun `while HA's connection is down a played stream keeps trying, and goes at once when it's back`() {
        val s = stream(HaWebRtcStream.Options(maxReconnects = 3, reconnectDelayMs = 1_000))
        val (peer, _) = connect(s)
        peer.events.onFrame(1280, 720)
        // HA restarts: its camera peer goes, then every request fails for want of a connection.
        peer.events.onConnectionState(PeerState.FAILED)
        clock.runAll()
        val down = HaError(HaError.NOT_CONNECTED, "Not connected")
        var waited = 1_000L
        repeat(8) {
            clock.advance(waited)
            channel.callsOf(HaWebRtcSignaling.TYPE_CLIENT_CONFIG).last().onError(down)
            clock.runAll()
            waited = minOf(waited * 2, 32_000)
        }
        // Far past maxReconnects, and still trying: those failures were the connection's, not the camera's.
        assertTrue(log.none { it.startsWith("error") })
        assertEquals("reconnecting 9 start_failed", log.last())
        assertEquals("backed off to the cap", 9, channel.callsOf(HaWebRtcSignaling.TYPE_CLIENT_CONFIG).size)
        clock.advance(31_999)
        assertEquals(9, channel.callsOf(HaWebRtcSignaling.TYPE_CLIENT_CONFIG).size)

        // HA is back: the stream connects now, not at the end of its wait.
        s.connectionRestored()
        clock.runAll()
        assertEquals(10, channel.callsOf(HaWebRtcSignaling.TYPE_CLIENT_CONFIG).size)
        val (peer2, _) = connect(session = "01JS2")
        peer2.events.onFrame(1280, 720)
        clock.runAll()
        assertEquals("frame", log.last())
        // The timer it cancelled doesn't make another connection.
        clock.advance(600_000)
        assertEquals(10, channel.callsOf(HaWebRtcSignaling.TYPE_CLIENT_CONFIG).size)
        assertFalse(peer2.closed)
    }

    @Test
    fun `an offer refused for want of a connection doesn't count either`() {
        val s = stream(HaWebRtcStream.Options(maxReconnects = 1, reconnectDelayMs = 100))
        val (peer, _) = connect(s)
        peer.events.onFrame(640, 480)
        peer.events.onConnectionState(PeerState.FAILED)
        clock.runAll()
        channel.refuseSubscribe = HaError(HaError.NOT_CONNECTED, "")
        repeat(4) {
            clock.advance(10_000)
            channel.answerConfig()
            clock.runAll()
            peers.last.offer()
            clock.runAll()
        }
        assertTrue(log.none { it.startsWith("error") })
        // A real failure still counts: with the one reconnect used by ICE, HA refusing the offer ends it.
        channel.refuseSubscribe = null
        clock.advance(10_000)
        channel.answerConfig()
        clock.runAll()
        peers.last.offer()
        clock.runAll()
        channel.subs.last().error("webrtc_offer_failed", "Camera offline")
        clock.runAll()
        assertEquals("error start_failed", log.last())
    }

    @Test
    fun `a stream that never played still fails at once without a connection`() {
        val s = stream()
        s.start()
        clock.runAll()
        channel.callsOf(HaWebRtcSignaling.TYPE_CLIENT_CONFIG).last().onError(HaError(HaError.NOT_CONNECTED, ""))
        clock.runAll()
        assertEquals("error start_failed", log.last())
        // Over: a restored connection doesn't bring it back (its owner makes a new one).
        s.connectionRestored()
        clock.runAll()
        assertEquals(1, channel.callsOf(HaWebRtcSignaling.TYPE_CLIENT_CONFIG).size)
    }

    @Test
    fun `a restored connection restarts a connection being set up, and leaves a playing one alone`() {
        val s = stream(HaWebRtcStream.Options(reconnectDelayMs = 1_000))
        val (peer, sub) = connect(s)
        peer.events.onFrame(640, 480)
        clock.runAll()
        s.connectionRestored()
        clock.runAll()
        assertFalse(peer.closed)
        assertFalse(sub.cancelled)
        assertEquals(1, channel.callsOf(HaWebRtcSignaling.TYPE_CLIENT_CONFIG).size)

        // Reconnecting, its offer out on the connection that then dropped: no answer will come on it.
        peer.events.onConnectionState(PeerState.FAILED)
        clock.advance(1_000)
        channel.answerConfig()
        clock.runAll()
        peers.last.offer()
        clock.runAll()
        val stale = channel.subs.last()
        s.connectionRestored()
        clock.runAll()
        assertTrue(stale.cancelled)
        assertTrue(peers.last.closed)
        assertEquals(3, channel.callsOf(HaWebRtcSignaling.TYPE_CLIENT_CONFIG).size)

        // Paused: nothing to restore (resuming connects).
        s.setActive(false)
        s.connectionRestored()
        clock.runAll()
        assertEquals(3, channel.callsOf(HaWebRtcSignaling.TYPE_CLIENT_CONFIG).size)
    }

    @Test
    fun `a frame resets the reconnect count`() {
        val s = stream(HaWebRtcStream.Options(maxReconnects = 1, reconnectDelayMs = 100))
        var (peer, _) = connect(s)
        repeat(3) {
            peer.events.onFrame(640, 480)
            peer.events.onConnectionState(PeerState.FAILED)
            clock.advance(100)
            peer = connect().first
        }
        assertEquals(3, log.count { it == "reconnecting 1 ice_failed" })
        assertTrue(log.none { it.startsWith("error") })
    }

    @Test
    fun `pausing stops frames at once and the connection after the idle wait`() {
        val s = stream(HaWebRtcStream.Options(idleTeardownMs = 60_000))
        val (peer, sub) = connect(s)
        peer.events.onFrame(640, 480)
        clock.runAll()
        s.setActive(false)
        assertFalse(peer.drawing)
        peer.events.onFrame(640, 480)
        clock.advance(59_999)
        assertFalse(peer.closed)
        assertEquals(1, log.count { it.startsWith("first") || it == "frame" })
        clock.advance(1)
        assertTrue(peer.closed)
        assertTrue(sub.cancelled)

        // Resuming makes a new connection, and its first frame is a new first frame.
        clock.now += 1_000
        s.setActive(true)
        clock.runAll()
        val (peer2, _) = connect(session = "01JS2")
        clock.now += 300
        peer2.events.onFrame(640, 480)
        clock.runAll()
        assertEquals("first 300 640x480", log.last())
    }

    @Test
    fun `resuming within the idle wait keeps the connection`() {
        val s = stream(HaWebRtcStream.Options(idleTeardownMs = 60_000))
        val (peer, _) = connect(s)
        peer.events.onFrame(640, 480)
        clock.runAll()
        s.setActive(false)
        clock.advance(30_000)
        s.setActive(true)
        assertTrue(peer.drawing)
        clock.advance(1_000)
        peer.events.onFrame(640, 480)
        clock.advance(60_000)
        assertFalse(peer.closed)
        assertEquals(2, log.count { it.startsWith("first") })
        assertEquals("first 1000 640x480", log.last())
    }

    @Test
    fun `a kept connection that shows nothing after resume times out and reconnects`() {
        val s = stream(HaWebRtcStream.Options(idleTeardownMs = 60_000, connectTimeoutMs = 10_000))
        val (peer, _) = connect(s)
        peer.events.onFrame(640, 480)
        clock.runAll()
        s.setActive(false)
        clock.advance(30_000)
        // Still connected, but its video stopped while paused.
        s.setActive(true)
        clock.advance(9_999)
        assertFalse(peer.closed)
        clock.advance(1)
        assertTrue(peer.closed)
        assertEquals("reconnecting 1 timeout", log.last())
    }

    @Test
    fun `a frame from before the pause doesn't count for the run after it`() {
        val s = stream(HaWebRtcStream.Options(idleTeardownMs = 60_000, connectTimeoutMs = 10_000))
        val (peer, _) = connect(s)
        peer.events.onFrame(640, 480)
        clock.runAll()
        // A frame the peer reported just before the pause, still queued when the panel pauses and resumes.
        peer.events.onFrame(640, 480)
        s.setActive(false)
        s.setActive(true)
        clock.runAll()
        assertEquals("not a first frame of the new run", 1, log.count { it.startsWith("first") })
        // The camera sends nothing more: the resumed run times out.
        clock.advance(10_000)
        assertTrue(peer.closed)
        assertEquals("reconnecting 1 timeout", log.last())
    }

    @Test
    fun `a playing run's later frames go to the player's steady path, never past a pause, a resume or a new connection`() {
        val s = stream(HaWebRtcStream.Options(idleTeardownMs = 60_000, connectTimeoutMs = 10_000))
        val steady = ArrayList<String>()
        var take = true
        s.steadyFrames = HaWebRtcStream.SteadyFrames { w, h -> steady += "${w}x$h"; take }
        val (peer, _) = connect(s)
        // The run's first frame goes through the stream's thread, as before; it isn't steady yet.
        peer.events.onFrame(640, 480)
        assertEquals(emptyList<String>(), steady)
        clock.runAll()
        // Now the run plays on: its frames skip the stream's thread (nothing queued) when the player takes them.
        peer.events.onFrame(640, 480)
        peer.events.onFrame(640, 480)
        assertEquals(listOf("640x480", "640x480"), steady)
        clock.runAll()
        assertEquals("steady frames don't reach the stream's own listener", listOf("first", "session"), log.map { it.substringBefore(' ') }.sorted())
        // The player can't take one (a new size): it goes the usual way.
        take = false
        peer.events.onFrame(1280, 720)
        clock.runAll()
        assertEquals("frame", log.last())
        take = true
        // Paused and resumed: the resumed run's frames go the usual way until its first frame was reported.
        s.setActive(false)
        steady.clear()
        peer.events.onFrame(640, 480)
        s.setActive(true)
        peer.events.onFrame(640, 480)
        assertEquals(emptyList<String>(), steady)
        clock.runAll()
        assertEquals(2, log.count { it.startsWith("first") })
        peer.events.onFrame(640, 480)
        assertEquals(listOf("640x480"), steady)
        // A new connection after a failure starts over too.
        peer.events.onConnectionState(PeerState.FAILED)
        clock.runAll()
        steady.clear()
        peer.events.onFrame(640, 480)
        assertEquals("a torn-down connection's frame is never steady", emptyList<String>(), steady)
    }

    @Test
    fun `a listener that closes the stream from onReconnecting leaves no timer behind`() {
        val s = stream()
        s.listener = object : HaWebRtcStream.Listener {
            override fun onFirstFrame(sinceStartMs: Long, width: Int, height: Int) {}
            override fun onReconnecting(attempt: Int, error: HaError) { log += "reconnecting"; s.close() }
            override fun onError(error: HaError) { log += "error" }
        }
        val (peer, _) = connect(s)
        peer.events.onFrame(640, 480)
        peer.events.onConnectionState(PeerState.FAILED)
        clock.runAll()
        assertEquals(listOf("reconnecting"), log)
        assertEquals(0, clock.pendingTimers())
        clock.advance(600_000)
        assertEquals(1, channel.callsOf(HaWebRtcSignaling.TYPE_CLIENT_CONFIG).size)
    }

    @Test
    fun `idle wait 0 closes at once`() {
        val s = stream(HaWebRtcStream.Options(idleTeardownMs = 0))
        val (peer, _) = connect(s)
        s.setActive(false)
        assertTrue(peer.closed)
    }

    @Test
    fun `a pause doesn't time out, and a stream started paused waits`() {
        val s = stream(HaWebRtcStream.Options(connectTimeoutMs = 10_000, idleTeardownMs = 60_000))
        connect(s)
        s.setActive(false)
        clock.advance(30_000)
        assertTrue(log.none { it.startsWith("error") })

        val idle = stream()
        idle.setActive(false)
        idle.start()
        clock.runAll()
        assertEquals(1, channel.callsOf(HaWebRtcSignaling.TYPE_CLIENT_CONFIG).size)
        idle.setActive(true)
        clock.runAll()
        assertEquals(2, channel.callsOf(HaWebRtcSignaling.TYPE_CLIENT_CONFIG).size)
    }

    @Test
    fun `start is once`() {
        val s = stream()
        s.start()
        s.start()
        clock.runAll()
        assertEquals(1, channel.callsOf(HaWebRtcSignaling.TYPE_CLIENT_CONFIG).size)
    }
}
