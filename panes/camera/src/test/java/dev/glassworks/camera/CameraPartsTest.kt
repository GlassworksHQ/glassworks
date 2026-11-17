/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.camera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class CameraPartsTest {

    private val snapshots = CameraPolicy(privacy = false, use = CameraUse.PRESENCE_AND_SNAPSHOTS, onvif = true)
    private val presenceOnly = CameraPolicy(privacy = false, use = CameraUse.PRESENCE)

    @Test fun withoutTheCameraOrWithPrivacyNothingRuns() {
        assertEquals(CameraPlan.NOTHING, CameraPlan.of(snapshots, permitted = false, ownSensor = false, signedIn = true))
        assertEquals(CameraPlan.NOTHING, CameraPlan.of(snapshots.copy(privacy = true), permitted = true, ownSensor = false, signedIn = true))
    }

    @Test fun theScreenUsesTheCameraOnlyWithoutASensorOfItsOwn() {
        assertEquals(CameraPlan(detect = true, feedScreen = true, stream = false), CameraPlan.of(presenceOnly, true, ownSensor = false, signedIn = true))
        assertEquals(CameraPlan.NOTHING, CameraPlan.of(presenceOnly, true, ownSensor = true, signedIn = true))
    }

    @Test fun theStreamNeedsItsSwitchSnapshotsAndASignIn() {
        assertEquals(CameraPlan(true, true, true), CameraPlan.of(snapshots, true, ownSensor = false, signedIn = true))
        assertEquals("no default password", CameraPlan(true, true, false), CameraPlan.of(snapshots, true, ownSensor = false, signedIn = false))
        assertEquals("refused in Presence only", CameraPlan(true, true, false), CameraPlan.of(snapshots.copy(use = CameraUse.PRESENCE), true, false, true))
        assertEquals("motion for the ONVIF events even with a sensor of its own", CameraPlan(true, false, true), CameraPlan.of(snapshots, true, ownSensor = true, signedIn = true))
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

    @Test fun theStreamAsksForARangeThatTopsAtTheEncodersRate() {
        assertEquals("the steadiest one", 15..15, fpsRangeFor(listOf(7..15, 15..15, 15..30, 30..30), 15))
        assertEquals(7..15, fpsRangeFor(listOf(7..15, 7..30), 15))
        assertEquals("none tops at 15: the camera keeps its own rate", null, fpsRangeFor(listOf(15..30, 30..30), 15))
        assertEquals(null, fpsRangeFor(null, 15))
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

    @Test fun presenceOnALegacyCameraAsksForTheSlowestRangeThatCoversIt() {
        assertEquals("the lowest top, then the lowest floor", 7..15, presenceRangeFor(listOf(15..30, 30..30, 15..15, 7..15), 5))
        assertEquals(5..5, presenceRangeFor(listOf(5..5, 5..30), 5))
        assertEquals("none reaches presence's rate", null, presenceRangeFor(listOf(1..3), 5))
        assertEquals(null, presenceRangeFor(null, 5))
    }
}
