/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.camera

import android.content.Context
import android.os.Handler
import dev.glassworks.engine.vitro.data.HaDashboardClient
import org.json.JSONObject

/**
 * One camera's live picture of one kind (WebRTC, HLS or MJPEG), as HA's frontend has one player element per kind
 * (`ha-web-rtc-player`, `ha-hls-player`, the MJPEG `<img>`). Vitro's stream choice ([CameraStreamKind], mirroring
 * `ha-camera-stream`) decides which kinds a card runs and which one shows; a stream only plays and reports.
 *
 * Everything here is main-thread only, and every [Listener] call arrives on the main thread.
 *
 * - [view] is the picture: the card places it (full size, over its still image) before [start] and keeps it until
 *   [close]. It draws nothing until the first frame, so the still underneath shows while the stream connects.
 * - [start] connects and plays. Called once; a second call does nothing.
 * - [setActive] false stops pulling and decoding (a view another one hides, HA's page in front of the dashboard) but
 *   keeps the stream; true resumes it, reconnecting if it has to. A stream starts active.
 * - [close] ends it for good and frees the decoder, sockets and threads: the card went. No [Listener] call comes
 *   after it, and nothing else may be called on the stream.
 * - [connectionRestored]: Home Assistant's connection ([CameraStreamHost]'s WebSocket) is back after it dropped. A
 *   stream that was waiting for it to reconnect tries now. Optional: a stream that doesn't wait ignores it.
 * - [setRendering] false: the picture is off screen (its card scrolled out of sight). The stream keeps its
 *   connection and decoder, so it shows again at once, but needn't draw meanwhile; true draws again. A stream that
 *   hasn't shown its first picture yet draws it anyway, as that picture is its [Listener.onFirstFrame]. Optional.
 * - [closeWhenHidden] true: a paused stream would keep only threads and buffers, as it connects afresh on resume
 *   anyway (WebRTC's, whose session closes on pause): the card closes it instead of pausing it, and opens a new one
 *   when it shows again.
 * - A stream reports through [listener]: [Listener.onFirstFrame] once per [start] or resume, when the first picture
 *   is on screen (HA's `streams` event with `hasVideo: true`); [Listener.onFrameRendered] for each later picture drawn
 *   (Clarity counts them); [Listener.onError] when it can't play or stops playing (HA's `hasVideo: false`: the choice
 *   falls back to the next kind and closes this one); [Listener.onEnded] when the server ends a live stream, which the
 *   choice treats like an error.
 */
interface CameraStream {
    val view: android.view.View

    fun start()

    fun setActive(active: Boolean)

    fun close()

    fun connectionRestored() {}

    fun setRendering(rendering: Boolean) {}

    val closeWhenHidden: Boolean get() = false

    /**
     * The view has a surface a frame can be drawn on (it got one once laid out in a window). The choice's own wait for
     * video ([dev.glassworks.engine.vitro.cards.LiveCameraFeed.ALONE_TIMEOUT_MS]) counts from then: a view built long
     * before it is drawn (a heavy view's build) doesn't use the wait up. True for a player that doesn't say.
     */
    val surfaceReady: Boolean get() = true

    var listener: Listener?

    interface Listener {
        fun onFirstFrame(sinceStartMs: Long)

        fun onFrameRendered()

        /** The view got its surface ([surfaceReady] is true now). */
        fun onSurfaceReady() {}

        /** The view lost its surface (hidden, or detached): nothing it plays is on screen until it gets one again. */
        fun onSurfaceLost() {}

        fun onError(reason: String)

        fun onEnded()
    }
}

/**
 * Optional on a [CameraStream]: the picture's size in pixels (0 × 0 until known), read after
 * [CameraStream.Listener.onFirstFrame] and each [CameraStream.Listener.onFrameRendered]. With it the card fits the
 * picture the way HA's `<video>` does (`object-fit`) and sizes itself to it when it has no `aspect_ratio`; without
 * it the stream's view fills the card.
 */
interface CameraStreamVideoSize {
    val videoWidth: Int

    val videoHeight: Int
}

/**
 * The kinds of live stream, in HA's order of preference: WebRTC, then HLS (both from `camera/capabilities`'
 * `frontend_stream_types`), then the MJPEG proxy stream every camera has.
 */
enum class CameraStreamKind { WEB_RTC, HLS, MJPEG }

/**
 * Builds a [CameraStream] of [kind] for a camera entity, or null when it can't (no such player on this device, no
 * Home Assistant connection): the choice then counts that kind as failed and falls back, as HA does when a player
 * reports no video.
 */
interface CameraStreamFactory {
    fun create(entityId: String, kind: CameraStreamKind): CameraStream?

    /**
     * A camera that plays this kind is about to start: what every stream of the kind shares (libwebrtc's factory and
     * EGL) may get ready off the main thread meanwhile. Optional; main thread.
     */
    fun prewarm() {}
}

/**
 * What the engine hands a [CameraStreamFactory] provider: the app's context, the main thread, and Home Assistant's
 * connection as the dashboard's own WebSocket sees it. A factory never gets the stored token itself, only what HA
 * returns for a request ([callWs], [subscribeWs]) and the bearer for HA's own origin ([accessToken]).
 */
class CameraStreamHost(
    val context: Context,
    val main: Handler,
    /** Home Assistant's origin (`http://homeassistant.local:8123`), no trailing slash. */
    val haBase: String,
    /** The current bearer for [haBase], or null before sign-in. Read it per request: it is refreshed. */
    val accessToken: () -> String?,
    /**
     * One WebSocket command and its result, on any thread (`success`, `result`, `error`). Without a connection it fails
     * at once, with an error [isNotConnected] tells apart from Home Assistant's own.
     */
    val callWs: (msg: JSONObject, done: (success: Boolean, result: Any?, error: JSONObject?) -> Unit) -> Unit,
    /**
     * A WebSocket subscription (`camera/webrtc/offer`): its events reach `onEvent` on any thread until the returned
     * handle is called; `onError` runs once if HA refuses it or it can't be sent.
     */
    val subscribeWs: (msg: JSONObject, onEvent: (JSONObject) -> Unit, onError: (JSONObject?) -> Unit) -> () -> Unit,
) {
    companion object {
        /**
         * True when [error] (from [callWs] or [subscribeWs]) means there was no connection to Home Assistant, or it
         * dropped before the answer: HA never saw the request. Null counts, as [subscribeWs] reports a refused send.
         */
        @JvmStatic
        fun isNotConnected(error: JSONObject?): Boolean =
            error == null || error.optString("code") in setOf(HaDashboardClient.DISCONNECTED, HaDashboardClient.SEND_FAILED)
    }
}

/**
 * Where a stream kind's player plugs into Vitro. The engine builds one factory per kind and per connection from the
 * registered provider. Vitro registers HLS and MJPEG itself; `WEB_RTC` has no provider until the WebRTC player
 * registers one, and until then the choice treats WebRTC as unsupported and goes on to HLS.
 */
object CameraStreamFactories {
    private val providers = java.util.EnumMap<CameraStreamKind, (CameraStreamHost) -> CameraStreamFactory>(CameraStreamKind::class.java)

    /** Registers (or replaces) the player for [kind]. Any thread; it applies to cameras that start afterwards. */
    @JvmStatic
    fun register(kind: CameraStreamKind, provider: (CameraStreamHost) -> CameraStreamFactory) {
        synchronized(providers) { providers[kind] = provider }
    }

    /** Removes the player for [kind] (tests). */
    @JvmStatic
    fun unregister(kind: CameraStreamKind) {
        synchronized(providers) { providers.remove(kind) }
    }

    /** The provider for [kind], or null when nothing registered one. */
    @JvmStatic
    fun provider(kind: CameraStreamKind): ((CameraStreamHost) -> CameraStreamFactory)? = synchronized(providers) { providers[kind] }
}
