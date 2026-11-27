/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.webrtc

import android.os.Handler
import android.os.Looper
import android.view.TextureView
import dev.glassworks.engine.vitro.camera.CameraPlayers
import dev.glassworks.engine.vitro.camera.CameraStream
import dev.glassworks.engine.vitro.camera.CameraStreamFactories
import dev.glassworks.engine.vitro.camera.CameraStreamHost
import dev.glassworks.engine.vitro.camera.CameraStreamKind
import dev.glassworks.engine.vitro.camera.CameraStreamVideoSize
import dev.glassworks.hawebrtc.HaError
import dev.glassworks.hawebrtc.HaWebRtcSignaling
import dev.glassworks.hawebrtc.IceCandidateInit
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class VitroWebRtcTest {
    private val context = RuntimeEnvironment.getApplication()

    /** What the host saw: commands, and the subscriptions with their callbacks. */
    private val calls = ArrayList<Pair<JSONObject, (Boolean, Any?, JSONObject?) -> Unit>>()
    private val subs = ArrayList<Triple<JSONObject, (JSONObject) -> Unit, (JSONObject?) -> Unit>>()
    private var cancelled = 0

    private val host = CameraStreamHost(
        context, Handler(Looper.getMainLooper()), "http://homeassistant.local:8123", { null },
        callWs = { msg, done -> calls += msg to done },
        subscribeWs = { msg, onEvent, onError -> subs += Triple(msg, onEvent, onError); { cancelled++ } },
    )

    @After
    fun unregister() = CameraStreamFactories.unregister(CameraStreamKind.WEB_RTC)

    @Test
    fun `registering gives Vitro's cameras a WebRTC player, and only that kind`() {
        assertFalse(CameraPlayers.forHost(host) { null }.supports(CameraStreamKind.WEB_RTC))
        VitroWebRtc.register()
        val players = CameraPlayers.forHost(host) { null }
        assertTrue(players.supports(CameraStreamKind.WEB_RTC))
        // Through the engine, Clarity's camera task wraps the WebRTC stream (ClarityCameras.measured), and the card
        // still reads its picture's size through the wrapper.
        val measured = players.create("camera.front_door", CameraStreamKind.WEB_RTC)!!
        assertFalse("wrapped for Clarity", measured is WebRtcCameraStream)
        assertTrue(measured is CameraStreamVideoSize)
        assertTrue(measured.view is TextureView)
        measured.close()
        val factory = CameraStreamFactories.provider(CameraStreamKind.WEB_RTC)!!(host)
        assertNull(factory.create("camera.front_door", CameraStreamKind.HLS))
        val stream = factory.create("camera.front_door", CameraStreamKind.WEB_RTC)!!
        assertTrue(stream is CameraStreamVideoSize)
        assertTrue(stream.view is TextureView)
        assertEquals(0, (stream as CameraStreamVideoSize).videoWidth)
        stream.close()
    }

    /**
     * A card's video draws into a TextureView on every Android, WebRTC and HLS alike, never a SurfaceView: a panel whose
     * screen is rotated (the S1's, Android 10) has a composer that overlays only one video, so a surface per camera
     * pushed the whole window onto the GPU at video rate. API 29 is the S1's, API 34 a recent tablet's.
     */
    @Test
    @Config(sdk = [29, 34])
    fun `a card's WebRTC and HLS players draw into a TextureView on Android 10 and 14`() {
        assertEquals(dev.glassworks.hawebrtc.VideoViewKind.TEXTURE_VIEW, VitroWebRtc.OPTIONS.view)
        VitroWebRtc.register()
        val players = CameraPlayers.forHost(host) { null }
        for (kind in listOf(CameraStreamKind.WEB_RTC, CameraStreamKind.HLS)) {
            val stream = players.create("camera.front_door", kind)!!
            assertTrue("$kind: ${stream.view.javaClass.name}", stream.view is TextureView)
            assertFalse(kind.toString(), stream.view is android.view.SurfaceView)
            stream.close()
        }
    }

    @Test
    fun `the card's frame does the fitting, so the picture fills the view it gets (fit_mode fill stretches)`() {
        assertEquals(dev.glassworks.hawebrtc.VideoScaling.STRETCH, VitroWebRtc.OPTIONS.scaling)
        assertEquals("muted", false, VitroWebRtc.OPTIONS.stream.audio)
        assertEquals("paused means closed, as HLS", 0L, VitroWebRtc.OPTIONS.stream.idleTeardownMs)
        assertEquals("the cards' cameras redraw the window together, at most 30 times a second", 30, VitroWebRtc.OPTIONS.pacedMaxFps)
    }

    @Test
    fun `the host channel carries HA's signaling both ways`() {
        val channel = HostChannel(host)
        val events = ArrayList<String>()
        val signaling = HaWebRtcSignaling(channel, "camera.front_door", object : HaWebRtcSignaling.Listener {
            override fun onSession(sessionId: String) { events += "session $sessionId" }
            override fun onAnswer(sdp: String) { events += "answer $sdp" }
            override fun onRemoteCandidate(candidate: IceCandidateInit) { events += "candidate ${candidate.candidate}" }
            override fun onError(error: HaError) { events += "error ${error.code}" }
        })
        var iceServers = -1
        signaling.fetchClientConfig({ iceServers = it.iceServers.size }, { events += "config ${it.code}" })
        assertEquals("camera/webrtc/get_client_config", calls.single().first.getString("type"))
        calls.single().second(true, JSONObject("""{"configuration":{"iceServers":[{"urls":"stun:192.0.2.1"}]}}"""), null)
        assertEquals(1, iceServers)

        signaling.offer("v=0")
        val (msg, onEvent, _) = subs.single()
        assertEquals("camera/webrtc/offer", msg.getString("type"))
        signaling.addLocalCandidate(IceCandidateInit("candidate:1 1 udp 1 192.0.2.10 5000 typ host", "0", 0))
        onEvent(JSONObject().put("type", "session").put("session_id", "01JS"))
        onEvent(JSONObject().put("type", "answer").put("answer", "v=0 answer"))
        onEvent(JSONObject().put("type", "candidate").put("candidate", JSONObject().put("candidate", "candidate:2 1 udp 1 192.0.2.20 6000 typ host")))
        val candidate = calls.last().first
        assertEquals("camera/webrtc/candidate", candidate.getString("type"))
        assertEquals("01JS", candidate.getString("session_id"))
        signaling.close()
        assertEquals(1, cancelled)
        assertEquals(listOf("session 01JS", "answer v=0 answer", "candidate candidate:2 1 udp 1 192.0.2.20 6000 typ host"), events)
    }

    @Test
    fun `HA's refusals and a lost connection reach the signaling as errors`() {
        val channel = HostChannel(host)
        var error: HaError? = null
        channel.call(JSONObject().put("type", "camera/webrtc/get_client_config"), { throw AssertionError() }, { error = it })
        calls.single().second(false, null, JSONObject().put("code", "webrtc_get_client_config_failed").put("message", "no"))
        assertEquals("webrtc_get_client_config_failed", error!!.code)

        var result: Any? = "unset"
        channel.call(JSONObject().put("type", "camera/webrtc/candidate"), { result = it }, { throw AssertionError() })
        calls.last().second(true, JSONObject.NULL, null)
        assertNull(result)

        channel.subscribe(JSONObject().put("type", "camera/webrtc/offer"), {}, { error = it })
        subs.single().third(null) // Vitro's client couldn't send it
        assertEquals(HaError.NOT_CONNECTED, error!!.code)
    }

    @Test
    fun `Vitro's own no-connection codes are the library's NOT_CONNECTED, so the stream waits for HA`() {
        val channel = HostChannel(host)
        val codes = ArrayList<String>()
        for (code in listOf("disconnected", "send_failed", "home_assistant_error")) {
            channel.call(JSONObject().put("type", "camera/webrtc/get_client_config"), { throw AssertionError() }, { codes += it.code })
            calls.last().second(false, null, JSONObject().put("code", code).put("message", "Not connected"))
        }
        channel.call(JSONObject().put("type", "camera/webrtc/get_client_config"), { throw AssertionError() }, { codes += it.code })
        calls.last().second(false, null, null) // the engine had no client
        assertEquals(listOf(HaError.NOT_CONNECTED, HaError.NOT_CONNECTED, "home_assistant_error", HaError.NOT_CONNECTED), codes)
    }

    @Test
    fun `a stream reports the player's failure once, on the main thread, and nothing after close`() {
        VitroWebRtc.register()
        val stream = CameraStreamFactories.provider(CameraStreamKind.WEB_RTC)!!(host).create("camera.front_door", CameraStreamKind.WEB_RTC)!!
        val reasons = ArrayList<String>()
        var onMain = false
        stream.listener = object : CameraStream.Listener {
            override fun onFirstFrame(sinceStartMs: Long) = throw AssertionError()
            override fun onFrameRendered() = throw AssertionError()
            override fun onError(reason: String) {
                onMain = Looper.myLooper() == Looper.getMainLooper()
                reasons += reason
            }
            override fun onEnded() = throw AssertionError()
        }
        // No libwebrtc native library on the JVM: the player fails to start, as on a device without it.
        stream.start()
        val deadline = System.currentTimeMillis() + 10_000
        while (reasons.isEmpty() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
            Thread.sleep(20)
        }
        assertEquals(1, reasons.size)
        assertTrue(reasons.single(), reasons.single().startsWith("start_failed: libwebrtc"))
        assertTrue(onMain)
        stream.close()
        stream.close()
        assertNull(stream.listener)
        assertNotNull(stream.view)
    }
}
