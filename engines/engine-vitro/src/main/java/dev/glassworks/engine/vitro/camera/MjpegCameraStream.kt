/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.camera

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.widget.ImageView
import dev.glassworks.engine.vitro.net.MjpegStreamPlayer

/**
 * HA's MJPEG proxy stream (`/api/camera_proxy_stream/<entity>`) as a [CameraStream]: [MjpegStreamPlayer]'s frames
 * in an [ImageView], which shows nothing until the first one. Paused, it closes the connection; resumed, it opens a
 * new one. [newPlayer] is the player for a URL and token (tests swap it).
 */
class MjpegCameraStream internal constructor(
    context: Context,
    private val url: String,
    private val accessToken: () -> String?,
    private val newPlayer: (url: String, token: String?, onFrame: (Bitmap) -> Unit, onError: () -> Unit) -> Player,
) : CameraStream, CameraStreamVideoSize {
    constructor(context: Context, url: String, accessToken: () -> String?) : this(context, url, accessToken, { u, t, f, e ->
        val p = MjpegStreamPlayer(u, t, onFrame = f, onError = e)
        object : Player {
            override fun start() = p.start()
            override fun stop() = p.stop()
        }
    })

    /** What [MjpegStreamPlayer] does for this stream: start, and stop for good. */
    interface Player {
        fun start()

        fun stop()
    }

    private val image = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_XY }
    override val view: android.view.View get() = image
    override var listener: CameraStream.Listener? = null

    override var videoWidth = 0
        private set
    override var videoHeight = 0
        private set

    private var player: Player? = null
    private var started = false
    private var closed = false
    private var active = true
    private var connectedAt = 0L
    private var gotFrame = false

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

    override fun close() {
        if (closed) return
        closed = true
        listener = null
        disconnect()
        image.setImageDrawable(null)
    }

    private fun connect() {
        disconnect()
        connectedAt = SystemClock.elapsedRealtime()
        gotFrame = false
        // Nothing until this start's (or resume's) first frame: the picture from before the pause isn't live.
        image.setImageDrawable(null)
        lateinit var current: Player
        current = newPlayer(url, accessToken(), onFrame@{ bmp ->
            if (closed || player !== current) return@onFrame
            image.setImageBitmap(bmp)
            videoWidth = bmp.width
            videoHeight = bmp.height
            if (!gotFrame) {
                gotFrame = true
                listener?.onFirstFrame(SystemClock.elapsedRealtime() - connectedAt)
            } else {
                listener?.onFrameRendered()
            }
        }, onError@{
            if (closed || player !== current) return@onError
            player = null
            listener?.onError("mjpeg: the stream failed or dropped")
        })
        player = current
        current.start()
    }

    private fun disconnect() {
        val p = player ?: return
        player = null
        runCatching { p.stop() }
    }
}
