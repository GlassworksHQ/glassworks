package dev.glassworks.hawebrtc

import livekit.org.webrtc.DataChannel
import livekit.org.webrtc.IceCandidate
import livekit.org.webrtc.MediaConstraints
import livekit.org.webrtc.MediaStream
import livekit.org.webrtc.MediaStreamTrack
import livekit.org.webrtc.PeerConnection
import livekit.org.webrtc.PeerConnectionFactory
import livekit.org.webrtc.RtpReceiver
import livekit.org.webrtc.RtpTransceiver
import livekit.org.webrtc.SdpObserver
import livekit.org.webrtc.SessionDescription
import livekit.org.webrtc.VideoFrame
import livekit.org.webrtc.VideoSink
import livekit.org.webrtc.VideoTrack
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A [WebRtcPeer] on libwebrtc: one `PeerConnection` with recvonly transceivers, as HA's frontend makes it (audio
 * first when asked, then video), its frames drawn by [view]. A frame counts ([WebRtcPeer.Events.onFrame]) when [view]
 * shows it, not when it is decoded: the renderer drops frames while it has no surface to draw on.
 */
internal class LibWebRtcPeer(
    factory: PeerConnectionFactory,
    config: WebRtcClientConfig,
    audio: Boolean,
    private val view: VideoOutput,
    private val events: WebRtcPeer.Events,
) : WebRtcPeer {
    private val closed = AtomicBoolean(false)
    @Volatile private var receiving = true
    private val pc: PeerConnection
    private val dataChannel: DataChannel?
    private val video: VideoTrack?

    private val frames = object : VideoSink {
        override fun onFrame(frame: VideoFrame) {
            if (closed.get() || !receiving) return
            view.onFrame(frame)
        }
    }

    private val drawn = FrameDrawn { w, h -> if (!closed.get() && receiving) events.onFrame(w, h) }

    init {
        val servers = config.iceServers.map { s ->
            PeerConnection.IceServer.builder(s.urls)
                .setUsername(s.username.orEmpty())
                .setPassword(s.credential.orEmpty())
                .createIceServer()
        }
        val rtc = PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            iceTransportsType = if (config.iceTransportPolicy == "relay") PeerConnection.IceTransportsType.RELAY
            else PeerConnection.IceTransportsType.ALL
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }
        pc = factory.createPeerConnection(rtc, Observer()) ?: throw IllegalStateException("libwebrtc could not create a peer connection")
        // Owned here until the peer is made: on a failure part-way, both native references go before the throw.
        var channel: DataChannel? = null
        try {
            channel = config.dataChannel?.let { pc.createDataChannel(it, DataChannel.Init()) }
            val recvonly = RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
            if (audio) pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, recvonly)
            val transceiver = pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, recvonly)
            video = transceiver.receiver.track() as? VideoTrack
            video?.addSink(frames)
        } catch (e: Throwable) {
            closed.set(true)
            channel?.let { runCatching { it.dispose() } }
            pc.dispose()
            throw e
        }
        dataChannel = channel
        view.onDrawn = drawn
    }

    override fun createOffer(onSdp: (sdp: String) -> Unit, onError: (reason: String) -> Unit) {
        pc.createOffer(object : Sdp() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                if (closed.get()) return
                pc.setLocalDescription(object : Sdp() {
                    override fun onSetSuccess() = onSdp(sdp.description)
                    override fun onSetFailure(error: String?) = onError("setLocalDescription: ${error.orEmpty()}")
                }, sdp)
            }

            override fun onCreateFailure(error: String?) = onError("createOffer: ${error.orEmpty()}")
        }, MediaConstraints())
    }

    override fun setRemoteAnswer(sdp: String, onDone: () -> Unit, onError: (reason: String) -> Unit) {
        if (closed.get()) return
        pc.setRemoteDescription(object : Sdp() {
            override fun onSetSuccess() = onDone()
            override fun onSetFailure(error: String?) = onError(error.orEmpty())
        }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    override fun addRemoteCandidate(candidate: IceCandidateInit) {
        if (closed.get() || candidate.candidate.isEmpty()) return
        // HA's frontend: a candidate with neither mid nor index is for mid "0". libwebrtc finds the section by mid
        // when it has one, and by index otherwise.
        val mid = candidate.sdpMid ?: if (candidate.sdpMLineIndex == null) "0" else ""
        pc.addIceCandidate(IceCandidate(mid, candidate.sdpMLineIndex ?: 0, candidate.candidate))
    }

    override fun setReceiving(receiving: Boolean) {
        if (closed.get()) return
        this.receiving = receiving
        video?.setEnabled(receiving)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (view.onDrawn === drawn) view.onDrawn = null
        runCatching { video?.removeSink(frames) }
        dataChannel?.let { runCatching { it.unregisterObserver() }; runCatching { it.dispose() } }
        // Closes the connection and frees its transceivers and tracks.
        pc.dispose()
    }

    private inner class Observer : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            if (closed.get()) return
            events.onLocalCandidate(IceCandidateInit(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex))
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            if (closed.get()) return
            events.onConnectionState(
                when (newState) {
                    PeerConnection.PeerConnectionState.NEW -> PeerState.NEW
                    PeerConnection.PeerConnectionState.CONNECTING -> PeerState.CONNECTING
                    PeerConnection.PeerConnectionState.CONNECTED -> PeerState.CONNECTED
                    PeerConnection.PeerConnectionState.DISCONNECTED -> PeerState.DISCONNECTED
                    PeerConnection.PeerConnectionState.FAILED -> PeerState.FAILED
                    PeerConnection.PeerConnectionState.CLOSED -> PeerState.CLOSED
                },
            )
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
        override fun onAddStream(stream: MediaStream) {}
        override fun onRemoveStream(stream: MediaStream) {}
        // A channel the camera opens isn't used. Its Java wrapper holds a native reference of its own: let it go now
        // (the connection keeps the channel itself).
        override fun onDataChannel(channel: DataChannel) {
            runCatching { channel.dispose() }
        }
        // The offer is made once the transceivers are in; later renegotiation isn't needed for receiving.
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
    }

    private open class Sdp : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }
}
