/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.camera

import dev.glassworks.engine.vitro.clarity.ClarityCameras
import dev.glassworks.engine.vitro.net.MjpegStreamPlayer

/**
 * The players a dashboard's cameras use, one [CameraStreamFactory] per kind: what [CameraStreamFactories] has
 * registered for a kind, else Vitro's own HLS and MJPEG players. WebRTC has none until its player registers.
 */
class CameraPlayers(private val factories: Map<CameraStreamKind, CameraStreamFactory>) : CameraStreamFactory {
    /** True when [kind] has a player here. */
    fun supports(kind: CameraStreamKind): Boolean = factories.containsKey(kind)

    override fun create(entityId: String, kind: CameraStreamKind): CameraStream? = factories[kind]?.create(entityId, kind)

    override fun create(entityId: String, kind: CameraStreamKind, overlay: Boolean): CameraStream? =
        factories[kind]?.create(entityId, kind, overlay)

    override fun supportsOverlay(kind: CameraStreamKind): Boolean = factories[kind]?.supportsOverlay(kind) == true

    /** [kind]'s player gets ready ([CameraStreamFactory.prewarm]): a camera is about to play it. */
    fun prewarm(kind: CameraStreamKind) {
        factories[kind]?.prewarm()
    }

    companion object {
        /**
         * The players for [host]'s Home Assistant. [entityPicture] is a camera's current `entity_picture`, whose
         * signed token the MJPEG stream carries (HA's `computeMJPEGStreamUrl` uses the same `access_token`).
         */
        fun forHost(host: CameraStreamHost, entityPicture: (String) -> String?): CameraPlayers {
            val builtIn = mapOf(
                CameraStreamKind.HLS to object : CameraStreamFactory {
                    override fun create(entityId: String, kind: CameraStreamKind): CameraStream = HlsCameraStream(host, entityId)
                },
                CameraStreamKind.MJPEG to object : CameraStreamFactory {
                    override fun create(entityId: String, kind: CameraStreamKind): CameraStream? {
                        val url = MjpegStreamPlayer.streamUrl(host.haBase, entityId, entityPicture(entityId)) ?: return null
                        return MjpegCameraStream(host.context, url, host.accessToken)
                    }
                },
            )
            val out = java.util.EnumMap<CameraStreamKind, CameraStreamFactory>(CameraStreamKind::class.java)
            for (kind in CameraStreamKind.values()) {
                val registered = CameraStreamFactories.provider(kind)?.let { provider -> runCatching { provider(host) }.getOrNull() }
                (registered ?: builtIn[kind])?.let { out[kind] = measured(it) }
            }
            return CameraPlayers(out)
        }

        /** Every kind's factory reports its streams to Clarity's camera task while a run measures. */
        private fun measured(factory: CameraStreamFactory): CameraStreamFactory = ClarityCameras.measured(factory)
    }
}

/**
 * How many video players (HLS or WebRTC, which take a hardware decoder each) run at once on this panel. HA's
 * frontend has no such cap: a browser on a desktop has decoders to spare, a wall panel's SoC has a few. A slot is a
 * video player, not a camera. Only active cameras count: one in a hidden view, or behind HA's own page, gives its
 * slots back. Main thread only.
 *
 * Four, the most Clarity's camera views show at once (four `clarity_cam_*` cards): four cameras on WebRTC are four
 * decoders. Each camera's **main** player comes first: the kind HA settles on (WebRTC when the camera offers it, as
 * `ha-camera-stream`'s `_streams` picks WebRTC once both have loaded, frontend 20261028.8). The second player HA loads
 * side by side while WebRTC connects (HLS) is an **extra**: it takes a slot only when one is free and no camera waits
 * for its main player, and gives its slot up as soon as one does ([acquire] closes it first, so the players open
 * never exceed the cap). Past the cap a camera plays HA's MJPEG stream (decoded as JPEGs) and takes up video as soon
 * as a slot frees: every waiting camera is told, those waiting for a main player first.
 *
 * Two kinds of holder give way to a camera the user is looking at:
 * - A camera scrolled off screen ([park]) keeps playing, but its slot goes to a camera on screen that waits for one,
 *   before an extra's does.
 * - The camera of an open More Info sheet is a **foreground** one: HA's dialog plays its own stream over the
 *   dashboard, so with every slot held by the cards behind it, the sheet's main player takes the slot granted last
 *   among them ([acquire]'s `onPreempt`). That card plays MJPEG meanwhile, first in line for the next free slot, so
 *   it takes video up again when the sheet closes.
 */
object CameraVideoBudget {
    const val MAX_VIDEO_CAMERAS = 4

    /** The cap (tests lower it). */
    var max: Int = MAX_VIDEO_CAMERAS

    /**
     * A slot's holder. An extra, or a main player [parked] off screen, gives its slot up through [onYield], which
     * closes its player; a main player that isn't [foreground] gives it up to a foreground one through [onPreempt].
     * [onFree] is how it hears of a free slot once it gave one up.
     */
    private class Holder(
        val extra: Boolean,
        val onYield: (() -> Unit)?,
        val foreground: Boolean,
        val onPreempt: (() -> Unit)?,
        val onFree: () -> Unit,
    ) {
        var parked = false
        var parkedYield: (() -> Unit)? = null
    }

    private val holders = LinkedHashMap<Any, Holder>()

    /** Cameras waiting for a main player's slot, longest first. */
    private var waiters = LinkedHashMap<Any, () -> Unit>()

    /** Cameras that would load a second player side by side if a slot were spare. */
    private val extraWaiters = LinkedHashMap<Any, () -> Unit>()

    val inUse: Int get() = holders.size

    /** The slots extras hold (tests). */
    val extrasInUse: Int get() = holders.values.count { it.extra }

    /** Cameras waiting for a main player's slot (tests). */
    val waiting: Int get() = waiters.size

    /**
     * Takes a slot for [owner] (true), or false: [onFree] runs once one frees.
     *
     * - A main player ([extra] false) takes a free slot, else a parked holder's, else an extra's: that holder's
     *   [onYield] runs first (it closes its player), then the slot is [owner]'s. A [foreground] one then takes the
     *   slot granted last among main players that aren't: its holder's [onPreempt] runs (it must close its player and
     *   must not call [release] or [acquire]), and that holder waits first in line, its [onFree] kept. With none to
     *   take, it waits.
     * - An extra ([extra] true) takes a free slot only while no camera waits for a main player, and gives it up when
     *   one asks ([onYield], which must close the player and must not call [release]).
     * - An extra already held and asked for as a main player becomes a main holder.
     */
    fun acquire(
        owner: Any,
        extra: Boolean = false,
        onYield: (() -> Unit)? = null,
        foreground: Boolean = false,
        onPreempt: (() -> Unit)? = null,
        onFree: () -> Unit,
    ): Boolean {
        holders[owner]?.let { held ->
            // An extra that became its camera's main player (HLS once WebRTC failed) is a main holder from now on:
            // it's never taken for another camera's main player.
            if (held.extra && !extra) holders[owner] = Holder(false, null, foreground, onPreempt, onFree)
            return true
        }
        if (holders.size < max && (!extra || waiters.isEmpty())) {
            grant(owner, Holder(extra, onYield, foreground, if (extra) null else onPreempt, onFree))
            return true
        }
        if (!extra) {
            val yielding = holders.entries.firstOrNull { it.value.parked } ?: holders.entries.firstOrNull { it.value.extra }
            if (yielding != null) {
                holders.remove(yielding.key)
                val h = yielding.value
                runCatching { (if (h.parked) h.parkedYield else h.onYield)?.invoke() }
                grant(owner, Holder(false, null, foreground, onPreempt, onFree))
                return true
            }
            if (foreground) {
                val preempted = holders.entries.lastOrNull { !it.value.foreground && !it.value.extra && it.value.onPreempt != null }
                if (preempted != null) {
                    holders.remove(preempted.key)
                    // First in line for the next free slot: the sheet's, when it closes.
                    waiters = LinkedHashMap<Any, () -> Unit>().apply {
                        put(preempted.key, preempted.value.onFree)
                        putAll(waiters)
                    }
                    runCatching { preempted.value.onPreempt?.invoke() }
                    grant(owner, Holder(false, null, true, null, onFree))
                    return true
                }
            }
            waiters[owner] = onFree
        } else {
            extraWaiters[owner] = onFree
        }
        return false
    }

    private fun grant(owner: Any, holder: Holder) {
        holders[owner] = holder
        waiters.remove(owner)
        extraWaiters.remove(owner)
    }

    /**
     * [owner]'s camera is off screen ([parked] true) or back on it (false). Parked, its slot goes to the next camera
     * that needs one for its main player: [onYield] closes the player (it must not call [release] or [acquire]), at
     * once when one waits already. Nothing happens to an owner that holds no slot.
     */
    fun park(owner: Any, parked: Boolean, onYield: (() -> Unit)? = null) {
        val h = holders[owner] ?: return
        h.parked = parked
        h.parkedYield = if (parked) onYield else null
        if (!parked || waiters.isEmpty()) return
        holders.remove(owner)
        runCatching { onYield?.invoke() }
        tellWaiters()
    }

    /**
     * [owner] gives its slot back (or stops waiting for one). Every waiter is told, those waiting for a main player
     * first, longest first: each asks again ([acquire] keeps the cap, and waits again when the slot is gone), so one
     * that no longer wants a slot (hidden meanwhile) never keeps it from the others.
     */
    fun release(owner: Any) {
        waiters.remove(owner)
        extraWaiters.remove(owner)
        if (holders.remove(owner) == null) return
        tellWaiters()
    }

    private fun tellWaiters() {
        val told = waiters.values.toList() + extraWaiters.values.toList()
        waiters.clear()
        extraWaiters.clear()
        for (onFree in told) onFree()
    }

    /** Tests: no camera holds or waits for a slot, and the cap is back to its default. */
    internal fun reset() {
        holders.clear()
        waiters.clear()
        extraWaiters.clear()
        max = MAX_VIDEO_CAMERAS
    }
}
