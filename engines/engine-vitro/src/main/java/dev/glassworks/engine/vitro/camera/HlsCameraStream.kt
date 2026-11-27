/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.camera

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import android.view.TextureView
import android.view.View
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import org.json.JSONObject

/**
 * HA's HLS stream (the `stream` integration) as a [CameraStream], the way `ha-hls-player` plays it, on Media3's
 * ExoPlayer instead of hls.js:
 *
 * - The address comes from `camera/stream` over the dashboard's WebSocket, as the frontend's `fetchStreamUrl` asks
 *   for it, made absolute on HA's origin. Its path carries HA's stream token, so the playlist and segments need no
 *   other credentials (the frontend's `fetch` and hls.js send none either).
 * - hls.js's settings, where ExoPlayer has them: 30 s manifest, playlist and fragment timeouts (the connection's
 *   connect and read timeouts), and catching up with the live edge at up to 2× (`maxLiveSyncPlaybackRate`). LL-HLS
 *   is played whenever HA offers it: hls.js turns `lowLatencyMode` off past two streams because a browser keeps at
 *   most six HTTP/1.1 connections per host, a limit a panel's own HTTP stack doesn't have. `backBufferLength` caps
 *   what hls.js keeps behind the playhead; ExoPlayer keeps none, which is less on a panel.
 * - Muted, as HA's cards are, and with the audio track off, so no audio decoder runs.
 * - The picture draws in a [TextureView] on every Android version, not a SurfaceView: a panel whose screen is rotated
 *   (the S1's) has a composer that overlays only one video, so a surface per camera pushed the whole window onto the
 *   GPU at video rate and scrolling slowed down. It stays clear (alpha 0) until the first frame, so the card's still
 *   shows while the stream connects.
 * - Off screen ([setRendering] false), once its first frame is in, the player lets go of the view: it keeps
 *   decoding (Media3 renders to a placeholder surface), so it's live again at once, but draws nothing.
 * - Fallen behind the live window, it goes back to the live edge, as hls.js does; any other playback error, and
 *   `camera/stream` failing, is the stream failing (HA's `hasVideo: false`).
 *
 * Paused ([setActive] false), the player is released: decoders, buffers and connections go. Resumed, it asks
 * `camera/stream` again (HA keeps the same stream while it's alive, and starts a new one if it ended).
 */
@OptIn(markerClass = [UnstableApi::class])
class HlsCameraStream internal constructor(
    context: Context,
    private val main: Handler,
    private val entityId: String,
    private val haBase: String,
    private val callWs: (msg: JSONObject, done: (success: Boolean, result: Any?, error: JSONObject?) -> Unit) -> Unit,
    private val newPlayer: (Context) -> ExoPlayer,
    private val newSource: (url: String) -> MediaSource,
    /** Where the player draws: the [view] (tests give the player a surface of their own). */
    private val attachOutput: (ExoPlayer, TextureView) -> Unit,
    /** The player lets go of the [view] (off screen). */
    private val detachOutput: (ExoPlayer, TextureView) -> Unit = { p, _ -> p.clearVideoSurface() },
) : CameraStream, CameraStreamVideoSize {
    constructor(host: CameraStreamHost, entityId: String) : this(
        host.context, host.main, entityId, host.haBase, host.callWs, ::defaultPlayer, ::defaultSource,
        attachOutput = { p, v -> p.setVideoTextureView(v) },
    )

    private val appContext = context
    private val output = TextureView(context).apply { alpha = 0f }
    override val view: View get() = output

    /** On screen ([setRendering]). */
    private var rendering = true

    /** The player draws into [output] now. */
    private var outputAttached = false
    override var listener: CameraStream.Listener? = null

    override var videoWidth = 0
        private set
    override var videoHeight = 0
        private set

    private var started = false
    private var closed = false
    private var active = true
    private var player: ExoPlayer? = null

    /** Bumped by each connect and disconnect: a `camera/stream` answer or player event of an earlier one is dropped. */
    private var generation = 0
    private var startedAt = 0L
    private var firstFrameSeen = false

    /** The playlist address `camera/stream` gave last (tests, logs). */
    var url: String? = null
        private set

    override fun start() {
        if (started || closed) return
        started = true
        if (active) connect()
    }

    override fun setActive(active: Boolean) {
        if (closed || this.active == active) return
        this.active = active
        if (!started) return
        if (active) connect() else disconnect()
    }

    override fun setRendering(rendering: Boolean) {
        if (closed || this.rendering == rendering) return
        this.rendering = rendering
        applyOutput()
    }

    /** The player draws into the view while on screen, and until its first frame in any case (it says the stream plays). */
    private fun applyOutput() {
        val p = player ?: return
        val want = rendering || !firstFrameSeen
        if (want == outputAttached) return
        outputAttached = want
        if (want) attachOutput(p, output) else detachOutput(p, output)
    }

    override fun close() {
        if (closed) return
        closed = true
        listener = null
        disconnect()
    }

    private fun connect() {
        disconnect()
        val gen = generation
        startedAt = SystemClock.elapsedRealtime()
        firstFrameSeen = false
        // Clear again until this start's (or resume's) first frame: the last frame of before never shows as live.
        output.alpha = 0f
        callWs(JSONObject().put("type", "camera/stream").put("entity_id", entityId)) { ok, result, error ->
            main.post {
                if (closed || gen != generation) return@post
                val path = (result as? JSONObject)?.optString("url").orEmpty()
                if (!ok || path.isEmpty()) {
                    fail("camera/stream: ${error?.optString("code")?.ifEmpty { null } ?: "no stream"}")
                    return@post
                }
                play(gen, absolute(haBase, path))
            }
        }
    }

    private fun play(gen: Int, playlist: String) {
        url = playlist
        val p = newPlayer(appContext)
        player = p
        p.volume = 0f
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
        p.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                if (closed || gen != generation) return
                output.alpha = 1f
                if (!firstFrameSeen) {
                    firstFrameSeen = true
                    listener?.onFirstFrame(SystemClock.elapsedRealtime() - startedAt)
                    // Off screen since before its first frame: it lets go of the view now that it has shown one.
                    applyOutput()
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width <= 0 || videoSize.height <= 0) return
                videoWidth = (videoSize.width * videoSize.pixelWidthHeightRatio).toInt().coerceAtLeast(1)
                videoHeight = videoSize.height
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (closed || gen != generation) return
                if (playbackState == Player.STATE_ENDED) {
                    disconnect()
                    listener?.onEnded()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (closed || gen != generation) return
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    // hls.js keeps a live stream at its edge on its own: back to the live edge.
                    p.seekToDefaultPosition()
                    p.prepare()
                    return
                }
                fail("hls: ${error.errorCodeName}")
            }
        })
        // Each frame the player hands the surface (on its playback thread): a rendered frame, after the first.
        p.setVideoFrameMetadataListener { _, _, _, _ ->
            main.post {
                if (!closed && gen == generation && firstFrameSeen && outputAttached) listener?.onFrameRendered()
            }
        }
        attachOutput(p, output)
        outputAttached = true
        p.setMediaSource(newSource(playlist))
        p.playWhenReady = true
        p.prepare()
    }

    private fun fail(reason: String) {
        disconnect()
        listener?.onError(reason)
    }

    private fun disconnect() {
        generation++
        outputAttached = false
        val p = player ?: return
        player = null
        runCatching { p.release() }
    }

    internal companion object {
        /** hls.js's manifest, playlist and fragment loading timeouts in HA's player. */
        const val TIMEOUT_MS = 30_000

        /** hls.js's `maxLiveSyncPlaybackRate` in HA's player. */
        const val MAX_LIVE_SYNC_SPEED = 2f

        fun defaultPlayer(context: Context): ExoPlayer = ExoPlayer.Builder(context).build()

        fun defaultSource(url: String): MediaSource {
            val http = DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(TIMEOUT_MS)
                .setReadTimeoutMs(TIMEOUT_MS)
                // HA's stream never redirects across protocols: one that does (a proxy) isn't followed.
                .setAllowCrossProtocolRedirects(false)
            return HlsMediaSource.Factory(http)
                .setAllowChunklessPreparation(true)
                .createMediaSource(mediaItem(url))
        }

        fun mediaItem(url: String): MediaItem = MediaItem.Builder()
            .setUri(url)
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setMaxPlaybackSpeed(MAX_LIVE_SYNC_SPEED).build())
            .build()

        /** HA's `hassUrl(path)`: the path `camera/stream` returns, on HA's origin (always, as the frontend does). */
        fun absolute(haBase: String, path: String): String = haBase.trimEnd('/') + "/" + path.trimStart('/')
    }
}
