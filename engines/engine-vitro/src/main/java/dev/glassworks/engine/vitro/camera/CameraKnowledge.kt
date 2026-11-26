/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.camera

import android.os.SystemClock
import dev.glassworks.engine.vitro.data.EntityStore
import java.util.WeakHashMap

/**
 * What one Home Assistant connection's cameras have shown about themselves, shared by every live picture of them (the
 * cards of every view, a rebuilt card, More Info): one per [EntityStore], forgotten when the connection comes back
 * after a drop ([forget]), since HA may have restarted with other settings. Main thread only.
 *
 * - **Capabilities.** A camera's `camera/capabilities` answer, asked once and kept ([capabilities], [ask]): HA's
 *   frontend asks again for each `ha-camera-stream`, but the answer only changes with HA's settings, and every
 *   request on a busy view waits behind its build. Requests for the same camera at once share one.
 * - **WebRTC that can't connect.** A camera whose WebRTC failed to connect ([webRtcFailed]: HA in Docker's bridge
 *   network, go2rtc's candidates on another network, no TURN) plays HLS straight away for [WEBRTC_FAILED_FOR_MS]
 *   after ([webRtcFailedRecently]), rather than show its still while WebRTC times out again on each rebuild. HA's
 *   frontend runs HLS beside WebRTC so it never waits; a panel with four decoders can't always.
 */
class CameraKnowledge internal constructor(private val now: () -> Long = SystemClock::elapsedRealtime) {
    private val capabilities = HashMap<String, List<CameraStreamKind>>()
    private val asking = HashMap<String, MutableList<(ok: Boolean, kinds: List<CameraStreamKind>?) -> Unit>>()
    private val webRtcFailedAt = HashMap<String, Long>()

    /** The [entityId]'s capabilities HA gave on this connection, or null when not known yet. */
    fun capabilities(entityId: String): List<CameraStreamKind>? = capabilities[entityId]

    /**
     * Asks HA for [entityId]'s capabilities through [send] (`camera/capabilities`; it answers on the main thread with
     * success and HA's result), unless a request for it is out already: [done] then hears that one's answer. A
     * success is kept; a failure isn't, so the next [ask] sends again. A reply to a request sent before [forget]
     * reaches no one: it may be the old Home Assistant's, and the askers ask again once the connection is back.
     */
    fun ask(entityId: String, send: (answer: (ok: Boolean, result: Any?) -> Unit) -> Unit, done: (ok: Boolean, kinds: List<CameraStreamKind>?) -> Unit) {
        capabilities[entityId]?.let { return done(true, it) }
        asking[entityId]?.let {
            it += done
            return
        }
        val waiting = mutableListOf(done)
        asking[entityId] = waiting
        send { ok, result ->
            if (asking[entityId] !== waiting) return@send
            asking.remove(entityId)
            val kinds = if (ok) CameraStreamChoice.kindsOf(result) else null
            if (kinds != null) capabilities[entityId] = kinds
            for (d in waiting.toList()) d(ok, kinds)
        }
    }

    /** [entityId]'s WebRTC failed to connect (not for want of HA's connection): HLS first for a while. */
    fun webRtcFailed(entityId: String) {
        webRtcFailedAt[entityId] = now()
    }

    /** [entityId]'s WebRTC showed video: it's tried first again. */
    fun webRtcPlayed(entityId: String) {
        webRtcFailedAt.remove(entityId)
    }

    /** True while [entityId]'s WebRTC failed less than [WEBRTC_FAILED_FOR_MS] ago. */
    fun webRtcFailedRecently(entityId: String): Boolean {
        val at = webRtcFailedAt[entityId] ?: return false
        if (now() - at < WEBRTC_FAILED_FOR_MS) return true
        webRtcFailedAt.remove(entityId)
        return false
    }

    /** The connection came back after a drop (HA may have restarted): everything is asked and tried afresh. */
    fun forget() {
        capabilities.clear()
        asking.clear()
        webRtcFailedAt.clear()
    }

    companion object {
        /** How long a camera whose WebRTC failed to connect goes to HLS first. */
        const val WEBRTC_FAILED_FOR_MS = 600_000L

        private val byStore = WeakHashMap<EntityStore, CameraKnowledge>()

        /** The knowledge of [store]'s connection. Main thread. */
        fun of(store: EntityStore): CameraKnowledge = byStore.getOrPut(store) { CameraKnowledge() }
    }
}
