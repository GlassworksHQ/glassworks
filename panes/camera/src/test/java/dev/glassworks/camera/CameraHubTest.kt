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

    @After fun release() { hubs.forEach { it.release() }; ShadowMediaCodecList.reset() }

    private val logs = java.util.Collections.synchronizedList(mutableListOf<String>())

    private fun hub() = CameraHub(app, log = { logs += it }).also { hubs += it }

    private fun opened() = eventually("the camera opens") { synchronized(logs) { logs.any { it.startsWith("camera 0 open (") } } }

    private fun camera(id: String, facing: Int) {
        val c = ShadowCameraCharacteristics.newCameraCharacteristics()
        Shadow.extract<ShadowCameraCharacteristics>(c).set(CameraCharacteristics.LENS_FACING, facing)
        cameras.addCamera(id, c)
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
        camera("0", CameraCharacteristics.LENS_FACING_EXTERNAL)
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
        settled(hub) // the hub registers for availability on its thread: unplug only once it has
        assertEquals(StreamFit.Size(1280, 720), hub.streamFit)
        cameras.removeCamera("1")
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
        cameras.removeCamera("0")
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

    @Test fun aSilenceLeadsToExactlyOneReopen() {
        val hub = silentCamera()
        hub.open(5f, presence()).use {
            opened(); settled(hub)
            advance(hub, CameraHub.SILENT_MS - 1)
            assertEquals("not silent long enough yet", 0, reopens())
            advance(hub, 1)
            assertEquals(1, reopens())
            assertEquals("camera: reopen 1 now (no frames for 5 s)", synchronized(logs) { logs.first { it.startsWith("camera: reopen ") } })
            assertEquals("closed and opened again at once", 2, opens())
        }
    }

    @Test fun aRepeatedSilenceBacksOff() {
        val hub = silentCamera()
        hub.open(5f, presence()).use {
            opened(); settled(hub)
            advance(hub, CameraHub.SILENT_MS)
            advance(hub, CameraHub.SILENT_MS)
            assertEquals(2, reopens())
            assertTrue(synchronized(logs) { "camera: reopen 2 in 10 s (no frames for 5 s)" in logs })
            assertEquals("the second waits", 2, opens())
            advance(hub, ReopenBackoff.FIRST_MS - 1)
            assertEquals(2, opens())
            advance(hub, 1)
            assertEquals(3, opens())
            advance(hub, CameraHub.SILENT_MS)
            assertTrue("the wait doubles", synchronized(logs) { "camera: reopen 3 in 20 s (no frames for 5 s)" in logs })
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
        advance(hub, CameraHub.SILENT_MS * 10)
        assertEquals(0, reopens())
        assertEquals(1, opens())
    }

    /** Privacy on and off during a backoff, as the root did on the Mirror: the camera opens at once, with a fresh count. */
    @Test fun privacyEndsABackoff() {
        val hub = silentCamera()
        val held = hub.open(5f, presence())
        opened(); settled(hub)
        advance(hub, CameraHub.SILENT_MS)
        advance(hub, CameraHub.SILENT_MS)
        assertEquals("reopen 2 waits", 2, opens())
        held.close(); hub.idle(); settled(hub)
        hub.open(5f, presence()).use {
            settled(hub)
            assertEquals(3, opens())
            advance(hub, CameraHub.SILENT_MS)
            assertTrue(synchronized(logs) { logs.last { it.startsWith("camera: reopen ") } == "camera: reopen 1 now (no frames for 5 s)" })
        }
    }

    @Test fun theBackoffStartsAtOnceDoublesAndIsCapped() {
        val b = ReopenBackoff(firstMs = 10_000, capMs = 60_000)
        assertEquals(listOf(0L, 10_000L, 20_000L, 40_000L, 60_000L, 60_000L), List(6) { b.silence() })
        assertEquals(6, b.attempt)
        repeat(100) { b.silence() }
        assertEquals("no overflow", 60_000L, b.silence())
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
}
