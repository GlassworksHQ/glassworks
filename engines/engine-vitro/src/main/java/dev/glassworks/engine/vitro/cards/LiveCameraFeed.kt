/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.cards

import android.graphics.Bitmap
import android.view.ViewGroup
import android.widget.FrameLayout
import dev.glassworks.engine.vitro.VitroFailures
import dev.glassworks.engine.vitro.camera.CameraFitFrame
import dev.glassworks.engine.vitro.camera.CameraStream
import dev.glassworks.engine.vitro.camera.CameraStreamChoice
import dev.glassworks.engine.vitro.camera.CameraStreamKind
import dev.glassworks.engine.vitro.camera.CameraStreamVideoSize
import dev.glassworks.engine.vitro.camera.CameraVideoBudget
import dev.glassworks.engine.vitro.camera.PlannedStream
import dev.glassworks.engine.vitro.ui.VitroCardContext
import org.json.JSONObject

/**
 * One camera's live picture for a card or the More-Info sheet. Main thread only.
 *
 * With [stream] true (HA's `camera_view: live`, and More Info) it is HA's `ha-camera-stream`: it reads the camera's
 * `camera/capabilities`, runs the players [CameraStreamChoice] picks (WebRTC, then HLS, then MJPEG, as HA does) in
 * [host] over the card's still, and falls back down that chain when one fails. A still snapshot ([onBitmap]) shows
 * first, as HA's poster does. When MJPEG fails too, snapshots of [picture] are polled every [POLL_MS] and MJPEG is
 * retried every [RETRY_MS]; its first frame stops the polling again.
 *
 * With [stream] false (HA's `camera_view: auto`) there is no stream: a snapshot now and every [SNAPSHOT_MS], as
 * HA's `hui-image` refreshes a camera picture.
 *
 * Lifecycle: [start] when the card is on screen, [setActive] false while it is hidden (another view in front, HA's
 * own page over the dashboard) and true when it shows again, [stop] when the card goes. A camera that plays video
 * holds one of the panel's [CameraVideoBudget] slots while it's active; without one it plays MJPEG until one frees.
 * A player that throws counts as that player failing (logged once by [VitroFailures]), never as the card's crash.
 */
internal class LiveCameraFeed(
    private val ctx: VitroCardContext,
    private val entityId: String,
    private val picture: () -> String?,
    private val stream: Boolean = true,
    /** Where the players' views go, full size, over the still. */
    private val host: ViewGroup? = null,
    /** The card's `fit_mode` (null: CSS's default for the element). */
    private val fitMode: String? = null,
    /** The live picture's size once a player knows it: HA's card takes the video's shape without `aspect_ratio`. */
    private val onVideoSize: ((width: Int, height: Int) -> Unit)? = null,
    /** The card's type, for the log when a player throws. */
    private val cardType: String? = null,
    private val onBitmap: (Bitmap) -> Unit,
) {
    private val pollMs = if (stream) POLL_MS else SNAPSHOT_MS

    private var running = false
    private var active = true
    private var polling = false
    private var inFlight = false

    /** Frames a player delivered this run: a still requested before the latest one never covers it. */
    private var streamFrames = 0

    /** Bumped by [stop]: a snapshot or capabilities answer of an earlier run neither lands nor clears [inFlight]. */
    private var generation = 0

    /** `camera/capabilities`' kinds; null until known. Kept across runs, as HA asks again only for another entity. */
    private var capabilities: List<CameraStreamKind>? = null
    private var capabilitiesAsked = false

    /** What HLS and WebRTC reported this run (HA's `hasVideo`): true with video, false failed, null loading. */
    private var hlsVideo: Boolean? = null
    private var webRtcVideo: Boolean? = null

    /** MJPEG failed: snapshots poll until [retry] opens it again. */
    private var mjpegDown = false

    /** No video slot was free: MJPEG plays until [CameraVideoBudget] calls back. */
    private var capped = false
    private var holdsSlot = false

    private class Slot(val stream: CameraStream, val frame: CameraFitFrame) {
        var hasFrame = false
    }

    private val slots = LinkedHashMap<CameraStreamKind, Slot>()
    private var recomputePosted = false

    private val pollTick = object : Runnable {
        override fun run() {
            if (!polling) return
            snapshot()
            ctx.main.postDelayed(this, pollMs)
        }
    }

    private val retry = Runnable {
        if (running && active && mjpegDown) {
            mjpegDown = false
            recompute()
        }
    }

    private val recomputeTask = Runnable {
        recomputePosted = false
        recompute()
    }

    val isPolling: Boolean get() = polling

    /** The kinds of player running now, in start order (tests, Clarity). */
    val runningKinds: List<CameraStreamKind> get() = slots.keys.toList()

    /** The kind on screen (the visible player that has drawn a frame), or null while only the still shows. */
    val shownKind: CameraStreamKind?
        get() = slots.entries.firstOrNull { (_, s) -> s.hasFrame && s.frame.alpha > 0f }?.key

    fun start() {
        if (running) return
        running = true
        active = true
        streamFrames = 0
        hlsVideo = null
        webRtcVideo = null
        mjpegDown = false
        capped = false
        snapshot()
        if (!stream) {
            startPolling()
            return
        }
        if (capabilities == null) askCapabilities() else recompute()
    }

    /** Hidden (false) or shown again (true): players pause and resume, and the video slot goes back meanwhile. */
    fun setActive(active: Boolean) {
        if (!running || this.active == active) return
        this.active = active
        if (!active) {
            stopPolling()
            ctx.main.removeCallbacks(retry)
            ctx.main.removeCallbacks(recomputeTask)
            recomputePosted = false
            // A player that can't pause is closed: it never keeps decoding once its slot goes back. (Not a failure of
            // the kind: a failed MJPEG would start polling, and nothing polls while the camera is hidden.)
            for (kind in slots.keys.toList()) {
                if (guard(kind) { slots[kind]?.stream?.setActive(false) } == null) closeSlot(kind)
            }
            releaseSlot()
            capped = false
            return
        }
        if (!stream) {
            snapshot()
            startPolling()
            return
        }
        // A video camera takes its slot again first; without one it goes to MJPEG (recompute closes the rest). A camera
        // that was capped before it hid starts over too: recompute asks for a slot, or waits for one, from its plan now.
        capped = false
        if (slots.keys.any { it != CameraStreamKind.MJPEG } && !acquireSlot()) capped = true
        for (kind in slots.keys.toList()) {
            if (capped && kind != CameraStreamKind.MJPEG) continue
            val slot = slots[kind] ?: continue
            // A player that can't resume has failed, as one that can't start.
            if (guard(kind) { slot.stream.setActive(true) } == null) {
                closeSlot(kind)
                failed(kind)
            }
        }
        if (mjpegDown) {
            startPolling()
            ctx.main.postDelayed(retry, RETRY_MS)
        }
        recompute()
    }

    fun stop() {
        running = false
        stopPolling()
        inFlight = false
        generation++
        ctx.main.removeCallbacks(retry)
        ctx.main.removeCallbacks(recomputeTask)
        recomputePosted = false
        // A capabilities request still out lands on nothing; the next start asks again.
        if (capabilities == null) capabilitiesAsked = false
        for (kind in slots.keys.toList()) closeSlot(kind)
        releaseSlot()
        capped = false
    }

    private fun askCapabilities() {
        if (capabilitiesAsked) return
        capabilitiesAsked = true
        val state = ctx.store[entityId]
        val features = (state?.attributes?.get("supported_features") as? Number)?.toInt() ?: 0
        if (features and CameraStreamChoice.FEATURE_STREAM == 0) {
            // HA's frontend doesn't ask a camera without the STREAM feature: MJPEG.
            capabilities = emptyList()
            recompute()
            return
        }
        val run = generation
        ctx.callWs(JSONObject().put("type", "camera/capabilities").put("entity_id", entityId)) { ok, result, _ ->
            ctx.main.post {
                if (run != generation) return@post
                // HA's frontend shows no stream when this fails; every camera has MJPEG, so Vitro plays that.
                capabilities = if (ok) CameraStreamChoice.kindsOf(result) else emptyList()
                recompute()
            }
        }
    }

    private fun scheduleRecompute() {
        if (recomputePosted) return
        recomputePosted = true
        ctx.main.post(recomputeTask)
    }

    /** Runs the players the choice asks for now: closes the others, opens the missing ones, shows the visible one. */
    private fun recompute() {
        if (!running || !active || !stream) return
        val offered = capabilities ?: return
        val supported = CameraStreamChoice.playable(offered) { ctx.cameraPlayers?.supports(it) == true }
        var plan = CameraStreamChoice.streams(supported, hlsVideo, webRtcVideo)
        if (plan.any { it.kind != CameraStreamKind.MJPEG }) {
            if (capped || !acquireSlot()) {
                capped = true
                plan = listOf(PlannedStream(CameraStreamKind.MJPEG, true))
            }
        } else {
            releaseSlot()
        }
        val wanted = plan.associateBy { it.kind }
        for (kind in slots.keys.toList()) if (kind !in wanted) closeSlot(kind)
        for (p in plan) {
            if (p.kind == CameraStreamKind.MJPEG && mjpegDown) continue
            val slot = slots[p.kind] ?: open(p.kind) ?: continue
            slot.frame.alpha = if (p.visible) 1f else 0f
            if (p.visible && slot.hasFrame) sizeFrom(slot)
        }
        // The still polls only while nothing streams: once MJPEG (or video) draws, its frames are the picture.
        if (slots.values.any { it.hasFrame && it.frame.alpha > 0f }) stopPolling()
    }

    private fun open(kind: CameraStreamKind): Slot? {
        val players = ctx.cameraPlayers
        val target = host
        val created = if (players == null || target == null) null else guard(kind) { players.create(entityId, kind) }
        if (created == null) {
            // No player for it here (or it threw): that kind failed, as HA's player reports `hasVideo: false`.
            failed(kind)
            return null
        }
        val frame = CameraFitFrame(requireNotNull(target).context, CameraFitFrame.fitFor(fitMode, kind))
        val slot = Slot(created, frame)
        slots[kind] = slot
        val ok = guard(kind) {
            frame.addView(created.view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            requireNotNull(target).addView(frame, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            created.listener = listenerFor(kind, slot)
            created.start()
        }
        if (ok == null) {
            closeSlot(kind)
            failed(kind)
            return null
        }
        return slot
    }

    private fun listenerFor(kind: CameraStreamKind, slot: Slot) = object : CameraStream.Listener {
        private fun current() = running && slots[kind] === slot

        override fun onFirstFrame(sinceStartMs: Long) {
            if (!current()) return
            slot.hasFrame = true
            streamFrames++
            when (kind) {
                CameraStreamKind.HLS -> hlsVideo = true
                CameraStreamKind.WEB_RTC -> webRtcVideo = true
                CameraStreamKind.MJPEG -> Unit
            }
            sizeFrom(slot)
            if (slot.frame.alpha > 0f) stopPolling()
            scheduleRecompute()
        }

        override fun onFrameRendered() {
            if (!current()) return
            streamFrames++
            sizeFrom(slot)
        }

        override fun onError(reason: String) {
            if (!current()) return
            closeSlot(kind)
            failed(kind)
        }

        override fun onEnded() = onError("ended")
    }

    private fun sizeFrom(slot: Slot) {
        val sized = slot.stream as? CameraStreamVideoSize ?: return
        val w = sized.videoWidth
        val h = sized.videoHeight
        if (w <= 0 || h <= 0) return
        slot.frame.setVideoSize(w, h)
        if (slot.frame.alpha > 0f) onVideoSize?.invoke(w, h)
    }

    /** [kind] can't play (now): the choice moves on, and a failed MJPEG falls back to polled stills. */
    private fun failed(kind: CameraStreamKind) {
        when (kind) {
            CameraStreamKind.HLS -> hlsVideo = false
            CameraStreamKind.WEB_RTC -> webRtcVideo = false
            CameraStreamKind.MJPEG -> {
                mjpegDown = true
                startPolling()
                ctx.main.removeCallbacks(retry)
                ctx.main.postDelayed(retry, RETRY_MS)
            }
        }
        scheduleRecompute()
    }

    private fun closeSlot(kind: CameraStreamKind) {
        val slot = slots.remove(kind) ?: return
        slot.stream.listener = null
        guard(kind) { slot.stream.close() }
        runCatching { (slot.frame.parent as? ViewGroup)?.removeView(slot.frame) }
    }

    private fun acquireSlot(): Boolean {
        if (holdsSlot) return true
        holdsSlot = CameraVideoBudget.acquire(this) {
            // A slot freed: a camera that played MJPEG for want of one takes up video again.
            ctx.main.post {
                if (running && active && capped) {
                    capped = false
                    recompute()
                }
            }
        }
        return holdsSlot
    }

    private fun releaseSlot() {
        holdsSlot = false
        CameraVideoBudget.release(this)
    }

    /** Runs a player's [block]; a throw is that player failing, logged once, never the card's crash. */
    private fun <T> guard(kind: CameraStreamKind, block: () -> T): T? = try {
        block()
    } catch (e: Exception) {
        VitroFailures.report("camera-${kind.name.lowercase()}", cardType, e)
        null
    }

    private fun startPolling() {
        if (polling || !running) return
        polling = true
        ctx.main.removeCallbacks(pollTick)
        ctx.main.postDelayed(pollTick, pollMs)
    }

    private fun stopPolling() {
        if (!polling) return
        polling = false
        ctx.main.removeCallbacks(pollTick)
    }

    private fun snapshot() {
        val pic = picture() ?: return
        if (inFlight) return
        inFlight = true
        val run = generation
        val framesBefore = streamFrames
        val sep = if ('?' in pic) '&' else '?'
        ctx.loadImage("$pic${sep}t=${System.currentTimeMillis()}") { bmp ->
            if (run != generation) return@loadImage
            inFlight = false
            // A streamed frame that arrived after this still was asked for is newer: the still never covers it.
            if (running && bmp != null && streamFrames == framesBefore) onBitmap(bmp)
        }
    }

    companion object {
        const val POLL_MS = 1_500L

        /** HA's `hui-image` refresh of a camera snapshot (`camera_view: auto`). */
        const val SNAPSHOT_MS = 10_000L
        const val RETRY_MS = 10_000L
    }
}
