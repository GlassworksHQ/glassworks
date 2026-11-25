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
 */
object CameraVideoBudget {
    const val MAX_VIDEO_CAMERAS = 4

    /** The cap (tests lower it). */
    var max: Int = MAX_VIDEO_CAMERAS

    /** A slot's holder: an extra gives its slot up through [onYield], which closes its player. */
    private class Holder(val extra: Boolean, val onYield: (() -> Unit)?)

    private val holders = LinkedHashMap<Any, Holder>()

    /** Cameras waiting for a main player's slot, longest first. */
    private val waiters = LinkedHashMap<Any, () -> Unit>()

    /** Cameras that would load a second player side by side if a slot were spare. */
    private val extraWaiters = LinkedHashMap<Any, () -> Unit>()

    val inUse: Int get() = holders.size

    /** The slots extras hold (tests). */
    val extrasInUse: Int get() = holders.values.count { it.extra }

    /**
     * Takes a slot for [owner] (true), or false: [onFree] runs once one frees.
     *
     * - A main player ([extra] false) takes a free slot, else an extra's: that holder's [onYield] runs first (it closes
     *   its player), then the slot is [owner]'s. With none to take, it waits.
     * - An extra ([extra] true) takes a free slot only while no camera waits for a main player, and gives it up when
     *   one asks ([onYield], which must close the player and must not call [release]).
     */
    fun acquire(owner: Any, extra: Boolean = false, onYield: (() -> Unit)? = null, onFree: () -> Unit): Boolean {
        holders[owner]?.let { return true }
        if (holders.size < max && (!extra || waiters.isEmpty())) {
            grant(owner, extra, onYield)
            return true
        }
        if (!extra) {
            val yielding = holders.entries.firstOrNull { it.value.extra }
            if (yielding != null) {
                holders.remove(yielding.key)
                runCatching { yielding.value.onYield?.invoke() }
                grant(owner, false, null)
                return true
            }
            waiters[owner] = onFree
        } else {
            extraWaiters[owner] = onFree
        }
        return false
    }

    private fun grant(owner: Any, extra: Boolean, onYield: (() -> Unit)?) {
        holders[owner] = Holder(extra, onYield)
        waiters.remove(owner)
        extraWaiters.remove(owner)
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
