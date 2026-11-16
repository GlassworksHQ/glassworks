/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.frame

import android.app.Application
import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDisplay

/**
 * #198: the generic frame asks the device how its screen stands and whether it takes a tap, so the lululemon Mirror
 * (portrait glass, `ro.sf.hwrotation=270`, no touchscreen) needs no frame code of its own.
 */
class GenericAndroidDeviceTest {
    @Test fun theNaturalOrientationIsTheScreenTurnedBackToRotation0() {
        assertEquals("a tablet as it stands", Orientation.LANDSCAPE, GenericAndroid.orientationOf(1280, 800, Surface.ROTATION_0))
        assertEquals("a tablet turned on its side", Orientation.LANDSCAPE, GenericAndroid.orientationOf(800, 1280, Surface.ROTATION_90))
        assertEquals(Orientation.LANDSCAPE, GenericAndroid.orientationOf(800, 1280, Surface.ROTATION_270))
        assertEquals("the Mirror: 1080x1920 at rotation 0", Orientation.PORTRAIT, GenericAndroid.orientationOf(1080, 1920, Surface.ROTATION_0))
        assertEquals("the Mirror, turned", Orientation.PORTRAIT, GenericAndroid.orientationOf(1920, 1080, Surface.ROTATION_90))
        assertEquals(Orientation.PORTRAIT, GenericAndroid.orientationOf(1080, 1920, Surface.ROTATION_180))
        assertEquals("square: landscape", Orientation.LANDSCAPE, GenericAndroid.orientationOf(1000, 1000, Surface.ROTATION_0))
    }

    @Test fun noTouchOnlyWhenAndroidListsDevicesAndNoneIsATouchscreen() {
        assertEquals("the Mirror: keys, no touchscreen", Input.NONE, GenericAndroid.inputOf(intArrayOf(1, 2)) { false })
        assertEquals(Input.TOUCH, GenericAndroid.inputOf(intArrayOf(1, 2)) { it == 2 })
        assertEquals("no devices listed: can't tell", Input.TOUCH, GenericAndroid.inputOf(intArrayOf()) { false })
    }
}

/**
 * The same rules on Robolectric's display. Robolectric's screen is a phone's (portrait at rotation 0, turned to 90
 * for `land`), so a tablet that stands landscape at rotation 0 sets its rotation back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GenericAndroidDisplayTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test @Config(qualifiers = "w1280dp-h800dp-land-mdpi")
    fun aLandscapeTabletIsLandscape() {
        shadowOf(ShadowDisplay.getDefaultDisplay()).setRotation(Surface.ROTATION_0)
        assertEquals(Orientation.LANDSCAPE, GenericAndroid(app).orientation)
    }

    @Test @Config(qualifiers = "w1280dp-h800dp-land-mdpi")
    fun aPhoneTurnedOnItsSideIsStillPortrait() = assertEquals(Orientation.PORTRAIT, GenericAndroid(app).orientation)

    @Test @Config(qualifiers = "w540dp-h960dp-port-xhdpi")
    fun aPortraitScreenIsPortrait() = assertEquals(Orientation.PORTRAIT, GenericAndroid(app).orientation)

    /** Robolectric lists no touchscreen device that the frame can count on: it keeps touch. */
    @Test fun onRobolectricItKeepsTouch() = assertEquals(Input.TOUCH, GenericAndroid(app).input)
}
