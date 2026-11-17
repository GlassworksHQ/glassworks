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

    private fun hub() = CameraHub(app, log = {}).also { hubs += it }

    private fun camera(id: String, facing: Int) {
        val c = ShadowCameraCharacteristics.newCameraCharacteristics()
        Shadow.extract<ShadowCameraCharacteristics>(c).set(CameraCharacteristics.LENS_FACING, facing)
        cameras.addCamera(id, c)
    }

    private fun eventually(what: String, check: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!check()) { assertTrue(what, System.currentTimeMillis() < end); Thread.sleep(20) }
    }

    @Test fun withoutACameraTheStreamIsRefused() {
        assertEquals(StreamFit.Refused(CameraHub.NO_CAMERA), hub().streamFit)
    }

    @Test fun aCameraThatGoesIsReadAgainOnceOpeningItFails() {
        camera("0", CameraCharacteristics.LENS_FACING_FRONT)
        val hub = hub()
        assertEquals(StreamFit.Size(1280, 720), hub.streamFit)
        cameras.removeCamera("0")
        assertEquals("kept: the page's refresh asks no camera service", StreamFit.Size(1280, 720), hub.streamFit)
        val lost = CountDownLatch(1)
        hub.open(5f, object : FrameSink {
            override fun frame(frame: LumaFrame) {}
            override fun lost(why: String) = lost.countDown()
        }).use {
            assertTrue("opening a camera that went fails", lost.await(5, TimeUnit.SECONDS))
            assertEquals(StreamFit.Refused(CameraHub.NO_CAMERA), hub.streamFit)
        }
    }

    @Test fun anExternalCameraIsReadAgainOnceItCloses() {
        camera("0", CameraCharacteristics.LENS_FACING_EXTERNAL)
        val hub = hub()
        val presence = hub.open(5f, object : FrameSink {
            override fun frame(frame: LumaFrame) {}
            override fun lost(why: String) {}
        })
        eventually("the camera opens") { hub.frameRate() == 5 }
        assertEquals(StreamFit.Size(1280, 720), hub.streamFit)
        presence.close()
        eventually("the camera closes") { hub.frameRate() == 0 }
        cameras.removeCamera("0")
        eventually("unplugged: no stream") { hub.streamFit == StreamFit.Refused(CameraHub.NO_CAMERA) }
    }
}
