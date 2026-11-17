/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.frame

import android.app.Application
import dev.glassworks.testing.RepoFiles
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    }

    @Test fun anotherMsm8916DeviceWithTheSameSystemImageIsNotAMirror() {
        val frame = device("Android", "MSM8916 for arm64", glassPanesSystem + ("ro.bootimage.build.fingerprint" to "qcom/msm8916_64/msm8916_64:6.0.1/MMB29M/1:user/release-keys"))
        assertEquals("msm8916-for-arm64-a1b2c3", frame.nodeName(mac))
        assertEquals("MSM8916 for arm64", frame.friendlyName())
        assertEquals("Android", frame.hardware.manufacturer)
        assertEquals("generic-android", frame.board)
    }

    @Test fun aMirrorBootImageOnAnotherBoardIsNotAMirror() {
        val props = mirror + ("ro.board.platform" to "msm8953")
        assertNull(DeviceFrame.of { props[it].orEmpty() })
    }

    @Test fun thePixelTabletKeepsItsOwnName() {
        val frame = device("Google", "Pixel Tablet", mapOf("ro.board.platform" to "gs201", "ro.bootimage.build.fingerprint" to "google/tangorpro/tangorpro:14/UQ1A/1:user/release-keys"))
        assertEquals("pixel-tablet-a1b2c3", frame.nodeName(mac))
        assertEquals("Pixel Tablet", frame.friendlyName())
        assertEquals("Google", frame.hardware.manufacturer)
        assertEquals("generic-android", frame.board)
    }

    @Test fun anUnknownDeviceWithNoPropertiesKeepsItsOwnName() {
        val frame = device("Samsung", "Galaxy Tab A")
        assertEquals("galaxy-tab-a-a1b2c3", frame.nodeName(mac))
        assertEquals("Galaxy Tab A", frame.friendlyName())
        assertEquals("Samsung", frame.hardware.manufacturer)
        assertEquals("generic-android", frame.board)
    }

    @Test fun onRobolectricItReadsTheRealPropertiesAndFindsNoFrame() {
        assertEquals("generic-android", GenericAndroid(app).board)
    }

    @Test fun eachIsNamedAsItsFrameJson() {
        for (frame in DeviceFrame.ALL) {
            val json = JSONObject(RepoFiles.file("frames/${frame.id}/design/frame.json").readText())
            assertEquals(frame.id, "device", json.getString("kind"))
            assertEquals(frame.id, json.getString("name"), frame.name)
            assertEquals(frame.id, json.getString("brand"), frame.brand)
        }
    }
}
