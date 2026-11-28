/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import dev.glassworks.engine.vitro.camera.CameraStreamChoice
import dev.glassworks.engine.vitro.camera.CameraStreamKind
import dev.glassworks.engine.vitro.camera.CameraStreamKind.HLS
import dev.glassworks.engine.vitro.camera.CameraStreamKind.MJPEG
import dev.glassworks.engine.vitro.camera.CameraStreamKind.WEB_RTC
import dev.glassworks.engine.vitro.camera.PlannedStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** The stream choice, held to HA frontend 20261028.8's `ha-camera-stream` `_streams` for every combination. */
class CameraStreamChoiceTest {
    private fun shown(kind: CameraStreamKind) = PlannedStream(kind, true)
    private fun hidden(kind: CameraStreamKind) = PlannedStream(kind, false)
    private fun choose(supported: List<CameraStreamKind>?, hls: Boolean? = null, webRtc: Boolean? = null) =
        CameraStreamChoice.streams(supported, hls, webRtc)

    @Test
    fun nothingRunsUntilCapabilitiesAreKnown() {
        assertEquals(emptyList<PlannedStream>(), choose(null))
    }

    @Test
    fun aCameraWithNoStreamTypePlaysMjpeg() {
        assertEquals(listOf(shown(MJPEG)), choose(emptyList()))
    }

    @Test
    fun oneTypePlaysAloneAndFallsToMjpegWhenItFails() {
        for (only in listOf(HLS, WEB_RTC)) {
            for (state in listOf(null, true)) {
                val (hls, rtc) = if (only == HLS) state to null else null to state
                assertEquals("$only $state", listOf(shown(only)), choose(listOf(only), hls, rtc))
            }
            val (hls, rtc) = if (only == HLS) false to null else null to false
            assertEquals("$only failed", listOf(shown(MJPEG)), choose(listOf(only), hls, rtc))
        }
        // The other player's report doesn't matter when only one type is offered.
        assertEquals(listOf(shown(HLS)), choose(listOf(HLS), hls = null, webRtc = false))
        assertEquals(listOf(shown(WEB_RTC)), choose(listOf(WEB_RTC), hls = false, webRtc = null))
    }

    @Test
    fun bothTypesLoadSideBySideAndWebRtcWins() {
        val both = listOf(HLS, WEB_RTC)
        // Both loading: HLS shows, WebRTC loads hidden.
        assertEquals(listOf(shown(HLS), hidden(WEB_RTC)), choose(both))
        // The order HA lists them in doesn't matter.
        assertEquals(listOf(shown(HLS), hidden(WEB_RTC)), choose(listOf(WEB_RTC, HLS)))
        // HLS has video first: it shows while WebRTC keeps loading.
        assertEquals(listOf(shown(HLS), hidden(WEB_RTC)), choose(both, hls = true))
        // WebRTC has video first: it shows, HLS keeps loading hidden.
        assertEquals(listOf(shown(WEB_RTC), hidden(HLS)), choose(both, webRtc = true))
        // One failed while the other loads: the other shows alone.
        assertEquals(listOf(shown(WEB_RTC)), choose(both, hls = false))
        assertEquals(listOf(shown(HLS)), choose(both, webRtc = false))
        // Fully loaded: WebRTC when it has video (muted: the audio rule never applies).
        assertEquals(listOf(shown(WEB_RTC)), choose(both, hls = true, webRtc = true))
        assertEquals(listOf(shown(WEB_RTC)), choose(both, hls = false, webRtc = true))
        // Both failed: MJPEG.
        assertEquals(listOf(shown(MJPEG)), choose(both, hls = false, webRtc = false))
    }

    @Test
    fun hlsKeepsPlayingWhenWebRtcFailsAfterIt() {
        // HA's fully-loaded branch returns MJPEG here; Vitro keeps the HLS that plays (documented on streams()).
        assertEquals(listOf(shown(HLS)), choose(listOf(HLS, WEB_RTC), hls = true, webRtc = false))
    }

    @Test
    fun theFullFallbackChainEndsInMjpeg() {
        val both = listOf(HLS, WEB_RTC)
        var hls: Boolean? = null
        var rtc: Boolean? = null
        assertEquals(listOf(shown(HLS), hidden(WEB_RTC)), choose(both, hls, rtc))
        rtc = false // WebRTC fails first
        assertEquals(listOf(shown(HLS)), choose(both, hls, rtc))
        hls = false // then HLS
        assertEquals(listOf(shown(MJPEG)), choose(both, hls, rtc))
    }

    @Test
    fun capabilitiesReadHasTypesInOrderAndDropUnknownOnes() {
        assertEquals(listOf(HLS, WEB_RTC), CameraStreamChoice.kindsOf(JSONObject("""{"frontend_stream_types": ["hls", "web_rtc"], "two_way_audio": false}""")))
        assertEquals(listOf(WEB_RTC), CameraStreamChoice.kindsOf(JSONObject("""{"frontend_stream_types": ["web_rtc", "web_rtc", "rtsp"]}""")))
        assertEquals(emptyList<CameraStreamKind>(), CameraStreamChoice.kindsOf(JSONObject("{}")))
        assertEquals(emptyList<CameraStreamKind>(), CameraStreamChoice.kindsOf(null))
    }

    @Test
    fun aKindWithoutAPlayerHereIsLeftOutSoWebRtcFallsThroughToHls() {
        val noWebRtc: (CameraStreamKind) -> Boolean = { it != WEB_RTC }
        val playable = CameraStreamChoice.playable(listOf(HLS, WEB_RTC), noWebRtc)
        assertEquals(listOf(HLS), playable)
        assertEquals(listOf(shown(HLS)), choose(playable))
        // A camera that offers WebRTC alone plays MJPEG until the WebRTC player exists.
        val onlyRtc = CameraStreamChoice.playable(listOf(WEB_RTC), noWebRtc)
        assertEquals(listOf(shown(MJPEG)), choose(onlyRtc))
        // With the player, nothing is left out.
        assertEquals(listOf(HLS, WEB_RTC), CameraStreamChoice.playable(listOf(HLS, WEB_RTC)) { true })
    }
}
