/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.frame

import android.app.Application
import dev.glassworks.testing.RepoFiles
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild

/**
 * #454: the generic app on a lululemon Mirror names itself after the Mirror's frame, not after the board name its
 * community image reports. Every other device, another MSM8916 one included, keeps its own maker and model (#262).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DeviceFrameTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val mac = "02:00:00:A1:B2:C3"

    /** A device: its `Build` values and the system properties the app can read. */
    private fun device(manufacturer: String, model: String, props: Map<String, String> = emptyMap()): GenericAndroid {
        ShadowBuild.setManufacturer(manufacturer)
        ShadowBuild.setModel(model)
        return GenericAndroid(app, prop = { props[it].orEmpty() })
    }

    /** The props of the Mirror with the Glass Panes image, as mirror-root.md and home-app.md read them on the unit. */
    private val glassPanesSystem = mapOf(
        "ro.board.platform" to "msm8916",
        "ro.product.device" to "msm8916_64",
        "ro.build.fingerprint" to "Android/msm8916_64/msm8916_64:6.0.1/MMB29M/11120023:userdebug/test-keys",
    )
    private val mirror = glassPanesSystem + ("ro.bootimage.build.fingerprint" to "mirror/mirror/msm8916_64:6.0.1/IFC-6309-2.0-MIR.S22Q401/2:user/test-keys")

    @Test fun theMirrorGoesByItsFrame() {
        val frame = device("Android", "MSM8916 for arm64", mirror)
        assertEquals("lululemon-mirror-a1b2c3", frame.nodeName(mac))
        assertEquals("lululemon Studio Mirror", frame.friendlyName())
        assertEquals("lululemon", frame.hardware.manufacturer)
        assertEquals("Studio Mirror", frame.hardware.model)
        assertEquals("the mDNS board record names its frame", "lululemon-mirror", frame.board)
        assertEquals("its camera is capped at 720p (#679)", 1280 * 720, frame.cameraMaxPixels)
        assertEquals("its camera says 0 degrees but sits at 180", 180, frame.cameraSensorDegrees)
        assertEquals("it asks for portrait itself, whatever auto-rotate says (#686)", Orientation.PORTRAIT_FIXED, frame.orientation)
        assertEquals("its display comes back from Android's display off with lines across it", SleepMode.BACKLIGHT_OFF, frame.sleepMode)
    }

    @Test fun anotherMsm8916DeviceWithTheSameSystemImageIsNotAMirror() {
        val frame = device("Android", "MSM8916 for arm64", glassPanesSystem + ("ro.bootimage.build.fingerprint" to "qcom/msm8916_64/msm8916_64:6.0.1/MMB29M/1:user/release-keys"))
        assertEquals("msm8916-for-arm64-a1b2c3", frame.nodeName(mac))
        assertEquals("MSM8916 for arm64", frame.friendlyName())
        assertEquals("Android", frame.hardware.manufacturer)
        assertEquals("generic-android", frame.board)
        assertEquals(Orientation.ANY, frame.orientation)
        assertEquals(SleepMode.LOCK, frame.sleepMode)
    }

    @Test fun aMirrorBootImageOnAnotherBoardIsNotAMirror() {
        val props = mirror + ("ro.board.platform" to "msm8953")
        assertNull(DeviceFrame.of { props[it].orEmpty() })
    }

    @Test fun aDeviceWithoutAFrameKeepsTheCamerasOwnLimit() {
        assertEquals(null, device("Google", "Pixel Tablet").cameraMaxPixels)
        assertEquals("and goes by Android's sensor orientation", null, device("Google", "Pixel Tablet").cameraSensorDegrees)
    }

    @Test fun thePixelTabletKeepsItsOwnName() {
        val frame = device("Google", "Pixel Tablet", mapOf("ro.board.platform" to "gs201", "ro.bootimage.build.fingerprint" to "google/tangorpro/tangorpro:14/UQ1A/1:user/release-keys"))
        assertEquals("pixel-tablet-a1b2c3", frame.nodeName(mac))
        assertEquals("Pixel Tablet", frame.friendlyName())
        assertEquals("Google", frame.hardware.manufacturer)
        assertEquals("generic-android", frame.board)
        assertEquals("as its KNOWN row", Orientation.LANDSCAPE, frame.orientation)
        assertEquals(SleepMode.LOCK, frame.sleepMode)
    }

    @Test fun anUnknownDeviceWithNoPropertiesKeepsItsOwnName() {
        val frame = device("Samsung", "Galaxy Tab A")
        assertEquals("galaxy-tab-a-a1b2c3", frame.nodeName(mac))
        assertEquals("Galaxy Tab A", frame.friendlyName())
        assertEquals("Samsung", frame.hardware.manufacturer)
        assertEquals("generic-android", frame.board)
        assertEquals(Orientation.ANY, frame.orientation)
    }

    @Test fun onRobolectricItReadsTheRealPropertiesAndFindsNoFrame() {
        assertEquals("generic-android", GenericAndroid(app).board)
    }

    /** #139: Kiln's stamp in build.prop, else the Glass Panes image its fingerprint names (image-patch.md has both). */
    @Test fun theMirrorsFrameVersionComesFromKilnsStampElseItsImage() {
        val stamped = device("Android", "MSM8916 for arm64", mirror + mapOf(FrameRelease.ID_PROP to "lululemon-mirror", FrameRelease.VERSION_PROP to "1.0.0")).frameRelease!!
        assertEquals("lululemon-mirror", stamped.id)
        assertEquals("1.0.0", stamped.stamp)
        assertEquals(DeviceFrame.MIRROR_INSTALL, stamped.install)
        val image2 = device("Android", "MSM8916 for arm64", mirror).frameRelease!!
        assertNull(image2.stamp)
        assertEquals("Glass Panes image 2", image2.image)
        val image1 = mirror + ("ro.build.fingerprint" to "Android/msm8916_64/msm8916_64:6.0.1/MMB29M/11041605:userdebug/test-keys")
        assertEquals("Glass Panes image 1", device("Android", "MSM8916 for arm64", image1).frameRelease!!.image)
        val image3 = mirror + ("ro.build.fingerprint" to "Android/msm8916_64/msm8916_64:6.0.1/MMB29M/11212248:userdebug/test-keys")
        assertEquals("Glass Panes image 3", device("Android", "MSM8916 for arm64", image3).frameRelease!!.image)
        val other = mirror + ("ro.build.fingerprint" to "Android/msm8916_64/msm8916_64:6.0.1/MMB29M/1:userdebug/test-keys")
        assertNull("an image Kiln doesn't know isn't named", device("Android", "MSM8916 for arm64", other).frameRelease!!.image)
    }

    @Test fun theImageKilnFlashesIsNamed() {
        val ts = RepoFiles.file("frames/lululemon-mirror/installer/src/image.ts").readText()
        val fingerprint = Regex("""fingerprint: '([^']+)'""").find(ts)!!.groupValues[1]
        assertEquals("Glass Panes image 3", DeviceFrame.GLASS_PANES[fingerprint])
    }

    @Test fun aDeviceWithoutAFrameHasNoFrameReleases() {
        assertNull(device("Google", "Pixel Tablet").frameRelease)
        val props = mapOf(FrameRelease.ID_PROP to "lululemon-mirror", FrameRelease.VERSION_PROP to "1.0.0")
        assertNull("a stamp alone doesn't make a Mirror", device("Google", "Pixel Tablet", props).frameRelease)
    }

    @Test fun eachIsNamedAsItsFrameJson() {
        for (frame in DeviceFrame.ALL) {
            val json = JSONObject(RepoFiles.file("frames/${frame.id}/design/frame.json").readText())
            assertEquals(frame.id, "device", json.getString("kind"))
            assertEquals(frame.id, json.getString("name"), frame.name)
            assertEquals(frame.id, json.getString("brand"), frame.brand)
            val camera = json.optJSONObject("camera")
            assertEquals("${frame.id}: camera.maxPixels", camera?.takeIf { it.has("maxPixels") }?.getInt("maxPixels"), frame.cameraMaxPixels)
            assertEquals("${frame.id}: camera.sensorDegrees", camera?.takeIf { it.has("sensorDegrees") }?.getInt("sensorDegrees"), frame.cameraSensorDegrees)
            val display = json.getJSONObject("display")
            if (frame.orientation == Orientation.PORTRAIT_FIXED) {
                assertTrue("${frame.id}: a fixed portrait needs a portrait display", display.getInt("height") > display.getInt("width"))
            }
        }
    }
}
