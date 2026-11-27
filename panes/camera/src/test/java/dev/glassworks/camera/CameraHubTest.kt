/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.camera

import android.app.Application
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.MediaCodecInfoBuilder
import org.robolectric.shadows.ShadowCameraCharacteristics
import org.robolectric.shadows.ShadowMediaCodecList
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The hub's stream size against Android's camera list (#479, the verifier's F1-F3 on #485). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CameraHubTest {
    private val app = RuntimeEnvironment.getApplication()
    private val cameras = shadowOf(app.getSystemService(Context.CAMERA_SERVICE) as CameraManager)
    private val hubs = mutableListOf<CameraHub>()

    /** An H.264 encoder, so a camera that lists no sizes would stream at 1280x720. */
    @Before fun anEncoder() {
        val avc = MediaFormat().apply { setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_VIDEO_AVC) }
        ShadowMediaCodecList.addCodec(
            MediaCodecInfoBuilder.newBuilder().setName("test.avc.encoder").setIsEncoder(true).setCapabilities(
                MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder().setMediaFormat(avc).setIsEncoder(true)
                    .setColorFormats(intArrayOf(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface))
                    .setProfileLevels(arrayOf(MediaCodecInfo.CodecProfileLevel().apply {
                        profile = MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline; level = MediaCodecInfo.CodecProfileLevel.AVCLevel41
                    })).build(),
            ).build(),
        )
    }

    /** Each hub's thread ends with its test, so none touches Robolectric's camera (its callbacks are static) in the next. */
    @After fun release() {
        // Read before release(): a HandlerThread's looper is null once the thread has quit (a test may release its hub itself).
        val threads = hubs.mapNotNull { it.looper?.thread }
        hubs.forEach { it.release() }
        // A hub thread still running here would keep its availability callback registered into the next test.
        val stuck = threads.onEach { it.join(5_000) }.filter { it.isAlive }
        ShadowMediaCodecList.reset()
        check(stuck.isEmpty()) { "${stuck.size} hub thread(s) didn't end within 5 s of release()" }
    }

    private val logs = java.util.Collections.synchronizedList(mutableListOf<String>())

    private fun hub() = CameraHub(app, log = { logs += it }).also { hubs += it }

    private fun opened() = eventually("the camera opens") { synchronized(logs) { logs.any { it.startsWith("camera 0 open (") } } }

    private fun camera(id: String, facing: Int) {
        val c = ShadowCameraCharacteristics.newCameraCharacteristics()
        Shadow.extract<ShadowCameraCharacteristics>(c).set(CameraCharacteristics.LENS_FACING, facing)
        cameras.addCamera(id, c)
    }

    /**
     * Adds or removes a camera the way Android tells the hub: on the hub's thread, the handler it registered its
     * availability callback with. Robolectric calls that callback on whatever thread changes the camera list, so from
     * the test's thread it would race the hub's own work (closeCamera logs "camera closed" before it forgets openId).
     */
    private fun onHubThread(hub: CameraHub, change: () -> Unit) {
        Handler(hub.looper).post(change)
        settled(hub)
    }

    private fun eventually(what: String, check: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!check()) { assertTrue(what, System.currentTimeMillis() < end); Thread.sleep(20) }
    }

    private val noEncoder = StreamFit.Refused("The video encoder can't take the camera's 1280 × 720 picture")

    private fun presence(lost: CountDownLatch = CountDownLatch(1)) = object : FrameSink {
        override fun frame(frame: LumaFrame) {}
        override fun lost(why: String) = lost.countDown()
    }

    @Test fun withoutACameraTheStreamIsRefused() {
        assertEquals(StreamFit.Refused(CameraHub.NO_CAMERA), hub().streamFit)
    }

    /** F1's "not kept": a camera plugged in after the refusal is read. */
    @Test fun aCameraThatAppearsAfterTheRefusalIsRead() {
        val hub = hub()
        assertEquals(StreamFit.Refused(CameraHub.NO_CAMERA), hub.streamFit)
        onHubThread(hub) { camera("0", CameraCharacteristics.LENS_FACING_EXTERNAL) }
        eventually("the new camera is read") { hub.streamFit == StreamFit.Size(1280, 720) }
    }

    /** F2: kept sizes answer without reading the camera again. A fresh read would now find no encoder. */
    @Test fun keptSizesAnswerWithoutAnotherRead() {
        camera("0", CameraCharacteristics.LENS_FACING_FRONT)
        val hub = hub()
        assertEquals(StreamFit.Size(1280, 720), hub.streamFit)
        ShadowMediaCodecList.reset()
        assertEquals(StreamFit.Size(1280, 720), hub.streamFit)
    }

    /** The delta verifier on #485: a removable camera unplugged while the hub doesn't hold it is read again. */
    @Test fun aCameraUnpluggedWhileNobodyHoldsItIsReadAgain() {
        camera("1", CameraCharacteristics.LENS_FACING_EXTERNAL)
        val hub = hub()
        assertEquals(StreamFit.Size(1280, 720), hub.streamFit)
        onHubThread(hub) { cameras.removeCamera("1") } // after the hub registered for availability, on its thread too
        eventually("unplugged: no stream") { hub.streamFit == StreamFit.Refused(CameraHub.NO_CAMERA) }
    }

    /** F3: a camera that fails while open is read again (here: the encoder went meanwhile, so the new answer differs). */
    @Test fun aCameraThatFailsIsReadAgain() {
        camera("0", CameraCharacteristics.LENS_FACING_FRONT)
        val hub = hub()
        val lost = CountDownLatch(1)
        hub.open(5f, presence(lost)).use {
            opened()
            assertEquals(StreamFit.Size(1280, 720), hub.streamFit)
            ShadowMediaCodecList.reset()
            cameras.triggerDisconnect()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("presence hears the camera went", lost.await(5, TimeUnit.SECONDS))
            assertEquals(noEncoder, hub.streamFit)
        }
    }

    @Test fun anExternalCameraIsReadAgainOnceItCloses() {
        camera("0", CameraCharacteristics.LENS_FACING_EXTERNAL)
        val hub = hub()
        val held = hub.open(5f, presence())
        opened()
        assertEquals(StreamFit.Size(1280, 720), hub.streamFit)
        held.close()
        eventually("the camera closes") { synchronized(logs) { "camera closed" in logs } }
        onHubThread(hub) { cameras.removeCamera("0") }
        eventually("unplugged: no stream") { hub.streamFit == StreamFit.Refused(CameraHub.NO_CAMERA) }
    }

    /** The delta verifier on #485: a read that a drop overtook (the camera changed meanwhile) isn't kept. */
    @Test fun aReadThatADropOvertookIsNotKept() {
        val k = KeptRead<Int>()
        assertEquals(1, k.read("0") { k.drop(); 1 })
        assertEquals(null, k.kept())
        assertEquals(2, k.read("0") { 2 })
        assertEquals(2, k.kept("0"))
        assertEquals("another camera's", null, k.kept("1"))
    }

    // ------------------------------------------------------------ a silent camera is reopened (#599)

    private fun lines(prefix: String) = synchronized(logs) { logs.count { it.startsWith(prefix) } }
    private fun opens() = lines("camera 0 open (")
    private fun reopens() = lines("camera: reopen ")

    /** Runs what is due on the hub's thread now. Robolectric's clock is fake: delayed work waits for [advance]. */
    private fun settled(hub: CameraHub) = shadowOf(hub.looper).idle()

    private fun advance(hub: CameraHub, ms: Long) {
        ShadowSystemClock.advanceBy(Duration.ofMillis(ms))
        settled(hub)
    }

    /** Robolectric's camera configures the session and never sends a frame: the Mirror's stuck first session. */
    private fun silentCamera(): CameraHub {
        camera("0", CameraCharacteristics.LENS_FACING_FRONT)
        return hub()
    }

    /** #674: the Mirror's camera sent its first frame more than 5 s after it opened. Its first frame gets longer. */
    @Test fun aStartingCameraIsNotReopenedBeforeTheFirstFrameGrace() {
        val hub = silentCamera()
        hub.open(5f, presence()).use {
            opened(); settled(hub)
            advance(hub, CameraHub.SILENT_MS)
            assertEquals("a mid-run silence's 5 s don't apply to the first frame", 0, reopens())
            advance(hub, CameraHub.FIRST_FRAME_MS - CameraHub.SILENT_MS - 1)
            assertEquals(0, reopens())
            advance(hub, 1)
            assertEquals(1, reopens())
            assertEquals("camera: reopen 1 now (no first frame in 10 s)", synchronized(logs) { logs.first { it.startsWith("camera: reopen ") } })
            assertEquals("closed and opened again at once", 2, opens())
        }
    }

    /**
     * #674: presence starts its clock when the camera is asked for frames, not when the pane turned on. A silence is
     * reported to it before the reopen starts the camera again, so the new session's grace can't hide it.
     */
    @Test fun presenceHearsWhenTheCameraStartsAndOfASilenceBeforeTheReopen() {
        val hub = silentCamera()
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val sink = object : FrameSink {
            override fun frame(frame: LumaFrame) {}
            override fun lost(why: String) { events += "lost: $why" }
            override fun started() { events += "started" }
        }
        hub.open(5f, sink).use {
            opened(); settled(hub)
            assertEquals(listOf("started"), events.toList())
            advance(hub, CameraHub.FIRST_FRAME_MS)
            assertEquals(listOf("started", "lost: no first frame in 10 s", "started"), events.toList())
        }
    }

    /** The same with presence itself: its own check is still pending when the hub reopens, and the silence is reported anyway. */
    @Test fun presenceReportsASilenceTheHubCaughtFirst() {
        val hub = silentCamera()
        val readings = java.util.Collections.synchronizedList(mutableListOf<Pair<Boolean, Float>>())
        val problems = java.util.Collections.synchronizedList(mutableListOf<String>())
        val held = mutableListOf<Runnable>() // presence's own checks: never run here
        val presence = CameraPresence(
            hub, clock = android.os.SystemClock::elapsedRealtime, post = { it.run() }, after = { _, r -> synchronized(held) { held += r } },
            onProblem = { problems += it },
        )
        presence.start(app) { near, raw -> readings += near to raw }
        try {
            opened(); settled(hub)
            advance(hub, CameraHub.FIRST_FRAME_MS)
            assertEquals(1, reopens())
            assertEquals(listOf(false to -1f), readings.toList())
            assertEquals(listOf("the camera stopped: no first frame in 10 s"), problems.toList())
        } finally {
            presence.stop(app)
        }
    }

    @Test fun aRepeatedSilenceBacksOff() {
        val hub = silentCamera()
        hub.open(5f, presence()).use {
            opened(); settled(hub)
            advance(hub, CameraHub.FIRST_FRAME_MS)
            advance(hub, CameraHub.FIRST_FRAME_MS)
            assertEquals(2, reopens())
            assertTrue(synchronized(logs) { "camera: reopen 2 in 10 s (no first frame in 10 s)" in logs })
            assertEquals("the second waits", 2, opens())
            advance(hub, ReopenBackoff.FIRST_MS - 1)
            assertEquals(2, opens())
            advance(hub, 1)
            assertEquals(3, opens())
            advance(hub, CameraHub.FIRST_FRAME_MS)
            assertTrue("the wait doubles", synchronized(logs) { "camera: reopen 3 in 20 s (no first frame in 10 s)" in logs })
        }
    }

    @Test fun privacyMeansNoReopen() {
        val hub = silentCamera()
        val held = hub.open(5f, presence())
        opened(); settled(hub)
        advance(hub, CameraHub.SILENT_MS / 2)
        held.close(); hub.idle() // what the pane does when privacy goes on
        settled(hub)
        assertTrue(synchronized(logs) { "camera closed" in logs })
        advance(hub, CameraHub.FIRST_FRAME_MS * 10)
        assertEquals(0, reopens())
        assertEquals(1, opens())
    }

    /** Privacy on and off during a backoff, as the root did on the Mirror: the camera opens at once, with a fresh count. */
    @Test fun privacyEndsABackoff() {
        val hub = silentCamera()
        val held = hub.open(5f, presence())
        opened(); settled(hub)
        advance(hub, CameraHub.FIRST_FRAME_MS)
        advance(hub, CameraHub.FIRST_FRAME_MS)
        assertEquals("reopen 2 waits", 2, opens())
        held.close(); hub.idle(); settled(hub)
        hub.open(5f, presence()).use {
            settled(hub)
            assertEquals(3, opens())
            advance(hub, CameraHub.FIRST_FRAME_MS)
            assertTrue(synchronized(logs) { logs.last { it.startsWith("camera: reopen ") } == "camera: reopen 1 now (no first frame in 10 s)" })
        }
    }

    @Test fun theBackoffStartsAtOnceDoublesAndIsCapped() {
        val b = ReopenBackoff(firstMs = 10_000, capMs = 60_000)
        assertEquals(listOf(0L, 10_000L, 20_000L, 40_000L, 60_000L, 60_000L), List(6) { b.silence() })
        assertEquals(6, b.attempt)
        repeat(100) { b.silence() }
        assertEquals("no overflow", 60_000L, b.silence())
    }

    /** #674: the first frame after a start gets [CameraHub.FIRST_FRAME_MS]; once frames came, a silence gets [CameraHub.SILENT_MS]. */
    @Test fun theDeadlineGivesTheFirstFrameLongerThanAMidRunSilence() {
        val d = FrameDeadline(firstMs = 10_000, silentMs = 5_000)
        d.started(1_000)
        assertEquals(1, d.left(10_999))
        assertEquals(0, d.left(11_000))
        assertEquals("no first frame in 10 s", d.why(11_000))
        d.frame(12_000)
        assertEquals(1, d.left(16_999))
        assertEquals("no frames for 5 s", d.why(17_000))
        d.started(20_000)
        assertEquals("a new session's first frame gets the grace again", 10_000, d.left(20_000))
        assertEquals("but it is looked at every 5 s", 5_000, d.nextCheck(20_000))
    }

    /** What the hub does on every image: frames coming back start the count again. */
    @Test fun aFrameResetsTheBackoff() {
        val b = ReopenBackoff()
        b.silence(); b.silence()
        b.reset()
        assertEquals(0, b.attempt)
        assertEquals(0L, b.silence())
        assertEquals(1, b.attempt)
    }

    // ------------------------------------------------------------ blank frames are no frames (the Mirror, 2026-11-25)

    private fun onHub(hub: CameraHub, run: () -> Unit) { android.os.Handler(hub.looper).post(run); settled(hub) }

    private val noStill: () -> ByteArray = { throw AssertionError("a blank frame was made into a still") }
    private val noLuma: () -> LumaFrame = { throw AssertionError("a blank frame reached presence") }

    private fun blank(hub: CameraHub) = onHub(hub) { hub.onFrame(android.os.SystemClock.elapsedRealtime(), blank = true, luma = noLuma, jpeg = noStill) }

    /** A session that sends only blank frames is reopened like one that sends nothing, and presence never sees them. */
    @Test fun blankFramesCountAsNoFrames() {
        val hub = silentCamera()
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val sink = object : FrameSink {
            override fun frame(frame: LumaFrame) { events += "frame" }
            override fun lost(why: String) { events += "lost: $why" }
        }
        hub.open(5f, sink).use {
            opened(); settled(hub)
            blank(hub); blank(hub)
            assertEquals("logged once a session", 1, lines("camera: blank frame"))
            advance(hub, CameraHub.FIRST_FRAME_MS)
            assertEquals(1, reopens())
            assertEquals("camera: reopen 1 now (no first frame in 10 s, 2 blank)", synchronized(logs) { logs.first { it.startsWith("camera: reopen ") } })
            assertEquals(listOf("lost: no first frame in 10 s, 2 blank"), events.toList())
        }
    }

    /** Blank frames after good ones: the mid-run silence catches them, and the backoff isn't reset by them. */
    @Test fun blankFramesAfterGoodOnesAreASilence() {
        val hub = silentCamera()
        hub.open(5f, presence()).use {
            opened(); settled(hub)
            onHub(hub) { hub.onFrame(android.os.SystemClock.elapsedRealtime(), false, { LumaFrame(2, 2, 2, ByteArray(4)) }, noStill) }
            advance(hub, 1_000)
            blank(hub)
            advance(hub, CameraHub.SILENT_MS - 1_000)
            assertEquals("camera: reopen 1 now (no frames for 5 s, 1 blank)", synchronized(logs) { logs.first { it.startsWith("camera: reopen ") } })
        }
    }

    /** A still waits past a blank frame for a good one: Home Assistant never gets the green picture. */
    @Test fun aStillSkipsBlankFrames() {
        val hub = silentCamera()
        val got = java.util.concurrent.atomic.AtomicReference<ByteArray?>()
        val still = Thread { got.set(hub.jpeg()) }.apply { start() }
        // The still's request is on the hub's queue once its thread waits; frames posted after it come after it.
        eventually("the still waits") { still.state == Thread.State.TIMED_WAITING }
        opened(); settled(hub)
        blank(hub)
        assertTrue("a blank frame answers nothing", still.isAlive)
        onHub(hub) { hub.onFrame(android.os.SystemClock.elapsedRealtime(), false, { LumaFrame(2, 2, 2, ByteArray(4)) }, { byteArrayOf(1, 2, 3) }) }
        still.join(5_000)
        assertTrue(byteArrayOf(1, 2, 3).contentEquals(got.get()))
    }

    /** Only blank frames: the still fails as one with no frame does (null after [CameraHub.STILL_WAIT_MS]). */
    @Test fun aStillWithOnlyBlankFramesFails() {
        val hub = silentCamera()
        var got: ByteArray? = byteArrayOf(9)
        val still = Thread { got = hub.jpeg() }.apply { start() }
        eventually("the still waits") { still.state == Thread.State.TIMED_WAITING }
        opened(); settled(hub)
        blank(hub); blank(hub)
        still.join(CameraHub.STILL_WAIT_MS + 3_000)
        assertEquals(null, got)
    }

    // ------------------------------------------------------------ the reader on a streaming camera

    @Test fun theReaderIsInEveryRequestUnlessTheCameraStreams() {
        assertEquals("presence alone", 1, readerEvery(streaming = false, minFps = 30))
    }

    /** The Mirror streams at a steady 30 fps: the reader gets 1 frame in 10 (3 fps), the encoder all 30. */
    @Test fun aStreamingCameraGivesTheReaderAFewFrames() {
        assertEquals(10, readerEvery(streaming = true, minFps = 30))
        assertEquals(5, readerEvery(streaming = true, minFps = 15))
        assertEquals("a camera that may drop to 7 fps: 1 in 2, still 3.5 fps at its slowest", 2, readerEvery(streaming = true, minFps = 7))
        assertEquals("a fast one is capped at 1 in 30", 30, readerEvery(streaming = true, minFps = 120))
    }

    /**
     * CodeRabbit on #924: with no rate asked, the camera picks its own and may slow down in the dark, so the interval
     * comes from its slowest rate, never an assumed one. Too slow for the reader to keep 3 fps, or unknown: every request.
     */
    @Test fun aSlowOrUnknownFloorKeepsTheReaderInEveryRequest() {
        assertEquals("floor 5: 1 in 2 would be 2.5 fps", 1, readerEvery(streaming = true, minFps = 5))
        assertEquals("floor 1", 1, readerEvery(streaming = true, minFps = 1))
        assertEquals("unknown", 1, readerEvery(streaming = true, minFps = 0))
    }

    // ------------------------------------------------------------ a camera that won't open is tried at a pace

    @Test fun theConnectBackoffDoublesAndIsCapped() {
        val b = ConnectBackoff(firstMs = 5_000, capMs = 60_000, minGapMs = 2_000)
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 40_000L, 60_000L, 60_000L), List(6) { b.failed(0) })
        assertEquals(6, b.attempt)
        repeat(100) { b.failed(0) }
        assertEquals("no overflow", 60_000L, b.failed(0))
        b.reset()
        assertEquals(0, b.attempt)
        assertEquals(5_000L, b.failed(0))
    }

    /** A freed camera is tried at once, but never sooner than the gap after the last failure. */
    @Test fun aFreedCameraIsTriedAfterTheGap() {
        val b = ConnectBackoff(firstMs = 5_000, capMs = 60_000, minGapMs = 2_000)
        assertEquals("no failure yet: now", 100L, b.freed(100))
        b.failed(1_000)
        assertEquals(3_000L, b.freed(1_500))
        assertEquals(4_000L, b.freed(4_000))
    }

    /**
     * The Mirror, 2026-11-25: a camera another app held was tried every 5 s in bursts, and Android 6's media server
     * crashed. Now each failure waits longer, and nothing else that asks for the camera meanwhile tries it.
     */
    @Test fun aCameraThatWontOpenIsTriedAtAPace() {
        val hub = hub() // no camera: every open fails
        val tries = { lines("camera: this device has no camera") }
        hub.open(5f, presence()).use {
            settled(hub)
            assertEquals(1, tries())
            assertTrue(synchronized(logs) { "camera: try 1 failed; next in 5 s or once the camera is free" in logs })
            // More users while it waits (presence restarting, a still's linger): no extra tries.
            repeat(5) { hub.open(5f, presence()) }
            settled(hub)
            advance(hub, 4_999)
            assertEquals(1, tries())
            advance(hub, 1)
            assertEquals(2, tries())
            advance(hub, 9_999)
            assertEquals("the second wait doubles", 2, tries())
            advance(hub, 1)
            assertEquals(3, tries())
            advance(hub, 20_000)
            assertEquals("then 20 s", 4, tries())
            advance(hub, 39_999)
            assertEquals(4, tries())
            advance(hub, 1)
            assertEquals("then 40 s", 5, tries())
            advance(hub, 60_000)
            assertEquals("capped at 60 s", 6, tries())
        }
    }

    /** A camera that comes while the hub waits is opened at once, without the rest of the wait. */
    @Test fun aCameraThatComesDuringTheWaitOpensAtOnce() {
        val hub = hub()
        hub.open(5f, presence()).use {
            settled(hub)
            advance(hub, ConnectBackoff.FIRST_MS) // a second failure: the wait is now 10 s
            assertEquals(0, opens())
            advance(hub, ConnectBackoff.MIN_GAP_MS)
            onHubThread(hub) { camera("0", CameraCharacteristics.LENS_FACING_FRONT) }
            opened()
            assertTrue(synchronized(logs) { "camera: 0 is free again; opening now" in logs })
        }
    }
}
