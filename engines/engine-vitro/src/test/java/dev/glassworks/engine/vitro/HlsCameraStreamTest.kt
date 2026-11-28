/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.test.utils.ExoPlayerTestRunner
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.test.utils.FakeSampleStream
import androidx.media3.test.utils.FakeMediaSource
import androidx.media3.test.utils.FakeTimeline
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.media3.test.utils.robolectric.RobolectricUtil
import dev.glassworks.engine.vitro.camera.CameraStream
import dev.glassworks.engine.vitro.camera.HlsCameraStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * The HLS player ([HlsCameraStream]) on Media3's fake renderers and a fake MediaSource: how it gets HA's stream
 * address, what it reports, and what it frees. On API 23 too, the legacy build's floor.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 34])
class HlsCameraStreamTest {
    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }
    private val main = Handler(Looper.getMainLooper())

    private class Events(private val wants: Boolean = true) : CameraStream.Listener {
        val log = ArrayList<String>()
        var frames = 0

        override val wantsFrames: Boolean get() = wants

        override fun onFirstFrame(sinceStartMs: Long) {
            log += "first"
        }

        override fun onFrameRendered() {
            frames++
        }

        override fun onError(reason: String) {
            log += "error $reason"
        }

        override fun onEnded() {
            log += "ended"
        }
    }

    private val players = ArrayList<ExoPlayer>()
    private val sources = ArrayList<String>()
    private val wsCalls = ArrayList<JSONObject>()

    /** What the player was told about its output, in order: "attach" and "detach". */
    private val outputs = ArrayList<String>()

    private fun stream(
        answer: (JSONObject) -> Triple<Boolean, Any?, JSONObject?> = { Triple(true, JSONObject().put("url", "/api/hls/t0k3n/master_playlist.m3u8"), null) },
        source: () -> MediaSource = { FakeMediaSource(FakeTimeline(), ExoPlayerTestRunner.VIDEO_FORMAT) },
    ) = HlsCameraStream(
        activity, main, "camera.porch", "http://192.0.2.10:8123",
        callWs = { msg, done ->
            wsCalls += msg
            val (ok, result, error) = answer(msg)
            done(ok, result, error)
        },
        newPlayer = { ctx -> TestExoPlayerBuilder(ctx).build().also { players += it } },
        newSource = { url -> sources += url; source() },
        attachOutput = { p, _ -> outputs += "attach"; p.setVideoSurface(Surface(SurfaceTexture(1))) },
        detachOutput = { p, _ -> outputs += "detach"; p.clearVideoSurface() },
    )

    @Test
    fun itAsksHaForTheStreamPlaysItMutedAndReportsItsFrames() {
        val s = stream()
        val events = Events()
        s.listener = events
        s.start()
        RobolectricUtil.runMainLooperUntil { events.log.isNotEmpty() }
        val asked = wsCalls.single()
        assertEquals("camera/stream" to "camera.porch", asked.getString("type") to asked.getString("entity_id"))
        assertEquals(2, asked.length())
        // HA's hassUrl(url): the stream token in the path is the only credential, as with the frontend's fetch.
        assertEquals("http://192.0.2.10:8123/api/hls/t0k3n/master_playlist.m3u8", sources.single())
        assertEquals(listOf("first"), events.log)
        assertEquals(1f, s.view.alpha)
        val p = players.single()
        assertEquals(0f, p.volume)
        assertTrue(p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_AUDIO))
        assertEquals(1280, s.videoWidth)
        assertEquals(720, s.videoHeight)
        s.close()
        ShadowLooper.idleMainLooper()
        assertTrue(p.isReleased)
    }

    @Test
    fun offScreenAfterItsFirstFrameItLetsGoOfTheViewAndTakesItBackOnScreen() {
        val s = stream()
        val events = Events()
        s.listener = events
        s.start()
        RobolectricUtil.runMainLooperUntil { events.log.isNotEmpty() }
        assertEquals(listOf("attach"), outputs)
        s.setRendering(false)
        assertEquals("off screen it draws nothing", listOf("attach", "detach"), outputs)
        s.setRendering(false)
        assertEquals(listOf("attach", "detach"), outputs)
        s.setRendering(true)
        assertEquals("back on screen it draws again", listOf("attach", "detach", "attach"), outputs)
        assertEquals("one first frame for the stream", listOf("first"), events.log)
        s.close()
    }

    @Test
    fun offScreenTheProductionDetachClearsThePlayersSurface() {
        // The real ExoPlayer wrapped to see what it's told; the stream's own default detachOutput.
        val cleared = ArrayList<String>()
        val s = HlsCameraStream(
            activity, main, "camera.porch", "http://192.0.2.10:8123",
            callWs = { _, done -> done(true, JSONObject().put("url", "/api/hls/t0k3n/master_playlist.m3u8"), null) },
            newPlayer = { ctx ->
                val real = TestExoPlayerBuilder(ctx).build().also { players += it }
                java.lang.reflect.Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) { _, m, args ->
                    if (m.name == "clearVideoSurface" && (args == null || args.isEmpty())) cleared += "cleared"
                    if (args == null) m.invoke(real) else m.invoke(real, *args)
                } as ExoPlayer
            },
            newSource = { FakeMediaSource(FakeTimeline(), ExoPlayerTestRunner.VIDEO_FORMAT) },
            attachOutput = { p, _ -> p.setVideoSurface(Surface(SurfaceTexture(1))) },
        )
        val events = Events()
        s.listener = events
        s.start()
        RobolectricUtil.runMainLooperUntil { events.log.isNotEmpty() }
        assertTrue(cleared.isEmpty())
        s.setRendering(false)
        assertEquals(listOf("cleared"), cleared)
        s.close()
    }

    @Test
    fun offScreenBeforeItsFirstFrameItKeepsTheViewUntilThatFrame() {
        val s = stream()
        val events = Events()
        s.listener = events
        s.setRendering(false)
        s.start()
        RobolectricUtil.runMainLooperUntil { events.log.isNotEmpty() }
        // The first frame is drawn anyway: it's what says the stream plays. Then the view goes.
        assertEquals(listOf("first"), events.log)
        assertEquals(listOf("attach", "detach"), outputs)
        s.close()
    }

    @Test
    fun theViewStaysClearUntilTheFirstFrame() {
        val s = stream(answer = { Triple(false, null, JSONObject().put("code", "start_stream_failed")) })
        s.listener = Events()
        assertEquals(0f, s.view.alpha)
    }

    @Test
    fun camerastreamFailingIsTheStreamFailing() {
        val s = stream(answer = { Triple(false, null, JSONObject().put("code", "start_stream_failed")) })
        val events = Events()
        s.listener = events
        s.start()
        ShadowLooper.idleMainLooper()
        assertEquals(listOf("error camera/stream: start_stream_failed"), events.log)
        assertTrue(players.isEmpty())
    }

    @Test
    fun aPlaybackErrorIsTheStreamFailingAndFreesThePlayer() {
        // The playlist answers 404 (HA's stream ended): not retried, the stream fails.
        val notFound = DataSource.Factory {
            object : BaseDataSource(true) {
                override fun open(dataSpec: DataSpec): Long =
                    throw HttpDataSource.InvalidResponseCodeException(404, "Not Found", null, emptyMap(), dataSpec, ByteArray(0))

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int = C.RESULT_END_OF_INPUT
                override fun getUri(): android.net.Uri? = null
                override fun close() = Unit
            }
        }
        val s = stream(source = { ProgressiveMediaSource.Factory(notFound).createMediaSource(MediaItem.fromUri("http://192.0.2.10:8123/x")) })
        val events = Events()
        s.listener = events
        s.start()
        RobolectricUtil.runMainLooperUntil { events.log.isNotEmpty() }
        assertTrue(events.log.single(), events.log.single().startsWith("error hls: "))
        assertTrue(players.single().isReleased)
    }

    @Test
    fun pausedItFreesThePlayerAndResumedItAsksForTheStreamAgain() {
        val s = stream()
        val events = Events()
        s.listener = events
        s.start()
        RobolectricUtil.runMainLooperUntil { events.log.isNotEmpty() }
        s.setActive(false)
        assertTrue(players.single().isReleased)
        assertEquals(1f, s.view.alpha)
        s.setActive(true)
        // Clear again until the resumed stream's first frame: the frame from before the pause isn't live.
        assertEquals(0f, s.view.alpha)
        RobolectricUtil.runMainLooperUntil { events.log.size == 2 }
        assertEquals(1f, s.view.alpha)
        assertEquals(listOf("first", "first"), events.log)
        assertEquals(2, wsCalls.size)
        assertEquals(2, players.size)
        assertFalse(players[1].isReleased)
        s.close()
    }

    @Test
    fun anAnswerForAClosedStreamOpensNothing() {
        var pending: (() -> Unit)? = null
        val s = HlsCameraStream(
            activity, main, "camera.porch", "http://192.0.2.10:8123",
            callWs = { _, done -> pending = { done(true, JSONObject().put("url", "/api/hls/x/master_playlist.m3u8"), null) } },
            newPlayer = { ctx -> TestExoPlayerBuilder(ctx).build().also { players += it } },
            newSource = { FakeMediaSource(FakeTimeline(), ExoPlayerTestRunner.VIDEO_FORMAT) },
            attachOutput = { _, _ -> },
        )
        s.start()
        s.close()
        pending!!()
        ShadowLooper.idleMainLooper()
        assertTrue(players.isEmpty())
    }

    @Test
    fun eachFrameIsReportedOnlyWhileTheListenerWantsIt() {
        for (wants in listOf(true, false)) {
            // Ten seconds of 30 fps video from where the fake timeline's window starts: the first frame, then the rest.
            @Suppress("DEPRECATION")
            val s = stream(source = {
                FakeMediaSource(
                    FakeTimeline(), DrmSessionManager.DRM_UNSUPPORTED,
                    { _, _ ->
                        (0 until 300).map { FakeSampleStream.FakeSampleStreamItem.oneByteSample(START_US + it * 33_333L, C.BUFFER_FLAG_KEY_FRAME) } +
                            FakeSampleStream.FakeSampleStreamItem.END_OF_STREAM_ITEM
                    },
                    ExoPlayerTestRunner.VIDEO_FORMAT,
                )
            })
            val events = Events(wants)
            s.listener = events
            s.start()
            RobolectricUtil.runMainLooperUntil { events.log.isNotEmpty() }
            assertEquals(listOf("first"), events.log)
            // A second of playback on the player's clock.
            val p = players.last()
            RobolectricUtil.runMainLooperUntil { p.currentPosition >= 1_000 }
            ShadowLooper.idleMainLooper()
            if (wants) assertTrue("the later frames, each reported (${events.frames})", events.frames >= 20)
            else assertEquals("not wanted: none reported, nothing posted for them", 0, events.frames)
            s.close()
        }
    }

    @Test
    fun aLiveCameraStartsOnHalfASecondOfMediaByTime() {
        // ExoPlayer's defaults wait for 2.5 s of media before the first frame; HA's hls.js plays its first fragment.
        assertEquals(500, HlsCameraStream.BUFFER_FOR_PLAYBACK_MS)
        assertTrue(HlsCameraStream.BUFFER_AFTER_REBUFFER_MS <= HlsCameraStream.MIN_BUFFER_MS)
        assertTrue(HlsCameraStream.MIN_BUFFER_MS < HlsCameraStream.MAX_BUFFER_MS)
        // Builds (DefaultLoadControl checks its durations' order), and the player takes it.
        HlsCameraStream.loadControl()
        HlsCameraStream.defaultPlayer(activity).release()
    }

    @Test
    fun theLiveConfigurationIsHlsJsCatchUp() {
        val item: MediaItem = HlsCameraStream.mediaItem("http://192.0.2.10:8123/api/hls/x/master_playlist.m3u8")
        assertEquals(2f, item.liveConfiguration.maxPlaybackSpeed)
        assertEquals("application/x-mpegURL", item.localConfiguration?.mimeType)
        assertEquals("http://192.0.2.10:8123/api/hls/x/master_playlist.m3u8", HlsCameraStream.absolute("http://192.0.2.10:8123/", "/api/hls/x/master_playlist.m3u8"))
    }

    private companion object {
        /** Where FakeTimeline's window starts in its period: its default position. */
        val START_US = FakeTimeline.TimelineWindowDefinition.DEFAULT_WINDOW_OFFSET_IN_FIRST_PERIOD_US
    }
}
