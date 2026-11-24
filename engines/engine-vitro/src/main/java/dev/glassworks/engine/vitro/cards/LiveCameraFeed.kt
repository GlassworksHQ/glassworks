/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.cards

import android.graphics.Bitmap
import dev.glassworks.engine.vitro.ui.VitroCardContext

/**
 * One camera's live picture for a card or the More-Info sheet: HA's MJPEG stream first, with a still snapshot shown
 * while it connects. If the stream fails or drops, snapshots of [picture] are polled every [POLL_MS] and the stream
 * is retried every [RETRY_MS]; the first streamed frame stops the polling again. Main thread only.
 */
internal class LiveCameraFeed(
    private val ctx: VitroCardContext,
    private val entityId: String,
    private val picture: () -> String?,
    private val onBitmap: (Bitmap) -> Unit,
) {
    private var running = false
    private var polling = false
    private var inFlight = false
    private var gotStreamFrame = false
    private var stopStream: (() -> Unit)? = null

    private val pollTick = object : Runnable {
        override fun run() {
            if (!polling) return
            snapshot()
            ctx.main.postDelayed(this, POLL_MS)
        }
    }

    private val retry = Runnable { if (running) openStream() }

    val isPolling: Boolean get() = polling

    fun start() {
        if (running) return
        running = true
        gotStreamFrame = false
        snapshot()
        openStream()
    }

    fun stop() {
        running = false
        polling = false
        inFlight = false
        ctx.main.removeCallbacks(pollTick)
        ctx.main.removeCallbacks(retry)
        stopStream?.let { runCatching { it() } }
        stopStream = null
    }

    private fun openStream() {
        stopStream?.let { runCatching { it() } }
        stopStream = null
        var failed = false
        val handle = ctx.startCameraStream(
            entityId,
            picture(),
            onFrame = { bmp ->
                if (running) {
                    gotStreamFrame = true
                    if (polling) {
                        polling = false
                        ctx.main.removeCallbacks(pollTick)
                    }
                    onBitmap(bmp)
                }
            },
            onFallback = {
                failed = true
                if (running) {
                    stopStream = null
                    if (!polling) {
                        polling = true
                        ctx.main.postDelayed(pollTick, POLL_MS)
                    }
                    ctx.main.removeCallbacks(retry)
                    ctx.main.postDelayed(retry, RETRY_MS)
                }
            },
        )
        if (!failed) stopStream = handle
    }

    private fun snapshot() {
        val pic = picture() ?: return
        if (inFlight) return
        inFlight = true
        val sep = if ('?' in pic) '&' else '?'
        ctx.loadImage("$pic${sep}t=${System.currentTimeMillis()}") { bmp ->
            inFlight = false
            // A streamed frame is newer than any snapshot, so a late still never covers it.
            if (running && bmp != null && (polling || !gotStreamFrame)) onBitmap(bmp)
        }
    }

    companion object {
        const val POLL_MS = 1_500L
        const val RETRY_MS = 10_000L
    }
}
