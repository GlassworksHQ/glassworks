/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.generic

import android.content.pm.ActivityInfo
import dev.glassworks.MainActivity
import dev.glassworks.engine.gecko.GeckoPinHarness
import dev.glassworks.engine.gecko.GeckoShadows
import dev.glassworks.frame.Orientation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild
import org.robolectric.shadows.ShadowSystemProperties

/**
 * #686: Android 6 turns the Mirror's auto-rotate on and its rotation to 0 on every update of the app, which turned the
 * dashboard sideways. The generic app on a Mirror now asks for portrait itself (`PORTRAIT`, which auto-rotate doesn't
 * turn), so the setting no longer matters. Every other device asks as before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    application = GenericApp::class,
    instrumentedPackages = ["org.mozilla.geckoview"],
    shadows = [
        GeckoShadows.ShadowRuntime::class, GeckoShadows.ShadowExtensionController::class,
        GeckoShadows.ShadowSessionController::class, GeckoShadows.ShadowSession::class, GeckoShadows.ShadowView::class,
    ],
)
class MirrorOrientationTest {
    @After fun tearDown() {
        ShadowSystemProperties.reset()
        ShadowBuild.reset()
    }

    private fun requested(): Int = GeckoPinHarness().use { it.activity.requestedOrientation }

    /** The Mirror's system properties with the Glass Panes image (as DeviceFrameTest's). */
    private fun aMirror() {
        ShadowBuild.setManufacturer("Android")
        ShadowBuild.setModel("MSM8916 for arm64")
        ShadowSystemProperties.override("ro.board.platform", "msm8916")
        ShadowSystemProperties.override("ro.bootimage.build.fingerprint", "mirror/mirror/msm8916_64:6.0.1/IFC-6309-2.0-MIR.S22Q401/2:user/test-keys")
    }

    /** Its panel is 1920x1080, so right after an update Android stands it landscape, at rotation 0. */
    @Test @Config(qualifiers = "w960dp-h540dp-land-xhdpi")
    fun theMirrorAsksForPortraitWhateverAutoRotateSays() {
        aMirror()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, requested())
    }

    @Test @Config(qualifiers = "w800dp-h1280dp-port-xhdpi")
    fun anotherMsm8916DeviceStillTurnsAsAndroidSays() {
        ShadowBuild.setModel("MSM8916 for arm64")
        ShadowSystemProperties.override("ro.board.platform", "msm8916")
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_USER, requested())
    }

    @Test @Config(qualifiers = "w800dp-h1280dp-port-xhdpi")
    fun aTabletStillTurnsAsAndroidSays() {
        ShadowBuild.setManufacturer("Example")
        ShadowBuild.setModel("Example Tab 10")
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_USER, requested())
    }

    @Test @Config(qualifiers = "w800dp-h1280dp-port-xhdpi")
    fun aPixelTabletStillStandsLandscape() {
        ShadowBuild.setManufacturer("Google")
        ShadowBuild.setModel("Pixel Tablet")
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, requested())
    }

    /** The one place each orientation becomes Android's request. Only the fixed one is new (#686). */
    @Test fun eachOrientationsRequest() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, MainActivity.screenOrientation(Orientation.LANDSCAPE))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, MainActivity.screenOrientation(Orientation.PORTRAIT))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_USER, MainActivity.screenOrientation(Orientation.ANY))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, MainActivity.screenOrientation(Orientation.PORTRAIT_FIXED))
    }
}
