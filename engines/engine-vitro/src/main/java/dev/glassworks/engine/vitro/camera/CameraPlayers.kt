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
 * How many cameras play video (HLS or WebRTC, which take a hardware decoder each) at once on this panel. HA's
 * frontend has no such cap: a browser on a desktop has decoders to spare, a wall panel's SoC has a few. Past it a
 * camera plays HA's MJPEG stream instead (decoded as JPEGs, as every camera did before HLS), and takes up video as
 * soon as a slot frees (every waiting camera is told and asks again). A camera that loads HLS and WebRTC side by side, as HA does, holds one slot.
 *
 * Four, the most Clarity's camera views show at once (four `clarity_cam_*` cards). Only active cameras count: one
 * in a hidden view, or behind HA's own page, gives its slot back. Main thread only.
 */
object CameraVideoBudget {
    const val MAX_VIDEO_CAMERAS = 4

    /** The cap (tests lower it). */
    var max: Int = MAX_VIDEO_CAMERAS

    private val holders = LinkedHashSet<Any>()
    private val waiters = LinkedHashMap<Any, () -> Unit>()

    val inUse: Int get() = holders.size

    /** Takes a slot for [owner] (true), or false when all are taken: [onFree] runs once one frees. */
    fun acquire(owner: Any, onFree: () -> Unit): Boolean {
        if (owner in holders) return true
        if (holders.size < max) {
            holders += owner
            waiters.remove(owner)
            return true
        }
        waiters[owner] = onFree
        return false
    }

    /**
     * [owner] gives its slot back (or stops waiting for one). Every waiter is told, longest first: each asks again
     * ([acquire] keeps the cap, and waits again when the slot is gone), so one that no longer wants a slot (hidden
     * meanwhile) never keeps it from the others.
     */
    fun release(owner: Any) {
        waiters.remove(owner)
        if (!holders.remove(owner)) return
        val told = waiters.values.toList()
        waiters.clear()
        for (onFree in told) onFree()
    }

    /** Tests: no camera holds or waits for a slot, and the cap is back to its default. */
    internal fun reset() {
        holders.clear()
        waiters.clear()
        max = MAX_VIDEO_CAMERAS
    }
}
