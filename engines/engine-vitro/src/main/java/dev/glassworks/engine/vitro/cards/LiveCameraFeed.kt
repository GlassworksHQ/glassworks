/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.cards

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import dev.glassworks.engine.vitro.VitroFailures
import dev.glassworks.engine.vitro.camera.CameraFitFrame
import dev.glassworks.engine.vitro.camera.CameraKnowledge
import dev.glassworks.engine.vitro.camera.CameraStream
import dev.glassworks.engine.vitro.camera.CameraStreamChoice
import dev.glassworks.engine.vitro.camera.CameraStreamKind
import dev.glassworks.engine.vitro.camera.CameraStreamVideoSize
import dev.glassworks.engine.vitro.camera.CameraVideoBudget
import dev.glassworks.engine.vitro.camera.PlannedStream
import dev.glassworks.engine.vitro.data.EntityStore
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
 *
 * What failed is tried again later, as HA's `ha-camera-stream` forgets what its players reported when Home Assistant
 * comes back: when HA's connection is back after a drop ([connectionRestored]; a camera fails all the way down to
 * stills while HA restarts), when the camera shows after a hide of [LONG_HIDE_MS] or more (the screen woke up), and
 * every [VIDEO_RETRY_MS] while a camera that offers video plays MJPEG because its video failed. MJPEG stays on screen
 * until the video that replaces it draws.
 *
 * Where a wall panel departs from HA's frontend, it changes only how soon a camera plays, never what it shows:
 * - HA's `camera/capabilities` answer is shared by every picture of the camera on the connection ([CameraKnowledge]),
 *   and asked as soon as the card is built, before it's on screen; WebRTC's player gets ready ([CameraPlayers.prewarm])
 *   once HA says the camera offers it.
 * - HLS loads beside WebRTC only after WebRTC has gone [EXTRA_DELAY_MS] without video, and stops as soon as WebRTC
 *   has video: HA runs both until both have loaded, and then shows WebRTC (muted, as here), so the second decoder and
 *   HA's HLS stream worker would only cost.
 * - WebRTC playing alone (no slot for HLS beside it) gets [ALONE_TIMEOUT_MS], from the moment its view has a
 *   surface to draw on, to show video before HLS takes over,
 *   rather than the player's own 20 s; a camera whose WebRTC failed to connect plays HLS first for a while
 *   ([CameraKnowledge.webRtcFailedRecently]).
 * - [foreground] (More Info's) takes a slot from a card behind it when every slot is held ([CameraVideoBudget]).
 * - Off screen ([setOnScreen] false: its card scrolled out of sight) the players stop drawing, and after [PARK_MS]
 *   its slot goes to a camera on screen that waits for one.
 * - A player that keeps nothing worth keeping while paused ([CameraStream.closeWhenHidden]: WebRTC) is closed when
 *   the camera hides, and opened again when it shows.
 * - The card's [still] is hidden while a stream's picture covers it (HA's poster stays under the `<video>`): it isn't
 *   drawn under every video frame. Only where the picture fills its whole frame ([CameraFitFrame.coversFrame]): a
 *   letterboxed video (`contain` in a card of another shape) keeps the still in its bands, as HA's poster shows
 *   there. It shows again at once whenever nothing plays over it: the stream stops, fails, falls back, pauses, or
 *   stops drawing off screen; then the next picture drawn on screen hides it again.
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
    /** More Info's camera: HA's dialog plays over the dashboard, so it takes a slot from a card behind it if it must. */
    private val foreground: Boolean = false,
    /** The card's still under [host] ([onBitmap]'s view): hidden while a stream's picture covers it. */
    private val still: View? = null,
    private val onBitmap: (Bitmap) -> Unit,
) {
    private val pollMs = if (stream) POLL_MS else SNAPSHOT_MS

    /** What the connection knows of its cameras: their capabilities, a WebRTC that failed to connect. */
    private val knowledge = CameraKnowledge.of(ctx.store)

    /** The kinds whose fallback was logged this run: one line each. */
    private val logged = HashSet<CameraStreamKind>()

    /** [setActive]'s ask: the card shows. [active] is that and not [offScreenOut]. */
    private var shown = true

    /** The card is on screen ([setOnScreen]). Read on the players' threads too (their listener's wantsFrames). */
    @Volatile
    private var onScreen = true

    /** Off screen for [PARK_MS]: its slots give way to a camera on screen ([CameraVideoBudget.park]). */
    private var parked = false

    /** Off screen, its main player's slot went to a camera on screen: it rests, as hidden, until it's on screen again. */
    private var offScreenOut = false

    private val parkTask = Runnable {
        if (!running || !active || onScreen) return@Runnable
        parked = true
        held.toList().let { kinds -> if (kinds.isNotEmpty()) park(mainOf(kinds)) }
    }

    /** WebRTC has had [EXTRA_DELAY_MS] to show video: HLS may load beside it now. */
    private var extraDue = false
    private var extraDuePosted = false

    private val extraDueTask = Runnable {
        extraDuePosted = false
        extraDue = true
        recompute()
    }

    private var aloneDeadlinePosted = false

    private val aloneDeadline = Runnable {
        aloneDeadlinePosted = false
        if (!running || !active || webRtcVideo != null || slots[CameraStreamKind.WEB_RTC] == null) return@Runnable
        // WebRTC alone, still without video: it can't connect, most likely. HLS takes over, here and for a while on
        // the camera's other pictures.
        knowledge.webRtcFailed(entityId)
        log(CameraStreamKind.WEB_RTC, "no video within $ALONE_TIMEOUT_MS ms with no HLS beside it")
        closeSlot(CameraStreamKind.WEB_RTC)
        failed(CameraStreamKind.WEB_RTC)
    }

    private var running = false

    /** Read on the players' threads too (their listener's wantsFrames). */
    @Volatile
    private var active = true
    private var polling = false
    private var inFlight = false

    /** Frames a player delivered this run: a still requested before the latest one never covers it. */
    private var streamFrames = 0

    /** Bumped by [stop]: a snapshot or capabilities answer of an earlier run neither lands nor clears [inFlight]. */
    private var generation = 0

    /**
     * `camera/capabilities`' kinds; null until HA answers. HA's answer, an empty one included, is kept across runs (HA
     * asks again only for another entity); a request that failed is not: MJPEG plays meanwhile, and it's asked again.
     */
    private var capabilities: List<CameraStreamKind>? = null
    private var capabilitiesAsked = false

    /** The last `camera/capabilities` request failed: MJPEG plays until a later one answers. */
    private var capabilitiesFailed = false

    /** The wait before asking again after a failed request: doubled each time, up to [CAPABILITIES_RETRY_MAX_MS]. */
    private var capabilitiesRetryMs = CAPABILITIES_RETRY_MS

    private val askAgain = Runnable {
        if (running && active && capabilities == null) askCapabilities()
    }

    /** What HLS and WebRTC reported this run (HA's `hasVideo`): true with video, false failed, null loading. */
    private var hlsVideo: Boolean? = null
    private var webRtcVideo: Boolean? = null

    /** MJPEG failed: snapshots poll until [retry] opens it again. */
    private var mjpegDown = false

    /**
     * When [setActive] last hid it ([SystemClock.elapsedRealtime], which counts the device's deep sleep: a panel whose
     * screen slept for hours was hidden that long): a long hide tries what failed again on show.
     */
    private var hiddenAt = 0L

    /** [videoRetry] is posted. */
    private var videoRetryPosted = false

    private val videoRetry = Runnable {
        videoRetryPosted = false
        if (running && active) retryFailed()
    }

    /** No video slot was free: MJPEG plays until [CameraVideoBudget] calls back. */
    private var capped = false

    /**
     * A second video player (HLS beside WebRTC) had no slot of its own: the card plays the one it has until
     * [CameraVideoBudget] calls back. Each video player holds a slot: a decoder each.
     */
    private var starved = false

    /** The video kinds that hold a [CameraVideoBudget] slot. */
    private val held = HashSet<CameraStreamKind>()

    private class Slot(val stream: CameraStream, val frame: CameraFitFrame) {
        var hasFrame = false

        /**
         * A picture of this run is on screen now: drawn since the player started or resumed, and since the card came
         * back on screen. Until it is, the still shows under it and the player is asked for every frame
         * ([CameraStream.Listener.wantsFrames], read on its threads).
         */
        @Volatile
        var drawing = false

        /** The picture's size the card last took ([sizeFrom]), 0 × 0 before. */
        var width = 0
        var height = 0

        /** When the player's view got its surface ([SystemClock.elapsedRealtime]), or -1 while it has none. */
        var surfaceAt = -1L
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

    init {
        // Asked now, while the view builds: the answer is usually in when the card starts.
        if (stream) prefetchCapabilities()
    }

    val isPolling: Boolean get() = polling

    /** The kinds of player running now, in start order (tests, Clarity). */
    val runningKinds: List<CameraStreamKind> get() = slots.keys.toList()

    /** The kind on screen (the visible player that has drawn a frame), or null while only the still shows. */
    val shownKind: CameraStreamKind?
        get() = slots.entries.firstOrNull { (_, s) -> s.hasFrame && s.frame.alpha > 0f }?.key

    /**
     * A live camera whose capabilities this connection already knows (another picture of it asked, on this view or
     * another): it can start before its card is on screen, its players getting ready while the view builds.
     */
    val capabilitiesKnown: Boolean get() = stream && knowledge.capabilities(entityId) != null

    fun start() {
        if (running) return
        running = true
        live += this
        active = true
        shown = true
        offScreenOut = false
        parked = false
        extraDue = false
        logged.clear()
        streamFrames = 0
        hlsVideo = null
        webRtcVideo = null
        mjpegDown = false
        capped = false
        starved = false
        snapshot()
        if (!stream) {
            startPolling()
            return
        }
        // The connection's answer, the one it has now: a copy from before a reconnect isn't used (a camera without
        // the STREAM feature is read from its state again).
        capabilities = knowledge.capabilities(entityId)
        if (capabilities == null) capabilitiesAsked = false
        if (capabilities == null) askCapabilities() else recompute()
        if (!onScreen) schedulePark()
    }

    /** Hidden (false) or shown again (true): players pause and resume, and the video slot goes back meanwhile. */
    fun setActive(active: Boolean) {
        if (!running) return
        shown = active
        applyActive()
    }

    /**
     * The card is on screen (true) or scrolled out of sight (false). Off screen its players stop drawing at once (they
     * keep their connection, so they show again at once), and after [PARK_MS] its slot goes to the next camera on
     * screen that needs one; if one does, it rests as hidden until it's back on screen.
     */
    fun setOnScreen(onScreen: Boolean) {
        if (this.onScreen == onScreen) return
        this.onScreen = onScreen
        ctx.main.removeCallbacks(parkTask)
        for (kind in slots.keys.toList()) {
            val slot = slots[kind] ?: continue
            // Off screen the players stop drawing: the still is back under them, and stays until one draws again.
            if (!onScreen) slot.drawing = false
            if (guard(kind) { slot.stream.setRendering(onScreen) } == null) {
                closeSlot(kind)
                failed(kind)
            }
        }
        updateStill()
        if (!running) return
        if (!onScreen) {
            schedulePark()
            return
        }
        parked = false
        for (kind in held.toList()) CameraVideoBudget.park(this to kind, false)
        if (offScreenOut) {
            offScreenOut = false
            // Its slot went while it was still active (the pause was posted): it asks for one again.
            if (active) scheduleRecompute() else applyActive()
        }
    }

    private fun schedulePark() {
        ctx.main.removeCallbacks(parkTask)
        ctx.main.postDelayed(parkTask, PARK_MS)
    }

    /** [kind]'s slot (the main player's) gives way, while off screen, to a camera on screen that needs one. */
    private fun park(kind: CameraStreamKind) {
        CameraVideoBudget.park(this to kind, true) {
            held -= kind
            closeReplaced(kind)
            offScreenOut = true
            // Not from inside the budget's call: the rest of the camera rests as hidden, next.
            ctx.main.post { if (running && offScreenOut) applyActive() }
        }
    }

    /** Pauses or resumes the players as [shown] and [offScreenOut] say. */
    private fun applyActive() {
        val active = shown && !offScreenOut
        if (this.active == active) return
        this.active = active
        if (!active) {
            hiddenAt = SystemClock.elapsedRealtime()
            // Paused, nothing plays over the still; a resumed player shows a first frame again before it covers it.
            for (slot in slots.values) slot.drawing = false
            updateStill()
            stopPolling()
            cancelVideoRetry()
            cancelTimers()
            // Its slots go back: nothing to park until it shows again.
            ctx.main.removeCallbacks(parkTask)
            parked = false
            ctx.main.removeCallbacks(retry)
            ctx.main.removeCallbacks(askAgain)
            ctx.main.removeCallbacks(recomputeTask)
            recomputePosted = false
            // A player that can't pause is closed: it never keeps decoding once its slot goes back. (Not a failure of
            // the kind: a failed MJPEG would start polling, and nothing polls while the camera is hidden.) One that
            // keeps nothing worth keeping paused (WebRTC's) is closed too, and opened again on show.
            for (kind in slots.keys.toList()) {
                val slot = slots[kind] ?: continue
                if (slot.stream.closeWhenHidden) {
                    closeReplaced(kind)
                } else if (guard(kind) { slot.stream.setActive(false) } == null) {
                    closeSlot(kind)
                }
            }
            releaseSlots()
            capped = false
            return
        }
        if (!stream) {
            snapshot()
            startPolling()
            return
        }
        // Hidden long enough for the screen to have slept: what failed before may play now (HA restarted meanwhile).
        if (SystemClock.elapsedRealtime() - hiddenAt >= LONG_HIDE_MS) forgetFailures()
        // WebRTC connects afresh: HLS beside it waits its turn again.
        extraDue = false
        // Nothing of before is on screen (a closed player): a fresh still while the stream connects.
        if (slots.values.none { it.hasFrame }) snapshot()
        // A video camera takes its slot again first; without one it goes to MJPEG (recompute closes the rest). A camera
        // that was capped before it hid starts over too: recompute asks for a slot, or waits for one, from its plan now.
        capped = false
        starved = false
        val video = slots.keys.filter { it != CameraStreamKind.MJPEG }
        if (video.isNotEmpty()) {
            val main = mainOf(video)
            if (!acquireSlot(main, extra = false)) {
                capped = true
            } else {
                // An extra whose slot went meanwhile is closed; it comes back when a slot is spare (recompute).
                for (kind in video) if (kind != main && !acquireSlot(kind, extra = true)) {
                    closeSlot(kind)
                    starved = true
                }
            }
        }
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
        // Shown again after a failed capabilities request: ask now (the connection may be back).
        if (capabilities == null && !capabilitiesAsked) askCapabilities()
        recompute()
        if (!onScreen) schedulePark()
    }

    fun stop() {
        running = false
        live -= this
        stopPolling()
        cancelVideoRetry()
        cancelTimers()
        ctx.main.removeCallbacks(parkTask)
        parked = false
        offScreenOut = false
        inFlight = false
        generation++
        ctx.main.removeCallbacks(retry)
        ctx.main.removeCallbacks(recomputeTask)
        ctx.main.removeCallbacks(askAgain)
        recomputePosted = false
        // A capabilities request still out lands on nothing; the next start asks again.
        if (capabilities == null) capabilitiesAsked = false
        for (kind in slots.keys.toList()) closeSlot(kind)
        releaseSlots()
        capped = false
        updateStill()
    }

    /**
     * Home Assistant's connection is back after a drop. Each player hears it (a WebRTC stream waiting to reconnect tries
     * now); the kinds that failed meanwhile are tried again, from WebRTC down, and a failed capabilities request is
     * asked again now. A player that still plays is left alone. Hidden, it all waits for [setActive] true.
     */
    fun reconnected() {
        if (!running) return
        for ((kind, slot) in slots.entries.toList()) {
            if (guard(kind) { slot.stream.connectionRestored() } == null) {
                closeSlot(kind)
                failed(kind)
            }
        }
        if (!stream) return
        // HA may have restarted with other settings: its answer is asked again (the players run on meanwhile), now,
        // or when the camera shows.
        capabilities = null
        capabilitiesAsked = false
        if (capabilities == null && !capabilitiesAsked && active) {
            ctx.main.removeCallbacks(askAgain)
            capabilitiesRetryMs = CAPABILITIES_RETRY_MS
            askCapabilities()
        }
        retryFailed()
    }

    /** Forgets what failed and runs the choice again: true when something had. WebRTC goes first again, alone a while. */
    private fun retryFailed() {
        if (!forgetFailures()) return
        extraDue = false
        cancelTimers()
        recompute()
    }

    /** HLS's and WebRTC's failures and MJPEG's go: the choice starts over from what HA offers. True when any did. */
    private fun forgetFailures(): Boolean {
        var any = false
        if (hlsVideo == false) {
            hlsVideo = null
            any = true
        }
        if (webRtcVideo == false) {
            webRtcVideo = null
            knowledge.webRtcPlayed(entityId)
            any = true
        }
        if (mjpegDown) {
            mjpegDown = false
            ctx.main.removeCallbacks(retry)
            any = true
        }
        return any
    }

    private fun cancelVideoRetry() {
        if (!videoRetryPosted) return
        videoRetryPosted = false
        ctx.main.removeCallbacks(videoRetry)
    }

    /** The waits that belong to a run of the players: HLS's turn beside WebRTC, WebRTC's deadline alone. */
    private fun cancelTimers() {
        if (extraDuePosted) {
            extraDuePosted = false
            ctx.main.removeCallbacks(extraDueTask)
        }
        cancelAloneDeadline()
    }

    private fun cancelAloneDeadline() {
        if (!aloneDeadlinePosted) return
        aloneDeadlinePosted = false
        ctx.main.removeCallbacks(aloneDeadline)
    }

    /** HA's frontend doesn't ask a camera without the STREAM feature for its capabilities. */
    private fun offersStream(): Boolean {
        val features = (ctx.store[entityId]?.attributes?.get("supported_features") as? Number)?.toInt() ?: 0
        return features and CameraStreamChoice.FEATURE_STREAM != 0
    }

    /**
     * Sends `camera/capabilities` for [entityId]; the answer comes on the main thread. Once per request, whoever
     * waits for it: when HA offers WebRTC, its player gets ready while the rest of the view builds.
     */
    private fun sendCapabilities(answer: (Boolean, Any?) -> Unit) {
        ctx.callWs(JSONObject().put("type", "camera/capabilities").put("entity_id", entityId)) { ok, result, _ ->
            // On the main thread, as CameraStreamFactory.prewarm() asks: the factory starts its own background work,
            // first thing when the answer comes through, before the players that need it.
            ctx.main.post {
                if (ok) prewarmFor(CameraStreamChoice.kindsOf(result))
                answer(ok, result)
            }
        }
    }

    /** Asks for the capabilities before the card is on screen ([knowledge] keeps the answer for [start]). */
    private fun prefetchCapabilities() {
        if (knowledge.capabilities(entityId) != null || !offersStream()) return
        knowledge.ask(entityId, ::sendCapabilities) { _, _ -> }
    }

    /**
     * HA offers WebRTC for the camera: its player gets ready while the rest of the view builds. Main thread: the
     * factory's prewarm runs its work on a thread of its own.
     */
    private fun prewarmFor(kinds: List<CameraStreamKind>) {
        if (CameraStreamKind.WEB_RTC !in kinds) return
        val players = ctx.cameraPlayers ?: return
        if (players.supports(CameraStreamKind.WEB_RTC)) runCatching { players.prewarm(CameraStreamKind.WEB_RTC) }
    }

    private fun askCapabilities() {
        if (capabilitiesAsked) return
        capabilitiesAsked = true
        if (!offersStream()) {
            // HA's frontend doesn't ask a camera without the STREAM feature: MJPEG.
            capabilities = emptyList()
            recompute()
            return
        }
        val asked = generation
        knowledge.ask(entityId, ::sendCapabilities) { ok, kinds ->
            run {
                if (asked != generation) return@run
                if (ok && kinds != null) {
                    capabilities = kinds
                    capabilitiesFailed = false
                    capabilitiesRetryMs = CAPABILITIES_RETRY_MS
                } else {
                    // HA's frontend shows no stream when this fails; every camera has MJPEG, so Vitro plays that now.
                    // A failure is often passing (the connection dropped): not kept, asked again after a while.
                    capabilitiesFailed = true
                    capabilitiesAsked = false
                    if (running && active) {
                        ctx.main.removeCallbacks(askAgain)
                        ctx.main.postDelayed(askAgain, capabilitiesRetryMs)
                        capabilitiesRetryMs = (capabilitiesRetryMs * 2).coerceAtMost(CAPABILITIES_RETRY_MAX_MS)
                    }
                }
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
        val offered = capabilities ?: (if (capabilitiesFailed) emptyList() else return)
        val supported = CameraStreamChoice.playable(offered) { ctx.cameraPlayers?.supports(it) == true }
        val both = CameraStreamKind.HLS in supported && CameraStreamKind.WEB_RTC in supported
        // WebRTC failed to connect a moment ago, on this picture of the camera or another: HLS first.
        if (both && webRtcVideo == null && knowledge.webRtcFailedRecently(entityId)) webRtcVideo = false
        var plan = CameraStreamChoice.streams(supported, hlsVideo, webRtcVideo)
        if (both && webRtcVideo == true && hlsVideo == null) {
            // WebRTC has video, which HA settles on once HLS has loaded too (muted): HLS needn't load.
            plan = plan.filter { it.kind != CameraStreamKind.HLS }
        } else if (both && webRtcVideo == null && hlsVideo == null && !extraDue) {
            // Both loading: WebRTC alone, shown, for its first [EXTRA_DELAY_MS]; HLS joins if it has no video by then.
            plan = listOf(PlannedStream(CameraStreamKind.WEB_RTC, true))
            if (!extraDuePosted) {
                extraDuePosted = true
                ctx.main.postDelayed(extraDueTask, EXTRA_DELAY_MS)
            }
        }
        val video = plan.filter { it.kind != CameraStreamKind.MJPEG }
        if (video.isNotEmpty()) {
            // A player the plan dropped gives its slot back first, so the camera's next kind (HLS after WebRTC failed)
            // can take it.
            for (kind in held.toList()) if (video.none { it.kind == kind }) {
                closeSlot(kind)
                releaseSlot(kind)
            }
            // The main player (the kind HA settles on) takes its slot first; the one HA loads beside it is an extra.
            val main = mainOf(video.map { it.kind })
            val extra = video.firstOrNull { it.kind != main }?.kind
            if (capped || !acquireSlot(main, extra = false)) {
                capped = true
                releaseHeld()
                plan = listOf(PlannedStream(CameraStreamKind.MJPEG, true))
            } else {
                starved = extra != null && !acquireSlot(extra, extra = true)
                if (starved) {
                    // No spare slot: the main player alone, shown (clear until its first frame, over the still).
                    plan = plan.filter { it.kind != extra }.map { if (it.kind == main) it.copy(visible = true) else it }
                }
            }
        } else {
            releaseSlots()
        }
        val wanted = plan.associateBy { it.kind }
        // MJPEG on screen stays until what replaces it draws (a slot freed for video, capabilities known at last): the
        // video players are clear until their first frame and lie over it, so the card never blanks meanwhile.
        val bridge = slots[CameraStreamKind.MJPEG]?.hasFrame == true && CameraStreamKind.MJPEG !in wanted &&
            plan.none { p -> p.visible && slots[p.kind]?.hasFrame == true }
        for (kind in slots.keys.toList()) {
            if (kind !in wanted && !(bridge && kind == CameraStreamKind.MJPEG)) closeSlot(kind)
        }
        for (p in plan) {
            if (p.kind == CameraStreamKind.MJPEG && mjpegDown) continue
            val slot = slots[p.kind] ?: open(p.kind) ?: continue
            slot.frame.alpha = if (p.visible) 1f else 0f
            if (p.visible && slot.hasFrame) sizeFrom(slot, force = true)
        }
        updateStill()
        // The still polls only while nothing streams: once MJPEG (or video) draws, its frames are the picture.
        if (slots.values.any { it.hasFrame && it.frame.alpha > 0f }) stopPolling()
        // WebRTC alone (no slot for HLS, its turn not come yet, or HLS not loaded) gets a shorter wait for video when
        // HLS could take over.
        val alone = extraDue && webRtcVideo == null && hlsVideo == null && both &&
            slots[CameraStreamKind.WEB_RTC] != null && slots[CameraStreamKind.HLS] == null
        if (!alone) {
            cancelAloneDeadline()
        } else if (!aloneDeadlinePosted) {
            // Counted from the player's surface, the first moment a frame could be on screen: not from its start, which
            // [VitroView] puts inside the view's build (a heavy view's build on the S1 took seconds of it). A view not
            // laid out yet waits for its surface ([CameraStream.Listener.onSurfaceReady] recomputes); the player's
            // own timeout still bounds a surface that never comes.
            val since = slots[CameraStreamKind.WEB_RTC]?.surfaceAt ?: -1L
            if (since >= 0) {
                aloneDeadlinePosted = true
                ctx.main.postDelayed(aloneDeadline, (since + ALONE_TIMEOUT_MS - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
            }
        }
        // On MJPEG because the camera's video failed (not for want of a slot): video is tried again after a while.
        val videoFailed = !capped && video.isEmpty() && supported.any { it != CameraStreamKind.MJPEG }
        if (!videoFailed) {
            cancelVideoRetry()
        } else if (!videoRetryPosted) {
            videoRetryPosted = true
            ctx.main.postDelayed(videoRetry, VIDEO_RETRY_MS)
        }
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
        // A letterboxed picture leaves the still showing in its bands: it's hidden only once the picture fills the frame.
        frame.onCoverChanged = { if (slots[kind] === slot) updateStill() }
        slots[kind] = slot
        val ok = guard(kind) {
            frame.addView(created.view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            requireNotNull(target).addView(frame, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            created.listener = listenerFor(kind, slot)
            if (created.surfaceReady) slot.surfaceAt = SystemClock.elapsedRealtime()
            created.start()
            if (!onScreen) created.setRendering(false)
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

        /** Each frame only until this run's picture is on screen: then the still is covered, and the size known. */
        override val wantsFrames: Boolean get() = !slot.drawing && onScreen && active

        override fun onFirstFrame(sinceStartMs: Long) {
            if (!current()) return
            slot.hasFrame = true
            slot.drawing = true
            streamFrames++
            when (kind) {
                CameraStreamKind.HLS -> hlsVideo = true
                CameraStreamKind.WEB_RTC -> {
                    webRtcVideo = true
                    knowledge.webRtcPlayed(entityId)
                }
                CameraStreamKind.MJPEG -> Unit
            }
            sizeFrom(slot, force = true)
            if (slot.frame.alpha > 0f) stopPolling()
            updateStill()
            scheduleRecompute()
        }

        override fun onFrameRendered() {
            if (!current()) return
            streamFrames++
            // Its size is read when it changes, not every frame (a player that skips frames says so: onVideoSize).
            sizeFrom(slot)
            if (!slot.drawing && onScreen) {
                slot.drawing = true
                updateStill()
            }
        }

        override fun onVideoSize() {
            if (current() && slot.hasFrame) sizeFrom(slot)
        }

        override fun onSurfaceReady() {
            if (!current()) return
            // A new surface (the first, or one after the last went): WebRTC's wait for video starts over from it.
            slot.surfaceAt = SystemClock.elapsedRealtime()
            if (kind == CameraStreamKind.WEB_RTC) cancelAloneDeadline()
            scheduleRecompute()
        }

        override fun onSurfaceLost() {
            if (!current()) return
            // No surface, no frame on screen: the wait stops until the next one ([onSurfaceReady]).
            slot.surfaceAt = -1L
            if (kind == CameraStreamKind.WEB_RTC) cancelAloneDeadline()
        }

        override fun onError(reason: String) {
            if (!current()) return
            // WebRTC that never showed video, and not for want of HA's connection: it can't connect. HLS first for a
            // while, on every picture of the camera.
            if (kind == CameraStreamKind.WEB_RTC && webRtcVideo != true && !notConnected(reason)) knowledge.webRtcFailed(entityId)
            log(kind, reason)
            closeSlot(kind)
            failed(kind)
        }

        override fun onEnded() = onError("ended")
    }

    /**
     * The card takes [slot]'s picture size when it changed ([force]: and when its player is shown, or drew its first
     * frame, as the card may have taken another player's size meanwhile).
     */
    private fun sizeFrom(slot: Slot, force: Boolean = false) {
        val sized = slot.stream as? CameraStreamVideoSize ?: return
        val w = sized.videoWidth
        val h = sized.videoHeight
        if (w <= 0 || h <= 0) return
        if (!force && w == slot.width && h == slot.height) return
        slot.width = w
        slot.height = h
        slot.frame.setVideoSize(w, h)
        if (slot.frame.alpha > 0f) onVideoSize?.invoke(w, h)
    }

    /** Whether the still shows now ([still]): hidden while a shown player's picture of this run is on screen. */
    private var stillHidden = false

    /**
     * Hides the still while a stream covers it, and shows it again as soon as nothing does. INVISIBLE, not GONE:
     * the card's layout (and a card sized to the still's shape) stays as it is.
     */
    private fun updateStill() {
        val view = still ?: return
        val covered = running && active && onScreen &&
            slots.values.any { it.drawing && it.hasFrame && it.frame.alpha > 0f && it.frame.coversFrame }
        if (covered == stillHidden) return
        stillHidden = covered
        view.visibility = if (covered) View.INVISIBLE else View.VISIBLE
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

    /**
     * Closes [kind]'s player for a new one later (hidden, its slot given up): that one has shown nothing yet, so the
     * kind is loading again, as a new player is. A failure stays.
     */
    private fun closeReplaced(kind: CameraStreamKind) {
        closeSlot(kind)
        if (kind == CameraStreamKind.WEB_RTC && webRtcVideo == true) webRtcVideo = null
        if (kind == CameraStreamKind.HLS && hlsVideo == true) hlsVideo = null
    }

    private fun closeSlot(kind: CameraStreamKind) {
        val slot = slots.remove(kind) ?: return
        slot.drawing = false
        slot.frame.onCoverChanged = null
        // The still first, so no frame passes with neither the player's picture nor the still.
        updateStill()
        slot.stream.listener = null
        guard(kind) { slot.stream.close() }
        runCatching { (slot.frame.parent as? ViewGroup)?.removeView(slot.frame) }
    }

    /**
     * A [CameraVideoBudget] slot for this camera's [kind] player (one per video player). An [extra] (the player HA
     * loads beside the main one) gives its slot up to a camera that needs one for its main player: it's closed at
     * once, and the main player goes on alone.
     */
    private fun acquireSlot(kind: CameraStreamKind, extra: Boolean): Boolean {
        // A player held as an extra and now the main one (HLS once WebRTC failed) asks the budget to promote it.
        if (kind in held && extra) return true
        val onYield = if (!extra) null else ({
            held -= kind
            closeSlot(kind)
            starved = true
            scheduleRecompute()
        })
        // A card's main player gives its slot to More Info's camera when that one finds every slot held: the card
        // plays MJPEG meanwhile, first in line for the next slot.
        val onPreempt = if (extra || foreground) null else ({
            held -= kind
            closeReplaced(kind)
            capped = true
            scheduleRecompute()
        })
        val ok = CameraVideoBudget.acquire(this to kind, extra, onYield, foreground, onPreempt) {
            // A slot freed: a camera that played MJPEG, or its main player alone, for want of one takes up video again.
            ctx.main.post {
                if (running && active && (capped || starved)) {
                    capped = false
                    starved = false
                    recompute()
                }
            }
        }
        if (ok) {
            held += kind
            // Off screen long enough already: the new slot gives way to a camera on screen too.
            if (parked && !extra) park(kind)
        }
        return ok
    }

    private fun releaseSlot(kind: CameraStreamKind) {
        held -= kind
        CameraVideoBudget.release(this to kind)
    }

    /**
     * The camera's main player among [kinds]: WebRTC when it's there, the kind HA's `ha-camera-stream` settles on once
     * both have loaded (`_streams`, frontend 20261028.8); else the one video kind.
     */
    private fun mainOf(kinds: List<CameraStreamKind>): CameraStreamKind =
        if (CameraStreamKind.WEB_RTC in kinds) CameraStreamKind.WEB_RTC else kinds.first()

    /** Gives back the slots held, keeping this camera's place among the waiters. */
    private fun releaseHeld() {
        for (kind in held.toList()) releaseSlot(kind)
    }

    /** Gives back every slot and stops waiting for one. */
    private fun releaseSlots() {
        for (kind in CameraStreamKind.values()) if (kind != CameraStreamKind.MJPEG) releaseSlot(kind)
    }

    /**
     * One line per kind and run when a player fails, with its error code only: HA's messages can carry a camera's
     * stream address, and a WebRTC one its network's.
     */
    private fun log(kind: CameraStreamKind, reason: String) {
        if (!logged.add(kind)) return
        runCatching { Log.i(TAG, "$entityId: ${kind.name.lowercase()} fell back (${codeOf(reason)})") }
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
        private const val TAG = "VitroCamera"

        const val POLL_MS = 1_500L

        /** How long WebRTC plays alone before HLS loads beside it, if it has no video by then. */
        const val EXTRA_DELAY_MS = 1_500L

        /** How long WebRTC playing alone has to show video, from its view's surface, when HLS could take over. */
        const val ALONE_TIMEOUT_MS = 8_000L

        /** How long a card is off screen before its slot gives way to a camera on screen. */
        const val PARK_MS = 2_000L

        /** HA's `hui-image` refresh of a camera snapshot (`camera_view: auto`). */
        const val SNAPSHOT_MS = 10_000L
        const val RETRY_MS = 10_000L

        /** The first wait before asking `camera/capabilities` again after a failed request, and the longest. */
        const val CAPABILITIES_RETRY_MS = 10_000L
        const val CAPABILITIES_RETRY_MAX_MS = 300_000L

        /** A hide this long (HA's frontend closes a hidden page's WebRTC after 60 s) tries what failed again on show. */
        const val LONG_HIDE_MS = 60_000L

        /** How often a camera whose video failed tries it again while it plays MJPEG. */
        const val VIDEO_RETRY_MS = 300_000L

        /** The feeds started and not stopped, for [connectionRestored]. Main thread. */
        private val live = LinkedHashSet<LiveCameraFeed>()

        /**
         * Home Assistant's connection is back after a drop: every running feed of the engine whose entities are [store]
         * (the cards' and More Info's, whichever context built them) hears it ([reconnected]). Main thread.
         */
        fun connectionRestored(store: EntityStore) {
            CameraKnowledge.of(store).forget()
            for (feed in live.toList()) if (feed.ctx.store === store) feed.reconnected()
        }

        /** The error codes leading a stream's reason (`start_failed: not_connected: …`): a word each, nothing else. */
        private fun codesOf(reason: String): List<String> =
            reason.split(": ").take(2).takeWhile { it.matches(CODE) }

        internal fun codeOf(reason: String): String = codesOf(reason).joinToString(": ").ifEmpty { "error" }

        /** The stream failed because there was no connection to Home Assistant, which isn't the camera failing. */
        internal fun notConnected(reason: String): Boolean = "not_connected" in codesOf(reason)

        private val CODE = Regex("[a-z][a-z0-9_]{0,39}")
    }
}
