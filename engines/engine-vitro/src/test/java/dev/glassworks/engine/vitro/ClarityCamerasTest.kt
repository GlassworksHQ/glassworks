/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Application
import android.view.View
import android.widget.FrameLayout
import dev.glassworks.engine.vitro.camera.CameraStream
import dev.glassworks.engine.vitro.camera.CameraStreamFactory
import dev.glassworks.engine.vitro.camera.CameraStreamKind
import dev.glassworks.engine.vitro.camera.CameraStreamVideoSize
import dev.glassworks.engine.vitro.camera.CameraStreamKind.HLS
import dev.glassworks.engine.vitro.camera.CameraStreamKind.MJPEG
import dev.glassworks.engine.vitro.camera.CameraStreamKind.WEB_RTC
import dev.glassworks.engine.vitro.clarity.ClarityCameras
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Clarity's camera task on Vitro (docs/perf.md "Vitro"): the hooks on each [CameraStream] a measured factory makes, and
 * the `camera` line's streams, against a fake stream driven by hand on a fake clock.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ClarityCamerasTest {
    private companion object {
        /** The view measured (its index in the dashboard). */
        const val VIEW = 2
    }

    /** A stream as a player is one: a view, and reports through its listener when the test says. */
    private class FakeStream(ctx: android.content.Context) : CameraStream {
        override val view: View = View(ctx)
        override var listener: CameraStream.Listener? = null
        var started = 0
        var closed = 0
        var playing = true

        override fun start() {
            started++
        }

        override fun setActive(active: Boolean) {
            playing = active
        }

        override fun close() {
            closed++
        }
    }

    /** What the stream choice hears from the stream it holds. */
    private class Heard : CameraStream.Listener {
        val calls = ArrayList<String>()
        override fun onFirstFrame(sinceStartMs: Long) {
            calls += "first $sinceStartMs"
        }

        override fun onFrameRendered() {
            calls += "frame"
        }

        override fun onError(reason: String) {
            calls += "error $reason"
        }

        override fun onEnded() {
            calls += "ended"
        }
    }

    private val ctx: Application = RuntimeEnvironment.getApplication()
    private var t = 0.0
    private val cams = ClarityCameras { t }
    private val made = ArrayList<FakeStream>()
    private val factory = ClarityCameras.measured(object : CameraStreamFactory {
        override fun create(entityId: String, kind: CameraStreamKind): CameraStream = FakeStream(ctx).also { made += it }
    })

    /** The view's cards: one per camera here, each holding its streams' pictures. */
    private val cards = LinkedHashMap<String, FrameLayout>()

    /** Every built view's top-level cards, by view index: [VIEW] is the one measured. */
    private val views = HashMap<Int, MutableList<View>>()

    @Before
    fun measure() {
        ClarityCameras.current = cams
        cams.viewOfCard = { v -> views.entries.firstOrNull { (_, cs) -> cs.any { it === v } }?.key }
    }

    @After
    fun done() {
        ClarityCameras.current = null
    }

    private fun card(entity: String) = cards.getOrPut(entity) { FrameLayout(ctx).also { views.getOrPut(VIEW) { ArrayList() } += it } }

    /** A stream of [kind] for [entity], placed in its card and started at [at], as the choice does. */
    private fun open(entity: String, kind: CameraStreamKind, at: Double, into: FrameLayout = card(entity)): CameraStream {
        t = at
        val s = factory.create(entity, kind)!!
        into.addView(s.view)
        s.start()
        return s
    }

    private fun inner(s: CameraStream) = made.last { it.view === s.view }

    /** Pictures at [fps] from [from] to [to] (ms), the first one a first frame. */
    private fun play(s: CameraStream, from: Double, to: Double, fps: Double = 15.0, skip: (Double) -> Boolean = { false }) {
        val l = inner(s).listener!!
        var at = from
        var first = true
        while (at <= to) {
            if (!skip(at)) {
                t = at
                if (first) l.onFirstFrame((at - from).toLong()) else l.onFrameRendered()
                first = false
            }
            at += 1000.0 / fps
        }
    }

    private fun window(cameras: List<String>, expected: Int, path: CameraStreamKind, nav: Double, t0: Double, t1: Double) =
        cams.window(VIEW, cameras, expected, path, nav, t0, t1, 15.0)

    private fun stream(w: JSONObject, i: Int = 0) = w.getJSONArray("streams").getJSONObject(i)

    @Test
    fun aMeasuredStreamPassesEveryCallOnToTheChoice() {
        val s = open("camera.clarity_cam_0", WEB_RTC, 0.0)
        val heard = Heard().also { s.listener = it }
        val l = inner(s).listener!!
        l.onFirstFrame(640)
        l.onFrameRendered()
        l.onError("ice failed")
        l.onEnded()
        assertEquals(listOf("first 640", "frame", "error ice failed", "ended"), heard.calls)
        assertSame(inner(s).view, s.view)
        s.setActive(false)
        assertFalse(inner(s).playing)
        s.close()
        assertEquals(1, inner(s).started)
        assertEquals(1, inner(s).closed)
    }

    @Test
    fun outsideARunTheStreamIsTheSameAndNothingIsKept() {
        ClarityCameras.current = null
        val s = open("camera.clarity_cam_0", WEB_RTC, 0.0)
        val heard = Heard().also { s.listener = it }
        play(s, 1000.0, 2000.0)
        assertEquals("first 0", heard.calls.first())
        assertTrue(heard.calls.size >= 15)
        ClarityCameras.current = cams
        val w = window(listOf("camera.clarity_cam_0"), 1, WEB_RTC, 0.0, 1000.0, 2000.0)
        assertEquals(0, w.getInt("found"))
    }

    @Test
    fun theFactoryIsWrappedOnceAndSaysTheBuildIsWired() {
        assertTrue(ClarityCameras.wired)
        assertSame(factory, ClarityCameras.measured(factory))
        val s = factory.create("camera.x", HLS)!!
        assertNotSame(made.last(), s)
        assertSame(made.last().view, s.view)
        assertNull(ClarityCameras.measured(object : CameraStreamFactory {
            override fun create(entityId: String, kind: CameraStreamKind): CameraStream? = null
        }).create("camera.x", MJPEG))
    }

    @Test
    fun theWrapperGivesTheVideosSizeAsItsStreamKnowsIt() {
        class Sized(ctx: android.content.Context) : CameraStream, CameraStreamVideoSize {
            override val view: View = View(ctx)
            override var listener: CameraStream.Listener? = null
            override var videoWidth = 0
            override var videoHeight = 0
            override fun start() {}
            override fun setActive(active: Boolean) {}
            override fun close() {}
        }
        val inner = Sized(ctx)
        val s = ClarityCameras.measured(object : CameraStreamFactory {
            override fun create(entityId: String, kind: CameraStreamKind): CameraStream = inner
        }).create("camera.clarity_cam_0", WEB_RTC)!!
        val sized = s as CameraStreamVideoSize
        assertEquals(0, sized.videoWidth)
        inner.videoWidth = 1280
        inner.videoHeight = 720
        // Read through, each time: a player learns its size once the first frame is decoded.
        assertEquals(1280, sized.videoWidth)
        assertEquals(720, sized.videoHeight)
        // A stream that doesn't know its size: 0 both ways (unknown, so the video fills its card).
        val plain = factory.create("camera.clarity_cam_1", MJPEG) as CameraStreamVideoSize
        assertEquals(0, plain.videoWidth)
        assertEquals(0, plain.videoHeight)
    }

    @Test
    fun aWebRtcCameraThatPlaysIsGradedAsTheWebEnginesReceiver() {
        // The view opens at 1 s, the camera's first picture at 1.64 s, the window from 4 s for 30 s, 15 fps throughout.
        val s = open("camera.clarity_cam_0", WEB_RTC, 1100.0)
        play(s, 1640.0, 35_000.0)
        val w = window(listOf("camera.clarity_cam_0"), 1, WEB_RTC, 1000.0, 4000.0, 34_000.0)
        assertEquals(1, w.getInt("found"))
        val x = stream(w)
        assertEquals("web_rtc", x.getString("path"))
        assertEquals("web_rtc", x.getString("transport"))
        assertTrue(x.getBoolean("found"))
        assertEquals(640.0, x.getDouble("first_frame_ms"), 0.01)
        assertEquals(100.0, x.getDouble("shown_pct"), 0.01)
        assertEquals(0.0, x.getDouble("stall_total_ms"), 0.0)
        assertEquals(0, x.getInt("freezes"))
        assertEquals(66.7, x.getDouble("max_gap_ms"), 0.1)
        assertEquals(ClarityCameras.GAPS_FREEZE, x.getString("gaps_source"))
        assertEquals(ClarityCameras.SOURCE, x.getString("source"))
        assertEquals(30_000.0, x.getDouble("span_ms"), 0.0)
        assertEquals(0, x.getJSONArray("others").length())
        assertEquals(0, x.getJSONArray("errors").length())
    }

    @Test
    fun aFreezeInTheWindowIsAStallAtItsLengthInside() {
        val s = open("camera.clarity_cam_0", WEB_RTC, 0.0)
        // No picture from 10 s to 11.2 s: one freeze, 1.2 s long (and the longest gap).
        play(s, 500.0, 35_000.0) { it > 10_000.0 && it < 11_200.0 }
        val x = stream(window(listOf("camera.clarity_cam_0"), 1, WEB_RTC, 0.0, 4000.0, 34_000.0))
        assertEquals(1, x.getInt("freezes"))
        assertEquals(1200.0, x.getDouble("stall_total_ms"), 70.0)
        assertEquals(x.getDouble("stall_total_ms"), x.getDouble("max_gap_ms"), 0.01)
        assertTrue(x.getDouble("shown_pct") < 97)
    }

    @Test
    fun aCameraThatFellBackToMjpegIsNotFoundOnItsPathAndSaysWhatItPlayed() {
        // The choice tried WebRTC, which failed, then played MJPEG for the whole window.
        val rtc = open("camera.clarity_cam_0", WEB_RTC, 1100.0)
        inner(rtc).listener!!.onError("no answer to the offer")
        rtc.close()
        val mjpeg = open("camera.clarity_cam_0", MJPEG, 1200.0)
        play(mjpeg, 1500.0, 35_000.0)
        val w = window(listOf("camera.clarity_cam_0"), 1, WEB_RTC, 1000.0, 4000.0, 34_000.0)
        val x = stream(w)
        // As harness.js: a video on the path, that never showed a frame there (it fails the task).
        assertEquals(1, w.getInt("found"))
        assertTrue(x.isNull("first_frame_ms"))
        assertTrue(x.isNull("shown_pct"))
        assertEquals("mjpeg", x.getString("transport"))
        val other = x.getJSONArray("others").getJSONObject(0)
        assertEquals("mjpeg", other.getString("path"))
        assertEquals(500.0, other.getDouble("first_frame_ms"), 0.01)
        assertTrue(other.getBoolean("connected"))
        assertEquals("web_rtc: no answer to the offer", x.getJSONArray("errors").getString(0))
    }

    @Test
    fun aCameraWithNoStreamAtAllIsMissing() {
        val w = window(listOf("camera.clarity_cam_0"), 1, WEB_RTC, 0.0, 4000.0, 34_000.0)
        assertEquals(0, w.getInt("found"))
        assertFalse(stream(w).getBoolean("found"))
        assertTrue(stream(w).isNull("transport"))
        // A view whose cameras were asked for but not seen in its cards: each one missing.
        assertEquals(4, window(emptyList(), 4, WEB_RTC, 0.0, 4000.0, 34_000.0).getJSONArray("streams").length())
    }

    @Test
    fun theHlsFallbackCountsGapsOver500MsAsTheWebEnginesHlsDoes() {
        val s = open("camera.clarity_hls_0", HLS, 0.0)
        // Gaps of 400 ms (not a stall on HLS's rule) and one of 700 ms (a stall).
        val times = listOf(500.0, 4100.0) + (1..40).map { 4100.0 + it * 400.0 } + listOf(4100.0 + 40 * 400 + 700, 34_100.0)
        val l = inner(s).listener!!
        times.forEachIndexed { i, at ->
            t = at
            if (i == 0) l.onFirstFrame(500) else l.onFrameRendered()
        }
        val x = stream(window(listOf("camera.clarity_hls_0"), 1, HLS, 0.0, 4000.0, 34_000.0))
        assertEquals(ClarityCameras.GAPS_500, x.getString("gaps_source"))
        assertTrue(x.isNull("freezes"))
        // 700 ms, and the tail from the last picture to the window's end (34000 - 20800 = 13.2 s).
        assertEquals(700.0 + 13_200.0, x.getDouble("stall_total_ms"), 0.01)
    }

    @Test
    fun onlyTheViewsOwnCardsAndStreamsOpenSinceItsNavigationCount() {
        // A stream in a card that isn't the view's (another view's, kept built), and one that closed before the view
        // opened: neither is this view's camera.
        val elsewhere = FrameLayout(ctx).also { views[VIEW + 1] = mutableListOf(it) }
        val other = open("camera.clarity_cam_0", WEB_RTC, 0.0, into = elsewhere)
        play(other, 100.0, 35_000.0)
        val old = open("camera.clarity_cam_0", WEB_RTC, 0.0)
        play(old, 100.0, 900.0)
        t = 950.0
        old.close()
        val w = window(listOf("camera.clarity_cam_0"), 1, WEB_RTC, 1000.0, 4000.0, 34_000.0)
        assertEquals(0, w.getInt("found"))
        assertFalse(cams.allPlaying(VIEW, listOf("camera.clarity_cam_0"), WEB_RTC, 1000.0))
        val now = open("camera.clarity_cam_0", WEB_RTC, 1100.0)
        play(now, 1700.0, 35_000.0)
        assertTrue(cams.allPlaying(VIEW, listOf("camera.clarity_cam_0"), WEB_RTC, 1000.0))
        assertEquals(700.0, stream(window(listOf("camera.clarity_cam_0"), 1, WEB_RTC, 1000.0, 4000.0, 34_000.0))
            .getDouble("first_frame_ms"), 0.01)
    }

    @Test
    fun aViewThatBuildsItsCardsAgainKeepsItsCamerasHistory() {
        // The camera plays from 1.5 s; at 20 s a late state makes Vitro build the view's cards again: the old card and its
        // stream go, a new card's stream shows its first picture 300 ms later. The view's camera is both streams.
        val old = open("camera.clarity_cam_0", WEB_RTC, 1100.0)
        play(old, 1500.0, 20_000.0)
        t = 20_000.0
        old.close()
        views[VIEW] = ArrayList()
        cards.clear()
        val again = open("camera.clarity_cam_0", WEB_RTC, 20_000.0)
        play(again, 20_300.0, 35_000.0)
        val x = stream(window(listOf("camera.clarity_cam_0"), 1, WEB_RTC, 1000.0, 4000.0, 34_000.0))
        assertEquals(500.0, x.getDouble("first_frame_ms"), 0.01)
        assertEquals(2, x.getInt("sessions"))
        // 30 s at 15 fps less the gap between the two streams' pictures: one freeze, about 300 ms.
        assertTrue(x.getDouble("shown_pct") > 98)
        assertEquals(1, x.getInt("freezes"))
        assertEquals(300.0, x.getDouble("stall_total_ms"), 67.0)
        assertEquals("web_rtc", x.getString("transport"))
    }

    @Test
    fun onlyWhatHappenedByTheWindowsEndCounts() {
        // Read after the taps ran past the window: an error, a first picture and a stream that came after its end
        // are not the window's.
        val rtc = open("camera.clarity_cam_0", WEB_RTC, 1100.0)
        t = 40_000.0
        inner(rtc).listener!!.onError("late error")
        play(rtc, 40_500.0, 41_000.0)
        val mjpeg = open("camera.clarity_cam_0", MJPEG, 42_000.0)
        play(mjpeg, 42_100.0, 43_000.0)
        val x = stream(window(listOf("camera.clarity_cam_0"), 1, WEB_RTC, 1000.0, 4000.0, 34_000.0))
        assertTrue(x.isNull("first_frame_ms"))
        assertEquals(0, x.getJSONArray("errors").length())
        assertEquals(0, x.getJSONArray("others").length())
        assertTrue(x.isNull("transport"))
    }

    @Test
    fun aSecondFirstFrameAfterTheWindowChangesNothingInIt() {
        // One stream: its first picture 640 ms after it started, then (paused and resumed after the window) another
        // first picture 90 ms after the resume. The window keeps the first one's own time.
        val s = open("camera.clarity_cam_0", WEB_RTC, 1100.0)
        val l = inner(s).listener!!
        t = 1740.0
        l.onFirstFrame(640)
        t = 40_090.0
        l.onFirstFrame(90)
        val x = stream(window(listOf("camera.clarity_cam_0"), 1, WEB_RTC, 1000.0, 4000.0, 34_000.0))
        assertEquals(740.0, x.getDouble("first_frame_ms"), 0.01)
        assertEquals(640L, x.getLong("first_frame_since_start_ms"))
        // When the stream was made and started, from nav: the time before its own clock ran.
        assertEquals(100.0, x.getDouble("opened_ms"), 0.01)
        assertEquals(100.0, x.getDouble("started_ms"), 0.01)
    }

    @Test
    fun aStreamMadeBeforeItStartsHasItsOwnOpenTime() {
        t = 1050.0
        val s = factory.create("camera.clarity_cam_0", WEB_RTC)!!
        card("camera.clarity_cam_0").addView(s.view)
        t = 1300.0
        s.start()
        play(s, 1500.0, 35_000.0)
        val x = stream(window(listOf("camera.clarity_cam_0"), 1, WEB_RTC, 1000.0, 4000.0, 34_000.0))
        assertEquals(50.0, x.getDouble("opened_ms"), 0.01)
        assertEquals(300.0, x.getDouble("started_ms"), 0.01)
    }

    @Test
    fun aCameraThatNeverPlayedHasNoStartTimes() {
        open("camera.clarity_cam_0", WEB_RTC, 1100.0)
        val x = stream(window(listOf("camera.clarity_cam_0"), 1, WEB_RTC, 1000.0, 4000.0, 34_000.0))
        assertFalse(x.has("started_ms"))
    }

    @Test
    fun aStreamInAGridCardBelongsToTheGridItSitsIn() {
        val grid = FrameLayout(ctx)
        views.getOrPut(VIEW) { ArrayList() } += grid
        val inner = FrameLayout(ctx).also { grid.addView(it) }
        val names = listOf("camera.clarity_cam_1", "camera.clarity_cam_2", "camera.clarity_cam_3")
        for (n in names) play(open(n, WEB_RTC, 1100.0, into = inner), 1500.0, 35_000.0)
        val w = window(names, 3, WEB_RTC, 1000.0, 4000.0, 34_000.0)
        assertEquals(3, w.getInt("found"))
        assertTrue(cams.allPlaying(VIEW, names, WEB_RTC, 1000.0))
    }
}
