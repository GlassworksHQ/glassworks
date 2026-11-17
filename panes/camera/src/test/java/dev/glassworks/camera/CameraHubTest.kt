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
}
