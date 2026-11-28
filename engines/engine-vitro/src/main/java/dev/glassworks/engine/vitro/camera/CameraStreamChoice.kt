/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.camera

import org.json.JSONArray
import org.json.JSONObject

/** One player a camera runs: [visible] false is HA's `class="hidden"`, a player that loads while another shows. */
data class PlannedStream(val kind: CameraStreamKind, val visible: Boolean)

/**
 * Which players a live camera runs, as HA's frontend decides it (`ha-camera-stream`'s `_streams`, frontend
 * 20261028.8). Pure: the caller feeds it what it knows and runs what comes back.
 *
 * HA's choice also weighs audio (HLS wins over WebRTC when only HLS has sound and the player isn't muted). Vitro
 * plays every camera muted, as HA's cards do, so that rule never applies here.
 */
object CameraStreamChoice {
    /** HA's `CameraEntityFeature.STREAM`: without it the frontend never asks for capabilities and plays MJPEG. */
    const val FEATURE_STREAM = 2

    private val NONE = emptyList<PlannedStream>()
    private val MJPEG = listOf(PlannedStream(CameraStreamKind.MJPEG, true))

    /**
     * `camera/capabilities`' `frontend_stream_types` as kinds, in HA's order (`hls`, `web_rtc`); unknown ones are
     * dropped. A [result] without the list reads as none.
     */
    fun kindsOf(result: Any?): List<CameraStreamKind> {
        val types = (result as? JSONObject)?.optJSONArray("frontend_stream_types") ?: JSONArray()
        val out = ArrayList<CameraStreamKind>(2)
        for (i in 0 until types.length()) {
            val kind = when (types.optString(i)) {
                "hls" -> CameraStreamKind.HLS
                "web_rtc" -> CameraStreamKind.WEB_RTC
                else -> null
            }
            if (kind != null && kind !in out) out += kind
        }
        return out
    }

    /**
     * The kinds this panel can play out of [offered]: one without a player here ([hasPlayer] false: WebRTC until
     * its player registers) is left out, as if HA hadn't offered it, so the choice goes on to the next kind.
     */
    fun playable(offered: List<CameraStreamKind>, hasPlayer: (CameraStreamKind) -> Boolean): List<CameraStreamKind> =
        offered.filter { it != CameraStreamKind.MJPEG && hasPlayer(it) }

    /**
     * The players to run for a camera that supports [supported] (null: capabilities not known yet), given what the
     * HLS and WebRTC players reported: true once one has video, false once it failed, null while it loads (or
     * hasn't run). Line for line HA's `_streams`, muted, with one exception: when HLS plays and WebRTC then fails,
     * HA's "fully loaded" branch drops to MJPEG (its comment says "both streams failed to load", which they didn't);
     * here HLS keeps playing.
     */
    fun streams(supported: List<CameraStreamKind>?, hlsVideo: Boolean?, webRtcVideo: Boolean?): List<PlannedStream> {
        if (supported == null) return NONE
        // Doesn't support any stream type: MJPEG.
        if (supported.isEmpty()) return MJPEG
        if (supported.size == 1) {
            val only = supported[0]
            // Only one type, no need to choose; a stream that failed to load falls back to MJPEG.
            if ((only == CameraStreamKind.HLS && hlsVideo == false) || (only == CameraStreamKind.WEB_RTC && webRtcVideo == false)) {
                return MJPEG
            }
            return listOf(PlannedStream(only, true))
        }
        if (hlsVideo != null && webRtcVideo != null) {
            // Fully loaded. (The audio rule would pick HLS here when WebRTC lacks sound and the player isn't muted.)
            if (webRtcVideo) return listOf(PlannedStream(CameraStreamKind.WEB_RTC, true))
            // HA returns MJPEG here even when HLS has video (see above).
            if (hlsVideo) return listOf(PlannedStream(CameraStreamKind.HLS, true))
            // Both streams failed to load.
            return MJPEG
        }
        if (hlsVideo != webRtcVideo) {
            // One of the two is loaded, or errored: choose the one that has video or is still loading.
            if (hlsVideo == true) return listOf(PlannedStream(CameraStreamKind.HLS, true), PlannedStream(CameraStreamKind.WEB_RTC, false))
            if (hlsVideo == false) return listOf(PlannedStream(CameraStreamKind.WEB_RTC, true))
            if (webRtcVideo == true) return listOf(PlannedStream(CameraStreamKind.WEB_RTC, true), PlannedStream(CameraStreamKind.HLS, false))
            if (webRtcVideo == false) return listOf(PlannedStream(CameraStreamKind.HLS, true))
        }
        return listOf(PlannedStream(CameraStreamKind.HLS, true), PlannedStream(CameraStreamKind.WEB_RTC, false))
    }
}
