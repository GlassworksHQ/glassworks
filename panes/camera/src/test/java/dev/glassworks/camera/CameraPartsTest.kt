/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.camera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class CameraPartsTest {

    private val snapshots = CameraPolicy(privacy = false, use = CameraUse.PRESENCE_AND_SNAPSHOTS, onvif = true)
    private val presenceOnly = CameraPolicy(privacy = false, use = CameraUse.PRESENCE)

    @Test fun withoutTheCameraOrWithPrivacyNothingRuns() {
        assertEquals(CameraPlan.NOTHING, CameraPlan.of(snapshots, permitted = false, ownSensor = false, signedIn = true, streams = true))
        assertEquals(CameraPlan.NOTHING, CameraPlan.of(snapshots.copy(privacy = true), permitted = true, ownSensor = false, signedIn = true, streams = true))
    }

    @Test fun theScreenUsesTheCameraOnlyWithoutASensorOfItsOwn() {
        assertEquals(CameraPlan(detect = true, feedScreen = true, stream = false), CameraPlan.of(presenceOnly, true, ownSensor = false, signedIn = true, streams = true))
        assertEquals(CameraPlan.NOTHING, CameraPlan.of(presenceOnly, true, ownSensor = true, signedIn = true, streams = true))
    }

    @Test fun theStreamNeedsItsSwitchSnapshotsAndASignIn() {
        assertEquals(CameraPlan(true, true, true), CameraPlan.of(snapshots, true, ownSensor = false, signedIn = true, streams = true))
        assertEquals("no default password", CameraPlan(true, true, false), CameraPlan.of(snapshots, true, ownSensor = false, signedIn = false, streams = true))
        assertEquals("refused in Presence only", CameraPlan(true, true, false), CameraPlan.of(snapshots.copy(use = CameraUse.PRESENCE), true, false, true, streams = true))
        assertEquals("motion for the ONVIF events even with a sensor of its own", CameraPlan(true, false, true), CameraPlan.of(snapshots, true, ownSensor = true, signedIn = true, streams = true))
    }

    @Test fun nv21KeepsLumaRowsAndInterleavesVThenU() {
        // 4x2 picture, luma rows padded to 6; chroma 2x1, planar (pixel stride 1) with row stride 4.
        val y = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4, 99, 99, 5, 6, 7, 8, 99, 99))
        val u = ByteBuffer.wrap(byteArrayOf(10, 11, 99, 99))
        val v = ByteBuffer.wrap(byteArrayOf(20, 21, 99, 99))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 20, 10, 21, 11), yuvToNv21(4, 2, y, 6, u, v, 4, 1))
    }

    @Test fun nv21FromAlreadyInterleavedChroma() {
        // Pixel stride 2: U and V share a buffer offset by one, as most cameras deliver NV12/NV21.
        val y = ByteBuffer.wrap(ByteArray(8) { it.toByte() })
        val uv = byteArrayOf(10, 20, 11, 21)
        val u = ByteBuffer.wrap(uv)
        val v = ByteBuffer.wrap(byteArrayOf(20, 11, 21, 0))
        assertArrayEquals(byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 20, 10, 21, 11), yuvToNv21(4, 2, y, 4, u, v, 4, 2))
    }

    @Test fun stillsStandUpright() {
        // The Pixel Tablet docked in landscape: front sensor at 90, display at 270.
        assertEquals(0, stillRotation(90, 270, front = true))
        assertEquals(90, stillRotation(90, 0, front = true))
        assertEquals(270, stillRotation(270, 0, front = false))
        assertEquals(0, stillRotation(270, 270, front = false))
        assertEquals(180, stillRotation(90, 90, front = true))
        assertEquals("the Mirror: its sensor at 180 under a screen at rotation 3", 270, stillRotation(180, 270, front = false))
    }

    // ------------------------------------------------------------ blank frames (the Mirror's green stills, 2026-11-25)

    /** A 1280x720 YUV_420_888 image as the Mirror's camera gives it: Y, U and V rows 1280 apart, chroma interleaved. */
    private class Yuv(val w: Int = 1280, val h: Int = 720, val stride: Int = 1280, yAt: (x: Int, row: Int) -> Int, uvAt: (x: Int, row: Int) -> Int) {
        val y: ByteBuffer = ByteBuffer.allocate(stride * h)
        // One buffer for both chroma planes, as Android gives NV21: U at 0, V at 1, pixel stride 2.
        private val uv = ByteBuffer.allocate(stride * h / 2)
        val u: ByteBuffer get() = uv.duplicate()
        val v: ByteBuffer get() = (uv.duplicate().position(1) as ByteBuffer).slice()
        init {
            // Through the stride, padding too: what lies past the width is the caller's to fill.
            for (row in 0 until h) for (x in 0 until stride) y.put(row * stride + x, yAt(x, row).toByte())
            for (row in 0 until h / 2) for (x in 0 until stride) uv.put(row * stride + x, uvAt(x, row).toByte())
        }
        fun blank() = blankYuv(w, h, y, stride, u, v, stride, 2)
    }

    @Test fun anAllZeroFrameIsBlank() {
        assertTrue(Yuv(yAt = { _, _ -> 0 }, uvAt = { _, _ -> 0 }).blank())
    }

    /** What Home Assistant showed: solid green with a dark noisy strip. The strip's rows don't save the frame. */
    @Test fun aZeroFrameWithANoisyStripIsBlank() {
        val noise = java.util.Random(1)
        val strip = 345..360 // more rows than the spike's, across one of the sampled rows
        assertTrue(Yuv(yAt = { _, r -> if (r in strip) noise.nextInt(8) else 0 }, uvAt = { _, r -> if (r * 2 in strip) noise.nextInt(4) else 0 }).blank())
    }

    /**
     * What the Mirror's stream and stills showed on the test build (2026-11-25): a streaky third of picture on top,
     * two thirds all zero below. The streaks don't save the frame.
     */
    @Test fun twoThirdsZeroUnderAStreakyThirdIsBlank() {
        val noise = java.util.Random(3)
        assertTrue(Yuv(yAt = { _, r -> if (r < 240) 20 + noise.nextInt(200) else 0 }, uvAt = { _, r -> if (r < 120) 100 + noise.nextInt(60) else 0 }).blank())
    }

    /**
     * The same frame as the Mirror's reader got it on the next test build (2026-11-25): Android 6's camera shim draws
     * through the GPU, so the zero two thirds come out green (Y 79, U 83, V 71, give or take a step), not zero.
     */
    @Test fun twoThirdsDrawnGreenUnderAStreakyThirdIsBlank() {
        val noise = java.util.Random(5)
        assertTrue(Yuv(
            yAt = { _, r -> if (r < 240) 20 + noise.nextInt(200) else 78 + noise.nextInt(3) },
            uvAt = { x, r -> if (r < 120) 100 + noise.nextInt(60) else if (x % 2 == 0) 83 else 71 },
        ).blank())
    }

    /**
     * Four bands, each dead in a different plane only: drawn-green Y, then U, then V, then grey. Each plane alone has a
     * quarter of its rows dead, but no row is dead in all three, so it's a picture.
     */
    @Test fun deadRowsInDifferentPlanesDontAddUp() {
        fun band(row: Int) = row / 180
        assertFalse(Yuv(
            yAt = { _, r -> if (band(r) == 0) 79 else 128 },
            uvAt = { x, r -> when {
                x % 2 == 0 && band(r * 2) == 1 -> 83
                x % 2 == 1 && band(r * 2) == 2 -> 71
                else -> 128
            } },
        ).blank())
    }

    /** A flat grey wall reads Y 79 across whole rows, but its chroma is grey: not blank. */
    @Test fun aFlatGreyWallIsNotBlank() {
        assertFalse(Yuv(yAt = { _, _ -> 79 }, uvAt = { _, _ -> 128 }).blank())
    }

    /** A green scene near the drawn green, but with a real picture's spread across the row: not blank. */
    @Test fun aGreenSceneIsNotBlank() {
        val noise = java.util.Random(6)
        assertFalse(Yuv(
            yAt = { _, _ -> 70 + noise.nextInt(20) },
            uvAt = { x, _ -> (if (x % 2 == 0) 83 else 71) - 10 + noise.nextInt(21) },
        ).blank())
    }

    /** A fifth of zero rows (under the threshold) with a real picture above: not blank. */
    @Test fun aFewZeroRowsUnderAPictureAreNotBlank() {
        val noise = java.util.Random(4)
        assertFalse(Yuv(yAt = { _, r -> if (r < 600) 20 + noise.nextInt(200) else 0 }, uvAt = { _, r -> if (r < 300) 100 + noise.nextInt(60) else 0 }).blank())
    }

    /** The spike's dark room: Y around 4 with noise, chroma near 128. Dark is not blank. */
    @Test fun aDarkRoomIsNotBlank() {
        val noise = java.util.Random(2)
        assertFalse(Yuv(yAt = { _, _ -> 2 + noise.nextInt(5) }, uvAt = { _, _ -> 126 + noise.nextInt(5) }).blank())
    }

    /** A covered lens on a full-range camera can read Y 0, but its chroma is grey (128), not zero. */
    @Test fun blackWithGreyChromaIsNotBlank() {
        assertFalse(Yuv(yAt = { _, _ -> 0 }, uvAt = { _, _ -> 128 }).blank())
    }

    /** Zero chroma alone (a green-tinted picture) isn't blank either: the picture has luma. */
    @Test fun aPictureWithZeroChromaIsNotBlank() {
        assertFalse(Yuv(yAt = { x, _ -> x % 256 }, uvAt = { _, _ -> 0 }).blank())
    }

    /** Rows padded past the width: the padding is never read, so junk there doesn't matter. */
    @Test fun paddingIsNotRead() {
        assertTrue(Yuv(w = 1280, stride = 1344, yAt = { x, _ -> if (x >= 1280) 0x55 else 0 }, uvAt = { x, _ -> if (x >= 1280) 0x55 else 0 }).blank())
    }

    @Test fun aShortBufferIsNotBlankAndDoesNotThrow() {
        val empty = ByteBuffer.allocate(4)
        assertFalse(blankYuv(1280, 720, empty, 1280, empty, empty, 1280, 2))
    }

    @Test fun theOnvifMacIsStableLocallyAdministeredAndNotThePanels() {
        val panel = "02:00:00:00:00:01"
        val mac = onvifMac(panel)
        assertEquals(mac, onvifMac(panel.uppercase()))
        assertTrue(Regex("[0-9a-f]{2}(:[0-9a-f]{2}){5}").matches(mac))
        val first = mac.substring(0, 2).toInt(16)
        assertEquals("locally administered, unicast", 0x02, first and 0x03)
        assertNotEquals(panel, mac)
        assertNotEquals(mac, onvifMac("02:00:00:00:00:02"))
    }

    /** Every camera runs at its top rate: on the Mirror, 15–15 (from its own list) broke every frame (2026-11-25). */
    @Test fun aCameraIsAskedForItsSteadiestRangeAtItsTopRate() {
        assertEquals("the Mirror: steady 30, never 15–15", 30..30, cameraRangeFor(listOf(15..15, 24..24, 7..30, 30..30)))
        assertEquals("the steadiest at the top", 15..30, cameraRangeFor(listOf(7..30, 15..30, 7..15)))
        assertEquals(null, cameraRangeFor(null))
        assertEquals(null, cameraRangeFor(emptyList()))
    }

    @Test fun theSdpAnnouncesOnlyARateTheCameraIsAskedFor() {
        assertEquals(15, announcedFps(7..15))
        assertEquals("no a=framerate", 0, announcedFps(null))
    }

    @Test fun theHubReadsAtASizeTheCameraLists() {
        val hd = 1280 to 720
        assertEquals("listed: kept", hd, yuvSizeFor(listOf(640 to 480, 1280 to 720, 1920 to 1080), hd))
        assertEquals("not listed: the nearest in area", 1280 to 960, yuvSizeFor(listOf(640 to 480, 1280 to 960, 2592 to 1944), hd))
        assertEquals("never above 1080p", 640 to 480, yuvSizeFor(listOf(640 to 480, 2592 to 1944), hd))
        assertEquals("nothing up to 1080p: the smallest listed, never one it doesn't list", 2048 to 1536, yuvSizeFor(listOf(2592 to 1944, 2048 to 1536), hd))
        assertEquals(hd, yuvSizeFor(null, hd))
        assertEquals(hd, yuvSizeFor(emptyList(), hd))
    }

    /** #679: the frame's cap replaces 1080p. */
    @Test fun theFramesCapBoundsTheSize() {
        val hd = 1280 to 720
        assertEquals("listed but above the cap", 1024 to 576, yuvSizeFor(listOf(640 to 480, 1024 to 576, 1280 to 720), hd, maxPixels = 1024 * 576))
        assertEquals("nearest under the cap", 960 to 720, yuvSizeFor(listOf(640 to 480, 960 to 720, 1920 to 1080), 1920 to 1080, maxPixels = 1280 * 720))
    }

    /**
     * #679: a LEGACY camera runs its preview at the smallest sensor-shaped size that covers the reader, else its
     * largest. The Mirror's 4:3 sensor ran 1920x1080 for a 1280x720 reader; a 4:3 reader is the preview itself.
     */
    @Test fun aLegacyCameraReadsAtTheSensorsShapeUnderTheCap() {
        val mirrorLike = listOf(1280 to 720, 1024 to 768, 800 to 600, 640 to 480, 320 to 240)
        val sensor = 2592 to 1944
        assertEquals(1024 to 768, yuvSizeFor(mirrorLike, 1280 to 720, maxPixels = 1280 * 720, sensor = sensor))
        assertEquals("the cap still holds", 800 to 600, yuvSizeFor(mirrorLike, 1280 to 720, maxPixels = 640 * 480 + 200_000, sensor = sensor))
        assertEquals("a 16:9 sensor keeps 1280x720", 1280 to 720, yuvSizeFor(mirrorLike, 1280 to 720, sensor = 1920 to 1080))
        assertEquals("no size of the sensor's shape: as before", 1280 to 720, yuvSizeFor(listOf(1280 to 720, 640 to 360), 1280 to 720, sensor = sensor))
        assertEquals("not LEGACY: as before", 1280 to 720, yuvSizeFor(mirrorLike, 1280 to 720, maxPixels = 1280 * 720))
        assertEquals("an encoder that refuses 1024x768: the next sensor-shaped size it takes", 800 to 600,
            yuvSizeFor(mirrorLike, 1280 to 720, maxPixels = 1280 * 720, sensor = sensor) { it != 1024 to 768 })
        assertEquals("none it takes: as before, so a stream that worked still does", 1280 to 720,
            yuvSizeFor(mirrorLike, 1280 to 720, maxPixels = 1280 * 720, sensor = sensor) { it == 1280 to 720 })
        assertTrue(legacyAspect(1024 to 768, sensor))
        assertFalse(legacyAspect(1280 to 720, sensor))
    }

    /** #679: the device's own encoder first, when it takes the size; Android's order within each group. */
    @Test fun theHardwareEncoderComesFirst() {
        val any = { _: Int, _: Int -> true }
        val google = AvcEncoder("OMX.google.h264.encoder", hardware = false, listsSurface = true, takes = any)
        val qcom = AvcEncoder("OMX.qcom.video.encoder.avc", hardware = true, listsSurface = true, takes = any)
        val oldQcom = AvcEncoder("OMX.qcom.old.avc", hardware = true, listsSurface = false, takes = any)
        assertEquals(listOf(qcom, oldQcom, google).map { it.name }, encoderOrder(listOf(google, oldQcom, qcom), 1280, 720).map { it.name })
        val small = AvcEncoder("OMX.small.avc", hardware = true, listsSurface = true, takes = { w, h -> w * h <= 640 * 480 })
        assertEquals("one that can't take the size isn't tried", listOf(google.name), encoderOrder(listOf(small, google), 1280, 720).map { it.name })
        assertTrue(encoderOrder(emptyList(), 1280, 720).isEmpty())
    }

    @Test fun softwareCodecsAreNamedAsAospNamesThem() {
        for (sw in listOf("OMX.google.h264.encoder", "c2.android.avc.encoder", "OMX.Google.H264.Encoder", "ffmpeg.avc")) assertTrue(sw, isSoftwareCodec(sw))
        for (hw in listOf("OMX.qcom.video.encoder.avc", "c2.exynos.h264.encoder", "OMX.MTK.VIDEO.ENCODER.AVC")) assertFalse(hw, isSoftwareCodec(hw))
    }

    /** #479: Android 6's emulator camera (LEGACY) lists no 1280x720; the stream runs at the size the hub reads. */
    @Test fun aLegacyCameraStreamsAtTheSizeItLists() {
        val legacy = listOf(640 to 480, 320 to 240, 176 to 144)
        val reader = yuvSizeFor(legacy, CameraHub.WIDTH to CameraHub.HEIGHT)
        assertEquals(StreamFit.Size(640, 480), streamFitFor(reader, legacy) { _, _ -> true })
        assertEquals("a camera that doesn't list encoder sizes", StreamFit.Size(640, 480), streamFitFor(reader, null) { _, _ -> true })
        val noSurface = streamFitFor(reader, listOf(320 to 240)) { _, _ -> true }
        assertEquals("The camera can't send its 640 × 480 picture to a video encoder", (noSurface as StreamFit.Refused).why)
        val noEncoder = streamFitFor(reader, legacy) { w, h -> w * h <= 320 * 240 }
        assertEquals("The video encoder can't take the camera's 640 × 480 picture", (noEncoder as StreamFit.Refused).why)
        assertEquals("a camera that can't stream runs presence alone", CameraPlan(true, true, false),
            CameraPlan.of(snapshots, true, ownSensor = false, signedIn = true, streams = false))
    }

}
