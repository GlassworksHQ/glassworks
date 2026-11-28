/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.webrtc

import android.view.View
import dev.glassworks.engine.vitro.camera.CameraStream
import dev.glassworks.engine.vitro.camera.CameraStreamFactories
import dev.glassworks.engine.vitro.camera.CameraStreamFactory
import dev.glassworks.engine.vitro.camera.CameraStreamHost
import dev.glassworks.engine.vitro.camera.CameraStreamKind
import dev.glassworks.engine.vitro.camera.CameraStreamVideoSize
import dev.glassworks.hawebrtc.HaCommandChannel
import dev.glassworks.hawebrtc.HaError
import dev.glassworks.hawebrtc.HaWebRtcPlayer
import dev.glassworks.hawebrtc.HaWebRtcStream
import dev.glassworks.hawebrtc.VideoScaling
import dev.glassworks.hawebrtc.VideoViewKind
import org.json.JSONObject

/**
 * Vitro's WebRTC cameras: [CameraStreamKind.WEB_RTC] on libs/ha-webrtc, which plays a camera the way HA's frontend
 * `ha-web-rtc-player` does. An app that ships this module calls [register] once, before Vitro's dashboards load;
 * Vitro's stream choice then runs WebRTC beside HLS, as `ha-camera-stream` does, and shows whichever plays first
 * (WebRTC, when both do).
 */
object VitroWebRtc {
    /** Registers the WebRTC player with Vitro. Any thread; idempotent. */
    @JvmStatic
    fun register() {
        CameraStreamFactories.register(CameraStreamKind.WEB_RTC) { host -> Factory(host) }
    }

    /**
     * How Vitro plays: muted (dashboard cameras are), and a paused stream closing its connection at once, as the seam
     * asks (`setActive(false)` stops pulling and decoding, like Vitro's HLS player releasing ExoPlayer). The picture
     * fills the view: the card's `CameraFitFrame` does the fitting (HA's `object-fit`), sizing the view to the picture
     * for `contain` and `cover` and to the card for `fill`, which then stretches as HA's does.
     */
    internal val OPTIONS = HaWebRtcPlayer.Options(
        stream = HaWebRtcStream.Options(audio = false, idleTeardownMs = 0),
        scaling = VideoScaling.STRETCH,
        // A TextureView on every Android version, never a SurfaceView: a panel whose screen is rotated (the S1's) has a
        // composer that overlays only one video, so a surface per camera pushed the whole window onto the GPU at video
        // rate and scrolling slowed down. In the window, the video also fades, clips and filters with its card.
        view = VideoViewKind.TEXTURE_VIEW,
    )

    internal class Factory(private val host: CameraStreamHost) : CameraStreamFactory {
        override fun create(entityId: String, kind: CameraStreamKind): CameraStream? =
            if (kind == CameraStreamKind.WEB_RTC) WebRtcCameraStream(HaWebRtcPlayer(host.context, HostChannel(host), entityId, OPTIONS)) else null

        /** libwebrtc's factory and EGL get made off the main thread while the view builds. */
        override fun prewarm() = HaWebRtcPlayer.prewarm(host.context)
    }
}

/**
 * Vitro's Home Assistant connection ([CameraStreamHost]: the dashboard's own WebSocket) as a [HaCommandChannel]. A
 * request that failed for want of a connection is [HaError.NOT_CONNECTED], whatever code Vitro's client gave it, so
 * the stream waits for HA rather than give up on the camera.
 */
internal class HostChannel(private val host: CameraStreamHost) : HaCommandChannel {
    override fun call(command: JSONObject, onResult: (Any?) -> Unit, onError: (HaError) -> Unit) {
        host.callWs(command) { success, result, error ->
            if (success) onResult(result.takeUnless { it == JSONObject.NULL }) else onError(errorOf(error))
        }
    }

    override fun subscribe(command: JSONObject, onEvent: (JSONObject) -> Unit, onError: (HaError) -> Unit): HaCommandChannel.Subscription {
        val cancel = host.subscribeWs(command, onEvent) { error -> onError(errorOf(error)) }
        return HaCommandChannel.Subscription { cancel() }
    }

    private fun errorOf(error: JSONObject?): HaError =
        if (CameraStreamHost.isNotConnected(error)) HaError(HaError.NOT_CONNECTED, error?.optString("message").orEmpty())
        else HaError.fromJson(error)
}

/**
 * An [HaWebRtcPlayer] as Vitro's [CameraStream]: both are main-thread only and report on the main thread. The first
 * frame is HA's `hasVideo: true`. An error is the player giving up (HA's `start_failed` or `connect_failed`, a
 * connection that failed and couldn't come back, or no video in 20 s), which makes Vitro's choice fall back. WebRTC
 * has no end from the server's side, so [CameraStream.Listener.onEnded] isn't called.
 *
 * Paused, Vitro's player closes its session at once ([VitroWebRtc.OPTIONS]) and would keep only its threads and EGL
 * surface: so it's [closeWhenHidden], and the card opens a new one when it shows again (libwebrtc's shared parts
 * linger meanwhile, so that costs a new session, as a resume does).
 */
internal class WebRtcCameraStream(private val player: HaWebRtcPlayer) : CameraStream, CameraStreamVideoSize {
    override val view: View get() = player.view
    override var listener: CameraStream.Listener? = null
    override val videoWidth: Int get() = player.videoWidth
    override val videoHeight: Int get() = player.videoHeight

    private var closed = false

    init {
        player.onSurface = { ready -> if (!closed) { if (ready) listener?.onSurfaceReady() else listener?.onSurfaceLost() } }
        player.listener = object : HaWebRtcPlayer.Listener {
            override fun onFirstFrame(sinceStartMs: Long, width: Int, height: Int) {
                if (!closed) listener?.onFirstFrame(sinceStartMs)
            }

            override fun onFrameRendered() {
                if (!closed) listener?.onFrameRendered()
            }

            // A steady frame posts its report only while the card or Clarity wants it, as HLS does
            // ([CameraStream.Listener.wantsFrames]): not 15 main-thread tasks a second per camera for nobody.
            override val wantsFrames: Boolean
                get() = !closed && listener?.wantsFrames == true

            override fun onError(error: HaError) {
                if (!closed) listener?.onError(listOf(error.code, error.message).filter { it.isNotEmpty() }.joinToString(": "))
            }
        }
    }

    override fun start() = player.start()

    override fun setActive(active: Boolean) = player.setActive(active)

    override fun connectionRestored() = player.connectionRestored()

    override fun setRendering(rendering: Boolean) = player.setRendering(rendering)

    override val closeWhenHidden: Boolean get() = true

    override val surfaceReady: Boolean get() = player.surfaceReady

    override fun close() {
        if (closed) return
        closed = true
        listener = null
        player.close()
    }
}
